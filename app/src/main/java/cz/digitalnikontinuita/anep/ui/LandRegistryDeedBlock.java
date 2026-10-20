package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.LandRegistryDeed.Assessment;
import cz.digitalnikontinuita.anep.ui.LandRegistryDeed.Signature;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Blok „Požadavky na vkladovou listinu“ — může dokument posloužit jako elektronická vkladová
 * listina pro vklad do katastru? Co se posuzuje a kdy se blok ukáže, stojí u
 * {@link LandRegistryDeed}.
 *
 * <p>Stojí mezi dokumentovými bloky Analýzy a je <b>vždy sbalený</b>, stejně jako ostatní
 * (rozhodnutí uživatele z 28. 9. 2026). Záhlaví je proto bezbarvé; výsledek nese barevně
 * podložená věta uvnitř — zelená, červená, nebo hnědožlutá jako u neurčeného verdiktu.
 *
 * <p>Kladný výsledek je co nejstručnější: vyjmenuje, čím který podpis požadavky splňuje.
 * Záporný a neurčený vypisují úplný výčet požadavků s tím, co splněno je a co ne — čtenář
 * u nich potřebuje vidět celý obraz, ne jen jednu chybu (pokyn uživatele 29. 9. 2026).
 */
final class LandRegistryDeedBlock {

    private LandRegistryDeedBlock() {}

    static final String TITLE = "Požadavky na vkladovou listinu";

    /** Úvodní věta nad hodnocením, s odkazy na zdroje požadavků. */
    static final String INTRO = "Pokud by právě vložený dokument PDF měl být použit jako"
            + " vkladová listina pro vklad do katastru, musí splňovat požadavky popsané"
            + " [na webu ČÚZK](https://cuzk.gov.cz/Katastr-nemovitosti/Zapisy-do-KN/"
            + "Podani-navrhu-na-vklad-v-elektronicke-podobe.aspx)"
            + " či v [tomto dokumentu](https://cuzk.gov.cz/Zememerictvi/Zememericke-cinnosti/"
            + "Overovani-vysledku-zememerickych-cinnosti-v-elektr/"
            + "letak_elektronicke-vklady_na-web.aspx).";

    private static final String NOT_ASSESSED_BY_ANEP =
            "AnEP posuzuje jen technické náležitosti; nedokáže posoudit věcný obsah ani to, zda"
            + " dokument podepsali všichni účastníci právního jednání.";

    /**
     * Hotový blok i se záhlavím, nebo {@code null}, když se nemá ukázat.
     *
     * @param reverify ověření s ústupky podatelny katastru; {@code null} (sondy) — rada pak
     *                 zůstane bez odkazu
     */
    static VBox build(ValidationResult result, Runnable reverify) {
        Assessment a = LandRegistryDeed.assess(result);
        if (a == null) return null;
        return AdvisorView.collapsibleBlock(TITLE, body(a, reverify), false,
                AdvisorView.BlockTint.PLAIN);
    }

    private static VBox body(Assessment a, Runnable reverify) {
        VBox body = new VBox(6);
        body.setPadding(new Insets(8, 12, 10, 12));
        body.getChildren().add(AdvisorView.richParagraph(INTRO));
        switch (a.verdict()) {
            case MEETS -> {
                body.getChildren().add(headline("Tento dokument splňuje požadavky, kladené na"
                        + " elektronickou vkladovou listinu pro vklad do katastru.", "#1a7a1a"));
                for (Signature s : a.signatures()) {
                    body.getChildren().add(bullet(0, "**" + s.name() + ":** " + s.legalType()
                            + concessionNote(s) + ", certifikát v dokumentu, " + identification(s)));
                }
                body.getChildren().add(bullet(0, "**kvalifikované časové razítko:** "
                        + timestamp(a.timestamp())));
                body.getChildren().add(bullet(0, "**obsah:** " + textLayer(a)));
                addConcessionsExplained(body, a);
                addNonReference(body, a);
                addOthers(body, a);
                body.getChildren().add(disclaimer());
            }
            case FAILS -> {
                body.getChildren().add(headline("Tento dokument nesplňuje požadavky, kladené na"
                        + " elektronickou vkladovou listinu pro vklad do katastru.", "#cc0000"));
                addChecklist(body, a, reverify);
                addNonReference(body, a);
                addOthers(body, a);
                body.getChildren().add(disclaimer());
            }
            case UNDECIDED -> {
                body.getChildren().add(headline("U tohoto dokumentu nelze posoudit, zda splňuje"
                        + " či nesplňuje požadavky, kladené na elektronickou vkladovou listinu"
                        + " do katastru.", "#9a6700"));
                addChecklist(body, a, reverify);
                addNonReference(body, a);
                addOthers(body, a);
                body.getChildren().add(disclaimer());
            }
        }
        return body;
    }

