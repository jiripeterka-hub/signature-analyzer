package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.alert.SilentOnStatusAlert;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.TokenExtractionStrategy;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.TimestampBinary;
import eu.europa.esig.dss.service.tsp.OnlineTSPSource;
import eu.europa.esig.dss.spi.DSSUtils;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource;
import eu.europa.esig.dss.validation.SignedDocumentValidator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Comparator;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class TimestampingService {

    /** Result of a timestamp operation: the augmented document plus the TSA signing cert's notAfter. */
    public record TimestampResult(DSSDocument doc, Date tsaCertNotAfter) {}

    private final AtomicReference<CommonCertificateVerifier> verifierRef;

    public TimestampingService(AtomicReference<CommonCertificateVerifier> verifierRef) {
        this.verifierRef = verifierRef;
    }

    public TimestampResult addTimestamp(File inputFile, char[] pdfPassword,
                                        TsaProfile profile, DigestAlgorithm digestAlgorithm) {
        if (verifierRef.get() == null) {
            throw new TimestampingException("Ověřovatel certifikátů není dosud inicializován");
        }

        eu.europa.esig.dss.spi.x509.tsp.TSPSource tspSource =
                TsaSigningKey.observing(buildTspSource(profile), profile);
        try {
            return addTimestampDirect(inputFile, pdfPassword, tspSource, digestAlgorithm);
        } catch (Exception e) {
            throw new TimestampingException("Přidání razítka selhalo: " + e.getMessage(), e);
        }
    }

    /** Variant that accepts an in-memory {@link DSSDocument} — writes to a temp file and delegates. */
    public TimestampResult addTimestamp(DSSDocument doc, char[] pdfPassword,
                                        TsaProfile profile, DigestAlgorithm digestAlgorithm) {
        if (verifierRef.get() == null) {
            throw new TimestampingException("Ověřovatel certifikátů není dosud inicializován");
        }

        java.io.File tmp = null;
        try {
            tmp = java.io.File.createTempFile("anep-preserve-", ".pdf");
            try (java.io.OutputStream os = java.nio.file.Files.newOutputStream(tmp.toPath())) {
                doc.writeTo(os);
            }
            eu.europa.esig.dss.spi.x509.tsp.TSPSource tspSource =
                TsaSigningKey.observing(buildTspSource(profile), profile);
            return addTimestampDirect(tmp, pdfPassword, tspSource, digestAlgorithm);
        } catch (TimestampingException e) {
            throw e;
        } catch (Exception e) {
            throw new TimestampingException("Přidání razítka selhalo: " + e.getMessage(), e);
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    /**
     * Space reserved in the PDF for the first attempt, in bytes of the token.
     *
     * <p>The same figure DSS reserves for a PAdES document time-stamp, and deliberately so: the
     * two applications then produce archives of the same size for the same operation, and a
     * difference in the output points at a difference in what was embedded rather than at padding.
     * Read off DSS's own work — a B → LTA extension of the same document reserves 9 472 B for each
     * of its two stamps, against a token of 4 543 B.
     *
     * <p>It does not fit every token, and that is the point of {@link #addTimestampDirect} retrying.
     * Of 390 RFC 3161 tokens in the sample collection (median 4 543 B, largest 11 299 B) twelve —
     * 3,1 % — are larger than this, so DSS's own default would fail outright on documents that are
     * already in the collection. Here it costs a second request to the TSA and nothing else, which
     * is what makes reserving this tightly affordable at all: without the retry the reservation
     * would have to cover the worst case for every document that will never come near it.
     *
     * <p>It used to be 32 kB, which never failed and never fitted either — the reservation is
     * written into the file as hexadecimal, so each stamp cost 65 538 bytes of which some 56 000
     * were zeroes. On a preservation from level B, which stamps twice, that was 56 kB of padding
     * in a 153 kB increase.
     */
    private static final int INITIAL_SIGNATURE_RESERVE = 9472;

    /** Beyond this a token is not large, something is wrong, and doubling further is no answer. */
    private static final int MAX_SIGNATURE_RESERVE = 128 * 1024;

    /**
     * The reserved space was too small for the token the TSA returned. Carries the size that was
     * actually needed, so the retry can reserve from measurement instead of from another guess.
     */
    private static final class ReserveTooSmall extends RuntimeException {
        final int needed;
        ReserveTooSmall(int needed) {
            super("token needs " + needed + " B", null, false, false);
            this.needed = needed;
        }
    }

    /**
     * Adds the time-stamp, reserving space for it sparingly and starting over with more if the
     * token does not fit.
     *
     * <p>How much room a token needs cannot be known before asking for it, and the question cannot
     * be asked cheaply in advance: the digest sent to the TSA is taken over the document with the
     * placeholder already in it, so a different reservation is a different digest. The reservation
     * therefore has to be chosen first and tested afterwards.
     *
     * <p>Testing it is exact rather than inferred. The token arrives in the signing callback, where
     * its length can simply be compared with what was set aside; PDFBox would also refuse to write
     * it, but only by throwing an {@link java.io.IOException} whose text would have to be matched,
     * and a message is not something to hang behaviour on across library versions.
     *
     * <p>A retry costs a second request to the TSA. That is affordable at roughly one document in
     * thirty — see {@link #INITIAL_SIGNATURE_RESERVE} — and it is what lets the reservation be set
     * to what a token usually needs instead of to what one might conceivably need.
     *
     * <p>Viditelné v balíku, ne soukromé, a razítkovací zdroj sem přichází jako rozhraní: je to
     * jediné místo, kterým se dá úkon pustit bez sítě a bez objednávky u autority. Měření
     * „zůstane dokument po uchování, přerazítkování či prodloužení stále PDF/A?" tudy prochází
     * s jednorázovou autoritou, jejíž razítko nikdo nikdy neověřuje — ta otázka je o struktuře
     * souboru, ne o důvěryhodnosti razítka.
     */
    // Non-static: needs verifierRef for the DSS-validation fallback in extractTsaCertNotAfterFromDoc.
    TimestampResult addTimestampDirect(
            File inputFile, char[] pdfPassword,
            eu.europa.esig.dss.spi.x509.tsp.TSPSource tspSource, DigestAlgorithm digestAlgorithm) throws Exception {

        int reserve = INITIAL_SIGNATURE_RESERVE;
        while (true) {
            try {
                return stampOnce(inputFile, pdfPassword, tspSource, digestAlgorithm, reserve);
            } catch (ReserveTooSmall e) {
                if (reserve >= MAX_SIGNATURE_RESERVE) {
                    throw new TimestampingException("Časové razítko se nevejde do dokumentu:"
                            + " token má " + e.needed + " B, což je víc, než kolik lze rezervovat.");
                }
                // Room for the token that was actually returned, with the same margin again — a
                // token this size means the TSA embeds more than the sample ever showed, and the
                // next one from it will be no smaller.
                reserve = Math.min(MAX_SIGNATURE_RESERVE, Math.max(reserve * 2, e.needed * 2));
            }
        }
    }

    /**
     * Nepřímý odkaz na stránku tak, jak ho nese strom stránek ({@code /Kids} rodiče), místo
     * slovníku stránky samotného. Slovník vložený jako hodnota do nového objektu přibere PDFBox do
     * přírůstkové revize celý; odkaz zapíše jen jako „n 0 R“. Nenajde-li se, vrátí slovník.
     */
    private static org.apache.pdfbox.cos.COSBase pageReference(PDPage page) {
        org.apache.pdfbox.cos.COSDictionary dict = page.getCOSObject();
        org.apache.pdfbox.cos.COSDictionary parent = dict.getCOSDictionary(COSName.PARENT);
        COSArray kids = parent != null ? parent.getCOSArray(COSName.KIDS) : null;
        if (kids != null) {
            for (int i = 0; i < kids.size(); i++) {
                org.apache.pdfbox.cos.COSBase raw = kids.get(i);
                if (raw instanceof org.apache.pdfbox.cos.COSObject ref && ref.getObject() == dict) {
                    return ref;
                }
            }
        }
        return dict;
    }

    private TimestampResult stampOnce(
            File inputFile, char[] pdfPassword,
            eu.europa.esig.dss.spi.x509.tsp.TSPSource tspSource, DigestAlgorithm digestAlgorithm, int reserve)
            throws Exception {

        PDDocument pdf = loadPdf(inputFile, pdfPassword);
        try {
            PDPage lastPage = pdf.getPage(pdf.getNumberOfPages() - 1);

            PDAcroForm acroForm = pdf.getDocumentCatalog().getAcroForm();
            if (acroForm == null) {
                acroForm = new PDAcroForm(pdf);
                pdf.getDocumentCatalog().setAcroForm(acroForm);
            }

            // Create PDSignature first — must pre-assign to field before addSignature().
            PDSignature sig = new PDSignature();
            sig.setType(COSName.getPDFName("DocTimeStamp"));
            sig.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            sig.setSubFilter(COSName.getPDFName("ETSI.RFC3161"));

            // PDSignatureField constructor creates a proper merged field+widget dict (no /Kids),
            // so getWidgets().get(0) inside addSignature() returns a valid widget.
            PDSignatureField sigField = new PDSignatureField(acroForm);
            sigField.getCOSObject().setItem(COSName.F, COSInteger.get(4)); // Printable, invisible
            COSArray rect = new COSArray();
            rect.add(COSInteger.ZERO); rect.add(COSInteger.ZERO);
            rect.add(COSInteger.ZERO); rect.add(COSInteger.ZERO);
            sigField.getCOSObject().setItem(COSName.RECT, rect);
            // /P page reference makes Adobe Reader show the stamp in the signature panel.
            // NOT calling page.addAnnotation() which would modify /Annots → "Annotations changed".
            //
            // Stránka se přitom přepsat nesmí. Dokud se do /P vkládal slovník stránky
            // (lastPage.getCOSObject()), přibral ho PDFBox 3 do přírůstkové revize celý — obsah beze
            // změny, jen jinak zformátovaný — a Acrobat pak hlásil „Anotace změněny“ u každého
            // odkazu, jehož cíl na tu stránku míří. Nález uživatele 18. 10. 2026 nad dokumentem
            // s obsahem na str. 2, kde poslední odkaz vedl na poslední stránku; Signer tutéž operaci
            // provedl bez přepsání stránky a hlášení nebylo. Shození příznaku setNeedToBeUpdated
            // nepomohlo (změřeno); pomohl až nepřímý odkaz, který se zapíše jen jako „n 0 R“.
            sigField.getCOSObject().setItem(COSName.P, pageReference(lastPage));

            // Pre-assign sig (/V) so findSignatureField() inside addSignature() finds this field.
            // Without /V, findSignatureField() skips it (bytecode: getSignature() == null → ifnull skip).
            sigField.setValue(sig);
            acroForm.getFields().add(sigField);

            // Capture the raw timestamp response bytes so we can extract the TSA cert notAfter.
            AtomicReference<byte[]> capturedTsBytes = new AtomicReference<>();

            SignatureOptions options = new SignatureOptions();
            options.setPreferredSignatureSize(reserve);
            pdf.addSignature(sig, content -> {
                try {
                    byte[] contentBytes = content.readAllBytes();
                    byte[] hash = DSSUtils.digest(digestAlgorithm, contentBytes);
                    TimestampBinary tb = tspSource.getTimeStampResponse(digestAlgorithm, hash);
                    byte[] tsBytes = tb.getBytes();
                    // Checked here, where the answer is a plain comparison, rather than left to
                    // PDFBox's own refusal further down: it reports the same thing as an IOException
                    // whose text would have to be recognised by matching. Thrown unwrapped so the
                    // retry loop sees it — PDFBox declares only IOException, but a RuntimeException
                    // travels out of the callback intact.
                    if (tsBytes.length > reserve) throw new ReserveTooSmall(tsBytes.length);
                    capturedTsBytes.set(tsBytes);
                    return tsBytes;
                } catch (ReserveTooSmall e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException("TSP request failed: " + e.getMessage(), e);
                }
            }, options);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pdf.saveIncremental(baos);
            byte[] resultBytes = baos.toByteArray();

            // First try: extract TSA cert notAfter from the CMS token in the TSA response.
            // Second try: run a lightweight DSS validation on the resulting document — DSS builds
            // the full TSA cert chain via AIA so the signing cert is always available.
            Date tsaCertNotAfter = extractTsaCertNotAfterFromToken(capturedTsBytes.get());
            if (tsaCertNotAfter == null) {
                tsaCertNotAfter = extractTsaCertNotAfterFromDoc(resultBytes);
            }
            return new TimestampResult(new InMemoryDocument(resultBytes), tsaCertNotAfter);
        } finally {
            pdf.close();
        }
    }

    /**
     * Tries to extract the TSA signing cert's notAfter from raw time-stamp DER bytes.
     * Returns null if the cert is not embedded in the token (some TSAs omit it).
     *
     * <p>Both encodings are accepted, because both turn up: {@code TSPSource} hands back the bare
     * token (a CMS ContentInfo — that is what goes into a signature or a PDF /Contents), while a
     * response straight off the wire is a TimeStampResp wrapping it.
     */
    static Date extractTsaCertNotAfterFromToken(byte[] tsBytes) {
        if (tsBytes == null) return null;
        try {
            TimeStampToken tst = parseToken(tsBytes);
            if (tst == null) return null;

            // Primary: match against the signer's SID.
            var signers = tst.toCMSSignedData().getSignerInfos().getSigners();
            if (!signers.isEmpty()) {
                var sid = signers.iterator().next().getSID();
                @SuppressWarnings("unchecked")
                var byId = (java.util.Collection<X509CertificateHolder>)
                        tst.getCertificates().getMatches(sid);
                if (!byId.isEmpty()) {
                    return byId.iterator().next().getNotAfter();
                }
            }

            // Fallback: pick the end-entity cert (earliest notAfter, not a CA) from all embedded certs.
            @SuppressWarnings("unchecked")
            var all = (java.util.Collection<X509CertificateHolder>)
                    tst.getCertificates().getMatches(null);
            return all.stream()
                    .filter(c -> !isCA(c))
                    .map(X509CertificateHolder::getNotAfter)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static TimeStampToken parseToken(byte[] tsBytes) {
        try {
            return new TimeStampToken(new org.bouncycastle.cms.CMSSignedData(tsBytes));
        } catch (Exception notAToken) {
            try {
                return new TimeStampResponse(tsBytes).getTimeStampToken();
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static boolean isCA(X509CertificateHolder cert) {
        try {
            var ext = cert.getExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints);
            return ext != null && org.bouncycastle.asn1.x509.BasicConstraints
                    .getInstance(ext.getParsedValue()).isCA();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fallback: run a lightweight DSS validation on {@code docBytes} and return the notAfter
     * of the signing certificate of the most recently added document timestamp.
     * DSS resolves the TSA cert chain via AIA even when the cert is not embedded in the token.
     */
    private Date extractTsaCertNotAfterFromDoc(byte[] docBytes) {
        CommonCertificateVerifier base = verifierRef.get();
        if (base == null) return null;
        try {
            // Fresh verifier: copy trust sources + AIA for chain building.
            // No OCSP/CRL sources so validation completes quickly without online fetching.
            CommonCertificateVerifier lv = new CommonCertificateVerifier();
            for (var src : base.getTrustedCertSources().getSources()) {
                lv.addTrustedCertSources(src);
            }
            lv.setAIASource(new DefaultAIASource(TrustConfig.createDataLoader()));
            lv.setAlertOnMissingRevocationData(new SilentOnStatusAlert());
            lv.setAlertOnRevokedCertificate(new SilentOnStatusAlert());
            lv.setAlertOnInvalidTimestamp(new SilentOnStatusAlert());

            SignedDocumentValidator validator = SignedDocumentValidator.fromDocument(
                    new InMemoryDocument(docBytes));
            validator.setCertificateVerifier(lv);
            validator.setTokenExtractionStrategy(TokenExtractionStrategy.NONE);

            var dd = validator.validateDocument().getDiagnosticData();
            // The new timestamp has the latest production time — take its signing cert notAfter.
            return dd.getTimestampList().stream()
                    .filter(t -> t.getProductionTime() != null)
                    .max(Comparator.comparingLong(t -> t.getProductionTime().getTime()))
                    .map(t -> t.getSigningCertificate() != null
                            ? t.getSigningCertificate().getNotAfter() : null)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static PDDocument loadPdf(File file, char[] pdfPassword) throws Exception {
        return (pdfPassword != null && pdfPassword.length > 0)
                ? Loader.loadPDF(file, new String(pdfPassword))
                : Loader.loadPDF(file);
    }

    /**
     * Spojení s autoritou, i s tím, čím se u ní aplikace prokáže.
     *
     * <p>Dva způsoby přihlášení a každý sahá do jiné vrstvy. Jméno a heslo jde do hlavičky HTTP;
     * klientský certifikát o patro níž, do navazování TLS, takže se nastavuje na loaderu a ne na
     * žádosti. Právě proto je přepnutí mezi nimi u téhož poskytovatele zpravidla i změna adresy —
     * PostSignum DEMO má pro heslo port 444 a pro certifikát 443.
     *
     * <p>Klientský certifikát si s naší {@code TrustStrategy} (Avast, Windows-ROOT, přibalené
     * české kořeny) nepřekáží. Ověřeno v bajtkódu {@code CommonsDataLoader.getSSLContextBuilder}:
     * důvěryhodný a klíčový materiál se do {@code SSLContextBuilder} zavádějí dvěma nezávislými
     * voláními, takže zavedením klíče se okruh důvěry nepřepíše.
     */
    private static OnlineTSPSource buildTspSource(TsaProfile profile) {
        var loader = TrustConfig.createTimestampDataLoader();
        switch (profile.auth()) {
            case BASIC -> {
                try {
                    URI uri = new URI(profile.getUrl());
                    loader.addAuthentication(uri.getHost(), uri.getPort(), null,
                            profile.getUsername(), profile.getPassword().toCharArray());
                    loader.setPreemptiveAuthentication(true);
                } catch (Exception ignored) {}
            }
            case CERTIFICATE -> applyClientCertificate(loader, profile);
            case NONE -> { }
        }
        OnlineTSPSource tspSource = new OnlineTSPSource(profile.getUrl());
        tspSource.setDataLoader(loader);
        return tspSource;
    }

    /**
     * Zavede klientský certifikát do TLS — a napřed se přesvědčí, že se úložiště vůbec otevře.
     *
     * <p>Kontrola napřed je celý smysl téhle metody. DSS otevírá úložiště až ve chvíli, kdy staví
     * {@code SSLContext}, tedy uprostřed žádosti, a chybějící soubor či špatné heslo odtamtud
     * vyletí jako {@code IllegalArgumentException("Unable to configure the SSLContext")}. To by se
     * uživateli ukázalo jako „od zdroje se nepodařilo získat časové razítko" — tedy obvinění
     * autority z něčeho, co se stalo na jeho počítači.
     *
     * <p>Rozlišují se proto tři odpovědi: soubor tam není, heslo nesedí, a obsah není úložiště
     * toho druhu, za jaký se podle přípony vydává. Java hlásí špatné heslo jako
     * {@code IOException}, jejíž příčinou je {@code UnrecoverableKeyException} — na rozdíl od
     * porušeného souboru, kde příčina chybí.
     *
     * <p>Na rozdíl od jména a hesla se tu chyba <b>nepolyká</b>. U Basic se polykat dá a dělá se
     * to odjakživa: špatné heslo stejně pozná až server a jeho odpověď je srozumitelná. Nečitelné
     * úložiště naproti tomu znamená, že se spojení naváže <b>bez certifikátu</b>, autorita odpoví
     * 403 a uživatel se o svém klíči nedozví nic.
     */
    private static void applyClientCertificate(
            eu.europa.esig.dss.service.http.commons.CommonsDataLoader loader, TsaProfile profile) {
        File store = new File(profile.getClientCertPath());
        if (!store.isFile()) {
            throw new TimestampingException("Soubor s klientským certifikátem nebyl nalezen: "
                    + profile.getClientCertPath());
        }
        char[] pass = profile.getClientCertPassword().toCharArray();
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(store.toPath())) {
            java.security.KeyStore.getInstance(profile.clientCertStoreType()).load(in, pass);
        } catch (java.io.IOException e) {
            boolean badPassword = e.getCause() instanceof java.security.UnrecoverableKeyException;
            throw new TimestampingException(badPassword
                    ? "Heslo k úložišti s klientským certifikátem nesouhlasí."
                    : "Úložiště s klientským certifikátem se nepodařilo přečíst: "
                      + e.getMessage(), e);
        } catch (Exception e) {
            throw new TimestampingException("Soubor „" + store.getName() + "“ není úložištěm typu "
                    + profile.clientCertStoreType() + ": " + e.getMessage(), e);
        }
        loader.setSslKeystore(new eu.europa.esig.dss.model.FileDocument(store));
        loader.setSslKeystoreType(profile.clientCertStoreType());
        loader.setSslKeystorePassword(pass);
    }

    /**
     * Co se o autoritě zjistilo: dokdy platí její razítkovací certifikát, které otiskové funkce
     * přijímá a zda jsou její razítka kvalifikovaná.
     *
     * @param certNotAfter konec platnosti; null, když certifikát v tokenu nebyl
     * @param supportedHashes {@code TsaProfile.HASH_*}, na které odpověděla razítkem
     * @param quality co o kvalifikovanosti řekly důvěryhodné seznamy; viz {@link #measureQuality}
     */
    public record TsaProbe(Date certNotAfter, java.util.List<String> supportedHashes,
                           TsaProfile.Quality quality, TsaSigningKey signingKey) {}

    /** Jedno vyžádané razítko — i s bajty tokenu, protože se z nich dá přečíst víc než datum. */
    private record ProbeStamp(byte[] token, Date certNotAfter) {}

    /**
     * Zeptá se autority na každou ze tří otiskových funkcí a zapamatuje si, které vzala.
     *
     * <p>Na každou zvlášť, protože podpora souvislá být nemusí. Vypadalo rozumně zkoušet
     * odshora a prvním úspěchem skončit — jenže I.CA na SHA-512 odpoví razítkem, SHA-384 odmítne
     * („Message digest algorithm is not supported", PKIFailureInfo 0x80) a SHA-256 zase vezme.
     * Kdo by z první odpovědi usoudil na strop, nabídne uživateli funkci, kterou autorita odmítá.
     *
     * <p>Stojí to tři žádosti místo jedné, tedy u placené autority tři razítka z kvóty. Děje se
     * to jednou za zdroj a je to jediná cena, za kterou se nabídka dá mít správně.
     *
     * <p>Nedostupný zdroj se pozná a další dotazy se nepokládají. Odmítnutí funkce a nedosažitelný
     * server vypadají v kódu stejně — obojí je výjimka — jenže z prvního se dá usoudit na podporu
     * a z druhého vůbec nic. Bez toho rozlišení by výpadek sítě mlčky prohlásil zdroj za
     * nepodporující nic, přepsal by mu nastavenou funkci a čekání na tři vypršelá spojení by
     * trvalo minutu místo dvaceti vteřin.
     *
     * <p>Metoda je <b>instanční</b>, ačkoli o síť se stará sama: potřebuje ověřovatele certifikátů,
     * aby vydané razítko poměřila s důvěryhodnými seznamy. Bez toho by kvalifikovanost zbyla na
     * zaškrtávátku v nastavení, tedy na tvrzení toho, kdo zdroj zadával.
     *
     * @throws TimestampingException když se autorita neozvala vůbec, nebo odmítla všechny tři
     */
    public TsaProbe probe(TsaProfile profile) {
        java.util.List<String> supported = new java.util.ArrayList<>();
        Date notAfter = null;
        byte[] token = null;
        TimestampingException last = null;

        for (String hash : TsaProfile.allHashAlgorithms()) {
            try {
                ProbeStamp stamp = requestStamp(profile, resolveAlgorithm(hash));
                supported.add(hash);
                if (notAfter == null) notAfter = stamp.certNotAfter();
                if (token == null) token = stamp.token();
            } catch (TimestampingException e) {
                last = e;
                if (unreachable(e)) throw e;
            }
        }
        if (supported.isEmpty()) {
            throw last != null ? last
                    : new TimestampingException("Zdroj neodpověděl na žádnou otiskovou funkci");
        }
        return new TsaProbe(notAfter, supported, measureQuality(token),
                TsaSigningKey.fromToken(token));
    }

    /**
     * Je razítko od téhle autority kvalifikované?
     *
     * <p>Neptá se na to uživatele a neodvozuje to ze jména zdroje: <b>posoudí vydané razítko</b>,
     * a to touž cestou, kterou aplikace posuzuje razítka nalezená v dokumentech — DSS ho ověří
     * proti evropským důvěryhodným seznamům a vrátí {@code TimestampQualification}. Zdroj, který
     * se jmenuje „Qualified“ a v seznamech není, tak vyjde nekvalifikovaně, a testovací DEMO
     * autorita se pozná sama od sebe, aniž by se kdokoli musel na něco rozpomenout.
     *
     * <p>Razítko nemá orazítkovaná data a mít je nemůže — vzniklo nad náhodným otiskem bez
     * předobrazu (viz {@link #requestStamp}). Kvalifikovanost na tom nestojí: rozhoduje se podle
     * certifikátu vydavatele a podle toho, co o jeho službě říká důvěryhodný seznam v čase vydání,
     * ne podle toho, zda otisk k něčemu sedí.
     *
     * <p><b>Nezařazeno (NA) znamená nekvalifikované, ne nezjištěno.</b> DSS vrací
     * {@code TimestampQualification.NA} pro razítko, ke kterému v důvěryhodných seznamech nenašlo
     * službu — a kvalifikované razítko je podle eIDAS právě to, za nímž stojí služba v seznamu
     * uvedená. Absence tedy odpověď je, ne její chybění. Bez tohohle rozlišení vycházela testovací
     * autorita PostSignum DEMO jako „nezjištěno" a texty o ní mluvily jako o kvalifikované
     * (změřeno 18. 9. 2026).
     *
     * <p>Právě proto se ale napřed ověřuje, že <b>je podle čeho soudit</b>: nenačtené seznamy dají
     * NA úplně stejně a bez téhle pojistky by se jimi nekvalifikovaným prohlásil i zdroj, který
     * kvalifikovaný je. Prázdný okruh důvěry proto vrací „nevím".
     *
     * <p>Odpověď „nevím“ je plnohodnotná a vrací se vždy, když měření neproběhlo: bez načtených
     * seznamů, bez certifikátu v tokenu, při jakékoli výjimce. Texty pro takový zdroj mluví jako
     * dosud, tedy o „(kvalifikovaném elektronickém)“ razítku v závorkách — tvrdit nekvalifikovanost
     * z toho, že se něco nepodařilo změřit, by bylo horší než mlčet.
     *
     * <p>Co se cestou stalo, vypíše {@code -Danep.tsatrace=true}. Bez toho je odpověď „nevím"
     * nerozeznatelná od odpovědi „nevím" z úplně jiné příčiny a ladit se to dá jen hádáním.
     */
    private TsaProfile.Quality measureQuality(byte[] token) {
        CommonCertificateVerifier base = verifierRef.get();
        if (token == null) return traceQuality("token chybí", TsaProfile.Quality.UNKNOWN);
        if (base == null) return traceQuality("ověřovatel není", TsaProfile.Quality.UNKNOWN);
        try {
            var trusted = base.getTrustedCertSources();
            if (trusted == null || trusted.getNumberOfCertificates() == 0) {
                return traceQuality("okruh důvěry je prázdný", TsaProfile.Quality.UNKNOWN);
            }
            CommonCertificateVerifier lv = new CommonCertificateVerifier();
            for (var src : trusted.getSources()) {
                lv.addTrustedCertSources(src);
            }
            lv.setAIASource(new DefaultAIASource(TrustConfig.createDataLoader()));
            lv.setAlertOnMissingRevocationData(new SilentOnStatusAlert());
            lv.setAlertOnRevokedCertificate(new SilentOnStatusAlert());
            lv.setAlertOnInvalidTimestamp(new SilentOnStatusAlert());

            var validator = new eu.europa.esig.dss.validation.timestamp.DetachedTimestampValidator(
                    new InMemoryDocument(token));
            validator.setCertificateVerifier(lv);
            validator.setTokenExtractionStrategy(TokenExtractionStrategy.NONE);

            var simple = validator.validateDocument().getSimpleReport();
            String id = simple.getFirstTimestampId();
            if (id == null) {
                return traceQuality("v tokenu není razítko", TsaProfile.Quality.UNKNOWN);
            }
            var qualification = simple.getTimestampQualification(id);
            if (qualification == null) {
                return traceQuality("DSS kvalifikaci nevrátil", TsaProfile.Quality.UNKNOWN);
            }
            return traceQuality("DSS: " + qualification,
                    qualification == eu.europa.esig.dss.enumerations.TimestampQualification.QTSA
                            ? TsaProfile.Quality.QUALIFIED
                            : TsaProfile.Quality.UNQUALIFIED);
        } catch (Exception e) {
            return traceQuality("výjimka: " + e, TsaProfile.Quality.UNKNOWN);
        }
    }

    private static TsaProfile.Quality traceQuality(String why, TsaProfile.Quality quality) {
        if (Boolean.getBoolean("anep.tsatrace")) {
            System.out.println("[tsa] kvalifikovanost = " + quality + "  (" + why + ")");
        }
        return quality;
    }

    /**
     * Zda selhání znamená „server jsme nepotkali", a ne „server řekl ne".
     *
     * <p>Nedosažitelnost se pozná na úrovni spojení: nepřeložené jméno, odmítnuté nebo vypršelé
     * připojení. Odpověď HTTP 400 sem **nepatří**, i když je to také {@code IOException} —
     * portugalská autorita jí odmítá SHA-512 a SHA-384, kdežto SHA-256 vezme, takže server
     * odpovídá a jeho odpověď o podpoře vypovídá.
     */
    private static boolean unreachable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException
                    || t instanceof java.net.ConnectException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.NoRouteToHostException) {
                return true;
            }
            if (t.getClass().getSimpleName().equals("HttpHostConnectException")) return true;
        }
        return false;
    }

    /**
     * Vyžádá si od autority jedno razítko jen proto, aby se z něj přečetl konec platnosti jejího
     * certifikátu, a nic jím nerazítkuje.
     *
     * <p>Jiná cesta k tomu údaji nevede. Autorita ho nikde nepublikuje — certifikát posílá
     * v tokenu, a token vydá jedině na požádání. Razítko se proto pořídí nad otiskem, který nemá
     * žádný předobraz: náhodných 32 bajtů. Nic tím není orazítkované, protože k žádnému dokumentu
     * ten otisk nepatří a nikdy patřit nebude, takže vzniklý token nemůže sloužit jako důkaz
     * o čemkoli. To je záměr — vyžádat si razítko nad skutečným dokumentem by znamenalo pořídit
     * doklad, o který nikdo nestál.
     *
     * <p>Stojí to jednu žádost na autoritu při potvrzení zdroje v nastavení. U placené autority
     * je to jedno razítko z kvóty; proto se to dělá jen jednou, a ne při každém spuštění.
     *
     * @return konec platnosti podepisujícího certifikátu, nebo null, když ho token nenese
     * @throws TimestampingException když se s autoritou nepodařilo domluvit
     */
    public static Date probeCertNotAfter(TsaProfile profile, DigestAlgorithm digestAlgorithm) {
        return requestStamp(profile, digestAlgorithm).certNotAfter();
    }

    /**
     * Totéž, ale i s bajty tokenu.
     *
     * <p>Rozděleno proto, že se z jednoho razítka čtou <b>dvě</b> věci: konec platnosti
     * certifikátu a kvalifikovanost služby ({@link #measureQuality}). Vyžádat si kvůli každé
     * vlastní razítko by u placené autority znamenalo dvakrát tolik z kvóty za totéž.
     */
    private static ProbeStamp requestStamp(TsaProfile profile, DigestAlgorithm digestAlgorithm) {
        if (profile == null || profile.getUrl() == null || profile.getUrl().isBlank()) {
            throw new TimestampingException("Zdroj časových razítek nemá adresu");
        }
        // Chyby v klientském certifikátu se hlásí tak, jak přišly: mluví o uživatelově souboru,
        // ne o autoritě, a obalit je do „od zdroje se nepodařilo získat razítko" by je proměnilo
        // v obvinění druhé strany.
        var tspSource = buildTspSource(profile);
        try {
            // Otisk musí mít přesně tolik bajtů, kolik jich ta funkce dává, jinak žádost odmítne
            // autorita, ne my — proto se opravdu počítá, místo aby se délka odhadovala.
            byte[] seed = new byte[32];
            new java.security.SecureRandom().nextBytes(seed);
            byte[] digest = java.security.MessageDigest
                    .getInstance(digestAlgorithm.getJavaName()).digest(seed);
            var token = tspSource.getTimeStampResponse(digestAlgorithm, digest);
            return new ProbeStamp(token.getBytes(),
                    extractTsaCertNotAfterFromToken(token.getBytes()));
        } catch (Exception e) {
            throw new TimestampingException(failureMessage(profile, e), e);
        }
    }

    /**
     * Proč razítko nepřišlo, řečeno tak, aby se podle toho dalo něco udělat.
     *
     * <p>Jedna věta navíc, a je nutná. Adresa pro přihlášení certifikátem ukončí spojení hláškou
     * {@code handshake_failure}, a to <b>doslova stejně</b> pro certifikát, který nepřijímá, jako
     * pro žádný certifikát — změřeno 18. 9. 2026 na {@code demo.postsignum.cz}: obojí skončilo
     * týmž {@code Received fatal alert: handshake_failure}. Server totiž o důvod odmítnutí
     * klienta zásadně nedělí a nic jiného než tuhle větu neřekne.
     *
     * <p>Holé „nepodařilo se získat časové razítko: handshake_failure" tedy posílá uživatele
     * hledat výpadek sítě tam, kde je ve hře jeho vlastní klíč. Odtud ten dodatek — a jen
     * u přihlášení certifikátem, protože jinde by to byla rada mimo.
     */
    private static String failureMessage(TsaProfile profile, Exception e) {
        String base = "Od zdroje se nepodařilo získat časové razítko: " + e.getMessage();
        if (profile.auth() != TsaProfile.Auth.CERTIFICATE || !handshakeFailed(e)) return base;
        return base + " — server ukončil zabezpečené spojení při ověřování klientského"
                + " certifikátu. Obvykle to znamená, že certifikát nepřijímá (bývá vyžadován"
                + " certifikát od určité autority), nebo že v úložišti není k certifikátu"
                + " privátní klíč. Stejnou odpověď dá i na spojení bez certifikátu, takže"
                + " o dostupnosti služby to neříká nic.";
    }

    private static boolean handshakeFailed(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof javax.net.ssl.SSLHandshakeException) return true;
        }
        return false;
    }

    public static DigestAlgorithm resolveAlgorithm(String name) {
        return switch (name) {
            case TsaProfile.HASH_SHA384 -> DigestAlgorithm.SHA384;
            case TsaProfile.HASH_SHA256 -> DigestAlgorithm.SHA256;
            default                     -> DigestAlgorithm.SHA512;
        };
    }

    public static final class TimestampingException extends RuntimeException {
        public TimestampingException(String msg)                  { super(msg); }
        public TimestampingException(String msg, Throwable cause) { super(msg, cause); }
    }
}
