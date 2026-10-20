package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.stage.Stage;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Záznam o úkonu nad dvojicí souborů „před úkonem“ a „po něm“ — bez razítkovací služby.
 *
 * <p>Úkon samotný potřebuje síť a dialog pro uložení, takže se tu přeskakuje: za výsledný soubor
 * poslouží soubor už jednou uchovaný (třeba službou SecuSign). Všechno ostatní jde touž cestou
 * jako v aplikaci — {@code AdvisorView.show} nad každým z obou souborů, {@code beginRecord}
 * a {@code fillAfter} — takže se měří párování prvků a srovnání s předpovědí na skutečných
 * dokumentech, ne na stavech sestavených v testu.
 *
 * <p>Okno záznamu se otevře a zavře skutečným stiskem tlačítka; vysázený protokol jde do
 * zadaného souboru a jeho první strana do PNG vedle něj.
 *
 * <pre>java ... ActionRecordProbe &lt;před&gt; &lt;po&gt; [výstup.pdf]</pre>
 */
public final class ActionRecordProbe extends Application {

    @Override
    public void start(Stage stage) {
        List<String> args = getParameters().getRaw();
        new Thread(() -> {
            try {
                run(new File(args.get(0)), new File(args.get(1)),
                        args.size() > 2 ? new File(args.get(2)) : null);
            } catch (Throwable e) {
                e.printStackTrace(System.out);
            }
            Platform.exit();
        }, "action-record-probe").start();
    }

