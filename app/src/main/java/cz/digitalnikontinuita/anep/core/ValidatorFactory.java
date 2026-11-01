package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.alert.SilentOnStatusAlert;
import eu.europa.esig.dss.enumerations.TokenExtractionStrategy;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.policy.ValidationPolicyFacade;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.validation.executor.signature.DefaultSignatureProcessExecutor;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.policy.ValidationPolicy;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.model.x509.revocation.crl.CRL;
import eu.europa.esig.dss.model.x509.revocation.ocsp.OCSP;
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource;
import eu.europa.esig.dss.spi.validation.CertificateVerifier;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.validation.RevocationDataLoadingStrategyFactory;
import eu.europa.esig.dss.spi.x509.CertificateSource;
import eu.europa.esig.dss.spi.x509.CommonCertificateSource;
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource;
import eu.europa.esig.dss.spi.x509.revocation.RevocationSource;
import eu.europa.esig.dss.spi.signature.AdvancedSignature;
import eu.europa.esig.dss.spi.x509.tsp.TimestampToken;
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator;
import eu.europa.esig.dss.validation.SignedDocumentValidator;
import eu.europa.esig.dss.validation.reports.Reports;

import cz.digitalnikontinuita.anep.core.OcspFirstCrlFallbackRevocationDataLoadingStrategy.SkippedRevocation;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Collections;

/**
 * Hands a {@code DSSDocument} to DSS and lets it pick the matching validator.
 */
public class ValidatorFactory {

    /**
     * Result of a validation run.
     * {@code skippedRevocations} holds any post-expiry OCSPs that were fetched by
     * {@link OcspFirstCrlFallbackRevocationDataLoadingStrategy} but not returned to DSS
     * because a CRL from the custom store was used instead. These are needed by
     * {@link ReportMapper} to detect revocation-before-BST (S20/S21) even when DSS
     * reports TOTAL_PASSED based on the pre-revocation CRL.
     *
     * <p>{@code unreadableSigningTimes} jsou identifikátory podpisů, které podepsaný atribut
     * signing-time nesou, ale DSS ho nepřečetlo — viz {@link #unreadableSigningTimes(SignedDocumentValidator)}.
     */
    public record ValidationOutcome(Reports reports, List<SkippedRevocation> skippedRevocations,
                                    Map<String, DerivedTimeResult> derivedTimeResults,
                                    java.util.Set<String> unreadableSigningTimes) {
        public ValidationOutcome(Reports reports) {
            this(reports, List.of(), Map.of());
        }

        public ValidationOutcome(Reports reports, List<SkippedRevocation> skippedRevocations) {
            this(reports, skippedRevocations, Map.of());
        }

        public ValidationOutcome(Reports reports, List<SkippedRevocation> skippedRevocations,
                                 Map<String, DerivedTimeResult> derivedTimeResults) {
            this(reports, skippedRevocations, derivedTimeResults, java.util.Set.of());
        }
    }

    /**
     * Závěr téhož ověření, spočítaný k okamžiku, který o existenci prvku svědčí odjinud —
     * z pozdějšího podpisu, který ten dřívější kryje.
     *
     * <p>EN 319 102-1 V1.4.0 tabulka 20 pro to má vyhrazený vstup: <i>„time indication for
     * signature existence"</i>, hodnotu, kterou <b>Driving Application předává SVA</b> jako údaj
     * o době, kdy podpis podle jejího vědomí existoval. Krok 5.5.4 1) jí inicializuje
     * best-signature-time, NOTE 3 mezi přijatelné zdroje řadí i svědectví notáře — a krytí
     * pozdějším podpisem s kvalifikovaným razítkem je doklad nesrovnatelně silnější. NOTE 2
     * u kroku 2) to dopovídá: u {@code REVOKED_NO_POE} <i>„a proof that the signing occurred
     * before the revocation date can help to go from INDETERMINATE to TOTAL-PASSED"</i>.
     *
     * @param when        <b>rozhodný okamžik k zobrazení</b>: nejstarší z dostupných důkazů, tedy
     *                    vlastní platné razítko prvku, je-li starší než razítko krycího prvku.
     *                    Krok 5.5.4 2) říká, že best-signature-time se snižuje na každé platné
     *                    podpisové razítko, které je dřívější, a Rozšířený přehled tentýž údaj
     *                    ukazuje z prvního průchodu — proto se tahle hodnota nesmí řídit tím,
     *                    k čemu se náhodou počítalo.
     * @param controlTime <b>okamžik, ke kterému se doopravdy počítalo.</b> Od {@link #when} se
     *                    liší tam, kde by byl rozhodný okamžik starší než revokační údaj, o který
     *                    se závěr opře — viz {@code chooseControlTime()}.
     * @param indication  co vyšlo k tomu okamžiku
     * @param subIndication  a s jakou pod-indikací; {@code null} u kladného závěru
     * @param reports     celé protokoly druhého průchodu. Nese se celé, ne jen výsledek: prvek,
     *                    jehož závěr vydal tenhle průchod, se z něj má i celý mapovat — situace,
     *                    důvody i rozhodný okamžik patří k závěru, který nese. Jinak by karta
     *                    hlásila „platný" a pod tím vyjmenovala důvody z průchodu prvního.
     * @param poeSupplied hodiny se neposouvaly: {@code controlTime} je okamžik prvního průchodu
     *                    a důkaz existence dostalo DSS jako razítko prvku — viz
     *                    {@code recomputeWithCoveringTimestamp()}.
     * @param envelope    důkaz nepochází z krycího prvku téhož souboru, nýbrž z <b>obalu</b>, z něhož
     *                    byl prvek předán k ověření — viz {@link #withEnvelopeProof}; jinak {@code null}
     */
    public record DerivedTimeResult(java.util.Date when, java.util.Date controlTime,
                                    String indication, String subIndication, Reports reports,
                                    boolean poeSupplied, EnvelopeProof envelope) {
        public DerivedTimeResult(java.util.Date when, java.util.Date controlTime,
                                 String indication, String subIndication, Reports reports) {
            this(when, controlTime, indication, subIndication, reports, false, null);
        }

        public DerivedTimeResult(java.util.Date when, java.util.Date controlTime,
                                 String indication, String subIndication, Reports reports,
                                 boolean poeSupplied) {
            this(when, controlTime, indication, subIndication, reports, poeSupplied, null);
        }
    }

