package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.diagnostic.CertificateRevocationWrapper;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.RevocationWrapper;
import eu.europa.esig.dss.enumerations.RevocationType;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
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
import java.util.List;

/**
 * Přerazítkování — writes the revocation information the validation found into the document's DSS
 * dictionary, except what tells nobody anything, and leaves it to the caller to add a time-stamp.
 *
 * <h2>Why this exists next to {@link AugmentationService}</h2>
 *
 * <p>Uchování produces an artefact for a validator that follows ETSI EN 319 102-1. Such a validator
 * asks what a piece of revocation information can be held to <em>prove</em>, and on that question a
 * list published after the certificate expired proves nothing: a CA may drop entries for expired
 * certificates (RFC 5280 clause 3.3), so the certificate's absence from it is not a statement that
 * it was never revoked. DSS refuses to embed such material and {@code isCrlUseless()} strips out
 * whatever slips through — deliberately, and that must stay as it is.
 *
 * <p>A large part of Czech practice validates differently. CzechPOINT, the filing rooms of public
 * authorities, and comparable readers ask whether the certificate was within its validity period
 * and whether any list or response says it was revoked. Post-expiry material answers that question
 * for them, as does material from an issuer whose own certificate has since expired, and SHA-1 does
 * not disqualify a signature. For a document heading to such a reader the strict cleanup is not a
 * safeguard but the thing standing in the way — and the document AnEP would otherwise hand over is
 * a fixation container, which no automated validator reads at all.
 *
 * <p>What such a reader needs from this path is the time-stamp — not the revocation material. Až do
 * 17. 10. 2026 se tu vkládalo doslova vše, co ověření vidělo, i odpověď „nerevokován“ vydaná po
 * expiraci certifikátu. Měření ten předpoklad vyvrátilo (sondy {@code RestampMaterialScan}
 * a {@code RestampCompletenessScan}, D:\tmp a D:\Downloads): u 99 ze 175 nabídnutých dokumentů byl
 * takový údaj k certifikátu jediný, a přesto ho nikdo nevyužil. CzechPOINT uznal podpis za platný
 * u podoby <b>jen s razítkem</b>, bez vloženého údaje — revokaci si opatřil sám. Acrobat ukázal
 * u podoby s údajem i bez něj totéž, neznámou platnost, a vloženou OCSP odmítl („Odezva OCSP má
 * propadlou platnost…“, stejně jako 21. 9. 2026). Standardy jinak ani nedovolují: EN 319 102-1
 * (5.2.6 krok 4, pozn. 4 a 8) bere post-expirační údaj jen od vydavatele, o němž je známo, že stav
 * po expiraci drží, a EN 319 411-2 (CSS-6.3.10) k tomu dává právě {@code expiredCertsOnCRL}
 * a {@code archiveCutOff}. Vložený údaj tak jen zvětšoval soubor a sváděl k domněnce, že revokace
 * byla prověřena.
 *
 * <p>Vkládá se proto jen to, co o certifikátu něco vypovídá ({@link #speaks}): údaj vydaný za jeho
 * platnosti — včetně údaje od vydavatele, jemuž mezitím vypršel certifikát, protože tuhle toleranci
 * validátory se zásobou mívají a údaj sám je z doby platnosti —, post-expirační údaj, jehož
 * vydavatel se k certifikátu výslovně hlásí ({@code expiredCertsOnCRL}, {@code archiveCutOff},
 * {@code CertHash}), a každý údaj, který certifikát označuje za revokovaný: ten je tvrzením, ne
 * mlčením. Údaj, o kterém se rozhodnout nedá (nevíme, ke kterému certifikátu patří), se vloží
 * — jako v {@code AugmentationService}.
 *
 * <p>Offered only where the coverage really is complete on the looser reading (situation 45) and
 * the time-stamp chain is unbroken and still live (situations 31 and 32) — see
 * {@code AdvisorView}.
 *
 * <h2>Scope</h2>
 *
 * <p>PDF only, and that is the whole of the target group's practice. The DSS/VRI dictionary is a
 * PDF-level construction that knows nothing about SubFilter, so this serves PAdES, PKCS7 and
 * PDF-NOT-ETSI alike. The other formats would need their unsigned attributes rebuilt, and DSS —
 * the only thing that can do that — applies exactly the filtering this path exists to avoid.
 */
