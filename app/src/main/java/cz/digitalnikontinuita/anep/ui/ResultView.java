package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.EvidenceRecordEntry;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import java.time.Instant;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.text.Font;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.List;
import java.util.Map;

/** Renders each found signature as a full-width card, stacked vertically. */
public class ResultView {

    /**
     * Razítkovací služba a vybraný zdroj pro protokol; {@code null} znamená, že se na razítko
     * nebude ptát. Drží to {@code MainView}, tenhle pohled to jen předá dál.
     */
    private PrintUtil.ProtocolExtras protocolExtras;

    public void setProtocolExtras(PrintUtil.ProtocolExtras extras) {
        this.protocolExtras = extras;
    }

    /**
     * Krátký popisek důvodu do seznamu „Zjištěné důvody“, ze souboru {@code texty/duvody.txt}.
     */
    static final Map<Integer, String> REASON_WORKING_LABELS = ReasonTexts.shortLabels();


    // Obě upozornění dřív končila radou zaškrtnout „Využij i revokační informace, vydané po
    // expiraci certifikátu" a slibem, že pak bude platnost posouzena spolehlivě — dvojka k tomu
    // rovnou tvrdila „Podpis je tedy neplatný". Ani jedno neplatí. Ta volba rozhoduje o tom, zda
    // se smí použít revokační údaj; chybí tu ale něco jiného — důkaz, kdy podpis vznikl — a ten
    // žádné nastavení nedodá. Po jejím zaškrtnutí se výsledek nezmění na neplatný, zůstane
    // neznámý (viz zrušený přepis verdiktu v buildCard). Slibovat spolehlivé posouzení tedy
    // znamenalo poslat čtenáře přepínat něco, co s jeho dokumentem nehne.
    //
    // Znění všech čtyř upozornění jsou od 15. 9. 2026 v souboru texty/situace.txt; tady zůstává
    // jen jméno, pod kterým si je bere zbytek aplikace.
    static final String S20_WARNING_TEXT = SituationTexts.get("20", "text");

    static final String S21_WARNING_TEXT = SituationTexts.get("21", "text");

    /**
     * Situace 48 — revokovaný certifikát bez důkazu o čase podpisu.
     *
     * <p>Text stojí zvlášť, aby ho vedle seznamu situací mohla použít i tlačítka „Upozornění“
     * v Zjednodušeném a Rozšířeném přehledu. Datum revokace do něj doplňuje
     * {@link #revokedNoProofWarning(SignatureSummary)}, zná-li je ověření.
     */
    static final String S48_WARNING_TEXT = SituationTexts.warning(48, null);

    /**
     * Situace 2 — revokace před rozhodným okamžikem, který určilo časové razítko.
     *
     * <p>Vlastní text vedle {@link #S48_WARNING_TEXT}, protože se liší tím podstatným: tady
     * důkaz existence je, jen ukazuje na špatnou stranu. Do 27. 8. 2026 tu upozornění nestálo
     * vůbec — situace 2 tehdy překlápěla verdikt na „neplatný" a vysvětlovala se řádkem „Důvod
     * neplatnosti". Po zrušení toho přepisu by týž dokument zůstal u holého „neznámý" bez
     * jediné zmínky o revokaci, kdežto jeho varianta B (situace 48) upozornění dostávala.
     */
    static final String S2_WARNING_TEXT = SituationTexts.warning(2, null);

    /** Text upozornění pro situaci 2, s datem revokace, je-li známo. */
    static String revokedBeforeDecisiveWarning(SignatureSummary sig) {
        return SituationTexts.warning(2, sig.getCertRevokedAt());
    }

    /** Totéž pro situaci 48. Bez data by čtenář musel jít hledat do Analýzy jedinou věc,
     *  kvůli které upozornění čte. */
    static String revokedNoProofWarning(SignatureSummary sig) {
        return SituationTexts.warning(48, sig.getCertRevokedAt());
    }

    /**
     * Vysvětlivky situací, načtené ze souboru {@code texty/situace.txt}.
     *
     * <p>Do 15. 9. 2026 stály přímo tady, jako přes tři sta řádků slepovaných řetězců;
     * viz {@link SituationTexts}.
     */
    static final Map<Integer, String> SITUATION_LABELS = SituationTexts.labels();


    /**
     * Důvody, které mají v souboru vyplněnou radu pro zkušenější uživatele.
     *
     * <p>Většina z nich jsou ty, u nichž je překážkou revokační údaj — vydaný po expiraci (2),
     * od expirovaného vydavatele (6), s neznámým stavem (10), jinak nepoužitelný (11), vůbec
     * nezískaný (12) nebo takový, že o certifikátu nic neříká (25, 29) —, a ty mají všechny touž
     * radu o úložišti vlastních CRL. Radu ale smí nést kterýkoli důvod a některé mají vlastní:
     * 3 („vyjádřit důvěru certifikátu") a 4 („ověřit k zadanému datu").
     *
     * <p>Kolik řádků z toho na kartě bude, rozhoduje znění, ne počet důvodů — viz
     * {@link AdvisorView#expertTipRows}.
     *
     * <p>Výčet se od 15. 9. 2026 neurčuje tady, nýbrž souborem: důvod v něm je právě tehdy,
     * má-li vyplněný klíč {@code zkusenejsi}. Text té rady je tam taky, po důvodech, takže smí
     * být u každého jiný.
     *
     * <p>⚠ Jsou to jen <b>katalogové</b> oddíly. Od 19. 9. 2026 smí radu nést i náhradní znění
     * příčiny ({@code [21/NO_REVOCATION]}), a tam už číslo důvodu nestačí — o řádku na kartě
     * proto rozhoduje {@link AdvisorView#expertTip}, ne tenhle výčet.
     */
    static final java.util.Set<Integer> EXPERT_CRL_TIP_REASONS = ReasonTexts.expertTipReasons();

    record ReasonEntry(String problem, String detail, String solution) {}

    /**
     * Katalog důvodů, načtený ze souboru {@code texty/duvody.txt}.
     *
     * <p>Do 14. 9. 2026 stál tenhle katalog přímo tady, jako tři sta řádků slepovaných
     * řetězců. Přeformulovat větu proto znamenalo zásah do zdrojového kódu. Znění jsou nyní
     * v souboru, který jde otevřít v editoru; viz {@link ReasonTexts}.
     */
    static final java.util.Map<Integer, ReasonEntry> REASONS = ReasonTexts.catalogue();

    /**
     * Applies a plastic (raised, gradient) style with hover and press feedback to a button.
     *
     * <p><b>A minimum width, aby si tlačítko udrželo svůj nápis.</b> Všechna tři tahle tlačítka
     * stojí v řádku vedle zalamovaného popisku, který má {@code Hgrow ALWAYS} — a zalamovaný
     * popisek hlásí preferovanou šířku celého textu <b>na jeden řádek</b>. Ta přeteče vždycky,
     * takže {@code HBox} zkracuje potomky na jejich nejmenší šířku a tlačítku bez minima zbyde
     * šířka výpustky. Vypadá to jako oranžový obdélníček se třemi tečkami; nález uživatele
     * 22. 9. 2026 u poznámky situace 51.
     *
     * <p>Změřeno, ne odhadnuto ({@link StaleRevocationNoteLayoutTest}): tlačítko mělo 25 bodů
     * místo 71, a to i v okně širokém 1 200 bodů — o nedostatek místa tedy nikdy nešlo.
     */
    private static void applyPlasticStyle(Button btn,
            String normalTop, String normalBottom,
            String hoverTop,  String hoverBottom,
            String pressTop,  String pressBottom,
            String border) {
        String base = "-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: white;"
                + " -fx-background-radius: 4; -fx-border-radius: 4;"
                + " -fx-border-color: " + border + "; -fx-border-width: 1;"
                + " -fx-cursor: hand;"
                + " -fx-focus-color: transparent; -fx-faint-focus-color: transparent;";
        String normal  = base + " -fx-padding: 2 7 2 7;"
                + " -fx-background-color: linear-gradient(to bottom, " + normalTop + ", " + normalBottom + ");"
                + " -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.40), 2, 0, 0, 1);";
        String hover   = base + " -fx-padding: 2 7 2 7;"
                + " -fx-background-color: linear-gradient(to bottom, " + hoverTop + ", " + hoverBottom + ");"
                + " -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.55), 4, 0, 0, 2);";
        String pressed = base + " -fx-padding: 3 7 1 7;"
                + " -fx-background-color: linear-gradient(to bottom, " + pressTop + ", " + pressBottom + ");"
                + " -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.20), 1, 0, 0, 0);"
                + " -fx-translate-y: 1;";
        btn.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        btn.setStyle(normal);
        btn.setOnMouseEntered(e -> btn.setStyle(hover));
        btn.setOnMouseExited(e  -> btn.setStyle(normal));
        btn.setOnMousePressed(e -> btn.setStyle(pressed));
        btn.setOnMouseReleased(e -> btn.setStyle(btn.isHover() ? hover : normal));
    }

    static Button makePodrobnejiButton(String detail) {
        Button btn = new Button("Podrobněji");
        btn.setFocusTraversable(false);
        applyPlasticStyle(btn,
                "#5b8ec4", "#336699",
                "#6fa0d8", "#3d7ab8",
                "#2a5880", "#1e3a60",
                "#1e3d60");
        btn.setOnAction(e -> showDetailDialog(detail));
        return btn;
    }

    /**
     * Okno s podrobnostmi k jednomu důvodu. Vytaženo z {@link #makePodrobnejiButton}, protože
     * je otevírá i textový odkaz „Podrobněji …“ v Analýze — obojí musí ukázat totéž.
     */
    static void showDetailDialog(String detail) {
        javafx.scene.control.Alert alert =
                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
        alert.setTitle("Podrobnosti");
        alert.setHeaderText(null);
        Label content = new Label(detail);
        content.setWrapText(true);
        content.setMaxWidth(430);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(480);
        alert.showAndWait();
    }
    private static final String CARD_STYLE =
            "-fx-border-color: #cccccc; -fx-border-width: 1; -fx-border-radius: 4;";