    /**
     * Remembers each {@link CommonCertificateVerifier}'s original trusted certificate
     * sources (LOTL/offline, captured the first time the verifier is seen here) so that
     * the custom trust store layered in by {@link #validate} can be reset and re-applied
     * fresh on every call. The verifier instance is reused across the app's lifetime
     * (built once at startup), so without this reset, trust store edits made via the
     * management dialog would only ever accumulate, never shrink.
     */
    private static final Map<CommonCertificateVerifier, List<CertificateSource>> baseTrustedSources =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Same snapshot/reset treatment as {@link #baseTrustedSources}, but for adjunct sources. */
    private static final Map<CommonCertificateVerifier, List<CertificateSource>> baseAdjunctSources =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Same snapshot/reset treatment as {@link #baseTrustedSources}, but for the CRL source. */
    private static final Map<CommonCertificateVerifier, RevocationSource<CRL>> baseCrlSources =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Totéž pro zdroj OCSP. Bez něj by se úpravy zdroje na sdíleném verifieru vrstvily: aplikace
     * si drží jeden verifier po celou dobu běhu, takže obalení filtrem by v něm zůstalo i pro
     * příští ověření — a to už třeba k žádnému datu neprobíhá. Týž reset zároveň vrací původní
     * zdroj poté, co si ho pro sebe vyměnila tolerance acceptNewerOcsp.
     */
    private static final Map<CommonCertificateVerifier, RevocationSource<OCSP>> baseOcspSources =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Totéž pro {@code RevocationDataVerifier}. Tolerance ho vyměňují za
     * {@link LenientRevocationDataVerifier}, a dokud se nevracel, zůstal ve sdíleném verifieru
     * i pro další běhy — včetně přísných běhů {@link RelaxationMeasurement}, které tak měřily
     * s tolerancí, kterou měly odebrat (nalezeno 2. 10. 2026). Původní hodnota bývá {@code null};
     * DSS si pak výchozí vytvoří samo. Mapa {@code null} nesnese, proto obal.
     */
    private static final Map<CommonCertificateVerifier,
            java.util.Optional<eu.europa.esig.dss.spi.validation.RevocationDataVerifier>>
            baseRevocationDataVerifiers = Collections.synchronizedMap(new WeakHashMap<>());

    /** Same snapshot/reset treatment, but for the revocation loading strategy. */
    private static final Map<CommonCertificateVerifier, RevocationDataLoadingStrategyFactory> baseStrategyFactories =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final AppSettings settings;

    public ValidatorFactory(AppSettings settings) {
        this.settings = settings;
    }

    /** Convenience overload — no PDF password. */
    public ValidationOutcome validate(DSSDocument signatureDoc, DSSDocument detachedContent,
                                      CertificateVerifier certificateVerifier) {
        return validate(signatureDoc, detachedContent, certificateVerifier, null);
    }

    /**
     * @param pdfPassword password for decrypting a password-protected PDF; {@code null} if none.
     */
    public ValidationOutcome validate(DSSDocument signatureDoc, DSSDocument detachedContent,
                                      CertificateVerifier certificateVerifier,
                                      char[] pdfPassword) {
        return validate(signatureDoc, detachedContent, certificateVerifier, pdfPassword, List.of());
    }

    /**
     * @param evidenceRecords evidence records covering {@code signatureDoc} as a whole, supplied
     *                        as separate files. DSS uses the time of each record's first archive
     *                        time-stamp as a proof of existence for the signature and the data it
     *                        covers, so passing them here can turn an otherwise expired signature
     *                        into a valid one. Empty when none were supplied.
     */
    public ValidationOutcome validate(DSSDocument signatureDoc, DSSDocument detachedContent,
                                      CertificateVerifier certificateVerifier,
                                      char[] pdfPassword,
                                      List<DSSDocument> evidenceRecords) {
        return validate(signatureDoc,
                detachedContent != null ? List.of(detachedContent) : List.<DSSDocument>of(),
                certificateVerifier, pdfPassword, evidenceRecords);
    }

    /**
     * @param detachedContents content the signature does not carry itself. Beyond the classic
     *                         detached-signature case this is also how the further documents of
     *                         a group evidence record reach DSS: it hands the detached record
     *                         the primary document plus these, so a record covering five files
     *                         can account for all five rather than reporting four as unchecked.
     */
    public ValidationOutcome validate(DSSDocument signatureDoc, List<DSSDocument> detachedContents,
                                      CertificateVerifier certificateVerifier,
                                      char[] pdfPassword,
                                      List<DSSDocument> evidenceRecords) {
        OcspFirstCrlFallbackRevocationDataLoadingStrategyFactory ocspCrlFactory = null;
        if (certificateVerifier instanceof CommonCertificateVerifier ccv) {
            // Reset trusted/adjunct certificate sources to their original (LOTL-only) state
            // before each run, undoing any mutation from a previous call.
            List<CertificateSource> base = baseTrustedSources.computeIfAbsent(ccv,
                    v -> new ArrayList<>(v.getTrustedCertSources().getSources()));
            ccv.setTrustedCertSources(base.toArray(new CertificateSource[0]));

            List<CertificateSource> adjunctBase = baseAdjunctSources.computeIfAbsent(ccv,
                    v -> new ArrayList<>(v.getAdjunctCertSources().getSources()));
            ccv.setAdjunctCertSources(adjunctBase.toArray(new CertificateSource[0]));

            // Layer in the user's custom trust store on top of the (now reset) LOTL base, on
            // every run — read fresh from disk so additions/removals made in the management
            // dialog take effect immediately, without restarting the app or re-running LOTL.
            List<CertificateToken> customTrusted = CustomTrustStore.loadTrustedCertificates();
            if (!customTrusted.isEmpty()) {
                addTrustedCerts(ccv, customTrusted);
            }

            // Komerční autority se podávají bezpodmínečně jako adjunct (nedůvěryhodný) materiál:
            // některé jejich podpisové certifikáty nemají použitelné AIA, takže bez toho by DSS
            // řetěz nesestavil ani s připnutou kotvou. Je to jen materiál, ne důvěra — co z toho
            // je kotvou, rozhoduje vlastní úložiště. Pro dokument bez vztahu k nim neškodné.
            addAdjunctCerts(ccv, CzechCommercialCATrust.loadCommercialIssuingCAs());
            addAdjunctCerts(ccv, CzechCommercialCATrust.loadAllRoots());

            // Reset the CRL source to its original (online-only) state before each run.
            RevocationSource<CRL> baseCrl = baseCrlSources.computeIfAbsent(ccv, CommonCertificateVerifier::getCrlSource);
            RevocationSource<OCSP> baseOcsp =
                    baseOcspSources.computeIfAbsent(ccv, CommonCertificateVerifier::getOcspSource);
            ccv.setOcspSource(baseOcsp);
            RevocationDataLoadingStrategyFactory baseStrategy = baseStrategyFactories.computeIfAbsent(
                    ccv, CommonCertificateVerifier::getRevocationDataLoadingStrategyFactory);
            ccv.setRevocationDataVerifier(baseRevocationDataVerifiers.computeIfAbsent(ccv,
                    v -> java.util.Optional.ofNullable(v.getRevocationDataVerifier())).orElse(null));

            // Always wrap the online CRL source with FilteringCRLSource: a CRL whose
            // thisUpdate is after the certificate's notAfter AND whose expiredCertsOnCRL
            // extension is absent or also after notAfter makes no authoritative statement
            // about the certificate's revocation status: RFC 5280 clause 3.3 lets the
            // authority drop the entry once the certificate expires. DSS's LTV
            // CRS can bypass isConsistent() and pick up such a CRL via the RAC — where
            // the doesRevocationKnowCertificate check is IGNORED by default policy —
            // so we must block it at the source level before it enters the context.
            // Wrap the online CRL source with FilteringCRLSource — rejects any CRL whose
            // expiredCertsOnCRL (eco) date is after cert.notAfter: per ITU-T X.509 such a
            // CRL does not contain revocation records for a cert that expired before eco, so
            // DSS reading it would produce a false "good" result for a revoked cert.

            // Zadané datum ověření si ústupek „revokační data vydaná po expiraci" uděluje samo:
            // ověřuje-li se ke dni v minulosti, je každý údaj stažený dnes vůči tehdejším
            // certifikátům post-expirační, a bez toho by k datu nešlo ověřit vůbec nic.
            //
            // Je to ale ústupek zúžený, ne plný — na rozdíl od zaškrtávátek, kde si ho uživatel
            // vybral. Propouští jen údaj, který něco dokládá: viz PostExpiryRevokedOnlySource
            // a RFC 5280 §3.3. Změřeno na 1648 podpisech proti plnému ústupku (dnešní stav)
            // i proti žádnému: zúžení nepustí ani jeden odvolaný podpis jako platný, kdežto
            // úplné zrušení ústupku by u 32 podpisů zahodilo zjištěnou revokaci.
            boolean dateGrants = settings.isUseCustomValidationTime()
                    && settings.getValidationDate() != null;
            boolean narrowedByDate = dateGrants && !settings.isAcceptNewerOcsp();

            RevocationSource<CRL> filteredOnlineCrl = new FilteringCRLSource(baseCrl);

            // If the user has CRLs in their custom store, add them as a last-resort fallback:
            // online OCSP and CRL are tried first via DSS's normal strategy; the custom store
            // is only consulted when those return nothing for a given certificate.
            // The same eco filter applies: a custom CRL with eco > cert.notAfter is also not
            // authoritative for that cert and must be rejected to prevent false "good" results.
            List<DSSDocument> customCrlDocs = CustomCrlStore.loadAsDocuments();
            boolean hasCustomCrls = !customCrlDocs.isEmpty();
            if (hasCustomCrls) {
                // BestMatchCustomCrlSource evaluates ALL stored CRLs for each certificate and
                // returns the one that best proves revocation status during the cert's validity
                // period (highest eco < notAfter, or highest thisUpdate < notAfter without eco),
                // among those issued no earlier than the moment being asked about.
                ccv.setCrlSource(narrowPostExpiry(new FallbackCrlSource(filteredOnlineCrl,
                        new BestMatchCustomCrlSource(customCrlDocs,
                                revocationReferenceTime(signatureDoc, detachedContents, pdfPassword))),
                        narrowedByDate));
            } else {
                ccv.setCrlSource(narrowPostExpiry(filteredOnlineCrl, narrowedByDate));
            }
            // Týž filtr na OCSP. Obaluje se vždy původní zdroj, ne ten z minulého běhu.
            if (narrowedByDate && baseOcsp != null) {
                ccv.setOcspSource(new PostExpiryRevokedOnlySource<>(baseOcsp));
            }
            // Každá volba dělá právě jednu věc. relaxConsistency je o stáří údaje —
            // thisUpdate > cert.notAfter — a uděluje ho jen zaškrtávátko, které o tom mluví,
            // plus zadané datum (zúženě, viz výš).
            //
            // Dřív ho zapínalo i acceptExpiredRevocationSources, které je o vydavateli údaje,
            // ne o jeho stáří. Tím obstarávalo i práci té druhé volby a obě se staly
            // zastupitelnými: měření nedokázalo vytknout ani jednu, protože kterákoli sama
            // stačila, a výčet rozhodujících ústupků pak musel jmenovat obě. Kdo si vybral
            // jednu vadu, dostal odpuštěné dvě.
            boolean relaxConsistency = settings.isAcceptNewerOcsp() || dateGrants;

            // For strategy selection we distinguish between truly lenient modes
            // (acceptNewerOcsp, acceptExpiredRevocationSources) and a custom validation date:
            //  • Lenient modes: post-expiry OCSPs are intentionally accepted by the verifier,
            //    so the base strategy should return them directly — the custom CRL store must
            //    NOT intercept them.
            //  • Custom date: online revocation fetched today is post-expiry relative to a
            //    historical certificate; the custom CRL store likely holds a within-validity
            //    CRL that is exactly what we need as a fallback.
            // Jde o to, jestli verifier post-expirační OCSP přijme — a to po rozpojení dělá
            // jedině acceptNewerOcsp. Kdyby tu zůstalo i acceptExpiredRevocationSources,
            // vyřadilo by záložní CRL z vlastního úložiště kvůli toleranci, která se stáří
            // údaje vůbec netýká.
            boolean lenientRevocationMode = settings.isAcceptNewerOcsp();

            // When custom CRLs are present AND we are NOT in a lenient-revocation mode, use
            // OcspFirstCrlFallbackRevocationDataLoadingStrategy: tries OCSP first; falls back
            // to CRL (and thus the custom store via FallbackCrlSource) only when the OCSP is
            // post-expiry or unavailable.
            // The factory is saved so that post-validation, any post-expiry OCSPs that were
            // skipped in favour of the custom CRL can be retrieved and passed to ReportMapper
            // for S20/S21 revocation-before-BST detection.
            ocspCrlFactory = (hasCustomCrls && !lenientRevocationMode)
                    ? new OcspFirstCrlFallbackRevocationDataLoadingStrategyFactory() : null;
            ccv.setRevocationDataLoadingStrategyFactory(
                    ocspCrlFactory != null ? ocspCrlFactory : baseStrategy);

            // When acceptNewerOcsp is set, switch to a lenient OCSP source that suppresses
            // invalid-update-time alerts (some CAs omit nextUpdate; silencing the alert
            // lets DSS keep the response in the token pool for further checks).
            if (settings.isAcceptNewerOcsp()) {
                OnlineOCSPSource lenientOcsp = new OnlineOCSPSource();
                // Same loader the verifier was built with, or switching this tolerance on would
                // quietly take away the content type and the Avast-aware TLS stack that make
                // OCSP reachable at all — see TrustConfig.createOcspDataLoader().
                lenientOcsp.setDataLoader(TrustConfig.createOcspDataLoader());
                lenientOcsp.setAlertOnInvalidUpdateTime(new SilentOnStatusAlert());
                ccv.setOcspSource(lenientOcsp);
            }
            // (relaxConsistency already declared above)
            // relaxAcceptability: retry isAcceptable() with current wall-clock time when the
            // strict check fails. Needed when the OCSP responder's or CRL issuer's own cert has
            // expired — DSS rejects the revocation data during responder-chain validation.
            boolean relaxAcceptability = settings.isAcceptExpiredRevocationSources();
            if (relaxConsistency || relaxAcceptability) {
                ccv.setRevocationDataVerifier(
                        LenientRevocationDataVerifier.createFromDefault(relaxConsistency, relaxAcceptability));
            }
        }

        StartupTrace.mark("  verifier sources prepared");
        ValidationPolicy policy = ValidationPolicyFactory.build(settings);
        StartupTrace.mark("  validation policy built");
        // Tell the trusted-list maintenance threads to stand down: they saturate network and
        // CPU with ~40 TL downloads, which otherwise slows this validation by seconds.
        BackgroundWork.beginForeground();
        Reports reports;
        java.util.Set<String> unreadableSigningTimes = new java.util.HashSet<>();
        try {
            reports = runValidation(signatureDoc, detachedContents, certificateVerifier, policy,
                    pdfPassword, evidenceRecords, unreadableSigningTimes);
        } finally {
            BackgroundWork.endForeground();
        }
        List<SkippedRevocation> skipped = ocspCrlFactory != null
                ? ocspCrlFactory.getSkippedRevocations() : List.of();
        return new ValidationOutcome(reports, skipped, derivedTimeResults(reports, policy),
                java.util.Set.copyOf(unreadableSigningTimes));
    }

    /**
     * Podpisy, které podepsaný atribut signing-time mají, a DSS ho přesto nevidí.
     *
     * <p>DSS u takového podpisu hlásí, že atribut chybí (SIG_CONSTRAINTS_FAILURE), a AnEP z toho
     * dřív bralo situaci 22 se slovy „podpis neobsahuje atribut". Na podpisu z roku 2020
     * (3. 10. 2026) to byla nepravda: čas tam stál, jen jako GeneralizedTime místo UTCTime,
     * které pro léta 1950–2049 předepisuje RFC 5652. Rozdíl je pro čtenáře podstatný — chybějící
     * atribut nedoplní nikdo, kdežto nestandardně zapsaný přečtou tolerantnější ověřovatelé.
     *
     * <p>Jen CAdES (i uvnitř ASiC): u něj je atribut v podepsaných atributech CMS a dá se ověřit,
     * že tam je. Volá se po {@code validateDocument()}, takže podpisy už má validátor načtené.
     */
    private static java.util.Set<String> unreadableSigningTimes(SignedDocumentValidator validator) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            for (AdvancedSignature signature : validator.getSignatures()) {
                if (signature instanceof eu.europa.esig.dss.cades.validation.CAdESSignature cades
                        && cades.getSigningTime() == null
                        && cades.getSignerInformation().getSignedAttributes() != null
                        && cades.getSignerInformation().getSignedAttributes().get(
                                org.bouncycastle.asn1.cms.CMSAttributes.signingTime) != null) {
                    out.add(signature.getId());
                }
            }
        } catch (Exception e) {
            // Nepovede-li se to, zůstane u obecného znění situace 22 — to platí i tak.
        }
        return out;
    }

