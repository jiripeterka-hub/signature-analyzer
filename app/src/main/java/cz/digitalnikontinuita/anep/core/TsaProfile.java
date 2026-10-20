package cz.digitalnikontinuita.anep.core;

/**
 * Configuration for a single Time Stamping Authority (TSA) server.
 * Passwords are stored in plain text in the settings file.
 */
public class TsaProfile {

    public static final String HASH_SHA256 = "SHA256";
    public static final String HASH_SHA384 = "SHA384";
    public static final String HASH_SHA512 = "SHA512";

    /**
     * Čím se autorita přesvědčí, že s ní mluví oprávněný žadatel.
     *
     * <p>Neukládá se, nýbrž odvozuje z vyplněných polí, takže v nastavení nemůže vzniknout stav,
     * ve kterém je zvolen jeden způsob a vyplněn druhý. Editační okno při přepnutí způsobu pole
     * toho nezvoleného vyprázdní, a tím je rozhodnuto.
     */
    public enum Auth {
        /** Veřejný zdroj — neposílá se nic. */
        NONE,
        /** HTTP Basic: jméno a heslo. */
        BASIC,
        /** Klientský certifikát v TLS — úložiště PKCS#12 nebo JKS, chráněné heslem. */
        CERTIFICATE
    }

    /**
     * Vydává tahle autorita <b>kvalifikovaná</b> časová razítka?
     *
     * <p>Není to tvrzení uživatele, nýbrž <b>měření</b>: sonda si od zdroje stejně vyžádá jedno
     * razítko (viz {@link #certNotAfter}) a to se pak posoudí touž cestou, kterou aplikace
     * posuzuje razítka v dokumentech — podle evropských důvěryhodných seznamů. Zaškrtávátko
     * v nastavení by byl názor toho, kdo zdroj zadával, a texty o dokumentu by stály na něm.
     *
     * <p>Tři hodnoty, protože „nezjištěno" je skutečný stav a mlčet o něm by znamenalo hádat:
     * nastane u zdrojů založených dřív, než se to začalo měřit, i tehdy, když se nepodařilo
     * načíst důvěryhodné seznamy.
     */
    public enum Quality {
        /** Zatím se neměřilo, nebo měření neproběhlo. */
        UNKNOWN,
        /** Za razítkem stojí kvalifikovaná služba podle důvěryhodných seznamů (QTSA). */
        QUALIFIED,
        /** Razítko kvalifikované není — typicky testovací (DEMO) zdroj. */
        UNQUALIFIED
    }

    private String name;
    private String url;
    private String username;
    private String password;
    private String hashAlgorithm;

    /**
     * Cesta k úložišti s klientským certifikátem (PKCS#12, tedy .p12 nebo .pfx, případně JKS).
     *
     * <p>Ukládá se <b>cesta, ne obsah</b>. Soubor s privátním klíčem patří tam, kam ho uživatel
     * dal; nastavení aplikace není úložiště klíčů a kopírovat ho do %APPDATA% by znamenalo
     * rozmnožit něco, co se má držet na jednom místě. Cenou je, že přesunutý soubor přestane
     * fungovat — ohlásí se to při nejbližší žádosti o razítko a v nastavení se dá cesta opravit.
     */
    private String clientCertPath = "";

    /** Heslo k tomu úložišti. V nastavení leží otevřeně, stejně jako heslo k Basic. */
    private String clientCertPassword = "";

    /** Co se naměřilo o kvalifikovanosti; viz {@link Quality}. */
    private Quality quality = Quality.UNKNOWN;

    /**
     * Kdy skončí platnost certifikátu, kterým tahle autorita razítka podepisuje — a tím i doba,
     * na kterou razítko od ní posune ověřitelnost dokumentu.
     *
     * <p>Pamatuje se proto, že se na to Analýza potřebuje zeptat, **než** se cokoli stane: nabídku
     * uchování, prodloužení či přerazítkování má popsat i s datem, do kdy se ověřitelnost
     * protáhne, a to se jinak dá zjistit jedině tak, že se razítko nejdřív pořídí. Získává se
     * poprvé při potvrzení zdroje v nastavení jedním vyžádaným razítkem a pak se při každém
     * skutečném použití srovná se skutečností — autorita si certifikát obmění, aniž by se ptala.
     *
     * <p>Null znamená „ještě nezjištěno", ne „nemá konec platnosti". Text, který na tom stojí,
     * se pak nezobrazí; hádat by bylo horší než mlčet.
     */
    private java.util.Date certNotAfter;

