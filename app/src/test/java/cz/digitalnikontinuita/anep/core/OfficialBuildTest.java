package cz.digitalnikontinuita.anep.core;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rozpoznání oficiálního sestavení AnEP (1. 11. 2026). Skutečný certifikát autora je veřejný —
 * leží v každém podepsaném instalátoru; jmenovec a cizí vydavatel jsou vyrobené na místě.
 */
class OfficialBuildTest {

    private static final String VALID = "✓ platný podpis kódu — k času časového razítka 1. 11. 2026";

    private static X509CertificateHolder author;
    private static X509CertificateHolder namesake;
    private static X509CertificateHolder stranger;

    @BeforeAll
    static void setUp() throws Exception {
        try (InputStream in = OfficialBuildTest.class.getResourceAsStream("/authenticode/anep-autor-2026.cer")) {
            assertNotNull(in, "chybí certifikát autora v testovacích zdrojích");
            author = new X509CertificateHolder(in.readAllBytes());
        }
        namesake = cert("CN=Jiří Peterka,O=Jiří Peterka,L=Brno,C=CZ",
                "CN=Certum Code Signing 2021 CA,O=Asseco Data Systems S.A.,C=PL");
        stranger = cert("CN=Jiný vydavatel,O=Jiný vydavatel,C=CZ",
                "CN=Certum Code Signing 2021 CA,O=Asseco Data Systems S.A.,C=PL");
    }

    private static X509CertificateHolder cert(String subject, String issuer) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair k = g.generateKeyPair();
        return new JcaX509v3CertificateBuilder(new X500Name(issuer), BigInteger.TWO,
                new Date(System.currentTimeMillis() - 86_400_000L),
                new Date(System.currentTimeMillis() + 86_400_000L * 365), new X500Name(subject), k.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(k.getPrivate()));
    }

    @Test
    void authorCertificateIsKnownAndLooksLikeAuthor() {
        assertTrue(OfficialBuild.isKnown(author));
        assertTrue(OfficialBuild.looksLikeAuthor(author));
        assertFalse(OfficialBuild.isKnown(namesake));
        assertTrue(OfficialBuild.looksLikeAuthor(namesake));
        assertFalse(OfficialBuild.looksLikeAuthor(stranger));
    }

    @Test
    void knownCertificateFollowsVerdict() {
        // Na jménu souboru u známého certifikátu nezáleží — autor smí program pojmenovat jakkoli.
        assertTrue(OfficialBuild.assess(author, VALID, "cokoli.exe").startsWith("✓ oficiální sestavení"));
        assertTrue(OfficialBuild.assess(author, "✗ neplatný — soubor byl po podpisu změněn", "AnEP.exe")
                .startsWith("✗ nejde o neporušené"));
        assertTrue(OfficialBuild.assess(author, "? nerozhodnuto", "AnEP.exe").startsWith("? "));
    }

    @Test
    void namesakeIsOnlyAHintWithThumbprint() {
        String line = OfficialBuild.assess(namesake, VALID, "AnEP-1.2.0.msi");
        assertTrue(line.startsWith("? podepsal Jiří Peterka certifikátem, který tato verze AnEP nezná"), line);
        assertTrue(line.contains("SHA-1 "), line);
        assertTrue(line.contains("https://bajecnysvet.cz/anep/"), line);
    }

    @Test
    void foreignSignerOfAnepNamedFileIsRejected() {
        String line = OfficialBuild.assess(stranger, VALID, "AnEP-1.1.0.msi");
        assertTrue(line.startsWith("✗ nejde o oficiální sestavení AnEP"), line);
        assertTrue(line.contains("Jiný vydavatel"), line);
        assertTrue(OfficialBuild.assess(stranger, "✗ neplatný — podpis matematicky nesedí", "AnEP.exe")
                .startsWith("✗ "));
    }

    @Test
    void unrelatedProgramsGetNoLine() {
        assertNull(OfficialBuild.assess(stranger, VALID, "setup.exe"));
        assertNull(OfficialBuild.assess(namesake, "? nerozhodnuto", "setup.exe"));
        assertNull(OfficialBuild.assessUnsigned("setup.exe"));
        assertNull(OfficialBuild.assessUnsigned(null));
    }

    @Test
    void unsignedAnepIsRejected() {
        assertTrue(OfficialBuild.assessUnsigned("AnEP.exe").startsWith("✗ nejde o oficiální sestavení AnEP"));
    }

    @Test
    void fileNamesThatClaimAnep() {
        for (String n : new String[] {"AnEP.exe", "anep.msi", "AnEP-1.1.0.msi", "AnEP_1.1.0.exe",
                "AnEP 1.1.0.msi", "AnEP (1).msi", "C:\\Users\\x\\Downloads\\AnEP-1.1.0.msi",
                "slozka/AnEP.exe"}) {
            assertTrue(OfficialBuild.claimsAnep(n), n);
        }
        for (String n : new String[] {"AnEPx.exe", "nanep.exe", "AnEP.zip", "AnEP-1.1.0-windows-portable.zip",
                "MyAnEP.msi"}) {
            assertFalse(OfficialBuild.claimsAnep(n), n);
        }
    }
}
