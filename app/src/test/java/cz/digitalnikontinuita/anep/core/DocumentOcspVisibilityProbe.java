package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.signature.AdvancedSignature;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.validation.SignedDocumentValidator;

import java.io.File;

/**
 * Prints how many OCSP responses a document exposes through the signature's own revocation source
 * — the API {@code AugmentationService.readDocumentOcspBytes} reads, and the assumption
 * {@link DocumentOcspSource} stands on.
 *
 * <p>Worth its own probe because the assumption is not obvious: a B-T document has no {@code /DSS}
 * dictionary at all and keeps its only response inside the CMS. If DSS's source did not reach in
 * there, the whole arrangement would silently do nothing.
 *
 * <p>Reads only — no revocation lookups, no augmentation, no sizes.
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.core.DocumentOcspVisibilityProbe &lt;file&gt; [...]</pre>
 */
public final class DocumentOcspVisibilityProbe {

    public static void main(String[] args) {
        for (String arg : args) {
            File f = new File(arg);
            System.out.println("=== " + f.getName());
            try {
                DSSDocument doc = new DocumentLoader().load(f);
                SignedDocumentValidator validator = SignedDocumentValidator.fromDocument(doc);
                validator.setCertificateVerifier(new CommonCertificateVerifier());
                for (AdvancedSignature signature : validator.getSignatures()) {
                    var binaries = signature.getOCSPSource().getAllRevocationBinaries();
                    System.out.println("  podpis " + signature.getId());
                    System.out.println("    odpovědí OCSP v dokumentu: " + binaries.size());
                    binaries.forEach(b -> System.out.println("      " + b.getBinaries().length + " B"));
                    System.out.println("    CRL v dokumentu: "
                            + signature.getCRLSource().getAllRevocationBinaries().size());
                }
            } catch (Exception e) {
                System.out.println("  nepřečteno: " + e);
            }
        }
    }
}
