# Přehled online komunikace AnEP

**Analyzátor elektronických podpisů (AnEP) — s kým, kdy a proč se aplikace spojuje po síti**

Verze 1.1 (návrh) · stav ke dni 3. 11. 2026 · AnEP 1.2.0, knihovna DSS 6.5.1

## 1 Úvod

### 1.1 K čemu tento přehled je

Přehled vypočítává všechna spojení, která AnEP navazuje po síti: co je spouští, kam vedou, jakým protokolem a co se při nich odesílá. Je určen správcům sítě (pravidla pro odchozí provoz, proxy, inspekce TLS), bezpečnostním rolím povinné osoby podle zákona o kybernetické bezpečnosti a každému, kdo posuzuje, co se o práci s aplikací dozvědí třetí strany.

Přehled je sestaven z výčtu míst ve zdrojovém kódu, která síť používají, a tam, kde na tom záleží, z měření (oddíl 5). Doprovází Technickou a bezpečnostní dokumentaci AnEP, která na něj odkazuje.

### 1.2 Hlavní zásady v kostce

- **Dokument se nikam neodesílá.** Ověřovaný soubor, jeho obsah ani jeho jméno neopouštějí počítač. Ven odchází nejvýš otisk (hash) dat při žádosti o časové razítko a identifikátory certifikátů při dotazu na revokaci.
- **Aplikace nic nenaslouchá.** Neotevírá žádný port a nepřijímá spojení zvenčí.
- **Žádná telemetrie.** Aplikace neodesílá statistiky, hlášení o chybách ani údaje o používání.
- **Žádné automatické aktualizace.** Novou verzi si uživatel stahuje a instaluje sám; aplikace jen na vyžádání zjistí, zda vyšla.
- **Bez sítě aplikace pracuje dál.** Důvěryhodné seznamy se berou z místní mezipaměti, revokační údaje z dokumentu a z úložiště vlastních seznamů CRL; výsledek pak uvádí, že ověření proběhlo bez aktuálních dat.

## 2 Přehled spojení

### 2.1 Souhrnná tabulka

| Č. | Účel | Spouštěč | Cíl | Protokol | Co se odesílá |
|---|---|---|---|---|---|
| K1 | Důvěryhodné seznamy EU (LOTL a národní seznamy) | první spuštění; dále na pozadí, každý seznam nejvýš jednou za 12 hodin; ruční obnova v Nastavení | `ec.europa.eu` a adresy národních seznamů uvedené v LOTL (oddíl 4.1) | HTTPS, u jednoho národního seznamu HTTP | žádost GET bez údajů o dokumentu |
| K2 | Revokace certifikátu — OCSP | ověření dokumentu, pokud potřebný revokační údaj dokument nenese nebo nestačí; úkony uchování | respondér OCSP uvedený v certifikátu (rozšíření AIA) | obvykle HTTP, port 80; POST | otisky jména a klíče vydavatele a sériové číslo ověřovaného certifikátu |
| K3 | Revokace certifikátu — seznam CRL | totéž jako K2, když OCSP není k dispozici nebo nestačí; úkony uchování | distribuční místo CRL uvedené v certifikátu (rozšíření CDP) | obvykle HTTP, port 80; GET | žádost o celý seznam bez údajů o certifikátu |
| K4 | Certifikáty vydavatelů (AIA) | sestavení certifikačního řetězu, když ho dokument nenese celý | adresa vydavatele uvedená v certifikátu | obvykle HTTP, port 80; GET | žádost o certifikát vydavatele |
| K5 | Seznam CRL pro posouzení uchování | sestavení výsledku, když certifikátu v řetězu chybí revokační údaj a posuzuje se, zda jde ověřitelnost zachovat | distribuční místo CRL uvedené v certifikátu | obvykle HTTP, port 80; GET | žádost o celý seznam |
| K6 | Časové razítko | jen na pokyn uživatele: uchování, přerazítkování, prodloužení, razítko dokumentu, forenzní balíček a zkouška zdroje ve Správě zdrojů razítek | autorita časových razítek, kterou uživatel nastavil | HTTP nebo HTTPS podle nastavené adresy; POST (RFC 3161) | otisk dat, případně jméno a heslo nebo klientský certifikát |
| K7 | Kontrola nové verze | jen na stisk odkazu v okně O aplikaci | `https://www.bajecnysvet.cz/anep/verze.json` | HTTPS, port 443; GET | číslo verze aplikace v hlavičce User-Agent |
| K8 | Odkazy v textech aplikace | jen na klepnutí uživatele | stránka odkazu (předpisy, vydavatelé, stránka aplikace) | otevírá ji výchozí prohlížeč systému, ne AnEP | nic z aplikace |

