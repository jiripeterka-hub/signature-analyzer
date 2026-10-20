package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Co stojí vykreslení jedné stránky — čas a paměť.
 *
 * <p>Měření, které si {@code docs/nahled-pdf-navrh.md} v oddílu 7.1 uložil udělat dřív, než se
 * napíše první řádek náhledu. Odhad tam stojí („A4 při 150 DPI ≈ 8,7 MB na stránku“) a je to
 * odhad; podle skutečných čísel se má rozhodnout o měřítku a velikosti cache.
 *
 * <p>Měří se to, co bude dělat náhled: otevřít dokument, vykreslit stránku do rastru a převést ji
 * na PNG, protože tudy se obrázek dostává do JavaFX. Otevření dokumentu se počítá zvlášť — děje se
 * jednou za soubor, kdežto vykreslení při každém listování.
 *
 * <pre>java -cp … PdfRenderCostProbe &lt;složka|soubor&gt; [měřítko] [max]</pre>
 */
public final class PdfRenderCostProbe {

    private record Sample(String name, int pages, long openMs, long renderMs, long pngMs,
                          int width, int height, long rasterBytes, long pngBytes) { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: PdfRenderCostProbe <složka|soubor> [měřítko] [max]");
            return;
        }
        float scale = args.length > 1 ? Float.parseFloat(args[1]) : 1.5f;
        int max = args.length > 2 ? Integer.parseInt(args[2]) : Integer.MAX_VALUE;

        List<File> files = new ArrayList<>();
        collect(new File(args[0]), files);
        List<Sample> samples = new ArrayList<>();
        int failed = 0;

        for (File f : files) {
            if (samples.size() >= max) break;
            PdfSource src = PdfSource.of(f);
            if (!src.isPdf()) continue;
            try {
                long t0 = System.nanoTime();
                try (PDDocument doc = src.open(null)) {
                    long opened = System.nanoTime();
                    PDFRenderer renderer = new PDFRenderer(doc);
                    BufferedImage img = renderer.renderImage(0, scale);
                    long rendered = System.nanoTime();

                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(img, "png", png);
                    long encoded = System.nanoTime();

                    long raster = (long) img.getWidth() * img.getHeight()
                            * (img.getColorModel().getPixelSize() / 8);
                    samples.add(new Sample(f.getName(), doc.getNumberOfPages(),
                            ms(t0, opened), ms(opened, rendered), ms(rendered, encoded),
                            img.getWidth(), img.getHeight(), raster, png.size()));
                }
            } catch (Throwable e) {
                failed++;
            }
        }

        if (samples.isEmpty()) {
            System.out.println("Nezměřeno nic.");
            return;
        }

        System.out.println("=== NEJPOMALEJŠÍ VYKRESLENÍ (měřítko " + scale + ") ===");
        samples.stream()
                .sorted(Comparator.comparingLong(Sample::renderMs).reversed())
                .limit(10)
                .forEach(PdfRenderCostProbe::print);

        System.out.println();
        System.out.println("=== NEJVĚTŠÍ RASTR ===");
        samples.stream()
                .sorted(Comparator.comparingLong(Sample::rasterBytes).reversed())
                .limit(5)
                .forEach(PdfRenderCostProbe::print);

        System.out.println();
        System.out.printf("souborů %d (nepodařilo se %d), stran celkem %d%n",
                samples.size(), failed, samples.stream().mapToInt(Sample::pages).sum());
        System.out.printf("otevření dokumentu:  medián %d ms, nejhorší %d ms%n",
                median(samples, Sample::openMs), maxOf(samples, Sample::openMs));
        System.out.printf("vykreslení stránky:  medián %d ms, nejhorší %d ms%n",
                median(samples, Sample::renderMs), maxOf(samples, Sample::renderMs));
        System.out.printf("převod na PNG:       medián %d ms, nejhorší %d ms%n",
                median(samples, Sample::pngMs), maxOf(samples, Sample::pngMs));
        System.out.printf("rastr v paměti:      medián %s, nejhorší %s%n",
                mb(median(samples, Sample::rasterBytes)), mb(maxOf(samples, Sample::rasterBytes)));
        System.out.printf("PNG:                 medián %s, nejhorší %s%n",
                mb(median(samples, Sample::pngBytes)), mb(maxOf(samples, Sample::pngBytes)));
    }

    private static void print(Sample s) {
        System.out.printf("  %-52s %4d str  otevření %4d ms  vykreslení %5d ms  PNG %4d ms"
                        + "  %d×%d  rastr %s  png %s%n",
                s.name().length() > 52 ? s.name().substring(0, 49) + "..." : s.name(),
                s.pages(), s.openMs(), s.renderMs(), s.pngMs(), s.width(), s.height(),
                mb(s.rasterBytes()), mb(s.pngBytes()));
    }

    private static long ms(long from, long to) { return (to - from) / 1_000_000; }

    private static long median(List<Sample> samples, java.util.function.ToLongFunction<Sample> f) {
        long[] v = samples.stream().mapToLong(f).sorted().toArray();
        return v[v.length / 2];
    }

    private static long maxOf(List<Sample> samples, java.util.function.ToLongFunction<Sample> f) {
        return samples.stream().mapToLong(f).max().orElse(0);
    }

    private static String mb(long bytes) {
        return bytes < 1024 * 1024
                ? String.format("%d kB", bytes / 1024)
                : String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static void collect(File root, List<File> out) {
        if (root.isFile()) { out.add(root); return; }
        File[] kids = root.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collect(k, out);
            else if (k.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) out.add(k);
        }
    }

    private PdfRenderCostProbe() { }
}
