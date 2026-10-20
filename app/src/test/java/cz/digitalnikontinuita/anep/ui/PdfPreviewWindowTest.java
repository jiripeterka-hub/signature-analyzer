package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.DocumentVersions;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Okno náhledu: jedno, listovatelné, a po sobě uklidí.
 *
 * <p>Dokument si zkouška <b>vyrobí</b>, místo aby sáhla do sbírky: chování okna na obsahu nezávisí
 * a zkouška, která potřebuje {@code D:\tmp}, na cizím stroji neběží.
 */
class PdfPreviewWindowTest {

    private static boolean toolkitUp;

    @BeforeAll
    static void startToolkit() {
        toolkitUp = FxToolkit.available();
    }

    @AfterEach
    void closeWindow() {
        if (toolkitUp) onFx(() -> { PdfPreviewWindow.close(); return null; });
    }

    @Test
    @DisplayName("Otevře se, vypíše stranu a po zavření po sobě uklidí")
    void opensAndCloses(@TempDir Path dir) throws Exception {
        File pdf = threePager(dir);

        assertFalse(onFx(PdfPreviewWindow::isOpen), "Na začátku nic otevřeného není");
        onFx(() -> { PdfPreviewWindow.open(null, pdf, null, null); return null; });
        assertTrue(onFx(PdfPreviewWindow::isOpen));

        awaitStatus("Strana 1 z 3");

        onFx(() -> { PdfPreviewWindow.close(); return null; });
        assertFalse(onFx(PdfPreviewWindow::isOpen), "Zavřené okno se nemá hlásit jako otevřené");
    }

    /**
     * Jedno okno, ne druhé — rozhodnutí uživatele z 18. 9. 2026. Druhý stisk tlačítka má to
     * stávající vytáhnout dopředu; kdyby zakládal další, měl by uživatel na obrazovce dva náhledy
     * téhož dokumentu a žádný způsob, jak poznat, který je který.
     */
    /**
     * Nález uživatele z 21. 9. 2026 nad dokumentem Evropské komise: stránky jsou sken uložený
     * v JPEG 2000 a k tomu formátu nemá Java čtečku, takže náhled ukázal bílou plochu a nic
     * neřekl. Z celé stránky vyšlo 0,1 % nebílých bodů — a čtenář nemá jak poznat, jestli je
     * dokument prázdný, nebo se vykreslení nepovedlo.
     *
     * <p>Přibalit dekodér nejde: {@code jai-imageio-jpeg2000} stojí na licenci JJ2000, která
     * omezuje obor užití a je neslučitelná s GPL-3 (z téhož důvodu ji nepřibaluje ani PDFBox).
     * Zbývá to říct a nabídnout, co pomůže — otevřít dokument v přidružené aplikaci.
     */
    @Test
    @DisplayName("Nevykreslitelný sken náhled pojmenuje a nabídne systémový prohlížeč")
    void anUndecodableScanIsExplained(@TempDir Path dir) throws Exception {
        File pdf = jpxScan(dir);
        onFx(() -> { PdfPreviewWindow.open(null, pdf, null, null); return null; });
        awaitStatus("JPEG 2000");
        assertTrue(onFx(() -> statusText().contains("Adobe Acrobat Reader")),
                "Věta má poradit, čím dokument otevřít: " + onFx(PdfPreviewWindowTest::statusText));
        assertNotNull(onFx(() -> button("Otevřít v přidružené aplikaci")),
                "K radě patří tlačítko, které to udělá");
    }

    @Test
    @DisplayName("Nad běžnou stránkou ten pruh nestojí")
    void anOrdinaryPageHasNoSuchBar(@TempDir Path dir) throws Exception {
        File pdf = threePager(dir);
        onFx(() -> { PdfPreviewWindow.open(null, pdf, null, null); return null; });
        awaitStatus("Strana 1 z 3");
        assertFalse(onFx(() -> statusText().contains("JPEG 2000")),
                "U dokumentu, který se vykreslit dá, nemá být co vysvětlovat");
    }

