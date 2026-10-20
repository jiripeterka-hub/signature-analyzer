package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Run;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Labeled;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Odkazy z bloků u prvku do výkladu na začátku záložky.
 *
 * <p>Bloky u prvku výklad neopisují, nýbrž na něj odkazují — a odkaz, který nikam nevede nebo
 * který v textu zůstane jako {@code [text](CÍL)}, je horší než opsaný odstavec. Obojí se dá
 * splést tiše, protože značka je jen text a chyba v ní nic nevyhodí.
 */
class ContinuityOutlookLinkTest {

    private static final Run STRICT = new Run(true, List.of(), null);

    private static boolean toolkitUp;

    @BeforeAll
    static void startToolkit() {
        toolkitUp = FxToolkit.available();
    }

    @Test
    @DisplayName("Odkaz na variantu otevře její výklad")
    void linkTargetsTheVariantOutlook() {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit nenaběhl");
        List<ContinuityView.Outlook> asked = new ArrayList<>();
        List<Hyperlink> links = onFxThread(() -> {
            VBox blocks = blocks(new ContinuityElementBlocks.Gates(true, false, false, true),
                    asked::add);
            return collect(blocks, Hyperlink.class);
        });

        assertFalse(links.isEmpty(), "Blok u prvku má odkazovat do výkladu, ne ho opisovat.");
        onFxThread(() -> {
            links.forEach(Hyperlink::fire);
            return null;
        });
        assertEquals(links.size(), asked.size(), "Každý odkaz musí vést do výkladu.");
        assertTrue(asked.contains(ContinuityView.Outlook.PRESERVE_PROLONG),
                "U dokumentu, kterému se nabízí uchování, patří odkaz k té variantě: " + asked);
    }

    @Test
    @DisplayName("Přerazítkování odkazuje na svou variantu, ne na uchování")
    void restampLinksToItsOwnOutlook() {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit nenaběhl");
        List<ContinuityView.Outlook> asked = new ArrayList<>();
        List<Hyperlink> links = onFxThread(() -> {
            VBox blocks = blocks(new ContinuityElementBlocks.Gates(false, false, true, true),
                    asked::add);
            return collect(blocks, Hyperlink.class);
        });
        onFxThread(() -> {
            links.forEach(Hyperlink::fire);
            return null;
        });
        assertTrue(asked.contains(ContinuityView.Outlook.RESTAMP),
                "Dosah přerazítkování popisuje jeho vlastní varianta: " + asked);
    }

    @Test
    @DisplayName("Bez obsluhy se odstavec vysází jako text, ne jako značka odkazu")
    void withoutHandlerTheMarkupDisappears() {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit nenaběhl");
        String text = onFxThread(() -> {
            VBox blocks = blocks(new ContinuityElementBlocks.Gates(true, false, false, true), null);
            StringBuilder sb = new StringBuilder();
            for (Labeled l : collect(blocks, Labeled.class)) {
                if (l.getText() != null) sb.append(l.getText()).append('\n');
            }
            for (javafx.scene.text.Text t : collect(blocks, javafx.scene.text.Text.class)) {
                sb.append(t.getText());
            }
            return sb.toString();
        });

        assertFalse(text.contains("](" ), "Značka odkazu se nesmí dostat na obrazovku: " + text);
        assertFalse(text.contains("(PRESERVE_PROLONG)"), "Cíl odkazu není text pro čtenáře.");
        assertTrue(text.contains("varianta „uchování“ a „prodloužení“"),
                "Jméno výkladu má zůstat i tam, kde se na něj nedá kliknout.");
        assertTrue(collect(blocks(new ContinuityElementBlocks.Gates(true, false, false, true),
                null), Hyperlink.class).isEmpty(),
                "Bez obsluhy nemá odkaz kam vést, takže žádný nevzniká.");
    }

    /** Bloky pro jeden bezvadný podpis, s danou bránou a obsluhou odkazů. */
    private static VBox blocks(ContinuityElementBlocks.Gates gates,
                               java.util.function.Consumer<ContinuityView.Outlook> open) {
        SignatureSummary sig = SigFixture.sig().certNotAfter(SigFixture.FUTURE).build();
        return ContinuityElementBlocks.build(sig, "TOTAL_PASSED", STRICT, false, gates,
                null, true, ContinuityElementBlocks.Source.named("I.CA", null), open);
    }

    private static <T> List<T> collect(Node node, Class<T> type) {
        List<T> found = new ArrayList<>();
        List<Node> stack = new ArrayList<>();
        stack.add(node);
        while (!stack.isEmpty()) {
            Node n = stack.remove(stack.size() - 1);
            if (type.isInstance(n)) found.add(type.cast(n));
            if (n instanceof Parent p) stack.addAll(p.getChildrenUnmodifiable());
        }
        return found;
    }

    private static <T> T onFxThread(Supplier<T> work) {
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
            Assumptions.assumeTrue(done.await(30, TimeUnit.SECONDS), "FX vlákno neodpovědělo");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Assumptions.abort("přerušeno");
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        return out.get();
    }
}
