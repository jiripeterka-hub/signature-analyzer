package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.FileDocument;
import eu.europa.esig.dss.model.policy.ValidationPolicy;
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator;
import eu.europa.esig.dss.policy.ValidationPolicyFacade;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.validation.SignedDocumentValidator;
import eu.europa.esig.dss.validation.reports.Reports;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Validates one document under three cryptographic policies and prints what each concludes:
 * SHA-1 held to its expiry date, SHA-1 declared unexpired (what the application's "tolerate SHA-1"
 * setting does today), and SHA-1 still expired but demoted to a warning (what setting the level to
 * WARN on the reference service does).
 *
 * <p>The two relaxations are not the same instruction, and the difference only shows at archival
 * level: an expired algorithm bounds the proofs of existence that past validation may rely on, no
 * matter what level its constraint carries, whereas an unexpired one bounds nothing.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 */
public final class Sha1PolicyProbe {

    private static final String SHA1_EXPIRED = "<Algo Date=\"2012-08-01\">SHA1</Algo>";
    private static final String SHA1_UNEXPIRED = "<Algo Date=\"2099-01-01\">SHA1</Algo>";
    private static final String LEVEL_FAIL =
            "<AlgoExpirationDate Level=\"FAIL\" Format=\"yyyy-MM-dd\" "
            + "UpdateDate=\"2024-10-13\" LevelAfterUpdate=\"WARN\">";
    private static final String LEVEL_WARN =
            "<AlgoExpirationDate Level=\"WARN\" Format=\"yyyy-MM-dd\" "
            + "UpdateDate=\"2024-10-13\" LevelAfterUpdate=\"WARN\">";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: Sha1PolicyProbe <file>");
            return;
        }
        Path file = Path.of(args[0]);
        CommonCertificateVerifier verifier =
                new TrustConfig(AppSettings.load()).buildLotlCertificateVerifier();

        String base = defaultPolicyXml();
        if (!base.contains(SHA1_EXPIRED) || !base.contains(LEVEL_FAIL)) {
            System.out.println("politika nemá očekávaný tvar, ukončuji");
            return;
        }

        AppSettings first = AppSettings.load();
        first.setIgnoreSha1Deprecated(true);
        var pre = new cz.digitalnikontinuita.anep.core.ValidatorFactory(first)
                .validate(new FileDocument(file.toFile()), null, verifier, null);
        System.out.println();
        System.out.println("--- ValidatorFactory jako PRVNÍ běh v procesu");
        for (String id : pre.reports().getSimpleReport().getSignatureIdList()) {
            System.out.printf("   podpis   %s%n", pre.reports().getSimpleReport().getIndication(id));
        }

        run("dle standardů (SHA-1 expirovaná, FAIL)", file, verifier, base);
        run("jak to dělá AnEP (SHA-1 neexpirovaná do 2099)", file, verifier,
                base.replace(SHA1_EXPIRED, SHA1_UNEXPIRED));
        run("jak to dělá online služba (SHA-1 expirovaná, jen WARN)", file, verifier,
                base.replace(LEVEL_FAIL, LEVEL_WARN));

        // Same policy as the second run, but reached through the application's own pipeline.
        AppSettings tolerant = AppSettings.load();
        tolerant.setIgnoreSha1Deprecated(true);
        var outcome = new cz.digitalnikontinuita.anep.core.ValidatorFactory(tolerant)
                .validate(new FileDocument(file.toFile()), null, verifier, null);
        SimpleReport viaApp = outcome.reports().getSimpleReport();
        System.out.println();
        System.out.println("--- touž politikou, ale přes ValidatorFactory aplikace");
        for (String id : viaApp.getSignatureIdList()) {
            System.out.printf("   podpis   %s%s%n", viaApp.getIndication(id),
                    viaApp.getSubIndication(id) != null ? "/" + viaApp.getSubIndication(id) : "");
        }
        for (var ts : outcome.reports().getDiagnosticData().getTimestampList()) {
            System.out.printf("   razítko  %s  %-18s %s%s%n", ts.getProductionTime(),
                    ts.getDigestMatchers().isEmpty() ? "?"
                            : ts.getDigestMatchers().get(0).getDigestMethod(),
                    viaApp.getIndication(ts.getId()),
                    viaApp.getSubIndication(ts.getId()) != null
                            ? "/" + viaApp.getSubIndication(ts.getId()) : "");
        }
    }

    private static void run(String label, Path file, CommonCertificateVerifier verifier, String xml)
            throws Exception {
        ValidationPolicy policy;
        try (InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            policy = ValidationPolicyFacade.newFacade().getValidationPolicy(in);
        }
        DSSDocument doc = new FileDocument(file.toFile());
        SignedDocumentValidator validator = SignedDocumentValidator.fromDocument(doc);
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
            System.out.printf("   razítko  %s  %-18s %s%s%n", ts.getProductionTime(),
                    ts.getDigestMatchers().isEmpty() ? "?"
                            : ts.getDigestMatchers().get(0).getDigestMethod(),
                    simple.getIndication(ts.getId()),
                    simple.getSubIndication(ts.getId()) != null
                            ? "/" + simple.getSubIndication(ts.getId()) : "");
        }
    }

    private static String defaultPolicyXml() throws Exception {
        try (InputStream in = ValidationPolicyFacade.class
                .getResourceAsStream("/policy/constraint.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace("\r", "\n");
        }
    }
}
