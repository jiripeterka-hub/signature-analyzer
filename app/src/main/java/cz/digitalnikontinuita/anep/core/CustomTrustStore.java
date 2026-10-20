package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.x509.CertificateToken;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * User-managed store of additional trusted certificates, layered on top of the EU
 * Trusted Lists (LOTL) during validation. Certificates are kept as individual files under
 * {@code %APPDATA%/SignatureAnalyzer/trusted-certs/} so the user can add or remove trust
 * anchors (e.g. an internal/company CA, or any CA not covered by EUTL) without restarting
 * the app — {@link #loadTrustedCertificates()} re-reads the directory on every call.
 */
public class CustomTrustStore {

    private static final Path STORE_DIR;

    static {
        STORE_DIR = AppDirs.dataDir().resolve("trusted-certs");
    }

    /** One entry in the store: the backing file plus its parsed certificate. */
    public record Entry(File file, CertificateToken certificate) {}

    private CustomTrustStore() {
    }

    /**
     * Imports {@code sourceFile} (DER or PEM, single certificate) into the store.
     *
     * @throws IOException if the file cannot be read or does not contain a valid X.509 certificate
     */
    public static Entry addCertificate(File sourceFile) throws IOException {
        return addCertificateBytes(Files.readAllBytes(sourceFile.toPath()));
    }

    /**
     * Imports a certificate already held in memory (DER bytes). If an identical
     * certificate (by DSS identifier, i.e. its digest) is already in the store, the
     * existing entry is returned as-is rather than creating a duplicate file — this makes
     * the bulk "insert known commercial CA roots" action safe to invoke repeatedly.
     */
    public static Entry addCertificateBytes(byte[] der) throws IOException {
        CertificateToken token = parse(der);
        if (token == null) {
            throw new IOException("Soubor neobsahuje platný certifikát X.509.");
        }
        for (Entry existing : listCertificates()) {
            if (existing.certificate().getDSSIdAsString().equals(token.getDSSIdAsString())) {
                return existing;
            }
        }
        Files.createDirectories(STORE_DIR);
        Path dest = STORE_DIR.resolve(uniqueFileName(token));
        Files.write(dest, der);
        return new Entry(dest.toFile(), token);
    }

    /** Removes a previously imported certificate. */
    public static void removeCertificate(File file) throws IOException {
        Files.deleteIfExists(file.toPath());
    }

    /** Lists all stored certificates. Unparsable files are silently skipped. */
    public static List<Entry> listCertificates() {
        List<Entry> result = new ArrayList<>();
        if (!Files.isDirectory(STORE_DIR)) return result;
        File[] files = STORE_DIR.toFile().listFiles();
        if (files == null) return result;
        for (File f : files) {
            try {
                CertificateToken token = parse(Files.readAllBytes(f.toPath()));
                if (token != null) result.add(new Entry(f, token));
            } catch (IOException ignored) {
                // skip unreadable file
            }
        }
        result.sort((a, b) -> displayName(a.certificate()).compareToIgnoreCase(displayName(b.certificate())));
        return result;
    }

    /** Loads just the parsed certificates, ready to hand to DSS as an extra trusted source. */
    public static List<CertificateToken> loadTrustedCertificates() {
        return listCertificates().stream().map(Entry::certificate).collect(Collectors.toList());
    }

    /** Human-readable label for a certificate: its Common Name (or full Subject DN as fallback). */
    public static String displayName(CertificateToken token) {
        String dn = token.getCertificate().getSubjectX500Principal().getName();
        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=")) return trimmed.substring(3);
        }
        return dn;
    }

    private static CertificateToken parse(byte[] der) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate x509 = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
            return new CertificateToken(x509);
        } catch (Exception e) {
            return null;
        }
    }

    private static String uniqueFileName(CertificateToken token) {
        String base = displayName(token).replaceAll("[^a-zA-Z0-9._-]+", "_");
        if (base.length() > 60) base = base.substring(0, 60);
        return base + "_" + Integer.toHexString(token.getDSSIdAsString().hashCode()) + ".cer";
    }
}
