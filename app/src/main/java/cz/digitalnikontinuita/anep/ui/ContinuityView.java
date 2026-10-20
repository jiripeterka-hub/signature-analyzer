package cz.digitalnikontinuita.anep.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;

/**
 * Záložka „Digitální kontinuita“ — co se s dokumentem dá udělat, aby zůstal ověřitelný, a do kdy.
 *
 * <h2>Proč vlastní záložka</h2>
 *
 * <p>Analýza mluví po podpisech: každá karta popisuje jeden autentizační prvek. Akce jsou ale
 * dokumentové — nové časové razítko kryje celý soubor ({@code ByteRange} razítka dokumentu podle
 * ETSI EN 319 142-1 „shall cover the entire document“), takže se nedá provést pro jeden podpis
 * a pro druhý ne. Řádek s tlačítky přesto stál pod seznamem karet a tím vizuálně visel na té
 * poslední. U jednoho podpisu to nevadilo; u pěti to klame.
 *
 * <p>Rozdělení je tedy podle rozsahu, ne podle tématu: <b>Analýza je per podpis, tahle záložka
 * per dokument</b>. Co která akce udělá, dokdy se lhůta posune a čí je to vlastně věc — to je
 * otázka o souboru jako celku a patří na jedno místo, ne pod poslední kartu.
 *
 * <h2>Čtyři části</h2>
 *
 * <p><b>1. Co je digitální kontinuita.</b> Výklad pojmu, nikoli nález o dokumentu: stojí tu
 * pořád stejně, i když ještě nic ověřeno nebylo, a proto ho drží tahle třída a ne {@link
 * AdvisorView}. Složený, aby se čtenář, který ví, co pojem znamená, nemusel prokousávat
 * odstavcem pokaždé znovu; kdo to čte poprvé, má to na dosah jednoho kliknutí.
 *
 * <p><b>2. Jaké možnosti nabízí AnEP.</b> Rovněž výklad, ne nález: co aplikace umí, na
 * ověřovaném dokumentu nezávisí — které z toho na <i>tenhle</i> soubor dosáhne, říkají až bloky
 * pod ním. Uvnitř dva vnořené bloky po variantách úkonu.
 *
 * <p><b>3. Po prvcích.</b> Pro každý podpis, pečeť či samostatné razítko sada bloků, které
 * odpovídají na jednu otázku z různých stran: <b>do kdy se dá platnost ověřit</b> — zdejším
 * způsobem, validátorem podle standardů, nástrojem s vlastní zásobou revokačních informací.
 * Staví je {@link ContinuityElementBlocks}.
 *
 * <p><b>4. Dokument jako celek.</b> Jen tam, kde je prvků víc: co a do kdy je třeba udělat,
 * je otázka o souboru — jeden úkon kryje celý dokument a tlačítko dole je jedno. Stojí tu
 * proto oba bloky o úkonu, popis souběhu a výčet toho, jak se úkon projeví na kterém prvku.
 * U dokumentu s jediným prvkem tahle část není a oba bloky zůstávají u něj.
 *
 * <h2>Co si tahle třída drží</h2>
 *
 * <p>Kromě obou výkladových částí skoro nic. Obsah závislý na dokumentu i řádek s tlačítky
 * staví dál {@link AdvisorView}, protože obojí stojí na jeho bránách a na službách, které má
 * zapojené; sem se hotové uzly jen předávají.
 */
public class ContinuityView {

