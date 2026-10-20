# Blok „Způsob ověření“ — návrh textů a archiv zrušených částí

> ## ⚠ Stav k 19. 8. 2026
>
> **V aplikaci je jen část první** — „jak ověřovala aplikace AnEP“. Části druhá (validátory podle
> standardů) a třetí (CzechPOINT, podatelny OVM, Signer) byly **zrušeny**; důvod a bilance jsou
> v oddílu 15 na konci. Oddíly 3, 4 a 8–14 tedy popisují kód, který v aplikaci **už není** —
> zůstávají jako podklad pro případ, že se k předpovědi vrátíme.
>
> Živé jsou: oddíl 1 (umístění a stavba), oddíl 2 (část první), oddíl 9 (překážky) a oddíly
> 10 a 13 v částech, které se týkají názvosloví.

Složený blok v kartě Rozboru. Odpovídá na otázku, kterou karta jinak neklade nahlas:
**za jakých pravidel ten verdikt vznikl?**

---

## 1. Umístění a stavba

Blok patří **za „Průběh ověřování“** ve všech třech kartách (platná / neplatná / neznámá).
„Průběh“ říká, co se dělo; „Způsob“ říká, podle jakých pravidel se to posuzovalo a co to
znamená pro jiné nástroje.

Blok má **tři části** oddělené nadpisy:

1. **Jak ověřovala aplikace AnEP** — závěr o režimu a výčet konkrétních odchylek
2. **Validátory, které se striktně drží standardů**
3. **CzechPOINT, podatelny OVM, program Signer, …**

Části odděluje vodorovná čára **užší a světlejší** než ta mezi celými bloky karty: tři části jsou
jedno sdělení ve třech krocích, ne tři samostatná témata. Změřeno na vykreslené kartě — předěl
částí začíná 64 px od okraje při poloviční sytosti, předěl bloků 24 px v plné.

Blok se ukazuje **vždy**, i když je vše v pořádku — kladná věta „proběhlo zcela podle
standardů a dopadne stejně všude“ je ta cennější polovina sdělení.

### Otevřená otázka k rozhodnutí

„Průběh ověřování“ má dnes na konci odrážky **„Podmínky ověřování“** (kotva důvěry, vlastní
CRL úložiště, ústupky). To je věcně totéž, co má říkat část 1 nového bloku. Buď se odtud
odrážky odstraní, nebo v „Průběhu“ zůstanou jako holý výčet a nový blok je vyloží. Doporučuji
**odstranit** — dvakrát totéž dvěma způsoby čtenáře jen zdrží.

---

## 2. Část první — Jak ověřovala aplikace AnEP

### 1A — beze všech odchylek

> Ověřování proběhlo zcela podle technických standardů (ETSI EN 319 102-1 a navazujících),
> bez jakýchkoli ústupků a bez zvláštních nastavení. Kotva důvěry pochází z důvěryhodných
> seznamů EU (EUTL), stav revokace byl zjištěn z informací, které standardy připouštějí,
> a ověřovalo se k aktuálnímu času.
>
> Výsledek tedy nezávisí na tom, čím byl získán.

### 1B — s odchylkami (uvozovací věta)

> Ověřování neproběhlo zcela podle technických standardů. Uvedeného výsledku bylo dosaženo
> za těchto konkrétních podmínek:

Za ní následují jen ty odrážky, které pro **tento** prvek skutečně platí.

### Odrážky — jednotlivé podmínky

**(a) Revokační informace vydané po expiraci certifikátu** *(rozhodující ústupek NEWER_OCSP)*

> **Byly využity revokační informace vydané po skončení řádné doby platnosti certifikátu.**
> Standardy takový údaj nepřipouštějí: o certifikátu, jehož platnost už skončila, se podle
> nich nedá zpětně nic dokládat seznamem či odpovědí, které vznikly až potom. Bez tohoto
> ústupku by stav revokace zůstal nezjištěný a výsledek by nebyl kladný.

**(b) Revokační informace od expirovaného vydavatele** *(EXPIRED_REVOCATION_SOURCES)*

> **Byly přijaty revokační informace od vydavatele, jemuž mezitím vypršel vlastní certifikát.**
> Standardy žádají, aby šlo vydavatele revokačního údaje ověřit; u expirovaného certifikátu
> to už nejde. Zde se vyšlo z toho, že v době vydání údaje certifikát vydavatele ještě platil.
> Bez tohoto ústupku by stav revokace zůstal nezjištěný a výsledek by nebyl kladný.

**(c) SHA-1** *(SHA1)*

> **Nebylo přihlédnuto k zastaralosti algoritmu SHA-1.** Knihovna DSS považuje SHA-1 od roku
> 2022 za nedostatečný a podpisy či certifikáty, které jej používají, odmítá. Zde bylo od
> tohoto omezení odhlédnuto. Bez tohoto ústupku by výsledek nebyl kladný.

**(d) PDF-NOT-ETSI** *(PDF_NOT_ETSI)*

> **Byl ověřen podpis ve formátu PDF-NOT-ETSI.** Jde o podpis podle starší specifikace Adobe,
> který nesplňuje požadavky ETSI na formát PAdES — chybí mu povinný atribut signing-certificate.
> Standardně jej DSS odmítá jako formátově nevyhovující; zde byl ověřen kryptograficky
> a obsahově a formátový nedostatek byl pominut.

