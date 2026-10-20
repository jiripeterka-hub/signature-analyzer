package cz.digitalnikontinuita.anep.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Položky serialNumber v předmětu certifikátu, když jich je víc.
 *
 * <p>Tvar podle kvalifikovaného certifikátu I.CA z roku 2026: číslo pasu a vedle něj klientské
 * číslo vydavatele, v pořadí RFC 2253 klientské číslo první. Čísla jsou vymyšlená.
 */
class DnUtilSerialNumberTest {

    /** Hodnota jako DER PrintableString v hexu — tak, jak ji DSS dává pro OID 2.5.4.5. */
    private static String hex(String value) {
        StringBuilder sb = new StringBuilder(String.format("13%02x", value.length()));
        for (char c : value.toCharArray()) sb.append(String.format("%02x", (int) c));
        return sb.toString();
    }

    private static final String DN = "2.5.4.5=#" + hex("ICA - 1234567")
            + ",2.5.4.4=#" + hex("Novak")
            + ",C=CZ"
            + ",2.5.4.5=#" + hex("PASCZ-12345678")
            + ",CN=Jan Novak";

    @Test
    @DisplayName("doklad se najde, i když první položka serialNumber dokladem není")
    void documentBehindIssuerNumber() {
        assertEquals("číslo cestovního pasu 12345678", DnUtil.identityDocument(DN));
    }

    @Test
    @DisplayName("každá položka serialNumber má vlastní řádek se svou hodnotou")
    void everyEntryShown() {
        List<String[]> entries = DnUtil.serialNumberEntries(DN);
        assertEquals(2, entries.size());
        assertEquals("Identifikátor držitele (serialNumber):", entries.get(0)[0]);
        assertEquals("ICA - 1234567", entries.get(0)[1]);
        assertEquals("Číslo pasu:", entries.get(1)[0]);
        assertEquals("12345678", entries.get(1)[1]);
    }

    @Test
    @DisplayName("dva doklady se vyjmenují oba")
    void twoDocuments() {
        String dn = "SERIALNUMBER=IDCCZ-111222333,SERIALNUMBER=PASCZ-44556677,CN=Jan Novak";
        assertEquals("číslo občanského průkazu 111222333 a číslo cestovního pasu 44556677",
                DnUtil.identityDocument(dn));
    }

    @Test
    @DisplayName("bez dokladu žádný doklad")
    void noDocument() {
        assertNull(DnUtil.identityDocument("2.5.4.5=#" + hex("ICA - 1234567") + ",CN=Jan Novak"));
        assertEquals(0, DnUtil.serialNumberEntries("CN=Jan Novak").size());
    }
}
