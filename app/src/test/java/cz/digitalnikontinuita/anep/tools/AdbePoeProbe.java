package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.diagnostic.CertificateRevocationWrapper;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.RevocationWrapper;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.util.Set;

/**
 * Komu se přiznává důkaz existence u vloženého revokačního údaje — a co z toho plyne pro lhůtu.
 *
 * <p>Otázka, kvůli které sonda vznikla: {@code ReportMapper.revocationsWithPoe()} uznává důkaz
 * existence jen od razítek archivních, dokumentových a kontejnerových. U OCSP odpovědi v poli
 * {@code SignedData.crls} je to správně — podpisové razítko ji nekryje. Odpověď v <b>podepsaném</b>
 * atributu {@code adbe-revocationInfoArchival} ale do podpisové hodnoty vstupuje, takže podpisové
 * razítko nad podpisovou hodnotou její existenci dokládá. Tahle sonda ukáže, jestli se ten rozdíl
 * na výstupu aplikace projeví, nebo jestli lhůtu drží něco jiného.
 *
 * <pre>
 *   java -Dstdout.encoding=UTF-8 -cp "app/target/test-classes;app/target/classes;$(cat cp.txt)" \
 *        cz.digitalnikontinuita.anep.tools.AdbePoeProbe "D:\cesta\k\souboru.pdf"
 * </pre>
 */
public final class AdbePoeProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: AdbePoeProbe <file> [<file>...]");
            return;
        }
        AppSettings settings = AppSettings.load();
        TrustConfig trust = new TrustConfig(settings);

        for (String arg : args) {
            File file = new File(arg);
            System.out.println();
            System.out.println("######## " + file.getName());

            CommonCertificateVerifier verifier = trust.buildLotlCertificateVerifier();
            DSSDocument doc = new DocumentLoader().load(file);
            ValidatorFactory.ValidationOutcome outcome =
                    new ValidatorFactory(settings).validate(doc, null, verifier, null);
            DiagnosticData dd = outcome.reports().getDiagnosticData();
            ValidationResult result =
                    new ReportMapper().map(file.getName(), null, outcome, settings);

            Set<String> poe = poeSet(dd);
            System.out.println("revocationsWithPoe(): " + poe);

            for (SignatureWrapper sw : dd.getSignatures()) {
                System.out.println("── podpis " + sw.getId());
                for (eu.europa.esig.dss.enumerations.RevocationOrigin o
                        : eu.europa.esig.dss.enumerations.RevocationOrigin.values()) {
                    var grp = sw.foundRevocations().getRelatedRevocationsByOrigin(o);
                    if (grp.isEmpty()) continue;
                    System.out.println("   nositel " + o + ": "
                            + grp.stream().map(x -> x.getId()).toList());
                }
                for (TimestampWrapper ts : sw.getTimestampList()) {
                    System.out.println("   razítko " + ts.getType()
                            + "  vydáno " + ts.getProductionTime()
                            + "  archivní=" + ts.getType().isArchivalTimestamp()
                            + "  dokumentové=" + ts.getType().isDocumentTimestamp());
                    for (RevocationWrapper r : ts.getTimestampedRevocations()) {
                        System.out.println("      kryje revokační údaj: " + r.getId()
                                + "  (" + r.getOrigin() + ")");
                    }
                    System.out.println("      krytých certifikátů: "
                            + ts.getTimestampedCertificates().size());
                }
                CertificateWrapper cert = sw.getSigningCertificate();
                if (cert == null) continue;
                System.out.println("   podpisový certifikát platí do " + cert.getNotAfter());
                for (CertificateRevocationWrapper r : cert.getCertificateRevocationData()) {
                    CertificateWrapper issuer = r.getSigningCertificate();
                    System.out.println("   revokační údaj " + r.getId());
                    System.out.println("      původ=" + r.getOrigin()
                            + "  vDokumentu=" + r.isInternalRevocationOrigin()
                            + "  druh=" + r.getRevocationType()
                            + "  stav=" + r.getStatus()
                            + (r.getRevocationDate() != null ? " (" + r.getRevocationDate() + ")" : ""));
                    System.out.println("      thisUpdate=" + r.getThisUpdate()
                            + "  certHash=" + r.isCertHashExtensionPresent()
                            + "/" + r.isCertHashExtensionMatch()
                            + "  archiveCutOff=" + r.getArchiveCutOff());
                    System.out.println("      vydavatel=" + (issuer != null ? issuer.getId() : "?")
                            + "  platí do " + (issuer != null ? issuer.getNotAfter() : "?"));
                    System.out.println("      má důkaz existence dle AnEP: " + poe.contains(r.getId()));
                }
            }

            for (SignatureSummary sig : result.getSignatures()) {
                System.out.println("── výstup aplikace");
                System.out.println("   verdikt: " + sig.getIndication()
                        + (sig.getSubIndication() != null ? "/" + sig.getSubIndication() : "")
                        + "  " + sig.getFormat());
                System.out.println("   situace=" + sig.getDetectedSituations()
                        + "  důvody=" + sig.getDetectedReasons());
                System.out.println("   verifiableUntil=" + sig.getVerifiableUntil());
                System.out.println("   augmentationDeadline=" + sig.getAugmentationDeadline());
                System.out.println("   unprovenRevocationDeadline=" + sig.getUnprovenRevocationDeadline());
                System.out.println("   horizont: " + ContinuityHorizonProbe.describe(sig));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> poeSet(DiagnosticData dd) throws Exception {
        var m = ReportMapper.class.getDeclaredMethod("revocationsWithPoe", DiagnosticData.class);
        m.setAccessible(true);
        return (Set<String>) m.invoke(null, dd);
    }
}
