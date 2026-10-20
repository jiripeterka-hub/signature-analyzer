# Schopnosti aplikace AnEP detailně

* aplikace je postavena na knihovně DSS (Digital Signature Service) od EK, kterou využívá jako tzv. SVA (Signature Validation Application). Aplikace AnEP sama je tzv. DA (Driving Application).
* pracuje se zaručenými, uznávanými a kvalifikovanými elektronickými podpisy a pečetěmi ve formátech PAdES, XAdES, CAdES i JAdES, s časovými razítky dle RFC 3161
* platnost elektronických podpisů, pečetí a časových razítek ověřuje primárně dle standardů, ale umožňuje jednorázově aplikovat i určité ústupky, konkrétně:
   * využít i revokační informace, vydané po expiraci podpisového/pečetícího certifikátu
   * využít i revokační informace, vydané již expirovaným vydavatelem
   * ignorovat zastaralost algoritmu SHA-1
   * ověřovat i podpisy v nereferenčním formátu PDF-NOT-ETSI (starší podpisy v PDF, typicky z doby před eIDAS, bez povinných podepsaných atributů)
   * v základním nastavení je všechny naráz zapíná/vypíná jediná volba „Ověřování podpisů a pečetí dle standardů“; jednotlivě se přepínají v pokročilém nastavení
   * tři z nich — všechny kromě SHA-1 — zapne pro jedno ověření i odkaz „Ověřit s ústupky podatelny katastru“ v posouzení vkladové listiny (viz Záložka Analýza)
* ústupky platí **jednorázově** — po ověření se nastavení vrací do výchozího (striktního) stavu, aby se kladný verdikt nemohl tiše opřít o volbu zapnutou před měsícem. Vlastních úložišť se to netýká, ta platí, dokud je uživatel neodebere.
* aplikace **měří, zda se ústupek na výsledku skutečně podílel**: ověření proběhne znovu bez něj a výsledky se porovnají. U kladného verdiktu se pak vypíšou jen ústupky, bez kterých by závěr nevznikl, nikoli ty, které byly pouze zapnuté. Totéž platí pro vlastní úložiště a pro zadaný čas ověření; do protokolu jde týž výčet.
* vedle ústupků má aplikace dvě samostatná nastavení, která ústupky nejsou:
   * **ověření k zadanému datu a času** — jediný způsob, jak zjistit, jak by dokument dopadl tehdy (a ne dnes)
   * **ověřování časových razítek podle standardů** — přepíná mezi posuzováním razítka podle řádné doby platnosti certifikátu TSA (přísnější, odpovídá CzechPOINTu) a podle okamžiku vyřazení služby z důvěryhodných seznamů (tzv. sunset, odpovídá standardům)

## Co aplikace přečte

* formát se pozná **podle obsahu souboru, nikoli podle přípony** — přejmenovaný soubor aplikaci nezmate a kontejner uvnitř kontejneru se otevře také
* nosiče a prvky, které přečte a ověří:
   * **PDF** — PAdES všech úrovní (B-B až B-LTA), podpisy PDF-NOT-ETSI, podpisy PKCS #7 a dokumentová (archivní) časová razítka
   * **ASiC-S / ASiC-E** — .asice, .asics, .sce, .scs i kontejner ASiC uložený s příponou .zip
   * **archiv ZIP** — obyčejný archiv, který není kontejnerem ASiC, se otevře v prohlížeči obsahu; soubory v něm lze předat k ověření jednotlivě
   * **odloučené a samostatné podpisy** — CAdES (.p7s, .p7m, .cms), XAdES (.xml), JAdES (.json, .jose, .jws)
   * **samostatná časová razítka** (.tst a razítka v kontejnerech) jako plnohodnotný autentizační prvek s vlastní kartou
   * **datové zprávy ISDS** (.zfo) — zprávy i doručenky
   * **ODF** — ODT, ODS, ODP s podpisy XAdES v META-INF/documentsignatures.xml
   * **OOXML** — DOCX, XLSX, PPTX; podpis se neověřuje, ale aplikace do něj nahlédne (viz níže)
   * **evidenční záznamy** — RFC 4998 (.ers, .er) i XMLERS, samostatné i vložené
   * **holý certifikát, CRL seznam a odpověď OCSP** — neověřují se (nejde o podepsané dokumenty), otevřou se rovnou ve vlastním prohlížeči
