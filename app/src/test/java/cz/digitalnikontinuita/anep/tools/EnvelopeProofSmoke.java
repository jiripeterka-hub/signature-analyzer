package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.ContainerInspector;
import cz.digitalnikontinuita.anep.core.PdfAttachmentInspector;
import cz.digitalnikontinuita.anep.core.EnvelopeProof;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.InMemoryDocument;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Celá cesta důkazu existence z obalu nad jedním prvkem obalu (ASiC, CMS, ZFO, XAdES, JAdES,
 * PDF s přílohami; prvek se zadává cestou uzlu z prohlížeče): ověření prvku samostatně,
 * {@link ValidatorFactory#withEnvelopeProof} a {@link ReportMapper} — tedy to, co uvidí karta.
 *
 * <p>Vznikla 1. 11. 2026 spolu s důkazem z obalu. Hlavní otázka, kterou zodpovídá: nezamlčí
 * se odvolání před okamžikem důkazu? U umělého vzorku podepsaného až po odvolání musí vyjít
 * situace 20 (viz {@code RevokedBeforeProofScan}).
 *
 * <pre>
 *   java -cp ... cz.digitalnikontinuita.anep.tools.EnvelopeProofSmoke [-t] &lt;kontejner&gt; &lt;prvek&gt;
 * </pre>
 * {@code -t} zapne všechny čtyři ústupky.
 */
public final class EnvelopeProofSmoke {

    public static void main(String[] args) throws Exception {
        List<String> a = new java.util.ArrayList<>(List.of(args));
        boolean tolerant = a.remove("-t");
        Path container = Path.of(a.get(0));
        String entry = a.get(1);

        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(tolerant);
        settings.setAcceptExpiredRevocationSources(tolerant);
        settings.setIgnoreSha1Deprecated(tolerant);
        settings.setAcceptPdfNotEtsi(tolerant);
        settings.setUseCustomValidationTime(false);
        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        var factory = new ValidatorFactory(settings);

        byte[] envelope = Files.readAllBytes(container);
        byte[] element = extract(envelope, container.getFileName().toString(), entry);
        if (element == null) {
            System.out.println("prvek " + entry + " v obalu není");
            System.exit(1);
        }
        var outcome = factory.validate(new InMemoryDocument(element, entry), null, verifier, null);
        print("samostatně", outcome, settings, entry);
        var withProof = factory.withEnvelopeProof(outcome,
                new EnvelopeProof.Source(envelope, container.getFileName().toString(), entry, element),
                verifier);
        print("s důkazem z obalu", withProof, settings, entry);
        System.exit(0);
    }

    /**
     * Prvek tak, jak ho vyjme prohlížeč obalu: týž strom ({@link ContainerInspector}, u PDF
     * {@link PdfAttachmentInspector}), hledá se podle cesty uzlu.
     */
    public static byte[] extract(byte[] envelope, String name, String path) {
        ContainerInspector.Result tree =
                ContainerInspector.detectKind(envelope) == ContainerInspector.Kind.PDF
                        ? PdfAttachmentInspector.inspect(envelope, name, null)
                        : ContainerInspector.inspect(envelope, name);
        return tree == null || tree.root() == null ? null : find(tree.root(), path);
    }

    private static byte[] find(ContainerInspector.Node node, String path) {
        for (ContainerInspector.Node c : node.getChildren()) {
            if (path.equals(c.getPath()) && c.getData() != null) return c.getData();
            byte[] deeper = find(c, path);
            if (deeper != null) return deeper;
        }
        return null;
    }

    private static void print(String label, ValidatorFactory.ValidationOutcome outcome,
                              AppSettings settings, String name) {
        ValidationResult result = new ReportMapper().map(name, "x", outcome, settings, Map.of(),
                List.of(), false, Map.of());
        System.out.println("== " + label);
        for (SignatureSummary s : result.getSignatures()) {
            System.out.println("  " + s.getIndication() + "/" + s.getSubIndication()
                    + "  BST " + s.getBestSignatureTime()
                    + "  situace " + s.getDetectedSituations()
                    + "  důvody " + s.getDetectedReasons()
                    + "  rozhodující " + s.getDecisiveSettings());
            if (s.getEnvelopeProofDetail() != null) {
                System.out.println("  věta: " + s.getEnvelopeProofDetail());
                System.out.println("  razítko obalu: " + s.getEnvelopeProofTimestamp());
            }
        }
    }
}
