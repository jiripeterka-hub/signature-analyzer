package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the state AnEP starts in.
 *
 * <p>The validation-policy switches are no longer persisted, so these field initialisers are the
 * only thing deciding how strictly a document is judged on a fresh launch. Should one of them
 * ever be flipped — or a switch be added without a deliberate default — every verdict would
 * change without a single line of validation code being touched, and nothing else in the build
 * would notice. Hence this test, which is about intent rather than about logic.
 *
 * <p>Deliberately exercises the constructor rather than {@link AppSettings#load()}: load() reads
 * the real settings file under %APPDATA%, and a test has no business depending on — or touching —
 * the developer's own configuration.
 */
class AppSettingsDefaultsTest {

    @Test
    @DisplayName("Nové nastavení ověřuje dle standardů, bez jakékoli tolerance")
    void freshSettingsVerifyAccordingToStandards() {
        AppSettings s = new AppSettings();

        assertFalse(s.isAcceptNewerOcsp(),
                "Revokační data vydaná po expiraci certifikátu se ve výchozím stavu nepřijímají.");
        assertFalse(s.isAcceptExpiredRevocationSources(),
                "Revokační informace od expirovaného vydavatele se ve výchozím stavu nepřijímají.");
        assertFalse(s.isIgnoreSha1Deprecated(),
                "Zastaralost SHA-1 se ve výchozím stavu neignoruje.");
        assertFalse(s.isAcceptPdfNotEtsi(),
                "Základní PDF podpisy mimo ETSI PAdES se ve výchozím stavu neuznávají.");
        assertTrue(s.isTimestampStandardsMode(),
                "Časová razítka se ve výchozím stavu posuzují dle standardů.");
    }

    @Test
    @DisplayName("Expertní mód je při startu vypnutý")
    void expertModeStartsOff() {
        // Detailní rozbor and Protokoly DSS are hidden until asked for. Turning this on by
        // default would put the most technical output in front of every reader, including the
        // ones it would only confuse — which is the whole reason the mode exists.
        assertFalse(new AppSettings().isExpertMode());
    }

    @Test
    @DisplayName("Výchozí nastavení nemá zadané datum ověření")
    void freshSettingsHaveNoCustomValidationTime() {
        AppSettings s = new AppSettings();
        assertFalse(s.isUseCustomValidationTime());
        assertNull(s.getCustomValidationTime());
    }

    @Test
    @DisplayName("Po ověření se validační politika vrátí přesně tam, odkud vyšla")
    void resetPutsPolicyBackToTheFreshState() {
        // Porovnává se s čerstvou instancí, ne s napsanými hodnotami: kdyby se výchozí stav někdy
        // změnil, nesmí se dát změnit jen na jednom ze dvou míst, aniž by to tohle zachytilo.
        AppSettings fresh = new AppSettings();
        AppSettings s = new AppSettings();

        s.setAcceptNewerOcsp(true);
        s.setAcceptExpiredRevocationSources(true);
        s.setIgnoreSha1Deprecated(true);
        s.setAcceptPdfNotEtsi(true);
        s.setTimestampStandardsMode(false);
        s.setUseCustomValidationTime(true);
        s.setCustomValidationTime(java.time.LocalDateTime.of(2019, 6, 1, 12, 0));

        s.resetValidationPolicy();

        assertEquals(fresh.isAcceptNewerOcsp(), s.isAcceptNewerOcsp());
        assertEquals(fresh.isAcceptExpiredRevocationSources(), s.isAcceptExpiredRevocationSources());
        assertEquals(fresh.isIgnoreSha1Deprecated(), s.isIgnoreSha1Deprecated());
        assertEquals(fresh.isAcceptPdfNotEtsi(), s.isAcceptPdfNotEtsi());
        assertEquals(fresh.isTimestampStandardsMode(), s.isTimestampStandardsMode());
        assertEquals(fresh.isUseCustomValidationTime(), s.isUseCustomValidationTime());
        assertNull(s.getCustomValidationTime());
    }

    @Test
    @DisplayName("Reset po ověření nesahá na expertní mód ani na nakonfigurovaný materiál")
    void resetLeavesViewAndStoresAlone() {
        // Hranice úkonu. Expertní mód neurčuje, jak přísně se soudí, jen co je vidět — a protože
        // reset spouští ověření, musel by se jinak zaškrtávat před každým jednotlivým dokumentem.
        // TSA je konfigurace zdroje, ne úleva; kdyby ji ověření mazalo, přišel by uživatel při
        // prvním ověření o nastavenou službu.
        AppSettings s = new AppSettings();
        s.setExpertMode(true);
        TsaProfile tsa = new TsaProfile();
        tsa.setName("I.CA");
        tsa.setUrl("http://tsa.ica.cz");
        s.setTsaProfiles(java.util.List.of(tsa));
        s.setSelectedTsaIndex(0);

        s.resetValidationPolicy();

        assertTrue(s.isExpertMode(), "Expertní mód se ověřením nevypíná.");
        assertEquals(1, s.getTsaProfiles().size(), "Nastavená TSA zůstává.");
        assertEquals(0, s.getSelectedTsaIndex());
    }
}
