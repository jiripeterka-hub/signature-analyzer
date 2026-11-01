# Důkaz existence z obalu — návrh implementace

Stav: implementováno 1. 11. 2026 (kroky 1–4: ASiC, obalující podpisy, datové zprávy včetně razítka
podání, přílohy PDF). Zbývá vnoření obalů a odloučené podpisy (bod 6 a 7). Vnitřní podklad, ne
dokument pro čtenáře.

Poznámka k implementaci: razítko podání datové zprávy ověřené samostatně DSS označí jako
CONTENT_TIMESTAMP, a obsahové razítko best-signature-time nesnižuje. Při připsání do dat prvku se
proto po dobu přepočtu vede jako podpisové (`ValidatorFactory.recomputeWithEnvelopeTimestamp`).

## Oč jde

Prvek otevřený z prohlížeče obalu tlačítkem „Ověřit v AnEP“ (dokument v kontejneru ASiC, obsah
obalujícího podpisu, příloha datové zprávy, příloha PDF) se dnes ověřuje jako samostatný soubor.
Obal ale často nese platné časové razítko nad bajty, mezi nimiž prvek leží, a to razítko je
**důkazem existence** prvku k okamžiku svého vzniku. EN 319 102-1 V1.4.0 pro to má vstup
„time indication for signature existence“ (tab. 20), který předává aplikace; AnEP ho už
používá pro krytí pozdějším podpisem uvnitř téhož souboru (`ValidatorFactory`,
`recomputeWithCoveringTimestamp()`). Návrh rozšiřuje tentýž mechanismus na důkaz přicházející
z obalu.

**Zásada:** prvek dostane důkaz existence, když jeho bajty leží uvnitř dat, nad nimiž je
**platné časové razítko** — přímo, nebo přes otisk v manifestu či v podepsaných atributech.
Důkaz dává razítko, ne podpis: neplatný obalový podpis s platným razítkem důkaz dává, podpis
bez razítka žádný (deklarovaný čas podpisu důkazem není).

## Co se naměřilo (1. 11. 2026)

Kontejner ASiC-E s časovými razítky (razítko kontejneru I.CA z 30. 12. 2019, archivní razítko
PostSignum z 29. 11. 2024 přes `ASiCArchiveManifest`), uvnitř PDF s podpisem PAdES-BASELINE-LT
z 11/2014 (certifikát podepisujícího platný do 1/2015) a podpisovým razítkem RSA/SHA-1.
Sonda mimo repozitář, přepočet nad diagnostickými daty s výchozí politikou DSS:

| varianta | razítko PDF | podpis |
|---|---|---|
| PDF samostatně | INDETERMINATE / OUT_OF_BOUNDS_NO_POE | INDETERMINATE / OUT_OF_BOUNDS_NOT_REVOKED |
| s připsaným razítkem kontejneru | INDETERMINATE / CRYPTO_CONSTRAINTS_FAILURE_NO_POE | INDETERMINATE |
| SHA-1 přijato, bez důkazu | INDETERMINATE / OUT_OF_BOUNDS_NO_POE | INDETERMINATE |
| s připsaným razítkem kontejneru + SHA-1 přijato | PASSED | TOTAL_PASSED (BST 11/2014) |

Řetěz důkazů funguje (razítko obalu → vnitřní razítko → podpis). Připsání razítka do
diagnostických dat dalo totéž co posun hodin, bez jeho vedlejších účinků.

### Sken sbírek (1. 11. 2026, sonda `EnvelopePoeScan`)

`D:\tmp` (241 souborů) a `D:\Downloads` (2 502 souborů); prvky s podpisem uvnitř obalů s platným
razítkem. Indikace DSS, přísně a se všemi čtyřmi ústupky; u změn i ústupek, který sám stačí.

| výsledek | prvků |
|---|---|
| závěr se změní už v přísné politice | 5 — z toho 4 přílohy datových zpráv (3 různé zprávy, razítko pečeti) a **1 falešně kladný** (viz níže) |
| závěr se změní jen s ústupkem | 19 — post-expirační zdroje 8 (z toho 1 spolu s novějším OCSP), SHA-1 4, novější OCSP 4 (+1), jen kombinace 2 |
| jen pod-indikace (OUT_OF_BOUNDS_NO_POE → REVOCATION_OUT_OF_BOUNDS_NO_POE) | 9 |
| beze změny (platné už samy, vadný řetěz certifikátů, důkaz pozdější než konec platnosti) | ostatní |

Krytí se našlo u všech druhů obalů: razítko kontejneru ASiC (otisk v manifestu), razítko pečeti
datové zprávy, razítko podpisu revize PDF s přílohou i DocTimeStamp. Část prvků je ve sbírce
vícekrát (kopie po uchování), čísla jsou tedy horní odhad různých dokumentů.