    /**
     * Výklad pojmu, po odstavcích. Text i jeho sazba jsou od uživatele; tučně jsou pojmy, které
     * výklad zavádí, ne zdůraznění vět.
     *
     * <p>Odstavce jsou pole, ne jeden řetězec s prázdnými řádky: každý se sází vlastním
     * {@link javafx.scene.text.TextFlow} (viz {@link AdvisorView#boldMarkup}), protože běhy
     * tučného a obyčejného písma jinak než po běhách nejdou a zalomení řádku musí procházet
     * jejich hranicemi.
     */
    private static final String[] WHAT_IS = {
            "O digitální kontinuitě se hovoří v souvislosti s průběžným (tj. **kontinuálním**)"
            + " udržováním elektronických (správně **digitálních**) dokumentů a jejich"
            + " autentizačních prvků (podpisů, pečetí a časových razítek) v takovém stavu, aby se"
            + " z těchto prvků daly dovozovat vlastnosti samotného dokumentu. Zejména **pravost"
            + " jejich podpisů** (neboli to, že dokument podepsal skutečně ten, kdo je prezentován"
            + " jako podepsaná osoba, resp. opatřil pečetí ten, kdo je prezentován jako pečetící"
            + " osoba), a dále **pravost dokumentu jako takového** (že od svého podepsání nebyl"
            + " jakkoli pozměněn, resp. že nebyla porušena jeho integrita – též že dokument je"
            + " původní, resp. autentický).",

            "**Pravost podpisu** (či pečeti) je právní aspekt, který se v případě elektronických"
            + " podpisů (pečetí) může dovozovat z jejich (technické) **platnosti**. V případě"
            + " kvalifikovaného či uznávaného elektronického podpisu se jeho pravost dá z jeho"
            + " platnosti dovozovat bez potřeby čehokoli dalšího. V případě pouze zaručeného"
            + " elektronického podpisu tomu tak není (a k dovození právní pravosti je nutné"
            + " ověření nějakých dalších skutečností). Obdobně pro pečeti.",

            "Naproti tomu **pravost dokumentu** (jeho neporušená integrita) se dá dovozovat"
            + " z platnosti kvalifikovaného, uznávaného, ale i zaručeného elektronického podpisu.",

            "S postupem času ale dochází k oslabování kryptografických algoritmů, na kterých jsou"
            + " elektronické podpisy a pečeti založeny, a tím i ke změnám (degradaci) jejich"
            + " vlastností. Aby se nenarušila možnost dovozovat (právní) pravost přímo z jejich"
            + " (technické) platnosti, jsou v elektronických podpisech a pečetích zabudována"
            + " opatření, omezující možnost ověření platnosti jen po určitou dobu, a nikoli déle."
            + " Realizováno je to zejména omezenou časovou platností certifikátů, na kterých jsou"
            + " založeny elektronické podpisy, pečeti i časová razítka.",

            "Lze si to představit také jako určitou **časovou pojistku**, která chrání ty, kdo se"
            + " chtějí spoléhat na starší elektronické dokumenty a jejich podpisy, neboli tzv."
            + " spoléhající se osoby, před dopady oslabování kryptografických algoritmů.",

            "Účinky této pomyslné pojistky lze odkládat (vždy na určitou konkrétní dobu, v řádu"
            + " několika let) pomocí aktivních kroků, chránících před oslabováním kryptografických"
            + " algoritmů a zajišťujících prodloužení digitální kontinuity, resp. zachování"
            + " možnosti ověřit platnost podpisů či pečetí, a dovozovat z nich jejich pravost,"
            + " i pravost dokumentu jako takového.",

            "Velmi zjednodušeně jde o přidávání dalších časových razítek a všech (dosud"
            + " chybějících) informací, nutných pro pozdější ověření – tzv. **validačních"
            + " informací**. Zejména pak **revokačních informací**, nutných pro ověření toho, zda"
            + " konkrétní certifikát nebyl revokován (též: odvolán, resp. předčasně zneplatněn).",

            "Důležité přitom je, že každý aktivní krok chrání před oslabováním kryptografických"
            + " algoritmů vždy jen po určitou dobu, a nikoli napořád. Proto je nutné aktivní kroky"
            + " vždy po určité době opakovat.",
    };

    /** Značka odstavce, na jejímž místě se místo textu nakreslí vodorovná čára. */
    private static final String RULE = "---";

    /** Značka na začátku odstavce, který se má vysázet jako odrážka. */
    private static final String BULLET = "• ";

