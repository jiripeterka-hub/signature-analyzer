package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.jaxb.XmlByteRange;
import eu.europa.esig.dss.diagnostic.jaxb.XmlDiagnosticData;
import eu.europa.esig.dss.diagnostic.jaxb.XmlModification;
import eu.europa.esig.dss.diagnostic.jaxb.XmlModificationDetection;
import eu.europa.esig.dss.diagnostic.jaxb.XmlPDFRevision;
import eu.europa.esig.dss.diagnostic.jaxb.XmlPDFSignatureDictionary;
import eu.europa.esig.dss.diagnostic.jaxb.XmlSignature;
import eu.europa.esig.dss.diagnostic.jaxb.XmlTimestamp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pravidlo „jen tolik položek, kolik je různých verzí“.
 *
 * <p>Rozhodnutí uživatele z 18. 9. 2026 a nejkřehčí část třetí etapy: slévá se podle toho, co
 * DSS hlásí jako rozdíl proti dnešní podobě, a nedopatřením se dá stejně snadno slít všechno
 * jako nic. Zkouší se nad sestaveným {@code DiagnosticData}, protože sbírka {@code D:\tmp}
 * dokument s viditelně změněným obsahem nemá vůbec.
 */
class DocumentVersionsTest {

    private static final long FILE = 100_000;

    @Test
    @DisplayName("Bez rozdílů zbude jediná položka — dnešní podoba")
    void nothingToChooseFrom() {
        List<DocumentVersions.Version> versions = DocumentVersions.of(
                data(signature(40_000, null), timestamp(FILE, null)), FILE);

        assertEquals(1, versions.size(), "Není z čeho vybírat, přepínač se nemá stavět");
        assertTrue(versions.get(0).current());
        assertEquals("Dnešní podoba souboru", versions.get(0).label());
    }

    @Test
    @DisplayName("Revize s rozdílem dostane vlastní položku a dnešní podoba je poslední")
    void aDifferingRevisionGetsItsOwnItem() {
        List<DocumentVersions.Version> versions = DocumentVersions.of(
                data(signature(40_000, pages(1))), FILE);

        assertEquals(2, versions.size());
        assertEquals(40_000, versions.get(0).endOffset(), "Podoba je prefix po konec revize");
        assertEquals(List.of(1), versions.get(0).changedPages());
        assertTrue(versions.get(0).label().startsWith("Jak dokument vypadal, když jej podepsal"),
                versions.get(0).label());
        assertTrue(versions.get(1).current(), "Dnešní podoba patří na konec");
    }

    /**
     * Právě kvůli tomuhle pravidlo vzniklo: uchování přidá validační data a razítko, obsah se
     * nezmění, a osm krytých revizí by dalo osm položek s obsahem k nerozeznání.
     */
    @Test
    @DisplayName("Revize, které se od dneška liší týmž, splynou v jednu")
    void revisionsDifferingTheSameWayMerge() {
        List<DocumentVersions.Version> versions = DocumentVersions.of(
                data(signature(30_000, pages(3)),
                     signature(50_000, pages(3)),
                     timestamp(70_000, pages(3))), FILE);

        assertEquals(2, versions.size(), "Tři prvky, jedna podoba navíc k dnešní");
        assertEquals(30_000, versions.get(0).endOffset(),
                "Skupinu má pojmenovat a vymezit ten nejstarší prvek");
    }

    @Test
    @DisplayName("Liší-li se rozdíly, položky se neslévají")
    void differentDifferencesStayApart() {
        List<DocumentVersions.Version> versions = DocumentVersions.of(
                data(signature(30_000, pages(1, 2)), signature(60_000, pages(2))), FILE);

        assertEquals(3, versions.size());
        assertEquals(List.of(1, 2), versions.get(0).changedPages());
        assertEquals(List.of(2), versions.get(1).changedPages());
    }

    /**
     * Překryv anotací není rozdíl mezi revizemi — změřeno na 15 dokumentech, u kterých se hlásí,
     * ačkoli za jejich posledním kryjícím prvkem není ani bajt. Kdyby se počítal, dostal by
     * vlastní položku každý dokument s podpisovou vizualizací.
     */
    @Test
    @DisplayName("Překryv anotací vlastní podobu netvoří")
    void annotationOverlapDoesNotMakeAVersion() {
        XmlModificationDetection md = new XmlModificationDetection();
        md.getAnnotationOverlap().add(page(1));

        assertEquals(1, DocumentVersions.of(data(signature(40_000, md)), FILE).size());
    }