Spojení K2 až K4 vyvolává knihovna DSS v průběhu ověření; které z nich proběhnou, záleží na tom, co dokument nese. Po každém úkonu, který dokument mění (K6), se výsledný soubor hned ověří, takže následují i spojení K2 až K4 k certifikátům autority časových razítek.

### 2.2 Co se z jednotlivých spojení dozví druhá strana

| Spojení | Druhá strana | Co se dozví |
|---|---|---|
| K1 | Evropská komise a správci národních seznamů | adresu, ze které se stahuje, a čas; nic o ověřovaných dokumentech |
| K2 | vydavatel ověřovaného certifikátu | **který certifikát se ověřuje** (tedy čí podpis nebo pečeť), kdy a z jaké adresy |
| K3, K5 | vydavatel certifikátu | jen to, že se z dané adresy stahuje jeho seznam; nikoli který certifikát |
| K4 | vydavatel certifikátu | že se z dané adresy stahuje certifikát vydavatele |
| K6 | autorita časových razítek | otisk dat, který razítkuje (z něj obsah dokumentu zjistit nelze), čas, adresu, případně přihlašovací údaje |
| K7 | provozovatel stránky aplikace | že se z dané adresy ptá aplikace AnEP v uvedené verzi |

Z pohledu ochrany informací je nejcitlivější K2: posloupnost dotazů OCSP prozrazuje vydavatelům, čí podpisy organizace v daném čase ověřovala. Kdo to nechce, může revokační údaje doplňovat z úložiště vlastních seznamů CRL a provoz OCSP pravidly sítě nepovolit; ověření pak stojí na seznamech CRL (K3), které nic takového neprozrazují.

## 3 Jednotlivá spojení

### 3.1 K1 — důvěryhodné seznamy EU

Seznam důvěryhodných seznamů EU (LOTL) a národní důvěryhodné seznamy jsou zdrojem kotev důvěry: podle nich se rozhoduje, zda je certifikát kvalifikovaný a zda služba, která ho vydala, měla v rozhodném okamžiku kvalifikovaný status.

- **První spuštění:** aplikace seznamy stáhne a čeká na ně; jde o desítky souborů v řádu desítek megabajtů. Bez sítě se ověřuje v offline režimu (jen kryptografická kontrola) a výsledek to uvádí.
- **Další spuštění:** seznamy se načtou z místní mezipaměti bez sítě. Na pozadí se pak ověří jejich čerstvost: každý národní seznam nejvýš jednou za 12 hodin od jeho posledního potvrzení, a jsou-li potvrzené všechny, nestahuje se ani LOTL. Kontrola se pozastaví, kdykoli uživatel ověřuje. Najde-li se novější verze některého seznamu, stáhnou se znovu.
- **Ruční obnova:** tlačítkem v Nastavení.
- **Mezipaměť:** `%APPDATA%\SignatureAnalyzer\tl-cache` (od 16. 10. 2026; dříve `%USERPROFILE%\.signature-analyzer\tl-cache`) a zhuštěný stav `trust-state-v2.bin`.

Pravost seznamů se ověřuje jejich elektronickou pečetí: národní seznamy proti certifikátům ohlášeným v LOTL, LOTL proti certifikátům, které nese ve svém vlastním podpisu. Pravost samotného LOTL proto stojí na spojení HTTPS s `ec.europa.eu` (viz Technická a bezpečnostní dokumentace, oddíl o rizicích).

### 3.2 K2 až K4 — revokace a certifikáty vydavatelů

Revokační údaje se hledají v tomto pořadí: nejprve v dokumentu samém, pak online (OCSP, a když OCSP není nebo nestačí, seznam CRL), nakonec v úložišti vlastních seznamů CRL uživatele. Adresy respondérů OCSP, distribučních míst CRL a vydavatelů jsou zapsány v certifikátech — **pocházejí tedy z ověřovaného dokumentu**, nikoli z nastavení aplikace. Okruh serverů, s nimiž se aplikace spojí, proto nelze předem vyčíslit: jsou to servery vydavatelů certifikátů, jejichž podpisy se ověřují.

Totéž platí pro ověření podpisu programu (`.exe`, `.msi`) v prohlížeči kontejnerů: revokace certifikátů podpisu kódu se zjišťuje stejnými spojeními, u nich napřed ze seznamu CRL.

### 3.3 K5 — seznam CRL pro posouzení uchování

