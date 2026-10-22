# Politika uchovávání elektronických podpisů AnEP

**Analyzátor elektronických podpisů (AnEP) — politika uchovávání ověřitelnosti**

==Verze 1.9 · stav ke dni 17. 10. 2026== · AnEP 1.0.0, knihovna DSS 6.5

## 1 Úvod

### 1.1 K čemu tento dokument je

Elektronický podpis jde ověřit jen po určitou dobu: certifikáty expirují, vydavatelé přestávají zveřejňovat revokační informace a algoritmy časem přestávají být považovány za spolehlivé. Uchovat ověřitelnost znamená doplnit do dokumentu, co bude pozdější ověření potřebovat, a zafixovat to časovým razítkem dřív, než to přestane jít.

AnEP takový úkon nabízí, a na rozdíl od ověřování přitom **dokument mění**: výsledný soubor si s sebou ponese, co do něj úkon vložil. Proto má říct otevřeně, podle čeho rozhoduje, co nabídne, co do dokumentu vloží a co z toho slibuje. AnEP není služba vytvářející důvěru a jeho výstup nemá právní účinky výstupu kvalifikované služby uchovávání.

Dokument je stavěný podle ETSI TS 119 511 (politika a bezpečnostní požadavky na služby uchovávání) a používá pojmy ETSI TS 119 512 (cíle uchování, modely ukládání, schémata). Stejně jako politika ověřování převzala strukturu ETSI TS 119 172-1 jen tam, kde má u aplikace předmět, i tento dokument vynechává provoz, personál, úložiště a odpovědnost poskytovatele: u aplikace provozované uživatelem na vlastním počítači předmět nemají.

V pojmech TS 119 511 je tento dokument **popisem profilů uchování** (6.4) a **politikou důkazů o uchování** (4.3.4, 6.5) v jednom. Politikou služby (4.3.2) ani prohlášením o postupech (4.3.1) není, protože AnEP není poskytovatel služby, a identifikátor politiky služby uchovávání podle 4.3.2 proto nepoužívá.

### 1.2 Vztah k politice ověřování

Uchování stojí na výsledku ověření. Co je platné, jak se posuzují razítka a revokační údaje, které kotvy důvěry platí a co je ústupek, stanoví **Politika ověřování elektronických podpisů AnEP**; tento dokument na ni odkazuje a nic z ní neopakuje. V pojmech TS 119 511 (4.3.5, 6.6) je politika ověřování politikou ověřování podpisu, na kterou odkazují profily uchování: podle ní se validační data sbírají a ověřují.

Rozdělení je věcné: politika ověřování popisuje **dnešní** ověření, tento dokument **předpověď do budoucna** — dokdy bude dokument ověřitelný — a **zásah** do dokumentu. Obě politiky se vydávají se shodným číslem verze a vztahují se k témuž stavu aplikace; změna jedné znamená novou verzi obou.

### 1.3 Uchovávání v AnEP v kostce

- Úkon spouští vždy uživatel. Aplikace sama nic nehlídá, neukládá ani neopakuje; lhůtu sdělí a umí ji zapsat do kalendáře.
- Původní soubor zůstává nedotčen, úkon vytvoří nový.
- Úkon je jeden pro celý dokument. Nabídne se nejsilnější varianta, na kterou dosáhne aspoň jeden prvek: **uchování**, **přerazítkování**, nebo **prodloužení**. Kde zachovat ověřitelnost nejde, nabízí se **forenzní balíček**.
- Úkon se nabídne jen tam, kde je možný a zároveň přínosný; kde ne, aplikace řekne proč.
- Výsledný soubor se hned ověří a výsledek se porovná s tím, co aplikace před úkonem předpověděla; vznikne záznam o úkonu.

## 2 Identifikace

| Položka | Hodnota |
|---|---|
| Název | Politika uchovávání elektronických podpisů AnEP |
| Verze | ==1.9 ze dne 17. 10. 2026== |
| Platí pro | AnEP 1.0.0 (repozitář signature-analyzer, stav po commitu ==11f98cf==) |
| Navazuje na | Politika ověřování elektronických podpisů AnEP, verze ==1.9== |
| Struktura dokumentu | ETSI TS 119 511 V1.1.1 (2019-06) |
| Pojmy a schéma uchování | ETSI TS 119 512 V1.2.1 (2023-05), schéma F.4 „pds+wos+aug“ |
| Cílová úroveň podpisů | B-LTA podle ETSI EN 319 142-1 (PAdES), EN 319 122-1 (CAdES), EN 319 132-1 (XAdES), TS 119 182-1 (JAdES) a EN 319 162-1 (ASiC) |
| Kryptografická omezení | výchozí politika DSS 6.5 podle ETSI TS 119 312 (politika ověřování, kapitola 10) |
| Identifikátor (OID) | nepřidělen |

Politika popisuje chování aplikace. Kde se aplikace změní, mění se i politika; každá verze uvádí, k jakému stavu aplikace se vztahuje, a všechny verze zůstávají k dispozici.

