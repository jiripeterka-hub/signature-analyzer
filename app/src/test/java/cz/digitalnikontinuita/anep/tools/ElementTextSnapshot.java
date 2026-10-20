package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.ContainerInspector;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;

/**
 * Snímek okna prohlížeče certifikátů — a k němu čísla o tom, jestli se hodnoty zalomily.
 *
 * <p>Do 16. 9. 2026 bylo okno jedinou {@code TextArea} bez zalamování a dlouhá hodnota z něj
 * vybíhala (nález uživatele). Že to přestalo platit, se z kódu vyčíst nedá: rozhoduje o tom
 * rozvržení mřížky, šířka sloupců a to, jestli {@code selectable()} dopočítá výšku podle počtu
 * zalomených řádek. Sonda proto okno opravdu postaví, nechá ho rozvrhnout a vypíše u každé
 * hodnoty, na kolik řádek vyšla a jak je široká proti šířce okna.
 *
 * <p>Certifikát si bere ze skutečného souboru — z kontejneru, podpisu CMS nebo holého
 * {@code .cer}; hledá v něm první prvek, který se dá přečíst jako certifikát.
 *
 * <pre>
 *   java -Dstdout.encoding=UTF-8 -cp "app/target/test-classes;app/target/classes;$(cat cp.txt)" \
 *        --module-path "%JAVAFX%" --add-modules javafx.controls,javafx.graphics \
 *        cz.digitalnikontinuita.anep.tools.ElementTextSnapshot -out okno.png "cesta\k\souboru.p7s"
 * </pre>
 */
public final class ElementTextSnapshot {

    private ElementTextSnapshot() { }

