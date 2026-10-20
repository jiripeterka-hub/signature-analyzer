package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.ContainerInspector;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Node;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Co prohlížeč kontejnerů udělá s každým souborem JSON v zadaných adresářích.
 *
 * <p>Od 14. 9. 2026 se JSON nezobrazuje jako jeden kus textu, ale rozebírá — což znamená, že
 * <b>každý</b> soubor JSON, na který aplikace narazí, projde novým rozborem. Sonda je tu proto,
 * aby se to dalo změřit místo odhadovat: kolik souborů se pozná jako JAdES, kolik jich zůstane
 * obyčejným JSON (a musí zůstat — konfigurační soubor se nesmí vydávat za podpis) a jestli
 * některý neshodí rozbor výjimkou.
 *
 * <p>Změřeno při zavedení nad {@code D:\tmp}, {@code D:\Downloads} a sbírkou na OneDrive:
 * 35 souborů, 26 JAdES (obalující i oddělené, jeden s protipodpisem), 7 obyčejných JSON,
 * 0 výjimek.
 *
 * <p>Nesahá na DSS ani na síť, takže běží v sekundách a nepotřebuje důvěryhodné seznamy.
 *
 * <pre>java -cp … JadesCorpusScan &lt;adresář&gt; [&lt;adresář&gt; …]</pre>
 */
public final class JadesCorpusScan {

    /** Hlouběji se nechodí; korpusy jsou ploché a hlubší zanoření jsou zálohy a mezivýsledky. */
    private static final int MAX_DEPTH = 4;

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: JadesCorpusScan <adresář> [<adresář> …]");
            return;
        }
        int seen = 0;
        int jades = 0;
        int plain = 0;
        int failed = 0;
        for (String root : args) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(Path.of(root), MAX_DEPTH)) {
                files = walk.filter(Files::isRegularFile).filter(JadesCorpusScan::looksLikeJson).toList();
            } catch (Exception e) {
                System.out.println("ADRESÁŘ\t" + root + "\t" + e);
                continue;
            }
            for (Path file : files) {
                seen++;
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    if (ContainerInspector.detectKind(bytes) != ContainerInspector.Kind.JSON) continue;
                    Result result = ContainerInspector.inspect(bytes, file.getFileName().toString());
                    if (result.typeLabel().contains("JAdES")) {
                        jades++;
                        System.out.println("JADES\t" + result.typeLabel()
                                + "\t" + result.summary().get("Podepsaný obsah")
                                + "\tprvků=" + count(result.root()) + "\t" + file);
                    } else {
                        plain++;
                        System.out.println("JSON\t" + result.typeLabel() + "\t" + file);
                    }
                } catch (Throwable t) {
                    // Výjimka je tu nález, ne nehoda: rozbor se volá z vlákna, které kreslí okno.
                    failed++;
                    System.out.println("VÝJIMKA\t" + file + "\t" + t);
                }
            }
        }
        System.out.println("souborů=" + seen + "  JAdES=" + jades + "  obyčejný JSON=" + plain
                + "  výjimek=" + failed);
    }

    private static boolean looksLikeJson(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".json") || name.endsWith(".jws") || name.endsWith(".jades");
    }

    private static int count(Node node) {
        int total = 0;
        for (Node child : node.getChildren()) total += 1 + count(child);
        return total;
    }
}
