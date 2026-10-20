package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.ContainerInspector;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Node;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Result;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs {@link ContainerInspector} over a folder of ZFO files and prints what the data-message
 * view made of each one: the summary, the envelope, the písemnosti and the outcome of the hash
 * check against the qualified time-stamp.
 *
 * <p>Its point is the last of those. A hash mismatch is the one thing this view asserts that
 * nothing else in the application can cross-check, so it has to be exercised against a real
 * collection before it is trusted to accuse a file of having been altered. Pass {@code -q} to
 * print one line per file, which is the form to read a large collection in.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 */
public final class IsdsMessageScan {

    public static void main(String[] args) throws Exception {
        List<String> paths = new ArrayList<>(List.of(args));
        boolean quiet = paths.remove("-q");
        if (paths.isEmpty()) {
            System.err.println("usage: IsdsMessageScan [-q] <folder|file>…");
            return;
        }

        List<File> files = new ArrayList<>();
        for (String path : paths) {
            File file = new File(path);
            if (file.isDirectory()) {
                try (Stream<Path> walk = Files.walk(file.toPath(), 1)) {
                    walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().toLowerCase().endsWith(".zfo"))
                        .sorted()
                        .forEach(p -> files.add(p.toFile()));
                }
            } else if (file.isFile()) {
                files.add(file);
            }
        }

        int messages = 0, verified = 0, mismatched = 0, notMessages = 0;
        java.util.Map<String, Integer> byType = new java.util.TreeMap<>();
        for (File file : files) {
            byte[] bytes = Files.readAllBytes(file.toPath());
            Result result = ContainerInspector.inspect(bytes, file.getName());
            if (result.kind() != ContainerInspector.Kind.ZFO) {
                notMessages++;
                System.out.println("NENÍ DZ  " + file.getName() + "  → " + result.typeLabel()
                        + (result.error() != null ? "  (" + result.error() + ")" : ""));
                // A file that looked like a message and then would not parse says so here; that
                // note is the whole point of the fallback, so it must not be swallowed.
                result.warnings().forEach(w -> System.out.println("         ! " + w));
                continue;
            }
            messages++;
            byType.merge(shortType(result.typeLabel()), 1, Integer::sum);
            // Verdikt je od 16. 10. 2026 u razítka podání, ne v záhlaví zprávy.
            String verdict = hashVerdict(result.root());
            if (verdict.contains("✔")) verified++;
            if (verdict.contains("✘")) mismatched++;

            if (quiet) {
                System.out.println((verdict.contains("✔") ? "OK  " : verdict.contains("✘") ? "CHYBA " : "?   ")
                        + String.format("%-46s", shortType(result.typeLabel()))
                        + file.getName() + "  [" + result.summary().getOrDefault("ID datové zprávy", "?")
                        + "] " + result.summary().getOrDefault("Věc", "") + "  — " + verdict);
                continue;
            }

            System.out.println("=".repeat(100));
            System.out.println(file.getName() + "  (" + ContainerInspector.formatSize(bytes.length) + ")");
            for (Map.Entry<String, String> e : result.summary().entrySet()) {
                System.out.printf("  %-28s %s%n", e.getKey() + ":", e.getValue());
            }
            for (String warning : result.warnings()) {
                System.out.println("  ! " + warning);
            }
            print(result.root(), "  ");
        }

        System.out.println("-".repeat(100));
        System.out.println("souborů: " + files.size() + ", datových zpráv: " + messages
                + ", otisk souhlasí: " + verified + ", otisk nesouhlasí: " + mismatched
                + ", nerozpoznáno jako DZ: " + notMessages);
        byType.forEach((type, count) -> System.out.println("   " + count + "× " + type));
    }

    /** The type label without the "(ZFO)" every line would otherwise repeat. */
    private static String shortType(String typeLabel) {
        return typeLabel.replace(" (ZFO)", "").replace(", bez písemností", "");
    }

    /** Verdikt kontroly otisku z prvku razítka podání, kdekoli ve stromu; jinak „—“. */
    private static String hashVerdict(Node node) {
        String v = node.getInfo().get("Kontrola otisku zprávy");
        if (v != null) return v;
        for (Node child : node.getChildren()) {
            String found = hashVerdict(child);
            if (!"—".equals(found)) return found;
        }
        return "—";
    }

    private static void print(Node node, String indent) {
        for (Node child : node.getChildren()) {
            System.out.println(indent + (child.isDirectory() ? "[+] " : "    ") + child.getName()
                    + (child.getSize() >= 0 ? "  (" + ContainerInspector.formatSize(child.getSize()) + ")" : "")
                    + (child.getRole() != null ? "  — " + child.getRole() : "")
                    + (child.isAttachment() ? "  «písemnost»" : ""));
            for (Map.Entry<String, String> e : child.getInfo().entrySet()) {
                if ("Role".equals(e.getKey())) continue;
                System.out.printf("%s        %-40s %s%n", indent, e.getKey() + ":", e.getValue());
            }
            print(child, indent + "    ");
        }
    }
}
