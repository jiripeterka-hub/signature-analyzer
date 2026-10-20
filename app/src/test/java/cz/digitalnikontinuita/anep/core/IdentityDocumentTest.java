package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Doklad totožnosti zapsaný v atributu serialNumber předmětu certifikátu.
 *
 * <p>Pravidlo sedí v {@code core}, protože se na ně ptají dvě místa v různých balíčcích: výpis
 * podepsané osoby a blok o právních účincích přes {@code DnUtil}, a prohlížeč certifikátů přes
 * {@link ContainerInspector}. Test je tu proto, aby se ta jediná kopie nerozešla se skutečností.
 */
class IdentityDocumentTest {

    @Test
    @DisplayName("občanský průkaz podle schématu IDCCZ-")
    void idCard() {
        IdentityDocument.Document d = IdentityDocument.of("IDCCZ-123456789");
        assertNotNull(d);
        assertEquals("občanský průkaz", d.kind());
        assertEquals("číslo občanského průkazu", d.phrase());
        assertEquals("Číslo OP", d.shortLabel());
        assertEquals("123456789", d.number());
    }

    @Test
    @DisplayName("cestovní pas podle schématu PASCZ-")
    void passport() {
        IdentityDocument.Document d = IdentityDocument.of("PASCZ-12345678");
        assertNotNull(d);
        assertEquals("cestovní pas", d.kind());
        assertEquals("číslo cestovního pasu", d.phrase());
        assertEquals("Číslo pasu", d.shortLabel());
        assertEquals("12345678", d.number());
    }

    @Test
    @DisplayName("holá hodnota bez schématu dokladem není")
    void plainValueIsNotADocument() {
        // Přesně tak vypadá certifikát PostSignum pro zaměstnance: vlastní identifikátor držitele,
        // žádný odkaz na doklad. Vydávat ho za doklad by byl dohad podaný jako zjištění.
        assertNull(IdentityDocument.of("P397054"));
    }

    @Test
    @DisplayName("ostatní schémata z EN 319 412-1 se za doklad nevydávají")
    void otherSchemesAreNotDocuments() {
        // PNO je rodné číslo, TIN a TAX daňové identifikátory, IDCSK průkaz jiné země — všechno
        // identifikátory, ale ne takové, které by aplikace uměla popsat správně.
        assertNull(IdentityDocument.of("PNOCZ-8001011234"));
        assertNull(IdentityDocument.of("TINCZ-CZ12345678"));
        assertNull(IdentityDocument.of("TAXCZ-12345678"));
        assertNull(IdentityDocument.of("IDCSK-123456789"));
        assertNull(IdentityDocument.of("PASSK-12345678"));
    }

    @Test
    @DisplayName("prázdná a chybějící hodnota neshodí čtení")
    void emptyValues() {
        assertNull(IdentityDocument.of(null));
        assertNull(IdentityDocument.of(""));
        assertNull(IdentityDocument.of("   "));
        assertNull(IdentityDocument.of("IDCCZ-"));
    }
}
