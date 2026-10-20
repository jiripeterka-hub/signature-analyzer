package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import cz.digitalnikontinuita.anep.core.OcspFirstCrlFallbackRevocationDataLoadingStrategy.SkippedRevocation;
import cz.digitalnikontinuita.anep.core.PdfOrphanSignatureDetector.OrphanSignature;
import cz.digitalnikontinuita.anep.model.EvidenceRecordEntry;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.TimestampChain;
import cz.digitalnikontinuita.anep.model.ValidationCourse;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.diagnostic.CertificateRevocationWrapper;
import eu.europa.esig.dss.diagnostic.RelatedRevocationWrapper;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.EvidenceRecordWrapper;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.PDFRevisionWrapper;
import eu.europa.esig.dss.diagnostic.SignerDataWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.diagnostic.TrustServiceWrapper;
import cz.digitalnikontinuita.anep.model.SignatureSummary.AugmentationDeadline;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.CertificateStatus;
import eu.europa.esig.dss.enumerations.DigestMatcherType;
import eu.europa.esig.dss.enumerations.CertificateQualification;
import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.enumerations.RevocationType;
import eu.europa.esig.dss.enumerations.SubIndication;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.enumerations.SignatureProfile;
import eu.europa.esig.dss.enumerations.SignatureQualification;
import eu.europa.esig.dss.enumerations.TimestampQualification;
import eu.europa.esig.dss.enumerations.TimestampType;
import eu.europa.esig.dss.detailedreport.DetailedReport;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.validation.reports.Reports;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ReportMapper {

    /**
     * The tolerances a user can switch on, each able to turn a refusal into an acceptance.
     *
     * <p>Named here because three places need to agree about them: the run that measures which of
     * them a verdict actually required, the list shown beside that verdict, and situation 34. The
     * validation date is deliberately not among them — it is a concession too, but no re-run can
     * measure it, since repeating the validation at a different time answers a different question.
     */
    /**
     * Výsledek měření nutnosti ústupků pro jeden podpis.
     *
     * @param settings     ústupky, na nichž kladný verdikt stojí
     * @param alternatives {@code true}, když se navzájem zastupují — stačí kterýkoli z nich,
     *                     ne všechny zároveň. Rozdíl, který text nesmí smazat: „bez tohohle by
     *                     výsledek nebyl kladný" je nad zastupitelnou dvojicí nepravda, protože
     *                     bez jednoho by ho nesl ten druhý.
     */
    public record RequiredRelaxations(java.util.Set<Relaxation> settings, boolean alternatives) { }

    public enum Relaxation {
        NEWER_OCSP("Využij i revokační informace, vydané po expiraci certifikátu",
                "povoleno OCSP po expiraci certifikátu"),
        // Nejmenuje se to podle expirace, i když constraint v DSS ano (RevocationIssuerNotExpired).
        // Ten test je na *rozsah* platnosti, ne na jeho konec, a při ověřování k okamžiku v minulosti
        // spadne skoro vždycky na spodní mez: respondér, který odpověď podepsal dnes, tehdy ještě
        // vydaný nebyl. Popisek podle horní meze tvrdil o takovém běhu něco, co se nestalo.
        EXPIRED_REVOCATION_SOURCES("Využij i revokační informace od již expirovaného vydavatele",
                "povoleny revokační informace od již expirovaného vydavatele"),
        SHA1("Ignorovat zastaralost algoritmu SHA1",
                "ignorována zastaralost SHA-1"),
        PDF_NOT_ETSI("Ověřovat i podpisy ve formátu PDF-NOT-ETSI",
                "povoleno ověřování podpisů ve formátu PDF-NOT-ETSI");
        // Ústupek pro chybějící či nečitelný signing-time tu byl 3. 10. 2026 jen chvíli: týž den
        // se z něj stala trvalá součást politiky (ValidationPolicyFactory.applySigningTimeRelaxation).

        private final String settingName;
        private final String concessionName;

        Relaxation(String settingName, String concessionName) {
            this.settingName = settingName;
            this.concessionName = concessionName;
        }

        /** As the settings dialog names it, for the list of what carried the verdict. */
        public String settingName() { return settingName; }

        /** As situation 34 names it, for the list of concessions the verdict was reached under. */
        public String concessionName() { return concessionName; }

        public boolean isEnabled(AppSettings settings) {
            return switch (this) {
                case NEWER_OCSP -> settings.isAcceptNewerOcsp();
                case EXPIRED_REVOCATION_SOURCES -> settings.isAcceptExpiredRevocationSources();
                case SHA1 -> settings.isIgnoreSha1Deprecated();
                case PDF_NOT_ETSI -> settings.isAcceptPdfNotEtsi();
            };
        }

        public void setEnabled(AppSettings settings, boolean enabled) {
            switch (this) {
                case NEWER_OCSP -> settings.setAcceptNewerOcsp(enabled);
                case EXPIRED_REVOCATION_SOURCES -> settings.setAcceptExpiredRevocationSources(enabled);
                case SHA1 -> settings.setIgnoreSha1Deprecated(enabled);
                case PDF_NOT_ETSI -> settings.setAcceptPdfNotEtsi(enabled);
            }
        }

        /** Those switched on right now, in declaration order. */
        public static List<Relaxation> enabledIn(AppSettings settings) {
            List<Relaxation> on = new ArrayList<>();
            for (Relaxation r : values()) if (r.isEnabled(settings)) on.add(r);
            return on;
        }
    }

    public ValidationResult map(String fileName, String detectedContainerType,
                                 ValidationOutcome outcome, AppSettings settings) {
        return map(fileName, detectedContainerType, outcome, settings, java.util.Map.of(), List.of());
    }

    public ValidationResult map(String fileName, String detectedContainerType,
                                 ValidationOutcome outcome, AppSettings settings,
                                 java.util.Map<String, PdfSignatureAppearanceExtractor.Appearance> signatureAppearances) {
        return map(fileName, detectedContainerType, outcome, settings, signatureAppearances, List.of());
    }

    public ValidationResult map(String fileName, String detectedContainerType,
                                 ValidationOutcome outcome, AppSettings settings,
                                 java.util.Map<String, PdfSignatureAppearanceExtractor.Appearance> signatureAppearances,
                                 List<PdfOrphanSignatureDetector.OrphanSignature> orphans) {
        return map(fileName, detectedContainerType, outcome, settings, signatureAppearances, orphans, false);
    }

    public ValidationResult map(String fileName, String detectedContainerType,
                                 ValidationOutcome outcome, AppSettings settings,
                                 java.util.Map<String, PdfSignatureAppearanceExtractor.Appearance> signatureAppearances,
                                 List<PdfOrphanSignatureDetector.OrphanSignature> orphans,
                                 boolean hasExternalSig) {
        return map(fileName, detectedContainerType, outcome, settings, signatureAppearances,
                orphans, hasExternalSig, java.util.Map.of());
    }

    public ValidationResult map(String fileName, String detectedContainerType,
                                 ValidationOutcome outcome, AppSettings settings,
                                 java.util.Map<String, PdfSignatureAppearanceExtractor.Appearance> signatureAppearances,
                                 List<PdfOrphanSignatureDetector.OrphanSignature> orphans,
                                 boolean hasExternalSig,
                                 java.util.Map<String, String> signingTools) {
        return map(fileName, detectedContainerType, outcome, settings, signatureAppearances,
                orphans, hasExternalSig, signingTools, null);
    }

    /**
     * @param requiredRelaxations per signature, the tolerances without which it would not have been
     *        accepted, as established by re-running the validation; an empty set means it passes
     *        with all of them switched off. Signatures absent from the map, and a {@code null} map,
     *        were not measured and fall back to inference — see {@link #computeDecisiveSettings}.
     */
    public ValidationResult map(String fileName, String detectedContainerType,
                                 ValidationOutcome outcome, AppSettings settings,
                                 java.util.Map<String, PdfSignatureAppearanceExtractor.Appearance> signatureAppearances,
                                 List<PdfOrphanSignatureDetector.OrphanSignature> orphans,
                                 boolean hasExternalSig,
                                 java.util.Map<String, String> signingTools,
                                 java.util.Map<String, RequiredRelaxations> requiredRelaxations) {
        return map(fileName, detectedContainerType, outcome, settings, signatureAppearances,
                orphans, hasExternalSig, signingTools, requiredRelaxations, 0);
    }

    /**
     * @param strayPrefixBytes bytes {@link cz.digitalnikontinuita.anep.core.DocumentLoader} had to cut off
     *        the front of the file for DSS to recognise the document; 0 for everything well-formed.
     *        No signature covers them, so the course of the verification says they were there.
     */
    public ValidationResult map(String fileName, String detectedContainerType,
                                 ValidationOutcome outcome, AppSettings settings,
                                 java.util.Map<String, PdfSignatureAppearanceExtractor.Appearance> signatureAppearances,
                                 List<PdfOrphanSignatureDetector.OrphanSignature> orphans,
                                 boolean hasExternalSig,
                                 java.util.Map<String, String> signingTools,
                                 java.util.Map<String, RequiredRelaxations> requiredRelaxations,
                                 int strayPrefixBytes) {
        Reports baseReports = outcome.reports();
        SimpleReport baseReport = baseReports.getSimpleReport();
        DiagnosticData dd = baseReports.getDiagnosticData();
        List<SignatureSummary> summaries = new ArrayList<>();
        // Collect PDF field names that DSS has processed, so orphan detection can filter them out.
        java.util.Set<String> dssFieldNames = new java.util.HashSet<>();
        // Rozsah bajtů je totožnost, která přežije i rozdílné pojmenování téhož pole — viz
        // filtr osiřelých podpisů níž.
        java.util.Set<String> dssByteRanges = new java.util.HashSet<>();
        // Obojí se plní tady, před hlavní smyčkou, a ne v ní: pořadí prvků se počítá ještě dřív
        // a musí vědět, které osiřelé podpisy se na kartu opravdu dostanou. Zdroj je týž jako
        // předtím (identifikátory ze zjednodušeného protokolu, prvky z diagnostických dat),
        // takže se obsah obou množin nemění — jen vznikne o pár řádků výš.
        for (String id : baseReport.getSignatureIdList()) {
            SignatureWrapper sw = dd.getSignatureById(id);
            if (sw == null) continue;
            if (sw.getPDFRevision() != null) {
                String br = byteRangeKey(sw.getPDFRevision().getSignatureByteRange());
                if (br != null) dssByteRanges.add(br);
            }
            if (sw.getFirstFieldName() != null) dssFieldNames.add(sw.getFirstFieldName());
        }
        // Osiřelé podpisy, které se stanou kartou. Potřebuje to řazení prvků i smyčka, která je
        // vysází; kdyby si to každé místo filtrovalo po svém, rozešla by se čísla prvků s tím,
        // co je vidět.
        List<OrphanSignature> orphanCards =
                orphanCards(orphans, dssFieldNames, dssByteRanges);

        // Pořadí prvků se počítá hned tady, ne až u řazení na konci: výklad situace 54 jmenuje
        // krycí prvek číslem, a to číslo musí být totéž, které čtenář uvidí v záhlaví karty.
        java.util.Map<String, Integer> docPos = documentPositions(dd, baseReport, orphanCards);
        java.util.Map<String, Integer> displayPositions = displayPositions(docPos);

        for (String id : baseReport.getSignatureIdList()) {
            Instant signingTime = baseReport.getSigningTime(id) != null
                    ? baseReport.getSigningTime(id).toInstant()
                    : null;
            SignatureQualification q = baseReport.getSignatureQualification(id);
            Instant verifiableUntil = computeVerifiableUntil(id, dd, baseReport);
            boolean hasTs = hasValidTimestamp(id, dd, baseReport);
            // Use the earliest valid timestamp's production time as BST.
            // getBestSignatureTime() falls back to current system time for INDETERMINATE.
            Instant bestSigTime = earliestValidTimestampTime(id, dd, baseReport);
            // Důkaz existence odvozený z prvku, který tenhle kryje — viz coveringProof().
            // Sám o sobě rozhodný okamžik nepřepisuje; to dělá až kladný druhý průchod níž.
            CoveringProof covering = coveringProof(id, dd, baseReport);
            // Závěr téhož ověření spočítaný k tomu odvozenému okamžiku — viz
            // ValidatorFactory.derivedTimeResults(). Bere se, jen když je kladný: k čemu se
            // dobral záporný nebo neprůkazný druhý průchod, čtenáři neříká nic, co by nevěděl
            // z prvního.
            ValidatorFactory.DerivedTimeResult derived = outcome.derivedTimeResults().get(id);
            boolean derivedPasses = derived != null && derived.reports() != null
                    && Indication.TOTAL_PASSED.name().equals(derived.indication());
            // Prvek, jehož závěr vydal druhý průchod, se z něj má i celý mapovat. Jinak by karta
            // hlásila „platný" a pod tím vyjmenovala důvody, ke kterým se dobral průchod první —
            // situace, důvody, rozhodný okamžik i průběh ověřování patří k závěru, který nese.
            //
            // Stínící lokálky, ne přepis jednotlivých volání: metod, které se protokolu ptají, je
            // uvnitř smyčky přes třicet a vybírat je po jedné by znamenalo, že se na jednu zapomene.
            // Vnější podoby se jmenují baseReport / baseReports a slouží prvkům, kterých se druhý
            // průchod netýká, a samostatným razítkům pod smyčkou.
            SimpleReport simpleReport = derivedPasses
                    ? derived.reports().getSimpleReport() : baseReport;
            Reports reports = derivedPasses ? derived.reports() : baseReports;
            // A rozhodný okamžik je pak ten odvozený. Všechno, co se o něj opírá (situace 20
            // a 24, výběr revokačního údaje, věta o odstupu revokace), musí soudit k okamžiku,
            // na kterém závěr stojí — ne k přítomnosti.
            //
            // {@code derived.when()} je nejstarší z dostupných důkazů: má-li prvek vlastní platné
            // razítko starší než krycí prvek, je to ono (viz ValidatorFactory.derivedTimeResults).
            if (derivedPasses) bestSigTime = derived.when().toInstant();
            // Evidenční záznam je týž důkaz existence jako razítko, jen nese ho jiný objekt.
            // Do 27. 9. 2026 se sem nedostal: rozhodný okamžik znal jen razítka prvku, takže
            // u podpisu bez razítka, který DSS uznal právě díky záznamu, soudily situace 20 a 24
            // i celý Průběh ověřování k přítomnosti — a Analýza tvrdila, že certifikát byl
            // odvolán „ještě před rozhodným okamžikem", ačkoli k odvolání došlo půl roku po
            // čase, který záznam dokládá (nález uživatele). Rozšířený přehled přitom ten
            // okamžik jmenoval správně. Rozhoduje nejstarší z důkazů.
            Instant recordProof = earliestEvidenceRecordProof(id, dd, simpleReport);
            if (recordProof != null
                    && (bestSigTime == null || recordProof.isBefore(bestSigTime))) {
                bestSigTime = recordProof;
            }
            boolean decisiveInterchangeable = requiredRelaxations != null
                    && requiredRelaxations.containsKey(id)
                    && requiredRelaxations.get(id).alternatives();
            // Změřeno, nebo jen odvozeno? Prázdný výčet znamená obojí a karta ty dvě věci
            // nesmí ztotožnit — viz SignatureSummary.decisiveSettingsMeasured.
            boolean decisiveMeasured = requiredRelaxations != null
                    && requiredRelaxations.containsKey(id);
            List<String> decisive = computeDecisiveSettings(id, simpleReport, dd, settings,
                    requiredRelaxations);
            SignatureWrapper sw = dd.getSignatureById(id);
            String encAlg = sw != null && sw.getEncryptionAlgorithm() != null
                    ? sw.getEncryptionAlgorithm().getName() : null;
            String digAlg = sw != null && sw.getDigestAlgorithm() != null
                    ? sw.getDigestAlgorithm().getName() : null;
            String certDN = sw != null && sw.getSigningCertificate() != null
                    ? sw.getSigningCertificate().getCertificateDN() : null;
            CertificateWrapper sigCert = sw != null ? sw.getSigningCertificate() : null;
            String ikMpsv = IkMpsv.of(sigCert != null ? sigCert.getBinaries() : null);
            SystemCertificate.Kind sysKind = systemCertificateKind(sigCert, null);
            boolean sysZnacka = sysKind != SystemCertificate.Kind.NONE;
            boolean qualifiedFlag = sysZnacka
                    ? sysKind == SystemCertificate.Kind.QUALIFIED_SYSTEM : isQualifiedSig(q);
            String legalTypeStr = sysZnacka
                    ? (qualifiedFlag
                        ? "uznávanou elektronickou značku (dle zákona č. 227/2000 Sb.)"
                        : "elektronickou značku (dle zákona č. 227/2000 Sb.)")
                    : legalType(q);
            List<TimestampEntry> tsTimesAsc = allTimestamps(id, dd, simpleReport);
            String customAnchor = customTrustAnchorName(id, dd, simpleReport);
            boolean viaCustomStore = customAnchor != null;
            String signedBy = simpleReport.getSignedBy(id);
            String signerEmail = sw != null
                    ? CertificateEmailUtil.extractEmail(sw.getSigningCertificate()) : null;
            if (signedBy != null && signerEmail != null) {
                signedBy = signedBy + " (" + signerEmail + ")";
            }
            // Look up pre-extracted PDF appearance by the PDF field name reported by DSS
            byte[] appearancePng = null;
            PdfFieldVisibility appearanceVisibility = null;
            String signingTool = null;
            if (sw != null) {
                String pdfFieldName = sw.getFirstFieldName();
                if (pdfFieldName != null) {
                    PdfSignatureAppearanceExtractor.Appearance appearance =
                            signatureAppearances.get(pdfFieldName);
                    if (appearance != null) {
                        appearancePng = appearance.png();
                        appearanceVisibility = appearance.visibility();
                    }
                    signingTool = signingTools.get(pdfFieldName);
                }
            }
            S33Result s33 = sw != null ? computeS33(id, dd, simpleReport, bestSigTime)
                    : S33Result.none();
            S34Result s34 = computeS34(settings, id, requiredRelaxations);
            List<Integer> situations = detectFailureSituations(id, dd, simpleReport,
                    reports.getDetailedReport(), settings, bestSigTime, hasExternalSig,
                    s33.detected(), s33.tolerantlyComplete(), s34.detected(),
                    outcome.skippedRevocations(), derivedPasses);
            // Čeho se revokační nálezy týkají: u neznámé kotvy (důvod 3) umlčí detectReasons()
            // rady o revokaci podpisového řetězu, kdežto tyhle — z řetězu razítka, který ukotvený
            // být může — nechá stát.
            java.util.Set<Integer> revocOnTimestampChains = sw != null
                    ? revocationReasonsOnTimestampChains(sw, dd, simpleReport.getSubIndication(id),
                            settings, outcome.skippedRevocations())
                    : java.util.Set.copyOf(REVOCATION_ADVICE_REASONS);
            ReasonFacts reasonFacts = new ReasonFacts(revocOnTimestampChains,
                    sw != null && naTimestampsAllNoChain(sw, simpleReport),
                    covering != null && sigCert != null && sigCert.getNotAfter() != null
                            && covering.when().isBefore(sigCert.getNotAfter().toInstant()),
                    sw != null && ownPoeWithinValidity(sw, simpleReport));
            List<Integer> reasons = detectReasons(situations, settings, reasonFacts);
            // Důvody 24 a 25 mluví „o certifikátu", a ten může být z řetězu podpisu i z řetězu
            // razítka. Jméno se bere z téhož výčtu, který situaci vyvolal — jinak by text
            // jmenoval certifikát, kterého se nález netýká.
            java.util.Map<Integer, String> reasonCertificates = new java.util.LinkedHashMap<>();
            if (sw != null && (reasons.contains(24) || reasons.contains(25))) {
                boolean sealElement = isSeal(simpleReport, id);
                if (reasons.contains(24)) {
                    List<CertificateWrapper> certs = outOfRangeRevocationIssuerCerts(
                            sw, settings.getValidationDate(), ChainScope.ALL);
                    if (!certs.isEmpty()) {
                        reasonCertificates.put(24,
                                revocationSubject(sw, certs.get(0), sealElement));
                    }
                }
                if (reasons.contains(25)) {
                    List<CertificateWrapper> certs = uninformativeExternalCerts(sw);
                    if (!certs.isEmpty()) {
                        reasonCertificates.put(25,
                                revocationSubject(sw, certs.get(0), sealElement));
                    }
                }
            }
            String sha1Detail = (sw != null && reasons.contains(15)) ? buildSha1Detail(sw, dd) : null;
            List<CertificateWrapper> noRevocationCerts = new ArrayList<>();
            String noRevocationDetail = (sw != null && reasons.contains(12))
                    ? buildNoRevocationDetail(id, sw, dd, simpleReport, noRevocationCerts) : null;
            // Pass bestSigTime when S21 fires so the detail can mention revocation before BST.
            List<CertificateWrapper> newerRevocationCerts = new ArrayList<>();
            String newerRevocationDetail = (sw != null && reasons.contains(2))
                    ? buildNewerRevocationDetail(sw, dd, situations.contains(21) ? bestSigTime : null,
                            newerRevocationCerts) : null;
            List<CertificateWrapper> expiredResponderCerts = new ArrayList<>();
            String expiredResponderDetail = (sw != null && reasons.contains(6))
                    ? buildExpiredResponderDetail(sw, expiredResponderCerts) : null;
            // Rada „sežeňte CRL z vhodné doby“ stojí v Analýze jednou, pod spodním z důvodů,
            // kterých se týká — tak i okno za ní je jedno: certifikáty všech těch důvodů
            // dohromady, ať už je jejich revokační údaj pozdní, od expirovaného vydavatele,
            // nebo žádný.
            List<CertificateWrapper> crlSourceCerts = new ArrayList<>(newerRevocationCerts);
            crlSourceCerts.addAll(expiredResponderCerts);
            crlSourceCerts.addAll(noRevocationCerts);
            // Důvody 10 (stav neznámý) a 11 (vložený údaj nepoužitelný) vedou k téže radě, takže
            // patří i do téhož okna. Bez nich se rada u nich ukázala bez odkazu — jediná z pěti.
            if (sw != null && reasons.contains(10)) {
                crlSourceCerts.addAll(unknownStatusCerts(sw, outcome.skippedRevocations()));
            }
            if (sw != null && reasons.contains(11)) {
                crlSourceCerts.addAll(unusableEmbeddedCerts(sw, simpleReport.getSubIndication(id)));
            }
            if (sw != null && reasons.contains(25)) {
                crlSourceCerts.addAll(uninformativeExternalCerts(sw));
            }
            String crlSourceHelp = buildCrlSourceHelp(crlSourceCerts);
            String s33Detail = s33.detail();
            String s33MissingDetail = s33.missingDetail();
            List<byte[]> s33CrlBytes = s33.crlBytes();
            // Custom validation time is decisive when it was set and the signature is TOTAL_PASSED.
            // Store the formatted date/time so the UI can display it even after the setting is cleared.
            String decisiveCustomTime = null;
            if (simpleReport.getIndication(id) == Indication.TOTAL_PASSED
                    && settings.isUseCustomValidationTime()
                    && settings.getCustomValidationTime() != null) {
                decisiveCustomTime = TimeFormat.full(
                        settings.getCustomValidationTime().atZone(java.time.ZoneId.systemDefault()).toInstant());
            }
            String laterRevokedDetail = null;
            if (sw != null && situations.contains(24)) {
                if (settings.getCustomValidationTime() != null) {
                    Instant customTime = settings.getCustomValidationTime()
                            .atZone(java.time.ZoneId.systemDefault()).toInstant();
                    laterRevokedDetail = buildLaterRevokedDetail(sw, customTime);
                } else if (bestSigTime != null) {
                    laterRevokedDetail = buildRevokedAfterSigningDetail(sw, bestSigTime,
                        bestSigTime.equals(recordProof) ? "evidenčním záznamem"
                                : !derivedPasses ? "časovým razítkem" : null);
                }
            }
            // Údaje o certifikátu podpisu — táž metoda staví i kartu podpisu adbe.x509.rsa_sha1,
            // který DSS nezpracuje a ověřuje ho Pkcs1SignatureValidator.
            CertFacts certFacts = certFacts(sigCert, sysZnacka, qualifiedFlag);
            String certKind = certFacts.kind();
            Boolean certQscd = certFacts.qscd();
            String certIssuerDN = certFacts.issuerDN();
            java.time.Instant certNotBefore = certFacts.notBefore();
            java.time.Instant certNotAfter = certFacts.notAfter();
            boolean certRevoked = certFacts.revoked();
            java.time.Instant certRevokedAt = certFacts.revokedAt();
            String certSerialNumber = certFacts.serialNumber();
            boolean certSelfSigned = certFacts.selfSigned();
            boolean certQcCompliant = certFacts.qcCompliant();
            java.util.List<String> certQcTypeDescriptions = certFacts.qcTypeDescriptions();
            eu.europa.esig.dss.diagnostic.PDFRevisionWrapper pdfRev =
                    sw != null ? sw.getPDFRevision() : null;
            String pdfReason      = pdfRev != null ? pdfRev.getReason()      : null;
            String pdfLocation    = pdfRev != null ? pdfRev.getLocation()    : null;
            String pdfContactInfo = pdfRev != null ? pdfRev.getContactInfo() : null;
            eu.europa.esig.dss.enumerations.CertificationPermission docMdp =
                    pdfRev != null ? pdfRev.getDocMDPPermissions() : null;
            String certification = docMdp != null ? docMdp.name() : null;
            SignatureSummary.PdfLock pdfLock = readPdfLock(pdfRev);
            boolean usedCustomTime = settings.isUseCustomValidationTime()
                    && settings.getValidationDate() != null;
            summaries.add(new SignatureSummary(
                    id,
                    String.valueOf(simpleReport.getSignatureFormat(id)),
                    // Kladný závěr k odvozenému okamžiku má přednost. Není to přepis verdiktu,
                    // nýbrž týž proces s tím, co o prvku víme navíc: EN 319 102-1 tabulka 20 zná
                    // „time indication for signature existence" jako vstup od Driving Application
                    // a krok 5.5.4 1) jím inicializuje best-signature-time. Situace 54 to u prvku
                    // pojmenuje, aby čtenář věděl, oč se závěr opírá.
                    derivedPasses ? Indication.TOTAL_PASSED.name()
                            : String.valueOf(simpleReport.getIndication(id)),
                    derivedPasses ? null
                            : simpleReport.getSubIndication(id) != null
                                    ? String.valueOf(simpleReport.getSubIndication(id)) : null,
                    signedBy,
                    signingTime,
                    qualifiedFlag,
                    legalTypeStr,
                    bestSigTime,
                    verifiableUntil,
                    hasTs,
                    decisive,
                    decisiveInterchangeable,
                    decisiveMeasured,
                    encAlg,
                    digAlg,
                    certDN,
                    tsTimesAsc,
                    viaCustomStore,
                    customAnchor,
                    computeAugmentationDeadline(id, dd, simpleReport),
                    computeUnprovenRevocationDeadline(id, dd, simpleReport),
                    situations,
                    reasons,
                    sha1Detail,
                    noRevocationDetail,
                    newerRevocationDetail,
                    crlSourceHelp,
                    expiredResponderDetail,
                    decisiveCustomTime,
                    laterRevokedDetail,
                    appearancePng,
                    appearanceVisibility,
                    ikMpsv,
                    pdfReason, pdfLocation, pdfContactInfo,
                    usedCustomTime,
                    certification,
                    certKind, certQscd, certIssuerDN,
                    certNotBefore, certNotAfter, certRevoked, certRevokedAt,
                    certSerialNumber, certSelfSigned,
                    certQcCompliant, certQcTypeDescriptions,
                    s33Detail, s33MissingDetail, s33CrlBytes, s33.preservable(),
                    s33.tolerantDetail(), s33.tolerantCrlBytes(),
                    s34.failReason(),
                    signingTool,
                    buildValidationCourse(id, dd, simpleReport, settings, bestSigTime,
                            tsTimesAsc, outcome.skippedRevocations(), strayPrefixBytes,
                            reports.getXmlDetailedReport()),
                    mapEvidenceRecords(dd.getEvidenceRecords(), id, simpleReport, fileName),
                    situations.contains(43)
                            ? withdrawnTimestampServiceDetail(
                                    sw, simpleReport.getBestSignatureTime(id))
                            : situations.contains(49)
                            ? unqualifiedProofDetail(id, sw, simpleReport,
                                    reports.getDetailedReport()) : null,
                    situations.contains(50)
                            ? unqualifiedProofCarrierDetail(id, sw, simpleReport,
                                    reports.getDetailedReport()) : null,
                    situations.contains(51) ? staleRevocationDetail(sw, simpleReport, id) : null,
                    pdfLock,
                    // Nese se i tam, kde krycí důkaz <b>nepomohl</b> — od 22. 9. 2026. Dokud se
                    // vydával jen se situací 54, tvrdily texty u takového prvku, že „není o co se
                    // opřít" a že jde o nenávratnou ztrátu kontinuity, ačkoli dokument razítko má
                    // a aplikace z něj důkaz odvodila (nález uživatele nad dokumentem se
                    // dvěma pečetěmi, z nichž druhá kryje první). U prvku, který obstál i bez toho, se nevydává: tam se přepočet
                    // nepouštěl, takže věta „ověření k tomu okamžiku neuspělo" by lhala.
                    situations.contains(54) || simpleReport.getIndication(id) == Indication.INDETERMINATE
                            ? coveringProofDetail(covering, bestSigTime,
                                    // Předaný důkaz hodinami nehýbe: kontrolní čas je dnešek,
                                    // a věta o „pozdějším okamžiku" by o něm lhala.
                                    derivedPasses && !derived.poeSupplied()
                                            ? derived.controlTime().toInstant() : null,
                                    displayPositions, situations.contains(54)) : null,
                    situations.contains(54) ? coveringProofTimestamp(covering, dd, baseReport)
                            : null,
                    counterSignatureOfId(sw),
                    null,  // příčina selhání samostatného razítka — tenhle prvek je podpis
                    dssRemarks(id, simpleReport),
                    reasonCertificates,
                    // Prvek je podpis; údaje o razítkovací službě nese TimestampEntry. Kotvou ale
                    // může být i sám podpisový certifikát — viz SignatureSummary.anchorCert.
                    null,
                    sw != null && sw.getSigningCertificate() != null
                            && sw.getSigningCertificate().isTrusted(),
                    newerRevocationCerts.isEmpty()
                            || newerRevocationCerts.get(0).getNotAfter() == null
                            ? null : newerRevocationCerts.get(0).getNotAfter().toInstant(),
                    sw == null || !reasons.contains(6) ? null : expiredResponderNotAfter(sw),
                    situations.contains(22) && outcome.unreadableSigningTimes().contains(id)
            ));
        }

        // A file whose only authentication elements are time-stamps has no signature to hang
        // situation 33 on, yet the question it answers is exactly the one preservation asks there:
        // renewing the chain means embedding revocation data for the time-stamps already inside,
        // and that has to be obtainable. Computed once for the file, not per time-stamp, because
        // that is what it is a fact about — and it holds for a chain of PDF document time-stamps
        // just as for an ASiC. For every other file this stays empty and the summaries below are
        // unchanged.
        boolean timestampOnlyFile = isTimestampOnlyFile(dd);
        S33Result containerS33 = timestampOnlyFile
                ? computeS33ForContainerTimestamps(dd)
                : S33Result.none();

        // A chain of time-stamps has one deadline, and it belongs to its newest link. Each stamp
        // fixes the one before it, so an earlier one is not waiting to be preserved — it is
        // preserved already, by the stamp that covers it. Saying "lhůta pro uchování: ihned" on
        // its card reads as a document falling apart, when in fact the only date that can still be
        // acted on is the last one's. Read from the report rather than from the diagnostic data, so
        // that it ranks exactly the stamps that get a card of their own.
        String newestTimestampId = null;
        java.util.Date newestProduction = null;
        for (String id : baseReport.getTimestampIdList()) {
            java.util.Date produced = baseReport.getProductionTime(id);
            if (produced == null) continue;
            if (newestProduction == null || produced.after(newestProduction)) {
                newestProduction = produced;
                newestTimestampId = id;
            }
        }

        for (String id : baseReport.getTimestampIdList()) {
            Instant productionTime = baseReport.getProductionTime(id) != null
                    ? baseReport.getProductionTime(id).toInstant()
                    : null;
            TimestampQualification q = baseReport.getTimestampQualification(id);
            // DSS baseReports standalone timestamps with bare PASSED/FAILED (not TOTAL_PASSED/
            // TOTAL_FAILED, which are reserved for signatures) — normalize so the rest of the
            // app (which only checks for the TOTAL_* forms) doesn't treat a valid timestamp
            // as "neznámý".
            Indication tsIndication = baseReport.getIndication(id);
            String normalizedIndication = switch (tsIndication) {
                case PASSED -> String.valueOf(Indication.TOTAL_PASSED);
                case FAILED -> String.valueOf(Indication.TOTAL_FAILED);
                default -> String.valueOf(tsIndication);
            };
            // Proč neobstálo — zařazeno z klíčů hlášení protokolu DSS, stejně jako u razítek
            // uvnitř podpisu. Wrapper se hledá kvůli jedinému dotazu navíc: porušený token se
            // pozná dřív než z indikace a musí se rozlišit od razítka, jehož krytá data se
            // nenašla (viz TimestampFailure.of a hasBrokenTimestampIntegrity).
            TimestampWrapper tsAlgToken = dd.getTimestampById(id);
            SignatureSummary.TimestampFailure tsFailure = null;
            if (tsIndication != Indication.PASSED) {
                TimestampWrapper tsw = tsAlgToken;
                tsFailure = tsw != null
                        ? timestampFailure(tsw, baseReport)
                        : SignatureSummary.TimestampFailure.of(messageKeys(baseReport, id),
                                baseReport.getSubIndication(id) == null
                                        ? null : baseReport.getSubIndication(id).name());
            }
            // A time-stamp on its own — a forensic package, a time-stamped container — used to be
            // reported with almost nothing: who issued it and when, and that was all. Yet its
            // certificate is validated exactly like a signing certificate, with revocation data
            // fetched for it and an expiry that decides how long the stamp remains verifiable, so
            // the same facts exist and there was no reason to withhold them.
            TimestampWrapper tsw = dd.getTimestampById(id);
            CertificateWrapper tsCert = tsw != null ? tsw.getSigningCertificate() : null;
            if (tsCert != null) {
                CertificateWrapper full = dd.getUsedCertificateById(tsCert.getId());
                if (full != null) tsCert = full;
            }
            String tsCertDN = tsCert != null ? tsCert.getCertificateDN() : null;
            String tsIssuerDN = tsCert != null ? tsCert.getCertificateIssuerDN() : null;
            String tsSerial = tsCert != null ? tsCert.getSerialNumber() : null;
            boolean tsSelfSigned = tsCert != null && tsCert.isSelfSigned();
            Instant tsNotBefore = tsCert != null && tsCert.getNotBefore() != null
                    ? tsCert.getNotBefore().toInstant() : null;
            Instant tsNotAfter = tsCert != null && tsCert.getNotAfter() != null
                    ? tsCert.getNotAfter().toInstant() : null;
            boolean tsCertRevoked = false;
            Instant tsCertRevokedAt = null;
            if (tsCert != null) {
                for (CertificateRevocationWrapper rev : tsCert.getCertificateRevocationData()) {
                    if (CertificateStatus.REVOKED.equals(rev.getStatus())) {
                        tsCertRevoked = true;
                        tsCertRevokedAt = rev.getRevocationDate() != null
                                ? rev.getRevocationDate().toInstant() : null;
                        break;
                    }
                }
            }
            // Deliberately no "druh certifikátu" and no QSCD flag. A time-stamping unit's
            // certificate carries no QC statements — measured on the Ministero della Difesa TSU,
            // qcCompliance=false and qcTypes empty while the stamp itself is QTSA — because a
            // time-stamp's qualified status comes from the Trusted List service, not from the
            // certificate. Deriving the label the way signatures do would print "nekvalifikovaný
            // certifikát" beside "kvalifikované elektronické časové razítko".
            Instant tsVerifiableUntil = tsw != null
                    && baseReport.getIndication(id) == Indication.PASSED
                    ? chainExpiry(tsw.getCertificateChain()) : null;
            AugmentationDeadline tsDeadline = tsw == null || !id.equals(newestTimestampId) ? null
                    : timestampAugDeadline(tsw, tsVerifiableUntil, dd);
            AugmentationDeadline tsUnproven = tsw == null || !id.equals(newestTimestampId) ? null
                    : unprovenRevocationDeadline(tsw.getCertificateChain(),
                            revocationsWithPoe(dd), ChainRole.TIMESTAMP);
            // S34 for a file that carries nothing but time-stamps, on the same terms as S33 above:
            // there is no signature to ask about, yet preservation asks the question anyway and
            // refuses to be offered without an answer. Without this a forensic package — valid,
            // renewable, with its TSU certificate good for years — could only ever be fixated again,
            // one container inside another, because situation 34 was never computed for it and the
            // gate reads it as "verified with concessions".
            //
            // Scoped to time-stamp-only files on purpose. A document time-stamp inside a signed PDF
            // also appears in this list, and there the signature already carries S34; saying it
            // twice on two cards would not tell the reader anything new.
            S34Result tsS34 = timestampOnlyFile
                    ? computeS34(settings, id, requiredRelaxations)
                    : new S34Result(false, null);
            // Rozhodující ústupky i u samostatného razítka. Měření je zná — {@code
            // RelaxationMeasurement.acceptedIn()} razítka počítá, a to schválně, kvůli
            // kontejnerům, které nic jiného nenesou —, jenže sem se do 19. 9. 2026 předával
            // natvrdo prázdný seznam. Blok „Průběh ověřování“ pak i o razítku, které bez
            // ústupku neobstojí, psal, že se ústupky neprojevily a bez nich by ověření
            // dopadlo stejně. V mapě je prvek jen tehdy, když ho ověření uznalo, takže
            // samotná přítomnost záznamu je zároveň odpovědí na to, zda razítko obstálo.
            RequiredRelaxations tsRelax = requiredRelaxations != null
                    ? requiredRelaxations.get(id) : null;
            List<String> tsDecisive = new ArrayList<>();
            if (tsRelax != null) {
                for (Relaxation r : Relaxation.values()) {
                    if (tsRelax.settings().contains(r)) tsDecisive.add(r.settingName());
                }
            }
            List<Integer> tsSituations = new ArrayList<>();
            // S33 is the one situation that applies: in a time-stamp-only container it is
            // what says whether the chain can still be renewed. S45 rides with it, saying
            // the same thing on the looser reading — a fixation container whose TSU
            // certificate has expired is exactly where the two answers diverge.
            if (containerS33.detected()) tsSituations.add(33);
            else if (containerS33.tolerantlyComplete()) tsSituations.add(45);
            if (tsS34.detected()) tsSituations.add(34);
            summaries.add(new SignatureSummary(
                    id,
                    "TimeStampToken",
                    normalizedIndication,
                    baseReport.getSubIndication(id) != null
                            ? String.valueOf(baseReport.getSubIndication(id)) : null,
                    baseReport.getProducedBy(id),
                    productionTime,
                    q == TimestampQualification.QTSA,
                    legalTypeTs(q),
                    null,               // bestSignatureTime — the stamp is its own moment
                    tsVerifiableUntil,
                    false,
                    List.copyOf(tsDecisive),
                    tsRelax != null && tsRelax.alternatives(),
                    tsRelax != null,
                    // Blok „Použité kryptografické algoritmy" v Rozšířeném přehledu stál
                    // u samostatného razítka prázdný — nadpis sliboval algoritmy a pod ním byl
                    // jen řádek o formátu, zopakovaný z hlavičky karty. Přitom je DSS zná: token
                    // razítka je podepsaný a wrapper ty dvě hodnoty nese stejně jako u podpisu.
                    tsAlgToken != null && tsAlgToken.getEncryptionAlgorithm() != null
                            ? tsAlgToken.getEncryptionAlgorithm().getName() : null,
                    tsAlgToken != null && tsAlgToken.getDigestAlgorithm() != null
                            ? tsAlgToken.getDigestAlgorithm().getName() : null,
                    tsCertDN,
                    List.of(),
                    false,
                    null,   // customTrustAnchorName — a lone time-stamp card does not show it
                    tsDeadline,
                    tsUnproven,
                    List.copyOf(tsSituations),
                    // Do 14. 9. 2026 tu stálo List.of() s poznámkou „failure reasons not
                    // applicable to standalone timestamps". Neplatilo to: takové razítko sice
                    // nemá podpisový certifikát ani vložené revokační údaje, ale neobstát může
                    // z týchž příčin jako razítko uvnitř podpisu — a protokol DSS je pojmenovává
                    // stejně. Sken 416 dokumentů našel 209 neúspěšných samostatných razítek
                    // a všechna do jednoho byla na kartě bez jediného důvodu.
                    tsFailure == null ? List.of() : List.of(31),
                    null,  // sha1Detail not applicable to standalone timestamps
                    null,  // noRevocationDetail not applicable to standalone timestamps
                    null,  // newerRevocationDetail not applicable to standalone timestamps
                    null,  // crlSourceHelp not applicable to standalone timestamps
                    null,  // expiredResponderDetail not applicable to standalone timestamps
                    null,  // decisiveCustomTime not applicable to standalone timestamps
                    null,  // laterRevokedDetail not applicable to standalone timestamps
                    null,  // standalone timestamps have no visual appearance
                    null,  // …and so no visibility to describe
                    null,  // IK MPSV not applicable to standalone timestamps
                    null, null, null,  // PDF signature dictionary fields not applicable to timestamps
                    false,  // usedCustomValidationTime not applicable to standalone timestamps
                    null,   // certificationPermission not applicable to standalone timestamps
                    // certKind and certQscd stay empty on purpose — see the note above on QC
                    // statements; the view knows to leave those two rows out for a time-stamp.
                    null, null, tsIssuerDN,
                    tsNotBefore, tsNotAfter, tsCertRevoked, tsCertRevokedAt,
                    tsSerial, tsSelfSigned,
                    false, java.util.List.of(),  // certQcCompliant/certQcTypeDescriptions — no QC statements in a TSU cert
                    containerS33.detail(), containerS33.missingDetail(), containerS33.crlBytes(),
                    containerS33.preservable(),
                    containerS33.tolerantDetail(), containerS33.tolerantCrlBytes(),
                    tsS34.failReason(),
                    null,  // signingTool not applicable to standalone timestamps
                    null,  // validationCourse not applicable to standalone timestamps
                    List.of(),  // evidence records attach to signatures, not to standalone timestamps
                    null, // withdrawnTsaDetail describes a signature's proof chain, not a lone timestamp
                    null, // a proofCarrierDetail ze stejného důvodu
                    null, // ani situace 51: mluví o podpisovém certifikátu, a ten tu žádný není
                    // Zámek klade podpis, ne razítko: /Lock je entry podpisového pole a razítko
                    // v PDF žádné pole nezamyká. Změřeno — DSS u dokumentového razítka v témž
                    // souboru vrací u obou dotazů null.
                    null,
                    // Situace 54 mluví o podpisu krytém pozdějším podpisem; samostatné razítko
                    // žádný podpisový certifikát nemá, takže není co dokazovat.
                    null, null,
                    null, // a protipodpis to není: razítko nepodepisuje podpisovou hodnotu ničeho
                    tsFailure,
                    dssRemarks(id, baseReport),
                    java.util.Map.of(),  // důvody 24 a 25 se u samostatného razítka nevydávají
                    tsw == null ? null : qualifiedOnlyFrom(tsw, baseReport),
                    tsCert != null && tsCert.isTrusted(),
                    null, null,  // důvody 2 a 6 se u samostatného razítka nevydávají
                    false        // situace 22 také ne
            ));
        }

        // Orphan signatures: detected by PDFBox but not processed by DSS (unsupported SubFilter).
        // Které to jsou, rozhodl {@link #orphanCards} ještě před hlavní smyčkou — týmž pravidlem,
        // podle kterého se počítalo pořadí prvků.
        for (OrphanSignature orphan : orphanCards) {
            String subFilter = orphan.subFilter() != null ? orphan.subFilter() : "NEZNÁMÝ";
            PdfSignatureAppearanceExtractor.Appearance orphanAppearance =
                    signatureAppearances.get(orphan.fieldName());
            byte[] appearancePng =
                    orphanAppearance != null ? orphanAppearance.png() : null;
            PdfFieldVisibility appearanceVisibility =
                    orphanAppearance != null ? orphanAppearance.visibility() : null;
            // Podpis adbe.x509.rsa_sha1 ověřil AnEP sám (Pkcs1SignatureValidator). Do 27. 9. 2026
            // tu stálo jen „nerozpoznaný druh autentizačního prvku“ bez certifikátu a s důvodem
            // „neznámý SubFilter“, ačkoli podpis nese vše, co ověření potřebuje (nález uživatele).
            Pkcs1SignatureValidator.Verification pv = orphan.verification();
            CertificateWrapper pvCert = pv != null ? pv.signingCert() : null;
            SignatureQualification pvQual = pv != null
                    ? signatureQualificationOf(pv.qualificationAtIssuance(), pv.indication())
                    : null;
            // Bajty certifikátu se berou z podpisu: diagnostická data z ověření samotného
            // certifikátu je nenesou (getBinaries() je null), a bez nich by kontrola QcStatements
            // i IK MPSV tiše vyšly naprázdno.
            byte[] pvDer = null;
            try {
                if (pv != null && pv.signature().signingCertificate() != null) {
                    pvDer = pv.signature().signingCertificate().getEncoded();
                }
            } catch (Exception ignored) { }
            SystemCertificate.Kind pvSysKind = systemCertificateKind(pvCert, pvDer);
            boolean pvSys = pvSysKind != SystemCertificate.Kind.NONE;
            boolean pvQualified = pvSys
                    ? pvSysKind == SystemCertificate.Kind.QUALIFIED_SYSTEM : isQualifiedSig(pvQual);
            String orphanLegalType = pv == null ? "nerozpoznaný druh autentizačního prvku"
                    : pvSys ? (pvQualified
                            ? "uznávanou elektronickou značku (dle zákona č. 227/2000 Sb.)"
                            : "elektronickou značku (dle zákona č. 227/2000 Sb.)")
                    : legalType(pvQual);
            CertFacts pvFacts = certFacts(pvCert, pvSys, pvQualified);
            List<Integer> orphanSituations = pv != null ? pv.situations() : List.of(19);
            List<Integer> orphanReasons = pv != null
                    ? detectReasons(orphanSituations, settings) : List.of(17);
            String orphanSigner = pvCert != null && pvCert.getReadableCertificateName() != null
                    ? pvCert.getReadableCertificateName() : orphan.signerName();
            summaries.add(new SignatureSummary(
                    "orphan:" + orphan.fieldName(),
                    subFilter,
                    pv != null ? pv.indication() : "INDETERMINATE",
                    pv != null ? pv.subIndication() : null,
                    orphanSigner,
                    orphan.signingTime(),
                    pvQualified,
                    orphanLegalType,
                    pv != null ? pv.proofOfExistence() : null,
                    null,
                    false,
                    List.of(),
                    false,
                    false,  // decisiveSettingsMeasured — ústupky se u osiřelého podpisu neměří
                    pv != null && pv.signature().keySize() > 0 ? "RSA" : null,
                    pv != null && pv.signature().digestAlgorithm() != null
                            ? pv.signature().digestAlgorithm().replace("-", "") : null,
                    pvCert != null ? pvCert.getCertificateDN() : null,
                    List.of(),
                    false,
                    null,   // customTrustAnchorName — not applicable to orphan signatures
                    null,   // augmentationDeadline
                    null,   // unprovenRevocationDeadline
                    orphanSituations,
                    orphanReasons,
                    null,  // sha1Detail not applicable to orphan signatures
                    null,  // noRevocationDetail not applicable to orphan signatures
                    null,  // newerRevocationDetail not applicable to orphan signatures
                    null,  // crlSourceHelp not applicable to orphan signatures
                    null,  // expiredResponderDetail not applicable to orphan signatures
                    null,  // decisiveCustomTime not applicable to orphan signatures
                    null,  // laterRevokedDetail not applicable to orphan signatures
                    appearancePng,
                    appearanceVisibility,
                    IkMpsv.of(pvDer),
                    null, null, null,  // PDF signature dictionary fields not applicable to orphan signatures
                    false,  // usedCustomValidationTime not applicable to orphan signatures
                    null,   // certificationPermission not applicable to orphan signatures
                    pvFacts.kind(), pvFacts.qscd(), pvFacts.issuerDN(),
                    pvFacts.notBefore(), pvFacts.notAfter(), pvFacts.revoked(), pvFacts.revokedAt(),
                    pvFacts.serialNumber(), pvFacts.selfSigned(),
                    pvFacts.qcCompliant(), pvFacts.qcTypeDescriptions(),
                    null, null, java.util.List.of(), false,  // s33* not applicable to orphan signatures
                    null, java.util.List.of(),  // s45* not applicable to orphan signatures
                    null,  // s34FailReason not applicable to orphan signatures
                    signingTools.get(orphan.fieldName()),
                    null,  // validationCourse not applicable to orphan signatures
                    List.of(),  // DSS never saw this signature, so nothing links a record to it
                    null, // withdrawnTsaDetail not applicable to orphan signatures
                    null, // ani proofCarrierDetail
                    null, // ani situace 51 — DSS ten podpis nikdy neviděl
                    null, // ani zámek: ten se čte z PDF revize, kterou DSS u osiřelého podpisu nemá
                    null, // ani situace 54 — bez rozsahu bajtů od DSS se pokrytí nedá určit
                    null, // a tedy ani razítko krycího prvku
                    null, // protipodpis deklaruje formát, a ten DSS u osiřelého podpisu nepřečetlo
                    null, // ani příčina selhání razítka: osiřelý prvek razítko není
                    List.of(),  // ani připomínky DSS: ten podpis knihovna vůbec nezpracovala
                    java.util.Map.of(),  // a tedy ani důvody, které by certifikát jmenovaly
                    null, false,  // ani údaje o razítkovací službě: prvek je osiřelý podpis
                    null, null,   // ani data u důvodů 2 a 6: ty se tu nevydávají
                    false         // ani situace 22
            ));
        }

        // Algoritmus nejnovějšího kryjícího razítka — viz SignatureSummary.TimestampKey.
        eu.europa.esig.dss.model.policy.ValidationPolicy cryptoPolicy = null;
        try {
            if (settings != null) cryptoPolicy = ValidationPolicyFactory.build(settings);
        } catch (Exception e) {
            // Bez politiky se údaj nedoplní a Digitální kontinuita mluví jako dřív.
        }
        if (cryptoPolicy != null) {
            for (SignatureSummary s : summaries) {
                s.setNewestTimestampKey(newestTimestampKey(s.getSignatureId(), dd, cryptoPolicy));
            }
        }
        // Seznam, podle něhož se certifikát prvku posuzoval — viz SignatureSummary.TrustListRef.
        for (SignatureSummary s : summaries) {
            s.setTrustList(trustListOf(s.getSignatureId(), dd));
        }

        int afterAll = baseReport.getSignatureIdList().size() * 2;
        summaries.sort(Comparator
                .comparingInt((SignatureSummary s) -> docPos.getOrDefault(s.getSignatureId(), afterAll))
                .thenComparing(SignatureSummary::getSigningTime,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        return new ValidationResult(
                fileName,
                detectedContainerType,
                hasExternalSig,
                summaries,
                baseReports.getXmlSimpleReport(),
                baseReports.getXmlDetailedReport(),
                dd,
                mapEvidenceRecords(unattachedEvidenceRecords(dd), null, baseReport, fileName)
        );
    }

    // ---------------------------------------------------------------------
    // Evidence records
    // ---------------------------------------------------------------------

    /**
     * Evidence records that cover no signature at all — a preservation container holding only
     * data, or a record handed in on its own. Everything else is reported on the signature it
     * covers, so a record never shows up in both places.
     */
    private static List<EvidenceRecordWrapper> unattachedEvidenceRecords(DiagnosticData dd) {
        List<EvidenceRecordWrapper> result = new ArrayList<>();
        for (EvidenceRecordWrapper er : dd.getEvidenceRecords()) {
            if (er.getCoveredSignatures().isEmpty()) result.add(er);
        }
        return result;
    }

    /**
     * @param sigId when non-null, keeps only the records covering that signature; when null,
     *              maps every record given.
     */
    private static List<EvidenceRecordEntry> mapEvidenceRecords(
            List<EvidenceRecordWrapper> records, String sigId, SimpleReport simpleReport,
            String verifiedFileName) {
        if (records == null || records.isEmpty()) return List.of();
        List<EvidenceRecordEntry> result = new ArrayList<>();
        for (EvidenceRecordWrapper er : records) {
            if (sigId != null && er.getCoveredSignatures().stream()
                    .noneMatch(s -> sigId.equals(s.getId()))) {
                continue;
            }
            result.add(mapEvidenceRecord(er, simpleReport, verifiedFileName));
        }
        result.sort(Comparator.comparing(EvidenceRecordEntry::earliestProof,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    private static EvidenceRecordEntry mapEvidenceRecord(EvidenceRecordWrapper er,
                                                          SimpleReport simpleReport,
                                                          String verifiedFileName) {
        List<TimestampEntry> timestamps = new ArrayList<>();
        for (TimestampWrapper ts : er.getTimestampList()) {
            CertificateWrapper tsaCert = ts.getSigningCertificate();
            timestamps.add(new TimestampEntry(
                    ts.getProductionTime() != null ? ts.getProductionTime().toInstant() : null,
                    ts.isSignatureValid() && ts.isMessageImprintDataIntact(),
                    tsaCert != null ? tsaCert.getReadableCertificateName() : null,
                    tsaCert != null && tsaCert.getNotAfter() != null
                            ? tsaCert.getNotAfter().toInstant() : null,
                    erTsKind(ts),
                    simpleReport.getTimestampQualification(ts.getId())
                            == TimestampQualification.QTSA,
                    ts.isSignatureIntact() && ts.isSignatureValid()
                            && ts.isMessageImprintDataIntact(),
                    // Bez příčiny zůstával řádek „platnost razítka se nepodařilo ověřit" u razítek
                    // v evidenčním záznamu bez závorky, kdežto u ostatních ji má — táž otázka,
                    // dvě různé odpovědi.
                    timestampFailure(ts, simpleReport),
                    timestampWeakDigest(ts),
                    timestampStatusWithdrawn(ts)));
        }
        timestamps.sort(Comparator.comparing(TimestampEntry::time,
                Comparator.nullsLast(Comparator.naturalOrder())));

        int total = er.getDigestMatchers().size();
        int intact = (int) er.getDigestMatchers().stream()
                .filter(m -> m.isDataFound() && m.isDataIntact()).count();
        // A digest in the record with no document behind it is reported by DSS as an orphan
        // reference. That means "not supplied for checking", not "altered" — for a group record
        // shown only some of its documents it is the expected state, not a defect.
        int missing = (int) er.getDigestMatchers().stream()
                .filter(m -> !m.isDataFound()).count();

        List<String> covered = new ArrayList<>();
        // Substituting the verified file's name for a scope description only holds when the
        // record actually matched something. With nothing matched, the record does not cover
        // that file at all, and naming it here would assert the opposite of the verdict.
        String substitute = intact > 0 ? verifiedFileName : null;
        for (SignerDataWrapper sd : er.getCoveredSignedData()) {
            String name = normalizeCoveredName(sd.getReferencedName(), substitute);
            if (name != null && !covered.contains(name)) covered.add(name);
        }

        // The record's own verdict lives under the evidence-record token in the simple report;
        // for a record embedded in a signature there may be no such top-level token at all.
        Indication indication = null;
        SubIndication subIndication = null;
        try {
            indication = simpleReport.getIndication(er.getId());
            subIndication = simpleReport.getSubIndication(er.getId());
        } catch (Exception ignored) {
            // No entry for this record — leave the verdict unstated rather than inventing one.
        }

        return new EvidenceRecordEntry(
                er.getId(),
                er.getFilename(),
                erTypeLabel(er),
                erOriginLabel(er),
                normalizeTokenIndication(indication),
                subIndication != null ? String.valueOf(subIndication) : null,
                er.isStructuralValidationValid(),
                er.getStructuralValidationMessages(),
                timestamps,
                covered,
                total,
                intact,
                missing,
                earliestValidTimestamp(timestamps));
    }

    /**
     * DSS names the object it could not name after a file: when a covered object is the whole
     * document being verified, the "referenced name" is a description of the scope — "Full PDF"
     * for a PDF, "Full document" elsewhere — not a filename. Listing those verbatim among real
     * filenames reads as a stray entry, so they are replaced by the name of the file actually
     * verified, which is what they stand for.
     *
     * @return the display name, or null when there is nothing sensible to show
     */
    private static String normalizeCoveredName(String referencedName, String verifiedFileName) {
        if (referencedName == null) return null;
        String trimmed = referencedName.trim();
        // "Partial PDF" belongs to the same family: it is what DSS calls a byte range covering
        // part of the document, i.e. still the file being verified, not a second file.
        if ("Full PDF".equals(trimmed) || "Full document".equals(trimmed)
                || "Partial PDF".equals(trimmed)) {
            return verifiedFileName;  // may be null; caller skips it then
        }
        return referencedName;
    }

    /**
     * Earliest moment the record actually proves, which is the earliest time-stamp that itself
     * holds up. Mirrors {@code earliestValidTimestampTime} used for signatures: a time-stamp
     * whose signature is broken or whose imprint no longer matches anchors nothing, and taking
     * its production time anyway would hand the reader a proof that does not exist.
     */
    /**
     * Nejstarší okamžik, k němuž existenci prvku dokládá evidenční záznam, který ho kryje.
     *
     * <p>Záznam se počítá jen tehdy, když kryje neporušená data — aspoň jeden otisk souhlasí
     * a žádný dodaný nesouhlasí — a z jeho razítek jen ta, která DSS ověřilo jako
     * {@code PASSED}. Týž práh jako u razítek prvku v {@link #earliestValidTimestampTime}:
     * kryptograficky neporušené razítko od služby, které nelze důvěřovat, nic nedokládá.
     */
    private static Instant earliestEvidenceRecordProof(String sigId, DiagnosticData dd,
                                                       SimpleReport simpleReport) {
        List<EvidenceRecordWrapper> records = dd.getEvidenceRecords();
        if (records == null) return null;
        Instant best = null;
        for (EvidenceRecordWrapper er : records) {
            if (er.getCoveredSignatures().stream().noneMatch(s -> sigId.equals(s.getId()))) {
                continue;
            }
            boolean intact = er.getDigestMatchers().stream()
                    .anyMatch(m -> m.isDataFound() && m.isDataIntact());
            boolean broken = er.getDigestMatchers().stream()
                    .anyMatch(m -> m.isDataFound() && !m.isDataIntact());
            if (!intact || broken) continue;
            for (TimestampWrapper ts : er.getTimestampList()) {
                if (ts.getProductionTime() == null
                        || simpleReport.getIndication(ts.getId()) != Indication.PASSED) {
                    continue;
                }
                Instant t = ts.getProductionTime().toInstant();
                if (best == null || t.isBefore(best)) best = t;
            }
        }
        return best;
    }

    private static Instant earliestValidTimestamp(List<TimestampEntry> timestamps) {
        return timestamps.stream()
                .filter(TimestampEntry::passed)
                .map(TimestampEntry::time)
                .filter(java.util.Objects::nonNull)
                .min(java.util.Comparator.naturalOrder())
                .orElse(null);
    }

    /**
     * DSS reports evidence records and standalone time-stamps with bare PASSED/FAILED, while
     * the rest of AnEP only recognises the TOTAL_* forms used for signatures.
     */
    private static String normalizeTokenIndication(Indication indication) {
        if (indication == null) return null;
        return switch (indication) {
            case PASSED -> String.valueOf(Indication.TOTAL_PASSED);
            case FAILED -> String.valueOf(Indication.TOTAL_FAILED);
            default -> String.valueOf(indication);
        };
    }

    private static String erTypeLabel(EvidenceRecordWrapper er) {
        if (er.getEvidenceRecordType() == null) return "evidenční záznam";
        return switch (er.getEvidenceRecordType()) {
            case XML_EVIDENCE_RECORD  -> "XMLERS (RFC 6283)";
            case ASN1_EVIDENCE_RECORD -> "ERS (RFC 4998)";
        };
    }

    private static String erOriginLabel(EvidenceRecordWrapper er) {
        if (er.getOrigin() == null) return null;
        return switch (er.getOrigin()) {
            case SIGNATURE -> "vložen v podpisu";
            case CONTAINER -> "v kontejneru";
            case EXTERNAL  -> "samostatný soubor";
        };
    }

    /**
     * Distinguishes the three roles a time-stamp plays inside an evidence record. A renewal is
     * not just another archive time-stamp: a time-stamp renewal re-anchors the existing hash
     * tree before the TSA's certificate expires, while a hash-tree renewal rebuilds the tree
     * with a stronger digest algorithm. Which one happened tells the reader what was at risk.
     */
    private static String erTsKind(TimestampWrapper ts) {
        if (ts.getEvidenceRecordTimestampType() == null) return "er";
        return switch (ts.getEvidenceRecordTimestampType()) {
            case ARCHIVE_TIMESTAMP -> "er";
            case TIMESTAMP_RENEWAL_ARCHIVE_TIMESTAMP -> "er_ts_renewal";
            case HASH_TREE_RENEWAL_ARCHIVE_TIMESTAMP -> "er_hashtree_renewal";
        };
    }

    /**
     * The relaxed settings without which this signature would not have been accepted.
     *
     * <p>Only re-running the validation can answer that, and {@code requiredRelaxations} carries
     * the result: for each signature, the tolerances it genuinely could not do without. Without
     * that evidence this method infers, and infers too generously — it names a setting whenever it
     * is enabled and the document holds anything the setting could bear on. For a re-stamped ISDS
     * message that announced "post-expiry revocation data" while the verdict in fact rested on an
     * ordinary, in-date CRL. The javadoc called such names "likely decisive"; the status bar
     * presented them as fact.
     *
     * <p>The inference below is kept for signatures the measurement did not cover — a validation
     * that threw, or one nobody asked about because no tolerance was enabled.
     */
    private List<String> computeDecisiveSettings(String id, SimpleReport sr, DiagnosticData dd,
                                                   AppSettings settings,
                                                   java.util.Map<String, RequiredRelaxations> requiredRelaxations) {
        if (sr.getIndication(id) != Indication.TOTAL_PASSED) return List.of();
        if (requiredRelaxations != null && requiredRelaxations.containsKey(id)) {
            java.util.Set<Relaxation> measuredSet = requiredRelaxations.get(id).settings();
            List<String> measured = new ArrayList<>();
            for (Relaxation r : Relaxation.values()) {
                if (measuredSet.contains(r)) measured.add(r.settingName());
            }
            return List.copyOf(measured);
        }

        List<String> result = new ArrayList<>();

        // Accept newer OCSP: decisive whenever some OCSP in the signing or TSA cert chain was
        // produced (thisUpdate) after the certificate it covers had already expired (notAfter).
        // For LTA signatures the "post-expiry" OCSP is often in a TSA cert chain, not the
        // signing cert chain, so both must be checked.
        if (settings.isAcceptNewerOcsp()) {
            SignatureWrapper sw = dd.getSignatureById(id);
            if (sw != null && hasNewerRevocationAnywhere(sw, dd, settings.getValidationDate())) {
                result.add(Relaxation.NEWER_OCSP.settingName());
            }
        }

        // Accept expired revocation sources: decisive when any embedded revocation token
        // in the signature's chain was issued by an OCSP responder or CRL issuer whose
        // own signing certificate has since expired.
        if (settings.isAcceptExpiredRevocationSources()) {
            SignatureWrapper sw = dd.getSignatureById(id);
            if (sw != null && hasExpiredRevocationSourceAnywhere(sw, dd)) {
                result.add(Relaxation.EXPIRED_REVOCATION_SOURCES.settingName());
            }
        }

        // Ignore SHA1 deprecated: check signature digest and cert chain digest algorithms
        if (settings.isIgnoreSha1Deprecated()) {
            SignatureWrapper sw = dd.getSignatureById(id);
            if (sw != null && usesSha1(sw, dd)) {
                result.add(Relaxation.SHA1.settingName());
            }
        }

        // Accept PDF-NOT-ETSI: decisive when the signature format is PDF-NOT-ETSI
        if (settings.isAcceptPdfNotEtsi()) {
            SignatureWrapper sw = dd.getSignatureById(id);
            if (sw != null && SignatureLevel.PDF_NOT_ETSI.equals(sw.getSignatureFormat())) {
                result.add(Relaxation.PDF_NOT_ETSI.settingName());
            }
        }

        return List.copyOf(result);
    }

    /** True when any certificate in the signing or TSA chain has revocation data (OCSP, or a CRL
     *  with expiredCertsOnCRL) whose thisUpdate is after the certificate's notAfter, or is stale
     *  for an expired cert — the condition relaxed by the
     *  "Využij i revokační informace, vydané po expiraci certifikátu" setting.
     *  A CRL issued after cert expiry without expiredCertsOnCRL is excluded: enabling the setting
     *  cannot fix it because LenientRevocationDataVerifier.isConsistent() still rejects such CRLs. */
    private boolean hasNewerRevocationAnywhere(SignatureWrapper sw, DiagnosticData dd,
                                                java.util.Date controlTime) {
        return hasNewerRevocationAnywhere(sw, dd, controlTime, ChainScope.ALL);
    }

    private boolean hasNewerRevocationAnywhere(SignatureWrapper sw, DiagnosticData dd,
                                                java.util.Date controlTime, ChainScope scope) {
        if (scope == ChainScope.ALL) {
            for (CertificateWrapper cw : sw.getCertificateChain()) {
                if (certHasNewerRevocation(cw, controlTime)) return true;
            }
        }
        for (TimestampWrapper ts : sw.getTimestampList()) {
            for (CertificateWrapper cw : ts.getCertificateChain()) {
                if (certHasNewerRevocation(cw, controlTime)) return true;
            }
        }
        for (TimestampWrapper ts : dd.getTimestampList()) {
            TimestampType tsType = ts.getType();
            if (!tsType.isDocumentTimestamp() && !tsType.isContainerTimestamp()) continue;
            if (ts.getTimestampedSignatures().stream().noneMatch(s -> sw.getId().equals(s.getId()))) continue;
            for (CertificateWrapper cw : ts.getCertificateChain()) {
                if (certHasNewerRevocation(cw, controlTime)) return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} when any embedded revocation token (OCSP or CRL) in the signature's
     * revocation pool or in any of its timestamps was issued by an entity whose own signing
     * certificate has since expired. This is the condition relaxed by the
     * "Využij i revokační informace od již expirovaného vydavatele" setting.
     */
    private boolean hasExpiredRevocationSourceAnywhere(SignatureWrapper sw, DiagnosticData dd) {
        java.util.Date now = new java.util.Date();
        if (revocationsHaveExpiredSource(sw.foundRevocations().getRelatedRevocationData(), now)) return true;
        for (TimestampWrapper ts : sw.getTimestampList()) {
            if (revocationsHaveExpiredSource(ts.foundRevocations().getRelatedRevocationData(), now)) return true;
        }
        for (TimestampWrapper ts : dd.getTimestampList()) {
            TimestampType tsType = ts.getType();
            if (!tsType.isDocumentTimestamp() && !tsType.isContainerTimestamp()) continue;
            if (ts.getTimestampedSignatures().stream().noneMatch(s -> sw.getId().equals(s.getId()))) continue;
            if (revocationsHaveExpiredSource(ts.foundRevocations().getRelatedRevocationData(), now)) return true;
        }
        return false;
    }

    private static boolean revocationsHaveExpiredSource(
            List<RelatedRevocationWrapper> revocations, java.util.Date now) {
        for (RelatedRevocationWrapper rrw : revocations) {
            CertificateWrapper sigCert = rrw.getSigningCertificate();
            if (sigCert == null) continue;
            if (sigCert.isTrusted()) continue;
            java.util.Date notAfter = sigCert.getNotAfter();
            if (notAfter != null && notAfter.before(now)) return true;
        }
        return false;
    }

    private static boolean certHasNewerRevocation(CertificateWrapper cw,
                                                   java.util.Date controlTime) {
        if (cw.getNotAfter() == null) return false;
        java.util.Date now = new java.util.Date();
        if (!cw.getNotAfter().before(now)) return false;
        List<CertificateRevocationWrapper> revocations = cw.getCertificateRevocationData();
        // If any revocation token was issued while the certificate was still valid
        // (thisUpdate ≤ certNotAfter), that embedded evidence is the correct historical
        // anchor for the signature.  Case B must not fire in this situation: every such
        // CRL will have a stale nextUpdate years later, and triggering S6 would falsely
        // suggest that enabling "use post-expiry revocation" helps when in reality the
        // failure is unrelated to revocation (e.g. SHA-1 algorithm constraint).
        boolean hasWithinValidityRevocation = revocations.stream()
                .anyMatch(r -> r.getThisUpdate() != null
                        && !r.getThisUpdate().after(cw.getNotAfter()));
        for (CertificateRevocationWrapper rev : revocations) {
            // A CRL issued after the cert's notAfter is only authoritative when it carries
            // expiredCertsOnCRL ≤ cert.notAfter. Without it the CRL makes no statement about
            // this expired cert, so enabling "acceptNewerOcsp" cannot fix the problem.
            // LenientRevocationDataVerifier.isConsistent() enforces the same condition.
            if (rev.getRevocationType() == RevocationType.CRL
                    && rev.getThisUpdate() != null
                    && rev.getThisUpdate().after(cw.getNotAfter())) {
                java.util.Date eco = rev.getExpiredCertsOnCRL();
                if (eco == null || eco.after(cw.getNotAfter())) continue;
            }
            // Case A: OCSP, or CRL with valid expiredCertsOnCRL, issued after notAfter.
            if (rev.getThisUpdate() != null && rev.getThisUpdate().after(cw.getNotAfter())) {
                // OCSP od respondéra, jehož certifikát k rozhodnému okamžiku neplatil, je jiný
                // případ: pouští ho acceptExpiredRevocationSources, ne acceptNewerOcsp, a S6 by
                // tedy radila zaškrtnutí, které s výsledkem nehne.
                //
                // Měří se to na rozsah platnosti, ne jen na jeho konec, a k rozhodnému okamžiku,
                // ne k dnešku. Constraint v DSS (RevocationIssuerNotExpired, chyba
                // BBB_XCV_ICTIVRCIRI_ANS) je rozsahový a při ověřování ke dni v minulosti padá
                // skoro vždy na spodní mez: odpověď staženou dnes podepsal respondér, který tehdy
                // ještě vydaný nebyl. Původní podoba testu na to nedosáhla a S6 v režimu ověřování
                // k datu radila volbu, která prokazatelně nepomůže — změřeno na tom případu.
                if (rev.getRevocationType() == RevocationType.OCSP) {
                    Instant at = (controlTime != null ? controlTime : now).toInstant();
                    Instant respExpiry    = chainExpiry(rev.getCertificateChain());
                    Instant respNotBefore = chainNotBefore(rev.getCertificateChain());
                    boolean outsideRange = (respExpiry != null && respExpiry.isBefore(at))
                            || (respNotBefore != null && respNotBefore.isAfter(at));
                    if (outsideRange) continue;
                }
                return true;
            }
            // Case B: stale-nextUpdate heuristic — infers that a newer online CRL was
            // rejected in strict mode (it would not appear in the diagnostic data).
            // Only applies when there is NO within-validity revocation: if embedded data
            // already covers the cert's active period the suggestion to enable lenient
            // revocation cannot improve the overall validation outcome.
            if (!hasWithinValidityRevocation
                    && rev.getNextUpdate() != null
                    && rev.getNextUpdate().before(now)) {
                return true;
            }
        }
        return false;
    }


    /**
     * Returns {@code true} when the signing certificate's only embedded revocation was
     * produced by a respondent/CRL-issuer whose certificate chain had ALREADY EXPIRED at
     * production time ({@code chainExpiry(respondent) < rev.thisUpdate}), AND no alternative
     * trustworthy revocation (non-expired-at-production embedded, or external with
     * {@code thisUpdate ≤ cert.notAfter}) independently justifies the TOTAL_PASSED conclusion.
     *
     * <p>Key distinction from the "currently expired respondent" check: if the respondent's
     * cert was still valid when the OCSP was produced, the historical proof is legitimate even
     * after the respondent cert eventually expires.  S29 only targets OCSPs/CRLs produced
     * while the respondent/issuer cert was <em>already</em> expired.</p>
     */
    private static boolean hasOnlyExpiredAtProductionSourceRevocationForSigningCert(SignatureWrapper sw) {
        CertificateWrapper signingCert = sw.getSigningCertificate();
        if (signingCert == null || signingCert.isTrusted()) return false;
        List<CertificateRevocationWrapper> revData = signingCert.getCertificateRevocationData();
        if (revData.isEmpty()) return false;
        boolean hasEmbeddedExpiredAtProduction = false;
        for (CertificateRevocationWrapper rev : revData) {
            if (rev.isInternalRevocationOrigin()) {
                // Signed by a trust anchor: its own notAfter says nothing about trustworthiness.
                if (isTrustedRevocationIssuer(rev)) return false;
                Instant respExpiry = chainExpiry(rev.getCertificateChain());
                if (respExpiry == null) return false; // can't determine chain expiry → be conservative
                Instant thisUpdate = rev.getThisUpdate() != null
                        ? rev.getThisUpdate().toInstant() : null;
                if (thisUpdate == null || !respExpiry.isBefore(thisUpdate)) {
                    // Respondent was valid at production time — historical proof is legitimate
                    return false;
                }
                hasEmbeddedExpiredAtProduction = true;
            } else {
                // External (online / custom store): within-validity thisUpdate independently
                // justifies TOTAL_PASSED without relying on the embedded expired-source data.
                java.util.Date certNotAfter = signingCert.getNotAfter();
                if (certNotAfter != null && rev.getThisUpdate() != null
                        && !rev.getThisUpdate().after(certNotAfter)) {
                    return false;
                }
            }
        }
        return hasEmbeddedExpiredAtProduction;
    }

    /**
     * Returns {@code true} when the verdict rests on a trust anchor the user pinned in
     * {@link CustomTrustStore} rather than on one the European lists supply.
     *
     * <p>Every chain the conclusion depends on is examined, not only the signature's: a time-stamp
     * anchored solely by a pinned root carries the verdict just as much, and until this looked at
     * them a document could come out valid, with the signature's own chain reaching EUTL quite
     * legitimately, while the time-stamp that made the moment provable was anchored only by the
     * user's own store — and nothing said so. Measured on a PAdES-LTA from 2012 whose 2016
     * time-stamp is anchored by a pinned ACAeID2 root: without that root the reference validator
     * reaches INDETERMINATE, with it the signature passes, and the note was silent.
     *
     * <p>Judged per chain, because the answer differs between them and an EUTL-backed signature
     * chain must not vouch for a time-stamp chain that has no such backing. Only time-stamps DSS
     * accepted are consulted; one it rejected contributes nothing to the verdict and mentioning it
     * would be noise.
     *
     * <p>A root may legitimately sit in both EUTL and the custom store (e.g. after "Vložit
     * certifikáty komerčních autorit"). Where EUTL independently reaches trust in that same chain,
     * crediting the custom store would be misleading, so that chain does not count.
     */
    private String customTrustAnchorName(String id, DiagnosticData dd,
                                         SimpleReport simpleReport) {
        SignatureWrapper sw = dd.getSignatureById(id);
        if (sw == null) return null;
        List<CustomTrustStore.Entry> customCerts = CustomTrustStore.listCertificates();
        if (customCerts.isEmpty()) return null;
        String fromSignature = chainRestsOnCustomStore(sw.getCertificateChain(), customCerts);
        if (fromSignature != null) return fromSignature;
        for (TimestampWrapper ts : sw.getTimestampList()) {
            if (simpleReport.getIndication(ts.getId()) != Indication.PASSED) continue;
            String fromTimestamp = chainRestsOnCustomStore(ts.getCertificateChain(), customCerts);
            if (fromTimestamp != null) return fromTimestamp;
        }
        return null;
    }

    /**
     * The pinned certificate anchoring this chain, when nothing in EUTL anchors it, else null.
     * The name is returned rather than a flag so the reader can be told which certificate of their
     * own the verdict rests on.
     */
    private static String chainRestsOnCustomStore(List<CertificateWrapper> chain,
                                                  List<CustomTrustStore.Entry> customCerts) {
        java.util.Date now = new java.util.Date();
        String matchedName = null;
        boolean eutlAlsoReachesTrust = false;
        for (CertificateWrapper cw : chain) {
            if (!cw.isTrusted()) continue;
            // Check the WHOLE chain for an independent EUTL trust path — not just the cert
            // that happens to match the custom store — since the TL-recognised anchor and
            // the custom-store-matching cert may be different certificates in the same chain
            // (e.g. an intermediate added to the custom store, while the root above it is
            // separately reachable via EUTL).
            // Being listed is not the same as still being anchored by that listing. A service
            // whose sunset has passed is reached by the Trusted List and vouches for nothing
            // today, so a chain that hangs on it is carried by the pinned root after all. This is
            // the shape of the 2012 PAdES-LTA: its 2016 time-stamp's TSU is in the list with a
            // sunset of 1 July 2016, and reading isTrustedListReached() alone credited EUTL for
            // trust that only the user's own store supplies.
            if (cw.isTrustedListReached()
                    && (cw.getTrustSunsetDate() == null || cw.getTrustSunsetDate().after(now))) {
                eutlAlsoReachesTrust = true;
            }
            if (matchedName != null) continue;
            byte[] der = cw.getBinaries();
            if (der == null) continue;
            for (CustomTrustStore.Entry entry : customCerts) {
                try {
                    if (java.util.Arrays.equals(der, entry.certificate().getCertificate().getEncoded())) {
                        matchedName = cw.getReadableCertificateName();
                        break;
                    }
                } catch (Exception ignored) {
                    // skip unparsable entry
                }
            }
        }
        return eutlAlsoReachesTrust ? null : matchedName;
    }

    private boolean usesSha1(SignatureWrapper sw, DiagnosticData dd) {
        // Signature message digest (hash of signed data)
        if (sw.getDigestAlgorithm() == DigestAlgorithm.SHA1) return true;
        // The digests of what is actually signed. In XAdES these are the ds:DigestMethod of each
        // ds:Reference, in CAdES the message-digest attribute; either way this is a separate
        // choice from the signature algorithm, and it is the one DSS's cryptographic constraints
        // judge. A signature can be RSA-SHA256 from end to end and still hash the signed files
        // with SHA-1 — measured on an ASiC-E from the Signer test set, where the verdict came out
        // CRYPTO_CONSTRAINTS_FAILURE_NO_POE with no situation on the card to account for it,
        // because only getDigestAlgorithm() was being read here.
        if (digestMatchersUse(sw, DigestAlgorithm.SHA1)) return true;
        // Signing certificate chain (certs signed with SHA1WithRSA etc.)
        if (chainUsesSha1(sw.getCertificateChain())) return true;
        // Signature-linked timestamps: message imprint digest and TSA cert chain
        for (TimestampWrapper ts : sw.getTimestampList()) {
            if (timestampUsesSha1(ts)) return true;
        }
        // Document/container timestamps that cover this signature but may not be in
        // sw.getTimestampList() (e.g. PAdES /DocTimeStamp at PDF level)
        for (TimestampWrapper ts : dd.getTimestampList()) {
            TimestampType tsType = ts.getType();
            if (!tsType.isDocumentTimestamp() && !tsType.isContainerTimestamp()) continue;
            boolean coversThisSig = ts.getTimestampedSignatures().stream()
                    .anyMatch(s -> sw.getId().equals(s.getId()));
            if (coversThisSig && timestampUsesSha1(ts)) return true;
        }
        return false;
    }

    /**
     * Měla razítkovací služba kladný status, když tohle razítko vzniklo, a nemá ho teď?
     *
     * <p>Táž první část úvahy jako {@link #withdrawnTimestampServiceDetail}, ale <b>bez</b> jeho
     * podmínek o tom, jestli to verdikt něco stálo. Ty tam patří: situace 43 má pojmenovat
     * příčinu nekladného výsledku, a kde nic nestála, byla by to zmínka o něčem jiném. Rozšířený
     * přehled se ale ptá na něco jiného — co je s <b>tímhle razítkem</b> — a tam ta otázka stát
     * má i tehdy, když o výsledku rozhodlo něco dalšího.
     *
     * <p>Není to konstanta v převleku. Naměřeno na
     * {@code priklad6_2_Razitko_do_03_08_2034.pdf}: razítko z 20. 1. 2012 vychází {@code true},
     * razítko z 2. 8. 2016 {@code false} — vzniklo až po sunsetu 1. 7. 2016, takže kladný status
     * nemělo ani v době svého vzniku.
     */
    private static boolean timestampStatusWithdrawn(TimestampWrapper ts) {
        CertificateWrapper tsa = ts.getSigningCertificate();
        if (tsa == null || ts.getProductionTime() == null) return false;
        java.util.Date made = ts.getProductionTime();
        java.util.Date now = new java.util.Date();
        boolean anyService = false, positiveThen = false, positiveNow = false;
        for (var tsp : tsa.getTrustServiceProviders()) {
            for (var svc : tsp.getTrustServices()) {
                String type = tail(svc.getServiceType());
                if (!"QTST".equalsIgnoreCase(type) && !"TSA/QTST".equalsIgnoreCase(type)) {
                    continue;
                }
                anyService = true;
                boolean positive = S43_POSITIVE_STATUSES.contains(
                        tail(svc.getStatus()).toLowerCase(java.util.Locale.ROOT));
                if (positive && covers(svc, made)) positiveThen = true;
                if (positive && covers(svc, now)) positiveNow = true;
            }
        }
        return anyService && positiveThen && !positiveNow;
    }

    /**
     * Jméno zastaralého otisku, na kterém razítko stojí, nebo {@code null}.
     *
     * <p>Odpovídá na jinou otázku než {@link #timestampFailure}: ta říká, co verdikt <b>drží</b>,
     * kdežto tohle říká, na čem razítko <b>stojí</b>. Rozcházejí se častěji, než by člověk čekal:
     * u {@code SHA1/PAdES LTA uznavany 2015 SHA1.pdf} dává DSS dílčí indikaci
     * {@code CRYPTO_CONSTRAINTS_FAILURE_NO_POE}, ale zdejší zařazení podle klíčů hlášení dává
     * přednost expiraci certifikátu autority — a čtenář by se o SHA-1 nedozvěděl, ačkoli je
     * razítko na ní postavené skrz naskrz (otisk i podpis tokenu).
     *
     * <p>Nejdřív otisk, který razítko kryje — to je vlastní volba razítkovací autority —, až pak
     * řetěz jejích certifikátů. Jmenovat obojí by řádek prodloužilo bez užitku: čtenáři stačí
     * vědět, že je ve hře zastaralý algoritmus, a který.
     */
    private static String timestampWeakDigest(TimestampWrapper ts) {
        eu.europa.esig.dss.diagnostic.jaxb.XmlDigestMatcher imprint = ts.getMessageImprint();
        if (imprint != null && imprint.getDigestMethod() != null
                && isWeak(imprint.getDigestMethod())) {
            return imprint.getDigestMethod().getName();
        }
        for (CertificateWrapper cw : ts.getCertificateChain()) {
            if (cw.getSignatureAlgorithm() == null) continue;
            DigestAlgorithm d = cw.getSignatureAlgorithm().getDigestAlgorithm();
            if (d != null && isWeak(d)) return d.getName();
        }
        return null;
    }

    /** Otisky, které už neplatí za spolehlivé — táž trojice, jakou hlídá situace 15 a 16. */
    private static boolean isWeak(DigestAlgorithm d) {
        return d == DigestAlgorithm.SHA1 || d == DigestAlgorithm.MD5
                || d == DigestAlgorithm.RIPEMD160;
    }

    private static boolean timestampUsesSha1(TimestampWrapper ts) {
        // Message imprint digest (the hash algorithm used by the TSA over the signed data)
        eu.europa.esig.dss.diagnostic.jaxb.XmlDigestMatcher imprint = ts.getMessageImprint();
        if (imprint != null && imprint.getDigestMethod() == DigestAlgorithm.SHA1) return true;
        // TSA certificate chain (certs signed with SHA1WithRSA etc.)
        return chainUsesSha1(ts.getCertificateChain());
    }

    private static boolean chainUsesSha1(List<CertificateWrapper> chain) {
        for (CertificateWrapper cw : chain) {
            if (cw.getSignatureAlgorithm() != null
                    && cw.getSignatureAlgorithm().getDigestAlgorithm() == DigestAlgorithm.SHA1) {
                return true;
            }
        }
        return false;
    }

    /** True when any of the signature's own references is hashed with {@code alg}. */
    private static boolean digestMatchersUse(SignatureWrapper sw, DigestAlgorithm alg) {
        return sw.getDigestMatchers().stream().anyMatch(dm -> dm.getDigestMethod() == alg);
    }

    /** Names what a digest matcher covers, for the reader who has to be told where SHA-1 sits. */
    private static String digestMatcherLabel(
            eu.europa.esig.dss.diagnostic.jaxb.XmlDigestMatcher dm) {
        String uri = dm.getUri();
        if (dm.getType() == DigestMatcherType.REFERENCE
                && uri != null && !uri.isBlank() && !uri.startsWith("#")) {
            return "podepsaného souboru „" + uri + "“";
        }
        return switch (dm.getType()) {
            case SIGNED_PROPERTIES, KEY_INFO, SIGNATURE_PROPERTIES
                    -> "podepsaných vlastností podpisu";
            case MANIFEST, MANIFEST_ENTRY -> "položky manifestu";
            default -> "podepsaných dat";
        };
    }

    private static final java.time.format.DateTimeFormatter SHA1_TS_FMT =
            java.time.format.DateTimeFormatter.ofPattern("d. M. yyyy H:mm:ss 'UTC'")
                    .withZone(java.time.ZoneOffset.UTC);

    private String buildSha1Detail(SignatureWrapper sw, DiagnosticData dd) {
        List<String> lines = new ArrayList<>();

        if (sw.getDigestAlgorithm() == DigestAlgorithm.SHA1) {
            lines.add("SHA1 byla použita jako hashovací funkce samotného podpisu (otisk podepisovaných dat)");
        }

        for (eu.europa.esig.dss.diagnostic.jaxb.XmlDigestMatcher dm : sw.getDigestMatchers()) {
            if (dm.getDigestMethod() != DigestAlgorithm.SHA1) continue;
            lines.add("SHA1 byla použita v otisku " + digestMatcherLabel(dm));
        }

        for (CertificateWrapper cw : sw.getCertificateChain()) {
            if (cw.getSignatureAlgorithm() != null
                    && cw.getSignatureAlgorithm().getDigestAlgorithm() == DigestAlgorithm.SHA1) {
                lines.add("SHA1 v podpisu certifikátu podpisového řetězu: "
                        + cw.getReadableCertificateName()
                        + " (" + cw.getSignatureAlgorithm().getName() + ")");
            }
        }

        java.util.Set<String> seenTsIds = sw.getTimestampList().stream()
                .map(TimestampWrapper::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<TimestampWrapper> allTs = new ArrayList<>(sw.getTimestampList());
        for (TimestampWrapper ts : dd.getTimestampList()) {
            if (seenTsIds.contains(ts.getId())) continue;
            TimestampType type = ts.getType();
            if (!type.isDocumentTimestamp() && !type.isContainerTimestamp()) continue;
            boolean covers = ts.getTimestampedSignatures().stream()
                    .anyMatch(s -> sw.getId().equals(s.getId()));
            if (covers) { allTs.add(ts); seenTsIds.add(ts.getId()); }
        }

        for (TimestampWrapper ts : allTs) {
            eu.europa.esig.dss.diagnostic.jaxb.XmlDigestMatcher imprint = ts.getMessageImprint();
            String tsLabel = tsCzechType(ts);
            String tsTime = ts.getProductionTime() != null
                    ? " (" + SHA1_TS_FMT.format(ts.getProductionTime().toInstant()) + ")" : "";
            if (imprint != null && imprint.getDigestMethod() == DigestAlgorithm.SHA1) {
                lines.add("SHA1 byla využita v otisku zprávy " + tsLabel + tsTime);
            }
            for (CertificateWrapper cw : ts.getCertificateChain()) {
                if (cw.getSignatureAlgorithm() != null
                        && cw.getSignatureAlgorithm().getDigestAlgorithm() == DigestAlgorithm.SHA1) {
                    lines.add("SHA1 v podpisu certifikátu TSA řetězu " + tsLabel + ": "
                            + cw.getReadableCertificateName()
                            + " (" + cw.getSignatureAlgorithm().getName() + ")");
                }
            }
        }

        if (lines.isEmpty()) return null;
        if (lines.size() == 1) return lines.get(0);
        return lines.stream().map(l -> "• " + l).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String tsCzechType(TimestampWrapper ts) {
        return switch (ts.getType()) {
            case SIGNATURE_TIMESTAMP       -> "podpisového časového razítka";
            case DOCUMENT_TIMESTAMP        -> "dokumentového časového razítka";
            case ARCHIVE_TIMESTAMP         -> "archivního časového razítka";
            case CONTENT_TIMESTAMP         -> "razítka obsahu";
            case VALIDATION_DATA_TIMESTAMP -> "razítka validačních dat";
            default -> "časového razítka (" + ts.getType().name().toLowerCase().replace('_', ' ') + ")";
        };
    }

    // Known CA patterns → [friendly label, URL].  Checked case-insensitively against the
    // issuer cert's subject DN and the cert's own DN (both may carry the CA name).
    private static final String[][] KNOWN_CA_CRL_LINKS = {
        { "i.ca",                    "I.CA — historické CRL seznamy",       "https://www.ica.cz/zneplatnit-certifikat#seznamy" },
        { "první certifikační",      "I.CA — historické CRL seznamy",       "https://www.ica.cz/zneplatnit-certifikat#seznamy" },
        { "prvni certifikacni",      "I.CA — historické CRL seznamy",       "https://www.ica.cz/zneplatnit-certifikat#seznamy" },
        { "postsignum",              "PostSignum — CRL seznamy",            "https://www.postsignum.cz/seznamy_zneplatnenych_certifikatu_crl.html" },
        { "česká pošta",             "PostSignum — CRL seznamy",            "https://www.postsignum.cz/seznamy_zneplatnenych_certifikatu_crl.html" },
        { "ceska posta",             "PostSignum — CRL seznamy",            "https://www.postsignum.cz/seznamy_zneplatnenych_certifikatu_crl.html" },
        { "eidentity",               "eIdentity — CRL seznamy",             "https://www.eidentity.cz/zadost-o-zneplatneni-certifikatu/#seznam" },
    };

    /**
     * Důvod 12. Certifikáty bez revokačních dat ukládá do {@code affectedOut} — vypíše je okno
     * „kde vzít CRL“ u rady pro zkušenější uživatele, společně s certifikáty důvodů 2 a 6.
     *
     * <p>Text vrací jen v jednom případě: když server odpověď dočasně odmítl. To je jiná rada než
     * shánět starý seznam — stačí ověření zopakovat — a nese ji odkaz u popisu problému.
     */
    private String buildNoRevocationDetail(String id, SignatureWrapper sw, DiagnosticData dd,
                                            eu.europa.esig.dss.simplereport.SimpleReport simpleReport,
                                            List<CertificateWrapper> affectedOut) {
        SubIndication si = simpleReport.getSubIndication(id);
        if (si == SubIndication.TRY_LATER) {
            return "Revokační server (OCSP) dočasně odmítl poskytnout odpověď — zkuste ověření zopakovat.";
        }

        List<List<CertificateWrapper>> allChains = new ArrayList<>();
        allChains.add(sw.getCertificateChain());
        for (TimestampWrapper ts : sw.getTimestampList()) {
            allChains.add(ts.getCertificateChain());
        }

        java.util.Set<String> seenCerts = new java.util.HashSet<>();
        for (List<CertificateWrapper> chain : allChains) {
            for (CertificateWrapper c : chain) {
                if (c.isTrusted() || c.isSelfSigned()) break;
                if (!c.getCertificateRevocationData().isEmpty() || !seenCerts.add(c.getId())) continue;
                if (affectedOut != null) affectedOut.add(c);
            }
        }
        return null;
    }

    /**
     * Blok jednoho certifikátu v okně „kde vzít CRL“: čí certifikát to je, kdo jej vydal a do
     * kdy musí být vydán seznam, který o něm ještě něco prokáže. Sdílejí jej oba důvody, které
     * na to okno odkazují — 12 (revokační údaj se nepodařilo získat vůbec) i 2 (získaný údaj
     * je vydán až po expiraci certifikátu). Nalezenou autoritu jen přidá do {@code caLinks},
     * aby odkazy na její stránky stály jednou pod celým výčtem.
     */
    private static void appendCrlGuidance(StringBuilder sb, CertificateWrapper c, String issuerDn,
                                          java.util.Map<String, String> caLinks) {
        if (sb.length() > 0) sb.append("\n\n");
        sb.append("Certifikát: ").append(c.getReadableCertificateName());

        String issuerName = dnCn(issuerDn);
        if (issuerName != null) sb.append("\nVydavatel (CA): ").append(issuerName);

        String notAfterStr = formatRevDateTime(c.getNotAfter());
        sb.append("\nPlatnost certifikátu do: ").append(notAfterStr);
        sb.append("\nPotřebné CRL musí být vydáno nejpozději: ").append(notAfterStr);
        sb.append("\n  — nebo: CRL vydané i po tomto datu, pokud obsahuje příponu expiredCertsOnCRL s hodnotou, která toto datum nepřekračuje");

        // Detect known CA from issuer DN and from the cert's own DN
        String searchIn = ((issuerDn != null ? issuerDn : "") + " "
                + (c.getCertificateDN() != null ? c.getCertificateDN() : "")).toLowerCase();
        for (String[] entry : KNOWN_CA_CRL_LINKS) {
            if (searchIn.contains(entry[0])) {
                caLinks.putIfAbsent(entry[2], entry[1]);
                break;
            }
        }
    }

    /** Závěr okna „kde vzít CRL“: stránky autorit, na kterých historické seznamy bývají. */
    private static void appendCaLinks(StringBuilder sb, java.util.Map<String, String> caLinks) {
        if (caLinks.isEmpty()) return;
        sb.append("\n\nKde stáhnout historické CRL:");
        for (java.util.Map.Entry<String, String> e : caLinks.entrySet()) {
            // LINK: lines are rendered as clickable hyperlinks in ResultView.
            sb.append("\nLINK:").append(e.getValue()).append("|").append(e.getKey());
        }
    }

    /**
     * Totéž okno pro důvody 2 a 6. Certifikáty si nevybírá sám — dostane právě ty, o kterých
     * {@link #buildNewerRevocationDetail} a {@link #buildExpiredResponderDetail} napsaly, že
     * s jejich revokačním údajem je něco v nepořádku. Kdyby si je hledal znovu podle týchž
     * podmínek, mohla by se okna rozejít. Týž certifikát bývá v obou seznamech — vypíše se jednou.
     */
    private static String buildCrlSourceHelp(List<CertificateWrapper> certs) {
        StringBuilder sb = new StringBuilder();
        java.util.Map<String, String> caLinks = new java.util.LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (CertificateWrapper c : certs) {
            if (!seen.add(c.getId())) continue;
            appendCrlGuidance(sb, c, c.getCertificateIssuerDN(), caLinks);
        }
        if (sb.length() == 0) return null;
        appendCaLinks(sb, caLinks);
        return sb.toString();
    }

    /** Extracts the CN attribute value from an LDAP-style DN string, or the whole DN as fallback. */
    private static String dnCn(String dn) {
        if (dn == null) return null;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(?i)(?:^|,\\s*)CN=([^,]+)").matcher(dn);
        return m.find() ? m.group(1).trim() : dn;
    }



    private static final java.time.ZoneId PRAGUE = java.time.ZoneId.of("Europe/Prague");
    private static final java.time.ZoneOffset CEST  = java.time.ZoneOffset.ofHours(2);

    /** Jeden zápis okamžiku pro celou aplikaci — viz {@link TimeFormat}. */
    private static String formatRevDateTime(java.util.Date date) {
        return TimeFormat.full(date);
    }

    /**
     * Dřív samotné datum, dnes týž plný zápis jako všude jinde. Jméno zůstává, aby se změna
     * formátu neutopila v přejmenování dvaceti volaných míst.
     */
    private static String formatRevDate(java.util.Date date) {
        return TimeFormat.full(date);
    }

    private static String revTypeName(RevocationType type) {
        if (type == RevocationType.OCSP) return "OCSP";
        if (type == RevocationType.CRL) return "CRL";
        return "Revokační data";
    }

    /**
     * Reason 2: which cert(s) have revocation data issued after their notAfter, and what type.
     *
     * <p>Certifikáty, o kterých to napíše, ukládá do {@code affectedOut} (smí být {@code null}).
     * Z nich se staví okno „kde vzít CRL“ — má vypisovat přesně tytéž a nehledat si je znovu.
     */
    private String buildNewerRevocationDetail(SignatureWrapper sw, DiagnosticData dd, Instant bst,
                                              List<CertificateWrapper> affectedOut) {
        List<String> lines = new ArrayList<>();
        java.util.Set<String> seenCertIds = new java.util.HashSet<>();
        java.util.Date now = new java.util.Date();

        List<List<CertificateWrapper>> chains = new ArrayList<>();
        chains.add(sw.getCertificateChain());
        for (TimestampWrapper ts : sw.getTimestampList()) chains.add(ts.getCertificateChain());
        for (TimestampWrapper ts : dd.getTimestampList()) {
            TimestampType tsType = ts.getType();
            if (!tsType.isDocumentTimestamp() && !tsType.isContainerTimestamp()) continue;
            if (ts.getTimestampedSignatures().stream().noneMatch(s -> sw.getId().equals(s.getId()))) continue;
            chains.add(ts.getCertificateChain());
        }

        for (List<CertificateWrapper> chain : chains) {
            for (CertificateWrapper cw : chain) {
                if (cw.isTrusted()) break;
                if (cw.getNotAfter() == null || !cw.getNotAfter().before(now)) continue;
                if (!seenCertIds.add(cw.getId())) continue;
                List<CertificateRevocationWrapper> revocations = cw.getCertificateRevocationData();
                boolean hasWithinValidity = revocations.stream().anyMatch(
                        r -> r.getThisUpdate() != null && !r.getThisUpdate().after(cw.getNotAfter()));
                for (CertificateRevocationWrapper rev : revocations) {
                    if (rev.getRevocationType() == RevocationType.CRL
                            && rev.getThisUpdate() != null
                            && rev.getThisUpdate().after(cw.getNotAfter())) {
                        java.util.Date eco = rev.getExpiredCertsOnCRL();
                        if (eco == null || eco.after(cw.getNotAfter())) continue;
                    }
                    String type = revTypeName(rev.getRevocationType());
                    String certName = cw.getReadableCertificateName();
                    if (rev.getThisUpdate() != null && rev.getThisUpdate().after(cw.getNotAfter())) {
                        lines.add(type + " pro certifikát " + certName
                                + " — vydáno " + formatRevDate(rev.getThisUpdate())
                                + ", certifikát platil do " + formatRevDate(cw.getNotAfter()));
                        if (affectedOut != null) affectedOut.add(cw);
                        break;
                    }
                    if (!hasWithinValidity && rev.getNextUpdate() != null && rev.getNextUpdate().before(now)) {
                        lines.add(type + " pro certifikát " + certName
                                + " — platnost revokačního záznamu vypršela " + formatRevDate(rev.getNextUpdate())
                                + " (certifikát platil do " + formatRevDate(cw.getNotAfter()) + ")");
                        if (affectedOut != null) affectedOut.add(cw);
                        break;
                    }
                }
            }
        }

        // When S21 fires, the newer OCSP also proves revocation before BST — add a note so
        // the user understands why the status is INDETERMINATE rather than TOTAL_FAILED.
        if (bst != null) {
            outer:
            for (CertificateWrapper cw : sw.getCertificateChain()) {
                if (cw.isTrusted()) break;
                if (cw.getNotAfter() == null) continue;
                for (CertificateRevocationWrapper rev : cw.getCertificateRevocationData()) {
                    if (rev.isInternalRevocationOrigin()) continue;
                    if (rev.getStatus() != CertificateStatus.REVOKED) continue;
                    if (rev.getThisUpdate() == null || !rev.getThisUpdate().after(cw.getNotAfter())) continue;
                    if (rev.getRevocationDate() == null) continue;
                    if (!rev.getRevocationDate().toInstant().isBefore(bst)) continue;
                    lines.add("Tato novější data navíc prokazují, že certifikát byl revokován dne "
                            + formatRevDate(rev.getRevocationDate())
                            + " — tedy ještě před zjištěným časem podpisu.");
                    break outer;
                }
            }
        }

        if (lines.isEmpty()) return null;
        if (lines.size() == 1) return lines.get(0);
        return lines.stream().map(l -> "• " + l).collect(java.util.stream.Collectors.joining("\n"));
    }

    /**
     * Reason 6: which cert(s) have embedded revocation from an expired respondér, and what type.
     *
     * <p>Dotčené certifikáty ukládá do {@code affectedOut} (smí být {@code null}) — stejně jako
     * {@link #buildNewerRevocationDetail}, protože z obou seznamů se skládá jedno okno
     * „kde vzít CRL“.
     */
    private String buildExpiredResponderDetail(SignatureWrapper sw,
                                               List<CertificateWrapper> affectedOut) {
        List<String> lines = new ArrayList<>();
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        Instant now = Instant.now();

        List<List<CertificateWrapper>> chains = new ArrayList<>();
        chains.add(sw.getCertificateChain());
        for (TimestampWrapper ts : sw.getTimestampList()) chains.add(ts.getCertificateChain());

        for (List<CertificateWrapper> chain : chains) {
            for (CertificateWrapper cw : chain) {
                if (cw.isTrusted()) break;
                for (CertificateRevocationWrapper rev : cw.getCertificateRevocationData()) {
                    if (!rev.isInternalRevocationOrigin()) continue;
                    Instant respExpiry = chainExpiry(rev.getCertificateChain());
                    if (respExpiry == null || !respExpiry.isBefore(now)) continue;
                    String key = cw.getId() + "|" + rev.getRevocationType();
                    if (!seenKeys.add(key)) continue;
                    String type = revTypeName(rev.getRevocationType());
                    String certName = cw.getReadableCertificateName();
                    String issuerName = rev.getCertificateChain().isEmpty() ? null
                            : rev.getCertificateChain().get(0).getReadableCertificateName();
                    String expiryStr = TimeFormat.full(respExpiry);
                    if (issuerName != null && !issuerName.equals(certName)) {
                        lines.add(type + " pro certifikát " + certName
                                + " od " + issuerName
                                + " — certifikát vydavatele expiroval " + expiryStr);
                    } else {
                        lines.add(type + " pro certifikát " + certName
                                + " — certifikát vydavatele expiroval " + expiryStr);
                    }
                    if (affectedOut != null) affectedOut.add(cw);
                }
            }
        }

        if (lines.isEmpty()) return null;
        if (lines.size() == 1) return lines.get(0);
        return lines.stream().map(l -> "• " + l).collect(java.util.stream.Collectors.joining("\n"));
    }

    // ── S33 helpers ──────────────────────────────────────────────────────────────────────────

    /**
     * @param postExpiry the list was published after the certificate it would cover had already
     *                   expired. Downloaded all the same: strict coverage (S33) turns it away,
     *                   the tolerant reading behind S45 accepts it.
     */
    private record FetchedCrl(String url, java.util.Date thisUpdate, java.util.Date nextUpdate,
                              byte[] crlBytes, boolean postExpiry) {}
    /**
     * @param detected    revocation data is missing somewhere and all of it can still be obtained
     * @param preservable the same, and every piece can be obtained <em>issued after the
     *                    best-signature-time</em> — which is what makes preservation worth
     *                    offering. A list or response issued before the signature existed says
     *                    nothing about it, so embedding it would produce an LT-looking document
     *                    resting on evidence that cannot support it. Always false without a
     *                    best-signature-time: at level B the only anchor is the present moment,
     *                    and nothing can be issued after now.
     * @param tolerantlyComplete situation 45: strict coverage fails, but every certificate that
     *                    lacks it is covered once post-expiry material and material from an
     *                    expired issuer are allowed to count. Never true together with
     *                    {@code detected} — the two readings partition the outcome, so the reader
     *                    is told coverage is complete <em>or</em> that it is complete only on the
     *                    looser reading, never both.
     * @param tolerantCrlBytes lists downloaded during the walk that only the looser reading
     *                    accepts. They travel separately from {@code crlBytes} because
     *                    uchování must never embed them — see {@code isCrlUseless()}.
     */
    private record S33Result(boolean detected, String detail, String missingDetail,
                             List<byte[]> crlBytes, boolean preservable,
                             boolean tolerantlyComplete, String tolerantDetail,
                             List<byte[]> tolerantCrlBytes) {

        /** Nothing known — no signature, no chains. */
        static S33Result none() {
            return new S33Result(false, null, null, List.of(), false, false, null, List.of());
        }
    }

    // ── S34 helpers ──────────────────────────────────────────────────────────────────────────

    private record S34Result(boolean detected, String failReason) {}

    /**
     * Computes S34 ("ověření bez ústupků") for a single signature.
     * Returns detected=true when no lenient setting is active, no custom validation date is set,
     * and no certificate from the custom trust store appears in any relevant cert chain.
     * When detected=false, failReason lists which sub-conditions were not met.
     */
    private S34Result computeS34(AppSettings settings, String signatureId,
                                 java.util.Map<String, RequiredRelaxations> requiredRelaxations) {
        List<String> reasons = new ArrayList<>();

        // A tolerance that was switched on but did not have to be used is not a concession this
        // signature was verified under, and where the measurement covered it, only the ones it
        // actually needed are named. Reading the enabled flags alone marked every signature in the
        // document as verified with concessions, which among other things withheld the
        // preservation offer from documents that needed no tolerance at all.
        //
        // The validation date below stays outside this: the re-runs repeat the tolerances, not the
        // clock, so they say nothing about a verdict reached at a time of the user's choosing.
        RequiredRelaxations measuredEntry = requiredRelaxations != null
                ? requiredRelaxations.get(signatureId) : null;
        java.util.Set<Relaxation> measured =
                measuredEntry != null ? measuredEntry.settings() : null;
        if (measured != null) {
            for (Relaxation r : Relaxation.values()) {
                if (measured.contains(r)) reasons.add(r.concessionName());
            }
            if (settings.getValidationDate() != null) {
                reasons.add("explicitně zadáno datum ověření");
            }
            if (reasons.isEmpty()) return new S34Result(true, null);
            return new S34Result(false, String.join("; ", reasons));
        }

        if (settings.isAcceptNewerOcsp())
            reasons.add(Relaxation.NEWER_OCSP.concessionName());
        if (settings.isAcceptExpiredRevocationSources())
            reasons.add(Relaxation.EXPIRED_REVOCATION_SOURCES.concessionName());
        if (settings.isIgnoreSha1Deprecated())
            reasons.add(Relaxation.SHA1.concessionName());
        // The PDF-NOT-ETSI tolerance was missing here, and it is the concession most likely to
        // decide a verdict on its own: it relaxes the SAV constraint over signed attributes that
        // PAdES requires and a plain PDF signature never carries. A document could therefore come
        // out valid *and* labelled "ověřeno bez ústupků", which is a contradiction — and an
        // expensive one, because that label is exactly what invites the reader to expect a
        // conformant validator to agree. It will not: no setting of ours travels with the file,
        // and no amount of preservation can add attributes the signer never included.
        if (settings.isAcceptPdfNotEtsi())
            reasons.add(Relaxation.PDF_NOT_ETSI.concessionName());
        if (settings.getValidationDate() != null)
            reasons.add("explicitně zadáno datum ověření");

        if (reasons.isEmpty()) return new S34Result(true, null);
        return new S34Result(false, String.join("; ", reasons));
    }

    /** Downloads raw bytes from a URL with an 8 s connect / 12 s read timeout. */
    private static byte[] downloadBytes(String urlStr) {
        try {
            java.net.URLConnection conn = new java.net.URI(urlStr).toURL().openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(12_000);
            conn.setRequestProperty("User-Agent", "AnEP/1.0 CRL-Check");
            try (java.io.InputStream is = conn.getInputStream()) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Attempts to download a CRL from the cert's CDP URLs. Returns null when unavailable or invalid.
     *
     * <p>A list published after the certificate expired is returned too, flagged {@code postExpiry},
     * rather than discarded as it once was. Strict coverage still rejects it — a CA may drop entries
     * for expired certificates (RFC 5280 clause 3.3), so absence from such a list states nothing —
     * but the validators situation 45 is about accept it, and re-stamping has to be able to embed
     * the bytes. A list from within the certificate's lifetime always wins: the loop keeps looking
     * and only falls back to the post-expiry one when no CDP offers better.
     */
    private static FetchedCrl tryFetchCrlFromCdp(CertificateWrapper cert) {
        java.util.Date notAfter = cert.getNotAfter();
        if (notAfter == null) return null;
        List<String> cdpUrls = cert.getCRLDistributionPoints();
        if (cdpUrls == null || cdpUrls.isEmpty()) return null;
        FetchedCrl postExpiryFallback = null;
        for (String urlStr : cdpUrls) {
            try {
                byte[] bytes = downloadBytes(urlStr);
                if (bytes == null || bytes.length == 0) continue;
                java.security.cert.CertificateFactory cf =
                        java.security.cert.CertificateFactory.getInstance("X.509");
                java.security.cert.X509CRL crl = (java.security.cert.X509CRL)
                        cf.generateCRL(new ByteArrayInputStream(bytes));
                java.util.Date thisUpdate = crl.getThisUpdate();
                if (thisUpdate == null) continue;
                if (!thisUpdate.after(notAfter)) {
                    return new FetchedCrl(urlStr, thisUpdate, crl.getNextUpdate(), bytes, false);
                }
                if (postExpiryFallback == null) {
                    postExpiryFallback = new FetchedCrl(urlStr, thisUpdate, crl.getNextUpdate(),
                            bytes, true);
                }
            } catch (Exception e) {
                // try next URL
            }
        }
        return postExpiryFallback;
    }

    private static String describeMissingReason(CertificateWrapper c) {
        List<CertificateRevocationWrapper> extRevs = c.getCertificateRevocationData().stream()
                .filter(r -> !r.isInternalRevocationOrigin())
                .collect(java.util.stream.Collectors.toList());
        boolean hasCdp = c.getCRLDistributionPoints() != null && !c.getCRLDistributionPoints().isEmpty();
        if (extRevs.isEmpty() && !hasCdp) {
            return "certifikát neobsahuje CDP ani OCSP URL a žádná revokační informace není k dispozici";
        }
        if (extRevs.isEmpty()) {
            return "CRL z CDP se nepodařilo stáhnout nebo není v době platnosti certifikátu";
        }
        if (c.getNotAfter() != null
                && extRevs.stream().noneMatch(r -> revocationCoversCertificate(r, c.getNotAfter()))
                && extRevs.stream().anyMatch(
                        r -> r.getThisUpdate() != null && r.getThisUpdate().after(c.getNotAfter()))) {
            return "dostupné pouze post-exspirační revokační informace (vydané po notAfter certifikátu)";
        }
        boolean expiredResponder = extRevs.stream().anyMatch(r -> {
            if (r.getThisUpdate() == null) return false;
            Instant respExpiry = chainExpiry(r.getCertificateChain());
            return respExpiry != null && respExpiry.isBefore(r.getThisUpdate().toInstant());
        });
        return expiredResponder
                ? "dostupné revokační informace pocházejí od vydavatele, jehož certifikát byl v době vydání již expirovaný"
                : "dostupné revokační informace nejsou využitelné dle standardů";
    }

    /**
     * Would this revocation data still be in the document after uchování, or would the cleanup in
     * {@link AugmentationService} throw it out again?
     *
     * <p>Mirrors {@code isSingleRespUsable()} and {@code isCrlUseless()} there. An OCSP outlives
     * the certificate when it carries {@code certHash} (ISIS-MTT, binding the response to the
     * certificate directly) or an {@code archiveCutOff} (RFC 6960 clause 4.4.4) reaching back over
     * the certificate's lifetime. A CRL outlives it only through {@code expiredCertsOnCRL}
     * (ITU-T X.509, OID 2.5.29.60) — and that case never reaches here, it is already accepted as
     * standards-usable one step earlier.
     */
    private static boolean survivesEmbedding(CertificateRevocationWrapper rev, java.util.Date notAfter) {
        if (notAfter == null) return false;
        if (rev.getRevocationType() != RevocationType.OCSP) {
            // A CRL outlives the certificate either through its own expiredCertsOnCRL or through
            // the Trusted List's grant to the service; AugmentationService.isCrlUseless() keeps
            // both, so both may be promised here.
            return revocationCoversCertificate(rev, notAfter);
        }
        if (rev.isCertHashExtensionPresent()) return true;
        java.util.Date cutOff = rev.getArchiveCutOff();
        java.util.Date effective = cutOff != null ? cutOff : rev.getThisUpdate();
        return effective != null && !effective.after(notAfter);
    }

    /**
     * True when this revocation data says something about the moment in question.
     *
     * <p>The rule is the standard's own default, not a stricter one of our making. EN 319 102-1
     * V1.4.1 clause 5.2.5.4 step 1: absent a freshness value in the policy, the accepted freshness
     * is the interval between {@code thisUpdate} and {@code nextUpdate} — so step 2 accepts any
     * data whose validity window still reaches the moment being asked about. That is what makes a
     * cached interval response usable: the authority vouches for the status across the window it
     * published, so a response issued three hours before the signature, valid for a day, covers it.
     *
     * <p>Demanding issuance strictly after the moment — freshness zero — was tried and is wrong
     * here. Responders differ: PostSignum answers each request individually (measured: thisUpdate
     * two seconds before the query), while I.CA serves cached responses with roughly a day's
     * validity whose thisUpdate always predates the query. Under the strict reading the second kind
     * could never support preservation, though a conformant validator accepts it.
     *
     * <p>It still catches what the strict rule was introduced for: a list whose window closed long
     * before the signature existed says nothing about it. Data issued after the moment qualifies
     * outright, its window not having opened yet. Data without a {@code nextUpdate} qualifies only
     * that way — clause 5.2.5.4 step 1 fails such data outright when no policy supplies a freshness.
     */
    private static boolean coversMoment(java.util.Date thisUpdate, java.util.Date nextUpdate,
                                        Instant moment) {
        if (moment == null) return publishingNow(nextUpdate);
        if (thisUpdate != null && thisUpdate.toInstant().isAfter(moment)) return true;
        return nextUpdate != null && nextUpdate.toInstant().isAfter(moment);
    }

    /**
     * The same question for a signature that nothing anchors yet.
     *
     * <p>Here the moment to cover does not exist: preservation will create it, by adding the
     * time-stamp, and the revocation data will be fetched after that. Whether it will qualify
     * cannot be checked in advance — but it can be predicted, because data fetched later is never
     * older than data fetched now. So the question becomes whether the authority is publishing
     * usable status at all: an unexpired window, or a responder that answers each request and
     * therefore states no window.
     *
     * <p>Comparing against the current instant instead would fail on exactly the responders that
     * work best. PostSignum answers per request, so its {@code thisUpdate} is a second or two
     * before this code runs — always just short of "after now", which made the offer depend on
     * sub-second timing: of three signatures made in the same breath, one qualified and two did not.
     */
    private static boolean publishingNow(java.util.Date nextUpdate) {
        return nextUpdate == null || nextUpdate.toInstant().isAfter(Instant.now());
    }

    /**
     * A file whose only authentication elements are time-stamps — no signature at all.
     *
     * <p>Deliberately not restricted to ASiC. A PDF handed to a time-stamping authority and then
     * to another, and another, is the same artefact by a different route: a chain of proofs of
     * existence over a document nobody signed. It used to be excluded because the check asked for
     * a container type, and a PDF has none — so situations 33 and 34 were never computed for it and
     * every action but fixation was unofferable, on a document whose whole point is that the chain
     * gets renewed. Which path performs the renewal is a different question and is asked
     * separately: {@link cz.digitalnikontinuita.anep.core.SignatureExtensionService#isTimestampOnlyContainer}
     * still wants the container, because DSS extends containers and the PDF has a path of its own.
     */
    private static boolean isTimestampOnlyFile(DiagnosticData dd) {
        return dd.getSignatures().isEmpty() && !dd.getTimestampList().isEmpty();
    }

    /**
     * A certificate chain together with the moment its revocation data has to speak about.
     *
     * <p>The moment is not the same for every chain, and EN 319 102-1 V1.4.1 is explicit about it:
     * the revocation freshness checker (clause 5.2.5) measures against a <em>validation time</em>
     * passed in from outside, and the validation processes pass different ones. For the signing
     * certificate that is the best-signature-time (clause 5.5.4 step 8 b). For the certificate of a
     * time-stamp it is the time that time-stamp is proven to have existed (clause 5.6.3 step 5 a),
     * which is at the earliest its own generation time.
     *
     * <p>Judging a TSA certificate against the signature's best-signature-time instead would be
     * stricter than the standard asks — a time-stamp made years after the signature would demand
     * revocation data older than the time-stamp itself.
     */
    private record Chain(List<CertificateWrapper> certs, Instant reference) {}

    private static Instant productionTime(TimestampWrapper ts) {
        return ts.getProductionTime() == null ? null : ts.getProductionTime().toInstant();
    }

    private static String s33FormatLines(List<String> lines) {
        if (lines.isEmpty()) return null;
        if (lines.size() == 1) return lines.get(0);
        return lines.stream().map(l -> "• " + l).collect(java.util.stream.Collectors.joining("\n"));
    }

    /**
     * Computes S33 — is every certificate's revocation information available? — for one signature.
     *
     * <p>Available means either already in the document, whether that is inside the signature or
     * in the DSS/VRI dictionary, or fetchable now. Both count the same: S33 describes whether the
     * revocation picture can be made complete, not whether anything remains to be done about it.
     * A fully preserved document therefore satisfies S33, and so does one that carries nothing yet
     * but could fetch everything. It fails only where some certificate's revocation can neither be
     * found in the document nor obtained.
     *
     * <p>Data the document already holds is never fetched again — {@code hasEmbeddedRevocation}
     * and {@code coveredByOrphanCrl} end the walk for that certificate before any download, and
     * nothing is added to {@code crlBytesToEmbed} for it.
     *
     * <p>{@code preservable} is the separate question of whether uchování would achieve anything:
     * that needs something to actually be missing.
     */
    private S33Result computeS33(String sigId, DiagnosticData dd, SimpleReport simpleReport,
                                 Instant bestSigTime) {
        SignatureWrapper sw = dd.getSignatureById(sigId);
        if (sw == null) return S33Result.none();

        // Collect all cert chains: signing cert + all timestamp cert chains. Each carries the
        // moment its revocation data has to speak about — see Chain.
        //
        // The outermost document time-stamp is left out: its own validation material is not due yet.
        //
        // ETSI EN 319 142-1 V1.2.1 clause 6.3 requirement x) makes applications include, *before*
        // generating a document-time-stamp, the material needed to validate the signature and "any
        // time-stamp token's signing certificate … already incorporated to the signature
        // (including, of course, any previous document-time-stamp)". The time-stamp being applied
        // is not one of those, and the standard says when its turn comes: the protection is
        // extended "by adding further DSS information to validate the previous last document
        // time-stamp along with a new document time-stamp".
        //
        // Counting it here made a freshly preserved document report missing revocation data the
        // moment it was written, and preservation was offered again immediately — each round
        // adding another time-stamp whose own chain was then reported missing in turn.
        String outermostTsId = outermostDocumentTimestampId(dd, sigId);

        List<Chain> chains = new ArrayList<>();
        chains.add(new Chain(sw.getCertificateChain(), bestSigTime));
        java.util.Set<String> sigTsIds = new java.util.HashSet<>();
        for (TimestampWrapper ts : sw.getTimestampList()) {
            sigTsIds.add(ts.getId());
            if (ts.getId().equals(outermostTsId)) continue;
            chains.add(new Chain(ts.getCertificateChain(), productionTime(ts)));
        }
        for (TimestampWrapper ts : dd.getTimestampList()) {
            if (sigTsIds.contains(ts.getId())) continue;
            TimestampType tsType = ts.getType();
            if (!tsType.isDocumentTimestamp() && !tsType.isContainerTimestamp()) continue;
            if (ts.getTimestampedSignatures().stream().noneMatch(s -> sigId.equals(s.getId()))) continue;
            if (ts.getId().equals(outermostTsId)) continue;
            chains.add(new Chain(ts.getCertificateChain(), productionTime(ts)));
        }
        return s33ForChains(chains, orphanCrlIssuers(sw));
    }

    /**
     * Issuer names of the CRLs the document carries but DSS linked to no certificate.
     *
     * <p>DSS does not check revocation for a certificate it trusts, and a TSA certificate listed
     * in a Trusted List is one — so the CRL covering it, sitting in the document's own DSS/VRI
     * dictionary, is attached to nothing and never appears in {@code getCertificateRevocationData()}.
     * Asking only that method, as this class did, meant a list already inside the document counted
     * as absent: preservation was offered to fetch and embed a second copy, and the round after
     * that a third, because each copy was as invisible as the last.
     *
     * <p>Matching is by issuer name, which is what a CRL states about its scope — a CRL is issued
     * by the CA whose certificates it covers. It is deliberately not a validity-window test: the
     * question here is whether the document already carries the material, not whether that material
     * is fresh enough to decide the verdict, which the validation itself answers.
     */
    private static java.util.Set<String> orphanCrlIssuers(SignatureWrapper sw) {
        java.util.Set<String> issuers = new java.util.HashSet<>();
        for (var orphan : sw.foundRevocations().getOrphanRevocationData()) {
            if (orphan.getRevocationType() != RevocationType.CRL) continue;
            byte[] der = orphan.getBinaries();
            if (der == null) continue;
            try {
                issuers.add(dnKey(new org.bouncycastle.cert.X509CRLHolder(der).getIssuer()));
            } catch (Exception ignored) {
                // Unparsable bytes in the dictionary say nothing about coverage; skip them.
            }
        }
        return issuers;
    }

    /** True when a CRL already in the document was issued by this certificate's issuer. */
    private static boolean coveredByOrphanCrl(CertificateWrapper c,
                                              java.util.Set<String> orphanCrlIssuers) {
        if (orphanCrlIssuers.isEmpty()) return false;
        String issuerDn = c.getCertificateIssuerDN();
        if (issuerDn == null || issuerDn.isBlank()) return false;
        try {
            return orphanCrlIssuers.contains(dnKey(new org.bouncycastle.asn1.x500.X500Name(issuerDn)));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * A distinguished name reduced to something two sources can be compared on.
     *
     * <p>Neither the order nor the spelling can be relied upon. DSS renders a certificate's issuer
     * as {@code CN=…,O=…,2.5.4.97=#130e…,C=CZ} while the CRL in the same document states the very
     * same issuer as {@code C=CZ,organizationIdentifier=…,O=…,CN=…} — RDNs reversed, one attribute
     * as a raw OID with a hex-encoded value and the other by name. Comparing the strings finds no
     * match between a certificate and the CRL that covers it.
     *
     * <p>So each attribute is reduced to {@code oid=value} — the OID, never the alias — lower-cased,
     * and the parts are sorted. What survives is what both sides genuinely agree on.
     */
    private static String dnKey(org.bouncycastle.asn1.x500.X500Name name) {
        List<String> parts = new ArrayList<>();
        for (org.bouncycastle.asn1.x500.RDN rdn : name.getRDNs()) {
            for (org.bouncycastle.asn1.x500.AttributeTypeAndValue atv : rdn.getTypesAndValues()) {
                String value = org.bouncycastle.asn1.x500.style.IETFUtils.valueToString(atv.getValue());
                parts.add((atv.getType().getId() + "=" + value).toLowerCase(java.util.Locale.ROOT).trim());
            }
        }
        java.util.Collections.sort(parts);
        return String.join(",", parts);
    }

    /**
     * Id of the last document time-stamp covering this signature, or null when it carries none.
     *
     * <p>"Last" by production time, which for a PDF is the outermost revision — the one nothing
     * else protects yet, and the one whose validation material the next archiving round will add.
     * Signature time-stamps are not candidates however late they are: they sit inside the
     * signature, so any document time-stamp already covers them and their material is due now.
     */
    private static String outermostDocumentTimestampId(DiagnosticData dd, String sigId) {
        TimestampWrapper outermost = null;
        for (TimestampWrapper ts : dd.getTimestampList()) {
            TimestampType type = ts.getType();
            if (type == null || (!type.isDocumentTimestamp() && !type.isContainerTimestamp())) continue;
            if (ts.getTimestampedSignatures().stream().noneMatch(s -> sigId.equals(s.getId()))) continue;
            if (ts.getProductionTime() == null) continue;
            if (outermost == null
                    || ts.getProductionTime().after(outermost.getProductionTime())) {
                outermost = ts;
            }
        }
        return outermost != null ? outermost.getId() : null;
    }

    /**
     * S33 for a container that carries no signature at all, only time-stamps.
     *
     * <p>The question is the same one preservation always asks — can the revocation data that is
     * missing still be obtained? — but there is no signature to ask it about, so the chains come
     * from the container time-stamps themselves. Without this the offer would have to be made
     * blind, and it would be wrong exactly where it matters: once the last time-stamp's TSA
     * certificate has expired, no usable revocation for it can be fetched any more, so a fresh
     * time-stamp would attest to a chain that already has a hole in it. Fixation is the honest
     * offer there, and a false detected=true would hide it.
     */
    private S33Result computeS33ForContainerTimestamps(DiagnosticData dd) {
        List<Chain> chains = new ArrayList<>();
        for (TimestampWrapper ts : dd.getTimestampList()) {
            List<CertificateWrapper> chain = ts.getCertificateChain();
            if (!chain.isEmpty()) chains.add(new Chain(chain, productionTime(ts)));
        }
        if (chains.isEmpty()) return S33Result.none();
        return s33ForChains(chains, java.util.Set.of());
    }

    /**
     * The shared body: walk every certificate in every chain and decide whether what it lacks can
     * still be had. Identical for a signature and for a bare container time-stamp — only the way
     * the chains are gathered differs, which is why the callers above do just that and nothing more.
     */
    private S33Result s33ForChains(List<Chain> chains, java.util.Set<String> orphanCrlIssuers) {
        // Add responder/issuer chains from revocation data that is relevant to augmentation:
        // embedded data (internal origin) or externally-fetched data that is standards-usable
        // (thisUpdate within cert validity, or CRL with eco before notAfter). Post-expiry external
        // revocation (e.g. online OCSP fetched via the lenient verifier) won't be embedded during
        // augmentation — its responder chain would be spuriously added and may lack a downloadable
        // CRL, causing allAvailable to go false and suppressing the augmentation button.
        java.util.Set<String> addedResponderIds = new java.util.HashSet<>();
        for (Chain chain : new ArrayList<>(chains)) {
            for (CertificateWrapper c : chain.certs()) {
                if (c.isSelfSigned()) break;
                java.util.Date cNotAfter = c.getNotAfter();
                for (CertificateRevocationWrapper rev : c.getCertificateRevocationData()) {
                    if (!rev.isInternalRevocationOrigin()) {
                        // Skip external revocation that is not standards-usable for this cert.
                        if (!revocationCoversCertificate(rev, cNotAfter)) continue;
                    }
                    List<CertificateWrapper> respChain = rev.getCertificateChain();
                    if (!respChain.isEmpty() && addedResponderIds.add(respChain.get(0).getId())) {
                        // The issuer of a revocation token is judged at the same moment as the
                        // certificate it vouches for: it is that answer's credibility at stake.
                        chains.add(new Chain(respChain, chain.reference()));
                    }
                }
            }
        }

        // CDP download cache: Optional.empty() = tried and failed
        java.util.Map<String, java.util.Optional<FetchedCrl>> cdpCache = new java.util.HashMap<>();

        boolean anyMissing = false;
        boolean allAvailable = true;
        // Availability is not enough for preservation: what gets embedded has to speak about the
        // moment the signature is anchored to. Tracked separately from allAvailable so that S33
        // keeps describing the document as it is, while the offer keys on what preservation would
        // actually achieve.
        boolean allFresh = true;
        // The looser reading behind situation 45. It differs from allAvailable in exactly two
        // concessions — material published after the certificate expired, and material from an
        // issuer whose own certificate has expired — and in nothing else: a certificate with no
        // revocation information anywhere fails both readings alike.
        boolean allAvailableTolerant = true;
        java.util.Set<String> checked = new java.util.HashSet<>();
        List<String> availableLines = new ArrayList<>();
        List<String> missingLines  = new ArrayList<>();
        List<String> tolerantLines = new ArrayList<>();
        List<byte[]> crlBytesToEmbed = new ArrayList<>();
        List<byte[]> tolerantCrlBytes = new ArrayList<>();

        for (Chain chain : chains) {
            Instant reference = chain.reference();
            List<CertificateWrapper> certs = chain.certs();
            for (int i = 0; i < certs.size(); i++) {
                CertificateWrapper c = certs.get(i);
                // Only the certificate at the end of the chain — the signing one, or a TSA's own —
                // is held to the reference time. Its holder can have it revoked at any moment, and
                // bounding that is what the reference time is for. A CA certificate is different:
                // revocation there is a rare, published event, and the authority issues its list on
                // a slow cadence of its own. EN 319 102-1 V1.4.1 clause 5.2.5.4 step 1 takes the
                // freshness parameter "for the given certificate" and, absent a policy, derives it
                // from nextUpdate — so zero freshness is a choice to be made per certificate, not a
                // blanket rule. Made blanket, it blocked preservation of every PostSignum signature
                // in the corpus: the CA's list of 12 January 2026 is simply older than any signature
                // made since, though nothing is wrong with it.
                boolean endEntity = i == 0;
                if (c.isSelfSigned()) break;
                if (!checked.add(c.getId())) continue;
                if (c.isIdPkixOcspNoCheck()) continue; // responder cert — nocheck extension, no CRL needed

                // 0b. Revocation already embedded in the document structure (CMS, timestamp,
                //     DSS dict, VRI). Note it as available so the S33 detail is complete, but
                //     don't set anyMissing — this cert is already covered.
                //
                //     Being in the document is not on its own enough, and it took re-stamping to
                //     show why. That path deliberately embeds material the standards do not accept,
                //     so a document carrying a post-expiry OCSP used to satisfy S33 the moment it
                //     had been re-stamped — the very same document reported S45 one minute earlier,
                //     and nothing about the revocation's standing had changed. The test is the same
                //     one every other step applies: the material has to speak about the certificate
                //     it covers. What fails it falls through to the tolerant branch, which is where
                //     it belongs and where the reader is told what kind of coverage this is.
                CertificateRevocationWrapper embeddedUsable = c.getCertificateRevocationData().stream()
                        .filter(CertificateRevocationWrapper::isInternalRevocationOrigin)
                        .filter(r -> r.getStatus() != CertificateStatus.UNKNOWN)
                        .filter(r -> r.getThisUpdate() != null)
                        .filter(r -> c.getNotAfter() == null
                                || revocationCoversCertificate(r, c.getNotAfter()))
                        .findFirst().orElse(null);
                if (embeddedUsable != null) {
                    StringBuilder sb = new StringBuilder(c.getReadableCertificateName());
                    sb.append(" → ")
                      .append(embeddedUsable.getRevocationType() == RevocationType.CRL ? "CRL" : "OCSP")
                      .append(" (v dokumentu, vydáno ")
                      .append(formatRevDate(embeddedUsable.getThisUpdate()))
                      .append(")");
                    availableLines.add(sb.toString());
                    continue;
                }

                // 0c. A CRL from the document's own dictionary that DSS attached to nothing. It is
                //     in the document, so nothing is missing for this certificate — see
                //     orphanCrlIssuers() for why such a list ends up unattached.
                if (coveredByOrphanCrl(c, orphanCrlIssuers)) {
                    availableLines.add(c.getReadableCertificateName() + " → CRL v dokumentu");
                    continue;
                }

                // anyMissing is set by the branch that concludes something is actually missing,
                // not here.
                //
                // It used to be set at this point, before any of them had run, which made it true
                // for certificates the steps below then excused — a trust anchor at step 3, or
                // revocation the document already holds found at step 1. S33 therefore fired on
                // documents where nothing was missing at all, and a document preserved to LTA went
                // on being offered preservation round after round.
                //
                // Guarding this line with {@code !c.isTrusted()} was the wrong repair: in DSS
                // "trusted" means the certificate is in a Trusted List, which every qualified
                // intermediate CA is. That excused certificates whose revocation genuinely is
                // absent from the document and downloadable — the case preservation exists for —
                // and the offer disappeared where it was warranted.

                // 1. Check diagnostic data — external first, then any in-document data that
                //    getCertificateRevocationData() associates with this cert but that
                //    hasEmbeddedRevocation() missed (edge case: cert has both an expired
                //    external entry and a valid internal one, yet anyMatch returned false).
                //    External OCSP/CRL: trust augVerifier (OCSP-first) to handle it.
                //    Internal in-document: already present; augmentation will re-embed it.
                //    In either case no CDP download is needed.
                CertificateRevocationWrapper dssRev = bestStandardsUsableRevocation(c);
                if (dssRev != null) {
                    StringBuilder sb = new StringBuilder(c.getReadableCertificateName());
                    sb.append(" → ").append(dssRev.getRevocationType() == RevocationType.CRL ? "CRL" : "OCSP");
                    if (dssRev.isInternalRevocationOrigin()) {
                        // Already in the document, so nothing is missing for this certificate.
                        sb.append(" (v dokumentu");
                        if (dssRev.getThisUpdate() != null) {
                            sb.append(", vydáno ").append(formatRevDate(dssRev.getThisUpdate()));
                        }
                        sb.append(")");
                    } else {
                        anyMissing = true;
                        List<CertificateWrapper> issuerChain = dssRev.getCertificateChain();
                        if (!issuerChain.isEmpty()) {
                            sb.append(" od ").append(issuerChain.get(0).getReadableCertificateName());
                        }
                        if (dssRev.getThisUpdate() != null) {
                            sb.append(", vydáno ").append(formatRevDate(dssRev.getThisUpdate()));
                        }
                    }
                    availableLines.add(sb.toString());
                    if (endEntity && !coversMoment(dssRev.getThisUpdate(), dssRev.getNextUpdate(), reference))
                        allFresh = false;
                    continue;
                }

                // 1b. Post-expiry external revocation for already-expired certs, but only the kind
                //     that survives being embedded.
                //     S33 promises that uchování will end with the missing revocation data in the
                //     document, so nothing may count here that postProcessDss would strip out
                //     again. That rules out the ordinary post-expiry CRL: a CA legitimately drops
                //     entries for expired certificates (RFC 5280 clause 3.3), so the certificate's
                //     absence from a list published after it expired states nothing about it —
                //     which is exactly why {@code isCrlUseless()} discards it. Counting it here
                //     offered uchování that downloaded 835 kB and embedded nothing.
                //     What does survive is an OCSP that binds itself to the certificate:
                //     {@code certHash} (ISIS-MTT) or an {@code archiveCutOff} reaching back over
                //     its lifetime. The test mirrors {@code isSingleRespUsable()} in
                //     {@link AugmentationService}; the two must stay in step.
                if (c.getNotAfter() != null && c.getNotAfter().toInstant().isBefore(java.time.Instant.now())) {
                    CertificateRevocationWrapper postExpiry = c.getCertificateRevocationData().stream()
                            .filter(r -> !r.isInternalRevocationOrigin())
                            .filter(r -> r.getStatus() == CertificateStatus.GOOD)
                            .filter(r -> r.getThisUpdate() != null)
                            .filter(r -> survivesEmbedding(r, c.getNotAfter()))
                            .max(java.util.Comparator.comparing(CertificateRevocationWrapper::getThisUpdate))
                            .orElse(null);
                    if (postExpiry != null) {
                        anyMissing = true;
                        StringBuilder sb = new StringBuilder(c.getReadableCertificateName());
                        sb.append(" → ").append(postExpiry.getRevocationType() == RevocationType.CRL ? "CRL" : "OCSP")
                          .append(" (po expiraci certifikátu");
                        if (postExpiry.getThisUpdate() != null) {
                            sb.append(", vydáno ").append(formatRevDate(postExpiry.getThisUpdate()));
                        }
                        sb.append(")");
                        availableLines.add(sb.toString());
                        if (endEntity && !coversMoment(postExpiry.getThisUpdate(), postExpiry.getNextUpdate(),
                                reference)) allFresh = false;
                        continue;
                    }
                }

                // 2. No DSS data → try CDP download.
                //    CRL from CDP is self-contained (issued by the same CA as the cert), so no
                //    additional responder chain is needed during augmentation.  Only reached when
                //    the main validator had no revocation for this cert (e.g. trust-anchor CA that
                //    DSS skips during validation but whose CRL is still needed for LT embedding).
                java.util.Optional<FetchedCrl> cdpOpt = cdpCache.computeIfAbsent(
                        c.getId(), k -> java.util.Optional.ofNullable(tryFetchCrlFromCdp(c)));
                if (cdpOpt.isPresent() && !cdpOpt.get().postExpiry()) {
                    anyMissing = true;
                    FetchedCrl fetched = cdpOpt.get();
                    StringBuilder sb = new StringBuilder(c.getReadableCertificateName());
                    sb.append(" → CRL stažená z CDP");
                    if (fetched.thisUpdate() != null) {
                        sb.append(", vydáno ").append(formatRevDate(fetched.thisUpdate()));
                    }
                    availableLines.add(sb.toString());
                    if (fetched.crlBytes() != null) crlBytesToEmbed.add(fetched.crlBytes());
                    if (endEntity && !coversMoment(fetched.thisUpdate(), fetched.nextUpdate(), reference))
                        allFresh = false;
                    continue;
                }

                // 3. Truly missing — but trust anchors are exempt: ETSI EN 319 142-1 only
                //    requires revocation that was *used during validation*, and DSS skips
                //    revocation checks for trust anchors.
                if (c.isTrusted()) {
                    availableLines.add(c.getReadableCertificateName() + " → kotva důvěry, revokace nezjišťována");
                    continue;
                }

                // 4. Strict coverage has failed for this certificate. Ask the looser question
                //    situation 45 is about before writing it off: is there revocation information
                //    that a validator judging certificates by their own validity period would
                //    accept, even though ETSI's rules do not?
                //
                //    Everything the strict walk turned away arrives here, so this is the single
                //    place the two readings part company — the steps above are untouched, and a
                //    document where nothing was relaxed produces exactly the lines it did before.
                TolerantRevocation tolerant = tolerantCoverage(c, cdpOpt.orElse(null));
                allAvailable = false;
                missingLines.add(c.getReadableCertificateName() + " — " + describeMissingReason(c));
                if (tolerant != null) {
                    tolerantLines.add(c.getReadableCertificateName() + " → " + tolerant.description());
                    if (tolerant.crlBytes() != null) tolerantCrlBytes.add(tolerant.crlBytes());
                } else {
                    allAvailableTolerant = false;
                }
            }
        }

        // S33 states coverage: every certificate that needs revocation information has it — in the
        // document already, or obtainable. It deliberately does not depend on whether anything is
        // missing. A document that carries everything satisfies S33 exactly as much as one that
        // carries nothing yet but could fetch it all, because the question S33 answers is whether
        // the revocation picture is complete or completable, not whether there is work to do.
        boolean detected = allAvailable;

        // Whether there is work to do is a separate matter, and it is what the offer of uchování
        // runs on: something has to be missing, all of it obtainable, and fresh enough to be worth
        // embedding. Keeping the two apart is what stops a preserved document from being offered
        // preservation again for material it already holds.
        boolean worthDoing = anyMissing && allAvailable;

        // S45 is the complement of S33, never its companion: it reports that coverage is complete
        // only once post-expiry material and material from an expired issuer are allowed to count.
        // Where S33 holds there is nothing for it to add, and saying both would tell the reader
        // twice over that the picture is complete while implying the second reading was needed.
        boolean tolerantlyComplete = !allAvailable && allAvailableTolerant;

        return new S33Result(detected, s33FormatLines(availableLines), s33FormatLines(missingLines),
                worthDoing ? crlBytesToEmbed : List.of(), worthDoing && allFresh,
                tolerantlyComplete, tolerantlyComplete ? s33FormatLines(tolerantLines) : null,
                tolerantlyComplete ? tolerantCrlBytes : List.of());
    }

    /**
     * Revocation information a certificate-validity-based validator accepts, described for the
     * reader and — where the bytes had to be downloaded — carried along for embedding.
     */
    private record TolerantRevocation(String description, byte[] crlBytes) {}

    /**
     * Údaj, o který se závěr opřel, i s jeho vadami — nebo {@code null}, když ho nelze určit.
     *
     * <p>Výběr dělá DSS a zapisuje ho do protokolu jako {@code LatestAcceptableRevocationId};
     * čte se odsud týž údaj, jaký v Rozšířeném přehledu stojí v řádku „Využité revokační
     * informace". Bere se proto, že rozhoduje o tom, který ústupek verdikt opravdu nesl.
     *
     * <p>Naměřeno na 918 podpisech s aspoň jedním rozhodujícím ústupkem: u 81 % má vybraný údaj
     * právě jednu z obou vad, takže hlavní ústupek je jednoznačný; 6 % má obě naráz a 13 % ani
     * jednu, protože ústupek se uplatnil jinde v řetězci. Text se podle toho větví na tři tvary.
     */
    private static ValidationCourse.PrimaryRevocation primaryRevocation(
            SignatureWrapper sw, DiagnosticData dd, String detailedReportXml,
            Instant decisiveInstant, AppSettings settings, boolean conclusionPositive) {
        if (sw == null || detailedReportXml == null || detailedReportXml.isBlank()) return null;
        CertificateWrapper sc = sw.getSigningCertificate();
        if (sc == null) return null;
        CertificateRevocationWrapper chosen = chooseUsedRevocation(
                sc, selectedRevocationId(detailedReportXml, sc.getId()),
                r -> revocationIssuerHasProofInValidity(r, sw), decisiveInstant,
                settings.isAcceptExpiredRevocationSources(), settings.isAcceptNewerOcsp(),
                postExpiryRevocationAccepted(settings), conclusionPositive);
        if (chosen == null) return null;

        java.util.Date vt = dd.getValidationDate();
        boolean postExpiry = chosen.getThisUpdate() != null && sc.getNotAfter() != null
                && chosen.getThisUpdate().after(sc.getNotAfter());
        CertificateWrapper issuer = chosen.getSigningCertificate();
        boolean outOfRange = issuer != null && vt != null
                && ((issuer.getNotAfter() != null && issuer.getNotAfter().before(vt))
                 || (issuer.getNotBefore() != null && issuer.getNotBefore().after(vt)));

        // Vždycky mužský rod a jádro „revokační údaj": věta o něm dál mluví zájmenem („umožnil ho")
        // a druh se liší rodem — CRL seznam je mužský, OCSP odpověď ženská. Držet druh v závorce
        // je jediný způsob, jak nemít dvě znění věty jen kvůli shodě.
        String kind = chosen.getRevocationType() == RevocationType.CRL
                ? "CRL seznam" : "OCSP odpověď";
        String origin = chosen.isInternalRevocationOrigin()
                ? "vložená v dokumentu" : "stažená";
        String when = chosen.getThisUpdate() != null
                ? " z " + formatRevDate(chosen.getThisUpdate()) : "";
        // Bez závorky kolem druhu a data: plný zápis okamžiku svou závorku pro místní čas už má
        // a druhá kolem něj by dělala „(CRL seznam z … (…))". Přístavek za pomlčkou čte se stejně.
        return new ValidationCourse.PrimaryRevocation(chosen.getId(),
                origin + " revokační informace — " + kind + when, postExpiry, outOfRange);
    }

    /**
     * Který revokační údaj o certifikátu se má nazývat využitým.
     *
     * <p>Ne prostě ten, který DSS zapsalo jako {@code LatestAcceptableRevocationId} — ten atribut
     * znamená doslova „poslední přípustný", a v tolerantním režimu se přípustnou stane i odpověď
     * stažená dnes, takže výběr přeskočí na ni, i když verdikt na ní vůbec nestojí. Potkáno na
     * PAdES B-LT kvalifikovany.pdf: přísně projde o vloženou CRL z 13. 6. 2025, tolerantně DSS
     * ukáže na OCSP z 21. 8. 2026 — a měření nutnosti přitom nevrací žádný rozhodující ústupek,
     * tedy ta odpověď potřeba nebyla. Rozšířený přehled kvůli tomu tvrdil něco jiného než Analýza.
     *
     * <p>Pořadí je proto tohle:
     *
     * <ol>
     *   <li><b>Odvolání má přednost vždy.</b> Je jednosměrné: co bylo odvoláno, se neodvolá zpět.
     *       „Neodvolán" naproti tomu platí jen k okamžiku vydání a od té chvíle se mohlo změnit.</li>
     *   <li><b>Mezi „neodvolán" ten, který obstojí bez ústupku.</b> Pojmenovat jako využitý údaj,
     *       který si nevyžádal žádnou úlevu, je poctivější než pojmenovat ten, který ji potřeboval
     *       a přitom nebyl potřeba.</li>
     *   <li>Teprve nezbyde-li nic z toho, výběr DSS.</li>
     * </ol>
     */
    private static CertificateRevocationWrapper chooseUsedRevocation(
            CertificateWrapper cert, String crsSelectedId,
            java.util.function.Predicate<CertificateRevocationWrapper> hasProof,
            Instant decisiveInstant, boolean acceptExpiredSources, boolean acceptNewer,
            boolean postExpiryAccepted, boolean conclusionPositive) {
        List<CertificateRevocationWrapper> all = cert.getCertificateRevocationData();
        if (all == null || all.isEmpty()) return null;
        // Odvolání má přednost — ale jen to, které na verdikt dosáhlo. Došlo-li k němu až po
        // rozhodném okamžiku, certifikát byl tehdy v pořádku a závěr se opřel o údaj, který to
        // říká; „odvolán o tři měsíce později" je pravdivá zpráva o certifikátu, ne o tom, čím
        // se ověření řídilo.
        //
        // Naměřeno na „PAdES B-LT neviditelny PS.pdf" (s ústupkem pro expirovaného vydavatele):
        // DSS zapsalo jako využitou vloženou odpověď ze 14. 1. 2026, kdežto tohle pravidlo
        // ukazovalo na staženou z 24. 8. 2026 s odvoláním k 28. 4. 2026 — tedy tři a půl měsíce
        // po rozhodném okamžiku. Rozšířený přehled tak tvrdil něco jiného než Analýza i Detailní
        // analýza.
        //
        // A stejně tak jen to, které tenhle běh k DSS vůbec propustil. Post-expirační odvolání je
        // sice materiál, který něco dokládá — proto ho revocationUnusableReason() nepovažuje za
        // nepoužitelný — jenže bez uvolněné konzistence ho DSS zahodí (isConsistent()) a verdikt
        // se opře o něco jiného. Právě tak vypadá situace 20: vložený údaj vydaný ještě před
        // odvoláním nese TOTAL_PASSED, kdežto stažený, který odvolání prokazuje, leží stranou.
        // Dokud se rozlišení nedělalo, hlásil Rozšířený přehled i Analýza jako využitou tu
        // staženou odpověď a Závěr z ní počítal odstup „revokace přišla až později", ačkoli
        // k odvolání došlo dřív než k razítku.
        CertificateRevocationWrapper revoked = newestMatching(all,
                r -> CertificateStatus.REVOKED.equals(r.getStatus())
                        && !revokedAfter(r, decisiveInstant)
                        && !rejectedAsPostExpiry(cert, r, postExpiryAccepted));
        if (revoked != null) return revoked;

        java.util.function.Predicate<CertificateRevocationWrapper> strictlyUsable =
                r -> revocationUnusableReason(cert, r, false, false, hasProof.test(r)) == null;

        // Týž přísný test, jen bez námitky „chybí důkaz existence" — a jen tam, kde závěr padl
        // kladně. Ta námitka totiž stojí na tom, že se o takový údaj závěr opřít nemohl a ukáže
        // se to na LTV_ISCKNR; u prvku, který vyšel jako platný, se ale ukázalo něco jiného:
        // opřel. Je to pak zjištění o <b>trvanlivosti</b> toho důkazu, ne o tomhle ověření.
        //
        // Nález uživatele 13. 9. 2026 na „container-counter-signed-xades-baseline-lt.sce":
        // u obou prvků jmenuje protokol DSS jako využitou vloženou odpověď OCSP z 18. 2. 2026
        // (k rozhodnému okamžiku její vydavatel ještě platil), kdežto AnEP u prvku č. 1 jmenoval
        // staženou — kterou DSS výslovně odmítlo — a u prvku č. 2 nejmenoval nic.
        java.util.function.Predicate<CertificateRevocationWrapper> usableForConclusion =
                r -> revocationUnusableReason(cert, r, false, false,
                        hasProof.test(r) || conclusionPositive) == null;

        // Výběr DSS má přednost, pokud si nevyžádal žádnou úlevu. Protokol je autorita na to, oč se
        // ověření opřelo — atribut LatestAcceptableRevocationId je doslova jeho odpověď na tuto
        // otázku — a přebíjet ho smí jedině důvod, kvůli kterému tenhle přepis vznikl: že se
        // „poslední přípustný“ stane přípustným teprve ústupkem, na kterém závěr nestojí. Kde ústupek
        // potřeba nebyl, žádný takový důvod není a rozejít se s protokolem by znamenalo tvrdit
        // o postupu něco, co se nestalo.
        //
        // Naměřeno na „PAdES B-T kvalifikovaný PS 4096 neviditelny-extended-pades-baseline-lt.pdf“,
        // ověřeném bez jediného ústupku: podrobný protokol DSS čtyřikrát uvádí „Latest acceptable
        // revocation : OCSP_…_20260826-1122“ a kontrolu čerstvosti i kryptografických omezení počítá
        // právě nad tou odpovědí, kdežto Rozšířený přehled ukazoval vloženou CRL z 9. 5. 2026.
        if (crsSelectedId != null) {
            for (CertificateRevocationWrapper r : all) {
                if (!crsSelectedId.equals(r.getId())) continue;
                if (!usableForConclusion.test(r)) break;
                // Odvolání, ke kterému došlo až po rozhodném okamžiku, závěr nenese — o tom
                // rozhodl krok výš a tenhle ho nesmí obejít. Právě tak vypadá situace 24: k datu
                // ověření je certifikát odvolaný, ale k rozhodnému okamžiku nebyl, a závěr stojí
                // na údaji, který to říká.
                if (CertificateStatus.REVOKED.equals(r.getStatus())
                        && revokedAfter(r, decisiveInstant)) break;
                return r;
            }
        }

        // Jinak má přednost vlastní materiál dokumentu, obstojí-li podle nastavení, se kterým se
        // opravdu ověřovalo. Vložený údaj je současník rozhodného okamžiku a je to on, o co se
        // opře i validátor bez sítě; odpověď stažená dnes je doplněk.
        //
        // Tím se srovnaly dva případy, které si dosud odporovaly. Na „PAdES B-LT kvalifikovany.pdf"
        // ukazovalo DSS v tolerantním režimu na OCSP z 21. 8. 2026, ačkoli přísně stačila vložená
        // CRL z 13. 6. 2025 — proto se sem výběr DSS nesmí brát rovnou. Na „PAdES B-LT neviditelny
        // PS.pdf" naopak DSS ukázalo na vloženou odpověď ze 14. 1. 2026 a pravidlo „nejnovější
        // použitelný" ji přebilo staženou z 24. 8. 2026, takže Rozšířený přehled tvrdil něco jiného
        // než Analýza i Detailní rozbor. Obojí řeší táž věta: nejdřív to, co je v dokumentu.
        //
        // Posuzuje se podle skutečného nastavení, ne přísně. Přísný test je otázka „obešel by se
        // ten údaj bez úlevy", kdežto tady se ptáme, oč se závěr opřel — a ten se opřel o to, co
        // běh připustil. Právě na tomhle rozdílu se rozcházel Analýza, který soudí podle nastavení,
        // s tímhle výběrem, který soudil přísně.
        CertificateRevocationWrapper embedded = newestMatching(all,
                r -> r.isInternalRevocationOrigin()
                        && revocationUnusableReason(cert, r, acceptExpiredSources, acceptNewer,
                                hasProof.test(r)) == null);
        if (embedded != null) return embedded;

        // Jinak nejnovější, který si nevyžádal žádnou úlevu. Pojmenovat jako využitý údaj, který
        // úlevu nepotřeboval, je poctivější než ten, který ji potřeboval a přitom nebyl potřeba.
        CertificateRevocationWrapper strict = newestMatching(all, strictlyUsable);
        if (strict != null) return strict;

        if (crsSelectedId != null) {
            for (CertificateRevocationWrapper r : all) {
                if (!crsSelectedId.equals(r.getId())) continue;
                // Poslední možnost: výběr DSS, i když přísně neobstál. Jen ne tehdy, když padl na
                // chybějícím důkazu existence — to DSS zapsalo po kontrole přijatelnosti (RAC) a
                // že se o něj závěr opřít nemohl, se ukáže teprve na LTV_ISCKNR. Bez téhle výhrady
                // by se sem tou zadní cestou vrátil právě ten údaj, který výš vypadl.
                // …a jen dokud závěr nepadl kladně. Tahle větev je poslední možnost: dostane se
                // sem prvek, kterému neprošel ani jeden dřívější krok — a má-li přitom kladný
                // závěr, opřel se o něco, a to něco je výběr DSS.
                //
                // Naměřeno na „S5-LT pred revokaci - pozdejsi doplneni.pdf": podpisový certifikát
                // má JEDINÝ revokační údaj, vloženou odpověď OCSP z 30. 7. 2018 hlásící odvolání
                // téhož dne v 5:17:32 — tedy den po rozhodném okamžiku (29. 7. 2018 9:46:16).
                // První větev ji přeskočí právem: odvolání po rozhodném okamžiku verdikt nenese.
                // Jenže jiný údaj tu není, takže závěr „k rozhodnému okamžiku byl v pořádku"
                // stojí právě na ní — a AnEP ji vedl mezi odmítnutými a tvrdil, že stav určil
                // „jiný údaj", který neexistuje.
                //
                // Rozdíl proti první větvi: ta rozhoduje, který údaj má PŘEDNOST, když je z čeho
                // vybírat. Tahle odpovídá na otázku, oč se závěr opřel, když vybírat nebylo z čeho.
                return !conclusionPositive
                        && issuerExpiredWithoutProof(cert, r, hasProof.test(r), false) ? null : r;
            }
        }
        return null;
    }

    /**
     * Zda tenhle běh vůbec dovolil opřít se o revokační údaj vydaný až po expiraci certifikátu,
     * jehož se týká.
     *
     * <p>Uděluje to zaškrtávátko „Využij i revokační informace, vydané po expiraci certifikátu",
     * a zúženě také zadané datum ověření — k okamžiku v minulosti je post-expirační každý údaj
     * stažený dnes, takže bez toho by k datu nešlo ověřit nic. Je to táž dvojice, ze které
     * {@code ValidatorFactory} skládá {@code relaxConsistency}; bez ní DSS post-expirační údaj
     * zahodí v {@code isConsistent()} bez ohledu na to, co tvrdí.
     */
    public static boolean postExpiryRevocationAccepted(AppSettings settings) {
        return settings.isAcceptNewerOcsp()
                || (settings.isUseCustomValidationTime() && settings.getValidationDate() != null);
    }

    /**
     * Zda by tenhle běh takový údaj zahodil jako post-expirační — tedy zda se o něj závěr
     * <b>nemohl</b> opřít, ať už tvrdí cokoli.
     *
     * <p>Podmínka je schválně táž, jakou uvnitř DSS klade {@code isConsistent()}: {@code thisUpdate}
     * po {@code notAfter} certifikátu, s výjimkou seznamu CRL, jehož {@code expiredCertsOnCRL}
     * sahá ještě do doby platnosti — tím se vydavatel zavázal záznamy neodebírat, takže i jeho
     * post-expirační výpověď něco dokládá.
     */
    /**
     * Zda údaj vydaný až po expiraci certifikátu přesto vypovídá <b>o něm</b>.
     *
     * <p>Námitka proti post-expiračnímu údaji míří na odpověď „neodvolán“: vydavatel smí
     * expirovaný certifikát přestat uvádět, takže jeho mlčení nedokazuje nic. Padá však
     * pokadé, když údaj sám doloží, že o tomhle certifikátu ví — a to umí třemi způsoby:
     *
     * <ul>
     *   <li>{@code expiredCertsOnCRL} (X.509, OID 2.5.29.60) u seznamu,</li>
     *   <li>{@code archiveCutOff} (RFC 6960 kl. 4.4.4) u OCSP odpovědi,</li>
     *   <li>{@code CertHash} (Common PKI) — otisk právě toho certifikátu přímo v odpovědi,
     *       tedy doklad, že respondér nesečtěl „good“ naslepo.</li>
     * </ul>
     *
     * <p>Je to doslova pravidlo, kterým se řídí DSS: {@code RevocationDataVerifier.isConsistent()}
     * volá {@code doesRevocationKnowCertificate()}, a ta je
     * {@code revocationInformationAssured() || certHashMatch()} (ověřeno v bajtkódu dss-spi 6.5).
     * Dokud tady stálo jen {@code expiredCertsOnCRL}, prohlašoval AnEP za nepoužitelný údaj,
     * o který se jeho vlastní ověření opřelo — nad jedním úředním dokumentem říkala
     * Analýza „žádný údaj k dispozici není“, kdežto Rozšířený přehled i protokol DSS
     * jmenovaly staženou OCSP odpověď s odpovídajícím CertHash jako využitou.
     *
     * <p>Grant z důvěryhodného seznamu ({@code expiredCertsRevocationInfo}) se sem schválně
     * nepřidává: DSS ho v téhle otázce nečte, a AnEP by pak tvrdil o postupu něco, co se
     * nestalo. Pro pokrytí při uchování ho čte {@link #revocationKnownSince}.
     */
    private static boolean postExpiryStillSpeaks(CertificateWrapper cert,
                                                  CertificateRevocationWrapper rev) {
        java.util.Date notAfter = cert.getNotAfter();
        if (notAfter == null) return false;
        java.util.Date eco = rev.getExpiredCertsOnCRL();
        if (eco != null && !eco.after(notAfter)) return true;
        java.util.Date cutOff = rev.getArchiveCutOff();
        if (cutOff != null && !cutOff.after(notAfter)) return true;
        return rev.isCertHashExtensionPresent() && rev.isCertHashExtensionMatch();
    }

    private static boolean rejectedAsPostExpiry(CertificateWrapper cert,
                                                 CertificateRevocationWrapper rev,
                                                 boolean postExpiryAccepted) {
        if (postExpiryAccepted) return false;
        if (rev.getThisUpdate() == null || cert.getNotAfter() == null) return false;
        if (!rev.getThisUpdate().after(cert.getNotAfter())) return false;
        return !postExpiryStillSpeaks(cert, rev);
    }

    /**
     * O který revokační údaj se opřel závěr o každém použitém certifikátu — {@code certId → revId}.
     *
     * <p>Táž volba jako v {@link #chooseUsedRevocation}, jen položená pro celou diagnostiku najednou,
     * aby se na ni mohla ptát i Detailní rozbor. Ta jinak nemá jak odlišit údaj, který závěr nesl,
     * od údaje, který v dokumentu jen leží: {@link ValidationCourse#primaryRevocation()} mluví
     * o podpisovém certifikátu, kdežto vložené OCSP odpovědi bývají i o vydavatelích a
     * o certifikátech razítek.
     */
    public static java.util.Map<String, String> usedRevocationIdByCertificate(
            DiagnosticData dd, String detailedReportXml,
            boolean acceptExpiredSources, boolean acceptNewer, boolean postExpiryAccepted,
            java.util.Set<String> certsWithPositiveConclusion) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (dd == null) return map;
        // Důkaz existence se posuzuje u každého revokačního údaje zvlášť a napříč podpisy:
        // certifikát bývá společný víc z nich a stačí, aby ten důkaz unesl kterýkoli.
        java.util.Map<String, String> selected = selectedRevocationIds(detailedReportXml);
        for (CertificateWrapper cert : dd.getUsedCertificates()) {
            CertificateRevocationWrapper chosen = chooseUsedRevocation(
                    cert, selected.get(cert.getId()),
                    r -> revocationIssuerHasProofInValidity(r, dd),
                    earliestProvenExistence(dd), acceptExpiredSources, acceptNewer,
                    postExpiryAccepted,
                    certsWithPositiveConclusion != null
                            && certsWithPositiveConclusion.contains(cert.getId()));
            if (chosen != null) map.put(cert.getId(), chosen.getId());
        }
        return map;
    }

    /**
     * Podpisové certifikáty prvků, jejichž závěr padl kladně.
     *
     * <p>Otázku „opřel se o tenhle údaj závěr" má smysl klást jen k prvku, ne k dokumentu:
     * verdikt má každý vlastní. Mapa přes certifikáty ji proto potřebuje takhle převedenou.
     */
    public static java.util.Set<String> signingCertsOfPositiveSignatures(
            DiagnosticData dd, List<cz.digitalnikontinuita.anep.model.SignatureSummary> sigs) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (dd == null || sigs == null) return out;
        for (cz.digitalnikontinuita.anep.model.SignatureSummary s : sigs) {
            if (!"TOTAL_PASSED".equals(s.getIndication())) continue;
            SignatureWrapper sw = dd.getSignatureById(s.getSignatureId());
            if (sw != null && sw.getSigningCertificate() != null) {
                out.add(sw.getSigningCertificate().getId());
            }
        }
        return out;
    }

    /**
     * Zda nad certifikátem vydavatele tohoto revokačního údaje existuje důkaz, který padne
     * <b>dovnitř jeho doby platnosti</b>.
     *
     * <p>Nestačí, že podpis nějaké archivní razítko má. Razítko přidané až dávno po expiraci
     * respondéra prokazuje jen to, že odpověď existovala tehdy, kdy už respondér neplatil — a to
     * je k ničemu. V DSS to testuje {@code POEExistsWithinCertificateValidityRangeCheck}, kterou
     * {@code PastSignatureValidationCertificateRevocationSelector} volá nad
     * {@code revocationWrapper.getSigningCertificate()}; táž úvaha stojí i za
     * {@link #poeForIssuer}, jen tam slouží předpovědi, jestli uchování pomůže.
     *
     * <p>Potkáno na „LT po revokaci later LTA timestamped.pdf": respondér platil do 20. 8. 2018,
     * archivní razítko přibylo 1. 12. 2022. Podpis tedy úroveň LTA má, ale vložené OCSP se o ni
     * opřít nemůže — a DSS ho taky nepoužilo, verdikt skončil na
     * INDETERMINATE/REVOCATION_OUT_OF_BOUNDS_NO_POE. Hrubá podmínka „podpis má LTA" tvrdila opak.
     *
     * <p>Za platné razítko se tu bere to, jehož podpis sedí a otisk souhlasí. {@link #poeForIssuer}
     * se ptá protokolu na {@code Indication.PASSED}, což je přísnější, ale potřebuje
     * {@code SimpleReport}; ten na téhle cestě k dispozici není a rozdíl je jen v razítku, které
     * projde kryptograficky a padne na řetězu důvěry.
     */
    /**
     * Nositelé, ze kterých archivní proces (5.6) materiál opravdu bere — tedy dlouhodobé
     * úložiště dokumentu.
     *
     * <p>{@code ADBE_REVOCATION_INFO_ARCHIVAL} a {@code CMS_SIGNED_DATA} v něm <b>nejsou</b>:
     * odpověď v podepsaném atributu je součástí podpisu, ne validačního materiálu, a DSS na to
     * odpovídá {@code ARCH_LTAIVMP_ANS} („No long term availability and integrity of validation
     * material is present"). Důkaz existence takové odpovědi tedy existuje, ale nemá ho kdo
     * použít, dokud se materiál do úložiště nepřenese — což udělá teprve uchování.
     */
    private static final java.util.Set<eu.europa.esig.dss.enumerations.RevocationOrigin>
            DLOUHODOBE_ULOZISTE = java.util.EnumSet.of(
                    eu.europa.esig.dss.enumerations.RevocationOrigin.DSS_DICTIONARY,
                    eu.europa.esig.dss.enumerations.RevocationOrigin.VRI_DICTIONARY,
                    eu.europa.esig.dss.enumerations.RevocationOrigin.REVOCATION_VALUES,
                    eu.europa.esig.dss.enumerations.RevocationOrigin.ATTRIBUTE_REVOCATION_VALUES,
                    eu.europa.esig.dss.enumerations.RevocationOrigin.TIMESTAMP_VALIDATION_DATA,
                    eu.europa.esig.dss.enumerations.RevocationOrigin.ANY_VALIDATION_DATA);

    /**
     * Leží tenhle revokační údaj v dlouhodobém úložišti dokumentu?
     *
     * <p><b>Tohle je ta dělicí čára</b>, ne úroveň podpisu a ne druh razítka; obojí se 11. 9. 2026
     * měřením vyvrátilo. Úroveň selže na „B s revinfo A uznavany nereferencni.pdf": je to
     * PDF-NOT-ETSI, tedy pod LTA, a přesto se o vloženou odpověď opře i bez sítě — protože ta
     * odpověď je v /DSS a /VRI. Druh razítka selže na uchovaném úředním dokladu: jeho archivní
     * razítko z roku 2026 leží mimo platnost respondéra (do 2023), a přesto dokument projde,
     * protože důkaz dodá podpisové razítko z roku 2023 a materiál je v /DSS.
     *
     * <p>Naopak jeden doklad z roku 2026 a „PAdES B-B kvalfikovany s revinfo 20 22.pdf"
     * mají odpověď <b>jen</b> v atributu ADBE, žádné /DSS — a oba měřením padají: první k roku
     * 2027, druhý hned, jakmile se odpojí síť.
     */
    /** Táž otázka položená jen podle identifikátoru údaje. */
    private static boolean idInLongTermStore(String id, SignatureWrapper sw) {
        for (var origin : DLOUHODOBE_ULOZISTE) {
            for (var found : sw.foundRevocations().getRelatedRevocationsByOrigin(origin)) {
                if (id.equals(found.getId())) return true;
            }
        }
        return false;
    }

    private static boolean carriedInLongTermStore(
            eu.europa.esig.dss.diagnostic.RevocationWrapper rev, SignatureWrapper sw) {
        if (rev == null || sw == null) return false;
        for (var origin : DLOUHODOBE_ULOZISTE) {
            for (var found : sw.foundRevocations().getRelatedRevocationsByOrigin(origin)) {
                if (rev.getId().equals(found.getId())) return true;
            }
        }
        return false;
    }

    public static boolean revocationIssuerHasProofInValidity(
            eu.europa.esig.dss.diagnostic.RevocationWrapper rev, SignatureWrapper sw) {
        // Důkaz existuje, ale uplatní se jen u materiálu, ze kterého archivní proces bere —
        // viz carriedInLongTermStore().
        if (!carriedInLongTermStore(rev, sw)) return false;
        CertificateWrapper issuer = rev.getSigningCertificate();
        if (issuer == null) return false;
        Instant notAfter = issuer.getNotAfter() != null ? issuer.getNotAfter().toInstant() : null;
        if (notAfter == null) return false;
        java.util.Date poeDate = revocationIssuerProofTime(rev, sw);
        if (poeDate == null) return false;
        Instant poe = poeDate.toInstant();
        if (poe.isAfter(notAfter)) return false;
        Instant notBefore = issuer.getNotBefore() != null ? issuer.getNotBefore().toInstant() : null;
        return notBefore == null || !poe.isBefore(notBefore);
    }

    /**
     * Nejstarší platné razítko, které kryje certifikát vydavatele tohoto revokačního údaje —
     * tedy okamžik, k němuž bude důkaz existence toho certifikátu; {@code null}, když ho nekryje
     * žádné.
     *
     * <p>Odděleno od {@link #revocationIssuerHasProofInValidity} proto, že „důkaz vůbec není" a
     * „důkaz je, ale přišel pozdě" jsou pro čtenáře dvě různé zprávy a text je nesmí splést.
     */
    public static java.util.Date revocationIssuerProofTime(
            eu.europa.esig.dss.diagnostic.RevocationWrapper rev, SignatureWrapper sw) {
        CertificateWrapper issuer = rev.getSigningCertificate();
        if (issuer == null || sw == null) return null;
        java.util.Date best = null;
        for (TimestampWrapper ts : sw.getTimestampList()) {
            if (!ts.isSignatureValid() || !ts.isMessageImprintDataFound()
                    || !ts.isMessageImprintDataIntact()) continue;
            if (ts.getProductionTime() == null) continue;
            boolean covers = ts.getTimestampedCertificates().stream()
                    .anyMatch(c -> issuer.getId().equals(c.getId()));
            if (!covers) continue;
            if (best == null || ts.getProductionTime().before(best)) best = ts.getProductionTime();
        }
        return best;
    }

    /** Týž test položený za celý dokument — stačí, aby ho unesl kterýkoli z podpisů. */
    private static boolean revocationIssuerHasProofInValidity(
            eu.europa.esig.dss.diagnostic.RevocationWrapper rev, DiagnosticData dd) {
        return dd != null && dd.getSignatures().stream()
                .anyMatch(s -> revocationIssuerHasProofInValidity(rev, s));
    }

    /**
     * Nejstarší okamžik, který v tomhle dokumentu prokazuje platné časové razítko — náhrada za
     * rozhodný okamžik tam, kde není po ruce protokol s {@code bestSignatureTime}.
     */
    private static Instant earliestProvenExistence(DiagnosticData dd) {
        Instant best = null;
        for (SignatureWrapper sw : dd.getSignatures()) {
            for (TimestampWrapper ts : sw.getTimestampList()) {
                if (!ts.isSignatureValid() || !ts.isMessageImprintDataIntact()) continue;
                if (ts.getProductionTime() == null) continue;
                Instant t = ts.getProductionTime().toInstant();
                if (best == null || t.isBefore(best)) best = t;
            }
        }
        return best;
    }

    /** Odvolání, ke kterému došlo až po rozhodném okamžiku — na verdikt tedy nedosáhlo. */
    private static boolean revokedAfter(CertificateRevocationWrapper r, Instant decisiveInstant) {
        return decisiveInstant != null && r.getRevocationDate() != null
                && r.getRevocationDate().toInstant().isAfter(decisiveInstant);
    }

    /** Nejnovější podle {@code thisUpdate} z těch, které projdou testem; jinak {@code null}. */
    private static CertificateRevocationWrapper newestMatching(
            List<CertificateRevocationWrapper> all,
            java.util.function.Predicate<CertificateRevocationWrapper> test) {
        CertificateRevocationWrapper best = null;
        for (CertificateRevocationWrapper r : all) {
            if (r.getThisUpdate() == null || !test.test(r)) continue;
            if (best == null || r.getThisUpdate().after(best.getThisUpdate())) best = r;
        }
        return best;
    }

    /** {@code LatestAcceptableRevocationId} pro daný certifikát; první výskyt v pořadí dokumentu. */
    private static String selectedRevocationId(String detailedReportXml, String certId) {
        return selectedRevocationIds(detailedReportXml).get(certId);
    }

    /** Totéž pro všechny certifikáty naráz — protokol se pak čte jednou, ne jednou za certifikát. */
    private static java.util.Map<String, String> selectedRevocationIds(String detailedReportXml) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (detailedReportXml == null || detailedReportXml.isBlank()) return map;
        try {
            javax.xml.parsers.DocumentBuilderFactory dbf =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(
                    new org.xml.sax.InputSource(new java.io.StringReader(detailedReportXml)));
            org.w3c.dom.NodeList all = doc.getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                if (!(all.item(i) instanceof org.w3c.dom.Element el)) continue;
                String local = el.getLocalName();
                if (!"CRS".equals(local) && !"PSV_CRS".equals(local)) continue;
                String certId = el.getAttribute("Id");
                String rev = el.getAttribute("LatestAcceptableRevocationId");
                if (certId == null || certId.isBlank() || rev == null || rev.isBlank()) continue;
                map.putIfAbsent(certId, rev);
            }
        } catch (Exception ignored) { }
        return map;
    }

    /**
     * The looser reading of "revocation information is available", for a certificate the strict
     * walk has just given up on.
     *
     * <p>Two concessions, and only these two. Material may have been published after the
     * certificate expired, and it may come from an issuer whose own certificate was <em>already</em>
     * past its notAfter when it produced the answer — the test below is
     * {@code chainExpiry(issuer) < thisUpdate}, not "expired by now". The difference is the one
     * {@link #hasOnlyExpiredAtProductionSourceRevocationForSigningCert} spells out: an issuer that
     * was still valid when it signed leaves a legitimate historical proof, however long ago that was.
     * Both are refusals ETSI makes on grounds of what the material can be held to <em>prove</em>,
     * and both are refusals the validators at CzechPOINT and at the filing rooms of public
     * authorities do not make: they ask whether the certificate was valid and whether a list or
     * response says it was not revoked. Nothing else is relaxed — a certificate with no revocation
     * information at all, or one whose only answer is {@code UNKNOWN}, fails here as it does above.
     *
     * <p>{@code REVOKED} is not excluded on purpose: a revoked certificate says so under either
     * reading, and re-stamping is offered only for a signature that came out valid, which one
     * cannot have. Excluding it here would misdescribe the material instead.
     */
    private static TolerantRevocation tolerantCoverage(CertificateWrapper c, FetchedCrl fetched) {
        CertificateRevocationWrapper best = null;
        for (CertificateRevocationWrapper rev : c.getCertificateRevocationData()) {
            if (rev.getStatus() == CertificateStatus.UNKNOWN) continue;
            if (rev.getThisUpdate() == null) continue;
            if (best == null || rev.getThisUpdate().after(best.getThisUpdate())) best = rev;
        }
        if (best != null) {
            StringBuilder sb = new StringBuilder(
                    best.getRevocationType() == RevocationType.CRL ? "CRL" : "OCSP");
            List<String> why = new ArrayList<>();
            if (c.getNotAfter() != null && best.getThisUpdate().after(c.getNotAfter())) {
                why.add("vydáno po expiraci certifikátu");
            }
            Instant respExpiry = chainExpiry(best.getCertificateChain());
            if (respExpiry != null && respExpiry.isBefore(best.getThisUpdate().toInstant())) {
                why.add("od vydavatele, který už byl po expiraci");
            }
            if (!why.isEmpty()) sb.append(" (").append(String.join(", ", why)).append(")");
            sb.append(", vydáno ").append(formatRevDate(best.getThisUpdate()));
            // The bytes are in the diagnostic data, so re-stamping reads them straight from there
            // together with everything else DSS saw; nothing needs carrying from here.
            return new TolerantRevocation(sb.toString(), null);
        }
        if (fetched != null && fetched.crlBytes() != null) {
            String when = fetched.thisUpdate() != null
                    ? ", vydáno " + formatRevDate(fetched.thisUpdate()) : "";
            return new TolerantRevocation(
                    "CRL stažená z CDP (vydáno po expiraci certifikátu)" + when, fetched.crlBytes());
        }
        return null;
    }

    /**
     * Returns verifiableUntil: the maximum "effective expiry" across all PASSED timestamps,
     * where each timestamp's effective expiry = min(notAfter) of non-trust-anchor certs in
     * its chain. This matches DSS's ExtensionPeriodMax — the timestamp with the longest-lived
     * cert chain determines how far into the future the signature can be verified, regardless
     * of which timestamp was produced most recently.
     * Falls back to the signature's own signing cert notAfter if no valid timestamps.
     */
    private Instant computeVerifiableUntil(String sigId, DiagnosticData dd,
                                            SimpleReport simpleReport) {
        SignatureWrapper sw = dd.getSignatureById(sigId);
        if (sw == null) return null;

        // Only time-stamps that fix best-signature-time extend the window. A VRI time-stamp would
        // otherwise promise verifiability until its TSA certificate expires, years past the point
        // where conformant validators stop accepting the signature.
        Instant max = sw.getTimestampList().stream()
                .filter(ts -> canEstablishBestSignatureTime(ts, sw, simpleReport))
                .filter(ts -> simpleReport.getIndication(ts.getId()) == Indication.PASSED)
                .map(ts -> chainExpiry(ts.getCertificateChain()))
                .filter(t -> t != null)
                .max(Comparator.naturalOrder())
                .orElse(null);

        if (max != null) return max;

        CertificateWrapper cert = sw.getSigningCertificate();
        if (cert == null || cert.getNotAfter() == null) return null;
        return cert.getNotAfter().toInstant();
    }

    /**
     * Minimum notAfter from the signing cert up to and including the first trust anchor.
     * Certs above the trust anchor (e.g. an unlisted root CA above the EU-TL-registered
     * intermediate) are excluded — they are not part of the validated path and their
     * notAfter must not extend the computed window.
     * If the signing cert itself is the trust anchor, its notAfter is the result.
     */
    /**
     * True when the CRL or OCSP was signed directly by a trust anchor — typically a CA that is
     * itself on the Trusted List and answers OCSP with its own key, as eIdentity does.
     *
     * <p>Such data must never be treated as coming from an "expired issuer" (S5/S28/S29). A trust
     * anchor's trustworthiness comes from the Trusted List and its service history, not from the
     * certificate's own {@code notAfter}; EN 319 102-1 runs no validity check on it, which is why
     * DSS reaches TOTAL_PASSED for these signatures. {@link #chainExpiry} deliberately counts the
     * anchor's expiry (it serves the augmentation deadline, where the date does matter), so the
     * anchor has to be filtered out here instead. Mirrors the same test in
     * {@code DetailedReportView.collectExpiredResponderCerts()}.
     */
    /**
     * True when the signing certificate is revoked and the revocation happened after the
     * best-signature-time — the branch on which DSS reaches TOTAL_PASSED via
     * {@code ADEST_IRTPTBST} ("Is the revocation time after best-signature-time?").
     *
     * <p>In that case the verdict does not rest on the embedded "not revoked" answer at all, so
     * an expired issuer behind that answer is irrelevant and S28 must not downgrade the result.
     * Revocation is a permanent fact; the time-stamp proves the signature predates it. The same
     * distinction is already made for the amber note in
     * {@code DetailedReportView.collectExpiredResponderCerts()}.
     */
    private static boolean revokedAfterBestSignatureTime(SignatureWrapper sw, Instant bst) {
        CertificateWrapper signingCert = sw.getSigningCertificate();
        if (signingCert == null || bst == null) return false;
        for (CertificateRevocationWrapper rev : signingCert.getCertificateRevocationData()) {
            if (rev.getStatus() != CertificateStatus.REVOKED) continue;
            java.util.Date revDate = rev.getRevocationDate();
            if (revDate != null && revDate.toInstant().isAfter(bst)) return true;
        }
        return false;
    }

    private static boolean isTrustedRevocationIssuer(CertificateRevocationWrapper rev) {
        CertificateWrapper issuer = rev.getSigningCertificate();
        return issuer != null && issuer.isTrusted();
    }

    private static Instant chainExpiry(List<CertificateWrapper> chain) {
        Instant min = null;
        for (CertificateWrapper c : chain) {
            if (c.getNotAfter() == null) {
                if (c.isTrusted()) break;
                continue;
            }
            Instant notAfter = c.getNotAfter().toInstant();
            if (min == null || notAfter.isBefore(min)) min = notAfter;
            if (c.isTrusted()) break; // trust anchor included; stop here
        }
        return min;
    }

    /**
     * Protějšek {@link #chainExpiry}: okamžik, od kterého řetěz platí celý — tedy nejpozdější
     * {@code notBefore} v něm. Bez něj se dá poznat jen ten konec doby platnosti, který nastal,
     * a ověřuje-li se k okamžiku v minulosti, bývá potíž na druhé straně: respondér, který
     * odpověď podepsal, tehdy ještě vydaný nebyl.
     */
    private static Instant chainNotBefore(List<CertificateWrapper> chain) {
        Instant max = null;
        for (CertificateWrapper c : chain) {
            if (c.getNotBefore() == null) {
                if (c.isTrusted()) break;
                continue;
            }
            Instant notBefore = c.getNotBefore().toInstant();
            if (max == null || notBefore.isAfter(max)) max = notBefore;
            if (c.isTrusted()) break;
        }
        return max;
    }

    /**
     * Latest safe deadline for augmenting this signature to LT or LTA level:
     * the earliest notAfter of any cert (in the signing chain or any TSA chain)
     * that does NOT yet have embedded revocation data.
     *
     * Trust anchors are always excluded — they are validated via the Trusted List.
     * A cert is considered "covered" when at least one of its CertificateRevocationWrapper
     * entries has isInternalRevocationOrigin() == true (i.e. the CRL/OCSP was embedded in
     * the document, not just fetched online during this validation run).
     *
     * Fallback: when every cert already has embedded revocation (e.g. LT/LTA signature),
     * the augmentation is effectively complete; verifiableUntil is returned instead —
     * this marks when a fresh archive timestamp should be added before that deadline.
     */
    /**
     * The preservation deadline for a time-stamp standing on its own — a time-stamped container,
     * a forensic package. Same rule as for a signature, applied to the one chain there is: the
     * earliest expiry among certificates that do not yet carry revocation data inside the file.
     *
     * <p>Falls back to the same wording as {@link #computeAugmentationDeadline} when everything is
     * already covered, because the question is then identical — the chain of stamps has to be
     * renewed before the current one's certificate runs out.
     */
    private static AugmentationDeadline timestampAugDeadline(TimestampWrapper ts,
                                                             Instant verifiableUntil,
                                                             DiagnosticData dd) {
        AugmentationDeadline min = chainDeadlines(ts.getCertificateChain(),
                                                  revocationsWithPoe(dd), ChainRole.TIMESTAMP);
        if (min != null) return min;
        return verifiableUntil == null ? null : new AugmentationDeadline(verifiableUntil,
                "Ke všem certifikátům už jsou v dokumentu vloženy revokační informace, takže lhůtu"
                + " určuje konec platnosti certifikátu autority časových razítek. Do té doby je"
                + " třeba přidat další archivní razítko, jinak řetěz razítek skončí.");
    }

    private AugmentationDeadline computeAugmentationDeadline(String sigId, DiagnosticData dd,
                                                             SimpleReport simpleReport) {
        SignatureWrapper sw = dd.getSignatureById(sigId);
        if (sw == null) return null;

        java.util.Set<String> poe = revocationsWithPoe(dd);
        AugmentationDeadline min = null;
        // První řetěz je vlastní řetěz podpisu (či pečeti), ostatní patří razítkům — viz
        // validationChains(). Na roli závisí jen to, jak se certifikát ve vysvětlení lhůty
        // jmenuje; „podpisový certifikát" nad řetězem razítka by byl nesmysl.
        List<List<CertificateWrapper>> chains = validationChains(sigId, dd);
        ChainRole own = isSeal(simpleReport, sigId) ? ChainRole.SEAL : ChainRole.SIGNATURE;
        for (int i = 0; i < chains.size(); i++) {
            min = earlier(min, chainDeadlines(chains.get(i), poe,
                    i == 0 ? own : ChainRole.TIMESTAMP));
        }

        if (min == null) {
            // All reachable certs already have embedded revocation (and respondér chains are
            // covered) — fall back to verifiableUntil so the field communicates the next
            // relevant deadline (when a fresh archive timestamp should be added).
            Instant until = computeVerifiableUntil(sigId, dd, simpleReport);
            return until == null ? null : new AugmentationDeadline(until,
                    "Ke všem certifikátům už jsou v dokumentu vloženy revokační informace, takže"
                    + " lhůtu určuje nejzazší konec platnosti v řetězech certifikátů, na kterých"
                    + " časová razítka stojí. Do té doby je třeba přidat další archivní razítko,"
                    + " jinak řetěz razítek skončí.");
        }
        return min;
    }

    /**
     * Řetězce certifikátů, na kterých ověřitelnost jednoho podpisu stojí: jeho vlastní, řetězce
     * razítek v podpisu a řetězce dokumentových či kontejnerových razítek, která ho kryjí.
     *
     * <p>Vyčleněno z {@link #computeAugmentationDeadline}, protože se týž výčet ptá i na jinou
     * otázku — {@link #computeUnprovenRevocationDeadline} — a dvě kopie by se rozešly.
     */
    private static List<List<CertificateWrapper>> validationChains(String sigId,
                                                                   DiagnosticData dd) {
        List<List<CertificateWrapper>> chains = new ArrayList<>();
        SignatureWrapper sw = dd.getSignatureById(sigId);
        if (sw == null) return chains;
        chains.add(sw.getCertificateChain());

        // Signature-embedded timestamps (SIGNATURE_TIMESTAMP, ARCHIVE_TIMESTAMP, …)
        java.util.Set<String> sigTsIds = new java.util.HashSet<>();
        for (TimestampWrapper ts : sw.getTimestampList()) {
            sigTsIds.add(ts.getId());
            chains.add(ts.getCertificateChain());
        }

        // Document-level timestamps (PAdES /DocTimeStamp, ASiC container timestamps).
        // DSS does not always link these to sw.getTimestampList(); include them explicitly
        // when they cover this signature and haven't been counted above.
        for (TimestampWrapper ts : dd.getTimestampList()) {
            if (sigTsIds.contains(ts.getId())) continue;
            TimestampType tsType = ts.getType();
            if (!tsType.isDocumentTimestamp() && !tsType.isContainerTimestamp()) continue;
            boolean coversThisSig = ts.getTimestampedSignatures().stream()
                    .anyMatch(s -> sigId.equals(s.getId()));
            if (!coversThisSig) continue;
            chains.add(ts.getCertificateChain());
        }
        return chains;
    }

    /** Všechny tři lhůty, které jeden řetěz certifikátů ukládá, sloučené na tu nejbližší. */
    private static AugmentationDeadline chainDeadlines(List<CertificateWrapper> chain,
                                                       java.util.Set<String> poe,
                                                       ChainRole role) {
        return earlier(earlier(chainAugDeadline(chain, poe, role), responderDeadline(chain, poe)),
                       unprovenRevocationDeadline(chain, poe, role));
    }

    /**
     * Čí je řetěz, na jehož konci certifikát stojí — kvůli tomu, jak se ten certifikát jmenuje
     * ve vysvětlení lhůty.
     *
     * <p>„Lhůtu určuje certifikát Jan Novák" čtenáři neřekne, který ze čtyř certifikátů v řetězu
     * to je ani proč právě on; jméno v tom tvaru navíc vypadá jako jméno souboru. Role tedy
     * rozhoduje o názvu koncového certifikátu — podpisový, pečeti, razítkovací autority —
     * a všechno nad ním je certifikát certifikační autority.
     */
    enum ChainRole { SIGNATURE, SEAL, TIMESTAMP, REVOCATION_ISSUER }

    /**
     * Jméno certifikátu ve vysvětlení lhůty, v 1. pádě.
     *
     * @param leaf certifikát na začátku řetězu, tedy ten, kterým se podepisovalo či razítkovalo
     */
    private static String certRole(ChainRole role, boolean leaf, CertificateWrapper c) {
        return certRole(role, leaf, c.getReadableCertificateName());
    }

    /** Táž věta nad holým jménem — oddělené od DSS, aby se dala změřit testem. */
    static String certRole(ChainRole role, boolean leaf, String name) {
        if (!leaf) return "certifikát certifikační autority (" + name + ")";
        return switch (role) {
            case SIGNATURE -> "podpisový certifikát (držitele " + name + ")";
            case SEAL -> "certifikát pečeti (držitele " + name + ")";
            case TIMESTAMP -> "certifikát razítkovací autority (" + name + ")";
            case REVOCATION_ISSUER ->
                    "certifikát vydavatele revokačních informací (" + name + ")";
        };
    }

    /** Totéž v 6. pádě — „o podpisovém certifikátu (držitele …)". */
    private static String certRoleLocative(ChainRole role, boolean leaf, CertificateWrapper c) {
        return certRoleLocative(role, leaf, c.getReadableCertificateName());
    }

    static String certRoleLocative(ChainRole role, boolean leaf, String name) {
        if (!leaf) return "certifikátu certifikační autority (" + name + ")";
        return switch (role) {
            case SIGNATURE -> "podpisovém certifikátu (držitele " + name + ")";
            case SEAL -> "certifikátu pečeti (držitele " + name + ")";
            case TIMESTAMP -> "certifikátu razítkovací autority (" + name + ")";
            case REVOCATION_ISSUER ->
                    "certifikátu vydavatele revokačních informací (" + name + ")";
        };
    }

    /**
     * Lhůta, kterou uvaluje revokační údaj vložený v dokumentu, ale <b>bez vlastního důkazu
     * existence</b> — konec platnosti certifikátu, kterým je ten údaj podepsán.
     *
     * <p>Doplněno 30. 8. 2026, protože to dosud nikdo nesledoval a šlo o zásadní článek pro
     * stanovení termínu dalšího kroku. Nález, ze kterého to vzešlo: PAdES-BASELINE-T od
     * PostSignum s OCSP odpovědí přiloženou v {@code SignedData.crls}. Vložený údaj vypadá jako
     * něco, co nezvětrá — a v tom smyslu, že bajty zůstanou v souboru, to platí. Uznat ho ale
     * jde jen tak dlouho, dokud platí certifikát respondéra, který ho podepsal: pak už je to
     * „údaj od vydavatele, jemuž vypršel certifikát“, tedy případ, který standardy nepřipouštějí
     * a AnEP propouští jen ústupkem. Lhůtu tedy neurčovalo razítko roku 2032, nýbrž respondér
     * s platností do března 2027.
     *
     * <p>Podpisové razítko tu nepomůže: kryje jen podpisovou hodnotu, nikoli pole {@code crls}.
     * Proto se ptáme na {@link #revocationsWithPoe} a bereme jen údaje, které <b>nekryje</b>
     * archivní, dokumentové ani kontejnerové razítko — u LT/LTA se tedy nic nemění.
     *
     * <p>Certifikát s {@code id-pkix-ocsp-nocheck} se tu, na rozdíl od {@link #chainAugDeadline},
     * nepřeskakuje: ten příznak říká „ke mně netřeba revokační údaj“, ne „platím navěky“.
     */
    private static AugmentationDeadline unprovenRevocationDeadline(List<CertificateWrapper> chain,
                                                                   java.util.Set<String> poe,
                                                                   ChainRole role) {
        AugmentationDeadline min = null;
        for (int i = 0; i < chain.size(); i++) {
            CertificateWrapper c = chain.get(i);
            boolean leaf = i == 0;
            if (c.isTrusted()) break;
            if (c.getNotAfter() == null) continue;

            // Nejpozdější okamžik, do kterého je uznatelný některý z vložených údajů o tomhle
            // certifikátu. Když některý z nich má vlastní důkaz existence, nekončí to nikdy
            // a certifikát žádnou lhůtu neukládá.
            Instant embeddedUntil = null;
            boolean embedded = false;
            for (CertificateRevocationWrapper rev : c.getCertificateRevocationData()) {
                if (!rev.isInternalRevocationOrigin()) continue;
                embedded = true;
                if (poe.contains(rev.getId())) { embeddedUntil = null; break; }
                CertificateWrapper issuer = rev.getSigningCertificate();
                if (issuer == null || issuer.getNotAfter() == null) continue;
                Instant until = issuer.getNotAfter().toInstant();
                if (embeddedUntil == null || until.isAfter(embeddedUntil)) embeddedUntil = until;
            }
            if (!embedded || embeddedUntil == null) continue;

            // A teď to podstatné: <b>maximum</b>, ne minimum. Dokud platí certifikát, o kterém
            // údaj vypovídá, dá se stáhnout čerstvý — podepsaný už nástupcem respondéra. Vložený
            // údaj tedy není strop, nýbrž druhá cesta vedle sítě: uznatelný je do expirace svého
            // vydavatele, síť drží do expirace certifikátu samotného, a rozhoduje ta pozdější
            // z nich. Brát respondéra samotného by u krátkolesého respondéra pod dlouholeťým
            // podpisovým certifikátem hlásilo lhůtu o roky dřív, než je pravda.
            boolean fromNetwork = networkOutlastsEmbedded(c.getNotAfter().toInstant(),
                                                          embeddedUntil);
            Instant when = fromNetwork ? c.getNotAfter().toInstant() : embeddedUntil;
            String reason = fromNetwork
                    ? "Lhůtu určuje " + certRole(role, leaf, c)
                      + ", jehož platnost končí " + formatRevDate(c.getNotAfter())
                      + ". Revokační informace o něm sice v dokumentu vložená je, ale nekryje ji žádné"
                      + " archivní ani dokumentové časové razítko, takže ji lze uznat jen do expirace"
                      // Datum uznatelnosti vloženého údaje se sem doplnilo 7. 9. 2026: bez něj
                      // věta říkala, že rozhoduje něco jiného než datum nad ní, a čtenář neměl
                      // jak poznat, o kolik se ty dvě cesty rozcházejí. U „PAdES B-LT uznavany
                      // viditelny PS.pdf" je to rozdíl dvou a půl roku.
                      + " jejího vydavatele, tedy do "
                      + formatRevDate(java.util.Date.from(embeddedUntil))
                      + ". Čerstvou lze stáhnout, dokud certifikát platí;"
                      + " po expiraci už ji zpravidla nebude kde vzít."
                    // „jeho platnost" bylo dvojznačné od chvíle, kdy věta jmenuje dva certifikáty
                    // mužského rodu — vydavatele a ten, o kterém údaj vypovídá. Datum patří
                    // vydavateli, tak to říká i věta.
                    : "Lhůtu určuje vydavatel revokační informace o "
                      + certRoleLocative(role, leaf, c) + "; ta je vložená v dokumentu"
                      + " a platnost jejího vydavatele končí "
                      + formatRevDate(java.util.Date.from(embeddedUntil))
                      + ". Tu informaci nekryje žádné archivní ani dokumentové časové razítko,"
                      + " takže nemá vlastní důkaz existence: po expiraci svého vydavatele z ní"
                      + " bude „informace od vydavatele, jemuž vypršel certifikát“ — a takovou"
                      + " standardy neuznávají. Stáhnout čerstvou už nepomůže: certifikát,"
                      + " o kterém vypovídá, v té době bude dávno po expiraci.";
            min = earlier(min, new AugmentationDeadline(when, reason));
        }
        return min;
    }

    /**
     * Vydrží síť dél než vložený údaj? Vyčleněno kvůli tomu, aby se to pravidlo dalo změřit
     * samostatně: je to jediné místo, kde se bere <b>maximum</b>, a splést ho s minimem je tichá
     * chyba — lhůta vyjde dřív, než je pravda, a nikdo si toho nevšimne.
     *
     * @param certNotAfter    konec platnosti certifikátu, o kterém revokační údaj vypovídá —
     *                        do té doby se dá stáhnout čerstvý
     * @param embeddedUntil   konec platnosti vydavatele vloženého údaje — do té doby je uznatelný
     */
    static boolean networkOutlastsEmbedded(Instant certNotAfter, Instant embeddedUntil) {
        return certNotAfter.isAfter(embeddedUntil);
    }

    /**
     * Revokační údaje, o kterých je prokázáno, že existovaly v době, kdy jejich vydavatel ještě
     * platil — tedy ty, které kryje časové razítko nad celým souborem.
     *
     * <p>Rozlišuje se druh razítka a je to to podstatné: {@code getTimestampedRevocations()}
     * hlásí vloženou OCSP odpověď i u podpisového razítka, ačkoli to kryje jen podpisovou
     * hodnotu, nikoli pole {@code crls}. Vzít ten výčet tak, jak je, by celý tenhle článek
     * vypnulo právě u případu, kvůli kterému vznikl. Důkaz existence proto uznáváme jen od
     * razítek, která kryjí celý soubor: archivní, dokumentové a kontejnerové.
     */
    private static java.util.Set<String> revocationsWithPoe(DiagnosticData dd) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (TimestampWrapper ts : dd.getTimestampList()) {
            TimestampType t = ts.getType();
            if (!t.isArchivalTimestamp() && !t.isDocumentTimestamp() && !t.isContainerTimestamp()) {
                continue;
            }
            if (!ts.isSignatureValid() || !ts.isMessageImprintDataIntact()) continue;
            for (eu.europa.esig.dss.diagnostic.RevocationWrapper rev
                    : ts.getTimestampedRevocations()) {
                // Razítko musí být z doby, kdy vydavatel údaje ještě platil. Razítko přidané až po
                // jeho expiraci dokazuje jen to, že údaj existoval tehdy, když už vydavatel neplatil,
                // a to k uznání údaje nestačí (v DSS
                // {@code POEExistsWithinCertificateValidityRangeCheck}).
                //
                // Změřeno na uchovaném úředním dokumentu: vložená odpověď
                // z 2. 6. 2026 od respondéra s certifikátem do 14. 6. 2026, archivní razítko
                // z 9. 9. 2026. Hrubé „razítko ji kryje“ z ní dělalo údaj bez lhůty, takže blok
                // „Jak dlouho dokáže AnEP ověřit platnost“ sliboval rok 2034 — a týž dokument
                // přitom bez sítě skončil na INDETERMINATE/REVOCATION_OUT_OF_BOUNDS_NO_POE.
                if (!proofInIssuerValidity(rev, ts)) continue;
                ids.add(rev.getId());
            }
        }
        return ids;
    }

    /** Padne čas toho razítka do doby platnosti certifikátu, kterým je revokační údaj podepsán? */
    private static boolean proofInIssuerValidity(
            eu.europa.esig.dss.diagnostic.RevocationWrapper rev, TimestampWrapper ts) {
        CertificateWrapper issuer = rev.getSigningCertificate();
        if (issuer == null || issuer.getNotAfter() == null) return false;
        java.util.Date proof = ts.getProductionTime();
        if (proof == null) return false;
        if (proof.after(issuer.getNotAfter())) return false;
        return issuer.getNotBefore() == null || !proof.before(issuer.getNotBefore());
    }

    /**
     * Totéž jako {@link #unprovenRevocationDeadline}, ale za celý podpis — pro model, který si to
     * nese vedle {@link #computeAugmentationDeadline}.
     *
     * <p>Vlastní pole, ne jen součást minima: minimum je jedno číslo a horizont ověřitelnosti se
     * na obě lhůty ptá jinak. Lhůtu z dostupnosti stažených údajů zahazuje tam, kde rozhodující
     * údaj přišel z dokumentu nebo z vlastního úložiště; tahle lhůta platí i tam — vložený údaj
     * bez důkazu existence zvětrá bez ohledu na to, odkud přišly ostatní.
     */
    private static AugmentationDeadline computeUnprovenRevocationDeadline(
            String sigId, DiagnosticData dd, SimpleReport simpleReport) {
        // Dnešní test podle úrovně podpisu nahrazen nositelem údaje — viz
        // carriedInLongTermStore(). Úroveň označila za nedosažitelný i materiál, o který se
        // dokument prokazatelně opře (PDF-NOT-ETSI s /DSS).
        SignatureWrapper swForPoe = dd.getSignatureById(sigId);
        java.util.Set<String> poe = new java.util.HashSet<>();
        for (String id : revocationsWithPoe(dd)) {
            poe.add(id);
        }
        if (swForPoe != null) {
            poe.removeIf(id -> !idInLongTermStore(id, swForPoe));
            // A naopak: u materiálu v úložišti stačí důkaz od kteréhokoli razítka podpisu —
            // tak se o něj opře past signature validation. Změřeno na uchovaném úředním dokladu,
            // kde archivní razítko leží mimo platnost respondéra a nese to podpisové.
            for (CertificateWrapper c : dd.getUsedCertificates()) {
                for (CertificateRevocationWrapper r : c.getCertificateRevocationData()) {
                    if (revocationIssuerHasProofInValidity(r, swForPoe)) poe.add(r.getId());
                }
            }
        }
        AugmentationDeadline min = null;
        // Týž výčet a totéž rozdělení rolí jako v computeAugmentationDeadline: první řetěz je
        // vlastní, ostatní patří razítkům.
        List<List<CertificateWrapper>> chains = validationChains(sigId, dd);
        ChainRole own = isSeal(simpleReport, sigId) ? ChainRole.SEAL : ChainRole.SIGNATURE;
        for (int i = 0; i < chains.size(); i++) {
            min = earlier(min, unprovenRevocationDeadline(chains.get(i), poe,
                    i == 0 ? own : ChainRole.TIMESTAMP));
        }
        return min;
    }

    /**
     * For each cert in {@code chain} (up to the trust anchor) that has at least one
     * embedded revocation item, inspects the revocation signer's certificate chain
     * (OCSP respondér or CRL issuer) and applies {@link #chainAugDeadline} to it.
     * Returns the earliest deadline found, or null when every respondér chain is
     * already fully covered by embedded revocation.
     */
    private static AugmentationDeadline responderDeadline(List<CertificateWrapper> chain,
                                                          java.util.Set<String> poe) {
        AugmentationDeadline min = null;
        for (CertificateWrapper c : chain) {
            if (c.isTrusted()) break;
            for (CertificateRevocationWrapper rev : c.getCertificateRevocationData()) {
                if (!rev.isInternalRevocationOrigin()) continue;
                min = earlier(min, chainAugDeadline(rev.getCertificateChain(), poe,
                        ChainRole.REVOCATION_ISSUER));
            }
        }
        return min;
    }

    /** Returns the earliest non-null instant among the arguments, or null if all are null. */
    private static Instant minOf(Instant... instants) {
        Instant min = null;
        for (Instant i : instants) {
            if (i != null && (min == null || i.isBefore(min))) min = i;
        }
        return min;
    }

    /**
     * Minimum notAfter of certs in the chain (before the trust anchor) that do NOT
     * yet have an embedded CRL/OCSP response — i.e. certs for which revocation data
     * still needs to be fetched and stored to reach LT level.
     *
     * Trust anchors (isTrusted) stop the walk and are always included: if a trust
     * anchor cert has already expired, it signals that augmentation is overdue — the
     * caller's display layer should render "ihned" (immediately) in that case.
     * Self-signed certs in revocation-data chains (where DSS may not set isTrusted())
     * are treated the same way: they are included and then the walk stops.
     */
    private static AugmentationDeadline chainAugDeadline(List<CertificateWrapper> chain,
                                                         java.util.Set<String> poe,
                                                         ChainRole role) {
        AugmentationDeadline min = null;
        for (int i = 0; i < chain.size(); i++) {
            CertificateWrapper c = chain.get(i);
            boolean leaf = i == 0;
            if (c.isTrusted()) break;
            // An OCSP responder certificate carrying id-pkix-ocsp-nocheck needs no revocation data
            // of its own — RFC 6960 clause 4.2.2.2.1 says so, which is why computeS33() skips these
            // certificates as well. Counting one here set the deadline to its expiry, and since
            // responder certificates are deliberately short-lived, that expiry is usually in the
            // past: a document preserved minutes ago reported "ihned" because a PostSignum
            // responder certificate had run out in March.
            //
            // Tested after the anchor rules, not before them, so that skipping the certificate
            // never turns into walking past the end of the chain.
            boolean noCheck = c.isIdPkixOcspNoCheck();
            // Self-signed certs in this position are root CAs that DSS did not flag as
            // isTrusted() in the revocation-data chain context; treat them as anchors.
            if (c.isSelfSigned()) {
                if (!noCheck && c.getNotAfter() != null && !hasUsableEmbeddedRevocation(c, poe)) {
                    min = earlier(min, deadlineFor(c, role, leaf, hasEmbeddedRevocation(c)));
                }
                break;
            }
            if (noCheck) continue;
            if (c.getNotAfter() == null) continue;
            if (hasUsableEmbeddedRevocation(c, poe)) continue;
            min = earlier(min, deadlineFor(c, role, leaf, hasEmbeddedRevocation(c)));
        }
        return min;
    }

    /**
     * The deadline a single certificate imposes, worded so the reader can act on it.
     *
     * @param unusableEmbedded údaj v dokumentu je, jen ho už uznat nelze — věta to musí říct,
     *                         jinak si čtenář najde ten údaj v Rozšířeném přehledu a bude mít
     *                         za to, že si aplikace protiročí
     */
    private static AugmentationDeadline deadlineFor(CertificateWrapper c, ChainRole role,
                                                    boolean leaf, boolean unusableEmbedded) {
        return new AugmentationDeadline(c.getNotAfter().toInstant(),
                "Lhůtu určuje " + certRole(role, leaf, c)
                + ", jehož platnost končí " + formatRevDate(c.getNotAfter())
                + (unusableEmbedded
                        ? ". Revokační informace o něm v dokumentu vložená je, ale uznat ji už"
                          + " nelze, takže dokument je na tom stejně, jako by žádnou nenesl."
                          + " Použitelnou už po expiraci zpravidla nebude kde vzít."
                        : ". Do dokumentu k němu dosud nejsou vloženy revokační informace, a po expiraci"
                          + " už je zpravidla nebude kde vzít."));
    }

    private static AugmentationDeadline earlier(AugmentationDeadline a, AugmentationDeadline b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.when().isBefore(a.when()) ? b : a;
    }

    /** True when at least one CRL/OCSP for this cert is embedded in the document. */
    // ── "Průběh ověřování" block ─────────────────────────────────────────────────────────────

    /**
     * Assembles the record of how this verification ran: what the verdict was anchored to,
     * what revocation information existed and whether it could count, and under which settings.
     *
     * <p>The revocation entries are grouped by whether they are usable under the rules this run
     * applied — not by what DSS internally selected, which the diagnostic data does not report.
     * The distinction matters to the reader for the same reason it matters to DSS, so the lines
     * name the disqualifying property explicitly rather than just omitting the entry.
     */
    private ValidationCourse buildValidationCourse(String sigId, DiagnosticData dd,
                                                    SimpleReport simpleReport, AppSettings settings,
                                                    Instant provenExistence,
                                                    List<TimestampEntry> timestamps,
                                                    List<SkippedRevocation> skippedRevocations,
                                                    int strayPrefixBytes, String detailedReportXml) {
        SignatureWrapper sw = dd.getSignatureById(sigId);
        if (sw == null) return null;

        Instant validationTime = simpleReport.getValidationTime() != null
                ? simpleReport.getValidationTime().toInstant() : null;
        Instant dssBst = simpleReport.getBestSignatureTime(sigId) != null
                ? simpleReport.getBestSignatureTime(sigId).toInstant() : null;

        // What proves the earlier existence, and why DSS may not have anchored to it.
        String provenNote = null;
        String divergenceNote = null;
        if (provenExistence != null) {
            TimestampEntry source = timestamps.stream()
                    .filter(TimestampEntry::passed)
                    .filter(e -> provenExistence.equals(e.time()))
                    .findFirst().orElse(null);
            provenNote = source == null
                    ? evidenceRecordProofLabel(sigId, dd, simpleReport, provenExistence)
                    : null;
            if (provenNote == null) provenNote = tsKindLabel(source);
            // A second of slack: DSS reports the same instant with lower precision in places.
            if (dssBst != null && dssBst.isAfter(provenExistence.plusSeconds(1))) {
                divergenceNote = describeBstDivergence(simpleReport, sigId, validationTime, dssBst);
            }
        }

        // Který údaj se má nazývat využitým, rozhoduje chooseUsedRevocation — táž volba, jakou
        // pojmenovává Rozšířený přehled. Dokud se tady bral prostě první použitelný v pořadí
        // seznamu, znělo souvětí u „PAdES B-LT viditelny PS.pdf" jednou o vložené odpovědi ze
        // 14. 1. 2026 („revokován není") a podruhé o stažené z 24. 8. 2026 („revokován 28. 4. 2026"),
        // podle toho, v jakém pořadí data dorazila. Dvě karty téhož běhu si pak protiřečily.
        // Padl závěr kladně? Pak se o nějaký revokační údaj opřel a výčet ho nesmí prohlásit
        // za nepoužitelný — viz poslední větev chooseUsedRevocation.
        boolean conclusionPositive =
                Indication.TOTAL_PASSED == simpleReport.getIndication(sigId);
        ValidationCourse.PrimaryRevocation primary = primaryRevocation(sw, dd, detailedReportXml,
                provenExistence != null ? provenExistence : dssBst, settings, conclusionPositive);

        List<ValidationCourse.RevocationLine> used        = new ArrayList<>();
        List<ValidationCourse.RevocationLine> notDecisive = new ArrayList<>();
        List<ValidationCourse.RevocationLine> rejected    = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> notApplicable = new ArrayList<>();

        // O který údaj se závěr opřel u *každého* certifikátu, ne jen u podpisového. Bez toho
        // se výčet nedal rozdělit na „využita" a „nevyužita": {@link #primaryRevocation} mluví
        // o podpisovém certifikátu, kdežto řádky výčtu jsou i o vydavatelích a o razítkách.
        java.util.Map<String, String> decisiveByCert = usedRevocationIdByCertificate(
                dd, detailedReportXml, settings.isAcceptExpiredRevocationSources(),
                settings.isAcceptNewerOcsp(), postExpiryRevocationAccepted(settings),
                conclusionPositive && sw.getSigningCertificate() != null
                        ? java.util.Set.of(sw.getSigningCertificate().getId())
                        : java.util.Set.of());

        List<CustomCrlStore.Entry> customCrls = CustomCrlStore.listCrls();
        boolean customCrlDecisive = false;
        boolean postExpiryPresent = false;
        boolean fetchedOnline     = false;
        // Výsledek dotazu na stav revokace podpisového certifikátu, odděleně od ostatních:
        // text v „Průběhu ověřování" o něm mluví adresně a poznávat ho podle jména v řádcích
        // seznamů by se rozbilo při první změně formulace.
        CertificateWrapper signingCert = sw.getSigningCertificate();
        String scUsed = null, scUnusable = null, scMissing = null;
        boolean scHasEmbedded = false;
        ValidationCourse.RevocationFact scFact = null, tsFact = null, scBadFact = null;
        // Vložený údaj o podpisovém certifikátu — bez ohledu na to, zda se ho podařilo použít.
        // Doplněno 8. 9. 2026: záložka Digitální kontinuita o něm mluví ve faktech („vloženy jsou
        // revokační informace v podobě …“) a dosud na to brala scFact / scBadFact. První je
        // u prvku, který neobstál, prázdný, a druhý drží *první odmítnutý* záznam v pořadí,
        // v jakém data dorazila — u „priklad6_17.pdf“ jednou vloženou odpověď OCSP, podruhé CRL
        // stažené on-line. Fakta pak tvrdila „CRL seznam“ o dokumentu, který nese OCSP.
        ValidationCourse.RevocationFact scEmbeddedFact = null;
        // Přísné čtení téhož údaje. Rozdíl proti běžnému běhu říká, které pravidlo muselo být
        // uvolněno, aby se údaj směl použít — a právě to má souvislý text čtenáři sdělit.
        AppSettings strict = new AppSettings();
        // Prošla tomuhle běhu volba podrobného protokolu DSS? Na tom stojí odpověď na otázku,
        // jestli se stav revokace vůbec zjistil — výběr níž totiž umí propadnout na náhradní údaj
        // a ten pak vypadá jako podklad závěru, ačkoli závěr žádný není.
        boolean selectedRevocationUnusable = false;
        if (signingCert != null) {
            String selectedId = selectedRevocationId(detailedReportXml, signingCert.getId());
            for (CertificateRevocationWrapper rev : signingCert.getCertificateRevocationData()) {
                if (!rev.getId().equals(selectedId)) continue;
                selectedRevocationUnusable = revocationUnusableReason(signingCert, rev,
                        settings.isAcceptExpiredRevocationSources(), settings.isAcceptNewerOcsp(),
                        revocationIssuerHasProofInValidity(rev, sw)) != null;
                break;
            }
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        // The signing chain comes first; the rest belong to time-stamps.
        boolean signingChain = true;
        for (List<CertificateWrapper> chain : revocationRelevantChains(sigId, dd, sw)) {
            boolean firstOfChain = true;
            boolean firstChainCert = true;
            for (CertificateWrapper c : chain) {
                // A chain that ends where it starts leaves every list above empty, and an empty
                // block reads as "revocation was not checked" — which is right, but looks like a
                // defect unless it is said out loud. Only the certificate the signature itself
                // rests on is worth that sentence: further up the chain, being a trust anchor is
                // the ordinary state of affairs, and a TSU certificate the Trusted List names
                // directly is so common that saying it for every time-stamp would bury the case
                // the reader actually came to understand.
                if (signingChain && firstOfChain && (c.isTrusted() || c.isSelfSigned())) {
                    if (seen.add(c.getId())) notApplicable.add(describeTrustAnchorLeaf(c));
                }
                firstOfChain = false;
                if (c.isSelfSigned()) break;      // root — validated through the trust anchor
                if (c.isTrusted()) break;          // trust anchor — no revocation check applies
                if (!seen.add(c.getId())) continue;
                if (c.isIdPkixOcspNoCheck()) continue; // responder cert: id-pkix-ocsp-nocheck

                List<CertificateRevocationWrapper> entries = c.getCertificateRevocationData();
                List<SkippedRevocation> skippedForCert = skippedRevocations.stream()
                        .filter(sr -> c.getId().equals(sr.certId()))
                        .collect(java.util.stream.Collectors.toList());

                boolean isSigningCert = signingCert != null && signingCert.getId().equals(c.getId());
                boolean firstOfCertChain = firstChainCert;
                firstChainCert = false;

                if (entries.isEmpty() && skippedForCert.isEmpty()) {
                    String line = c.getReadableCertificateName() + " — " + describeMissingReason(c);
                    missing.add(line);
                    if (isSigningCert) scMissing = line;
                    continue;
                }
                // Where the usable entries came from decides what the "Způsob ověření" block may
                // promise about other validators, so it is recorded as a fact rather than read
                // back out of the prose lines below.
                //
                // Per certificate, not per entry: a certificate often has both an embedded OCSP
                // and a CRL fetched now, and the second one changes nothing — a reader with only
                // the document still has what the verdict needs. Only a certificate whose sole
                // usable revocation came from outside makes the verdict depend on the outside.
                boolean embeddedHere = false;
                boolean onlineHere   = false;
                boolean customHere   = false;
                for (CertificateRevocationWrapper rev : entries) {
                    // Údaj, který vznikl až po skončení platnosti certifikátu, o němž vypovídá.
                    // Počítá se před tříděním na použitelné a nepoužitelné, a to schválně: to
                    // třídění je pohled AnEP, kdežto ústupek uděluje zadané datum uvnitř DSS.
                    // Měřeno — u podpisu, který nesla post-expirační CRL hlásící odvolání, byl
                    // týž záznam v `unused`, takže počítání z použitelných ústupek zamlčelo.
                    if (rev.getThisUpdate() != null && c.getNotAfter() != null
                            && rev.getThisUpdate().after(c.getNotAfter())) {
                        postExpiryPresent = true;
                    }
                    // Přes všechny záznamy, ne jen přes ten první: pořadí stažených údajů je
                    // náhodné a odpověď na otázku „nese dokument o tomhle certifikátu vůbec
                    // něco" se s ním měnit nesmí.
                    if (isSigningCert && rev.isInternalRevocationOrigin()) {
                        scHasEmbedded = true;
                        // První vložený v pořadí; víc jich k témuž certifikátu bývá zřídka a pro
                        // větu „vloženy jsou revokační informace v podobě …“ stačí jeden.
                        if (scEmbeddedFact == null) {
                            scEmbeddedFact = revocationFact(rev, customCrls, strict, c,
                                    revocationIssuerHasProofInValidity(rev, sw));
                        }
                    }
                    String reason = revocationUnusableReason(c, rev,
                            settings.isAcceptExpiredRevocationSources(),
                            settings.isAcceptNewerOcsp(),
                            revocationIssuerHasProofInValidity(rev, sw));
                    // Týž údaj nesmí být naráz „využita" v odstavci a „nedala se využít" ve výčtu
                    // pod ním. Ptáme se proto znovu, jen bez té jediné námitky — ostatní kontroly
                    // (neznámý stav, expirovaný vydavatel už v době vydání, post-expirace) platí
                    // dál, protože se ptají na něco jiného.
                    if (reason != null && primary != null && primary.id().equals(rev.getId())
                            && reason.startsWith("vydavateli od té doby")) {
                        reason = revocationUnusableReason(c, rev,
                                settings.isAcceptExpiredRevocationSources(),
                                settings.isAcceptNewerOcsp(), true);
                    }
                    String line = describeRevocationEntry(c, rev, customCrls);
                    if (reason != null) {
                        rejected.add(revocationLine(line + " — nedala se využít: " + reason,
                                rev, c));
                        if (isSigningCert && scUnusable == null) {
                            scUnusable = line + " — " + reason;
                            scBadFact = revocationFact(rev, customCrls, strict, c,
                                    revocationIssuerHasProofInValidity(rev, sw));
                        }
                        continue;
                    }
                    boolean isChosen = primary != null && primary.id().equals(rev.getId());
                    // Přípustnost a využití nejsou totéž. Údaj může projít všemi pravidly a přesto
                    // na verdikt nedosáhnout, protože proces nemá krok, ve kterém by na něm
                    // záleželo — tak dopadne stažená odpověď o odvolání u podpisu, který přísný
                    // režim uzavřel podle údaje vloženého v dokumentu. Dokud výčet obojí sléval
                    // pod jeden nadpis „přípustné podle standardů", četl to čtenář jako „bylo
                    // použito" a pak marně hledal, proč z toho nic neplyne.
                    boolean decisive = isSigningCert && primary != null
                            ? isChosen
                            : rev.getId().equals(decisiveByCert.get(c.getId()));
                    if (decisive) used.add(revocationLine(line + " — využita", rev, c));
                    else          notDecisive.add(revocationLine(line + " — nevyužita", rev, c));
                    if (isSigningCert && (isChosen || (primary == null && scUsed == null))) {
                        scUsed = line;
                        scFact = revocationFact(rev, customCrls, strict, c,
                                revocationIssuerHasProofInValidity(rev, sw));
                    } else if (!signingChain && firstOfCertChain && tsFact == null) {
                        tsFact = revocationFact(rev, customCrls, strict, c,
                                revocationIssuerHasProofInValidity(rev, sw));
                    }
                    if (rev.isInternalRevocationOrigin())              embeddedHere = true;
                    else if (isFromCustomCrlStore(rev, customCrls))    customHere   = true;
                    else                                              onlineHere   = true;
                }
                if (!embeddedHere) {
                    if (customHere) customCrlDecisive = true;
                    if (onlineHere) fetchedOnline     = true;
                }
                for (SkippedRevocation sr : skippedForCert) {
                    // Sběrač zaznamenává *každou* post-expirační OCSP odpověď, kterou strategie
                    // stáhla — i tu, kterou nakonec DSS dostalo. Taková odpověď je pak v
                    // DiagnosticData a vypsala se výš i se správným důvodem; druhý řádek o témž
                    // údaji by čtenáři přidal jen zmatek.
                    if (alreadyListed(entries, sr)) continue;
                    // A důvod, který se sem píše: strategie odloží OCSP odpověď právě a jen tehdy,
                    // když je post-expirační — tím to je dané. Dřív tu stálo „přednost dostala CRL
                    // z vlastního úložiště“, což byl dohad o tom, co se stalo potom: záložní CRL
                    // se nemusí najít vůbec, natož ve vlastním úložišti, a věta pak odkazovala na
                    // seznam, který v celém rozboru nikde není.
                    String line = c.getReadableCertificateName() + " ← OCSP získané online, vydáno "
                            + formatRevDate(sr.thisUpdate()) + ", "
                            + statusText(sr.status(), sr.revocationDate())
                            + " — nedala se využít: vydáno až po expiraci certifikátu,"
                            + " kterého se týká";
                    // Bez identifikátoru: tahle odpověď se do DiagnosticData nedostala, protože ji
                    // strategie odložila dřív, než ji DSS uvidělo. Aplikace tedy nedrží její bajty
                    // a nemá co ukázat ani uložit — řádek zůstane bez odkazů.
                    rejected.add(new ValidationCourse.RevocationLine(line, null, true,
                            certSerialOf(c)));
                    if (isSigningCert && scUnusable == null) scUnusable = line;
                }
            }
            signingChain = false;
        }

        // Trust anchor, active stores, enabled relaxations and the decisive ones among them used
        // to be listed here as "Podmínky ověřování". They moved to the "Způsob ověření" block,
        // which says the same things and then draws the conclusion the reader came for — what
        // other validators will make of the document. Listing them in both places meant reading
        // the same facts twice, the second time with the point attached.
        //
        // What stays is the note below: it is not a setting anyone chose but something found in
        // the file, and it belongs where the course of the verification is described.
        List<String> context = new ArrayList<>();
        if (strayPrefixBytes > 0) {
            // Czech number agreement, the same rule CzechPlural spells out for the screens; that
            // class lives in the UI package and this one must not reach into it. The relative
            // pronoun has to follow the count too, hence both halves in one string.
            String bytes = strayPrefixBytes + (strayPrefixBytes == 1 ? " bajt navíc, který"
                    : strayPrefixBytes <= 4 ? " bajty navíc, které" : " bajtů navíc, které");
            context.add("Před hlavičkou PDF leží " + bytes + " nekryje žádný podpis; ověřen byl"
                    + " dokument od hlavičky dál, tak jak jej otevírají prohlížeče PDF.");
        }

        Instant customTime = settings.isUseCustomValidationTime() && settings.getCustomValidationTime() != null
                ? settings.getCustomValidationTime().atZone(java.time.ZoneId.systemDefault()).toInstant()
                : null;

        return new ValidationCourse(validationTime, dssBst, provenExistence, provenNote,
                divergenceNote, customTime, used, notDecisive, rejected, missing,
                notApplicable, context,
                scHasEmbedded, customCrlDecisive, fetchedOnline, postExpiryPresent,
                primary, scUsed, scUnusable, scMissing,
                scFact, tsFact, scBadFact, scEmbeddedFact, selectedRevocationUnusable);
    }

    /**
     * Why no revocation status was established for a certificate that sits at the head of its own
     * chain — the case where the reader finds the revocation section empty and suspects the check
     * never ran.
     *
     * <p>It genuinely never ran, and ETSI EN 319 102-1 does not ask it to: a trust anchor's
     * standing comes from the Trusted List entry, which is also what withdraws it. What puts an
     * end-entity certificate in that position is the Trusted List naming it as a service's own
     * digital identity — the seal of a qualified validation service (Svctype QESValidation/Q) and
     * many a TSU certificate are listed exactly that way. That service is named here, matched by
     * digital identity rather than guessed from the service type, because the same certificate
     * also matches the CA/QC service that issued it and naming that one would mislead.
     */
    private static String describeTrustAnchorLeaf(CertificateWrapper c) {
        String name = c.getReadableCertificateName();
        if (!c.isTrusted()) {
            return name + " — certifikát je podepsán sám sebou, revokační záznam pro něj"
                    + " nikdo nevydává.";
        }
        TrustServiceWrapper own = null;
        for (TrustServiceWrapper ts : c.getTrustServices()) {
            CertificateWrapper sdi = ts.getServiceDigitalIdentifier();
            if (sdi != null && c.getId().equals(sdi.getId())) {
                own = ts;
                break;
            }
        }
        if (own == null) {
            return name + " — certifikát je sám kotvou důvěry (je ve vlastním úložišti"
                    + " důvěryhodných certifikátů). Stav revokace se u kotvy důvěry nezjišťuje.";
        }
        String svc = own.getServiceNames().isEmpty() ? null : own.getServiceNames().get(0);
        // The service's own country code is often absent in the diagnostic data; the list the
        // entry came from always knows which country it is, and that is the interesting part.
        String cc = own.getCountryCode();
        if ((cc == null || cc.isBlank()) && own.getTrustedList() != null) {
            cc = own.getTrustedList().getCountryCode();
        }
        return name + " — certifikát je sám kotvou důvěry: v důvěryhodném seznamu"
                + (cc != null && !cc.isBlank() ? " " + cc : "")
                + " je uveden jako identita služby"
                + (svc != null ? " „" + svc + "“" : "")
                + ". Stav revokace se u kotvy důvěry nezjišťuje — o jejím vyřazení rozhoduje"
                + " stav služby v důvěryhodném seznamu, nikoli CRL nebo OCSP.";
    }

    /** Signing chain plus every time-stamp chain relevant to this signature. */
    private static List<List<CertificateWrapper>> revocationRelevantChains(String sigId,
                                                                           DiagnosticData dd,
                                                                           SignatureWrapper sw) {
        List<List<CertificateWrapper>> chains = new ArrayList<>();
        chains.add(sw.getCertificateChain());
        java.util.Set<String> sigTsIds = new java.util.HashSet<>();
        for (TimestampWrapper ts : sw.getTimestampList()) {
            sigTsIds.add(ts.getId());
            chains.add(ts.getCertificateChain());
        }
        for (TimestampWrapper ts : dd.getTimestampList()) {
            if (sigTsIds.contains(ts.getId())) continue;
            if (!ts.getType().isDocumentTimestamp() && !ts.getType().isContainerTimestamp()) continue;
            if (ts.getTimestampedSignatures().stream().noneMatch(s -> sigId.equals(s.getId()))) continue;
            chains.add(ts.getCertificateChain());
        }
        return chains;
    }

    /** "podpisové časové razítko od …" — what kind of token proves the earlier existence. */
    /**
     * Popis archivního razítka evidenčního záznamu, které dokládá právě {@code proven} — nebo
     * null, když ten okamžik žádný záznam nad prvkem nedokládá.
     */
    private static String evidenceRecordProofLabel(String sigId, DiagnosticData dd,
                                                   SimpleReport simpleReport, Instant proven) {
        if (!proven.equals(earliestEvidenceRecordProof(sigId, dd, simpleReport))) return null;
        for (EvidenceRecordWrapper er : dd.getEvidenceRecords()) {
            if (er.getCoveredSignatures().stream().noneMatch(s -> sigId.equals(s.getId()))) {
                continue;
            }
            for (TimestampWrapper ts : er.getTimestampList()) {
                if (ts.getProductionTime() == null
                        || !proven.equals(ts.getProductionTime().toInstant())) continue;
                CertificateWrapper tsa = ts.getSigningCertificate();
                String issuer = tsa != null ? tsa.getReadableCertificateName() : null;
                return "archivní časové razítko evidenčního záznamu"
                        + (issuer != null && !issuer.isBlank() ? " od " + issuer : "");
            }
        }
        return "archivní časové razítko evidenčního záznamu";
    }

    /** Druh razítka slovy i s vydavatelem; veřejné kvůli poznámce v AdvisorView. */
    public static String tsKindLabel(TimestampEntry entry) {
        if (entry == null) return "časové razítko";
        String kind = switch (entry.tsKind() != null ? entry.tsKind() : "sig") {
            case "doc"   -> "dokumentové časové razítko";
            case "vri", "vri_bare" -> "časové razítko v dokumentu (VRI)";
            case "content" -> "obsahové časové razítko";
            default      -> "podpisové časové razítko";
        };
        return entry.issuer() != null && !entry.issuer().isBlank()
                ? kind + " od " + entry.issuer() : kind;
    }

    /**
     * Explains why DSS kept the anchoring time at the validation time although a validated
     * time-stamp proves an earlier existence: the process stopped before the step that lowers it.
     */
    private static String describeBstDivergence(SimpleReport simpleReport, String sigId,
                                                 Instant validationTime, Instant dssBst) {
        boolean atValidationTime = validationTime != null
                && !dssBst.isBefore(validationTime.minusSeconds(1));
        SubIndication si = simpleReport.getSubIndication(sigId);
        StringBuilder sb = new StringBuilder();
        sb.append(atValidationTime
                ? "DSS pracoval s aktuálním časem, na čas razítka rozhodný okamžik nesnížil."
                : "DSS pracoval s jiným rozhodným okamžikem než je čas razítka.");
        sb.append(" Ověřování skončilo dřív, než na tento krok došlo");
        if (si != null) {
            sb.append(" (").append(si).append(")");
        }
        sb.append(". Samotné razítko přitom bylo ověřeno jako platné, takže existenci podpisu"
                + " v uvedeném čase prokazuje.");
        return sb.toString();
    }

    /**
     * Rozloží jeden revokační údaj na části, ze kterých souvislý text v „Průběhu ověřování"
     * poskládá větu — odkud údaj je, kdy vznikl a čemu vděčí za to, že se směl použít.
     *
     * <p>Uvolněné pravidlo se pozná srovnáním: {@code strict} je čerstvé {@link AppSettings},
     * tedy ověřování dle standardů. Když s ním údaj neobstojí a s běžným nastavením ano, rozhodl
     * o tom ústupek — a důvod z přísného čtení říká který.
     */
    private static ValidationCourse.RevocationFact revocationFact(
            CertificateRevocationWrapper rev, List<CustomCrlStore.Entry> customCrls,
            AppSettings strict, CertificateWrapper cert, boolean hasProofOfExistence) {
        String strictReason = revocationUnusableReason(cert, rev,
                strict.isAcceptExpiredRevocationSources(), strict.isAcceptNewerOcsp(),
                hasProofOfExistence);
        String relaxed = null;
        if (strictReason != null) {
            // Dvě vady vydavatele, ne jedna. „vydavatel měl" je ta, kdy už neplatil v okamžiku,
            // kdy odpověď podepsal — taková odpověď nevznikla v pořádku nikdy. Ta druhá vzniká
            // bezvadně a zestárne teprve tím, že vydavateli skončí platnost a nic to nepřeklene;
            // splynout nesmějí, protože každá se čtenáři vysvětluje jinak.
            relaxed = strictReason.startsWith("vydáno až po expiraci") ? "postExpiry"
                    : strictReason.startsWith("vydavatel měl") ? "expiredIssuer"
                    : strictReason.startsWith("vydavateli od té doby") ? "expiredIssuerNoProof"
                    : "other";
        }
        String origin = rev.isInternalRevocationOrigin() ? "document"
                : isFromCustomCrlStore(rev, customCrls) ? "store" : "online";
        return new ValidationCourse.RevocationFact(
                rev.getRevocationType() == RevocationType.OCSP,
                origin,
                rev.getThisUpdate() != null ? rev.getThisUpdate().toInstant() : null,
                rev.getStatus() == CertificateStatus.REVOKED,
                rev.getStatus() == CertificateStatus.UNKNOWN,
                rev.getRevocationDate() != null ? rev.getRevocationDate().toInstant() : null,
                relaxed);
    }

    /**
     * Řádek výčtu i s tím, co potřebují odkazy „Ukaž“ a „Ulož“ — identifikátor údaje a sériové
     * číslo certifikátu, na jehož záznamu se má prohlížeč CRL otevřít.
     */
    private static ValidationCourse.RevocationLine revocationLine(
            String text, CertificateRevocationWrapper rev, CertificateWrapper cert) {
        return new ValidationCourse.RevocationLine(text, rev.getId(),
                rev.getRevocationType() == RevocationType.OCSP, certSerialOf(cert));
    }

    /**
     * Sériové číslo certifikátu jako číslo, ne jako řetězec.
     *
     * <p>{@code getSerialNumber()} vrací dekadický zápis; prohlížeč CRL porovnává čísla, protože
     * tentýž údaj se v seznamech zapisuje jednou dekadicky a jednou šestnáctkově, s vedoucí nulou
     * i bez ní. Porovnání řetězců by na tom padlo.
     */
    private static java.math.BigInteger certSerialOf(CertificateWrapper cert) {
        try {
            String s = cert.getSerialNumber();
            return s != null && !s.isBlank() ? new java.math.BigInteger(s.trim()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** One line describing a revocation entry: source, type, issue time, status. */
    private static String describeRevocationEntry(CertificateWrapper cert,
                                                   CertificateRevocationWrapper rev,
                                                   List<CustomCrlStore.Entry> customCrls) {
        StringBuilder sb = new StringBuilder(cert.getReadableCertificateName());
        sb.append(" ← ").append(revTypeName(rev.getRevocationType()));
        if (rev.isInternalRevocationOrigin()) {
            sb.append(" z dokumentu");
            appendCrlIssuer(sb, rev);
        } else if (isFromCustomCrlStore(rev, customCrls)) {
            sb.append(" z vlastního úložiště");
            appendCrlIssuer(sb, rev);
        } else {
            sb.append(" získané online");
            List<CertificateWrapper> issuerChain = rev.getCertificateChain();
            if (!issuerChain.isEmpty()) {
                sb.append(" od ").append(issuerChain.get(0).getReadableCertificateName());
            } else {
                appendCrlIssuer(sb, rev);
            }
        }
        sb.append(", vydáno ").append(formatRevDate(rev.getThisUpdate()));
        sb.append(", ").append(statusText(rev.getStatus(), rev.getRevocationDate()));
        return sb.toString();
    }

    /**
     * Připíše „od <CN vydavatele>“ u seznamu CRL, který jméno vydavatele jinak nenese.
     *
     * <p>U staženého údaje je vydavatel ve větě už z řetězu certifikátů; u seznamu vloženého
     * v dokumentu nebo vzatého z vlastního úložiště tam ale nebyl vůbec, takže řádek říkal
     * „CRL z dokumentu, vydáno …“ a čtenář se z něj nedozvěděl, čí seznam to je. U odpovědi OCSP
     * se nedoplňuje: tu vydává respondér, ne certifikační autorita, a jeho jméno by ve větě
     * o stavu certifikátu odpovídalo na jinou otázku.
     *
     * <p>Jen CN, ne celé DN — plné jméno vydavatele je na jeden řádek výčtu příliš dlouhé a je
     * k vidění v prohlížeči seznamu, který si čtenář otevře odkazem „Ukaž“.
     */
    private static void appendCrlIssuer(StringBuilder sb, CertificateRevocationWrapper rev) {
        if (rev.getRevocationType() != RevocationType.CRL) return;
        String cn = crlIssuerCn(rev);
        if (cn != null && !cn.isBlank()) sb.append(" od ").append(cn);
    }

    /**
     * CN vydavatele seznamu CRL — nejprve z certifikátu, kterým je seznam podepsán, a když ho
     * diagnostika nezná, ze jména vydavatele uvnitř seznamu samotného.
     */
    private static String crlIssuerCn(CertificateRevocationWrapper rev) {
        CertificateWrapper signer = rev.getSigningCertificate();
        if (signer != null) {
            String name = signer.getReadableCertificateName();
            if (name != null && !name.isBlank()) return name;
        }
        byte[] der = rev.getBinaries();
        if (der == null || der.length == 0) return null;
        try {
            org.bouncycastle.asn1.x500.X500Name issuer =
                    new org.bouncycastle.cert.X509CRLHolder(der).getIssuer();
            org.bouncycastle.asn1.x500.RDN[] cn =
                    issuer.getRDNs(org.bouncycastle.asn1.x500.style.BCStyle.CN);
            if (cn.length == 0) return null;
            return org.bouncycastle.asn1.x500.style.IETFUtils.valueToString(
                    cn[0].getFirst().getValue());
        } catch (Exception e) {
            // Seznam, který se nepodaří rozebrat, si o vydavatele nezaslouží hádku — řádek
            // zůstane bez něj, tak jak byl dosud.
            return null;
        }
    }

    private static String statusText(CertificateStatus status, java.util.Date revocationDate) {
        if (status == CertificateStatus.REVOKED) {
            // S časem a v obou pásmech: u odvolání, ke kterému došlo týž den jako podpis nebo
            // vydání vloženého údaje, rozhoduje o závěru pořadí v rámci jednoho dne a samotné
            // datum ho zamlčí. Formát je týž, jaký mají ostatní časy v aplikaci.
            return revocationDate != null
                    ? "certifikát revokován " + formatRevDateTime(revocationDate)
                    : "certifikát revokován";
        }
        if (status == CertificateStatus.UNKNOWN) return "stav neznámý";
        return "certifikát nebyl revokován";
    }

    /**
     * Why this entry cannot carry the verdict, or null when it can. Mirrors the tests in
     * {@link #bestStandardsUsableExternalRevocation}, but keeps the relaxed settings visible:
     * with them switched on the entry does count, and the line says so.
     */
    private static String revocationUnusableReason(CertificateWrapper cert,
                                                    CertificateRevocationWrapper rev,
                                                    AppSettings settings) {
        return revocationUnusableReason(cert, rev,
                settings.isAcceptExpiredRevocationSources(), settings.isAcceptNewerOcsp());
    }

    /**
     * Táž otázka s ústupky zadanými přímo — aby šla položit i přísně, tedy s obojím vypnutým,
     * bez ohledu na to, co je právě zaškrtnuté. Používá {@link #chooseUsedRevocation}, když hledá
     * údaj, který obstojí bez jakékoli úlevy.
     */
    private static String revocationUnusableReason(CertificateWrapper cert,
                                                    CertificateRevocationWrapper rev,
                                                    boolean acceptExpiredSources,
                                                    boolean acceptNewer) {
        // Bez znalosti archivního razítka; volající, kteří ji mají, ať použijí přetížení níž.
        return revocationUnusableReason(cert, rev, acceptExpiredSources, acceptNewer, true);
    }

    /**
     * Zda vydavateli údaje mezitím skončila platnost a není nad ním archivní časové razítko,
     * které by to překlenulo — tedy to, na čem v DSS padá {@code LTV_ISCKNR}.
     *
     * <p>Je to jiná otázka než ta, kterou klade {@link #revocationUnusableReason} o pár řádků výš.
     * Ta se ptá, jestli vydavatel platil <b>v době, kdy odpověď vydal</b>; když ano, je odpověď
     * jako materiál v pořádku a DSS ji přijme (RAC projde). Tahle se ptá, jestli jde <b>dnes</b>
     * ještě prokázat, že tehdy byl důvěryhodný — a bez důkazu existence to nejde. Rozdíl není
     * akademický: potkáno na „příklad kvalifikovaného podpisu.pdf", kde odpověď z 21. 11. 2018
     * podepsal respondér platný do 2. 12. 2018. V době vydání tedy platil, RAC prošel, a přesto
     * se o odpověď závěr opřít nemohl — verdikt skončil na INDETERMINATE/OUT_OF_BOUNDS_NO_POE
     * a vyvolal situaci 5. Dokud se tyhle dvě otázky nerozlišily, hlásila Detailní rozbor
     * „DSS tyto revokační informace použil" a Analýza je vedl mezi použitelnými.
     *
     * <p>Podmínky jsou schválně tytéž jako u {@code expiredEmbeddedRevocation}, tedy u situace 5:
     * jen údaj <b>vložený v dokumentu</b>, jen u certifikátu, který už sám expiroval (u dosud
     * platného se dají stáhnout čerstvá data, takže na starém nic nestojí), a jen když vydavatel
     * není kotvou důvěry. Stažené údaje s toutéž vadou hlásí situace 46 a její vlastní predikát;
     * sem se nepřidávají, aby dvě pravidla neříkala o jednom údaji každé něco jiného.
     */
    private static boolean issuerExpiredWithoutProof(CertificateWrapper cert,
                                                      CertificateRevocationWrapper rev,
                                                      boolean hasProofOfExistence,
                                                      boolean acceptExpiredSources) {
        if (hasProofOfExistence || acceptExpiredSources) return false;
        if (!rev.isInternalRevocationOrigin()) return false;
        if (isTrustedRevocationIssuer(rev)) return false;
        Instant now = Instant.now();
        if (cert.getNotAfter() == null || !cert.getNotAfter().toInstant().isBefore(now)) return false;
        Instant respExpiry = chainExpiry(rev.getCertificateChain());
        return respExpiry != null && respExpiry.isBefore(now);
    }

    /** Táž otázka, ale s vědomím, jestli nad údajem stojí archivní časové razítko. */
    private static String revocationUnusableReason(CertificateWrapper cert,
                                                    CertificateRevocationWrapper rev,
                                                    boolean acceptExpiredSources,
                                                    boolean acceptNewer,
                                                    boolean hasProofOfExistence) {
        if (rev.getStatus() == CertificateStatus.UNKNOWN) {
            return "respondér stav certifikátu nezná";
        }
        if (issuerExpiredWithoutProof(cert, rev, hasProofOfExistence, acceptExpiredSources)) {
            return "vydavateli od té doby skončila platnost a bez archivního časového razítka"
                    + " už nelze prokázat, že byl v době vydání odpovědi důvěryhodný";
        }
        if (rev.getThisUpdate() == null) {
            return "chybí údaj o době vydání";
        }
        Instant respExpiry = chainExpiry(rev.getCertificateChain());
        if (respExpiry != null && respExpiry.isBefore(rev.getThisUpdate().toInstant())
                && !isTrustedRevocationIssuer(rev)
                && !acceptExpiredSources) {
            return "vydavatel měl v době vydání již expirovaný certifikát";
        }
        if (cert.getNotAfter() != null && rev.getThisUpdate().after(cert.getNotAfter())) {
            boolean stillSpeaks = postExpiryStillSpeaks(cert, rev);
            // Odvolání je i po expiraci přípustné, a to bez ohledu na to, kdy k němu došlo.
            // Námitka proti post-expiračnímu údaji totiž není zákaz jako takový: míří na odpověď
            // „neodvolán". Vydavatel smí podle RFC 5280 přestat expirovaný certifikát na seznamu
            // uvádět, takže jeho nepřítomnost po expiraci nedokazuje nic. RFC 6960 říká totéž
            // o OCSP: stav „good" sám o sobě netvrdí ani to, že odpověď vznikla za platnosti
            // certifikátu, kdežto „revoked" nese revocationTime. Odstranění záznamu umí udělat
            // z „odvolán" jen „chybí", nikdy naopak, takže kladné zjištění je spolehlivé.
            //
            // A právě proto se ta záporná odpověď dá podložit: když údaj doloží, že o tomhle
            // certifikátu ví — expiredCertsOnCRL, archiveCutOff nebo CertHash —, mlčení už není
            // mlčením z nevědomosti. To měří postExpiryStillSpeaks() a je to totéž, čím se řídí DSS.
            //
            // Kdy k odvolání došlo, je otázka jiná — ne jestli se údaj smí použít, ale co z něj
            // plyne pro verdikt. To řeší situace 20 a 21 a věta o rozhodném okamžiku v závěru
            // Analýzy; sem nepatří. Chvíli tu stálo zúžení „jen odvolání po rozhodném okamžiku",
            // jenže to obě otázky slévalo dohromady a v normě pro ně oporu nemá.
            boolean revoked = CertificateStatus.REVOKED.equals(rev.getStatus());
            if (!stillSpeaks && !revoked && !acceptNewer) {
                return "vydáno až po expiraci certifikátu, kterého se týká";
            }
        }
        return null;
    }

    /**
     * Zda odložená OCSP odpověď už mezi záznamy certifikátu je — tedy zda se přes strategii
     * přece jen dostala do DiagnosticData a vypsala se s vlastním důvodem.
     *
     * <p>Porovnává se dobou vydání a typem, protože o víc se opřít nedá: záznam ve sběrači nese
     * jen identifikátor certifikátu, stav a dvě data. Dvě různé OCSP odpovědi k témuž certifikátu
     * s touž vteřinou vydání by byly týž údaj i pro čtenáře.
     */
    private static boolean alreadyListed(List<CertificateRevocationWrapper> entries,
                                          SkippedRevocation sr) {
        for (CertificateRevocationWrapper rev : entries) {
            if (rev.getRevocationType() != RevocationType.OCSP) continue;
            if (rev.getThisUpdate() != null && rev.getThisUpdate().equals(sr.thisUpdate())) {
                return true;
            }
        }
        return false;
    }

    /** True when this CRL is byte-identical to one the user keeps in the custom store. */
    private static boolean isFromCustomCrlStore(CertificateRevocationWrapper rev,
                                                 List<CustomCrlStore.Entry> customCrls) {
        if (rev.getRevocationType() != RevocationType.CRL) return false;
        byte[] der = rev.getBinaries();
        if (der == null) return false;
        for (CustomCrlStore.Entry entry : customCrls) {
            try {
                if (java.util.Arrays.equals(der, entry.crl().getEncoded())) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    private static boolean hasEmbeddedRevocation(CertificateWrapper c) {
        return c.getCertificateRevocationData().stream()
                .anyMatch(ref -> ref.isInternalRevocationOrigin());
    }

    /**
     * Nese dokument k tomuhle certifikátu vložený revokační údaj, o který se <b>dá opřít</b>?
     *
     * <p>Rozdíl proti {@link #hasEmbeddedRevocation} je celý v tom slově. Lhůta pro uchování říká,
     * dokdy je třeba doplnit to, co v dokumentu chybí — a údaj, který uznat nelze, tam chybí
     * stejně jako by tam nebyl vůbec. Dokud se to nerozlišovalo, vycházel certifikát s vloženou,
     * ale nepoužitelnou odpovědí jako pokrytý, lhůta přeskočila na další článek v řetězu a blok
     * „Jak dlouho dokáže AnEP ověřit platnost“ sliboval dobu, kterou dokument bez sítě nedrží.
     *
     * <p>Posuzuje se přísně, bez ústupků: lhůta je odpověď na otázku, co bude třeba doplnit, aby
     * dokument obstál i u toho, kdo žádný ústupek nedělá.
     */
    private static boolean hasUsableEmbeddedRevocation(CertificateWrapper c,
                                                        java.util.Set<String> poe) {
        for (CertificateRevocationWrapper rev : c.getCertificateRevocationData()) {
            if (!rev.isInternalRevocationOrigin()) continue;
            if (revocationUnusableReason(c, rev, false, false, poe.contains(rev.getId())) == null) {
                return true;
            }
        }
        return false;
    }

    /** True when the cert has at least one standards-usable external (online-fetched) revocation. */
    private static boolean hasStandardsUsableExternalRevocation(CertificateWrapper cert) {
        return bestStandardsUsableExternalRevocation(cert) != null;
    }

    /**
     * Like {@link #bestStandardsUsableExternalRevocation} but also accepts in-document
     * (internal) revocation — used as a fallback in S33 step 1 when the cert has revocation
     * data associated with it in the diagnostic report that originates from the document
     * structure (DSS dict, VRI, CMS) yet somehow wasn't caught by {@code hasEmbeddedRevocation}.
     */
    private static CertificateRevocationWrapper bestStandardsUsableRevocation(CertificateWrapper cert) {
        // Try external first (augVerifier will handle it via online fetch).
        CertificateRevocationWrapper ext = bestStandardsUsableExternalRevocation(cert);
        if (ext != null) return ext;
        // Fallback: any standards-usable internal revocation associated with this cert.
        if (cert.getNotAfter() == null) return null;
        for (CertificateRevocationWrapper rev : cert.getCertificateRevocationData()) {
            if (!rev.isInternalRevocationOrigin()) continue;
            if (rev.getStatus() == CertificateStatus.UNKNOWN) continue;
            if (rev.getThisUpdate() == null) continue;
            if (!revocationCoversCertificate(rev, cert.getNotAfter())) continue;
            return rev;
        }
        return null;
    }

    /** Returns the best standards-usable external revocation for the cert (CRL preferred over OCSP,
     *  newer thisUpdate preferred within the same type), or null when none is available.
     *  "Standards-usable" means: thisUpdate within cert validity (or ECO for CRL), not UNKNOWN status,
     *  and the revocation issuer's chain was not already expired at the time of issuance. */
    /**
     * The moment from which the issuer of this revocation token is able to speak about a
     * certificate it covers.
     *
     * <p>Mirrors {@code RevocationHasInformationAboutCertificateCheck.getNotAfterRevoc()} in DSS,
     * because preservation has to judge revocation data by the same rule validation does. Four
     * sources, in the standard's order of precedence: the token's own {@code thisUpdate}, pulled
     * back by {@code expiredCertsOnCRL} (ITU-T X.509, OID 2.5.29.60) or {@code archiveCutOff}
     * (RFC 6960 clause 4.4.4) when the token carries one, and — only when it carries neither —
     * by {@code expiredCertsRevocationInfo}, the grant the Trusted List itself makes to the
     * service (ETSI TS 119 612 clause 5.5.9.4.3).
     *
     * <p>That last one was long missing here. A CA that publishes revocation for certificates
     * after they expire says so in the list rather than in every CRL, so leaving it out made
     * preservation refuse data that validation, correctly, accepts.
     */
    private static java.util.Date revocationKnownSince(CertificateRevocationWrapper rev) {
        java.util.Date since = rev.getThisUpdate();
        if (since == null) return null;
        java.util.Date expiredCertsOnCrl = rev.getExpiredCertsOnCRL();
        java.util.Date archiveCutOff = rev.getArchiveCutOff();
        if (expiredCertsOnCrl != null && expiredCertsOnCrl.before(since)) since = expiredCertsOnCrl;
        if (archiveCutOff != null && archiveCutOff.before(since)) since = archiveCutOff;
        if (expiredCertsOnCrl == null && archiveCutOff == null) {
            CertificateWrapper issuer = rev.getSigningCertificate();
            java.util.Date fromTrustedList = issuer != null
                    ? issuer.getCertificateTSPServiceExpiredCertsRevocationInfo() : null;
            if (fromTrustedList != null && fromTrustedList.before(since)) since = fromTrustedList;
        }
        return since;
    }

    /**
     * Whether this revocation token says anything about a certificate expiring at {@code notAfter}
     * — the question {@code BBB_XCV_REVOC_HAS_CERT_INFO} answers during validation.
     */
    private static boolean revocationCoversCertificate(CertificateRevocationWrapper rev,
                                                       java.util.Date notAfter) {
        if (notAfter == null || rev.getThisUpdate() == null) return false;
        if (rev.isCertHashExtensionPresent() && rev.isCertHashExtensionMatch()) return true;
        java.util.Date since = revocationKnownSince(rev);
        return since != null && !since.after(notAfter);
    }

    private static CertificateRevocationWrapper bestStandardsUsableExternalRevocation(CertificateWrapper cert) {
        if (cert.getNotAfter() == null) return null;
        CertificateRevocationWrapper best = null;
        for (CertificateRevocationWrapper rev : cert.getCertificateRevocationData()) {
            if (rev.isInternalRevocationOrigin()) continue;
            if (rev.getStatus() == CertificateStatus.UNKNOWN) continue;
            if (rev.getThisUpdate() == null) continue;
            Instant respExpiry = chainExpiry(rev.getCertificateChain());
            if (respExpiry != null && respExpiry.isBefore(rev.getThisUpdate().toInstant())) continue;
            if (!revocationCoversCertificate(rev, cert.getNotAfter())) continue;
            if (best == null) {
                best = rev;
            } else if (rev.getRevocationType() == RevocationType.CRL
                    && best.getRevocationType() != RevocationType.CRL) {
                best = rev; // prefer CRL over OCSP
            } else if (rev.getRevocationType() == best.getRevocationType()
                    && rev.getThisUpdate().after(best.getThisUpdate())) {
                best = rev; // prefer newer thisUpdate within same type
            }
        }
        return best;
    }

    /**
     * Does the signature carry a time-stamp that actually fixes best-signature-time? Content and
     * VRI time-stamps are excluded for the reasons given on {@link #canEstablishBestSignatureTime}
     * — counting them here would label the current time as "(čas razítka)" and would let a
     * time-stamp no conformant validator accepts decide S2.
     */
    private boolean hasValidTimestamp(String sigId, DiagnosticData dd,
                                       SimpleReport simpleReport) {
        SignatureWrapper sw = dd.getSignatureById(sigId);
        if (sw == null) return false;
        return sw.getTimestampList().stream()
                .filter(ts -> canEstablishBestSignatureTime(ts, sw, simpleReport))
                .anyMatch(ts -> simpleReport.getIndication(ts.getId()) == Indication.PASSED);
    }

    /** Trusted-List statuses under which a service counts as operating properly, pre- and post-eIDAS. */
    private static final List<String> S43_POSITIVE_STATUSES = List.of(
            "granted", "accredited", "undersupervision", "supervisionincessation");

    /**
     * Situation 43: the signature's decisive proof of existence rests on a time-stamping service
     * that the Trusted List has since withdrawn. Returns the sentence explaining it, or
     * {@code null} when it does not apply.
     *
     * <p>The time a time-stamp states is the time-stamp's own assertion, and nothing else in the
     * document corroborates it. Judging the service that issued it by that same assertion would
     * be circular: whoever held the TSA's key could mint a token at any point in the certificate's
     * lifetime and write into it any instant within that same span. ETSI EN 319 102-1 therefore
     * reads the service status at the moment the token's existence is independently established —
     * its lowest proof of existence, which is either a later archive time-stamp covering it or,
     * failing that, the present.
     *
     * <p>The situation used to enforce that reading itself, downgrading a TOTAL_PASSED verdict the
     * way situation 29 does, because DSS as then configured never saw a sunset date and passed such
     * signatures unconditionally. It no longer has to: {@code TrustConfig} now gives trust anchors
     * the sunset date the Trusted List implies, so Validation Time Sliding starts there and past
     * validation decides on proofs of existence rather than on this application's guess about them.
     * Measured over 1201 signatures, 56 stand on a withdrawn time-stamping service and DSS reaches
     * INDETERMINATE for 55 of them on its own. For the fifty-sixth it reaches TOTAL_PASSED — an
     * LTA-preserved document that was re-stamped in time, exactly the remedy this situation
     * recommends — and the old override marked it down anyway. Hence: DSS decides, S43 explains.
     *
     * <p>What the paragraph above does not say, and used to: that a TOTAL_PASSED verdict proves
     * past validation found a proof of existence predating the withdrawal. Measured 29 August 2026
     * on the 2012 PAdES-LTA — DSS passes it while writing {@code Time-stamp Qualification at lowest
     * POE time = TSA} for the very time-stamp the verdict rests on. A pass proves something else:
     * that the chain reached a trust anchor the withdrawal does not touch, either the issuing CA
     * that kept its own granted status or a root pinned in the user's store. Situation 49 covers
     * exactly that case, so this one keeps to verdicts that are not positive.
     *
     * <p>Four conditions. Two read off the Trusted List entry of the time-stamp's own TSA
     * certificate — the service was in a positive state when the time-stamp was made, and is in
     * none now — and identify a withdrawn time-stamping service. A third requires a trust anchor
     * in that time-stamp's chain whose sunset date has passed, the same datum DSS's own
     * trust-anchor checks read. The fourth requires DSS to have set the best signature time later
     * than this time-stamp, which is what says the withdrawal cost the signature something rather
     * than merely being true of it. The caller adds a fifth: never on a signature DSS passed.
     *
     * <p>Only services of type QTST are consulted. The same TSA certificate is usually listed
     * under a certificate-issuing service as well, and that one has typically stayed granted.
     */
    static String withdrawnTimestampServiceDetail(SignatureWrapper sw,
                                                  java.util.Date bestSignatureTime) {
        if (sw == null) return null;
        TimestampWrapper earliest = null;
        for (TimestampWrapper ts : sw.getTimestampList()) {
            if (ts.getType() != TimestampType.SIGNATURE_TIMESTAMP) continue;
            if (ts.getProductionTime() == null) continue;
            if (earliest == null || ts.getProductionTime().before(earliest.getProductionTime())) {
                earliest = ts;
            }
        }
        if (earliest == null) return null;
        CertificateWrapper tsa = earliest.getSigningCertificate();
        if (tsa == null) return null;

        java.util.Date made = earliest.getProductionTime();
        java.util.Date now = new java.util.Date();
        boolean positiveThen = false, positiveNow = false, anyService = false;
        java.util.Date withdrawnSince = null;
        String statusThen = null, statusNow = null;
        for (var tsp : tsa.getTrustServiceProviders()) {
            for (var svc : tsp.getTrustServices()) {
                String type = tail(svc.getServiceType());
                if (!"QTST".equalsIgnoreCase(type) && !"TSA/QTST".equalsIgnoreCase(type)) continue;
                anyService = true;
                String status = tail(svc.getStatus()).toLowerCase(java.util.Locale.ROOT);
                boolean positive = S43_POSITIVE_STATUSES.contains(status);
                if (positive && covers(svc, made)) { positiveThen = true; statusThen = status; }
                if (positive && covers(svc, now)) positiveNow = true;
                if (!positive && covers(svc, now)
                        && (withdrawnSince == null || svc.getStartDate() != null
                            && svc.getStartDate().before(withdrawnSince))) {
                    withdrawnSince = svc.getStartDate();
                    statusNow = status;
                }
            }
        }
        if (!anyService || !positiveThen || positiveNow) return null;

        // Two questions the Trusted List entry alone cannot answer, both settled from what DSS
        // itself produced.
        //
        // Did the withdrawal reach the anchor this time-stamp's chain ends at? A service can lose
        // its status while the anchor above it keeps one, and then nothing turns on the loss.
        if (passedSunsetDate(earliest, now) == null) return null;
        // And did it cost the signature anything? Only if DSS declined to take this time-stamp as
        // the best signature time and had to reach for something later. Otherwise the signature is
        // inconclusive for a reason of its own — made after its certificate was revoked, most
        // often — and would read the same with the service still granted; naming the withdrawal
        // would name the wrong cause. Observed on two such test files.
        if (bestSignatureTime == null || !bestSignatureTime.after(made)) return null;
        // Nor when the signing certificate was already revoked by the time that time-stamp was
        // made. No proof of existence could have rescued such a signature, so the withdrawal is
        // not what decided it, however true it is of the service. This is the shape of the two
        // test files above: a certificate revoked in 2013, signed against afterwards, and
        // time-stamped by a service the list withdrew in 2016.
        if (revokedBefore(sw.getSigningCertificate(), made)) return null;

        CertificateWrapper signer = sw.getSigningCertificate();
        if (signer == null || signer.getNotAfter() == null) return null;
        java.util.Date fallback = null;
        for (TimestampWrapper ts : sw.getTimestampList()) {
            java.util.Date t = ts.getProductionTime();
            if (t == null || !t.after(made)) continue;
            if (fallback == null || t.before(fallback)) fallback = t;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Nejstarší časové razítko uvádí čas ")
          .append(TimeFormat.full(made))
          .append(" a vydala je ").append(tsa.getCommonName() != null ? tsa.getCommonName() : "TSA")
          .append(". Její razítkovací služba měla k tomuto času v důvěryhodném seznamu status ")
          .append(statusText(statusThen))
          .append(", dnes má status ").append(statusText(statusNow));
        if (withdrawnSince != null) {
            sb.append(" (od ").append(TimeFormat.full(withdrawnSince)).append(")");
        }
        // Two shapes worth wording apart: a later proof exists but comes too late, or there is no
        // later proof at all. The second is the graver of the two — nothing else in the document
        // stands in for the time-stamp — and a sentence about "the next proof" would be nonsense.
        if (fallback == null) {
            sb.append(". Že razítko existovalo dříve než dnes, nedokládá nic — dokument neobsahuje"
                    + " žádné pozdější razítko, které by je krylo. Podpisový certifikát přitom"
                    + " platil jen do ")
              .append(TimeFormat.full(signer.getNotAfter())).append(".");
        } else {
            sb.append(". Nezávisle je doloženo jen to, že razítko existovalo nejpozději ")
              .append(TimeFormat.full(fallback))
              .append(" (další razítko v pořadí) — a k tomu okamžiku už služba kladný status"
                    + " neměla. Podpisový certifikát platil do ")
              .append(TimeFormat.full(signer.getNotAfter())).append(".");
        }
        return sb.toString();
    }

    /**
     * Situace 49: ověření skončilo kladně, ale rozhodný okamžik nese razítko, které v době svého
     * vlastního důkazu existence už kvalifikované nebylo. Vrací větu, která to vysvětlí, nebo
     * {@code null}, když o nic takového nejde.
     *
     * <p>Rub situace 43. Ta popisuje případ, kdy odnětí statusu stálo podpis kladný výsledek;
     * tahle popisuje případ, kdy nestálo — a přesto se stalo. Obojí je totéž zjištění o službě,
     * jen jednou vysvětluje verdikt a podruhé ho doprovází, takže se navzájem vylučují a sdílejí
     * jedno pole {@code withdrawnTsaDetail}.
     *
     * <p>Že kladný výsledek takový důkaz vylučuje, jsem si myslel do 29. 8. 2026 — komentář
     * u situace 43 to tvrdil a byla to úvaha, ne měření. Neplatí. Kladný výsledek znamená jen to,
     * že se řetěz certifikátů toho razítka podařilo dovést ke kotvě důvěry, které se odnětí
     * netýká: buď k vydávající autoritě, která si kvalifikovaný status podržela, nebo k připnutému
     * kořeni z vlastního úložiště. Ani jedno není status razítkovací služby, a právě ten
     * standard k okamžiku nejnižšího POE posuzuje. Změřeno na PAdES-LTA z roku 2012, jehož
     * rozhodný okamžik nese razítko služby I.CA TSS/TSU 3, 12/2011, vyřazené k 1. 7. 2016:
     * DSS uzavře ověření jako TOTAL_PASSED a témuž razítku napíše
     * {@code Time-stamp Qualification at lowest POE time = TSA}.
     *
     * <p>Obě kvalifikace si bere z DSS, ne z vlastního čtení seznamu: jsou to jeho výpočty téhož
     * pravidla, které vypisuje i referenční on-line služba, takže se s ní zdejší věta nemůže
     * rozejít. Ze seznamu se dočítá jen slovní status, aby ho šlo čtenáři odcitovat.
     */
    static String unqualifiedProofDetail(String sigId, SignatureWrapper sw,
                                         SimpleReport simpleReport, DetailedReport detailedReport) {
        if (sw == null || detailedReport == null) return null;
        // Bez expirace podpisového certifikátu by ověření prošlo i bez razítka a jeho kvalifikace
        // by nerozhodovala o ničem. Pojmenovat ji tam by znamenalo přidat k platnému podpisu
        // výhradu, která se ho netýká.
        CertificateWrapper signer = sw.getSigningCertificate();
        if (signer == null || signer.getNotAfter() == null
                || !signer.getNotAfter().before(new java.util.Date())) {
            return null;
        }
        eu.europa.esig.dss.detailedreport.jaxb.XmlProofOfExistence poe =
                detailedReport.getBestProofOfExistence(sigId);
        if (poe == null || poe.getTimestampId() == null) return null;

        TimestampWrapper decisive = null;
        for (TimestampWrapper ts : sw.getTimestampList()) {
            if (poe.getTimestampId().equals(ts.getId())) decisive = ts;
        }
        if (decisive == null || decisive.getProductionTime() == null) return null;

        if (detailedReport.getTimestampQualificationAtTstGenerationTime(decisive.getId())
                != TimestampQualification.QTSA) {
            return null;
        }
        if (detailedReport.getTimestampQualificationAtBestPoeTime(decisive.getId())
                == TimestampQualification.QTSA) {
            return null;
        }

        CertificateWrapper tsa = decisive.getSigningCertificate();
        java.util.Date made = decisive.getProductionTime();
        String statusThen = qtstStatusAt(tsa, made);
        TimestampWrapper proof = nextAcceptedTimestamp(sw, simpleReport, made);
        // Status k okamžiku, ke kterému se stav služby posuzuje, ne k dnešku: u razítka krytého
        // pozdějším razítkem je to čas toho pozdějšího, jinak přítomnost. Napsat sem „dnes"
        // a k tomu datum z roku 2016 by čtenáři podsunulo, že jde o dvě různá zjištění.
        java.util.Date judgedAt = proof != null && proof.getProductionTime() != null
                ? proof.getProductionTime() : new java.util.Date();
        String statusJudged = qtstStatusAt(tsa, judgedAt);

        StringBuilder sb = new StringBuilder();
        sb.append("Rozhodný okamžik nese časové razítko z ").append(TimeFormat.full(made))
          .append(", které vydala ")
          .append(tsa != null && tsa.getCommonName() != null ? tsa.getCommonName() : "TSA")
          .append(".");
        if (statusThen != null) {
            sb.append(" K času, který razítko uvádí, měla jeho razítkovací služba"
                    + " v důvěryhodném seznamu status ").append(statusText(statusThen)).append(".");
        }
        if (proof != null && proof.getProductionTime() != null) {
            sb.append(" Že razítko existovalo dříve než dnes, je ale nezávisle doloženo teprve"
                    + " razítkem z ").append(TimeFormat.full(proof.getProductionTime()))
              .append(" — a k tomu okamžiku už služba kvalifikovaný status neměla");
        } else {
            sb.append(" Že razítko existovalo dříve než dnes, ale nedokládá nic:"
                    + " dokument neobsahuje žádné pozdější razítko, které by je krylo,"
                    + " takže se jeho existence prokazuje až k dnešku, kdy služba"
                    + " kvalifikovaný status nemá");
        }
        if (statusJudged != null) {
            sb.append(" (").append(statusText(statusJudged)).append(")");
        }
        sb.append(". Standard ETSI EN 319 102-1 posuzuje stav služby právě k tomuto okamžiku,"
                + " takže razítko je sice platné, ale za kvalifikované je k němu nepovažuje.");
        // Že na tom stejně bývá i razítko, které ten důkaz nese, tady stávalo jako přípojná věta.
        // Je to samostatné zjištění o jiném prvku, a tak má od 29. 8. 2026 vlastní situaci 50.
        sb.append(" Na výsledku ověření to nic nemění — platnost razítka nese jeho certifikační"
                + " řetěz, ne status služby. Validátor, který kvalifikovanost důkazu vyžaduje,"
                + " ale může u téhož dokumentu dojít k jinému závěru.");
        return sb.toString();
    }

    /**
     * Situace 50: důkaz existence, o který se rozhodné razítko opírá, nese sám další razítko —
     * a to v okamžiku svého vlastního důkazu kvalifikované nebylo. Vrací větu, nebo {@code null}.
     *
     * <p>Sestra situace 49, o jeden článek dál. Devětačtyřicítka mluví o razítku, které určuje
     * rozhodný okamžik; tahle o řadě, která za ním stojí. Rozdíl je věcný: rozhodné razítko může
     * mít službu se statusem v pořádku a přitom viset na důkazu od služby, která ho nemá — pak
     * 49 nenastane a bez 50 by se o tom nedozvěděl nikdo.
     *
     * <p>Prochází se celá řada pozdějších razítek, která DSS uznal, ne jen to nejbližší: každé
     * z nich nese důkaz o tom předchozím, takže řada je tak silná jako její nejslabší článek.
     * Hlásí se to nejbližší závadné a počet dalších, aby věta nevypadala jako vada jediného prvku.
     *
     * <p>Dvě podoby jsou v textu rozlišené, protože se liší závažností: razítko mohlo kvalifikaci
     * <b>ztratit</b> (při vzniku QTSA, k nejnižšímu POE už ne), nebo ji <b>nemít od začátku</b> —
     * to je případ razítka vydaného až po odnětí statusu jeho služby. Změřeno na PAdES-LTA
     * z roku 2012: razítko z 2. 8. 2016 od služby ACAeID2 TSA1, TSU2, vyřazené k 1. 7. 2016,
     * má u DSS kvalifikaci TSA už při vzniku.
     */
    static String unqualifiedProofCarrierDetail(String sigId, SignatureWrapper sw,
                                                SimpleReport simpleReport,
                                                DetailedReport detailedReport) {
        if (sw == null || detailedReport == null) return null;
        CertificateWrapper signer = sw.getSigningCertificate();
        if (signer == null || signer.getNotAfter() == null
                || !signer.getNotAfter().before(new java.util.Date())) {
            return null;
        }
        eu.europa.esig.dss.detailedreport.jaxb.XmlProofOfExistence poe =
                detailedReport.getBestProofOfExistence(sigId);
        if (poe == null || poe.getTimestampId() == null) return null;
        TimestampWrapper decisive = null;
        for (TimestampWrapper ts : sw.getTimestampList()) {
            if (poe.getTimestampId().equals(ts.getId())) decisive = ts;
        }
        if (decisive == null || decisive.getProductionTime() == null) return null;

        List<TimestampWrapper> offenders = new ArrayList<>();
        java.util.Date after = decisive.getProductionTime();
        while (true) {
            TimestampWrapper next = nextAcceptedTimestamp(sw, simpleReport, after);
            if (next == null || next.getProductionTime() == null) break;
            if (detailedReport.getTimestampQualificationAtBestPoeTime(next.getId())
                    != TimestampQualification.QTSA) {
                offenders.add(next);
            }
            after = next.getProductionTime();
        }
        if (offenders.isEmpty()) return null;

        TimestampWrapper first = offenders.get(0);
        CertificateWrapper tsa = first.getSigningCertificate();
        java.util.Date made = first.getProductionTime();
        boolean lostIt = detailedReport.getTimestampQualificationAtTstGenerationTime(first.getId())
                == TimestampQualification.QTSA;

        StringBuilder sb = new StringBuilder();
        sb.append("Důkaz, že rozhodné razítko existovalo, nese časové razítko z ")
          .append(TimeFormat.full(made)).append(", které vydala ")
          .append(tsa != null && tsa.getCommonName() != null ? tsa.getCommonName() : "TSA")
          .append(". Ani ono ale kvalifikované nebylo: ");
        if (lostIt) {
            String statusThen = qtstStatusAt(tsa, made);
            sb.append("v době, kterou uvádí, jeho služba kladný status ještě měla");
            if (statusThen != null) sb.append(" (").append(statusText(statusThen)).append(")");
            sb.append(", ale k okamžiku, ke kterému je nezávisle doložena jeho vlastní existence,"
                    + " už ne.");
        } else {
            String statusThen = qtstStatusAt(tsa, made);
            sb.append("jeho služba neměla kladný status už v době, kterou razítko samo uvádí");
            if (statusThen != null) sb.append(" (").append(statusText(statusThen)).append(")");
            sb.append(" — razítko tedy vzniklo až poté, co byla vyřazena.");
        }
        if (offenders.size() > 1) {
            sb.append(" Totéž platí o ").append(offenders.size() - 1)
              .append(offenders.size() == 2 ? " dalším razítku" : " dalších razítkách")
              .append(" v řadě.");
        }
        sb.append(" Řada razítek je tak silná jako její nejslabší článek: platnost drží"
                + " certifikační řetězy, ale kvalifikovanost důkazu se tímhle článkem ztrácí.");
        return sb.toString();
    }

    /** Status služby typu QTST u certifikátu TSA k danému okamžiku, nebo {@code null}. */
    private static String qtstStatusAt(CertificateWrapper tsa, java.util.Date when) {
        if (tsa == null) return null;
        for (var tsp : tsa.getTrustServiceProviders()) {
            for (var svc : tsp.getTrustServices()) {
                String type = tail(svc.getServiceType());
                if (!"QTST".equalsIgnoreCase(type) && !"TSA/QTST".equalsIgnoreCase(type)) continue;
                if (covers(svc, when)) {
                    return tail(svc.getStatus()).toLowerCase(java.util.Locale.ROOT);
                }
            }
        }
        return null;
    }

    /**
     * Nejbližší pozdější razítko, které DSS uznal — tedy to, ze kterého plyne důkaz existence
     * razítka vydaného v čase {@code after}. Neplatné se přeskakuje: žádný důkaz nenese.
     */
    private static TimestampWrapper nextAcceptedTimestamp(SignatureWrapper sw,
                                                          SimpleReport simpleReport,
                                                          java.util.Date after) {
        TimestampWrapper best = null;
        for (TimestampWrapper ts : sw.getTimestampList()) {
            java.util.Date t = ts.getProductionTime();
            if (t == null || !t.after(after)) continue;
            if (simpleReport.getIndication(ts.getId()) != Indication.PASSED) continue;
            if (best == null || t.before(best.getProductionTime())) best = ts;
        }
        return best;
    }

    /** Whether the certificate's revocation data places its revocation at or before {@code when}. */
    private static boolean revokedBefore(CertificateWrapper cert, java.util.Date when) {
        if (cert == null) return false;
        for (CertificateRevocationWrapper rev : cert.getCertificateRevocationData()) {
            if (!rev.isRevoked() || rev.getRevocationDate() == null) continue;
            if (!rev.getRevocationDate().after(when)) return true;
        }
        return false;
    }

    /**
     * The earliest already-elapsed sunset date among the trust anchors a time-stamp's certificate
     * chain reaches, or {@code null} when none of them has one. The sunset date is where the
     * Trusted List stops vouching for the anchor; DSS reads the same field when it initialises
     * Validation Time Sliding and when it asks whether a chain is still anchored at the
     * validation time.
     *
     * <p>The signing certificate is examined alongside the chain: for time-stamping services the
     * Trusted List commonly lists the TSU certificate itself, so the anchor is the leaf.
     */
    private static java.util.Date passedSunsetDate(TimestampWrapper ts, java.util.Date now) {
        List<CertificateWrapper> certs = new ArrayList<>(ts.getCertificateChain());
        if (ts.getSigningCertificate() != null) certs.add(ts.getSigningCertificate());
        java.util.Date earliest = null;
        for (CertificateWrapper cert : certs) {
            java.util.Date sunset = cert.getTrustSunsetDate();
            if (sunset == null || !sunset.before(now)) continue;
            if (earliest == null || sunset.before(earliest)) earliest = sunset;
        }
        return earliest;
    }

    /**
     * The Trusted List status as the list itself writes it, with a Czech gloss.
     *
     * <p>Quoted rather than paraphrased on purpose. "Platná" or "důvěryhodná" would be this
     * application's words for something the list never says: before eIDAS applied on 1 July 2016
     * a service was {@code accredited} or {@code undersupervision}, the two states Directive
     * 1999/93/EC provided for, and only from that date {@code granted}. Naming the actual value
     * lets the reader look it up in the list and lose nothing in translation.
     */
    private static String statusText(String status) {
        if (status == null) return "neznámý";
        String gloss = switch (status) {
            case "accredited"              -> "akreditovaná dle směrnice 1999/93/ES";
            case "undersupervision"        -> "pod dohledem dle směrnice 1999/93/ES";
            case "supervisionincessation"  -> "dohled se ukončuje";
            case "granted"                 -> "udělený status kvalifikované služby dle eIDAS";
            case "withdrawn"               -> "odňatý";
            case "deprecatedatnationallevel" -> "zrušený na národní úrovni";
            case "setbynationallaw"        -> "stanovený národním právem";
            default -> null;
        };
        return gloss == null ? "„" + status + "“" : "„" + status + "“ (" + gloss + ")";
    }


    private static boolean covers(eu.europa.esig.dss.diagnostic.jaxb.XmlTrustService svc, java.util.Date when) {
        java.util.Date from = svc.getStartDate();
        java.util.Date to = svc.getEndDate();
        return (from == null || !when.before(from)) && (to == null || when.before(to));
    }

    /**
     * Zámek, který podpis položil na podpisová pole, případně na celý dokument.
     *
     * <p>Skládá se ze dvou zdrojů, protože každý nese jinou půlku odpovědi:
     * <ul>
     *   <li><b>{@code FieldMDP}</b> — transformace zapsaná v {@code /Reference} podpisového
     *       slovníku. Je to <b>podepsaná</b> stopa toho, že se zamykalo, a nese rozsah, tedy
     *       kterých polí se zámek týká.</li>
     *   <li><b>{@code /Lock}</b> podpisového pole — pokyn zapsaný do pole ještě před podpisem.
     *       Jen v něm je entry {@code /P}, tedy povolená míra změn celého dokumentu.</li>
     * </ul>
     *
     * <p>Rozsah se bere z {@code FieldMDP}, a teprve když ho nemá, ze zámku pole. Oprávnění jde
     * jen ze zámku pole: DSS je z {@code TransformParams} nečte, ačkoli je Acrobat píše do obou
     * — {@code getFieldMDP().getPermissions()} vrací na měřeném souboru {@code null}, zatímco
     * {@code getSigFieldLock().getPermissions()} {@code NO_CHANGE_PERMITTED}.
     *
     * @return null, když podpis žádný zámek neklade
     */
    private static SignatureSummary.PdfLock readPdfLock(
            eu.europa.esig.dss.diagnostic.PDFRevisionWrapper pdfRev) {
        if (pdfRev == null) return null;
        eu.europa.esig.dss.diagnostic.jaxb.XmlPDFLockDictionary fieldMdp = pdfRev.getFieldMDP();
        eu.europa.esig.dss.diagnostic.jaxb.XmlPDFLockDictionary fieldLock = pdfRev.getSigFieldLock();
        if (fieldMdp == null && fieldLock == null) return null;

        eu.europa.esig.dss.diagnostic.jaxb.XmlPDFLockDictionary scopeSource =
                fieldMdp != null && fieldMdp.getAction() != null ? fieldMdp : fieldLock;
        String scope = scopeSource != null && scopeSource.getAction() != null
                ? scopeSource.getAction().name() : null;
        List<String> fields = scopeSource != null && scopeSource.getFields() != null
                ? List.copyOf(scopeSource.getFields()) : List.of();
        String permission = fieldLock != null && fieldLock.getPermissions() != null
                ? fieldLock.getPermissions().name() : null;
        return new SignatureSummary.PdfLock(scope, fields, permission);
    }

    private static String tail(String uri) {
        return uri == null ? "" : uri.substring(uri.lastIndexOf('/') + 1);
    }

    /**
     * Národní důvěryhodný seznam, pod jehož službou je veden certifikát podpisu (u samostatného
     * razítka certifikát razítka), s pořadovým číslem a datem vydání (16. 10. 2026). DSS službu
     * přiřazuje přímo certifikátu podpisu, nejen kotvě — čte se proto odtud, stejně jako
     * kvalifikovanost razítkovací služby v {@link #qualifiedOnlyFrom}.
     *
     * <p>Bere se první národní seznam (ne LOTL); certifikát vedený ve dvou zemích zároveň se
     * ve sbírkách nevyskytuje. {@code null}, když certifikát pod žádnou službou veden není.
     */
    private static SignatureSummary.TrustListRef trustListOf(String id, DiagnosticData dd) {
        if (id == null) return null;
        CertificateWrapper cert = null;
        SignatureWrapper sw = dd.getSignatureById(id);
        if (sw != null) {
            cert = sw.getSigningCertificate();
        } else {
            TimestampWrapper tw = dd.getTimestampById(id);
            if (tw != null) cert = tw.getSigningCertificate();
        }
        if (cert == null) return null;
        for (TrustServiceWrapper svc : cert.getTrustServices()) {
            eu.europa.esig.dss.diagnostic.jaxb.XmlTrustedList tl = svc.getTrustedList();
            if (tl == null || tl.isLOTL()) continue;
            return new SignatureSummary.TrustListRef(tl.getCountryCode(), tl.getSequenceNumber(),
                    tl.getIssueDate() != null ? tl.getIssueDate().toInstant() : null);
        }
        return null;
    }

    /**
     * Klíč nejnovějšího razítka, které prvek kryje (5. 10. 2026). U podpisu jeho razítka
     * a dokumentová či kontejnerová razítka nad ním — táž množina jako u kontroly SHA-1 výše;
     * u samostatného razítka pozdější razítka, která ho zahrnují, a nekryje-li ho nic, ono samo.
     *
     * <p>Rozhoduje nejnovější razítko, protože to drží důkaz existence všeho pod ním: dokud je
     * jeho algoritmus spolehlivý, starší slabé algoritmy ověření nevadí. Proto se nové razítko
     * se silnějším algoritmem musí přidat dřív, než ten jeho přestane být spolehlivý.
     *
     * <p>Datum se bere z kryptografické sady politiky ověřování ({@link TsaSigningKey}), ne
     * z vlastní tabulky. Změřeno sondou {@code CryptoHorizonScan}: ve sbírkách D:\tmp
     * a D:\Downloads je takové razítko (RSA-2048, 1. 1. 2027) u 60 % prvků s datem.
     */
    private static SignatureSummary.TimestampKey newestTimestampKey(
            String id, DiagnosticData dd,
            eu.europa.esig.dss.model.policy.ValidationPolicy policy) {
        if (id == null) return null;
        List<TimestampWrapper> covering = new ArrayList<>();
        SignatureWrapper sw = dd.getSignatureById(id);
        if (sw != null) {
            covering.addAll(sw.getTimestampList());
            for (TimestampWrapper ts : dd.getTimestampList()) {
                if (covering.stream().anyMatch(c -> c.getId().equals(ts.getId()))) continue;
                if (ts.getTimestampedSignatures().stream().anyMatch(s -> id.equals(s.getId()))) {
                    covering.add(ts);
                }
            }
        } else {
            TimestampWrapper self = dd.getTimestampById(id);
            if (self == null) return null;
            covering.add(self);
            for (TimestampWrapper ts : dd.getTimestampList()) {
                if (ts.getTimestampedTimestamps().stream().anyMatch(t -> id.equals(t.getId()))) {
                    covering.add(ts);
                }
            }
        }
        TimestampWrapper newest = covering.stream()
                .filter(ts -> ts.getProductionTime() != null)
                .max(Comparator.comparing(TimestampWrapper::getProductionTime))
                .orElse(null);
        if (newest == null || newest.getSignatureAlgorithm() == null) return null;
        int size;
        try {
            size = Integer.parseInt(newest.getKeyLengthUsedToSignThisToken());
        } catch (Exception e) {
            return null;
        }
        TsaSigningKey key = new TsaSigningKey(newest.getSignatureAlgorithm(), size);
        java.util.Date until = key.reliableUntil(policy);
        if (until == null) return null;
        return new SignatureSummary.TimestampKey(key.describe(), until.toInstant(),
                TsaSigningKey.isForecast(until, policy));
    }

    /** All timestamps for a signature sorted ascending by production time. */
    private List<TimestampEntry> allTimestamps(String sigId, DiagnosticData dd,
                                                SimpleReport simpleReport) {
        SignatureWrapper sw = dd.getSignatureById(sigId);
        if (sw == null) return List.of();
        return sw.getTimestampList().stream()
                .filter(ts -> ts.getProductionTime() != null)
                .sorted(Comparator.comparing(ts -> ts.getProductionTime()))
                .map(ts -> {
                    CertificateWrapper tsaCert = ts.getSigningCertificate();
                    String issuer = null;
                    Instant notAfter = null;
                    if (tsaCert != null) {
                        issuer = tsaCert.getCommonName() != null && !tsaCert.getCommonName().isBlank()
                                ? tsaCert.getCommonName() : tsaCert.getReadableCertificateName();
                        notAfter = tsaCert.getNotAfter() != null
                                ? tsaCert.getNotAfter().toInstant() : null;
                    }
                    String tsKind = resolveTsKind(ts);
                    // A VRI token only counts towards BST when an archival time-stamp covers it,
                    // so the two cases need different labels — see canEstablishBestSignatureTime.
                    if ("vri".equals(tsKind) && !isCoveredByArchivalTimestamp(ts, sw, simpleReport)) {
                        tsKind = "vri_bare";
                    }
                    return new TimestampEntry(
                            ts.getProductionTime().toInstant(),
                            simpleReport.getIndication(ts.getId()) == Indication.PASSED,
                            issuer,
                            notAfter,
                            tsKind,
                            simpleReport.getTimestampQualification(ts.getId())
                                    == TimestampQualification.QTSA,
                            // Neporušenost bez důvěry: podpis tokenu sedí a otisk, který kryje,
                            // souhlasí. Táž trojice dotazů, jakou klade DetailedReportView; co
                            // nepřidává, je isTrustedChain(), a právě to je ten rozdíl.
                            ts.isSignatureIntact() && ts.isSignatureValid()
                                    && ts.isMessageImprintDataIntact(),
                            timestampFailure(ts, simpleReport),
                            timestampWeakDigest(ts),
                            timestampStatusWithdrawn(ts),
                            qualifiedOnlyFrom(ts, simpleReport),
                            tsaCert != null && tsaCert.isTrusted());
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Proč razítko neobstálo, pojmenované dílčí indikací — {@code null} u razítka, které prošlo.
     *
     * <p>Rozhoduje dílčí indikace, ne výčet chyb. Ten svádí: u razítek z roku 2012 hlásí protokol
     * vedle sebe vyřazenou SHA-1, expirovaný certifikát TSU i sunset kotvy, jenže verdikt drží
     * jediná z těch věcí. Změřeno 14. 9. 2026 na „certifikacni_a_uznavany_s_razitky_s_revinfo.pdf":
     * po zapnutí ústupku k SHA-1 chyby {@code ASCCM_*} z protokolu zmizely a indikace všech tří
     * razítek zůstala {@code INDETERMINATE/OUT_OF_BOUNDS_NO_POE}.
     *
     * <p>Porušený token se pozná dřív než z indikace, a <b>týmž dotazem jako situace 36</b>
     * ({@link #hasBrokenTimestampIntegrity}). Ta shoda je podstatná: S36 vydá důvod 23 a ten
     * důvod 21 potlačuje, takže shodné předpoklady znamenají, že se oba nikdy nerozejdou.
     * Dřív tu stál dotaz vlastní a širší — {@code !isSignatureValid() || !isMessageImprintDataIntact()}
     * — který označil za porušené i razítko, jehož krytá data se vůbec <i>nenašla</i>
     * ({@code isMessageImprintDataFound()} je false). Takové razítko S36 nespustí, důvod 21
     * tedy zůstane, a čtenář by se o nenalezených datech dočetl, že je token „porušený“.
     */
    /**
     * Mají všechna razítka mimo EUTL i příčinu „řetěz nevede k žádné kotvě"?
     *
     * <p>Odpověď potřebuje {@link #detectReasons}: právě tehdy říkají důvody 19 a 21 totéž a
     * devatenáctka se umlčí. Ptá se po razítkách, ne po číslech důvodů, protože se ty dva důvody
     * rozejdou hned, jak čtenář certifikát té autority vloží do vlastního úložiště — razítko se
     * ověří, jedenadvacítka zmizí a v EUTL ta autorita pořád není.
     *
     * <p>Kvalifikace i příčina se čtou týmiž dotazy jako situace 23 a 27, aby se výsledek nemohl
     * rozejít s tím, co ty situace vidí: {@code getTimestampQualification() == NA} a
     * {@link #timestampFailure}.
     *
     * <p>Prázdný výčet (žádné razítko mimo EUTL) vrací {@code false} — není co potlačovat.
     */
    private static boolean naTimestampsAllNoChain(SignatureWrapper sw, SimpleReport simpleReport) {
        boolean anyNa = false;
        for (TimestampWrapper ts : sw.getTimestampList()) {
            if (simpleReport.getTimestampQualification(ts.getId()) != TimestampQualification.NA) {
                continue;
            }
            anyNa = true;
            if (timestampFailure(ts, simpleReport)
                    != SignatureSummary.TimestampFailure.NO_CHAIN) {
                return false;
            }
        }
        return anyNa;
    }

    /**
     * Odkdy je ta razítkovací služba v důvěryhodném seznamu vedena jako kvalifikovaná, začíná-li
     * ten zápis <b>až po</b> vzniku razítka; jinak {@code null}.
     *
     * <p>Odpověď na otázku, která z karty nejde vyčíst: proč je razítko platné, a přitom
     * nekvalifikované. Kvalifikovanost se posuzuje k okamžiku vzniku razítka, takže razítko od
     * služby, jejíž kvalifikovaný zápis začíná později, kvalifikované není — a nic na kartě to
     * dosud neříkalo. Změřeno 21. 9. 2026 na „ZGHO-fin2016-1H-NotREV-K-HR-1.pdf": razítko z
     * 8. 3. 2017 od I.CA TSS/TSU 6, jehož obě služby typu TSA/QTST začínají až 16. 8. a
     * 7. 11. 2017.
     *
     * <p>Vrací {@code null} i tehdy, když razítko kvalifikované je, nebo když se žádný takový
     * zápis nenašel — pak je příčina jinde (služba se statusem odebraným, nebo vůbec neuvedená,
     * a o obojím mluví jiná místa).
     */
    private static Instant qualifiedOnlyFrom(TimestampWrapper ts, SimpleReport simpleReport) {
        if (simpleReport.getTimestampQualification(ts.getId()) == TimestampQualification.QTSA) {
            return null;
        }
        if (ts.getProductionTime() == null) return null;
        Instant made = ts.getProductionTime().toInstant();
        CertificateWrapper cert = ts.getSigningCertificate();
        if (cert == null) return null;
        Instant earliest = null;
        for (TrustServiceWrapper svc : cert.getTrustServices()) {
            if (svc.getType() == null || !svc.getType().endsWith("/TSA/QTST")) continue;
            if (svc.getStatus() == null || !svc.getStatus().endsWith("/granted")) continue;
            if (svc.getStartDate() == null) continue;
            Instant from = svc.getStartDate().toInstant();
            if (!from.isAfter(made)) return null;   // tehdy už kvalifikovaná byla; příčina je jinde
            if (earliest == null || from.isBefore(earliest)) earliest = from;
        }
        return earliest;
    }

    private static SignatureSummary.TimestampFailure timestampFailure(
            TimestampWrapper ts, SimpleReport simpleReport) {
        if (simpleReport.getIndication(ts.getId()) == Indication.PASSED) return null;
        if (hasBrokenTimestampIntegrity(ts)) return SignatureSummary.TimestampFailure.BROKEN;
        SubIndication si = simpleReport.getSubIndication(ts.getId());
        // Před klíči hlášení: bez krytých dat se ověřování k ničemu dalšímu nedostane, a co by
        // klíče říkaly o certifikátu autority, je vedlejší. Viz TimestampFailure.NO_DATA.
        if (si == SubIndication.SIGNED_DATA_NOT_FOUND) {
            return SignatureSummary.TimestampFailure.NO_DATA;
        }
        return SignatureSummary.TimestampFailure.of(messageKeys(simpleReport, ts.getId()),
                si == null ? null : si.name());
    }

    /**
     * Klíče hlášení, která DSS k tokenu vydalo — podklad pro zařazení příčiny.
     *
     * <p>Bere obojí, ověřovací i kvalifikační: {@code QUAL_*} sice o příčině nerozhoduje, ale
     * oddělovat je tu znamená psát dvě cesty a na jednu zapomenout. Selže-li dotaz (DSS na
     * neznámé id vyhodí), vrací prázdný seznam a zařazení spadne na dílčí indikaci.
     */
    private static java.util.List<String> messageKeys(SimpleReport simpleReport, String tokenId) {
        java.util.List<String> keys = new ArrayList<>();
        try {
            simpleReport.getAdESValidationErrors(tokenId).forEach(m -> keys.add(m.getKey()));
            simpleReport.getQualificationErrors(tokenId).forEach(m -> keys.add(m.getKey()));
        } catch (Exception e) {
            return List.of();
        }
        return keys;
    }

    /**
     * Nese podpis razítko VRI, které nekryje žádné dokumentové razítko?
     *
     * <p>Táž dvojice otázek, jakou klade {@code allTimestamps()} při rozlišení „vri" a „vri_bare";
     * tady se ptá znovu, protože situace se počítají dřív, než se seznam razítek poskládá.
     */
    private static boolean hasBareVriTimestamp(SignatureWrapper sw, SimpleReport simpleReport) {
        for (TimestampWrapper ts : sw.getTimestampList()) {
            if (ts.getType() != TimestampType.VRI_TIMESTAMP) continue;
            if (!isCoveredByArchivalTimestamp(ts, sw, simpleReport)) return true;
        }
        return false;
    }

    private static String resolveTsKind(TimestampWrapper ts) {
        TimestampType type = ts.getType();
        if (type == null) return "sig";
        return switch (type) {
            case SIGNATURE_TIMESTAMP -> "sig";
            case CONTENT_TIMESTAMP,
                 INDIVIDUAL_DATA_OBJECTS_TIMESTAMP,
                 ALL_DATA_OBJECTS_TIMESTAMP -> "content";
            case ARCHIVE_TIMESTAMP -> "doc";
            case VRI_TIMESTAMP -> "vri";
            default -> "doc";
        };
    }

    /** Production time of the earliest PASSED timestamp (BST = Best Signature Time). */
    /**
     * Důkaz existence odvozený z <b>pozdějšího podpisu, který tenhle kryje</b>.
     *
     * <p>V PDF je každý podpis vlastní přírůstkovou revizí, takže pozdější podpis podepisuje
     * i bajty toho dřívějšího. Nese-li pozdější podpis platné časové razítko, plyne z toho, že
     * dřívější podpis existoval nejpozději v čase toho razítka — a to bez ohledu na to, že sám
     * žádné razítko nemá.
     *
     * <p><b>DSS tuhle úvahu nedělá</b> a je to tak podle standardů: podpisové časové razítko kryje
     * podpisovou hodnotu <i>svého</i> podpisu a extrakce důkazů existence přiřazuje důkaz tokenům,
     * které razítko uvádí mezi krytými objekty. Že rozsah podepsaných bajtů pozdějšího podpisu
     * fyzicky obsahuje ten dřívější, se odtud nedovodí; plošně se tak chová jen razítko dokumentové
     * nebo archivní. Odsud se tedy <b>nepřepisuje verdikt</b> — ten dál dává DSS —, jen se ten
     * odvozený důkaz přizná, protože jsou nástroje, které z něj vycházejí.
     *
     * <p>Nález uživatele z 12. 9. 2026 na „PAdES B-B +B-T kvalifikovany ICA.pdf": CzechPOINT
     * prohlásil za platný podpis, který AnEP hlásí jako INDETERMINATE/REVOKED_NO_POE. Oba podpisy
     * tam stojí na témže certifikátu, revokovaném 27. 8. 2026, a ten druhý nese razítko
     * z 24. 6. 2026 — tedy dva měsíce před revokací.
     *
     * @return nejstarší takový důkaz, nebo {@code null}, když žádný kryjící podpis s uznaným
     *         razítkem není
     */
    private CoveringProof coveringProof(String sigId, DiagnosticData dd,
                                        SimpleReport simpleReport) {
        SignatureWrapper self = dd.getSignatureById(sigId);
        return self == null ? null : coveringProof(self, dd.getSignatures(), simpleReport);
    }

    /**
     * Táž otázka položená nad seznamem prvků — takhle se na ni ptá i {@code ValidatorFactory},
     * když počítá druhý závěr k odvozenému okamžiku.
     */
    static CoveringProof coveringProof(SignatureWrapper self, List<SignatureWrapper> all,
                                       SimpleReport simpleReport) {
        if (self == null || all == null) return null;
        CoveringProof best = null;
        for (int i = 0; i < all.size(); i++) {
            SignatureWrapper other = all.get(i);
            if (other.getId().equals(self.getId())) continue;
            if (!coversElement(other, self)) continue;
            // Razítko musí být uznané a musí zakládat rozhodný okamžik — jinak by se starší
            // podpis dokazoval mladším, o kterém taky nikdo neví, kdy vznikl.
            //
            // A kryjící podpis musí být neporušený: celá úvaha stojí na tom, že jeho podpisová
            // hodnota je spočítaná nad bajty toho dřívějšího. Nesedí-li, nedokládá nic.
            if (!other.isSignatureIntact()) continue;
            Instant when = earliestValidTimestampTime(other, simpleReport);
            if (when == null) continue;
            if (best == null || when.isBefore(best.when())) {
                best = new CoveringProof(when, isCounterSignatureOf(other, self),
                        other.getId());
            }
        }
        return best;
    }

    /**
     * Kryje prvek {@code outer} prvek {@code inner} natolik, že jeho časové razítko dokládá
     * i existenci toho druhého?
     *
     * <p>Dvě cesty, protože formáty odpovídají různě, a rozdíl je podstatný:
     *
     * <ul>
     *   <li><b>PDF</b> — pokrytí se <i>počítá</i> z rozsahů podepsaných bajtů. Každý podpis je
     *       vlastní přírůstkovou revizí, takže pozdější podepisuje i ten dřívější; je to
     *       pravidlo, ne výjimka.</li>
     *   <li><b>Protipodpis</b> (kterýkoli formát, typicky ASiC/XAdES) — pokrytí <i>deklaruje sám
     *       formát</i>: protipodpis podepisuje podpisovou hodnotu svého rodiče a DSS ten vztah
     *       vydá přímo, přes {@code isCounterSignature()} a {@code getParent()}. Počítat se
     *       nemusí nic, a je to důkaz silnější, ne slabší.</li>
     * </ul>
     *
     * <p><b>Paralelní podpisy v kontejneru sem schválně nepatří</b>, a je to ten hlavní rozdíl
     * proti PDF. V ASiC leží podpisy vedle sebe (META-INF/signatures001.xml, 002.xml…) a každý
     * podepisuje tytéž datové objekty, ne jeden druhého; mezi nimi žádné pořadí není a pozdější
     * o dřívějším nic nedokládá. Ověřeno 12. 9. 2026 vlastnoručně sestaveným kontejnerem:
     * oba podpisy mají rozsah „obsah.txt[FULL]" a ani jeden nekryje ten druhý. Sbírky přitom
     * žádný vícepodpisový kontejner nemají — 34 kontejnerů s podpisy, všechny s jediným
     * podpisovým souborem —, takže přípravek si test staví sám.
     *
     * <p>Kontejnerová a archivní razítka tu nefigurují: ta kryjí celý kontejner a důkaz existence
     * z nich DSS odvozuje už dnes samo, viz {@link #canEstablishBestSignatureTime}.
     */
    static boolean coversElement(SignatureWrapper outer, SignatureWrapper inner) {
        if (outer == null || inner == null) return false;
        if (isCounterSignatureOf(outer, inner)) return true;
        long[] innerRange = signedByteRange(inner);
        return innerRange != null && coversSignedRange(signedByteRange(outer), innerRange);
    }

    /**
     * Identifikátor prvku, nad kterým tenhle stojí jako protipodpis; {@code null}, není-li
     * protipodpisem.
     *
     * <p>Vztah deklaruje sám formát, nepočítá se z bajtů — viz {@link #coversElement}. Pořadové
     * číslo se tu schválně nepočítá: prvky se před vykreslením ještě přerovnávají a mezi podpisy
     * se řadí samostatná časová razítka, takže index v diagnostických datech není totéž co číslo
     * na kartě.
     */
    private static String counterSignatureOfId(SignatureWrapper sw) {
        return sw != null && sw.isCounterSignature() && sw.getParent() != null
                ? sw.getParent().getId() : null;
    }

    /** Je {@code outer} protipodpisem právě nad {@code inner}? */
    private static boolean isCounterSignatureOf(SignatureWrapper outer, SignatureWrapper inner) {
        return outer != null && inner != null && outer.isCounterSignature()
                && outer.getParent() != null
                && inner.getId().equals(outer.getParent().getId());
    }

    /**
     * Rozsah bajtů, které podpis kryje: {@code [start1, len1, start2, len2]}.
     *
     * <p>Bere se z {@code DiagnosticData}, ne hledáním {@code /ByteRange} v bajtech souboru, a je
     * to podstatný rozdíl. Změřeno 12. 9. 2026 na „test na opacne poradi signed.pdf": soubor nese
     * tři takové rozsahy, ale jen dva jsou podpisy — ten první patří položce {@code /Perms /UR3},
     * tedy podpisu užívacích práv (Reader-enabled), a kryje 303 541 bajtů. Bajtové síto by ho
     * považovalo za podpis.
     */
    private static long[] signedByteRange(SignatureWrapper sw) {
        PDFRevisionWrapper rev = sw.getPDFRevision();
        if (rev == null) return null;
        List<java.math.BigInteger> br = rev.getSignatureByteRange();
        if (br == null || br.size() != 4) return null;
        long[] out = new long[4];
        for (int i = 0; i < 4; i++) {
            if (br.get(i) == null) return null;
            out[i] = br.get(i).longValue();
        }
        return out;
    }

    /**
     * Leží celý podpis {@code inner} uvnitř bajtů, které podepsal {@code outer}?
     *
     * <p>Ptá se na pokrytí, ne na pořadí — pořadí je až důsledek. Kontrolní scénář, na kterém se
     * to 12. 9. 2026 ověřovalo: prázdné podpisové pole, do něj se podepíše až po jiném podpisu.
     * Pole vzniklo první, podpis do něj je ale poslední revizí, takže ten druhý podpis jej
     * nekryje a za důkaz jeho existence se nevydá.
     *
     * <p>Podpis {@code inner} sahá od {@code inner[0]} po {@code inner[2] + inner[3]}; mezi
     * {@code inner[0]+inner[1]} a {@code inner[2]} leží jeho vlastní {@code /Contents}. Kryje ho
     * tedy ten podpis, jehož <b>první</b> krytá část začíná na nule a sahá za jeho konec — druhá
     * část už leží až za vlastním {@code /Contents} toho pozdějšího, tedy dál než celý
     * {@code inner}.
     */
    static boolean coversSignedRange(long[] outer, long[] inner) {
        if (outer == null || inner == null || outer.length != 4 || inner.length != 4) return false;
        if (outer[0] != 0) return false;
        long innerEnd = inner[2] + inner[3];
        long outerFirstEnd = outer[0] + outer[1];
        return innerEnd <= outerFirstEnd;
    }

    /**
     * Odvozený důkaz existence a prvek, ze kterého plyne (číslovaný jako na kartě).
     *
     * @param counterSignature zda ten prvek kryje tenhle podpis jako protipodpis; u PDF je to
     *                         naopak pokrytí spočítané z rozsahů bajtů. Věta pro čtenáře se tím
     *                         liší — „podepsanými bajty" by u protipodpisu neplatilo.
     */
    record CoveringProof(Instant when, boolean counterSignature, String signatureId) { }

    /**
     * Časové razítko krycího prvku, o které se odvozený důkaz opírá.
     *
     * <p>Nese se do souhrnu proto, že horizont ověřitelnosti se ptá, jak dlouho bude uznatelný
     * <b>důkaz existence</b> — a u tohohle prvku to není žádné jeho vlastní razítko, nýbrž právě
     * tohle. Bez něj by {@code ContinuityHorizon} spadl do větve „žádné razítko", vzal za mez
     * konec platnosti podpisového certifikátu a napsal, že se platnost posuzuje k aktuálnímu
     * okamžiku — ačkoli se posuzovala k okamžiku o dva roky staršímu.
     *
     * <p>Vrací se skutečná položka krycího prvku, ne vyrobená kopie: kdyby se razítko přidalo
     * mezi razítka tohohle prvku, fakta by o podpisu bez razítka tvrdila, že razítko má.
     */
    private TimestampEntry coveringProofTimestamp(CoveringProof proof, DiagnosticData dd,
                                                  SimpleReport simpleReport) {
        if (proof == null || proof.signatureId() == null) return null;
        for (TimestampEntry ts : allTimestamps(proof.signatureId(), dd, simpleReport)) {
            if (ts.time() != null && ts.time().equals(proof.when())) return ts;
        }
        return null;
    }

    /**
     * Kam v dokumentu patří každý podpis a každé samostatné razítko — klíč, podle kterého se prvky
     * na kartě řadí.
     *
     * <p>Řadit podle uváděného času podpisu je špatně: podpis může tvrdit čas, který předchází
     * razítku ležícímu v dokumentu dřív. Pozice se proto odvozuje z toho, kolik podpisů které
     * razítko kryje:
     *
     * <ul>
     *   <li>podpis na indexu {@code i} → přihrádka {@code i*2} (sudá),</li>
     *   <li>razítko kryjící {@code k} podpisů → přihrádka {@code k*2 - 1} (lichá; −1 znamená
     *       „před všemi podpisy").</li>
     * </ul>
     *
     * <p>Razítko předcházející všem podpisům tak sedne na −1 a archivní razítko za všechny, které
     * kryje. Uváděný čas zůstává jen jako rozřazovač při shodě (dvě razítka před všemi podpisy).
     *
     * <h2>Osiřelý podpis počítání podle krytí rozbije</h2>
     *
     * <p>Nález uživatele z 20. 9. 2026 nad „uznavana pecet.pdf“: dokumentové časové razítko
     * stálo jako <b>prvek č. 1</b>, ačkoli vzniklo o dvaadvacet minut po podpisu pod ním.
     * Důvod je v tom, že DSS ten podpis vůbec nevidí — má {@code /SubFilter adbe.x509.rsa_sha1}
     * a čte ho až {@link PdfOrphanSignatureDetector}. Razítko tedy kryje <b>nula</b> podpisů
     * (přihrádka −1, „před všemi“) a osiřelý podpis žádnou přihrádku neměl, takže spadl do
     * záložní {@code afterAll}. Dvě domněnky, obě vedle, a shodou okolností právě proti sobě.
     *
     * <p>Kde se rozsahy bajtů znají, se proto nepočítá krytí, nýbrž <b>kam až revize sahá</b>
     * ({@code /ByteRange} → {@code b + c}, viz {@link RevisionTail#coveredUntil}). To je skutečné
     * pořadí v souboru, ne jeho odhad: PDF se aktualizuje přírůstkově, takže pozdější prvek končí
     * dál. Nad tím dokumentem: podpis končí na 59 598, razítko na 93 013.
     *
     * <p>Podmínky jsou dvě a obě jsou úmyslné. Uplatní se to <b>jen když nějaký osiřelý podpis
     * je</b> — bez nich se nemá co zlepšit a dokumenty, kterých se to netýká, se řadí přesně jako
     * dosud. A <b>jen když rozsah znají všechny prvky</b>: v kontejneru ASiC ani v samostatném
     * CMS žádný {@code /ByteRange} není, a míchat dvě různá pravidla v jednom pořadí by dalo
     * výsledek, který neodpovídá ani jednomu.
     */
    private static java.util.Map<String, Integer> documentPositions(
            DiagnosticData dd, SimpleReport baseReport, List<OrphanSignature> orphanCards) {
        java.util.Map<String, TimestampWrapper> tsById = dd.getTimestampList().stream()
                .collect(java.util.stream.Collectors.toMap(
                        eu.europa.esig.dss.diagnostic.AbstractTokenProxy::getId, t -> t));
        java.util.Map<String, Long> ends = revisionEnds(dd, baseReport, tsById, orphanCards);
        if (ends != null) return positionsFromEnds(ends);

        List<String> sigIdOrder = baseReport.getSignatureIdList();
        java.util.Map<String, Integer> docPos = new java.util.HashMap<>();
        for (int i = 0; i < sigIdOrder.size(); i++) {
            docPos.put(sigIdOrder.get(i), i * 2);
        }
        for (String tsId : baseReport.getTimestampIdList()) {
            TimestampWrapper tw = tsById.get(tsId);
            int coveredSigs = tw != null ? tw.getTimestampedSignatures().size() : 0;
            docPos.put(tsId, coveredSigs * 2 - 1);
        }
        return docPos;
    }

    /**
     * Konce revizí všech prvků — nebo {@code null}, když je jediný neznámý.
     *
     * <p>Buď se ví o všech, nebo se podle nich neřadí vůbec: pořadí složené ze dvou různých
     * pravidel by neodpovídalo ani jednomu.
     */
    private static java.util.Map<String, Long> revisionEnds(
            DiagnosticData dd, SimpleReport baseReport,
            java.util.Map<String, TimestampWrapper> tsById, List<OrphanSignature> orphanCards) {
        if (orphanCards == null || orphanCards.isEmpty()) return null;

        java.util.Map<String, Long> ends = new java.util.LinkedHashMap<>();
        for (String id : baseReport.getSignatureIdList()) {
            SignatureWrapper sw = dd.getSignatureById(id);
            long end = sw != null ? RevisionTail.coveredUntil(sw.getPDFRevision()) : 0;
            if (end <= 0) return null;
            ends.put(id, end);
        }
        for (OrphanSignature orphan : orphanCards) {
            long end = revisionEnd(orphan.byteRange());
            if (end <= 0) return null;
            ends.put("orphan:" + orphan.fieldName(), end);
        }
        for (String tsId : baseReport.getTimestampIdList()) {
            TimestampWrapper tw = tsById.get(tsId);
            long end = tw != null ? RevisionTail.coveredUntil(tw.getPDFRevision()) : 0;
            if (end <= 0) return null;
            ends.put(tsId, end);
        }
        return ends;
    }

    /**
     * Pořadí z konců revizí: kdo končí dřív, je dřív.
     *
     * <p>Sudé a liché přihrádky tu nejsou potřeba. Ty byly trik, jak mezi podpisy vsunout
     * razítko, o kterém se vědělo jen to, kolik podpisů kryje; se skutečnými rozsahy je pořadí
     * úplné a každý prvek má vlastní číslo. Nezávislé na deklarovaném čase — <b>a to je ten
     * rozdíl</b>: dvě razítka za týmž podpisem se dosud řadila podle toho, jaký čas o sobě
     * tvrdí, kdežto tady podle toho, kde v souboru opravdu leží.
     *
     * <p>Shoda konců v PDF nenastává (každé podepsání přidá vlastní revizi), rozhodnout ji ale
     * musí něco pevného, aby dva běhy nad týmž souborem nedaly dvě různá pořadí.
     */
    static java.util.Map<String, Integer> positionsFromEnds(java.util.Map<String, Long> ends) {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, Long> e : ends.entrySet()) {
            int before = 0;
            for (java.util.Map.Entry<String, Long> other : ends.entrySet()) {
                int cmp = other.getValue().compareTo(e.getValue());
                if (cmp < 0 || (cmp == 0 && other.getKey().compareTo(e.getKey()) < 0)) before++;
            }
            out.put(e.getKey(), before);
        }
        return out;
    }

    /**
     * Kam sahá revize osiřelého podpisu, z jeho {@code /ByteRange} ve tvaru „a,b,c,d".
     *
     * <p>Totéž číslo a touž cestou jako {@link RevisionTail#coveredUntil} u prvků, které zná DSS:
     * {@code c + d}. Rozsah odsud je řetězec z PDFBoxu, ne seznam čísel, takže se musí přečíst —
     * ale počítá se z něj stejně, jinak by se dvě poloviny téhož pořadí měřily jinak.
     *
     * @return konec revize, nebo 0, když rozsah chybí nebo se nedá přečíst
     */
    static long revisionEnd(String byteRange) {
        if (byteRange == null) return 0;
        String[] parts = byteRange.split(",");
        if (parts.length != 4) return 0;
        try {
            long end = Long.parseLong(parts[2].trim()) + Long.parseLong(parts[3].trim());
            return end > 0 ? end : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Osiřelé podpisy, ze kterých se stane karta — ostatní jsou týž prvek, který už ukázalo DSS.
     *
     * <p>Napřed rozsah bajtů, teprve pak jméno. Jména se u zanořeného pole rozcházejí: DSS hlásí
     * vlastní {@code /T} („SignatureField1[0]"), PDFBox plné jméno ze stromu formuláře
     * („data[0].doc[0].….SignatureField1[0]"). Naměřeno na dokumentu s doložkou, kde se týž podpis
     * hlásil dvakrát — jednou jako PKCS7-T, podruhé jako nerozpoznaný druh prvku.
     *
     * <p>{@code ETSI.RFC3161} je pro DSS rozpoznaný {@code /SubFilter} dokumentového časového
     * razítka; ta jdou přes {@code getTimestampIdList()} a jejich jména polí se do
     * {@code dssFieldNames} nedostanou, protože ta množina vzniká jen z podpisů. Bez tohohle
     * řádku by se u každého takového razítka objevil druhý, osiřelý prvek s neznámou platností.
     */
    private static List<OrphanSignature> orphanCards(List<OrphanSignature> orphans,
                                                     java.util.Set<String> dssFieldNames,
                                                     java.util.Set<String> dssByteRanges) {
        List<OrphanSignature> out = new ArrayList<>();
        for (OrphanSignature orphan : orphans) {
            if (orphan.byteRange() != null && dssByteRanges.contains(orphan.byteRange())) continue;
            if (orphan.fieldName() != null && dssFieldNames.contains(orphan.fieldName())) continue;
            if ("ETSI.RFC3161".equals(orphan.subFilter())) continue;
            out.add(orphan);
        }
        return out;
    }

    /**
     * Kolikátým autentizačním prvkem v pořadí bude každý z nich na kartě — číslováno od jedničky,
     * jak je čtenář uvidí v záhlaví („Autentizační prvek č. N").
     *
     * <p>Počítá se z {@link #documentPositions} a rozřazovač podle času k tomu není potřeba.
     * Řadí-li se podle konců revizí, má každý prvek vlastní přihrádku; řadí-li se podle krytí,
     * sedí podpisy na sudých a samostatná razítka na lichých, takže se o tutéž přihrádku mohou
     * dělit leda dvě razítka mezi sebou — a to pořadím podpisů nehne.
     *
     * <p><b>Proč se to nesmí brát z pořadí v diagnostických datech DSS.</b> Tam jsou jen podpisy,
     * kdežto na kartě se mezi ně řadí i samostatná časová razítka: kontejnerové razítko, které
     * nekryje žádný podpis, dostane přihrádku −1 a jde úplně první. První podpis je pak
     * <b>druhý</b> autentizační prvek. Věta situace 54 by čtenáře poslala k cizímu prvku. Táž past
     * chytila 13. 9. 2026 označení protipodpisu — proto tam model nese identifikátor rodiče, ne
     * jeho číslo.
     */
    static java.util.Map<String, Integer> displayPositions(
            java.util.Map<String, Integer> docPos) {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, Integer> e : docPos.entrySet()) {
            int before = 0;
            for (Integer other : docPos.values()) {
                if (other < e.getValue()) before++;
            }
            out.put(e.getKey(), before + 1);
        }
        return out;
    }

    /** Věta situace 54 — který prvek ten důkaz nese a k jakému okamžiku. */
    static String coveringProofDetail(CoveringProof proof, Instant decisive,
                                             Instant controlTime,
                                             java.util.Map<String, Integer> displayPositions,
                                             boolean helped) {
        if (proof == null) return null;
        Integer position = displayPositions.get(proof.signatureId());
        // Bez pořadí by věta ukázala na prvek, který čtenář pod tím číslem nevidí; radši mlčet.
        if (position == null) return null;
        String head = "Prvek č. " + position + " v témže dokumentu "
                + (proof.counterSignature()
                        ? "je protipodpisem nad tímto podpisem"
                        : "kryje tento podpis svými podepsanými bajty")
                + " a nese platné časové razítko z "
                + formatRevDate(java.util.Date.from(proof.when()))
                + ". Z toho plyne, že tento podpis existoval nejpozději v ten okamžik";

        // Důkaz je, ale nepomohl: ověření se k tomu okamžiku zopakovalo a skončilo znovu bez
        // závěru. Říct to se musí, jinak by texty u takového prvku tvrdily, že se dřívější
        // existence nedá ničím doložit — a dokument přitom razítko nese.
        if (!helped) {
            return head + ". Ověření se k tomu okamžiku zopakovalo, platnost se ale prokázat"
                    + " nepodařilo ani tak: překážkou je něco jiného než doba vzniku.";
        }
        // Rozhodný okamžik nemusí být ten z krycího prvku: má-li prvek vlastní platné razítko
        // starší, posuzuje se k němu. Věta „a právě k němu se jeho platnost posuzovala" by pak
        // ukazovala na jiný čas, než jaký stojí v závěru — a přesně tenhle druh rozporu mezi
        // záložkami hlásil uživatel 13. 9. 2026.
        // Čtvrtý případ, od 22. 9. 2026: posuzovalo se k okamžiku ještě pozdějšímu, než je čas
        // krycího razítka — k poslednímu, kdy certifikát prvku ještě platil. Dělá to
        // ValidatorFactory.lateControlTime() tam, kde k času krycího razítka nebyl na světě
        // revokační údaj (typicky certifikát respondéra OCSP je mladší). Stojí první, protože
        // podmínky obou dřívějších případů umí splnit taky.
        if (controlTime != null && controlTime.isAfter(proof.when())) {
            return head + ". Samo posouzení proběhlo k okamžiku ještě pozdějšímu — poslednímu,"
                    + " kdy certifikát tohoto podpisu ještě platil —, protože v době krycího"
                    + " razítka nebyl na světě revokační údaj, o který se závěr opírá. Pozdější"
                    + " okamžik je přitom přísnější, ne shovívavější: odvolání, ke kterému by"
                    + " mezitím došlo, by se do závěru promítlo.";
        }
        if (decisive == null || !decisive.isBefore(proof.when())) {
            return head + ", a právě k němu se jeho platnost posuzovala.";
        }
        // Třetí případ, od 14. 9. 2026: rozhodný okamžik je ten dřívější, ale posuzovalo se přesto
        // k okamžiku krycího prvku, protože v té dřívější chvíli ještě nebyl na světě revokační
        // údaj, o který se závěr opírá (viz ValidatorFactory.chooseControlTime). Věta to musí
        // rozlišit — dokud říkala „posuzovala se k okamžiku dřívějšímu", tvrdila o tomhle prvku
        // něco, co se nestalo.
        if (controlTime != null && controlTime.isAfter(decisive)) {
            return head + ". Rozhodným okamžikem je čas ještě dřívější, který dokládá vlastní"
                    + " časové razítko tohoto podpisu; samo posouzení ale proběhlo k okamžiku"
                    + " krycího prvku, protože revokační údaj, o který se opírá, v té dřívější"
                    + " chvíli ještě neexistoval.";
        }
        return head + ". Platnost se pak posuzovala k okamžiku ještě dřívějšímu, který dokládá"
                + " vlastní časové razítko tohoto podpisu.";
    }

    private Instant earliestValidTimestampTime(String sigId, DiagnosticData dd,
                                               SimpleReport simpleReport) {
        return earliestValidTimestampTime(dd.getSignatureById(sigId), simpleReport);
    }

    /** Táž otázka nad hotovým wrapperem — bez {@code DiagnosticData}, které volající nemusí mít. */
    static Instant earliestValidTimestampTime(SignatureWrapper sw,
                                              SimpleReport simpleReport) {
        if (sw == null) return null;
        return sw.getTimestampList().stream()
                .filter(ts -> canEstablishBestSignatureTime(ts, sw, simpleReport))
                .filter(ts -> simpleReport.getIndication(ts.getId()) == Indication.PASSED
                        && ts.getProductionTime() != null)
                .map(ts -> ts.getProductionTime().toInstant())
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /**
     * The S36 predicate: is this timestamp token cryptographically inconsistent in itself?
     *
     * <p>Two ways that happens. The message imprint the token carries no longer matches the data
     * the token covers — the case seen when a document is altered after being archive-stamped.
     * Or the token's own signature does not verify, so nothing it asserts can be relied on.
     *
     * <p>This is deliberately narrower than S27. A timestamp can fail to verify for reasons that
     * say nothing about the token's integrity — an untrusted TSA, a deprecated digest, an expired
     * chain — and every one of those is repairable by a setting or an added certificate. A broken
     * token is not repairable by anything, which is why it deserves to be told apart.
     */
    private static boolean hasBrokenTimestampIntegrity(TimestampWrapper ts) {
        if (!ts.isSignatureIntact()) return true;
        if (ts.isMessageImprintDataFound() && !ts.isMessageImprintDataIntact()) return true;
        return ts.getDigestMatchers().stream()
                .anyMatch(dm -> dm.isDataFound() && !dm.isDataIntact());
    }

    /**
     * The S5 predicate: does the signature, or any of its timestamps, rely on revocation data
     * embedded in the document whose issuer's certificate chain has since expired?
     *
     * <p>Chains are walked only up to the first trusted certificate — a trust anchor's own
     * expiry is governed by the trusted list, not by this check. Externally fetched revocation
     * data is ignored: the concern is data frozen into the document, which cannot be refreshed.
     *
     * <p>Only certificates that have themselves expired are considered, and that restriction is
     * what makes the predicate mean anything. An expired revocation issuer is a problem solely
     * because proving it was trustworthy when it spoke needs a POE, and a POE is needed only on
     * the long-term path. A certificate still inside its validity never takes that path: EN 319
     * 102-1 settles it in the Validation Process for Basic Signatures at the current time, where
     * the revocation's own issuer is assessed at the revocation's production time, not now. The
     * basic process returns PASSED and the long-term and archival processes simply carry that
     * result forward — with a missing archive time-stamp reported as INFORMATION, not an error.
     * Firing here for a still-valid certificate therefore contradicted conformant validators,
     * which is what it did for a signature whose certificate ran to 2026-08-28 while its
     * embedded OCSP responder had expired on 2026-03-01.
     *
     * <p>Extracted from the S5 block so that S28 can consult it while {@code totalPassed} is
     * still in play. Registering S5 itself stays behind the early return, keeping it a
     * non-TOTAL_PASSED finding as before.
     */
    /**
     * Kdy skončila platnost vydavatele vloženého revokačního údaje — údaj do věty u důvodu 6.
     *
     * <p>Bere se z téhož nálezu, jakým se našla situace 5 ({@link #expiredEmbeddedRevocation}),
     * takže datum ve větě patří k tomu údaji, o kterém ta věta mluví. Rozhoduje nejbližší konec
     * platnosti v řetězu vydavatele — právě ten, na kterém situace stojí.
     */
    private Instant expiredResponderNotAfter(SignatureWrapper sw) {
        ExpiredRevocation found = expiredEmbeddedRevocation(sw);
        return found == null ? null : chainExpiry(found.rev().getCertificateChain());
    }

    private boolean hasExpiredEmbeddedRevocationSource(SignatureWrapper sw) {
        return expiredEmbeddedRevocation(sw) != null;
    }

    /**
     * Zda je v řetězci revokační údaj, jehož vydavatel k rozhodnému okamžiku neplatil — tedy to,
     * co spraví volba {@code EXPIRED_REVOCATION_SOURCES} a nic jiného.
     *
     * <p>Doplněk k {@link #expiredEmbeddedRevocation}, ne jeho zdvojení. Ta hlásí jediný výsek:
     * údaj <b>vložený v dokumentu</b> od vydavatele, jemuž platnost <b>skončila</b>, měřeno
     * k dnešku. Sem patří zbytek, který nikam nespadal:
     *
     * <ul>
     *   <li>vydavatel, který k rozhodnému okamžiku <b>ještě neplatil</b> — při ověřování ke dni
     *       v minulosti běžný případ, protože odpověď staženou dnes podepsal respondér zřízený
     *       až později. Tohle {@code expiredEmbeddedRevocation} neumí vyjádřit vůbec: měří jen
     *       konec doby platnosti, kdežto constraint v DSS (RevocationIssuerNotExpired, chyba
     *       BBB_XCV_ICTIVRCIRI_ANS) je rozsahový;</li>
     *   <li>vydavatel po expiraci u údaje <b>staženého</b>, ne vloženého.</li>
     * </ul>
     *
     * <p>Je to týž test, jaký si {@code certHasNewerRevocation()} odečítá, aby situace 6 neradila
     * volbu, která s výsledkem nehne. Ty dvě podmínky patří k sobě: co jedna vyloučí jako „tudy
     * ne", má druhá zvednout jako „tudy ano". Kdyby se rozešly, vznikne případ bez vodítka —
     * a přesně ten stav tuhle situaci vyvolal k životu.
     */
    private boolean hasOutOfRangeRevocationIssuer(SignatureWrapper sw, java.util.Date controlTime) {
        return hasOutOfRangeRevocationIssuer(sw, controlTime, ChainScope.ALL);
    }

    private boolean hasOutOfRangeRevocationIssuer(SignatureWrapper sw, java.util.Date controlTime,
                                                   ChainScope scope) {
        return !outOfRangeRevocationIssuerCerts(sw, controlTime, scope).isEmpty();
    }

    /**
     * Certifikáty, jejichž revokační údaj pochází od vydavatele mimo dobu jeho platnosti —
     * tedy ty, o kterých mluví situace 46 a důvod 24.
     *
     * <p>Táž dvojrole jako u {@link #unknownStatusCerts}: podmínku situace i pojmenování
     * certifikátu v textu důvodu nese jeden výčet. Dvě znění téhož testu by se rozešla a text by
     * jmenoval certifikát, kterého se nález netýká — nebo mlčel tam, kde situace nastala.
     */
    private List<CertificateWrapper> outOfRangeRevocationIssuerCerts(SignatureWrapper sw,
                                                                      java.util.Date controlTime,
                                                                      ChainScope scope) {
        List<CertificateWrapper> out = new ArrayList<>();
        Instant at = controlTime != null ? controlTime.toInstant() : Instant.now();
        for (List<CertificateWrapper> chain : signingAndTimestampChains(sw, scope)) {
            for (CertificateWrapper c : chain) {
                if (c.isTrusted()) break;
                for (CertificateRevocationWrapper rev : c.getCertificateRevocationData()) {
                    if (isTrustedRevocationIssuer(rev)) continue;
                    Instant expiry    = chainExpiry(rev.getCertificateChain());
                    Instant notBefore = chainNotBefore(rev.getCertificateChain());
                    boolean outOfRange = (notBefore != null && notBefore.isAfter(at))
                            || (expiry != null && expiry.isBefore(at)
                                && !rev.isInternalRevocationOrigin());
                    if (outOfRange && out.stream().noneMatch(x -> x.getId().equals(c.getId()))) {
                        out.add(c);
                        break;
                    }
                }
            }
        }
        return out;
    }

    /**
     * The embedded CRL/OCSP whose issuer has expired since it was produced, or {@code null}.
     * Only certificates that are themselves already expired are considered — for a still-valid
     * certificate fresh revocation data can be fetched, so an expired issuer of the embedded
     * copy proves nothing about the signature.
     */
    private ExpiredRevocation expiredEmbeddedRevocation(SignatureWrapper sw) {
        return expiredEmbeddedRevocation(sw, ChainScope.ALL);
    }

    private ExpiredRevocation expiredEmbeddedRevocation(SignatureWrapper sw, ChainScope scope) {
        Instant now = Instant.now();
        for (List<CertificateWrapper> chain : signingAndTimestampChains(sw, scope)) {
            for (CertificateWrapper c : chain) {
                if (c.isTrusted()) break;
                if (c.getNotAfter() == null || !c.getNotAfter().toInstant().isBefore(now)) continue;
                for (CertificateRevocationWrapper rev : c.getCertificateRevocationData()) {
                    if (!rev.isInternalRevocationOrigin()) continue;
                    if (isTrustedRevocationIssuer(rev)) continue;
                    Instant respExpiry = chainExpiry(rev.getCertificateChain());
                    if (respExpiry != null && respExpiry.isBefore(now)) {
                        return new ExpiredRevocation(c, rev);
                    }
                }
            }
        }
        return null;
    }

    /** An embedded revocation with an expired issuer, together with the certificate it covers. */
    private record ExpiredRevocation(CertificateWrapper covered, CertificateRevocationWrapper rev) { }

    /**
     * Predicts whether augmenting to LTA ("Uchování") would turn this INDETERMINATE signature
     * into one a conformant validator accepts — the S35 condition.
     *
     * <p>The situation it recognises is narrow. The document already carries revocation data that
     * DSS is willing to accept on its own terms: issued while the signing certificate was valid,
     * reporting it as not revoked, and predating the signature time-stamp. What fails today is
     * {@code LTV_ISCKNR} — the certificate that issued that revocation data has since expired, so
     * at the current instant nothing proves the issuer was trustworthy when it spoke. Without an
     * archive time-stamp {@code ARCH_LTAIVMP} fails, Past Signature Validation never runs, and the
     * verdict stays INDETERMINATE/OUT_OF_BOUNDS_NO_POE.
     *
     * <p>An archive time-stamp fixes exactly that: it gives the validation material a proof of
     * existence, PSV runs, and slides the assessment back to a control-time at which the issuer
     * was valid. Rozhodující podmínka — a důvod, proč je tohle předpověď, ne dohad — zní, že ten
     * důkaz existence musí padnout dovnitř doby platnosti vydavatele revokace, protože přesně to
     * {@code PSV_DIURDSCHPVR} testuje.
     *
     * <p><b>A je to důkaz existence certifikátu vydavatele revokace, ne podpisu.</b> Dřív se sem
     * dosazovalo podpisové časové razítko, jenže to certifikát respondéra nekryje — u PAdES B-LT
     * nikdy, protože respondér přichází až se slovníkem DSS, který se zapisuje po razítku.
     * Změřeno na PAdES B-LT neviditelny PS.pdf: razítko kryje podpis, podepsaná data a jeden
     * certifikát, revokace žádné. Certifikát respondéra tedy důkaz existence nemá a dostane ho
     * teprve od nového archivního razítka, tedy k dnešku — a to je u respondéra, který vypršel
     * 10. 3. 2026, pozdě. Predikát přesto sliboval nápravu a uchování skončilo na
     * {@code PSV_DIURDSCHPVR_ANS: The issuer of the used revocation data of the signing certificate
     * does not have a POE within its validity range!}
     *
     * <p>Z toho plyne i mez téhle situace: míří na případ, kdy vydavatel revokace už expiroval,
     * a nové razítko mu důkaz uvnitř jeho platnosti dodat nemůže. Zbývá tedy jediná cesta, jak
     * může být pravdivá — že už teď existuje razítko, které ten certifikát kryje.
     *
     * <p>Returns false whenever any link is missing, so the situation stays quiet rather than
     * promising a repair that would not materialise.
     */
    /**
     * Okamžik, k němuž bude existovat důkaz, že <b>certifikát vydavatele</b> revokace existoval.
     *
     * <p>Zrcadlí to, co dělá DSS. {@code POEExistsWithinCertificateValidityRangeCheck} má
     * v javadocu jedinou větu: <i>„verifies if the set of POEs contains a POE for the certificate
     * after the issuance date and before the expiration date of that certificate"</i>, a
     * {@code PastSignatureValidationCertificateRevocationSelector} ji volá nad
     * {@code revocationWrapper.getSigningCertificate()} — tedy nad certifikátem respondéra,
     * ne nad odpovědí samotnou. Revokační údaj se do přijatelných zařadí jen tehdy, když
     * {@code poe.isPOEExistInRange(issuer, issuer.notBefore, issuer.notAfter)} vyjde.
     *
     * <p>Bere se nejstarší platné razítko, které ten certifikát kryje; není-li žádné, tak
     * {@code now}, protože důkaz vznikne teprve novým archivním razítkem.
     */
    private static Instant poeForIssuer(CertificateWrapper issuer, SignatureWrapper sw,
                                        SimpleReport simpleReport, Instant now) {
        return sw.getTimestampList().stream()
                .filter(ts -> simpleReport.getIndication(ts.getId()) == Indication.PASSED)
                .filter(ts -> ts.getProductionTime() != null)
                .filter(ts -> ts.getTimestampedCertificates().stream()
                        .anyMatch(c -> c.getId().equals(issuer.getId())))
                .map(ts -> ts.getProductionTime().toInstant())
                .min(Comparator.naturalOrder())
                .orElse(now);
    }

    private boolean preservationWouldRestoreValidity(SignatureWrapper sw,
                                                     SimpleReport simpleReport, Instant now) {
        CertificateWrapper sigCert = sw.getSigningCertificate();
        if (sigCert == null) return false;
        Instant certNotAfter = sigCert.getNotAfter() != null
                ? sigCert.getNotAfter().toInstant() : null;

        for (CertificateRevocationWrapper rev : sigCert.getCertificateRevocationData()) {
            // Must already be embedded — preservation adds a time-stamp, not revocation data.
            // Internal origin covers every in-document carrier, including the pre-ETSI Adobe
            // signed attribute adbe-revocationInfoArchival (1.2.840.113583.1.1.8): a B-T-looking
            // PAdES signature can hold its OCSP there, and augmentation lifts it into /DSS.
            if (!rev.isInternalRevocationOrigin()) continue;
            // A revoked verdict cannot be repaired by proving when it was made.
            if (CertificateStatus.REVOKED.equals(rev.getStatus())) continue;

            Instant thisUpdate = rev.getThisUpdate() != null
                    ? rev.getThisUpdate().toInstant() : null;
            if (thisUpdate == null) continue;
            // The revocation must itself clear the "has information about the certificate" gate,
            // and must predate the POE so the archive time-stamp actually covers it.
            if (certNotAfter != null && thisUpdate.isAfter(certNotAfter)) continue;

            CertificateWrapper issuer = rev.getSigningCertificate();
            if (issuer == null || issuer.isTrusted()) continue;
            Instant issuerNotAfter = issuer.getNotAfter() != null
                    ? issuer.getNotAfter().toInstant() : null;
            Instant issuerNotBefore = issuer.getNotBefore() != null
                    ? issuer.getNotBefore().toInstant() : null;
            // The expired issuer is what blocks validation now — otherwise this is a different
            // problem and preservation is not the remedy.
            if (issuerNotAfter == null || !issuerNotAfter.isBefore(now)) continue;

            // Důkaz existence certifikátu vydavatele — viz poeForIssuer(). Dosazovat sem
            // podpisové razítko je chyba: u B-LT kryje podpis a podpisový certifikát, ale
            // certifikát respondéra ne, protože ten přijde až se slovníkem DSS.
            Instant poe = poeForIssuer(issuer, sw, simpleReport, now);
            // Decisive: the POE lies inside the issuer's validity, so PSV_DIURDSCHPVR will pass.
            if (poe.isAfter(issuerNotAfter)) continue;
            if (issuerNotBefore != null && poe.isBefore(issuerNotBefore)) continue;

            return true;
        }
        return false;
    }

    /**
     * Content timestamps (CAdES {@code content-time-stamp}, XAdES
     * {@code AllDataObjectsTimeStamp} / {@code IndividualDataObjectsTimeStamp}) are signed
     * attributes covering the data BEFORE signing. They bound the signing time from below,
     * whereas best-signature-time is an upper bound established by signature/archive
     * timestamps, so they must never enter the BST computation — a content timestamp can
     * legitimately predate the signature by an arbitrary interval.
     */
    private static boolean isContentTimestamp(TimestampType type) {
        return type == TimestampType.CONTENT_TIMESTAMP
                || type == TimestampType.ALL_DATA_OBJECTS_TIMESTAMP
                || type == TimestampType.INDIVIDUAL_DATA_OBJECTS_TIMESTAMP;
    }

    /**
     * May this time-stamp establish best-signature-time — that is, does a conformant validator
     * accept it as proof that the signature already existed?
     *
     * <p>Content time-stamps never can: they predate signing and bound the signing time from
     * below, not above.
     *
     * <p>A VRI time-stamp ({@code /TS} inside a DSS dictionary's VRI entry) depends on whether
     * anything protects it, and both outcomes were confirmed against DSS:
     *
     * <ul>
     *   <li><b>Standing alone it does not count.</b> PAdES recognises only the CMS
     *       {@code signature-time-stamp} attribute and document time-stamps for the T level, and
     *       DSS agrees — it files the token under {@code getVRITimestamps()} alone, leaves
     *       {@code getTLevelTimestamps()} empty, reports the signature as BASELINE-B, and keeps
     *       the proof of existence at the validation time even after validating the token as
     *       PASSED. The substantive reason is that a VRI entry sits in the unsigned DSS
     *       dictionary of an incremental update, so anyone can strip or replace it without a
     *       trace; evidence that can be removed silently cannot carry a validity claim.</li>
     *   <li><b>Covered by a document or archive time-stamp it does count.</b> That time-stamp
     *       includes the VRI token among its time-stamped objects, so POE extraction proves the
     *       token existed, and the removal objection disappears. DSS then reports a proof of
     *       existence at the VRI token's own production time, attributed to that token — seen on
     *       a signature whose document time-stamp of 2025-04-18 covers a VRI token of
     *       2019-10-08, where the resulting best-signature-time is 2019-10-08.</li>
     * </ul>
     *
     * <p>Only the archival family is accepted as a cover. A signature time-stamp cannot serve:
     * it covers the signature value alone, and the VRI entry is written later. Coverage is
     * followed one level deep, which is what real documents use.
     */
    private static boolean canEstablishBestSignatureTime(TimestampWrapper ts, SignatureWrapper sw,
                                                         SimpleReport simpleReport) {
        if (isContentTimestamp(ts.getType())) return false;
        if (ts.getType() != TimestampType.VRI_TIMESTAMP) return true;
        return isCoveredByArchivalTimestamp(ts, sw, simpleReport);
    }

    /**
     * Má prvek vlastní platné časové razítko, které může nést důkaz existence (tedy ne razítko
     * obsahu ani holé razítko VRI — viz {@link #canEstablishBestSignatureTime}), a vzniklo ještě
     * za platnosti podpisového certifikátu?
     *
     * <p>Pak razítko, které vedle něj neobstálo, o výsledku nerozhoduje — ověření se má o co
     * opřít, a jestli to nakonec udělá, závisí na něčem jiném (typicky na revokačních
     * informacích, bez nichž DSS na razítka vůbec nedojde). Nález uživatele 13. 10. 2026, viz
     * {@link #detectReasons}.
     */
    static boolean ownPoeWithinValidity(SignatureWrapper sw, SimpleReport simpleReport) {
        CertificateWrapper cert = sw.getSigningCertificate();
        if (cert == null || cert.getNotAfter() == null) return false;
        java.util.Date until = cert.getNotAfter();
        for (TimestampWrapper ts : sw.getTimestampList()) {
            if (simpleReport.getIndication(ts.getId()) != Indication.PASSED) continue;
            if (ts.getProductionTime() == null || !ts.getProductionTime().before(until)) continue;
            if (canEstablishBestSignatureTime(ts, sw, simpleReport)) return true;
        }
        return false;
    }

    /** Does a validated document/archive/container time-stamp include this token in its scope? */
    private static boolean isCoveredByArchivalTimestamp(TimestampWrapper ts, SignatureWrapper sw,
                                                        SimpleReport simpleReport) {
        return sw.getTimestampList().stream()
                .filter(other -> !other.getId().equals(ts.getId()))
                .filter(other -> other.getType() == TimestampType.DOCUMENT_TIMESTAMP
                        || other.getType() == TimestampType.ARCHIVE_TIMESTAMP
                        || other.getType() == TimestampType.CONTAINER_TIMESTAMP)
                .filter(other -> simpleReport.getIndication(other.getId()) == Indication.PASSED)
                .anyMatch(other -> other.getTimestampedObjects().stream()
                        .anyMatch(o -> o.getToken() != null
                                && ts.getId().equals(o.getToken().getId())));
    }

    /**
     * Detects which specific failure situations apply to a TOTAL_FAILED / INDETERMINATE
     * signature. Returns an empty list for TOTAL_PASSED signatures.
     *
     * <ol>
     *   <li>Integrity violation — signed content has been modified</li>
     *   <li>BST (from timestamp) at or after revocation of the signing certificate</li>
     *   <li>No timestamp and the signing certificate has already expired</li>
     *   <li>Timestamps present but their certificate chains have expired</li>
     *   <li>Embedded revocation present but the respondér/issuer cert chain has expired</li>
     *   <li>Online OCSP rejected: thisUpdate is after cert's notAfter (strict DSS policy)</li>
     *   <li>Online OCSP obtained but reports certificate status UNKNOWN</li>
     *   <li>Embedded revocation present but unusable for other reasons (not S5 or S7-related)</li>
     *   <li>Failed to obtain any online revocation data (TRY_LATER or no data at all)</li>
     *   <li>Signing certificate was not yet valid at the time of signing (NOT_YET_VALID)</li>
     *   <li>A CA certificate in the chain was revoked (REVOKED_CA_NO_POE)</li>
     *   <li>Certificate chain cannot reach a trusted anchor</li>
     * </ol>
     */
    /**
     * Situations arising from evidence records covering {@code sigId}.
     *
     * <p>Kept separate from the main detection because these say something about the record
     * rather than about the signature, and because the record's own verdict is independent of
     * the signature's: a perfectly valid signature can carry a broken record, and a signature
     * that fails on its own can carry a sound one.
     */
    private static List<Integer> detectEvidenceRecordSituations(String sigId, DiagnosticData dd,
                                                                SimpleReport simpleReport) {
        List<Integer> result = new ArrayList<>();
        boolean anyStructureIssue = false;
        boolean anyBrokenDigest = false;
        boolean anyWithoutValidTimestamp = false;
        boolean anyIncompleteCoverage = false;

        for (EvidenceRecordWrapper er : dd.getEvidenceRecords()) {
            if (er.getCoveredSignatures().stream().noneMatch(s -> sigId.equals(s.getId()))) continue;

            if (!er.isStructuralValidationValid()) anyStructureIssue = true;

            // Broken, not merely absent: an object that was never supplied is reported by DSS as
            // an orphan reference and says nothing about the record's soundness.
            boolean broken = er.getDigestMatchers().stream()
                    .anyMatch(m -> m.isDataFound() && !m.isDataIntact());
            if (broken) anyBrokenDigest = true;

            boolean anyValidTs = er.getTimestampList().stream()
                    .anyMatch(ts -> ts.isSignatureValid() && ts.isMessageImprintDataIntact());
            if (!anyValidTs) anyWithoutValidTimestamp = true;

            // Whether the record reaches everything the signature signs is a question DSS already
            // answers — the default policy carries SignedFilesCovered and
            // ContainerSignedAndTimestampedFilesCovered at WARN level. Reading its verdict beats
            // recomputing the comparison here, where the signature's scopes and the record's
            // digest matchers would have to be lined up by hand.
            if (hasCoverageWarning(er.getId(), simpleReport)) anyIncompleteCoverage = true;
        }

        if (anyStructureIssue) result.add(37);
        if (anyIncompleteCoverage) result.add(38);
        if (anyBrokenDigest) result.add(39);
        if (anyWithoutValidTimestamp) result.add(40);

        // S41: a record over a signature that carries no validation material of its own. The
        // record preserves existence and integrity, never revocation status — so once the signing
        // certificate expires and its CRL/OCSP stops being published, the record still proves
        // when the signature existed while nothing proves the certificate was not revoked then.
        // Fixing it later is impossible: augmenting to LT would rewrite the very bytes the hash
        // tree covers, which is why AnEP withdraws that offer once a record is present.
        boolean recordCoversThisSignature = dd.getEvidenceRecords().stream()
                .anyMatch(er -> er.getCoveredSignatures().stream()
                        .anyMatch(cs -> sigId.equals(cs.getId())));
        SignatureWrapper sw = dd.getSignatureById(sigId);
        if (recordCoversThisSignature && sw != null && lacksValidationMaterial(sw)) {
            result.add(41);
        }
        return result;
    }

    /**
     * Profiles that embed no revocation data. ETSI TS 119 132-3 and TS 119 122-3 recognise an
     * embedded evidence record only on an {@code *AdES-BASELINE-LT} signature or higher; anything
     * below has to fetch revocation data at validation time, which is exactly what stops working
     * once the certificate expires.
     */
    private static final java.util.Set<SignatureProfile> PROFILES_WITHOUT_VALIDATION_MATERIAL =
            java.util.Set.of(
                    SignatureProfile.BASELINE_B, SignatureProfile.BASELINE_T,
                    SignatureProfile.EXTENDED_BES, SignatureProfile.EXTENDED_EPES,
                    SignatureProfile.EXTENDED_T, SignatureProfile.NOT_ETSI);

    private static boolean lacksValidationMaterial(SignatureWrapper sw) {
        SignatureLevel level = sw.getSignatureFormat();
        if (level == null || level.getSignatureProfile() == null) return false;
        if (!PROFILES_WITHOUT_VALIDATION_MATERIAL.contains(level.getSignatureProfile())) return false;
        // The profile is only a label. A signature can carry embedded revocation data without
        // being recognised as LT (a plain PKCS #7 with an Adobe revocation dictionary, say), and
        // in that case the material is there and the warning would be wrong.
        return sw.foundRevocations().getRelatedRevocationData().stream()
                .noneMatch(RelatedRevocationWrapper::isInternalRevocationOrigin);
    }

    /**
     * Připomínky, které knihovna DSS vydala k jednomu prvku — v jejích protokolech to žluté.
     *
     * <p>Dvě skupiny za sebou, protože DSS je vede zvlášť a odpovídají na jinou otázku: ověření
     * podpisu jako takového (AdES) mluví o tom, co je s podpisem, kdyžto posouzení
     * kvalifikovanosti o tom, za co se podpis podle důvěryhodných seznamů počítá. Pořadí
     * zůstává tak, jak je knihovna vydává; karta je neseřazuje.
     *
     * <p>Chyby a informativní hlášení se sem nesbírají. Chyby nese verdikt a rozebírá je blok
     * o důvodech, informativní hlášení jsou výpisy kroků. Žlutá vrstva je ta jediná, která
     * říká „prvek obstál, ale něco na něm stojí za povšimnutí".
     */
    private static List<SignatureSummary.DssRemark> dssRemarks(String id,
                                                              SimpleReport simpleReport) {
        if (id == null) return List.of();
        List<SignatureSummary.DssRemark> out = new ArrayList<>();
        try {
            for (eu.europa.esig.dss.jaxb.object.Message m
                    : simpleReport.getAdESValidationWarnings(id)) {
                out.add(new SignatureSummary.DssRemark(m.getKey(), m.getValue(), false));
            }
            for (eu.europa.esig.dss.jaxb.object.Message m
                    : simpleReport.getQualificationWarnings(id)) {
                out.add(new SignatureSummary.DssRemark(m.getKey(), m.getValue(), true));
            }
        } catch (Exception noSuchToken) {
            // Prvek, který v protokolu nemá vlastní záznam — pak není co číst a není co tvrdit.
            return List.of();
        }
        return List.copyOf(out);
    }

    /** DSS warning keys for "the evidence record does not reach everything that is signed". */
    private static final java.util.Set<String> COVERAGE_WARNING_KEYS = java.util.Set.of(
            "BBB_CV_ER_HASSDOC_ANS",      // not all signed data objects covered
            "BBB_FC_ISFP_ASTFORAMC_ANS"); // ASiC: not all signed/time-asserted files covered

    private static boolean hasCoverageWarning(String recordId, SimpleReport simpleReport) {
        if (recordId == null) return false;
        try {
            // An evidence record embedded in a signature may have no top-level token in the
            // simple report, in which case there is nothing to read and no claim to make.
            return simpleReport.getAdESValidationWarnings(recordId).stream()
                    .anyMatch(m -> COVERAGE_WARNING_KEYS.contains(m.getKey()));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Věta pro situaci 51, nebo {@code null}, když nenastala — a tedy zároveň to pravidlo samo.
     *
     * <p>Nastane, když <b>žádný</b> revokační údaj k podpisovému certifikátu nevznikl v době
     * podpisu ani po ní. Ptá se to na všechny, ne jen na využitý: dokud aspoň jeden mluví
     * o okamžiku podpisu nebo pozdějším, je o co se opřít, a která z nich nesla verdikt, je
     * otázka pro jiné místo. Údaj bez {@code thisUpdate} situaci ruší — o takovém se nedá tvrdit,
     * že je starší.
     *
     * <p>Odstup se uvádí v jednotce, kterou čtenář pozná bez počítání, protože právě on je to
     * podstatné: pár minut je běžný provoz (podepisující program si vyžádá stav certifikátu těsně
     * před podpisem), kdežto hodiny nebo dny jsou prostor, o kterém se neví nic.
     */
    private static String staleRevocationDetail(SignatureWrapper sw, SimpleReport simpleReport,
                                                String sigId) {
        CertificateWrapper cert = sw.getSigningCertificate();
        if (cert == null) return null;
        List<CertificateRevocationWrapper> revs = cert.getCertificateRevocationData();
        if (revs == null || revs.isEmpty()) return null;
        Instant signedAt = signatureMoment(sw, simpleReport);
        if (signedAt == null) return null;
        Instant newest = null;
        Instant newestUntil = null;
        for (CertificateRevocationWrapper r : revs) {
            if (r.getThisUpdate() == null) return null;
            Instant t = r.getThisUpdate().toInstant();
            if (!t.isBefore(signedAt)) return null;
            if (newest == null || t.isAfter(newest)) {
                newest = t;
                newestUntil = r.getNextUpdate() == null ? null : r.getNextUpdate().toInstant();
            }
        }
        java.time.Duration gap = java.time.Duration.between(newest, signedAt);
        // Pečeť se nepodepisuje, nýbrž se jí pečetí, a karta okolo tuhle větu skloňuje všude
        // jinde — „stav platnosti pečeti", „Formát pečeti". Text, který by uprostřed toho mluvil
        // o podpisu, by vypadal jako cizí těleso. Druh prvku se pozná z kvalifikace, kterou
        // jádro stejně čte kvůli právnímu typu; jméno konstanty nese SEAL.
        boolean seal = isSeal(simpleReport, sigId);
        String moment = seal ? "zapečetění" : "podepsání";
        // Sedmý pád pro „mezi jeho vydáním a …“. Do 22. 9. 2026 se i tam dosazoval tvar výše
        // a věta končila „mezi jeho vydáním a podepsání“; našlo se to při měření, kde se text
        // vypisoval celý.
        String momentInstr = seal ? "zapečetěním" : "podepsáním";
        // A který certifikát to je, se taky musí říct. Poznámka stojí na kartě, kde jsou vedle
        // sebe certifikáty podpisové, razítkovací i vydavatelů revokačních údajů, a samotné
        // „o certifikátu" nechávalo čtenáře hádat, o kterém z nich je řeč — upřesnění uživatele
        // z 21. 9. 2026. Věta přitom mluví vždy o certifikátu prvku, viz getSigningCertificate()
        // na začátku metody.
        // Tvar „certifikát pečeti", ne „pečetící certifikát": tak mu říká zbytek aplikace na
        // devíti místech (AdvisorView.elementCert) a slovo „pečetící" se jinde váže k osobě
        // („pečetící osoba“), takže by se to četlo jako certifikát toho, kdo pečetil.
        String ofCert = seal ? "o certifikátu pečeti" : "o podpisovém certifikátu";
        // „Nejnovější revokační informace, které má aplikace k dispozici" místo „Nejnovější
        // revokační údaj" — znění od uživatele 22. 9. 2026. Věta tím říká, čí je to nález:
        // nejnovější z toho, co se k ověření sešlo, ne nejnovější, co kdy vydavatel vydal.
        // Jmenuje se u toho certifikát, kterého se to týká (upřesnění z 21. 9. 2026): na kartě
        // stojí vedle sebe certifikáty podpisové, razítkovací i vydavatelů revokačních údajů.
        String head = "Nejnovější revokační informace, které má aplikace k dispozici "
                + ofCert + ", vznikly " + gapWords(gap)
                + (seal ? " před zapečetěním (" : " před podpisem (")
                + TimeFormat.full(newest) + ")";

        // Dvě různá sdělení, a plést je dohromady je věcná chyba. Údaj, jehož platnost sahá za
        // okamžik vzniku prvku, o tom okamžiku **vypovídá** — EN 319 102-1 V1.4.1, klauzule
        // 5.2.5.4 krok 1 bere jako přijatelnou čerstvost právě interval thisUpdate–nextUpdate,
        // a totéž pravidlo má aplikace v coversMoment(). Tvrdit u něj „nevypovídá" byla
        // nepravda; hlášeno uživatelem 4. 9. 2026 nad pečetí z roku 2019, kde CRL vydaná
        // hodinu a 40 minut před zapečetěním platila ještě celý další den.
        //
        // Výhrada tím ale nemizí, jen se mění na tu pravdivou: odvolání, ke kterému by došlo
        // mezi vydáním údaje a vznikem prvku, se do něj promítnout nemohlo a ukázalo by se až
        // v následujícím. Přesně tak vypadá podvrh „T 2020 10 15 po revokaci Signer s
        // revinfo.pdf" — a od poctivého dokumentu ho zdejší ověřování nerozezná.
        if (newestUntil != null && newestUntil.isAfter(signedAt)) {
            return head + " a platily do " + TimeFormat.full(newestUntil) + ", okamžik " + moment
                    + " tedy pokrývají. Odvolání, ke kterému by došlo mezi jejich vydáním"
                    + " a " + momentInstr + ", by se ale projevilo až v následujících"
                    + " revokačních informacích.";
        }
        return head + ", takže o stavu certifikátu v okamžiku " + moment + " nevypovídají.";
    }

    /**
     * Je ten prvek pečeť, ne podpis? Podle kvalifikace, kterou jádro čte i pro právní typ —
     * konstanty pro pečeti mají v názvu {@code SEAL}. Ptát se {@code ExtendedView} nelze, to
     * bydlí v UI.
     */
    private static boolean isSeal(SimpleReport simpleReport, String sigId) {
        try {
            SignatureQualification q = simpleReport.getSignatureQualification(sigId);
            return q != null && q.name().contains("SEAL");
        } catch (Exception e) {
            return false;      // kvalifikace se nezjistila; „podpis" je bezpečnější výchozí volba
        }
    }

    /** Odstup slovy, se skloňováním; {@code CzechPlural} bydlí v UI a sem se sáhnout nesmí. */
    private static String gapWords(java.time.Duration d) {
        long days = d.toDays();
        if (days > 0) return days + (days == 1 ? " den" : days <= 4 ? " dny" : " dní");
        long h = d.toHours();
        if (h > 0) return h + (h == 1 ? " hodinu" : h <= 4 ? " hodiny" : " hodin");
        long min = d.toMinutes();
        if (min > 0) return min + (min == 1 ? " minutu" : min <= 4 ? " minuty" : " minut");
        long sec = Math.max(d.toSeconds(), 1);
        return sec + (sec == 1 ? " sekundu" : sec <= 4 ? " sekundy" : " sekund");
    }

    /**
     * Okamžik, ke kterému se má poměřovat stáří revokačních údajů — tedy kdy podpis vznikl.
     *
     * <p>Podpisové razítko, které obstálo, a teprve není-li žádné, deklarovaný čas podpisu.
     * Rozhodný okamžik ({@code bestSigTime}) se sem nehodí: ten se u podpisu s vadným podpisovým
     * razítkem posune na pozdější razítko dokumentové a odstup pak vyjde nesmyslně velký.
     * Naměřeno na „LT s podpisem delsim nez je casove razitko extended.pdf", kde takhle vyšlo
     * „údaj starší o 728 dní", ačkoli je s podpisem současný.
     *
     * <p>Deklarovaný čas si podepisující určuje sám, takže se na něj nedá spolehnout — pro tenhle
     * účel to ale nevadí. Posunout ho dopředu znamená prohlásit údaj za starší, tedy přiznat víc,
     * a posunout dozadu situaci umlčí jen u podpisu, který stejně nemá čím doložit, kdy vznikl.
     */
    private static Instant signatureMoment(SignatureWrapper sw, SimpleReport simpleReport) {
        Instant earliest = null;
        for (TimestampWrapper ts : sw.getTimestampList()) {
            if (ts.getType() != TimestampType.SIGNATURE_TIMESTAMP) continue;
            if (simpleReport.getIndication(ts.getId()) != Indication.PASSED) continue;
            if (ts.getProductionTime() == null) continue;
            Instant t = ts.getProductionTime().toInstant();
            if (earliest == null || t.isBefore(earliest)) earliest = t;
        }
        if (earliest != null) return earliest;
        return sw.getClaimedSigningTime() != null ? sw.getClaimedSigningTime().toInstant() : null;
    }

    private List<Integer> detectFailureSituations(String sigId, DiagnosticData dd,
                                                   SimpleReport simpleReport,
                                                   DetailedReport detailedReport,
                                                   AppSettings settings,
                                                   Instant bestSigTime,
                                                   boolean hasExternalSig,
                                                   boolean s33Detected,
                                                   boolean s45Detected,
                                                   boolean s34Detected,
                                                   List<SkippedRevocation> skippedRevocations,
                                                   boolean derivedProofDecided) {
        boolean totalPassed = simpleReport.getIndication(sigId) == Indication.TOTAL_PASSED;
        SignatureWrapper sw = dd.getSignatureById(sigId);
        if (sw == null) return List.of();

        // When there is no timestamp BST, fall back to the custom validation time as effective BST.
        // This prevents S20/S21 from firing conservatively when the custom time precedes the
        // revocation date — the cert was actually valid at the chosen verification time.
        Instant effectiveBst = bestSigTime;
        if (effectiveBst == null && settings.isUseCustomValidationTime()
                && settings.getCustomValidationTime() != null) {
            effectiveBst = settings.getCustomValidationTime()
                    .atZone(java.time.ZoneId.systemDefault()).toInstant();
        }

        List<Integer> result = new ArrayList<>();

        result.addAll(detectEvidenceRecordSituations(sigId, dd, simpleReport));

        // S20: TOTAL_PASSED but an ignored online OCSP proves the cert was revoked before BST.
        // Occurs in standard mode (acceptNewerOcsp=false): DSS trusts embedded revocation data
        // issued before revocation, while the contradicting online OCSP (thisUpdate > notAfter)
        // is rejected as "newer". The signature appears valid but the cert was already revoked.
        // Also fires when a pre-revocation CRL from the custom store was used (TOTAL_PASSED)
        // but an online OCSP (collected in skippedRevocations) shows revocation before BST.
        if (totalPassed && !settings.isAcceptNewerOcsp()
                && hasIgnoredRevokedOcsp(sw, effectiveBst, skippedRevocations)) {
            result.add(20);
        }


        // S29: TOTAL_PASSED in standards mode, but the signing cert's only embedded revocation
        // was produced by a respondent/CRL-issuer whose certificate had ALREADY EXPIRED at
        // that production time (chainExpiry < thisUpdate). DSS accepted this data via relaxed
        // consistency, but standards mode (!acceptExpiredRevocationSources) should reject it.
        // DIFFERENT from "respondent expired after OCSP issuance": if the respondent cert was
        // still valid when the OCSP was produced, the historical proof stands regardless of
        // whether the respondent has since expired — that case must NOT trigger S29.
        if (totalPassed && !settings.isAcceptExpiredRevocationSources()
                && hasOnlyExpiredAtProductionSourceRevocationForSigningCert(sw)) {
            result.add(29);
        }


        // S24: TOTAL_PASSED verified at a custom validation time; the cert was valid at that time,
        // but online revocation data (issued after cert expiry, hence rejected by standard mode)
        // shows the cert was revoked AFTER the custom time. Shown as an informational note.
        if (totalPassed && settings.isUseCustomValidationTime()
                && settings.getCustomValidationTime() != null) {
            Instant customTime = settings.getCustomValidationTime()
                    .atZone(java.time.ZoneId.systemDefault()).toInstant();
            if (hasLaterRevokedOcsp(sw, customTime)) {
                result.add(24);
            }
        }
        // S24 without a custom time: the certificate is revoked, but a time-stamp proves the
        // signature predates the revocation — the branch on which DSS reaches TOTAL_PASSED
        // (ADEST_IRTPTBST). The verdict is correct, yet "this certificate was revoked later"
        // is exactly the kind of fact the reader should not have to dig out of the reports.
        if (totalPassed && !settings.isUseCustomValidationTime()
                && revokedAfterBestSignatureTime(sw, effectiveBst)) {
            result.add(24);
        }
        // S4 in non-standards timestamp mode: DSS does not check TSA cert notAfter, so it
        // returns TOTAL_PASSED even when the TSA cert has expired. We detect this ourselves
        // so that the UI can override the result to INDETERMINATE and show reason 5.
        if (totalPassed && !settings.isTimestampStandardsMode()
                && !sw.getTimestampList().isEmpty()) {
            Instant now = Instant.now();
            boolean anyExpired = sw.getTimestampList().stream().anyMatch(ts -> {
                Instant exp = chainExpiry(ts.getCertificateChain());
                return exp != null && exp.isBefore(now);
            });
            if (anyExpired) result.add(4);
        }
        // S43 is described where it is detected, a few lines below.
        // S31: nepřerušený časový řetězec — first passed timestamp produced before the signing
        // cert's notAfter, and each subsequent timestamp produced before the previous TSA cert's
        // notAfter. O odvolání situace nemluví; proč, stojí u výpočtu lhůty níž.
        //
        // Holé razítko VRI se do řetězu nepočítá, a to ani u 31, ani u 32. Obě situace popisují
        // řetěz důkazů existence *podpisu*: 31 že nemá mezeru, 32 že jeho poslední článek jde ještě
        // posoudit. Razítko VRI kryje revokační údaje ve slovníku, ne podpis, takže samo o sobě
        // rozhodný okamžik nezakládá — a bez ukotvení nemá „řetěz je nepřerušený" o čem mluvit.
        // Potkáno na priklad16.pdf_verify (3).pdf: jediné razítko v dokumentu je VRI, DSS hodnotí
        // podpis jako BASELINE-B a rozhodný okamžik nemá, a přesto se hlásilo 31 i 32.
        //
        // Vyloučeno je jen „vri_bare", ne „vri". Ten rozdíl zavedl allTimestamps() a je věcný:
        // razítko VRI, které kryje archivní razítko, do rozhodného okamžiku počítá (viz
        // canEstablishBestSignatureTime), takže součástí řetězu důkazů je a vyloučit by se nemělo.
        CertificateWrapper sc31 = sw.getSigningCertificate();
        if (sc31 != null && sc31.getNotAfter() != null) {
            // Lhůtou je konec řádné doby platnosti, a nic jiného. Odvolání se do ní nezapočítává,
            // ačkoli to tak bývalo:
            //
            // 1. Věta, kterou situace 31 v Analýze vysvětluje, mluví <b>jen</b> o expiraci
            //    („některé razítko vzniklo až poté, co skončila platnost certifikátu, který měl
            //    krýt"). Zkracovalo-li lhůtu odvolání, byla ta věta nepravdivá: na
            //    „PAdES B-LT po revokaci 3.pdf" vzniklo razítko 29. 4. 2026, kdežto certifikát
            //    platil do 27. 6. 2026 — po konci platnosti tam nevzniklo nic.
            // 2. Odvolání má vlastní situaci. Že razítko nesmí vzniknout až po odvolání
            //    (EN 319 122-1 čl. 6.3 písm. m), hlásí situace 44, a nabídku uchování hlídají
            //    navíc {@code continuityObstacle()} a {@code signingCertsStillValid} v
            //    {@code AdvisorView}, které se na {@code isCertRevoked()} ptají přímo. Dvojí
            //    hlídání téhož jen zamlčelo, které z obou pravidel zrovna zabralo.
            //
            // Chvíli tu stálo zúžení „jen odvolání, které tenhle běh mohl použít". Řešilo přísný
            // režim, ale se zaškrtnutým „Využij i revokační informace, vydané po expiraci" se
            // nepravdivá věta vracela — vada přitom se zaškrtávátky nesouvisí vůbec.
            Instant deadline31 = sc31.getNotAfter().toInstant();
            List<TimestampEntry> ts31 = allTimestamps(sigId, dd, simpleReport).stream()
                    .filter(TimestampEntry::passed)
                    .filter(e -> !"vri_bare".equals(e.tsKind()))
                    .collect(java.util.stream.Collectors.toList());
            // Souvislost počítá TimestampChain slitím intervalů, ne porovnáním sousedů. Rozdíl je
            // věcný: razítko přidané po expiraci certifikátu toho bezprostředně předchozího mezeru
            // netvoří, pokud v té chvíli ještě platil certifikát některého staršího — o ten se
            // důkaz existence opře. Porovnávání sousedů takovou řadu hlásilo jako přerušenou.
            if (!ts31.isEmpty() && ts31.get(0).time().isBefore(deadline31)
                    && TimestampChain.unbroken(ts31)) {
                result.add(31);
            }
        }

        // S32: řada razítek je dosud krytá platným certifikátem — the chain of passed timestamps
        // still reaches into the future (no sunset grace period needed).
        //
        // Ne „poslední razítko“, nýbrž kam sahá řada: mladší razítko s kratším certifikátem to,
        // co pod ním stojí, nezkracuje. Změřeno 18. 9. 2026 na Úschovně Czech POINT nad
        // „podpis s razitkem-extended-pades-baseline-lta.pdf“ — dokumentové razítko expirovalo
        // 11. 11. 2025, podpis přesto uznán, protože podpisové razítko stojí na certifikátu
        // platném do 31. 12. 2028. Podle starého čtení tomuhle dokumentu chyběla situace 32,
        // takže se mu místo uchování nabízelo jen prodloužení.
        {
            List<TimestampEntry> ts32 = allTimestamps(sigId, dd, simpleReport).stream()
                    .filter(TimestampEntry::passed)
                    .filter(e -> !"vri_bare".equals(e.tsKind()))
                    .collect(java.util.stream.Collectors.toList());
            Instant reach32 = TimestampChain.reachFromStart(ts32);
            if (reach32 != null && Instant.now().isBefore(reach32)) {
                result.add(32);
            }
        }

        // S43: the decisive proof of existence rests on a time-stamping service the Trusted List
        // has since withdrawn. Detected from the same predicate that builds the explanatory text,
        // which is cheap — it reads the Trusted List entry and the sunset date DSS already
        // resolved, nothing more.
        //
        // Only where there is something to explain. On a signature that failed — altered data, a
        // certificate revoked before signing — how a proof of existence is judged is moot, and
        // listing it among the causes would put an irrelevance next to the real one; observed on a
        // deliberately altered test file. On one DSS passed there is something to explain, but not
        // this: saying the validity cannot be established would contradict the result. That case
        // is situation 49, right below.
        if (!totalPassed && simpleReport.getIndication(sigId) != Indication.TOTAL_FAILED
                && withdrawnTimestampServiceDetail(
                        sw, simpleReport.getBestSignatureTime(sigId)) != null) {
            result.add(43);
        }

        // S49: rub situace 43 — verdikt je kladný, a přesto rozhodný okamžik nese razítko, které
        // v době svého vlastního důkazu existence kvalifikované nebylo. Do 29. 8. 2026 se sem nic
        // takového nehlásilo: komentář u situace 43 tvrdil, že kladný výsledek už sám dokazuje
        // důkaz existence z doby před odnětím statusu. Měření to vyvrátilo — kladný výsledek
        // dokazuje jen to, že řetěz razítka končí u kotvy, které se odnětí netýká.
        if (totalPassed
                && unqualifiedProofDetail(sigId, sw, simpleReport, detailedReport) != null) {
            result.add(49);
        }

        // S50: o článek dál — důkaz o rozhodném razítku nese razítko, které samo kvalifikované
        // nebylo. Na 49 nezávisí: rozhodné razítko může mít status v pořádku a viset na důkazu
        // od služby, která ho nemá.
        if (totalPassed
                && unqualifiedProofCarrierDetail(sigId, sw, simpleReport, detailedReport) != null) {
            result.add(50);
        }

        // S52: dokument nese razítko ve slovníku DSS/VRI, které nekryje žádné dokumentové
        // razítko — a podpis nemá důkaz existence odjinud. Tedy: razítko v souboru je, čtenář
        // ho vidí, a přesto se ověřuje k přítomnosti.
        //
        // <p>Proč nepočítá: PAdES uznává pro úroveň T jen atribut signature-time-stamp uvnitř
        // podpisu a dokumentové (archivní) razítko. Token ve VRI záznamu není ani jedno; přibyl
        // inkrementálním updatem a nekryje-li ho dokumentové razítko, může ho kdokoli beze stopy
        // odstranit. Důkaz, který jde odstranit, nemůže nést závěr o platnosti. AnEP to takhle
        // počítal odjakživa — {@code canEstablishBestSignatureTime} ho vylučuje z BST a situace
        // 31 a 32 ho vynechávají —, ale <b>říct to uměl jedině bublinou v Rozšířeném přehledu</b>.
        // V Analýze se čtenář nedozvěděl nic, ačkoli u podpisu bez jiného razítka je právě tohle
        // celá příčina toho, proč ověření nedopadlo.
        //
        // <p><b>Jen když na tom záleží.</b> Ptá se to i na to, že podpis nemá důkaz existence
        // odjinud: kde ho má, je holé VRI podrobnost bez vlivu na výsledek a Rozšířený přehled
        // ji s bublinou ukáže tak jako tak. Táž úvaha jako u situace 43 — hlásit se má tam, kde
        // je co vysvětlovat.
        //
        // <p>Změřeno na plné moci se třemi podpisy PAdES (u všech je holé
        // VRI jediné razítko, všechny INDETERMINATE/OUT_OF_BOUNDS_NO_POE) a na složce
        // {@code AnEP\razitko VRI}, kde dva takové podpisy končí na REVOKED_NO_POE se situací 44
        // — tam by rozdíl byl ještě větší, protože razítko tvrdí čas před odvoláním certifikátu.
        if (bestSigTime == null && hasBareVriTimestamp(sw, simpleReport)) {
            result.add(52);
        }

        // S33: computed externally via computeS33() and passed in to avoid duplicate CDP downloads
        if (s33Detected) result.add(33);

        // S45: the same walk's looser reading — computed alongside S33 and mutually exclusive
        // with it. Reported whatever the verdict, like S33: it describes the material that exists,
        // and a reader looking at an invalid signature is entitled to know the revocation picture
        // is complete on the reading their filing room applies. What is gated on the verdict is
        // the offer of re-stamping, and that is decided in AdvisorView.
        if (s45Detected) result.add(45);

        // S34: computed externally via computeS34() and passed in
        if (s34Detected) result.add(34);

        // S28 used to sit here: TOTAL_PASSED downgraded to INDETERMINATE whenever an embedded
        // revocation came from an issuer that has since expired. Removed 2026-07-28 after a scan
        // of the whole sample corpus (1740 files, 1819 signatures, strict and lenient runs) in
        // which it fired eight times, every one of them a false alarm contradicting DSS. The
        // reason is structural: DSS reaches TOTAL_PASSED in this constellation only through a
        // route that makes the expired issuer irrelevant — the issuer is a trust anchor, a
        // time-stamp proves the data existed while the issuer was valid (PSV), another live
        // revocation is available, or the certificate is revoked after best-signature-time. Each
        // guard added for those cases emptied the situation further, and nothing was left.
        // S5 still reports the expired issuer as a finding on signatures that did not pass.

        // S51: závěr o tom, že certifikát nebyl odvolán, stojí na revokačním údaji, který vznikl
        // dřív než sám podpis — a o okamžiku podepsání tedy nevypovídá nic.
        //
        // <p>Odpověď „neodvolán" mluví ke svému vlastnímu času. Odvolání se přitom zapisuje
        // zpětně (revocationDate), takže „nebyl odvolán v čase T" umí doložit jedině údaj vydaný
        // <b>po</b> T; údaj z doby před podpisem dokládá nanejvýš stav ke své vlastní chvíli.
        // Mezi ním a podpisem může ležet cokoli, odvolání nevyjímaje.
        //
        // <p><b>Odkud to je.</b> Ze sbírky {@code D:\tmp}, složek „po revokaci" a „ICA revokace
        // 2018": úmyslné doklady toho, že Adobe Acrobat Reader podvrh neodhalí. Podepsáno až po
        // odvolání certifikátu a v dokumentu je podstrčena OCSP odpověď z doby před odvoláním.
        // AnEP na nich do 2. 9. 2026 hlásil platný podpis — u „LT po revokaci later LTA
        // timestamped.pdf" s ústupkem k expirovanému vydavateli, u „T 2020 10 15 po revokaci
        // Signer s revinfo.pdf" dokonce <b>bez ústupků, se situací 34</b>, protože archivní
        // razítko dodá důkaz existence té odpovědi v době platnosti jejího vydavatele a past
        // validation projde. Nabídka uchování se u něj rozsvítila také.
        //
        // <p><b>Proč ne přes politiku DSS.</b> Kontrola {@code RevocationFreshness} tam je, ale
        // s {@code Value="0"} ji DSS bere jako „mez neurčena" a spadne na {@code nextUpdate}
        // odpovědi. Změřeno: se stupněm FAIL zachytí případ z roku 2020 (BBB_RFC_IRIF_ANS)
        // a případ z roku 2018 <b>ne</b>, protože I.CA vydává cachované odpovědi s platností
        // kolem 24 hodin a ta z 29. 7. tak „pokrývá" i podpis z 30. 7. Zdejší test na
        // {@code nextUpdate} nehledí, a chytí proto obojí.
        //
        // <p><b>Verdikt to nemění, a schválně.</b> Táž mezera je i u pěti minut — odpověď z 10:44
        // nemluví o 10:49 — a na sbírce ji má zhruba stejně podpisů poctivých jako podvržených
        // (z 200 podpisů jich 29 prošlo s údajem starším než podpis, z toho asi polovina jsou
        // běžné případy, kdy si podepisující program vyžádá OCSP těsně před podpisem). Plošné
        // snižování verdiktu by tedy zasáhlo hlavně nevinné. Co z toho plyne, je sdělení
        // o velikosti té mezery — a odmítnutí uchování, protože razítko by stvrdilo stav,
        // který se možná nikdy nestal (viz {@code AdvisorView.continuityObstacle}).
        //
        // <p>Ptá se to na <b>všechny</b> revokační údaje k podpisovému certifikátu, ne jen na ten
        // využitý: dokud aspoň jeden vznikl v době podpisu nebo po ní, je o co se opřít, a která
        // z nich nesla verdikt, je otázka pro jiné místo. Údaj bez {@code thisUpdate} situaci
        // ruší — o něm se nedá tvrdit, že je starší.
        if (staleRevocationDetail(sw, simpleReport, sigId) != null) {
            result.add(51);
        }

        // S54: závěr o platnosti tohoto prvku stojí na důkazu existence odvozeném z prvku, který
        // ho kryje — sám žádné časové razítko nenese. Nález uživatele z 12. 9. 2026: CzechPOINT
        // takový podpis uznal za platný, kdežto AnEP i DSS ne.
        //
        // <b>Podmínka je jediná a není to odhad</b>: rozhoduje, jestli přepočet k odvozenému
        // okamžiku vyšel kladně (viz {@code ValidatorFactory.derivedTimeResults}). Dokud situace
        // jen doprovázela neznámý výsledek, musela si sama tipovat, jestli by dřívější okamžik
        // pomohl, a měření na 486 souborech k tomu vynutilo tři ruční pravidla: jen INDETERMINATE
        // (dva prvky vycházely TOTAL_FAILED/SIG_CRYPTO_FAILURE, kde žádný důkaz o době vzniku
        // nezmění nic), certifikát v pořádku k tomu okamžiku, a seznam překážek, na které důkaz
        // nedosáhne (jedinou položkou byla NO_CERTIFICATE_CHAIN_FOUND — kotvu důvěry důkaz o čase
        // nevytvoří). Od chvíle, kdy rozhoduje výsledek přepočtu, odpovídá na tutéž otázku sám
        // proces, a to autoritativně: kde by okamžik nepomohl, přepočet kladně nevyjde. Ruční
        // seznam vedle autoritativní odpovědi se dřív nebo později rozejde, takže se neudržuje.
        //
        // <b>Zúžení na pod-indikace končící „NO_POE" zkuseno a zamítnuto</b> ještě v době těch
        // ručních pravidel. Vypadá lákavě — DSS by tím samo pojmenovalo chybějící důkaz jako tu
        // mezeru —, jenže zahodilo devět prvků s CERTIFICATE_CHAIN_GENERAL_FAILURE, které nesou
        // důvod 2, tedy revokační údaj vydaný až po expiraci certifikátu. A to je přesně
        // překážka, kterou dřívější rozhodný okamžik odstraňuje; ta pod-indikace neznamená, co
        // její jméno slibuje, jak se píše i u zjišťování situace 12 o kus níž.
        //
        // Stojí <b>před</b> návratem u kladného verdiktu, protože od chvíle, kdy ten důkaz verdikt
        // nese, je to zjištění o platném prvku: čtenář se musí dozvědět, oč se to „platný" opírá,
        // jinak mu na kartě vyjde kladný závěr u podpisu bez razítka a bez vysvětlení.
        //
        // Situace zůstává bez důvodu: není to závada, na kterou se dá odpovědět radou „co může
        // pomoci", nýbrž vysvětlení kladného závěru.
        if (derivedProofDecided) {
            result.add(54);
        }

        // A TOTAL_PASSED signature has nothing more to explain, so detection ends here — except
        // for S29, which downgrades the verdict itself and would otherwise leave that card with
        // no reasons at all. The checks below are safe to run on a passed signature: SubIndication
        // is null, so S1 and S2 cannot fire, and the rest (S3, S4, S5, S23…) are structural facts.
        if (totalPassed && !result.contains(29)) {
            return List.copyOf(result);
        }

        SubIndication si = simpleReport.getSubIndication(sigId);
        Instant now = Instant.now();

        // S1 a S53: dvě různé vady, které do 3. 9. 2026 padaly do jednoho pytle — a jedna z nich
        // se pak čtenáři hlásila jako druhá.
        //
        // <p>Otisk podepsaných dat (digest matcher) odpovídá na otázku „změnil se po podpisu
        // obsah?“. Podpisová hodnota (isSignatureIntact) odpovídá na jinou: „ověří se podpis
        // veřejným klíčem z certifikátu?“. DSS je hlásí zvlášť a rozcházejí se: HASH_FAILURE
        // znamená první, SIG_CRYPTO_FAILURE nad nezměněným obsahem znamená druhou.
        //
        // <p>Změřeno na „certifikacni a dva uznavane 2015 10 28 do 2026 10.pdf“: certifikační
        // podpis má MESSAGE_DIGEST dataIntact=true (obsah tedy nikdo nezměnil) a přesto
        // TOTAL_FAILED/SIG_CRYPTO_FAILURE. Uvnitř CMS je totiž rozpor — signatureAlgorithm uvádí
        // sha256WithRSAEncryption, ale podpis byl vytvořen nad SHA-1, jak uvádí digestAlgorithm
        // i DigestInfo v samotném RSA bloku. Ověření podpisové hodnoty se SHA-1 sedí, se SHA-256
        // ne; Adobe Reader, Signer i CzechPOINT proto podpis přijmou. Karta AnEP přesto hlásila
        // „neplatný (porušení integrity)“ a důvod 7 k tomu psal „Došlo ke změně podepsaného
        // obsahu“ — o dokumentu, do kterého nikdo nezasáhl.
        boolean contentChanged = si == SubIndication.HASH_FAILURE
                || sw.getDigestMatchers().stream()
                        .anyMatch(dm -> dm.isDataFound() && !dm.isDataIntact());
        if (contentChanged) {
            result.add(1);
        } else if (si == SubIndication.SIG_CRYPTO_FAILURE && !sw.isSignatureIntact()) {
            result.add(53);
        }

        boolean hasPassedTs = sw.getTimestampList().stream()
                .filter(ts -> canEstablishBestSignatureTime(ts, sw, simpleReport))
                .anyMatch(ts -> simpleReport.getIndication(ts.getId()) == Indication.PASSED);

        // S2: Signing cert revoked with no POE before the revocation date.
        // Fires when (a) a valid timestamp provides a BST at or after the revocation date,
        // proving the signing definitely happened after the cert was already revoked; OR
        // (b) the user enabled lenient OCSP mode (acceptNewerOcsp=true) AND the revocation
        // really is the post-expiry kind that the option lets through. Without a timestamp
        // proving signing occurred before the revocation, AnEP conservatively treats the
        // signature as invalid.
        // In standard mode without a timestamp, REVOKED_NO_POE stays as INDETERMINATE (neznámý).
        //
        // Druhá podmínka u větve (b) přibyla 27. 8. 2026. Bez ní stačilo zaškrtnout toleranci
        // a S2 se indikovala u každého REVOKED_NO_POE — tedy i tam, kde žádný post-expirační
        // údaj není a to zaškrtávátko nemá do čeho mluvit. Verdikt se pak překlopil z „neznámý"
        // na „neplatný (revokace certifikátu ještě před podepsáním)", ačkoli o čase vzniku
        // podpisu se nezměnilo nic a tvrdit ho nešlo ani předtím. Vidět na
        // „PAdES B-B kvalifikovany ICA.pdf": certifikát ještě platí, revokace je doložena
        // bezvadnou on-line odpovědí z doby platnosti a razítko chybí — takový podpis musí
        // zůstat neznámý v obou režimech. Nově tam v obou nastane situace 48.
        if (si == SubIndication.REVOKED_NO_POE
                && (hasPassedTs
                    || (settings.isAcceptNewerOcsp() && hasPostExpiryRevokedEntry(sw)))) {
            result.add(2);
        }

        // S44: the revocation stands in the way of preserving the document, and nothing the user
        // does can move it. S2 and S30 say the certificate was revoked; this says what follows from
        // it, which is the question the reader actually has in front of them when the Uchování
        // button is not there — it is withheld for any verdict that is not TOTAL_PASSED, silently.
        //
        // The refusal is not AnEP being careful. Preserving means adding an archive time-stamp, and
        // EN 319 122-1 clause 6.3 requirement m) has such a token created before the certificate was
        // revoked or expired; EN 319 102-1 clause 4.3.3.4 NOTE 2 says the same. DSS enforces it and
        // ends the augmentation with "Revoked/Suspended certificate(s) detected". A document that
        // did get preserved would also read as stronger than it is: its verdict rests on revocation
        // data out on the network, which the preserved copy would not carry.
        //
        // Every revocation sub-indication counts, not only the signing certificate's own: a revoked
        // CA in the chain blocks preservation exactly as thoroughly.
        if (si == SubIndication.REVOKED || si == SubIndication.REVOKED_NO_POE
                || si == SubIndication.REVOKED_CA_NO_POE) {
            result.add(44);
        }

        // S3: No timestamps at all and the signing certificate has already expired
        if (sw.getTimestampList().isEmpty()) {
            CertificateWrapper sigCert = sw.getSigningCertificate();
            if (sigCert != null && sigCert.getNotAfter() != null
                    && sigCert.getNotAfter().toInstant().isBefore(now)) {
                result.add(3);
            }
        }

        // S23: At least one timestamp has TimestampQualification.NA — its TSA is not on the EUTL.
        // Checked across all timestamp types: PAdES uses DOCUMENT_TIMESTAMP, CAdES/XAdES use
        // SIGNATURE_TIMESTAMP; both can be from a non-EUTL TSA.
        // NA status is a structural fact independent of validation mode — in lenient mode DSS
        // may accept other timestamps as PASSED, but the non-EUTL timestamp is a real concern
        // the user should know about regardless. Does not fire for TOTAL_PASSED (early return).
        if (!sw.getTimestampList().isEmpty()
                && sw.getTimestampList().stream()
                        .anyMatch(ts -> simpleReport.getTimestampQualification(ts.getId())
                                == TimestampQualification.NA)) {
            result.add(23);
        }

        // S4: Timestamps exist but at least one TSA certificate chain has expired
        if (!sw.getTimestampList().isEmpty()) {
            boolean anyExpired = sw.getTimestampList().stream().anyMatch(ts -> {
                Instant exp = chainExpiry(ts.getCertificateChain());
                return exp != null && exp.isBefore(now);
            });
            if (anyExpired) result.add(4);
        }

        // S5: Embedded revocation present but the respondér/CRL-issuer cert chain has expired.
        // A non-TOTAL_PASSED finding: on a signature DSS accepted, the expired issuer provably
        // did not stand in the way, so reporting it there would only confuse.
        //
        // Potlačeno, když je ústupek k expirovanému vydavateli už zapnutý — táž úvaha, jakou
        // vedou situace 6 a 29, jen sem se nedostala. Bez toho z S5 vznikl důvod 6, jehož rada
        // zní „zapněte v nastavení volbu Využij i revokační informace od již expirovaného
        // vydavatele" — a ta už zapnutá byla. Karta tedy doporučovala povolit povolené a čtenář
        // z ní odešel s tím, že cesta vede přes zaškrtávátko, ačkoli zaškrtnuté je.
        //
        // Vidět na „PAdES B kvalifikovany s razitkem v dokumentu.pdf" ověřeném se všemi ústupky:
        // situace [2, 5, 33, 44, 52] a mezi důvody 6 vedle 9 a 27. Opraveno tím, že údaj, který
        // tohle nastavení pouští dál, přestane být hlášen jako překážka — přesně jak to o sobě
        // píše situace 6.
        //
        // Bere to zároveň falešné „při svém aktuálním nastavení" ve větě
        // ContinuityElementBlocks.lostOutlook(), která se ptá právě na důvody 2 a 6: kde je ústupek
        // zapnutý, nastavení už pomoct nemůže a věta to nemá naznačovat.
        if (!settings.isAcceptExpiredRevocationSources()
                && hasExpiredEmbeddedRevocationSource(sw)) {
            result.add(5);
        }

        // S46: revokační údaj nelze použít, protože certifikát jeho vydavatele k rozhodnému
        // okamžiku neplatil. Spraví to jedině volba EXPIRED_REVOCATION_SOURCES — ta samá podmínka
        // se odečítá v certHasNewerRevocation(), aby situace 6 neposílala uživatele k volbě, která
        // s výsledkem nehne. Bez téhle situace zůstal takový podpis úplně bez vodítka: S5 mlčí
        // (hlásí jen vložená data od vydavatele po expiraci), S6 po opravě také, a to správně.
        if (!settings.isAcceptExpiredRevocationSources()
                && hasOutOfRangeRevocationIssuer(sw, settings.getValidationDate())) {
            result.add(46);
        }

        // S35: verification per the standards found no usable revocation information, but the
        // data needed is already in the document — the only thing missing is a proof that it
        // existed while it was still verifiable. Adding an archive time-stamp (Uchování) supplies
        // that proof and lets a conformant validator reach TOTAL_PASSED.
        if (si == SubIndication.OUT_OF_BOUNDS_NO_POE
                && !(sw.isThereALevel() && sw.isALevelTechnicallyValid())
                && preservationWouldRestoreValidity(sw, simpleReport, now)) {
            result.add(35);
        }

        // S6: Revocation data rejected because "Využij i revokační informace, vydané po expiraci certifikátu" would fix this.
        // Uses hasNewerRevocationAnywhere() — same predicate as computeDecisiveSettings — so
        // any case where the setting is decisive is reliably detected as a situation.
        // Covers OCSP and CRL, Case A (thisUpdate > cert.notAfter) and Case B (stale).
        // Suppressed when acceptNewerOcsp is already enabled: DSS then uses the data and the
        // actual revocation state (S2 = REVOKED_NO_POE) is reported instead.
        if (!settings.isAcceptNewerOcsp()
                && hasNewerRevocationAnywhere(sw, dd, settings.getValidationDate())) {
            result.add(6);
        }

        // S21: INDETERMINATE with an ignored online OCSP that proves revocation before BST.
        // Same helper as S20, but applies when totalPassed=false (typically INDETERMINATE + S6).
        // S30: same condition, !totalPassed only — short display label for "Zjištěné příčiny" block.
        if (!settings.isAcceptNewerOcsp() && hasIgnoredRevokedOcsp(sw, effectiveBst, skippedRevocations)) {
            result.add(21);
            if (!totalPassed) result.add(30);
        }

        // S48: revokovaný certifikát, o kterém dosud nemluvila žádná situace.
        //
        // REVOKED_NO_POE znamená, že ověřování mělo revokační údaj, přijalo ho a došlo podle něj
        // k závěru „odvolán". Chybí druhá polovina — důkaz, kdy podpis vznikl — a tak standardy
        // nedovolí prohlásit podpis za neplatný (mohl vzniknout před revokací), ani za platný
        // (mohl vzniknout po ní). Verdikt zůstává neznámý a to je správně.
        //
        // Dosud to ale bylo jediné, co se čtenář dozvěděl: S2 mlčí, protože nemá důkaz existence
        // ani zapnutou toleranci; S21/S30 mlčí, protože ty popisují údaj vydaný až po expiraci
        // certifikátu, kdežto zde je údaj z doby platnosti a bezvadný; S6 mlčí ze stejného
        // důvodu. Na kartě tak zbyla prázdná hlavička „Zjištěné důvody a doporučení" a nikde ani
        // slovo o revokaci — u certifikátu, který ještě ani nevypršel, je to ta nejdůležitější
        // věc, kterou o něm aplikace ví. Změřeno na „PAdES B-B kvalifikovany ICA.pdf".
        //
        // Vzájemně výlučná s S2 (tam je důkaz a verdikt padá na neplatný) i s S30 (tam překážkou
        // není chybějící důkaz času, ale stáří samotného revokačního údaje).
        if (si == SubIndication.REVOKED_NO_POE && !result.contains(2) && !result.contains(30)) {
            result.add(48);
        }

        // S7: Online OCSP obtained but reports certificate status UNKNOWN.
        //
        // Druhý pohled je do sběrače přeskočených odpovědí, a bez něj situace na půl dokumentů
        // nedosáhne. Má-li uživatel vlastní CRL, běží v přísném režimu
        // OcspFirstCrlFallbackRevocationDataLoadingStrategy: post-expirační OCSP si poznamená a
        // DSS předá CRL, takže do DiagnosticData ta odpověď nikdy nedorazí. Změřeno na
        // „uznavany 12 12 2021 eIdentity signed.pdf“ (eIdentity, certifikát do 21. 6. 2022):
        // přísně nevznikl žádný důvod — S6 tu CRL právem vylučuje (post-expirační, bez
        // expiredCertsOnCRL), S8 je na vložená data, S9 chce certifikát bez jediného záznamu —
        // kdežto po zapnutí tolerance se táž odpověď objevila v DiagnosticData a s ní důvod 10.
        // Týž dokument tak vyprávěl dva různé příběhy podle nastavení, které se stavu „nevím“
        // vůbec netýká.
        if (!unknownStatusCerts(sw, skippedRevocations).isEmpty()) result.add(7);

        // S8: Embedded revocation present but unusable — not covered by S5 (respondér expired)
        // and not an UNKNOWN-status entry. Most common cause: embedded OCSP with
        // thisUpdate > cert notAfter. Also detected via REVOCATION_OUT_OF_BOUNDS_NO_POE.
        //
        // Not when S6 has already said it. The parenthesis in the old comment here — "strict DSS
        // rejects it just as for online, but the relevant setting is about online OCSP" — was the
        // assumption that made this worth reporting separately, and it is wrong: the tolerance for
        // revocation issued after expiry applies to embedded data exactly as it does to fetched
        // data. Measured on a re-stamped PAdES-T whose only material was an embedded post-expiry
        // OCSP: enabling that one setting turns it TOTAL_PASSED. So S8 was adding a second entry
        // for the same fact, and through reason 11 a remedy that does not apply — put a usable CRL
        // in the custom store — beside reason 2, which names the setting that actually helps.
        //
        // Rare before, since a document had to arrive already carrying such a response. Re-stamping
        // makes it the ordinary outcome, which is how it came to light.
        if (!result.contains(6) && !unusableEmbeddedCerts(sw, si).isEmpty()) result.add(8);

        // S9: Failed to get any online revocation data.
        // Direct indicator: DSS received a TRY_LATER OCSP response.
        // Also: a non-anchor, non-self-signed cert has zero revocation entries — neither
        // embedded nor online was found/stored (network unreachable or OCSP/CRL unavailable).
        if (si == SubIndication.TRY_LATER || hasCertWithoutRevocation(sw, ChainScope.ALL)) {
            result.add(9);
        }

        // S10: Signing certificate was not yet valid at the time of signing
        if (si == SubIndication.NOT_YET_VALID) {
            result.add(10);
        }

        // S25 / S26: signing time outside cert validity window, detected from data.
        // Uses certified BST (from a valid timestamp) if available; falls back to the claimed
        // signing-time attribute (unreliable, but better than nothing when there is no BST).
        // S25: signed AFTER cert.notAfter — cert was already expired at signing time.
        //      Complements S3 (which only checks cert.notAfter vs. current time and only fires
        //      when there is no timestamp at all); S25 fires regardless of timestamp presence.
        // S26: signed BEFORE cert.notBefore — cert not yet valid. Complements S10 (DSS
        //      SubIndication NOT_YET_VALID); fires from data when DSS does not flag it.
        {
            CertificateWrapper sc = sw.getSigningCertificate();
            if (sc != null) {
                Instant claimedTime = simpleReport.getSigningTime(sigId) != null
                        ? simpleReport.getSigningTime(sigId).toInstant() : null;
                Instant sigTimeCheck = bestSigTime != null ? bestSigTime : claimedTime;
                if (sigTimeCheck != null) {
                    if (sc.getNotAfter() != null
                            && sigTimeCheck.isAfter(sc.getNotAfter().toInstant())) {
                        result.add(25);
                    }
                    if (!result.contains(10) && sc.getNotBefore() != null
                            && sigTimeCheck.isBefore(sc.getNotBefore().toInstant())) {
                        result.add(26);
                    }
                }
            }
        }

        // S11: A CA certificate in the chain was revoked (no POE before revocation)
        if (si == SubIndication.REVOKED_CA_NO_POE) {
            result.add(11);
        }

        // S12: Certificate chain cannot be built to a trusted anchor.
        // Detected either via SubIndication or directly by inspecting whether any cert in the
        // signing chain has isTrusted() == true (i.e. is anchored in the EU Trusted List or the
        // custom trust store that was loaded during validation).
        //
        // CERTIFICATE_CHAIN_GENERAL_FAILURE is the one sub-indication that does not mean what its
        // name suggests. DSS returns it from the X.509 validation process for anything that leaves
        // the chain inconclusive, revocation included: a PostSignum signature whose only available
        // OCSP was post-expiry came out as CERTIFICATE_CHAIN_GENERAL_FAILURE / BBB_XCV_IARDPFC_ANS
        // ("no acceptable revocation data") while both the CA and the root sat in the EU Trusted
        // List. Claiming a missing trust anchor there is simply false, so that sub-indication only
        // counts when the chain really reaches none — which is the same test as the branch below.
        boolean noAnchor = sw.getCertificateChain().isEmpty()
                || sw.getCertificateChain().stream().noneMatch(CertificateWrapper::isTrusted);
        if (si == SubIndication.NO_CERTIFICATE_CHAIN_FOUND
                || si == SubIndication.NO_CERTIFICATE_CHAIN_FOUND_NO_POE
                || (si == SubIndication.CERTIFICATE_CHAIN_GENERAL_FAILURE && noAnchor)) {
            result.add(12);
        } else if (!sw.getCertificateChain().isEmpty() && noAnchor) {
            result.add(12);
        }

        // S13: Signed data not found — the file that was signed is not present.
        // Typical for detached XAdES/CAdES where the original document was not provided.
        if (si == SubIndication.SIGNED_DATA_NOT_FOUND) {
            result.add(13);
        } else if (sw.getDigestMatchers().stream()
                .anyMatch(dm -> !dm.isDataFound() && isSignedContentType(dm.getType()))) {
            result.add(13);
        }

        // S15: SHA-1 digest algorithm detected.
        // Suppressed when the user has explicitly enabled the SHA-1 relaxation setting.
        if (!settings.isIgnoreSha1Deprecated() && usesSha1(sw, dd)) {
            result.add(15);
        }

        // S16: MD5 or RIPEMD-160 digest algorithm detected — critically deprecated algorithms
        // for which no relaxation is available in the application settings.
        if (usesCriticallyDeprecated(sw, dd)) {
            result.add(16);
        }

        // S56: hašovací funkce, kterou validační politika vůbec nepřipouští — chybí v seznamu
        // AcceptableDigestAlgo výchozí politiky DSS s poznámkou „Not referenced in ETSI/SOGIS"
        // (SHA3-224, MD2, SHAKE). Není slabá, jako MD5 v situaci 16, jen ji ETSI TS 119 312
        // neuvádí. Do 4. 10. 2026 pro ni AnEP neměl důvod a podpis končil jako neznámý bez
        // jediného indikovaného důvodu — nalezeno skenem sbírky „bajecny svet" na PAdES LT
        // s otiskem SHA3-224 (CRYPTO_CONSTRAINTS_FAILURE_NO_POE, důvody prázdné).
        //
        // Podmínkou je neúspěšné ověření, ne dílčí indikace CRYPTO_CONSTRAINTS_FAILURE. Ta se
        // zkoušela první a nestačila: DSS hlásí jen první překážku, a kde je dřív revokační
        // údaj vydaný po expiraci, končí na CERTIFICATE_CHAIN_GENERAL_FAILURE a o otisku mlčí.
        // Čtenář pak viděl jen důvod 2, zaškrtl ústupek — a teprve potom se objevil důvod 35.
        // Nález uživatele 4. 10. 2026 nad PAdES T se SHA3-224; ve sbírce takové byly tři ze
        // čtyř podpisů se SHA3-224. Otisk mimo politiku podpis zastaví vždy, takže u neúspěšného
        // ověření je příčinou, i když ho DSS nejmenuje první.
        if (!totalPassed && usesDigest(sw, dd, ReportMapper::isNotAccepted)) {
            result.add(56);
        }

        // S14: Hash mismatch specifically on externally-referenced content (detached signature).
        // In contrast to S1 (which covers any integrity violation including enveloped),
        // this indicates the signature and the provided document are most likely an incorrect
        // pair — the signature belongs to a different file, not that the file was tampered with.
        if (hasExternalSig && sw.getDigestMatchers().stream().anyMatch(dm -> {
            if (!dm.isDataFound() || dm.isDataIntact()) return false;
            DigestMatcherType t = dm.getType();
            // CAdES, JAdES: always external/detached content references
            if (t == DigestMatcherType.MESSAGE_DIGEST
                    || t == DigestMatcherType.CONTENT_DIGEST
                    || t == DigestMatcherType.SIG_D_ENTRY) return true;
            // XAdES REFERENCE: external when URI is non-empty and not a fragment (#id)
            if (t == DigestMatcherType.REFERENCE) {
                String uri = dm.getUri();
                return uri != null && !uri.isEmpty() && !uri.startsWith("#");
            }
            return false;
        })) {
            result.add(14);
        }

        // S17: PDF-NOT-ETSI — plain PDF digital signature, not conforming to PAdES (ETSI EN 319 102-2).
        // Most commonly caused by absence of the mandatory signing-certificate signed attribute.
        // Typical of pre-eIDAS qualified signatures created under the old EU Directive 1999/93/EC.
        // Suppressed when the user has explicitly enabled the PDF-NOT-ETSI relaxation setting
        // (analogous to S15 suppression via ignoreSha1Deprecated).
        // S18: PKCS #7 — CMS/PKCS #7 signature, not conforming to CAdES (ETSI EN 319 122-1).
        // Both formats fail SIG_CONSTRAINTS_FAILURE under the standard DSS validation policy.
        SignatureLevel sigLevel = sw.getSignatureFormat();
        if (!settings.isAcceptPdfNotEtsi() && sigLevel == SignatureLevel.PDF_NOT_ETSI) {
            result.add(17);
        } else if (sigLevel != null && sigLevel.toString().startsWith("PKCS7")) {
            result.add(18);
        }

        // S47: revokační údaj se stáhnout podařilo, jenže o certifikátu nic neříká. Doplňuje S9
        // (nedorazilo nic) z druhé strany: dorazilo něco bezcenného, a verdikt přesto padne na
        // „nelze rozhodnout“. Bez téhle situace zůstal takový podpis úplně bez vysvětlení —
        // S6 tenhle případ právem vylučuje (zaškrtnutí tolerance na něj nedosáhne, protože
        // překážkou není stáří údaje, ale to, že v něm certifikát nefiguruje), S8 je na vložená
        // data a S9 chce certifikát bez jediného záznamu.
        if (!uninformativeExternalCerts(sw).isEmpty()) {
            result.add(47);
        }

        // S22: CAdES/XAdES/ASiC signature missing the 'signing-time' signed attribute.
        // In the standard DSS validation policy, BBB_SAV_ISQPSTP (signing-time present)
        // is a mandatory constraint in the LTV process — absence causes SIG_CONSTRAINTS_FAILURE.
        // PDF_NOT_ETSI (S17) and PKCS7 (S18) also produce SIG_CONSTRAINTS_FAILURE for related
        // reasons; exclude them to avoid double-reporting.
        // „Chybí“ je přitom to, co tvrdí DSS: atribut v podpisu být může, jen ho DSS nepřečte
        // (GeneralizedTime místo UTCTime). Takový podpis nese SignatureSummary.isSigningTimeUnreadable()
        // a texty situace 22 a důvodu 18 o něm mluví jinak — viz ValidatorFactory.unreadableSigningTimes.
        //
        // S55 (3. 10. 2026): deklarovaný čas tam je, jen leží mimo dobu platnosti podpisového
        // certifikátu — kontrola SigningTimeInCertRange (BBB_SAV_ISQPSTWSCVR). Do té doby z toho
        // byla situace 22 a čtenář četl „podpis neobsahuje atribut signing-time“ nad podpisem,
        // který ho má (rozdily v case\rozdil v case*.pdf, /M 17. 10. 2017, certifikát vydán později).
        //
        // Od 3. 10. 2026 (téhož dne) je situace 22 jen poznámka: požadavek na signing-time AnEP
        // trvale ztlumil (ValidationPolicyFactory.applySigningTimeRelaxation), takže na verdikt
        // nepůsobí a důvod 18 se nevydává. Hlásí se podle toho, zda DSS deklarovaný čas zná —
        // validátory na výchozí politice DSS takový podpis odmítnou, a to čtenář vědět má.
        // Situace 55 je od 4. 10. 2026 také jen poznámka: SigningTimeInCertRange je v politice AnEP
        // ztlumený (ValidationPolicyFactory.applySigningTimeRelaxation) a důvod 34 se nevydává.
        if (sigLevel != SignatureLevel.PDF_NOT_ETSI
                && (sigLevel == null || !sigLevel.toString().startsWith("PKCS7"))
                && claimedTimeOutsideCertificate(sw)) {
            result.add(55);
        }
        if (sw != null && sw.getClaimedSigningTime() == null
                && sigLevel != SignatureLevel.PDF_NOT_ETSI
                && (sigLevel == null || !sigLevel.toString().startsWith("PKCS7"))) {
            result.add(22);
        }

        // S27: at least one timestamp could not be verified as PASSED — it doesn't serve as PoE,
        // regardless of why (SHA1, untrusted TSA, past validation failure, etc.).
        // Fires even when other timestamps did pass; each failed timestamp is a separate concern.
        if (sw.getTimestampList().stream()
                .anyMatch(ts -> simpleReport.getIndication(ts.getId()) != Indication.PASSED)) {
            result.add(27);
        }

        // S36: a narrower, harder case of S27 — the timestamp token is itself cryptographically
        // inconsistent, rather than merely unverifiable. Evaluated over the same timestamp list
        // as S27, so S36 can never fire without it: a broken token always ends up with an
        // indication other than PASSED.
        if (sw.getTimestampList().stream().anyMatch(ReportMapper::hasBrokenTimestampIntegrity)) {
            result.add(36);
        }

        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    /**
     * True when an online revocation entry for the signing cert chain shows REVOKED,
     * was issued after cert.notAfter (the "newer" condition that standard mode rejects),
     * and the revocation time precedes BST (or BST is unknown — conservative).
     *
     * <p>Also checks {@code skippedRevocations} — post-expiry OCSPs that were fetched by
     * {@link OcspFirstCrlFallbackRevocationDataLoadingStrategy} but not returned to DSS
     * because a CRL from the custom store was used instead.  These are invisible to DSS's
     * DiagnosticData but must still trigger the S20/S21 warning.
     */
    /**
     * Certifikáty, o jejichž stavu respondér řekl „nevím“ — ať už ta odpověď dorazila do
     * DiagnosticData, nebo ji {@link OcspFirstCrlFallbackRevocationDataLoadingStrategy}
     * přeskočila ve prospěch CRL z vlastního úložiště.
     *
     * <p>Jeden zdroj pro dvě věci: situaci 7 (nastala, není-li výčet prázdný) a okno „kde vzít
     * CRL“ u důvodu 10. Kdyby si je hledala každá po svém, mohla by být situace indikována nad
     * prázdným oknem — nebo naopak.
     */
    private static List<CertificateWrapper> unknownStatusCerts(SignatureWrapper sw,
                                                                List<SkippedRevocation> skippedRevocations) {
        return unknownStatusCerts(sw, skippedRevocations, ChainScope.ALL);
    }

    private static List<CertificateWrapper> unknownStatusCerts(SignatureWrapper sw,
                                                                List<SkippedRevocation> skippedRevocations,
                                                                ChainScope scope) {
        List<CertificateWrapper> out = new ArrayList<>();
        for (List<CertificateWrapper> chain : signingAndTimestampChains(sw, scope)) {
            for (CertificateWrapper c : chain) {
                if (c.isTrusted()) break;
                boolean unknown = c.getCertificateRevocationData().stream()
                        .anyMatch(rev -> !rev.isInternalRevocationOrigin()
                                && rev.getRevocationType() == RevocationType.OCSP
                                && rev.getStatus() == CertificateStatus.UNKNOWN);
                if (!unknown) {
                    for (SkippedRevocation sr : skippedRevocations) {
                        if (c.getId().equals(sr.certId())
                                && sr.status() == CertificateStatus.UNKNOWN) {
                            unknown = true;
                            break;
                        }
                    }
                }
                if (unknown && out.stream().noneMatch(x -> x.getId().equals(c.getId()))) out.add(c);
            }
        }
        return out;
    }

    /**
     * Certifikáty s vloženým revokačním údajem, který nejde použít z jiného důvodu než expirace
     * respondéra (tu hlásí S5) — typicky vložená OCSP s {@code thisUpdate} po {@code notAfter}
     * certifikátu. Táž dvojrole jako u {@link #unknownStatusCerts}: situace 8 a okno „kde vzít
     * CRL“ u důvodu 11.
     *
     * <p>Širší záběr přes pod-indikaci {@code REVOCATION_OUT_OF_BOUNDS_NO_POE} zůstává: tam DSS
     * říká, že načasování vložených dat je mimo, aniž by šlo ukázat na jedinou podmínku, takže
     * se vypíšou všechny certifikáty, které nějaký vložený údaj nesou.
     */
    private static List<CertificateWrapper> unusableEmbeddedCerts(SignatureWrapper sw, SubIndication si) {
        return unusableEmbeddedCerts(sw, si, ChainScope.ALL);
    }

    private static List<CertificateWrapper> unusableEmbeddedCerts(SignatureWrapper sw, SubIndication si,
                                                                  ChainScope scope) {
        List<CertificateWrapper> out = new ArrayList<>();
        Instant now = Instant.now();
        List<List<CertificateWrapper>> chains = signingAndTimestampChains(sw, scope);
        for (List<CertificateWrapper> chain : chains) {
            for (CertificateWrapper c : chain) {
                if (c.isTrusted()) break;
                if (c.getNotAfter() == null) continue;
                for (CertificateRevocationWrapper rev : c.getCertificateRevocationData()) {
                    if (!rev.isInternalRevocationOrigin()) continue;
                    if (rev.getStatus() == CertificateStatus.UNKNOWN) continue; // excluded by definition
                    // Respondér cert expired → already covered by S5
                    Instant respExpiry = chainExpiry(rev.getCertificateChain());
                    if (respExpiry != null && respExpiry.isBefore(now)) continue;
                    // Embedded OCSP with thisUpdate after the cert's notAfter
                    if (rev.getRevocationType() == RevocationType.OCSP
                            && rev.getThisUpdate() != null
                            && rev.getThisUpdate().after(c.getNotAfter())) {
                        if (out.stream().noneMatch(x -> x.getId().equals(c.getId()))) out.add(c);
                        break;
                    }
                }
            }
        }
        if (out.isEmpty() && si == SubIndication.REVOCATION_OUT_OF_BOUNDS_NO_POE) {
            for (List<CertificateWrapper> chain : chains) {
                for (CertificateWrapper c : chain) {
                    if (c.isTrusted()) break;
                    boolean anyEmbedded = c.getCertificateRevocationData().stream()
                            .anyMatch(CertificateRevocationWrapper::isInternalRevocationOrigin);
                    if (anyEmbedded && out.stream().noneMatch(x -> x.getId().equals(c.getId()))) out.add(c);
                }
            }
        }
        return out;
    }

    /**
     * Certifikáty, ke kterým se stažený seznam odvolaných certifikátů (CRL) sice našel, ale
     * o nich nic neříká: vyšel až po skončení jejich platnosti a nesahá do ní ani přes
     * expiredCertsOnCRL. Podle RFC 5280 čl. 3.3 smí vydavatel po expiraci záznam vypustit, takže
     * mlčení takového seznamu není důkaz — a {@link #revocationCoversCertificate} je týž test,
     * jakým to měří DSS (BBB_XCV_REVOC_HAS_CERT_INFO).
     *
     * <p>Táž dvojrole jako u {@link #unknownStatusCerts}: situace 47 a okno „kde vzít CRL“
     * u důvodu 25. Vložený údaj, který certifikát pokrývá, situaci ruší — pak je čím ověřovat.
     */
    private static List<CertificateWrapper> uninformativeExternalCerts(SignatureWrapper sw) {
        return uninformativeExternalCerts(sw, ChainScope.ALL);
    }

    private static List<CertificateWrapper> uninformativeExternalCerts(SignatureWrapper sw,
                                                                       ChainScope scope) {
        List<CertificateWrapper> out = new ArrayList<>();
        for (List<CertificateWrapper> chain : signingAndTimestampChains(sw, scope)) {
            for (CertificateWrapper c : chain) {
                if (c.isTrusted()) break;
                if (c.getNotAfter() == null) continue;
                boolean anyUselessCrl = false;
                boolean anyCovering = false;
                for (CertificateRevocationWrapper rev : c.getCertificateRevocationData()) {
                    if (revocationCoversCertificate(rev, c.getNotAfter())) {
                        anyCovering = true;
                        continue;
                    }
                    // Jen stažený seznam. Post-expirační OCSP je věc situace 6: tam tolerance
                    // opravdu pomůže, protože odpověď o certifikátu mluví, jen pozdě — kdežto
                    // seznam, ve kterém certifikát nefiguruje, nepustí ke slovu žádné nastavení.
                    // Změřeno: bez tohoto zúžení situace vzplála i u podpisu, kde je jediným
                    // staženým údajem post-expirační OCSP a důvod 2 radí správně.
                    if (!rev.isInternalRevocationOrigin()
                            && rev.getRevocationType() == RevocationType.CRL) {
                        anyUselessCrl = true;
                    }
                }
                if (anyUselessCrl && !anyCovering
                        && out.stream().noneMatch(x -> x.getId().equals(c.getId()))) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /**
     * Které řetězce revokační nález prohledává.
     *
     * <p>Zavedeno kvůli potlačení u neznámé kotvy (důvod 3). Revokační situace 5 až 9, 46 a 47
     * měří podpisový řetěz i řetězy razítek dohromady a v čísle situace se ten rozdíl ztratí —
     * jenže rozhodující je: řetěz razítka může být ukotvený, i když podpisový není, a tam rada
     * o revokačních údajích vede k cíli. {@code TIMESTAMPS_ONLY} na to odpoví.
     */
    private enum ChainScope {
        /** Podpisový řetězec a řetězce všech přiložených razítek. */
        ALL,
        /** Jen řetězce razítek — tedy nález, který neznámá kotva podpisového certifikátu nesráží. */
        TIMESTAMPS_ONLY
    }

    /** Podpisový řetězec a řetězce všech přiložených razítek — v tomhle pořadí je čtou situace 5 až 9. */
    private static List<List<CertificateWrapper>> signingAndTimestampChains(SignatureWrapper sw) {
        return signingAndTimestampChains(sw, ChainScope.ALL);
    }

    private static List<List<CertificateWrapper>> signingAndTimestampChains(SignatureWrapper sw,
                                                                            ChainScope scope) {
        List<List<CertificateWrapper>> chains = new ArrayList<>();
        if (scope == ChainScope.ALL) chains.add(sw.getCertificateChain());
        for (TimestampWrapper ts : sw.getTimestampList()) {
            chains.add(ts.getCertificateChain());
        }
        return chains;
    }

    /**
     * Jak text pojmenuje certifikát, kterého se revokační nález týká — v 6. pádě, protože věty
     * o něm mluví jako „o …".
     *
     * <p>Kontrola textů 21. 9. 2026: důvody 24 a 25 začínaly „o certifikátu" a čtenář neměl jak
     * poznat, o který jde. Dopsat „podpisovém" nešlo: situace 46 a 47 chodí po podpisovém řetězu
     * i po řetězech razítek, takže ten certifikát může být klidně certifikát razítkovací
     * autority. Jmenuje se proto ten, na kterém nález opravdu vznikl.
     */
    private static String revocationSubject(SignatureWrapper sw, CertificateWrapper c,
                                             boolean seal) {
        CertificateWrapper signing = sw.getSigningCertificate();
        if (signing != null && signing.getId().equals(c.getId())) {
            return seal ? "certifikátu pečeti" : "podpisovém certifikátu";
        }
        String name = c.getReadableCertificateName();
        for (CertificateWrapper x : sw.getCertificateChain()) {
            if (x.getId().equals(c.getId())) {
                return "certifikátu vydavatele „" + name + "“";
            }
        }
        return "certifikátu „" + name + "“ z řetězu časového razítka";
    }

    /**
     * Je v řetězu certifikát bez jediného revokačního záznamu — vloženého či staženého? Podmínka
     * situace 9, vytažená z ní, aby šla položit i samotným řetězům razítek.
     *
     * <p>Chodí se od koncového certifikátu vzhůru a končí se u kotvy důvěry nebo u certifikátu
     * podepsaného sebou samým: u kotvy se stav revokace nezjišťuje a kořen o sobě nevypovídá.
     */
    private static boolean hasCertWithoutRevocation(SignatureWrapper sw, ChainScope scope) {
        for (List<CertificateWrapper> chain : signingAndTimestampChains(sw, scope)) {
            for (CertificateWrapper c : chain) {
                if (c.isTrusted() || c.isSelfSigned()) break;
                if (c.getCertificateRevocationData().isEmpty()) return true;
            }
        }
        return false;
    }

    /**
     * Které důvody revokační rodiny mají zdroj i v řetězu některého razítka — tedy mimo podpisový
     * řetěz, jehož se týká důvod 3 (neznámý certifikát).
     *
     * <p>Odpověď potřebuje {@link #detectReasons}, a sama situace ji dát nemůže: situace 5 až 9,
     * 46 a 47 měří podpisový řetěz i řetězy razítek dohromady ({@link #signingAndTimestampChains})
     * a v čísle situace se ten rozdíl ztratí. Přitom rozhoduje o tom, zda je rada k něčemu:
     * u neznámé kotvy podpisového certifikátu vede do zdi, u ukotveného řetězu razítka k cíli.
     *
     * <p>Měří se týmiž predikáty jako situace samy, jen se jim podstrčí užší výběr řetězů — dvě
     * znění téhož testu by se časem rozešla a situace by byla indikována nad prázdným výčtem,
     * nebo naopak.
     */
    private java.util.Set<Integer> revocationReasonsOnTimestampChains(
            SignatureWrapper sw, DiagnosticData dd, SubIndication si, AppSettings settings,
            List<SkippedRevocation> skippedRevocations) {
        java.util.Set<Integer> out = new java.util.LinkedHashSet<>();
        ChainScope ts = ChainScope.TIMESTAMPS_ONLY;
        java.util.Date controlTime = settings.getValidationDate();
        if (!settings.isAcceptNewerOcsp()
                && hasNewerRevocationAnywhere(sw, dd, controlTime, ts)) out.add(2);      // S6
        if (!settings.isAcceptExpiredRevocationSources()
                && expiredEmbeddedRevocation(sw, ts) != null) out.add(6);                // S5
        if (!unknownStatusCerts(sw, skippedRevocations, ts).isEmpty()) out.add(10);      // S7
        if (!unusableEmbeddedCerts(sw, si, ts).isEmpty()) out.add(11);                   // S8
        // Nedostupnost kvůli síti (TRY_LATER) není vlastností řetězu: na podpisový certifikát ji
        // svést nelze, a tak se u ní důvod 12 nepotlačuje.
        if (si == SubIndication.TRY_LATER || hasCertWithoutRevocation(sw, ts)) out.add(12);  // S9
        if (!settings.isAcceptExpiredRevocationSources()
                && hasOutOfRangeRevocationIssuer(sw, controlTime, ts)) out.add(24);      // S46
        if (!uninformativeExternalCerts(sw, ts).isEmpty()) out.add(25);                  // S47
        return out;
    }

    private static boolean hasIgnoredRevokedOcsp(SignatureWrapper sw, Instant bst,
                                                  List<SkippedRevocation> skippedRevocations) {
        for (CertificateWrapper cw : sw.getCertificateChain()) {
            if (cw.isTrusted()) break;
            if (cw.getNotAfter() == null) continue;
            // Check diagnostic-data revocations (embedded OCSP case).
            for (CertificateRevocationWrapper rev : cw.getCertificateRevocationData()) {
                if (rev.isInternalRevocationOrigin()) continue;
                if (rev.getStatus() != CertificateStatus.REVOKED) continue;
                if (rev.getThisUpdate() == null || !rev.getThisUpdate().after(cw.getNotAfter())) continue;
                // Revocation before BST — or BST unknown (conservative: warn anyway)
                if (bst != null && rev.getRevocationDate() != null
                        && !rev.getRevocationDate().toInstant().isBefore(bst)) continue;
                return true;
            }
            // Check OCSPs skipped by OcspFirstCrlFallbackRevocationDataLoadingStrategy
            // (custom CRL case: OCSP was fetched but DSS never saw it).
            for (SkippedRevocation sr : skippedRevocations) {
                if (!cw.getId().equals(sr.certId())) continue;
                if (sr.status() != CertificateStatus.REVOKED) continue;
                if (sr.thisUpdate() == null || !sr.thisUpdate().after(cw.getNotAfter())) continue;
                if (bst != null && sr.revocationDate() != null
                        && !sr.revocationDate().toInstant().isBefore(bst)) continue;
                return true;
            }
        }
        return false;
    }

    /**
     * True when some certificate in the signing chain carries a REVOKED entry that was issued
     * after that certificate's own notAfter — the post-expiry revocation data that the option
     * „Využij i revokační informace, vydané po expiraci certifikátu" is there to let through.
     *
     * <p>Sourozenec {@link #hasIgnoredRevokedOcsp}, ale bez jeho dvou podmínek: vložený původ se
     * nepřeskakuje (post-expirační CRL v dokumentu propouští táž volba jako staženou odpověď)
     * a na rozhodný okamžik se neptá — tady jde o to, čím revokace je, ne kdy nastala vůči
     * důkazu existence. Do sběrače přeskočených odpovědí se nedívá schválně: ten plní
     * {@code OcspFirstCrlFallbackRevocationDataLoadingStrategy}, kterou ValidatorFactory
     * v tolerantním režimu vůbec nenasazuje, takže by byl vždy prázdný.
     */
    private static boolean hasPostExpiryRevokedEntry(SignatureWrapper sw) {
        for (CertificateWrapper cw : sw.getCertificateChain()) {
            if (cw.isTrusted()) break;
            if (cw.getNotAfter() == null) continue;
            for (CertificateRevocationWrapper rev : cw.getCertificateRevocationData()) {
                if (rev.getStatus() != CertificateStatus.REVOKED) continue;
                if (rev.getThisUpdate() == null) continue;
                if (rev.getThisUpdate().after(cw.getNotAfter())) return true;
            }
        }
        return false;
    }

    /** True when an online revocation entry for the signing cert chain shows REVOKED,
     *  was issued after cert.notAfter (standard mode rejects it), and the revocation
     *  date is AFTER bst — meaning the cert was still valid at bst but later revoked. */
    private static boolean hasLaterRevokedOcsp(SignatureWrapper sw, Instant bst) {
        for (CertificateWrapper cw : sw.getCertificateChain()) {
            if (cw.isTrusted()) break;
            if (cw.getNotAfter() == null) continue;
            for (CertificateRevocationWrapper rev : cw.getCertificateRevocationData()) {
                if (rev.isInternalRevocationOrigin()) continue;
                if (rev.getStatus() != CertificateStatus.REVOKED) continue;
                if (rev.getThisUpdate() == null || !rev.getThisUpdate().after(cw.getNotAfter())) continue;
                if (rev.getRevocationDate() == null) continue;
                if (rev.getRevocationDate().toInstant().isAfter(bst)) return true;
            }
        }
        return false;
    }

    /**
     * S24 message for the no-custom-time case: the signing certificate is revoked, but the
     * revocation came after the time-stamped signing time, so the signature stays valid.
     *
     * @param ownTimestamp zda ten okamžik dokládá <b>vlastní</b> časové razítko prvku. Kde ho
     *                     dokládá jiný prvek, který tenhle kryje (situace 54), by věta „doložen
     *                     časovým razítkem" tvrdila o podpisu bez razítka, že razítko má; odkud
     *                     ten okamžik je, řekne poznámka k situaci 54.
     */
    private String buildRevokedAfterSigningDetail(SignatureWrapper sw, Instant bst,
                                                  String provenBy) {
        CertificateWrapper signingCert = sw.getSigningCertificate();
        if (signingCert == null) return null;
        for (CertificateRevocationWrapper rev : signingCert.getCertificateRevocationData()) {
            if (rev.getStatus() != CertificateStatus.REVOKED) continue;
            java.util.Date revDate = rev.getRevocationDate();
            if (revDate == null || !revDate.toInstant().isAfter(bst)) continue;
            return "Certifikát podepsané osoby byl revokován (předčasně zneplatněn) dne "
                    + formatRevDate(revDate) + ", tedy až po čase podpisu ("
                    + TimeFormat.full(bst) + ")"
                    + (provenBy != null ? ", který je doložen " + provenBy + "." : ".")
                    + " Na platnost tohoto podpisu to proto nemá vliv — pozdější revokace se"
                    + " vztahuje jen na to, co bylo podepsáno až po ní.";
        }
        return null;
    }

    /** Returns a Czech message describing the later revocation (for S24 display). */
    private String buildLaterRevokedDetail(SignatureWrapper sw, Instant customTime) {
        String customTimeStr = TimeFormat.full(customTime);
        for (CertificateWrapper cw : sw.getCertificateChain()) {
            if (cw.isTrusted()) break;
            if (cw.getNotAfter() == null) continue;
            for (CertificateRevocationWrapper rev : cw.getCertificateRevocationData()) {
                if (rev.isInternalRevocationOrigin()) continue;
                if (rev.getStatus() != CertificateStatus.REVOKED) continue;
                if (rev.getThisUpdate() == null || !rev.getThisUpdate().after(cw.getNotAfter())) continue;
                if (rev.getRevocationDate() == null) continue;
                if (rev.getRevocationDate().toInstant().isAfter(customTime)) {
                    String revDateStr = formatRevDate(rev.getRevocationDate());
                    return "Ověření proběhlo k zadanému datu " + customTimeStr
                            + ". K tomuto datu byl certifikát platný. Aplikace má však k dispozici"
                            + " informaci (byť vydanou po expiraci certifikátu), která dokládá,"
                            + " že certifikát byl revokován dne " + revDateStr + ".";
                }
            }
        }
        return null;
    }

    /** True for digest matcher types that cover the directly-signed content payload. */
    private static boolean isSignedContentType(DigestMatcherType t) {
        return t == DigestMatcherType.MESSAGE_DIGEST
                || t == DigestMatcherType.CONTENT_DIGEST
                || t == DigestMatcherType.SIG_D_ENTRY
                || t == DigestMatcherType.REFERENCE
                || t == DigestMatcherType.OBJECT;
    }

    /** True when MD5 or RIPEMD-160 appears in the signature digest, certificate chain, or any timestamp. */
    private boolean usesCriticallyDeprecated(SignatureWrapper sw, DiagnosticData dd) {
        if (isCriticallyDeprecated(sw.getDigestAlgorithm())) return true;
        // Same blind spot as in usesSha1: the references carry their own digest algorithm.
        if (digestMatchersUse(sw, DigestAlgorithm.MD5)
                || digestMatchersUse(sw, DigestAlgorithm.RIPEMD160)) return true;
        for (CertificateWrapper cw : sw.getCertificateChain()) {
            if (cw.getSignatureAlgorithm() != null
                    && isCriticallyDeprecated(cw.getSignatureAlgorithm().getDigestAlgorithm())) return true;
        }
        for (TimestampWrapper ts : sw.getTimestampList()) {
            if (timestampUsesCriticallyDeprecated(ts)) return true;
        }
        for (TimestampWrapper ts : dd.getTimestampList()) {
            TimestampType tsType = ts.getType();
            if (!tsType.isDocumentTimestamp() && !tsType.isContainerTimestamp()) continue;
            if (ts.getTimestampedSignatures().stream().anyMatch(s -> sw.getId().equals(s.getId()))
                    && timestampUsesCriticallyDeprecated(ts)) return true;
        }
        return false;
    }

    private static boolean isCriticallyDeprecated(DigestAlgorithm alg) {
        return alg == DigestAlgorithm.MD5 || alg == DigestAlgorithm.RIPEMD160;
    }

    /**
     * Hašovací funkce, které výchozí politika DSS 6.5 připouští (AcceptableDigestAlgo). Co v seznamu
     * není, odmítne bez ohledu na datum — viz situace 56.
     */
    private static final java.util.Set<DigestAlgorithm> ACCEPTED_DIGESTS = java.util.EnumSet.of(
            DigestAlgorithm.MD5, DigestAlgorithm.SHA1, DigestAlgorithm.SHA224,
            DigestAlgorithm.SHA256, DigestAlgorithm.SHA384, DigestAlgorithm.SHA512,
            DigestAlgorithm.SHA3_256, DigestAlgorithm.SHA3_384, DigestAlgorithm.SHA3_512,
            DigestAlgorithm.RIPEMD160, DigestAlgorithm.WHIRLPOOL);

    static boolean isNotAccepted(DigestAlgorithm alg) {
        return alg != null && !ACCEPTED_DIGESTS.contains(alg);
    }

    /**
     * Stojí podpis někde na otisku, který {@code test} označí? Prochází táž místa jako
     * {@link #usesCriticallyDeprecated}, jen certifikáty řetězu bere po kotvu důvěry: podpis
     * kotvy samotné DSS kryptograficky neposuzuje a staré kořeny bývají podepsané třeba MD2.
     */
    private static boolean usesDigest(SignatureWrapper sw, DiagnosticData dd,
                                      java.util.function.Predicate<DigestAlgorithm> test) {
        if (test.test(sw.getDigestAlgorithm())) return true;
        if (sw.getDigestMatchers().stream().anyMatch(dm -> test.test(dm.getDigestMethod()))) {
            return true;
        }
        if (chainUses(sw.getCertificateChain(), test)) return true;
        for (TimestampWrapper ts : sw.getTimestampList()) {
            if (timestampUses(ts, test)) return true;
        }
        for (TimestampWrapper ts : dd.getTimestampList()) {
            TimestampType tsType = ts.getType();
            if (!tsType.isDocumentTimestamp() && !tsType.isContainerTimestamp()) continue;
            if (ts.getTimestampedSignatures().stream().anyMatch(s -> sw.getId().equals(s.getId()))
                    && timestampUses(ts, test)) return true;
        }
        return false;
    }

    private static boolean chainUses(List<CertificateWrapper> chain,
                                     java.util.function.Predicate<DigestAlgorithm> test) {
        for (CertificateWrapper cw : chain) {
            if (cw.isTrusted()) break;
            if (cw.getSignatureAlgorithm() != null
                    && test.test(cw.getSignatureAlgorithm().getDigestAlgorithm())) return true;
        }
        return false;
    }

    private static boolean timestampUses(TimestampWrapper ts,
                                         java.util.function.Predicate<DigestAlgorithm> test) {
        eu.europa.esig.dss.diagnostic.jaxb.XmlDigestMatcher imprint = ts.getMessageImprint();
        if (imprint != null && test.test(imprint.getDigestMethod())) return true;
        if (test.test(ts.getDigestAlgorithm())) return true;
        return chainUses(ts.getCertificateChain(), test);
    }

    private static boolean timestampUsesCriticallyDeprecated(TimestampWrapper ts) {
        eu.europa.esig.dss.diagnostic.jaxb.XmlDigestMatcher imprint = ts.getMessageImprint();
        if (imprint != null && isCriticallyDeprecated(imprint.getDigestMethod())) return true;
        for (CertificateWrapper cw : ts.getCertificateChain()) {
            if (cw.getSignatureAlgorithm() != null
                    && isCriticallyDeprecated(cw.getSignatureAlgorithm().getDigestAlgorithm())) return true;
        }
        return false;
    }

    /** True when the signing certificate is a PostSignum "systémový certifikát" (Act 227/2000 Sb.).
     *  Identified by SERIALNUMBER=S followed by digits in the subject DN, which is the PostSignum
     *  convention for system certificates issued to organizations (not natural persons). */
    /**
     * Je certifikát systémový (zákon č. 227/2000 Sb.), a pokud ano, kvalifikovaný?
     *
     * <p>Rozhoduje upozornění v certifikační politice ({@link SystemCertificate}) — tím se
     * certifikát prohlašuje sám a zná ho PostSignum obou generací i I.CA. Do 27. 9. 2026 se
     * poznávaly jen politiky PostSignum 2.23.134.1.4.1.8.*, takže značky Informačního systému
     * datových schránek z roku 2009 (PostSignum Qualified CA, …1.3.121) a I.CA (SecuStamp,
     * Pozemkový fond ČR, …23624.1.1.40.*) vycházely jako „neurčený druh“ či podpis (nález
     * uživatele). Dosavadní znaky zůstávají jako záloha pro certifikát bez upozornění.
     *
     * @param der bajty certifikátu, nejsou-li v {@code cert} (diagnostická data z ověření
     *            samotného certifikátu je nenesou); jinak null
     */
    private static SystemCertificate.Kind systemCertificateKind(CertificateWrapper cert, byte[] der) {
        byte[] bytes = der != null ? der : cert != null ? cert.getBinaries() : null;
        SystemCertificate.Kind byNotice = SystemCertificate.of(bytes);
        if (byNotice != SystemCertificate.Kind.NONE) return byNotice;
        if (!isSystemovyCertifikat(cert)) return SystemCertificate.Kind.NONE;
        return hasQcStatements(bytes) ? SystemCertificate.Kind.QUALIFIED_SYSTEM
                                      : SystemCertificate.Kind.SYSTEM;
    }

    private static boolean isSystemovyCertifikat(CertificateWrapper cert) {
        if (cert == null) return false;
        // Primary: PostSignum system certificate policy OID arc 2.23.134.1.4.1.8.*
        // (confirmed OIDs: 2.23.134.1.4.1.8.200 and 2.23.134.1.4.1.8.310 from test PDFs)
        for (eu.europa.esig.dss.diagnostic.jaxb.XmlCertificatePolicy p : cert.getCertificatePolicies()) {
            String oid = p.getValue();
            if (oid != null && oid.startsWith("2.23.134.1.4.1.8.")) return true;
        }
        // Secondary: subject DN contains serialNumber=S+digits. DSS renders OID 2.5.4.5 as
        // "2.5.4.5=#<hex>" (RFC 2253 encoded), "SN=", or "SERIALNUMBER=" depending on the name style.
        String dn = cert.getCertificateDN();
        return dn != null && java.util.regex.Pattern.compile(
                "(?:SERIALNUMBER|SN)=S\\d+", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(dn).find();
    }

    /** True when the signing certificate contains the QcStatements extension
     *  (OID 1.3.6.1.5.5.7.1.3), indicating a qualified certificate under ETSI / Act 227/2000. */
    private static boolean hasQcStatements(byte[] der) {
        if (der == null) return false;
        try {
            java.security.cert.X509Certificate x509 =
                (java.security.cert.X509Certificate)
                    java.security.cert.CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(der));
            return x509.getExtensionValue("1.3.6.1.5.5.7.1.3") != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Údaje o certifikátu podpisu pro kartu; prázdné, když certifikát není. */
    record CertFacts(String kind, Boolean qscd, String issuerDN, Instant notBefore,
                     Instant notAfter, boolean revoked, Instant revokedAt, String serialNumber,
                     boolean selfSigned, boolean qcCompliant, List<String> qcTypeDescriptions) { }

    static CertFacts certFacts(CertificateWrapper sigCert, boolean sysZnacka,
                               boolean qualifiedFlag) {
        String certKind = null;
        Boolean certQscd = null;
        String certIssuerDN = null;
        java.time.Instant certNotBefore = null;
        java.time.Instant certNotAfter = null;
        boolean certRevoked = false;
        java.time.Instant certRevokedAt = null;
        String certSerialNumber = null;
        boolean certSelfSigned = false;
        boolean certQcCompliant = false;
        java.util.List<String> certQcTypeDescriptions = java.util.List.of();
        if (sigCert != null) {
            String qual = sigCert.isQcCompliance() ? "kvalifikovaný" : "nekvalifikovaný";
            String purpose = "";
            for (eu.europa.esig.dss.enumerations.QCType t : sigCert.getQcTypes()) {
                if (eu.europa.esig.dss.enumerations.QCTypeEnum.QCT_ESIGN.equals(t)) { purpose = " pro elektronický podpis"; break; }
                if (eu.europa.esig.dss.enumerations.QCTypeEnum.QCT_ESEAL.equals(t)) { purpose = " pro elektronickou pečeť"; break; }
                if (eu.europa.esig.dss.enumerations.QCTypeEnum.QCT_WEB.equals(t))   { purpose = " pro webové stránky (TLS)"; break; }
            }
            // Před eIDAS QcType neexistoval a směrnice 1999/93/ES znala kvalifikovaný
            // certifikát jen pro elektronický podpis — viz SignatureSummary.isCertIssuedUnderDirective.
            if (purpose.isEmpty() && sigCert.isQcCompliance() && sigCert.getQcTypes().isEmpty()
                    && sigCert.getNotBefore() != null
                    && sigCert.getNotBefore().toInstant().isBefore(SignatureSummary.EIDAS_APPLICATION)) {
                purpose = " pro elektronický podpis";
            }
            certKind = qual + " certifikát" + purpose;
            // Systémový certifikát podle zákona č. 227/2000 Sb. nenese QcCompliance — nebyl
            // kvalifikovaný ve smyslu směrnice ani eIDAS, ale podle českého zákona, a jeho
            // kvalifikovanost se proto čte stejně jako u právního typu výš (sysZnacka).
            // Do 27. 9. 2026 se tu hlásil jako „nekvalifikovaný certifikát“, ačkoli právní
            // typ o tomtéž prvku říkal „uznávaná elektronická značka“ (nález uživatele).
            if (sysZnacka) {
                certKind = (qualifiedFlag ? "kvalifikovaný systémový certifikát"
                                          : "systémový certifikát")
                        + " (dle zákona č. 227/2000 Sb.)";
            }
            certQscd = sigCert.isSupportedByQSCD();
            certIssuerDN = sigCert.getCertificateIssuerDN();
            certSerialNumber = sigCert.getSerialNumber();
            certSelfSigned = sigCert.isSelfSigned();
            certQcCompliant = sigCert.isQcCompliance();
            certQcTypeDescriptions = sigCert.getQcTypes().stream()
                    .map(t -> t.getDescription()).collect(java.util.stream.Collectors.toList());
            certNotBefore = sigCert.getNotBefore() != null ? sigCert.getNotBefore().toInstant() : null;
            certNotAfter  = sigCert.getNotAfter()  != null ? sigCert.getNotAfter().toInstant()  : null;
            for (CertificateRevocationWrapper rev : sigCert.getCertificateRevocationData()) {
                if (CertificateStatus.REVOKED.equals(rev.getStatus())) {
                    certRevoked = true;
                    certRevokedAt = rev.getRevocationDate() != null
                            ? rev.getRevocationDate().toInstant() : null;
                    break;
                }
            }
        }
        return new CertFacts(certKind, certQscd, certIssuerDN, certNotBefore, certNotAfter,
                certRevoked, certRevokedAt, certSerialNumber, certSelfSigned, certQcCompliant,
                certQcTypeDescriptions);
    }

    /**
     * Kvalifikace podpisu odvozená z kvalifikace certifikátu k vydání a z verdiktu — tak, jak by
     * ji spočítalo DSS, kdyby podpis zpracovalo. Pro podpis adbe.x509.rsa_sha1, u kterého DSS
     * ověřuje jen certifikát.
     */
    static SignatureQualification signatureQualificationOf(CertificateQualification cq,
                                                           String indication) {
        boolean passed = "TOTAL_PASSED".equals(indication);
        boolean failed = "TOTAL_FAILED".equals(indication);
        if (cq == null) return passed ? SignatureQualification.ADESIG
                : failed ? SignatureQualification.NOT_ADES : SignatureQualification.INDETERMINATE_ADESIG;
        return switch (cq) {
            case QCERT_FOR_ESIG_QSCD -> passed ? SignatureQualification.QESIG
                    : failed ? SignatureQualification.NOT_ADES_QC_QSCD
                    : SignatureQualification.INDETERMINATE_QESIG;
            case QCERT_FOR_ESIG -> passed ? SignatureQualification.ADESIG_QC
                    : failed ? SignatureQualification.NOT_ADES_QC
                    : SignatureQualification.INDETERMINATE_ADESIG_QC;
            case QCERT_FOR_ESEAL_QSCD -> passed ? SignatureQualification.QESEAL
                    : failed ? SignatureQualification.NOT_ADES_QC_QSCD
                    : SignatureQualification.INDETERMINATE_QESEAL;
            case QCERT_FOR_ESEAL -> passed ? SignatureQualification.ADESEAL_QC
                    : failed ? SignatureQualification.NOT_ADES_QC
                    : SignatureQualification.INDETERMINATE_ADESEAL_QC;
            case CERT_FOR_ESEAL -> passed ? SignatureQualification.ADESEAL
                    : failed ? SignatureQualification.NOT_ADES
                    : SignatureQualification.INDETERMINATE_ADESEAL;
            default -> passed ? SignatureQualification.ADESIG
                    : failed ? SignatureQualification.NOT_ADES
                    : SignatureQualification.INDETERMINATE_ADESIG;
        };
    }

    private boolean isQualifiedSig(SignatureQualification q) {
        return q == SignatureQualification.QESIG || q == SignatureQualification.QESEAL;
    }

    private String legalType(SignatureQualification q) {
        if (q == null) return "pouze zaručený elektronický podpis";
        return switch (q) {
            case QESIG ->
                "kvalifikovaný elektronický podpis" +
                " (uznávaný elektronický podpis dle zákona č. 297/2016 Sb.)";
            case ADESIG_QC ->
                "zaručený elektronický podpis, založený na kvalifikovaném certifikátu" +
                " (uznávaný elektronický podpis dle zákona č. 297/2016 Sb.)";
            case ADESIG ->
                "pouze zaručený elektronický podpis";
            case QESEAL ->
                "kvalifikovanou elektronickou pečeť" +
                " (uznávanou elektronickou pečeť dle zákona č. 297/2016 Sb.)";
            case ADESEAL_QC ->
                "zaručenou elektronickou pečeť, založenou na kvalifikovaném certifikátu" +
                " (uznávanou elektronickou pečeť dle zákona č. 297/2016 Sb.)";
            case ADESEAL ->
                "zaručenou elektronickou pečeť";
            case INDETERMINATE_QESIG ->
                "pravděpodobně kvalifikovaný elektronický podpis" +
                " (kvalifikaci se nepodařilo jednoznačně ověřit)";
            case INDETERMINATE_ADESIG_QC ->
                "pravděpodobně zaručený elektronický podpis, založený na kvalifikovaném certifikátu" +
                " (kvalifikaci se nepodařilo jednoznačně ověřit)";
            case INDETERMINATE_ADESIG ->
                "pravděpodobně zaručený elektronický podpis" +
                " (kvalifikaci se nepodařilo jednoznačně ověřit)";
            case INDETERMINATE_QESEAL ->
                "pravděpodobně kvalifikovanou elektronickou pečeť" +
                " (kvalifikaci se nepodařilo jednoznačně ověřit)";
            case INDETERMINATE_ADESEAL_QC ->
                "pravděpodobně zaručenou elektronickou pečeť, založenou na kvalifikovaném certifikátu" +
                " (kvalifikaci se nepodařilo jednoznačně ověřit)";
            case INDETERMINATE_ADESEAL ->
                "pravděpodobně zaručenou elektronickou pečeť" +
                " (kvalifikaci se nepodařilo jednoznačně ověřit)";
            case INDETERMINATE_UNKNOWN_QC_QSCD,
                 INDETERMINATE_UNKNOWN_QC,
                 INDETERMINATE_UNKNOWN ->
                "autentizační prvek neurčeného druhu" +
                " (kvalifikaci se nepodařilo jednoznačně ověřit)";
            case NOT_ADES_QC_QSCD, NOT_ADES_QC, NOT_ADES ->
                "autentizační prvek nesplňující požadavky pro zaručený ani kvalifikovaný podpis nebo pečeť";
            default ->
                "pouze zaručený elektronický podpis";
        };
    }

    private String legalTypeTs(TimestampQualification q) {
        if (q == null) return "elektronické časové razítko";
        return switch (q) {
            case QTSA -> "kvalifikované elektronické časové razítko";
            case NA   -> "elektronické časové razítko (TSA nenalezena v důvěryhodném seznamu EUTL)";
            default   -> "nekvalifikované elektronické časové razítko";
        };
    }

    /**
     * Derives user-facing reasons from the already-computed list of failure situations.
     * Each reason groups one or more situations under a single explanation.
     * Reason numbering is independent of situation numbering.
     *
     * Reason table (grows as new reasons are defined):
     *   1 — zastaralý formát                          : S17
     *   2 — OCSP vydané po expiraci, validátor jim nevěří : S6
     *   3 — neznámý certifikát                           : S12
     *   4 — expirovaný podpisový certifikát, bez TS      : S3
     *   5 — expirovaný certifikát posledního TS (ověř. dle cert.) : S4
     *   6 — vložená revokační data nejde použít, exp. responder   : S5
     *   7 — porušená integrita podpisu                           : S1
     *   8 — nesprávná dvojice soubor+podpis                      : S14
     *   9 — revokovaný certifikát                                : S2
     *  10 — neznámý stav revokace                               : S7
     *  11 — revokační informace nelze využít                     : S8
     *  12 — revokační informace nedostupné                       : S9
     *  13 — podpis před začátkem platnosti certifikátu           : S10
     *  14 — revokovaný certifikát CA                             : S11
     *  15 — SHA1                                                 : S15
     *  16 — MD5 nebo RIPEMD-160                                  : S16
     *  17 — neznámý SubFilter (hardcoded for orphans)            : S19
     *  18 — chybí atribut signing-time (od 3. 10. 2026 se nevydává; S22 je poznámka)
     *  19 — nedůvěryhodné časové razítko (TSA není na EUTL)     : S23
     *  20 — podpis po expiraci certifikátu                           : S25
     *  21 — žádné TS neslouží jako PoE                              : S27
     *  22 — revokace prokázána, ale daty nepoužitelnými dle standardů : S30
     *  23 — poškozené časové razítko, nenapravitelné                 : S36
     *  24 — vydavatel revokačních dat mimo dobu své platnosti        : S46
     *  25 — stažený revokační údaj o certifikátu mlčí                : S47
     *  26 — revokovaný certifikát, chybí důkaz času podpisu          : S48
     *  27 — razítko ve slovníku VRI, které nezakládá PoE              : S52
     *  34 — deklarovaný čas mimo platnost certifikátu (od 4. 10. 2026 se nevydává; S55 je poznámka)
     *  35 — hašovací funkce, kterou standardy nepřipouštějí (SHA3-224) : S56
     */
    // Suppression table: if key reason is present, all listed reasons are suppressed.
    //
    // Důvod 3 (neznámý certifikát, tedy že řetěz nekončí u kotvy důvěry) tu od 29. 8. 2026
    // nepotlačují ani důvod 2 (revokační údaj vydaný po expiraci), ani důvod 12 (revokační údaj
    // se nepodařilo získat). Obojí stálo na úvaze, že jde o jednu věc viděnou dvakrát: existuje-li
    // revokační údaj, nebo se po něm aspoň marně sáhlo, je prý řetěz znám a trojka by mátla.
    //
    // Neplatí to. Nesestavitelný řetěz a potíž s revokačními údaji jsou dvě samostatná zjištění
    // a nastávají spolu — třeba u komerčních podpisů, kde chybí kotva i použitelný revokační údaj
    // naráz. Potlačením se čtenář nedozvěděl to jediné, co s tím sám může udělat: připnout
    // certifikát vydávající autority do vlastního úložiště. Aplikace tu radu na jiném místě
    // dává a od 4619c3c k ní rovnou nabízí i certifikáty tuzemských komerčních autorit.
    private static final java.util.Map<Integer, java.util.List<Integer>> REASON_SUPPRESSIONS =
        java.util.Map.ofEntries(
            // Reason 7 (porušená integrita) suppresses everything else — highest priority.
            java.util.Map.entry(7,  java.util.List.of(1,2,3,4,5,6,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,25,26,34,35)),
            // Důvod 28 (podpisová hodnota se neověřila) má touž přednost jako 7 a ze stejného
            // titulu: dokud se neověří samotný podpis, jsou úvahy o revokaci, formátu či
            // algoritmu bezpředmětné. Vznikl rozdělením situace 1, takže i seznam je týž.
            java.util.Map.entry(28, java.util.List.of(1,2,3,4,5,6,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,25,26,34,35)),
            // Reason 23 (poškozené časové razítko) suppresses reason 21 (razítka neslouží jako
            // PoE): both describe the same timestamp, but 23 names the actual defect and says
            // truthfully that nothing can repair it, whereas 21 would advise adding a TSA
            // certificate — useless here and misleading.
            java.util.Map.entry(23, java.util.List.of(21)),
            // Kde uchování platnost teprve vytvoří, je to ta podstatná zpráva; důvody 6 a 29 by
            // vedle ní nabízely ústupek k téže vložené informaci. Ústupek zmiňuje důvod 30 sám,
            // až za uchováním.
            java.util.Map.entry(30, java.util.List.of(6, 29)),
            // Reason 10 (OCSP stav UNKNOWN) has priority over reason 2 (OCSP po expiraci):
            // the revocation status is genuinely unknown — timing is a secondary detail.
            //
            // A nad důvodem 25 z téhož titulu: obojí je táž slepá ulička — o stavu certifikátu
            // se nic nedozvíme — jenže desítka jmenuje odpověď, kterou respondér skutečně dal,
            // kdežto 25 mluví o seznamu, ve kterém certifikát nefiguruje. Rada je u obou stejná,
            // tak ať stojí jednou. Změřeno na „uznavany 12 12 2021 eIdentity signed.pdf“, kde
            // v přísném režimu nastanou obě situace naráz.
            java.util.Map.entry(10, java.util.List.of(2, 25)),
            // Reason 16 (kriticky zastaralý algoritmus MD5/RIPEMD-160) suppresses all other
            // reasons except reason 7 — a critically broken algorithm is the primary finding
            // regardless of any other structural or validation problems.
            // Reason 16 is itself excluded from reason 18's list so that 16 always wins over 18.
            java.util.Map.entry(16, java.util.List.of(1,2,3,4,5,6,8,9,10,11,12,13,14,15,17,18,19,20,21,22,25,26,34)),
            // Důvod 35 (otisk, který standardy nepřipouštějí, např. SHA3-224) umlčuje revokační
            // rady z téhož titulu jako důvody 4, 20 a 21: podpis zastaví otisk, ať se s revokací
            // udělá cokoli, a rada povolit ústupek by čtenáře poslala k zaškrtávátku, po kterém
            // výsledek zůstane neznámý. Rozhodnutí uživatele 4. 10. 2026 nad PAdES T se SHA3-224,
            // kde vedle důvodu 35 stál důvod 2. Fakta o revokaci (9, 22, 26) zůstávají.
            // Výčet je REVOCATION_ADVICE_REASONS, opsaný: ta konstanta stojí níž a při statické
            // inicializaci by tu ještě byla null.
            java.util.Map.entry(35, java.util.List.of(2, 6, 10, 11, 12, 24, 25)),
            // Reason 18 (chybí signing-time) suppresses most other reasons — the missing attribute
            // is the sole cause of SIG_CONSTRAINTS_FAILURE; all other observations are secondary.
            // Reason 7 (porušená integrita) is excluded: it already suppresses 18 itself, so
            // whichever rule fires first the net result is always only reason 7.
            // Reason 16 (kriticky zastaralý algoritmus) is excluded: 16 dominates over 18.
            // Reason 20 (signed after cert expiry) stays: if we have a certified BST proving
            // the signing was after notAfter, that fact is independent of the missing attribute.
            java.util.Map.entry(18, java.util.List.of(1,2,3,4,5,6,8,9,10,11,12,13,14,15,17,19,21,25)),
            // Reason 22 (revokační info prokazující revokaci, nevyužitelné dle standardů) suppresses
            // reason 2 (OCSP po expiraci): R22 is the stronger and more specific finding — R2 would
            // duplicate the timing explanation already conveyed by R22.
            java.util.Map.entry(22, java.util.List.of(2, 25)),
            // Důvod 21 (žádné razítko neslouží jako PoE) má přednost před celou rodinou rad
            // o revokačních údajích. Bez důkazu existence padne rozhodný okamžik na „teď",
            // a certifikátu po expiraci to nespraví žádné nastavení revokačních dat — rada
            // zaškrtnout toleranci pošle uživatele tam, kde se nic nestane. Je to totéž, co platí
            // u důvodu 4.
            //
            // Důvod 22 v tom seznamu ale <b>není</b>, ačkoli u čtyřky je. Není to rada, nýbrž fakt:
            // že podpisový certifikát byl k rozhodnému okamžiku odvolán. Změřeno na sbírce —
            // 14 podpisů se sub-indikací REVOKED_NO_POE, u kterých by po umělčení zůstalo jen
            // „SHA-1 a žádné razítko neslouží jako PoE“ a o odvolání by se čtenář z výčtu důvodů
            // nedozvěděl nic. Důvod 26 (odvolání bez důkazu času) u nich indikován není.
            java.util.Map.entry(21, java.util.List.of(2, 6, 10, 11, 12, 24, 25)),
            // Důvod 24 (vydavatel revokace mimo dobu platnosti) má přednost před důvodem 2 z téhož
            // důvodu, jen o patro jinde: překážkou není stáří údaje, ale jeho vydavatel, a pouští
            // ho druhé zaškrtávátko. To navíc zapíná i toleranci k post-expiračním datům, takže
            // rada důvodu 24 pokrývá i to, co by radil důvod 2 — obráceně to neplatí.
            //
            // Obě pravidla vznikla z měření: na sbírce se důvod 2 ukázal 260×, z toho 99× marně,
            // a 29 z 30 prohlédnutých marných nálezů mělo vedle sebe právě 21 nebo 24.
            java.util.Map.entry(24, java.util.List.of(2)),
            // Důvody 4 a 20 mají přednost před důvodem 2 z téhož důvodu jako 21: chybí důkaz
            // existence. Důvod 4 (expirovaný podpisový certifikát bez časového razítka) i důvod 20
            // (podpis vznikl až po expiraci certifikátu) znamenají, že rozhodný okamžik leží mimo
            // dobu platnosti — a to je jediná věc, kterou applyNewerOcspRelaxation() schválně
            // nepovoluje, protože by z toho byla díra na antedatování. Zaškrtnutí tolerance tedy
            // s takovým podpisem nehne a rada by lhala.
            //
            // Důvod 22 (on-line revokační údaj prokazující revokaci, který standardy nepouštějí
            // ke slovu) tu <b>býval</b> a od 9. 9. 2026 není. Potlačoval se s odůvodněním, že slibuje
            // změnu výsledku na NEPLATNÝ, která tu nastat nemůže — jenže text důvodu 22 byl
            // mezitím přepsán a dnes říká pravý opak: že platnost tím spolehlivě posouzena nebude
            // a stojí to za zaškrtnutí jen tomu, kdo chce mít revokaci pojmenovanou přímo
            // ve zjištěných důvodech. Předpoklad toho potlačení tedy padl.
            //
            // A hlavně: <b>dvaadvacítka není rada, nýbrž fakt</b> — že podpisový certifikát byl
            // k rozhodnému okamžiku odvolán. Změřeno při pokusu přidat ji k rodině i u důvodů 20
            // a 21: 14 podpisů se sub-indikací REVOKED_NO_POE by ve výčtu mělo jen „SHA-1
            // a žádné razítko neslouží jako PoE“ a o odvolání by se čtenář nedozvěděl nic
            // (důvod 26 u nich indikován není). Fakt se neumlčuje, i když rada vedle něj nikam nevede.
            //
            // A z téhož titulu potlačuje důvod 4 celou rodinu revokačních nálezů: 6 (vložené údaje
            // od expirovaného respondéra), 10 (stav revokace neznámý), 11 (údaje nelze využít),
            // 12 (údaje se nepodařilo získat) a 24 (vydavatel údaje mimo dobu platnosti). Každý
            // z nich radí, jak revokační údaj pořídit nebo připustit — jenže bez důkazu existence
            // stojí rozhodný okamžik na přítomnosti, tedy mimo dobu platnosti certifikátu, a tam
            // se stavem revokace nezmění nic. Čtenář by šel zkoušet zaškrtávátka, po kterých
            // výsledek zůstane týž. Jediná pravdivá rada je ta od čtyřky: ověřit k zadanému datu.
            //
            // Změřeno na 251 podpisech (D:\tmp a „realne podpisy z praxe“): důvod 4 je indikován
            // u 48 z nich a vedle sebe má nejčastěji právě 6 (13×) a 12 (10×).
            //
            // Co se naopak nepotlačuje: 15 (SHA-1) a 3 (neznámý certifikát) — to nejsou rady
            // o revokaci, nýbrž druhá překážka, na kterou čtenář narazí hned, jakmile čtvrtku
            // vyřeší ověřením k zadanému datu; a 20 (podpis až po expiraci), což je silnější
            // a jiné zjištění o témž certifikátu.
            java.util.Map.entry(4,  java.util.List.of(2, 6, 10, 11, 12, 24, 25)),
            // Důvod 20 (podpis vznikl až po expiraci certifikátu) týž seznam, jen z druhé strany:
            // důkaz existence tu je, jenže ukazuje za konec platnosti. Rozhodný okamžik tedy
            // leží mimo dobu platnosti stejně jako u čtyřky a revokační údaje s tím nehnou.
            java.util.Map.entry(20, java.util.List.of(2, 6, 10, 11, 12, 24, 25)),
            // Důvod 25 (stažený seznam o certifikátu mlčí) jde s důvodem 2 ruku v ruce, kdekoli
            // je překážkou chybějící důkaz existence (4, 20, 21) nebo prokázaná revokace (22):
            // sehnat starší seznam k ničemu nevede, když rozhodný okamžik stojí mimo platnost
            // certifikátu nebo když už je jasno, že byl odvolán.
            //
            // Důvod 24 v tom výčtu schválně není, ačkoli důvod 2 potlačuje. U dvojky to platilo
            // proto, že jeho zaškrtávátko obstará i to, co by radila ona; tady ne — tolerance
            // k expirovanému vydavateli žádný seznam, ve kterém certifikát nefiguruje, nespraví.
            // Jsou to dvě různé překážky na dvou různých certifikátech a čtenář musí odstranit
            // obě (změřeno na „PAdES T zaruceny.pdf“).
            // Důvod 1 (zastaralý, nereferenční formát) má přednost taktéž. Vzniká ze situace 17,
            // která je indikována jen tehdy, když je tolerance k formátu vypnutá — tedy když je
            // formát tím, co verdikt drží. Revokační tolerance na to nedosáhne.
            java.util.Map.entry(1,  java.util.List.of(2))
        );

    /**
     * Rodina rad o revokačních údajích: 2 (údaj vydaný po expiraci), 6 (vydavatel vloženého údaje
     * mezitím expiroval), 10 (stav neznámý), 11 (vložený údaj nepoužitelný), 12 (údaj se nepodařilo
     * získat), 24 (vydavatel mimo dobu platnosti) a 25 (stažený seznam o certifikátu mlčí).
     *
     * <p>Táž rodina, jakou umlčují důvody 4, 20 a 21, a z téhož titulu: jde o rady, a rada, po které
     * se nic nestane, posílá čtenáře zkoušet zaškrtávátka nadarmo. Fakta o revokaci (9, 22, 26) v ní
     * nejsou a nikdy být nesmějí — ta se neumlčují, i když vedle nich žádná rada nikam nevede.
     */
    private static final List<Integer> REVOCATION_ADVICE_REASONS = List.of(2, 6, 10, 11, 12, 24, 25);

    /**
     * Package-private kvůli {@code ReasonSuppressionTest}: potlačovací tabulka je čistá funkce
     * a kombinací, ve kterých se důvody potkávají, je mnohem víc, než kolik jich sbírka zná.
     */
    /** Rozsah bajtů podpisu jako „a,b,c,d" — klíč, kterým se pozná týž podpis napříč jmény. */
    private static String byteRangeKey(java.util.List<java.math.BigInteger> range) {
        if (range == null || range.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < range.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(range.get(i));
        }
        return sb.toString();
    }

    /**
     * Bez zúžení na podpisový řetěz: potlačení revokačních rad u neznámé kotvy (důvod 3) se
     * neuplatní, protože se neví, čeho se nálezy týkají. Konzervativní z definice — radu je lépe
     * nechat stát než umlčet tu, která vede k cíli. Takhle to volají zkoušky a nástroje nad
     * čistou funkcí; ověřování má odpověď po ruce a volá tvar o třech parametrech.
     */
    /**
     * Co o dokumentu ví jen diagnostika a výčet situací to neunese.
     *
     * <p>Nosič, ne rostoucí řada parametrů: tohle je za tři dny druhý takový fakt a další
     * přibudou. Obě pole popisují totéž — pravidlo, které se rozhoduje podle certifikátů nebo
     * razítek za důvody, ne podle čísel důvodů, a do {@link #REASON_SUPPRESSIONS} se proto
     * nevejde.
     *
     * @param revocationOnTimestampChains důvody revokační rodiny, jejichž nález má zdroj i mimo
     *        podpisový řetěz — viz {@link #revocationReasonsOnTimestampChains}
     * @param naTimestampsAllNoChain      každé razítko mimo EUTL má i příčinu „řetěz nevede
     *        k žádné kotvě" — viz {@link #naTimestampsAllNoChain}
     * @param coveredWithinValidity       prvek bez vlastního razítka kryje pozdější prvek
     *        s platným razítkem z doby, kdy certifikát prvku ještě platil — viz
     *        {@link #coveringProof}. Důvod 4 pak nesmí umlčet revokační rady: jeho premisa
     *        („rozhodný okamžik stojí na přítomnosti") tu neplatí.
     * @param ownPoeWithinValidity        prvek má <b>vlastní</b> platné razítko, které může nést
     *        důkaz existence, z doby, kdy podpisový certifikát ještě platil — viz
     *        {@link #ownPoeWithinValidity}. Razítko, které vedle něj neobstálo, pak výsledek
     *        nedrží: důvody 21 a 23 se nevydají a revokační rady se neumlčí.
     */
    record ReasonFacts(java.util.Set<Integer> revocationOnTimestampChains,
                       boolean naTimestampsAllNoChain, boolean coveredWithinValidity,
                       boolean ownPoeWithinValidity) {

        ReasonFacts(java.util.Set<Integer> revocationOnTimestampChains,
                    boolean naTimestampsAllNoChain) {
            this(revocationOnTimestampChains, naTimestampsAllNoChain, false, false);
        }

        ReasonFacts(java.util.Set<Integer> revocationOnTimestampChains,
                    boolean naTimestampsAllNoChain, boolean coveredWithinValidity) {
            this(revocationOnTimestampChains, naTimestampsAllNoChain, coveredWithinValidity, false);
        }

        /** Nic se neví, tedy nic se nepotlačuje. Takhle to volají zkoušky a nástroje. */
        static final ReasonFacts UNKNOWN =
                new ReasonFacts(java.util.Set.copyOf(REVOCATION_ADVICE_REASONS), false);
    }

    static List<Integer> detectReasons(List<Integer> situations, AppSettings settings) {
        return detectReasons(situations, settings, ReasonFacts.UNKNOWN);
    }

    static List<Integer> detectReasons(List<Integer> situations, AppSettings settings,
                                       ReasonFacts facts) {
        java.util.Set<Integer> revocationOnTimestampChains =
                facts.revocationOnTimestampChains();
        List<Integer> reasons = new ArrayList<>();
        if (situations.contains(17)) reasons.add(1);   // zastaralý formát
        if (situations.contains(6)) reasons.add(2);  // OCSP vydané po expiraci
        if (situations.contains(12)) reasons.add(3);   // neznámý certifikát
        if (situations.contains(3))  reasons.add(4);   // expirovaný cert, bez TS
        if (situations.contains(4) && !settings.isTimestampStandardsMode()) reasons.add(5);
        // Rozděleno 11. 9. 2026: situace 5 (vydavatel mezitím expiroval) a situace 29 (neplatil
        // už při vydání) jsou dva různé nálezy a jedním textem se popsat nedají.
        if (situations.contains(5))  reasons.add(6);   // vydavateli mezitím skončila platnost
        if (situations.contains(29)) reasons.add(29);  // vydavatel neplatil už při vydání
        // Situace 35 dosud nevydávala žádný důvod; teď říká to, co čtenáři pomůže nejvíc.
        if (situations.contains(35)) reasons.add(30);
        if (situations.contains(1) && !situations.contains(14)) reasons.add(7);  // porušená integrita
        if (situations.contains(14)) reasons.add(8);   // nesouhlasí hash (ext. podpis)
        // R9 suppressed when S21 also fires: the REVOKED_NO_POE itself came from a post-expiry
        // OCSP that standards mode rejects — claiming "provably revoked before BST" (R9) would
        // contradict the R2 warning that the revocation data is untrusted.  R2 covers the warning,
        // with an added note about the revocation date in the detail text (built via S21 flag).
        if (situations.contains(2) && !situations.contains(21)) reasons.add(9);
        if (situations.contains(7))  reasons.add(10);  // OCSP neznámý stav
        if (situations.contains(8))  reasons.add(11);  // revokační data nepoužitelná
        if (situations.contains(9))  reasons.add(12);  // nepodařilo se získat revokační info
        if (situations.contains(10) || situations.contains(26)) reasons.add(13);  // podpis před platností certifikátu
        if (situations.contains(11)) reasons.add(14);  // revokovaný certifikát CA
        if (situations.contains(15)) reasons.add(15);  // SHA1
        if (situations.contains(16)) reasons.add(16);  // MD5 nebo RIPEMD160
        if (situations.contains(56)) reasons.add(35);  // otisk mimo standardy (SHA3-224)
        if (situations.contains(19)) reasons.add(17);  // neznámý SubFilter
        // Důvod 18 (chybí signing-time) se od 3. 10. 2026 nevydává — situace 22 je poznámka.
        // Důvod 34 (deklarovaný čas mimo platnost certifikátu) se od 4. 10. 2026 nevydává —
        // situace 55 je poznámka.
        if (situations.contains(23)) reasons.add(19);  // nedůvěryhodné razítko (TSA není na EUTL)
        if (situations.contains(25) && !situations.contains(27)) reasons.add(20);  // podpis po expiraci certifikátu (jen pokud je BST certifikovaný)
        // Důvody 21 a 23 (razítko neobstálo, resp. je poškozené) jen tehdy, když prvek nemá jiné
        // platné razítko z doby platnosti certifikátu. Má-li ho, neobstálé razítko výsledek
        // nedrží a oba důvody by čtenáře poslaly za falešnou příčinou — hůř, svou potlačovací
        // řadou by umlčely tu skutečnou. Nález uživatele 13. 10. 2026 nad kontejnerem ASiC-E
        // s podpisem CAdES-T: razítko obsahu s nesouhlasným otiskem, vedle něj platné podpisové
        // razítko vzniklé dva dny před koncem platnosti certifikátu a pozdější razítko
        // kontejneru. Karta hlásila jen důvod 23 („nedá se nic dělat"), kdežto ověření skončilo
        // na chybějících revokačních informacích k podpisovému certifikátu — situace 9, z níž
        // je důvod 12 s radou vložit CRL z vhodné doby; tu důvod 21 umlčoval. Poškozené razítko
        // pak zmíní poznámka k výsledku (AdvisorView.addIgnoredTimestampRow).
        boolean timestampsDecide = !facts.ownPoeWithinValidity();
        if (situations.contains(27) && timestampsDecide) reasons.add(21);  // žádné TS neslouží jako PoE
        if (situations.contains(30)) reasons.add(22);  // on-line revokační info prokazující revokaci, nelze použít dle standardů
        if (situations.contains(36) && timestampsDecide) reasons.add(23);  // poškozené časové razítko (nenapravitelné)
        if (situations.contains(46)) reasons.add(24);  // vydavatel revokace mimo dobu platnosti
        if (situations.contains(47)) reasons.add(25);  // stažený revokační údaj o certifikátu mlčí
        if (situations.contains(48)) reasons.add(26);  // revokovaný certifikát, chybí důkaz času podpisu
        if (situations.contains(52)) reasons.add(27);  // razítko ve VRI, které nezakládá PoE
        if (situations.contains(53)) reasons.add(28);  // podpisová hodnota se neověřila, obsah nezměněn
        // Zavedeno 14. 9. 2026 podle docs/duvody-z-protokolu-navrh.md. Důvod 31 (samostatné
        // razítko) se nepřidává tady — ten prvek touhle cestou neprochází, přiděluje se mu
        // rovnou při mapování razítek.
        if (situations.contains(37) || situations.contains(38)
                || situations.contains(39) || situations.contains(40)) {
            reasons.add(32);  // evidenčnímu záznamu něco schází
        }
        if (situations.contains(13)) reasons.add(33);  // chybí podepsaný soubor
        // Potlačení se rozhoduje podle odvozené sady, ne podle seznamu, ze kterého se zároveň
        // maže. Do 27. 8. 2026 tu byla smyčka, která mazala rovnou do `reasons` a ptala se jí
        // samé — takže kdykoli jeden potlačovatel odstranil klíč jiného, rozhodovalo pořadí
        // iterace. To u `Map.ofEntries()` Java losuje při každém startu JVM (SALT v
        // java.util.ImmutableCollections), a týž dokument tak po druhém spuštění aplikace
        // ukazoval jiné důvody. Změřeno na 311 situačních sadách ze sbírek D:\tmp a D:\Downloads:
        // 5 z nich (7 podpisů) dávalo dva různé výsledky, například
        // „kolize\1-signed-cades-baseline-t.pkcs7.p7s" 8× [15, 19, 23, 25] a 4× [15, 19, 23].
        //
        // Umlčený důvod tedy umlčuje dál, a to schválně: pravidla v tabulce odůvodňuje fakt
        // o dokumentu, ne přednost textu. Důvod 21 bere důvodům 2 a 25 slovo proto, že bez důkazu
        // existence leží rozhodný okamžik v přítomnosti — a to platí i tam, kde jeho vlastní
        // místo na kartě zaujme přesnější důvod 23. Kaskádové čtení („kdo není indikován, neumlčuje")
        // by ty rady vrátilo ve chvíli, kdy jsou pořád stejně nepravdivé, a poslalo čtenáře
        // shánět starší CRL nebo zaškrtávat toleranci tam, kde to prokazatelně nepomůže.
        //
        // Ruční obchvat u důvodu 16 (není v seznamu důvodu 18, aby 16 vyhrál) tím ztrácí smysl,
        // ale zůstává neškodný: seznam důvodu 18 je podmnožinou seznamu důvodu 16, takže na
        // výsledku se nepodílí.
        List<Integer> derived = List.copyOf(reasons);
        java.util.Set<Integer> silenced = new java.util.HashSet<>();
        for (var entry : REASON_SUPPRESSIONS.entrySet()) {
            // Důvod 4 umlčuje revokační rady proto, že bez důkazu existence stojí rozhodný
            // okamžik v přítomnosti, za koncem platnosti certifikátu. U prvku, který kryje
            // pozdější prvek s platným razítkem z doby platnosti, to neplatí: důkaz existence
            // je, a překážkou je pak právě revokační údaj. Nález uživatele 2. 10. 2026: dva
            // podpisy v úrovni B kryté podpisem s razítkem; bez ústupků jim karta ukázala jen
            // důvod 4, ačkoli ústupek k post-expiračním údajům (důvod 2) je překlopí na platné.
            if (entry.getKey() == 4 && facts.coveredWithinValidity()) continue;
            if (derived.contains(entry.getKey())) silenced.addAll(entry.getValue());
        }
        // Neznámá kotva (důvod 3) bere slovo revokačním radám — ale jen těm, které se týkají
        // podpisového řetězu. V tabulce to stát nemůže, protože tam se rozhoduje podle čísel
        // důvodů a tohle je otázka na certifikáty za nimi.
        //
        // Proč vůbec: podle ETSI EN 319 102-1 čl. 5.2.6 se revokace zjišťuje uvnitř ověření
        // certifikačního řetězu, až po sestavení cesty ke kotvě. Bez kotvy blok skončí na
        // NO_CERTIFICATE_CHAIN_FOUND a na revokaci vůbec nedojde — a musí to tak být, protože
        // odpověď OCSP i seznam CRL podepisuje týž neznámý vydavatel. Rada „sežeňte CRL“ nebo
        // „zapněte toleranci“ tedy s výsledkem nehne, dokud trojka stojí. Je to týž titul, z jakého
        // umlčují touž rodinu důvody 4, 20 a 21: rozhodný okamžik, respektive kotva, leží mimo
        // dosah kteréhokoli zaškrtávátka.
        //
        // Situace 12 se tu bere jako fakt o řetězu („nevede k žádné kotvě“), ne jako výrok o tom,
        // co rozhodlo o verdiktu — na to nestačí, jak upozorňuje AdvisorView.chainNotBuilt(). Pro
        // tohle pravidlo ale stačí právě ten fakt: k revokaci se na takovém řetězu nedošlo, ať už
        // ověření skončilo na čemkoli.
        //
        // Co se nepotlačuje:
        //   * nález na řetězu razítka — ten může být ukotvený, i když podpisový není, a tam rada
        //     vede k cíli. To je ta část, kterou číslo situace neunese a měří ji
        //     revocationReasonsOnTimestampChains();
        //   * důvod 21 (žádné razítko neslouží jako PoE) — jiný prvek a jiný lék: trojka radí
        //     vložit vydavatele podpisového certifikátu, 21 vydavatele razítka, a ve znění
        //     21/NO_CHAIN ho i jmenuje. Obě překážky musí čtenář odstranit, každou zvlášť;
        //   * fakta o revokaci (9, 22, 26) — ta se neumlčují nikdy;
        //   * důvod 15 (SHA-1) a další nálezy mimo revokaci — druhá překážka, na kterou čtenář
        //     narazí hned, jakmile vydavatele vloží do úložiště.
        //
        // Důvod 29 (vydavatel neplatil už při vydání) v rodině není schválně: vzniká jen ze situace
        // 29, a ta se hlásí pouze u TOTAL_PASSED, kdežto trojka jen u opaku. Potkat se nemohou.
        if (derived.contains(3)) {
            for (int r : REVOCATION_ADVICE_REASONS) {
                if (!revocationOnTimestampChains.contains(r)) silenced.add(r);
            }
        }
        // Neznámá razítkovací autorita: důvod 19 (TSA není v EUTL) a důvod 21 s příčinou
        // „řetěz nevede k žádné kotvě" jsou v tomhle případě táž zpráva, a jedenadvacítka ji nese
        // líp — jmenuje autoritu, připouští i odebraný status („nebo tam ke kontrolovanému
        // okamžiku nebyla") a její rada dodává, co po vložení certifikátu ještě zbývá (u razítek
        // na SHA-1 to rozhoduje o tom, jestli se vůbec něco změní).
        //
        // Nesmí to být plošné: ty dva důvody se ptají na různé věci a hned se rozejdou. Vloží-li
        // čtenář certifikát té autority do vlastního úložiště, razítko se ověří a důvod 21 zmizí —
        // kdežto 19 platí dál, protože v EUTL ta autorita pořád není, a je to jediné místo, kde se
        // to čtenář dozví. Proto ta podmínka o razítkách, ne o číslech důvodů.
        //
        // A na rozdíl od tabulky se ptá na důvod, který na kartě <b>zůstane</b>: kdyby
        // jedenadvacítku umlčel důvod 23 (poškozené razítko), mluvil by ten text o jiném razítku
        // a devatenáctka by byla zase jediným nositelem zprávy o EUTL.
        if (facts.naTimestampsAllNoChain() && derived.contains(21) && !silenced.contains(21)) {
            silenced.add(19);
        }
        reasons.removeAll(silenced);
        // Reason 1 (zastaralý formát / S17) used to be dropped whenever any other reason was
        // present, on the grounds that the format is then a contributing factor rather than the
        // primary finding. For PDF-NOT-ETSI that is backwards. S17 only fires while the
        // PDF-NOT-ETSI relaxation is off — that is, only while the format really is what blocks
        // the verdict, because the missing signed attributes are exactly what makes DSS return
        // SIG_CONSTRAINTS_FAILURE. Dropping it left SHA-1 standing as the apparent cause on
        // AnEP\PDF not ETSI\T kvalifikovany nereferencni.pdf, a document that turns TOTAL_PASSED
        // the moment the format is accepted and without touching SHA-1 at all. The reader then
        // hunts for a revocation or crypto fault instead of flipping the one switch that helps —
        // and misses the "Zkus jednorázové tolerantní ověření" button, which appears precisely
        // because reason 1's remedy mentions the tolerant mode.
        //
        // The cases where the format genuinely is secondary are already in the suppression table
        // above: reason 7 (broken integrity), 16 (critically obsolete algorithm) and 18 (missing
        // signing-time) each list reason 1 among what they suppress.
        reasons.sort(Comparator.naturalOrder());
        return List.copyOf(reasons);
    }

    /**
     * Identifikátor klienta MPSV z certifikátu — fasáda pro {@code DetailedReportView}, který má
     * po ruce {@code CertificateWrapper}.
     *
     * <p>Pravidlo samo bydlí od 16. 9. 2026 v {@link IkMpsv}: ptá se na ně i prohlížeč
     * certifikátů v {@link ContainerInspector} a dvě znění téhož by se rozešla.
     */
    public static String extractSanIkMpsv(byte[] certDer) {
        return IkMpsv.of(certDer);
    }

    /**
     * Deklarovaný čas podpisu (signing-time, u PAdES i /M) leží mimo dobu platnosti podpisového
     * certifikátu — to, co v DSS hlídá SigningTimeInCertRange. Situace 55.
     */
    static boolean claimedTimeOutsideCertificate(SignatureWrapper sw) {
        if (sw == null || sw.getClaimedSigningTime() == null) return false;
        CertificateWrapper cert = sw.getSigningCertificate();
        if (cert == null || cert.getNotBefore() == null || cert.getNotAfter() == null) return false;
        java.util.Date claimed = sw.getClaimedSigningTime();
        return claimed.before(cert.getNotBefore()) || claimed.after(cert.getNotAfter());
    }
}
