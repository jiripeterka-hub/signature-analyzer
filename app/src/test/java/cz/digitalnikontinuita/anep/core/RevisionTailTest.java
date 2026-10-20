package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.jaxb.XmlByteRange;
import eu.europa.esig.dss.diagnostic.jaxb.XmlDiagnosticData;
import eu.europa.esig.dss.diagnostic.jaxb.XmlModification;
import eu.europa.esig.dss.diagnostic.jaxb.XmlModificationDetection;
import eu.europa.esig.dss.diagnostic.jaxb.XmlObjectModification;
import eu.europa.esig.dss.diagnostic.jaxb.XmlObjectModifications;
import eu.europa.esig.dss.diagnostic.jaxb.XmlPDFRevision;
import eu.europa.esig.dss.diagnostic.jaxb.XmlPDFSignatureDictionary;
import eu.europa.esig.dss.diagnostic.jaxb.XmlSignature;
import eu.europa.esig.dss.diagnostic.jaxb.XmlTimestamp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pravidla, podle kterých se rozhoduje, co je v souboru za posledním kryjícím prvkem.
 *
 * <p>Zkouší se nad <b>sestaveným</b> {@code DiagnosticData}, ne nad souborem, a je to záměr.
 * Sbírka {@code D:\tmp} (214 PDF) neobsahuje ani jeden dokument, u kterého by se po podpisu
 * viditelně změnil obsah — změřeno 18. 9. 2026 sondou {@code PdfRevisionTailProbe} — takže tu
 * zajímavější větev by na skutečných souborech nešlo vyzkoušet vůbec. Sestavený vstup ji vyzkouší
 * a zároveň drží ostatní pravidla, která z měření vyplynula.
 */
class RevisionTailTest {

    private static final long FILE = 100_000;

    @Test
    @DisplayName("Prvek sahající na konec souboru neříká nic — a blok se nestaví")
    void anElementReachingTheEndSaysNothing() {
        assertNull(RevisionTail.of(data(signature(FILE, null)), FILE),
                "Za prvkem není ani bajt, takže není o čem mluvit");
    }

    @Test
    @DisplayName("Rozhoduje ten prvek, který sahá nejdál, ne poslední v seznamu")
    void theFurthestElementWins() {
        // Podpis kryje méně než razítko nad ním — právě tohle nastane po uchování a právě tady
        // měla první podoba návrhu chybu: počítala jen podpisy a nad uchovaným dokumentem by
        // hlásila rozdíl, který tam není.
        assertNull(RevisionTail.of(data(signature(60_000, null), timestamp(FILE, null)), FILE),
                "Razítko kryje celý soubor, takže se nestalo nic");

        RevisionTail tail = RevisionTail.of(
                data(signature(60_000, null), timestamp(90_000, null)), FILE);
        assertNotNull(tail);
        assertEquals(10_000, tail.tailBytes(), "Měří se od nejdál sahajícího prvku");
        assertEquals(RevisionTail.Element.RAZITKO, tail.lastElement());
    }

    /**
     * Nález z měření, kvůli kterému se návrh opravoval: 15 souborů z {@code D:\tmp} hlásí překryv
     * anotací, ačkoli za jejich posledním prvkem není ani bajt. Není to tedy rozdíl mezi revizemi,
     * nýbrž vlastnost jedné revize, a do otázky „změnil se obsah" nepatří.
     */
    @Test
    @DisplayName("Překryv anotací není změna obsahu")
    void annotationOverlapIsNotAContentChange() {
        XmlModificationDetection md = new XmlModificationDetection();
        md.getAnnotationOverlap().add(page(1));
        md.setObjectModifications(objectChanges(true, false, false, false));

        RevisionTail tail = RevisionTail.of(data(signature(90_000, md)), FILE);
        assertNotNull(tail);
        assertFalse(tail.contentChanged(),
                "Překryv anotací se hlásí i tam, kde se nezměnilo nic — viz javadoc");
        assertTrue(tail.changedPages().isEmpty());
    }

    /**
     * Druhý nález z měření: 13 souborů hlásí „vyplnění formulářů" nebo „změnu anotací" a přitom
     * u nich DSS nenajde jediný vizuální rozdíl. Kategorie tedy pojmenovávají, co přibylo, ale na
     * otázku, zda to čtenář uvidí, neodpovídají.
     */
    @Test
    @DisplayName("Kategorie objektových změn samy o sobě změnu obsahu neznamenají")
    void objectCategoriesAloneAreNotAContentChange() {
        XmlModificationDetection md = new XmlModificationDetection();
        md.setObjectModifications(objectChanges(true, true, true, true));

        RevisionTail tail = RevisionTail.of(data(signature(90_000, md)), FILE);
        assertNotNull(tail);
        assertFalse(tail.contentChanged(), "Bez vizuálního rozdílu se obsah za změněný nepovažuje");
        assertTrue(tail.validationData() && tail.formOrSignature()
                && tail.annotations() && tail.undefined(), "Kategorie se ale mají zapamatovat");
    }

    /**
     * Větev, kterou sbírka nemá čím vyvolat — a proto je sestavená.
     */
    @Test
    @DisplayName("Vizuální a stránkový rozdíl znamenají změnu obsahu, a strany se sloučí a seřadí")
    void visualAndPageDifferencesAreAContentChange() {
        XmlModificationDetection md = new XmlModificationDetection();
        md.getVisualDifference().add(page(7));
        md.getVisualDifference().add(page(3));
        md.getPageDifference().add(page(7));   // týž list z druhého seznamu se nemá zdvojit
        md.getPageDifference().add(page(1));

        RevisionTail tail = RevisionTail.of(data(signature(90_000, md)), FILE);
        assertNotNull(tail);
        assertTrue(tail.contentChanged());
        assertEquals(List.of(1, 3, 7), tail.changedPages(),
                "Strany se slučují z obou seznamů, bez opakování a vzestupně");
    }

    @Test
    @DisplayName("Neplatný ByteRange se nebere v potaz")
    void anInvalidByteRangeIsIgnored() {
        XmlSignature sig = signature(90_000, null);
        sig.getPDFRevision().getPDFSignatureDictionary().getSignatureByteRange().setValid(false);
        assertNull(RevisionTail.of(data(sig), FILE),
                "Prefix by nebyl tím, co prvek kryje — pak se o něm nedá tvrdit nic");
    }

    @Test
    @DisplayName("Bez kryjícího prvku a bez známé délky souboru se mlčí")
    void nothingToSayWithoutInput() {
        assertNull(RevisionTail.of(data(), FILE), "Dokument bez podpisu i razítka");
        assertNull(RevisionTail.of(null, FILE));
        assertNull(RevisionTail.of(data(signature(90_000, null)), 0),
                "Neznámá délka souboru; hádat se nebude");
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

    /** Revize končící na {@code end}: {@code /ByteRange} je [0, a, b, c] a b+c = end. */
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

    private static XmlObjectModifications objectChanges(boolean extension, boolean forms,
                                                        boolean annotations, boolean undefined) {
        XmlObjectModifications om = new XmlObjectModifications();
        if (extension) om.getExtensionChanges().add(new XmlObjectModification());
        if (forms) om.getSignatureOrFormFill().add(new XmlObjectModification());
        if (annotations) om.getAnnotationChanges().add(new XmlObjectModification());
        if (undefined) om.getUndefined().add(new XmlObjectModification());
        return om;
    }

    private static XmlModification page(int number) {
        XmlModification m = new XmlModification();
        m.setPage(BigInteger.valueOf(number));
        return m;
    }
}
