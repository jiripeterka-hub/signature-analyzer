package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Řádek „Posloupnost časových razítek" v Rozšířeném přehledu tvrdí o dokumentu, dokdy je krytý
 * a jestli v tom krytí není díra. Obojí je měřeno, ne psáno napevno.
 *
 * <p>Mezera vzniká tím, že další razítko přibylo až po konci krytí celé dosavadní části řady —
 * ne nutně po expiraci certifikátu razítka bezprostředně předchozího; pravidlo drží
 * {@code TimestampChain} a stejně ho počítá i situace 31 v ReportMapperu. Kdyby si ta dvě místa
 * protiřečila, četl by čtenář v jedné záložce „nepřerušená" a ve druhé, že řetěz má mezeru.
 * Rozhoduje výhradně řádná doba platnosti certifikátu; sunset služby v důvěryhodném seznamu do
 * toho nemluví, protože posloupnost má říct, dokud je dokument krytý, ne dokud je to ještě
 * omluvitelné.
 *
 * <p>Testuje se zde, a ne na sbírce: mezi 296 soubory v D:\tmp je přerušená posloupnost jediná
 * a se dvěma mezerami se nevyskytuje vůbec, takže sonda ty větve nikdy neproběhne.
 */
class TimestampSequenceTest {

    private static final Instant T0 = Instant.parse("2020-01-01T10:00:00Z");

    @Test
    @DisplayName("Razítko přidané před koncem platnosti předchozího řetěz neruší")
    void inTimeIsUnbroken() {
        List<TimestampEntry> chain = List.of(
                ts(0, 365),      // vzniklo v čase T0, certifikát autority platí rok
                ts(300, 365),    // přibylo 300. den, tedy včas
                ts(600, 365));   // a další 600. den, opět v platnosti toho předchozího

        assertTrue(ExtendedView.sequenceUnbroken(chain));
        assertTrue(ExtendedView.sequenceGaps(chain).isEmpty());
    }

    @Test
    @DisplayName("Razítko přidané až po expiraci předchozího je mezera, a to od-do")
    void lateStampBreaksTheChain() {
        List<TimestampEntry> chain = List.of(
                ts(0, 365),
                ts(400, 365));   // certifikát prvního razítka skončil 365. den

        assertFalse(ExtendedView.sequenceUnbroken(chain),
                "Razítko vzniklé po expiraci certifikátu předchozí autority nekryje, "
                + "co má krýt — řetěz tam má mezeru.");
        List<ExtendedView.SequenceGap> gaps = ExtendedView.sequenceGaps(chain);
        assertEquals(1, gaps.size());
        assertEquals(day(365), gaps.get(0).from(), "Mezera začíná koncem platnosti certifikátu.");
        assertEquals(day(400), gaps.get(0).to(), "Mezera končí vznikem dalšího razítka.");
    }

    @Test
    @DisplayName("Mezera i tehdy, když konec platnosti předchozího není znám")
    void unknownExpiryCountsAsGap() {
        List<TimestampEntry> chain = List.of(
                new TimestampEntry(T0, true, "TSA", null, "sig", true),
                ts(10, 365));

        assertFalse(ExtendedView.sequenceUnbroken(chain),
                "Bez konce platnosti nelze ukázat, že další razítko přibylo včas; "
                + "„nepřerušená\" by tvrdila zjištění, které se neudělalo.");
        assertTrue(ExtendedView.sequenceText(chain).contains("začátek nelze určit"),
                ExtendedView.sequenceText(chain));
    }

    @Test
    @DisplayName("Vypíšou se všechny mezery, ne jen ta první")
    void everyGapIsListed() {
        List<TimestampEntry> chain = List.of(
                ts(0, 100),      // certifikát skončil 100. den
                ts(200, 100),    // první mezera: 100 → 200; jeho certifikát končí 300. den
                ts(400, 365));   // druhá mezera: 300 → 400

        List<ExtendedView.SequenceGap> gaps = ExtendedView.sequenceGaps(chain);
        assertEquals(2, gaps.size(), "Druhá mezera se ztratila — dřív se cyklus po první zastavil.");
        assertEquals(day(100), gaps.get(0).from());
        assertEquals(day(200), gaps.get(0).to());
        assertEquals(day(300), gaps.get(1).from());
        assertEquals(day(400), gaps.get(1).to());

        String text = ExtendedView.sequenceText(chain);
        assertEquals(2, text.lines().filter(l -> l.startsWith("přerušení")).count(), text);
    }

