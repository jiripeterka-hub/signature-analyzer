package cz.digitalnikontinuita.anep.model;

import java.time.Instant;
import java.util.List;

/**
 * Flattened view of a single signature finding within a document, shaped for
 * direct display in the UI. One input file can produce several of these
 * (e.g. multiple independent signatures inside one ASiC-E container).
 */
public class SignatureSummary {

    private final String signatureId;
    private final String format;          // e.g. PAdES-BASELINE-LT, XAdES-BASELINE-B
    private final String indication;      // TOTAL_PASSED, TOTAL_FAILED, INDETERMINATE...
    private final String subIndication;   // populated when indication is FAILED/INDETERMINATE
    private final String signerDisplayName;
    private final Instant signingTime;
    private final boolean qualified;
    /**
     * Human-readable Czech legal type, used as the text after "Jde o ".
     * Null when the signature does not fall into any of the three recognised
     * categories under Czech Act 297/2016.
     */
    private final String legalType;
    private final Instant bestSignatureTime;
    private final Instant verifiableUntil;
    private final boolean hasValidTimestamp;
    /**
     * Names of settings that were decisive in producing a TOTAL_PASSED result.
     * Empty when the result stands without any relaxed settings — but only where
     * {@link #decisiveSettingsMeasured} says so; otherwise empty means nothing was established.
     */
    private final List<String> decisiveSettings;
    /** Zastupují-li se ústupky ve {@link #decisiveSettings} navzájem — stačí kterýkoli z nich. */
    private final boolean decisiveSettingsInterchangeable;
    /**
     * Zda {@link #decisiveSettings} vzešly z měření, nebo jen z odvození.
     *
     * <p>Prázdný výčet znamená dvojí a text ty dvě věci nesmí ztotožnit: <b>změřeno</b> a nic
     * potřeba nebylo (pak smí zaznít „bez nich by ověření dopadlo stejně“), nebo <b>neměřeno</b>
     * a odvození nic nenašlo — a to o výsledku neříká nic. Odvození ({@code
     * ReportMapper.computeDecisiveSettings}) hledá jen čtyři předem vyjmenované tvary vady
     * a úplné být nemůže: 19. 9. 2026 se na PAdES-BES, který bez ústupku neprojde (CRL vydaná
     * pět let před vydáním certifikátu → {@code BBB_XCV_IARDPFC_ANS}), nechytil ani jeden
     * z nich, a karta o ústupcích napsala, že se neprojevily.
     */
    private final boolean decisiveSettingsMeasured;

    // Extended-view fields
    private final String encryptionAlgorithm;        // e.g. "RSA", "ECDSA"
    private final String digestAlgorithm;            // e.g. "SHA256", "SHA-384"
    private final String signerCertDN;               // full Subject DN of signing certificate
    /**
     * All timestamps associated with this signature, sorted ascending by production time.
     * Each entry carries a {@code passed} flag indicating whether DSS validated it as PASSED.
     */
    private final List<TimestampEntry> allTimestamps;
    /**
     * True when the certificate chain's trust anchor is a certificate from the app's own
     * {@link cz.digitalnikontinuita.anep.core.CustomTrustStore}, rather than the EU Trusted Lists.
     */
    private final boolean trustedViaCustomStore;
    /** Name of the pinned certificate that anchored a chain, or null. */
    private final String customTrustAnchorName;

    /**
     * tsKind values: "sig" = embedded in signature (SIGNATURE_TIMESTAMP),
     *                "content" = signed content-time-stamp attribute, produced BEFORE signing
     *                            (CONTENT_TIMESTAMP / ALL_ or INDIVIDUAL_DATA_OBJECTS_TIMESTAMP);
     *                            does not establish best-signature-time,
     *                "doc" = standalone document/archive timestamp (ETSI-level, DOCUMENT_TIMESTAMP),
     *                "vri" = timestamp stored in PDF DSS/VRI dictionary (VRI_TIMESTAMP, Adobe-style).
     */
    /**
     * Jedno časové razítko prvku.
     *
     * <p>{@code qualified} je kvalifikovanost dle důvěryhodných seznamů, tedy
     * {@code TimestampQualification.QTSA}. Všechno ostatní — včetně {@code NA}, kde autoritu
     * seznam vůbec nezná — kvalifikované není. Nese ho záznam, a ne až pohled: pohled dostane
     * seznam razítek, ne jejich id, a dohledat kvalifikovanost zpětně podle času vzniku by
     * u dvou razítek téže vteřiny přiřadilo cokoli.
     *
     * <p>{@code statusWithdrawn} říká, že razítkovací služba měla kladný status v době, kdy
     * razítko vzniklo, a teď ho nemá. Je to <b>otázka o tomhle razítku</b>, ne o podpisu:
     * situace 43 se ptá za celý prvek a až po několika podmínkách o tom, jestli to verdikt něco
     * stálo. Rozdíl je vidět na {@code priklad6_2_Razitko_do_03_08_2034.pdf}: razítko z roku 2012
     * má {@code true}, razítko z 2. 8. 2016 {@code false} — to už vzniklo po sunsetu, takže
     * kladný status nemělo ani tehdy a odebrat mu ho neměl kdo.
     *
     * <p>{@code weakDigest} je jméno zastaralého otisku, na kterém razítko stojí („SHA1“,
     * „MD5“, „RIPEMD160“), nebo {@code null}. Není to táž otázka jako {@code failureCause}:
     * o verdiktu může rozhodovat něco jiného a zastaralý otisk přesto stát za zmínku — viz
     * {@code ExtendedView.timestampFailureLine}.
     *
     * <p>{@code intact} je neporušenost samotného tokenu — podpis razítka sedí a otisk, který
     * kryje, souhlasí — <b>bez ohledu na důvěru</b>. Od {@code passed} se liší přesně tam, kde
     * o razítko jde: token od služby s odebraným kvalifikovaným statusem nebo od autority mimo
     * důvěryhodné seznamy je neporušený, a přesto neobstojí. Kdo odhaduje, jak s dokumentem
     * naloží cizí nástroj, musí se ptát na tohle, ne na verdikt zdejšího ověření.
     */
    /**
     * @param qualifiedFrom odkdy má ta razítkovací služba v důvěryhodném seznamu kvalifikovaný
     *        status, je-li to <b>až po</b> vzniku tohohle razítka; jinak {@code null}. Právě to
     *        je důvod, proč razítko může být platné a přitom nekvalifikované, a sám o sobě se
     *        z karty vyčíst nedá.
     * @param anchorCert certifikát razítkovací autority je sám kotvou důvěry — seznam ho uvádí
     *        přímo jako identitu služby. U kotvy se řetěz končí a její vlastní platnost se
     *        neposuzuje, takže razítko obstojí i po expiraci toho certifikátu.
     */
    public record TimestampEntry(Instant time, boolean passed, String issuer, Instant notAfter,
                                  String tsKind, boolean qualified, boolean intact,
                                  TimestampFailure failureCause, String weakDigest,
                                  boolean statusWithdrawn, Instant qualifiedFrom,
                                  boolean anchorCert) {

        /** Bez údajů z důvěryhodného seznamu — pro místa, která se na ně neptají. */
        public TimestampEntry(Instant time, boolean passed, String issuer, Instant notAfter,
                              String tsKind, boolean qualified, boolean intact,
                              TimestampFailure failureCause, String weakDigest,
                              boolean statusWithdrawn) {
            this(time, passed, issuer, notAfter, tsKind, qualified, intact, failureCause,
                    weakDigest, statusWithdrawn, null, false);
        }

        /**
         * Bez názvu zastaralého otisku a bez stavu služby — pro místa, která se na ně neptají.
         */
        public TimestampEntry(Instant time, boolean passed, String issuer, Instant notAfter,
                              String tsKind, boolean qualified, boolean intact,
                              TimestampFailure failureCause) {
            this(time, passed, issuer, notAfter, tsKind, qualified, intact, failureCause,
                    null, false);
        }

        /**
         * Bez pojmenované příčiny — pro místa, která razítko jen popisují a neptají se, proč
         * neobstálo.
         */
        public TimestampEntry(Instant time, boolean passed, String issuer, Instant notAfter,
                              String tsKind, boolean qualified, boolean intact) {
            this(time, passed, issuer, notAfter, tsKind, qualified, intact, null, null,
                    false);
        }

        /**
         * Bez údaje o neporušenosti — pro místa, kde se razítko neposuzuje samostatně a platnost
         * je jediné, co se o něm ví. Tam se bere, že platné razítko je i neporušené; obráceně to
         * neplatí a právě proto {@code intact} existuje.
         */
        public TimestampEntry(Instant time, boolean passed, String issuer, Instant notAfter,
                              String tsKind, boolean qualified) {
            this(time, passed, issuer, notAfter, tsKind, qualified, passed, null, null,
                    false);
        }
    }

