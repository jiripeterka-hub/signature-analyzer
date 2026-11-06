package cz.digitalnikontinuita.anep.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.SignatureSummary.ExpiredCustomAnchor;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Znění poznámky ke kotvě z vlastního úložiště po konci platnosti (6. 11. 2026). */
class ExpiredAnchorTextTest {

    private static final Instant ENDED = Instant.parse("2025-01-19T08:00:00Z");

    private static String text(SigFixture.Builder builder, ExpiredCustomAnchor anchor) {
        SignatureSummary sig = builder.build();
        sig.setExpiredCustomAnchor(anchor);
        return AdvisorView.expiredAnchorText(sig);
    }

    @Test
    @DisplayName("Bez kotvy po platnosti žádná poznámka")
    void noAnchorNoText() {
        assertNull(AdvisorView.expiredAnchorText(SigFixture.sig().build()));
    }

    @Test
    @DisplayName("Autorita nebo kořen: rozhodný okamžik a odkaz do okna úložiště")
    void authorityAnchor() {
        String t = text(SigFixture.sig(), new ExpiredCustomAnchor("Autorita X", ENDED, false, null));
        assertTrue(t.startsWith("Kotva důvěry „Autorita X“"), t);
        assertTrue(t.contains("v rozhodném okamžiku"), t);
        assertTrue(t.contains(AdvisorView.TRUST_STORE_WINDOW), "odkaz stojí na názvu okna: " + t);
        assertFalse(t.contains("zneplatněn"), t);
    }

    @Test
    @DisplayName("Sám podpisový certifikát: odpadá i revokace, pečeť má vlastní pojmenování")
    void signingCertificateAnchor() {
        ExpiredCustomAnchor anchor = new ExpiredCustomAnchor("Podepsaný Y", ENDED, true, null);
        String signature = text(SigFixture.sig(), anchor);
        assertTrue(signature.startsWith("Kotvou důvěry je tu sám podpisový certifikát „Podepsaný Y“"),
                signature);
        assertTrue(signature.contains("zda nebyl zneplatněn"), signature);
        assertTrue(text(SigFixture.sig().seal(), anchor).contains("certifikát pečeti"));
    }

    @Test
    @DisplayName("Kotva razítka: mluví o okamžiku vydání razítka, ne o rozhodném okamžiku")
    void timestampAnchor() {
        String t = text(SigFixture.sig(), new ExpiredCustomAnchor("Autorita Z", ENDED, false,
                Instant.parse("2026-06-01T10:00:00Z")));
        assertTrue(t.contains("o kterou se opírá časové razítko z"), t);
        assertTrue(t.contains("v okamžiku vydání razítka"), t);
        assertFalse(t.contains("v rozhodném okamžiku"), t);
    }
}
