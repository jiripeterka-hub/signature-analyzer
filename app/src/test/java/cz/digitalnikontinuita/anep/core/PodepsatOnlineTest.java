package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.x509.CertificateToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rozpoznání podpisu od služby Podepsat.online. Znění /Reason i DN vydavatele mají tvar,
 * jaký služba zapisuje (naměřeno 31. 10. 2026 na jejích podpisech); e-mail a číslo jsou smyšlené.
 */
class PodepsatOnlineTest {

    private static final String ISSUER = "CN=SignMaster CA,C=CZ,L=Hornokrcska 15\\; 140 00 Praha 4,"
            + "ST=Czech Republic,O=Software602 a.s. [IC 63078236]";
    private static final String REASON =
            "Identita uživatele ověřena prostřednictvím emailu jan@example.cz a telefonu +420600000000.";

    @Test
    @DisplayName("vydavatel SignMaster CA a znění /Reason služby — jde o službu")
    void recognised() {
        assertTrue(PodepsatOnline.isServiceSignature(ISSUER, REASON));
    }

    @Test
    @DisplayName("bez znění /Reason služby nejde o službu, ani od téže autority")
    void issuerAloneIsNotEnough() {
        assertFalse(PodepsatOnline.isServiceSignature(ISSUER, null));
        assertFalse(PodepsatOnline.isServiceSignature(ISSUER, "Souhlasím s obsahem"));
        assertFalse(PodepsatOnline.isServiceSignature(
                "CN=Jiná CA,O=Někdo a.s.", REASON));
    }

    @Test
    @DisplayName("e-mail a telefon se čtou z /Reason, bez tečky na konci")
    void contact() {
        PodepsatOnline.Contact c = PodepsatOnline.contact(REASON);
        assertNotNull(c);
        assertEquals("jan@example.cz", c.email());
        assertEquals("+420600000000", c.phone());
    }

    @Test
    @DisplayName("přibalený kořen je SignMaster CA s vlastním podpisem a otiskem z javadocu")
    void bundledRoot() {
        CertificateToken root = PodepsatOnline.loadRoot();
        assertNotNull(root);
        assertTrue(root.isSelfSigned());
        assertTrue(root.getSubject().getRFC2253().contains("CN=SignMaster CA"));
        assertEquals(PodepsatOnline.ROOT_SHA256, java.util.HexFormat.of().formatHex(
                root.getDigest(eu.europa.esig.dss.enumerations.DigestAlgorithm.SHA256)));
    }
}
