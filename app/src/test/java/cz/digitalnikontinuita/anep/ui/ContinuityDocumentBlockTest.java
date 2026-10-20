package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.ui.ContinuityElementBlocks.Element;
import cz.digitalnikontinuita.anep.ui.ContinuityElementBlocks.Gates;
import cz.digitalnikontinuita.anep.ui.ContinuityElementBlocks.Source;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Run;

import javafx.scene.Node;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Souběh prvků: oba bloky o úkonu stojí jednou za dokument, ne u každého prvku.
 *
 * <p>Vzniklo 9. 9. 2026 s tím přesunem (pokyn uživatele). Hlídá se to, co se přesunem dá tiše
 * ztratit: že bloky u prvku opravdu <b>zmizely</b>, že to, čím se prvky liší, dokumentový blok
 * <b>říká</b>, a že lhůta v nadpisu je ta nejbližší — pozdější by slibovala víc, než co po ní
 * platí.
 *
 * <p>Testuje se přes {@code documentBlocks}, ne přes celou záložku: brány a dosah počítá
 * {@code AdvisorView} z živého ověření a sem přicházejí hotové. Co který prvek umí, tedy zadává
 * přípravek.
 */
class ContinuityDocumentBlockTest {

    private static final Instant FUTURE = SigFixture.FUTURE;
    private static final Instant LATER = SigFixture.LATER;
    private static final Run STRICT = new Run(true, List.of(), null);
    private static final Gates PRESERVE = new Gates(true, false, false, false);
    private static final Gates NOTHING = new Gates(false, false, false, false);
    /** Zdroj razítek, o kterém se nic zvláštního neví — tedy takový, jakých je většina. */
    private static final Source TSA = Source.named("I.CA", null);
    /** Zdroj, o kterém se <b>změřilo</b>, že kvalifikovaná razítka nevydává. */
    private static final Source DEMO = new Source("PostSignum DEMO", java.util.Date.from(LATER),
            cz.digitalnikontinuita.anep.core.TsaProfile.Quality.UNQUALIFIED);
    /** Týž zdroj co {@link #TSA}, jen s naměřeným datem — aby měl modrý blok co do nadpisu. */
    private static final Source TSA_DATED = new Source("I.CA", java.util.Date.from(LATER),
            cz.digitalnikontinuita.anep.core.TsaProfile.Quality.QUALIFIED);

    private static boolean toolkitUp;

    @BeforeAll
    static void startToolkit() {
        toolkitUp = FxToolkit.available();
    }

    @Test
    @DisplayName("U prvku při souběhu nezůstanou bloky o úkonu")
    void elementKeepsOnlyTheWhoAndUntilBlocks() {
        String text = onFx(() -> text(ContinuityElementBlocks.build(
                element(FUTURE, true, null), PRESERVE, TSA, null, false, null)));

        assertTrue(text.contains("Jak dlouho dokáže AnEP ověřit platnost?"),
                "Blok o zdejším dosahu k prvku patří dál: " + text);
        assertFalse(text.contains("AnEP nabízí možnost"),
                "Nabídka je dokumentová a u prvku stát nemá: " + text);
        assertFalse(text.contains("Jak dlouho je možné zachovat ověřitelnost?"),
                "Dosah úkonu je dokumentový a u prvku stát nemá: " + text);
    }

    @Test
    @DisplayName("Dokumentový blok nese popis souběhu a výčet po prvcích")
    void documentBlockCarriesTheCombinationAndTheElements() {
        String text = documentText(List.of(
                element(FUTURE, true, null),
                element(LATER, false, "ověření neskončilo platným výsledkem")));

        assertTrue(text.contains("POPIS SOUBĚHU"),
                "Popis souběhu je od 9. 9. 2026 uvnitř bloku, ne vedle něj: " + text);
        assertTrue(text.contains("Prvek č. 1") && text.contains("Prvek č. 2"),
                "Výčet po prvcích chybí: " + text);
        assertTrue(text.contains("nedosáhne: ověření neskončilo platným výsledkem"),
                "Důvod, proč se na prvek nedosáhne, má stát u něj: " + text);
    }

