package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.TsaProfile;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.RadioButton;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Vejde se do okna „Přidat TSA“ všechno, co v něm je?
 *
 * <p>Nález uživatele z 18. 9. 2026: okno s přihlášením klientským certifikátem se otevřelo nižší,
 * než jak vysoký byl jeho obsah, takže pole na heslo k úložišti bylo uříznuté a na tlačítko OK se
 * nedalo dosáhnout vůbec. Příčinou byl zalamovaný popisek: JavaFX počítá nejmenší výšku popisku
 * z <b>jednoho řádku</b>, takže scéna se nastavila na míru textu, který se ve skutečnosti zalomil
 * do tří.
 *
 * <p>Je to vada, kterou překlad nechytí a oko jen po proklikání okna — a proklikat je třeba
 * všechny tři stavy přihlášení, protože každý má jinou výšku. Proto tahle zkouška: postaví obsah
 * okna v každém z nich, najde nejníže položený ovládací prvek a porovná, kde končí, s výškou
 * scény, která se z toho obsahu spočítá.
 *
 * <p><b>Bez modálního okna.</b> První podoba zkoušky otevírala skutečný dialog a čekala, až
 * {@code showAndWait()} skončí. Zasekávala se, pokaždé u jiného stavu — a zkouška, která se
 * zasekne, neodliší vadu od vlastní nespolehlivosti. Proto vznikl šev
 * {@link TsaManagerDialog#buildEditForm}: obsah se postaví a změří, aniž by se okno ukázalo
 * modálně.
 */
class TsaEditDialogSizeTest {

    private static boolean toolkitUp;

    @BeforeAll
    static void startToolkit() {
        toolkitUp = FxToolkit.available();
    }

    @Test
    @DisplayName("Okno „Přidat TSA“ pojme celý obsah — klientský certifikát")
    void theEditDialogFitsWithAClientCertificate() {
        check("klientský certifikát", TsaProfile.postSignumDemoCertificate());
    }

    @Test
    @DisplayName("Okno „Přidat TSA“ pojme celý obsah — jméno a heslo")
    void theEditDialogFitsWithBasicAuth() {
        check("jméno a heslo", TsaProfile.postSignumDemoBasic());
    }

    @Test
    @DisplayName("Okno „Přidat TSA“ pojme celý obsah — bez přihlášení")
    void theEditDialogFitsWithoutAuth() {
        check("bez přihlášení", TsaProfile.sectigoQualified());
    }

    /**
     * Přepnutí způsobu přihlášení mění výšku obsahu a okno se na to má srovnat.
     *
     * <p>Je to <b>jiný stav než okno otevřené rovnou na certifikát</b>: tam se velikost počítá
     * při zobrazení, tady až za běhu. Uživatel na pole s heslem nedosáhl právě takhle.
     */
    @Test
    @DisplayName("Po přepnutí na klientský certifikát se okno srovná s novou výškou")
    void switchingToCertificateResizesTheWindow() {
        String verdict = onFx(() -> {
            Stage host = new Stage();
            VBox root = TsaManagerDialog.buildEditForm(host, TsaProfile.postSignumDemoBasic(),
                    true, new TsaProfile[1]);
            host.setResizable(true);
            host.setScene(new Scene(root));
            host.show();
            settle(root);
            // Podle popisku, ne podle pořadí prvků ve formuláři: přeskládání formuláře nemá
            // zkoušku tiše obrátit v měření něčeho jiného.
            for (Node n : all(root)) {
                if (n instanceof RadioButton rb && rb.getText() != null
                        && rb.getText().startsWith("Klientský certifikát")) {
                    rb.fire();
                    break;
                }
            }
            settle(root);
            String out = measure(host.getScene());
            host.close();
            return out;
        });
        assertTrue(verdict.startsWith("OK"),
                "Po přepnutí na certifikát obsah přetéká pod spodní hranu: " + verdict);
    }

    private static void check(String what, TsaProfile profile) {
        String verdict = onFx(() -> {
            Stage host = new Stage();
            VBox root = TsaManagerDialog.buildEditForm(host, profile, true, new TsaProfile[1]);
            host.setResizable(true);
            host.setScene(new Scene(root));
            host.show();
            settle(root);
            String out = measure(host.getScene());
            host.close();
            return out;
        });
        assertTrue(verdict.startsWith("OK"),
                "Obsah okna sahá pod jeho spodní hranu — " + what + ": " + verdict);
    }

    /** Dopočítá styly a rozvržení. Bez toho vracejí souřadnice nuly a zkouška projde vždycky. */
    private static void settle(Parent root) {
        root.applyCss();
        root.layout();
    }

    /** „OK …“, nebo popis toho, co zůstalo pod spodní hranou. */
    private static String measure(Scene scene) {
        Node lowest = null;
        double bottom = 0;
        for (Node n : all(scene.getRoot())) {
            if (!n.isVisible() || !(n instanceof Control)) continue;
            Bounds b = n.localToScene(n.getBoundsInLocal());
            if (b.getHeight() <= 0) continue;
            if (b.getMaxY() > bottom) {
                bottom = b.getMaxY();
                lowest = n;
            }
        }
        String where = String.format(
                "scéna %.0f×%.0f, nejnižší prvek končí na %.0f, sám má %.0f×%.0f (%s)",
                scene.getWidth(), scene.getHeight(), bottom,
                lowest == null ? 0 : lowest.getBoundsInLocal().getWidth(),
                lowest == null ? 0 : lowest.getBoundsInLocal().getHeight(),
                name(lowest));
        return (bottom <= scene.getHeight() + 0.5 ? "OK — " : "PŘETÉKÁ — ") + where;
    }

    private static String name(Node n) {
        if (n instanceof Labeled l && l.getText() != null && !l.getText().isBlank()) {
            return "„" + l.getText() + "“";
        }
        return String.valueOf(n);
    }

    private static List<Node> all(Node root) {
        List<Node> out = new ArrayList<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            out.add(n);
            if (n instanceof Parent p) queue.addAll(p.getChildrenUnmodifiable());
        }
        return out;
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
