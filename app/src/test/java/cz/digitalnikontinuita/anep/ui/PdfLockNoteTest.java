package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary.PdfLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Věta o zámku, který podpis položil na formulářová pole, případně na celý dokument.
 *
 * <p>Tvary zámku, které se tu zkoušejí, nejsou vymyšlené: jsou to všechny, jaké dalo bajtové síto
 * nad 2416 soubory v D:\tmp a D:\Downloads — zámek nese 23 z nich — a jejich čtení sondou nad
 * {@code PDFRevisionWrapper}. Zkouší se tady, a ne na sbírce, protože právě to síto ukázalo, že
 * některé kombinace jsou v ní jen jednou a jiné vůbec.
 *
 * <p>Nejdůležitější je první případ: <b>rozsah a oprávnění jsou nezávislé</b>. DocuSign vydává
 * {@code INCLUDE} nad jediným polem a k tomu {@code /P 3} nad celým dokumentem. Věta postavená
 * jen na oprávnění by zamlčela, že zamčené je jedno jediné pole; věta postavená jen na rozsahu by
 * zamlčela omezení dokumentu. První podoba tohoto kódu dělala obojí špatně — psala jen tu druhou
 * půlku a rozsah zahodila.
 */
class PdfLockNoteTest {

    @Test
    @DisplayName("Bez zámku není co psát")
    void noLockNoNote() {
        assertNull(ExtendedView.pdfLockNote(null, false));
    }

    @Test
    @DisplayName("Zámek s /P 1 mluví o dokumentu, ne o polích — „PAdES B-T kvalifikovnay zamknuty.pdf“")
    void allFieldsNoChangesPermitted() {
        String note = ExtendedView.pdfLockNote(
                new PdfLock("ALL", List.of(), "NO_CHANGE_PERMITTED"), false);
        assertEquals("Tímto podpisem je dokument uzamčen: nejsou v něm povoleny žádné"
                + " další změny.", note);
    }

    @Test
    @DisplayName("„Všechna pole“ vedle /P nepadne — v tom dokumentu je jediné, a to podepsané")
    void allScopeAddsNothingBesidePermission() {
        String note = ExtendedView.pdfLockNote(
                new PdfLock("ALL", List.of(), "NO_CHANGE_PERMITTED"), false);
        assertTrue(!note.contains("formulářov"), note);
    }

    @Test
    @DisplayName("U pečeti se mění jen podmět")
    void sealWording() {
        String note = ExtendedView.pdfLockNote(
                new PdfLock("ALL", List.of(), "NO_CHANGE_PERMITTED"), true);
        assertTrue(note.startsWith("Touto pečetí je dokument uzamčen"), note);
    }

    @Test
    @DisplayName("/P 2 a /P 3 nejsou „uzamčeno“ bez přívlastku — obojí zapovídá jen obsah")
    void looserPermissionsSayWhatTheyAre() {
        String p2 = ExtendedView.pdfLockNote(
                new PdfLock("ALL", List.of(), "MINIMAL_CHANGES_PERMITTED"), false);
        assertTrue(p2.contains("uzamčen proti změnám obsahu"), p2);
        assertTrue(p2.contains("připojování dalších podpisů"), p2);
    }

    @Test
    @DisplayName("DocuSign: jedno pole v INCLUDE a k tomu /P 3 — obě půlky musí zaznít")
    void singleFieldWithDocumentPermission() {
        String note = ExtendedView.pdfLockNote(
                new PdfLock("INCLUDE", List.of("ENVELOPEID_23AC7201"), "CHANGES_PERMITTED"), false);
        assertTrue(note.contains("uzamčen proti změnám obsahu"), note);
        assertTrue(note.contains("Uzamčeno je formulářové pole ENVELOPEID_23AC7201."), note);
    }

    @Test
    @DisplayName("Zámek vlastního pole bez /P o dokumentu nic netvrdí")
    void fieldOnlyLockSaysNothingAboutDocument() {
        String note = ExtendedView.pdfLockNote(
                new PdfLock("INCLUDE", List.of("workerMandatory1"), null), false);
        assertEquals("Tímto podpisem je uzamčeno formulářové pole workerMandatory1.", note);
        assertTrue(!note.contains("V dokumentu"), note);
    }

    @Test
    @DisplayName("Více polí dostane množné číslo")
    void severalFields() {
        String note = ExtendedView.pdfLockNote(
                new PdfLock("INCLUDE", List.of("Sig 1", "Sig 2"), null), false);
        assertTrue(note.contains("jsou uzamčena formulářová pole Sig 1, Sig 2"), note);
    }

    @Test
    @DisplayName("EXCLUDE vyjmenovává, co zamčené není")
    void excludeScope() {
        String note = ExtendedView.pdfLockNote(
                new PdfLock("EXCLUDE", List.of("Poznamka"), null), false);
        assertTrue(note.contains("kromě Poznamka"), note);
    }

    @Test
    @DisplayName("Neznámý rozsah se přizná, místo aby se dopověděl")
    void unknownScope() {
        String note = ExtendedView.pdfLockNote(new PdfLock(null, List.of(), null), false);
        assertTrue(note.contains("rozsah se nepodařilo zjistit"), note);
    }

    @Test
    @DisplayName("U prázdného pole se mluví v budoucím čase, a také o dokumentu")
    void plannedLockIsFutureTense() {
        String planned = EmptySignatureFieldBlock.plannedLock(
                new PdfLock("ALL", List.of(), "NO_CHANGE_PERMITTED"));
        assertEquals("celý dokument — po podepsání tohoto pole už v něm nebudou povoleny"
                + " žádné další změny", planned);
    }

    @Test
    @DisplayName("Prázdné pole s vyjmenovaným rozsahem i /P řekne obojí")
    void plannedLockNamesFieldsBesidePermission() {
        String planned = EmptySignatureFieldBlock.plannedLock(
                new PdfLock("INCLUDE", List.of("Podpis1"), "CHANGES_PERMITTED"));
        assertTrue(planned.startsWith("obsah celého dokumentu"), planned);
        assertTrue(planned.endsWith("uzamčeno bude formulářové pole Podpis1"), planned);
    }

    @Test
    @DisplayName("Prázdné pole bez /P mluví jen o polích")
    void plannedLockFieldsOnly() {
        String planned = EmptySignatureFieldBlock.plannedLock(
                new PdfLock("INCLUDE", List.of("Signature2"), null));
        assertEquals("formulářové pole Signature2", planned);
    }
}
