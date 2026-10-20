package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.FixationService;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.AdvisorView;
import cz.digitalnikontinuita.anep.ui.DetailedReportView;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Vykreslí kartu jedné ze čtyř záložek pro daný soubor a uloží ji jako PNG.
 *
 * <p>Aplikace je desktopová a okno se nedá ovládat zvenčí, takže „ukaž, jak to vypadá" se jinak
 * odpovědět nedá než snímkem scény. Staví se skutečné {@code show()} té které záložky, se
 * skutečnými službami, tedy totéž, co uvidí uživatel; rozdíl je jen v tom, že se místo okna
 * udělá snímek.
 *
 * <p>Výchozí záložka je Analýza ({@code AdvisorView}). {@code -view zjednoduseny} přepne na
 * {@code ResultView}, {@code -view rozsireny} na {@code ExtendedView} — obě jsou bez akčních
 * tlačítek, takže na rozdíl od Analýzy nepotřebují {@code FixationService} a spol.
 * {@code -view kontinuita} přepne na {@code ContinuityView} (staví se přes Analýzu, viz níže).
 * {@code -detail} přepne na {@code DetailedReportView}, jak už dřív.
 *
 * <p>Volitelný {@code -expand} rozbalí zabalené bloky, aby bylo vidět i to, co je na kartě
 * schované — poznámky k výsledku bývají zavřené.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.tools.CardSnapshot \
 *       -Dexec.args="-out D:\out\karta.png -view rozsireny -expand D:\cesta\k\souboru.pdf"
 * </pre>
 */
public final class CardSnapshot extends Application {