    @Test
    @DisplayName("Rozpoznání se ptá na zdroje stránky, ne na vykreslení")
    void theDetectionReadsThePageResources(@TempDir Path dir) throws Exception {
        try (PDDocument scan = org.apache.pdfbox.Loader.loadPDF(jpxScan(dir));
             PDDocument plain = org.apache.pdfbox.Loader.loadPDF(threePager(dir))) {
            assertTrue(PdfPreviewWindow.unreadableImages(scan.getPage(0)));
            assertFalse(PdfPreviewWindow.unreadableImages(plain.getPage(0)));
            assertFalse(PdfPreviewWindow.unreadableImages(null));
        }
    }

    @Test
    @DisplayName("Druhé otevření nezaloží druhé okno")
    void openingTwiceKeepsOneWindow(@TempDir Path dir) throws Exception {
        File pdf = threePager(dir);

        onFx(() -> { PdfPreviewWindow.open(null, pdf, null, null); return null; });
        awaitStatus("Strana 1 z 3");
        onFx(() -> { PdfPreviewWindow.open(null, pdf, null, null); return null; });

        assertEquals(1, onFx(() -> previewWindows().size()), "Okno má zůstat jedno");
    }

    @Test
    @DisplayName("Listování se drží v mezích dokumentu")
    void pagingStaysWithinTheDocument(@TempDir Path dir) throws Exception {
        File pdf = threePager(dir);
        onFx(() -> { PdfPreviewWindow.open(null, pdf, null, null); return null; });
        awaitStatus("Strana 1 z 3");

        press("▶");
        awaitStatus("Strana 2 z 3");
        press("▶");
        awaitStatus("Strana 3 z 3");
        // Na poslední straně je „dál“ vypnuté, takže se za konec dokumentu nedá dostat.
        assertTrue(onFx(() -> button("▶").isDisabled()), "Za poslední stranou už nic není");
        press("◀");
        awaitStatus("Strana 2 z 3");
    }

    /**
     * Soubor, který není PDF, se sem přes tlačítko nedostane — to se u ne-PDF neukazuje. Kdyby
     * přece, okno má říct proč. Mlčet a nic neudělat by vypadalo jako zaseklé tlačítko.
     */
    @Test
    @DisplayName("Nečitelný soubor skončí hláškou, ne výjimkou")
    void anUnreadableFileIsReported(@TempDir Path dir) throws Exception {
        File notPdf = dir.resolve("neni.pdf").toFile();
        java.nio.file.Files.writeString(notPdf.toPath(), "tohle PDF není");

        onFx(() -> { PdfPreviewWindow.open(null, notPdf, null, null); return null; });
        awaitStatus("nepodařilo otevřít");
        assertTrue(onFx(PdfPreviewWindow::isOpen), "Okno zůstane a vysvětlí, co se stalo");
    }

    /**
     * Skok z bloku v Analýze: klik na číslo strany otevře náhled rovnou tam.
     *
     * <p>Podoba se tu vyrábí ručně a její {@code endOffset} je délka celého souboru, takže se
     * otevře celý. Je to úmysl: zkouší se chování okna (skok, ukazatel), ne čtení prefixu —
     * to má svoji zkoušku v {@code DocumentVersionsTest} a ověřeno je i na vyrobeném vzorku.
     * Prefix náhodného PDF by platné PDF nebyl a zkouška by měřila jen to, že se nedá otevřít.
     */
    @Test
    @DisplayName("Otevření s cílem zamíří na tu stranu a řekne, že se na ní obsah liší")
    void openingWithATargetAimsAtThePage(@TempDir Path dir) throws Exception {
        File pdf = threePager(dir);
        List<DocumentVersions.Version> versions = List.of(
                new DocumentVersions.Version("Jak to vypadalo při podpisu", pdf.length(),
                        false, List.of(2)),
                new DocumentVersions.Version("Dnešní podoba souboru", pdf.length(),
                        true, List.of()));

        onFx(() -> { PdfPreviewWindow.open(null, pdf, null, versions, 2, true); return null; });

        awaitStatus("Strana 2 z 3");
        assertTrue(onFx(PdfPreviewWindowTest::statusText)
                        .contains("na této straně se obsah po podpisu liší"),
                "Ukazatel rozdílu na té straně chybí");
    }

