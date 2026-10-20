package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.enumerations.CertificateStatus;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.model.x509.revocation.Revocation;
import eu.europa.esig.dss.spi.x509.revocation.RevocationSource;
import eu.europa.esig.dss.spi.x509.revocation.RevocationToken;
import eu.europa.esig.dss.spi.x509.revocation.crl.CRLToken;

import java.util.Date;

/**
 * Zahodí revokační údaj, který vznikl až po expiraci certifikátu a přitom nic nedokládá.
 *
 * <p>RFC 5280 §3.3 dovoluje vydavateli po expiraci certifikátu záznam o něm ze seznamu
 * <em>odebrat</em>; přidat ho tam zpětně nikdy nemá důvod. Post-expirační „GOOD“ tedy může stejně
 * dobře znamenat „nebyl odvolán“ i „záznam už jsme smazali“ a nedokládá nic. Post-expirační
 * „REVOKED“ naproti tomu dokládá pořád totéž co v den vydání. Výjimkou je CRL s rozšířením
 * {@code expiredCertsOnCRL} (ITU-T X.509, OID 2.5.29.60) sahajícím do doby platnosti certifikátu:
 * tím se vydavatel zavázal záznamy neodebírat, takže platí i jeho „GOOD“.
 *
 * <p>Filtruje se na úrovni zdroje, ne v {@link LenientRevocationDataVerifier}, a to ze stejného
 * důvodu, jaký je popsaný u {@link FilteringCRLSource}: jakmile se token jednou dostane do
 * kontextu, RAC ho už neodfiltruje, protože {@code doesRevocationKnowCertificate} bývá v politice
 * IGNORE. Měřeno na sbírce: zúžení uvnitř verifieru nemělo na výsledek žádný vliv, protože se
 * k němu token vůbec nedostal.
 *
 * <p>Týká se to jen údajů <b>stahovaných</b> — data vložená v dokumentu tudy neprocházejí a
 * zůstávají nedotčená. Je to záměr, ne opomenutí: vložený údaj tam někdo uložil úmyslně a stojí
 * za ním doklad o existenci v čase, kdežto tenhle filtr řeší údaj, který si validátor obstaral
 * až teď. Rozšířit ho na vložená data by sáhlo na uchování (B-LT/B-LTA), a to tímhle měřením
 * pokryté není.
 */
public class PostExpiryRevokedOnlySource<R extends Revocation> implements RevocationSource<R> {

    private static final long serialVersionUID = 1L;

    private final RevocationSource<R> delegate;

    public PostExpiryRevokedOnlySource(RevocationSource<R> delegate) {
        this.delegate = delegate;
    }

    @Override
    public RevocationToken<R> getRevocationToken(CertificateToken certificateToken,
                                                 CertificateToken issuerCertificateToken) {
        RevocationToken<R> token;
        try {
            token = delegate.getRevocationToken(certificateToken, issuerCertificateToken);
        } catch (Exception e) {
            return null;
        }
        if (token == null) return null;

        Date notAfter = certificateToken.getNotAfter();
        Date thisUpdate = token.getThisUpdate();
        if (notAfter == null || thisUpdate == null) return token;
        if (!thisUpdate.after(notAfter)) return token;      // vznikl za platnosti — beze změny

        CertificateStatus status = token.getStatus();
        if (status != null && status.isRevoked()) return token;

        if (token instanceof CRLToken crlToken) {
            Date eco = crlToken.getExpiredCertsOnCRL();
            if (eco != null && !eco.after(notAfter)) return token;
        }
        return null;
    }
}
