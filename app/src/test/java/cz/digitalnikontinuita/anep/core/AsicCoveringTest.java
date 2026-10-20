package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.asic.xades.ASiCWithXAdESSignatureParameters;
import eu.europa.esig.dss.asic.xades.signature.ASiCWithXAdESService;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.enumerations.ASiCContainerType;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.SignatureValue;
import eu.europa.esig.dss.model.ToBeSigned;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.xades.signature.XAdESCounterSignatureParameters;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pokrytí prvku prvkem v kontejnerech ASiC — základ situace 54 mimo PDF.
 *
 * <p>V PDF se pokrytí <b>počítá</b> z rozsahů podepsaných bajtů, protože každý podpis je vlastní
 * přírůstkovou revizí. V kontejneru to neplatí: podpisy tam leží vedle sebe a každý podepisuje
 * tytéž datové objekty, ne jeden druhého. Jediné skutečné pokrytí uvnitř kontejneru je
 * <b>protipodpis</b>, a ten pokrytí nepočítá — deklaruje ho sám formát.
 *
 * <p>Přípravky se staví za běhu, klíčem na jedno použití. Není to z pohodlnosti: obě sbírky mají
 * 34 kontejnerů s podpisy a <b>ani jeden</b> nemá víc než jeden podpisový soubor (změřeno
 * 12. 9. 2026 sítkem nad ZIPy i přes DSS), takže co se neumí vyrobit, to se neumí ani změřit.
 *
 * <p>Časové razítko tu nikde není a schválně: testuje se rozhodnutí o pokrytí, kdežto požadavek
 * „krycí prvek musí nést uznané razítko" je společný s cestou pro PDF a měří se tam.
 */
class AsicCoveringTest {

    private static KeyPair kp;
    private static CertificateToken cert;

    @BeforeAll
    static void key() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        kp = kpg.generateKeyPair();
        X500Principal dn = new X500Principal("CN=AnEP test, O=AnEP, C=CZ");
        long now = System.currentTimeMillis();
        cert = new CertificateToken(new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(dn, BigInteger.ONE,
                        new Date(now - 86_400_000L), new Date(now + 3650L * 86_400_000L),
                        dn, kp.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256withRSA")
                                .build(kp.getPrivate()))));
    }

    @Test
    @DisplayName("Paralelní podpisy v kontejneru se navzájem nekryjí")
    void parallelContainerSignaturesCoverNothing() {
        // Tohle je běžný ASiC: dva podpisy nad týmž datovým objektem. Žádné pořadí mezi nimi
        // není a pozdější o dřívějším nic nedokládá — kdyby se pravidlo z PDF přeneslo naslepo,
        // tvrdilo by opak.
        List<SignatureWrapper> sigs = signatures(parallelSigned());
        assertEquals(2, sigs.size(), "Přípravek má nést dva podpisy.");
        assertFalse(ReportMapper.coversElement(sigs.get(1), sigs.get(0)),
                "Druhý podpis kryje tentýž dokument, ne ten první podpis.");
        assertFalse(ReportMapper.coversElement(sigs.get(0), sigs.get(1)),
                "A naopak už vůbec.");
    }

    @Test
    @DisplayName("Protipodpis kryje svého rodiče, a jen jeho")
    void aCounterSignatureCoversItsParent() {
        List<SignatureWrapper> sigs = signatures(counterSigned());
        assertEquals(2, sigs.size(), "Přípravek má nést podpis a protipodpis nad ním.");
        SignatureWrapper parent = sigs.stream().filter(s -> !s.isCounterSignature())
                .findFirst().orElseThrow();
        SignatureWrapper counter = sigs.stream().filter(SignatureWrapper::isCounterSignature)
                .findFirst().orElseThrow();

        assertTrue(ReportMapper.coversElement(counter, parent),
                "Protipodpis podepisuje podpisovou hodnotu rodiče, takže ho kryje.");
        assertFalse(ReportMapper.coversElement(parent, counter),
                "Rodič o protipodpisu nevěděl a krýt ho nemůže.");
        assertFalse(ReportMapper.coversElement(counter, counter),
                "Sám sebe nekryje nikdo.");
    }

    // ── stavba přípravků ────────────────────────────────────────────────────────────────

    private static DSSDocument parallelSigned() {
        return sign(List.of(sign(List.of(
                new InMemoryDocument("obsah".getBytes(), "obsah.txt")))));
    }

    private static DSSDocument counterSigned() {
        DSSDocument once = sign(List.of(new InMemoryDocument("obsah".getBytes(), "obsah.txt")));
        ASiCWithXAdESService service = new ASiCWithXAdESService(new CommonCertificateVerifier());
        XAdESCounterSignatureParameters p = new XAdESCounterSignatureParameters();
        p.setSigningCertificate(cert);
        p.setCertificateChain(cert);
        p.setSignatureLevel(SignatureLevel.XAdES_BASELINE_B);
        p.setDigestAlgorithm(DigestAlgorithm.SHA256);
        p.setSignatureIdToCounterSign(signatures(once).get(0).getId());
        return service.counterSignSignature(once, p, sign(service.getDataToBeCounterSigned(once, p)));
    }

    private static DSSDocument sign(List<DSSDocument> docs) {
        ASiCWithXAdESService service = new ASiCWithXAdESService(new CommonCertificateVerifier());
        ASiCWithXAdESSignatureParameters p = new ASiCWithXAdESSignatureParameters();
        p.setSigningCertificate(cert);
        p.setCertificateChain(cert);
        p.setSignatureLevel(SignatureLevel.XAdES_BASELINE_B);
        p.setDigestAlgorithm(DigestAlgorithm.SHA256);
        p.aSiC().setContainerType(ASiCContainerType.ASiC_E);
        return service.signDocument(docs, p, sign(service.getDataToSign(docs, p)));
    }

    private static SignatureValue sign(ToBeSigned tbs) {
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(kp.getPrivate());
            s.update(tbs.getBytes());
            return new SignatureValue(SignatureAlgorithm.RSA_SHA256, s.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Kontejner ověřovatelem bez důvěryhodných seznamů — zkoumá se struktura, ne verdikt. */
    private static List<SignatureWrapper> signatures(DSSDocument container) {
        DiagnosticData dd = new ValidatorFactory(new AppSettings())
                .validate(container, null, new CommonCertificateVerifier(), null)
                .reports().getDiagnosticData();
        return dd.getSignatures();
    }
}
