# Politika ověřování elektronických podpisů AnEP

**Analyzátor elektronických podpisů (AnEP) — politika ověřování podpisu**

==Verze 1.9== · stav ke dni 17. 10. 2026 · AnEP 1.0.0, knihovna DSS 6.5

## 1 Úvod

### 1.1 K čemu tento dokument je

Kvalifikované služby ověřování platnosti podpisů zveřejňují svou politiku ověřování — dokument, který říká, podle jakých pravidel o platnosti podpisu rozhodují. Dva různé ověřovatelé mohou o témž podpisu dojít k různým závěrům, a bez takového dokumentu nelze poznat, zda se jeden z nich mýlí, nebo zda jen každý uplatňuje jiné pravidlo.

AnEP není služba vytvářející důvěru a jeho výsledek nemá právní účinky výsledku kvalifikované služby. Právě proto ale má říct otevřeně, jak ověřuje: čtenář, který dostane od AnEP jiný závěr než od jiného nástroje, má mít možnost dohledat, v čem se postupy liší.

Dokument je stavěný jako politika ověřování podpisu ve smyslu ETSI TS 119 172-1 — popisuje postup ověřování krok po kroku podle ETSI EN 319 102-1 a vyjmenovává, kde se AnEP od výchozího nastavení knihovny DSS odchyluje. Nepopisuje provoz, personál ani odpovědnost poskytovatele; to jsou náležitosti politiky služby (ETSI TS 119 441), a ty u aplikace provozované uživatelem na vlastním počítači nemají předmět.

### 1.2 AnEP v kostce

- Ověřuje **lokálně**. Dokument ani jeho otisk nikam neodcházejí; po síti se stahují jen důvěryhodné seznamy, revokační informace (OCSP, CRL) a chybějící certifikáty vydavatelů.
- Ověřuje **podle ETSI EN 319 102-1** prostřednictvím knihovny DSS Evropské komise (verze 6.5), vždy až po úroveň dlouhodobého ověření s archivními daty.
- Důvěru odvozuje z **evropských důvěryhodných seznamů** (LOTL a národní seznamy) a z **vlastního úložiště certifikátů**, které spravuje uživatel.
- Ve dvou věcech je **mírnější než výchozí politika DSS**: nevyžaduje, aby podpis nesl deklarovaný čas podpisu (atribut signing-time), a nevyžaduje, aby tento čas ležel v době platnosti certifikátu. Obojí jen oznámí jako poznámku k výsledku.
- Čtyři další odchylky od standardů jsou **ústupky**, které si uživatel zapíná výslovně, vždy jen pro jedno ověření, a které se ve výsledku vykazují.
- Výsledek je jeden ze tří: **platný**, **neplatný**, nebo **neznámá platnost**. Neznámá platnost není neplatnost — znamená, že rozhodnutí nebylo možné učinit, a výsledek vždy říká proč.

## 2 Identifikace

| Položka | Hodnota |
|---|---|
| Název | Politika ověřování elektronických podpisů AnEP |
| Verze | ==1.9== ze dne 17. 10. 2026 |
| Platí pro | AnEP 1.0.0 (repozitář signature-analyzer, stav po commitu ==11f98cf==) |
| Navazující dokument | Politika uchovávání elektronických podpisů AnEP, verze ==1.9== |
| Ověřovací jádro | knihovna DSS (Digital Signature Service) 6.5 Evropské komise |
| Postup ověřování | ETSI EN 319 102-1 V1.4.0 (2024-03) |
| Struktura dokumentu | ETSI TS 119 172-1 (politika ověřování podpisu) |
| Kryptografická omezení | výchozí politika DSS 6.5 podle ETSI TS 119 312 |
| Identifikátor (OID) | nepřidělen |

Politika popisuje chování aplikace. Kde se aplikace změní, mění se i politika; každá verze uvádí, k jakému stavu aplikace se vztahuje. Co aplikace dělá s dokumentem, aby zůstal ověřitelný, popisuje samostatná Politika uchovávání elektronických podpisů AnEP; obě politiky se vydávají se shodným číslem verze a vztahují se k témuž stavu aplikace.

## 3 Model ověřování a vstupy

### 3.1 Model

EN 319 102-1 dělí ověřování na řídicí aplikaci (Driving Application, DA), která připraví vstupy a vyloží výsledek, a ověřovací aplikaci (Signature Validation Application, SVA), která podle politiky rozhodne. V AnEP je řídicí aplikací AnEP sám, ověřovací aplikací knihovna DSS s politikou podle této kapitoly a kapitoly 6. Celý dokument zpracovává SVA — AnEP nepracuje v rozděleném modelu, kdy se ověřovateli posílá jen otisk.

### 3.2 Vstupy

