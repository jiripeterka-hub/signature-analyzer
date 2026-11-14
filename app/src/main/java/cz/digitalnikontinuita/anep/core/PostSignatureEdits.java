package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Co se po posledním kryjícím prvku stalo s <b>formulářovými poli a anotacemi</b> — zjištěno
 * vlastním porovnáním podepsané podoby s dnešní, ne z DSS.
 *
 * <h2>Proč to nestačí vzít z DSS</h2>
 *
 * <p>Zjištěno 14. 11. 2026 na vzorku, do kterého se po podpisu přírůstkovou aktualizací přidal
 * červený pruh jako anotace Stamp. DSS 6.5.1 při vizuálním porovnání revizí vykresluje dnešní
 * podobu <b>bez anotací přidaných či změněných po podpisu</b> ({@code DefaultPdfDifferencesFinder}
 * volá {@code generateImageScreenshotWithoutAnnotations}), takže pruh, který čtenář na stránce
 * vidí, žádný vizuální rozdíl nedá. Totéž platí pro vyplněné pole formuláře — i jeho vzhled je
 * anotace (Widget). Objektové změny DSS hlásí jen cestou k objektu
 * ({@code CREATION type=Annot …/Annots 84 0 R}), bez druhu anotace, strany a jména pole. Adobe
 * Acrobat Reader nad týmž vzorkem vypíše „Anotace vytvořeny: anotace Stamp na stránce 1" —
 * a právě tohle čtenář potřebuje vědět.
 *
 * <h2>Co se tu nehodnotí</h2>
 *
 * <p>Vyplnění formuláře ani přidání anotace po podpisu <b>není chyba</b> (rozhodnutí uživatele
 * 14. 11. 2026): PDF to dovoluje, podpis to neporušuje a u formulářů podepsaných ještě prázdných
 * je to obvyklé. Tahle třída jen zjišťuje, co přibylo; jak o tom mluvit, rozhoduje UI.
 *
 * <p>Změřeno sondou {@code TailAnnotationFormScan} 14. 11. 2026 nad 2 346 PDF z {@code D:\tmp}
 * a {@code D:\Downloads}: za posledním kryjícím prvkem je něco u 209 souborů, viditelná anotace
 * z nich u jednoho, vyplněné pole u žádného; za prvním prvkem vyplněná pole u jednoho skutečného
 * formuláře. Změny jen u odkazů (Link) se objevily dvakrát — proto se neviditelné anotace počítají
 * zvlášť a nad kartami o nich řeč není.
 *
 * @param annotations změny anotací mimo pole formuláře (Widget), po stranách
 * @param fields      vyplněná či nově přidaná pole formuláře (bez podpisových)
 */
public record PostSignatureEdits(List<AnnotationEdit> annotations, List<FieldEdit> fields) {

    /** Co se s anotací stalo. */
    public enum Action { ADDED, CHANGED, REMOVED }

    /**
     * @param kind    podtyp anotace z PDF ({@code Stamp}, {@code FreeText}, …)
     * @param page    strana, číslováno od jedné
     * @param visible vidí ji čtenář na stránce? (ne odkaz/popup, ne skrytá, nenulová plocha)
     */
    public record AnnotationEdit(String kind, int page, Action action, boolean visible) { }

    /**
     * @param name  plně kvalifikované jméno pole
     * @param type  {@code Tx}, {@code Btn}, {@code Ch}
     * @param added pole v podepsané podobě vůbec nebylo
     * @param page  strana prvního vzhledu pole (od jedné), 0 když ho pole na stránce nemá
     */
    public record FieldEdit(String name, String type, boolean added, int page) { }

    /** Druhy anotací, které čtenář nevidí jako obsah stránky. */
    private static final Set<String> INVISIBLE_KINDS = Set.of("Link", "Popup");

    /** Zpětné odkazy, přes které by hluboké porovnání obešlo celý dokument. */
    private static final Set<COSName> SKIP = Set.of(COSName.P, COSName.PARENT,
            COSName.getPDFName("Popup"), COSName.getPDFName("IRT"), COSName.LENGTH);

    public PostSignatureEdits {
        annotations = List.copyOf(annotations);
        fields = List.copyOf(fields);
    }

    /** Anotace, které čtenář na stránce vidí. */
    public List<AnnotationEdit> visibleAnnotations() {
        return annotations.stream().filter(AnnotationEdit::visible).toList();
    }

    /** Změny anotací, které na stránce vidět nejsou (odkazy apod.). */
    public int hiddenAnnotationCount() {
        return (int) annotations.stream().filter(a -> !a.visible()).count();
    }

    /**
     * Je o čem mluvit nad kartami? Vyplněné pole, nebo viditelná anotace. Neviditelné změny ne —
     * čtenář je nevidí a v přehledu by byly šumem.
     */
    public boolean notable() {
        return !fields.isEmpty() || !visibleAnnotations().isEmpty();
    }

