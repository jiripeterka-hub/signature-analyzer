package cz.digitalnikontinuita.anep.model;

import eu.europa.esig.dss.diagnostic.DiagnosticData;

import java.util.List;

/**
 * Top-level result of analyzing one input file: the signatures/timestamps
 * found inside it, plus the raw ETSI-conformant XML reports for anyone who
 * wants the full detail behind the summary.
 */
public class ValidationResult {

    private final String fileName;
    private final String detectedContainerType; // e.g. "PDF", "ASiC-E", "ASiC-S", "CMS", "XML"
    private final boolean externalSignature;    // true when the signature file was supplied separately from the signed content
    private final List<SignatureSummary> signatures;
    private final String simpleReportXml;
    private final String detailedReportXml;
    private final DiagnosticData diagnosticData;

    /**
     * Evidence records that cover no signature — either because the document holds only
     * preserved data, or because the record was handed in on its own. Records that do cover
     * a signature live on that signature's {@link SignatureSummary} instead, so nothing is
     * listed twice.
     * <p>
     * Without this the app would report "0 signatures" and show nothing at all for a file
     * that in fact carries a valid proof of existence.
     */
    private final List<EvidenceRecordEntry> standaloneEvidenceRecords;

    /**
     * Which ISDS artefact the input is — message, delivery receipt or bare envelope — or
     * {@code null} when it is not one. DSS sees only a CMS object and cannot tell a ZFO from an
     * ordinary P7M, so this is set by whoever opened the file. It decides whether the result is
     * worded as a container or as a data message, which is what the reader actually has in front
     * of them.
     */
    private cz.digitalnikontinuita.anep.core.ContainerInspector.IsdsArtefact isdsArtefact;

    /**
     * Signature fields the document carries prepared but unsigned. They are a finding about the
     * document, not about any authentication element — a document may consist of nothing else,
     * and then the signature list is empty while this one is not.
     * <p>
     * Set by whoever opened the PDF, like {@link #isdsArtefact}: DSS never reports an empty
     * field, because from its point of view there is no signature to validate.
     */
    private List<cz.digitalnikontinuita.anep.core.PdfEmptySignatureFieldDetector.EmptyField>
            emptySignatureFields = List.of();
    private int contentPdfSignatures;
    private int contentPdfTimestamps;

    /**
     * Verze PDF a podmnožiny PDF, ke kterým se dokument hlásí (PDF/A a spol.), nebo
     * {@code null}, nejde-li o PDF. Zjištění o souboru jako celku, ne o autentizačním prvku —
     * proto tady vedle {@link #emptySignatureFields} a ne na {@link SignatureSummary}. Platí
     * o tom PDF, které se ověřuje: u odděleného podpisu je to podepisovaný dokument.
     */
    private cz.digitalnikontinuita.anep.core.PdfProfileExtractor.Profile pdfProfile;

    /**
     * Zda dokument dostál prohlášení o PDF/A, které nese, nebo {@code null}, když se k žádnému
     * jednoznačnému profilu nehlásí a není tedy co prověřovat.
     *
     * <p>Nález o souboru, ne o autentizačním prvku, a na verdikt nemá vliv: nekonformní PDF/A
     * je pořád platně podepsaný dokument. Proto stojí tady vedle výsledku a do situací ani
     * důvodů se nedostane.
     */
    private cz.digitalnikontinuita.anep.core.PdfAConformanceChecker.Conformance pdfConformance;

    public ValidationResult(String fileName, String detectedContainerType,
                             boolean externalSignature,
                             List<SignatureSummary> signatures,
                             String simpleReportXml, String detailedReportXml,
                             DiagnosticData diagnosticData) {
        this(fileName, detectedContainerType, externalSignature, signatures,
                simpleReportXml, detailedReportXml, diagnosticData, List.of());
    }

    public ValidationResult(String fileName, String detectedContainerType,
                             boolean externalSignature,
                             List<SignatureSummary> signatures,
                             String simpleReportXml, String detailedReportXml,
                             DiagnosticData diagnosticData,
                             List<EvidenceRecordEntry> standaloneEvidenceRecords) {
        this.fileName = fileName;
        this.detectedContainerType = detectedContainerType;
        this.externalSignature = externalSignature;
        this.signatures = signatures;
        this.simpleReportXml = simpleReportXml;
        this.detailedReportXml = detailedReportXml;
        this.diagnosticData = diagnosticData;
        this.standaloneEvidenceRecords = standaloneEvidenceRecords != null
                ? List.copyOf(standaloneEvidenceRecords) : List.of();
    }

    /** Viz {@link #getRevisionTail()}. */
    private cz.digitalnikontinuita.anep.core.RevisionTail revisionTail;

    public String getFileName()                    { return fileName; }
    public String getDetectedContainerType()       { return detectedContainerType; }
    public boolean isExternalSignature()           { return externalSignature; }
    public List<SignatureSummary> getSignatures()  { return signatures; }
    public String getSimpleReportXml()             { return simpleReportXml; }
    public String getDetailedReportXml()           { return detailedReportXml; }
    public DiagnosticData getDiagnosticData()      { return diagnosticData; }
    public List<EvidenceRecordEntry> getStandaloneEvidenceRecords() { return standaloneEvidenceRecords; }