    /**
     * Druhý závěr pro prvky, o jejichž existenci svědčí pozdější podpis, který je kryje.
     *
     * <p>Počítá se <b>přepočtem nad už získanými daty</b>, ne novým ověřením: procesní executor
     * DSS bere hotovou {@code XmlDiagnosticData} a vlastní „current time". Změřeno 12. 9. 2026 na
     * „PAdES B-B +B-T kvalifikovany ICA.pdf": první ověření 6 190 ms, přepočet <b>47 ms</b>,
     * a bez jediného dotazu do sítě.
     *
     * <p>Posun „current time" je <b>širší</b> než parametr, který norma popisuje: ten inicializuje
     * jen best-signature-time, kdežto tenhle posouvá i posuzování stavu důvěryhodných seznamů
     * a čerstvosti revokačních údajů. Krok 5.5.4 1) ovšem sám říká „<i>to the time indication …
     * or the current time</i>", takže je to cesta, kterou norma zná; kdyby DSS ten parametr někdy
     * vystavilo samostatně, patří sem místo tohohle.
     *
     * <p>Jeden běh na každý <b>různý</b> okamžik, ne na každý prvek: dokument s pěti prvky krytými
     * týmž razítkem se přepočítá jednou.
     */
    private Map<String, DerivedTimeResult> derivedTimeResults(Reports reports,
                                                              ValidationPolicy policy) {
        if (reports == null || reports.getDiagnosticData() == null) return Map.of();
        DiagnosticData dd = reports.getDiagnosticData();
        SimpleReport now = reports.getSimpleReport();

        // Prvky, které by z dřívějšího okamžiku mohly něco získat, a okamžik, který jim svědčí.
        Map<String, ReportMapper.CoveringProof> wanted = new java.util.LinkedHashMap<>();
        Map<String, java.time.Instant> decisive = new java.util.LinkedHashMap<>();
        Map<String, java.time.Instant> control = new java.util.LinkedHashMap<>();
        List<SignatureWrapper> sigs = dd.getSignatures();
        for (SignatureWrapper sw : sigs) {
            if (now.getIndication(sw.getId()) != Indication.INDETERMINATE) continue;
            ReportMapper.CoveringProof proof = ReportMapper.coveringProof(sw, sigs, now);
            if (proof == null) continue;
            wanted.put(sw.getId(), proof);
            // Rozhodný okamžik je ten <b>nejstarší</b> z dostupných důkazů, ne automaticky ten
            // z krycího prvku. Protipodpis i pozdější podpis z povahy věci vznikají až po prvku,
            // který kryjí, takže jejich razítko bývá pozdější než razítko prvku samého —
            // a EN 319 102-1 v kroku 5.5.4 2) říká, že best-signature-time se snižuje na každé
            // platné podpisové razítko, které je dřívější. Brát krycí důkaz i tam, kde má prvek
            // vlastní starší razítko, znamenalo hlásit pozdější okamžik, než je nutné, a text
            // v Analýze se pak rozešel s Rozšířeným přehledem (nález uživatele 13. 9. 2026).
            java.time.Instant own = ReportMapper.earliestValidTimestampTime(sw, now);
            decisive.put(sw.getId(),
                    own != null && own.isBefore(proof.when()) ? own : proof.when());
            control.put(sw.getId(), chooseControlTime(sw, decisive.get(sw.getId()), proof.when()));
        }
        if (wanted.isEmpty()) return Map.of();

        ValidationPolicy effective = policy != null ? policy : defaultPolicy();
        if (effective == null) return Map.of();

        Map<String, SignatureWrapper> byId = new java.util.HashMap<>();
        for (SignatureWrapper sw : sigs) byId.put(sw.getId(), sw);

        Map<String, DerivedTimeResult> out = new java.util.HashMap<>();
        Map<java.util.Date, Reports> byTimeReports = new java.util.HashMap<>();
        for (Map.Entry<String, ReportMapper.CoveringProof> e : wanted.entrySet()) {
            java.util.Date when = java.util.Date.from(decisive.get(e.getKey()));
            java.util.Date at = java.util.Date.from(control.get(e.getKey()));
            Reports recomputed = byTimeReports.computeIfAbsent(at,
                    t -> recompute(reports, effective, t));
            SimpleReport then = recomputed != null ? recomputed.getSimpleReport() : null;
            if (then == null) continue;
            Indication indication = then.getIndication(e.getKey());
            if (indication == null) continue;

            // Druhý pokus, k pozdějšímu okamžiku — viz lateControlTime(). Jen tam, kde první
            // nevyšel kladně: kde vyšel, není co zlepšovat a dřívější okamžik je ten správný.
            if (indication != Indication.TOTAL_PASSED) {
                java.time.Instant late = lateControlTime(byId.get(e.getKey()),
                        decisive.get(e.getKey()), control.get(e.getKey()));
                if (late != null) {
                    java.util.Date lateAt = java.util.Date.from(late);
                    Reports again = byTimeReports.computeIfAbsent(lateAt,
                            t -> recompute(reports, effective, t));
                    SimpleReport thenLate = again != null ? again.getSimpleReport() : null;
                    Indication lateInd = thenLate != null
                            ? thenLate.getIndication(e.getKey()) : null;
                    if (lateInd == Indication.TOTAL_PASSED) {
                        out.put(e.getKey(), new DerivedTimeResult(when, lateAt, lateInd.name(),
                                null, again));
                        continue;
                    }
                }
                // Třetí pokus: k témuž okamžiku jako první, ale bez revokačních údajů, které
                // tehdy ještě nebyly na světě — viz unbornRevocations().
                Reports unborn = recomputeWithoutUnborn(reports, effective, at,
                        byId.get(e.getKey()), decisive.get(e.getKey()));
                SimpleReport thenUnborn = unborn != null ? unborn.getSimpleReport() : null;
                if (thenUnborn != null
                        && thenUnborn.getIndication(e.getKey()) == Indication.TOTAL_PASSED) {
                    out.put(e.getKey(), new DerivedTimeResult(when, at,
                            Indication.TOTAL_PASSED.name(), null, unborn));
                    continue;
                }
                // Čtvrtý pokus: hodiny se neposouvají vůbec, krycí razítko se předá jako důkaz
                // existence — viz recomputeWithCoveringTimestamp().
                Reports supplied = recomputeWithCoveringTimestamp(reports, effective,
                        e.getKey(), e.getValue());
                SimpleReport thenSupplied = supplied != null ? supplied.getSimpleReport() : null;
                if (thenSupplied != null
                        && thenSupplied.getIndication(e.getKey()) == Indication.TOTAL_PASSED) {
                    out.put(e.getKey(), new DerivedTimeResult(when, dd.getValidationDate(),
                            Indication.TOTAL_PASSED.name(), null, supplied, true));
                    continue;
                }
            }
            out.put(e.getKey(), new DerivedTimeResult(when, at, indication.name(),
                    then.getSubIndication(e.getKey()) != null
                            ? then.getSubIndication(e.getKey()).name() : null,
                    byTimeReports.get(at)));
        }
        return Map.copyOf(out);
    }

