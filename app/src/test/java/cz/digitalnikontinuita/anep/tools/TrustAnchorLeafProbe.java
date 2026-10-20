package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.TrustServiceWrapper;

import java.io.File;

/**
 * Prints, for every certificate in a signature's chain, whether DSS treated it as a trust anchor,
 * where the token came from, which trust service it matched, and what revocation data was gathered
 * for it.
 *
 * <p>Answers the question a blank revocation section raises: was revocation not checked, or was
 * there nothing to check? DSS does not ask about the revocation status of a trust anchor, and a
 * certificate reaches that status simply by being in the trusted certificate source — which for a
 * qualified validation service includes the very certificate it seals its protocols with. Where
 * that happens the chain collapses to a single entry and every revocation list in the report is
 * empty, correctly and by design.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 */
public final class TrustAnchorLeafProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: TrustAnchorLeafProbe <file>");
            return;
        }
        File f = new File(args[0]);
        AppSettings settings = AppSettings.load();
        var doc = new DocumentLoader().load(f);
        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        var outcome = new ValidatorFactory(settings).validate(doc, null, verifier, null);
        DiagnosticData dd = outcome.reports().getDiagnosticData();

        for (SignatureWrapper sw : dd.getSignatures()) {
            System.out.println("podpis " + sw.getId());
            print("  ", sw.getCertificateChain());
            System.out.println("  razítek: " + sw.getTimestampList().size());
            sw.getTimestampList().forEach(ts -> {
                System.out.println("  razítko " + ts.getId() + " (" + ts.getProductionTime() + ")");
                print("    ", ts.getCertificateChain());
            });
        }
    }

    private static void print(String indent, java.util.List<CertificateWrapper> chain) {
        for (CertificateWrapper c : chain) {
            System.out.println(indent + c.getReadableCertificateName());
            System.out.println(indent + "  důvěryhodný (kotva): " + c.isTrusted()
                    + "   vlastní podpis: " + c.isSelfSigned()
                    + "   ocsp-nocheck: " + c.isIdPkixOcspNoCheck());
            System.out.println(indent + "  zdroje tokenu      : " + c.getSources());
            System.out.println(indent + "  revokační záznamy  : " + c.getCertificateRevocationData().size());
            for (TrustServiceWrapper ts : c.getTrustServices()) {
                System.out.println(indent + "  služba v TL        : " + ts.getServiceNames()
                        + "  (" + ts.getTspNames() + ", " + ts.getCountryCode() + ")");
                System.out.println(indent + "    typ              : " + ts.getType());
                System.out.println(indent + "    stav             : " + ts.getStatus());
                var sdi = ts.getServiceDigitalIdentifier();
                System.out.println(indent + "    identita služby  : "
                        + (sdi == null ? "—" : sdi.getReadableCertificateName())
                        + (sdi != null && sdi.getId().equals(c.getId())
                                ? "   <<< TENTO CERTIFIKÁT" : ""));
            }
        }
    }
}