* zvláštní případy při čtení:
   * **odloučený podpis** — podepsaný obsah se přikládá jako druhý soubor; týmž tlačítkem se přikládá i evidenční záznam k dokumentu
   * **heslem chráněné PDF** — aplikace si o heslo řekne teprve tehdy, když bez něj dokument opravdu neotevře; u souboru, který nese jen vlastnické heslo a čtení nebrání, se neptá vůbec
   * **osiřelý podpis** — podpis, který se nepodařilo zpracovat, se v přehledu neztratí, ohlásí se jako nerozpoznaný druh prvku
   * **prázdná podpisová pole** — pole připravená k podpisu se vypíšou také, včetně toho, zda by byl podpis v nich viditelný a co by po podpisu uzamklo

## Důvěra a revokační informace

* kotvy důvěry pocházejí z **evropských důvěryhodných seznamů (LOTL/EUTL)** — kolem třiceti národních seznamů a několika tisíc certifikátů; ukládají se do místní mezipaměti, takže start aplikace nečeká na síť, čerstvost se dokontroluje na pozadí a seznam jde kdykoli načíst znovu ručně
* vedle toho se čte **kořenové úložiště Windows**
* aplikace si ze seznamu pamatuje nejen to, že je certifikát důvěryhodný, ale i **jakou službu poskytuje, pod jakým poskytovatelem a do kdy byla v seznamu vedena** — bez toho by nešlo posoudit razítko od služby, která mezitím ze seznamu zmizela nebo jí byl odebrán kvalifikovaný status
* **vlastní úložiště důvěryhodných certifikátů** — kotvy, které evropské seznamy neuvádějí (interní firemní autorita, starší komerční linky); jedním tlačítkem se vloží vydávající autority tuzemských komerčních linek (I.CA, PostSignum, eIdentity, NCA, KB). Aplikace vždy přizná, že závěr stojí na kotvě, kterou jiný nástroj nemá odkud vzít.
* **vlastní úložiště CRL seznamů** — záložní zdroj pro staré podpisy, kde už vydavatel nic nezveřejňuje; aplikace si z něj vybírá ten seznam, který o certifikátu skutečně může vypovídat
* revokační údaje se zjišťují živě, **napřed OCSP, pak CRL jako záloha**, a doplňují se o to, co dokument nese sám
* u každého údaje aplikace sleduje **původ** — z dokumentu, z vlastního úložiště, nebo ze sítě —, protože právě to rozhoduje, zda půjde závěr zopakovat i jinde a později
* údaje se dělí do tří skupin, neboť „přípustný“ a „využitý“ není totéž: o které se závěr opřel, které standardy připouštějí, ale závěr na nich nestojí, a které použít nešlo — u každého i proč
* zvlášť se řekne i to, kde se na stav revokace **správně nikdo neptal**, protože certifikát je sám kotvou důvěry

## Podání výsledku

