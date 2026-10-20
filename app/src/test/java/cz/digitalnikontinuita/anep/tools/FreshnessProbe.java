package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import eu.europa.esig.dss.enumerations.TokenExtractionStrategy;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.validation.reports.Reports;
import eu.europa.esig.dss.validation.SignedDocumentValidator;
import eu.europa.esig.dss.model.policy.ValidationPolicy;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.policy.ValidationPolicyFacade;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Co udělá s verdiktem zapnutí kontroly čerstvosti revokačních dat.
 *
 * <p>Vzniklo nad souborem {@code LT po revokaci later LTA timestamped.pdf} — úmyslným podvrhem,
 * kde je podepsáno až po odvolání certifikátu a v dokumentu je podstrčena OCSP odpověď z doby
 * <b>před</b> podpisem. Ta odpověď říká GOOD a o stavu v okamžiku podpisu nevypovídá nic;
 * DSS ji přesto přijme, protože výchozí politika má
 * {@code <RevocationFreshness Level="IGNORE" Unit="DAYS" Value="0" />}.
 *
 * <p>Sonda proto pustí týž soubor třikrát: přísně, s ústupkem „revokační informace od již
 * expirovaného vydavatele" (jak to dělá aplikace), a s týmž ústupkem plus čerstvostí zvednutou
 * na FAIL. Třetí běh odpovídá na otázku, jestli tenhle jeden přepínač podvrh zachytí.
 *
 * <p>Politika se skládá tady a ne přes {@code ValidationPolicyFactory}, protože ta vrací hotový
 * objekt a XML už do něj nesáhne; náhrady jsou opsané z jejích metod, aby běh odpovídal tomu,
 * co dělá aplikace.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.tools.FreshnessProbe \
 *       -Dexec.args="D:\cesta\k\souboru.pdf"
 * </pre>
 */
public final class FreshnessProbe {

    private FreshnessProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: FreshnessProbe <file> [<file>...]");
            return;
        }
        AppSettings base = AppSettings.load();
        CommonCertificateVerifier verifier;
        try {
            verifier = new TrustConfig(base).buildLotlCertificateVerifier();
            System.out.println("Duveryhodne seznamy: nacteny");
        } catch (Exception e) {
            verifier = new TrustConfig(base).buildOfflineCertificateVerifier();
            System.out.println("Duveryhodne seznamy: NEDOSTUPNE (" + e + ")");
        }
        for (String a : args) {
            Path p = Path.of(a);
            System.out.println();
            System.out.println("######## " + p.getFileName());
            run(p, verifier, "prisne", false, false);
            run(p, verifier, "prisne + cerstvost FAIL", false, true);
            run(p, verifier, "ustupek expirovany vydavatel", true, false);
            run(p, verifier, "ustupek + cerstvost FAIL", true, true);
        }
    }

    private static void run(Path file, CommonCertificateVerifier verifier, String label,
                            boolean expiredRevSources, boolean freshness) {
        try {
            ValidationPolicy policy = policy(expiredRevSources, freshness);
            DSSDocument doc = new DocumentLoader().load(file.toFile());
            SignedDocumentValidator validator = SignedDocumentValidator.fromDocument(doc);
            validator.setCertificateVerifier(verifier);
            validator.setTokenExtractionStrategy(TokenExtractionStrategy.EXTRACT_ALL);
            Reports reports = validator.validateDocument(policy);
            System.out.println("── " + label);
            var sr = reports.getSimpleReport();
            for (String id : sr.getSignatureIdList()) {
                System.out.println("     " + sr.getIndication(id)
                        + (sr.getSubIndication(id) != null ? "/" + sr.getSubIndication(id) : ""));
                sr.getAdESValidationErrors(id).forEach(m ->
                        System.out.println("       chyba " + m.getKey() + ": " + m.getValue()));
                sr.getAdESValidationWarnings(id).forEach(m ->
                        System.out.println("       varov " + m.getKey() + ": " + m.getValue()));
            }
        } catch (Exception e) {
            System.out.println("── " + label + "   !! " + e);
        }
    }

    /** Výchozí politika DSS s týmiž náhradami, jaké dělá aplikace, plus volitelně čerstvost. */
    private static ValidationPolicy policy(boolean expiredRevSources, boolean freshness)
            throws Exception {
        try (InputStream in = ValidationPolicyFacade.class
                .getResourceAsStream("/policy/constraint.xml")) {
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace("\r", "\n");
            if (expiredRevSources) {
                xml = xml.replace("<RevocationIssuerNotExpired Level=\"FAIL\" />",
                                  "<RevocationIssuerNotExpired Level=\"WARN\" />");
            }
            if (freshness) {
                xml = xml.replace("<RevocationFreshness Level=\"IGNORE\" Unit=\"DAYS\" Value=\"0\" />",
                                  "<RevocationFreshness Level=\"FAIL\" Unit=\"DAYS\" Value=\"0\" />");
            }
            try (InputStream mod = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
                return ValidationPolicyFacade.newFacade().getValidationPolicy(mod);
            }
        }
    }
}
