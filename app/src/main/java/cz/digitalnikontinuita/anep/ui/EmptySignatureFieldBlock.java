package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.PdfEmptySignatureFieldDetector.EmptyField;
import cz.digitalnikontinuita.anep.core.PdfFieldVisibility;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Blok „Prázdné podpisové pole" — připravené, ale dosud nepodepsané pole v PDF.
 *
 * <p>Jde o nález o dokumentu, ne o autentizačním prvku: dokument nemusí mít jediný podpis
 * a přesto tenhle blok mít, takže se nedá pověsit na kartu podpisu.
 *
 * <p><b>Stojí jen v Rozšířeném přehledu.</b> Do 22. 9. 2026 se stavěl i v Analýze, a to ve zcela
 * totožné podobě — kdo si prošel obě záložky, četl dvakrát totéž. Pokyn uživatele: nechat ho
 * tam, kde se vypisují vlastnosti dokumentu a prvků, a z Analýzy vypustit. Blok zůstává
 * společný, jen ho staví jediný volající.
 *
 * <p>Blok se zobrazuje jen tehdy, když v dokumentu nějaké prázdné pole je, a je rozbalený.
 * Ostatní bloky karty jsou zavřené, protože odpovídají na otázku, kterou u konkrétního dokumentu
 * klade jen někdo; tenhle naopak oznamuje něco, co čtenář nečekal a co jinde v aplikaci nenajde —
 * DSS o prázdném poli mlčí, protože z jeho pohledu žádný podpis k ověření není.
 */
final class EmptySignatureFieldBlock {

    private EmptySignatureFieldBlock() {}

    /** Popisková sloupec stejné šířky, jakou mají řádky ostatních bloků v přehledu. */
    private static final double LBL_WIDTH = 170;

    static boolean isPresent(ValidationResult result) {
        return result != null && !result.getEmptySignatureFields().isEmpty();
    }

    /** „Prázdné podpisové pole" / „Prázdná podpisová pole" podle počtu. */
    static String title(ValidationResult result) {
        int n = result.getEmptySignatureFields().size();
        return n == 1 ? "Prázdné podpisové pole" : "Prázdná podpisová pole";
    }

    /**
     * Hotový blok i se záhlavím, nebo {@code null}, když v dokumentu žádné prázdné pole není.
     * Volající ho vloží tak, jak je.
     */
    static VBox build(ValidationResult result) {
        if (!isPresent(result)) return null;
        return AdvisorView.collapsibleBlock(title(result), body(result), true);
    }

    private static VBox body(ValidationResult result) {
        List<EmptyField> fields = result.getEmptySignatureFields();
        VBox body = new VBox(3);
        body.setPadding(new Insets(6, 12, 10, 12));

        body.getChildren().add(paragraph(intro(fields.size())));

        for (int i = 0; i < fields.size(); i++) {
            EmptyField f = fields.get(i);
            // Čára i před prvním polem: nad ním stojí úvodní odstavec, ne jiné pole.
            Separator sep = new Separator();
            sep.setPadding(new Insets(6, 0, 4, 0));
            body.getChildren().add(sep);
            if (fields.size() > 1) {
                Label head = new Label("Pole č. " + (i + 1));
                head.setStyle("-fx-font-weight: bold; -fx-text-fill: #003189; -fx-font-size: 12px;");
                body.getChildren().add(head);
            }
            body.getChildren().add(row("Název pole:", f.fieldName()));
            body.getChildren().add(row("Umístění:", placement(f)));
            body.getChildren().add(row("Podpis v něm bude:",
                    FieldVisibilityText.forecast(f.visibility())));
            body.getChildren().add(row("Příznaky anotace:", f.visibility().describeFlags()));
            if (f.required()) {
                body.getChildren().add(row("Vyplnění:",
                        "pole je v dokumentu označeno jako povinné (Required)"));
            }
            if (f.lock() != null) {
                body.getChildren().add(row("Po podpisu uzamkne:", plannedLock(f.lock())));
            }
        }

        Separator tail = new Separator();
        tail.setPadding(new Insets(8, 0, 4, 0));
        body.getChildren().add(tail);
        body.getChildren().add(paragraph(OUTRO));
        return body;
    }

    // ------------------------------------------------------------------ texty

    static String intro(int n) {
        String what = n == 1
                ? "Dokument obsahuje jedno podpisové pole, které je připravené, ale zatím nevyplněné."
                : "Dokument obsahuje " + CzechPlural.count(n, "podpisové pole",
                        "podpisová pole", "podpisových polí")
                  + ", která jsou připravená, ale zatím nevyplněná.";
        return what
            + " Prázdné pole není podpis a na výsledek ověření nemá vliv — je to místo, které"
            + " autor dokumentu vyhradil pro podpis někoho dalšího."
            + "\n\nJak bude budoucí podpis vypadat, je přitom dané už teď: rozhoduje o tom velikost"
            + " pole a jeho příznaky zobrazení. Podepisovací nástroj obojí přebírá a sám nemění —"
            + " přidá k tomu jen zámek proti pozdějšímu posunutí pole. Následující řádky proto"
            + " o vzhledu mluví předem.";
    }

