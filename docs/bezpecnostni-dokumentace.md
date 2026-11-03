# Technická a bezpečnostní dokumentace AnEP

**Analyzátor elektronických podpisů (AnEP) — podklad pro posouzení podle zákona o kybernetické bezpečnosti a aktu o kybernetické odolnosti**

Verze 1.2 (návrh) · stav ke dni 3. 11. 2026 · AnEP 1.2.0, knihovna DSS 6.5.1

## 1 Úvod

### 1.1 K čemu tento dokument je

Dokument popisuje AnEP z hlediska kybernetické bezpečnosti: co aplikace je, z čeho se skládá, s čím pracuje, kudy na ni může útok přijít, jak se brání, jaká rizika zůstávají a co má udělat ten, kdo ji provozuje. Je určen povinné osobě, která chce AnEP používat ve své síti (zde síť resortu justice), jejím bezpečnostním rolím a správcům.

Struktura sleduje obsah technické dokumentace podle přílohy VII aktu o kybernetické odolnosti a informace pro uživatele podle jeho přílohy II. Kapitola 10 pak přiřazuje údaje z dokumentu k bezpečnostním opatřením, která povinné osobě ukládá vyhláška k zákonu o kybernetické bezpečnosti.

Síťovou komunikaci aplikace podrobně popisuje samostatný **Přehled online komunikace AnEP**; tento dokument z něj přebírá jen závěry.

### 1.2 Právní rámec

| Předpis | Stav | Koho zavazuje |
|---|---|---|
| Zákon č. 264/2025 Sb., o kybernetické bezpečnosti | účinný od 1. 11. 2025 | poskytovatele regulované služby — zde provozovatele sítě justice; autora aplikace nikoli |
| Vyhláška č. 409/2025 Sb., o bezpečnostních opatřeních poskytovatele regulované služby v režimu vyšších povinností | účinná od 1. 11. 2025 | povinnou osobu v režimu vyšších povinností |
| Vyhláška č. 410/2025 Sb. (režim nižších povinností) | účinná od 1. 11. 2025 | povinnou osobu v režimu nižších povinností |
| Nařízení (EU) 2024/2847 (akt o kybernetické odolnosti, CRA) | v platnosti od 10. 12. 2024; kapitola IV použitelná od 11. 6. 2026, povinnost hlásit podle čl. 14 od 11. 9. 2026, ostatní ustanovení od 11. 12. 2027 | výrobce, dovozce a distributory produktů s digitálními prvky dodávaných na trh v rámci obchodní činnosti |

Stav nařízení ověřen v záhlaví EUR-Lex dne 3. 11. 2026: nařízení je v platnosti, bylo opraveno (opravy jednotlivých jazykových znění) a změněno nařízením (EU) 2025/327 s účinností změn od 26. 3. 2027 (čl. 13 odst. 4, čl. 31 odst. 3, čl. 32 odst. 5a); tyto změny se AnEP netýkají.

Dokument předpokládá, že provozovatel je v **režimu vyšších povinností**, a odkazuje proto na vyhlášku č. 409/2025 Sb. Je-li v režimu nižších povinností, platí odkazy přiměřeně pro vyhlášku č. 410/2025 Sb.

### 1.3 Postavení AnEP podle aktu o kybernetické odolnosti

AnEP je svobodný software s otevřeným zdrojovým kódem (licence EUPL 1.2), který autor jako fyzická osoba zveřejňuje bezplatně na své stránce; celý zdrojový kód je veřejně dostupný na GitHubu (kapitola 2.1). Podle bodu 18 odůvodnění nařízení se poskytování takového softwaru, které výrobce nezpeněžuje, nepovažuje za obchodní činnost, a software tak není „dodáván na trh“ ve smyslu nařízení. **Povinnosti výrobce podle aktu o kybernetické odolnosti se proto na AnEP v nynější podobě distribuce nevztahují.** Nevztahuje se na něj ani režim správce softwaru s otevřeným zdrojovým kódem (bod 19 odůvodnění), který míří na právnické osoby.

Posouzení se může změnit, pokud by autor za AnEP nebo za jeho podporu, úpravy či provoz přijímal úplatu, například na základě smlouvy s resortem. Pak by šlo o dodávání v rámci obchodní činnosti a povinnosti výrobce by od 11. 12. 2027 (hlášení od 11. 9. 2026) platily plně.

