package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.RevisionTail;
import cz.digitalnikontinuita.anep.model.ValidationResult;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Blok „co v souboru přibylo po podpisu": ukáže se jen tam, kde je co říct, a mluví podle toho,
 * co se doopravdy stalo.
 *
 * <p>Vzniklo s blokem 18. 9. 2026. Hlídá tři věci, které se dají tiše ztratit: že mlčí nad
 * dokumentem, u kterého se nestalo nic (dvě třetiny sbírky), že nad změněným obsahem mluví jinak
 * a otevře se sám, a že obvyklý případ nestraší — obsah se nezměnil a věta to má říct.
 */
class RevisionTailBlockTest {

    private static boolean toolkitUp;

    @BeforeAll
    static void startToolkit() {
        toolkitUp = FxToolkit.available();
    }

    @Test
    @DisplayName("Není-li za posledním prvkem nic, blok se nestaví vůbec")
    void noBlockWhenThereIsNothingToSay() {
        assertNull(RevisionTailBlock.build(result(null)),
                "Mlčení je u dvou třetin souborů správná odpověď a nesmí zabírat místo");
        assertNull(RevisionTailBlock.build(null));
    }

    @Test
    @DisplayName("Obvyklý případ: přibyla validační data, obsah se nezměnil")
    void validationDataOnly() {
        RevisionTail tail = new RevisionTail(RevisionTail.Element.PODPIS, 13_440, List.of(),
                true, false, false, false);
        String text = render(tail);

        assertTrue(text.contains("Po podepsání do souboru ještě něco přibylo"), text);
        assertTrue(text.contains("13 kB"), "Velikost se má říct v čitelné jednotce: " + text);
        assertTrue(text.contains("Obsah dokumentu se tím nezměnil"), text);
        // To podstatné, co se jinde nedozví: přibylé údaje nekryje nic.
        assertTrue(text.contains("nekryje žádný podpis ani časové razítko"), text);
        assertTrue(text.contains("Digitální kontinuita"),
                "Kde se s tím dá něco udělat, ví ta záložka: " + text);
        // Odkud se ty údaje berou — doplnění na přání uživatele 21. 9. 2026. Bez toho zůstávalo
        // u zjištění, že něco přibylo, ačkoli je to obvyklý a žádoucí zásah.
        assertTrue(text.contains("povýšení úrovně z T na LT"),
                "Věta má říct, čím ty údaje do dokumentu přišly: " + text);
    }

    /**
     * Věta o úrovni patří k rozšíření dokumentu, ne k ostatním nálezům: zásah do polí ani změna
     * anotací s úrovní podpisu nesouvisí.
     *
     * <p>Že smí tvrdit „nejčastěji", je změřené: na 266 dokumentech nese každé rozšíření dokumentu
     * slovník {@code /DSS}. Popisné údaje souboru v té kategorii jsou — {@code /Metadata} skoro
     * u všech —, ale nikdy nepřicházejí samy: nástroj, který podpis povýší na LT, při tom
     * přeuloží i XMP.
     */
    @Test
    @DisplayName("O úrovni LT se mluví jen u rozšíření dokumentu")
    void theLtSentenceOnlyFollowsValidationData() {
        String withoutIt = render(new RevisionTail(RevisionTail.Element.PODPIS, 8_000, List.of(),
                false, true, true, false));
        assertFalse(withoutIt.contains("LT"),
                "Zásah do polí ani změna anotací s úrovní podpisu nesouvisí: " + withoutIt);

        String withIt = render(new RevisionTail(RevisionTail.Element.PODPIS, 8_000, List.of(),
                true, false, false, false));
        assertTrue(withIt.contains("Nejčastěji jde o důsledek dodatečného povýšení"), withIt);
    }

    @Test
    @DisplayName("Změněný obsah: jiný nadpis, jmenované strany, a podpis se neprohlašuje za vadný")
    void changedContentIsCalledOut() {
        RevisionTail tail = new RevisionTail(RevisionTail.Element.PODPIS, 40_000, List.of(3, 7),
                false, true, false, false);
        String text = render(tail);

        assertTrue(text.contains("Po podepsání se v dokumentu změnil obsah"), text);
        assertTrue(text.contains("na stranách 3 a 7"), text);
        // Dvě věci, které se nesmí slít: zdejší verdikt se nemění, a přesto dnešní podoba není
        // ta podepsaná. Čtenář, kterému se řekne jen to druhé, hledá na kartě neplatnost, která
        // tam není.
        assertTrue(text.contains("verdikt uvedený výše se tím nemění"), text);
        // A nesmí se slíbit platnost napříč nástroji: Acrobat nad týmž vzorkem hlásí neplatný
        // podpis, protože živá politika DSS má PdfVisualDifference jen jako varování.
        assertFalse(text.contains("zůstává platný"),
                "Platnost napříč nástroji aplikace neví a slibovat ji nesmí: " + text);
        assertTrue(text.contains("neplatností posledního podpisu nebo neznámým stavem jeho platnosti"),
                "Že přísnější posouzení existuje, se musí říct: " + text);
        // Neslibovat porovnání, které se nekoná.
        assertTrue(text.contains("Co přesně se změnilo, aplikace neříká"), text);
    }