    /**
     * Proč se razítko nepodařilo ověřit jako platné.
     *
     * <p>Dělení je převzaté z dílčích indikací ETSI EN 319 102-1: každá z nich pojmenovává jinou
     * překážku a — což je tu to podstatné — každá se odstraňuje něčím jiným, nebo neodstraňuje
     * vůbec. Bez toho rozlišení uměl důvod 21 jen odkázat čtenáře do podrobného protokolu DSS,
     * ať si příčinu najde sám.
     *
     * <p>Rozlišuje se podle dílčí indikace razítka, ne podle výčtu chyb v protokolu. Chyb bývá
     * u jednoho razítka několik naráz a ta nejnápadnější nemusí být ta rozhodující: u razítek
     * z roku 2012 stojí v protokolu SHA-1 nad vším ostatním, jenže verdikt drží
     * {@link #EXPIRED_NO_POE}. Ověřeno měřením — po zapnutí ústupku k SHA-1 chyby
     * {@code ASCCM_*} zmizí a indikace se nezmění.
     */
    public enum TimestampFailure {
        /**
         * Certifikátu razítkovací autority skončila platnost a nic nedokládá, že razítko vzniklo
         * ještě za ní ({@code OUT_OF_BOUNDS_NO_POE}). Ověřování pak nemá důkaz existence dřívější
         * než čas ověření, a ten do platnosti certifikátu nespadá.
         */
        EXPIRED_NO_POE,
        /** Otisk nebo podpisový algoritmus razítka už neplatí za spolehlivý (SHA-1 a spol.). */
        CRYPTO,
        /** Certifikát razítkovací autority byl odvolán. */
        REVOKED,
        /** Řetěz certifikátů razítka nevede k žádné kotvě důvěry. */
        NO_CHAIN,
        /** O certifikátu razítkovací autority se nepodařilo zjistit, zda nebyl odvolán. */
        NO_REVOCATION,
        /** Sám token je porušený — otisk nebo podpis razítka nesedí. */
        BROKEN,
        /**
         * Data, která razítko kryje, se nenašla — typicky samostatné razítko (.tst) předložené
         * bez souboru, ke kterému patří. Otisk pak není s čím porovnat, a to je jiný nález než
         * {@link #BROKEN}: nic nesvědčí o tom, že by se data změnila. Do 25. 9. 2026 spadal pod
         * BROKEN a důvod 31 o takovém razítku tvrdil, že otisk nesouhlasí (nález uživatele).
         */
        NO_DATA,
        /** Dílčí indikace, kterou zdejší výklad nerozlišuje. */
        OTHER;

        /**
         * Zařazení podle <b>klíčů hlášení</b>, která DSS k tokenu vydalo; dílčí indikace je až
         * záloha.
         *
         * <p>Dílčí indikace sama nestačí a je to změřené. Sken 416 dokumentů (14. 9. 2026) našel
         * 209 neúspěšných samostatných razítek a <b>58 z nich</b> nese
         * {@code NO_CERTIFICATE_CHAIN_FOUND_NO_POE}, ačkoli jejich autorita v důvěryhodných
         * seznamech je — chybí jen revokační údaj ({@code BBB_XCV_IRDPFC_ANS}). Zařazení podle
         * indikace by jim tvrdilo, že autorita mezi důvěryhodnými není.
         *
         * <p>Pořadí otázek není libovolné:
         * <ol>
         *   <li>odvolání — nejsilnější tvrzení, přebíjí vše ostatní;</li>
         *   <li>chybějící kotva důvěry — bez ní nemá smysl mluvit o expiraci ani o revokaci;</li>
         *   <li>expirace certifikátu;</li>
         *   <li>chybějící revokační údaj;</li>
         *   <li>zastaralý algoritmus.</li>
         * </ol>
         *
         * <p><b>Expirace před algoritmem</b> je záměr podepřený měřením: u
         * „certifikacni_a_uznavany_s_razitky_s_revinfo.pdf" stojí SHA-1 v protokolu nad vším
         * ostatním, ale verdikt drží expirace — po zapnutí ústupku k SHA-1 se indikace nezměnila.
         */
        public static TimestampFailure of(java.util.Collection<String> messageKeys, String sub) {
            if (messageKeys != null) {
                if (hasKey(messageKeys, "BBB_XCV_ISCR_ANS", "BBB_XCV_ISCOH_ANS")) return REVOKED;
                if (hasKey(messageKeys, "BBB_XCV_CCCBB")) return NO_CHAIN;
                if (hasKey(messageKeys, "BBB_XCV_ICTIVRSC_ANS", "BBB_XCV_HPCCVVT_ANS")) {
                    return EXPIRED_NO_POE;
                }
                if (hasKey(messageKeys, "BBB_XCV_IRDPFC_ANS", "BBB_XCV_IARDPFC_ANS",
                        "BBB_XCV_REVOC_HAS_CERT_INFO_ANS", "BBB_XCV_ISCUKN_ANS")) {
                    return NO_REVOCATION;
                }
                if (hasKey(messageKeys, "ASCCM_")) return CRYPTO;
            }
            return ofSubIndication(sub);
        }

        /** Nese některý z klíčů kterýkoli z uvedených předpon? */
        private static boolean hasKey(java.util.Collection<String> keys, String... prefixes) {
            for (String k : keys) {
                if (k == null) continue;
                for (String prefix : prefixes) {
                    if (k.startsWith(prefix)) return true;
                }
            }
            return false;
        }