| Vstup | Popis |
|---|---|
| Podepsaný objekt | dokument s vloženým podpisem (PDF, kontejner ASiC, CMS/PKCS#7 s obsahem, XML, JSON) |
| Externí podpis | samostatný soubor podpisu (.p7s, .xml, …) předložený vedle podepsaného obsahu |
| Evidenční záznam | záznam podle RFC 4998 nebo RFC 6283 předložený vedle dokumentu |
| Čas ověření | aktuální čas, nebo okamžik zadaný uživatelem (viz 11.3) |
| Heslo | heslo k PDF, které jej bez hesla neotevře |
| Kotvy důvěry | důvěryhodné seznamy EU a vlastní úložiště certifikátů (kapitola 5) |
| Revokační údaje | vložené v podpisu, stažené on-line, z vlastního úložiště CRL (kapitola 7) |

Při ověřování externího podpisu se posuzuje jen externí podpis. Podpisy, které nese samo předložené PDF, se v tom případě neověřují; výsledek na ně upozorní.

## 4 Podporované formáty

| Formát | Úrovně | Poznámka |
|---|---|---|
| PAdES (PDF) | B-B, B-T, B-LT, B-LTA | včetně dokumentových časových razítek a slovníku DSS/VRI |
| PDF-NOT-ETSI a PKCS#7 v PDF (SubFilter adbe.pkcs7.*) | — | podpisy bez atributů, které vyžaduje PAdES; ověří se s ústupkem (11.2), jinak vyjdou s neznámou platností pro nesoulad formátu |
| adbe.x509.rsa_sha1 | — | DSS je nezpracuje; podpisovou hodnotu ověří AnEP sám, certifikát přes DSS se stejnými kotvami a revokačními zdroji |
| CAdES, CMS, PKCS#7 | B-B až B-LTA, starší úrovně (BES, T, C, X, XL, A) | včetně datových zpráv ISDS (.zfo) |
| XAdES | B-B až B-LTA, starší úrovně | |
| JAdES | B-B až B-LTA | |
| ASiC-S, ASiC-E (CAdES i XAdES) | B-B až B-LTA | včetně samostatných časových razítek a evidenčních záznamů uvnitř kontejneru |
| OpenDocument (ODF) | podle vloženého XAdES | ověřuje se jako kontejner ASiC s XAdES |
| Office Open XML (DOCX, XLSX, PPTX) | — | **neověřuje se**; AnEP jen přečte, co v podpisu stojí. DSS nezná transformaci, kterou OOXML ke krytí obsahu potřebuje |
| Samostatné časové razítko (RFC 3161) | — | ověří se jako samostatný prvek |
| Evidenční záznam (RFC 4998, RFC 6283) | — | jako doklad existence dat; viz 8.4 |

PDF, jehož hlavička `%PDF-` nezačíná na prvním bajtu, ale do prvního kilobajtu, se ověří od hlavičky; bajty před ní nekryje žádný podpis a výsledek je uvede.

## 5 Kotvy důvěry

### 5.1 Důvěryhodné seznamy EU

Výchozím zdrojem důvěry je seznam důvěryhodných seznamů Evropské komise (LOTL, `https://ec.europa.eu/tools/lotl/eu-lotl.xml`) a národní důvěryhodné seznamy, na které odkazuje. Podpisový certifikát seznamu LOTL se bere z jeho vlastního obsahu (pivoty LOTL jsou podporované).

- Seznamy se ukládají do mezipaměti a aplikace z ní startuje; kontrola nových verzí na síti probíhá na pozadí při spuštění aplikace, u každého národního seznamu nejvýše jednou za 12 hodin, a kdykoli na pokyn uživatele. Přerušená kontrola (zavřením aplikace) při dalším spuštění naváže u seznamů, které ještě ověřeny nebyly. Údaj o době ověření se po dokončení kontroly či obnovy na pozadí hned překreslí.
- Kotvou je certifikát služby uvedené v seznamu. Doba, po kterou kotva platí, končí tam, kde seznam službě ukončil kladný stav (tzv. sunset). Za kladný stav se považují i služby uznané na národní úrovni (`recognisedatnationallevel`, `setbynationallaw`).
- Kvalifikovanost certifikátu, podpisu a časového razítka určuje DSS podle údajů seznamu (typ služby, stav, kvalifikátory) k rozhodnému okamžiku.
- Je-li síť nedostupná, ověřuje se z mezipaměti; aplikace uvádí, kdy své kopie seznamů naposledy ověřila u zdroje, a na vyžádání i naposledy vydaný národní seznam (zemi, pořadové číslo a datum vydání).
- U každého podpisu, pečeti a časového razítka aplikace uvádí, podle kterého národního seznamu se jeho certifikát posuzoval: zemi, pořadové číslo seznamu a datum jeho vydání. Tím je doloženo, z jakého stavu seznamu závěr vychází.

### 5.2 Vlastní úložiště certifikátů

Uživatel může do vlastního úložiště vložit certifikáty, kterým má aplikace důvěřovat navíc — typicky kořen interní nebo komerční certifikační autority. Úložiště se čte při každém ověření znovu, změna platí okamžitě. Podpis, jehož řetěz vede k takové kotvě, výsledek označí jako ověřený vůči vlastní kotvě.

Certifikáty tuzemských komerčních autorit má AnEP přibalené jako **doplňkový materiál** pro sestavení řetězu, ne jako kotvy: důvěru k nim zakládá teprve vložení do vlastního úložiště.

### 5.3 Zvláštní případy kotev

- Je-li kotvou sám podpisový certifikát (seznam jej vede jako identitu služby), neposuzuje se jeho doba platnosti ani revokace; platí, dokud je služba v kladném stavu.
- Kotvou bývá i koncový certifikát časového razítka (TSU). Pro posouzení se bere identita služby, ne první certifikát řetězu.

## 6 Základní stavební bloky

Ověřování probíhá podle EN 319 102-1, kap. 5.2 a 5.3, s politikou, kterou tvoří výchozí politika DSS 6.5 s úpravami podle tabulky níže. Úpravy uvedené jako **trvalé** platí vždy; **ústupky** jen tehdy, když si je uživatel zapne (kapitola 11).

| Stavební blok (EN 319 102-1) | Realizace v AnEP | Odchylka od výchozí politiky DSS |
|---|---|---|
| Kontrola formátu (5.2.2) | DSS | ústupek: PDF-NOT-ETSI (11.2) |
| Identifikace podpisového certifikátu (5.2.3) | DSS; certifikát z podpisu, doplňkové certifikáty z AIA a přibaleného materiálu | žádná |
| Inicializace validačního kontextu (5.2.4) | DSS; kotvy podle kapitoly 5 | žádná |
| Kontrola čerstvosti revokačních dat (5.2.5) | neprovádí se (výchozí stav DSS) | žádná; viz 7.4 |
| Ověření certifikátu X.509 (5.2.6) | DSS; řetěz ke kotvě v modelu skořápky (6.2), revokace (kapitola 7) | ústupky: revokační údaje po expiraci, vydavatel revokace mimo platnost (11.2) |
| Kryptografické ověření (5.2.7) | DSS; u adbe.x509.rsa_sha1 AnEP | žádná |
| Ověření akceptace podpisu (5.2.8) | DSS | **trvalé:** deklarovaný čas (signing-time) se nevyžaduje; shoda deklarovaného času s platností certifikátu se nevyžaduje (6.1). Ústupky: SHA-1, PDF-NOT-ETSI |
| Prezentace výsledku (5.2.9) | AnEP (kapitola 12) | — |

### 6.1 Deklarovaný čas podpisu

Výchozí politika DSS požaduje, aby podpis nesl podepsaný atribut signing-time (u PAdES se za něj počítá i údaj `/M` ve slovníku podpisu) a aby tento čas ležel v době platnosti podpisového certifikátu. AnEP oba požadavky trvale snižuje na varování:

- Deklarovaný čas pochází z hodin počítače podepisujícího a nic nedokládá. O době vzniku podpisu rozhoduje časové razítko nebo jiný důkaz existence (kapitola 8).
- EN 319 102-1 deklarovaný čas potřebuje jen pro kontrolu zpoždění razítka (time-stamp delay, 5.5.3 krok 5), kterou výchozí politika DSS sama neprovádí.
- Kvalifikovaná služba NCA/I.CA QVerify deklarovaný čas podle své politiky ověřování nezpracovává vůbec.

Chybí-li atribut, je-li zapsán v podobě, kterou knihovna nepřečte (GeneralizedTime místo UTCTime, které RFC 5652 předepisuje pro roky 1950–2049), nebo leží-li deklarovaný čas mimo platnost certifikátu, výsledek to uvede jako poznámku: ověřovatelé postavení na výchozím nastavení DSS mohou takový podpis odmítnout.

### 6.2 Model ověření řetězu certifikátů

Řetěz certifikátů se ověřuje v **modelu skořápky** (shell model). Je to model výchozí politiky DSS 6.5 (`<Model Value="SHELL"/>`); AnEP ho nemění a žádný ústupek ho nepřepíná. Stejně se ověřují i řetězy certifikátů TSA u časových razítek.

Modely se liší tím, k jakému okamžiku musí platit certifikáty autorit nad podpisovým certifikátem. Podpisový certifikát sám musí ve všech třech platit k rozhodnému okamžiku — k času ověření, nebo, dokládá-li důkaz existence dřívější vznik podpisu, k okamžiku tohoto důkazu (8.4).

| Model | Certifikáty autorit musí platit (být v době platnosti a nezneplatněné) |
|---|---|
| skořápky (shell) — AnEP | k rozhodnému okamžiku, stejně jako podpisový certifikát |
| řetězu (chain) | každý k okamžiku, kdy vydal certifikát pod sebou |
| hybridní (hybrid) | k okamžiku vydání podpisového certifikátu |

Vyprší-li nebo je-li zneplatněn certifikát autority až poté, co vydala podpisový certifikát, obstojí podpis v modelu skořápky jen tehdy, když jeho vznik dokládá důkaz existence z doby, kdy certifikát autority ještě platil (časové razítko, evidenční záznam). Bez něj vede ověření k neznámé platnosti, ačkoli v modelu řetězu nebo v hybridním modelu by podpis obstál. Certifikáty autorit obvykle platí déle než certifikáty, které vydaly, a podpisový certifikát musí k rozhodnému okamžiku platit v každém modelu; modely se proto v praxi rozcházejí zřídka, hlavně při zneplatnění certifikátu autority (například po kompromitaci jejího klíče). Ověřovatelé, kteří pracují v jiném modelu, mohou v takovém případě o témž podpisu dojít k jinému závěru.

## 7 Revokační informace

### 7.1 Zdroje a jejich pořadí

1. Revokační údaje vložené v podpisu nebo v dokumentu (slovník DSS/VRI u PDF, atributy CAdES a XAdES).
2. On-line: nejprve OCSP, potom CRL z distribučního bodu certifikátu.
3. Vlastní úložiště CRL, je-li v něm nějaký seznam — jako poslední záchrana, když on-line zdroje k danému certifikátu nic použitelného nevrátí. Z uložených seznamů se vybere ten, který stav certifikátu v době jeho platnosti dokládá nejlépe.

### 7.2 Údaje vydané po expiraci certifikátu

RFC 5280 (3.3) dovoluje autoritě po expiraci certifikátu záznam o jeho revokaci ze seznamu odstranit. Seznam nebo odpověď vydané po expiraci proto o certifikátu nic průkazného neříkají:

- CRL vydaný po expiraci certifikátu, jehož rozšíření `expiredCertsOnCRL` chybí nebo je pozdější než konec platnosti certifikátu, se nepoužije vůbec.
- OCSP odpověď vydaná po expiraci certifikátu se nepoužije; jejím připuštěním je ústupek (11.2).
- Při ověření k zadanému okamžiku v minulosti (11.3) se připustí jen takový pozdější údaj, který revokaci **dokládá**; údaj hlásící „nerevokován“ se zahodí.

### 7.3 Vydavatel revokačního údaje

Certifikát vydavatele revokačního údaje (respondéra OCSP nebo vydavatele CRL) musí k rozhodnému okamžiku platit. Připuštění údaje od vydavatele mimo dobu platnosti je ústupkem (11.2).

### 7.4 Čerstvost

Čerstvost revokačních údajů se jako samostatné kritérium neposuzuje (výchozí stav DSS). EN 319 102-1 s ní pracuje jinak než jako s propouštěcí podmínkou: zastaralý údaj posouvá kontrolní okamžik do minulosti (posun času ověření, 5.6.2.2) a vyžádá si dřívější důkaz existence.

### 7.5 Který údaj rozhodl

Výsledek uvádí, který revokační údaj byl k rozhodnutí využit (odkud pochází a kdy byl vydán) a které další údaje dokument nese a proč využity nebyly.

## 8 Časová razítka a důkaz existence

### 8.1 Ověření razítka

Razítko se ověřuje podle RFC 3161 a stavebního bloku 5.4 EN 319 102-1: kryptografie, otisk, certifikát TSA s řetězem ke kotvě, revokace certifikátu TSA. Neplatné razítko se do dalšího postupu nepočítá jako důkaz existence, výsledek však uvede, proč neobstálo. Má-li prvek vedle něj jiné platné razítko, které důkaz existence nést může a vzniklo ještě za platnosti podpisového certifikátu, neobstálé razítko o výsledku nerozhoduje: výsledek ho uvede jako poznámku, ne jako důvod (12.2).

### 8.2 Kvalifikovanost služby a její vyřazení

Kvalifikovanost razítka se posuzuje k jeho nejstaršímu důkazu existence, ne k času, který razítko samo tvrdí. Razítko služby, které důvěryhodný seznam mezitím kladný stav odňal, výsledek vykáže; o tom, zda kotva platí, rozhoduje doba důvěry certifikátu, ne jen stav služby.

### 8.3 Dva režimy posuzování razítek

| Režim | Popis |
|---|---|
| podle standardů (výchozí) | razítko s expirovaným certifikátem TSA se uzná, je-li jeho platnost doložena dalším razítkem nebo je-li certifikát kotvou (EN 319 102-1) |
| podle platnosti certifikátů | razítko se uzná, jen dokud platí certifikát TSA; odpovídá praxi některých tuzemských ověřovatelů |

Druhý režim zapíná uživatel výslovně a platí jen pro jedno ověření.

### 8.4 Zdroje důkazu existence

Rozhodný okamžik (best-signature-time) určují v tomto pořadí důkazy, které existenci podpisu dokládají nejdříve:

1. platné podpisové časové razítko, dokumentové razítko a archivní razítka (řada razítek, kde každé další prodlužuje doklad toho předchozího);
2. evidenční záznam (RFC 4998, RFC 6283) předložený s dokumentem nebo uložený v kontejneru;
3. **pozdější podpis s platným razítkem, který tento podpis kryje** (u PDF podle rozsahu bajtů). EN 319 102-1 pro to má vstup „time indication for signature existence“ předávaný řídicí aplikací (tabulka 20, krok 5.5.4); AnEP jej předá DSS a ověření zopakuje. Výsledek takto odvozený důkaz uvede výslovně, protože jiní ověřovatelé tuto úvahu obvykle nedělají.

Bez jakéhokoli důkazu existence je rozhodným okamžikem čas ověření.

## 9 Validační procesy

Ověřuje se vždy nejvyšším procesem, tedy **procesem pro podpisy s archivními daty** (EN 319 102-1, 5.6), který v sobě obsahuje proces pro základní podpisy (5.3) i proces pro podpisy s časem a dlouhodobými validačními daty (5.5). Používají se stavební bloky ověření v minulosti (5.6.2.1, 5.6.2.4), posunu času ověření (5.6.2.2) a extrakce důkazu existence (5.6.2.3).

## 10 Kryptografická omezení

AnEP přebírá kryptografická omezení beze změny z výchozí politiky DSS 6.5 (ETSI TS 119 312). Algoritmus se posuzuje k okamžiku, kdy je jeho použití doloženo; pozdější razítko se silnějším algoritmem může podpis se slabším algoritmem udržet.

Data v tabulce jsou okamžiky, od kterých ETSI TS 119 312 algoritmus za spolehlivý nepovažuje. Co ověření udělá s algoritmem použitým po takovém datu, záleží na tom, zda datum leží před poslední aktualizací kryptografické sady ve výchozí politice DSS (1. 2. 2026), nebo za ní:

- **datum před aktualizací** je zjištěná skutečnost — algoritmus ověření **zamítne**;
- **datum za aktualizací** je odhad do budoucna — algoritmus ověření nezamítne, jen k výsledku připojí **varování**, že se algoritmus už nepovažuje za spolehlivý.

Tak postupuje knihovna DSS ve výchozím nastavení a AnEP to nemění.

| Algoritmus | Nespolehlivý od | Po tomto datu |
|---|---|---|
| MD5 | 1. 8. 2004 | zamítnutí |
| SHA-1 | 1. 8. 2012 (ústupek 11.2 jej připustí) | zamítnutí |
| RIPEMD-160 | 1. 8. 2014 | zamítnutí |
| SHA-224 | 1. 1. 2029 | varování |
| SHA-256, SHA-384, SHA-512, SHA3-256/384/512 | bez omezení | — |
| SHA3-224, MD2 | nepřipouštějí se vůbec — ETSI TS 119 312 je neuvádí | zamítnutí |
| RSA, RSASSA-PSS 786–1 023 bitů | 1. 8. 2010 | zamítnutí |
| RSA, RSASSA-PSS 1 024–1 899 bitů | 1. 10. 2019 | zamítnutí |
| RSA, RSASSA-PSS 1 900–2 999 bitů | 1. 1. 2027 | varování |
| RSA 3 000 bitů a více | 1. 1. 2035 | varování |
| RSASSA-PSS 3 000 bitů a více | bez omezení | — |
| ECDSA pod 224 bitů | 1. 8. 2012 | zamítnutí |
| ECDSA 224–255 bitů | 1. 10. 2021 | zamítnutí |
| ECDSA 256 bitů a více | bez omezení | — |

Nejvíc se to týká klíčů RSA o 2 048 bitech, nejrozšířenějších u podpisových certifikátů i u autorit časových razítek. Ověřeno 5. 10. 2026: podpis, jehož poslední časové razítko je podepsané takovým klíčem, vyjde při ověření k 1. 3. 2027 jako platný, s varováním u podpisu, u razítka i u revokačního údaje. Platnost tedy 1. 1. 2027 nekončí. Ověřovatelé, kteří uplatňují aktualizovanou kryptografickou sadu nebo vlastní, přísnější pravidla, ale mohou takový algoritmus po tomto datu odmítnout.

Klíče RSA kratší než 786 bitů a ECDSA kratší než 160 bitů se nepřipouštějí vůbec. MD5 a RIPEMD-160 nespraví žádný ústupek, a stejně tak SHA3-224: ta slabá není, jen ji standardy pro elektronické podpisy neuvádějí (od 4. 10. 2026 ji AnEP hlásí jako samostatný důvod).

## 11 Ústupky

### 11.1 Co je ústupek

Ústupek je odchylka od standardů, kterou si uživatel zapne výslovně, aby zjistil, zda by podpis obstál za mírnějších podmínek. Platí vždy **jen pro jedno ověření** — po dokončení ověření se politika vrátí do výchozího stavu. Ústupky se neukládají a nepřenášejí mezi spuštěními aplikace.

U výsledku dosaženého s ústupky AnEP změří, které z nich byly pro kladný výsledek rozhodující (opakovaným ověřením bez nich), a vyjmenuje je. Výsledek s ústupkem nikdy netvrdí, že ověření proběhlo podle standardů.

### 11.2 Přehled ústupků

| Ústupek | Co připouští | Úprava politiky DSS |
|---|---|---|
| Využij i revokační informace vydané po expiraci certifikátu | OCSP či CRL vydané po skončení platnosti certifikátu | RevocationHasInformationAboutCertificate → IGNORE, AcceptableRevocationDataFound a RevocationIssuerValidAtProductionTime → WARN |
| Využij i revokační informace od již expirovaného vydavatele | údaj, jehož vydavatel k rozhodnému okamžiku neplatil | RevocationIssuerNotExpired → WARN |
| Ignorovat zastaralost algoritmu SHA-1 | podpisy a certifikáty s SHA-1 | datum zastarání SHA-1 posunuto na 1. 1. 2099 |
| Ověřovat i podpisy ve formátu PDF-NOT-ETSI | podpisy PDF bez atributů vyžadovaných PAdES | MessageDigestOrSignedPropertiesPresent → WARN |

Volba „Ověřování podpisů a pečetí dle standardů“ vypne nebo zapne všechny čtyři najednou. Odkaz „Ověřit s ústupky podatelny katastru“ v posouzení vkladové listiny (12.5) zapne pro jedno opakované ověření tři z nich — všechny kromě SHA-1.

### 11.3 Ověření k zadanému okamžiku

Uživatel může ověřit k okamžiku v minulosti. Protože revokační údaj stažený dnes je vůči tehdejšímu certifikátu vždy vydaný po jeho expiraci, uděluje si zadaný okamžik ústupek z 7.2 sám, avšak **zúžený**: propustí jen údaj, který revokaci dokládá. Výsledek vždy uvede, že platí k zadanému okamžiku, ne k dnešku.

## 12 Výsledek ověření

### 12.1 Indikace

| Výsledek | Indikace EN 319 102-1 | Význam |
|---|---|---|
| Platný | TOTAL-PASSED | ověření bylo dokončeno a podpis obstál |
| Neplatný | TOTAL-FAILED | ověření bylo dokončeno a prokázalo vadu (porušená integrita, revokace před podpisem, …) |
| Neznámá platnost | INDETERMINATE | ověření nebylo možné dokončit; uvede se sub-indikace a důvody |

### 12.2 Vysvětlení výsledku

Výsledek se netváří jako konečný verdikt bez odůvodnění. K neplatnému a neznámému výsledku uvádí **důvody** — číslovaná, česky formulovaná zjištění, každé s popisem problému, technickým rozvedením a radou, co může pomoci. Důvody řadí od těch, které žádný ústupek nespraví, k těm, které ústupek spraví.

Důvody mají říkat, co výsledek skutečně drží. Proto se nálezy navzájem potlačují — silnější zjištění umlčí slabší, a rada, po které by se výsledek nezměnil, se nevydá. Neobstálé časové razítko se jako důvod uvede jen tehdy, když prvek nemá jiné platné razítko z doby platnosti podpisového certifikátu; jinak by zastínilo skutečnou příčinu výsledku, typicky chybějící revokační informace, u nichž ověření podle EN 319 102-1 ke zkoumání razítek vůbec nedojde.

Ke každému výsledku pak uvádí **průběh ověřování** (k jakému okamžiku se ověřovalo, o co se rozhodný okamžik opřel, který revokační údaj rozhodl) a **poznámky k výsledku** — výhrady, které verdikt nemění, ale čtenář je potřebuje znát, například že jiní ověřovatelé mohou podpis odmítnout kvůli deklarovanému času (6.1), že kvalifikovanost razítka stojí na službě s odňatým stavem, nebo že důkaz existence byl odvozen z pozdějšího podpisu.

### 12.3 Právní kvalifikace

Druh podpisu se uvádí podle nařízení eIDAS (kvalifikovaný elektronický podpis, zaručený podpis založený na kvalifikovaném certifikátu, …) a podle zákona č. 297/2016 Sb. (uznávaný elektronický podpis, uznávaná elektronická pečeť).

U uznávaného elektronického podpisu se uvádí, zda může mít účinky úředně ověřeného podpisu podle § 6 odst. 2 zákona č. 12/2020 Sb. Že je má, se uvádí jen tehdy, když kvalifikovaný certifikát nese zapsaný osobní doklad držitele. Doklad se čte z atributu serialNumber předmětu certifikátu podle registračních schémat ETSI EN 319 412-1 čl. 5.1.3, a to jen ze dvou: `IDCCZ-` (občanský průkaz) a `PASCZ-` (cestovní pas). Jiné hodnoty se uvádějí jako identifikátor držitele bez výkladu.

Předmět certifikátu může nést **více atributů serialNumber** — například číslo dokladu a vedle něj klientské číslo vydavatele. Posuzují se všechny, nikoli jen první; nese-li certifikát dokladů více, uvedou se všechny.

### 12.4 Protokoly

K výsledku jsou k dispozici protokoly knihovny DSS (zjednodušený, podrobný, diagnostická data, protokol podle ETSI TS 119 102-2) a protokol AnEP ve formátu PDF/A-2b. Výsledek AnEP není opatřen podpisem ani pečetí — AnEP není kvalifikovanou službou a jeho výsledek je podkladem, ne potvrzením.

### 12.5 Posouzení dokumentu jako vkladové listiny

V expertním módu AnEP u každého dokumentu PDF s vnitřními podpisy posoudí, zda splňuje technické požadavky ČÚZK na elektronickou vkladovou listinu: formát PDF, nejde o prostý sken, podpisy jsou platné a založené na kvalifikovaném certifikátu, certifikáty jsou v dokumentu, pravost podpisu je prokázána (IK MPSV či číslo osobního dokladu v certifikátu; zápis v registru obyvatel a úřední ověření AnEP posoudit nedokáže) a po posledním podpisu je platné kvalifikované časové razítko. Pečeti a značky se neposuzují. Výsledek zní „splňuje“, „nesplňuje“, nebo „nelze posoudit“.

Posouzení platnosti podpisů vychází z informace podatelny katastru (říjen 2026): podatelna ověřuje nejprve podle standardů bez ústupků, a když podpis neobstojí, může jej ověřit ještě službou SecuSign Validation, a využít tak i její vlastní zásobu revokačních informací. Odtud plyne:

- Podpis platný s ústupky z 11.2 **kromě SHA-1** požadavek splňuje.
- Podpis platný jen díky ústupku pro SHA-1 i podpis, jehož platnost je neznámá kvůli SHA-1 (důvod 15), požadavek **nesplňuje** — podatelna SHA-1 nepřijímá.
- Podpis **neznámé platnosti** vede k výsledku „nelze posoudit“. Neproběhlo-li ještě ověření s ústupky podatelny, AnEP je doporučí; proběhlo-li a platnost zůstala neznámá kvůli revokačním informacím, upozorní, že podatelna může mít k dispozici vlastní zásobu revokačních informací služby, kterou využívá.
- Podpis platný jen s ústupky, který potřebuje časové razítko a nemá žádné, jehož certifikát by ještě platil, vede k výsledku „nelze posoudit“: ověřovatel, který posuzuje razítka podle platnosti certifikátů (druhý režim v 8.3), takové razítko uznat nemusí.
- U podpisu v nereferenčním formátu AnEP upozorní, že podatelna takové podpisy zatím přijímá, ale chystá se jejich přijímání omezit; na výsledek to vliv nemá.

Posouzení je odhadem na základě výsledku ověření, ne rozhodnutím podatelny; věcný obsah dokumentu ani to, zda jej podepsali všichni účastníci, AnEP neposuzuje.

### 12.6 Změny dokumentu PDF po podpisu

Podpis v PDF nekryje celý soubor, nýbrž tu jeho podobu (revizi), která existovala v okamžiku podpisu. Co přibylo přírůstkovou aktualizací potom, kryté není. AnEP přebírá posouzení takových změn z výchozí politiky DSS 6.5 beze změny:

| Změna po podpisu | Úroveň v politice DSS | Dopad na verdikt |
|---|---|---|
| přidání či odebrání stránky (`PdfPageDifference`) | FAIL | podpis neobstojí |
| změna obsahu stránky (`PdfVisualDifference`) | WARN | verdikt se nemění, jen varování |
| zásah do formulářových a podpisových polí, anotací, nedefinované změny | WARN | verdikt se nemění, jen varování |

Změnu obsahu stránky — tedy to, že čtenář dnes vidí něco jiného, než co bylo podepsáno — AnEP přesto výslovně vykazuje, protože podpis platný podle verdiktu se pak týká jiné podoby dokumentu, než jakou má čtenář před sebou:

- v **Zjednodušeném přehledu** jedním řádkem s čísly dotčených stran;
- v **Rozšířeném přehledu** samostatným blokem nad kartami prvků, který uvede dotčené strany a nabídne náhled dokumentu v podobě kryté podpisem i v dnešní podobě.

Jiné validátory mohou takovou změnu hodnotit jinak, například neplatností posledního podpisu nebo neznámým stavem jeho platnosti.

Přibyla-li za posledním podpisem či razítkem jen validační data nebo jiné rozšíření dokumentu, obsah se nemění; AnEP to uvede v expertním módu jako informaci, že přibylé údaje nekryje žádný podpis ani razítko.

### 12.7 Prohlížení podpisové struktury (Inspektor)

V expertním módu nabízí AnEP v záložce Detailní rozbor **Inspektor** — prohlížeč podpisové struktury dokumentu PDF, podpisu CAdES, kontejneru ASiC a podpisu XAdES a JAdES. Ukazuje strukturu tak, jak je v souboru uložena: podpisy a pečetě, časová razítka včetně razítek vnořených v podpisech, u PDF slovník `/DSS`, záznamy `/VRI` a revize dokumentu s podobou `/DSS` v každé z nich, u podpisů jejich atributy či vlastnosti, certifikáty, seznamy CRL a odpovědi OCSP.

Inspektor čte soubor nezávisle na ověřování a o výsledku ověření **nerozhoduje** — jeho zjištění do verdiktu, důvodů ani poznámek k výsledku nevstupují. Přepočítává jen to, co lze zjistit bez politiky ověřování:

- otisk podepsaných dat (atribut message-digest nad rozsahem `/ByteRange` či nad podepsaným souborem, odkazy podpisu XAdES, oddělená data podpisu JAdES),
- otisk v časovém razítku podpisu a razítku obsahu,
- otisk podpisového certifikátu uvedený v podpisu,
- strukturní souvislosti: ke kterému podpisu patří záznam VRI, ve které revizi přibyla položka `/DSS`, zda rozsah podpisu končí na konci revize.

Kryptografickou platnost podpisů, řetězy certifikátů ani stav revokace neposuzuje; to je věcí ověřování podle této politiky. U prvků, které se podaří spárovat s výsledkem ověření, uvádí odděleně od zjištění o souboru, jak s nimi ověřování naložilo: výsledek podpisu či razítka, roli certifikátu v řetězu a který revokační údaj rozhodl, nebyl přijat nebo nebyl použit.

## 13 Omezení podle ETSI TS 119 172-1

Tabulka uvádí, jak AnEP naplňuje omezení podle přílohy A.4.2.1 (tabulka A.2) a přílohy C normy ETSI TS 119 172-1.

| Omezení | Hodnota v AnEP |
|---|---|
| (m)1.1 SetOfTrustAnchors | důvěryhodné seznamy EU (LOTL a národní) a vlastní úložiště certifikátů; doba důvěry podle stavu služby (kapitola 5) |
| (m)1.2 CertificationPath | sestavuje se z certifikátů v podpisu a dokumentu, z AIA a z přibaleného doplňkového materiálu |
| (m)1.3–1.10 počáteční parametry RFC 5280 6.1.1 | neuplatňují se (výchozí hodnoty) |
| (m)2.1 RevocationCheckingConstraints | eitherCheck — OCSP nebo CRL (kapitola 7) |
| (m)2.2 RevocationFreshnessConstraints | neuplatňují se (7.4) |
| (m)2.3 RevocationInfoOnExpiredCerts | údaj po expiraci jen s rozšířením expiredCertsOnCRL dřívějším než konec platnosti certifikátu (7.2) |
| (m)3 LoAOnTSPPractices | neuplatňuje se |
| a) EUQualifiedCertificateRequired | ne — kvalifikovanost se vyhodnotí a uvede, nevyžaduje se |
| b) EUQualifiedCertificateSigRequired, c) EUQualifiedCertificateSealRequired | ne — vyhodnotí se a uvede |
| d) EUSSCDRequired, g) EUQSigCDRequired, h) EUQSealCDRequired | ne — vyhodnotí se a uvede |
| e) EUAdESigRequired, f) EUAdESealRequired | ne — vyhodnotí se a uvede |