## 3 Model uchovávání

### 3.1 Cíle uchování

TS 119 512 (4.2) rozlišuje tři cíle uchování. AnEP je naplňuje takto:

| Cíl | Význam podle normy | V AnEP |
|---|---|---|
| PDS — uchování elektronických podpisů | dlouhodobě zachovat schopnost podpis ověřit, jeho stav platnosti a důkaz existence podepsaných dat | uchování, přerazítkování a prodloužení |
| PGD — uchování obecných dat | dlouhodobý důkaz existence předaných dat bez ohledu na to, zda jsou podepsaná | forenzní balíček |
| AUG — doplnění důkazů | doplnit důkazy o uchování, které objekt už nese | opakované uchování dokumentu, který už uchován byl |

### 3.2 Model ukládání a schéma

AnEP pracuje v modelu **bez ukládání** (WOS; TS 119 511, 4.1.4; TS 119 512, 4.3.3): důkaz vzniká hned při úkonu a aplikace si nic neponechá — ani dokument, ani jeho otisk, ani důkaz. Nejbližším schématem podle TS 119 512 je **F.4 — doplňování podpisu bez ukládání** (`pds+wos+aug`): důkazem o uchování je archivní časové razítko uvnitř podpisu (CAdES archive-time-stamp-v3, XAdES ArchiveTimeStamp, PAdES document-time-stamp) a ověřuje se podle normy daného formátu podpisu.

Od schématu F.4 se AnEP odchyluje ve dvou bodech:

- F.4.6 a TS 119 511 (7.14) ukládají službě sledovat sílu použitých algoritmů a důkazy sama doplňovat. AnEP nic nesleduje; opakovat úkon před koncem lhůty je na uživateli. Aplikace mu k tomu dává lhůtu (kapitola 7) a připomínku do kalendáře (9.4).
- U odděleného podpisu má podle F.4.5 služba udržovat vazbu podpisu na podepsaná data. AnEP ji neudržuje; jen ji při úkonu vyžaduje (6.7).

Poznámka k normě: TS 119 512 uvádí v F.4.1 identifikátor schématu `pds+wos+aug`, v F.4.7 `pgd+wos+aug`. Jde zřejmě o překlep; tato politika se drží F.4.1.

### 3.3 Profily uchování

TS 119 511 (6.4) popisuje službu uchovávání jejími profily. Odpovídají jim čtyři úkony AnEP:

| Profil | Cíl | Vstup | Důkaz o uchování |
|---|---|---|---|
| Uchování | PDS, při opakování AUG | PAdES, CAdES, XAdES, JAdES, ASiC | archivní razítko v podpisu; výsledek v úrovni B-LTA |
| Přerazítkování | PDS | PDF, včetně nereferenčních formátů | dokumentové časové razítko |
| Prodloužení | PDS | tytéž formáty jako uchování, navíc PDF-NOT-ETSI | archivní nebo dokumentové časové razítko |
| Forenzní balíček | PGD | libovolný soubor | kontejner ASiC-E s časovým razítkem |

Všechny profily pracují v modelu bez ukládání. Profil platí po dobu platnosti verze této politiky, ve které je popsán. Profily nemají vlastní identifikátor; označuje je název úkonu a verze politiky.

### 3.4 Co AnEP nedělá

- Nepodepisuje a podpis neobnovuje; přidává jen časová razítka a validační data.
- Nevytváří evidenční záznamy (ERS); umí je jen ověřit.
- Nic neukládá a nehlídá lhůty sám; nesleduje vývoj kryptografie.
- Nepřijímá místo podepsaných dat jen jejich otisk.
- Nezaručuje, že výsledek uzná jiný ověřovatel (kapitola 10).

## 4 Vstupy a předpoklady

### 4.1 Výsledek ověření

O nabídce úkonu se rozhoduje z téhož ověření, které uživatel vidí na kartě dokumentu, podle politiky ověřování. Validační data, která dokument nese, se ověřují; chybějící se dosbírají on-line a z vlastních úložišť (TS 119 511, OVR-9.3-01 a 9.3-02).

### 4.2 Zdroj časových razítek

