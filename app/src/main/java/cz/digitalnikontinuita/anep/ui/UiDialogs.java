package cz.digitalnikontinuita.anep.ui;

import javafx.scene.control.Alert;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;

/**
 * Dvě drobnosti, které potřebuje každé okno prohlížející jeden prvek: hláška o chybě a uložení
 * bajtů do souboru.
 *
 * <p>Bydlí zvlášť, protože prohlížeč CRL, prohlížeč certifikátů i výčet v Analýze dělají totéž
 * a třetí opis téhož dialogu by se rozešel s prvními dvěma při první změně znění.
 */
final class UiDialogs {

    private UiDialogs() { }

    /** Hláška o tom, že se něco nepodařilo. */
    static void error(String header, String message) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("Chyba");
        a.setHeaderText(header);
        a.setContentText(message);
        a.showAndWait();
    }

    /**
     * Uloží bajty do souboru, který si uživatel vybere.
     *
     * @param defaultName jméno předvyplněné v dialogu
     * @param filterDesc  popis typu, např. {@code "CRL seznam (*.crl)"}
     * @param filterExt   maska, např. {@code "*.crl"}
     */
    static void saveBytes(byte[] data, String defaultName, String filterDesc, String filterExt,
                           Window owner) {
        if (data == null || data.length == 0) {
            error("Není co uložit", "Prvek neobsahuje žádná data.");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Uložit soubor");
        fc.setInitialFileName(defaultName);
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(filterDesc, filterExt),
                new FileChooser.ExtensionFilter("Všechny soubory (*.*)", "*.*"));
        File file = fc.showSaveDialog(owner);
        if (file == null) return;
        try {
            Files.write(file.toPath(), data);
        } catch (Exception ex) {
            error("Nepodařilo se uložit soubor", String.valueOf(ex.getMessage()));
        }
    }

    /**
     * Umí prvek s touhle příponou otevřít systémový prohlížeč?
     *
     * <p>Výčet, ne domněnka. Certifikát i seznam CRL jsou v systému zavedené typy — Windows na ně
     * má vlastní okno, macOS otevře Klíčenku —, kdežto <b>odpověď OCSP je nikde nezná</b>: systém
     * by se na {@code .ors} akorát zeptal, čím ji otevřít. Tlačítko, které vede na tuhle otázku,
     * je horší než žádné, takže se u odpovědí OCSP nenabízí.
     */
    static boolean hasSystemViewer(String filterExt) {
        return "*.cer".equals(filterExt) || "*.crl".equals(filterExt);
    }

    /**
     * Uloží prvek do dočasného souboru a předá ho systému.
     *
     * <p>K čemu to je, když má aplikace vlastní prohlížeč: ten ukazuje to, nač se ptá tenhle
     * nástroj, a u odpovědi OCSP je jediný, který o ní vůbec ví. Systémové okno je vedle toho
     * <b>druhý názor</b> — jiná sada polí, jiné pojmenování, a u certifikátu i cesta k tomu, co
     * aplikace nedělá vůbec (nainstalovat jej do systémového úložiště). Přání uživatele
     * z 20. 9. 2026.
     *
     * <p><b>Přípona rozhoduje, ne obsah.</b> Systém pozná typ jedině podle ní, takže se jménem
     * z dialogu „Uložit…" vystačit nedá — může mít příponu původního souboru, nebo žádnou. Bere
     * se z něj proto jen základ a přípona se dosadí podle toho, co se ukazuje.
     *
     * <p>Otevírá se <b>na vlákně na pozadí</b>: {@code Desktop.open()} se vrátí, až systém
     * přidruženou aplikaci spustí, a to na obslužném vlákně JavaFX znamená zamrzlé okno.
     *
     * <p>Soubor je dočasný a maže se při ukončení aplikace. Držet ho déle nemá smysl — kdo ho
     * chce mít, má vedle tlačítko „Uložit…"; a nechávat po sobě certifikáty v systémovém
     * odkladišti je horší než je uklidit.
     */
    static void openInSystemViewer(byte[] data, String defaultName, String filterExt,
                                   Window owner) {
        if (data == null || data.length == 0) {
            error("Není co otevřít", "Prvek neobsahuje žádná data.");
            return;
        }
        if (!java.awt.Desktop.isDesktopSupported()
                || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
            error("Systémový prohlížeč není k dispozici",
                    "Toto prostředí neumí otevřít soubor v přidružené aplikaci."
                    + " Uložte prvek tlačítkem „Uložit…“ a otevřete jej sami.");
            return;
        }
        File file;
        try {
            file = writeTempFor(data, defaultName, filterExt);
        } catch (Exception ex) {
            error("Nepodařilo se připravit soubor", String.valueOf(ex.getMessage()));
            return;
        }
        final File toOpen = file;
        Thread thread = new Thread(() -> {
            try {
                java.awt.Desktop.getDesktop().open(toOpen);
            } catch (Exception ex) {
                // Nejčastěji na Linuxu: k typu není přidružená žádná aplikace. Hláška proto
                // neříká „chyba", nýbrž co s tím — uložit a otevřít čím uživatel chce.
                javafx.application.Platform.runLater(() -> error(
                        "Systém tento prvek otevřít neumí",
                        "Pro soubory typu " + filterExt + " nemá přidruženou aplikaci."
                        + " Uložte prvek tlačítkem „Uložit…“ a otevřete jej sami."));
            }
        }, "open-in-system-viewer");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Předá systému soubor, který na disku už je — na rozdíl od {@link #openInSystemViewer} se
     * nic nikam nekopíruje.
     *
     * <p>K čemu to je: náhled v aplikaci nevykreslí všechno (sken v JPEG 2000 ne, viz
     * {@code PdfPreviewWindow}), kdežto prohlížeč PDF přidružený v systému ano. Tlačítko proto
     * nabízí původní dokument, ne jeho výřez — čtenář chce vidět obsah, ne revizi.
     *
     * <p>Otevírá se na vlákně na pozadí ze stejného důvodu jako u prvků: {@code Desktop.open()}
     * se vrátí, až systém přidruženou aplikaci spustí, a na obslužném vlákně JavaFX to znamená
     * zamrzlé okno.
     */
    static void openFileInSystemViewer(File file, Window owner) {
        if (file == null || !file.isFile()) {
            error("Není co otevřít", "Původní soubor se nepodařilo najít.");
            return;
        }
        if (!java.awt.Desktop.isDesktopSupported()
                || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
            error("Systémový prohlížeč není k dispozici",
                    "Toto prostředí neumí otevřít soubor v přidružené aplikaci."
                    + " Otevřete dokument sami, například v Adobe Acrobat Readeru.");
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                java.awt.Desktop.getDesktop().open(file);
            } catch (Exception ex) {
                javafx.application.Platform.runLater(() -> error(
                        "Systém dokument otevřít neumí",
                        "K souborům PDF nemá přidruženou aplikaci."
                        + " Nainstalujte si prohlížeč PDF, například Adobe Acrobat Reader."));
            }
        }, "open-file-in-system-viewer");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Dočasný soubor s prvkem, pojmenovaný tak, aby ho systém poznal.
     *
     * <p>Odděleně od otevírání, protože právě tahle půlka se dá změřit: že přípona odpovídá tomu,
     * co se ukazuje, a že se jméno z dialogu „Uložit…“ nepropíše do cesty i s tím, co do ní
     * nepatří. Druhá půlka závisí na tom, co má systém přidružené, a ta se dá jen zkusit.
     */
    static File writeTempFor(byte[] data, String defaultName, String filterExt) throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("anep-prvek-");
        dir.toFile().deleteOnExit();
        File file = dir.resolve(baseName(defaultName) + filterExt.replace("*", "")).toFile();
        Files.write(file.toPath(), data);
        file.deleteOnExit();
        return file;
    }

    /** Jméno bez přípony a bez znaků, které by v cestě vadily. */
    private static String baseName(String defaultName) {
        String name = defaultName == null || defaultName.isBlank() ? "prvek" : defaultName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return name.isEmpty() ? "prvek" : name;
    }
}
