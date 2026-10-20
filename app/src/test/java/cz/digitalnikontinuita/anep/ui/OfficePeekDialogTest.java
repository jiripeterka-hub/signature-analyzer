package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.Family;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proč se ověření nekonalo, se u obou rodin říká jinak — a ten rozdíl je věcný, ne slohový.
 *
 * <p>U OOXML aplikace ověřovat <b>neumí</b>: chybí {@code RelationshipTransform}. U ODF umí
 * a běžně to dělá — změřeno 31. 8. 2026 na podepsaném ODT, který
 * {@code ASiCContainerWithXAdESValidator} projde jako XAdES-BASELINE-T s neporušenou integritou
 * a nalezeným razítkem. Kdyby okno mělo jednu společnou větu, v jednom z těch případů by lhalo;
 * a přesně tak to napsané bylo, dokud se to nezměřilo.
 */
class OfficePeekDialogTest {

    @Test
    @DisplayName("OOXML: aplikace ověřovat neumí")
    void ooxmlSaysTheApplicationCannot() {
        String text = OfficePeekDialog.introText(Family.OOXML);
        assertTrue(text.contains("zatím neověřuje"), text);
    }

    @Test
    @DisplayName("ODF: aplikace ověřovat umí, jen tenhle soubor neprošel")
    void odfSaysTheFileIsTheProblem() {
        String text = OfficePeekDialog.introText(Family.ODF);
        assertTrue(text.contains("běžně ověřuje"),
                "podepsaný ODT projde normálně; tvrdit u něj opak by byla nepravda: " + text);
        assertNotEquals(OfficePeekDialog.introText(Family.OOXML), text);
    }

    @Test
    @DisplayName("Horizont: minulost a budoucnost se říkají jinak")
    void horizonSpeaksInTheRightTense() {
        String future = OfficePeekDialog.horizonNote(
                java.time.Instant.now().plus(java.time.Duration.ofDays(365)));
        assertTrue(future.contains("má ověření šanci"), future);

        String past = OfficePeekDialog.horizonNote(
                java.time.Instant.parse("2020-01-01T00:00:00Z"));
        assertTrue(past.contains("už skončila"), past);
        assertTrue(past.contains("nezneplatňuje"),
                "prošlý certifikát razítka sám o sobě verdikt neurčuje: " + past);
        assertNotEquals(future, past);
    }

    @Test
    @DisplayName("Úroveň se nevydává za výsledek ověření — a výhrada stojí jen tam, kde platí")
    void levelIsNeverPresentedAsAVerdict() {
        var L = cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.Level.class;
        String caveat = "ověřením by mohla vyjít nižší";

        for (var level : java.util.List.of(
                cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.Level.T,
                cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.Level.LT,
                cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.Level.LTA)) {
            assertTrue(OfficePeekDialog.levelNote(level).contains(caveat),
                    level + ": chybí výhrada, že jde o strukturu, ne o ověření");
        }

        for (var level : java.util.List.of(
                cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.Level.B,
                cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.Level.XMLDSIG)) {
            assertFalse(OfficePeekDialog.levelNote(level).contains(caveat),
                    level + ": tady není z čeho klesnout — úroveň je daná tím, co v souboru"
                    + " chybí, a chybějící prvek ověřením nevznikne");
        }

        assertEquals(5, L.getEnumConstants().length,
                "přibyla úroveň? rozhodni, do které z obou skupin patří");
    }

    @Test
    @DisplayName("Druh podpisu: bez QcType se mlčí, nedosazuje se výchozí")
    void signatureKindStaysSilentWithoutAType() {
        assertNull(OfficePeekDialog.signatureKind(null));

        var empty = new eu.europa.esig.dss.model.x509.extension.QcStatements();
        assertNull(OfficePeekDialog.signatureKind(empty),
                "certifikát bez QcType nemá čím rozhodnout; „podpis“ jako výchozí by bylo"
                + " tvrzení, které nikdo nenapsal");

        var seal = new eu.europa.esig.dss.model.x509.extension.QcStatements();
        seal.setQcTypes(java.util.List.of(
                eu.europa.esig.dss.enumerations.QCTypeEnum.QCT_ESEAL));
        assertTrue(OfficePeekDialog.signatureKind(seal).contains("pečeť"),
                OfficePeekDialog.signatureKind(seal));
    }
}