        /**
         * Zařazení podle dílčí indikace, jak ji pojmenovává DSS.
         *
         * <p>Stojí tu, a ne u volajícího, protože se na tutéž otázku ptají <b>dvě</b> místa:
         * razítko uvnitř podpisu (důvod 21) a samostatné časové razítko (důvod 31). Kdyby si
         * každé vedlo vlastní tabulku, rozejdou se — a čtenář by u téže dílčí indikace dostal
         * jednou jedno vysvětlení a jindy druhé.
         *
         * <p>Bere řetězec, ne {@code SubIndication}: samostatné razítko si nese svou indikaci
         * v souhrnu už jako text a převádět ji zpátky na výčtový typ jen kvůli tomuhle dotazu
         * by znamenalo ošetřovat hodnoty, které DSS umí a zdejší výčet ne.
         */
        public static TimestampFailure ofSubIndication(String sub) {
            if (sub == null) return OTHER;
            return switch (sub) {
                case "OUT_OF_BOUNDS_NO_POE", "OUT_OF_BOUNDS_NOT_REVOKED", "EXPIRED" ->
                        EXPIRED_NO_POE;
                case "CRYPTO_CONSTRAINTS_FAILURE", "CRYPTO_CONSTRAINTS_FAILURE_NO_POE" -> CRYPTO;
                case "REVOKED", "REVOKED_NO_POE", "REVOKED_CA_NO_POE" -> REVOKED;
                case "NO_CERTIFICATE_CHAIN_FOUND", "NO_CERTIFICATE_CHAIN_FOUND_NO_POE",
                     "CERTIFICATE_CHAIN_GENERAL_FAILURE" -> NO_CHAIN;
                case "TRY_LATER", "REVOCATION_OUT_OF_BOUNDS_NO_POE" -> NO_REVOCATION;
                case "SIG_CRYPTO_FAILURE", "HASH_FAILURE" -> BROKEN;
                // Nenalezená data nejsou porušený otisk: razítko jen není s čím porovnat.
                case "SIGNED_DATA_NOT_FOUND" -> NO_DATA;
                default -> OTHER;
            };
        }
    }

    /**
     * When preservation has to happen by, and which element sets that date.
     *
     * <p>{@code when} is the earliest notAfter of any certificate that requires a revocation check
     * (signing cert chain + TSA cert chains, stopping before the trust anchor) — the latest point
     * at which OCSP/CRL for all non-anchor certificates is still reliably obtainable.
     *
     * <p>{@code reason} names the certificate the date came from, in a sentence fit to show the
     * reader. A bare date says when to act but not what is running out, and "ihned" without a
     * reason is the least useful thing the field could say — it reads as a fault in the document
     * rather than as a named certificate that expires on a named day.
     */
    public record AugmentationDeadline(Instant when, String reason) {}

    /**
     * Deadline for preserving this signature, or null when the signing certificate itself is the
     * trust anchor (no separate revocation needed) or the entry is a standalone timestamp token.
     */
    private final AugmentationDeadline augmentationDeadline;

    /**
     * Lhůta, kterou ukládá revokační údaj vložený v dokumentu, ale bez vlastního důkazu
     * existence: konec platnosti certifikátu, kterým je ten údaj podepsán. {@code null}, když
     * takový údaj není — buď v dokumentu žádný vložený není, nebo ho kryje archivní či
     * dokumentové časové razítko, a pak jeho uznání na platnosti vydavatele nezávisí.
     *
     * <p>Stojí vedle {@link #augmentationDeadline}, ne jen v něm, přestože do jeho minima
     * vstupuje také: horizont ověřitelnosti se na obě lhůty ptá jinak. Lhůtu z dostupnosti
     * stažených údajů zahazuje tam, kde rozhodující údaj přišel z dokumentu nebo z vlastního
     * úložiště; tahle platí i tam.
     */
    private final AugmentationDeadline unprovenRevocationDeadline;

    /**
     * Numbered failure situations (1–6) detected for TOTAL_FAILED / INDETERMINATE signatures.
     * Empty for TOTAL_PASSED signatures and for standalone timestamp tokens.
     */
    private final List<Integer> detectedSituations;

    /**
     * Numbered reasons derived from detected situations. Each reason groups one or more
     * situations under a user-facing explanation with remediation guidance.
     * Empty for TOTAL_PASSED signatures and for standalone timestamp tokens.
     */
    private final List<Integer> detectedReasons;

    /**
     * PNG image bytes of the signature's visual appearance extracted from the PDF page region
     * occupied by the signature annotation widget. {@code null} when not a PDF signature, when
     * the signature field has no visual appearance, or when the appearance is blank/empty.
     */
    private final byte[] signatureAppearancePng;

    /**
     * Zda tu vizualizaci čtenář vůbec uvidí — na obrazovce, na papíře, nebo nikde. Podpis může
     * nést bezvadný obrázek, který žádný prohlížeč nenakreslí, protože ho anotace má označený
     * jako skrytý; bez tohohle údaje by aplikace obrázek ukázala a mlčky tvrdila, že tak
     * dokument vypadá. {@code null} u prvků, které nejsou podpisem v PDF.
     */
    private final cz.digitalnikontinuita.anep.core.PdfFieldVisibility appearanceVisibility;

    /**
     * Human-readable Czech description of WHERE exactly SHA1 was found (signature digest,
     * certificate chain, timestamp message imprint, TSA cert chain). Non-null only when
     * reason 15 (SHA1) is among {@link #detectedReasons}; used to populate the "Podrobněji"
     * button on that reason card in the UI.
     */
    private final String sha1Detail;

    /**
     * Human-readable Czech description of WHICH certificate(s) lacked revocation information.
     * Non-null only when reason 12 is among {@link #detectedReasons}; used to populate the
     * "Podrobněji" button on that reason card in the UI.
     */
    private final String noRevocationDetail;

    /**
     * Human-readable Czech detail about which certificate(s) had revocation data (OCSP/CRL)
     * issued after their notAfter and what type it was.
     * Non-null only when reason 2 is among {@link #detectedReasons}.
     */
    private final String newerRevocationDetail;

    /**
     * Podklad pro okno „kde vzít CRL“ u rady pro zkušenější uživatele: dotčené certifikáty, do
     * kdy musí být seznam vydán, a odkazy na stránky autorit s historickými seznamy. Týž tvar
     * jako {@link #noRevocationDetail} u důvodu 12 — obě okna vykresluje tentýž kód.
     *
     * <p>Skládá se z certifikátů všech důvodů, které tu radu nesou (2, 6, 10, 11, 12 a 25),
     * protože ta rada je u všech táž věta, a stojí proto v Analýze jen jednou; non-null tehdy,
     * je-li mezi {@link #detectedReasons} aspoň jeden z nich.
     */
    private final String crlSourceHelp;

    /**
     * Human-readable Czech detail about which certificate(s) had embedded revocation data
     * from an expired respondér/CRL-issuer, and what type (OCSP/CRL).
     * Non-null only when reason 6 is among {@link #detectedReasons}.
     */
    private final String expiredResponderDetail;

    /**
     * Formatted date/time of the custom validation time that was active during this
     * verification. Non-null only for TOTAL_PASSED signatures verified with a custom
     * validation time; used to show a note analogous to the lenient-settings note in the UI.
     */
    private final String decisiveCustomTime;

