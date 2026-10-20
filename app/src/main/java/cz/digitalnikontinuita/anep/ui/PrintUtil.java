package cz.digitalnikontinuita.anep.ui;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamSource;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.apache.fop.apps.FOUserAgent;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopConfParser;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.MimeConstants;
import org.apache.fop.pdf.PDFConformanceException;

/**
 * Exports a JavaFX VBox content tree as a text-based A4 PDF/A via Apache FOP.
 *
 * Walks the scene graph and emits XSL-FO, taking the text of any Labeled
 * (Label, Button, Hyperlink), any TextInputControl (TextField, TextArea) and
 * any Text, and descending into everything else that has children.
 * Consecutive "label-value" HBox rows (first child = bold Label with
 * minWidth ≥ 80) are grouped into a two-column FO table so columns align.
 */
final class PrintUtil {

    /**
     * Zelená z loga aplikace a její světlá plocha; obojí odečteno přímo z {@code app-icon.png}
     * (6728 obrazových bodů #61ae20, 45667 bodů #e2f0d9).
     *
     * <p>Text v pruhu je tmavý, ne bílý, a je to měřený požadavek: bílá na #61ae20 dává kontrast
     * 2,77 : 1, kdežto norma žádá 4,5 : 1. Tmavá na téže zelené dává 6,82 : 1. Alternativou by
     * bylo zelenou ztmavit, jenže pak by to nebyla barva loga.
     */
    private static final String LOGO_GREEN = "#61ae20";
    private static final String LOGO_GREEN_DARK = "#14320a";

    /**
     * Barvu nese jen záhlaví a řádek se stavem platnosti; všechno ostatní je na bílé a odděluje
     * se černou čárou. Protokol je tištěný dokument, ne obrazovka: barevných ploch v něm má být
     * co nejmíň, aby to, co barvu má, znamenalo něco samo o sobě.
     */
    private static final String RULE = "0.7pt solid #000000";

    /** Rozbalené logo pro FOP; jednou za běh, protože FOP chce cestu, ne proud. */
    private static java.io.File logoFile;

    private PrintUtil() {}

