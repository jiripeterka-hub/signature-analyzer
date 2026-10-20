package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.PdfAConformanceChecker.Conformance;
import cz.digitalnikontinuita.anep.core.PdfAConformanceChecker.Violation;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import javafx.scene.Node;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Texty bloku o shodě s PDF/A. Hlídá se hlavně to, co je na nich věcné: že tři možné výsledky
 * říkají tři různé věci a že se ani jeden z nich nevydává za verdikt o podpisu.
 */
class PdfAConformanceBlockTest {

    @Test
    @DisplayName("Tři výsledky, tři různá tvrzení")
    void threeOutcomesReadDifferently() {
        assertTrue(PdfAConformanceBlock.intro(ok()).contains("splňuje"));
        assertFalse(PdfAConformanceBlock.intro(ok()).contains("nesplňuje"));

        assertTrue(PdfAConformanceBlock.intro(bad()).contains("nesplňuje"));

        String unknown = PdfAConformanceBlock.intro(broken());
        assertTrue(unknown.contains("nepodařilo"));
        // Nezdar nástroje se nesmí číst jako nález o dokumentu.
        assertTrue(unknown.contains("Neznamená to, že dokument normě nevyhovuje"));
    }

    @Test
    @DisplayName("Žádný z výsledků nehýbe verdiktem o podpisu — a říká to")
    void neverTouchesTheSignatureVerdict() {
        for (Conformance c : List.of(ok(), bad(), broken())) {
            assertTrue(PdfAConformanceBlock.outro(c)
                            .contains("nemá vliv na výsledek ověření podpisů"),
                    "chybí u " + c.flavour() + "/" + c.compliant());
        }
    }

    @Test
    @DisplayName("Profil se pojmenuje i s odpovídajícím zněním ISO")
    void namesTheSpecification() {
        assertEquals("PDF/A-2b", PdfAConformanceBlock.name("2b"));
        assertEquals("PDF/A-4f", PdfAConformanceBlock.name("4f"));
        assertEquals("ISO 19005-1:2005 — PDF/A-1b", PdfAConformanceBlock.specification("1b"));
        assertEquals("ISO 19005-2:2011 — PDF/A-2u", PdfAConformanceBlock.specification("2u"));
        assertEquals("ISO 19005-3:2012 — PDF/A-3a", PdfAConformanceBlock.specification("3a"));
        assertEquals("ISO 19005-4:2020 — PDF/A-4", PdfAConformanceBlock.specification("4"));
    }

    @Test
    @DisplayName("Porušené pravidlo nese odkaz do normy, počet výskytů jen když jich je víc")
    void violationWording() {
        String once = PdfAConformanceBlock.describe(
                new Violation("ISO 19005-2:2011", "6.2.4.3", 2, "DeviceRGB shall only be used…", 1));
        assertTrue(once.contains("ISO 19005-2:2011, 6.2.4.3-2"), once);
        assertFalse(once.contains("×"), once);

        String many = PdfAConformanceBlock.describe(
                new Violation("ISO 19005-2:2011", "6.2.4.3", 2, "DeviceRGB shall only be used…", 7));
        assertTrue(many.contains("(7×)"), many);
    }

    @Test
    @DisplayName("Bez prověření se blok nestaví")
    void noBlockWithoutACheck() {
        ValidationResult empty = new ValidationResult("dokument.pdf", null, false,
                List.of(), null, null, null);
        assertFalse(PdfAConformanceBlock.isPresent(empty));
        empty.setPdfConformance(ok());
        assertTrue(PdfAConformanceBlock.isPresent(empty));
    }

    private static Conformance ok() {
        return new Conformance("2b", true, List.of(), null);
    }

    private static Conformance bad() {
        return new Conformance("1b", false, List.of(
                new Violation("ISO 19005-1:2005", "6.3.5", 3, "…CIDSet…", 4)), null);
    }

