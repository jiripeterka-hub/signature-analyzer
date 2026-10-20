package cz.digitalnikontinuita.anep.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Application settings persisted to %APPDATA%/SignatureAnalyzer/settings.properties.
 */
public class AppSettings {

    private static final Path SETTINGS_FILE;

    static {
        SETTINGS_FILE = AppDirs.dataDir().resolve("settings.properties");
    }

    // The keys acceptNewerOcsp, acceptExpiredRevocationSources, timestampStandardsMode,
    // ignoreSha1Deprecated and acceptPdfNotEtsi were written by earlier versions. They are
    // neither read nor written any more; the first save() drops them from the file.
    private static final String KEY_TSA_COUNT                         = "tsa.count";
    private static final String KEY_TSA_SELECTED                      = "tsa.selectedIndex";

    // Výchozí stav validační politiky. Pojmenovaný proto, že ho čtou dvě místa — počáteční
    // hodnoty polí níž a resetValidationPolicy() —, a kdyby se rozešly, návrat „do výchozího
    // stavu" by vracel jinam, než odkud se vyšlo.
    private static final boolean DEFAULT_ACCEPT_NEWER_OCSP                 = false;
    private static final boolean DEFAULT_ACCEPT_EXPIRED_REVOCATION_SOURCES = false;
    private static final boolean DEFAULT_TIMESTAMP_STANDARDS_MODE          = true;
    private static final boolean DEFAULT_IGNORE_SHA1_DEPRECATED            = false;
    private static final boolean DEFAULT_ACCEPT_PDF_NOT_ETSI               = false;
    private static final boolean DEFAULT_USE_CUSTOM_VALIDATION_TIME        = false;

    private boolean acceptNewerOcsp                = DEFAULT_ACCEPT_NEWER_OCSP;
    private boolean acceptExpiredRevocationSources = DEFAULT_ACCEPT_EXPIRED_REVOCATION_SOURCES;
    private boolean timestampStandardsMode         = DEFAULT_TIMESTAMP_STANDARDS_MODE;
    private boolean ignoreSha1Deprecated           = DEFAULT_IGNORE_SHA1_DEPRECATED;
    private boolean acceptPdfNotEtsi               = DEFAULT_ACCEPT_PDF_NOT_ETSI;
    /**
     * Shows the Detailní rozbor and Protokoly DSS tabs. Off at every launch, like the policy
     * switches and for a related reason: even an experienced reader needs those two only now and
     * then, so having to ask for them costs little, while showing them unasked puts the most
     * technical output in front of readers it would only confuse.
     */
    private boolean expertMode                     = false;

    private boolean useCustomValidationTime        = DEFAULT_USE_CUSTOM_VALIDATION_TIME;
    private LocalDateTime customValidationTime     = null;

    private List<TsaProfile> tsaProfiles           = new ArrayList<>();
    private int selectedTsaIndex                   = -1;

    // ---- accessors ----

    public boolean isAcceptNewerOcsp()                  { return acceptNewerOcsp; }
    public boolean isAcceptExpiredRevocationSources()   { return acceptExpiredRevocationSources; }
    public boolean isTimestampStandardsMode()           { return timestampStandardsMode; }
    public boolean isIgnoreSha1Deprecated()             { return ignoreSha1Deprecated; }
    public boolean isAcceptPdfNotEtsi()                 { return acceptPdfNotEtsi; }
    public boolean isExpertMode()                       { return expertMode; }
    public boolean isUseCustomValidationTime()          { return useCustomValidationTime; }
    public LocalDateTime getCustomValidationTime()      { return customValidationTime; }

    public void setAcceptNewerOcsp(boolean v)                 { this.acceptNewerOcsp = v; }
    public void setAcceptExpiredRevocationSources(boolean v)  { this.acceptExpiredRevocationSources = v; }
    public void setTimestampStandardsMode(boolean v)          { this.timestampStandardsMode = v; }
    public void setIgnoreSha1Deprecated(boolean v)            { this.ignoreSha1Deprecated = v; }
    public void setAcceptPdfNotEtsi(boolean v)                { this.acceptPdfNotEtsi = v; }
    public void setExpertMode(boolean v)                      { this.expertMode = v; }
    public void setUseCustomValidationTime(boolean v)         { this.useCustomValidationTime = v; }
    public void setCustomValidationTime(LocalDateTime v)      { this.customValidationTime = v; }

