package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nahlédnutí do podpisu v kancelářském dokumentu — co se z něj dá přečíst bez ověřování.
 *
 * <p>Vzorky se skládají tady, ne berou ze sbírky: sbírka není v repozitáři a test, který bez ní
 * mlčí, nehlídá nic. Skutečné soubory posloužily k tomu, aby tvar vzorku odpovídal realitě —
 * včetně toho, že {@code docProps} a {@code [Content_Types].xml} nekryje ani Microsoft Office.
 */
class OfficeSignaturePeekTest {

    @Test
    @DisplayName("Z podpisu v OOXML se přečte osoba, čas, algoritmy i pokrytí částí")
    void readsWhatTheOoxmlSignatureClaims(@TempDir Path dir) throws Exception {
        File docx = ooxml(dir, "podepsany.docx", signatureXml("sha1", "rsa-sha1"));

        OfficeSignaturePeek.Finding finding = OfficeSignaturePeek.peek(docx);
        assertNotNull(finding, "podepsaný dokument se nerozpoznal");
        assertEquals(OfficeSignaturePeek.Family.OOXML, finding.family());
        assertEquals(1, finding.signatures().size());

        OfficeSignaturePeek.Signature sig = finding.signatures().get(0);
        assertEquals(Instant.parse("2023-08-27T09:47:35Z"), sig.claimedTime());
        assertEquals("Microsoft Office", sig.provider());
        assertEquals("Souhlasím s obsahem", sig.purpose());
        assertEquals(2, sig.references(), "reference na části podpisu se nepočítají jako části");
        assertTrue(sig.coveredParts().contains("word/document.xml"),
                "z URI se má odříznout dotaz ?ContentType=…: " + sig.coveredParts());
        assertTrue(sig.usesSha1(), "SHA-1 je nález, který rozhodne sám o sobě");
    }

    @Test
    @DisplayName("Nekryté části se pojmenují — jsou v nich autor a datum úpravy")
    void namesTheUncoveredParts(@TempDir Path dir) throws Exception {
        File docx = ooxml(dir, "podepsany.docx", signatureXml("sha256", "rsa-sha256"));

        OfficeSignaturePeek.Finding finding = OfficeSignaturePeek.peek(docx);
        assertNotNull(finding);
        assertTrue(finding.uncoveredParts().contains("docProps/core.xml"),
                "právě tady je autor a datum úpravy: " + finding.uncoveredParts());
        assertFalse(finding.signatures().get(0).usesSha1());
    }

    @Test
    @DisplayName("Podpis v ODF se najde v META-INF, ne v _xmlsignatures")
    void findsTheOdfSignature(@TempDir Path dir) throws Exception {
        File odt = zip(dir.resolve("podepsany.odt").toFile(),
                entry("mimetype", "application/vnd.oasis.opendocument.text"),
                entry("content.xml", "<office/>"),
                entry("META-INF/documentsignatures.xml", signatureXml("sha256", "rsa-sha256")));

        OfficeSignaturePeek.Finding finding = OfficeSignaturePeek.peek(odt);
        assertNotNull(finding, "podepsaný ODT se nerozpoznal");
        assertEquals(OfficeSignaturePeek.Family.ODF, finding.family());
        assertEquals(1, finding.signatures().size());
    }

    @Test
    @DisplayName("Nepodepsaný dokument ani cizí formát nález nevydá")
    void staysSilentWithoutASignature(@TempDir Path dir) throws Exception {
        File plain = zip(dir.resolve("cisty.docx").toFile(),
                entry("word/document.xml", "<w:document/>"));
        assertNull(OfficeSignaturePeek.peek(plain), "nepodepsaný dokument nemá co hlásit");

        File pdf = dir.resolve("dokument.pdf").toFile();
        Files.write(pdf.toPath(), "%PDF-1.7".getBytes(StandardCharsets.UTF_8));
        assertNull(OfficeSignaturePeek.peek(pdf), "PDF sem nepatří, to se ověřuje normálně");
        assertFalse(OfficeSignaturePeek.isCandidate(pdf));
    }