**Falešně kladný případ — nutná pojistka.** ASiC-S s razítkem kontejneru nad PDF s podpisem
úrovně B. Certifikát podepisujícího byl odvolán dopoledne téhož dne, podpis a razítko kontejneru
jsou až odpoledne. Přísně s důkazem z obalu vyšlo TOTAL_PASSED, se všemi ústupky REVOKED_NO_POE
(správně). Příčina: odvolání dokládá jen CRL vydaný po expiraci certifikátu, který přísná politika
DSS nepřijme; zbude vložený CRL z rána téhož dne se stavem GOOD a vůči důkazu z odpoledne obstojí.
Jde o uměle vyrobený vzorek (podepsáno záměrně po revokaci, s vloženým CRL z doby před ní).

**Měřeno dál (sonda `RevokedBeforeProofScan`, závěr AnEP z `ReportMapper`, přísně):** v `D:\tmp`
(250 souborů, 42 podpisů TOTAL_PASSED) a `D:\Downloads` (2 548 souborů, 631 podpisů TOTAL_PASSED)
je jediný podpis platný přes známé dřívější odvolání — týž umělý vzorek ve variantě LT s vlastním
razítkem. AnEP ho nezamlčí: indikuje situaci 20 („maskované odvolání“) a karta i záhlaví o odvolání
před podpisem červeně upozorní. U odvozeného času z krycího podpisu nebyl nalezen žádný případ.

**Důsledek pro implementaci:** novou pojistku nepsat. Přepočet s připsaným razítkem obalu vést přes
`ReportMapper` tak, aby se situace 20 posuzovala vůči okamžiku důkazu z obalu, a ověřit to testem
na popsaném vzorku (karta nesmí ukázat kladný závěr bez upozornění na odvolání).

## Návrh řešení

### 1. Jádro: `EnvelopeProof`

Nový záznam v `core/`, který putuje od prohlížeče k ověření:

- okamžik důkazu (čas razítka),
- druh a jméno obalu, cesta k prvku v něm (pro kartu),
- `Reports` obalu a id krycího razítka (a razítek, která drží v platnosti jeho, např. archivních),
- jak byla zjištěna vazba na bajty (otisk v manifestu, podepsaný obsah, otisk zprávy, revize PDF).

Předává se jen spolu s bajty, které prohlížeč z obalu vyjmul — vazbu na bajty tak není třeba
dokazovat znovu. Soubor otevřený samostatně důkaz nedostane.

**Přenos:** `ContainerView.setOpenInAnepHandler` dnes bere `BiConsumer<byte[], String>`;
rozšířit o `EnvelopeProof` (může být `null`). `MainView.verifyExtracted()` ho uloží k
`sigFile` a `analyze()` ho předá do `ValidatorFactory.validate(...)`. Při výběru jiného
souboru se zahodí.

### 2. Vyhledání krycího razítka — `EnvelopeProofFinder`

Obal se ověří přes DSS (`ValidatorFactory`, stejná politika a zdroje jako hlavní ověření) a
z jeho diagnostických dat se najde **nejstarší razítko s indikací PASSED**, které prvek kryje:

| obal | krytí | odkud vazba |
|---|---|---|
| ASiC-S/E s razítkem | razítko kontejneru | `XmlSignerData` (jméno + otisk) v kryté množině razítka |
| ASiC-S/E s podpisem | podpisové / archivní razítko podpisu | podpis kryje `XmlSignerData` prvku |
| CMS .p7m, XAdES/JAdES obalující | podpisové / archivní razítko | podepsaný obsah = prvek |
| ZFO — pečeť | razítko pečeti (čas stažení) | podepsaný obsah CMS zahrnuje přílohy |
| ZFO — `dmQTimestamp` | kvalifikované razítko podání | otisk zprávy (`dmHash`) zahrnuje přílohy; jen když `checkHash` souhlasí |
| příloha PDF | razítko podpisu nebo DocTimeStamp revize, v níž příloha už je | viz bod 5 |

Razítko, které samo neprošlo (INDETERMINATE, FAILED), důkaz nedává. Platnost razítka se bere
z ověření obalu, tedy včetně archivních razítek, která ho drží v platnosti.

### 3. Uplatnění v ověření prvku

Zobecnit `recomputeWithCoveringTimestamp()`: krycí razítko (a razítka, která drží jeho) se
dočasně připíše do diagnostických dat prvku — mezi nalezená razítka každého podpisu
(kategorie SIGNATURE) a ke každému vnitřnímu razítku (kategorie TIMESTAMP) — spolu s chybějícími
certifikáty a revokačními údaji z obalu, přepočte se a připsání se vrátí. Hodiny se neposouvají.

