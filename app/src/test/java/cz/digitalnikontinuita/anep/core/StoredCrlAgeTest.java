package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static cz.digitalnikontinuita.anep.core.BestMatchCustomCrlSource.issuedAfterTheCertificateExisted;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seznam CRL vydaný dřív, než certifikát vůbec vznikl, o něm nedokládá nic.
 *
 * <p>Nález uživatele 13. 9. 2026: uložená CRL z 9. 9. 2021 prohlásila „certifikát nebyl revokován"
 * o certifikátu vydaném 7. 6. 2025 — a odsunula tím odpověď OCSP staženou týž den, podle které
 * certifikát revokován byl. Certifikát v době vydání seznamu neexistoval, takže jeho nepřítomnost
 * v seznamu je mlčení o něčem, co ještě nebylo.
 *
 * <p>Pravidlo úmyslně <b>nezávisí na rozhodném okamžiku</b>: ten se u podpisu bez časového razítka
 * nemusí dát stanovit, a právě tam se starší filtr (Search 0) dřív mlčky vypínal.
 */
class StoredCrlAgeTest {

    private static Date at(String iso) {
        return Date.from(Instant.parse(iso));
    }

    @Test
    @DisplayName("Seznam starší než certifikát se zahodí")
    void listPredatesTheCertificate() {
        assertFalse(issuedAfterTheCertificateExisted(
                at("2021-09-09T07:25:13Z"), at("2025-06-07T08:35:22Z")));
    }

    @Test
    @DisplayName("Seznam vydaný po certifikátu projde")
    void listAfterTheCertificate() {
        assertTrue(issuedAfterTheCertificateExisted(
                at("2026-01-20T10:00:00Z"), at("2025-06-07T08:35:22Z")));
    }

    @Test
    @DisplayName("Seznam vydaný přesně v okamžiku vydání certifikátu projde")
    void sameMoment() {
        Date moment = at("2025-06-07T08:35:22Z");
        assertTrue(issuedAfterTheCertificateExisted(moment, moment));
    }

    @Test
    @DisplayName("O sekundu dřív už ne — hranice je ostrá")
    void oneSecondEarlier() {
        Date notBefore = at("2025-06-07T08:35:22Z");
        Date thisUpdate = Date.from(notBefore.toInstant().minus(1, ChronoUnit.SECONDS));
        assertFalse(issuedAfterTheCertificateExisted(thisUpdate, notBefore));
    }

    @Test
    @DisplayName("Chybějící datum nic nezakazuje — zahazuje se jen to, co je prokazatelně dřív")
    void missingDates() {
        assertTrue(issuedAfterTheCertificateExisted(null, at("2025-06-07T08:35:22Z")));
        assertTrue(issuedAfterTheCertificateExisted(at("2021-09-09T07:25:13Z"), null));
        assertTrue(issuedAfterTheCertificateExisted(null, null));
    }
}
