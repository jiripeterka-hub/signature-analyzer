package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.alert.SilentOnStatusAlert;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.FileDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.pades.signature.PAdESExtensionService;
import eu.europa.esig.dss.service.crl.OnlineCRLSource;
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader;
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Augments a PAdES document from B or B-T level to B-LT by fetching and embedding
 * revocation data (CRL/OCSP) into the PDF DSS/VRI dictionaries.
 */
public class AugmentationService {

    private final AtomicReference<CommonCertificateVerifier> verifierRef;

    public AugmentationService(AtomicReference<CommonCertificateVerifier> verifierRef) {
        this.verifierRef = verifierRef;
    }

    public DSSDocument augment(File inputFile, char[] pdfPassword, List<byte[]> extraCrls) {
        return augment(inputFile, pdfPassword, extraCrls, false);
    }

    /**
     * @param revocationDecidesVerdict true when the verdict just reported for this document rests
     *        on a revocation — the signing certificate was revoked before the signature can be
     *        shown to have existed. The document's own responses must then not pre-empt the
     *        network ({@link DocumentOcspSource}), because the evidence the verdict stands on is
     *        precisely what the document does <em>not</em> carry: its embedded response predates
     *        the revocation and says the certificate was good. Preserving from that alone would
     *        produce an artefact that validates more favourably than the original does today,
     *        once the revocation list ages out of the network. Where revocation came only
     *        <em>after</em> the signature existed the verdict does not rest on it, the embedded
     *        response is the whole answer, and pre-empting saves megabytes.
     */
    public DSSDocument augment(File inputFile, char[] pdfPassword, List<byte[]> extraCrls,
                               boolean revocationDecidesVerdict) {
        CommonCertificateVerifier base = verifierRef.get();
        if (base == null) {
            throw new AugmentationException("Ověřovatel certifikátů není dosud inicializován");
        }

        // Snapshot existing DSS /CRLs before augmentation.  It serves two purposes:
        //
        //  1. DocumentCrlSource answers from it for already-expired certificates, so no CRL is
        //     fetched that the document is already holding.  This is what keeps the file from
        //     growing, and it is the only thing that can: saveIncremental() only appends, so a CRL
        //     once written stays in the bytes whatever postProcessDss() later does to /CRLs.
        //  2. incorporateValidationData() rewrites the DSS using only what the augVerifier found,
        //     so any CRLs already in the DSS would be silently dropped.  We re-inject them via
        //     postProcessDss() so that CRL coverage for chain certs and trust anchors survives.
        List<byte[]> existingDssCrls = readDssCrlBytes(inputFile, pdfPassword);

        // Through the loader rather than straight from the file, so that a PDF whose header does
        // not start at byte zero can be preserved at all: DSS refuses such a file outright, and a
        // document AnEP has just validated would otherwise fail here with "format not recognized".
        // The trimmed bytes are what the signature covers, so the preserved artefact is built from
        // exactly what was verified.
        DSSDocument input = new DocumentLoader().load(inputFile);

        // Whatever the verifier's CRL sources supply — including the user's own store — ends up in
        // the final document, OCSP present or not. See postProcessDss() for why the signing-cert
        // CRL is no longer dropped as a duplicate of an OCSP.
        // Souběh podpisů: nese-li dokument víc než jeden, smí uchování proběhnout i tehdy,
        // když některému z nich nepomůže — viz pátý parametr a jeho javadoc.
        CommonCertificateVerifier augVerifier = buildPreservationVerifier(base, existingDssCrls,
                revocationDecidesVerdict ? List.of() : readDocumentOcspBytes(input, pdfPassword),
                ValidatorFactory.earliestTimestampTime(input, null, pdfPassword),
                countSignatures(input, pdfPassword) > 1);

        try {
            List<byte[]> allExtraCrls = new java.util.ArrayList<>(existingDssCrls);
            if (extraCrls != null) allExtraCrls.addAll(extraCrls);

            DSSDocument doc = input;
            PAdESExtensionService extSvc = new PAdESExtensionService(augVerifier);
            DSSDocument augmented = extSvc.incorporateValidationData(doc, pdfPassword);
            trace("po incorporateValidationData", inputFile.length(), augmented);
            augmented = postProcessDss(augmented, allExtraCrls, pdfPassword,
                    expiredCertsGrants(base));
            trace("po postProcessDss          ", inputFile.length(), augmented);

            // Hand back the original when the round produced nothing.
            //
            // Preserving a document a second time used to add over a megabyte while the DSS
            // dictionary ended up holding exactly what it held before: the same single CRL. The
            // document's own CRL is fed to DSS as revocation data (that is what DocumentCrlSource
            // is for), DSS does not know it is already inside and writes it into the new DSS
            // dictionary as a fresh stream, postProcessDss writes it once more, and an incremental
            // save can never take either copy back out. The 521 kB CRL ended up in the file three
            // times over two rounds, with one of them referenced.
            //
            // Chasing whichever layer duplicated the bytes would leave the other one in place, so
            // the test is on the outcome instead: if the augmented document's DSS dictionary holds
            // the same material as the original's, the round achieved nothing and its bytes are
            // pure cost. Returning the input unchanged also lets preservation carry on and add its
            // archive time-stamp, which is the part that does have something to contribute.
            if (dssContentUnchanged(inputFile, augmented, pdfPassword)) {
                return input;
            }
            return augmented;
        } catch (Exception e) {
            throw new AugmentationException("Augmentace selhala: " + e.getMessage(), e);
        }
    }

    /**
     * Variant that takes an in-memory document — writes it to a temp file and delegates.
     *
     * <p>Needed for the second round of preserving a B-level PDF, where what has to be augmented is
     * the freshly time-stamped document rather than the file the user opened. The result is read
     * back into memory before the temp file goes away, since {@link #augment(File, char[], List)}
     * can hand back the document it was given — which for a large input is a
     * {@link FileDocument} pointing at that temp file.
     */
    public DSSDocument augment(DSSDocument doc, char[] pdfPassword, List<byte[]> extraCrls) {
        return augment(doc, pdfPassword, extraCrls, false);
    }

