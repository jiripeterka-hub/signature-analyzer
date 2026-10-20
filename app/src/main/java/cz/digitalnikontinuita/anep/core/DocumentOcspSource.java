package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.model.x509.revocation.ocsp.OCSP;
import eu.europa.esig.dss.spi.x509.revocation.RevocationSource;
import eu.europa.esig.dss.spi.x509.revocation.RevocationToken;
import eu.europa.esig.dss.spi.x509.revocation.ocsp.ExternalResourcesOCSPSource;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Serves OCSP responses the document already carries, so that preservation does not go online for
 * an answer it is holding in its hand — the counterpart of {@link DocumentCrlSource}, whose absence
 * was what made the CRLs expensive in the first place.
 *
 * <p>The sequence it prevents, measured on {@code T uznavany eIdentity.pdf}: the document carries an
 * OCSP response from the day it was signed, issued while the signing certificate was valid and
 * saying GOOD — everything preservation needs. Nothing offered that response to the loading
 * strategy, so the strategy asked the responder, which answered UNKNOWN with a {@code thisUpdate} of
 * today, as responders do for a certificate that expired three years ago. Post-expiry and
 * uninformative, that answer sent the strategy down the CRL branch, and the list it came back with
 * ran to 231 kB — written into the document by DSS, where an incremental save cannot take bytes back
 * out.
 *
 * <p><b>Deliberately narrow, for the same reason as {@link DocumentCrlSource}.</b> A response is
 * served only for a certificate that has <em>already expired</em>. While a certificate is still
 * alive, fresh revocation data is the whole point of preserving, and serving a stale response from
 * the document would embed an answer older than the one available — so this source stands aside and
 * the online path runs exactly as before.
 *
 * <p>Not for validation. There the document's own material is evidence DSS collects and weighs by
 * itself, and pre-empting the online answer would hide the current state of a certificate from the
 * reader.
 */
public class DocumentOcspSource implements RevocationSource<OCSP> {

    private static final long serialVersionUID = 1L;

    /** Null when the document carries no parseable response — then only {@link #online} answers. */
    private final ExternalResourcesOCSPSource embedded;

    private final RevocationSource<OCSP> online;

    public DocumentOcspSource(List<byte[]> ocspBytes, RevocationSource<OCSP> online) {
        this.online = online;
        List<DSSDocument> parsed = new ArrayList<>();
        if (ocspBytes != null) {
            for (byte[] bytes : ocspBytes) {
                if (bytes == null || bytes.length == 0) continue;
                parsed.add(new InMemoryDocument(bytes));
            }
        }
        ExternalResourcesOCSPSource source = null;
        if (!parsed.isEmpty()) {
            try {
                source = new ExternalResourcesOCSPSource(parsed.toArray(new DSSDocument[0]));
            } catch (Exception e) {
                source = null;   // unparseable material is validation's problem, not preservation's
            }
        }
        this.embedded = source;
    }

    @Override
    public RevocationToken<OCSP> getRevocationToken(CertificateToken certificateToken,
                                                    CertificateToken issuerCertificateToken) {
        RevocationToken<OCSP> token = fromDocument(certificateToken, issuerCertificateToken);
        if (token != null && stillWorthKeeping(token)) return token;
        RevocationToken<OCSP> fresh = online == null ? null
                : online.getRevocationToken(certificateToken, issuerCertificateToken);
        // Vlastní odpověď dokumentu se nezahazuje: když síť ničím neodpoví, je pořád lepší než nic.
        return fresh != null ? fresh : token;
    }

    /**
     * Vydrží ta vložená odpověď i po uchování?
     *
     * <p>Odpověď, jejímuž respondérovi už skončila platnost certifikátu, uznat nelze — a razítko
     * přidané teď na tom nic nezmění: dokazovalo by existenci odpovědi k dnešku, tedy k době,
     * kdy respondér již neplatil. Uchování by tím nechalo v dokumentu materiál, který mu
     * k ničemu není, a odpuštěným stažením čerstvé odpovědi by ho nechalo závislý na síti.
     *
     * <p>Změřeno na úředním dokumentu: nesl odpověď z 2. 6. 2026 od respondéra
     * s certifikátem do 14. 6. 2026. Uchování ji posloužilo místo sítě, nic nového nevložilo —
     * a uchovaný dokument pak bez sítě skončil na INDETERMINATE/REVOCATION_OUT_OF_BOUNDS_NO_POE.
     */
    private static boolean stillWorthKeeping(RevocationToken<OCSP> token) {
        CertificateToken issuer = token.getIssuerCertificateToken();
        if (issuer == null || issuer.getNotAfter() == null) return true;
        return !issuer.getNotAfter().before(new Date());
    }

    private RevocationToken<OCSP> fromDocument(CertificateToken cert, CertificateToken issuer) {
        if (embedded == null || cert == null) return null;
        Date notAfter = cert.getNotAfter();
        if (notAfter == null || !notAfter.before(new Date())) return null;  // still valid — go online
        try {
            RevocationToken<OCSP> token = embedded.getRevocationToken(cert, issuer);
            // Only a response that speaks about the certificate's own lifetime is worth pre-empting
            // the network with; anything else leaves the online path to answer as it did before.
            return RevocationCoverage.isAuthoritative(token, cert) ? token : null;
        } catch (Exception e) {
            return null;
        }
    }
}
