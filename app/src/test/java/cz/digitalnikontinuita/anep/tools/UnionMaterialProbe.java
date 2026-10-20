package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.AugmentationService;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.RestampingService;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Snese podpis, který obstál, nefiltrovaný revokační materiál svého souseda ve stejném
 * {@code /DSS}?
 *
 * <p>Otázka stojí před sloučením obou materiálových cest do jednoho průchodu. Uchování vkládá jen
 * to, co strohé čtení norem uzná; přerazítkování vkládá všechno, co ověření vidělo. U dokumentu
 * s několika podpisy může jeden podpis potřebovat první a druhý druhé — a protože slovník
 * {@code /DSS} je jeden a společný, sejde se obojí na jedné hromádce.
 *
 * <p>Předpoklad „materiál navíc nikdy neuškodí" v tomhle kódu <b>neplatí</b> a je to změřené:
 * {@code AugmentationService.isCrlUseless} vznikl kvůli případu, kdy o úspěchu augmentace
 * rozhodla přítomnost jediné CRL a zadržení seznamu skončilo tvrdým „Revoked/Suspended
 * certificate(s) detected". Proto se to měří, a ne odhaduje.
 *
 * <p>Měří se obě pořadí:
 * <ul>
 *   <li><b>přísně → doslova</b> (navrhované): DSS se rozhoduje o dosažitelných podpisech dřív,
 *       než se do slovníku dostane nepoužitelný materiál toho zbylého;</li>
 *   <li><b>doslova → přísně</b> (opačné): právě ten scénář, kvůli kterému to pořadí vzniklo.</li>
 * </ul>
 *
 * <p>Bez časového razítka. Otázka zní, jak strohý ověřovatel přečte obsah slovníku, a na to
 * razítko nepotřebuje — ušetří se tím i razítka na účtu autority.
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.tools.UnionMaterialProbe &lt;file&gt;…</pre>
 */
