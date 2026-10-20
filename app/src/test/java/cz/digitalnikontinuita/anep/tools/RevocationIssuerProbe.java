package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.RevocationWrapper;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;

import java.io.File;

/**
 * Ke každému prvku vypíše podpisový certifikát, revokační údaje k němu a — to je tu to podstatné —
 * <b>vydavatele každého toho údaje i s jeho dobou platnosti</b>.
 *
 * <p>Právě na vydavateli revokačního údaje se láme, jestli DSS údaj dnes ještě smí použít: je-li
 * certifikát respondéra po expiraci a nic nedokládá, že odpověď vznikla za jeho platnosti,
 * ověření skončí u „nevím“, i když odpověď sama zní „neodvolán“.
 */
public final class RevocationIssuerProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: RevocationIssuerProbe <soubor>");
            return;
        }
        File file = new File(args[0]);
        AppSettings settings = AppSettings.load();
        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        var outcome = new ValidatorFactory(settings)
                .validate(new DocumentLoader().load(file), null, verifier, null);
        var dd = outcome.reports().getDiagnosticData();
        var simple = outcome.reports().getSimpleReport();

        int n = 0;
        for (SignatureWrapper sw : dd.getSignatures()) {
            n++;
            CertificateWrapper sc = sw.getSigningCertificate();
            System.out.println();
            System.out.printf("--- prvek č. %d : %s / %s%n", n,
                    simple.getIndication(sw.getId()), simple.getSubIndication(sw.getId()));
            if (sc == null) { System.out.println("      bez podpisového certifikátu"); continue; }
            System.out.printf("      podpisový cert : %s%n", sc.getReadableCertificateName());
            System.out.printf("      platnost       : %s .. %s%n", sc.getNotBefore(), sc.getNotAfter());
            System.out.printf("      vydavatel      : %s%n",
                    sc.getSigningCertificate() != null
                            ? sc.getSigningCertificate().getReadableCertificateName() : "—");
            for (RevocationWrapper r : sc.getCertificateRevocationData()) {
                CertificateWrapper issuer = r.getSigningCertificate();
                System.out.printf("      revokace %s  %s  thisUpdate=%s  zdroj=%s%n",
                        r.getId().substring(0, 12), r.getRevocationType(),
                        r.getThisUpdate(), r.getOrigin());
                System.out.printf("           vydavatel údaje : %s%n",
                        issuer != null ? issuer.getReadableCertificateName() : "—");
                if (issuer != null) {
                    System.out.printf("           jeho platnost   : %s .. %s%n",
                            issuer.getNotBefore(), issuer.getNotAfter());
                }
            }
        }
    }
}
