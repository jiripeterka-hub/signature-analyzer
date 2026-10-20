package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Certifikát prvku se v textech jmenuje podle druhu prvku.
 *
 * <p>Kontrola textů 21. 9. 2026 jich našla 22: věty per prvek psaly „podpisový certifikát"
 * natvrdo, ačkoli {@link AdvisorView#gendered} vedle nich v téže větě skloňovala „podpis / pečeť
 * / razítko". U pečeti pak odstavec „Revokační informace" začínal „U podpisového certifikátu…",
 * zatímco verdikt nad ním mluvil o pečeti.
 *
 * <p>Samostatných razítek se to netýkalo: mapování jim dává {@code validationCourse = null},
 * takže se k těmhle větám nedostanou. Střední rod je tu přesto, protože rozhoduje formát prvku,
 * ne to, kudy k větě vede cesta dnes.
 */
class ElementCertWordingTest {

    @Test
    @DisplayName("Jméno certifikátu se skloňuje podle druhu prvku")
    void theCertificateIsNamedAfterTheElement() {
        SignatureSummary signature = SigFixture.sig().build();
        SignatureSummary seal = SigFixture.sig().seal().build();
        SignatureSummary stamp = SigFixture.sig().format("TimeStampToken").build();

        assertEquals("podpisový certifikát",
                AdvisorView.elementCert(signature, AdvisorView.Case.NOMINATIV));
        assertEquals("podpisového certifikátu",
                AdvisorView.elementCert(signature, AdvisorView.Case.GENITIV));
        assertEquals("podpisovému certifikátu",
                AdvisorView.elementCert(signature, AdvisorView.Case.DATIV));
        assertEquals("podpisovém certifikátu",
                AdvisorView.elementCert(signature, AdvisorView.Case.LOKAL));

        // V ženském a středním rodě se skloňuje jen samo slovo „certifikát": 1. pád má tvar
        // „certifikát pečeti", zbylé tři „certifikátu pečeti". Mužský se mění ve všech čtyřech.
        assertEquals("certifikát pečeti",
                AdvisorView.elementCert(seal, AdvisorView.Case.NOMINATIV));
        assertEquals("certifikát razítka",
                AdvisorView.elementCert(stamp, AdvisorView.Case.NOMINATIV));
        for (AdvisorView.Case c : new AdvisorView.Case[] {
                AdvisorView.Case.GENITIV, AdvisorView.Case.DATIV, AdvisorView.Case.LOKAL }) {
            assertEquals("certifikátu pečeti", AdvisorView.elementCert(seal, c), c.toString());
            assertEquals("certifikátu razítka", AdvisorView.elementCert(stamp, c), c.toString());
        }
    }

    @Test
    @DisplayName("Odstavec o revokaci u pečeti o podpisovém certifikátu nemluví")
    void theRevocationParagraphOfASealDoesNotMentionASigningCertificate() {
        SignatureSummary seal = SigFixture.sig()
                .seal()
                .indication("INDETERMINATE")
                .situations(9)
                .revocationFrom("document")
                .build();

        String text = AdvisorView.revocationText(seal, seal.getValidationCourse(), null, false);
        assertFalse(text.contains("podpisov"), "u pečeti nemá stát „podpisový“: " + text);
        assertTrue(text.contains("certifikátu pečeti"), text);
    }

    @Test
    @DisplayName("U podpisu zůstává znění beze změny")
    void aSignatureKeepsItsWording() {
        SignatureSummary sig = SigFixture.sig()
                .indication("INDETERMINATE")
                .situations(9)
                .revocationFrom("document")
                .build();

        assertTrue(AdvisorView.revocationText(sig, sig.getValidationCourse(), null, false)
                .contains("podpisového certifikátu"));
    }
}
