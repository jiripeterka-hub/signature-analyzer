package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.enumerations.EvidenceRecordTypeEnum;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.analyzer.evidencerecord.EvidenceRecordAnalyzer;
import eu.europa.esig.dss.spi.validation.analyzer.evidencerecord.EvidenceRecordAnalyzerFactory;

/**
 * Answers "are these bytes an evidence record?" by content, not by file extension.
 * <p>
 * Needed because the user hands AnEP two files without saying which is which: the same
 * drop zone that takes a detached signature also takes a detached evidence record, and a
 * {@code .xml} may be a XAdES signature, an XMLERS evidence record, or neither. DSS's own
 * {@code ServiceLoader} lookup already knows how to tell them apart, so this class only
 * wraps it and makes the "no" answer cheap and exception-free.
 * <p>
 * Both DSS evidence-record modules must be on the classpath for this to ever return true —
 * they register the factories this looks up. Without them every document is simply "not an
 * evidence record", which degrades to the pre-evidence-record behaviour rather than failing.
 */
public final class EvidenceRecordDetector {

    private EvidenceRecordDetector() { }

    /**
     * @return true when DSS recognises {@code doc} as an RFC 4998 or RFC 6283 evidence record.
     */
    public static boolean isEvidenceRecord(DSSDocument doc) {
        if (doc == null) return false;
        try {
            return EvidenceRecordAnalyzerFactory.isSupportedDocument(doc);
        } catch (Exception e) {
            // Malformed XML, a truncated file, an unrelated format — all mean "no" here.
            // Anything genuinely wrong with a real evidence record surfaces later, during
            // validation, where it can be reported to the user with context.
            return false;
        }
    }

    /**
     * @return the evidence record's format, or {@code null} when {@code doc} is not one
     *         (or is too damaged for DSS to say).
     */
    public static EvidenceRecordTypeEnum typeOf(DSSDocument doc) {
        if (doc == null) return null;
        try {
            EvidenceRecordAnalyzer analyzer = EvidenceRecordAnalyzerFactory.fromDocument(doc);
            return analyzer.getEvidenceRecordType();
        } catch (Exception e) {
            return null;
        }
    }

    /** Czech label naming the format and the RFC behind it, for display. */
    public static String describe(EvidenceRecordTypeEnum type) {
        if (type == null) return "evidenční záznam";
        return switch (type) {
            case XML_EVIDENCE_RECORD  -> "evidenční záznam XMLERS (RFC 6283)";
            case ASN1_EVIDENCE_RECORD -> "evidenční záznam ERS (RFC 4998)";
        };
    }
}
