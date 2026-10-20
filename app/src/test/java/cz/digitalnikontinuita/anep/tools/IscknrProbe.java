package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import eu.europa.esig.dss.diagnostic.CertificateRevocationWrapper;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.detailedreport.DetailedReport;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;

/**
 * Co knihovna DSS opravdu řekne o LTV_ISCKNR nad jedním souborem, s ústupkem i bez něj.
 *
 * <p>Sonda k jedné otázce: co u platného podpisu znamená připomínka {@code LTV_ISCKNR_ANS1}.
 * Vypisuje verdikt, varování, celý detailní protokol (kde je u téhle kontroly i doplňující
 * informace s dobou platnosti vydavatele revokace) a revokační údaje podpisového certifikátu
 * s tím, kdo je vydal a dokdy ten vydavatel platil.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.tools.IscknrProbe \
 *       -Dexec.args="D:\cesta\k\souboru.pdf"
 * </pre>
 */
public final class IscknrProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: IscknrProbe <file>");
            return;
        }
        File file = new File(args[0]);
        for (boolean lenient : new boolean[] { false, true }) {
            System.out.println("==================================================================");
            System.out.println("ustupek 'expirovany vydavatel revokace' = " + lenient);
            System.out.println("==================================================================");
            run(file, lenient);
        }
    }

    private static void run(File file, boolean lenient) throws Exception {
        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(false);
        settings.setAcceptExpiredRevocationSources(lenient);
        settings.setAcceptPdfNotEtsi(false);

        DSSDocument doc = new DocumentLoader().load(file);
        CommonCertificateVerifier verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        ValidationOutcome outcome = new ValidatorFactory(settings).validate(doc, null, verifier, null);

        SimpleReport sr = outcome.reports().getSimpleReport();
        DetailedReport dr = outcome.reports().getDetailedReport();
        DiagnosticData dd = outcome.reports().getDiagnosticData();

        for (String id : sr.getSignatureIdList()) {
            System.out.println("-- podpis " + id);
            System.out.println("   indikace  : " + sr.getIndication(id) + " / " + sr.getSubIndication(id));
            sr.getAdESValidationErrors(id).forEach(m -> System.out.println("   CHYBA     : " + m.getKey() + " = " + m.getValue()));
            sr.getAdESValidationWarnings(id).forEach(m -> System.out.println("   VAROVANI  : " + m.getKey() + " = " + m.getValue()));
            System.out.println("   LTV indikace v detailnim protokolu: "
                    + dr.getLongTermValidationIndication(id) + " / " + dr.getLongTermValidationSubIndication(id));
            System.out.println("   ARCH indikace: "
                    + dr.getArchiveDataValidationIndication(id) + " / " + dr.getArchiveDataValidationSubIndication(id));

            SignatureWrapper sw = dd.getSignatureById(id);
            CertificateWrapper signing = sw.getSigningCertificate();
            if (signing != null) {
                System.out.println("   podpisovy cert : " + signing.getId());
                System.out.println("      platnost    : " + signing.getNotBefore() + " .. " + signing.getNotAfter());
                for (CertificateRevocationWrapper r : signing.getCertificateRevocationData()) {
                    CertificateWrapper issuer = r.getSigningCertificate();
                    System.out.println("      revokace " + r.getId());
                    System.out.println("         typ/puvod  : " + r.getRevocationType() + " / " + r.getOrigin()
                            + " (vlozena=" + r.isInternalRevocationOrigin() + ")");
                    System.out.println("         stav       : " + r.getStatus());
                    System.out.println("         thisUpdate : " + r.getThisUpdate()
                            + "  produkce: " + r.getProductionDate());
                    System.out.println("         vydavatel  : " + (issuer == null ? "?" : issuer.getId()));
                    if (issuer != null) {
                        System.out.println("            platnost : " + issuer.getNotBefore() + " .. " + issuer.getNotAfter());
                        System.out.println("            kotva    : " + issuer.isTrusted());
                    }
                }
            }
            for (TimestampWrapper ts : sw.getTimestampList()) {
                System.out.println("   razitko " + ts.getType() + " cas " + ts.getProductionTime()
                        + " kryje cert.: " + ts.getTimestampedCertificates().size()
                        + ", revokace: " + ts.getTimestampedRevocations().size());
            }
        }
        System.out.println("   cas overeni (validation time): " + dd.getValidationDate());

        // Celý detailní protokol; kontrolu LTV_ISCKNR v něm poznáme podle klíče, a hned vedle ní
        // stojí AdditionalInfo s dobou platnosti vydavatele revokace a s časem, ke kterému se měří.
        String xml = outcome.reports().getXmlDetailedReport();
        for (String line : xml.split("\\R")) {
            if (line.contains("ISCKNR") || line.contains("REVOCATION_CERT_VALIDITY")
                    || line.contains("ValidationProcessLongTermData")
                    || line.contains("ICTIVRCIRI") || line.contains("IRDPFC")
                    || line.contains("PSV_DIURDSCHPVR") || line.contains("ARCH_LTAIVMP")) {
                System.out.println("   XML: " + line.trim());
            }
        }
    }
}
