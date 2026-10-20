package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.ui.CrlView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Opravdu se tabulka v prohlížeči CRL dá seřadit a prohledat?
 *
 * <p>Uživatel 15. 9. 2026 ohlásil, že řazení podle sloupců zmizelo. Zmizelo: {@code FilteredList}
 * předaný {@code TableView} přímo se řadit nedá a kliknutí do záhlaví neudělá nic. Java to
 * přeloží, okno se otevře, sloupce vypadají jako dřív — pozná se to jedině stiskem.
 *
 * <p>Sonda proto <b>nekreslí, nýbrž tiskne</b>: otevře skutečné okno, programově nastaví řazení
 * podle prvního sloupce a přečte první řádek; pak napíše do hledacího pole a přečte počet řádků.
 * Viz poznámka o ovládacích prvcích JavaFX — ověřovat vykreslením tuhle vadu neumí.
 *
 * <pre>java -cp … CrlTableSmoke &lt;soubor.crl&gt;</pre>
 */
public final class CrlTableSmoke extends Application {

    private static Path crl;

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: CrlTableSmoke <soubor.crl>");
            System.exit(2);
        }
        crl = Path.of(args[0]);
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        CrlView.show(Files.readAllBytes(crl), null, null, null);
        Platform.runLater(() -> {
            Window window = Window.getWindows().stream()
                    .filter(w -> w instanceof Stage st && "Seznam CRL".equals(st.getTitle()))
                    .findFirst().orElse(null);
            if (window == null) {
                System.out.println("OKNO SE NEOTEVRELO");
                Platform.exit();
                System.exit(1);
            }
            List<Node> found = new ArrayList<>();
            collect(window.getScene().getRoot(), found);
            TableView<?> table = (TableView<?>) found.stream()
                    .filter(n -> n instanceof TableView).findFirst().orElse(null);
            TextField search = (TextField) found.stream()
                    .filter(n -> n instanceof TextField).findFirst().orElse(null);
            if (table == null || search == null) {
                System.out.println("TABULKA NEBO HLEDANI CHYBI");
                Platform.exit();
                System.exit(1);
            }

            int total = table.getItems().size();
            System.out.println("zaznamu celkem: " + total);
            System.out.println("prvni pred razenim : " + first(table));

            @SuppressWarnings("unchecked")
            TableColumn<Object, ?> col = (TableColumn<Object, ?>) table.getColumns().get(0);
            col.setSortType(TableColumn.SortType.DESCENDING);
            ((TableView<Object>) table).getSortOrder().setAll(col);
            ((TableView<Object>) table).sort();
            String desc = first(table);
            System.out.println("prvni po sestupnem: " + desc);

            col.setSortType(TableColumn.SortType.ASCENDING);
            ((TableView<Object>) table).sort();
            String asc = first(table);
            System.out.println("prvni po vzestupnem: " + asc);

            boolean sortWorks = !desc.equals(asc);
            System.out.println(sortWorks ? "RAZENI FUNGUJE" : "RAZENI NEFUNGUJE");

            search.setText(normalized(asc));
            int filtered = table.getItems().size();
            System.out.println("po hledani \"" + normalized(asc) + "\": " + filtered
                    + " z " + total);
            boolean searchWorks = filtered >= 1 && filtered < total;

            search.setText("");
            boolean restored = table.getItems().size() == total;
            System.out.println(searchWorks && restored
                    ? "HLEDANI FUNGUJE" : "HLEDANI NEFUNGUJE");

            Platform.exit();
            System.exit(sortWorks && searchWorks && restored ? 0 : 1);
        });
    }

    private static String first(TableView<?> t) {
        return t.getItems().isEmpty() ? "(prazdno)"
                : String.valueOf(t.getColumns().get(0).getCellData(0));
    }

    /** Hledané číslo bez oddělovačů — ať se ověří i to, že na nich nezáleží. */
    private static String normalized(String serialHex) {
        return serialHex.replace(" ", "").replace(":", "");
    }

    private static void collect(Parent parent, List<Node> out) {
        for (Node n : parent.getChildrenUnmodifiable()) {
            out.add(n);
            if (n instanceof Parent p) collect(p, out);
        }
    }
}
