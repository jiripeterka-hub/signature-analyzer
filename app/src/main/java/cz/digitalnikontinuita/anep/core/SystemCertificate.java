package cz.digitalnikontinuita.anep.core;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.x509.CertificatePolicies;
import org.bouncycastle.asn1.x509.PolicyInformation;
import org.bouncycastle.asn1.x509.PolicyQualifierId;
import org.bouncycastle.asn1.x509.PolicyQualifierInfo;
import org.bouncycastle.asn1.x509.UserNotice;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Systémový certifikát podle zákona č. 227/2000 Sb. — podklad elektronické značky — poznaný
 * podle textového upozornění (userNotice) v certifikační politice.
 *
 * <p>Tím upozorněním se takový certifikát sám prohlašuje, a to u všech vydavatelů, které sbírka
 * zná: PostSignum první generace („Tento certifikat byl vydan jako kvalifikovany systemovy
 * certifikat ve smyslu zakona 227/2000 Sb.“, politika 2.23.134.1.4.1.3.x), PostSignum druhé
 * generace (2.23.134.1.4.1.8.x) i I.CA („Tento kvalifikovany systemovy certifikat je vydan podle
 * zakona c. 227/2000 Sb.“, 1.3.6.1.4.1.23624.1.1.40.x; od 2016 10.1.33.x — viz certifikační
 * politika I.CA „CP vydávání systémových certifikátů“, kap. 7.1.2). Čísla politik se mezi
 * vydavateli i generacemi liší, znění upozornění ne.
 *
 * <p>QcStatements ani QcCompliance k tomu nestačí: kvalifikovaný systémový certifikát
 * PostSignum nese jen 1.3.6.1.5.5.7.11.2, systémový certifikát I.CA pro SecuStamp nenese nic
 * a certifikát I.CA pro Pozemkový fond ČR (2010) nese QcCompliance — ač je systémový.
 */
public final class SystemCertificate {

    public enum Kind { NONE, SYSTEM, QUALIFIED_SYSTEM }

    private SystemCertificate() { }

    public static Kind of(byte[] der) {
        if (der == null) return Kind.NONE;
        try {
            X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
            byte[] ext = c.getExtensionValue("2.5.29.32");
            if (ext == null) return Kind.NONE;
            Kind result = Kind.NONE;
            for (PolicyInformation pi : CertificatePolicies.getInstance(
                    ASN1OctetString.getInstance(ext).getOctets()).getPolicyInformation()) {
                if (pi.getPolicyQualifiers() == null) continue;
                for (ASN1Encodable q : pi.getPolicyQualifiers()) {
                    PolicyQualifierInfo pq = PolicyQualifierInfo.getInstance(q);
                    if (!PolicyQualifierId.id_qt_unotice.equals(pq.getPolicyQualifierId())) continue;
                    UserNotice un = UserNotice.getInstance(pq.getQualifier());
                    if (un.getExplicitText() == null) continue;
                    Kind k = classify(un.getExplicitText().getString());
                    if (k.ordinal() > result.ordinal()) result = k;
                }
            }
            return result;
        } catch (Exception e) {
            return Kind.NONE;
        }
    }

    /** Rozhodne podle znění upozornění; diakritika a velikost písmen nehrají roli. */
    static Kind classify(String notice) {
        String t = Normalizer.normalize(notice, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        if (t.contains("kvalifikovany systemovy") || t.contains("qualified system certificate")) {
            return Kind.QUALIFIED_SYSTEM;
        }
        if (t.contains("systemovy certifikat") || t.contains("system certificate")) {
            return Kind.SYSTEM;
        }
        return Kind.NONE;
    }
}