    private static final String MET = "**zde splněno**";

    private static String notMet(String why)  { return "!!zde nesplněno: " + why + "!!"; }

    private static String unknown(String why) { return "??" + why + "??"; }

    /**
     * Úplný výčet požadavků — pro „nesplňuje“ i „nelze posoudit“. U každého stojí jedno ze
     * tří označení: splněno (tučně černě), nesplněno (tučně červeně) a nezjištěno či zbývá
     * ověřit jinde (tučně hnědožlutě, barvou pruhu „nelze posoudit“). U „nesplňuje“ je
     * aspoň jeden požadavek červený; u „nelze posoudit“ žádný, jen hnědožluté — to, co AnEP
     * rozhodnout nedokáže: platnost podpisu, kterou se nepodařilo určit (od 8. 10. 2026 jakoukoli,
     * nejen kvůli prostředí, viz {@link LandRegistryDeed}), prokázání pravosti u podpisu bez
     * IK MPSV i bez čísla dokladu, a textová vrstva, když se soubor nepodařilo projít.
     */
    private static void addChecklist(VBox body, Assessment a, Runnable reverify) {
        body.getChildren().add(AdvisorView.richParagraph("Jednotlivé požadavky a jejich splnění:"));
        body.getChildren().add(bullet(0, "musí být ve formátu PDF nebo PDF/A – " + MET));
        body.getChildren().add(bullet(0, "nesmí jít o prostý sken (jen obrázky stránek bez"
                + " textové vrstvy) – " + (a.textLayer() == null
                        ? unknown("to se nepodařilo zjistit")
                        : a.plainScan()
                                ? notMet("dokument obsahuje jen obrázky stránek bez textové vrstvy")
                                : MET + " (" + textLayer(a) + ")")));

        List<String> validity = new ArrayList<>();
        for (Signature s : a.invalid()) validity.add(notMet("podpis " + s.name() + " je neplatný"));
        for (Signature s : a.sha1Rejected()) {
            validity.add(notMet("podpis " + s.name() + (s.indication().equals("TOTAL_PASSED")
                    ? " je platný jen díky toleranci k zastaralé hashovací funkci SHA-1"
                    : " používá zastaralou hashovací funkci SHA-1")
                    + ", kterou podatelna katastru nepřijímá"));
        }
        for (Signature s : a.doubtful()) {
            // Podmíněně: aplikace nezná verzi ani nastavení služby SecuSign (CLAUDE.md).
            validity.add(unknown("podpis " + s.name() + " je platný jen s ústupky, které podatelna"
                    + " katastru připouští až při ověření službou SecuSign Validation; ta ale může"
                    + " časové razítko hodnotit jen podle platnosti certifikátu, na kterém je"
                    + " založeno, a ten u žádného z razítek tohoto podpisu už neplatí"));
        }
        List<Signature> unconfirmed = a.unconfirmed();
        if (!unconfirmed.isEmpty()) {
            validity.add(unknown("u " + (unconfirmed.size() == 1 ? "podpisu " : "podpisů ")
                    + names(unconfirmed) + " se platnost nepodařilo určit")
                    + " (podrobnosti jsou na kartě podpisu)");
        }
        List<Signature> undetermined = a.undetermined();
        if (!undetermined.isEmpty()) {
            validity.add(unknown("u " + (undetermined.size() == 1 ? "podpisu " : "podpisů ")
                    + names(undetermined) + " se platnost nepodařilo určit, protože nebyly"
                    + " dostupné potřebné on-line údaje") + " – zkuste ověření zopakovat");
        }
        List<Signature> withConcessions = a.withConcessions();
        if (validity.isEmpty()) {
            validity.add(MET + (withConcessions.isEmpty() ? ""
                    : " (" + (withConcessions.size() == 1 ? "podpis " : "podpisy ")
                            + names(withConcessions) + " s ústupky, které podatelna katastru"
                            + " připouští)"));
        }
        body.getChildren().add(bullet(0, "všechny jeho elektronické podpisy musí být platné – "
                + String.join("; ", validity)));
        addUnknownValidityAdvice(body, a, reverify);
        addConcessionsExplained(body, a);

        List<String> qualified = new ArrayList<>();
        for (Signature s : a.notQualified()) {
            qualified.add(notMet("podpis " + s.name() + " není založen na kvalifikovaném"
                    + " certifikátu" + (s.legalType() != null ? " (jde o " + s.legalType() + ")" : "")));
        }
        body.getChildren().add(bullet(0, "všechny jeho elektronické podpisy musí být založeny"
                + " na kvalifikovaném certifikátu (tj. být uznávanými nebo kvalifikovanými"
                + " elektronickými podpisy) – "
                + (qualified.isEmpty() ? MET : String.join("; ", qualified))));

        List<String> embedded = new ArrayList<>();
        for (Signature s : a.notEmbedded()) {
            embedded.add(notMet("certifikát podpisu " + s.name() + " v dokumentu není"));
        }
        List<Signature> unknownEmbedded = a.embeddedUnknown();
        if (!unknownEmbedded.isEmpty()) {
            embedded.add(unknown("u " + (unknownEmbedded.size() == 1 ? "podpisu " : "podpisů ")
                    + names(unknownEmbedded) + " se to nepodařilo zjistit"));
        }
        body.getChildren().add(bullet(0, "všechny certifikáty, na kterých jsou podpisy založeny,"
                + " musí být obsaženy v dokumentu – "
                + (embedded.isEmpty() ? MET : String.join("; ", embedded))));

        body.getChildren().add(bullet(0, "pro každý podpis musí být splněna jedna z následujících"
                + " podmínek:"));
        body.getChildren().add(bullet(1, "certifikát obsahuje IK MPSV svého držitele"));
        body.getChildren().add(bullet(1, "certifikát obsahuje číslo osobního dokladu svého držitele"));
        body.getChildren().add(bullet(1, "certifikát je zapsán v základním registru obyvatel"
                + " – to AnEP nedokáže zkontrolovat"));
        body.getChildren().add(bulletWithBubble(1, "podpis je úředně ověřen na kontaktním místě"
                + " Czech POINT, u notáře nebo u advokáta – ověřovací doložku je nutné ověřit"
                + " samostatně", CLAUSE_HOWTO));
        body.getChildren().add(AdvisorView.richParagraph("Zde:"));
        for (Signature s : a.signatures()) {
            body.getChildren().add(bullet(1, "**" + s.name() + ":** " + (s.identified()
                    ? identification(s) + " – **splněno**"
                    : "certifikát neobsahuje IK MPSV ani číslo osobního dokladu – "
                            + unknown("zbývá registr obyvatel nebo úřední ověření"))));
        }

        body.getChildren().add(bullet(0, "na dokumentu musí být platné kvalifikované časové"
                + " razítko: buď dokumentové za posledním podpisem, nebo podpisové u posledního"
                + " podpisu – " + (a.timestamp() != null
                        ? MET + " (" + timestamp(a.timestamp()) + ")"
                        : notMet("k poslednímu podpisu (" + a.lastSignature().name()
                                + ") ani za ním není připojeno platné kvalifikované časové razítko"))));
    }