**(e) Přísnější posuzování časových razítek** *(vypnuté „Ověřování časových razítek podle standardů“)*

> **Časová razítka byla posuzována přísněji, než žádají standardy.** Razítko bylo uznáno jen
> tehdy, pokud certifikát razítkovací autority dosud platí podle svého data notAfter. Standardy
> jsou v tomto shovívavější: razítko platí, dokud je příslušná služba vedena na důvěryhodných
> seznamech jako kvalifikovaná — až do tzv. sunsetu. V tomto nastavení se AnEP chová jako
> CzechPOINT a podatelny OVM, a může dojít k horšímu výsledku než validátor držící se standardů.

**(f) Vlastní kotva důvěry** *(isTrustedViaCustomStore)*

> **Důvěra se opírá o vlastní úložiště certifikátů, ne o důvěryhodné seznamy EU.** Řetězec
> certifikátů končí u certifikátu „{jméno kotvy}“, který byl do aplikace vložen ručně. To je
> rozhodnutí toho, kdo ověřuje — ne skutečnost daná dokumentem. Kdo je nesdílí, k témuž
> výsledku nedojde.

**(g) Vlastní úložiště CRL bylo rozhodující**

> **Stav revokace byl zjištěn ze seznamu CRL ve vlastním úložišti.** Online zdroj tento údaj
> už neposkytuje, případně vrátil údaj, který standardy nepřipouštějí. K témuž výsledku dojde
> jen ten, kdo má týž seznam k dispozici.

**(h) Zadaný čas ověření** *(usedCustomValidationTime)*

> **Ověřovalo se k zadanému okamžiku {DD. MM. RRRR HH:MM}, ne k dnešku.** Slouží to k posouzení,
> jak podpis vypadal v minulosti. Jiný nástroj ověřuje k času, kdy je spuštěn, a k jinému
> okamžiku může dojít k jinému závěru — zejména pokud byl certifikát mezitím odvolán nebo mu
> skončila platnost.

**(i) Zapnuté, ale nerozhodující ústupky** — jedna věta na konci výčtu:

> Zapnuty byly i další ústupky ({výčet}), na výsledku tohoto podpisu se ale neprojevily —
> bez nich by dopadl stejně.

---

## 3. Část druhá — Validátory, které se striktně drží standardů

Nadpis: **Validátory, které se striktně drží standardů**
Podtitulek: *(služba DSS Evropské komise a nástroje, které z knihovny DSS vycházejí)*

### 2A — dojdou k témuž

> Dojdou k témuž výsledku. Vše, oč se závěr opírá, je buď v samotném dokumentu, nebo veřejně
> dostupné: kotva důvěry z důvěryhodných seznamů EU a revokační informace v podobě, kterou
> standardy uznávají.

**+ dovětek, pokud revokační údaje nejsou vložené v dokumentu (čerpaly se ze sítě):**

> Platí to ale jen po dobu, po kterou budou tytéž revokační informace dostupné i jim. Ty, o něž
> se závěr opírá, nejsou v dokumentu vloženy a byly získány ze sítě; jakmile je vydavatel
> přestane zveřejňovat, přestane být výsledek dosažitelný{, což bude nejpozději DD. MM. RRRR}.

### 2B — nedojdou, protože závěr stojí na ústupcích

> K témuž výsledku nedojdou. Ústupky vyjmenované výše standardy nepřipouštějí, takže validátor,
> který se jich drží, nebude mít o stavu revokace čím rozhodnout. Výsledek u něj zůstane
> **neznámý — nikoli neplatný**. Není to rozpor: říká jen, že co je zde doloženo shovívavějším
> čtením, jemu doloženo není.

### 2C — nedojdou, protože kotva je vlastní

> K témuž výsledku nedojdou. Kotvu důvěry z vlastního úložiště nemá žádný jiný validátor;
> pro něj řetězec certifikátů nekončí u ničeho, čemu by měl důvěřovat, a stav platnosti
> u něj zůstane neznámý.

### 2D — dojdou k lepšímu než AnEP (jen při zapnutém přísném režimu razítek)

> Mohou dojít k příznivějšímu výsledku než AnEP. Časová razítka posuzují podle důvěryhodných
> seznamů, nikoli podle řádné doby platnosti certifikátu razítkovací autority — a právě v tom
> je zdejší nastavení přísnější, než standardy žádají.

---

## 4. Část třetí — CzechPOINT, podatelny OVM, program Signer, …

Nadpis: **CzechPOINT, podatelny OVM, program Signer, …** — výčet končí výpustkou schválně,
není uzavřený.

### Společný úvod (vždy)

> Tyto nástroje se od standardů odchylují ve dvou věcech, a to soustavně:
>
> • **Časová razítka** posuzují podle řádné doby platnosti certifikátu razítkovací autority.
>   Jakmile ten certifikát expiruje, razítko pro ně přestává být důkazem existence v čase —
>   i když je příslušná služba na důvěryhodných seznamech stále vedena jako kvalifikovaná.
>
> • **Revokační informace** naopak berou shovívavěji, než standardy dovolují: ptají se, zda byl
>   certifikát platný a zda o něm nějaký seznam či odpověď tvrdí odvolání. Vydání až po
>   skončení platnosti certifikátu jim nevadí a chybějící údaje mohou doplnit z vlastních zásob.
>
> V praxi jsou tedy v jedné věci přísnější a v druhé volnější než AnEP v základním nastavení.

