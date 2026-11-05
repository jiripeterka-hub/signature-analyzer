# Soupis změn AnEP

Změny ve verzích aplikace AnEP (Analyzátor elektronických podpisů). Slouží jako podklad
pro soupis změn ke každé nové vydané verzi.

## Připravovaná verze

### Připojení k internetu

- **Oprava: spojení na proxy se vydávalo přes SOCKS.** Pokud systém uváděl proxy SOCKS, šlo přes ni
  i spojení na HTTP proxy, včetně proxy zadané v Nastavení, a volba „Bez proxy“ ji také neobešla.
  V takové síti končila obnova důvěryhodných seznamů a další spojení vypršením času. Zadaná HTTP
  proxy i HTTP proxy ze systému se nyní používají přímo a volba „Bez proxy“ jde vždy přímo.
  Sítě, kde systém uvádí jen SOCKS, fungují jako dosud.
- **Zadaná proxy může být i SOCKS.** U zadané proxy lze v Pokročilém nastavení zvolit protokol
  HTTP nebo SOCKS. U SOCKS překládá jména serverů tento počítač, ne proxy, a kontrola nové verze
  jde přímo, mimo proxy. Přihlášení k proxy aplikace dále nepodporuje.

### Vzhled

- **Velikost rozhraní.** V Nastavení přibyla volba „Velikost rozhraní“: podle systému (výchozí),
  nebo 100, 125, 150, 175 či 200 %. Zvětší celé rozhraní aplikace včetně písma a ovládacích
  prvků a pomůže tam, kde je na daném monitoru špatně čitelné. Volba se pamatuje, projeví se
  po novém spuštění aplikace a na výsledek ověření nemá vliv.
- **Okna se vejdou na obrazovku.** Okno, které by bylo větší než obrazovka (typicky při velkém
  měřítku rozhraní), se při otevření zmenší a posune tak, aby na ni celé vešlo.

## Verze 1.2.0 (změny oproti 1.1.1)

### Připojení k internetu

- **Proxy.** Aplikace dosud chodila na internet vždy přímo a proxy nastavenou v systému
  nepoužívala. V síti, kde musí veškerý provoz jít přes proxy, proto neprošla obnova
  důvěryhodných seznamů, dotazy na revokaci (OCSP, CRL) ani žádosti o časové razítko
  a aplikace se chovala jako bez připojení. Nově se ve výchozím stavu použije proxy podle
  nastavení systému (kde žádná není, jde se přímo jako dosud). V Pokročilém nastavení přibyl
  oddíl „Připojení k internetu (proxy)“, kde lze místo toho zadat adresu a port proxy
  s výjimkami pro servery, k nimž se má jít přímo, nebo proxy vypnout. Změna platí hned
  po uložení. Proxy, která vyžaduje přihlášení, aplikace zatím nepodporuje.
- **Okno Nastavení s posuvníkem.** Okno Nastavení jde nově zmenšit a zvětšit na výšku
  a obsah se v něm posouvá; vyšší než obrazovka se už neotevře. Pokročilé nastavení se
  otevírá vysoké 500 bodů, takže se vejde i na malý monitor. Tlačítka Uložit a Zrušit
  stojí v pevném pruhu na spodku okna a při posouvání obsahu zůstávají vidět. Oddíl „Čas
  ověření“ se v něm přesunul níž, před nastavení proxy: nahoře jsou volby, které se
  používají nejčastěji.
- **Opravený popis obnovy seznamů.** Vysvětlivka u důvěryhodných seznamů EU v Nastavení
  uváděla, že se seznamy obnovují, je-li mezipaměť starší než 24 hodin. Ve skutečnosti se
  na pozadí kontroluje každý národní seznam zvlášť, nejvýš jednou za 12 hodin.

### Protokoly a soukromí

- **Protokol DSS bez přístupu k internetu.** Záložka s protokolem ověření si dosud při
  každém zobrazení stahovala styly a skripty (Bootstrap, jQuery) z cizích serverů
  cdn.jsdelivr.net a code.jquery.com; jejich provozovatelé se tak mohli dozvědět, kdy si
  kdo protokol prohlíží, a bez připojení se protokol zobrazil bez úprav. Obě knihovny jsou
  nově součástí aplikace a protokol se vykreslí i offline. Jejich licence (MIT) jsou
  uvedeny v souboru NOTICE.txt a ve složce licenses.

## Verze 1.1.1 (změny oproti 1.1.0)

