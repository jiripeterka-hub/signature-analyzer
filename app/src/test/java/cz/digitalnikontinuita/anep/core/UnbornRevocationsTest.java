package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.diagnostic.jaxb.XmlCertificate;
import eu.europa.esig.dss.diagnostic.jaxb.XmlCertificateRevocation;
import eu.europa.esig.dss.diagnostic.jaxb.XmlRevocation;
import eu.europa.esig.dss.diagnostic.jaxb.XmlSigningCertificate;
import eu.europa.esig.dss.enumerations.CertificateStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Které revokační údaje se při přepočtu k odvozenému okamžiku odkládají
 * ({@link ValidatorFactory#unbornRevocations}).
 *
 * <p>Čísla jsou z „PAdES B-B +B-T kvalifikovany ICA.pdf", prvek č. 1: kontrolní čas 24. 6. 2026
 * (razítko krycího podpisu), vložená odpověď OCSP z téhož dne od respondéra platného od 5. 6.,
 * odpověď stažená 29. 9. od respondéra platného až od 18. 9.; odvolání 27. 8. 2026.
 */
class UnbornRevocationsTest {

    private static final Date CONTROL = d("2026-06-24T07:19:48Z");
    private static final Date REVOKED_AT = d("2026-08-27T07:27:12Z");

    @Test
    @DisplayName("stažený údaj, jehož respondér ke kontrolnímu času neplatil, se odloží")
    void laterResponderIsSetAside() {
        XmlCertificateRevocation embedded = rev("2026-06-24T07:14:17Z", "2026-06-05T13:22:49Z",
                "2026-09-23T13:22:46Z", CertificateStatus.GOOD, null);
        XmlCertificateRevocation online = rev("2026-09-29T08:47:07Z", "2026-09-18T08:59:33Z",
                "2027-01-06T08:59:30Z", CertificateStatus.REVOKED, REVOKED_AT);
        assertEquals(List.of(online),
                ValidatorFactory.unbornRevocations(cert(embedded, online), CONTROL, CONTROL));
    }

    @Test
    @DisplayName("údaj dokládající odvolání před rozhodným okamžikem se ponechá")
    void earlierRevocationIsKept() {
        XmlCertificateRevocation online = rev("2026-09-29T08:47:07Z", "2026-09-18T08:59:33Z",
                "2027-01-06T08:59:30Z", CertificateStatus.REVOKED, d("2026-06-20T00:00:00Z"));
        assertTrue(ValidatorFactory.unbornRevocations(cert(online), CONTROL, CONTROL).isEmpty());
    }

    @Test
    @DisplayName("pozdější údaj od respondéra platného i ke kontrolnímu času zůstává")
    void laterDataWithValidResponderIsKept() {
        XmlCertificateRevocation online = rev("2026-09-29T08:47:07Z", "2026-06-05T13:22:49Z",
                "2027-01-06T08:59:30Z", CertificateStatus.REVOKED, REVOKED_AT);
        assertTrue(ValidatorFactory.unbornRevocations(cert(online), CONTROL, CONTROL).isEmpty());
    }

    @Test
    @DisplayName("údaj z doby před kontrolním časem se neodkládá")
    void earlierDataIsKept() {
        XmlCertificateRevocation embedded = rev("2026-06-24T07:14:17Z", "2026-06-25T00:00:00Z",
                "2026-09-23T13:22:46Z", CertificateStatus.GOOD, null);
        assertTrue(ValidatorFactory.unbornRevocations(cert(embedded), CONTROL, CONTROL).isEmpty());
    }

    private static XmlCertificate cert(XmlCertificateRevocation... revs) {
        XmlCertificate c = new XmlCertificate();
        c.getRevocations().addAll(List.of(revs));
        return c;
    }

    private static XmlCertificateRevocation rev(String produced, String issuerFrom,
                                                String issuerTo, CertificateStatus status,
                                                Date revokedAt) {
        XmlCertificate issuer = new XmlCertificate();
        issuer.setNotBefore(d(issuerFrom));
        issuer.setNotAfter(d(issuerTo));
        XmlSigningCertificate sc = new XmlSigningCertificate();
        sc.setCertificate(issuer);
        XmlRevocation r = new XmlRevocation();
        r.setProductionDate(d(produced));
        r.setThisUpdate(d(produced));
        r.setSigningCertificate(sc);
        XmlCertificateRevocation cr = new XmlCertificateRevocation();
        cr.setRevocation(r);
        cr.setStatus(status);
        cr.setRevocationDate(revokedAt);
        return cr;
    }

    private static Date d(String s) {
        return Date.from(Instant.parse(s));
    }
}