### 3A — mají důkaz existence (platí situace 32)

*Přepsáno 19. 8. 2026 — viz oddíl 12.*

> U tohoto podpisu **mohou dojít ke stejnému výsledku jako AnEP**{, a to i tehdy, opírá-li se
> zdejší závěr o revokační ústupky — ty jsou pro ně běžnou praxí}: certifikát autority posledního
> platného časového razítka dosud platí (do {DD. MM. RRRR}).

### 3B — chybí jim důkaz existence, certifikát razítka skončil (chybí situace 32)

*Přepsáno 19. 8. 2026 — viz oddíl 11.*

> U tohoto podpisu **se jim ověření nepodaří dokončit**: certifikát posledního časového razítka
> již expiroval (jeho platnost skončila {DD. MM. RRRR}), a tak již nemůže sloužit jako důkaz
> o existenci podpisu v (dřívějším) čase. Proto musí ověřovat platnost podpisu k aktuálnímu času
> — a jelikož k tomu podpisový certifikát již také expiroval, platnost podpisu zůstává neznámá.

### 3C — prvek nemá žádné časové razítko a certifikát dosud platí

> Tento podpis není opatřen časovým razítkem, rozhodným okamžikem je tedy současnost. Podpisový
> certifikát dosud platí, takže tyto nástroje dojdou k témuž výsledku jako AnEP. Přestane to
> platit, jakmile certifikát skončí ({DD. MM. RRRR}) — a to i pro validátory držící se standardů.

### 3D — vlastní kotva

> Kotvu důvěry z vlastního úložiště nemají ani tyto nástroje. Stav platnosti u nich zůstane
> neznámý.

### 3E — rozhodující ústupek SHA-1 nebo PDF-NOT-ETSI  ⚠ K OVĚŘENÍ

> Jak s tímto podpisem naloží, nelze předem říci — u zastaralého algoritmu i u nereferenčního
> formátu záleží na konkrétní verzi a nastavení každého z těchto nástrojů. AnEP to za ně
> předvídat nedokáže.

**Poznámka:** u odrážek (c) a (d) nemám doloženo, jak se CzechPOINT a Signer skutečně chovají.
Navržený text proto nic netvrdí. Pokud to víš jistě, nahradíme ho konkrétním zněním.

---

## 5. Uzavírací věta (nepovinná)

Když se predikce liší od zdejšího výsledku, na konci bloku:

> Co se s tím dá dělat, je popsáno v záložce **Digitální kontinuita**.

---

## 6. Co k tomu potřebuje kód

| Podmínka | Zdroj dat | Stav |
|---|---|---|
| rozhodující ústupky (a–d) | `sig.getDecisiveSettings()`, chybějící situace 34 | je |
| zapnuté nerozhodující ústupky (i) | `Relaxation.enabledIn(settings)` mínus předchozí | je |
| přísný režim razítek (e) | `timestampStandardsMode` v `AdvisorView` | je |
| vlastní kotva (f) | `isTrustedViaCustomStore()`, `getCustomTrustAnchorName()` | je |
| zadaný čas (h) | `isUsedCustomValidationTime()`, `customValidationTime` | je |
| konec platnosti cert. TSU (3A/3B) | `lastTimestampCertExpiry(sig)`, situace 32 | je |
| konec platnosti podpisového cert. (3C) | `sig.getCertNotAfter()` | je |
| dokdy bude výsledek dosažitelný (2A) | `sig.getVerifiableUntil()` | je |
| **vlastní CRL byla rozhodující (g)** | dnes jen řetězcem „z vlastního úložiště“ v `revocationUsed()` | **doplnit příznak** |
| **revokace vložená vs. ze sítě (2A)** | dnes jen řetězcem „z dokumentu“ / „získané online“ | **doplnit příznak** |

Poslední dvě položky by šly poznat porovnáváním textu odrážek, což je křehké. Lepší je přidat
do `ValidationCourse` dvě booleovská pole a naplnit je v `ReportMapper.buildValidationCourse()`,
kde už se obojí ví.

Texty se skloňují přes `gendered(sig, …)` — „podpis / pečeť / časové razítko“.

---

# 7. Záznam o zabudování (19. 8. 2026)

Blok je v `AdvisorView.addVerificationModeBlock()`, volá se ve všech třech kartách hned za
`addValidationCourseBlock()`. Texty výše platí, ale sázení si vynutilo změny, které stojí za
schválení.

## Co se muselo přidat: verdikt

Schválené texty počítaly s **kladným** výsledkem — „dojdou k témuž“ dává smysl jen tam, kde je
k čemu dojít. Na kartě s neznámým výsledkem blok sliboval, že jinde dopadne stejně dobře,
ačkoli tady nedopadl nijak. Části 2 a 3 proto rozlišují tři verdikty. **Tyto věty jsou nové
a nejsou z původního návrhu:**

| Kde | Neplatný | Neznámý |
|---|---|---|
| Část 2 | „**Dojdou k témuž výsledku.** To, oč se opírá, je v dokumentu samotném a na nastavení toho, kdo ověřuje, nezávisí.“ | „**Ověřování nedokončí ani ony.** Posuzují podle týchž pravidel jako AnEP v tomto nastavení, takže jim bude chybět totéž.“ |
| Část 3 | „**dojdou k témuž závěru jako AnEP**.“ | „**mohou dojít k příznivějšímu závěru než AnEP**: revokační informace, které zde standardy nedovolily použít, jim zpravidla stačí, případně si chybějící údaje doplní z vlastních zásob.“ |

