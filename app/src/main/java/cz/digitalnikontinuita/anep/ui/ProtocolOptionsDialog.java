package cz.digitalnikontinuita.anep.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.File;

/**
 * Dvě otázky před vygenerováním protokolu: přiložit ověřovaný soubor a orazítkovat.
 *
 * <h2>Proč se ptát, a ne to dělat vždycky</h2>
 *
 * <p>Obojí má cenu, kterou nemá smysl platit pokaždé. Příloha nafoukne protokol o celý ověřovaný
 * soubor a <b>vynutí PDF/A-3 místo PDF/A-2</b> (PDF/A-2 přílohu, která sama není PDF/A, zakazuje —
 * ISO 19005-2, klauzule 6.8); razítko potřebuje síť, chvíli trvá a u zdroje, který si účtuje za
 * kus, i něco stojí. Obě odpovědi jsou proto ve výchozím stavu <b>ne</b>.
 *
 * <p>Otázka na razítko se nenabídne, když aplikace nemá čím razítkovat — bez vybraného zdroje
 * v nastavení by to bylo zaškrtávátko, které nic neudělá.
 */
final class ProtocolOptionsDialog {

    private ProtocolOptionsDialog() { }

    /**
     * @return odpovědi, nebo {@code null}, když uživatel export odvolal
     */
    static PrintUtil.ProtocolOptions ask(Stage owner, File sigFile, File origFile,
                                          PrintUtil.ProtocolExtras extras) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Generovat protokol");
        dialog.setHeaderText("Co má protokol obsahovat?");

        ButtonType ok = new ButtonType("Generovat", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        CheckBox attach = new CheckBox(attachLabel(sigFile, origFile));
        attach.setSelected(false);
        boolean haveFiles = sigFile != null || origFile != null;
        attach.setDisable(!haveFiles);

        // Kolik dokumentů z eLegalizace se přiloží spolu s ověřovaným souborem. Musí to zaznít
        // tady: uživatel zaškrtává „vložit ověřovaný soubor“ a dostal by víc, než co si přečetl.
        java.util.List<File> retez = PrintUtil.confirmedLegalisationChain(sigFile);
        Label attachNote = note(haveFiles
                ? "Protokol pak ponese i soubor, o kterém vypovídá, takže se dá později ověřit"
                  + " znovu z jediného souboru. Vysází se ale jako PDF/A-3 místo PDF/A-2:"
                  + " archivní norma PDF/A-2 přílohu, která sama není PDF/A, nepřipouští."
                  + (retez.isEmpty() ? ""
                        : " Spolu s ním se přiloží i " + pocetDokumentu(retez.size())
                          + ", na které se odkazuje otisky z eLegalizace a jejichž otisky"
                          + " souhlasí.")
                : "Ověřovaný soubor není k dispozici, takže není co přiložit.");

        String tsa = extras != null ? extras.tsaName() : null;
        boolean canStamp = extras != null && tsa != null && !tsa.isBlank();
        // Přívlastek se řídí tím, co se o vybraném zdroji naměřilo. Nad testovací autoritou by
        // „kvalifikovaným" bylo nepravdivé, a je to věta, kterou si uživatel přečte v okamžiku
        // rozhodování — tedy to nejhorší místo, kde slibovat víc, než co přijde.
        boolean unqualified = extras != null
                && extras.tsaQuality() == cz.digitalnikontinuita.anep.core.TsaProfile.Quality.UNQUALIFIED;
        CheckBox stamp = new CheckBox(unqualified
                ? "Opatřit protokol elektronickým časovým razítkem"
                : "Opatřit protokol kvalifikovaným elektronickým časovým razítkem");
        stamp.setSelected(false);
        stamp.setDisable(!canStamp);

        Label stampNote = note(!canStamp
                ? "Žádný zdroj časových razítek není v nastavení vybrán, takže razítko pořídit"
                  + " nelze."
                : "Razítko doloží, kdy protokol vznikl a že se od té doby nezměnil. Pořídí se"
                  + " od zdroje vybraného v nastavení (" + tsa + "), takže je potřeba připojení"
                  + " k síti a generování chvíli potrvá."
                  + (unqualified
                        ? " Tento zdroj ale vydává razítka, která nejsou kvalifikovaná —"
                          + " aplikace k němu v důvěryhodných seznamech kvalifikovanou službu"
                          + " nenašla. Razítko na protokolu tedy nebude tím, co se za"
                          + " kvalifikované razítko uznává; k doložení naostro vyberte"
                          + " v nastavení jiný zdroj."
                        : ""));

        VBox body = new VBox(4, attach, attachNote, new Label(" "), stamp, stampNote);
        body.setPadding(new Insets(6, 4, 2, 4));
        body.setPrefWidth(520);
        dialog.getDialogPane().setContent(body);

        ButtonType answer = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (answer != ok) return null;
        return new PrintUtil.ProtocolOptions(attach.isSelected() && haveFiles,
                                             stamp.isSelected() && canStamp);
    }

    /** Jednotné číslo, nebo dvojice — u odloučeného podpisu jsou to dva soubory, ne jeden. */
    /** „jeden dokument“ / „dva dokumenty“ / „5 dokumentů“ — shoda podle počtu. */
    private static String pocetDokumentu(int n) {
        if (n == 1) return "jeden dokument";
        if (n >= 2 && n <= 4) return n + " dokumenty";
        return n + " dokumentů";
    }

    private static String attachLabel(File sigFile, File origFile) {
        return sigFile != null && origFile != null
                ? "Vložit ověřované soubory do protokolu jako přílohu (podpis i podepsaný obsah)"
                : "Vložit ověřovaný soubor do protokolu jako přílohu";
    }

    private static Label note(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: #555555;");
        l.setPadding(new Insets(0, 0, 0, 22));
        return l;
    }
}
