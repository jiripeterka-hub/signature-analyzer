package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.EvidenceRecordEntry;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Stavitel {@link SignatureSummary} pro testy záložky Digitální kontinuita.
 *
 * <p>Konstruktor souhrnu má přes padesát parametrů a testy by se v nich ztratily; tenhle stavitel
 * drží nuly na jednom místě, aby v testu zůstalo vidět jen to, čím se případy od sebe liší. Nová
 * pole modelu sem propadnou jako {@code null} a tomu, co se měří, to nevadí — jinak by je stavitel
 * musel znát všechna.
 *
 * <p>Vytažen ze třídy {@code ContinuityHorizonTest}, jakmile ho začala potřebovat i
 * {@code ContinuityObstacleTest}. Opsat ho podruhé by znamenalo dvě místa, která se musí držet
 * téhož konstruktoru.
 */
final class SigFixture {

    static final Instant NOW = Instant.now();
    static final Instant FUTURE = NOW.plus(900, ChronoUnit.DAYS);
    static final Instant PAST = NOW.minus(900, ChronoUnit.DAYS);
    static final Instant LATER = NOW.plus(1800, ChronoUnit.DAYS);
    /**
     * Kdy vzniklo časové razítko — dřív, než skončila platnost podpisového certifikátu.
     *
     * <p>Přípravky dávaly čas razítka rovný {@link #PAST}, tedy témuž okamžiku, ve kterém
     * certifikát expiroval. V realitě takový dokument nevznikne a v přípravku to maskovalo
     * pravidlo, že první razítko musí vzniknout <b>ještě za platnosti</b> podpisového
     * certifikátu (situace 31 i {@code brokenChainStopsTolerant}).
     */
    static final Instant STAMPED = PAST.minus(60, ChronoUnit.DAYS);

    private SigFixture() { }

    static Builder sig() { return new Builder(); }

    /**
     * Souhrn s vyplněnými jen těmi poli, na která se {@link ContinuityHorizon} ptá.
     *
     * <p>Konstruktor {@code SignatureSummary} má přes padesát parametrů a testy by se v nich
     * ztratily; tenhle stavitel drží nuly na jednom místě, aby v testu zůstalo vidět jen to, čím
     * se případy od sebe liší. Nová pole modelu sem propadnou jako {@code null} a tomu, co se
     * měří, to nevadí — jinak by je stavitel musel znát všechna.
     */
    static final class Builder {
        private String format = "PAdES-BASELINE-B";
        private boolean qualified;
        private String legalType;
        private Instant verifiableUntil;
        private Instant certNotAfter;
        private String certIssuerDN;
        private java.util.Map<Integer, String> reasonCertificates = java.util.Map.of();
        private Instant tsQualifiedFrom;
        private boolean anchorCert;
        private Instant newerRevocationCertNotAfter;
        private Instant expiredResponderNotAfter;
        private boolean signingTimeUnreadable;
        private boolean certSelfSigned;
        private SignatureSummary.TimestampFailure standaloneFailure;
        private List<TimestampEntry> timestamps = List.of();
        private boolean customAnchor;
        private List<String> decisiveSettings = List.of();
        /** Výchozí je „změřeno“ — tak běží aplikace; „neměřeno“ si test musí říct sám. */
        private boolean decisiveSettingsMeasured = true;
        private SignatureSummary.AugmentationDeadline deadline;
        private SignatureSummary.AugmentationDeadline unproven;
        private cz.digitalnikontinuita.anep.model.ValidationCourse course;
        private List<Integer> situations = List.of();
        private List<Integer> reasons = List.of();
        private List<EvidenceRecordEntry> records = List.of();
        private String indication = "TOTAL_PASSED";
        private String subIndication;
        private Instant bestSignatureTime;
        private String coveringProofDetail;
        private String counterSignatureOfId;
        private String signatureId = "SIG-1";
        private List<SignatureSummary.DssRemark> dssRemarks = List.of();

