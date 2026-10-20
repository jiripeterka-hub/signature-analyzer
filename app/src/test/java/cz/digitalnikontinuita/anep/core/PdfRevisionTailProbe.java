package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.PDFRevisionWrapper;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.diagnostic.jaxb.XmlObjectModification;

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Co je v souboru za posledním kryjícím prvkem — a co do toho DSS řadí.
 *
 * <p>Měření, které podle {@code docs/nahled-pdf-navrh.md} má předcházet prvnímu řádku kódu
 * náhledu. Odpovídá na dvě otázky, na kterých návrh stojí a které se z dokumentace DSS vyčíst
 * nedají:
 *
 * <ol>
 *   <li><b>Co doopravdy patří do {@code getPdfExtensionChanges()}?</b> Na tom visí pravidlo
 *       o slévání verzí (oddíl 4.6 návrhu): prvek, po kterém přibylo jen „rozšíření dokumentu“,
 *       vlastní verzi netvoří. Kdyby do té kategorie spadlo něco obsahového, rozdíl by zmizel
 *       právě tam, kde na něm záleží.</li>
 *   <li><b>Jak často poslední kryjící prvek sahá na konec souboru?</b> Podle toho se pozná, kolik
 *       dokumentů vůbec má o čem mluvit — a jestli věta z oddílu 4.8 bude něco říkat, nebo jen
 *       u drtivé většiny opakovat „nic“.</li>
 * </ol>
 *
 * <p>Kryjícím prvkem je <b>podpis i dokumentové časové razítko</b>; obojí má vlastní
 * {@code /ByteRange} a bere se z {@code DiagnosticData}, nikdy hledáním v bajtech — viz past
 * s {@code /Perms /UR3} u {@code ReportMapper.signedByteRange()}.
 *
 * <pre>java -cp … PdfRevisionTailProbe &lt;složka|soubor&gt; [max]</pre>
 */
public final class PdfRevisionTailProbe {

    /** Jeden kryjící prvek: kam sahá a co po něm přibylo. */
    private record Covering(String label, long end, PDFRevisionWrapper rev) { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: PdfRevisionTailProbe <složka|soubor> [max]");
            return;
        }
        int max = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;
        List<File> files = new ArrayList<>();
        collect(new File(args[0]), files);

        AppSettings settings = AppSettings.load();
        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();

        int seen = 0, withCovering = 0, reachesEof = 0, hasTail = 0;
        int tailExtensionOnly = 0, tailWithContentChange = 0, tailNoCategories = 0;
        int eofWithOverlap = 0, eofWithVisual = 0, producedTail = 0;
        // Co všechno DSS do jednotlivých kategorií zařadilo — jádro otázky 1.
        Map<String, Integer> extensionKinds = new LinkedHashMap<>();
        Map<String, Integer> otherKinds = new LinkedHashMap<>();

        for (File f : files) {
            if (seen >= max) break;
            PdfSource src = PdfSource.of(f);
            if (!src.isPdf()) continue;
            seen++;
            long length = f.length();
            try {
                var outcome = new ValidatorFactory(settings)
                        .validate(new DocumentLoader().load(f), null, verifier, null);
                DiagnosticData dd = outcome.reports().getDiagnosticData();

                // Kontrola: vydá RevisionTail.of() totéž, co spočítá tahle sonda? Liší se
                // jedinou pojistkou — isSignatureByteRangeValid() — a v Detailním rozboru je
                // ta vlastnost popsaná jako „pokrývá celý dok.", což by znamenalo, že zahodí
                // právě ty dokumenty, o které jde.
                if (RevisionTail.of(dd, length) != null) producedTail++;

                List<Covering> covering = covering(dd);
                if (covering.isEmpty()) continue;
                withCovering++;

                Covering last = covering.get(covering.size() - 1);
                boolean eof = last.end() == length;
                if (eof) {
                    reachesEof++;
                    // Kontrolní otázka: hlásí DSS „překryv anotací" i tam, kde za posledním
                    // prvkem není ani bajt? Pak to není rozdíl mezi revizemi, nýbrž vlastnost
                    // jedné revize — a do testu „změnil se obsah" nepatří.
                    PDFRevisionWrapper r0 = last.rev();
                    if (!r0.getPdfAnnotationsOverlapConcernedPages().isEmpty()) {
                        eofWithOverlap++;
                        System.out.printf("EOF+PŘEKRYV  %-50s  strany %s%n", shorten(f.getName()),
                                pages(r0.getPdfAnnotationsOverlapConcernedPages()));
                    }
                    if (!r0.getPdfVisualDifferenceConcernedPages().isEmpty()) eofWithVisual++;
                } else {
                    hasTail++;
                    long tail = length - last.end();
                    PDFRevisionWrapper r = last.rev();
                    List<String> content = new ArrayList<>();
                    if (!r.getPdfVisualDifferenceConcernedPages().isEmpty()) {
                        content.add("vizuální " + pages(r.getPdfVisualDifferenceConcernedPages()));
                    }
                    if (!r.getPdfPageDifferenceConcernedPages().isEmpty()) {
                        content.add("stránky " + pages(r.getPdfPageDifferenceConcernedPages()));
                    }
                    // Překryv anotací do "content" NEPATŘÍ — změřeno: hlásí se i u prvků,
                    // za kterými v souboru není ani bajt (viz eofWithOverlap). Je to vlastnost
                    // jedné revize, ne rozdíl mezi dvěma. Vypisuje se zvlášť.
                    String overlap = r.getPdfAnnotationsOverlapConcernedPages().isEmpty() ? ""
                            : "  [překryv anotací " + pages(r.getPdfAnnotationsOverlapConcernedPages()) + "]";
                    boolean anyCategory = r.arePdfObjectModificationsDetected();
                    boolean onlyExtension = anyCategory
                            && !r.getPdfExtensionChanges().isEmpty()
                            && r.getPdfSignatureOrFormFillChanges().isEmpty()
                            && r.getPdfAnnotationChanges().isEmpty()
                            && r.getPdfUndefinedChanges().isEmpty();

                    if (!anyCategory) tailNoCategories++;
                    else if (onlyExtension && content.isEmpty()) tailExtensionOnly++;
                    else tailWithContentChange++;

                    tally(extensionKinds, r.getPdfExtensionChanges());
                    tally(otherKinds, r.getPdfSignatureOrFormFillChanges());
                    tally(otherKinds, r.getPdfAnnotationChanges());
                    tally(otherKinds, r.getPdfUndefinedChanges());

                    System.out.printf("%-58s  prvků=%d  za posledním %s B (%s)  %s%s%n",
                            shorten(f.getName()), covering.size(), tail, last.label(),
                            anyCategory ? categorySummary(r) : "žádné kategorie změn",
                            (content.isEmpty() ? "" : "  OBSAH: " + String.join(", ", content))
                                    + overlap);
                    System.out.flush();
                }
            } catch (Exception ex) {
                System.out.println("CHYBA  " + f.getName() + " : " + ex);
            }
        }

