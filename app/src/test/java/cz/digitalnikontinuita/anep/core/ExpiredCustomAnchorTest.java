package cz.digitalnikontinuita.anep.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.digitalnikontinuita.anep.model.SignatureSummary.ExpiredCustomAnchor;
import eu.europa.esig.dss.cades.CAdESSignatureParameters;
import eu.europa.esig.dss.cades.signature.CAdESService;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.enumerations.SignatureAlgorithm;
import eu.europa.esig.dss.enumerations.TokenExtractionStrategy;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.enumerations.SignaturePackaging;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.SignatureValue;
import eu.europa.esig.dss.model.TimestampBinary;
import eu.europa.esig.dss.model.ToBeSigned;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource;
import eu.europa.esig.dss.spi.x509.revocation.crl.ExternalResourcesCRLSource;
import eu.europa.esig.dss.spi.x509.tsp.TSPSource;
import eu.europa.esig.dss.validation.SignedDocumentValidator;
import eu.europa.esig.dss.validation.reports.Reports;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kdy se čtenář dozví o kotvě z vlastního úložiště po konci její platnosti (6. 11. 2026).
 *
 * <p>Certifikáty a podpisy vznikají přímo v testu, nad výchozí politikou AnEP a bez sítě. Výsledek
 * ověření testy hlídají také: změna je jen ve výkladu, a kdyby DSS začal expirovanou kotvu
 * odmítat, poznámka by přestala dávat smysl.
 */
class ExpiredCustomAnchorTest {

    private record Issued(KeyPair keys, X509Certificate cert) {}

    private static long serial = 1000;

