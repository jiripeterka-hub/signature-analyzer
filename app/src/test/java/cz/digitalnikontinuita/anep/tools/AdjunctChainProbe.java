package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.CzechCommercialCATrust;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import eu.europa.esig.dss.model.FileDocument;
import eu.europa.esig.dss.model.policy.ValidationPolicy;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.policy.ValidationPolicyFacade;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.CommonCertificateSource;
import eu.europa.esig.dss.validation.SignedDocumentValidator;
import eu.europa.esig.dss.validation.reports.Reports;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Asks whether the intermediates the application supplies as adjunct material are what lets it
 * build certificate chains the reference service cannot.
 *
 * <p>Each run gets its own verifier, because DSS writes certificates discovered during validation
 * back into the one it was given: reusing a verifier makes every later run quietly more capable
 * than the first, which is enough to invert the result being measured.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 */
public final class AdjunctChainProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: AdjunctChainProbe <file>");
            return;
        }
        Path file = Path.of(args[0]);
        String xml = new String(ValidationPolicyFacade.class
                .getResourceAsStream("/policy/constraint.xml").readAllBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace("\r", "\n")
                .replace("<Algo Date=\"2012-08-01\">SHA1</Algo>",
                         "<Algo Date=\"2099-01-01\">SHA1</Algo>");

        run("holé DSS, jen kotvy z EU seznamů", file, xml, Mod.NONE);
        run("+ vlastní úložiště důvěryhodných certifikátů", file, xml, Mod.CUSTOM_TRUST);
        run("+ vlastní úložiště CRL", file, xml, Mod.CUSTOM_CRLS);
        run("+ obojí", file, xml, Mod.BOTH);
    }

    private enum Mod { NONE, CUSTOM_TRUST, CUSTOM_CRLS, BOTH }

    private static void run(String label, Path file, String xml, Mod mod)
            throws Exception {
        CommonCertificateVerifier verifier =
                new TrustConfig(AppSettings.load()).buildLotlCertificateVerifier();
        if (mod == Mod.CUSTOM_TRUST || mod == Mod.BOTH) {
            List<CertificateToken> pinned =
                    cz.digitalnikontinuita.anep.core.CustomTrustStore.loadTrustedCertificates();
            eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource source =
                    new eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource();
            pinned.forEach(source::addCertificate);
            verifier.addTrustedCertSources(source);
            System.out.println("(vlastních kotev důvěry: " + pinned.size() + ")");
        }
        if (mod == Mod.CUSTOM_CRLS || mod == Mod.BOTH) {
            List<eu.europa.esig.dss.model.DSSDocument> crls =
                    cz.digitalnikontinuita.anep.core.CustomCrlStore.loadAsDocuments();
            verifier.setCrlSource(new cz.digitalnikontinuita.anep.core.FallbackCrlSource(
                    new cz.digitalnikontinuita.anep.core.FilteringCRLSource(verifier.getCrlSource()),
                    new cz.digitalnikontinuita.anep.core.BestMatchCustomCrlSource(crls, null)));
            System.out.println("(vlastních CRL: " + crls.size() + ")");
        }

        ValidationPolicy policy;
        try (InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            policy = ValidationPolicyFacade.newFacade().getValidationPolicy(in);
        }
        SignedDocumentValidator validator =
                SignedDocumentValidator.fromDocument(new FileDocument(file.toFile()));
        validator.setCertificateVerifier(verifier);
        Reports reports = validator.validateDocument(policy);
        SimpleReport simple = reports.getSimpleReport();

        System.out.println();
        System.out.println("--- " + label);
        for (String id : simple.getSignatureIdList()) {
            System.out.printf("   podpis   %s%s%n", simple.getIndication(id),
                    simple.getSubIndication(id) != null ? "/" + simple.getSubIndication(id) : "");
        }
        for (var ts : reports.getDiagnosticData().getTimestampList()) {
            System.out.printf("   razítko  %tF  %-7s řetěz=%d  %s%s%n", ts.getProductionTime(),
                    ts.getDigestMatchers().isEmpty() ? "?"
                            : ts.getDigestMatchers().get(0).getDigestMethod(),
                    ts.getCertificateChain().size(),
                    simple.getIndication(ts.getId()),
                    simple.getSubIndication(ts.getId()) != null
                            ? "/" + simple.getSubIndication(ts.getId()) : "");
        }
    }
}
