package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector;
import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Link;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Presses the window's button and reports what came out of it.
 *
 * <p>That it compiles says nothing about whether the check runs: the verdict is painted from a
 * background task, the button disables itself while that task is in flight, and a window that
 * never re-enabled it would look finished and be dead. So the smoke does what the reader does —
 * hands the panel a file, fires the button, and reads the stripe and its colour off the result.
 *
 * <pre>java -cp … cz.digitalnikontinuita.anep.ui.LegalisationLinkSmoke &lt;dokument-s-odkazem&gt; &lt;odkazovany&gt; [&lt;jiny&gt;]</pre>
 */
public final class LegalisationLinkSmoke extends Application {

    private final Deque<Runnable> steps = new ArrayDeque<>();

    @Override
    public void start(Stage stage) {
        List<String> args = getParameters().getRaw();
        if (args.size() < 2) {
            System.out.println("usage: LegalisationLinkSmoke <dokument-s-odkazem> <odkazovany> [<jiny>]");
            Platform.exit();
            return;
        }
        File source = new File(args.get(0));
        File referenced = new File(args.get(1));
        File other = args.size() > 2 ? new File(args.get(2)) : null;

        Link link = LegalisationLinkInspector.read(source, null);
        System.out.println("== " + source.getName());
        if (link == null) {
            System.out.println("   ŽÁDNÝ ODKAZ — okno by se neotevřelo");
            Platform.exit();
            return;
        }
        System.out.println("   odkaz: příloha=" + link.attachmentName() + ", guid=" + link.guid()
                + ", typ=" + link.type() + ", cíl=" + link.targetName()
                + ", otisků=" + link.digests().size());

        steps.add(() -> run(source, link, referenced, "odkazovaný dokument"));
        if (other != null) steps.add(() -> run(source, link, other, "jiný dokument"));
        steps.add(Platform::exit);
        next();
    }

    private void next() {
        Runnable step = steps.poll();
        if (step != null) step.run();
    }

    private void run(File source, Link link, File candidate, String what) {
        System.out.println("-- " + what + ": " + candidate.getName());
        LegalisationLinkWindow.Panel panel = new LegalisationLinkWindow.Panel(null, source, link);

        System.out.println("   tlačítko před vložením: "
                + (panel.verifyButton().isDisable() ? "nepřístupné (správně)" : "PŘÍSTUPNÉ — chyba"));
        panel.accept(candidate);
        if (panel.verifyButton().isDisable()) {
            // Odmítnutý soubor: nic se nepočítá, takže na afterCheck by se čekalo marně.
            System.out.println("   soubor nepřijat — tlačítko zůstalo nepřístupné");
            report(panel.results());
            next();
            return;
        }
        System.out.println("   tlačítko po vložení:    přístupné (správně)");

        panel.afterCheck(() -> {
            report(panel.results());
            System.out.println("   tlačítko po ověření:    "
                    + (panel.verifyButton().isDisable() ? "NEPŘÍSTUPNÉ — okno by zůstalo zaseknuté"
                                                        : "přístupné (správně)"));
            // Řetěz: po shodě se má další okno otevřít samo, po neshodě čekat za tlačítkem.
            long open = javafx.stage.Window.getWindows().stream()
                    .filter(javafx.stage.Window::isShowing).count();
            System.out.println("   otevřených oken:        " + open);
            next();
        });
        // fire() is the button's own action path — the same one a click takes.
        panel.verifyButton().fire();
    }

    /** Prints the stripe with its colour, then the per-function lines under it. */
    private static void report(VBox results) {
        for (Node node : results.getChildren()) {
            if (node instanceof HBox row && row.getStyle().contains("-fx-background-color")) {
                String colour = row.getStyle().replaceAll(".*-fx-background-color: ([^;]+);.*", "$1");
                String text = row.getChildren().isEmpty() ? ""
                        : ((Label) row.getChildren().get(0)).getText();
                System.out.println("   PRUH [" + colour + "] " + text);
            } else if (node instanceof VBox box && !box.getChildren().isEmpty()
                    && box.getChildren().get(0) instanceof Label head) {
                System.out.println("      " + head.getText());
            } else if (node instanceof HBox row && !row.getChildren().isEmpty()
                    && row.getChildren().get(0) instanceof javafx.scene.control.Button b) {
                System.out.println("   TLAČÍTKO: " + b.getText());
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
