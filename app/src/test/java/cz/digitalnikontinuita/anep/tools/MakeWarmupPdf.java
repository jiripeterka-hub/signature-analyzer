package cz.digitalnikontinuita.anep.tools;

import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.SignatureValue;
import eu.europa.esig.dss.model.ToBeSigned;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.pades.PAdESSignatureParameters;
import eu.europa.esig.dss.pades.signature.PAdESService;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * One-off generator for the warm-up fixture used by
 * {@link cz.digitalnikontinuita.anep.core.WarmupService}: a minimal PDF carrying one PAdES-B
 * signature made with a throw-away self-signed key.
 *
 * <p>Its only purpose is to make the DSS signature-parsing stack (CMS, BouncyCastle,
 * certificate handling) load in the background at startup, so it never needs to be
 * trustworthy or even still valid — it is never shown to the user.
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.tools.MakeWarmupPdf app/src/main/resources/warmup/warmup-signed.pdf</pre>
 */
public final class MakeWarmupPdf {

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0]
                : "app/src/main/resources/warmup/warmup-signed.pdf");

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X500Principal dn = new X500Principal("CN=AnEP warmup, O=AnEP, C=CZ");
        long now = System.currentTimeMillis();
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(dn, BigInteger.ONE,
                        new Date(now - 86_400_000L), new Date(now + 3650L * 86_400_000L),
                        dn, kp.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate())));

        byte[] pdfBytes;
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());
            pdf.save(bos);
            pdfBytes = bos.toByteArray();
        }
        DSSDocument doc = new InMemoryDocument(pdfBytes, "warmup.pdf");

        PAdESSignatureParameters params = new PAdESSignatureParameters();
        params.setSigningCertificate(new CertificateToken(cert));
        params.setCertificateChain(new CertificateToken(cert));
        params.setSignatureLevel(SignatureLevel.PAdES_BASELINE_B);
        params.setDigestAlgorithm(DigestAlgorithm.SHA256);

        PAdESService service = new PAdESService(new CommonCertificateVerifier());
        ToBeSigned toBeSigned = service.getDataToSign(doc, params);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(kp.getPrivate());
        signer.update(toBeSigned.getBytes());
        SignatureValue sv = new SignatureValue(SignatureAlgorithm.RSA_SHA256, signer.sign());

        DSSDocument signed = service.signDocument(doc, params, sv);
        Files.createDirectories(out.getParent());
        try (var in = signed.openStream()) {
            Files.write(out, in.readAllBytes());
        }
        System.out.printf("warm-up fixture written: %s (%d bytes)%n", out, Files.size(out));
    }
}