    /**
     * Úvod druhé části — co všechno aplikace pro udržení kontinuity umí.
     *
     * <p>Čára uprostřed dělí dvě věci různého řádu: nad ní varianty, které <b>prodlouží
     * ověřitelnost</b>, pod ní balíček, který ji neprodlouží a nastupuje teprve tam, kde už
     * žádná z variant nejde. Bez ní by se čtvrtý odstavec četl jako další varianta v řadě.
     */
    private static final String[] OPTIONS = {
            "Aplikace AnEP nabízí více variant toho, jak prodloužit digitální kontinuitu"
            + " elektronických dokumentů a jejich podpisů a pečetí, neboli jak zachovat možnost"
            + " ověření jejich platnosti po nějaké další období. Tyto varianty jsou závislé"
            + " zejména na tom, v jakém „časovém stádiu“ se elektronicky podepsané či opečetěné"
            + " dokumenty nacházejí.",

            "Společné je pro všechny varianty to, že jejich výstupem, který aplikace AnEP"
            + " vygeneruje, je soubor se sufixem (koncovkou jména) vyjadřujícím variantu"
            + " prodloužení digitální kontinuity a datum, do kdy je prodloužena. Například pokud"
            + " byl vstupem soubor *dokument.pdf* a došlo k jeho uchování, bude výstupem soubor"
            + " *dokument_Uchovano_do_03_08_2034.pdf*.",

            "Datum na konci jména (dále v textu značené jako DD_MM_RRRR) přitom není vlastností"
            + " dokumentu či podpisu nebo pečeti, nýbrž"
            + " důsledkem volby: nové časové razítko se pořizuje od zdroje časových razítek, který"
            + " je vybrán v nastavení aplikace, a ověřitelnost posune jen tak daleko, kam až sahá"
            + " platnost certifikátu, který používá tento zdroj. Volbou zdroje s delší platností"
            + " certifikátu lze proto dosáhnout delšího posunutí – a naopak, pokud by vybraný"
            + " zdroj nedosáhl dál, než kam dokument dosáhne i bez nového časového razítka,"
            + " aplikace na to upozorní.",

            "Která konkrétní varianta prodloužení digitální kontinuity, resp. zachování"
            + " ověřitelnosti je pro daný podpis či pečeť dostupná, aplikace AnEP vždy uživateli"
            + " explicitně sdělí, stejně jako do kdy je potřeba provést příslušný úkon (níže,"
            + " v bloku, který variantu jmenuje už v nadpisu — „AnEP nabízí možnost…“).",

            RULE,

            "Pro případ, kdy platnost podpisu (pečeti) již není možné ověřit, nebo kdy z jiného"
            + " důvodu není možné provést žádnou z variant prodloužení digitální kontinuity (resp."
            + " zachování možnosti ověřit platnost), nabízí aplikace AnEP ještě jednu"
            + " „záchrannou“ možnost. Jejím smyslem je vyjít vstříc případným budoucím sporům"
            + " o elektronický dokument a jeho vlastnosti, které by musel řešit znalec v oboru"
            + " kryptografie, nejspíše soudní znalec. Aplikace může připravit aktuálně dostupné"
            + " podklady pro budoucí znalecké posouzení: shromáždit vše, co o dokumentu ví a má"
            + " aktuálně k dispozici, „zabalit to“ do jednoho celku (ASiC kontejneru), a ten"
            + " opatřit (kvalifikovaným elektronickým) časovým razítkem, coby důkazem o existenci"
            + " v čase. Výsledek případného budoucího posouzení to ale samozřejmě nijak"
            + " negarantuje.",

            "V aplikaci AnEP je tato možnost prezentována jako vytvoření **forenzního balíčku**:"
            + " pokud je jí předložen např. soubor *dokument.pdf*, bude vytvořen forenzní balíček"
            + " v podobě souboru *FB_dokument_pdf_Do_DD_MM_RRRR.asice* (kde DD_MM_RRRR je datum,"
            + " do kterého lze ověřit platnost časového razítka na ASiC kontejneru).",
    };

