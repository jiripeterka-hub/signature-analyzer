package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.x509.CertificateToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rozpoznání podpisu od služby BankID Sign podle vydavatele. DN mají tvar, jaký nesou
 * certifikáty služby (naměřeno 1. 11. 2026).
 */
class BankIdSignTest {

    @Test
    @DisplayName("vydávající autorita BankID Document CA — jde o službu")
    void recognised() {
        assertTrue(BankIdSign.isServiceSignature("CN=BankID Document CA,OU=Bankovni identita\\, a.s.,"
                + "O=Bankovni identita\\, a.s.,ST=Praha,C=CZ"));
    }

    @Test
    @DisplayName("kořen ani cizí autorita za službu nepovažuje")
    void others() {
        assertFalse(BankIdSign.isServiceSignature("CN=BankID Document Root CA,O=Bankovni identita\\, a.s."));
        assertFalse(BankIdSign.isServiceSignature("CN=Document CA,O=Někdo jiný a.s."));
        assertFalse(BankIdSign.isServiceSignature(null));
    }

    @Test
    @DisplayName("přibalený kořen je BankID Document Root CA s vlastním podpisem a otiskem z javadocu")
    void bundledRoot() {
        CertificateToken root = BankIdSign.loadRoot();
        assertNotNull(root);
        assertTrue(root.isSelfSigned());
        assertTrue(root.getSubject().getRFC2253().contains("CN=BankID Document Root CA"));
        assertEquals(BankIdSign.ROOT_SHA256, java.util.HexFormat.of().formatHex(
                root.getDigest(eu.europa.esig.dss.enumerations.DigestAlgorithm.SHA256)));
    }
}