        Builder format(String v) { this.format = v; return this; }
        Builder qualified() { this.qualified = true; return this; }
        /** Právní typ prvku — podle něj se pozná pečeť, a tedy i jak se jmenuje její certifikát. */
        Builder legalType(String v) { this.legalType = v; return this; }
        /** Prvek je pečeť. */
        Builder seal() { this.legalType = "kvalifikovaná elektronická pečeť"; return this; }
        Builder verifiableUntil(Instant v) { this.verifiableUntil = v; return this; }
        Builder certNotAfter(Instant v) { this.certNotAfter = v; return this; }
        Builder timestamps(List<TimestampEntry> v) { this.timestamps = v; return this; }
        Builder standaloneFailure(SignatureSummary.TimestampFailure v) {
            this.standaloneFailure = v;
            return this;
        }
        Builder dssRemarks(List<SignatureSummary.DssRemark> v) {
            this.dssRemarks = v;
            return this;
        }
        Builder customAnchor() { this.customAnchor = true; return this; }
        Builder decisiveSettings(List<String> v) { this.decisiveSettings = v; return this; }
        Builder decisiveSettingsMeasured(boolean v) {
            this.decisiveSettingsMeasured = v;
            return this;
        }
        Builder situations(Integer... v) { this.situations = List.of(v); return this; }
        /** Zjištěné důvody — texty u důvodu 21 se ptají, zda je vedle něj indikován i důvod 15. */
        Builder reasons(Integer... v) { this.reasons = List.of(v); return this; }
        Builder indication(String v) { this.indication = v; return this; }
        /** DN vydavatele certifikátu, jak je píše DSS — věta u důvodu 3 z něj bere jméno (CN). */
        Builder certIssuerDN(String v) { this.certIssuerDN = v; return this; }
        /** Jak text pojmenuje certifikát u důvodů 24 a 25 — klíčem je číslo důvodu. */
        Builder reasonCertificates(java.util.Map<Integer, String> v) {
            this.reasonCertificates = v;
            return this;
        }
        /** Odkdy má razítkovací služba kvalifikovaný status — jen u prvku, který je razítkem. */
        Builder tsQualifiedFrom(Instant v) { this.tsQualifiedFrom = v; return this; }
        /** Konec platnosti certifikátu, o kterém mluví důvod 2. */
        Builder newerRevocationCertNotAfter(Instant v) {
            this.newerRevocationCertNotAfter = v;
            return this;
        }
        /** Konec platnosti vydavatele vloženého revokačního údaje — důvod 6. */
        Builder expiredResponderNotAfter(Instant v) {
            this.expiredResponderNotAfter = v;
            return this;
        }
        /** Certifikát razítkovací autority je sám kotvou důvěry. */
        Builder anchorCert() { this.anchorCert = true; return this; }
        Builder signingTimeUnreadable() { this.signingTimeUnreadable = true; return this; }
        /** Certifikát s vlastním podpisem — u důvodu 3 platí vlastní znění. */
        Builder certSelfSigned() { this.certSelfSigned = true; return this; }
        Builder subIndication(String v) { this.subIndication = v; return this; }
        Builder bestSignatureTime(Instant v) { this.bestSignatureTime = v; return this; }

        /** Věta o prvku, který tenhle kryje a doložil jeho dřívější existenci — situace 54. */
        Builder coveringProof(String v) { this.coveringProofDetail = v; return this; }

        /** Prvek je protipodpisem nad prvkem s identifikátorem {@code v}. */
        Builder counterSignatureOf(String v) { this.counterSignatureOfId = v; return this; }

        /** Vlastní identifikátor prvku — potřebný tam, kde se prvky na sebe odkazují. */
        Builder signatureId(String v) { this.signatureId = v; return this; }

        /**
         * Evidenční záznam nad podpisem. Obsah je lhostejný — {@code continuityObstacle()} se
         * ptá jen na to, jestli tam nějaký je; co z něj plyne, nese situace 41.
         */
        Builder evidenceRecord() {
            this.records = List.of(new EvidenceRecordEntry(
                    "ER-1", "evidence.ers", "ERS", "vložen", "PASSED", null,
                    true, List.of(), List.of(), List.of(), 1, 1, 0, PAST));
            return this;
        }

        Builder deadline(Instant when) {
            this.deadline = new SignatureSummary.AugmentationDeadline(when, "lhůtu určuje …");
            return this;
        }

        /** Vložený revokační údaj bez důkazu existence: platnost jeho vydavatele končí {@code when}. */
        Builder unprovenRevocation(Instant when) {
            this.unproven = new SignatureSummary.AugmentationDeadline(when, "lhůtu určuje …");
            return this;
        }

        /** Průběh ověřování jen s tím, na co se horizont ptá: odkud přišel rozhodující údaj. */
        Builder revocationFrom(String origin) {
            this.course = new cz.digitalnikontinuita.anep.model.ValidationCourse(
                    PAST, PAST, null, null, null, null,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    false, "store".equals(origin), true, false, null,
                    null, null, null,
                    new cz.digitalnikontinuita.anep.model.ValidationCourse.RevocationFact(
                            false, origin, PAST, false, false, null, null),
                    null, null,
                    // Vložený údaj jen tam, kde přípravek říká „z dokumentu" — jinak ho dokument
                    // nenese a fakta o něm nesmí mluvit.
                    "document".equals(origin)
                            ? new cz.digitalnikontinuita.anep.model.ValidationCourse
                                    .RevocationFact(false, origin, PAST, false, false, null, null)
                            : null,
                    false);
            return this;
        }

