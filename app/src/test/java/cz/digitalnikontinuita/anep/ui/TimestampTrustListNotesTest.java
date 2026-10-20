package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Věty, které u razítka vysvětlují, co vypadá jako rozpor.
 *
 * <p>Otázka uživatele z 21. 9. 2026 nad {@code ZGHO-fin2016-1H-NotREV-K-HR-1.pdf}: razítko je
 * platné, ale nekvalifikované, ačkoli je vydala služba s kvalifikovaným statusem — a je platné
 * i po expiraci certifikátu té autority, který stojí na témž řádku. Obojí je správně a obojí
 * plyne z důvěryhodného seznamu; z karty se to ale vyčíst nedalo.
 *
 * <p>Změřeno na tom dokumentu: razítko z 8. 3. 2017, služba kvalifikovaná až od 16. 8. 2017,
 * certifikát TSU je sám kotvou důvěry a platil do 24. 10. 2022.
 */
class TimestampTrustListNotesTest {

    private static final Instant MADE = Instant.parse("2017-03-08T22:30:34Z");
    private static final Instant QUALIFIED_FROM = Instant.parse("2017-08-16T12:15:00Z");
    private static final Instant EXPIRED = Instant.now().minus(900, ChronoUnit.DAYS);
    private static final Instant STILL_VALID = Instant.now().plus(900, ChronoUnit.DAYS);

    private static TimestampEntry entry(boolean qualified, Instant notAfter,
                                        Instant qualifiedFrom, boolean anchorCert) {
        return new TimestampEntry(MADE, true, "I.CA TSS/TSU 6", notAfter, "vri_bare",
                qualified, true, null, null, false, qualifiedFrom, anchorCert);
    }

    @Test
    @DisplayName("Nekvalifikované kvůli pozdějšímu zápisu služby se vysvětlí datem")
    void theLaterQualifiedEntryIsExplained() {
        List<String> notes = ExtendedView.trustListNotes(
                entry(false, STILL_VALID, QUALIFIED_FROM, false), true);
        assertEquals(1, notes.size(), notes.toString());
        assertTrue(notes.get(0).contains("2017"), notes.get(0));
        assertTrue(notes.get(0).contains("kvalifikovaný status"), notes.get(0));
    }

    @Test
    @DisplayName("Platnost po expiraci se vysvětlí jen u kotvy důvěry")
    void theAnchorIsExplainedOnlyWhenItExplainsSomething() {
        List<String> anchorExpired = ExtendedView.trustListNotes(
                entry(true, EXPIRED, null, true), true);
        assertEquals(1, anchorExpired.size(), anchorExpired.toString());
        assertTrue(anchorExpired.get(0).contains("kotvou důvěry"), anchorExpired.get(0));

        // Certifikát, který ještě platí: není co vysvětlovat.
        assertEquals(List.of(), ExtendedView.trustListNotes(
                entry(true, STILL_VALID, null, true), true));
        // Certifikát po expiraci, ale kotvou není — platné takové razítko není a věta by lhala.
        assertEquals(List.of(), ExtendedView.trustListNotes(
                entry(true, EXPIRED, null, false), true));
        // Razítko, které neobstálo: vysvětlovat platnost nemá smysl.
        assertEquals(List.of(), ExtendedView.trustListNotes(
                entry(true, EXPIRED, null, true), false));
    }

    @Test
    @DisplayName("U kvalifikovaného razítka se o kvalifikovanosti nemluví")
    void aQualifiedTimestampNeedsNoExplanation() {
        assertEquals(List.of(), ExtendedView.trustListNotes(
                entry(true, STILL_VALID, QUALIFIED_FROM, false), true));
    }

    /**
     * Samostatné razítko je prvek, ne položka výčtu, takže ty údaje nese souhrn, ne
     * {@link TimestampEntry}. Otázka je ale táž a odpověď se skládá týmž pravidlem — na to je
     * tahle zkouška: aby se ty dvě cesty nerozešly.
     */
    @Test
    @DisplayName("Samostatné razítko dostane touž větu jako položka výčtu")
    void aStandaloneTimestampGetsTheSameSentence() {
        List<String> fromEntry = ExtendedView.trustListNotes(
                entry(false, EXPIRED, QUALIFIED_FROM, true), true);
        List<String> fromSummary = ExtendedView.trustListNotes(
                false, EXPIRED, QUALIFIED_FROM, true, true);
        assertEquals(fromEntry, fromSummary);
        assertEquals(2, fromSummary.size(), fromSummary.toString());
    }

    @Test
    @DisplayName("Obě věty naráz — přesně případ z toho dokumentu")
    void bothNotesTogether() {
        List<String> notes = ExtendedView.trustListNotes(
                entry(false, EXPIRED, QUALIFIED_FROM, true), true);
        assertEquals(2, notes.size(), notes.toString());
    }

    /**
     * Nález uživatele z 23. 9. 2026 nad „PAdES LTA 2016 08 06.pdf": dvě razítka s nepřerušeným
     * řetězem, druhé (dokumentové) vzniklo ještě před expirací certifikátu autority prvního
     * (podpisového). To druhé razítko je tedy pro to první důkazem existence uvnitř platnosti
     * jeho certifikátu — s kotvou důvěry to nemá co dělat, ačkoli dnes už ten certifikát dávno
     * expiroval. Věta o kotvě proto smí mluvit jen o platnosti k okamžiku, kdy razítko dostalo
     * svůj důkaz existence ({@code assessedAt}), ne o dnešku.
     */
    @Test
    @DisplayName("Nepřerušený řetěz razítek platnost dokládá dřív, než certifikát expiroval")
    void chainedTimestampProvenBeforeCertExpiredNeedsNoAnchorNote() {
        Instant certExpiry = Instant.parse("2022-01-15T13:19:58Z");
        Instant provenAt = Instant.parse("2022-01-09T18:04:09Z");

        List<String> notes = ExtendedView.trustListNotes(
                entry(false, certExpiry, null, true), true, provenAt);
        assertEquals(List.of(), notes, notes.toString());
    }

    /**
     * Totéž razítko, ale posuzované k dnešku (jako by nemělo pozdější razítko, které je kryje) —
     * tam věta o kotvě právem svítí, protože bez toho pozdějšího razítka je to opravdu jediný
     * důvod, proč razítko po expiraci certifikátu obstojí.
     */
    @Test
    @DisplayName("Bez pozdějšího razítka věta o kotvě u prošlého certifikátu zůstává")
    void withoutALaterTimestampTheAnchorNoteStillApplies() {
        Instant certExpiry = Instant.parse("2022-01-15T13:19:58Z");
        Instant now = Instant.parse("2026-09-23T00:00:00Z");

        List<String> notes = ExtendedView.trustListNotes(
                entry(false, certExpiry, null, true), true, now);
        assertEquals(1, notes.size(), notes.toString());
        assertTrue(notes.get(0).contains("kotvou důvěry"), notes.get(0));
    }
}