    private final VBox cardsBox = new VBox();
    private final ScrollPane scroll = new ScrollPane(cardsBox);
    private final Button pdfBtn = new Button("Generovat protokol jako PDF");
    private final VBox root;

    private File pdfSigFile;
    private File pdfOrigFile;

    /**
     * Okamžik, kdy ověřování proběhlo — ne kdy vznikl protokol. Ty dva se můžou lišit o hodiny,
     * když uživatel mezitím jen listuje záložkami, a protokol vypovídá o ověření.
     */
    private java.time.Instant verificationTime;

    /**
     * Nastavení, bez kterého by výsledek vyšel jinak, sesbírané přes všechny podpisy dokumentu.
     * Jen rozhodující, ne všechno zapnuté: co se na výsledku neprojevilo, o něm nic nevypovídá.
     */
    private java.util.List<String> decisiveSettings = java.util.List.of();

    void setVerificationTime(java.time.Instant t) { this.verificationTime = t; }

    private boolean timestampStandardsMode = true;

    public void setTimestampStandardsMode(boolean v) { this.timestampStandardsMode = v; }

    void setFileInfo(File sig, File orig) {
        pdfSigFile = sig;
        pdfOrigFile = orig;
    }

    /** Odkaz z přílohy {@code legalisation-metadata.xml}; {@code null}, když ho dokument nenese. */
    void setLegalisationLink(cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Link link) {
        this.legalisationLink = link;
    }

    private cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Link legalisationLink;

    /** Rámeček s větami o odkazech — drží se kvůli překreslení po kontrole v okně. */
    private VBox legalisationBox;

    public ResultView() {
        // Kontrola odkazu běží ve vlastním okně, klidně až nad hotovou kartou; věty o řetězu
        // se proto překreslují na zápis do záznamu, ne jen při stavbě karet.
        cz.digitalnikontinuita.anep.core.LegalisationLinkLog.onChange(
                () -> javafx.application.Platform.runLater(this::refreshLegalisationBox));
        cardsBox.setPadding(new Insets(12));
        cardsBox.setSpacing(0);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        pdfBtn.setDisable(true);
        pdfBtn.setOnAction(e -> PrintUtil.saveAsPdf(cardsBox,
                (Stage) pdfBtn.getScene().getWindow(),
                PrintUtil.protocolFileName("ZP", pdfSigFile),
                pdfSigFile, pdfOrigFile, "Zjednodušený protokol",
                verificationTime, decisiveSettings, protocolExtras));
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
        legalisationBox = null;
    }

    public void show(ValidationResult result) {
        pdfBtn.setDisable(false);
        cardsBox.getChildren().clear();

        List<SignatureSummary> sigs = result.getSignatures();
        elements = sigs;
        // Sjednocení přes podpisy, ne seznam za každý zvlášť: protokol mluví o běhu jako celku
        // a totéž nastavení se u dvou podpisů opakovat nemá. Pořadí se drží podle prvního výskytu.
        java.util.LinkedHashSet<String> decisive = new java.util.LinkedHashSet<>();
        for (SignatureSummary sig : sigs) {
            if (sig.getDecisiveSettings() != null) decisive.addAll(sig.getDecisiveSettings());
        }
        decisiveSettings = List.copyOf(decisive);

        Label countLabel = authCountHeader(result);
        cardsBox.getChildren().add(countLabel);
        // Změna obsahu po podpisu: tahle záložka se otevírá první a ukazuje zelené „platný“,
        // takže bez upozornění by čtenář uvěřil, že dokument je ten podepsaný. Jen odkaz,
        // celý blok stojí v Rozšířeném přehledu (rozhodnutí uživatele 9. 10. 2026).
        String changed = RevisionTailBlock.warningLine(result);
        if (changed != null) {
            Label warn = new Label(changed);
            warn.setWrapText(true);
            warn.setMaxWidth(Double.MAX_VALUE);
            warn.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            warn.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #9a6700;"
                    + " -fx-background-color: #f3ead6; -fx-padding: 6 10 6 10;");
            cardsBox.getChildren().add(warn);
        }
        if (sigs.isEmpty()) return;
        cardsBox.getChildren().add(new Separator());

        // In strict timestamp mode, flag signatures where the LAST timestamp's TSA cert has expired.
        // Only the last timestamp matters for the overall validity assessment — it's evaluated at
        // current time. Earlier timestamps are covered by subsequent timestamps (their TSA cert
        // only needed to be valid until the next valid timestamp was produced).
        java.util.Set<String> tsExpiredSigIds = new java.util.HashSet<>();
        if (!timestampStandardsMode) {
            DiagnosticData dd = result.getDiagnosticData();
            if (dd != null) {
                java.util.Date now = new java.util.Date();
                for (eu.europa.esig.dss.diagnostic.SignatureWrapper sig : dd.getSignatures()) {
                    java.util.List<TimestampWrapper> allTs = sig.getTimestampList().stream()
                            .filter(ts -> ts.getProductionTime() != null)
                            .sorted(java.util.Comparator.comparing(TimestampWrapper::getProductionTime))
                            .collect(java.util.stream.Collectors.toList());
                    if (!allTs.isEmpty()) {
                        TimestampWrapper last = allTs.get(allTs.size() - 1);
                        if (hasExpiredCertInTsChainAt(last, dd, now)) {
                            tsExpiredSigIds.add(sig.getId());
                        }
                    }
                }
            }
        }

        boolean showHeaders = sigs.size() > 1;
        int itemCounter = 0;
        for (int i = 0; i < sigs.size(); i++) {
            SignatureSummary s = sigs.get(i);
            String header = null;
            if (showHeaders) {
                header = "Autentizační prvek č.: " + (++itemCounter);
            }
            cardsBox.getChildren().add(buildCard(s, header, tsExpiredSigIds));
            if (i < sigs.size() - 1) {
                Separator sep = new Separator();
                sep.setPadding(new Insets(10, 0, 10, 0));
                cardsBox.getChildren().add(sep);
            }
        }