AnEP certifikáty nevydává ani nespravuje; ověřuje podpisy a pečetě. Mezi důležité produkty podle přílohy III nařízení (třída I, bod 9: infrastruktura veřejných klíčů a software k vydávání digitálních certifikátů) by proto podle znění přílohy nepatřil a spadal by do výchozí kategorie s posouzením shody samotným výrobcem.

Dokument přesto požadavky přílohy I a obsah přílohy VII sleduje dobrovolně: povinná osoba podle zákona o kybernetické bezpečnosti je při pořizování aktiv potřebuje posoudit tak jako tak (§ 12 vyhlášky) a pro případ, že by se AnEP stal produktem dodávaným na trh, ukazuje, co chybí (kapitola 12).

## 2 Identifikace produktu

| Položka | Hodnota |
|---|---|
| Název | AnEP — Analyzátor elektronických podpisů |
| Verze | 1.2.0 |
| Výrobce (autor) | Jiří Peterka, jiri@peterka.cz |
| Stránka | https://bajecnysvet.cz/anep/ |
| Licence | EUPL 1.2 (české a anglické znění přiloženo); licence přibalených knihoven v souboru NOTICE.txt a ve složce licenses |
| Zdrojový kód | veřejný repozitář https://github.com/jiripeterka-hub/signature-analyzer (větev `master`); každé vydání má značku, verze 1.2.0 značku `v1.2.0` |
| Platforma | Windows 10 a 11, 64bitové; sestavení pro Linux |
| Distribuce | instalátor MSI a přenosný archiv ZIP pro Windows, archiv pro Linux |
| Podpis instalátoru | podpis kódu (Authenticode, SHA-256) certifikátem autora od Certum Code Signing 2021 CA, s časovým razítkem Certum; otisk SHA-256 certifikátu `13930456cefa0384b6376360866f06df3e355c5fa58a52c16acddbe909f0a73c`, platnost 1. 11. 2026 – 1. 11. 2027 |
| Hlavní komponenty | DSS 6.5.1 (Evropská komise), Apache PDFBox, Bouncy Castle, Apache Santuario, Apache HttpClient 5.6, Apache FOP 2.11, Saxon-HE 12.5, JavaFX 21.0.4, přibalené běhové prostředí Javy |

Verzi nainstalované aplikace ukazuje okno O aplikaci. Pravost instalátoru lze ověřit ve vlastnostech souboru (Digitální podpisy) porovnáním otisku certifikátu s údajem výše a na stránce aplikace; AnEP sám ji ověří v prohlížeči kontejnerů (řádek „Sestavení AnEP“).

### 2.1 Zdrojový kód a vlastní sestavení

Úplný zdrojový kód AnEP je veřejně dostupný v repozitáři **https://github.com/jiripeterka-hub/signature-analyzer**. Obsahuje kód aplikace, automatické testy, ověřovací politiku (`policy/constraint.xml`), skripty pro sestavení balíčků pro Windows a Linux, popis architektury (`docs/architecture.md`) a soupis změn (`CHANGELOG.md`). Každé vydání je označeno značkou (`v1.0`, `v1.1.0`, `v1.1.1`, `v1.2.0`), takže lze dohledat přesný stav kódu, z něhož byla daná verze sestavena. Linuxové balíčky se sestavují veřejně v GitHub Actions (workflow `linux-portable.yml`) a záznam o každém sestavení je v repozitáři k nahlédnutí.

Pro povinnou osobu to znamená tři věci:

- **Kód lze prověřit.** Vlastní bezpečnostní rozbor, revize kódu nebo test podle § 24 vyhlášky nemusí stát jen na tomto dokumentu; lze ho provést nad týmž kódem, z něhož vznikají vydané balíčky.
- **Aplikaci si může sestavit každý sám.** Licence EUPL 1.2 dovoluje kód užívat, měnit i šířit. Organizace si tak může sestavit vlastní instanci z ověřeného stavu kódu (například ze značky `v1.2.0`) a nemusí spoléhat na instalátor stažený ze stránky autora. Potřebuje k tomu JDK 21 nebo novější a Maven 3.9 nebo novější, pro instalátor MSI ještě WiX Toolset v3; postup je v souboru `README.md` v repozitáři (`mvn -pl app -am package`, balíčky skriptem `packaging/windows/dist.ps1` nebo `packaging/linux/dist.sh`). Knihovny třetích stran se při sestavení stahují z repozitáře Maven Central.
- **Údržba nestojí jen na autorovi.** Kdyby autor přestal aplikaci udržovat, může opravy zranitelností nebo aktualizace knihoven provést organizace sama nebo jejím jménem někdo jiný (riziko R11).

