package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.AppClassLoader;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.detailedreport.DetailedReportFacade;
import eu.europa.esig.dss.simplereport.SimpleReportFacade;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import net.sf.saxon.TransformerFactoryImpl;
import org.apache.fop.apps.FopFactory;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * "Protokoly" — zobrazuje Jednoduchý přehled a Podrobný přehled ve stejné
 * HTML podobě jako DSS online validační služba.  Oba reporty lze stáhnout
 * jako XML nebo PDF.
 */
public class ProtocolView {

    private final VBox root = new VBox();

    // WebView is created lazily: instantiating one boots the WebKit engine, which costs
    // roughly a second of startup time — paid twice here, for a tab most runs never open.
    // The holders sit in the scene graph from the start; the engine appears on first view.
    private final StackPane simpleHolder   = new StackPane();
    private final StackPane detailedHolder = new StackPane();
    private WebView simpleWebView;
    private WebView detailedWebView;

    /** Report HTML is only rendered into a WebView once that tab is actually shown. */
    private boolean simpleDirty   = false;
    private boolean detailedDirty = false;
    /** Set once the user opens the protocols tab; before that nothing is rendered. */
    private boolean shown = false;
    private TabPane tabs;

    private String simpleXml   = "";
    private String detailedXml = "";
    private boolean timestampStandardsMode = true;

    private final Label strictModeNote;

