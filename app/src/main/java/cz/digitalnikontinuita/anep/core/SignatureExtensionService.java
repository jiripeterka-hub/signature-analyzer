package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.asic.cades.ASiCWithCAdESSignatureParameters;
import eu.europa.esig.dss.asic.cades.ASiCWithCAdESTimestampParameters;
import eu.europa.esig.dss.asic.cades.signature.ASiCWithCAdESService;
import eu.europa.esig.dss.asic.xades.ASiCWithXAdESSignatureParameters;
import eu.europa.esig.dss.asic.xades.signature.ASiCWithXAdESService;
import eu.europa.esig.dss.cades.CAdESSignatureParameters;
import eu.europa.esig.dss.cades.signature.CAdESService;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.enumerations.ASiCContainerType;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureForm;
import eu.europa.esig.dss.enumerations.JWSSerializationType;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.jades.JAdESSignatureParameters;
import eu.europa.esig.dss.jades.JWSJsonSerializationParser;
import eu.europa.esig.dss.jades.signature.JAdESService;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.FileDocument;
import eu.europa.esig.dss.model.SerializableSignatureParameters;
import eu.europa.esig.dss.service.tsp.OnlineTSPSource;
import eu.europa.esig.dss.signature.DocumentSignatureService;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.xades.XAdESSignatureParameters;
import eu.europa.esig.dss.xades.signature.XAdESService;
import cz.digitalnikontinuita.anep.model.ValidationResult;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Uchování pro formáty, které DSS rozšiřuje samo — CAdES, XAdES, JAdES a kontejnery ASiC.
 *
 * <p>The PDF path in {@link AugmentationService} exists because PAdES preservation is not a
 * signature extension at all: the revocation material goes into a PDF-level DSS dictionary that
 * has to be written by hand, and the archive time-stamp is a document time-stamp appended as a new
 * PDF revision. Nothing of that applies here. For every other format the archive form is part of
 * the signature itself, so DSS's own {@code extendDocument()} does the whole job — incorporate
 * validation data, then add the archive time-stamp — and doing it by hand would only reimplement
 * it worse.
 *
 * <p>The level asked for is always {@code *_BASELINE_LTA}. DSS fills in whatever is missing on the
 * way, so a B-level signature gets its signature time-stamp, its validation data and its archive
 * time-stamp in one call. That is also why preservation is worth offering to a signature that
 * already has revocation data: at LT the certificate of the last time-stamp cannot yet have its
 * own revocation data embedded (EN 319 142-1 clause 6.3 — it goes in before the <em>next</em>
 * time-stamp), so LT → LTA is precisely the step that supplies it.
 *
 * <p><b>A detached signature needs the document it signed.</b> The archive time-stamp covers the
 * signed data, so DSS cannot build one from the signature file alone. Preservation is therefore
 * offered only when both files are at hand; a lone {@code .p7s} can be fixed
 * ({@link FixationService}) but not preserved.
 */
public class SignatureExtensionService {

    /**
     * The extended signature, the level it was taken to, and the expiry of the certificate the
     * archive time-stamp was signed with — the date the preserved file stays verifiable until,
     * which is what the suggested file name is built from.
     */
    public record ExtensionResult(DSSDocument document, SignatureLevel level,
                                  java.util.Date tsaCertNotAfter) {}

    private final AtomicReference<CommonCertificateVerifier> verifierRef;

    public SignatureExtensionService(AtomicReference<CommonCertificateVerifier> verifierRef) {
        this.verifierRef = verifierRef;
    }

    /**
     * Whether this service is the one that handles the validated file.
     *
     * <p>PAdES, PKCS7 and PDF-NOT-ETSI are deliberately excluded — those belong to
     * {@link AugmentationService}, whatever DSS thinks of their level.
     *
     * @param signatureFile the file carrying the signature, needed only to tell an extensible
     *                      JAdES from one that is not; null answers for the format alone
     */
    public static boolean handles(ValidationResult result, File signatureFile) {
        if (isTimestampOnlyContainer(result)) return true;
        SignatureForm form = signatureForm(result);
        if (form == SignatureForm.CAdES || form == SignatureForm.XAdES) return true;
        if (form != SignatureForm.JAdES) return false;
        return signatureFile == null || jwsSerializationType(signatureFile) != null;
    }