public class RestampingService {

    /**
     * Vypovídá revokační údaj o certifikátu něco? Pravidlo DSS ({@code isConsistent()}, viz
     * {@code ReportMapper.postExpiryStillSpeaks}) rozšířené o revokaci, jako
     * {@code RevocationCoverage.saysSomething}:
     *
     * <ul>
     *   <li>vydán za platnosti certifikátu ({@code thisUpdate ≤ notAfter}), nebo</li>
     *   <li>vydavatel se k certifikátu i po expiraci hlásí — {@code expiredCertsOnCRL} nebo
     *       {@code archiveCutOff} nejpozději v {@code notAfter}, případně shodný {@code CertHash},
     *       nebo</li>
     *   <li>certifikát označuje za revokovaný.</li>
     * </ul>
     *
     * <p>Chybí-li některé z dat, o údaji se rozhodnout nedá a vloží se — jako v
     * {@code AugmentationService.stripUnusableOcsps}.
     */
    static boolean speaks(java.util.Date thisUpdate, java.util.Date notAfter,
                          java.util.Date expiredCertsOnCrl, java.util.Date archiveCutOff,
                          boolean certHashMatch, boolean revoked) {
        if (revoked) return true;
        if (thisUpdate == null || notAfter == null) return true;
        if (!thisUpdate.after(notAfter)) return true;
        if (expiredCertsOnCrl != null && !expiredCertsOnCrl.after(notAfter)) return true;
        if (archiveCutOff != null && !archiveCutOff.after(notAfter)) return true;
        return certHashMatch;
    }