    /** Vypínač pro měření: {@code -Danep.nolatecontrol} vrátí chování před 22. 9. 2026. */
    private static final boolean LATE_CONTROL_OFF = Boolean.getBoolean("anep.nolatecontrol");

    /**
     * Pozdější kontrolní čas na druhý pokus — <b>nejzazší okamžik, kdy certifikát prvku ještě
     * platil</b>; {@code null}, když takový okamžik nedává smysl.
     *
     * <p><b>Proč vůbec.</b> Posun „current time" je jen přiblížení normativního vstupu: ten
     * inicializuje best-signature-time, kdežto tady se posouvají i hodiny všeho ostatního. Nález
     * uživatele 22. 9. 2026 ukázal, co to umí provést. Pečeť bez
     * vlastního razítka kryje pozdější pečeť s razítkem z 29. 4. 2025; přepočet k tomu okamžiku
     * obě původní překážky odstranil ({@code ICTIVRSC}, {@code TSV_IBSTBCEC}), jenže přinesl
     * novou: {@code BBB_XCV_ICTIVRCIRI_ANS} — „the current time is not in the validity range of
     * the certificate of the issuer of the revocation information". Jediná dostupná odpověď OCSP
     * je z 22. 9. 2026 a certifikát respondéra platí od 23. 3. 2026, takže v dubnu 2025 nebyl na
     * světě. Ústupek k post-expiračním revokačním údajům tedy pomohl pečeti č. 2 (ta se posuzuje
     * k dnešku), a pečeti č. 1 ho vzal právě ten posun, který jí měl pomoci.
     *
     * <p><b>Proč zrovna konec platnosti certifikátu.</b> Existence prvku k okamžiku důkazu
     * znamená existenci i kdykoli později, takže kontrolní čas smí být pozdější — a nejzazší
     * použitelný je ten, kdy certifikát ještě platil; za ním se vrací původní překážka. U toho
     * dokumentu se obě okna překrývají dvanáct dní (23. 3. – 4. 4. 2026).
     *
     * <p><b>Je to volba přísnější, ne shovívavější.</b> Čím pozdější okamžik, tím víc se do
     * závěru promítne: odvolání, k němuž by mezitím došlo, pozdější kontrolní čas zachytí, kdežto
     * dřívější ne. Proto se zkouší <b>až jako druhý</b> — dřív má vždy přednost okamžik, který
     * norma popisuje, a pozdější se uplatní jedině tehdy, když k němu vyjde kladný závěr.
     *
     * <p>Podmínky: certifikát už musí být expirovaný (jinak první průchod počítal k dnešku a
     * pozdější okamžik neexistuje), expirace musí být <b>po</b> doloženém okamžiku existence
     * (jinak by se posuzovalo k době, o které důkaz nic neříká) a musí být pozdější než kontrolní
     * čas prvního pokusu (jinak není co zkoušet).
     */
    static java.time.Instant lateControlTime(SignatureWrapper sw, java.time.Instant decisive,
                                             java.time.Instant primary) {
        if (LATE_CONTROL_OFF || sw == null) return null;
        var sc = sw.getSigningCertificate();
        return lateControlTimeFor(
                sc == null || sc.getNotAfter() == null ? null : sc.getNotAfter().toInstant(),
                decisive, primary, java.time.Instant.now());
    }

    /**
     * Samo rozhodnutí, oddělené od vytahování údajů z {@code SignatureWrapper} — aby se dalo
     * vyzkoušet na okamžicích, a ne až na dokumentu, který má tu správnou souhru dat. Týž šev
     * jako u {@link #controlTimeFor}.
     */
    static java.time.Instant lateControlTimeFor(java.time.Instant certNotAfter,
                                                java.time.Instant decisive,
                                                java.time.Instant primary,
                                                java.time.Instant now) {
        if (certNotAfter == null || decisive == null || now == null) return null;
        // Vteřina před koncem platnosti: v okamžiku notAfter už certifikát podle DSS neplatí.
        java.time.Instant lastValid = certNotAfter.minusSeconds(1);
        if (!lastValid.isBefore(now)) return null;        // certifikát ještě platí
        if (lastValid.isBefore(decisive)) return null;    // důkaz je až po expiraci
        if (primary != null && !lastValid.isAfter(primary)) return null;   // není co zkoušet
        return lastValid;
    }

    /**
     * Okamžik, ke kterému se přepočet doopravdy pustí.
     *
     * <p>Rozhodný okamžik se snižuje na vlastní platné razítko prvku, je-li starší než razítko
     * krycího prvku. Jenže <b>oprávnění posunout hodiny dává až to krycí razítko</b> — teprve ono
     * dokládá, že celá ta revize dokumentu, včetně vložených revokačních údajů, v ten okamžik
     * existovala. Je-li vlastní razítko starší než revokační údaj, o který se závěr opře, ověřuje
     * se k době, kdy ten údaj ještě nebyl na světě. Na takový okamžik se závěr stavět nemá.
     *
     * <p>Že to není jen estetická výhrada, ukazuje sama knihovna: ověřování podle minulosti má
     * kontrolu {@code PSV_HRDBIBCT} — <i>„The revocation data has been issued at or after the
     * control time!"</i> — takže na zbytečně dřívějším okamžiku se dá i prohrát.
     *
     * <p>Prahem je <b>nejstarší</b> {@code thisUpdate} mezi revokačními údaji k podpisovému
     * certifikátu: je-li rozhodný okamžik před ním, neexistoval v tu chvíli žádný z nich a žádný
     * závěr o odvolání se o něj opřít nemohl. Přísnější varianta (žádat, aby okamžik nepředcházel
     * ani tomu nejmladšímu) by padala i tam, kde se závěr opřel o údaj z doby podpisu a ten
     * pozdější jen leží vedle.
     *
     * <p>Změřeno 14. 9. 2026 na 1 389 dokumentech se dvěma a více podpisy: 211 prvků s krycím
     * důkazem, z toho <b>65 s rozhodným okamžikem před svým revokačním údajem</b> — a u všech 65
     * vycházel závěr k oběma okamžikům stejně (38× TOTAL_PASSED, 27× INDETERMINATE). Tahle změna
     * tedy nepřeklápí žádný verdikt ve sbírce; odstraňuje nepodloženost, ne chybu ve výsledku.
     */
    private static java.time.Instant chooseControlTime(SignatureWrapper sw,
                                                       java.time.Instant decisive,
                                                       java.time.Instant proof) {
        return controlTimeFor(decisive, proof,
                sw == null ? null : earliestRevocationThisUpdate(sw));
    }

