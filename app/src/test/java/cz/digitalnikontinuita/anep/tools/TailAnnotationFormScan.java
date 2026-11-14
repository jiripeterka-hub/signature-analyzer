package cz.digitalnikontinuita.anep.tools;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;

import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Co přibylo za posledním kryjícím prvkem — po anotacích a formulářových polích.
 *
 * <p>Napsáno 14. 11. 2026. DSS 6.5.1 vynechává z vizuálního porovnání revizí anotace přidané či
 * změněné po podpisu ({@code generateImageScreenshotWithoutAnnotations}), takže pruh přidaný jako
 * anotace Stamp nedá vizuální rozdíl a AnEP o něm mlčí v přehledu; objektové změny DSS hlásí jen
 * cestou k objektu, bez druhu anotace, strany a jména pole. Uživatel chce, aby vyplnění formuláře
 * nebo přidání anotace po podpisu nebylo prezentováno jako chyba, ale aby o něm aplikace vhodně
 * informovala. Sonda měří, jak často k tomu ve sbírkách dochází a kolik z toho je vidět.
 *
 * <p>Bez DSS, jen PDFBox: kryjící prvky jsou podpisové slovníky polí ({@code /Perms /UR3} mezi
 * nimi není — past popsaná u {@code ReportMapper.signedByteRange()}), konec revize je
 * {@code b + c} z {@code /ByteRange}. Porovnává se prefix souboru po konec poslední kryté revize
 * s celým souborem.
 *
 * <pre>
 *   java -cp "app/target/test-classes;app/target/classes;$(cat cp.txt)" \
 *        cz.digitalnikontinuita.anep.tools.TailAnnotationFormScan &lt;výstup.tsv&gt; &lt;složka|soubor&gt;...
 * </pre>
 *
 * <p>Výstup obsahuje cesty k souborům — patří do scratchpadu, ne do repozitáře.
 */
public final class TailAnnotationFormScan {

    /** Druhy anotací, které čtenář na stránce nevidí jako obsah. */
    private static final Set<String> INVISIBLE_KINDS = Set.of("Link", "Popup");