    /** As above, carrying {@code revocationDecidesVerdict} through to the file-based variant. */
    public DSSDocument augment(DSSDocument doc, char[] pdfPassword, List<byte[]> extraCrls,
                               boolean revocationDecidesVerdict) {
        File tmp = null;
        try {
            tmp = File.createTempFile("anep-augment-", ".pdf");
            try (OutputStream os = java.nio.file.Files.newOutputStream(tmp.toPath())) {
                doc.writeTo(os);
            }
            DSSDocument augmented = augment(tmp, pdfPassword, extraCrls, revocationDecidesVerdict);
            try (InputStream is = augmented.openStream()) {
                return new InMemoryDocument(is.readAllBytes());
            }
        } catch (AugmentationException e) {
            throw e;
        } catch (Exception e) {
            throw new AugmentationException("Augmentace selhala: " + e.getMessage(), e);
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    /**
     * The certificate verifier preservation runs with, whatever the format.
     *
     * <p>It is not the verifier validation uses. Validation answers "what can be shown about this
     * document as it stands"; preservation has to go and get what is missing, so this one is
     * allowed online — OCSP, CRL and AIA — and it consults the user's own CRL store.
     *
     * <p>CRL source chain: the document's own CRLs first (when the caller has any), then online,
     * then the user's store. The store is needed when the signing certificate has expired and the
     * online OCSP is post-expiry (thisUpdate &gt; cert.notAfter):
     * {@link OcspFirstCrlFallbackRevocationDataLoadingStrategy} skips that OCSP and falls back to
     * the CRL path, {@link FilteringCRLSource} blocks post-expiry CRLs without
     * {@code expiredCertsOnCRL} coverage, and a within-validity CRL from the store is embedded.
     *
     * <p><b>Post-expirační údaj se tedy nevloží, a to schválně — neopravovat.</b> Vypadá to jako
     * zbytečná přísnost: údaj je po ruce, tak proč ho tam nedat. Důvod: validátorům podle
     * standardů nepomůže (revokační údaj musí vypovídat o rozhodném okamžiku, a ten leží před
     * expirací) a tomu, kdo má vlastní zásobu revokačních dat, je lhostejný.
     *
     * <p>Zbývala jediná námitka — že cizí zásoba nemusí sahat libovolně do historie, a hlavně že
     * Adobe Acrobat Reader, tedy zdaleka nejrozšířenější čtečka, by takový údaj využít mohl.
     * <b>Změřeno 21. 9. 2026:</b> do {@code PAdES B-T viditelny PS.pdf} (podpisový certifikát
     * platil do 27. 6. 2026) se toutéž cestou, jen bez tohohle filtru a s oběma ústupky
     * {@link LenientRevocationDataVerifier}, vložila odpověď OCSP vydaná týž den. Acrobat ji
     * nevyužil a ohlásil „Odezva OCSP má propadlou platnost nebo ještě není platná" — porovnává
     * dobu platnosti té odpovědi s okamžikem, ke kterému podpis posuzuje, tedy stejně přísně jako
     * standardy. Námitka tím padla; bez nového důkazu se to nemá otvírat znovu.
     *
     * @param documentCrls CRLs the document already carries, so they are never fetched again.
     *                     Only the PDF path has these — DSS reads a container's own revocation
     *                     material itself — so the other callers pass an empty list.
     * @param documentOcsps OCSP responses the document already carries, for the same reason and
     *                     with the same narrowness — see {@link DocumentOcspSource}. Without them
     *                     an expired certificate's answer was fetched from a responder that no
     *                     longer knows it, and the UNKNOWN that came back sent preservation off to
     *                     download a CRL instead.
     * @param referenceTime the moment the embedded revocation data has to speak about — the
     *                     best-signature-time. A list issued before it proves nothing about the
     *                     signature and embedding it would dress the document up as preserved
     *                     while adding no evidence. Null leaves the store unfiltered.
     */
    // Veřejné kvůli nástroji cz.digitalnikontinuita.anep.tools.RevokedLtaBuilder, který staví testovací
    // artefakty touž cestou jako Uchování a liší se od něj jedinou věcí — umlčeným poplachem na
    // revokovaný certifikát. Kdyby si verifikátor skládal sám, přestal by měřit to, co aplikace
    // opravdu dělá.
    public static CommonCertificateVerifier buildPreservationVerifier(CommonCertificateVerifier base,
                                                               List<byte[]> documentCrls,
                                                               List<byte[]> documentOcsps,
                                                               java.util.Date referenceTime) {
        return buildPreservationVerifier(base, documentCrls, documentOcsps, referenceTime, false);
    }

    /**
     * @param tolerateUnprovableSignatures povolit zápis validačních dat i do dokumentu, v němž
     *        <b>některý</b> podpis už doložit nelze — tedy vypnout dvě výstrahy DSS, které by
     *        jinak odmítly celý úkon.
     *
     * <p>Zapíná se <b>jen při souběhu prvků</b>, a to z jediného důvodu: nové časové razítko kryje
     * celý soubor, takže je nelze přidat jen k některému podpisu. Nabídka uchování se řídí tím,
     * čemu úkon prospěje — při souběhu tedy „nejvyšším" řešením, které jde provést aspoň u jednoho
     * prvku. DSS naproti tomu vetuje zápis za celý dokument, jakmile se o kterýkoli podpis opřít
     * nelze, a tím zablokuje právě ten prvek, kvůli kterému se nabídka udělala. U dokumentu
     * s jediným podpisem se veto neruší: tam brána AnEP a DSS odpovídají na tutéž otázku a druhý
     * názor se hodí.
     *
     * <p>Vypínají se dvě, protože za první čeká druhá. Obě mají v {@code CommonCertificateVerifier}
     * výchozí {@code ExceptionOnStatusAlert}:
     * <ul>
     *   <li>{@code alertOnExpiredCertificate} → {@code assertAllSignaturesNotExpired()}. Pro každý
     *       podpis se ptá, zda jeho certifikát ještě platí, a když ne, zda dokument razítkem
     *       dokládá, že podpis vznikl, dokud platil. DSS každému podpisu registruje důkaz
     *       k aktuálnímu času, takže u dosud platného certifikátu je kontrola zadarmo.</li>
     *   <li>{@code alertOnRevokedCertificate} → {@code assertAllSignatureCertificatesNotRevoked()}.
     *       Naměřeno 12. 9. 2026 na „PAdES B-B +B-T kvalifikovany ICA.pdf": po umlčení první se
     *       úkon zastavil na druhé, protože oba podpisy stojí na témže certifikátu, revokovaném
     *       27. 8. 2026.</li>
     * </ul>
     *
     * <p>Umlčení neznamená „neověřuj": {@code SilentOnStatusAlert} kontrolu provede a výsledek
     * spočítá, jen z něj neudělá výjimku. Ověřování dokumentu se nemění vůbec — to běží nad
     * verifierem z {@code TrustConfig}, kdežto tenhle slouží výhradně uchování.
     *
     * <p><b>Proč se tím nic nenalhává.</b> Změřeno na témže souboru: uchování přesune podpis
     * s razítkem z PAdES-BASELINE-T na LT a TOTAL_PASSED, kdežto podpis bez razítka zůstane
     * PKCS7-B / INDETERMINATE/REVOKED_NO_POE, před i po. Do dokumentu se navíc zapíše i stažená
     * odpověď, která odvolání dokládá; důkaz se tedy uchová, ne zamlčí. Přesně to má na mysli
     * EN 319 102-1 V1.4.1 klauzule 4.3.4.4, když říká, že zápis validačního materiálu má smysl
     * i u podpisu, který neobstál.
     *
     * <p><b>Cesta {@code SignatureExtensionService} tuhle úlevu nedostane</b> a nesmí ji dostat:
     * tam se přidává <i>podpisové</i> časové razítko a EN 319 122-1 V1.3.1 klauzule 6.3, požadavek
     * m), žádá, aby vzniklo dřív, než byl certifikát revokován nebo než expiroval. Tady se zapisuje
     * jen validační materiál, na který ten požadavek nemíří.
     */
    public static CommonCertificateVerifier buildPreservationVerifier(CommonCertificateVerifier base,
                                                               List<byte[]> documentCrls,
                                                               List<byte[]> documentOcsps,
                                                               java.util.Date referenceTime,
                                                               boolean tolerateUnprovableSignatures) {
        CommonsDataLoader loader = TrustConfig.createDataLoader();
        CommonCertificateVerifier verifier = new CommonCertificateVerifier();
        if (tolerateUnprovableSignatures) {
            verifier.setAlertOnExpiredCertificate(new SilentOnStatusAlert());
            verifier.setAlertOnRevokedCertificate(new SilentOnStatusAlert());
        }

        for (var src : base.getTrustedCertSources().getSources()) {
            verifier.addTrustedCertSources(src);
        }
        for (var src : base.getAdjunctCertSources().getSources()) {
            verifier.addAdjunctCertSources(src);
        }

        // The OCSP source keeps a loader of its own: DSS's OCSPDataLoader posts with
        // Content-Type: application/ocsp-request and refuses to have it changed, and the generic
        // loader that used to be handed in here dropped the header. A responder entitled to
        // insist on it answered 401, checkOCSP turned that into "no OCSP available", and
        // preservation embedded a 282 kB CRL where a 2 kB response would have done.
        OnlineOCSPSource ocspSource = new OnlineOCSPSource();
        ocspSource.setDataLoader(TrustConfig.createOcspDataLoader());
        verifier.setOcspSource(documentOcsps == null || documentOcsps.isEmpty()
                ? ocspSource
                : new DocumentOcspSource(documentOcsps, ocspSource));
        verifier.setAIASource(new DefaultAIASource(loader));

        OnlineCRLSource onlineCrl = new OnlineCRLSource();
        onlineCrl.setDataLoader(loader);
        List<DSSDocument> customCrlDocs = CustomCrlStore.loadAsDocuments();
        eu.europa.esig.dss.spi.x509.revocation.RevocationSource<
                eu.europa.esig.dss.model.x509.revocation.crl.CRL> network = new FallbackCrlSource(
                        new FilteringCRLSource(onlineCrl),
                        new BestMatchCustomCrlSource(customCrlDocs, referenceTime));
        verifier.setCrlSource(documentCrls == null || documentCrls.isEmpty()
                ? network
                : new FallbackCrlSource(new DocumentCrlSource(documentCrls), network));
        verifier.setRevocationDataLoadingStrategyFactory(
                OcspFirstCrlFallbackRevocationDataLoadingStrategy::new);
        return verifier;
    }

    /** Size trace for -Danep.augment.trace, to attribute growth to the stage that caused it. */
    private static void trace(String stage, long inputSize, DSSDocument d) {
        if (System.getProperty("anep.augment.trace") == null) return;
        try (InputStream is = d.openStream()) {
            long n = is.readAllBytes().length;
            System.out.printf("[augment] %s  %,12d B   proti vstupu %+,12d B%n",
                    stage, n, n - inputSize);
        } catch (Exception ignored) {}
    }

    /**
     * True when {@code augmented} carries exactly the same DSS material as {@code inputFile} —
     * same certificates, same CRLs, same OCSP responses, compared by content rather than by
     * object identity.
     *
     * <p>A missing DSS dictionary counts as an empty set, so a document that had none and still
     * has none is also unchanged.
     */
    private static boolean dssContentUnchanged(File inputFile, DSSDocument augmented,
                                               char[] pdfPassword) {
        try {
            java.util.Set<String> before;
            try (PDDocument pdf = loadPdf(inputFile, pdfPassword)) {
                before = readDssFingerprints(pdf);
            }
            byte[] bytes;
            try (InputStream is = augmented.openStream()) { bytes = is.readAllBytes(); }
            java.util.Set<String> after;
            try (PDDocument pdf = loadPdf(bytes, pdfPassword)) {
                after = readDssFingerprints(pdf);
            }
            return before.equals(after);
        } catch (Exception e) {
            return false;   // cannot tell — keep the augmented document
        }
    }

    /** SHA-256 of every stream in /DSS/Certs, /DSS/CRLs and /DSS/OCSPs, tagged with its array. */
    private static java.util.Set<String> readDssFingerprints(PDDocument pdf) throws Exception {
        java.util.Set<String> result = new java.util.HashSet<>();
        org.apache.pdfbox.cos.COSBase dssBase = pdf.getDocumentCatalog()
                .getCOSObject().getDictionaryObject(COSName.getPDFName("DSS"));
        if (!(dssBase instanceof COSDictionary dss)) return result;
        java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
        for (String arrayName : new String[] {"Certs", "CRLs", "OCSPs"}) {
            org.apache.pdfbox.cos.COSBase base =
                    dss.getDictionaryObject(COSName.getPDFName(arrayName));
            if (!(base instanceof COSArray arr)) continue;
            for (int i = 0; i < arr.size(); i++) {
                if (!(arr.getObject(i) instanceof COSStream s)) continue;
                try (InputStream is = s.createInputStream()) {
                    result.add(arrayName + ":" + hexDigest(sha256, is.readAllBytes()));
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    private static PDDocument loadPdf(File file, char[] pdfPassword) throws Exception {
        return (pdfPassword != null && pdfPassword.length > 0)
                ? Loader.loadPDF(file, new String(pdfPassword))
                : Loader.loadPDF(file);
    }

    private static PDDocument loadPdf(byte[] bytes, char[] pdfPassword) throws Exception {
        return (pdfPassword != null && pdfPassword.length > 0)
                ? Loader.loadPDF(bytes, new String(pdfPassword))
                : Loader.loadPDF(bytes);
    }

    /**
     * Every OCSP response the document carries, wherever it keeps them.
     *
     * <p>Read through DSS rather than out of the {@code /DSS} dictionary, because that dictionary
     * is not where they necessarily are: a B-T document has none, and its only response sits inside
     * the CMS as revocation values — which is exactly the case this exists for. DSS's own signature
     * source knows both places.
     *
     * <p>A structural read, like {@link ValidatorFactory#earliestTimestampTime}: no revocation
     * lookups, no policy, and the bare verifier is not optional — an ASiC container builds one
     * nested analyzer per signature file and rejects a missing one.
     */
    private static List<byte[]> readDocumentOcspBytes(DSSDocument doc, char[] pdfPassword) {
        List<byte[]> result = new ArrayList<>();
        try {
            eu.europa.esig.dss.validation.SignedDocumentValidator validator =
                    eu.europa.esig.dss.validation.SignedDocumentValidator.fromDocument(doc);
            validator.setCertificateVerifier(new CommonCertificateVerifier());
            if (pdfPassword != null && validator
                    instanceof eu.europa.esig.dss.pades.validation.PDFDocumentValidator pdfValidator) {
                pdfValidator.setPasswordProtection(pdfPassword);
            }
            for (eu.europa.esig.dss.spi.signature.AdvancedSignature signature : validator.getSignatures()) {
                for (var binary : signature.getOCSPSource().getAllRevocationBinaries()) {
                    byte[] bytes = binary.getBinaries();
                    if (bytes != null && bytes.length > 0) result.add(bytes);
                }
            }
        } catch (Exception ignored) {
            // Unreadable structure is validation's problem to report, not preservation's.
        }
        return result;
    }

    /**
     * Kolik podpisů dokument nese — na tom stojí rozhodnutí o souběhu.
     *
     * <p>Strukturální čtení jako {@link #readDocumentOcspBytes}: bez revokačních dotazů a bez
     * politiky, ale s holým ověřovatelem, protože bez něj kontejner ASiC sestavu odmítne.
     * Počítají se podpisy, ne razítka — samostatné dokumentové razítko souběh prvků nedělá.
     *
     * <p>Nečitelná struktura vrací 0, tedy „žádný souběh": úleva se pak neudělí a platí dosavadní
     * chování. Nečitelnou strukturu ohlásí ověřování, ne uchování.
     */
    private static int countSignatures(DSSDocument doc, char[] pdfPassword) {
        try {
            eu.europa.esig.dss.validation.SignedDocumentValidator validator =
                    eu.europa.esig.dss.validation.SignedDocumentValidator.fromDocument(doc);
            validator.setCertificateVerifier(new CommonCertificateVerifier());
            if (pdfPassword != null && validator
                    instanceof eu.europa.esig.dss.pades.validation.PDFDocumentValidator pdfValidator) {
                pdfValidator.setPasswordProtection(pdfPassword);
            }
            return validator.getSignatures().size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** Reads all CRL byte arrays stored in the DSS /CRLs array of {@code file}. */
    private static List<byte[]> readDssCrlBytes(File file, char[] pdfPassword) {
        try {
            PDDocument pdf = (pdfPassword != null && pdfPassword.length > 0)
                    ? Loader.loadPDF(file, new String(pdfPassword))
                    : Loader.loadPDF(file);
            try {
                return dssCrlStreams(pdf);
            } finally {
                pdf.close();
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    /** Same, for a document held in memory. */
    private static List<byte[]> readDssCrlBytes(DSSDocument doc, char[] pdfPassword) {
        try (InputStream is = doc.openStream()) {
            PDDocument pdf = loadPdf(is.readAllBytes(), pdfPassword);
            try {
                return dssCrlStreams(pdf);
            } finally {
                pdf.close();
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private static List<byte[]> dssCrlStreams(PDDocument pdf) {
        List<byte[]> result = new ArrayList<>();
        org.apache.pdfbox.cos.COSBase dssBase = pdf.getDocumentCatalog()
                .getCOSObject().getDictionaryObject(COSName.getPDFName("DSS"));
        if (!(dssBase instanceof COSDictionary dss)) return result;
        org.apache.pdfbox.cos.COSBase crlsBase =
                dss.getDictionaryObject(COSName.getPDFName("CRLs"));
        if (!(crlsBase instanceof COSArray crls)) return result;
        for (int i = 0; i < crls.size(); i++) {
            org.apache.pdfbox.cos.COSBase obj = crls.getObject(i);
            if (!(obj instanceof COSStream s)) continue;
            try (InputStream is = s.createInputStream()) {
                result.add(is.readAllBytes());
            } catch (Exception ignored) {}
        }
        return result;
    }

    /** A revocation list an augmentation round newly wrote into the document. */
    public record EmbeddedCrl(String issuer, int size) {}

    /**
     * The CRLs {@code augmented} holds that {@code original} did not — what this round actually
     * added, with the issuer named and the size in bytes.
     *
     * <p>Exists so that the preservation UI can say what the operation costs without reproducing
     * the PDF plumbing. Nearly all of a preserved document's growth is these lists: an OCSP
     * response runs to single-digit kilobytes and a time-stamp to a few more, while a CRL from a
     * large CA reaches hundreds of kilobytes — and an incremental save can never take them back.
     */
    public static List<EmbeddedCrl> newlyEmbeddedCrls(File original, DSSDocument augmented,
                                                      char[] pdfPassword) {
        List<byte[]> before = readDssCrlBytes(original, pdfPassword);
        List<EmbeddedCrl> added = new ArrayList<>();
        for (byte[] der : readDssCrlBytes(augmented, pdfPassword)) {
            boolean wasThere = before.stream().anyMatch(b -> java.util.Arrays.equals(b, der));
            if (!wasThere) added.add(new EmbeddedCrl(crlIssuerName(der), der.length));
        }
        return added;
    }

    /** CN of a CRL's issuer, its full DN when there is no CN, or null when it will not parse. */
    private static String crlIssuerName(byte[] der) {
        try {
            org.bouncycastle.cert.X509CRLHolder holder = new org.bouncycastle.cert.X509CRLHolder(der);
            org.bouncycastle.asn1.x500.RDN[] cn =
                    holder.getIssuer().getRDNs(org.bouncycastle.asn1.x500.style.BCStyle.CN);
            if (cn.length > 0) {
                return org.bouncycastle.asn1.x500.style.IETFUtils
                        .valueToString(cn[0].getFirst().getValue());
            }
            return holder.getIssuer().toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Single-pass post-processor that injects {@code extraCrls} (CRLs downloaded via CDP
     * during S33 detection) and CRLs snapshotted from the original file's DSS into /DSS/CRLs
     * and every /DSS/VRI/xxx entry, deduplicated against streams already present.
     * After injection the signing certificate's CRL is removed when a usable OCSP for it is
     * already in the DSS — all other CRLs are kept.
     *
     * Always calls {@code dss.setItem("CRLs", crls)} — whether the array is new or existing —
     * to reliably mark the DSS COSDictionary as updated so that PDFBox's COSIncrement.collect()
     * includes it in the incremental xref.
     */
    private static DSSDocument postProcessDss(DSSDocument augmented, List<byte[]> extraCrls,
                                               char[] pdfPassword,
                                               Map<String, Date> expiredCertsGrants) throws Exception {
        byte[] docBytes;
        try (InputStream is = augmented.openStream()) { docBytes = is.readAllBytes(); }

        PDDocument pdf = (pdfPassword != null && pdfPassword.length > 0)
                ? Loader.loadPDF(docBytes, new String(pdfPassword))
                : Loader.loadPDF(docBytes);
        try {
            org.apache.pdfbox.cos.COSBase dssBase = pdf.getDocumentCatalog()
                    .getCOSObject().getDictionaryObject(COSName.getPDFName("DSS"));
            if (!(dssBase instanceof COSDictionary dss)) return augmented;

            org.apache.pdfbox.cos.COSBase vriBase = dss.getDictionaryObject(COSName.getPDFName("VRI"));
            COSDictionary vri = vriBase instanceof COSDictionary vd ? vd : null;

            // Preserve existing CRLs — let the framework decide what's needed.
            org.apache.pdfbox.cos.COSBase crlsBase = dss.getDictionaryObject(COSName.getPDFName("CRLs"));
            COSArray crls = crlsBase instanceof COSArray ca ? ca : new COSArray();
            List<COSStream> existingStreams = readExistingCrlStreams(crls);

            // Work out what is genuinely missing before touching anything.
            //
            // Marking the DSS dictionary as updated propagates that state to the CRL array and on
            // to every stream in it, and PDFBox then re-serialises all of them into the
            // incremental revision — including CRLs that are already in the file, byte for byte.
            // The same happens to any existing stream added to a VRI entry. On a document being
            // preserved a second time that rewrote a 521 kB CRL for nothing, and an incremental
            // save can never take it back out again.
            //
            // So nothing is touched unless there is something to add. A CRL already in the
            // document keeps whatever VRI references it had — nic se do VRI nedopisuje, viz níž.
            List<byte[]> missingCrls = new ArrayList<>();
            for (byte[] crlBytes : extraCrls) {
                if (findMatchingStream(existingStreams, crlBytes) == null) missingCrls.add(crlBytes);
            }

            if (!missingCrls.isEmpty()) {
                // setItem BEFORE any crls.add() calls.
                // For a freshly created COSArray, originDocumentState is null, making update() a
                // no-op — crls.add() would never mark the array or its elements dirty.
                // setItem() on the already-loaded DSS dict (which has originDocumentState)
                // propagates that state to crls AND marks crls as updated.  Subsequent
                // crls.add(stream) then propagates state to each new stream object so
                // COSIncrement.collect() includes everything in the incremental xref revision.
                dss.setItem(COSName.getPDFName("CRLs"), crls);

                for (byte[] crlBytes : missingCrls) {
                    PDStream stream = new PDStream(pdf);
                    try (OutputStream os = stream.createOutputStream()) { os.write(crlBytes); }
                    COSStream cosStream = stream.getCOSObject();
                    crls.add(cosStream);
                }
            }

            // Slovník /VRI se už nezakládá ani nedoplňuje (9. 9. 2026).
            //
            // Psával se sem — a jen sem, protože DSS ho nevytváří vůbec — se seznamy, které tahle
            // metoda přidává. Záznam VRI pak nesl jen je: na „43. výzva OP AMIF … Uchovano_do_03_08_2034"
            // stály ve VRI dva CRL a odpověď OCSP, kterou do /DSS vložilo DSS, v něm nebyla.
            // Validátor, který by VRI četl, by u toho podpisu žádnou OCSP odpověď nenašel.
            //
            // Dopsat do záznamu i zbytek by znamenalo přidat do něj proudy, které v dokumentu už
            // jsou — a PDFBox je tím označí za změněné a přírůstkový zápis je uloží znovu, celé.
            // Právě tak se kdysi zdvojil půlmegový seznam. Rozhodovací část dokumentu je přitom
            // /DSS: VRI je nepovinné (PDF 2.0 ho zavrhuje) a DSS sám ho nepíše. Poloviční VRI
            // je horší než žádné, takže se nedoplňuje nic.
            //
            // Co už v dokumentu je, se nechává na pokoji — metody níže z existujícího VRI jen
            // odstraňují to, co odstraňují i z /DSS, aby si obojí neodporovalo.

            // Remove from DSS /Certs and VRI /Cert any certificate that is already embedded            // Remove from DSS /Certs and VRI /Cert any certificate that is already embedded
            // in the CMS structure of a signature or timestamp — they're accessible there and
            // duplicating them in DSS bloats the file without adding value.
            purgeCmsDuplicateCerts(pdf, dss, vri);

            // A signing-certificate CRL is deliberately NOT removed here, even when the DSS
            // already carries an OCSP for the same certificate.
            //
            // It used to be, on the grounds that the two say the same thing and the CRL is much
            // the bigger of them. But a PDF is extended by an incremental update: by the time
            // this runs the CRL has already been appended to the file, and dropping the reference
            // frees nothing. Measured on a 184 kB document carrying a 151 kB CRL — removing it
            // changed the result by six bytes, while the CRL stayed in the file at an offset no
            // validator would ever look at. So the removal cost the evidence and saved nothing.
            //
            // Keeping the reference costs the same bytes and leaves the list usable, which
            // matters when the OCSP predates the signature and the CRL does not: then the CRL is
            // the stronger statement about the certificate at the moment it was used.

            // Drop OCSP responses that no conformant validator can use — typically a freshly
            // fetched response for a certificate that has already expired.
            stripUnusableOcsps(pdf, dss, vri);

            // The same for CRLs, plus removal of byte-identical duplicates. DSS collects
            // revocation data per certificate chain and writes what it fetched without checking
            // whether the result is of any use to the certificates actually present, or whether
            // it already wrote the same list while walking another chain.
            stripUnusableAndDuplicateCrls(pdf, dss, vri, expiredCertsGrants);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pdf.saveIncremental(baos);
            return new InMemoryDocument(baos.toByteArray(), augmented.getName(), augmented.getMimeType());
        } finally {
            pdf.close();
        }
    }

    /**
     * Removes from DSS /Certs (and VRI /Cert sub-arrays) any certificate whose DER bytes are
     * already present in the CMS SignedData of a PDF signature or document timestamp.
     */
    private static void purgeCmsDuplicateCerts(PDDocument pdf, COSDictionary dss,
                                                COSDictionary vri) throws Exception {
        // Collect SHA-256 fingerprints of all certs embedded in CMS structures.
        java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
        java.util.Set<String> cmsFingerprints = new java.util.HashSet<>();
        for (PDSignature sig : pdf.getSignatureDictionaries()) {
            byte[] contents = sig.getContents();
            if (contents == null) continue;
            try {
                org.bouncycastle.cms.CMSSignedData cms =
                        new org.bouncycastle.cms.CMSSignedData(contents);
                @SuppressWarnings("unchecked")
                java.util.Collection<org.bouncycastle.cert.X509CertificateHolder> certs =
                        cms.getCertificates().getMatches(null);
                for (org.bouncycastle.cert.X509CertificateHolder cert : certs) {
                    cmsFingerprints.add(hexDigest(sha256, cert.getEncoded()));
                }
            } catch (Exception ignored) {}
        }
        if (cmsFingerprints.isEmpty()) return;

        // Filter the global /Certs array.
        org.apache.pdfbox.cos.COSBase certsBase =
                dss.getDictionaryObject(COSName.getPDFName("Certs"));
        if (certsBase instanceof COSArray existingCerts) {
            COSArray filteredCerts = new COSArray();
            boolean removed = false;
            for (int i = 0; i < existingCerts.size(); i++) {
                org.apache.pdfbox.cos.COSBase deref = existingCerts.getObject(i);
                if (deref instanceof COSStream stream) {
                    try (InputStream is = stream.createInputStream()) {
                        if (cmsFingerprints.contains(hexDigest(sha256, is.readAllBytes()))) {
                            removed = true;
                            continue;
                        }
                    } catch (Exception ignored) {}
                }
                filteredCerts.add(existingCerts.get(i)); // keep original ref (direct or indirect)
            }
            if (removed) dss.setItem(COSName.getPDFName("Certs"), filteredCerts);
        }

        // Filter VRI /Cert sub-arrays for consistency.
        if (vri != null) {
            for (COSName key : vri.keySet()) {
                org.apache.pdfbox.cos.COSBase entry = vri.getDictionaryObject(key);
                if (!(entry instanceof COSDictionary vriEntry)) continue;
                org.apache.pdfbox.cos.COSBase certBase =
                        vriEntry.getDictionaryObject(COSName.getPDFName("Cert"));
                if (!(certBase instanceof COSArray vriCerts)) continue;
                COSArray filteredVriCerts = new COSArray();
                boolean removedFromVri = false;
                for (int i = 0; i < vriCerts.size(); i++) {
                    org.apache.pdfbox.cos.COSBase deref = vriCerts.getObject(i);
                    if (deref instanceof COSStream stream) {
                        try (InputStream is = stream.createInputStream()) {
                            if (cmsFingerprints.contains(hexDigest(sha256, is.readAllBytes()))) {
                                removedFromVri = true;
                                continue;
                            }
                        } catch (Exception ignored) {}
                    }
                    filteredVriCerts.add(vriCerts.get(i));
                }
                if (removedFromVri) vriEntry.setItem(COSName.getPDFName("Cert"), filteredVriCerts);
            }
        }
    }

    /** ISIS-MTT id-isismtt-at-certHash — its presence makes an OCSP usable past cert expiry. */
    private static final org.bouncycastle.asn1.ASN1ObjectIdentifier CERT_HASH_OID =
            new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.3.36.8.3.13");

    /**
     * Removes from DSS /OCSPs (and VRI /OCSP sub-arrays) every OCSP response that carries no
     * usable statement about the certificate it covers, and would therefore be discarded by any
     * conformant validator.
     *
     * <p>The rule mirrors ETSI EN 319 102-1's "revocation information assured" gate as DSS
     * implements it: a response says something about a certificate only when its {@code thisUpdate}
     * falls within that certificate's validity period. Two extensions override the date used:
     * {@code archiveCutOff} (RFC 6960 §4.4.4) supplies an earlier effective date, and
     * {@code certHash} (ISIS-MTT) binds the response to the certificate directly, bypassing the
     * gate altogether. Both are honoured here, so responses that legitimately outlive the
     * certificate are preserved.
     *
     * <p>This matters after augmenting an already-expired signing certificate: the fresh online
     * OCSP fetched during augmentation carries {@code thisUpdate = now}, long past the
     * certificate's {@code notAfter}, so it is dead weight — it enlarges the archived document
     * and, because validators must ignore it, invites the reader to believe a revocation check
     * happened that in fact did not. The response that does carry the verdict is the one embedded
     * back when the certificate was still valid.
     *
     * <p>Deliberately conservative: a response is dropped only when every {@code SingleResp} in it
     * is unusable AND the covered certificate was found in the document. Anything that cannot be
     * judged is kept. CRLs are left untouched — one CRL covers a whole CA, so it may still be
     * required by other, unexpired certificates in the chain.
     */
    private static void stripUnusableOcsps(PDDocument pdf, COSDictionary dss, COSDictionary vri)
            throws Exception {
        org.apache.pdfbox.cos.COSBase ocspsBase =
                dss.getDictionaryObject(COSName.getPDFName("OCSPs"));
        if (!(ocspsBase instanceof COSArray ocsps) || ocsps.size() == 0) return;

        List<org.bouncycastle.cert.X509CertificateHolder> knownCerts =
                collectKnownCertificates(pdf, dss);
        if (knownCerts.isEmpty()) return;

        java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
        java.util.Set<String> droppedFingerprints = new java.util.HashSet<>();
        COSArray kept = new COSArray();
        for (int i = 0; i < ocsps.size(); i++) {
            org.apache.pdfbox.cos.COSBase deref = ocsps.getObject(i);
            byte[] der = null;
            if (deref instanceof COSStream s) {
                try (InputStream is = s.createInputStream()) { der = is.readAllBytes(); }
                catch (Exception ignored) {}
            }
            if (der != null && isOcspUnusable(der, knownCerts)) {
                droppedFingerprints.add(hexDigest(sha256, der));
                continue;
            }
            kept.add(ocsps.get(i)); // keep original ref (direct or indirect)
        }
        if (droppedFingerprints.isEmpty()) return;
        dss.setItem(COSName.getPDFName("OCSPs"), kept);

        // Mirror the removal into the VRI /OCSP sub-arrays so both views stay consistent.
        if (vri == null) return;
        for (COSName key : vri.keySet()) {
            org.apache.pdfbox.cos.COSBase entry = vri.getDictionaryObject(key);
            if (!(entry instanceof COSDictionary vriEntry)) continue;
            org.apache.pdfbox.cos.COSBase vriOcspBase =
                    vriEntry.getDictionaryObject(COSName.getPDFName("OCSP"));
            if (!(vriOcspBase instanceof COSArray vriOcsps)) continue;
            COSArray filtered = new COSArray();
            boolean removedFromVri = false;
            for (int i = 0; i < vriOcsps.size(); i++) {
                org.apache.pdfbox.cos.COSBase deref = vriOcsps.getObject(i);
                if (deref instanceof COSStream s) {
                    try (InputStream is = s.createInputStream()) {
                        if (droppedFingerprints.contains(hexDigest(sha256, is.readAllBytes()))) {
                            removedFromVri = true;
                            continue;
                        }
                    } catch (Exception ignored) {}
                }
                filtered.add(vriOcsps.get(i));
            }
            if (removedFromVri) vriEntry.setItem(COSName.getPDFName("OCSP"), filtered);
        }
    }

    /** True when every SingleResp covers a known certificate and none of them is usable. */
    private static boolean isOcspUnusable(
            byte[] der, List<org.bouncycastle.cert.X509CertificateHolder> knownCerts) {
        try {
            org.bouncycastle.cert.ocsp.OCSPResp resp = new org.bouncycastle.cert.ocsp.OCSPResp(der);
            if (!(resp.getResponseObject()
                    instanceof org.bouncycastle.cert.ocsp.BasicOCSPResp basic)) return false;
            org.bouncycastle.cert.ocsp.SingleResp[] singles = basic.getResponses();
            if (singles == null || singles.length == 0) return false;
            for (org.bouncycastle.cert.ocsp.SingleResp single : singles) {
                org.bouncycastle.cert.X509CertificateHolder covered =
                        findCoveredCertificate(single.getCertID(), knownCerts);
                if (covered == null) return false;      // cannot judge → keep
                if (isSingleRespUsable(single, covered)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;                                // unparseable → keep
        }
    }

    /** Applies the thisUpdate ≤ notAfter gate, honouring archiveCutOff and certHash. */
    private static boolean isSingleRespUsable(
            org.bouncycastle.cert.ocsp.SingleResp single,
            org.bouncycastle.cert.X509CertificateHolder covered) {
        if (single.getExtension(CERT_HASH_OID) != null) return true;

        java.util.Date effective = single.getThisUpdate();
        if (effective == null) return true;             // nothing to compare → keep
        org.bouncycastle.asn1.x509.Extension cutOff = single.getExtension(
                org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers.id_pkix_ocsp_archive_cutoff);
        if (cutOff != null) {
            try {
                effective = org.bouncycastle.asn1.ASN1GeneralizedTime
                        .getInstance(cutOff.getParsedValue()).getDate();
            } catch (Exception ignored) {}
        }
        java.util.Date notAfter = covered.getNotAfter();
        return notAfter == null || !effective.after(notAfter);
    }

    /**
     * Locates the certificate an OCSP CertID refers to. Matching is by serial number confirmed
     * with the CertID's issuerNameHash, so a serial reused by a different CA cannot match.
     */
    private static org.bouncycastle.cert.X509CertificateHolder findCoveredCertificate(
            org.bouncycastle.cert.ocsp.CertificateID certId,
            List<org.bouncycastle.cert.X509CertificateHolder> knownCerts) {
        String digestName = digestNameForOid(certId.getHashAlgOID().getId());
        if (digestName == null) return null;            // unknown alg → cannot judge
        for (org.bouncycastle.cert.X509CertificateHolder cert : knownCerts) {
            if (!certId.getSerialNumber().equals(cert.getSerialNumber())) continue;
            try {
                byte[] issuerDer = cert.getIssuer().getEncoded(org.bouncycastle.asn1.ASN1Encoding.DER);
                byte[] hash = java.security.MessageDigest.getInstance(digestName).digest(issuerDer);
                if (java.util.Arrays.equals(hash, certId.getIssuerNameHash())) return cert;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String digestNameForOid(String oid) {
        return switch (oid) {
            case "1.3.14.3.2.26"          -> "SHA-1";
            case "2.16.840.1.101.3.4.2.1" -> "SHA-256";
            case "2.16.840.1.101.3.4.2.2" -> "SHA-384";
            case "2.16.840.1.101.3.4.2.3" -> "SHA-512";
            default -> null;
        };
    }

    /** All certificates reachable in the document: CMS SignedData of every signature plus DSS /Certs. */
    /**
     * Removes CRLs that are of no use to any certificate in the document, and collapses
     * byte-identical duplicates.
     *
     * <p>Augmentation fetches revocation data per certificate chain, and what comes back is the
     * issuer's <em>current</em> CRL. For a certificate that expired years ago that CRL is dead
     * weight: it was issued long after the certificate ceased to exist, and without an
     * expiredCertsOnCRL extension (ITU-T X.509, OID 2.5.29.60) it makes no statement about
     * it — RFC 5280 clause 3.3 lets the authority drop the entry once the certificate has
     * expired. The
     * document already carries the CRL that does cover the certificate — that is what made it an
     * LT signature in the first place. Keeping the fresh one only inflates the file; the case
     * that prompted this added 842 kB to a 181 kB document.
     *
     * <p>A CRL is kept whenever at least one known certificate needs it, so a fresh CRL still
     * serving a currently valid certificate stays. Only CRLs that serve nothing are dropped.
     */
    private static void stripUnusableAndDuplicateCrls(PDDocument pdf, COSDictionary dss,
                                                      COSDictionary vri,
                                                      Map<String, Date> expiredCertsGrants)
            throws Exception {
        org.apache.pdfbox.cos.COSBase crlsBase = dss.getDictionaryObject(COSName.getPDFName("CRLs"));
        if (!(crlsBase instanceof COSArray crls) || crls.size() == 0) return;

        List<org.bouncycastle.cert.X509CertificateHolder> knownCerts =
                collectKnownCertificates(pdf, dss);

        java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Set<String> droppedFingerprints = new java.util.HashSet<>();
        COSArray kept = new COSArray();

        for (int i = 0; i < crls.size(); i++) {
            org.apache.pdfbox.cos.COSBase deref = crls.getObject(i);
            byte[] der = null;
            if (deref instanceof COSStream st) {
                try (InputStream is = st.createInputStream()) { der = is.readAllBytes(); }
                catch (Exception ignored) { }
            }
            if (der == null) { kept.add(crls.get(i)); continue; }

            String fingerprint = hexDigest(sha256, der);
            if (!seen.add(fingerprint)) {          // byte-identical to one already kept
                droppedFingerprints.add(fingerprint);
                continue;
            }
            if (!knownCerts.isEmpty() && isCrlUseless(der, knownCerts, expiredCertsGrants)) {
                droppedFingerprints.add(fingerprint);
                seen.remove(fingerprint);
                continue;
            }
            kept.add(crls.get(i));
        }
        if (droppedFingerprints.isEmpty()) return;
        dss.setItem(COSName.getPDFName("CRLs"), kept);

        // Mirror into the VRI /CRL sub-arrays so the two views agree.
        if (vri == null) return;
        for (COSName key : vri.keySet()) {
            org.apache.pdfbox.cos.COSBase entry = vri.getDictionaryObject(key);
            if (!(entry instanceof COSDictionary vriEntry)) continue;
            org.apache.pdfbox.cos.COSBase vriCrlBase =
                    vriEntry.getDictionaryObject(COSName.getPDFName("CRL"));
            if (!(vriCrlBase instanceof COSArray vriCrls)) continue;
            COSArray filtered = new COSArray();
            java.util.Set<String> vriSeen = new java.util.HashSet<>();
            boolean changed = false;
            for (int i = 0; i < vriCrls.size(); i++) {
                org.apache.pdfbox.cos.COSBase d = vriCrls.getObject(i);
                if (d instanceof COSStream st) {
                    try (InputStream is = st.createInputStream()) {
                        String fp = hexDigest(sha256, is.readAllBytes());
                        if (droppedFingerprints.contains(fp) || !vriSeen.add(fp)) {
                            changed = true;
                            continue;
                        }
                    } catch (Exception ignored) { }
                }
                filtered.add(vriCrls.get(i));
            }
            if (changed) vriEntry.setItem(COSName.getPDFName("CRL"), filtered);
        }
    }

    /**
     * True when this CRL says nothing about any known certificate: for every certificate whose
     * issuer it matches, it neither names it as revoked nor was issued while it was still alive,
     * nor carries an expiredCertsOnCRL reaching back over its lifetime.
     *
     * <p>The revocation entry is the half that used to be missing, and leaving it out cost more
     * than it saved. A list published long after a certificate expired proves nothing by its
     * <em>silence</em> — the authority may drop the entry once the certificate is dead (RFC 5280
     * clause 3.3) — but an entry that is still there is a plain statement of fact, and it is the
     * same statement {@link RevocationCoverage#saysSomething} weighs during validation, where S21
     * and S30 rest on it. Discarded here, it left {@code T uznavany eIdentity.pdf} carrying a
     * 231 kB list that nothing referenced: DSS had fetched it, written it into the incremental
     * update — where no later edit can take bytes back out — and needed it to accept the document
     * at all. Withholding it from DSS instead was measured and is not an option: the augmentation
     * then fails outright with "Revoked/Suspended certificate(s) detected", so a document AnEP
     * reports as valid could not be preserved.
     */
    private static boolean isCrlUseless(byte[] der,
                                        List<org.bouncycastle.cert.X509CertificateHolder> knownCerts,
                                        Map<String, Date> expiredCertsGrants) {
        try {
            java.security.cert.X509CRL crl = (java.security.cert.X509CRL)
                    java.security.cert.CertificateFactory.getInstance("X.509")
                            .generateCRL(new java.io.ByteArrayInputStream(der));
            byte[] issuerDer = crl.getIssuerX500Principal().getEncoded();
            java.util.Date thisUpdate = crl.getThisUpdate();
            java.util.Date expiredCertsOnCrl = readExpiredCertsOnCrl(crl);

            boolean matchedAnyIssuer = false;
            for (org.bouncycastle.cert.X509CertificateHolder cert : knownCerts) {
                if (!java.util.Arrays.equals(issuerDer, cert.getIssuer().getEncoded())) continue;
                // An OCSP responder marked id-pkix-ocsp-nocheck needs no revocation checking at
                // all (RFC 6960 clause 4.2.2.2.1), so it never makes a CRL worth keeping. Without
                // this, one long-lived responder certificate keeps its issuer's current CRL alive
                // for the whole document — which is exactly how an 842 kB CRL got embedded to
                // serve a signing certificate that had expired years earlier.
                if (hasOcspNoCheck(cert)) continue;
                matchedAnyIssuer = true;
                if (crl.getRevokedCertificate(cert.getSerialNumber()) != null) {
                    return false;   // names this certificate — a statement, not silence
                }
                java.util.Date notAfter = cert.getNotAfter();
                if (thisUpdate == null || notAfter == null || !thisUpdate.after(notAfter)) {
                    return false;   // issued while that certificate was still alive
                }
                if (expiredCertsOnCrl != null && !expiredCertsOnCrl.after(notAfter)) {
                    return false;   // reaches back over that certificate's lifetime
                }
                // The same reach can come from the Trusted List instead of the CRL: a service
                // whose entry carries expiredCertsRevocationInfo keeps publishing revocation for
                // certificates that have expired, and says so once in the list rather than in
                // every list it issues. DSS honours it when validating
                // (RevocationHasInformationAboutCertificateCheck), so discarding such a CRL here
                // would throw away exactly what makes the signature verifiable.
                if (expiredCertsOnCrl == null) {
                    Date granted = grantFor(issuerDer, knownCerts, expiredCertsGrants);
                    if (granted != null && !granted.after(notAfter)) {
                        return false;
                    }
                }
            }
            // A CRL whose issuer matches nothing we know is left alone: it may serve a
            // certificate this method never sees, and dropping it would be a guess.
            return matchedAnyIssuer;

        } catch (Exception e) {
            return false;   // unparseable — keep it rather than discard something usable
        }
    }

    /** True when the certificate carries id-pkix-ocsp-nocheck (OID 1.3.6.1.5.5.7.48.1.5). */
    private static boolean hasOcspNoCheck(org.bouncycastle.cert.X509CertificateHolder cert) {
        try {
            return cert.getExtension(new org.bouncycastle.asn1.ASN1ObjectIdentifier(
                    "1.3.6.1.5.5.7.48.1.5")) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The {@code expiredCertsRevocationInfo} dates the Trusted Lists grant, by the RFC 2253 subject
     * of the certificate each grant is attached to.
     *
     * <p>Read once per preservation run: the alternative is to consult the trust source for every
     * CRL in the document, and the source is shared with whatever validation is running.
     */
    private static Map<String, Date> expiredCertsGrants(CommonCertificateVerifier verifier) {
        Map<String, Date> grants = new java.util.HashMap<>();
        if (verifier == null || verifier.getTrustedCertSources() == null) return grants;
        for (eu.europa.esig.dss.spi.x509.CertificateSource source
                : verifier.getTrustedCertSources().getSources()) {
            if (!(source instanceof eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource tl)) {
                continue;
            }
            for (eu.europa.esig.dss.model.x509.CertificateToken cert : tl.getCertificates()) {
                for (eu.europa.esig.dss.model.tsl.TrustProperties properties
                        : tl.getTrustServices(cert)) {
                    for (eu.europa.esig.dss.model.tsl.TrustServiceStatusAndInformationExtensions svc
                            : properties.getTrustService()) {
                        Date granted = svc.getExpiredCertsRevocationInfo();
                        if (granted == null) continue;
                        String key = dnKey(cert.getCertificate().getSubjectX500Principal().getEncoded());
                        grants.merge(key, granted, (a, b) -> a.before(b) ? a : b);
                    }
                }
            }
        }
        return grants;
    }

    /**
     * Distinguished names are compared as their DER encoding, never as text. The three parties here
     * print the same name three ways — {@code X500Principal} outermost-first, Bouncy Castle's
     * {@code X500Name} in encoding order — and a string comparison silently never matches.
     */
    private static String dnKey(byte[] der) {
        StringBuilder sb = new StringBuilder(der.length * 2);
        for (byte b : der) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                             .append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    /**
     * The grant covering the certificate with this subject, following issuer links upwards when the
     * certificate itself is not the one the list names.
     *
     * <p>Trusted Lists usually name the root, while the CRL is issued by an intermediate beneath
     * it; DSS resolves this by attributing a service to every certificate in the chain below it,
     * and this walk is the same idea with the document's own certificates for company.
     */
    private static Date grantFor(byte[] subjectDer,
                                 List<org.bouncycastle.cert.X509CertificateHolder> knownCerts,
                                 Map<String, Date> grants) throws java.io.IOException {
        if (grants.isEmpty()) return null;
        byte[] current = subjectDer;
        for (int depth = 0; depth < 8 && current != null; depth++) {
            Date granted = grants.get(dnKey(current));
            if (granted != null) return granted;
            byte[] next = null;
            for (org.bouncycastle.cert.X509CertificateHolder cert : knownCerts) {
                if (!java.util.Arrays.equals(cert.getSubject().getEncoded(), current)) continue;
                byte[] issuer = cert.getIssuer().getEncoded();
                if (!java.util.Arrays.equals(issuer, current)) next = issuer;  // self-signed: stop
                break;
            }
            current = next;
        }
        return null;
    }

    /** Reads the expiredCertsOnCRL extension (OID 2.5.29.60), or null when absent. */
    static java.util.Date readExpiredCertsOnCrl(java.security.cert.X509CRL crl) {
        try {
            byte[] raw = crl.getExtensionValue("2.5.29.60");
            if (raw == null) return null;
            byte[] inner = org.bouncycastle.asn1.ASN1OctetString.getInstance(raw).getOctets();
            return org.bouncycastle.asn1.ASN1GeneralizedTime.getInstance(inner).getDate();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<org.bouncycastle.cert.X509CertificateHolder> collectKnownCertificates(
            PDDocument pdf, COSDictionary dss) {
        List<org.bouncycastle.cert.X509CertificateHolder> result = new ArrayList<>();
        for (PDSignature sig : pdf.getSignatureDictionaries()) {
            byte[] contents = sig.getContents();
            if (contents == null) continue;
            try {
                org.bouncycastle.cms.CMSSignedData cms =
                        new org.bouncycastle.cms.CMSSignedData(contents);
                result.addAll(cms.getCertificates().getMatches(null));
            } catch (Exception ignored) {}
        }
        org.apache.pdfbox.cos.COSBase certsBase =
                dss.getDictionaryObject(COSName.getPDFName("Certs"));
        if (certsBase instanceof COSArray certs) {
            for (int i = 0; i < certs.size(); i++) {
                if (!(certs.getObject(i) instanceof COSStream s)) continue;
                try (InputStream is = s.createInputStream()) {
                    result.add(new org.bouncycastle.cert.X509CertificateHolder(is.readAllBytes()));
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    private static String hexDigest(java.security.MessageDigest md, byte[] data) {
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static List<COSStream> readExistingCrlStreams(COSArray crls) {
        List<COSStream> result = new ArrayList<>();
        for (int i = 0; i < crls.size(); i++) {
            org.apache.pdfbox.cos.COSBase obj = crls.getObject(i);
            if (obj instanceof COSStream s) result.add(s);
        }
        return result;
    }

    private static COSStream findMatchingStream(List<COSStream> existing, byte[] target) {
        for (COSStream s : existing) {
            try (InputStream is = s.createInputStream()) {
                if (java.util.Arrays.equals(is.readAllBytes(), target)) return s;
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static final class AugmentationException extends RuntimeException {
        public AugmentationException(String msg) { super(msg); }
        public AugmentationException(String msg, Throwable cause) { super(msg, cause); }
    }
}