- Zdroj se zadává jako pojmenovaný profil s adresou, přihlášením (jménem a heslem, nebo klientským certifikátem) a hašovací funkcí, kterou se počítá otisk dat odesílaný autoritě. Použít lze **kterýkoli zdroj**, který uživatel zadá; veřejný zdroj Sectigo Qualified umí aplikace předvyplnit.
- Při zadání se zdroj **otestuje**: aplikace si vyžádá razítko nad náhodným otiskem (tedy nad ničím, co by mohlo sloužit jako doklad) a zjistí z něj konec platnosti certifikátu autority, hašovací funkce otisku, které autorita přijímá, kvalifikovanost razítka podle důvěryhodných seznamů a algoritmus i délku klíče, kterými autorita razítka podepisuje. Konec platnosti certifikátu a klíč se pak zpřesňují z každého skutečného razítka. U zdroje, jehož kvalifikovanost se při zadání nezjistila (zdroje zadané dřív, než se začala měřit), se doplní z ověření výsledku prvního úkonu: kvalifikovanost razítka, které úkon přidal, posoudí táž cesta proti důvěryhodným seznamům.
- Razítka se žádají protokolem RFC 3161 (OVR-9.2-01). Zda autorita postupuje podle EN 319 421 a razítka odpovídají EN 319 422 (OVR-7.5-02, OVR-9.2-02), aplikace neověřuje; ověřuje jen kvalifikovanost.
- U zdroje, jehož razítka **nejsou kvalifikovaná**, to aplikace řekne při zadání, v nabídce úkonu i ve jménu výsledného souboru. Pro uchování doporučuje kvalifikovaný zdroj (TS 119 511, příloha A, OVR-A-03).
- Podepisuje-li autorita razítka klíčem, který podle kryptografické sady politiky ověřování přestane být spolehlivý dřív, než skončí její certifikát (dnes klíč RSA o 2 048 bitech s datem 1. 1. 2027), aplikace na to upozorní u zdroje i v bloku o dosahu úkonu a doporučí zdroj s klíčem RSA aspoň 3 000 bitů nebo ECDSA.
- Aplikace upozorní také tehdy, když vybraný zdroj ověřitelnost prakticky neposune, protože certifikát jeho autority končí dřív, než kam dokument dosáhne i bez zásahu.

### 4.3 Síť a data

Při úkonu se po síti stahují jen revokační údaje, chybějící certifikáty vydavatelů a časová razítka. Nedostupnost zdroje razítek se nezamění za odmítnutí. Obsah dokumentů aplikace neukládá (TS 119 511, OVR-9.1-01); uchovává jen údaje pro vlastní chod — nastavení, mezipaměť důvěryhodných seznamů, vlastní úložiště certifikátů a CRL a seznam naposledy otevřených souborů.

## 5 Dokdy je dokument ověřitelný

### 5.1 Tři čtení

Otázka, dokdy je dokument ověřitelný, nemá jednu odpověď — závisí na tom, kdo ověřuje. Aplikace proto odpovídá třikrát:

- **AnEP** — tak, jak se právě ověřovalo, včetně zapnutých ústupků, vlastních úložišť a případného zadaného okamžiku.
- **Podle standardů** — validátor bez jakýchkoli odchylek; kde to dnes už nejde, řekne se, dokdy to šlo.
- **CzechPOINT, podatelny a další validátory** s vlastní zásobou revokačních informací, které mohou být tolerantnější k některým požadavkům standardů, ale posuzovat časová razítka přísněji. O nich aplikace mluví vždy jen v možnosti: nezná jejich verzi, nastavení ani obsah jejich zásob.

### 5.2 Co datum určuje

Datum určuje článek, který povolí nejdřív:

- **důkaz existence** — dokdy obstojí časové razítko, které podpis dokládá;
- **dostupnost revokačních informací** — dokdy je vydavatel zveřejňuje;
- **uznatelnost vloženého revokačního údaje** — dokdy platí certifikát jeho vydavatele;
- **uznání razítka** — kvalifikované razítko drží do vyřazení služby z důvěryhodných seznamů, jinak do konce platnosti certifikátu jeho autority.

Kde rozhoduje vyřazení služby, které se předem neví, datum se neuvádí. Algoritmy podpisu a razítek se do data nezapočítávají: ověření na data za poslední aktualizací kryptografické sady reaguje jen varováním (politika ověřování, kapitola 10). Algoritmus nejnovějšího razítka ale ovlivňuje radu, kdy jednat (kapitola 7).

### 5.3 Dosah úkonu a očekávaná doba trvání důkazu

Úkon posune ověřitelnost na **pozdější** ze dvou dat: dokdy dokument vydrží už dnes, a dokdy platí certifikát autority vybraného zdroje razítek. Nové razítko dosáhne jen tam, kam sahá certifikát jeho autority, ale nezkracuje to, co dokument už má.

V pojmech TS 119 511 (4.4) je toto datum **očekávanou dobou trvání důkazu**. Profil ji podle OVR-6.4-06 má uvádět, a AnEP ji uvádí ve jménu výsledného souboru (9.1) a v záznamu o úkonu (9.3).

**Odchylka od OVR-6.4-07 a 6.4-08.** Norma žádá, aby se očekávaná doba trvání důkazu zakládala na odhadu vhodnosti kryptografických algoritmů, a doporučuje k tomu ETSI TS 119 312. AnEP ji počítá z certifikátů a dostupnosti revokačních informací a algoritmy do ní nezapočítává, a to záměrně: samo ověření na data za poslední aktualizací kryptografické sady reaguje jen varováním, takže zkrácené datum by tvrdilo konec ověřitelnosti, který v AnEP nenastane. Měřeno 5. 10. 2026 na sbírce 2 745 souborů s 1 564 prvky: zkrácení podle algoritmů by se týkalo 293 z 488 prvků s datem a všechny by dostaly datum 1. 1. 2027 kvůli klíči RSA o 2 048 bitech autority posledního razítka. Místo zkrácení data aplikace upozorňuje na slabý klíč (4.2, kapitola 7, 9.3).