    @Test
    @DisplayName("Tlačítko „Na rozdíl“ skočí na stranu s rozdílem a dokola")
    void theDifferenceButtonJumps(@TempDir Path dir) throws Exception {
        File pdf = threePager(dir);
        List<DocumentVersions.Version> versions = List.of(
                new DocumentVersions.Version("Jak to vypadalo při podpisu", pdf.length(),
                        false, List.of(3)),
                new DocumentVersions.Version("Dnešní podoba souboru", pdf.length(),
                        true, List.of()));

        onFx(() -> { PdfPreviewWindow.open(null, pdf, null, versions); return null; });
        awaitStatus("Strana 1 z 3");

        press("Na rozdíl ▸");
        awaitStatus("Strana 3 z 3");
        // Dokola: na poslední straně s rozdílem se tlačítko vrátí na první, místo aby přestalo
        // reagovat — mlčící tlačítko vypadá jako porouchané.
        press("Na rozdíl ▸");
        awaitStatus("Strana 3 z 3");
    }

    @Test
    @DisplayName("Bez rozdílů se tlačítko „Na rozdíl“ neukazuje")
    void noDifferenceNoButton(@TempDir Path dir) throws Exception {
        File pdf = threePager(dir);
        onFx(() -> { PdfPreviewWindow.open(null, pdf, null, null); return null; });
        awaitStatus("Strana 1 z 3");

        assertTrue(onFx(() -> !buttonOrNull("Na rozdíl ▸").isVisible()),
                "Tlačítko, které nemá kam skočit, nemá co ukazovat");
    }

    private static javafx.scene.control.Button buttonOrNull(String label) {
        for (Node n : all()) {
            if (n instanceof javafx.scene.control.Button b && label.equals(b.getText())) return b;
        }
        throw new IllegalStateException("Tlačítko „" + label + "“ v okně není");
    }

    /**
     * Zvětšení: procenta se počítají vůči přirozené velikosti stránky, ne vůči rastru. Rastr se
     * vykresluje ve větším měřítku kvůli ostrosti, takže „150 %“ nad stránkou, která vypadá jako
     * v Acrobatu při stu procentech, by čtenáře mátlo.
     */
    @Test
    @DisplayName("Náhled se otevře na 100 %")
    void opensAtActualSize(@TempDir Path dir) throws Exception {
        File pdf = threePager(dir);
        onFx(() -> { PdfPreviewWindow.open(null, pdf, null, null); return null; });
        awaitStatus("Strana 1 z 3");
        awaitStatus("%");
        assertEquals("100 %", onFx(PdfPreviewWindowTest::zoomText));
        assertFalse(onFx(() -> toggle("Přizpůsobit šířce").isSelected()),
                "Přizpůsobení šířce je volba, ne výchozí stav");
    }

    @Test
    @DisplayName("Zvětšení mění procenta a vypne přizpůsobení šířce")
    void zoomingLeavesFitToWidth(@TempDir Path dir) throws Exception {
        File pdf = threePager(dir);
        onFx(() -> { PdfPreviewWindow.open(null, pdf, null, null); return null; });
        awaitStatus("Strana 1 z 3");
        // Obrázek dorazí až z vlákna na pozadí; procenta se počítají z něj.
        awaitStatus("%");

        onFx(() -> { toggle("Přizpůsobit šířce").fire(); return null; });
        assertTrue(onFx(() -> toggle("Přizpůsobit šířce").isSelected()));
        String before = onFx(PdfPreviewWindowTest::zoomText);

        press("+");
        String after = onFx(PdfPreviewWindowTest::zoomText);
        assertNotEquals(before, after, "Zvětšení se má projevit na procentech");
        assertFalse(onFx(() -> toggle("Přizpůsobit šířce").isSelected()),
                "Ruční zvětšení znamená, že se čtenář rozhodl sám — automatika mu nemá mluvit do"
                + " toho");

        // Zpátky na přizpůsobení: procenta se mají vrátit k té hodnotě, která vyplní šířku.
        onFx(() -> { toggle("Přizpůsobit šířce").fire(); return null; });
        assertEquals(before, onFx(PdfPreviewWindowTest::zoomText),
                "Přizpůsobení šířce dá vždy touž hodnotu");
    }

