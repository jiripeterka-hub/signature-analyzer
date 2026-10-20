package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.diagnostic.CertificateRevocationWrapper;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Dumps, for one file, every piece of revocation data DSS ended up holding about each certificate
 * in the signing chain — under strict settings and under each lenient setting separately.
 *
 * <p>Written to answer "what does AnEP actually see?" when a document validates as INDETERMINATE
 * under the standards but turns valid once a lenient switch is flipped: the interesting part is
 * which revocation tokens exist in the diagnostic data in each mode, whether they are authoritative
 * for the certificate they cover, and which situations AnEP derives from them. Reasoning about DSS
 * internals is no substitute for looking.
 *
 * <pre>
 *   java -cp ... cz.digitalnikontinuita.anep.tools.RevocationProbe "D:\path\to\file.pdf"
 * </pre>
 */
public final class RevocationProbe {

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: RevocationProbe <file> [<file>...]");
            return;
        }

        TrustConfig trustConfig = new TrustConfig(AppSettings.load());
        CommonCertificateVerifier verifier;
        try {
            verifier = trustConfig.buildLotlCertificateVerifier();
            System.out.println("Trusted Lists: loaded");
        } catch (Exception e) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
            System.out.println("Trusted Lists: UNAVAILABLE (" + e + ")");
        }

        for (String arg : args) {
            Path p = Path.of(arg);
            System.out.println();
            System.out.println("############ " + p.getFileName());
            run(p, verifier, "PŘÍSNĚ (bez ústupků)", false, false);
            run(p, verifier, "acceptNewerOcsp = true", true, false);
            run(p, verifier, "acceptExpiredRevocationSources = true", false, true);
            run(p, verifier, "acceptPdfNotEtsi = true", false, false, true);
        }
    }

    private static void run(Path file, CommonCertificateVerifier verifier, String label,
                            boolean newerOcsp, boolean expiredRevSources) {
        run(file, verifier, label, newerOcsp, expiredRevSources, false);
    }

    private static void run(Path file, CommonCertificateVerifier verifier, String label,
                            boolean newerOcsp, boolean expiredRevSources, boolean pdfNotEtsi) {
        System.out.println();
        System.out.println("── " + label + " ───────────────────────────────");
        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(newerOcsp);
        settings.setAcceptExpiredRevocationSources(expiredRevSources);
        settings.setAcceptPdfNotEtsi(pdfNotEtsi);
        try {
            DSSDocument doc = new DocumentLoader().load(file.toFile());
            ValidationOutcome outcome = new ValidatorFactory(settings)
                    .validate(doc, null, verifier, null);
            ValidationResult result = new ReportMapper()
                    .map(file.getFileName().toString(), null, outcome, settings);
            DiagnosticData dd = outcome.reports().getDiagnosticData();

            for (SignatureSummary sig : result.getSignatures()) {
                System.out.println("  " + sig.getIndication()
                        + (sig.getSubIndication() != null ? "/" + sig.getSubIndication() : "")
                        + "   " + sig.getFormat()
                        + "   situace=" + sig.getDetectedSituations()
                        + "   důvody=" + sig.getDetectedReasons());
            }
            System.out.println("  přeskočené revokace (skippedRevocations): "
                    + outcome.skippedRevocations().size());

            // DSS's own error keys. Worth printing next to the situations: they name the constraint
            // that actually failed, and a verdict with no situation to explain it is usually a
            // situation that is not looking where DSS looked — ASCCM_AR_ANS_ANR ("SHA1 … for object
            // reference") against an empty situation list is how the reference-digest blind spot in
            // usesSha1() finally showed itself.
            for (String id : simpleReport(outcome).getSignatureIdList()) {
                simpleReport(outcome).getAdESValidationErrors(id).forEach(m ->
                        System.out.println("  chyba DSS " + m.getKey() + ": " + m.getValue()));
            }

            for (SignatureWrapper sw : dd.getSignatures()) {
                // Digest algorithms of what is signed — a separate choice from the signature
                // algorithm, and the one the cryptographic constraints judge.
                for (var dm : sw.getDigestMatchers()) {
                    System.out.println("    otisk " + dm.getType()
                            + (dm.getUri() != null ? " " + dm.getUri() : "")
                            + "  alg=" + dm.getDigestMethod());
                }
                for (CertificateWrapper c : sw.getCertificateChain()) {
                    dumpCert(c, "    ");
                }
                // S31 reads the timestamps, so their chains matter as much as the signing one.
                for (var ts : sw.getTimestampList()) {
                    System.out.println("    RAZÍTKO " + ts.getType()
                            + "  čas=" + fmt(ts.getProductionTime())
                            + "  podpisOK=" + ts.isSignatureValid()
                            + "  otisekOK=" + ts.isMessageImprintDataFound());
                    for (CertificateWrapper c : ts.getCertificateChain()) {
                        dumpCert(c, "        ");
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("  SELHALO: " + e);
        }
    }

    private static eu.europa.esig.dss.simplereport.SimpleReport simpleReport(ValidationOutcome o) {
        return o.reports().getSimpleReport();
    }

    private static void dumpCert(CertificateWrapper c, String indent) {
        Date notAfter = c.getNotAfter();
        System.out.println(indent + c.getReadableCertificateName()
                + "   notAfter=" + fmt(notAfter)
                + (c.isTrusted() ? "  [kotva důvěry]" : "")
                + (c.isIdPkixOcspNoCheck() ? "  [ocsp-nocheck]" : ""));
        List<CertificateRevocationWrapper> revs = c.getCertificateRevocationData();
        if (revs.isEmpty()) {
            System.out.println(indent + "    (žádná revokační data)");
            return;
        }
        for (CertificateRevocationWrapper r : revs) {
            Date thisUpdate = r.getThisUpdate();
            boolean postExpiry = thisUpdate != null && notAfter != null && thisUpdate.after(notAfter);
            Date eco = r.getExpiredCertsOnCRL();
            System.out.println(indent + "    " + r.getRevocationType()
                    + "  " + (r.isInternalRevocationOrigin() ? "v dokumentu" : "staženo")
                    + "  status=" + r.getStatus()
                    + "  thisUpdate=" + fmt(thisUpdate)
                    + (postExpiry ? "  ⚠ PO EXPIRACI" : "")
                    + (eco != null ? "  eco=" + fmt(eco) : "  eco=—")
                    // CertHash je pro OCSP totéž, co expiredCertsOnCRL pro seznam: dokládá, že
                    // respondér zná právě tenhle certifikát, a DSS podle něj pouští i odpověď
                    // vydanou po expiraci (RevocationDataKnownCheck).
                    + "  certHash=" + (r.isCertHashExtensionPresent()
                            ? (r.isCertHashExtensionMatch() ? "sedí" : "NESEDÍ") : "—")
                    + "  → autoritativní: " + (authoritative(r, notAfter) ? "ANO" : "NE"));
            // Who issued the answer and until when that issuer's own chain is good. This is the
            // S5 predicate's input, and without it a wrongly-fired S5 cannot be told apart from a
            // rightly-fired one by reading the output.
            List<CertificateWrapper> issuerChain = r.getCertificateChain();
            if (issuerChain.isEmpty()) {
                System.out.println(indent + "        vydavatel: (řetěz prázdný)");
            } else {
                Date chainExpiry = null;
                StringBuilder names = new StringBuilder();
                for (CertificateWrapper ic : issuerChain) {
                    if (names.length() > 0) names.append(" ← ");
                    names.append(ic.getReadableCertificateName());
                    if (ic.isTrusted()) { names.append(" [kotva]"); break; }
                    if (ic.getNotAfter() != null
                            && (chainExpiry == null || ic.getNotAfter().before(chainExpiry))) {
                        chainExpiry = ic.getNotAfter();
                    }
                }
                boolean expiredIssuer = chainExpiry != null && chainExpiry.before(new Date());
                System.out.println(indent + "        vydavatel: " + names
                        + "   platí do " + fmt(chainExpiry)
                        + (expiredIssuer ? "  ⚠ VYDAVATEL EXPIROVAL" : ""));
            }
        }
    }

    /**
     * Zrcadlo pravidla DSS ({@code doesRevocationKnowCertificate}): údaj vydaný za platnosti
     * certifikátu, nebo — po expiraci — údaj, který o něm prokazatelné ví: {@code
     * expiredCertsOnCRL}, {@code archiveCutOff}, nebo odpovídající {@code CertHash}.
     */
    private static boolean authoritative(CertificateRevocationWrapper r, Date notAfter) {
        Date thisUpdate = r.getThisUpdate();
        if (thisUpdate == null || notAfter == null) return false;
        if (!thisUpdate.after(notAfter)) return true;
        Date eco = r.getExpiredCertsOnCRL();
        if (eco != null && !eco.after(notAfter)) return true;
        Date cutOff = r.getArchiveCutOff();
        if (cutOff != null && !cutOff.after(notAfter)) return true;
        return r.isCertHashExtensionPresent() && r.isCertHashExtensionMatch();
    }

    private static String fmt(Date d) { return d == null ? "—" : FMT.format(d); }
}