## 6 Úkony a podmínky jejich nabídky

### 6.1 Společné zásady

- Podepsané bajty se nemění. Úkon zapisuje nový soubor; u PDF jako přírůstkovou aktualizaci.
- Úkon je **dokumentový**: nové časové razítko kryje celý soubor, takže se provádí jednou pro všechny prvky v něm. Prvku, na který nedosáhne, neuškodí — co je na něm dnes sporné, zůstane sporné. Jak se úkon projeví na kterém prvku, aplikace vypíše.
- Varianty se vylučují a nabídne se jediná, v pořadí **uchování, přerazítkování, prodloužení** — nejsilnější, na kterou dosáhne aspoň jeden prvek.
- Úkon je opakovatelný; dokument, který už uchován byl, lze uchovat znovu.

### 6.2 Když nejdou sehnat všechna validační data

TS 119 511 (4.2) uvádí tři možné postupy služby, která nedokáže sehnat všechna potřebná validační data: oznámit selhání, uchovat vše, co lze shromáždit, nebo data dosbírat později. AnEP postupuje podle varianty:

- **Za dokument** stačí jeden prvek, na který úkon dosáhne — to odpovídá postupu „uchovat vše, co lze shromáždit“. Ostatním prvkům úkon neuškodí a výčet řekne, na které nedosáhne.
- **Uchování** se u prvku nenabídne, nelze-li sehnat všechna chybějící data — to odpovídá postupu „oznámit selhání“, a aplikace uvede proč.
- **Přerazítkování** vloží ==z chybějících dat, která má aplikace k dispozici, ta, která o certifikátu něco vypovídají (6.4)== — opět „uchovat vše, co lze shromáždit“==, jen bez údajů, které nikomu nic nedokládají==.
- Data **dosbírat později** AnEP sám neumí. Jediný případ, kdy odklad doporučí, je čerstvě podepsaný dokument (6.3).

### 6.3 Uchování

Uchování doplní chybějící revokační informace a certifikáty a zafixuje je archivním časovým razítkem; výsledkem je úroveň B-LTA, kterou by měl ověřit kterýkoli validátor postupující podle standardů. Nabídne se, platí-li u prvku:

- závěr ověření je platný, nebo data, která k němu chybějí, už v dokumentu jsou a chybí jen důkaz, že tam byla (ten archivní razítko vytvoří);
- ověření proběhlo bez ústupků a bez zadaného okamžiku;
- řada časových razítek je nepřerušená a její poslední článek stojí na dosud platném certifikátu (u podpisu bez razítka se řada neposuzuje — žádná není);
- je co doplnit nebo zafixovat a dá se to opatřit.

U podpisu v úrovni B, který ještě žádné razítko nemá, přidá úkon razítka dvě: první podpis ukotví v čase, druhé je archivní a kryje i validační data doplněná mezi nimi. Podpisový certifikát přitom musí být platný a neodvolaný, protože razítko vytvořené po jeho revokaci nebo expiraci standardy nepřipouštějí (EN 319 122-1, kap. 6.3, požadavek m).

Úkon vloží revokační údaje a certifikáty, na kterých stál závěr ověření, **včetně těch z vlastních úložišť** uživatele: jinak by uchovaný soubor postrádal právě ten doklad. Údaje, které ověření použít nemůže, se nevkládají. Tím úkon naplňuje OVR-9.3-03: dokládá existenci podpisu i validačních dat.

U čerstvě podepsaného dokumentu, jehož závěr stojí na revokačním údaji vydaném dřív než podpis, aplikace doporučí s uchováním počkat, až vydavatel zveřejní údaj novější: uchování by starší údaj zafixovalo natrvalo. Doporučení platí jen pro podpis mladší než 24 hodin; u staršího dokumentu by čekání nebylo k ničemu.

Poškozené časové razítko (nesouhlasí jeho otisk nebo podpis), které o závěru ověření nerozhoduje, protože prvek má jiné platné razítko z doby platnosti podpisového certifikátu (politika ověřování, 8.1), uchování nebrání. Archivní razítko poškozené razítko nespraví, jen ho překryje spolu s ostatním obsahem, a výsledek ověření o něm dál ponese poznámku. Požadavek platného a neodvolaného podpisového certifikátu pro nové podpisové razítko tím dotčen není.

PDF, které nese jen podpisy v nereferenčním formátu (PDF-NOT-ETSI), uchovat nelze: uchování nemůže dodat podepsané atributy, které takový podpis nikdy nenesl. Takový dokument může projít přerazítkováním nebo prodloužením.

