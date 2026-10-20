package cz.digitalnikontinuita.anep.tools;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;

import java.io.File;

/**
 * Vypíše u každé stránky MediaBox, CropBox a natočení, a u každého podpisového pole jeho
 * obdélník — tedy přesně ty údaje, ze kterých se počítá výřez vizualizace.
 *
 * <p>Na těch dvou obdélnících záleží víc, než je vidět: {@code PDFRenderer} kreslí <b>CropBox</b>,
 * kdežto výpočet výřezu vychází z MediaBoxu. Liší-li se, nebo nemá-li box počátek v (0,0),
 * ukáže se výřez odjinud, než kde podpis doopravdy je.
 */
public final class PageBoxProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: PageBoxProbe <pdf>");
            return;
        }
        try (PDDocument doc = Loader.loadPDF(new File(args[0]))) {
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage p = doc.getPage(i);
                System.out.printf("stránka %d%n", i + 1);
                System.out.printf("   MediaBox : %s%n", p.getMediaBox());
                System.out.printf("   CropBox  : %s%n", p.getCropBox());
                System.out.printf("   rotace   : %d%n", p.getRotation());
                boolean same = p.getMediaBox().toString().equals(p.getCropBox().toString());
                System.out.printf("   shodné   : %s;  počátek MediaBoxu v nule: %s%n", same,
                        p.getMediaBox().getLowerLeftX() == 0 && p.getMediaBox().getLowerLeftY() == 0);
            }
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            if (form == null) { System.out.println("bez AcroFormu"); return; }
            for (PDField f : form.getFieldTree()) {
                if (!(f instanceof PDSignatureField sf)) continue;
                System.out.printf("%npole „%s“%n", sf.getFullyQualifiedName());
                for (PDAnnotationWidget w : sf.getWidgets()) {
                    System.out.printf("   Rect     : %s%n", w.getRectangle());
                    System.out.printf("   /F       : %d%n", w.getAnnotationFlags());
                    System.out.printf("   vzhled   : %s%n",
                            w.getAppearance() != null && w.getAppearance().getNormalAppearance() != null
                                    ? "ano" : "—");
                    int page = -1;
                    for (int i = 0; i < doc.getNumberOfPages(); i++) {
                        for (PDAnnotation a : doc.getPage(i).getAnnotations()) {
                            if (a.getCOSObject() == w.getCOSObject()) page = i + 1;
                        }
                    }
                    System.out.printf("   stránka  : %d%n", page);
                }
            }
        }
    }
}
