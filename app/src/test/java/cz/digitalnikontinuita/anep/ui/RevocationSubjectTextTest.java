package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Důvody 24 a 25 jmenují certifikát, kterého se nález týká.
 *
 * <p>Obě věty začínaly holým „o certifikátu" a čtenář neměl jak poznat, o který jde — kontrola
 * textů 21. 9. 2026. Dopsat do souboru natvrdo „podpisovém" nešlo: situace 46 a 47, ze kterých ty
 * důvody vznikají, chodí po podpisovém řetězu <b>i po řetězech razítek</b>, takže to může být
 * i certifikát razítkovací autority. Jméno proto určuje jádro z téhož výčtu certifikátů, kterým
 * situaci našlo, a text si ho dosadí.
 */
class RevocationSubjectTextTest {

    private static String problem(SignatureSummary sig, int rn) {
        return AdvisorView.reasonProblem(sig, rn, ResultView.REASONS.get(rn));
    }

    @Test
    @DisplayName("Jméno z jádra se dosadí do věty")
    void theSubjectFromTheCoreIsFilledIn() {
        SignatureSummary sig = SigFixture.sig()
                .indication("INDETERMINATE")
                .reasons(24, 25)
                .reasonCertificates(Map.of(
                        24, "certifikátu „I.CA TSS/TSU 3“ z řetězu časového razítka",
                        25, "podpisovém certifikátu"))
                .build();

        String r24 = problem(sig, 24);
        assertTrue(r24.contains("z řetězu časového razítka"),
                "nález je na řetězu razítka a věta to má říct: " + r24);
        assertFalse(r24.contains("{"), r24);

        String r25 = problem(sig, 25);
        assertTrue(r25.contains("o podpisovém certifikátu"), r25);
        assertFalse(r25.contains("{"), r25);
    }

    @Test
    @DisplayName("Bez jména platí znění, které certifikát nepojmenuje")
    void withoutASubjectThePlainWordingApplies() {
        SignatureSummary sig = SigFixture.sig()
                .indication("INDETERMINATE")
                .reasons(24, 25)
                .build();

        for (int rn : new int[] {24, 25}) {
            String text = problem(sig, rn);
            assertFalse(text.contains("{"), "zástupné slovo zůstalo nedoplněné: " + text);
            assertEquals(ReasonTexts.get(rn + "/bez-certifikatu", "problem"), text,
                    "má platit náhradní znění: " + text);
        }
    }

    @Test
    @DisplayName("Katalogové znění zástupné slovo opravdu nese")
    void theCatalogueTextCarriesThePlaceholder() {
        // Kdyby ho někdo z textu vymazal, dosazení by tiše nic neudělalo a věta by se vrátila
        // k holému „o certifikátu" — přesně k tomu, co se tímhle opravovalo.
        assertTrue(ResultView.REASONS.get(24).problem().contains("{certifikat}"));
        assertTrue(ResultView.REASONS.get(25).problem().contains("{certifikat}"));
    }
}
