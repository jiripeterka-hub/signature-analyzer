package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static cz.digitalnikontinuita.anep.ui.ResultView.noProofAndCertExpired;
import static cz.digitalnikontinuita.anep.ui.SigFixture.sig;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kdy Zjednodušený přehled tlačítko „Upozornění“ nevysází.
 *
 * <p>Pravidlo z 16. 9. 2026: podpis <b>bez časového razítka, jehož certifikát už expiroval</b>,
 * je na téhle záložce beznadějný případ a tlačítko u něj jen posílá čtenáře přepínat volby,
 * které s dokumentem nehnou. V Rozšířeném přehledu zůstává; ten stojí na vlastním výpočtu
 * a tohle pravidlo nezná.
 *
 * <p>Obě poloviny podmínky se testují zvlášť, protože každá sama o sobě je běžný stav, u kterého
 * upozornění dává smysl: podpis s razítkem a odvolaným certifikátem je situace 2 a expirovaný
 * certifikát s razítkem je úplně obyčejný archivní dokument.
 */
class SimpleWarningSuppressionTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final Instant EXPIRED = NOW.minus(400, ChronoUnit.DAYS);
    private static final Instant STILL_VALID = NOW.plus(400, ChronoUnit.DAYS);

    @Test
    @DisplayName("Bez razítka a s expirovaným certifikátem se upozornění nevysází")
    void hiddenWithoutProofAndExpiredCert() {
        assertTrue(noProofAndCertExpired(
                sig().situations(21).certNotAfter(EXPIRED).build(), NOW));
    }

    @Test
    @DisplayName("Časové razítko upozornění ponechá, i když certifikát expiroval")
    void timestampKeepsIt() {
        assertFalse(noProofAndCertExpired(
                sig().situations(2).certNotAfter(EXPIRED).timestamps(razitko()).build(), NOW));
    }

    @Test
    @DisplayName("Dosud platný certifikát upozornění ponechá, i když razítko není")
    void validCertKeepsIt() {
        assertFalse(noProofAndCertExpired(
                sig().situations(48).certNotAfter(STILL_VALID).build(), NOW));
    }

    @Test
    @DisplayName("Prvek bez podpisového certifikátu pravidlo nechává být")
    void noCertificateAtAll() {
        // Samostatné časové razítko a osiřelý prvek: certNotAfter je u nich prázdné a ptát se na
        // expiraci nemá o čem. Tlačítko u nich stejně nestojí, ale pravidlo to nesmí spoléhat.
        assertFalse(noProofAndCertExpired(sig().format("TimeStampToken").build(), NOW));
    }

    @Test
    @DisplayName("Expirace se měří k okamžiku ověření, ne k dnešku")
    void measuredAgainstTheValidationTime() {
        // Ověření k zadanému času v minulosti: tehdy certifikát ještě platil, takže upozornění
        // zůstává, ačkoli dnes už je po expiraci. Bez tohoto by se karta ptala na jiný okamžik
        // než zbytek jejího obsahu.
        assertFalse(noProofAndCertExpired(
                sig().situations(21).certNotAfter(EXPIRED).build(),
                EXPIRED.minus(10, ChronoUnit.DAYS)));
    }

    private static List<TimestampEntry> razitko() {
        return List.of(new TimestampEntry(NOW.minus(500, ChronoUnit.DAYS), true, "TSA",
                STILL_VALID, "sig", true));
    }
}