    /**
     * Kategorie změn se mají pojmenovat tím, čím jsou.
     *
     * <p>Obě znění se opravovala poté, co je uživatel uviděl na kartě: „změny, které se nepodařilo
     * zařadit" dělalo z nálezu omluvu aplikace (DSS se na tu kategorii ptá „obsahuje dokument
     * žádné nedefinované změny objektů?"), a věta o certifikátech tvrdila víc, než co v kategorii
     * „rozšíření dokumentu" doopravdy je — patří tam i změny popisných údajů souboru.
     */
    @Test
    @DisplayName("Nedefinované změny jsou nález, ne slabina aplikace")
    void undefinedChangesAreAFindingNotAnExcuse() {
        String text = render(new RevisionTail(RevisionTail.Element.PODPIS, 24_000, List.of(1),
                true, true, false, true));

        assertTrue(text.contains("mimo to, co se po podpisu považuje za přípustné"), text);
        assertFalse(text.contains("nepodařilo zařadit"),
                "To znění obracelo nález v omluvu: " + text);
        assertTrue(text.contains("případně popisných údajů souboru"),
                "Do rozšíření dokumentu patří i změny popisných údajů: " + text);
    }

    @Test
    @DisplayName("Poslední kryjící prvek se jmenuje podle toho, čím je")
    void theElementIsNamedForWhatItIs() {
        String bySignature = render(new RevisionTail(RevisionTail.Element.PODPIS, 5_000,
                List.of(), true, false, false, false));
        String byTimestamp = render(new RevisionTail(RevisionTail.Element.RAZITKO, 5_000,
                List.of(), true, false, false, false));

        assertTrue(bySignature.contains("poslední podpis"), bySignature);
        assertTrue(byTimestamp.contains("poslední časové razítko"), byTimestamp);
        assertFalse(byTimestamp.contains("poslední podpis"),
                "Nad uchovaným dokumentem kryje nejdál razítko, ne podpis: " + byTimestamp);
    }

    @Test
    @DisplayName("Jen změněný obsah otevře blok sám")
    void onlyAChangedContentOpensItself() {
        assertTrue(expanded(new RevisionTail(RevisionTail.Element.PODPIS, 1_000, List.of(2),
                false, false, false, false)));
        assertFalse(expanded(new RevisionTail(RevisionTail.Element.PODPIS, 1_000, List.of(),
                true, false, false, false)),
                "Obvyklý případ nemá nad kartami zabírat místo");
    }

    /**
     * Očekávání uživatele z 18. 9. 2026, vyslovené nad hotovým blokem: „předpokládám, že tam bude
     * možnost prokliknout se na zobrazení těch stránek". Odkaz míří na podobu krytou podpisem —
     * dnešní ukáže čtenáři kterákoli prohlížečka, kdežto to, co bylo podepsáno, žádná.
     */
    @Test
    @DisplayName("Čísla stran jsou odkazy a vedou na tu stranu")
    void pageNumbersAreLinks() {
        java.util.List<Integer> opened = new java.util.ArrayList<>();
        RevisionTail tail = new RevisionTail(RevisionTail.Element.PODPIS, 40_000, List.of(3, 7),
                false, true, false, false);

        String text = onFx(() -> {
            VBox block = RevisionTailBlock.build(result(tail), opened::add);
            java.util.List<javafx.scene.control.Hyperlink> found = links(block);
            assertEquals(2, found.size(), "Jeden odkaz na stranu");
            assertEquals("3", found.get(0).getText());
            assertEquals("7", found.get(1).getText());
            found.get(1).fire();
            return text(block);
        });

        assertEquals(List.of(7), opened, "Klik má otevřít právě tu stranu");
        // Věta zůstává celá i s odkazy uprostřed.
        assertTrue(text.contains("A je to podstatné: na stranách 3 a 7 je dnes vidět něco jiného"),
                text);
        assertTrue(text.contains("neplatností posledního podpisu nebo neznámým stavem jeho platnosti"), text);
    }

    /**
     * Pokyn uživatele z 9. 10. 2026: „nabídni také proklik na rychlý náhled výsledného stavu“.
     * Čísla stran vedou na podobu krytou podpisem, tenhle odkaz na dnešní — na první změněnou
     * stranu.
     */
    @Test
    @DisplayName("Odkaz na dnešní podobu otevře náhled na první změněné straně")
    void currentStateLink() {
        java.util.List<Integer> covered = new java.util.ArrayList<>();
        java.util.List<Integer> current = new java.util.ArrayList<>();
        RevisionTail tail = new RevisionTail(RevisionTail.Element.PODPIS, 40_000, List.of(3, 7),
                false, true, false, false);

        onFx(() -> {
            VBox block = RevisionTailBlock.build(result(tail), covered::add, current::add);
            java.util.List<javafx.scene.control.Hyperlink> found = links(block);
            javafx.scene.control.Hyperlink now = found.stream()
                    .filter(h -> h.getText().contains("dnešní podobě")).findFirst().orElseThrow();
            now.fire();
            return null;
        });

        assertEquals(List.of(3), current, "Dnešní podoba, první změněná strana");
        assertTrue(covered.isEmpty(), "Krytá podoba se tím otevírat nemá");
    }

