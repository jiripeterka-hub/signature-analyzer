package cz.digitalnikontinuita.anep.ui;

import java.io.FileInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Co AnEP vyčte z DN držitele daného certifikátu — zvlášť jestli v něm vidí číslo osobního
 * dokladu podle ETSI EN 319 412-1 čl. 5.1.3 (schémata {@code IDCCZ-} a {@code PASCZ-}).
 *
 * <pre>java -cp … DnProbe &lt;soubor.crt&gt;…</pre>
 */
public final class DnProbe {

    public static void main(String[] args) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        for (String a : args) {
            try (FileInputStream in = new FileInputStream(a)) {
                X509Certificate cert = (X509Certificate) cf.generateCertificate(in);
                // RFC 2253, tedy týž tvar, v jakém DN přichází od DSS.
                String dn = cert.getSubjectX500Principal().getName("RFC2253");
                System.out.println("=== " + a);
                System.out.println("DN: " + dn);
                for (DnUtil.Attr at : DnUtil.attributes(dn)) {
                    System.out.println("   " + at.key() + " = " + at.value());
                }
                List<String[]> sns = DnUtil.serialNumberEntries(dn);
                if (sns.isEmpty()) System.out.println("serialNumberEntry: (žádné)");
                for (String[] sn : sns) {
                    System.out.println("serialNumberEntry: " + sn[0] + " " + sn[1]);
                }
                System.out.println("identityDocument:  "
                        + (DnUtil.identityDocument(dn) == null
                                ? "(žádný — doklad se nerozpoznal)" : DnUtil.identityDocument(dn)));
            }
        }
    }
}
