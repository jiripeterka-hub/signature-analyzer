package cz.digitalnikontinuita.anep.core;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Vstup do Inspektoru podpisových struktur: pozná formát a předá soubor příslušnému čtení.
 *
 * <p>Inspektor ukazuje soubor tak, jak je uložen, nezávisle na DSS — i to, co DSS přehlédne nebo
 * nepoužije (přebytečné položky v {@code /DSS}, VRI bez podpisu, starší podoby úložiště
 * v dřívějších revizích). Výklad podle výsledku ověření se k uzlům přidává až potom,
 * v {@link StructureAnnotations}.
 *
 * <p><b>Proč název podle formátu.</b> Rozhodnutí uživatele z 13. 10. 2026: inspektor je jeden,
 * ale čtenář má z tlačítka poznat, na co se dívá — „Inspektor PDF“, „Inspektor CAdES“,
 * „Inspektor ASiC“, „Inspektor XAdES“. Rozklad CMS ({@link CmsStructure}) sdílejí CAdES, ASiC
 * i PDF; XAdES ({@link XadesStructure}, doplněn 13. 10. 2026) samostatně i v ASiC. JAdES zatím
 * rozložený je od téhož dne také ({@link JadesStructure}).
 */
public final class StructureInspector {

    private StructureInspector() {}

    public enum Format {
        PDF("Inspektor PDF"),
        CADES("Inspektor CAdES"),
        ASIC("Inspektor ASiC"),
        XADES("Inspektor XAdES"),
        JADES("Inspektor JAdES");

        private final String title;
        Format(String title) { this.title = title; }
        /** Popisek tlačítka i název okna. */
        public String title() { return title; }
    }

    /**
     * Výsledek čtení.
     *
     * @param revisionEnds konce revizí PDF v bajtech (u ostatních formátů prázdné)
     * @param summary      souhrnné řádky nad stromem
     * @param error        chyba, kvůli které se soubor nepodařilo přečíst vůbec, nebo {@code null}
     */
    public record Inspection(Format format, String fileName, long fileLength, List<Long> revisionEnds,
                             List<StructureNode> roots, List<StructureNode.Row> summary, String error) {}

    /** Formát, který Inspektor umí ukázat, nebo {@code null}, když tlačítko nemá smysl nabízet. */
    public static Format formatOf(File file) {
        if (file == null || !file.isFile()) return null;
        byte[] head;
        try (InputStream in = Files.newInputStream(file.toPath())) {
            head = in.readNBytes(1024);
        } catch (Exception e) {
            return null;
        }
        // CMS napřed: .p7m s vloženým PDF nese „%PDF-“ hned v prvním kilobajtu (zjištěno sondou
        // InspectorSmoke 13. 10. 2026) a hlásil se jako PDF.
        ContainerInspector.Kind kind = ContainerInspector.detectKind(head);
        if (kind == ContainerInspector.Kind.CMS || kind == ContainerInspector.Kind.ZFO) return Format.CADES;
        if (head.length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
            return hasCadesParts(file) ? Format.ASIC : null;
        }
        if (indexOf(head, "%PDF-".getBytes()) >= 0) return Format.PDF;
        // JAdES: JSON (obecná či zploštělá serializace) nebo kompaktní tvar, který JSON není.
        boolean compactCandidate = kind == ContainerInspector.Kind.OTHER && head.length >= 2
                && head[0] == 'e' && head[1] == 'y';
        if ((kind == ContainerInspector.Kind.JSON || compactCandidate) && file.length() < 64L * 1024 * 1024) {
            try {
                if (JadesStructure.looksLikeJws(Files.readAllBytes(file.toPath()))) return Format.JADES;
            } catch (Exception ignored) { }
        }
        // XML: podpis bývá až na konci dokumentu (obalený podpis), proto se čte celý soubor —
        // jen do rozumné velikosti; větší XML s podpisem se v praxi nevyskytuje.
        if (kind == ContainerInspector.Kind.XML && file.length() < 64L * 1024 * 1024) {
            try {
                if (XadesStructure.looksLikeXmlSignature(Files.readAllBytes(file.toPath()))) return Format.XADES;
            } catch (Exception ignored) { }
        }
        return null;
    }

    /**
     * Přečte soubor.
     *
     * @param original oddělený podepsaný soubor u {@code .p7s}, nebo {@code null}
     * @param password heslo k šifrovanému PDF, nebo {@code null}
     */
    public static Inspection inspect(File file, File original, char[] password) {
        Format format = formatOf(file);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.toPath());
        } catch (Exception e) {
            return new Inspection(format, file.getName(), file.length(), List.of(), List.of(), List.of(),
                    "Soubor se nepodařilo načíst: " + CmsStructure.describe(e));
        }
        if (format == null) {
            return new Inspection(null, file.getName(), bytes.length, List.of(), List.of(), List.of(),
                    "Tento formát Inspektor nerozkládá.");
        }
        try {
            return switch (format) {
                case PDF -> PdfStructure.inspect(bytes, file.getName(), password);
                case CADES -> CadesStructure.inspectCms(bytes, file.getName(), readOrNull(original));
                case ASIC -> CadesStructure.inspectAsic(bytes, file.getName());
                case XADES -> XadesStructure.inspect(bytes, file.getName(), readOrNull(original),
                        original != null ? original.getName() : null);
                case JADES -> JadesStructure.inspect(bytes, file.getName(), readOrNull(original),
                        original != null ? original.getName() : null);
            };
        } catch (Exception e) {
            return new Inspection(format, file.getName(), bytes.length, List.of(), List.of(), List.of(),
                    "Strukturu se nepodařilo přečíst: " + CmsStructure.describe(e));
        }
    }

    private static byte[] readOrNull(File f) {
        if (f == null || !f.isFile()) return null;
        try {
            return Files.readAllBytes(f.toPath());
        } catch (Exception e) {
            return null;
        }
    }

    /** ASiC s podpisem CAdES či XAdES nebo s časovým razítkem — jen tehdy má Inspektor co ukázat. */
    private static boolean hasCadesParts(File file) {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(file.toPath()))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName().toLowerCase(Locale.ROOT);
                if (n.startsWith("meta-inf/") && (n.endsWith(".p7s") || n.endsWith(".tst")
                        || (n.contains("signatures") && n.endsWith(".xml")))) return true;
            }
        } catch (Exception ignored) {
            // Poškozený ZIP: tlačítko se nenabídne, prohlížeč kontejneru zůstává.
        }
        return false;
    }

    static int indexOf(byte[] hay, byte[] needle) {
        return indexOf(hay, needle, 0, hay.length);
    }

    static int indexOf(byte[] hay, byte[] needle, int from, int to) {
        outer:
        for (int i = Math.max(0, from); i <= to - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