* výsledek má tři podoby a každá nese svou barvu, kterou drží protokol i všechny záložky: **platný / stav platnosti neznámý / neplatný**
* „neznámý“ není závěr o neplatnosti — je to konstatování, že se platnost nepodařilo prokázat, a aplikace u něj vždy řekne, co konkrétně chybělo a zda to jde ještě opatřit
* **šest záložek**, řazených podle hloubky; první čtyři vidí každý, poslední dvě jen v expertním módu:
   * **Zjednodušený přehled** — verdikt, druh prvku i s výkladem pojmu, podepsaná osoba, vydavatel certifikátu, čas podpisu a vizualizace podpisu; změnil-li se po podpisu obsah dokumentu, upozorní na to hnědožlutým řádkem s čísly dotčených stran a odkazem na Rozšířený přehled
   * **Rozšířený přehled** — totéž v devíti pojmenovaných rozbalovacích blocích: podepsaná osoba, vizualizace, důvody podpisu, certifikát, časová razítka i s vydavatelem a koncem platnosti jeho certifikátu, evidenční záznamy, rozhodný okamžik, kontrola revokace a použité kryptografické algoritmy (algoritmus, délka klíče a hašovací funkce podpisu i každého časového razítka, které ho kryje, u razítek zvlášť hašovací funkce jejich podpisu a otisku orazítkovaných dat; prvky jsou odděleny čarou); nad kartami prvků stojí blok **Prázdné podpisové pole** a — změnil-li se po podpisu obsah stránek — rozbalený blok s hnědožlutým záhlavím **Po podepsání se v dokumentu změnil obsah**: uvede dotčené strany, upozorní, že jiné validátory mohou takovou změnu hodnotit jinak (například neplatností posledního podpisu nebo neznámým stavem jeho platnosti), a nabídne náhled dokumentu v podobě kryté podpisem i v dnešní podobě (po změnách)
   * **Analýza** — proč to dopadlo takhle (viz níže)
   * **Digitální kontinuita** — jak dlouho dokument zůstane ověřitelný, kdo ho ještě ověří a co a do kdy je třeba udělat; zde stojí i tlačítka úkonů
   * **Detailní rozbor** — vše, co o dokumentu vydá diagnostika DSS: certifikační cesty, důvěryhodná služba a její stav v seznamech, časová razítka, algoritmy a časy, rozsah podpisu, podpisový slovník PDF, detekce změn po podpisu, záznamy VRI a globální validační data, struktura podpisů CMS a kryté objekty<mark>; na začátku záložky stojí tlačítko **Inspektor** podpisové struktury — podle formátu Inspektor PDF, CAdES, ASiC, XAdES nebo JAdES (viz Vlastní prohlížeče); karty prvků se jmenují stejně jako v Rozšířeném přehledu a nesou jméno podepsané osoby; označení, pod kterým prvek vede knihovna DSS, je v kartě jako řádek „Identifikátor DSS“, podle kterého se prvek dohledá v Protokolech DSS</mark>
   * **Protokoly DSS** — surové zprávy knihovny (jednoduchá i podrobná), ke stažení jako XML nebo PDF
* každou záložku kromě Zjednodušeného přehledu lze **odpojit do vlastního okna** a mít dvě části výsledku vedle sebe
* ověřování běží **na pozadí**, okno zůstává ovladatelné
* chybí-li důvěryhodné seznamy některé země nebo jsou-li uložené staré, řekne to aplikace v hlavičce nad kartami — je to údaj o tom, co mohla mít k dispozici
* hlavička uvádí, kdy aplikace své kopie seznamů naposledy ověřila u zdroje; ikona ⓘ za ní na klepnutí ukáže naposledy vydaný národní seznam (zemi, pořadové číslo, datum vydání) a karta Analýzy u každého prvku seznam, podle kterého se jeho certifikát posuzoval

## Záložka Analýza — bloky

* **Formát podpisu** — druh, zapouzdření, úroveň a odpověď na otázku, jde-li o referenční formát podle prováděcího rozhodnutí Komise (EU) 2015/1506
* **Zjištěné důvody a doporučení** — očíslované důvody: co se stalo a co pomůže
* **Průběh ověřování** — za jakých pravidel se ověřovalo, jak aplikace postupovala a čím to skončilo; uvnitř podrobný výčet revokačních údajů s odkazy „Ukaž“ a „Ulož“ u každého
* **Poznámky k výsledku** — co verdikt nezměnilo, ale čtenář to má znát; uvnitř podblok s připomínkami samotné knihovny DSS
* **Možné právní účinky** — jen tam, kde plynou přímo z výsledku a z textu předpisu (eIDAS čl. 25, § 6 odst. 2 zákona č. 12/2020 Sb.); zapsaný osobní doklad se přitom hledá ve všech položkách serialNumber předmětu certifikátu, nejen v první — certifikát může vedle čísla dokladu nést i klientské číslo vydavatele
* **Certifikační podpis**, **Zámek dokumentu**, **Protipodpis (kontrasignatura)** — je-li čím je naplnit
* **Kde je šance na úspěšné ověření** a jeho zrcadlo **Kde ověření nebude úspěšné** — odhad chování jiných nástrojů, vždy v možnosti, ne v jistotě
* bloky o **dokumentu** (nikoli o jednotlivém prvku), stojící nad kartami prvků — jen v expertním módu, aby Analýza běžného uživatele nezahlcovala:
   * **Shoda s normou PDF/A** — deklaruje-li dokument nějaký profil PDF/A, prověří se, zda mu skutečně dostojí (XMP je prohlášení, ne shoda); blok je záměrně bezbarvý, protože nekonformní PDF/A je pořád platně podepsaný dokument
   * **Co v souboru přibylo po podpisu** — přibyla-li za posledním kryjícím prvkem jen validační data či jiné rozšíření dokumentu a obsah se nezměnil; změna obsahu stojí v Rozšířeném přehledu
   * **Požadavky na vkladovou listinu** — u každého PDF s vnitřními podpisy posoudí technické požadavky ČÚZK na elektronickou vkladovou listinu: splňuje / nesplňuje / nelze posoudit. Vychází z postupu podatelny katastru, která ověřuje nejprve podle standardů a jinak může využít službu SecuSign Validation: podpis platný s ústupky kromě SHA-1 požadavek splňuje, SHA-1 vede k „nesplňuje“, podpis neznámé platnosti k „nelze posoudit“ s odkazem „Ověřit s ústupky podatelny katastru“, a u nereferenčního formátu blok upozorní, že podatelna jeho přijímání chystá omezit
   * **odkazované dokumenty** z přílohy eLegalizace

