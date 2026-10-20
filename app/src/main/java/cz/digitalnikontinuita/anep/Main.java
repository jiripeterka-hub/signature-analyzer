package cz.digitalnikontinuita.anep;

import cz.digitalnikontinuita.anep.core.StartupTrace;
import cz.digitalnikontinuita.anep.ui.MainView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point. Boots the JavaFX UI which lets a user pick a PDF, an ASiC
 * container, or a raw/detached signature object (CMS or XML) and shows the
 * DSS validation result for it.
 */
public class Main extends Application {

    @Override
    public void start(Stage primaryStage) {
        StartupTrace.mark("Application.start() entered");
        Thread.currentThread().setContextClassLoader(AppClassLoader.get());
        MainView mainView = new MainView();
        StartupTrace.mark("MainView constructed");
        Scene scene = new Scene(mainView.getRoot(), 980, 640);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());

        primaryStage.setTitle("AnEP (Analyzátor elektronických podpisů)");
        List<Image> appIcons = List.of(
                new Image(getClass().getResourceAsStream("/app-icon.png")),
                new Image(getClass().getResourceAsStream("/app-icon-48.png")),
                new Image(getClass().getResourceAsStream("/app-icon-16.png")));
        primaryStage.getIcons().addAll(appIcons);
        // Ikonu dostane každé další okno samo. Prohlížeč kontejneru, příloh, evidenčního záznamu,
        // revokačních údajů, nastavení, odpojená záložka — okno otevírá čtrnáct míst ve třinácti
        // souborech a všechna kromě odpojené záložky se dosud hlásila obecnou ikonou systému.
        //
        // Hlídá se seznam oken od JavaFX, ne aby to čtrnáct míst dělalo samo: příště by na to
        // patnácté zapomnělo. Táž úvaha jako u zavírání oken o pár řádků níž.
        //
        // Prázdný seznam je podmínka, ne opatrnost: okno, které si ikonu nastavilo samo, se tím
        // nepřepíše. Popupy (bubliny, kontextová menu) nejsou Stage, takže se jich to netýká.
        Window.getWindows().addListener((javafx.collections.ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window w : change.getAddedSubList()) {
                    if (w instanceof Stage st && st.getIcons().isEmpty()) {
                        st.getIcons().addAll(appIcons);
                    }
                }
            }
        });
        primaryStage.setScene(scene);
        // Zavřením hlavního okna končí celá aplikace. Odpojené záložky i prohlížeče — kontejnerů,
        // příloh, evidenčních záznamů, revokačních údajů — jsou samostatná okna, takže by jinak
        // zůstala viset nad aplikací, která už nemá kde ověřovat, a JavaFX by běžel dál, protože
        // se ukončuje teprve se zavřením posledního okna.
        //
        // Bere se seznam oken od JavaFX, ne vlastní evidence: okno otevírá jedenáct míst v deseti
        // souborech a evidence by se rozešla při prvním dalším. Kopie seznamu je nutná — zavírání
        // ten živý seznam mění pod rukama. Filtr na Stage vynechá bubliny a kontextová menu, která
        // se zavírají sama.
        primaryStage.setOnHidden(e -> {
            for (Window w : new ArrayList<>(Window.getWindows())) {
                if (w != primaryStage && w instanceof Stage other) other.close();
            }
        });
        primaryStage.show();
        StartupTrace.mark("stage shown");
        mainView.startWarmup();

        // Startup measurement aid: -Danep.exitAfterStartup=<ms> closes the app on its own,
        // which is what lets a profiling run (or a CDS archive dump) end cleanly.
        long exitAfter = Long.getLong("anep.exitAfterStartup", 0L);
        if (exitAfter > 0) {
            Thread t = new Thread(() -> {
                try { Thread.sleep(exitAfter); } catch (InterruptedException ignored) { }
                javafx.application.Platform.exit();
                // Hard exit: background workers (trust-list refresh, warm-up) would otherwise
                // keep a measurement run alive, and a CDS archive is only written on real exit.
                System.exit(0);
            }, "startup-exit");
            t.setDaemon(true);
            t.start();
        }
    }

    public static void main(String[] args) {
        StartupTrace.mark("main() entered");
        // JavaFX replaces the context classloader on its Application Thread with its
        // own module classloader, which doesn't see classpath jars. DSS's ServiceLoader
        // calls happen on background threads that inherit this broken classloader, so
        // SPI providers like dss-cms-object are never found. Capture the real classloader
        // here (before JavaFX starts) and restore it inside start().
        AppClassLoader.set(Thread.currentThread().getContextClassLoader());
        launch(args);
    }
}