    /**
     * Hledání v textu bylo v náhledu od 18. 9. 2026 a 20. 9. 2026 je pryč — <b>pokyn
     * uživatele</b>: „je to skutečně jen náhled, když někdo chce hledat, může si spustit Adobe
     * Acrobat Reader.“ Zkouška to drží, protože políčko se do lišty vrátí snadno a nikdo by si
     * toho nevšiml; zmizet s ním musí i {@code PDFTextStripper} a značka nálezu v pruhu miniatur.
     */
    @Test
    @DisplayName("Náhled je jen náhled: hledat se v něm nedá")
    void thePreviewHasNoSearch(@TempDir Path dir) throws Exception {
        File pdf = threePager(dir);
        onFx(() -> { PdfPreviewWindow.open(null, pdf, null, null); return null; });
        awaitStatus("Strana 1 z 3");

        assertTrue(onFx(() -> all().stream()
                        .filter(n -> n instanceof javafx.scene.control.TextField)
                        .map(n -> (javafx.scene.control.TextField) n)
                        .noneMatch(f -> f.getPromptText() != null
                                && f.getPromptText().contains("Hledat"))),
                "Políčko pro hledání v náhledu být nemá");
        assertTrue(onFx(() -> all().stream()
                        .noneMatch(n -> n instanceof Labeled l && l.getText() != null
                                && l.getText().contains("Další"))),
                "Ani tlačítko na další nález");
    }

    /**
     * Vlastní měřítko: klik na údaj, číslo, Enter.
     *
     * <p>Zadání od uživatele (20. 9. 2026). Tlačítka {@code −} a {@code +} násobí a dělí
     * 1,25, takže na rovných sto procent nedosáhnou; políčko je jediná cesta, jak si čtenář
     * řekne přesně.
     */
    @Test
    @DisplayName("Klik na údaj o měřítku otevře políčko a napsaná hodnota se uplatní")
    void clickingTheZoomReadoutLetsYouTypeIt(@TempDir Path dir) throws Exception {
        File pdf = threePager(dir);
        onFx(() -> { PdfPreviewWindow.open(null, pdf, null, null); return null; });
        awaitStatus("Strana 1 z 3");
        awaitStatus("%");

        assertFalse(onFx(() -> zoomField().isVisible()), "Zavřené, dokud se na údaj neklikne");

        // Nejdřív pryč s přizpůsobením šířce: dokud je zapnuté, přepočítá se měřítko při každé
        // změně šířky okna, a zkouška by srovnávala dvě hodnoty z různých okamžiků. Naměřeno —
        // jednou z pěti běhů to spadlo.
        press("+");
        String shown = onFx(PdfPreviewWindowTest::zoomText).replace(" %", "");

        clickZoomReadout();
        assertTrue(onFx(() -> zoomField().isVisible()), "Klik má políčko otevřít");
        assertEquals(shown, onFx(() -> zoomField().getText()),
                "Otevře se s hodnotou, která právě platí — aby se dala jen přepsat");

        typeZoom("100");
        assertEquals("100 %", onFx(PdfPreviewWindowTest::zoomText));
        assertFalse(onFx(() -> zoomField().isVisible()), "Po Enteru se políčko zavře");
        assertFalse(onFx(() -> toggle("Přizpůsobit šířce").isSelected()),
                "Vlastní měřítko je rozhodnutí čtenáře, automatika mu do něj nemá mluvit");
    }

