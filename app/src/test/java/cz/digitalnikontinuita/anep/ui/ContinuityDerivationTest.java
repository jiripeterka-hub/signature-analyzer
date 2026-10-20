package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Run;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Odvození lhůty nesmí tvrdit víc, než plyne z faktů — a to u každé varianty zvlášť.
 *
 * <p>Vzniklo 8. 9. 2026 po nálezu uživatele: u dokumentu s vloženou odpovědí OCSP text tvrdil,
 * že uznatelnost té odpovědi končí 15. 9. 2029, ačkoli certifikát jejího vydavatele skončí
 * 23. 3. 2027 a to pozdější datum drží úplně jiná cesta — možnost stáhnout čerstvý údaj. Táž
 * vada se pak našla ještě třikrát jinde. Všechny mají společné to, že si <b>vlastní věta</b>
 * přivlastnila datum spočítané odjinud, takže se hlídá právě to.
 */
class ContinuityDerivationTest {

    private static final Instant NOW = SigFixture.NOW;
    private static final Instant FUTURE = SigFixture.FUTURE;
    private static final Instant PAST = SigFixture.PAST;
    private static final Instant LATER = SigFixture.LATER;
    private static final Run STRICT = new Run(true, List.of(), null);

    private static boolean toolkitUp;

    @BeforeAll
    static void startToolkit() {
        toolkitUp = FxToolkit.available();
    }

    @Test
    @DisplayName("Vložený údaj bez důkazu existence: datum patří tomu, kdo ho drží")
    void embeddedRevocationDoesNotClaimSomeoneElsesDate() {
        // Respondér do FUTURE, podpisový certifikát do LATER — model vezme to pozdější, protože
        // dokud certifikát platí, dá se stáhnout čerstvý údaj. Text tedy nesmí tvrdit, že do
        // LATER je uznatelný ten vložený.
        String text = blockText(SigFixture.sig()
                .certNotAfter(LATER)
                .unprovenRevocation(FUTURE)
                .build());

        assertTrue(text.contains("lhůtu určuje"),
                "Který článek lhůtu drží, má říct model: " + text);
        assertFalse(text.contains("uznat jej lze jen do konce platnosti certifikátu jeho"
                        + " vydavatele, tedy do"),
                "Vlastní věta o vydavateli si brala datum z jiné cesty: " + text);
    }

    @Test
    @DisplayName("Kvalifikované razítko: datum je spodní mez, ne konec")
    void aQualifiedTimestampGivesALowerBound() {
        String text = blockText(SigFixture.sig()
                .certNotAfter(PAST)
                .verifiableUntil(FUTURE)
                .timestamps(List.of(new TimestampEntry(PAST, true, "TSA", FUTURE, "sig", true)))
                .build());

        assertTrue(text.contains("nejméně do uvedeného data"),
                "Nadpis říká „Nejméně do“, takže věta nesmí tvrdit „právě do“: " + text);
        assertFalse(text.contains("právě do uvedeného data"), text);
    }

    @Test
    @DisplayName("Razítko: neříká se, že jeho existenci prokazuje razítko")
    void aLoneTimestampIsItsOwnProof() {
        String text = blockText(SigFixture.sig()
                .format("TimeStampToken")
                .qualified()
                .certNotAfter(FUTURE)
                .build());

        assertTrue(text.contains("Razítko je svým vlastním důkazem existence"), text);
        assertFalse(text.contains("Dřívější existenci časového razítka prokazuje časové razítko"),
                "Tautologie: " + text);
    }

    @Test
    @DisplayName("U samostatného razítka se nemluví o podpisovém certifikátu")
    void aLoneTimestampHasNoSigningCertificate() {
        SignatureSummary sig = SigFixture.sig()
                .format("TimeStampToken")
                .qualified()
                .certNotAfter(FUTURE)
                .build();
        String facts = String.join(" ", ContinuityFacts.paragraphs(sig));

        assertFalse(facts.contains("Podpis je založen"),
                "Samostatné razítko podpisový certifikát nemá: " + facts);
        assertTrue(facts.contains("Časové razítko je založeno na certifikátu"),
                "Konec platnosti certifikátu razítka model zná, takže má stát ve faktech: "
                + facts);
        assertFalse(facts.contains(", ale bez vložených"),
                "Dvě „bez“ v jedné větě se nedají přečíst: " + facts);
    }

