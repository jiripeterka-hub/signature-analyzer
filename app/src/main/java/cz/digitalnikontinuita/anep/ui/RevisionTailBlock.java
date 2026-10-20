package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.RevisionTail;
import cz.digitalnikontinuita.anep.model.ValidationResult;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Blok „Co v souboru přibylo po podpisu" — je za posledním kryjícím prvkem ještě něco?
 *
 * <p>Stojí nad kartami prvků: je to nález o dokumentu, ne o jednotlivém podpisu. Sourozenec
 * {@link PdfAConformanceBlock} a staví se stejně — {@code build()} vrací {@code null}, není-li
 * co říct.
 *
 * <p><b>Kde stojí, záleží na podobě</b> (rozhodnutí uživatele 9. 10. 2026). Změna obsahu je
 * varování, které patří vedle verdiktu: blok stojí v <b>Rozšířeném přehledu</b> pro všechny,
 * rozbalený a s hnědožlutým záhlavím, a Zjednodušený přehled na něj upozorní jedním řádkem
 * ({@link #warningLine}). Klidná podoba — přibyla jen validační data — zůstává v Analýze
 * v expertním módu. Obě místa najednou ne: blok o prázdných podpisových polích ukázal, že
 * totéž dvakrát čtenář čte jako dvě různé věci.
 *
 * <p><b>Mlčení je ta častější odpověď a je to tak správně.</b> Ze 179 souborů z {@code D:\tmp},
 * které mají kryjící prvek, sahá poslední z nich na konec souboru u <b>118</b>; tam se za ním
 * nestalo nic a blok by opakoval „nic". Rozhodnutí uživatele z 18. 9. 2026: ukázat se má jen tam,
 * kde je co sdělit. Co je „nic", rozhoduje {@link RevisionTail#of}.
 *
 * <p><b>Dvě různě vážné věci pod jedním nadpisem.</b> Obvyklý případ je, že za posledním prvkem
 * přibyla validační data — obsah se nezměnil a blok o tom mluví klidně. Vzácnější a vážný je, že
 * se změnil obsah stránek; tehdy nadpis mluví jinak a blok se <b>otevře sám</b>. Je to jediný
 * blok v Analýze, který se otevírá sám, a má to důvod: čtenář, který o téhle možnosti neví, ji
 * neotevře, protože ho nenapadne, že by měl.
 *
 * <p><b>Odstín nenese verdikt.</b> Ani změna obsahu po podpisu neznamená, že je podpis neplatný —
 * podepsané bajty zůstaly nedotčené a otisk sedí. Červené záhlaví by to popřelo dřív, než si
 * čtenář přečte první větu. Změněný obsah má proto od 9. 10. 2026 záhlaví hnědožluté
 * ({@link AdvisorView.BlockTint#BROWN}) — „pozor“, ne „neplatné“; klidná podoba zůstává
 * u {@link AdvisorView.BlockTint#PLAIN}.
 */
final class RevisionTailBlock {

    private RevisionTailBlock() { }

    static boolean isPresent(ValidationResult result) {
        return result != null && result.getRevisionTail() != null;
    }

    static VBox build(ValidationResult result) {
        return build(result, null);
    }

    /**
     * Hotový blok i se záhlavím, nebo {@code null}, není-li co říct.
     *
     * @param openPage otevře náhled na dané straně (číslováno od jedné) v podobě, kterou kryje
     *                 podpis. Je-li {@code null} — sondy, zkoušky, dokument bez náhledu — vysází
     *                 se čísla stran jako prostý text. Očekávání uživatele z 18. 9. 2026:
     *                 „předpokládám, že tam bude možnost prokliknout se na zobrazení těch
     *                 stránek". Je to nejpřímější cesta od nálezu k tomu, co ho způsobilo.
     */
    static VBox build(ValidationResult result, IntConsumer openPage) {
        return build(result, openPage, null);
    }

    /**
     * @param openCurrent otevře náhled v <b>dnešní</b> podobě na dané straně — druhá polovina
     *                    srovnání k {@code openPage} (pokyn uživatele 9. 10. 2026: „nabídni
     *                    proklik na rychlý náhled výsledného stavu“). {@code null} — bez odkazu.
     */
    static VBox build(ValidationResult result, IntConsumer openPage, IntConsumer openCurrent) {
        if (!isPresent(result)) return null;
        RevisionTail tail = result.getRevisionTail();
        return AdvisorView.collapsibleBlock(title(tail), body(tail, openPage, openCurrent),
                tail.contentChanged(), tail.contentChanged()
                        ? AdvisorView.BlockTint.BROWN : AdvisorView.BlockTint.PLAIN);
    }

    /** Změnil se po podpisu obsah? Podle toho blok stojí v Rozšířeném přehledu, nebo v Analýze. */
    static boolean contentChanged(ValidationResult result) {
        return isPresent(result) && result.getRevisionTail().contentChanged();
    }

    /**
     * Jeden řádek pro Zjednodušený přehled, nebo {@code null}, nezměnil-li se obsah.
     *
     * <p>Zjednodušený přehled se po ověření otevírá první a ukazuje zelené „platný“ — právě
     * tam by čtenář bez upozornění uvěřil, že dokument je ten podepsaný. Celý blok se tam
     * neopakuje, jen se na něj odkáže (rozhodnutí uživatele 9. 10. 2026).
     */
    static String warningLine(ValidationResult result) {
        if (!contentChanged(result)) return null;
        List<Integer> pages = result.getRevisionTail().changedPages();
        String where = pages.size() == 1
                ? "strana " + pages.get(0)
                : "strany " + join(pages.stream().map(String::valueOf).toList());
        return "Po podepsání se v dokumentu změnil obsah (" + where + ") — podrobnosti"
                + " v Rozšířeném přehledu.";
    }

    static String title(RevisionTail tail) {
        return tail.contentChanged()
                ? "Po podepsání se v dokumentu změnil obsah"
                : "Po podepsání do souboru ještě něco přibylo";
    }

    private static VBox body(RevisionTail tail, IntConsumer openPage, IntConsumer openCurrent) {
        VBox body = new VBox(6);
        body.setPadding(new Insets(6, 12, 10, 12));

        body.getChildren().add(paragraph(opening(tail)));
        body.getChildren().add(paragraph(whatWasAdded(tail)));
        body.getChildren().add(tail.contentChanged()
                ? changedParagraph(tail, openPage)
                : paragraph(unchangedMeaning()));
        if (tail.contentChanged() && openCurrent != null) {
            body.getChildren().add(currentLink(tail, openCurrent));
        }
        return body;
    }

    /**
     * Odkaz na dnešní podobu, na první změněné straně. Čísla stran v odstavci nad ním vedou
     * na podobu krytou podpisem; tenhle na tu, kterou čtenář dostal do ruky — obě poloviny
     * srovnání jsou tak jedno klepnutí od sebe.
     */
    private static Node currentLink(RevisionTail tail, IntConsumer openCurrent) {
        int page = tail.changedPages().get(0);
        Hyperlink link = new Hyperlink("Rychlý náhled dokumentu v dnešní podobě (po změnách)");
        link.setStyle("-fx-font-size: 12px; -fx-text-fill: #003189; -fx-padding: 0;"
                + " -fx-border-width: 0;");
        link.setOnAction(e -> {
            link.setVisited(false);
            openCurrent.accept(page);
        });
        javafx.scene.control.Tooltip.install(link, new javafx.scene.control.Tooltip(
                "Ukázat stranu " + page + " tak, jak vypadá dnes"));
        return link;
    }

    /**
     * Poslední odstavec u změněného obsahu — s čísly stran jako odkazy, jsou-li kam vést.
     *
     * <p>Odkaz otevře <b>podobu, kterou kryje podpis</b>, ne dnešní: dnešní ukáže čtenáři
     * kterákoli prohlížečka, kdežto to, co bylo podepsáno, žádná. Přepnout zpátky je pak jedno
     * kliknutí v nabídce vedle.
     */
    private static Node changedParagraph(RevisionTail tail, IntConsumer openPage) {
        if (openPage == null) return paragraph(changedMeaning(tail));

        List<Integer> pages = tail.changedPages();
        TextFlow flow = new TextFlow();
        flow.getChildren().add(plain("A je to podstatné: "
                + (pages.size() == 1 ? "na straně " : "na stranách ")));
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) flow.getChildren().add(plain(i == pages.size() - 1 ? " a " : ", "));
            flow.getChildren().add(pageLink(pages.get(i), openPage));
        }
        flow.getChildren().add(plain(" je dnes vidět něco jiného, než co bylo podepsáno."
                + changedTail()));
        return flow;
    }

    private static Hyperlink pageLink(int page, IntConsumer openPage) {
        Hyperlink link = new Hyperlink(String.valueOf(page));
        link.setStyle("-fx-font-size: 12px; -fx-text-fill: #003189; -fx-padding: 0;"
                + " -fx-border-width: 0;");
        link.setOnAction(e -> openPage.accept(page));
        javafx.scene.control.Tooltip.install(link, new javafx.scene.control.Tooltip(
                "Ukázat stranu " + page + " tak, jak vypadala v okamžiku podpisu"));
        return link;
    }

    private static Text plain(String text) {
        Text t = new Text(text);
        t.setStyle("-fx-font-size: 12px; -fx-fill: #111111;");
        return t;
    }

    // ------------------------------------------------------------------ texty

    /**
     * První věta: co je za posledním prvkem a kolik toho je.
     *
     * <p>Jmenuje se ten prvek, ne „podpis" paušálně: u dokumentu po uchování je posledním
     * kryjícím prvkem <b>razítko</b> a mluvit o podpisu by čtenáře poslalo hledat na kartu, kde
     * se to nestalo.
     */
    private static String opening(RevisionTail tail) {
        String element = tail.lastElement() == RevisionTail.Element.RAZITKO
                ? "poslední časové razítko" : "poslední podpis";
        return "Podpis ani časové razítko nekryje celý soubor, nýbrž tu jeho podobu, která"
                + " existovala v okamžiku jejich vzniku. Za tím, co kryje " + element
                + " v tomto dokumentu, je ještě " + size(tail.tailBytes())
                + " dat — přibyla až potom.";
    }

    /**
     * Druhá věta: co to je. Kategorie se berou z DSS a slouží právě jen k pojmenování — na otázku,
     * zda čtenář uvidí něco jiného, neodpovídají (změřeno, viz {@link RevisionTail}).
     *
     * <p>Obě znění se 18. 9. 2026 opravovala podle toho, co ty kategorie doopravdy znamenají:
     *
     * <ul>
     *   <li><b>Rozšíření dokumentu</b> nejsou jen validační údaje. Měření nad {@code D:\tmp}
     *       našlo v té kategorii 56 změn popisných údajů souboru (Metadata) vedle 181 ostatních,
     *       takže věta o certifikátech a odvolání by nad nimi tvrdila něco jiného, než co se
     *       stalo.</li>
     *   <li><b>„Nedefinované" změny nejsou slabina aplikace</b>, nýbrž nález. DSS se na ně ptá
     *       {@code „Does the document contain none of the undefined object modifications?"} a
     *       odpovídá {@code „The document contains undefined object modifications after the
     *       signature revision!"} — je to kategorie pro změny, které se nevešly mezi ty, jež se
     *       po podpisu považují za přípustné. Znění „nepodařilo se zařadit" to obracelo
     *       v omluvu.</li>
     * </ul>
     */
    private static String whatWasAdded(RevisionTail tail) {
        List<String> parts = new java.util.ArrayList<>();
        if (tail.validationData()) {
            parts.add("rozšíření dokumentu — přidání údajů usnadňujících pozdější ověření"
                    + " (certifikátů a revokačních informací), případně popisných údajů souboru");
        }
        if (tail.formOrSignature()) parts.add("zásah do formulářových či podpisových polí");
        if (tail.annotations()) parts.add("změnu anotací, tedy poznámek a razítek nad stránkou");
        if (tail.undefined()) {
            parts.add("změny mimo to, co se po podpisu považuje za přípustné");
        }
        if (parts.isEmpty()) {
            return "Co přesně to je, se z dokumentu zjistit nepodařilo.";
        }
        String sentence = "Aplikace v tom rozpoznala " + join(parts) + ".";
        // Odkud se ty údaje v dokumentu berou. Bez toho zůstávalo u zjištění, že něco přibylo,
        // a čtenář si mohl myslet, že to tam přidal kdovíkdo — přitom je to obvyklý a žádoucí
        // zásah.
        //
        // „Nejčastěji" je změřené, ne odhad. Na 266 dokumentech (D:\tmp a sbírka příkladů) nese
        // každé rozšíření dokumentu slovník /DSS, tedy validační údaje; popisné údaje souboru
        // v té kategorii opravdu jsou — /Metadata je skoro u všech —, ale samy přišly jen dvakrát
        // z 2 314 souborů (obojí v D:\Downloads, vedle stromu struktury). Proto „případně",
        // ne „nebo", a proto smí věta o úrovni stát bez podmínky.
        //
        // Dvě cesty, protože se obě vyskytují: /DSS přibyl k podpisu úrovně T 73×, ale k úrovni
        // B 20× (+ 6× PKCS7-B). Nález uživatele na TrustPro_qualified_electronic_signature.pdf —
        // DSS mu hlásí PAdES-BASELINE-B, nese 5 certifikátů a 3 revokační údaje a razítko má jen
        // ve slovníku VRI, které se na baseline úroveň nepočítá. Věta o samotném T na LT by
        // u takového dokumentu mluvila o něčem, co se nestalo.
        if (tail.validationData()) {
            sentence += " Nejčastěji jde o důsledek dodatečného povýšení úrovně z T na LT či"
                    + " dodatečného přidání revokačních informací k úrovni B.";
        }
        return sentence;
    }

    /**
     * Uklidňující závěr — a zároveň jediné místo, kde se řekne, že <b>ty přibylé údaje nekryje
     * nic</b>.
     *
     * <p>Právě tohle je u obvyklého případu ten užitek: dokument na úrovni LT nese validační data,
     * která nezastřešuje žádné razítko, a čtenář nemá odkud vědět, že to je rozdíl oproti LTA.
     * Odkazuje se na Digitální kontinuitu, protože tam se s tím dá něco udělat; co přesně, ví ta
     * záložka, a opsat to sem by znamenalo mít dvě odpovědi na jednu otázku.
     */
    private static String unchangedMeaning() {
        return "Obsah dokumentu se tím nezměnil — aplikace neporovnává jen bajty, ale i to, co je"
                + " na stránkách vidět, a žádný rozdíl nenašla. Ty přibylé údaje ale nekryje žádný"
                + " podpis ani časové razítko, takže o nich nelze doložit, kdy vznikly. Zastřešit"
                + " je umí další časové razítko; co se pro tento dokument nabízí, stojí v záložce"
                + " Digitální kontinuita.";
    }

    /**
     * Závěr u změněného obsahu.
     *
     * <p>Dvě věci, které se nesmí slít: <b>podepsané bajty porušené nejsou</b> (přírůstková
     * aktualizace je nemění, otisk sedí — proto se verdikt na kartě nehne), a přesto <b>dnešní
     * podoba není celá ta podepsaná</b>. Čtenář, kterému se řekne jen to druhé, bude hledat na
     * kartě neplatnost, která tam není.
     *
     * <p><b>Neříká se ale, že podpis „zůstává platný".</b> Do 18. 9. 2026 to tu stálo a bylo to
     * přeceněné: uživatel otevřel vyrobený vzorek v Adobe Acrobat Readeru a ten nad ním hlásí
     * <b>neplatný podpis kvůli změnám v dokumentu</b>. Není to rozpor, nýbrž rozdíl v nastavení —
     * živá politika DSS má {@code PdfVisualDifference Level="WARN"}, takže změna obsahu stránky
     * u nás varuje a neshazuje, kdežto {@code PdfPageDifference} je {@code FAIL}. Blok proto mluví
     * o <b>zdejším</b> verdiktu a rovnou říká, že přísnější posouzení existuje; slíbit platnost
     * napříč nástroji by znamenalo slíbit něco, co aplikace neví a co se právě vyvrátilo.
     *
     * <p>Neříká se, <b>co</b> se změnilo, protože to aplikace neví — ví, kterých stránek se to
     * týká. Slíbit víc by znamenalo slíbit porovnání, které se nekoná.
     */
    private static String changedMeaning(RevisionTail tail) {
        List<Integer> pages = tail.changedPages();
        String where = pages.size() == 1
                ? "na straně " + pages.get(0)
                : "na stranách " + join(pages.stream().map(String::valueOf).toList());
        return "A je to podstatné: " + where + " je dnes vidět něco jiného, než co bylo podepsáno."
                + changedTail();
    }

    /**
     * Druhá půlka odstavce, společná oběma podobám — s odkazy i bez nich.
     *
     * <p>Věta o jiných validátorech je od 9. 10. 2026 obecnější. Uživatel vyrobený vzorek
     * (pruh dokreslený přes stranu 1 po podpisu) předložil několika nástrojům a reakce se
     * rozešly: jeden hlásil, že dokument podepsán není vůbec, jeden neznámý stav platnosti,
     * dva podpis uznaly a změnu indikovaly. Dřívější „označí za neplatný“ tak bylo jen jedním
     * z možných posouzení. Nástroje se nejmenují a jejich chování se nevyjmenovává — aplikace
     * nezná jejich verzi ani nastavení (CLAUDE.md).
     */
    private static String changedTail() {
        return " Podepsané bajty to nezměnilo — přírůstková aktualizace se jich nedotkne, otisk"
                + " podpisu sedí dál — takže verdikt uvedený výše se tím nemění. Jiné validátory"
                + " ale mohou takovou změnu hodnotit jinak, například neplatností posledního"
                + " podpisu nebo neznámým stavem jeho platnosti. Co přesně se změnilo, aplikace"
                + " neříká; ví, kterých stran se to týká.";
    }

    /** „1,2 kB“, „13 kB“, „2,4 MB“ — velikost tak, aby se dala přečíst bez počítání nul. */
    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 10) return String.format("%.1f kB", kb).replace('.', ',');
        if (kb < 1024) return Math.round(kb) + " kB";
        return String.format("%.1f MB", kb / 1024.0).replace('.', ',');
    }

    /** „a, b a c“ — spojka před posledním, čárky mezi ostatními. */
    private static String join(List<String> parts) {
        if (parts.size() == 1) return parts.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(i == parts.size() - 1 ? " a " : ", ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static Label paragraph(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 12px; -fx-text-fill: #111111;");
        return l;
    }
}
