package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;

/**
 * Ověří dokument dvakrát: normálně a <b>bez sítě</b> — s odpojenými zdroji revokačních údajů.
 *
 * <p>Odpovídá na otázku, kterou blok „Jak dlouho dokáže AnEP ověřit platnost?“ zodpovídá datem:
 * co zbude, až přestane být dostupná odpověď, kterou se dnes podařilo stáhnout. Dohadovat se to
 * nedá — dokument buď obstojí z vlastních zásob, nebo ne, a to se pozná jedině tím, že se mu
 * síť vezme.
 *
 * <p>Kotvy důvěry zůstávají (berou se z uložených důvěryhodných seznamů), takže se neměří
 * nedostupnost seznamů, nýbrž jen nedostupnost revokačních údajů.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.tools.OfflineHorizonProbe \
 *       -Dexec.args="D:\cesta\k\souboru.pdf"
 * </pre>
 */
public final class OfflineHorizonProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: OfflineHorizonProbe <file> [<file>...]");
            return;
        }
        AppSettings settings = AppSettings.load();
        TrustConfig trust = new TrustConfig(settings);

        for (String arg : args) {
            File file = new File(arg);
            System.out.println();
            System.out.println("######## " + file.getName());
            run(file, settings, trust, false);
            run(file, settings, trust, true);
        }
    }

    private static void run(File file, AppSettings settings, TrustConfig trust, boolean offline)
            throws Exception {
        CommonCertificateVerifier verifier = trust.buildLotlCertificateVerifier();
        if (offline) {
            // Zdroje se odpojují až po sestavení, aby zůstalo všechno ostatní stejné —
            // kotvy důvěry, politika i nastavení. Bez nich má DSS k dispozici jen to, co nese
            // sám dokument.
            verifier.setOcspSource(null);
            verifier.setCrlSource(null);
        }
        DSSDocument doc = new DocumentLoader().load(file);
        ValidationResult result = new ReportMapper().map(file.getName(), null,
                new ValidatorFactory(settings).validate(doc, null, verifier, null), settings);
        System.out.println("── " + (offline ? "BEZ SÍTĚ" : "normálně") + " ─────────────");
        for (SignatureSummary sig : result.getSignatures()) {
            System.out.println("  " + sig.getIndication()
                    + (sig.getSubIndication() != null ? "/" + sig.getSubIndication() : "")
                    + "  " + sig.getFormat()
                    + "  situace=" + sig.getDetectedSituations()
                    + "  důvody=" + sig.getDetectedReasons());
            var vc = sig.getValidationCourse();
            if (vc != null && vc.signingCertRevocation() != null) {
                var r = vc.signingCertRevocation();
                System.out.println("      využitý údaj: " + (r.ocsp() ? "OCSP" : "CRL")
                        + " odkud=" + r.origin() + " vydán=" + r.issued()
                        + " ústupek=" + r.relaxedRule());
            } else {
                System.out.println("      využitý údaj: (žádný)");
            }
            if (vc != null && vc.signingCertRevocationUnusableFact() != null) {
                var r = vc.signingCertRevocationUnusableFact();
                System.out.println("      nepoužitelný: " + (r.ocsp() ? "OCSP" : "CRL")
                        + " odkud=" + r.origin() + " vydán=" + r.issued()
                        + " ústupek=" + r.relaxedRule());
            }
            System.out.println("      horizont: " + ContinuityHorizonProbe.describe(sig));
        }
    }
}