### Instalátor pro Windows

- **Hlášení, když je tatáž verze už nainstalována.** Instalátor spuštěný na počítači, kde
  je AnEP v téže verzi už nainstalován, se dosud po chvíli beze slova zavřel. Nově ohlásí,
  že tato verze už nainstalována je, a poradí ji pro novou instalaci napřed odinstalovat.
- **Spuštění AnEP po instalaci.** Závěrečná obrazovka instalátoru nabízí volbu „Spustit
  AnEP“; ve výchozím stavu není zaškrtnutá.

### Ověřování

- **Vlastní seznamy CRL i u důkazu z obalu.** Dokument předaný tlačítkem „Ověřit v AnEP“
  bez vlastního časového razítka se dosud ptal úložiště vlastních seznamů CRL k dnešku,
  a seznam z doby platnosti certifikátu proto nepoužil, ani když dřívější existenci
  dokládalo razítko obalu. Nyní se, pokud razítko obalu samo nestačí, úložiště zeptá
  k okamžiku tohoto razítka. Podpis, kterému chyběl jen revokační údaj z doby platnosti
  certifikátu, tak může vyjít platný. Analýza i Rozšířený přehled pak seznam z úložiště
  uvedou jako zdroj revokační informace a neoznačí ho za chybějící.

### Výklad výsledků

- **Co který podpis v kontejneru podepisuje.** U podpisů a pečetí v kontejneru ASiC uvádí
  Zjednodušený i Rozšířený přehled nový řádek „Podepsané soubory“ (u pečeti „Zapečetěné
  soubory“) se jmény dokumentů, které daný prvek podepisuje. Ve sloučeném kontejneru, kde
  každý podpis podepisuje jiné dokumenty nebo jejich kombinaci, je tak vidět, k čemu se
  který závěr o platnosti vztahuje. U podepsaného archivu .zip (ASiC-S) uvede i soubory
  v něm; u dokumentu PDF a u externího podpisu se řádek neukazuje.
- **Důkaz z obalu, který nestačil.** Když dokument předaný tlačítkem „Ověřit v AnEP“
  z kontejneru, datové zprávy či jiného obalu neobstál ani s časovým razítkem obalu, karta
  Analýzy to nově řekne: v poznámce k výsledku, v bloku „Průběh ověřování“ i ve znění důvodu
  „expirovaný podpisový certifikát, bez časového razítka“, které už netvrdí, že dřívější
  existenci nedokládá nic. Dosud karta o obalu mlčela a ukázala jen tento důvod. Nově se
  ukážou i důvody, které ověření s razítkem obalu skutečně zastavily — typicky chybějící
  revokační informace k podpisovému certifikátu, včetně rady vložit seznam CRL z vhodné doby.
  Kde texty o důkazu z obalu mluví, uvádějí v závorce i jméno souboru obalu (například
  „obalu (smlouva.asice)“), u vnořených obalů celou cestu od vnějšího k vnitřnímu.
  Rozšířený přehled v bloku „Rozhodný okamžik ověření“ uvede čas razítka obalu jako prokázanou
  existenci podpisu a vysvětlí, proč rozhodný okamžik podle DSS zůstal u času ověření:
  ověřování skončilo dřív, než se k razítku dostalo.

### Prohlížeč kontejnerů

- **Rozpoznání oficiálního sestavení AnEP.** Souhrn podpisu kódu u programu (`.exe`, `.msi`),
  který se týká AnEP, má nový řádek „Sestavení AnEP“. Podepsal-li program autor AnEP
  certifikátem, který aplikace zná, a podpis je platný, jde o oficiální sestavení. Podepsal-li ho
  certifikát se jménem autora, který aplikace nezná (třeba obnovený), uvede to jen jako náznak
  a ukáže otisk certifikátu k porovnání se stránkou aplikace. Soubor, který se jménem vydává
  za AnEP, ale je nepodepsaný nebo ho podepsal někdo jiný, označí jako neoficiální. U ostatních
  programů se nic nemění.

## Verze 1.1.0 (změny oproti 1.0.0)

### Ověřování

