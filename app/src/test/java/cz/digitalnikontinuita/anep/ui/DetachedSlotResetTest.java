package cz.digitalnikontinuita.anep.ui;

import javafx.application.Platform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Slot pro externí podpis a to, kdy se zapomene.
 *
 * <p>Nález uživatele z 20. 9. 2026: „vložím nepodepsaný soubor a k němu externí podpis, ověřím,
 * a pak vložím nový soubor; ten externí tam stále zůstává a musí se mazat.“ Externí podpis patří
 * k jednomu určitému dokumentu a vedle jiného neznamená nic — jenže to platí o dokumentu
 * předloženém <b>po ověření</b>, ne o dokumentu, kterým se dvojice teprve skládá. Obě strany
 * drží tenhle test, protože je snadné opravit jednu a rozbít druhou; přesně to se v obou směrech
 * stalo (commit 08a6135 a pak tenhle).
 *
 * <h2>Proč reflexí a proč bez ověřování</h2>
 *
 * <p>{@code acceptSigFile}, {@code acceptOrigFile} i příznak {@code verificationRan} jsou
 * v {@link MainView} soukromé a mají být; zveřejnit je kvůli zkoušce by bylo obráceně (táž úvaha
 * jako u sond v {@code tools/}). Ověřování se nespouští vůbec — měří se přepínání slotů, a ke
 * skutečné validaci by bylo potřeba PDF, důvěryhodné seznamy a vteřiny navíc. Příznak se proto
 * nastavuje přímo, tak jako ho nastaví začátek {@code analyze()}.
 */
class DetachedSlotResetTest {

    private static boolean toolkitUp;

    @BeforeAll
    static void startToolkit() {
        toolkitUp = FxToolkit.available();
    }

    @Test
    @DisplayName("Dokument vložený po ověření zapomene externí podpis i paměť tlačítka ↺")
    void aDocumentDroppedAfterAVerificationForgetsTheDetachedFile(@TempDir Path dir)
            throws Exception {
        File content = file(dir, "obsah.txt");
        File detached = file(dir, "podpis.p7s");
        File fresh = file(dir, "jiny-dokument.pdf");

        onFx(() -> {
            MainView view = new MainView();
            invoke(view, "acceptSigFile", content);
            invoke(view, "acceptOrigFile", detached);
            assertEquals(detached, field(view, "origFile"), "Dvojice se právě složila");

            // Co udělá začátek analyze(): od téhle chvíle je dvojice uzavřená věc.
            set(view, "verificationRan", true);
            invoke(view, "resetFileZones");

            invoke(view, "acceptSigFile", fresh);
            assertNull(field(view, "origFile"),
                    "Externí podpis od předchozího dokumentu tu nemá co dělat");
            assertNull(field(view, "lastOrigFile"),
                    "A nesmí se vrátit ani obnovou — o to uživateli šlo především");
            assertEquals(fresh, field(view, "lastSigFile"), "Obnova má vrátit nový dokument");
            return null;
        });
    }

    @Test
    @DisplayName("Dokument podaný jako druhá polovina dvojice externí podpis nechá být")
    void aDocumentCompletingThePairKeepsTheDetachedFile(@TempDir Path dir) throws Exception {
        File detached = file(dir, "podpis.p7s");
        File content = file(dir, "obsah.txt");

        onFx(() -> {
            MainView view = new MainView();
            // Pořadí, které je přirozené, když má člověk v ruce ten podpis.
            invoke(view, "acceptOrigFile", detached);
            invoke(view, "acceptSigFile", content);

            assertEquals(detached, field(view, "origFile"),
                    "Nic se neověřovalo, takže se dvojice teprve skládá a mazat se nemá co");
            return null;
        });
    }

    @Test
    @DisplayName("Tlačítko ↺ vrátí obě poloviny i po ověření")
    void theRestoreButtonStillBringsBothHalvesBack(@TempDir Path dir) throws Exception {
        File content = file(dir, "obsah.txt");
        File detached = file(dir, "podpis.p7s");

        onFx(() -> {
            MainView view = new MainView();
            invoke(view, "acceptSigFile", content);
            invoke(view, "acceptOrigFile", detached);
            set(view, "verificationRan", true);
            invoke(view, "resetFileZones");

            restore(view);
            assertEquals(content, field(view, "sigFile"));
            assertEquals(detached, field(view, "origFile"),
                    "Obnova si hodnoty odkládá stranou dřív, než sáhne na zóny");
            return null;
        });
    }

    /** Stiskne tlačítko ↺ — hledá se podle popisku, stejně jako ho najde oko uživatele. */
    private static void restore(MainView view) {
        for (javafx.scene.Node n : all(view.getRoot())) {
            if (n instanceof javafx.scene.control.Button b && "↺".equals(b.getText())) {
                b.fire();
                return;
            }
        }
        fail("Tlačítko ↺ v okně není");
    }

    private static java.util.List<javafx.scene.Node> all(javafx.scene.Node n) {
        java.util.List<javafx.scene.Node> out = new java.util.ArrayList<>();
        walk(n, out);
        return out;
    }

    private static void walk(javafx.scene.Node n, java.util.List<javafx.scene.Node> out) {
        out.add(n);
        if (n instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node c : p.getChildrenUnmodifiable()) walk(c, out);
        }
    }

    private static File file(Path dir, String name) throws Exception {
        File f = dir.resolve(name).toFile();
        Files.writeString(f.toPath(), "x");
        return f;
    }

    private static void invoke(MainView view, String method, File arg) {
        try {
            Method m = MainView.class.getDeclaredMethod(method, File.class);
            m.setAccessible(true);
            m.invoke(view, arg);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void invoke(MainView view, String method) {
        try {
            Method m = MainView.class.getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(view);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object field(MainView view, String name) {
        try {
            Field f = MainView.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(view);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void set(MainView view, String name, Object value) {
        try {
            Field f = MainView.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(view, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static <T> T onFx(Supplier<T> work) {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit nenaběhl");
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { out.set(work.get()); }
            catch (Throwable t) { failure.set(t); }
            finally { done.countDown(); }
        });
        try {
            assertTrue(done.await(60, TimeUnit.SECONDS), "vlákno JavaFX neodpovědělo");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        return out.get();
    }
}