    public ProtocolView() {
        strictModeNote = new Label(
                "Upozornění: Je zapnuto přísné ověřování časových razítek podle jejich certifikátů " +
                "(dle notAfter). Tyto protokoly DSS vycházejí ze standardního ověřování dle ETSI " +
                "(se sunsetem) — neodráží proto dodatečnou kontrolu certifikátů TSA, kterou AnEP " +
                "aplikuje. Výsledek zobrazený AnEP může být přísnější než výsledek v těchto protokolech.");
        strictModeNote.setWrapText(true);
        strictModeNote.setStyle(
                "-fx-background-color:#fff3cd;-fx-border-color:#ffc107;-fx-border-width:1;" +
                "-fx-padding:8 12 8 12;-fx-font-size:11px;-fx-text-fill:#664d00;");
        strictModeNote.setVisible(false);
        strictModeNote.setManaged(false);

        // Banner o rozchodu s DSS zrušen 27. 8. 2026. Vysvětloval, proč AnEP ukazuje
        // „neplatný" tam, kde protokoly hlásí INDETERMINATE/REVOKED_NO_POE. Ten rozchod
        // už neexistuje — přepis verdiktu byl odstraněn a AnEP nad takovým podpisem uvádí
        // neznámou platnost stejně jako DSS.

        Tab simpleTab   = buildTab("Jednoduchý protokol",  simpleHolder,   true);
        Tab detailedTab = buildTab("Podrobný protokol",   detailedHolder, false);

        tabs = new TabPane(simpleTab, detailedTab);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        root.getChildren().addAll(strictModeNote, tabs);

        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> renderSelected());
    }

    /**
     * Called by {@link MainView} when the "Protokoly DSS" tab is opened. Until that happens
     * nothing is rendered — see the note on the lazily created WebViews above.
     */
    public void onShown() {
        shown = true;
        renderSelected();
    }

    /** Creates the WebView for the currently selected protocol tab and loads pending HTML. */
    private void renderSelected() {
        if (!shown) return; // tab never opened — nothing to draw yet
        boolean simple = tabs.getSelectionModel().getSelectedIndex() == 0;
        if (simple) {
            if (!simpleDirty) return;
            simpleDirty = false;
            loadHtml(webView(true), simpleXml, true);
        } else {
            if (!detailedDirty) return;
            detailedDirty = false;
            loadHtml(webView(false), detailedXml, false);
        }
    }

    /** Lazily instantiates the WebView for one protocol tab and puts it in its holder. */
    private WebView webView(boolean simple) {
        if (simple) {
            if (simpleWebView == null) {
                simpleWebView = new WebView();
                simpleHolder.getChildren().setAll(simpleWebView);
            }
            return simpleWebView;
        }
        if (detailedWebView == null) {
            detailedWebView = new WebView();
            detailedHolder.getChildren().setAll(detailedWebView);
        }
        return detailedWebView;
    }

    public void setTimestampStandardsMode(boolean v) {
        timestampStandardsMode = v;
        strictModeNote.setVisible(!v);
        strictModeNote.setManaged(!v);
    }

    private Tab buildTab(String title, StackPane webViewHolder, boolean simple) {
        Button xmlBtn = new Button("Stáhnout XML");
        Button pdfBtn = new Button("Stáhnout PDF");

        String baseName = simple ? "simple-report" : "detailed-report";
        xmlBtn.setOnAction(e -> saveXml(simple ? simpleXml : detailedXml, baseName + ".xml"));
        pdfBtn.setOnAction(e -> savePdf(simple ? simpleXml : detailedXml, simple, baseName + ".pdf"));

        HBox toolbar = new HBox(8, xmlBtn, pdfBtn);
        toolbar.setPadding(new Insets(8, 8, 4, 8));

        VBox.setVgrow(webViewHolder, Priority.ALWAYS);
        VBox content = new VBox(toolbar, webViewHolder);
        VBox.setVgrow(content, Priority.ALWAYS);

        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    public VBox getRoot() {
        return root;
    }

    /** Vrátí záložku do podoby před prvním ověřením; viz {@code MainView.clearResults()}. */
    public void clear() {
        simpleXml   = "";
        detailedXml = "";
        simpleDirty   = false;
        detailedDirty = false;
        if (simpleWebView != null)   simpleWebView.getEngine().loadContent("");
        if (detailedWebView != null) detailedWebView.getEngine().loadContent("");
    }

    public void show(ValidationResult result) {
        simpleXml   = result.getSimpleReportXml();
        detailedXml = result.getDetailedReportXml();
        // Defer the XSLT→HTML rendering (Saxon) and the WebView load until the tab is opened —
        // most validations never have their DSS protocols looked at.
        simpleDirty   = true;
        detailedDirty = true;
        renderSelected();
    }

    // -------------------------------------------------------------------------

    private void loadHtml(WebView webView, String xml, boolean simple) {
        if (xml == null || xml.isBlank()) return;
        try {
            String fragment = simple
                    ? SimpleReportFacade.newFacade().generateHtmlReport(xml)
                    : DetailedReportFacade.newFacade().generateHtmlReport(xml);
            // Strip soft hyphens and Unicode Private Use Area chars (U+E000–U+F8FF)
            // that DSS injects as Font Awesome icons — they render as □ in JavaFX WebView
            // when the FA font fails to load from CDN.

            // DSS XSLT emits literal \n + spaces between words inside text content.
            // JavaFX WebView renders these as □ instead of collapsing whitespace like a normal browser.
            // Collapse any whitespace run that contains a newline down to a single space.
            String cleaned = fragment
                    .replaceAll("[ \\t]*\\n[ \\t\\n]*", " ")
                    .replace("­", "")
                    .replace("&shy;", "")
                    .replaceAll("[\\uE000-\\uF8FF]", "");
            String html = wrapBootstrap(cleaned);
            // After page load: JS removes any remaining non-printable chars from DOM text nodes
            webView.getEngine().getLoadWorker().stateProperty().addListener(
                (obs, oldState, newState) -> {
                    if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                        webView.getEngine().executeScript(
                            "(function(){"
                            + "var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false);"
                            + "var n;"
                            + "while(n=w.nextNode()){"
                            + "  n.textContent=n.textContent"
                            + "    .replace(/\\u00AD/g,'')"      // soft hyphen
                            + "    .replace(/[\\uE000-\\uF8FF]/g,'')" // PUA / FA icons
                            + "    .replace(/[\\u0080-\\u009F]/g,'')" // C1 controls
                            + "    .replace(/\\uFFFD/g,'');"     // replacement char
                            + "}"
                            + "})();"
                        );
                    }
                }
            );
            webView.getEngine().loadContent(html, "text/html");
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            webView.getEngine().loadContent(
                    "<pre style='font-family:monospace;font-size:12px;padding:8px'>"
                    + escapeHtml(xml) + "</pre>",
                    "text/html");
        }
    }

    private String wrapBootstrap(String fragment) {
        return "<!DOCTYPE html><html lang=\"cs\"><head>"
                + "<meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/bootstrap@4.6.2/dist/css/bootstrap.min.css\">"
                + "<style>"
                + "body{padding:16px;font-size:14px;} "
                + ".card{margin-bottom:1rem;} "
                + ".badge{font-size:85%;} "
                + ".card-header{background-color:#003189 !important;color:#ffffff !important;border-color:#003189 !important;} "
                + ".card-header a,.card-header button,.card-header [data-toggle]{color:#ffffff !important;} "
                // Suppress Font Awesome ::before icons — they render as □ in JavaFX WebView when the FA font is unavailable
                + ".fa::before,.fas::before,.far::before,.fab::before,.fal::before,"
                + "[class^='fa-']::before,[class*=' fa-']::before{content:'' !important;font-family:inherit !important;} "
                // Certificate chain links (and other non-toggle anchors) are hidden entirely —
                // clickable cert-detail popups don't work in JavaFX WebView anyway.
                + "a:not([data-toggle]){display:none !important;} "
                + "</style>"
                + "</head><body>"
                + fragment
                + "<script src=\"https://code.jquery.com/jquery-3.6.4.slim.min.js\"></script>"
                + "<script src=\"https://cdn.jsdelivr.net/npm/bootstrap@4.6.2/dist/js/bootstrap.bundle.min.js\"></script>"
                + "</body></html>";
    }

    private void saveXml(String xml, String defaultName) {
        if (xml == null || xml.isBlank()) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Uložit XML");
        chooser.setInitialFileName(defaultName);
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("XML soubory", "*.xml"));
        File file = chooser.showSaveDialog(stage());
        if (file != null) {
            try {
                Files.write(file.toPath(), xml.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ex) {
                ex.printStackTrace(System.err);
            }
        }
    }

    private void savePdf(String xml, boolean simple, String defaultName) {
        if (xml == null || xml.isBlank()) return;

        // FileChooser musí běžet na JavaFX threadu
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Uložit PDF");
        chooser.setInitialFileName(defaultName);
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PDF soubory", "*.pdf"));
        File file = chooser.showSaveDialog(stage());
        if (file == null) return;

        // FOP generování na background threadu s AppClassLoader
        // (FOP interně používá ServiceLoader, který na JavaFX threadu může selhat)
        Thread t = new Thread(() -> {
            // FOP loads its SPI (incl. gatherContextInfo function) via context classloader.
            // AppClassLoader may not see FOP's META-INF/services — use FOP's own classloader.
            Thread.currentThread().setContextClassLoader(FopFactory.class.getClassLoader());
            try {
                // Fáze 1: Saxon XSLT 2.0 — XML → XSL-FO
                // Používáme Saxon přímo (new TransformerFactoryImpl()), aby DSS XSLT
                // funkce jako gatherContextInfo byly rozpoznány bez ohledu na JAXP lookup
                String xsltPath = simple
                        ? "xslt/pdf/simple-report.xslt"
                        : "xslt/pdf/detailed-report.xslt";
                ByteArrayOutputStream xslFoOut = new ByteArrayOutputStream();
                try (InputStream xsltStream = Thread.currentThread()
                        .getContextClassLoader().getResourceAsStream(xsltPath)) {
                    if (xsltStream == null) throw new IllegalStateException(
                            "XSLT not found on classpath: " + xsltPath);
                    TransformerFactory saxonFactory = new TransformerFactoryImpl();
                    Transformer xsltTransformer = saxonFactory.newTransformer(new StreamSource(xsltStream));
                    xsltTransformer.transform(new StreamSource(new StringReader(xml)),
                                new StreamResult(xslFoOut));
                }

                // Fáze 2: FOP — XSL-FO → PDF/A-2b.
                // Přes PrintUtil, protože tamní továrna má nakonfigurovaná písma. Šablony DSS
                // neuvádějí font-family vůbec, takže bez ní FOP sáhne po základní Helvetice,
                // kterou nelze vložit do souboru — a PDF/A ji odmítá.
                if (!PrintUtil.renderPdfA(xslFoOut.toByteArray(), file)) {
                    Platform.runLater(() -> PrintUtil.showNotPdfA(stage()));
                }
            } catch (Exception ex) {
                // Táž obsluha jako u ostatních exportů: zamčený soubor dostane větu, se kterou
                // se dá něco dělat, cokoli jiného výpis zásobníku. Soubor se nemaže — býval by
                // to předchozí export uživatele.
                Platform.runLater(() -> PrintUtil.showExportFailure(stage(), ex, file));
            }
        }, "pdf-export");
        t.setDaemon(true);
        t.start();
    }

    private Stage stage() {
        return (Stage) root.getScene().getWindow();
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
