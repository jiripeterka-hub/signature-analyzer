package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;

import java.io.File;

/**
 * Řetěz certifikátů samostatných časových razítek, a u každého článku, jestli je kotvou důvěry
 * a jaké revokační údaje k němu jsou.
 *
 * <p>Na tom se láme otázka, jestli razítku „chybí revokační informace": kotva důvěry se na
 * revokaci nezjišťuje vůbec, takže u ní nic chybět nemůže.
 */
public final class TimestampChainProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: TimestampChainProbe <soubor>");
            return;
        }
        File file = new File(args[0]);
        AppSettings settings = AppSettings.load();
        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        var outcome = new ValidatorFactory(settings)
                .validate(new DocumentLoader().load(file), null, verifier, null);
        var dd = outcome.reports().getDiagnosticData();
        var simple = outcome.reports().getSimpleReport();

        for (TimestampWrapper ts : dd.getTimestampList()) {
            System.out.println();
            System.out.printf("razítko %s  typ=%s  vzniklo=%s  %s%n", ts.getId().substring(0, 12),
                    ts.getType(), ts.getProductionTime(), simple.getIndication(ts.getId()));
            System.out.printf("   kryje podpisy: %s%n", ts.getTimestampedSignatures().size());
            for (CertificateWrapper c : ts.getCertificateChain()) {
                System.out.printf("   • %-55s kotva=%s  platí do %s%n",
                        zkrat(c.getReadableCertificateName()), c.isTrusted(), c.getNotAfter());
                if (c.isTrusted()) {
                    System.out.println("       (kotva důvěry — revokace se u ní nezjišťuje)");
                    continue;
                }
                if (c.getCertificateRevocationData().isEmpty()) {
                    System.out.println("       revokace: ŽÁDNÁ");
                } else {
                    c.getCertificateRevocationData().forEach(r -> System.out.printf(
                            "       revokace: %s z %s, zdroj %s%n",
                            r.getRevocationType(), r.getThisUpdate(), r.getOrigin()));
                }
            }
        }
    }

    private static String zkrat(String s) {
        return s == null ? "—" : (s.length() > 55 ? s.substring(0, 55) : s);
    }
}