    public cz.digitalnikontinuita.anep.core.ContainerInspector.IsdsArtefact getIsdsArtefact() {
        return isdsArtefact;
    }
    public void setIsdsArtefact(cz.digitalnikontinuita.anep.core.ContainerInspector.IsdsArtefact isdsArtefact) {
        this.isdsArtefact = isdsArtefact;
    }

    public List<cz.digitalnikontinuita.anep.core.PdfEmptySignatureFieldDetector.EmptyField>
            getEmptySignatureFields() {
        return emptySignatureFields;
    }
    public void setEmptySignatureFields(
            List<cz.digitalnikontinuita.anep.core.PdfEmptySignatureFieldDetector.EmptyField> fields) {
        this.emptySignatureFields = fields == null ? List.of() : fields;
    }

    /**
     * Podpisy (či pečeti) a dokumentová časová razítka, které nese samo PDF předložené jako
     * podepsaný obsah k externímu podpisu. DSS je v tom režimu neověřuje — posuzuje jen externí
     * soubor —, a karta proto o nich jen říká, že tu jsou. Mimo externí podpis vždy nula.
     */
    public int getContentPdfSignatures()                 { return contentPdfSignatures; }
    public int getContentPdfTimestamps()                 { return contentPdfTimestamps; }
    public void setContentPdfSignatures(int signatures, int timestamps) {
        this.contentPdfSignatures = signatures;
        this.contentPdfTimestamps = timestamps;
    }

    public cz.digitalnikontinuita.anep.core.PdfProfileExtractor.Profile getPdfProfile() {
        return pdfProfile;
    }
    public void setPdfProfile(cz.digitalnikontinuita.anep.core.PdfProfileExtractor.Profile profile) {
        this.pdfProfile = profile;
    }

    /**
     * Co je v souboru za posledním prvkem, který ho kryje — nebo {@code null}, není-li za ním nic.
     *
     * <p>Nese to výsledek, ne {@code SignatureSummary}: je to nález o <b>dokumentu</b>, ne
     * o jednotlivém podpisu, a odpověď vzniká z celého souboru naráz. Táž úvaha jako u prázdných
     * podpisových polí a shody s PDF/A o kus níž.
     */
    public cz.digitalnikontinuita.anep.core.RevisionTail getRevisionTail() {
        return revisionTail;
    }

    public void setRevisionTail(cz.digitalnikontinuita.anep.core.RevisionTail revisionTail) {
        this.revisionTail = revisionTail;
    }

    /**
     * Je PDF prostým skenem? {@code null}, když se to nezjišťovalo nebo nepodařilo zjistit.
     * Nález o souboru, ne o prvku; čte ho jen blok o vkladové listině.
     */
    private cz.digitalnikontinuita.anep.core.PdfScanDetector.Kind pdfTextLayer;

    /** Ověřovalo se bez důvěryhodných seznamů EU (offline režim). */
    private boolean trustListsOffline;

    /**
     * Ústupky, které byly pro tohle ověření zapnuté — ne ty, které verdikt nesly (ty jsou
     * u podpisu, {@code SignatureSummary.getDecisiveSettings()}). Blok o vkladové listině podle
     * toho pozná, zda ověření s ústupky podatelny katastru už proběhlo. Politika se po ověření
     * vrací do výchozího stavu, takže výsledek si to musí nést sám.
     */
    private java.util.Set<cz.digitalnikontinuita.anep.core.ReportMapper.Relaxation> activeRelaxations =
            java.util.Set.of();

    public java.util.Set<cz.digitalnikontinuita.anep.core.ReportMapper.Relaxation> getActiveRelaxations() {
        return activeRelaxations;
    }

    public void setActiveRelaxations(
            java.util.Set<cz.digitalnikontinuita.anep.core.ReportMapper.Relaxation> relaxations) {
        this.activeRelaxations = relaxations == null ? java.util.Set.of() : java.util.Set.copyOf(relaxations);
    }

    public boolean isTrustListsOffline() { return trustListsOffline; }

    public void setTrustListsOffline(boolean offline) { this.trustListsOffline = offline; }

    public cz.digitalnikontinuita.anep.core.PdfScanDetector.Kind getPdfTextLayer() {
        return pdfTextLayer;
    }

    public void setPdfTextLayer(cz.digitalnikontinuita.anep.core.PdfScanDetector.Kind kind) {
        this.pdfTextLayer = kind;
    }

    public cz.digitalnikontinuita.anep.core.PdfAConformanceChecker.Conformance getPdfConformance() {
        return pdfConformance;
    }
    public void setPdfConformance(
            cz.digitalnikontinuita.anep.core.PdfAConformanceChecker.Conformance conformance) {
        this.pdfConformance = conformance;
    }
}
