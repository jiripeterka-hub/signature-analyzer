package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.ReportMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Změněný obsah a neověřená podpisová hodnota jsou dvě různé vady.
 *
 * <p>Do 3. 9. 2026 spadaly obě do situace 1 a čtenář dostal u obou důvod 7: „Došlo ke změně
 * podepsaného obsahu (porušení jeho integrity)“. Nad „certifikacni a dva uznavane 2015 10 28
 * do 2026 10.pdf“ to byla nepravda — otisk podepsaných dat tam souhlasí (DSS sám hlásí
 * {@code MESSAGE_DIGEST dataIntact=true}) a selhává až ověření podpisové hodnoty, protože CMS
 * uvádí sha256WithRSAEncryption, ačkoli podpis vznikl nad SHA-1.
 *
 * <p>Detekci samotnou (S1 × S53) drží měření na dvou souborech: zmíněné PDF dává S53, tentýž
 * dokument s otočeným bajtem uvnitř ByteRange dává HASH_FAILURE a S1. Sem se vejde to, co jde
 * změřit bez DSS: že se obě situace nemíchají v důvodech a že ke každé je co přečíst.
 */
class IntegritySplitTest {

    private static List<Integer> reasonsFor(Integer... situations) throws Exception {
        Method detect = ReportMapper.class.getDeclaredMethod(
                "detectReasons", List.class, AppSettings.class);
        detect.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Integer> out = (List<Integer>) detect.invoke(null, List.of(situations),
                new AppSettings());
        return out;
    }

    @Test
    @DisplayName("Změněný obsah dává důvod 7, neověřená podpisová hodnota důvod 28")
    void theTwoDefectsGetTheirOwnReasons() throws Exception {
        assertTrue(reasonsFor(1).contains(7), "Situace 1 je o změně obsahu.");
        assertFalse(reasonsFor(1).contains(28));

        assertTrue(reasonsFor(53).contains(28), "Situace 53 je o podpisové hodnotě.");
        assertFalse(reasonsFor(53).contains(7),
                "Tvrdit u nezměněného dokumentu, že se změnil obsah, je nepravda.");
    }

    @Test
    @DisplayName("Důvod 28 umlčí ostatní stejně jako důvod 7")
    void theNewReasonSilencesTheRest() throws Exception {
        // Dokud se neověří samotný podpis, jsou úvahy o revokaci či algoritmu bezpředmětné —
        // a přesně to dělá důvod 7 od začátku. Měřeno přes situaci 15 (SHA-1), která nad tím
        // certifikačním podpisem opravdu svítí.
        assertTrue(reasonsFor(15).contains(15), "Bez fatálního důvodu SHA-1 na kartě stojí.");
        assertFalse(reasonsFor(53, 15).contains(15), "S fatálním důvodem už ne.");
        assertFalse(reasonsFor(1, 15).contains(15));
    }

    @Test
    @DisplayName("K nové situaci i k novému důvodu je co přečíst")
    void bothCataloguesKnowTheNewEntries() {
        assertNotNull(ResultView.SITUATION_LABELS.get(53),
                "Situace bez popisku se ve výpisu ukáže jako holé „Situace 53“.");
        assertNotNull(ResultView.REASON_WORKING_LABELS.get(28));
        ResultView.ReasonEntry r = ResultView.REASONS.get(28);
        assertNotNull(r, "Důvod bez záznamu v katalogu se v Analýze nevypíše vůbec.");
        assertTrue(r.problem().contains("nezměnil"),
                "První věta musí říct to hlavní: obsah je v pořádku.");
        assertNotNull(r.detail());
        assertNotNull(r.solution());

        assertTrue(ResultView.SITUATION_LABELS.get(1).contains("53"),
                "Obě situace se dají snadno zaměnit; popisky se na sebe musí odkazovat.");
        assertTrue(ResultView.SITUATION_LABELS.get(53).contains("situace 1"));
    }
}