Vlastní sestavení má dvě odlišnosti, s nimiž je třeba počítat:

- **Není podepsané autorem.** Podpis instalátoru a programu je vázán na certifikát autora, který ke zdrojovému kódu nepatří. Vlastní sestavení je proto nepodepsané, nebo podepsané certifikátem organizace, a AnEP sám je v prohlížeči kontejnerů neoznačí jako oficiální sestavení. Organizace, která sestavuje sama, by měla sestavení podepsat vlastním certifikátem pro podpis kódu a evidovat otisky výsledných balíčků (§ 7 vyhlášky).
- **Jméno.** Licence se nevztahuje na jméno „AnEP“ a ikonu aplikace. Kdo kód upraví a upravenou verzi dále šíří, má ji pojmenovat jinak; sestavení beze změny kódu pro vlastní potřebu organizace se to netýká.

Zda je sestavení reprodukovatelné, tedy zda z téže značky vznikne bajtově totožný balíček, ověřováno nebylo; přibalené běhové prostředí Javy se bere z JDK na počítači, kde se sestavuje.

## 3 Zamýšlený účel a bezpečnostní prostředí

### 3.1 Účel

AnEP ověřuje elektronické podpisy, pečetě a časová razítka (PAdES, CAdES, XAdES, JAdES, ASiC, datové zprávy ISDS, záznamy o důkazech) podle nařízení eIDAS a norem ETSI a vykládá výsledek česky. Na pokyn uživatele umí doplnit do dokumentu údaje pro dlouhodobé ověření a časové razítko (vždy do nového souboru) a vytvořit forenzní balíček. Jeho výstup je podkladem pro odborné posouzení; AnEP není kvalifikovanou službou ověřování platnosti ani uchovávání a jeho výstup nemá právní účinky jejich výstupu.

### 3.2 Bezpečnostní prostředí, s nímž se počítá

- **Jeden uživatel na vlastním počítači.** AnEP je desktopová aplikace bez serverové části, bez uživatelských účtů a bez vzdálené správy. Přístup k aplikaci a k jejím datům řídí operační systém.
- **Nedůvěryhodné vstupy.** Ověřované soubory pocházejí z vnějšku a mohou být záměrně upravené. Aplikace s nimi tak zachází (kapitola 5).
- **Síť je volitelná.** Aplikace funguje bez sítě; se sítí doplňuje aktuální důvěryhodné seznamy, revokační údaje a časová razítka.
- **Žádná práva správce za běhu.** Aplikace běží pod účtem uživatele; instalace podle volby instalátoru.

### 3.3 Předvídatelné nesprávné použití

| Situace | Dopad | Jak aplikace pomáhá |
|---|---|---|
| Spolehnutí na výsledek získaný s ústupkem v ověřování | kladný výsledek, který jiný ověřovatel nemusí potvrdit | ústupky platí jen pro jedno ověření a výsledek uvádí, zda o něm ústupek rozhodl |
| Ověřování bez aktuálních důvěryhodných seznamů | zastaralý závěr o kvalifikovanosti | lišta se stářím seznamů; offline režim je ve výsledku vyznačen |
| Razítko od testovací autority | razítko, které nemá důvěryhodnost | aplikace kvalitu autority zjišťuje a texty ji uvádějí |
| Otevření přílohy nebo programu z kontejneru | spuštění škodlivého obsahu mimo AnEP | otevření vždy jen na pokyn uživatele; u programů ukáže podpis kódu |
| Ověřovací výstup vydávaný za výstup kvalifikované služby | nepřiměřená váha výstupu | texty a politiky ověřování a uchovávání to výslovně vylučují |

## 4 Architektura

### 4.1 Vrstvy

Aplikace má dvě vrstvy. Spodní je knihovna **DSS** Evropské komise (referenční implementace ověřovacího procesu ETSI EN 319 102-1): rozbor formátů, sestavení certifikačních cest, ověřovací proces a protokoly. Horní vrstva je vlastní kód AnEP: rozhodnutí, co výsledek znamená, výklad v češtině, zdroje důvěry a revokace, úkony nad dokumentem a uživatelské rozhraní v JavaFX.

