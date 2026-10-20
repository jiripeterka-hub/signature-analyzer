package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.PdfSignatureAppearanceExtractor;
import cz.digitalnikontinuita.anep.core.PdfSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.io.File;
import java.nio.file.Files;

/**
 * Zjišťuje, jestli výpočet výřezu obstojí i tam, kde se <b>CropBox liší od MediaBoxu</b>.
 *
 * <p>Podezření: {@code PDFRenderer} kreslí CropBox, kdežto výpočet vychází z MediaBoxu a nikde
 * neodečítá počátek boxu. V korpusu mají všechny stránky oba boxy shodné a s počátkem v nule,
 * takže se to nemá na čem projevit — zkouší se proto na uměle upravené kopii.
 *
 * <p><b>Zkušební CropBox musí podpisové pole obsahovat.</b> První pokus ho zmenšil tak, že pole
 * zůstalo venku; že se vizualizace nenašla, pak nebyla chyba výpočtu, nýbrž správný závěr —
 * v oříznuté stránce podpis opravdu není. Obě zdejší varianty pole obsahují, takže se ptají
 * jen na to, nač mají.
 *
 * <pre>java -cp … CropBoxProbe &lt;pdf&gt; &lt;výstupní-adresář&gt;</pre>
 */
public final class CropBoxProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: CropBoxProbe <pdf> <výstupní-adresář>");
            return;
        }
        File src = new File(args[0]);
        File outDir = new File(args[1]);
        outDir.mkdirs();

        dump(src, outDir, "0-puvodni");

        // Jiná velikost, počátek pořád v nule — ptá se jen na rozměry.
        variant(src, outDir, "1-cropbox-mensi", new PDRectangle(0, 0, 300, 400));
        // Jiná velikost i počátek — ptá se navíc na posun.
        variant(src, outDir, "2-cropbox-posunuty", new PDRectangle(5, 5, 295, 395));
    }

    private static void variant(File src, File outDir, String label, PDRectangle cropBox)
            throws Exception {
        File f = new File(outDir, label + ".pdf");
        try (PDDocument doc = Loader.loadPDF(src)) {
            doc.getPage(0).setCropBox(cropBox);
            doc.save(f);
        }
        System.out.printf("%n%s: CropBox %s, MediaBox zůstává%n", label, cropBox);
        dump(f, outDir, label);
    }

    private static void dump(File pdf, File outDir, String label) throws Exception {
        var map = PdfSignatureAppearanceExtractor.extract(PdfSource.of(pdf), null);
        if (map.isEmpty()) { System.out.println("   " + label + " : žádná vizualizace"); return; }
        for (var e : map.entrySet()) {
            var a = e.getValue();
            File out = new File(outDir, label + ".png");
            System.out.printf("   %-22s %s%n", label,
                    a.png() == null ? "bez obrázku" : a.png().length + " B → " + out.getName());
            if (a.png() != null) Files.write(out.toPath(), a.png());
        }
    }
}
