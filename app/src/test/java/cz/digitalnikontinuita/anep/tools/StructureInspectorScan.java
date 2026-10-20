package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.StructureAnnotations;
import cz.digitalnikontinuita.anep.core.StructureInspector;
import cz.digitalnikontinuita.anep.core.StructureNode;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Sonda Inspektoru podpisových struktur nad sbírkou: projde soubory, které Inspektor umí
 * (PDF, CAdES, ASiC s CAdES), a vypíše, kolik uzlů a zjištění v nich našel, kolik to trvalo
 * a zda čtení nespadlo. S {@code --validate} soubor i ověří a spáruje strukturu s DSS —
 * vypíše pak, kolik certifikátů a revokačních údajů DSS nepoužil.
 *
 * <pre>java -cp … StructureInspectorScan [--validate] [--dump] &lt;složka|soubor&gt;…</pre>
 *
 * <p>{@code --dump} vypíše celý strom; jen pro jednotlivý soubor. Výstup jde na konzoli
 * a nikam se neukládá — sbírky obsahují důvěrné dokumenty.
 */
public final class StructureInspectorScan {

    public static void main(String[] args) throws Exception {
        boolean validate = false, dump = false;
        List<Path> roots = new ArrayList<>();
        for (String a : args) {
            if (a.equals("--validate")) validate = true;
            else if (a.equals("--dump")) dump = true;
            else roots.add(Path.of(a));
        }
        List<Path> files = new ArrayList<>();
        for (Path r : roots) {
            try (Stream<Path> w = Files.walk(r)) {
                w.filter(Files::isRegularFile)
                        .filter(p -> StructureInspector.formatOf(p.toFile()) != null)
                        .sorted().forEach(files::add);
            }
        }

        AppSettings settings = null;
        Object verifier = null;
        ValidatorFactory factory = null;
        if (validate) {
            settings = AppSettings.load();
            verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
            factory = new ValidatorFactory(settings);
        }
        Map<String, Integer> totals = new TreeMap<>();
        int i = 0;
        for (Path p : files) {
            i++;
            long t0 = System.nanoTime();
            StructureInspector.Inspection in;
            try {
                in = StructureInspector.inspect(p.toFile(), null, null);
            } catch (Throwable e) {
                System.out.println(i + "\t" + p + "\tPÁD\t" + e);
                totals.merge("pád", 1, Integer::sum);
                continue;
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (in.error() != null) {
                System.out.println(i + "\t" + p + "\tCHYBA\t" + in.error());
                totals.merge("chyba čtení", 1, Integer::sum);
                continue;
            }
            if (validate) {
                try {
                    var doc = new DocumentLoader().load(p.toFile());
                    var outcome = factory.validate(doc, null,
                            (eu.europa.esig.dss.spi.validation.CertificateVerifier) verifier, null);
                    ValidationResult result = new ReportMapper().map(p.getFileName().toString(), "x", outcome,
                            settings, Map.of(), List.of(), false, Map.of());
                    Map<String, SignatureSummary> byId = new HashMap<>();
                    for (SignatureSummary s : result.getSignatures()) {
                        if (s.getSignatureId() != null) byId.put(s.getSignatureId(), s);
                    }
                    StructureAnnotations.apply(in.roots(), new StructureAnnotations.Context(
                            result.getDiagnosticData(), byId, simple(result.getSimpleReportXml()), Map.of(), Map.of()));
                } catch (Throwable e) {
                    System.out.println(i + "\t" + p + "\tOVĚŘENÍ SPADLO\t" + e);
                }
            }
            Map<String, Integer> c = new TreeMap<>();
            var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<StructureNode, Boolean>());
            for (StructureNode r : in.roots()) count(r, c, seen);
            c.forEach((k, v) -> totals.merge(k, v, Integer::sum));
            System.out.println(i + "\t" + p + "\t" + ms + " ms\trevizí " + in.revisionEnds().size() + "\t" + c);
            if (dump) for (StructureNode r : in.roots()) print(r, "");
        }
        System.out.println("CELKEM souborů " + files.size() + ": " + totals);
    }

    private static eu.europa.esig.dss.simplereport.SimpleReport simple(String xml) {
        try {
            return new eu.europa.esig.dss.simplereport.SimpleReport(
                    eu.europa.esig.dss.simplereport.SimpleReportFacade.newFacade().unmarshall(xml));
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code seen}: razítko vnořené v podpisu visí i ve skupině razítek — počítá se jednou. */
    private static void count(StructureNode n, Map<String, Integer> c, java.util.Set<StructureNode> seen) {
        if (!seen.add(n)) return;
        c.merge("uzlů", 1, Integer::sum);
        for (StructureNode.Note note : n.notes()) {
            if (note.severity() != StructureNode.Severity.OK && !note.fromValidation()) {
                c.merge(note.severity().name().toLowerCase(Locale.ROOT) + ": " + shorten(note.text()), 1, Integer::sum);
            }
            if (note.fromValidation() && (note.text().startsWith("DSS tento") || note.text().contains("osiřelý")
                    || note.text().startsWith("Výsledek"))) {
                c.merge(n.kind() + " " + shorten(note.text()), 1, Integer::sum);
            }
        }
        for (StructureNode ch : n.children()) count(ch, c, seen);
    }

    /** Zjištění bez čísel, ať se v součtech sejdou. */
    private static String shorten(String t) {
        String s = t.replaceAll("[0-9]+", "N");
        return s.length() > 70 ? s.substring(0, 70) : s;
    }

    private static void print(StructureNode n, String indent) {
        System.out.println(indent + n.title() + (n.subtitle() != null ? "  — " + n.subtitle() : ""));
        for (StructureNode.Note note : n.notes()) {
            System.out.println(indent + "    [" + note.severity() + (note.fromValidation() ? "/DSS" : "") + "] " + note.text());
        }
        for (StructureNode.Row r : n.rows()) {
            String v = r.value().length() > 100 ? r.value().substring(0, 100) + "…" : r.value();
            System.out.println(indent + "    " + r.label() + ": " + v);
        }
        for (StructureNode c : n.children()) print(c, indent + "  ");
    }
}
