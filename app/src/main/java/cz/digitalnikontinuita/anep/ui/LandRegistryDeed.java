package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.ReportMapper.Relaxation;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Splňuje dokument požadavky ČÚZK na elektronickou vkladovou listinu?
 *
 * <p>Požadavky jsou z letáku ČÚZK „Jak správně a jednoduše požádat o vklad do katastru
 * nemovitostí elektronicky?“ (2025) a ze stránky „Podání návrhu na vklad v elektronické podobě“:
 * <ul>
 *   <li>formát PDF nebo PDF/A;</li>
 *   <li>nejde o prostý sken, tedy jen obrázky stránek bez textové vrstvy
 *       ({@link cz.digitalnikontinuita.anep.core.PdfScanDetector}). Sken s rozpoznaným textem
 *       (OCR) i dokument bez textu, který skenem není (výkres s textem v křivkách), vyhovují
 *       — rozhodnutí uživatele z 29. 9. 2026;</li>
 *   <li>každý podpis je uznávaný nebo kvalifikovaný, tedy založený na kvalifikovaném certifikátu;</li>
 *   <li>podpisy jsou vloženy do dokumentu a jsou k nim připojeny certifikáty;</li>
 *   <li>pravost podpisu je prokázána: IK MPSV nebo číslo osobního dokladu v certifikátu,
 *       certifikát zapsaný v registru obyvatel, nebo úřední ověření podpisu;</li>
 *   <li>kvalifikované časové razítko připojené po posledním podpisu nebo spolu s ním.</li>
 * </ul>
 *
 * <p><b>Posuzují se jen podpisy.</b> Pečeť ani značka není podpis účastníka právního jednání;
 * na výsledek nemají vliv, blok je jen vyjmenuje (rozhodnutí uživatele z 28. 9. 2026).
 *
 * <p><b>Kdy se posuzuje vůbec.</b> U PDF s interními podpisy a bez externího podpisu, a jen
 * v expertním módu ({@code AdvisorView.show}). Do 8. 10. 2026 navíc jen tehdy, když byl aspoň
 * jeden podpis platný — nad podpisem neznámé platnosti blok neříkal nic nového (rozhodnutí
 * uživatele z 28. 9. 2026). Od té doby u něj radí ověření s ústupky podatelny katastru, takže
 * podmínka padla spolu s přesunem bloku do expertního módu (rozhodnutí uživatele 8. 10. 2026).
 *
 * <p><b>Jak ověřuje podatelna katastru</b> (informace podatelny, kterou uživatel získal 8. 10. 2026):
 * primárně podle standardů bez ústupků, také knihovnou DSS. Když podpis takto neobstojí, zkusí
 * ještě službu SecuSign Validation, jejíž politiku ověřování známe — ta připouští revokační
 * informace vydané po expiraci certifikátu i od expirovaného vydavatele a starší (nereferenční)
 * formáty, a má vlastní zásobu revokačních informací. Odtud tři pravidla:
 * <ul>
 *   <li><b>SHA-1 podatelna nepřijímá</b>, ani přes SecuSign. Podpis platný jen díky toleranci
 *       k SHA-1 i podpis neznámé platnosti kvůli SHA-1 (důvod 15) proto požadavky nesplňují.</li>
 *   <li>Podpis platný s ústupky z {@link #LAND_REGISTRY_CONCESSIONS} požadavek <b>splňuje</b> —
 *       podatelna k témuž výsledku dojde přes SecuSign. <b>Ledaže</b> stojí na časovém razítku,
 *       jehož certifikát už expiroval: SecuSign hodnotí razítka podle platnosti jejich
 *       certifikátů, takže by takový podpis neuznala ani jedna cesta podatelny. Tehdy výsledek
 *       zní „nelze posoudit“ (rozhodnutí uživatele 8. 10. 2026), viz {@link #timestampDoubt}.</li>
 *   <li><b>Nereferenční formáty</b> podatelna zatím přijímá, ale chystá se to omezit. Na výsledek
 *       to vliv nemá, blok na to jen upozorní.</li>
 * </ul>
 *
 * <p><b>Podpis neznámé platnosti dává výsledek „nelze posoudit“</b> (pokyn uživatele 8. 10. 2026;
 * do té doby od 29. 9. 2026 „nesplňuje“, s výjimkou neznámé platnosti zaviněné prostředím).
 * Podatelna má k dispozici víc, než AnEP vidí: ústupky a vlastní zásobu revokačních informací
 * služby SecuSign. Blok proto doporučí ověření s ústupky podatelny, a když ani to nepomůže,
 * upozorní na tu zásobu. Neznámá platnost zaviněná <b>prostředím</b> — nedostupné revokační
 * informace (důvod 12), nebo neznámý certifikát (důvod 3) jen proto, že se nenačetly
 * důvěryhodné seznamy EU — se dál rozlišuje, jen kvůli radě: tam má smysl ověření zopakovat.
 *
 * <p>Registr obyvatel ani úřední ověření AnEP vidět nemůže. Chybí-li podpisu IK MPSV i číslo
 * dokladu, zbývá tedy jen „nelze posoudit“ — nikdy „nesplňuje“.
 */
final class LandRegistryDeed {

    private LandRegistryDeed() {}

    enum Verdict { MEETS, FAILS, UNDECIDED }

    /**
     * Ústupky, které podatelna katastru připouští (přes službu SecuSign Validation): všechny
     * kromě tolerance k SHA-1.
     */
    static final Set<Relaxation> LAND_REGISTRY_CONCESSIONS = java.util.Collections.unmodifiableSet(
            java.util.EnumSet.of(Relaxation.NEWER_OCSP, Relaxation.EXPIRED_REVOCATION_SOURCES,
                    Relaxation.PDF_NOT_ETSI));

    /** Důvod 15 — zastaralá hashovací funkce SHA-1. */
    private static final int REASON_SHA1 = 15;

    /**
     * Důvody neznámé platnosti, které stojí na revokačních informacích — u nich může pomoci
     * vlastní zásoba revokačních informací služby SecuSign. U jiných (neznámý certifikát,
     * nepodporovaný algoritmus …) by taková rada nic neslibovala.
     */
    private static final Set<Integer> REVOCATION_REASONS = Set.of(2, 6, 10, 11, 12, 24, 25, 29, 30);

    /**
     * Jeden podpis.
     *
     * @param legalType zjednodušený druh podpisu („kvalifikovaný elektronický podpis“ …), nebo
     *                  {@code null}, nezná-li se
     * @param certEmbedded {@code null}, když se to z diagnostických dat nedá zjistit
     * @param identityDocument číslo osobního dokladu jako fráze do věty, nebo {@code null}
     * @param referenceFormat podpis je v některém z referenčních formátů (ETSI baseline)
     * @param concessions ústupky podatelny katastru, na kterých stojí kladný verdikt; prázdné,
     *                    obstál-li podpis podle standardů
     * @param sha1 podatelna podpis odmítne kvůli SHA-1 — obstál jen díky toleranci k ní, nebo
     *             je kvůli ní jeho platnost neznámá
     * @param revocation neznámá platnost stojí (i) na revokačních informacích
     * @param timestampDoubt platný jen s ústupky a zároveň na razítku s expirovaným
     *                       certifikátem — viz {@link LandRegistryDeed#timestampDoubt}
     */
    record Signature(String name, String indication, String legalType, boolean qualifiedCert,
                     Boolean certEmbedded, String ikMpsv, String identityDocument,
                     boolean environmental, boolean referenceFormat, Set<Relaxation> concessions,
                     boolean sha1, boolean revocation, boolean timestampDoubt) {

        /** Platný tak, jak ho podatelna přijme: podle standardů, nebo s jejími ústupky. */
        boolean valid()         { return passed() && !timestampDoubt; }
        boolean invalid()       { return "TOTAL_FAILED".equals(indication) || "FAILED".equals(indication); }
        boolean identified()    { return ikMpsv != null || identityDocument != null; }
        /** Odmítnutý kvůli SHA-1 — neplatný podpis se hlásí jako neplatný, ne kvůli SHA-1. */
        boolean sha1Rejected()  { return sha1 && !invalid(); }
        /** Platný s ústupky, ale přes SecuSign by neobstálo razítko. */
        boolean doubtful()      { return passed() && timestampDoubt; }
        boolean undetermined()  { return !passed() && !invalid() && !sha1; }

        private boolean passed() { return "TOTAL_PASSED".equals(indication) && !sha1; }
    }

    /**
     * @param lastSignature podpis, za kterým (nebo u něhož) má být razítko
     * @param timestamp platné kvalifikované razítko u posledního podpisu nebo za ním, nebo
     *                  {@code null}, není-li žádné
     * @param others pečeti a značky — jméno a druh, jen k upozornění
     */
    /**
     * @param concessionsTried ověření už proběhlo se všemi ústupky podatelny katastru — rada
     *                         ho zkusit by byla zbytečná
     */
    record Assessment(Verdict verdict, List<Signature> signatures, Signature lastSignature,
                      TimestampEntry timestamp, List<String> others,
                      cz.digitalnikontinuita.anep.core.PdfScanDetector.Kind textLayer,
                      boolean concessionsTried) {

        boolean plainScan() {
            return textLayer == cz.digitalnikontinuita.anep.core.PdfScanDetector.Kind.PLAIN_SCAN;
        }


        List<Signature> invalid()         { return signatures.stream().filter(Signature::invalid).toList(); }
        List<Signature> sha1Rejected()    { return signatures.stream().filter(Signature::sha1Rejected).toList(); }
        List<Signature> doubtful()        { return signatures.stream().filter(Signature::doubtful).toList(); }
        /** Platnost neznámá kvůli prostředí — ověření by stálo za zopakování. */
        List<Signature> undetermined()    { return signatures.stream().filter(s -> s.undetermined() && s.environmental()).toList(); }
        /** Platnost se nepodařilo potvrdit a prostředí za to nemůže. */
        List<Signature> unconfirmed()     { return signatures.stream().filter(s -> s.undetermined() && !s.environmental()).toList(); }
        /** Platné jen díky ústupkům, které podatelna katastru připouští. */
        List<Signature> withConcessions() { return signatures.stream().filter(s -> s.valid() && !s.concessions().isEmpty()).toList(); }
        List<Signature> nonReference()    { return signatures.stream().filter(s -> !s.referenceFormat()).toList(); }
        List<Signature> notQualified()    { return signatures.stream().filter(s -> !s.qualifiedCert()).toList(); }
        List<Signature> notEmbedded()     { return signatures.stream().filter(s -> Boolean.FALSE.equals(s.certEmbedded())).toList(); }
        List<Signature> embeddedUnknown() { return signatures.stream().filter(s -> s.certEmbedded() == null).toList(); }
        List<Signature> unidentified()    { return signatures.stream().filter(s -> !s.identified()).toList(); }
    }

    /** Razítka, která vznikla až po podpisu: podpisové, dokumentové či archivní a razítko ve VRI. */
    private static final Set<String> LATER_KINDS = Set.of("sig", "doc", "vri");

    /** Posouzení, nebo {@code null}, když se blok nemá ukázat vůbec. */
    static Assessment assess(ValidationResult result) {
        if (result == null || result.isExternalSignature()) return null;
        if (result.getPdfProfile() == null) return null;
        DiagnosticData dd = result.getDiagnosticData();
        if (dd != null && dd.getContainerInfo() != null) return null;

        List<SignatureSummary> sigs = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (SignatureSummary s : result.getSignatures()) {
            if ("TimeStampToken".equals(s.getFormat())) continue;
            if (s.isSeal() || s.isMark()) {
                others.add(displayName(s) + " (" + (s.isMark() ? "elektronická značka"
                        : "elektronická pečeť") + ")");
                continue;
            }
            sigs.add(s);
        }
        // Bez jediného podpisu (jen pečeti či značky) není co posuzovat.
        if (sigs.isEmpty()) return null;

        SignatureSummary last = lastSignature(sigs, dd);
        List<Signature> checks = new ArrayList<>();
        for (SignatureSummary s : sigs) {
            checks.add(check(s, dd, result.isTrustListsOffline(), s == last));
        }

        Signature lastCheck = checks.get(sigs.indexOf(last));
        TimestampEntry ts = last.getAllTimestamps().stream()
                .filter(t -> LATER_KINDS.contains(t.tsKind()) && t.passed() && t.qualified())
                .findFirst().orElse(null);

        // Neznámá platnost sama „nesplňuje“ nedává (viz javadoc třídy); SHA-1 ano.
        boolean fails = checks.stream().anyMatch(c -> c.invalid() || c.sha1Rejected()
                        || !c.qualifiedCert()
                        || Boolean.FALSE.equals(c.certEmbedded()))
                || ts == null
                || result.getPdfTextLayer() == cz.digitalnikontinuita.anep.core.PdfScanDetector.Kind.PLAIN_SCAN;
        boolean meets = !fails && result.getPdfTextLayer() != null
                && checks.stream().allMatch(c -> c.valid()
                && Boolean.TRUE.equals(c.certEmbedded()) && c.identified());
        Verdict verdict = fails ? Verdict.FAILS : meets ? Verdict.MEETS : Verdict.UNDECIDED;
        boolean tried = result.getActiveRelaxations().containsAll(LAND_REGISTRY_CONCESSIONS);
        return new Assessment(verdict, checks, lastCheck, ts, others, result.getPdfTextLayer(),
                tried);
    }

    /** Důvody neznámé platnosti, za které může prostředí, ne podpis — viz javadoc třídy. */
    static boolean environmental(List<Integer> reasons, boolean trustListsOffline) {
        if (reasons == null || reasons.isEmpty()) return false;
        return reasons.stream().allMatch(r -> r == 12 || r == 3 && trustListsOffline);
    }

    /**
     * Stojí kladný verdikt na toleranci k SHA-1? Jen tehdy, když bez ní nejde: zastupují-li se
     * rozhodující ústupky navzájem ({@code alternatives}) a je mezi nimi i jiný, nese verdikt ten.
     */
    static boolean needsSha1(Collection<Relaxation> decisive, boolean alternatives) {
        if (!decisive.contains(Relaxation.SHA1)) return false;
        return !alternatives || decisive.stream().allMatch(r -> r == Relaxation.SHA1);
    }

    /** Ústupky podatelny katastru, na kterých kladný verdikt stojí (bez SHA-1). */
    static Set<Relaxation> landRegistryConcessions(Collection<Relaxation> decisive, boolean alternatives) {
        Set<Relaxation> out = java.util.EnumSet.noneOf(Relaxation.class);
        for (Relaxation r : decisive) if (LAND_REGISTRY_CONCESSIONS.contains(r)) out.add(r);
        // Ze zastupitelných stačí jeden — vypíše se ten první, ne všechny jako by byly potřeba.
        if (alternatives && out.size() > 1) {
            Relaxation first = out.iterator().next();
            out.clear();
            out.add(first);
        }
        return out;
    }

    /** Odmítne podatelna podpis kvůli SHA-1? Viz javadoc třídy. */
    static boolean sha1Rejected(String indication, Collection<Relaxation> decisive,
                                boolean alternatives, List<Integer> reasons) {
        if ("TOTAL_PASSED".equals(indication)) return needsSha1(decisive, alternatives);
        return reasons != null && reasons.contains(REASON_SHA1);
    }

    /**
     * Neobstál by podpis platný jen s ústupky ani přes SecuSign, protože tam neobstojí jeho
     * razítko? SecuSign uzná razítko, jen dokud platí certifikát, na kterém je založeno (nebo je
     * ten certifikát kotvou důvěry). Razítko podpis potřebuje, když už expiroval podpisový
     * certifikát (důkaz existence), a u posledního podpisu vždy — o jeho razítko se opírá
     * požadavek na kvalifikované razítko.
     *
     * <p>Podpis platný podle standardů se neptá: tu cestu podatelna projde knihovnou DSS, která
     * razítka hodnotí podle standardů jako AnEP.
     */
    static boolean timestampDoubt(boolean withConcessions, boolean last, Instant certNotAfter,
                                  List<TimestampEntry> timestamps, Instant now) {
        if (!withConcessions) return false;
        boolean certExpired = certNotAfter != null && !certNotAfter.isAfter(now);
        if (!certExpired && !last) return false;
        return timestamps.stream().noneMatch(t -> t.passed() && LATER_KINDS.contains(t.tsKind())
                && (t.anchorCert() || t.notAfter() != null && t.notAfter().isAfter(now)));
    }

    private static Signature check(SignatureSummary s, DiagnosticData dd, boolean trustListsOffline,
                                   boolean last) {
        String raw = s.getLegalType();
        String legal = raw == null || raw.isBlank() ? null : ExtendedView.simplifyLegalType(raw);
        boolean qualified = "kvalifikovaný elektronický podpis".equals(legal)
                || "uznávaný elektronický podpis".equals(legal);
        String ik = s.getIkMpsv() == null || s.getIkMpsv().isBlank() ? null : s.getIkMpsv();
        // Doklad se bere jen z kvalifikovaného certifikátu — tatáž podmínka jako v bloku
        // o právních účincích, aby si ty dva bloky neodporovaly.
        String doc = s.isCertQcCompliant() ? DnUtil.identityDocument(s.getSignerCertDN()) : null;
        List<Relaxation> decisive = decisive(s);
        boolean alternatives = s.isDecisiveSettingsInterchangeable();
        List<Integer> reasons = s.getDetectedReasons();
        boolean passed = "TOTAL_PASSED".equals(s.getIndication());
        Set<Relaxation> concessions = passed ? landRegistryConcessions(decisive, alternatives) : Set.of();
        return new Signature(displayName(s), s.getIndication(), legal, qualified,
                certEmbedded(s, dd), ik, doc,
                environmental(reasons, trustListsOffline),
                AdvisorView.isReferenceFormat(s.getFormat()),
                concessions,
                sha1Rejected(s.getIndication(), decisive, alternatives, reasons),
                reasons != null && reasons.stream().anyMatch(REVOCATION_REASONS::contains),
                timestampDoubt(!concessions.isEmpty(), last, s.getCertNotAfter(),
                        s.getAllTimestamps(), Instant.now()));
    }

    /** Rozhodující ústupky podpisu; SignatureSummary je nese pod jménem z Nastavení. */
    private static List<Relaxation> decisive(SignatureSummary s) {
        List<Relaxation> out = new ArrayList<>();
        for (Relaxation r : Relaxation.values()) {
            if (s.getDecisiveSettings().contains(r.settingName())) out.add(r);
        }
        return out;
    }

    /**
     * Je podpisový certifikát v dokumentu? V podpisu (CMS) nebo ve slovníku DSS — obojí DSS
     * vede mezi certifikáty nalezenými u podpisu. Nenašlo-li DSS podpisový certifikát vůbec,
     * v dokumentu není: stáhnout ho odjinud neumí.
     */
    private static Boolean certEmbedded(SignatureSummary s, DiagnosticData dd) {
        if (dd == null || s.getSignatureId() == null) return null;
        SignatureWrapper sw = dd.getSignatureById(s.getSignatureId());
        if (sw == null) return null;
        CertificateWrapper signing = sw.getSigningCertificate();
        if (signing == null) return false;
        return sw.foundCertificates().getRelatedCertificates().stream()
                .anyMatch(c -> signing.getId().equals(c.getId()));
    }

    /**
     * Poslední podpis — ten, jehož revize sahá v souboru nejdál. Kde rozsah bajtů chybí,
     * rozhoduje čas podpisu.
     */
    private static SignatureSummary lastSignature(List<SignatureSummary> sigs, DiagnosticData dd) {
        return sigs.stream().max(Comparator
                .comparing((SignatureSummary s) -> revisionEnd(s, dd))
                .thenComparing(s -> s.getSigningTime() != null ? s.getSigningTime()
                        : java.time.Instant.MIN)).orElseThrow();
    }

    private static BigInteger revisionEnd(SignatureSummary s, DiagnosticData dd) {
        if (dd == null || s.getSignatureId() == null) return BigInteger.ZERO;
        SignatureWrapper sw = dd.getSignatureById(s.getSignatureId());
        if (sw == null || sw.getPDFRevision() == null) return BigInteger.ZERO;
        List<BigInteger> br = sw.getPDFRevision().getSignatureByteRange();
        if (br == null || br.size() < 4) return BigInteger.ZERO;
        return br.get(2).add(br.get(3));
    }

    private static String displayName(SignatureSummary s) {
        String n = s.getSignerDisplayName();
        return n == null || n.isBlank() ? "neznámá osoba" : n;
    }
}