    @Test
    @DisplayName("Přerušená řada razítek: okruh CzechPOINTu neověří, a text to říká")
    void aBrokenChainStopsTheToleranceCircle() {
        // Podle zafixovaného dokumentu: pečeť s certifikátem po expiraci, dvě platná
        // kvalifikovaná razítka, ale druhé vzniklo až po expiraci certifikátu prvního (chybí
        // situace 31). Certifikát toho druhého platí dál — a právě proto blok tvrdil, že
        // validátory se striktním hodnocením razítek platnost ověří. Neověří.
        // Časy podle skutečného dokumentu: pečeť z doby platnosti certifikátu, první razítko
        // ještě za jeho platnosti, druhé až po expiraci certifikátu toho prvního.
        Instant sealed = PAST.minus(30, java.time.temporal.ChronoUnit.DAYS);
        Instant firstStampUntil = PAST.plus(60, java.time.temporal.ChronoUnit.DAYS);
        SignatureSummary sig = SigFixture.sig()
                .certNotAfter(PAST)
                .verifiableUntil(FUTURE)
                .timestamps(List.of(
                        new TimestampEntry(sealed, true, "TSU 1", firstStampUntil, "sig", true),
                        new TimestampEntry(NOW, true, "TSU 2", FUTURE, "doc", true)))
                .situations(32, 33, 34)
                .build();

        ContinuityHorizon.Horizon tolerant = ContinuityHorizon.tolerant(sig, "TOTAL_PASSED");
        assertTrue(tolerant.kind() == ContinuityHorizon.Kind.LOST,
                "Řada s mezerou důkaz existence nedá, takže tam není co datovat: " + tolerant);

        String text = blockText(sig);
        assertTrue(text.contains("Řada časových razítek je přerušena"),
                "Důvod musí být řečen, ne jen datum vynecháno: " + text);
        assertFalse(text.contains("proto mohou ověřit až do konce"),
                "Slib ověření si odporoval s posledním blokem téže karty: " + text);
    }

    @Test
    @DisplayName("Souvislá řada razítek zůstává tolerantním okruhem ověřitelná")
    void anUnbrokenChainKeepsTheTolerantHorizon() {
        Instant sealed = PAST.minus(30, java.time.temporal.ChronoUnit.DAYS);
        SignatureSummary sig = SigFixture.sig()
                .certNotAfter(PAST)
                .verifiableUntil(FUTURE)
                .timestamps(List.of(
                        new TimestampEntry(sealed, true, "TSU 1", FUTURE, "sig", true)))
                .situations(31, 32, 33, 34)
                .build();

        ContinuityHorizon.Horizon tolerant = ContinuityHorizon.tolerant(sig, "TOTAL_PASSED");
        assertTrue(tolerant.kind() == ContinuityHorizon.Kind.UNTIL,
                "Souvislá řada se měnit neměla: " + tolerant);
        assertFalse(ContinuityHorizon.brokenChainStopsTolerant(sig), "Řada je souvislá.");
    }

    @Test
    @DisplayName("Vložená odpověď OCSP se ve faktech nejmenuje CRL")
    void embeddedOcspIsNotCalledACrl() {
        // „priklad6_17.pdf": vložená odpověď OCSP od vydavatele s prošlým certifikátem. Využitý
        // údaj je proto prázdný a fakta brala druh odtud — výchozí hodnota z toho udělala „CRL
        // seznam", ačkoli Analýza o témže dokumentu správně mluvila o OCSP.
        SignatureSummary sig = SigFixture.sig()
                .certNotAfter(PAST)
                .timestamps(List.of(
                        new TimestampEntry(PAST, true, "TSU", FUTURE, "sig", true)))
                .embeddedOcspUnused()
                .build();
        String facts = String.join(" ", ContinuityFacts.paragraphs(sig));

        assertTrue(facts.contains("v podobě odpovědi OCSP"), facts);
        assertFalse(facts.contains("CRL seznam"), "Druh se má brát z vloženého údaje: " + facts);
        assertTrue(facts.contains("Odpověď OCSP vložená v dokumentu byla vydána"),
                "Vložený údaj je fakt o souboru, i když ho ověření nepoužilo: " + facts);
        assertFalse(facts.contains("byla využita odpověď OCSP"),
                "O nepoužitelném údaji se nesmí tvrdit, že se využil: " + facts);
    }