    /**
     * Samo rozhodnutí, oddělené od vytahování údajů z {@code SignatureWrapper} — aby se dalo
     * vyzkoušet na okamžicích, a ne až na dokumentu, který má tu správnou souhru dat.
     */
    static java.time.Instant controlTimeFor(java.time.Instant decisive, java.time.Instant proof,
                                            java.time.Instant earliestRevocation) {
        if (decisive == null || proof == null || !decisive.isBefore(proof)) return decisive;
        return earliestRevocation != null && decisive.isBefore(earliestRevocation)
                ? proof : decisive;
    }

    /** Nejstarší {@code thisUpdate} mezi revokačními údaji k podpisovému certifikátu prvku. */
    private static java.time.Instant earliestRevocationThisUpdate(SignatureWrapper sw) {
        var sc = sw.getSigningCertificate();
        if (sc == null) return null;
        java.time.Instant best = null;
        for (var r : sc.getCertificateRevocationData()) {
            if (r.getThisUpdate() == null) continue;
            java.time.Instant t = r.getThisUpdate().toInstant();
            if (best == null || t.isBefore(best)) best = t;
        }
        return best;
    }

    /** Vypínač pro měření: {@code -Danep.nounborn} vrátí chování před 29. 9. 2026. */
    private static final boolean UNBORN_OFF = Boolean.getBoolean("anep.nounborn");

    /**
     * Revokační údaje k podpisovému certifikátu prvku, které <b>ke kontrolnímu času ještě
     * neexistovaly</b> a zároveň se k němu použít nedají, protože certifikát jejich vydavatele
     * tehdy neplatil; prázdný seznam, když takové nejsou.
     *
     * <p><b>Proč.</b> DSS vybírá mezi přijatelnými revokačními údaji <b>nejmladší</b> a teprve
     * potom zkoumá, zda jeho vydavatel platil ke kontrolnímu času. Posun hodin v přepočtu tak
     * prohrává na údaji, který k tomu okamžiku z povahy věci existovat nemohl. Nález uživatele
     * 29. 9. 2026 („PAdES B-B +B-T kvalifikovany ICA.pdf"): podpis v úrovni B krytý pozdějším
     * podpisem s razítkem z 24. 6. 2026; certifikát revokován 27. 8., expiroval 28. 8. Dokument
     * nese odpověď OCSP „neodvolán" z 24. 6. (respondér platný od 5. 6.), ale DSS sáhlo po
     * odpovědi stažené 29. 9., jejíž respondér platí až od 18. 9. 2026 →
     * {@code BBB_XCV_ICTIVRCIRI_ANS} k 24. 6. i ke konci platnosti certifikátu. Ještě 13. 9. se
     * týž dokument překlápěl na platný: I.CA mezitím vyměnila certifikát respondéra, takže
     * výsledek závisel na tom, <i>kdy</i> se ověřovalo. Pozdější podpis téhož dokumentu, který se
     * posuzuje k dnešku, stejnou odpověď OCSP použít smí — odtud rozdíl mezi oběma prvky.
     *
     * <p><b>Proč je to bezpečné.</b> Odloží se jen údaj, který o certifikátu netvrdí odvolání
     * k rozhodnému okamžiku nebo dřív: buď „neodvolán" (a to po expiraci stejně nic nedokládá),
     * nebo odvolání <b>pozdější</b> než rozhodný okamžik — tedy přesně to, co by z něj
     * ověřování podle minulosti vyvodilo samo. Údaj dokládající odvolání před rozhodným
     * okamžikem se ponechá vždy, takže odložení nemůže zakrýt skutečnou překážku.
     */
    static List<eu.europa.esig.dss.diagnostic.jaxb.XmlCertificateRevocation> unbornRevocations(
            eu.europa.esig.dss.diagnostic.jaxb.XmlCertificate cert, Date controlTime,
            Date decisive) {
        List<eu.europa.esig.dss.diagnostic.jaxb.XmlCertificateRevocation> out = new ArrayList<>();
        if (cert == null || controlTime == null || decisive == null) return out;
        for (var cr : cert.getRevocations()) {
            var r = cr.getRevocation();
            if (r == null) continue;
            Date produced = r.getProductionDate() != null ? r.getProductionDate() : r.getThisUpdate();
            if (produced == null || !produced.after(controlTime)) continue;
            var issuer = r.getSigningCertificate() != null
                    ? r.getSigningCertificate().getCertificate() : null;
            if (issuer == null || issuer.getNotBefore() == null || issuer.getNotAfter() == null)
                continue;
            boolean issuerValid = !controlTime.before(issuer.getNotBefore())
                    && !controlTime.after(issuer.getNotAfter());
            if (issuerValid) continue;
            boolean revokedByThen = cr.getStatus() != null && cr.getStatus().isRevoked()
                    && (cr.getRevocationDate() == null || !cr.getRevocationDate().after(decisive));
            if (revokedByThen) continue;
            out.add(cr);
        }
        return out;
    }

    /**
     * Přepočet k {@code when} bez revokačních údajů z {@link #unbornRevocations}; {@code null},
     * když žádné nejsou nebo se to nepovede. Zbyde-li k certifikátu jen údaj, který ke
     * kontrolnímu času neexistoval, neodkládá se nic — závěr by pak stál na prázdnu.
     *
     * <p>Diagnostická data jsou sdílená s prvním průchodem, proto se seznam u certifikátu jen
     * dočasně zúží a hned vrátí; vrácené {@code Reports} pak nesou úplná data (výčet
     * revokačních údajů na kartě o odložený údaj nepřijde), kdežto podrobný protokol ukazuje,
     * o který údaj se závěr opřel.
     */
    private static Reports recomputeWithoutUnborn(Reports reports, ValidationPolicy policy,
                                                  Date when, SignatureWrapper sw, java.time.Instant decisive) {
        if (UNBORN_OFF || sw == null || decisive == null) return null;
        var sc = sw.getSigningCertificate();
        if (sc == null) return null;
        eu.europa.esig.dss.diagnostic.jaxb.XmlCertificate cert = null;
        for (var c : reports.getDiagnosticDataJaxb().getUsedCertificates()) {
            if (sc.getId().equals(c.getId())) { cert = c; break; }
        }
        if (cert == null) return null;
        var unborn = unbornRevocations(cert, when, Date.from(decisive));
        if (unborn.isEmpty() || unborn.size() == cert.getRevocations().size()) return null;
        List<eu.europa.esig.dss.diagnostic.jaxb.XmlCertificateRevocation> original =
                new ArrayList<>(cert.getRevocations());
        try {
            cert.getRevocations().removeAll(unborn);
            return recompute(reports, policy, when);
        } finally {
            cert.getRevocations().clear();
            cert.getRevocations().addAll(original);
        }
    }

    /** Vypínač pro měření: {@code -Danep.nosuppliedpoe} vrátí chování před 2. 10. 2026. */
    private static final boolean SUPPLIED_POE_OFF = Boolean.getBoolean("anep.nosuppliedpoe");

    /**
     * Přepočet k <b>témuž</b> okamžiku jako první průchod, v němž se razítko krycího prvku
     * přiřadí i prvku krytému; {@code null}, když to nejde nebo se to nepovede.
     *
     * <p><b>Proč.</b> Všechny tři dřívější pokusy posouvají hodiny, a posun je širší, než co norma
     * popisuje (viz {@link #derivedTimeResults}): kromě best-signature-time posune i posuzování
     * vydavatele revokačního údaje. Nález uživatele 2. 10. 2026 nad „ruzne vizualizace.pdf":
     * dva podpisy v úrovni B kryté třetím podpisem s razítkem z 11. 10. 2024, certifikát týž
     * (expirace 14. 6. 2025). S ústupkem k post-expiračním údajům vyjde třetí podpis platný —
     * posuzuje se k dnešku a rozhodným okamžikem je jeho razítko. Kryté podpisy ale ne: jediný
     * revokační údaj je odpověď OCSP stažená dnes a certifikát respondéra platí od 23. 3. 2026,
     * takže k 11. 10. 2024 i ke konci platnosti certifikátu padá přepočet na
     * {@code BBB_XCV_ICTIVRCIRI_ANS}; odložit ten údaj (třetí pokus) nejde, je jediný. Dva
     * podpisy v naprosto stejném postavení tak dostaly opačný verdikt.
     *
     * <p><b>Jak.</b> Tohle je to, co norma doopravdy popisuje: hodiny zůstanou, a best-signature-time
     * se sníží důkazem existence (EN 319 102-1 krok 5.5.4 1) a 2)). DSS vstup „time indication for
     * signature existence" nevystavuje, ale best-signature-time snižuje každým platným razítkem
     * prvku — a razítko krycího prvku <b>je</b> razítkem nad bajty, mezi nimiž leží podpisová
     * hodnota prvku krytého. Proto se mu dočasně připíše do diagnostických dat (mezi nalezená
     * razítka prvku i mezi objekty, které razítko kryje), přepočítá se a vrátí se zpět. Prvek pak
     * DSS posoudí přesně jako ten, který ho kryje.
     *
     * <p>Připisuje se jen to razítko krycího prvku, z něhož je odvozený důkaz
     * ({@link ReportMapper.CoveringProof#when()}), a jen platné. Vrácené {@code Reports} nesou
     * obnovená data, takže na kartě se razítko mezi razítky prvku neobjeví — kryje ho, nepatří mu.
     */
    private static Reports recomputeWithCoveringTimestamp(Reports reports, ValidationPolicy policy,
                                                          String signatureId,
                                                          ReportMapper.CoveringProof proof) {
        if (SUPPLIED_POE_OFF || proof == null || proof.signatureId() == null) return null;
        var dd = reports.getDiagnosticDataJaxb();
        eu.europa.esig.dss.diagnostic.jaxb.XmlSignature covered = null;
        eu.europa.esig.dss.diagnostic.jaxb.XmlSignature covering = null;
        for (var s : dd.getSignatures()) {
            if (signatureId.equals(s.getId())) covered = s;
            if (proof.signatureId().equals(s.getId())) covering = s;
        }
        if (covered == null || covering == null) return null;
        SimpleReport now = reports.getSimpleReport();
        eu.europa.esig.dss.diagnostic.jaxb.XmlTimestamp ts = null;
        for (var ft : covering.getFoundTimestamps()) {
            var t = ft.getTimestamp();
            if (t != null && t.getProductionTime() != null
                    && t.getProductionTime().toInstant().equals(proof.when())
                    && now.getIndication(t.getId()) == Indication.PASSED) {
                ts = t;
                break;
            }
        }
        if (ts == null) return null;
        var found = new eu.europa.esig.dss.diagnostic.jaxb.XmlFoundTimestamp();
        found.setTimestamp(ts);
        var object = new eu.europa.esig.dss.diagnostic.jaxb.XmlTimestampedObject();
        object.setCategory(eu.europa.esig.dss.enumerations.TimestampedObjectType.SIGNATURE);
        object.setToken(covered);
        try {
            covered.getFoundTimestamps().add(found);
            ts.getTimestampedObjects().add(object);
            return recompute(reports, policy, dd.getValidationDate());
        } finally {
            covered.getFoundTimestamps().remove(found);
            ts.getTimestampedObjects().remove(object);
        }
    }

