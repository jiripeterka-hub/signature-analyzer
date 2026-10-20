package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Dá se u <b>jednotlivého</b> časového razítka říct, že jeho službě byl odebrán kvalifikovaný status?
 *
 * <p>Situace 43 tuhle otázku zodpovídá za celý podpis a navíc až po několika podmínkách o tom,
 * jestli to verdikt něco stálo. Do závorky u řádku „platnost razítka se nepodařilo ověřit" je
 * potřeba jen ta první, čistě per-razítková část: <b>měla služba kladný status, když razítko
 * vzniklo, a nemá ho teď?</b> Sonda ji počítá pro každé razítko zvlášť.
 *
 * <pre>java -cp … WithdrawnPerTimestampProbe &lt;soubor nebo složka&gt;…</pre>
 */
public final class WithdrawnPerTimestampProbe {

    private static final List<String> EXTENSIONS =
            List.of(".pdf", ".p7s", ".p7m", ".asice", ".asics", ".sce", ".scs", ".xml", ".json");

    /** Stavy, které se počítají za kladné — táž množina jako u situace 43. */
    private static final List<String> POSITIVE =
            List.of("granted", "recognisedatnationallevel", "undersupervision",
                    "accredited", "supervisionincessation");

    public static void main(String[] args) throws Exception {
        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(false);
        settings.setAcceptExpiredRevocationSources(false);
        settings.setAcceptPdfNotEtsi(false);

        var factory = new ValidatorFactory(settings);
        var loader = new DocumentLoader();
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();

        List<Path> files = new ArrayList<>();
        for (String a : args) {
            Path root = Path.of(a);
            if (Files.isRegularFile(root)) {
                files.add(root);
            } else {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile)
                            .filter(WithdrawnPerTimestampProbe::isCandidate)
                            .sorted().forEach(files::add);
                }
            }
        }

        for (Path p : files) {
            try {
                var doc = loader.load(p.toFile());
                var outcome = factory.validate(doc, null, verifier, null);
                SimpleReport sr = outcome.reports().getSimpleReport();
                var dd = outcome.reports().getDiagnosticData();
                boolean named = false;
                for (TimestampWrapper ts : dd.getTimestampList()) {
                    if (Indication.PASSED.equals(sr.getIndication(ts.getId()))) continue;
                    if (!named) {
                        System.out.println("=== " + p.getFileName());
                        named = true;
                    }
                    System.out.println("  razítko " + ts.getProductionTime()
                            + "   odebraný status: " + withdrawn(ts));
                    CertificateWrapper tsa = ts.getSigningCertificate();
                    if (tsa == null) {
                        System.out.println("     (bez certifikátu autority)");
                        continue;
                    }
                    for (var tsp : tsa.getTrustServiceProviders()) {
                        for (var svc : tsp.getTrustServices()) {
                            System.out.println("     služba " + tail(svc.getServiceType())
                                    + " / " + tail(svc.getStatus())
                                    + "  od " + svc.getStartDate() + " do " + svc.getEndDate());
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("  CHYBA " + p.getFileName() + ": " + e);
            }
        }
    }

    /** Měla služba kladný status, když razítko vzniklo, a nemá ho teď? */
    static Boolean withdrawn(TimestampWrapper ts) {
        CertificateWrapper tsa = ts.getSigningCertificate();
        if (tsa == null || ts.getProductionTime() == null) return null;
        Date made = ts.getProductionTime();
        Date now = new Date();
        boolean any = false, positiveThen = false, positiveNow = false;
        for (var tsp : tsa.getTrustServiceProviders()) {
            for (var svc : tsp.getTrustServices()) {
                String type = tail(svc.getServiceType());
                if (!"QTST".equalsIgnoreCase(type) && !"TSA/QTST".equalsIgnoreCase(type)) continue;
                any = true;
                boolean positive = POSITIVE.contains(
                        tail(svc.getStatus()).toLowerCase(Locale.ROOT));
                if (positive && covers(svc, made)) positiveThen = true;
                if (positive && covers(svc, now)) positiveNow = true;
            }
        }
        if (!any) return null;
        return positiveThen && !positiveNow;
    }

    private static boolean covers(eu.europa.esig.dss.diagnostic.jaxb.XmlTrustService svc, Date at) {
        Date from = svc.getStartDate();
        Date to = svc.getEndDate();
        return (from == null || !at.before(from)) && (to == null || at.before(to));
    }

    private static String tail(String uri) {
        if (uri == null) return "";
        int slash = uri.lastIndexOf('/');
        return slash >= 0 ? uri.substring(slash + 1) : uri;
    }

    private static boolean isCandidate(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(n::endsWith);
    }
}
