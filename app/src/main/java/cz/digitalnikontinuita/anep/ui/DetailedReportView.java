package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.CustomTrustStore;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.diagnostic.CertificateRevocationWrapper;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.EvidenceRecordWrapper;
import eu.europa.esig.dss.diagnostic.jaxb.XmlDigestMatcher;
import eu.europa.esig.dss.diagnostic.FoundCertificatesProxy;
import eu.europa.esig.dss.diagnostic.FoundRevocationsProxy;
import eu.europa.esig.dss.diagnostic.OrphanCertificateWrapper;
import eu.europa.esig.dss.diagnostic.PDFRevisionWrapper;
import eu.europa.esig.dss.diagnostic.RelatedCertificateWrapper;
import eu.europa.esig.dss.diagnostic.RelatedRevocationWrapper;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.diagnostic.TrustServiceWrapper;
import eu.europa.esig.dss.diagnostic.jaxb.XmlObjectModification;
import eu.europa.esig.dss.diagnostic.jaxb.XmlSignatureScope;
import eu.europa.esig.dss.enumerations.CertificateOrigin;
import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.enumerations.CertificationPermission;
import eu.europa.esig.dss.enumerations.KeyUsageBit;
import eu.europa.esig.dss.enumerations.RevocationOrigin;
import eu.europa.esig.dss.enumerations.RevocationType;
import eu.europa.esig.dss.enumerations.SubIndication;
import eu.europa.esig.dss.enumerations.TimestampType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Stage;

import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.cert.ocsp.UnknownStatus;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import java.math.BigInteger;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Comprehensive display of every detail DSS DiagnosticData exposes for a signed document. */
public class DetailedReportView {

    /**
     * Razítkovací služba a vybraný zdroj pro protokol; {@code null} znamená, že se na razítko
     * nebude ptát. Drží to {@code MainView}, tenhle pohled to jen předá dál.
     */
    private PrintUtil.ProtocolExtras protocolExtras;

    /**
     * Kdy ověřování proběhlo — do záhlaví protokolu, stejně jako u obou přehledů. Bez toho
     * z hotového protokolu nešlo poznat, ke kterému okamžiku jeho závěry platí.
     */
    private java.time.Instant verificationTime;

    void setVerificationTime(java.time.Instant t) { this.verificationTime = t; }

    public void setProtocolExtras(PrintUtil.ProtocolExtras extras) {
        this.protocolExtras = extras;
    }

    private static final String HDR_BG   = "#003189";
    private static final String HDR_FG   = "#ffffff";
    private static final String SEC_BG   = "#e8edf7";
    private static final String SEC_FG   = "#003189";
    private static final String CARD_BORDER = "-fx-border-color:#cccccc;-fx-border-width:1;-fx-border-radius:4;-fx-padding:0;";

    private final VBox cardsBox = new VBox(14);
    private final ScrollPane scroll = new ScrollPane(cardsBox);
    private final Button pdfBtn = new Button("Generovat protokol jako PDF");
    private final Button inspectorBtn = new Button("Inspektor");
    /** Výsledek ověření pro výklad v Inspektoru; staví se v {@link #show}, kde jsou mapy po ruce. */
    private cz.digitalnikontinuita.anep.core.StructureAnnotations.Context inspectorContext;
    private final VBox root;

    private File pdfSigFile;
    private File pdfOrigFile;

    void setFileInfo(File sig, File orig) {
        pdfSigFile = sig;
        pdfOrigFile = orig;
    }

    /** Maps revocation token ID → display name of the certificate it covers. */
    private java.util.Map<String, String> revCertNames = new java.util.HashMap<>();
    /** Maps revocation token ID → certificate ID it was issued for. */
    private java.util.Map<String, String> revToCertId = new java.util.HashMap<>();
    /**
     * Revokační token → sériové číslo certifikátu, na jehož záznamu se má otevřít prohlížeč CRL.
     * Jeden seznam může krýt víc certifikátů z řetězců; přednost má ten, který na něm revokovaný
     * je, protože kvůli němu se čtenář do seznamu dívá.
     */
    private java.util.Map<String, BigInteger> revToCertSerial = new java.util.HashMap<>();
    /** Maps revocation token ID → the certificate-status verdict DSS derived from it. */
    private java.util.Map<String, CertificateRevocationWrapper> revStatusById = new java.util.HashMap<>();
    /** Maps revocation token ID → reasons DSS gave for not accepting it (empty/absent = accepted). */
    private java.util.Map<String, List<String>> revRejectionReasons = new java.util.HashMap<>();
    /**
     * Poznámka, kterou k revokačnímu údaji připisuje sama AnEP — na rozdíl od
     * {@link #revRejectionReasons} tu nejde o odmítnutí ze strany DSS.
     *
     * <p>Nadpis je součástí záznamu, protože se liší podle toho, jestli o stavu certifikátu
     * rozhodl právě tenhle údaj, nebo jiný; jeden pevný nadpis u ikony tvrdil „DSS tyto revokační
     * informace použil" i tam, kde se závěr opřel o něco jiného.
     */
    private record RevocationNote(String title, String text) {}

    /** Maps OCSP token ID → the note above; absent when there is nothing to add. */
    private java.util.Map<String, RevocationNote> revExpiredResponderNotes = new java.util.HashMap<>();

    /** certId → id revokačního údaje, o který se opřel závěr o tom certifikátu (ReportMapper). */
    private java.util.Map<String, String> usedRevocationByCert = new java.util.HashMap<>();
    /** All non-internal (online-fetched) revocation tokens from this validation. */
    private java.util.Set<eu.europa.esig.dss.diagnostic.RevocationWrapper> allOnlineRevocations
            = new java.util.HashSet<>();
    /** IDs of online revocations already displayed in some section; prevents duplicates across
     *  signature / timestamp sections when the cert-chain association cannot be determined. */
    private java.util.Set<String> shownOnlineRevIds = new java.util.HashSet<>();

    /** Full diagnostic data for the currently-shown document; used by post-processing checks. */
    private DiagnosticData currentDd;
    /**
     * Označení prvku pro záhlaví karty podle id — „Pečeť č.: 2“, „Podpis“ —, číslované stejně
     * jako v Rozšířeném přehledu ({@code ExtendedView.show}).
     *
     * <p>Do 13. 10. 2026 nesla karta v záhlaví identifikátor DSS („Podpis · S-6A5A1DD893…“),
     * který čtenáři nic neřekne a v Rozšířeném přehledu se tentýž prvek jmenoval jinak
     * (připomínka uživatele). Identifikátor zůstává uvnitř karty jako řádek, ke srovnání
     * s Protokoly DSS.
     */
    private java.util.Map<String, String> elementHeaders = new java.util.HashMap<>();
    /** Prvky, které jsou elektronickou značkou — viz SignatureSummary.isMark(). */
    private java.util.Set<String> markIds = new java.util.HashSet<>();

    /**
     * Závěr, ke kterému DSS u jednotlivých tokenů došlo. Diagnostická data ho neobsahují — nesou
     * jen zjištěné skutečnosti (otisk sedí, podpis je neporušený, řetěz končí u kotvy), nikoli
     * výsledek ověřovacího procesu. Bez něj se „Celková platnost" opírala o ty skutečnosti samy
     * a hlásila „Ano" i u razítka, které DSS neuznal: sunset kotvy, zastaralý algoritmus ani
     * chybějící důkaz existence se mezi nimi neprojeví.
     */
    private eu.europa.esig.dss.simplereport.SimpleReport currentSimple;

    /**
     * Id certifikátů, které v tomhle dokumentu skutečně nesou nějaký řetěz jako kotva z vlastního
     * úložiště. Počítá se jednou na dokument, protože odpověď nezávisí na certifikátu samotném,
     * ale na řetězu, ve kterém stojí.
     *
     * <p>Být v úložišti nestačí. Kořen připnutý „pro jistotu" nad kotvou, kterou nese důvěryhodný
     * seznam, se ověření vůbec neúčastní — řetěz skončí dřív, u té kotvy ze seznamu. Když se ptalo
     * jen certifikátu samotného, dostal takový kořen odznak vlastního úložiště a tvrdil zásluhu,
     * kterou neměl.
     */
    private java.util.Set<String> customAnchorCertIds = java.util.Set.of();

    /**
     * When true (default), timestamp validity is taken directly from DSS (sunset behaviour).
     * When false, each timestamp's cert chain is additionally checked against current date —
     * any expired cert causes the timestamp to be shown as "Neznámá".
     */
    private boolean timestampStandardsMode = true;
    private boolean acceptExpiredRevocationSources = false;
    private boolean acceptNewerOcsp = false;
    /** Zda běh propustil i revokační údaj vydaný po expiraci certifikátu — viz
     *  {@link cz.digitalnikontinuita.anep.core.ReportMapper#postExpiryRevocationAccepted}. Vlastní
     *  příznak, ne {@code acceptNewerOcsp}: uděluje ho i zadané datum ověření. */
    private boolean postExpiryAccepted = false;

    public void setTimestampStandardsMode(boolean v) { this.timestampStandardsMode = v; }
    public void setAcceptExpiredRevocationSources(boolean v) { this.acceptExpiredRevocationSources = v; }
    public void setAcceptNewerOcsp(boolean v) { this.acceptNewerOcsp = v; }
    public void setPostExpiryAccepted(boolean v) { this.postExpiryAccepted = v; }
    public void setPdfPassword(char[] pwd) { this.currentPdfPassword = pwd; }

    /** Password for encrypted PDFs — used when parsing PDF structure at the end of show(). */
    private char[] currentPdfPassword;

    public DetailedReportView() {
        cardsBox.setPadding(new Insets(12));
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color:transparent;-fx-background:transparent;");

        pdfBtn.setDisable(true);
        pdfBtn.setOnAction(e -> PrintUtil.saveAsPdf(cardsBox,
                (Stage) pdfBtn.getScene().getWindow(),
                PrintUtil.protocolFileName("DA", pdfSigFile),
                pdfSigFile, pdfOrigFile, "Detailní rozbor", verificationTime,
                java.util.List.of(), protocolExtras));
        // Inspektor podpisových struktur (13. 10. 2026): vstup je tady, v Detailním
        // rozboru, protože ten se ukazuje jen v expertním režimu. Popisek se řídí formátem
        // souboru a u formátů, které Inspektor nerozkládá, tlačítko není.
        inspectorBtn.setVisible(false);
        inspectorBtn.managedProperty().bind(inspectorBtn.visibleProperty());
        inspectorBtn.setOnAction(e -> {
            if (pdfSigFile == null) return;
            File[] t = inspectorTarget();
            if (t == null) return;
            StructureInspectorWindow.open(inspectorBtn.getScene().getWindow(), t[0], t[1],
                    t[1] == null ? currentPdfPassword : null, inspectorContext);
        });
        HBox toolbar = new HBox(8, pdfBtn);
        toolbar.setPadding(new Insets(4, 8, 8, 8));
        // Inspektor stojí na začátku těla záložky, ne ve spodní liště (přání uživatele
        // 13. 10. 2026): je to vstup do prohlížení, ne akce nad hotovým rozborem. Mimo cardsBox,
        // aby se nedostal do protokolu PDF a při rolování zůstal na očích.
        HBox inspectorBar = new HBox(inspectorBtn);
        inspectorBar.setPadding(new Insets(8, 12, 0, 12));
        inspectorBar.managedProperty().bind(inspectorBtn.visibleProperty());
        inspectorBar.visibleProperty().bind(inspectorBtn.visibleProperty());

        VBox.setVgrow(scroll, Priority.ALWAYS);
        root = new VBox(inspectorBar, scroll, toolbar);
    }

    public VBox getRoot() { return root; }