    /** Co podatelna katastru udělá s podpisem, který podle standardů neobstál. */
    private static final String HOW_THE_REGISTRY_VERIFIES = "Podatelna katastru ověřuje podpisy"
            + " nejprve podle standardů, bez ústupků. Pokud podpis takto neobstojí, zkouší ještě"
            + " službu SecuSign Validation, která připouští i revokační informace vydané po expiraci"
            + " certifikátu nebo od již expirovaného vydavatele a starší (nereferenční) formáty"
            + " podpisů – nikoli však zastaralou hashovací funkci SHA-1.";

    /**
     * Rada k podpisům neznámé platnosti. Dokud ověření s ústupky podatelny katastru neproběhlo,
     * nabídne ho; po něm zbývá jen vlastní zásoba revokačních informací služby SecuSign — a ta
     * má smysl jen tam, kde neznámá platnost stojí na revokačních informacích.
     */
    private static void addUnknownValidityAdvice(VBox body, Assessment a, Runnable reverify) {
        List<Signature> unknown = new ArrayList<>(a.unconfirmed());
        unknown.addAll(a.undetermined());
        if (unknown.isEmpty()) return;
        if (!a.concessionsTried()) {
            String text = HOW_THE_REGISTRY_VERIFIES + " Zkuste proto ověření s týmiž ústupky.";
            if (reverify == null) {
                body.getChildren().add(bullet(1, text));
                return;
            }
            HBox row = (HBox) bullet(1, text);
            javafx.scene.text.TextFlow flow = (javafx.scene.text.TextFlow) row.getChildren().get(1);
            javafx.scene.control.Hyperlink run = new javafx.scene.control.Hyperlink(
                    "Ověřit s ústupky podatelny katastru");
            run.setStyle("-fx-font-size: 12px; -fx-text-fill: #0a5ca8; -fx-padding: 0;"
                    + " -fx-border-width: 0;");
            run.setOnAction(e -> {
                run.setVisited(false);
                reverify.run();
            });
            flow.getChildren().addAll(new javafx.scene.text.Text("  "), run);
            body.getChildren().add(row);
            return;
        }
        boolean one = unknown.size() == 1;
        String text = "Ani s ústupky, které připouští podatelna katastru, se platnost "
                + (one ? "tohoto podpisu" : "těchto podpisů") + " nepodařilo potvrdit.";
        if (unknown.stream().anyMatch(Signature::revocation)) {
            text += " Podatelna ale využívá i službu SecuSign Validation, která má vlastní zásobu"
                    + " revokačních informací – s ní " + (one ? "může podpis" : "mohou podpisy")
                    + " obstát i tehdy, když se potřebné revokační informace jinak získat nedají.";
        }
        body.getChildren().add(bullet(1, text));
    }

