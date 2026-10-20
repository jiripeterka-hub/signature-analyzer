package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.validation.reports.Reports;

import java.io.File;

/**
 * Co přesně DSS vytýká PDF s certifikačním podpisem.
 *
 * <p>Vzniklo nad „certifikacni a dva uznavane 2015 10 28 do 2026 10.pdf“: online DSS i AnEP
 * hlásily u certifikačního podpisu porušenou integritu, zatímco Adobe Reader, Signer i CzechPOINT
 * viděly dokument v pořádku (jen „provedeny povolené změny“). Ručně spočítaný otisk přes ByteRange
 * přitom sedí, takže odpověď musí být v tom, co k tomu DSS říká samo — a to jinde než v protokolu
 * nenajdeme.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.ui.PdfMdpProbe \
 *       -Dexec.args="C:\cesta\bez\mezer.pdf"
 * </pre>
 */
public final class PdfMdpProbe {

    private PdfMdpProbe() { }

    public static void main(String[] args) throws Exception {
        AppSettings settings = AppSettings.load();
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();
        File f = new File(args[0]);
        DSSDocument doc = new DocumentLoader().load(f);
        Reports reports = new ValidatorFactory(settings)
                .validate(doc, null, verifier, null).reports();

        // Nálezy AnEP vedle surového DSS: situace 1 a 53 se rozcházejí právě podle toho, co
        // z DSS níž čte tahle sonda (dataIntact × isSignatureIntact).
        var mapped = new cz.digitalnikontinuita.anep.core.ReportMapper().map(f.getName(), null,
                new ValidatorFactory(settings).validate(doc, null, verifier, null), settings);
        for (var sig : mapped.getSignatures()) {
            System.out.println("AnEP " + sig.getSignatureId() + "  " + sig.getIndication()
                    + "  situace=" + sig.getDetectedSituations()
                    + "  důvody=" + sig.getDetectedReasons()
                    + "  certifikační=" + sig.isCertification()
                    + " (" + sig.getCertificationPermission() + ")"
                    + "  druh=" + sig.getLegalType());
        }

        SimpleReport simple = reports.getSimpleReport();
        DiagnosticData dd = reports.getDiagnosticData();

        for (String id : simple.getSignatureIdList()) {
            SignatureWrapper sw = dd.getSignatureById(id);
            System.out.println("\n================ " + id + " ================");
            System.out.println("  formát        : " + simple.getSignatureFormat(id));
            System.out.println("  indikace      : " + simple.getIndication(id)
                    + " / " + simple.getSubIndication(id));
            System.out.println("  podepsal      : " + simple.getSignedBy(id));
            System.out.println("  signatureIntact: " + sw.isSignatureIntact()
                    + " | signatureValid: " + sw.isSignatureValid()
                    + " | digestMatcher(s):");
            sw.getDigestMatchers().forEach(dm -> System.out.println("      - " + dm.getType()
                    + "  dataFound=" + dm.isDataFound() + " dataIntact=" + dm.isDataIntact()
                    + " algo=" + dm.getDigestMethod()));
            System.out.println("  algoritmy DSS : signature=" + sw.getSignatureAlgorithm()
                    + " digest=" + sw.getDigestAlgorithm()
                    + " encryption=" + sw.getEncryptionAlgorithm());
            System.out.println("  chyby ETSI    : " + msgs(simple.getAdESValidationErrors(id)));
            System.out.println("  varování      : " + msgs(simple.getAdESValidationWarnings(id)));
            System.out.println("  info          : " + msgs(simple.getAdESValidationInfo(id)));

            var rev = sw.getPDFRevision();
            if (rev != null) {
                System.out.println("  --- PDF revize ---");
                System.out.println("   pole        : " + rev.getSignatureFieldNames());
                System.out.println("   ByteRange   : " + rev.getSignatureByteRange());
                System.out.println("   DocMDP      : " + rev.getDocMDPPermissions());
                System.out.println("   FieldMDP    : " + rev.getFieldMDP());
                System.out.println("   SigFieldLock: " + rev.getSigFieldLock());
                System.out.println("   ByteRange platný : " + rev.isSignatureByteRangeValid());
                System.out.println("   slovník konzist. : "
                        + rev.isPdfSignatureDictionaryConsistent());
                System.out.println("   změny detekovány : " + rev.arePdfModificationsDetected()
                        + " | objektové: " + rev.arePdfObjectModificationsDetected());
                System.out.println("   překryv anotací  : "
                        + rev.getPdfAnnotationsOverlapConcernedPages());
                System.out.println("   vizuální rozdíl  : "
                        + rev.getPdfVisualDifferenceConcernedPages());
                System.out.println("   rozdíl stran     : "
                        + rev.getPdfPageDifferenceConcernedPages());
                System.out.println("   změněná pole     : " + rev.getModifiedFieldNames());
                System.out.println("   rozšíření        : " + names(rev.getPdfExtensionChanges()));
                System.out.println("   podpis/formulář  : "
                        + names(rev.getPdfSignatureOrFormFillChanges()));
                System.out.println("   anotace          : " + names(rev.getPdfAnnotationChanges()));
                System.out.println("   NEDEFINOVANÉ     : " + names(rev.getPdfUndefinedChanges()));
            }
        }
    }

    /** Hlášky DSS textem, ne odkazem na objekt. */
    private static java.util.List<String> msgs(
            java.util.List<eu.europa.esig.dss.jaxb.object.Message> messages) {
        return messages == null ? java.util.List.of()
                : messages.stream().map(m -> m.getKey() + ": " + m.getValue()).toList();
    }

    /** Jména objektových změn — celý XmlObjectModification je na výpis moc ukecaný. */
    private static java.util.List<String> names(
            java.util.List<eu.europa.esig.dss.diagnostic.jaxb.XmlObjectModification> mods) {
        return mods == null ? java.util.List.of()
                : mods.stream().map(m -> m.getAction() + " " + m.getType()
                        + " " + m.getFieldName() + " → " + m.getValue()).toList();
    }
}
