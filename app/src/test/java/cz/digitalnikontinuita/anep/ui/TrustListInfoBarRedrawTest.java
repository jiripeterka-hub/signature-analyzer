package cz.digitalnikontinuita.anep.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hlavička o důvěryhodných seznamech se musí dát překreslit i mimo vykreslení celé karty.
 *
 * <p>Dřív se stavěla nanovo jedině při {@code show(result)}, takže obnova seznamů v Nastavení stav
 * opravila, ale varování „Chybějící důvěryhodné seznamy" viselo dál až do dalšího ověření —
 * uživateli to vypadalo, že pokyn k načtení nic neudělal, i když udělal. Tenhle test drží to, co
 * z toho plyne: uzel je trvalý a stav se do něj promítne hned, bez ověřování mezi tím.
 */
class TrustListInfoBarRedrawTest {

    private static boolean toolkitUp;

    @BeforeAll
    static void startToolkit() {
        toolkitUp = FxToolkit.available();
    }

    @Test
    @DisplayName("Značka o chybějícím seznamu zmizí hned po změně stavu, bez dalšího ověření")
    void missingBadgeAppearsAndGoesOnStateChange() {
        Boolean[] seen = onFxThread(() -> {
            TrustListInfoBar bar = new TrustListInfoBar();
            Parent node = bar.node();

            bar.set(System.currentTimeMillis(), false, null, List.of(), List.of());
            boolean clean = hasMissingBadge(node);

            // Totéž, co po neúspěšné obnově vrátí TrustConfig.getEmptyTerritories().
            bar.set(System.currentTimeMillis(), false, null, List.of(),
                    List.of("https://eidas.gov.ie/Irelandtslsignedv6.xml"));
            boolean missing = hasMissingBadge(node);

            // A po úspěšné obnově — týž uzel, žádné nové vykreslení karty.
            bar.set(System.currentTimeMillis(), false, null, List.of(), List.of());
            boolean fixed = hasMissingBadge(node);

            return new Boolean[] { clean, missing, fixed };
        });

        assertFalse(seen[0], "Bez chybějících seznamů nemá být co ukazovat.");
        assertTrue(seen[1], "Chybějící seznam se musí ve značce projevit hned.");
        assertFalse(seen[2], "Po opravě stavu musí značka zmizet bez dalšího ověření.");
    }

    @Test
    @DisplayName("Prázdná hlavička nezabírá místo nad kartou")
    void emptyBarIsNotManaged() {
        Boolean[] seen = onFxThread(() -> {
            TrustListInfoBar bar = new TrustListInfoBar();
            bar.set(-1, false, null, List.of(), List.of());
            boolean emptyManaged = bar.node().isManaged();
            bar.set(System.currentTimeMillis(), false, null, List.of(), List.of());
            boolean filledManaged = bar.node().isManaged();
            return new Boolean[] { emptyManaged, filledManaged };
        });

        // Neviditelný, ale spravovaný uzel by si nad kartou pořád držel svou výšku i odsazení.
        assertFalse(seen[0], "Prázdná hlavička nesmí být spravovaná.");
        assertTrue(seen[1], "Hlavička s obsahem se vykreslit musí.");
    }

    /** Červená ⚠ značka „chybějící seznamy" kdekoli pod uzlem. */
    private static boolean hasMissingBadge(Node n) {
        if (n instanceof Label l && "⚠".equals(l.getText())
                && l.getStyle() != null && l.getStyle().contains("#b00020")) return true;
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) if (hasMissingBadge(c)) return true;
        }
        return false;
    }

    private static <T> T onFxThread(Supplier<T> work) {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit není k dispozici");
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { out.set(work.get()); }
            catch (Throwable t) { failure.set(t); }
            finally { done.countDown(); }
        });
        try {
            assertTrue(done.await(30, TimeUnit.SECONDS), "vlákno JavaFX neodpovědělo");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        return out.get();
    }
}