Poslední řádek je věcně důležitý: u neznámého výsledku je okruh CzechPOINTu **shovívavější**,
ne přísnější, a schválený text tenhle směr neměl.

## Šestá varianta části 3: razítko, které samo neobstálo

Nahlášeno na `cert_podpis_kval_razitko_bez_revinf_beze_zmen.pdf` (I.CA TSS/TSU 1, 12/2009, služba
má od 1. 7. 2016 status „withdrawn“). Varianta 3B tam napsala „**k témuž výsledku nedojdou** …
zde razítko platí dál, protože službu drží důvěryhodné seznamy“. **Obojí bylo nepravda:** službě
je kvalifikovaný status odebraný, takže razítko neuznává ani AnEP (situace 27 a 43), a certifikát
razítkovací autority navíc skončil 21. 12. 2014 — nespolehne se na ně tedy ani jedno ze čtení
a všichni dojdou ke stejnému výsledku.

Kořen chyby: celá větev 3A/3B mlčky předpokládala, že AnEP razítko **uznává**, a rozhodovala se
jen podle situace 32. Nově se ptá nejdřív, jestli nějaké razítko vůbec obstálo
(`TimestampEntry.passed()`), a když ne, rozhoduje **proč**:

- **porušená integrita (situace 36)** → razítko neprokazuje nic nikomu:
  „…má porušenou integritu, takže důkaz existence v čase neposkytuje nikomu. **Dojdou k témuž
  výsledku jako AnEP** — tady nerozhoduje přísnost výkladu, nýbrž samotné razítko.“
- **certifikát razítkovací autority už skončil** → odmítne ho i posouzení podle platnosti
  certifikátu, byť z jiného důvodu:
  „Časové razítko vydala služba, které byl v důvěryhodných seznamech odebrán kvalifikovaný status,
  a certifikát její razítkovací autority navíc skončil {datum}. Nespolehne se na ně tedy ani výklad
  podle standardů, ani posouzení podle platnosti certifikátu — tyto nástroje proto **dojdou
  k témuž výsledku jako AnEP**.“ (Bez situace 43 táž věta bez první poloviny.)
- **cokoli jiného** → netvrdí se nic: „Zda k němu dojdou tyto nástroje, se předem říci nedá:
  záleží na důvodu, proč razítko neobstálo, a ten je uveden výše.“ Zvláštní případ je odebraný
  status u **dosud platného** certifikátu autority: tam razítko odmítá AnEP a tyto nástroje ne,
  protože posuzují podle certifikátu — text to říká.

Data pro varianty 3A a 3B nově měří `lastValidTimestampCertExpiry()`, tedy jen razítka, která
obstála. Věty mluví o „posledním **platném** časovém razítku“, takže musí měřit tímtéž; dosavadní
`lastTimestampCertExpiry()` bral nejnovější bez ohledu na výsledek.

## Pátá varianta části 3: přísný režim razítek

Varianta 3B tvrdí „zde razítko platí dál, protože službu drží důvěryhodné seznamy“. Při vypnutém
„Ověřování časových razítek podle standardů“ neplatí — v tom režimu razítko odmítá i AnEP. Přibyla
proto věta: „**dojdou k témuž závěru**: certifikát autority posledního platného časového razítka
skončil {datum}, a v tomto nastavení jej za důkaz existence v čase neuznává ani AnEP.“

## Drobnosti, které text upřesnily

- **Odrážka (g)** a dovětek části 2A o síťových revokacích se ukazují **jen u kladného verdiktu**.
  Bez něj se o nic neopřelo nic: seznam z vlastního úložiště sice byl mezi použitelnými údaji,
  ale verdikt nenesl.
- **Datum v dovětku 2A** není `getVerifiableUntil()` (to měří konec řetězu razítek a u podpisu
  drženého sunsetem leží v **minulosti** — věta by slibovala lhůtu, která uplynula), nýbrž
  `getAugmentationDeadline()`: nejbližší notAfter mezi certifikáty bez vložené revokace. A ukáže
  se jen tehdy, když teprve přijde.
- **Příznaky původu revokace jsou per certifikát**, ne per záznam. Certifikát často má vloženou
  OCSP i CRL staženou teď; ta druhá nic nemění, protože čtenáři se samotným dokumentem stačí
  ta první. Jen certifikát, jehož jediná použitelná revokace přišla zvenčí, dělá verdikt závislým
  na vnějšku.
- **Věta o nerozhodujících ústupcích** zní u nekladného verdiktu jinak: „Zda se na výsledku
  podílely, se měří jen u kladného ověření — k tomu zde nedošlo.“ Původní „bez nich by dopadl
  stejně“ je měřený údaj a měří se jen u kladného výsledku.
- **Výčet toho, co bylo v pořádku** (kotva z EUTL, uznatelná revokační data) se u nekladného
  verdiktu vynechává — právě tam, kde ověření skončilo na nesestaveném řetězci, by věta o kotvě
  tvrdila kotvu, která nebyla.
- „U podpisu“ → „**U tohoto podpisu / U této pečeti / U tohoto časového razítka**“.

## „Podmínky ověřování“ zmizely z Průběhu ověřování

