package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Věta „V čem je problém" u důvodu 3 (neznámý certifikát) jmenuje vydavatele.
 *
 * <p>Samotné „certifikát není této aplikaci znám" nedává čtenáři nic, čeho by se chytil — právě
 * certifikát vydavatele se vkládá do vlastního úložiště a podle jména se pozná, jde-li o autoritu,
 * které chce čtenář důvěřovat.
 *
 * <p>Zkouší se i větev bez jména: kdyby se zástupné slovo nedoplnilo, čtenář by četl
 * {@code {vydavatel}} nebo prázdné uvozovky a měl by to za chybu aplikace.
 */
class UnknownCertIssuerTest {

    private static final String DN =
            "CN=I.CA Qualified 2 CA/RSA 02/2016,O=První certifikační autorita\\, a.s.,C=CZ";

    private static SignatureSummary sigWith(String issuerDn) {
        return SigFixture.sig()
                .indication("INDETERMINATE")
                .subIndication("NO_CERTIFICATE_CHAIN_FOUND")
                .situations(12)
                .reasons(3)
                .certIssuerDN(issuerDn)
                .build();
    }

    private static String problem(SignatureSummary sig) {
        return AdvisorView.reasonProblem(sig, 3, ResultView.REASONS.get(3));
    }

    @Test
    @DisplayName("Vydavatel je ve větě jménem")
    void issuerIsNamed() {
        String text = problem(sigWith(DN));
        assertTrue(text.contains("I.CA Qualified 2 CA/RSA 02/2016"),
                "jméno vydavatele ve větě chybí: " + text);
        assertFalse(text.contains("{"), "zástupné slovo zůstalo nedoplněné: " + text);
    }

    @Test
    @DisplayName("Bez jména platí znění, které o vydavateli mlčí")
    void withoutTheNameThePlainWordingApplies() {
        // DN bez položky CN — a taky případ, kdy DN není vůbec.
        for (String dn : new String[] {"O=Neznámá organizace,C=CZ", null}) {
            String text = problem(sigWith(dn));
            assertFalse(text.contains("{"), "zástupné slovo zůstalo nedoplněné: " + text);
            assertFalse(text.contains("„“"), "prázdné uvozovky po nedoplněném jméně: " + text);
            assertEquals(ReasonTexts.get("3/bez-vydavatele", "problem"), text,
                    "má platit náhradní znění bez vydavatele: " + text);
        }
    }

    @Test
    @DisplayName("Certifikát s vlastním podpisem má vlastní znění a autoritu nejmenuje")
    void selfSignedCertificateHasItsOwnWording() {
        // Vydavatelem je tam podepsaný sám, takže „vydala jej autorita …" by jmenovala jeho —
        // a znělo by to jako doporučení od sebe samého. Jde přitom o jinou zprávu: za obsah
        // takového certifikátu neručí nikdo, kdežto neznámou autoritu aplikace poznat může.
        SignatureSummary sig = SigFixture.sig()
                .indication("INDETERMINATE")
                .situations(12)
                .reasons(3)
                .certIssuerDN("CN=Jan Novák,C=CZ")
                .certSelfSigned()
                .build();
        String text = problem(sig);
        assertEquals(ReasonTexts.get("3/vlastni-podpis", "problem"), text);
        assertTrue(text.contains("s vlastním podpisem"), text);
        assertFalse(text.contains("Jan Novák"), "vydavatelem je podepsaný sám: " + text);
        assertFalse(text.contains("{"), "zástupné slovo zůstalo nedoplněné: " + text);
    }

    @Test
    @DisplayName("Jméno se bere týmž způsobem jako pro odstavec o kotvě důvěry")
    void oneSourceForBothPlaces() {
        assertEquals("I.CA Qualified 2 CA/RSA 02/2016",
                AdvisorView.certIssuerName(sigWith(DN)));
        assertEquals(null, AdvisorView.certIssuerName(sigWith("O=Bez CN,C=CZ")));
    }
}