    /**
     * Řádek v Zjednodušeném přehledu (rozhodnutí uživatele 9. 10. 2026) — jen u změněného obsahu,
     * se stranami a s odkazem na Rozšířený přehled, kde stojí celý blok.
     */
    @Test
    @DisplayName("Zjednodušený přehled upozorní jen na změněný obsah")
    void warningLineForSimpleOverview() {
        assertEquals("Po podepsání se v dokumentu změnil obsah (strana 1) — podrobnosti"
                + " v Rozšířeném přehledu.", RevisionTailBlock.warningLine(result(
                new RevisionTail(RevisionTail.Element.PODPIS, 2_000, List.of(1),
                        false, true, false, true))));
        assertTrue(RevisionTailBlock.warningLine(result(
                new RevisionTail(RevisionTail.Element.PODPIS, 2_000, List.of(3, 7),
                        false, true, false, false))).contains("(strany 3 a 7)"));
        assertNull(RevisionTailBlock.warningLine(result(
                new RevisionTail(RevisionTail.Element.PODPIS, 5_000, List.of(),
                        true, false, false, false))),
                "Pouhá validační data nejsou varování");
        assertNull(RevisionTailBlock.warningLine(result(null)));
    }

    @Test
    @DisplayName("U nezměněného obsahu odkaz na dnešní podobu není")
    void noCurrentLinkWithoutChange() {
        RevisionTail tail = new RevisionTail(RevisionTail.Element.PODPIS, 5_000, List.of(),
                true, false, false, false);
        boolean any = onFx(() -> links(RevisionTailBlock.build(result(tail), p -> { }, p -> { }))
                .stream().anyMatch(h -> h.getText().contains("dnešní podobě")));
        assertFalse(any, "Dnešní podoba se od kryté neliší, není co srovnávat");
    }

    @Test
    @DisplayName("Bez náhledu se čísla stran vysází jako text, ne jako mrtvé odkazy")
    void withoutAPreviewThePagesArePlainText() {
        RevisionTail tail = new RevisionTail(RevisionTail.Element.PODPIS, 40_000, List.of(3),
                false, true, false, false);

        String text = onFx(() -> {
            VBox block = RevisionTailBlock.build(result(tail), null);
            assertTrue(links(block).isEmpty(), "Odkaz, který nikam nevede, je horší než text");
            return text(block);
        });
        assertTrue(text.contains("na straně 3 je dnes vidět něco jiného"), text);
    }

    private static java.util.List<javafx.scene.control.Hyperlink> links(Node root) {
        java.util.List<javafx.scene.control.Hyperlink> out = new java.util.ArrayList<>();
        java.util.ArrayDeque<Node> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            if (n instanceof javafx.scene.control.Hyperlink h) out.add(h);
            if (n instanceof Parent p) queue.addAll(p.getChildrenUnmodifiable());
        }
        return out;
    }

    // ------------------------------------------------------------------ přípravek

    private static String render(RevisionTail tail) {
        return onFx(() -> text(RevisionTailBlock.build(result(tail))));
    }

    /** Je blok po sestavení rozbalený? Pozná se podle toho, že jeho tělo je vidět. */
    private static boolean expanded(RevisionTail tail) {
        return onFx(() -> {
            VBox block = RevisionTailBlock.build(result(tail));
            // Prvním potomkem je záhlaví, druhým tělo; rozbalený blok tělo ukazuje.
            return block.getChildren().size() > 1 && block.getChildren().get(1).isVisible();
        });
    }

    private static ValidationResult result(RevisionTail tail) {
        ValidationResult r = new ValidationResult("x.pdf", "PDF", false, List.of(), null, null, null);
        r.setRevisionTail(tail);
        return r;
    }

    private static String text(Node node) {
        StringBuilder sb = new StringBuilder();
        collect(node, sb);
        return sb.toString();
    }

    private static void collect(Node n, StringBuilder sb) {
        // TextFlow napřed: odstavec s odkazy je poskládaný z Textů a Hyperlinků a sbírat je po
        // jednom s koncem řádku mezi nimi by z jedné věty udělalo deset řádků — zkoušky by pak
        // hledaly text, který takhle nikde nestojí.
        if (n instanceof javafx.scene.text.TextFlow flow) {
            for (Node c : flow.getChildrenUnmodifiable()) {
                if (c instanceof javafx.scene.text.Text t) sb.append(t.getText());
                else if (c instanceof Labeled l && l.getText() != null) sb.append(l.getText());
            }
            sb.append('\n');
            return;
        }
        if (n instanceof Labeled l && l.getText() != null) sb.append(l.getText()).append('\n');
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) collect(c, sb);
        }
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
