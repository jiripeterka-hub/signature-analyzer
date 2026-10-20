package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.service.crl.OnlineCRLSource;
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader;
import eu.europa.esig.dss.service.http.commons.FileCacheDataLoader;
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource;
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource;
import eu.europa.esig.dss.spi.x509.KeyStoreCertificateSource;
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource;
import eu.europa.esig.dss.spi.client.http.IgnoreDataLoader;
import eu.europa.esig.dss.tsl.function.GrantedOrRecognizedAtNationalLevelTrustAnchorPeriodPredicate;
import eu.europa.esig.dss.tsl.job.TLValidationJob;
import eu.europa.esig.dss.tsl.source.LOTLSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.enumerations.SubIndication;
import eu.europa.esig.dss.enumerations.TSLType;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.tsl.CertificateTrustTime;
import eu.europa.esig.dss.model.tsl.Condition;
import eu.europa.esig.dss.model.tsl.ConditionForQualifiers;
import eu.europa.esig.dss.model.job.DownloadInfoRecord;
import eu.europa.esig.dss.model.tsl.OtherTSLPointer;
import eu.europa.esig.dss.model.tsl.TLParsingInfoRecord;
import eu.europa.esig.dss.model.tsl.LOTLInfo;
import eu.europa.esig.dss.model.tsl.TLInfo;
import eu.europa.esig.dss.model.tsl.TLValidationJobSummary;
import eu.europa.esig.dss.model.tsl.TrustProperties;
import eu.europa.esig.dss.model.tsl.TrustServiceProvider;
import eu.europa.esig.dss.model.tsl.TrustServiceStatusAndInformationExtensions;
import eu.europa.esig.dss.model.job.ValidationInfoRecord;
import eu.europa.esig.dss.model.timedependent.TimeDependentValues;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.hc.core5.ssl.TrustStrategy;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrustConfig {

    private static final String EU_LOTL_URL =
            "https://ec.europa.eu/tools/lotl/eu-lotl.xml";

    private static final String TRUST_STATE_FILE   = "trust-state.ser";      // legacy
    private static final String COMPACT_STATE_FILE = "trust-state-v2.bin";   // fast format
    private static final String CHECK_RUN_FILE     = "tl-check-last-run.timestamp";

    /**
     * How long a completed freshness check stays valid. National trusted lists change on a
     * scale of weeks, and a check costs ~40 downloads, so re-running it on every launch only
     * slows the app down.
     */
    public static final int TL_CHECK_MIN_INTERVAL_HOURS = 12;
    private static final long TL_CHECK_MIN_INTERVAL_MS = TL_CHECK_MIN_INTERVAL_HOURS * 60L * 60 * 1000;
    private static final int    COMPACT_MAGIC      = 0x544C4358;             // "TLCX"
    // v1: cert data only (STUB_TL_INFO everywhere; Phase 1 required).
    // v2: certs first, TLInfo section at end (STUB_TL_INFO per cert; Phase 1 required).
    // v3: TLInfo section FIRST, then cert section with per-TrustProperties TL URL.
    //     Each cert gets the correct real TLInfo (with territory), so Phase 1 is skipped.
    // v4: same as v3 but each service entry also stores additionalServiceInfoUris so DSS
    //     ServiceByCertificateTypeFilter can pass without Phase 1 or a manual refresh.
    // v5: same as v4 but ConditionForQualifiers are pre-evaluated per cert at save time so
    //     conflicting qualifiers (e.g. QCWithQSCD vs X_QSCD) are never both stored for the
    //     same cert, eliminating the QSCD-inconsistency DSS errors caused by ALWAYS_TRUE.
    // v6: same as v5 but per-TrustProperties TSP names + trade names, and per-service-entry
    //     service names are saved so TrustedCertificateMatchTrustServiceCheck.isMatch() can
    //     compare the CA cert's DN against TSP/service names (required for the check to pass).
    // v7: same as v6, plus three things whose absence made the cache disagree with a fresh parse
    //     of the very same lists.
    //     - Each CertificateTrustTime stores its trusted flag. Up to v6 the flag was assumed true
    //       on load, which holds only while every trust time is unbounded. Once a trust anchor
    //       validity predicate is in play, a certificate listed solely under services that never
    //       held a positive status is not a trust anchor at all, and that verdict has to survive
    //       the round trip rather than be reinvented as unbounded trust.
    //     - Each service entry stores its ServiceSupplyPoints: the OCSP and CRL endpoints a list
    //       publishes for a service, 725 of them over 433 certificates, which DSS asks for through
    //       getAlternativeOCSPUrls/getAlternativeCRLUrls when a certificate's own AIA and CDP
    //       yield nothing usable.
    //     - Each service entry stores expiredCertsRevocationInfo, the date from which the service
    //       keeps publishing revocation data for certificates that have already expired. Without
    //       it RevocationHasInformationAboutCertificateCheck rejects a CRL whose thisUpdate lies
    //       past the certificate's notAfter, so an expired-but-preserved signature came out
    //       INDETERMINATE/CERTIFICATE_CHAIN_GENERAL_FAILURE from the cache and TOTAL_PASSED from a
    //       fresh parse.
    //     A v6 or older file predates the trust anchor validity predicate and carries no sunset
    //     dates either, so loading one drops to the Phase 1 reparse below, which rebuilds
    //     everything from the cached XML without touching the network.
    private static final short  COMPACT_VERSION    = 7;

    // Placeholder Condition used when reconstructing ConditionForQualifiers from
    // the compact cache.  The qualifiers are already scoped per-certificate by the
    // time they are written, so an always-true condition is functionally equivalent.
    private static final Condition ALWAYS_TRUE = new Condition() {
        private static final long serialVersionUID = 1L;
        @Override public boolean check(CertificateToken cert) { return true; }
        @Override public String toString(String indent) { return indent + "always-true"; }
    };

    // Minimal stub TLInfo used as a non-null placeholder when reconstructing
    // TrustProperties from the compact cache.  TLInfo is required by the TrustProperties
    // constructor but is only consulted for report generation, not for core trust /
    // qualification logic.  The per-TrustProperties stub always has FAR_FUTURE nextUpdateDate
    // so individual qualification constraint checks pass; the job summary uses a separate,
    // per-country CacheInfoRecord with the real nextUpdateDate from the TL XML.
    private static final TLInfo STUB_TL_INFO;
    static {
        CacheInfoRecord s = new CacheInfoRecord();
        STUB_TL_INFO = new TLInfo(s, s, s, "cache://stub");
    }

    /**
     * InfoRecord used when reconstructing TLInfo objects from the compact binary.
     *
     * <p>Od DSS 6.5 se rozhraní dělí: obecná část ({@code model.job.ParsingInfoRecord}) platí
     * pro důvěryhodné seznamy i pro nové LoTE, kdežto všechno, co je vlastní seznamům —
     * teritorium, pořadové číslo, poskytovatelé — sedí v {@code model.tsl.TLParsingInfoRecord}.
     * Zdejší třída implementuje tu druhou; {@code DownloadInfoRecord} a {@code ValidationInfoRecord}
     * se zároveň přestěhovaly z {@code model.tsl} do {@code model.job}.
     *
     * <p>Two constructor forms:
     * <ul>
     *   <li>No-arg: creates a stub with FAR_FUTURE nextUpdateDate, used for
     *       {@link #STUB_TL_INFO} (per-TrustProperties placeholder) and for v1 compat.</li>
     *   <li>Parameterised: stores real metadata read from a v2 compact binary; used to
     *       build the per-country TLInfo objects in {@link TLValidationJobSummary}.</li>
     * </ul>
     */
    private static final class CacheInfoRecord
            implements DownloadInfoRecord, TLParsingInfoRecord, ValidationInfoRecord {
        private static final long serialVersionUID = 1L;

        private final String  territory;
        private final int     version;
        private final int     sequenceNumber;
        private final Date    issueDate;
        private final Date    nextUpdateDate;
        private final boolean valid;

        /** Stub constructor: FAR_FUTURE nextUpdateDate, always valid. */
        CacheInfoRecord() {
            this("", 5, 0, null,
                    new Date(System.currentTimeMillis() + 180L * 24 * 3600 * 1000L),
                    true);
        }

        /** Parameterised constructor: real metadata from the v2 compact binary. */
        CacheInfoRecord(String territory, int version, int sequenceNumber,
                        Date issueDate, Date nextUpdateDate, boolean valid) {
            this.territory      = territory     != null ? territory : "";
            this.version        = version;
            this.sequenceNumber = sequenceNumber;
            this.issueDate      = issueDate;
            this.nextUpdateDate = nextUpdateDate;
            this.valid          = valid;
        }

        // InfoRecord
        @Override public boolean isRefreshNeeded()  { return false; }
        @Override public boolean isDesynchronized() { return false; }
        @Override public boolean isSynchronized()   { return true;  }
        @Override public boolean isError()          { return false; }
        @Override public boolean isToBeDeleted()    { return false; }
        @Override public boolean isResultExist()    { return true;  }
        @Override public String  getStatusName()    { return "";    }
        @Override public Date getLastStateTransitionTime()        { return null; }
        @Override public Date getLastSuccessSynchronizationTime() { return new Date(); }
        @Override public String getExceptionMessage()             { return null; }
        @Override public String getExceptionStackTrace()          { return null; }
        @Override public Date getExceptionFirstOccurrenceTime()   { return null; }
        @Override public Date getExceptionLastOccurrenceTime()    { return null; }
        // DownloadInfoRecord
        @Override public DSSDocument getDocument()               { return null; }
        @Override public Date        getLastDownloadAttemptTime() { return null; }
        // Otisk staženého seznamu; DSS 6.5 jej přidalo, aby uměl poznat, že se obsah na adrese
        // nezměnil. Zdejší mezipaměť ho nedrží — kompaktní podoba nese jen to, co je potřeba
        // k sestavení kotev — a null znamená „neznám", ne „nesouhlasí".
        @Override public eu.europa.esig.dss.model.Digest getDigest() { return null; }
        // TLParsingInfoRecord
        @Override public TSLType  getTSLType()        { return eu.europa.esig.dss.enumerations.TSLTypeEnum.EUgeneric; }
        @Override public Integer  getSequenceNumber() { return sequenceNumber; }
        @Override public Integer  getVersion()        { return version; }
        @Override public String   getTerritory()      { return territory; }
        @Override public Date     getIssueDate()      { return issueDate; }
        @Override public Date     getNextUpdateDate() { return nextUpdateDate; }
        @Override public List<String>               getDistributionPoints()          { return Collections.emptyList(); }
        @Override public List<TrustServiceProvider> getTrustServiceProviders()       { return Collections.emptyList(); }
        @Override public List<OtherTSLPointer>      getLotlOtherPointers()           { return Collections.emptyList(); }
        @Override public List<OtherTSLPointer>      getTlOtherPointers()             { return Collections.emptyList(); }
        @Override public List<String>               getPivotUrls()                   { return Collections.emptyList(); }
        @Override public List<String>               getStructureValidationMessages() { return Collections.emptyList(); }
        @Override public String getSigningCertificateAnnouncementUrl() { return null; }
        @Override public int getTSPNumber() { return 0; }
        @Override public int getTSNumber()  { return 0; }
        @Override public int getCertNumber(){ return 0; }
        // ValidationInfoRecord — isValid drives the TL "signature check" in DSS reports.
        @Override public Indication       getIndication()          { return valid ? Indication.PASSED : Indication.FAILED; }
        @Override public SubIndication    getSubIndication()       { return null; }
        @Override public Date             getSigningTime()         { return null; }
        @Override public CertificateToken getSigningCertificate()  { return null; }
        @Override public List<CertificateToken> getPotentialSigners() { return Collections.emptyList(); }
        @Override public boolean isValid()         { return valid;  }
        @Override public boolean isIndeterminate() { return false;  }
        @Override public boolean isInvalid()       { return !valid; }
    }

    private final AppSettings settings;

    /** Cache directory — kept so background threads can save trust state. */
    private File cacheDir;
    /** Cached TrustedListsCertificateSource — kept so background threads can save to it. */
    private TrustedListsCertificateSource trustedSource;
    /** The DSS job used to refresh the trust lists — kept for on-demand refresh. */
    private TLValidationJob tlJob;
    /** Cache-only loader (Long.MAX_VALUE expiry, IgnoreDataLoader fallback) used as the
     *  onlineDataLoader during the fast parallel startup read from disk. */
    private FileCacheDataLoader cacheOnlyLoader;
    /** Network loader (expiry=0) used only for actual online refreshes. */
    private FileCacheDataLoader networkLoader;
    /** Marker file whose last-modified time records when EUTL was last downloaded. */
    private File lastRefreshFile;
    /** Per-TL record of the last successful freshness check: TL URL → epoch millis. */
    private File tlCheckStateFile;
    /**
     * Kdo chce vědět, že se stav seznamů změnil na pozadí — kontrola čerstvosti doběhla, úplné
     * stažení začalo či skončilo. Zavedeno 16. 10. 2026: hlavička karet i stavový řádek se dřív
     * plnily jen při startu a při ověření, takže po obnově na pozadí ukazovaly dál staré datum
     * (na jiném počítači tak po instalaci viselo 29. 9.). Volá se z vlákna na pozadí.
     */
    private volatile Runnable trustStateListener;
    /** In-memory mirror of {@link #tlCheckStateFile}; written by the background check thread
     *  and read from the FX thread, hence the volatile immutable-replacement pattern. */
    private volatile Map<String, Long> tlLastSuccessfulCheck = new LinkedHashMap<>();
    /** Context classloader captured at build time; needed for DSS ServiceLoader resolution. */
    private ClassLoader appClassLoader;
    /** Set to true while Phase 2 (network) refresh is running. */
    private volatile boolean networkRefreshInProgress = false;
    /** Compact binary version that was last successfully loaded (1 or 2; 0 = none loaded). */
    private int lastCompactVersionLoaded = 0;
    /** LOTL sequenceNumber extracted from the v2 compact binary on load; -1 if unknown. */
    private volatile int cachedLotlSequenceNumber = -1;

    public TrustConfig(AppSettings settings) {
        this.settings = settings;
    }

    /** Viz {@link #trustStateListener}; posluchač si sám přejde na vlákno UI. */
    public void setTrustStateListener(Runnable listener) {
        this.trustStateListener = listener;
    }

    private void fireTrustStateChanged() {
        Runnable l = trustStateListener;
        if (l == null) return;
        try {
            l.run();
        } catch (Exception e) {
            System.err.println("[TrustConfig] trust state listener failed: " + e.getMessage());
        }
    }

    public TrustConfig() {
        this(new AppSettings());
    }

    /**
     * Obnovy nad sdíleným {@link #tlJob} běží po jedné.
     *
     * <p>Který loader job používá, je jeden proměnlivý údaj na sdíleném objektu, a každá obnova si
     * ho kolem sebe přepíná: na začátku síťový, ve {@code finally} zpátky na cache-only. Když se
     * dvě obnovy potkají — a potkají se snadno, protože obnova na pozadí čeká na
     * {@code BackgroundWork.awaitIdle()} a rozběhne se klidně ve chvíli, kdy si uživatel otevře
     * Nastavení —, ta rychlejší přepne loader zpátky uprostřed té druhé. Zbytek seznamů se pak
     * „stahuje" z cache, a seznam, který v cache není, skončí chybou stažení.
     *
     * <p>Tím se to zacyklí: bez staženého souboru není co uložit, takže seznam v cache není ani
     * příště. Naměřeno na irském seznamu, který v cache chyběl: {@code onlineRefresh()} s
     * cache-only loaderem vrátí přesně „Cannot retrieve data from url […]. Empty content is
     * obtained!", tedy hlášku „(stažení)" s adresou místo území — což je slovo od slova to, co
     * dialog Nastavení hlásil, ačkoli týž seznam šel v téže chvíli stáhnout na první pokus.
     */
    private final Object refreshLock = new Object();

    /**
     * Forces an immediate online refresh of the EU trusted lists.
     * Blocks the calling thread; intended to be called from a background thread.
     * No-op if {@link #buildLotlCertificateVerifier()} has not been called yet.
     */
    public String refreshLotlNow() {
        if (tlJob == null || networkLoader == null) {
            return "Obnova neproběhla — seznamy ještě nebyly načteny.";
        }
        ClassLoader prev = Thread.currentThread().getContextClassLoader();
        synchronized (refreshLock) {
        // Až za zámkem: kdyby se to četlo před ním, byl by to stav z doby před obnovou, která
        // mezitím doběhla, a výpadek dávno známý by se ohlásil jako nový.
        List<String> alreadyEmpty = emptyTerritoriesFound;
        try {
            if (appClassLoader != null) {
                Thread.currentThread().setContextClassLoader(appClassLoader);
            }
            tlJob.setOnlineDataLoader(networkLoader);
            tlJob.onlineRefresh();
            if (trustedSource != null) postOnlineRefreshFixup(trustedSource);
            touchLastRefreshFile();
            // Everything that downloaded fine is confirmed now — no point re-checking it on the
            // next launch; what failed stays due and is retried.
            recordRefreshedLists(tlJob);

            // Neúspěch jednotlivého národního seznamu obnovu nezhatí — DSS ho prostě vynechá
            // a jede dál. Dokud se to nečetlo, hlásila obnova „hotovo" i tehdy, když jedna země
            // vypadla, uložila stav bez ní a jediná stopa byl řádek v logu při příštím startu.
            // Irsko takhle vypadlo a stálo to za půl dne hledání.
            List<ListFailure> failed = failedLists(tlJob);
            List<String> newlyLost = new ArrayList<>();
            for (ListFailure f : failed) {
                // Porovnává se na obojí: uložený stav vede zemi buď kódem území, nebo — když se
                // seznam nikdy nepodařilo rozebrat — jeho adresou. Kdyby se hledalo jen podle
                // jednoho, vypadal by starý známý výpadek jako nový a stav by se nikdy neuložil.
                if (!alreadyEmpty.contains(f.name()) && !alreadyEmpty.contains(f.url())) {
                    newlyLost.add(f.describe());
                }
            }

            if (trustedSource != null && cacheDir != null) {
                if (newlyLost.isEmpty()) {
                    // Buď prošlo všechno, nebo padlo jen to, co ve stavu stejně nebylo — uložený
                    // stav se tím nezhorší. Trvalý výpadek na cizí straně tak cache nezmrazí.
                    saveCompactTrustState(trustedSource, cacheDir);
                } else {
                    System.err.println("[TrustConfig] uložený stav ponechán beze změny — "
                            + "při obnově vypadlo: " + String.join(", ", newlyLost));
                }
            }
            if (failed.isEmpty()) return "Hotovo — seznam byl úspěšně obnoven.";
            String what = failed.stream().map(ListFailure::describe)
                    .collect(java.util.stream.Collectors.joining(", "));
            return newlyLost.isEmpty()
                    ? "Obnoveno. Nenačetlo se: " + what + " — chybělo i předtím."
                    : "Obnoveno, ale nenačetlo se: " + what
                      + ". Uložený stav zůstal beze změny, zkuste to prosím znovu.";
        } finally {
            if (cacheOnlyLoader != null) tlJob.setOnlineDataLoader(cacheOnlyLoader);
            Thread.currentThread().setContextClassLoader(prev);
        }
        }
    }

    /**
     * Národní seznamy, které při poslední obnově neprošly, s důvodem: území (nebo URL, když se
     * nepodařilo ani rozebrat) → čím to padlo.
     *
     * <p>Klíč je záměrně týž řetězec, jaký používá {@link #emptyTerritories} — obojí se porovnává
     * proti sobě, aby šlo odlišit „teď vypadlo" od „nebylo tam ani předtím".
     */
    private record ListFailure(String name, String url, String reason, String detail) {
        String describe() {
            return name + " (" + reason
                    + (detail != null && !detail.isBlank() ? ": " + detail : "") + ")";
        }
    }

    /**
     * Hláška od DSS, zkrácená na jednu čitelnou větu.
     *
     * <p>Bez ní stálo v dialogu jen „(stažení)", a to jsou tři úplně různé diagnózy pod jedním
     * slovem: nedůvěryhodný certifikát serveru, vypršený čas, nebo prázdná odpověď. Právě ten
     * rozdíl je to jediné, co uživateli — a tomu, kdo to po něm hledá — řekne, jestli má zkusit
     * znovu, sáhnout na truststore, nebo počkat, až se cizí server vzpamatuje. Naměřeno na
     * irském seznamu: DSS vrací „Cannot retrieve data from url […]. Empty content is obtained!",
     * což znamená něco úplně jiného než výpadek sítě, a z pouhého „(stažení)" se to nepozná.
     */
    private static String shortReason(String message, String url) {
        if (message == null || message.isBlank()) return null;
        String s = message.replaceAll("\\s+", " ").trim();
        // Adresa v hlášce je táž, jakou nese jméno záznamu — dvakrát v jedné větě jen ubírá místo
        // tomu podstatnému za ní.
        if (url != null) {
            s = s.replace("[" + url + "]", "").replace("  ", " ").replace(" .", ".").trim();
        }
        // Krátí se odzadu, ne po první větě: DSS staví hlášky tak, že obecné „Cannot retrieve data
        // from url" stojí první a to, co se opravdu stalo („Empty content is obtained!"), až za
        // tečkou. Ořez po první větě proto zahazoval přesně tu půlku, kvůli které se to vypisuje.
        return s.length() > 200 ? s.substring(0, 197) + "…" : s;
    }

    private static List<ListFailure> failedLists(TLValidationJob job) {
        List<ListFailure> out = new ArrayList<>();
        TLValidationJobSummary sum = job == null ? null : job.getSummary();
        if (sum == null) return out;
        for (LOTLInfo li : sum.getLOTLInfos()) {
            for (TLInfo tl : li.getTLInfos()) collectFailure(tl, out);
        }
        for (TLInfo tl : sum.getOtherTLInfos()) collectFailure(tl, out);
        return out;
    }

    private static void collectFailure(TLInfo tl, List<ListFailure> out) {
        if (tl == null || tl.getUrl() == null || "cache://stub".equals(tl.getUrl())) return;
        DownloadInfoRecord d = tl.getDownloadCacheInfo();
        TLParsingInfoRecord p = tl.getParsingCacheInfo();
        ValidationInfoRecord v = tl.getValidationCacheInfo();
        String why = null;
        String detail = null;
        if (d != null && d.isError())      { why = "stažení";        detail = d.getExceptionMessage(); }
        else if (p != null && p.isError()) { why = "rozbor";         detail = p.getExceptionMessage(); }
        else if (v != null && v.isError()) { why = "ověření podpisu"; detail = v.getExceptionMessage(); }
        if (why == null) return;
        String name = p != null && p.getTerritory() != null && !p.getTerritory().isEmpty()
                ? p.getTerritory() : tl.getUrl();
        for (ListFailure f : out) if (f.url().equals(tl.getUrl())) return;
        out.add(new ListFailure(name, tl.getUrl(), why, shortReason(detail, tl.getUrl())));
    }

    /**
     * Phase 1 of the background refresh: reads cached TL XML files from disk and builds
     * real TLInfo/LOTLInfo objects. Must run synchronously in the init thread before the
     * verifier is exposed to callers — otherwise document validation contends with the TL
     * source writes and the app freezes on first drag-drop.
     *
     * Runs in the calling thread (lotl-init) which already has the correct context
     * ClassLoader, so no ClassLoader manipulation is needed here.
     */
    private void doPhase1CacheRefresh(TLValidationJob job, FileCacheDataLoader cacheLoader) {
        try {
            long t0 = System.currentTimeMillis();
            job.setOnlineDataLoader(cacheLoader);
            job.onlineRefresh();
            // onlineRefresh() replaces the summary with real TLInfo objects (no STUB).
            // Re-inject STUB and update cachedLotlSequenceNumber for binary serialization.
            if (trustedSource != null) postOnlineRefreshFixup(trustedSource);
            System.err.printf("[TrustConfig] Phase 1 (cache-only refresh) done in %d ms%n",
                    System.currentTimeMillis() - t0);
        } catch (Exception e) {
            System.err.println("[TrustConfig] Phase 1 failed: " + e.getMessage());
        }
    }

    /**
     * Phase 2: downloads updated TLs from the network, saves the compact state, and resets
     * the data loader.  Runs in a background daemon thread so it does not block the UI.
     * {@link #networkRefreshInProgress} is set before the thread starts and cleared when
     * it finishes, so callers can poll the flag.
     */
    private void startNetworkRefresh(TLValidationJob job,
                                     FileCacheDataLoader netLoader,
                                     FileCacheDataLoader cacheLoader,
                                     ClassLoader cl,
                                     TrustedListsCertificateSource src) {
        networkRefreshInProgress = true;
        fireTrustStateChanged();
        Thread bg = new Thread(() -> {
            Thread.currentThread().setContextClassLoader(cl);
            try {
                // A full refresh is the heaviest thing this app does off the FX thread; let any
                // validation the user started finish first. Čekání zůstává před zámkem — držet
                // ho po dobu, kdy se čeká na cizí práci, by zbytečně blokovalo ruční obnovu.
                BackgroundWork.awaitIdle();
                long t0 = System.currentTimeMillis();
                // Viz refreshLock: přepnutí loaderu i návrat musí patřit k jedné obnově.
                synchronized (refreshLock) {
                    try {
                        job.setOnlineDataLoader(netLoader);
                        job.onlineRefresh();
                        System.err.printf("[TrustConfig] Phase 2 (network refresh) done in %d ms%n",
                                System.currentTimeMillis() - t0);
                        postOnlineRefreshFixup(src);
                        touchLastRefreshFile();
                        recordRefreshedLists(job);
                        saveCompactTrustState(src, cacheDir);
                    } finally {
                        job.setOnlineDataLoader(cacheLoader);
                    }
                }
            } catch (Exception e) {
                System.err.println("[TrustConfig] Phase 2 failed: " + e.getMessage());
                e.printStackTrace(System.err);
            } finally {
                networkRefreshInProgress = false;
                fireTrustStateChanged();
            }
        }, "lotl-network-refresh");
        bg.setDaemon(true);
        bg.setPriority(Thread.MIN_PRIORITY);
        bg.start();
    }

    /**
     * Called after every {@code job.onlineRefresh()} (Phase 2 or manual refresh).
     * DSS replaces the summary with a fresh one containing real LOTLInfo/TLInfo objects.
     * We must re-add STUB_TL_INFO to the summary so that compact-loaded TrustProperties
     * (which reference STUB_TL_INFO) remain findable via getTLInfoById() during validation.
     * We also update cachedLotlSequenceNumber from the fresh summary for future startups.
     */
    private void postOnlineRefreshFixup(TrustedListsCertificateSource src) {
        TLValidationJobSummary s = src.getSummary();
        if (s == null) return;
        if (!s.getLOTLInfos().isEmpty()) {
            TLParsingInfoRecord pr = s.getLOTLInfos().get(0).getParsingCacheInfo();
            if (pr != null && pr.getSequenceNumber() != null) {
                cachedLotlSequenceNumber = pr.getSequenceNumber();
            }
        }
        // Re-inject STUB_TL_INFO so DSS can resolve it during getTLInfoById() lookups.
        // Real TLInfos from onlineRefresh() have fresh (future) nextUpdateDate, so adding
        // STUB alongside them does not cause "not fresh" warnings.
        List<TLInfo> withStub = new ArrayList<>(s.getOtherTLInfos());
        withStub.add(STUB_TL_INFO);
        src.setSummary(new TLValidationJobSummary(s.getLOTLInfos(), withStub));
    }

    /** Returns the epoch millis of the last successful network EUTL refresh, or -1 if unknown. */
    public long getLastNetworkRefreshTime() {
        return readLastRefreshTime();
    }

    /** Returns true while a Phase 2 (network) refresh is actively running in the background. */
    public boolean isNetworkRefreshInProgress() {
        return networkRefreshInProgress;
    }

    /**
     * Kdy AnEP naposledy potvrdil své kopie seznamů proti zdroji, nebo -1, když to neví.
     *
     * <p>Není to totéž co {@link #getLastNetworkRefreshTime()}. Úplné stažení proběhne jen tehdy,
     * když kontrola čerstvosti ({@link #isAnyNationalTlUpdated()}, nejvýš jednou za 12 hodin)
     * najde novější seznam; kontrola, která nic nového nenašla, čas stažení neposune. Hlavička
     * ale tvrdí „ověřeny u zdroje“, a to platí i o kontrole bez nálezu — proto pozdější z obou:
     * poslední úplné stažení, nebo poslední úspěšné potvrzení některého národního seznamu.
     * Seznamy, které se potvrdit nepodařilo, hlásí zvlášť {@link #getStaleTrustLists()}.
     * Zavedeno 16. 10. 2026 na pokyn uživatele.
     */
    public long getLastSourceCheckTime() {
        long latest = readLastRefreshTime();
        for (long t : tlLastSuccessfulCheck.values()) latest = Math.max(latest, t);
        return latest;
    }

    /**
     * Verze národního důvěryhodného seznamu: země, pořadové číslo ({@code TSLSequenceNumber})
     * a okamžik vydání ({@code ListIssueDateTime}). Kterákoli složka může chybět.
     */
    public record TrustListVersion(String territory, Integer sequenceNumber, java.time.Instant issueDate) { }

    /**
     * Národní seznam s nejpozdějším datem vydání mezi načtenými, nebo {@code null}, dokud stav
     * důvěry načtený není. Datum vydání je údaj ze samotného seznamu — říká, z jakého stavu
     * ověřování vychází, kdežto {@link #getLastSourceCheckTime()} jen to, kdy ho AnEP porovnal
     * se zdrojem. Zavedeno 16. 10. 2026 na pokyn uživatele: bublina nad hlavičkou ukazuje jen
     * tento jediný seznam, ne výčet všech zemí.
     */
    public TrustListVersion getLatestIssuedTrustList() {
        try {
            TLValidationJobSummary s = trustedSource != null ? trustedSource.getSummary() : null;
            if (s == null) return null;
            TrustListVersion latest = null;
            for (TLInfo tl : allCountryTls(s.getLOTLInfos(), s.getOtherTLInfos())) {
                if (tl == null || "cache://stub".equals(tl.getUrl())) continue;
                TLParsingInfoRecord pr = tl.getParsingCacheInfo();
                if (pr == null || pr.getIssueDate() == null) continue;
                java.time.Instant issued = pr.getIssueDate().toInstant();
                if (latest == null || issued.isAfter(latest.issueDate())) {
                    latest = new TrustListVersion(pr.getTerritory(), pr.getSequenceNumber(), issued);
                }
            }
            return latest;
        } catch (Exception e) {
            // Souhrn se může zrovna vyměňovat obnovou na pozadí; údaj je jen informační.
            return null;
        }
    }

    /**
     * A national Trusted List whose cached copy has not been confirmed against its source
     * for longer than {@link #STALE_TL_WARNING_DAYS}.  Its certificates are still used for
     * validation — only the assurance that they are current is missing.
     */
    public record StaleTrustList(String url, String territory, long lastCheckedMillis, long days) { }

    /**
     * Age in days after which an unconfirmed national TL is reported to the user.
     * ETSI TS 119 612 obliges member states to reissue a TL at least every six months,
     * so two weeks without a single successful fetch indicates a real outage on their side
     * rather than normal quiet time.
     */
    public static final int STALE_TL_WARNING_DAYS = 14;

    /**
     * Returns the national TLs that could not be confirmed for {@link #STALE_TL_WARNING_DAYS}
     * or longer, newest-first by age.  Empty when everything is current or when no freshness
     * check has run yet.
     */
    public List<StaleTrustList> getStaleTrustLists() {
        long now = System.currentTimeMillis();
        long thresholdMs = STALE_TL_WARNING_DAYS * 24L * 60 * 60 * 1000;
        Map<String, String> territories = territoriesByUrl();
        List<StaleTrustList> stale = new ArrayList<>();
        for (Map.Entry<String, Long> e : tlLastSuccessfulCheck.entrySet()) {
            long age = now - e.getValue();
            if (age < thresholdMs) continue;
            // Report only lists the validation actually loaded. The check state is a file on disk
            // and outlives a change to what is worth checking: entries for the LOTL's PDF pointers,
            // written before parseNationalTlUrls() learned to skip them, would otherwise keep
            // showing until the next freshness run pruned them half a day later. DSS never loads a
            // PDF pointer, so its own summary is the authority on which lists are real.
            if (!territories.isEmpty() && !territories.containsKey(e.getKey())) continue;
            stale.add(new StaleTrustList(e.getKey(), territories.get(e.getKey()),
                    e.getValue(), age / (24L * 60 * 60 * 1000)));
        }
        stale.sort((a, b) -> Long.compare(b.days(), a.days()));
        return stale;
    }

    /**
     * Maps TL URL → ISO country code using the currently loaded trust state. Every loaded list is
     * a key, with a null value when its territory could not be read: the key set is what tells
     * {@link #getStaleTrustLists()} which lists validation is really using, so an entry must not
     * drop out of it just because the country code is missing.
     *
     * <p>Empty when the summary has not been populated yet, which callers read as "cannot tell"
     * rather than "nothing is loaded".
     */
    private Map<String, String> territoriesByUrl() {
        Map<String, String> map = new HashMap<>();
        try {
            if (trustedSource != null && trustedSource.getSummary() != null) {
                for (TLInfo tl : trustedSource.getSummary().getOtherTLInfos()) {
                    if (tl.getUrl() == null) continue;
                    TLParsingInfoRecord pr = tl.getParsingCacheInfo();
                    map.put(tl.getUrl(), pr != null ? pr.getTerritory() : null);
                }
            }
        } catch (Exception ignored) {
            // Summary may not be populated yet — country codes are cosmetic here.
        }
        return map;
    }

    /**
     * Checks whether any national Trusted List has a {@code ListIssueDateTime} more recent
     * than the last successful network refresh stored in {@code last-refresh.timestamp}.
     *
     * <p>Downloads the LOTL XML to discover national TL URLs, then fetches each TL that is due
     * and compares its {@code ListIssueDateTime} against the stored timestamp.  A TL that cannot
     * be downloaded or parsed (network error, TLS failure, HTML error page instead of XML)
     * is skipped: the cached copy from the last successful refresh stays in use and the TL is
     * checked again on the next startup.  Unreachable TLs are reported as a single summary
     * line rather than aborting the check.</p>
     *
     * <p><b>Interval a přerušení (16. 10. 2026).</b> Dřív se před stahováním zapsala značka
     * „kontrola proběhla" a dalších 12 hodin se nekontrolovalo vůbec. Kontrola i úplné stažení
     * ale běží v démonovém vlákně, které zavření aplikace ukončí — krátká relace tak kontrolu
     * přerušila a značka ji na 12 hodin zablokovala; na jiném počítači tím po instalaci zůstalo
     * datum seznamů na 29. 9. Teď se interval počítá u každého seznamu zvlášť podle jeho
     * posledního potvrzení ({@link #tlLastSuccessfulCheck}), které se zapisuje hned po každém
     * seznamu. Přerušená kontrola proto při dalším startu naváže tam, kde skončila, a krátké
     * relace ji neopakují celou. Seznam, u něhož se našla novější verze, se za potvrzený
     * nezapíše: kdyby úplné stažení nedoběhlo, najde ho další start znovu.</p>
     *
     * <p>Returns {@code true} conservatively when the last-refresh timestamp is absent,
     * mirroring the old seqNum==0 behaviour that always triggered Phase 2 on first migration.</p>
     */
    private boolean isAnyNationalTlUpdated() {
        long lastRefresh = readLastRefreshTime();
        if (lastRefresh < 0) {
            System.err.println("[TrustConfig] No last-refresh timestamp — forcing network refresh");
            return true;
        }
        // Downloading ~40 national lists costs tens of MB and several seconds of CPU. Member
        // states publish new lists on a scale of weeks, so a list confirmed within
        // TL_CHECK_MIN_INTERVAL is not fetched again. Když jsou potvrzené všechny známé seznamy,
        // nestahuje se ani LOTL.
        long now = System.currentTimeMillis();
        Map<String, Long> known = tlLastSuccessfulCheck;
        if (!known.isEmpty() && known.values().stream()
                .allMatch(t -> now - t < TL_CHECK_MIN_INTERVAL_MS)) {
            long oldest = known.values().stream().mapToLong(Long::longValue).min().orElse(now);
            System.err.printf("[TrustConfig] TL freshness check skipped — all lists confirmed,"
                    + " oldest %d h ago%n", (now - oldest) / 3_600_000L);
            return false;
        }
        try {
            CommonsDataLoader loader = createDataLoader();
            byte[] lotlBytes = loader.get(EU_LOTL_URL);
            if (lotlBytes == null || lotlBytes.length == 0) return false;

            List<String> tlUrls = parseNationalTlUrls(lotlBytes);
            // TLs seen for the first time inherit the last refresh time, since their cached copy
            // does date from that refresh; lists the LOTL no longer points to are dropped.
            Map<String, Long> state = alignTlCheckState(tlUrls, lastRefresh);
            List<String> due = new ArrayList<>();
            for (String url : tlUrls) {
                if (now - state.get(url) >= TL_CHECK_MIN_INTERVAL_MS) due.add(url);
            }
            System.err.printf("[TrustConfig] Checking %d of %d national TLs (last refresh: %s)%n",
                    due.size(), tlUrls.size(), java.time.Instant.ofEpochMilli(lastRefresh));

            List<String> unreachable = new ArrayList<>();
            for (String url : due) {
                // Never make the user wait behind housekeeping: pause while a validation runs.
                BackgroundWork.awaitIdle();
                try {
                    byte[] tlBytes = loader.get(url);
                    if (tlBytes == null || tlBytes.length == 0) {
                        unreachable.add(url + " (empty response)");
                        continue;
                    }
                    java.time.Instant issueDate = parseTlListIssueDateTime(tlBytes);
                    if (issueDate == null) {
                        unreachable.add(url + " (no ListIssueDateTime)");
                        continue;
                    }
                    if (issueDate.toEpochMilli() > lastRefresh) {
                        System.err.printf("[TrustConfig] TL updated: %s  ListIssueDateTime=%s%n",
                                url, issueDate);
                        // Za potvrzený se nezapisuje — viz javadoc. Úplné stažení, které
                        // následuje, potvrdí všechno, co se stáhne (recordRefreshedLists).
                        return true;
                    }
                    // Zapsat hned, ne až na konci: zavřením aplikace by se jinak postup ztratil.
                    recordTlChecks(Map.of(url, System.currentTimeMillis()));
                } catch (Exception e) {
                    // Keep the cached copy of this TL and retry on the next startup.
                    unreachable.add(url + " (" + shortReason(e) + ")");
                }
            }
            if (unreachable.isEmpty()) {
                System.err.println("[TrustConfig] All national TLs are current");
            } else {
                System.err.printf("[TrustConfig] %d of %d checked national TLs are current; "
                        + "%d could not be checked — keeping the cached copy, will retry next startup%n",
                        due.size() - unreachable.size(), due.size(), unreachable.size());
                for (String u : unreachable) {
                    System.err.println("[TrustConfig]   unchecked: " + u);
                }
            }
            return false;
        } catch (Exception e) {
            System.err.println("[TrustConfig] TL freshness check failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Builds a namespace-aware, XXE-hardened {@link DocumentBuilder} whose SAX {@link ErrorHandler}
     * rethrows instead of printing.  The default handler writes lines such as
     * {@code [Fatal Error] :1:1: Content is not allowed in prolog.} straight to {@code System.err},
     * bypassing our own logging — a TL served as an HTML error page would otherwise produce
     * unattributed noise in the log.
     */
    private static DocumentBuilder newSecureDocumentBuilder() throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setErrorHandler(new ErrorHandler() {
            @Override public void warning(SAXParseException e) { }
            @Override public void error(SAXParseException e) throws SAXParseException { throw e; }
            @Override public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
        });
        return db;
    }

    /** Compacts an exception into a single short log-friendly reason. */
    private static String shortReason(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return e.getClass().getSimpleName();
        // DSS wraps transport errors as "Unable to process GET call for url [...]. Reason : [real cause]"
        int marker = msg.indexOf("Reason : [");
        if (marker >= 0) {
            msg = msg.substring(marker + "Reason : [".length());
            if (msg.endsWith("]")) msg = msg.substring(0, msg.length() - 1);
        }
        msg = msg.replaceAll("\\s+", " ").trim();
        return msg.length() > 120 ? msg.substring(0, 117) + "..." : msg;
    }

    /** MIME type of the machine-processable Trusted List, per ETSI TS 119 612 clause 5.3.10. */
    private static final String TSL_XML_MIME_TYPE = "application/vnd.etsi.tsl+xml";

    /**
     * Extracts national TL {@code TSLLocation} URLs from a LOTL XML document.
     * LOTL self-references (EU Commission pivot entries on {@code ec.europa.eu}) are excluded.
     *
     * <p>Each member state is listed twice: once for the machine-processable XML list and once for
     * the human-readable PDF rendering of it, each in its own {@code OtherTSLPointer} with a
     * {@code MimeType}. Only the XML pointers belong here. Collecting every {@code TSLLocation}
     * in the document meant fetching 11 PDFs (several hundred kB each) on every freshness run only
     * to find no {@code ListIssueDateTime} in them, and — because a list that cannot be read is
     * never marked as confirmed — reporting all 11 to the user as unverified Trusted Lists
     * fourteen days after each full refresh, permanently.
     *
     * <p>The discrimination has to be on {@code MimeType}, not on the URL: the LOTL of August 2026
     * serves Denmark's PDF from {@code .../TSLDK_v6pdf} and its XML from {@code .../TSLDK_v6xml},
     * and the Czech XML list from {@code .../TSL_CZ.xtsl}. A pointer that declares no MimeType at
     * all is kept — losing a real Trusted List would be the worse mistake.
     */
    private static List<String> parseNationalTlUrls(byte[] lotlBytes) throws Exception {
        Document doc = newSecureDocumentBuilder().parse(new ByteArrayInputStream(lotlBytes));
        NodeList pointers =
                doc.getElementsByTagNameNS("http://uri.etsi.org/02231/v2#", "OtherTSLPointer");
        if (pointers.getLength() == 0) pointers = doc.getElementsByTagName("OtherTSLPointer");
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < pointers.getLength(); i++) {
            if (!(pointers.item(i) instanceof Element pointer)) continue;
            String mimeType = firstDescendantText(pointer, "MimeType");
            if (mimeType != null && !TSL_XML_MIME_TYPE.equals(mimeType)) continue;
            String url = firstDescendantText(pointer, "TSLLocation");
            // Skip LOTL self-pointers — pivot entries are always on ec.europa.eu
            if (url == null || !url.startsWith("http") || url.contains("ec.europa.eu")) continue;
            if (!urls.contains(url)) urls.add(url);
        }
        return urls;
    }

    /**
     * Text of the first descendant with the given local name, in whatever namespace — inside a
     * pointer the location is in the TSL namespace while its MimeType is in the additional-types
     * one, and neither prefix is fixed by the standard. Null when absent or blank.
     */
    private static String firstDescendantText(Element scope, String localName) {
        NodeList all = scope.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node node = all.item(i);
            String name = node.getLocalName();
            if (name == null) {   // non-namespace-aware fallback: strip any prefix ourselves
                name = node.getNodeName();
                int colon = name.indexOf(':');
                if (colon >= 0) name = name.substring(colon + 1);
            }
            if (!localName.equals(name)) continue;
            String text = node.getTextContent();
            return text == null || text.isBlank() ? null : text.trim();
        }
        return null;
    }

    /**
     * Parses the {@code ListIssueDateTime} element from a national Trusted List XML.
     * Returns {@code null} when the element is absent; throws when the payload is not
     * parseable XML or the date is malformed, so the caller can report which TL failed
     * instead of silently treating it as current.
     */
    private static java.time.Instant parseTlListIssueDateTime(byte[] tlBytes) throws Exception {
        Document doc = newSecureDocumentBuilder().parse(new ByteArrayInputStream(tlBytes));
        NodeList nl = doc.getElementsByTagNameNS("http://uri.etsi.org/02231/v2#", "ListIssueDateTime");
        if (nl.getLength() == 0) nl = doc.getElementsByTagName("ListIssueDateTime");
        if (nl.getLength() == 0) return null;
        return java.time.Instant.parse(nl.item(0).getTextContent().trim());
    }

    /** Writes the TLInfo fields (URL, territory, version, seqNum, issueDate, nextUpdateDate, isValid). */
    private static void writeTLEntry(DataOutputStream dos, TLInfo tl) throws IOException {
        dos.writeUTF(tl.getUrl() != null ? tl.getUrl() : "");
        TLParsingInfoRecord pr = tl.getParsingCacheInfo();
        dos.writeUTF(pr != null && pr.getTerritory() != null ? pr.getTerritory() : "");
        dos.writeInt(pr != null && pr.getVersion() != null ? pr.getVersion() : 5);
        dos.writeInt(pr != null && pr.getSequenceNumber() != null ? pr.getSequenceNumber() : 0);
        dos.writeLong(pr != null && pr.getIssueDate() != null
                ? pr.getIssueDate().getTime() : Long.MIN_VALUE);
        dos.writeLong(pr != null && pr.getNextUpdateDate() != null
                ? pr.getNextUpdateDate().getTime() : Long.MIN_VALUE);
        ValidationInfoRecord vr = tl.getValidationCacheInfo();
        dos.writeBoolean(vr != null && vr.isValid());
    }

    /** Reads a TLInfo previously written by {@link #writeTLEntry}. */
    private static TLInfo readTLEntry(DataInputStream dis) throws IOException {
        String url         = dis.readUTF();
        String territory   = dis.readUTF();
        int    version     = dis.readInt();
        int    seqNum      = dis.readInt();
        long   issueDateMs = dis.readLong();
        dis.readLong(); // stored nextUpdateDate — ignored; FAR_FUTURE used instead (see below)
        boolean isValid    = dis.readBoolean();
        Date issueDate = issueDateMs != Long.MIN_VALUE ? new Date(issueDateMs) : null;
        // Use FAR_FUTURE nextUpdateDate so DSS never reports "The trusted list is not
        // considered as fresh!" for our cached TLInfos.  DSS's internal TLInfo objects
        // (produced by onlineRefresh) set lastSuccessSynchronizationTime which suppresses
        // the stale check; our CacheInfoRecord returns null for that field.  Real TL
        // freshness is managed via LOTL sequenceNumber comparison, not this date.
        Date farFuture = new Date(System.currentTimeMillis() + 180L * 24 * 3600 * 1000L);
        CacheInfoRecord rec = new CacheInfoRecord(territory, version, seqNum, issueDate, farFuture, isValid);
        return new TLInfo(rec, rec, rec, url.isEmpty() ? null : url);
    }

    /**
     * Content key under which reconstructed {@link TrustProperties} are shared. Covers everything
     * the object carries that validation reads, so two entries collapse only when nothing can tell
     * them apart — in particular the per-certificate qualifiers written since v5 keep entries
     * separate whenever they genuinely differ.
     */
    private static String trustPropertiesKey(String tlUrl, TrustServiceProvider tsp,
            List<TrustServiceStatusAndInformationExtensions> entries) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(tlUrl).append('|').append(tsp.getTerritory())
          .append('|').append(tsp.getNames()).append('|').append(tsp.getTradeNames());
        for (TrustServiceStatusAndInformationExtensions e : entries) {
            sb.append("||").append(e.getType()).append('|').append(e.getStatus())
              .append('|').append(e.getStartDate()).append('|').append(e.getEndDate())
              .append('|').append(e.getAdditionalServiceInfoUris())
              .append('|').append(e.getServiceSupplyPoints())
              .append('|').append(e.getExpiredCertsRevocationInfo())
              .append('|').append(e.getNames());
            List<ConditionForQualifiers> cfqs = e.getConditionsForQualifiers();
            if (cfqs != null) {
                for (ConditionForQualifiers cfq : cfqs) {
                    sb.append('|').append(cfq.isCritical()).append(cfq.getQualifiers());
                }
            }
        }
        return sb.toString();
    }

    /**
     * Reads one {@link CertificateTrustTime}. Files written before v7 carry no trusted flag; a
     * certificate they mention was always a trust anchor, since the writer had no predicate that
     * could have decided otherwise.
     */
    private static CertificateTrustTime readTrustTime(DataInputStream dis, boolean hasTrustedFlag)
            throws IOException {
        long start = dis.readLong();
        long end   = dis.readLong();
        if (hasTrustedFlag && !dis.readBoolean()) {
            return new CertificateTrustTime(false);
        }
        return new CertificateTrustTime(
                start != Long.MIN_VALUE ? new Date(start) : null,
                end   != Long.MIN_VALUE ? new Date(end)   : null);
    }

    /** Reads a LOTLInfo (TLInfo fields + child TLInfos count + each child). */
    private static LOTLInfo readLOTLEntry(DataInputStream dis) throws IOException {
        String url         = dis.readUTF();
        String territory   = dis.readUTF();
        int    version     = dis.readInt();
        int    seqNum      = dis.readInt();
        long   issueDateMs = dis.readLong();
        dis.readLong(); // stored nextUpdateDate — ignored; FAR_FUTURE used instead (see readTLEntry)
        boolean isValid    = dis.readBoolean();
        Date issueDate = issueDateMs != Long.MIN_VALUE ? new Date(issueDateMs) : null;
        Date farFuture = new Date(System.currentTimeMillis() + 180L * 24 * 3600 * 1000L);
        CacheInfoRecord rec = new CacheInfoRecord(territory, version, seqNum, issueDate, farFuture, isValid);
        LOTLInfo lotl = new LOTLInfo(rec, rec, rec, url.isEmpty() ? null : url);
        int numChildren = dis.readInt();
        List<TLInfo> children = new ArrayList<>(numChildren);
        for (int i = 0; i < numChildren; i++) children.add(readTLEntry(dis));
        lotl.setTlInfos(children);
        return lotl;
    }

    /**
     * CommonsDataLoader builds its own Apache HttpClient with its own SSLContext —
     * it does NOT read javax.net.ssl.trustStore.  When ~/.jdk-cacerts exists we
     * install a TrustStrategy that accepts certificates signed by any CA in that
     * store (e.g. Avast Root CA under SSL interception) while still falling back
     * to the JVM system cacerts for all other certificates (e.g. standard CAs like
     * ICA.cz).  Using setSslTruststore() alone would REPLACE the system trust store,
     * causing legitimate CAs absent from ~/.jdk-cacerts to be rejected.
     */
    static CommonsDataLoader createDataLoader() {
        CommonsDataLoader loader = new CommonsDataLoader();
        applyCustomCaTrustStrategy(loader);
        return loader;
    }

    /**
     * The same loader as {@link #createDataLoader()}, but one an OCSP responder will talk to.
     *
     * <p>DSS's {@code OCSPDataLoader} exists for a single reason: it posts with
     * {@code Content-Type: application/ocsp-request}, and it refuses to have that changed. Handing
     * an {@code OnlineOCSPSource} a plain {@code CommonsDataLoader} silently drops the header, and
     * a responder within its rights to insist on it answers 401 — which reaches DSS as "no OCSP
     * available" and sends preservation off to fetch a CRL instead. Measured on
     * {@code https://qca-g1.digitalsign.pt/ocsp}: 401 with the plain loader, a good response with
     * this one, and a 282 kB CRL embedded in a document where a 2 kB OCSP would have done.
     */
    static eu.europa.esig.dss.service.http.commons.OCSPDataLoader createOcspDataLoader() {
        eu.europa.esig.dss.service.http.commons.OCSPDataLoader loader =
                new eu.europa.esig.dss.service.http.commons.OCSPDataLoader();
        applyCustomCaTrustStrategy(loader);
        return loader;
    }

    /**
     * Loader pro autority časových razítek — s vypnutým rozbalováním odpovědi.
     *
     * <p>I.CA vrací na {@code tsabase.ica.cz} hlavičku {@code Content-Encoding: binary}. To
     * není komprese; server tím říká „není to text", tedy to, co patří do
     * {@code Content-Transfer-Encoding}. Apache HttpClient 5.6, který s sebou přinesla DSS 6.5,
     * to bere jako název kompresního algoritmu, žádný takový nezná a spojení ukončí —
     * uživatel uvidí „Unsupported Content-Encoding: binary" a razítko nedostane, i když mu ho
     * autorita vydala. Starší 5.5.2 z DSS 6.4 hlavičku přecházela mlčky.
     *
     * <p>{@code disableContentCompression()} vyřadí celý ten článek: klient si o kompresi
     * neřekne a přijatá data už nerozbaluje, takže token propadne tak, jak přišel. U razítka
     * se tím nic neztrácí — odpověď je pár kilobajtů DER, které se stlačit stejně nedají.
     * Ostatních loaderů se to netýká: seznamy odvolaných certifikátů bývají velké a komprese
     * se u nich hodí.
     */
    static eu.europa.esig.dss.service.http.commons.TimestampDataLoader createTimestampDataLoader() {
        eu.europa.esig.dss.service.http.commons.TimestampDataLoader loader =
                new eu.europa.esig.dss.service.http.commons.TimestampDataLoader() {
                    @Override
                    protected synchronized org.apache.hc.client5.http.impl.classic.HttpClientBuilder
                            getHttpClientBuilder(String url) {
                        return super.getHttpClientBuilder(url).disableContentCompression();
                    }
                };
        applyCustomCaTrustStrategy(loader);
        return loader;
    }

    private static void applyCustomCaTrustStrategy(CommonsDataLoader loader) {
        File jdkCacerts = new File(System.getProperty("user.home"), ".jdk-cacerts");
        TrustStrategy strategy = buildCombinedTrustStrategy(jdkCacerts);
        if (strategy != null) loader.setTrustStrategy(strategy);
    }

    /**
     * Builds a TrustStrategy that chains multiple additional trust sources and
     * returns true when any of them accepts the certificate chain.  Returning
     * false causes Apache HttpClient to fall back to normal PKIX validation
     * against the JVM system cacerts.
     *
     * <p>Sources (each loaded silently if unavailable):
     * <ol>
     *   <li>~/.jdk-cacerts (PKCS12) — Avast Root CA and other user-added certs</li>
     *   <li>Windows-ROOT — Microsoft Trusted Root Programme; includes CAs such as
     *       I.CA (needed for tsabase.ica.cz) that are absent from JVM cacerts</li>
     *   <li>KeychainStore — totéž na macOS: kořeny, které si systém nese sám</li>
     *   <li>kořeny tuzemských autorit přibalené v aplikaci ({@link CzechCommercialCATrust})</li>
     * </ol>
     *
     * <p><b>Proč jsou přibalené kořeny mezi zdroji pro TLS.</b> Změřeno na tomto JDK:
     * v {@code cacerts} je 118 kořenů a <b>ani jeden český</b>. Na Windows to zachraňuje
     * úložiště {@code Windows-ROOT}; na Linuxu a macOS by spojení s {@code tsabase.ica.cz}
     * skončilo chybou „PKIX path building failed", která vypadá jako porucha na druhé straně.
     * Kořeny přitom aplikace už nese — používá je jako materiál k sestavení řetězu — takže
     * jde jen o to nabídnout je i pro navazování spojení.
     *
     * <p>Rozšiřuje to okruh důvěry pro HTTPS, a to vědomě: jsou to právě ty autority, s jejichž
     * službami aplikace mluví (razítka, OCSP, seznamy odvolaných), a certifikáty pocházejí
     * z jejich vlastních veřejných úložišť. Není to „důvěřuj čemukoli": pro cokoli jiného se
     * stále rozhoduje podle systémových {@code cacerts}.
     *
     * <p>Nezávislé na tom, že totéž úložiště nese Windows: kdyby se z balení zase vytratil modul
     * {@code jdk.crypto.mscapi} (jednou se to stalo), sáhne se sem a razítko se pořídí dál.
     */
    private static TrustStrategy buildCombinedTrustStrategy(File customStore) {
        List<X509TrustManager> managers = new ArrayList<>();

        if (customStore.exists()) {
            try {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                try (FileInputStream is = new FileInputStream(customStore)) {
                    ks.load(is, "changeit".toCharArray());
                }
                collectTrustManagers(ks, managers);
            } catch (Exception ignored) {}
        }

        try {
            KeyStore winRoot = KeyStore.getInstance("Windows-ROOT");
            winRoot.load(null, null);
            collectTrustManagers(winRoot, managers);
        } catch (Exception e) {
            // Not on Windows, or the store is unavailable — and on Windows the second means the
            // runtime was built without jdk.crypto.mscapi, which is a packaging fault worth
            // naming. Silence here cost an afternoon: the roots of the Czech authorities live
            // only in this store, so without it the application cannot reach tsabase.ica.cz at
            // all and reports a PKIX failure that looks like a problem at the other end.
            if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                    .contains("windows")) {
                System.out.println("[TrustConfig] Windows-ROOT není k dispozici (" + e.getMessage()
                        + ") — běhové prostředí zřejmě nemá modul jdk.crypto.mscapi;"
                        + " kořeny z úložiště Windows se nepoužijí");
            }
        }

        // macOS: kořeny, které si nese systém. Typ úložiště dodává poskytovatel Apple, takže
        // na jiných systémech výjimka jen znamená „tady to není" a jde se dál.
        try {
            KeyStore keychain = KeyStore.getInstance("KeychainStore");
            keychain.load(null, null);
            collectTrustManagers(keychain, managers);
        } catch (Exception ignored) {
            // Není macOS, nebo úložiště není k dispozici.
        }

        // Certifikáty tuzemských autorit přibalené v aplikaci. Bez nich by aplikace mimo Windows
        // nenavázala spojení s tuzemskými razítkovacími službami — viz javadoc výše.
        //
        // Berou se kořeny i vydávající autority. Kořen by sám o sobě stačil jen tehdy, kdyby
        // server poslal celý řetěz; když pošle jen svůj certifikát, chybí mezičlánek a spojení
        // padne. Vydávající autorita v roli kotvy to překlene. Certifikáty TLS vydává u I.CA
        // jiná linka („I.CA Public CA") než ta kvalifikovaná, takže bez vydávajících by tenhle
        // zdroj minul právě to, kvůli čemu vznikl.
        try {
            List<eu.europa.esig.dss.model.x509.CertificateToken> certs =
                    new ArrayList<>(CzechCommercialCATrust.loadAllRoots());
            certs.addAll(CzechCommercialCATrust.loadCommercialIssuingCAs());
            if (!certs.isEmpty()) {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                ks.load(null, null);
                int i = 0;
                for (eu.europa.esig.dss.model.x509.CertificateToken t : certs) {
                    ks.setCertificateEntry("anep-" + (i++), t.getCertificate());
                }
                collectTrustManagers(ks, managers);
            }
        } catch (Exception e) {
            System.out.println("[TrustConfig] přibalené certifikáty tuzemských autorit se pro TLS"
                    + " nepodařilo použít: " + e.getMessage());
        }

        if (managers.isEmpty()) return null;

        return (chain, authType) -> {
            for (X509TrustManager tm : managers) {
                try {
                    tm.checkServerTrusted(chain, authType);
                    return true;
                } catch (CertificateException ignored) {}
            }
            return false; // fall back to JVM system cacerts
        };
    }

    private static void collectTrustManagers(KeyStore ks, List<X509TrustManager> target)
            throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (javax.net.ssl.TrustManager m : tmf.getTrustManagers()) {
            if (m instanceof X509TrustManager x) { target.add(x); return; }
        }
    }

    /**
     * Složka mezipaměti důvěryhodných seznamů: {@code %APPDATA%\SignatureAnalyzer\tl-cache},
     * vedle nastavení, vlastních CRL a kotev důvěry. Do 16. 10. 2026 ležela v
     * {@code %USERPROFILE%\.signature-analyzer\tl-cache}, což dokumentace neuváděla — slibuje,
     * že vše uživatelské je v {@code %APPDATA%\SignatureAnalyzer}. Starou složku se nepřebírá:
     * první start po změně seznamy jednou stáhne znovu.
     */
    public static File tlCacheDir() {
        return AppDirs.dataDir().resolve("tl-cache").toFile();
    }

    /**
     * Builds a verifier backed by the EU List of Trusted Lists (LOTL).
     *
     * <p>Startup strategy:
     * <ul>
     *   <li>If the disk cache already contains TL data, we load it immediately
     *       via {@code offlineRefresh()} (no network, typically &lt;1 s) and
     *       kick off a background {@code onlineRefresh()} to silently update the
     *       cache for next time.</li>
     *   <li>On the very first run (empty cache) we do a blocking {@code onlineRefresh()}
     *       which downloads all ~27 EU country trusted lists (~20 MB).</li>
     * </ul>
     *
     * <p>Country TLs are processed in parallel via a fixed thread pool, which
     * dramatically reduces both first-run and background-refresh time.
     */
    public CommonCertificateVerifier buildLotlCertificateVerifier() throws IOException {
        TrustedListsCertificateSource trustedListsCertificateSource =
                new TrustedListsCertificateSource();
        this.trustedSource = trustedListsCertificateSource;

        this.cacheDir = tlCacheDir();
        Files.createDirectories(cacheDir.toPath());

        this.lastRefreshFile = new File(cacheDir, "last-refresh.timestamp");
        this.tlCheckStateFile = new File(cacheDir, "tl-check-state.properties");
        // Značka běhu kontroly ze starších verzí: interval se od 16. 10. 2026 řídí stavem
        // jednotlivých seznamů (viz isAnyNationalTlUpdated), soubor už nic neříká.
        new File(cacheDir, CHECK_RUN_FILE).delete();
        loadTlCheckState();

        // offlineLoader: used only for extractLotlSigningCerts() (single LOTL XML read from cache)
        FileCacheDataLoader offlineLoader = new FileCacheDataLoader();
        offlineLoader.setCacheExpirationTime(Long.MAX_VALUE);
        offlineLoader.setDataLoader(new IgnoreDataLoader());
        offlineLoader.setFileCacheDirectory(cacheDir);

        // cacheOnlyLoader: used as onlineDataLoader during fast parallel startup.
        // onlineRefresh() processes TLs in parallel via the executor; by setting this as the
        // onlineDataLoader (Long.MAX_VALUE expiry, no network fallback), we get parallel
        // processing from disk instead of the sequential offlineRefresh() which took 22 s.
        FileCacheDataLoader cacheOnlyLoader = new FileCacheDataLoader();
        cacheOnlyLoader.setCacheExpirationTime(Long.MAX_VALUE);
        cacheOnlyLoader.setDataLoader(new IgnoreDataLoader());
        cacheOnlyLoader.setFileCacheDirectory(cacheDir);
        this.cacheOnlyLoader = cacheOnlyLoader;

        // networkLoader: used only for actual online refreshes (background or manual).
        FileCacheDataLoader networkLoader = new FileCacheDataLoader();
        networkLoader.setDataLoader(createDataLoader());
        networkLoader.setFileCacheDirectory(cacheDir);
        networkLoader.setCacheExpirationTime(0L);
        this.networkLoader = networkLoader;

        // Determine cache state BEFORE extracting signing certs so we can avoid a
        // network round-trip on every startup when the LOTL XML is already cached.
        // Exclude bookkeeping files so we only count actual TL XML cache entries.
        String[] cached = cacheDir.list((d, name) -> !name.equals("last-refresh.timestamp")
                && !name.equals("tl-check-state.properties")
                && !name.equals(CHECK_RUN_FILE)
                && !name.equals(TRUST_STATE_FILE)
                && !name.equals(COMPACT_STATE_FILE));
        boolean hasCache = cached != null && cached.length > 0;

        // Bootstrap trust: extract the EU Commission's LOTL signing cert from the
        // LOTL XML itself.  Use the cached copy when available (fast disk read);
        // fall back to downloading when the cache is empty (first run only).
        CommonTrustedCertificateSource lotlSigningCerts =
                extractLotlSigningCerts(hasCache ? offlineLoader : networkLoader);

        LOTLSource lotlSource = new LOTLSource();
        lotlSource.setUrl(EU_LOTL_URL);
        lotlSource.setPivotSupport(true);
        lotlSource.setCertificateSource(lotlSigningCerts);
        // Ends each trust anchor's trust where the Trusted List ends the service's positive
        // status — the sunset date.  Without this DSS hands every certificate in the lists an
        // unbounded CertificateTrustTime, and the ETSI trust-anchor checks that read the sunset
        // date (BBB_XCV_HPCCVVT, the Validation Time Sliding initialisation) have nothing to
        // work with, so a service withdrawn at the 2016 eIDAS transition still anchors trust
        // today.  The tolerant variant is deliberate: the strict one would additionally drop
        // ~230 certificates listed only under recognisedatnationallevel or setbynationallaw —
        // services nationally valid but never eIDAS-qualified — and refusing them trust
        // altogether is a separate decision from honouring a withdrawal.
        lotlSource.setTrustAnchorValidityPredicate(
                new GrantedOrRecognizedAtNationalLevelTrustAnchorPeriodPredicate());

        // Worker threads for parallel country-TL processing.
        // They inherit the calling thread's context classloader so DSS ServiceLoader SPIs resolve.
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        this.appClassLoader = cl;
        int nThreads = Math.min(8, Runtime.getRuntime().availableProcessors() * 2);
        ExecutorService executor = Executors.newFixedThreadPool(nThreads, r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setDaemon(true);
            t.setContextClassLoader(cl);
            return t;
        });

        TLValidationJob job = new TLValidationJob();
        job.setOfflineDataLoader(offlineLoader);
        job.setOnlineDataLoader(cacheOnlyLoader); // start with cache-only; switched to networkLoader when needed
        job.setTrustedListCertificateSource(trustedListsCertificateSource);
        job.setListOfTrustedListSources(lotlSource);
        job.setExecutorService(executor);
        this.tlJob = job;

        long t0 = System.currentTimeMillis();
        System.err.printf("[TrustConfig] hasCache=%b%n", hasCache);

        if (hasCache && loadCompactTrustState(trustedListsCertificateSource, cacheDir)) {
            System.err.printf("[TrustConfig] trust state loaded in %d ms (compact v%d)%n",
                    System.currentTimeMillis() - t0, lastCompactVersionLoaded);

            if (lastCompactVersionLoaded >= 7 && cachedLotlSequenceNumber > 0) {
                // ── v7+ fast path: real TLInfos, additionalServiceInfoUris, pre-evaluated qualifiers, TSP/service names, trust times with their trusted flag, service supply points ──
                // Each cert's TrustProperties already holds the correct TLInfo (with real
                // territory and LOTL seqNum) loaded from the v3 binary section.  DSS
                // qualification gets the correct EU member state code without Phase 1.
                // LOTL sequenceNumber check and optional Phase 2 download run in a daemon
                // thread so startup returns immediately after the binary load (~650 ms).
                System.err.printf("[TrustConfig] v7 ready (LOTL seq=%d) — Phase 1 skipped, TL freshness check starting in background%n",
                        cachedLotlSequenceNumber);
                Thread lotlCheck = new Thread(() -> {
                    if (isAnyNationalTlUpdated()) {
                        startNetworkRefresh(job, networkLoader, cacheOnlyLoader, cl,
                                trustedListsCertificateSource);
                    } else {
                        // Kontrola potvrdila seznamy u zdroje, takže se posunul čas v hlavičce.
                        fireTrustStateChanged();
                    }
                }, "lotl-freshness-check");
                lotlCheck.setDaemon(true);
                lotlCheck.setPriority(Thread.MIN_PRIORITY);
                lotlCheck.setContextClassLoader(cl);
                lotlCheck.start();
            } else {
                // ── Older binary: Phase 1 needed to rebuild what it cannot supply ──
                // v1/v2 lack real TrustServiceProvider data (country code, etc.) which DSS needs
                // for correct qualification protocol output; v3–v6 predate the trust anchor
                // validity predicate and so carry no sunset dates, the ServiceSupplyPoints
                // or expiredCertsRevocationInfo. Phase 1 re-processes the
                // cached TL XMLs via onlineRefresh() — no network — and the current binary is
                // written afterwards, so this costs a few seconds once per format change.
                doPhase1CacheRefresh(job, cacheOnlyLoader);
                saveCompactTrustState(trustedListsCertificateSource, cacheDir);
                if (isAnyNationalTlUpdated()) {
                    startNetworkRefresh(job, networkLoader, cacheOnlyLoader, cl,
                            trustedListsCertificateSource);
                }
            }
        } else if (hasCache && loadTrustState(trustedListsCertificateSource, cacheDir)) {
            // ── Legacy path: old trust-state.ser (first run after code update) ────────
            System.err.printf("[TrustConfig] legacy trust state loaded in %d ms — migrating%n",
                    System.currentTimeMillis() - t0);
            doPhase1CacheRefresh(job, cacheOnlyLoader);
            saveCompactTrustState(trustedListsCertificateSource, cacheDir);
            new File(cacheDir, TRUST_STATE_FILE).delete();
            if (isAnyNationalTlUpdated()) {
                startNetworkRefresh(job, networkLoader, cacheOnlyLoader, cl,
                        trustedListsCertificateSource);
            }
        } else {
            // ── Slow path (first run or missing / stale state) ────────────────────────
            // Full TL processing via onlineRefresh().  Saves a v2 compact state file
            // afterwards so the next startup uses the fast path and skips Phase 1.
            System.err.println("[TrustConfig] trust state missing — doing full refresh (slow)");
            job.setOnlineDataLoader(networkLoader);
            job.onlineRefresh();
            postOnlineRefreshFixup(trustedListsCertificateSource);
            job.setOnlineDataLoader(cacheOnlyLoader);
            touchLastRefreshFile();
            recordRefreshedLists(job);
            saveCompactTrustState(trustedListsCertificateSource, cacheDir);
            System.err.printf("[TrustConfig] full refresh done in %d ms%n",
                    System.currentTimeMillis() - t0);
        }

        CommonCertificateVerifier verifier = new CommonCertificateVerifier();
        verifier.setTrustedCertSources(trustedListsCertificateSource);
        configureOnlineRevocationSources(verifier);
        // DefaultAIASource()'s no-arg constructor uses NativeHTTPDataLoader, which ignores
        // our Avast-aware truststore and fails HTTPS AIA fetches under Avast SSL interception
        // (same issue as LOTL fetching — see createDataLoader()). Use the configured loader
        // so issuer certificates can actually be downloaded for chain building.
        verifier.setAIASource(new DefaultAIASource(createDataLoader()));
        return verifier;
    }

    /**
     * Gives a verifier the online OCSP and CRL sources, both on loaders that work here.
     *
     * <p>Validation used to build these with DSS's no-arg constructors while preservation built
     * its own, and each half had one thing right: validation posted OCSP requests with the correct
     * content type but over a TLS stack that fails under Avast interception, preservation had the
     * Avast-aware stack but lost the content type. A document could therefore be validated against
     * an OCSP that preservation could not fetch, and — measured on the European Commission's seal
     * — end up INDETERMINATE for want of revocation data that was there all along.
     *
     * <p>The AIA source is set by the callers, which know whether they want one at all.
     */
    private static void configureOnlineRevocationSources(CommonCertificateVerifier verifier) {
        OnlineOCSPSource ocspSource = new OnlineOCSPSource();
        ocspSource.setDataLoader(createOcspDataLoader());
        verifier.setOcspSource(ocspSource);

        OnlineCRLSource crlSource = new OnlineCRLSource();
        crlSource.setDataLoader(createDataLoader());
        verifier.setCrlSource(crlSource);
    }

    /**
     * Downloads the LOTL XML and extracts every X.509 certificate found inside
     * its {@code ds:X509Certificate} elements (the signer cert plus any chain
     * certs embedded in the KeyInfo).  All are added to a trusted source so DSS
     * can validate the LOTL signature.
     */
    private CommonTrustedCertificateSource extractLotlSigningCerts(FileCacheDataLoader loader) {
        CommonTrustedCertificateSource src = new CommonTrustedCertificateSource();
        try {
            byte[] lotlBytes = loader.get(EU_LOTL_URL);

            Document doc = newSecureDocumentBuilder().parse(new ByteArrayInputStream(lotlBytes));

            NodeList certNodes = doc.getElementsByTagNameNS(
                    "http://www.w3.org/2000/09/xmldsig#", "X509Certificate");

            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (int i = 0; i < certNodes.getLength(); i++) {
                String b64 = certNodes.item(i).getTextContent().replaceAll("\\s+", "");
                byte[] der = Base64.getDecoder().decode(b64);
                X509Certificate x509 = (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(der));
                src.addCertificate(new CertificateToken(x509));
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        return src;
    }

    /** Builds a verifier backed by a static trust store and live revocation checks. */
    public CommonCertificateVerifier buildCertificateVerifier(File trustStoreFile, String trustStorePassword)
            throws IOException, KeyStoreException, NoSuchAlgorithmException, CertificateException {

        CommonCertificateVerifier certificateVerifier = new CommonCertificateVerifier();

        CommonTrustedCertificateSource trustedCertSource = new CommonTrustedCertificateSource();
        if (trustStoreFile != null && trustStoreFile.exists()) {
            try (FileInputStream fis = new FileInputStream(trustStoreFile)) {
                KeyStoreCertificateSource keyStoreSource = new KeyStoreCertificateSource(
                        fis, "PKCS12", trustStorePassword.toCharArray());
                trustedCertSource.importAsTrusted(keyStoreSource);
            }
        }
        certificateVerifier.setTrustedCertSources(trustedCertSource);
        configureOnlineRevocationSources(certificateVerifier);
        certificateVerifier.setAIASource(new DefaultAIASource(createDataLoader()));
        return certificateVerifier;
    }

    /** No trust anchors, no network — structural/cryptographic check only. */
    public CommonCertificateVerifier buildOfflineCertificateVerifier() {
        return new CommonCertificateVerifier();
    }

    /**
     * Saves trust data to a compact custom binary file ({@code trust-state-v2.bin}) in v3 format.
     *
     * <p>v3 layout: TLInfo section FIRST (LOTL + per-country TLInfos with URL/territory/seqNum),
     * then cert section where each {@link TrustProperties} records the TL URL it belongs to.
     * On load, a {@code Map<url, TLInfo>} is built from the TLInfo section so each cert's
     * TrustProperties gets the correct real TLInfo (with territory), enabling Phase 1 to be
     * skipped entirely on subsequent startups.
     */
    /**
     * Territories whose trusted list the state knows about but from which not a single certificate
     * came. Reported so that a list which silently dropped out shows up as itself.
     *
     * <p>The count of country lists says how many the LOTL announced and DSS kept a record for,
     * which is not the same as how many actually contributed anything: a list that fails to
     * download keeps its record and goes on being counted. The two numbers were only ever the same
     * by luck, and when they parted — Portugal vanished from the state on 12 August 2026 while the
     * line still read "31 country TLs" — the loss surfaced a week later as a qualified seal of the
     * European Commission reported as merely advanced. Nothing in the log had said a word.
     *
     * @param knownTls   the country lists the state has metadata for
     * @param certsByUrl how many certificates arrived from each list, keyed by its URL
     */
    static List<String> emptyTerritories(List<TLInfo> knownTls, Map<String, Integer> certsByUrl) {
        List<String> empty = new ArrayList<>();
        for (TLInfo tl : knownTls) {
            if (tl == null || tl.getUrl() == null || "cache://stub".equals(tl.getUrl())) continue;
            if (certsByUrl.getOrDefault(tl.getUrl(), 0) > 0) continue;
            TLParsingInfoRecord pr = tl.getParsingCacheInfo();
            String territory = pr != null && pr.getTerritory() != null && !pr.getTerritory().isEmpty()
                    ? pr.getTerritory() : tl.getUrl();
            if (!empty.contains(territory)) empty.add(territory);
        }
        return empty;
    }

    /** Táž informace do logu, tedy s předsazenou čárkou nebo prázdná. */
    private static String emptyTerritoriesLog(List<String> empty) {
        return empty.isEmpty() ? "" : ", bez certifikátů: " + String.join(", ", empty);
    }

    /**
     * Země, jejichž důvěryhodný seznam stav sice zná, ale nepřišel z něj jediný certifikát.
     *
     * <p>Pro uživatelské rozhraní: podpis vydaný v takové zemi se neověří jako kvalifikovaný,
     * protože není z čeho postavit řetěz — a je to jediná porucha stavu důvěry, kterou nelze
     * poznat z výsledku ověření, jenom uhodnout. Zastaralý seznam se proti tomu pozná snadno
     * a nic nerozbíjí, viz {@link #getStaleTrustLists()}.
     */
    public List<String> getEmptyTerritories() {
        return emptyTerritoriesFound;
    }

    /** Naplní se při uložení i při načtení stavu; do té doby prázdné. */
    private volatile List<String> emptyTerritoriesFound = List.of();

    /** Every country list the state knows: the LOTLs' children plus the standalone ones. */
    private static List<TLInfo> allCountryTls(List<? extends LOTLInfo> lotls, List<TLInfo> others) {
        List<TLInfo> all = new ArrayList<>();
        for (LOTLInfo lotl : lotls) {
            if (lotl.getTLInfos() != null) all.addAll(lotl.getTLInfos());
        }
        all.addAll(others);
        return all;
    }

    private void saveCompactTrustState(TrustedListsCertificateSource src, File dir) {
        File stateFile = new File(dir, COMPACT_STATE_FILE);
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(stateFile), 1 << 20))) {

            dos.writeInt(COMPACT_MAGIC);
            dos.writeShort(COMPACT_VERSION);

            // ── Section 1: TLInfo metadata (written FIRST so it loads before certs) ──────
            TLValidationJobSummary summary = src.getSummary();
            List<LOTLInfo> lotls  = (summary != null) ? summary.getLOTLInfos()     : Collections.emptyList();
            List<TLInfo>   others = (summary != null) ? summary.getOtherTLInfos()  : Collections.emptyList();
            // Real LOTLs are those that already have country TL children from Phase 1 or 2.
            List<LOTLInfo> realLotls = lotls.stream()
                    .filter(l -> l.getTLInfos() != null && !l.getTLInfos().isEmpty())
                    .collect(java.util.stream.Collectors.toList());
            // Filter out the synthetic STUB from others (it is re-injected by postOnlineRefreshFixup).
            List<TLInfo> realOthers = others.stream()
                    .filter(tl -> !"cache://stub".equals(tl.getUrl()))
                    .collect(java.util.stream.Collectors.toList());

            dos.writeInt(realLotls.size());
            for (LOTLInfo lotl : realLotls) {
                writeTLEntry(dos, lotl);
                List<TLInfo> children = lotl.getTLInfos();
                dos.writeInt(children.size());
                for (TLInfo child : children) writeTLEntry(dos, child);
            }
            dos.writeInt(realOthers.size());
            for (TLInfo tl : realOthers) writeTLEntry(dos, tl);

            // ── Section 2: Cert data ──────────────────────────────────────────────────────
            List<CertificateToken> certs = new ArrayList<>(src.getCertificates());
            dos.writeInt(certs.size());

            // Kolik certifikátů přišlo z které země — viz emptyTerritories().
            Map<String, Integer> certsByTlUrl = new HashMap<>();

            for (CertificateToken cert : certs) {
                byte[] der = cert.getEncoded();
                dos.writeInt(der.length);
                dos.write(der);

                List<TrustProperties> props = src.getTrustServices(cert);
                dos.writeInt(props == null ? 0 : props.size());
                java.util.Set<String> urlsOfThisCert = new java.util.HashSet<>();
                if (props != null) {
                    for (TrustProperties tp : props) {
                        // TL URL: identifies which country TL this TrustProperties belongs to.
                        // On load (v3) this URL is used to look up the real TLInfo so DSS
                        // qualification gets the correct territory without running Phase 1.
                        TLInfo tpTlInfo = tp.getTLInfo();
                        String tlUrl = (tpTlInfo != null && tpTlInfo.getUrl() != null
                                && !"cache://stub".equals(tpTlInfo.getUrl()))
                                ? tpTlInfo.getUrl() : "";
                        dos.writeUTF(tlUrl);
                        if (!tlUrl.isEmpty()) urlsOfThisCert.add(tlUrl);

                        // v6: TSP names and trade names — required by isMatch() in
                        // TrustedCertificateMatchTrustServiceCheck to compare the CA cert DN
                        // against the TSP/service names without running Phase 1.
                        TrustServiceProvider tsp = tp.getTrustServiceProvider();
                        Map<String, List<String>> tspNamesMap = (tsp != null) ? tsp.getNames() : null;
                        Map<String, List<String>> tspTradeNamesMap = (tsp != null) ? tsp.getTradeNames() : null;
                        writeNamesList(dos, tspNamesMap);
                        writeNamesList(dos, tspTradeNamesMap);

                        List<TrustServiceStatusAndInformationExtensions> entries = new ArrayList<>();
                        for (TrustServiceStatusAndInformationExtensions e : tp.getTrustService()) {
                            entries.add(e);
                        }
                        dos.writeInt(entries.size());
                        for (TrustServiceStatusAndInformationExtensions svc : entries) {
                            dos.writeUTF(svc.getType()   != null ? svc.getType()   : "");
                            dos.writeUTF(svc.getStatus() != null ? svc.getStatus() : "");
                            dos.writeLong(svc.getStartDate() != null ? svc.getStartDate().getTime() : Long.MIN_VALUE);
                            dos.writeLong(svc.getEndDate()   != null ? svc.getEndDate().getTime()   : Long.MIN_VALUE);
                            List<ConditionForQualifiers> cfqs = svc.getConditionsForQualifiers();
                            // v5: pre-evaluate conditions against this specific cert so that only
                            // qualifiers that genuinely apply are stored.  On load ALWAYS_TRUE is
                            // used, but the stored list is already cert-specific so no conflicting
                            // qualifiers (e.g. QCWithQSCD + X_QSCD) end up on the same cert.
                            List<ConditionForQualifiers> matchingCfqs = new ArrayList<>();
                            if (cfqs != null) {
                                for (ConditionForQualifiers cfq : cfqs) {
                                    Condition cond = cfq.getCondition();
                                    if (cond == null || cond.check(cert)) matchingCfqs.add(cfq);
                                }
                            }
                            dos.writeInt(matchingCfqs.size());
                            for (ConditionForQualifiers cfq : matchingCfqs) {
                                dos.writeBoolean(cfq.isCritical());
                                List<String> quals = cfq.getQualifiers();
                                dos.writeInt(quals == null ? 0 : quals.size());
                                if (quals != null) {
                                    for (String q : quals) dos.writeUTF(q);
                                }
                            }
                            // v4+: additionalServiceInfoUris (e.g. ForeSignatures/ForeSeals/ForWebAuth)
                            // needed by DSS ServiceByCertificateTypeFilter for post-eIDAS qualification.
                            List<String> uris = svc.getAdditionalServiceInfoUris();
                            dos.writeInt(uris == null ? 0 : uris.size());
                            if (uris != null) {
                                for (String u : uris) dos.writeUTF(u);
                            }
                            // v6: service names — needed by isMatch() to match CA cert DN against
                            // service names in TrustedCertificateMatchTrustServiceCheck.
                            writeNamesList(dos, svc.getNames());
                            // v7: ServiceSupplyPoints — the revocation endpoints the list itself
                            // publishes, and the only ones available when AIA and CDP fail.
                            List<String> supplyPoints = svc.getServiceSupplyPoints();
                            dos.writeInt(supplyPoints == null ? 0 : supplyPoints.size());
                            if (supplyPoints != null) {
                                for (String sp : supplyPoints) dos.writeUTF(sp);
                            }
                            // v7: expiredCertsRevocationInfo — from this date the service keeps
                            // publishing revocation data for certificates that have expired.
                            Date expiredCertsInfo = svc.getExpiredCertsRevocationInfo();
                            dos.writeLong(expiredCertsInfo != null
                                    ? expiredCertsInfo.getTime() : Long.MIN_VALUE);
                        }
                    }
                }

                CertificateTrustTime tt = src.getTrustTime(cert);
                dos.writeBoolean(tt != null);
                if (tt != null) {
                    dos.writeLong(tt.getStartDate() != null ? tt.getStartDate().getTime() : Long.MIN_VALUE);
                    dos.writeLong(tt.getEndDate()   != null ? tt.getEndDate().getTime()   : Long.MIN_VALUE);
                    // v7: a certificate whose services never held a positive status is not a
                    // trust anchor at all, which the dates alone cannot express — both are absent
                    // there, the same shape as "trusted without limit".
                    dos.writeBoolean(tt.isTrusted());
                }
                for (String u : urlsOfThisCert) certsByTlUrl.merge(u, 1, Integer::sum);
            }

            List<TLInfo> countryTls = allCountryTls(realLotls, realOthers);
            emptyTerritoriesFound = emptyTerritories(countryTls, certsByTlUrl);
            System.err.printf("[TrustConfig] compact trust state saved (v%d): %d certs, %d LOTLs, "
                            + "%d country TLs (%.1f MB)%s%n",
                    COMPACT_VERSION, certs.size(), realLotls.size(), countryTls.size(),
                    stateFile.length() / 1_048_576.0,
                    emptyTerritoriesLog(emptyTerritoriesFound));
        } catch (Exception e) {
            System.err.println("[TrustConfig] saveCompactTrustState failed: " + e.getMessage());
            stateFile.delete();
        }
    }

    /**
     * Loads the compact binary trust state (v1, v2, or v3) produced by
     * {@link #saveCompactTrustState}.
     *
     * <p>v1/v2: cert data with STUB_TL_INFO — Phase 1 must still run to build real TrustProperties.
     *
     * <p>v3: TLInfo section comes FIRST; each cert's TrustProperties records the TL URL it
     * belongs to.  A {@code Map<url, TLInfo>} built from the TLInfo section lets each cert get
     * its correct real TLInfo (with territory) on load, so Phase 1 can be skipped entirely.
     * Sets {@link #lastCompactVersionLoaded} to reflect which version was read.
     */
    private boolean loadCompactTrustState(TrustedListsCertificateSource target, File dir) {
        File stateFile = new File(dir, COMPACT_STATE_FILE);
        if (!stateFile.exists()) return false;
        lastCompactVersionLoaded = 0;
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(stateFile), 1 << 20))) {

            int magic = dis.readInt();
            if (magic != COMPACT_MAGIC) { stateFile.delete(); return false; }
            short version = dis.readShort();
            if (version < 1 || version > 7) { stateFile.delete(); return false; }

            CertificateFactory cf = CertificateFactory.getInstance("X.509");

            if (version >= 3) {
                // ── v3/v4/v5/v6: TLInfo section FIRST ───────────────────────────────────
                // Step 1: read TLInfo section, build URL→TLInfo and URL→LOTLInfo lookup maps.
                int numLotls = dis.readInt();
                List<LOTLInfo> lotls = new ArrayList<>(numLotls);
                Map<String, TLInfo>   tlByUrl   = new HashMap<>(64);
                Map<String, LOTLInfo> lotlByUrl = new HashMap<>(8);
                for (int i = 0; i < numLotls; i++) {
                    LOTLInfo lotl = readLOTLEntry(dis);
                    lotls.add(lotl);
                    for (TLInfo child : lotl.getTLInfos()) {
                        if (child.getUrl() != null && !child.getUrl().isEmpty()) {
                            tlByUrl.put(child.getUrl(), child);
                            // Map each country TL URL to its parent LOTLInfo so we can pass
                            // LOTLInfo to the TrustProperties 4-arg constructor (required for
                            // DSS qualification — tp.getLOTLInfo() must be non-null).
                            lotlByUrl.put(child.getUrl(), lotl);
                        }
                    }
                }
                int numOthers = dis.readInt();
                List<TLInfo> othersList = new ArrayList<>(numOthers);
                for (int i = 0; i < numOthers; i++) {
                    TLInfo other = readTLEntry(dis);
                    othersList.add(other);
                    if (other.getUrl() != null && !other.getUrl().isEmpty()) {
                        tlByUrl.put(other.getUrl(), other);
                    }
                }
                if (!lotls.isEmpty()) {
                    TLParsingInfoRecord pr = lotls.get(0).getParsingCacheInfo();
                    cachedLotlSequenceNumber = (pr != null && pr.getSequenceNumber() != null)
                            ? pr.getSequenceNumber() : -1;
                }

                // Step 2: read cert section with per-TrustProperties TL URL.
                int numCerts = dis.readInt();
                Map<CertificateToken, List<TrustProperties>> trustMap  = new LinkedHashMap<>(numCerts * 2);
                Map<CertificateToken, List<CertificateTrustTime>> timeMap = new LinkedHashMap<>(numCerts * 2);
                Map<String, TrustProperties> interned = new HashMap<>(numCerts);
                Map<String, Integer> certsByTlUrl = new HashMap<>();

                for (int i = 0; i < numCerts; i++) {
                    byte[] der = new byte[dis.readInt()];
                    dis.readFully(der);
                    CertificateToken cert = new CertificateToken(
                            (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));

                    int numProps = dis.readInt();
                    List<TrustProperties> props = new ArrayList<>(numProps);
                    java.util.Set<String> urlsOfThisCert = new java.util.HashSet<>();
                    for (int p = 0; p < numProps; p++) {
                        String tlUrl = dis.readUTF();
                        if (!tlUrl.isEmpty()) urlsOfThisCert.add(tlUrl);
                        // Look up the real TLInfo and its parent LOTLInfo by URL.
                        TLInfo   tlInfo   = tlUrl.isEmpty() ? STUB_TL_INFO
                                : tlByUrl.getOrDefault(tlUrl, STUB_TL_INFO);
                        LOTLInfo lotlInfo = tlUrl.isEmpty() ? null : lotlByUrl.get(tlUrl);

                        // Build TSP with territory so DSS qualification can identify the
                        // EU member state without needing Phase 1 to supply it.
                        TrustServiceProvider tsp = new TrustServiceProvider();
                        if (tlInfo != STUB_TL_INFO) {
                            TLParsingInfoRecord pr = tlInfo.getParsingCacheInfo();
                            if (pr != null && pr.getTerritory() != null
                                    && !pr.getTerritory().isEmpty()) {
                                tsp.setTerritory(pr.getTerritory());
                            }
                        }
                        // v6: TSP names needed by TrustedCertificateMatchTrustServiceCheck.isMatch()
                        if (version >= 6) {
                            List<String> tspNames = readNamesList(dis);
                            List<String> tspTradeNames = readNamesList(dis);
                            if (!tspNames.isEmpty())
                                tsp.setNames(Collections.singletonMap("en", tspNames));
                            if (!tspTradeNames.isEmpty())
                                tsp.setTradeNames(Collections.singletonMap("en", tspTradeNames));
                        }

                        int numEntries = dis.readInt();
                        List<TrustServiceStatusAndInformationExtensions> entries =
                                new ArrayList<>(numEntries);
                        for (int e = 0; e < numEntries; e++) {
                            entries.add(readServiceEntry(dis, version >= 4, version >= 6, version >= 7));
                        }
                        // Use 4-arg constructor when LOTLInfo is available: DSS qualification
                        // checks tp.getLOTLInfo() to verify the cert comes from an EU LOTL-based
                        // trust list.  Without it, DSS cannot establish the trust chain and
                        // reports "None of the related trust services may issue certificates of
                        // a suitable type!" and "The trusted list is not considered as fresh!".
                        // Interned by content. DSS keys trust properties by EntityIdentifier, not
                        // by certificate: getTrustServices() returns the union for every
                        // certificate sharing a subject and public key, and that union is what
                        // gets written out once per such certificate. Rebuilding it as fresh
                        // objects defeats the deduplication in
                        // TrustedListsCertificateSource.addCertificate, which is a contains()
                        // over equals, so an entity with three certificates ended up with its
                        // services listed three times — 6071 trust properties where a fresh parse
                        // of the same lists yields 5119, and up to 11 on a single certificate
                        // against 4. Handing back one object per distinct content restores what
                        // the synchronizer does, which is to share one instance across the
                        // certificates of a service.
                        String key = trustPropertiesKey(tlUrl, tsp, entries);
                        TrustProperties tp = interned.get(key);
                        if (tp == null) {
                            tp = (lotlInfo != null)
                                    ? new TrustProperties(lotlInfo, tlInfo, tsp,
                                            new TimeDependentValues<>(entries))
                                    : new TrustProperties(tlInfo, tsp,
                                            new TimeDependentValues<>(entries));
                            interned.put(key, tp);
                        }
                        props.add(tp);
                    }
                    if (!props.isEmpty()) trustMap.put(cert, props);

                    boolean hasTrustTime = dis.readBoolean();
                    if (hasTrustTime) {
                        timeMap.put(cert, List.of(readTrustTime(dis, version >= 7)));
                    }
                    for (String u : urlsOfThisCert) certsByTlUrl.merge(u, 1, Integer::sum);
                }

                target.setTrustPropertiesByCertificates(trustMap);
                target.setTrustTimeByCertificates(timeMap);

                // Summary: real LOTLInfos (with country TL children) + STUB for safety.
                // All cert TrustProperties reference objects from tlByUrl (also in the children
                // list), so DSS's getTLInfoById() lookups resolve correctly without Phase 1.
                List<TLInfo> othersWithStub = new ArrayList<>(othersList);
                othersWithStub.add(STUB_TL_INFO);
                target.setSummary(new TLValidationJobSummary(lotls, othersWithStub));

                List<TLInfo> countryTls = allCountryTls(lotls, othersList);
                emptyTerritoriesFound = emptyTerritories(countryTls, certsByTlUrl);
                System.err.printf("[TrustConfig] compact trust state loaded (v%d): %d certs, "
                                + "%d LOTLs, %d country TLs, LOTL seq=%d%s%n",
                        version, numCerts, lotls.size(), countryTls.size(),
                        cachedLotlSequenceNumber, emptyTerritoriesLog(emptyTerritoriesFound));
                lastCompactVersionLoaded = version;
                return true;
            }

            // ── v1 / v2: cert section first, all TrustProperties use STUB_TL_INFO ────────
            // Phase 1 must run after loading to rebuild real TrustServiceProvider data.
            int numCerts = dis.readInt();
            Map<CertificateToken, List<TrustProperties>> trustMap  = new LinkedHashMap<>(numCerts * 2);
            Map<CertificateToken, List<CertificateTrustTime>> timeMap = new LinkedHashMap<>(numCerts * 2);

            for (int i = 0; i < numCerts; i++) {
                byte[] der = new byte[dis.readInt()];
                dis.readFully(der);
                CertificateToken cert = new CertificateToken(
                        (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));

                int numProps = dis.readInt();
                List<TrustProperties> props = new ArrayList<>(numProps);
                for (int p = 0; p < numProps; p++) {
                    int numEntries = dis.readInt();
                    List<TrustServiceStatusAndInformationExtensions> entries = new ArrayList<>(numEntries);
                    for (int e = 0; e < numEntries; e++) {
                        entries.add(readServiceEntry(dis, false, false, false));
                    }
                    props.add(new TrustProperties(STUB_TL_INFO, new TrustServiceProvider(),
                            new TimeDependentValues<>(entries)));
                }
                if (!props.isEmpty()) trustMap.put(cert, props);

                boolean hasTrustTime = dis.readBoolean();
                if (hasTrustTime) {
                    timeMap.put(cert, List.of(readTrustTime(dis, false)));
                }
            }

            target.setTrustPropertiesByCertificates(trustMap);
            target.setTrustTimeByCertificates(timeMap);

            if (version == 2) {
                // v2: TLInfo section at the end — read only for the LOTL sequenceNumber.
                int numLotls = dis.readInt();
                List<LOTLInfo> lotls = new ArrayList<>(numLotls);
                for (int i = 0; i < numLotls; i++) lotls.add(readLOTLEntry(dis));
                int numOthers = dis.readInt();
                for (int i = 0; i < numOthers; i++) readTLEntry(dis); // consume, discard
                if (!lotls.isEmpty()) {
                    TLParsingInfoRecord pr = lotls.get(0).getParsingCacheInfo();
                    cachedLotlSequenceNumber = (pr != null && pr.getSequenceNumber() != null)
                            ? pr.getSequenceNumber() : -1;
                }
                System.err.printf("[TrustConfig] compact trust state loaded (v2): %d certs, "
                        + "LOTL seq=%d%n", numCerts, cachedLotlSequenceNumber);
            } else {
                System.err.printf("[TrustConfig] compact trust state loaded (v1): %d certs%n", numCerts);
            }
            target.setSummary(new TLValidationJobSummary(
                    Collections.emptyList(), List.of(STUB_TL_INFO)));
            lastCompactVersionLoaded = version;
            return true;
        } catch (Exception e) {
            System.err.println("[TrustConfig] loadCompactTrustState failed (" + e.getMessage()
                    + ") — deleting stale state");
            lastCompactVersionLoaded = 0;
            stateFile.delete();
            return false;
        }
    }

    /** Writes all values from a locale→values map as a flat count + UTF string list. */
    private static void writeNamesList(DataOutputStream dos, Map<String, List<String>> namesMap)
            throws IOException {
        if (namesMap == null || namesMap.isEmpty()) {
            dos.writeInt(0);
            return;
        }
        List<String> flat = new ArrayList<>();
        for (List<String> vals : namesMap.values()) {
            if (vals != null) flat.addAll(vals);
        }
        dos.writeInt(flat.size());
        for (String s : flat) dos.writeUTF(s != null ? s : "");
    }

    /** Reads a flat name list written by {@link #writeNamesList}. */
    private static List<String> readNamesList(DataInputStream dis) throws IOException {
        int count = dis.readInt();
        if (count == 0) return Collections.emptyList();
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(dis.readUTF());
        return result;
    }

    /** Reads one {@link TrustServiceStatusAndInformationExtensions} entry from the stream.
     *  @param readUris  {@code true} for v4+ streams which include additionalServiceInfoUris.
     *  @param readNames {@code true} for v6+ streams which include service names. */
    private static TrustServiceStatusAndInformationExtensions readServiceEntry(
            DataInputStream dis, boolean readUris, boolean readNames, boolean readSupplyPoints)
            throws IOException {
        String type   = dis.readUTF();
        String status = dis.readUTF();
        long startMs  = dis.readLong();
        long endMs    = dis.readLong();
        int numCfqs   = dis.readInt();
        List<ConditionForQualifiers> cfqs = new ArrayList<>(numCfqs);
        for (int q = 0; q < numCfqs; q++) {
            boolean critical = dis.readBoolean();
            int numQuals     = dis.readInt();
            List<String> quals = new ArrayList<>(numQuals);
            for (int j = 0; j < numQuals; j++) quals.add(dis.readUTF());
            cfqs.add(new ConditionForQualifiers(ALWAYS_TRUE, quals, critical));
        }
        List<String> uris = Collections.emptyList();
        if (readUris) {
            int numUris = dis.readInt();
            uris = new ArrayList<>(numUris);
            for (int u = 0; u < numUris; u++) uris.add(dis.readUTF());
        }
        Map<String, List<String>> names = null;
        if (readNames) {
            List<String> svcNames = readNamesList(dis);
            if (!svcNames.isEmpty()) names = Collections.singletonMap("en", svcNames);
        }
        List<String> supplyPoints = Collections.emptyList();
        Date expiredCertsInfo = null;
        if (readSupplyPoints) {
            int numPoints = dis.readInt();
            supplyPoints = new ArrayList<>(numPoints);
            for (int sp = 0; sp < numPoints; sp++) supplyPoints.add(dis.readUTF());
            long expiredMs = dis.readLong();
            if (expiredMs != Long.MIN_VALUE) expiredCertsInfo = new Date(expiredMs);
        }
        return new TrustServiceStatusAndInformationExtensions
                .TrustServiceStatusAndInformationExtensionsBuilder()
                .setType(type.isEmpty() ? null : type)
                .setStatus(status.isEmpty() ? null : status)
                .setConditionsForQualifiers(cfqs)
                .setAdditionalServiceInfoUris(uris)
                .setServiceSupplyPoints(supplyPoints)
                .setExpiredCertsRevocationInfo(expiredCertsInfo)
                .setNames(names)
                .setStartDate(startMs != Long.MIN_VALUE ? new Date(startMs) : null)
                .setEndDate(endMs    != Long.MIN_VALUE ? new Date(endMs)    : null)
                .build();
    }

    /**
     * Tries to load the legacy Java-serialized trust state ({@code trust-state.ser}).
     * Returns {@code false} if the file is missing, corrupted, or from an incompatible
     * class version.
     */
    @SuppressWarnings("unchecked")
    private boolean loadTrustState(TrustedListsCertificateSource target, File dir) {
        File stateFile = new File(dir, TRUST_STATE_FILE);
        if (!stateFile.exists()) return false;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(stateFile))) {
            Map<CertificateToken, List<TrustProperties>> trustMap =
                    (Map<CertificateToken, List<TrustProperties>>) ois.readObject();
            Map<CertificateToken, List<CertificateTrustTime>> timeMap =
                    (Map<CertificateToken, List<CertificateTrustTime>>) ois.readObject();
            target.setTrustPropertiesByCertificates(trustMap);
            target.setTrustTimeByCertificates(timeMap);
            System.err.printf("[TrustConfig] legacy trust state loaded: %d certs%n", trustMap.size());
            return true;
        } catch (Exception e) {
            System.err.println("[TrustConfig] loadTrustState failed (" + e.getMessage()
                    + ") — deleting stale state");
            stateFile.delete();
            return false;
        }
    }

    /**
     * Writes the current epoch millis as plain text into the marker file.
     * More reliable than File.setLastModified(), which silently fails on Windows.
     */
    private void touchLastRefreshFile() {
        try {
            Files.writeString(lastRefreshFile.toPath(),
                    String.valueOf(System.currentTimeMillis()));
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    /**
     * Zapíše k uloženému stavu potvrzení daných seznamů. Slučuje, nepřepisuje: kontrola
     * čerstvosti a ruční obnova z Nastavení mohou běžet současně a každá zná jen své seznamy.
     */
    private synchronized void recordTlChecks(Map<String, Long> confirmed) {
        if (confirmed.isEmpty()) return;
        Map<String, Long> state = new LinkedHashMap<>(tlLastSuccessfulCheck);
        state.putAll(confirmed);
        saveTlCheckState(state);
    }

    /**
     * Srovná uložený stav se seznamy, na které LOTL právě ukazuje: nové dostanou čas posledního
     * úplného stažení, zaniklé vypadnou. Vrací výsledný stav.
     */
    private synchronized Map<String, Long> alignTlCheckState(List<String> tlUrls, long lastRefresh) {
        Map<String, Long> state = new LinkedHashMap<>(tlLastSuccessfulCheck);
        state.keySet().retainAll(tlUrls);
        for (String url : tlUrls) state.putIfAbsent(url, lastRefresh);
        saveTlCheckState(state);
        return state;
    }

    /**
     * Po úplném stažení zapíše jako potvrzené ty národní seznamy, které prošly. Dřív za ně
     * mluvila značka běhu kontroly; teď, kdy se interval počítá po seznamech, by je jinak další
     * start stahoval znovu. Seznam, který při stažení selhal, potvrzený není a zkusí se příště.
     */
    private void recordRefreshedLists(TLValidationJob job) {
        TLValidationJobSummary sum = job == null ? null : job.getSummary();
        if (sum == null) return;
        java.util.Set<String> failed = new java.util.HashSet<>();
        for (ListFailure f : failedLists(job)) failed.add(f.url());
        long now = System.currentTimeMillis();
        Map<String, Long> confirmed = new LinkedHashMap<>();
        for (TLInfo tl : allCountryTls(sum.getLOTLInfos(), sum.getOtherTLInfos())) {
            if (tl == null || tl.getUrl() == null || "cache://stub".equals(tl.getUrl())) continue;
            if (failed.contains(tl.getUrl())) continue;
            confirmed.put(tl.getUrl(), now);
        }
        recordTlChecks(confirmed);
    }

    /**
     * Persists the per-TL check state as {@code url=epochMillis} lines and publishes it
     * to {@link #tlLastSuccessfulCheck} for the UI to read.
     */
    private void saveTlCheckState(Map<String, Long> state) {
        tlLastSuccessfulCheck = Collections.unmodifiableMap(new LinkedHashMap<>(state));
        if (tlCheckStateFile == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Long> e : state.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            Files.writeString(tlCheckStateFile.toPath(), sb.toString());
        } catch (Exception e) {
            System.err.println("[TrustConfig] Could not save TL check state: " + e.getMessage());
        }
    }

    /** Reads the per-TL check state written by {@link #saveTlCheckState}; empty when absent. */
    private void loadTlCheckState() {
        Map<String, Long> state = new LinkedHashMap<>();
        if (tlCheckStateFile != null && tlCheckStateFile.exists()) {
            try {
                for (String line : Files.readAllLines(tlCheckStateFile.toPath())) {
                    int eq = line.lastIndexOf('=');
                    if (eq <= 0) continue;
                    try {
                        state.put(line.substring(0, eq).trim(),
                                Long.parseLong(line.substring(eq + 1).trim()));
                    } catch (NumberFormatException ignored) {
                        // Skip a corrupted line rather than discarding the whole file.
                    }
                }
            } catch (Exception e) {
                System.err.println("[TrustConfig] Could not read TL check state: " + e.getMessage());
            }
        }
        tlLastSuccessfulCheck = Collections.unmodifiableMap(state);
    }

    /**
     * Returns the epoch millis stored in the marker file, or -1 if the file
     * does not exist or cannot be parsed.
     */
    private long readLastRefreshTime() {
        if (lastRefreshFile == null || !lastRefreshFile.exists()) return -1L;
        try {
            return Long.parseLong(Files.readString(lastRefreshFile.toPath()).trim());
        } catch (Exception e) {
            return -1L;
        }
    }
}