    public static void main(String[] args) throws Exception {
        Path out = null;
        String source = null;
        // Užší okno zalomí i hodnoty, které se do výchozí šířky vejdou — tedy totéž, co udělá
        // dlouhé DN, jen se na to nemusí hledat certifikát, který ho má.
        double width = 0;
        boolean ocsp = false;
        for (int i = 0; i < args.length; i++) {
            if ("-out".equals(args[i])) out = Path.of(args[++i]);
            else if ("-width".equals(args[i])) width = Double.parseDouble(args[++i]);
            else if ("-ocsp".equals(args[i])) ocsp = true;
            else source = args[i];
        }
        if (source == null) {
            System.out.println("usage: ElementTextSnapshot [-ocsp] [-width <px>] [-out <png>] <soubor>");
            return;
        }

        byte[] der = firstElement(Files.readAllBytes(Path.of(source)), source, ocsp);
        if (der == null) {
            System.out.println("v souboru se nenašel " + (ocsp ? "údaj OCSP" : "certifikát"));
            return;
        }
        String text = ocsp ? ContainerInspector.describeOcspResponse(der)
                           : ContainerInspector.describeCertificate(der);
        System.out.println("### popis má " + text.split("\n", -1).length + " řádků, "
                + ContainerInspector.splitRows(text).size() + " po rozebrání");

        final Path png = out;
        final double sceneWidth = width;
        final boolean isOcsp = ocsp;
        final String windowTitle = ocsp ? "Odpověď OCSP" : "Certifikát";
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            cz.digitalnikontinuita.anep.ui.ElementTextView.show(windowTitle, text, der,
                    isOcsp ? "ocsp-odpoved.ors" : "certifikat.cer",
                    windowTitle, isOcsp ? "*.ors" : "*.cer", null);
            // Zúžit až po ustálení, ne hned. Hned po show() si JavaFX okno ještě samo rozměřuje
            // a šířku zadanou v témž kole přebije — změřeno: okno se vrátilo na 860 px a snímek
            // pořízený „při 360" ukazoval plnou šířku. Tohle je zároveň věrnější: uživatel taky
            // tahá za okraj hotového okna.
            // Okno je nemodální a show() se vrací hned; rozvržení a zalomení textu dokončí až
            // běh smyčky. Kol se čeká hodně: po změně šířky si každá hodnota přepočítává výšku
            // ve dvou odložených kolech, nová výška posune řádek mřížky, ten může změnit šířku
            // sloupce — a celé to jede znovu. Změřeno: nad certifikátem se {@code fitToContent}
            // zavolá 96krát a správná čísla padnou až v posledních kolech.
            after(ROUNDS / 3, () -> {
                Stage opened = Stage.getWindows().stream()
                        .filter(w -> w instanceof Stage).map(w -> (Stage) w)
                        .filter(s -> windowTitle.equals(s.getTitle()))
                        .findFirst().orElse(null);
                // Užší scéna, ne užší okno ani strop na kořeni. Obojí bylo zkoušeno a obojí
                // nechalo obsah v původní šířce: {@code stage.setWidth()} scénu nepřekreslí
                // (okno hlásilo 360 px, scéna dál 860) a kořeni scény {@code maxWidth}
                // nepomůže, protože scéna svůj kořen roztahuje na svou velikost. Tentýž strom
                // uzlů vsazený do nové, užší scény se zalomí přesně tak, jak se zalomí
                // uživateli, který okno zúžil.
                if (opened != null && sceneWidth > 0) {
                    javafx.scene.Parent content = opened.getScene().getRoot();
                    opened.setScene(new javafx.scene.Scene(content, sceneWidth, 620));
                }
                after(ROUNDS, () -> {
                Stage stage = Stage.getWindows().stream()
                        .filter(w -> w instanceof Stage).map(w -> (Stage) w)
                        .filter(s -> windowTitle.equals(s.getTitle()))
                        .findFirst().orElse(null);
                if (stage == null) {
                    System.out.println("okno se neotevřelo");
                    done.countDown();
                    return;
                }
                Node root = stage.getScene().getRoot();
                // Snímek napřed, měření až po něm: {@code snapshot()} si vynutí úplný průchod
                // CSS a rozvržením, kdežto {@code selectable()} dopočítává výšku hodnoty ve
                // dvou odložených kolech. Po změně šířky okna tedy čtení výšek před snímkem
                // vrací stav z předchozí šířky — změřeno, hlásilo jednu zalomenou hodnotu tam,
                // kde jich na obrázku bylo šest.
                if (png != null) {
                    try {
                        WritableImage img = root.snapshot(null, null);
                        ImageIO.write(toBufferedImage(img), "png", new File(png.toString()));
                        System.out.println("### snímek: " + png);
                    } catch (Exception e) {
                        System.out.println("snímek se nepodařil: " + e);
                    }
                } else {
                    root.applyCss();
                    ((javafx.scene.Parent) root).layout();
                }
                System.out.printf("### okno %.0f px, scéna %.0f px, nejmenší možná šířka obsahu"
                        + " %.0f px%n", stage.getWidth(), stage.getScene().getWidth(),
                        root instanceof javafx.scene.layout.Region r ? r.minWidth(-1) : -1);
                report(root, stage.getScene().getWidth());
                done.countDown();
                });
            });
        });
        done.await();
        Platform.exit();
    }

    /** Kolik kol smyčky se počká, než se okno vyfotí a změří. */
    private static final int ROUNDS = Integer.getInteger("anep.snapshot.rounds", 60);

    /** Spustí úkol až po {@code rounds} kolech smyčky událostí. */
    private static void after(int rounds, Runnable task) {
        if (rounds <= 0) task.run();
        else Platform.runLater(() -> after(rounds - 1, task));
    }

    /** Kolik řádek zabrala která hodnota a jestli se některá nevešla do šířky okna. */
    private static void report(Node root, double sceneWidth) {
        int values = 0, wrapped = 0, tooWide = 0;
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            if (n instanceof javafx.scene.Parent p) queue.addAll(p.getChildrenUnmodifiable());
            if (!(n instanceof javafx.scene.control.TextArea ta)) continue;
            values++;
            double w = ta.getWidth();
            double h = ta.getHeight();
            // Kolik místa si text uvnitř skinu opravdu vzal. Je-li to víc než výška prvku,
            // spodní řádka se nevykreslí — přesně to, na co se sonda ptá.
            double textHeight = 0;
            for (Node t : ta.lookupAll(".text")) textHeight += t.getLayoutBounds().getHeight();
            // Řádka je při 12px neproporcionálním písmu kolem 15 px; přesnost tu nehraje roli,
            // jde o to odlišit jednu řádku od několika.
            int lines = (int) Math.max(1, Math.round(h / 15.0));
            if (lines > 1) wrapped++;
            if (w > sceneWidth) tooWide++;
            String value = ta.getText();
            System.out.printf("  %2d řádk%s  šířka %4.0f  výška %3.0f / text %3.0f %s  %s%n",
                    lines, lines == 1 ? "a " : "y ", w, h, textHeight,
                    textHeight > h + 1 ? "USEKNUTO" : "        ",
                    value.length() > 40 ? value.substring(0, 37) + "…" : value);
        }
        System.out.println("### hodnot=" + values + ", zalomených=" + wrapped
                + ", širších než okno=" + tooWide);
    }

    /** První prvek souboru, který se dá přečíst jako certifikát či odpověď OCSP — ať leží kdekoli. */
    private static byte[] firstElement(byte[] bytes, String name, boolean ocsp) {
        if (readable(bytes, ocsp)) return bytes;
        ContainerInspector.Result result;
        try {
            result = ContainerInspector.inspect(bytes, name);
        } catch (Exception e) {
            return null;
        }
        if (result == null || result.root() == null) return null;
        Deque<ContainerInspector.Node> queue = new ArrayDeque<>();
        queue.add(result.root());
        while (!queue.isEmpty()) {
            ContainerInspector.Node n = queue.poll();
            queue.addAll(n.getChildren());
            byte[] data = n.getData();
            if (data != null && data.length > 0 && readable(data, ocsp)) return data;
        }
        return null;
    }

    private static boolean readable(byte[] data, boolean ocsp) {
        return (ocsp ? ContainerInspector.describeOcspResponse(data)
                     : ContainerInspector.describeCertificate(data)) != null;
    }

    private static java.awt.image.BufferedImage toBufferedImage(WritableImage img) {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        java.awt.image.BufferedImage out =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader px = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) out.setRGB(x, y, px.getArgb(x, y));
        }
        return out;
    }
}