        System.out.println();
        System.out.println("=== SOUHRN ===");
        System.out.printf("PDF souborů: %d, z toho s kryjícím prvkem: %d%n", seen, withCovering);
        System.out.printf("  poslední prvek sahá na konec souboru: %d%n", reachesEof);
        System.out.printf("  za posledním prvkem ještě něco je:    %d%n", hasTail);
        System.out.printf("      jen rozšíření dokumentu:          %d%n", tailExtensionOnly);
        System.out.printf("      i změna obsahu:                   %d%n", tailWithContentChange);
        System.out.printf("      DSS neuvedl žádnou kategorii:     %d%n", tailNoCategories);
        System.out.println();
        System.out.println("Kontrola: prvky sahající na KONEC souboru (za nimi není ani bajt)");
        System.out.printf("  a přesto hlásí překryv anotací:  %d%n", eofWithOverlap);
        System.out.printf("  a přesto hlásí vizuální rozdíl:  %d%n", eofWithVisual);
        System.out.println();
        System.out.printf("RevisionTail.of() vrátil záznam u %d souborů (sonda napočítala %d)%n", producedTail, hasTail);
        System.out.println();
        System.out.println("Co DSS řadí do „rozšíření dokumentu“ (getPdfExtensionChanges):");
        dump(extensionKinds);
        System.out.println();
        System.out.println("Co řadí do ostatních tří kategorií:");
        dump(otherKinds);
    }

    /** Podpisy i dokumentová razítka, seřazené podle toho, kam sahají. */
    private static List<Covering> covering(DiagnosticData dd) {
        List<Covering> out = new ArrayList<>();
        for (SignatureWrapper sw : dd.getSignatures()) {
            long end = end(sw.getPDFRevision());
            if (end > 0) out.add(new Covering("podpis", end, sw.getPDFRevision()));
        }
        for (TimestampWrapper tw : dd.getTimestampList()) {
            PDFRevisionWrapper r = tw.getPDFRevision();
            long end = end(r);
            if (end > 0) out.add(new Covering("razítko " + tw.getType(), end, r));
        }
        out.sort(Comparator.comparingLong(Covering::end));
        return out;
    }

    /** Kam revize sahá: {@code b + c} z {@code /ByteRange}, nebo 0, když ho nemá. */
    private static long end(PDFRevisionWrapper rev) {
        if (rev == null) return 0;
        List<BigInteger> br = rev.getSignatureByteRange();
        if (br == null || br.size() != 4 || br.get(2) == null || br.get(3) == null) return 0;
        return br.get(2).longValue() + br.get(3).longValue();
    }

    private static String categorySummary(PDFRevisionWrapper r) {
        StringBuilder sb = new StringBuilder();
        append(sb, "rozšíření", r.getPdfExtensionChanges().size());
        append(sb, "formuláře", r.getPdfSignatureOrFormFillChanges().size());
        append(sb, "anotace", r.getPdfAnnotationChanges().size());
        append(sb, "nedefinované", r.getPdfUndefinedChanges().size());
        return sb.length() == 0 ? "bez změn" : sb.toString();
    }

    private static void append(StringBuilder sb, String name, int count) {
        if (count == 0) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(name).append('=').append(count);
    }

    private static void tally(Map<String, Integer> into, List<XmlObjectModification> mods) {
        for (XmlObjectModification m : mods) {
            String key = m.getAction() + " " + (m.getType() != null ? m.getType() : "?")
                    + (m.getFieldName() != null ? " /" + m.getFieldName() : "");
            into.merge(key, 1, Integer::sum);
        }
    }

    private static void dump(Map<String, Integer> kinds) {
        if (kinds.isEmpty()) { System.out.println("  (nic)"); return; }
        kinds.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(40)
                .forEach(e -> System.out.printf("  %5d×  %s%n", e.getValue(), e.getKey()));
    }

    private static String pages(List<BigInteger> pages) {
        StringBuilder sb = new StringBuilder();
        for (BigInteger p : pages) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p);
        }
        return sb.toString();
    }

    private static String shorten(String name) {
        return name.length() > 58 ? name.substring(0, 55) + "..." : name;
    }

    private static void collect(File root, List<File> out) {
        if (root.isFile()) { out.add(root); return; }
        File[] kids = root.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collect(k, out);
            else if (k.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) out.add(k);
        }
    }

    private PdfRevisionTailProbe() { }
}
