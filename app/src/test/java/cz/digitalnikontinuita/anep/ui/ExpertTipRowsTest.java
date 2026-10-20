package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pod kterými důvody stojí „Pro zkušenější uživatele".
 *
 * <p>Nález uživatele z 21. 9. 2026 nad {@code Metodicky_pokyn_-_pristupnost…v1-3.pdf}: karta
 * s důvody 3 a 4 ukázala jedinou radu, tu od čtyřky („ověřit k zadanému datu"), a rada od
 * trojky („vyjádřit důvěru certifikátu") se nikam nevešla. Řádek se totiž stavěl jeden, pod
 * posledním důvodem, který nějakou radu má.
 *
 * <p>Dokud byly ty věty všechny stejné — a osm důvodů jich opravdu má jednu a touž, o úložišti
 * vlastních CRL —, vycházelo to nastejno. Jakmile se sešly dvě různé, jedna zmizela.
 */
class ExpertTipRowsTest {

    private static SignatureSummary sigWith(Integer... reasons) {
        return SigFixture.sig().indication("INDETERMINATE").reasons(reasons).build();
    }

    private static Set<Integer> rows(Integer... reasons) {
        return AdvisorView.expertTipRows(sigWith(reasons), List.of(reasons));
    }

    @Test
    @DisplayName("Dvě různé rady stojí každá u svého důvodu")
    void differentAdviceStaysWithItsOwnReason() {
        assertEquals(Set.of(3, 4), rows(3, 4),
                "rada od trojky i od čtyřky se musí na kartu vejít");
        // A že jsou to opravdu dvě různé věty — jinak by ten test neměřil nic.
        SignatureSummary sig = sigWith(3, 4);
        assertNotEquals(AdvisorView.expertTip(sig, 3), AdvisorView.expertTip(sig, 4));
    }

    @Test
    @DisplayName("Táž rada u víc důvodů stojí jednou, pod spodním z nich")
    void thesameAdviceIsPrintedOnceUnderTheBottomOne() {
        assertEquals(Set.of(12), rows(2, 6, 12),
                "rada o CRL je u všech tří táž věta a patří pod spodní z nich");
        assertEquals(Set.of(11), rows(10, 11), rows(10, 11).toString());
    }

    @Test
    @DisplayName("Obojí naráz: sdílená rada jednou, vlastní rada zvlášť")
    void sharedAndOwnAdviceTogether() {
        // Pořadí je pořadím výpisu: rada o CRL patří pod dvanáctku, rada trojky pod trojku.
        assertEquals(Set.of(3, 12), rows(3, 6, 12));
    }

    @Test
    @DisplayName("Bez rady nestojí řádek žádný")
    void noAdviceNoRow() {
        assertEquals(Set.of(), rows(7, 8));
    }
}
