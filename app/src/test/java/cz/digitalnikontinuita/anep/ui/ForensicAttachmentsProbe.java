package cz.digitalnikontinuita.anep.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Sonda okna, které před vytvořením forenzního balíčku z ověřeného dokumentu nabízí přiložit
 * další soubory (10. 11. 2026). Tlačítka skutečně stiskne ({@code fire()}):
 *
 * <ol>
 *   <li>„Zrušit“ — okno vrátí {@code null}, balíček se tedy nevytvoří;</li>
 *   <li>přidání dvou souborů a dokumentu samého, odebrání jednoho, „Vytvořit balíček“ — vrátí
 *       jedinou přílohu; dokument se mezi přílohy nedostal.</li>
 * </ol>
 *
 * <p>Výběr souborů systémovým oknem sonda neřídí; soubory přidává cestou, kterou jde i výsledek
 * toho okna a přetažení ({@code addFiles}). Potřebuje JavaFX na module-path.
 */
public final class ForensicAttachmentsProbe extends Application {

    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage primary) throws Exception {
        primary.setScene(new Scene(new StackPane(), 200, 100));
        primary.show();

        Path dir = Files.createTempDirectory("anep-prilohy-");
        File document = Files.writeString(dir.resolve("dokument.pdf"), "d").toFile();
        File a = Files.writeString(dir.resolve("příloha č. 1.txt"), "a").toFile();
        File b = Files.writeString(dir.resolve("zpráva.txt"), "bb").toFile();
        List<File> already = List.of(document);

        boolean ok = true;

        // 1. Zrušit
        Platform.runLater(() -> button("Zrušit").fire());
        List<File> cancelled = ForensicPackageDialog.askAttachments(primary, document, already);
        System.out.println("Zrušit            -> " + cancelled);
        ok &= cancelled == null;

        // 2. Přidat, odebrat, vytvořit
        Platform.runLater(() -> {
            @SuppressWarnings("unchecked")
            ListView<File> list = (ListView<File>) dialog().getScene().lookup("#attachments");
            ForensicPackageDialog.addFiles(list, List.of(a, b, document, a, dir.toFile()), already);
            System.out.println("po přidání        -> " + names(list.getItems()));
            System.out.println("Odebrat bez výběru vypnuto: " + button("Odebrat").isDisabled());
            list.getSelectionModel().select(b);
            button("Odebrat").fire();
            System.out.println("po odebrání       -> " + names(list.getItems()));
            button("Vytvořit balíček").fire();
        });
        List<File> chosen = ForensicPackageDialog.askAttachments(primary, document, already);
        System.out.println("Vytvořit balíček  -> " + (chosen == null ? null : names(chosen)));
        ok &= chosen != null && chosen.size() == 1 && chosen.get(0).equals(a);

        System.out.println(ok ? "VÝSLEDEK: v pořádku" : "VÝSLEDEK: CHYBA");
        Platform.exit();
    }

    private static Stage dialog() {
        return (Stage) Window.getWindows().stream()
                .filter(w -> w instanceof Stage s && "Forenzní balíček".equals(s.getTitle()))
                .findFirst().orElseThrow();
    }

    private static Button button(String text) {
        return dialog().getScene().getRoot().lookupAll(".button").stream()
                .map(n -> (Button) n).filter(x -> text.equals(x.getText()))
                .findFirst().orElseThrow();
    }

    private static List<String> names(List<File> files) {
        return files.stream().map(File::getName).toList();
    }
}
