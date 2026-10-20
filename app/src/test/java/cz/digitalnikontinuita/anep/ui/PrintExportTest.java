package cz.digitalnikontinuita.anep.ui;

import javafx.application.Platform;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the values reach the exported PDF, not just the labels beside them.
 *
 * <p>They stopped doing so for six weeks without anything noticing: {@code extractText} listed the
 * concrete node classes it knew, the views moved their value fields to {@code TextArea} to make
 * them selectable, and every value silently became an empty cell. The links went the same way —
 * {@code Hyperlink} is not a {@code Button} but its sibling. Nothing here would have caught it
 * before, because nothing exercised the export at all.
 *
 * <p>Runs against the XSL-FO rather than the finished PDF: this is about what the scene-graph walk
 * extracts, and the FO is where that is decided.
 */
class PrintExportTest {

    private static boolean toolkitUp;

    @BeforeAll
    static void startToolkit() {
        toolkitUp = FxToolkit.available();
    }

    @Test
    @DisplayName("Hodnota v TextArea se dostane do exportu, ne jen její popisek")
    void textAreaValuesAreExported() {
        String fo = foOf(() -> {
            VBox card = new VBox();
            card.getChildren().add(row("Sériové číslo certifikátu:", new TextArea("11092618")));
            card.getChildren().add(row("Platný do:", new TextArea("7. 1. 2017")));
            return card;
        });

        assertTrue(fo.contains("Sériové číslo certifikátu:"), "popisek v exportu chybí");
        assertTrue(fo.contains("11092618"), "hodnota z TextArea v exportu chybí:\n" + fo);
        assertTrue(fo.contains("7. 1. 2017"), "druhá hodnota v exportu chybí");
    }

    @Test
    @DisplayName("Odkazy se exportují svým textem, ačkoli Hyperlink není Button")
    void hyperlinkTextIsExported() {
        String fo = foOf(() -> {
            VBox card = new VBox();
            card.getChildren().add(row("Certifikační cesta:", new Hyperlink("I.CA Qualified CA")));
            TextFlow flow = new TextFlow(new Text("revokace přes "),
                    new Hyperlink("CRL č. 7896"), new Text(" ze dne 7. 1. 2017"));
            card.getChildren().add(flow);
            return card;
        });

        assertTrue(fo.contains("I.CA Qualified CA"), "text odkazu v exportu chybí:\n" + fo);
        assertTrue(fo.contains("CRL č. 7896"), "odkaz uvnitř TextFlow v exportu chybí");
        assertTrue(fo.contains("ze dne 7. 1. 2017"), "text kolem odkazu v exportu chybí");
    }

    @Test
    @DisplayName("Hodnota ve vnořeném kontejneru se neztratí")
    void valuesInsideNestedContainersSurvive() {
        String fo = foOf(() -> {
            VBox inner = new VBox(new TextArea("kvalifikovaný elektronický podpis"));
            VBox card = new VBox();
            card.getChildren().add(row("Právní účinky:", inner));
            return card;
        });

        assertTrue(fo.contains("kvalifikovaný elektronický podpis"),
                "hodnota z vnořeného kontejneru v exportu chybí:\n" + fo);
    }

    @Test
    @DisplayName("Zabalený blok se do exportu dostane celý")
    void collapsedBlockContentIsExported() {
        String fo = foOf(() -> {
            VBox body = new VBox();
            body.getChildren().add(row("Sériové číslo certifikátu:", new TextArea("11092618")));
            VBox card = new VBox();
            card.getChildren().add(AdvisorView.collapsibleBlock("Certifikát", body, false));
            return card;
        });

        assertTrue(fo.contains("Certifikát"), "nadpis bloku v exportu chybí:\n" + fo);
        assertTrue(fo.contains("11092618"),
                "obsah zabaleného bloku v exportu chybí — protokol by odešel bez toho, "
                + "co má čtenář zavřené:\n" + fo);
    }

