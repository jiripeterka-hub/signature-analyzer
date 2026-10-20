package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.model.EvidenceRecordEntry;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import cz.digitalnikontinuita.anep.model.TimestampChain;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;

import eu.europa.esig.dss.diagnostic.CertificateRevocationWrapper;
import eu.europa.esig.dss.enumerations.CertificateStatus;
import eu.europa.esig.dss.enumerations.RevocationType;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.cert.ocsp.UnknownStatus;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ExtendedView {

    /**
     * Razítkovací služba a vybraný zdroj pro protokol; {@code null} znamená, že se na razítko
     * nebude ptát. Drží to {@code MainView}, tenhle pohled to jen předá dál.
     */
    private PrintUtil.ProtocolExtras protocolExtras;

    /**
     * Kdy ověřování proběhlo — do záhlaví protokolu, vedle údajů o souborech. Bez toho tu ta
     * položka chyběla, ačkoli protokol ze Zjednodušeného přehledu ji nese; z hotového protokolu
     * pak nešlo poznat, ke kterému okamžiku závěry platí.
     */
    private java.time.Instant verificationTime;

    void setVerificationTime(java.time.Instant t) { this.verificationTime = t; }

    /**
     * Otevře náhled na dané straně v podobě kryté podpisem, resp. v dnešní podobě — pro blok
     * „Po podepsání se v dokumentu změnil obsah“, který tu stojí od 9. 10. 2026 (do té doby
     * v Analýze). Nastavuje {@code MainView}, protože jen on má soubor, heslo i seznam podob.
     * {@code null} u sond — čísla stran se pak vysází jako prostý text.
     */
    void setPreviewOpeners(java.util.function.IntConsumer covered,
                           java.util.function.IntConsumer current) {
        this.coveredPreviewOpener = covered;
        this.currentPreviewOpener = current;
    }

    private java.util.function.IntConsumer coveredPreviewOpener;
    private java.util.function.IntConsumer currentPreviewOpener;

    public void setProtocolExtras(PrintUtil.ProtocolExtras extras) {
        this.protocolExtras = extras;
    }

    private static final String CARD_STYLE =
            "-fx-border-color: #cccccc; -fx-border-width: 1; -fx-border-radius: 4;";


    private static final double LBL_WIDTH = 270;

    /** Why a VRI time-stamp that nothing covers does not set the decisive instant. */
    private static final String VRI_BARE_NOTE =
            "Toto razítko je uloženo ve slovníku DSS/VRI dokumentu, nikoli v podpisu samotném."
            + " Norma PAdES uznává pro úroveň T jen dvě konstrukce — atribut signature-time-stamp"
            + " uvnitř podpisu a dokumentové (archivní) časové razítko. Razítko ve VRI záznamu"
            + " není ani jedno z nich.\n\n"
            + "Rozhodující je, že VRI záznam přibyl inkrementálním updatem a v tomto dokumentu ho"
            + " nekryje žádné dokumentové razítko. Kdokoli ho proto může odstranit či nahradit,"
            + " aniž by to šlo poznat. Důkaz, který lze beze stopy odstranit, nemůže nést závěr"
            + " o platnosti podpisu.\n\n"
            + "Samotné razítko je přitom kryptograficky v pořádku a skutečně dokládá, že podpis"
            + " v uvedený čas existoval. Konformní validátory — včetně online služby DSS — s ním"
            + " ale nepočítají a rozhodný okamžik ponechávají na aktuálním čase. AnEP se proto"
            + " chová stejně, aby nesliboval ověřitelnost, kterou jinde nepotvrdí.\n\n"
            + "Kdyby dokument obsahoval dokumentové časové razítko, které tento token kryje,"
            + " platilo by něco jiného: krytí ho chrání před odstraněním, uplatní se extrakce"
            + " důkazů existence (PoE) a rozhodný okamžik by se posunul na čas tohoto razítka.";

    private final VBox cardsBox = new VBox();

    /**
     * Diagnostická data posledního vykreslení — blok „Použité kryptografické algoritmy“ z nich
     * bere algoritmy a délky klíčů, které souhrn prvku nenese (5. 10. 2026). {@code null}
     * před prvním vykreslením.
     */
    private DiagnosticData diagnosticData;
    private final ScrollPane scroll = new ScrollPane(cardsBox);
    private final Button pdfBtn = new Button("Generovat protokol jako PDF");
    private final VBox root;

    private File pdfSigFile;
    private File pdfOrigFile;

    private boolean timestampStandardsMode = true;
    private boolean preferLatestOcsp = false;
    private final TrustListInfoBar trustListInfoBar = new TrustListInfoBar();
    private java.time.LocalDateTime customValidationTime = null;

    public void setTimestampStandardsMode(boolean v)          { this.timestampStandardsMode = v; }
    public void setPreferLatestOcsp(boolean v)                { this.preferLatestOcsp = v; }
    public void setCustomValidationTime(java.time.LocalDateTime v) { this.customValidationTime = v; }

    public void setTrustListInfo(long sourceCheckMs, boolean refreshInProgress,
                                 TrustConfig.TrustListVersion latestList,
                                 java.util.List<TrustConfig.StaleTrustList> staleTrustLists,
                                 java.util.List<String> emptyTerritories) {
        trustListInfoBar.set(sourceCheckMs, refreshInProgress, latestList,
                staleTrustLists, emptyTerritories);
    }

    void setFileInfo(File sig, File orig) {
        pdfSigFile = sig;
        pdfOrigFile = orig;
    }

    /**
     * Odkaz z přílohy {@code legalisation-metadata.xml}, našel-li se v předloženém dokumentu.
     *
     * <p>Čte ho {@code MainView} při ověřování — tam, kde už PDF stejně otevřené je — a předává
     * sem. Blok o odkazech se staví jen podle něj: {@code null} znamená, že dokument žádnou
     * takovou přílohu nenese a blok na kartě není.
     */
    void setLegalisationLink(cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Link link) {
        this.legalisationLink = link;
    }

    private cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Link legalisationLink;

    /**
     * Těla bloků o odkazech, jedno na kartu — kvůli tomu, aby se daly překreslit, až uživatel
     * v okně provede kontrolu.
     *
     * <p>Okno se otevírá i nad hotovou kartou, takže bez překreslení by blok tvrdil „dosud
     * nebyl předložen“ ve chvíli, kdy vedle něj svítí zelený pruh s výsledkem.
     */
    private final java.util.List<VBox> legalisationBodies = new java.util.ArrayList<>();

    public ExtendedView() {
        // Kontrola odkazu běží ve vlastním okně a klidně až nad hotovou kartou; překreslení
        // musí na vlákno rozhraní, protože zápis přichází z vlákna, které kontrolu dokončilo.
        cz.digitalnikontinuita.anep.core.LegalisationLinkLog.onChange(
                () -> javafx.application.Platform.runLater(this::refreshLegalisationBlocks));
        cardsBox.setPadding(new Insets(12));
        cardsBox.setSpacing(0);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        // Karta je tu složená z pojmenovaných bloků a v protokolu se sázejí bez nadpisů; viz
        // PrintUtil.PLAIN_BLOCKS.
        cardsBox.getProperties().put(PrintUtil.PLAIN_BLOCKS, Boolean.TRUE);

        pdfBtn.setDisable(true);
        pdfBtn.setOnAction(e -> PrintUtil.saveAsPdf(cardsBox,
                (Stage) pdfBtn.getScene().getWindow(),
                PrintUtil.protocolFileName("RP", pdfSigFile),
                pdfSigFile, pdfOrigFile, "Rozšířený přehled", verificationTime,
                java.util.List.of(), protocolExtras));
        HBox toolbar = new HBox(pdfBtn);
        toolbar.setPadding(new Insets(4, 8, 8, 8));

        VBox.setVgrow(scroll, Priority.ALWAYS);
        root = new VBox(scroll, toolbar);
    }

    public VBox getRoot() {
        return root;
    }

    /** Vrátí záložku do podoby před prvním ověřením; viz {@code MainView.clearResults()}. */
    public void clear() {
        pdfBtn.setDisable(true);
        cardsBox.getChildren().clear();
    }

    public void show(ValidationResult result) {
        pdfBtn.setDisable(false);
        cardsBox.getChildren().clear();
        legalisationBodies.clear();

        // Vkládá se vždycky, i když zrovna není co říct: pruh se překresluje i mimo tohle
        // vykreslení (po obnově seznamů v Nastavení), a to jde jen u uzlu, který ve stromu je.
        cardsBox.getChildren().add(trustListInfoBar.node());

        DiagnosticData dd = result.getDiagnosticData();

        // Per-signature, per-timestamp expiry flags keyed by signature ID.
        // Index i corresponds to the i-th timestamp sorted by production time (ascending).
        // A timestamp is "expired" only if its TSA cert was past notAfter at the point in time
        // when that timestamp's validity needed to be assessed:
        //   – intermediate timestamps: assessed at the production time of the next valid timestamp
        //   – the last timestamp:      assessed at current time
        Map<String, java.util.List<Boolean>> tsExpiredFlagsBySig = new HashMap<>();
        if (!timestampStandardsMode && dd != null) {
            java.util.Date now = new java.util.Date();
            for (eu.europa.esig.dss.diagnostic.SignatureWrapper sig : dd.getSignatures()) {
                java.util.List<TimestampWrapper> allTs = sig.getTimestampList().stream()
                        .filter(ts -> ts.getProductionTime() != null)
                        .sorted(java.util.Comparator.comparing(TimestampWrapper::getProductionTime))
                        .collect(java.util.stream.Collectors.toList());
                java.util.List<Boolean> flags = new java.util.ArrayList<>();
                for (int i = 0; i < allTs.size(); i++) {
                    TimestampWrapper ts = allTs.get(i);
                    java.util.Date validationTime;
                    if (i == allTs.size() - 1) {
                        validationTime = now;
                    } else {
                        TimestampWrapper next = allTs.get(i + 1);
                        boolean nextValid = next.isMessageImprintDataIntact()
                                && next.isSignatureIntact() && next.isSignatureValid()
                                && next.isTrustedChain();
                        validationTime = nextValid ? next.getProductionTime() : now;
                    }
                    flags.add(hasExpiredCertInTsChainAt(ts, dd, validationTime));
                }
                tsExpiredFlagsBySig.put(sig.getId(), flags);
            }
        }

        Map<String, CertificateRevocationWrapper> sigRevocMap =
                buildSigRevocMap(dd, result.getDetailedReportXml(), result.getSignatures());

        Map<String, String> signerEmails = buildSignerEmails(dd);
        Map<String, List<CertificateRevocationWrapper>> documentRevocation =
                buildEmbeddedRevocation(dd);

        List<cz.digitalnikontinuita.anep.core.CustomCrlStore.Entry> customCrls =
                cz.digitalnikontinuita.anep.core.CustomCrlStore.listCrls();

        List<SignatureSummary> sigs = result.getSignatures();
        elements = sigs;
        diagnosticData = dd;
        Label countLabel = ResultView.authCountHeader(result);
        cardsBox.getChildren().add(countLabel);

        // Evidence records covering no signature — a preservation container holding only data,
        // or a record handed in on its own. They get their own card because there is no
        // signature card to hang them on, and without it the tab would be empty for a file that
        // does carry a proof of existence.
        List<EvidenceRecordEntry> loose = result.getStandaloneEvidenceRecords();
        if (!loose.isEmpty()) {
            cardsBox.getChildren().add(new Separator());
            VBox erCard = new VBox(4);
            erCard.setPadding(new Insets(6, 0, 6, 0));
            Label head = new Label(loose.size() == 1
                    ? "Evidenční záznam bez vazby na podpis"
                    : "Evidenční záznamy bez vazby na podpis");
            head.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #003189;");
            erCard.getChildren().add(head);
            appendEvidenceRecords(erCard, loose, pdfOrigFile != null);
            cardsBox.getChildren().add(erCard);
        }

        // Připravená, ale nepodepsaná pole — týž blok jako v Analýze, ze stejného zdroje.
        // Stojí před návratem u prázdného seznamu podpisů: dokument může mít prázdné pole
        // a jinak nic, a právě tehdy je to jediné, co se o něm dá říct.
        VBox emptyFields = EmptySignatureFieldBlock.build(result);
        if (emptyFields != null) {
            cardsBox.getChildren().add(new Separator());
            cardsBox.getChildren().add(emptyFields);
        }

        // Změna obsahu po podpisu — varování vedle verdiktu, pro všechny a rozbalené
        // (rozhodnutí uživatele 9. 10. 2026). Klidná podoba bloku zůstává v Analýze.
        if (RevisionTailBlock.contentChanged(result)) {
            cardsBox.getChildren().add(new Separator());
            cardsBox.getChildren().add(RevisionTailBlock.build(result,
                    coveredPreviewOpener, currentPreviewOpener));
        }

        if (sigs.isEmpty()) return;
        cardsBox.getChildren().add(new Separator());

        boolean showHeaders = sigs.size() > 1;
        int sigCounter = 0, sealCounter = 0, tsCounter = 0;
        for (int i = 0; i < sigs.size(); i++) {
            SignatureSummary s = sigs.get(i);
            String header = null;
            if (showHeaders) {
                boolean isTs = "TimeStampToken".equals(s.getFormat());
                boolean isSeal = !isTs && s.isSeal();
                if (isTs) {
                    header = "Časové razítko č.: " + (++tsCounter);
                } else if (isSeal) {
                    header = "Pečeť č.: " + (++sealCounter);
                } else {
                    header = "Podpis č.: " + (++sigCounter);
                }
            }
            cardsBox.getChildren().add(buildCard(s, header,
                    tsExpiredFlagsBySig.getOrDefault(s.getSignatureId(), java.util.List.of()),
                    sigRevocMap, customCrls, signerEmails, documentRevocation));
            if (i < sigs.size() - 1) {
                Separator sep = new Separator();
                sep.setPadding(new Insets(10, 0, 10, 0));
                cardsBox.getChildren().add(sep);
            }
        }

        VBox legalisation = legalisationCard();
        if (legalisation != null) {
            Separator sep = new Separator();
            sep.setPadding(new Insets(10, 0, 10, 0));
            cardsBox.getChildren().add(sep);
            cardsBox.getChildren().add(legalisation);
        }
    }

    private static boolean isFromCustomCrlStore(
            eu.europa.esig.dss.diagnostic.RevocationWrapper rev,
            List<cz.digitalnikontinuita.anep.core.CustomCrlStore.Entry> customCrls) {
        if (rev.getRevocationType() != RevocationType.CRL) return false;
        byte[] der = rev.getBinaries();
        if (der == null) return false;
        for (cz.digitalnikontinuita.anep.core.CustomCrlStore.Entry entry : customCrls) {
            try {
                if (java.util.Arrays.equals(der, entry.crl().getEncoded())) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private boolean hasExpiredCertInTsChainAt(TimestampWrapper ts, DiagnosticData dd,
                                               java.util.Date validationTime) {
        CertificateWrapper signingCert = ts.getSigningCertificate();
        if (signingCert != null) {
            CertificateWrapper full = dd.getUsedCertificateById(signingCert.getId());
            if (full == null) full = signingCert;
            if (full.getNotAfter() != null && full.getNotAfter().before(validationTime)) return true;
        }
        for (CertificateWrapper c : ts.getCertificateChain()) {
            CertificateWrapper full = dd.getUsedCertificateById(c.getId());
            if (full == null) full = c;
            if (full.getNotAfter() != null && full.getNotAfter().before(validationTime)) return true;
        }
        return false;
    }

    /**
     * Maps certificate ID → the revocation token DSS actually selected for it, read from the
     * {@code LatestAcceptableRevocationId} attribute of the Detailed Report's
     * {@code <CRS>} / {@code <PSV_CRS>} (Certificate Revocation Data Selector) elements.
     *
     * <p>This is DSS's own verdict on which revocation was decisive and is therefore
     * authoritative — unlike any local heuristic, it stays correct when several revocations
     * are embedded for one certificate and DSS rejects the newest (e.g. an OCSP issued after
     * the certificate expired, which fails BBB_XCV_REVOC_HAS_CERT_INFO).
     */
    private Map<String, String> parseSelectedRevocationIds(String detailedReportXml) {
        Map<String, String> map = new HashMap<>();
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
                String revId  = el.getAttribute("LatestAcceptableRevocationId");
                if (certId == null || certId.isBlank() || revId == null || revId.isBlank()) continue;
                map.putIfAbsent(certId, revId);
            }
        } catch (Exception ignored) {}
        return map;
    }

    /**
     * E-mail držitele certifikátu, klíčovaný id podpisu či razítka.
     *
     * <p>Není v {@link SignatureSummary}, ačkoli tam patří: konstruktor souhrnu má přes
     * padesát parametrů a přidávat padesátý první kvůli jednomu řádku by tři volání
     * v ReportMapperu ohrozilo víc, než ten řádek stojí. Zdroj je týž, který už ReportMapper
     * používá pro jméno podepsané osoby.
     *
     * <p><b>Proč nestačí DN:</b> z 222 podpisových certifikátů v korpusu D:\tmp nese e-mail
     * v DN jen 24, kdežto 197 ho má výhradně v rozšíření SubjectAltName. Blok stavěný jen
     * na DN by ho tedy u devětí z deseti dokumentů neukázal. Kde je na obou místech, shoduje
     * se — ve všech 24 případech — takže jeden řádek stačí a dva by mátly.
     */
    private static Map<String, String> buildSignerEmails(DiagnosticData dd) {
        Map<String, String> map = new HashMap<>();
        if (dd == null) return map;
        for (eu.europa.esig.dss.diagnostic.SignatureWrapper sig : dd.getSignatures()) {
            String email = cz.digitalnikontinuita.anep.core.CertificateEmailUtil
                    .extractEmail(sig.getSigningCertificate());
            if (email != null && !email.isBlank()) map.put(sig.getId(), email);
        }
        // Samostatné razítko má kartu jako podpis a jeho blok se jmenuje Vydavatel časového
        // razítka; když certifikát jednotky kontakt nese, patří do něj ze stejného důvodu.
        for (TimestampWrapper ts : dd.getTimestampList()) {
            String email = cz.digitalnikontinuita.anep.core.CertificateEmailUtil
                    .extractEmail(ts.getSigningCertificate());
            if (email != null && !email.isBlank()) map.put(ts.getId(), email);
        }
        return map;
    }

    /**
     * Co dokument sám nese o certifikátu, na kterém prvek stojí — klíčováno id podpisu či razítka.
     *
     * <p>Hodnota je popis nejnovějšího vloženého údaje, nebo <b>prázdný řetězec</b>, když
     * dokument o tom certifikátu nenese nic. Ten rozdíl je potřeba: {@code null} znamená „nevíme“
     * (certifikát se nenašel) a v tom případě se neříká nic, kdežto prázdný řetězec je zjištění,
     * o kterém se psát má — dokument bez vlastního revokačního údaje je právě ten, který bez sítě
     * neobstojí.
     */
    private static Map<String, List<CertificateRevocationWrapper>> buildEmbeddedRevocation(
            DiagnosticData dd) {
        Map<String, List<CertificateRevocationWrapper>> map = new HashMap<>();
        if (dd == null) return map;
        for (eu.europa.esig.dss.diagnostic.SignatureWrapper sig : dd.getSignatures()) {
            List<CertificateRevocationWrapper> d = embeddedFor(dd, sig.getSigningCertificate());
            if (d != null) map.put(sig.getId(), d);
        }
        for (TimestampWrapper ts : dd.getTimestampList()) {
            List<CertificateRevocationWrapper> d = embeddedFor(dd, ts.getSigningCertificate());
            if (d != null) map.put(ts.getId(), d);
        }
        return map;
    }

    /**
     * Všechny revokační údaje o tomto certifikátu, které leží přímo v dokumentu, od nejnovějšího;
     * prázdný seznam, není-li tam žádný, a {@code null}, když není o čem mluvit.
     *
     * <p>Celý výčet, ne jen nejnovější údaj. Po uchování bývají v dokumentu dva — původní a ten,
     * který uchování přidalo — a říct jen o jednom z nich znamená zaměčet materiál, který v souboru
     * je. Nalézt: uchovaný dokument, který nese OCSP z 2. 9. i z 9. 9.
     * 2026, Analýza je vypisovala obě, kdežto Rozšířený přehled známil jen tu využitou.
     */
    private static List<CertificateRevocationWrapper> embeddedFor(DiagnosticData dd,
                                                                  CertificateWrapper cert) {
        if (cert == null) return null;
        CertificateWrapper full = dd.getUsedCertificateById(cert.getId());
        if (full != null) cert = full;
        List<CertificateRevocationWrapper> found = new java.util.ArrayList<>();
        for (CertificateRevocationWrapper r : cert.getCertificateRevocationData()) {
            if (!r.isInternalRevocationOrigin() || r.getThisUpdate() == null) continue;
            found.add(r);
        }
        found.sort(java.util.Comparator.comparing(CertificateRevocationWrapper::getThisUpdate)
                .reversed());
        return found;
    }

    /** „OCSP odpověď z 2. 9. 2026“ — jméno jednoho údaje ve větě. */
    private static String describeRevocation(CertificateRevocationWrapper r) {
        return (r.getRevocationType() == RevocationType.OCSP ? "OCSP odpověď" : "CRL seznam")
                + " z " + TimeUtil.format(r.getThisUpdate());
    }

    private Map<String, CertificateRevocationWrapper> buildSigRevocMap(
            DiagnosticData dd, String detailedReportXml, List<SignatureSummary> summaries) {
        Map<String, CertificateRevocationWrapper> map = new HashMap<>();
        if (dd == null) return map;
        Map<String, String> selectedRevIds = parseSelectedRevocationIds(detailedReportXml);
        // Standalone time-stamps are keyed by their own id, the same one their card carries, so the
        // "Využité revokační informace" row can answer for them as well. Signature and time-stamp
        // ids never collide, so one map serves both.
        for (eu.europa.esig.dss.diagnostic.TimestampWrapper ts : dd.getTimestampList()) {
            CertificateWrapper tsCert = ts.getSigningCertificate();
            if (tsCert == null) continue;
            CertificateWrapper full = dd.getUsedCertificateById(tsCert.getId());
            if (full != null) tsCert = full;
            CertificateRevocationWrapper chosen = selectRevocation(tsCert, selectedRevIds);
            if (chosen != null) map.put(ts.getId(), chosen);
        }
        for (eu.europa.esig.dss.diagnostic.SignatureWrapper sig : dd.getSignatures()) {
            CertificateWrapper sigCert = sig.getSigningCertificate();
            if (sigCert != null) {
                CertificateWrapper full = dd.getUsedCertificateById(sigCert.getId());
                if (full != null) sigCert = full;
            }
            if (sigCert == null) continue;
            CertificateRevocationWrapper chosenForSig = selectRevocation(sigCert, selectedRevIds);
            if (chosenForSig != null) map.put(sig.getId(), chosenForSig);
        }

        // Přepis podle ReportMapper.chooseUsedRevocation(): odvolání, jinak údaj, který obstojí bez
        // ústupku. To, co se čte výš z protokolu, je LatestAcceptableRevocationId — doslova
        // „poslední přípustný", a v tolerantním režimu se přípustnou stane i odpověď stažená dnes,
        // takže výběr přeskočí na ni, i když verdikt na ní nestojí. Právě tím tenhle řádek tvrdil
        // něco jiného než „Revokační informace" v Analýze; potkáno na PAdES B-LT kvalifikovany.pdf.
        // Samostatná razítka zůstávají na tom, co dalo DSS — vlastní ValidationCourse nemají.
        if (summaries != null) {
            for (SignatureSummary sum : summaries) {
                var course = sum.getValidationCourse();
                if (course == null || course.primaryRevocation() == null) continue;
                String wanted = course.primaryRevocation().id();
                for (CertificateWrapper c : dd.getUsedCertificates()) {
                    for (CertificateRevocationWrapper r : c.getCertificateRevocationData()) {
                        if (wanted.equals(r.getId())) map.put(sum.getSignatureId(), r);
                    }
                }
            }
        }
        return map;
    }

    /**
     * Which revocation token answered for one certificate — the one the card reports as used.
     * Extracted so a standalone time-stamp's certificate goes through exactly the same choice as a
     * signing certificate's; the rules below are subtle enough that a second copy would drift.
     */
    private CertificateRevocationWrapper selectRevocation(
            CertificateWrapper sigCert, Map<String, String> selectedRevIds) {
        {
            // Preferred path: take the revocation DSS itself reported as decisive. The heuristic
            // below runs only when the Detailed Report is unavailable or names no revocation
            // for this certificate.
            String selectedId = selectedRevIds.get(sigCert.getId());
            if (selectedId != null) {
                for (CertificateRevocationWrapper crw : sigCert.getCertificateRevocationData()) {
                    if (selectedId.equals(crw.getId())) return crw;
                }
            }
            // CertificateRevocationWrapper extends RevocationWrapper, so getThisUpdate() /
            // isInternalRevocationOrigin() are available directly — no revById lookup needed.
            // Status filter deliberately omitted: a cert revoked AFTER the signing time is still
            // relevant info and should be shown (the signature may still be TOTAL_PASSED).
            CertificateRevocationWrapper bestCrw = null;
            boolean bestInternal = false;
            java.util.Date bestThisUpdate = null;
            for (CertificateRevocationWrapper crw : sigCert.getCertificateRevocationData()) {
                boolean isInternal = crw.isInternalRevocationOrigin();
                java.util.Date thisUpdate = crw.getThisUpdate();
                boolean better;
                if (preferLatestOcsp) {
                    better = bestCrw == null
                            || (thisUpdate != null
                                && (bestThisUpdate == null || thisUpdate.after(bestThisUpdate)));
                } else {
                    better = bestCrw == null
                            || (isInternal && !bestInternal)
                            || (isInternal == bestInternal
                                && thisUpdate != null
                                && (bestThisUpdate == null || thisUpdate.after(bestThisUpdate)));
                }
                if (better) {
                    bestCrw = crw;
                    bestInternal = isInternal;
                    bestThisUpdate = thisUpdate;
                }
            }
            // In standards mode (preferLatestOcsp=false): if the selected embedded OCSP has an
            // expired respondent cert (S5), upgrade to a fresher external revocation when one
            // exists in DiagnosticData. Two cases:
            //   S5-LTA: DSS fetched a fresh online OCSP at validation time — upgrade to that.
            //   S5 non-LTA with custom CRL: FallbackCrlSource provided a custom CRL instead of
            //   the post-expiry online CRL — upgrade to that CRL (both OCSP and CRL are
            //   considered; the one with the newer thisUpdate is selected).
            // Only revocations within the cert's validity period (thisUpdate ≤ certNotAfter) are
            // eligible as upgrade targets. Post-expiry online OCSPs are skipped even when present
            // in DiagnosticData — DSS would not select them in standards mode, and upgrading to
            // one would misrepresent what DSS actually used (e.g. with acceptExpiredRevocationSources
            // enabled, DSS trusts the embedded OCSP with the expired respondent, not the post-expiry
            // online one). Without any eligible external revocation the embedded OCSP is kept.
            if (!preferLatestOcsp && bestCrw != null && bestInternal
                    && bestCrw.getRevocationType() == RevocationType.OCSP) {
                java.util.Date now = new java.util.Date();
                CertificateWrapper resp = bestCrw.getSigningCertificate();
                if (resp != null && !resp.isTrusted()
                        && resp.getNotAfter() != null && resp.getNotAfter().before(now)) {
                    java.util.Date certNotAfter = sigCert.getNotAfter();
                    for (CertificateRevocationWrapper crw : sigCert.getCertificateRevocationData()) {
                        if (crw.isInternalRevocationOrigin()) continue;
                        java.util.Date tu = crw.getThisUpdate();
                        // Skip post-expiry external revocations: they are outside the cert's
                        // validity period and DSS would not select them in standards mode.
                        if (tu != null && certNotAfter != null && tu.after(certNotAfter)) continue;
                        // Consider both OCSP and CRL — custom CRLs from the store are external CRLs.
                        if (tu != null && (bestThisUpdate == null || tu.after(bestThisUpdate))) {
                            bestCrw = crw;
                            bestInternal = false;
                            bestThisUpdate = tu;
                        }
                    }
                }
            }
            return bestCrw;
        }
    }

    /** Prvky tohoto ověření v pořadí, ve kterém je čtenář vidí — kvůli číslování rodiče
     *  u protipodpisu. Karta se staví po jednom prvku, ale číslo jiného prvku se dá zjistit
     *  jen ze seznamu. */
    private List<SignatureSummary> elements = List.of();

    private VBox buildCard(SignatureSummary sig, String header,
                           java.util.List<Boolean> tsExpiredFlags,
                           Map<String, CertificateRevocationWrapper> sigRevocMap,
                           List<cz.digitalnikontinuita.anep.core.CustomCrlStore.Entry> customCrls,
                           Map<String, String> signerEmails,
                           Map<String, List<CertificateRevocationWrapper>> documentRevocation) {
        // Overall tsExpired only when the LAST timestamp has an expired TSA cert (it's assessed at now)
        boolean tsExpired = !tsExpiredFlags.isEmpty()
                && tsExpiredFlags.get(tsExpiredFlags.size() - 1);
        VBox card = new VBox(3);
        card.setPadding(new Insets(8, 10, 8, 10));
        card.setStyle(CARD_STYLE);

        if (header != null) {
            Label headerLabel = new Label(header);
            headerLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; " +
                    "-fx-text-fill: #003189; -fx-padding: 0 0 4 0;");
            card.getChildren().add(headerLabel);
        }

        boolean isTs = "TimeStampToken".equals(sig.getFormat());

        String rawLegalType = sig.getLegalType() != null
                ? sig.getLegalType()
                : sig.getIndication()
                  + (sig.getSubIndication() != null ? " / " + sig.getSubIndication() : "");

        boolean isSeal = !isTs && sig.isSeal();

        // 1. Jde o — simplified type s tooltipem jako ve Zjednodušeném přehledu
        String simplified = simplifyLegalType(rawLegalType);
        String tooltipText = legalTypeTooltip(simplified);
        String simplifiedText = nominativeLegalType(simplified);
        String simplifiedDisplay = sig.isCertification()
                ? "(certifikační) " + simplifiedText : simplifiedText;
        card.getChildren().add(rowWithTooltip(legalTypeLabel(simplified), simplifiedDisplay, tooltipText));
        String counterNote = counterSignatureNote(sig, elements);
        if (counterNote != null) card.getChildren().add(greyNote(counterNote));
        if (sig.isCertification()) {
            card.getChildren().add(certificationPermissionNote(sig.getCertificationPermission()));
        }
        // Zámek stojí vedle certifikačního omezení, ne místo něj: nese ho i obyčejný podpis,
        // který certifikační není, a právě takový je referenční uzamčený dokument.
        String lockNote = pdfLockNote(sig.getPdfLock(), isSeal);
        if (lockNote != null) card.getChildren().add(greyNote(lockNote));

        // 2. Stav platnosti
        // Neither a signature nor a seal (e.g. a standalone timestamp) always uses the
        // masculine grammatical forms below ("platný"/"neplatný"/"neznámý").
        boolean isSealGender = isSeal && !rawLegalType.contains("podpis");
        String indication = tsExpired ? "INDETERMINATE" : sig.getIndication();
        // S1: the signed content was altered. DSS 6.4 masks this as NO_SIGNING_CERTIFICATE_FOUND
        // (the failed message-digest check invalidates the signing-certificate candidate, and ISC
        // runs before CV), so its own indication understates a provably broken document. S1 reads
        // the digest matchers directly. Matches the DSS online service and EN 319 102-1, which
        // both give TOTAL_FAILED/HASH_FAILURE. Applied first so the rules below cannot undo it.
        if ("INDETERMINATE".equals(indication) && sig.getDetectedSituations().contains(1)) {
            indication = "TOTAL_FAILED";
        }
        // Přepis „REVOKED_NO_POE + situace 2 → neplatný" tu byl zrušen 27. 8. 2026 spolu s tímtéž
        // v ResultView a AdvisorView.displayedIndication(); zdůvodnění je u ResultView.buildCard().
        // Krátce: časové razítko je horní mez okamžiku podpisu, ne dolní, takže z revokace před
        // rozhodným okamžikem neplyne, že podpis vznikl až po ní. Výsledek zůstává neznámý —
        // shodně s on-line validátorem DSS.
        // S29: DSS said TOTAL_PASSED but the signing cert's only embedded revocation was
        // produced by a respondent whose cert had already expired at production time.
        // S43 no longer belongs here: since trust anchors carry a sunset date, DSS applies the
        // standard's reading of a withdrawn time-stamping service itself, and overriding a pass
        // it reached that way would be stricter than the standard the situation cites.
        if ("TOTAL_PASSED".equals(indication)
                && sig.getDetectedSituations().contains(29)) {
            indication = "INDETERMINATE";
        }
        // Read before the status bar rather than beside block 10, because the bar now names the
        // custom CRL among the reasons the verdict stands. Same guard as before, so the store — a
        // test that re-encodes every list it holds — is still consulted only where it can matter.
        boolean verdictCanRestOnRevocation =
                "TOTAL_PASSED".equals(indication) && !tsExpired;
        CertificateRevocationWrapper usedRevoc = verdictCanRestOnRevocation && sigRevocMap != null
                ? sigRevocMap.get(sig.getSignatureId()) : null;
        boolean verdictRestsOnCustomCrl = usedRevoc != null
                && usedRevoc.getRevocationType() != RevocationType.OCSP
                && !usedRevoc.isInternalRevocationOrigin()
                && isFromCustomCrlStore(usedRevoc, customCrls);

        String stavText;
        String stavColor;
        String stavNote = null;
        if ("TOTAL_PASSED".equals(indication)) {
            stavText = isSealGender ? "platná" : "platný";
            stavColor = "#1a7a1a";
        } else if ("TOTAL_FAILED".equals(indication)) {
            stavText = isSealGender ? "neplatná" : "neplatný";
            stavColor = "#cc0000";
            // Důvod 9 (revokace) sem už nedosáhne — viz zrušený přepis verdiktu výš.
            if (sig.getDetectedReasons().contains(7)) {
                stavNote = "(porušení integrity)";
            } else if (sig.getDetectedReasons().contains(28)) {
                // Situace 53: obsah nezměněn, neověřila se podpisová hodnota. Karta v Rozboru
                // říká totéž — obě místa musí mluvit stejně, jinak si čtenář vybere to horší.
                stavNote = "(podpisovou hodnotu nelze ověřit)";
            }
        } else {
            stavText = "neznámý";
            stavColor = "#9a6700";
            if (tsExpired) {
                stavNote = "(DSS ověřil podpis jako platný; AnEP jej zpřísnil na Neznámý, " +
                           "protože certifikát TSA expiroval a je zapnuto přísné ověřování razítek)";
            }
        }
        // „Prokázána pouze díky těmto okolnostem“ je závěr z měření, takže výčet smí být
        // neprázdný jen tam, kde se měřilo. Odvozený výčet říká slabší věc — že tolerance
        // byla zapnutá a dokument má něco, čeho se týká — a jako nutnost se vydávat nesmí.
        List<String> decisiveSettings = "TOTAL_PASSED".equals(indication)
                        && sig.isDecisiveSettingsMeasured()
                ? sig.getDecisiveSettings() : List.of();
        String decisiveCustomTime = "TOTAL_PASSED".equals(indication)
                ? sig.getDecisiveCustomTime() : null;
        boolean hasS20 = sig.getDetectedSituations().contains(20);
        boolean hasS21 = sig.getDetectedSituations().contains(21);
        // S48 — revokovaný certifikát, ke kterému chybí důkaz o čase podpisu. Pořadí je stejné
        // jako v Zjednodušeném přehledu; situace se navzájem vylučují už při detekci, takže
        // řetězec rozhoduje jen o tom, který text se vezme, ne o tom, co se zamlčí.
        String warnText = hasS20 ? ResultView.S20_WARNING_TEXT
                        : hasS21 ? ResultView.S21_WARNING_TEXT
                        : sig.getDetectedSituations().contains(48)
                                ? ResultView.revokedNoProofWarning(sig)
                        : sig.getDetectedSituations().contains(2)
                                ? ResultView.revokedBeforeDecisiveWarning(sig) : null;
        card.getChildren().add(stavRow(stavText, stavColor, stavNote, decisiveSettings,
                "TOTAL_PASSED".equals(indication) && sig.isDecisiveSettingsInterchangeable(),
                decisiveCustomTime, warnText,
                "TOTAL_PASSED".equals(indication) ? sig.getCustomTrustAnchorName() : null,
                verdictRestsOnCustomCrl ? usedRevoc : null));

        // 3. Formát podpisu / pečeti / časového razítka
        String formatLbl = isTs ? "Formát časového razítka:" : (isSeal ? "Formát pečeti:" : "Formát podpisu:");
        String formatVal = isTs ? timestampFormatLabel() : formatSignatureLabel(sig.getFormat());
        card.getChildren().add(row(formatLbl, formatVal));

        // Odsud dál je karta složená z pojmenovaných bloků, všechny zavřené. Řádky nad tímto
        // místem — čeho se nález týká, jak dopadl a v jakém je formátu — čte každý; všechno
        // ostatní je odpověď na otázku, kterou u konkrétního dokumentu klade jen někdo, a nadpisy
        // pod verdiktem jsou pak její obsah, ne předstupeň před ním. Bloky staví
        // AdvisorView.collapsibleBlock(), aby se chovaly stejně jako v Analýze: zabalený obsah se
        // schovává přes visible/managed, takže PrintUtil ho při exportu do PDF projde tak jako tak
        // a protokol odchází celý, i to, co má čtenář zavřené.
        addBlock(card, signerTitle(sig, isTs),
                dnBlock(sig, isTs, isSeal, signerEmails.get(sig.getSignatureId())), false);
        addBlock(card, isTs ? "Vizualizace razítka"
                       : isSeal ? "Vizualizace pečeti" : "Vizualizace podpisu",
                appearanceBlock(sig), false);
        addBlock(card, isSeal ? "Důvody pečetění" : "Důvody podpisu",
                reasonsBlock(sig, isTs, isSeal), false);
        addBlock(card, "Certifikát", certificateBlock(sig, isTs), false,
                certificateActions(signingCertBytes(sig)));
        addBlock(card, "Časová razítka", timestampsBlock(sig, isTs, indication), false);
        addBlock(card, "Evidenční záznamy", evidenceBlock(sig), false);
        addBlock(card, "Rozhodný okamžik ověření", decisiveInstantBlock(sig, isTs, isSeal), false);
        addBlock(card, "Kontrola revokace",
                revocationBlock(verdictCanRestOnRevocation ? usedRevoc : null,
                        verdictRestsOnCustomCrl,
                        documentRevocation.get(sig.getSignatureId()),
                        certSerial(signingCertBytes(sig))), false);
        addBlock(card, "Použité kryptografické algoritmy",
                algorithmsBlock(sig, isTs, isSeal), false);

        // Dvě lhůty — „Platnost lze ověřit do" a „Lhůta pro zachování ověřitelnosti" — tady
        // bývaly jako poslední dva řádky karty, s odůvodněním, že nemají čekat, až je někdo
        // rozbalí. Odstraněny: od té doby vznikla záložka Digitální kontinuita a ta na tutéž
        // otázku odpovídá pořádně — nejen datem, ale i tím, čím je dané, jak se liší podle
        // způsobu ověření a co se s tím dá udělat. Dvě místa s týmž datem znamenají dvě místa,
        // která se musí držet stejného výpočtu; jedno z nich přitom umí říct jen půlku.
        //
        // Rozšířený přehled tím zůstává u toho, co o podpisu platí, a nepřebírá roli rozcestníku
        // k úkonům. Viz ContinuityElementBlocks a ContinuityHorizon.

        return card;
    }

    // ------------------------------------------------------------------
    // Bloky karty
    // ------------------------------------------------------------------

    /**
     * Přidá blok, pokud má co ukázat — a před ním čáru, aby bylo vidět, kde jeden končí.
     *
     * <p>Prázdný blok se nepřidává vůbec. Nadpis, pod kterým nic není, je horší než chybějící
     * nadpis: čtenář ho rozbalí a musí sám odvodit, jestli se něco nenašlo, nebo nezobrazilo.
     */
    /**
     * Rámeček o odkazech z eLegalizace — vlastní karta za kartami prvků, nebo {@code null},
     * nenese-li dokument přílohu s otisky.
     *
     * <p>Vlastní karta, a ne blok uvnitř karty podpisu, protože odkaz je vlastnost
     * <b>dokumentu</b>: nese ho soubor, ne ten který podpis, a u dokumentu se třemi podpisy by
     * se týž blok třikrát opakoval. Rámeček to říká na první pohled — je vedle karet prvků,
     * ne v nich.
     *
     * <p>Značka {@code KEEP_TITLE} si vyžádá nadpis i v protokolu, který jinak nadpisy bloků
     * z Rozšířeného přehledu vynechává: řádky uvnitř mluví o jiném souboru, takže bez nadpisu
     * by se četly jako údaje o právě posuzovaném podpisu.
     */
    private VBox legalisationCard() {
        VBox content = legalisationBlock();
        if (content == null || content.getChildren().isEmpty()) return null;
        VBox card = new VBox(3);
        card.setPadding(new Insets(8, 10, 8, 10));
        card.setStyle(CARD_STYLE);
        VBox block = AdvisorView.collapsibleBlock("Odkazované dokumenty z eLegalizace",
                content, false);
        block.getProperties().put(PrintUtil.KEEP_TITLE, Boolean.TRUE);
        card.getChildren().add(block);
        return card;
    }

    private static void addBlock(VBox card, String title, VBox content, boolean expanded) {
        addBlock(card, title, content, expanded, null);
    }

    private static void addBlock(VBox card, String title, VBox content, boolean expanded,
                                 javafx.scene.Node actions) {
        if (content == null || content.getChildren().isEmpty()) return;
        card.getChildren().add(new Separator());
        card.getChildren().add(AdvisorView.collapsibleBlock(title, content, expanded,
                AdvisorView.BlockTint.NEUTRAL, actions));
    }

    /**
     * Odkazy „Ukaž“ a „Ulož“ do záhlaví bloku Certifikát, nebo {@code null}, nejsou-li bajty
     * certifikátu po ruce.
     *
     * <p>Přání uživatele 15. 10. 2026: certifikát podepisujícího zobrazit a uložit přímo
     * z Rozšířeného přehledu, ne až přes Analýzu nebo Detailní rozbor. Obě akce jsou tytéž jako
     * u důvodu s certifikátem v Analýze ({@code AdvisorView.certLinkParagraph}) — vlastní
     * prohlížeč s tlačítkem do systémového prohlížeče a uložení jako {@code .cer} v DER —,
     * aby se tatáž věc na dvou místech nechovala dvojím způsobem.
     */
    private HBox certificateActions(byte[] der) {
        if (der == null) return null;
        String linkStyle = "-fx-font-size: 12px; -fx-text-fill: #0a5ca8; -fx-padding: 0;"
                + " -fx-border-width: 0; -fx-font-weight: normal;";
        Hyperlink show = new Hyperlink("Ukaž");
        show.setStyle(linkStyle);
        Tooltip.install(show, new Tooltip("Zobrazí certifikát"));
        show.setOnAction(e -> {
            show.setVisited(false);
            ElementTextView.show("Certifikát",
                    cz.digitalnikontinuita.anep.core.ContainerInspector.describeCertificate(der),
                    der, "certifikat.cer", "Certifikát (*.cer)", "*.cer", ownerStage());
        });
        Hyperlink save = new Hyperlink("Ulož");
        save.setStyle(linkStyle);
        Tooltip.install(save, new Tooltip("Uloží certifikát do souboru"));
        save.setOnAction(e -> {
            save.setVisited(false);
            UiDialogs.saveBytes(der, "certifikat.cer", "Certifikát (*.cer)", "*.cer",
                    ownerStage());
        });
        HBox box = new HBox(10, show, save);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Stage ownerStage() {
        return root.getScene() != null && root.getScene().getWindow() instanceof Stage s
                ? s : null;
    }

    /**
     * Podpisový certifikát prvku — u podpisu či pečeti i u samostatného časového razítka;
     * {@code null}, nezná-li ho DSS nebo nejsou-li po ruce jeho bajty. Týž výběr jako
     * {@code AdvisorView.signingCertBytes}.
     */
    private byte[] signingCertBytes(SignatureSummary sig) {
        if (diagnosticData == null || sig.getSignatureId() == null) return null;
        CertificateWrapper cert = null;
        var sw = diagnosticData.getSignatureById(sig.getSignatureId());
        if (sw != null) {
            cert = sw.getSigningCertificate();
        } else {
            var ts = diagnosticData.getTimestampById(sig.getSignatureId());
            if (ts != null) cert = ts.getSigningCertificate();
        }
        byte[] der = cert != null ? cert.getBinaries() : null;
        return der != null && der.length > 0 ? der : null;
    }

    /** Sériové číslo certifikátu z jeho bajtů, nebo {@code null}, když je nejde přečíst. */
    private static BigInteger certSerial(byte[] der) {
        if (der == null) return null;
        try {
            return new org.bouncycastle.cert.X509CertificateHolder(der).getSerialNumber();
        } catch (Exception e) {
            return null;
        }
    }

    /** Nadpis prvního bloku, a zároveň popisek pro CN uvnitř něj. */
    private static String signerTitle(SignatureSummary sig, boolean isTs) {
        return isTs ? "Vydavatel časového razítka" : sig.personLabel();
    }

    /**
     * Blok „Odkazované dokumenty z eLegalizace“ — nebo {@code null}, nenese-li dokument přílohu
     * s otisky.
     *
     * <p>Odkaz z eLegalizace míří na dokument, který v souboru není: příloha
     * {@code legalisation-metadata.xml} nese jen jeho otisky. Ověřit se dá jedině tak, že ten
     * druhý dokument někdo předloží — což se děje v samostatném okně, klidně dřív, než dojde na
     * ověření samotného předloženého souboru. Blok proto neukazuje výsledek ověření podpisu,
     * nýbrž to, co uživatel o odkazu zjistil, ať už kdykoli.
     *
     * <p>Blok je na kartě každého autentizačního prvku, ačkoli mluví o dokumentu jako celku.
     * Je to tak schválně: čtenář kartu čte odshora dolů a otázka „na co ten dokument odkazuje“
     * patří k tomu, co drží v ruce, ne do zvláštní části, kterou by musel hledat.
     */
    private VBox legalisationBlock() {
        if (legalisationLink == null) return null;
        VBox body = blockBody();
        legalisationBodies.add(body);
        fillLegalisation(body);
        return body;
    }

    /** Překreslí všechna těla bloků — po kontrole, kterou uživatel provedl v okně. */
    private void refreshLegalisationBlocks() {
        for (VBox body : legalisationBodies) {
            body.getChildren().clear();
            fillLegalisation(body);
        }
    }

    private void fillLegalisation(VBox body) {
        var link = legalisationLink;
        if (link == null) return;

        body.getChildren().add(row("Příloha s otisky:",
                (link.attachmentName() != null ? link.attachmentName() : "legalisation-metadata.xml")
                + " — dokument nese otisky jiného dokumentu, ne ten dokument sám"));
        if (link.type() != null && !link.type().isBlank()) {
            body.getChildren().add(row("Druh záznamu:", legalisationTypeLabel(link.type())));
        }
        if (link.targetName() != null && !link.targetName().isBlank()) {
            body.getChildren().add(row("Odkazovaný dokument dle přílohy:", link.targetName()));
        }
        StringBuilder funkce = new StringBuilder();
        for (var d : link.digests()) {
            if (funkce.length() > 0) funkce.append(", ");
            funkce.append(d.function());
        }
        if (funkce.length() > 0) {
            body.getChildren().add(row("Otisky v příloze:", funkce.toString()));
        }

        java.util.List<cz.digitalnikontinuita.anep.core.LegalisationLinkLog.Step> chain =
                cz.digitalnikontinuita.anep.core.LegalisationLinkLog.chain(pdfSigFile);
        if (chain.isEmpty()) {
            body.getChildren().add(row("Kontrola otisků:",
                    "Odkazovaný dokument dosud nebyl předložen. Nabízí ho okno „Odkaz"
                    + " z eLegalizace“, které aplikace otevírá k dokumentům s touto přílohou;"
                    + " dokud jím neprojde, o odkazovaném dokumentu se nedá tvrdit nic."));
            return;
        }

        for (int i = 0; i < chain.size(); i++) {
            var step = chain.get(i);
            body.getChildren().add(new Separator());
            String poradi = chain.size() == 1 ? "Předložený dokument:"
                    : (i + 1) + ". předložený dokument:";
            body.getChildren().add(row(poradi, step.candidate().getName()));
            body.getChildren().add(row("Typ souboru:", pripona(step.candidate().getName())));
            body.getChildren().add(row("Velikost:", velikost(step.candidateSize())));
            body.getChildren().add(row("Poslední změna:", step.candidateModified() == null
                    ? "—" : TimeUtil.format(java.util.Date.from(step.candidateModified()))));
            body.getChildren().add(row("Kontrola otisků:", vysledek(step)));
            body.getChildren().add(row("Odkaz v předloženém dokumentu:",
                    dalsiOdkaz(step, i + 1 < chain.size())));
        }
    }

    /** {@code ORIGINAL} a {@code ARCH} jsou jediné dva druhy, které eLegalizace do přílohy píše. */
    private static String legalisationTypeLabel(String type) {
        return switch (type.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "ORIGINAL" -> "legalizační záznam (ORIGINAL)";
            case "ARCH" -> "podpisový arch (ARCH)";
            default -> type;
        };
    }

    /** Přípona souboru velkými písmeny; „bez přípony“, když ji jméno nemá. */
    private static String pripona(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toUpperCase(java.util.Locale.ROOT)
                : "bez přípony";
    }

    private static String velikost(long bytes) {
        if (bytes < 0) return "—";
        return String.format(java.util.Locale.forLanguageTag("cs"), "%,d B", bytes)
                + (bytes >= 1024
                        ? String.format(java.util.Locale.forLanguageTag("cs"), " (%,.1f kB)",
                                bytes / 1024.0)
                        : "");
    }

    /** Jak kontrola dopadla, včetně toho, které funkce se opravdu porovnaly. */
    private static String vysledek(cz.digitalnikontinuita.anep.core.LegalisationLinkLog.Step step) {
        var check = step.check();
        StringBuilder sb = new StringBuilder(switch (check.outcome()) {
            case MATCH -> "otisky souhlasí — je to dokument, na který příloha odkazuje";
            case MISMATCH -> "otisky nesouhlasí — buď jde o jiný dokument, nebo o tentýž"
                    + " po nějaké změně, i takové, která se na pohled neprojeví";
            case UNSUPPORTED -> "zkontrolovat nešlo: žádnou z uvedených hašovacích funkcí"
                    + " tato aplikace nezná";
            case ERROR -> "soubor se nepodařilo přečíst"
                    + (check.error() != null ? " (" + check.error() + ")" : "");
        });
        StringBuilder porovnano = new StringBuilder();
        for (var d : check.digests()) {
            if (!d.supported()) continue;
            if (porovnano.length() > 0) porovnano.append(", ");
            porovnano.append(d.function()).append(d.matches() ? " souhlasí" : " NESOUHLASÍ");
        }
        if (porovnano.length() > 0) sb.append(" — ").append(porovnano);
        return sb.toString();
    }

    /** Nese předložený dokument vlastní odkaz, a je-li tomu tak, došlo na něj? */
    private static String dalsiOdkaz(cz.digitalnikontinuita.anep.core.LegalisationLinkLog.Step step,
                                      boolean pokracuje) {
        if (step.candidateLink() == null) {
            return "nenese — řetěz odkazů tímto dokumentem končí";
        }
        return pokracuje
                ? "nese vlastní přílohu s otisky; jak dopadla její kontrola, stojí níž"
                : "nese vlastní přílohu s otisky, ta ale dosud zkontrolována nebyla";
    }

    private static VBox blockBody() {
        VBox body = new VBox(3);
        body.setPadding(new Insets(2, 0, 8, 0));
        return body;
    }

    /**
     * Všechny položky DN certifikátu, na kterém prvek stojí — od toho, kdo to je, přes organizaci,
     * kterou zastupuje, k adrese a kontaktům. Pořadí z certifikátu se nedrží schválně, viz
     * {@link DnUtil#displayRank}.
     *
     * <p>Vynechány jsou tři: GIVENNAME a SURNAME, protože totéž jméno stojí v CN hned nahoře, a
     * serialNumber, který jméno neupřesňuje — je to identifikátor držitele (u českých
     * kvalifikovaných certifikátů číslo dokladu) a patří k certifikátu, kde ho blok
     * {@link #certificateBlock} uvádí i s výkladem, co za číslo to je.
     *
     * <p>Položka, pro kterou aplikace nemá české jméno, se ukáže pod svým OID. Blok slibuje celý
     * DN; zahodit údaj proto, že se neví, jak ho nazvat, by ten slib tiše porušilo.
     */
    private VBox dnBlock(SignatureSummary sig, boolean isTs, boolean isSeal, String email) {
        VBox body = blockBody();
        String cnLabel = signerTitle(sig, isTs) + ":";
        // Řádky se sbírají s pořadovým číslem a řadí až nakonec, aby se mezi položky DN dal
        // vložit i údaj, který v DN není — e-mail z rozšíření — a přistál tam, kam patří.
        record Item(int rank, String label, String value) { }
        List<Item> items = new java.util.ArrayList<>();
        boolean cnShown = false;
        boolean emailShown = false;
        for (DnUtil.Attr a : DnUtil.attributes(sig.getSignerCertDN())) {
            if (DnUtil.isGivenNameOrSurname(a.key()) || DnUtil.isSubjectSerialNumber(a.key())) {
                continue;
            }
            int rank = DnUtil.displayRank(a.key());
            if (DnUtil.isCommonName(a.key())) {
                items.add(new Item(rank, cnLabel, a.value()));
                cnShown = true;
            } else if (DnUtil.isOrganizationIdentifier(a.key())) {
                String[] org = DnUtil.organizationIdentifierEntry(a.value());
                if (org != null) items.add(new Item(rank, org[0] + ":", org[1]));
            } else {
                items.add(new Item(rank, DnUtil.label(a.key()) + ":", a.value()));
                if (DnUtil.isEmail(a.key())) emailShown = true;
            }
        }
        // E-mail držitele i tam, kde ho DN nemá — v korpusu je to devět certifikátů z deseti,
        // protože české kvalifikované certifikáty ho nosí v SubjectAltName. Viz
        // {@link #buildSignerEmails}.
        //
        // Nepřipíše se, pokud táž adresa v bloku už stojí. U certifikátů pro šifrovanou poštu
        // bývá adresa přímo obsahem CN, takže by se dva řádky pod sebou lišily jen popiskem
        // a četly by se jako chyba (změřeno na D:\\tmp\\emailovy certifikat).
        boolean alreadyListed = email != null
                && items.stream().anyMatch(i -> email.equalsIgnoreCase(i.value()));
        if (!emailShown && !alreadyListed && email != null && !email.isBlank()) {
            items.add(new Item(DnUtil.displayRank("1.2.840.113549.1.9.1"),
                    "E-mailová adresa:", email));
        }
        // DN bez CN je vzácné, ale jméno, které aplikace zná odjinud, se kvůli tomu ztratit nesmí:
        // u samostatného razítka je to jméno razítkovací jednotky z protokolu DSS.
        if (!cnShown && sig.getSignerDisplayName() != null && !sig.getSignerDisplayName().isBlank()) {
            items.add(new Item(-1, cnLabel, sig.getSignerDisplayName()));
        }
        // Stabilní řazení: položky téhož druhu — typicky několik OU — si mezi sebou pořadí
        // z certifikátu podrží, protože v něm nesou strukturu, kterou nemá co přerovnávat.
        items.sort(java.util.Comparator.comparingInt(Item::rank));
        for (Item item : items) body.getChildren().add(row(item.label(), item.value()));
        return body;
    }

    /**
     * Vizualizace — táž podoba jako blok „Vizualizace podpisu" v Analýze.
     *
     * <p>Rozšířený přehled měl vlastní kopii, a ta se od Analýzy lišila pořadím i tím, co v ní
     * bylo. Sjednoceno 4. 9. 2026 na jediný zdroj: co čtenář vidí na jedné záložce, vidí i na
     * druhé, a úprava textu se nemůže provést jen na půlce míst.
     */
    private VBox appearanceBlock(SignatureSummary sig) {
        if (!AdvisorView.hasAppearanceToReport(sig)) return null;
        VBox body = blockBody();
        body.setFillWidth(true);
        body.getChildren().add(AdvisorView.buildAppearanceSection(
                sig, sig.getSignatureAppearancePng(), false));
        return body;
    }

    /** Co podepisující osoba uvedla v podpisovém slovníku PDF: důvod, místo, kontakt. */
    private VBox reasonsBlock(SignatureSummary sig, boolean isTs, boolean isSeal) {
        if (isTs) return null;
        String reason = sig.getPdfReason();
        String location = sig.getPdfLocation();
        String contact = sig.getPdfContactInfo();
        VBox body = blockBody();
        if (reason != null && !reason.isBlank()) {
            body.getChildren().add(row(isSeal ? "Důvod pečetění:" : "Důvod podpisu:", reason));
        }
        if (location != null && !location.isBlank()) {
            body.getChildren().add(row(isSeal ? "Místo pečetění:" : "Místo podpisu:", location));
        }
        if (contact != null && !contact.isBlank()) {
            body.getChildren().add(row("Kontakt:", contact));
        }
        return body;
    }

    /**
     * Certifikát, na kterém prvek stojí: co je zač, kdo ho vydal, na jak dlouho a jak je na tom
     * dnes. Identifikátor držitele (serialNumber v DN) je tady, ne v bloku o osobě — je to číslo
     * dokladu či registrace, ne další část jména, a bez popisu, co za číslo to je, by nad jménem
     * jen mátlo.
     */
    static final String DIRECTIVE_CERT_TOOLTIP = "Certifikát byl vydán před 1. 7. 2016, kdy se"
            + " začalo používat nařízení (EU) č. 910/2014 (eIDAS). Vznikl tedy ještě podle"
            + " směrnice 1999/93/ES o zásadách Společenství pro elektronické podpisy.\n\n"
            + "Podle čl. 51 odst. 2 nařízení eIDAS se kvalifikované certifikáty vydané fyzickým"
            + " osobám podle směrnice považují za kvalifikované certifikáty pro elektronický podpis"
            + " podle eIDAS, a to až do skončení své platnosti.\n\n"
            + "Údaj o účelu certifikátu (QcType), který dnešní kvalifikované certifikáty nesou,"
            + " takový certifikát zpravidla nemá — ten zavedly až normy vydané pro eIDAS.";

    static final String SYSTEM_CERT_TOOLTIP = "Systémový certifikát zavedl zákon č. 227/2000 Sb.,"
            + " o elektronickém podpisu (novelou č. 440/2004 Sb.), jako podklad pro elektronickou"
            + " značku. Značku na rozdíl od podpisu nevytváří člověk, ale označující osoba —"
            + " zpravidla automaticky, informačním systémem (výpisy z rejstříků, potvrzení"
            + " o doručení apod.).\n\n"
            + "Elektronická značka založená na kvalifikovaném systémovém certifikátu byla"
            + " uznávanou elektronickou značkou. Kvalifikovaným ho činil český zákon, ne směrnice"
            + " 1999/93/ES ani nařízení eIDAS — proto certifikát nenese příznak QcCompliance"
            + " a knihovna DSS, která posuzuje jen kvalifikovanost podle evropských pravidel, ho za"
            + " kvalifikovaný nepovažuje.\n\n"
            + "Zákon č. 227/2000 Sb. zrušil s účinností od 19. 9. 2016 zákon č. 297/2016 Sb."
            + " Nařízení eIDAS elektronickou značku nezná; nejbližší obdobou je elektronická pečeť.";

    private VBox certificateBlock(SignatureSummary sig, boolean isTs) {
        VBox body = blockBody();
        if (!isTs && (sig.getCertNotAfter() != null || sig.getCertKind() != null)) {
            String certKindDisplay;
            if (sig.isCertSelfSigned()) {
                certKindDisplay = "certifikát s vlastním podpisem (self-signed)";
            } else if (sig.isCertSystem()) {
                certKindDisplay = sig.getCertKind();
            } else if (sig.isCertQcCompliant()
                    && sig.getCertQcTypeDescriptions().contains("qc-type-esign")) {
                certKindDisplay = "kvalifikovaný certifikát pro elektronický podpis";
            } else if (sig.isCertQcCompliant()
                    && sig.getCertQcTypeDescriptions().contains("qc-type-eseal")) {
                certKindDisplay = "kvalifikovaný certifikát pro elektronickou pečeť";
            } else if (sig.isCertQcCompliant()) {
                // Bez QcType: u certifikátu podle směrnice 1999/93/ES běžný stav, ne vada.
                // ReportMapper mu účel dopočítal, jinak zbude holé „kvalifikovaný certifikát".
                certKindDisplay = sig.getCertKind() != null
                        ? sig.getCertKind() : "kvalifikovaný certifikát";
            } else {
                certKindDisplay = "nekvalifikovaný certifikát";
            }
            if (sig.isCertSystem()) {
                body.getChildren().add(rowWithTooltip("Druh certifikátu:", certKindDisplay,
                        SYSTEM_CERT_TOOLTIP));
            } else if (sig.isCertIssuedUnderDirective()) {
                body.getChildren().add(rowWithTooltip("Druh certifikátu:",
                        certKindDisplay + ", vydaný ještě podle směrnice 1999/93/ES",
                        DIRECTIVE_CERT_TOOLTIP));
            } else {
                body.getChildren().add(row("Druh certifikátu:", certKindDisplay));
            }
            // U systémového certifikátu se příznak nevypisuje — viz AdvisorView.certKindText.
            if (sig.getCertQscd() != null && !sig.isCertSystem()) {
                body.getChildren().add(row("S příznakem QSCD:", sig.getCertQscd() ? "ano" : "ne"));
            }
        }
        String issuerCN = DnUtil.dnField(sig.getCertIssuerDN(), "2.5.4.3", "CN");
        if (issuerCN != null && !issuerCN.isBlank()) {
            body.getChildren().add(row("Vydavatel:", issuerCN));
        }
        String issuerO = DnUtil.dnField(sig.getCertIssuerDN(), "2.5.4.10", "O");
        if (issuerO != null && !issuerO.isBlank()) {
            body.getChildren().add(row("Vydavatel — organizace:", issuerO));
        }
        String issuerC = DnUtil.dnField(sig.getCertIssuerDN(), "2.5.4.6", "C");
        if (issuerC != null && !issuerC.isBlank()) {
            body.getChildren().add(row("Vydavatel — stát:", issuerC));
        }
        if (sig.getCertSerialNumber() != null) {
            String dec = sig.getCertSerialNumber();
            String hex = cz.digitalnikontinuita.anep.core.ContainerInspector.hexNumber(dec);
            body.getChildren().add(row("Sériové číslo certifikátu:", dec + " (" + hex + ")"));
        }
        if (sig.getCertNotBefore() != null && sig.getCertNotAfter() != null) {
            body.getChildren().add(row("Řádná doba platnosti:",
                    TimeUtil.format(sig.getCertNotBefore()) + " – "
                    + TimeUtil.format(sig.getCertNotAfter())));
        }
        String certStatus;
        if (sig.isCertRevoked()) {
            certStatus = sig.getCertRevokedAt() != null
                    ? "revokovaný k " + TimeUtil.format(sig.getCertRevokedAt())
                    : "revokovaný (datum revokace neznámé)";
        } else if (sig.getCertNotAfter() != null) {
            certStatus = sig.getCertNotAfter().isBefore(java.time.Instant.now())
                    ? "prošlý" : "stále platný";
        } else {
            certStatus = null;
        }
        if (certStatus != null) body.getChildren().add(row("Aktuální stav:", certStatus));

        // Položek serialNumber může být víc (doklad i klientské číslo vydavatele) — každá
        // na vlastní řádek; viz DnUtil.serialNumberEntries.
        for (String[] subjectSerial : DnUtil.serialNumberEntries(sig.getSignerCertDN())) {
            body.getChildren().add(row(subjectSerial[0], subjectSerial[1]));
        }
        String ikMpsv = sig.getIkMpsv();
        if (ikMpsv != null && !ikMpsv.isBlank()) body.getChildren().add(row("IK MPSV:", ikMpsv));
        return body;
    }

    /**
     * Časová razítka prvku, každé s vydavatelem a koncem platnosti certifikátu razítkovací
     * autority. U samostatného razítka je tím jediným údajem čas, který samo nese.
     */
    private VBox timestampsBlock(SignatureSummary sig, boolean isTs, String indication) {
        VBox body = blockBody();
        if (isTs) {
            if (sig.getSigningTime() != null) {
                // Samostatné razítko nemá pod sebou žádné jiné, které by mu dalo důkaz existence:
                // jeho platnost je rovnou verdikt karty a certifikát razítkovací autority je ten,
                // který o něm rozhoduje. Táž úvaha, jakou dělá ContinuityHorizon.anep() ve větvi
                // pro prvek typu razítko — po expiraci toho certifikátu drží razítko už jen
                // kvalifikovaný status služby, tedy nenastalý sunset.
                boolean valid = "TOTAL_PASSED".equals(indication);
                // Samostatné razítko je prvek, ne položka výčtu, a údaje z důvěryhodného
                // seznamu proto nese souhrn, ne TimestampEntry — otázka je ale táž a odpověď
                // se skládá týmž pravidlem.
                body.getChildren().add(timestampRow("Čas připojení razítka:",
                        sig.isQualified(), valid,
                        valid && heldBySunset(sig.getCertNotAfter(), java.time.Instant.now()),
                        TimeUtil.format(sig.getSigningTime()), null,
                        trustListNotes(sig.isQualified(), sig.getCertNotAfter(),
                                sig.getTsQualifiedFrom(), sig.isAnchorCert(), valid)));
            }
            return body;
        }

        List<TimestampEntry> entries = sig.getAllTimestamps();
        // Only records that actually prove something get mentioned among the time-stamps.
        // A record whose digest no longer matches the document, or whose time-stamp does not
        // hold up, protects nothing here — and a line saying "and one more in the evidence
        // record" would be read as the document being covered, which is the opposite of what
        // happened. Such a record is not hidden; its own block states plainly why it fails.
        List<TimestampEntry> recordTs = sig.getEvidenceRecords().stream()
                .filter(EvidenceRecordEntry::providesProof)
                .flatMap(e -> e.timestamps().stream()).toList();
        // Blok bez jediného razítka se nezobrazuje vůbec — věta „žádné" pod nadpisem „Časová
        // razítka" je nadpis navíc, ne informace navíc.
        if (entries.isEmpty() && recordTs.isEmpty()) return null;

        if (entries.isEmpty()) {
            // Saying only "žádné" next to a record that carries one is a half-truth: the
            // signature really has no time-stamp of its own, but the document is time-stamped.
            body.getChildren().add(row("Časová razítka:",
                    "žádné v podpisu; v evidenčním záznamu "
                    + CzechPlural.count(recordTs.size(), "razítko", "razítka", "razítek")
                    + " (podrobnosti v bloku Evidenční záznamy)"));
            return body;
        }
        for (int i = 0; i < entries.size(); i++) {
            TimestampEntry e = entries.get(i);
            String kind = e.tsKind() != null ? e.tsKind() : "sig";
            String kindSuffix = switch (kind) {
                case "doc"   -> " (dokumentové)";
                case "vri", "vri_bare" -> " (v dokumentu / VRI)";
                case "content" -> " (obsahové — před podpisem)";
                default      -> " (v podpisu)";
            };
            String label = (entries.size() == 1 ? "Časové razítko" : "Razítko č. " + (i + 1))
                    + kindSuffix + ":";
            StringBuilder val = new StringBuilder(TimeUtil.format(e.time()));
            if (e.issuer() != null && !e.issuer().isBlank()) {
                val.append("  —  vydavatel: ").append(e.issuer());
            }
            if (e.notAfter() != null) {
                val.append("  —  platnost certifikátu TSA do: ").append(TimeUtil.format(e.notAfter()));
            }
            // Certifikát razítkovací autority se posuzuje k okamžiku, ke kterému se na platnost
            // tohohle razítka ptáme — u posledního teď, u ostatních ke vzniku toho následujícího,
            // které jim dalo důkaz existence. Totéž pravidlo, jakým se počítají tsExpiredFlags.
            boolean sunset = e.passed() && heldBySunset(e.notAfter(), assessedAt(entries, i));
            // Při vypnutém „Ověřování časových razítek podle standardů" si takové razítko
            // aplikace sama nezapočítá — pruh nad kartou hlásí zpřísnění na neznámý výsledek —
            // a slovo „platné" by mu odporovalo. Zůstane holý řádek jako dosud.
            boolean valid = e.passed() && (timestampStandardsMode || !sunset);
            // A bare VRI token sits beside a decisive instant it did not set, which needs
            // explaining — but in an icon, not in the label, which has to stay short.
            body.getChildren().add(timestampRow(label, e.qualified(), valid,
                    valid && sunset, val.toString(),
                    "vri_bare".equals(kind) ? VRI_BARE_NOTE : null,
                    trustListNotes(e, valid, assessedAt(entries, i))));
            if (!e.passed()) {
                Label errLbl = new Label(timestampFailureLine(e, provenInTime(entries, i)));
                errLbl.setStyle("-fx-text-fill: #cc0000;");
                Region indent = new Region();
                indent.setMinWidth(LBL_WIDTH + 4);
                body.getChildren().add(new HBox(indent, errLbl));
            }
        }
        // The signature has time-stamps of its own and the record adds more. Name them here too,
        // so the count in this block is not quietly short of what the document actually carries.
        if (!recordTs.isEmpty()) {
            body.getChildren().add(row("Dále v evidenčním záznamu:",
                    CzechPlural.count(recordTs.size(), "razítko", "razítka", "razítek")
                    + " (podrobnosti v bloku Evidenční záznamy)"));
        }
        appendSequenceRow(body, entries);
        return body;
    }

    /** Zelená kladného stavu — táž, kterou má pruh „Stav platnosti“ u platného prvku. */
    private static final String OK_GREEN = "#1a7a1a";

    /** Hnědá upřesňujících poznámek, stejná jako všude jinde v aplikaci. */
    private static final String NOTE_BROWN = "#7a4a00";

    /**
     * Poznámka k razítku, které obstálo jen díky nenastalému sunsetu.
     *
     * <p>Řádek přiznává, na čem ta platnost stojí: certifikát razítkovací autority už doslouží,
     * a razítko drží jenom to, že služba je na důvěryhodných seznamech dosud vedená jako
     * kvalifikovaná. Kdo posuzuje razítko podle řádné doby platnosti certifikátu — a tak to dělá
     * řada tuzemských validátorů — dojde k jinému závěru. Táž věc, o které mluví blok „Kde
     * ověření nebude úspěšné“ v Analýze, jen tady u konkrétního razítka.
     */
    /**
     * Věty, které vysvětlují, co na řádku o razítku vypadá jako rozpor.
     *
     * <p>Otázka uživatele z 21. 9. 2026 nad „ZGHO-fin2016-1H-NotREV-K-HR-1.pdf": razítko je
     * <b>platné, ale nekvalifikované</b>, ačkoli je vydala služba, která kvalifikovaný status má —
     * a je platné <b>i po expiraci</b> certifikátu té autority, který je na témž řádku vypsaný.
     * Obojí je správně a obojí plyne z důvěryhodného seznamu, jenže čtenář to odtud nemá jak
     * poznat.
     *
     * <p>Obě věty proto stojí jen tam, kde ten rozpor opravdu nastane: první u nekvalifikovaného
     * razítka, jehož služba kvalifikovaný zápis <b>má</b>, jen začíná až po jeho vzniku; druhá
     * u platného razítka, jehož certifikát už expiroval a je sám kotvou důvěry.
     */
    static List<String> trustListNotes(TimestampEntry e, boolean valid) {
        return trustListNotes(e, valid, java.time.Instant.now());
    }

    /**
     * @param assessedAt okamžik, k němuž se posuzuje platnost certifikátu — u razítka kryté
     *        dalším razítkem vznik toho následujícího ({@link #assessedAt(List, int)}), jinak
     *        současnost. Bez tohohle parametru věta o kotvě důvěry lhala: razítko kryté
     *        nepřerušeným řetězem obstojí, i když jeho certifikát dneska už neplatí, protože
     *        v okamžiku, kdy dostalo důkaz existence, ještě platil — s kotvou důvěry to nemá
     *        co dělat.
     */
    static List<String> trustListNotes(TimestampEntry e, boolean valid,
                                       java.time.Instant assessedAt) {
        return trustListNotes(e.qualified(), e.notAfter(), e.qualifiedFrom(), e.anchorCert(),
                valid, assessedAt);
    }

    static List<String> trustListNotes(boolean qualified, java.time.Instant notAfter,
                                       java.time.Instant qualifiedFrom, boolean anchorCert,
                                       boolean valid) {
        return trustListNotes(qualified, notAfter, qualifiedFrom, anchorCert, valid,
                java.time.Instant.now());
    }

    static List<String> trustListNotes(boolean qualified, java.time.Instant notAfter,
                                       java.time.Instant qualifiedFrom, boolean anchorCert,
                                       boolean valid, java.time.Instant assessedAt) {
        List<String> notes = new java.util.ArrayList<>(2);
        if (!qualified && qualifiedFrom != null) {
            notes.add("* kvalifikovaný status má tato služba v důvěryhodném seznamu až od "
                    + TimeUtil.format(qualifiedFrom) + "; razítko vzniklo dřív, a proto se"
                    + " za kvalifikované nepočítá — posuzuje se to k okamžiku jeho vzniku");
        }
        if (valid && anchorCert && notAfter != null
                && notAfter.isBefore(assessedAt)) {
            notes.add("* certifikát autority časového razítka je v důvěryhodném seznamu uveden"
                    + " přímo, takže je sám kotvou důvěry; jeho vlastní platnost se proto"
                    + " neposuzuje — díky tomu může být razítko ověřeno jako platné"
                    + " i po expiraci certifikátu");
        }
        return notes;
    }

    private static final String SUNSET_ONLY_NOTE =
            "* platné jen při ověřování dle standardů (nikoli při striktním ověřování dle"
            + " platnosti certifikátu)";

    /**
     * Okamžik, k němuž se posuzuje platnost certifikátu autority i-tého razítka: vznik toho
     * následujícího, obstálo-li samo, jinak současnost. Razítko kryté dalším razítkem má důkaz
     * existence a jeho certifikát tedy nemusí platit dodnes — táž úvaha, jakou dělá výpočet
     * {@code tsExpiredFlags} nad DiagnosticData.
     */
    private static java.time.Instant assessedAt(List<TimestampEntry> entries, int i) {
        if (i + 1 < entries.size()) {
            TimestampEntry next = entries.get(i + 1);
            if (next.passed() && next.time() != null) return next.time();
        }
        return java.time.Instant.now();
    }

    /**
     * Dokládá pozdější razítko, že tohle vzniklo ještě za platnosti certifikátu své autority?
     *
     * <p>Táž úvaha jako u {@link #heldBySunset}, jen obrácená: tam se ptá razítka, které obstálo,
     * tady toho, které neobstálo. {@link #assessedAt} vrací u posledního razítka současnost —
     * a to důkaz není, proto ta podmínka na {@code isBefore(now)}.
     */
    static boolean provenInTime(List<TimestampEntry> entries, int i) {
        if (entries == null || i < 0 || i >= entries.size()) return false;
        TimestampEntry e = entries.get(i);
        java.time.Instant when = assessedAt(entries, i);
        return e.notAfter() != null && when != null
                && !when.isAfter(e.notAfter())
                && when.isBefore(java.time.Instant.now());
    }

    /** Certifikát razítkovací autority už k rozhodnému okamžiku neplatil — drží to jen sunset. */
    private static boolean heldBySunset(java.time.Instant notAfter, java.time.Instant when) {
        return notAfter != null && notAfter.isBefore(when);
    }

    /**
     * Řádek o jednom časovém razítku: „kvalifikované, platné, přidáno: …“.
     *
     * <p>Slova, která nesou dobrou zprávu — {@code kvalifikované} a {@code platné} — jsou zeleně
     * a tučně, a jen tam, kde platí obojí: zelené „kvalifikované“ u razítka, které neobstálo, by
     * chválilo něco, co se nepovedlo. Poznámka o sunsetu jde na vlastní řádek hodnoty hnědě,
     * tedy tak, jak aplikace píše upřesnění všude jinde.
     *
     * @param tail zbytek hodnoty za „přidáno: “ — čas, vydavatel, konec platnosti certifikátu
     */
    /**
     * Řádek o razítku, které neobstálo — a stručně i proč.
     *
     * <p>Do 15. 9. 2026 stálo v tom řádku jen „platnost razítka se nepodařilo ověřit“ a čtenář
     * se příčinu v téhle záložce nedozvěděl nikde; musel do Analýzy za důvodem 21. Přitom ji model
     * nese — {@link TimestampEntry#failureCause()} — a je zařazená podle klíčů hlášení DSS,
     * tedy podle toho, co verdikt <b>opravdu drží</b>.
     *
     * <p><b>Rozhodující příčina není vždy ta nápadná, a proto se uvádí obojí.</b> U
     * {@code SHA1/PAdES LTA uznavany 2015 SHA1.pdf} — dokumentu, který má SHA-1 rovnou ve
     * jménu — dává DSS dílčí indikaci {@code CRYPTO_CONSTRAINTS_FAILURE_NO_POE}, ale zdejší
     * zařazení podle klíčů hlášení dává přednost expiraci certifikátu autority — a má k tomu
     * důvod: při zavádění důvodu 21 se změřilo, že po zapnutí ústupku k SHA-1 se indikace
     * nezmění. Napsat do závorky jen „SHA1“ by tedy čtenáře poslalo zapnout ústupek, který mu
     * nepomůže; zamlčet SHA-1 úplně by zase zatajilo to, co na razítku pozná na první pohled.
     * Proto rozhodující příčina napřed a zastaralý otisk za čárkou (pokyn uživatele
     * 15. 9. 2026):
     *
     * <pre>platnost razítka se nepodařilo ověřit (prošlý certifikát autority bez důkazu
     * o čase, SHA1)</pre>
     *
     * <p>Kde otisk rozhoduje sám ({@code CRYPTO}), čárka by větu roztrhla nadvakrát o tomž —
     * název se tam proto připojí rovnou k ní: „zastaralý kryptografický algoritmus SHA1“.
     *
     * <p>Třetí položkou je <b>odebraný kvalifikovaný status</b> služby, která razítko vydala
     * ({@link TimestampEntry#statusWithdrawn()}). Stojí před názvem otisku, protože říká o razítku
     * víc — týká se důvěry ve službu, kdežto otisk jen síly použité matematiky. Do závorky
     * přibyla až poté, co uživatel doložil, že se ta otázka dá zodpovědět per razítko:
     * jeden z dávno uchovaných kontejnerů ve sbírce.
     * Do té doby ji AnEP věděl jen za celý podpis (situace 43).
     *
     * <p>Bez zařazení ({@code OTHER}, nebo příčina neznámá) zůstává řádek holý jako dosud.
     * Obecná závorka typu „(jiný důvod)“ by čtenáři neřekla nic a budila dojem, že aplikace
     * něco tají.
     */
    private static String timestampFailureLine(TimestampEntry e) {
        return timestampFailureLine(e, false);
    }

    /**
     * @param provenInTime pozdější razítko dokládá, že tohle vzniklo ještě za platnosti
     *                     certifikátu své autority — viz {@link #failureReason}
     */
    private static String timestampFailureLine(TimestampEntry e, boolean provenInTime) {
        String why = failureReason(e.failureCause(), provenInTime);
        String weak = e.weakDigest();
        boolean cryptoDecides = e.failureCause() == SignatureSummary.TimestampFailure.CRYPTO;
        List<String> parts = new ArrayList<>();
        // Rozhodující příčina napřed. Kde rozhoduje právě otisk, připojí se jeho název rovnou k ní
        // — čárka by větu roztrhla nadvakrát o tomž.
        if (why != null) parts.add(cryptoDecides && weak != null ? why + " " + weak : why);
        // Odebraný status před algoritmem: říká o razítku víc — týká se důvěry ve službu, kdežto
        // otisk jen síly použité matematiky.
        if (e.statusWithdrawn()) parts.add("odebraný kvalifikovaný status");
        if (weak != null && !cryptoDecides) parts.add(weak);
        return "platnost razítka se nepodařilo ověřit"
                + (parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")");
    }

    /**
     * Stručně česky, co razítko složilo; {@code null}, když to zdejší výklad nerozlišuje.
     *
     * <p><b>„Bez důkazu o čase“ se smí říct jen tam, kde ten důkaz opravdu chybí.</b> Nález
     * uživatele z 20. 9. 2026: podpisové razítko dostalo popisek „prošlý certifikát autority bez
     * důkazu o čase“, ačkoli v témže dokumentu leží dokumentové razítko o měsíc mladší, které ho
     * v čase fixuje — a fixuje ho <b>uvnitř</b> platnosti certifikátu jeho autority. Že důkaz
     * existuje, ví i DSS: v podrobném protokolu u toho razítka stojí
     * {@code ValidationTime="TIMESTAMP_POE_TIME"} právě s časem toho dokumentového razítka.
     *
     * <p>Co v takovém případě platí dál: certifikát autority je opravdu prošlý a ověření se
     * dokončit nepodařilo ({@code PSV_IPTVC_ANS}, „past time-stamp validation is not
     * conclusive“) — jen to není tím, že by chybělo, čím dřívější existenci doložit.
     *
     * @param provenInTime pozdější razítko dokládá dřívější existenci, a to ještě za platnosti
     *                     certifikátu autority tohohle razítka
     */
    static String failureReason(SignatureSummary.TimestampFailure cause,
                                boolean provenInTime) {
        if (cause == null) return null;
        return switch (cause) {
            case CRYPTO -> "zastaralý kryptografický algoritmus";
            case EXPIRED_NO_POE -> provenInTime
                    ? "prošlý certifikát autority; ověřit ani podle pozdějšího razítka se to"
                      + " nepodařilo"
                    : "prošlý certifikát autority bez důkazu o čase";
            case REVOKED -> "odvolaný certifikát autority";
            case NO_CHAIN -> "neznámý vydavatel";
            case NO_REVOCATION -> "neznámý stav odvolání autority";
            case BROKEN -> "porušený otisk razítka";
            case NO_DATA -> "nebyla předložena orazítkovaná data";
            case OTHER -> null;
        };
    }

    private HBox timestampRow(String labelText, boolean qualified, boolean valid,
                              boolean sunsetOnly, String tail, String tooltipText,
                              List<String> notes) {
        List<javafx.scene.Node> runs = new java.util.ArrayList<>();
        String qualifiedWord = qualified ? "kvalifikované" : "nekvalifikované";
        runs.add(qualified && valid ? greenRun(qualifiedWord) : plainRun(qualifiedWord));
        if (valid) {
            runs.add(plainRun(", "));
            runs.add(greenRun("platné"));
        }
        runs.add(plainRun(", přidáno: " + tail));
        if (sunsetOnly) runs.add(brownRun("\n" + SUNSET_ONLY_NOTE));
        for (String note : notes) runs.add(brownRun("\n" + note));
        return richRow(labelText, runs, tooltipText);
    }

    private static Text plainRun(String text) {
        Text t = new Text(text);
        t.setStyle("-fx-fill: #111111;");
        return t;
    }

    private static Text greenRun(String text) {
        Text t = new Text(text);
        t.setStyle("-fx-fill: " + OK_GREEN + "; -fx-font-weight: bold;");
        return t;
    }

    private static Text brownRun(String text) {
        Text t = new Text(text);
        t.setStyle("-fx-fill: " + NOTE_BROWN + ";");
        return t;
    }

    /**
     * Řádek, jehož hodnota je poskládaná z barevných úseků, s volitelnou ⓘ bublinou.
     *
     * <p>Hodnota je {@code TextFlow}, ne {@code TextArea} jako u {@link #row(String, String)} —
     * jinak by se jednotlivá slova nedala obarvit. Výběr myší tím odpadá, proto je nad ní stejné
     * kontextové „Kopírovat“, jaké má {@link #rowWithTooltip}.
     */
    private HBox richRow(String labelText, List<javafx.scene.Node> runs, String tooltipText) {
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111;");
        lbl.setMinWidth(LBL_WIDTH);

        String plainValue = runs.stream()
                .map(n -> n instanceof Text t ? t.getText() : "")
                .collect(java.util.stream.Collectors.joining());

        TextFlow flow = new TextFlow(runs.toArray(new javafx.scene.Node[0]));
        if (tooltipText != null) flow.getChildren().add(infoIcon(tooltipText));
        // Velikost se sází tady, protože Text ji dědí a hodnota musí sedět s řádky okolo.
        flow.setStyle("-fx-font-size: 13px;");

        MenuItem copyItem = new MenuItem("Kopírovat");
        copyItem.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(plainValue);
            Clipboard.getSystemClipboard().setContent(cc);
        });
        ContextMenu copyMenu = new ContextMenu(copyItem);
        // TextFlow is a Node, not a Control, so the menu has to be popped up by hand.
        flow.setOnContextMenuRequested(e -> copyMenu.show(flow, e.getScreenX(), e.getScreenY()));
        HBox.setHgrow(flow, Priority.ALWAYS);

        HBox row = new HBox(4, lbl, flow);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    /**
     * Souhrn na konci bloku: odkdy dokdíž sahají platná razítka a jestli mezi nimi není mezera.
     *
     * <p>Při jediném platném razítku se nepíše nic — posloupnost o jednom prvku nemá co
     * shrnovat a řádek by jen zopakoval čas, který stojí o kus výš.
     *
     * <p><b>Přerušení se počítá, netvrdí:</b> pravidlo je totéž, kterým ReportMapper zjišťuje
     * situaci 31 — každé další razítko musí vzniknout ještě před koncem platnosti certifikátu
     * autority toho předchozího. Kde tomu tak není, má řetěz důkazů existence mezeru a věta
     * „nepřerušená“ by říkala opak toho, co o témže dokumentu píše Analýza.
     *
     * <p>Počítá se ze všech platných razítek, která blok vypisuje, včetně holého VRI. Situace 31
     * ho vylučuje, protože sama mluví o řetězu kryjícím podpis; tady je nadpis „posloupnost
     * časových razítek“ a čtenář má ta razítka před sebou vypsaná, takže vynechat jedno z nich
     * bez vysvětlení by shrnutí rozešlo se seznamem nad ním.
     */
    private void appendSequenceRow(VBox body, List<TimestampEntry> entries) {
        List<TimestampEntry> valid = entries.stream()
                .filter(TimestampEntry::passed)
                .filter(e -> e.time() != null)
                .toList();
        if (valid.size() < 2) return;
        body.getChildren().add(row("Posloupnost časových razítek:", sequenceText(valid)));
    }

    /**
     * Hodnota řádku o posloupnosti: začátek, každá mezera zvlášť, konec.
     *
     * <p>Samostatně, aby se dala zkontrolovat testem: v korpusu je přerušená posloupnost
     * vzácná a se dvěma mezerami se nevyskytuje vůbec, takže sondou by se ta věta nedala
     * poplat.
     */
    static String sequenceText(List<TimestampEntry> valid) {
        List<SequenceGap> gaps = sequenceGaps(valid);
        // Konec posloupnosti je konec krytí, ne konec platnosti certifikátu posledního razítka.
        // Razítko kryje od svého vzniku do expirace svého certifikátu — tož totéž, čím se měří
        // mezery mezi razítky — a sahá tam, kam dosáhne nejzazší z těch intervalů. Mladší
        // razítko s kratším certifikátem posloupnost nezkracuje; dokud to stálo na posledním
        // razítku, hlásil řádek u „podpis s razitkem-extended-pades-baseline-lta.pdf" konec
        // 11. 11. 2025, ačkoli podpisové razítko pod ním platí do 31. 12. 2028 (nález uživatele
        // 18. 9. 2026, potvrzený Úschovnou Czech POINT).
        java.time.Instant end = TimestampChain.reachOfLastRun(valid);
        String endText = end != null ? TimeUtil.format(end) : null;
        String start = TimeUtil.format(valid.get(0).time());

        StringBuilder value = new StringBuilder();
        if (gaps.isEmpty()) {
            value.append("nepřerušená, začíná ").append(start)
                 .append(endText != null ? " a končí " + endText : " a konec není znám");
        } else {
            // Každá mezera na vlastní řádek. Jedna se do věty ještě vejde, dvě už ne — a právě
            // ony jsou to, co má čtenář z řádku vyčíst.
            value.append("přerušená")
                 .append("\nzačíná ").append(start);
            for (SequenceGap gap : gaps) {
                value.append("\npřerušení ");
                if (gap.from() != null) {
                    value.append("od ").append(TimeUtil.format(gap.from()))
                         .append(" do ").append(TimeUtil.format(gap.to()));
                } else {
                    value.append("do ").append(TimeUtil.format(gap.to()))
                         .append(" (začátek nelze určit, u certifikátu autority předchozího")
                         .append(" razítka chybí konec platnosti)");
                }
            }
            value.append(endText != null ? "\nkončí " + endText : "\nkonec není znám");
        }
        return value.toString();
    }

    /**
     * Mezera v posloupnosti: od konce platnosti certifikátu autority jednoho razítka do vzniku
     * toho dalšího. {@code from} je null, když konec platnosti není znám.
     */
    record SequenceGap(java.time.Instant from, java.time.Instant to) { }

    /**
     * Mezery v posloupnosti platných razítek, v pořadí, v jakém nastaly. Razítka se očekávají
     * seřazená vzestupně, jak je vydává ReportMapper.
     *
     * <p>Mezera je tam, kde další razítko přibylo až po konci krytí té části řady, která mu
     * předchází — ne nutně po expiraci certifikátu razítka bezprostředně předchozího; pravidlo
     * i s odůvodněním drží {@link TimestampChain}. Rozhoduje <b>výhradně řádná doba platnosti
     * certifikátů</b>, ne sunset služby v důvěryhodném seznamu: sunset je shovívavost, kterou
     * norma přiznává kotvě důvěry, a posloupnost razítek má říct, dokud je dokument krytý, ne
     * dokud je to ještě omluvitelné.
     *
     * <p>Neznámý konec platnosti se počítá jako konec krytí. Není to opatrnost pro opatrnost:
     * bez něj nelze ukázat, že další razítko vzniklo včas, a „nepřerušená“ by tvrdila zjištění,
     * které se neudělalo. Stejně to počítá situace 31 v ReportMapperu.
     */
    static List<SequenceGap> sequenceGaps(List<TimestampEntry> valid) {
        List<SequenceGap> gaps = new java.util.ArrayList<>();
        for (TimestampChain.Gap gap : TimestampChain.gaps(valid)) {
            gaps.add(new SequenceGap(gap.from(), gap.to()));
        }
        return List.copyOf(gaps);
    }

    /** Zkratka pro {@link #sequenceGaps}: posloupnost bez jediné mezery. */
    static boolean sequenceUnbroken(List<TimestampEntry> valid) {
        return sequenceGaps(valid).isEmpty();
    }

    /**
     * Evidenční záznamy kryjící tento prvek. Blok vlastní, ne řádky mezi razítky: záznam kryje
     * skupinu datových objektů a to, jestli každý z nich pořád sedí, je právě to, co stojí za
     * přečtení — a řádek razítka nemá kam to napsat.
     */
    private VBox evidenceBlock(SignatureSummary sig) {
        if (sig.getEvidenceRecords().isEmpty()) return null;
        VBox body = blockBody();
        appendEvidenceRecords(body, sig.getEvidenceRecords(), true);
        return body;
    }

    /**
     * K jakému okamžiku se soudilo a odkud se ten okamžik vzal.
     *
     * <p>Rozhodný okamžik má dvě odpovědi, které se rozcházejí: čas, který dokládá platné časové
     * razítko, a čas, o který se opřel závěr DSS. Podle ETSI EN 319 102-1 začíná
     * best-signature-time na aktuálním čase a snižuje se na čas razítka teprve v kroku, ke
     * kterému se ověřování nemusí dostat — běh, který skončí dřív, hlásí „teď", ačkoli razítko
     * dokládá roky starý podpis. Dřív tu proto nebyl řádek žádný, aby karta netvrdila jedno ze
     * dvou. Blok obojí unese: říká, co dokládá razítko, co vzalo za své DSS, a kde se to
     * rozchází, i proč.
     */
    private VBox decisiveInstantBlock(SignatureSummary sig, boolean isTs, boolean isSeal) {
        VBox body = blockBody();
        cz.digitalnikontinuita.anep.model.ValidationCourse vc = sig.getValidationCourse();

        // Ne „Aktuální čas": v bloku o rozhodném okamžiku stojí tenhle řádek vedle tří dalších
        // časů a samotné „aktuální" neřekne, čeho se týká. Je to čas, ke kterému ověřování
        // proběhlo — a právě od něj se odvíjí best-signature-time, dokud ho nesníží razítko.
        body.getChildren().add(row("Aktuální čas ověření:",
                TimeUtil.format(java.time.Instant.now())));
        if (!isTs && sig.getSigningTime() != null) {
            body.getChildren().add(row(
                    sig.isMark() ? "Deklarovaný čas značky:"
                    : isSeal ? "Deklarovaný čas pečeti:" : "Deklarovaný čas podpisu:",
                    TimeUtil.format(sig.getSigningTime())));
        }
        java.time.Instant custom = vc != null && vc.customValidationTime() != null
                ? vc.customValidationTime()
                : (customValidationTime != null
                        ? customValidationTime.atZone(java.time.ZoneId.systemDefault()).toInstant()
                        : null);
        if (custom != null) {
            body.getChildren().add(row("Zadaný čas ověření:", TimeUtil.format(custom)
                    + "  —  uživatel zvolil toto datum v pokročilém nastavení, ověřování proběhlo"
                    + " k tomuto okamžiku"));
        }
        if (vc != null && vc.provenExistence() != null) {
            String note = vc.provenExistenceNote() != null ? "  —  " + vc.provenExistenceNote() : "";
            body.getChildren().add(row(
                    isTs ? "Prokázaná existence razítka:"
                         : sig.isMark() ? "Prokázaná existence značky:"
                         : isSeal ? "Prokázaná existence pečeti:" : "Prokázaná existence podpisu:",
                    TimeUtil.format(vc.provenExistence()) + note));
        }
        if (vc != null && vc.dssBestSignatureTime() != null) {
            body.getChildren().add(row("Rozhodný okamžik dle DSS:",
                    TimeUtil.format(vc.dssBestSignatureTime())));
        }
        if (vc != null && vc.bstDivergenceNote() != null) {
            body.getChildren().add(paragraph(vc.bstDivergenceNote(), "#7a4a00"));
        }

        // The anchoring time does get a row of its own once an evidence record supplies it. The
        // objection above is that the value has two answers that differ — but a record's proof is
        // not one of the two: it names one moment, and DSS's own verdict rests on that same
        // moment. Leaving it out would hide the single most consequential thing the record does.
        EvidenceRecordEntry proofRecord = sig.getEvidenceRecords().stream()
                .filter(EvidenceRecordEntry::providesProof)
                .min(java.util.Comparator.comparing(EvidenceRecordEntry::earliestProof))
                .orElse(null);
        if (!isTs && proofRecord != null) {
            java.time.Instant fromRecord = proofRecord.earliestProof();
            java.time.Instant fromSignature = sig.hasValidTimestamp()
                    ? sig.getBestSignatureTime() : null;
            // Whichever proof is older wins: the decisive instant is the earliest moment the
            // signature is shown to have existed, so a record time-stamped years after the
            // signature's own time-stamp does not move it. Stating both and leaving the reader to
            // pick would be worse than useless — the row is labelled as the decisive instant, so
            // it has to hold the decisive value.
            String value;
            if (fromSignature == null) {
                value = TimeUtil.format(fromRecord)
                        + "  —  převzat z evidenčního záznamu, z jeho nejstaršího platného"
                        + " časového razítka; podpis sám žádné platné razítko nemá";
            } else if (fromSignature.isAfter(fromRecord)) {
                value = TimeUtil.format(fromRecord)
                        + "  —  z nejstaršího platného časového razítka v evidenčním záznamu,"
                        + " které je starší než vlastní razítko podpisu, to je z "
                        + TimeUtil.format(fromSignature);
            } else {
                value = TimeUtil.format(fromSignature)
                        + "  —  z vlastního časového razítka podpisu; evidenční záznam dokládá"
                        + " až pozdější okamžik, totiž " + TimeUtil.format(fromRecord)
                        + ", a rozhodný proto neposouvá";
            }
            body.getChildren().add(row("Rozhodný okamžik:", value));
        }
        return body;
    }

    /**
     * Který revokační údaj závěr skutečně nesl — s odkazy na jeho zobrazení a uložení.
     *
     * @param certSerial sériové číslo certifikátu podepisující osoby, na jehož záznamu se má
     *                   otevřít prohlížeč CRL; viz {@link #openCrlViewer}
     */
    private VBox revocationBlock(CertificateRevocationWrapper usedRevoc,
                                 boolean revocationFromCustomStore,
                                 List<CertificateRevocationWrapper> embeddedInDocument,
                                 BigInteger certSerial) {
        if (usedRevoc == null) return null;
        boolean isOcsp     = usedRevoc.getRevocationType() == RevocationType.OCSP;
        boolean isInternal = usedRevoc.isInternalRevocationOrigin();
        // What the document carries wins over what happens to sit on this machine. The store is
        // consulted only for a token the document does not already hold, so a list that is in both
        // places was read from the document — and after preservation that is the normal state,
        // because preservation embeds the very list it took from the store. Saying "z vlastního
        // úložiště" there tells the reader the verdict depends on a local file, when in fact the
        // document stands on its own and would verify the same anywhere.
        //
        // This check used to run ahead of the origin, on the reasoning that DSS might report a
        // store CRL as INPUT_DOCUMENT and mislabel it "vložený". Measured on DSS 6.4 it does not:
        // a CRL that comes only from the store is reported EXTERNAL (AnEP\potrebuje vlozit CRL,
        // both files), while embedded material — including the CRL AnEP itself embedded when
        // preserving bajecny svet\2026\01 14 full\CAdES B-LT kvalifikovany PS_Do_19_07_2036.sce —
        // is INPUT_DOCUMENT. The origin separates the two cases, so it decides first.
        String prefix;
        if (isOcsp && isInternal)              prefix = "vložená OCSP odpověď, vydaná k ";
        else if (!isOcsp && isInternal)        prefix = "vložený CRL seznam, vydaný k ";
        else if (revocationFromCustomStore)    prefix = "CRL seznam z vlastního úložiště, vydaný k ";
        else if (isOcsp)                       prefix = "OCSP odpověď získaná on-line, vydaná k ";
        else                                   prefix = "CRL seznam získaný on-line, vydaný k ";
        String value = prefix + TimeUtil.format(usedRevoc.getThisUpdate());
        // If the cert was revoked AFTER the signing time, DSS still validates the signature as
        // TOTAL_PASSED (the cert was valid at signing time, proven by the timestamp). Show a note
        // so the user knows the cert is now revoked.
        String revocNote = null;
        if (usedRevoc.getStatus() == CertificateStatus.REVOKED) {
            String revDate = usedRevoc.getRevocationDate() != null
                    ? TimeUtil.format(usedRevoc.getRevocationDate()) : "neznámému datu";
            revocNote = "Certifikát podepisující osoby byl revokován k " + revDate + ".\n"
                    + "Podpis je přesto platný, protože k revokaci došlo "
                    + "až po rozhodném okamžiku ověření platnosti podpisu.";
        }
        VBox body = blockBody();
        body.getChildren().add(
                rowWithShowButton("Využité revokační informace:", value, usedRevoc, revocNote,
                        certSerial));

        // Co dokument nese sám, když se závěr opřel o údaj, který v něm není. Jsou to dvě různé
        // otázky a čtenář potřebuje obě: oč se opřelo tohle ověření, a s čím si poradí kdokoli
        // další, kdo dostane jen ten soubor — třeba bez sítě nebo až poté, co autorita přestane
        // odpovídat.
        //
        // Vlastní řádek, ne dovětek k tomu předchozímu: za hodnotou řádku o využitém údaji stojí
        // odkazy Podrobnosti/Ukaž/Ulož a ty patří <b>jemu</b>. Věta o jiném údaji vsunutá těsně
        // před ně svádí číst je jako odkazy na ni.
        //
        // Nic navíc znamená mlčet. Řádek „dokument sám o tomto certifikátu revokační údaj nenese"
        // pojmenovával nepřítomnost, a to je u položky nadepsané „Další revokační informace"
        // prázdné sdělení: nic dalšího není. Předchozí řádek už řekl, odkud se údaj vzal, takže
        // že v dokumentu není, z něj plyne — a tenhle to jen opakoval jinými slovy, navíc na
        // každém certifikátu zvlášť, takže se blok zaplnil větami, které nic nepřidávají.
        // Úaje, které dokument nese vedle toho právě jmenovaného. Využitý údaj se z výčtu vypouští —
        // řádek nad ním ho právě popsal a zopakovat ho jako „další" by čtenáře nechalo hledat rozdíl,
        // který tam není. Podmínka bývala „využitý údaj není z dokumentu", a tím řádek mizel právě
        // u uchovaných dokumentů: ty nesou dva vložené údaje a závěr stojí na jednom z nich, takže
        // o tom druhém se neřeklo nic.
        if (embeddedInDocument != null) {
            List<String> others = new java.util.ArrayList<>();
            for (CertificateRevocationWrapper r : embeddedInDocument) {
                if (usedRevoc.getId() != null && usedRevoc.getId().equals(r.getId())) continue;
                others.add(describeRevocation(r));
            }
            if (!others.isEmpty()) {
                body.getChildren().add(row("Další revokační informace:",
                        (isInternal ? "dokument nese také " : "dokument sám nese ")
                        + String.join(", ", others)));
            }
        }
        return body;
    }

    /**
     * Čím se podepisovalo a v jakém tvaru to v dokumentu leží.
     *
     * <p>Od 5. 10. 2026 (pokyn uživatele) i algoritmy <b>časových razítek</b>, která prvek kryje
     * — touž sestavou řádků jako u podpisu, každé razítko oddělené čarou. Dosud blok mlčel právě
     * o tom, na čem dnes záleží nejvíc: nejnovější razítko drží důkaz existence všeho pod ním,
     * a jeho algoritmus (typicky RSA o 2 048 bitech) rozhoduje, dokdy to vydrží. Proto i délka
     * klíče, u podpisu i u razítek. Razítka jsou v témž pořadí a se stejným číslováním jako
     * v bloku „Časová razítka“, aby se dala spárovat.
     */
    private VBox algorithmsBlock(SignatureSummary sig, boolean isTs, boolean isSeal) {
        VBox body = blockBody();
        DiagnosticData dd = diagnosticData;
        eu.europa.esig.dss.diagnostic.AbstractTokenProxy own = dd == null ? null
                : isTs ? dd.getTimestampById(sig.getSignatureId())
                : dd.getSignatureById(sig.getSignatureId());
        if (sig.getEncryptionAlgorithm() != null) {
            body.getChildren().add(row(
                    isSeal ? "Algoritmus pečeti:" : "Podpisový algoritmus:",
                    sig.getEncryptionAlgorithm()));
        }
        String ownKey = own != null ? keyLength(own.getKeyLengthUsedToSignThisToken()) : null;
        if (ownKey != null) body.getChildren().add(row("Délka klíče:", ownKey));
        if (sig.getDigestAlgorithm() != null) {
            // U razítka jsou hašovací funkce dvě a mohou se lišit — viz addImprintRow —, takže
            // řádek musí říct, která to je (pokyn uživatele 5. 10. 2026).
            body.getChildren().add(row(isTs ? "Hašovací funkce podpisu razítka:"
                                            : "Použitá hašovací funkce:",
                    sig.getDigestAlgorithm()));
        }
        if (own instanceof TimestampWrapper ownTs) addImprintRow(body, ownTs);
        body.getChildren().add(row(
                isTs ? "Formát časového razítka:" : isSeal ? "Formát pečeti:" : "Formát podpisu:",
                isTs ? timestampFormatLabel() : formatSignatureLabel(sig.getFormat())));
        // Signing tool — only when the signature declares it (/Prop_Build /App); omitted otherwise
        if (sig.getSigningTool() != null && !sig.getSigningTool().isBlank()) {
            body.getChildren().add(row("Vytvořeno pomocí:", sig.getSigningTool()));
        }
        if (!isTs && dd != null) addTimestampAlgorithms(body, sig, dd);
        return body;
    }

    /**
     * Razítka nad podpisem, každé za čarou. Táž množina a totéž řazení jako
     * {@code sig.getAllTimestamps()} (ReportMapper.allTimestamps: razítka podpisu bez času se
     * vynechají, zbytek podle času vzniku), takže „Razítko č. 2“ je tu i v bloku „Časová
     * razítka“ totéž razítko.
     */
    private void addTimestampAlgorithms(VBox body, SignatureSummary sig, DiagnosticData dd) {
        eu.europa.esig.dss.diagnostic.SignatureWrapper sw = dd.getSignatureById(sig.getSignatureId());
        if (sw == null) return;
        List<TimestampWrapper> list = sw.getTimestampList().stream()
                .filter(ts -> ts.getProductionTime() != null)
                .sorted(java.util.Comparator.comparing(TimestampWrapper::getProductionTime))
                .toList();
        List<TimestampEntry> entries = sig.getAllTimestamps();
        for (int i = 0; i < list.size(); i++) {
            TimestampWrapper ts = list.get(i);
            body.getChildren().add(new Separator());
            String kind = i < entries.size() && entries.get(i).tsKind() != null
                    ? entries.get(i).tsKind() : "sig";
            String suffix = switch (kind) {
                case "doc" -> " (dokumentové)";
                case "vri", "vri_bare" -> " (v dokumentu / VRI)";
                case "content" -> " (obsahové — před podpisem)";
                default -> " (v podpisu)";
            };
            StringBuilder head = new StringBuilder(TimeUtil.format(ts.getProductionTime()));
            var tsa = ts.getSigningCertificate();
            if (tsa != null) {
                String name = tsa.getCommonName() != null && !tsa.getCommonName().isBlank()
                        ? tsa.getCommonName() : tsa.getReadableCertificateName();
                if (name != null) head.append("  —  vydavatel: ").append(name);
            }
            body.getChildren().add(row((list.size() == 1 ? "Časové razítko" : "Razítko č. " + (i + 1))
                    + suffix + ":", head.toString()));
            if (ts.getEncryptionAlgorithm() != null) {
                body.getChildren().add(row("Podpisový algoritmus:",
                        ts.getEncryptionAlgorithm().getName()));
            }
            String key = keyLength(ts.getKeyLengthUsedToSignThisToken());
            if (key != null) body.getChildren().add(row("Délka klíče:", key));
            if (ts.getDigestAlgorithm() != null) {
                body.getChildren().add(row("Hašovací funkce podpisu razítka:",
                        ts.getDigestAlgorithm().getName()));
            }
            addImprintRow(body, ts);
        }
    }

    /**
     * Otisk, kterým razítko váže orazítkovaná data — u razítka druhá hašovací funkce vedle té
     * v jeho vlastním podpisu, a nemusí být táž (SHA-1 v otisku pod podpisem SHA-256 je přesně
     * případ, který blok o razítkách jinak neukáže). Funkci podpisu volí autorita, funkci
     * otisku žadatel; ve sbírce je razítko I.CA s podpisem SHA256 a otiskem SHA512.
     *
     * <p>Řádek se jmenoval „Otisk orazítkovaných dat“ a vedle „Použité hašovací funkce“ se oba
     * četly jako totéž (dotaz uživatele, 5. 10. 2026); proto oba nesou, čeho se týkají.
     */
    private void addImprintRow(VBox body, TimestampWrapper ts) {
        var imprint = ts.getMessageImprint();
        if (imprint != null && imprint.getDigestMethod() != null) {
            body.getChildren().add(row("Hašovací funkce otisku dat:",
                    imprint.getDigestMethod().getName()));
        }
    }

    /** „2 048 bitů“; {@code null} pro neznámou délku (DSS píše „?“ nebo nic). */
    private static String keyLength(String bits) {
        try {
            int n = Integer.parseInt(bits);
            if (n <= 0) return null;
            return String.format(java.util.Locale.ROOT, "%,d", n).replace(',', ' ') + " bitů";
        } catch (Exception e) {
            return null;
        }
    }

    /** Odstavec bez popisku, zarovnaný pod hodnoty řádků kolem. */
    private static HBox paragraph(String text) {
        return paragraph(text, "#555555");
    }

    private static HBox paragraph(String text, String colour) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: " + colour + ";");
        HBox box = new HBox(label);
        HBox.setHgrow(label, Priority.ALWAYS);
        box.setPadding(new Insets(2, 0, 2, 0));
        return box;
    }

    private HBox rowWithShowButton(String labelText, String value,
                                   eu.europa.esig.dss.diagnostic.RevocationWrapper revoc) {
        return rowWithShowButton(labelText, value, revoc, null, null);
    }

    private HBox rowWithShowButton(String labelText, String value,
                                   eu.europa.esig.dss.diagnostic.RevocationWrapper revoc,
                                   String notePopupText, BigInteger certSerial) {
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111;");
        lbl.setMinWidth(LBL_WIDTH);

        javafx.scene.text.Text textNode = new javafx.scene.text.Text(value != null ? value : "");
        textNode.setStyle("-fx-fill: #111111;");

        String revColor = (revoc != null && revoc.getRevocationType() == RevocationType.OCSP)
                ? "#cc5500" : "#007733";
        Hyperlink ukazLink = new Hyperlink("Ukaž");
        ukazLink.setStyle("-fx-font-size: 11px; -fx-text-fill:" + revColor + ";-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ukazLink.setOnAction(e -> { showRevocationDialog(revoc, certSerial); ukazLink.setVisited(false); });

        javafx.scene.text.TextFlow flow = new javafx.scene.text.TextFlow(textNode);

        if (notePopupText != null) {
            Hyperlink noteLink = new Hyperlink("  Podrobnosti");
            noteLink.setStyle("-fx-font-size: 11px;-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
            final String popupText = notePopupText;
            noteLink.setOnAction(e -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.initOwner(noteLink.getScene().getWindow());
                alert.setTitle("Revokace certifikátu");
                alert.setHeaderText(null);
                alert.setContentText(popupText);
                alert.getButtonTypes().setAll(ButtonType.OK);
                alert.showAndWait();
                noteLink.setVisited(false);
            });
            flow.getChildren().add(noteLink);
        }

        Hyperlink ulozLink = new Hyperlink("Ulož");
        ulozLink.setStyle("-fx-font-size: 11px; -fx-text-fill:" + revColor + ";-fx-border-color:transparent;-fx-padding:0 0 1 0;-fx-background-color:transparent;");
        ulozLink.setOnAction(e -> { saveRevocToFile(revoc); ulozLink.setVisited(false); });

        flow.getChildren().addAll(new javafx.scene.text.Text("  "), ukazLink, new javafx.scene.text.Text("  "), ulozLink);
        HBox.setHgrow(flow, Priority.ALWAYS);

        javafx.scene.control.ContextMenu cm = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem copyItem = new javafx.scene.control.MenuItem("Kopírovat text");
        copyItem.setOnAction(e -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(value != null ? value : "");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
        });
        cm.getItems().add(copyItem);
        flow.setOnContextMenuRequested(e -> cm.show(flow, e.getScreenX(), e.getScreenY()));

        HBox box = new HBox(4, lbl, flow);
        box.setAlignment(Pos.BASELINE_LEFT);
        return box;
    }

    private void saveRevocToFile(eu.europa.esig.dss.diagnostic.RevocationWrapper revoc) {
        byte[] data = revoc != null ? revoc.getBinaries() : null;
        if (data == null || data.length == 0) {
            javafx.scene.control.Alert err = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
            err.setHeaderText(null);
            err.setContentText("Soubor neobsahuje žádná data k uložení.");
            err.showAndWait();
            return;
        }
        boolean isOcsp = revoc.getRevocationType() == RevocationType.OCSP;
        String defaultName = isOcsp ? "ocsp-response.ocsp" : "crl.crl";
        String filterDesc  = isOcsp ? "OCSP odpověď (*.ocsp)" : "CRL seznam (*.crl)";
        String filterExt   = isOcsp ? "*.ocsp" : "*.crl";
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Uložit soubor");
        fc.setInitialFileName(defaultName);
        fc.getExtensionFilters().addAll(
                new javafx.stage.FileChooser.ExtensionFilter(filterDesc, filterExt),
                new javafx.stage.FileChooser.ExtensionFilter("Všechny soubory (*.*)", "*.*"));
        java.io.File file = fc.showSaveDialog(null);
        if (file != null) {
            try {
                java.nio.file.Files.write(file.toPath(), data);
            } catch (Exception ex) {
                javafx.scene.control.Alert err = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
                err.setHeaderText(null);
                err.setContentText("Nepodařilo se uložit soubor:\n" + ex.getMessage());
                err.showAndWait();
            }
        }
    }

    private void showRevocationDialog(eu.europa.esig.dss.diagnostic.RevocationWrapper revoc,
                                      BigInteger certSerial) {
        if (revoc.getRevocationType() == RevocationType.OCSP) {
            showOcspDialog(revoc);
        } else {
            openCrlViewer(revoc, certSerial);
        }
    }

    private void showOcspDialog(eu.europa.esig.dss.diagnostic.RevocationWrapper ocsp) {
        VBox content = new VBox(4);
        content.setPadding(new Insets(16));
        StringBuilder copyBuf = new StringBuilder();

        content.getChildren().add(ocspSectionHeader("Základní informace", copyBuf));
        addOcspRow(content, copyBuf, "Zdroj (URL respondéra)", ocsp.getSourceAddress());
        addOcspRow(content, copyBuf, "Vydáno (producedAt)",    TimeUtil.format(ocsp.getProductionDate()));
        addOcspRow(content, copyBuf, "Platný od (thisUpdate)", TimeUtil.format(ocsp.getThisUpdate()));
        addOcspRow(content, copyBuf, "Platný do (nextUpdate)", TimeUtil.format(ocsp.getNextUpdate()));

        byte[] der = ocsp.getBinaries();
        if (der != null && der.length > 0) {
            try {
                OCSPResp resp = new OCSPResp(der);
                addOcspRow(content, copyBuf, "Stav odpovědi", ocspRespStatusLabel(resp.getStatus()));
                Object obj = resp.getResponseObject();
                if (obj instanceof BasicOCSPResp basic) {
                    String responderId = basic.getResponderId().toASN1Primitive().toString();
                    addOcspRow(content, copyBuf, "ID respondéra", responderId);
                    addOcspRow(content, copyBuf, "Algoritmus podpisu",
                            basic.getSignatureAlgOID() != null ? basic.getSignatureAlgOID().getId() : null);
                    if (basic.getCerts() != null && basic.getCerts().length > 0) {
                        addOcspRow(content, copyBuf, "Certifikátů v odpovědi",
                                String.valueOf(basic.getCerts().length));
                    }
                    SingleResp[] responses = basic.getResponses();
                    content.getChildren().add(ocspSectionHeader(
                            "Stav certifikátů (" + responses.length + ")", copyBuf));
                    for (int i = 0; i < responses.length; i++) {
                        SingleResp sr = responses[i];
                        if (responses.length > 1) {
                            Label idx = new Label("Záznam č. " + (i + 1));
                            idx.setStyle("-fx-font-style:italic;-fx-text-fill:#555555;" +
                                    "-fx-font-size:11px;-fx-padding:4 0 0 0;");
                            content.getChildren().add(idx);
                        }
                        BigInteger serial = sr.getCertID().getSerialNumber();
                        addOcspRow(content, copyBuf, "Sériové č. certifikátu",
                                cz.digitalnikontinuita.anep.core.ContainerInspector.hexNumber(serial));
                        addOcspRow(content, copyBuf, "Hash algoritmus (CertID)",
                                sr.getCertID().getHashAlgOID() != null
                                        ? sr.getCertID().getHashAlgOID().getId() : null);
                        Object certStatus = sr.getCertStatus();
                        if (certStatus == null) {
                            addOcspRow(content, copyBuf, "Stav certifikátu", "Dobrý (nerevokovaný)");
                        } else if (certStatus instanceof RevokedStatus rs) {
                            addOcspRow(content, copyBuf, "Stav certifikátu", "Revokovaný");
                            addOcspRow(content, copyBuf, "Datum revokace",
                                    TimeUtil.format(rs.getRevocationTime()));
                            if (rs.hasRevocationReason()) {
                                addOcspRow(content, copyBuf, "Důvod revokace",
                                        crlReasonLabel(rs.getRevocationReason()));
                            }
                        } else if (certStatus instanceof UnknownStatus) {
                            addOcspRow(content, copyBuf, "Stav certifikátu", "Neznámý");
                        }
                        addOcspRow(content, copyBuf, "Platné od (thisUpdate)",
                                TimeUtil.format(sr.getThisUpdate()));
                        if (sr.getNextUpdate() != null)
                            addOcspRow(content, copyBuf, "Platné do (nextUpdate)",
                                    TimeUtil.format(sr.getNextUpdate()));
                    }
                }
            } catch (Exception ex) {
                Label warn = new Label("Parsování OCSP odpovědi selhalo: " + ex.getMessage());
                warn.setStyle("-fx-text-fill:#cc0000;-fx-font-size:11px;");
                warn.setWrapText(true);
                content.getChildren().add(warn);
            }
        }

        content.getChildren().add(ocspSectionHeader("Podpis respondéra", copyBuf));
        String sigAlg = ocsp.getSignatureAlgorithm() != null ? ocsp.getSignatureAlgorithm().toString() : null;
        addOcspRow(content, copyBuf, "Algoritmus (DSS)", sigAlg);
        addOcspRow(content, copyBuf, "Podpis neporušený", ocsp.isSignatureIntact() ? "Ano" : "Ne");
        addOcspRow(content, copyBuf, "Podpis validní",    ocsp.isSignatureValid() ? "Ano" : "Ne");
        addOcspRow(content, copyBuf, "Řetěz důvěryhodný", ocsp.isTrustedChain() ? "Ano" : "Ne");
        addOcspRow(content, copyBuf, "Otisk cert. (certHash)",
                ocsp.isCertHashExtensionPresent()
                        ? "přítomen — " + (ocsp.isCertHashExtensionMatch() ? "shoda" : "NESHODA")
                        : "nepřítomen");
        CertificateWrapper respCert = ocsp.getSigningCertificate();
        if (respCert != null) {
            String cn = respCert.getCommonName() != null && !respCert.getCommonName().isBlank()
                    ? respCert.getCommonName() : respCert.getReadableCertificateName();
            addOcspRow(content, copyBuf, "Certifikát respondéra", cn);
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
        HBox btnRow = new HBox(8, copyAll, close);
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

    /**
     * Seznam CRL se otevírá ve <b>vlastním</b> prohlížeči, ne v systémovém: ten ukáže soubor,
     * kdežto čtenář se ptá na jeden z tisíců záznamů v něm. Vidí hlavičku seznamu i tabulku
     * odvolaných certifikátů a může seznam rovnou uložit.
     *
     * <p>Otevírá se na záznamu {@code certSerial}. Do 15. 10. 2026 se sem číslo nepředávalo
     * a okno stálo na prvním řádku i nad seznamem, na kterém certifikát podepisující osoby
     * revokovaný je (nález uživatele na podpisu I.CA s tisíci záznamy v CRL); umělo to jen
     * Analýza.
     */
    private void openCrlViewer(eu.europa.esig.dss.diagnostic.RevocationWrapper crl,
                               BigInteger certSerial) {
        byte[] der = crl != null ? crl.getBinaries() : null;
        if (der == null || der.length == 0) return;
        eu.europa.esig.dss.diagnostic.CertificateWrapper issuer =
                crl.getSigningCertificate();
        CrlView.show(der, certSerial, issuer != null ? issuer.getBinaries() : null, null);
    }

    private HBox ocspSectionHeader(String text, StringBuilder buf) {
        if (buf != null) buf.append('\n').append(text).append('\n');
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-size:11px;-fx-font-weight:bold;-fx-text-fill:#003189;-fx-padding:8 0 2 0;");
        Separator sep = new Separator();
        VBox inner = new VBox(2, sep, lbl);
        HBox box = new HBox(inner);
        HBox.setHgrow(inner, Priority.ALWAYS);
        return box;
    }

    private void addOcspRow(VBox parent, StringBuilder buf, String label, String value) {
        if (value == null || value.isBlank() || "—".equals(value) || "null".equals(value)) return;
        if (buf != null) buf.append(label).append('\t').append(value).append('\n');
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-weight:bold;-fx-text-fill:#111111;-fx-font-size:12px;");
        lbl.setMinWidth(220);
        lbl.setMaxWidth(220);
        lbl.setWrapText(true);
        javafx.scene.control.TextArea val = ResultView.selectable(value, "-fx-text-fill:#111111;-fx-font-size:12px;");
        HBox.setHgrow(val, Priority.ALWAYS);
        HBox row = new HBox(8, lbl, val);
        row.setPadding(new Insets(1, 0, 1, 0));
        parent.getChildren().add(row);
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

    // Klíče se berou z Relaxation, ne opisují: jsou to klíče, ne popisky, a jedna kopie navíc
    // znamená, že se při přejmenování volby tiše přestane hledat.
    private static final Map<String, String> LENIENT_SETTING_DESCRIPTIONS = Map.of(
        cz.digitalnikontinuita.anep.core.ReportMapper.Relaxation.NEWER_OCSP.settingName(),
            "V řetězu certifikátů se nachází revokační informace (OCSP nebo CRL) vydaná po uplynutí " +
            "platnosti (notAfter) příslušného certifikátu. Standardní ověřování taková data odmítá " +
            "jako vydaná pozdě — bez této volby by ověření nebylo úspěšné.",
        cz.digitalnikontinuita.anep.core.ReportMapper.Relaxation.EXPIRED_REVOCATION_SOURCES.settingName(),
            "Certifikát vydavatele revokační informace (respondéra OCSP nebo vydavatele CRL) k rozhodnému " +
            "okamžiku neplatil — buď už vypršel, nebo, ověřuje-li se k okamžiku v minulosti, tehdy ještě " +
            "vydaný nebyl. Standardní ověřování taková data odmítá — bez této volby by ověření nebylo " +
            "úspěšné.",
        cz.digitalnikontinuita.anep.core.ReportMapper.Relaxation.SHA1.settingName(),
            "Podpis nebo řetěz certifikátů využívá hashovací algoritmus SHA-1, který je v dnešní " +
            "době považován za kryptograficky nedostatečný. Standardní ověřování takový podpis odmítá — " +
            "bez této volby by ověření nebylo úspěšné.",
        cz.digitalnikontinuita.anep.core.ReportMapper.Relaxation.PDF_NOT_ETSI.settingName(),
            "Podpis používá PDF sub-filtr Adobe (např. adbe.pkcs7.detached), který neodpovídá standardu " +
            "ETSI/PAdES. DSS standardně vrací pro takovýto podpis výsledek, který platnost neprokáže " +
            "(INDETERMINATE) — bez této volby by ověření neprokázalo platnost podpisu.",
        "Využít vložené CRL seznamy",
            "Ověření využilo vlastní CRL seznam(y) vložené do aplikace jako záložní zdroj revokačních " +
            "informací. Bez těchto CRL by nebylo možné prokázat stav certifikátu a ověření by " +
            "nebylo úspěšné."
    );

    private HBox stavRow(String stavText, String stavColor, String stavNote,
                         List<String> decisiveSettings, boolean interchangeable,
                         String decisiveCustomTime,
                         String warningText, String customTrustAnchor,
                         CertificateRevocationWrapper customCrl) {
        Label lbl = new Label("Stav platnosti:");
        lbl.setStyle("-fx-font-weight: bold; -fx-text-fill: white;");
        lbl.setMinWidth(LBL_WIDTH);

        Text primary = new Text(stavText);
        primary.setStyle("-fx-font-weight: bold; -fx-fill: white;");

        List<javafx.scene.Node> flowNodes = new java.util.ArrayList<>();
        flowNodes.add(primary);

        // Everything the verdict leans on beyond a plain standards-conformant check, named in one
        // breath. Each of these can hold on its own and several can hold at once, so they are
        // listed rather than ranked — the reader needs to know the whole of what the result
        // depends on, not the most important part of it.
        List<String> reasons = new java.util.ArrayList<>();
        if (!decisiveSettings.isEmpty())   reasons.add("tolerantnímu ověřování");
        if (customTrustAnchor != null)     reasons.add("vlastnímu důvěryhodnému certifikátu");
        if (customCrl != null)             reasons.add("vlastnímu CRL");
        if (decisiveCustomTime != null)    reasons.add("zadanému datu ověřování");
        if (!reasons.isEmpty()) {
            Text lenientNote = new Text("  (díky " + String.join(", ", reasons) + ")");
            // Bold, brackets and all: white on the status bar's saturated background, the ordinary
            // weight was hard to read on a display, and this is the part that qualifies the verdict.
            lenientNote.setStyle("-fx-font-weight: bold; -fx-fill: white;");
            flowNodes.add(lenientNote);

            Hyperlink detailsLink = new Hyperlink("Podrobnosti");
            detailsLink.setPadding(new Insets(0, 0, 2, 6));
            detailsLink.setStyle("-fx-font-size: 11px; -fx-text-fill: white; -fx-underline: true;");
            detailsLink.setOnAction(e -> {
                showDecisiveDetail(decisiveSettings, interchangeable, decisiveCustomTime,
                        customTrustAnchor, customCrl, detailsLink);
                detailsLink.setVisited(false);
            });
            flowNodes.add(detailsLink);
        } else if (stavNote != null) {
            Text noteText = new Text("  " + stavNote);
            // Same reasoning as the note above: white on a saturated bar needs the heavier weight.
            noteText.setStyle("-fx-font-weight: bold; -fx-fill: white;");
            flowNodes.add(noteText);
        }

        TextFlow flow = new TextFlow(flowNodes.toArray(new javafx.scene.Node[0]));
        HBox box;
        if (warningText != null) {
            box = new HBox(4, lbl, flow, ResultView.makeWarningButton(warningText));
        } else {
            HBox.setHgrow(flow, Priority.ALWAYS);
            box = new HBox(4, lbl, flow);
        }
        box.setStyle("-fx-background-color: " + stavColor + "; -fx-padding: 6 10 6 10;");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** One bold heading with its explanation, laid out like the entries above it. */
    private static void addDetailEntry(VBox content, String heading, String description) {
        Label nameLabel = new Label(heading);
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(440);

        Label descLabel = new Label(description);
        descLabel.setStyle("-fx-text-fill: #444444; -fx-font-size: 11px;");
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(440);

        content.getChildren().addAll(nameLabel, descLabel);
    }

    private void showDecisiveDetail(List<String> decisiveSettings, boolean interchangeable,
                                    String decisiveCustomTime,
                                    String customTrustAnchor,
                                    CertificateRevocationWrapper customCrl,
                                    javafx.scene.Node anchor) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(anchor.getScene().getWindow());
        alert.setTitle("Nastavení ověřování");
        // Zastupují-li se ty okolnosti navzájem, je „díky těmto“ nepravda: stačila kterákoli
        // z nich a bez jedné by výsledek nesla druhá. Měří to RelaxationMeasurement.measure()
        // druhým během, v němž pouští každý ústupek samostatně — a popisy níž se řídí týmž
        // příznakem, takže nadpis a popisy si nesmějí odporovat.
        alert.setHeaderText(interchangeable
            ? "Platnost tohoto podpisu byla prokázána díky jedné\n"
              + "z těchto okolností nad rámec standardního ověřování.\n"
              + "Stačí kterákoli z nich, aktivované byly všechny:"
            : "Platnost tohoto podpisu byla prokázána pouze díky\n"
              + "těmto okolnostem nad rámec standardního ověřování:");
        alert.getButtonTypes().setAll(ButtonType.OK);

        VBox content = new VBox(10);
        content.setPrefWidth(460);
        boolean first = true;

        for (String setting : decisiveSettings) {
            if (!first) content.getChildren().add(new Separator());
            first = false;

            Label nameLabel = new Label(setting);
            nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
            nameLabel.setWrapText(true);
            nameLabel.setMaxWidth(440);

            String desc = LENIENT_SETTING_DESCRIPTIONS.getOrDefault(setting,
                "Tato nastavená odchylka umožnila úspěšné ověření podpisu.");
            // Věta „bez této volby by ověření nebylo úspěšné" platí jen tam, kde je volba
            // nezastupitelná. Nad zastupitelnou dvojicí by tvrdila o každé z nich něco,
            // co vyvrací ta druhá.
            if (interchangeable) {
                desc = desc.replace(" — bez této volby by ověření nebylo úspěšné.", ".")
                           .replace(" — bez této volby by ověření neprokázalo platnost podpisu.", ".");
            }
            Label descLabel = new Label(desc);
            descLabel.setStyle("-fx-text-fill: #444444; -fx-font-size: 11px;");
            descLabel.setWrapText(true);
            descLabel.setMaxWidth(440);

            content.getChildren().addAll(nameLabel, descLabel);
        }

        if (customTrustAnchor != null) {
            if (!first) content.getChildren().add(new Separator());
            first = false;
            addDetailEntry(content, "Vlastní důvěryhodný certifikát: " + customTrustAnchor,
                    "Řetěz certifikátů končí u certifikátu z vašeho vlastního úložiště"
                    + " důvěryhodných certifikátů, nikoli u kotvy důvěry z evropských"
                    + " důvěryhodných seznamů. Validátor, který toto úložiště nemá, k této kotvě"
                    + " nedojde a podpis nemusí ověřit jako platný. Certifikát ve vlastním"
                    + " úložišti také nemá sunset — konec kladného statusu, který u služeb"
                    + " ze seznamů omezuje dobu, po kterou kotvu drží.");
        }

        if (customCrl != null) {
            if (!first) content.getChildren().add(new Separator());
            first = false;
            String issuer = customCrl.getSigningCertificate() != null
                    ? customCrl.getSigningCertificate().getReadableCertificateName() : "neznámý vydavatel";
            String issued = customCrl.getThisUpdate() != null
                    ? TimeUtil.format(customCrl.getThisUpdate()) : "neznámé datum";
            addDetailEntry(content, "Vlastní CRL od " + issuer,
                    "Stav revokace certifikátu prokázal seznam CRL z vašeho vlastního úložiště,"
                    + " vydaný " + issued + ". Tento seznam není v dokumentu obsažen a nebylo"
                    + " ho možné získat on-line, takže validátor bez tohoto úložiště jej k dispozici"
                    + " mít nebude. Trvalé řešení je zachovat ověřitelnost, čímž se seznam vloží přímo do"
                    + " dokumentu.");
        }

        if (decisiveCustomTime != null) {
            if (!first) content.getChildren().add(new Separator());

            Label nameLabel = new Label("Ověřit k zadanému datu a času: " + decisiveCustomTime);
            nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
            nameLabel.setWrapText(true);
            nameLabel.setMaxWidth(440);

            Label descLabel = new Label(
                "Ověření proběhlo k datu a času zadanému v Nastavení (" + decisiveCustomTime + "). "
                + "Bez tohoto nastavení by ověření proběhlo k aktuálnímu systémovému času, "
                + "a výsledek mohl být odlišný (například pokud podpisový certifikát nebo "
                + "revokační informace přestaly být platné k aktuálnímu datu).");
            descLabel.setStyle("-fx-text-fill: #444444; -fx-font-size: 11px;");
            descLabel.setWrapText(true);
            descLabel.setMaxWidth(440);

            content.getChildren().addAll(nameLabel, descLabel);
        }

        alert.getDialogPane().setContent(content);
        alert.showAndWait();
    }

    /**
     * Hodnota řádku „Formát časového razítka:". Protějšek {@link #formatSignatureLabel}, jehož
     * závorka u podpisů říká, zda jde o referenční formát — u razítka ta otázka nevzniká,
     * a mlčet o tom by znamenalo nechat čtenáře hádat. Podrobný výklad je v Analýze, v bloku
     * „Formát razítka".
     */
    static String timestampFormatLabel() {
        return "TimeStampToken — token dle RFC 3161 (úrovně ani referenční formáty se"
             + " časových razítek netýkají)";
    }

    static String formatSignatureLabel(String raw) {
        if (raw == null) return "";
        String display = AdvisorView.formatLabel(raw).replaceAll("-BASELINE-", " Baseline-");
        boolean isRef = raw.toUpperCase().contains("BASELINE");
        return display + (isRef ? " (jde o referenční formát)" : " (nejde o referenční formát)");
    }

    static String simplifyLegalType(String raw) {
        String s = raw.replace("pravděpodobně ", "")
                      .replaceAll("\\s*\\([^)]*\\)", "")
                      .trim();
        if (s.startsWith("pouze ")) s = s.substring("pouze ".length());
        s = s.replace("zaručený elektronický podpis, založený na kvalifikovaném certifikátu",
                      "uznávaný elektronický podpis");
        s = s.replace("zaručenou elektronickou pečeť, založenou na kvalifikovaném certifikátu",
                      "uznávanou elektronickou pečeť");
        return s;
    }

    /**
     * True when a simplified legal type names a seal (or the older "elektronická značka").
     * The "podpis" guard keeps the catch-all type that mentions both words on the signature
     * side; it mirrors the gender test in {@code AdvisorView.gendered()}.
     */
    static boolean isSealLegalType(String simplified) {
        if (simplified == null) return false;
        String low = simplified.toLowerCase();
        return (low.contains("pečeť") || low.contains("značk")) && !low.contains("podpis");
    }

    /** Label opening the type row: seals read "Je to: kvalifikovaná …", the rest "Jde o: …". */
    static String legalTypeLabel(String simplified) {
        return isSealLegalType(simplified) ? "Je to:" : "Jde o:";
    }

    /**
     * Nominative (1. pád) form of a seal legal type. {@link #simplifyLegalType} produces the
     * accusative, which is what "Jde o …" takes; seals are introduced with "Je to:" instead,
     * so they need the nominative. Signature types are returned unchanged — their accusative
     * and nominative forms are identical ("kvalifikovaný elektronický podpis").
     */
    static String nominativeLegalType(String simplified) {
        if (!isSealLegalType(simplified)) return simplified;
        return simplified.replace("kvalifikovanou", "kvalifikovaná")
                         .replace("uznávanou", "uznávaná")
                         .replace("zaručenou", "zaručená")
                         .replace("elektronickou", "elektronická")
                         .replace("založenou", "založená")
                         .replace("značku", "značka");
    }

    /**
     * Výklad druhu podpisu — text za ikonou ⓘ v Zjednodušeném i Rozšířeném přehledu, v Analýze
     * vypsaný celý, a v protokolu vysazený na vlastní řádce za „tj.: “.
     *
     * <p>Všechna znění začínají <b>malým</b> písmenem, protože žádné z nich nezačíná větu:
     * navazují na „Jde o:“ / „Je to:“ a v protokolu na „tj.: “. Do 30. 8. 2026 začínala čtyři
     * z šesti velkým a výklad tak vypadal, jako by patřil někam jinam než ostatní.
     *
     * <p>Jediné místo, kde ta znění jsou. {@code ResultView} mělo do 30. 8. 2026 vlastní kopii
     * téhož řetězu podmínek — dvě znění se v ní od těchto lišila a nikdo si toho nevšiml, protože
     * obě vypadala správně, každé zvlášť.
     */
    static String legalTypeTooltip(String simplified) {
        return stripMarkup(legalTypeTooltipMarkup(simplified));
    }

    /** URL of § 6 odst. 2 zákona č. 297/2016 Sb. na Zákonech pro lidi. */
    static final String URL_ACT_297_2016_S6 = "https://www.zakonyprolidi.cz/cs/2016-297#p6-2";

    /** Totéž pro § 9 odst. 2 — pečeťová obdoba § 6. */
    static final String URL_ACT_297_2016_S9 = "https://www.zakonyprolidi.cz/cs/2016-297#p9-2";

    /**
     * Totéž znění jako {@link #legalTypeTooltip}, ale se značkami: {@code **tučně**} a
     * {@code [text](url)}. Čte ho jen Analýza, kde se výklad sází do {@code TextFlow} a odkaz v něm
     * musí být klikací; bublina v přehledech a protokol berou očistěnou podobu výše.
     */
    static String legalTypeTooltipMarkup(String simplified) {
        if (simplified == null) return null;
        return switch (simplified) {
            case "uznávaný elektronický podpis" ->
                "zaručený elektronický podpis, založený na kvalifikovaném certifikátu"
                + " (AdEsig-QC) dle nařízení (EU) č. 910/2014 (eIDAS), resp. první varianta"
                + " v rámci pojmu \"uznávaný elektronický podpis\""
                + " dle [§ 6 odst. 2](" + URL_ACT_297_2016_S6 + ") zákona č. 297/2016 Sb.";
            case "kvalifikovaný elektronický podpis" ->
                "kvalifikovaný elektronický podpis (QESig) dle nařízení (EU) č. 910/2014 (eIDAS),"
                + " resp. druhá varianta pojmu \"uznávaný elektronický podpis\""
                + " dle [§ 6 odst. 2](" + URL_ACT_297_2016_S6 + ") zákona č. 297/2016 Sb.";
            case "uznávanou elektronickou pečeť" ->
                "zaručená elektronická pečeť, založená na kvalifikovaném certifikátu"
                + " (AdESeal-QC) dle nařízení (EU) č. 910/2014 (eIDAS), resp. první varianta"
                + " v rámci pojmu \"uznávaná elektronická pečeť\""
                + " dle [§ 9 odst. 2](" + URL_ACT_297_2016_S9 + ") zákona č. 297/2016 Sb.";
            case "kvalifikovanou elektronickou pečeť" ->
                "kvalifikovaná elektronická pečeť (QESeal) dle nařízení (EU) č. 910/2014 (eIDAS),"
                + " resp. druhá varianta pojmu \"uznávaná elektronická pečeť\""
                + " dle [§ 9 odst. 2](" + URL_ACT_297_2016_S9 + ") zákona č. 297/2016 Sb.";
            case "uznávanou elektronickou značku" ->
                "uznávaná elektronická značka dle původního zákona č. 227/2000 Sb."
                + " o elektronickém podpisu. Jde o starší českou právní kategorii,"
                + " předcházející pečeti dle nařízení eIDAS.";
            case "elektronickou značku" ->
                "elektronická značka dle původního zákona č. 227/2000 Sb. o elektronickém podpisu."
                + " Jde o starší českou právní kategorii, předcházející pečeti dle nařízení eIDAS.";
            default -> null;
        };
    }

    /** Seškrtne značky {@code **tučně**} a {@code [text](url)} na holý text. */
    private static String stripMarkup(String markup) {
        if (markup == null) return null;
        return markup.replace("**", "")
                     .replaceAll("\\[([^\\]]+)]\\([^)]+\\)", "$1");
    }

    private HBox certificationPermissionNote(String permission) {
        String text = switch (permission != null ? permission : "") {
            case "NO_CHANGE_PERMITTED" ->
                "Povolené změny v dokumentu: žádné — jakákoli úprava zneplatní tento certifikační podpis/pečeť";
            case "MINIMAL_CHANGES_PERMITTED" ->
                "Povolené změny v dokumentu: vyplňování formulářů, přidávání podpisů a pečetí";
            case "CHANGES_PERMITTED" ->
                "Povolené změny v dokumentu: vyplňování formulářů, přidávání podpisů a pečetí, vytváření a úpravy anotací";
            default ->
                "Povolené změny v dokumentu: neznámá úroveň oprávnění (" + permission + ")";
        };
        return greyNote(text);
    }

    /**
     * Věta pod hodnotou „Jde o:", která prvek označí za protipodpis a jmenuje jeho rodiče;
     * {@code null} u prvku, který protipodpisem není.
     *
     * <p>Sdílená Zjednodušeným i Rozšířeným přehledem, aby se o téže věci nemluvilo na každé
     * záložce jinak — týž důvod jako u {@link #pdfLockNote}.
     */
    static String counterSignatureNote(SignatureSummary sig, List<SignatureSummary> all) {
        Integer parent = counterSignaturePosition(sig, all);
        return parent == null ? null
                : "Jedná se o protipodpis (kontrasignaturu) k autentizačnímu prvku č. " + parent + ".";
    }

    /**
     * Kolikátým autentizačním prvkem v pořadí je rodič tohohle protipodpisu; {@code null}, není-li
     * prvek protipodpisem nebo není-li rodič v seznamu.
     *
     * <p>Počítá se z <b>hotového</b> seznamu prvků, protože jen ten zná pořadí, ve kterém je
     * čtenář uvidí — mezi podpisy se řadí i samostatná časová razítka.
     */
    static Integer counterSignaturePosition(SignatureSummary sig, List<SignatureSummary> all) {
        String parentId = sig == null ? null : sig.getCounterSignatureOfId();
        if (parentId == null || all == null) return null;
        for (int i = 0; i < all.size(); i++) {
            if (parentId.equals(all.get(i).getSignatureId())) return i + 1;
        }
        return null;
    }

    /** Drobná šedá poznámka pod hodnotou řádku, zarovnaná pod ni. */
    private HBox greyNote(String text) {
        Label note = new Label(text);
        note.setStyle("-fx-font-size: 11px; -fx-text-fill: #555555;");
        note.setWrapText(true);
        HBox box = new HBox(note);
        box.setPadding(new javafx.geometry.Insets(0, 0, 2, LBL_WIDTH + 8));
        return box;
    }

    /**
     * Věta o zámku, který podpis položil na podpisová pole, případně na celý dokument.
     *
     * <p>Sdílená všemi přehledy, aby se o téže věci nemluvilo na každé záložce jinak. Vědomě
     * nemluví o platnosti: zámek je pokyn pro prohlížeč, ne vada, a dokument uzamčený a přitom
     * dál platný je normální stav — právě takový je referenční „PAdES B-T kvalifikovnay
     * zamknuty.pdf“.
     *
     * <p>Rozlišuje dvě různě silné věci, které PDF zapisuje jedním mechanismem. Bez {@code /P}
     * je to zámek <b>formulářových polí</b> a o zbytku dokumentu neříká nic; s {@code /P} je to
     * omezení celého dokumentu na téže stupnici jako u certifikačního podpisu. Slévat obojí do
     * věty „dokument je uzamčen“ by tvrdilo víc, než v souboru stojí.
     *
     * @return null, když podpis žádný zámek neklade
     */
    static String pdfLockNote(SignatureSummary.PdfLock lock, boolean seal) {
        if (lock == null) return null;
        String by = seal ? "Touto pečetí" : "Tímto podpisem";
        if (lock.permission() == null) {
            // Bez /P zamyká podpis jen formulářová pole a o dokumentu neříká nic. Pak je rozsah
            // to jediné, co se dá říct.
            return by + " " + lockScopeClause(lock) + ".";
        }
        String note = by + " " + lockPermissionClause(lock.permission());
        String fields = lockFieldsClause(lock);
        return fields != null ? note + " " + fields : note;
    }

    /**
     * „jsou uzamčena všechna formulářová pole dokumentu“ — rozsah zámku, bez podmětu.
     *
     * <p>Rozsah a oprávnění jsou dvě nezávislé věci a musí se říct obě. Změřeno na sbírce:
     * DocuSign vydává {@code INCLUDE} nad jediným polem <b>a k tomu</b> {@code /P 3}, takže věta
     * postavená jen na oprávnění by zamlčela, že zamčené je jedno jediné pole, a věta postavená
     * jen na rozsahu by zamlčela omezení celého dokumentu.
     */
    private static String lockScopeClause(SignatureSummary.PdfLock lock) {
        List<String> fields = lock.fields();
        String named = String.join(", ", fields);
        return switch (lock.scope() != null ? lock.scope() : "") {
            case "ALL" -> "jsou uzamčena všechna formulářová pole dokumentu";
            case "INCLUDE" -> fields.isEmpty()
                    ? "jsou uzamčena formulářová pole vyjmenovaná v dokumentu"
                    : fields.size() == 1
                            ? "je uzamčeno formulářové pole " + named
                            : "jsou uzamčena formulářová pole " + named;
            case "EXCLUDE" -> fields.isEmpty()
                    ? "jsou uzamčena všechna formulářová pole dokumentu"
                    : "jsou uzamčena všechna formulářová pole kromě " + named;
            default -> "je na formulářová pole položen zámek, jehož rozsah se nepodařilo zjistit";
        };
    }

    /**
     * Hlavní věta u zámku s entry {@code /P} — co se stalo s <b>dokumentem</b>.
     *
     * <p>Vede, protože je to to podstatné, a ne rozsah polí. Uzamčený dokument nemusí mít jediné
     * formulářové pole: referenční „PAdES B-T kvalifikovnay zamknuty.pdf“ má v AcroFormu jen ten
     * jeden podpis, kterým se zamykalo, takže věta „uzamčena jsou všechna formulářová pole
     * dokumentu“ mluvila o množině s jediným, už podepsaným prvkem — formálně pravda, pro čtenáře
     * matoucí. Tímhle způsobem o tom mluví i Adobe Acrobat Reader („Dokument uzamkl(a):
     * Signature2“). Opraveno na námitku uživatele 6. 9. 2026.
     *
     * <p>{@code /P 2} a {@code /P 3} nejsou „uzamčeno“ bez přívlastku: obojí zapovídá zásah do
     * obsahu, ale dál povoluje vyplňování a podpisy, {@code /P 3} navíc anotace.
     */
    private static String lockPermissionClause(String permission) {
        return switch (permission) {
            case "NO_CHANGE_PERMITTED" ->
                "je dokument uzamčen: nejsou v něm povoleny žádné další změny.";
            case "MINIMAL_CHANGES_PERMITTED" ->
                "je dokument uzamčen proti změnám obsahu: povoleno zůstává jen vyplňování"
                + " formulářových polí a připojování dalších podpisů a pečetí.";
            case "CHANGES_PERMITTED" ->
                "je dokument uzamčen proti změnám obsahu: povoleno zůstává vyplňování formulářových"
                + " polí, připojování dalších podpisů a pečetí a vkládání či úprava poznámek"
                + " (anotací).";
            default ->
                "je dokument uzamčen: míra povolených změn je zapsána neznámou hodnotou ("
                + permission + ").";
        };
    }

    /**
     * Dovětek o polích u zámku, který už mluvil o dokumentu — a jen tehdy, když něco přidává.
     *
     * <p>{@code /Action /All} vedle {@code /P} nepřidává nic: dokument je omezený celý, takže
     * věta o „všech polích“ je jen jinými slovy totéž, a v dokumentu bez formulářových polí navíc
     * matoucí. Vyjmenovaný rozsah je něco jiného — DocuSign zamyká jedno konkrétní pole a k tomu
     * dává {@code /P 3}, a tam je právě to pole tou informací.
     *
     * @return null, když není co dodat
     */
    private static String lockFieldsClause(SignatureSummary.PdfLock lock) {
        List<String> fields = lock.fields();
        if (fields.isEmpty()) return null;
        String named = String.join(", ", fields);
        return switch (lock.scope() != null ? lock.scope() : "") {
            case "INCLUDE" -> fields.size() == 1
                    ? "Uzamčeno je formulářové pole " + named + "."
                    : "Uzamčena jsou formulářová pole " + named + ".";
            case "EXCLUDE" -> "Uzamčena jsou všechna formulářová pole kromě " + named + ".";
            default -> null;
        };
    }

    /** Row with a ContextMenu-copyable value label and an optional ⓘ tooltip icon. */
    private HBox rowWithTooltip(String labelText, String value, String tooltipText) {
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111;");
        lbl.setMinWidth(LBL_WIDTH);

        if (tooltipText == null) {
            return row(labelText, value);
        }

        MenuItem copyItem = new MenuItem("Kopírovat");
        copyItem.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(value);
            Clipboard.getSystemClipboard().setContent(cc);
        });

        Label icon = infoIcon(tooltipText);

        // TextFlow rather than a Label so the value wraps like every other row's value does, and
        // rather than an HBox so the icon stays right after the last word instead of being pushed
        // to the far right. Font size is set here because Text inherits it, and the value must
        // match the TextArea-based rows around it.
        Text valText = new Text(value);
        valText.setStyle("-fx-fill: #111111;");
        TextFlow flow = new TextFlow(valText, icon);
        flow.setStyle("-fx-font-size: 13px;");
        // TextFlow is a Node, not a Control, so the menu has to be popped up by hand.
        ContextMenu copyMenu = new ContextMenu(copyItem);
        flow.setOnContextMenuRequested(e ->
                copyMenu.show(flow, e.getScreenX(), e.getScreenY()));
        HBox.setHgrow(flow, Priority.ALWAYS);

        HBox row = new HBox(4, lbl, flow);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    /**
     * Bublina ⓘ vedle hodnoty: kolečko, které na kliknutí ukáže vysvětlující text.
     *
     * <p>Vytažené z {@link #rowWithTooltip}, aby ji uměl i {@link #richRow} — holé razítko VRI
     * potřebuje tutéž bublinu, ale hodnotu poskládanou z barevných úseků.
     */
    private Label infoIcon(String tooltipText) {
        Label icon = new Label("ⓘ");
        icon.setStyle("-fx-font-size: 13px; -fx-text-fill: #003189; -fx-cursor: hand; -fx-padding: 0 0 0 4;");
        Tooltip tip = new Tooltip(tooltipText);
        tip.setWrapText(true);
        tip.setMaxWidth(380);
        // The default 5 s is too short for the longer notes: the reader loses them mid-sentence.
        tip.setShowDuration(javafx.util.Duration.INDEFINITE);
        tip.setStyle("-fx-font-size: 11px; -fx-background-color: #ffffff; -fx-text-fill: #111111; -fx-border-color: #cccccc; -fx-border-width: 1;");
        Tooltip.install(icon, tip);
        // Escape alone is not a discoverable way out of a bubble that never times out. autoHide
        // closes it on a click anywhere else; clicking the icon again closes it too.
        tip.setAutoHide(true);
        long[] lastHidden = {0};
        tip.setOnHidden(e -> lastHidden[0] = System.currentTimeMillis());
        icon.setOnMouseClicked(e -> {
            if (tip.isShowing()) { tip.hide(); return; }
            // autoHide reacts to the press before this handler runs, so a click on the icon while
            // the bubble is open arrives here as "not showing" — reopening it would make the
            // bubble impossible to close from the icon.
            if (System.currentTimeMillis() - lastHidden[0] < 250) return;
            tip.show(icon, e.getScreenX() + 12, e.getScreenY() + 12);
        });
        return icon;
    }

    private HBox row(String labelText, String value) {
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111;");
        lbl.setMinWidth(LBL_WIDTH);

        javafx.scene.control.TextArea val =
                ResultView.selectable(value, "-fx-text-fill: #111111;", false);
        HBox.setHgrow(val, Priority.ALWAYS);

        HBox box = new HBox(4, lbl, val);
        box.setAlignment(Pos.BASELINE_LEFT);
        return box;
    }

    // ------------------------------------------------------------------
    // Evidence records
    // ------------------------------------------------------------------

    /**
     * Renders evidence records into {@code card}. Shared by the per-signature block and by the
     * standalone block shown when a document carries records but no signature at all.
     */
    void appendEvidenceRecords(VBox card, List<EvidenceRecordEntry> records,
                               boolean documentSubmitted) {
        for (int i = 0; i < records.size(); i++) {
            EvidenceRecordEntry er = records.get(i);
            String head = (records.size() == 1 ? "Evidenční záznam" : "Evidenční záznam č. " + (i + 1));

            StringBuilder what = new StringBuilder(er.type());
            if (er.origin() != null) what.append("  —  ").append(er.origin());
            if (er.filename() != null && !er.filename().isBlank()) {
                what.append("  —  ").append(er.filename());
            }
            card.getChildren().add(row(head + ":", what.toString()));

            // The headline claim. Everything below explains how it was reached, so it goes first.
            String verdict;
            if (!er.providesProof()) {
                verdict = "ne — " + whyNoProof(er, documentSubmitted);
            } else if (er.partiallyChecked()) {
                verdict = "ano, pro předložený soubor — dokládá jeho existenci k "
                        + TimeUtil.format(er.earliestProof());
            } else {
                verdict = "ano — dokládá existenci krytých dat k " + TimeUtil.format(er.earliestProof());
            }
            card.getChildren().add(row("Poskytuje důkaz existence:", verdict));

            String coverage;
            if (er.digestMatchersTotal() == 0) {
                coverage = "žádné (záznam neuvádí ani jeden otisk)";
            } else if (er.allObjectsIntact()) {
                coverage = CzechPlural.objects(er.digestMatchersTotal())
                        + " — všechny otisky souhlasí";
            } else {
                StringBuilder c = new StringBuilder();
                c.append("záznam kryje ").append(CzechPlural.objects(er.digestMatchersTotal()))
                        .append("; ověřeno ").append(er.digestMatchersIntact());
                if (er.digestMatchersBroken() > 0) {
                    c.append(", nesouhlasí ").append(er.digestMatchersBroken());
                }
                if (er.digestMatchersMissing() > 0) {
                    c.append(", nepředloženo ").append(er.digestMatchersMissing());
                }
                coverage = c.toString();
            }
            card.getChildren().add(er.partiallyChecked()
                    ? rowWithTooltip("Kryté objekty:", coverage, PARTIAL_NOTE)
                    : row("Kryté objekty:", coverage));

            // The record protects files that were not part of this verification. Offer to check
            // them rather than leaving the reader to work out that it is even possible — but in
            // a window of its own, so this result stays about this one document.
            if (er.digestMatchersMissing() > 0 && pdfOrigFile != null) {
                Button checkOthers = new Button("Zkontrolovat další kryté soubory…");
                checkOthers.setOnAction(ev -> EvidenceRecordCoverageWindow.show(
                        checkOthers.getScene().getWindow(), pdfOrigFile,
                        er.digestMatchersIntact(), er.digestMatchersTotal()));
                Region indent = new Region();
                indent.setMinWidth(LBL_WIDTH + 4);
                HBox btnRow = new HBox(indent, checkOthers);
                btnRow.setPadding(new Insets(2, 0, 2, 0));
                card.getChildren().add(btnRow);
            }

            if (!er.coveredObjects().isEmpty()) {
                card.getChildren().add(row("Kryje soubory:", String.join(", ", er.coveredObjects())));
            }

            for (int t = 0; t < er.timestamps().size(); t++) {
                TimestampEntry ts = er.timestamps().get(t);
                String kindSuffix = switch (ts.tsKind() != null ? ts.tsKind() : "er") {
                    case "er_ts_renewal"       -> " (obnova razítka)";
                    case "er_hashtree_renewal" -> " (obnova hash-tree)";
                    default                    -> " (archivní)";
                };
                String label = (er.timestamps().size() == 1
                        ? "Razítko v záznamu" : "Razítko v záznamu č. " + (t + 1)) + kindSuffix + ":";
                StringBuilder val = new StringBuilder(TimeUtil.format(ts.time()));
                if (ts.issuer() != null && !ts.issuer().isBlank()) {
                    val.append("  —  vydavatel: ").append(ts.issuer());
                }
                if (ts.notAfter() != null) {
                    val.append("  —  platnost certifikátu TSA do: ").append(TimeUtil.format(ts.notAfter()));
                }
                card.getChildren().add(row(label, val.toString()));
                if (!ts.passed()) {
                    Label errLbl = new Label(timestampFailureLine(ts));
                    errLbl.setStyle("-fx-text-fill: #cc0000;");
                    javafx.scene.layout.Region indent = new javafx.scene.layout.Region();
                    indent.setMinWidth(LBL_WIDTH + 4);
                    card.getChildren().add(new HBox(indent, errLbl));
                }
            }

            // How long the record holds without attention. Deliberately a value, not a situation:
            // situations in AnEP are findings that are either true or false, whereas this is a
            // date, and turning it into a finding would mean inventing a threshold for "soon".
            TimestampEntry newest = er.timestamps().stream()
                    .filter(TimestampEntry::passed)
                    .filter(t -> t.notAfter() != null)
                    .max(java.util.Comparator.comparing(TimestampEntry::time))
                    .orElse(null);
            if (newest != null) {
                card.getChildren().add(rowWithTooltip("Lhůta pro obnovu záznamu:",
                        TimeUtil.format(newest.notAfter()), RENEWAL_NOTE));
            }

            // A schema complaint is not a failed proof, and must not be dressed as one: records
            // whose hash tree and time-stamp are sound routinely carry extra metadata their
            // generator invented. Hence an explanatory note in ordinary text, not a red warning.
            if (!er.structureValid()) {
                card.getChildren().add(rowWithTooltip("Odchylka od schématu:",
                        String.join("; ", er.structureMessages()),
                        STRUCTURE_NOTE));
            }
        }
    }

    private static final String STRUCTURE_NOTE =
            "Záznam se v něčem odchyluje od schématu svého standardu (RFC 4998 pro ERS, "
            + "RFC 6283 pro XMLERS).\n\n"
            + "Nejde o chybu důkazu. Strom otisků i časové razítko se ověřují nezávisle na "
            + "schématu, a pokud sedí, záznam svou funkci plní. Nejčastější příčinou jsou "
            + "doplňková metadata, která do záznamu přidal nástroj, jenž ho vytvořil, na místo, "
            + "kde je standard nepředpokládá.\n\n"
            + "Odchylka ale může vadit jiné aplikaci, která záznam odmítne otevřít — proto se "
            + "uvádí.";

    private static final String RENEWAL_NOTE =
            "Datum, do kterého platí certifikát razítkovací autority u nejnovějšího archivního "
            + "časového razítka v záznamu.\n\n"
            + "Do té doby je potřeba provést obnovu časového razítka (RFC 4998, čl. 5.2): "
            + "nad dosavadní posloupnost razítek se připojí nové, čímž se platnost záznamu "
            + "prodlouží. Po tomto datu už staré razítko nelze ověřit a řetěz se přetrhne.\n\n"
            + "Druhý druh obnovy — obnova stromu otisků (čl. 5.3) — se řídí tím, kdy zeslábne "
            + "použitá hashovací funkce. Ta se nevyčerpá k žádnému pevnému datu, takže se zde "
            + "žádná lhůta neuvádí; posuzuje se podle doporučení k síle algoritmů.";

    private static final String PARTIAL_NOTE =
            "Záznam kryje víc souborů, než kolik jich bylo předloženo k ověření.\n\n"
            + "Není to závada. Každý krytý soubor má ve stromu otisků vlastní list, takže "
            + "u předložených souborů je důkaz úplný a platný bez ohledu na to, jestli máte "
            + "po ruce i ostatní.\n\n"
            + "O nepředložených souborech ale záznam v tomto ověření neříká nic — pokud "
            + "potřebujete doložit i je, předložte je také.";

    private String whyNoProof(EvidenceRecordEntry er, boolean documentSubmitted) {
        if (er.digestMatchersTotal() == 0) return "záznam neuvádí žádný krytý otisk";
        if (er.digestMatchersBroken() > 0) {
            return "otisk předloženého objektu nesouhlasí — data byla po vytvoření záznamu změněna "
                    + "(nesouhlasí " + er.digestMatchersBroken() + " z " + er.digestMatchersTotal() + ")";
        }
        if (er.digestMatchersIntact() == 0) {
            // Nothing matched. Whether that is the record's doing or the reader's depends on
            // something the digest counts cannot show: a file that was submitted but is covered
            // by no leaf of the tree looks exactly like a file that was never submitted.
            return documentSubmitted
                    ? "předložený soubor tento evidenční záznam nekryje — neodpovídá žádnému "
                      + "z otisků, které záznam obsahuje"
                    : "žádný z krytých objektů nebyl předložen k ověření";
        }
        if (er.timestamps().isEmpty()) return "záznam neobsahuje žádné časové razítko";
        return "žádné z časových razítek záznamu se nepodařilo ověřit";
    }
}
