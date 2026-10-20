package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.CmsStructure.Content;
import cz.digitalnikontinuita.anep.core.StructureNode.Kind;
import cz.digitalnikontinuita.anep.core.StructureNode.Severity;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.util.DateConverter;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Podpisová struktura PDF pro Inspektor: podpisy a pečetě, časová razítka dokumentu, slovník
 * {@code /DSS}, záznamy {@code /VRI} a revize dokumentu.
 *
 * <p><b>Revize se hledají v bajtech, ne v DSS.</b> Inspektor má ukázat i to, co DSS nevidí:
 * podobu {@code /DSS} v každé revizi zvlášť (uživatel o to požádal 13. 10. 2026 — „obojí“:
 * výslednou podobu s údajem, ve které revizi položka přibyla, i procházení po revizích). Revize
 * končí značkou {@code %%EOF}, které předchází {@code startxref}; každá taková předpona souboru je
 * sama úplným PDF, a tak se dá otevřít a přečíst, jak tehdy {@code /DSS} vypadal. U linearizovaného
 * dokumentu první {@code %%EOF} uzavírá jen tabulku první strany a revizí není.
 *
 * <p>{@code /ByteRange} se tu na rozdíl od {@link RevisionTail} čte ze slovníku podpisu — Inspektor
 * ukazuje, co v souboru stojí, a právě nesoulad rozsahu s revizí či s místem {@code /Contents}
 * je jedna z věcí, které v něm má odborník najít.
 */
final class PdfStructure {

    private PdfStructure() {}

    private static final COSName DSS = COSName.getPDFName("DSS");
    private static final COSName VRI = COSName.getPDFName("VRI");
    private static final COSName CERTS = COSName.getPDFName("Certs");
    private static final COSName CRLS = COSName.getPDFName("CRLs");
    private static final COSName OCSPS = COSName.getPDFName("OCSPs");
    private static final COSName CERT = COSName.getPDFName("Cert");
    private static final COSName CRL = COSName.getPDFName("CRL");
    private static final COSName OCSP = COSName.getPDFName("OCSP");
    private static final COSName TU = COSName.getPDFName("TU");
    private static final COSName TS = COSName.getPDFName("TS");

    /** Položka úložiště: klíč totožnosti, bajty a odkaz na objekt PDF. */
    private record Item(String key, byte[] der, String objRef) {}

    private record VriEntry(String name, List<Item> certs, List<Item> crls, List<Item> ocsps,
                            String tu, byte[] ts, String objRef) {}

    /** Podoba {@code /DSS} v jedné revizi; {@code null} v {@link #dss} = slovník chybí. */
    private record Snapshot(String dssRef, List<Item> certs, List<Item> crls, List<Item> ocsps,
                            Map<String, VriEntry> vri, boolean present) {}

    /** Co se o podpisu potřebuje vědět mimo jeho uzel — k párování VRI a k revizím. */
    /** {@code vriCandidates}: možný klíč VRI (SHA-1, velkými písmeny) → čeho je otiskem. */
    private record SigRef(StructureNode node, String key, String label, long end,
                          Map<String, String> vriCandidates) {}