    @Test
    @DisplayName("Počet razítek mění pád celé skupiny, přívlastek jde s ním")
    void theTimestampCountGovernsTheCase() {
        // Do čtyř se počítané jméno řídí předložkou (7. pád), od pěti stojí ve druhém. Přívlastek
        // zůstával v druhém pádě vždy, takže vycházelo „s 2 (kvalifikovaných elektronických)
        // časovými razítky" — našel uživatel na „LT kvalifikovany 2020 10 15 s razitkem
        // Signer.pdf".
        String two = String.join(" ", ContinuityFacts.paragraphs(stamps(2)));
        assertTrue(two.contains("s 2 (kvalifikovanými elektronickými) časovými razítky"), two);
        assertFalse(two.contains("(kvalifikovaných elektronických) časovými"), two);

        String five = String.join(" ", ContinuityFacts.paragraphs(stamps(5)));
        assertTrue(five.contains("s 5 (kvalifikovaných elektronických) časových razítek"), five);
        // „Všemi platnými" se řídí 7. pádem, takže u pěti a víc nesedí.
        assertFalse(five.contains("všemi platnými"), five);
        assertTrue(five.contains("z nichž všechna jsou platná"), five);
    }

    @Test
    @DisplayName("O údaji staženém až teď se netvrdí, že zjistil stav k rozhodnému okamžiku")
    void aResponseFetchedTodayIsNotPresentedAsTheBasis() {
        // Dokument s odpovědí OCSP v podepsaném atributu ADBE. DSS ji vybralo
        // jako poslední přípustnou, AnEP ji odmítl, a výběr propadl na odpověď staženou v tentýž
        // den, kdy ověřování běželo. Fakta pak tvrdila, že stav revokace k roku 2024 zjistila
        // odpověď z roku 2026 — nález uživatele z 12. 9. 2026.
        SignatureSummary sig = SigFixture.sig()
                .certNotAfter(PAST)
                .bestSignatureTime(SigFixture.STAMPED)
                .timestamps(List.of(
                        new TimestampEntry(SigFixture.STAMPED, true, "TSU", FUTURE, "doc", true)))
                .embeddedRejectedOnlineUsed()
                .build();
        String facts = String.join(" ", ContinuityFacts.paragraphs(sig));

        assertFalse(facts.contains("byla využita odpověď OCSP"),
                "Závěr na stažené odpovědi nestojí — stav zůstal neznámý: " + facts);
        assertFalse(facts.contains("Pro zjištění stavu revokace"),
                "Nic se nezjistilo, takže věta nesmí začít „pro zjištění“: " + facts);
        assertTrue(facts.contains("se prokázat nepodařilo"),
                "Výsledek má zaznít jako první: " + facts);
        assertTrue(facts.contains("Odpověď OCSP vložená v dokumentu byla vydána"),
                "Vlastní materiál dokumentu je fakt, který čtenář v souboru najde: " + facts);
        assertTrue(facts.contains("stáhla on-line odpověď OCSP"),
                "Stažený údaj se nezamlčuje, jen se nevydává za podklad závěru: " + facts);
    }

    @Test
    @DisplayName("Situace 35: chybí dosah posuzování, ne důkaz existence")
    void situation35BlamesTheProcessNotAMissingProof() {
        // Situace 35 se ohlásí právě a jen tehdy, když existující uznané razítko kryje certifikát
        // vydavatele vložené informace ještě za jeho platnosti — důkaz tedy dokument nese už dnes
        // a tvrdit „chybí důkaz, ten by vytvořilo archivní razítko" je nepravda. Táž oprava, jakou
        // 11. 9. 2026 dostal důvod 30; na kartu se tehdy nepropsala.
        String text = lostBlockText(SigFixture.sig()
                .certNotAfter(PAST)
                .indication("INDETERMINATE")
                .timestamps(List.of(
                        new TimestampEntry(SigFixture.STAMPED, true, "TSU", FUTURE, "doc", true)))
                .situations(35)
                .build());

        assertFalse(text.contains("chybí důkaz"),
                "Důkaz dokument nese; S35 bez něj vůbec nenastane: " + text);
        assertFalse(text.contains("Ten by vytvořilo archivní časové razítko"),
                "Razítko vydané dnes dokládá dnešek, ne minulost: " + text);
        assertTrue(text.contains("na ně posuzování podle standardů nedosáhne"),
                "Vadí dosah posuzování, a to má věta říct: " + text);
        assertTrue(text.contains("ten důkaz dokument už nese"),
                "Že důkaz existuje, je to podstatné zjištění: " + text);
    }

