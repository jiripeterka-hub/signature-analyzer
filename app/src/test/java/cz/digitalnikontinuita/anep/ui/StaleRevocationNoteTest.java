package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Poznámka situace 51 na kartě s neznámou platností — kde stojí a jak vypadá.
 *
 * <p>Otázka uživatele z 22. 9. 2026 nad pečetí bez vlastního razítka: je vhodné ukazovat tuhle
 * výhradu, když ověřování stejně skončilo neznámým výsledkem? Poznámka tehdy stála na konci bloku
 * „Zjištěné důvody a doporučení“, který začíná větou „Aplikace detekovala následující důvod(y)
 * neúspěšného ověření“ — a četla se tedy jako další důvod, ačkoli důvod není. Od té chvíle stojí
 * v bloku „Poznámky k výsledku“, jak je zvykem na kartě s kladným výsledkem.
 *
 * <p>Druhá polovina zkoušky je o vzhledu: o tom rozhoduje vykreslení, ne úvaha, takže se šířka
 * tlačítka měří.
 */
class StaleRevocationNoteTest {

    /** Text tak, jak ho jádro vysází nad dokumentem, kterým se na to přišlo. */
    private static final String DETAIL =
            "Nejnovější revokační informace, které má aplikace k dispozici o certifikátu"
            + " pečeti, vznikly 1 hodinu před zapečetěním (10. 7. 2019 6:12:17 UTC"
            + " (8:12:17 SELČ)) a platily do 11. 7. 2019 6:12:17 UTC (8:12:17 SELČ), okamžik"
            + " zapečetění tedy pokrývají. Odvolání, ke kterému by došlo mezi jejich vydáním"
            + " a zapečetěním, by se ale projevilo až v následujících revokačních"
            + " informacích.";

    private static boolean toolkitUp;

    @BeforeAll
    static void startToolkit() {
        toolkitUp = FxToolkit.available();
    }

    /** Pečeť z roku 2019: jedinou překážkou je expirovaný certifikát bez razítka (důvod 4). */
    private static SignatureSummary sealWithStaleRevocation() {
        return SigFixture.sig()
                .indication("INDETERMINATE")
                .seal()
                .situations(51)
                .reasons(4)
                .staleRevocation(DETAIL)
                .build();
    }

    @Test
    @DisplayName("Poznámka stojí v bloku „Poznámky k výsledku“, ne mezi důvody")
    void theNoteLivesInTheNotesBlock() {
        Assumptions.assumeTrue(toolkitUp, "JavaFX se nepodařilo nastartovat");

        List<String> texts = onFx(() -> {
            VBox card = new VBox();
            AdvisorView.addResultNotesBlock(card, sealWithStaleRevocation());
            return labels(card);
        });

        assertTrue(texts.contains("Poznámky k výsledku"),
                "blok se nepostavil nebo se jmenuje jinak: " + texts);
        assertTrue(texts.stream().anyMatch(t -> t.equals(DETAIL)),
                "poznámka v bloku není: " + texts);
        assertTrue(texts.stream().noneMatch(t -> t.contains("neúspěšného ověření")),
                "poznámka zůstala ve výčtu důvodů: " + texts);
    }

    @Test
    @DisplayName("Bez situace 51 se blok nestaví vůbec")
    void withoutTheSituationThereIsNoBlock() {
        Assumptions.assumeTrue(toolkitUp, "JavaFX se nepodařilo nastartovat");

        int children = onFx(() -> {
            VBox card = new VBox();
            AdvisorView.addResultNotesBlock(card, SigFixture.sig()
                    .indication("INDETERMINATE").seal().reasons(4).build());
            return card.getChildren().size();
        });
        assertEquals(0, children, "prázdný blok „Poznámky k výsledku“ na kartě nemá co dělat");
    }

    /**
     * Tlačítko „Upozornění“ vedle poznámky se zmáčklo na výpustku — oranžový obdélníček se třemi
     * tečkami místo nápisu, vidět na screenshotu uživatele.
     *
     * <p>Nešlo o nedostatek místa: zalamovaný popisek hlásí preferovanou šířku celého textu na
     * jeden řádek, ta přeteče vždycky, a {@code HBox} pak zkracuje potomky na jejich nejmenší
     * šířku. Tlačítko bez minima skončilo na 25 bodech místo 71, a to i v okně širokém 1 200.
     * Měří se proto ve škále šířek, které karta v aplikaci má.
     */
    @Test
    @DisplayName("Tlačítko u poznámky se nezmáčkne na výpustku")
    void theWarningButtonKeepsItsLabel() {
        Assumptions.assumeTrue(toolkitUp, "JavaFX se nepodařilo nastartovat");

        StringBuilder log = new StringBuilder();
        boolean squeezed = false;
        for (double width : new double[]{700, 800, 900, 980, 1200}) {
            double[] m = onFx(() -> measureButton(width));
            log.append(String.format("%ncelková šířka %.0f: tlačítko %.1f, potřebuje %.1f",
                    width, m[0], m[1]));
            if (m[0] + 0.5 < m[1]) squeezed = true;
        }
        assertTrue(!squeezed, "tlačítko „Upozornění“ se v řádku zmáčklo:" + log);
    }

    /** @return dvojice {skutečná šířka tlačítka, šířka, kterou by potřebovalo celé}. */
    private static double[] measureButton(double width) {
        VBox body = new VBox(8);
        body.setPadding(new Insets(10));
        assertTrue(AdvisorView.addStaleRevocationRow(body, sealWithStaleRevocation()),
                "řádek s poznámkou se nepostavil");

        new Scene(body, width, 400);      // scéna dá rozvržení šířku, výšku si dopočítá samo
        body.applyCss();
        body.layout();

        HBox row = (HBox) body.getChildren().get(0);
        Button btn = (Button) row.getChildren().get(0);
        return new double[]{btn.getWidth(), btn.prefWidth(-1)};
    }

    // ------------------------------------------------------------------ nářadí

    /** Texty všech popisků ve stromu, v pořadí, v jakém se procházejí. */
    private static List<String> labels(Parent root) {
        List<String> out = new ArrayList<>();
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            if (n instanceof Label l && l.getText() != null) out.add(l.getText());
            if (n instanceof Parent p) queue.addAll(p.getChildrenUnmodifiable());
        }
        return out;
    }

    private static <T> T onFx(Supplier<T> work) {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                out.set(work.get());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(30, TimeUnit.SECONDS)) fail("vlákno JavaFX neodpovědělo");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
        if (err.get() != null) fail(err.get());
        return out.get();
    }
}
