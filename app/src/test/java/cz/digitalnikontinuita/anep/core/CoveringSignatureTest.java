package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kryje pozdější podpis ten dřívější? Na tom stojí situace 54 — odvozený důkaz existence.
 *
 * <p>Otázka zní „leží celý podpis Y uvnitř bajtů podepsaných podpisem X", ne „je X pozdější než
 * Y". Obě odpovědi vycházejí u PDF stejně, ale jen ta první je odpověď na správnou otázku, a jen
 * ona přežije scénář, kvůli kterému se to hlídá: prázdné podpisové pole, do kterého se podepíše
 * až po jiném podpisu. Pole vzniklo první, podpis do něj je ale poslední revizí souboru.
 *
 * <p>Všechny rozsahy níž jsou naměřené na skutečných souborech 12. 9. 2026, ne vymyšlené.
 */
class CoveringSignatureTest {

    /** „PAdES B-B +B-T kvalifikovany ICA.pdf" — podpis bez razítka a podpis s razítkem nad ním. */
    private static final long[] BEZ_RAZITKA = { 0, 62722, 93890, 85024 };     // konec 178 914
    private static final long[] S_RAZITKEM  = { 0, 183227, 210255, 4421 };    // konec 214 676

    /** „test na opacne poradi signed.pdf" — I.CA do nového pole, pak vyplněno původní prázdné. */
    private static final long[] ICA        = { 0, 309133, 353613, 61454 };    // konec 415 067
    private static final long[] POSTSIGNUM = { 0, 418984, 452542, 18083 };    // konec 470 625

    @Test
    @DisplayName("Pozdější podpis kryje ten dřívější, ne naopak")
    void theLaterSignatureCoversTheEarlierOne() {
        assertTrue(ReportMapper.coversSignedRange(S_RAZITKEM, BEZ_RAZITKA),
                "Podpis s razítkem kryje celý soubor včetně toho bez razítka.");
        assertFalse(ReportMapper.coversSignedRange(BEZ_RAZITKA, S_RAZITKEM),
                "Dřívější podpis o tom pozdějším nevěděl a krýt ho nemůže.");
    }

    @Test
    @DisplayName("Vyplněné prázdné pole je poslední revizí, i když vzniklo jako první")
    void aFilledEmptyFieldIsTheLastRevision() {
        // Pole „váš podpis" bylo v dokumentu od začátku, podepsalo se do něj ale až po I.CA.
        // Kdyby se pokrytí odvozovalo z pořadí polí, vyšlo by to obráceně.
        assertTrue(ReportMapper.coversSignedRange(POSTSIGNUM, ICA),
                "Podpis vložený do původně prázdného pole je poslední, takže kryje ten před ním.");
        assertFalse(ReportMapper.coversSignedRange(ICA, POSTSIGNUM),
                "A naopak ne — jinak by se starší podpis vydával za důkaz existence mladšího.");
    }

    @Test
    @DisplayName("Nesmyslné a neúplné rozsahy pokrytí nezakládají")
    void malformedRangesProveNothing() {
        assertFalse(ReportMapper.coversSignedRange(null, BEZ_RAZITKA));
        assertFalse(ReportMapper.coversSignedRange(S_RAZITKEM, null));
        assertFalse(ReportMapper.coversSignedRange(new long[] { 0, 10 }, BEZ_RAZITKA),
                "Rozsah, který nemá čtyři čísla, se nedá přečíst.");
        // Krycí podpis musí začínat na nule: podpis, který nekryje začátek souboru, nekryje ani
        // to, co v něm leží před ním.
        assertFalse(ReportMapper.coversSignedRange(new long[] { 100, 500000, 600000, 100 },
                BEZ_RAZITKA), "Pokrytí od nenulového začátku se za úplné brát nesmí.");
    }

    @Test
    @DisplayName("Podpis sám sebe nekryje")
    void aSignatureDoesNotCoverItself() {
        // Vlastní /Contents leží v díře mezi oběma částmi rozsahu, takže konec podpisu je vždy
        // za koncem jeho první kryté části.
        assertFalse(ReportMapper.coversSignedRange(BEZ_RAZITKA, BEZ_RAZITKA));
        assertFalse(ReportMapper.coversSignedRange(POSTSIGNUM, POSTSIGNUM));
    }
}
