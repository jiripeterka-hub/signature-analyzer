package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Horizon;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Kind;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Limit;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Run;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Záhlaví bloků v Digitální kontinuitě odpovídají na „do kdy“ a barvou říkají, jak to dopadlo.
 * Obojí se dá splést tiše, a proto se to měří:
 *
 * <ul>
 *   <li><b>Pravidla</b> — která veličina lhůtu určuje. Na sbírce se dají potkat jen některé
 *       kombinace; ostatní by se jinak nikdy neprošly.</li>
 *   <li><b>Barva</b> — že se odstín opravdu vykreslí. Neplatná hodnota v {@code -fx-background-color}
 *       nevyhodí výjimku, jen se tiše přeskočí, a záhlaví by pak nesla sdělení, které nikdo
 *       neuvidí.</li>
 * </ul>
 */
class ContinuityHorizonTest {

    private static final Instant NOW = SigFixture.NOW;
    private static final Instant FUTURE = SigFixture.FUTURE;
    private static final Instant PAST = SigFixture.PAST;
    private static final Instant STAMPED = SigFixture.STAMPED;
    private static final Instant LATER = SigFixture.LATER;
    private static final Run STRICT = new Run(true, List.of(), null);

    private static SigFixture.Builder sig() { return SigFixture.sig(); }

    private static boolean toolkitUp;

    @BeforeAll
    static void startToolkit() {
        toolkitUp = FxToolkit.available();
    }

    // ── Pravidla ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Bez časového razítka rozhoduje podpisový certifikát")
    void levelBEndsWithTheSigningCertificate() {
        SignatureSummary sig = sig().certNotAfter(FUTURE).build();
        Horizon h = ContinuityHorizon.anep(sig, "TOTAL_PASSED", STRICT);

        assertEquals(Kind.UNTIL, h.kind());
        assertEquals(FUTURE, h.when());
        assertEquals(Limit.SIGNING_CERT, h.limit(),
                "Bez důkazu existence se ověřuje k aktuálnímu času, a ten skončí s certifikátem.");
    }

    @Test
    @DisplayName("Podpisový certifikát jako kotva důvěry: datum se nestanoví, ani uplynulé")
    void signingCertificateAsTrustAnchorHasNoDate() {
        // Protokol SecuSign z roku 2017: pečeť bez razítka, certifikát do 8. 3. 2018, a přesto
        // platná — seznam ten certifikát vede jako identitu služby, takže jeho expiraci nikdo
        // neposuzuje. Záhlaví hlásilo „Do 8. 3. 2018 — již uplynulo".
        SignatureSummary sig = sig().certNotAfter(PAST).anchorCert().build();
        Horizon h = ContinuityHorizon.anep(sig, "TOTAL_PASSED", STRICT);

        assertEquals(Kind.OPEN, h.kind());
        assertNull(h.when());
        assertEquals(Limit.TRUST_ANCHOR, h.limit());
    }

    @Test
    @DisplayName("Vložený revokační údaj bez důkazu existence zkrátí lhůtu pod razítko")
    void unprovenEmbeddedRevocationOutranksTheTimestampCertificate() {
        Instant responderUntil = NOW.plus(200, ChronoUnit.DAYS);
        SignatureSummary sig = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", PAST, "doc", true, true)))
                .verifiableUntil(LATER)
                .certNotAfter(FUTURE)
                .unprovenRevocation(responderUntil)
                .build();
        Horizon h = ContinuityHorizon.anep(sig, "TOTAL_PASSED", STRICT);

        assertEquals(Kind.UNTIL, h.kind());
        assertEquals(responderUntil, h.when(),
                "Rozhoduje respondér, kterým je vložený údaj podepsán, ne certifikát razítka.");
        assertEquals(Limit.REVOCATION_PROOF, h.limit());
    }

    @Test
    @DisplayName("Kryté razítko nechává lhůtu na razítku — vložený údaj pak důkaz existence má")
    void provenEmbeddedRevocationChangesNothing() {
        SignatureSummary sig = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", false, true)))
                .verifiableUntil(FUTURE)
                .certNotAfter(PAST)
                .build();
        Horizon h = ContinuityHorizon.anep(sig, "TOTAL_PASSED", STRICT);

        assertEquals(Limit.TIMESTAMP_CERT, h.limit(),
                "Bez nekrytého vloženého údaje se nový článek uplatnit nesmí.");
        assertEquals(FUTURE, h.when());
    }

    @Test
    @DisplayName("Vložený údaj bez důkazu existence uzavře i otevřený horizont (sunset)")
    void unprovenEmbeddedRevocationClosesTheOpenHorizon() {
        Instant responderUntil = NOW.plus(200, ChronoUnit.DAYS);
        SignatureSummary sig = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", PAST, "doc", true, true)))
                .certNotAfter(PAST)
                .unprovenRevocation(responderUntil)
                .build();
        Horizon h = ContinuityHorizon.anep(sig, "TOTAL_PASSED", STRICT);

        assertEquals(Kind.UNTIL, h.kind(),
                "Sunset se sice neví, ale uznatelnost vloženého údaje končí známým dnem.");
        assertEquals(responderUntil, h.when());
        assertEquals(Limit.REVOCATION_PROOF, h.limit());
    }

    @Test
    @DisplayName("Kvalifikované razítko drží do sunsetu, takže datum není")
    void qualifiedTimestampLeavesTheDateOpen() {
        SignatureSummary sig = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", PAST, "doc", true, true)))
                .certNotAfter(PAST)
                .build();
        Horizon h = ContinuityHorizon.anep(sig, "TOTAL_PASSED", STRICT);

        assertEquals(Kind.OPEN, h.kind(), "Sunset se předem neví, datum by tedy bylo vymyšlené.");
        assertEquals(Limit.SUNSET, h.limit());
    }

    @Test
    @DisplayName("Nekvalifikované razítko končí s certifikátem své autority")
    void unqualifiedTimestampEndsWithItsCertificate() {
        SignatureSummary sig = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", false, true)))
                .verifiableUntil(FUTURE)
                .certNotAfter(PAST)
                .build();
        Horizon h = ContinuityHorizon.anep(sig, "TOTAL_PASSED", STRICT);

        assertEquals(Kind.UNTIL, h.kind());
        assertEquals(FUTURE, h.when());
        assertEquals(Limit.TIMESTAMP_CERT, h.limit(),
                "Za nekvalifikovanou službu důvěryhodné seznamy neručí; sunset se jí netýká.");
    }

