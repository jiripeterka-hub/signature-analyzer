package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.jaxb.XmlGeneralName;
import eu.europa.esig.dss.enumerations.GeneralNameType;

/**
 * Extracts a certificate holder's e-mail address, checking both the Subject DN's
 * emailAddress attribute and the Subject Alternative Name extension (rfc822Name) — many
 * Czech qualified certificates only carry the e-mail in the SAN, not in the DN.
 */
public final class CertificateEmailUtil {

    private CertificateEmailUtil() {
    }

    /** Returns the holder's e-mail address, or {@code null} if none is present. */
    public static String extractEmail(CertificateWrapper cert) {
        if (cert == null) return null;

        String direct = cert.getEmail();
        if (direct != null && !direct.isBlank()) return direct;

        if (cert.getSubjectAlternativeNames() == null) return null;
        for (XmlGeneralName name : cert.getSubjectAlternativeNames()) {
            if (name.getType() == GeneralNameType.RFC822_NAME
                    && name.getValue() != null && !name.getValue().isBlank()) {
                return name.getValue();
            }
        }
        return null;
    }
}