- **Důkaz existence z obalu.** Dokument předaný k ověření tlačítkem „Ověřit v AnEP“
  z prohlížeče obsahu — z kontejneru ASiC, z datové zprávy, z obalujícího podpisu nebo jako
  příloha PDF — nově využije časové razítko obalu, které jeho obsah kryje, jako důkaz, že
  dokument existoval nejpozději v čase onoho razítka. U datové zprávy je to kvalifikované
  časové razítko podání, případně razítko připojené při stažení; u přílohy PDF razítko té
  verze dokumentu, která přílohu už obsahovala. Leží-li obal v dalším obalu (třeba kontejner
  v datové zprávě), poslouží i razítko vnějšího obalu, a to i k podržení razítka vnitřního
  obalu, které by samo už neobstálo. U dokumentu PDF předloženého
  s externím podpisem, který nese i vlastní podpisy, nabídne karta Analýzy odkaz „Ověřit
  podpisy dokumentu PDF s důkazem existence z externího podpisu“; razítko externího podpisu
  se pak využije stejně. Podpis, jehož platnost
  by jinak zůstala neznámá — typicky proto, že mezitím vypršel certifikát podepisujícího nebo
  razítkovací autority —, se pak posoudí k tomuto dřívějšímu okamžiku; díky tomu může obstát
  i vlastní razítko podpisu. Využijí se jen razítka, která sama obstojí, včetně archivních
  razítek obalu. Karta Analýzy to výslovně uvede a záložka Digitální kontinuita
  upozorní, že uchovávat je třeba celý obal: vyjmutý dokument tento důkaz nemá. Dokládá-li
  revokační informace, že certifikát byl odvolán ještě před tímto okamžikem, karta na to
  upozorní stejně jako u vlastního razítka.
  Forenzní balíček vytvořený z takového dokumentu nově obsahuje i obal, z něhož byl dokument
  předán (u vnořených obalů všechny, u externího podpisu ten podpis), beze změny a se zmínkou
  v popisu balíčku.
- **Knihovna DSS 6.5.1.** Ověřovacím jádrem je nově opravné vydání DSS 6.5.1. Samotné
  ověřování podpisů a výchozí politika se proti verzi 6.5 nemění; novější jsou knihovny, na
  kterých stojí (mimo jiné Bouncy Castle 1.86 a veraPDF 1.30.3).
- **Podpisy CB-AdES.** AnEP nově ověřuje podpisy ve formátu CB-AdES (ETSI TS 119 152-1),
  založené na podpisech COSE a datovém formátu CBOR — odloučené (soubor .cose s podepsaným
  dokumentem v hlavním poli) i obalující. Dosud je odmítal jako nepodporovaný formát.
  Karta Analýzy u nich uvádí, že zatím nejde o referenční formát: CB-AdES nejmenuje
  rozhodnutí (EU) 2015/1506 ani prováděcí nařízení (EU) 2026/248, které je nahradilo.
  Záložka Digitální kontinuita nabízí u podpisu CB-AdES uchování stejně jako u ostatních
  formátů AdES: doplní chybějící revokační informace a archivní časové razítko až na úroveň
  LTA (odloučený podpis jen spolu s podepsaným dokumentem). S formátem CB-AdES ale CzechPOINT
  ani podatelny pracovat neumějí: blok o nich proto u takového podpisu neuvádí datum, nýbrž
  že platnost neověří, a výsledek uchování slibuje ověřitelnost jen ve validátorech, které
  formát CB-AdES podporují.
- **Referenční formáty podle prováděcího nařízení (EU) 2026/248.** Výklad o referenčním
  formátu na kartě Analýzy, v Rozšířeném přehledu i v dokumentaci se nově opírá o nařízení
  2026/248, které k 22. 2. 2026 zrušilo dosud citované rozhodnutí (EU) 2015/1506. JAdES je
  referenčním formátem s poznámkou, že podle nařízení formálně až od 23. 2. 2027; poznámka po
  tomto dni sama zmizí. Rozšířený přehled dřív posuzoval referenční formát jen podle označení
  „Baseline“ a u CB-AdES by tvrdil, že jde o referenční formát; nově píše „zatím nejde“.

### Výklad výsledku ověření