    private static void run(File before, File after, File pdf) throws Exception {
        AppSettings settings = AppSettings.load();
        CommonCertificateVerifier verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        ValidationResult rb = validate(before, settings, verifier);
        ValidationResult ra = validate(after, settings, verifier);

        ActionRecord record = onFx(() -> {
            AdvisorView advisor = new AdvisorView();
            ContinuityView continuity = new ContinuityView();
            advisor.setAppSettings(settings);
            // Služby jako v aplikaci: bez nich zůstanou brány úkonů zavřené a záznam hlásí u všech
            // prvků „nedosáhne“ (17. 10. 2026, záznam o prodloužení razítek).
            var ref = new java.util.concurrent.atomic.AtomicReference<>(verifier);
            advisor.setFixationService(new cz.digitalnikontinuita.anep.core.FixationService(ref));
            advisor.setRestampingService(new cz.digitalnikontinuita.anep.core.RestampingService());
            advisor.setAugmentationService(new cz.digitalnikontinuita.anep.core.AugmentationService(ref));
            advisor.setExtensionService(new cz.digitalnikontinuita.anep.core.SignatureExtensionService(ref));
            advisor.setTimestampingService(new cz.digitalnikontinuita.anep.core.TimestampingService(ref));
            continuity.setActionRow(advisor.getActionRow());
            advisor.setContinuityView(continuity);

            advisor.setSourceFile(before, null, null);
            advisor.show(rb);
            // -Danep.mode=PROLONG|RESTAMP: záznam jiného úkonu nad tímtéž párem (17. 10. 2026,
            // kontrola textů záznamu u prodloužení).
            ActionRecord r = advisor.beginRecord(AdvisorView.ContinuityMode.valueOf(
                    System.getProperty("anep.mode", "PRESERVE")), settings.getSelectedTsa());
            advisor.setSourceFile(after, null, null);
            advisor.show(ra);
            advisor.fillAfter(r);
            return r;
        });
        record.input = ActionRecord.FileFact.of(before);
        record.output = ActionRecord.FileFact.of(after);
        record.performedAt = Instant.now();
        // Certifikát nového razítka: přibylý prvek-razítko s nejvzdálenějším koncem platnosti.
        // Datum do jména je pak pozdější z něj a z dosavadní ověřitelnosti dokumentu — týž výpočet
        // jako AdvisorView.promisedUntil, který tu nejde zavolat, protože dokument „před“ už
        // v pohledu nestojí.
        Instant tsaEnd = record.pairs().stream()
                .filter(p -> p.before() == null && p.after().certNotAfter() != null)
                .map(p -> p.after().certNotAfter()).max(Instant::compareTo).orElse(null);
        record.tsaCertNotAfter = tsaEnd != null ? java.util.Date.from(tsaEnd) : null;
        Instant promised = tsaEnd;
        if (record.beforeDocUntil != null && (promised == null
                || record.beforeDocUntil.isAfter(promised))) {
            promised = record.beforeDocUntil;
        }
        record.promisedUntil = promised != null ? java.util.Date.from(promised) : null;

        System.out.println("před: " + before.getName());
        System.out.println("po:   " + after.getName());
        for (ActionRecord.Pair p : record.pairs()) {
            System.out.println("  #" + p.number() + "  "
                    + (p.before() == null ? "(nový)" : p.before().verdict() + " "
                        + ActionRecord.anepWords(p.before().anep()) + " dosáhne=" + p.before().reached())
                    + "  →  "
                    + (p.after() == null ? "(nenalezen)" : p.after().verdict() + " "
                        + ActionRecord.anepWords(p.after().anep())));
        }
        System.out.println("  dokument: " + ActionRecord.day(record.beforeDocUntil) + " → "
                + ActionRecord.day(record.afterDocUntil) + ", ve jménu "
                + ActionRecord.day(promised));
        List<String> issues = record.discrepancies();
        System.out.println("  rozchody: " + (issues.isEmpty() ? "žádné" : ""));
        for (String s : issues) System.out.println("    • " + s);

        // Okno: otevřít, najít „Zavřít“ a stisknout ho.
        String closed = onFx(() -> {
            Stage w = ActionRecordWindow.show(null, record, null);
            Button close = find(w.getScene().getRoot(), "Zavřít");
            Button save = find(w.getScene().getRoot(), "Uložit záznam o úkonu…");
            if (close == null || save == null) return "tlačítka nenalezena";
            if (pdf != null) {
                javafx.scene.image.WritableImage shot = w.getScene().snapshot(null);
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                        (int) shot.getWidth(), (int) shot.getHeight(),
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < img.getHeight(); y++)
                    for (int x = 0; x < img.getWidth(); x++)
                        img.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
                javax.imageio.ImageIO.write(img, "png",
                        new File(pdf.getPath().replaceAll("\\.pdf$", "") + "-okno.png"));
            }
            close.fire();
            return w.isShowing() ? "okno zůstalo otevřené" : "okno se zavřelo";
        });
        System.out.println("  okno: " + closed);

        if (pdf != null) {
            String fo = onFx(() -> ActionRecordWindow.foOf(record));
            PrintUtil.Outcome outcome = ActionRecordWindow.write(fo, pdf, false, null);
            System.out.println("  protokol: " + pdf + (outcome.archival() ? " (PDF/A)" : " (běžné PDF)"));
            try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
                var image = new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImageWithDPI(0, 80);
                File png = new File(pdf.getPath().replaceAll("\\.pdf$", "") + ".png");
                javax.imageio.ImageIO.write(image, "png", png);
                System.out.println("  strana 1: " + png + " (stran " + doc.getNumberOfPages() + ")");
            }
        }
    }

    private static ValidationResult validate(File f, AppSettings settings,
                                             CommonCertificateVerifier verifier) throws Exception {
        return new ReportMapper().map(f.getName(), null,
                new ValidatorFactory(settings).validate(new DocumentLoader().load(f), null,
                        verifier, null), settings);
    }

    private static Button find(Node node, String text) {
        if (node instanceof Button b && text.equals(b.getText())) return b;
        if (node instanceof javafx.scene.Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                Button found = find(c, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private interface FxCall<T> { T call() throws Exception; }

    private static <T> T onFx(FxCall<T> call) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(call.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get();
    }
}
