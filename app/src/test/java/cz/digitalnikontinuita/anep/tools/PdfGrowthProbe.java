package cz.digitalnikontinuita.anep.tools;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows where a PDF's bytes actually are: page count, signatures, and every stream object grouped
 * by what it is.
 *
 * <p>Written when a document that had been preserved twice grew far more the second time than the
 * DSS dictionary could account for. Comparing the revisions of such a file by eye does not work —
 * an incremental update only appends, so the question is always which objects the last revision
 * added and how big they are.
 *
 * <pre>
 *   java -cp ... cz.digitalnikontinuita.anep.tools.PdfGrowthProbe &lt;file&gt; [&lt;file&gt;...]
 * </pre>
 */
public final class PdfGrowthProbe {

    public static void main(String[] args) throws Exception {
        for (String a : args) {
            File f = new File(a);
            System.out.println();
            System.out.println("=== " + f.getName() + "   " + Files.size(f.toPath()) + " B");
            try (PDDocument pdf = Loader.loadPDF(f)) {
                System.out.println("  stran: " + pdf.getNumberOfPages()
                        + ", revizí (%%EOF): " + countEof(f));

                List<PDSignature> sigs = pdf.getSignatureDictionaries();
                System.out.println("  podpisových slovníků: " + sigs.size());
                for (PDSignature s : sigs) {
                    byte[] c = s.getContents();
                    System.out.println("      " + s.getSubFilter()
                            + "  jméno=" + s.getName()
                            + "  /Contents=" + (c == null ? 0 : c.length) + " B");
                }

                // Every stream in the file, grouped by a label derived from its dictionary.
                Map<String, long[]> byKind = new LinkedHashMap<>();   // {počet, bajtů}
                List<String> biggest = new ArrayList<>();
                // The xref table is the live view: objects a superseded revision left behind are
                // not in it. That is exactly the distinction wanted here — if the live streams
                // add up to far less than the file, the growth is orphaned bytes, not content.
                long liveTotal = 0;
                for (var key : pdf.getDocument().getXrefTable().keySet()) {
                    COSObject obj = pdf.getDocument().getObjectFromPool(key);
                    if (obj == null) continue;
                    COSBase base = obj.getObject();
                    if (!(base instanceof COSStream st)) continue;
                    long len;
                    try (InputStream is = st.createInputStream()) {
                        len = is.readAllBytes().length;
                    } catch (Exception e) {
                        continue;
                    }
                    String kind = label(st);
                    long[] agg = byKind.computeIfAbsent(kind, k -> new long[2]);
                    agg[0]++; agg[1] += len;
                    liveTotal += len;
                    biggest.add(String.format("%10d B  %s  (obj %d)", len, kind, obj.getObjectNumber()));
                }

                System.out.printf("  živé proudy celkem: %,d B ze souboru %,d B%n",
                        liveTotal, Files.size(f.toPath()));
                System.out.println("  proudy podle druhu:");
                byKind.entrySet().stream()
                        .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[1]).reversed())
                        .forEach(e -> System.out.printf("      %-28s %3d ks %,12d B%n",
                                e.getKey(), e.getValue()[0], e.getValue()[1]));

                System.out.println("  největší jednotlivé proudy:");
                biggest.stream()
                        .sorted(Comparator.comparingLong(
                                (String s) -> Long.parseLong(s.trim().split(" ")[0])).reversed())
                        .limit(8)
                        .forEach(s -> System.out.println("      " + s));
            } catch (Exception e) {
                System.out.println("  SELHALO: " + e);
            }
        }
    }

    /** A short description of what a stream is, from its own dictionary. */
    private static String label(COSStream st) {
        COSBase t = st.getDictionaryObject(COSName.TYPE);
        COSBase sub = st.getDictionaryObject(COSName.SUBTYPE);
        String type = t instanceof COSName n ? n.getName() : null;
        String subtype = sub instanceof COSName n ? n.getName() : null;
        if (type != null && subtype != null) return type + "/" + subtype;
        if (type != null) return type;
        if (subtype != null) return subtype;
        if (st.getDictionaryObject(COSName.getPDFName("Filter")) != null
                && st.getDictionaryObject(COSName.getPDFName("Length1")) != null) {
            return "FontFile";
        }
        if (st.getDictionaryObject(COSName.getPDFName("Resources")) != null) return "obsah stránky?";
        return "bez /Type (obsah, CRL, OCSP…)";
    }

    private static int countEof(File f) throws Exception {
        byte[] all = Files.readAllBytes(f.toPath());
        String s = new String(all, java.nio.charset.StandardCharsets.ISO_8859_1);
        int n = 0, i = 0;
        while ((i = s.indexOf("%%EOF", i)) >= 0) { n++; i += 5; }
        return n;
    }
}
