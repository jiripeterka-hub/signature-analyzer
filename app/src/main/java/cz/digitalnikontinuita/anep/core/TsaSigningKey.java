package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.enumerations.Context;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.EncryptionAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureAlgorithm;
import eu.europa.esig.dss.model.TimestampBinary;
import eu.europa.esig.dss.model.policy.CryptographicSuite;
import eu.europa.esig.dss.model.policy.ValidationPolicy;
import eu.europa.esig.dss.spi.DSSPKUtils;
import eu.europa.esig.dss.spi.x509.tsp.TSPSource;
import eu.europa.esig.dss.validation.policy.CryptographicSuiteUtils;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;

import java.util.Collection;
import java.util.Date;
import java.util.function.BiConsumer;

/**
 * Čím autorita podepisuje svá časová razítka: podpisový algoritmus a délka klíče.
 *
 * <p>Vzniklo 5. 10. 2026 na pokyn uživatele, jako upozornění u zdroje časových razítek. Měření
 * nad sbírkami (sonda {@code CryptoHorizonScan}) ukázalo, že většina autorit podepisuje klíčem
 * RSA o 2 048 bitech, který podle ETSI TS 119 312 přestává být spolehlivý 1. 1. 2027 — dřív,
 * než skončí jejich certifikáty (u PostSignum až v roce 2032). Ověření na to zatím reaguje jen
 * varováním (viz politika ověřování, oddíl 10), jiné validátory ale takové razítko po tom datu
 * odmítnout mohou. Uživatel, který si vybírá zdroj pro uchování, to má vědět předem.
 *
 * <p><b>Konec spolehlivosti se nepočítá vlastní tabulkou</b>, nýbrž z kryptografické sady
 * politiky, podle které AnEP ověřuje ({@link ValidationPolicyFactory}) — tedy týmiž daty, na
 * která pak reaguje ověření. Kdyby se sada změnila, upozornění se změní s ní.
 *
 * <p>Zjišťuje se z tokenu, ne z certifikátu: rozhoduje algoritmus, kterým je razítko
 * podepsané (RSA a RSASSA-PSS mají u téhož klíče různá data), a ten nese jen token.
 */
public record TsaSigningKey(SignatureAlgorithm algorithm, int keyLength) {

    /**
     * Komu ohlásit klíč zjištěný při skutečném razítkování. Nastavuje ho hlavní okno; jádro
     * o nastavení aplikace neví a vědět nemá. {@code null} = nikomu (sondy, testy).
     *
     * <p>Bez toho by klíč znaly jen zdroje přidané od 5. 10. 2026 — test zdroje proběhne jen
     * při jeho zadání. Takhle ho zdroj založený dřív dostane při prvním dalším použití.
     */
    public static volatile BiConsumer<TsaProfile, TsaSigningKey> observer;