    @Test
    @DisplayName("PKCS #7 se píše s křížkem a bez úrovně; razítka vazbou „je opatřen“")
    void pkcs7IsNamedWithoutALevel() {
        // Dvě věci naráz, obě pokyn uživatele z 12. 9. 2026. Jméno formátu: DSS hlásí PKCS7-T,
        // ale úrovně standardy pro tenhle formát nedefinují, takže je psát do jeho jména znamená
        // tvrdit, že existují — a to zrovna ve větě, která říká opak. A vazba: za tečkou se
        // předložkou pokračovat nedá, takže z „Nese " + „s jedním platným …" vycházelo
        // „Nese s jedním platným (kvalifikovaným elektronickým) časovým razítkem".
        String one = String.join(" ", ContinuityFacts.paragraphs(SigFixture.sig()
                .format("PKCS7-T")
                .certNotAfter(PAST)
                .timestamps(List.of(
                        new TimestampEntry(SigFixture.STAMPED, true, "TSU", FUTURE, "sig", true)))
                .build()));
        assertTrue(one.contains("ve formátu PKCS #7, pro který standardy úrovně nedefinují."), one);
        assertFalse(one.contains("PKCS7"), "Formát se jmenuje PKCS #7: " + one);
        assertFalse(one.contains("PKCS #7-T"), "Úroveň do jména formátu nepatří: " + one);
        assertTrue(one.contains(
                "Je opatřen jedním platným (kvalifikovaným elektronickým) časovým razítkem"), one);
        assertFalse(one.contains("Nese "), "Vazba „Nese s …“ je rozbitá: " + one);

        String two = String.join(" ", ContinuityFacts.paragraphs(SigFixture.sig()
                .format("PKCS7-T")
                .certNotAfter(PAST)
                .timestamps(List.of(
                        new TimestampEntry(SigFixture.STAMPED, true, "TSU 1", FUTURE, "sig", true),
                        new TimestampEntry(NOW, true, "TSU 2", FUTURE, "doc", true)))
                .build()));
        assertTrue(two.contains("Je opatřen 2 (kvalifikovanými elektronickými) časovými razítky,"
                + " z nichž všechna jsou platná"), two);

        // Zápor se s tou vazbou nesnese, a stejně tak se za něj nedá připojit „, ale bez
        // vložených revokačních informací" — druhá vada se pak řekne vlastní větou.
        String none = String.join(" ", ContinuityFacts.paragraphs(SigFixture.sig()
                .format("PKCS7-B")
                .certNotAfter(PAST)
                .build()));
        assertTrue(none.contains(
                "Není opatřen žádným časovým razítkem. Revokační informace vloženy nejsou."), none);

        // Týž název i v řádku „Formát podpisu:" Rozšířeného přehledu.
        assertEquals("PKCS #7 (nejde o referenční formát)",
                ExtendedView.formatSignatureLabel("PKCS7-LTA"));
        assertEquals("PAdES Baseline-LTA (jde o referenční formát)",
                ExtendedView.formatSignatureLabel("PAdES-BASELINE-LTA"));
    }