        /**
         * Dokument nese odpověď OCSP, kterou ověření <b>nepoužilo</b> — případ prvku, který
         * neobstál.
         *
         * <p>Přesně tak vypadá „priklad6_17.pdf": vložená odpověď OCSP od vydavatele, jemuž
         * mezitím skončila platnost. Využitý údaj je proto {@code null} a fakta musí druh vzít
         * z vloženého; dokud to nedělala, tvrdila o takovém dokumentu „CRL seznam".
         */
        Builder embeddedOcspUnused() {
            this.course = new cz.digitalnikontinuita.anep.model.ValidationCourse(
                    PAST, PAST, null, null, null, null,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    true, false, false, true, null,
                    null, null, null,
                    null, null, null,
                    new cz.digitalnikontinuita.anep.model.ValidationCourse.RevocationFact(
                            true, "document", PAST, false, false, null, "expiredIssuerNoProof"),
                    true);
            return this;
        }

        /**
         * Vložený údaj běh odmítl a výběr propadl na odpověď staženou <b>až teď</b> — přesně
         * tak dopadl dokument s odpovědí OCSP v atributu ADBE.
         *
         * <p>Podstatné je, že <b>oba</b> údaje jsou v průběhu: {@code signingCertRevocation} nese
         * ten stažený (model ho vede jako využitý, protože na něj ukázal výběr) a
         * {@code signingCertRevocationUnusableFact} ten vložený. Fakta z toho dřív skládala větu
         * „pro zjištění stavu revokace k rozhodnému okamžiku byla využita odpověď vydaná dnes".
         */
        Builder embeddedRejectedOnlineUsed() {
            this.indication = "INDETERMINATE";
            var embedded = new cz.digitalnikontinuita.anep.model.ValidationCourse.RevocationFact(
                    true, "document", STAMPED, false, false, null, "expiredIssuerNoProof");
            this.course = new cz.digitalnikontinuita.anep.model.ValidationCourse(
                    NOW, NOW, null, null, null, null,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    true, false, true, true, null,
                    null, null, null,
                    new cz.digitalnikontinuita.anep.model.ValidationCourse.RevocationFact(
                            true, "online", NOW, false, false, null, null),
                    null, embedded, embedded, true);
            return this;
        }

        /**
         * Revokace se u podpisového certifikátu nezjišťovala, protože stojí v čele vlastního
         * řetězu — buď je kotvou důvěry, nebo je podepsaný sám sebou. {@code entry} je řádek,
         * jaký o něm píše Podrobný výčet; odstavec v Průběhu ověřování si větu skládá sám.
         */
        Builder revocationNotApplicable(String entry) {
            this.course = new cz.digitalnikontinuita.anep.model.ValidationCourse(
                    NOW, NOW, null, null, null, null,
                    List.of(), List.of(), List.of(), List.of(), List.of(entry), List.of(),
                    false, false, false, false, null,
                    null, null, null,
                    null, null, null, null, false);
            return this;
        }

        /** Text situace 51; {@code null} znamená, že údaj o odvolání není starší než podpis. */
        private String staleRevocation;

        Builder staleRevocation(String detail) {
            this.staleRevocation = detail;
            return this;
        }

        SignatureSummary build() {
            return new SignatureSummary(
                    signatureId, format, indication, subIndication, "Kdosi", PAST,
                    qualified, legalType, bestSignatureTime, verifiableUntil, !timestamps.isEmpty(), decisiveSettings,
                    false, decisiveSettingsMeasured, null, null, null, timestamps, customAnchor,
                    customAnchor ? "Vlastní kotva" : null, deadline, unproven, situations, reasons, null,
                    null, null, null, null, null, null,
                    null, null, null, null, null, null, false,
                    null, null, null, certIssuerDN, null, certNotAfter,
                    false, null, null, certSelfSigned, false, List.of(),
                    null, null, List.of(), false, null, List.of(),
                    null, null, course, records, null, null,
                    staleRevocation, null, coveringProofDetail, null, counterSignatureOfId,
                    standaloneFailure, dssRemarks, reasonCertificates,
                    tsQualifiedFrom, anchorCert, newerRevocationCertNotAfter,
                    expiredResponderNotAfter, signingTimeUnreadable);
        }
    }
}
