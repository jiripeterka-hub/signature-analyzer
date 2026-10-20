package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.OfficeSignaturePeek;
import cz.digitalnikontinuita.anep.tools.OfficePeekProbe;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;

import java.io.File;
import java.util.concurrent.CountDownLatch;

/**
 * Vysadí obsah okna pro zadaný soubor a vypíše, co v něm skutečně stojí — řádek po řádku.
 *
 * <p>Sonda existuje proto, že model se dá odměřit sondou {@link OfficePeekProbe}, ale to ještě
 * neznamená, že se údaj do okna dostal. Tady se staví tytéž prvky, které uvidí uživatel, jen se
 * místo vykreslení přečte jejich text. Okno se neotevírá.
 *
 * <p>Spouští se ručně: {@code java ... OfficePeekWindowProbe <cesta k dokumentu>}.
 */
public final class OfficePeekWindowProbe {

    private OfficePeekWindowProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("použití: OfficePeekWindowProbe <cesta k podepsanému dokumentu>");
            return;
        }
        File file = new File(args[0]);
        OfficeSignaturePeek.Finding finding = OfficeSignaturePeek.peek(file);
        if (finding == null) {
            System.out.println("v souboru není podpis kancelářského formátu");
            return;
        }

        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                Parent content = OfficePeekDialog.buildContent(file, finding, null);
                print(content, 0);
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
    }

    private static void print(Node node, int depth) {
        if (node instanceof Label l) {
            String text = l.getText();
            if (text != null && !text.isBlank()) {
                System.out.println("  ".repeat(depth) + text);
            }
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) print(child, depth + 1);
        }
    }
}
