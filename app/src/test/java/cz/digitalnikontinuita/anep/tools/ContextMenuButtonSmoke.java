package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.ExplorerContextMenu;
import cz.digitalnikontinuita.anep.ui.SettingsDialog;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Tlačítko „Přidat do nabídky“ / „Odebrat z nabídky“ v Nastavení stisknuté doopravdy
 * ({@code fire()}), s kontrolou registru po každém stisku, a oprava položky po přesunu přenosné
 * verze ({@link ExplorerContextMenu#repairIfMoved}). Napsáno 22. 11. 2026.
 *
 * <p>Zapisuje do {@code HKCU\Software\Classes\*\shell\AnEP} a na konci položku odebere — pouštět
 * jen tam, kde ji uživatel přidanou nemá. Cestu k programu dává {@code -Djpackage.app-path},
 * jako ji dává spouštěč sestavené aplikace.
 *
 * <pre>java -Djpackage.app-path=…\AnEP.exe --module-path … -cp … ContextMenuButtonSmoke</pre>
 */
public final class ContextMenuButtonSmoke extends Application {

    @Override
    public void start(Stage stage) {
        new Thread(() -> {
            int failures = 0;
            try {
                Path exe = ExplorerContextMenu.currentExe();
                System.out.println("exe          = " + exe);
                if (ExplorerContextMenu.read().userEntry()) {
                    System.out.println("PŘERUŠENO: položka už existuje, sonda by ji odebrala");
                    Platform.exit();
                    return;
                }
                onFx(() -> {
                    Stage owner = new Stage();
                    owner.show();
                    Platform.runLater(() -> SettingsDialog.show(owner, AppSettings.load(),
                            () -> "Hotovo.", null));
                });
                Button add = waitForButton("Přidat do nabídky");
                onFx(add::fire);
                Button remove = waitForButton("Odebrat z nabídky");
                var afterAdd = ExplorerContextMenu.read();
                System.out.println("po přidání   = " + afterAdd.userExe()
                        + "  pozn.: " + noteText());
                if (!exe.equals(afterAdd.userExe())) failures++;

                onFx(remove::fire);
                waitForButton("Přidat do nabídky");
                var afterRemove = ExplorerContextMenu.read();
                System.out.println("po odebrání  = " + afterRemove.userExe());
                if (afterRemove.userEntry()) failures++;

                // Přesunutá přenosná verze: položka vede na neexistující soubor.
                ExplorerContextMenu.install(Path.of("C:\\neexistuje\\AnEP.exe"));
                System.out.println("před opravou = " + ExplorerContextMenu.read().userExe());
                boolean repaired = ExplorerContextMenu.repairIfMoved();
                var afterRepair = ExplorerContextMenu.read();
                System.out.println("oprava       = " + repaired + " → " + afterRepair.userExe());
                if (!repaired || !exe.equals(afterRepair.userExe())) failures++;
                // Existující cíl se nepřepisuje.
                if (ExplorerContextMenu.repairIfMoved()) failures++;
                ExplorerContextMenu.uninstall();
                System.out.println("úklid        = " + ExplorerContextMenu.read().userEntry());
            } catch (Throwable t) {
                t.printStackTrace(System.out);
                failures++;
            }
            System.out.println(failures == 0 ? "VÝSLEDEK: OK" : "VÝSLEDEK: CHYB " + failures);
            // Modální dialog zavřít dřív, než skončí JavaFX — jinak glass.dll při ukončení spadne.
            Platform.runLater(() -> {
                for (Window w : new java.util.ArrayList<>(Window.getWindows())) {
                    if (w instanceof Stage s) s.close();
                }
                Platform.exit();
            });
        }, "smoke").start();
    }

    private static void onFx(Runnable r) throws Exception {
        CompletableFuture<Void> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { r.run(); f.complete(null); } catch (Throwable t) { f.completeExceptionally(t); }
        });
        f.get();
    }

    private static Button waitForButton(String text) throws Exception {
        for (int i = 0; i < 200; i++) {
            CompletableFuture<Button> f = new CompletableFuture<>();
            Platform.runLater(() -> f.complete(find(text)));
            Button b = f.get();
            if (b != null && !b.isDisabled()) return b;
            Thread.sleep(100);
        }
        throw new IllegalStateException("tlačítko „" + text + "“ se neobjevilo");
    }

    private static String noteText() throws Exception {
        CompletableFuture<String> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            String found = null;
            for (Window w : Window.getWindows()) {
                if (w.getScene() == null) continue;
                found = findNote(w.getScene().getRoot());
                if (found != null) break;
            }
            f.complete(found);
        });
        return f.get();
    }

    private static Button find(String text) {
        for (Window w : Window.getWindows()) {
            if (w.getScene() == null) continue;
            Button b = find(w.getScene().getRoot(), text);
            if (b != null) return b;
        }
        return null;
    }

    private static Button find(Node n, String text) {
        if (n instanceof Button b && text.equals(b.getText())) return b;
        if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
            return find(sp.getContent(), text);
        }
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                Button b = find(c, text);
                if (b != null) return b;
            }
        }
        return null;
    }

    private static String findNote(Node n) {
        if (n instanceof Label l && l.getText() != null && l.getText().startsWith("Položka je")) {
            return l.getText();
        }
        if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
            return findNote(sp.getContent());
        }
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                String s = findNote(c);
                if (s != null) return s;
            }
        }
        return null;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
