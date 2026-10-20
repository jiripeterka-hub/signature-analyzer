package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.io.File;
import java.nio.file.Files;

/**
 * Vykreslí stranu do PNG, ať se dá podívat, co z ní PDFBox udělá.
 *
 * <p>Nástroj na jednu otázku, která se jinak nedá zodpovědět jinak než okem: <b>vypadá vykreslená
 * stránka tak, jak má?</b> Vznikl 18. 9. 2026 z nálezu uživatele, že vizualizace podpisu
 * v náhledu má přeházená písmena („RNDr. Ing. Jiří Peterka“ → „RNdr. Lng. Jiví P%terkb“), zatímco
 * ostatní text téže stránky je v pořádku.
 *
 * <p>Umí i <b>prefix</b>, tedy tu podobu, kterou kryje podpis — právě na ní se ta vada ukázala
 * a bez možnosti vykreslit prefix zvlášť by nešlo rozhodnout, zda ji působí ořez, nebo je v tom
 * dokumentu odjakživa.
 *
 * <pre>java -cp … PageRenderDumpProbe &lt;vstup.pdf&gt; &lt;výstup.png&gt; [strana] [měřítko] [prefixBajtů]</pre>
 */
public final class PageRenderDumpProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: PageRenderDumpProbe <vstup.pdf> <výstup.png>"
                    + " [strana] [měřítko] [prefixBajtů]");
            return;
        }
        File in = new File(args[0]);
        File out = new File(args[1]);
        int page = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        float scale = args.length > 3 ? Float.parseFloat(args[3]) : 1.5f;
        long prefix = args.length > 4 ? Long.parseLong(args[4]) : 0;

        byte[] bytes = Files.readAllBytes(in.toPath());
        if (prefix > 0 && prefix < bytes.length) {
            bytes = java.util.Arrays.copyOf(bytes, (int) prefix);
        }
        System.out.println("vstup: " + in + "  (" + bytes.length + " B"
                + (prefix > 0 ? ", prefix" : "") + ")");

        try (PDDocument doc = PdfSource.of(bytes).open(null)) {
            System.out.println("stran: " + doc.getNumberOfPages());
            var image = new PDFRenderer(doc).renderImage(page - 1, scale);
            javax.imageio.ImageIO.write(image, "png", out);
            System.out.println("výstup: " + out + "  " + image.getWidth() + "×" + image.getHeight());
        }
    }

    private PageRenderDumpProbe() { }
}