    @Test
    @DisplayName("Prvky se řadí podle toho, kam sahají, ne podle pořadí v protokolu")
    void elementsAreOrderedByReach() {
        // Razítko kryje víc než podpis, a přijde v seznamu DSS až po něm; obě podoby se liší
        // jinak, takže se nesmějí slít ani prohodit.
        List<DocumentVersions.Version> versions = DocumentVersions.of(
                data(signature(60_000, pages(2)), timestamp(30_000, pages(5))), FILE);

        assertEquals(3, versions.size());
        assertEquals(30_000, versions.get(0).endOffset(), "Nejstarší podoba patří dopředu");
        assertEquals(60_000, versions.get(1).endOffset());
    }

    // ------------------------------------------------------------ prvek, který sám kreslí

    /**
     * Nález uživatele z 22. 9. 2026: čtyři podpisy, každý s vlastní vizualizací, a nabídka žádná.
     * DSS porovnává stránky se zakrytými podpisovými poli, takže přidaná vizualizace pro ně
     * rozdíl není — a přitom je to právě ta otázka, kvůli které se podoby nabízejí.
     *
     * <p><b>Opraveno 23. 9. 2026</b> (viz {@link #aSoleVisibleSignatureWithNoLaterDrawerMerges}):
     * dokud po prvku nekreslí žádný pozdější, maska DSS nic neschovává a prázdný výsledek platí.
     * Bez dalšího prvku za sebou se proto podpis slije s dnešní podobou — přesně to, co dřív
     * tenhle test ověřoval jako opak, byl týž tvar jako nahlášená chyba.
     */
    @Test
    @DisplayName("Podpis s viditelnou vizualizací, po kterém nic dalšího nekreslí, se slije s dneškem")
    void aVisibleSignatureWithNothingLaterMerges() {
        List<DocumentVersions.Version> versions = DocumentVersions.of(
                data(signature(40_000, null)), FILE, Set.of("SIG-40000"));

        assertEquals(1, versions.size(), versions.toString());
        assertTrue(versions.get(0).current());
    }

    @Test
    @DisplayName("Viditelný podpis dostane vlastní položku, kreslí-li po něm ještě další")
    void everyVisibleSignatureGetsItsOwnExceptTheLast() {
        List<DocumentVersions.Version> versions = DocumentVersions.of(
                data(signature(20_000, null), signature(40_000, null), signature(60_000, null)),
                FILE, Set.of("SIG-20000", "SIG-40000", "SIG-60000"));

        // Poslední podpis už nemá, kdo by po něm kreslil — jeho vlastní pole se do dneška
        // nezmění, a prázdný výsledek DSS mu proto platí stejně jako neviditelnému prvku.
        assertEquals(3, versions.size(), "Dvě podoby před podpisy, které mají pokračování, a dnešní");
        assertEquals(20_000, versions.get(0).endOffset());
        assertEquals(40_000, versions.get(1).endOffset());
        assertTrue(versions.get(2).current());
    }

    /**
     * Nález uživatele z 23. 9. 2026 nad dokumentem uchovaným z B-T na B-LTA: jediný podpis,
     * po kterém následuje jen archivní razítko beze změny obsahu. Náhled nabízel dvě podoby
     * k nerozeznání, ačkoli po podpisu nic dalšího nekreslí.
     */
    @Test
    @DisplayName("Jediný viditelný podpis, po kterém následuje jen neviditelné razítko, se slije s dneškem")
    void aSoleVisibleSignatureWithNoLaterDrawerMerges() {
        List<DocumentVersions.Version> versions = DocumentVersions.of(
                data(signature(40_000, null), timestamp(FILE, null)), FILE, Set.of("SIG-40000"));

        assertEquals(1, versions.size(), versions.toString());
        assertTrue(versions.get(0).current());
    }

