package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.CustomCrlStore;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.diagnostic.CertificateRevocationWrapper;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.RevocationWrapper;
import eu.europa.esig.dss.enumerations.RevocationType;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.service.crl.OnlineCRLSource;
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader;
import eu.europa.esig.dss.service.http.commons.OCSPDataLoader;
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource;
import eu.europa.esig.dss.spi.DSSUtils;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.revocation.RevocationToken;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

import javax.security.auth.x500.X500Principal;
import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509CRL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Vloží přerazítkování opravdu všechno, co AnEP k certifikátům dokumentu má?
 *
 * <p>Otázka uživatele (17. 10. 2026): má přerazítkování vkládat všechny revokační údaje, které
 * aplikace má, včetně neautoritativních „nerevokován“ — a dělá to? Z kódu plyne, že ne úplně:
 * vkládá se jen to, co je v diagnostických datech DSS, plus CRL ze situací 33 a 45. Post-expirační
 * OCSP, které strategie načítání odloží ({@code SkippedRevocation}), nemá bajty, a post-expirační
 * CRL se do situace 45 dostane jen tehdy, když je situace 45 indikována.
 *
 * <p>Sonda to měří přímo: ke každému certifikátu s vydavatelem si znovu opatří, co AnEP opatřit
 * umí (OCSP a CRL on-line, CRL z vlastního úložiště), a porovná to s tím, co by vložilo
 * {@code RestampingService.embedAll} (diagnostická data + CRL ze situací 33 a 45 + co už je
 * v {@code /DSS}). CRL se porovnávají bajty; OCSP podle certifikátu, protože každá nově stažená
 * odpověď má jiné bajty — chybí tedy jen tehdy, když k certifikátu není vložena žádná.
 *
 * <pre>
 *   ... RestampCompletenessScan -out app/target/scan/complete.txt @seznam.txt
 * </pre>
 */
public final class RestampCompletenessScan {

    public static void main(String[] args) throws Exception {
        List<Path> files = new ArrayList<>();
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-out")) out = Path.of(args[++i]);
            else if (args[i].startsWith("@")) {
                for (String l : Files.readAllLines(Path.of(args[i].substring(1)))) {
                    if (!l.isBlank()) files.add(Path.of(l.trim()));
                }
            } else files.add(Path.of(args[i]));
        }
        if (files.isEmpty() || out == null) {
            System.out.println("usage: RestampCompletenessScan -out <file> @list | <pdf>...");
            return;
        }