Při sestavování výsledku aplikace posuzuje, zda půjde ověřitelnost dokumentu zachovat. Chybí-li některému certifikátu v řetězu revokační údaj (typicky certifikátu certifikační autority, u něhož ho DSS při ověření nehledá), pokusí se stáhnout jeho seznam CRL z distribučního místa v certifikátu. Spojení má časový limit 8 s na navázání a 12 s na čtení a hlavičku `User-Agent: AnEP/1.0 CRL-Check`.

### 3.4 K6 — časová razítka

Žádost o časové razítko obsahuje otisk razítkovaných dat (podle nastavení SHA-512, SHA-384 nebo SHA-256), nikoli data sama. Aplikace žádnou autoritu předem nastavenou nemá; uživatel si ji přidá ve Správě zdrojů razítek, kde může využít předvyplnění pro Sectigo Qualified (`http://timestamp.sectigo.com/qualified`) nebo pro testovací autoritu PostSignum DEMO.

Přihlášení k autoritě je trojí: žádné, jménem a heslem (HTTP Basic, posílá se rovnou s první žádostí), nebo klientským certifikátem v TLS. **Je-li adresa autority zadána jako `http://`, putuje jméno i heslo nešifrovaně.** Autority bez přihlášení obvykle pracují přes HTTP; obsah odpovědi (razítko) je chráněn jejím podpisem.

Nově přidaný zdroj aplikace vyzkouší: vyžádá si zkušební razítko a zjistí, které otiskové funkce autorita přijímá a zda je její razítko kvalifikované. I to jsou spojení K6.

### 3.5 K7 — kontrola nové verze

Kontrola běží **jen na stisk odkazu v okně O aplikaci**, nikdy sama při spuštění. Aplikace stáhne malý soubor s číslem a datem poslední vydané verze a nanejvýš nabídne stránku ke stažení; sama nic nestahuje ani neinstaluje. Žádost nese hlavičky `User-Agent: AnEP/<verze>` a `Cache-Control: no-cache`, časový limit je 10 s.

### 3.6 K8 — odkazy

Odkazy v textech aplikace (znění předpisů, stránky vydavatelů certifikátů, stránka aplikace, návod ke zdrojům razítek) otevírá výchozí prohlížeč systému, a to jen na klepnutí. Aplikace sama tyto stránky nenačítá.

### 3.7 Co síť nepoužívá

- **Zobrazení protokolu DSS** (záložka Protokoly DSS) — od verze 1.2.0 bez sítě, viz kapitola 6.
- **Inspektor struktury podpisu** — přepočet otisků u XAdES rozkládá jen odkazy na soubory kontejneru nebo na předložený oddělený dokument; na adresy uvedené v dokumentu se nespojuje.
- **Rozbor dokumentu** — PDF, kontejnery, datové zprávy, přílohy a odkazy eLegalizace se čtou jen z místních souborů.
- **Zahřátí aplikace po spuštění** — zkušební ověření přibaleného vzorku probíhá bez online zdrojů.
- **Protokoly ve formátu PDF/A, forenzní balíček, záznam o úkonu** — vznikají na místě (forenzní balíček ovšem obsahuje razítko a revokační údaje získané spojeními K2 až K6).

## 4 Požadavky na síť

### 4.1 Odchozí provoz, který je třeba povolit

| Spojení | Cíl | Port | Nutnost |
|---|---|---|---|
| K1 | `ec.europa.eu` | 443 | nutné pro aktuální důvěryhodné seznamy |
| K1 | servery národních seznamů (výčet níže) | 443, u Slovenska 80 | nutné pro aktuální důvěryhodné seznamy |
| K2–K5 | servery vydavatelů certifikátů (předem nevyčíslitelné) | 80, výjimečně 443 | nutné pro ověření revokace online; jinak jen z dokumentu a vlastního úložiště |
| K6 | nastavená autorita časových razítek | 80 nebo 443, případně jiný podle adresy | jen pro úkony uchování a forenzní balíček |
| K7 | `www.bajecnysvet.cz` | 443 | volitelné |

Národní seznamy podle LOTL k 3. 11. 2026 (zjištěno z mezipaměti; LOTL může adresy kdykoli změnit): `tl.nbu.gov.sk` (HTTP), `crc.bg`, `dec.dmrid.gov.cy`, `dp.trustedlist.fi`, `eidas.agid.gov.it`, `eidas.gov.ie`, `gov.hr`, `messervices.cyber.gouv.fr`, `nkom.no`, `rrt.lt`, `sr.riik.ee`, `tl.bundesnetzagentur.de`, `trusted-list.lu`, `trustedlist.adr.gov.ro`, `trustedlist.pts.se`, `trustlist.gov.lv`, `tsl.belgium.be`, `tsl.digital.gob.es`, `tsl.fjarskiptastofa.is`, `tsl.gov.cz`, `tsl.mca.org.mt`, `www.digst.dk`, `www.eett.gr`, `www.gns.gov.pt`, `www.llv.li`, `www.nccert.pl`, `www.nmhh.hu`, `www.rdi.nl`, `www.signatur.rtr.at`, `www.tl.gov.si`.

