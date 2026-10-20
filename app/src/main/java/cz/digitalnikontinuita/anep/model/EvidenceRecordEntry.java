package cz.digitalnikontinuita.anep.model;

import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;

import java.time.Instant;
import java.util.List;

/**
 * One evidence record (RFC 4998 ERS or RFC 6283 XMLERS) as it applies to the document
 * being examined.
 *
 * <p>An evidence record does the same job as an archive time-stamp — it proves the covered
 * data already existed at a given moment — but it does it for a whole group of data objects
 * at once, and it is renewed on its own schedule rather than by augmenting the signature.
 * That difference is why it gets its own type here instead of being folded into
 * {@link TimestampEntry}: the reader needs to see what the record covers and whether every
 * covered object still matches, which a time-stamp entry has no room for.
 *
 * @param id                   DSS token id, for cross-referencing with the protocols
 * @param filename             name of the record's file, when it has one (null for records
 *                             embedded in a signature)
 * @param type                 Czech label naming the format and its RFC
 * @param origin               where the record was found — in a signature, in a container,
 *                             or supplied as a separate file
 * @param indication           DSS verdict for the record itself, already normalised to the
 *                             TOTAL_* forms the rest of the app expects
 * @param subIndication        detail of the verdict; null when the record passed
 * @param structureValid       false when the record deviates from its schema. Worth showing,
 *                             but on its own it says nothing about the hash tree — a record
 *                             carrying non-standard metadata still protects its data objects.
 * @param structureMessages    what the schema validation objected to
 * @param timestamps           the record's archive time-stamps, oldest first
 * @param coveredObjects       names of the data objects the record covers
 * @param digestMatchersTotal   how many covered objects the record accounts for
 * @param digestMatchersIntact  how many were supplied and still hash to the recorded value
 * @param digestMatchersMissing how many were never supplied for checking. A group record
 *                              covering five documents that is shown one of them reports four
 *                              here — which says nothing bad about the record. Kept apart from
 *                              the altered case on purpose: "you did not hand me that file"
 *                              and "that file has been tampered with" are opposite conclusions,
 *                              and DSS distinguishes them (ORPHAN_REFERENCE vs ARCHIVE_OBJECT).
 * @param earliestProof        production time of the record's earliest <em>valid</em> archive
 *                             time-stamp — the moment the covered data is proven to have
 *                             existed by. Null when no time-stamp in the record holds up,
 *                             in which case the record proves nothing no matter how well its
 *                             hash tree matches. Time-stamps that failed are still listed in
 *                             {@link #timestamps()} so the reader can see what was attempted.
 */
public record EvidenceRecordEntry(
        String id,
        String filename,
        String type,
        String origin,
        String indication,
        String subIndication,
        boolean structureValid,
        List<String> structureMessages,
        List<TimestampEntry> timestamps,
        List<String> coveredObjects,
        int digestMatchersTotal,
        int digestMatchersIntact,
        int digestMatchersMissing,
        Instant earliestProof) {

    public EvidenceRecordEntry {
        structureMessages = structureMessages != null ? List.copyOf(structureMessages) : List.of();
        timestamps        = timestamps        != null ? List.copyOf(timestamps)        : List.of();
        coveredObjects    = coveredObjects    != null ? List.copyOf(coveredObjects)    : List.of();
    }

    /** Covered objects that were supplied but no longer hash to the recorded value. */
    public int digestMatchersBroken() {
        return Math.max(0, digestMatchersTotal - digestMatchersIntact - digestMatchersMissing);
    }

    /** True when every covered data object was supplied and still matches its recorded digest. */
    public boolean allObjectsIntact() {
        return digestMatchersTotal > 0 && digestMatchersIntact == digestMatchersTotal;
    }

    /**
     * True when the record covers objects that were not supplied for checking — the normal
     * situation for a group record when only some of its documents are at hand.
     */
    public boolean partiallyChecked() {
        return digestMatchersMissing > 0 && digestMatchersBroken() == 0 && digestMatchersIntact > 0;
    }

    /**
     * True when this record can be relied on as proof of existence for the objects that were
     * actually checked: nothing supplied has been altered, at least one covered object matched,
     * and a valid time-stamp anchors the tree in time.
     * <p>
     * Objects that were never supplied deliberately do not count against the record. Each leaf
     * of the hash tree stands on its own — a group record shown one of its five documents still
     * proves that document existed, and demanding all five before saying so would make group
     * records look broken whenever they are used as intended. What is <em>not</em> claimed is
     * anything about the four absent documents; {@link #partiallyChecked()} exists so the UI
     * can say that out loud.
     * <p>
     * Schema deviations also do not disqualify a record. A record carrying non-standard metadata
     * alongside a correct hash tree still does its job, and reporting that separately keeps a
     * cosmetic complaint from reading as a failed proof.
     */
    public boolean providesProof() {
        return digestMatchersIntact > 0 && digestMatchersBroken() == 0 && earliestProof != null;
    }
}