    public static void main(String[] args) throws Exception {
        List<Path> files = new ArrayList<>();
        int from = 1;
        if (args.length > 1 && "-prvni".equals(args[1])) {
            first = true;
            from = 2;
        }
        for (int i = from; i < args.length; i++) {
            try (Stream<Path> s = Files.walk(Path.of(args[i]))) {
                s.filter(p -> p.toString().toLowerCase().endsWith(".pdf") && Files.isRegularFile(p))
                        .forEach(files::add);
            }
        }
        Map<String, Integer> totals = new TreeMap<>();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[0]), StandardCharsets.UTF_8))) {
            out.println("soubor\tstav\tza_koncem_B\tanot_pridane_viditelne\tanot_pridane_skryte"
                    + "\tanot_zmenene_viditelne\tanot_zmenene_skryte\tanot_odebrane\tpole_vyplnena"
                    + "\tpole_nova\tpodpisova_pole_nova\tobsah_stranek\tpocet_stran\tdetail");
            int n = 0;
            for (Path p : files) {
                n++;
                if (n % 100 == 0) System.err.println("… " + n + "/" + files.size());
                Row r;
                try {
                    r = scan(p);
                } catch (Throwable t) {
                    r = new Row("chyba: " + t.getClass().getSimpleName());
                }
                out.println(p + "\t" + r.tsv());
                out.flush();
                count(totals, r);
            }
        }
        System.out.println("souborů: " + files.size());
        totals.forEach((k, v) -> System.out.println(String.format("%-50s %6d", k, v)));
    }

    private static void count(Map<String, Integer> t, Row r) {
        t.merge("stav " + (r.state.startsWith("chyba") ? "chyba" : r.state), 1, Integer::sum);
        if (!"za koncem".equals(r.state)) return;
        boolean visAnn = r.annAddedVis + r.annChangedVis > 0;
        boolean hidAnn = r.annAddedHid + r.annChangedHid + r.annRemoved > 0;
        boolean form = r.fieldsFilled + r.fieldsNew > 0;
        if (visAnn) t.merge("  viditelné anotace přidané/změněné", 1, Integer::sum);
        if (!visAnn && hidAnn) t.merge("  jen neviditelné anotační změny", 1, Integer::sum);
        if (form) t.merge("  vyplněná či nová formulářová pole", 1, Integer::sum);
        if (r.sigFieldsNew > 0) t.merge("  nová podpisová pole", 1, Integer::sum);
        if (r.content) t.merge("  změněný obsah stránek", 1, Integer::sum);
        if (r.pagesChanged) t.merge("  změněný počet stran", 1, Integer::sum);
        if ((visAnn || form) && !r.content && !r.pagesChanged) {
            t.merge("  => nová úroveň (pole/viditelné anotace, obsah beze změny)", 1, Integer::sum);
        }
        if (!visAnn && !hidAnn && !form && r.sigFieldsNew == 0 && !r.content && !r.pagesChanged) {
            t.merge("  => nic z toho (validační data apod.)", 1, Integer::sum);
        }
    }

    /**
     * {@code -prvni}: porovnávat od <b>prvního</b> kryjícího prvku, ne od posledního. Formulář se
     * v praxi vyplňuje mezi podpisy (jeden podepíše, druhý vyplní a podepíše) — pak za posledním
     * prvkem nic není, ale první podpis vyplněná pole nekryje.
     */
    private static boolean first;

    private static Row scan(Path p) throws Exception {
        byte[] all = Files.readAllBytes(p);
        long end = 0;
        long min = Long.MAX_VALUE;
        try (PDDocument doc = Loader.loadPDF(all)) {
            for (PDSignature s : doc.getSignatureDictionaries()) {
                int[] br = s.getByteRange();
                if (br == null || br.length != 4) continue;
                long e = (long) br[2] + br[3];
                if (e > end && e <= all.length) end = e;
                if (e > 0 && e < min && e <= all.length) min = e;
            }
            if (first && end > 0) end = min;
            if (end == 0) return new Row("bez kryjícího prvku");
            // Za %%EOF bývá jen konec řádku; to není přírůstek.
            if (all.length - end <= 2) return new Row("na konci");
            try (PDDocument signed = Loader.loadPDF(Arrays.copyOf(all, (int) end))) {
                Row r = new Row("za koncem");
                r.tail = all.length - end;
                compare(signed, doc, r);
                return r;
            }
        }
    }

    private static void compare(PDDocument signed, PDDocument now, Row r) throws Exception {
        int pages = Math.min(signed.getNumberOfPages(), now.getNumberOfPages());
        r.pagesChanged = signed.getNumberOfPages() != now.getNumberOfPages();
        List<String> detail = new ArrayList<>();
        for (int i = 0; i < pages; i++) {
            PDPage a = signed.getPage(i), b = now.getPage(i);
            if (!same(a.getCOSObject().getDictionaryObject(COSName.CONTENTS),
                    b.getCOSObject().getDictionaryObject(COSName.CONTENTS))) {
                r.content = true;
                detail.add("obsah s." + (i + 1));
            }
            Map<String, COSDictionary> before = annots(a), after = annots(b);
            for (var e : after.entrySet()) {
                COSDictionary old = before.get(e.getKey());
                String kind = kind(e.getValue());
                if ("Widget".equals(kind)) continue;          // pole se počítají níže
                boolean vis = visible(e.getValue(), kind);
                if (old == null) {
                    if (vis) r.annAddedVis++; else r.annAddedHid++;
                    detail.add("+" + kind + (vis ? "" : "(skrytá)") + " s." + (i + 1));
                } else if (!same(old, e.getValue())) {
                    if (vis) r.annChangedVis++; else r.annChangedHid++;
                    detail.add("~" + kind + (vis ? "" : "(skrytá)") + " s." + (i + 1));
                }
            }
            for (var e : before.entrySet()) {
                if (!after.containsKey(e.getKey()) && !"Widget".equals(kind(e.getValue()))) {
                    r.annRemoved++;
                    detail.add("-" + kind(e.getValue()) + " s." + (i + 1));
                }
            }
        }
        Map<String, PDField> fa = fields(signed), fb = fields(now);
        for (var e : fb.entrySet()) {
            PDField old = fa.get(e.getKey());
            boolean sig = e.getValue() instanceof PDSignatureField;
            if (old == null) {
                if (sig) {
                    // Podpisové pole za posledním prvkem je nevyplněné (jinak by krylo dál).
                    r.sigFieldsNew++;
                    detail.add("+podp.pole");
                } else {
                    r.fieldsNew++;
                    detail.add("+pole " + e.getValue().getFieldType());
                }
            } else if (!sig && !same(old.getCOSObject().getDictionaryObject(COSName.V),
                    e.getValue().getCOSObject().getDictionaryObject(COSName.V))) {
                r.fieldsFilled++;
                detail.add("vyplněno " + e.getValue().getFieldType());
            }
        }
        r.detail = String.join(", ", detail.subList(0, Math.min(12, detail.size())))
                + (detail.size() > 12 ? ", …" : "");
    }

    /**
     * Anotace stránky podle čísla objektu; přímé (nepřímo neodkazované) podle druhu a obdélníku.
     * Klíčem je číslo objektu, protože přírůstková aktualizace starý objekt přepíše pod týmž číslem.
     */
    private static Map<String, COSDictionary> annots(PDPage page) {
        Map<String, COSDictionary> m = new HashMap<>();
        COSBase arr = page.getCOSObject().getDictionaryObject(COSName.ANNOTS);
        if (!(arr instanceof COSArray a)) return m;
        for (int i = 0; i < a.size(); i++) {
            COSBase raw = a.get(i);
            COSBase o = raw instanceof COSObject co ? co.getObject() : raw;
            if (!(o instanceof COSDictionary d)) continue;
            String key = raw instanceof COSObject co
                    ? String.valueOf(co.getKey())
                    : "direct " + kind(d) + " " + d.getDictionaryObject(COSName.RECT);
            m.put(key, d);
        }
        return m;
    }

    private static Map<String, PDField> fields(PDDocument doc) {
        Map<String, PDField> m = new HashMap<>();
        PDAcroForm form = doc.getDocumentCatalog().getAcroForm(null);
        if (form == null) return m;
        for (PDField f : form.getFieldTree()) {
            if (f.getFieldType() == null) continue;   // nekoncové uzly stromu
            m.put(f.getFullyQualifiedName(), f);
        }
        return m;
    }

    private static String kind(COSDictionary d) {
        COSName s = d.getCOSName(COSName.SUBTYPE);
        return s == null ? "?" : s.getName();
    }

    /** Vidí ji čtenář? Ne odkaz/popup, ne skrytá (bit 2) ani noView (bit 6), s nenulovou plochou. */
    private static boolean visible(COSDictionary d, String kind) {
        if (INVISIBLE_KINDS.contains(kind)) return false;
        int f = d.getInt(COSName.F, 0);
        if ((f & 2) != 0 || (f & 32) != 0) return false;
        COSBase rect = d.getDictionaryObject(COSName.RECT);
        if (rect instanceof COSArray ra && ra.size() == 4) {
            float w = Math.abs(ra.toFloatArray()[2] - ra.toFloatArray()[0]);
            float h = Math.abs(ra.toFloatArray()[3] - ra.toFloatArray()[1]);
            if (w < 1 || h < 1) return false;
        }
        return true;
    }

    /**
     * Hluboké porovnání; nepřímé odkazy se rozvinou, zpětné odkazy (/P, /Parent, /Popup, /IRT)
     * se přeskočí, jinak by porovnání přes stránku obešlo celý dokument. Proudy podle surových bajtů.
     */
    private static boolean same(COSBase a, COSBase b) throws Exception {
        return same(a, b, new IdentityHashMap<>(), 0);
    }

    private static final Set<COSName> SKIP = Set.of(COSName.P, COSName.PARENT,
            COSName.getPDFName("Popup"), COSName.getPDFName("IRT"));

    private static boolean same(COSBase a, COSBase b, IdentityHashMap<COSBase, Boolean> seen, int depth)
            throws Exception {
        if (a instanceof COSObject oa) a = oa.getObject();
        if (b instanceof COSObject ob) b = ob.getObject();
        if (a == null || b == null) return a == b;
        if (depth > 12 || seen.put(a, Boolean.TRUE) != null) return true;
        if (a instanceof COSStream sa && b instanceof COSStream sb) {
            try (InputStream ia = sa.createRawInputStream(); InputStream ib = sb.createRawInputStream()) {
                if (!Arrays.equals(ia.readAllBytes(), ib.readAllBytes())) return false;
            }
        }
        if (a instanceof COSDictionary da && b instanceof COSDictionary db) {
            if (da.size() != db.size()) return false;
            for (COSName k : da.keySet()) {
                if (SKIP.contains(k) || COSName.LENGTH.equals(k)) continue;
                if (!db.containsKey(k)) return false;
                if (!same(da.getItem(k), db.getItem(k), seen, depth + 1)) return false;
            }
            return true;
        }
        if (a instanceof COSArray xa && b instanceof COSArray xb) {
            if (xa.size() != xb.size()) return false;
            for (int i = 0; i < xa.size(); i++) {
                if (!same(xa.get(i), xb.get(i), seen, depth + 1)) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    private static final class Row {
        final String state;
        long tail;
        int annAddedVis, annAddedHid, annChangedVis, annChangedHid, annRemoved;
        int fieldsFilled, fieldsNew, sigFieldsNew;
        boolean content, pagesChanged;
        String detail = "";

        Row(String state) { this.state = state; }

        String tsv() {
            return state + "\t" + tail + "\t" + annAddedVis + "\t" + annAddedHid + "\t"
                    + annChangedVis + "\t" + annChangedHid + "\t" + annRemoved + "\t"
                    + fieldsFilled + "\t" + fieldsNew + "\t" + sigFieldsNew + "\t"
                    + (content ? 1 : 0) + "\t" + (pagesChanged ? 1 : 0) + "\t" + detail;
        }
    }
}
