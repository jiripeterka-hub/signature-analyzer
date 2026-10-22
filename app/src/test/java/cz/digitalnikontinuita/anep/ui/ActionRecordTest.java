package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.TsaProfile;
import cz.digitalnikontinuita.anep.ui.ActionRecord.ElementKind;
import cz.digitalnikontinuita.anep.ui.ActionRecord.ElementState;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Horizon;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Kind;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Limit;

import javafx.application.Platform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Záznam o úkonu: párování prvků před úkonem a po něm a srovnání výsledku s předpovědí.
 *
 * <p>Srovnání je to jediné, co záznam sám vyvozuje — všechno ostatní jen opisuje z výpočtů,
 * které má záložka Digitální kontinuita. Proto se tu zkouší na stavech sestavených ručně, bez
 * dokumentu: o tom, co vrátí ověření, tyhle zkoušky nic neříkají a říkat nemají.
 */
class ActionRecordTest {

    private static final Instant Y2029 = Instant.parse("2029-03-06T10:00:00Z");
    private static final Instant Y2034 = Instant.parse("2034-08-03T10:00:00Z");
    private static final Instant Y2027 = Instant.parse("2027-05-18T10:00:00Z");

    private static Horizon until(Instant t) { return new Horizon(Kind.UNTIL, t, Limit.TIMESTAMP_CERT); }
    private static final Horizon LOST = new Horizon(Kind.LOST, null, Limit.NONE);

    private static ElementState sig(String id, String verdict, Horizon anep, Horizon tolerant,
                                    boolean reached) {
        return new ElementState(id, ElementKind.SIGNATURE, "Jan Zkušební", verdict, anep, tolerant,
                reached, reached ? null : "certifikát byl odvolán", List.of(), null, null);
    }

    private static ElementState stamp(String id, Instant certEnd) {
        return new ElementState(id, ElementKind.TIMESTAMP, "TSA", "TOTAL_PASSED", until(certEnd),
                until(certEnd), false, null, List.of(), certEnd, null);
    }

    private static ActionRecord record(AdvisorView.ContinuityMode mode, List<ElementState> before,
                                       List<ElementState> after, Instant afterDoc, Instant promised) {
        ActionRecord r = new ActionRecord(mode, "Zkušební TSA", TsaProfile.Quality.QUALIFIED,
                before, Y2027);
        r.after = after;
        r.afterDocUntil = afterDoc;
        r.promisedUntil = promised != null ? Date.from(promised) : null;
        return r;
    }