| Část | Úloha |
|---|---|
| Načtení dokumentu | soubor se zabalí pro DSS; DSS sám rozpozná formát |
| Ověření (DSS) | ověřovací politika z `policy/constraint.xml` s případnými ústupky pro jedno ověření |
| Zdroje důvěry | důvěryhodné seznamy EU (LOTL a národní), úložiště kořenů Windows, vlastní kotvy uživatele |
| Zdroje revokace | v pořadí: údaje v dokumentu, online OCSP a CRL, úložiště vlastních seznamů CRL |
| Výklad | převod výsledku DSS na důvody a doporučení |
| Úkony | uchování (B-LTA), přerazítkování, prodloužení, razítko dokumentu, forenzní balíček; vždy nový soubor, po úkonu nové ověření a záznam o úkonu |
| Inspektory | struktura kontejnerů, datových zpráv, příloh PDF, struktury podpisu |
| Rozhraní | šest záložek s výsledkem, protokoly ve formátu PDF/A-2b |

Podrobný popis stavby je v `docs/architecture.md` v repozitáři.

### 4.2 Datové toky

1. Uživatel otevře soubor (výběrem, přetažením, ze seznamu posledních souborů nebo z prohlížeče kontejnerů).
2. Aplikace ho přečte do paměti a předá DSS; soubor se nemění.
3. DSS si podle potřeby vyžádá revokační údaje a certifikáty vydavatelů (spojení K2 až K4 Přehledu online komunikace).
4. Výsledek se zobrazí; uložit ho lze jen na pokyn uživatele (protokol PDF/A, XML).
5. Úkon nad dokumentem si vyžádá časové razítko (K6) a výsledný soubor zapíše tam, kam určí uživatel.

Nezávisle na tom aplikace udržuje důvěryhodné seznamy (K1). Jiné toky dat ven nejsou; ověřovaný dokument ani jeho obsah se nikam neodesílá.

### 4.3 Místně uložená data

Vše uživatelské leží ve složce `%APPDATA%\SignatureAnalyzer` (na Linuxu `$XDG_CONFIG_HOME` nebo `~/.config`):

| Soubor nebo složka | Obsah | Citlivost |
|---|---|---|
| `settings.properties` | nastavení, zdroje časových razítek **včetně jmen a hesel k autoritám a hesla ke klientskému certifikátu, v čitelné podobě** | vysoká, pokud jsou hesla zadána |
| `recent-files.properties` | cesty k naposledy otevřeným souborům | střední: jména souborů mohou prozradit obsah práce |
| `custom-crls` | seznamy CRL, které uživatel vložil | nízká (veřejné údaje) |
| `trusted-certs` | vlastní kotvy důvěry uživatele | **integritně citlivá**: co je zde, tomu aplikace důvěřuje |
| `tl-cache`, `trust-state-v2.bin` | mezipaměť důvěryhodných seznamů | integritně citlivá (viz riziko R3) |

Mimo tuto složku aplikace zapisuje jen soubory, které uživatel výslovně uloží. Klientský certifikát k autoritě razítek zůstává tam, kde ho uživatel má; aplikace si pamatuje cestu k němu.

## 5 Rozhraní a prostor útoku

### 5.1 Vstupy

| Vstup | Původ | Ochrana |
|---|---|---|
| Ověřované soubory (PDF, ZIP/ASiC, XML, ASN.1/CMS, JSON, ZFO) | nedůvěryhodný | rozbor knihovnami DSS, PDFBox, Bouncy Castle a Santuario; zpracování XML s vypnutým načítáním vnějších entit; v inspektoru XAdES Santuario v režimu bezpečného ověřování a rozklad odkazů jen na soubory kontejneru nebo předložený dokument |
| Odpovědi OCSP, seznamy CRL, certifikáty AIA | síť, nedůvěryhodný kanál | platnost se posuzuje podle podpisu vydavatele a ověřovací politiky, ne podle kanálu |
| Důvěryhodné seznamy | síť | ověření pečeti seznamu; u LOTL viz riziko R3 |
| Časová razítka | síť | ověření podpisu a certifikátu autority po přijetí |
| Kontrola verze | síť | čte se jen číslo a datum podle pevného vzoru; nic se nestahuje ani nespouští |
| Nastavení a data v `%APPDATA%` | místní, pod účtem uživatele | ochrana operačním systémem |

### 5.2 Co aplikace nemá