    /**
     * Human-readable Czech message explaining that the signature was verified at a custom
     * time at which the cert was valid, but online revocation data show it was later revoked.
     * Non-null only when situation S24 is detected (TOTAL_PASSED + custom time + later revocation).
     */
    private final String laterRevokedDetail;

    /** IK (identifikátor klienta) MPSV extracted from SAN OtherName OID 1.3.6.1.4.1.11801.2.1. */
    private final String ikMpsv;

    /** PDF signature dictionary fields — null when absent or not a PDF signature. */
    private final String pdfReason;
    private final String pdfLocation;
    private final String pdfContactInfo;

    /** True when the user had a custom validation time set in settings during this verification. */
    private final boolean usedCustomValidationTime;

    /**
     * Human-readable Czech description of the signing certificate type, e.g.
     * "kvalifikovaný certifikát pro elektronický podpis". Null for timestamps and orphans.
     */
    private final String certKind;

    /**
     * True/false when the QSCD flag is known from the signing certificate.
     * Null for timestamps and orphans (concept does not apply).
     */
    private final Boolean certQscd;

    /**
     * Full RFC 2253 issuer DN of the signing certificate.
     * Null for timestamps and orphans.
     */
    private final String certIssuerDN;

    /** notBefore of the signing certificate. Null for timestamps and orphans. */
    private final Instant certNotBefore;

    /** notAfter of the signing certificate. Null for timestamps and orphans. */
    private final Instant certNotAfter;

    /** True when at least one revocation record for the signing certificate carries status REVOKED. */
    private final boolean certRevoked;

    /**
     * Revocation date of the signing certificate when {@link #certRevoked} is true and the date
     * is known; null otherwise (either not revoked, or revoked but date unavailable).
     */
    private final Instant certRevokedAt;

    /** Decimal serial number of the signing certificate. Null for timestamps and orphans. */
    private final String certSerialNumber;

    /** True when the signing certificate is self-signed. False for timestamps and orphans. */
    private final boolean certSelfSigned;

    /** True when the signing certificate carries the QcCompliance extension (isQcCompliance()). */
    private final boolean certQcCompliant;

    /** QC type descriptions from QcTypes extension, e.g. ["esign"], ["eseal"]. Empty for timestamps/orphans. */
    private final List<String> certQcTypeDescriptions;

    /**
     * Non-null when this is a PDF certification signature (DocMDP entry present).
     * Holds the {@code CertificationPermission} enum name:
     * {@code NO_CHANGE_PERMITTED}, {@code MINIMAL_CHANGES_PERMITTED}, or {@code CHANGES_PERMITTED}.
     * Null for ordinary (approval) signatures and all non-PDF signatures.
     */
    private final String certificationPermission;

    /**
     * Zámek, který podpis položil na podpisová pole, případně na celý dokument.
     *
     * <p>Něco jiného než {@link #certificationPermission}, ačkoli se to plete: certifikační
     * podpis (DocMDP) musí být první v dokumentu, je nejvýš jeden a mluví za celý dokument.
     * Zámek podle {@code /Lock} a {@code FieldMDP} může nést <b>kterýkoli</b> podpis, i druhý
     * v pořadí, a v základní podobě zamyká jen formulářová pole. Teprve entry {@code /P}
     * — přidané v PDF 2.0 a psané Acrobatem při „Zamknout dokument po podepsání“ — z něj dělá
     * omezení celého dokumentu, na téže stupnici jako DocMDP.
     *
     * <p>Změřeno na „PAdES B-T kvalifikovnay zamknuty.pdf“: {@code /Perms /DocMDP} tam není
     * vůbec, takže o uzamčení dokumentu se bez tohohle údaje nikde nedozvíme.
     *
     * @param scope       {@code PdfLockAction}: {@code ALL} (všechna pole), {@code INCLUDE}
     *                    nebo {@code EXCLUDE} (pak rozhoduje {@code fields}); null, když se
     *                    rozsah nepodařilo zjistit
     * @param fields      jména polí u {@code INCLUDE}/{@code EXCLUDE}; u {@code ALL} prázdné
     * @param permission  {@code CertificationPermission} z {@code /P}, nebo null, když zámek
     *                    žádné {@code /P} nenese a týká se tedy jen polí
     */
    public record PdfLock(String scope, List<String> fields, String permission) {

        public PdfLock {
            fields = fields != null ? List.copyOf(fields) : List.of();
        }

        /** Zámek sahá na celý dokument, ne jen na formulářová pole. */
        public boolean locksDocument() { return permission != null; }
    }

    /**
     * Zámek položený tímto podpisem, nebo null, když podpis žádný neklade.
     * Viz {@link PdfLock}.
     */
    private final PdfLock pdfLock;

    /**
     * Human-readable Czech list of revocation entries (OCSP/CRL) that are available online
     * but not yet embedded in the document — the data that would be added during LT augmentation.
     * Non-null only when situation S33 is detected.
     */
    private final String s33Detail;

    /**
     * Human-readable Czech list of certificates for which no standards-usable revocation
     * information is available online (neither embedded nor fetchable). Non-null only when
     * situation S33 is NOT detected AND at least one cert is missing usable revocation.
     */
    private final String s33MissingDetail;

    /**
     * Raw DER bytes of CRL files downloaded from CDP URLs during S33 analysis.
     * Non-empty only when S33 is detected (situation 33 present); used to inject
     * additional CRLs into the PDF DSS dictionary during augmentation, supplementing
     * what PAdESExtensionService embeds (which excludes trust-anchor CAs).
     */
    private final List<byte[]> s33CrlBytes;

    /**
     * Human-readable Czech list of the certificates whose revocation information exists only in a
     * form strict rules turn away — published after the certificate expired, or issued by an
     * authority whose own certificate has since expired. Non-null only when situation S45 is
     * detected, and then never together with {@link #s33Detail}: the two readings of coverage are
     * mutually exclusive.
     */
    private final String s45Detail;

    /**
     * Raw DER bytes of CRLs downloaded from CDP URLs during S45 analysis — the post-expiry lists
     * that {@link #s33CrlBytes} deliberately excludes because uchování would strip them out again.
     * Re-stamping embeds them verbatim; nothing else may.
     */
    private final List<byte[]> s45CrlBytes;

    /**
     * True when preservation would actually achieve something: revocation data is missing, all of
     * it can still be obtained, and every piece would be issued after the best-signature-time.
     *
     * <p>Distinct from situation 33, which describes the document. This says whether acting on it
     * is worth offering. At level B it is always false — the signature is anchored to the present,
     * so no revocation data can be issued after that anchor, and the only thing that helps is a
     * time-stamp to move the anchor into the past.
     */
    private final boolean preservationWorthwhile;

    /**
     * When situation S34 ("ověření bez ústupků") is NOT detected, contains a semicolon-separated
     * list of the sub-conditions that prevented it. Null when S34 is detected or not applicable.
     */
    private final String s34FailReason;