## 14 Srovnání s jinými ověřovateli

Pro orientaci, proč mohou různé nástroje dojít k různým závěrům. Údaje o ostatních ověřovatelích pocházejí z jejich zveřejněných politik a z pozorování, ne z jejich zdrojového kódu.

| Pravidlo | AnEP | SecuSign Validation (Software602) | QVerify (I.CA, NCA) |
|---|---|---|---|
| Ověřovací jádro | DSS 6.5 | vlastní | vlastní knihovny I.CA |
| Dostupnost | aplikace pro kohokoli | veřejná služba | jen na smlouvu |
| Deklarovaný čas (signing-time) | nevyžaduje, jen poznámka | čte i nestandardní zápis | nezpracovává |
| Deklarovaný čas mimo platnost certifikátu | nevadí, jen poznámka | neuvedeno | nezpracovává |
| Čerstvost revokace | neposuzuje | 24hodinová ochranná lhůta volitelná, ve výchozím stavu vypnutá | podle nextUpdate; bez něj neprojde |
| Vlastní archiv CRL | úložiště spravované uživatelem | stahuje CRL každých 60 minut | neuvedeno |
| SHA-1 | jen jako ústupek | přijímá bez poznámky (pozorováno) | nepřijímá |
| Starší a nereferenční formáty | ano (PDF-NOT-ETSI jako ústupek) | výslovně vyjmenovává | nejmenuje |
| ASiC a XAdES úrovně LTA | ano | ano | jen do LT |
| Podpisová politika v PAdES a CAdES | podporována | neuvedeno | odmítá |
| Změny PDF po podpisu | vykáže, rozdíl ve vzhledu je varování (12.6) | hlásí | po posledním podpisu jen validační data a metadata |
| Evidenční záznamy | ano | neuvedeno | ne |
| Ověření k zadanému okamžiku | ano (11.3) | ano, jen s volbou „ignorovat chybějící důkaz existence“ | neuvedeno |