- žádný naslouchající síťový port a žádné vzdálené ovládání,
- žádné automatické stahování ani spouštění kódu, žádné zásuvné moduly,
- žádné spouštění skriptů ani maker z dokumentů; zobrazení protokolu DSS (JavaFX WebView) načítá jen obsah přibalený v aplikaci,
- žádnou telemetrii.

### 5.3 Spojení podle údajů z dokumentu

Adresy respondérů OCSP, distribučních míst CRL a vydavatelů certifikátů jsou zapsány v certifikátech, a ty pocházejí z ověřovaného dokumentu. Upravený dokument tedy může přimět aplikaci, aby se spojila na adresu, kterou zvolil jeho autor — například aby se dozvěděl, kdy a odkud byl dokument otevřen, nebo aby aplikace oslovila adresu ve vnitřní síti. Odpověď se přitom posuzuje kryptograficky a nemůže výsledek ověření zvrátit ve prospěch útočníka; zůstává ale únik informace o otevření a odchozí požadavek, který si nevyžádal uživatel. Zmírnění: pravidla odchozího provozu (kapitola 10, § 18) a riziko R5.

## 6 Bezpečnostní vlastnosti podle přílohy I části I

| Požadavek | Stav v AnEP |
|---|---|
| a) bez známých zneužitelných zranitelností | závislosti nejsou soustavně prověřovány proti databázím zranitelností; viz kapitola 11 |
| b) bezpečné výchozí nastavení | ověřuje se přísně podle norem; ústupky jen na vyžádání a pro jedno ověření; žádná autorita razítek předem nastavená; kontrola verze jen na stisk |
| c) bezpečnostní aktualizace | nové verze vydává autor; automatické aktualizace aplikace nemá, upozornění na novou verzi jen na vyžádání; viz kapitola 8 |
| d) ochrana před neoprávněným přístupem | aplikace nemá účty; spoléhá na řízení přístupu operačního systému |
| e) důvěrnost údajů | dokumenty neopouštějí počítač; spojení s důvěryhodnými seznamy a kontrola verze přes HTTPS; slabina: hesla k autoritám razítek uložená v čitelné podobě (R6) a přenášená nešifrovaně při adrese `http://` |
| f) integrita údajů | ověřovaný soubor se nikdy nemění; úkony zapisují nový soubor a výsledek hned ověří; záznam o úkonu nese otisky SHA-256 obou souborů |
| g) minimalizace údajů | ven odchází jen otisk dat (razítko) a identifikátory certifikátů (OCSP); žádné osobní údaje uživatele |
| h) dostupnost | práce bez sítě; mezipaměť seznamů; časové limity spojení; údržba seznamů ustupuje práci uživatele |
| i) dopad na jiné služby | stahování seznamů nejvýš jednou za 12 hodin pro každý seznam |
| j) omezení prostoru útoku | bez naslouchajících portů a zásuvných modulů; přibalené běhové prostředí ale obsahuje i moduly pro ladění a vzdálenou správu JVM, které aplikace nepotřebuje (R8) |
| k) zmírnění dopadu incidentu | běh pod účtem uživatele bez zvýšených práv |
| l) záznamy o činnosti | aplikace bezpečnostní události nezaznamenává; o úkonech nad dokumentem vzniká záznam o úkonu, který lze uložit jako PDF/A |
| m) odstranění dat | odstraněním složky `%APPDATA%\SignatureAnalyzer` (kapitola 9.3) |

## 7 Kryptografie

- **Ověřování podpisů:** kryptografická omezení výchozí politiky DSS 6.5.1 podle ETSI TS 119 312 (algoritmy, délky klíčů a data jejich zastarání); podrobně Politika ověřování elektronických podpisů AnEP.
- **Časová razítka:** otisková funkce podle nastavení zdroje, výchozí SHA-512; aplikace zjišťuje, které funkce autorita přijímá.
- **Záznamy o úkonu a forenzní balíček:** otisky SHA-256.
- **Přenos:** TLS podle přibaleného běhového prostředí Javy. Pro HTTPS spojení knihovny DSS se vedle kořenů Javy důvěřuje úložišti kořenů Windows, souboru `%USERPROFILE%\.jdk-cacerts` a kořenům tuzemských autorit přibaleným v aplikaci.
- **Podpis instalátoru:** Authenticode se SHA-256 a časovým razítkem.

Doporučení NÚKIB k minimálním kryptografickým požadavkům se týkají hlavně kryptografie, kterou povinná osoba sama používá (§ 25 vyhlášky); AnEP kryptografii k ochraně dat organizace nepoužívá, jen ji ověřuje.