### 6.4 Přerazítkování

Přerazítkování přidá k dokumentu nové časové razítko nad dnešním stavem a vloží ty chybějící validační údaje, ==které o certifikátu něco vypovídají (viz níže)==. Je určeno ověřovatelům, kteří časová razítka posuzují podle platnosti jejich certifikátů a revokační informace si mohou doplnit z vlastní zásoby. Nabídne se jen u PDF (včetně nereferenčních formátů) a jen tam, kde uchování nejde, platí-li u prvku:

- závěr ověření je platný, nebo existuje naděje, že ho vydá ověřovatel posuzující tolerantněji (s revokačními údaji, které přísné čtení nepřipouští, s ústupkem, nebo s kotvou z vlastního úložiště);
- řada razítek je nepřerušená podle měřítka těch, komu je úkon určen, a poslední razítko stojí na dosud platném certifikátu;
- revokační informace jsou k dispozici, i v podobě, kterou přísná pravidla nepřipouštějí;
- dokument se neověřoval k zadanému okamžiku.

==Z revokačních údajů, které má aplikace k dispozici, se vloží ty, které o certifikátu něco vypovídají: vydané za jeho platnosti (i od vydavatele, jehož certifikát mezitím vypršel), vydané po expiraci s výslovným závazkem vydavatele (`expiredCertsOnCRL`, `archiveCutOff`, shodný `CertHash`) a ty, které certifikát označují za revokovaný. Odpověď „nerevokován“ vydaná po expiraci bez takového závazku se nevkládá. Podle RFC 5280 (3.3) smí vydavatel záznam o expirovaném certifikátu vypustit, takže jeho mlčení nic nedokládá; EN 319 102-1 (5.2.6) takový údaj připouští jen od vydavatele, o němž je známo, že stav po expiraci drží, a EN 319 411-2 (CSS-6.3.10) k tomu předepisuje právě uvedená rozšíření. Při měření (říjen 2026) takový údaj nevyužil ověřovatel s vlastní zásobou revokačních informací, kterému je přerazítkování určeno — revokaci si opatřil sám —, ani Adobe Acrobat Reader. Totéž pravidlo platí pro údaje, které uchování a prodloužení navíc vkládají u prvku, jehož revokační informace jsou k dispozici jen v podobě, kterou přísná pravidla nepřipouštějí.==

Ostatní formáty se nepřerazítkovávají: jejich nepodepsané atributy umí přestavět jen knihovna DSS, a ta přitom uplatní právě to přísné filtrování, kvůli kterému přerazítkování existuje. Kde validační data chybějí, dokládá razítko jen to, co bylo k dispozici, ne vše, co ověření potřebuje — výsledek ověří kterýkoli validátor jen tehdy, doplní-li si chybějící sám.

### 6.5 Prodloužení

Prodloužení přidá nové časové razítko jako důkaz, že celý dosavadní stav existoval v době, kdy služba, která vydala poslední razítko, ještě měla kvalifikovaný status. Nabídne se, platí-li u prvku:

- ověření podle standardů obstálo, ale jen díky tomu, že služba posledního razítka si dosud drží kvalifikovaný status: certifikát posledního razítka už vypršel, nebo je řada razítek přerušená;
- služba posledního razítka nebyla z důvěryhodných seznamů vyřazena;
- revokační informace jsou k dispozici;
- vypršel-li certifikát posledního razítka, je tento certifikát kotvou důvěry;
- ověření proběhlo bez ústupků, s jedinou výjimkou — revokačního údaje u razítka s expirovaným certifikátem — a bez zadaného okamžiku.

==Spolu s razítkem úkon doplní chybějící revokační informace a certifikáty stejně jako uchování (6.3): vloží jen ty, které ověření použít může, a revokační údaj vydaný po expiraci certifikátu bez závazku vydavatele nevloží. U prvku, jehož revokační informace jsou k dispozici jen v podobě, kterou přísná pravidla nepřipouštějí, platí navíc pravidlo z 6.4. Údaj o certifikátu právě přidaného razítka výsledný soubor nenese a nést ještě nemůže: podle EN 319 142-1 (6.3) se vkládá až před dalším razítkem.==

Pro toho, kdo časová razítka posuzuje podle platnosti jejich certifikátů, v řadě razítek mezera zůstane; prodloužení ji nezacelí. Typickým případem je samostatné časové razítko, jehož certifikát autority už skončil a které drží jen důvěryhodný seznam.

### 6.6 Forenzní balíček

Forenzní balíček zabalí nedotčený originál do kontejneru ASiC-E spolu se vším ověřovacím materiálem, který se podařilo sehnat, se soupisem toho, odkud co pochází (`soupis.xml`), a případně s dalšími soubory, aby bylo doloženo, že k sobě patří. Kontejner opatří časovým razítkem. V pojmech TS 119 512 je to kontejner objektů uchování se soupisem a cílem uchování obecných dat (PGD).