    @Test
    @DisplayName("Vizualizace podpisu se do protokolu opravdu vysází, ne jen její popisek")
    void signatureAppearanceImageIsExported() {
        // Skutecny blok z karty, ne rucne poskladany: obrazek v nem lezi ve StackPane a prave
        // ten byl pricinou. Test s ImageView primo ve VBoxu prosel i nad rozbitym pruchodem.
        String fo = foOf(() -> new VBox(ResultView.buildAppearanceSection(onePixelPng())));

        assertTrue(fo.contains("Vizualizace podpisu:"), "popisek v exportu chybi:\n" + fo);
        assertTrue(fo.contains("fo:external-graphic"),
                "obrazek v exportu chybi \u2014 protokol by nesl popisek a pod nim nic:\n" + fo);
    }

    /** Nejmensi platne PNG: jeden pruhledny bod. Jde o to, ze se obrazek vysadi, ne co je na nem. */
    private static byte[] onePixelPng() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                + "YPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
    }

    @Test
    @DisplayName("Vyklad druhu podpisu se v protokolu uvozuje \u201etj.: \u201c, bez zavorek")
    void legalTypeExplanationIsIntroducedNotBracketed() {
        String fo = foOf(() -> {
            Label lbl = new Label("Jde o:");
            lbl.setMinWidth(160);
            lbl.setStyle("-fx-font-weight: bold;");

            Label icon = new Label("\u24d8");
            javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip(
                    ExtendedView.legalTypeTooltip("kvalifikovaný elektronický podpis"));
            javafx.scene.control.Tooltip.install(icon, tip);

            TextFlow value = new TextFlow(new Text("kvalifikovaný elektronický podpis"), icon);
            return new VBox(new HBox(lbl, value));
        });

        assertTrue(fo.contains("tj.: kvalifikovaný elektronický podpis (QESig)"),
                "vyklad ma stat na vlastni radce za \u201etj.: \u201c a zacinat malym pismenem:\n" + fo);
        assertFalse(fo.contains("(kvalifikovaný elektronický podpis (QESig)"),
                "vyklad se uz nema sazet v zavorce:\n" + fo);
    }

    @Test
    @DisplayName("Zadny vyklad druhu podpisu nezacina velkym pismenem")
    void everyLegalTypeExplanationStartsLowerCase() {
        for (String type : new String[] {
                "uznávaný elektronický podpis", "kvalifikovaný elektronický podpis",
                "uznávanou elektronickou pečeť", "kvalifikovanou elektronickou pečeť",
                "uznávanou elektronickou značku", "elektronickou značku" }) {
            String text = ExtendedView.legalTypeTooltip(type);
            assertNotNull(text, "vyklad chybi pro: " + type);
            char first = text.charAt(0);
            assertTrue(Character.isLowerCase(first),
                    "vyklad navazuje na \u201eJde o:\u201c / \u201etj.: \u201c, takze nezacina vetu \u2014 "
                    + type + ": " + text);
        }
    }

    @Test
    @DisplayName("Rozsireny prehled: bloky bez nadpisu a oddelene jedinou carou")
    void plainBlocksDropTheHeadingAndTheSecondRule() {
        String withHeadings = foOf(() -> blockCard(false));
        String plain = foOf(() -> blockCard(true));

        assertTrue(withHeadings.contains("Certifikát"),
                "bez priznaku ma nadpis bloku zustat:\n" + withHeadings);
        assertFalse(plain.contains("Certifikát"),
                "s priznakem se nadpis bloku sazet nema:\n" + plain);
        assertTrue(plain.contains("11092618"), "obsah bloku ma zustat:\n" + plain);

        // Porovnava se rozdil, ne absolutni pocet: vlastni caru ma i zahlavi protokolu, takze
        // nula tu nikdy nebude a pevne cislo by test svazalo s podobou zahlavi.
        assertEquals(rules(plain) + 1, rules(withHeadings),
                "nadpis bloku pridaval druhou caru; bez nej ma zbyt jedina, od Separatoru");
    }

    @Test
    @DisplayName("Blok si nadpis v protokolu může vyžádat i tam, kde se nadpisy nesázejí")
    void aBlockMayKeepItsHeadingInPlainMode() {
        // Přesně případ bloku „Odkazované dokumenty z eLegalizace“: jeho řádky nemluví
        // o posuzovaném podpisu, nýbrž o jiném souboru, takže bez nadpisu by v protokolu
        // splynuly s údaji o certifikátu nad nimi.
        String plain = foOf(() -> {
            VBox body = new VBox();
            body.getChildren().add(row("Příloha s otisky:", new TextArea("legalisation-metadata.xml")));
            VBox card = new VBox();
            VBox block = AdvisorView.collapsibleBlock("Odkazované dokumenty z eLegalizace",
                    body, false);
            block.getProperties().put(PrintUtil.KEEP_TITLE, Boolean.TRUE);
            card.getChildren().add(block);
            card.getProperties().put(PrintUtil.PLAIN_BLOCKS, Boolean.TRUE);
            return card;
        });

        assertTrue(plain.contains("Odkazované dokumenty z eLegalizace"),
                "vyžádaný nadpis má v protokolu zůstat:\n" + plain);
        assertTrue(plain.contains("legalisation-metadata.xml"), plain);

        // Nadpis, který v protokolu stojí sám mezi řádky ostatních bloků, musí být vidět jako
        // nadpis — proto šedý podklad, týž jako má otevřený blok na kartě.
        int nadpis = plain.indexOf("Odkazované dokumenty z eLegalizace");
        String blok = plain.substring(Math.max(0, plain.lastIndexOf("<fo:block", nadpis)), nadpis);
        assertTrue(blok.contains("background-color=\"#e4e4e4\""),
                "nadpis vyžádaného bloku má mít šedý podklad: " + blok);
    }

    /** Kolik vodorovnych car protokol nese. */
    private static int rules(String fo) {
        int n = 0;
        for (int i = fo.indexOf("border-top"); i >= 0; i = fo.indexOf("border-top", i + 1)) n++;
        for (int i = fo.indexOf("border-bottom"); i >= 0; i = fo.indexOf("border-bottom", i + 1)) n++;
        return n;
    }

    /** Karta s jednim pojmenovanym blokem, jak ji sklada Rozsireny prehled. */
    private static VBox blockCard(boolean plainBlocks) {
        VBox body = new VBox();
        body.getChildren().add(row("Sériové číslo certifikátu:", new TextArea("11092618")));
        VBox card = new VBox();
        card.getChildren().add(new javafx.scene.control.Separator());
        card.getChildren().add(AdvisorView.collapsibleBlock("Certifikát", body, false));
        if (plainBlocks) card.getProperties().put(PrintUtil.PLAIN_BLOCKS, Boolean.TRUE);
        return card;
    }

    @Test
    @DisplayName("Zahlavi protokolu nese okamzik overeni, kdyz je znam")
    void headerCarriesTheVerificationTime() {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit není k dispozici");
        java.util.concurrent.atomic.AtomicReference<String> out = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Platform.runLater(() -> {
            out.set(PrintUtil.buildFo(new VBox(), null, null, "Rozšířený přehled",
                    java.time.Instant.parse("2026-08-31T09:15:00Z"), java.util.List.of()));
            done.countDown();
        });
        try {
            assertTrue(done.await(30, TimeUnit.SECONDS), "vlakno JavaFX neodpovedelo");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        assertTrue(out.get().contains("Ověřování proběhlo:"),
                "zahlavi neuvadi, kdy overeni probehlo:\n" + out.get());
    }

    // ------------------------------------------------------------------------------------ helpers

    /** A label-value row shaped the way {@code isDataRow} recognises one. */
    private static HBox row(String label, javafx.scene.Node value) {
        Label l = new Label(label);
        l.setMinWidth(160);
        l.setStyle("-fx-font-weight: bold;");
        return new HBox(l, value);
    }

    /** Builds the scene graph on the FX thread and returns the XSL-FO it produces. */
    private static String foOf(Supplier<VBox> graph) {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit není k dispozici");
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(PrintUtil.buildFo(graph.get(), null, null, "Zjednodušený protokol", null, java.util.List.of()));
            } catch (Throwable t) {
                failure.set(t);
            }
            done.countDown();
        });
        try {
            assertTrue(done.await(30, TimeUnit.SECONDS), "vlákno JavaFX neodpovědělo");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }
}