    /**
     * Věta o razítkovací službě, které důvěryhodný seznam odňal kvalifikovaný status: jmenuje
     * razítko, jeho TSA a data, kdy se status měnil. Null jinak — situace a její vysvětlení
     * vznikají spolu, takže jedno bez druhého nenastane.
     *
     * <p>Jedno pole pro dvě situace, které se vylučují: 43 vysvětluje, proč verdikt kladný není,
     * 49 doprovází verdikt, který kladný je. Obojí je totéž zjištění o službě, jen jednou stojí
     * mezi příčinami a podruhé mezi poznámkami k výsledku.
     */
    private final String withdrawnTsaDetail;
    /**
     * Situace 51 — o kolik dřív než podpis vznikl revokační údaj, o který se opřel
     * závěr o neodvolání, a odkud ten údaj je. Věta pro čtenáře, ne data k dalšímu
     * počítání: samotné číslo by mu nic neřeklo a složit ho jinde znovu by znamenalo
     * mít pravidlo na dvou místech.
     */
    private final String staleRevocationDetail;
    /**
     * Situace 54 — věta o důkazu existence odvozeném z pozdějšího podpisu: který prvek ho nese
     * a k jakému okamžiku. Null, když situace 54 nenastala.
     *
     * <p>Hotová věta, ne data k dalšímu počítání: číslo prvku a okamžik samy o sobě čtenáři nic
     * neřeknou a skládat tu větu podruhé jinde by znamenalo mít pravidlo na dvou místech — týž
     * důvod jako u {@link #staleRevocationDetail}.
     */
    private final String coveringProofDetail;
    /**
     * Situace 54 — časové razítko <b>krycího</b> prvku, o které se odvozený důkaz existence
     * opírá. Null, když situace 54 nenastala.
     *
     * <p>Nepočítá se mezi {@link #timestamps} a nesmí: fakta o prvku by pak tvrdila, že podpis
     * bez razítka razítko má. Slouží jedinému účelu — horizont ověřitelnosti se ptá, jak dlouho
     * bude uznatelný důkaz existence, a u tohohle prvku je to právě tohle razítko.
     */
    private final TimestampEntry coveringProofTimestamp;
    /**
     * Identifikátor prvku, nad kterým tenhle stojí jako <b>protipodpis</b> (kontrasignatura);
     * {@code null} u prvku, který protipodpisem není.
     *
     * <p><b>Identifikátor, ne pořadové číslo.</b> Čtenáři se ukazuje číslo, jenže to závisí na
     * pořadí, ve kterém prvky nakonec leží na kartě — a mezi podpisy se řadí i samostatná časová
     * razítka, takže „druhý podpis" a „druhý autentizační prvek" nemusí být totéž. Číslo se proto
     * dopočítává až při vykreslení, z hotového seznamu prvků
     * ({@code ExtendedView.counterSignaturePosition}).
     *
     * <p>Není to totéž co krytí spočítané z bajtů ({@link #coveringProofDetail}): protipodpis
     * deklaruje sám formát a podepisuje <b>podpisovou hodnotu</b> svého rodiče, kdežto pozdější
     * podpis v PDF kryje jen tím, že jeho rozsah podepsaných bajtů ten dřívější obsahuje.
     */
    private final String counterSignatureOfId;

    /**
     * Věta situace 50: důkaz o rozhodném razítku nese další razítko, které samo kvalifikované
     * nebylo. Vlastní pole, a ne sdílené s {@link #withdrawnTsaDetail}: se situací 49 se
     * nevylučuje, obě mohou nastat u téhož podpisu a každá mluví o jiném prvku.
     */
    private final String proofCarrierDetail;

    /**
     * Software that produced this signature, read from the PDF signature dictionary's
     * {@code /Prop_Build /App} entry (name plus version when declared). Null whenever the
     * signature does not declare it — most non-Adobe tools and all non-PDF formats — in
     * which case the UI omits the item rather than guessing.
     */
    private final String signingTool;

    /**
     * Proč neobstálo <b>samostatné</b> časové razítko — prvek, který je sám razítkem, ne
     * podpisem s razítky. {@code null} u podpisů a u razítek, která prošla.
     *
     * <p>Nese to souhrn, a ne až pohled: zařazení se opírá o klíče hlášení z protokolu DSS
     * (viz {@link TimestampFailure#of}), a ty jsou v té chvíli po ruce jen mapperu.
     */
    private final TimestampFailure standaloneTimestampFailure;

    /**
     * How the verification itself ran — anchoring times, revocation information, settings.
     * Null for standalone time-stamps and orphan signatures, which have no such course.
     */
    private final ValidationCourse validationCourse;

    /**
     * Evidence records covering this signature, whether embedded in it, present in the same
     * container, or supplied as separate files. Empty for the overwhelming majority of
     * documents. Kept apart from {@link #allTimestamps} on purpose: DSS does not put an
     * evidence record's time-stamps into the signature's timestamp list either, and folding
     * them together would make them count towards the augmentation deadline, which follows
     * the signature's own archive time-stamps and not the record's renewal cycle.
     */
    private final List<EvidenceRecordEntry> evidenceRecords;

    /**
     * Jedna připomínka, kterou knihovna DSS vydala k tomuto prvku — to, co je v protokolech
     * DSS žluté.
     *
     * <p>{@code key} je klíč hlášení DSS ({@code QUAL_QSCD_AT_ST_ANS} a podobně); pod ním se
     * hledá český výklad v {@code texty/dss-pripominky.txt}. {@code message} je věta samotné
     * knihovny, tak jak stojí v jejím protokolu — nese se s sebou proto, aby měl čtenář co
     * porovnat s protokolem DSS, a aby klíč bez výkladu neskončil na kartě jako holý kód.
     *
     * <p>{@code qualification} rozlišuje, odkud připomínka je: ověření podpisu jako takového
     * (AdES), nebo posouzení kvalifikovanosti podle důvěryhodných seznamů. DSS je vede zvlášť
     * a čtou se jinak: první mluví o tom, co je s podpisem, druhá o tom, za co se počítá.
     */
    public record DssRemark(String key, String message, boolean qualification) {}

    /**
     * Připomínky knihovny DSS k tomuto prvku, v pořadí, v jakém je vydala: nejprve ověření
     * podpisu, pak kvalifikovanost. Prázdné, když žádné nevydala.
     */
    private final List<DssRemark> dssRemarks;

    /**
     * Jak texty pojmenují certifikát, kterého se důvod týká — klíčem je číslo důvodu.
     *
     * <p>Jen pro důvody, jejichž věta by jinak řekla holé „o certifikátu": 24 a 25 vznikají ze
     * situací, které chodí po podpisovém řetězu i po řetězech razítek, takže dopsat do textu
     * natvrdo „podpisovém" by bylo nepravdivé. Prázdné, když se certifikát určit nepodařilo —
     * pak platí náhradní znění bez něj.
     */
    private final java.util.Map<Integer, String> reasonCertificates;

    /**
     * Údaje z důvěryhodného seznamu o razítkovací službě — jen u prvku, který je sám razítkem.
     *
     * <p>Totéž, co u položky výčtu nese {@link TimestampEntry}: odkdy má ta služba kvalifikovaný
     * status, začíná-li ten zápis až po vzniku razítka, a zda je certifikát autority sám kotvou
     * důvěry. Obojí vysvětluje, co na kartě vypadá jako rozpor — razítko platné, a přitom
     * nekvalifikované, a platné i po expiraci certifikátu, který je na kartě vypsaný.
     */
    private final Instant tsQualifiedFrom;
    /**
     * Certifikát prvku je sám kotvou důvěry — u razítka certifikát razítkovací autority, u podpisu
     * a pečeti podpisový certifikát.
     *
     * <p>U podpisu to nastává tam, kde důvěryhodný seznam vede podpisový certifikát jako identitu
     * některé služby (typicky kvalifikované služby ověřování nebo uchovávání, která tím certifikátem
     * pečetí své protokoly). Kotva se pak neposuzuje podle své doby platnosti, nýbrž podle statusu
     * služby v seznamu, a pečeť bez razítka tak obstojí i po expiraci certifikátu — potkáno na
     * protokolu SecuSign (Software602) z roku 2017, certifikát do 8. 3. 2018.
     */
    private final boolean anchorCert;

