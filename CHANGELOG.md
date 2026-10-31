# Soupis změn AnEP

Změny ve verzích aplikace AnEP (Analyzátor elektronických podpisů). Slouží jako podklad
pro soupis změn ke každé nové vydané verzi.

## Připravovaná verze (změny oproti 1.0.0)

### Ověřování

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

### Instalační balíčky

- **Linuxová verze: položka v nabídce aplikací.** Přenosný archiv pro Linux nově obsahuje
  skript `pridat-do-nabidky.sh`. Po rozbalení stačí spustit `./AnEP/pridat-do-nabidky.sh`
  a AnEP se objeví v nabídce aplikací přihlášeného uživatele. Skript nepotřebuje sudo, nic
  nemaže ani neinstaluje a funguje na libovolné distribuci. Jen zapíše spouštěč pro místo, kde
  AnEP právě leží. Po přesunu adresáře AnEP je třeba skript spustit znovu. Odebrání položky
  skript sám vypíše (`rm ~/.local/share/applications/anep.desktop`).