    /**
     * Otiskové funkce, na které tahle autorita odpověděla razítkem — nabídka v nastavení.
     *
     * <p>Je to **výčet, ne strop**, a to je poučení z měření. Vypadalo rozumně předpokládat, že
     * kdo zvládne silnější funkci, zvládne i slabší, a nabízet proto všechno od stropu dolů.
     * I.CA to vyvrací: na SHA-512 odpoví razítkem, na SHA-384 odmítnutím
     * („Message digest algorithm is not supported", PKIFailureInfo 0x80) a na SHA-256 zase
     * razítkem. Podpora tedy souvislá být nemusí a odvozovat ji z jedné odpovědi znamená
     * nabídnout funkci, kterou autorita odmítne.
     *
     * <p>Prázdné znamená „nezjištěno" a nabídka pak zůstává úplná; je to stav zdrojů založených
     * dřív, než se to začalo zjišťovat, i těch, u nichž se autorita neozvala.
     */
    private java.util.List<String> supportedHashes = java.util.List.of();

    /**
     * Čím autorita podepisuje razítka — algoritmus a délka klíče. Zjišťuje se testem při
     * zadání zdroje a pak z každého skutečného razítka (viz {@link TsaSigningKey#observer}).
     * Slouží upozornění v nastavení, že klíč přestane být podle ETSI TS 119 312 spolehlivý
     * dřív, než skončí certifikát autority (doplněno 5. 10. 2026).
     *
     * <p>Null znamená „nezjištěno“; upozornění se pak nezobrazí.
     */
    private TsaSigningKey signingKey;

    public TsaProfile() {
        this("", "", "", "", HASH_SHA512);
    }

    public TsaProfile(String name, String url, String username, String password, String hashAlgorithm) {
        this.name          = name          != null ? name          : "";
        this.url           = url           != null ? url           : "";
        this.username      = username      != null ? username      : "";
        this.password      = password      != null ? password      : "";
        this.hashAlgorithm = (hashAlgorithm != null && !hashAlgorithm.isBlank()) ? hashAlgorithm : HASH_SHA512;
    }

    public TsaProfile(TsaProfile other) {
        this(other.name, other.url, other.username, other.password, other.hashAlgorithm);
        this.certNotAfter = other.certNotAfter;
        this.supportedHashes = other.supportedHashes;
        this.clientCertPath = other.clientCertPath;
        this.clientCertPassword = other.clientCertPassword;
        this.quality = other.quality;
        this.signingKey = other.signingKey;
    }

    /** Všechny tři funkce v pořadí od nejsilnější — pořadí nabídky i pořadí zkoušení. */
    public static java.util.List<String> allHashAlgorithms() {
        return java.util.List.of(HASH_SHA512, HASH_SHA384, HASH_SHA256);
    }

    /** „SHA512“ → „SHA-512“ (i jména DSS); co tvar nemá, vrátí beze změny. */
    public static String displayHash(String name) {
        return name == null ? "" : name.replaceFirst("^SHA(1|\\d{3})$", "SHA-$1");
    }

    /**
     * Otiskové funkce, které smí nastavení nabídnout.
     *
     * <p>Zjištěný výčet, a dokud se nic nezjistilo, všechny tři: neznalost není důvod uživatele
     * omezovat, jen důvod se nezaručovat.
     */
    public java.util.List<String> offeredHashAlgorithms() {
        return supportedHashes.isEmpty() ? allHashAlgorithms() : supportedHashes;
    }

    public static TsaProfile sectigoQualified() {
        return new TsaProfile("Sectigo Qualified", "http://timestamp.sectigo.com/qualified", "", "", HASH_SHA512);
    }

    /**
     * Testovací razítkovací autorita PostSignum, přihlášení jménem a heslem.
     *
     * <p>Údaje jsou veřejné — PostSignum je zveřejňuje přímo pro tenhle účel (postsignum.cz →
     * Kvalifikovaná časová razítka → Testovací časová razítka) — takže je nemá smysl chtít po
     * uživateli. Razítka odtud <b>nejsou důvěryhodná</b> a nemají být; aplikace si to zjistí sama
     * ({@link Quality}) a texty se tomu přizpůsobí.
     *
     * <p>SHA-256 proto, že silnější funkce tahle služba nebere. Sonda si to ověří tak jako tak;
     * tohle jen ušetří dvě žádosti, o kterých se předem ví, jak dopadnou.
     */
    public static TsaProfile postSignumDemoBasic() {
        return new TsaProfile("PostSignum DEMO (jméno a heslo)",
                "https://demo.postsignum.cz:444/DEMO-TSS/HttpTspServer/",
                "demoTSA", "demoTSA2010", HASH_SHA256);
    }

    /**
     * Táž testovací autorita, ale s přihlášením <b>klientským certifikátem</b>.
     *
     * <p>Je to <b>jiná adresa, ne jiný protokol</b>: tentýž server, port 443 místo 444. Certifikát
     * a heslo k němu doplní uživatel — PostSignum bere kterýkoli komerční certifikát od sebe nebo
     * od DEMO CA PostSignum VCA 4 či 5, osobní i serverový.
     */
    public static TsaProfile postSignumDemoCertificate() {
        return new TsaProfile("PostSignum DEMO (certifikát)",
                "https://demo.postsignum.cz/DEMO-TSS/HttpTspServer/",
                "", "", HASH_SHA256);
    }

