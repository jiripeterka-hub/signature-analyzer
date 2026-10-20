package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.PdfAConformanceChecker;
import cz.digitalnikontinuita.anep.core.PdfProfileExtractor;
import cz.digitalnikontinuita.anep.core.PdfSource;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Projde zadané soubory a adresáře a u každého PDF vypíše, co o své podobě prozrazuje: verzi
 * a podmnožiny, ke kterým se hlásí. Na konci sečte, jak často se co v korpusu vyskytuje.
 *
 * <p>Je to přístroj ke změření věty v záhlaví záložek, ne test — proto vypisuje i počet
 * zpracovaných souborů: nulový nález bez toho čísla nic neznamená.
 *
 * <p>Běží offline a bez JavaFX; stačí {@code java -cp}.
 */
public final class PdfProfileProbe {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: PdfProfileProbe <soubor|adresář>…");
            return;
        }
        List<File> files = new ArrayList<>();
        for (String path : args) collect(new File(path), files);

        Map<String, Integer> versions = new TreeMap<>();
        Map<String, Integer> standards = new TreeMap<>();
        int pdfs = 0;
        int withStandard = 0;
        int unexpected = 0;
        int unreadable = 0;
        int checked = 0, compliant = 0, violating = 0, inconclusive = 0, mostRules = 0;
        long checkMs = 0;

        for (File file : files) {
            PdfSource source = PdfSource.of(file);
            if (!source.isPdf()) continue;
            pdfs++;
            PdfProfileExtractor.Profile profile = PdfProfileExtractor.extract(source, null);
            if (profile == null) {
                unreadable++;
                System.out.println("? nečitelné   " + file);
                continue;
            }
            versions.merge(String.valueOf(profile.version()), 1, Integer::sum);
            if (!profile.standards().isEmpty()) {
                withStandard++;
                String key = String.join(" + ", profile.standards());
                standards.merge(key, 1, Integer::sum);
                System.out.println(key + "   verze " + profile.version()
                        + (profile.unexpected() ? "   [nečekaný údaj]" : "") + "   " + file);
            }
            if (profile.unexpected()) unexpected++;

            // A dostál tomu? Prověří se přesně to, co se prověří i v aplikaci.
            long t0 = System.nanoTime();
            PdfAConformanceChecker.Conformance c =
                    PdfAConformanceChecker.check(source, profile);
            if (c != null) {
                checkMs += (System.nanoTime() - t0) / 1_000_000;
                checked++;
                if (c.inconclusive()) {
                    inconclusive++;
                    System.out.println("   ! ověření selhalo: " + c.failure());
                } else if (c.compliant()) {
                    compliant++;
                } else {
                    violating++;
                    mostRules = Math.max(mostRules, c.violations().size());
                    System.out.println("   ! nevyhovuje, porušuje " + c.violations().size()
                            + " pravidel, první: "
                            + (c.violations().isEmpty() ? "-" : c.violations().get(0).reference()));
                }
            }
        }

        System.out.println();
        System.out.println("zpracováno souborů: " + files.size() + ", z toho PDF: " + pdfs);
        System.out.println("nečitelných PDF: " + unreadable);
        System.out.println("hlásí se k nějaké podmnožině: " + withStandard
                + ", nečekaný identifikační údaj: " + unexpected);
        System.out.println("verze:");
        versions.forEach((v, n) -> System.out.println("   " + v + "   " + n));
        System.out.println("podmnožiny:");
        standards.forEach((s, n) -> System.out.println("   " + s + "   " + n));
        System.out.println("shoda s PDF/A — prověřeno: " + checked
                + ", vyhovuje: " + compliant + ", nevyhovuje: " + violating
                + ", ověření selhalo: " + inconclusive);
        System.out.println("   nejvíc porušených pravidel u jednoho dokumentu: " + mostRules);
        System.out.println("   čas prověřování celkem: " + checkMs + " ms"
                + (checked > 0 ? ", průměr " + checkMs / checked + " ms" : ""));
    }

    private static void collect(File file, List<File> out) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) collect(child, out);
        } else if (file.isFile()) {
            out.add(file);
        }
    }
}