Podle doporučení. Zůstala tam jediná odrážka — o bajtech před hlavičkou PDF. Není to nastavení,
které si někdo zvolil, ale nález v souboru, a patří k popisu průběhu. Parametry `viaCustomStore`
a `decisiveSettings` tím z `buildValidationCourse()` vypadly.

## Co zbývá rozhodnout

1. ~~**Jméno jednoho nastavení se rozchází.**~~ Vyřešeno: `Relaxation.EXPIRED_REVOCATION_SOURCES`
   se jmenuje „Přijmout i revokační **informace** od expirovaného vydavatele“, stejně jako
   zaškrtávátko v nastavení. Týmž řetězcem je klíčovaná i mapa `LENIENT_SETTING_DESCRIPTIONS`
   v `ExtendedView` — musela se přejmenovat s ním, jinak by vyhledání popisu tiše přestalo
   fungovat a Rozšířený přehled by u té odchylky psal náhradní větu.
2. ~~**Varianta 3E** stále nic netvrdí.~~ Otázka nebyla otevřená tak, jak jsem ji položil —
   viz oddíl 8.

## Čím se to ověřovalo

`tools/AdvisorCourseSmoke` umí nově `-t` (tolerantní režim), `-s` (přísná razítka) a `-c` (zadaný
čas) a sbírá i texty z `TextFlow` — bez toho by blok neviděl, je celý z běhů. Přejezd 35 souborů
z `D:\tmp` (36 prvků) prošel všemi větvemi: 18× kladný a shodný, 13× neznámý, 3× vlastní CRL,
2× vlastní kotva; u okruhu CzechPOINTu 17× rozchod, 8× shoda, 1× příznivější závěr.


---

# 8. Varianta 3E: ptát se jen tam, kde na odpovědi záleží (19. 8. 2026)

Nahlášeno na témže dokumentu (`cert_podpis_kval_razitko_bez_revinf_beze_zmen.pdf`): je to zároveň
**PDF-NOT-ETSI a SHA-1** (v časovém razítku) — tedy přesně kombinace, u které jsem tvrdil, že
chování CzechPOINTu neznám — **a přitom o něm blok říká natvrdo, jak dopadne**.

Rozpor byl jen zdánlivý a ukázal, že otázka byla položená špatně. **O výsledku u těchto nástrojů
rozhoduje důkaz existence, ne formát ani hashovací funkce.** Bez použitelného časového razítka
nemá kdo posoudit expirovaný podpisový certifikát, ať si o PDF-NOT-ETSI a SHA-1 myslí kdokoli co
chce. Formát a algoritmus přijdou ke slovu teprve tam, kde rozhodný okamžik stojí.

Dovětek 3E se proto ukazuje **jen ve dvou větvích**, kde prvek u těchto nástrojů obstojí a otázka
zbývá jako poslední:

- platí situace 32 (certifikát autority posledního platného razítka dosud platí),
- prvek nemá razítko a podpisový certifikát dosud platí.

Ve všech ostatních větvích — vyřazená služba, expirovaný certifikát autority, porušená integrita
razítka, vlastní kotva, přísný režim — je hotovo a dovětek by podrýval závěr, který právě padl.

Znění je zároveň užší: pojmenuje jen to, co skutečně platí, místo obojího najednou.

> Zbývá jedno, co AnEP za ně předvídat nedokáže: jak naloží {se zastaralým algoritmem SHA-1 |
> s nereferenčním formátem PDF-NOT-ETSI | se zastaralým algoritmem SHA-1 a s nereferenčním
> formátem}. Záleží na konkrétní verzi a nastavení každého z těchto nástrojů.

**Ověřeno v potlačujícím směru**, na nahlášeném dokumentu (výchozí i tolerantní režim) a na sedmi
dalších nereferenčních a SHA-1 příkladech z `D:	mp`: u všech rozhodne dřív důkaz existence
a dovětek se neukáže. Případ, kdy naopak zabrat **má** — kladný verdikt se stále platným
certifikátem razítkovací autority a k tomu rozhodující SHA-1 či nereferenční formát — se
v korpusu nenašel; ta větev je zatím ověřená jen čtením kódu.


---

# 9. Důvod neúspěchu patří do první části (19. 8. 2026)

Nahlášeno na `PAdES B-B.pdf` (bez razítka, podpisový certifikát skončil 14. 6. 2025): důvod, proč
ověření nedopadlo, stál až v části o CzechPOINTu — a četl se tam jako **vlastnost těch nástrojů**,
ne jako fakt o dokumentu, který platí pro každého. Část 2 přitom už předtím psala „bude jim chybět
**totéž**“, aniž bylo řečeno co.

Nově se překážka vysloví **jednou, v první části**, a další dvě se na ni odkazují. Blok ji umí
pojmenovat ve čtyřech třídách; pátá možnost je mlčet.

