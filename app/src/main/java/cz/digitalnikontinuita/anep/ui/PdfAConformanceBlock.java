package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.PdfAConformanceChecker.Conformance;
import cz.digitalnikontinuita.anep.core.PdfAConformanceChecker.Violation;
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
 * Blok „Shoda s normou PDF/A" — dostál dokument prohlášení, které o sobě nese?
 *
 * <p>Stojí <b>jen v Analýze</b>, nad kartami prvků: je to nález o dokumentu, ne o autentizačním
 * prvku. Zjednodušený ani Rozšířený přehled o shodě s PDF/A nemluví — rozhodnutí uživatele
 * ze 17. 9. 2026. Ukazuje se jen u dokumentů, které nějaký profil PDF/A deklarují; u ostatních
 * není co prověřovat a mlčení je správná odpověď.
 *
 * <p><b>Odstín je záměrně bezbarvý</b> ({@link AdvisorView.BlockTint#PLAIN}). Barevná záhlaví
 * v Analýze nesou verdikt o platnosti a tenhle blok verdikt nenese ani nést nesmí: nekonformní
 * PDF/A je pořád platně podepsaný dokument. Červené záhlaví by to popřelo dřív, než si čtenář
 * přečte první větu.
 *
 * <p><b>Nikdy se nerozbaluje sám.</b> Odpovídá na otázku, kterou si u konkrétního dokumentu
 * položí jen někdo, a otevřený by nad kartami prvků zabíral místo tomu, kvůli čemu se záložka
 * otevírá.
 *
 * <p>O tom, že úkony AnEP archivní podobu dokumentu zachovají, se tu <b>nepíše</b>. Patří to
 * do Digitální kontinuity, do bloku o nabízeném úkonu: tam aplikace ví, který úkon se
 * doopravdy nabízí, a může ho pojmenovat místo podmiňovacího způsobu.
 */
final class PdfAConformanceBlock {

    private PdfAConformanceBlock() {}

    /** Popisková sloupec stejné šířky, jakou mají řádky ostatních bloků v Analýze. */
    private static final double LBL_WIDTH = 170;

    static boolean isPresent(ValidationResult result) {
        return result != null && result.getPdfConformance() != null;
    }

    static String title() {
        return "Shoda s normou PDF/A";
    }

    /** Hotový blok i se záhlavím, nebo {@code null}, není-li co prověřovat. */
    static VBox build(ValidationResult result) {
        if (!isPresent(result)) return null;
        // Zavřený vždy, i když dokument deklaraci nedostál. Blok odpovídá na otázku, kterou si
        // u konkrétního dokumentu položí jen někdo, a nad kartami prvků by otevřený zabíral
        // místo tomu, kvůli čemu se záložka otevírá. Rozhodnutí uživatele z 17. 9. 2026.
        return AdvisorView.collapsibleBlock(title(), body(result.getPdfConformance()), false,
                AdvisorView.BlockTint.PLAIN);
    }

    private static VBox body(Conformance c) {
        VBox body = new VBox(3);
        body.setPadding(new Insets(6, 12, 10, 12));

        body.getChildren().add(paragraph(intro(c)));
        body.getChildren().add(row("Ověřováno proti:", specification(c.flavour())));

        if (!c.inconclusive() && !c.compliant() && !c.violations().isEmpty()) {
            Separator sep = new Separator();
            sep.setPadding(new Insets(6, 0, 4, 0));
            body.getChildren().add(sep);

            Label head = new Label("Porušená pravidla");
            head.setStyle("-fx-font-weight: bold; -fx-text-fill: #003189; -fx-font-size: 12px;");
            body.getChildren().add(head);
            for (Violation v : c.violations()) {
                body.getChildren().add(paragraph(describe(v)));
            }
        }

        body.getChildren().add(paragraph(outro(c)));
        return body;
    }

    // ------------------------------------------------------------------ texty

    /** Jak se profil jmenuje ve větě: z „2b" vyjde „PDF/A-2b". */
    static String name(String flavour) {
        return "PDF/A-" + flavour;
    }

    /** Které znění ISO 19005 se na ten profil vztahuje. */
    static String specification(String flavour) {
        String part = flavour.isEmpty() ? "" : flavour.substring(0, 1);
        String iso = switch (part) {
            case "1" -> "ISO 19005-1:2005";
            case "2" -> "ISO 19005-2:2011";
            case "3" -> "ISO 19005-3:2012";
            case "4" -> "ISO 19005-4:2020";
            default -> "ISO 19005";
        };
        return iso + " — " + name(flavour);
    }

    static String intro(Conformance c) {
        String profile = name(c.flavour());
        if (c.inconclusive()) {
            return "Dokument se hlásí k " + profile + ", ale shodu s tou normou se nepodařilo"
                + " ověřit: validátor nad tímto souborem selhal (" + c.failure() + ")."
                + "\n\nNeznamená to, že dokument normě nevyhovuje — znamená to, že se to odsud"
                + " neví. Neúspěch nástroje není nález o dokumentu.";
        }
        if (c.compliant()) {
            return "Dokument se hlásí k " + profile + " a podmínky té normy splňuje. Prohlášení"
                + " v jeho metadatech tedy odpovídá skutečnosti.";
        }
        int n = c.violations().size();
        String rules = n == 0 ? "" : n == 1 ? " Porušuje jedno její pravidlo."
                : " Porušuje " + CzechPlural.count(n, "její pravidlo", "její pravidla",
                        "jejích pravidel") + ".";
        return "Dokument se hlásí k " + profile + ", ale podmínky té normy nesplňuje." + rules
            + "\n\nNení to nic výjimečného. Prohlášení do souboru zapisuje nástroj, který ho"
            + " vytvořil, a nikdo je nekontroluje; pozdější zásahy do dokumentu je navíc můžou"
            + " nechat zastarat.";
    }

    /** Jedno porušené pravidlo: odkaz do normy, kolikrát, a jak zní. */
    static String describe(Violation v) {
        String count = v.occurrences() > 1 ? "  (" + v.occurrences() + "×)" : "";
        String text = v.message() == null || v.message().isBlank() ? "" : "\n" + v.message();
        return "• " + v.reference() + count + text;
    }

    static String outro(Conformance c) {
        String base =
            "Shoda s PDF/A nemá vliv na výsledek ověření podpisů a pečetí. Norma popisuje, jak"
            + " má být dokument uložen, aby ho bylo možné věrně zobrazit i za desítky let;"
            + " o tom, kdo a čím ho podepsal, neříká nic. Dokument, který normě nevyhovuje,"
            + " může být bez výhrad platně podepsaný — a naopak.";
        if (c.inconclusive()) return base;
        String tool =
            "\n\nOvěřuje validátor veraPDF, referenční implementace sdružení PDF Association"
            + " a Open Preservation Foundation. Ověřuje se výhradně proti profilu, ke kterému"
            + " se dokument sám přihlásil. Znění porušených pravidel je citováno z normy"
            + " anglicky tak, jak je vydává veraPDF, i s číslem článku — aby se dalo dohledat"
            + " v originálu.";
        if (c.compliant()) {
            return base
                + "\n\nStrojová kontrola přitom nepokrývá všechno: část podmínek PDF/A úrovně A"
                + " (přístupnost, správné pořadí čtení, jazyk obsahu) posoudit strojem nelze"
                + " a žádný validátor je nekontroluje.";
        }
        return base + tool;
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