- **Druh podpisu či pečeti s porušenou integritou.** Když se podepsaný obsah po podpisu
  změnil, uváděla verze 1.0.0 u druhu prvku „autentizační prvek nesplňující požadavky pro
  zaručený ani kvalifikovaný podpis nebo pečeť“, a to i u podpisu s kvalifikovaným
  certifikátem a kvalifikovaným prostředkem (QSCD). Nově AnEP druh zachová a označí ho jako
  neplatný, například „neplatný kvalifikovaný elektronický podpis“, „neplatný uznávaný
  elektronický podpis“ nebo „neplatná kvalifikovaná elektronická pečeť“. Výklad „V terminologii
  práva“ nejprve uvede druh stejně jako u platného prvku a pak doplní, že se dokument po podpisu
  změnil, podpis jeho současný obsah nekryje, a proto se k podpisu na dokumentu v této podobě
  neváží účinky kvalifikovaného (uznávaného, zaručeného) podpisu či pečeti podle nařízení eIDAS
  ani zákona č. 297/2016 Sb. Verdikt „neplatný (porušení integrity)“ a důvod 7 zůstávají beze
  změny. U ostatních příčin neplatnosti (například odvolání certifikátu před podpisem) zůstává
  dosavadní znění.
- **Nepodporovaný formát externího podpisu.** Když byl v hlavním poli dokument a jako externí
  podpis soubor ve formátu, který aplikace nepodporuje, uváděla verze 1.0.0 ve stavovém řádku
  i v nabídce forenzního balíčku, že nepodporuje formát dokumentu (například PDF). Nově jmenuje
  soubor externího podpisu, který skutečně přečíst nedokázala; totéž jméno nese i popis
  ve vytvořeném forenzním balíčku.
- **Podání vůči OVM.** U platného kvalifikovaného či uznávaného elektronického podpisu karta
  Analýzy nově na začátku výkladu o tom, co podpis zaručuje, uvádí, že postačuje pro podání
  (úkon fyzické osoby) vůči OVM v elektronické podobě podle § 6 zákona č. 297/2016 Sb.,
  a — nejde-li o podpis dokumentu ve formátu PDF, ať interní, nebo externí — že většina OVM
  přijímá elektronicky podepsaná podání jen ve formátu PDF. U platné
  kvalifikované či uznávané elektronické pečeti stejně, jen jde o úkon právnické osoby podle
  § 9 téhož zákona. Paragraf je odkazem na znění zákona.
  U platného zaručeného elektronického podpisu naopak řádek „Upozornění“ nově končí větou,
  že tento podpis pro podání (úkon fyzické osoby) vůči OVM podle § 6 téhož zákona
  nepostačuje.
- **Podpisy od služby Podepsat.online.** AnEP pozná zaručený elektronický podpis vytvořený
  službou Podepsat.online. Ta podepisuje certifikátem od vlastní, nekvalifikované autority
  provozovatele („SignMaster CA“, Software602 a.s.), která není v důvěryhodných seznamech EU,
  takže ověření skončí neznámou platností. Karta Analýzy to nově u důvodu 3 vysvětlí a v radě
  pro zkušenější uživatele nabídne — pokud uživatel službě důvěřuje — vložit kořenový
  certifikát služby mezi důvěryhodné, s proklikem přímo do okna Vlastní důvěryhodné certifikáty. V tom okně přibylo
  tlačítko „Vložit kořenový certifikát služby Podepsat.online“; certifikát je součástí
  aplikace, ve výchozím stavu ale mezi důvěryhodnými není. U platného podpisu od této služby
  stojí v řádku „Upozornění“ nové znění: identita podepsané osoby je jen deklarovaná a služba
  ji ověřuje pouze zprávou na zadaný e-mail a kódem v SMS na zadané číslo (obojí karta uvádí
  podle údajů, které služba zapsala do důvodu podpisu), což nezabrání tomu, aby se uživatel
  služby vydával za někoho jiného. Vysvětlení k oběma tlačítkům pro vložení certifikátů
  (komerčních autorit i služby Podepsat.online) už nestojí pod nimi, ale otevírá se ve vlastním
  okně odkazem „Co se stane?“ vpravo od tlačítka.
- **Podpisy od služby BankID Sign.** Obdobně AnEP pozná zaručený elektronický podpis od služby
  BankID Sign, jejíž autorita („BankID Document CA“, Bankovní identita, a.s.) také není
  v důvěryhodných seznamech EU. Karta Analýzy u neznámé platnosti vysvětlí, o co jde, a v radě
  pro zkušenější uživatele nabídne vložení kořenového certifikátu služby mezi důvěryhodné;
  v okně Vlastní důvěryhodné certifikáty k tomu přibylo tlačítko „Vložit kořenový certifikát
  služby BankID Sign“ (ve výchozím stavu certifikát mezi důvěryhodnými není). U platného
  podpisu řádek „Upozornění“ uvádí, že identita podepsané osoby byla ověřena skrze její
  bankovní identitu, a jmenuje banku z certifikátu. Rozšířený přehled vypisuje v bloku
  Podepsaná osoba identifikátor osoby u dané banky jako „ID osoby“, s vysvětlením pod ikonou ⓘ.