    @Test
    @DisplayName("Úroveň se odečte ze struktury, ne z ověření")
    void readsTheStructuralLevel(@TempDir Path dir) throws Exception {
        assertEquals(OfficeSignaturePeek.Level.B,
                level(dir, "b.docx", signatureXml("sha256", "rsa-sha256", "")),
                "podepsané vlastnosti bez razítka jsou B");

        assertEquals(OfficeSignaturePeek.Level.T,
                level(dir, "t.docx", signatureXml("sha256", "rsa-sha256",
                        "<SignatureTimeStamp><EncapsulatedTimeStamp>xx</EncapsulatedTimeStamp>"
                        + "</SignatureTimeStamp>")),
                "razítko posouvá na T");

        assertEquals(OfficeSignaturePeek.Level.LT,
                level(dir, "lt.docx", signatureXml("sha256", "rsa-sha256",
                        "<SignatureTimeStamp><EncapsulatedTimeStamp>xx</EncapsulatedTimeStamp>"
                        + "</SignatureTimeStamp><CertificateValues/><RevocationValues/>")),
                "certifikáty i revokační údaje jsou LT");

        assertEquals(OfficeSignaturePeek.Level.T,
                level(dir, "certs-only.docx", signatureXml("sha256", "rsa-sha256",
                        "<SignatureTimeStamp><EncapsulatedTimeStamp>xx</EncapsulatedTimeStamp>"
                        + "</SignatureTimeStamp><CertificateValues/>")),
                "samotné certifikáty na LT nestačí — chybí čím doložit, že nebyly odvolány");

        assertEquals(OfficeSignaturePeek.Level.LTA,
                level(dir, "lta.docx", signatureXml("sha256", "rsa-sha256",
                        "<SignatureTimeStamp><EncapsulatedTimeStamp>xx</EncapsulatedTimeStamp>"
                        + "</SignatureTimeStamp><CertificateValues/><RevocationValues/>"
                        + "<ArchiveTimeStamp><EncapsulatedTimeStamp>xx</EncapsulatedTimeStamp>"
                        + "</ArchiveTimeStamp>")),
                "archivní razítko je LTA, i když se token nepodařilo rozebrat");
    }

    @Test
    @DisplayName("Bez vlastností XAdES je to holý XMLDSIG")
    void recognizesPlainXmldsig(@TempDir Path dir) throws Exception {
        String xml = """
            <Signature xmlns="http://www.w3.org/2000/09/xmldsig#">
              <SignedInfo>
                <SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"/>
                <Reference URI="/word/document.xml?ContentType=application/xml">
                  <DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"/>
                </Reference>
              </SignedInfo>
              <SignatureValue>AAAA</SignatureValue>
            </Signature>
            """;
        assertEquals(OfficeSignaturePeek.Level.XMLDSIG, level(dir, "plain.docx", xml));
    }

    @Test
    @DisplayName("Vložený seznam odvolaných se přečte i s vydavatelem a daty")
    void readsAnEmbeddedCrl(@TempDir Path dir) throws Exception {
        String crl = Base64.getEncoder().encodeToString(sampleCrl());
        File docx = ooxml(dir, "s-crl.docx", signatureXml("sha256", "rsa-sha256",
                "<CertificateValues/><RevocationValues><CRLValues>"
                + "<EncapsulatedCRLValue>" + crl + "</EncapsulatedCRLValue>"
                + "</CRLValues></RevocationValues>"));

        OfficeSignaturePeek.Signature sig = OfficeSignaturePeek.peek(docx).signatures().get(0);
        assertEquals(1, sig.revocations().size());
        OfficeSignaturePeek.Revocation r = sig.revocations().get(0);
        assertFalse(r.ocsp(), "je to CRL, ne OCSP");
        assertEquals("CRL", r.kindLabel());
        assertTrue(r.issuer().contains("Zkusmo"), "vydavatel se přečetl: " + r.issuer());
        assertNotNull(r.produced(), "thisUpdate se přečetl");
        assertNotNull(r.nextUpdate(), "nextUpdate se přečetl");
    }

    @Test
    @DisplayName("Nečitelný revokační údaj se nepočítá, ale prvek se nezatají")
    void doesNotCountUnreadableRevocations(@TempDir Path dir) throws Exception {
        File docx = ooxml(dir, "rozbite.docx", signatureXml("sha256", "rsa-sha256",
                "<CertificateValues/><RevocationValues><CRLValues>"
                + "<EncapsulatedCRLValue>tohle-neni-DER</EncapsulatedCRLValue>"
                + "</CRLValues></RevocationValues>"));

        OfficeSignaturePeek.Signature sig = OfficeSignaturePeek.peek(docx).signatures().get(0);
        assertTrue(sig.revocations().isEmpty(), "nečitelný údaj se nevydává za přečtený");
        assertTrue(sig.hasRevocationValues(), "že tam prvek je, se ale zamlčet nesmí");
    }

    @Test
    @DisplayName("Horizont určuje poslední razítko v čase, ne první v pořadí zápisu")
    void horizonComesFromTheLastTimestamp() {
        Instant older = Instant.parse("2019-03-15T09:26:41Z");
        Instant newer = Instant.parse("2020-11-29T16:48:52Z");
        OfficeSignaturePeek.Signature sig = new OfficeSignaturePeek.Signature(
                "sig1.xml", null, List.of(), null,
                List.of(new OfficeSignaturePeek.Timestamp(
                                OfficeSignaturePeek.Timestamp.Kind.SIGNATURE, older, null, true,
                                Instant.parse("2024-12-22T08:31:14Z"), null),
                        new OfficeSignaturePeek.Timestamp(
                                OfficeSignaturePeek.Timestamp.Kind.ARCHIVE, newer, null, true,
                                Instant.parse("2026-10-23T10:51:02Z"), null)),
                Set.of(), "rsa-sha256", 1, List.of(),
                OfficeSignaturePeek.Level.LTA, List.of(), true, false, null, null);

        assertEquals(OfficeSignaturePeek.Timestamp.Kind.ARCHIVE, sig.lastTimestamp().kind());
        assertEquals(Instant.parse("2026-10-23T10:51:02Z"), sig.lastTimestamp().certNotAfter());
    }