    /**
     * Logo jako soubor, na který se FOP umí odkázat, nebo {@code null}, když to nevyjde.
     *
     * <p>Selhání se nehlásí: protokol bez loga je pořád protokol, kdežto protokol, který se kvůli
     * obrázku nevytvoří, není k ničemu.
     */
    private static synchronized String logoUri() {
        try {
            if (logoFile == null || !logoFile.isFile()) {
                java.io.File tmp = java.io.File.createTempFile("anep-logo", ".png");
                tmp.deleteOnExit();
                try (java.io.InputStream in = PrintUtil.class.getResourceAsStream("/app-icon.png")) {
                    if (in == null) return null;
                    Files.copy(in, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                logoFile = tmp;
            }
            return logoFile.toURI().toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The archival profile every protocol is written in, and the PDF version it needs.
     *
     * <p>The Formátová pravidla Národního archivu accept all eight PDF/A levels, so the choice is
     * ours. It is <em>not</em> the obvious PDF/A-1b: that part requires a CIDSet stream for every
     * embedded CID font subset (ISO 19005-1, 6.3.5) and FOP writes none, so veraPDF rejects the
     * output — the only failing rule out of 129, but a failure all the same. Embedding the fonts
     * whole instead of subsetting them (single-byte encoding mode) does not fix it and grows the
     * file thirtyfold. PDF/A-2 dropped the requirement, and 2b passes veraPDF as it stands.
     *
     * <p>Not PDF/A-3, which would allow attachments but counts as a <em>container</em> format to
     * the state's SIP validator (fmt/479–481), dragging in rules about unpacking it. PDF/A-2 is
     * fmt/476–478 and stays clear of them.
     *
     * <p>The version has to be said out loud: FOP writes a 1.4 header whatever the PDF/A mode, and
     * PDF/A-2 sits on PDF 1.7. The two constants belong together — FOP refuses PDF/A-1b outright
     * once the version is raised to 1.7, so neither can be changed on its own.
     */
    private static final String PDFA_MODE = "PDF/A-2b";
    private static final String PDF_VERSION = "1.7";

    /**
     * Profil pro protokol, který nese ověřovaný soubor jako přílohu.
     *
     * <p>Vynucený, ne zvolený: PDF/A-2 připouští přílohu jen tehdy, je-li sama PDF/A (ISO 19005-2,
     * klauzule 6.8), což ověřovaný soubor skoro nikdy není. Změřeno na veraPDF — PDF/A-2b
     * s přílohou padá na pravidle 6.8-5, PDF/A-3b s toutéž přílohou projde. Cena je popsaná
     * u {@link cz.digitalnikontinuita.anep.core.PdfAttachments}: PDF/A-3 je pro validátor státního SIP
     * kontejnerový formát. Proto se na přílohu ptáme a bez ní zůstává protokol PDF/A-2b.
     */
    private static final String PDFA_ATTACHMENT_MODE = "PDF/A-3b";

    /**
     * Řezy, které zastoupí písma base-14, v pořadí obyčejné, tučné, kurzíva, tučná kurzíva.
     *
     * <p>FO, které rodinu neuvádí vůbec, spadne na base-14 Helvetiku, a tu vložit nelze; PDF/A
     * to odmítne slovy „For PDF/A-2b, all fonts, even the base 14 fonts, have to be embedded!
     * Offending font: /Helvetica". Předlohy protokolů DSS písmo nikde neurčují, takže bez
     * těchto náhrad by jejich výstup nemohl být PDF/A nikdy.
     *
     * <p>Účinné je deklarovat trojice; {@code <substitution>} nechá výběr na FOP a export
     * skončí toutéž hláškou (změřeno).
     *
     * <p><b>Rodin je víc kvůli jiným systémům.</b> Dřív se hledal jen windowsový
     * {@code %SystemRoot%\Fonts\arial.ttf}; na Linuxu a macOS se nenašlo nic a protokol se
     * uložil jako obyčejné PDF. Liberation Sans je metricky shodná s Arialem a bývá na Linuxu,
     * DejaVu Sans je náhradní volba, na macOS je Arial ve složce Supplemental. Bere se první
     * rodina, ze které se najde aspoň obyčejný řez.
     */
    private static final String[][] FONT_FAMILIES = {
        // Windows
        {"arial.ttf", "arialbd.ttf", "ariali.ttf", "arialbi.ttf"},
        // macOS (Arial je v Supplemental), pak Helvetica jako záloha
        {"Arial.ttf", "Arial Bold.ttf", "Arial Italic.ttf", "Arial Bold Italic.ttf"},
        // Linux — metricky shodná s Arialem
        {"LiberationSans-Regular.ttf", "LiberationSans-Bold.ttf",
         "LiberationSans-Italic.ttf", "LiberationSans-BoldItalic.ttf"},
        // Linux, náhradní volba
        {"DejaVuSans.ttf", "DejaVuSans-Bold.ttf",
         "DejaVuSans-Oblique.ttf", "DejaVuSans-BoldOblique.ttf"},
    };

    /** Váha a řez ke každému sloupci {@link #FONT_FAMILIES}. */
    private static final String[][] FACE_STYLES = {
        {"normal", "normal"}, {"bold", "normal"}, {"normal", "italic"}, {"bold", "italic"},
    };

    // Cached FOP factory configured with system font auto-detection (supports Czech diacritics).
    // Initialised once on first use; subsequent exports reuse it without rescanning fonts.
    private static volatile FopFactory FACTORY;

    private static FopFactory getFopFactory() throws Exception {
        if (FACTORY == null) {
            synchronized (PrintUtil.class) {
                if (FACTORY == null) {
                    String conf =
                        "<?xml version=\"1.0\"?>" +
                        "<fop version=\"1.0\">" +
                          "<renderers>" +
                            "<renderer mime=\"application/pdf\">" +
                              "<version>" + PDF_VERSION + "</version>" +
                              fontConfig() +
                            "</renderer>" +
                          "</renderers>" +
                        "</fop>";
                    FopConfParser parser = new FopConfParser(
                        new ByteArrayInputStream(conf.getBytes(StandardCharsets.UTF_8)),
                        new File(".").toURI());
                    FACTORY = parser.getFopFactoryBuilder().build();
                }
            }
        }
        return FACTORY;
    }

    /**
     * Samovyhledávání písem plus náhrady za base-14. Řez, který na stroji není, se prostě
     * vynechá — export pak funguje dál, jen nemůže být PDF/A, což {@link #renderPdfA} řekne
     * nahlas, místo aby selhal.
     */
    private static String fontConfig() {
        StringBuilder sb = new StringBuilder("<fonts><auto-detect/>");
        File[] found = findFaces();
        for (int i = 0; i < found.length; i++) {
            if (found[i] == null) continue;
            sb.append("<font embed-url=\"").append(found[i].toURI()).append("\" kerning=\"yes\">");
            for (String family : new String[] {"Helvetica", "sans-serif", "any"}) {
                sb.append("<font-triplet name=\"").append(family)
                  .append("\" style=\"").append(FACE_STYLES[i][1])
                  .append("\" weight=\"").append(FACE_STYLES[i][0]).append("\"/>");
            }
            sb.append("</font>");
        }
        return sb.append("</fonts>").toString();
    }

    /**
     * První rodina z {@link #FONT_FAMILIES}, ze které se najde aspoň obyčejný řez; vrací čtyři
     * soubory, chybějící jako {@code null}.
     *
     * <p>Rodiny se míchat nesmějí. Tučný řez z jiné rodiny než obyčejný by v protokolu vypadal
     * jako chyba sazby, a to u dokumentu, který má být důkazem, není maličkost.
     */
    private static File[] findFaces() {
        List<File> dirs = fontDirs();
        for (String[] family : FONT_FAMILIES) {
            File[] faces = new File[family.length];
            for (int i = 0; i < family.length; i++) {
                for (File dir : dirs) {
                    File f = new File(dir, family[i]);
                    if (f.isFile()) { faces[i] = f; break; }
                }
            }
            if (faces[0] != null) return faces;
        }
        return new File[FACE_STYLES.length];
    }

    /** Kde který systém drží písma. Neexistující adresáře se přeskočí. */
    private static List<File> fontDirs() {
        List<File> dirs = new java.util.ArrayList<>();
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null) dirs.add(new File(systemRoot, "Fonts"));
        String home = System.getProperty("user.home", "");
        for (String path : new String[] {
                "/System/Library/Fonts/Supplemental", "/System/Library/Fonts", "/Library/Fonts",
                home + "/Library/Fonts",
                "/usr/share/fonts/truetype/liberation", "/usr/share/fonts/truetype/liberation2",
                "/usr/share/fonts/truetype/dejavu", "/usr/share/fonts/liberation-sans",
                "/usr/share/fonts/dejavu-sans-fonts", "/usr/share/fonts/TTF",
                "/usr/share/fonts", "/usr/local/share/fonts", home + "/.fonts"}) {
            File d = new File(path);
            if (d.isDirectory()) dirs.add(d);
        }
        return dirs;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Must be called on the JavaFX Application Thread.
     * Generates XSL-FO on the FX thread (scene-graph access), then renders
     * the PDF on a daemon background thread.
     */
    /**
     * Navržené jméno protokolu: {@code <předpona>_<jméno>_<přípona>.pdf}, tedy ze
     * {@code dokument.pdf} vyjde {@code ZP_dokument_pdf.pdf}, resp. {@code RP_dokument_pdf.pdf}.
     *
     * <p>Přípona se do jména vpisuje, místo aby se zahodila: protokoly k {@code smlouva.pdf}
     * a {@code smlouva.asice} by jinak nesly totéž jméno a ve složce by se navzájem přepsaly.
     * Tečka se proto mění na podtržítko a nová {@code .pdf} patří protokolu samotnému.
     *
     * <p>Bez souboru — a tedy bez čeho jméno složit — zbývá holé {@code <předpona>_protokol.pdf}.
     *
     * @param prefix {@code ZP} pro Zjednodušený přehled, {@code RP} pro Rozšířený,
     *               {@code DA} pro Detailní rozbor
     */
    static String protocolFileName(String prefix, File source) {
        if (source == null) return prefix + "_protokol.pdf";
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "";
        return prefix + "_" + base + (ext.isEmpty() ? "" : "_" + ext) + ".pdf";
    }

    static void saveAsPdf(Node contentNode, Stage ownerStage, String suggestedName,
                          File sigFile, File origFile, String title,
                          java.time.Instant verifiedAt, List<String> decisiveSettings) {
        saveAsPdf(contentNode, ownerStage, suggestedName, sigFile, origFile, title, verifiedAt,
                  decisiveSettings, null);
    }

    /**
     * @param extras razítkovací služba a jméno vybraného zdroje, nebo {@code null}, když je
     *               aplikace nemá čím obsloužit — otázka na razítko se pak nenabídne
     */
    static void saveAsPdf(Node contentNode, Stage ownerStage, String suggestedName,
                          File sigFile, File origFile, String title,
                          java.time.Instant verifiedAt, List<String> decisiveSettings,
                          ProtocolExtras extras) {
        // Napřed otázky, teprve pak výběr souboru: obě odpovědi mění, co se bude ukládat, a ptát
        // se na ně až po zadání jména by znamenalo ptát se, když už je uživatel rozhodnutý.
        ProtocolOptions options = ProtocolOptionsDialog.ask(ownerStage, sigFile, origFile, extras);
        if (options == null) return;

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Generovat protokol jako PDF");
        chooser.setInitialFileName(suggestedName);
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PDF soubory (*.pdf)", "*.pdf"));
        File file = chooser.showSaveDialog(ownerStage);
        if (file == null) return;

        // Scene-graph walk must happen on the FX thread.
        String fo = buildFo(contentNode, sigFile, origFile, title, verifiedAt, decisiveSettings);

        Thread worker = new Thread(() -> {
            Thread.currentThread().setContextClassLoader(FopFactory.class.getClassLoader());
            try {
                Outcome outcome = writeProtocol(fo.getBytes(StandardCharsets.UTF_8), file,
                        options, attachments(sigFile, origFile), extras);
                if (!outcome.archival()) Platform.runLater(() -> showNotPdfA(ownerStage));
                if (outcome.stampFailure() != null) {
                    Platform.runLater(() -> showStampFailure(ownerStage, outcome.stampFailure()));
                }
            } catch (Exception ex) {
                Platform.runLater(() -> showExportFailure(ownerStage, ex, file));
            }
        }, "pdf-export");
        worker.setDaemon(true);
        worker.start();
    }

    /** Co uživatel odpověděl na obě otázky. Obojí je ve výchozím stavu vypnuté. */
    record ProtocolOptions(boolean attachSource, boolean timestamp) { }

    /**
     * Co protokol umí nad rámec sazby. Drží to {@code MainView}, protože razítkovací služba
     * i vybraný zdroj patří jemu; sem se předává, aby {@code PrintUtil} nemusel znát nastavení.
     */
    interface ProtocolExtras {
        /** Jméno vybraného zdroje časových razítek, nebo {@code null}, když žádný vybrán není. */
        String tsaName();

        /**
         * Co se o vybraném zdroji naměřilo — vydává kvalifikovaná razítka?
         *
         * <p>Nabídka razítka v dialogu o protokolu na tom stojí: slibovat „kvalifikované
         * elektronické časové razítko" nad zdrojem, o kterém se ví, že kvalifikovaná nevydává,
         * je nepravda na zaškrtávátku, které si uživatel přečte těsně předtím, než ho zmáčkne.
         *
         * <p>Výchozí odpověď je „nezjištěno“, tedy stav, ve kterém texty mluví jako dřív:
         * implementace, která se tím nezabývá (zkoušky, sondy), tím nic nerozbije.
         */
        default cz.digitalnikontinuita.anep.core.TsaProfile.Quality tsaQuality() {
            return cz.digitalnikontinuita.anep.core.TsaProfile.Quality.UNKNOWN;
        }

        /** Orazítkuje soubor na místě. Volá se na pracovním vlákně, ne na tom obslužném. */
        void stamp(File pdf) throws Exception;
    }

    /**
     * Jak export dopadl. Dvě různé věci, které se obě mohou nepovést zvlášť: archivní profil
     * a razítko. Protokol bez razítka je pořád protokol, takže se neúspěch hlásí zvlášť a soubor
     * zůstane uložený.
     */
    record Outcome(boolean archival, Exception stampFailure) { }

    /**
     * Vysází, případně přiloží soubory, případně orazítkuje — a teprve pak sáhne na cílový soubor.
     *
     * <p>Pořadí je dané: razítko musí krýt i přílohu, takže se přikládá dřív, než se razítkuje.
     * A všechno se odehraje nad dočasným souborem, protože export přepisuje zpravidla včerejší
     * export a ten nesmí zmizet kvůli něčemu, co se nepovedlo v půlce.
     */
    static Outcome writeProtocol(byte[] fo, File target, ProtocolOptions options,
                                         List<cz.digitalnikontinuita.anep.core.PdfAttachments.Attachment> files,
                                         ProtocolExtras extras) throws Exception {
        boolean attach = options.attachSource() && !files.isEmpty();
        byte[] pdf;
        boolean archival = true;
        try {
            pdf = render(fo, attach ? PDFA_ATTACHMENT_MODE : PDFA_MODE);
        } catch (Exception ex) {
            if (!isConformanceFailure(ex)) throw ex;
            System.err.println("PDF/A není dosažitelné, ukládám jako běžné PDF: " + rootCause(ex));
            pdf = render(fo, null);
            archival = false;
        }
        if (attach) pdf = cz.digitalnikontinuita.anep.core.PdfAttachments.attach(pdf, files);

        Exception stampFailure = null;
        if (options.timestamp() && extras != null) {
            File tmp = File.createTempFile("anep-protokol-", ".pdf");
            try {
                Files.write(tmp.toPath(), pdf);
                extras.stamp(tmp);
                pdf = Files.readAllBytes(tmp.toPath());
            } catch (Exception ex) {
                // Razítko je přídavek, ne podmínka. Protokol se uloží tak jako tak a uživatel se
                // dozví, že razítko chybí — jinak by přišel i o to, co se povedlo.
                stampFailure = ex;
            } finally {
                Files.deleteIfExists(tmp.toPath());
            }
        }

        Files.write(target.toPath(), pdf);
        return new Outcome(archival, stampFailure);
    }

    /** Ověřované soubory tak, jak se mají v protokolu jmenovat. */
    static List<cz.digitalnikontinuita.anep.core.PdfAttachments.Attachment> attachments(
            File sigFile, File origFile) {
        List<cz.digitalnikontinuita.anep.core.PdfAttachments.Attachment> out = new java.util.ArrayList<>();
        if (sigFile != null) {
            out.add(new cz.digitalnikontinuita.anep.core.PdfAttachments.Attachment(sigFile,
                    origFile != null ? "Soubor s podpisem, jak byl předložen k ověření"
                                     : "Soubor, jak byl předložen k ověření"));
        }
        if (origFile != null) {
            out.add(new cz.digitalnikontinuita.anep.core.PdfAttachments.Attachment(origFile,
                    "Podepsaný obsah k odloučenému podpisu"));
        }
        // Dokumenty, na které se ověřovaný soubor odkazuje otisky z eLegalizace. Přiloží se celý
        // potvrzený řetěz, protože jinak by protokol nesl dokument, který sám o sobě není vším:
        // legalizační záznam bez legalizovaného dokumentu je jen otisk bez toho, co otiskuje.
        File predchozi = sigFile;
        for (File dalsi : confirmedLegalisationChain(sigFile)) {
            out.add(new cz.digitalnikontinuita.anep.core.PdfAttachments.Attachment(dalsi,
                    predchozi == sigFile
                            ? "Dokument, na který se ověřovaný soubor odkazuje otisky"
                              + " z eLegalizace (otisky souhlasí)"
                            : "Dokument, na který se otisky z eLegalizace odkazuje "
                              + predchozi.getName() + " (otisky souhlasí)"));
            predchozi = dalsi;
        }
        return out;
    }

    /**
     * Řetěz dokumentů z eLegalizace, u kterých kontrola otisků <b>souhlasila</b> — v pořadí,
     * v jakém na sebe odkazují.
     *
     * <p>Jen potvrzené a jen souvislý začátek řetězu: jakmile jeden článek nesedí, dál už není
     * o čem mluvit. Dokument za neshodou sice někdo předložil, jenže ten, kdo na něj odkazoval,
     * není tím, na koho odkazoval ověřovaný soubor — vkládat ho do protokolu by tvrdilo vazbu,
     * která nebyla prokázána.
     */
    static List<File> confirmedLegalisationChain(File source) {
        List<File> out = new java.util.ArrayList<>();
        for (var step : cz.digitalnikontinuita.anep.core.LegalisationLinkLog.chain(source)) {
            if (step.check().outcome()
                    != cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Outcome.MATCH) {
                break;
            }
            File candidate = step.candidate();
            if (candidate != null && candidate.isFile()) out.add(candidate);
        }
        return out;
    }

    /** Razítko se nepovedlo, protokol ano — a to je jiná zpráva než neúspěšný export. */
    static void showStampFailure(Stage owner, Exception ex) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.initOwner(owner);
        alert.setTitle("Protokol uložen bez časového razítka");
        alert.setHeaderText("Protokol je uložen, časové razítko se ale přidat nepodařilo.");
        alert.setContentText("Důvod: " + rootCause(ex)
                + "\n\nProtokol je platný i bez razítka; razítko jen dokládá, kdy vznikl."
                + " Zkusit to znovu lze novým vygenerováním protokolu.");
        alert.getDialogPane().setMinWidth(520);
        alert.showAndWait();
    }

    /**
     * Renders XSL-FO into {@code target} as PDF/A-2b, or as ordinary PDF when a font the document
     * needs cannot be embedded.
     *
     * <p>The archive wants an output data format, so PDF/A is what this aims at. It is not
     * something to fail over, though: a protocol nobody can save is worse than one that is merely
     * not archival, and the only realistic cause — a machine missing the faces named in
     * {@link #BASE14_FACES} — is not something the user can fix at the moment they hit the button.
     * So the fallback writes the file and the caller says what it did not manage.
     *
     * <p>Shared with {@link ProtocolView}, which renders the DSS reports through the same factory;
     * they would otherwise reach FOP with no font configuration at all and could never be PDF/A.
     *
     * <p>The document is rendered whole before the file is touched. Writing straight into the
     * target meant that anything going wrong — a locked file, a failure halfway through, a retry
     * in the ordinary-PDF mode — happened to a file the user already had, and the caller then
     * deleted it to tidy up. Exporting over yesterday's export must not be able to destroy it.
     *
     * @return true when the file is PDF/A-2b, false when it had to be written as ordinary PDF
     */
    static boolean renderPdfA(byte[] fo, File target) throws Exception {
        byte[] pdf;
        boolean archival = true;
        try {
            pdf = render(fo, PDFA_MODE);
        } catch (Exception ex) {
            if (!isConformanceFailure(ex)) throw ex;
            System.err.println("PDF/A není dosažitelné, ukládám jako běžné PDF: " + rootCause(ex));
            pdf = render(fo, null);
            archival = false;
        }
        Files.write(target.toPath(), pdf);
        return archival;
    }

    @SuppressWarnings("unchecked")
    private static byte[] render(byte[] fo, String pdfaMode) throws Exception {
        FopFactory fopFactory = getFopFactory();
        FOUserAgent agent = fopFactory.newFOUserAgent();
        if (pdfaMode != null) agent.getRendererOptions().put("pdf-a-mode", pdfaMode);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, agent, out);
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.transform(new StreamSource(new ByteArrayInputStream(fo)),
                    new SAXResult(fop.getDefaultHandler()));
        return out.toByteArray();
    }

    /** True when FOP refused the document for PDF/A reasons rather than failing to render it. */
    private static boolean isConformanceFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof PDFConformanceException) return true;
        }
        return false;
    }

    private static String rootCause(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage();
    }

    /**
     * Reports a failed export: a sentence the reader can act on when the cause is one they can
     * do something about, the stack trace when it is not.
     *
     * <p>The one that actually happens is the file being open in a PDF viewer — Windows locks it
     * and the export cannot replace it. That is not a fault in the application and a stack trace
     * tells the reader nothing they can use, so it gets a plain sentence naming the likely cause
     * and the two ways out. Everything else keeps the trace, because for a real defect the trace
     * is the only thing worth having.
     */
    static void showExportFailure(javafx.stage.Window owner, Throwable ex, File target) {
        if (isFileUnwritable(ex)) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            if (owner != null) alert.initOwner(owner);
            alert.setTitle("PDF nelze uložit");
            alert.setHeaderText("Do souboru „" + target.getName() + "“ nelze zapsat");
            alert.setContentText(
                    "Nejčastěji proto, že je právě otevřený v prohlížeči PDF a ten jej drží"
                    + " zamčený. Zavřete jej a zkuste to znovu, nebo uložte pod jiným názvem."
                    + "\n\nPůvodní soubor zůstal beze změny.");
            alert.showAndWait();
            return;
        }
        String trace = buildTrace(ex);
        System.err.println(trace);
        showError(owner, trace);
    }

    /** True when the export failed because the target could not be written, not because of us. */
    private static boolean isFileUnwritable(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof java.io.FileNotFoundException
                    || t instanceof java.nio.file.FileSystemException) {
                return true;
            }
        }
        return false;
    }

    /** Says the file was written but is not archival, and why that matters. */
    static void showNotPdfA(Stage owner) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        if (owner != null) alert.initOwner(owner);
        alert.setTitle("PDF uloženo, ale ne jako PDF/A");
        alert.setHeaderText("Protokol byl uložen jako běžné PDF");
        alert.setContentText(
                "Nepodařilo se do souboru vložit všechna použitá písma, a bez toho nelze PDF/A"
                + " vytvořit. Obsah protokolu je úplný a soubor je použitelný;"
                + " pro předání do archivu ale PDF/A vyžadují Formátová pravidla Národního archivu."
                + "\n\nObvyklou příčinou je chybějící písmo Arial v systému.");
        alert.showAndWait();
    }

    // -----------------------------------------------------------------------
    // XSL-FO generation
    // -----------------------------------------------------------------------

    private static String formatPdfFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f kB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.2f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    private static String formatPdfFileDate(long ms) {
        return TimeUtil.format(ms);
    }

    private static void appendFileRow(StringBuilder sb, String label, String value) {
        appendFileRow(sb, label, value, false);
    }

    /**
     * @param standalone údaj, který nepatří k předchozí skupině a nemá nad sebou nadpis: dostane
     *                   mezeru navíc a odsazení zleva jako nadpis, aby s ním byl zarovnán a
     *                   nevypadal jako další položka té skupiny nad ním
     */
    private static void appendFileRow(StringBuilder sb, String label, String value,
                                      boolean standalone) {
        String top = standalone ? "7pt" : "1pt";
        String left = standalone ? "0pt" : "6pt";
        sb.append("    <fo:table-row>\n")
          .append("      <fo:table-cell padding=\"" + top + " 4pt 2pt " + left + "\">")
          .append("<fo:block font-weight=\"bold\">").append(esc(label)).append("</fo:block></fo:table-cell>\n")
          .append("      <fo:table-cell padding=\"" + top + " 4pt 2pt 4pt\">")
          .append("<fo:block>").append(esc(value)).append("</fo:block></fo:table-cell>\n")
          .append("    </fo:table-row>\n");
    }

    /**
     * Zelený pruh se znakem aplikace a názvem protokolu.
     *
     * <p>Pruh je společný všem třem protokolům, které aplikace generuje — liší se jen názvem pod
     * jménem aplikace. Kdyby zezelenal jen jeden, byly by z toho dvě různě vypadající hlavičky
     * téhož nástroje.
     */
    private static void appendHeaderFo(StringBuilder fo, String title) {
        String logo = logoUri();
        fo.append("<fo:block background-color=\"").append(LOGO_GREEN).append("\" color=\"")
          .append(LOGO_GREEN_DARK).append("\" padding=\"6pt\">\n")
          .append("<fo:table table-layout=\"fixed\" width=\"100%\">\n")
          .append("  <fo:table-column column-width=\"30pt\"/>\n")
          .append("  <fo:table-column column-width=\"proportional-column-width(1)\"/>\n")
          .append("  <fo:table-body>\n    <fo:table-row>\n")
          .append("      <fo:table-cell display-align=\"center\"><fo:block>");
        if (logo != null) {
            fo.append("<fo:external-graphic src=\"url('").append(logo)
              .append("')\" content-height=\"24pt\" content-width=\"24pt\"/>");
        }
        fo.append("</fo:block></fo:table-cell>\n")
          .append("      <fo:table-cell display-align=\"center\">\n")
          .append("        <fo:block font-weight=\"bold\" font-size=\"10pt\">")
          .append("AnEP (Analyzátor elektronických podpisů)</fo:block>\n")
          .append("        <fo:block font-size=\"8.5pt\">").append(esc(title))
          .append("</fo:block>\n")
          .append("      </fo:table-cell>\n    </fo:table-row>\n")
          .append("  </fo:table-body>\n</fo:table>\n</fo:block>\n");
    }

    /**
     * Světle zelený panel pod pruhem: co bylo zadáno a kdy se ověřovalo.
     *
     * <p>Čas je okamžik, kdy ověřování proběhlo, ne kdy vznikl protokol. Ty dva se můžou lišit
     * o hodiny, když uživatel mezitím jen listuje záložkami, a protokol vypovídá o ověření.
     */
    private static void appendInputsFo(StringBuilder fo, File sigFile, File origFile,
                                       java.time.Instant verifiedAt) {
        fo.append("<fo:block padding=\"5pt 0 7pt 0\" border-bottom=\"").append(RULE)
          .append("\" margin-bottom=\"7pt\">\n")
          .append("<fo:table table-layout=\"fixed\" width=\"100%\">\n")
          .append("  <fo:table-column column-width=\"145pt\"/>\n")
          .append("  <fo:table-column column-width=\"proportional-column-width(1)\"/>\n")
          .append("  <fo:table-body>\n");

        if (sigFile != null) {
            appendPanelHeading(fo, "Ověřovaný dokument", true);
            appendFileRow(fo, "Název souboru:", sigFile.getName());
            appendFileRow(fo, "Velikost:", formatPdfFileSize(sigFile.length()));
            appendFileRow(fo, "Datum poslední změny:", formatPdfFileDate(sigFile.lastModified()));
        }
        if (origFile != null) {
            appendPanelHeading(fo, "Doplňující soubor", false);
            appendFileRow(fo, "Název souboru:", origFile.getName());
            appendFileRow(fo, "Velikost:", formatPdfFileSize(origFile.length()));
            appendFileRow(fo, "Datum poslední změny:", formatPdfFileDate(origFile.lastModified()));
        }
        if (verifiedAt != null) {
            // Vlastní položka, ne nadpis a pod ním řádek: nadpis nad jediným údajem říká
            // dvakrát totéž a v panelu se třemi řádky nad sebou z toho byl schod navíc.
            // Čas má týž tvar jako data souborů výš — s UTC i místním časem; kdyby se
            // lišily, četlo by se to jako dva různé druhy údaje, ačkoli jde o tutéž věc.
            appendFileRow(fo, "Ověřování proběhlo:",
                    formatPdfFileDate(verifiedAt.toEpochMilli()), true);
        }
        fo.append("  </fo:table-body>\n</fo:table>\n</fo:block>\n");
    }

    private static void appendPanelHeading(StringBuilder fo, String text, boolean first) {
        fo.append("    <fo:table-row><fo:table-cell number-columns-spanned=\"2\"")
          .append(first ? " padding-bottom=\"2pt\">" : " padding-top=\"5pt\" padding-bottom=\"2pt\">")
          .append("<fo:block font-weight=\"bold\">")
          .append(esc(text))
          .append("</fo:block></fo:table-cell></fo:table-row>\n");
    }

    /**
     * Závěrečný panel: nastavení, bez kterého by výsledek nevyšel tak, jak vyšel.
     *
     * <p>Vypisují se jen rozhodující ústupky, ne všechno zapnuté. Co se na výsledku neprojevilo,
     * o něm nic nevypovídá, a protokol o ověření nemá být soupisem zaškrtávátek.
     */
    private static void appendSettingsFo(StringBuilder fo, List<String> decisiveSettings) {
        if (decisiveSettings == null || decisiveSettings.isEmpty()) return;
        fo.append("<fo:block padding=\"7pt 0 0 0\" border-top=\"").append(RULE)
          .append("\" margin-top=\"9pt\">\n")
          .append("<fo:block font-weight=\"bold\" margin-bottom=\"3pt\">")
          .append("Nastavení, které ovlivnilo výsledek</fo:block>\n");
        for (String name : decisiveSettings) {
            fo.append("<fo:block margin-left=\"10pt\" text-indent=\"-8pt\">")
              .append(esc("• " + name)).append("</fo:block>\n");
        }
        // Věta až za výčtem: nejdřív ať je vidět co, teprve pak co z toho plyne.
        fo.append("<fo:block margin-top=\"3pt\">")
          .append(esc("Bez nich by výsledek vyšel jinak."))
          .append("</fo:block>\n")
          .append("</fo:block>\n");
    }

    /**
     * Odkaz v patičce. Černý s podtržením, ne modrý: barvu v protokolu nese jen záhlaví a řádek
     * se stavem platnosti, takže by modrý odkaz tvrdil důležitost, kterou nemá.
     */
    private static String link(String text, String url) {
        return "<fo:basic-link external-destination=\"url('" + url + "')\""
                + " text-decoration=\"underline\">" + esc(text) + "</fo:basic-link>";
    }

    /**
     * Patička opakovaná na každé straně. Výhrada k záruce patří na každý list, ne jen na první:
     * z protokolu se tiskne i jediná strana a ta pak putuje dál sama.
     */
    private static String footerFo() {
        return "    <fo:static-content flow-name=\"xsl-region-after\">\n"
             + "      <fo:block text-align=\"center\" font-size=\"8.5pt\""
             + " border-top=\"" + RULE + "\" padding-top=\"4pt\">"
             + esc("Výsledek ověření v aplikaci AnEP i tento protokol jsou poskytovány"
                     + " bez jakékoli záruky.")
             + "</fo:block>\n"
             // Celá patička je v jednom stupni, a je to největší, jaký projde: nejdelší věta má
             // 128 znaků, sazba je 18 cm čili asi 510 bodů, a v Helvetice se ta věta vejde na
             // jednu řádku do 8,57 bodu. Osm bodů nechává rezervu na to, že skutečné písmo není
             // Helvetica na chlup; 8,5 by se vešlo taky, ale bez místa na omyl.
             + "      <fo:block text-align=\"center\" font-size=\"8pt\" padding-top=\"3pt\">"
             + esc("Analyzátor elektronických podpisů (AnEP) slouží výhradně potřebám"
                     + " výuky a seznamování se s problematikou elektronických podpisů.")
             + "</fo:block>\n"
             + "      <fo:block text-align=\"center\" font-size=\"8pt\">"
             + esc("Autorem aplikace AnEP je ")
             + link("Jiří Peterka", "mailto:jiri@peterka.cz")
             + esc("; při jejím vývoji byla využita ")
             + link("knihovna DSS",
                     "https://ec.europa.eu/digital-building-blocks/DSS/webapp-demo/")
             + esc(" a nástroj ")
             + link("Claude Code", "https://claude.com/product/claude-code")
             + "</fo:block>\n"
             + "      <fo:block text-align=\"center\" font-size=\"8pt\">"
             + link("https://bajecnysvet.cz/anep", "https://bajecnysvet.cz/anep")
             + esc("  ·  ")
             + link("https://digitalnikontinuita.cz", "https://digitalnikontinuita.cz")
             + "</fo:block>\n"
             + "    </fo:static-content>\n";
    }

    /** Package-private so {@code PrintExportTest} can read what the scene graph turned into. */
    static String buildFo(Node contentNode, File sigFile, File origFile, String title,
                          java.time.Instant verifiedAt, List<String> decisiveSettings) {
        plainBlocks = contentNode != null
                && Boolean.TRUE.equals(contentNode.getProperties().get(PLAIN_BLOCKS));
        StringBuilder body = new StringBuilder();
        appendHeaderFo(body, title);
        if (sigFile != null || verifiedAt != null) {
            appendInputsFo(body, sigFile, origFile, verifiedAt);
        }
        renderNode(contentNode, body);
        appendSettingsFo(body, decisiveSettings);

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
             + "<fo:root xmlns:fo=\"http://www.w3.org/1999/XSL/Format\">\n"
             + "  <fo:layout-master-set>\n"
             + "    <fo:simple-page-master master-name=\"A4\"\n"
             + "        page-height=\"29.7cm\" page-width=\"21cm\"\n"
             + "        margin-top=\"1.5cm\" margin-bottom=\"1.5cm\"\n"
             + "        margin-left=\"1.5cm\" margin-right=\"1.5cm\">\n"
             + "      <fo:region-body margin-bottom=\"2.3cm\"/>\n"
             + "      <fo:region-after extent=\"2.1cm\"/>\n"
             + "    </fo:simple-page-master>\n"
             + "  </fo:layout-master-set>\n"
             + "  <fo:page-sequence master-reference=\"A4\">\n"
             + footerFo()
             + "    <fo:flow flow-name=\"xsl-region-body\""
             + " font-family=\"Arial,Helvetica,sans-serif\" font-size=\"8.5pt\">\n"
             + body
             + "    </fo:flow>\n"
             + "  </fo:page-sequence>\n"
             + "</fo:root>\n";
    }

    private static void renderNode(Node node, StringBuilder fo) {
        if (node == null) return;
        if      (node instanceof javafx.scene.image.ImageView image) renderImage(image, fo);
        else if (node instanceof VBox vbox)  renderVBox(vbox, fo);
        else if (node instanceof HBox hbox)  renderHBox(hbox, fo);
        else if (node instanceof Separator)
            fo.append("<fo:block border-bottom=\"" + RULE + "\""
                    + " margin-top=\"5pt\" margin-bottom=\"5pt\"/>\n");
        // TextFlow je take Pane, ale jeho deti jsou vlozene useky jedne vety a extractText je
        // slepuje bez mezer; musi ven driv, nez se na nej dostane obecna vetev nize.
        else if (node instanceof TextFlow) {
            String t = extractText(node);
            if (!t.isBlank())
                fo.append("<fo:block>").append(esc(t)).append("</fo:block>\n");
        }
        // Kazdy jiny kontejner: sestoupit do nej. Bez teto vetve koncil StackPane -- a s nim
        // vizualizace podpisu, kterou nese -- v extractText, ktere z obrazku vytahne prazdno.
        // Protokol pak mel popisek "Vizualizace podpisu:" a pod nim nic; opraveno 30. 8. 2026.
        else if (node instanceof javafx.scene.layout.Pane pane) {
            renderChildren(pane.getChildren(), fo);
        }
        else {
            String t = extractText(node);
            if (!t.isBlank())
                fo.append("<fo:block>").append(esc(t)).append("</fo:block>\n");
        }
    }

    /**
     * Klíč, pod kterým si {@code ImageView} nese původní bajty obrázku.
     *
     * <p>Bez nich by se obrázek do protokolu nedostal: {@code extractText} vrátí u obrázku
     * prázdno, takže se dosud vysázel jen popisek „Vizualizace podpisu:“ a pod ním nic.
     * Získat je zpět z {@code Image} by znamenalo překódovat snímek přes {@code SwingFXUtils},
     * a to je modul {@code javafx.swing}, který zabalené běhové prostředí nemá.
     */
    static final String PNG_BYTES = "anep.png.bytes";

    /**
     * Vlastnost kořenového uzlu: sázej bloky bez nadpisů, oddělené jedinou čarou.
     *
     * <p>Volba protokolu, ne uzlu — proto ji nese ten uzel, který se {@link #buildFo} předává,
     * a ne každý blok zvlášť. Analýza své nadpisy potřebuje (jsou to otázky, na které blok
     * odpovídá), Rozšířený přehled ne (jsou to jména skupin, jejichž řádky se pojmenují samy).
     */
    static final String PLAIN_BLOCKS = "anep.print.plainBlocks";

    /**
     * Značka na uzlu bloku: nadpis se má vysázet i v protokolu, který jinak nadpisy bloků
     * vynechává. Nese ji {@code VBox} celého bloku, tedy rodič záhlaví.
     */
    static final String KEEP_TITLE = "anep.print.keepTitle";

    /** Vyžádal si tenhle blok nadpis i tam, kde se jinak nesází? */
    private static boolean keepsTitle(HBox header) {
        javafx.scene.Parent block = header.getParent();
        return block != null && Boolean.TRUE.equals(block.getProperties().get(KEEP_TITLE));
    }

    /**
     * Platí pro právě sázený protokol. Statické proto, že sazba prochází scénu deseti metodami
     * a protahovat příznak všemi by zaneslo podpisy kvůli jedinému místu, které ho čte;
     * {@link #buildFo} běží jen na vlákně JavaFX, takže se dva protokoly nepotkají.
     */
    private static boolean plainBlocks;

    /**
     * Obrázek do protokolu — přes dočasný soubor, protože FOP chce cestu, ne proud. Táž cesta,
     * jakou už chodí logo v záhlaví.
     *
     * <p>Selhání se nehlásí a obrázek se mlčky vynechá: protokol bez vizualizace je pořád
     * protokol, kdežto protokol, který se kvůli obrázku nevytvoří, není k ničemu.
     *
     * <p>Šířka je omezená na 340 bodů, tedy zhruba na polovinu sázecí šířky A4, a výška se
     * dopočítá z poměru stran snímku. Vizualizace podpisu bývá široký nízký obdélník a pevná
     * výška by ho roztáhla.
     */
    private static void renderImage(javafx.scene.image.ImageView view, StringBuilder fo) {
        Object bytes = view.getProperties().get(PNG_BYTES);
        if (!(bytes instanceof byte[] png) || png.length == 0) return;
        try {
            java.io.File tmp = java.io.File.createTempFile("anep-img", ".png");
            tmp.deleteOnExit();
            Files.write(tmp.toPath(), png);

            double w = view.getImage() != null ? view.getImage().getWidth() : 0;
            double h = view.getImage() != null ? view.getImage().getHeight() : 0;
            double widthPt = w > 0 ? Math.min(w * 0.75, 340) : 340;
            String height = w > 0 && h > 0
                    ? String.format(Locale.ROOT, "%.1fpt", widthPt * h / w) : "scale-to-fit";

            // Rámeček s paspartou, vystředěný v kartě podpisu. Vizualizace mívá bílé pozadí a na
            // bílém listu protokolu by nebylo poznat, kde začíná a kde končí — proto šedá plocha
            // kolem a tenká linka těsně u obrázku. Rozměry jsou odečtené z vysázené stránky, ne
            // odhadnuté: s paspartou 5 bodů a šedou #f2f2f2 nebyla plocha kolem obrázku na tisku
            // znát a rámeček vypadal jako pouhá linka.
            //
            // Vystředí se tabulkou o třech sloupcích, protože jinak to nejde: rámeček má obepnout
            // obrázek, ne celou kartu, a takový blok se v XSL-FO na střed nepostaví — pružné
            // krajní sloupce jsou to, co ho tam odsune.
            fo.append("<fo:table table-layout=\"fixed\" width=\"100%\"")
              .append(" space-before=\"4pt\" space-after=\"7pt\">\n")
              .append("  <fo:table-column column-width=\"proportional-column-width(1)\"/>\n")
              .append("  <fo:table-column column-width=\"")
              .append(String.format(Locale.ROOT, "%.1fpt", widthPt + 24))
              .append("\"/>\n")
              .append("  <fo:table-column column-width=\"proportional-column-width(1)\"/>\n")
              .append("  <fo:table-body>\n    <fo:table-row>\n")
              .append("      <fo:table-cell><fo:block/></fo:table-cell>\n")
              .append("      <fo:table-cell background-color=\"#e6e6e6\"")
              .append(" border=\"0.7pt solid #6f6f6f\" padding=\"9pt\">\n")
              .append("        <fo:block background-color=\"#ffffff\"")
              .append(" border=\"0.4pt solid #b0b0b0\" padding=\"2pt\">")
              .append("<fo:external-graphic src=\"url('").append(tmp.toURI())
              .append("')\" content-width=\"")
              .append(String.format(Locale.ROOT, "%.1fpt", widthPt))
              .append("\" content-height=\"").append(height)
              .append("\" scaling=\"uniform\"/></fo:block>\n")
              .append("      </fo:table-cell>\n")
              .append("      <fo:table-cell><fo:block/></fo:table-cell>\n")
              .append("    </fo:table-row>\n  </fo:table-body>\n</fo:table>\n");
        } catch (Exception e) {
            // mlčky bez obrázku
        }
    }

    private static void renderVBox(VBox vbox, StringBuilder fo) {
        String style = styleOf(vbox);
        boolean isCard = style.contains("border-color") || style.contains("border-width");

        if (isCard)
            fo.append("<fo:block border=\"0.5pt solid #cccccc\" padding=\"7pt\""
                    + " margin-bottom=\"9pt\" keep-together.within-page=\"auto\">\n");

        renderChildren(vbox.getChildren(), fo);

        if (isCard) fo.append("</fo:block>\n");
    }

    /**
     * Iterates {@code children} and groups consecutive data rows into a single
     * two-column FO table so label and value columns align across all rows.
     */
    private static void renderChildren(List<Node> children, StringBuilder fo) {
        boolean inTable = false;

        for (Node child : children) {
            // Řádek se stavem platnosti vypadá jako datový (tučný popisek s minWidth, za ním
            // hodnota), takže by skončil v zarovnané tabulce a o barvu by přišel. Musí ven dřív,
            // než se na něj isDataRow podívá — barva výsledku je jediná barva, kterou obsah nese.
            if (child instanceof HBox hbox && isDataRow(hbox)
                    && verdictColour(styleOf(hbox)) == null) {
                if (!inTable) {
                    fo.append("<fo:table table-layout=\"fixed\" width=\"100%\""
                            + " space-before=\"1pt\" space-after=\"1pt\">\n")
                      .append("  <fo:table-column column-width=\"145pt\"/>\n")
                      .append("  <fo:table-column column-width=\"proportional-column-width(1)\"/>\n")
                      .append("  <fo:table-body>\n");
                    inTable = true;
                }
                renderDataRow(hbox, fo);
            } else {
                if (inTable) {
                    fo.append("  </fo:table-body>\n</fo:table>\n");
                    inTable = false;
                }
                renderNode(child, fo);
            }
        }

        if (inTable) fo.append("  </fo:table-body>\n</fo:table>\n");
    }

    /**
     * The title of a collapsible block's header row, or null when this HBox is not one.
     *
     * <p>Recognised by shape rather than by style: {@code AdvisorView.collapsibleBlock} paints its
     * header transparent and repaints it on hover, so there is no colour to key on — but the two
     * children, the arrow followed by the title, are what it always builds.
     *
     * <p>Šipka je od 9. 9. 2026 kreslený trojúhelník ({@code StackPane} s jediným {@code Polygon}),
     * ne znak ▾/▸ — velikost znaku nešla řídit. Jinou dvojici „{@code StackPane}
     * s trojúhelníkem plus {@code Label}“ karty nestaví, takže to pořád poznává
     * jen záhlaví bloku.
     */
    private static String collapsibleTitle(HBox hbox) {
        // Za nadpisem mohou od 15. 10. 2026 stát akce bloku (Ukaž/Ulož u certifikátu); do
        // protokolu nepatří, záhlaví se proto pozná podle prvních dvou dětí a zbytek se zahodí.
        List<Node> ch = hbox.getChildren();
        if (ch.size() < 2 || !(ch.get(1) instanceof Label title)) return null;
        return isBlockArrow(ch.get(0)) ? nvl(title.getText()) : null;
    }

    /** Kreslená šipka rozbalovacího bloku: {@code StackPane} s jediným trojúhelníkem uvnitř. */
    private static boolean isBlockArrow(Node node) {
        return node instanceof javafx.scene.layout.StackPane sp
                && sp.getChildren().size() == 1
                && sp.getChildren().get(0) instanceof javafx.scene.shape.Polygon;
    }

    /**
     * A data row is an HBox whose first child is a bold Label with
     * an explicitly set minWidth ≥ 80 (characteristic of all label-value rows
     * across ResultView, ExtendedView, and DetailedReportView).
     */
    private static boolean isDataRow(HBox hbox) {
        List<Node> ch = hbox.getChildren();
        if (ch.isEmpty() || !(ch.get(0) instanceof Label lbl)) return false;
        return lbl.getMinWidth() >= 80 && styleOf(lbl).contains("bold");
    }

    private static void renderDataRow(HBox hbox, StringBuilder fo) {
        List<Node> children = hbox.getChildren();
        String labelText = nvl(((Label) children.get(0)).getText());

        // Vysvětlení od ikony ⓘ nepatří do hodnoty: je to výklad pojmu, ne jeho druhá polovina.
        // Vytáhne se stranou a přijde pod hodnotu vlastní řádkou v menším stupni, aby bylo vidět,
        // co je údaj a co poznámka k němu.
        String note = infoNote(hbox);
        StringBuilder val = new StringBuilder();
        for (int i = 1; i < children.size(); i++) {
            String t = extractText(children.get(i));
            if (!note.isEmpty() && t.endsWith(note)) t = t.substring(0, t.length() - note.length());
            if (!t.isBlank()) {
                if (val.length() > 0) val.append("  ");
                val.append(t);
            }
        }

        fo.append("    <fo:table-row>\n")
          .append("      <fo:table-cell padding=\"1pt 4pt 1pt 0\">")
          .append("<fo:block font-weight=\"bold\">").append(esc(labelText))
          .append("</fo:block></fo:table-cell>\n")
          .append("      <fo:table-cell padding=\"1pt 0 1pt 4pt\">")
          .append("<fo:block>").append(esc(val.toString()))
          .append("</fo:block>\n");
        if (!note.isEmpty()) {
            fo.append("<fo:block font-size=\"7.5pt\" space-before=\"1pt\">")
              .append(esc(infoNoteLine(note))).append("</fo:block>\n");
        }
        fo.append("</fo:table-cell>\n")
          .append("    </fo:table-row>\n");
    }

    /**
     * Vysvětlení jako vlastní řádka protokolu: bez závorek a uvozené „tj.: “.
     *
     * <p>Závorky drží {@code infoIconText}, protože podle nich se výklad pozná a odřízne z
     * hodnoty řádku. Na tištěné řádce ale nemají co dělat — stojí sama a nic neobjímá.
     */
    private static String infoNoteLine(String note) {
        String t = note.trim();
        if (t.startsWith("(") && t.endsWith(")")) t = t.substring(1, t.length() - 1).trim();
        return t.isEmpty() ? "" : "tj.: " + t;
    }

    /** Text bubliny od ikony ⓘ kdekoli v řádku, i s obalujícími závorkami; jinak prázdný. */
    private static String infoNote(Node node) {
        if (node instanceof Labeled labeled) {
            String info = infoIconText(labeled);
            if (info != null) return info;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                String found = infoNote(child);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    /**
     * Barva řádku se stavem platnosti, nebo {@code null}, když o takový řádek nejde.
     *
     * <p>Tři hodnoty pocházejí z {@code ResultView}: zelená pro platný, červená pro neplatný,
     * okrová pro neznámý. Čtou se ze stylu, ne z textu — text se ohýbá podle rodu prvku
     * (platný / platná / platné) a podle toho, jestli je za ním v závorce důvod.
     */
    private static String verdictColour(String style) {
        if (style.contains("#1a7a1a")) return "#1a7a1a";
        if (style.contains("#cc0000")) return "#cc0000";
        if (style.contains("#9a6700")) return "#9a6700";
        return null;
    }

    /** HBoxes that are NOT data rows: card headers, section headers, or plain text lines. */
    private static void renderHBox(HBox hbox, StringBuilder fo) {
        String style = styleOf(hbox);

        // Nadpis rozbalovacího bloku. Šipka před ním je ovládací prvek, ne obsah — v tištěném
        // protokolu nemá co dělat. Zůstane samotný nadpis, vysazený jako nadpis oddílu,
        // protože přesně tím v kartě je.
        String blockTitle = collapsibleTitle(hbox);
        if (blockTitle != null) {
            // Protokol z Rozšířeného přehledu si nadpisy bloků nepřeje: řádky uvnitř mají vlastní
            // popisky, takže nadpis říká podruhé totéž, a spolu s čarou nad sebou dělal ze dvou
            // oddělovačů dva — čára od Separatoru a čára od nadpisu. Bez nadpisu zbude jedna.
            //
            // Výjimku si blok může vyžádat sám (KEEP_TITLE). Potřebuje ji ten, jehož řádky
            // nemluví o právě posuzovaném prvku, nýbrž o něčem vedle něj — „Odkazované dokumenty
            // z eLegalizace“ mluví o jiném souboru a bez nadpisu by jeho řádky v protokolu
            // splynuly s údaji o podpisu.
            boolean keep = keepsTitle(hbox);
            if (plainBlocks && !keep) return;
            // Vyžádaný nadpis dostane šedý podklad — týž, jaký má otevřený blok na kartě
            // (#e4e4e4 z AdvisorView.BlockTint.NEUTRAL). V protokolu, kde ostatní bloky nadpis
            // nemají, by jinak splynul s řádky kolem sebe.
            fo.append("<fo:block border-top=\"" + RULE + "\""
                    + (keep ? " background-color=\"#e4e4e4\" padding-left=\"3pt\""
                            + " padding-right=\"3pt\" padding-bottom=\"2pt\"" : "")
                    + " padding-top=\"4pt\" font-weight=\"bold\""
                    + " margin-top=\"5pt\" margin-bottom=\"2pt\">")
              .append(esc(blockTitle)).append("</fo:block>\n");
            return;
        }

        // Collect all text from direct children (Buttons become [text])
        String text = hbox.getChildren().stream()
                .map(PrintUtil::extractText)
                .filter(t -> !t.isBlank())
                .collect(Collectors.joining("  "));

        if (text.isBlank()) {
            // Might contain nested VBoxes — recurse
            for (Node child : hbox.getChildren()) renderNode(child, fo);
            return;
        }

        String verdict = verdictColour(style);
        if (verdict != null) {
            // Jediný barevný řádek uvnitř obsahu: stav platnosti, v barvě výsledku.
            fo.append("<fo:block background-color=\"" + verdict + "\" color=\"#ffffff\""
                    + " padding=\"5pt\" font-weight=\"bold\" font-size=\"9.5pt\""
                    + " margin-top=\"3pt\" margin-bottom=\"4pt\">")
              .append(esc(text)).append("</fo:block>\n");
        } else if (style.contains("003189")) {
            // Nadpis karty — dřív modrá plocha, teď černá čára nad ním
            fo.append("<fo:block border-top=\"" + RULE + "\""
                    + " padding-top=\"5pt\" font-weight=\"bold\" font-size=\"9.5pt\""
                    + " margin-top=\"6pt\" margin-bottom=\"4pt\">")
              .append(esc(text)).append("</fo:block>\n");
        } else if (style.contains("e8edf7")) {
            // Nadpis oddílu — dřív světle modrá plocha
            fo.append("<fo:block border-top=\"" + RULE + "\""
                    + " padding-top=\"4pt\" font-weight=\"bold\""
                    + " margin-top=\"5pt\" margin-bottom=\"2pt\">")
              .append(esc(text)).append("</fo:block>\n");
        } else {
            fo.append("<fo:block>").append(esc(text)).append("</fo:block>\n");
        }
    }

    // -----------------------------------------------------------------------
    // Text extraction (scene graph → plain string)
    // -----------------------------------------------------------------------

    /**
     * Text of one node, whatever kind of node it is.
     *
     * <p>Matched by base class on purpose. Listing the concrete controls is what silently emptied
     * this export once: when the views moved their value fields to {@code TextArea} to make them
     * selectable, every value disappeared from the PDF and the labels went on printing beside
     * nothing. {@code Labeled} covers Label, Button, Hyperlink and CheckBox alike —
     * {@code Hyperlink} is not a {@code Button} but its sibling, so the certificate and CRL links
     * were lost the same way — and {@code TextInputControl} covers TextField together with
     * TextArea.
     *
     * <p>Anything else with children is descended into rather than skipped, so a container this
     * class has never heard of costs a little formatting instead of its whole contents.
     */
    /**
     * Znak ⓘ se v protokolu nahradí tím, co bublina za ním říká, v závorce.
     *
     * <p>Na obrazovce je to ovládací prvek: čtenář na něj najede a dozví se, co druh podpisu
     * znamená. V tištěném protokolu najet nemá kam, a znak sám navíc není v zabudovaném písmu,
     * takže z něj vyjde prázdný obdélník nebo mřížka. Vysvětlení je přitom to jediné, oč tam jde.
     *
     * <p>Bublina se čte z vlastností uzlu, ne přes {@code getTooltip()}: instaluje se přes
     * {@code Tooltip.install(Node, Tooltip)}, což vlastnost ovládacího prvku nenastavuje.
     */
    private static String infoIconText(Labeled labeled) {
        if (!"\u24d8".equals(nvl(labeled.getText()).trim())) return null;
        for (Object v : labeled.getProperties().values()) {
            if (v instanceof javafx.scene.control.Tooltip tip) {
                String text = nvl(tip.getText()).trim();
                return text.isEmpty() ? "" : " (" + text + ")";
            }
        }
        return "";
    }

    private static String extractText(Node node) {
        if (node == null) return "";
        if (node instanceof Labeled labeled) {
            String info = infoIconText(labeled);
            return info != null ? info : nvl(labeled.getText());
        }
        if (node instanceof TextInputControl input)  return nvl(input.getText());
        if (node instanceof Text t)                  return nvl(t.getText());
        // TextFlow holds inline runs, so its parts join without a separator; everything else is
        // separate items and gets one.
        if (node instanceof TextFlow flow)
            return flow.getChildren().stream()
                    .map(PrintUtil::extractText)
                    .collect(Collectors.joining());
        if (node instanceof Parent parent)
            return parent.getChildrenUnmodifiable().stream()
                    .map(PrintUtil::extractText)
                    .filter(t -> !t.isBlank())
                    .collect(Collectors.joining("  "));
        return "";
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static String styleOf(Node n) {
        String s = n.getStyle();
        return s != null ? s : "";
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    private static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String buildTrace(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable t = ex;
        while (t != null) {
            sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
            StackTraceElement[] frames = t.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 8); i++)
                sb.append("  at ").append(frames[i]).append("\n");
            t = t.getCause();
            if (t != null) sb.append("Caused by: ");
        }
        return sb.toString();
    }

    private static void showError(javafx.stage.Window owner, String trace) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        if (owner != null) alert.initOwner(owner);
        alert.setTitle("Chyba při generování PDF");
        alert.setHeaderText("PDF nelze vygenerovat");
        alert.getDialogPane().setPrefWidth(700);
        TextArea ta = new TextArea(trace);
        ta.setEditable(false);
        ta.setWrapText(false);
        ta.setPrefRowCount(15);
        alert.getDialogPane().setContent(ta);
        alert.showAndWait();
    }
}