    private static Conformance broken() {
        return new Conformance("2b", false, List.of(), "IndexOutOfBoundsException: Index: 0");
    }
    @Test
    @DisplayName("Blok se opravdu postaví a nese, co má")
    void blockRendersWhatTheTextsSay() {
        String rendered = onFx(() -> text(PdfAConformanceBlock.build(resultWith(bad()))));

        assertTrue(rendered.contains("Shoda s normou PDF/A"), rendered);
        assertTrue(rendered.contains("nesplňuje"), rendered);
        assertTrue(rendered.contains("ISO 19005-1:2005, 6.3.5-3"), rendered);
        assertTrue(rendered.contains("(4×)"), rendered);
        assertTrue(rendered.contains("nemá vliv na výsledek ověření podpisů"), rendered);
        // U vyhovujícího dokumentu uvedení veraPDF z bloku vypadlo (pokyn uživatele
        // 17. 9. 2026) — kdo ho hledá, najde ho v licenční poznámce distribuce. U toho, který
        // nevyhovuje, zůstává: vysvětluje, proč jsou znění porušených pravidel anglicky.
        assertFalse(PdfAConformanceBlock.outro(ok()).contains("Open Preservation Foundation"),
                PdfAConformanceBlock.outro(ok()));
        assertTrue(rendered.contains("Open Preservation Foundation"), rendered);
    }

    @Test
    @DisplayName("Blok se nerozbaluje sám nikdy")
    void theBlockNeverOpensItself() {
        for (Conformance c : List.of(ok(), bad(), broken())) {
            assertFalse(onFx(() -> expanded(PdfAConformanceBlock.build(resultWith(c)))),
                    "blok má nad kartami prvků zůstat zavřený");
        }
    }

    private static ValidationResult resultWith(Conformance c) {
        ValidationResult r = new ValidationResult("dokument.pdf", null, false,
                List.of(), null, null, null);
        r.setPdfConformance(c);
        return r;
    }

    /** Tělo bloku je druhé dítě; collapsibleBlock ho schovává přes visible/managed. */
    private static boolean expanded(VBox block) {
        return block.getChildren().size() > 1 && block.getChildren().get(1).isVisible();
    }

    private static String text(Node n) {
        StringBuilder sb = new StringBuilder();
        collect(n, sb);
        return sb.toString();
    }

    private static void collect(Node n, StringBuilder sb) {
        if (n instanceof javafx.scene.control.Labeled l && l.getText() != null) {
            sb.append(l.getText()).append('\n');
        }
        if (n instanceof javafx.scene.control.TextInputControl t && t.getText() != null) {
            sb.append(t.getText()).append('\n');
        }
        if (n instanceof javafx.scene.Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) collect(c, sb);
        }
    }

    private static <T> T onFx(java.util.function.Supplier<T> work) {
        Assumptions.assumeTrue(FxToolkit.available(), "JavaFX toolkit nenaběhl");
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try {
                out.set(work.get());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(30, TimeUnit.SECONDS)) fail("blok se nevykreslil do 30 s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
        if (failure.get() != null) fail(failure.get());
        return out.get();
    }
    @Test
    @DisplayName("Blok se dostane do protokolu — i když je v okně sbalený")
    void blockReachesTheProtocol() {
        // Protokol sází PrintUtil procházkou scénou. Nález o shodě s PDF/A v něm musí být vždy:
        // protokol je tištěný doklad o ověření, ne snímek obrazovky, a co je v něm sbalené,
        // do něj stejně patří. Proto se zkouší ten případ, kdy blok v okně zavřený JE —
        // dokument, který své deklaraci dostál.
        String fo = onFx(() -> {
            VBox holder = new VBox(PdfAConformanceBlock.build(resultWith(ok())));
            return PrintUtil.buildFo(holder, null, null, "Protokol", null, List.of());
        });

        assertTrue(fo.contains("Shoda s normou PDF/A"), fo);
        assertTrue(fo.contains("splňuje"), fo);
        assertTrue(fo.contains("ISO 19005-2:2011"), fo);
        assertTrue(fo.contains("nemá vliv na výsledek ověření podpisů"), fo);
    }

    @Test
    @DisplayName("Do protokolu jdou i vyjmenovaná porušená pravidla")
    void protocolCarriesTheViolations() {
        String fo = onFx(() -> {
            VBox holder = new VBox(PdfAConformanceBlock.build(resultWith(bad())));
            return PrintUtil.buildFo(holder, null, null, "Protokol", null, List.of());
        });

        assertTrue(fo.contains("nesplňuje"), fo);
        assertTrue(fo.contains("ISO 19005-1:2005, 6.3.5-3"), fo);
    }    @Test
    @DisplayName("O úkonech blok nemluví — to patří do Digitální kontinuity")
    void theBlockSaysNothingAboutTheActions() {
        for (Conformance c : List.of(ok(), bad(), broken())) {
            String text = PdfAConformanceBlock.intro(c) + PdfAConformanceBlock.outro(c);
            assertFalse(text.contains("archivní podobu"), text);
            assertFalse(text.contains("uchování"), text);
        }
    }
}
