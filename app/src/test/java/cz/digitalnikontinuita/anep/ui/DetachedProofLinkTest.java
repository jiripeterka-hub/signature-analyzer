package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ScrollPane;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Odkaz „Ověřit podpisy dokumentu PDF s důkazem existence z externího podpisu" na kartě Analýzy
 * (1. 11. 2026): objeví se jen u dokumentu PDF, který k externímu podpisu nese i vlastní podpisy,
 * a stisk ho skutečně předá aplikaci.
 */
class DetachedProofLinkTest {

    private static final String LABEL =
            "Ověřit podpisy dokumentu PDF s důkazem existence z externího podpisu";

    @Test
    @DisplayName("odkaz je u PDF s vlastními podpisy a stisk zavolá aplikaci")
    void linkFiresCallback() throws Exception {
        Assumptions.assumeTrue(FxToolkit.available(), "JavaFX toolkit není k dispozici");
        AtomicInteger calls = new AtomicInteger();
        Hyperlink link = onFx(() -> findLink(render(1, calls::incrementAndGet)));
        assertNotNull(link, "odkaz na kartě chybí");
        onFx(() -> { link.fire(); return null; });
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("bez vlastních podpisů dokumentu se odkaz nenabízí")
    void noLinkWithoutContentSignatures() throws Exception {
        Assumptions.assumeTrue(FxToolkit.available(), "JavaFX toolkit není k dispozici");
        assertNull(onFx(() -> findLink(render(0, () -> { }))));
    }

    private static Parent render(int contentSignatures, Runnable callback) {
        ValidationResult result = new ValidationResult("dokument.pdf", "PDF", true, List.of(),
                null, null, null);
        result.setContentPdfSignatures(contentSignatures, 0);
        AdvisorView view = new AdvisorView();
        view.setAppSettings(new AppSettings());
        view.setDetachedProofVerifyCallback(callback);
        view.show(result);
        return (Parent) view.getRoot();
    }

    private static Hyperlink findLink(Node node) {
        if (node instanceof Hyperlink h && LABEL.equals(h.getText())) return h;
        if (node instanceof ScrollPane sp && sp.getContent() != null) return findLink(sp.getContent());
        if (node instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                Hyperlink found = findLink(c);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(work.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(30, TimeUnit.SECONDS);
    }
}