    /**
     * Vnořený blok: uchování a prodloužení, tedy úkony navazující na dosavadní řadu razítek.
     *
     * <p>Tři díly oddělené čarou podle toho, v jakém stavu dokument je: certifikát ještě platí →
     * plnohodnotné uchování; už neplatí, ale razítka obstojí i přísně → uchování; obstojí jen
     * volněji → zbývá prodloužení, a to s užším dosahem.
     *
     * <p>Jména variant, která text zavádí, jsou od 7. 9. 2026 i na tlačítku a ve jméně výstupního
     * souboru — viz {@code AdvisorView.ContinuityMode}. Kdyby se tam změnila, musí se změnit
     * i tady: čtenář hledá dole to slovo, které si přečetl nahoře.
     */
    private static final String[] OPTION_PRESERVE_PROLONG = {
            "Nejpříznivější je situace v případě „čerstvých“ dokumentů, které byly podepsány či"
            + " opatřeny pečetí relativně nedávno. Určujícím kritériem pro tento případ je to, že"
            + " ještě neexpiroval podpisový certifikát (tj. certifikátu, na kterém je podpis (či"
            + " pečeť) založen, ještě neskončila řádná doba jeho platnosti). Pak je totiž"
            + " zajištěna dostupnost revokačních informací, které jsou pro zachování"
            + " ověřitelnosti nezbytné, přímo od jejich vydavatelů.",

            "Aplikace AnEP je pak schopna provést „plnohodnotné“ **uchování** platného podpisu"
            + " (pečeti): přidat veškeré (dosud chybějící) revokační informace a časová razítka"
            + " tak, aby vznikl podpis (pečeť) v úrovni LTA. Svému uživateli aplikace dopředu"
            + " sdělí, do kdy je třeba příslušný úkon provést: obecně dříve, než podpisový"
            + " certifikát expiruje.",

            "Výstupem bude takový podpis (pečeť), jehož platnost by mělo být možné ověřit na všech"
            + " validátorech až do data DD_MM_RRRR. A to i na validátorech, které hodnotí platnost"
            + " časových razítek striktněji než technické standardy (pouze podle řádné doby"
            + " platnosti certifikátů časových razítek). Což jsou např. CzechPOINTy, většina"
            + " podatelen OVM, služba SecuSign, program Signer a další. Na validátorech, které"
            + " hodnotí časová razítka podle standardů (což je např. služba DSS a na ní založené"
            + " validátory), bude možné ověřit platnost podpisu ještě déle, i po datu DD_MM_RRRR"
            + " (až do tzv. sunsetu, kdy bude službě, která vydala poslední časové razítko,"
            + " odebrán její dosavadní kvalifikovaný status).",

            RULE,

            "Stejně tak je pro možnost uchování příznivá situace, kdy podpisový certifikát sice"
            + " již expiroval, ale v podpisu (pečeti) již jsou vloženy všechny revokační"
            + " informace, potřebné pro pozdější ověření, a platná (kvalifikovaná elektronická)"
            + " časová razítka poskytují důkaz o existenci podpisu v čase (tzv. PoE, Proof of"
            + " Existence), v době, kdy jeho certifikát ještě neexpiroval.",

            "Zde ale záleží ještě i na tom, o jaká časová razítka jde, a v případě více časových"
            + " razítek také na tom, jak je zformována jejich posloupnost. Jde o to, že existují"
            + " dva způsoby hodnocení časových razítek při ověřování jejich platnosti:",

            BULLET
            + "„striktnější“: nástroje a služby pro ověřování platnosti (tzv. validátory) mohou"
            + " podmiňovat platnost časového razítka tím, že jeho certifikát (tj. certifikát, na"
            + " kterém je časové razítko založeno) ještě neexpiroval. Takto k časovým razítkům"
            + " přistupují např. CzechPOINTy, podatelny OVM, spisové služby, služba SecuSign"
            + " a program Signer a další",

            BULLET
            + "„volnější“: podle technických standardů je možné považovat (kvalifikované"
            + " elektronické) časové razítko za platné i po expiraci jeho certifikátu, pokud má"
            + " jeho vydavatel stále kvalifikovaný status. Přesněji: dokud je služba, v rámci"
            + " které bylo časové razítko vydáno, stále uvedena na tzv. důvěryhodných seznamech"
            + " jako služba s kvalifikovaným statusem. Kdy jí bude odebrán (neboli kdy dojde"
            + " k tzv. sunsetu) nelze předem určit",

            "Aby uchování zachovalo ověření platnosti do data DD_MM_RRRR na všech validátorech,"
            + " je nutné, aby alespoň jedno z časových razítek bylo ověřeno jako platné"
            + " „striktním“ způsobem — nemusí to být to poslední. A pokud je časových razítek"
            + " více, musí být jejich posloupnost souvislá: v tom smyslu, že každé další časové"
            + " razítko bylo přidáno ještě dříve, než expiroval certifikát některého z těch"
            + " předchozích. Mladší časové razítko s kratší platností certifikátu tedy"
            + " posloupnost nezkracuje; nepřidá nic, ale ani nic nebere. Je-li to splněno"
            + " a jsou-li v podpisu (pečeti) zároveň vloženy všechny potřebné revokační"
            + " informace, pak je uchování možné i u takovéhoto podpisu – a jeho platnost bude"
            + " až do data DD_MM_RRRR možné ověřit na všech validátorech.",

            RULE,

            "Aplikace AnEP (pokud se jí v nastavení neřekne jinak) hodnotí časová razítka"
            + " „volněji“. Stejně jako ostatní validátory, které ověřují časová razítka podle"
            + " technických standardů. Což je zejména služba DSS a validátory, založené na této"
            + " službě a jejím zdrojovém kódu (stejně jako AnEP). Jako platné pak tyto validátory"
            + " mohou ověřit i takové časové razítko, kterému již expiroval jeho certifikát – ale"
            + " u jeho vydavatele ještě nedošlo k onomu sunsetu. V případě posloupnosti časových"
            + " razítek pak stačí, aby další časové razítko bylo přidáno dříve, než došlo"
            + " k sunsetu předchozího časového razítka.",

            "Pokud nastal tento případ, tedy pokud časová razítka vycházejí jako platná jen při"
            + " „volnějším“ způsobu jejich hodnocení, není výše popisované uchování možné. A to"
            + " ani v případě, kdy jsou k podpisu (pečeti) již přiloženy všechny potřebné"
            + " revokační informace. Důvodem je to, že validátory se „striktním“ hodnocením"
            + " časových razítek (jako jsou CzechPOINTy, podatelny atd.) nebudou schopné ověřit"
            + " časová razítka jako platná a použít je jako nezbytný důkaz o existenci podpisu"
            + " (PoE) v dřívějším čase.",

            "Pro takovýto případ aplikace AnEP nabízí variantu prodloužení digitální kontinuity,"
            + " resp. zachování možnosti ověření platnosti, kterou lze charakterizovat jako"
            + " **prodloužení**. Spočívá v tom, že aplikace pouze přidá další časové razítko."
            + " Podmínkou ovšem stále je, aby v podpisu (pečeti) již byly vloženy všechny potřebné"
            + " revokační informace pro jeho pozdější ověření.",

            "Důležité je, že platnost podpisu (pečeti), který je výsledkem takovéhoto prodloužení,"
            + " bude možné ověřit jen na validátorech, které používají „volnější“ hodnocení"
            + " časových razítek při jejich ověřování. Tedy např. v aplikaci AnEP, službě DSS a na"
            + " ní založených validátorech – ale nikoli třeba na CzechPOINTu, na podatelnách atd.",
    };