## Důvody, situace a připomínky knihovny

* <mark>**35 důvodů**</mark> — výklad pro čtenáře; jeden důvod shrnuje i několik různých zjištění pod jedno vysvětlení a jedno doporučení
* každý důvod má stejnou stavbu a pořadí částí: **popis problému** (povinný), **Technicky** (nepovinný, pod odkazem „Podrobněji …“), **Co pomůže** (povinný), **Pro zkušenější** (nepovinný, typicky vlastní CRL seznam nebo vlastní kotva důvěry)
* některé důvody mají **náhradní znění** vybíraná podle okolností — jinak zní věta o razítku, jehož autorita mezitím expirovala, a jinak o razítku, které neobstálo z jiné příčiny; údaje z dokumentu se do vět doplňují
* **silnější nález ten slabší umlčí** — u podpisu s porušenou integritou se neradí povolit toleranci k revokačním údajům
* tam, kde řešení není, se žádné nepředstírá
* **52 situací** — jemnější členění zjištění, ze kterého se důvody odvozují; mají vlastní vysvětlivky
* **29 výkladů k připomínkám knihovny DSS** — anglická hlášení DSS přeložená do věty, která čtenáři řekne, co znamenají a proč na výsledku nic nezměnily
* všechny tyto texty leží v souborech `texty/duvody.txt`, `texty/situace.txt` a `texty/dss-pripominky.txt`, takže je lze upravovat bez zásahu do kódu; číselník důvodů se dá vytisknout

## Vlastní prohlížeče