    /**
     * A container whose only authentication element is a time-stamp — no signature at all.
     *
     * <p>Common enough to matter: a document handed to a TSA and nothing else, and every container
     * AnEP itself produces by fixation. There is no signature to take to LTA here, so preservation
     * means something different — another time-stamp over the whole container, which restarts the
     * clock before the current one's certificate expires, and validation data for the time-stamp
     * that was already there, so that it stays checkable once its CA stops publishing.
     */
    public static boolean isTimestampOnlyContainer(ValidationResult result) {
        DiagnosticData dd = result == null ? null : result.getDiagnosticData();
        return isTimestampOnlyFile(result) && dd.getContainerType() != null;
    }

    /**
     * The same artefact without the container: any file whose only authentication elements are
     * time-stamps.
     *
     * <p>A PDF passed from one time-stamping authority to the next is one of these — a chain of
     * proofs of existence over a document nobody signed — and everything said above about what
     * preservation means applies to it word for word. What differs is who does the work: DSS
     * extends containers, whereas a PDF gets its validation data in a DSS dictionary and its
     * time-stamp as a new revision, which is why {@link #handles} keeps asking the narrower
     * question and this one exists beside it.
     */
    public static boolean isTimestampOnlyFile(ValidationResult result) {
        DiagnosticData dd = result == null ? null : result.getDiagnosticData();
        return dd != null && dd.getSignatures().isEmpty() && !dd.getTimestampList().isEmpty();
    }

    /**
     * The JWS serialization of a JAdES file, or null when it is the compact one.
     *
     * <p>Compact serialization is a single {@code header.payload.signature} string with nowhere to
     * put an unprotected header, so there is no room for validation data or an archive time-stamp
     * and DSS refuses to extend it (JAdESService.assertExtensionPossible). The other two
     * serializations carry {@code etsiU}, and those extend normally. Which one a file uses has to
     * be read from the file: the parameters default to compact, and passing that default is what
     * makes DSS refuse a JAdES that would in fact extend perfectly well.
     */
    private static JWSSerializationType jwsSerializationType(File signatureFile) {
        try {
            JWSJsonSerializationParser parser =
                    new JWSJsonSerializationParser(new FileDocument(signatureFile));
            if (!parser.isSupported()) return null;
            JWSSerializationType type = parser.parse().getJWSSerializationType();
            return type == JWSSerializationType.COMPACT_SERIALIZATION ? null : type;
        } catch (Exception e) {
            return null;
        }
    }

    /** The signature form of the first signature DSS found, or null when there is none. */
    private static SignatureForm signatureForm(ValidationResult result) {
        DiagnosticData dd = result == null ? null : result.getDiagnosticData();
        if (dd == null) return null;
        for (SignatureWrapper sw : dd.getSignatures()) {
            SignatureLevel level = sw.getSignatureFormat();
            if (level != null && level.getSignatureForm() != null) return level.getSignatureForm();
        }
        return null;
    }

    /**
     * Extends every signature in {@code signatureFile} to the archive level.
     *
     * @param signatureFile   the file carrying the signature — the container, the XML, or the
     *                        detached {@code .p7s}. Not the signed document.
     * @param detachedContent the document the signature signs, when it lives outside the signature
     *                        file; null otherwise.
     * @param result          the validation just run over it, for the format and container type
     * @param profile         TSA to obtain the archive time-stamp from
     * @param algorithm       digest algorithm for the time-stamp
     */
    public ExtensionResult preserve(File signatureFile, File detachedContent,
                                    ValidationResult result, TsaProfile profile,
                                    DigestAlgorithm algorithm) {
        return extend(signatureFile, detachedContent, result, profile, algorithm, true);
    }

    /**
     * Adds only the time-stamp that takes a B-level signature to T, without touching revocation
     * data.
     *
     * <p>The step that has to come first for a signature that has none. Until a time-stamp exists,
     * the signature is anchored to the present, and revocation data issued after the present does
     * not exist — so there is nothing to embed and no point pretending otherwise. Once the anchor
     * has moved into the past, the data that appears afterwards can speak about it, and
     * preservation has something to work with.
     *
     * <p>No user action reaches this any more. The "Přidat časové razítko" button is gone:
     * preservation performs this step itself as the first of its rounds and carries on to LTA, so
     * offering the step separately meant offering a third of the job beside the whole of it. Kept
     * because it is what the level-B branch of {@link #preserve} is built from and because stopping
     * at T is a real operation, not because anything calls it today.
     */
    public ExtensionResult addTimestamp(File signatureFile, File detachedContent,
                                        ValidationResult result, TsaProfile profile,
                                        DigestAlgorithm algorithm) {
        return extend(signatureFile, detachedContent, result, profile, algorithm, false);
    }