    @Test
    @DisplayName("Bez definovaných úrovní řádek „Úroveň:“ o úrovních nemluví")
    void theLevelRowDropsLevelNamesWhereNoneAreDefined() {
        // Pokyn uživatele z 12. 9. 2026: u PKCS #7 si řádek „Úroveň:“ odporoval s blokem o kus
        // výš, který říká „pro který standardy úrovně nedefinují“. Text tedy popisuje obsah
        // a jména úrovní — na začátku i v závorkách uvnitř — z něj mizí.
        SignatureSummary sig = SigFixture.sig().format("PKCS7-T").build();
        for (String level : List.of("B", "T", "LT", "LTA")) {
            String plain = AdvisorView.levelText(level, sig, false);
            assertFalse(plain.contains("úrovn"), level + ": " + plain);
            assertFalse(plain.contains("(B)"), level + ": " + plain);
            assertFalse(plain.contains("(T)"), level + ": " + plain);
            assertFalse(plain.contains("(LT)"), level + ": " + plain);
            assertFalse(plain.startsWith(level + " ("), "Jméno úrovně nemá uvozovat: " + plain);
            assertTrue(Character.isUpperCase(plain.charAt(0)),
                    "Po vypuštění jména úrovně věta začíná velkým písmenem: " + plain);
        }
        assertTrue(AdvisorView.levelText("T", sig, false).startsWith(
                "Kromě samotného podpisu obsahuje navíc kvalifikované elektronické časové razítko"),
                AdvisorView.levelText("T", sig, false));

        // Referenčním formátům se nic nemění — tam jméno úrovně o něčem vypovídá.
        assertTrue(AdvisorView.levelText("T", sig, true).startsWith("T (Time) — kromě"),
                AdvisorView.levelText("T", sig, true));
        assertFalse(AdvisorView.levelsDefined("PKCS7-LTA"), "PKCS #7 úrovně definované nemá.");
        assertTrue(AdvisorView.levelsDefined("PAdES-BASELINE-LTA"));
    }

    @Test
    @DisplayName("Formáty mimo rodiny AdES řádek „Druh formátu:“ nedostanou")
    void formatsOutsideTheAdesFamiliesGetNoFamilyRow() {
        // PKCS #7 je konstrukce starší než CAdES a PDF-NOT-ETSI je podpis v PDF, který PAdES
        // nesplňuje — ani jeden do žádné z rodin nepatří. Řádek jim přitom hlásil „PAdES (PDF
        // Advanced Electronic Signatures)", protože se rozhoduje podle toho, v čem podpis leží.
        // Pokyn uživatele z 12. 9. 2026.
        assertFalse(AdvisorView.adesFamilyApplies("PKCS7-T"), "PKCS #7 do rodin AdES nepatří.");
        assertFalse(AdvisorView.adesFamilyApplies("PDF-NOT-ETSI"), "PDF-NOT-ETSI PAdES nesplňuje.");
        assertTrue(AdvisorView.adesFamilyApplies("PAdES-BASELINE-LTA"));
        assertTrue(AdvisorView.adesFamilyApplies("CAdES-BASELINE-T"));
    }

    /**
     * Starší profily PAdES do stupnice B/T/LT/LTA nepatří a dosadit jim písmeno znamená tvrdit
     * o nich něco, co neplatí.
     *
     * <p>Nález uživatele z 20. 9. 2026: podpis hlášený DSS jako {@code PAdES-BES} nesl podpisové
     * i dokumentové časové razítko a k tomu vloženou odpověď OCSP, a řádek „Úroveň“ o něm psal
     * „obsahuje jen samotný podpis a certifikát podepsané osoby. Není v něm žádný důkaz o čase
     * ani uložená informace o tom, zda certifikát nebyl zneplatněn.“
     *
     * <p>Ve výčtu {@code SignatureLevel} knihovny DSS má nebaselinová rodina PAdES jen BES, EPES
     * a LTV — obdobu úrovně T v ní nenajdete, takže podpis s razítkem v ní zůstane BES. CAdES
     * a XAdES {@code -T} mají, a tam tedy BES „bez razítka“ opravdu znamená.
     */
    @Test
    @DisplayName("PAdES-BES písmeno úrovně nedostane, CAdES-BES ano")
    void theOldPadesProfilesAreNotOnTheBaselineScale() {
        assertEquals(null, AdvisorView.levelKey("PAdES-BES"),
                "Stupnice B/T/LT/LTA se starších profilů PAdES netýká");
        assertEquals(null, AdvisorView.levelKey("PAdES-EPES"));
        assertEquals(null, AdvisorView.levelKey("PAdES-LTV"));

        assertEquals("B", AdvisorView.levelKey("CAdES-BES"),
                "V CAdES úroveň T existuje, takže BES opravdu znamená „bez razítka“");
        assertEquals("B", AdvisorView.levelKey("XAdES-BES"));
        assertEquals("B", AdvisorView.levelKey("PAdES-BASELINE-B"), "Baseline se nemění");
        assertEquals("LTA", AdvisorView.levelKey("PAdES-BASELINE-LTA"));
    }

