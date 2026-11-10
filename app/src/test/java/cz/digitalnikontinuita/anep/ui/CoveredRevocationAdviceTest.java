package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Důvod 22 u prvku, jehož dřívější existenci dokládá krycí prvek s razítkem.
 *
 * <p>Katalogová rada říká, že volba k post-expiračním revokačním údajům k platnosti nevede,
 * protože nic nedokládá, zda podpis vznikl před revokací. Změřeno 10. 11. 2026 na podpisu
 * JAdES B-B s protipodpisem úrovně LT: s tou volbou vyšel podpis jako platný, protože razítko
 * protipodpisu je starší než revokace.
 */
class CoveredRevocationAdviceTest {

    private static SignatureSummary sig(String coveringProof) {
        return SigFixture.sig()
                .indication("INDETERMINATE")
                .reasons(22)
                .coveringProof(coveringProof)
                .build();
    }

    @Test
    @DisplayName("S krycím důkazem rada netvrdí, že volba k platnosti nevede")
    void coveredElementGetsItsOwnAdvice() {
        SignatureSummary covered = sig("Prvek č. 2 v témže dokumentu je protipodpisem…");
        String advice = AdvisorView.reasonSolution(covered, 22, ResultView.REASONS.get(22));
        assertTrue(advice.contains("může pak podpis vyjít jako platný"), advice);
        assertFalse(advice.contains("spolehlivě posouzena nebude"), advice);

        String problem = AdvisorView.reasonProblem(covered, 22, ResultView.REASONS.get(22));
        assertFalse(problem.contains("k rozhodnému okamžiku"), problem);
    }

    @Test
    @DisplayName("Bez krycího důkazu zůstává katalogové znění")
    void plainElementKeepsTheCatalogueText() {
        SignatureSummary plain = sig(null);
        ResultView.ReasonEntry re = ResultView.REASONS.get(22);
        assertEquals(re.solution(), AdvisorView.reasonSolution(plain, 22, re));
        assertEquals(re.problem(), AdvisorView.reasonProblem(plain, 22, re));
    }
}
