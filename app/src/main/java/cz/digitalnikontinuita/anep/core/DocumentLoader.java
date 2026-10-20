package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.FileDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.validation.SignedDocumentValidator;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Wraps a file on disk as a DSS {@code DSSDocument}.
 * <p>
 * DSS does its own format detection later (see {@link ValidatorFactory}) based
 * on the file's actual content - it distinguishes PDF, ASiC (a structured
 * ZIP), CMS (CAdES), and XML (XAdES) by inspecting bytes, not by extension.
 * So this class deliberately does no classification of its own.
 * <p>
 * The bytes are read once up front rather than handed to DSS as a {@link FileDocument}.
 * DSS reopens the document many times over one validation — every candidate validator
 * factory sniffs it, then the PDF/CMS layers read it again — and with an on-access virus
 * scanner in the way each of those opens can cost real time (measured: seconds per open
 * for a file in a folder the scanner watches closely). Reading it once collapses that to
 * a single scan. Large files stay file-backed so memory use remains bounded.
 */
public class DocumentLoader {

    /** Above this size the file is left on disk — streaming beats holding it all in heap. */
    private static final long IN_MEMORY_LIMIT_BYTES = 64L * 1024 * 1024;

    private static final byte[] PDF_PREAMBLE = {'%', 'P', 'D', 'F', '-'};

    /**
     * How far into a file the {@code %PDF-} header is still looked for. The value is Adobe's:
     * readers accept a header anywhere within the first kilobyte and read the document from
     * there, which is why files like this open everywhere except here.
     */
    private static final int PREAMBLE_SEARCH_LIMIT = 1024;

    /**
     * A loaded document together with what had to be cut off the front of it to make DSS
     * recognise it — normally zero. See {@link #loadWithDetail}.
     */
    public record Loaded(DSSDocument document, int strayPrefixBytes) { }

    public DSSDocument load(File file) {
        return loadWithDetail(file).document();
    }

    /**
     * Loads {@code file} and reports whether anything precedes the document proper.
     *
     * <p>DSS picks the validator by the document's opening bytes, so a PDF whose {@code %PDF-}
     * header does not sit at offset zero is rejected outright as an unrecognised format — even
     * though every PDF reader opens it, because ISO 32000's requirement that the header be the
     * first line has always been relaxed in practice to "somewhere in the first kilobyte, and
     * offsets count from there". Such files exist in the wild: one report in the sample collection carries
     * a single stray {@code 0x0A} and validates perfectly once it is gone.
     *
     * <p>The cut is therefore made here, but only where nothing else can explain the document:
     * DSS is asked first, and only a document it refuses is trimmed. Without that guard the rule
     * would misfire on formats that legitimately carry a PDF a few dozen bytes in — an enveloping
     * CAdES (its DER header precedes the signed PDF) and a ZIP whose first entry is a stored PDF
     * both put {@code %PDF-} well inside the search window.
     *
     * <p>The stray bytes are outside every signature — no ByteRange can cover them once the
     * offsets are read from the header — so the caller is expected to say so rather than let the
     * trim pass unmentioned.
     */
    public Loaded loadWithDetail(File file) {
        byte[] bytes = null;
        try {
            if (file.length() <= IN_MEMORY_LIMIT_BYTES) {
                bytes = Files.readAllBytes(file.toPath());
            }
        } catch (Exception e) {
            // Unreadable as a whole (locked, vanished, out of memory) — let DSS try the file.
        }
        if (bytes == null) return new Loaded(new FileDocument(file), 0);

        int prefix = strayPdfPrefixLength(bytes);
        if (prefix > 0) {
            return new Loaded(new InMemoryDocument(
                    Arrays.copyOfRange(bytes, prefix, bytes.length), file.getName()), prefix);
        }
        return new Loaded(new InMemoryDocument(bytes, file.getName()), 0);
    }

    /**
     * The number of bytes preceding a PDF that DSS would otherwise refuse to read, or 0 when
     * there is nothing to cut — which is the answer for every well-formed file.
     */
    private static int strayPdfPrefixLength(byte[] bytes) {
        int offset = indexOfPreamble(bytes);
        if (offset <= 0) return 0;              // absent, or already where it belongs
        if (opensAsAnotherFormat(bytes)) return 0;
        if (isRecognisedByDss(bytes)) return 0; // some other format that happens to carry a PDF
        return offset;
    }

    /**
     * Whether the bytes before the header announce a format of their own, whatever DSS makes of
     * it. Asking DSS is not enough on its own: a plain ZIP that is not an ASiC container is a
     * format DSS declines, and one in the sample corpus holds a stored PDF as its first entry
     * sixty bytes in — cutting there would turn an archive AnEP correctly reports as unreadable
     * into a mangled PDF. The same holds for a DER structure (CMS that DSS could not parse) and
     * for XML.
     */
    private static boolean opensAsAnotherFormat(byte[] bytes) {
        if (bytes.length < 2) return false;
        if (bytes[0] == 'P' && bytes[1] == 'K') return true;   // ZIP family, ASiC included
        if (bytes[0] == 0x30) return true;                     // DER SEQUENCE — CMS and friends
        if (bytes[0] == '<') return true;                      // XML, and HTML claiming to be it
        return false;
    }

    /** Offset of the {@code %PDF-} header within the search window, or -1 when it is not there. */
    private static int indexOfPreamble(byte[] bytes) {
        int limit = Math.min(bytes.length - PDF_PREAMBLE.length, PREAMBLE_SEARCH_LIMIT);
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < PDF_PREAMBLE.length; j++) {
                if (bytes[i + j] != PDF_PREAMBLE[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /**
     * Whether DSS claims the bytes as a document it can validate. Only the format sniffing runs —
     * each candidate factory reads the opening bytes and says yes or no — so this costs nothing
     * near a validation, and it is reached only for the handful of files whose PDF header is out
     * of place to begin with.
     */
    private static boolean isRecognisedByDss(byte[] bytes) {
        try {
            SignedDocumentValidator.fromDocument(new InMemoryDocument(bytes));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