    @Test
    @DisplayName("Místo písmene stojí u nich vysvětlení, a to i u LTV")
    void theOldPadesProfilesGetAnExplanationInstead() {
        String bes = AdvisorView.oldPadesLevelText("PAdES-BES");
        assertTrue(bes.startsWith("BES (Basic Electronic Signature)"), bes);
        assertTrue(bes.contains("stupnice B / T / LT / LTA se ho netýká"), bes);
        assertTrue(bes.contains("neplyne, že podpis nemá časové razítko"),
                "Právě tenhle nevyslovený krok svedl čtenáře na scestí: " + bes);

        // Do 20. 9. 2026 neměl řádek „Úroveň“ vůbec; pokyn uživatele byl nechat ho s vysvětlením.
        String ltv = AdvisorView.oldPadesLevelText("PAdES-LTV");
        assertTrue(ltv.startsWith("LTV (Long Term Validation)"), ltv);
        assertTrue(ltv.contains("vložená validační data"), ltv);

        assertEquals(null, AdvisorView.oldPadesLevelText("PAdES-BASELINE-B"),
                "Baselinový profil na stupnici patří");
        assertEquals(null, AdvisorView.oldPadesLevelText("CAdES-BES"));
        assertEquals(null, AdvisorView.oldPadesLevelText("PDF-NOT-ETSI"));
    }

    /**
     * Situace 9 mluví o síti, ne o tom, co dokument nese.
     *
     * <p>Nález uživatele z 20. 9. 2026 (týž dokument): odstavec „Revokační informace“ hlásil
     * „nepodařilo se získat využitelné informace … (vložené revokační informace nelze využít)“,
     * ačkoli stav revokace zjistila vložená odpověď OCSP — týž běh o ní zároveň psal, že vznikla
     * deset minut před podpisem a okamžik podepsání pokrývá, a Detailní rozbor ji uváděl se
     * stavem „V pořádku“. Tři místa, dvě z nich pravdivá.
     */
    @Test
    @DisplayName("Neúspěšné stažení revokace nepřebije údaj, který stav zjistil")
    void afailedOnlineFetchDoesNotOverrideAnEstablishedState() {
        SignatureSummary withEmbedded = SigFixture.sig()
                .situations(9).revocationFrom("document").build();
        assertEquals(AdvisorView.RevocationItem.AS_IS,
                AdvisorView.revocationItem(withEmbedded, withEmbedded.getValidationCourse(),
                        false, false),
                "Vložený údaj stav zjistil, takže ho odstavec má popsat, ne popřít");

        // A kde řetěz nevede ke kotvě, nemluví odstavec ani o využití: ověřování se k údaji
        // nedostalo, protože revokace se zjišťuje až uvnitř ověřeného řetězu (EN 319 102-1,
        // čl. 5.2.6). Odpověď podepsanou týmž neznámým vydavatelem není čím ověřit — otázka
        // uživatele z 20. 9. 2026.
        SignatureSummary noAnchor = SigFixture.sig()
                .situations(9).subIndication("NO_CERTIFICATE_CHAIN_FOUND")
                .revocationFrom("document").build();
        assertEquals(AdvisorView.RevocationItem.NOT_REACHED,
                AdvisorView.revocationItem(noAnchor, noAnchor.getValidationCourse(),
                        false, false));

        SignatureSummary nothing = SigFixture.sig().situations(9).build();
        assertEquals(AdvisorView.RevocationItem.UNUSABLE,
                AdvisorView.revocationItem(nothing, nothing.getValidationCourse(), false, false),
                "Bez zjištěného stavu zůstává situace 9 důvodem k té větě");

        // Ostatní situace se nemění: tam je „nelze využít" věcné zjištění, ne domněnka ze sítě.
        SignatureSummary expiredIssuer = SigFixture.sig()
                .situations(5).revocationFrom("document").build();
        assertEquals(AdvisorView.RevocationItem.UNUSABLE,
                AdvisorView.revocationItem(expiredIssuer, expiredIssuer.getValidationCourse(),
                        false, false));
    }