## 8 Aktualizace a řízení zranitelností

### 8.1 Vydávání verzí

Nové verze vydává autor na stránce aplikace s podepsaným instalátorem a soupisem změn. Aplikace se sama neaktualizuje; na stisk odkazu v okně O aplikaci zjistí, zda vyšla novější verze, a nabídne stránku ke stažení. Instalátor spuštěný na počítači s touž verzí to ohlásí.

Bezpečnostní opravy nejsou dosud vydávány odděleně od funkčních změn a nejsou zvlášť označovány.

### 8.2 Komponenty třetích stran

Aplikace přibaluje knihovny uvedené v kapitole 2 a jejich závislosti a běhové prostředí Javy. Soupis licencí vzniká při sestavení (`packaging/collect-licenses.py`, soubor NOTICE.txt). **Softwarový kusovník (SBOM) ve strojově čitelném formátu dosud nevzniká.**

### 8.3 Hlášení zranitelností

Zranitelnost lze nahlásit autorovi na adresu jiri@peterka.cz. Politika koordinovaného zveřejňování zranitelností dosud stanovena není.

### 8.4 Doba podpory

Doba podpory, po kterou autor zajistí opravy zranitelností, **dosud stanovena není**. Akt o kybernetické odolnosti u produktů dodávaných na trh předpokládá zpravidla nejméně pět let (čl. 13 odst. 8, bod 60 odůvodnění). Povinná osoba by ji měla s autorem dohodnout nebo ji při posouzení rizik zohlednit jako nejistotu.

### 8.5 Testování

Součástí repozitáře je přes sto tříd automatických testů (JUnit), které se spouštějí při sestavení. Dopad změn ověřování se měří sondami na sbírkách skutečných i umělých dokumentů. Bezpečnostní testování (fuzzing vstupů, penetrační test) dosud neproběhlo.

## 9 Pokyny pro bezpečné používání

### 9.1 Instalace

1. Instalátor stahujte jen ze stránky https://bajecnysvet.cz/anep/, nebo si aplikaci sestavte ze zdrojového kódu podle kapitoly 2.1.
2. Před instalací ověřte digitální podpis instalátoru a otisk certifikátu (kapitola 2).
3. Na spravovaných stanicích instalujte centrálně a evidujte verzi a otisk SHA-256 instalátoru.

### 9.2 Provoz

- Při prvním spuštění musí být dostupná síť, nebo se ověřuje jen kryptograficky (offline režim).
- Pravidla odchozího provozu, proxy a inspekci TLS nastavte podle Přehledu online komunikace (kapitola 4). Proxy se volí v Pokročilém nastavení (výchozí podle nastavení systému); proxy s přihlášením aplikace nepodporuje.
- Do `trusted-certs` vkládejte jen kotvy, jejichž původ jste ověřili: aplikace jim důvěřuje.
- Hesla k autoritám razítek zadávejte jen u adres `https://`; složku `%APPDATA%\SignatureAnalyzer` nesdílejte a nezálohujte na místa s širším přístupem.
- Přílohy a programy z kontejnerů otevírejte jen tehdy, když jim důvěřujete; AnEP je předává systému a další bezpečnost už neřídí.
- Ústupky v ověřování používejte vědomě; výsledek s ústupkem se nemusí shodovat s jinými ověřovateli.

### 9.3 Vyřazení z provozu

Odinstalujte aplikaci a odstraňte složku `%APPDATA%\SignatureAnalyzer` (nastavení včetně uložených hesel, seznam posledních souborů, vlastní kotvy a seznamy CRL, mezipaměť seznamů). Odinstalace ji neodstraní. Soubory, které uživatel uložil jinam (výsledky úkonů, protokoly, forenzní balíčky), zůstávají.

## 10 Podklady pro bezpečnostní opatření povinné osoby

Přiřazení k paragrafům vyhlášky č. 409/2025 Sb. Uvádí, co k opatření povinné osobě dává tento dokument a co musí zajistit sama.