    private static Date day(String s) {
        return Date.from(LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static X500Name subjectOf(X509Certificate c) {
        return X500Name.getInstance(c.getSubjectX500Principal().getEncoded());
    }

    private static Issued issue(String cn, Issued issuer, boolean ca, boolean tsa,
                                String from, String to) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        X500Name subject = new X500Name("CN=" + cn + ",O=Test,C=CZ");
        PrivateKey signer = issuer == null ? kp.getPrivate() : issuer.keys().getPrivate();
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                issuer == null ? subject : subjectOf(issuer.cert()),
                BigInteger.valueOf(serial++), day(from), day(to), subject, kp.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(ca
                ? KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature
                : KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
        if (tsa) {
            b.addExtension(Extension.extendedKeyUsage, true,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
        }
        return new Issued(kp, new JcaX509CertificateConverter().getCertificate(
                b.build(new JcaContentSignerBuilder("SHA256withRSA").build(signer))));
    }

    private static DSSDocument crlOf(Issued issuer) throws Exception {
        long now = System.currentTimeMillis();
        X509v2CRLBuilder b = new X509v2CRLBuilder(subjectOf(issuer.cert()),
                new Date(now - 86_400_000L));
        b.setNextUpdate(new Date(now + 30 * 86_400_000L));
        return new InMemoryDocument(b.build(new JcaContentSignerBuilder("SHA256withRSA")
                .build(issuer.keys().getPrivate())).getEncoded());
    }

    /** Razítkovací autorita, která razítkuje teď a ve svém jméně. */
    private static TSPSource tspOf(Issued tsa) {
        return (DigestAlgorithm digest, byte[] value) -> {
            try {
                TimeStampRequestGenerator requests = new TimeStampRequestGenerator();
                requests.setCertReq(true);  // Jinak token certifikát neponese a DSS nemá s čím ověřovat.
                TimeStampRequest req = requests.generate(
                        new ASN1ObjectIdentifier(digest.getOid()), value);
                DigestCalculatorProvider dcp = new JcaDigestCalculatorProviderBuilder().build();
                TimeStampTokenGenerator gen = new TimeStampTokenGenerator(
                        new JcaSimpleSignerInfoGeneratorBuilder()
                                .build("SHA256withRSA", tsa.keys().getPrivate(), tsa.cert()),
                        dcp.get(new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                NISTObjectIdentifiers.id_sha256)),
                        new ASN1ObjectIdentifier("1.2.3.4.5"));
                gen.addCertificates(new JcaCertStore(List.of(tsa.cert())));
                return new TimestampBinary(gen.generate(req, BigInteger.valueOf(serial++), new Date())
                        .getEncoded());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private static DSSDocument sign(Issued signer, List<Issued> chain, String signingDate,
                                    Issued tsa) throws Exception {
        CAdESSignatureParameters p = new CAdESSignatureParameters();
        p.setSignatureLevel(tsa != null
                ? SignatureLevel.CAdES_BASELINE_T : SignatureLevel.CAdES_BASELINE_B);
        p.setSignaturePackaging(SignaturePackaging.ENVELOPING);
        p.setDigestAlgorithm(DigestAlgorithm.SHA256);
        p.setSigningCertificate(new CertificateToken(signer.cert()));
        List<CertificateToken> tokens = new ArrayList<>();
        for (Issued c : chain) tokens.add(new CertificateToken(c.cert()));
        p.setCertificateChain(tokens);
        p.bLevel().setSigningDate(day(signingDate));
        CAdESService service = new CAdESService(new CommonCertificateVerifier());
        if (tsa != null) service.setTspSource(tspOf(tsa));
        DSSDocument data = new InMemoryDocument("zkouska".getBytes());
        ToBeSigned tbs = service.getDataToSign(data, p);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(signer.keys().getPrivate());
        s.update(tbs.getBytes());
        return service.signDocument(data, p,
                new SignatureValue(SignatureAlgorithm.RSA_SHA256, s.sign()));
    }

    /** Ověří podpis a vrátí poznámku; zároveň hlídá, že se výsledek ověření nezměnil. */
    private static ExpiredCustomAnchor note(DSSDocument signature, List<Issued> store,
                                            List<Issued> crlIssuers, Instant bestSigTime)
            throws Exception {
        CommonCertificateVerifier v = new CommonCertificateVerifier();
        CommonTrustedCertificateSource src = new CommonTrustedCertificateSource();
        List<CustomTrustStore.Entry> entries = new ArrayList<>();
        for (Issued a : store) {
            CertificateToken token = new CertificateToken(a.cert());
            src.addCertificate(token);
            entries.add(new CustomTrustStore.Entry(null, token));
        }
        v.setTrustedCertSources(src);
        List<DSSDocument> crls = new ArrayList<>();
        for (Issued c : crlIssuers) crls.add(crlOf(c));
        if (!crls.isEmpty()) {
            v.setCrlSource(new ExternalResourcesCRLSource(crls.toArray(new DSSDocument[0])));
        }
        v.setAIASource(null);
        SignedDocumentValidator validator = SignedDocumentValidator.fromDocument(signature);
        validator.setCertificateVerifier(v);
        // Jako ValidatorFactory: bez toho diagnostická data nenesou certifikáty, s nimiž se úložiště porovnává.
        validator.setTokenExtractionStrategy(TokenExtractionStrategy.EXTRACT_ALL);
        Reports reports = validator.validateDocument(ValidationPolicyFactory.build(new AppSettings()));
        SimpleReport simple = reports.getSimpleReport();
        String id = simple.getFirstSignatureId();
        assertEquals(Indication.TOTAL_PASSED, simple.getIndication(id),
                "Kotva po platnosti nemá výsledek ověření změnit");
        return ReportMapper.expiredCustomAnchor(id, reports.getDiagnosticData(), simple, entries,
                bestSigTime);
    }

    @Test
    @DisplayName("Kotva v platnosti: žádná poznámka")
    void validAnchorHasNoNote() throws Exception {
        Issued root = issue("Koren", null, true, false, "2015-01-01", "2099-01-01");
        Issued leaf = issue("List", root, false, false, "2024-01-01", "2099-01-01");
        assertNull(note(sign(leaf, List.of(leaf, root), "2024-06-01", null),
                List.of(root), List.of(root), null));
    }

    @Test
    @DisplayName("Bez důkazu existence rozhoduje čas ověření: kotva po platnosti se ohlásí")
    void withoutProofExpiredAnchorIsReported() throws Exception {
        Issued root = issue("Koren", null, true, false, "2015-01-01", "2025-01-01");
        Issued leaf = issue("List", root, false, false, "2024-01-01", "2099-01-01");
        ExpiredCustomAnchor anchor = note(sign(leaf, List.of(leaf, root), "2024-06-01", null),
                List.of(root), List.of(root), null);
        assertNotNull(anchor);
        assertFalse(anchor.signingCertificate());
        assertNull(anchor.timestampTime());
        assertEquals(day("2025-01-01").toInstant(), anchor.notAfter());
    }

    @Test
    @DisplayName("Podpis doložený z doby platnosti kotvy: běžný starý podpis, žádná poznámka")
    void proofBeforeExpiryHasNoNote() throws Exception {
        Issued root = issue("Koren", null, true, false, "2015-01-01", "2025-01-01");
        Issued leaf = issue("List", root, false, false, "2024-01-01", "2099-01-01");
        assertNull(note(sign(leaf, List.of(leaf, root), "2024-06-01", null),
                List.of(root), List.of(root), day("2024-06-01").toInstant()));
    }

    @Test
    @DisplayName("Doložení až po konci platnosti kotvy se ohlásí")
    void proofAfterExpiryIsReported() throws Exception {
        Issued root = issue("Koren", null, true, false, "2015-01-01", "2025-01-01");
        Issued leaf = issue("List", root, false, false, "2024-01-01", "2099-01-01");
        assertNotNull(note(sign(leaf, List.of(leaf, root), "2026-06-01", null),
                List.of(root), List.of(root), day("2026-06-01").toInstant()));
    }

    @Test
    @DisplayName("Kotvou sám podpisový certifikát: ohlásí se i se starším důkazem existence")
    void signingCertificateAsAnchorIsAlwaysReported() throws Exception {
        Issued self = issue("Sam", null, false, false, "2020-01-01", "2025-01-01");
        ExpiredCustomAnchor anchor = note(sign(self, List.of(self), "2024-06-01", null),
                List.of(self), List.of(), day("2024-06-01").toInstant());
        assertNotNull(anchor);
        assertTrue(anchor.signingCertificate());
    }

    @Test
    @DisplayName("Razítko vydané po konci platnosti své vlastní kotvy se ohlásí")
    void timestampAnchorExpiredBeforeIssueIsReported() throws Exception {
        Issued root = issue("Koren", null, true, false, "2015-01-01", "2099-01-01");
        Issued leaf = issue("List", root, false, false, "2024-01-01", "2099-01-01");
        // Kotvou je autorita nad razítkovým certifikátem. Razítkový certifikát sám musí v okamžiku
        // razítka platit: změřeno, jinak razítko vyjde FAILED / SIG_CRYPTO_FAILURE i jako kotva
        // z úložiště, takže se o něj žádný závěr neopře a poznámka by nebyla o čem.
        Issued tsaRoot = issue("Koren razitek", null, true, false, "2015-01-01", "2025-01-01");
        Issued tsa = issue("Razitko", tsaRoot, false, true, "2024-01-01", "2099-01-01");
        ExpiredCustomAnchor anchor = note(sign(leaf, List.of(leaf, root), "2024-06-01", tsa),
                List.of(root, tsaRoot), List.of(root, tsaRoot), Instant.now());
        assertNotNull(anchor);
        assertFalse(anchor.signingCertificate());
        assertNotNull(anchor.timestampTime());
    }
}
