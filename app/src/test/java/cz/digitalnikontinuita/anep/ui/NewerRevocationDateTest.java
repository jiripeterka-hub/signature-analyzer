package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Důvod 2 řekne, kdy ten certifikát expiroval.
 *
 * <p>Přání uživatele z 21. 9. 2026: věta říkala, že revokační údaj přišel až po expiraci, ale kdy
 * k té expiraci došlo, si čtenář musel najít v jiné záložce.
 *
 * <p>Datum se bere z certifikátu, kterého se nález opravdu týká. Situace 6 chodí i po řetězech
 * razítek, takže sáhnout po konci platnosti podpisového certifikátu by u nálezu na razítku dosadilo
 * do věty cizí datum — jádro proto posílá ten z téhož výčtu, kterým důvod našlo.
 */
class NewerRevocationDateTest {

    private static final Instant EXPIRY = Instant.parse("2026-06-27T08:35:22Z");

    private static String problem(SignatureSummary sig) {
        return AdvisorView.reasonProblem(sig, 2, ResultView.REASONS.get(2));
    }

    @Test
    @DisplayName("Datum expirace se dosadí do věty")
    void theExpiryIsFilledIn() {
        SignatureSummary sig = SigFixture.sig()
                .indication("INDETERMINATE")
                .reasons(2)
                .newerRevocationCertNotAfter(EXPIRY)
                .build();

        String text = problem(sig);
        assertTrue(text.contains("k ní došlo"), text);
        assertTrue(text.contains("2026"), text);
        assertFalse(text.contains("{"), "zástupné slovo zůstalo nedoplněné: " + text);
        assertFalse(text.contains("()"), "prázdná závorka: " + text);
    }

    @Test
    @DisplayName("Bez data platí znění bez závorky")
    void withoutTheDateTheBareWordingApplies() {
        SignatureSummary sig = SigFixture.sig()
                .indication("INDETERMINATE")
                .reasons(2)
                .build();

        String text = problem(sig);
        assertEquals(ReasonTexts.get("2/bez-data", "problem"), text, text);
        assertFalse(text.contains("{"), text);
        assertFalse(text.contains("k ní došlo"), "bez data se ta závorka vynechá celá: " + text);
    }

    /**
     * Důvod 6 mluví o jiném certifikátu — o tom, kterým je revokační údaj podepsaný — a datum si
     * proto bere z jiného místa. Sazba je ale společná, takže obě věty musí dopadnout stejně.
     */
    @Test
    @DisplayName("Totéž u důvodu 6, jen s datem vydavatele")
    void theIssuerExpiryIsFilledInForReasonSix() {
        SignatureSummary withDate = SigFixture.sig()
                .indication("INDETERMINATE")
                .reasons(6)
                .expiredResponderNotAfter(EXPIRY)
                .build();
        String text = AdvisorView.reasonProblem(withDate, 6, ResultView.REASONS.get(6));
        assertTrue(text.contains("jeho platnost skončila"), text);
        assertTrue(text.contains("2026"), text);
        assertFalse(text.contains("{"), text);

        SignatureSummary withoutDate = SigFixture.sig()
                .indication("INDETERMINATE").reasons(6).build();
        String plain = AdvisorView.reasonProblem(withoutDate, 6, ResultView.REASONS.get(6));
        assertEquals(ReasonTexts.get("6/bez-data", "problem"), plain, plain);
        assertFalse(plain.contains("{"), plain);
    }

    /**
     * Datum u důvodu 6 patří <b>vydavateli</b> údaje, ne certifikátu, k němuž se údaj vztahuje.
     * Kdyby se ty dva zdroje pletly, věta by u jednoho z důvodů ukázala cizí datum.
     */
    @Test
    @DisplayName("Každý z těch dvou důvodů si bere své datum")
    void eachReasonTakesItsOwnDate() {
        java.time.Instant issuerExpiry = java.time.Instant.parse("2024-01-31T23:00:00Z");
        SignatureSummary sig = SigFixture.sig()
                .indication("INDETERMINATE")
                .reasons(2, 6)
                .newerRevocationCertNotAfter(EXPIRY)
                .expiredResponderNotAfter(issuerExpiry)
                .build();

        assertTrue(problem(sig).contains("2026"), problem(sig));
        String six = AdvisorView.reasonProblem(sig, 6, ResultView.REASONS.get(6));
        assertTrue(six.contains("2024"), six);
        assertFalse(six.contains("2026"), "to je datum druhého certifikátu: " + six);
    }

    @Test
    @DisplayName("Katalogová znění zástupné slovo opravdu nesou")
    void theCatalogueTextCarriesThePlaceholder() {
        assertTrue(ResultView.REASONS.get(2).problem().contains("{konec}"));
        assertTrue(ResultView.REASONS.get(6).problem().contains("{konec}"));
    }
}