* **prohlížeč kontejnerů** — ZIP (ASiC, ODF, OOXML i prostý archiv) se stromem položek a rolemi u známých jmen, CMS a PKCS #7, XML, JAdES s vloženým dokumentem, datová zpráva ISDS i přílohy PDF. U vybraného prvku se vypíšou údaje a náhled (text, obrázek, jinak šestnáctkový výpis; XML se zformátuje) a prvek jde uložit samostatně, rozbalit s celým kontejnerem, otevřít v přidružené aplikaci, otevřít jako vnořený kontejner, poslat zpět k plnému ověření v AnEP nebo z něj zkopírovat otisk SHA-256. Nad stromem je filtr podle názvu.
* <mark>**Inspektor podpisové struktury** — jen v expertním módu, tlačítkem na začátku Detailního rozboru; podle formátu se jmenuje Inspektor PDF, CAdES, ASiC, XAdES nebo JAdES, a je-li k dokumentu předložen externí podpis, rozebírá ten. Ukazuje podpisovou strukturu tak, jak je v souboru uložena, ve stromu, který začíná sbalený: podpisy a pečetě, všechna časová razítka včetně vnořených v podpisech, u PDF slovník /DSS, záznamy /VRI a revize dokumentu — /DSS ve výsledné podobě s údajem, ve které revizi položka přibyla, i v podobě každé revize zvlášť; revizi bez jediného objektu pozná. Rozkládá podepisující, atributy a vlastnosti podpisu, razítka, certifikáty, seznamy CRL a odpovědi OCSP a přepočítává otisky podepsaných dat, razítek i podpisového certifikátu. Pruh nad stromem ukazuje revize PDF a rozsah, který vybraný podpis kryje. U prvků spárovaných s výsledkem ověření uvede odděleně od zjištění o souboru, co s nimi udělal DSS — výsledek podpisu či razítka, roli certifikátu v řetězu, který revokační údaj rozhodl a který nebyl přijat nebo použit. Odkazy vedou mezi souvisejícími prvky a ke všem výskytům téhož prvku; každý prvek jde uložit. O výsledku ověření Inspektor nerozhoduje.</mark>
* **prohlížeč certifikátů** — subjekt, vydavatel, sériové číslo, doba platnosti, algoritmy, klíčová a rozšířená užití česky, základní omezení, přístupový bod OCSP a distribuční body CRL, prohlášení podle QcStatements (kvalifikovanost, účel, prostředek QSCD, limit transakce, role podle PSD2) a zapsaný osobní doklad (číslo OP nebo pasu), i když předmět nese víc položek serialNumber
* **prohlížeč CRL seznamu** — okno se otevře rovnou na tom certifikátu, o který jde, a není-li na seznamu, řekne to; hlavička seznamu (vydavatel s odkazem na jeho certifikát, datum vydání i příštího, číslo seznamu, jde-li o deltu, počet záznamů), řazení podle sloupců a hledání sériového čísla šestnáctkově i desítkově
* **prohlížeč odpovědi OCSP** — stav odpovědi, kdo odpověděl a kdy, nesené certifikáty a pro každý dotázaný certifikát sériové číslo, hashovací funkci, stav, datum a důvod odvolání pojmenovaný slovem a doby platnosti odpovědi
* **přílohy dokumentu** — sbírají se ze tří míst (jmenný strom vložených souborů, anotace na stránce, pole přidružených souborů u PDF/A-3) a ukazují se pohromadě; u heslem chráněného dokumentu se vytahují už dešifrované, a „Ověřit v AnEP“ je u nich nejužitečnější volba
* **náhled obsahu dokumentu** — okno s listováním po stránkách, **přepínač podob** (dnešní soubor proti tomu, co kryjí jednotlivé podpisy a razítka), ukazatel stran s rozdílem a skok na ně; z bloku o změně obsahu v Rozšířeném přehledu se otevře rovnou na dotčené straně, v podobě kryté podpisem, nebo v dnešní podobě
* okna jsou **nemodální**, takže se čtenář může dál pohybovat po záložkách nebo si položit dva prvky vedle sebe

## Digitální kontinuita

* otázku „do kdy je dokument ověřitelný“ zodpovídá **třemi čteními**, protože to není vlastnost dokumentu, nýbrž vztah mezi dokumentem a tím, kdo ho ověřuje:
   * **AnEP** — tak, jak se právě ověřovalo (se zapnutými ústupky, vlastními úložišti a zadaným časem)
   * **podle standardů** — validátor bez jakýchkoli odchylek; kde to dnes už nejde, řekne se, dokdy to šlo
   * **CzechPOINT, podatelny a další** — nástroje s vlastní zásobou revokačních informací, tolerantnější i vůči některým požadavkům technických standardů, ale s přísnějším posuzováním časových razítek
* o prvních dvou mluví aplikace v jistotě, o třetím okruhu **vždy jen v možnosti** — nezná jejich verzi, nastavení ani obsah jejich zásob
* datum určuje ten článek, který povolí nejdřív: **důkaz existence** (PoE), **dostupnost revokačních informací**, **uznatelnost vloženého údaje** (po expiraci jeho vydavatele) a **uznání razítka** (do vyřazení služby ze seznamů, jinak do konce certifikátu TSA)
* u každého prvku stojí čtyři odpovědi — dokdy ověří AnEP, dokdy validátor podle standardů, dokdy CzechPOINT a podatelny, a co a do kdy je třeba udělat
* zvláštní případ: stojí-li dnešní kladný závěr na údaji staženém právě teď ze sítě a doba, po kterou se na jeho dostupnost dalo spoléhat, už uplynula, **žádné datum se neuvede** a u úkonu stojí „Nejlépe ihned“
* druhý takový případ: nejnovější časové razítko prvku je podepsané algoritmem, který podle ETSI TS 119 312 přestane být spolehlivý dřív, než lhůta uplyne — dnes typicky klíč RSA o 2 048 bitech s datem 1. 1. 2027; i pak u úkonu stojí „Nejlépe ihned“ s vysvětlením, že nové razítko se silnějším algoritmem je třeba přidat před tímto datem. Podepisuje-li slabým klíčem i vybraný zdroj razítek, upozorní na to blok „Jak dlouho je možné zachovat ověřitelnost?“