| Překážka | Text v první části |
|---|---|
| bez razítka, certifikát skončil | „{Podpis není opatřen} časovým razítkem a podpisový certifikát již neplatí (skončil {datum}). **Bez důkazu existence v čase není rozhodný okamžik, ke kterému by se platnost podpisového certifikátu posuzovala.**“ |
| razítko s porušenou integritou (situace 36) | „Časové razítko má porušenou integritu, takže neprokazuje nic. **Není tak rozhodný okamžik…**“ |
| razítko od vyřazené služby (situace 43) | „Časové razítko vydala služba, které byl v důvěryhodných seznamech odebrán kvalifikovaný status, takže jako důkaz existence v čase neslouží. **Není tak rozhodný okamžik…**“ |
| nereferenční formát (situace 17 / 18) | „{Podpis je} ve formátu PDF-NOT-ETSI, který nesplňuje požadavky ETSI na formát, a ověřování podle standardů jej proto odmítá ověřit. **O platnosti samotného podpisu to nevypovídá nic — jen o tom, že se neposuzovala.**“ |
| stav revokace (situace 5–9) | „Nepodařilo se zjistit, zda podpisový certifikát nebyl k rozhodnému okamžiku odvolán: {revokační informace se nepodařilo získat / sice k dispozici jsou, ale standardy je v této podobě nepřipouštějí / část chybí a zbytek standardy nepřipouštějí}. Které to jsou, stojí v bloku Průběh ověřování.“ |

Každá věta začíná „Ověření se přesto nepodařilo dokončit.“ — navazuje na předchozí větu o tom, že
se ověřovalo podle standardů.

## Odkazy místo opakování

- **Část 2** říká „takže jim bude chybět **totéž**“ jen tehdy, když překážka pojmenovaná je;
  jinak „narazí na **tutéž překážku**“, což antecedent nepotřebuje.
- **Část 3** už důvod neopakuje, zbyl jí jen dopad na tyto nástroje:
  - „**Chybí jim totéž**: bez důkazu existence v čase nemá rozhodný okamžik odkud vzít žádný
    z nich, takže **dojdou k témuž výsledku jako AnEP**.“
  - „Certifikát razítkovací autority navíc skončil {datum}, takže razítko odmítne i posouzení
    podle platnosti certifikátu — **dojdou k témuž výsledku jako AnEP**.“
  - „Porušenou integritu razítka nespraví žádné čtení, takže **dojdou k témuž výsledku jako AnEP**.“

## Past, na kterou se přitom přišlo

Revokační překážku **nejde poznat podle prázdného seznamu použitelných revokací**. Ten bývá
neprázdný i tam, kde revokace vadí, protože nese i údaje k řetězcům certifikátů časových razítek —
vadit přitom může jen ta k podpisovému certifikátu. Rozhodují proto situace 5–9. Potkáno na
`PAdES uznavany 2019 s razitkem v dokumentu.pdf`, kde první pokus mlčel.

**Ověřeno** na 35 souborech z `D:	mp`: žádný prvek už nezůstane s nepojmenovaným důvodem
a žádná výjimka nepadla.


---

# 10. Sjednocení: „stav revokace“ místo „revokační stav“ (19. 8. 2026)

Na přání uživatele. Ve všech pádech — „stav revokace byl zjištěn“, „by stav revokace zůstal
nezjištěný“, „nebude mít **o stavu revokace** čím rozhodnout“.

Neomezeno na tento blok: táž vazba stála i v `ResultView` (situace 41), `ReportMapper` (řádky
o kotvě důvěry v Průběhu ověřování), `DetailedReportView` a `ExtendedView`. Nechat ji tam by
znamenalo, že aplikace pojmenovává tutéž věc dvěma způsoby podle toho, na kterou záložku se
čtenář dívá. Celkem sedm míst v pěti souborech.


---

# 11. Přepis textu 3B (19. 8. 2026)

Dosavadní znění tvrdilo rozchod: „**k témuž výsledku nedojdou** … zde razítko platí dál, protože
službu drží důvěryhodné seznamy“. Nové znění mluví **jen o těch nástrojích** — co se u nich stalo
a proč — a tím platí pro oba verdikty naráz, aniž musí shodu či rozchod pojmenovat:

> U tohoto podpisu **se jim ověření nepodaří dokončit**: certifikát posledního časového razítka
> již expiroval (jeho platnost skončila {DD. MM. RRRR}), a tak již nemůže sloužit jako důkaz
> o existenci podpisu v (dřívějším) čase. Proto musí ověřovat platnost podpisu k aktuálnímu času
> — a jelikož k tomu podpisový certifikát již také expiroval, platnost podpisu zůstává neznámá.

Změřeno: větev chytá **7 prvků s neznámým výsledkem a 4 platné**. U platných je to rozchod (AnEP
uzná díky sunsetu, ony ne — právě kvůli té dvojici existuje v Digitální kontinuitě úkon
Prodloužení), u neznámých shoda dosažená jinudy. Staré znění mluvilo o rozchodu v obou případech
a u těch sedmi tedy tvrdilo rozdíl mezi dvěma stejnými výsledky.

## Podmínka, kterou věta tvrdí

Poslední půlvěta („podpisový certifikát již **také** expiroval“) je fakt, ne úvaha, a musí platit.
Kde podpisový certifikát ještě platí, ověření k aktuálnímu času jim projít může, a text je jiný:

> U tohoto podpisu **jim důkaz o existenci v čase chybí**: certifikát posledního časového razítka
> již expiroval (jeho platnost skončila {DD. MM. RRRR}). Posuzují proto platnost podpisu
> k aktuálnímu času; podpisový certifikát k němu dosud platí (do {DD. MM. RRRR}), takže k výsledku
> dojít mohou — jen se bude opírat o jiný okamžik než ten zdejší.