    public String getName()          { return name; }
    public String getUrl()           { return url; }
    public String getUsername()      { return username; }
    public String getPassword()      { return password; }
    public String getHashAlgorithm() { return hashAlgorithm; }
    public java.util.Date getCertNotAfter() { return certNotAfter; }
    public java.util.List<String> getSupportedHashes() { return supportedHashes; }
    public String getClientCertPath()     { return clientCertPath; }
    public String getClientCertPassword() { return clientCertPassword; }
    public Quality getQuality()           { return quality; }
    public TsaSigningKey getSigningKey()  { return signingKey; }

    public void setName(String v)          { this.name          = v != null ? v : ""; }
    public void setUrl(String v)           { this.url           = v != null ? v : ""; }
    public void setUsername(String v)      { this.username      = v != null ? v : ""; }
    public void setPassword(String v)      { this.password      = v != null ? v : ""; }
    public void setHashAlgorithm(String v) { this.hashAlgorithm = (v != null && !v.isBlank()) ? v : HASH_SHA512; }
    public void setCertNotAfter(java.util.Date v) { this.certNotAfter = v; }
    public void setClientCertPath(String v)     { this.clientCertPath     = v != null ? v : ""; }
    public void setClientCertPassword(String v) { this.clientCertPassword = v != null ? v : ""; }
    public void setQuality(Quality v)           { this.quality = v != null ? v : Quality.UNKNOWN; }
    public void setSigningKey(TsaSigningKey v)  { this.signingKey = v; }

    /**
     * Zapíše zjištěný výčet a přesadí nastavenou funkci, není-li mezi nimi.
     *
     * <p>Ta druhá půlka je celý smysl: profil vzniká s SHA-512, protože se nedá vědět předem,
     * a autorita, která ji nebere, by jinak zůstala nastavená na funkci, kterou odmítá — chyba
     * by přišla až uprostřed uchování. Přesazuje se na nejsilnější podporovanou, protože slabší
     * otisk je ústupek a ten se nemá dělat větší, než je nutné.
     */
    public void setSupportedHashes(java.util.List<String> v) {
        if (v == null || v.isEmpty()) { this.supportedHashes = java.util.List.of(); return; }
        java.util.List<String> ordered = new java.util.ArrayList<>();
        for (String h : allHashAlgorithms()) if (v.contains(h)) ordered.add(h);
        this.supportedHashes = java.util.List.copyOf(ordered);
        if (!ordered.isEmpty() && !ordered.contains(hashAlgorithm)) {
            this.hashAlgorithm = ordered.get(0);
        }
    }

    /** Přihlašuje se jménem a heslem? Jméno stačí — autorita s prázdným heslem existuje. */
    public boolean hasAuth() { return !username.isBlank(); }

    /** Přihlašuje se klientským certifikátem? */
    public boolean hasClientCert() { return !clientCertPath.isBlank(); }

    /**
     * Způsob přihlášení, odvozený z vyplněných polí.
     *
     * <p>Certifikát má přednost před jménem. Vyplněné obojí je stav, který editační okno vytvořit
     * nedovolí, ale settings.properties se dá upravit ručně a pak musí být rozhodnuto jednoznačně.
     */
    public Auth auth() {
        if (hasClientCert()) return Auth.CERTIFICATE;
        if (hasAuth()) return Auth.BASIC;
        return Auth.NONE;
    }

    /**
     * Druh úložiště podle přípony souboru.
     *
     * <p>PKCS#12 je dnešní podoba (.p12, .pfx) a je i výchozí odpovědí: soubor bez přípony nebo
     * s cizí je spíš PKCS#12 než cokoli jiného, a plete-li se, ohlásí to Java při otevírání.
     * JKS se jmenuje proto, že se s ním v tuzemsku pořád ještě potkat lze.
     */
    public String clientCertStoreType() {
        String lower = clientCertPath.toLowerCase(java.util.Locale.ROOT);
        return (lower.endsWith(".jks") || lower.endsWith(".keystore")) ? "JKS" : "PKCS12";
    }

    /**
     * Zda dva profily míří na tutéž autoritu, tedy zda si smějí zapamatované datum předat.
     *
     * <p>Rozhoduje adresa a přihlášení, ne jméno: přejmenovat zdroj v nastavení neznamená, že se
     * začne ptát někoho jiného, kdežto změna adresy ano — a s ní přestává platit i to, co se
     * o certifikátu vědělo.
     *
     * <p>Mezi přihlašovací údaje patří i klientský certifikát. Přepnutí zdroje z hesla na
     * certifikát je u PostSignum DEMO <b>změna adresy</b> (port 444 → 443), takže by se poznalo
     * i bez toho — jinde ale být nemusí a naměřené údaje patří k tomu, kdo je vydal.
     */
    public boolean sameEndpointAs(TsaProfile other) {
        return other != null
                && url.equals(other.url)
                && username.equals(other.username)
                && password.equals(other.password)
                && clientCertPath.equals(other.clientCertPath)
                && clientCertPassword.equals(other.clientCertPassword);
    }

    @Override
    public String toString() { return name.isBlank() ? url : name; }
}