## Co aplikace udělá s dokumentem

* **žádný z úkonů nesahá na podepsané bajty** — každý zapisuje nový soubor a původní nechává být
* **Zachovat ověřitelnost** — doplní chybějící revokační informace a zafixuje je dalším časovým razítkem, aby dokument přežil expiraci podpisového certifikátu i certifikátů dosavadních razítek. Podle stavu dokumentu se nabízí jedna ze tří variant, které se na tlačítku jmenují:
   * **Uchovat** — plnohodnotná varianta: ověření podle standardů obstálo bez ústupků, řada časových razítek je nepřerušená a její poslední článek stojí na dosud platném certifikátu; doplní, co chybí, a přidá archivní razítko (úroveň B-LTA). Výsledek by měl ověřit kterýkoli validátor
   * **Přerazítkovat** — jen u PDF, kde uchování nejde, protože platnost obstojí jen při mírnějším posuzování: s revokačními údaji, které přísné čtení nepřipouští, s ústupkem, s kotvou z vlastního úložiště, nebo u razítka, které je neporušené, ale neobstálo kvůli vyřazení své služby ze seznamů. Doplní ty chybějící revokační informace, které o certifikátu něco vypovídají (odpověď „nerevokován“ vydanou po expiraci bez závazku vydavatele nevkládá), a přidá razítko nad dnešním stavem — pro validátory, které razítka posuzují podle platnosti jejich certifikátů
   * **Prodloužit** — ověření podle standardů obstálo jen díky tomu, že služba, která vydala poslední razítko, si dosud drží kvalifikovaný status: certifikát posledního razítka už vypršel, nebo je řada razítek přerušená. Nové razítko doloží existenci dokumentu v době, kdy to ještě platí; pro toho, kdo razítka posuzuje podle platnosti jejich certifikátů, ale v řadě mezera zůstane
* tlačítko je vždy jen jedno (varianty se vylučují a nabídne se nejsilnější, na kterou úkon dosáhne) a **úkon je opakovatelný**
* **Vytvořit forenzní balíček** — pro dokumenty, které zachovat nelze, i pro soubory, jejichž formát aplikace vůbec nepřečte: zabalí nedotčený originál do kontejneru ASiC-E spolu se vším ověřovacím materiálem, který se podařilo sehnat, a se soupisem toho, odkud co pochází, a opatří to časovým razítkem. Přidat se dají i další soubory, aby bylo doloženo, že patří k sobě.
* nové razítko kryje celý soubor, takže **úkon je jeden pro celý dokument** a jeho účinek se vypíše po prvcích: kterému podpisu pomůže, kterému ne a proč; prvku, na který nedosáhne, přitom neuškodí
* výsledný soubor si nese v názvu **značku úkonu a datum**, do kdy vydrží (např. `smlouva_Uchovano_do_06_03_2029.pdf`); značka je bez diakritiky a bez mezer a značky se nevrství
* úkon se nabídne **jen tam, kde je možný a zároveň poctivý** — u dokumentu, jehož certifikát byl odvolán dřív, než se dá prokázat existence podpisu, se místo tlačítka řekne, proč to nepomůže; aplikace také upozorní, když vybraný zdroj razítek ověřitelnost prakticky neposune
* co ověření použilo z **vlastních úložišť**, se při zachování ověřitelnosti zapíše do dokumentu — jinak by uchovaný soubor postrádal právě ten důkaz, na kterém jeho verdikt stál
* **výsledný soubor se hned ověří** — na pozadí, touž cestou, jako kdyby ho uživatel otevřel sám; hlavní okno přitom dál ukazuje vstupní soubor — a otevře se **záznam o úkonu**: u každého prvku stav před úkonem a po něm, zda na něj úkon měl dosáhnout, a zda výsledek odpovídá tomu, co aplikace předpověděla (včetně data ve jménu souboru); rozchod se vyjmenuje. Výsledný soubor se do hlavního okna otevře tlačítkem v záznamu
* záznam nese jméno, **formát a úroveň**, velikost a **otisk SHA-256** vstupního i výsledného souboru, **revokační informace** rozdělené na ty, které dokument nesl už předtím, ty, které úkon přidal, a ty, které si ověření výsledku opatřilo mimo dokument, dále okamžik úkonu, zdroj časových razítek, konec platnosti certifikátu nového razítka a jeho **kryptografické parametry** (algoritmus, délka klíče, hašovací funkce podpisu razítka a hašovací funkce otisku dat, s upozorněním, přestane-li algoritmus podle ETSI TS 119 312 být spolehlivý dřív než certifikát); na přání se uloží jako protokol **PDF/A** (předpona `ZU_`), případně s časovým razítkem
* z okna záznamu lze uložit **připomínku do kalendáře** (soubor `.ics`) s předstihem 1, 5, 10 nebo 30 dnů před datem, do kdy je dokument ověřitelný; událost nese jméno výsledného souboru i jeho plnou cestu
* totéž nabízí záložka Digitální kontinuita na konci bloku s nabídkou úkonu, uvádí-li jeho nadpis konkrétní lhůtu (ne „nejlépe ihned“ ani lhůtu uplynulou): připomínka připadne na zvolený počet dnů před koncem lhůty a nese jméno a plnou cestu analyzovaného dokumentu

