package cz.digitalnikontinuita.anep.tools;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;

/**
 * Najde podepsaná PDF, jejichž podpisové pole sedí na <b>otočené</b> stránce — a mezi nimi ta,
 * kde pole něco kreslí.
 *
 * <p>Výpočet výřezu vizualizace má pro každé natočení vlastní větev a vyzkoušet se dá jen na
 * dokumentu, který to natočení má. Bez tohohle hledání by se opravovalo naslepo: v korpusu je
 * natočených stránek málo a ručně se nedají najít.
 *
 * <p>Jen PDFBox, žádná síť ani DSS — projde tisíce souborů za minuty.
 *
 * <pre>java -cp … RotatedFieldScan &lt;seznam-cest.txt&gt;</pre>
 */
public final class RotatedFieldScan {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: RotatedFieldScan <seznam-cest.txt>");
            return;
        }
        List<String> paths = Files.readAllLines(Path.of(args[0]));
        TreeMap<Integer, Integer> byRotation = new TreeMap<>();
        int files = 0, withField = 0;

        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            File f = new File(p.trim());
            if (!f.isFile()) continue;
            files++;
            try (PDDocument doc = Loader.loadPDF(f)) {
                PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
                if (form == null) continue;
                for (PDField fld : form.getFieldTree()) {
                    if (!(fld instanceof PDSignatureField sf)) continue;
                    for (PDAnnotationWidget w : sf.getWidgets()) {
                        PDRectangle r = w.getRectangle();
                        if (r == null || r.getWidth() <= 1 || r.getHeight() <= 1) continue;
                        int pi = pageOf(doc, w);
                        if (pi < 0) continue;
                        PDPage page = doc.getPage(pi);
                        int rot = ((page.getRotation() % 360) + 360) % 360;
                        withField++;
                        byRotation.merge(rot, 1, Integer::sum);
                        if (rot != 0) {
                            boolean draws = w.getAppearance() != null
                                    && w.getAppearance().getNormalAppearance() != null;
                            System.out.printf("rotace %3d  kreslí=%s  Rect=%s  strana %d/%d  %s%n",
                                    rot, draws ? "ano" : "ne ", r, pi + 1,
                                    doc.getNumberOfPages(), f.getPath());
                            System.out.flush();
                        }
                    }
                }
            } catch (Exception e) {
                // Poškozené nebo heslem chráněné soubory se přeskakují: hledá se natočení,
                // ne úplnost korpusu.
            }
        }
        System.out.println();
        System.out.printf("souborů %d, podpisových polí s obdélníkem %d%n", files, withField);
        byRotation.forEach((k, v) -> System.out.printf("   rotace %3d : %d%n", k, v));
    }

    private static int pageOf(PDDocument doc, PDAnnotationWidget w) throws Exception {
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            for (PDAnnotation a : doc.getPage(i).getAnnotations()) {
                if (a.getCOSObject() == w.getCOSObject()) return i;
            }
        }
        return -1;
    }
}
