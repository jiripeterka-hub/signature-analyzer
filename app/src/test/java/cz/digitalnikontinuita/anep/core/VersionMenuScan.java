package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Kolik podob by nabídka v náhledu vydala podle tří pravidel.
 *
 * <p>Otázka uživatele z 22. 9. 2026: dokument se čtyřmi podpisy, každý s vlastní vizualizací,
 * nabídku vůbec nenabídne. Je to důsledek pravidla 4.6 z {@code docs/nahled-pdf-navrh.md} —
 * položku dostane jen revize, u které DSS hlásí vizuální nebo stránkový rozdíl, a přidaná
 * vizualizace podpisu mezi ně nepatří.
 *
 * <ul>
 *   <li><b>dnes</b> — {@link DocumentVersions#of}, tedy rozdíl podle DSS;</li>
 *   <li><b>vzhled</b> — totéž, a navíc prvek, který sám kreslí viditelnou vizualizaci;</li>
 *   <li><b>vše</b> — položka za každý kryjící prvek.</li>
 * </ul>
 *
 * <pre>java -cp … cz.digitalnikontinuita.anep.core.VersionMenuScan &lt;seznam.txt&gt; &lt;vystup.txt&gt;</pre>
 */
public final class VersionMenuScan {

    private VersionMenuScan() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: VersionMenuScan <seznam.txt> <vystup.txt>");
            return;
        }
        List<String> paths = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);

        AppSettings settings = AppSettings.load();
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();
        ValidatorFactory factory = new ValidatorFactory(settings);

        int files = 0, menuToday = 0, menuLook = 0, menuAll = 0, gained = 0;
        try (PrintWriter w = new PrintWriter(
                Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8))) {
            for (String p : paths) {
                if (p == null || p.isBlank()) continue;
                File f = new File(p.trim());
                if (!f.isFile()) continue;
                files++;
                try {
                    DSSDocument doc = new DocumentLoader().load(f);
                    var outcome = factory.validate(doc, null, verifier, null);
                    DiagnosticData dd = outcome.reports().getDiagnosticData();
                    long len = f.length();

                    int today = DocumentVersions.of(dd, len).size();

                    Map<String, PdfSignatureAppearanceExtractor.Appearance> looks =
                            PdfSignatureAppearanceExtractor.extract(PdfSource.of(f), null);

                    // Nové pravidlo se neopisuje, nýbrž volá — jinak by sonda měřila něco
                    // jiného než aplikace.
                    java.util.Set<String> visible = new java.util.HashSet<>();
                    for (SignatureWrapper sw : dd.getSignatures()) {
                        var ap = sw.getFirstFieldName() == null
                                ? null : looks.get(sw.getFirstFieldName());
                        if (ap != null && ap.visibility() != null && ap.visibility().visible()) {
                            visible.add(sw.getId());
                        }
                    }
                    int look = DocumentVersions.of(dd, len, visible).size();

                    int all = 1;   // dnešní podoba je vždy
                    for (SignatureWrapper sw : dd.getSignatures()) {
                        long end = RevisionTail.coveredUntil(sw.getPDFRevision());
                        if (end > 0 && end < len) all++;
                    }
                    for (TimestampWrapper tw : dd.getTimestampList()) {
                        long end = RevisionTail.coveredUntil(tw.getPDFRevision());
                        if (end > 0 && end < len) all++;
                    }
                    menuToday += today;
                    menuLook += look;
                    menuAll += all;
                    if (look > today) gained++;
                    w.println(String.join("\t", "dnes=" + today, "vzhled=" + look,
                            "vse=" + all, f.getName()));
                } catch (Exception | StackOverflowError e) {
                    w.println("CHYBA\t" + f.getName() + "\t" + e);
                }
                w.flush();
                if (files % 25 == 0) System.out.println("### " + files + "/" + paths.size());
            }
            String sum = "souboru=" + files + ", polozek celkem: dnes=" + menuToday
                    + ", vzhled=" + menuLook + ", vse=" + menuAll
                    + "; souboru, kterym pravidlo 'vzhled' nabidku rozsiri=" + gained;
            w.println("# " + sum);
            System.out.println("### " + sum);
        }
    }

}
