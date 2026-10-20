package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.jaxb.XmlManifestFile;
import eu.europa.esig.dss.diagnostic.jaxb.XmlSignatureScope;
import eu.europa.esig.dss.jaxb.object.Message;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Co přesně zůstalo v kontejneru nepodepsané, když DSS vydá {@code BBB_CV_IAFS_ANS}.
 *
 * <p>Sonda k jedné otázce: hlášení „Not all files are signed" říká jen tolik, že obsah kontejneru
 * je širší než to, co podpis kryje — ale neřekne <b>co</b> přebývá. Bez toho se k němu nedá napsat
 * poctivý výklad: jinak se čte, když nekrytý zůstal další dokument, a jinak, když je to evidenční
 * záznam nebo jiná průvodní složka, kterou podpis krýt ani nemůže.
 *
 * <p>Pro každý prvek s tímhle hlášením vypíše typ kontejneru, všechny obsahové soubory a ty z nich,
 * které nekryje ani manifest podpisu, ani jeho rozsah. Nakonec sečte přípony nekrytých souborů.
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.tools.UnsignedFilesScan &lt;složka&gt;…</pre>
 */
public final class UnsignedFilesScan {

    private static final List<String> EXTENSIONS =
            List.of(".asice", ".asics", ".sce", ".scs", ".zip");

    public static void main(String[] args) throws Exception {
        List<Path> roots = new ArrayList<>();
        for (String a : args) roots.add(Path.of(a));
        if (roots.isEmpty()) {
            System.err.println("usage: UnsignedFilesScan <folder> [<folder>...]");
            return;
        }

        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(true);
        settings.setAcceptExpiredRevocationSources(true);
        settings.setIgnoreSha1Deprecated(true);
        settings.setAcceptPdfNotEtsi(true);

        ValidatorFactory validatorFactory = new ValidatorFactory(settings);
        DocumentLoader documentLoader = new DocumentLoader();
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();

        Map<String, Integer> uncoveredByExtension = new TreeMap<>();
        int hits = 0, files = 0;

        for (Path root : roots) {
            List<Path> candidates;
            try (Stream<Path> walk = Files.walk(root)) {
                candidates = walk.filter(Files::isRegularFile)
                        .filter(UnsignedFilesScan::isCandidate)
                        .sorted().toList();
            }
            for (Path p : candidates) {
                try {
                    DSSDocument doc = documentLoader.load(p.toFile());
                    ValidationOutcome outcome =
                            validatorFactory.validate(doc, null, verifier, null);
                    SimpleReport sr = outcome.reports().getSimpleReport();
                    DiagnosticData dd = outcome.reports().getDiagnosticData();
                    if (!dd.isContainerInfoPresent()) continue;

                    boolean printed = false;
                    for (String id : sr.getSignatureIdList()) {
                        boolean hit = false;
                        for (Message m : sr.getAdESValidationWarnings(id)) {
                            if ("BBB_CV_IAFS_ANS".equals(m.getKey())) hit = true;
                        }
                        for (Message m : sr.getAdESValidationErrors(id)) {
                            if ("BBB_CV_IAFS_ANS".equals(m.getKey())) hit = true;
                        }
                        if (!hit) continue;
                        hits++;
                        if (!printed) {
                            files++;
                            System.out.println();
                            System.out.println("== " + p);
                            System.out.println("   typ kontejneru : " + dd.getContainerType());
                            System.out.println("   obsahove soubory: " + dd.getContainerContentFilenames());
                            for (XmlManifestFile mf : dd.getManifestFiles()) {
                                System.out.println("   manifest " + mf.getFilename()
                                        + " (k podpisu " + mf.getSignatureFilename() + "): " + mf.getEntries());
                            }
                            printed = true;
                        }
                        SignatureWrapper sw = dd.getSignatureById(id);
                        Set<String> covered = new LinkedHashSet<>();
                        XmlManifestFile mf = dd.getManifestFileForFilename(sw.getFilename());
                        if (mf != null) covered.addAll(mf.getEntries());
                        for (XmlSignatureScope scope : sw.getSignatureScopes()) {
                            covered.add(scope.getName());
                        }
                        List<String> uncovered = new ArrayList<>();
                        for (String f : dd.getContainerContentFilenames()) {
                            if (!covered.contains(f)) uncovered.add(f);
                        }
                        System.out.println("   podpis " + sw.getFilename()
                                + " [" + sr.getIndication(id) + "] kryje " + covered);
                        // Rozsah podpisu i s TYPEM: kontrola v DSS pocita jen rozsahy FULL,
                        // takze prvek s rozsahem jineho typu spadne, i kdyz manifest sedi.
                        for (XmlSignatureScope scope : sw.getSignatureScopes()) {
                            System.out.println("      rozsah: " + scope.getName()
                                    + "  typ=" + scope.getScope());
                        }
                        System.out.println("      NEKRYTO: " + uncovered);
                        for (String f : uncovered) {
                            int dot = f.lastIndexOf('.');
                            String ext = dot < 0 ? "(bez pripony)" : f.substring(dot).toLowerCase(Locale.ROOT);
                            uncoveredByExtension.merge(ext, 1, Integer::sum);
                        }
                    }
                } catch (Exception e) {
                    // Sonda se ptá na obsah kontejneru, ne na načítání.
                }
            }
        }

        System.out.println();
        System.out.println("=== pripony nekrytych souboru ===");
        uncoveredByExtension.forEach((k, n) -> System.out.println(String.format("%5d  %s", n, k)));
        System.out.println("---");
        System.out.println("prvku s hlasenim=" + hits + "  v " + files + " souborech");
    }

    private static boolean isCandidate(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(n::endsWith);
    }
}
