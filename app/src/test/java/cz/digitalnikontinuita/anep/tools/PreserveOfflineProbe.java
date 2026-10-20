package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.AugmentationService;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReliedUponRevocation;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TimestampingService;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.TsaProfile;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Vydrží uchovaný dokument bez sítě? Změří to místo dohadování.
 *
 * <p>Otázka vznikla u podpisu B-T s OCSP odpovědí v podepsaném atributu
 * {@code adbe-revocationInfoArchival}, jejíž respondér mezitím expiroval. Lhůta „do kdy je třeba
 * zasáhnout" stojí na tom, že uchování takový dokument spraví — a jestli ho opravdu spraví, se
 * pozná jedině tak, že se úkon provede a výsledek se ověří <b>bez sítě</b>. Se sítí by se totiž
 * závěr mohl opřít o čerstvě staženou odpověď a nic by to nedokládalo.
 *
 * <p>Vstupní soubor se nemění; výsledek se zapíše vedle něj do zadané složky. Úkon si vyžádá
 * jedno skutečné časové razítko od zdroje vybraného v nastavení.
 *
 * <pre>
 *   java -Dstdout.encoding=UTF-8 -cp "app/target/test-classes;app/target/classes;$(cat cp.txt)" \
 *        cz.digitalnikontinuita.anep.tools.PreserveOfflineProbe &lt;soubor.pdf&gt; &lt;výstupní složka&gt;
 * </pre>
 */
public final class PreserveOfflineProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: PreserveOfflineProbe <soubor.pdf> <výstupní složka>");
            return;
        }
        File input = Path.of(args[0]).toFile();
        Path outDir = Path.of(args[1]);

        AppSettings settings = AppSettings.load();
        TsaProfile profile = settings.getSelectedTsa();
        if (profile == null) {
            System.err.println("V nastavení není vybrán zdroj časových razítek — úkon nelze provést.");
            return;
        }
        System.out.println("zdroj razítek: " + profile.getName() + "  " + profile.getUrl());

        TrustConfig trust = new TrustConfig(settings);

        // 1) Původní dokument — se sítí i bez ní.
        System.out.println();
        System.out.println("======== PŮVODNÍ " + input.getName());
        ValidationResult before = validate(input, settings, trust, false, "se sítí");
        validate(input, settings, trust, true, "BEZ SÍTĚ");

        // 2) Uchování — týmž sledem, jakým ho dělá aplikace: materiál dovnitř, pak razítko.
        List<byte[]> extraCrls = new ArrayList<>();
        for (SignatureSummary sig : before.getSignatures()) {
            if (sig.getDetectedSituations().contains(33)) {
                extraCrls.addAll(sig.getS33CrlBytes());
            }
        }
        extraCrls.addAll(ReliedUponRevocation.crlBytes(before.getDiagnosticData()));
        boolean revocationDecisive = before.getSignatures().stream()
                .anyMatch(s -> s.getDetectedSituations().contains(44));
        System.out.println();
        System.out.println("uchování: extraCrls=" + extraCrls.size()
                + "  revocationDecisive=" + revocationDecisive);

        CommonCertificateVerifier verifier = trust.buildLotlCertificateVerifier();
        AugmentationService aug = new AugmentationService(new AtomicReference<>(verifier));
        DSSDocument augmented = aug.augment(input, null, extraCrls, revocationDecisive);

        TimestampingService tsa = new TimestampingService(new AtomicReference<>(verifier));
        DigestAlgorithm alg = TimestampingService.resolveAlgorithm(profile.getHashAlgorithm());
        DSSDocument stamped = tsa.addTimestamp(augmented, null, profile, alg).doc();

        String name = input.getName().replaceAll("\\.pdf$", "") + "_Uchovano_sonda.pdf";
        File out = outDir.resolve(name).toFile();
        stamped.save(out.getAbsolutePath());
        System.out.println("zapsáno: " + out + "  (" + input.length() + " B → " + out.length() + " B)");

        // 3) Výsledek — se sítí i bez ní. Ta druhá odpověď je ta, o kterou jde.
        System.out.println();
        System.out.println("======== PO UCHOVÁNÍ " + out.getName());
        validate(out, settings, trust, false, "se sítí");
        validate(out, settings, trust, true, "BEZ SÍTĚ");
    }

    private static ValidationResult validate(File file, AppSettings settings, TrustConfig trust,
                                             boolean offline, String popis) throws Exception {
        CommonCertificateVerifier verifier = trust.buildLotlCertificateVerifier();
        if (offline) {
            verifier.setOcspSource(null);
            verifier.setCrlSource(null);
        }
        DSSDocument doc = new DocumentLoader().load(file);
        ValidationResult result = new ReportMapper().map(file.getName(), null,
                new ValidatorFactory(settings).validate(doc, null, verifier, null), settings);
        System.out.println("── " + popis + " ─────────────");
        for (SignatureSummary sig : result.getSignatures()) {
            System.out.println("   " + sig.getIndication()
                    + (sig.getSubIndication() != null ? "/" + sig.getSubIndication() : "")
                    + "  " + sig.getFormat()
                    + "  situace=" + sig.getDetectedSituations()
                    + "  důvody=" + sig.getDetectedReasons());
            System.out.println("      BST=" + sig.getBestSignatureTime());
            for (SignatureSummary.TimestampEntry ts : sig.getAllTimestamps()) {
                System.out.println("      razítko " + ts.tsKind() + " z " + ts.time()
                        + "  prošlo=" + ts.passed() + "  neporušené=" + ts.intact()
                        + "  kvalifikované=" + ts.qualified()
                        + "  cert do " + ts.notAfter());
            }
            System.out.println("      verifiableUntil=" + sig.getVerifiableUntil());
            var ad = sig.getAugmentationDeadline();
            System.out.println("      augmentationDeadline=" + (ad != null ? ad.when() : null));
            var ur = sig.getUnprovenRevocationDeadline();
            System.out.println("      unprovenRevocationDeadline=" + (ur != null ? ur.when() : null));
            System.out.println("      horizont: " + ContinuityHorizonProbe.describe(sig));
        }
        return result;
    }
}
