package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.AugmentationService;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReliedUponRevocation;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Augments each given PDF and reports how much the file grew, plus the CRLs in the DSS before
 * and after.
 *
 * <p>Written to measure whether serving revocation data the document already carries actually
 * keeps bytes out of the result — the stripping done in {@code postProcessDss} cannot, because
 * {@code saveIncremental} only appends. Run it before and after a change and compare the growth
 * figures; the CRL listings say where the bytes went.
 *
 * <p><b>{@code -relied} zapíná to, co dělá tlačítko.</b> Aplikace předává do augmentace ještě
 * {@code extraCrls} — seznamy, o které se opřelo právě doběhlé ověření ({@code
 * ReliedUponRevocation}) — a sonda je do 8. 9. 2026 nepředávala vůbec, takže každý naměřený
 * přírůstek přesně o tuhle položku lhal. Nad „PAdES T-LEvel jen uznavany extended.pdf“ vyšlo bez
 * přepínače +52 kB a s ním +2,6 MB; ten rozdíl je celá otázka, kvůli které sonda existuje.
 * Přepínač validuje dokument stejným ověřovatelem, jakým to dělá aplikace.
 *
 * <pre>
 *   mvn -q org.codehaus.mojo:exec-maven-plugin:3.3.0:java \
 *       -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.tools.AugmentSizeProbe \
 *       -Dexec.args="\"D:\\path\\to\\file.pdf\""
 * </pre>
 */
public final class AugmentSizeProbe {

