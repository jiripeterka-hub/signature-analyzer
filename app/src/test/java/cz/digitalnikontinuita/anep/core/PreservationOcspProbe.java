package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource;
import eu.europa.esig.dss.spi.DSSUtils;
import eu.europa.esig.dss.spi.validation.RevocationDataVerifier;
import eu.europa.esig.dss.spi.x509.revocation.RevocationToken;

import java.io.File;
import java.util.List;

/**
 * Asks the OCSP responder for a document's signing certificate twice — once through the data
 * loader validation uses and once through the one preservation uses — and then asks DSS's
 * revocation-data verifier whether the answer is acceptable.
 *
 * <p>Written after a document validated with a fresh online OCSP was preserved with a 282 kB CRL
 * for the same certificate and no OCSP at all. {@code OcspFirstCrlFallbackRevocationDataLoadingStrategy}
 * reaches the CRL branch only when {@code checkOCSP} returns nothing, and that method collapses
 * three different outcomes — a transport failure, an empty answer, and a token the verifier
 * refuses — into the same null. This probe keeps them apart.
 *
 * <p>Lives in {@code cz.digitalnikontinuita.anep.core} because {@link TrustConfig#createDataLoader()} is
 * package-private, and the whole point is to compare it against DSS's default.
 *
 * <p>Run it the way the application runs, or the comparison is meaningless — {@code run.bat} sets
 * {@code javax.net.ssl.trustStore} to {@code ~/.jdk-cacerts}, which changes what the default
 * loader trusts:
 *
 * <pre>
 *   java -Djavax.net.ssl.trustStore=%USERPROFILE%\.jdk-cacerts \
 *        -Djavax.net.ssl.trustStorePassword=changeit \
 *        -cp ... cz.digitalnikontinuita.anep.core.PreservationOcspProbe &lt;file.pdf&gt;
 * </pre>
 */
public final class PreservationOcspProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: PreservationOcspProbe <file.pdf>");
            return;
        }
        System.out.println("javax.net.ssl.trustStore = "
                + System.getProperty("javax.net.ssl.trustStore", "(nenastaven)"));

        File f = new File(args[0]);
        AppSettings settings = AppSettings.load();
        var doc = new DocumentLoader().load(f);
        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        var outcome = new ValidatorFactory(settings).validate(doc, null, verifier, null);
        DiagnosticData dd = outcome.reports().getDiagnosticData();

        for (SignatureWrapper sw : dd.getSignatures()) {
            List<CertificateWrapper> chain = sw.getCertificateChain();
            CertificateWrapper certW = sw.getSigningCertificate();
            if (certW == null || chain.size() < 2) continue;
            CertificateWrapper issuerW = chain.stream()
                    .filter(c -> !c.getId().equals(certW.getId()))
                    .findFirst().orElse(null);
            if (issuerW == null) continue;

            CertificateToken cert = DSSUtils.loadCertificate(certW.getBinaries());
            CertificateToken issuer = DSSUtils.loadCertificate(issuerW.getBinaries());
            System.out.println("certifikát : " + certW.getReadableCertificateName());
            System.out.println("vydavatel  : " + issuerW.getReadableCertificateName());
            System.out.println("revokace nalezená při ověření: "
                    + certW.getCertificateRevocationData().size());
            certW.getCertificateRevocationData().forEach(r -> System.out.println(
                    "   " + r.getRevocationType() + ", thisUpdate " + r.getThisUpdate()));

            ask("výchozí loader DSS (ověřování)", new OnlineOCSPSource(), cert, issuer);

            OnlineOCSPSource generic = new OnlineOCSPSource();
            generic.setDataLoader(TrustConfig.createDataLoader());
            ask("createDataLoader() — obecný, bez Content-Type", generic, cert, issuer);

            OnlineOCSPSource configured = new OnlineOCSPSource();
            configured.setDataLoader(TrustConfig.createOcspDataLoader());
            ask("createOcspDataLoader() — s application/ocsp-request", configured, cert, issuer);
        }
    }

    private static void ask(String label, OnlineOCSPSource source,
                            CertificateToken cert, CertificateToken issuer) {
        System.out.println("--- " + label);
        RevocationToken<?> token;
        try {
            token = source.getRevocationToken(cert, issuer);
        } catch (Exception e) {
            System.out.println("    selhalo: " + rootCause(e));
            return;
        }
        if (token == null) {
            System.out.println("    responder nevrátil nic");
            return;
        }
        System.out.println("    stav " + token.getStatus()
                + ", thisUpdate " + token.getThisUpdate()
                + ", nextUpdate " + token.getNextUpdate());
        RevocationDataVerifier rdv = RevocationDataVerifier.createDefaultRevocationDataVerifier();
        System.out.println("    isAcceptable: " + rdv.isAcceptable(token));
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return t.getClass().getSimpleName() + " → " + c.getClass().getSimpleName()
                + ": " + c.getMessage();
    }
}