    /**
     * Vnořený blok: přerazítkování — úkon pro dokument, jehož revokační informace už standardy
     * neuznají. Dosah je proto obrácený než u předchozího bloku: uspěje tam, kde předchozí ne,
     * a neuspěje tam, kde předchozí ano.
     */
    private static final String[] OPTION_RESTAMP = {
            "Méně příznivá je situace v případě, kdy podpisový certifikát již expiroval,"
            + " a v podpisu nejsou vloženy všechny revokační informace, potřebné a využitelné pro"
            + " jeho pozdější ověření. Pak je problém v tom, že tyto revokační informace již od"
            + " jejich vydavatelů nemusí být dostupné (jejich dostupnost je zajištěna jen po"
            + " dobu platnosti certifikátu, ke kterému se vztahují). A i když dostupné stále jsou,"
            + " jedná se o revokační informace vydané po expiraci certifikátu, nebo vydané již"
            + " expirovaným vydavatelem (tzv. respondérem, v případě OCSP odpovědí) – a technické"
            + " standardy jejich využití zapovídají.",

            "Na problém s pozdější dostupností revokačních informací obvykle pamatují validátory,"
            + " které používají „striktní“ hodnocení časových razítek při jejich ověřování"
            + " – a udržují si vlastní zásobu revokačních informací pro jejich pozdější využití."
            + " Jde typicky o CzechPOINTy, většinu podatelen OVM, službu SecuSign, program Signer"
            + " a další. Stejně tak jsou tyto validátory vstřícné k využití starších"
            + " (nereferenčních) podpisových formátů, starších hashovacích funkcí (např. SHA1),"
            + " a jsou ochotny využít i takové revokační informace (od expirovaného vydavatele"
            + " či vydané po expiraci podpisového certifikátu), jejichž využití technické"
            + " standardy zapovídají.",

            "Na druhou stranu takovéto validátory obvykle uplatňují přísnější hodnocení časových"
            + " razítek při jejich ověřování, striktně podle řádné doby platnosti jejich"
            + " certifikátů. Jako platné tak hodnotí jen takové časové razítko, kterému ještě"
            + " neexpiroval jeho certifikát (tj. ještě neskončila řádná doba platnosti"
            + " certifikátu, na kterém je časové razítko založeno). A v případě více časových"
            + " razítek vyžadují, aby to další bylo přidáno ještě před expirací certifikátu"
            + " předchozího razítka.",

            "Pokud je u právě ověřovaného podpisu či pečeti šance na úspěšné ověření"
            + " u takovýchto validátorů, aplikace AnEP nabídne takovou možnost prodloužení"
            + " digitální kontinuity, resp. zachování ověřitelnosti, kterou sama označuje jako"
            + " **přerazítkování**. Spočívá v přidání takových využitelných revokačních"
            + " informací, jaké má aplikace k dispozici, a v přidání dalšího časového razítka.",

            "Důležité je, že možnost úspěšného ověření platnosti takto „přerazítkovaného“ podpisu"
            + " (pečeti) na validátorech, jako je CzechPOINT, podatelny OVM a další, není"
            + " garantována a nelze se na ni spoléhat.",
    };