    // ── Vzorky ───────────────────────────────────────────────────────────────────────────────

    private static OfficeSignaturePeek.Level level(Path dir, String name, String xml)
            throws Exception {
        return OfficeSignaturePeek.peek(ooxml(dir, name, xml)).signatures().get(0).level();
    }

    /**
     * Skutečná CRL, ne otisk skutečné: vydavatel a data se čtou přes {@code CertificateFactory},
     * takže vzorek musí být platné DER. Podepisuje se klíčem vyrobeným na místě — obsah nikdo
     * neověřuje, jde o to, aby se dal rozebrat.
     */
    private static byte[] sampleCrl() throws Exception {
        var keys = java.security.KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        var pair = keys.generateKeyPair();
        var issuer = new org.bouncycastle.asn1.x500.X500Name("CN=Zkusmo CA,C=CZ");
        var now = new java.util.Date();
        var builder = new org.bouncycastle.cert.X509v2CRLBuilder(issuer, now);
        builder.setNextUpdate(new java.util.Date(now.getTime() + 86_400_000L));
        var signer = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
                .build(pair.getPrivate());
        return builder.build(signer).getEncoded();
    }


    /**
     * Tvar odpovídá skutečným souborům: dvě kryté části, dvě reference na vlastní části podpisu
     * (ty se jako části dokumentu nepočítají) a nekryté {@code docProps}.
     */
    private static String signatureXml(String digest, String signature) {
        return signatureXml(digest, signature, "");
    }

    /**
     * @param unsigned obsah {@code UnsignedSignatureProperties} — razítka, vložené certifikáty
     *                 a revokační údaje. Odtud se odečítá úroveň podpisu.
     */
    private static String signatureXml(String digest, String signature, String unsigned) {
        return """
            <Signature xmlns="http://www.w3.org/2000/09/xmldsig#">
              <SignedInfo>
                <SignatureMethod Algorithm="http://www.w3.org/2000/09/xmldsig#%s"/>
                <Reference URI="/word/document.xml?ContentType=application/xml">
                  <DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#%s"/>
                </Reference>
                <Reference URI="/word/styles.xml?ContentType=application/xml">
                  <DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#%s"/>
                </Reference>
                <Reference URI="#idPackageObject">
                  <DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#%s"/>
                </Reference>
              </SignedInfo>
              <SignatureValue>AAAA</SignatureValue>
              <Object>
                <SignatureProperties>
                  <SignatureProperty>
                    <SignatureInfoV1 xmlns="http://schemas.microsoft.com/office/2006/digsig">
                      <SignatureComments>Souhlasím s obsahem</SignatureComments>
                      <SignatureProviderId>Microsoft Office</SignatureProviderId>
                    </SignatureInfoV1>
                  </SignatureProperty>
                </SignatureProperties>
                <QualifyingProperties xmlns="http://uri.etsi.org/01903/v1.3.2#">
                  <SignedProperties>
                    <SignedSignatureProperties>
                      <SigningTime>2023-08-27T09:47:35Z</SigningTime>
                    </SignedSignatureProperties>
                  </SignedProperties>
                  <UnsignedProperties>
                    <UnsignedSignatureProperties>%s</UnsignedSignatureProperties>
                  </UnsignedProperties>
                </QualifyingProperties>
              </Object>
            </Signature>
            """.formatted(signature, digest, digest, digest, unsigned);
    }

    private static File ooxml(Path dir, String name, String signatureXml) throws Exception {
        return zip(dir.resolve(name).toFile(),
                entry("[Content_Types].xml", "<Types/>"),
                entry("word/document.xml", "<w:document/>"),
                entry("word/styles.xml", "<w:styles/>"),
                entry("docProps/core.xml", "<cp:coreProperties/>"),
                entry("docProps/app.xml", "<Properties/>"),
                entry("_xmlsignatures/sig1.xml", signatureXml));
    }

    private record Part(String name, String content) { }

    private static Part entry(String name, String content) { return new Part(name, content); }

    private static File zip(File target, Part... parts) throws Exception {
        try (OutputStream os = Files.newOutputStream(target.toPath());
             ZipOutputStream zip = new ZipOutputStream(os)) {
            for (Part p : parts) {
                zip.putNextEntry(new ZipEntry(p.name()));
                zip.write(p.content().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return target;
    }
}
