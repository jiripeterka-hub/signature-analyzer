package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dokdy je po ruce revokační údaj, který obstojí.
 *
 * <p>Dvě cesty vedou k témuž: <b>vložený</b> údaj je uznatelný do konce platnosti svého
 * vydavatele, <b>čerstvý</b> se dá stáhnout do konce platnosti certifikátu, o kterém vypovídá.
 * Rozhoduje ta, která vydrží déle — a právě proto se tu bere maximum, ačkoli všude jinde v této
 * třídě lhůty spadají na minimum. Sáhnout omylem po minimu je tichá chyba: lhůta vyjde dřív, než
 * je pravda, a čtenář nemá jak poznat, že ho aplikace zbytečně honí.
 *
 * <p>Změřeno na dvou skutečných dokumentech od PostSignum, kde vyšla obě pořadí:
 * u úředního dokumentu platí respondér do 23. 3. 2027 a podpisový certifikát
 * jen do 3. 12. 2026, takže rozhoduje vložený údaj; u „PAdES B-LT kvalifikovany PS neviditelny“
 * je to obráceně — respondér do 23. 3. 2027, certifikát do 18. 5. 2027 — a rozhoduje síť.
 */
class RevocationReachTest {

    private static final Instant RESPONDER = Instant.parse("2027-03-23T12:16:54Z");

    @Test
    @DisplayName("Delší podpisový certifikát: rozhoduje čerstvý údaj ze sítě")
    void networkWinsWhenTheCertificateOutlivesTheResponder() {
        Instant cert = Instant.parse("2027-05-18T12:48:22Z");
        assertTrue(ReportMapper.networkOutlastsEmbedded(cert, RESPONDER),
                "Dokud certifikát platí, dá se stáhnout čerstvý údaj podepsaný nástupcem"
                + " respondéra — lhůta tedy sahá dál než k expiraci respondéra.");
    }

    @Test
    @DisplayName("Kratší podpisový certifikát: rozhoduje vložený údaj")
    void embeddedWinsWhenTheResponderOutlivesTheCertificate() {
        Instant cert = Instant.parse("2026-12-03T07:14:24Z");
        assertFalse(ReportMapper.networkOutlastsEmbedded(cert, RESPONDER),
                "Po expiraci certifikátu už nebude čerstvý údaj kde vzít, ale vložený zůstává"
                + " uznatelný do expirace svého vydavatele.");
    }

    @Test
    @DisplayName("Shodná data: nerozhoduje síť, tedy ani žádné prodloužení")
    void tieDoesNotFavourTheNetwork() {
        assertFalse(ReportMapper.networkOutlastsEmbedded(RESPONDER, RESPONDER));
        assertFalse(ReportMapper.networkOutlastsEmbedded(
                RESPONDER.minus(1, ChronoUnit.DAYS), RESPONDER));
    }
}
