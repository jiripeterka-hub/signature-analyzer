package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Snese jeden {@code PDDocument} dvě vlákna, která z něj současně vykreslují?
 *
 * <p>Otázka z nálezu uživatele z 18. 9. 2026: v náhledu měla vizualizace podpisu přeházená
 * písmena („RNDr. Ing. Jiří Peterka“ → „RNdr. Lng. Jiví P%terkb“), zatímco týž soubor vykreslený
 * mimo aplikaci vyšel správně. Pátá etapa přitom do okna přidala pruh miniatur, a ten vykresluje
 * na <b>druhém</b> vlákně — z téhož dokumentu, ze kterého současně vykresluje stránku to první.
 *
 * <p>Sonda to postaví proti sobě: napřed vykreslí stránku sama, v klidu, a ten rastr si nechá jako
 * měřítko. Pak spustí vykreslování miniatur na druhém vlákně a týž rastr pořídí znovu, opakovaně.
 * Rozdíl v jediném obrazovém bodu stačí — obrázek téže stránky z téhož dokumentu má vyjít pokaždé
 * stejně.
 *
 * <p>S přepínačem {@code -lock} projdou obě vlákna jednocestnou závorou — právě tak, jak to od
 * 18. 9. 2026 dělá {@code PdfPreviewWindow}. Je to druhá polovina měření: nález bez ověření, že ho
 * oprava léčí, je jen půlka práce.
 *
 * <pre>java -cp … ConcurrentRenderProbe &lt;soubor.pdf&gt; [opakování] [-lock]</pre>
 */
public final class ConcurrentRenderProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: ConcurrentRenderProbe <soubor.pdf> [opakování]");
            return;
        }
        File file = new File(args[0]);
        int rounds = args.length > 1 && !args[1].startsWith("-") ? Integer.parseInt(args[1]) : 12;
        boolean locked = java.util.Arrays.asList(args).contains("-lock");
        final Object gate = new Object();
        System.out.println(locked ? "se závorou" : "bez závory");
        byte[] bytes = Files.readAllBytes(file.toPath());

        BufferedImage reference;
        try (PDDocument doc = PdfSource.of(bytes).open(null)) {
            reference = new PDFRenderer(doc).renderImage(0, 1.5f);
        }
        System.out.println("měřítko pořízeno v klidu: "
                + reference.getWidth() + "×" + reference.getHeight());

        try (PDDocument doc = PdfSource.of(bytes).open(null)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = doc.getNumberOfPages();

            AtomicInteger differing = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(1);

            // Druhé vlákno dělá to, co v aplikaci dělá pruh miniatur: mele dokola týž dokument
            // v malém měřítku.
            Thread thumbs = new Thread(() -> {
                while (done.getCount() > 0) {
                    for (int p = 0; p < pages; p++) {
                        try {
                            if (locked) {
                                synchronized (gate) { renderer.renderImage(p, 0.2f); }
                            } else {
                                renderer.renderImage(p, 0.2f);
                            }
                        } catch (Throwable ignored) {
                            // Výjimka je taky nález, ale počítá se až u toho druhého vlákna —
                            // tady jde jen o to, aby se dokument používal současně.
                        }
                    }
                }
            }, "thumbs");
            thumbs.setDaemon(true);
            thumbs.start();

            for (int i = 0; i < rounds; i++) {
                try {
                    BufferedImage again;
                    if (locked) {
                        synchronized (gate) { again = renderer.renderImage(0, 1.5f); }
                    } else {
                        again = renderer.renderImage(0, 1.5f);
                    }
                    if (!same(reference, again)) differing.incrementAndGet();
                } catch (Throwable e) {
                    failed.incrementAndGet();
                    System.out.println("  výjimka: " + e);
                }
            }
            done.countDown();
            thumbs.join(2000);

            System.out.println();
            System.out.printf("pokusů %d, rastr se lišil %dkrát, vykreslení selhalo %dkrát%n",
                    rounds, differing.get(), failed.get());
            System.out.println(differing.get() == 0 && failed.get() == 0
                    ? "Souběh se neprojevil."
                    : "NÁLEZ: jeden PDDocument dvě vlákna nesnese.");
        }
    }

    private static boolean same(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
            }
        }
        return true;
    }

    private ConcurrentRenderProbe() { }
}
