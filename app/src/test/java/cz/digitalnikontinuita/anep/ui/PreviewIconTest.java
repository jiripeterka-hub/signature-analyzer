package cz.digitalnikontinuita.anep.ui;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.shape.SVGPath;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Vznikne z cesty ikony náhledu skutečný tvar?
 *
 * <p>Ikona listu s lupou (vybraná uživatelem 18. 9. 2026) je zatím jediná v hlavičce, která
 * používá <b>relativní</b> příkazy ({@code a}, {@code h}, {@code v} místo velkých). Vykreslila se
 * při výběru, jenže parser JavaFX není parser prohlížeče — a špatná cesta by se neprojevila
 * překladem, nýbrž prázdným tlačítkem, nebo výjimkou při startu aplikace.
 *
 * <p>Zkouší se rozměr, ne vzhled: tvar musí vyplnit podstatnou část mřížky 24 × 24, jinak se cesta
 * nerozparsovala, jak měla. Meze jsou schválně volné — přesnými čísly by zkouška hlídala tu
 * konkrétní kresbu, a ta se má dát vyměnit bez toho, aby padala. Jak ikona vypadá, rozhoduje oko
 * nad vykreslením; na to zkouška není a být nemá.
 */
class PreviewIconTest {

    private static boolean toolkitUp;

    @BeforeAll
    static void startToolkit() {
        toolkitUp = FxToolkit.available();
    }

    @Test
    @DisplayName("Ikona náhledu se rozparsuje do tvaru o rozumné velikosti")
    void thePreviewIconParsesIntoAShape() {
        Bounds bounds = onFx(() -> {
            try {
                Method m = MainView.class.getDeclaredMethod("previewIcon");
                m.setAccessible(true);
                SVGPath icon = (SVGPath) m.invoke(null);
                // Bez měřítka: zajímá nás, co vyšlo z cesty, ne jak se pak zvětšila.
                icon.setScaleX(1);
                icon.setScaleY(1);
                return icon.getBoundsInLocal();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        // Mřížka je 24 × 24; obrys s tloušťkou tahu 2 se z ní může o kus vylít, ale tvar ji má
        // z podstatné části vyplnit. Nula nebo pár bodů znamená nerozparsovanou cestu.
        assertTrue(bounds.getWidth() > 10 && bounds.getWidth() < 30,
                "Šířka tvaru je " + bounds.getWidth() + ", čekalo se v řádu mřížky 24");
        assertTrue(bounds.getHeight() > 10 && bounds.getHeight() < 30,
                "Výška tvaru je " + bounds.getHeight() + ", čekalo se v řádu mřížky 24");
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