    private ExtensionResult extend(File signatureFile, File detachedContent,
                                   ValidationResult result, TsaProfile profile,
                                   DigestAlgorithm algorithm, boolean toArchiveLevel) {
        CommonCertificateVerifier base = verifierRef.get();
        if (base == null) {
            throw new ExtensionException("Ověřovatel certifikátů není dosud inicializován");
        }
        if (profile == null) {
            throw new ExtensionException("Není vybrána žádná autorita časových razítek");
        }
        SignatureForm form = signatureForm(result);
        boolean timestampOnly = isTimestampOnlyContainer(result);
        if (form == null && !timestampOnly) {
            throw new ExtensionException("Ve vybraném souboru se nenašel žádný podpis, jehož ověřitelnost by šlo zachovat");
        }
        DiagnosticData dd = result.getDiagnosticData();
        ASiCContainerType container = dd.getContainerType();

        // Neither the document's own CRLs nor its OCSP responses are handed over: unlike the PDF
        // path, which has to re-inject them because incorporateValidationData() would drop them,
        // DSS reads a signature's embedded revocation data itself and keeps it. Nor does the size
        // argument apply here — a container is rewritten, not appended to, so nothing fetched in
        // vain stays behind in the bytes.
        //
        // The earliest time-stamp bounds which lists from the user's store may be embedded: one
        // issued before the signature existed says nothing about it.
        CommonCertificateVerifier verifier = AugmentationService.buildPreservationVerifier(
                base, List.of(), List.of(),
                ValidatorFactory.earliestTimestampTime(new FileDocument(signatureFile),
                        detachedContent == null ? null : List.of(new FileDocument(detachedContent)),
                        null));

        // DSS refuses to add a signature time-stamp once the signing certificate has expired or
        // been revoked, and that refusal is not DSS being cautious — it is the standard.
        // EN 319 122-1 V1.3.1 clause 6.3, requirement m): the tokens carried in signature-time-stamp
        // "shall be created before the signing certificate has been revoked or has expired", and
        // EN 319 102-1 V1.4.1 clause 4.3.3.4 NOTE 2 says the same, adding that validation of the
        // resulting signature can otherwise fail.
        //
        // This check was once silenced here, on the strength of the note in clause 4.3.4.4 that
        // augmentation is meaningful even for a TOTAL-FAILED signature. That note is about
        // incorporating validation material — the step to LT, where capturing the evidence is
        // worth having whatever the verdict. It says nothing about adding a time-stamp, where the
        // requirement above applies instead. So the alert stays in force.
        //
        // Poškozené časové razítko (nesouhlasí otisk nebo podpis tokenu) naproti tomu úkon
        // nezastaví. DSS ve výchozím stavu (alertOnInvalidTimestamp = ExceptionOnStatusAlert)
        // odmítne rozšířit každý podpis, který takové razítko nese — i tehdy, když o výsledku
        // nerozhoduje, protože důkaz existence dává jiné platné razítko. Nález uživatele
        // 13. 10. 2026 na kontejneru ASiC-E s podpisem CAdES-T: poškozené razítko obsahu, platné
        // podpisové razítko, podpis po doplnění CRL do vlastního úložiště platný — a uchování
        // skončilo „Broken timestamp(s) detected". Na rozdíl od výstrah o expiraci a revokaci výš
        // tu žádný požadavek norem nestojí: archivní razítko poškozené razítko nespraví, jen ho
        // překryje spolu se vším ostatním, a karta o něm dál nese poznámku. Rozhodnutí uživatele
        // týž den; cesta pro PDF tuhle kontrolu neprovádí vůbec, takže se tím obě srovnají.
        verifier.setAlertOnInvalidTimestamp(new eu.europa.esig.dss.alert.SilentOnStatusAlert());

        // Extending from B goes through two time-stamps — the signature one, then the archive one.
        // The last is the archive time-stamp, and its certificate is the one whose expiry bounds
        // how long the preserved file stays verifiable.
        CapturingTspSource tspSource = new CapturingTspSource(
                TsaSigningKey.observing(buildTspSource(profile), profile));

        if (timestampOnly) {
            return renewContainerTimestamp(signatureFile, container, verifier, tspSource, algorithm);
        }

        DocumentSignatureService<?, ?> service = buildService(form, container, verifier);
        ((eu.europa.esig.dss.signature.AbstractSignatureService<?, ?>) service)
                .setTspSource(tspSource);

        SignatureLevel target = toArchiveLevel ? archiveLevel(form) : baselineTLevel(form);
        SerializableSignatureParameters params =
                buildParameters(form, container, algorithm, detachedContent, signatureFile, target);

        try {
            @SuppressWarnings("unchecked")
            DocumentSignatureService<SerializableSignatureParameters, ?> typed =
                    (DocumentSignatureService<SerializableSignatureParameters, ?>) service;
            DSSDocument extended = typed.extendDocument(new FileDocument(signatureFile), params);
            return new ExtensionResult(extended, target,
                    TimestampingService.extractTsaCertNotAfterFromToken(tspSource.lastToken()));
        } catch (Exception e) {
            // A refusal from the TSA is worth telling apart from every other failure: it is the
            // only one the caller can do something about, by asking for SHA-256 instead. Offering
            // that after a revoked certificate would be a nonsensical question.
            if (tspSource.failure() != null) {
                throw new TsaException("Časové razítko se nepodařilo získat: "
                        + tspSource.failure().getMessage(), tspSource.failure());
            }
            throw new ExtensionException("Zachování ověřitelnosti selhalo: " + e.getMessage(), e);
        }
    }