    private final VBox contentBox = new VBox(8);
    private final ScrollPane scroll = new ScrollPane(contentBox);
    /**
     * Část závislá na dokumentu — části dvě a tři. Drží se zvlášť, aby výklad pojmu nad ní
     * zůstal stát i tehdy, když se promění nebo zmizí všechno pod ním.
     */
    private final VBox documentBox = new VBox(8);
    /**
     * Místo pro řádek s tlačítky. Drží se zvlášť, aby zůstal dole a nerolovalo se s ním — je to
     * jediná část záložky, na kterou se kliká, a hledat ji po odrolování by bylo popření toho,
     * proč tahle záložka vznikla.
     */
    private final VBox actionRowHolder = new VBox();
    private final VBox root;

    /**
     * Kam vede odkaz z bloku u prvku. Bloky u prvku odkazují na výklad místo toho, aby ho
     * opisovaly — viz {@code AdvisorView.continuityLinked}.
     */
    public enum Outlook {
        /** Celý výklad možností, bez konkrétní varianty — pro odkaz na forenzní balíček. */
        OPTIONS,
        /** Vnořený blok o uchování a prodloužení. */
        PRESERVE_PROLONG,
        /** Vnořený blok o přerazítkování. */
        RESTAMP
    }

    /** Uzly, které umí odkaz otevřít. Drží se, protože odkaz přijde až za dlouho po stavbě. */
    private final Node optionsBlock;
    private final Node preserveProlongBlock;
    private final Node restampBlock;

    public ContinuityView() {
        contentBox.setPadding(new Insets(12));
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        preserveProlongBlock = nestedBlock("varianta „uchování“ a „prodloužení“",
                OPTION_PRESERVE_PROLONG);
        restampBlock = nestedBlock("varianta „přerazítkování“", OPTION_RESTAMP);
        optionsBlock = optionsBlock(preserveProlongBlock, restampBlock);
        contentBox.getChildren().setAll(whatIsBlock(), optionsBlock, documentBox);
        showPlaceholder();
        root = new VBox(scroll, actionRowHolder);
    }

    public VBox getRoot() { return root; }

    /**
     * Otevře výklad, na který odkaz míří, a odroluje k němu.
     *
     * <p>Otevírá se i vnější blok — vnořený uvnitř zavřeného by se neukázal. Rolování jde přes
     * {@code runLater}, protože rozbalením se změní výšky a pozice uzlu se do té doby počítá
     * ze starého rozvržení.
     */
    public void open(Outlook target) {
        AdvisorView.openBlock(optionsBlock);
        Node focus = switch (target) {
            case OPTIONS -> optionsBlock;
            case PRESERVE_PROLONG -> preserveProlongBlock;
            case RESTAMP -> restampBlock;
        };
        if (focus != optionsBlock) AdvisorView.openBlock(focus);
        javafx.application.Platform.runLater(() -> scrollTo(focus));
    }

    /**
     * Odroluje tak, aby uzel byl vidět. Pozice se bere přes scénu, ne přes
     * {@code getBoundsInParent()} — vnořený blok leží o dvě úrovně hlouběji než rolovaný obsah.
     */
    private void scrollTo(Node node) {
        contentBox.applyCss();
        contentBox.layout();
        double y = contentBox.sceneToLocal(node.localToScene(0, 0)).getY();
        double span = contentBox.getBoundsInLocal().getHeight()
                - scroll.getViewportBounds().getHeight();
        scroll.setVvalue(span > 0 ? Math.max(0, Math.min(1, y / span)) : 0);
    }

    /** Řádek s tlačítky, který staví {@link AdvisorView}. Předává se jednou, při sestavení okna. */
    public void setActionRow(Node row) {
        actionRowHolder.getChildren().setAll(row);
    }