    /**
     * Kde ověřování uvázlo na kotvě důvěry, nemá závěr mluvit o revokaci. Ptá se proto na dílčí
     * indikaci — situace 12 sama o sobě nestačí, její znění říká, že „může být přímou příčinou
     * nebo důsledkem jiného selhání“.
     */
    @Test
    @DisplayName("Nesestavený řetězec se pozná z dílčí indikace, ne ze situace")
    void anUnbuiltChainIsReadFromTheSubIndication() {
        assertTrue(AdvisorView.chainNotBuilt(
                SigFixture.sig().subIndication("NO_CERTIFICATE_CHAIN_FOUND").build()));
        assertTrue(AdvisorView.chainNotBuilt(
                SigFixture.sig().subIndication("NO_CERTIFICATE_CHAIN_FOUND_NO_POE").build()));
        assertFalse(AdvisorView.chainNotBuilt(
                SigFixture.sig().subIndication("OUT_OF_BOUNDS_NO_POE").build()));
        assertFalse(AdvisorView.chainNotBuilt(SigFixture.sig().situations(12).build()),
                "Samotná situace 12 nestačí — bývá i důsledkem jiného selhání");
    }

    /** Texty bloků u prvku, jehož platnost zůstala neznámá. */
    private static String lostBlockText(SignatureSummary sig) {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit nenaběhl");
        return onFxThread(() -> {
            VBox blocks = ContinuityElementBlocks.build(sig, "INDETERMINATE", STRICT, false,
                    new ContinuityElementBlocks.Gates(true, false, false, true),
                    null, true, ContinuityElementBlocks.Source.named("I.CA", null), null);
            StringBuilder sb = new StringBuilder();
            collect(blocks, sb);
            return sb.toString();
        });
    }

    /** Podpis s daným počtem platných kvalifikovaných razítek v souvislé řadě. */
    private static SignatureSummary stamps(int count) {
        List<TimestampEntry> list = new java.util.ArrayList<>();
        Instant when = PAST.minus(60, java.time.temporal.ChronoUnit.DAYS);
        for (int i = 0; i < count; i++) {
            list.add(new TimestampEntry(when.plus(i, java.time.temporal.ChronoUnit.DAYS),
                    true, "TSU " + (i + 1), FUTURE, i == 0 ? "sig" : "doc", true));
        }
        return SigFixture.sig()
                .format("PAdES-BASELINE-LTA")
                .certNotAfter(PAST)
                .verifiableUntil(FUTURE)
                .timestamps(list)
                .build();
    }

    /** Texty všech bloků u prvku slepené do jednoho řetězce. */
    private static String blockText(SignatureSummary sig) {
        Assumptions.assumeTrue(toolkitUp, "JavaFX toolkit nenaběhl");
        return onFxThread(() -> {
            VBox blocks = ContinuityElementBlocks.build(sig, "TOTAL_PASSED", STRICT, false,
                    new ContinuityElementBlocks.Gates(true, false, false, true),
                    // Dosah si blok od 8. 9. 2026 nedopočítává, přebírá ho; tady zajímají
                    // texty úkonu, takže se předává „dosáhne".
                    null, true, ContinuityElementBlocks.Source.named("I.CA", null), null);
            StringBuilder sb = new StringBuilder();
            collect(blocks, sb);
            return sb.toString();
        });
    }

    private static void collect(javafx.scene.Node n, StringBuilder sb) {
        if (n instanceof javafx.scene.control.Labeled l && l.getText() != null) {
            sb.append(l.getText()).append('\n');
        }
        if (n instanceof javafx.scene.text.TextFlow tf) {
            for (javafx.scene.Node c : tf.getChildrenUnmodifiable()) {
                if (c instanceof javafx.scene.text.Text t) sb.append(t.getText());
                if (c instanceof javafx.scene.control.Labeled l && l.getText() != null) {
                    sb.append(l.getText());
                }
            }
            sb.append('\n');
            return;
        }
        if (n instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node c : p.getChildrenUnmodifiable()) collect(c, sb);
        }
    }

    private static <T> T onFxThread(Supplier<T> work) {
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
            Assumptions.assumeTrue(done.await(30, TimeUnit.SECONDS), "FX vlákno neodpovědělo");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Assumptions.abort("přerušeno");
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        return out.get();
    }
}