    /**
     * Adds another time-stamp over a container that carries only time-stamps.
     *
     * <p>{@code timestamp()} is handed the container itself, not its contents. DSS then covers
     * everything already inside — the documents, the previous time-stamp and its manifest — with a
     * new {@code ASiCArchiveManifest} and a new {@code .tst}, which is what makes this a renewal
     * rather than a second, independent stamp: the new time-stamp attests to the old one, so the
     * chain survives the expiry of the old TSA certificate.
     *
     * <p>The verifier's revocation sources are what supplies validation data for the previous
     * time-stamp along the way.
     */
    private ExtensionResult renewContainerTimestamp(
            File containerFile, ASiCContainerType container, CommonCertificateVerifier verifier,
            CapturingTspSource tspSource, DigestAlgorithm algorithm) {
        ASiCWithCAdESService service = new ASiCWithCAdESService(verifier);
        service.setTspSource(tspSource);

        ASiCWithCAdESTimestampParameters params =
                new ASiCWithCAdESTimestampParameters(algorithm);
        params.aSiC().setContainerType(container);

        try {
            DSSDocument stamped = service.timestamp(
                    List.of(new FileDocument(containerFile)), params);
            return new ExtensionResult(stamped, null,
                    TimestampingService.extractTsaCertNotAfterFromToken(tspSource.lastToken()));
        } catch (Exception e) {
            if (tspSource.failure() != null) {
                throw new TsaException("Časové razítko se nepodařilo získat: "
                        + tspSource.failure().getMessage(), tspSource.failure());
            }
            throw new ExtensionException("Zachování ověřitelnosti selhalo: " + e.getMessage(), e);
        }
    }

    /**
     * ASiC is a packaging, not a signature form: a container holds CAdES or XAdES signatures and
     * the service is chosen by both facts together.
     */
    private static DocumentSignatureService<?, ?> buildService(
            SignatureForm form, ASiCContainerType container, CommonCertificateVerifier verifier) {
        if (container != null) {
            return switch (form) {
                case CAdES -> new ASiCWithCAdESService(verifier);
                case XAdES -> new ASiCWithXAdESService(verifier);
                default -> throw new ExtensionException(
                        "Kontejner ASiC s podpisem " + form + " uchovat neumíme");
            };
        }
        return switch (form) {
            case CAdES -> new CAdESService(verifier);
            case XAdES -> new XAdESService(verifier);
            case JAdES -> new JAdESService(verifier);
            default -> throw new ExtensionException("Formát " + form + " uchovat neumíme");
        };
    }

