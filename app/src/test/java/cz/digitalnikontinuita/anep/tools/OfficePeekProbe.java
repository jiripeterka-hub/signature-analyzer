package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.OfficeSignaturePeek;
import cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.Finding;
import cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.Revocation;
import cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.Signature;
import cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.Timestamp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Sonda, ne test: projede podepsané kancelářské dokumenty ze sbírky a vypíše, co z nich
 * {@link OfficeSignaturePeek} přečte. Nic netvrdí a nic neselže — slouží k tomu, aby se
 * nová pole (úroveň, razítka, revokační údaje) posoudila na skutečných souborech, ne na
 * vlastní představě o tom, jak vypadají.
 *
 * <p>Spouští se ručně jako {@code main}, ne jako test — stejně jako ostatní sondy v tomhle
 * balíčku. Testem být nesmí: nad sbírkou běží desítky vteřin a na stroji bez ní by nehlídala nic.
 * Cesty se předávají v prvním argumentu nebo v {@code -Danep.probe.roots}, jinak se vezmou
 * obvyklé dva adresáře.
 */
public final class OfficePeekProbe {

    private OfficePeekProbe() { }

    public static void main(String[] args) throws Exception {
        String roots = args.length > 0 ? args[0]
                : System.getProperty("anep.probe.roots", "D:\\tmp,D:\\Downloads");
        List<Path> files = new ArrayList<>();
        for (String r : roots.split(",")) {
            Path root = Path.of(r.trim());
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> OfficeSignaturePeek.isCandidate(p.toFile()))
                        .forEach(files::add);
            }
        }
        if (files.isEmpty()) {
            System.out.println("PROBE: sbírka není k dispozici, nic k měření");
            return;
        }

        int signed = 0;
        int withHorizon = 0;
        int withRevocations = 0;
        java.util.Map<String, Integer> levels = new java.util.TreeMap<>();
        for (Path p : files) {
            Finding f;
            try {
                f = OfficeSignaturePeek.peek(p.toFile());
            } catch (Exception e) {
                System.out.println("PROBE CHYBA " + p.getFileName() + ": " + e);
                continue;
            }
            if (f == null) continue;
            signed++;
            for (Signature s : f.signatures()) {
                levels.merge(s.level().name(), 1, Integer::sum);
                Timestamp last = s.lastTimestamp();
                if (last != null && last.certNotAfter() != null) withHorizon++;
                if (!s.revocations().isEmpty()) withRevocations++;
                System.out.printf("%-14s ts=%d %-28s rev=%d%n",
                        s.level().name(), s.timestamps().size(),
                        last == null ? "bez razítka"
                                : last.kind().label() + " do "
                                  + (last.certNotAfter() != null ? last.certNotAfter() : "?"),
                        s.revocations().size());
                for (Timestamp t : s.timestamps()) {
                    System.out.printf("      razítko %-10s %s  neporušené=%s  cert do %s%n",
                            t.kind().label(), t.time(), t.intact(), t.certNotAfter());
                }
                for (Revocation r : s.revocations()) {
                    System.out.printf("      %-4s %s  %s%n",
                            r.kindLabel(), r.produced(), r.issuer());
                }
                System.out.println("      " + p.getFileName());
            }
        }
        System.out.println("=== SOUHRN ===");
        System.out.println("kandidátů: " + files.size() + ", podepsaných: " + signed);
        System.out.println("úrovně: " + levels);
        System.out.println("s horizontem (cert razítka čitelný): " + withHorizon);
        System.out.println("s čitelnými revokačními údaji: " + withRevocations);
    }
}
