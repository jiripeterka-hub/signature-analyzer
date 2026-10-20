package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Odstavec „Revokační informace" u certifikátu, který stojí v čele vlastního řetězu.
 *
 * <p>Nález uživatele z 21. 9. 2026 na {@code selfsigned s krivkou 3.pdf}: odstavec o něm tvrdil,
 * že „je sám kotvou důvěry a o jeho postavení rozhoduje důvěryhodný seznam" — jenže vlastnoručně
 * podepsaný certifikát, který ve vlastním úložišti není, kotvou důvěry není. Týž běh o kus výš
 * hlásí, že se řetěz nepodařilo sestavit k žádné kotvě, a Podrobný výčet u téhož certifikátu píše
 * „revokační záznam pro něj nikdo nevydává". Tři místa, dvě z nich pravdivá.
 *
 * <p>Rozhoduje situace 12, tedy že v podpisovém řetězu není jediný důvěryhodný certifikát — týž
 * test, jakým to měří Podrobný výčet ({@code ReportMapper.describeTrustAnchorLeaf} se ptá na
 * {@code !c.isTrusted()}).
 */
class SelfSignedRevocationTextTest {

    private static String text(SignatureSummary sig) {
        return AdvisorView.revocationText(sig, sig.getValidationCourse(), null, false);
    }

    @Test
    @DisplayName("Certifikát podepsaný sám sebou a mimo úložiště se za kotvu důvěry neprohlašuje")
    void selfSignedOutsideTheStoreIsNotCalledATrustAnchor() {
        SignatureSummary sig = SigFixture.sig()
                .indication("INDETERMINATE")
                .subIndication("NO_CERTIFICATE_CHAIN_FOUND")
                .situations(12)
                .reasons(3)
                .certSelfSigned()
                .revocationNotApplicable("Jiří Peterka — certifikát je podepsán sám sebou,"
                        + " revokační záznam pro něj nikdo nevydává.")
                .build();

        String text = text(sig);
        assertFalse(text.contains("kotvou důvěry"),
                "kotvou důvěry není — řetěz nevede k žádné: " + text);
        assertFalse(text.contains("důvěryhodný seznam"),
                "o jeho postavení žádný seznam nerozhoduje: " + text);
        assertTrue(text.contains("podepsaný sám sebou"), text);
        assertTrue(text.contains("nemá je kdo vydávat"),
                "má říct, proč revokační informace nejsou: " + text);
    }

    @Test
    @DisplayName("Kotva důvěry si svou větu drží")
    void aTrustAnchorKeepsItsSentence() {
        // Certifikát, který důvěryhodný seznam uvádí jako vlastní identitu služby — pečeť
        // kvalifikované služby ověřování a leckterý certifikát razítkovací autority. Tam je věta
        // o seznamu pravdivá a situace 12 indikována není.
        SignatureSummary anchor = SigFixture.sig()
                .revocationNotApplicable("Kvalifikovaná služba ověřování — certifikát je sám"
                        + " kotvou důvěry.")
                .build();
        assertTrue(text(anchor).contains("kotvou důvěry"), text(anchor));

        // A vlastnoručně podepsaný certifikát vložený do vlastního úložiště je na tom stejně:
        // kotvou důvěry je, takže situace 12 nenastane.
        SignatureSummary pinned = SigFixture.sig()
                .certSelfSigned()
                .customAnchor()
                .revocationNotApplicable("Jiří Peterka — certifikát je sám kotvou důvěry (je ve"
                        + " vlastním úložišti důvěryhodných certifikátů).")
                .build();
        assertTrue(text(pinned).contains("kotvou důvěry"), text(pinned));
    }
}
