package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.pades.signature.PAdESExtensionService;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * Writes out what DSS's {@code incorporateValidationData} produces, before
 * {@code AugmentationService.postProcessDss} has taken anything back out of the DSS dictionary.
 *
 * <p>Exists because the two stages disagree about a document's size in a way only the intermediate
 * bytes can explain: preservation of a 172 kB PDF grew it by 237 kB while the DSS dictionary of
 * the result holds one 2 kB OCSP response and three certificates. Something large is fetched,
 * written, and then dereferenced — and an incremental save cannot take bytes back out, so it stays
 * in the file as dead weight. Dumping this file with {@code DssDictionaryProbe} names it.
 *
 * <p>Lives in {@code cz.digitalnikontinuita.anep.core} for access to the package-private
 * {@code buildPreservationVerifier}, so that the run matches what preservation really does.
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.core.IntermediateDssProbe &lt;in.pdf&gt; &lt;out.pdf&gt;</pre>
 */
public final class IntermediateDssProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: IntermediateDssProbe <in.pdf> <out.pdf>");
            return;
        }
        File in = new File(args[0]);
        AppSettings settings = AppSettings.load();
        CommonCertificateVerifier base = new TrustConfig(settings).buildLotlCertificateVerifier();

        DSSDocument doc = new DocumentLoader().load(in);
        CommonCertificateVerifier augVerifier = AugmentationService.buildPreservationVerifier(
                base, java.util.List.of(), java.util.List.of(),
                ValidatorFactory.earliestTimestampTime(doc, null, null));

        DSSDocument augmented = new PAdESExtensionService(augVerifier)
                .incorporateValidationData(doc, null);

        try (OutputStream os = Files.newOutputStream(new File(args[1]).toPath())) {
            augmented.writeTo(os);
        }
        System.out.println("vstup     : " + in.length() + " B");
        System.out.println("mezistav  : " + new File(args[1]).length() + " B");
        System.out.println("zapsáno   : " + args[1]);
    }
}