    /**
     * Returns the custom validation time as a {@link Date} for DSS, or {@code null}
     * when the feature is disabled or no time has been set.
     */
    public Date getValidationDate() {
        if (!useCustomValidationTime || customValidationTime == null) return null;
        return Date.from(customValidationTime.atZone(ZoneId.systemDefault()).toInstant());
    }

    // ---- TSA accessors ----

    public List<TsaProfile> getTsaProfiles()     { return tsaProfiles; }
    public int getSelectedTsaIndex()             { return selectedTsaIndex; }
    /*
     * Nastavení „při nepodporovaném otisku nabídnout SHA-256" tu bývalo. Zmizelo, protože se
     * ptalo na to, co se dá zjistit: TimestampingService.probe() vyzkouší při potvrzení zdroje
     * SHA-512, pak SHA-384, pak SHA-256, a nastavení pak nabízí jen to, na co zdroj odpověděl.
     * Klíč tsa.hashFallback může v settings.properties zůstat, nikdo ho nečte.
     */

    /** Returns the active TSA profile, or {@code null} if none is selected. */
    public TsaProfile getSelectedTsa() {
        if (selectedTsaIndex < 0 || selectedTsaIndex >= tsaProfiles.size()) return null;
        return tsaProfiles.get(selectedTsaIndex);
    }

    /**
     * Srovná zapamatovaný konec platnosti razítkovacího certifikátu se skutečností z právě
     * získaného razítka a případně jej opraví.
     *
     * <p>Volá se po každém úspěšném orazítkování. Autorita si certifikát obmění, kdy uzná za
     * vhodné, a nikomu to nehlásí — jediný okamžik, kdy se to dá poznat, je razítko, které
     * přijde. Zapisuje se do nastavení hned, protože ten údaj má být k dispozici i po zavření
     * aplikace a dalšího razítka se nikdo nedočká dřív, než ho bude potřebovat text v Analýze.
     *
     * <p>Profil se hledá podle koncového bodu, ne podle jména či pořadí: uživatel může zdroj mezi
     * dvěma použitími přejmenovat nebo přesunout v seznamu, a byla by to táž autorita.
     *
     * @param used     profil, kterým se razítkovalo
     * @param observed konec platnosti odečtený z razítka; null, když se ho nepodařilo přečíst
     * @return true, když se zapamatovaný údaj změnil
     */
    public boolean rememberTsaCertNotAfter(TsaProfile used, java.util.Date observed) {
        if (used == null || observed == null) return false;
        boolean changed = false;
        for (TsaProfile t : tsaProfiles) {
            if (!t.sameEndpointAs(used)) continue;
            if (observed.equals(t.getCertNotAfter())) continue;
            t.setCertNotAfter(observed);
            changed = true;
        }
        // I profil, který v seznamu není (kopie z dialogu), ať nese aktuální hodnotu.
        used.setCertNotAfter(observed);
        if (changed) {
            // Nepovedený zápis nesmí shodit akci, která právě dopadla dobře; údaj se doplní
            // při dalším razítku.
            try {
                save();
            } catch (Exception e) {
                System.err.println("[AppSettings] nepodařilo se uložit konec platnosti TSA: "
                        + e.getMessage());
            }
        }
        return changed;
    }

    /**
     * Zapamatuje klíč autority zjištěný ze skutečného razítka — protějšek
     * {@link #rememberTsaCertNotAfter}, ze stejného důvodu: autorita klíč obmění, aniž by to
     * hlásila. Profil se hledá podle koncového bodu.
     *
     * @return true, když se zapamatovaný údaj změnil
     */
    public boolean rememberTsaQuality(TsaProfile used, TsaProfile.Quality observed) {
        // Třetí z rodiny: kvalifikovanost zjištěná z razítka, které úkon právě přidal, a z ověření
        // výsledného souboru proti důvěryhodným seznamům. Měří se sice už při zadání zdroje, ale
        // teprve od 18. 9. 2026 — zdroje založené dřív ji měly „nezjištěnou" napořád a záznam
        // o úkonu to tak i psal (nález uživatele 13. 10. 2026 u zdroje Sectigo Qualified).
        // „Nevím" nic nepřepisuje: neměřilo se, nebo se nebylo podle čeho.
        if (used == null || observed == null || observed == TsaProfile.Quality.UNKNOWN) return false;
        boolean changed = false;
        for (TsaProfile t : tsaProfiles) {
            if (!t.sameEndpointAs(used)) continue;
            if (observed == t.getQuality()) continue;
            t.setQuality(observed);
            changed = true;
        }
        used.setQuality(observed);
        if (changed) {
            try {
                save();
            } catch (Exception e) {
                System.err.println("[AppSettings] nepodařilo se uložit kvalifikovanost TSA: "
                        + e.getMessage());
            }
        }
        return changed;
    }

