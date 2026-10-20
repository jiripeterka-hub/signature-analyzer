package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.detailedreport.DetailedReportFacade;
import eu.europa.esig.dss.diagnostic.DiagnosticDataFacade;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.policy.ValidationPolicy;
import eu.europa.esig.dss.simplereport.SimpleReportFacade;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.validation.SignedDocumentValidator;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Runs one throw-away validation in the background right after the window appears, so that
 * the user's first real validation doesn't pay for the whole DSS stack waking up.
 *
 * <p>Measured on a cold JVM, the first {@code validate()} took ~6.3 s versus ~0.3 s for every
 * later one. Almost none of that was the document: {@code SignedDocumentValidator.fromDocument}
 * alone cost ~4.2 s (ServiceLoader scanning every jar for validator factories, then loading
 * PDFBox / BouncyCastle / xmlsec), the validation policy ~0.5 s (JAXB) and the engine plus
 * report marshalling ~1.5 s. All of it is one-off per process, so doing it on an idle
 * background thread while the user is still picking a file makes it disappear.
 *
 * <p>The warm-up document is a bundled throw-away signature, and the verifier has no online
 * sources — the warm-up never touches the network and never reads user data.
 */
public final class WarmupService {

    private static volatile boolean started = false;

    private WarmupService() { }

    /** Starts the warm-up once per process; returns immediately. */
    public static void start(AppSettings settings, ClassLoader contextClassLoader) {
        if (started) return;
        started = true;
        Thread t = new Thread(() -> run(settings), "dss-warmup");
        t.setDaemon(true);
        // Below normal: the LOTL load and any user-triggered validation must win the CPU.
        t.setPriority(Thread.MIN_PRIORITY);
        if (contextClassLoader != null) t.setContextClassLoader(contextClassLoader);
        t.start();
    }

    private static void run(AppSettings settings) {
        try {
            StartupTrace.mark("warmup started");

            // JAXB contexts behind the report facades — seconds on first touch, cached afterwards.
            DiagnosticDataFacade.newFacade();
            SimpleReportFacade.newFacade();
            DetailedReportFacade.newFacade();
            StartupTrace.mark("warmup: report facades");

            ValidationPolicy policy = ValidationPolicyFactory.build(settings);
            StartupTrace.mark("warmup: policy");
            BackgroundWork.awaitIdle();

            // PDFBox maps fonts through a provider that scans every font installed on the
            // machine and caches the result in ~/.pdfbox.cache. With the cache warm this is
            // ~0.4 s; on a machine seeing the app for the first time it is far more.
            org.apache.pdfbox.pdmodel.font.FontMappers.instance().getTrueTypeFont("Arial", null);
            StartupTrace.mark("warmup: font mapper");

            BackgroundWork.awaitIdle();
            byte[] fixture = warmupPdf();
            DSSDocument doc = new InMemoryDocument(fixture, "warmup.pdf");
            SignedDocumentValidator validator = SignedDocumentValidator.fromDocument(doc);
            StartupTrace.mark("warmup: validator SPI resolved");

            // A verifier with no online sources: nothing to fetch, nothing to trust.
            validator.setCertificateVerifier(new CommonCertificateVerifier());
            if (policy != null) {
                validator.validateDocument(policy);
            } else {
                validator.validateDocument();
            }
            StartupTrace.mark("warmup: validation engine");

            // The PDFBox passes AnEP runs alongside DSS on every PDF — same story, they load
            // their own slice of PDFBox on first use.
            PdfSource warmupSource = PdfSource.of(fixture);
            PdfSignatureAppearanceExtractor.extract(warmupSource, null);
            PdfOrphanSignatureDetector.detect(warmupSource, null);
            PdfEmptySignatureFieldDetector.detect(warmupSource, null);
            PdfSigningToolExtractor.extract(warmupSource, null);
            PdfProfileExtractor.extract(warmupSource, null);
            // veraPDF si při prvním použití načítá profily pravidel z XML a rozjíždí Rhino,
            // kterým je vyhodnocuje — měřeno 0,8 s. Zaplatit se to má tady, ne u prvního
            // dokumentu, který se k PDF/A hlásí. Až úplně na konci: tenhle krok je ze všech
            // nejdražší a nemá zdržovat ty ostatní, o které jde častěji.
            warmUpPdfaValidator();
            StartupTrace.mark("warmup: done");
        } catch (Throwable ignored) {
            // Warm-up is best-effort — a failure here must never affect the running app.
            StartupTrace.mark("warmup: failed (harmless)");
        }
    }

    /**
     * Načte a přeloží profil pravidel PDF/A, aby první ověřovaný dokument nečekal na to, co
     * se dá udělat předem. Vlastní dokument se nevaliduje — drahé je tohle, ne běh nad daty.
     */
    private static void warmUpPdfaValidator() {
        try {
            org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider.initialise();
            org.verapdf.pdfa.Foundries.defaultInstance()
                    .createValidator(org.verapdf.pdfa.flavours.PDFAFlavour.PDFA_2_B, false)
                    .close();
        } catch (Throwable ignored) {
            // Jako celý warm-up: selhání tady nesmí mít vliv na běžící aplikaci.
        }
    }

    /**
     * The warm-up document: a minimal PDF with one PAdES signature made by a throw-away
     * self-signed key (see {@code MakeWarmupPdf}). It must carry a real signature — validating
     * an unsigned PDF leaves the CMS / BouncyCastle / certificate stack cold, which is where
     * most of the first-validation cost sits. Falls back to an empty PDF if the resource is
     * missing, which still warms the PDF and report layers.
     */
    private static byte[] warmupPdf() throws Exception {
        try (InputStream in = WarmupService.class.getResourceAsStream("/warmup/warmup-signed.pdf")) {
            if (in != null) return in.readAllBytes();
        }
        return emptyPdf();
    }

    /** Smallest valid PDF we can hand to the PAdES validator, built with PDFBox itself. */
    private static byte[] emptyPdf() throws Exception {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());
            pdf.save(out);
            return out.toByteArray();
        }
    }
}