    /** Strany, na kterých je změna vidět — vzestupně. Pole bez strany se nepočítají. */
    public List<Integer> visiblePages() {
        TreeSet<Integer> pages = new TreeSet<>();
        for (AnnotationEdit a : annotations) if (a.visible()) pages.add(a.page());
        for (FieldEdit f : fields) if (f.page() > 0) pages.add(f.page());
        return List.copyOf(pages);
    }

    // ------------------------------------------------------------------ zjištění

    /**
     * Porovná podobu krytou posledním prvkem ({@code coveredEnd} prvních bajtů) s celým souborem.
     *
     * @return zjištění, nebo {@code null}, když soubor nejde přečíst (šifrovaný, poškozený) —
     *         pak se o tom mlčí, jako dosud
     */
    public static PostSignatureEdits read(File file, long coveredEnd) {
        try {
            byte[] all = Files.readAllBytes(file.toPath());
            if (coveredEnd <= 0 || coveredEnd >= all.length) return null;
            try (PDDocument now = Loader.loadPDF(all);
                 PDDocument signed = Loader.loadPDF(Arrays.copyOf(all, (int) coveredEnd))) {
                return compare(signed, now);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ mezi prvky

    /** Čím je prvek, který změnu kryje — kvůli textu na kartě. */
    public enum CoveredBy { PODPIS, RAZITKO }

    /**
     * Co se stalo <b>mezi</b> tímto prvkem a nejbližším dalším kryjícím prvkem.
     *
     * @param edits    vyplněná pole a anotace v tom úseku
     * @param nextId   identifikátor dalšího prvku z DiagnosticData — UI podle něj dohledá,
     *                 zda jde o podpis, či pečeť
     * @param nextName jméno z certifikátu dalšího prvku; {@code null}, když se nedá zjistit
     * @param nextKind podpis (či pečeť), nebo dokumentové časové razítko
     * @param nextSeal je další prvek pečeť? Doplňuje UI podle karet — DiagnosticData to
     *                 přímo neříká
     */
    public record Interim(PostSignatureEdits edits, String nextId, String nextName,
                          CoveredBy nextKind, boolean nextSeal) {

        public Interim withNextSeal(boolean seal) {
            return new Interim(edits, nextId, nextName, nextKind, seal);
        }
    }

    /**
     * Vyplnění a anotace mezi po sobě jdoucími kryjícími prvky — pro poznámku na kartě toho
     * staršího.
     *
     * <p>Přání uživatele 14. 11. 2026, vyvolané měřením: u skutečného formuláře ze sbírky
     * (prohlášení podepsané dvakrát, se zaškrtávacími poli) byla pole vyplněna <b>mezi</b>
     * podpisy. Za posledním prvkem pak nic není a blok nad kartami mlčí, ale první podpis
     * vyplněné hodnoty nekryje — kryje je až ten druhý. Úsek za posledním prvkem sem nepatří:
     * o něm mluví blok nad kartami ({@link RevisionTail#filledOrAnnotated()}) a dvakrát totéž
     * čtenář čte jako dvě různé věci.
     *
     * <p>Každý prefix se načítá jednou a porovnává se sousedním — dokument s pěti podpisy tak
     * stojí pět načtení, ne patnáct.
     *
     * @return podle identifikátoru staršího prvku jen úseky, ve kterých je o čem mluvit
     *         ({@link #notable()}); prázdná mapa, když soubor nejde přečíst
     */
    public static Map<String, Interim> interim(eu.europa.esig.dss.diagnostic.DiagnosticData dd,
                                               File file) {
        Map<String, Interim> out = new HashMap<>();
        if (dd == null || file == null) return out;
        record El(String id, long end, String name, CoveredBy kind) { }
        List<El> els = new ArrayList<>();
        for (var sw : dd.getSignatures()) {
            long end = RevisionTail.coveredUntil(sw.getPDFRevision());
            if (end > 0) els.add(new El(sw.getId(), end, name(sw.getSigningCertificate()),
                    CoveredBy.PODPIS));
        }
        for (var tw : dd.getTimestampList()) {
            long end = RevisionTail.coveredUntil(tw.getPDFRevision());
            if (end > 0) els.add(new El(tw.getId(), end, name(tw.getSigningCertificate()),
                    CoveredBy.RAZITKO));
        }
        if (els.size() < 2) return out;
        els.sort(java.util.Comparator.comparingLong(El::end));
        try {
            byte[] all = Files.readAllBytes(file.toPath());
            PDDocument prev = null;
            try {
                for (int i = 0; i < els.size() - 1; i++) {
                    El cur = els.get(i), next = els.get(i + 1);
                    if (next.end() > all.length) break;
                    if (prev == null) prev = Loader.loadPDF(Arrays.copyOf(all, (int) cur.end()));
                    if (next.end() == cur.end()) continue;
                    PDDocument nextDoc = Loader.loadPDF(Arrays.copyOf(all, (int) next.end()));
                    PostSignatureEdits e = compare(prev, nextDoc);
                    if (e.notable()) {
                        out.put(cur.id(), new Interim(e, next.id(), next.name(), next.kind(), false));
                    }
                    prev.close();
                    prev = nextDoc;
                }
            } finally {
                if (prev != null) prev.close();
            }
        } catch (Exception | OutOfMemoryError e) {
            return Map.of();
        }
        return out;
    }

    private static String name(eu.europa.esig.dss.diagnostic.CertificateWrapper c) {
        if (c == null) return null;
        String cn = c.getCommonName();
        if (cn != null && !cn.isBlank()) return cn;
        String org = c.getOrganizationName();
        return org != null && !org.isBlank() ? org : null;
    }

    static PostSignatureEdits compare(PDDocument signed, PDDocument now) throws Exception {
        List<AnnotationEdit> ann = new ArrayList<>();
        int pages = Math.min(signed.getNumberOfPages(), now.getNumberOfPages());
        Map<COSDictionary, Integer> widgetPage = new IdentityHashMap<>();
        for (int i = 0; i < pages; i++) {
            Map<String, COSDictionary> before = annots(signed.getPage(i), null, 0);
            Map<String, COSDictionary> after = annots(now.getPage(i), widgetPage, i + 1);
            for (var e : after.entrySet()) {
                String kind = kind(e.getValue());
                if ("Widget".equals(kind)) continue;          // pole se počítají níže
                COSDictionary old = before.get(e.getKey());
                if (old == null) {
                    ann.add(new AnnotationEdit(kind, i + 1, Action.ADDED, visible(e.getValue(), kind)));
                } else if (!same(old, e.getValue())) {
                    ann.add(new AnnotationEdit(kind, i + 1, Action.CHANGED, visible(e.getValue(), kind)));
                }
            }
            for (var e : before.entrySet()) {
                String kind = kind(e.getValue());
                if ("Widget".equals(kind) || after.containsKey(e.getKey())) continue;
                ann.add(new AnnotationEdit(kind, i + 1, Action.REMOVED, false));
            }
        }

        List<FieldEdit> fields = new ArrayList<>();
        Map<String, PDField> fa = fields(signed), fb = fields(now);
        for (var e : fb.entrySet()) {
            PDField f = e.getValue();
            // Podpisové pole za posledním prvkem je nevyplněné — o těch mluví blok o prázdných
            // podpisových polích, sem nepatří.
            if (f instanceof PDSignatureField) continue;
            PDField old = fa.get(e.getKey());
            boolean added = old == null;
            if (!added && same(old.getCOSObject().getDictionaryObject(COSName.V),
                    f.getCOSObject().getDictionaryObject(COSName.V))) continue;
            // Nové pole bez hodnoty není vyplnění.
            if (added && f.getCOSObject().getDictionaryObject(COSName.V) == null) continue;
            int page = 0;
            for (var w : f.getWidgets()) {
                Integer p = widgetPage.get(w.getCOSObject());
                if (p != null) {
                    page = p;
                    break;
                }
            }
            fields.add(new FieldEdit(e.getKey(), f.getFieldType(), added, page));
        }
        fields.sort(java.util.Comparator.comparingInt(FieldEdit::page)
                .thenComparing(FieldEdit::name));
        return new PostSignatureEdits(ann, fields);
    }

    /**
     * Anotace stránky podle čísla objektu — přírůstková aktualizace přepíše objekt pod týmž
     * číslem, takže změněná anotace se pozná podle obsahu, ne podle klíče. Přímé (nepřímo
     * neodkazované) podle druhu a obdélníku.
     */
    private static Map<String, COSDictionary> annots(PDPage page, Map<COSDictionary, Integer> pageOf,
                                                     int pageNo) {
        Map<String, COSDictionary> m = new LinkedHashMap<>();
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
            if (pageOf != null) pageOf.put(d, pageNo);
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
        if (d.getDictionaryObject(COSName.RECT) instanceof COSArray ra && ra.size() == 4) {
            float[] r = ra.toFloatArray();
            if (Math.abs(r[2] - r[0]) < 1 || Math.abs(r[3] - r[1]) < 1) return false;
        }
        return true;
    }

    /** Hluboké porovnání; nepřímé odkazy se rozvinou, proudy podle surových bajtů. */
    private static boolean same(COSBase a, COSBase b) throws Exception {
        return same(a, b, new IdentityHashMap<>(), 0);
    }

    private static boolean same(COSBase a, COSBase b, IdentityHashMap<COSBase, Boolean> seen,
                                int depth) throws Exception {
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
            for (COSName k : da.keySet()) {
                if (SKIP.contains(k)) continue;
                if (!db.containsKey(k) || !same(da.getItem(k), db.getItem(k), seen, depth + 1)) return false;
            }
            for (COSName k : db.keySet()) {
                if (!SKIP.contains(k) && !da.containsKey(k)) return false;
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
}