        AppSettings settings = AppSettings.load();
        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier;
        try {
            verifier = trustConfig.buildLotlCertificateVerifier();
        } catch (Exception e) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
        }
        ValidatorFactory factory = new ValidatorFactory(settings);
        DocumentLoader loader = new DocumentLoader();
        ReportMapper mapper = new ReportMapper();
        OnlineOCSPSource ocspSource = new OnlineOCSPSource(new OCSPDataLoader());
        OnlineCRLSource crlSource = new OnlineCRLSource(new CommonsDataLoader());
        List<CustomCrlStore.Entry> store = CustomCrlStore.listCrls();

        Map<String, Integer> tally = new TreeMap<>();
        int docs = 0, docsMissing = 0;
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            for (Path p : files) {
                File f = p.toFile();
                DSSDocument doc;
                ValidationResult result;
                try {
                    doc = loader.load(f);
                    result = mapper.map(f.getName(), null,
                            factory.validate(doc, null, verifier, null), settings);
                } catch (Throwable e) {
                    w.println("SKIP\t" + p + "\t" + e);
                    continue;
                }
                docs++;
                DiagnosticData dd = result.getDiagnosticData();

                Set<String> embedded = dssStreams(doc);
                for (RevocationWrapper rev : dd.getAllRevocationData()) {
                    if (rev.getBinaries() != null) embedded.add(sha(rev.getBinaries()));
                }
                for (SignatureSummary sig : result.getSignatures()) {
                    for (byte[] b : sig.getS33CrlBytes()) embedded.add(sha(b));
                    for (byte[] b : sig.getS45CrlBytes()) embedded.add(sha(b));
                }

                List<String> missing = new ArrayList<>();
                for (CertificateWrapper c : dd.getUsedCertificates()) {
                    CertificateWrapper issuerW = c.getSigningCertificate();
                    if (issuerW == null || issuerW.getId().equals(c.getId())) continue;
                    if (c.getBinaries() == null || issuerW.getBinaries() == null) continue;
                    CertificateToken cert = DSSUtils.loadCertificate(c.getBinaries());
                    CertificateToken issuer = DSSUtils.loadCertificate(issuerW.getBinaries());
                    Date notAfter = c.getNotAfter();
                    String who = c.getCommonName() + " (do " + iso(notAfter)
                            + (c.isTrusted() ? ", kotva" : "") + ")";

                    boolean hasOcsp = false;
                    for (CertificateRevocationWrapper r : c.getCertificateRevocationData()) {
                        if (r.getRevocationType() == RevocationType.OCSP) { hasOcsp = true; break; }
                    }
                    if (!hasOcsp) {
                        try {
                            RevocationToken<?> t = ocspSource.getRevocationToken(cert, issuer);
                            if (t != null) {
                                String cls = classify("OCSP", t.getThisUpdate(), notAfter,
                                        t.getStatus() == null ? "?" : t.getStatus().name(), null);
                                missing.add(cls + "\tONLINE\t" + who);
                            }
                        } catch (Exception ignored) { }
                    }
                    try {
                        RevocationToken<?> t = crlSource.getRevocationToken(cert, issuer);
                        if (t != null && t.getEncoded() != null && !embedded.contains(sha(t.getEncoded()))) {
                            String status = t.getStatus() == null ? "?" : t.getStatus().name();
                            missing.add(classify("CRL", t.getThisUpdate(), notAfter, status,
                                    t.getExpiredCertsOnCRL()) + "\tONLINE\t" + who);
                        }
                    } catch (Exception ignored) { }
                    for (CustomCrlStore.Entry e : store) {
                        X509CRL crl = e.crl();
                        try {
                            if (!crl.getIssuerX500Principal()
                                    .equals(new X500Principal(c.getCertificateIssuerDN()))) continue;
                        } catch (Exception ex) {
                            continue;
                        }
                        if (embedded.contains(sha(crl.getEncoded()))) continue;
                        String status = crl.getRevokedCertificate(cert.getSerialNumber()) != null
                                ? "REVOKED" : "GOOD";
                        missing.add(classify("CRL", crl.getThisUpdate(), notAfter, status, null)
                                + "\tVLASTNI-ULOZISTE\t" + who);
                    }
                }

                if (!missing.isEmpty()) docsMissing++;
                w.println((missing.isEmpty() ? "UPLNE\t" : "CHYBI\t") + p);
                for (String m : missing) {
                    w.println("\t" + m);
                    String[] parts = m.split("\t");
                    tally.merge(parts[0] + " " + parts[1], 1, Integer::sum);
                }
                w.flush();
                System.out.println("### " + docs + "/" + files.size());
            }
            w.println();
            w.println("# dokumentu=" + docs + " s chybejicim materialem=" + docsMissing);
            tally.forEach((k, v) -> w.println("#   " + k + " = " + v));
        }
        System.exit(0);
    }

    /** „OCSP PO-EXPIRACI GOOD“ apod.; u CRL s expiredCertsOnCRL do doby platnosti „AUTORITATIVNI“. */
    private static String classify(String kind, Date issued, Date notAfter, String status, Date eco) {
        String when;
        if (issued == null || notAfter == null) when = "?";
        else if (!issued.after(notAfter)) when = "PRED-EXPIRACI";
        else if (eco != null && !eco.after(notAfter)) when = "PO-EXPIRACI-AUTORITATIVNI";
        else when = "PO-EXPIRACI";
        return kind + " " + when + "\t" + status;
    }

    private static Set<String> dssStreams(DSSDocument doc) throws Exception {
        Set<String> out = new HashSet<>();
        byte[] bytes;
        try (InputStream is = doc.openStream()) { bytes = is.readAllBytes(); }
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            COSBase dssBase = pdf.getDocumentCatalog().getCOSObject()
                    .getDictionaryObject(COSName.getPDFName("DSS"));
            if (!(dssBase instanceof COSDictionary dss)) return out;
            for (String key : List.of("CRLs", "OCSPs")) {
                if (dss.getDictionaryObject(COSName.getPDFName(key)) instanceof COSArray a) {
                    for (int i = 0; i < a.size(); i++) {
                        if (a.getObject(i) instanceof COSStream s) {
                            try (InputStream is = s.createInputStream()) { out.add(sha(is.readAllBytes())); }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // nečitelné PDF: nic se nepovažuje za přítomné
        }
        return out;
    }

    private static String sha(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String iso(Date d) {
        return d == null ? "?" : d.toInstant().toString().substring(0, 10);
    }
}