    @Test
    @DisplayName("Lhůta v nadpisu je nejbližší z lhůt dosažených prvků")
    void theHeadlineDeadlineIsTheNearestOne() {
        String text = documentText(List.of(
                element(FUTURE, true, null),
                element(LATER, true, null)));

        assertTrue(text.contains("Do kdy je třeba ji využít?  Do " + full(FUTURE)),
                "Nadpis má nést nejbližší lhůtu: " + text);
        assertFalse(text.contains("Do kdy je třeba ji využít?  Do " + full(LATER)),
                "Pozdější lhůta v nadpisu slibuje víc, než po ní platí: " + text);
        assertTrue(text.contains("nejbližší z lhůt jednotlivých prvků"),
                "Že se lhůty prvků liší, se nesmí zamlčet: " + text);
        assertTrue(text.contains("U tohoto prvku je možnost využitelná do " + full(LATER)),
                "Vlastní lhůta prvku, který ji má jinou, chybí: " + text);
    }

    /**
     * Nález uživatele 25. 9. 2026 nad dokumentem se čtyřmi razítky: lhůta prvku č. 2 uplynula
     * v roce 2019, prvek č. 4 vydrží do roku 2030 — a nadpis za dokument hlásil, že lhůta
     * uplynula. Uplynulá lhůta jednoho prvku neomezuje, co úkon dá ostatním.
     */
    @Test
    @DisplayName("Uplynulá lhůta jednoho prvku nepřebije lhůtu ostatních")
    void anElapsedDeadlineDoesNotWin() {
        String text = documentText(List.of(
                element(SigFixture.PAST, true, null),
                element(LATER, true, null)));

        assertTrue(text.contains("Do kdy je třeba ji využít?  Do " + full(LATER)),
                "Nadpis má nést lhůtu, která ještě běží: " + text);
        assertFalse(text.contains("Lhůta k jejímu využití uplynula"),
                "Úkon ještě pomůže prvku, jehož lhůta běží: " + text);
    }

    @Test
    @DisplayName("Uplynuly-li lhůty všech prvků, nadpis to řekne")
    void allElapsedSaysSo() {
        String text = documentText(List.of(
                element(SigFixture.PAST, true, null),
                element(SigFixture.PAST, true, null)));

        assertTrue(text.contains("Lhůta k jejímu využití uplynula " + full(SigFixture.PAST)),
                "Kde uplynuly všechny, má to nadpis říct: " + text);
    }

    @Test
    @DisplayName("Kde jsou lhůty stejné, o rozdílu se nemluví")
    void equalDeadlinesSayNothingAboutDifferences() {
        String text = documentText(List.of(
                element(FUTURE, true, null),
                element(FUTURE, true, null)));

        assertFalse(text.contains("nejbližší z lhůt jednotlivých prvků"),
                "Věta o rozdílu nad dvěma shodnými daty je nesmysl: " + text);
        assertFalse(text.contains("U tohoto prvku je možnost využitelná do"),
                "Zopakovat u prvku totéž datum jako v nadpisu jen zaplní blok: " + text);
    }

    /**
     * Prvek, jehož závěr stojí na revokačním údaji ze sítě, na který už není spoleh: lhůta za
     * dokument tím padá celá, i když ostatní prvky svá data mají.
     *
     * <p>Nález uživatele z 9. 9. 2026 nad „2 podpisy signed.pdf“. Datum v nadpisu tam pocházelo
     * z certifikátu razítkovací autority a o dostupnosti té OCSP neříkalo nic.
     */
    @Test
    @DisplayName("Údaj ze sítě po expiraci: dokument nemá lhůtu, nýbrž „nejlépe ihned“")
    void anOnlineOnlyElementLeavesTheDocumentWithoutADeadline() {
        String text = documentText(List.of(onlineElement(), element(LATER, true, null)));

        assertTrue(text.contains("Kdy je třeba ji využít?  Nejlépe ihned"),
                "Nadpis má místo data nést odpověď „nejlépe ihned“: " + text);
        assertFalse(text.contains("Do kdy je třeba ji využít?"),
                "Ptát se „do kdy“ tam, kde odpovědí není datum, je rozpor: " + text);
        assertTrue(text.contains("Lhůtu k využití této možnosti stanovit nelze. U prvku č. 1"),
                "Který prvek lhůtu shodil, má stát v bloku: " + text);
        assertTrue(text.contains("uplynula " + full(SigFixture.PAST)),
                "Datum, po kterém už na dostupnost údaje není spoleh, patří k tomu prvku: "
                + text);
        assertTrue(text.contains("U tohoto prvku je možnost využitelná do " + full(LATER)),
                "Prvek, který svou lhůtu má, o ni přijít nesmí: " + text);
    }