    @Test
    @DisplayName("Procenta, čárka i hodnota mimo meze")
    void theTypedValueIsParsedLeniantlyAndClamped(@TempDir Path dir) throws Exception {
        File pdf = threePager(dir);
        onFx(() -> { PdfPreviewWindow.open(null, pdf, null, null); return null; });
        awaitStatus("Strana 1 z 3");
        awaitStatus("%");

        clickZoomReadout();
        typeZoom("150 %");
        assertEquals("150 %", onFx(PdfPreviewWindowTest::zoomText),
                "Znak procenta patří k údaji vedle, takže ho čtenář napíše");

        clickZoomReadout();
        typeZoom("12,5");
        assertEquals("25 %", onFx(PdfPreviewWindowTest::zoomText),
                "Pod dolní mez se ořízne, ne odmítne — a čárka je česká desetinná značka");

        clickZoomReadout();
        typeZoom("5000");
        assertEquals("500 %", onFx(PdfPreviewWindowTest::zoomText), "A totéž shora");
    }

    @Test
    @DisplayName("Nesmysl i Esc nechají měřítko být")
    void nonsenseAndEscapeLeaveTheZoomAlone(@TempDir Path dir) throws Exception {
        File pdf = threePager(dir);
        onFx(() -> { PdfPreviewWindow.open(null, pdf, null, null); return null; });
        awaitStatus("Strana 1 z 3");
        awaitStatus("%");

        clickZoomReadout();
        typeZoom("100");
        String before = onFx(PdfPreviewWindowTest::zoomText);

        clickZoomReadout();
        typeZoom("kolik to je");
        assertEquals(before, onFx(PdfPreviewWindowTest::zoomText),
                "Překlep v jednom čísle si hlášku nezaslouží; měřítko prostě zůstane");

        clickZoomReadout();
        onFx(() -> {
            zoomField().setText("250");
            zoomField().fireEvent(new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.ESCAPE, false, false, false, false));
            return null;
        });
        assertEquals(before, onFx(PdfPreviewWindowTest::zoomText), "Esc zadání zruší");
        assertFalse(onFx(() -> zoomField().isVisible()), "A políčko zavře");
    }

    private static void clickZoomReadout() {
        onFx(() -> {
            zoomReadout().getOnMouseClicked().handle(null);
            return null;
        });
    }

    /** Napíše hodnotu a potvrdí ji Enterem. */
    private static void typeZoom(String value) {
        onFx(() -> {
            zoomField().setText(value);
            zoomField().fireEvent(new javafx.event.ActionEvent());
            return null;
        });
    }

    private static javafx.scene.control.TextField zoomField() {
        for (Node n : all()) {
            if (n instanceof javafx.scene.control.TextField f) return f;
        }
        throw new IllegalStateException("Políčko pro měřítko v okně není");
    }

    private static Labeled zoomReadout() {
        for (Node n : all()) {
            if (n instanceof Labeled l && l.getOnMouseClicked() != null
                    && l.getText() != null && l.getText().endsWith(" %")) {
                return l;
            }
        }
        throw new IllegalStateException("Údaj o měřítku v okně není, nebo se na něj nedá kliknout");
    }

    private static String zoomText() {
        for (Node n : all()) {
            if (n instanceof Labeled l && l.getText() != null && l.getText().endsWith(" %")) {
                return l.getText();
            }
        }
        return "";
    }

    private static javafx.scene.control.ToggleButton toggle(String label) {
        for (Node n : all()) {
            if (n instanceof javafx.scene.control.ToggleButton t && label.equals(t.getText())) {
                return t;
            }
        }
        throw new IllegalStateException("Přepínač „" + label + "“ v okně není");
    }


    // ------------------------------------------------------------------ přípravek

    /**
     * Dokument, jehož stránka nese obrázek v JPEG 2000 — to, co Java neumí rozbalit.
     *
     * <p>Obsah obrázku je nesmysl a nemusí být jiný: rozpoznání se ptá na filtr ve zdrojích
     * stránky, ne na to, co je uvnitř. Právě proto, že se obrázek nedekóduje, se na tohle nedá
     * počkat u vykreslování — PDFBox výjimku jen zaloguje a kreslí dál.
     */
    private static File jpxScan(Path dir) throws Exception {
        File out = dir.resolve("sken-jpeg2000.pdf").toFile();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            page.setResources(new org.apache.pdfbox.pdmodel.PDResources());
            doc.addPage(page);
            org.apache.pdfbox.cos.COSStream stream = doc.getDocument().createCOSStream();
            stream.setItem(org.apache.pdfbox.cos.COSName.TYPE,
                    org.apache.pdfbox.cos.COSName.XOBJECT);
            stream.setItem(org.apache.pdfbox.cos.COSName.SUBTYPE,
                    org.apache.pdfbox.cos.COSName.IMAGE);
            stream.setItem(org.apache.pdfbox.cos.COSName.FILTER,
                    org.apache.pdfbox.cos.COSName.JPX_DECODE);
            stream.setInt(org.apache.pdfbox.cos.COSName.WIDTH, 16);
            stream.setInt(org.apache.pdfbox.cos.COSName.HEIGHT, 16);
            try (java.io.OutputStream os = stream.createRawOutputStream()) {
                os.write(new byte[] {0, 0, 0, 12, 'j', 'P', ' ', ' '});
            }
            page.getResources().put(org.apache.pdfbox.cos.COSName.getPDFName("Im0"),
                    new org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject(
                            new org.apache.pdfbox.pdmodel.common.PDStream(stream), null));
            doc.save(out);
        }
        return out;
    }

    private static File threePager(Path dir) throws Exception {
        File out = dir.resolve("tri-strany.pdf").toFile();
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= 3; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("strana " + i);
                    cs.endText();
                }
            }
            doc.save(out);
        }
        return out;
    }

    /** Počká, až se v okně objeví očekávaný text. Vykresluje se na pozadí, takže hned tam není. */
    private static void awaitStatus(String fragment) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            String text = onFx(PdfPreviewWindowTest::statusText);
            if (text.contains(fragment)) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(e);
            }
        }
        fail("Do 20 s se neobjevilo „" + fragment + "“; v okně je: " + onFx(PdfPreviewWindowTest::statusText));
    }

    private static void press(String label) {
        onFx(() -> { button(label).fire(); return null; });
    }

    private static javafx.scene.control.Button button(String label) {
        for (Node n : all()) {
            if (n instanceof javafx.scene.control.Button b && label.equals(b.getText())) return b;
        }
        throw new IllegalStateException("Tlačítko „" + label + "“ v okně není");
    }

    private static String statusText() {
        StringBuilder sb = new StringBuilder();
        for (Node n : all()) {
            if (n instanceof Labeled l && l.getText() != null) sb.append(l.getText()).append('\n');
        }
        return sb.toString();
    }

    private static List<Node> all() {
        List<Node> out = new ArrayList<>();
        for (Stage st : previewWindows()) {
            collect(st.getScene().getRoot(), out);
        }
        return out;
    }

    private static List<Stage> previewWindows() {
        List<Stage> out = new ArrayList<>();
        for (Window w : new ArrayList<>(Window.getWindows())) {
            if (w instanceof Stage st && st.getTitle() != null
                    && st.getTitle().startsWith("Náhled — ")) {
                out.add(st);
            }
        }
        return out;
    }

    private static void collect(Node n, List<Node> out) {
        out.add(n);
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) collect(c, out);
        }
    }

    private static <T> T onFx(Supplier<T> work) {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit nenaběhl");
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                out.set(work.get());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(20, TimeUnit.SECONDS)) fail("Vlákno JavaFX neodpovědělo do 20 s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
        if (failure.get() != null) fail(failure.get());
        return out.get();
    }
}
