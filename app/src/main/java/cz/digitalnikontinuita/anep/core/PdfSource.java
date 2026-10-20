package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * The PDF a validation run works on, as either bytes already in memory or a file on disk.
 *
 * <p>AnEP inspects every PDF several times over one validation — DSS parses it, and on top of
 * that come the appearance, orphan-signature and signing-tool passes. Each pass used to open
 * the file again, which is wasteful in general and expensive where an on-access virus scanner
 * inspects the file on every open. {@link DocumentLoader} already holds the bytes for anything
 * under its size limit, so the passes can share them; larger documents keep streaming from
 * disk, and this class is the one place that decides which of the two applies.
 */
public final class PdfSource {

    private final byte[] bytes; // null when the document stays on disk
    private final File file;    // null when the document is held in memory

    private PdfSource(byte[] bytes, File file) {
        this.bytes = bytes;
        this.file = file;
    }

    public static PdfSource of(byte[] pdfBytes) {
        return new PdfSource(pdfBytes, null);
    }

    public static PdfSource of(File pdfFile) {
        return new PdfSource(null, pdfFile);
    }

    /**
     * Prefers the bytes {@code doc} already holds, falling back to {@code file} for documents
     * {@link DocumentLoader} left on disk.
     */
    public static PdfSource of(File file, DSSDocument doc) {
        if (doc instanceof InMemoryDocument inMemory) {
            try (InputStream in = inMemory.openStream()) {
                return of(in.readAllBytes());
            } catch (IOException e) {
                // Fall through to the file — it is the same content either way.
            }
        }
        return of(file);
    }

    /** True when the content really is a PDF; the PDF passes skip everything else. */
    public boolean isPdf() {
        if (bytes != null) return startsWithPdfHeader(bytes);
        if (file == null || !file.isFile()) return false;
        try (InputStream in = new java.io.FileInputStream(file)) {
            return startsWithPdfHeader(in.readNBytes(5));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Nezpracované bajty dokumentu. Caller closes it.
     *
     * <p>Pro nástroje, které si PDF parsují samy a knihovnu PDFBox neznají — veraPDF má
     * vlastní parser a chce na vstup proud, ne {@link PDDocument}.
     */
    public java.io.InputStream openRaw() throws IOException {
        return bytes != null ? new java.io.ByteArrayInputStream(bytes)
                             : new java.io.FileInputStream(file);
    }

    /** Opens the document with PDFBox. Caller closes it. */
    public PDDocument open(char[] password) throws IOException {
        String pwd = (password != null && password.length > 0) ? new String(password) : "";
        return bytes != null ? Loader.loadPDF(bytes, pwd) : Loader.loadPDF(file, pwd);
    }

    /**
     * Potřebuje ten soubor heslo, aby se vůbec dal otevřít?
     *
     * <p><b>Není to táž otázka jako „je šifrovaný".</b> PDF zná dvě hesla: uživatelské otevírá
     * dokument, vlastnické omezuje, co se s ním smí dělat. Nejběžnější podoba ochrany — ta,
     * kterou Acrobat hlásí jako „Zabezpečení heslem" s zákazy tisku, úprav a podepisování — má
     * uživatelské heslo <b>prázdné</b>: soubor se otevře komukoli a heslo drží jen ta omezení.
     *
     * <p>AnEP se dosud ptal na přítomnost {@code /Encrypt} v traileru a nad každým takovým
     * souborem vyskočil dialogem, ještě než se ho pokusil otevřít. Kdo heslo nezná — a u téhle
     * ochrany ho nezná nikdo kromě autora — se nedozvěděl ani to, že dokument žádný podpis
     * nenese. Acrobat týž soubor otevře bez ptaní. Nález uživatele z 15. 9. 2026 nad třemi
     * zprávami z ISDS: všechny tři se otevřou
     * prázdným heslem, mají {@code canModify=false} a neobsahují jediný podpis.
     *
     * <p>Odpovídá se tedy pokusem, ne věštěním: tailový sken na {@code /Encrypt} zůstává jako
     * laciná předběžná odpověď (drčivá většina PDF šifrovaná není a nemá se kvůli ní nic
     * otvírat), a teprve když narazí, zkusí se soubor opravdu otevřít prázdným heslem.
     *
     * <p>Jiná než heslová chyba při otevírání <b>není</b> důvod ptát se na heslo: poškozený
     * soubor má ohlásit svůj vlastní problém, ne posílat uživatele shánět heslo, které mu
     * nepomůže.
     */
    public static boolean needsPasswordToOpen(File file) {
        if (file == null || !file.isFile()) return false;
        if (!of(file).isPdf()) return false;
        if (!hasEncryptEntry(file)) return false;
        try (PDDocument ignored = Loader.loadPDF(file, "")) {
            return false;
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException wrongPassword) {
            return true;
        } catch (Exception somethingElse) {
            return false;
        }
    }

    /**
     * Je v traileru {@code /Encrypt}? Laciná předběžná odpověď, ne závěr — čte posledních
     * ~4 kB souboru, kde trailer bývá.
     *
     * <p>Mine-li ho (linearizované PDF, mnoho inkrementálních revizí), nestane se nic zlého:
     * dialog se předem neukáže a na heslo se zeptá až {@code MainView}, když ověřování skončí
     * heslovou výjimkou. Týž postup měl kód i předtím.
     */
    private static boolean hasEncryptEntry(File file) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long len = raf.length();
            if (len < 5) return false;
            int scanLen = (int) Math.min(4096, len);
            raf.seek(len - scanLen);
            byte[] tail = new byte[scanLen];
            raf.readFully(tail);
            return new String(tail, java.nio.charset.StandardCharsets.ISO_8859_1)
                    .contains("/Encrypt");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean startsWithPdfHeader(byte[] head) {
        return head.length >= 5
                && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F'
                && head[4] == '-';
    }
}