    private static final String OUTRO =
            "Vzhled se řídí příznaky anotace podle ISO 32000-1, tabulky 165: příznak Hidden"
            + " vylučuje zobrazení i tisk, příznak NoView jen zobrazení na obrazovce (tiskne se"
            + " dál, je-li nastaven Print) a bez příznaku Print se anotace nikdy netiskne."
            + " Pole nulové velikosti nemá kam kreslit, takže je neviditelné bez ohledu na"
            + " příznaky — tak se obvykle zakládá záměrně neviditelný podpis.";

    /**
     * Co pole zamkne, až ho někdo podepíše.
     *
     * <p>Vlastní text, ne {@code ExtendedView.pdfLockNote()}: ten mluví o zámku, který už padl,
     * a tady se teprve chystá. Věta v minulém čase by u prázdného pole tvrdila, že dokument je
     * uzamčený — a on není, dokud ho někdo nepodepíše.
     */
    static String plannedLock(SignatureSummary.PdfLock lock) {
        List<String> names = lock.fields();
        String named = String.join(", ", names);
        // Pořadí i důvod jsou stejné jako u už podepsaného pole — viz
        // ExtendedView.lockPermissionClause(). Nese-li zámek /P, je řeč o dokumentu; rozsah polí
        // se dodává, jen když jsou vyjmenovaná, protože „všechna pole“ vedle /P nepřidává nic.
        String permission = switch (lock.permission() != null ? lock.permission() : "") {
            case "NO_CHANGE_PERMITTED" ->
                "celý dokument — po podepsání tohoto pole už v něm nebudou povoleny žádné"
                + " další změny";
            case "MINIMAL_CHANGES_PERMITTED" ->
                "obsah celého dokumentu — povoleno pak zůstane jen vyplňování formulářových polí"
                + " a připojování dalších podpisů a pečetí";
            case "CHANGES_PERMITTED" ->
                "obsah celého dokumentu — povoleno pak zůstane vyplňování formulářových polí,"
                + " připojování dalších podpisů a pečetí a vkládání či úprava poznámek (anotací)";
            case "" -> null;
            default -> "celý dokument (míra povolených změn je zapsána neznámou hodnotou "
                       + lock.permission() + ")";
        };
        String fields = switch (lock.scope() != null ? lock.scope() : "") {
            case "ALL" -> permission == null ? "všechna formulářová pole dokumentu" : null;
            case "INCLUDE" -> names.isEmpty()
                    ? "formulářová pole vyjmenovaná v dokumentu"
                    : (names.size() == 1 ? "formulářové pole " : "formulářová pole ") + named;
            case "EXCLUDE" -> names.isEmpty()
                    ? "všechna formulářová pole dokumentu"
                    : "všechna formulářová pole kromě " + named;
            default -> permission == null
                    ? "formulářová pole v rozsahu, který se nepodařilo zjistit" : null;
        };
        if (permission == null) return fields;
        return fields == null ? permission : permission + "; uzamčeno bude " + fields;
    }

    /** „strana 1, 452 × 71 bodů" — kde pole je a jak je velké. */
    static String placement(EmptyField f) {
        PdfFieldVisibility v = f.visibility();
        if (v.zeroArea()) {
            String where = f.page() > 0 ? "strana " + f.page() + ", " : "";
            return where + "nulová velikost (pole nemá kreslicí plochu)";
        }
        String size = size(v.width()) + " × " + size(v.height()) + " bodů"
                + " (" + mm(v.width()) + " × " + mm(v.height()) + " mm)";
        return f.page() > 0
                ? "strana " + f.page() + ", " + size
                : size + ", pole není navázáno na žádnou stránku";
    }

    private static String size(float pt) {
        return String.format(java.util.Locale.ROOT, "%.0f", pt);
    }

    /** 1 bod = 1/72 palce. */
    private static String mm(float pt) {
        return String.format(java.util.Locale.ROOT, "%.0f", pt * 25.4f / 72f);
    }

    // ------------------------------------------------------------------ prvky

    private static HBox row(String labelText, String value) {
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111; -fx-font-size: 12px;");
        lbl.setMinWidth(LBL_WIDTH);
        lbl.setMaxWidth(LBL_WIDTH);
        lbl.setWrapText(true);
        TextArea val = ResultView.selectable(value, "-fx-text-fill: #111111; -fx-font-size: 12px;");
        HBox.setHgrow(val, Priority.ALWAYS);
        HBox box = new HBox(8, lbl, val);
        box.setPadding(new Insets(1, 0, 1, 0));
        return box;
    }

    private static HBox paragraph(String text) {
        TextArea val = ResultView.selectable(text, "-fx-text-fill: #111111; -fx-font-size: 12px;");
        HBox.setHgrow(val, Priority.ALWAYS);
        HBox box = new HBox(val);
        box.setPadding(new Insets(2, 0, 2, 0));
        return box;
    }
}