## Zdroje časových razítek

* spravují se jako **pojmenované profily** s adresou, přihlášením (včetně klientského certifikátu) a algoritmem otisku
* veřejný zdroj Sectigo Qualified umí aplikace **předvyplnit**
* při zadání se zdroj **otestuje**: zjistí se konec platnosti jeho certifikátu (podle něj se pak počítá, kam až úkon posune ověřitelnost) a výčet otisků, které autorita skutečně podporuje, aby se pak nevybíral algoritmus, který odmítne
* u zdroje se zjistí i **algoritmus a délka klíče**, kterým autorita razítka podepisuje (při testu i z každého dalšího razítka); přestane-li klíč být podle ETSI TS 119 312 spolehlivý dřív, než skončí certifikát autority — dnes typicky RSA o 2 048 bitech s datem 1. 1. 2027 —, správa zdrojů na to **upozorní** a doporučí zdroj s delším klíčem
* nedostupnost zdroje se nezamění za odmítnutí

## Protokoly a export

* ze Zjednodušeného přehledu, Rozšířeného přehledu i Detailního rozboru se generuje protokol jako **PDF/A-2b**, tedy v archivním formátu, který přijímají Formátová pravidla Národního archivu
* protokol nese záhlaví s údaji o souboru a okamžikem ověření, vizualizaci podpisu v rámečku, výklad pojmů a výčet toho, čím se ověřování řídilo; do protokolu jde i to, co má čtenář na obrazovce sbalené
* dvě volby, obě ve výchozím stavu vypnuté:
   * **vložit ověřovaný soubor** — protokol pak nese i soubor, o kterém vypovídá, takže se dá ověřit znovu z jediného souboru; vysází se ale jako PDF/A-3
   * **opatřit časovým razítkem** — doloží, kdy protokol vznikl a že se od té doby nezměnil; nabídne se jen tehdy, je-li nastavený nějaký zdroj
* v expertním módu jdou stáhnout původní zprávy DSS jako **XML nebo PDF**

## Zvláštní obsahy