## 15 Změny politiky

| Verze | Datum | Změna |
|---|---|---|
| 1.0 | 4. 10. 2026 | první vydání |
| 1.1 | 5. 10. 2026 | oddíl 10: rozlišeno, kdy vypršení algoritmu vede k zamítnutí a kdy jen k varování; doplněn RSASSA-PSS s klíčem 3 000 bitů a více; odkaz na politiku uchovávání vydanou spolu s touto verzí a stav aplikace sjednocený s ní |
| 1.2 | 8. 10. 2026 | oddíl 12.3: účinky úředně ověřeného podpisu a zapsaný osobní doklad; posuzují se všechny atributy serialNumber předmětu certifikátu, ne jen první |
| 1.3 | 8. 10. 2026 | nový oddíl 12.5: posouzení dokumentu jako vkladové listiny podle postupu podatelny katastru (ústupky kromě SHA-1, neznámá platnost vede k „nelze posoudit“); oddíl 11.2: místo tlačítka tolerantního ověření, které se už nenabízí, odkaz pro ověření s ústupky podatelny katastru |
| 1.4 | 9. 10. 2026 | nový oddíl 12.6: změny dokumentu PDF po podpisu — jak je posuzuje politika DSS, kde je AnEP vykazuje (Zjednodušený a Rozšířený přehled, náhled kryté i dnešní podoby) a že je jiné validátory mohou hodnotit jinak |
| 1.5 | 13. 10. 2026 | oddíly 8.1 a 12.2: neobstálé časové razítko se uvádí jako důvod jen tehdy, když prvek nemá jiné platné razítko z doby platnosti podpisového certifikátu; jinak jako poznámka k výsledku |
| 1.6 | 13. 10. 2026 | nový oddíl 12.7: Inspektor podpisové struktury v expertním módu — co ukazuje, které otisky přepočítává a že jeho zjištění do výsledku ověření nevstupují; ověřování samo se nemění |
| 1.7 | 17. 10. 2026 | nový oddíl 6.2: řetěz certifikátů se ověřuje v modelu skořápky (shell) výchozí politiky DSS — čím se liší od modelu řetězu a hybridního modelu a kdy to mění závěr; odkaz na něj v tabulce stavebních bloků (5.2.6); ověřování samo se nemění |
| ==1.8== | ==17. 10. 2026== | ==beze změny obsahu; číslo verze a stav aplikace sjednocené s verzí 1.8 politiky uchovávání, která mění, jaké revokační údaje vkládá přerazítkování== |
| ==1.9== | ==17. 10. 2026== | ==beze změny obsahu; číslo verze a stav aplikace sjednocené s verzí 1.9 politiky uchovávání, která upřesňuje, jaké revokační údaje vkládá prodloužení== |