    /** Údaj z diagnostiky: stačí, když vypovídá o jednom z certifikátů, ke kterým patří. */
    private static boolean speaksAboutSome(List<CertificateWrapper> certs,
                                           List<CertificateRevocationWrapper> refs) {
        if (certs == null || certs.isEmpty()) return true;
        for (int i = 0; i < certs.size(); i++) {
            CertificateWrapper cert = certs.get(i);
            CertificateRevocationWrapper ref = refs.get(i);
            if (speaks(ref.getThisUpdate(), cert.getNotAfter(), ref.getExpiredCertsOnCRL(),
                    ref.getArchiveCutOff(),
                    ref.isCertHashExtensionPresent() && ref.isCertHashExtensionMatch(),
                    eu.europa.esig.dss.enumerations.CertificateStatus.REVOKED.equals(ref.getStatus()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Seznam CRL mimo diagnostiku (situace 33 a 45): týká se certifikátů, které vydal vydavatel
     * seznamu. Revokaci se tu ptá přímo seznamu, protože diagnostika o něm nic neví.
     */
    private static boolean crlSpeaksAboutSome(byte[] der, DiagnosticData dd) {
        java.security.cert.X509CRL crl;
        try {
            crl = (java.security.cert.X509CRL) java.security.cert.CertificateFactory
                    .getInstance("X.509").generateCRL(new java.io.ByteArrayInputStream(der));
        } catch (Exception e) {
            return true;
        }
        if (dd == null) return true;
        java.util.Date eco = AugmentationService.readExpiredCertsOnCrl(crl);
        boolean related = false;
        for (CertificateWrapper cert : dd.getUsedCertificates()) {
            if (cert.getBinaries() == null) continue;
            java.security.cert.X509Certificate x509;
            try {
                x509 = (java.security.cert.X509Certificate) java.security.cert.CertificateFactory
                        .getInstance("X.509").generateCertificate(
                                new java.io.ByteArrayInputStream(cert.getBinaries()));
            } catch (Exception e) {
                continue;
            }
            if (!x509.getIssuerX500Principal().equals(crl.getIssuerX500Principal())) continue;
            related = true;
            boolean revoked = crl.getRevokedCertificate(x509.getSerialNumber()) != null;
            if (speaks(crl.getThisUpdate(), x509.getNotAfter(), eco, null, false, revoked)) {
                return true;
            }
        }
        return !related;
    }

    private static String sha256Hex(byte[] b) {
        try {
            return hex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What one round wrote into the document, so the UI can say what it did before anything is
     * saved. Counted per item rather than in bytes: the whole point is which material went in.
     */
    /**
     * Id certifikátů, které patří do řetězů vyjmenovaných podpisů — nebo {@code null}, když se
     * nefiltruje.
     *
     * <p>Do řetězu se počítá i to, co drží razítka toho podpisu: bez certifikátů razítkovací
     * autority by vložený materiál nedával smysl, protože právě ta razítka dokládají, kdy stav
     * existoval.
     */
    private static java.util.Set<String> allowedCertificateIds(
            DiagnosticData dd, java.util.Set<String> onlySignatureIds) {
        if (onlySignatureIds == null || onlySignatureIds.isEmpty()) return null;
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (eu.europa.esig.dss.diagnostic.SignatureWrapper sw : dd.getSignatures()) {
            if (!onlySignatureIds.contains(sw.getId())) continue;
            addChain(sw.getCertificateChain(), allowed);
            if (sw.getSigningCertificate() != null) allowed.add(sw.getSigningCertificate().getId());
            for (eu.europa.esig.dss.diagnostic.TimestampWrapper ts : sw.getTimestampList()) {
                addChain(ts.getCertificateChain(), allowed);
                if (ts.getSigningCertificate() != null) {
                    allowed.add(ts.getSigningCertificate().getId());
                }
            }
        }
        return allowed;
    }

    private static void addChain(List<CertificateWrapper> chain, java.util.Set<String> out) {
        if (chain == null) return;
        for (CertificateWrapper c : chain) {
            if (c != null) out.add(c.getId());
        }
    }

    /**
     * Id revokačních odpovědí, které mluví o povolených certifikátech.
     *
     * <p>Ptá se od certifikátu k jeho revokacím ({@code getCertificateRevocationData()}), ne
     * opačně: revokační obal sám neví, ke komu byl při ověřování přiřazen. Vydavatel odpovědi do
     * povoleného řetězu patřit nemusí — rozhoduje, o kom odpověď mluví, ne kdo ji podepsal.
     */
    private static java.util.Set<String> allowedRevocationIds(
            DiagnosticData dd, java.util.Set<String> allowedCerts) {
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (CertificateWrapper cert : dd.getUsedCertificates()) {
            if (!allowedCerts.contains(cert.getId())) continue;
            for (eu.europa.esig.dss.diagnostic.CertificateRevocationWrapper rev
                    : cert.getCertificateRevocationData()) {
                if (rev != null) allowed.add(rev.getId());
            }
        }
        return allowed;
    }

    /**
     * @param withheld revokační údaje, které úkon měl, ale nevložil, protože o certifikátu nic
     *                 nevypovídají (viz {@link #speaks})
     */
    public record RestampReport(int crls, int ocsps, int certificates, int withheld) {
        public boolean isEmpty() { return crls == 0 && ocsps == 0 && certificates == 0; }

        /** Czech summary of what went in, or null when nothing did. */
        public String describe() {
            if (isEmpty()) return null;
            List<String> parts = new ArrayList<>();
            if (crls > 0) parts.add(crls + "× CRL");
            if (ocsps > 0) parts.add(ocsps + "× OCSP");
            if (certificates > 0) parts.add(certificates + "× certifikát");
            return String.join(", ", parts);
        }
    }

    public record Restamped(DSSDocument document, RestampReport report) {}

    /**
     * Embeds the revocation material that says something about its certificate ({@link #speaks})
     * and the certificates from {@code diagnosticData} into the document's DSS/VRI dictionaries,
     * plus those of the extra CRLs the situation analysis downloaded that pass the same rule.
     *
     * <p>Returns the input document unchanged, with an empty report, when everything is already
     * there. That is not a failure and the caller carries on: a document whose data is complete is
     * precisely the one whose re-stamping consists of the time-stamp alone.
     */
    public Restamped embedAll(File inputFile, char[] pdfPassword, DiagnosticData diagnosticData,
                              List<byte[]> extraCrls) {
        return embedAll(new DocumentLoader().load(inputFile), pdfPassword, diagnosticData,
                extraCrls, null);
    }

    /**
     * Totéž pro dokument, který ještě neleží na disku, a případně jen pro materiál patřící
     * vyjmenovaným podpisům.
     *
     * <p><b>Proč ten výběr.</b> Když se obě materiálové cesty spojí do jednoho průchodu, sejde se
     * v jednom slovníku {@code /DSS} i materiál podpisů, kterým se stejně nedá pomoct. Změřeno na
     * {@code pet podpisu LT.pdf}: doslovné vložení všeho natáhlo čtyři CRL, soubor vyrostl z 815 kB
     * na 3,55 MB — a nezlepšil se ani jeden verdikt, tři podpisy zůstaly INDETERMINATE před i po.
     * 2,7 MB čisté ztráty.
     *
     * <p>Tenhle výběr filtruje podle toho, <b>čímu řetězu materiál patří</b>; co materiál
     * o certifikátu vypovídá, posuzuje zvlášť {@link #speaks}.
     *
     * @param onlySignatureIds id podpisů, jejichž materiál se má vložit; {@code null} nebo prázdná
     *                         množina znamená všechno, což je chování jediného podpisu a zůstává
     *                         výchozí
     */
    public Restamped embedAll(DSSDocument input, char[] pdfPassword, DiagnosticData diagnosticData,
                              List<byte[]> extraCrls, java.util.Set<String> onlySignatureIds) {
        List<byte[]> crls = new ArrayList<>();
        List<byte[]> ocsps = new ArrayList<>();
        List<byte[]> certs = new ArrayList<>();

        int withheld = 0;
        java.util.Set<String> ddHashes = new java.util.HashSet<>();
        if (diagnosticData != null) {
            java.util.Set<String> allowedCerts =
                    allowedCertificateIds(diagnosticData, onlySignatureIds);
            java.util.Set<String> allowedRevocations = allowedCerts == null ? null
                    : allowedRevocationIds(diagnosticData, allowedCerts);
            java.util.Map<String, List<CertificateWrapper>> certsByRev = new java.util.HashMap<>();
            java.util.Map<String, List<CertificateRevocationWrapper>> refsByRev =
                    new java.util.HashMap<>();
            for (CertificateWrapper cert : diagnosticData.getUsedCertificates()) {
                for (CertificateRevocationWrapper ref : cert.getCertificateRevocationData()) {
                    if (ref == null) continue;
                    certsByRev.computeIfAbsent(ref.getId(), k -> new ArrayList<>()).add(cert);
                    refsByRev.computeIfAbsent(ref.getId(), k -> new ArrayList<>()).add(ref);
                }
            }
            for (RevocationWrapper rev : diagnosticData.getAllRevocationData()) {
                byte[] bin = rev.getBinaries();
                if (bin == null || bin.length == 0) continue;
                if (allowedRevocations != null && !allowedRevocations.contains(rev.getId())) continue;
                ddHashes.add(sha256Hex(bin));
                if (!speaksAboutSome(certsByRev.get(rev.getId()), refsByRev.get(rev.getId()))) {
                    withheld++;
                    continue;
                }
                if (rev.getRevocationType() == RevocationType.CRL) crls.add(bin); else ocsps.add(bin);
            }
            for (CertificateWrapper cert : diagnosticData.getUsedCertificates()) {
                if (allowedCerts != null && !allowedCerts.contains(cert.getId())) continue;
                byte[] bin = cert.getBinaries();
                if (bin != null && bin.length > 0) certs.add(bin);
            }
        }
        if (extraCrls != null) {
            for (byte[] der : extraCrls) {
                if (der == null || der.length == 0) continue;
                // Seznam, který zná i diagnostika, už rozhodla smyčka výše.
                if (ddHashes.contains(sha256Hex(der))) continue;
                if (!crlSpeaksAboutSome(der, diagnosticData)) {
                    withheld++;
                    continue;
                }
                crls.add(der);
            }
        }

        // Vstup přichází přes DocumentLoader, ne přímo z disku, aby šlo přerazítkovat i PDF, jehož
        // hlavička nezačíná na nultém bajtu — PDFBox by takový soubor odmítl. Osekané bajty jsou
        // to, co podpis kryje, takže se staví z toho, co bylo ověřeno.
        try {
            byte[] docBytes;
            try (InputStream is = input.openStream()) { docBytes = is.readAllBytes(); }
            PDDocument pdf = (pdfPassword != null && pdfPassword.length > 0)
                    ? Loader.loadPDF(docBytes, new String(pdfPassword))
                    : Loader.loadPDF(docBytes);
            try {
                COSDictionary catalog = pdf.getDocumentCatalog().getCOSObject();
                COSBase dssBase = catalog.getDictionaryObject(COSName.getPDFName("DSS"));
                COSDictionary dss;
                if (dssBase instanceof COSDictionary existing) {
                    dss = existing;
                } else {
                    // A B or T level document has no DSS dictionary at all. Creating it on the
                    // catalog is what makes the rest of this work: setItem() propagates the
                    // catalog's origin state into the new dictionary, and without that PDFBox's
                    // incremental save would leave every stream added below out of the xref.
                    //
                    // The catalog is marked explicitly because it is the one object whose own
                    // contents changed — a new key — and nothing else will notice. Doing the same
                    // to an existing DSS dictionary would be a mistake: the mark propagates to
                    // every stream inside it, and PDFBox would re-serialise revocation lists that
                    // are already in the file, which an incremental save can never take back.
                    dss = new COSDictionary();
                    catalog.setItem(COSName.getPDFName("DSS"), dss);
                    catalog.setNeedToBeUpdated(true);
                }

                // The certificates already inside a CMS structure are reachable there, so writing
                // them a second time would grow the file for nothing. Revocation material gets no
                // such treatment: this path exists to make the dictionary the complete record.
                java.util.Set<String> cmsCerts = cmsCertificateFingerprints(pdf);
                List<byte[]> newCerts = new ArrayList<>();
                java.security.MessageDigest sha256 =
                        java.security.MessageDigest.getInstance("SHA-256");
                for (byte[] der : certs) {
                    if (!cmsCerts.contains(hex(sha256.digest(der)))) newCerts.add(der);
                }

                List<COSStream> addedCrls  = inject(pdf, dss, "CRLs",  crls);
                List<COSStream> addedOcsps = inject(pdf, dss, "OCSPs", ocsps);
                List<COSStream> addedCerts = inject(pdf, dss, "Certs", newCerts);

                RestampReport report = new RestampReport(
                        addedCrls.size(), addedOcsps.size(), addedCerts.size(), withheld);
                if (report.isEmpty()) return new Restamped(input, report);

                linkIntoVri(pdf, dss, addedCrls, addedOcsps, addedCerts);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                pdf.saveIncremental(baos);
                return new Restamped(new InMemoryDocument(baos.toByteArray(), input.getName(),
                        eu.europa.esig.dss.enumerations.MimeTypeEnum.PDF), report);
            } finally {
                pdf.close();
            }
        } catch (Exception e) {
            throw new RestampingException("Vložení revokačních dat selhalo: " + e.getMessage(), e);
        }
    }

    /**
     * Appends to {@code /DSS/<key>} every byte array not already there, and returns the streams
     * actually created.
     *
     * <p>The {@code setItem} before the first {@code add} is not redundant even for an array read
     * out of the file. It is what marks the array — and through it every stream added afterwards —
     * as updated, so that {@code COSIncrement.collect()} puts them in the incremental revision. A
     * freshly created {@code COSArray} has no origin state at all, so without this nothing would be
     * written; the same trap is documented in {@link AugmentationService}.
     *
     * <p>Nothing is touched when there is nothing to add. Marking the dictionary dirty propagates
     * to every stream already in it, and PDFBox then re-serialises lists that are already in the
     * file — for a large CRL that is hundreds of kilobytes an incremental save can never reclaim.
     */
    private static List<COSStream> inject(PDDocument pdf, COSDictionary dss, String key,
                                          List<byte[]> material) throws Exception {
        List<COSStream> added = new ArrayList<>();
        if (material.isEmpty()) return added;

        COSBase arrayBase = dss.getDictionaryObject(COSName.getPDFName(key));
        COSArray array = arrayBase instanceof COSArray a ? a : new COSArray();

        List<byte[]> existing = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            if (array.getObject(i) instanceof COSStream s) {
                try (InputStream is = s.createInputStream()) { existing.add(is.readAllBytes()); }
            }
        }

        List<byte[]> missing = new ArrayList<>();
        for (byte[] der : material) {
            boolean known = existing.stream().anyMatch(b -> java.util.Arrays.equals(b, der))
                    || missing.stream().anyMatch(b -> java.util.Arrays.equals(b, der));
            if (!known) missing.add(der);
        }
        if (missing.isEmpty()) return added;

        dss.setItem(COSName.getPDFName(key), array);
        for (byte[] der : missing) {
            PDStream stream = new PDStream(pdf);
            try (OutputStream os = stream.createOutputStream()) { os.write(der); }
            COSStream cos = stream.getCOSObject();
            array.add(cos);
            added.add(cos);
        }
        return added;
    }

    /**
     * Puts the newly written streams into every {@code /DSS/VRI} entry, creating the entry for a
     * signature that has none.
     *
     * <p>VRI is optional — a validator has to read the DSS dictionary and may ignore VRI entirely —
     * but the readers this path serves are old Adobe-lineage implementations, and VRI is the older
     * and more widely understood of the two structures. Its keys are the uppercase hex SHA-1 of
     * each signature dictionary's {@code /Contents}, which is how Adobe defined it and the reason
     * SHA-1 appears here regardless of what the signature itself uses.
     */
    private static void linkIntoVri(PDDocument pdf, COSDictionary dss, List<COSStream> crls,
                                    List<COSStream> ocsps, List<COSStream> certs) throws Exception {
        COSBase vriBase = dss.getDictionaryObject(COSName.getPDFName("VRI"));
        COSDictionary vri;
        if (vriBase instanceof COSDictionary existing) {
            vri = existing;
        } else {
            vri = new COSDictionary();
            dss.setItem(COSName.getPDFName("VRI"), vri);
        }

        for (COSName key : new ArrayList<>(vri.keySet())) {
            if (vri.getDictionaryObject(key) instanceof COSDictionary entry) {
                fill(entry, crls, ocsps, certs);
            }
        }

        java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
        for (PDSignature sig : pdf.getSignatureDictionaries()) {
            byte[] contents = sig.getContents();
            if (contents == null) continue;
            COSName key = COSName.getPDFName(hex(sha1.digest(contents)).toUpperCase(
                    java.util.Locale.ROOT));
            if (vri.getDictionaryObject(key) != null) continue;
            COSDictionary entry = new COSDictionary();
            vri.setItem(key, entry);
            fill(entry, crls, ocsps, certs);
        }
    }

    private static void fill(COSDictionary vriEntry, List<COSStream> crls, List<COSStream> ocsps,
                             List<COSStream> certs) {
        append(vriEntry, "CRL",  crls);
        append(vriEntry, "OCSP", ocsps);
        append(vriEntry, "Cert", certs);
    }

    private static void append(COSDictionary vriEntry, String key, List<COSStream> streams) {
        if (streams.isEmpty()) return;
        COSBase base = vriEntry.getDictionaryObject(COSName.getPDFName(key));
        COSArray array = base instanceof COSArray a ? a : new COSArray();
        vriEntry.setItem(COSName.getPDFName(key), array);
        for (COSStream s : streams) array.add(s);
    }

    /** SHA-256 fingerprints of every certificate embedded in a CMS structure of this document. */
    private static java.util.Set<String> cmsCertificateFingerprints(PDDocument pdf)
            throws Exception {
        java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
        java.util.Set<String> fingerprints = new java.util.HashSet<>();
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
                    fingerprints.add(hex(sha256.digest(cert.getEncoded())));
                }
            } catch (Exception ignored) {
                // A structure that will not parse says nothing about what is already inside it.
            }
        }
        return fingerprints;
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static final class RestampingException extends RuntimeException {
        public RestampingException(String msg, Throwable cause) { super(msg, cause); }
    }
}