    /** Označení prvků podle id ve stejném pořadí a se stejným číslováním jako Rozšířený přehled. */
    private static java.util.Map<String, String> elementHeaders(
            List<cz.digitalnikontinuita.anep.model.SignatureSummary> sigs) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        int signatures = 0, seals = 0, timestamps = 0;
        for (cz.digitalnikontinuita.anep.model.SignatureSummary s : sigs) {
            if ("TimeStampToken".equals(s.getFormat())) timestamps++;
            else if (s.isSeal()) seals++;
            else signatures++;
        }
        int sig = 0, seal = 0, ts = 0;
        for (cz.digitalnikontinuita.anep.model.SignatureSummary s : sigs) {
            if (s.getSignatureId() == null) continue;
            boolean isTs = "TimeStampToken".equals(s.getFormat());
            String label;
            if (isTs) label = timestamps > 1 ? "Časové razítko č.: " + (++ts) : "Časové razítko";
            else if (s.isSeal()) label = seals > 1 ? "Pečeť č.: " + (++seal) : "Pečeť";
            else label = signatures > 1 ? "Podpis č.: " + (++sig) : "Podpis";
            out.put(s.getSignatureId(), label);
        }
        return out;
    }

    /**
     * Co má Inspektor rozebírat: {@code [soubor, podepsaný obsah]}, nebo {@code null}.
     *
     * <p>Při externím podpisu je ve druhém poli hlavního okna ({@link #pdfOrigFile}) podpis
     * a v prvním ({@link #pdfSigFile}) dokument, který podepisuje — viz {@code MainView.analyze}.
     * Rozebírá se pak podpis a dokument mu slouží jen k přepočtu otisku. Do 13. 10. 2026
     * Inspektor bral vždy první pole a u dvojice PDF + {@code .p7s} ukazoval podpisy uvnitř PDF,
     * tedy něco jiného, než co se ověřovalo (nález uživatele). Záznam o důkazu ve druhém poli
     * Inspektor nerozkládá ({@code formatOf} ho nepozná), takže tam zůstává u dokumentu.
     */
    private File[] inspectorTarget() {
        if (pdfOrigFile != null && pdfOrigFile.exists()
                && cz.digitalnikontinuita.anep.core.StructureInspector.formatOf(pdfOrigFile) != null) {
            return new File[]{pdfOrigFile, pdfSigFile};
        }
        return pdfSigFile != null && pdfSigFile.exists() ? new File[]{pdfSigFile, null} : null;
    }

    /** Vrátí záložku do podoby před prvním ověřením; viz {@code MainView.clearResults()}. */
    public void clear() {
        pdfBtn.setDisable(true);
        inspectorBtn.setVisible(false);
        inspectorContext = null;
        cardsBox.getChildren().clear();
    }

    public void show(ValidationResult result) {
        pdfBtn.setDisable(false);
        cardsBox.getChildren().clear();
        DiagnosticData dd = result.getDiagnosticData();
        if (dd == null) {
            cardsBox.getChildren().add(new Label("Diagnostická data nejsou k dispozici."));
            return;
        }
        currentDd = dd;
        boolean isPdf = "PDF".equalsIgnoreCase(result.getDetectedContainerType())
                || result.getFileName().toLowerCase().endsWith(".pdf");

        // Build id → bestSignatureTime lookup from the flattened summaries
        java.util.Map<String, java.time.Instant> bestTimeMap = new java.util.HashMap<>();
        java.util.Map<String, String> indicationMap = new java.util.HashMap<>();
        markIds = new java.util.HashSet<>();
        for (cz.digitalnikontinuita.anep.model.SignatureSummary s : result.getSignatures()) {
            if (s.getSignatureId() != null && s.isMark()) markIds.add(s.getSignatureId());
            if (s.getBestSignatureTime() != null)
                bestTimeMap.put(s.getSignatureId(), s.getBestSignatureTime());
            if (s.getSignatureId() != null && s.getIndication() != null)
                indicationMap.put(s.getSignatureId(), s.getIndication());
        }

        // Build lookup maps from all certs in diagnostic data
        revCertNames    = new java.util.HashMap<>();
        revToCertId     = new java.util.HashMap<>();
        revToCertSerial = new java.util.HashMap<>();
        revStatusById   = new java.util.HashMap<>();
        allOnlineRevocations = new java.util.HashSet<>();
        shownOnlineRevIds    = new java.util.HashSet<>();
        for (CertificateWrapper cw : dd.getUsedCertificates()) {
            String certName = fullName(cw);
            BigInteger serial = certSerial(cw.getBinaries());
            for (CertificateRevocationWrapper crw : cw.getCertificateRevocationData()) {
                revCertNames.putIfAbsent(crw.getId(), certName);
                revToCertId.putIfAbsent(crw.getId(), cw.getId());
                revStatusById.putIfAbsent(crw.getId(), crw);
                if (serial != null) {
                    if (crw.getStatus() == eu.europa.esig.dss.enumerations.CertificateStatus.REVOKED) {
                        revToCertSerial.put(crw.getId(), serial);
                    } else {
                        revToCertSerial.putIfAbsent(crw.getId(), serial);
                    }
                }
            }
        }
        // Collect online revocations via getAllRevocationData() — RevocationWrapper has reliable origin
        for (eu.europa.esig.dss.diagnostic.RevocationWrapper rev : dd.getAllRevocationData()) {
            if (!rev.isInternalRevocationOrigin()) {
                allOnlineRevocations.add(rev);
            }
        }
        // Supplement with non-internal revocations from certificate data.
        // DSS sometimes fetches a fresh OCSP for a still-valid cert during validation
        // but does not include it in getAllRevocationData() (it appears only in the detailed
        // report's BBB/CRS sections). Those tokens can still be retrieved via the certificate's
        // getCertificateRevocationData() list, so we pick them up here as a second attempt.
        {
            java.util.Set<String> alreadyOnlineIds = new java.util.HashSet<>();
            for (eu.europa.esig.dss.diagnostic.RevocationWrapper r : allOnlineRevocations)
                alreadyOnlineIds.add(r.getId());
            for (CertificateWrapper cw : dd.getUsedCertificates()) {
                for (CertificateRevocationWrapper crw : cw.getCertificateRevocationData()) {
                    if (!crw.isInternalRevocationOrigin() && alreadyOnlineIds.add(crw.getId()))
                        allOnlineRevocations.add(crw);
                }
            }
        }
        currentSimple = parseSimpleReport(result.getSimpleReportXml());
        customAnchorCertIds = findCustomAnchors(dd);
        revRejectionReasons = parseRevocationRejectionReasons(result.getDetailedReportXml());
        suppressExpiredCertsOnCrlReasons(dd);
        // enrichWithExpiredResponderReasons adds amber warning icons (revExpiredResponderNotes)
        // for embedded OCSPs whose respondent cert has since expired. Kept separate from
        // revRejectionReasons so we never display "DSS did not use this" for an OCSP whose RAC
        // was actually PASSED — the respondent may have been valid at signing time.
        revExpiredResponderNotes = new java.util.HashMap<>();
        usedRevocationByCert = cz.digitalnikontinuita.anep.core.ReportMapper
                .usedRevocationIdByCertificate(dd, result.getDetailedReportXml(),
                        acceptExpiredRevocationSources, acceptNewerOcsp, postExpiryAccepted,
                        // Bez téhle množiny by bublina u prvku s kladným závěrem tvrdila, že se
                        // o odpověď opřít nešlo, zatímco Analýza o téže odpovědi píše „využita".
                        cz.digitalnikontinuita.anep.core.ReportMapper
                                .signingCertsOfPositiveSignatures(dd, result.getSignatures()));
        if (!acceptExpiredRevocationSources) {
            enrichWithExpiredResponderReasons(dd);
        }

        // Souhrny podle id: karta se staví z diagnostiky DSS, kdežto ladicí blok na jejím konci
        // mluví o situacích a příznacích, které počítá ReportMapper — ty jsou jen v souhrnu.
        java.util.Map<String, cz.digitalnikontinuita.anep.model.SignatureSummary> summaryById =
                new java.util.HashMap<>();
        for (cz.digitalnikontinuita.anep.model.SignatureSummary s : result.getSignatures()) {
            if (s.getSignatureId() != null) summaryById.put(s.getSignatureId(), s);
        }

        elementHeaders = elementHeaders(result.getSignatures());
        for (SignatureWrapper sig : dd.getSignatures()) {
            cardsBox.getChildren().add(buildSignatureCard(sig, isPdf, bestTimeMap, indicationMap,
                    summaryById.get(sig.getId())));
        }

        // Records covering no signature get a card of their own — a preservation container with
        // no signature inside, or a record handed in alone, would otherwise leave this tab empty.
        List<EvidenceRecordWrapper> loose = dd.getEvidenceRecords().stream()
                .filter(er -> er.getCoveredSignatures().isEmpty())
                .toList();
        if (!loose.isEmpty()) {
            cardsBox.getChildren().add(buildStandaloneEvidenceRecordCard(loose));
        }

        if (isPdf && pdfSigFile != null && pdfSigFile.exists()) {
            appendPdfStructureCard(pdfSigFile);
        }

        File[] target = inspectorTarget();
        cz.digitalnikontinuita.anep.core.StructureInspector.Format format = target == null ? null
                : cz.digitalnikontinuita.anep.core.StructureInspector.formatOf(target[0]);
        inspectorBtn.setVisible(format != null);
        if (format != null) inspectorBtn.setText(format.title());
        inspectorContext = new cz.digitalnikontinuita.anep.core.StructureAnnotations.Context(
                dd, summaryById, currentSimple, usedRevocationByCert, revRejectionReasons);
    }

    // -------------------------------------------------------------------------
    // Signature card
    // -------------------------------------------------------------------------

    private VBox buildSignatureCard(SignatureWrapper sig, boolean isPdf,
                                     java.util.Map<String, java.time.Instant> bestTimeMap,
                                     java.util.Map<String, String> indicationMap,
                                     cz.digitalnikontinuita.anep.model.SignatureSummary summary) {
        VBox card = new VBox();
        card.setStyle(CARD_BORDER);

        // Main header
        String sigLabel = elementHeaders.getOrDefault(sig.getId(),
                sig.isCounterSignature() ? "Protipodpis" : "Podpis");
        if (sig.isCounterSignature() && !sigLabel.startsWith("Protipodpis")) {
            sigLabel = "Protipodpis — " + sigLabel;
        }
        String who = summary != null ? summary.getSignerDisplayName() : null;
        card.getChildren().add(cardHeader(who != null && !who.isBlank() ? sigLabel + " · " + who : sigLabel));

        VBox body = new VBox(0);
        body.setPadding(new Insets(10));

        // --- 1. Výsledek ověření ---
        body.getChildren().add(sectionHeader("Výsledek ověření"));
        body.getChildren().add(row("Formát",         str(sig.getSignatureFormat())));
        body.getChildren().add(row("Identifikátor DSS", sig.getId()));
        body.getChildren().add(row("Algoritmus",     sigAlg(sig)));
        boolean bOk  = sig.isBLevelTechnicallyValid();
        boolean tOk  = sig.isThereTLevel() && sig.isTLevelTechnicallyValid();
        boolean ltOk = sig.isThereXLevel() && sig.isXLevelTechnicallyValid();
        boolean ltaOk= sig.isThereALevel() && sig.isALevelTechnicallyValid();
        // Strict timestamp mode: any expired cert in a signature timestamp's chain → T/LT fail
        boolean tDowngradedByStrictMode = false;
        if (!timestampStandardsMode && tOk) {
            boolean anyExpired = sig.getSignatureTimestamps().stream()
                    .anyMatch(ts -> findExpiredCertInTsChain(ts) != null);
            if (anyExpired) {
                tOk   = false;
                ltOk  = false;
                tDowngradedByStrictMode = true;
            }
        }
        body.getChildren().add(row("Úroveň B (kryptografická integ.)", boolLabel(bOk)));
        if (sig.isThereTLevel())
            body.getChildren().add(row("Úroveň T (časové razítko)",    boolLabel(tOk)));
        if (sig.isThereXLevel())
            body.getChildren().add(row("Úroveň LT (úplné revokační d.)", boolLabel(ltOk)));
        if (sig.isThereALevel())
            body.getChildren().add(row("Úroveň LTA (archivní razítko)", boolLabel(ltaOk)));

        List<String> structMsgs = sig.getStructuralValidationMessages();
        String errMsg = sig.getErrorMessage();
        if (!structMsgs.isEmpty())
            body.getChildren().add(multiRow("Strukturální chyby", structMsgs, "#cc0000"));
        if (errMsg != null && !errMsg.isBlank())
            body.getChildren().add(row("Chyba zpracování", errMsg));

        // --- 2. Podpisující / Pečetící ---
        CertificateWrapper cert = sig.getSigningCertificate();
        // Same discriminator as SignatureSummary.isSeal(): the certificate's QcType, which is
        // what the "Druh certifikátu:" row in Rozšířený přehled reports.
        boolean sealCert = cert != null && cert.getQcTypes().stream()
                .anyMatch(eu.europa.esig.dss.enumerations.QCTypeEnum.QCT_ESEAL::equals);
        // Značku (zákon č. 227/2000 Sb.) pozná jen souhrn — podle systémového certifikátu, ne QcType.
        boolean mark = markIds.contains(sig.getId());
        body.getChildren().add(sectionHeader(mark ? "Označující" : sealCert ? "Pečetící" : "Podpisující"));
        if (cert != null) {
            String signerEmail = cz.digitalnikontinuita.anep.core.CertificateEmailUtil.extractEmail(cert);
            String displayName = fullName(cert);
            if (signerEmail != null) displayName = displayName + " (" + signerEmail + ")";
            addIfNotBlank(body, mark ? "Označující osoba"
                    : sealCert ? "Pečetící osoba" : "Podepsaná osoba", displayName);
            addIfNotBlank(body, "Titul",         DnUtil.dnField(cert.getCertificateDN(), "2.5.4.12", "T"));
            String orgName = cert.getOrganizationName();
            addIfNotBlank(body, "Organizace",    orgName);
            if (orgName != null && !orgName.isBlank())
                addIfNotBlank(body, "Organizační jednotka", DnUtil.bestOu(cert.getCertificateDN()));
            addIfNotBlank(body, "Stát",          cert.getCountryName());
            addIfNotBlank(body, "E-mail",        signerEmail);
            addIfNotBlank(body, "Lokalita",      cert.getLocality());
        } else {
            body.getChildren().add(row("Certifikát", "nenalezen"));
        }
        addIfNotBlank(body, "Místo podpisu",  sigLocation(sig));
        if (!sig.getClaimedRoles().isEmpty())
            body.getChildren().add(row("Role (claimed)", sig.getSignerRoleDetails(sig.getClaimedRoles()).stream().collect(Collectors.joining(", "))));
        if (!sig.getCertifiedRoles().isEmpty())
            body.getChildren().add(row("Role (certified)", sig.getSignerRoleDetails(sig.getCertifiedRoles()).stream().collect(Collectors.joining(", "))));

        // --- 3. Časy ---
        body.getChildren().add(sectionHeader("Časy"));
        addIfNotNull(body, "Uváděný čas podpisu",             sig.getClaimedSigningTime());
        java.time.Instant best = tDowngradedByStrictMode ? java.time.Instant.now() : bestTimeMap.get(sig.getId());
        if (best != null) {
            String bestLabel = tDowngradedByStrictMode
                    ? fmtDate(java.util.Date.from(best)) + "  —  aktuální čas (razítko neplatné)"
                    : fmtDate(java.util.Date.from(best));
            body.getChildren().add(row("Rozhodný okamžik pro ověření", bestLabel));
        }
        List<TimestampWrapper> sigTs = sig.getSignatureTimestamps();
        if (!sigTs.isEmpty()) {
            TimestampWrapper ts = sigTs.get(0);
            addIfNotNull(body, "Čas razítka (T-level)", ts.getProductionTime());
            CertificateWrapper tsCert = ts.getSigningCertificate();
            if (tsCert != null)
                addIfNotBlank(body, "TSA (razítkující autorita)", tsCert.getOrganizationName() != null
                        ? tsCert.getOrganizationName() : tsCert.getCommonName());
        }

        // --- 4. Podpisový certifikát ---
        if (cert != null) {
            body.getChildren().add(certSectionHeader("Podpisový certifikát", cert));
            body.getChildren().add(row("Předmět (Subject DN)", humanDn(cert.getCertificateDN())));
            body.getChildren().add(row("Vydavatel (Issuer DN)", humanDn(cert.getCertificateIssuerDN())));
            body.getChildren().add(row("Sériové číslo certifikátu", cert.getSerialNumber()));
            // Položky serialNumber z DN, ne z DSS getSubjectSerialNumber(): ten vrací jen jednu
            // a certifikát jich může nést víc — I.CA 2026 má číslo pasu i klientské číslo
            // vydavatele. Popisky jako v Rozšířeném přehledu; dřívější „Č. v evidenci
            // vydavatele“ stálo i nad číslem občanského průkazu. Sjednoceno 8. 10. 2026.
            for (String[] entry : DnUtil.serialNumberEntries(cert.getCertificateDN())) {
                body.getChildren().add(row(entry[0].replaceAll(":$", ""), entry[1]));
            }
            addIfNotNull(body, "Platný od", cert.getNotBefore());
            addIfNotNull(body, "Platný do",  cert.getNotAfter());
            body.getChildren().add(row("Klíč", keyDesc(cert)));
            List<KeyUsageBit> ku = cert.getKeyUsages();
            if (!ku.isEmpty())
                body.getChildren().add(row("Key Usage", ku.stream().map(Enum::name).collect(Collectors.joining(", "))));
            body.getChildren().add(row("Kvalifikovaný certifikát (QC)", boolLabel(cert.isQcCompliance())));
            body.getChildren().add(row("QSCD / podpis na bezpečném zařízení", boolLabel(cert.isSupportedByQSCD())));
            if (!cert.getQcTypes().isEmpty())
                body.getChildren().add(row("Typy QC", cert.getQcTypes().stream().map(t -> t.getDescription()).collect(Collectors.joining(", "))));
            body.getChildren().add(row("Dostupná v důvěryhodném seznamu (TL)", boolLabel(cert.isTrustedListReached())));
            body.getChildren().add(row("Řetěz důvěryhodný", boolLabel(sig.isTrustedChain())));

            // TSP info
            List<TrustServiceWrapper> tsps = cert.getTrustServices();
            if (!tsps.isEmpty()) {
                body.getChildren().add(sectionHeader("Důvěryhodná služba (TSP)"));
                TrustServiceWrapper tsp = serviceToShow(cert, tsps);
                String tspName = tsp.getTspNames().isEmpty() ? null : tsp.getTspNames().get(0);
                String svcName = tsp.getServiceNames().isEmpty() ? null : tsp.getServiceNames().get(0);
                addIfNotBlank(body, "Poskytovatel (TSP)", tspName);
                addIfNotBlank(body, "Název služby",       svcName);
                addIfNotBlank(body, "Typ služby",         tsp.getType());
                addIfNotBlank(body, "Stav služby",        tsp.getStatus());
                if (tsps.size() > 1)
                    body.getChildren().add(row("Dalších TSP záznamů", String.valueOf(tsps.size() - 1)));
            }
        }

        // --- 5. Certifikační cesta ---
        List<CertificateWrapper> chain = sig.getCertificateChain();
        if (!chain.isEmpty()) {
            body.getChildren().add(sectionHeader("Certifikační cesta (" + chain.size() + " certifikátů)"));
            for (int i = 0; i < chain.size(); i++) {
                CertificateWrapper c = chain.get(i);
                String prefix = (i == 0) ? "Podpisový" : (i == chain.size()-1 ? "Kořenový CA" : "Mezilehlý CA");
                body.getChildren().add(chainRow(i + 1, prefix, c));
            }
        }

        // --- 6. Vložené validační materiály ---
        buildEmbeddedMaterials(body, sig);

        // --- 6b. Revokační informace z online zdrojů ---
        List<CertificateWrapper> sigAllCerts = new java.util.ArrayList<>(sig.getCertificateChain());
        if (cert != null && sigAllCerts.stream().noneMatch(c -> c.getId().equals(cert.getId()))) {
            sigAllCerts.add(0, cert);
        }
        buildOnlineRevocationSection(body, sigAllCerts);

        // Nothing above can have listed anything for a signing certificate that is itself the
        // trust anchor — DSS asks no revocation question about one. Said plainly, because an
        // absent section otherwise looks like a check that silently failed.
        if (cert != null && cert.isTrusted() && cert.getCertificateRevocationData().isEmpty()) {
            body.getChildren().add(trustAnchorNoRevocationNote(cert));
        }

        // When all embedded revocations for the signing cert were rejected (expired respondent
        // etc.) but the signature is TOTAL_PASSED, DSS must have used a fresh online OCSP that
        // was never serialized into the DiagnosticData. Explain this apparent contradiction.
        if ("TOTAL_PASSED".equals(indicationMap.get(sig.getId())) && cert != null) {
            List<CertificateRevocationWrapper> sigCertRevocs = cert.getCertificateRevocationData();
            boolean hasEmbedded = sigCertRevocs.stream()
                    .anyMatch(CertificateRevocationWrapper::isInternalRevocationOrigin);
            boolean allEmbeddedRejected = hasEmbedded && sigCertRevocs.stream()
                    .filter(CertificateRevocationWrapper::isInternalRevocationOrigin)
                    .allMatch(crw -> {
                        List<String> r = revRejectionReasons.get(crw.getId());
                        return r != null && !r.isEmpty();
                    });
            if (allEmbeddedRejected) {
                boolean anyOnlineShownForSigningCert = sigCertRevocs.stream()
                        .filter(crw -> !crw.isInternalRevocationOrigin())
                        .anyMatch(crw -> shownOnlineRevIds.contains(crw.getId()));
                if (!anyOnlineShownForSigningCert) {
                    body.getChildren().add(onlineOcspNotInDiagnosticsNote());
                }
            }
        }

        // --- 7. Rozsah podpisu ---
        List<XmlSignatureScope> scopes = sig.getSignatureScopes();
        if (!scopes.isEmpty()) {
            body.getChildren().add(sectionHeader("Rozsah podpisu"));
            for (XmlSignatureScope sc : scopes) {
                body.getChildren().add(row(str(sc.getScope()), sc.getDescription() != null ? sc.getDescription() : sc.getName()));
            }
        }

        // --- 7. PDF-specifické informace ---
        if (isPdf) {
            PDFRevisionWrapper pdf = sig.getPDFRevision();
            if (pdf != null) {
                body.getChildren().add(sectionHeader("PDF — podpisový slovník"));
                addIfNotBlank(body, "Filtr (Filter)",          pdf.getFilter());
                addIfNotBlank(body, "Subfiltr (SubFilter)",    pdf.getSubFilter());
                addIfNotBlank(body, "Pole podpisu",            pdf.getFirstFieldName());
                addIfNotBlank(body, "Jméno v certifikátu",     pdf.getSignerName());
                addIfNotBlank(body, "Důvod (Reason)",          pdf.getReason());
                addIfNotBlank(body, "Místo (Location)",        pdf.getLocation());
                addIfNotBlank(body, "Kontakt",                 pdf.getContactInfo());

                List<BigInteger> byteRange = pdf.getSignatureByteRange();
                if (!byteRange.isEmpty())
                    body.getChildren().add(row("ByteRange", byteRange.stream().map(BigInteger::toString).collect(Collectors.joining(", ", "[", "]"))));
                body.getChildren().add(row("ByteRange platný (pokrývá celý dok.)", boolLabel(pdf.isSignatureByteRangeValid())));
                body.getChildren().add(row("Slovník podpisu konzistentní",         boolLabel(pdf.isPdfSignatureDictionaryConsistent())));

                CertificationPermission docMdp = pdf.getDocMDPPermissions();
                if (docMdp != null)
                    body.getChildren().add(row("Certifikační omezení (DocMDP)", docMdp.name()));

                // Zámek podpisových polí. Dvě položky vedle sebe schválně: FieldMDP je podepsaná
                // stopa v /Reference podpisového slovníku a nese rozsah, /Lock je pokyn zapsaný
                // do pole a jen v něm bývá /P, tedy povolená míra změn celého dokumentu. Kdo se
                // dívá sem, chce vidět obojí zvlášť — jinak se nepozná, odkud která půlka je.
                addIfNotBlank(body, "Zámek polí — FieldMDP",     lockLabel(pdf.getFieldMDP()));
                addIfNotBlank(body, "Zámek polí — SigFieldLock", lockLabel(pdf.getSigFieldLock()));

                body.getChildren().add(sectionHeader("PDF — detekce změn po podpisu"));
                body.getChildren().add(row("Změny detekovány", boolLabelInverse(pdf.arePdfModificationsDetected())));

                if (pdf.arePdfModificationsDetected()) {
                    if (!pdf.getPdfAnnotationsOverlapConcernedPages().isEmpty())
                        body.getChildren().add(row("Překryv anotací — stránky", pageList(pdf.getPdfAnnotationsOverlapConcernedPages())));
                    if (!pdf.getPdfVisualDifferenceConcernedPages().isEmpty())
                        body.getChildren().add(row("Vizuální rozdíly — stránky", pageList(pdf.getPdfVisualDifferenceConcernedPages())));
                    if (!pdf.getPdfPageDifferenceConcernedPages().isEmpty())
                        body.getChildren().add(row("Přidané/odebrané stránky", pageList(pdf.getPdfPageDifferenceConcernedPages())));
                }

                if (pdf.arePdfObjectModificationsDetected()) {
                    List<XmlObjectModification> ext   = pdf.getPdfExtensionChanges();
                    List<XmlObjectModification> forms = pdf.getPdfSignatureOrFormFillChanges();
                    List<XmlObjectModification> ann   = pdf.getPdfAnnotationChanges();
                    List<XmlObjectModification> undef = pdf.getPdfUndefinedChanges();
                    if (!ext.isEmpty())   body.getChildren().add(row("Rozšíření dokumentu po podpisu", String.valueOf(ext.size()) + " změn"));
                    if (!forms.isEmpty()) body.getChildren().add(row("Vyplnění formulářů po podpisu",  String.valueOf(forms.size()) + " změn"));
                    if (!ann.isEmpty())   body.getChildren().add(row("Změny anotací po podpisu",       String.valueOf(ann.size()) + " změn"));
                    if (!undef.isEmpty()) body.getChildren().add(row("Nedefinované změny po podpisu",  String.valueOf(undef.size()) + " změn"));
                    List<String> modFields = pdf.getModifiedFieldNames();
                    if (!modFields.isEmpty())
                        body.getChildren().add(row("Upravená pole", String.join(", ", modFields)));
                }
            }
        }

        // --- 8. Časová razítka ---
        List<TimestampWrapper> allTs = sig.getTimestampList();
        if (!allTs.isEmpty()) {
            body.getChildren().add(sectionHeader("Časová razítka (" + allTs.size() + ")"));
            for (int i = 0; i < allTs.size(); i++) {
                TimestampWrapper ts = allTs.get(i);
                java.time.Instant validationTime = nextProofTime(allTs, i);
                body.getChildren().add(new Separator());
                buildTimestampSection(body, ts, validationTime);
            }
        }

        // Evidence records covering this signature. Placed after the time-stamps because that is
        // the order in which they matter: the record is what carries the proof onwards once the
        // signature's own chain runs out.
        List<EvidenceRecordWrapper> records = sig.getEvidenceRecords();
        for (int i = 0; i < records.size(); i++) {
            body.getChildren().add(new Separator());
            buildEvidenceRecordSection(body, records.get(i), i + 1, records.size());
        }

        appendDebugSection(body, summary);

        card.getChildren().add(body);
        return card;
    }

    /**
     * Ladicí informace na úplný konec karty jednoho podpisu.
     *
     * <p>Do 2. 9. 2026 stály v Analýze, pod každou kartou s nálezem. Patří sem: Analýza odpovídá
     * na otázku „co s tím", kdežto výčet situací a příznaků je podklad pro ladění, a na jedné
     * kartě se ty dvě věci navzájem přebíjely. Detailní rozbor je tab, kam čtenář chodí právě pro
     * tohle.
     *
     * <p><b>Situace se vypisují vždy</b>, i u podpisu, jehož platnost byla ověřena. V Analýze se
     * u kladného výsledku vynechávaly — tam se blok jmenoval „zjištěné příčiny" a u platného
     * podpisu by neměly co pojmenovávat. Tady žádné příčiny nejsou, je to soupis nálezu, a ten
     * je u platného podpisu stejně zajímavý: právě situace 31 až 35 a 45 mluví o dokumentu, který
     * prošel, a rozhodují o tom, co s ním půjde dělat dál.
     *
     * <p>Oddělené dvojitě — čárou a nadpisem oddílu —, protože všechno nad ním je čtení
     * diagnostiky DSS, kdežto tohle jsou závěry, které z ní vyvodil AnEP. Míchat obojí bez
     * hranice by u tabu, který má být doslovným výpisem, mátlo.
     */
    private void appendDebugSection(VBox body,
                                     cz.digitalnikontinuita.anep.model.SignatureSummary summary) {
        if (summary == null) return;

        body.getChildren().add(new Separator());
        body.getChildren().add(sectionHeader("Ladicí informace (nálezy AnEP)"));

        java.util.List<Integer> situations = summary.getDetectedSituations();
        if (!situations.isEmpty()) {
            VBox causes = new VBox(4);
            causes.setPadding(new Insets(2, 8, 6, 8));
            causes.getChildren().add(ResultView.buildSituationsBlock(situations, summary));
            if (situations.contains(43) && summary.getWithdrawnTsaDetail() != null) {
                Label withdrawn = new Label(summary.getWithdrawnTsaDetail());
                withdrawn.setStyle("-fx-text-fill: #7a4a00; -fx-font-size: 12px;"
                        + " -fx-padding: 0 0 0 138;");
                withdrawn.setWrapText(true);
                causes.getChildren().add(withdrawn);
            }
            body.getChildren().add(causes);
        }

        boolean s33 = situations.contains(33);
        boolean s34 = situations.contains(34);
        Label dbg = new Label("[ladění]"
                + "  S31 nepřerušený řetězec: " + yn(situations.contains(31))
                + "   S32 poslední razítko v platnosti dle cert.: " + yn(situations.contains(32))
                + "   S33 revokace všech certifikátů dostupné: " + yn(s33)
                + "   S45 dostupné jen tolerantně: " + yn(situations.contains(45))
                + "   S34 ověření bez ústupků: " + yn(s34)
                + "   S35 zachování by obnovilo ověřitelnost: " + yn(situations.contains(35))
                + "   S43 razítko od vyřazené služby: " + yn(situations.contains(43))
                + "   S49 důkaz od vyřazené služby: " + yn(situations.contains(49))
                + "   S50 nekvalifikovaný článek řady: " + yn(situations.contains(50)));
        dbg.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;"
                + " -fx-font-style: italic; -fx-padding: 2 8 0 8;");
        dbg.setWrapText(true);
        body.getChildren().add(dbg);

        if (!s34 && summary.getS34FailReason() != null) {
            body.getChildren().add(debugNote("Proč ne S34: " + summary.getS34FailReason(), "#666666"));
        }
        if (s33 && summary.getS33Detail() != null) {
            body.getChildren().add(debugNote(summary.getS33Detail(), "#446644"));
        }
        if (!s33) {
            if (summary.getS33MissingDetail() != null) {
                body.getChildren().add(debugNote("Chybí: " + summary.getS33MissingDetail(), "#884400"));
            }
            if (summary.getS33Detail() != null) {
                body.getChildren().add(debugNote("Revokace certifikátů: " + summary.getS33Detail(),
                        "#446644"));
            }
            // Co našlo volnější čtení tam, kde přísné vzdalo — vedle řádku „Chybí" schválně:
            // obě mluví o týchž certifikátech a čtenář musí vidět, které odmítnutí je ve hře.
            if (summary.getS45Detail() != null) {
                body.getChildren().add(debugNote("Tolerantně k dispozici: " + summary.getS45Detail(),
                        "#665500"));
            }
        }
    }

    private static String yn(boolean b) {
        return b ? "ANO" : "NE";
    }

    private static Label debugNote(String text, String color) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-size: 10px; -fx-text-fill: " + color + ";"
                + " -fx-font-style: italic; -fx-padding: 0 8 4 24;");
        lbl.setWrapText(true);
        return lbl;
    }

    // -------------------------------------------------------------------------
    // Evidence records
    // -------------------------------------------------------------------------

    private VBox buildStandaloneEvidenceRecordCard(List<EvidenceRecordWrapper> records) {
        VBox card = new VBox();
        card.setStyle(CARD_BORDER);
        card.getChildren().add(cardHeader(records.size() == 1
                ? "Evidenční záznam bez vazby na podpis"
                : "Evidenční záznamy bez vazby na podpis (" + records.size() + ")"));

        VBox body = new VBox(2);
        body.setPadding(new Insets(8, 10, 10, 10));
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) body.getChildren().add(new Separator());
            buildEvidenceRecordSection(body, records.get(i), i + 1, records.size());
        }
        card.getChildren().add(body);
        return card;
    }

    /**
     * Renders one evidence record in full detail.
     *
     * <p>What this adds over the summary in Rozšířený přehled is the item-by-item view: which
     * covered object matched and which did not, the material the record carries inside itself,
     * and the complete certificate path behind each of its archive time-stamps. The summary
     * answers whether the record holds; this answers why.
     */
    private void buildEvidenceRecordSection(VBox body, EvidenceRecordWrapper er,
                                            int index, int total) {
        String heading = total == 1 ? "Evidenční záznam" : "Evidenční záznam " + index + " z " + total;
        body.getChildren().add(sectionHeader(heading + " — " + erTypeLabel(er)));

        if (er.getFilename() != null && !er.getFilename().isBlank()) {
            body.getChildren().add(row("Soubor", er.getFilename()));
        }
        body.getChildren().add(row("Původ", erOriginLabel(er)));
        body.getChildren().add(row("Vložen v podpisu", er.isEmbedded() ? "ano" : "ne"));
        if (er.getDigestAlgoAndValue() != null && er.getDigestAlgoAndValue().getDigestMethod() != null) {
            body.getChildren().add(row("Otisk záznamu",
                    er.getDigestAlgoAndValue().getDigestMethod().getName()));
        }

        // Structural validation is reported as plain rows, not as an error: a record can deviate
        // from its schema and still protect its data. See the note in ExtendedView.
        if (!er.isStructuralValidationValid()) {
            List<String> msgs = er.getStructuralValidationMessages();
            body.getChildren().add(multiRow("Odchylka od schématu",
                    msgs.isEmpty() ? List.of("(bez bližšího určení)") : msgs, "#111111"));
        }

        List<XmlDigestMatcher> matchers = er.getDigestMatchers();
        body.getChildren().add(sectionHeader("Kryté objekty (" + matchers.size() + ")"));
        if (matchers.isEmpty()) {
            body.getChildren().add(row("—", "záznam neuvádí žádný otisk"));
        }
        for (XmlDigestMatcher m : matchers) {
            String name = m.getDocumentName() != null && !m.getDocumentName().isBlank()
                    ? m.getDocumentName()
                    : "(bez názvu — objekt nebyl předložen, a záznam sám jména nenese)";
            String state;
            if (!m.isDataFound()) {
                state = "nepředložen k ověření";
            } else if (m.isDataIntact()) {
                state = "otisk souhlasí";
            } else {
                state = "OTISK NESOUHLASÍ — data byla po vytvoření záznamu změněna";
            }
            String algo = m.getDigestMethod() != null ? m.getDigestMethod().getName() : "?";
            body.getChildren().add(row(erMatcherLabel(m.getType()),
                    name + "  |  " + state + "  |  " + algo));
        }

        List<TimestampWrapper> tsList = er.getTimestampList();
        if (!tsList.isEmpty()) {
            body.getChildren().add(sectionHeader("Časová razítka záznamu (" + tsList.size() + ")"));
            for (int i = 0; i < tsList.size(); i++) {
                TimestampWrapper ts = tsList.get(i);
                // Each time-stamp is judged as of the next one in the sequence, exactly as the
                // signature's own chain is: a renewal is what keeps its predecessor verifiable.
                java.time.Instant validationTime = nextProofTime(tsList, i);
                body.getChildren().add(new Separator());
                Label kind = new Label("Role v záznamu: " + erTimestampKindLabel(ts));
                kind.setStyle("-fx-font-size:11px;-fx-font-weight:bold;-fx-text-fill:#003189;-fx-padding:4 0 0 0;");
                body.getChildren().add(kind);
                buildTimestampSection(body, ts, validationTime);
            }
        }

        buildEmbeddedMaterials(body, er.foundCertificates(), er.foundRevocations());
    }

    private String erTypeLabel(EvidenceRecordWrapper er) {
        if (er.getEvidenceRecordType() == null) return "neznámý formát";
        return switch (er.getEvidenceRecordType()) {
            case XML_EVIDENCE_RECORD  -> "XMLERS (RFC 6283)";
            case ASN1_EVIDENCE_RECORD -> "ERS (RFC 4998)";
        };
    }

    private String erOriginLabel(EvidenceRecordWrapper er) {
        if (er.getOrigin() == null) return "neurčen";
        return switch (er.getOrigin()) {
            case SIGNATURE -> "vložen v podpisu";
            case CONTAINER -> "v kontejneru";
            case EXTERNAL  -> "samostatný soubor";
        };
    }

    private String erTimestampKindLabel(TimestampWrapper ts) {
        if (ts.getEvidenceRecordTimestampType() == null) return "archivní časové razítko";
        return switch (ts.getEvidenceRecordTimestampType()) {
            case ARCHIVE_TIMESTAMP -> "archivní časové razítko";
            case TIMESTAMP_RENEWAL_ARCHIVE_TIMESTAMP ->
                    "obnova časového razítka (RFC 4998 čl. 5.2)";
            case HASH_TREE_RENEWAL_ARCHIVE_TIMESTAMP ->
                    "obnova stromu otisků (RFC 4998 čl. 5.3)";
        };
    }

    private String erMatcherLabel(eu.europa.esig.dss.enumerations.DigestMatcherType t) {
        if (t == null) return "Objekt";
        return switch (t) {
            case EVIDENCE_RECORD_ARCHIVE_OBJECT     -> "Archivní objekt";
            case EVIDENCE_RECORD_ORPHAN_REFERENCE   -> "Osiřelý odkaz";
            case EVIDENCE_RECORD_MASTER_SIGNATURE   -> "Krytý podpis";
            case EVIDENCE_RECORD_ARCHIVE_TIME_STAMP -> "Otisk archivního razítka";
            case EVIDENCE_RECORD_ARCHIVE_TIME_STAMP_SEQUENCE -> "Otisk posloupnosti razítek";
            default -> t.name().replace("_", " ");
        };
    }

    // -------------------------------------------------------------------------
    // Embedded validation materials
    // -------------------------------------------------------------------------

    private void buildEmbeddedMaterials(VBox body, SignatureWrapper sig) {
        buildEmbeddedMaterials(body, sig.foundCertificates(), sig.foundRevocations());
    }

    /**
     * Renders whatever validation material a token carries inside itself. Takes the two proxies
     * rather than the token because an evidence record carries the same kinds of material as a
     * signature — certificates and revocation data for its own time-stamps — and DSS exposes
     * them through the identical interfaces.
     */
    private void buildEmbeddedMaterials(VBox body, FoundCertificatesProxy fc,
                                        FoundRevocationsProxy fr) {

        List<RelatedCertificateWrapper> relCerts    = fc.getRelatedCertificates();
        List<OrphanCertificateWrapper>  orphanCerts = fc.getOrphanCertificates();
        List<RelatedRevocationWrapper>  relRevs     = fr.getRelatedRevocationData();

        if (relCerts.isEmpty() && orphanCerts.isEmpty() && relRevs.isEmpty()) return;

        body.getChildren().add(sectionHeader(
                "Vložené validační materiály ("
                + (relCerts.size() + orphanCerts.size()) + " cert., "
                + relRevs.size() + " rev. záznamů)"));

        // Certifikáty s přímou vazbou na ověřovací řetěz — seskupené podle umístění
        for (CertificateOrigin origin : CertificateOrigin.values()) {
            List<RelatedCertificateWrapper> group = fc.getRelatedCertificatesByOrigin(origin);
            if (group.isEmpty()) continue;
            String loc = certOriginLabel(origin);
            for (RelatedCertificateWrapper rc : group) {
                String detail = humanDn(rc.getCertificateDN())
                        + "  |  platný do " + fmtDate(rc.getNotAfter());
                body.getChildren().add(certRow("Certifikát [" + loc + "]", detail, rc,
                        anchoredByCustomStore(rc)));
            }
        }

        // Osiřelé certifikáty — v dokumentu, ale bez přímé vazby na žádný ověřovací token
        for (CertificateOrigin origin : CertificateOrigin.values()) {
            List<OrphanCertificateWrapper> group = fc.getOrphanCertificatesByOrigin(origin);
            if (group.isEmpty()) continue;
            String loc = certOriginLabel(origin);
            for (OrphanCertificateWrapper oc : group) {
                String detail = humanDn(oc.getCertificateDN())
                        + "  |  platný do " + fmtDate(oc.getNotAfter());
                body.getChildren().add(orphanCertRow("Certifikát [" + loc + "]", detail, oc));
            }
        }

        // Revokační záznamy — CRL jednotlivě s tlačítkem, OCSP souhrnně
        for (RevocationOrigin origin : RevocationOrigin.values()) {
            List<RelatedRevocationWrapper> group = fr.getRelatedRevocationsByOrigin(origin);
            if (group.isEmpty()) continue;
            String loc = revOriginLabel(origin);

            List<RelatedRevocationWrapper> crls  = group.stream()
                    .filter(r -> r.getRevocationType() == RevocationType.CRL).toList();
            List<RelatedRevocationWrapper> ocsps = group.stream()
                    .filter(r -> r.getRevocationType() == RevocationType.OCSP).toList();

            for (RelatedRevocationWrapper crl : crls) {
                body.getChildren().add(crlRow("CRL [" + loc + "]", revDetail(crl, "CRL"), crl));
            }
            for (RelatedRevocationWrapper ocsp : ocsps) {
                body.getChildren().add(ocspRow("OCSP [" + loc + "]", revDetail(ocsp, "OCSP"), ocsp));
            }
        }
    }

    /**
     * Shows CRL/OCSP records fetched online (or from the user's custom CRL store) during
     * validation, for the given certificate chain.
     *
     * <p>Three-tier lookup strategy (each tier is a fallback for the previous one):
     * <ol>
     *   <li>Method A — via pre-built {@code revToCertId} map: works when DSS includes
     *       online-fetched revocations in {@code getCertificateRevocationData()}.</li>
     *   <li>Method B — direct cert scan: iterate {@code getUsedCertificates()} for certs in the
     *       given chain and match their revocation data against {@code allOnlineRevocations}.
     *       Handles the case where the pre-built map is incomplete.</li>
     *   <li>Method C — show all not-yet-displayed online revocations: used when the cert-chain
     *       association cannot be determined at all. Each token is shown at most once per
     *       document (tracked in {@code shownOnlineRevIds}) to avoid duplication across
     *       multiple signature/timestamp sections.</li>
     * </ol>
     */
    private void buildOnlineRevocationSection(VBox body, List<CertificateWrapper> chain) {
        if (allOnlineRevocations.isEmpty()) return;

        // Quick-lookup map: online revocation ID → RevocationWrapper
        java.util.Map<String, eu.europa.esig.dss.diagnostic.RevocationWrapper> onlineById =
                new java.util.LinkedHashMap<>();
        for (eu.europa.esig.dss.diagnostic.RevocationWrapper rev : allOnlineRevocations) {
            onlineById.put(rev.getId(), rev);
        }

        java.util.Set<String> chainCertIds = chain.stream()
                .map(CertificateWrapper::getId)
                .collect(java.util.stream.Collectors.toSet());

        java.util.Set<String> relevantIds = new java.util.LinkedHashSet<>();

        // Method A: via pre-built revToCertId
        for (eu.europa.esig.dss.diagnostic.RevocationWrapper rev : allOnlineRevocations) {
            String certId = revToCertId.get(rev.getId());
            if (certId != null && chainCertIds.contains(certId)) {
                relevantIds.add(rev.getId());
            }
        }

        // Method B: direct certificate scan (fallback when revToCertId misses online revocations)
        if (relevantIds.isEmpty() && currentDd != null) {
            for (CertificateWrapper cw : currentDd.getUsedCertificates()) {
                if (!chainCertIds.contains(cw.getId())) continue;
                for (CertificateRevocationWrapper crw : cw.getCertificateRevocationData()) {
                    if (onlineById.containsKey(crw.getId())) {
                        relevantIds.add(crw.getId());
                    }
                }
            }
        }

        // Method C: cert association unknown — show all not-yet-displayed online revocations.
        // shownOnlineRevIds prevents the same token from appearing in both the signature
        // section and the timestamp section.
        if (relevantIds.isEmpty()) {
            for (String id : onlineById.keySet()) {
                if (!shownOnlineRevIds.contains(id)) {
                    relevantIds.add(id);
                }
            }
        }

        // Exclude tokens already shown in a previous section, then record the remainder.
        relevantIds.removeAll(shownOnlineRevIds);
        shownOnlineRevIds.addAll(relevantIds);

        List<eu.europa.esig.dss.diagnostic.RevocationWrapper> relevant = relevantIds.stream()
                .map(onlineById::get)
                .filter(Objects::nonNull)
                .sorted(java.util.Comparator.comparing(
                        rev -> rev.getThisUpdate() != null ? rev.getThisUpdate() : new Date(0)))
                .collect(java.util.stream.Collectors.toList());

        if (relevant.isEmpty()) return;

        List<cz.digitalnikontinuita.anep.core.CustomCrlStore.Entry> customCrls =
                cz.digitalnikontinuita.anep.core.CustomCrlStore.listCrls();

        List<eu.europa.esig.dss.diagnostic.RevocationWrapper> fromCustomStore = new java.util.ArrayList<>();
        List<eu.europa.esig.dss.diagnostic.RevocationWrapper> fromOnline = new java.util.ArrayList<>();
        for (eu.europa.esig.dss.diagnostic.RevocationWrapper rev : relevant) {
            (isFromCustomCrlStore(rev, customCrls) ? fromCustomStore : fromOnline).add(rev);
        }

        if (!fromCustomStore.isEmpty()) {
            body.getChildren().add(sectionHeader(
                    "Revokační informace z vlastního úložiště CRL (" + fromCustomStore.size() + ")"));
            addRevocationRows(body, fromCustomStore);
        }
        if (!fromOnline.isEmpty()) {
            body.getChildren().add(sectionHeader(
                    "Revokační informace z online zdrojů (" + fromOnline.size() + ")"));
            addRevocationRows(body, fromOnline);
        }
    }

    private void addRevocationRows(VBox body, List<eu.europa.esig.dss.diagnostic.RevocationWrapper> revocations) {
        for (eu.europa.esig.dss.diagnostic.RevocationWrapper rev : revocations) {
            String certName = revCertNames.get(rev.getId());
            String type = rev.getRevocationType() == RevocationType.CRL ? "CRL" : "OCSP";
            String detail = revDetail(rev, type, certName);
            if (rev.getRevocationType() == RevocationType.CRL) {
                body.getChildren().add(crlRow(type, detail, rev));
            } else {
                body.getChildren().add(ocspRow(type, detail, rev));
            }
        }
    }

    /**
     * Which of a certificate's trust services to describe. DSS attaches every service the
     * certificate can be matched against, and the list starts with the CA/QC service that issued
     * it — which for most certificates is the one worth naming.
     *
     * <p>Not for all of them. A certificate the Trusted List carries as a service's own digital
     * identity — the seal of a qualified validation service, a TSU certificate — matches both
     * that service and the CA/QC service above it, and taking the first named "I.CA — vydávání
     * kvalifikovaných certifikátů" for a certificate whose entry in the list actually reads
     * "I.CA QVerify". Where the certificate is a service's digital identity, that service is the
     * true answer and is preferred here.
     */
    private static TrustServiceWrapper serviceToShow(CertificateWrapper cert,
                                                     List<TrustServiceWrapper> services) {
        for (TrustServiceWrapper ts : services) {
            CertificateWrapper sdi = ts.getServiceDigitalIdentifier();
            if (sdi != null && cert.getId().equals(sdi.getId())) return ts;
        }
        return services.get(0);
    }

    /**
     * Informational note for a signing certificate whose revocation status was never asked about
     * because the certificate is itself a trust anchor. The revocation sections above are then
     * simply absent, which reads as a check that failed to run rather than one that does not
     * apply.
     *
     * <p>Only for the certificate the signature rests on. A TSU certificate the Trusted List names
     * directly is in the same position, but that is so ordinary that saying it for every
     * time-stamp would bury the case worth reading.
     */
    private HBox trustAnchorNoRevocationNote(CertificateWrapper cert) {
        Label icon = new Label("ℹ");
        icon.setStyle("-fx-text-fill:#0055aa;-fx-font-size:14px;-fx-font-weight:bold;");

        TrustServiceWrapper own = null;
        for (TrustServiceWrapper ts : cert.getTrustServices()) {
            CertificateWrapper sdi = ts.getServiceDigitalIdentifier();
            if (sdi != null && cert.getId().equals(sdi.getId())) { own = ts; break; }
        }
        String svc = own == null || own.getServiceNames().isEmpty()
                ? null : own.getServiceNames().get(0);

        Label text = new Label(
                "Stav revokace podpisového certifikátu se nezjišťoval: certifikát je sám kotvou důvěry"
                + (svc != null ? " — v důvěryhodném seznamu je uveden jako identita služby „"
                        + svc + "“" : "")
                + ". Podle ETSI EN 319 102-1 se u kotvy důvěry revokace neověřuje; o jejím "
                + "vyřazení rozhoduje stav služby v důvěryhodném seznamu, nikoli CRL nebo OCSP. "
                + "Prázdné revokační sekce výše tedy nejsou známkou neprovedené kontroly.");
        text.setWrapText(true);
        text.setStyle("-fx-font-size:11px;-fx-text-fill:#333333;");
        javafx.scene.layout.HBox.setHgrow(text, javafx.scene.layout.Priority.ALWAYS);

        HBox box = new HBox(6, icon, text);
        box.setPadding(new Insets(5, 6, 5, 6));
        box.setStyle(
                "-fx-background-color:#e8f0fe;-fx-border-color:#0055aa;-fx-border-width:0 0 0 3;" +
                "-fx-border-radius:0;-fx-background-radius:2;");
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    /**
     * Informational note shown when DSS validated a signature as TOTAL_PASSED using a fresh
     * online OCSP that it did not serialize into the DiagnosticData (so nothing appears in the
     * normal "online sources" section). Without this note the user would see only unusable
     * embedded revocations and no explanation for why the signature is still valid.
     */
    private HBox onlineOcspNotInDiagnosticsNote() {
        Label icon = new Label("ℹ");
        icon.setStyle("-fx-text-fill:#0055aa;-fx-font-size:14px;-fx-font-weight:bold;");

        Label text = new Label(
                "Podpis byl ověřen jako platný (TOTAL_PASSED): validátor si vyžádal čerstvou " +
                "OCSP odpověď online, která potvrdila platnost podpisového certifikátu. " +
                "Tato odpověď se nenachází v DiagnosticData DSS, a proto není zobrazena " +
                "výše — lze ji ověřit v DetailedReport.xml (atribut LatestAcceptableRevocationId).");
        text.setWrapText(true);
        text.setStyle("-fx-font-size:11px;-fx-text-fill:#333333;");
        javafx.scene.layout.HBox.setHgrow(text, javafx.scene.layout.Priority.ALWAYS);

        HBox box = new HBox(6, icon, text);
        box.setPadding(new Insets(5, 6, 5, 6));
        box.setStyle(
                "-fx-background-color:#e8f0fe;-fx-border-color:#0055aa;-fx-border-width:0 0 0 3;" +
                "-fx-border-radius:0;-fx-background-radius:2;");
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    /** True if {@code rev}'s raw bytes match a CRL file in the user's custom CRL store. */
    private boolean isFromCustomCrlStore(eu.europa.esig.dss.diagnostic.RevocationWrapper rev,
                                         List<cz.digitalnikontinuita.anep.core.CustomCrlStore.Entry> customCrls) {
        if (rev.getRevocationType() != RevocationType.CRL) return false;
        byte[] der = rev.getBinaries();
        if (der == null) return false;
        for (cz.digitalnikontinuita.anep.core.CustomCrlStore.Entry entry : customCrls) {
            try {
                if (java.util.Arrays.equals(der, entry.crl().getEncoded())) return true;
            } catch (Exception ignored) {
                // skip unparsable entry
            }
        }
        return false;
    }

    private String certOriginLabel(CertificateOrigin o) {
        return switch (o) {
            case DSS_DICTIONARY              -> "PDF DSS slovník";
            case VRI_DICTIONARY              -> "PDF VRI slovník";
            case CERTIFICATE_VALUES          -> "CAdES/PAdES CertificateValues";
            case KEY_INFO                    -> "CMS KeyInfo (v podpisu)";
            case SIGNED_DATA                 -> "CMS SignedData";
            case ATTR_AUTHORITIES_CERT_VALUES -> "AttrAuthoritiesCertValues";
            case TIMESTAMP_VALIDATION_DATA   -> "TimestampValidationData";
            case ANY_VALIDATION_DATA         -> "AnyValidationData";
            case BASIC_OCSP_RESP             -> "OCSP odpověď";
            default                          -> o.name();
        };
    }

    private String revOriginLabel(RevocationOrigin o) {
        return switch (o) {
            case DSS_DICTIONARY                -> "PDF DSS slovník";
            case VRI_DICTIONARY                -> "PDF VRI slovník";
            case REVOCATION_VALUES             -> "CAdES/PAdES RevocationValues";
            case ATTRIBUTE_REVOCATION_VALUES   -> "AttributeRevocationValues";
            case ADBE_REVOCATION_INFO_ARCHIVAL -> "ADBE RevocationInfo (starý PAdES)";
            case TIMESTAMP_VALIDATION_DATA     -> "TimestampValidationData";
            case CMS_SIGNED_DATA               -> "CMS SignedData";
            case ANY_VALIDATION_DATA           -> "AnyValidationData";
            case INPUT_DOCUMENT                -> "vlastní úložiště CRL";
            case EXTERNAL                      -> "externí (online dotaz)";
            case CACHED                        -> "cache";
            default                            -> o.name();
        };
    }

    // -------------------------------------------------------------------------
    // UI building blocks
    // -------------------------------------------------------------------------

    private HBox cardHeader(String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:" + HDR_FG + ";");
        lbl.setWrapText(true);
        HBox box = new HBox(lbl);
        box.setPadding(new Insets(8, 12, 8, 12));
        box.setStyle("-fx-background-color:" + HDR_BG + ";");
        return box;
    }

    private HBox sectionHeader(String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-size:11px;-fx-font-weight:bold;-fx-text-fill:" + SEC_FG + ";");
        HBox box = new HBox(lbl);
        box.setPadding(new Insets(6, 12, 4, 0));
        box.setStyle("-fx-background-color:transparent;");
        // separator above
        return box;
    }

    private Separator sep() {
        return new Separator();
    }

    private static HBox row(String label, String value) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-weight:bold;-fx-text-fill:#111111;-fx-font-size:12px;");
        lbl.setMinWidth(220);
        lbl.setMaxWidth(220);
        lbl.setWrapText(true);

        TextArea val = ResultView.selectable(value, "-fx-text-fill:#111111;-fx-font-size:12px;");
        HBox.setHgrow(val, Priority.ALWAYS);

        HBox box = new HBox(8, lbl, val);
        box.setPadding(new Insets(1, 0, 1, 0));
        return box;
    }

    private VBox multiRow(String label, List<String> items, String color) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-weight:bold;-fx-text-fill:#111111;-fx-font-size:12px;");
        lbl.setMinWidth(220);

        VBox vals = new VBox(2);
        for (String item : items) {
            Label v = new Label("• " + item);
            v.setStyle("-fx-text-fill:" + color + ";-fx-font-size:12px;");
            v.setWrapText(true);
            vals.getChildren().add(v);
        }
        HBox.setHgrow(vals, Priority.ALWAYS);
        HBox box = new HBox(8, lbl, vals);
        box.setPadding(new Insets(1, 0, 1, 0));
        VBox outer = new VBox(box);
        return outer;
    }

    private HBox chainRow(int idx, String role, CertificateWrapper c) {
        String text = idx + ". [" + role + "]  " + humanDn(c.getCertificateDN())
                + "  |  " + fmtDate(c.getNotBefore()) + " – " + fmtDate(c.getNotAfter());

        Text textNode = new Text(text);
        textNode.setStyle("-fx-font-size:11px;-fx-fill:#111111;");

        Hyperlink ukazLink = new Hyperlink("Ukaž");
        ukazLink.setStyle("-fx-font-size:11px;-fx-text-fill:#0055cc;-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ukazLink.setOnAction(e -> { openCertViewer(c); ukazLink.setVisited(false); });
        Hyperlink ulozLink = new Hyperlink("Ulož");
        ulozLink.setStyle("-fx-font-size:11px;-fx-text-fill:#0055cc;-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ulozLink.setOnAction(e -> { saveCertToFile(c); ulozLink.setVisited(false); });

        TextFlow flow = new TextFlow(textNode);
        if (c.isTrusted()) {
            flow.getChildren().addAll(new Text("  "), trustAnchorBadge(anchoredByCustomStore(c)));
        } else if (c.isSelfSigned()) {
            flow.getChildren().addAll(new Text("  "), selfSignedBadge(), new Text("  "), addToTrustButton(c));
        }
        flow.getChildren().addAll(new Text("  "), ukazLink, new Text("  "), ulozLink);
        HBox.setHgrow(flow, Priority.ALWAYS);
        withCopyMenu(flow, text);

        HBox box = new HBox(flow);
        box.setPadding(new Insets(1, 0, 1, 8));
        return box;
    }

    /**
     * Returns the first certificate in the timestamp's chain whose notAfter is before today,
     * or {@code null} if all certs are currently valid. Used only in strict timestamp mode.
     *
     * DSS may omit the signing cert from getCertificateChain() when it is itself a trust anchor
     * (it becomes the chain endpoint and is not repeated). We check it explicitly first.
     */
    private CertificateWrapper findExpiredCertInTsChain(TimestampWrapper ts) {
        Date now = new Date();
        // Resolve certs through the full pool: chain/signing wrappers may be thin references
        // whose getNotAfter() returns null, while getUsedCertificateById() returns full data.
        CertificateWrapper signingCert = ts.getSigningCertificate();
        if (signingCert != null) {
            CertificateWrapper full = currentDd != null
                    ? currentDd.getUsedCertificateById(signingCert.getId()) : signingCert;
            if (full == null) full = signingCert;
            if (full.getNotAfter() != null && full.getNotAfter().before(now)) {
                return full;
            }
        }
        for (CertificateWrapper c : ts.getCertificateChain()) {
            CertificateWrapper full = currentDd != null
                    ? currentDd.getUsedCertificateById(c.getId()) : c;
            if (full == null) full = c;
            if (full.getNotAfter() != null && full.getNotAfter().before(now)) {
                return full;
            }
        }
        return null;
    }

    /**
     * Zda razítko obstálo natolik, aby jím šlo měřit čas ověření razítka předchozího.
     *
     * <p>Neporušenost tokenu na to nestačí, a proto se sem ptá i na závěr DSS: razítko od služby
     * po sunsetu je neporušené, podepsané platným klíčem a jeho řetěz končí u certifikátu, který
     * v seznamu je — a přesto z něj žádný důkaz existence neplyne. Bez téhle otázky se řádek
     * „Platnost ověřována k" odvolával na razítko, které samo neobstálo.
     */
    private boolean isTsBasicallyValid(TimestampWrapper ts) {
        if (!(ts.isMessageImprintDataIntact() && ts.isSignatureIntact()
                && ts.isSignatureValid() && ts.isTrustedChain())) {
            return false;
        }
        Indication indication = tsIndication(ts);
        return indication == null || indication == Indication.PASSED;
    }

    /**
     * Okamžik, k němuž se posuzuje platnost razítka na pozici {@code i}: čas nejbližšího
     * pozdějšího razítka, které obstálo, jinak přítomnost.
     *
     * <p>Hledá se dál než o jednu pozici. Řada razítek nemusí být samá platná a to, které
     * neobstálo, žádný důkaz existence nenese — přeskočí se, přesně jako to dělá DSS, když
     * u tokenu uvádí „Lowest POE". Dokud se dívalo jen na následující razítko, odvolával se
     * řádek na razítko, které samo neobstálo.
     */
    private java.time.Instant nextProofTime(List<TimestampWrapper> all, int i) {
        for (int j = i + 1; j < all.size(); j++) {
            TimestampWrapper next = all.get(j);
            if (isTsBasicallyValid(next) && next.getProductionTime() != null) {
                return next.getProductionTime().toInstant();
            }
        }
        return java.time.Instant.now();
    }

    /** Závěr DSS o tomto tokenu, nebo {@code null}, není-li zpráva k dispozici. */
    private Indication tsIndication(TimestampWrapper ts) {
        return currentSimple != null ? currentSimple.getIndication(ts.getId()) : null;
    }

    /**
     * Zpráva o výsledku ověření rozebraná zpátky z XML, které nese {@link ValidationResult}.
     * Vrací {@code null}, když ji nelze přečíst — volající pak zůstane u zjištěných skutečností,
     * což je stav před 29. 8. 2026.
     */
    private static eu.europa.esig.dss.simplereport.SimpleReport parseSimpleReport(String xml) {
        if (xml == null || xml.isBlank()) return null;
        try {
            return new eu.europa.esig.dss.simplereport.SimpleReport(
                    eu.europa.esig.dss.simplereport.SimpleReportFacade.newFacade().unmarshall(xml));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Česky, proč DSS token neuznal. Kód pod-indikace se připojuje v závorce: tahle záložka je
     * expertní a vypisuje surové údaje, takže je co dohledat.
     */
    private static String subIndicationText(Indication indication, SubIndication sub) {
        String detail = switch (sub == null ? SubIndication.FORMAT_FAILURE : sub) {
            // Překlad je schválně širší než „chybí kotva důvěry". Tenhle kód nese DSS i tam,
            // kde kotva je a chybí jen revokační údaj k některému článku řetězu: ze 209
            // neúspěšných samostatných razítek ve skenu ze 14. 9. 2026 jich 58 mělo právě
            // tuhle pod-indikaci s klíčem BBB_XCV_IRDPFC_ANS. Užší překlad by i expertovi
            // podsouval závěr, který z kódu neplyne.
            case NO_CERTIFICATE_CHAIN_FOUND, NO_CERTIFICATE_CHAIN_FOUND_NO_POE ->
                    "řetěz certifikátů se nepodařilo ověřit — chybí kotva důvěry platná v době"
                    + " ověření, nebo revokační údaj k některému článku řetězu";
            case OUT_OF_BOUNDS_NO_POE, OUT_OF_BOUNDS_NOT_REVOKED ->
                    "chybí důkaz, že razítko existovalo za platnosti certifikátu TSA";
            case CRYPTO_CONSTRAINTS_FAILURE, CRYPTO_CONSTRAINTS_FAILURE_NO_POE ->
                    "použitý algoritmus se už nepovažuje za spolehlivý";
            case REVOKED, REVOKED_NO_POE, REVOKED_CA_NO_POE ->
                    "certifikát TSA byl odvolán";
            case HASH_FAILURE, SIG_CRYPTO_FAILURE ->
                    "otisk nebo podpis razítka nesouhlasí";
            case TRY_LATER -> "nepodařilo se získat revokační informace";
            default -> indication == Indication.FAILED ? "ověření selhalo" : "ověření není průkazné";
        };
        return detail + " (" + indication + (sub != null ? "/" + sub : "") + ")";
    }

    /**
     * Adds a full per-timestamp section to {@code body} showing every field
     * that DSS DiagnosticData exposes via TimestampWrapper.
     *
     * @param validationTime the point in time at which this timestamp's validity was assessed:
     *                       production time of the nearest later timestamp that passed, or current
     *                       time when no later timestamp did — see {@link #nextProofTime}.
     */
    private void buildTimestampSection(VBox body, TimestampWrapper ts, java.time.Instant validationTime) {
        TimestampType type = ts.getType();
        String typeStr = type != null ? type.name().replace("_", " ") : "?";

        // Sub-header showing type and production time
        Label subHdr = new Label("Typ: " + typeStr + "   |   " + fmtDate(ts.getProductionTime()));
        subHdr.setStyle("-fx-font-size:11px;-fx-font-weight:bold;-fx-text-fill:#003189;-fx-padding:4 0 2 0;");
        body.getChildren().add(subHdr);

        // Identifier
        body.getChildren().add(row("Identifikátor DSS", ts.getId()));

        // --- Celková platnost razítka ---
        body.getChildren().add(sectionHeader("Celková platnost razítka"));
        String tsOverall;
        if (!ts.isMessageImprintDataIntact()) {
            tsOverall = "Ne — otisk zprávy nesouhlasí";
        } else if (!ts.isSignatureIntact()) {
            tsOverall = "Ne — podpis razítka je technicky neplatný";
        } else if (!ts.isSignatureValid()) {
            tsOverall = "Ne — razítko neověřeno vůči certifikátu TSA";
        } else if (!ts.isTrustedChain()) {
            tsOverall = "Ne — řetěz TSA nedůvěryhodný";
        } else if (tsIndication(ts) != null && tsIndication(ts) != Indication.PASSED) {
            // Zjištěné skutečnosti výš sedí, a přesto DSS razítko neuznal. Tady se to pozná:
            // sunset kotvy, zastaralý algoritmus i chybějící důkaz existence jsou závěry
            // ověřovacího procesu, ne položky diagnostických dat. Dokud se sem nesahalo,
            // hlásila tahle záložka „Ano" i u razítek, která ostatní záložky odmítly.
            Indication indication = tsIndication(ts);
            tsOverall = (indication == Indication.FAILED ? "Ne — " : "Neznámá — ")
                    + subIndicationText(indication, currentSimple.getSubIndication(ts.getId()));
        } else if (!timestampStandardsMode) {
            CertificateWrapper expiredCert = findExpiredCertInTsChain(ts);
            if (expiredCert != null) {
                tsOverall = "Neznámá — certifikát " + fullName(expiredCert)
                        + " v řetězu TSA expiroval (platný do " + fmtDate(expiredCert.getNotAfter()) + ")";
            } else {
                tsOverall = "Ano";
            }
        } else {
            tsOverall = "Ano";
        }
        body.getChildren().add(row("Celková platnost", tsOverall));
        body.getChildren().add(row("Platnost ověřována k", fmtDate(java.util.Date.from(validationTime))));

        // --- Integrita ---
        body.getChildren().add(sectionHeader("Integrita razítka"));
        body.getChildren().add(row("Otisk zprávy v pořádku (message imprint)",
                ts.isMessageImprintDataIntact() ? "Ano — otisk souhlasí" : "Ne — otisk nesouhlasí"));
        body.getChildren().add(row("Podpis razítka technicky platný",
                ts.isSignatureIntact() ? "Ano" : "Ne"));
        body.getChildren().add(row("Razítko ověřeno vůči certifikátu TSA",
                ts.isSignatureValid() ? "Ano" : "Ne"));

        // --- Algoritmy ---
        body.getChildren().add(sectionHeader("Algoritmy"));
        body.getChildren().add(row("Šifrovací algoritmus", str(ts.getEncryptionAlgorithm())));
        body.getChildren().add(row("Hashovací algoritmus", str(ts.getDigestAlgorithm())));
        String tsKeyLen = ts.getKeyLengthUsedToSignThisToken();
        if (tsKeyLen != null && !tsKeyLen.isBlank())
            body.getChildren().add(row("Délka klíče (bitů)", tsKeyLen));

        // --- TSA certifikát ---
        CertificateWrapper tsaCert = ts.getSigningCertificate();
        if (tsaCert != null) {
            body.getChildren().add(certSectionHeader("Certifikát TSA (razítkující autorita)", tsaCert));
            body.getChildren().add(row("Celé jméno / CN", fullName(tsaCert)));
            addIfNotBlank(body, "Organizace", tsaCert.getOrganizationName());
            addIfNotBlank(body, "Org. jednotka", tsaCert.getOrganizationalUnit());
            addIfNotBlank(body, "Stát", tsaCert.getCountryName());
            body.getChildren().add(row("Předmět (Subject DN)", humanDn(tsaCert.getCertificateDN())));
            body.getChildren().add(row("Vydavatel (Issuer DN)", humanDn(tsaCert.getCertificateIssuerDN())));
            body.getChildren().add(row("Sériové číslo certifikátu", tsaCert.getSerialNumber()));
            addIfNotNull(body, "Platný od", tsaCert.getNotBefore());
            addIfNotNull(body, "Platný do",  tsaCert.getNotAfter());
            List<KeyUsageBit> ku = tsaCert.getKeyUsages();
            if (!ku.isEmpty())
                body.getChildren().add(row("Key Usage", ku.stream().map(Enum::name).collect(Collectors.joining(", "))));
            body.getChildren().add(row("Kvalifikovaný certifikát (QC)", boolLabel(tsaCert.isQcCompliance())));
            body.getChildren().add(row("Dostupná v důvěryhodném seznamu (TL)", boolLabel(tsaCert.isTrustedListReached())));
            body.getChildren().add(row("Řetěz důvěryhodný", boolLabel(ts.isTrustedChain())));
            if (!tsaCert.getQcTypes().isEmpty())
                body.getChildren().add(row("Typy QC", tsaCert.getQcTypes().stream()
                        .map(t -> t.getDescription()).collect(Collectors.joining(", "))));

            // TSP info
            List<TrustServiceWrapper> tsps = tsaCert.getTrustServices();
            if (!tsps.isEmpty()) {
                body.getChildren().add(sectionHeader("Důvěryhodná služba TSA (TSP)"));
                TrustServiceWrapper tsp = serviceToShow(tsaCert, tsps);
                String tspName = tsp.getTspNames().isEmpty() ? null : tsp.getTspNames().get(0);
                String svcName = tsp.getServiceNames().isEmpty() ? null : tsp.getServiceNames().get(0);
                addIfNotBlank(body, "Poskytovatel (TSP)", tspName);
                addIfNotBlank(body, "Název služby", svcName);
                addIfNotBlank(body, "Typ služby", tsp.getType());
                addIfNotBlank(body, "Stav služby", tsp.getStatus());
                if (tsps.size() > 1)
                    body.getChildren().add(row("Dalších TSP záznamů", String.valueOf(tsps.size() - 1)));
            }
        } else {
            body.getChildren().add(row("Certifikát TSA", "nenalezen"));
            body.getChildren().add(row("Řetěz důvěryhodný", boolLabel(ts.isTrustedChain())));
        }

        // --- Certifikační cesta TSA ---
        List<CertificateWrapper> tsChain = ts.getCertificateChain();
        if (!tsChain.isEmpty()) {
            body.getChildren().add(sectionHeader("Certifikační cesta TSA (" + tsChain.size() + " certifikátů)"));
            for (int i = 0; i < tsChain.size(); i++) {
                CertificateWrapper c = tsChain.get(i);
                String role = (i == 0) ? "TSA" : (i == tsChain.size() - 1 ? "Kořenový CA" : "Mezilehlý CA");
                body.getChildren().add(chainRow(i + 1, role, c));
            }
        }

        // --- Vložené validační materiály v razítku ---
        FoundCertificatesProxy fcTs = ts.foundCertificates();
        FoundRevocationsProxy  frTs = ts.foundRevocations();
        List<RelatedCertificateWrapper> tsCerts      = fcTs.getRelatedCertificates();
        List<OrphanCertificateWrapper>  tsOrphanCerts = fcTs.getOrphanCertificates();
        List<RelatedRevocationWrapper>  tsRevs        = frTs.getRelatedRevocationData();
        if (!tsCerts.isEmpty() || !tsOrphanCerts.isEmpty() || !tsRevs.isEmpty()) {
            body.getChildren().add(sectionHeader(
                    "Vložené validační materiály v razítku ("
                    + (tsCerts.size() + tsOrphanCerts.size()) + " cert., "
                    + tsRevs.size() + " rev. záznamů)"));
            for (CertificateOrigin origin : CertificateOrigin.values()) {
                List<RelatedCertificateWrapper> grp = fcTs.getRelatedCertificatesByOrigin(origin);
                if (grp.isEmpty()) continue;
                for (RelatedCertificateWrapper rc : grp) {
                    String detail = humanDn(rc.getCertificateDN())
                            + "  |  platný do " + fmtDate(rc.getNotAfter());
                    body.getChildren().add(certRow("Certifikát [" + certOriginLabel(origin) + "]",
                            detail, rc, anchoredByCustomStore(rc)));
                }
            }
            for (CertificateOrigin origin : CertificateOrigin.values()) {
                List<OrphanCertificateWrapper> grp = fcTs.getOrphanCertificatesByOrigin(origin);
                if (grp.isEmpty()) continue;
                for (OrphanCertificateWrapper oc : grp) {
                    String detail = humanDn(oc.getCertificateDN())
                            + "  |  platný do " + fmtDate(oc.getNotAfter());
                    body.getChildren().add(orphanCertRow("Certifikát [" + certOriginLabel(origin) + "]", detail, oc));
                }
            }
            for (RevocationOrigin origin : RevocationOrigin.values()) {
                List<RelatedRevocationWrapper> grp = frTs.getRelatedRevocationsByOrigin(origin);
                if (grp.isEmpty()) continue;
                String loc = revOriginLabel(origin);

                List<RelatedRevocationWrapper> crls  = grp.stream()
                        .filter(r -> r.getRevocationType() == RevocationType.CRL).toList();
                List<RelatedRevocationWrapper> ocsps = grp.stream()
                        .filter(r -> r.getRevocationType() == RevocationType.OCSP).toList();

                for (RelatedRevocationWrapper crl : crls) {
                    body.getChildren().add(crlRow("CRL [" + loc + "]", revDetail(crl, "CRL"), crl));
                }
                for (RelatedRevocationWrapper ocsp : ocsps) {
                    body.getChildren().add(ocspRow("OCSP [" + loc + "]", revDetail(ocsp, "OCSP"), ocsp));
                }
            }
        }

        // Online-fetched revocation for TSA chain (include signing cert itself — it may be absent from chain)
        List<CertificateWrapper> tsAllCerts = new java.util.ArrayList<>(ts.getCertificateChain());
        CertificateWrapper tsSigningCert = ts.getSigningCertificate();
        if (tsSigningCert != null && tsAllCerts.stream()
                .noneMatch(c -> c.getId().equals(tsSigningCert.getId()))) {
            tsAllCerts.add(0, tsSigningCert);
        }
        buildOnlineRevocationSection(body, tsAllCerts);
    }

    /** Opens a DER-encoded certificate in the OS certificate viewer. */
    /**
     * Certifikát se otevírá ve <b>vlastním</b> prohlížeči, ne v systémovém.
     *
     * <p>Systémový ukáže jinou sadu polí, než na kterou se ptá čtenář tohohle nástroje: neumí
     * kvalifikovaná prohlášení (QCStatements) ani údaje PSD2, tedy právě to, podle čeho
     * se pozná, s jým certifikátem má čtenář co do činění. Navíc odsud už není cesta zpátky:
     * soubor se odloží do dočasného adresáře a předá cizímu programu.
     */
    private static void openCertViewer(CertificateWrapper cert) {
        openCertViewer(cert != null ? cert.getBinaries() : null);
    }

    private static void showError(String msg) {
        javafx.application.Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Chyba");
            alert.setHeaderText(null);
            alert.setContentText(msg);
            alert.showAndWait();
        });
    }

    /** Row with label, value text and inline "Ukaž" link to open the certificate. */
    /**
     * @param pinnedAnchor zda je certifikát kotvou jen díky vlastnímu úložišti. Předává se, a
     *                     nezjišťuje se tady, protože obsah úložiště drží instance, kdežto tahle
     *                     metoda slouží i statickému dialogu, který žádnou nemá.
     */
    private static HBox certRow(String label, String detail, CertificateWrapper cert,
                                boolean pinnedAnchor) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-weight:bold;-fx-text-fill:#111111;-fx-font-size:12px;");
        lbl.setMinWidth(220);
        lbl.setMaxWidth(220);
        lbl.setWrapText(true);

        Text textNode = new Text(detail != null ? detail : "");
        textNode.setStyle("-fx-font-size:12px;-fx-fill:#111111;");

        Hyperlink ukazLink = new Hyperlink("Ukaž");
        ukazLink.setStyle("-fx-font-size:11px;-fx-text-fill:#0055cc;-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ukazLink.setOnAction(e -> { openCertViewer(cert); ukazLink.setVisited(false); });
        Hyperlink ulozLink = new Hyperlink("Ulož");
        ulozLink.setStyle("-fx-font-size:11px;-fx-text-fill:#0055cc;-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ulozLink.setOnAction(e -> { saveCertToFile(cert); ulozLink.setVisited(false); });

        TextFlow flow = new TextFlow(textNode);
        if (cert.isTrusted()) {
            flow.getChildren().addAll(new Text("  "), trustAnchorBadge(pinnedAnchor));
        }
        flow.getChildren().addAll(new Text("  "), ukazLink, new Text("  "), ulozLink);
        HBox.setHgrow(flow, Priority.ALWAYS);
        withCopyMenu(flow, detail);

        HBox box = new HBox(8, lbl, flow);
        box.setPadding(new Insets(1, 0, 1, 0));
        return box;
    }

    /** Row for an orphan certificate — in the document but not linked to any validation token. */
    private HBox orphanCertRow(String label, String detail, OrphanCertificateWrapper oc) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-weight:bold;-fx-text-fill:#111111;-fx-font-size:12px;");
        lbl.setMinWidth(220);
        lbl.setMaxWidth(220);
        lbl.setWrapText(true);

        byte[] der = oc.getBinaries();
        boolean noData = der == null || der.length == 0;
        Hyperlink ukazLink = new Hyperlink("Ukaž");
        ukazLink.setStyle("-fx-font-size:11px;-fx-text-fill:#0055cc;-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ukazLink.setDisable(noData);
        ukazLink.setOnAction(e -> { openCertViewer(der); ukazLink.setVisited(false); });
        Hyperlink ulozLink = new Hyperlink("Ulož");
        ulozLink.setStyle("-fx-font-size:11px;-fx-text-fill:#0055cc;-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ulozLink.setDisable(noData);
        ulozLink.setOnAction(e -> { saveCertToFile(der); ulozLink.setVisited(false); });

        Text textNode = new Text(detail != null ? detail : "");
        textNode.setStyle("-fx-font-size:12px;-fx-fill:#111111;");

        TextFlow flow = new TextFlow(ukazLink, new Text("  "), ulozLink, new Text("  "), textNode);
        HBox.setHgrow(flow, Priority.ALWAYS);
        withCopyMenu(flow, detail);

        HBox box = new HBox(8, lbl, flow);
        box.setPadding(new Insets(1, 0, 1, 0));
        return box;
    }

    private static void saveCertToFile(CertificateWrapper cert) {
        saveBytesToFile(cert != null ? cert.getBinaries() : null,
                "certificate.cer", "Certifikát (*.cer)", "*.cer");
    }

    private void saveCertToFile(byte[] der) {
        saveBytesToFile(der, "certificate.cer", "Certifikát (*.cer)", "*.cer");
    }

    /** Opens DER-encoded certificate bytes in the OS certificate viewer. */
    /** Totéž ze syrových bajtů — pro certifikát, který DSS nezná jako použitý. */
    private static void openCertViewer(byte[] der) {
        if (der == null || der.length == 0) {
            showError("Certifikát neobsahuje žádná data.");
            return;
        }
        ElementTextView.show("Certifikát",
                cz.digitalnikontinuita.anep.core.ContainerInspector.describeCertificate(der),
                der, "certifikat.cer", "Certifikát (*.cer)", "*.cer", null);
    }

    /** Builds the detail string for a CRL or OCSP row, including the covered certificate name. */
    private String revDetail(eu.europa.esig.dss.diagnostic.RevocationWrapper rev, String type) {
        return revDetail(rev, type, revCertNames.get(rev.getId()));
    }

    private String revDetail(eu.europa.esig.dss.diagnostic.RevocationWrapper rev, String type, String certName) {
        StringBuilder sb = new StringBuilder(type);
        CertificateWrapper sigCert = rev.getSigningCertificate();
        if (sigCert != null) {
            String cn = fullName(sigCert);
            if (cn != null && !cn.isBlank())
                sb.append("  |  ").append(cn);
        }
        sb.append("  |  thisUpdate: ").append(fmtDate(rev.getThisUpdate()));
        if (rev.getNextUpdate() != null)
            sb.append("  |  nextUpdate: ").append(fmtDate(rev.getNextUpdate()));
        if (rev.getRevocationType() == RevocationType.CRL) {
            String expDate = crlExpiredCertsDate(rev);
            if (expDate != null)
                sb.append("  |  expiredCertsOnCRL: ").append(expDate);
        }
        if (certName != null && !certName.isBlank())
            sb.append("  |  pro certifikát: ").append(certName);
        if (rev.getRevocationType() == RevocationType.OCSP) {
            String status = certStatusText(rev);
            if (status != null)
                sb.append("  |  stav certifikátu: ").append(status);
        }
        sb.append(sizeFragment(rev.getBinaries()));
        return sb.toString();
    }

    /**
     * Size of a revocation token, as a fragment of a detail line; empty when the bytes are not
     * available. Worth stating for every CRL and OCSP the tab lists, because size is what separates
     * the two in practice: an OCSP response is a couple of kilobytes about one certificate, while a
     * full CRL from a large CA runs to hundreds of kilobytes — and uchování copies whichever one it
     * used into the document, where the bytes stay for good.
     */
    private static String sizeFragment(byte[] der) {
        if (der == null || der.length == 0) return "";
        return "  |  velikost: "
                + cz.digitalnikontinuita.anep.core.ContainerInspector.formatSize(der.length);
    }

    /**
     * Returns the certificate status (V pořádku / Neznámý / Revokován) that DSS derived
     * from the given OCSP token, or {@code null} if no status could be determined.
     */
    private String certStatusText(eu.europa.esig.dss.diagnostic.RevocationWrapper rev) {
        CertificateRevocationWrapper crw = revStatusById.get(rev.getId());
        if (crw == null || crw.getStatus() == null) return null;
        return switch (crw.getStatus()) {
            case GOOD -> "V pořádku";
            case UNKNOWN -> "Neznámý";
            case REVOKED -> {
                StringBuilder sb = new StringBuilder("Revokován");
                if (crw.getRevocationDate() != null)
                    sb.append(" (").append(fmtDate(crw.getRevocationDate())).append(")");
                if (crw.getReason() != null)
                    sb.append(", důvod: ").append(crlReasonLabel(crw.getReason().getValue()));
                yield sb.toString();
            }
        };
    }

    /**
     * Prochází vložené OCSP odpovědi u všech podpisů (a jejich razítek) a hledá tu, která tvrdí
     * „neodvolán" a přišla od respondéra, jehož certifikát mezitím expiroval. Takové tvrzení je
     * pomíjivé: bez archivního razítka už není čím prokázat, že respondér byl v době odpovědi
     * důvěryhodný. Odpověď „odvolán" je proti tomu trvalý fakt a poznámku nedostává.
     *
     * <p>Poznámka ale nesmí z toho tvrzení dělat závěr o podpisu. Původně končila větou
     * „platnost podpisu je proto neznámá (INDETERMINATE)" — a na sbírce D:\tmp (242 souborů,
     * 115 vydaných poznámek) se to s verdiktem rozešlo ve 33 případech, protože o stavu
     * certifikátu nezřídka rozhodne úplně jiný revokační údaj. Tenhle příklad to ukazuje celý:
     * u „PAdES LT kvalifikovny viditelny 1.pdf" má vložená odpověď stav „nerevokován" od respondéra
     * expirovaného 3. 8. 2025, kdežto závěr nese OCSP odpověď stažená teď, podle níž je certifikát
     * odvolán — a podpis vychází jako TOTAL_PASSED, protože k odvolání došlo až po podpisu.
     *
     * <p>Verdikt se proto v textu neuvádí vůbec. Místo něj se čte, který údaj podle
     * {@link cz.digitalnikontinuita.anep.core.ReportMapper#usedRevocationIdByCertificate} závěr o daném
     * certifikátu nesl; poznámka pak mluví jen o materiálu a o tom, jestli se o něj závěr opřel.
     * Vázat text na verdikt by ostatně ani nešlo poctivě: tentýž vložený token bývá připojen
     * k několika podpisům naráz (slovník DSS je v dokumentu jeden) a bublina u řádku je jedna —
     * naměřeno 10 takových tokenů u podpisů s různým verdiktem.
     */
    private void enrichWithExpiredResponderReasons(DiagnosticData dd) {
        Date now = new Date();
        // Existence důkazu je vlastnost samotného údaje, ne podpisu: stačí, aby ho unesl kterýkoli
        // z podpisů, ke kterým je token připojen — proto se slučuje disjunkcí. Dvě fáze jsou tu
        // kvůli tomu, aby se text skládal až nad úplným obrázkem.
        java.util.Map<String, RelatedRevocationWrapper> tokens = new java.util.LinkedHashMap<>();
        java.util.Map<String, Boolean> archiveTsByToken = new java.util.HashMap<>();
        java.util.Map<String, Date> proofTimeByToken = new java.util.HashMap<>();
        for (SignatureWrapper sig : dd.getSignatures()) {
            collectExpiredResponderCerts(sig.foundRevocations().getRelatedRevocationData(),
                    sig, now, tokens, archiveTsByToken, proofTimeByToken);
            for (TimestampWrapper ts : sig.getTimestampList()) {
                collectExpiredResponderCerts(ts.foundRevocations().getRelatedRevocationData(),
                        sig, now, tokens, archiveTsByToken, proofTimeByToken);
            }
        }
        for (java.util.Map.Entry<String, RelatedRevocationWrapper> e : tokens.entrySet()) {
            revExpiredResponderNotes.put(e.getKey(), buildExpiredResponderNote(
                    e.getValue(), archiveTsByToken.get(e.getKey()),
                    proofTimeByToken.get(e.getKey())));
        }
    }

    /**
     * První fáze: které tokeny si poznámku zaslouží a jestli nad nimi stojí použitelný důkaz
     * existence — tedy archivní razítko vydané ještě za platnosti respondéra, ne jakékoli.
     */
    private void collectExpiredResponderCerts(
            List<RelatedRevocationWrapper> revocations, SignatureWrapper sig, Date now,
            java.util.Map<String, RelatedRevocationWrapper> tokens,
            java.util.Map<String, Boolean> archiveTsByToken,
            java.util.Map<String, Date> proofTimeByToken) {
        for (RelatedRevocationWrapper rrw : revocations) {
            if (rrw.getRevocationType() != RevocationType.OCSP) continue;
            // If DSS already rejected this revocation (S29 — respondent expired AT production time),
            // a red ⚠ from parseRevocationRejectionReasons is already shown; skip the amber note.
            if (revRejectionReasons.containsKey(rrw.getId())) continue;
            CertificateWrapper respCert = rrw.getSigningCertificate();
            if (respCert == null) continue;
            if (respCert.isTrusted()) continue;
            Date notAfter = respCert.getNotAfter();
            if (notAfter == null || !notAfter.before(now)) continue;
            // A REVOKED verdict is a permanent fact: DSS's LTV process takes the
            // revocation-time vs best-signature-time branch and never requires the respondent
            // cert to still be valid at validation time (no LTV_ISCKNR check applies). Only a
            // GOOD ("not revoked") verdict is perishable.
            CertificateRevocationWrapper crw = revStatusById.get(rrw.getId());
            if (crw != null && crw.getStatus()
                    == eu.europa.esig.dss.enumerations.CertificateStatus.REVOKED) continue;
            tokens.putIfAbsent(rrw.getId(), rrw);
            archiveTsByToken.merge(rrw.getId(),
                    cz.digitalnikontinuita.anep.core.ReportMapper
                            .revocationIssuerHasProofInValidity(rrw, sig),
                    (a, b) -> a || b);
            Date proof = cz.digitalnikontinuita.anep.core.ReportMapper
                    .revocationIssuerProofTime(rrw, sig);
            if (proof != null) {
                proofTimeByToken.merge(rrw.getId(), proof, (a, b) -> a.before(b) ? a : b);
            }
        }
    }

    /** Druhá fáze: nadpis i text podle toho, jestli se o tenhle údaj závěr opřel. */
    private RevocationNote buildExpiredResponderNote(
            RelatedRevocationWrapper rrw, boolean hasArchiveTs, Date proofTime) {
        CertificateWrapper respCert = rrw.getSigningCertificate();
        String certId = revToCertId.get(rrw.getId());
        String decidingId = certId != null ? usedRevocationByCert.get(certId) : null;
        boolean decisive = decidingId != null && decidingId.equals(rrw.getId());

        String coveredCert = revCertNames.getOrDefault(rrw.getId(), "v řetězci");
        String fact = "Certifikát respondéra OCSP (" + fullName(respCert) + ", platný do "
                + fmtDate(respCert.getNotAfter()) + ") od té doby expiroval.";

        // S archivním razítkem je důkaz existence po ruce, takže se o odpověď opřít lze a nadpis
        // se řídí jen tím, jestli právě ona rozhodla.
        if (hasArchiveTs) {
            return new RevocationNote(
                    decisive ? TITLE_USED
                             : (decidingId != null ? TITLE_OTHER_DECIDED : TITLE_PLAIN),
                    fact + " Archivní časové razítko prokazuje, že odpověď byla vydána v době "
                    + "platnosti respondéra — platnost podpisu tím není dotčena."
                    // Nadpis by jinak sliboval údaj, který se v textu nikde neobjeví.
                    + (decisive || decidingId == null ? ""
                       : " Stav certifikátu " + coveredCert + " ostatně určila "
                         + describeDecidingRevocation(decidingId) + "."));
        }
        // Bez archivního razítka nesmí nadpis tvrdit využití ani v jednom z případů níž: poznámka
        // se vydává právě tehdy, když respondér mezitím expiroval, a tehdy se o jeho „neodvolán"
        // závěr opřít nemůže. Dřív tu stálo „DSS tyto revokační informace použil" nad textem,
        // který o dvě věty dál vysvětloval, že prokázat je nelze — potkáno na „příklad
        // kvalifikovaného podpisu.pdf", kde tatáž odpověď vyvolala situaci 5 a verdikt
        // INDETERMINATE.
        if (decidingId != null && !decisive) {
            return new RevocationNote(TITLE_OTHER_DECIDED,
                    fact + " Na této odpovědi ale závěr nestojí: stav certifikátu " + coveredCert
                    + " určila " + describeDecidingRevocation(decidingId) + ".");
        }
        // Dokument archivní razítko mít může a přesto to nestačí — musí být vydáno ještě za
        // platnosti respondéra. Tvrdit v takovém případě „bez archivního časového razítka" by
        // čtenáře poslalo shánět něco, co už tam je.
        String why = proofTime == null
                ? " Nad touto odpovědí ale není archivní časové razítko (úroveň LTA), takže dnes"
                  + " už není čím prokázat, že respondér byl tehdy důvěryhodný."
                : " Archivní časové razítko, které ji kryje, ale přibylo až "
                  + fmtDate(proofTime) + " — tedy po skončení platnosti respondéra. Důkaz"
                  + " existence musí padnout do doby, kdy respondér ještě platil; pozdější"
                  + " neprokazuje nic.";
        // Opřel-li se o ni závěr, nadpis to musí říct. K rozhodnému okamžiku respondér ještě
        // platil, takže se o ni opřít šlo; co chybí, je trvanlivost — a ta patří do budoucího
        // času, ne do věty „opřít se nesmí". Jinak by tatáž odpověď byla v Analýze „využita"
        // a tady „nemohl se o ni opřít"; přesně tak si karta odporovala 13. 9. 2026.
        if (decisive) {
            return new RevocationNote(TITLE_USED,
                    fact + " V době vydání odpovědi ještě platil, takže samotná odpověď je"
                    + " v pořádku a závěr se o ni opřít mohl." + why
                    + " Pozdější ověřování, které by soudilo k dnešku, už se o ni opřít nedokáže;"
                    + " včasné archivní časové razítko by to bývalo odstranilo.");
        }
        return new RevocationNote(TITLE_NO_PROOF,
                fact + " V době vydání odpovědi ještě platil, takže samotná odpověď je v pořádku."
                + why + " O takovou odpověď se závěr opřít nesmí; včasné archivní časové razítko"
                + " by tuto pochybnost bývalo odstranilo.");
    }

    /**
     * Údaj, o který se závěr opřel, popsaný tak, jak ho pojmenovává „Využité revokační informace"
     * v Rozšířeném přehledu — druh v závorce, protože rod se u CRL seznamu a OCSP odpovědi liší.
     */
    private String describeDecidingRevocation(String revocationId) {
        CertificateRevocationWrapper rev = revStatusById.get(revocationId);
        if (rev == null) return "jiná revokační informace";
        String kind = rev.getRevocationType() == RevocationType.CRL ? "CRL seznam" : "OCSP odpověď";
        String origin = rev.isInternalRevocationOrigin() ? "vložená v dokumentu" : "stažená";
        String when = rev.getThisUpdate() != null ? " z " + fmtDate(rev.getThisUpdate()) : "";
        String status = certStatusText(rev);
        return origin + " revokační informace (" + kind + when + ")"
                + (status != null ? " se stavem „" + status + "“" : "");
    }

    /**
     * Removes the "thisUpdate outside cert validity" rejection reason from any CRL that carries
     * the expiredCertsOnCRL extension (OID 2.5.29.60). That extension is the exact RFC mechanism
     * allowing a CRL issued after a cert's expiry to still cover that cert; DSS may correctly use
     * the CRL for validation while the RAC constraint (BBB_XCV_REVOC_HAS_CERT_INFO_ANS) still
     * fires because it checks thisUpdate vs. notAfter without considering the extension.
     */
    private void suppressExpiredCertsOnCrlReasons(eu.europa.esig.dss.diagnostic.DiagnosticData dd) {
        if (dd == null) return;
        for (eu.europa.esig.dss.diagnostic.RevocationWrapper rev : dd.getAllRevocationData()) {
            if (rev.getRevocationType() != RevocationType.CRL) continue;
            if (crlExpiredCertsDate(rev) == null) continue;
            List<String> reasons = revRejectionReasons.get(rev.getId());
            if (reasons == null) continue;
            reasons.removeIf(r -> r.startsWith(
                    "Datum vydání revokačních dat (thisUpdate) leží mimo interval platnosti"));
            if (reasons.isEmpty()) revRejectionReasons.remove(rev.getId());
        }
    }

    /**
     * Parses the ETSI Detailed Report XML and collects, for every revocation token whose
     * {@code <RAC>} (Revocation Acceptance Checker) conclusion was not PASSED, the list of
     * Czech-translated reasons explaining why DSS did not accept the revocation data.
     * Reads individual {@code <Constraint>} failures so that {@code <AdditionalInfo>}
     * context (dates, ranges) can be appended to the translated text.
     */
    private java.util.Map<String, List<String>> parseRevocationRejectionReasons(String detailedReportXml) {
        java.util.Map<String, List<String>> map = new java.util.HashMap<>();
        if (detailedReportXml == null || detailedReportXml.isBlank()) return map;
        try {
            javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(
                    new org.xml.sax.InputSource(new java.io.StringReader(detailedReportXml)));
            org.w3c.dom.NodeList racNodes = doc.getElementsByTagNameNS("*", "RAC");
            for (int i = 0; i < racNodes.getLength(); i++) {
                org.w3c.dom.Element rac = (org.w3c.dom.Element) racNodes.item(i);
                String id = rac.getAttribute("Id");
                if (id == null || id.isBlank()) continue;

                org.w3c.dom.Element conclusion = firstChildElement(rac, "Conclusion");
                if (conclusion == null) continue;
                org.w3c.dom.Element indicationEl = firstChildElement(conclusion, "Indication");
                if ("PASSED".equals(indicationEl != null ? indicationEl.getTextContent() : null)) continue;

                List<String> reasons = map.computeIfAbsent(id, k -> new java.util.ArrayList<>());

                // Scan individual Constraint elements — they carry both the error Key and
                // AdditionalInfo (dates, validity ranges) not present in the Conclusion summary.
                org.w3c.dom.NodeList racChildren = rac.getChildNodes();
                for (int j = 0; j < racChildren.getLength(); j++) {
                    org.w3c.dom.Node cn = racChildren.item(j);
                    if (!(cn instanceof org.w3c.dom.Element constraint)
                            || !"Constraint".equals(constraint.getLocalName())) continue;
                    org.w3c.dom.Element statusEl = firstChildElement(constraint, "Status");
                    if (statusEl == null || "OK".equals(statusEl.getTextContent())
                            || "IGNORED".equals(statusEl.getTextContent())) continue;

                    String key = null;
                    String englishFallback = null;
                    org.w3c.dom.NodeList msgNodes = constraint.getChildNodes();
                    for (int k = 0; k < msgNodes.getLength(); k++) {
                        org.w3c.dom.Node mn = msgNodes.item(k);
                        if (!(mn instanceof org.w3c.dom.Element me)) continue;
                        String ln = me.getLocalName();
                        if ("Error".equals(ln) || "Warning".equals(ln)) {
                            key = me.getAttribute("Key");
                            englishFallback = me.getTextContent();
                            break;
                        }
                    }
                    if (key == null || key.isBlank()) continue;

                    org.w3c.dom.Element aiEl = firstChildElement(constraint, "AdditionalInfo");
                    String additionalInfo = aiEl != null ? aiEl.getTextContent().trim() : null;

                    String msg = translateRacKey(key, englishFallback, additionalInfo);
                    if (!reasons.contains(msg)) reasons.add(msg);
                }

                // Fallback: if no constraint-level reasons found, read Conclusion Errors/Warnings.
                if (reasons.isEmpty()) {
                    org.w3c.dom.NodeList cl = conclusion.getChildNodes();
                    for (int j = 0; j < cl.getLength(); j++) {
                        org.w3c.dom.Node n = cl.item(j);
                        if (!(n instanceof org.w3c.dom.Element el)) continue;
                        String tag = el.getLocalName();
                        if (!"Errors".equals(tag) && !"Warnings".equals(tag)) continue;
                        String msg = translateRacKey(el.getAttribute("Key"),
                                el.getTextContent().trim(), null);
                        if (!msg.isBlank() && !reasons.contains(msg)) reasons.add(msg);
                    }
                }
            }
        } catch (Exception e) {
            // Best-effort — leave (partial) map as-is if the XML can't be parsed.
        }
        return map;
    }

    /**
     * Translates a DSS RAC error/warning key to a Czech explanation.
     * Appends {@code additionalInfo} (dates, ranges from the XML) when available.
     * Falls back to {@code englishFallback} for keys without a known translation.
     */
    private static String translateRacKey(String key, String englishFallback, String additionalInfo) {
        String czech = switch (key == null ? "" : key) {
            case "BBB_XCV_REVOC_HAS_CERT_INFO_ANS" ->
                "Datum vydání revokačních dat (thisUpdate) leží mimo interval platnosti"
                + " certifikátu — data prokazují stav certifikátu v jinou dobu, než kdy certifikát platil.";
            case "BBB_XCV_REVOC_AFTER_CERT_NOT_BEFORE_ANS" ->
                "Revokační data byla vydána před začátkem platnosti certifikátu (před notBefore).";
            case "BBB_XCV_REVOC_ISSUER_VALID_AT_PROD_ANS" ->
                "Certifikát vydavatele revokačních dat nebyl platný v době jejich vydání.";
            case "BBB_XCV_REVOC_SELF_ISSUED_OCSP_ANS" ->
                "OCSP odpověď je self-issued — vydavatel je totožný se subjektem certifikátu.";
            case "BBB_XCV_REVOC_RESPID_MATCH_ANS" ->
                "ResponderId v OCSP odpovědi neodpovídá certifikátu vydavatele.";
            case "BBB_XCV_REVOC_THIS_UPDATE_PRESENT_ANS" ->
                "OCSP odpověď neobsahuje povinné pole thisUpdate.";
            case "BBB_XCV_REVOC_ISSUER_KNOWN_ANS" ->
                "Certifikát vydavatele revokačních dat nebyl nalezen.";
            case "BBB_XCV_ISCUKN_ANS" ->
                "Stav certifikátu je neznámý nebo je certifikát revokován.";
            case "BBB_XCV_CCCBB_ANS" ->
                "Řetězec certifikátů vydavatele revokačních dat nelze sestavit k důvěryhodné kotvě.";
            case "BBB_CV_ISIR_ANS" ->
                "Podpis revokačních dat je neplatný.";
            case "BBB_XCV_RAC_ANS" ->
                "Kontrola přijatelnosti revokačních dat nebyla průkazná.";
            case "BBB_XCV_IARDPFC_ANS" ->
                "Pro certifikát nebyla nalezena žádná přijatelná revokační data.";
            case "ACCM_POS_ANS" ->
                "Kryptografický algoritmus revokačních dat nesplňuje požadavky validační politiky.";
            default -> englishFallback != null ? englishFallback : key;
        };
        if (additionalInfo != null && !additionalInfo.isBlank()) {
            czech = czech + "\n  ↳ " + additionalInfo;
        }
        return czech;
    }

    private static org.w3c.dom.Element firstChildElement(org.w3c.dom.Element parent, String localName) {
        org.w3c.dom.NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            org.w3c.dom.Node n = nl.item(i);
            if (n instanceof org.w3c.dom.Element el && localName.equals(el.getLocalName())) return el;
        }
        return null;
    }

    /**
     * Both revocation annotations share one icon shape and one bubble layout, so the badge next
     * to a revocation row always reads the same way; only the colour carries the severity, and
     * the bubble title always states up front whether DSS used the data. Previously the two
     * cases used different glyphs (⚠ vs ⓘ), which made two notes about the same certificate
     * look like unrelated kinds of information.
     */
    private static final String REVOC_ICON_GLYPH = "ⓘ";

    /** Nadpisy žluté bubliny. Který z nich padne, rozhoduje {@link #buildExpiredResponderNote}. */
    private static final String TITLE_USED  = "DSS tyto revokační informace použil — poznámka:";
    private static final String TITLE_OTHER_DECIDED =
            "O stavu certifikátu rozhodla jiná revokační informace — poznámka:";
    private static final String TITLE_PLAIN = "Poznámka k těmto revokačním informacím:";
    private static final String TITLE_NO_PROOF =
            "DSS se o tyto revokační informace opřít nemohl — poznámka:";

    /** Red badge: DSS refused this revocation data — it played no part in the verdict. */
    private Label rejectionIcon(List<String> reasons) {
        return revocationNoteIcon("#cc0000", "#fff3f3",
                "DSS tyto revokační informace NEPOUŽIL. Důvody:", reasons);
    }

    /**
     * Amber badge: DSS did accept the revocation data (RAC = PASSED), but its respondent's
     * certificate has since expired — a forward-looking note about long-term archival,
     * not a rejection.
     */
    private Label expiredResponderIcon(RevocationNote note) {
        return revocationNoteIcon("#b36000", "#fffbe6", note.title(), List.of(note.text()));
    }

    private Label revocationNoteIcon(String color, String bgColor, String title, List<String> items) {
        Label icon = new Label(REVOC_ICON_GLYPH);
        icon.setStyle("-fx-text-fill:" + color + ";-fx-font-size:13px;-fx-font-weight:bold;-fx-cursor:hand;");
        icon.setOnMouseClicked(e -> showWarningBubble(icon, title, bgColor, color, items));
        return icon;
    }

    private void showWarningBubble(Label anchor, String title, String bgColor, String borderColor,
                                   List<String> items) {
        Popup popup = new Popup();
        popup.setAutoHide(true);

        VBox box = new VBox(4);
        box.setMaxWidth(420);
        box.setStyle(
                "-fx-background-color:" + bgColor + ";-fx-border-color:" + borderColor +
                ";-fx-border-width:1;-fx-border-radius:4;-fx-background-radius:4;-fx-padding:10;");

        Label titleLbl = new Label(title);
        titleLbl.setWrapText(true);
        titleLbl.setMaxWidth(400);
        titleLbl.setStyle("-fx-font-weight:bold;-fx-font-size:12px;-fx-text-fill:" + borderColor + ";");
        box.getChildren().add(titleLbl);

        for (String item : items) {
            Label l = new Label("•  " + item);
            l.setWrapText(true);
            l.setMaxWidth(400);
            l.setStyle("-fx-font-size:11px;-fx-text-fill:#333333;");
            box.getChildren().add(l);
        }

        popup.getContent().add(box);
        javafx.geometry.Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        popup.show(anchor, b.getMinX(), b.getMaxY() + 4);
    }

    /** Row with label, value text and inline "Ukaž" link to open the CRL. */
    private HBox crlRow(String label, String detail, eu.europa.esig.dss.diagnostic.RevocationWrapper crl) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-weight:bold;-fx-text-fill:#111111;-fx-font-size:12px;");
        lbl.setMinWidth(220);
        lbl.setMaxWidth(220);
        lbl.setWrapText(true);

        Text textNode = new Text(detail != null ? detail : "");
        textNode.setStyle("-fx-font-size:12px;-fx-fill:#111111;");

        Hyperlink ukazLink = new Hyperlink("Ukaž");
        ukazLink.setStyle("-fx-font-size:11px;-fx-text-fill:#007733;-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        BigInteger highlight = revToCertSerial.get(crl.getId());
        ukazLink.setOnAction(e -> { openCrlViewer(crl, highlight); ukazLink.setVisited(false); });
        Hyperlink ulozLink = new Hyperlink("Ulož");
        ulozLink.setStyle("-fx-font-size:11px;-fx-text-fill:#007733;-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ulozLink.setOnAction(e -> { saveCrlToFile(crl); ulozLink.setVisited(false); });

        TextFlow flow = new TextFlow(textNode);
        List<String> reasons = revRejectionReasons.get(crl.getId());
        if (reasons != null && !reasons.isEmpty()) {
            flow.getChildren().addAll(new Text("  "), rejectionIcon(reasons));
        }
        flow.getChildren().addAll(new Text("  "), ukazLink, new Text("  "), ulozLink);
        HBox.setHgrow(flow, Priority.ALWAYS);
        withCopyMenu(flow, detail);

        HBox box = new HBox(8, lbl, flow);
        box.setPadding(new Insets(1, 0, 1, 0));
        return box;
    }

    private Date crlExpiredCertsDateRaw(eu.europa.esig.dss.diagnostic.RevocationWrapper crl) {
        try {
            byte[] der = crl.getBinaries();
            if (der == null || der.length == 0) return null;
            org.bouncycastle.cert.X509CRLHolder holder = new org.bouncycastle.cert.X509CRLHolder(der);
            org.bouncycastle.asn1.x509.Extension ext = holder.getExtension(
                    new org.bouncycastle.asn1.ASN1ObjectIdentifier("2.5.29.60"));
            if (ext == null) return null;
            org.bouncycastle.asn1.ASN1GeneralizedTime gt =
                    org.bouncycastle.asn1.ASN1GeneralizedTime.getInstance(
                            org.bouncycastle.asn1.ASN1Primitive.fromByteArray(
                                    ext.getExtnValue().getOctets()));
            return gt.getDate();
        } catch (Exception e) {
            return null;
        }
    }

    private String crlExpiredCertsDate(eu.europa.esig.dss.diagnostic.RevocationWrapper crl) {
        Date d = crlExpiredCertsDateRaw(crl);
        return d != null ? fmtDate(d) : null;
    }

    private void saveCrlToFile(eu.europa.esig.dss.diagnostic.RevocationWrapper crl) {
        saveBytesToFile(crl.getBinaries(), "crl.crl", "CRL seznam (*.crl)", "*.crl");
    }

    private void saveOcspToFile(eu.europa.esig.dss.diagnostic.RevocationWrapper ocsp) {
        saveBytesToFile(ocsp.getBinaries(), "ocsp-response.ocsp", "OCSP odpověď (*.ocsp)", "*.ocsp");
    }

    private static void saveBytesToFile(byte[] data, String defaultName, String filterDesc, String filterExt) {
        if (data == null || data.length == 0) {
            showError("Soubor neobsahuje žádná data k uložení.");
            return;
        }
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Uložit soubor");
        fc.setInitialFileName(defaultName);
        fc.getExtensionFilters().addAll(
                new javafx.stage.FileChooser.ExtensionFilter(filterDesc, filterExt),
                new javafx.stage.FileChooser.ExtensionFilter("Všechny soubory (*.*)", "*.*"));
        java.io.File file = fc.showSaveDialog(null);
        if (file != null) {
            try {
                Files.write(file.toPath(), data);
            } catch (Exception ex) {
                showError("Nepodařilo se uložit soubor:\n" + ex.getMessage());
            }
        }
    }

    /**
     * Seznam CRL se otevírá ve <b>vlastním</b> prohlížeči, ne v systémovém: ten ukáže soubor,
     * kdežto čtenář se ptá na jeden z tisíců záznamů v něm. Vidí hlavičku seznamu i tabulku
     * odvolaných certifikátů a může seznam rovnou uložit.
     *
     * <p>Otevírá se na záznamu {@code highlight} (viz {@link #revToCertSerial}). Do 15. 10. 2026
     * se číslo nepředávalo a okno stálo na prvním řádku i nad seznamem, na kterém certifikát
     * revokovaný je; umělo to jen Analýza.
     */
    private static void openCrlViewer(eu.europa.esig.dss.diagnostic.RevocationWrapper crl,
                                      BigInteger highlight) {
        byte[] der = crl != null ? crl.getBinaries() : null;
        if (der == null || der.length == 0) return;
        eu.europa.esig.dss.diagnostic.CertificateWrapper issuer =
                crl.getSigningCertificate();
        CrlView.show(der, highlight, issuer != null ? issuer.getBinaries() : null, null);
    }

    /** Sériové číslo certifikátu z jeho bajtů, nebo {@code null}, když je nejde přečíst. */
    private static BigInteger certSerial(byte[] der) {
        if (der == null || der.length == 0) return null;
        try {
            return new org.bouncycastle.cert.X509CertificateHolder(der).getSerialNumber();
        } catch (Exception e) {
            return null;
        }
    }

    /** Row with label, value text and inline "Ukaž" link to show the OCSP dialog. */
    private HBox ocspRow(String label, String detail, eu.europa.esig.dss.diagnostic.RevocationWrapper ocsp) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-weight:bold;-fx-text-fill:#111111;-fx-font-size:12px;");
        lbl.setMinWidth(220);
        lbl.setMaxWidth(220);
        lbl.setWrapText(true);

        Text textNode = new Text(detail != null ? detail : "");
        textNode.setStyle("-fx-font-size:12px;-fx-fill:#111111;");

        Hyperlink ukazLink = new Hyperlink("Ukaž");
        ukazLink.setStyle("-fx-font-size:11px;-fx-text-fill:#cc5500;-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ukazLink.setOnAction(e -> { showOcspDialog(ocsp); ukazLink.setVisited(false); });
        Hyperlink ulozLink = new Hyperlink("Ulož");
        ulozLink.setStyle("-fx-font-size:11px;-fx-text-fill:#cc5500;-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ulozLink.setOnAction(e -> { saveOcspToFile(ocsp); ulozLink.setVisited(false); });

        TextFlow flow = new TextFlow(textNode);
        List<String> dssReasons = revRejectionReasons.get(ocsp.getId());
        if (dssReasons != null && !dssReasons.isEmpty()) {
            flow.getChildren().addAll(new Text("  "), rejectionIcon(dssReasons));
        }
        RevocationNote respNote = revExpiredResponderNotes.get(ocsp.getId());
        if (respNote != null) {
            flow.getChildren().addAll(new Text("  "), expiredResponderIcon(respNote));
        }
        flow.getChildren().addAll(new Text("  "), ukazLink, new Text("  "), ulozLink);
        HBox.setHgrow(flow, Priority.ALWAYS);
        withCopyMenu(flow, detail);

        HBox box = new HBox(8, lbl, flow);
        box.setPadding(new Insets(1, 0, 1, 0));
        return box;
    }

    private void showOcspDialog(eu.europa.esig.dss.diagnostic.RevocationWrapper ocsp) {
        showOcspDialogUnified(ocsp.getBinaries(), ocsp);
    }

    /**
     * Unified OCSP detail dialog used by both "Detailní rozbor" and "Protokoly DSS" tabs.
     * wrapper may be null when called from raw-bytes context and no match was found in DiagnosticData.
     */
    /**
     * Prohlížeč odpovědi OCSP — jeden pro celou aplikaci.
     *
     * <p>Volá ho i Analýza, kde se předtím otvíral chudší textový náhled bez bloku o podpisu
     * respondéra. Táž odpověď se má číst stejně, ať se na ni uživatel dostane odkudkoli; blok
     * o podpisu respondéra navíc odpovídá právě na otázku, kterou si nad revokačním údajem
     * klade Analýza — čpodepsal ji ten, kdo měl, a dá se mu věřit.
     *
     * <p>Statické, aby ho šlo zavolat bez instance {@code DetailedReportView}: dialog ani jeho
     * pomocníci se o stav té záložky neopírají, jen o předaná data.
     *
     * @param der     bajty odpovědi; bez nich se vypíše jen to, co ví DSS
     * @param wrapper údaj z DiagnosticData, ze kterého je blok o podpisu respondéra;
     *                {@code null} ten blok vynechá
     */
    static void showOcspDialogUnified(byte[] der, eu.europa.esig.dss.diagnostic.RevocationWrapper wrapper) {
        VBox content = new VBox(4);
        content.setPadding(new Insets(16));
        StringBuilder copyBuf = new StringBuilder();

        if (der != null && der.length > 0) {
            try {
                OCSPResp resp = new OCSPResp(der);
                addDialogRow(content, copyBuf, "Stav odpovědi", ocspRespStatusLabel(resp.getStatus()));

                Object obj = resp.getResponseObject();
                if (obj instanceof BasicOCSPResp basic) {
                    content.getChildren().add(dialogSectionHeader("Základní informace", copyBuf));
                    addDialogRow(content, copyBuf, "ID respondéra", ocspResponderIdFull(basic));
                    addDialogRow(content, copyBuf, "Vydáno (producedAt)", fmtDate(basic.getProducedAt()));
                    if (wrapper != null)
                        addDialogRow(content, copyBuf, "Zdroj (URL respondéra)", wrapper.getSourceAddress());
                    addDialogRow(content, copyBuf, "Algoritmus podpisu",
                            basic.getSignatureAlgOID() != null
                                    ? cmsOidLabel(basic.getSignatureAlgOID().getId()) : null);
                    if (basic.getCerts() != null && basic.getCerts().length > 0)
                        addDialogRow(content, copyBuf, "Certifikátů v odpovědi",
                                String.valueOf(basic.getCerts().length));

                    SingleResp[] responses = basic.getResponses();
                    content.getChildren().add(dialogSectionHeader(
                            "Stav certifikátů (" + responses.length + ")", copyBuf));
                    for (int i = 0; i < responses.length; i++) {
                        SingleResp sr = responses[i];
                        if (responses.length > 1) {
                            Label idx = new Label("Záznam č. " + (i + 1));
                            idx.setStyle("-fx-font-style:italic;-fx-text-fill:#555555;" +
                                    "-fx-font-size:11px;-fx-padding:4 0 0 0;");
                            content.getChildren().add(idx);
                        }
                        addDialogRow(content, copyBuf, "Sériové č. certifikátu",
                                cz.digitalnikontinuita.anep.core.ContainerInspector.hexNumber(
                                        sr.getCertID().getSerialNumber()));
                        addDialogRow(content, copyBuf, "Hash algoritmus (CertID)",
                                sr.getCertID().getHashAlgOID() != null
                                        ? cmsOidLabel(sr.getCertID().getHashAlgOID().getId()) : null);
                        Object certStatus = sr.getCertStatus();
                        if (certStatus == null) {
                            addDialogRow(content, copyBuf, "Stav certifikátu", "Dobrý (nerevokovaný)");
                        } else if (certStatus instanceof RevokedStatus rs) {
                            addDialogRow(content, copyBuf, "Stav certifikátu", "Revokovaný");
                            addDialogRow(content, copyBuf, "Datum revokace", fmtDate(rs.getRevocationTime()));
                            if (rs.hasRevocationReason())
                                addDialogRow(content, copyBuf, "Důvod revokace",
                                        crlReasonLabel(rs.getRevocationReason()));
                        } else if (certStatus instanceof UnknownStatus) {
                            addDialogRow(content, copyBuf, "Stav certifikátu", "Neznámý");
                        }
                        addDialogRow(content, copyBuf, "thisUpdate", fmtDate(sr.getThisUpdate()));
                        if (sr.getNextUpdate() != null)
                            addDialogRow(content, copyBuf, "nextUpdate", fmtDate(sr.getNextUpdate()));
                    }
                }
            } catch (Exception ex) {
                Label warn = new Label("Parsování OCSP odpovědi selhalo: " + ex.getMessage());
                warn.setStyle("-fx-text-fill:#cc0000;-fx-font-size:11px;");
                warn.setWrapText(true);
                content.getChildren().add(warn);
            }
        }

        if (wrapper != null) {
            content.getChildren().add(dialogSectionHeader("Podpis respondéra", copyBuf));
            addDialogRow(content, copyBuf, "Algoritmus (DSS)", str(wrapper.getSignatureAlgorithm()));
            addDialogRow(content, copyBuf, "Podpis neporušený", boolLabel(wrapper.isSignatureIntact()));
            addDialogRow(content, copyBuf, "Podpis validní",    boolLabel(wrapper.isSignatureValid()));
            addDialogRow(content, copyBuf, "Řetěz důvěryhodný", boolLabel(wrapper.isTrustedChain()));
            addDialogRow(content, copyBuf, "Otisk cert. (certHash)",
                    wrapper.isCertHashExtensionPresent()
                            ? "přítomen — " + (wrapper.isCertHashExtensionMatch() ? "shoda" : "NESHODA")
                            : "nepřítomen");
            CertificateWrapper respCert = wrapper.getSigningCertificate();
            if (respCert != null) {
                String respDetail = fullName(respCert) + "  |  " + humanDn(respCert.getCertificateDN());
                copyBuf.append("Certifikát respondéra\t").append(respDetail).append('\n');
                // Dialog je statický (volá ho i AdvisorView), takže se na vlastní úložiště nemá
                // koho zeptat. Odznak tu proto zůstává obecný; rozlišený je v řetězech, kde se
                // čtenář ptá, čím je prvek kryt.
                content.getChildren().add(certRow("Certifikát respondéra", respDetail, respCert, false));
            }
        }

        Button close = new Button("Zavřít");
        close.setDefaultButton(true);
        final String copyText = copyBuf.toString();
        Button copyAll = new Button("Kopírovat vše");
        copyAll.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(copyText);
            Clipboard.getSystemClipboard().setContent(cc);
        });
        // Uložit samotnou odpověď, ne jen její opis do schránky — týž požadavek jako u prohlížeče
        // seznamu CRL a certifikátů: co si uživatel prohlíží, má jít odsud i odnést.
        HBox btnRow = der != null && der.length > 0
                ? new HBox(8, saveResponse(der), copyAll, close)
                : new HBox(8, copyAll, close);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(12, 0, 0, 0));
        content.getChildren().add(btnRow);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color:transparent;-fx-background:transparent;");

        Stage stage = new Stage();
        stage.setTitle("OCSP odpověď");
        stage.setScene(new Scene(scroll, 720, 520));
        close.setOnAction(e -> stage.close());
        stage.show();
    }

    /** Tlačítko, které uloží prohlíženou odpověď OCSP tak, jak přišla. */
    private static Button saveResponse(byte[] der) {
        Button save = new Button("Uložit…");
        save.setOnAction(e -> saveBytesToFile(der, "ocsp-odpoved.ocsp",
                "OCSP odpověď (*.ocsp)", "*.ocsp"));
        return save;
    }

    private static HBox dialogSectionHeader(String text) {
        return dialogSectionHeader(text, null);
    }

    private static HBox dialogSectionHeader(String text, StringBuilder buf) {
        if (buf != null) buf.append('\n').append(text).append('\n');
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-size:11px;-fx-font-weight:bold;-fx-text-fill:" + SEC_FG + ";-fx-padding:8 0 2 0;");
        Separator sep = new Separator();
        VBox inner = new VBox(2, sep, lbl);
        HBox box = new HBox(inner);
        HBox.setHgrow(inner, Priority.ALWAYS);
        return box;
    }

    private static void addDialogRow(VBox parent, String label, String value) {
        addDialogRow(parent, null, label, value);
    }

    private static void addDialogRow(VBox parent, StringBuilder buf, String label, String value) {
        if (value == null || value.isBlank() || "—".equals(value) || "null".equals(value)) return;
        if (buf != null) buf.append(label).append('\t').append(value).append('\n');
        parent.getChildren().add(row(label, value));
    }

    private static String ocspRespStatusLabel(int status) {
        return switch (status) {
            case 0 -> "Úspěšná (successful)";
            case 1 -> "Chybný požadavek (malformedRequest)";
            case 2 -> "Vnitřní chyba (internalError)";
            case 3 -> "Zkuste později (tryLater)";
            case 5 -> "Vyžadován podpis (sigRequired)";
            case 6 -> "Neautorizováno (unauthorized)";
            default -> "Neznámý stav (" + status + ")";
        };
    }

    private static String crlReasonLabel(int reason) {
        return switch (reason) {
            case 0  -> "Neurčeno (unspecified)";
            case 1  -> "Kompromitace klíče (keyCompromise)";
            case 2  -> "Kompromitace CA (cACompromise)";
            case 3  -> "Změna příslušnosti (affiliationChanged)";
            case 4  -> "Nahrazen (superseded)";
            case 5  -> "Ukončena operace (cessationOfOperation)";
            case 6  -> "Pozastaven (certificateHold)";
            case 8  -> "Odblokován (removeFromCRL)";
            case 9  -> "Odvolána výjimka (privilegeWithdrawn)";
            case 10 -> "Kompromitace AA (aACompromise)";
            default -> "Kód " + reason;
        };
    }

    /**
     * Small pill-shaped label marking a certificate as a trust anchor.
     *
     * <p>Připnutá kotva má vlastní barvu a text. Do 29. 8. 2026 dostávaly obě tentýž zelený
     * odznak, takže se na téhle záložce nedalo poznat, že řetěz drží certifikát, který si do
     * aplikace vložil sám uživatel — a právě na téhle záložce se čtenář dívá, když se ptá,
     * čím je razítko kryté. Analýza to říká větou, ale ve sbaleném bloku o kus dál.
     */
    private static Label trustAnchorBadge(boolean pinned) {
        Label badge = new Label(pinned ? "kotva důvěry (vlastní úložiště)" : "kotva důvěry");
        badge.setStyle(
                "-fx-font-size:10px;-fx-font-weight:bold;-fx-text-fill:#ffffff;" +
                "-fx-background-color:" + (pinned ? "#7a4a00" : "#1a7a1a") + ";" +
                "-fx-background-radius:4;-fx-padding:1 6 1 6;");
        return badge;
    }

    /** Zda tenhle certifikát nese některý řetěz dokumentu jako kotva z vlastního úložiště. */
    private boolean anchoredByCustomStore(CertificateWrapper cert) {
        return cert != null && customAnchorCertIds.contains(cert.getId());
    }

    /**
     * Projde řetězy podpisů a časových razítek a vrátí id certifikátů, o které se důvěra opřela
     * proto, že je má uživatel ve vlastním úložišti.
     *
     * <p>Rozhoduje se po řetězech, ne po certifikátech — táž úvaha jako v
     * {@code ReportMapper.chainRestsOnCustomStore}. Když řetěz někde po cestě dosáhne na kotvu,
     * kterou dnes nese důvěryhodný seznam, skončí u ní a nic nad ní se ověření neúčastní; co je
     * v úložišti navíc, je pak jen ležící soubor. Teprve řetěz, který takovou kotvu nemá, drží
     * certifikát z úložiště — a jen ten dostane odznak.
     *
     * <p>„Být v seznamu uveden" přitom není totéž co „být jím dnes nesen": službě po sunsetu
     * seznam už neručí, takže řetěz na ní visící se o kotvu ze seznamu neopírá.
     */
    private java.util.Set<String> findCustomAnchors(DiagnosticData dd) {
        java.util.Set<String> digests = loadCustomStoreDigests();
        if (digests.isEmpty() || dd == null) return java.util.Set.of();
        List<List<CertificateWrapper>> chains = new java.util.ArrayList<>();
        for (SignatureWrapper sw : dd.getSignatures()) chains.add(sw.getCertificateChain());
        for (TimestampWrapper ts : dd.getTimestampList()) chains.add(ts.getCertificateChain());

        java.util.Date now = new java.util.Date();
        java.util.Set<String> out = new java.util.HashSet<>();
        for (List<CertificateWrapper> chain : chains) {
            if (chain == null || chain.isEmpty()) continue;
            boolean eutlCarriesIt = chain.stream().anyMatch(c -> c.isTrusted()
                    && c.isTrustedListReached()
                    && (c.getTrustSunsetDate() == null || c.getTrustSunsetDate().after(now)));
            if (eutlCarriesIt) continue;
            for (CertificateWrapper c : chain) {
                if (!c.isTrusted()) continue;
                String digest = derDigest(c.getBinaries());
                if (digest != null && digests.contains(digest)) {
                    out.add(c.getId());
                    break;
                }
            }
        }
        return out;
    }

    /** Otisky obsahu vlastního úložiště; prázdná množina, když je prázdné nebo nečitelné. */
    private static java.util.Set<String> loadCustomStoreDigests() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (CustomTrustStore.Entry entry : CustomTrustStore.listCertificates()) {
            try {
                String digest = derDigest(entry.certificate().getCertificate().getEncoded());
                if (digest != null) out.add(digest);
            } catch (Exception ignored) {
                // nečitelnou položku úložiště přeskoč — odznak je informace navíc, ne podmínka
            }
        }
        return out;
    }

    private static String derDigest(byte[] der) {
        if (der == null || der.length == 0) return null;
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(der));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Small pill-shaped label warning that a certificate is self-signed but was not
     * recognised as a (legitimate) trust anchor — i.e. the chain ends here unverified.
     */
    /**
     * Button offered next to every untrusted self-signed certificate, letting the user
     * one-click add it to {@link CustomTrustStore} — e.g. for an internal/company CA root
     * encountered while inspecting a signature, without needing to export and re-import it
     * manually via Nastavení → Vlastní důvěryhodné certifikáty.
     */
    private Hyperlink addToTrustButton(CertificateWrapper cert) {
        Hyperlink btn = new Hyperlink("Přidat do důvěryhodných");
        btn.setStyle("-fx-font-size:11px;-fx-text-fill:#1a7a1a;-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        btn.setOnAction(e -> {
            try {
                byte[] der = cert.getBinaries();
                if (der == null || der.length == 0) {
                    showError("Certifikát neobsahuje žádná data.");
                    return;
                }
                CustomTrustStore.addCertificateBytes(der);
                btn.setText("Přidáno");
                btn.setDisable(true);
            } catch (Exception ex) {
                showError("Certifikát se nepodařilo přidat do důvěryhodných:\n" + ex.getMessage());
            }
        });
        return btn;
    }

    private Label selfSignedBadge() {
        Label badge = new Label("certifikát s vlastním podpisem");
        badge.setStyle(
                "-fx-font-size:10px;-fx-font-weight:bold;-fx-text-fill:#ffffff;" +
                "-fx-background-color:#b00020;-fx-background-radius:4;" +
                "-fx-padding:1 6 1 6;");
        return badge;
    }

    /**
     * Section header with an inline "Ukaž" button (and optional trust-anchor badge)
     * that opens {@code cert} in the OS certificate viewer.
     */
    private HBox certSectionHeader(String text, CertificateWrapper cert) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-size:11px;-fx-font-weight:bold;-fx-text-fill:" + SEC_FG + ";");

        Hyperlink btn = new Hyperlink("Ukaž");
        btn.setStyle("-fx-font-size:11px;-fx-text-fill:#0055cc;-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        btn.setOnAction(e -> { openCertViewer(cert); btn.setVisited(false); });

        Hyperlink ulozBtn = new Hyperlink("Ulož");
        ulozBtn.setStyle("-fx-font-size:11px;-fx-text-fill:#0055cc;-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ulozBtn.setOnAction(e -> { saveCertToFile(cert); ulozBtn.setVisited(false); });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox box = new HBox(8, lbl, btn, ulozBtn);
        if (cert.isTrusted()) {
            box.getChildren().add(trustAnchorBadge(anchoredByCustomStore(cert)));
        } else if (cert.isSelfSigned()) {
            box.getChildren().add(selfSignedBadge());
            box.getChildren().add(addToTrustButton(cert));
        }
        box.getChildren().add(spacer);
        box.setPadding(new Insets(6, 0, 4, 0));
        box.setAlignment(Pos.CENTER_LEFT);
        box.setStyle("-fx-background-color:transparent;");
        return box;
    }

    private static void withCopyMenu(TextFlow flow, String text) {
        ContextMenu cm = new ContextMenu();
        MenuItem item = new MenuItem("Kopírovat text");
        item.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(text != null ? text : "");
            Clipboard.getSystemClipboard().setContent(cc);
        });
        cm.getItems().add(item);
        flow.setOnContextMenuRequested(e -> cm.show(flow, e.getScreenX(), e.getScreenY()));
    }

    private void addIfNotBlank(VBox parent, String label, String value) {
        if (value != null && !value.isBlank())
            parent.getChildren().add(row(label, value));
    }

    private void addIfNotNull(VBox parent, String label, Date value) {
        if (value != null)
            parent.getChildren().add(row(label, fmtDate(value)));
    }

    // -------------------------------------------------------------------------
    // DN decoding — RFC 4514 OID labels + ASN.1 hex value decoding
    // -------------------------------------------------------------------------

    private static final java.util.Map<String, String> OID_LABELS = new java.util.LinkedHashMap<>();
    static {
        // Standard X.500 attribute types (2.5.4.x)
        OID_LABELS.put("2.5.4.3",  "CN");
        OID_LABELS.put("2.5.4.4",  "Příjmení");
        OID_LABELS.put("2.5.4.5",  "serialNumber");
        OID_LABELS.put("2.5.4.6",  "C");
        OID_LABELS.put("2.5.4.7",  "L");
        OID_LABELS.put("2.5.4.8",  "ST");
        OID_LABELS.put("2.5.4.9",  "Ulice");
        OID_LABELS.put("2.5.4.10", "O");
        OID_LABELS.put("2.5.4.11", "OU");
        OID_LABELS.put("2.5.4.12", "Titul");
        OID_LABELS.put("2.5.4.15", "Kategorie podnikání");
        OID_LABELS.put("2.5.4.16", "Adresa");
        OID_LABELS.put("2.5.4.17", "PSČ");
        OID_LABELS.put("2.5.4.42", "Jméno");
        OID_LABELS.put("2.5.4.43", "Iniciály");
        OID_LABELS.put("2.5.4.44", "Generační přívlastek");
        OID_LABELS.put("2.5.4.45", "X500 UID");
        OID_LABELS.put("2.5.4.46", "DN Qualifier");
        OID_LABELS.put("2.5.4.65", "Pseudonym");
        OID_LABELS.put("2.5.4.97", "OrgId");
        // PKCS#9
        OID_LABELS.put("1.2.840.113549.1.9.1", "E");
        OID_LABELS.put("1.2.840.113549.1.9.2", "Nestrukturovaný název");
        // LDAP / Domain Component
        OID_LABELS.put("0.9.2342.19200300.100.1.1",  "UID");
        OID_LABELS.put("0.9.2342.19200300.100.1.25", "DC");
        // EV Certificate jurisdiction fields (Microsoft/CA/Browser Forum)
        OID_LABELS.put("1.3.6.1.4.1.311.60.2.1.1", "Jurisdikce – lokalita");
        OID_LABELS.put("1.3.6.1.4.1.311.60.2.1.2", "Jurisdikce – kraj");
        OID_LABELS.put("1.3.6.1.4.1.311.60.2.1.3", "Jurisdikce – stát");
        // Russian GOST fields (common in cross-border EU certs)
        OID_LABELS.put("1.2.643.3.131.1.1", "INN");
        OID_LABELS.put("1.2.643.100.1",     "OGRN");
        OID_LABELS.put("1.2.643.100.3",     "SNILS");
    }

    /** Decode a raw DN string: replace OID notation with friendly labels and
     *  decode #hexvalue ASN.1 encoded attribute values. */
    private static String humanDn(String rawDn) {
        if (rawDn == null || rawDn.isBlank()) return rawDn;
        // Step 1: decode OID=#hex patterns
        java.util.regex.Matcher m1 = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)*)=#([0-9A-Fa-f]+)")
                .matcher(rawDn);
        StringBuffer sb1 = new StringBuffer();
        while (m1.find()) {
            String oid   = m1.group(1);
            String hex   = m1.group(2);
            String label = OID_LABELS.getOrDefault(oid, oid);
            String val   = decodeAsn1Hex(hex);
            m1.appendReplacement(sb1, java.util.regex.Matcher.quoteReplacement(label + "=" + val));
        }
        m1.appendTail(sb1);
        String step1 = sb1.toString();

        // Step 2: replace remaining bare OIDs (OID=value where OID not yet replaced)
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)*)=")
                .matcher(step1);
        StringBuffer sb2 = new StringBuffer();
        while (m2.find()) {
            String oid   = m2.group(1);
            String label = OID_LABELS.getOrDefault(oid, oid);
            m2.appendReplacement(sb2, java.util.regex.Matcher.quoteReplacement(label + "="));
        }
        m2.appendTail(sb2);
        return sb2.toString();
    }

    /** Decode an ASN.1 DER-encoded primitive from a hex string (tag + length + value). */
    private static String decodeAsn1Hex(String hex) {
        try {
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++)
                bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            int tag = bytes[0] & 0xFF;
            // Length: simplified — handle short form (< 128) and one-byte long form
            int lenByte = bytes[1] & 0xFF;
            int valueOffset;
            int valueLen;
            if (lenByte < 0x80) {
                valueOffset = 2;
                valueLen = lenByte;
            } else {
                int numBytes = lenByte & 0x7F;
                valueLen = 0;
                for (int i = 0; i < numBytes; i++)
                    valueLen = (valueLen << 8) | (bytes[2 + i] & 0xFF);
                valueOffset = 2 + numBytes;
            }
            byte[] value = java.util.Arrays.copyOfRange(bytes, valueOffset, valueOffset + valueLen);
            return switch (tag) {
                case 0x0C -> new String(value, java.nio.charset.StandardCharsets.UTF_8);   // UTF8String
                case 0x1E -> new String(value, java.nio.charset.StandardCharsets.UTF_16BE); // BMPString
                case 0x16 -> new String(value, java.nio.charset.StandardCharsets.US_ASCII); // IA5String
                default   -> new String(value, java.nio.charset.StandardCharsets.ISO_8859_1); // PrintableString, TeletexString…
            };
        } catch (Exception e) {
            return "#" + hex; // fallback: return original
        }
    }

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    private static String str(Object o) {
        return o == null ? "—" : o.toString();
    }

    private static String fmtDate(Date d) {
        return TimeUtil.format(d);
    }

    private String abbreviate(String s, int max) {
        if (s == null) return "?";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String boolLabel(boolean v) {
        return v ? "Ano" : "Ne";
    }

    /** Zámkový slovník tak, jak ho DSS načetl — syrově, včetně toho, co v něm chybí. */
    private static String lockLabel(
            eu.europa.esig.dss.diagnostic.jaxb.XmlPDFLockDictionary lock) {
        if (lock == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("Action: ").append(lock.getAction() != null ? lock.getAction().name() : "—");
        if (lock.getFields() != null && !lock.getFields().isEmpty()) {
            sb.append("   Fields: ").append(String.join(", ", lock.getFields()));
        }
        // /P se v DSS objevuje jen u SigFieldLock. Acrobat je píše i do TransformParams pod
        // FieldMDP, ale ISO 32000 je tam nemá a DSS je odtud nečte — u FieldMDP tedy „bez /P“
        // neznamená, že v souboru není.
        sb.append("   P: ").append(lock.getPermissions() != null
                ? lock.getPermissions().name() : "bez /P");
        return sb.toString();
    }

    private String boolLabelInverse(boolean v) {
        return v ? "Ano — dokument byl po podpisu změněn" : "Ne — dokument nebyl po podpisu změněn";
    }

    private String sigAlg(SignatureWrapper sig) {
        String enc    = str(sig.getEncryptionAlgorithm());
        String digest = str(sig.getDigestAlgorithm());
        String keyLen = sig.getKeyLengthUsedToSignThisToken();
        return enc + (keyLen != null && !keyLen.isBlank() ? "-" + keyLen : "") + " / " + digest;
    }

    private String keyDesc(CertificateWrapper cert) {
        // Key length is on token (AbstractTokenProxy), not directly on CertificateWrapper.
        // Use signing certificate reference key size if available.
        return str(cert.getEncryptionAlgorithm());
    }

    private static String fullName(CertificateWrapper c) {
        String cn = c.getCommonName();
        if (cn != null && !cn.isBlank()) return cn;
        // DSS may return null for fields encoded with non-standard OIDs; extract from decoded DN
        String given = c.getGivenName();
        String sur   = c.getSurname();
        if (given == null || given.isBlank()) given = dnField(c.getCertificateDN(), "2.5.4.42");
        if (sur   == null || sur.isBlank())   sur   = dnField(c.getCertificateDN(), "2.5.4.4");
        if (given != null || sur != null)
            return (given != null ? given : "") + (sur != null ? " " + sur : "");
        return c.getReadableCertificateName();
    }

    /** Extract a decoded value for a specific OID from a raw DN string. */
    private static String dnField(String rawDn, String oid) {
        if (rawDn == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:^|,)\\s*" + java.util.regex.Pattern.quote(oid) + "=#([0-9A-Fa-f]+)")
                .matcher(rawDn);
        if (m.find()) return decodeAsn1Hex(m.group(1));
        // also try unencoded form: OID=value
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("(?:^|,)\\s*" + java.util.regex.Pattern.quote(oid) + "=([^,]+)")
                .matcher(rawDn);
        if (m2.find()) return m2.group(1).trim();
        return null;
    }

    private String sigLocation(SignatureWrapper sig) {
        List<String> parts = new java.util.ArrayList<>();
        if (sig.getStreetAddress() != null && !sig.getStreetAddress().isBlank()) parts.add(sig.getStreetAddress());
        if (sig.getCity()          != null && !sig.getCity().isBlank())          parts.add(sig.getCity());
        if (sig.getStateOrProvince()!= null && !sig.getStateOrProvince().isBlank()) parts.add(sig.getStateOrProvince());
        if (sig.getCountryName()   != null && !sig.getCountryName().isBlank())   parts.add(sig.getCountryName());
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private String pageList(List<BigInteger> pages) {
        return pages.stream().map(BigInteger::toString).collect(Collectors.joining(", "));
    }

    // =========================================================================
    // PDF internal structure — DSS/VRI dictionary + CMS SignedData
    // =========================================================================

    /**
     * Appends a card showing the raw PDF internal structure:
     *  - /DSS dictionary (global cert/CRL/OCSP pool)
     *  - /VRI dictionary entries (per-signature validation data)
     *  - CMS SignedData for each signature: signed vs. unsigned attributes
     */
    private void appendPdfStructureCard(java.io.File pdfFile) {
        VBox card = new VBox();
        card.setStyle(CARD_BORDER);
        card.getChildren().add(cardHeader("PDF — interní struktura (DSS/VRI, CMS podpisy)"));

        VBox body = new VBox(0);
        body.setPadding(new Insets(10));

        try (org.apache.pdfbox.pdmodel.PDDocument pdf =
                (currentPdfPassword != null && currentPdfPassword.length > 0)
                ? org.apache.pdfbox.Loader.loadPDF(pdfFile, new String(currentPdfPassword))
                : org.apache.pdfbox.Loader.loadPDF(pdfFile)) {

            appendDssVri(body, pdf);

            java.util.List<org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature> sigs =
                    pdf.getSignatureDictionaries();
            if (!sigs.isEmpty()) {
                body.getChildren().add(sectionHeader("CMS struktura podpisů (" + sigs.size() + ")"));
                for (org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature sig : sigs) {
                    body.getChildren().add(new Separator());
                    if ("adbe.x509.rsa_sha1".equals(sig.getSubFilter())) {
                        appendPkcs1Signature(body, sig, pdfFile);
                    } else {
                        appendCmsSignature(body, sig);
                    }
                }
            }
        } catch (Exception e) {
            body.getChildren().add(row("Chyba při čtení struktury PDF",
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        card.getChildren().add(body);
        cardsBox.getChildren().add(card);
    }

    private void appendDssVri(VBox body, org.apache.pdfbox.pdmodel.PDDocument pdf) {
        org.apache.pdfbox.cos.COSBase dssBase = pdf.getDocumentCatalog()
                .getCOSObject().getDictionaryObject(org.apache.pdfbox.cos.COSName.getPDFName("DSS"));

        body.getChildren().add(sectionHeader("DSS slovník — globální validační data"));

        if (!(dssBase instanceof org.apache.pdfbox.cos.COSDictionary dss)) {
            body.getChildren().add(row("Přítomnost",
                    "Není přítomen — dokument nemá LT-level validační data v PDF"));
            return;
        }

        // ---------- Global cert/CRL/OCSP pools ----------
        appendDssCertItems(body,
                cosArrayOf(dss, "Certs"), "Certifikáty (/DSS/Certs)");
        appendDssCrlItems(body,
                cosArrayOf(dss, "CRLs"),  "CRL seznamy (/DSS/CRLs)");
        appendDssOcspItems(body,
                cosArrayOf(dss, "OCSPs"), "OCSP odpovědi (/DSS/OCSPs)");

        // ---------- VRI entries ----------
        org.apache.pdfbox.cos.COSDictionary vri = (org.apache.pdfbox.cos.COSDictionary)
                dss.getDictionaryObject(org.apache.pdfbox.cos.COSName.getPDFName("VRI"));

        if (vri == null || vri.keySet().isEmpty()) {
            body.getChildren().add(sectionHeader("VRI záznamy (per-signature validační data)"));
            body.getChildren().add(row("Přítomnost", "Žádné VRI záznamy — PAdES LT struktura chybí"));
            return;
        }

        body.getChildren().add(sectionHeader(
                "VRI záznamy — per-signature validační data  (" + vri.keySet().size() + " podpisů)"));

        for (org.apache.pdfbox.cos.COSName key : vri.keySet()) {
            org.apache.pdfbox.cos.COSBase entryBase = vri.getDictionaryObject(key);
            if (!(entryBase instanceof org.apache.pdfbox.cos.COSDictionary entry)) continue;

            String k = key.getName();
            Label vriHdr = new Label("VRI[" + k + "]");
            vriHdr.setStyle(
                    "-fx-font-size:11px;-fx-font-weight:bold;-fx-text-fill:#444444;" +
                    "-fx-padding:6 0 2 8;");
            body.getChildren().add(vriHdr);

            appendDssCertItems(body, cosArrayOf(entry, "Cert"), "  Certifikáty (/Cert)");
            appendDssCrlItems(body,  cosArrayOf(entry, "CRL"),  "  CRL seznamy (/CRL)");
            appendDssOcspItems(body, cosArrayOf(entry, "OCSP"), "  OCSP odpovědi (/OCSP)");

            boolean hasTs = entry.getDictionaryObject(
                    org.apache.pdfbox.cos.COSName.getPDFName("TS")) != null;
            boolean hasTu = entry.getDictionaryObject(
                    org.apache.pdfbox.cos.COSName.getPDFName("TU")) != null;
            if (hasTs) body.getChildren().add(plainAttrNote("  TS (timestamp) přítomno"));
            if (hasTu) body.getChildren().add(plainAttrNote("  TU (timestamp update) přítomno"));
        }
    }

    private org.apache.pdfbox.cos.COSArray cosArrayOf(
            org.apache.pdfbox.cos.COSDictionary dict, String key) {
        org.apache.pdfbox.cos.COSBase obj = dict.getDictionaryObject(
                org.apache.pdfbox.cos.COSName.getPDFName(key));
        return (obj instanceof org.apache.pdfbox.cos.COSArray arr) ? arr : null;
    }

    private void appendDssCertItems(VBox body,
            org.apache.pdfbox.cos.COSArray arr, String heading) {
        int count = arr != null ? arr.size() : 0;
        body.getChildren().add(sectionHeader(heading + " (" + count + ")"));
        if (count == 0) { body.getChildren().add(plainAttrNote("  (žádné)")); return; }
        for (int i = 0; i < arr.size(); i++) {
            byte[] der = readCosStreamBytes(arr.getObject(i));
            if (der == null) continue;
            body.getChildren().add(dssRawCertRow(i + 1, der));
        }
    }

    private void appendDssCrlItems(VBox body,
            org.apache.pdfbox.cos.COSArray arr, String heading) {
        int count = arr != null ? arr.size() : 0;
        body.getChildren().add(sectionHeader(heading + " (" + count + ")"));
        if (count == 0) { body.getChildren().add(plainAttrNote("  (žádné)")); return; }
        for (int i = 0; i < arr.size(); i++) {
            byte[] der = readCosStreamBytes(arr.getObject(i));
            if (der == null) continue;
            body.getChildren().add(dssRawCrlRow(i + 1, der));
        }
    }

    private void appendDssOcspItems(VBox body,
            org.apache.pdfbox.cos.COSArray arr, String heading) {
        int count = arr != null ? arr.size() : 0;
        body.getChildren().add(sectionHeader(heading + " (" + count + ")"));
        if (count == 0) { body.getChildren().add(plainAttrNote("  (žádné)")); return; }
        for (int i = 0; i < arr.size(); i++) {
            byte[] der = readCosStreamBytes(arr.getObject(i));
            if (der == null) continue;
            body.getChildren().add(dssRawOcspRow(i + 1, der));
        }
    }

    /** Reads decompressed bytes from a PDFBox COSStream (resolves indirect references). */
    private byte[] readCosStreamBytes(org.apache.pdfbox.cos.COSBase obj) {
        if (!(obj instanceof org.apache.pdfbox.cos.COSStream stream)) return null;
        try (java.io.InputStream is = stream.createInputStream()) {
            return is.readAllBytes();
        } catch (Exception e) { return null; }
    }

    // ---------- DSS raw-bytes row builders ----------

    private HBox dssRawCertRow(int idx, byte[] der) {
        String detail = parseDssCertDetail(der);
        Label lbl = new Label("Certifikát " + idx);
        lbl.setStyle("-fx-font-weight:bold;-fx-text-fill:#111111;-fx-font-size:12px;");
        lbl.setMinWidth(130);
        lbl.setMaxWidth(130);

        Text textNode = new Text(detail);
        textNode.setStyle("-fx-font-size:12px;-fx-fill:#111111;");

        Hyperlink ukazLink = new Hyperlink("Ukaž");
        ukazLink.setStyle("-fx-font-size:11px;-fx-text-fill:#0055cc;" +
                "-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ukazLink.setOnAction(e -> { openCertViewer(der); ukazLink.setVisited(false); });
        Hyperlink ulozLink = new Hyperlink("Ulož");
        ulozLink.setStyle("-fx-font-size:11px;-fx-text-fill:#0055cc;" +
                "-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ulozLink.setOnAction(e -> { saveCertToFile(der); ulozLink.setVisited(false); });

        TextFlow flow = new TextFlow(textNode, new Text("  "), ukazLink, new Text("  "), ulozLink);
        HBox.setHgrow(flow, Priority.ALWAYS);
        withCopyMenu(flow, detail);

        HBox box = new HBox(8, lbl, flow);
        box.setPadding(new Insets(1, 0, 1, 8));
        return box;
    }

    private HBox dssRawCrlRow(int idx, byte[] der) {
        // Appended here rather than inside the parser so that an entry which fails to parse still
        // reports how big it is — with nothing else to go on, that is the only handle on it.
        String detail = parseDssCrlDetail(der) + sizeFragment(der);
        Label lbl = new Label("CRL " + idx);
        lbl.setStyle("-fx-font-weight:bold;-fx-text-fill:#111111;-fx-font-size:12px;");
        lbl.setMinWidth(130);
        lbl.setMaxWidth(130);

        Text textNode = new Text(detail);
        textNode.setStyle("-fx-font-size:12px;-fx-fill:#111111;");

        Hyperlink ukazLink = new Hyperlink("Ukaž");
        ukazLink.setStyle("-fx-font-size:11px;-fx-text-fill:#007733;" +
                "-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ukazLink.setOnAction(e -> { openRawCrlViewer(der); ukazLink.setVisited(false); });
        Hyperlink ulozLink = new Hyperlink("Ulož");
        ulozLink.setStyle("-fx-font-size:11px;-fx-text-fill:#007733;" +
                "-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ulozLink.setOnAction(e -> saveBytesToFile(der, "crl.crl", "CRL seznam (*.crl)", "*.crl"));

        TextFlow flow = new TextFlow(textNode, new Text("  "), ukazLink, new Text("  "), ulozLink);
        HBox.setHgrow(flow, Priority.ALWAYS);
        withCopyMenu(flow, detail);

        HBox box = new HBox(8, lbl, flow);
        box.setPadding(new Insets(1, 0, 1, 8));
        return box;
    }

    private HBox dssRawOcspRow(int idx, byte[] der) {
        String detail = parseDssOcspDetail(der) + sizeFragment(der);
        Label lbl = new Label("OCSP " + idx);
        lbl.setStyle("-fx-font-weight:bold;-fx-text-fill:#111111;-fx-font-size:12px;");
        lbl.setMinWidth(130);
        lbl.setMaxWidth(130);

        Text textNode = new Text(detail);
        textNode.setStyle("-fx-font-size:12px;-fx-fill:#111111;");

        Hyperlink ukazLink = new Hyperlink("Ukaž");
        ukazLink.setStyle("-fx-font-size:11px;-fx-text-fill:#cc5500;" +
                "-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ukazLink.setOnAction(e -> { showRawOcspDialog(der); ukazLink.setVisited(false); });
        Hyperlink ulozLink = new Hyperlink("Ulož");
        ulozLink.setStyle("-fx-font-size:11px;-fx-text-fill:#cc5500;" +
                "-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ulozLink.setOnAction(e -> saveBytesToFile(
                der, "ocsp-response.ocsp", "OCSP odpověď (*.ocsp)", "*.ocsp"));

        TextFlow flow = new TextFlow(textNode, new Text("  "), ukazLink, new Text("  "), ulozLink);
        HBox.setHgrow(flow, Priority.ALWAYS);
        withCopyMenu(flow, detail);

        HBox box = new HBox(8, lbl, flow);
        box.setPadding(new Insets(1, 0, 1, 8));
        return box;
    }

    // ---------- Detail-string parsers for DSS items ----------

    private String parseDssCertDetail(byte[] der) {
        try {
            org.bouncycastle.cert.X509CertificateHolder h =
                    new org.bouncycastle.cert.X509CertificateHolder(der);
            String subject = humanDn(h.getSubject().toString());
            String issuer  = humanDn(h.getIssuer().toString());
            String notAfter = fmtDate(h.getNotAfter());
            String s = subject + "  |  platný do " + notAfter;
            // Add "vydal: ..." only when issuer differs from subject (non-self-signed)
            if (!h.getSubject().equals(h.getIssuer()))
                s += "  |  vydal: " + issuer;
            return s;
        } catch (Exception e) {
            return "(neparsovatelné: " + e.getMessage() + ")";
        }
    }

    private String parseDssCrlDetail(byte[] der) {
        try {
            org.bouncycastle.cert.X509CRLHolder h = new org.bouncycastle.cert.X509CRLHolder(der);
            String issuer = humanDn(h.getIssuer().toString());
            String thisUpdate = fmtDate(h.getThisUpdate());
            String s = "Vydavatel: " + issuer + "  |  thisUpdate: " + thisUpdate;
            if (h.getNextUpdate() != null)
                s += "  |  nextUpdate: " + fmtDate(h.getNextUpdate());
            return s;
        } catch (Exception e) {
            return "(neparsovatelné: " + e.getMessage() + ")";
        }
    }

    private String parseDssOcspDetail(byte[] der) {
        try {
            org.bouncycastle.cert.ocsp.OCSPResp resp = new org.bouncycastle.cert.ocsp.OCSPResp(der);
            Object obj = resp.getResponseObject();
            if (obj instanceof org.bouncycastle.cert.ocsp.BasicOCSPResp basic) {
                String id = ocspResponderIdCn(basic);
                Date producedAt = basic.getProducedAt();
                int n = basic.getResponses().length;
                return "producedAt: " + fmtDate(producedAt)
                        + "  |  " + n + " cert. stav(ů)"
                        + "  |  respondér: " + id;
            }
            return "Status: " + ocspRespStatusLabel(resp.getStatus());
        } catch (Exception e) {
            return "(neparsovatelné)";
        }
    }

    /** Returns the CN of a byName OCSP responderId, or the full DN, or hex of a byKey hash. */
    private static String ocspResponderIdCn(org.bouncycastle.cert.ocsp.BasicOCSPResp basic) {
        try {
            org.bouncycastle.asn1.ocsp.ResponderID respId =
                    org.bouncycastle.asn1.ocsp.ResponderID.getInstance(
                            basic.getResponderId().toASN1Primitive());
            org.bouncycastle.asn1.x500.X500Name byName = respId.getName();
            if (byName != null) {
                org.bouncycastle.asn1.x500.RDN[] cnRdns =
                        byName.getRDNs(org.bouncycastle.asn1.x500.style.BCStyle.CN);
                if (cnRdns != null && cnRdns.length > 0)
                    return cnRdns[0].getFirst().getValue().toString();
                return byName.toString();
            }
            byte[] keyHash = respId.getKeyHash();
            if (keyHash != null) {
                StringBuilder sb = new StringBuilder("KeyHash:");
                for (byte b : keyHash) sb.append(String.format("%02X", b & 0xFF));
                return sb.toString();
            }
        } catch (Exception ignored) { }
        return "?";
    }

    /** Returns the full DN of a byName OCSP responderId, or hex of a byKey hash. */
    private static String ocspResponderIdFull(org.bouncycastle.cert.ocsp.BasicOCSPResp basic) {
        try {
            org.bouncycastle.asn1.ocsp.ResponderID respId =
                    org.bouncycastle.asn1.ocsp.ResponderID.getInstance(
                            basic.getResponderId().toASN1Primitive());
            org.bouncycastle.asn1.x500.X500Name byName = respId.getName();
            if (byName != null) return byName.toString();
            byte[] keyHash = respId.getKeyHash();
            if (keyHash != null) {
                StringBuilder sb = new StringBuilder("KeyHash:");
                for (byte b : keyHash) sb.append(String.format("%02X", b & 0xFF));
                return sb.toString();
            }
        } catch (Exception ignored) { }
        return "?";
    }

    /** Totéž ze syrových bajtů — pro seznam, který DSS nezná jako použitý. */
    private static void openRawCrlViewer(byte[] der) {
        if (der == null || der.length == 0) { showError("CRL neobsahuje žádná data."); return; }
        CrlView.show(der, null, null, null);
    }

    // ---------- OCSP detail dialog for raw bytes ----------

    private void showRawOcspDialog(byte[] der) {
        eu.europa.esig.dss.diagnostic.RevocationWrapper wrapper = null;
        if (currentDd != null) {
            for (eu.europa.esig.dss.diagnostic.RevocationWrapper r : currentDd.getAllRevocationData()) {
                if (java.util.Arrays.equals(r.getBinaries(), der)) {
                    wrapper = r;
                    break;
                }
            }
        }
        showOcspDialogUnified(der, wrapper);
    }

    private int cosArraySize(org.apache.pdfbox.cos.COSDictionary dict, String key) {
        org.apache.pdfbox.cos.COSBase obj = dict.getDictionaryObject(
                org.apache.pdfbox.cos.COSName.getPDFName(key));
        return (obj instanceof org.apache.pdfbox.cos.COSArray arr) ? arr.size() : 0;
    }

    /**
     * Podpis se {@code /SubFilter adbe.x509.rsa_sha1}: v {@code /Contents} není CMS, ale holá
     * podpisová hodnota PKCS #1 zabalená do OCTET STRING, a certifikáty leží zvlášť v položce
     * {@code /Cert}. DSS takový podpis vůbec nezpracuje; do 27. 9. 2026 ho tahle karta četla jako
     * CMS a hlásila „Chyba parsování CMS“ (nález uživatele nad elektronickými značkami).
     *
     * <p>Kontrola podpisové hodnoty tu je jen kryptografická: veřejným klíčem prvního certifikátu
     * z {@code /Cert} se hodnota rozšifruje a otisk z ní se porovná s otiskem podepsaných bajtů.
     * O certifikátu (řetěz, revokace, kvalifikovanost) neříká nic.
     */
    private void appendPkcs1Signature(VBox body,
            org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature pdSig,
            java.io.File pdfFile) {
        String fieldName = pdSig.getName();
        Label sigLbl = new Label("Podpis" +
                (fieldName != null && !fieldName.isBlank() ? ": " + fieldName : ""));
        sigLbl.setStyle(
                "-fx-font-size:11px;-fx-font-weight:bold;-fx-text-fill:#003189;-fx-padding:6 0 2 0;");
        body.getChildren().add(sigLbl);

        addIfNotBlank(body, "Filtr (Filter)",       pdSig.getFilter());
        addIfNotBlank(body, "Subfiltr (SubFilter)", pdSig.getSubFilter());
        body.getChildren().add(row("Formát obsahu",
                "holá podpisová hodnota PKCS #1, nikoli struktura CMS — certifikáty jsou"
                + " v položce /Cert. Tento typ podpisu knihovna DSS nezpracovává."));

        cz.digitalnikontinuita.anep.core.Pkcs1PdfSignature p;
        try {
            p = cz.digitalnikontinuita.anep.core.Pkcs1PdfSignature.read(pdSig,
                    java.nio.file.Files.readAllBytes(pdfFile.toPath()));
        } catch (Exception e) {
            body.getChildren().add(row("Chyba čtení souboru",
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
            return;
        }
        if (p.signatureValue() != null) {
            body.getChildren().add(row("Délka podpisové hodnoty",
                    p.signatureValue().length + " B (" + p.signatureValue().length * 8 + " bitů)"));
        }
        body.getChildren().add(row("Certifikáty v /Cert", p.certificates().size() + " záznamů"));
        java.security.cert.X509Certificate signer = p.signingCertificate();
        if (signer != null) {
            body.getChildren().add(row("Certifikát podpisu (první v /Cert)",
                    signer.getSubjectX500Principal().getName(
                            javax.security.auth.x500.X500Principal.RFC2253)));
            body.getChildren().add(row("Vydavatel certifikátu",
                    signer.getIssuerX500Principal().getName(
                            javax.security.auth.x500.X500Principal.RFC2253)));
        }
        if (p.digestAlgorithm() != null) {
            body.getChildren().add(row("Hašovací funkce (DigestInfo)", p.digestAlgorithm()));
        }
        body.getChildren().add(row("Kryptografická kontrola",
                Boolean.TRUE.equals(p.intact())
                        ? "podpisová hodnota odpovídá podepsanému obsahu — obsah od podpisu nebyl"
                          + " změněn (ověřena jen integrita; certifikát viz Rozšířený přehled)"
                        : Boolean.FALSE.equals(p.intact())
                        ? "podpisová hodnota NEODPOVÍDÁ podepsanému obsahu"
                          + (p.problem() != null ? " — " + p.problem() : "")
                        : "nelze provést — " + p.problem()));
    }

    private void appendCmsSignature(VBox body,
            org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature pdSig) {

        String fieldName = pdSig.getName();
        Label sigLbl = new Label("Podpis" +
                (fieldName != null && !fieldName.isBlank() ? ": " + fieldName : ""));
        sigLbl.setStyle(
                "-fx-font-size:11px;-fx-font-weight:bold;-fx-text-fill:#003189;-fx-padding:6 0 2 0;");
        body.getChildren().add(sigLbl);

        addIfNotBlank(body, "Filtr (Filter)",       pdSig.getFilter());
        addIfNotBlank(body, "Subfiltr (SubFilter)", pdSig.getSubFilter());

        byte[] contents = null;
        try { contents = pdSig.getContents(); } catch (Exception ignored) {}

        if (contents == null || contents.length == 0) {
            body.getChildren().add(row("CMS obsah", "Prázdný — žádná data k analýze"));
            return;
        }

        // Strip trailing NUL padding (PDF writers zero-pad /Contents to the reserved size)
        int realLen = contents.length;
        while (realLen > 0 && contents[realLen - 1] == 0) realLen--;
        if (realLen < contents.length)
            contents = java.util.Arrays.copyOf(contents, realLen);

        try (org.bouncycastle.asn1.ASN1InputStream asn1in =
                new org.bouncycastle.asn1.ASN1InputStream(contents)) {

            org.bouncycastle.asn1.cms.ContentInfo ci =
                    org.bouncycastle.asn1.cms.ContentInfo.getInstance(asn1in.readObject());
            org.bouncycastle.asn1.cms.SignedData sd =
                    org.bouncycastle.asn1.cms.SignedData.getInstance(ci.getContent());

            body.getChildren().add(row("CMS verze", "SignedData v" + sd.getVersion().getValue()));

            String digestAlgStr = streamDigestAlgs(sd.getDigestAlgorithms());
            if (!digestAlgStr.isEmpty())
                body.getChildren().add(row("Hashové algoritmy v SignedData", digestAlgStr));

            if (sd.getEncapContentInfo() != null)
                body.getChildren().add(row("Typ obsahu (eContentType)",
                        cmsOidLabel(sd.getEncapContentInfo().getContentType().getId())));

            if (sd.getCertificates() != null)
                body.getChildren().add(row("Certifikáty v CMS (/certificates)",
                        sd.getCertificates().size() + " záznamů"));
            if (sd.getCRLs() != null)
                body.getChildren().add(row("CRL v CMS (/crls)", sd.getCRLs().size() + " záznamů"));

            org.bouncycastle.asn1.ASN1Set siSet = sd.getSignerInfos();
            body.getChildren().add(row("Podepisovatelé (SignerInfo)", String.valueOf(siSet.size())));

            for (int i = 0; i < siSet.size(); i++) {
                org.bouncycastle.asn1.cms.SignerInfo si =
                        org.bouncycastle.asn1.cms.SignerInfo.getInstance(siSet.getObjectAt(i));
                appendSignerInfo(body, si, i + 1, siSet.size() > 1);
            }

        } catch (Exception e) {
            body.getChildren().add(row("Chyba parsování CMS",
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private String streamDigestAlgs(org.bouncycastle.asn1.ASN1Set algSet) {
        if (algSet == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < algSet.size(); i++) {
            try {
                org.bouncycastle.asn1.x509.AlgorithmIdentifier ai =
                        org.bouncycastle.asn1.x509.AlgorithmIdentifier.getInstance(algSet.getObjectAt(i));
                if (i > 0) sb.append(", ");
                sb.append(cmsOidLabel(ai.getAlgorithm().getId()));
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    private void appendSignerInfo(VBox body,
            org.bouncycastle.asn1.cms.SignerInfo si, int idx, boolean multi) {

        if (multi) {
            Label hdr = new Label("— Podpisující č. " + idx + " —");
            hdr.setStyle(
                    "-fx-font-size:11px;-fx-font-weight:bold;-fx-text-fill:#555555;" +
                    "-fx-padding:6 0 2 8;");
            body.getChildren().add(hdr);
        }

        body.getChildren().add(row("Verze SignerInfo", "v" + si.getVersion().getValue()));
        body.getChildren().add(row("Hash algoritmus (SignerInfo)",
                cmsOidLabel(si.getDigestAlgorithm().getAlgorithm().getId())));
        body.getChildren().add(row("Šifrovací algoritmus podpisu",
                cmsOidLabel(si.getDigestEncryptionAlgorithm().getAlgorithm().getId())));

        // --- Signed attributes ---
        body.getChildren().add(sectionHeader(
                "Podepsané atributy  — ✓ JSOU kryty podpisem (změna zneplatní podpis)"));

        org.bouncycastle.asn1.ASN1Set authAttrs = si.getAuthenticatedAttributes();
        if (authAttrs == null || authAttrs.size() == 0) {
            body.getChildren().add(plainAttrNote("(žádné podepsané atributy — holý podpis)"));
        } else {
            for (int i = 0; i < authAttrs.size(); i++) {
                try {
                    org.bouncycastle.asn1.cms.Attribute attr =
                            org.bouncycastle.asn1.cms.Attribute.getInstance(authAttrs.getObjectAt(i));
                    String oid = attr.getAttrType().getId();
                    body.getChildren().add(cmsAttrRow(
                            cmsAttrLabel(oid), oid, cmsAttrDetail(oid, attr), true));
                } catch (Exception ignored) {}
            }
        }

        // --- Unsigned attributes ---
        body.getChildren().add(sectionHeader(
                "Nepodepsané atributy  — ✗ NEJSOU kryty podpisem (lze přidávat bez zneplatnění)"));

        org.bouncycastle.asn1.ASN1Set unauthAttrs = si.getUnauthenticatedAttributes();
        if (unauthAttrs == null || unauthAttrs.size() == 0) {
            body.getChildren().add(plainAttrNote("(žádné nepodepsané atributy)"));
        } else {
            for (int i = 0; i < unauthAttrs.size(); i++) {
                try {
                    org.bouncycastle.asn1.cms.Attribute attr =
                            org.bouncycastle.asn1.cms.Attribute.getInstance(unauthAttrs.getObjectAt(i));
                    String oid = attr.getAttrType().getId();
                    body.getChildren().add(cmsAttrRow(
                            cmsAttrLabel(oid), oid, cmsAttrDetail(oid, attr), false));

                    // Nested TST info for timestamp-type attributes
                    if ("1.2.840.113549.1.9.16.2.14".equals(oid)
                            || "1.2.840.113549.1.9.16.2.27".equals(oid)
                            || "1.2.840.113549.1.9.16.2.48".equals(oid)) {
                        appendTstDetails(body, attr);
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Row showing a CMS signed or unsigned attribute.
     * The ✓/✗ badge immediately signals whether the attribute is protected by the signature.
     */
    private HBox cmsAttrRow(String name, String oid, String detail, boolean signed) {
        Label nameLbl = new Label(name);
        nameLbl.setStyle("-fx-font-weight:bold;-fx-text-fill:#111111;-fx-font-size:12px;");
        nameLbl.setMinWidth(230);
        nameLbl.setMaxWidth(230);
        nameLbl.setWrapText(true);

        Label badge = new Label(signed ? "✓ podepsáno" : "✗ nepodepsáno");
        badge.setStyle(signed
                ? "-fx-font-size:10px;-fx-font-weight:bold;-fx-text-fill:#1a7a1a;"
                : "-fx-font-size:10px;-fx-font-weight:bold;-fx-text-fill:#cc5500;");

        String valueText = oid + (detail != null && !detail.isBlank() ? "  |  " + detail : "");
        TextArea val = ResultView.selectable(valueText, "-fx-text-fill:#444444;-fx-font-size:11px;");
        HBox.setHgrow(val, Priority.ALWAYS);

        HBox rightBox = new HBox(8, badge, val);
        HBox.setHgrow(rightBox, Priority.ALWAYS);

        HBox box = new HBox(8, nameLbl, rightBox);
        box.setPadding(new Insets(2, 0, 2, 8));
        return box;
    }

    private Label plainAttrNote(String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-size:11px;-fx-text-fill:#777777;-fx-padding:1 0 1 16;");
        return lbl;
    }

    /**
     * Parses and displays nested TSTInfo data from a timestamp-type CMS attribute.
     * One row per timestamp token in the attribute's value SET.
     */
    private void appendTstDetails(VBox body,
            org.bouncycastle.asn1.cms.Attribute attr) {
        try {
            org.bouncycastle.asn1.ASN1Set vals = attr.getAttrValues();
            for (int v = 0; v < vals.size(); v++) {
                try {
                    org.bouncycastle.asn1.cms.ContentInfo ci =
                            org.bouncycastle.asn1.cms.ContentInfo.getInstance(vals.getObjectAt(v));
                    org.bouncycastle.asn1.cms.SignedData sd =
                            org.bouncycastle.asn1.cms.SignedData.getInstance(ci.getContent());

                    org.bouncycastle.asn1.ASN1OctetString encapContent =
                            org.bouncycastle.asn1.ASN1OctetString.getInstance(
                                    sd.getEncapContentInfo().getContent());
                    org.bouncycastle.asn1.tsp.TSTInfo tst =
                            org.bouncycastle.asn1.tsp.TSTInfo.getInstance(
                                    org.bouncycastle.asn1.ASN1Primitive.fromByteArray(
                                            encapContent.getOctets()));

                    String genTime = "?";
                    try { genTime = fmtDate(tst.getGenTime().getDate()); }
                    catch (Exception ignored) {}

                    String msgAlg = tst.getMessageImprint() != null
                            ? cmsOidLabel(tst.getMessageImprint()
                                    .getHashAlgorithm().getAlgorithm().getId())
                            : "?";
                    String serial = tst.getSerialNumber() != null
                            ? cz.digitalnikontinuita.anep.core.ContainerInspector.hexNumber(
                                    tst.getSerialNumber().getValue())
                            : "?";

                    String tsaStr = "";
                    if (tst.getTsa() != null) {
                        tsaStr = "  |  TSA: " + tst.getTsa().toString();
                    } else if (sd.getSignerInfos().size() > 0) {
                        // Try to get TSA name from inner SignerInfo signing cert
                        try {
                            org.bouncycastle.asn1.cms.SignerInfo innerSi =
                                    org.bouncycastle.asn1.cms.SignerInfo.getInstance(
                                            sd.getSignerInfos().getObjectAt(0));
                            // Just note the inner signer count
                            tsaStr = "  |  inner signers: " + sd.getSignerInfos().size();
                        } catch (Exception ignored) {}
                    }

                    String tstLabel = vals.size() > 1
                            ? "  → Razítko " + (v + 1) : "  → Razítko";
                    Label row = new Label(tstLabel
                            + ":  čas " + genTime
                            + "  |  hash " + msgAlg
                            + "  |  S/N " + serial
                            + tsaStr);
                    row.setStyle(
                            "-fx-font-size:11px;-fx-text-fill:#004488;-fx-padding:0 0 2 32;");
                    row.setWrapText(true);
                    body.getChildren().add(row);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    // CMS attribute OID → friendly label
    private static String cmsAttrLabel(String oid) {
        return switch (oid) {
            // Signed attributes
            case "1.2.840.113549.1.9.3"        -> "id-contentType";
            case "1.2.840.113549.1.9.4"        -> "id-messageDigest";
            case "1.2.840.113549.1.9.5"        -> "id-signingTime";
            case "1.2.840.113549.1.9.16.2.12"  -> "id-aa-signingCertificate";
            case "1.2.840.113549.1.9.16.2.47"  -> "id-aa-signingCertificateV2";
            case "1.2.840.113549.1.9.16.2.16"  -> "id-aa-commitmentTypeIndication";
            case "1.2.840.113549.1.9.16.2.11"  -> "id-aa-ets-sigPolicyId";
            case "1.2.840.113549.1.9.16.2.15"  -> "id-aa-ets-signerLocation";
            case "1.2.840.113549.1.9.16.2.44"  -> "id-aa-signerAttributesV2";
            case "1.2.840.113549.1.9.15"       -> "id-smimeCapabilities";
            // Unsigned attributes
            case "1.2.840.113549.1.9.16.2.14"  -> "id-aa-signatureTimeStampToken";
            case "1.2.840.113549.1.9.16.2.21"  -> "id-aa-ets-certificateRefs";
            case "1.2.840.113549.1.9.16.2.22"  -> "id-aa-ets-revocationRefs";
            case "1.2.840.113549.1.9.16.2.23"  -> "id-aa-ets-certValues";
            case "1.2.840.113549.1.9.16.2.24"  -> "id-aa-ets-revocationValues";
            case "1.2.840.113549.1.9.16.2.25"  -> "id-aa-ets-escTimeStamp";
            case "1.2.840.113549.1.9.16.2.26"  -> "id-aa-ets-certCRLTimestamp";
            case "1.2.840.113549.1.9.16.2.27"  -> "id-aa-ets-archiveTimestampV2";
            case "1.2.840.113549.1.9.16.2.48"  -> "id-aa-ets-archiveTimestampV3";
            case "1.2.840.113549.1.9.16.2.43"  -> "id-aa-ets-ATSHashIndex";
            case "1.2.840.113549.1.9.16.2.49"  -> "id-aa-ets-ATSHashIndexV2";
            default -> oid;
        };
    }

    // General OID (content types, hash algorithms, asymmetric algorithms) → label
    private static String cmsOidLabel(String oid) {
        return switch (oid) {
            case "1.2.840.113549.1.7.1"       -> "id-data";
            case "1.2.840.113549.1.7.2"       -> "id-signedData";
            case "1.2.840.113549.1.9.16.1.4"  -> "id-ct-TSTInfo";
            case "2.16.840.1.101.3.4.2.1"     -> "SHA-256";
            case "2.16.840.1.101.3.4.2.2"     -> "SHA-384";
            case "2.16.840.1.101.3.4.2.3"     -> "SHA-512";
            case "1.3.14.3.2.26"              -> "SHA-1";
            case "2.16.840.1.101.3.4.2.4"     -> "SHA-224";
            case "1.2.840.113549.1.1.1"       -> "RSA";
            case "1.2.840.113549.1.1.11"      -> "SHA256withRSA";
            case "1.2.840.113549.1.1.12"      -> "SHA384withRSA";
            case "1.2.840.113549.1.1.13"      -> "SHA512withRSA";
            case "1.2.840.10045.2.1"          -> "EC";
            case "1.2.840.10045.4.3.2"        -> "SHA256withECDSA";
            case "1.2.840.10045.4.3.3"        -> "SHA384withECDSA";
            case "1.2.840.10045.4.3.4"        -> "SHA512withECDSA";
            default -> oid;
        };
    }

    /** Decode the human-readable detail value for a given CMS attribute type. */
    private String cmsAttrDetail(String oid,
            org.bouncycastle.asn1.cms.Attribute attr) {
        try {
            org.bouncycastle.asn1.ASN1Set vals = attr.getAttrValues();
            return switch (oid) {
                case "1.2.840.113549.1.9.3" -> { // contentType
                    org.bouncycastle.asn1.ASN1ObjectIdentifier ct =
                            org.bouncycastle.asn1.ASN1ObjectIdentifier.getInstance(
                                    vals.getObjectAt(0));
                    yield cmsOidLabel(ct.getId());
                }
                case "1.2.840.113549.1.9.5" -> { // signingTime
                    org.bouncycastle.asn1.cms.Time time =
                            org.bouncycastle.asn1.cms.Time.getInstance(vals.getObjectAt(0));
                    yield fmtDate(time.getDate());
                }
                case "1.2.840.113549.1.9.4" -> { // messageDigest
                    byte[] digest = org.bouncycastle.asn1.DEROctetString.getInstance(
                            vals.getObjectAt(0)).getOctets();
                    String hex = byteArrayToHex(digest);
                    yield hex.length() > 32 ? hex.substring(0, 32) + "…" : hex;
                }
                case "1.2.840.113549.1.9.16.2.16" -> { // commitmentTypeIndication
                    org.bouncycastle.asn1.ASN1Sequence seq =
                            org.bouncycastle.asn1.ASN1Sequence.getInstance(vals.getObjectAt(0));
                    org.bouncycastle.asn1.ASN1ObjectIdentifier commitType =
                            org.bouncycastle.asn1.ASN1ObjectIdentifier.getInstance(seq.getObjectAt(0));
                    yield commitmentTypeLabel(commitType.getId());
                }
                case "1.2.840.113549.1.9.16.2.14",
                     "1.2.840.113549.1.9.16.2.27",
                     "1.2.840.113549.1.9.16.2.48" ->
                    vals.size() == 1 ? "(1 razítko — detail níže)"
                                     : "(" + vals.size() + " razítka — detail níže)";
                case "1.2.840.113549.1.9.16.2.23",  // certValues
                     "1.2.840.113549.1.9.16.2.24"   // revocationValues
                    -> "(vložená validační data)";
                case "1.2.840.113549.1.9.16.2.21",  // certificateRefs
                     "1.2.840.113549.1.9.16.2.22"   // revocationRefs
                    -> "(hashové reference)";
                default -> vals.size() > 0 ? "(" + vals.size() + " hodnot)" : "";
            };
        } catch (Exception e) {
            return "(neparsovatelné)";
        }
    }

    private static String commitmentTypeLabel(String oid) {
        return switch (oid) {
            case "1.2.840.113549.1.9.16.6.1" -> "Důkaz původu (proofOfOrigin)";
            case "1.2.840.113549.1.9.16.6.2" -> "Důkaz přijetí (proofOfReceipt)";
            case "1.2.840.113549.1.9.16.6.3" -> "Důkaz doručení (proofOfDelivery)";
            case "1.2.840.113549.1.9.16.6.4" -> "Důkaz odesílatele (proofOfSender)";
            case "1.2.840.113549.1.9.16.6.5" -> "Souhlas / schválení (proofOfApproval)";
            case "1.2.840.113549.1.9.16.6.6" -> "Důkaz vytvoření (proofOfCreation)";
            default -> oid;
        };
    }

    private static String byteArrayToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