    /**
     * Konec platnosti certifikátu, o kterém mluví důvod 2 — tedy toho, k němuž se váže revokační
     * údaj vydaný až po jeho expiraci.
     *
     * <p>Není to nutně podpisový certifikát: situace 6 chodí i po řetězech razítek, takže sáhnout
     * po {@link #certNotAfter} by u nálezu na razítku dosadilo do věty cizí datum. Bere se proto
     * z téhož výčtu certifikátů, kterým se ten důvod našel.
     */
    private final Instant newerRevocationCertNotAfter;

    /**
     * Konec platnosti certifikátu <b>vydavatele</b> revokačního údaje — to, o čem mluví důvod 6.
     *
     * <p>Jiný certifikát než u {@link #newerRevocationCertNotAfter}: tam jde o ten, k němuž se
     * údaj vztahuje, tady o ten, kterým je podepsaný (respondér OCSP nebo vydavatel CRL). Bere se
     * z téhož nálezu, jakým se situace 5 našla, a rozhoduje nejbližší konec platnosti v jeho
     * řetězu — právě ten, na kterém ta situace stojí.
     */
    private final Instant expiredResponderNotAfter;

    /**
     * Podepsaný atribut signing-time v podpisu je, jen ho DSS nepřečetlo — situace 22 a důvod 18
     * tu proto nemluví o chybějícím atributu.
     *
     * <p>Případ, na kterém se to našlo (3. 10. 2026): CAdES z roku 2020 nese čas jako
     * GeneralizedTime, ačkoli RFC 5652 (§ 11.3) chce pro léta 1950–2049 UTCTime. BouncyCastle ho
     * přečte, {@code CAdESSignature.getSigningTime()} v DSS 6.5 vrátí {@code null} a DSS pak hlásí,
     * že atribut chybí. Služba SecuSign týž podpis ověřila jako platný s výchozím nastavením.
     */
    private final boolean signingTimeUnreadable;

    public SignatureSummary(String signatureId, String format, String indication,
                             String subIndication, String signerDisplayName,
                             Instant signingTime, boolean qualified, String legalType,
                             Instant bestSignatureTime, Instant verifiableUntil,
                             boolean hasValidTimestamp, List<String> decisiveSettings,
                             boolean decisiveSettingsInterchangeable,
                             boolean decisiveSettingsMeasured,
                             String encryptionAlgorithm, String digestAlgorithm,
                             String signerCertDN, List<TimestampEntry> allTimestamps,
                             boolean trustedViaCustomStore, String customTrustAnchorName,
                             AugmentationDeadline augmentationDeadline,
                             AugmentationDeadline unprovenRevocationDeadline,
                             List<Integer> detectedSituations, List<Integer> detectedReasons,
                             String sha1Detail, String noRevocationDetail,
                             String newerRevocationDetail, String crlSourceHelp,
                             String expiredResponderDetail,
                             String decisiveCustomTime,
                             String laterRevokedDetail,
                             byte[] signatureAppearancePng,
                             cz.digitalnikontinuita.anep.core.PdfFieldVisibility appearanceVisibility,
                             String ikMpsv,
                             String pdfReason, String pdfLocation, String pdfContactInfo,
                             boolean usedCustomValidationTime,
                             String certificationPermission,
                             String certKind, Boolean certQscd, String certIssuerDN,
                             Instant certNotBefore, Instant certNotAfter,
                             boolean certRevoked, Instant certRevokedAt,
                             String certSerialNumber, boolean certSelfSigned,
                             boolean certQcCompliant, List<String> certQcTypeDescriptions,
                             String s33Detail, String s33MissingDetail,
                             List<byte[]> s33CrlBytes, boolean preservationWorthwhile,
                             String s45Detail, List<byte[]> s45CrlBytes,
                             String s34FailReason,
                             String signingTool,
                             ValidationCourse validationCourse,
                             List<EvidenceRecordEntry> evidenceRecords,
                             String withdrawnTsaDetail,
                             String proofCarrierDetail,
                             String staleRevocationDetail,
                             PdfLock pdfLock,
                             String coveringProofDetail,
                             TimestampEntry coveringProofTimestamp,
                             String counterSignatureOfId,
                             TimestampFailure standaloneTimestampFailure,
                             List<DssRemark> dssRemarks,
                             java.util.Map<Integer, String> reasonCertificates,
                             Instant tsQualifiedFrom, boolean anchorCert,
                             Instant newerRevocationCertNotAfter,
                             Instant expiredResponderNotAfter,
                             boolean signingTimeUnreadable) {
        this.pdfLock = pdfLock;
        this.coveringProofDetail = coveringProofDetail;
        this.coveringProofTimestamp = coveringProofTimestamp;
        this.counterSignatureOfId = counterSignatureOfId;
        this.staleRevocationDetail = staleRevocationDetail;
        this.withdrawnTsaDetail = withdrawnTsaDetail;
        this.proofCarrierDetail = proofCarrierDetail;
        this.validationCourse = validationCourse;
        this.evidenceRecords = evidenceRecords != null ? List.copyOf(evidenceRecords) : List.of();
        this.signatureId = signatureId;
        this.format = format;
        this.indication = indication;
        this.subIndication = subIndication;
        this.signerDisplayName = signerDisplayName;
        this.signingTime = signingTime;
        this.qualified = qualified;
        this.legalType = legalType;
        this.bestSignatureTime = bestSignatureTime;
        this.verifiableUntil = verifiableUntil;
        this.hasValidTimestamp = hasValidTimestamp;
        this.decisiveSettings = decisiveSettings != null ? List.copyOf(decisiveSettings) : List.of();
        this.decisiveSettingsInterchangeable = decisiveSettingsInterchangeable;
        this.decisiveSettingsMeasured = decisiveSettingsMeasured;
        this.encryptionAlgorithm = encryptionAlgorithm;
        this.digestAlgorithm = digestAlgorithm;
        this.signerCertDN = signerCertDN;
        this.allTimestamps = allTimestamps != null ? List.copyOf(allTimestamps) : List.of();
        this.trustedViaCustomStore = trustedViaCustomStore;
        this.customTrustAnchorName = customTrustAnchorName;
        this.augmentationDeadline = augmentationDeadline;
        this.unprovenRevocationDeadline = unprovenRevocationDeadline;
        this.detectedSituations = detectedSituations != null ? List.copyOf(detectedSituations) : List.of();
        this.detectedReasons = detectedReasons != null ? List.copyOf(detectedReasons) : List.of();
        this.sha1Detail = sha1Detail;
        this.noRevocationDetail = noRevocationDetail;
        this.newerRevocationDetail = newerRevocationDetail;
        this.crlSourceHelp = crlSourceHelp;
        this.expiredResponderDetail = expiredResponderDetail;
        this.decisiveCustomTime = decisiveCustomTime;
        this.laterRevokedDetail = laterRevokedDetail;
        this.signatureAppearancePng = signatureAppearancePng;
        this.appearanceVisibility = appearanceVisibility;
        this.ikMpsv = ikMpsv;
        this.pdfReason = pdfReason;
        this.pdfLocation = pdfLocation;
        this.pdfContactInfo = pdfContactInfo;
        this.usedCustomValidationTime = usedCustomValidationTime;
        this.certificationPermission = certificationPermission;
        this.certKind = certKind;
        this.certQscd = certQscd;
        this.certIssuerDN = certIssuerDN;
        this.certNotBefore = certNotBefore;
        this.certNotAfter = certNotAfter;
        this.certRevoked = certRevoked;
        this.certRevokedAt = certRevokedAt;
        this.certSerialNumber = certSerialNumber;
        this.certSelfSigned = certSelfSigned;
        this.certQcCompliant = certQcCompliant;
        this.certQcTypeDescriptions = certQcTypeDescriptions != null ? List.copyOf(certQcTypeDescriptions) : List.of();
        this.s33Detail = s33Detail;
        this.s33MissingDetail = s33MissingDetail;
        this.s33CrlBytes = s33CrlBytes != null ? new java.util.ArrayList<>(s33CrlBytes) : List.of();
        this.preservationWorthwhile = preservationWorthwhile;
        this.s45Detail = s45Detail;
        this.s45CrlBytes = s45CrlBytes != null ? new java.util.ArrayList<>(s45CrlBytes) : List.of();
        this.s34FailReason = s34FailReason;
        this.signingTool = signingTool;
        this.standaloneTimestampFailure = standaloneTimestampFailure;
        this.dssRemarks = dssRemarks != null ? List.copyOf(dssRemarks) : List.of();
        this.reasonCertificates = reasonCertificates != null
                ? java.util.Map.copyOf(reasonCertificates) : java.util.Map.of();
        this.tsQualifiedFrom = tsQualifiedFrom;
        this.anchorCert = anchorCert;
        this.newerRevocationCertNotAfter = newerRevocationCertNotAfter;
        this.expiredResponderNotAfter = expiredResponderNotAfter;
        this.signingTimeUnreadable = signingTimeUnreadable;
    }