    public boolean rememberTsaSigningKey(TsaProfile used, TsaSigningKey observed) {
        if (used == null || observed == null) return false;
        boolean changed = false;
        for (TsaProfile t : tsaProfiles) {
            if (!t.sameEndpointAs(used)) continue;
            if (observed.equals(t.getSigningKey())) continue;
            t.setSigningKey(observed);
            changed = true;
        }
        used.setSigningKey(observed);
        if (changed) {
            try {
                save();
            } catch (Exception e) {
                System.err.println("[AppSettings] nepodařilo se uložit klíč TSA: "
                        + e.getMessage());
            }
        }
        return changed;
    }

    public void setTsaProfiles(List<TsaProfile> profiles) { this.tsaProfiles = new ArrayList<>(profiles); }
    public void setSelectedTsaIndex(int index)            { this.selectedTsaIndex = index; }

    // ---- persistence ----

    public static AppSettings load() {
        AppSettings s = new AppSettings();
        if (!Files.exists(SETTINGS_FILE)) return s;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(SETTINGS_FILE)) {
            p.load(in);
        } catch (IOException e) {
            return s;
        }
        // Validation-policy switches are deliberately NOT read back. They keep the field
        // initialisers, which spell out verification according to the standards, so every launch
        // starts there — see the note on save() for why.