    public static void main(String[] args) throws Exception {
        List<Path> inputs = new ArrayList<>();
        // Mirrors what the Analýza tab passes when the verdict it just showed rests on a revocation:
        // the document's own responses must not stand in for the network then. Without the switch
        // only the cheap branch could be measured.
        boolean revocationDecisive = false;
        boolean feedRelied = false;
        boolean feedS33 = false;
        boolean listOnly = false;
        for (String a : args) {
            if ("-revoked".equals(a)) { revocationDecisive = true; continue; }
            if ("-relied".equals(a)) { feedRelied = true; continue; }
            // Seznamy ke kotvám důvěry, které tlačítko Uchovat předává ze situace 33. Bez nich
            // měří sonda jinou dávku než aplikace — a právě ony bývají tím, co dokument nafoukne.
            if ("-s33".equals(a)) { feedS33 = true; continue; }
            // Jen soupis toho, co by se předalo — bez augmentace. Porovnat dva běhy (před
            // změnou a po ní) je pak otázka vteřin na dokument místo minut na sbírku.
            if ("-list".equals(a)) { feedRelied = true; listOnly = true; continue; }
            Path p = Path.of(a);
            if (Files.isDirectory(p)) {
                try (Stream<Path> walk = Files.walk(p)) {
                    walk.filter(Files::isRegularFile)
                            .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".pdf"))
                            .sorted().forEach(inputs::add);
                }
            } else if (Files.isRegularFile(p)) {
                inputs.add(p);
            }
        }
        if (inputs.isEmpty()) {
            System.err.println("usage: AugmentSizeProbe [-revoked] [-relied] [-s33]"
                    + " <file.pdf|folder> [...]");
            return;
        }
        final boolean decisive = revocationDecisive;
        final boolean relied = feedRelied;
        final boolean s33 = feedS33;
        final boolean onlyList = listOnly;
        System.out.println("verdikt stojí na revokaci: " + decisive
                + "   předávat seznamy, o které se ověření opřelo: " + relied);

        AppSettings settings = AppSettings.load();
        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier;
        try {
            verifier = trustConfig.buildLotlCertificateVerifier();
            System.out.println("Trusted Lists: loaded");
        } catch (Exception e) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
            System.out.println("Trusted Lists: UNAVAILABLE (" + e + ")");
        }

        AugmentationService service =
                new AugmentationService(new AtomicReference<>(verifier));

        for (Path in : inputs) {
            System.out.println();
            System.out.println("=== " + in.getFileName());
            long before = Files.size(in);
            if (onlyList) {
                long sum = 0;
                for (byte[] der : reliedUpon(in, settings, verifier)) {
                    System.out.println("  extraCRL  " + kb(der.length));
                    sum += der.length;
                }
                System.out.println("  extraCRL celkem " + kb(sum));
                continue;
            }
            System.out.println("  vstup   " + kb(before));
            describeCrls("  vstup   ", Files.readAllBytes(in));
            try {
                long t0 = System.currentTimeMillis();
                List<byte[]> extra = new java.util.ArrayList<>();
                if (relied) extra.addAll(reliedUpon(in, settings, verifier));
                if (s33) extra.addAll(anchorCrls(in, settings, verifier));
                for (byte[] der : extra) {
                    System.out.println("  extraCRL  " + kb(der.length)
                            + "   (o tenhle seznam se opřelo ověření)");
                }
                DSSDocument out = service.augment(in.toFile(), null, extra, decisive);
                long ms = System.currentTimeMillis() - t0;
                byte[] outBytes;
                try (InputStream is = out.openStream()) { outBytes = is.readAllBytes(); }
                long after = outBytes.length;
                System.out.println("  výstup  " + kb(after)
                        + "   přírůstek " + kb(after - before)
                        + "   (" + ms + " ms)");
                describeCrls("  výstup  ", outBytes);
                File dump = new File(System.getProperty("anep.probe.out", "."),
                        in.getFileName().toString().replaceAll("\\.pdf$", "") + "-augmented.pdf");
                Files.write(dump.toPath(), outBytes);
                System.out.println("  uloženo " + dump);
            } catch (Exception e) {
                System.out.println("  SELHALO: " + e);
            }
        }
    }

    /**
     * Seznamy, o které se opřelo ověření — přesně to, co aplikace předává jako {@code extraCrls}.
     * Ověřuje se týmž ověřovatelem, aby se sonda ptala stejných zdrojů jako ona.
     */
    private static List<byte[]> reliedUpon(Path in, AppSettings settings,
                                           CommonCertificateVerifier verifier) {
        try {
            DSSDocument doc = new DocumentLoader().load(in.toFile());
            var reports = new ValidatorFactory(settings).validate(doc, null, verifier, null);
            return ReliedUponRevocation.crlBytes(reports.reports().getDiagnosticData());
        } catch (Exception e) {
            System.out.println("  (ověření pro extraCrls selhalo: " + e + ")");
            return List.of();
        }
    }

    /**
     * Seznamy ke kotvám důvěry, které by uchování vložilo — totéž, co sbírá
     * {@code AdvisorView} před stiskem tlačítka Uchovat.
     */
    private static List<byte[]> anchorCrls(Path in, AppSettings settings,
                                           CommonCertificateVerifier verifier) {
        try {
            DSSDocument doc = new DocumentLoader().load(in.toFile());
            var outcome = new ValidatorFactory(settings).validate(doc, null, verifier, null);
            var result = new cz.digitalnikontinuita.anep.core.ReportMapper()
                    .map(in.getFileName().toString(), null, outcome, settings);
            List<byte[]> out = new java.util.ArrayList<>();
            for (var sig : result.getSignatures()) {
                if (sig.getDetectedSituations().contains(33)) out.addAll(sig.getS33CrlBytes());
            }
            return out;
        } catch (Exception e) {
            System.out.println("  (údaje ke kotvám se nepodařilo získat: " + e + ")");
            return List.of();
        }
    }

    /** Prints one line per CRL referenced from the DSS: size, issuer, thisUpdate. */
    private static void describeCrls(String prefix, byte[] pdfBytes) {
        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            COSBase dssBase = pdf.getDocumentCatalog().getCOSObject()
                    .getDictionaryObject(COSName.getPDFName("DSS"));
            if (!(dssBase instanceof COSDictionary dss)) {
                System.out.println(prefix + "DSS: žádný");
                return;
            }
            COSBase crlsBase = dss.getDictionaryObject(COSName.getPDFName("CRLs"));
            if (!(crlsBase instanceof COSArray crls) || crls.size() == 0) {
                System.out.println(prefix + "CRL: žádná");
                describeArray(prefix, dss, "OCSPs");
                describeArray(prefix, dss, "Certs");
                return;
            }
            long total = 0;
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < crls.size(); i++) {
                if (!(crls.getObject(i) instanceof COSStream s)) continue;
                byte[] der;
                try (InputStream is = s.createInputStream()) { der = is.readAllBytes(); }
                total += der.length;
                String desc;
                try {
                    java.security.cert.X509CRL crl = (java.security.cert.X509CRL)
                            java.security.cert.CertificateFactory.getInstance("X.509")
                                    .generateCRL(new java.io.ByteArrayInputStream(der));
                    desc = crl.getThisUpdate() + "  " + crl.getIssuerX500Principal().getName();
                } catch (Exception e) {
                    desc = "(nečitelná)";
                }
                lines.add(prefix + "  CRL " + kb(der.length) + "  " + desc);
            }
            System.out.println(prefix + "CRL: " + lines.size() + " ks, celkem " + kb(total));
            lines.forEach(System.out::println);
            describeArray(prefix, dss, "OCSPs");
            describeArray(prefix, dss, "Certs");
        } catch (Exception e) {
            System.out.println(prefix + "CRL: nepřečteno (" + e + ")");
        }
    }

    /** Prints the item count and total byte size of a DSS sub-array. */
    private static void describeArray(String prefix, COSDictionary dss, String name) {
        COSBase base = dss.getDictionaryObject(COSName.getPDFName(name));
        if (!(base instanceof COSArray arr) || arr.size() == 0) {
            System.out.println(prefix + name + ": žádné");
            return;
        }
        long total = 0;
        int n = 0;
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.getObject(i) instanceof COSStream s)) continue;
            try (InputStream is = s.createInputStream()) { total += is.readAllBytes().length; n++; }
            catch (Exception ignored) {}
        }
        System.out.println(prefix + name + ": " + n + " ks, celkem " + kb(total));
    }

    private static String kb(long bytes) {
        return String.format("%,d B (%,.1f kB)", bytes, bytes / 1024.0);
    }
}
