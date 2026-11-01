package cz.digitalnikontinuita.anep.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Údaje elektronického autorizačního razítka z předmětu certifikátu.
 *
 * <p>Tvar podle certifikátů ČKA a ČKAIT od PostSignum Qualified CA 4, tak jak DN předává DSS
 * (RFC 2253, title a organizationIdentifier jako DER v hexu). Jména a čísla jsou vymyšlená.
 */
class AuthorizationStampTest {

    /** Hodnota jako DER UTF8String v hexu — tak, jak ji DSS dává pro title a další OID. */
    private static String hex(String value) {
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(String.format("0c%02x", b.length));
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    private static String dn(String orgId, String org, String number, String title) {
        return "2.5.4.12=#" + hex(title)
                + ",2.5.4.5=#" + hex("P123456")
                + ",2.5.4.42=#" + hex("Jan")
                + ",2.5.4.4=#" + hex("Novák")
                + ",CN=Ing. arch. Jan Novák"
                + ",OU=" + number
                + ",OU=Elektronické autorizační razítko"
                + ",O=" + org
                + ",2.5.4.97=#" + hex(orgId)
                + ",C=CZ";
    }

    @Test
    @DisplayName("ČKA: jméno, pětimístné číslo, obory, komora")
    void architects() {
        AuthorizationStamp s = AuthorizationStamp.of(dn("NTRCZ-45769371",
                "ČESKÁ KOMORA ARCHITEKTŮ", "01234",
                "A: architektura (A1), UP: územní plánování (A2)"));
        assertEquals("Ing. arch. Jan Novák", s.name());
        assertEquals("01234", s.number());
        assertEquals("A: architektura (A1), UP: územní plánování (A2)", s.fields());
        assertEquals("ČESKÁ KOMORA ARCHITEKTŮ", s.chamber());
        assertEquals("45769371", s.chamberId());
        assertEquals(AuthorizationStamp.Chamber.CKA, s.knownChamber());
        assertTrue(s.numberFitsChamber());
        assertTrue(s.contentComplete());
    }

    @Test
    @DisplayName("ČKAIT: sedmimístné číslo; pětimístné by neodpovídalo")
    void engineers() {
        String org = "Česká komora autorizovaných inženýrů a techniků činných ve výstavbě";
        AuthorizationStamp s = AuthorizationStamp.of(dn("NTRCZ-45770743", org, "0001234",
                "ID00, IP00"));
        assertEquals(AuthorizationStamp.Chamber.CKAIT, s.knownChamber());
        assertTrue(s.numberFitsChamber());

        AuthorizationStamp shortNumber = AuthorizationStamp.of(dn("NTRCZ-45770743", org,
                "01234", "IP00"));
        assertFalse(shortNumber.numberFitsChamber());
    }

    @Test
    @DisplayName("bez OU „Elektronické autorizační razítko“ to razítko není")
    void notAStamp() {
        assertNull(AuthorizationStamp.of("CN=Jan Novák,OU=01234,O=Firma s.r.o.,C=CZ"));
        assertNull(AuthorizationStamp.of(null));
    }

    @Test
    @DisplayName("chybějící obor se pozná")
    void missingField() {
        AuthorizationStamp s = AuthorizationStamp.of(
                "CN=Jan Novák,OU=01234,OU=Elektronické autorizační razítko,"
                + "O=ČESKÁ KOMORA ARCHITEKTŮ,C=CZ");
        assertNull(s.fields());
        assertNull(s.knownChamber());
        assertNull(s.numberFitsChamber());
        assertFalse(s.contentComplete());
    }
}