* **datové zprávy ISDS** — průkazný otisk je v kvalifikovaném razítku zprávy, ne v poli `dmHash`, které je jen jeho kopií; aplikace ho přepočítá ze syrových bajtů a porovná s tím, co kryje razítko. Zpráva se pak čte jako zpráva (kdo komu poslal, pod jakou značkou, kdy dodána a kdy doručena, co v ní bylo za písemnosti), včetně číselníků ISDS.
* **evidenční záznamy** — ERS i XMLERS se ověří a řekne se, zda a k jakému okamžiku dokládají existenci předloženého souboru a co z nich naopak neplyne; kryje-li záznam víc souborů, otevře se okno, kde se ostatní přiloží a zkontrolují proti otiskům záznamu
* **odkaz z eLegalizace** — příloha `legalisation-metadata.xml` odkazuje otisky na jiný dokument, který v souboru není; aplikace tu vazbu rozpozná, sama otevře okno, nechá druhý dokument přiložit a ověří **všechny** deklarované otisky. Potvrzený řetěz jde i do protokolu a pokračuje se, nese-li odkázaný dokument odkaz na další.
* **podpisy JAdES** — JSON podle JWS se rozebere na součástky: vložený dokument (který odtud jinak nikdo nevydá), chráněná hlavička po parametrech, certifikáty podepisujícího a každá nepodepsaná vlastnost jako samostatný prvek **v pořadí, ve kterém opravdu leží** (v JAdES to pořadí něco znamená: archivní razítko kryje to, co mu předchází)
* **vizualizace a původ podpisu** — z PDF se vytáhne obrázek podpisu tak, jak ho vidí čtenář (včetně otočených stránek), a z metadat i program, kterým byl dokument podepsán
* **zámek dokumentu a certifikační podpis** — PDF může být uzamčeno samotným podpisem, a není to certifikační podpis; aplikace čte obě poloviny zápisu (rozsah i úroveň povolených změn) a řekne, co je uzamčeno. Zámek nic neříká o platnosti a nebrání zachování ověřitelnosti.
* **protipodpis (kontrasignatura)** — podpis, který nepodepisuje dokument, nýbrž jiný podpis, se pozná a řekne se, čí podpis podepisuje
* **podpisy v OOXML** — neověřují se (formát potřebuje `RelationshipTransform`, který DSS nezná, takže není doloženo, že podpis kryje obsah, který čtenář vidí). Karta proto nemá barvu výsledku, má vlastní „neověřeno“, a ukáže odděleně: co podpis o sobě tvrdí (úroveň podle struktury B / T / LT / LTA, vložené revokační informace) a co se ověřit dá i tak (samotný token časového razítka, doba platnosti certifikátu, kvalifikovanost podle QcStatements, přítomnost vydavatele v EUTL). Nabídne se forenzní balíček.

## Nastavení, úložiště a provoz

* **základní nastavení** má čtyři věci: přepínač přísnosti, ověřování časových razítek podle standardů, expertní mód (zpřístupní Detailní rozbor a Protokoly DSS a bloky o dokumentu v Analýze) a okno „O aplikaci“; změny se projeví ihned, bez restartu
* **pokročilé nastavení** přidává jednotlivé ústupky, ověření k zadanému datu a času, správu vlastních důvěryhodných certifikátů a vlastních CRL seznamů, zdroje časových razítek a ruční obnovu důvěryhodných seznamů EU
* vše uživatelské leží v `%APPDATA%\SignatureAnalyzer`: nastavení, profily zdrojů razítek, vlastní úložiště CRL, vlastní důvěryhodné certifikáty a mezipaměť důvěryhodných seznamů; do systému aplikace jinak nezasahuje
* aplikace se šíří pod **EUPL verze 1.2** (má závazné české znění) a dodává se jako instalátor .msi i jako **přenosná verze**, obojí s vlastním běhovým prostředím
* start je zrychlen **předehřátím ověřovací cesty** a mezipamětí důvěryhodných seznamů, takže první ověření netrvá znatelně déle než druhé
* licence použitých knihoven se dodávají s každou distribucí a jejich seznam se generuje z těch souborů, které se opravdu balí
* aplikace běží na Windows; přeloží se a běží i na Linuxu a macOS

## Kde jsou hranice — co aplikace záměrně nedělá

* **nepodepisuje** — umí přidat časové razítko a doplnit ověřovací materiál, ale podpis ani pečeť nevytváří
* **nevydává právní stanoviska** — právní účinky uvádí jen tam, kde plynou přímo z výsledku ověření a z textu předpisu
* **nepředvídá cizí validátory** — o jiných nástrojích mluví v možnosti, ne v jistotě
* **nevytváří evidenční záznamy** — ověřit je umí, vydat ne
* **neověřuje podpisy v kancelářských formátech OOXML** — nahlédne do nich a řekne, co o sobě tvrdí, ale verdikt nevydá
* **nezakrývá ústupky** — kladný verdikt, který stojí na povoleném ústupku, to má u sebe napsáno
* **nedává záruku** — výstupy slouží k seznámení s problematikou a k vlastnímu posouzení, ne jako závazný posudek
