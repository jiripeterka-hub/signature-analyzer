package cz.digitalnikontinuita.anep.core;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rozebrání výpisu zpět na popisek a hodnotu — {@code ContainerInspector.splitRows}.
 *
 * <p>Okno s certifikátem podle nich staví mřížku, aby se dlouhá hodnota zalomila místo aby
 * vybíhala z okna (nález uživatele 16. 9. 2026). Rozebírá tím ale formát, který o pár set řádků
 * výš skládá {@code line()}, a dvě takové poloviny se dřív nebo později rozejdou. Test je drží
 * pohromadě: certifikát si vyrobí, nechá ho popsat a rozebrat, a ptá se na hodnoty, které do něj
 * sám vložil.
 */
class ContainerTextRowTest {

    /** DN s čárkami a mezerami — tedy přesně to, co se v mřížce láme na víc řádků. */
    private static final String DN =
            "CN=Mgr. Jan Novák,O=Zkušební úřad České republiky,C=CZ";

    private static String describedSelfSigned() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair keys = gen.generateKeyPair();
        X500Name name = new X500Name(DN);
        long now = System.currentTimeMillis();
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate());
        var holder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(21096),
                new Date(now - 86_400_000L), new Date(now + 86_400_000L), name, keys.getPublic())
                .build(signer);
        return ContainerInspector.describeCertificate(holder.getEncoded());
    }

    private static ContainerInspector.TextRow row(List<ContainerInspector.TextRow> rows,
                                                   String label) {
        return rows.stream().filter(r -> label.equals(r.label())).findFirst().orElse(null);
    }

    @Test
    @DisplayName("Hodnota se vrátí celá, i když je v ní mezera")
    void valueSurvivesWhole() throws Exception {
        List<ContainerInspector.TextRow> rows =
                ContainerInspector.splitRows(describedSelfSigned());
        ContainerInspector.TextRow subject = row(rows, "Subjekt");
        assertNotNull(subject, "řádek Subjekt: " + rows);
        assertEquals(DN, subject.value());
    }

    @Test
    @DisplayName("Dvě mezery uvnitř hodnoty ji nerozdělí")
    void doubleSpaceInsideValueKeepsTheRow() throws Exception {
        // Vydavatel podepsaného sebou samým má za DN ještě „  (podepsán sám sebou)" — tedy druhé
        // dvojmezeří na řádku. Dělí se jen to první, které je výplní za popiskem.
        ContainerInspector.TextRow issuer =
                row(ContainerInspector.splitRows(describedSelfSigned()), "Vydavatel");
        assertNotNull(issuer);
        assertTrue(issuer.value().startsWith(DN), "hodnota: " + issuer.value());
        assertTrue(issuer.value().endsWith("(podepsán sám sebou)"), "hodnota: " + issuer.value());
    }

    @Test
    @DisplayName("Nadpis bloku ani prázdná řádka popisek nedostanou")
    void headersHaveNoLabel() {
        List<ContainerInspector.TextRow> rows = ContainerInspector.splitRows(
                "Základní informace\n"
                + "\n"
                + "  Záznam č. 1\n");
        assertEquals(3, rows.size());
        assertFalse(rows.get(0).hasLabel());
        assertEquals("Základní informace", rows.get(0).value());
        assertFalse(rows.get(1).hasLabel());
        assertTrue(rows.get(1).value().isBlank());
        assertFalse(rows.get(2).hasLabel(), "„Záznam č. 1“ je nadpis, ne dvojice: " + rows.get(2));
    }

    @Test
    @DisplayName("Odsazený řádek si odsazení nese zvlášť")
    void indentKeptApartFromTheLabel() throws Exception {
        // Prohlášení QCStatements se vypisují o dvě mezery dál; v mřížce to odsazení drží popisek,
        // takže se nesmí ztratit ani přilepit k němu.
        List<ContainerInspector.TextRow> rows = ContainerInspector.splitRows(
                "  QcCompliance                kvalifikovaný certifikát podle nařízení eIDAS\n");
        assertEquals(1, rows.size());
        assertEquals("  ", rows.get(0).indent());
        assertEquals("QcCompliance", rows.get(0).label());
        assertEquals("kvalifikovaný certifikát podle nařízení eIDAS", rows.get(0).value());
    }

    @Test
    @DisplayName("Závěrečná nová řádka nepřidá prázdný řádek navíc")
    void trailingNewlineDropped() {
        List<ContainerInspector.TextRow> rows =
                ContainerInspector.splitRows("Subjekt                     CN=Kdosi\n");
        assertEquals(1, rows.size());
        assertEquals("CN=Kdosi", rows.get(0).value());
    }

    @Test
    @DisplayName("Prázdný vstup nevydá žádný řádek")
    void emptyInput() {
        assertTrue(ContainerInspector.splitRows(null).isEmpty());
        assertTrue(ContainerInspector.splitRows("").isEmpty());
    }
}