        int count = integer(p, KEY_TSA_COUNT, 0);
        for (int i = 0; i < count; i++) {
            TsaProfile t = new TsaProfile(
                    p.getProperty("tsa." + i + ".name",          ""),
                    p.getProperty("tsa." + i + ".url",           ""),
                    p.getProperty("tsa." + i + ".username",      ""),
                    p.getProperty("tsa." + i + ".password",      ""),
                    p.getProperty("tsa." + i + ".hashAlgorithm", TsaProfile.HASH_SHA512)
            );
            // Konec platnosti razítkovacího certifikátu; chybí u zdrojů založených dřív, než se
            // začal pamatovat, a doplní se při prvním použití.
            long notAfter = 0;
            try {
                notAfter = Long.parseLong(p.getProperty("tsa." + i + ".certNotAfter", "0"));
            } catch (NumberFormatException ignored) { }
            if (notAfter > 0) t.setCertNotAfter(new java.util.Date(notAfter));
            String hashes = p.getProperty("tsa." + i + ".supportedHashes", "");
            if (!hashes.isBlank()) t.setSupportedHashes(java.util.List.of(hashes.split(",")));
            t.setClientCertPath(p.getProperty("tsa." + i + ".clientCertPath", ""));
            t.setClientCertPassword(p.getProperty("tsa." + i + ".clientCertPassword", ""));
            // Nezjištěno je správná odpověď i pro zdroje založené dřív, než se kvalifikovanost
            // začala měřit, i pro hodnotu, které se v souboru někdo dotkl. Texty na ni umějí
            // odpovědět tak, jak mluvily vždycky.
            try {
                t.setQuality(TsaProfile.Quality.valueOf(
                        p.getProperty("tsa." + i + ".quality", "UNKNOWN")));
            } catch (IllegalArgumentException ignored) { }
            t.setSigningKey(TsaSigningKey.parse(p.getProperty("tsa." + i + ".signingKey", "")));
            s.tsaProfiles.add(t);
        }
        s.selectedTsaIndex = integer(p, KEY_TSA_SELECTED, -1);
        if (s.selectedTsaIndex >= s.tsaProfiles.size()) s.selectedTsaIndex = -1;
        return s;
    }

    /**
     * Vrátí validační politiku do výchozího stavu — volá se po každém dokončeném ověření.
     *
     * <p>Ústupek je vyšetřovací prostředek pro jeden konkrétní soubor, ne způsob, jak se od téhle
     * chvíle soudí všechno ostatní. Že se nezapisuje do souboru, chránilo jen před přenosem mezi
     * spuštěními; uvnitř jednoho běhu zaškrtnutí zůstávalo v platnosti, dokud si na ně někdo
     * nevzpomněl, a další dokument se pak posoudil mírněji, aniž by to bylo na výsledku znát.
     * Životnost je proto jedno ověření. Totéž pravidlo tu už dřív platilo pro zadaný čas ověření,
     * jen se prosazovalo v {@code MainView} zvlášť.
     *
     * <p>Expertní mód se nevrací: neurčuje, jak přísně se dokument soudí, jen co je z výsledku
     * vidět. Nevrací se ani úložiště a služby (vlastní certifikáty a CRL, TSA, EUTL) — ty říkají,
     * jaký materiál je k dispozici, a ze stejného důvodu, pro který se ukládají, se nesmí ztrácet.
     * Viz {@link #save()}.
     */
    public void resetValidationPolicy() {
        acceptNewerOcsp                = DEFAULT_ACCEPT_NEWER_OCSP;
        acceptExpiredRevocationSources = DEFAULT_ACCEPT_EXPIRED_REVOCATION_SOURCES;
        timestampStandardsMode         = DEFAULT_TIMESTAMP_STANDARDS_MODE;
        ignoreSha1Deprecated           = DEFAULT_IGNORE_SHA1_DEPRECATED;
        acceptPdfNotEtsi               = DEFAULT_ACCEPT_PDF_NOT_ETSI;
        useCustomValidationTime        = DEFAULT_USE_CUSTOM_VALIDATION_TIME;
        customValidationTime           = null;
    }

    public void save() throws IOException {
        Files.createDirectories(SETTINGS_FILE.getParent());
        Properties p = new Properties();
        // Validation-policy switches are not written. The verdict has to depend on the document,
        // not on an invisible leftover from a previous session: a relaxation is an investigative
        // measure taken for one particular file, and carrying it over would quietly turn the
        // exception into the default. Nothing is lost by it — the Analýza tab offers a one-click
        // lenient re-verification that turns them on for a single run.
        //
        // Stores and service configuration below are a different matter and do persist: they say
        // what material is available, not how strictly it is judged.
        p.setProperty(KEY_TSA_COUNT, Integer.toString(tsaProfiles.size()));
        for (int i = 0; i < tsaProfiles.size(); i++) {
            TsaProfile t = tsaProfiles.get(i);
            p.setProperty("tsa." + i + ".name",          t.getName());
            p.setProperty("tsa." + i + ".url",           t.getUrl());
            p.setProperty("tsa." + i + ".username",      t.getUsername());
            p.setProperty("tsa." + i + ".password",      t.getPassword());
            p.setProperty("tsa." + i + ".hashAlgorithm", t.getHashAlgorithm());
            p.setProperty("tsa." + i + ".certNotAfter",
                    Long.toString(t.getCertNotAfter() != null ? t.getCertNotAfter().getTime() : 0));
            p.setProperty("tsa." + i + ".supportedHashes",
                    String.join(",", t.getSupportedHashes()));
            p.setProperty("tsa." + i + ".clientCertPath",     t.getClientCertPath());
            p.setProperty("tsa." + i + ".clientCertPassword", t.getClientCertPassword());
            p.setProperty("tsa." + i + ".quality",            t.getQuality().name());
            p.setProperty("tsa." + i + ".signingKey",
                    t.getSigningKey() != null ? t.getSigningKey().persist() : "");
        }
        p.setProperty(KEY_TSA_SELECTED,      Integer.toString(selectedTsaIndex));

        try (OutputStream out = Files.newOutputStream(SETTINGS_FILE)) {
            p.store(out, "Analyzátor podpisů — nastavení validační politiky");
        }
    }

    private static boolean bool(Properties p, String key, boolean defaultVal) {
        String v = p.getProperty(key);
        return v != null ? Boolean.parseBoolean(v) : defaultVal;
    }

    private static int integer(Properties p, String key, int defaultVal) {
        String v = p.getProperty(key);
        if (v == null) return defaultVal;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }
}
