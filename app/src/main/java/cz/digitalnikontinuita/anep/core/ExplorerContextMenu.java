package cz.digitalnikontinuita.anep.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Položka „Otevřít v AnEP“ v nabídce Průzkumníka Windows po pravém kliknutí na soubor
 * (požadavek uživatele 22. 11. 2026).
 *
 * <p>Instalátor MSI zapisuje položku sám ({@code main.wxs}, klíč pod {@code HKLM}). Přenosná
 * verze instalátor nemá, a proto ji zapisuje tlačítko v Nastavení — do {@code HKCU}, bez práv
 * správce. Je to klasická položka nabídky: ve Windows 11 ji Průzkumník ukáže až pod „Zobrazit
 * další možnosti“; do zkrácené nabídky se dostanou jen aplikace s identitou balíčku MSIX.
 *
 * <p>S registrem se mluví přes {@code reg.exe} a soubory {@code .reg}, které jsou v UTF-16:
 * výstup {@code reg query} je v kódové stránce konzole a cestu s diakritikou by pokazil,
 * a uvozovky v argumentech {@code reg add} Java na Windows předává nespolehlivě.
 */
public final class ExplorerContextMenu {

    private ExplorerContextMenu() { }

    /** Klíč položky bez kořene; tentýž zapisuje instalátor MSI. */
    static final String KEY = "Software\\Classes\\*\\shell\\AnEP";
    /** Text položky v nabídce — tentýž jako v {@code main.wxs}. */
    public static final String LABEL = "Otevřít v AnEP";

    /** Kde je položka zapsaná a kam vede. */
    public record State(Path userExe, Path machineExe) {
        /** Položku pro tohoto uživatele zapsala přenosná verze (tlačítkem v Nastavení). */
        public boolean userEntry() { return userExe != null; }
        /** Položku zapsal instalátor MSI (pro celý počítač). */
        public boolean machineEntry() { return machineExe != null; }
    }

    /**
     * Spustitelný soubor běžící aplikace, nebo {@code null}, když AnEP neběží ze sestavení
     * (spuštění z vývojového prostředí). Spouštěč jpackage nastavuje {@code jpackage.app-path}.
     */
    public static Path currentExe() {
        String p = System.getProperty("jpackage.app-path");
        if (p == null || p.isBlank()) return null;
        Path exe = Path.of(p);
        return Files.isRegularFile(exe) ? exe : null;
    }

    /** Jde o Windows? Jinde se položka nenabízí. */
    public static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    public static State read() {
        return new State(readExe("HKCU"), readExe("HKLM"));
    }

    /** Zapíše položku pro tohoto uživatele tak, aby vedla na {@code exe}. */
    public static void install(Path exe) throws IOException {
        Path reg = Files.createTempFile("anep-menu", ".reg");
        try {
            Files.writeString(reg, regFile(exe), StandardCharsets.UTF_16LE);
            if (run("reg", "import", reg.toString()) != 0) {
                throw new IOException("Zápis do registru se nepodařil.");
            }
        } finally {
            Files.deleteIfExists(reg);
        }
    }

    /** Odebere položku zapsanou pro tohoto uživatele; položky instalátoru se nedotkne. */
    public static void uninstall() throws IOException {
        if (run("reg", "delete", "HKCU\\" + KEY, "/f") != 0 && readExe("HKCU") != null) {
            throw new IOException("Odebrání z registru se nepodařilo.");
        }
    }

    /**
     * Po přesunutí přenosné verze vede položka do prázdna. Když soubor, na který ukazuje,
     * už neexistuje, přesměruje ji na právě běžící aplikaci. Existující cíl se nepřepisuje:
     * kdo má dvě kopie, ať o položce rozhoduje tlačítkem, ne tím, kterou spustil naposled.
     *
     * @return {@code true}, když se položka opravila
     */
    public static boolean repairIfMoved() {
        if (!supported()) return false;
        Path exe = currentExe();
        if (exe == null) return false;
        Path registered = readExe("HKCU");
        if (registered == null || Files.exists(registered)) return false;
        try {
            install(exe);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Obsah souboru .reg pro položku vedoucí na {@code exe}. */
    static String regFile(Path exe) {
        String e = exe.toAbsolutePath().toString();
        String key = "HKEY_CURRENT_USER\\" + KEY;
        return "\uFEFFWindows Registry Editor Version 5.00\r\n\r\n"
                + "[" + key + "]\r\n"
                + "@=" + regString(LABEL) + "\r\n"
                + "\"Icon\"=" + regString("\"" + e + "\",0") + "\r\n\r\n"
                + "[" + key + "\\command]\r\n"
                + "@=" + regString("\"" + e + "\" \"%1\"") + "\r\n";
    }

    private static String regString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final Pattern COMMAND = Pattern.compile(
            "(?m)^@=\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$");

    /** Cesta k programu z příkazu položky pod daným kořenem, nebo {@code null}. */
    static Path readExe(String root) {
        if (!supported()) return null;
        Path reg = null;
        try {
            reg = Files.createTempFile("anep-menu", ".reg");
            if (run("reg", "export", root + "\\" + KEY + "\\command", reg.toString(), "/y") != 0) {
                return null;
            }
            return exeFromExport(Files.readString(reg, StandardCharsets.UTF_16LE));
        } catch (IOException e) {
            return null;
        } finally {
            if (reg != null) try { Files.deleteIfExists(reg); } catch (IOException ignored) { }
        }
    }

    /** Z exportu klíče {@code command} vytáhne cestu k programu (první řetězec v uvozovkách). */
    static Path exeFromExport(String export) {
        Matcher m = COMMAND.matcher(export.replace("\uFEFF", ""));
        if (!m.find()) return null;
        String cmd = m.group(1).replaceAll("\\\\(.)", "$1");
        String exe;
        if (cmd.startsWith("\"")) {
            int end = cmd.indexOf('"', 1);
            if (end < 0) return null;
            exe = cmd.substring(1, end);
        } else {
            int sp = cmd.indexOf(' ');
            exe = sp < 0 ? cmd : cmd.substring(0, sp);
        }
        try {
            return exe.isBlank() ? null : Path.of(exe);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int run(String... cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("reg.exe neodpověděl");
            }
            return p.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