Pravidla podle jmen serverů nestačí pro K2 až K5: kdo chce ověřovat revokaci online, musí povolit odchozí HTTP k vydavatelům obecně, nebo se smířit s tím, že revokační údaje budou jen z dokumentu a z úložiště vlastních seznamů CRL.

### 4.2 Proxy

Od verze 1.2.0 se všechna spojení K1 až K7 řídí jednou volbou v Pokročilém nastavení, oddíl „Připojení k internetu (proxy)“:

| Volba | Chování |
|---|---|
| Podle nastavení systému (výchozí) | proxy podle nastavení Internetu ve Windows; kde žádná není, přímo |
| Zadaná proxy | zadaný protokol (HTTP nebo SOCKS), adresa a port; servery ve výjimkách (např. `*.justice.cz`) přímo |
| Bez proxy | vždy přímo |

Spojení HTTPS jdou přes proxy tunelem (metoda CONNECT), takže proxy nevidí jejich obsah; spojení HTTP (OCSP, CRL, většina autorit razítek) vidí celé. Spojení na `localhost` jdou vždy přímo. Změna volby platí hned po uložení, i pro spojení, která už aplikace má otevřená. Volba se ukládá do `settings.properties` (klíče `proxy.mode`, `proxy.protocol`, `proxy.host`, `proxy.port`, `proxy.exclusions`), takže ji lze na spravovaných stanicích rozdat i souborem.

**Omezení.** Proxy, která vyžaduje přihlášení (HTTP 407, včetně integrovaného přihlášení Windows), aplikace nepodporuje. U volby podle nastavení systému je ověřena pevně zadaná proxy; zda se uplatní i automatická konfigurace (skript PAC, WPAD), závisí na běhovém prostředí Javy a ověřeno není — kde se v síti používá, doporučuje se zadat proxy ručně.

**Protokol SOCKS.** Spojení knihovny DSS (K1 až K4 a K6) jdou přes zadanou proxy SOCKS, jména serverů ale překládá tento počítač, ne proxy; v síti bez vlastního překladu jmen tato spojení selžou. Kontrola verze (K7) SOCKS nepoužije a jde přímo.

Do verze 1.1.1 včetně knihovna DSS, přes kterou běží spojení K1 až K4 a K6, proxy nepoužívala vůbec, ani systémovou, ani zadanou vlastnostmi Javy (měření v oddílu 5.1); v síti s povinnou proxy se aplikace chovala jako bez sítě. Ve verzi 1.2.0 se spojení na HTTP proxy, i zadanou, vydávalo přes SOCKS, pokud ho systém uváděl, a volba „Bez proxy“ ho neobcházela; v takové síti spojení končila vypršením času. Od následující verze jde spojení na HTTP proxy přímo.

### 4.3 Inspekce TLS

Spojení K1 až K4 a K6 důvěřují pro HTTPS vedle kořenů běhového prostředí Javy také úložišti kořenových certifikátů Windows (`Windows-ROOT`), souboru `%USERPROFILE%\.jdk-cacerts`, existuje-li, a kořenům tuzemských certifikačních autorit přibaleným v aplikaci. Kořen proxy s inspekcí TLS, rozšířený do úložiště Windows správou domény, proto tato spojení přijmou.

Spojení K7 (kontrola verze) a K5 používají jen kořeny běhového prostředí Javy. **Za inspekcí TLS kontrola verze selže** s chybou sestavení certifikační cesty; aplikace pak ohlásí, že zjistit novou verzi se nepodařilo. Spojení K5 jde obvykle přes HTTP a inspekce TLS se ho netýká.

### 4.4 Provoz bez sítě

AnEP lze provozovat i na počítači bez přístupu k internetu. Důvěryhodné seznamy se pak berou z mezipaměti, a pokud žádná není (první spuštění), ověřuje se jen kryptograficky a výsledek to uvádí. Stáří seznamů ukazuje lišta v okně aplikace. Revokační údaje lze dodávat ručně do úložiště vlastních seznamů CRL. Úkony uchování a forenzní balíček potřebují autoritu časových razítek, a tedy síť.

## 5 Měření

### 5.1 Proxy (3. 11. 2026)

