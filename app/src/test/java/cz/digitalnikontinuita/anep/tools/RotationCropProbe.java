package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.PdfSignatureAppearanceExtractor;
import cz.digitalnikontinuita.anep.core.PdfSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.nio.file.Files;

/**
 * Uloží týž dokument se všemi čtyřmi natočeními stránky a ke každému vytáhne vizualizaci — aby
 * šlo očima zkontrolovat, že výřez sedí na podpis, a ne na kus stránky pod ním.
 *
 * <p>V korpusu 5 592 podepsaných PDF má otočenou stránku <b>jediný</b> dokument (270°), takže
 * větve pro 90° a 180° by jinak neměly na čem obstát. Přetočení téhož souboru je vyrobí: obdélník
 * pole i jeho vzhledový proud zůstávají v souřadnicích dokumentu a mění se jen {@code /Rotate} —
 * tedy přesně ta veličina, o kterou ve výpočtu jde. Podpis se přeuložením poruší, což nevadí:
 * měří se geometrie, ne platnost.
 *
 * <p>Vizualizaci vydává <b>sama aplikace</b>, ne opsaný vzorec. Sonda s vlastní kopií výpočtu tu
 * byla a je zrušená: ověřovala by kopii, a ta se dřív nebo později s produkčním kódem rozejde.
 * Čísla výřezu hlídá {@code AppearanceCropTest}, tohle je na podívání.
 *
 * <pre>java -cp … RotationCropProbe &lt;pdf&gt; &lt;výstupní-adresář&gt;</pre>
 */
public final class RotationCropProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: RotationCropProbe <pdf> <výstupní-adresář>");
            return;
        }
        File outDir = new File(args[1]);
        outDir.mkdirs();

        for (int rot : new int[] {0, 90, 180, 270}) {
            File tmp = new File(outDir, "rot" + rot + ".pdf");
            try (PDDocument doc = Loader.loadPDF(new File(args[0]))) {
                doc.getPage(0).setRotation(rot);
                doc.save(tmp);
            }
            var map = PdfSignatureAppearanceExtractor.extract(PdfSource.of(tmp), null);
            if (map.isEmpty()) {
                System.out.printf("rotace %3d : žádná vizualizace%n", rot);
                continue;
            }
            for (var e : map.entrySet()) {
                byte[] png = e.getValue().png();
                File out = new File(outDir, "rot" + rot + ".png");
                System.out.printf("rotace %3d : pole „%s“ → %s%n", rot, e.getKey(),
                        png == null ? "bez obrázku" : png.length + " B, " + out.getName());
                if (png != null) Files.write(out.toPath(), png);
            }
        }
    }
}
