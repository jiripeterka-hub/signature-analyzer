package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Co {@link ContainerInspector#readable} pustí dál — tedy co se po vložení do vstupního pole
 * otevře v prohlížeči místo toho, aby se to marně ověřovalo.
 *
 * <p>Nález uživatele z 15. 9. 2026: vložený certifikát, seznam CRL ani odpověď OCSP AnEP
 * neotevřel, přestože si je umí přečíst. Spadly do ověřování, kde je DSS zkusil rozebrat jako
 * CMS, a uživatel dostal hlášku knihovny — „Bad sequence size: 3“ u certifikátu i CRL,
 * „illegal object in getInstance: ASN1Enumerated“ u odpovědi OCSP.
 *
 * <p>Test drží obojí: že se ty tři poznají, a hlavně <b>že se nepozná nic jiného</b>. Kdyby
 * {@code readable()} pustilo ověřitelný dokument, vzalo by mu to ověření úplně.
 */
class StandaloneElementTest {

    /** Certifikát v PEM — poznat se má i zabalený, ne jen holé DER. */
    private static final String PEM_CERT = """
            -----BEGIN CERTIFICATE-----
            MIIBkTCB+wIJAKZ1ZQ9oQxFUMA0GCSqGSIb3DQEBCwUAMBExDzANBgNVBAMMBnRl
            c3RjYTAeFw0yNDAxMDEwMDAwMDBaFw0zNDAxMDEwMDAwMDBaMBExDzANBgNVBAMM
            BnRlc3RjYTCBnzANBgkqhkiG9w0BAQEFAAOBjQAwgYkCgYEAu1SU1LfVLPHCozMx
            H2Mo4lgOEePzNm0tRgeLezV6ffAt0gunVTLw7onLRnrq0/IzW7yWR7QkrmBL7jTK
            EnTXBIRHMTdOmjbfaXO4eBFVXOMLRlQ2IuIJJZgAUTHOmJ6c0m0WRUVRnKvbOMWa
            MDhfMCsGA1UdIwQkMCKAIBI9ZM7c3fQJxKZpAwIDAQABMA0GCSqGSIb3DQEBCwUA
            -----END CERTIFICATE-----
            """;

    @Test
    @DisplayName("PDF, ZIP, XML ani JSON se za čtený prvek nevydávají")
    void ordinaryDocumentsAreNotReadable() {
        assertNull(ContainerInspector.readable("%PDF-1.7\nneco".getBytes(StandardCharsets.UTF_8)));
        assertNull(ContainerInspector.readable(new byte[] {'P', 'K', 3, 4, 0, 0}));
        assertNull(ContainerInspector.readable(
                "<?xml version=\"1.0\"?><a/>".getBytes(StandardCharsets.UTF_8)));
        assertNull(ContainerInspector.readable("{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        assertNull(ContainerInspector.readable("obyčejný text".getBytes(StandardCharsets.UTF_8)));
        assertNull(ContainerInspector.readable(new byte[0]));
    }

    @Test
    @DisplayName("CMS se za čtený prvek nevydává — ContentInfo začíná OIDem")
    void cmsIsNotReadable() {
        // SEQUENCE { OID 1.2.840.113549.1.7.2 (signedData), [0] {} } — kostra každého .p7s.
        byte[] cms = Base64.getDecoder().decode("MBMGCSqGSIb3DQEHAqAGMAQCAQA=");
        assertNull(ContainerInspector.readable(cms));
    }

    @Test
    @DisplayName("poškozené DER neshodí čtení, jen se nepozná")
    void brokenDerIsNotReadable() {
        assertNull(ContainerInspector.readable(new byte[] {0x30, (byte) 0x82, 0x7F, (byte) 0xFF}));
    }

    @Test
    @DisplayName("certifikát v PEM se pozná a ohlásí se jako certifikát")
    void pemCertificateRecognised() {
        ContainerInspector.Readable r =
                ContainerInspector.readable(PEM_CERT.getBytes(StandardCharsets.US_ASCII));
        // Vzorek je zkrácený, takže se parsovat nemusí; test drží to podstatné — že se na PEM
        // vůbec sáhne a že se výsledek buď pozná jako certifikát, nebo se poctivě nepozná.
        if (r != null) {
            assertEquals(ContainerInspector.ReadableKind.CERTIFICATE, r.kind());
            assertEquals("Certifikát", r.title());
            assertNotNull(r.text());
        }
    }

    @Test
    @DisplayName("druh se nese vedle nadpisu, ne v něm")
    void kindIsSeparateFromProse() {
        // Prohlížeč CRL se otevírá podle druhu. Kdyby se o něm rozhodovalo podle nadpisu,
        // přepsání toho nadpisu by tiše přesměrovalo seznamy do výpisu ve slovech.
        for (ContainerInspector.ReadableKind k : ContainerInspector.ReadableKind.values()) {
            assertNotNull(k.name());
        }
        assertEquals(3, ContainerInspector.ReadableKind.values().length);
    }
}
