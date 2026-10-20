package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kdy Závěr v Analýze pojmenuje zastaralost hašovací funkce.
 *
 * <p>Nález uživatele z 15. 9. 2026 nad {@code hashe\RSA MD5 2019 11 22 s A.pdf}: jediný důvod je
 * 16 (MD5), dílčí indikace {@code CRYPTO_CONSTRAINTS_FAILURE_NO_POE} — a Závěr přesto mluvil
 * o chybějícím revokačním údaji, o MD5 ani slovo. V tolerantním režimu si navíc odporoval
 * s odstavcem o kus výš, který hlásil využitou vloženou odpověď OCSP.
 *
 * <p>Rozhoduje <b>dílčí indikace</b>, ne výčet situací: situace 15 (SHA-1) bývá indikována
 * i tam, kde o výsledku rozhodlo něco docela jiného.
 */
class CryptoConclusionTest {

    private static SigFixture.Builder crypto(String subIndication) {
        return SigFixture.sig()
                .format("PAdES-BASELINE-LT")
                .indication("INDETERMINATE")
                .subIndication(subIndication)
                .certNotAfter(SigFixture.PAST);
    }

    @Test
    @DisplayName("MD5 s krypto indikací rozhoduje")
    void md5Decides() {
        SignatureSummary sig = crypto("CRYPTO_CONSTRAINTS_FAILURE_NO_POE")
                .situations(4, 5, 6, 16, 31, 33, 34).reasons(16).build();
        assertTrue(AdvisorView.cryptoDecisive(sig));
    }

    @Test
    @DisplayName("SHA-1 s krypto indikací rozhoduje taky")
    void sha1Decides() {
        SignatureSummary sig = crypto("CRYPTO_CONSTRAINTS_FAILURE")
                .situations(4, 15, 27).reasons(15).build();
        assertTrue(AdvisorView.cryptoDecisive(sig));
    }

    @Test
    @DisplayName("SHA-1 bez krypto indikace nerozhoduje")
    void sha1WithoutCryptoIndicationDoesNotDecide() {
        // Přesně tvar priklad6_2_Razitko_do_03_08_2034.pdf: situace 15 je indikována, ale
        // o výsledku rozhoduje razítko (důvod 21) a dílčí indikace mluví o mezích platnosti.
        // Kdyby stačila situace, Závěr by tam pojmenoval SHA-1 místo chybějícího důkazu.
        SignatureSummary sig = crypto("OUT_OF_BOUNDS_NOT_REVOKED")
                .situations(4, 15, 25, 27, 32, 33, 34, 43).reasons(15, 21).build();
        assertFalse(AdvisorView.cryptoDecisive(sig));
    }

    @Test
    @DisplayName("krypto indikace bez krypto situace nerozhoduje")
    void cryptoIndicationWithoutSituation() {
        // Text Závěru končí větou o tom, zda ji promine volnější nastavení, a to se pozná
        // právě ze situace 15 nebo 16. Bez ní by věta neměla z čeho vyjít.
        SignatureSummary sig = crypto("CRYPTO_CONSTRAINTS_FAILURE_NO_POE")
                .situations(4, 27).reasons(21).build();
        assertFalse(AdvisorView.cryptoDecisive(sig));
    }

    @Test
    @DisplayName("bez dílčí indikace se neptá")
    void noSubIndication() {
        SignatureSummary sig = SigFixture.sig().indication("INDETERMINATE")
                .situations(16).reasons(16).build();
        assertFalse(AdvisorView.cryptoDecisive(sig));
    }
}
