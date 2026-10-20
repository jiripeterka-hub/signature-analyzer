package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.jaxb.object.Message;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Která varování knihovna DSS nad sbírkou opravdu vydá — a jak často.
 *
 * <p>Sonda ke stavbě podbloku „Připomínky od knihovny DSS". Výklad k varování má smysl psát jen
 * k těm, která uživatel opravdu uvidí; seznam klíčů v dokumentaci DSS čítá stovky položek
 * a většina z nich nad reálnými dokumenty nikdy nevystoupí. Vypsání vede zvlášť pro prvky,
 * které obstály (tam podblok vznikne), a zvlášť pro ostatní.
 *
 * <p>Výchozí běh je <b>přísný</b>, se všemi ústupky vypnutými — takový, jaký odpovídá
 * nastavení po instalaci. Volba {@code -all} je naopak všechny zapne, protože hlášení
 * odemčené ústupkem se jinak nenaměří a zůstane bez výkladu; tak se 19. 9. 2026 přišlo
 * na {@code LTV_ISCKNR_ANS1}.
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.tools.DssWarningScan [-all] &lt;složka&gt;…</pre>
 */
public final class DssWarningScan {

    private static final List<String> EXTENSIONS =
            List.of(".pdf", ".p7s", ".p7m", ".asice", ".asics", ".sce", ".scs", ".xml", ".json");

    public static void main(String[] args) throws Exception {
        List<Path> roots = new ArrayList<>();
        boolean lenient = false;
        for (String a : args) {
            if ("-all".equals(a)) lenient = true;
            else roots.add(Path.of(a));
        }
        if (roots.isEmpty()) {
            System.err.println("usage: DssWarningScan [-all] <folder> [<folder>...]");
            return;
        }

        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(lenient);
        settings.setAcceptExpiredRevocationSources(lenient);
        settings.setIgnoreSha1Deprecated(lenient);
        settings.setAcceptPdfNotEtsi(lenient);
        System.out.println("rezim: " + (lenient ? "se vsemi ustupky" : "prisne"));

        ValidatorFactory validatorFactory = new ValidatorFactory(settings);
        DocumentLoader documentLoader = new DocumentLoader();
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();

        Map<String, Integer> passedKeys = new TreeMap<>();
        Map<String, Integer> otherKeys = new TreeMap<>();
        Map<String, String> sample = new LinkedHashMap<>();
        Map<String, String> where = new LinkedHashMap<>();
        int sigs = 0, passed = 0, passedWithWarning = 0;

        for (Path root : roots) {
            List<Path> candidates;
            try (Stream<Path> walk = Files.walk(root)) {
                candidates = walk.filter(Files::isRegularFile)
                        .filter(DssWarningScan::isCandidate)
                        .sorted().toList();
            }
            for (Path p : candidates) {
                try {
                    DSSDocument doc = documentLoader.load(p.toFile());
                    ValidationOutcome outcome =
                            validatorFactory.validate(doc, null, verifier, null);
                    SimpleReport sr = outcome.reports().getSimpleReport();
                    List<String> ids = new ArrayList<>(sr.getSignatureIdList());
                    ids.addAll(sr.getTimestampIdList());
                    for (String id : ids) {
                        sigs++;
                        boolean ok = Indication.TOTAL_PASSED.equals(sr.getIndication(id));
                        if (ok) passed++;
                        List<Message> all = new ArrayList<>(sr.getAdESValidationWarnings(id));
                        int ades = all.size();
                        all.addAll(sr.getQualificationWarnings(id));
                        if (ok && !all.isEmpty()) passedWithWarning++;
                        for (int i = 0; i < all.size(); i++) {
                            Message m = all.get(i);
                            String key = (i < ades ? "ades " : "kval ") + m.getKey();
                            (ok ? passedKeys : otherKeys).merge(key, 1, Integer::sum);
                            sample.putIfAbsent(key, m.getValue());
                            if (ok) where.putIfAbsent(key, p.toString());
                        }
                    }
                } catch (Exception e) {
                    // Sonda se ptá na varování, ne na načítání — nesečtený soubor jen přeskočí.
                }
            }
        }

        System.out.println("=== varovani u prvku, ktere OBSTALY (TOTAL_PASSED) ===");
        passedKeys.forEach((k, n) -> System.out.println(
                String.format("%5d  %-45s %s%n       -> %s", n, k, sample.get(k),
                        where.get(k))));
        System.out.println("=== varovani u ostatnich prvku ===");
        otherKeys.forEach((k, n) -> System.out.println(
                String.format("%5d  %-45s %s", n, k, sample.get(k))));
        System.out.println("---");
        System.out.println("prvku=" + sigs + "  obstalo=" + passed
                + "  z toho s varovanim=" + passedWithWarning);
    }

    private static boolean isCandidate(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(n::endsWith);
    }
}
