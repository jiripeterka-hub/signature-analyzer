package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * User-managed store of CRL files, kept as a fallback for the (rare) case where DSS itself
 * cannot obtain usable revocation information online. Unlike {@link CustomTrustStore}, these
 * are NOT used automatically on every validation — only when the user explicitly asks for it
 * (Nastavení → Vlastní CRL seznamy, plus the "Zkusit ověřit s vlastními CRL" action), since a
 * stale or mismatched CRL supplied by mistake should never silently change a result.
 *
 * <p>Files are kept under {@code %APPDATA%/SignatureAnalyzer/custom-crls/}.</p>
 */
public class CustomCrlStore {

    private static final Path STORE_DIR;

    static {
        STORE_DIR = AppDirs.dataDir().resolve("custom-crls");
    }

    /** One entry in the store: the backing file plus its parsed CRL. */
    public record Entry(File file, X509CRL crl) {}

    private CustomCrlStore() {
    }

    /**
     * Imports {@code sourceFile} (DER or PEM-encoded CRL) into the store.
     *
     * @throws IOException if the file cannot be read or does not contain a valid CRL
     */
    public static Entry addCrl(File sourceFile) throws IOException {
        byte[] der = Files.readAllBytes(sourceFile.toPath());
        X509CRL crl = parse(der);
        if (crl == null) {
            throw new IOException("Soubor neobsahuje platný CRL seznam.");
        }
        Files.createDirectories(STORE_DIR);
        Path dest = STORE_DIR.resolve(uniqueFileName(crl, sourceFile.getName()));
        Files.write(dest, der);
        return new Entry(dest.toFile(), crl);
    }

    /** Removes a previously imported CRL. */
    public static void removeCrl(File file) throws IOException {
        Files.deleteIfExists(file.toPath());
    }

    /** Lists all stored CRLs. Unparsable files are silently skipped. */
    public static List<Entry> listCrls() {
        List<Entry> result = new ArrayList<>();
        if (!Files.isDirectory(STORE_DIR)) return result;
        File[] files = STORE_DIR.toFile().listFiles();
        if (files == null) return result;
        for (File f : files) {
            try {
                X509CRL crl = parse(Files.readAllBytes(f.toPath()));
                if (crl != null) result.add(new Entry(f, crl));
            } catch (IOException ignored) {
                // skip unreadable file
            }
        }
        result.sort((a, b) -> a.crl().getIssuerX500Principal().getName()
                .compareToIgnoreCase(b.crl().getIssuerX500Principal().getName()));
        return result;
    }

    /** Loads the stored CRLs as DSS documents, ready to hand to an {@code ExternalResourcesCRLSource}. */
    public static List<DSSDocument> loadAsDocuments() {
        return listCrls().stream()
                .map(e -> {
                    try {
                        return (DSSDocument) new InMemoryDocument(Files.readAllBytes(e.file().toPath()), e.file().getName());
                    } catch (IOException ex) {
                        return null;
                    }
                })
                .filter(d -> d != null)
                .collect(Collectors.toList());
    }

    /** Human-readable summary: issuer's Common Name plus thisUpdate/nextUpdate/expiredCertsOnCRL. */
    public static String displayName(X509CRL crl) {
        String issuer = issuerCommonName(crl);
        String thisUpdate = crl.getThisUpdate() != null
                ? TimeFormat.full(crl.getThisUpdate()) : "?";
        String nextUpdate = crl.getNextUpdate() != null
                ? TimeFormat.full(crl.getNextUpdate()) : "?";
        String eco = ecoDateStr(crl);
        String name = issuer + "  (thisUpdate: " + thisUpdate + ", nextUpdate: " + nextUpdate;
        if (eco != null) name += ", expiredCertsOnCRL: " + eco;
        return name + ")";
    }

    private static String ecoDateStr(X509CRL crl) {
        try {
            org.bouncycastle.cert.X509CRLHolder holder =
                    new org.bouncycastle.cert.X509CRLHolder(crl.getEncoded());
            org.bouncycastle.asn1.x509.Extension ext = holder.getExtension(
                    new org.bouncycastle.asn1.ASN1ObjectIdentifier("2.5.29.60"));
            if (ext == null) return null;
            org.bouncycastle.asn1.ASN1GeneralizedTime gt =
                    org.bouncycastle.asn1.ASN1GeneralizedTime.getInstance(
                            org.bouncycastle.asn1.ASN1Primitive.fromByteArray(
                                    ext.getExtnValue().getOctets()));
            return TimeFormat.full(gt.getDate());
        } catch (Exception e) {
            return null;
        }
    }

    private static String issuerCommonName(X509CRL crl) {
        String dn = crl.getIssuerX500Principal().getName();
        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=")) return trimmed.substring(3);
        }
        return dn;
    }

    private static X509CRL parse(byte[] der) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509CRL) cf.generateCRL(new ByteArrayInputStream(der));
        } catch (Exception e) {
            return null;
        }
    }

    private static String uniqueFileName(X509CRL crl, String originalName) {
        String base = originalName.replaceAll("[^a-zA-Z0-9._-]+", "_");
        if (base.length() > 60) base = base.substring(0, 60);
        if (!base.toLowerCase().endsWith(".crl")) base = base + ".crl";
        return Integer.toHexString(crl.getIssuerX500Principal().getName().hashCode()
                ^ (crl.getThisUpdate() != null ? crl.getThisUpdate().hashCode() : 0)) + "_" + base;
    }
}