    /** Prvek, jehož závěr drží revokační údaj stažený ze sítě po expiraci certifikátu. */
    private static Element onlineElement() {
        SignatureSummary sig = SigFixture.sig()
                .certNotAfter(SigFixture.PAST)
                .revocationFrom("online")
                .deadline(SigFixture.PAST)
                .build();
        return ContinuityElementBlocks.element(sig, "TOTAL_PASSED", STRICT, false, true, null);
    }

    /**
     * Prvek, jehož zdejší horizont končí platností podpisového certifikátu — a ta je zadaná.
     * Bez razítka pod sebou drží závěr právě jen ten certifikát, takže se dá lhůta nastavit
     * přímo a test nemusí skládat řadu razítek.
     */
    /**
     * Připomínka do kalendáře na konci posledního bloku (pokyn uživatele 5. 10. 2026): tlačítko
     * se stiskne doopravdy a uložený soubor nese předstih, nabídnutou variantu i plnou cestu
     * k dokumentu.
     */
    @Test
    @DisplayName("Blok s datem v nadpisu nabízí připomínku do kalendáře a tlačítko ji uloží")
    void reminderButtonWritesTheFile() throws Exception {
        java.io.File document = new java.io.File(System.getProperty("java.io.tmpdir"),
                "smlouva.pdf").getAbsoluteFile();
        java.io.File target = java.io.File.createTempFile("anep-pripominka-", ".ics");
        var previous = CalendarReminder.target;
        CalendarReminder.target = (owner, name) -> target;
        try {
            String outcome = onFx(() -> {
                VBox blocks = ContinuityElementBlocks.build(element(FUTURE, true, null),
                        PRESERVE, TSA, null, true, null, document);
                String all = text(blocks);
                if (!all.contains("Do kdy je třeba ji využít?  Do ")) {
                    return "nadpis bez data: " + all;
                }
                javafx.scene.control.ComboBox<?> lead = find(blocks,
                        javafx.scene.control.ComboBox.class);
                javafx.scene.control.Button button = find(blocks,
                        javafx.scene.control.Button.class);
                if (lead == null || button == null) return "ovládací prvky nenalezeny";
                @SuppressWarnings("unchecked")
                javafx.scene.control.ComboBox<Integer> l =
                        (javafx.scene.control.ComboBox<Integer>) lead;
                if (!l.getItems().equals(CalendarReminder.LEADS)) return "jiné předstihy";
                l.setValue(10);
                button.fire();
                return "stisknuto";
            });
            org.junit.jupiter.api.Assertions.assertEquals("stisknuto", outcome);
            String ics = java.nio.file.Files.readString(target.toPath()).replace("\r\n ", "");
            assertTrue(ics.contains("SUMMARY:Uchovat dokument: smlouva.pdf"), ics);
            assertTrue(ics.contains(CalendarReminder.escape(document.getPath())), ics);
        } finally {
            CalendarReminder.target = previous;
            java.nio.file.Files.deleteIfExists(target.toPath());
        }
    }

    @Test
    @DisplayName("Dokumentový blok nabízí připomínku jen se známým souborem")
    void documentBlockReminderNeedsTheFile() {
        List<Element> elements = List.of(element(FUTURE, true, null), element(LATER, true, null));
        java.io.File document = new java.io.File(System.getProperty("java.io.tmpdir"),
                "smlouva.pdf").getAbsoluteFile();
        boolean with = onFx(() -> find(ContinuityElementBlocks.documentBlocks(elements, PRESERVE,
                List.of(), TSA, null, null, document), javafx.scene.control.Button.class) != null);
        boolean without = onFx(() -> find(ContinuityElementBlocks.documentBlocks(elements,
                PRESERVE, List.of(), TSA, null, null), javafx.scene.control.Button.class) != null);
        assertTrue(with, "dokumentový blok bez připomínky");
        assertFalse(without, "připomínka bez souboru");
    }