| § vyhlášky | Opatření | Podklad z dokumentu | Úkol povinné osoby |
|---|---|---|---|
| § 7 | Řízení aktiv | identifikace, komponenty, místní data (kap. 2, 4.3) | zařadit AnEP do evidence aktiv, určit garanta a hodnotu aktiva |
| § 8 | Řízení rizik | rizika R1–R9 (kap. 11) | posoudit rizika v kontextu vlastní sítě a přijmout opatření |
| § 9 | Řízení dodavatelů | postavení autora a licence; veřejný zdrojový kód a možnost vlastního sestavení (kap. 1.3, 2, 2.1) | posoudit, zda je autor dodavatelem či významným dodavatelem; bez smluvního vztahu žádné smluvní záruky (podpora, hlášení incidentů) nejsou; rozhodnout, zda používat balíčky autora, nebo sestavovat sama |
| § 11 | Řízení změn | soupis změn ke každé verzi, Přehled online komunikace (kap. 6) | nové verze před nasazením vyzkoušet a schválit |
| § 12 | Akvizice, vývoj a údržba | bezpečnostní vlastnosti, kryptografie, aktualizace (kap. 6–8); zdrojový kód (kap. 2.1) | zohlednit chybějící dobu podpory a SBOM; požadavky uplatnit vůči autorovi, je-li smluvní vztah; při vlastním sestavení oddělit sestavovací prostředí a podepisovat výsledné balíčky |
| § 13, § 20 | Řízení přístupu a oprávnění | aplikace nemá účty (kap. 3.2) | přístup řídit účty operačního systému; omezit, kdo smí měnit `trusted-certs` |
| § 14 | Zvládání událostí a incidentů | kontakt na autora (kap. 8.3) | zapojit AnEP do vlastního postupu zvládání incidentů |
| § 18 | Bezpečnost komunikačních sítí | cíle, porty a protokoly, volba proxy (Přehled online komunikace, kap. 2 a 4) | povolit jen nezbytný odchozí provoz; nastavit proxy; rozhodnout o OCSP |
| § 22 | Zaznamenávání událostí | aplikace bezpečnostní události nezaznamenává (kap. 6, bod l) | zaznamenávat na úrovni operačního systému a síťových prvků (spuštění aplikace, odchozí spojení) |
| § 24 | Aplikační bezpečnost | prostor útoku (kap. 5), stav testování (kap. 8.5), zdrojový kód k revizi (kap. 2.1) | před nasazením a po významné změně provést test podle § 24 v rozsahu odpovídajícím aktivu; prověřit závislosti na známé zranitelnosti |
| § 25 | Kryptografické algoritmy | kap. 7 | autority razítek nastavovat přes HTTPS |
| § 26 | Zajišťování dostupnosti | provoz bez sítě (Přehled online komunikace, kap. 4.4) | zajistit dostupnost důvěryhodných seznamů (síť, případně přenos mezipaměti) |

## 11 Posouzení rizik

### 11.1 Rizika