- **Elektronické autorizační razítko.** U podpisu, jehož certifikát má v organizační jednotce
  (OU) „Elektronické autorizační razítko“ (vydávají je ČKA a ČKAIT), karta Analýzy nově
  obsahuje rozbalovací blok „Elektronické autorizační razítko“; u platného podpisu stojí před
  blokem o účincích úředně ověřeného podpisu. Blok posoudí, zda podpis splňuje požadavky
  § 13 odst. 3 písm. b) zákona č. 360/1992 Sb.: platný kvalifikovaný elektronický podpis
  s kvalifikovaným elektronickým časovým razítkem a v certifikátu jméno autorizované osoby,
  číslo, pod nímž je zapsána v seznamu autorizovaných osob (druhá OU), obor, popřípadě
  specializaci (položka title) a označení Komory (položka O). U každého z těchto údajů uvede
  rovnou jeho hodnotu z certifikátu, případně že chybí; u čísla autorizace upozorní na
  neobvyklý tvar (u ČKA mívá pět číslic, u ČKAIT sedm). IČO Komory vypíše zvlášť mezi dalšími
  údaji certifikátu.
- **Opravené datum zastarání SHA-1.** Nápověda k volbě „Ignorovat zastaralost algoritmu
  SHA1“ v Nastavení i poznámka o tomto ústupku na kartě Analýzy uváděly, že knihovna DSS
  považuje SHA-1 za zastaralý od roku 2022. Ve skutečnosti je to od 1. 8. 2012 a k tomuto
  datu se ověřování řídí i dosud; opraven byl jen text.

### Prohlížeč kontejnerů

- **Jméno dokumentu vloženého v obálce .p7m.** Obálka CMS / PKCS #7 sama jméno podepsaného
  souboru neuchovává. Nese ho jen nepovinný podepsaný atribut content-hints, který některé
  podepisující nástroje vyplňují. Verze 1.0.0 vložený dokument vždy pojmenovala podle obálky
  (z `smlouva podepsana.p7m` udělala `smlouva podepsana.pdf`). Nově, když obálka content-hints
  se jménem souboru nese, ukáže prohlížeč a nabídne k uložení původní jméno dokumentu a uvede
  ho i v záhlaví (u odděleného podpisu .p7s také). Když ho nenese, jméno se odvodí jako dosud
  a karta prvku v řádku „Původ názvu“ řekne, že je odvozené z názvu obálky.
- **Odpovědi OCSP vložené v podpisech .p7s a .p7m.** Odpověď OCSP lze v objektu CMS uložit
  dvojím způsobem: samotnou vnitřní strukturu (BasicOCSPResponse), nebo celou odpověď podle
  RFC 5940. Verze 1.0.0 v prohlížeči kontejnerů i v Inspektoru četla jen první způsob, takže
  podpisy s odpověďmi uloženými celé (tak je ukládá například knihovna DSS u CAdES B-LT
  a B-LTA) vypadaly, jako by žádné revokační údaje nenesly („Vložené CRL / OCSP: 0 / 0“).
  Nově se ukážou oba způsoby a celou odpověď lze vyjmout beze změny. Prohlížeč kontejnerů
  navíc nově ukazuje certifikáty, seznamy CRL a odpovědi OCSP z nepodepsaných atributů
  certificate-values a revocation-values (rozšířená forma CAdES-X-L), které dosud přecházel.
  Údaj uložený na více místech se ukáže jednou a karta v řádku „Uloženo v“ uvede všechna
  místa. Ověřování samo se nemění, tyto údaje četlo vždy.