    @Test
    @DisplayName("Uchování dopadlo podle předpovědi: žádný rozchod, nové razítko přibude jako prvek")
    void preservationAsPredicted() {
        ActionRecord r = record(AdvisorView.ContinuityMode.PRESERVE,
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2027), until(Y2027), true)),
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2029), until(Y2029), false),
                        stamp("T-new", Y2029)),
                Y2029, Y2029);

        assertEquals(List.of(), r.discrepancies());
        List<ActionRecord.Pair> pairs = r.pairs();
        assertEquals(2, pairs.size());
        assertEquals("S-1", pairs.get(0).before().id());
        assertNull(pairs.get(1).before(), "nové razítko nemá stav před úkonem");
        assertEquals(2, pairs.get(1).number());
    }

    @Test
    @DisplayName("Prvek, na který úkon měl dosáhnout, vyšel s neznámou platností — hlásí se")
    void reachedElementNotPassing() {
        ActionRecord r = record(AdvisorView.ContinuityMode.PRESERVE,
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2027), until(Y2027), true)),
                List.of(sig("S-1", "INDETERMINATE", LOST, LOST, false)),
                null, Y2029);

        List<String> d = r.discrepancies();
        assertEquals(1, d.size(), d.toString());
        assertTrue(d.get(0).contains("s neznámou platností"), d.get(0));
    }

    @Test
    @DisplayName("Prvek mimo dosah úkonu se nesmí zhoršit; zůstane-li, jak byl, je to v pořádku")
    void unreachedElementMustNotWorsen() {
        ElementState revoked = sig("S-2", "INDETERMINATE", LOST, LOST, false);
        ActionRecord same = record(AdvisorView.ContinuityMode.PRESERVE,
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2027), until(Y2027), true), revoked),
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2029), until(Y2029), false), revoked),
                Y2029, Y2029);
        assertEquals(List.of(), same.discrepancies());

        ActionRecord worse = record(AdvisorView.ContinuityMode.PRESERVE,
                List.of(revoked),
                List.of(sig("S-2", "TOTAL_FAILED", new Horizon(Kind.INVALID, null, Limit.NONE),
                        LOST, false)),
                null, null);
        List<String> d = worse.discrepancies();
        assertEquals(1, d.size(), d.toString());
        assertTrue(d.get(0).contains("zhoršil"), d.get(0));
    }

    @Test
    @DisplayName("Datum ve jménu souboru se srovnává po dnech: hodiny rozdílu nejsou rozchod")
    void promisedDateComparedByDay() {
        List<ElementState> before = List.of(sig("S-1", "TOTAL_PASSED", until(Y2027), until(Y2027), true));
        List<ElementState> after = List.of(sig("S-1", "TOTAL_PASSED", until(Y2029), until(Y2029), false));

        ActionRecord sameDay = record(AdvisorView.ContinuityMode.PRESERVE, before, after,
                Y2029.minus(3, ChronoUnit.HOURS), Y2029);
        assertEquals(List.of(), sameDay.discrepancies());

        ActionRecord shorter = record(AdvisorView.ContinuityMode.PRESERVE, before, after,
                Y2029, Y2034);
        List<String> d = shorter.discrepancies();
        assertEquals(1, d.size(), d.toString());
        assertTrue(d.get(0).contains("3. 8. 2034") && d.get(0).contains("6. 3. 2029"), d.get(0));
    }

    @Test
    @DisplayName("Přerazítkování se měří odhadem pro CzechPOINT, ne přísným verdiktem")
    void restampJudgedByTolerantReading() {
        // Přísné ověření výsledného souboru bez ústupků dá neznámou platnost — a to je u
        // přerazítkování očekávané; rozhoduje okruh nástrojů, pro který úkon vznikl.
        ActionRecord ok = record(AdvisorView.ContinuityMode.RESTAMP,
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2027), until(Y2027), true)),
                List.of(sig("S-1", "INDETERMINATE", LOST, until(Y2029), false)),
                null, Y2029);
        assertEquals(List.of(), ok.discrepancies());

        ActionRecord lost = record(AdvisorView.ContinuityMode.RESTAMP,
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2027), until(Y2027), true)),
                List.of(sig("S-1", "INDETERMINATE", LOST, LOST, false)),
                null, Y2029);
        List<String> d = lost.discrepancies();
        assertEquals(1, d.size(), d.toString());
        assertTrue(d.get(0).contains("CzechPOINT"), d.get(0));
    }

    @Test
    @DisplayName("Prvek, který po úkonu chybí, se nezamlčí")
    void missingElementReported() {
        ActionRecord r = record(AdvisorView.ContinuityMode.PRESERVE,
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2027), until(Y2027), true)),
                List.of(sig("S-other", "TOTAL_PASSED", until(Y2029), until(Y2029), false)),
                Y2029, Y2029);
        List<String> d = r.discrepancies();
        assertEquals(1, d.size(), d.toString());
        assertTrue(d.get(0).contains("nepodařilo najít"), d.get(0));
        assertEquals(2, r.pairs().size(), "nový i chybějící prvek ve výčtu");
    }

    @Test
    @DisplayName("Razítko se změněnými bajty (a tím ID) se spáruje podle času vzniku a vydavatele")
    void timestampWithNewIdPairedByTime() {
        // Uchování kontejneru ASiC vloží ověřovací údaje do razítka samotného a DSS mu dá jiný
        // identifikátor; čas vzniku a vydavatel zůstanou.
        Instant made = Instant.parse("2019-10-17T07:55:13Z");
        ElementState old = new ElementState("T-old", ElementKind.TIMESTAMP, "TSA", "TOTAL_PASSED",
                until(Y2027), until(Y2027), true, null, List.of(), Y2027, made);
        ElementState reencoded = new ElementState("T-reencoded", ElementKind.TIMESTAMP, "TSA",
                "TOTAL_PASSED", until(Y2029), until(Y2029), false, null, List.of(), Y2027, made);
        ElementState fresh = new ElementState("T-new", ElementKind.TIMESTAMP, "TSA", "TOTAL_PASSED",
                until(Y2029), until(Y2029), false, null, List.of(), Y2029,
                Instant.parse("2026-10-04T10:00:00Z"));
        ActionRecord r = record(AdvisorView.ContinuityMode.PRESERVE, List.of(old),
                List.of(reencoded, fresh), Y2029, Y2029);

        List<ActionRecord.Pair> pairs = r.pairs();
        assertEquals(2, pairs.size(), pairs.toString());
        assertEquals("T-old", pairs.get(0).before().id());
        assertNull(pairs.get(1).before());
        assertEquals(List.of(), r.discrepancies());
    }

    @Test
    @DisplayName("Revokační údaje: co už v dokumentu bylo, co úkon přidal a co zůstalo mimo něj")
    void revocationsGrouped() {
        ActionRecord.RevocationFact kept = new ActionRecord.RevocationFact("k1", true, "CA",
                Y2027, List.of("Jan"), 500, true, "slovník DSS", false, List.of("C-ca"));
        ActionRecord.RevocationFact fetchedBefore = new ActionRecord.RevocationFact("k2", false,
                "OCSP", Y2027, List.of("Jan"), 2000, false, null, false, List.of("C-jan"));
        ActionRecord.RevocationFact embeddedNow = new ActionRecord.RevocationFact("k2", false,
                "OCSP", Y2027, List.of("Jan"), 2000, true, null, false, List.of("C-jan"));
        ActionRecord.RevocationFact fetchedAfter = new ActionRecord.RevocationFact("k3", false,
                "OCSP", Y2029, List.of("TSA"), 1500, false, null, true, List.of("C-tsa"));
        ActionRecord r = record(AdvisorView.ContinuityMode.PRESERVE, List.of(), List.of(), null, null);
        r.beforeRevocations = List.of(kept, fetchedBefore);
        r.afterRevocations = List.of(kept, embeddedNow, fetchedAfter);

        assertEquals(List.of(kept), r.revocationsKept());
        // Stažená před úkonem a vložená jím — to je přesně to, co uchování dělá.
        assertEquals(List.of(embeddedNow), r.revocationsAdded());
        assertEquals(List.of(fetchedAfter), r.revocationsFetchedAfter());

        // Čerstvá odpověď o certifikátu, ke kterému dokument už vložený údaj nese, se nevypíše.
        ActionRecord.RevocationFact freshForJan = new ActionRecord.RevocationFact("k4", false,
                "OCSP", Y2029, List.of("Jan"), 2000, false, null, false, List.of("C-jan"));
        r.afterRevocations = List.of(kept, embeddedNow, fetchedAfter, freshForJan);
        assertEquals(List.of(fetchedAfter), r.revocationsFetchedAfter());
        assertTrue(ActionRecord.revocationLine(kept).contains("uloženo: slovník DSS"));
        assertTrue(!ActionRecord.revocationLine(embeddedNow).contains("uloženo"));
    }

    @Test
    @DisplayName("Úvodní věta: úspěch a datum i čas, do kdy je dokument ověřitelný")
    void headlineSaysSuccessAndUntil() {
        ActionRecord ok = record(AdvisorView.ContinuityMode.PRESERVE,
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2027), until(Y2027), true)),
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2029), until(Y2029), false)),
                Y2029, Y2029);
        assertEquals("Uchování bylo úspěšné, možnost ověření byla prodloužena do "
                + cz.digitalnikontinuita.anep.core.TimeFormat.full(Y2029) + ".",
                ok.headline(ok.discrepancies()));

        ActionRecord bad = record(AdvisorView.ContinuityMode.PRESERVE,
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2027), until(Y2027), true)),
                List.of(sig("S-1", "INDETERMINATE", LOST, LOST, false)),
                null, null);
        assertEquals("Uchování proběhlo, výsledek se ale od předpovědi liší.",
                bad.headline(bad.discrepancies()));
    }

    @Test
    @DisplayName("Otisk SHA-256 souboru odpovídá známé hodnotě")
    void sha256OfFile() throws Exception {
        File f = File.createTempFile("anep-otisk-", ".bin");
        try {
            Files.write(f.toPath(), "abc".getBytes(StandardCharsets.US_ASCII));
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    ActionRecord.sha256(f));
        } finally {
            Files.deleteIfExists(f.toPath());
        }
    }

    @Test
    @DisplayName("Protokol záznamu nese otisky, oba soubory a barevný řádek výsledku")
    void protocolCarriesHashesAndVerdict() throws Exception {
        Assumptions.assumeTrue(FxToolkit.available(), "JavaFX toolkit není k dispozici");
        ActionRecord r = record(AdvisorView.ContinuityMode.PRESERVE,
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2027), until(Y2027), true)),
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2029), until(Y2029), false),
                        stamp("T-new", Y2029)),
                Y2029, Y2029);
        r.input = new ActionRecord.FileFact("smlouva.pdf", 1000, "aa".repeat(32));
        r.output = new ActionRecord.FileFact("smlouva_Uchovano_do_06_03_2029.pdf", 5000,
                "bb".repeat(32));
        r.performedAt = Instant.parse("2026-10-04T12:00:00Z");
        r.tsaCertNotAfter = Date.from(Y2029);

        AtomicReference<String> fo = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                fo.set(PrintUtil.buildFo(r.content(), null, null, r.title(), null, null));
            } catch (Throwable t) {
                failure.set(t);
            }
            done.countDown();
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "vlákno JavaFX neodpovědělo");
        if (failure.get() != null) throw new AssertionError(failure.get());

        String text = fo.get();
        assertTrue(text.contains("aa".repeat(32)), "otisk vstupu chybí");
        assertTrue(text.contains("bb".repeat(32)), "otisk výsledku chybí");
        assertTrue(text.contains("smlouva_Uchovano_do_06_03_2029.pdf"), "jméno výsledku chybí");
        assertTrue(text.contains("Záznam o úkonu: Uchování"), "titul chybí");
        assertTrue(!text.contains("Ověření výsledného souboru"), "vypuštěný blok se vrátil");
        assertTrue(!text.contains("Upozornění:"), "výhrada bez důvodu");
        assertTrue(text.contains("background-color=\"#1a7a1a\""),
                "řádek s výsledkem nemá barvu shody:\n" + text);
    }

    @Test
    @DisplayName("Uchování z úrovně B: protokol uvádí formát obou souborů a obě přidaná razítka")
    void baselineProtocolNamesFormatsAndTwoStamps() throws Exception {
        Assumptions.assumeTrue(FxToolkit.available(), "JavaFX toolkit není k dispozici");
        ActionRecord r = record(AdvisorView.ContinuityMode.PRESERVE,
                List.of(sig("S-1", "TOTAL_PASSED", LOST, LOST, true)),
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2029), until(Y2029), false),
                        stamp("T-1", Y2029), stamp("T-2", Y2029)),
                Y2029, Y2029);
        r.input = new ActionRecord.FileFact("smlouva.pdf", 1000, "aa".repeat(32));
        r.output = new ActionRecord.FileFact("smlouva_Uchovano.pdf", 5000, "bb".repeat(32));
        r.inputFormat = "PAdES-BASELINE-B";
        r.outputFormat = "PAdES-BASELINE-LTA";
        r.fromBaseline = true;

        String text = onFx(() -> PrintUtil.buildFo(r.content(), null, null, r.title(), null, null));
        assertTrue(text.contains("PAdES-BASELINE-B"), "formát vstupu chybí");
        assertTrue(text.contains("PAdES-BASELINE-LTA"), "formát výsledku chybí");
        assertTrue(text.contains("přidána dvě časová razítka"), "dvě razítka nejsou zmíněna");
        assertTrue(text.contains("Certifikát archivního razítka"), "řádek certifikátu se nezměnil");
    }

    @Test
    @DisplayName("Záznam uvádí algoritmy jen razítek přidaných úkonem, i se slabým klíčem")
    void addedStampAlgorithms() throws Exception {
        Assumptions.assumeTrue(FxToolkit.available(), "JavaFX toolkit není k dispozici");
        ActionRecord r = record(AdvisorView.ContinuityMode.PRESERVE,
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2027), until(Y2027), true)),
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2029), until(Y2029), false),
                        stamp("T-new", Y2029)),
                Y2029, Y2029);
        r.tsaCertNotAfter = Date.from(Y2029);
        ActionRecord.StampFact old = new ActionRecord.StampFact("aa", Instant.parse(
                "2020-01-01T10:00:00Z"), "Stará TSA", "ECDSA", 256, "SHA256", "SHA256", null);
        ActionRecord.StampFact added = new ActionRecord.StampFact("bb", Instant.parse(
                "2026-10-05T10:00:00Z"), "Nová TSA", "RSA", 2048, "SHA256", "SHA512",
                Instant.parse("2027-01-01T00:00:00Z"));
        r.beforeStamps = List.of(old);
        r.afterStamps = List.of(old, added);

        String text = onFx(() -> PrintUtil.buildFo(r.content(), null, null, r.title(), null, null));
        assertTrue(text.contains("Algoritmy nového razítka:"), text);
        assertTrue(text.contains("RSA, klíč 2 048 bitů, hašovací funkce podpisu razítka"
                + " SHA256, hašovací funkce otisku dat SHA512"), text);
        assertTrue(text.contains("knihovna DSS, nad kterou AnEP ověřuje, ho od 1. 1. 2027"
                + " nepovažuje za spolehlivý"), text);
        assertTrue(!text.contains("ECDSA"), "razítko, které dokument nesl už předtím: " + text);
    }

    @Test
    @DisplayName("„Úkon na prvek“ jen u dokumentu s více prvky")
    void reachRowOnlyWhereItSaysSomething() throws Exception {
        Assumptions.assumeTrue(FxToolkit.available(), "JavaFX toolkit není k dispozici");
        ActionRecord single = record(AdvisorView.ContinuityMode.PRESERVE,
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2027), until(Y2027), true)),
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2029), until(Y2029), false),
                        stamp("T-new", Y2029)),
                Y2029, Y2029);
        ActionRecord two = record(AdvisorView.ContinuityMode.PRESERVE,
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2027), until(Y2027), true),
                        sig("S-2", "TOTAL_PASSED", until(Y2027), until(Y2027), true)),
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2029), until(Y2029), false),
                        sig("S-2", "TOTAL_PASSED", until(Y2029), until(Y2029), false),
                        stamp("T-new", Y2029)),
                Y2029, Y2029);
        String singleText = onFx(() -> PrintUtil.buildFo(single.content(), null, null,
                single.title(), null, null));
        String twoText = onFx(() -> PrintUtil.buildFo(two.content(), null, null, two.title(),
                null, null));
        assertTrue(!singleText.contains("Úkon na prvek"), "u jediného prvku je řádek navíc");
        assertTrue(twoText.contains("Úkon na prvek"), "u dvou prvků řádek chybí");
    }

    @Test
    @DisplayName("Tlačítko „Otevřít výsledný soubor v AnEP“ v okně záznamu po stisku soubor otevře")
    void openResultButtonFires() throws Exception {
        Assumptions.assumeTrue(FxToolkit.available(), "JavaFX toolkit není k dispozici");
        ActionRecord r = record(AdvisorView.ContinuityMode.PRESERVE,
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2027), until(Y2027), true)),
                List.of(sig("S-1", "TOTAL_PASSED", until(Y2029), until(Y2029), false)),
                Y2029, Y2029);
        java.util.concurrent.atomic.AtomicInteger opened = new java.util.concurrent.atomic.AtomicInteger();
        String outcome = onFx(() -> {
            javafx.stage.Stage w = ActionRecordWindow.show(null, r, null, opened::incrementAndGet);
            try {
                javafx.scene.control.Button b = findButton(w.getScene().getRoot(),
                        "Otevřít výsledný soubor v AnEP");
                if (b == null) return "tlačítko nenalezeno";
                b.fire();
                return "stisknuto";
            } finally {
                w.close();
            }
        });
        assertEquals("stisknuto", outcome);
        assertEquals(1, opened.get(), "stisk soubor neotevřel");
    }

    private static javafx.scene.control.Button findButton(javafx.scene.Node node, String text) {
        if (node instanceof javafx.scene.control.Button b && text.equals(b.getText())) return b;
        if (node instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node c : p.getChildrenUnmodifiable()) {
                javafx.scene.control.Button found = findButton(c, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private interface FxCall<T> { T call() throws Exception; }

    private static <T> T onFx(FxCall<T> call) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                value.set(call.call());
            } catch (Throwable t) {
                failure.set(t);
            }
            done.countDown();
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "vlákno JavaFX neodpovědělo");
        if (failure.get() != null) throw new AssertionError(failure.get());
        return value.get();
    }
}