Balíček **ověřitelnost dokumentu neprodlouží**: dokládá jen, že tento materiál v této podobě existoval v daném okamžiku, a podpisu nedodá důkaz existence, který neměl. Čte ho odborník, ne validátor. Nabízí se tam, kde zachovat ověřitelnost všech prvků nejde; v expertním módu i vedle uchování. Balíček lze obnovit novým razítkem nad kontejnerem; jeho lhůtou je konec platnosti certifikátu posledního razítka.

### 6.7 Kdy se nenabídne nic

Uchování, přerazítkování ani prodloužení se nenabídnou, když:

- je prokázána **porušená integrita** — razítko by stvrdilo obsah, o kterém je prokázané, že podepsaný nebyl;
- dokument kryje **evidenční záznam** — ETSI TS 119 122-3 a TS 119 132-3 pak připouštějí jen přidání nebo obnovení záznamu a rozšíření podpisu by záznam zneplatnilo;
- jde o **oddělený podpis bez podepsaných dat**;
- dokument se ověřoval **k zadanému okamžiku**;
- **není vybrán zdroj časových razítek**.

Forenzní balíček v prvních čtyřech případech nabídnout jde, protože dokument nemění. U každého odmítnutí aplikace uvede důvod.

## 7 Lhůty k úkonu

Lhůta k úkonu je **konec ověřitelnosti dokumentu podle AnEP** — dokud je co vložit, vznikne úkonem prvek, který si ponese vše potřebné s sebou. Po této lhůtě už není co vkládat a úkon zmůže jen nové razítko nad dnešním stavem, jehož výsledek dokáží ověřit pouze validátory s vlastní zásobou revokačních informací. Uplyne-li první lhůta dřív než tato záložní, aplikace to řekne a uvede i datum, dokdy záložní varianta zbývá.

**Nejlépe ihned.** Místo data aplikace radí jednat nejlépe ihned ve dvou případech:

- dnešní kladný závěr stojí na revokačním údaji staženém on-line a doba, po kterou se na jeho dostupnost dalo spoléhat, už uplynula — že se ho podařilo stáhnout dnes, neříká nic o tom, zda půjde stáhnout i příště;
- nejnovější časové razítko, které prvek kryje, je podepsané algoritmem, který podle kryptografické sady politiky ověřování přestane být spolehlivý dřív, než lhůta uplyne (dnes typicky klíč RSA o 2 048 bitech, 1. 1. 2027). Ověření v AnEP na to datum reaguje jen varováním, jiné validátory ale mohou takové razítko po něm odmítnout, a nové razítko se silnějším algoritmem proto musí přijít dřív.

**Uplynulá lhůta.** Je-li lhůta pryč, aplikace to řekne i s datem a doporučí jednat hned, protože podklady se dál spíš ztrácejí, než přibývají.

**Dokument s více prvky.** Lhůta v nadpisu je nejbližší z lhůt, které ještě běží; lhůta jednoho prvku, která už uplynula, neomezuje, co úkon dá ostatním. Liší-li se lhůta prvku od lhůty dokumentu, výčet ji u prvku uvede.

## 8 Politika důkazů o uchování

### 8.1 Formát důkazu (OVR-6.5-08)

- **PDF:** validační data se zapíší do slovníku DSS a archivní razítko se přidá jako dokumentové časové razítko v nové přírůstkové revizi. Dokument, který se hlásí k normě PDF/A a splňuje ji, úkon v archivní podobě zachová; změřeno na 80 vyhovujících dokumentech ve 157 průchodech uchováním i přerazítkováním bez jediné ztráty shody. Jde o tvrzení o měření, ne o záruku. ==Nese-li dokument certifikační podpis, který nepovoluje žádné změny (DocMDP P=1), aplikace před úkonem upozorní: EN 319 142-1 (5.4.2.3, 5.4.3) i ISO 32000-2 (DocMDP, tabulka 257) přírůstkovou revizi jen se slovníkem DSS a dokumentovým časovým razítkem za změnu nepovažují, některé čtečky PDF (při měření Adobe Acrobat Reader) ho však mohou vyhodnotit jako nepovolenou změnu a certifikační podpis označit za neplatný. Úkon se nezakazuje, protože ověřovatelé, kterým je určen, mohou výsledek uznat.==
- **CAdES, XAdES, JAdES a kontejnery ASiC** rozšiřuje knihovna DSS na úroveň B-LTA. PDF uložené v kontejneru ASiC se uchovává jako podpis kontejneru, ne jako PDF.
- **Oddělený podpis** se rozšiřuje jen s podepsanými daty, protože archivní razítko kryje i je.

### 8.2 Kryptografie (OVR-6.5-03, 6.5-04)