V korpusu `D:	mp` tato varianta nenastala; je to pojistka, aby věta netvrdila expiraci, která
nenastala. **Její znění je moje, ne schválené.**

## Drobnost k rozhodnutí

Pomlčka: zadání mělo „- a jelikož“, vysazeno je „— a jelikož“ (em dash), jako ve zbytku bloku.


## Čas budoucí, ne minulý

„se jim ověření **nepodaří** dokončit“ — je to předpověď o cizím nástroji, ne zpráva o tom, co už
proběhlo. Vedle věty v první části, která je v čase minulém právem („Ověření se přesto **nepodařilo**
dokončit“, o běhu, který skutečně proběhl), ten rozdíl nese význam: co udělala AnEP, a co teprve
udělá někdo jiný.


---

# 12. Přepis textu 3A (19. 8. 2026)

Dvě změny naráz. **Závěr napřed, důvod za dvojtečkou** — dosud stálo pořadí obrácené a věta začínala
tím, co čtenáře nezajímá („jim nic nebrání“). A **„mohou dojít“ místo „dojdou“**: že mají důkaz
existence v čase, ještě neznamená, že se shodnou i na všem ostatním.

> U tohoto podpisu **mohou dojít ke stejnému výsledku jako AnEP**{, a to i tehdy, opírá-li se
> zdejší závěr o revokační ústupky — ty jsou pro ně běžnou praxí}: certifikát autority posledního
> platného časového razítka dosud platí (do {DD. MM. RRRR}).

Platí **jen pro kladný verdikt**. Táž větev obsluhuje i zbylé dva a u nich zůstává původní stavba
(„jim nic nebrání: {certifikát} …, takže …“), protože jejich závěry jsou jiné: u záporného
„dojdou k témuž závěru jako AnEP“, u neznámého „mohou dojít k příznivějšímu závěru než AnEP“.

## Vyřešeno pravidlem pro celou třetí část — viz oddíl 13


---

# 13. Pravidlo: o CzechPOINTu a spol. mluvit v možnosti (19. 8. 2026)

Zadání uživatele: **„obecně je vhodnější používat všude v souvislosti s CzechPOINTy, podatelnami
atd. méně striktní tvrzení („mohou dojít“) než rezolutní „dojdou“.“**

Je to správně i věcně. AnEP o těch nástrojích ví dvě soustavné odchylky (razítka podle platnosti
certifikátu, revokace shovívavěji) — ale ne jejich verzi, nastavení ani obsah jejich vlastních
zásob revokačních informací. Předpověď je tedy vždy podmíněná, i když z těch dvou pravidel plyne
docela pevně.

Sjednoceno na **sedmi místech** třetí části:

| Situace | Nově |
|---|---|
| kladný verdikt, platí situace 32 | „**mohou dojít ke stejnému výsledku jako AnEP**“ |
| záporný verdikt | „**mohou dojít k témuž závěru jako AnEP**“ |
| přísný režim razítek | „**mohou dojít k témuž závěru**“ |
| bez razítka, certifikát skončil | „takže **mohou dojít k témuž výsledku jako AnEP**“ |
| bez razítka, certifikát dosud platí | „takže **mohou dojít k témuž výsledku jako AnEP**“ |
| razítko s porušenou integritou | „takže **mohou dojít k témuž výsledku jako AnEP**“ |
| razítko neuznané, certifikát autority skončil | „— **mohou dojít k témuž výsledku jako AnEP**“ |

Osmé místo neobsahovalo „dojdou“, ale tvrdilo stejně rezolutně:

> ~~Kotvu důvěry z vlastního úložiště nemají ani tyto nástroje. Stav platnosti u nich zůstane
> neznámý.~~
>
> Kotvu důvěry z vlastního úložiště nemají ani tyto nástroje, takže stav platnosti u nich
> **zpravidla** zůstane neznámý — leda by týž certifikát někdo vložil i do jejich zásob.

**Znění té výjimky je moje, ne schválené.**

## Co záměrně rezolutní zůstalo

- „U tohoto podpisu **se jim ověření nepodaří dokončit**“ (text 3B) — schválené znění uživatele.
- „…takže razítko **odmítne** i posouzení podle platnosti certifikátu“ — to je tvrzení
  o pravidle, podle kterého ty nástroje pracují, ne o jejich celkovém výsledku; závěr té věty
  hedgovaný je.
- Celá **druhá část** (validátory podle standardů) zůstala beze změny. Tam jsou pravidla známá
  a úplná, takže předpověď podmiňovat netřeba — a zadání mluvilo o okruhu CzechPOINTu.


---

# 14. Podpis vzniklý mimo dobu platnosti certifikátu (19. 8. 2026)

Nahlášeno na `priklad8.pdf` — situace 25: existence podpisu je prokázána razítkem k 5. 12. 2020,
ale podpisový certifikát skončil už 27. 11. 2020. **Celý blok byl špatně, ve všech třech částech:**

| Část | Psalo | Proč špatně |
|---|---|---|
| 1 | „Nepodařilo se zjistit, zda podpisový certifikát nebyl k rozhodnému okamžiku odvolán…“ | jmenovala revokaci jako překážku a sváděla k tolerantnímu režimu, který s tím nic nesvede |
| 2 | „…takže jim bude chybět totéž“ | nic nechybí, doba platnosti je zapsaná v certifikátu |
| 3 | „**mohou dojít k příznivějšímu závěru než AnEP**: revokační informace … jim zpravidla stačí“ | slibovala, že okruh CzechPOINTu podpis uzná — přitom ho nemá uznat nikdo |

