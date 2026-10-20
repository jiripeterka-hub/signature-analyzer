package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Kolik podpisů nese <b>holé razítko VRI</b> — a u kolika z nich je právě ono tím, proč se
 * platnost nepodařilo ověřit.
 *
 * <p>Razítko ve slovníku DSS/VRI, které nekryje žádné dokumentové razítko, nezakládá důkaz
 * existence: přibylo inkrementálním updatem a kdokoli ho může beze stopy odstranit. AnEP to ví
 * ({@code tsKind} „vri_bare", vylučuje ho z BST i ze situací 31 a 32) a v Rozšířeném přehledu
 * o tom má bublinu, ale <b>situaci ani důvod na to nemá</b>, takže se to čtenář Analýzy nedozví.
 * Přitom u podpisu, který jiné razítko nemá, je to celá příčina neúspěchu.
 *
 * <p>Sonda proto počítá čtyři skupiny: podpisy s holým VRI vůbec; z nich ty, kterým chybí důkaz
 * existence; z nich ty s nekladným verdiktem; a nakonec ty, kde je holé VRI <b>jediné</b>
 * razítko — tam je vazba mezi vadou a výsledkem nejtěsnější.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.tools.VriBareScan \
 *       -Dexec.args="-out D:\out\vri.txt D:\tmp"
 * </pre>
 */
public final class VriBareScan {

    private VriBareScan() { }

    public static void main(String[] args) throws Exception {
        List<Path> roots = new ArrayList<>();
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            if ("-out".equals(args[i])) out = Path.of(args[++i]);
            else roots.add(Path.of(args[i]));
        }
        if (roots.isEmpty() || out == null) {
            System.out.println("usage: VriBareScan -out <file> <folder>...");
            return;
        }

        AppSettings settings = AppSettings.load();
        CommonCertificateVerifier verifier;
        try {
            verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
            System.out.println("Duveryhodne seznamy: nacteny");
        } catch (Exception e) {
            verifier = new TrustConfig(settings).buildOfflineCertificateVerifier();
            System.out.println("Duveryhodne seznamy: NEDOSTUPNE");
        }
        ValidatorFactory factory = new ValidatorFactory(settings);
        DocumentLoader loader = new DocumentLoader();
        ReportMapper mapper = new ReportMapper();

        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            try (var walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".pdf") || n.endsWith(".asice") || n.endsWith(".asics");
                    })
                    .sorted().forEach(files::add);
            }
        }
        System.out.println("### souboru: " + files.size());

        int sigs = 0, withBare = 0, bareNoPoe = 0, bareNoPoeBad = 0, bareOnly = 0;
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            int done = 0;
            for (Path p : files) {
                ValidationResult result;
                try {
                    DSSDocument doc = loader.load(p.toFile());
                    result = mapper.map(p.getFileName().toString(), null,
                            factory.validate(doc, null, verifier, null), settings);
                } catch (Exception | StackOverflowError e) {
                    continue;
                }
                for (SignatureSummary sig : result.getSignatures()) {
                    sigs++;
                    List<TimestampEntry> ts = sig.getAllTimestamps();
                    boolean bare = ts.stream().anyMatch(e -> "vri_bare".equals(e.tsKind()));
                    if (!bare) continue;
                    withBare++;
                    boolean noPoe = sig.getBestSignatureTime() == null;
                    boolean onlyBare = ts.stream().allMatch(e -> "vri_bare".equals(e.tsKind()));
                    boolean bad = !"TOTAL_PASSED".equals(sig.getIndication());
                    if (noPoe) bareNoPoe++;
                    if (noPoe && bad) bareNoPoeBad++;
                    if (onlyBare) bareOnly++;
                    w.println((bad ? "jiny  " : "PASSED") + "\t" + sig.getIndication()
                            + (sig.getSubIndication() != null ? "/" + sig.getSubIndication() : "")
                            + "\tbezPoE=" + noPoe + "\tjenVRI=" + onlyBare
                            + "\trazitek=" + ts.size()
                            + "\tsituace=" + sig.getDetectedSituations()
                            + "\tduvody=" + sig.getDetectedReasons()
                            + "\t" + p);
                }
                if (++done % 50 == 0) System.out.println("### " + done + "/" + files.size());
                w.flush();
            }
            String sum = "podpisu=" + sigs
                    + ", s holym VRI=" + withBare
                    + ", z toho bez PoE=" + bareNoPoe
                    + ", z toho nekladny verdikt=" + bareNoPoeBad
                    + ", jen holé VRI a nic jiného=" + bareOnly;
            w.println();
            w.println("# " + sum);
            System.out.println("### " + sum);
        }
    }
}
