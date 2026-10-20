package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AugmentationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the one preservation failure AnEP explains in its own words instead of passing DSS's
 * through.
 *
 * <p>A signature whose certificate was revoked before it can be shown to have existed cannot be
 * preserved, and that is a rule rather than a fault — EN 319 122-1 clause 6.3 requirement m). What
 * the reader used to get was {@code Revoked/Suspended certificate(s) detected} followed by a token
 * identifier: no certificate they recognise and no reason. The recognition hangs on wording inside
 * an upstream exception message, which is exactly the kind of thing that stops matching after a DSS
 * upgrade and fails silently — the dialog would simply go back to the raw text. Hence this test.
 */
class RevokedPreservationNoticeTest {

    /** The message DSS produces, wrapped the way AugmentationService wraps it. */
    private static Throwable dssRefusal() {
        return new AugmentationService.AugmentationException(
                "Augmentace selhala: Revoked/Suspended certificate(s) detected. "
                        + "[C-D28EC985A7B193E26C5758DFD8FB55D5C45610235EAEE4509DDB369B031AA9CB: "
                        + "Certificate is revoked/suspended!]",
                new IllegalStateException("Revoked/Suspended certificate(s) detected."));
    }

    @Test
    @DisplayName("Odmítnutí kvůli odvolanému certifikátu se vysvětlí česky")
    void revocationRefusalIsExplained() {
        String text = AdvisorView.revokedCertificateExplanation(dssRefusal(), false);

        assertNotNull(text, "Hláška DSS o odvolaném certifikátu se přestala poznávat — "
                + "čtenář by dostal syrový text s identifikátorem tokenu.");
        assertTrue(text.startsWith("Tento podpis nelze uchovat"), text);
        // „revokován", ne „odvolán": tak o tomtéž certifikátu mluví zbytek aplikace — v jejích
        // uživatelských textech je 121 výskytů revokace proti třem odvolání, a ta tři jsou dvě
        // věty o CRL ve Zjednodušeném přehledu. Hláška se se sousedními bloky Analýzy rozejít
        // nesmí, čtenář je vidí nad sebou. (Test čekal „odvolán" a padal od doby, kdy hláška
        // vznikla.)
        assertTrue(text.contains("revokován"), text);
        assertTrue(text.contains("Vytvořit forenzní balíček"),
                "Odmítnutí má nabídnout, co jde udělat místo toho.");
    }

    @Test
    @DisplayName("U pečeti mluví hláška o pečeti")
    void sealIsAddressedAsSeal() {
        String text = AdvisorView.revokedCertificateExplanation(dssRefusal(), true);

        assertNotNull(text);
        assertTrue(text.startsWith("Tuto pečeť nelze uchovat"), text);
    }

    @Test
    @DisplayName("Jiné selhání si své hlášení ponechá")
    void otherFailuresAreLeftAlone() {
        assertNull(AdvisorView.revokedCertificateExplanation(
                new IllegalStateException("Connection timed out"), false));
        assertNull(AdvisorView.revokedCertificateExplanation(
                new AugmentationService.AugmentationException(
                        "Augmentace selhala: Revocation data is missing for one or more "
                                + "certificate(s).", null), false));
        assertNull(AdvisorView.revokedCertificateExplanation(null, false));
    }
}
