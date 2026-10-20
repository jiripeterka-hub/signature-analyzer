package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.ContainerInspector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Co karta prvku v prohlížeči kontejnerů řekne o časovém razítku.
 *
 * <p>Pokyn uživatele ze 16. 9. 2026: u tokenu razítka má na kartě stát, kdy vzniklo, kdo je
 * vydal a do kdy platí certifikát, kterým je podepsáno — dosud tam byla jen role, OID atributu
 * a dva otisky. Čtenář se na ty tři věci ptá pokaždé a musel kvůli nim razítko uložit a otevřít
 * zvlášť.
 *
 * <p>Sonda existuje, protože se token do stromu dostane <b>třemi cestami</b> — nepodepsaným
 * atributem CMS (odloučený podpis, PAdES), samostatnou položkou {@code .tst} v ZIPu (ASiC)
 * a vlastností {@code etsiU} v JAdES — a z kódu není poznat, jestli všechny tři vedou k témuž
 * výpisu. Vypíše tedy každý uzel, jehož role mluví o razítku, i s tím, odkud pochází.
 *
 * <pre>
 *   java -Dstdout.encoding=UTF-8 -cp "app/target/test-classes;app/target/classes;$(cat cp.txt)" \
 *        cz.digitalnikontinuita.anep.tools.TimestampNodeProbe "cesta\k\souboru.p7s" ...
 * </pre>
 */
public final class TimestampNodeProbe {

    private TimestampNodeProbe() { }

    /** Řádky, kvůli kterým sonda vznikla — u nich se hlásí, jestli na kartě opravdu jsou. */
    private static final String[] WANTED = {
            "Čas dle razítka", "Vydavatel razítka", "Certifikát razítka platí do",
    };

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: TimestampNodeProbe <soubor>...");
            return;
        }
        for (String arg : args) {
            Path p = Path.of(arg);
            System.out.println("=== " + p.getFileName());
            ContainerInspector.Result result;
            try {
                result = ContainerInspector.inspect(Files.readAllBytes(p), p.getFileName().toString());
            } catch (Exception e) {
                System.out.println("    nepodařilo se otevřít: " + e);
                continue;
            }
            if (result == null || result.root() == null) {
                System.out.println("    není to kontejner");
                continue;
            }
            int found = 0;
            Deque<ContainerInspector.Node> queue = new ArrayDeque<>();
            queue.add(result.root());
            while (!queue.isEmpty()) {
                ContainerInspector.Node n = queue.poll();
                queue.addAll(n.getChildren());
                // Role bydlí na dvou místech: uzly z CMS a z JAdES ji dostávají do konstruktoru,
                // kdežto položkám ZIPu ji dopisuje annotateZipRoles do mapy údajů. Karta čte
                // obojí, takže i sonda musí.
                String role = n.getRole() != null ? n.getRole() : n.getInfo().get("Role");
                if (role == null || !role.contains("razítko")) continue;
                found++;
                System.out.println("  " + n.getDisplayName() + "  [" + role + "]");
                Map<String, String> info = n.getInfo();
                for (String key : WANTED) {
                    System.out.println("      " + (info.containsKey(key) ? "OK    " : "CHYBÍ ")
                            + key + (info.containsKey(key) ? " = " + info.get(key) : ""));
                }
                for (Map.Entry<String, String> e : info.entrySet()) {
                    boolean wanted = false;
                    for (String key : WANTED) wanted |= key.equals(e.getKey());
                    if (!wanted) System.out.println("      ·     " + e.getKey() + " = " + e.getValue());
                }
            }
            if (found == 0) System.out.println("    žádné razítko");
        }
    }
}