    @Test
    @DisplayName("Neznámá platnost žádnou lhůtu nemá")
    void indeterminateHasNoHorizon() {
        SignatureSummary sig = sig().certNotAfter(FUTURE).build();
        assertEquals(Kind.LOST, ContinuityHorizon.anep(sig, "INDETERMINATE", STRICT).kind(),
                "Prodlužovat lze jen to, co se prokázalo; jinak by lhůta slibovala výsledek, "
                + "který nenastal.");
        assertEquals(Kind.INVALID, ContinuityHorizon.anep(sig, "TOTAL_FAILED", STRICT).kind());
    }

    @Test
    @DisplayName("Okruh CzechPOINTu se ptá na neporušenost razítka, ne na zdejší verdikt")
    void tolerantReadingCountsIntactStampsEvenWhenTheyDidNotPass() {
        // Razítko od služby s odebraným kvalifikovaným statusem: token je v pořádku, zdejší
        // ověření ho ale neuzná. Ten okruh nástrojů se na status neptá a certifikát platí dál.
        SignatureSummary sig = sig()
                .timestamps(List.of(
                        new TimestampEntry(STAMPED, false, "TSA", FUTURE, "doc", false, true)))
                .certNotAfter(PAST)
                .build();
        Horizon h = ContinuityHorizon.tolerant(sig, "INDETERMINATE");

        assertEquals(Kind.UNTIL, h.kind(),
                "Číst tu passed() znamená předpokládat, že cizí nástroj uzná totéž co AnEP.");
        assertEquals(FUTURE, h.when());
    }

    @Test
    @DisplayName("Vlastní kotva důvěry cizím nástrojům nepomůže")
    void tolerantReadingRejectsACustomAnchor() {
        SignatureSummary sig = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", true, true)))
                .certNotAfter(FUTURE)
                .customAnchor()
                .build();
        assertEquals(Kind.LOST, ContinuityHorizon.tolerant(sig, "TOTAL_PASSED").kind(),
                "Kotvu z vlastního úložiště nemají odkud vzít, takže na ní nemohou postavit nic.");
    }

    @Test
    @DisplayName("Samostatné razítko se posuzuje samo, ne přes chybějící podpisový certifikát")
    void aLoneTimestampIsItsOwnProof() {
        SignatureSummary qualified = sig().format("TimeStampToken").qualified()
                .certNotAfter(PAST).build();
        assertEquals(Limit.SUNSET,
                ContinuityHorizon.anep(qualified, "TOTAL_PASSED", STRICT).limit(),
                "Razítko nemá pod sebou další razítko a podpisový certifikát u něj není.");

        SignatureSummary plain = sig().format("TimeStampToken").certNotAfter(FUTURE).build();
        Horizon h = ContinuityHorizon.anep(plain, "TOTAL_PASSED", STRICT);
        assertEquals(FUTURE, h.when());
        assertEquals(Limit.TIMESTAMP_CERT, h.limit());
    }

    @Test
    @DisplayName("Okruh CzechPOINTu razítko VRI nevidí — ani to kryté")
    void tolerantReadingIgnoresVriStamps() {
        // Tvar odmítnutého dokumentu: jediný článek uvnitř platnosti podpisového certifikátu je
        // razítko VRI, dokumentové přibylo až dávno po jeho expiraci. Úschovna Czech POINT nad
        // ním 18. 9. 2026 napsala, že podpis „není označen časovým razítkem" — razítko VRI leží
        // v nepodepsané přírůstkové aktualizaci a jejich rozbor ho nevypíše.
        SignatureSummary sig = sig()
                .timestamps(List.of(
                        new TimestampEntry(STAMPED, true, "TSA", FUTURE, "vri", true, true),
                        new TimestampEntry(NOW, true, "TSA", LATER, "doc", true, true)))
                .certNotAfter(PAST)
                .build();

        assertEquals(Kind.LOST, ContinuityHorizon.tolerant(sig, "INDETERMINATE").kind(),
                "Bez razítka VRI zbude řada, jejíž první článek vznikl až po expiraci "
                + "podpisového certifikátu — pro tenhle okruh je to mezera.");

        // Zdejší čtení kryté razítko VRI naopak bere: dokumentové razítko nad ním dokazuje, že
        // token existoval, a standardy z toho rozhodný okamžik odvodit dovolují.
        assertEquals(Kind.UNTIL, ContinuityHorizon.anep(sig, "TOTAL_PASSED", STRICT).kind(),
                "Vyloučení razítka VRI patří jen k okruhu CzechPOINTu, ne ke standardům.");
    }