    /** Výklad, proč podpis platný jen s ústupky požadavek splňuje. */
    private static void addConcessionsExplained(VBox body, Assessment a) {
        List<Signature> with = a.withConcessions();
        if (with.isEmpty()) return;
        boolean one = with.size() == 1;
        body.getChildren().add(AdvisorView.richParagraph((one ? "Podpis " : "Podpisy ")
                + names(with) + (one ? " je platný" : " jsou platné") + " jen díky ústupkům od"
                + " standardů. " + HOW_THE_REGISTRY_VERIFIES + " Takové ústupky tedy podatelna"
                + " katastru připouští."));
    }

    /** Za druhem podpisu: s jakými ústupky obstál, prázdné u podpisu platného podle standardů. */
    private static String concessionNote(Signature s) {
        if (s.concessions().isEmpty()) return "";
        return " (platný s ústupkem, který podatelna katastru připouští: "
                + s.concessions().stream().map(r -> r.concessionName())
                        .collect(Collectors.joining(", ")) + ")";
    }

    /**
     * Nereferenční formáty podatelna katastru zatím přijímá, ale chystá se to omezit
     * (informace podatelny, 8. 10. 2026). Na výsledek to vliv nemá; čtenář to ale má vědět dřív,
     * než dokument podá.
     */
    private static void addNonReference(VBox body, Assessment a) {
        List<Signature> non = a.nonReference();
        if (non.isEmpty()) return;
        boolean one = non.size() == 1;
        body.getChildren().add(AdvisorView.richParagraph((one ? "Podpis " : "Podpisy ")
                + names(non) + " " + (one ? "není" : "nejsou") + " v žádném z referenčních"
                + " formátů. " + unknown("Podatelna katastru takové podpisy zatím přijímá, ale"
                + " chystá se jejich přijímání omezit.")));
    }

    private static void addOthers(VBox body, Assessment a) {
        if (a.others().isEmpty()) return;
        boolean one = a.others().size() == 1;
        body.getChildren().add(AdvisorView.richParagraph((one
                ? "Na dokumentu je také další autentizační prvek, který není podpisem: "
                : "Na dokumentu jsou také další autentizační prvky, které nejsou podpisy: ")
                + String.join(", ", a.others()) + ". Požadavky na vkladovou listinu se týkají jen"
                + " podpisů, takže " + (one ? "tento prvek nemá" : "tyto prvky nemají")
                + " na posouzení vliv."));
    }

    /** Čím dokument požadavek „není prostý sken“ splňuje. */
    private static String textLayer(Assessment a) {
        return switch (a.textLayer()) {
            case TEXT -> "dokument má textovou vrstvu";
            case SCAN_WITH_OCR -> "jde o sken, ale s rozpoznaným textem (OCR)";
            case NO_TEXT_NOT_SCAN -> "dokument nemá textovou vrstvu, ale není skenem"
                    + " (například výkres s textem převedeným na křivky)";
            case PLAIN_SCAN -> "dokument je prostým skenem";
        };
    }

    private static String identification(Signature s) {
        List<String> parts = new ArrayList<>();
        if (s.ikMpsv() != null) parts.add("IK MPSV " + s.ikMpsv());
        if (s.identityDocument() != null) parts.add(s.identityDocument());
        return String.join(", ", parts);
    }

    private static String timestamp(TimestampEntry t) {
        String kind = "sig".equals(t.tsKind()) ? "podpisové, u posledního podpisu"
                : "doc".equals(t.tsKind()) ? "dokumentové, za posledním podpisem"
                : "za posledním podpisem";
        return kind + " (" + (t.issuer() != null ? t.issuer() + ", " : "")
                + TimeUtil.format(t.time()) + ")";
    }

