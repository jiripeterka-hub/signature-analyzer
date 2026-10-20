package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static cz.digitalnikontinuita.anep.ui.ExtendedView.counterSignatureNote;
import static cz.digitalnikontinuita.anep.ui.ExtendedView.counterSignaturePosition;
import static cz.digitalnikontinuita.anep.ui.SigFixture.sig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Označení protipodpisu v přehledech: „Jedná se o protipodpis (kontrasignaturu) k autentizačnímu
 * prvku č. N".
 *
 * <p>Číslo <b>nesmí</b> pocházet z pořadí v diagnostických datech DSS. Prvky se před vykreslením
 * přerovnávají a mezi podpisy se řadí samostatná časová razítka, takže „druhý podpis" a „druhý
 * autentizační prvek" nemusí být totéž — a čtenáře by věta poslala k cizímu prvku.
 */
class CounterSignatureNoteTest {

    @Test
    @DisplayName("Prvek, který protipodpisem není, žádnou poznámku nedostane")
    void notACounterSignature() {
        SignatureSummary s = sig().signatureId("A").build();
        assertNull(counterSignaturePosition(s, List.of(s)));
        assertNull(counterSignatureNote(s, List.of(s)));
    }

    @Test
    @DisplayName("Číslo je pozice rodiče v seznamu prvků")
    void positionOfParent() {
        SignatureSummary parent = sig().signatureId("A").build();
        SignatureSummary counter = sig().signatureId("B").counterSignatureOf("A").build();
        List<SignatureSummary> all = List.of(parent, counter);
        assertEquals(1, counterSignaturePosition(counter, all));
        assertEquals("Jedná se o protipodpis (kontrasignaturu) k autentizačnímu prvku č. 1.",
                counterSignatureNote(counter, all));
    }

    @Test
    @DisplayName("Samostatné časové razítko před rodičem číslo posouvá")
    void standaloneTimestampShiftsTheNumber() {
        // Přesně ten případ, kvůli kterému se číslo nepočítá z diagnostických dat: kontejnerové
        // razítko, které nekryje žádný podpis, se řadí před ně. Rodič je pak druhý autentizační
        // prvek, ačkoli v seznamu podpisů zůstává první.
        SignatureSummary stamp = sig().signatureId("TS").format("TimeStampToken").build();
        SignatureSummary parent = sig().signatureId("A").build();
        SignatureSummary counter = sig().signatureId("B").counterSignatureOf("A").build();
        List<SignatureSummary> all = List.of(stamp, parent, counter);
        assertEquals(2, counterSignaturePosition(counter, all));
        assertEquals("Jedná se o protipodpis (kontrasignaturu) k autentizačnímu prvku č. 2.",
                counterSignatureNote(counter, all));
    }

    @Test
    @DisplayName("Rodič, který v seznamu není, se raději nečísluje")
    void parentMissingFromTheList() {
        SignatureSummary counter = sig().signatureId("B").counterSignatureOf("NENÍ").build();
        List<SignatureSummary> all = List.of(counter);
        assertNull(counterSignaturePosition(counter, all));
        assertNull(counterSignatureNote(counter, all));
    }

    @Test
    @DisplayName("Bez seznamu prvků se číslo vzít nedá")
    void noList() {
        SignatureSummary counter = sig().signatureId("B").counterSignatureOf("A").build();
        assertNull(counterSignaturePosition(counter, null));
    }
}
