package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.enumerations.TimestampType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the DSS behaviour that keeps evidence-record time-stamps out of AnEP's deadline and
 * algorithm checks.
 *
 * <p>{@code ReportMapper} walks {@code DiagnosticData.getTimestampList()} in ten places to pick
 * up time-stamps that cover a signature without being linked to it — PAdES /DocTimeStamp, ASiC
 * container time-stamps. Every one of those walks is gated on
 * {@code isDocumentTimestamp() || isContainerTimestamp()}. Since DSS 6.x added
 * {@code EVIDENCE_RECORD_TIMESTAMP} to that same list, those gates are the only thing stopping
 * an evidence record's time-stamp from being treated as an archive time-stamp of the signature —
 * which would move the augmentation deadline (S31–S34) and the "verifiable until" date.
 *
 * <p>The gates hold only because the two predicates are identity checks rather than tests on
 * {@code TimestampType}'s internal {@code order} field, which is 3 for CONTAINER, DOCUMENT,
 * ARCHIVE and EVIDENCE_RECORD alike. That is an implementation detail of an upstream enum: if
 * DSS ever rewrites them as {@code 3 == order} — the form used by {@code isContentTimestamp()}
 * and {@code isSignatureTimestamp()} just above them — all ten call sites would start counting
 * evidence-record time-stamps silently, with no compile error and no test failure anywhere else.
 * This test is what turns that into a loud failure.
 */
class EvidenceRecordTimestampIsolationTest {

    @Test
    @DisplayName("Razítko z evidenčního záznamu neprojde filtrem pro dokumentová a kontejnerová razítka")
    void evidenceRecordTimestampIsNeitherDocumentNorContainer() {
        TimestampType er = TimestampType.EVIDENCE_RECORD_TIMESTAMP;

        assertFalse(er.isDocumentTimestamp(),
                "EVIDENCE_RECORD_TIMESTAMP se začal tvářit jako dokumentové razítko — "
                        + "ReportMapper by ho započítal do lhůt pro uchování (S31–S34).");
        assertFalse(er.isContainerTimestamp(),
                "EVIDENCE_RECORD_TIMESTAMP se začal tvářit jako kontejnerové razítko — "
                        + "ReportMapper by ho započítal do lhůt pro uchování (S31–S34).");
        assertTrue(er.isEvidenceRecordTimestamp(),
                "Chybí vlastní predikát, podle kterého lze razítka z evidenčních záznamů rozlišit.");
    }

    @Test
    @DisplayName("Predikáty pro ostatní razítka řádu 3 zůstávají navzájem výlučné")
    void orderThreeTypesRemainDistinguishable() {
        // The point of the asymmetry: all four share order 3, so anything derived from `order`
        // would lump them together. Asserting the other three keeps the test honest — it fails
        // if DSS loosens the predicates in either direction, not only for evidence records.
        assertTrue(TimestampType.DOCUMENT_TIMESTAMP.isDocumentTimestamp());
        assertFalse(TimestampType.DOCUMENT_TIMESTAMP.isContainerTimestamp());

        assertTrue(TimestampType.CONTAINER_TIMESTAMP.isContainerTimestamp());
        assertFalse(TimestampType.CONTAINER_TIMESTAMP.isDocumentTimestamp());

        assertTrue(TimestampType.ARCHIVE_TIMESTAMP.isArchivalTimestamp());
        assertFalse(TimestampType.ARCHIVE_TIMESTAMP.isDocumentTimestamp());
        assertFalse(TimestampType.ARCHIVE_TIMESTAMP.isContainerTimestamp());
    }
}