Situace 25 (a její dvojče 26, vznik před `notBefore`) proto dostala **přednost před formátem
i revokací** v první části a **vlastní větev před úvahami o razítkách** ve třetí.

## Nové texty

**Část 1** (nejvyšší priorita, před formátem i revokací):

> Ověření se přesto nepodařilo dokončit. Podpis vznikl {až po skončení | ještě před začátkem}
> řádné doby platnosti podpisového certifikátu (ta {skončila | začala} {DD. MM. RRRR HH:MM});
> existence podpisu je prokázána k {DD. MM. RRRR HH:MM}. **Certifikát v tu chvíli k podepisování
> neopravňoval, takže platnost podpisu nelze potvrdit — a nezmění na tom nic ani revokační
> informace, ani volnější nastavení.**

**Část 2:**

> **Ověřování nedokončí ani ony.** Doba platnosti certifikátu je zapsaná v něm samém a čtou ji
> stejně.

**Část 3:**

> **Mohou dojít k témuž závěru jako AnEP** — a k jinému by dojít neměly: certifikát v okamžiku
> vzniku podpisu {už | ještě} neplatil, a na tom jejich shovívavost k revokačním informacím nic
> nemění.

Sloveso je hedgované podle pravidla z oddílu 13, ale **odůvodnění zůstává rezolutní** („k jinému
by dojít neměly“). Je to rozdíl mezi tím, co ty nástroje udělají, a tím, co udělat mají.

## Ověřeno

- Poslední půlvěta první části („nezmění na tom nic ani volnější nastavení“) je **změřená**:
  týž dokument v tolerantním režimu dopadá stejně, verdikt se nemění.
- Situace 25 nezabírá u podpisu bez razítka s expirovaným certifikátem (`PAdES B-B.pdf`) —
  tam se rozhodný okamžik neprokázal a lepší popis je ten dosavadní. Pořadí větví to respektuje:
  chybějící důkaz existence má přednost.
- Přejezd 35 souborů z `D:	mp`: žádná výjimka, žádný prvek do nové větve nespadl (situace 25
  ani 26 se tam nevyskytují), takže se nic nepřerovnalo omylem.


---

# 15. Části 2 a 3 zrušeny (19. 8. 2026)

Rozhodnutí uživatele: **předpovídat, jak dopadne ověření v jiných validátorech, je obtížné
a složité.** Blok napříště řeší jen způsob ověřování v AnEP.

Je to správný závěr a stálo za to k němu dojít měřením. Předpovědět cizí nástroj znamená znát
jeho verzi, nastavení a obsah jeho vlastních zásob revokačních informací — a nic z toho AnEP
nemá. Každý jednotlivý případ šlo dotáhnout, jenže **jich přibývalo rychleji, než ubývalo**:

| Kolo | Co se rozbilo |
|---|---|
| 1 | texty psané pro kladný verdikt lhaly na kartách s neznámým a záporným (oddíl 7) |
| 2 | razítko od vyřazené služby: „zde razítko platí dál“ — jenže nedrží (oddíl 6) |
| 3 | důvod neúspěchu stál až v části o CzechPOINTu a četl se jako jejich vlastnost (oddíl 9) |
| 4 | 3B tvrdila rozchod u sedmi prvků, kde byla shoda (oddíl 11) |
| 5 | rezolutní „dojdou“ na osmi místech (oddíl 13) |
| 6 | podpis vzniklý po expiraci certifikátu: **všechny tři části** tvrdily něco jiného, než jak to je (oddíl 14) |

Šesté kolo je poučné nejvíc: nešlo o formulaci, ale o to, že blok neznal celou třídu případů —
a dokud nezná všechny, bude podobná díra vždycky ještě jedna.

## Co v kódu zůstalo

- **`ValidationCourse.revocationFetchedOnline`** se dál počítá a je správně; jen ho nikdo nečte.
  Výpočet je zadarmo (smyčka, která plní seznamy revokací, to stejně ví) a při návratu by chyběl.
  `customCrlDecisive` čte dál část první.
- **Věta o překážce** (`obstacleText()`, oddíl 9) zůstává — vznikla sice kvůli částem 2 a 3, ale
  je to fakt o dokumentu, ne předpověď. Bez ní by blok popsal, jak se ověřovalo, a zamlčel, čím
  to skončilo.
- Odstraněno: `officeOutcome()`, `noUsableTimestampOutcome()`, `lastValidTimestampCertExpiry()`,
  `partSeparator()`, `modeHeading()`, `modeSubtitle()` a s nimi nadpis jediné zbylé části —
  duplikoval hlavičku bloku.

## Jedna věta k rozhodnutí

V části první zůstala **„Výsledek tedy nezávisí na tom, čím byl získán.“** Je to poslední zbytek
předpovědi — slabý a nejmenující nikoho konkrétního, ale pořád tvrzení o cizích nástrojích.
Ponechána, protože patří ke schválenému textu 1A a čte se jako závěr o povaze zdejšího běhu.
**Kdyby měla jít pryč, je to jeden řádek.**

## Ověřeno po zrušení

Přejezd 35 souborů z `D:	mp`: žádná výjimka a **žádný zbytek** textů z částí 2 a 3.
