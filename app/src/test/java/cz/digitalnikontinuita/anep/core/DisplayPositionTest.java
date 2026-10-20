package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static cz.digitalnikontinuita.anep.core.ReportMapper.displayPositions;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Číslo, kterým výklad situace 54 jmenuje krycí prvek, musí být totéž, které čtenář vidí v záhlaví
 * karty („Autentizační prvek č. N").
 *
 * <p>Do 13. 9. 2026 se bralo z pořadí v diagnostických datech DSS. Tam jsou ale jen podpisy, kdežto
 * na kartě se mezi ně řadí i samostatná časová razítka — kontejnerové razítko, které nekryje žádný
 * podpis, jde úplně první. Věta pak ukazovala na cizí prvek.
 *
 * <p>Přihrádky: podpis na indexu {@code i} → {@code i*2}, razítko kryjící {@code k} podpisů →
 * {@code k*2 − 1}.
 */
class DisplayPositionTest {

    private static Map<String, Integer> slots(Object... pairs) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("Samé podpisy: pořadí je pořadí")
    void signaturesOnly() {
        Map<String, Integer> pos = displayPositions(slots("A", 0, "B", 2, "C", 4));
        assertEquals(1, pos.get("A"));
        assertEquals(2, pos.get("B"));
        assertEquals(3, pos.get("C"));
    }

    @Test
    @DisplayName("Razítko před všemi podpisy posune celé číslování")
    void standaloneTimestampFirst() {
        // Kontejnerové razítko nekryje žádný podpis → přihrádka 0*2 − 1 = −1, tedy před nimi.
        Map<String, Integer> pos = displayPositions(slots("TS", -1, "A", 0, "B", 2));
        assertEquals(1, pos.get("TS"));
        assertEquals(2, pos.get("A"));
        assertEquals(3, pos.get("B"));
    }

    @Test
    @DisplayName("Razítko uprostřed posune jen prvky za sebou")
    void timestampBetweenSignatures() {
        // Razítko kryjící první podpis → 1*2 − 1 = 1, tedy mezi podpis č. 1 a č. 2.
        Map<String, Integer> pos = displayPositions(slots("A", 0, "TS", 1, "B", 2));
        assertEquals(1, pos.get("A"));
        assertEquals(2, pos.get("TS"));
        assertEquals(3, pos.get("B"));
    }

    @Test
    @DisplayName("Archivní razítko za vším čísla podpisů nemění")
    void archiveTimestampLast() {
        Map<String, Integer> pos = displayPositions(slots("A", 0, "B", 2, "TS", 3));
        assertEquals(1, pos.get("A"));
        assertEquals(2, pos.get("B"));
        assertEquals(3, pos.get("TS"));
    }

    @Test
    @DisplayName("Prázdný dokument nevydá žádné číslo")
    void empty() {
        assertEquals(Map.of(), displayPositions(Map.of()));
    }

    // ── Pořadí z konců revizí ────────────────────────────────────────────

    /**
     * Nález uživatele z 20. 9. 2026 nad „uznavana pecet.pdf“: dokumentové časové razítko stálo
     * jako prvek č. 1, ačkoli vzniklo o dvaadvacet minut po podpisu pod ním. DSS ten podpis
     * nevidí ({@code /SubFilter adbe.x509.rsa_sha1}), takže razítko krylo „nula podpisů“ a sedlo
     * si na −1, tedy před všechny. Rozsahy bajtů říkají pravdu: podpis končí na 59 598, razítko
     * na 93 013.
     */
    @Test
    @DisplayName("Razítko za osiřelým podpisem jde až za něj")
    void aTimestampAfterAnOrphanSignatureComesSecond() {
        Map<String, Integer> pos = displayPositions(ReportMapper.positionsFromEnds(
                ends("orphan:Signature1", 59_598L, "TS", 93_013L)));
        assertEquals(1, pos.get("orphan:Signature1"));
        assertEquals(2, pos.get("TS"));
    }

    @Test
    @DisplayName("A razítko před podpisem zůstává první")
    void aTimestampBeforeTheSignatureStaysFirst() {
        Map<String, Integer> pos = displayPositions(ReportMapper.positionsFromEnds(
                ends("TS", 12_000L, "orphan:Signature1", 59_598L)));
        assertEquals(1, pos.get("TS"));
        assertEquals(2, pos.get("orphan:Signature1"));
    }

    /**
     * Dvě razítka za týmž podpisem se dosud řadila podle času, který o sobě tvrdí; s rozsahy
     * rozhoduje to, kde v souboru leží. Naměřeno na „priklad2_5 stamped_preserved_…“ — podpis
     * končí na 57 936, razítka na 89 476 a 166 176.
     */
    @Test
    @DisplayName("Dvě razítka za podpisem se řadí podle souboru, ne podle deklarovaného času")
    void twoTimestampsAreOrderedByTheFileNotByTheirClaim() {
        Map<String, Integer> pos = displayPositions(ReportMapper.positionsFromEnds(
                ends("orphan:Signature1", 57_936L, "TS-pozdější", 166_176L,
                        "TS-dřívější", 89_476L)));
        assertEquals(1, pos.get("orphan:Signature1"));
        assertEquals(2, pos.get("TS-dřívější"));
        assertEquals(3, pos.get("TS-pozdější"));
    }

    /**
     * V PDF shoda konců nenastává — každé podepsání přidá vlastní revizi. Rozhodnout ji ale musí
     * něco pevného, jinak by dva běhy nad týmž souborem daly dvě různá pořadí.
     */
    @Test
    @DisplayName("Shodné konce rozhodne jméno, ne náhoda")
    void equalEndsAreBrokenDeterministically() {
        Map<String, Integer> first = ReportMapper.positionsFromEnds(ends("B", 100L, "A", 100L));
        Map<String, Integer> again = ReportMapper.positionsFromEnds(ends("A", 100L, "B", 100L));
        assertEquals(first, again);
        assertEquals(0, first.get("A"));
        assertEquals(1, first.get("B"));
    }

    @Test
    @DisplayName("Konec revize je c + d z rozsahu bajtů")
    void theRevisionEndIsReadFromTheByteRange() {
        assertEquals(59_598L, ReportMapper.revisionEnd("0,189,711,58887"));
        assertEquals(93_013L, ReportMapper.revisionEnd("0,76245,92631,382"));
        assertEquals(0L, ReportMapper.revisionEnd(null), "Bez rozsahu se řadit nedá");
        assertEquals(0L, ReportMapper.revisionEnd("0,189,711"), "Neúplný rozsah taky ne");
        assertEquals(0L, ReportMapper.revisionEnd("a,b,c,d"), "A nečíselný už vůbec");
    }

    private static Map<String, Long> ends(Object... pairs) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], (Long) pairs[i + 1]);
        }
        return m;
    }
}
