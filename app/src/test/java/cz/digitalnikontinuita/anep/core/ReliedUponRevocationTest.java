package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.enumerations.CertificateStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kdy se seznam CRL, o který se opřelo ověření, do uchovaného dokumentu <b>nepřidává</b>.
 *
 * <h2>Proč to má vlastní test</h2>
 *
 * <p>Pravidlo se na sbírce {@code D:\tmp} nevyvolá ani jednou (188 dokumentů, žádný za vnější
 * seznam neplatí), takže jeho obě strany hlídá jen tenhle test. A ta odhazující strana je tichá:
 * odhodí-li víc, než má, uchovaný dokument přijde o důkaz, na kterém stojí jeho závěr, a pozná se
 * to až u toho, kdo ho bude chtít ověřit.
 *
 * <p>Případ, kvůli kterému pravidlo vzniklo, je „PAdES T-LEvel jen uznavany extended.pdf“:
 * podpisový certifikát platil do 30. 1. 2017 a měl dvě autoritativní CRL od PostSignum Qualified
 * CA 2 — vloženou z 30. 1. 2017 (139 kB) a staženou z 19. 1. 2020 (2 627 602 B, protože si drží
 * i položky za expirované certifikáty). Obě říkaly totéž a uchování z 549kB souboru udělalo
 * 3,25 MB.
 */
class ReliedUponRevocationTest {

    /** Konec platnosti podpisového certifikátu z měřeného dokumentu. */
    private static final Date NOT_AFTER = date("2017-01-30T07:25:13Z");
    /** Vložená CRL: vydána těsně před expirací, takže certifikát pokrývá bez dalšího. */
    private static final Date INSIDE_THIS_UPDATE = date("2017-01-30T04:20:04Z");
    /** Stažená CRL: o tři roky později, uznatelná jen díky expiredCertsOnCRL. */
    private static final Date OUTSIDE_THIS_UPDATE = date("2020-01-19T11:29:09Z");

    @Test
    @DisplayName("Týž stav a vložený seznam certifikát pokrývá: vnější se nepřidává")
    void sameAnswerInsideMakesTheOutsideListRedundant() {
        assertTrue(ReliedUponRevocation.insideAnswersToo(CertificateStatus.GOOD,
                        INSIDE_THIS_UPDATE, null, CertificateStatus.GOOD, NOT_AFTER),
                "Dva seznamy od téhož vydavatele, které o témž certifikátu tvrdí totéž, jsou pro"
                + " čtenáře jeden důkaz — a ten druhý stojí celou svou velikost.");
    }

    @Test
    @DisplayName("Rozdílný stav: vnější seznam je jediný důkaz a musí do dokumentu")
    void aDifferentAnswerOutsideIsKept() {
        assertFalse(ReliedUponRevocation.insideAnswersToo(CertificateStatus.GOOD,
                        INSIDE_THIS_UPDATE, null, CertificateStatus.REVOKED, NOT_AFTER),
                "To je situace 44: vložená odpověď říká „neodvolán“, síť „odvolán“, a závěr stojí"
                + " právě na té druhé. Odhodit ji znamená uchovat dokument, který se ověří"
                + " příznivěji než originál.");
        assertFalse(ReliedUponRevocation.insideAnswersToo(CertificateStatus.REVOKED,
                        INSIDE_THIS_UPDATE, null, CertificateStatus.GOOD, NOT_AFTER));
    }

    @Test
    @DisplayName("Vložený seznam certifikát nepokrývá: vnější se přidává")
    void anInsideListThatSaysNothingDoesNotCount() {
        assertFalse(ReliedUponRevocation.insideAnswersToo(CertificateStatus.GOOD,
                        OUTSIDE_THIS_UPDATE, null, CertificateStatus.GOOD, NOT_AFTER),
                "Seznam vydaný po expiraci certifikátu bez expiredCertsOnCRL o něm neříká nic:"
                + " RFC 5280 klauzule 3.3 dovoluje položku po expiraci vypustit.");
        assertFalse(ReliedUponRevocation.insideAnswersToo(CertificateStatus.GOOD,
                        null, null, CertificateStatus.GOOD, NOT_AFTER));
    }

    @Test
    @DisplayName("expiredCertsOnCRL sahající před expiraci vložený seznam uznává")
    void expiredCertsOnCrlReachesBackOverTheCertificate() {
        assertTrue(ReliedUponRevocation.insideAnswersToo(CertificateStatus.GOOD,
                        OUTSIDE_THIS_UPDATE, date("2010-01-19T11:44:47Z"),
                        CertificateStatus.GOOD, NOT_AFTER),
                "Vydavatel prohlásil, od kdy si drží položky za expirované certifikáty; potud"
                + " jeho seznam vypovídá i o tomhle.");
        assertFalse(ReliedUponRevocation.insideAnswersToo(CertificateStatus.GOOD,
                        OUTSIDE_THIS_UPDATE, date("2019-01-19T11:44:47Z"),
                        CertificateStatus.GOOD, NOT_AFTER),
                "Sahá-li až za expiraci, tenhle certifikát pod ni nespadá.");
    }

    @Test
    @DisplayName("Neznámý stav neodhazuje nic")
    void unknownIsNoAnswer() {
        assertFalse(ReliedUponRevocation.insideAnswersToo(CertificateStatus.UNKNOWN,
                        INSIDE_THIS_UPDATE, null, CertificateStatus.UNKNOWN, NOT_AFTER));
        assertFalse(ReliedUponRevocation.insideAnswersToo(null,
                        INSIDE_THIS_UPDATE, null, CertificateStatus.GOOD, NOT_AFTER));
    }

    private static Date date(String iso) {
        return Date.from(Instant.parse(iso));
    }
}