    @Override
    public void start(Stage stage) {
        List<String> args = getParameters().getRaw();
        String out = null, file = null, view = "analyza";
        boolean expand = false, lenient = false, detail = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "-out" -> out = args.get(++i);
                case "-expand" -> expand = true;
                // Reprodukce toho, co uživatel vidí po zaškrtnutí všech ústupků; bez toho sonda
                // ověřuje jiný běh než ten, na který se ptá.
                case "-all" -> lenient = true;
                // Detailní rozbor místo Analýzy — od přesunu ladicích informací je to jediné
                // místo, kde se dají vidět.
                case "-detail" -> detail = true;
                // Zjednodušený nebo Rozšířený přehled místo Analýzy — obě mají v kódu jinou
                // zarovnávací logiku (Pos.CENTER_LEFT místo výchozího Pos.TOP_LEFT), takže se
                // v nich chyby zarovnání projevují jinak, než v Analýze.
                case "-view" -> view = args.get(++i);
                default -> file = args.get(i);
            }
        }
        if (out == null || file == null) {
            System.out.println("usage: CardSnapshot -out <png> [-expand]"
                    + " [-view analyza|zjednoduseny|rozsireny|kontinuita] <file>");
            Platform.exit();
            return;
        }
        final String outPath = out, filePath = file, viewName = view;
        final boolean doExpand = expand, doLenient = lenient, doDetail = detail;
        new Thread(() -> {
            try {
                run(outPath, filePath, doExpand, doLenient, doDetail, viewName);
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
            Platform.exit();
        }, "card-snapshot").start();
    }

    private void run(String out, String file, boolean expand, boolean lenient,
                     boolean detail, String view) throws Exception {
        AppSettings settings = AppSettings.load();
        if (lenient) {
            settings.setAcceptNewerOcsp(true);
            settings.setAcceptExpiredRevocationSources(true);
            settings.setIgnoreSha1Deprecated(true);
            settings.setAcceptPdfNotEtsi(true);
        }
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();
        DSSDocument doc = new DocumentLoader().load(new File(file));
        // Vzhledy podpisů se musí předat, jinak nástroj vykreslí kartu bez bloku Vizualizace
        // a vypadá to jako chyba aplikace. Zjištěno 4. 9. 2026 při ověřování neviditelného
        // tisknutelného podpisu — snímek blok neměl, protože ho neměl co postavit.
        cz.digitalnikontinuita.anep.core.PdfSource pdfSource =
                cz.digitalnikontinuita.anep.core.PdfSource.of(new File(file));
        ValidatorFactory.ValidationOutcome outcome =
                new ValidatorFactory(settings).validate(doc, null, verifier, null);
        // Které ústupky verdikt opravdu nesly, se zjišťuje přeověřením bez nich — až do
        // 19. 9. 2026 to uměl jedině {@code MainView}, kdežto tenhle nástroj mapoval bez toho
        // a {@code ReportMapper} si musel vypomáhat odvozením. Karta pak nad souborem, který
        // bez ústupku neprojde, tvrdila, že se ústupky neprojevily a bez nich by ověření
        // dopadlo stejně. Nástroj, který má ukazovat, co uvidí uživatel, musí jít toutéž cestou.
        ValidationResult result = new ReportMapper().map(new File(file).getName(), null,
                outcome, settings,
                cz.digitalnikontinuita.anep.core.PdfSignatureAppearanceExtractor
                        .extract(pdfSource, null),
                java.util.List.of(), false, java.util.Map.of(),
                cz.digitalnikontinuita.anep.core.RelaxationMeasurement.measure(
                        settings, outcome, doc, null, verifier, null, java.util.List.of()));
        // Prázdná podpisová pole nastavuje MainView až za mapováním, takže bez tohohle řádku by
        // snímek ukazoval kartu bez bloku, který v aplikaci je — a nástroj má ukazovat totéž,
        // co uživatel.
        result.setEmptySignatureFields(
                cz.digitalnikontinuita.anep.core.PdfEmptySignatureFieldDetector.detect(
                        pdfSource, null));
        System.out.println("prvku: " + result.getSignatures().size()
                + "  prázdných polí: " + result.getEmptySignatureFields().size());
        for (var sig : result.getSignatures()) {
            System.out.println("   " + sig.getIndication() + "  situace="
                    + sig.getDetectedSituations());
            // Vysvětlení lhůty se v záložce sází doprostřed odstavce, takže se z obrázku dá
            // přečíst jen s lupou. Jmenuje přitom certifikát, a to je věc, která se snadno
            // rozejde s tím, čí certifikát to opravdu je — proto se vypisuje samostatně.
            if (sig.getAugmentationDeadline() != null) {
                System.out.println("   lhůta: " + sig.getAugmentationDeadline().reason());
            }
            if (sig.getUnprovenRevocationDeadline() != null) {
                System.out.println("   vložený údaj: "
                        + sig.getUnprovenRevocationDeadline().reason());
            }
            // Fakta, ze kterých se skládají věty bloků: úroveň, oba certifikáty a revokační
            // údaj, o který se závěr opřel. Vypisují se, aby se text dal navrhovat z toho, co
            // model doopravdy má, a ne z toho, co si o něm myslíme.
            System.out.println("   formát=" + sig.getFormat()
                    + "  právníTyp=" + sig.getLegalType()
                    + "  podepsal=" + sig.getSignerDisplayName()
                    + "  certDo=" + sig.getCertNotAfter()
                    + "  BST=" + sig.getBestSignatureTime());
            for (var ts : sig.getAllTimestamps()) {
                System.out.println("      razítko " + ts.tsKind() + " od=" + ts.issuer()
                        + " vzniklo=" + ts.time() + " certDo=" + ts.notAfter()
                        + " kvalifikované=" + ts.qualified() + " platné=" + ts.passed());
            }
            // Který článek lhůtu drží. Bez toho se nedá poznat, která větev odvození se
            // vypsala, a texty variant se kontrolují po jednom dokumentu naslepo.
            System.out.println("      horizont: " + ContinuityHorizonProbe.describe(sig));
            var vc = sig.getValidationCourse();
            if (vc != null && vc.signingCertRevocation() != null) {
                var r = vc.signingCertRevocation();
                System.out.println("      revokační údaj (využitý): "
                        + (r.ocsp() ? "OCSP" : "CRL")
                        + " odkud=" + r.origin() + " vydán=" + r.issued()
                        + " ústupek=" + r.relaxedRule());
            }
            // Údaj, který v dokumentu je, ale použít ho nešlo. U prvku, který neobstál, je to
            // jediné, co se o vloženém údaji ví — a fakta o něm musí mluvit stejně.
            if (vc != null && vc.signingCertRevocationUnusableFact() != null) {
                var r = vc.signingCertRevocationUnusableFact();
                System.out.println("      revokační údaj (nepoužitelný): "
                        + (r.ocsp() ? "OCSP" : "CRL")
                        + " odkud=" + r.origin() + " vydán=" + r.issued()
                        + " ústupek=" + r.relaxedRule());
            }
            if (vc != null) {
                System.out.println("      vložený údaj k podpisovému certifikátu: "
                        + vc.signingCertHasEmbeddedRevocation());
                for (var line : vc.revocationUsed()) {
                    System.out.println("      využito: ocsp=" + line.ocsp() + "  "
                            + line.text());
                }
                for (var line : vc.revocationNotDecisive()) {
                    System.out.println("      nerozhodné: ocsp=" + line.ocsp() + "  "
                            + line.text());
                }
                for (var line : vc.revocationRejected()) {
                    System.out.println("      odmítnuto: ocsp=" + line.ocsp() + "  "
                            + line.text());
                }
            }
        }

        final Object lock = new Object();
        final boolean[] done = { false };
        Platform.runLater(() -> {
            try {
                if (detail) {
                    DetailedReportView dv = new DetailedReportView();
                    dv.show(result);
                    VBox dRoot = (VBox) contentOf(dv.getRoot());
                    Scene dScene = new Scene(dRoot, 980, 100);
                    dRoot.applyCss();
                    dRoot.layout();
                    if (expand) { expandAll(dRoot); dRoot.applyCss(); dRoot.layout(); }
                    dRoot.resize(940, dRoot.prefHeight(940));
                    dRoot.layout();
                    WritableImage dImg = dRoot.snapshot(new SnapshotParameters(), null);
                    File dPng = new File(out);
                    if (dPng.getParentFile() != null) dPng.getParentFile().mkdirs();
                    ImageIO.write(toBufferedImage(dImg), "png", dPng);
                    System.out.println("uloženo: " + dPng.getAbsolutePath()
                            + "  " + (int) dImg.getWidth() + "×" + (int) dImg.getHeight());
                    synchronized (lock) { done[0] = true; lock.notifyAll(); }
                    return;
                }
                VBox root;
                switch (view) {
                    case "zjednoduseny" -> {
                        cz.digitalnikontinuita.anep.ui.ResultView rv =
                                new cz.digitalnikontinuita.anep.ui.ResultView();
                        rv.show(result);
                        root = rv.getRoot();
                    }
                    case "rozsireny" -> {
                        cz.digitalnikontinuita.anep.ui.ExtendedView ev =
                                new cz.digitalnikontinuita.anep.ui.ExtendedView();
                        ev.show(result);
                        root = ev.getRoot();
                    }
                    // Digitální kontinuita se staví přes Analýzu: obsah závislý na dokumentu
                    // i řádek s tlačítky jí předává AdvisorView, takže bez jeho show() by
                    // záložka nesla jen oba výkladové bloky a výchozí hlášku pod nimi.
                    case "kontinuita" -> {
                        cz.digitalnikontinuita.anep.ui.ContinuityView cv =
                                new cz.digitalnikontinuita.anep.ui.ContinuityView();
                        AdvisorView av = advisor(settings, verifier, file);
                        av.setContinuityView(cv);
                        // Řádek s tlačítky předává v aplikaci MainView. Bez tohohle řádku by
                        // snímek záložku ukázal bez tlačítek — a právě na nich je od 7. 9. 2026
                        // vidět, kterou variantu brána otevřela.
                        cv.setActionRow(av.getActionRow());
                        av.show(result);
                        // Snímek se bere z obsahu rolovací plochy, kdežto tlačítka stojí pod ní,
                        // takže na obrázku nebudou. Vypsat je stačí — je to jméno varianty.
                        StringBuilder btns = new StringBuilder();
                        for (Node n : av.getActionRow().getChildrenUnmodifiable()) {
                            if (n instanceof javafx.scene.control.Button b && b.isVisible()) {
                                btns.append(btns.isEmpty() ? "" : " | ").append(b.getText());
                            }
                        }
                        System.out.println("tlačítka: "
                                + (btns.isEmpty() ? "(žádné)" : btns.toString()));
                        root = cv.getRoot();
                    }
                    default -> {
                        AdvisorView av = advisor(settings, verifier, file);
                        av.show(result);
                        root = av.getRoot();
                    }
                }
                Scene scene = new Scene(root, 980, 100);
                scene.getStylesheets().add(
                        getClass().getResource("/css/app.css") != null
                                ? getClass().getResource("/css/app.css").toExternalForm()
                                : "data:text/css,");
                root.applyCss();
                root.layout();
                if (expand) {
                    expandAll(root);
                    root.applyCss();
                    root.layout();
                }
                // ResultView.selectable() dopočítává výšku hodnotových TextArea až přes
                // Platform.runLater — jednou po připojení do scény, podruhé (dvakrát vnořeně)
                // po ustálení šířky. Bez čekání na tato kola snímek zachytí TextArea ještě
                // v jejich výchozí (mnohem vyšší) výšce — přesně to 3. 9. 2026 předvedl snímek
                // Zjednodušeného přehledu, kde hodnota seděla nahoře v jinak prázdném boxu.
                // Snímek se tedy nebéře hned, ale až po několika dalších průchodech front FX
                // vlákna, aby všechna vnořená runLater kola doběhla.
                pump(4, () -> {
                    // Snímek se dělá z obsahu rolovací plochy, ne z kořene: kořen je vysoký přesně
                    // tolik, kolik měří okno, kdežto zajímavá je celá karta.
                    Node target = contentOf(root);
                    target.applyCss();
                    if (target instanceof Region r) {
                        r.resize(940, r.prefHeight(940));
                    }
                    root.layout();
                    SnapshotParameters sp = new SnapshotParameters();
                    WritableImage img = target.snapshot(sp, null);
                    try {
                        File png = new File(out);
                        if (png.getParentFile() != null) png.getParentFile().mkdirs();
                        ImageIO.write(toBufferedImage(img), "png", png);
                        System.out.println("uloženo: " + png.getAbsolutePath()
                                + "  " + (int) img.getWidth() + "×" + (int) img.getHeight());
                    } catch (Exception e) {
                        e.printStackTrace(System.out);
                    }
                    synchronized (lock) { done[0] = true; lock.notifyAll(); }
                });
            } catch (Exception e) {
                e.printStackTrace(System.out);
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
        });
        synchronized (lock) {
            while (!done[0]) lock.wait();
        }
    }

    /**
     * Analýza se všemi službami, na které se ptají brány úkonů. Bez nich vychází dokument, jehož
     * jedinou cestou je přerazítkování, jako „obstál, a přesto se nic nenabízí“.
     */
    private static AdvisorView advisor(AppSettings settings, CommonCertificateVerifier verifier,
                                       String file) {
        AdvisorView av = new AdvisorView();
        av.setAppSettings(settings);
        av.setSourceFile(new File(file), null, null);
        var ref = new java.util.concurrent.atomic.AtomicReference<>(verifier);
        av.setFixationService(new FixationService(ref));
        av.setRestampingService(new cz.digitalnikontinuita.anep.core.RestampingService());
        av.setAugmentationService(new cz.digitalnikontinuita.anep.core.AugmentationService(ref));
        av.setExtensionService(
                new cz.digitalnikontinuita.anep.core.SignatureExtensionService(ref));
        av.setTimestampingService(new cz.digitalnikontinuita.anep.core.TimestampingService(ref));
        return av;
    }

    /** Zavolá {@code finalStep} až za {@code rounds} dalších průchodů fronty FX vlákna. */
    private static void pump(int rounds, Runnable finalStep) {
        if (rounds <= 0) { finalStep.run(); return; }
        Platform.runLater(() -> pump(rounds - 1, finalStep));
    }

    /**
     * Převod bez {@code SwingFXUtils} — modul {@code javafx.swing} v téhle sestavě není
     * a přidávat závislost kvůli sondě se nevyplatí. Čte se přes {@code PixelReader}, což je
     * pomalé, ale jde o jeden snímek.
     */
    private static java.awt.image.BufferedImage toBufferedImage(WritableImage img) {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        java.awt.image.BufferedImage out =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader px = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, px.getArgb(x, y));
            }
        }
        return out;
    }

    /** Obsah rolovací plochy, je-li nějaká; jinak kořen sám. */
    private static Node contentOf(Node node) {
        if (node instanceof ScrollPane sp && sp.getContent() != null) return sp.getContent();
        if (node instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                Node found = contentOf(child);
                if (found != child || child instanceof ScrollPane) return found;
            }
        }
        return node;
    }

    /** Rozbalí bloky, které se staví jako zabalené — jinak je na snímku vidět jen nadpis. */
    private static void expandAll(Node node) {
        List<Node> stack = new ArrayList<>();
        stack.add(node);
        while (!stack.isEmpty()) {
            Node n = stack.remove(stack.size() - 1);
            // collapsibleBlock() schovává tělo přes visible/managed, jiný příznak nemá —
            // rozbalení je tedy „co je neviditelné a nespravované, zviditelni".
            if (!n.isVisible() && !n.isManaged()) {
                n.setVisible(true);
                n.setManaged(true);
            }
            // Šipka bloku je kreslený trojúhelník (StackPane s jediným Polygonem). Rozbalení
            // jde tudy mimo toggle v AdvisorView, takže by zůstala ukazovat doprava, přestože
            // tělo bloku už je vidět — překreslí se tedy špičkou dolů, jako to dělá kliknutí.
            if (n instanceof javafx.scene.layout.StackPane sp && sp.getChildren().size() == 1
                    && sp.getChildren().get(0) instanceof javafx.scene.shape.Polygon tri) {
                double a = 13, h = a * Math.sqrt(3) / 2;
                tri.getPoints().setAll(List.of(0.0, 0.0, a, 0.0, a / 2, h));
            }
            if (n instanceof javafx.scene.Parent p) {
                stack.addAll(p.getChildrenUnmodifiable());
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