    public TimestampFailure getStandaloneTimestampFailure() {
        return standaloneTimestampFailure;
    }

    public List<DssRemark> getDssRemarks() {
        return dssRemarks;
    }

    public String getSignatureId()                    { return signatureId; }
    public String getFormat()                         { return format; }
    public String getIndication()                     { return indication; }
    public String getSubIndication()                  { return subIndication; }
    public String getSignerDisplayName()              { return signerDisplayName; }
    public Instant getSigningTime()                   { return signingTime; }
    public boolean isQualified()                      { return qualified; }
    public String getLegalType()                      { return legalType; }
    public Instant getBestSignatureTime()             { return bestSignatureTime; }
    public Instant getVerifiableUntil()               { return verifiableUntil; }
    public boolean hasValidTimestamp()                { return hasValidTimestamp; }
    public List<String> getDecisiveSettings()         { return decisiveSettings; }
    public boolean isDecisiveSettingsInterchangeable() { return decisiveSettingsInterchangeable; }
    public boolean isDecisiveSettingsMeasured()       { return decisiveSettingsMeasured; }
    public String getEncryptionAlgorithm()            { return encryptionAlgorithm; }
    public String getDigestAlgorithm()                { return digestAlgorithm; }
    public String getSignerCertDN()                   { return signerCertDN; }
    public List<TimestampEntry> getAllTimestamps()       { return allTimestamps; }
    public boolean isTrustedViaCustomStore()           { return trustedViaCustomStore; }
    public String  getCustomTrustAnchorName()          { return customTrustAnchorName; }
    public AugmentationDeadline getAugmentationDeadline()  { return augmentationDeadline; }
    public AugmentationDeadline getUnprovenRevocationDeadline() {
        return unprovenRevocationDeadline;
    }
    public List<Integer> getDetectedSituations()       { return detectedSituations; }
    public List<Integer> getDetectedReasons()          { return detectedReasons; }
    public java.util.Map<Integer, String> getReasonCertificates() { return reasonCertificates; }
    public Instant getTsQualifiedFrom()                 { return tsQualifiedFrom; }
    public boolean isAnchorCert()                     { return anchorCert; }
    public Instant getNewerRevocationCertNotAfter()     { return newerRevocationCertNotAfter; }
    public Instant getExpiredResponderNotAfter()        { return expiredResponderNotAfter; }
    public boolean isSigningTimeUnreadable()            { return signingTimeUnreadable; }
    public String getSha1Detail()                      { return sha1Detail; }
    public String getNoRevocationDetail()              { return noRevocationDetail; }
    public String getNewerRevocationDetail()           { return newerRevocationDetail; }
    public String getCrlSourceHelp()                   { return crlSourceHelp; }
    public String getExpiredResponderDetail()          { return expiredResponderDetail; }
    public String getDecisiveCustomTime()              { return decisiveCustomTime; }
    public String getLaterRevokedDetail()              { return laterRevokedDetail; }
    public byte[] getSignatureAppearancePng()           { return signatureAppearancePng; }
    public cz.digitalnikontinuita.anep.core.PdfFieldVisibility getAppearanceVisibility() {
        return appearanceVisibility;
    }
    public String getIkMpsv()                           { return ikMpsv; }
    public String getPdfReason()                        { return pdfReason; }
    public String getPdfLocation()                      { return pdfLocation; }
    public String getPdfContactInfo()                   { return pdfContactInfo; }
    public boolean isUsedCustomValidationTime()         { return usedCustomValidationTime; }
    public boolean isCertification()                    { return certificationPermission != null; }
    public String getCertificationPermission()          { return certificationPermission; }
    public PdfLock getPdfLock()                         { return pdfLock; }
    public String getCertKind()                         { return certKind; }
    public Boolean getCertQscd()                        { return certQscd; }
    public String getCertIssuerDN()                     { return certIssuerDN; }
    public Instant getCertNotBefore()                   { return certNotBefore; }
    public Instant getCertNotAfter()                    { return certNotAfter; }
    public boolean isCertRevoked()                      { return certRevoked; }
    public Instant getCertRevokedAt()                   { return certRevokedAt; }
    public String getCertSerialNumber()                 { return certSerialNumber; }
    public boolean isCertSelfSigned()                   { return certSelfSigned; }
    public boolean isCertQcCompliant()                  { return certQcCompliant; }

    /**
     * Systémový certifikát podle zákona č. 227/2000 Sb. — podklad elektronické značky. Pozná se
     * podle druhu, který mu přidělil ReportMapper (politika PostSignum 2.23.134.1.4.1.8.*,
     * případně serialNumber „S…“ v předmětu).
     */
    /**
     * True when the element is an electronic mark (elektronická značka) under Act No. 227/2000
     * Coll. — the legal type ReportMapper assigned from a system certificate. The person behind
     * it is the „označující osoba“, not a signatory: the Act names it so, and a mark is typically
     * made by an information system, not by a person signing.
     */
    public boolean isMark() {
        return legalType != null && legalType.toLowerCase().contains("značk");
    }