    /** Klíč z tokenu (CMS i obalené odpovědi); {@code null}, když se přečíst nedá. */
    public static TsaSigningKey fromToken(byte[] tsBytes) {
        if (tsBytes == null) return null;
        try {
            TimeStampToken tst;
            try {
                tst = new TimeStampToken(new org.bouncycastle.cms.CMSSignedData(tsBytes));
            } catch (Exception notAToken) {
                tst = new TimeStampResponse(tsBytes).getTimeStampToken();
            }
            if (tst == null) return null;
            Collection<SignerInformation> signers =
                    tst.toCMSSignedData().getSignerInfos().getSigners();
            if (signers.isEmpty()) return null;
            SignerInformation signer = signers.iterator().next();
            SignatureAlgorithm alg = algorithm(signer);
            @SuppressWarnings("unchecked")
            Collection<X509CertificateHolder> certs =
                    (Collection<X509CertificateHolder>) tst.getCertificates()
                            .getMatches(signer.getSID());
            if (alg == null || certs.isEmpty()) return null;
            var cert = new JcaX509CertificateConverter().getCertificate(certs.iterator().next());
            int size = DSSPKUtils.getPublicKeySize(cert.getPublicKey());
            return size > 0 ? new TsaSigningKey(alg, size) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Algoritmus z identifikátorů v SignerInfo. U RSA nese pole jen „rsaEncryption“ a otisk je
     * zvlášť; u ECDSA a RSASSA-PSS je v poli rovnou podpisový algoritmus, u PSS i s parametry.
     */
    private static SignatureAlgorithm algorithm(SignerInformation signer) {
        String encOid = signer.getEncryptionAlgOID();
        try {
            EncryptionAlgorithm enc = EncryptionAlgorithm.forOID(encOid);
            DigestAlgorithm digest = DigestAlgorithm.forOID(signer.getDigestAlgOID());
            return SignatureAlgorithm.getAlgorithm(enc, digest);
        } catch (Exception notBare) {
            try {
                return SignatureAlgorithm.forOidAndParams(encOid, signer.getEncryptionAlgParams());
            } catch (Exception noParams) {
                try {
                    return SignatureAlgorithm.forOID(encOid);
                } catch (Exception unknown) {
                    return null;
                }
            }
        }
    }

    /**
     * Od kdy kryptografická sada politiky ověřování algoritmus s touto délkou klíče za
     * spolehlivý nepovažuje; {@code null} = bez omezení.
     */
    public Date reliableUntil(ValidationPolicy policy) {
        CryptographicSuite suite = policy.getSignatureCryptographicConstraint(Context.TIMESTAMP);
        if (suite == null) return null;
        return CryptographicSuiteUtils.getExpirationDate(suite, algorithm, keyLength);
    }

    /**
     * Klíč zdroje, který přestane být spolehlivý dřív, než skončí certifikát autority.
     *
     * @param until    od kdy se nepovažuje za spolehlivý
     * @param forecast datum leží za poslední aktualizací kryptografické sady — ověření na ně
     *                 reaguje jen varováním (politika ověřování, oddíl 10)
     */
    public record Weakness(TsaSigningKey key, Date until, boolean forecast) { }

    /**
     * Je klíč zdroje „slabý“? {@code null}, když klíč není zjištěný, když ho sada neomezuje,
     * nebo když omezení leží až za koncem certifikátu autority — tam o konci rozhoduje
     * certifikát a datum ve jménu souboru to už říká.
     */
    public static Weakness weakness(TsaProfile profile, ValidationPolicy policy) {
        if (profile == null || policy == null) return null;
        TsaSigningKey key = profile.getSigningKey();
        if (key == null) return null;
        Date until = key.reliableUntil(policy);
        if (until == null) return null;
        Date certEnd = profile.getCertNotAfter();
        if (certEnd != null && !until.before(certEnd)) return null;
        return new Weakness(key, until, isForecast(until, policy));
    }

    /** Leží datum za poslední aktualizací kryptografické sady (tedy je to odhad)? */
    public static boolean isForecast(Date until, ValidationPolicy policy) {
        Date updated = suiteUpdateDate(policy);
        return updated != null && until != null && updated.before(until);
    }

    /** Datum poslední aktualizace kryptografické sady; data za ním jsou odhad. */
    public static Date suiteUpdateDate(ValidationPolicy policy) {
        CryptographicSuite suite = policy.getSignatureCryptographicConstraint(Context.TIMESTAMP);
        return suite != null ? suite.getCryptographicSuiteUpdateDate() : null;
    }

    /** „RSA, klíč 2 048 bitů“ — pro text upozornění. */
    public String describe() {
        String size = String.format(java.util.Locale.ROOT, "%,d", keyLength).replace(',', ' ');
        return algorithm.getEncryptionAlgorithm().getName() + ", klíč " + size + " bitů";
    }

    /** Zápis do nastavení: jméno algoritmu DSS a délka. */
    public String persist() {
        return algorithm.name() + ":" + keyLength;
    }

    /** Opak {@link #persist()}; {@code null} pro prázdný nebo nečitelný zápis. */
    public static TsaSigningKey parse(String s) {
        if (s == null || s.isBlank()) return null;
        int colon = s.lastIndexOf(':');
        if (colon < 0) return null;
        try {
            return new TsaSigningKey(SignatureAlgorithm.valueOf(s.substring(0, colon)),
                    Integer.parseInt(s.substring(colon + 1)));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Zdroj razítek, který po každém razítku ohlásí klíč autority {@link #observer}u. Razítko
     * vrací beze změny; nepodaří-li se klíč přečíst, mlčí.
     */
    public static TSPSource observing(TSPSource delegate, TsaProfile profile) {
        return new Observing(delegate, profile);
    }

    private record Observing(TSPSource delegate, TsaProfile profile) implements TSPSource {
        @Override
        public TimestampBinary getTimeStampResponse(DigestAlgorithm digestAlgorithm,
                                                    byte[] digest) {
            TimestampBinary binary = delegate.getTimeStampResponse(digestAlgorithm, digest);
            BiConsumer<TsaProfile, TsaSigningKey> o = observer;
            if (o != null && binary != null) {
                TsaSigningKey key = fromToken(binary.getBytes());
                if (key != null) o.accept(profile, key);
            }
            return binary;
        }
    }
}