    @Test
    @DisplayName("Dokumentové razítko naopak řadu pro okruh CzechPOINTu nese")
    void tolerantReadingAcceptsADocumentTimestamp() {
        // Protějšek předchozího a rovněž změřený: týž tvar, jen článkem uvnitř platnosti
        // certifikátu je dokumentové razítko. Úschovna takový dokument uznala.
        SignatureSummary sig = sig()
                .timestamps(List.of(
                        new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", true, true)))
                .certNotAfter(PAST)
                .build();

        Horizon h = ContinuityHorizon.tolerant(sig, "TOTAL_PASSED");
        assertEquals(Kind.UNTIL, h.kind());
        assertEquals(FUTURE, h.when());
    }

    @Test
    @DisplayName("Ústupek, o který se závěr opřel, bere výsledek validátoru podle standardů")
    void aDecisiveConcessionCostsTheStandardsReading() {
        SignatureSummary sig = sig()
                .certNotAfter(FUTURE)
                .decisiveSettings(List.of("Ignorovat zastaralost algoritmu SHA1"))
                .build();
        var dev = ContinuityHorizon.deviations(sig, STRICT, false, "TOTAL_PASSED");

        assertTrue(dev.lenient());
        assertEquals(Kind.LOST,
                ContinuityHorizon.standards(sig, "TOTAL_PASSED", STRICT, dev).kind());
        assertEquals(Kind.UNTIL, ContinuityHorizon.anep(sig, "TOTAL_PASSED", STRICT).kind(),
                "Zdejšímu čtení ten ústupek nevadí — právě v tom je ten rozdíl.");
    }

    @Test
    @DisplayName("Kvalifikované razítko s dosud platným certifikátem dá pevné datum")
    void qualifiedTimestampWithLivingCertificateGivesTheDate() {
        SignatureSummary sig = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", true, true)))
                .verifiableUntil(FUTURE)
                .certNotAfter(PAST)
                .build();
        Horizon h = ContinuityHorizon.anep(sig, "TOTAL_PASSED", STRICT);

        assertEquals(Kind.UNTIL, h.kind(),
                "Dokud certifikát razítkovací autority platí, je datum pevné a obě čtení pravidel "
                + "se na něm shodnou; sunset začíná až za ním.");
        assertEquals(FUTURE, h.when());
        assertEquals(Limit.QUALIFIED_TIMESTAMP_CERT, h.limit());
    }

    @Test
    @DisplayName("Revokační údaj z vlastního úložiště lhůtu nezkracuje, ze sítě ano")
    void ownStoreRevocationDoesNotShortenTheHorizon() {
        // „T kvalifikovany ICA.pdf": závěr nese CRL z vlastního úložiště, kdežto lhůta pro
        // uchování ukazuje na podpisový certifikát dávno po expiraci. Blok tehdy hlásil ji.
        SignatureSummary fromStore = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", true, true)))
                .verifiableUntil(FUTURE).certNotAfter(PAST)
                .deadline(PAST).revocationFrom("store")
                .build();
        Horizon stored = ContinuityHorizon.anep(fromStore, "TOTAL_PASSED", STRICT);
        assertEquals(FUTURE, stored.when(),
                "Údaj ve vlastním úložišti nezvětrá, takže horizont neurčuje.");

        // Lhůta v budoucnu je horizont: až vydavateli skončí certifikát, přestane vydávat.
        SignatureSummary fromNet = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", LATER, "doc", true, true)))
                .verifiableUntil(LATER).certNotAfter(PAST)
                .deadline(FUTURE).revocationFrom("online")
                .build();
        Horizon fetched = ContinuityHorizon.anep(fromNet, "TOTAL_PASSED", STRICT);
        assertEquals(FUTURE, fetched.when(),
                "Údaj stažený ze sítě je dostupný jen dokud ho vydavatel zveřejňuje.");
        assertEquals(Limit.REVOCATION, fetched.limit());
    }

    @Test
    @DisplayName("Lhůta, která už uplynula, horizontem není — běh ji sám vyvrátil")
    void anElapsedDeadlineIsNoHorizon() {
        // „T kvalifikovany PS.pdf": ověření prošlo díky ústupku na post-expirační údaj, který se
        // dnes stáhnout podařilo, ačkoli lhůta pro uchování skončila loni. Blok tu lhůtu hlásil
        // jako horizont, přestože ji zrovna proběhlé ověření vyvrátilo.
        SignatureSummary sig = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", true, true)))
                .verifiableUntil(FUTURE).certNotAfter(PAST)
                .deadline(PAST).revocationFrom("online")
                .build();
        Horizon h = ContinuityHorizon.anep(sig, "TOTAL_PASSED", STRICT);

        assertEquals(FUTURE, h.when(),
                "Horizont padá na další článek v pořadí, tedy na řetěz razítek.");
        assertNotEquals(Limit.REVOCATION, h.limit());
        assertTrue(ContinuityHorizon.dependsOnOnlineRevocation(sig),
                "Závislost na síti nezmizela — jen není datem, nýbrž podmínkou.");
    }

    @Test
    @DisplayName("Blok AnEP řekne, co použil, a z toho odvodí lhůtu")
    void theAnepBlockDerivesTheDeadlineFromWhatItUsed() {
        // Dřív tuhle věc nesla samostatná poznámka o závislosti na síti
        // (onlineRevocationNote); od 7. 9. 2026 je to první a druhá věta bloku, protože
        // odvození lhůty a materiál, ze kterého plyne, patří k sobě.
        // Aby lhůtu určila dostupnost revokačních údajů, musí být dřív než konec platnosti
        // podpisového certifikátu — jinak rozhoduje certifikát a text mluví o něm.
        String text = todoText(sig()
                .certNotAfter(LATER)
                .revocationFrom("online")
                .deadline(FUTURE)
                .build(), "TOTAL_PASSED");

        assertTrue(text.contains("Aplikace AnEP ověřila platnost"),
                "Blok má začínat tím, jak se ověřovalo: " + text);
        assertTrue(text.contains("on-line"),
                "Odkud revokační údaj přišel, rozhoduje o celé lhůtě: " + text);
        assertTrue(text.contains("Proto je pro ni rozhodující, jak dlouho budou tyto informace"
                        + " dostupné"),
                "Věta o způsobu ověření musí ústit v to, co je proto rozhodující: " + text);
        // Druhá věta si bere pojmenování článku z modelu (v přípravku „lhůtu určuje …“), aby
        // nejmenovala podpisový certifikát tam, kde lhůtu drží jiný článek řetězu.
        assertTrue(text.contains("lhůtu určuje"),
                "Který článek lhůtu drží, má říct model, ne blok: " + text);
        assertTrue(text.contains("Proto možnost ověřit platnost"),
                "A uzavírá se tím, do kdy tedy ověření vydrží: " + text);
    }

    @Test
    @DisplayName("Fakta o prvku stojí nad bloky a nesou oba certifikáty")
    void factsNameBothCertificates() {
        SignatureSummary sig = sig()
                .format("PAdES-BASELINE-T")
                .certNotAfter(FUTURE)
                .timestamps(List.of(new TimestampEntry(PAST, true, "I.CA TSU", LATER, "sig", true)))
                .build();
        String facts = String.join("\n", ContinuityFacts.paragraphs(sig));

        assertTrue(facts.contains("v úrovni T"), facts);
        assertTrue(facts.contains("s jedním platným (kvalifikovaným elektronickým) časovým"
                + " razítkem"), facts);
        assertTrue(facts.contains("ale bez vložených revokačních informací"), facts);
        assertTrue(facts.contains("Podpis je založen na certifikátu"), facts);
        assertTrue(facts.contains("Časové razítko je založeno na certifikátu (autority I.CA TSU)"),
                facts);
    }

    // ── Barva ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Barevné záhlaví se opravdu vykreslí, a to i na zavřeném bloku")
    void colouredHeadersActuallyPaint() {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit není k dispozici");
        javafx.scene.paint.Color[] seen = onFxThread(() -> {
            javafx.scene.paint.Color[] out = new javafx.scene.paint.Color[4];
            AdvisorView.BlockTint[] tints = {
                    AdvisorView.BlockTint.GREEN, AdvisorView.BlockTint.BROWN,
                    AdvisorView.BlockTint.BLUE, AdvisorView.BlockTint.RED };
            for (int i = 0; i < tints.length; i++) {
                // Zavřený schválně: barva má nést sdělení dřív, než čtenář klikne.
                VBox block = AdvisorView.collapsibleBlock("nadpis", new VBox(), false, tints[i]);
                new Scene(new VBox(block));
                block.applyCss();
                block.layout();
                Region header = (Region) block.getChildren().get(0);
                assertNotNull(header.getBackground(), "Záhlaví zůstalo bez podkladu.");
                assertTrue(header.getBackground().getFills().size() > 0);
                out[i] = (javafx.scene.paint.Color)
                        header.getBackground().getFills().get(0).getFill();
            }
            return out;
        });

        for (javafx.scene.paint.Color c : seen) {
            assertNotEquals(javafx.scene.paint.Color.TRANSPARENT, c,
                    "Průhledné záhlaví by sdělení barvy ztratilo.");
        }
        assertNotEquals(seen[0], seen[1], "Zelená a hnědá se musí lišit.");
        assertNotEquals(seen[1], seen[2], "Hnědá a modrá se musí lišit.");
        assertNotEquals(seen[2], seen[3],
                "Modrá a červená se musí lišit — od 30. 8. 2026 stojí vedle sebe: „Jak dlouho je"
                + " možné zachovat ověřitelnost?“ modrá, „AnEP nabízí možnost…“ červená.");
    }

    @Test
    @DisplayName("Uplynulá lhůta z vloženého údaje horizont AnEP nesnižuje")
    void anElapsedRevocationProofIsNotAHorizon() {
        SignatureSummary sig = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", false, true)))
                .verifiableUntil(FUTURE)
                .certNotAfter(PAST)
                .unprovenRevocation(PAST)
                .build();
        Horizon h = ContinuityHorizon.anep(sig, "TOTAL_PASSED", STRICT);

        assertEquals(Limit.TIMESTAMP_CERT, h.limit(),
                "Ústupek s časem nemizí: AnEP ověří i zítra, takže minulé datum není lhůta.");
        assertEquals(FUTURE, h.when());
    }

    @Test
    @DisplayName("Validátory podle standardů nesou datum, dokdy to dokázaly")
    void standardsCarryTheLastDayItWorked() {
        SignatureSummary sig = sig().certNotAfter(PAST).deadline(PAST).build();
        ContinuityHorizon.Deviations dev = new ContinuityHorizon.Deviations(
                List.of(ReportMapper.Relaxation.EXPIRED_REVOCATION_SOURCES.settingName()),
                List.of(), true, false, null, false, null, false);
        Horizon h = ContinuityHorizon.standards(sig, "TOTAL_PASSED", STRICT, dev);

        assertEquals(Kind.LOST, h.kind());
        assertEquals(PAST, h.when(), "Nejen „nedokáží“, ale i dokdy dokázaly.");
    }

    @Test
    @DisplayName("Ústupek bez data (SHA-1) žádné datum nepřidá")
    void standardsStayBareForARuleThatNeverHadADate() {
        SignatureSummary sig = sig().certNotAfter(PAST).deadline(PAST).build();
        ContinuityHorizon.Deviations dev = new ContinuityHorizon.Deviations(
                List.of("Toleruj SHA-1"), List.of(), true, false, null, false, null, false);
        Horizon h = ContinuityHorizon.standards(sig, "TOTAL_PASSED", STRICT, dev);

        assertEquals(Kind.LOST, h.kind());
        assertNull(h.when(),
                "SHA-1 je vlastnost podpisu od začátku; datum by se četlo jako zmeškaná lhůta.");
    }

    // ── Lhůta k úkonu ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Lhůta k úkonu je nejzazší ze čtení, která zásah ještě zachrání")
    void theTodoDeadlineTakesTheLatestSalvageableReading() {
        Horizon anepElapsed = new Horizon(Kind.UNTIL, PAST, Limit.REVOCATION_PROOF);
        Horizon tolerant = new Horizon(Kind.UNTIL, FUTURE, Limit.TIMESTAMP_CERT);

        assertEquals(FUTURE, ContinuityElementBlocks.salvageableUntil(anepElapsed, tolerant),
                "Uchování má smysl, dokud ho ověří aspoň jeden okruh nástrojů.");
        assertEquals(LATER, ContinuityElementBlocks.salvageableUntil(
                new Horizon(Kind.UNTIL, LATER, Limit.TIMESTAMP_CERT), tolerant));
        assertEquals(PAST, ContinuityElementBlocks.salvageableUntil(anepElapsed,
                new Horizon(Kind.LOST, null, Limit.NONE)),
                "Kde tolerantní čtení nevyšlo, zbývá jen to zdejší — i když už uplynulo.");
        assertNull(ContinuityElementBlocks.salvageableUntil(
                new Horizon(Kind.OPEN, null, Limit.SUNSET), tolerant),
                "U otevřeného horizontu žádné číslo neexistuje.");
    }

    // ── Užitek ze zásahu ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Zdroj, který přidá 48 minut, ověřitelnost neposune")
    void aSourceThatBarelyOutlivesTheDocumentIsNoGain() {
        Instant docUntil = Instant.parse("2032-05-02T08:33:06Z");
        assertFalse(ContinuityHorizon.worthTheAction(docUntil,
                        docUntil.plus(48, ChronoUnit.MINUTES)),
                "Formálně posun, prakticky nic — a přesně tohle se na I.CA potkalo.");
        assertFalse(ContinuityHorizon.worthTheAction(docUntil,
                docUntil.minus(3, ChronoUnit.DAYS)));
        assertFalse(ContinuityHorizon.worthTheAction(docUntil,
                docUntil.plus(300, ChronoUnit.DAYS)),
                "Úkon, který by se musel do roka opakovat, je odklad, ne řešení.");
    }

    @Test
    @DisplayName("Zdroj s certifikátem o roky delším za zásah stojí")
    void aLongerSourceIsWorthIt() {
        Instant docUntil = Instant.parse("2032-05-02T08:33:06Z");
        assertTrue(ContinuityHorizon.worthTheAction(docUntil,
                docUntil.plus(800, ChronoUnit.DAYS)));
        assertTrue(ContinuityHorizon.worthTheAction(null, docUntil),
                "Bez čeho měřit se varovat nedá.");
        assertTrue(ContinuityHorizon.worthTheAction(docUntil, null));
    }

    // ── Neplatnost, na které se neshodnou všichni ────────────────────────────────────────────

    /**
     * Situace 53 — obsah nezměněný, neověřila se podpisová hodnota.
     *
     * <p>Záporný verdikt tu nemluví za všechny: validátory, které se řídí polem digestAlgorithm
     * (Adobe Reader, Signer, CzechPOINT), takový podpis ověří. Do 3. 9. 2026 psala záložka
     * „K témuž závěru dojde každý validátor, dnes i později“ a okruh CzechPOINTu dostal rovnou
     * {@code INVALID} — obojí prokazatelná nepravda, změřená na „certifikacni a dva uznavane
     * 2015 10 28 do 2026 10.pdf“.
     */
    @Test
    @DisplayName("Vada jen v podpisové hodnotě nezavírá okruh CzechPOINTu")
    void aValueOnlyDefectLeavesTheTolerantReadingOpen() {
        SignatureSummary sig = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", false, true)))
                .certNotAfter(PAST)
                .situations(53)
                .build();
        assertEquals(Kind.UNTIL, ContinuityHorizon.tolerant(sig, "TOTAL_FAILED").kind(),
                "Tyhle nástroje takový podpis ověří; „neplatnost potvrdí i oni“ je nepravda.");
        assertTrue(ContinuityHorizon.valueDefectOnly(sig));

        SignatureSummary tampered = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", false, true)))
                .certNotAfter(PAST)
                .situations(1)
                .build();
        assertEquals(Kind.INVALID, ContinuityHorizon.tolerant(tampered, "TOTAL_FAILED").kind(),
                "Změněný obsah je zjištění, na kterém se shodnou všichni.");
        assertFalse(ContinuityHorizon.valueDefectOnly(tampered));
    }

    @Test
    @DisplayName("Blok o AnEP u situace 53 nemluví za ostatní")
    void theAnepBlockDoesNotSpeakForEveryoneAtFiftyThree() {
        SignatureSummary sig = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", false, true)))
                .certNotAfter(PAST)
                .situations(53)
                .build();
        String text = todoText(sig, "TOTAL_FAILED");

        assertFalse(text.contains("K témuž závěru dojde každý validátor"), text);
        assertTrue(text.contains("Podepsaný obsah je přitom nezměněný"), text);
        assertTrue(text.contains("digestAlgorithm"),
                "Čtenář má vědět, čím se ten druhý výsledek liší: " + text);
    }

    // ── Čím je lhůta určena ──────────────────────────────────────────────────────────────────

    /**
     * Který článek lhůtu drží. Rozlišení bylo dřív vidět v textu — pod lhůtou stála, nebo
     * nestála pobídka „Nejlépe ihned — čekat na konec lhůty nepřináší nic“. Ta je od 9. 9. 2026
     * pryč (pokyn uživatele: čekáním se dá získat razítko od zdroje s déle platným certifikátem,
     * takže věta nebyla pravdivá), měřené případy ale zůstávají — jen se ptají rovnou modelu.
     */
    @Test
    @DisplayName("Lhůtu drží ten článek, který o ní opravdu rozhoduje")
    void theDeadlineNamesWhatHoldsIt() {
        // Údaj se stahuje ze sítě a vydavatel ho jednou přestane zveřejňovat.
        assertEquals(Limit.REVOCATION, ContinuityHorizon.anep(sig()
                        .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", false, true)))
                        .verifiableUntil(FUTURE)
                        .certNotAfter(PAST)
                        .revocationFrom("online")
                        .deadline(NOW.plus(100, ChronoUnit.DAYS))
                        .build(), "TOTAL_PASSED", STRICT).limit(),
                "Dokud je co stahovat — to je mez, kterou drží dostupnost, ne certifikát.");

        // Všechno je vloženo; rozhoduje certifikát razítkovací autority.
        assertEquals(Limit.TIMESTAMP_CERT, ContinuityHorizon.anep(sig()
                        .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", false, true)))
                        .verifiableUntil(FUTURE)
                        .certNotAfter(PAST)
                        .revocationFrom("document")
                        .build(), "TOTAL_PASSED", STRICT).limit());

        // Údaj v souboru je, chybí mu důkaz existence — bajty nezmizí, mez drží vydavatel údaje.
        assertEquals(Limit.REVOCATION_PROOF, ContinuityHorizon.anep(sig()
                        .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", LATER, "doc", false, true)))
                        .verifiableUntil(LATER)
                        .certNotAfter(PAST)
                        .revocationFrom("document")
                        .unprovenRevocation(FUTURE)
                        .build(), "TOTAL_PASSED", STRICT).limit());
    }

    /**
     * Kde závěr stojí na revokačním údaji staženém ze sítě a doba, po kterou se na jeho
     * dostupnost dalo spoléhat, už uplynula, <b>žádná lhůta neexistuje</b>.
     *
     * <p>Nález uživatele z 9. 9. 2026 nad „2 podpisy signed.pdf“: podpis v úrovni T,
     * certifikát expirovaný 3. 9. 2025, OCSP stažená téhož dne, kdy se ověřovalo — a blok
     * přesto hlásil lhůtu 13. 10. 2030, což je konec platnosti certifikátu razítkovací
     * autority. „Kde ale bereš jistotu, že takováto OCSP bude dostupná i později?“ Nikde;
     * datum proto ustupuje odpovědi „nejlépe ihned“.
     */
    @Test
    @DisplayName("Údaj ze sítě po expiraci: místo data „nejlépe ihned“")
    void anOnlineOnlyRevocationHasNoDeadline() {
        SignatureSummary sig = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", false, true)))
                .verifiableUntil(FUTURE)
                .certNotAfter(PAST)
                .revocationFrom("online")
                .deadline(PAST)
                .build();

        assertTrue(ContinuityElementBlocks.actNow(sig),
                "Údaj je jen ze sítě a doba, po kterou byl zaručeně dostupný, uplynula.");
        assertTrue(ContinuityElementBlocks.todoTitle(PRESERVE_ONLY, true, FUTURE, true)
                        .endsWith("Kdy je třeba ji využít?  Nejlépe ihned"),
                ContinuityElementBlocks.todoTitle(PRESERVE_ONLY, true, FUTURE, true));

        String text = todoText(sig);
        assertTrue(text.contains("Lhůtu k využití této možnosti stanovit nelze"),
                "Proč lhůta chybí, musí stát v bloku: " + text);
        assertFalse(text.contains("je třeba využít nejpozději do"),
                "Datum z certifikátu razítka není lhůtou k úkonu: " + text);
    }

    /**
     * A totéž v bloku „Jak dlouho dokáže AnEP ověřit platnost?“: z nadpisu mizí datum
     * <b>bez náhrady</b> a odpověď je celá v těle — i s tím, co by platilo, kdyby údaj dostupný
     * zůstal. Pokyn uživatele z 9. 9. 2026.
     *
     * <p>Nejdůležitější je poslední tvrzení: věta „Dřívější existenci podpisu prokazuje časové
     * razítko, takže je rozhodující, jak dlouho bude uznatelné ono“ se u takového prvku
     * <b>nesmí</b> sázet. Razítko prokazuje existenci podpisu; o možnosti znovu zjistit stav
     * revokace neříká nic, a právě to byl ten nepodložený skok.
     */
    @Test
    @DisplayName("Údaj ze sítě po expiraci: v bloku o dosahu AnEP zmizí datum z nadpisu")
    void anOnlineOnlyRevocationTakesTheDateOutOfTheAnepHeading() {
        SignatureSummary sig = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", false, true)))
                .verifiableUntil(FUTURE)
                .certNotAfter(PAST)
                .revocationFrom("online")
                .deadline(PAST)
                .build();

        String text = anepText(sig);
        assertTrue(text.contains("Jak dlouho dokáže AnEP ověřit platnost?" + '\n'),
                "Nadpis má zůstat holou otázkou, bez data i bez náhrady: " + text);
        assertFalse(text.contains("Jak dlouho dokáže AnEP ověřit platnost?  "),
                "Datum ani jiná odpověď do nadpisu nepatří: " + text);
        assertTrue(text.contains("proto stanovit nelze"),
                "Proč doba chybí, musí stát v těle bloku: " + text);
        assertTrue(text.contains("Kdyby ta informace dostupná zůstala, dokázala by aplikace"
                        + " ověřit platnost do " + full(FUTURE)),
                "Datum se neztrácí, jen dostává podmínku: " + text);
        assertFalse(text.contains("je rozhodující, jak dlouho bude uznatelné ono"),
                "Razítko o dostupnosti revokačního údaje nevypovídá: " + text);
    }

    /** Text prvního bloku i s nadpisem — nadpis je tu půlka měřeného. */
    private static String anepText(SignatureSummary sig) {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit nenaběhl");
        return onFxThread(() -> {
            VBox blocks = ContinuityElementBlocks.build(sig, "TOTAL_PASSED", STRICT, false,
                    new ContinuityElementBlocks.Gates(true, false, false, true),
                    null, true, ContinuityElementBlocks.Source.named("I.CA", null), null);
            StringBuilder sb = new StringBuilder();
            collectText(blocks.getChildren().get(0), sb);
            return sb.toString();
        });
    }

    private static String full(java.time.Instant when) {
        return cz.digitalnikontinuita.anep.core.TimeFormat.full(when);
    }

    @Test
    @DisplayName("Dokud je na dostupnost údaje spoleh, lhůta datum má")
    void anOnlineRevocationStillInReachKeepsItsDate() {
        SignatureSummary sig = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", false, true)))
                .verifiableUntil(FUTURE)
                .certNotAfter(PAST)
                .revocationFrom("online")
                .deadline(NOW.plus(100, ChronoUnit.DAYS))
                .build();

        assertFalse(ContinuityElementBlocks.actNow(sig),
                "Údaj je zatím zaručeně dostupný, takže lhůta datum má.");
        assertTrue(todoText(sig).contains("je třeba využít nejpozději do"), todoText(sig));
    }

    /**
     * Pobídka ke spěchu se nesází <b>nikde</b>, ani tam, kde se dřív sázela.
     *
     * <p>Pokyn uživatele z 9. 9. 2026: „Může přinést to, že bude k dispozici časové razítko
     * s delší platností svého certifikátu. Takže navrhuji tuto větu vypustit, od toho tam ta
     * lhůta je.“ Test drží právě tohle — kdyby se věta vrátila jinou cestou, spadne.
     */
    @Test
    @DisplayName("Pobídka „Nejlépe ihned“ se nesází nikde")
    void noHasteAdviceAnywhere() {
        for (String origin : List.of("online", "document", "store")) {
            String text = todoText(sig()
                    .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", false, true)))
                    .verifiableUntil(FUTURE)
                    .certNotAfter(PAST)
                    .revocationFrom(origin)
                    .deadline(NOW.plus(100, ChronoUnit.DAYS))
                    .build());
            assertFalse(text.contains("Nejlépe ihned"),
                    "O tempu rozhoduje lhůta, ne pobídka (původ údaje „" + origin + "“): "
                    + text);
        }
    }

    /**
     * A totéž o prvním bloku: „chybějí revokační informace“ je u S35 nepravda.
     *
     * <p>Data v dokumentu jsou; chybí jim důkaz, že tam byla, dokud šla uznat, a ten se neshání —
     * vytváří ho archivní razítko. Nad „T kvalifikovany PS.pdf“ první blok tvrdil, že aplikace
     * nemá revokační informace kde vzít, zatímco poslední blok nabízel úkon, který to spraví.
     */
    @Test
    @DisplayName("Situace 35: první blok netvrdí, že revokační informace chybějí")
    void thirtyFiveChangesTheOutlookInTheFirstBlock() {
        String text = blockTextFor(35);
        assertTrue(text.contains("Nechybějí ale revokační informace"), text);
        assertFalse(text.contains("nemá kde vzít"),
                "Materiál v dokumentu je; shánět se nemá co, jen doložit: " + text);
        assertFalse(blockTextFor().contains("Nechybějí ale revokační informace"),
                "Bez S35 se o vloženém materiálu nic neví.");
    }

    /**
     * Varování o jednorázové platnosti nastavení jen tam, kde na tom nastavení výsledek stojí.
     *
     * <p>Aplikace po ověření zahazuje ústupky a zadaný čas; vlastní kotva důvěry ani úložiště CRL
     * se nevracejí. Nad podpisem, který prošel bez ústupku, tedy věta strašila nastavením, které
     * se ničeho nedotklo — vidět na `kvalifikovany viditelny.pdf`, kde závěr stojí na vlastní CRL
     * a žádný ústupek se neuplatnil.
     */
    @Test
    @DisplayName("Uplatněný ústupek si vyžádá poznámku o jednorázovém nastavení")
    void aUsedConcessionCarriesTheOneShotNote() {
        SignatureSummary bare = sig().certNotAfter(FUTURE).build();
        assertFalse(todoText(bare).contains("Uvedené platí pro ověření provedené stejným"),
                "Bez ústupku a bez zadaného času se nastavení výsledku nedotklo.");

        SignatureSummary lenient = sig().certNotAfter(FUTURE)
                .decisiveSettings(List.of("Ignorovat zastaralost algoritmu SHA1"))
                .build();
        assertTrue(todoText(lenient).contains("Uvedené platí pro ověření provedené stejným"),
                "Ústupek platí jen na jedno ověření a čtenář to musí vědět.");
    }

    @Test
    @DisplayName("Zadaný čas ověření si ji vyžádá také")
    void aCustomValidationTimeCarriesItToo() {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit nenaběhl");
        SignatureSummary sig = sig().certNotAfter(FUTURE).build();
        Run atPast = new Run(true, List.of(), PAST);
        String text = onFxThread(() -> {
            VBox blocks = ContinuityElementBlocks.build(sig, "TOTAL_PASSED", atPast, false,
                    new ContinuityElementBlocks.Gates(true, false, false, true),
                    null, true, ContinuityElementBlocks.Source.named("I.CA", null), null);
            StringBuilder sb = new StringBuilder();
            collectText(blocks, sb);
            return sb.toString();
        });
        assertTrue(text.contains("Uvedené platí pro ověření provedené stejným"),
                "Zadaný čas se stejně jako ústupek po ověření zahodí: " + text);
    }

    /** Blok úkonu nad prvkem, který AnEP neověří, ale tolerantní okruh ano. */
    private static String blockTextFor(Integer... situations) {
        return todoText(sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", FUTURE, "doc", false, true)))
                .certNotAfter(PAST)
                .situations(situations)
                .build(), "INDETERMINATE");
    }

    /** Texty bloku „Co nabízí AnEP a do kdy je třeba toho využít“ slepené do jednoho řetězce. */
    private static String todoText(SignatureSummary sig) {
        return todoText(sig, "TOTAL_PASSED");
    }

    private static String todoText(SignatureSummary sig, String verdict) {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit nenaběhl");
        return onFxThread(() -> {
            VBox blocks = ContinuityElementBlocks.build(sig, verdict, STRICT, false,
                    new ContinuityElementBlocks.Gates(true, false, false, true),
                    null, true, ContinuityElementBlocks.Source.named("I.CA", null), null);
            StringBuilder sb = new StringBuilder();
            collectText(blocks, sb);
            return sb.toString();
        });
    }

    private static void collectText(javafx.scene.Node n, StringBuilder sb) {
        if (n instanceof javafx.scene.control.Labeled l && l.getText() != null) {
            sb.append(l.getText()).append('\n');
        }
        if (n instanceof javafx.scene.text.TextFlow tf) {
            for (javafx.scene.Node c : tf.getChildrenUnmodifiable()) {
                if (c instanceof javafx.scene.text.Text t) sb.append(t.getText());
            }
            sb.append('\n');
            return;
        }
        if (n instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node c : p.getChildrenUnmodifiable()) collectText(c, sb);
        }
    }

    // ── Nadpis úkonového bloku ──────────────────────────────────────────

    private static final ContinuityElementBlocks.Gates PRESERVE_ONLY =
            new ContinuityElementBlocks.Gates(true, false, false, false);
    private static final ContinuityElementBlocks.Gates PROLONG_ONLY =
            new ContinuityElementBlocks.Gates(false, true, false, false);
    private static final ContinuityElementBlocks.Gates FIX_ONLY =
            new ContinuityElementBlocks.Gates(false, false, false, true);

    @Test
    @DisplayName("Nadpis jmenuje variantu i termín")
    void todoTitleCarriesTheDeadline() {
        String title = ContinuityElementBlocks.todoTitle(PRESERVE_ONLY, true, FUTURE);
        assertTrue(title.startsWith("AnEP nabízí možnost uchování."), title);
        assertTrue(title.contains("Do "), "Bez data by se pro něj muselo rozbalovat: " + title);
        assertTrue(ContinuityElementBlocks.todoTitle(PROLONG_ONLY, true, FUTURE)
                        .startsWith("AnEP nabízí možnost prodloužení."),
                "Jméno varianty se musí shodovat s tlačítkem dole.");
    }

    @Test
    @DisplayName("Uplynulá lhůta se v nadpisu pozná")
    void todoTitleNamesAnElapsedDeadline() {
        String title = ContinuityElementBlocks.todoTitle(PRESERVE_ONLY, true, PAST);
        assertTrue(title.contains("Lhůta k jejímu využití uplynula"),
                "Holé datum by se četlo jako čas, který ještě je: " + title);
    }

    @Test
    @DisplayName("Bez lhůty zůstane otázka, tam kam úkon nedosáhne zmizí i varianta")
    void todoTitleStaysBareWithoutADeadline() {
        assertEquals("AnEP nabízí možnost uchování. Do kdy je třeba ji využít?",
                ContinuityElementBlocks.todoTitle(PRESERVE_ONLY, true, null),
                "Horizont OPEN datum nemá, otázku z nadpisu ale zodpovídá tělo bloku.");
        assertEquals("Co AnEP nabízí",
                ContinuityElementBlocks.todoTitle(PRESERVE_ONLY, false, FUTURE),
                "Termín nad prvkem, na který úkon nedosáhne, by sliboval, co v těle nestojí.");
        assertEquals("AnEP nabízí pouze možnost vytvoření forenzního balíčku",
                ContinuityElementBlocks.todoTitle(FIX_ONLY, false, null));
    }

    // ── Zdroj razítek kratší než řada, kterou dokument nese ─────────────

    /**
     * Nález uživatele z 20. 9. 2026 nad „PAdES B-LT kvalifikovany PS neviditelny.pdf“: blok
     * „Jak dlouho je možné zachovat ověřitelnost?“ hlásil „přidání takového razítka by nemělo
     * žádný přínos“ a blok hned pod ním „AnEP nabízí možnost uchování. Do kdy je třeba ji
     * využít?  Do 23. 3. 2027“.
     *
     * <p>Pravdivý je ten druhý. Změřeno skutečně provedeným uchováním (zdroj ACCV, certifikát do
     * 25. 2. 2029): výsledek je podpis v úrovni LTA, který AnEP ověří <b>nejméně do
     * 1. 11. 2031</b> — tedy o čtyři a půl roku dál než dnes. Mez „žádný přínos“ se přitom
     * měřila proti nejzazšímu z obou čtení, tedy proti okruhu CzechPOINTu, ačkoli úkon
     * zachraňuje to bližší, zdejší.
     */
    @Test
    @DisplayName("Krátký zdroj razítek nad delší řadou: úkon přínos má a blok to řekne")
    void aShortSourceStillGainsWhatTheChainAlreadyReaches() {
        Instant responderUntil = NOW.plus(200, ChronoUnit.DAYS);
        SignatureSummary sig = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", LATER, "doc", true, true)))
                .verifiableUntil(LATER)
                .certNotAfter(PAST)
                .unprovenRevocation(responderUntil)
                .build();

        String text = textWithSource(sig, FUTURE);
        assertFalse(text.contains("nemělo žádný přínos"),
                "Úkon zafixuje vložený údaj a tím posune zdejší ověřitelnost: " + text);
        assertTrue(text.contains("Jak dlouho je možné zachovat ověřitelnost?  Do " + full(LATER)),
                "Odpověď je dosah řady razítek, ne konec certifikátu zdroje: " + text);
        assertTrue(text.contains("Nejde přitom jen o prodloužení ověřitelnosti, ale i o její"
                        + " zachování"),
                "Druhá půlka úkonu nestojí na dosahu razítka, nýbrž na revokačních"
                + " informacích: " + text);
        assertTrue(text.contains("Mladší razítko ale řadu nezkracuje"),
                "Proč dosah nekončí s certifikátem zdroje, musí být řečeno: " + text);
        assertTrue(text.contains("neposune dál, než kam dokument dosáhne i bez něj"),
                "Poznámka o krátkém zdroji zůstává, ale „vůbec“ v ní být nesmí: " + text);
        assertFalse(text.contains("ověřitelnost neposune vůbec"), text);
    }

    /**
     * A protějšek, aby se předchozí oprava nepřelila i tam, kam nepatří: co je vloženo
     * i zafixováno, drží obě čtení na témže datu a kratší zdroj razítek k němu opravdu nepřidá
     * nic. Tenhle případ v D:\tmp nastává čtyřikrát a text u něj zůstal beze změny.
     */
    @Test
    @DisplayName("Kde obě čtení končí stejně, zůstává „žádný přínos“")
    void aShortSourceOverNothingToSalvageKeepsTheOldWording() {
        SignatureSummary sig = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", LATER, "doc", true, true)))
                .verifiableUntil(LATER)
                .certNotAfter(PAST)
                .build();

        String text = textWithSource(sig, FUTURE);
        assertTrue(text.contains("nemělo žádný přínos"),
                "Není co zachránit a zdroj nedosáhne dál — přínos opravdu žádný: " + text);
        assertFalse(text.contains("Jak dlouho je možné zachovat ověřitelnost?  Do "),
                "Datum v nadpisu by se četlo jako zkrácení: " + text);
        assertTrue(text.contains("ověřitelnost neposune vůbec"), text);
    }

    /**
     * Odstavec o smyslu úkonu má dvě půlky a u situace 35 platí jen ta druhá: data v dokumentu
     * jsou a chybí jim jen razítko nad celým souborem, takže „vloží revokační informace dostupné
     * dnes“ by si odporovalo s prvním blokem, který o témž podpisu píše, že je aplikace nemá
     * odkud vzít. Táž past, na kterou upozornil uživatel 3. 9. 2026 u zrušeného odstavce
     * o záložním užitku.
     */
    @Test
    @DisplayName("U situace 35 mluví odstavec jen o zafixování, ne o vkládání")
    void theSituation35VariantOnlyTalksAboutFixing() {
        SignatureSummary sig = sig()
                .timestamps(List.of(new TimestampEntry(STAMPED, true, "TSA", LATER, "doc", true, true)))
                .verifiableUntil(LATER)
                .certNotAfter(PAST)
                .unprovenRevocation(NOW.plus(200, ChronoUnit.DAYS))
                .situations(35)
                .build();

        String text = textWithSource(sig, FUTURE);
        assertTrue(text.contains("Nejde přitom jen o prodloužení ověřitelnosti"), text);
        assertFalse(text.contains("vloží revokační informace dostupné dnes"),
                "Není odkud je vzít — první blok o témž podpisu říká pravý opak: " + text);
        assertTrue(text.contains("Revokační informace, které v dokumentu už vloženy jsou,"
                        + " přestanou být po expiraci svého vydavatele využitelné. Úkon je"
                        + " zafixuje"), text);
    }

    /** Text všech bloků prvku se zdrojem razítek, jehož certifikát končí v {@code tsaUntil}. */
    private static String textWithSource(SignatureSummary sig, Instant tsaUntil) {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit nenaběhl");
        return onFxThread(() -> {
            VBox blocks = ContinuityElementBlocks.build(sig, "TOTAL_PASSED", STRICT, false,
                    new ContinuityElementBlocks.Gates(true, false, false, true), null, true,
                    ContinuityElementBlocks.Source.named("ACCV", java.util.Date.from(tsaUntil)),
                    null);
            StringBuilder sb = new StringBuilder();
            collectText(blocks, sb);
            return sb.toString();
        });
    }

    private static <T> T onFxThread(Supplier<T> work) {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
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

    // ── Stavitel ─────────────────────────────────────────────────────────────────────────────

}