Hašovací funkci otisku dat, který se odesílá autoritě, určuje profil zdroje razítek (výchozí je SHA-512) a nabízejí se jen funkce, které autorita při testu přijala. Podpisový algoritmus, délku klíče a hašovací funkci podpisu razítka volí autorita; aplikace je zjistí a upozorní na klíč, který podle knihovny DSS přestane být spolehlivý dřív, než skončí certifikát autority (4.2). Kryptografická omezení při ověřování důkazu stanoví politika ověřování, kapitola 10.

### 8.3 Jak důkaz ověřit (OVR-6.5-06)

Důkaz o uchování je součástí podpisu nebo dokumentu a ověřuje se podle normy formátu podpisu (TS 119 512, F.4.5) kterýmkoli validátorem. Kotvami důvěry pro podpisy i razítka v důkazu jsou evropské důvěryhodné seznamy. Kotva z vlastního úložiště certifikátů uživatele platí jen pro toho, kdo jí důvěřuje také (politika ověřování, kapitola 5).

### 8.4 Co důkaz říká o svém původu (OVR-6.5-09, 9.2-04)

Archivní razítko **nenese** údaj o aplikaci, o této politice ani o profilu uchování. Politika se do důkazu nevkládá; z kontextu ji lze určit podle značky ve jménu výsledného souboru (9.1), podle záznamu o úkonu (9.3) a podle data vzniku razítka, k němuž platí verze politiky uvedená v kapitole 12.

## 9 Výsledek úkonu a doklady o něm

### 9.1 Výsledný soubor

Jméno výsledného souboru nese značku varianty a datum, dokdy je dokument ověřitelný: `_Uchovano_do_DD_MM_RRRR`, `_Prodlouzeno_do_DD_MM_RRRR`, `_Prerazitkovano_do_DD_MM_RRRR`; forenzní balíček `_Do_DD_MM_RRRR`. Značky jsou bez diakritiky a mezer a nevrství se — při dalším úkonu se dosavadní značka nahradí. Je-li razítko změřeně nekvalifikované, přibude za datum ` (DEMO)`. Datum je očekávaná doba trvání důkazu podle 5.3.

### 9.2 Ověření výsledku

Výsledný soubor se hned po úkonu ověří na pozadí touž cestou, jako kdyby ho uživatel otevřel sám. Výsledek se porovná s předpovědí: u každého prvku stav před úkonem a po něm, zda na něj úkon měl dosáhnout, a zda datum ve jménu souboru odpovídá tomu, co dá ověření. Každý rozchod se vyjmenuje.

### 9.3 Záznam o úkonu

Záznam o úkonu uvádí:

- úkon, okamžik provedení, verzi aplikace a zdroj časových razítek s jeho kvalifikovaností;
- konec platnosti certifikátu nového razítka a kryptografické parametry razítek, která úkon přidal — podpisový algoritmus, délku klíče, hašovací funkci podpisu razítka a hašovací funkci otisku dat, s upozorněním, přestane-li algoritmus být spolehlivý dřív než certifikát. Které razítko úkon přidal, se pozná podle času vzniku a otisku orazítkovaných dat: razítko, do kterého úkon jen doplnil validační data (u kontejneru ASiC to dělá i knihovna DSS), se za nové nepočítá;
- jméno, formát a úroveň, velikost a otisk SHA-256 vstupního i výsledného souboru;
- revokační informace rozdělené na ty, které dokument nesl už předtím, ty, které úkon přidal, a ty, které si ověření výsledku opatřilo mimo dokument;
- stav prvků před úkonem a po něm a shodu s předpovědí.

Záznam lze uložit jako protokol PDF/A (předpona `ZU_`), na přání opatřený časovým razítkem.

**Záznam o úkonu není důkazem o uchování.** Důkazem je podle TS 119 512 (3.1, F.4.5) archivní razítko v podpisu, které ověří kterýkoli validátor. Záznam je informace pro uživatele: nevydává ho služba vytvářející důvěru a k výslednému souboru ho váže jen otisk. Není ani záznamem událostí služby ve smyslu OVR-7.10-02.

### 9.4 Připomínka do kalendáře

Protože AnEP lhůty nehlídá sám (3.2), nabízí uložení připomínky ve formátu iCalendar s předstihem 1, 5, 10 nebo 30 dnů — v záznamu o úkonu před koncem ověřitelnosti výsledného souboru, v nabídce úkonu před koncem lhůty, je-li to konkrétní datum. Připomínka je celodenní událost a nese jméno i plnou cestu k souboru.

## 10 Co úkon nezaručuje

- Úkon nezaručuje, že výsledek uzná jiný ověřovatel. Ověřovatelé mohou uplatňovat jiná pravidla, jinou kryptografickou sadu nebo jinou zásobu revokačních informací; aplikace jejich verzi ani nastavení nezná.
- Úkon nechrání před vyřazením služby z důvěryhodných seznamů, před budoucími kryptografickými omezeními ani před tím, že uživatel úkon před koncem lhůty nezopakuje.
- Úkon nenapraví, co je na prvku sporné dnes.
- AnEP není kvalifikovaná služba uchovávání ve smyslu čl. 34 nařízení eIDAS (TS 119 511, příloha A) a jeho výstup nemá právní účinky jejího výstupu.

