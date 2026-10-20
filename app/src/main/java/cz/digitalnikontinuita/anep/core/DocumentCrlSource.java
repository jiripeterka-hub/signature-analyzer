package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.model.x509.revocation.crl.CRL;
import eu.europa.esig.dss.spi.x509.revocation.RevocationSource;
import eu.europa.esig.dss.spi.x509.revocation.RevocationToken;
import eu.europa.esig.dss.spi.x509.revocation.crl.ExternalResourcesCRLSource;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Serves CRLs that the document already carries in its DSS dictionary, so that augmentation
 * does not go online for revocation data it is holding in its hand.
 *
 * <p>Motivation is size. A CRL fetched during augmentation for a certificate that expired years
 * ago says nothing about it (RFC 5280 clause 3.3 lets the authority drop the entry once the
 * certificate expires) and is stripped from {@code /CRLs} afterwards
 * — but stripping does not shrink the file. The document is written with {@code saveIncremental},
 * which only appends, so the stream stays in the bytes as an orphan; one such CRL added 842 kB to
 * a 181 kB document. A full rewrite is not an option, it would break the existing signatures'
 * ByteRange. The only way to keep the bytes out is not to fetch them.
 *
 * <p><b>Deliberately narrow.</b> A token is served only for a certificate that has <em>already
 * expired</em>. For such a certificate no freshly issued CRL can add anything, while the document
 * necessarily carries the one that does cover it — that is what made the signature LT in the first
 * place. For a certificate still in its validity period this source stands aside and returns
 * {@code null}, so the online fetch happens exactly as before: there, a fresh CRL is the point, and
 * serving a stale one from the document could leave DSS with revocation data it then rejects as
 * inconsistent.
 *
 * <p>Selecting among the document's CRLs is the same problem the user's custom store poses, so the
 * selection is delegated to {@link BestMatchCustomCrlSource} — issuer matching plus the
 * {@code expiredCertsOnCRL} rules. When none of the document's CRLs authoritatively covers the
 * certificate, {@code null} falls through to the online path unchanged.
 */
public class DocumentCrlSource implements RevocationSource<CRL> {

    private static final long serialVersionUID = 1L;

    private final BestMatchCustomCrlSource delegate;

    public DocumentCrlSource(List<byte[]> crlBytes) {
        List<DSSDocument> parsed = new ArrayList<>();
        if (crlBytes != null) {
            for (byte[] bytes : crlBytes) {
                if (bytes == null || bytes.length == 0) continue;
                DSSDocument doc = new InMemoryDocument(bytes);
                try {
                    new ExternalResourcesCRLSource(doc);   // reject unparseable entries up front
                } catch (Exception e) {
                    continue;
                }
                parsed.add(doc);
            }
        }
        this.delegate = parsed.isEmpty() ? null : new BestMatchCustomCrlSource(parsed);
    }

    @Override
    public RevocationToken<CRL> getRevocationToken(CertificateToken cert,
                                                   CertificateToken issuer) {
        if (delegate == null) return null;
        Date notAfter = cert.getNotAfter();
        if (notAfter == null || !notAfter.before(new Date())) return null;  // still valid — go online
        try {
            return delegate.getRevocationToken(cert, issuer);
        } catch (Exception e) {
            return null;
        }
    }
}
