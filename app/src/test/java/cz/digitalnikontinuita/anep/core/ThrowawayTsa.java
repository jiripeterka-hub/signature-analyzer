package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.model.TimestampBinary;
import eu.europa.esig.dss.spi.x509.tsp.TSPSource;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;

import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Razítkovací autorita na jedno použití, celá uvnitř tohoto běhu.
 *
 * <p>Je tu kvůli jediné otázce: <b>zůstane dokument po uchování, přerazítkování nebo prodloužení
 * tím, čím byl — tedy PDF/A?</b> Ta otázka je o struktuře souboru, o tom, co úkon do PDF zapíše.
 * Kdo razítko vydal a jestli mu někdo věří, na ni nemá vliv vůbec žádný. Ostrá autorita by
 * odpověděla totéž a stála by za každý dokument jeden objednaný podpis.
 *
 * <p>Klíč i certifikát vznikají při každém běhu znovu a nikam se neukládají. Certifikát nese
 * rozšířené použití klíče {@code id-kp-timeStamping} jako kritické, protože bez něj RFC 3161
 * razítko odmítne vydat i sám BouncyCastle.
 *
 * <p><b>Nikdy nesmí opustit zkušební zdrojáky.</b> Dokumenty, které z něj vyjdou, jsou měřicí
 * odpad: ověřením neprojdou a projít nemají.
 */
public final class ThrowawayTsa implements TSPSource {

    /** OID politiky razítkování. Vlastní větev pod „example“ — nesmí se srazit s ničím ostrým. */
    private static final ASN1ObjectIdentifier POLICY =
            new ASN1ObjectIdentifier("1.3.6.1.4.1.13762.3");

    private final TimeStampTokenGenerator generator;
    private final AtomicLong serial = new AtomicLong(1);

    public ThrowawayTsa() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();

            X500Principal dn = new X500Principal("CN=AnEP merici TSA, O=AnEP, C=CZ");
            long now = System.currentTimeMillis();
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                    new JcaX509v3CertificateBuilder(dn, BigInteger.ONE,
                            new Date(now - 86_400_000L), new Date(now + 3650L * 86_400_000L),
                            dn, kp.getPublic())
                            .addExtension(Extension.extendedKeyUsage, true,
                                    new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping))
                            .build(new JcaContentSignerBuilder("SHA256withRSA")
                                    .build(kp.getPrivate())));

            X509CertificateHolder holder = new JcaX509CertificateHolder(cert);
            generator = new TimeStampTokenGenerator(
                    new org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder()
                            .build("SHA256withRSA", kp.getPrivate(), cert),
                    new JcaDigestCalculatorProviderBuilder().build()
                            .get(new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                    new ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.1"))),
                    POLICY);
            // JcaCertStore, ne CollectionStore: s tím druhým vyšel token bez jediného
            // certifikátu a měření pak hlásilo, že archivní razítko boří PDF/A — pravidlo
            // ISO 19005-2, 6.4.3-2 žádá, aby podpis nesl certifikát svého vydavatele. Byla to
            // vada přístroje, ne nález o aplikaci; stálo to deset falešně obviněných dokumentů.
            generator.addCertificates(new org.bouncycastle.cert.jcajce.JcaCertStore(
                    java.util.List.of(cert)));
        } catch (Exception e) {
            throw new IllegalStateException("Jednorázovou TSA se nepodařilo postavit", e);
        }
    }

    @Override
    public TimestampBinary getTimeStampResponse(DigestAlgorithm digestAlgorithm, byte[] digest) {
        try {
            // certReq musí být zapnuté. RFC 3161 ho má v žádosti jako příznak „přilož svůj
            // certifikát" a BouncyCastle ho ve výchozím stavu nechává vypnutý — autorita pak
            // certifikát vědomě vynechá. Ostrý klient DSS ho zapíná; bez toho vydá měření
            // nepravdivý závěr, že archivní razítko boří PDF/A (pravidlo ISO 19005-2, 6.4.3-2
            // žádá, aby podpis nesl certifikát podepisujícího).
            TimeStampRequestGenerator requests = new TimeStampRequestGenerator();
            requests.setCertReq(true);
            TimeStampRequest request = requests.generate(
                    new ASN1ObjectIdentifier(digestAlgorithm.getOid()), digest);
            TimeStampResponse response = new TimeStampResponseGenerator(generator,
                    acceptedAlgorithms())
                    .generate(request, BigInteger.valueOf(serial.getAndIncrement()), new Date());
            return new TimestampBinary(response.getTimeStampToken().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Jednorázová TSA razítko nevydala", e);
        }
    }

    /**
     * Orazítkuje soubor touhle autoritou a vrátí výsledek.
     *
     * <p>Je to tady, a ne u volajícího, kvůli viditelnosti: {@code addTimestampDirect} je vidět
     * jen uvnitř balíku {@code core}, kdežto zkoušky protokolu bydlí v {@code ui}. Jinak by se
     * kvůli měření musela otevřít veřejná cesta, kterou aplikace nepotřebuje.
     */
    public static eu.europa.esig.dss.model.DSSDocument stamp(java.io.File pdf) {
        TimestampingService service = new TimestampingService(
                new java.util.concurrent.atomic.AtomicReference<>(
                        new eu.europa.esig.dss.spi.validation.CommonCertificateVerifier()));
        try {
            return service.addTimestampDirect(pdf, null, new ThrowawayTsa(),
                    DigestAlgorithm.SHA256).doc();
        } catch (Exception e) {
            throw new IllegalStateException("Orazítkování jednorázovou autoritou selhalo", e);
        }
    }

    /** Které otisky se přijímají: všechny, které se v měření mohou objevit. */
    private static java.util.Set<String> acceptedAlgorithms() {
        return java.util.Set.of(
                "2.16.840.1.101.3.4.2.1",   // SHA-256
                "2.16.840.1.101.3.4.2.2",   // SHA-384
                "2.16.840.1.101.3.4.2.3",   // SHA-512
                "1.3.14.3.2.26");           // SHA-1
    }
}