## 11 Vztah k ETSI TS 119 511 a TS 119 512

Tabulka uvádí, jak AnEP naplňuje požadavky TS 119 511.

| Požadavky | Naplnění v AnEP |
|---|---|
| kap. 5; 6.1–6.3; 6.7; 7.1–7.13; 7.16; příloha A | nemají předmět — provoz, organizace, smlouvy a bezpečnost poskytovatele |
| 8.1, 8.2 (protokoly); OVR-6.4-09, 6.4-10 (profily on-line) | nemají předmět — AnEP není služba s rozhraním; profily popisuje tato politika (3.3) |
| OVR-6.4-01, 6.4-02, 6.4-04, 6.4-13, 6.4-14 | splněno popisem profilů (3.3) a veřejnými verzemi politiky |
| OVR-6.4-03 | nesplněno — profily nemají vlastní identifikátor (3.3) |
| OVR-6.4-06 | splněno — očekávaná doba trvání důkazu ve jménu souboru a v záznamu (5.3) |
| OVR-6.4-07, 6.4-08; 7.14 | nesplněno záměrně — datum nezohledňuje kryptografii a nic se nesleduje; náhradou je upozornění na slabý klíč (5.3) |
| OVR-6.5-01 až 6.5-06, 6.5-08 | splněno v kapitole 8 |
| OVR-6.5-09 | splněno — důkaz údaj o politice nenese a politika to uvádí (8.4) |
| 6.6 | politika ověřování AnEP |
| OVR-7.5-02, 7.5-03; 9.2-01, 9.2-02 | částečně — protokol RFC 3161 ano, soulad autority s EN 319 421 a 319 422 se neověřuje (4.2) |
| 7.15 | netýká se — požadavek platí jen pro modely s ukládáním |
| OVR-9.1-01 | splněno — obsah dokumentů se neukládá (4.3) |
| OVR-9.2-04, 9.2-05 | nesplněno — politika se do důkazu nevkládá (8.4) |
| OVR-9.3-01 až 9.3-03 | splněno u uchování; u přerazítkování s výhradou chybějících dat (6.4) |
| OVR-9.3-04 až 9.3-08 | netýká se — kombinace PDS+PGD ani předávání jen otisku se nenabízejí |

Z TS 119 512 AnEP odpovídá schématu F.4 se dvěma odchylkami uvedenými v 3.2. Protokol podle kapitoly 5 normy (operace PreservePO a další) předmět nemá.

## 12 Změny politiky

| Verze | Datum | Změna |
|---|---|---|
| 1.1 | 5. 10. 2026 | první vydání, spolu s verzí 1.1 politiky ověřování |
| 1.2 | 8. 10. 2026 | beze změny obsahu; číslo verze a stav aplikace sjednocené s verzí 1.2 politiky ověřování |
| 1.3 | 8. 10. 2026 | beze změny obsahu; číslo verze a stav aplikace sjednocené s verzí 1.3 politiky ověřování |
| 1.4 | 9. 10. 2026 | beze změny obsahu; číslo verze a stav aplikace sjednocené s verzí 1.4 politiky ověřování |
| 1.5 | 13. 10. 2026 | oddíl 6.3: poškozené časové razítko, které o závěru ověření nerozhoduje, uchování nebrání; oddíl 4.2: kvalifikovanost zdroje razítek zadaného před jejím měřením se doplní z ověření výsledku prvního úkonu; oddíl 9.3: razítka přidaná úkonem se poznají podle času vzniku a otisku dat; spolu s verzí 1.5 politiky ověřování |
| 1.6 | 13. 10. 2026 | beze změny obsahu; číslo verze a stav aplikace sjednocené s verzí 1.6 politiky ověřování |
| 1.7 | 17. 10. 2026 | beze změny obsahu; číslo verze a stav aplikace sjednocené s verzí 1.7 politiky ověřování |
| ==1.8== | ==17. 10. 2026== | ==oddíly 6.2 a 6.4: přerazítkování (a uchování u prvku s volnějším čtením) vkládá jen revokační údaje, které o certifikátu něco vypovídají — odpověď „nerevokován“ vydaná po expiraci bez závazku vydavatele se nevkládá, s odůvodněním ze standardů a z měření; oddíl 8.1: upozornění před úkonem u dokumentu s certifikačním podpisem bez povolených změn (DocMDP P=1); spolu s verzí 1.8 politiky ověřování== |
| ==1.9== | ==17. 10. 2026== | ==oddíl 6.5: prodloužení doplní revokační informace a certifikáty stejně jako uchování, post-expirační údaje bez závazku vydavatele nevkládá a údaj o certifikátu přidaného razítka ještě nenese; oddíl 6.4: pravidlo pro údaje u prvku s volnějším čtením platí i pro prodloužení; spolu s verzí 1.9 politiky ověřování== |