- **Podpis kódu u programů (.exe, .dll, .msi).**
  Spustitelný soubor Windows nebo instalační balíček Windows Installer předložený k ověření
  dosud skončil hlášením, že formát aplikace nepodporuje. Nově se otevře v prohlížeči
  kontejnerů, který ukáže, zda program nese podpis kódu (Authenticode), a pokud ano, kdo ho
  podepsal, jaký otisk souboru podpis kryje, název programu a odkaz uvedené podepisujícím,
  časová razítka (RFC 3161 i starší protipodpis Authenticode), vložené certifikáty a případné
  vnořené podpisy. AnEP přitom ověří, zda se soubor od podpisu nezměnil (přepočte jeho otisk
  a porovná ho s podepsaným), zda podpis matematicky sedí ke klíči certifikátu podepisujícího
  a zda časové razítko kryje tento podpis a jeho podpis sedí. Na Windows ověří i certifikát
  podepisujícího a autority razítka: řetěz ke kořeni, kterému počítač ve Windows důvěřuje,
  účel certifikátu a zneplatnění, s časovým razítkem jako důkazem existence. Výsledkem je
  verdikt „platný podpis kódu“, „neplatný“ nebo „nerozhodnuto“ s důvodem. Revokační údaje
  vydané po vypršení certifikátu přijme po dobu 10 let, po kterou je autoritám ukládají
  pravidla CA/B Fóra pro podpis kódu. Na jiných systémech certifikát neověří a okno to
  řekne. Pravidla popisuje nový oddíl 12.8 politiky ověřování. Podpis i vnořené podpisy lze vyjmout
  jako soubory .p7s. Program se z prohlížeče nedá spustit: tlačítko „Otevřít v aplikaci“ je
  u něj nedostupné, aby se z rozebíraného podání omylem nespustil cizí kód.
- **Hledání v prohlížeči kontejnerů i v údajích prvků.** Pole nad stromem dosud hledalo jen
  v názvech prvků. Nově prohledává i jejich roli a všechny údaje na kartě prvku (předmět
  a vydavatel certifikátu, sériové číslo, čas razítka a další) a otisky SHA-256 a SHA-1, takže
  certifikát lze najít podle vydavatele či sériového čísla a prvek podle otisku z protokolu.
  Nezáleží na velikosti písmen ani na diakritice („pecet“ najde „Pečeť“) a otisk či sériové
  číslo lze vložit i s mezerami nebo dvojtečkami. Obsah prvků (text uvnitř souborů) se
  neprohledává.
- **Velké položky archivu ZIP se už nevydávají za celé.** Prohlížeč načítá do paměti položky
  do 128 MB. Když archiv velikost položky v hlavičce neuváděl (tak balí například Průzkumník
  Windows), verze 1.0.0 načetla jen prvních 128 MB a s touto useknutou částí dál zacházela jako
  s celou položkou: ukázala ji jako 128 MB velkou, spočítala z ní otisk SHA-256, uložila ji
  a předala k ověření. Nově prohlížeč takovou položku nenačte vůbec, uvede její skutečnou
  velikost a upozorní, že ji je třeba rozbalit z archivu jiným programem.

### O aplikaci

- **Kontrola nové verze.** Okno „O aplikaci“ (v Nastavení) má nově odkaz „Zkontrolovat, zda je
  k dispozici nová verze“. Teprve po jeho stisku se AnEP zeptá stránky aplikace na číslo
  poslední vydané verze; sama od sebe se nikam neozývá, ani při spuštění. Je-li vydaná verze
  novější, nabídne odkaz na stránku https://www.bajecnysvet.cz/anep/ziskat.php. Nic sama
  nestahuje ani neinstaluje: stažení i instalace jsou na uživateli. Instalátor nové verze
  tu dosavadní sám odinstaluje; nastavení zůstane zachováno.

### Instalační balíčky

- **Podepsaný instalátor a spouštěč.** Instalátor pro Windows (`.msi`) i spustitelný soubor
  `AnEP.exe` v instalaci a v přenosném archivu nesou elektronický podpis kódu s časovým
  razítkem. Vydavatelem je Jiří Peterka, certifikát vydala Certum Code Signing 2021 CA.
  Windows proto při instalaci ukazuje ověřeného vydavatele místo „neznámého“ a podpis lze
  ověřit ve vlastnostech souboru (karta Digitální podpisy) nebo v AnEP samotném.
- **Linuxová verze: položka v nabídce aplikací.** Přenosný archiv pro Linux nově obsahuje
  skript `pridat-do-nabidky.sh`. Po rozbalení stačí spustit `./AnEP/pridat-do-nabidky.sh`
  a AnEP se objeví v nabídce aplikací přihlášeného uživatele. Skript nepotřebuje sudo, nic
  nemaže ani neinstaluje a funguje na libovolné distribuci. Jen zapíše spouštěč pro místo, kde
  AnEP právě leží. Po přesunu adresáře AnEP je třeba skript spustit znovu. Odebrání položky
  skript sám vypíše (`rm ~/.local/share/applications/anep.desktop`).
