package cz.digitalnikontinuita.anep.tools;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.SingleResp;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;

/**
 * Reads the {@code /DSS} dictionary of a PDF and says what is in it and whom each item speaks
 * about: CRLs by issuer and size, OCSP responses by responder and by the serial number they
 * answer for, certificates by subject.
 *
 * <p>Written to account for the size of a preserved document. The preservation dialog reports how
 * much each embedded list costs, but not which certificate made it necessary — and the difference
 * between "the signing certificate's CRL was embedded although an OCSP was used" and "the OCSP
 * responder's own certificate needed a CRL" is the difference between a bug and a rule of the
 * standard. Only the bytes in the file can settle it.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.tools.DssDictionaryProbe &lt;file.pdf&gt; [...]</pre>
 */
public final class DssDictionaryProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: DssDictionaryProbe <file.pdf> [...]");
            return;
        }
        for (String a : args) {
            File f = new File(a);
            System.out.println("=== " + f.getName() + "  (" + f.length() + " B)");
            try (PDDocument pdf = Loader.loadPDF(Files.readAllBytes(f.toPath()))) {
                COSBase dssBase = pdf.getDocumentCatalog().getCOSObject()
                        .getDictionaryObject(COSName.getPDFName("DSS"));
                if (!(dssBase instanceof COSDictionary dss)) {
                    System.out.println("  /DSS: žádný");
                    continue;
                }
                dumpCrls(dss);
                dumpOcsps(dss);
                dumpCerts(dss);
            }
            System.out.println();
        }
    }

    private static void dumpCrls(COSDictionary dss) {
        long total = 0;
        int n = 0;
        for (byte[] der : streams(dss, "CRLs")) {
            n++;
            total += der.length;
            try {
                X509CRL crl = (X509CRL) CertificateFactory.getInstance("X.509")
                        .generateCRL(new ByteArrayInputStream(der));
                int revoked = crl.getRevokedCertificates() == null
                        ? 0 : crl.getRevokedCertificates().size();
                System.out.printf("  CRL  %,9d B  vydal %s%n", der.length,
                        crl.getIssuerX500Principal().getName());
                System.out.printf("       thisUpdate %s, položek %d%n", crl.getThisUpdate(), revoked);
            } catch (Exception e) {
                System.out.printf("  CRL  %,9d B  (nečitelná: %s)%n", der.length, e);
            }
        }
        System.out.printf("  CRL celkem: %d ks, %,d B%n", n, total);
    }

    private static void dumpOcsps(COSDictionary dss) {
        long total = 0;
        int n = 0;
        for (byte[] der : streams(dss, "OCSPs")) {
            n++;
            total += der.length;
            try {
                BasicOCSPResp basic = (BasicOCSPResp) new OCSPResp(der).getResponseObject();
                System.out.printf("  OCSP %,9d B  respondér %s%n", der.length,
                        basic.getResponderId().toASN1Primitive().getName() != null
                                ? basic.getResponderId().toASN1Primitive().getName()
                                : "(dle klíče)");
                for (SingleResp sr : basic.getResponses()) {
                    System.out.printf("       o certifikátu č. %s, thisUpdate %s, stav %s%n",
                            sr.getCertID().getSerialNumber().toString(16).toUpperCase(),
                            sr.getThisUpdate(),
                            sr.getCertStatus() == null ? "dobrý" : sr.getCertStatus().toString());
                }
                if (basic.getCerts() != null) {
                    for (var holder : basic.getCerts()) {
                        System.out.println("       nese certifikát respondéra: " + holder.getSubject());
                    }
                }
            } catch (Exception e) {
                System.out.printf("  OCSP %,9d B  (nečitelná: %s)%n", der.length, e);
            }
        }
        System.out.printf("  OCSP celkem: %d ks, %,d B%n", n, total);
    }

    private static void dumpCerts(COSDictionary dss) {
        long total = 0;
        int n = 0;
        for (byte[] der : streams(dss, "Certs")) {
            n++;
            total += der.length;
            try {
                X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(der));
                System.out.printf("  cert %,9d B  %s%n", der.length,
                        c.getSubjectX500Principal().getName());
            } catch (Exception e) {
                System.out.printf("  cert %,9d B  (nečitelný)%n", der.length);
            }
        }
        System.out.printf("  Certs celkem: %d ks, %,d B%n", n, total);
    }

    private static java.util.List<byte[]> streams(COSDictionary dss, String key) {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        COSBase base = dss.getDictionaryObject(COSName.getPDFName(key));
        if (!(base instanceof COSArray arr)) return out;
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.getObject(i) instanceof COSStream s)) continue;
            try (InputStream is = s.createInputStream()) {
                out.add(is.readAllBytes());
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
