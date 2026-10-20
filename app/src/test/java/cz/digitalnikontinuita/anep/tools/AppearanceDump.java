package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.PdfSignatureAppearanceExtractor;
import cz.digitalnikontinuita.anep.core.PdfSource;

import java.io.File;
import java.nio.file.Files;

/**
 * Uloží vizualizace tak, jak je vydá sama aplikace — ne sonda s opsaným vzorcem.
 *
 * <p>Bez toho se ověřuje opravená kopie výpočtu, ne opravený výpočet.
 *
 * <pre>java -cp … AppearanceDump &lt;pdf&gt; &lt;výstupní-adresář&gt;</pre>
 */
public final class AppearanceDump {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: AppearanceDump <pdf> <výstupní-adresář>");
            return;
        }
        File outDir = new File(args[1]);
        outDir.mkdirs();

        var map = PdfSignatureAppearanceExtractor.extract(
                PdfSource.of(new File(args[0])), null);
        if (map.isEmpty()) {
            System.out.println("žádná vizualizace");
            return;
        }
        int n = 0;
        for (var e : map.entrySet()) {
            n++;
            var a = e.getValue();
            File out = new File(outDir, "vzhled" + n + ".png");
            System.out.printf("pole „%s“ : viditelnost %s, %s%n", e.getKey(), a.visibility(),
                    a.png() == null ? "bez obrázku" : a.png().length + " B → " + out.getName());
            if (a.png() != null) Files.write(out.toPath(), a.png());
        }
    }
}