| Č. | Hrozba nebo slabina | Dopad | Stávající opatření | Doporučení |
|---|---|---|---|---|
| R1 | Upravený dokument zneužije chybu v knihovně pro rozbor formátů (PDF, XML, ASN.1, ZIP) | spuštění kódu pod účtem uživatele, pád aplikace | rozbor zavedenými knihovnami; XML bez vnějších entit; běh bez zvýšených práv | udržovat knihovny aktuální; prověřovat závislosti na známé zranitelnosti; zvážit fuzzing |
| R2 | Známá zranitelnost v přibalené knihovně nebo běhovém prostředí | podle zranitelnosti | vydávání nových verzí | zavést SBOM a pravidelné prověřování závislostí; označovat bezpečnostní opravy |
| R3 | Podvržení LOTL: kotva pro ověření pečeti LOTL se bere z certifikátů obsažených v samotném staženém LOTL, nikoli z ohlášení v Úředním věstníku EU | kdo ovládne spojení s `ec.europa.eu` (např. kořenem v úložišti Windows nebo v `.jdk-cacerts`) nebo zapíše do mezipaměti, může podstrčit vlastní kotvy důvěry, a podvržený podpis pak vyjde jako kvalifikovaný | HTTPS; důvěra jen kořenům systému a přibaleným tuzemským kořenům | ukotvit LOTL na certifikáty ohlášené v Úředním věstníku (podpora v DSS existuje); chránit `%APPDATA%` před zápisem jinými účty |
| R4 | Úprava vlastních kotev nebo seznamů CRL v `%APPDATA%` jiným procesem pod týmž účtem | falešně kladný nebo záporný výsledek | ochrana operačním systémem | v síti justice zvážit správu `trusted-certs` jen správcem |
| R5 | Spojení na adresy uvedené v certifikátech dokumentu (kap. 5.3) | únik informace o otevření dokumentu; požadavek do vnitřní sítě | odpovědi se posuzují kryptograficky | pravidly odchozího provozu nepovolit spojení z aplikace do vnitřních sítí; zvážit volbu bez OCSP |
| R6 | Hesla k autoritám razítek a ke klientskému certifikátu uložená v čitelné podobě v `settings.properties`; heslo přenášené nešifrovaně při adrese `http://` | zneužití účtu u autority razítek | soubor v profilu uživatele | ukládat hesla chráněně (Windows DPAPI) a varovat před `http://` u autorit s heslem |
| R7 | Únik informací o ověřovaných podpisech vydavatelům přes OCSP | vydavatel ví, čí podpisy organizace kdy ověřovala | žádné | volba vypnout OCSP; revokace z vlastního úložiště CRL |
| R8 | Přibalené běhové prostředí obsahuje moduly, které aplikace nepotřebuje (ladicí agent JDWP, vzdálená správa JVM, překladač, jshell) | větší prostor útoku, pokud by je někdo zapnul parametry spuštění | moduly nejsou ve výchozím spuštění aktivní | omezit sadu modulů běhového prostředí na nezbytné |
| R9 | Síť s povinnou proxy nebo s inspekcí TLS | proxy s přihlášením aplikace nepodporuje (do verze 1.1.1 včetně nepoužívala proxy vůbec); kontrola verze za inspekcí TLS selže | volba proxy (od verze 1.2.0); provoz bez sítě; důvěra kořenům Windows u spojení DSS | doplnit přihlášení k proxy, je-li v síti vyžadováno; sjednotit důvěru pro kontrolu verze |
| R10 | Podvržený instalátor | spuštění cizího kódu | podepsaný instalátor; AnEP rozpozná oficiální sestavení | ověřovat podpis před instalací; centrální distribuce; případně vlastní sestavení ze značky vydání (kap. 2.1) |
| R11 | Chybějící závazek podpory | zranitelnost zůstane neopravena | veřejný zdrojový kód pod EUPL 1.2: opravu může provést a aplikaci sestavit i někdo jiný než autor (kap. 2.1) | stanovit dobu podpory a postup hlášení zranitelností |

### 11.2 Zbytková rizika

Po přijetí opatření v pravém sloupci zůstávají hlavně rizika spojená s rozborem nedůvěryhodných souborů (R1) a se závislostí na jediném autorovi (R11); tu zmírňuje veřejný zdrojový kód, který si organizace může sama prověřit, opravit a sestavit. Pro provoz v síti justice je nejvýznamnější riziko R3, protože se týká samotného základu důvěry, a riziko R9, které rozhoduje o tom, zda aplikace v síti s proxy dosáhne na aktuální data; po zavedení volby proxy zbývá z něj proxy s přihlášením.

## 12 Rozdíl oproti požadavkům aktu o kybernetické odolnosti

Pro případ, že by se AnEP stal produktem dodávaným na trh, chybí:

| Požadavek | Kde | Stav |
|---|---|---|
| Softwarový kusovník | příloha I část II bod 1 | chybí |
| Politika koordinovaného zveřejňování zranitelností a kontaktní místo pro jejich hlášení | příloha I část II body 5 a 6, příloha II bod 2 | je jen e-mailová adresa autora |
| Uveřejňování informací o opravených zranitelnostech a oddělené bezpečnostní aktualizace | příloha I část II body 2 a 4 | neděje se |
| Pravidelné bezpečnostní testy | příloha I část II bod 3 | neprobíhají |
| Doba podpory a datum jejího skončení | čl. 13 odst. 8, příloha II bod 7 | nestanoveno |
| Automatické bezpečnostní aktualizace s možností odkladu | příloha I část I písm. c) | aplikace jen na vyžádání oznámí novou verzi |
| Zaznamenávání bezpečnostně významné činnosti | příloha I část I písm. l) | chybí |
| Hlášení aktivně zneužívaných zranitelností a závažných incidentů | čl. 14 | postup nestanoven |
| EU prohlášení o shodě a označení CE | čl. 28, 30 | nevydáno (nyní není povinné) |

## 13 Související dokumenty

- Přehled online komunikace AnEP
- Politika ověřování elektronických podpisů AnEP
- Politika uchovávání elektronických podpisů AnEP
- Soupis změn (CHANGELOG.md)
- Popis architektury (`docs/architecture.md`)