    /** Vypínač pro měření: {@code -Danep.noenvelopepoe} vrátí chování před 1. 11. 2026. */
    private static final boolean ENVELOPE_POE_OFF = Boolean.getBoolean("anep.noenvelopepoe");

    /**
     * Doplní k výsledku závěr s <b>důkazem existence z obalu</b>, z něhož byl prvek předán
     * k ověření ({@link EnvelopeProof}).
     *
     * <p>Obal se ověří týmiž zdroji a politikou jako prvek, najde se jeho nejstarší platné razítko
     * nad bajty prvku, a to se připíše do diagnostických dat prvku stejně jako krycí razítko
     * v {@link #recomputeWithCoveringTimestamp}: hodiny se neposouvají, best-signature-time sníží
     * DSS samo. Spolu s ním se připíšou i ostatní razítka obalu (archivní drží v platnosti to
     * krycí) a certifikáty a revokační údaje, které jejich platnost nesou. Změřeno sondou
     * {@code EnvelopePoeScan} 1. 11. 2026: připsání dalo tentýž závěr jako posun hodin k času
     * razítka obalu, bez jeho vedlejších účinků.
     *
     * <p>Jen u podpisů, které vyšly INDETERMINATE a nepomohl jim ani odvozený čas z krycího
     * prvku: důkaz existence neplatný podpis nespraví a platnému nemá co přidat. Výsledek se
     * předává jako {@link DerivedTimeResult}, takže {@link ReportMapper} posoudí rozhodný okamžik
     * i situaci 20 vůči okamžiku, který důkaz doložil — sonda {@code RevokedBeforeProofScan}
     * ukázala, že jen tak se nezamlčí odvolání před tím okamžikem, které dokládá údaj, jejž
     * přísná politika DSS nepřijme.
     *
     * <p>Obaly: kontejner ASiC, obalující podpis (CMS, XAdES, JAdES), datová zpráva a PDF
     * s přílohami — jak se v nich krytí pozná, popisuje {@link EnvelopeProof#find}. U datové
     * zprávy se bere i razítko podání ({@code dmQTimestamp}), které je starší než razítko
     * pečeti — viz {@link EnvelopeProof#submission}.
     */
    public ValidationOutcome withEnvelopeProof(ValidationOutcome outcome, EnvelopeProof.Source source,
                                               CertificateVerifier certificateVerifier) {
        if (ENVELOPE_POE_OFF || outcome == null || source == null || outcome.reports() == null) {
            return outcome;
        }
        Reports reports = outcome.reports();
        SimpleReport now = reports.getSimpleReport();
        List<String> wanted = new ArrayList<>();
        for (String id : now.getSignatureIdList()) {
            if (now.getIndication(id) != Indication.INDETERMINATE) continue;
            DerivedTimeResult derived = outcome.derivedTimeResults().get(id);
            if (derived != null && Indication.TOTAL_PASSED.name().equals(derived.indication())) continue;
            wanted.add(id);
        }
        if (wanted.isEmpty()) return outcome;
        ValidationPolicy built = ValidationPolicyFactory.build(settings);
        ValidationPolicy effective = built != null ? built : defaultPolicy();
        if (effective == null) return outcome;

        // Obal a všechny obaly nad ním — viz proofsFrom().
        List<EnvelopeProof> candidates = new ArrayList<>(
                proofsFrom(source, certificateVerifier, effective));
        if (candidates.isEmpty()) return outcome;
        // Od nejstaršího. Starší důkaz je lepší, ale ne vždy použitelný: v jeho okamžiku třeba
        // ještě nebyl na světě revokační údaj, o který se závěr opírá (týž jev jako u krycího
        // prvku, viz chooseControlTime). Kde nevyjde, zkusí se pozdější.
        candidates.sort(java.util.Comparator.comparing(EnvelopeProof::when));

        Map<String, DerivedTimeResult> out = new java.util.HashMap<>(outcome.derivedTimeResults());
        List<String> open = new ArrayList<>(wanted);
        for (EnvelopeProof proof : candidates) {
            if (open.isEmpty()) break;
            Reports supplied = recomputeWithEnvelopeTimestamp(reports, effective, proof);
            SimpleReport then = supplied != null ? supplied.getSimpleReport() : null;
            if (then == null) continue;
            for (java.util.Iterator<String> it = open.iterator(); it.hasNext(); ) {
                String id = it.next();
                if (then.getIndication(id) != Indication.TOTAL_PASSED) continue;
                // Rozhodný okamžik je nejstarší platný důkaz. Často to není razítko obalu, nýbrž
                // vlastní razítko prvku, které teprve díky němu obstálo (měřený kontejner: razítko
                // obalu z 2019 drželo podpisové razítko z 2014) — a to DSS ve svém
                // best-signature-time má.
                java.util.Date bst = then.getBestSignatureTime(id);
                java.util.Date when = bst != null && bst.toInstant().isBefore(proof.when())
                        ? bst : java.util.Date.from(proof.when());
                out.put(id, new DerivedTimeResult(when, reports.getDiagnosticData().getValidationDate(),
                        Indication.TOTAL_PASSED.name(), null, supplied, true, proof));
                it.remove();
            }
        }
        if (out.size() == outcome.derivedTimeResults().size()) return outcome;
        return new ValidationOutcome(reports, outcome.skippedRevocations(), Map.copyOf(out),
                outcome.unreadableSigningTimes());
    }

    /**
     * Důkazy pro prvek z obalu {@code source} a ze všech obalů nad ním.
     *
     * <ul>
     *   <li>razítka obalu nad prvkem, u datové zprávy i razítko podání;</li>
     *   <li>razítka obalů nad ním — razítko vnějšího obalu kryje i vše, co leží ve vnitřním
     *       (ZFO → ASiC → PDF). Na každé úrovni je „prvkem" to, co v daném obalu leží: dokument,
     *       nebo vnitřní obal, který ho nese;</li>
     *   <li>razítko obalu, které by samo už neobstálo, ale podrží ho důkaz z obalu nad ním
     *       (1. 11. 2026, na pokyn uživatele): obal se přepočte s tím důkazem stejně, jako se
     *       přepočítává dokument, a jeho razítko, které tím obstojí, se nabídne jako důkaz
     *       s oporou ({@link EnvelopeProof#supportedBy}). Pomůže tam, kde razítko vnějšího obalu
     *       přišlo na dokument pozdě, ale na razítko vnitřního obalu ještě včas.</li>
     * </ul>
     * Prázdný seznam, když žádný obal důkaz nedá.
     */
    private List<EnvelopeProof> proofsFrom(EnvelopeProof.Source source,
                                           CertificateVerifier certificateVerifier,
                                           ValidationPolicy policy) {
        List<EnvelopeProof> out = new ArrayList<>();
        if (source.envelopeBytes() == null || source.elementBytes() == null) return out;
        // Důkazy z obalů nad tímhle: jejich „prvkem" je tento obal.
        List<EnvelopeProof> outer = source.parent() == null ? List.of()
                : proofsFrom(source.parent(), certificateVerifier, policy);
        out.addAll(ownProofs(source, certificateVerifier, policy, outer));
        for (EnvelopeProof p : outer) out.add(p.through(source.envelopeName()));
        return out;
    }