Výsledek nese `DerivedTimeResult` s příznakem původu „z obalu“, aby karta i Rozšířený přehled
věděly, odkud důkaz je. Výsledek bez důkazu zůstává k dispozici pro srovnání.

### 4. ZFO: ověření `dmQTimestamp`

`IsdsMessageInspector` razítko podání dnes jen čte (BouncyCastle) a porovnává otisk. Aby mohlo
sloužit jako důkaz, musí ho ověřit DSS (důvěra v TSA, platnost, revokace) jako odloučené razítko
nad otiskem zprávy (`DigestDocument`). Bez toho se použije jen razítko pečeti.

### 5. Přílohy PDF: revize

`PdfAttachmentInspector` neví, ve které revizi příloha přibyla. Pro každý podpis / DocTimeStamp
z ověření obalu se otevře podepsaná revize (bajty do konce jeho ByteRange) a zjistí se, zda v ní
příloha se shodným obsahem (otisk) už je — ve stromu EmbeddedFiles i v anotacích příloh. Kryjí ji
jen razítka revizí, které ji obsahují. Příloha přidaná pozdější inkrementální změnou je krytá
jen pozdějšími razítky.

### 6. Vnoření

ZFO → ASiC → PDF: důkaz se dědí dolů. `ContainerView.openNested` předá vnořenému prohlížeči
seznam důkazů obalů nad ním; při ověření prvku se použije nejstarší platný. Navíc důkaz vnějšího
obalu může pomoci platnosti razítka vnitřního obalu — to až ve druhém kroku.

### 7. Odloučené podpisy

Dokument k odloučenému podpisu se neotevírá z prohlížeče, ale ze slotu pro externí obsah.
Nabídnout na kartě odkaz „Ověřit podepsaný dokument s důkazem existence z podpisu“, je-li dokument
sám podepsaný. Až po bodech 1–3.

### 8. Výklad a dokumentace

- **Karta Analýzy:** výslovně, že závěr stojí na důkazu z obalu — který obal, které razítko,
  k jakému dni — a jak by prvek vyšel samostatně. Nové texty do `texty/` (poznámka, případně
  důvod), s doplněním do `ReasonTextsTest` / `SituationTextsTest`.
- **Rozšířený přehled:** krycí razítko obalu mezi důkazy existence, odlišené od vlastních razítek.
- **Digitální kontinuita:** u prvku, jehož platnost stojí na důkazu z obalu, upozornit, že
  uchovat je třeba celý obal — vyjmutý prvek důkaz ztrácí. Horizon počítat podle razítka obalu.
- **Evidence record** nad dokumentem (dnes nativně v DSS) vykládat stejným jazykem.
- `politika-overovani.md` (nový oddíl), přegenerovat; `CHANGELOG.md`.

## Postup

0. **Měření předem.** Sonda `EnvelopePoeScan` (`tools/`) nad `D:\tmp` a `D:\Downloads`: pro každý
   obal a každý vložený podepsaný prvek závěr samostatně a s důkazem z obalu, s ústupky i bez.
   Výstup: kolik prvků změní závěr, kolik jen pod-indikaci, u kterých druhů obalů. Na pozadí,
   po podsložkách. Podle výsledku upravit pořadí kroků.
1. Jádro (body 1–3) a ASiC s razítkem — jednotkové testy vyhledání krytí a přepočtu.
2. ASiC s podpisem, CMS/XAdES/JAdES obalující.
3. ZFO (razítko pečeti, pak `dmQTimestamp`).
4. Přílohy PDF.
5. Vnoření, odloučené podpisy.
6. Výklad, kontinuita, dokumentace — průběžně s každým krokem, ne až na konec.

Po každém kroku sken sbírek před a po (žádný prvek otevřený samostatně se nesmí změnit).

## K rozhodnutí

1. **Automaticky, nebo na vyžádání?** Návrh: při „Ověřit v AnEP“ automaticky; karta uvede
   i samostatný závěr.
2. **Neplatný obalový podpis s platným razítkem** — důkaz dává? Návrh: ano (dokládá ho razítko).
3. **Obalové razítko INDETERMINATE** — návrh: důkaz nedává, karta to uvede.
4. **`dmQTimestamp` u ZFO** — návrh: použít, je to nejstarší doklad o zprávě; vyžaduje bod 4.
5. **SHA-1:** mechanismus nic nezmírňuje; kde rozhoduje SHA-1 (jako v měřeném případě), platí
   dál stávající ústupek a karta ho uvede jako rozhodující.