public final class UnionMaterialProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: UnionMaterialProbe <file>…");
            return;
        }
        // S přepínačem --tolerantni se zapnou všechny čtyři ústupky, přesně jak to dělá
        // MainView.performLenientVerify(). Nestačí jeden: acceptExpiredRevocationSources se týká
        // vydavatele odpovědi, jehož vlastní certifikát vypršel, kdežto odpověď vydaná až po konci
        // platnosti certifikátu, o kterém mluví, spadá pod acceptNewerOcsp. Zapnout jen ten první
        // verdikty nepřeklopilo a měření tolerantního případu tím padlo.
        //
        // Načíst je z uložených nastavení nejde: AppSettings.save() politiku ověřování schválně
        // nezapisuje, aby se ústupek nepřenášel mezi sezeními. Nastavuje se proto tady.
        boolean tolerant = java.util.Arrays.asList(args).contains("--tolerantni");
        AppSettings settings = AppSettings.load();
        if (tolerant) {
            settings.setAcceptNewerOcsp(true);
            settings.setAcceptExpiredRevocationSources(true);
            settings.setIgnoreSha1Deprecated(true);
            settings.setAcceptPdfNotEtsi(true);
            System.out.println("[režim] tolerantní: všechny čtyři ústupky zapnuty");
        }
        CommonCertificateVerifier verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        AtomicReference<CommonCertificateVerifier> ref = new AtomicReference<>(verifier);
        AugmentationService augmentation = new AugmentationService(ref);
        RestampingService restamping = new RestampingService();
        ValidatorFactory factory = new ValidatorFactory(settings);
        DocumentLoader loader = new DocumentLoader();
        Path tmp = Files.createTempDirectory("union-probe");

        for (String path : args) {
            if (path.startsWith("--")) continue;
            File input = new File(path);
            System.out.println();
            System.out.println("############ " + input.getName());

            ValidationResult before = validate(factory, loader, verifier, settings, input);
            System.out.println("  výchozí:                    " + verdicts(before));

            // Pořadí 1: přísně, pak doslova.
            File afterAugment = null;
            try {
                DSSDocument augmented = augmentation.augment(input, null, List.of());
                afterAugment = write(tmp, input, "1a-augment", augmented);
                ValidationResult r1 = validate(factory, loader, verifier, settings, afterAugment);
                System.out.println("  po přísné augmentaci:       " + verdicts(r1)
                        + "   " + size(afterAugment));

                ValidationResult ddSource = r1;
                RestampingService.Restamped both = restamping.embedAll(afterAugment, null,
                        validateRaw(factory, loader, verifier, afterAugment).reports().getDiagnosticData(),
                        List.of());
                File afterBoth = write(tmp, input, "1b-verbatim", both.document());
                ValidationResult r2 = validate(factory, loader, verifier, settings, afterBoth);
                System.out.println("  + doslovné vložení:         " + verdicts(r2)
                        + "   " + size(afterBoth)
                        + "   vloženo: " + describe(both.report()));
                System.out.println("  ZMĚNA VERDIKTU: "
                        + changed(ddSource, r2));
            } catch (Exception e) {
                System.out.println("  pořadí přísně→doslova SELHALO: " + e);
            }

            // Pořadí 1 s filtrem: doslovný materiál jen pro podpisy, které o něj stojí, tedy ty
            // se situací 45 (pokrytí úplné až na volnější čtení). Ostatním nic nepřinese.
            try {
                java.util.Set<String> needVerbatim = new java.util.HashSet<>();
                for (SignatureSummary sig : before.getSignatures()) {
                    if (sig.getDetectedSituations().contains(45)) {
                        needVerbatim.add(sig.getSignatureId());
                    }
                }
                if (afterAugment == null) {
                    System.out.println("  s filtrem:                  (augmentace neproběhla)");
                } else if (needVerbatim.isEmpty()) {
                    System.out.println("  s filtrem:                  žádný podpis doslovný"
                            + " materiál nepotřebuje → vloží se nic, zůstává "
                            + size(afterAugment));
                } else {
                    RestampingService.Restamped filtered = restamping.embedAll(
                            loader.load(afterAugment), null,
                            validateRaw(factory, loader, verifier, afterAugment).reports()
                                    .getDiagnosticData(),
                            List.of(), needVerbatim);
                    File out = write(tmp, input, "3-filtered", filtered.document());
                    ValidationResult r5 = validate(factory, loader, verifier, settings, out);
                    System.out.println("  s filtrem (" + needVerbatim.size() + " z "
                            + before.getSignatures().size() + " podpisů): " + verdicts(r5)
                            + "   " + size(out) + "   vloženo: " + describe(filtered.report()));
                }
            } catch (Exception e) {
                System.out.println("  filtrovaná varianta SELHALA: " + e);
            }

            // Pořadí 2: doslova, pak přísně — to, čemu se navrhované pořadí vyhýbá.
            try {
                RestampingService.Restamped verbatim = restamping.embedAll(input, null,
                        validateRaw(factory, loader, verifier, input).reports().getDiagnosticData(),
                        List.of());
                File afterVerbatim = write(tmp, input, "2a-verbatim", verbatim.document());
                ValidationResult r3 = validate(factory, loader, verifier, settings, afterVerbatim);
                System.out.println("  doslovné vložení samo:      " + verdicts(r3)
                        + "   " + size(afterVerbatim));
                DSSDocument then = augmentation.augment(afterVerbatim, null, List.of());
                File afterBoth2 = write(tmp, input, "2b-augment", then);
                ValidationResult r4 = validate(factory, loader, verifier, settings, afterBoth2);
                System.out.println("  + přísná augmentace:        " + verdicts(r4)
                        + "   " + size(afterBoth2));
            } catch (Exception e) {
                System.out.println("  pořadí doslova→přísně SELHALO: " + e);
            }
        }
        System.out.println();
        System.out.println("mezivýsledky: " + tmp);
    }

    private static ValidationResult validate(ValidatorFactory factory, DocumentLoader loader,
                                             CommonCertificateVerifier verifier,
                                             AppSettings settings, File f) {
        DSSDocument doc = loader.load(f);
        return new ReportMapper().map(f.getName(), null,
                factory.validate(doc, null, verifier, null), settings);
    }

    private static ValidationOutcome validateRaw(ValidatorFactory factory, DocumentLoader loader,
                                                 CommonCertificateVerifier verifier, File f) {
        return factory.validate(loader.load(f), null, verifier, null);
    }

    private static String verdicts(ValidationResult r) {
        StringBuilder sb = new StringBuilder();
        for (SignatureSummary s : r.getSignatures()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(shortVerdict(s.getIndication()));
        }
        return sb.length() == 0 ? "(nic)" : sb.toString();
    }

    /** Zhoršil se některý podpis, který předtím obstál? To je ta otázka, na které všechno visí. */
    private static String changed(ValidationResult before, ValidationResult after) {
        List<SignatureSummary> a = before.getSignatures();
        List<SignatureSummary> b = after.getSignatures();
        if (a.size() != b.size()) return "POČET PRVKŮ SE ZMĚNIL (" + a.size() + " → " + b.size() + ")";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.size(); i++) {
            String x = a.get(i).getIndication();
            String y = b.get(i).getIndication();
            if (!java.util.Objects.equals(x, y)) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("č.").append(i + 1).append(": ").append(x).append(" → ").append(y);
            }
        }
        return sb.length() == 0 ? "žádná" : sb.toString();
    }

    private static String shortVerdict(String indication) {
        if (indication == null) return "?";
        return switch (indication) {
            case "TOTAL_PASSED" -> "PASSED";
            case "TOTAL_FAILED" -> "FAILED";
            case "INDETERMINATE" -> "INDET";
            default -> indication;
        };
    }

    private static String describe(RestampingService.RestampReport report) {
        String d = report.describe();
        return d == null ? "nic" : d;
    }

    private static String size(File f) {
        return String.format("%,d B", f.length());
    }

    private static File write(Path dir, File input, String stage, DSSDocument doc)
            throws Exception {
        String base = input.getName().replaceAll("\\.[^.]+$", "");
        File out = dir.resolve(base + "_" + stage + ".pdf").toFile();
        try (InputStream is = doc.openStream()) {
            Files.write(out.toPath(), is.readAllBytes());
        }
        return out;
    }
}