    /** Důkazy, které nese obal sám, případně s oporou v důkazech {@code outer} nad ním. */
    private List<EnvelopeProof> ownProofs(EnvelopeProof.Source source,
                                          CertificateVerifier certificateVerifier,
                                          ValidationPolicy policy, List<EnvelopeProof> outer) {
        List<EnvelopeProof> out = new ArrayList<>();
        switch (ContainerInspector.detectKind(source.envelopeBytes())) {
            case ZIP, CMS, ZFO, XML, JSON, PDF -> { }
            default -> { return out; }
        }
        Reports envelope;
        try {
            // Externí podpis se ověřuje spolu s dokumentem, jinak by DSS jeho otisky nespárovalo
            // a podpis by vyšel jako porušený.
            envelope = validate(new eu.europa.esig.dss.model.InMemoryDocument(
                            source.envelopeBytes(), source.envelopeName()),
                    source.detached() ? new eu.europa.esig.dss.model.InMemoryDocument(
                            source.elementBytes(), source.elementPath()) : null,
                    certificateVerifier, null).reports();
        } catch (Exception e) {
            return out;
        }
        EnvelopeProof sealed = EnvelopeProof.find(source, envelope);
        if (sealed != null) out.add(sealed);
        // Razítko obalu s oporou v obalu nad ním: jen když vyjde dřív než to, které obstálo
        // samo — jinak nic nepřidá. Od nejstarší opory; první, která pomůže, stačí.
        List<EnvelopeProof> supports = new ArrayList<>(outer);
        supports.sort(java.util.Comparator.comparing(EnvelopeProof::when));
        for (EnvelopeProof support : supports) {
            Reports rescued = recomputeWithEnvelopeTimestamp(envelope, policy, support);
            EnvelopeProof held = rescued != null ? EnvelopeProof.find(source, rescued) : null;
            if (held != null && (sealed == null || held.when().isBefore(sealed.when()))) {
                out.add(held.supportedBy(support));
                break;
            }
        }
        // Datová zpráva: razítko podání je starší než razítko pečeti (to přidává ISDS až při
        // stažení), a kryje-li otisk zprávy i tuto písemnost, je to lepší důkaz. Ověří se samo
        // jako odloučené razítko nad otiskem, který kryje — že otisk na obálku zprávy sedí,
        // zjistil už IsdsMessageInspector.
        EnvelopeProof.Submission submission = EnvelopeProof.submission(source);
        if (submission != null) {
            try {
                Reports stamp = validate(new eu.europa.esig.dss.model.InMemoryDocument(
                                submission.token(), "dmQTimestamp.tst"),
                        new eu.europa.esig.dss.model.DigestDocument(submission.algorithm(),
                                java.util.Base64.getEncoder().encodeToString(submission.imprint())),
                        certificateVerifier, null).reports();
                EnvelopeProof submitted = EnvelopeProof.fromSubmission(source, stamp);
                if (submitted != null) out.add(submitted);
            } catch (Exception e) {
                // Razítko podání, které DSS neověří, důkaz nedá; zůstává razítko pečeti.
            }
        }
        return out;
    }

    /**
     * Přepočet s krycím razítkem obalu připsaným do diagnostických dat prvku — viz
     * {@link #withEnvelopeProof}. Připsané objekty se po přepočtu odeberou, stejně jako
     * v {@link #recomputeWithCoveringTimestamp}: razítko obalu prvku nepatří, jen ho kryje.
     */
    private static Reports recomputeWithEnvelopeTimestamp(Reports reports, ValidationPolicy policy,
                                                          EnvelopeProof proof) {
        var dd = reports.getDiagnosticDataJaxb();
        var cover = proof.token();
        // Krycí razítko a řetěz opor nad ním (EnvelopeProof#support): každé další drží to
        // předchozí, takže se připisují všechna a každé se naváže na to, které drží.
        List<EnvelopeProof> layers = new ArrayList<>();
        for (EnvelopeProof p = proof; p != null; p = p.support()) layers.add(p);
        java.util.Set<String> tsIds = new java.util.HashSet<>();
        dd.getUsedTimestamps().forEach(t -> tsIds.add(t.getId()));
        for (EnvelopeProof layer : layers) {
            if (tsIds.contains(layer.token().getId())) return null;
        }
        java.util.Set<String> certIds = new java.util.HashSet<>();
        dd.getUsedCertificates().forEach(c -> certIds.add(c.getId()));
        java.util.Set<String> revIds = new java.util.HashSet<>();
        dd.getUsedRevocations().forEach(r -> revIds.add(r.getId()));

        List<eu.europa.esig.dss.diagnostic.jaxb.XmlCertificate> addedCerts = new ArrayList<>();
        List<eu.europa.esig.dss.diagnostic.jaxb.XmlRevocation> addedRevs = new ArrayList<>();
        List<eu.europa.esig.dss.diagnostic.jaxb.XmlTimestamp> addedTs = new ArrayList<>();
        List<eu.europa.esig.dss.diagnostic.jaxb.XmlTimestampedObject> addedObjects = new ArrayList<>();
        Map<eu.europa.esig.dss.diagnostic.jaxb.XmlSignature,
                eu.europa.esig.dss.diagnostic.jaxb.XmlFoundTimestamp> addedFound =
                new java.util.LinkedHashMap<>();
        List<eu.europa.esig.dss.diagnostic.jaxb.XmlTimestamp> ownTimestamps =
                new ArrayList<>(dd.getUsedTimestamps());
        // Druh razítka se po dobu přepočtu vede jako podpisové. Razítko podání datové zprávy
        // ověřené samostatně DSS označí jako CONTENT_TIMESTAMP — a obsahové razítko dokládá
        // jen to, že podpis vznikl až po něm, takže best-signature-time nesnižuje. Změřeno
        // 1. 11. 2026 na příloze datové zprávy: razítko podání obstálo, a přepočet přesto
        // skončil k dnešku. Vůči prvku je to přitom razítko nad bajty, mezi nimiž leží jeho
        // podpisová hodnota, tedy přesně to, co podpisové razítko dokládá.
        Map<eu.europa.esig.dss.diagnostic.jaxb.XmlTimestamp,
                eu.europa.esig.dss.enumerations.TimestampType> originalTypes =
                new java.util.LinkedHashMap<>();
        for (EnvelopeProof layer : layers) originalTypes.put(layer.token(), layer.token().getType());
        Map<eu.europa.esig.dss.diagnostic.jaxb.XmlTimestamp,
                List<eu.europa.esig.dss.diagnostic.jaxb.XmlTimestampedObject>> chainLinks =
                new java.util.LinkedHashMap<>();
        try {
            originalTypes.keySet().forEach(t ->
                    t.setType(eu.europa.esig.dss.enumerations.TimestampType.SIGNATURE_TIMESTAMP));
            for (EnvelopeProof layer : layers) {
                var edd = layer.envelope().getDiagnosticDataJaxb();
                for (var c : edd.getUsedCertificates()) if (certIds.add(c.getId())) addedCerts.add(c);
                for (var r : edd.getUsedRevocations()) if (revIds.add(r.getId())) addedRevs.add(r);
                for (var t : edd.getUsedTimestamps()) if (tsIds.add(t.getId())) addedTs.add(t);
            }
            // Opora drží razítko pod sebou: razítko datové zprávy kryje kontejner i s jeho
            // razítkem, a to je důkaz existence toho razítka.
            for (int i = 1; i < layers.size(); i++) {
                var link = new eu.europa.esig.dss.diagnostic.jaxb.XmlTimestampedObject();
                link.setCategory(eu.europa.esig.dss.enumerations.TimestampedObjectType.TIMESTAMP);
                link.setToken(layers.get(i - 1).token());
                layers.get(i).token().getTimestampedObjects().add(link);
                chainLinks.put(layers.get(i).token(), List.of(link));
            }
            dd.getUsedCertificates().addAll(addedCerts);
            dd.getUsedRevocations().addAll(addedRevs);
            dd.getUsedTimestamps().addAll(addedTs);
            for (var s : dd.getSignatures()) {
                var found = new eu.europa.esig.dss.diagnostic.jaxb.XmlFoundTimestamp();
                found.setTimestamp(cover);
                s.getFoundTimestamps().add(found);
                addedFound.put(s, found);
                var object = new eu.europa.esig.dss.diagnostic.jaxb.XmlTimestampedObject();
                object.setCategory(eu.europa.esig.dss.enumerations.TimestampedObjectType.SIGNATURE);
                object.setToken(s);
                addedObjects.add(object);
            }
            // Bajty prvku kryjí i jeho vlastní razítka: razítko obalu je důkazem existence
            // i pro ně, a to je cesta, kterou se na měřeném kontejneru dostal podpis z 2014
            // k platnosti — razítko obalu z 2019 dosvědčilo vnitřní razítko v době, kdy
            // certifikát jeho TSA ještě platil.
            for (var t : ownTimestamps) {
                var object = new eu.europa.esig.dss.diagnostic.jaxb.XmlTimestampedObject();
                object.setCategory(eu.europa.esig.dss.enumerations.TimestampedObjectType.TIMESTAMP);
                object.setToken(t);
                addedObjects.add(object);
            }
            cover.getTimestampedObjects().addAll(addedObjects);
            return recompute(reports, policy, dd.getValidationDate());
        } finally {
            originalTypes.forEach((t, type) -> t.setType(type));
            chainLinks.forEach((t, links) -> t.getTimestampedObjects().removeAll(links));
            cover.getTimestampedObjects().removeAll(addedObjects);
            addedFound.forEach((s, found) -> s.getFoundTimestamps().remove(found));
            dd.getUsedTimestamps().removeAll(addedTs);
            dd.getUsedRevocations().removeAll(addedRevs);
            dd.getUsedCertificates().removeAll(addedCerts);
        }
    }

