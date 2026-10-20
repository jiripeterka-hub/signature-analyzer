package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.AugmentationService;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReliedUponRevocation;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.RestampingService;
import cz.digitalnikontinuita.anep.core.TimestampingService;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Prodloužení PDF bez okna — tytéž kroky jako {@code AdvisorView.runPreservation(PROLONG)}: přísná
 * augmentace s CRL ze situace 33 a s tím, o co se opřelo ověření, u podpisů se situací 45
 * materiál podle {@code RestampingService.speaks}, a jedno časové razítko ze zvoleného zdroje.
 *
 * <p>Vzniklo 17. 10. 2026 pro kontrolu záznamu o prodloužení: výsledný soubor se pak předá
 * {@code ActionRecordProbe} s {@code -Danep.mode=PROLONG}. Situace 44 (revokace rozhoduje) se
 * přenáší, podpis bez razítka (dvě kola z úrovně B) se tu neřeší — prodloužení se mu nenabízí.
 *
 * <pre>java ... ProlongProbe &lt;vstup.pdf&gt; &lt;výstup.pdf&gt;</pre>
 */
public final class ProlongProbe {

    public static void main(String[] args) throws Exception {
        File in = new File(args[0]);
        File out = new File(args[1]);
        AppSettings settings = AppSettings.load();
        TrustConfig tc = new TrustConfig(settings);
        CommonCertificateVerifier verifier;
        try {
            verifier = tc.buildLotlCertificateVerifier();
        } catch (Exception e) {
            verifier = tc.buildOfflineCertificateVerifier();
        }
        ValidationResult result = new ReportMapper().map(in.getName(), null,
                new ValidatorFactory(settings).validate(new DocumentLoader().load(in), null,
                        verifier, null), settings);

        List<byte[]> extraCrls = new ArrayList<>();
        for (SignatureSummary sig : result.getSignatures()) {
            if (sig.getDetectedSituations().contains(33)) extraCrls.addAll(sig.getS33CrlBytes());
        }
        extraCrls.addAll(ReliedUponRevocation.crlBytes(result.getDiagnosticData()));
        boolean decisive = result.getSignatures().stream()
                .anyMatch(s -> s.getDetectedSituations().contains(44));
        Set<String> verbatim = result.getSignatures().stream()
                .filter(s -> s.getDetectedSituations().contains(45))
                .map(SignatureSummary::getSignatureId).collect(Collectors.toSet());

        AtomicReference<CommonCertificateVerifier> ref = new AtomicReference<>(verifier);
        DSSDocument doc = new AugmentationService(ref).augment(in, null, extraCrls, decisive);
        if (!verbatim.isEmpty()) {
            var r = new RestampingService().embedAll(doc, null, result.getDiagnosticData(),
                    List.of(), verbatim);
            doc = r.document();
            System.out.println("doslovná polovina: " + r.report());
        }
        var profile = settings.getSelectedTsa();
        DSSDocument stamped = new TimestampingService(ref).addTimestamp(doc, null, profile,
                TimestampingService.resolveAlgorithm(profile.getHashAlgorithm())).doc();
        try (InputStream is = stamped.openStream(); OutputStream os = Files.newOutputStream(out.toPath())) {
            is.transferTo(os);
        }
        System.out.println("situace: " + result.getSignatures().stream()
                .map(s -> String.valueOf(s.getDetectedSituations())).collect(Collectors.joining(" | ")));
        System.out.println("zapsáno: " + out + " (" + out.length() + " B)");
        System.exit(0);
    }
}
