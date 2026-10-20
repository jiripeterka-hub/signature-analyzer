package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Deviations;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Run;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prázdný výčet rozhodujících ústupků znamená dvojí a texty to nesmí splést.
 *
 * <p>Nález z 19. 9. 2026 nad jedním reportem: PAdES-BES, jehož jediným revokačním údajem
 * byl seznam CRL vydaný pět let <b>před</b> vydáním podpisového certifikátu. Přísně skončí
 * {@code INDETERMINATE / CERTIFICATE_CHAIN_GENERAL_FAILURE} ({@code BBB_XCV_IARDPFC_ANS}),
 * s ústupky {@code TOTAL_PASSED} — verdikt tedy na ústupku stojí. Karta přesto napsala
 * „na výsledku ověření podpisu se ale neprojevily — bez nich by ověření dopadlo stejně“
 * a o kus výš „bez jakýchkoli ústupků“.
 *
 * <p>Příčina není v měření, nýbrž v tom, že se neptalo: výčet vznikl odvozením, a to hledá čtyři
 * předem vyjmenované tvary vady (post-expirační údaj, expirovaný vydavatel, SHA-1, PDF-NOT-ETSI).
 * Revokační údaj vydaný před vznikem certifikátu není ani jeden z nich, takže odvození nenašlo
 * nic — a prázdno se vysázelo jako zjištění.
 */
class UnmeasuredConcessionsTest {

    private static AppSettings lenient() {
        AppSettings s = new AppSettings();
        for (ReportMapper.Relaxation r : ReportMapper.Relaxation.values()) r.setEnabled(s, true);
        return s;
    }

    private static AppSettings strict() {
        AppSettings s = new AppSettings();
        for (ReportMapper.Relaxation r : ReportMapper.Relaxation.values()) r.setEnabled(s, false);
        return s;
    }

    @Test
    @DisplayName("Zapnuté ústupky bez měření: blok o nich nesmí tvrdit nic")
    void enabledButUnmeasured() {
        SignatureSummary sig = SigFixture.sig().decisiveSettingsMeasured(false).build();
        assertTrue(AdvisorView.concessionsUnmeasured(sig, lenient()));
    }

    @Test
    @DisplayName("Změřeno a nic nebylo potřeba: to už je zjištění")
    void measuredAndNoneNeeded() {
        SignatureSummary sig = SigFixture.sig().decisiveSettingsMeasured(true).build();
        assertFalse(AdvisorView.concessionsUnmeasured(sig, lenient()));
    }

    @Test
    @DisplayName("Bez zapnutých ústupků není o čem mlčet")
    void nothingEnabled() {
        // „Ověřování proběhlo zcela podle technických standardů, bez jakýchkoli ústupků“ je nad
        // během, kde žádný zaškrtnutý nebyl, pravda i bez měření — a měřit se tam nemá co.
        SignatureSummary sig = SigFixture.sig().decisiveSettingsMeasured(false).build();
        assertFalse(AdvisorView.concessionsUnmeasured(sig, strict()));
    }

    @Test
    @DisplayName("Kontinuita: nezměřený kladný verdikt jmenuje zapnuté, ne prázdno")
    void continuityNamesEnabledWhenUnmeasured() {
        // Blok o standardech se ptá dev.lenient(). Do 19. 9. 2026 stačilo k „změřeno“ samotné
        // TOTAL_PASSED, takže nad nezměřeným během vyšel prázdný výčet, blok spadl do větve
        // „Odchylky se na závěru neprojevily“ a sliboval, že cizí validátor dojde k témuž.
        List<String> enabled = List.of(ReportMapper.Relaxation.NEWER_OCSP.settingName());
        Run run = new Run(true, enabled, null);

        SignatureSummary unmeasured = SigFixture.sig()
                .certNotAfter(SigFixture.FUTURE).decisiveSettingsMeasured(false).build();
        Deviations dev = ContinuityHorizon.deviations(unmeasured, run, false, "TOTAL_PASSED");
        assertFalse(dev.measured());
        assertEquals(enabled, dev.namedLenient());
        assertTrue(dev.lenient());

        SignatureSummary measured = SigFixture.sig()
                .certNotAfter(SigFixture.FUTURE).decisiveSettingsMeasured(true).build();
        Deviations ok = ContinuityHorizon.deviations(measured, run, false, "TOTAL_PASSED");
        assertTrue(ok.measured());
        assertTrue(ok.namedLenient().isEmpty());
        assertFalse(ok.lenient());
    }
}