        // Odkaz z eLegalizace je vlastnost dokumentu, ne toho kterého podpisu — proto vlastní
        // rámeček za kartami prvků, a ne řádek uvnitř některé z nich.
        legalisationBox = legalisationBox();
        if (legalisationBox != null) {
            Separator sep = new Separator();
            sep.setPadding(new Insets(10, 0, 10, 0));
            cardsBox.getChildren().add(sep);
            cardsBox.getChildren().add(legalisationBox);
        }
    }

    /**
     * Věty o tom, kam předložený dokument odkazuje — a kam odkazuje ten odkazovaný, došlo-li
     * na jeho kontrolu.
     *
     * <p>Zjednodušený přehled neříká, jak kontrola dopadla; na to je Rozšířený přehled. Tady jde
     * o jedinou věc, kterou má čtenář vědět hned: že dokument, který drží v ruce, sám o sobě
     * není vším — odkazuje na jiný soubor, a ten může odkazovat dál.
     */
    private VBox legalisationBox() {
        java.util.List<String> vety = legalisationSentences();
        if (vety.isEmpty()) return null;
        VBox box = new VBox(4);
        box.setPadding(new Insets(8, 10, 8, 10));
        box.setStyle(CARD_STYLE);
        for (String veta : vety) {
            Label lbl = new Label(veta);
            lbl.setWrapText(true);
            lbl.setStyle("-fx-text-fill: #111111;");
            box.getChildren().add(lbl);
        }
        return box;
    }

    /** Překreslení po kontrole, kterou uživatel provedl v okně nad hotovou kartou. */
    private void refreshLegalisationBox() {
        if (legalisationBox == null) return;
        legalisationBox.getChildren().clear();
        for (String veta : legalisationSentences()) {
            Label lbl = new Label(veta);
            lbl.setWrapText(true);
            lbl.setStyle("-fx-text-fill: #111111;");
            legalisationBox.getChildren().add(lbl);
        }
    }

    /**
     * Jedna věta za každý článek řetězu. První mluví o předloženém dokumentu, další o tom, co
     * uživatel předložil jako odkazovaný — a to jen tehdy, když ten dokument opravdu nese
     * vlastní přílohu s otisky.
     */
    private java.util.List<String> legalisationSentences() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (legalisationLink == null) return out;
        java.util.List<cz.digitalnikontinuita.anep.core.LegalisationLinkLog.Step> chain =
                cz.digitalnikontinuita.anep.core.LegalisationLinkLog.chain(pdfSigFile);

        out.add("Předložený dokument se prostřednictvím otisků (eLegalizace) odkazuje na dokument "
                + cil(legalisationLink, chain.isEmpty() ? null : chain.get(0)) + ".");

        for (int i = 0; i < chain.size(); i++) {
            var step = chain.get(i);
            out.add(vysledekVety(step));
            if (step.candidateLink() == null) return out;
            var dalsi = i + 1 < chain.size() ? chain.get(i + 1) : null;
            out.add("Dokument " + step.candidate().getName()
                    + " se prostřednictvím otisků (eLegalizace) odkazuje na dokument "
                    + cil(step.candidateLink(), dalsi) + ".");
        }
        // Poslední odkaz v řadě, ke kterému nikdo nic nepředložil. Mlčet o tom by znamenalo
        // nechat větu „odkazuje na dokument X“ vyznít, jako by se X ověřilo.
        out.add("Tento dokument zatím nebyl ke kontrole otisků předložen.");
        return out;
    }

    /**
     * Jméno odkazovaného dokumentu. Nejdřív to, které uvádí sama příloha; podpisové archy ho
     * neuvádějí, a pak poslouží jméno souboru, který uživatel jako odkazovaný předložil. Nemáme-li
     * ani to, řekne se to rovnou — tvrdit jméno, které nikdo neuvedl, by bylo horší než mlčet.
     */
    /**
     * Jak dopadlo porovnání otisků — jednou větou.
     *
     * <p>Zjednodušený přehled neuvádí, které funkce se porovnávaly ani jaké hodnoty vyšly; na to
     * je Rozšířený přehled. Zůstává jediné, co čtenáři říká, jestli má v ruce ten dokument,
     * o kterém příloha mluví.
     */
    private static String vysledekVety(
            cz.digitalnikontinuita.anep.core.LegalisationLinkLog.Step step) {
        String jmeno = "\u201e" + step.candidate().getName() + "\u201c";
        return switch (step.check().outcome()) {
            case MATCH -> "Otisky předloženého dokumentu " + jmeno + " souhlasí.";
            case MISMATCH -> "Otisky předloženého dokumentu " + jmeno + " nesouhlasí — není to"
                    + " dokument, na který se odkazuje.";
            case UNSUPPORTED -> "Otisky předloženého dokumentu " + jmeno + " porovnat nešlo:"
                    + " žádnou z uvedených hašovacích funkcí tato aplikace nezná.";
            case ERROR -> "Otisky předloženého dokumentu " + jmeno + " porovnat nešlo:"
                    + " soubor se nepodařilo přečíst.";
        };
    }

    private static String cil(cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Link link,
                              cz.digitalnikontinuita.anep.core.LegalisationLinkLog.Step step) {
        // Jméno v uvozovkách: „odkazuje na dokument dokument s legalizovanym…“ je pravda, ale
        // čte se jako přeřeknutí — a jména souborů z eLegalizace tak vypadají běžně.
        if (link.targetName() != null && !link.targetName().isBlank()) {
            return "„" + link.targetName() + "“";
        }
        if (step != null) return "„" + step.candidate().getName() + "“";
        return ", jehož jméno příloha neuvádí";
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
     * Podpis bez důkazu o čase svého vzniku, jehož certifikát už expiroval — prvek, u kterého
     * Zjednodušený přehled upozornění nevysází.
     *
     * <p>Tahle záložka odpovídá na otázku „platí to?“ a u takového prvku zní odpověď „neznámý“
     * bez ohledu na to, co která revokační informace říká. Nechybí totiž revokační údaj, nýbrž
     * důkaz, kdy podpis vznikl — a ten nedodá žádné nastavení ani nic staženého ze sítě; obě
     * znění upozornění to o sobě samy píšou. Tlačítko vedle pruhu ale vypadá jako výzva něco
     * udělat a posílá čtenáře přepínat volby, které s jeho dokumentem nehnou.
     *
     * <p>Výklad se neztrácí, jen se nabízí tam, kde je na něj místo: v Rozšířeném přehledu
     * stojí totéž tlačítko s týmž textem a v Analýze celý důvod i s doporučením. Táž úvaha jako
     * u situace 24 v {@link #buildCard}.
     *
     * <p>Expirace se posuzuje k okamžiku, ke kterému se ověřovalo — tedy k zadanému času, je-li
     * nastaven, protože jinak by se u ověření do minulosti ptala na něco jiného než zbytek karty.
     * Průběh ověřování ten okamžik nese; {@code fallback} se uplatní jen tam, kde průběh není
     * (osiřelý prvek), a tam je {@code certNotAfter} stejně prázdné.
     */
    static boolean noProofAndCertExpired(SignatureSummary sig, java.time.Instant fallback) {
        if (sig.hasValidTimestamp() || sig.getCertNotAfter() == null) return false;
        java.time.Instant reference = sig.getValidationCourse() != null
                        && sig.getValidationCourse().validationTime() != null
                ? sig.getValidationCourse().validationTime()
                : fallback != null ? fallback : java.time.Instant.now();
        return sig.getCertNotAfter().isBefore(reference);
    }

    /** Prvky tohoto ověření v pořadí, ve kterém je čtenář vidí — kvůli číslování rodiče
     *  u protipodpisu. Karta se staví po jednom prvku, ale číslo jiného prvku se dá zjistit
     *  jen ze seznamu. */
    private List<SignatureSummary> elements = List.of();

    private VBox buildCard(SignatureSummary sig, String header, java.util.Set<String> tsExpiredSigIds) {
        boolean tsExpired = tsExpiredSigIds.contains(sig.getSignatureId());
        VBox card = new VBox(8);
        card.setPadding(new Insets(10));
        card.setStyle(CARD_STYLE);

        if (header != null) {
            Label headerLbl = new Label(header);
            headerLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #003189;");
            card.getChildren().add(headerLbl);
        }

        String rawLegalType = sig.getLegalType() != null
                ? sig.getLegalType()
                : sig.getIndication()
                  + (sig.getSubIndication() != null ? " / " + sig.getSubIndication() : "");

        String legalType = simplifyLegalType(rawLegalType);

        String infoTip = ExtendedView.legalTypeTooltip(legalType);
        String legalTypeText = ExtendedView.nominativeLegalType(legalType);
        String legalTypeDisplay = sig.isCertification()
                ? "(certifikační) " + legalTypeText : legalTypeText;
        card.getChildren().add(row(ExtendedView.legalTypeLabel(legalType), legalTypeDisplay, infoTip));
        String counterNote = ExtendedView.counterSignatureNote(sig, elements);
        if (counterNote != null) card.getChildren().add(greyNote(counterNote));

        // Stav platnosti
        boolean isTs = "TimeStampToken".equals(sig.getFormat());
        // Czech grammatical gender:
        //   podpis (masculine) → platný / neplatný / neznámý   ← also the fallback for unknown type
        //   pečeť/značka (feminine) → platná / neplatná / neznámý
        //   razítko (neuter) → platné / neplatné / neznámý
        // "neznámý" uses masculine for all three types.
        boolean isSealForGender = !isTs
                && (rawLegalType.toLowerCase().contains("pečeť") || rawLegalType.toLowerCase().contains("značk"))
                && !rawLegalType.toLowerCase().contains("podpis");
        String indication = tsExpired ? "INDETERMINATE" : sig.getIndication();
        // S1: the signed content was altered. DSS 6.4 folds the message-digest check into
        // SignerInformation.verify(), so a content mismatch invalidates the signing-certificate
        // candidate and the ISC block — which runs before CV — reports
        // NO_SIGNING_CERTIFICATE_FOUND, masking the real HASH_FAILURE behind its own consequence.
        // S1 reads the digest matchers directly and is not fooled by that. The reference DSS
        // online service reports TOTAL_FAILED/HASH_FAILURE here, which is also what
        // EN 319 102-1 prescribes; "neznámý" would understate a provably broken document.
        // Applied first so a broken document cannot be relabelled by the rules below.
        if ("INDETERMINATE".equals(indication) && sig.getDetectedSituations().contains(1)) {
            indication = "TOTAL_FAILED";
        }
        // REVOKED_NO_POE už verdikt nepřepisuje. Do 27. 8. 2026 tu stálo, že situace 2 (razítko
        // s rozhodným okamžikem za revokací) překlápí „neznámý" na „neplatný". Neobstálo to:
        // razítko je horní mez okamžiku podpisu, ne dolní — dokládá, že podpis v ten čas už
        // existoval, ale ne že neexistoval ještě před revokací. Standardy proto nedovolí ani
        // jeden ze závěrů (EN 319 102-1, past signature validation → INDETERMINATE/REVOKED_NO_POE),
        // a on-line DSS nad týmiž dokumenty vrací neurčitelný výsledek.
        //
        // AnEP si tím odporoval sám: blok Průběh ověřování psal „standardy odmítají prohlásit
        // podpis za neplatný" nad stavovým řádkem, na kterém svítilo NEPLATNÝ — a u podpisu, kde
        // vedle toho nastala situace 21, se stejným důkazem vycházel „neznámý". Změřeno na
        // „PAdES B-LTA kvalifikovany po revokaci ICA.pdf" a jeho variantě T.
        //
        // Zjištění o revokaci nezmizelo, jen se přesunulo tam, kam patří: nese ho situace 2,
        // důvod 9 a závěr v Průběhu ověřování.
        // S29: DSS said TOTAL_PASSED but the signing cert's only embedded revocation was
        // produced by a respondent whose cert had already expired at production time.
        // S43 no longer belongs here: since trust anchors carry a sunset date, DSS applies the
        // standard's reading of a withdrawn time-stamping service itself, and overriding a pass
        // it reached that way would be stricter than the standard the situation cites.
        if ("TOTAL_PASSED".equals(indication)
                && sig.getDetectedSituations().contains(29)) {
            indication = "INDETERMINATE";
        }
        // Zámek podpisových polí, případně celého dokumentu. Šedou poznámkou pod druhem prvku,
        // stejně jako v Rozšířeném přehledu; text staví ExtendedView, aby obě záložky mluvily
        // o téže věci stejně.
        String lockNote = ExtendedView.pdfLockNote(sig.getPdfLock(), isSealForGender);
        if (lockNote != null) card.getChildren().add(greyNote(lockNote));

        String stavText;
        String stavColor;
        if ("TOTAL_PASSED".equals(indication)) {
            stavText  = isTs ? "platné" : (isSealForGender ? "platná" : "platný");
            stavColor = "#1a7a1a";
        } else if ("TOTAL_FAILED".equals(indication)) {
            stavText  = isTs ? "neplatné" : (isSealForGender ? "neplatná" : "neplatný");
            stavColor = "#cc0000";
        } else {
            stavText  = "neznámý";
            stavColor = "#9a6700";
        }
        Label stavLabel = new Label("Stav platnosti:");
        stavLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: white;");
        stavLabel.setMinWidth(130);
        Text stavPrimary = new Text(stavText);
        stavPrimary.setStyle("-fx-font-weight: bold; -fx-fill: white;");
        TextFlow stavFlow = new TextFlow(stavPrimary);
        if ("TOTAL_FAILED".equals(indication)) {
            // Důvod 9 (revokace) tu už nefiguruje: od zrušení přepisu verdiktu se s ním karta
            // „neplatný" nepotká, protože revokace bez důkazu o čase vzniku podpisu končí
            // neznámou platností.
            String failReason = null;
            if (sig.getDetectedReasons().contains(7)) {
                failReason = "porušení integrity";
            } else if (sig.getDetectedReasons().contains(28)) {
                // Ne „porušení integrity": obsah je nezměněný a tvrdit u něj opak je nepravda,
                // kterou si čtenář ověří u kteréhokoli jiného validátoru. Viz situace 53.
                failReason = "podpisovou hodnotu nelze ověřit";
            }
            if (failReason != null) {
                Text reasonText = new Text(" (" + failReason + ")");
                reasonText.setStyle("-fx-fill: white; -fx-font-weight: normal;");
                stavFlow.getChildren().add(reasonText);
            }
        }
        boolean hasS20 = sig.getDetectedSituations().contains(20);
        boolean hasS21 = sig.getDetectedSituations().contains(21);
        // S48 a S2 stojí na konci pořadí schválně: obě situace nad nimi mluví o revokačním údaji,
        // který se ověření nezúčastnil, a to je zpráva, kterou čtenář jinde nedostane. S48 a S2
        // popisují údaj, o který se ověření naopak opřelo — a ten už je vidět i v Analýze.
        // Vzájemně se vylučují (S2 a S48 už při detekci, S21 s oběma), takže řetězec rozhoduje
        // jen o tom, který text se vezme, ne o tom, co se zamlčí.
        String warnText = hasS20 ? SITUATION_LABELS.get(20)
                        : hasS21 ? SITUATION_LABELS.get(21)
                        : sig.getDetectedSituations().contains(48) ? revokedNoProofWarning(sig)
                        : sig.getDetectedSituations().contains(2) ? revokedBeforeDecisiveWarning(sig)
                        : null;
        if (warnText != null && noProofAndCertExpired(sig, verificationTime)) warnText = null;
        HBox stavRow;
        if (warnText != null) {
            Button warnBtn = makeWarningButton(warnText);
            stavRow = new HBox(8, stavLabel, stavFlow, warnBtn);
        } else {
            HBox.setHgrow(stavFlow, Priority.ALWAYS);
            stavRow = new HBox(8, stavLabel, stavFlow);
        }
        stavRow.setStyle("-fx-background-color: " + stavColor + "; -fx-padding: 6 10 6 10;");
        stavRow.setMaxWidth(Double.MAX_VALUE);
        stavRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        card.getChildren().add(stavRow);

        // Situace 24 (certifikát byl po vzniku podpisu revokován, verdikt je přesto platný) tu
        // mívala tlačítko „Informace". Do zjednodušeného přehledu nepatří: ten odpovídá na otázku
        // „platí to?" a odpověď zní ano — pozdější revokace na ní nic nemění, protože podpis
        // vznikl, dokud certifikát platil, a doložil to důkaz existence. Tlačítko vedle zeleného
        // pruhu ale vypadá jako výhrada k němu a čtenáře posílá hledat problém, který tam není.
        //
        // Výklad se neztrácí, jen se nabízí tam, kde je na něj místo: v Analýze u téhož podpisu
        // stojí totéž tlačítko s týmž textem (getLaterRevokedDetail), a to je stránka, kterou
        // čtenář otevírá právě kvůli podrobnostem.

        if (sig.getSignerDisplayName() != null && !sig.getSignerDisplayName().isBlank()) {
            // A time-stamp has no signatory. The name here is the time-stamping unit that issued
            // it, and calling that "podepsaná osoba" reads as if somebody had signed the document.
            String signerLabel = isTs ? "Časové razítko vydal/a:"
                    : sig.personLabel() + ":";
            card.getChildren().add(row(signerLabel, sig.getSignerDisplayName()));
        }
        // Kdo za totžnožt podepisujícího ručí. Jméno v řádku nad tím není samo o sobě tvrzením
        // aplikace — tvrdí ho vydavatel certifikátu, a ten má být vidět hned vedle, ne až
        // v Rozšířeném přehledu. Stačí CN: celé DN je pro tenhle přehled příliš dlouhé.
        String issuerCN = DnUtil.dnField(sig.getCertIssuerDN(), "2.5.4.3", "CN");
        if (issuerCN != null && !issuerCN.isBlank()) {
            card.getChildren().add(row("Certifikát vydal:", issuerCN));
        }
        if (!isTs) {
            String certT = DnUtil.dnField(sig.getSignerCertDN(), "2.5.4.12", "T");
            if (certT != null && !certT.isBlank()) card.getChildren().add(subRow("Titul:", certT));
        }

        if (isTs && sig.getSigningTime() != null) {
            card.getChildren().add(row("Čas připojení časového razítka:", TimeUtil.format(sig.getSigningTime())));
        }

        if (!isTs) {
            // Declared PDF properties (Důvod/Místo podpisu, Kontakt) belong to Rozšířený přehled;
            // Zjednodušený stays with what the validation itself established.
            String timeLabel = sig.isMark() ? "Čas značky:"
                    : sig.isSeal() ? "Čas pečeti:" : "Čas podpisu:";
            Instant bst = sig.getBestSignatureTime();
            if (bst != null) {
                String bstNote;
                if (sig.isBestSignatureTimeFromEvidenceRecord()) {
                    bstNote = "čas archivního razítka evidenčního záznamu";
                } else if (sig.hasValidTimestamp()) {
                    bstNote = "čas časového razítka";
                } else if (sig.isUsedCustomValidationTime()) {
                    bstNote = "explicitně zadaný čas pro ověření";
                } else {
                    bstNote = "deklarovaný čas";
                }
                card.getChildren().add(row(timeLabel, TimeUtil.format(bst) + "  (" + bstNote + ")"));
            } else if (sig.getSigningTime() != null) {
                card.getChildren().add(row(timeLabel, TimeUtil.format(sig.getSigningTime()) + "  (deklarovaný čas)"));
            }
        }

        // One line, no detail: an evidence record changes what the reader can conclude about
        // the signature's age, so it belongs in the simple overview — but everything about
        // what it covers and how well belongs in Rozšířený přehled.
        if (!sig.getEvidenceRecords().isEmpty()) {
            card.getChildren().add(row("Evidenční záznam:", evidenceRecordSummary(sig)));
        }

        if (!isTs && AdvisorView.hasAppearanceToReport(sig)) {
            card.getChildren().add(buildAppearanceSection(sig.getSignatureAppearancePng(), sig));
        }

        return card;
    }

    /** One sentence naming what the records covering this signature do or fail to do. */
    private static String evidenceRecordSummary(SignatureSummary sig) {
        List<EvidenceRecordEntry> records = sig.getEvidenceRecords();
        EvidenceRecordEntry proving = records.stream()
                .filter(EvidenceRecordEntry::providesProof)
                .min(java.util.Comparator.comparing(EvidenceRecordEntry::earliestProof))
                .orElse(null);
        String count = records.size() == 1 ? "" : records.size() + "× — ";
        if (proving != null) {
            // Only ever one document is verified at a time, so the record's proof is stated for
            // that one file — the others it covers were not checked and nothing is claimed of them.
            String scope = proving.partiallyChecked()
                    ? ", dokládá existenci předloženého souboru k "
                    : ", dokládá existenci k ";
            return count + proving.type() + scope + TimeUtil.format(proving.earliestProof());
        }
        return count + records.get(0).type() + " — nedokládá existenci "
                + "(podrobnosti v Rozšířeném přehledu)";
    }

    static HBox buildReasonsBlock(List<Integer> reasons) {
        Label header = new Label("Zjištěné důvody:");
        header.setStyle("-fx-font-weight: bold; -fx-text-fill: #003189;");
        header.setMinWidth(130);

        VBox itemBox = new VBox(3);
        for (int n : reasons) {
            String desc = REASON_WORKING_LABELS.getOrDefault(n, "Důvod " + n);
            Label item = new Label(n + ". " + desc);
            item.setStyle("-fx-text-fill: #003189; -fx-font-style: italic;");
            item.setWrapText(true);
            itemBox.getChildren().add(item);
        }
        HBox.setHgrow(itemBox, Priority.ALWAYS);
        return new HBox(8, header, itemBox);
    }

    /**
     * Vysvětlivka situace pro daný prvek. U situace 22 náhradní znění, když atribut
     * signing-time v podpisu je a DSS ho jen nepřečetlo — obecné znění by tvrdilo, že chybí.
     */
    static String situationLabel(int n, cz.digitalnikontinuita.anep.model.SignatureSummary sig) {
        if (n == 22 && sig != null && sig.isSigningTimeUnreadable()) {
            String unreadable = SituationTexts.get("22/necitelny", "text");
            if (unreadable != null) return unreadable;
        }
        return SITUATION_LABELS.getOrDefault(n, "Situace " + n);
    }

    static HBox buildSituationsBlock(List<Integer> situations,
                                     cz.digitalnikontinuita.anep.model.SignatureSummary sig) {
        // „Situace", ne „příčiny". Blok se od 2. 9. 2026 staví i u podpisu, jehož platnost
        // byla ověřena, a tam žádné příčiny nejsou — je to soupis nálezu. Právě proto se
        // v Analýze u kladného výsledku vynechával; v Detailním rozboru vynechávat nemá co.
        Label header = new Label("Zjištěné situace:");
        header.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111;");
        header.setMinWidth(130);

        VBox itemBox = new VBox(3);
        for (int n : situations) {
            Label item = new Label(n + ". " + situationLabel(n, sig));
            item.setStyle("-fx-text-fill: #111111;");
            item.setWrapText(true);
            itemBox.getChildren().add(item);
        }
        HBox.setHgrow(itemBox, Priority.ALWAYS);
        return new HBox(8, header, itemBox);
    }

    /**
     * Reduces a full legalType string to one of three bare categories
     * (kvalifikovaný / uznávaný / zaručený, and their seal equivalents).
     * Used only in the simplified view; DetailedReportView keeps the full text.
     */
    private static String simplifyLegalType(String raw) {
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

    static Button makeWarningButton(String text) {
        Button btn = new Button("Upozornění");
        btn.setFocusTraversable(false);
        applyPlasticStyle(btn,
                "#e08820", "#cc6600",
                "#f09a30", "#dd7700",
                "#aa5500", "#884400",
                "#884400");
        btn.setOnAction(e -> {
            javafx.scene.control.Alert alert =
                    new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
            alert.setTitle("Upozornění k výsledku ověření");
            alert.setHeaderText(null);
            Label content = new Label(text);
            content.setWrapText(true);
            content.setMaxWidth(430);
            alert.getDialogPane().setContent(content);
            alert.getDialogPane().setPrefWidth(480);
            alert.showAndWait();
        });
        return btn;
    }

    /**
     * Okno „kde vzít CRL“ — řádky {@code LINK:popis|adresa} v něm stojí jako proklikávací odkazy.
     * Otevírá je odkaz <b>Podrobněji …</b> v řádku s radou pro zkušenější uživatele. Do 25. 8. 2026
     * je otevíralo tlačítko u důvodu 12; to zmizelo s jeho řádkem „Co může pomoci“, jehož text se
     * přesunul právě do té rady.
     */
    static void showLinkableDetailDialog(String text) {
        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(4);
        content.setMaxWidth(440);
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("LINK:")) {
                String payload = line.substring(5);
                int sep = payload.indexOf('|');
                String label = sep >= 0 ? payload.substring(0, sep) : payload;
                String url   = sep >= 0 ? payload.substring(sep + 1) : payload;
                javafx.scene.control.Hyperlink link = new javafx.scene.control.Hyperlink(label);
                link.setWrapText(true);
                link.setStyle("-fx-font-size: 11px;");
                link.setOnAction(ev -> {
                    try { java.awt.Desktop.getDesktop().browse(new java.net.URI(url)); }
                    catch (Exception ex) { /* silently ignore */ }
                });
                content.getChildren().add(link);
            } else {
                Label lbl = new Label(line);
                lbl.setWrapText(true);
                lbl.setMaxWidth(440);
                lbl.setStyle("-fx-font-size: 11px;");
                content.getChildren().add(lbl);
            }
        }
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(460);
        scroll.setMaxHeight(380);
        javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Podrobnosti");
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(500);
        dialog.showAndWait();
    }

    static Button makeInfoButton(String text) {
        Button btn = new Button("Informace");
        btn.setFocusTraversable(false);
        applyPlasticStyle(btn,
                "#2196f3", "#1565c0",
                "#42a5f5", "#1976d2",
                "#0d47a1", "#0a3880",
                "#0d47a1");
        btn.setOnAction(e -> {
            javafx.scene.control.Alert alert =
                    new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
            alert.setTitle("Informace k výsledku ověření");
            alert.setHeaderText(null);
            Label content = new Label(text);
            content.setWrapText(true);
            content.setMaxWidth(430);
            alert.getDialogPane().setContent(content);
            alert.getDialogPane().setPrefWidth(480);
            alert.showAndWait();
        });
        return btn;
    }

    /** Indented, smaller row for subordinate certificate attributes (Titul, Organizace, OU). */
    private HBox subRow(String labelText, String value) {
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #555555;");
        lbl.setMinWidth(114);
        TextArea val = selectable(value, "-fx-font-size: 11px; -fx-text-fill: #555555;", false);
        HBox.setHgrow(val, Priority.ALWAYS);
        HBox box = new HBox(8, lbl, val);
        box.setAlignment(Pos.BASELINE_LEFT);
        box.setPadding(new javafx.geometry.Insets(0, 0, 0, 16));
        return box;
    }

    /** Row with a bold fixed-width label and a selectable value field. */
    private HBox row(String labelText, String value) {
        return row(labelText, value, null);
    }

    /** Drobná šedá poznámka pod hodnotou řádku, zarovnaná pod ni — jako v Rozšířeném přehledu. */
    private HBox greyNote(String text) {
        Label note = new Label(text);
        note.setStyle("-fx-font-size: 11px; -fx-text-fill: #555555;");
        note.setWrapText(true);
        HBox box = new HBox(note);
        box.setPadding(new javafx.geometry.Insets(0, 0, 2, 130 + 8));
        return box;
    }

    /** Row with an optional info icon (ⓘ) placed immediately after the value text. */
    private HBox row(String labelText, String value, String tooltipText) {
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111;");
        lbl.setMinWidth(130);

        TextArea val = selectable(value, "-fx-text-fill: #111111;", false);

        if (tooltipText == null) {
            HBox.setHgrow(val, Priority.ALWAYS);
            HBox box = new HBox(8, lbl, val);
            box.setAlignment(Pos.BASELINE_LEFT);
            return box;
        }

        // Use a Label (not TextField) so the icon sits pixel-tight after the text.
        // Copy is provided via a right-click context menu.
        Label valLabel = new Label(value);
        valLabel.setStyle("-fx-text-fill: #111111;");
        MenuItem copyItem = new MenuItem("Kopírovat");
        copyItem.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(value);
            Clipboard.getSystemClipboard().setContent(cc);
        });
        valLabel.setContextMenu(new ContextMenu(copyItem));

        Label icon = new Label("ⓘ");
        icon.setStyle("-fx-font-size: 13px; -fx-text-fill: #003189; -fx-cursor: hand; -fx-padding: 0 0 0 4;");
        Tooltip tip = new Tooltip(tooltipText);
        tip.setWrapText(true);
        tip.setMaxWidth(380);
        tip.setStyle("-fx-font-size: 11px; -fx-background-color: #ffffff; -fx-text-fill: #111111; -fx-border-color: #cccccc; -fx-border-width: 1;");
        Tooltip.install(icon, tip);
        icon.setOnMouseClicked(e -> tip.show(icon, e.getScreenX() + 12, e.getScreenY() + 12));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(8, lbl, new HBox(0, valLabel, icon), spacer);
        row.setAlignment(Pos.BASELINE_LEFT);
        return row;
    }

    /** Transparent, non-editable TextArea — wraps text and allows cursor selection + Ctrl+C. */
    static TextArea selectable(String text, String extraStyle) {
        return selectable(text, extraStyle, true);
    }

    /**
     * @param topAligned zda se sousední popisek zarovnává nahoru ({@code Pos.TOP_LEFT}, ať už
     *     explicitně nebo jako výchozí hodnota HBoxu) — tam text uvnitř potřebuje kompenzaci
     *     niže popsanou. Řádek zarovnaný podle spodní linky ({@code Pos.BASELINE_LEFT}) sedí
     *     správně sám: HBox obě spodní linky srovná podle skutečné baseline každého prvku,
     *     a stejný posun by ji rozhodil. Zjištěno 3. 9. 2026 nejdřív na řádku zarovnaném na
     *     střed ({@code Pos.CENTER_LEFT}) — bezpodmínečný posun ho rozbil v Zjednodušeném
     *     a Rozšířeném přehledu — a po přechodu na {@code BASELINE_LEFT} (ExtendedView.row(),
     *     ResultView.row()/subRow()) platí totéž ze stejného důvodu.
     */
    static TextArea selectable(String text, String extraStyle, boolean topAligned) {
        TextArea ta = new TextArea(text == null ? "" : text);
        ta.setEditable(false);
        ta.setFocusTraversable(false);
        ta.setWrapText(true);
        ta.setMaxWidth(Double.MAX_VALUE);
        ta.setMinHeight(Region.USE_PREF_SIZE);
        // Skin TextArea sazi text o 2 px niz, nez kde ma prvek horni okraj — a v radku zarovnanem
        // nahoru (Pos.TOP_LEFT) tak hodnota sedi niz nez tucny popisek vedle ni, ktery zadne takove
        // odsazeni nema. Neni to CSS: zmereno sondou SelectableOffsetProbe, ani padding, ani
        // background-insets na .scroll-pane a .viewport s tim nehnou, a cislo je 2 px pri 11, 12
        // i 13 px pisma a stejne pro jednu i vic radek. Proto se posouva cely prvek — translateY
        // do rozvrzeni nemluvi, takze vyska radku zustava, jaka byla, a rezerva pod textem se jen
        // zvetsi.
        if (topAligned) ta.setTranslateY(-2);
        double fontSize = 13.0;
        if (extraStyle != null) {
            if (extraStyle.contains("11px")) fontSize = 11.0;
            else if (extraStyle.contains("12px")) fontSize = 12.0;
        }
        final double fs = fontSize;
        ta.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; " +
                "-fx-border-color: transparent; -fx-padding: 0; " +
                "-fx-focus-color: transparent; -fx-faint-focus-color: transparent; " +
                "-fx-font-size: " + (int) fs + "px;" +
                (extraStyle != null ? " " + extraStyle : ""));
        final String inner = "-fx-background-color: transparent; -fx-padding: 0;" +
                (extraStyle != null ? " " + extraStyle : "");
        final String scrollStyle = "-fx-background-color: transparent; -fx-border-color: transparent; " +
                "-fx-padding: 0; -fx-vbar-policy: never; -fx-hbar-policy: never;";
        // skinProperty fires when the skin is first installed — at that moment the skin's
        // node tree exists but the ScrollPane skin may not yet be finalized. Apply styles
        // early to get the right content padding (fixes horizontal text alignment).
        ta.skinProperty().addListener((obs, os, ns) -> {
            if (ns != null) {
                ta.lookupAll(".content").forEach(n -> n.setStyle(inner));
                ta.lookupAll(".scroll-pane").forEach(n -> n.setStyle(scrollStyle));
            }
        });
        // sceneProperty + Platform.runLater fires after the first full layout pass,
        // when all skin nodes are guaranteed to be in the scene graph. Re-apply the
        // same styles so the transparent background survives the initial CSS pass.
        ta.sceneProperty().addListener((obs, os, ns) -> {
            if (ns != null) javafx.application.Platform.runLater(() -> {
                ta.applyCss();
                ta.lookupAll(".content").forEach(n -> n.setStyle(inner));
                ta.lookupAll(".scroll-pane").forEach(n -> n.setStyle(scrollStyle));
                fitToContent(ta, fs);
            });
        });
        // Defer fitToContent via Platform.runLater so it runs AFTER the layout pass;
        // setting minHeight/prefHeight during a layout pass has no effect in JavaFX.
        //
        // Dvakrát, ve dvou kolech: v prvním kole nemusí být text po změně šířky ještě přelomený,
        // takže {@code laidOutHeight} ukazuje stav z předchozí šířky — a výška vyjde podle
        // starého počtu řádek. Druhé kolo už vidí hotové rozvržení a číslo srovná. Smyčka
        // z toho nevznikne: mění se výška, a ta šířku neovlivňuje.
        ta.widthProperty().addListener((obs, ow, nw) ->
                javafx.application.Platform.runLater(() -> {
                    fitToContent(ta, fs);
                    javafx.application.Platform.runLater(() -> fitToContent(ta, fs));
                }));
        return ta;
    }

    private static void fitToContent(TextArea ta, double fontSize) {
        // Use the actual content-node width when available (post-layout); otherwise fall back.
        javafx.scene.Node contentNode = ta.lookup(".content");
        double w;
        if (contentNode != null && contentNode.getBoundsInLocal().getWidth() > 0) {
            w = contentNode.getBoundsInLocal().getWidth();
        } else {
            w = ta.getWidth() - 4.0;
        }
        if (w <= 0) return;
        Text measurer = new Text(ta.getText() == null ? "" : ta.getText());
        measurer.setFont(Font.font(fontSize));
        measurer.setWrappingWidth(w);
        double h = Math.max(measurer.getLayoutBounds().getHeight() + 4.0, fontSize * 1.5 + 4.0);
        h = Math.max(h, laidOutHeight(ta));
        ta.setMinHeight(h);
        ta.setPrefHeight(h);
    }

    /**
     * Výška, kterou by TextArea potřebovala podle textu, který v ní <em>opravdu</em> leží.
     *
     * <p>Měřidlo nad ní počítá s výchozím písmem, kdežto aplikace sází Segoe UI z {@code app.css},
     * takže se obě čísla rozcházejí — a rozcházejí se i tehdy, když se text po změně šířky zalomí
     * do jiného počtu řádek. Uzly {@code .text} uvnitř skinu ukazují stav po rozvržení, tedy to,
     * co uživatel uvidí.
     *
     * <p>K výšce textu se přičítá, co kolem výřezu ukusuje místo (1 px TextArea + 1 px scroll-pane
     * na každé straně) a 2 px rezervy. Bez rezervy vyjde výřez přesně na výšku textu a stačí
     * zaokrouhlení, aby se spodky písmen jako „j" nebo „g" ocitly za jeho okrajem — přesně to
     * hlásil uživatel 3. 9. 2026 z bloku „Zjištěné důvody".
     *
     * @return 0, dokud skin neexistuje; volající si pak vystačí s odhadem
     */
    private static double laidOutHeight(TextArea ta) {
        double textHeight = 0;
        for (javafx.scene.Node n : ta.lookupAll(".text")) {
            textHeight += n.getLayoutBounds().getHeight();
        }
        return textHeight > 0 ? textHeight + 6.0 : 0;
    }

    /** The three kinds of input the tab header has to word differently. */
    private enum AuthSubject { DOCUMENT, CONTAINER, EXTERNAL }

    /**
     * Headline sentence counting the authentication elements, worded according to what the
     * user actually submitted: a PDF ("Na dokumentu…"), a container carrying the signed data
     * inside — ASiC, P7M, enveloped XML/JSON ("Kontejner má…"), or a signature file supplied
     * separately from the signed content ("V externím souboru…").
     */
    static String authCountLabel(cz.digitalnikontinuita.anep.model.ValidationResult result) {
        return authCountLabel(result, true);
    }

    /**
     * @param withProfile zda má věta začít pojmenováním podoby souboru („Na dokumentu PDF
     *                    (podtypu PDF/A-2b, verze 1.7)…“). Analýza od 18. 9. 2026 předává
     *                    {@code false} tam, kde podobu souboru vysází vlastním nadpisem nad
     *                    dokumentovými bloky — jinak by ji čtenář měl na obrazovce dvakrát
     *                    (rozhodnutí uživatele).
     */
    static String authCountLabel(cz.digitalnikontinuita.anep.model.ValidationResult result,
                                 boolean withProfile) {
        String head = authElementCountSentence(result, withProfile);
        String fields = emptyFieldSentence(result);
        String own = contentPdfSignatureSentence(result);
        String out = fields == null ? head : head + ". " + fields;
        return own == null ? out : out + ". " + own;
    }

    /**
     * Věta o podpisech, které nese samo PDF předložené k externímu podpisu. DSS (a s ním AnEP)
     * v tom režimu ověřuje jen externí soubor, takže o nich jinak karta mlčí; do 3. 10. 2026 je
     * naopak vydávala za další prvky externího souboru. {@code null}, když PDF žádné nenese.
     */
    static String contentPdfSignatureSentence(cz.digitalnikontinuita.anep.model.ValidationResult result) {
        int s = result.getContentPdfSignatures();
        int t = result.getContentPdfTimestamps();
        if (s == 0 && t == 0) return null;
        String sigs = s == 0 ? null
                : s == 1 ? "vlastní podpis či pečeť"
                : CzechPlural.count(s, "vlastní podpis či pečeť", "vlastní podpisy či pečeti",
                        "vlastních podpisů či pečetí");
        String stamps = t == 0 ? null
                : t == 1 ? "dokumentové časové razítko"
                : CzechPlural.count(t, "dokumentové časové razítko", "dokumentová časová razítka",
                        "dokumentových časových razítek");
        String what = sigs != null && stamps != null ? sigs + " a " + stamps
                : sigs != null ? sigs : stamps;
        boolean one = s + t == 1;
        return "Předložený dokument PDF nese i " + what + "; spolu s externím podpisem se"
                + (one ? " neověřuje, ověříte jej" : " neověřují, ověříte je")
                + " předložením samotného PDF";
    }

    /**
     * Věta o prázdných podpisových polích, která se věší za větu o autentizačních prvcích —
     * ve všech třech záložkách naráz, protože ty ji všechny sázejí odsud. Ve Zjednodušeném
     * přehledu je to zároveň jediná zmínka o nálezu: rozbalovací blok tam není, a mlčet o poli,
     * které někdo v dokumentu čeká, by znamenalo, že se o něm čtenář nedozví vůbec.
     *
     * <p>{@code null}, když v dokumentu žádné prázdné pole není.
     */
    static String emptyFieldSentence(cz.digitalnikontinuita.anep.model.ValidationResult result) {
        int f = result.getEmptySignatureFields().size();
        if (f == 0) return null;
        // Shoda podle počtu: 1 → „je jedno", 2–4 → „jsou tři", 5+ → „je pět".
        String verb = (f >= 2 && f <= 4) ? "jsou" : "je";
        String what = f == 1
                ? "jedno prázdné podpisové pole"
                : CzechPlural.count(f, "prázdné podpisové pole",
                        "prázdná podpisová pole", "prázdných podpisových polí");
        // U dokumentu stačí zájmeno — věta o autentizačních prvcích ho pojmenovala o slovo dřív
        // a „Na dokumentu… V dokumentu…" za sebou zní jako přeřeknutí. U kontejneru a externího
        // podpisu se ale zájmeno vztahuje na jiný soubor, než ve kterém pole je (prohledává se
        // podepisovaný PDF, ne obálka), takže tam se dokument musí pojmenovat.
        boolean aboutTheSameFile = authSubject(result) == AuthSubject.DOCUMENT;
        String where = aboutTheSameFile ? "na něm " : "v dokumentu ";
        // „Kromě toho" jen tam, kde je k čemu přidávat; na dokumentu bez jediného podpisu by
        // ukazovalo na nic a protikladné „ale" řekne přesně to, co čtenář potřebuje slyšet:
        // nepodepsáno, a přesto se tu na podpis čeká.
        String opener = "je".equals(verb) ? "Je" : "Jsou";
        return result.getSignatures().isEmpty()
                ? opener + " " + where + "ale " + what
                : "Kromě toho " + verb + " " + where + what;
    }

    private static String authElementCountSentence(
            cz.digitalnikontinuita.anep.model.ValidationResult result, boolean withProfile) {
        int n = result.getSignatures().size();
        int er = result.getStandaloneEvidenceRecords().size();
        // A file holding nothing but preserved data and its evidence record has no
        // authentication element at all, yet saying only "no authentication element" would be
        // actively misleading: the file does carry a proof that the data existed. Name what is
        // there instead of what is missing.
        if (n == 0 && er > 0) {
            String noun = er == 1 ? "evidenční záznam"
                    : er <= 4 ? "evidenční záznamy"
                    : "evidenčních záznamů";
            String verb = er == 1 ? "je " : er <= 4 ? "jsou " : "je ";
            return switch (authSubject(result)) {
                case CONTAINER -> containerNoun(result)
                        + " nemá žádný podpis ani pečeť, obsahuje " + er + " " + noun;
                case EXTERNAL -> "V externím souboru " + verb + er + " " + noun
                        + ", žádný podpis ani pečeť";
                case DOCUMENT -> "Nejde o podepsaný dokument — " + verb + "to " + er + " " + noun;
            };
        }
        // Czech agreement: 1 → singular, 2–4 → nominative plural, 5+ → genitive plural.
        String noun = n == 1 ? "autentizační prvek"
                : n <= 4 ? "autentizační prvky"
                : "autentizačních prvků";
        // Say that a record backs the elements up, without implying it holds them: the reader
        // handed in two files and needs to know which one the signatures are actually in.
        boolean backedByRecord = result.getSignatures().stream()
                .anyMatch(s -> !s.getEvidenceRecords().isEmpty());
        String support = backedByRecord
                ? (n == 1 ? ", podpořený obsahem evidenčního záznamu"
                          : ", podpořené obsahem evidenčního záznamu")
                : "";
        return switch (authSubject(result)) {
            case CONTAINER -> {
                String container = containerNoun(result);
                yield n == 0
                        ? container + " nemá žádný autentizační prvek"
                        : container + " má " + n + " " + noun + support;
            }
            case EXTERNAL -> n == 0
                    ? "V externím souboru není žádný autentizační prvek"
                    : "V externím souboru " + (n <= 4 && n > 1 ? "jsou " : "je ") + n + " " + noun + support;
            case DOCUMENT -> {
                String on = withProfile ? documentSubject(result) : "Na dokumentu";
                yield n == 0
                        ? on + " není žádný autentizační prvek"
                        : on + " " + (n <= 4 && n > 1 ? "jsou " : "je ") + n + " " + noun + support;
            }
        };
    }

    /**
     * Čím větu o autentizačních prvcích začít: buď obecným „Na dokumentu", nebo — u PDF —
     * pojmenováním jeho podoby, tedy podmnožin, ke kterým se hlásí, a verze souboru.
     *
     * <p>Je to údaj o dokumentu, ne o podpisu, a čtenář se podle něj rozhoduje ještě dřív než
     * podle verdiktu: PDF/A-2b je dokument, který se archivovat má a smí, PDF/A-3 nebo PDF/A-4f
     * může vedle sebe nést přílohy s vlastním obsahem, a obyčejné PDF 1.4 nikdo ke dlouhodobému
     * uchování nedoporučuje. Proto to stojí v prvním řádku a ne až v podrobnostech.
     *
     * <p>Sází se tak, jak je to v souboru zapsáno, včetně toho, že se dokument může hlásit
     * k několika podmnožinám naráz (typicky PDF/A + PDF/UA). Nic se tu neověřuje — viz
     * {@link cz.digitalnikontinuita.anep.core.PdfProfileExtractor} a popisek
     * {@link #pdfProfileNote(cz.digitalnikontinuita.anep.model.ValidationResult)}.
     */
    private static String documentSubject(cz.digitalnikontinuita.anep.model.ValidationResult result) {
        var profile = result.getPdfProfile();
        if (profile == null || profile.isEmpty()) return "Na dokumentu";

        java.util.List<String> parts = new java.util.ArrayList<>();
        if (!profile.standards().isEmpty()) {
            parts.add((profile.standards().size() == 1 ? "podtypu " : "podtypů ")
                    + String.join(" a ", profile.standards()));
        }
        if (profile.version() != null) parts.add("verze " + profile.version());
        return "Na dokumentu PDF (" + String.join(", ", parts) + ")";
    }

    /**
     * Věta o podobě souboru pro <b>samostatný nadpis</b> nad dokumentovými bloky:
     * „Dokument je ve formátu PDF/A-2b, verze 1.7“.
     *
     * <p>Vznikla 18. 9. 2026 na pokyn uživatele. Dokud stála v jedné větě s počtem prvků
     * („Na dokumentu PDF (podtypu…, verze…) je 1 autentizační prvek“), nebylo z ničeho poznat,
     * které z bloků pod ní mluví o dokumentu a které o jednotlivých prvcích. Rozdělením se z jedné
     * věty staly dva nadpisy a každý si nese svoje.
     *
     * <p><b>Znění je od uživatele</b> (18. 9. 2026) a nahradilo moje „Dokument PDF má podtyp X
     * a verzi Y“.
     *
     * <p><b>Čárka před verzí není kosmetika.</b> Bez ní („PDF/A-2b verze 1.7“) se věta čte, jako
     * by 1.7 byla verzí té podmnožiny — jenže <b>verze je verzí souboru PDF</b> a PDF/A-2b žádné
     * verze nemá. S čárkou stojí oba údaje vedle sebe jako dva samostatné, což je přesně to, co
     * o dokumentu platí. Doplnil ji uživatel poté, co jsem tu nepřesnost obhajoval jako únosnou;
     * byla to lepší odpověď než moje.
     *
     * <p>Odkud se oba údaje berou, doříká popisek pod myší ({@link #pdfProfileNote}).
     *
     * <p><b>Formátem se rozumí podmnožina, ke které se dokument hlásí</b>, a nehlásí-li se
     * k žádné, zbývá prosté „PDF“ — sázet „ve formátu“ a nic za tím by byla věta bez obsahu.
     *
     * <p>Údaje jsou tytéž jako v {@link #documentSubject}, jen v jiném pádě, takže se obě věty
     * nemohou rozejít v tom, co o dokumentu tvrdí. {@code null}, když se dokument k ničemu nehlásí
     * a ani verze se nezjistila; nadpis pak zní obecně, viz volající.
     */
    static String documentProfileSentence(
            cz.digitalnikontinuita.anep.model.ValidationResult result) {
        if (authSubject(result) != AuthSubject.DOCUMENT) return null;
        var profile = result.getPdfProfile();
        if (profile == null || profile.isEmpty()) return null;

        String format = profile.standards().isEmpty()
                ? "PDF" : String.join(" a ", profile.standards());
        return "Dokument je ve formátu " + format
                + (profile.version() != null ? ", verze " + profile.version() : "");
    }

    /**
     * Nadpis nad dokumentovými bloky. Nese podobu souboru, a nezná-li se, obecné „Dokument jako
     * celek“ — týmiž slovy, jakými se dokumentová část jmenuje v Digitální kontinuitě.
     */
    static Label documentHeader(cz.digitalnikontinuita.anep.model.ValidationResult result) {
        String sentence = documentProfileSentence(result);
        Label label = new Label(sentence != null ? sentence : "Dokument jako celek");
        label.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #003189;");
        label.setWrapText(true);
        String note = pdfProfileNote(result);
        if (note != null) {
            Tooltip tip = new Tooltip(note);
            tip.setWrapText(true);
            tip.setMaxWidth(420);
            label.setTooltip(tip);
        }
        return label;
    }

    /**
     * Popisek k záhlaví, který říká, odkud se podoba dokumentu bere a co z ní neplyne.
     * {@code null}, když se v záhlaví o žádné podobě nepíše.
     *
     * <p><b>O shodě mluví podle toho, co se opravdu stalo.</b> Původní znění tvrdilo „shodu
     * s ní AnEP neověřuje — to umí až validátor příslušné části ISO“, jenže od té doby ji
     * AnEP ověřuje ({@link cz.digitalnikontinuita.anep.core.PdfAConformanceChecker}) a nad
     * PDF/A-1b stál popisek rovnou nad blokem „Shoda s normou PDF/A“, který výsledek toho
     * ověření nese. Obě věty byly v jednom commitu (c4a1b91) a odporovaly si od začátku.
     * Rozhoduje tedy {@code getPdfConformance()}: je-li vyplněné, popisek na blok odkáže;
     * není-li (podtyp mimo PDF/A, nebo nejednoznačná deklarace PDF/A, kdy
     * {@code pdfaFlavour} chybí), řekne, že se AnEP k téhle deklaraci nevyslovuje — což je
     * pravda, kterou původní věta říkala o všech.
     */
    static String pdfProfileNote(cz.digitalnikontinuita.anep.model.ValidationResult result) {
        var profile = result.getPdfProfile();
        if (profile == null || profile.isEmpty() || authSubject(result) != AuthSubject.DOCUMENT) {
            return null;
        }
        StringBuilder note = new StringBuilder();
        if (profile.version() != null) {
            note.append("Verze PDF je z hlavičky souboru, případně z klíče /Version v katalogu"
                    + " dokumentu, který ji smí povýšit (ISO 32000-1, 7.5.5).");
        }
        if (!profile.standards().isEmpty()) {
            if (note.length() > 0) note.append("\n\n");
            note.append("Podtyp je údaj z metadat XMP, kterým se dokument sám hlásí"
                    + " ke standardizované podmnožině PDF. Prohlášení ale není shoda:");
            if (result.getPdfConformance() != null) {
                note.append(" jestli dokument svým podmínkám dostál, prověřuje AnEP referenčním"
                        + " validátorem a výsledek říká blok „Shoda s normou PDF/A“ v Analýze.");
            } else {
                note.append(" tu AnEP prověřuje jen u jednoznačně deklarovaných profilů PDF/A,"
                        + " takže o tomhle prohlášení se nevyslovuje.");
            }
            note.append(" Chybějící údaj naopak znamená, že se dokument k ničemu nehlásí,"
                    + " ne že by podmínky nesplňoval.");
            if (profile.unexpected()) {
                note.append("\n\nNěkterý z identifikačních údajů je mimo hodnoty, které dnešní"
                        + " vydání ISO definují — může jít o novější vydání standardu, než které"
                        + " tahle verze AnEP zná, nebo o chybný zápis.");
            }
        }
        return note.toString();
    }

    /**
     * Záhlaví s počtem autentizačních prvků. Staví ho všechny tři záložky, které je zobrazují,
     * ze stejného místa — aby se nelišilo ani vzhledem, ani popiskem.
     */
    static Label authCountHeader(cz.digitalnikontinuita.anep.model.ValidationResult result) {
        return authCountHeader(result, true);
    }

    /** @param withProfile viz {@link #authCountLabel(ValidationResult, boolean)}. */
    static Label authCountHeader(cz.digitalnikontinuita.anep.model.ValidationResult result,
                                 boolean withProfile) {
        Label label = new Label(authCountLabel(result, withProfile));
        label.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #003189;");
        label.setWrapText(true);
        // Popisek o původu údajů patří k té větě, která je vysází. Nese-li podobu souboru nadpis
        // nad dokumentovými bloky, je popisek tam a tady by byl podruhé.
        String note = withProfile ? pdfProfileNote(result) : null;
        if (note != null) {
            Tooltip tip = new Tooltip(note);
            tip.setWrapText(true);
            tip.setMaxWidth(420);
            label.setTooltip(tip);
        }
        return label;
    }

    private static AuthSubject authSubject(cz.digitalnikontinuita.anep.model.ValidationResult result) {
        // Signature file submitted alongside the signed content it does not contain.
        if (result.isExternalSignature()) return AuthSubject.EXTERNAL;

        // ASiC — DSS reports container info only for these.
        eu.europa.esig.dss.diagnostic.DiagnosticData dd = result.getDiagnosticData();
        if (dd != null && dd.getContainerInfo() != null) return AuthSubject.CONTAINER;

        if (!result.getSignatures().isEmpty()) {
            // Non-PDF formats without separate detached content usually carry the signed data
            // themselves: P7M (CAdES), enveloping/enveloped XAdES, enveloping JAdES.
            //
            // Co je podpis v PDF, se poznává týmž predikátem jako jinde
            // ({@code AdvisorView.isPdfSignature}), ne výčtem předpon na místě. Ten výčet uváděl
            // jen PAdES a PDF-NOT-ETSI, takže podpis se starším SubFiltrem {@code adbe.pkcs7.*},
            // který DSS hlásí jako PKCS7-B/T/LT/LTA, propadl do větve pro kontejnery: nad
            // „pecet_certifikacni_uznavana_basic_razitko.pdf" (PKCS7-T) stálo v záhlaví
            // „Kontejner má 1 autentizační prvek", ačkoli šlo o obyčejné PDF. V korpusu D:\tmp
            // má PKCS7 formát 14 podpisů ve dvanácti souborech.
            String fmt = result.getSignatures().get(0).getFormat();
            // Osiřelý podpis (DSS ho nezpracovalo) má za formát svůj /SubFilter — „adbe.…“ či
            // „ETSI.…“ — a leží v PDF, protože jiné osiřelé podpisy nejsou. Bez téhle výjimky stálo
            // nad PDF s podpisem adbe.x509.rsa_sha1 „Kontejner má 1 autentizační prvek“ (nález
            // uživatele). isPdfSignature() se tu nerozšiřuje schválně: ptají se jí i nabídky
            // uchování, a ty podpis, který DSS nezpracovalo, obsloužit nemohou.
            boolean orphanInPdf = fmt != null
                    && (fmt.startsWith("adbe.") || fmt.startsWith("ETSI."));
            if (fmt != null && !AdvisorView.isPdfSignature(fmt) && !orphanInPdf
                    && !"TimeStampToken".equals(fmt)) {
                // ...but not always: a bare .p7s opened without the file it signs is a detached
                // signature with nothing inside it. DSS then finds no signed data to describe,
                // so the signature has no scopes — calling that a container would be wrong.
                return hasSignedDataInside(dd) ? AuthSubject.CONTAINER : AuthSubject.EXTERNAL;
            }
            return AuthSubject.DOCUMENT;
        }

        // No signature found — the file name is the only remaining signal.
        return isContainerFileName(result.getFileName())
                ? AuthSubject.CONTAINER : AuthSubject.DOCUMENT;
    }

    /**
     * How to name the thing the signatures were found in. A ZFO is a container in the technical
     * sense — a sealed CMS object with the message inside it — but calling it "Kontejner (P7M)"
     * describes the wrapping rather than the content, and the content is what the reader opened
     * the file for. Everything else keeps the container wording with its format in brackets.
     */
    private static String containerNoun(cz.digitalnikontinuita.anep.model.ValidationResult result) {
        if (result.getIsdsArtefact() != null) return result.getIsdsArtefact().label();
        String kind = containerKind(result);
        return "Kontejner" + (kind != null ? " (" + kind + ")" : "");
    }

    /**
     * Names the container flavour for the tab header, or {@code null} when it cannot be told
     * apart.  ASiC comes straight from DSS; the remaining container formats are identified by
     * the signature format, since those carry the signed data inside the signature itself.
     */
    private static String containerKind(cz.digitalnikontinuita.anep.model.ValidationResult result) {
        eu.europa.esig.dss.diagnostic.DiagnosticData dd = result.getDiagnosticData();
        if (dd != null && dd.getContainerInfo() != null && dd.getContainerType() != null) {
            return switch (dd.getContainerType()) {
                case ASiC_S -> "ASiC-S";
                case ASiC_E -> "ASiC-E";
            };
        }
        if (!result.getSignatures().isEmpty()) {
            String fmt = result.getSignatures().get(0).getFormat();
            if (fmt != null) {
                if (fmt.startsWith("CAdES")) return "P7M";
                if (fmt.startsWith("XAdES")) return "XML";
                if (fmt.startsWith("JAdES")) return "JSON";
            }
            return null;
        }
        return kindByExtension(result.getFileName());
    }

    /**
     * True when the first signature actually covers data present in the opened file — DSS
     * records a signature scope only for content it could get hold of. Empty scopes mean the
     * signed data lives elsewhere (detached signature submitted on its own).
     */
    private static boolean hasSignedDataInside(eu.europa.esig.dss.diagnostic.DiagnosticData dd) {
        if (dd == null || dd.getSignatures().isEmpty()) return true;
        return !dd.getSignatures().get(0).getSignatureScopes().isEmpty();
    }

    /** Container flavour guessed from the file name; used when no signature was parsed. */
    private static String kindByExtension(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return null;
        return switch (fileName.substring(dot + 1).toLowerCase()) {
            case "asice", "sce" -> "ASiC-E";
            case "asics", "scs" -> "ASiC-S";
            case "p7m" -> "P7M";
            case "zfo" -> "ZFO";
            case "xml" -> "XML";
            case "json" -> "JSON";
            default -> null;
        };
    }

    private static final java.util.Set<String> CONTAINER_EXTENSIONS = java.util.Set.of(
            "asice", "asics", "sce", "scs", "p7m", "zfo", "xml", "json");

    private static boolean isContainerFileName(String fileName) {
        if (fileName == null) return false;
        int dot = fileName.lastIndexOf('.');
        return dot >= 0
                && CONTAINER_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase());
    }

    /**
     * Vizualizace podpisu jako blok pro kartu — a schvalne package-private a staticka, aby ji
     * mohl postavit i {@code PrintExportTest}. Test, ktery si strom uzlu poskladal sam, tuhle
     * chybu neodhalil: obrazek je ve {@code StackPane} a prave do nej pruchod scenou nevstupoval.
     */
    static VBox buildAppearanceSection(byte[] pngBytes) {
        return buildAppearanceSection(pngBytes, (cz.digitalnikontinuita.anep.model.SignatureSummary) null);
    }

    /**
     * @param visibility zda tenhle obrázek čtenář v dokumentu opravdu uvidí; {@code null},
     *     když se to neví. Zjednodušený přehled dostane jen větu, bez rozkladu příznaků —
     *     ten patří do Analýzy a Rozšířeného přehledu.
     */
    static VBox buildAppearanceSection(byte[] pngBytes,
            cz.digitalnikontinuita.anep.model.SignatureSummary sig) {
        Label sectionLabel = new Label("Vizualizace podpisu:");
        sectionLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111; -fx-padding: 4 0 4 0;");

        VBox section = new VBox(4, sectionLabel);
        section.setFillWidth(false);
        section.setPadding(new Insets(4, 0, 0, 0));

        StackPane imagePane = pngBytes == null ? null : imagePane(pngBytes);
        if (imagePane == null) return new VBox();
        section.getChildren().add(imagePane);

        String caveat = FieldVisibilityText.appearanceCaveat(sig);
        if (caveat != null) {
            Label note = new Label(caveat);
            note.setWrapText(true);
            note.setMaxWidth(600);
            note.setStyle("-fx-text-fill: #111111; -fx-padding: 2 0 0 0;");
            section.getChildren().add(note);
        }
        return section;
    }

    /** Rámeček s obrázkem; bajty si nese s sebou, jinak by ho protokol vynechal. */
    private static StackPane imagePane(byte[] pngBytes) {
        Image image;
        try {
            image = new Image(new ByteArrayInputStream(pngBytes));
        } catch (Exception e) {
            return null;
        }

        ImageView imageView = new ImageView(image);
        // Bajty s sebou, aby je našel i protokol. {@code PrintUtil} umí obrázek vysadit, ale
        // potřebuje soubor — a z {@code Image} zpátky do PNG by se muselo překódovat přes
        // {@code SwingFXUtils}, tedy přes modul, který zabalené běhové prostředí nemá. Bez
        // tohohle řádku se v protokolu vytiskl popisek „Vizualizace podpisu:“ a pod ním nic.
        imageView.getProperties().put(PrintUtil.PNG_BYTES, pngBytes);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setFitWidth(Math.min(image.getWidth(), 600));
        imageView.setFitHeight(200);

        StackPane pane = new StackPane(imageView);
        pane.setStyle("-fx-border-color: #cccccc; -fx-border-width: 1; " +
                      "-fx-background-color: #ffffff; -fx-padding: 4;");
        pane.setAlignment(Pos.CENTER_LEFT);
        return pane;
    }
}