Zkušební program na stejné třídní cestě jako aplikace (DSS 6.5.1, Apache HttpClient 5.6, JDK 25) stáhl `https://ec.europa.eu/tools/lotl/eu-lotl.xml` přes `CommonsDataLoader` knihovny DSS a `https://www.bajecnysvet.cz/anep/verze.json` přes `java.net.http.HttpClient`, jednou bez vlastností proxy a jednou s `https.proxyHost=127.0.0.1`, `https.proxyPort=9` (a totéž pro `http`), tedy s proxy, která neexistuje.

| Klient | Bez proxy | S nastavenou neexistující proxy | Závěr |
|---|---|---|---|
| DSS `CommonsDataLoader` | spojení se serverem navázáno | spojení se serverem navázáno, stejně jako bez proxy | vlastnosti proxy nepoužívá |
| `java.net.http.HttpClient` | spojení se serverem navázáno | `ConnectException` (pokus o spojení s proxy) | vlastnosti proxy používá |

Na měřicím počítači běží inspekce TLS antivirového programu, a oba klienti proto skončili v TLS chybou sestavení certifikační cesty — zkušební program na rozdíl od aplikace nepřidává k důvěryhodným kořenům úložiště Windows. Pro závěr o proxy to nevadí: rozhoduje, s kým se klient spojil. Týž výsledek u `HttpClient` zároveň dokládá, co oddíl 4.3 říká o kontrole verze za inspekcí TLS.

### 5.2 Proxy po úpravě (3. 11. 2026)

Automatické testy aplikace (`NetworkProxyTest`) spouštějí místní zkušební proxy a žádají o adresu na jménu `anep-proxy-test.invalid`, které nelze přeložit, takže odpověď může přijít jen přes proxy. Se zadanou proxy prošly přes ni žádosti zavaděče pro seznamy a CRL (GET), zavaděče OCSP a zavaděče razítek (POST) i klienta kontroly verze; zavaděč vytvořený před změnou volby se po ní řídil novou volbou. Pro server ve výjimkách a pro volbu bez proxy proxy žádnou žádost nedostala.

## 6 Změny v poslední době

| Kdy | Verze | Změna |
|---|---|---|
| 3. 11. 2026 | 1.2.0 | **Proxy.** Všechna spojení se řídí volbou proxy (výchozí podle nastavení systému, nebo zadaná, nebo žádná); dosud knihovna DSS chodila vždy přímo. |
| 3. 11. 2026 | 1.2.0 | **Protokol DSS bez přístupu k internetu.** Záložka Protokoly DSS si dosud při každém zobrazení stahovala styly a skripty (Bootstrap, jQuery) z `cdn.jsdelivr.net` a `code.jquery.com`; jejich provozovatelé se tak mohli dozvědět, kdy si kdo protokol prohlíží. Obě knihovny jsou nově součástí aplikace; spojení odpadá. |
| 1. 11. 2026 | 1.1.0 | **Kontrola nové verze** (K7) — nové spojení, jen na stisk odkazu v okně O aplikaci. |
| 30. 10. 2026 | 1.1.0 | **Ověření podpisu programů** v prohlížeči kontejnerů — revokace certifikátů podpisu kódu přes spojení K2 a K3, napřed seznam CRL. |
| 16. 10. 2026 | před 1.0.0 | **Mezipaměť důvěryhodných seznamů** přesunuta do `%APPDATA%\SignatureAnalyzer\tl-cache`; první spuštění po změně seznamy jednou stáhne znovu. |

Verze 1.0.0 (22. 10. 2026) je výchozím stavem: spojení K1 až K6 a K8 v ní byla již v podobě popsané výše.

## 7 Doporučení

1. **Proxy s přihlášením.** Nastavení proxy je od verze 1.2.0 k dispozici (oddíl 4.2); kde proxy vyžaduje přihlášení, je potřeba výjimka pro aplikaci, nebo doplnit přihlášení k proxy.
2. **Kontrola verze za inspekcí TLS.** Sjednotit důvěru pro HTTPS se spojeními DSS (přidat úložiště Windows), aby kontrola verze fungovala i za inspekcí TLS.
3. **Hesla k autoritám razítek.** Upozornit uživatele, je-li u autority s přihlášením jménem a heslem zadána adresa `http://`, protože heslo pak putuje nešifrovaně.
4. **Volba bez OCSP.** Zvážit nastavení, které vypne dotazy OCSP (K2), pro pracoviště, která nechtějí vydavatelům prozrazovat, čí podpisy ověřují.
5. **Hlavička K5.** Hlavička `User-Agent` spojení K5 uvádí pevně verzi 1.0; sjednotit se skutečnou verzí jako u K7.
