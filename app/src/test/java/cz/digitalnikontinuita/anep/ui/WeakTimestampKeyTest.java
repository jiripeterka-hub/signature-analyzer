package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.TsaProfile;
import cz.digitalnikontinuita.anep.core.TsaSigningKey;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.ui.ContinuityElementBlocks.Element;
import cz.digitalnikontinuita.anep.ui.ContinuityElementBlocks.Gates;
import cz.digitalnikontinuita.anep.ui.ContinuityElementBlocks.Source;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Run;
import eu.europa.esig.dss.enumerations.SignatureAlgorithm;
import javafx.scene.Node;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Slabý algoritmus razítka v Digitální kontinuitě (pokyn uživatele z 5. 10. 2026).
 *
 * <p>Dvě věci: lhůta k úkonu je „nejlépe ihned“, když algoritmus nejnovějšího razítka přestane
 * být spolehlivý dřív, než lhůta uplyne (dnes typicky RSA-2048 a 1. 1. 2027); a blok „Jak
 * dlouho je možné zachovat ověřitelnost?“ upozorní, když slabým klíčem podepisuje i vybraný
 * zdroj razítek. Data jsou relativní k dnešku, aby test nestárl.
 */
class WeakTimestampKeyTest {

    private static final Instant SOON = Instant.now().plus(60, ChronoUnit.DAYS);
    private static final Instant FUTURE = SigFixture.FUTURE;
    private static final Instant LATER = SigFixture.LATER;
    private static final Run STRICT = new Run(true, List.of(), null);
    private static final Gates PRESERVE = new Gates(true, false, false, false);
    private static final Source STRONG = new Source("Silná TSA", Date.from(LATER),
            TsaProfile.Quality.QUALIFIED);
    private static final Source WEAK = new Source("Slabá TSA", Date.from(LATER),
            TsaProfile.Quality.QUALIFIED,
            new TsaSigningKey.Weakness(new TsaSigningKey(SignatureAlgorithm.RSA_SHA256, 2048),
                    Date.from(SOON), true));

    private static boolean toolkitUp;

    @BeforeAll
    static void startToolkit() {
        toolkitUp = FxToolkit.available();
    }

    private static Element element(Instant keyUntil) {
        SignatureSummary sig = SigFixture.sig().certNotAfter(FUTURE).build();
        if (keyUntil != null) {
            sig.setNewestTimestampKey(new SignatureSummary.TimestampKey(
                    "RSA, klíč 2 048 bitů", keyUntil, true));
        }
        return ContinuityElementBlocks.element(sig, "TOTAL_PASSED", STRICT, false, true, null);
    }

    @Test
    @DisplayName("razítko s algoritmem, který neplatí do konce lhůty: nejlépe ihned a proč")
    void weakKeyActsNow() {
        String text = onFx(() -> text(ContinuityElementBlocks.build(
                element(SOON), PRESERVE, STRONG, null, true, null)));
        assertTrue(text.contains("Nejlépe ihned"), text);
        assertTrue(text.contains("RSA, klíč 2 048 bitů"), text);
        assertTrue(text.contains("jen varováním"), text);
        assertFalse(text.contains("Do kdy je třeba ji využít?"), text);
    }

    @Test
    @DisplayName("algoritmus vydrží déle než lhůta: lhůta zůstane datem")
    void strongEnoughKeyKeepsDeadline() {
        String text = onFx(() -> text(ContinuityElementBlocks.build(
                element(LATER.plus(3650, ChronoUnit.DAYS)), PRESERVE, STRONG, null, true, null)));
        assertFalse(text.contains("Nejlépe ihned"), text);
        assertFalse(text.contains("nepovažuje za spolehlivý"), text);
    }

    @Test
    @DisplayName("bez údaje o klíči se nic nemění")
    void noKeyNoChange() {
        String text = onFx(() -> text(ContinuityElementBlocks.build(
                element(null), PRESERVE, STRONG, null, true, null)));
        assertFalse(text.contains("Nejlépe ihned"), text);
    }

    @Test
    @DisplayName("dokument: stačí jeden prvek se slabým razítkem a výčet ho jmenuje")
    void documentActsNow() {
        String text = onFx(() -> text(ContinuityElementBlocks.documentBlocks(
                List.of(element(null), element(SOON)), PRESERVE, List.of(), STRONG, null, null)));
        assertTrue(text.contains("Nejlépe ihned"), text);
        assertTrue(text.contains("U prvku č. 2 je nejnovější časové razítko"), text);
        assertTrue(text.contains("Nejnovější časové razítko tohoto prvku"), text);
    }

    @Test
    @DisplayName("slabý vybraný zdroj: upozornění v bloku o dosahu i v radě, kdy jednat")
    void weakSourceWarned() {
        String text = onFx(() -> text(ContinuityElementBlocks.build(
                element(SOON), PRESERVE, WEAK, null, true, null)));
        assertTrue(text.contains("Vybraný zdroj časových razítek (Slabá TSA) podepisuje"), text);
        assertTrue(text.contains("jeho razítko by tenhle problém nevyřešilo"), text);
    }

    @Test
    @DisplayName("silný vybraný zdroj: o zdroji se mlčí")
    void strongSourceSilent() {
        String text = onFx(() -> text(ContinuityElementBlocks.build(
                element(null), PRESERVE, STRONG, null, true, null)));
        assertFalse(text.contains("Vybraný zdroj časových razítek"), text);
    }

    private static String text(VBox blocks) {
        StringBuilder sb = new StringBuilder();
        collect(blocks, sb);
        return sb.toString();
    }

    private static void collect(Node node, StringBuilder sb) {
        if (node instanceof javafx.scene.control.Labeled l && l.getText() != null) {
            sb.append(l.getText()).append('\n');
        }
        if (node instanceof javafx.scene.text.Text t && t.getText() != null) {
            sb.append(t.getText());
        }
        if (node instanceof javafx.scene.Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) collect(c, sb);
        }
    }

    private static <T> T onFx(Supplier<T> work) {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit nenaběhl");
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
}
