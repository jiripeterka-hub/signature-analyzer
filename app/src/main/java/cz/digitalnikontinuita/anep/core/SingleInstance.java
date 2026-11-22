package cz.digitalnikontinuita.anep.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Soubor předaný při spuštění už běžící instanci (požadavek uživatele 22. 11. 2026): položka
 * „Otevřít v AnEP“ v nabídce Průzkumníka dosud otevírala pokaždé nové okno.
 *
 * <p>Druhé spuštění se souborem zjistí, že AnEP už běží, předá mu cestu a skončí dřív, než
 * nastartuje JavaFX. Spuštění bez souboru (z nabídky Start) otevírá novou instanci jako dosud —
 * kdo chce dvě okna vedle sebe, má je mít.
 *
 * <p>Bez sítě, jen soubory ve složce dat uživatele ({@link AppDirs#dataDir()}{@code \instance}):
 * <ul>
 *   <li>běžící instance drží zámek souboru {@code instance.lock} — operační systém ho uvolní, když
 *       proces skončí, i pádem, takže nic nezůstane viset;</li>
 *   <li>druhé spuštění zapíše cestu do souboru {@code *.req} ve složce {@code inbox} a počká, až si
 *       ho běžící instance vyzvedne (smaže); když se to do tří sekund nestane, požadavek stáhne
 *       a otevře vlastní okno.</li>
 * </ul>
 * Složka je pod účtem uživatele, takže jiný účet na témž počítači nic nepředá. Přenáší se jen
 * cesta k souboru; běžící instance ho vloží do pole pro dokument, nic neověřuje ani nespouští.
 *
 * <p>Proč ne socket: bezpečnostní dokumentace slibuje, že AnEP nemá žádný naslouchající port,
 * a ani místní port na 127.0.0.1 by to už nebyla pravda. Unixový socket (AF_UNIX) port nemá,
 * jenže na počítači uživatele jeho soubor po zavření nešel ve složce profilu smazat (Win32 chyba
 * 1920, ani {@code del}, ani otevřením jako reparse point; v {@code %TEMP%} ano — zřejmě filtr
 * souborového systému) a zbylý socket pak zablokoval každé další spuštění. Měřeno 22. 11. 2026.
 */
public final class SingleInstance {

    private SingleInstance() { }

    /** Jak dlouho čekat, než běžící instance požadavek vyzvedne. */
    private static final Duration HANDOFF_TIMEOUT = Duration.ofSeconds(3);
    /** Požadavek starší než tohle při startu nepatří nikomu, kdo ještě čeká. */
    private static final Duration STALE_REQUEST = Duration.ofSeconds(10);
    private static final String REQ = ".req";
    /** Delší cestu Windows nepřijmou. */
    private static final int MAX_PATH = 32 * 1024;

    /**
     * Zámky držené po celý běh aplikace; uvolní je až konec procesu. Seznam, ne jedna položka:
     * kanál, na který nic neukazuje, by mohl zavřít úklid paměti a zámek s ním.
     */
    private static final List<FileLock> HELD = new java.util.ArrayList<>();

    static Path instanceDir() {
        return AppDirs.dataDir().resolve("instance");
    }

    /** První argument, který vypadá jako cesta k souboru (ne přepínač), nebo {@code null}. */
    static String fileArgument(List<String> args) {
        if (args == null) return null;
        for (String a : args) {
            if (a != null && !a.isBlank() && !a.startsWith("-")) return a.strip();
        }
        return null;
    }

    /**
     * Pošle soubor z argumentů běžící instanci.
     *
     * @return {@code true}, když ho běžící instance převzala — volající pak skončí
     */
    public static boolean forward(List<String> args) {
        String file = fileArgument(args);
        if (file == null) return false;
        return send(instanceDir(), file);
    }

    static boolean send(Path dir, String path) {
        if (!running(dir)) return false;
        Path inbox = dir.resolve("inbox");
        Path req = inbox.resolve(UUID.randomUUID() + REQ);
        try {
            Files.createDirectories(inbox);
            // Nejdřív pod jiným jménem a pak přesunem: běžící instance nesmí přečíst půlku.
            Path tmp = inbox.resolve(UUID.randomUUID() + ".tmp");
            Files.writeString(tmp, path, StandardCharsets.UTF_8);
            Files.move(tmp, req, StandardCopyOption.ATOMIC_MOVE);
            Instant deadline = Instant.now().plus(HANDOFF_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                if (!Files.exists(req)) return true;
                Thread.sleep(30);
            }
            // Nevyzvednuto: stáhnout. Když se to nepovede, protože ho mezitím vzala, převzala ho.
            return !Files.deleteIfExists(req);
        } catch (IOException e) {
            trace("send", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Drží zámek v {@code dir} jiný proces (nebo tahle JVM, v testech)? */
    static boolean running(Path dir) {
        Path lockFile = dir.resolve("instance.lock");
        if (!Files.exists(lockFile)) return false;
        try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
            FileLock probe = ch.tryLock();
            if (probe == null) return true;
            probe.release();
            return false;
        } catch (OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            trace("running", e);
            return false;
        }
    }

    /**
     * Začne přijímat soubory od dalších spuštění. Když zámek drží jiná instance, nebo se
     * složku nepodaří založit, aplikace běží dál jako dosud — každé další spuštění pak otevře
     * vlastní okno.
     *
     * @param onFile dostane cestu z každého dalšího spuštění; volá se na vlákně příjmu
     */
    public static void serve(Consumer<String> onFile) {
        serve(instanceDir(), onFile);
    }

    /** @return {@code true}, když tahle instance začala soubory přijímat */
    static synchronized boolean serve(Path dir, Consumer<String> onFile) {
        try {
            Path inbox = dir.resolve("inbox");
            Files.createDirectories(inbox);
            FileChannel ch = FileChannel.open(dir.resolve("instance.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock l;
            try {
                l = ch.tryLock();
            } catch (OverlappingFileLockException e) {
                l = null;
            }
            if (l == null) {
                ch.close();
                return false;
            }
            HELD.add(l);
            WatchService watch = FileSystems.getDefault().newWatchService();
            inbox.register(watch, StandardWatchEventKinds.ENTRY_CREATE);
            // Až po registraci: požadavek, který přišel mezi zámkem a registrací, se tu nezapomene.
            // Starší požadavky zbyly po spuštění, které skončilo dřív, než je stáhlo.
            drain(inbox, onFile, true);
            Thread t = new Thread(() -> watchLoop(watch, inbox, onFile), "single-instance");
            t.setDaemon(true);
            t.start();
            return true;
        } catch (IOException | RuntimeException e) {
            trace("serve", e);
            return false;
        }
    }

    private static void watchLoop(WatchService watch, Path inbox, Consumer<String> onFile) {
        while (true) {
            WatchKey key;
            try {
                key = watch.take();
            } catch (InterruptedException e) {
                return;
            }
            key.pollEvents();
            drain(inbox, onFile, false);
            if (!key.reset()) return;
        }
    }

    private static void drain(Path inbox, Consumer<String> onFile, boolean dropStale) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(inbox, "*" + REQ)) {
            for (Path req : ds) {
                try {
                    FileTime made = Files.getLastModifiedTime(req);
                    boolean stale = dropStale && made.toInstant()
                            .isBefore(Instant.now().minus(STALE_REQUEST));
                    String path = Files.size(req) <= MAX_PATH && !stale
                            ? Files.readString(req, StandardCharsets.UTF_8) : null;
                    // Smazáním se odesílateli potvrdí převzetí; kdo smaže první, ten ho má.
                    if (!Files.deleteIfExists(req)) continue;
                    if (path != null && !path.isBlank()) onFile.accept(path.strip());
                } catch (IOException | RuntimeException e) {
                    // Jeden vadný požadavek nesmí zastavit příjem dalších.
                    trace("drain", e);
                }
            }
        } catch (IOException e) {
            trace("drain", e);
        }
    }

    /** Proč předání neprošlo — jen při {@code -Danep.timing=true}, jako ostatní měření startu. */
    private static void trace(String where, Throwable e) {
        if (StartupTrace.isEnabled()) System.err.println("[single-instance] " + where + ": " + e);
    }
}
