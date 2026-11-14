package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.PostSignatureEdits;
import cz.digitalnikontinuita.anep.core.RevisionTail;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
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
        if (tail.filledOrAnnotated()) {
            // Prostřední stupeň (14. 11. 2026): stojí v Rozšířeném přehledu a je otevřený, ale
            // záhlaví nenese verdikt — vyplnění formuláře ani poznámka nejsou chyba.
            return AdvisorView.collapsibleBlock(title(tail),
                    editedBody(tail, restrictedBySigner(result), openPage, openCurrent),
                    true, AdvisorView.BlockTint.PLAIN);
        }
        return AdvisorView.collapsibleBlock(title(tail), body(tail, openPage, openCurrent),
                tail.contentChanged(), tail.contentChanged()
                        ? AdvisorView.BlockTint.BROWN : AdvisorView.BlockTint.PLAIN);
    }

    /** Změnil se po podpisu obsah? Podle toho blok stojí v Rozšířeném přehledu, nebo v Analýze. */
    static boolean contentChanged(ValidationResult result) {
        return isPresent(result) && result.getRevisionTail().contentChanged();
    }

    /**
     * Stojí blok v Rozšířeném přehledu? Změněný obsah, nebo vyplněná pole či viditelné anotace.
     * Jinak (jen validační data) zůstává v Analýze v expertním módu.
     */
    static boolean inOverview(ValidationResult result) {
        return isPresent(result) && result.getRevisionTail().worthTheOverview();
    }

    /**
     * Řádek pro Zjednodušený přehled: varování u změněného obsahu, klidné upozornění u vyplnění
     * a anotací, jinak {@code null}.
     *
     * @param warning hnědý řádek (změněný obsah), nebo neutrální (vyplnění, anotace)
     */
    record Notice(String text, boolean warning) { }

    static Notice noticeLine(ValidationResult result) {
        String warn = warningLine(result);
        if (warn != null) return new Notice(warn, true);
        if (!isPresent(result) || !result.getRevisionTail().filledOrAnnotated()) return null;
        return new Notice(title(result.getRevisionTail()) + " — podpis tím porušen není;"
                + " podrobnosti v Rozšířeném přehledu.", false);
    }

    /**
     * Zakázal některý podpis právě takovou změnu? Certifikační podpis (DocMDP) nebo zámek
     * s {@code /P}: stupeň 1 nedovoluje nic, stupeň 2 vyplnění formuláře a podpisy, ale ne
     * anotace, stupeň 3 obojí. Pak věta „PDF takové doplnění dovoluje" neplatí — co smí přibýt,
     * určil podepsaný, a mluví o tom karta podpisu. Zámky jednotlivých polí (FieldMDP bez
     * {@code /P}) se tu neposuzují.
     */
    private static boolean restrictedBySigner(ValidationResult result) {
        if (result.getSignatures() == null) return false;
        RevisionTail tail = result.getRevisionTail();
        boolean annotations = tail.edits() != null && !tail.edits().visibleAnnotations().isEmpty();
        for (var s : result.getSignatures()) {
            for (String p : new String[] { s.getCertificationPermission(),
                    s.getPdfLock() != null ? s.getPdfLock().permission() : null }) {
                if ("NO_CHANGE_PERMITTED".equals(p)) return true;
                if ("MINIMAL_CHANGES_PERMITTED".equals(p) && annotations) return true;
            }
        }
        return false;
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
        if (tail.contentChanged()) return "Po podepsání se v dokumentu změnil obsah";
        if (tail.filledOrAnnotated()) {
            PostSignatureEdits e = tail.edits();
            List<PostSignatureEdits.AnnotationEdit> ann = e.visibleAnnotations();
            boolean one = ann.size() == 1;
            boolean added = ann.stream()
                    .anyMatch(a -> a.action() == PostSignatureEdits.Action.ADDED);
            if (ann.isEmpty()) return "Po podepsání byla v dokumentu vyplněna pole formuláře";
            if (!e.fields().isEmpty()) {
                return "Po podepsání byla v dokumentu vyplněna pole formuláře a "
                        + (added ? (one ? "přibyla anotace" : "přibyly anotace")
                                 : (one ? "změnila se anotace" : "změnily se anotace"));
            }
            return added
                    ? "Po podepsání v dokumentu " + (one ? "přibyla anotace" : "přibyly anotace")
                    : "Po podepsání se v dokumentu " + (one ? "změnila anotace" : "změnily anotace");
        }
        return "Po podepsání do souboru ještě něco přibylo";
    }

    private static VBox body(RevisionTail tail, IntConsumer openPage, IntConsumer openCurrent) {
        VBox body = new VBox(6);
        body.setPadding(new Insets(6, 12, 10, 12));

        body.getChildren().add(paragraph(opening(tail)));
        body.getChildren().add(paragraph(whatWasAdded(tail)));
        // Změněný obsah a k tomu vyplněná pole či anotace: vyjmenovat i je, ať čtenář ví,
        // čím se dnešní podoba liší kromě toho, co našel DSS.
        if (tail.edits() != null && tail.edits().notable()) {
            body.getChildren().add(paragraph(editsList(tail.edits())));
        }
        body.getChildren().add(tail.contentChanged()
                ? changedParagraph(tail, openPage)
                : paragraph(unchangedMeaning()));
        if (tail.contentChanged() && openCurrent != null) {
            body.getChildren().add(currentLink(tail.changedPages().get(0), openCurrent));
        }
        return body;
    }

    /**
     * Prostřední stupeň: vyplněná pole a anotace.
     *
     * <p>Rozhodnutí uživatele 14. 11. 2026: formulář podepsaný ještě prázdný a teď řádně
     * vyplněný nemá aplikace prezentovat jako chybu, ale má vhodně říct, že po podpisu byla
     * vyplněna pole a/nebo přidány anotace. Vzorem je Adobe Acrobat Reader, který podpis
     * i razítko nad takovým dokumentem uzná a pod nimi vypíše „Anotace vytvořeny: anotace Stamp
     * na stránce 1".
     */
    private static VBox editedBody(RevisionTail tail, boolean restricted, IntConsumer openPage,
                                   IntConsumer openCurrent) {
        VBox body = new VBox(6);
        body.setPadding(new Insets(6, 12, 10, 12));
        body.getChildren().add(paragraph(opening(tail)));
        String list = editsList(tail.edits());
        if (tail.validationData()) {
            list += " Vedle toho přibyly údaje usnadňující pozdější ověření (certifikáty"
                    + " a revokační informace), případně popisné údaje souboru.";
        }
        body.getChildren().add(paragraph(list));
        body.getChildren().add(paragraph(editedMeaning(tail, restricted)));
        List<Integer> pages = tail.edits().visiblePages();
        if (!pages.isEmpty()) {
            if (openPage != null) body.getChildren().add(signedPagesLinks(pages, openPage));
            if (openCurrent != null) body.getChildren().add(currentLink(pages.get(0), openCurrent));
        }
        return body;
    }

    /**
     * Výčet polí a anotací. Jména polí se zkracují na poslední článek — formuláře z LiveCycle
     * mívají jména jako {@code topmostSubform[0].Page1[0].Jmeno[0]} a celé by čtenáři nic
     * neřekla.
     */
    static String editsList(PostSignatureEdits e) {
        List<String> sentences = new java.util.ArrayList<>();
        List<String> filled = e.fields().stream().filter(f -> !f.added())
                .map(RevisionTailBlock::fieldName).toList();
        List<String> added = e.fields().stream().filter(PostSignatureEdits.FieldEdit::added)
                .map(RevisionTailBlock::fieldName).toList();
        if (!filled.isEmpty()) {
            sentences.add((filled.size() == 1 ? "Vyplněno bylo pole " : "Vyplněna byla pole ")
                    + capped(filled) + ".");
        }
        if (!added.isEmpty()) {
            sentences.add((added.size() == 1 ? "Nově přibylo a bylo vyplněno pole "
                    : "Nově přibyla a byla vyplněna pole ") + capped(added) + ".");
        }
        List<String> newAnn = new java.util.ArrayList<>(), changedAnn = new java.util.ArrayList<>();
        for (PostSignatureEdits.AnnotationEdit a : e.visibleAnnotations()) {
            String s = annotationName(a.kind()) + " na straně " + a.page();
            if (a.action() == PostSignatureEdits.Action.ADDED) newAnn.add(s); else changedAnn.add(s);
        }
        if (!newAnn.isEmpty()) {
            sentences.add((newAnn.size() == 1 ? "Přibyla anotace: " : "Přibyly anotace: ")
                    + capped(newAnn) + ".");
        }
        if (!changedAnn.isEmpty()) {
            sentences.add((changedAnn.size() == 1 ? "Změnila se anotace: " : "Změnily se anotace: ")
                    + capped(changedAnn) + ".");
        }
        int hidden = e.hiddenAnnotationCount();
        if (hidden > 0) {
            sentences.add("Kromě toho se " + (hidden == 1 ? "změnila 1 anotace, která"
                    : hidden < 5 ? "změnily " + hidden + " anotace, které"
                    : "změnilo " + hidden + " anotací, které")
                    + " na stránce vidět nejsou (například odkazy).");
        }
        return String.join(" ", sentences);
    }

    private static String fieldName(PostSignatureEdits.FieldEdit f) {
        String name = f.name();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) name = name.substring(dot + 1);
        name = name.replaceAll("\\[\\d+\\]$", "");
        return "„" + name + "“";
    }

    /**
     * Poznámka na kartu prvku, po kterém — ještě před dalším kryjícím prvkem — byla vyplněna
     * pole či přibyly anotace; {@code null}, když nic takového není.
     *
     * <p>Přání uživatele 14. 11. 2026, po měření nad sbírkou: skutečný formulář podepsaný
     * dvakrát měl zaškrtávací pole vyplněná mezi podpisy. Blok nad kartami o tom mlčí (za
     * posledním prvkem nic není), a přesto první podpis vyplněné hodnoty nekryje. Poznámka to
     * říká u toho podpisu a jmenuje ten, který je kryje. U dokumentového razítka se dodává, co
     * razítko dokládá a co ne — čas, ne původce.
     */
    static String interimNote(SignatureSummary sig) {
        if (sig == null || sig.getLaterEdits() == null) return null;
        PostSignatureEdits.Interim gap = sig.getLaterEdits();
        boolean selfTs = "TimeStampToken".equals(sig.getFormat());
        boolean selfSeal = !selfTs && sig.isSeal();
        String self = selfTs ? "Po tomto časovém razítku" : selfSeal ? "Po této pečeti" : "Po tomto podpisu";
        String pronoun = selfTs ? "Toto razítko" : selfSeal ? "Tato pečeť" : "Tento podpis";
        String name = gap.nextName() != null ? " " + gap.nextName() : "";
        String next, coveredBy;
        if (gap.nextKind() == PostSignatureEdits.CoveredBy.RAZITKO) {
            next = "časovým razítkem" + (name.isEmpty() ? "" : " autority" + name);
            coveredBy = "až pozdější časové razítko — to ale dokládá jen to, že doplnění"
                    + " existovalo nejpozději v okamžiku jeho vzniku, ne kdo je provedl";
        } else {
            next = (gap.nextSeal() ? "pečetí" : "podpisem") + name;
            coveredBy = gap.nextSeal() ? "až pozdější pečeť" : "až pozdější podpis";
        }
        return self + ", ještě před " + next + ", byl dokument doplněn. "
                + editsList(gap.edits()) + " " + pronoun + " tato doplnění nekryje, kryje je "
                + coveredBy + ". Na verdikt " + (selfTs ? "tohoto razítka" : selfSeal
                        ? "této pečeti" : "tohoto podpisu") + " to vliv nemá.";
    }

    /** Nejvýš osm položek, zbytek jako „a dalších N". */
    private static String capped(List<String> items) {
        if (items.size() <= 8) return join(items);
        int rest = items.size() - 7;
        List<String> head = new java.util.ArrayList<>(items.subList(0, 7));
        head.add(rest + (rest < 5 ? " další" : " dalších"));
        return join(head);
    }

    /** Český název druhu anotace; neznámý druh se jmenuje tím, čím ho nazývá PDF. */
    static String annotationName(String kind) {
        return switch (kind) {
            case "Text" -> "poznámka";
            case "FreeText" -> "textový rámeček";
            case "Stamp" -> "razítko";
            case "Highlight" -> "zvýraznění textu";
            case "Underline" -> "podtržení textu";
            case "StrikeOut" -> "přeškrtnutí textu";
            case "Squiggly" -> "vlnovka pod textem";
            case "Ink" -> "kresba od ruky";
            case "Square", "Circle", "Line", "Polygon", "PolyLine" -> "tvar";
            case "FileAttachment" -> "připojený soubor";
            case "Caret" -> "značka vložení";
            case "Redact" -> "začernění";
            case "Watermark" -> "vodoznak";
            case "Sound" -> "zvuk";
            default -> "anotace typu " + kind;
        };
    }

    /**
     * Co to znamená. Tři věci a žádná se nesmí ztratit: podpis to neporušuje (verdikt platí),
     * je to obvyklé (nestrašit), a přesto to podpis nekryje (proto o tom řeč nad kartami).
     *
     * <p>Věta „PDF to dovoluje" platí, jen pokud podepsaný změny neomezil — certifikační podpis
     * či zámek dokumentu může vyplnění nebo anotace zakázat. Pak se odkáže na kartu podpisu,
     * kde je omezení popsané, a o přípustnosti se tu nic netvrdí.
     */
    private static String editedMeaning(RevisionTail tail, boolean restricted) {
        String permitted = restricted
                ? "Některý z podpisů ale změny dokumentu po podpisu omezuje; zda je tohle"
                        + " doplnění v jeho mezích, ukazuje karta toho podpisu."
                : "Formát PDF takové doplnění po podpisu dovoluje a podepsaná data jím porušena"
                        + " nejsou — verdikt uvedený výše se tím nemění. U formulářů, které se"
                        + " podepisují ještě nevyplněné, a u poznámek, které si do dokumentu"
                        + " vkládá příjemce, je to obvyklé.";
        String element = tail.lastElement() == RevisionTail.Element.RAZITKO
                ? "časové razítko" : "podpis";
        return permitted + " Jen to, co přibylo, už " + element + " nekryje: kryje podobu"
                + " dokumentu z okamžiku svého vzniku, takže o vyplněných hodnotách a anotacích"
                + " z něj nelze doložit, kdo a kdy je doplnil. Jiné validátory mohou takové"
                + " doplnění vypsat jako změny po podpisu.";
    }

    /** „Podepsanou podobu ukáže náhled: strana 1, 3." — čísla stran jako odkazy. */
    private static Node signedPagesLinks(List<Integer> pages, IntConsumer openPage) {
        TextFlow flow = new TextFlow();
        flow.getChildren().add(plain("Jak dokument vypadal v okamžiku podpisu, ukáže náhled: "
                + (pages.size() == 1 ? "strana " : "strany ")));
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) flow.getChildren().add(plain(i == pages.size() - 1 ? " a " : ", "));
            flow.getChildren().add(pageLink(pages.get(i), openPage));
        }
        flow.getChildren().add(plain("."));
        return flow;
    }

    /**
     * Odkaz na dnešní podobu, na první změněné straně. Čísla stran v odstavci nad ním vedou
     * na podobu krytou podpisem; tenhle na tu, kterou čtenář dostal do ruky — obě poloviny
     * srovnání jsou tak jedno klepnutí od sebe.
     */
    private static Node currentLink(int page, IntConsumer openCurrent) {
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
     *
     * <p>„Posledního podpisu" se 14. 11. 2026 změnilo na „podpisů a časových razítek": nad
     * vzorkem s pruhem dokresleným do obsahu stránky dokumentu, který končil archivním razítkem,
     * označil Adobe Acrobat Reader za neplatný podpis i dokumentové razítko.
     */
    private static String changedTail() {
        return " Podepsané bajty to nezměnilo — přírůstková aktualizace se jich nedotkne, otisk"
                + " podpisu sedí dál — takže verdikt uvedený výše se tím nemění. Jiné validátory"
                + " ale mohou takovou změnu hodnotit jinak, například neplatností podpisů"
                + " a časových razítek nebo neznámým stavem jejich platnosti. Co přesně se"
                + " změnilo, aplikace neříká; ví, kterých stran se to týká.";
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
