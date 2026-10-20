package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.alert.ExceptionOnStatusAlert;
import eu.europa.esig.dss.alert.SilentOnStatusAlert;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Úleva pro souběh prvků se uděluje jen na požádání — a je to obojí naráz, nebo nic.
 *
 * <p>Vzniklo z nálezu uživatele z 12. 9. 2026 nad „PAdES B-B +B-T kvalifikovany ICA.pdf": dokument
 * se dvěma podpisy na témže certifikátu, z nichž jeden má časové razítko a druhý ne. Uchování
 * prospěje tomu s razítkem, jenže DSS zapsání validačních dat vetuje za celý dokument, jakmile se
 * o kterýkoli podpis opřít nelze — a zablokuje tím právě ten prvek, kvůli kterému se nabídka
 * udělala.
 *
 * <p>Hlídají se tři věci. Že se bez požádání nic nemění, protože u dokumentu s jediným podpisem
 * má veto DSS zůstat jako druhý názor. Že se umlčí <b>obě</b> výstrahy, protože za expirační čeká
 * revokační a s jednou by se úkon zastavil o krok dál. A že se výstrahy nemlčí nikdy jinde než
 * tady — cesta {@code SignatureExtensionService} přidává podpisové časové razítko, kde to
 * EN 319 122-1 čl. 6.3 m) zakazuje, a bere si proto čtyřparametrovou podobu.
 */
class PreservationVerifierAlertsTest {

    private static CommonCertificateVerifier build(boolean tolerate) {
        return AugmentationService.buildPreservationVerifier(
                new CommonCertificateVerifier(), List.of(), List.of(), null, tolerate);
    }

    @Test
    @DisplayName("Bez souběhu zůstávají výstrahy DSS v platnosti")
    void withoutConcurrencyTheAlertsStay() {
        CommonCertificateVerifier v = build(false);
        assertInstanceOf(ExceptionOnStatusAlert.class, v.getAlertOnExpiredCertificate(),
                "Veto nad podpisem bez důkazu existence má u jediného podpisu zůstat.");
        assertInstanceOf(ExceptionOnStatusAlert.class, v.getAlertOnRevokedCertificate(),
                "Totéž pro revokovaný certifikát.");
    }

    @Test
    @DisplayName("Čtyřparametrová podoba je ta přísná — používá ji prodloužení")
    void theFourArgumentFormIsTheStrictOne() {
        CommonCertificateVerifier v = AugmentationService.buildPreservationVerifier(
                new CommonCertificateVerifier(), List.of(), List.of(), null);
        assertInstanceOf(ExceptionOnStatusAlert.class, v.getAlertOnExpiredCertificate());
        assertInstanceOf(ExceptionOnStatusAlert.class, v.getAlertOnRevokedCertificate());
    }

    @Test
    @DisplayName("Při souběhu se umlčí obě výstrahy, ne jen ta první")
    void concurrencySilencesBothAlerts() {
        // Změřeno na tom dokumentu: po umlčení samotné expirační se úkon zastavil na
        // „Revoked/Suspended certificate(s) detected" — oba podpisy stojí na témže certifikátu,
        // revokovaném 27. 8. 2026.
        CommonCertificateVerifier v = build(true);
        assertInstanceOf(SilentOnStatusAlert.class, v.getAlertOnExpiredCertificate(),
                "Kvůli podpisu bez důkazu existence se nemá zastavit celý dokument.");
        assertInstanceOf(SilentOnStatusAlert.class, v.getAlertOnRevokedCertificate(),
                "Za expirační výstrahou čeká revokační; s jednou by se úkon zastavil o krok dál.");
    }

    @Test
    @DisplayName("Umlčí se jen ty dvě; ostatní výstrahy zůstávají beze změny")
    void nothingElseIsSilenced() {
        CommonCertificateVerifier tolerant = build(true);
        CommonCertificateVerifier plain = new CommonCertificateVerifier();
        assertInstanceOf(plain.getAlertOnInvalidTimestamp().getClass(),
                tolerant.getAlertOnInvalidTimestamp(), "Neplatné razítko se dál ohlásí.");
        assertInstanceOf(plain.getAlertOnMissingRevocationData().getClass(),
                tolerant.getAlertOnMissingRevocationData(), "Chybějící revokační data také.");
        assertInstanceOf(plain.getAlertOnNotYetValidCertificate().getClass(),
                tolerant.getAlertOnNotYetValidCertificate(), "Certifikát před počátkem platnosti též.");
    }
}