    static StructureInspector.Inspection inspect(byte[] bytes, String fileName, char[] password)
            throws Exception {
        String pwd = password != null && password.length > 0 ? new String(password) : null;
        List<Long> ends = revisionEnds(bytes);
        List<StructureNode.Row> summary = new ArrayList<>();
        summary.add(new StructureNode.Row("Velikost", ContainerInspector.formatSize(bytes.length)
                + " (" + bytes.length + " B)", null));
        summary.add(new StructureNode.Row("Revizí", String.valueOf(ends.size()), null));

        // Podoba /DSS v každé revizi. Poslední revize se otevírá znovu jako celý soubor, ať se
        // čte totéž, co by četl kdokoli jiný, i když za posledním %%EOF něco přečnívá.
        List<Snapshot> snapshots = new ArrayList<>();
        Map<String, byte[]> canonical = new HashMap<>();
        for (int r = 0; r < ends.size(); r++) {
            long end = ends.get(r);
            byte[] prefix = end >= bytes.length ? bytes : Arrays.copyOf(bytes, (int) end);
            try (PDDocument doc = pwd != null ? Loader.loadPDF(prefix, pwd) : Loader.loadPDF(prefix)) {
                snapshots.add(snapshot(doc, canonical));
            } catch (Exception e) {
                snapshots.add(new Snapshot(null, List.of(), List.of(), List.of(), Map.of(), false));
            }
        }

        List<StructureNode> roots = new ArrayList<>();
        List<SigRef> sigRefs = new ArrayList<>();
        StructureNode sigGroup = new StructureNode(Kind.GROUP, "Podpisy a pečetě");
        StructureNode tsGroup = new StructureNode(Kind.GROUP, "Časová razítka dokumentu");
        try (PDDocument doc = pwd != null ? Loader.loadPDF(bytes, pwd) : Loader.loadPDF(bytes)) {
            COSDictionary catalog = doc.getDocumentCatalog().getCOSObject();
            COSDictionary perms = catalog.getCOSDictionary(COSName.PERMS);
            COSBase docMdp = perms != null ? perms.getDictionaryObject(COSName.DOCMDP) : null;
            int index = 0;
            List<PDSignatureField> fields = new ArrayList<>(doc.getSignatureFields());
            for (PDSignatureField field : fields) {
                PDSignature sig = field.getSignature();
                if (sig == null) {
                    sigGroup.add(emptyField(field, doc));
                    continue;
                }
                index++;
                StructureNode node = signature(field, sig, doc, bytes, ends, index, sigRefs,
                        docMdp != null && docMdp == sig.getCOSObject());
                boolean docTs = isDocTimestamp(sig);
                (docTs ? tsGroup : sigGroup).add(node);
            }
            if (perms != null && perms.getDictionaryObject(COSName.getPDFName("UR3")) instanceof COSDictionary ur3) {
                sigGroup.add(usageRights(ur3, bytes, ends));
            }
        }
        sortByEnd(sigGroup);
        sortByEnd(tsGroup);
        long realSigs = sigGroup.children().stream().filter(n -> n.kind() == Kind.SIGNATURE).count();
        sigGroup.title("Podpisy a pečetě (" + realSigs + ")");
        int docTimestamps = tsGroup.children().size();
        // Vedle razítek dokumentu i razítka vnořená v podpisech (razítko podpisu, obsahu,
        // archivní) — týž uzel, strom ho ukáže na obou místech. Viz CmsStructure.embeddedTimestamps.
        int embedded = 0;
        for (StructureNode s : sigGroup.children()) {
            if (s.kind() != Kind.SIGNATURE) continue;
            for (StructureNode t : CmsStructure.embeddedTimestamps(s)) {
                tsGroup.add(t);
                embedded++;
            }
        }
        tsGroup.title("Časová razítka (" + tsGroup.children().size() + ")");
        if (sigGroup.children().isEmpty()) sigGroup.note(Severity.INFO, "Dokument nemá žádné podpisové pole.");
        if (docTimestamps == 0) tsGroup.note(Severity.INFO, "Dokument nemá časové razítko dokumentu (/DocTimeStamp).");
        summary.add(new StructureNode.Row("Podpisů a pečetí", String.valueOf(realSigs), null));
        summary.add(new StructureNode.Row("Razítek dokumentu / v podpisech", docTimestamps + " / " + embedded, null));
        roots.add(sigGroup);
        roots.add(tsGroup);

        // V které revizi se položka /DSS objevila poprvé.
        Map<String, Integer> firstSeen = new HashMap<>();
        Map<String, Integer> vriFirstSeen = new HashMap<>();
        for (int r = 0; r < snapshots.size(); r++) {
            Snapshot s = snapshots.get(r);
            for (List<Item> list : List.of(s.certs(), s.crls(), s.ocsps())) {
                for (Item it : list) firstSeen.putIfAbsent(it.key(), r + 1);
            }
            for (String k : s.vri().keySet()) vriFirstSeen.putIfAbsent(k, r + 1);
        }

        Snapshot last = snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);
        roots.add(dssNode(last, firstSeen, -1, snapshots.size()));
        roots.add(vriGroup(last, firstSeen, vriFirstSeen, sigRefs, -1));
        StructureNode revs = revisions(bytes, ends, snapshots, firstSeen, vriFirstSeen, sigRefs);
        roots.add(revs);
        long emptyRevs = revs.children().stream()
                .filter(n -> n.rows().stream().anyMatch(r -> r.label().equals("Objekty"))).count();
        if (emptyRevs > 0) {
            summary.set(1, new StructureNode.Row("Revizí", ends.size() + " (z toho " + emptyRevs
                    + " bez objektů)", null));
        }
        return new StructureInspector.Inspection(StructureInspector.Format.PDF, fileName, bytes.length,
                ends, roots, summary, null);
    }

    // ------------------------------------------------------------------ revize

    /**
     * Konce revizí: pozice za {@code %%EOF} (včetně konce řádku), kterým předchází {@code startxref}.
     * Bez jediné značky je celý soubor jednou revizí.
     */
    static List<Long> revisionEnds(byte[] b) {
        List<Long> ends = new ArrayList<>();
        byte[] eof = "%%EOF".getBytes(StandardCharsets.US_ASCII);
        byte[] sx = "startxref".getBytes(StandardCharsets.US_ASCII);
        int i = StructureInspector.indexOf(b, eof, 0, b.length);
        while (i >= 0) {
            if (StructureInspector.indexOf(b, sx, Math.max(0, i - 1100), i) >= 0) {
                int end = i + eof.length;
                if (end < b.length && b[end] == '\r') end++;
                if (end < b.length && b[end] == '\n') end++;
                ends.add((long) end);
            }
            i = StructureInspector.indexOf(b, eof, i + eof.length, b.length);
        }
        if (ends.size() > 1 && StructureInspector.indexOf(b, "/Linearized".getBytes(StandardCharsets.US_ASCII),
                0, Math.min(b.length, 1024)) >= 0) {
            ends.remove(0);
        }
        if (ends.isEmpty()) ends.add((long) b.length);
        return ends;
    }

    /** Číslo revize (od 1), kterou rozsah končící na {@code end} uzavírá; 0 = žádnou. */
    private static int revisionOf(List<Long> ends, long end) {
        for (int r = 0; r < ends.size(); r++) {
            if (Math.abs(ends.get(r) - end) <= 2) return r + 1;
        }
        return 0;
    }

    private static StructureNode revisions(byte[] bytes, List<Long> ends, List<Snapshot> snapshots,
                                           Map<String, Integer> firstSeen, Map<String, Integer> vriFirstSeen,
                                           List<SigRef> sigRefs) {
        StructureNode group = new StructureNode(Kind.GROUP, "Revize dokumentu (" + ends.size() + ")");
        long lastCovered = sigRefs.stream().mapToLong(SigRef::end).max().orElse(-1);
        long prev = 0;
        for (int r = 0; r < ends.size(); r++) {
            long end = ends.get(r);
            int no = r + 1;
            StructureNode node = new StructureNode(Kind.REVISION, "Revize " + no).key("rev:" + no);
            node.span(prev, end, false);
            node.row("Bajty", fmt(prev) + " až " + fmt(end - 1));
            node.row("Velikost", ContainerInspector.formatSize(end - prev));
            List<String> closers = new ArrayList<>();
            for (SigRef s : sigRefs) {
                if (Math.abs(s.end() - end) <= 2) {
                    node.row("Uzavírá ji", s.label(), s.key());
                    closers.add(s.label());
                }
            }
            Snapshot snap = snapshots.get(r);
            Snapshot before = r > 0 ? snapshots.get(r - 1) : null;
            int newCerts = countNew(snap.certs(), firstSeen, no);
            int newCrls = countNew(snap.crls(), firstSeen, no);
            int newOcsps = countNew(snap.ocsps(), firstSeen, no);
            int newVri = (int) snap.vri().keySet().stream().filter(k -> vriFirstSeen.get(k) == no).count();
            if (newCerts + newCrls + newOcsps + newVri > 0) {
                node.row("Nové v /DSS", newCerts + " certifikátů, " + newCrls + " CRL, " + newOcsps
                        + " OCSP, " + newVri + " VRI");
            }
            if (before != null && before.present() && !snap.present()) {
                node.note(Severity.WARNING, "V této revizi slovník /DSS zmizel.");
            }
            boolean empty = r > 0 && !definesObjects(bytes, prev, end);
            if (empty) {
                node.row("Objekty", "žádné — jen tabulka odkazů a trailer");
                node.note(Severity.INFO, "Přírůstková aktualizace bez jediného objektu: obsah dokumentu"
                        + " nemění. Takto vypadá hybridní soubor (trailer s /XRefStm pro starší čtečky),"
                        + " jaký vytváří např. export do PDF z Microsoft Wordu.");
            }
            String what = !closers.isEmpty() ? String.join(", ", closers)
                    : newCerts + newCrls + newOcsps + newVri > 0 ? "doplnění ověřovacích údajů"
                    : r == 0 ? "původní dokument"
                    : empty ? "bez objektů — obsah nemění" : "změna bez podpisu";
            node.subtitle(ContainerInspector.formatSize(end - prev) + " · " + what);
            if (lastCovered >= 0 && end > lastCovered + 2) {
                node.note(Severity.INFO, "Revizi nekryje žádný podpis ani razítko dokumentu.");
            }
            if (snap.present()) {
                StructureNode dss = node.add(dssNode(snap, firstSeen, no, ends.size()));
                dss.title("DSS v této revizi");
                StructureNode vri = node.add(vriGroup(snap, firstSeen, vriFirstSeen, sigRefs, no));
                vri.title("VRI v této revizi (" + snap.vri().size() + ")");
            } else {
                node.row("/DSS", "v této revizi není");
            }
            group.add(node);
            prev = end;
        }
        if (prev < bytes.length) {
            group.note(Severity.WARNING, "Za posledním %%EOF následuje ještě " + (bytes.length - prev)
                    + " B, které do žádné revize nepatří.");
        }
        if (ends.size() == 1 && ends.get(0) == bytes.length
                && StructureInspector.indexOf(bytes, "%%EOF".getBytes(StandardCharsets.US_ASCII)) < 0) {
            group.note(Severity.WARNING, "Soubor nemá značku %%EOF; revize nelze rozlišit.");
        }
        return group;
    }

    /**
     * Definuje úsek souboru aspoň jeden objekt ({@code N G obj})?
     *
     * <p>Nález uživatele 13. 10. 2026: nepodepsaný dokument z exportu Wordu měl „2 revize“, z nichž
     * druhá je 179 B prázdné tabulky odkazů ({@code xref 0 0}) a traileru s {@code /XRefStm}.
     * Formálně přírůstková aktualizace, obsahově nic; „změna bez podpisu“ u ní mátla.
     * Objekty v komprimovaném proudu objektů stojí uvnitř {@code obj} proudu, takže se počítají
     * i tak.
     */
    private static boolean definesObjects(byte[] b, long from, long to) {
        String s = new String(b, (int) from, (int) (to - from), StandardCharsets.ISO_8859_1);
        return java.util.regex.Pattern.compile("\\d+\\s+\\d+\\s+obj\\b").matcher(s).find();
    }

    private static int countNew(List<Item> items, Map<String, Integer> firstSeen, int rev) {
        int n = 0;
        for (Item it : items) if (firstSeen.get(it.key()) == rev) n++;
        return n;
    }

    // ------------------------------------------------------------------ /DSS

    private static Snapshot snapshot(PDDocument doc, Map<String, byte[]> canonical) {
        COSDictionary catalog = doc.getDocumentCatalog().getCOSObject();
        COSBase raw = catalog.getItem(DSS);
        if (!(catalog.getDictionaryObject(DSS) instanceof COSDictionary dss)) {
            return new Snapshot(null, List.of(), List.of(), List.of(), Map.of(), false);
        }
        Map<String, VriEntry> vri = new LinkedHashMap<>();
        if (dss.getDictionaryObject(VRI) instanceof COSDictionary vd) {
            for (COSName k : vd.keySet()) {
                if (!(vd.getDictionaryObject(k) instanceof COSDictionary e)) continue;
                byte[] ts = null;
                if (e.getDictionaryObject(TS) instanceof COSStream s) ts = streamBytes(s);
                String tu = e.getDictionaryObject(TU) instanceof COSString str ? str.getString() : null;
                vri.put(k.getName().toUpperCase(Locale.ROOT), new VriEntry(k.getName(),
                        items(e, CERT, "cert", canonical), items(e, CRL, "crl", canonical),
                        items(e, OCSP, "ocsp", canonical), tu, ts, ref(vd.getItem(k))));
            }
        }
        return new Snapshot(ref(raw), items(dss, CERTS, "cert", canonical), items(dss, CRLS, "crl", canonical),
                items(dss, OCSPS, "ocsp", canonical), vri, true);
    }

    private static List<Item> items(COSDictionary dict, COSName key, String type, Map<String, byte[]> canonical) {
        List<Item> out = new ArrayList<>();
        if (!(dict.getDictionaryObject(key) instanceof COSArray arr)) return out;
        for (int i = 0; i < arr.size(); i++) {
            COSBase raw = arr.get(i);
            if (!(arr.getObject(i) instanceof COSStream s)) continue;
            byte[] der = streamBytes(s);
            if (der == null) continue;
            String k = switch (type) {
                case "cert" -> CmsStructure.certKey(der);
                case "crl" -> CmsStructure.crlKey(der);
                default -> {
                    var basic = CmsStructure.basicOcsp(der);
                    try {
                        yield basic != null ? CmsStructure.ocspKey(basic.getEncoded("DER"))
                                : "ocsp-raw:" + ContainerInspector.sha256Hex(der);
                    } catch (Exception e) {
                        yield "ocsp-raw:" + ContainerInspector.sha256Hex(der);
                    }
                }
            };
            // Táž položka se v revizích opakuje; bajty stačí držet jednou.
            out.add(new Item(k, canonical.computeIfAbsent(k, x -> der), ref(raw)));
        }
        return out;
    }

    private static StructureNode dssNode(Snapshot snap, Map<String, Integer> firstSeen, int revision,
                                         int revisionCount) {
        StructureNode node = new StructureNode(Kind.DSS, "DSS — úložiště ověřovacích údajů (/DSS)");
        if (snap == null || !snap.present()) {
            node.note(Severity.INFO, "Dokument slovník /DSS nemá — ověřovací údaje nejsou uloženy"
                    + " na úrovni dokumentu (PAdES B-LT a vyšší je tu ukládá).");
            return node;
        }
        node.row("Objekt PDF", snap.dssRef());
        node.row("Certifikátů (/Certs)", String.valueOf(snap.certs().size()));
        node.row("Seznamů CRL (/CRLs)", String.valueOf(snap.crls().size()));
        node.row("Odpovědí OCSP (/OCSPs)", String.valueOf(snap.ocsps().size()));
        node.row("Záznamů VRI (/VRI)", String.valueOf(snap.vri().size()));
        node.subtitle(snap.certs().size() + " certifikátů, " + snap.crls().size() + " CRL, "
                + snap.ocsps().size() + " OCSP");
        node.add(itemGroup("Certifikáty (/Certs)", snap.certs(), firstSeen, revision, revisionCount));
        node.add(itemGroup("Seznamy CRL (/CRLs)", snap.crls(), firstSeen, revision, revisionCount));
        node.add(itemGroup("Odpovědi OCSP (/OCSPs)", snap.ocsps(), firstSeen, revision, revisionCount));
        checkDuplicates(node, snap);
        return node;
    }

    private static void checkDuplicates(StructureNode node, Snapshot snap) {
        for (List<Item> list : List.of(snap.certs(), snap.crls(), snap.ocsps())) {
            long distinct = list.stream().map(Item::key).distinct().count();
            if (distinct < list.size()) {
                node.note(Severity.INFO, "Úložiště obsahuje tutéž položku vícekrát ("
                        + (list.size() - distinct) + "× navíc).");
                return;
            }
        }
    }

    private static StructureNode itemGroup(String title, List<Item> items, Map<String, Integer> firstSeen,
                                           int revision, int revisionCount) {
        StructureNode g = new StructureNode(Kind.GROUP, title + " (" + items.size() + ")");
        for (Item it : items) g.add(itemNode(it, firstSeen, revision, revisionCount));
        return g;
    }

    private static StructureNode itemNode(Item it, Map<String, Integer> firstSeen, int revision,
                                          int revisionCount) {
        StructureNode n = it.key().startsWith("cert:") ? CmsStructure.certificate(it.der())
                : it.key().startsWith("crl:") ? CmsStructure.crl(it.der()) : CmsStructure.ocsp(it.der());
        n.row("Objekt PDF", it.objRef());
        Integer first = firstSeen.get(it.key());
        if (first != null) {
            n.row("Poprvé v revizi", String.valueOf(first), "rev:" + first);
            if (revision > 0) {
                n.subtitle((first == revision ? "nové v této revizi · " : "z revize " + first + " · ")
                        + n.subtitle());
            } else if (revisionCount > 1) {
                n.subtitle("revize " + first + " · " + n.subtitle());
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ /VRI

    private static StructureNode vriGroup(Snapshot snap, Map<String, Integer> firstSeen,
                                          Map<String, Integer> vriFirstSeen, List<SigRef> sigRefs, int revision) {
        int size = snap == null ? 0 : snap.vri().size();
        StructureNode g = new StructureNode(Kind.GROUP, "VRI — údaje k jednotlivým podpisům (/VRI) (" + size + ")");
        if (snap == null || !snap.present()) {
            g.note(Severity.INFO, "Bez slovníku /DSS nejsou ani záznamy VRI.");
            return g;
        }
        if (size == 0) {
            g.note(Severity.INFO, "Slovník /DSS záznamy VRI nemá; PAdES je nevyžaduje — ověřovatel"
                    + " vybírá z celého úložiště.");
            return g;
        }
        for (Map.Entry<String, VriEntry> e : snap.vri().entrySet()) {
            VriEntry v = e.getValue();
            String key = e.getKey();
            StructureNode n = new StructureNode(Kind.VRI, "VRI " + key.substring(0, Math.min(8, key.length())) + "…")
                    .key(revision < 0 ? "vri:" + key : null);
            n.row("Klíč", v.name());
            n.row("Objekt PDF", v.objRef());
            SigRef owner = null;
            for (SigRef s : sigRefs) {
                if (s.vriCandidates().containsKey(key)) { owner = s; break; }
            }
            if (owner != null) {
                n.row("Patří k", owner.label(), owner.key());
                n.row("Klíč je", owner.vriCandidates().get(key));
                n.subtitle("→ " + owner.label());
                if (revision < 0) owner.node().row("Záznam VRI", key, "vri:" + key);
            } else {
                n.subtitle("nepatří k žádnému podpisu ani razítku");
                if (revision < 0) {
                    // Jen informace: v souboru LT ze sbírky D:\tmp (13. 10. 2026) takový klíč není
                    // otiskem ničeho, co v souboru je — ani podstruktury DER v žádném objektu.
                    // Ověření to nevadí, PAdES záznamy VRI nevyžaduje.
                    n.note(Severity.INFO, "Klíči neodpovídá SHA-1 žádného podpisu ani razítka v dokumentu"
                            + " — záznam se k ničemu v souboru přiřadit nedá. Ověření to nevadí,"
                            + " ověřovatel bere údaje z celého /DSS.");
                }
            }
            if (v.tu() != null) {
                Calendar c = DateConverter.toCalendar(v.tu());
                n.row("Čas vytvoření (/TU)", c != null ? ContainerInspector.formatDate(c.getTime()) : v.tu());
            }
            Integer first = vriFirstSeen.get(key);
            if (first != null) n.row("Poprvé v revizi", String.valueOf(first), "rev:" + first);
            n.add(itemGroup("Certifikáty (/Cert)", v.certs(), firstSeen, revision, 0));
            n.add(itemGroup("Seznamy CRL (/CRL)", v.crls(), firstSeen, revision, 0));
            n.add(itemGroup("Odpovědi OCSP (/OCSP)", v.ocsps(), firstSeen, revision, 0));
            if (v.ts() != null) n.add(CmsStructure.timestamp(v.ts(), "razítko záznamu VRI (/TS)", List.of()));
            g.add(n);
        }
        return g;
    }

    // ------------------------------------------------------------------ podpis

    private static boolean isDocTimestamp(PDSignature sig) {
        COSBase type = sig.getCOSObject().getDictionaryObject(COSName.TYPE);
        return (type instanceof COSName n && "DocTimeStamp".equals(n.getName()))
                || "ETSI.RFC3161".equals(sig.getSubFilter());
    }

    private static StructureNode signature(PDSignatureField field, PDSignature sig, PDDocument doc, byte[] file,
                                           List<Long> ends, int index, List<SigRef> sigRefs, boolean certification) {
        boolean docTs = isDocTimestamp(sig);
        String name = field.getFullyQualifiedName();
        String label = (docTs ? "Razítko dokumentu" : "Podpis") + " „" + name + "“";
        StructureNode node = new StructureNode(docTs ? Kind.DOC_TIMESTAMP : Kind.SIGNATURE, label)
                .key("pdfsig:" + index);
        COSDictionary d = sig.getCOSObject();
        node.row("Pole (/T)", name);
        COSBase type = d.getDictionaryObject(COSName.TYPE);
        node.row("Typ (/Type)", type instanceof COSName t ? t.getName() : "neuveden");
        node.row("Filtr (/Filter)", sig.getFilter());
        node.row("Podfiltr (/SubFilter)", sig.getSubFilter() + subFilterHint(sig.getSubFilter()));
        if (certification) {
            node.row("Druh", "certifikační podpis (odkaz z /Perms /DocMDP)");
        }
        String m = d.getString(COSName.M);
        if (m != null) {
            Calendar c = sig.getSignDate();
            node.row("Čas (/M)", (c != null ? ContainerInspector.formatDate(c.getTime()) + " — " : "") + m);
        }
        node.row("Jméno (/Name)", sig.getName());
        node.row("Důvod (/Reason)", sig.getReason());
        node.row("Místo (/Location)", sig.getLocation());
        node.row("Kontakt (/ContactInfo)", sig.getContactInfo());
        if (d.getDictionaryObject(COSName.getPDFName("Prop_Build")) instanceof COSDictionary pb
                && pb.getDictionaryObject(COSName.getPDFName("App")) instanceof COSDictionary app) {
            node.row("Aplikace (/Prop_Build /App)", app.getNameAsString(COSName.NAME));
        }
        node.row("Objekt PDF", ref(field.getCOSObject().getItem(COSName.V)));
        widget(node, field, doc);

        int[] br = sig.getByteRange();
        long end = -1;
        Content signed = null;
        if (br == null || br.length != 4) {
            node.note(Severity.ERROR, "Slovník nemá platný /ByteRange (čtyři čísla).");
        } else {
            long a = br[0], b = br[1], c = br[2], len2 = br[3];
            end = c + len2;
            node.row("Rozsah (/ByteRange)", "[" + a + " " + b + " " + c + " " + len2 + "]");
            node.row("Kryté bajty", fmt(a) + " až " + fmt(a + b - 1) + " a " + fmt(c) + " až " + fmt(end - 1));
            node.row("Mezera pro /Contents", fmt(b) + " až " + fmt(c - 1) + " (" + (c - b) + " B)");
            node.span(a, a + b, false).span(a + b, c, true).span(c, end, false);
            if (a != 0) node.note(Severity.ERROR, "Rozsah nezačíná na začátku souboru.");
            boolean inFile = end <= file.length && b <= c;
            if (!inFile) {
                node.note(Severity.ERROR, "Rozsah sahá za konec souboru.");
            } else {
                if (file[(int) b] != '<' || file[(int) c - 1] != '>') {
                    node.note(Severity.ERROR, "Mezera v rozsahu nesedí přesně na hexadecimální hodnotu /Contents.");
                }
                int bi = (int) b, ci = (int) c, li = (int) len2;
                signed = new Content("bajty kryté rozsahem /ByteRange", md -> {
                    md.update(file, 0, bi);
                    md.update(file, ci, li);
                });
            }
            int rev = revisionOf(ends, end);
            if (rev > 0) {
                node.row("Revize", rev + " z " + ends.size(), "rev:" + rev);
                if (rev < ends.size()) {
                    node.note(Severity.INFO, "Po podpisu následuje " + (ends.size() - rev)
                            + (ends.size() - rev == 1 ? " další revize." : " dalších revizí."));
                } else {
                    node.note(Severity.OK, "Rozsah kryje celý soubor až na hodnotu /Contents.");
                }
            } else {
                node.note(Severity.WARNING, "Rozsah nekončí na konci žádné revize (bajt " + fmt(end) + ").");
            }
            node.subtitle((rev > 0 ? "revize " + rev : "mimo revize") + " · " + sig.getSubFilter());
        }

        byte[] contents = d.getDictionaryObject(COSName.CONTENTS) instanceof COSString s ? s.getBytes() : null;
        Map<String, String> vriCandidates = new LinkedHashMap<>();
        if (contents == null) {
            node.note(Severity.ERROR, "Slovník nemá hodnotu /Contents.");
        } else {
            int used = derLength(contents);
            node.row("Vyhrazené místo (/Contents)", contents.length + " B, obsazeno " + used + " B");
            byte[] der = Arrays.copyOf(contents, used);
            vriCandidates.put(sha1(contents), "SHA-1 celé hodnoty /Contents včetně výplně");
            vriCandidates.putIfAbsent(sha1(der), "SHA-1 objektu CMS bez výplně");
            List<Content> candidates = signed != null ? List.of(signed) : List.of();
            StructureNode inner;
            if ("adbe.x509.rsa_sha1".equals(sig.getSubFilter())) {
                inner = pkcs1(d, der);
            } else if (docTs) {
                inner = CmsStructure.timestamp(der, "časové razítko dokumentu", candidates);
                inner.title("Časové razítko (/Contents)");
                node.dssKey(inner.dssKey());
                if (inner.subtitle() != null) node.subtitle(node.subtitle() + " · " + inner.subtitle());
            } else {
                inner = CmsStructure.signedData(der, "Podpis CMS (/Contents)", candidates);
                for (StructureNode ch : inner.children()) {
                    if (ch.kind() == Kind.SIGNER && ch.dssKey() != null) {
                        node.dssKey(ch.dssKey());
                        if (ch.subtitle() != null) node.subtitle(ch.subtitle() + " · " + node.subtitle());
                        break;
                    }
                }
            }
            node.add(inner);
            // DSS zakládá záznam VRI i pro každé razítko vložené v podpisu, s klíčem z tokenu
            // razítka. Bez téhle dvojice hlásila sonda 13. 10. 2026 nad sbírkou D:\tmp 266 záznamů
            // VRI „bez podpisu“ v 66 souborech.
            if (!docTs) addTimestampRefs(inner, label, sigRefs);
        }
        if (d.getDictionaryObject(COSName.getPDFName("Reference")) instanceof COSArray refs) {
            node.add(references(refs));
        }
        sigRefs.add(new SigRef(node, node.key(), label, end, vriCandidates));
        return node;
    }

    private static void addTimestampRefs(StructureNode n, String owner, List<SigRef> sigRefs) {
        for (StructureNode c : n.children()) {
            if (c.kind() == Kind.TIMESTAMP && c.data() != null) {
                sigRefs.add(new SigRef(c, c.key(), "razítko v podpisu " + owner, -1,
                        Map.of(sha1(c.data()), "SHA-1 tokenu časového razítka")));
            }
            addTimestampRefs(c, owner, sigRefs);
        }
    }

    private static String sha1(byte[] b) {
        return ContainerInspector.digestHex(b, "SHA-1").toUpperCase(Locale.ROOT);
    }

    private static void widget(StructureNode node, PDSignatureField field, PDDocument doc) {
        List<PDAnnotationWidget> widgets = field.getWidgets();
        if (widgets.isEmpty()) {
            node.row("Vzhled", "pole nemá widget");
            return;
        }
        PDAnnotationWidget w = widgets.get(0);
        PDRectangle r = w.getRectangle();
        int page = w.getPage() != null ? doc.getPages().indexOf(w.getPage()) + 1 : 0;
        boolean invisible = r == null || r.getWidth() <= 0 || r.getHeight() <= 0;
        node.row("Vzhled", (invisible ? "neviditelný" : "viditelný")
                + (page > 0 ? ", strana " + page : "")
                + (invisible || r == null ? "" : String.format(Locale.ROOT, ", obdélník [%.0f %.0f %.0f %.0f]",
                        r.getLowerLeftX(), r.getLowerLeftY(), r.getUpperRightX(), r.getUpperRightY())));
    }

    private static StructureNode emptyField(PDSignatureField field, PDDocument doc) {
        StructureNode n = new StructureNode(Kind.EMPTY_FIELD, "Prázdné pole „" + field.getFullyQualifiedName() + "“")
                .subtitle("nepodepsáno");
        n.row("Pole (/T)", field.getFullyQualifiedName());
        widget(n, field, doc);
        n.note(Severity.INFO, "Podpisové pole bez podpisu (/V chybí).");
        return n;
    }

    private static StructureNode usageRights(COSDictionary ur3, byte[] file, List<Long> ends) {
        StructureNode n = new StructureNode(Kind.USAGE_RIGHTS, "Podpis užívacích práv (/Perms /UR3)")
                .subtitle("není podpisem osoby");
        n.row("Filtr (/Filter)", ur3.getNameAsString(COSName.FILTER));
        n.row("Podfiltr (/SubFilter)", ur3.getNameAsString(COSName.SUB_FILTER));
        if (ur3.getDictionaryObject(COSName.BYTERANGE) instanceof COSArray a && a.size() == 4) {
            long[] v = new long[4];
            for (int i = 0; i < 4; i++) v[i] = a.getObject(i) instanceof COSNumber num ? num.longValue() : 0;
            n.row("Rozsah (/ByteRange)", "[" + v[0] + " " + v[1] + " " + v[2] + " " + v[3] + "]");
            n.span(v[0], v[0] + v[1], false).span(v[0] + v[1], v[2], true).span(v[2], v[2] + v[3], false);
            int rev = revisionOf(ends, v[2] + v[3]);
            if (rev > 0) n.row("Revize", String.valueOf(rev), "rev:" + rev);
        }
        n.note(Severity.INFO, "Adobe jím v Readeru odemyká funkce (vyplňování, komentáře). O autorství"
                + " dokumentu nic neříká a ověřovatelé ho mezi podpisy nepočítají.");
        if (ur3.getDictionaryObject(COSName.CONTENTS) instanceof COSString s) {
            byte[] der = Arrays.copyOf(s.getBytes(), derLength(s.getBytes()));
            n.add(CmsStructure.signedData(der, "Podpis CMS (/Contents)", List.of()));
        }
        return n;
    }

    /** Podpis PKCS #1 ({@code adbe.x509.rsa_sha1}): hodnota je holý podpis, certifikáty v {@code /Cert}. */
    private static StructureNode pkcs1(COSDictionary d, byte[] der) {
        StructureNode n = new StructureNode(Kind.VALUE, "Podpis PKCS #1 (/Contents)").data(der, "podpis.bin");
        n.row("Délka", der.length + " B");
        n.note(Severity.INFO, "Starší podpis Adobe: /Contents nese holou hodnotu podpisu RSA, ne objekt CMS;"
                + " certifikáty jsou v /Cert.");
        COSBase cert = d.getDictionaryObject(COSName.getPDFName("Cert"));
        List<byte[]> certs = new ArrayList<>();
        if (cert instanceof COSString s) certs.add(s.getBytes());
        if (cert instanceof COSArray arr) {
            for (int i = 0; i < arr.size(); i++) if (arr.getObject(i) instanceof COSString s) certs.add(s.getBytes());
        }
        StructureNode g = n.add(new StructureNode(Kind.GROUP, "Certifikáty (/Cert) (" + certs.size() + ")"));
        for (byte[] c : certs) g.add(CmsStructure.certificate(c));
        return n;
    }

    /** Oprávnění certifikačního podpisu a uzamčení polí ({@code /Reference}, DocMDP / FieldMDP). */
    private static StructureNode references(COSArray refs) {
        StructureNode n = new StructureNode(Kind.VALUE, "Omezení změn (/Reference)");
        for (int i = 0; i < refs.size(); i++) {
            if (!(refs.getObject(i) instanceof COSDictionary ref)) continue;
            String method = ref.getNameAsString(COSName.getPDFName("TransformMethod"));
            String p = refs.size() > 1 ? " " + (i + 1) : "";
            n.row("Metoda" + p + " (/TransformMethod)", method);
            if (ref.getDictionaryObject(COSName.getPDFName("TransformParams")) instanceof COSDictionary tp) {
                if (tp.getDictionaryObject(COSName.P) instanceof COSNumber pn) {
                    int pv = pn.intValue();
                    String meaning = switch (pv) {
                        case 1 -> "žádné změny nejsou dovoleny";
                        case 2 -> "dovoleno vyplňování formulářů a podepisování";
                        case 3 -> "dovoleno vyplňování, podepisování a anotace";
                        default -> "neznámá hodnota";
                    };
                    n.row("Oprávnění" + p + " (/P)", pv + " — " + meaning);
                    n.subtitle("DocMDP P=" + pv);
                }
                String action = tp.getNameAsString(COSName.getPDFName("Action"));
                if (action != null) {
                    n.row("Akce" + p + " (/Action)", action + switch (action) {
                        case "All" -> " — uzamčena všechna pole";
                        case "Include" -> " — uzamčena vyjmenovaná pole";
                        case "Exclude" -> " — uzamčena všechna pole kromě vyjmenovaných";
                        default -> "";
                    });
                }
                if (tp.getDictionaryObject(COSName.FIELDS) instanceof COSArray f) {
                    List<String> names = new ArrayList<>();
                    for (int j = 0; j < f.size(); j++) if (f.getObject(j) instanceof COSString s) names.add(s.getString());
                    n.row("Pole" + p + " (/Fields)", String.join(", ", names));
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ pomocné

    private static void sortByEnd(StructureNode group) {
        group.children().sort((x, y) -> Long.compare(spanEnd(x), spanEnd(y)));
    }

    private static long spanEnd(StructureNode n) {
        return n.spans().isEmpty() ? Long.MAX_VALUE : n.spans().get(n.spans().size() - 1).end();
    }

    private static String subFilterHint(String sf) {
        if (sf == null) return "";
        return switch (sf) {
            case "ETSI.CAdES.detached" -> " — PAdES (CAdES, oddělený)";
            case "adbe.pkcs7.detached" -> " — CMS, oddělený (Adobe / PAdES)";
            case "adbe.pkcs7.sha1" -> " — CMS nad otiskem SHA-1 (zastaralé)";
            case "adbe.x509.rsa_sha1" -> " — PKCS #1 (zastaralé)";
            case "ETSI.RFC3161" -> " — časové razítko dokumentu";
            default -> "";
        };
    }

    /**
     * Délka objektu na začátku pole — PDF za něj doplňuje nuly do vyhrazeného místa.
     *
     * <p>Neurčitá délka (BER, {@code 30 80}) se musí dočíst až po koncovou značku: hlavička délku
     * neříká, a s výplní na konci se objekt CMS nepřečte. Takto kóduje i BouncyCastle, když se
     * mu neřekne o DER (zjištěno 13. 10. 2026 na podpisu sestaveném v testu).
     */
    static int derLength(byte[] b) {
        if (b.length < 2 || b[0] != 0x30) return b.length;
        int first = b[1] & 0xFF;
        if (first < 0x80) return Math.min(b.length, 2 + first);
        int n = first & 0x7F;
        if (n == 0) {
            java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(b);
            try (org.bouncycastle.asn1.ASN1InputStream a = new org.bouncycastle.asn1.ASN1InputStream(in, b.length)) {
                a.readObject();
                return b.length - in.available();
            } catch (Exception e) {
                return b.length;
            }
        }
        if (n > 4 || b.length < 2 + n) return b.length;
        long len = 0;
        for (int i = 0; i < n; i++) len = (len << 8) | (b[2 + i] & 0xFF);
        long total = 2 + n + len;
        return total > b.length ? b.length : (int) total;
    }

    private static byte[] streamBytes(COSStream s) {
        try (InputStream in = s.createInputStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    private static String ref(COSBase raw) {
        return raw instanceof COSObject o ? o.getObjectNumber() + " " + o.getGenerationNumber() + " R" : null;
    }

    private static String fmt(long v) {
        return String.format(Locale.forLanguageTag("cs"), "%,d", v).replace(' ', ' ');
    }
}
