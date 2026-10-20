package cz.digitalnikontinuita.anep.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Naposledy ověřované soubory — nejvýš {@link #LIMIT} položek, nejnovější první.
 *
 * <p>Položkou je <b>dvojice</b>: dokument a k němu případný externí podpis (nebo externí obsah).
 * Tatáž dvojice ověřená znovu se jen přesune na začátek; týž dokument s jiným externím
 * souborem je jiná položka.
 *
 * <p>Ukládá se zvlášť, do {@code recent-files.properties} vedle {@code settings.properties}:
 * nastavení říká, s čím aplikace pracuje, kdežto tohle je jen stopa po práci, a smazat jedno
 * nesmí sáhnout na druhé.
 *
 * <p>Nezapisují se dočasné soubory samotné aplikace (viz {@link #isTemporary}). Vznikají, když se
 * ověřuje prvek vytažený z kontejneru, a při ukončení aplikace mizí — v seznamu by po restartu
 * zbyla jen mrtvá položka.
 */
public final class RecentFiles {

    /** Kolik položek se pamatuje. */
    public static final int LIMIT = 10;

    /** Dokument a případný externí soubor k němu ({@code null}, když žádný nebyl). */
    public record Entry(File document, File external) {
        public Entry {
            Objects.requireNonNull(document, "document");
        }

        /** Dají se oba soubory pořád otevřít? */
        public boolean exists() {
            return document.isFile() && (external == null || external.isFile());
        }
    }

    private static final Path FILE;

    static {
        FILE = AppDirs.dataDir().resolve("recent-files.properties");
    }

    private RecentFiles() { }

    /** Uložený seznam; prázdný, když žádný není nebo se nedá přečíst. */
    public static List<Entry> load() {
        return load(FILE);
    }

    /**
     * Zapíše dvojici na začátek seznamu a seznam uloží. Chyba zápisu se spolkne — o ověření
     * nerozhoduje, a hlásit ji uživateli by ho rušilo kvůli věci, kterou si nevyžádal.
     */
    public static void remember(File document, File external) {
        if (document == null || isTemporary(document)
                || (external != null && isTemporary(external))) {
            return;
        }
        try {
            save(FILE, add(load(FILE), new Entry(absolute(document), absolute(external))));
        } catch (IOException ignored) {
            // viz javadoc
        }
    }

    /** Smaže celý seznam. */
    public static void clear() {
        try {
            Files.deleteIfExists(FILE);
        } catch (IOException ignored) {
            // nic, co by stálo za hlášení
        }
    }

    /** Čistá funkce za {@link #remember}: nová položka první, bez duplicit, nejvýš {@link #LIMIT}. */
    static List<Entry> add(List<Entry> current, Entry entry) {
        List<Entry> out = new ArrayList<>(LIMIT);
        out.add(entry);
        for (Entry e : current) {
            if (out.size() >= LIMIT) break;
            if (!e.equals(entry)) out.add(e);
        }
        return out;
    }

    static List<Entry> load(Path file) {
        List<Entry> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) return out;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            return out;
        }
        for (int i = 0; i < LIMIT; i++) {
            String doc = p.getProperty(i + ".document");
            if (doc == null || doc.isBlank()) continue;
            String ext = p.getProperty(i + ".external");
            out.add(new Entry(new File(doc),
                    ext == null || ext.isBlank() ? null : new File(ext)));
        }
        return out;
    }

    static void save(Path file, List<Entry> entries) throws IOException {
        Files.createDirectories(file.getParent());
        Properties p = new Properties();
        for (int i = 0; i < entries.size() && i < LIMIT; i++) {
            Entry e = entries.get(i);
            p.setProperty(i + ".document", e.document().getPath());
            if (e.external() != null) p.setProperty(i + ".external", e.external().getPath());
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "AnEP — naposledy ověřované soubory");
        }
    }

    private static File absolute(File f) {
        return f == null ? null : f.getAbsoluteFile();
    }

    /**
     * Je to dočasný soubor <b>samotné aplikace</b>? Ty mají v dočasném adresáři předponu
     * {@code anep-} (prvek vytažený z kontejneru, náhled protokolu…) a při ukončení mizí.
     *
     * <p>Jen ty, ne celý dočasný adresář: tam si ukládají soubory i jiné programy a nechávají je
     * tam. Nález uživatele 2. 10. 2026 — PDF přetažené z OneNotu leží v
     * {@code Temp\OneNote\16.0\Exported\…} a do seznamu se nedostalo, ačkoli se k němu vrátit dá.
     */
    static boolean isTemporary(File f) {
        return isTemporary(f, Path.of(System.getProperty("java.io.tmpdir")));
    }

    static boolean isTemporary(File f, Path tmpDir) {
        try {
            Path tmp = tmpDir.toAbsolutePath().normalize();
            Path p = f.getAbsoluteFile().toPath().normalize();
            if (!p.startsWith(tmp) || p.getNameCount() <= tmp.getNameCount()) return false;
            return p.getName(tmp.getNameCount()).toString().startsWith("anep-");
        } catch (RuntimeException e) {
            return false;
        }
    }
}