    /** Týž proces nad týmiž daty, jen k jinému okamžiku; {@code null}, když se to nepovede. */
    private static Reports recompute(Reports reports, ValidationPolicy policy,
                                     java.util.Date when) {
        try {
            DefaultSignatureProcessExecutor executor = new DefaultSignatureProcessExecutor();
            executor.setDiagnosticData(reports.getDiagnosticDataJaxb());
            executor.setValidationPolicy(policy);
            executor.setCurrentTime(when);
            return executor.execute();
        } catch (Exception e) {
            // Přepočet je nadstavba: co se nepovede, to se prostě neukáže. Ověření samo stojí
            // na prvním průchodu a ten už proběhl.
            return null;
        }
    }

    /**
     * Výchozí politika DSS. {@link ValidationPolicyFactory#build} vrací {@code null}, když žádný
     * ústupek potřeba není — validator si pak politiku načte sám, samostatný executor ne.
     */
    private static ValidationPolicy defaultPolicy() {
        try (java.io.InputStream is = ValidationPolicyFacade.class
                .getResourceAsStream("/policy/constraint.xml")) {
            return is == null ? null : ValidationPolicyFacade.newFacade().getValidationPolicy(is);
        } catch (Exception e) {
            return null;
        }
    }

    /** Convenience overload — no detached content. */
    public ValidationOutcome validate(DSSDocument signatureDoc, CertificateVerifier certificateVerifier) {
        return validate(signatureDoc, null, certificateVerifier, null);
    }

    // -------------------------------------------------------------------------

    /** Obalí revokační zdroj filtrem {@link PostExpiryRevokedOnlySource}, nebo ho vrátí beze změny. */
    private static <R extends eu.europa.esig.dss.model.x509.revocation.Revocation>
            RevocationSource<R> narrowPostExpiry(RevocationSource<R> source, boolean narrow) {
        return narrow ? new PostExpiryRevokedOnlySource<>(source) : source;
    }

    /**
     * The moment the custom CRL store is being asked about — an approximation of the
     * best-signature-time, taken as the earliest time-stamp in the document.
     *
     * <p>It has to be known before validation starts, because that is when the CRL source is
     * built, so it cannot be the best-signature-time DSS will later compute. The earliest
     * time-stamp is a safe stand-in: DSS's best-signature-time is the earliest <em>valid</em> one,
     * which can only be the same or later, so this never rejects a list DSS would have wanted.
     * The cost is one structural parse of the document — no revocation lookups, no policy — and it
     * is paid only when the store actually holds something.
     *
     * <p>Returns null when the document carries no time-stamp at all, which leaves the store
     * unconstrained rather than unusable. For such a signature DSS anchors validation to the
     * present, so nothing in an archive could ever satisfy the test, and applying it would quietly
     * retire the store for exactly the B-level documents users keep old lists for. A chosen
     * validation time stands in where there is one, since that is the moment being asked about.
     */
    private Date revocationReferenceTime(DSSDocument signatureDoc,
                                         List<DSSDocument> detachedContents, char[] pdfPassword) {
        Date earliest = earliestTimestampTime(signatureDoc, detachedContents, pdfPassword);
        if (earliest != null) return earliest;
        if (settings.isUseCustomValidationTime()) {
            Date given = settings.getValidationDate();
            if (given != null) return given;
        }
        // Bez jakéhokoli důkazu o existenci je tím okamžikem <b>přítomnost</b> — EN 319 102-1
        // v kroku 5.5.4 1) inicializuje best-signature-time „…or the current time". Dokud se sem
        // vracelo {@code null}, filtr stáří ve {@link BestMatchCustomCrlSource} (Search 0) se
        // u každého prvku bez razítka mlčky vypnul a uložená CRL směla tvrdit „neodvolán" o době,
        // o které nic neví. Nález uživatele 13. 9. 2026 na podpisu úrovně B.
        //
        // Prvku to nic nebere: bez důkazu o existenci se posuzuje k dnešku, a historický seznam
        // o dnešku nevypovídá. Kde se rozhodný okamžik stanovit dá (razítko, zadané datum), platí
        // dál on.
        return new Date();
    }

    /**
     * The earliest time-stamp anywhere in the document, or null when it carries none.
     *
     * <p>Reads the structure only — no revocation lookups, no policy — so it is cheap enough to
     * run before validation proper. Shared with the preservation services, which choose from the
     * same CRL store and must apply the same test to it.
     *
     * <p>An empty {@link CommonCertificateVerifier} is handed to the validator even though nothing
     * here needs one. It is not optional: an ASiC container builds one nested analyzer per
     * signature file and passes the verifier down to each, where {@code Objects.requireNonNull}
     * rejects a missing one — so {@code getSignatures()} threw an NPE for every container, the
     * catch below turned it into null, and the custom CRL store was left unconstrained for exactly
     * the documents Search 0 exists to protect. A bare verifier has no sources of its own and
     * cannot fetch anything; this stays a structural read.
     */
    public static Date earliestTimestampTime(DSSDocument signatureDoc,
                                             List<DSSDocument> detachedContents,
                                             char[] pdfPassword) {
        try {
            SignedDocumentValidator validator = SignedDocumentValidator.fromDocument(signatureDoc);
            validator.setCertificateVerifier(new CommonCertificateVerifier());
            if (pdfPassword != null && validator instanceof PDFDocumentValidator pdfValidator) {
                pdfValidator.setPasswordProtection(pdfPassword);
            }
            if (detachedContents != null && !detachedContents.isEmpty()) {
                validator.setDetachedContents(detachedContents);
            }
            Date earliest = null;
            for (AdvancedSignature signature : validator.getSignatures()) {
                for (TimestampToken ts : signature.getAllTimestamps()) {
                    Date generated = ts.getGenerationTime();
                    if (generated != null && (earliest == null || generated.before(earliest))) {
                        earliest = generated;
                    }
                }
            }
            return earliest;
        } catch (Exception e) {
            // Unreadable structure is validation's problem to report, not this helper's.
            return null;
        }
    }

    private Reports runValidation(DSSDocument signatureDoc, List<DSSDocument> detachedContents,
                                  CertificateVerifier certificateVerifier,
                                  ValidationPolicy policy, char[] pdfPassword,
                                  List<DSSDocument> evidenceRecords,
                                  java.util.Set<String> unreadableSigningTimes) {
        SignedDocumentValidator validator = SignedDocumentValidator.fromDocument(signatureDoc);
        StartupTrace.mark("  validator resolved (SPI lookup)");
        if (pdfPassword != null && validator instanceof PDFDocumentValidator pdfValidator) {
            pdfValidator.setPasswordProtection(pdfPassword);
        }
        validator.setCertificateVerifier(certificateVerifier);
        if (detachedContents != null && !detachedContents.isEmpty()) {
            validator.setDetachedContents(detachedContents);
        }
        // Evidence records are processed as part of ETSI EN 319 102-1 clause 5.6, which the
        // validator only reaches at ARCHIVAL_DATA level. That is DSS's default and AnEP never
        // lowers it, so no explicit setValidationLevel is needed here.
        if (evidenceRecords != null && !evidenceRecords.isEmpty()) {
            validator.setDetachedEvidenceRecordDocuments(evidenceRecords);
        }
        validator.setTokenExtractionStrategy(TokenExtractionStrategy.EXTRACT_ALL);
        java.util.Date customTime = settings.getValidationDate();
        if (customTime != null) {
            validator.setValidationTime(customTime);
        }
        Reports reports = policy != null
                ? validator.validateDocument(policy)
                : validator.validateDocument();
        StartupTrace.mark("  validateDocument() returned");
        unreadableSigningTimes.addAll(unreadableSigningTimes(validator));
        return reports;
    }

    /**
     * Adds {@code newCerts} to the verifier's existing trusted certificate sources
     * without removing the LOTL source.
     */
    private static void addTrustedCerts(CommonCertificateVerifier ccv,
                                        List<CertificateToken> newCerts) {
        CommonTrustedCertificateSource extra = new CommonTrustedCertificateSource();
        newCerts.forEach(extra::addCertificate);

        List<CertificateSource> combined = new ArrayList<>(
                ccv.getTrustedCertSources().getSources());
        combined.add(extra);
        ccv.setTrustedCertSources(combined.toArray(new CertificateSource[0]));
    }

    /**
     * Adds {@code newCerts} as adjunct (non-trusted) certificate material — usable by DSS
     * for chain-building, but granting no trust by themselves.
     */
    private static void addAdjunctCerts(CommonCertificateVerifier ccv,
                                        List<CertificateToken> newCerts) {
        if (newCerts.isEmpty()) return;
        CommonCertificateSource extra = new CommonCertificateSource();
        newCerts.forEach(extra::addCertificate);

        List<CertificateSource> combined = new ArrayList<>(
                ccv.getAdjunctCertSources().getSources());
        combined.add(extra);
        ccv.setAdjunctCertSources(combined.toArray(new CertificateSource[0]));
    }
}