    private static <T> T find(Node node, Class<T> type) {
        if (type.isInstance(node)) return type.cast(node);
        if (node instanceof javafx.scene.Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                T found = find(c, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Element element(Instant certNotAfter, boolean reaches, String obstacle) {
        SignatureSummary sig = SigFixture.sig().certNotAfter(certNotAfter).build();
        return ContinuityElementBlocks.element(sig, "TOTAL_PASSED", STRICT, false, reaches,
                obstacle);
    }

    private static String documentText(List<Element> elements) {
        return documentText(elements, PRESERVE, null);
    }

    private static String documentText(List<Element> elements, Gates gates, String pdfaProfile) {
        return documentText(elements, gates, TSA, pdfaProfile);
    }

    private static String documentText(List<Element> elements, Gates gates, Source tsa,
                                       String pdfaProfile) {
        return onFx(() -> text(ContinuityElementBlocks.documentBlocks(elements, gates,
                List.of(AdvisorView.continuityText("POPIS SOUBĚHU")), tsa,
                pdfaProfile, null)));
    }

    private static String full(Instant when) {
        return cz.digitalnikontinuita.anep.core.TimeFormat.full(when);
    }

    private static String text(VBox blocks) {
        StringBuilder sb = new StringBuilder();
        collect(blocks, sb);
        return sb.toString();
    }

    private static void collect(Node n, StringBuilder sb) {
        if (n instanceof javafx.scene.control.Labeled l && l.getText() != null) {
            sb.append(l.getText()).append('\n');
        }
        if (n instanceof javafx.scene.text.TextFlow tf) {
            for (Node c : tf.getChildrenUnmodifiable()) {
                if (c instanceof javafx.scene.text.Text t) sb.append(t.getText());
                if (c instanceof javafx.scene.control.Labeled l && l.getText() != null) {
                    sb.append(l.getText());
                }
            }
            sb.append('\n');
            return;
        }
        if (n instanceof javafx.scene.Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) collect(c, sb);
        }
    }

    private static <T> T onFx(Supplier<T> work) {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit nenaběhl");
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
            if (!done.await(30, TimeUnit.SECONDS)) fail("blok se nevykreslil do 30 s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
        if (failure.get() != null) fail(failure.get());
        return out.get();
    }
    /**
     * Věta o archivní podobě je slib o tom, co úkon s dokumentem udělá, takže smí zaznít jen
     * tam, kde je co zachovat: u konformního PDF/A, kterému se úkon nabízí. Jméno úkonu v ní
     * musí odpovídat tomu, co je na tlačítku — proto se zkouší i přerazítkování, kde se dřív
     * mluvilo paušálně o uchování.
     */
    @Test
    @DisplayName("U konformního PDF/A blok slíbí, že úkon archivní podobu zachová")
    void archivalFormIsPromisedWhereThereIsSomethingToKeep() {
        String text = documentText(List.of(element(FUTURE, true, null)), PRESERVE, "PDF/A-2b");
        assertTrue(text.contains("Archivní podobu dokumentu úkon zachová"), text);
        assertTrue(text.contains("PDF/A-2b"), text);
        assertTrue(text.contains("uchování do něj zapíše"), text);
        // Tvrzení stojí na tom, co úkon se souborem dělá. O zkušebním měření, kterým se to
        // ověřovalo, se tu nepíše — do hotového produktu nepatří (pokyn uživatele 17. 9. 2026);
        // čísla zůstávají v dokumentaci metody a v sondě PdfAAfterActionProbe.
        assertFalse(text.contains("měřen"), text);
        assertFalse(text.contains("průchodech"), text);
    }

    @Test
    @DisplayName("Slib jmenuje ten úkon, který se doopravdy nabízí")
    void thePromiseNamesTheActionOnOffer() {
        Gates restamp = new Gates(false, false, true, false);
        String text = documentText(List.of(element(FUTURE, true, null)), restamp, "PDF/A-3a");
        assertTrue(text.contains("přerazítkování do něj zapíše"), text);
        assertFalse(text.contains("uchování do něj zapíše"), text);
    }

    @Test
    @DisplayName("Dokumentu, který PDF/A není, se o archivní podobě nic neslibuje")
    void noPromiseWhereThereIsNoArchivalForm() {
        String text = documentText(List.of(element(FUTURE, true, null)), PRESERVE, null);
        assertFalse(text.contains("Archivní podobu"), text);
    }
    /**
     * Dokument s <b>jediným</b> autentizačním prvkem nemá dokumentovou část — ta se staví až od
     * dvou prvků (viz {@code AdvisorView.addCombinationBlock}). Nabídku tam popisuje blok
     * u prvku, a věta o archivní podobě tedy musí být v něm.
     *
     * <p>Vzniklo z nálezu uživatele 17. 9. 2026: nad souborem PDF/A-3b s jedním podpisem nebyla
     * v záložce o zachování podtypu ani zmínka. Věta byla jen v dokumentové části, kam se
     * u takového souboru vůbec nedojde.
     */
    @Test
    @DisplayName("U dokumentu s jediným prvkem stojí věta o archivní podobě u prvku")
    void singleElementDocumentStillGetsThePromise() {
        String text = onFx(() -> text(ContinuityElementBlocks.build(
                element(FUTURE, true, null), PRESERVE, TSA, null, true, "PDF/A-3b")));

        assertTrue(text.contains("Archivní podobu dokumentu úkon zachová"), text);
        assertTrue(text.contains("PDF/A-3b"), text);
    }

    /**
     * Bez vybraného zdroje časových razítek se nenabízí nic — a blok to musí říct i s důvodem.
     *
     * <p>Nález uživatele z 18. 9. 2026: záložka správně hlásila, že se v tuto chvíli nedá dělat
     * nic, ale zamlčela, že je to kvůli nastavení. Na zdroj se ptají všechny čtyři brány
     * ({@code AdvisorView}), takže tenhle stav nastane nad jakýmkoli dokumentem, i nad
     * bezvadným — a je to jediný důvod z celé té větve, který čtenář odstraní sám.
     */
    @Test
    @DisplayName("Bez zdroje časových razítek řekne blok, že chybí právě on")
    void withoutATimestampSourceTheBlockSaysSo() {
        String text = documentText(
                List.of(element(FUTURE, false, null), element(LATER, false, null)),
                NOTHING, Source.NONE, null);

        assertTrue(text.contains("nedá udělat nic z toho, co aplikace nabízí"), text);
        assertTrue(text.contains("není v něm vybrán žádný zdroj časových razítek"),
                "Důvod, proč se nenabízí nic, chybí: " + text);
        assertTrue(text.contains("Zdroj se vybírá v nastavení aplikace"),
                "Čtenář se nedozví, kde to napravit: " + text);
    }

    @Test
    @DisplayName("U dokumentu s jediným prvkem stojí týž důvod u prvku")
    void singleElementDocumentAlsoLearnsWhy() {
        String text = onFx(() -> text(ContinuityElementBlocks.build(
                element(FUTURE, false, null), NOTHING, Source.NONE, null, true, null)));

        assertTrue(text.contains("není v něm vybrán žádný zdroj časových razítek"),
                "Blok u prvku je jediný, který takový dokument má: " + text);
    }

    @Test
    @DisplayName("Kde má prvek vlastní překážku, není chybějící zdroj jediný důvod")
    void anElementWithItsOwnObstacleGetsBothReasons() {
        String text = onFx(() -> text(ContinuityElementBlocks.build(
                element(FUTURE, false, "ověření neskončilo platným výsledkem"),
                NOTHING, Source.NONE, null, true, null)));

        assertTrue(text.contains("Důvod: ověření neskončilo platným výsledkem"), text);
        assertTrue(text.contains("Nezávisle na tom není v nastavení aplikace vybrán žádný zdroj"),
                "Chybějící zdroj se musí říct i vedle vlastní překážky prvku: " + text);
        assertFalse(text.contains("Důvod je v nastavení aplikace"),
                "Tenhle prvek by nabídku nedostal ani se zdrojem: " + text);
    }

    @Test
    @DisplayName("S vybraným zdrojem se o jeho chybění nemluví")
    void withASourceSelectedNothingIsSaidAboutIt() {
        String text = documentText(
                List.of(element(FUTURE, false, null), element(LATER, false, null)),
                NOTHING, TSA, null);

        assertFalse(text.contains("zdroj časových razítek"),
                "Zdroj vybrán je, takže o něm nemá blok co říct: " + text);
    }

    /**
     * Testovací zdroj razítek: nabídka zůstává, slib padá.
     *
     * <p>Přibylo 18. 9. 2026 s možností přihlásit se k autoritě klientským certifikátem a
     * s předvolbou PostSignum DEMO. Kvalifikovanost se od té chvíle <b>měří</b> z vydaného
     * razítka, a tenhle zdroj měřením neprojde. Blok o něm proto nesmí mlčet: všechno ostatní,
     * co v záložce stojí, mluví o razítku, které za důkaz existence v čase někdo vezme.
     */
    @Test
    @DisplayName("Nekvalifikovaný zdroj: nabídka zůstane, ale blok na něj upozorní")
    void anUnqualifiedSourceIsCalledOut() {
        String text = documentText(
                List.of(element(FUTURE, true, null), element(LATER, true, null)),
                PRESERVE, DEMO, null);

        assertTrue(text.contains("nabízí aplikace AnEP možnost plnohodnotného uchování"),
                "Nabídka se nebere — testovací zdroj je legitimní volba: " + text);
        assertTrue(text.contains("vydává razítka, která nejsou kvalifikovaná"),
                "Výhrada k vybranému zdroji chybí: " + text);
        assertTrue(text.contains("PostSignum DEMO"),
                "Který zdroj to je, se musí jmenovat: " + text);
    }

    @Test
    @DisplayName("O zdroji, u kterého se nic nezměřilo, se výhrada nepíše")
    void anUnmeasuredSourceGetsNoWarning() {
        String text = documentText(
                List.of(element(FUTURE, true, null), element(LATER, true, null)),
                PRESERVE, TSA, null);

        assertFalse(text.contains("nejsou kvalifikovaná"),
                "Nezjištěno není totéž co nekvalifikováno: " + text);
    }

    /**
     * Modrý blok „Jak dlouho je možné zachovat ověřitelnost?“ je celý o slibu, že se ověřitelnost
     * protáhne do konce platnosti certifikátu autority. Nad nekvalifikovaným zdrojem ten slib
     * neplatí, takže datum z nadpisu musí zmizet — stejně jako u zdroje, který nedosáhne dál než
     * dokument sám. Nadpis je při sbalených blocích to jediné, co je vidět.
     */
    @Test
    @DisplayName("Nekvalifikovaný zdroj nedostane datum do nadpisu modrého bloku")
    void anUnqualifiedSourcePromisesNoDate() {
        String withDemo = documentText(List.of(element(FUTURE, true, null)), PRESERVE, DEMO, null);
        String withReal = documentText(List.of(element(FUTURE, true, null)),
                PRESERVE, TSA_DATED, null);

        assertTrue(withReal.contains("Jak dlouho je možné zachovat ověřitelnost?  Do "),
                "Kvalifikovaný zdroj datum v nadpisu má a mít musí: " + withReal);
        assertFalse(withDemo.contains("Jak dlouho je možné zachovat ověřitelnost?  Do "),
                "Datum v nadpisu slibuje prodloužení, ke kterému nedojde: " + withDemo);
        assertFalse(withDemo.contains("doba, do které bude prodloužena možnost ověření"),
                "Slib o prodloužení nad nekvalifikovaným razítkem neplatí: " + withDemo);
    }

    @Test
    @DisplayName("Při souběhu se věta u prvků neopakuje")
    void withSeveralElementsThePromiseStaysInTheDocumentPart() {
        // Při souběhu se blok o úkonu k prvku vůbec nestaví (actionBlocks = false), takže se
        // věta nemá kde zdvojit. Kdyby se to pravidlo změnilo, spadne tahle zkouška dřív než
        // čtenáři padne do oka totéž tvrzení pětkrát pod sebou.
        String text = onFx(() -> text(ContinuityElementBlocks.build(
                element(FUTURE, true, null), PRESERVE, TSA, null, false, "PDF/A-3b")));

        assertFalse(text.contains("Archivní podobu"), text);
    }
}