    private static String names(List<Signature> sigs) {
        return sigs.stream().map(Signature::name).collect(Collectors.joining(", "));
    }

    /** Jak ověřit ověřovací doložku v AnEP — obsah bubliny u bodu o úředním ověření. */
    static final List<String> CLAUSE_HOWTO = List.of(
            "V případě úředního ověření podpisu u notáře je výstupem ASiC kontejner, který kromě"
            + " dokumentu s ověřovaným podpisem (vkladové listiny) obsahuje i ověřovací doložku."
            + " Tento kontejner je nutné vložit do aplikace AnEP, ověřit jeho platnost, a pak"
            + " (skrze prohlížeč kontejneru) ověřit platnost ověřovací doložky a posoudit její"
            + " obsah (že ověřuje příslušný podpis na dokumentu).",
            "V případě úředního ověření na CzechPOINTu (ať již u přepážky či advokátem) je nutné"
            + " vložit do aplikace přímo samostatný PDF dokument s ověřovací doložkou a nechat"
            + " ověřit platnost jeho podpisu/pečeti. Po ověření aplikace AnEP otevře samostatné"
            + " okno, do kterého lze vložit další dokument, na který se doložka odkazuje"
            + " prostřednictvím otisků: může se jednat přímo o dokument s ověřovaným podpisem,"
            + " nebo o podpisový arch. Aplikace ověří správnost otisků a tím i správnost vazby"
            + " mezi doložkou a odkazovaným dokumentem. V případě podpisového archu se postup"
            + " opakuje: po vložení archu do samostatného okna se ověří vazba doložky na"
            + " podpisový arch, načež se otevře další samostatné okno pro ověření vazby"
            + " podpisového archu na dokument s ověřovaným podpisem.");

    /**
     * Odrážka zakončená modrým odkazem <b>Podrobněji</b>, který pod sebou otevře bublinu
     * s výkladem. Bublina se zavře klepnutím kamkoli mimo ni — týž druh jako u revokačních
     * poznámek v Detailním rozboru.
     */
    private static Node bulletWithBubble(int level, String markup, List<String> paragraphs) {
        HBox row = (HBox) bullet(level, markup);
        javafx.scene.text.TextFlow flow = (javafx.scene.text.TextFlow) row.getChildren().get(1);
        javafx.scene.control.Hyperlink more = new javafx.scene.control.Hyperlink("Podrobněji");
        more.setStyle("-fx-font-size: 12px; -fx-text-fill: #0a5ca8; -fx-padding: 0;"
                + " -fx-border-width: 0;");
        more.setOnAction(e -> {
            more.setVisited(false);
            showBubble(more, paragraphs);
        });
        flow.getChildren().addAll(new javafx.scene.text.Text("  "), more);
        return row;
    }

    private static void showBubble(Node anchor, List<String> paragraphs) {
        javafx.stage.Popup popup = new javafx.stage.Popup();
        popup.setAutoHide(true);
        VBox box = new VBox(8);
        box.setMaxWidth(480);
        box.setStyle("-fx-background-color: #f4f8fc; -fx-border-color: #0a5ca8;"
                + " -fx-border-width: 1; -fx-border-radius: 4; -fx-background-radius: 4;"
                + " -fx-padding: 10;");
        for (String p : paragraphs) {
            Label l = new Label(p);
            l.setWrapText(true);
            l.setMaxWidth(460);
            l.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);   // jinak jeden řádek s „…“
            l.setStyle("-fx-font-size: 12px; -fx-text-fill: #111111;");
            box.getChildren().add(l);
        }
        popup.getContent().add(box);
        javafx.geometry.Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        popup.show(anchor, b.getMinX(), b.getMaxY() + 4);
    }

    /** Závěrečná výhrada — tučně a odsazená od výčtu nad ní, aby se nečetla jako jeho součást. */
    private static Node disclaimer() {
        javafx.scene.text.TextFlow flow = AdvisorView.richParagraph("**" + NOT_ASSESSED_BY_ANEP + "**");
        VBox.setMargin(flow, new Insets(10, 0, 0, 0));
        return flow;
    }

    private static Node bullet(int level, String markup) {
        HBox row = AdvisorView.bulletParagraph(markup);
        row.setPadding(new Insets(0, 0, 0, 18 * level));
        return row;
    }

    private static HBox headline(String text, String color) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #ffffff;");
        lbl.setWrapText(true);
        HBox box = new HBox(lbl);
        box.setPadding(new Insets(6, 10, 6, 10));
        box.setStyle("-fx-background-color: " + color + ";");
        return box;
    }
}