    /**
     * Důvod původního pravidla zůstává v platnosti: co nekreslí nic, se slévá dál. Uchování
     * přidá validační data a razítko, obsah se nezmění o jediný pixel. A protože po tom
     * jediném viditelném podpisu už nekreslí nic dalšího, slije se s dneškem i on sám
     * (viz {@link #aSoleVisibleSignatureWithNoLaterDrawerMerges}).
     */
    @Test
    @DisplayName("Neviditelný podpis ani razítko vlastní podobu netvoří")
    void invisibleElementsStillMerge() {
        List<DocumentVersions.Version> versions = DocumentVersions.of(
                data(signature(20_000, null), signature(40_000, null), timestamp(60_000, null)),
                FILE, Set.of("SIG-20000"));

        assertEquals(1, versions.size(), "Nic po tom viditelném podpisu dál nekreslí");
    }

    /**
     * Kreslí-li i ten druhý podpis, maska DSS už něco schovává — vlastní podobu proto dostane
     * ten první i tady.
     */
    @Test
    @DisplayName("Neviditelný prvek mezi dvěma viditelnými podpisy skupinu nezachrání")
    void aDrawingSignatureLaterKeepsTheEarlierOneSeparate() {
        List<DocumentVersions.Version> versions = DocumentVersions.of(
                data(signature(20_000, null), timestamp(40_000, null), signature(60_000, null)),
                FILE, Set.of("SIG-20000", "SIG-60000"));

        assertEquals(2, versions.size(), "První podpis odděluje pozdější, ne prostřední razítko");
        assertEquals(20_000, versions.get(0).endOffset());
    }

    /**
     * Viditelný podpis otevírá skupinu i tehdy, když se od dneška liší týmž jako ten před ním —
     * jinak by splynul a podoba bez jeho vizualizace by se neukázala.
     */
    @Test
    @DisplayName("Viditelný podpis se neslije s předchozí podobou")
    void aVisibleSignatureBreaksTheGroup() {
        List<DocumentVersions.Version> versions = DocumentVersions.of(
                data(signature(30_000, pages(3)), signature(50_000, pages(3))),
                FILE, Set.of("SIG-50000"));

        assertEquals(3, versions.size());
        assertEquals(30_000, versions.get(0).endOffset());
        assertEquals(50_000, versions.get(1).endOffset());
    }

    @Test
    @DisplayName("Prvek, jehož revize sahá na konec souboru, položku nedostane ani s vizualizací")
    void theLastRevisionIsTodaysFile() {
        assertEquals(1, DocumentVersions.of(data(signature(FILE, null)), FILE,
                Set.of("SIG-" + FILE)).size());
    }

    // ------------------------------------------------------------------ přípravek

    private static DiagnosticData data(Object... elements) {
        XmlDiagnosticData xml = new XmlDiagnosticData();
        for (Object e : elements) {
            if (e instanceof XmlSignature s) xml.getSignatures().add(s);
            if (e instanceof XmlTimestamp t) xml.getUsedTimestamps().add(t);
        }
        return new DiagnosticData(xml);
    }

    private static XmlModificationDetection pages(int... numbers) {
        XmlModificationDetection md = new XmlModificationDetection();
        for (int n : numbers) md.getVisualDifference().add(page(n));
        return md;
    }

    private static XmlModification page(int number) {
        XmlModification m = new XmlModification();
        m.setPage(BigInteger.valueOf(number));
        return m;
    }

    private static XmlSignature signature(long end, XmlModificationDetection md) {
        XmlSignature s = new XmlSignature();
        s.setId("SIG-" + end);
        s.setPDFRevision(revision(end, md));
        return s;
    }

    private static XmlTimestamp timestamp(long end, XmlModificationDetection md) {
        XmlTimestamp t = new XmlTimestamp();
        t.setId("TS-" + end);
        t.setPDFRevision(revision(end, md));
        return t;
    }

    private static XmlPDFRevision revision(long end, XmlModificationDetection md) {
        XmlByteRange br = new XmlByteRange();
        br.setValid(true);
        br.getValue().add(BigInteger.ZERO);
        br.getValue().add(BigInteger.valueOf(1000));
        br.getValue().add(BigInteger.valueOf(end - 500));
        br.getValue().add(BigInteger.valueOf(500));

        XmlPDFSignatureDictionary dict = new XmlPDFSignatureDictionary();
        dict.setSignatureByteRange(br);

        XmlPDFRevision rev = new XmlPDFRevision();
        rev.setPDFSignatureDictionary(dict);
        if (md != null) rev.setModificationDetection(md);
        return rev;
    }
}