    /** Popisek osoby, která za prvkem stojí (bez dvojtečky) — podpis, pečeť, nebo značka. */
    public String personLabel() {
        return isMark() ? "Označující osoba" : isSeal() ? "Pečetící osoba" : "Podepsaná osoba";
    }

    public boolean isCertSystem() {
        return certKind != null && certKind.contains("systémový certifikát");
    }

    /** Den, od kterého se použije nařízení eIDAS (čl. 52 odst. 2) a skončila směrnice 1999/93/ES. */
    public static final Instant EIDAS_APPLICATION = Instant.parse("2016-07-01T00:00:00Z");

    /**
     * Kvalifikovaný certifikát vydaný ještě podle směrnice 1999/93/ES, tedy před 1. 7. 2016.
     *
     * <p>Takový certifikát nemá QcType — ten zavedla až EN 319 412-5 pro eIDAS — a nese jen
     * QcCompliance. Podle čl. 51 odst. 2 eIDAS se do konce své platnosti považuje za kvalifikovaný
     * certifikát pro elektronický podpis. DSS ho tak i kvalifikuje; Rozšířený přehled ho do
     * 27. 9. 2026 hlásil jako nekvalifikovaný, protože se ptal jen po QcType (nález uživatele).
     */
    public boolean isCertIssuedUnderDirective() {
        // Systémový certifikát byl kvalifikovaný podle českého zákona, ne podle směrnice —
        // i kdyby nesl QcCompliance (I.CA pro Pozemkový fond ČR, 2010).
        if (isCertSystem()) return false;
        return certQcCompliant && certNotBefore != null && certNotBefore.isBefore(EIDAS_APPLICATION);
    }
    public List<String> getCertQcTypeDescriptions()     { return certQcTypeDescriptions; }
    /**
     * True when the authentication element is a seal rather than a signature — the single
     * discriminator used by every tab for the "Pečetící osoba:" / "Podepsaná osoba:" label.
     *
     * <p>The certificate's QcType is authoritative and is checked first: it is the same source
     * the "Druh certifikátu:" row in Rozšířený přehled reports, so the two rows can never
     * disagree. QcType is only present on qualified certificates, so for the rest the legal-type
     * text is the fallback; it must match both "pečeť" and the stem "pečet" (declined forms).
     * Timestamps have neither and are handled by the callers, which use their own label.
     */
    public boolean isSeal() {
        if (certQcTypeDescriptions.contains("qc-type-eseal")) return true;
        if (certQcTypeDescriptions.contains("qc-type-esign")) return false;
        // Směrnice 1999/93/ES znala kvalifikované certifikáty jen pro podpis fyzických osob.
        if (isCertIssuedUnderDirective() && certQcTypeDescriptions.isEmpty()) return false;
        return legalType != null && legalType.toLowerCase().contains("pečet");
    }

    public String getS33Detail()                         { return s33Detail; }
    public String getS33MissingDetail()                  { return s33MissingDetail; }
    public List<byte[]> getS33CrlBytes()                 { return s33CrlBytes; }
    public String getS45Detail()                         { return s45Detail; }
    public List<byte[]> getS45CrlBytes()                 { return s45CrlBytes; }
    public boolean isPreservationWorthwhile()            { return preservationWorthwhile; }
    public String getS34FailReason()                     { return s34FailReason; }
    public String getWithdrawnTsaDetail()                { return withdrawnTsaDetail; }
    public String getStaleRevocationDetail()             { return staleRevocationDetail; }
    public String getCoveringProofDetail()               { return coveringProofDetail; }
    public TimestampEntry getCoveringProofTimestamp()    { return coveringProofTimestamp; }
    public String getCounterSignatureOfId()              { return counterSignatureOfId; }
    public boolean isCounterSignature()                  { return counterSignatureOfId != null; }
    public String getProofCarrierDetail()                { return proofCarrierDetail; }
    public String getSigningTool()                       { return signingTool; }
    public ValidationCourse getValidationCourse()        { return validationCourse; }
    public List<EvidenceRecordEntry> getEvidenceRecords() { return evidenceRecords; }

    /**
     * Algoritmus nejnovějšího časového razítka, které prvek kryje, a do kdy ho kryptografická
     * sada politiky ověřování považuje za spolehlivý. Doplněno 5. 10. 2026 na pokyn uživatele:
     * je-li to dřív než lhůta k úkonu — dnes typicky razítko podepsané klíčem RSA o 2 048 bitech
     * s datem 1. 1. 2027 —, Digitální kontinuita radí jednat nejlépe ihned.
     *
     * @param description „RSA, klíč 2 048 bitů“
     * @param reliableUntil od kdy se algoritmus nepovažuje za spolehlivý
     * @param forecast datum leží za poslední aktualizací sady — ověření na ně reaguje jen
     *        varováním, jiné validátory razítko mohou odmítnout
     */
    public record TimestampKey(String description, Instant reliableUntil, boolean forecast) { }

    /**
     * Doplňuje ho mapper až po sestavení prvku, proto není v konstruktoru: souhrn má tři
     * konstruktory s desítkami parametrů a údaj, který čte jediné místo, by je všechny
     * prodloužil. {@code null}, když prvek žádné razítko nekryje, nebo když algoritmus sada
     * neomezuje.
     */
    private TimestampKey newestTimestampKey;

    public TimestampKey getNewestTimestampKey()          { return newestTimestampKey; }
    public void setNewestTimestampKey(TimestampKey v)    { this.newestTimestampKey = v; }

    /**
     * Důvěryhodný seznam, podle kterého se posuzoval certifikát podpisu — země, pořadové číslo
     * a datum vydání, jak je DSS zapsalo do diagnostických dat u služby, pod níž je certifikát
     * veden. Doplněno 16. 10. 2026 na pokyn uživatele: datum, kdy AnEP seznamy naposledy porovnal
     * se zdrojem, neříká, z jakého stavu seznamu závěr o podpisu vychází; tohle ano.
     */
    public record TrustListRef(String territory, Integer sequenceNumber, Instant issueDate) { }

    /**
     * Doplňuje ho mapper po sestavení prvku, ze stejného důvodu jako {@link #newestTimestampKey}.
     * {@code null}, když certifikát podpisu pod žádnou službou z důvěryhodného seznamu veden
     * není (vlastní kotva, nesestavený řetěz, osiřelý podpis).
     */
    private TrustListRef trustList;

    public TrustListRef getTrustList()                   { return trustList; }
    public void setTrustList(TrustListRef v)             { this.trustList = v; }

    /** True when at least one evidence record actually proves this signature's earlier existence. */
    public boolean hasProvingEvidenceRecord() {
        return evidenceRecords.stream().anyMatch(EvidenceRecordEntry::providesProof);
    }

    /**
     * True when the best-signature-time is the one an evidence record proves — i.e. the record,
     * not a time-stamp of the signature itself, supplied the decisive instant.
     */
    public boolean isBestSignatureTimeFromEvidenceRecord() {
        return bestSignatureTime != null && evidenceRecords.stream()
                .filter(EvidenceRecordEntry::providesProof)
                .anyMatch(er -> bestSignatureTime.equals(er.earliestProof()));
    }
}