    /**
     * Obsah pro právě ověřený dokument, nebo {@code null} pro výchozí podobu záložky.
     *
     * <p>Volá se při každém ověření, i když se nenabízí nic — jinak by tu po dokumentu, který
     * něco nabízel, zůstal viset jeho text u dokumentu, který nenabízí nic. Výkladu pojmu nad tím
     * se to netýká: ten na dokumentu nezávisí a zůstává.
     */
    public void setBlock(Node block) {
        if (block == null) showPlaceholder();
        else documentBox.getChildren().setAll(block);
    }

    /** První část: výklad pojmu, složený. */
    private static Node whatIsBlock() {
        VBox body = paragraphs(WHAT_IS);
        return AdvisorView.collapsibleBlock("Co je digitální kontinuita", body, false);
    }

    /**
     * Tělo výkladového bloku: odstavce pod sebou, každý se svým značkováním. Odstavec
     * {@link #RULE} se nesází, kreslí se na jeho místě vodorovná čára; odstavec začínající
     * {@link #BULLET} se vysází jako odrážka.
     */
    private static VBox paragraphs(String... markup) {
        VBox body = new VBox(8);
        for (String m : markup) {
            if (RULE.equals(m)) body.getChildren().add(rule());
            else if (m.startsWith(BULLET)) {
                body.getChildren().add(bullet(m.substring(BULLET.length())));
            } else body.getChildren().add(AdvisorView.boldMarkup(m));
        }
        body.setPadding(new Insets(0, 12, 8, 12));
        return body;
    }

    /**
     * Jedna odrážka: puntík ve vlastním sloupci a text vedle něj.
     *
     * <p>Ne puntík na začátku textu — ten by se při zalomení choval jako slovo a další řádky by
     * začínaly pod ním. Text roste do zbylé šířky ({@code Hgrow}), jinak by si {@link TextFlow}
     * v {@link HBox} vzal svou přirozenou šířku a odrážka by přetékala z bloku.
     */
    private static Node bullet(String markup) {
        Label dot = new Label("•");
        dot.setStyle("-fx-font-size: 12px; -fx-text-fill: #111111;");
        dot.setMinWidth(10);
        TextFlow flow = AdvisorView.boldMarkup(markup);
        HBox row = new HBox(4, dot, flow);
        HBox.setHgrow(flow, Priority.ALWAYS);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(0, 0, 0, 10));
        return row;
    }

    /**
     * Předěl uvnitř výkladového bloku. Světlejší a odsazený od okrajů, aby dělil odstavce a ne
     * bloky — čára v plné šíři a plné sytosti se čte jako konec bloku.
     */
    private static Node rule() {
        Separator sep = new Separator();
        sep.setPadding(new Insets(4, 40, 2, 40));
        sep.setStyle("-fx-opacity: 0.5;");
        return sep;
    }

    /**
     * Druhá část: co aplikace nabízí, ve dvou variantách.
     *
     * <p>Varianty jsou <b>vnořené</b>, ne tři bloky vedle sebe: čtou se až po úvodu nad nimi
     * a samostatně by visely bez opory. Dělení je podle toho, co úkon udělá s dosavadní řadou
     * razítek — uchování a prodloužení na ni navazují, přerazítkování ji nad dokumentem v jeho
     * dnešní podobě začíná znovu.
     *
     * <p>Složené jsou i vnořené bloky: kdo si otevře výklad možností, ještě nemusí chtít čtení
     * o obou variantách. Otevřený vnořený blok by navíc odstrčil zbytek záložky o dvě obrazovky.
     */
    private static Node optionsBlock(Node... variants) {
        VBox body = paragraphs(OPTIONS);
        body.getChildren().addAll(variants);
        return AdvisorView.collapsibleBlock(
                "Jaké možnosti udržování digitální kontinuity nabízí AnEP", body, false);
    }

    /** Jedna varianta úkonu jako blok uvnitř bloku. */
    private static Node nestedBlock(String title, String... markup) {
        return AdvisorView.collapsibleBlock(title, paragraphs(markup), false);
    }

    private void showPlaceholder() {
        Label l = new Label("Zatím nebyl ověřen žádný dokument. Až se tak stane, bude tu stát, "
                + "co ho udrží ověřitelným a do kdy.");
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 12px; -fx-text-fill: #555555;");
        documentBox.getChildren().setAll(l);
    }
}
