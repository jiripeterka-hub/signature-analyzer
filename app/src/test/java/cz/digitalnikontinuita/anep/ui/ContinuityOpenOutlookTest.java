package cz.digitalnikontinuita.anep.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Druhá půlka odkazu: že cíl, na který míří, se opravdu otevře.
 *
 * <p>Vnořený blok leží uvnitř zavřeného vnějšího, takže samotné otevření toho vnitřního by
 * čtenáři nic neukázalo — a rozbalení jen přes {@code setVisible} by nechalo šipku a podklad
 * záhlaví ve stavu zavřeno. Obojí je tichá chyba: odkaz „funguje“ a přesto nic není vidět.
 */
class ContinuityOpenOutlookTest {

    private static boolean toolkitUp;

    @BeforeAll
    static void startToolkit() {
        toolkitUp = FxToolkit.available();
    }

    @Test
    @DisplayName("Výklad varianty je po otevření vidět i se svým obalem")
    void openingAVariantOpensItsOuterBlockToo() {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit nenaběhl");
        onFxThread(() -> {
            ContinuityView view = new ContinuityView();
            assertFalse(bodyVisible(view, "varianta „přerazítkování“"),
                    "Vnořené bloky se staví zabalené.");
            assertFalse(bodyVisible(view,
                    "Jaké možnosti udržování digitální kontinuity nabízí AnEP"),
                    "Výklad se staví zabalený.");

            view.open(ContinuityView.Outlook.RESTAMP);

            assertTrue(bodyVisible(view,
                    "Jaké možnosti udržování digitální kontinuity nabízí AnEP"),
                    "Vnořený blok uvnitř zavřeného obalu by se neukázal.");
            assertTrue(bodyVisible(view, "varianta „přerazítkování“"),
                    "Odkaz má otevřít právě tu variantu, na kterou míří.");
            assertFalse(bodyVisible(view, "varianta „uchování“ a „prodloužení“"),
                    "Druhá varianta se otevírat nemá — čtenář si o ni neřekl.");

            assertTrue(arrowPointsDown(view, "varianta „přerazítkování“"),
                    "Šipka musí říkat totéž co tělo, jinak blok vypadá zavřený.");
            return null;
        });
    }

    @Test
    @DisplayName("Odkaz na celý výklad otevře obal a vnořené nechá být")
    void openingTheOutlookLeavesVariantsAlone() {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit nenaběhl");
        onFxThread(() -> {
            ContinuityView view = new ContinuityView();
            view.open(ContinuityView.Outlook.OPTIONS);
            assertTrue(bodyVisible(view,
                    "Jaké možnosti udržování digitální kontinuity nabízí AnEP"));
            assertFalse(bodyVisible(view, "varianta „uchování“ a „prodloužení“"));
            assertFalse(bodyVisible(view, "varianta „přerazítkování“"));
            return null;
        });
    }

    @Test
    @DisplayName("Opakované otevření blok nezavře")
    void openingTwiceKeepsItOpen() {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit nenaběhl");
        onFxThread(() -> {
            ContinuityView view = new ContinuityView();
            view.open(ContinuityView.Outlook.PRESERVE_PROLONG);
            view.open(ContinuityView.Outlook.PRESERVE_PROLONG);
            assertTrue(bodyVisible(view, "varianta „uchování“ a „prodloužení“"),
                    "Druhé kliknutí na odkaz je opakovaná otázka, ne přepínač.");
            return null;
        });
    }

    /** Je tělo bloku s tímto nadpisem vidět? */
    private static boolean bodyVisible(ContinuityView view, String title) {
        VBox box = blockOf(view, title);
        return box != null && box.getChildren().size() > 1 && box.getChildren().get(1).isVisible();
    }

    /** Šipka ze záhlaví bloku s tímto nadpisem. */
    /**
     * Říká šipka toho bloku „otevřeno“?
     *
     * <p>Šipka je kreslený trojúhelník, ne znak, takže se čte z jeho bodů: otevřený blíok
     * má špičku dolů (trojúhelník je širší než vyšší), zavřený doprava (vyšší než širší).
     */
    private static boolean arrowPointsDown(ContinuityView view, String title) {
        VBox box = blockOf(view, title);
        if (box == null) return false;
        for (Node n : ((Parent) box.getChildren().get(0)).getChildrenUnmodifiable()) {
            if (n instanceof javafx.scene.layout.StackPane sp && sp.getChildren().size() == 1
                    && sp.getChildren().get(0) instanceof javafx.scene.shape.Polygon tri) {
                var b = tri.getLayoutBounds();
                return b.getWidth() > b.getHeight();
            }
        }
        return false;
    }

    /**
     * Uzel bloku, který {@code collapsibleBlock} postavil pro tenhle nadpis.
     *
     * <p>Obsah {@link javafx.scene.control.ScrollPane} se prochází zvlášť: bez scény nemá skin
     * a přes {@code getChildrenUnmodifiable()} se k němu nedostaneme. Bez toho tenhle test
     * nenašel ani jeden blok a tvrdil, že odkaz nic neotevřel.
     */
    private static VBox blockOf(ContinuityView view, String title) {
        List<Node> stack = new ArrayList<>();
        stack.add(view.getRoot());
        while (!stack.isEmpty()) {
            Node n = stack.remove(stack.size() - 1);
            if (n instanceof Label l && title.equals(l.getText())) {
                // Label → HBox záhlaví → VBox bloku.
                Parent header = l.getParent();
                if (header != null && header.getParent() instanceof VBox box) return box;
            }
            if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
                stack.add(sp.getContent());
            }
            if (n instanceof Parent p) stack.addAll(p.getChildrenUnmodifiable());
        }
        return null;
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