    @Test
    @DisplayName("Konec posloupnosti je expirace certifikátu posledního razítka, ne jeho vznik")
    void endIsTheLastCertificateExpiry() {
        List<TimestampEntry> chain = List.of(ts(0, 365), ts(300, 365));

        String text = ExtendedView.sequenceText(chain);

        // Poslední razítko vzniklo 300. den a jeho certifikát platí do 665. dne. Uvádět jako
        // konec den vzniku znamenalo tvrdit, že krytí skončilo v okamžiku, kdy začalo.
        assertTrue(text.endsWith("a končí " + TimeUtil.format(day(665))), text);
    }

    @Test
    @DisplayName("Neznámá expirace posledního razítka nesmaže krytí, které už je doložené")
    void unknownExpiryDoesNotShortenTheReach() {
        List<TimestampEntry> chain = List.of(
                ts(0, 365),
                new TimestampEntry(day(300), true, "TSA", null, "doc", true));

        // Do 18. 9. 2026 tu stálo „a konec není znám": konec se bral z posledního razítka a to
        // svůj nemá. Jenže krytí do 365. dne je doložené tím prvním a neznalost u mladšího
        // razítka je nemaže.
        assertTrue(ExtendedView.sequenceText(chain).endsWith("a končí " + TimeUtil.format(day(365))),
                ExtendedView.sequenceText(chain));
    }

    @Test
    @DisplayName("Konec není znám tam, kde ho nemá odkud vzít žádné razítko")
    void unknownEndIsAdmitted() {
        List<TimestampEntry> chain = List.of(
                new TimestampEntry(T0, true, "TSA", null, "sig", true),
                new TimestampEntry(day(300), true, "TSA", null, "doc", true));

        assertTrue(ExtendedView.sequenceText(chain).endsWith("konec není znám"),
                ExtendedView.sequenceText(chain));
    }

    @Test
    @DisplayName("Mladší razítko s kratším certifikátem posloupnost nezkracuje")
    void youngerShorterStampDoesNotShortenTheChain() {
        // Tvar z „podpis s razitkem-extended-pades-baseline-lta.pdf": podpisové razítko na
        // certifikátu do 31. 12. 2028, dokumentové o tři měsíce později na certifikátu, který
        // skončil už 11. 11. 2025. Úschovna Czech POINT takový podpis 18. 9. 2026 uznala —
        // opřela se o to starší razítko. Konec posloupnosti je tedy ten pozdější z obou.
        List<TimestampEntry> chain = List.of(
                ts(0, 2000),
                ts(100, 300));

        assertTrue(ExtendedView.sequenceUnbroken(chain));
        assertTrue(ExtendedView.sequenceText(chain).endsWith("a končí " + TimeUtil.format(day(2000))),
                ExtendedView.sequenceText(chain));
    }

    @Test
    @DisplayName("Razítko přibylé za platnosti staršího článku mezeru netvoří")
    void reachOfAnOlderStampKeepsTheChainTogether() {
        // Prostřední razítko stojí na krátkém certifikátu a expiruje 150. den; to třetí přibude
        // 400. den, tedy dávno po něm — ale ještě za platnosti certifikátu toho prvního (2000.
        // den). Porovnávání sousedů tu hlásilo mezeru, ačkoli důkaz existence drží první článek.
        List<TimestampEntry> chain = List.of(
                ts(0, 2000),
                ts(100, 50),
                ts(400, 365));

        assertTrue(ExtendedView.sequenceGaps(chain).isEmpty(),
                ExtendedView.sequenceText(chain));
    }

    private static Instant day(int n) {
        return T0.plus(n, ChronoUnit.DAYS);
    }

    /** Razítko vzniklé {@code dayCreated}. den, jehož certifikát TSA platí {@code certDays} dnů. */
    private static TimestampEntry ts(int dayCreated, int certDays) {
        Instant created = day(dayCreated);
        return new TimestampEntry(created, true, "TSA", created.plus(certDays, ChronoUnit.DAYS),
                "sig", true);
    }
}