    private static SerializableSignatureParameters buildParameters(
            SignatureForm form, ASiCContainerType container, DigestAlgorithm algorithm,
            File detachedContent, File signatureFile, SignatureLevel target) {
        List<DSSDocument> detached = detachedContent == null
                ? null : List.of(new FileDocument(detachedContent));

        if (container != null) {
            if (form == SignatureForm.CAdES) {
                ASiCWithCAdESSignatureParameters p = new ASiCWithCAdESSignatureParameters();
                p.setSignatureLevel(target);
                p.aSiC().setContainerType(container);
                p.getSignatureTimestampParameters().setDigestAlgorithm(algorithm);
                p.getArchiveTimestampParameters().setDigestAlgorithm(algorithm);
                return p;
            }
            ASiCWithXAdESSignatureParameters p = new ASiCWithXAdESSignatureParameters();
            p.setSignatureLevel(target);
            p.aSiC().setContainerType(container);
            p.getSignatureTimestampParameters().setDigestAlgorithm(algorithm);
            p.getArchiveTimestampParameters().setDigestAlgorithm(algorithm);
            return p;
        }

        switch (form) {
            case CAdES -> {
                CAdESSignatureParameters p = new CAdESSignatureParameters();
                p.setSignatureLevel(target);
                if (detached != null) p.setDetachedContents(detached);
                p.getSignatureTimestampParameters().setDigestAlgorithm(algorithm);
                p.getArchiveTimestampParameters().setDigestAlgorithm(algorithm);
                return p;
            }
            case XAdES -> {
                XAdESSignatureParameters p = new XAdESSignatureParameters();
                p.setSignatureLevel(target);
                if (detached != null) p.setDetachedContents(detached);
                p.getSignatureTimestampParameters().setDigestAlgorithm(algorithm);
                p.getArchiveTimestampParameters().setDigestAlgorithm(algorithm);
                return p;
            }
            default -> {
                JAdESSignatureParameters p = new JAdESSignatureParameters();
                p.setSignatureLevel(target);
                // Must match the file, or DSS rejects it — see jwsSerializationType().
                JWSSerializationType type = jwsSerializationType(signatureFile);
                if (type == null) {
                    throw new ExtensionException("Podpis JAdES je v kompaktní podobě"
                            + " (compact serialization), do které nelze doplnit ani revokační data,"
                            + " ani archivní časové razítko.");
                }
                p.setJwsSerializationType(type);
                if (detached != null) p.setDetachedContents(detached);
                p.getSignatureTimestampParameters().setDigestAlgorithm(algorithm);
                p.getArchiveTimestampParameters().setDigestAlgorithm(algorithm);
                return p;
            }
        }
    }

    private static SignatureLevel baselineTLevel(SignatureForm form) {
        return switch (form) {
            case CAdES -> SignatureLevel.CAdES_BASELINE_T;
            case XAdES -> SignatureLevel.XAdES_BASELINE_T;
            default    -> SignatureLevel.JAdES_BASELINE_T;
        };
    }

    private static SignatureLevel archiveLevel(SignatureForm form) {
        return switch (form) {
            case CAdES -> SignatureLevel.CAdES_BASELINE_LTA;
            case XAdES -> SignatureLevel.XAdES_BASELINE_LTA;
            default    -> SignatureLevel.JAdES_BASELINE_LTA;
        };
    }

    /**
     * Passes every time-stamp request through and keeps the last token that came back.
     *
     * <p>DSS obtains the time-stamps inside {@code extendDocument()} and does not report what it
     * got, so the only place to see them is on the way past.
     */
    private static final class CapturingTspSource
            implements eu.europa.esig.dss.spi.x509.tsp.TSPSource {

        private static final long serialVersionUID = 1L;

        private final eu.europa.esig.dss.spi.x509.tsp.TSPSource delegate;
        private final AtomicReference<byte[]> last = new AtomicReference<>();
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

        CapturingTspSource(eu.europa.esig.dss.spi.x509.tsp.TSPSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public eu.europa.esig.dss.model.TimestampBinary getTimeStampResponse(
                DigestAlgorithm digestAlgorithm, byte[] digest) {
            try {
                eu.europa.esig.dss.model.TimestampBinary binary =
                        delegate.getTimeStampResponse(digestAlgorithm, digest);
                if (binary != null) last.set(binary.getBytes());
                return binary;
            } catch (RuntimeException e) {
                failure.set(e);
                throw e;
            }
        }

        byte[] lastToken() { return last.get(); }

        /** The TSA's refusal, when there was one — DSS wraps it beyond recognition further up. */
        RuntimeException failure() { return failure.get(); }
    }

    /** Same construction as {@link TimestampingService}, including the optional HTTP auth. */
    private static OnlineTSPSource buildTspSource(TsaProfile profile) {
        var loader = TrustConfig.createTimestampDataLoader();
        if (profile.hasAuth()) {
            try {
                URI uri = new URI(profile.getUrl());
                loader.addAuthentication(uri.getHost(), uri.getPort(), null,
                        profile.getUsername(), profile.getPassword().toCharArray());
                loader.setPreemptiveAuthentication(true);
            } catch (Exception ignored) {}
        }
        OnlineTSPSource tspSource = new OnlineTSPSource(profile.getUrl());
        tspSource.setDataLoader(loader);
        return tspSource;
    }

    public static class ExtensionException extends RuntimeException {
        public ExtensionException(String msg)                  { super(msg); }
        public ExtensionException(String msg, Throwable cause) { super(msg, cause); }
    }

    /** The TSA would not issue the time-stamp — the one failure a different digest might cure. */
    public static final class TsaException extends ExtensionException {
        public TsaException(String msg, Throwable cause) { super(msg, cause); }
    }
}
