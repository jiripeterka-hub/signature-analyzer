# Náhled obsahu dokumentu — návrh

> ## Stav k 18. 9. 2026
>
> **Návrh, nic z toho není v aplikaci.** Čísla o výkonu a paměti jsou **odhady**, ne měření —
> u každého je to napsáno. Před prvním řádkem kódu se mají změřit na `D:\tmp` (214 dokumentů).
>
> **První oprava** (po dotazu „v čem může spočívat rozdíl"): přepínač podob stavěl jen nad
> **podpisy**, ačkoli po uchování je poslední krytá revize ta **dokumentového časového razítka**.
> Nad každým uchovaným dokumentem by tedy hlásil rozdíl, který tam není. Přibyl oddíl 3.
>
> **Druhá oprava** (rozhodnutí uživatele ke čtyřem otevřeným otázkám): náhled **není záložka**,
> nýbrž tlačítko v záhlaví a vlastní okno; přepínač nese jen **různé verze**, ne každou revizi;
> **vyhledávání ano**; u porušeného podpisu se podoby **neukazují**.
>
> **Otevřené otázky žádné nezbývají** — okno je jedno a nové ověření je zavře. Celý výčet
> rozhodnutí i s odkazy do textu je v oddílu 10.
>
> **Změřeno 18. 9. 2026 na `D:\tmp` (214 PDF).** Výsledky jsou v oddílu 5.1 a jeden z nich návrh
> opravil: **překryv anotací není rozdíl mezi revizemi** a do testu „změnil se obsah" nepatří.

Aplikace dnes o dokumentu všechno napíše, ale nikdy ho neukáže. Uživatel vidí verdikt, jméno
podepsaného a data — a musí věřit, že mluví o tom souboru, který má na mysli. Náhled tohle mění.

Podstatné ale není pohodlí: **náhled je jediný způsob, jak ukázat rozdíl mezi tím, co se
podepsalo, a tím, co v souboru je dnes.** O tom aplikace dnes mluví čísly („vizuální rozdíly —
stránky 3, 7") a čtenář si to nemá jak ověřit očima. Tomu jsou věnovány oddíly 3 až 5 a je to ta
část návrhu, kvůli které za to celé stojí.

---

## 1. Výchozí stav: co už je hotové

Náhled nezačíná na zelené louce. Vykreslování stránek v aplikaci **už je** — používá se na výřezy
vizualizací podpisů.

| co | kde | poznámka |
|---|---|---|
| vykreslení stránky do rastru | `PdfSignatureAppearanceExtractor.renderCrop()` | `PDFRenderer.renderImage(pageIndex, scale)`, pak výřez |
| rotace stránky, CropBox × MediaBox | tamtéž | past, na kterou se už jednou narazilo; hotové |
| heslem chráněná PDF | `PdfSource.open(char[])` | i s rozlišením uživatelského a vlastnického hesla |
| soubor × bajty v paměti | `PdfSource` | jedno místo, které o tom rozhoduje |
| rastr do JavaFX | `new Image(new ByteArrayInputStream(png))` | už na třech místech; `javafx-swing` netřeba |
| vlastní okno nad pohledem | `MainView.detachTab()` | hotový vzor, jak pohled postavit do samostatné scény |
| práce na pozadí | `BackgroundWork` | |

**veraPDF k tomu nepomůže a nemá se o to zkoušet.** `validation-model-jakarta` je validátor shody
s PDF/A — umí říct, zda soubor vyhovuje profilu, a nic víc. API na vykreslení stránky nemá.
Vykresluje PDFBox, který v aplikaci je a který tohle dělá už dnes.

---

## 2. K čemu náhled je

Tři užití, každé s jinou vahou:

1. **Vidět, co vlastně ověřuji.** Nejlacinější a nejmenší.
2. **Vidět podpis v souvislosti stránky.** Vizualizaci podpisu aplikace ukazuje vyříznutou; kde na
   stránce je a co je kolem ní, neřekne. Totéž u prázdných podpisových polí, u kterých aplikace umí
   předpovědět vzhled, ale ne polohu v dokumentu.
3. **Vidět, co se změnilo po podpisu.** Viz oddíly 3 až 5.

---

## 3. Co se může lišit mezi dnešním souborem a podepsanou revizí

### 3.1 Rozdíl je „co přibylo za podpisem", ne „co se v něm změnilo"

**Přírůstková aktualizace podpis nerozbije.** Podepsaný prefix zůstává bajt po bajtu stejný a otisk
sedí dál; přesně k tomu přírůstkové aktualizace jsou. **Situace 1** je něco jiného a vzácnějšího —
zásah *dovnitř* krytého rozsahu, po kterém otisk nesedí.

Z toho plyne, komu je přepínač podob určen: **podpisům, které jsou platné**, a přesto dnešní soubor
může ukazovat něco jiného, než co se podepsalo. U porušeného podpisu už odpověď dala karta —
viz 4.9.

### 3.2 Co za podpisem přibývá legitimně

**Co tam přidává sama AnEP.** Slovník `/DSS` s validačními daty a záznamy `/VRI`, dokumentové
časové razítko, případně obojí — podle toho, zda šlo o uchování, prodloužení či přerazítkování.

**Co dovolí režim DocMDP.** Certifikační podpis nese `/P`: 1 nedovolí nic, 2 vyplňování formulářů
a podepisování, 3 navíc anotace; `/Lock` (FieldMDP) k tomu zamyká konkrétní pole. Obojí aplikace
čte a ukazuje v Detailním rozboru. Prakticky to znamená vyplnění dalšího formulářového pole,
komentář, razítko nebo druhý podpis.

### 3.3 Co mění, co dokument říká — a podpis přitom nerozbije

Tahle rodina je důvod, proč náhled stojí za práci:

- nový objekt se **stejným číslem** přepíše obsahový proud stránky (maskování obsahu);
- nový `/Pages` přidá nebo odebere stránku;
- anotace položená **přes** podepsaný text;
- změněná hodnota formulářového pole, takže týž formulář čte jinak;
- vyměněné `/Resources` nebo písmo, takže týž obsahový proud vykreslí jinak.

Právě na tohle má DSS tři seznamy stránek a čtyři kategorie objektových změn, které aplikace dnes
vypisuje jako čísla — viz oddíl 5.

### 3.4 Co nic neříká

Metadata `/Info` nebo XMP (datum poslední změny) a `/Perms /UR3`, které přidává Adobe Reader.
Ten poslední je zároveň ta past, kvůli které se `/ByteRange` nesmí hledat v bajtech (oddíl 4.4).

Úplné přeuložení dokumentu je jiný případ: přepíše soubor, rozsah přestane sedět a podpis padne
nebo se vůbec nenajde.

---

## 4. Kterou podobu ukázat

### 4.1 Proč je to vůbec otázka

PDF s přírůstkovými aktualizacemi je **několik dokumentů v jednom souboru**. Prvek kryje jednu
revizi — tu, kterou vymezuje jeho `/ByteRange` — a cokoli přibylo potom, kryté není.

Náhled, který vedle verdiktu o podpisu ukáže dnešní obsah souboru, tedy může ukazovat něco jiného,
než o čem ten verdikt mluví. Nad dokumentem, kde po podpisu někdo obsah zamaskoval (oddíl 3.3), by
náhled ukazoval to zamaskované — a byl by přesvědčivější než věta na kartě, protože je to obrázek.

**Náhled bez podob by tedy byl v téhle aplikaci horší než žádný.**

### 4.2 Kryjícím prvkem je podpis i dokumentové razítko

Kryjící prvek je každý, kdo má vlastní `/ByteRange`: **podpis i dokumentové časové razítko**. Po
uchování je posledním z nich právě razítko a kryje všechno před sebou — takže dnešní soubor se
s poslední krytou revizí shoduje bajt po bajtu. Výčet počítaný jen z podpisů by nad každým
uchovaným dokumentem hlásil rozdíl, který tam není.

DSS to unese: `TimestampWrapper.getPDFRevision()` vrací týž `PDFRevisionWrapper` jako u podpisu,
tedy i `getSignatureByteRange()`.

### 4.3 Jak se krytá revize získá

`/ByteRange` má tvar `[0, a, b, c]`: prvek kryje bajty `0..a` a `b..b+c`, a mezi `a` a `b` leží
jeho vlastní `/Contents` (hexadecimální podoba podpisu či razítka, kterou krýt nemůže).

**Revize končí na `b + c`.** Prefix souboru `bytes[0 .. b+c)` je proto úplné, samostatně načtitelné
PDF — obsahuje i `/Contents` a končí značkou konce té revize. Vykreslí se tedy tak, že se z bajtů
uřízne prefix a otevře se PDFBoxem jako každý jiný dokument.

**Ověřeno 18. 9. 2026 na vyrobeném vzorku** (oddíl 5.2): před zásahem hlásí `DocumentVersions`
jedinou podobu, po zásahu dvě — krytou revizi o 281 569 B a dnešní soubor o 306 379 B. Prefix se
otevře jako platné PDF o jedné straně. Do té chvíle to byl výklad `/ByteRange`, ne měření.

Není k tomu potřeba nic z PDFBoxu navíc a nepoužije se `PDSignature.getSignedContent()`: ta vrací
*podepsaný obsah* s vynechanou dírou po `/Contents`, tedy něco, co samo o sobě PDF není.

### 4.4 Odkud vzít `/ByteRange` — a odkud ne

**Z `DiagnosticData`, přes `PDFRevisionWrapper.getSignatureByteRange()`. Nikdy hledáním
`/ByteRange` v bajtech souboru.**

Je to past, na kterou aplikace už jednou narazila a má ji popsanou u `ReportMapper.signedByteRange()`
(řádek 5131): nad `test na opacne poradi signed.pdf` nese soubor **tři** takové rozsahy, ale jen dva
jsou podpisy — první patří položce `/Perms /UR3`, tedy podpisu užívacích práv (Reader-enabled), a
kryje 303 541 bajtů. Bajtové síto by z něj udělalo revizi, kterou nikdo nepodepsal.

`ReportMapper.signedByteRange()` je dnes soukromá a ptá se jen `SignatureWrapper`. Návrh: rozšířit
ji i na `TimestampWrapper` (rozhraní je totéž) a **rozsah uložit do modelu** — k podpisu do
`SignatureSummary`, k razítku do `TimestampEntry`, stejně jako se tam už ukládá vizualizace podpisu
a viditelnost pole. Model je to místo, kde tyhle údaje o PDF žijí; počítat je podruhé v pohledu
znamená riskovat, že se obě odpovědi rozejdou.

Vedle rozsahu se uloží i **`isSignatureByteRangeValid()`**. Kde rozsah neplatí, podoba se nenabízí:
prefix by nebyl tím, co prvek kryje, a náhled by tvrdil nepravdu právě tam, kde na tom nejvíc
záleží.

### 4.5 Šifrované dokumenty

Prefix šifrovaného PDF je šifrovaný stejně jako celek a otevře se **týmž heslem**, které uživatel
zadal na začátku. `PdfSource.open(char[])` to umí; heslo drží `MainView` a předává ho dál stejnou
cestou jako dnes vizualizacím.

### 4.6 Nabízejí se jen různé verze, ne každá revize

**Rozhodnutí uživatele z 18. 9. 2026.** Dokument s pěti podpisy a třemi razítky má osm krytých
revizí, ale verzí, které se od sebe pro čtenáře liší, bývá podstatně míň: uchování přidá validační
data a razítko, obsah se přitom nezmění o jediný pixel. Osm položek v přepínači by tedy bylo osm
odpovědí na otázku, která má dvě.

Pravidlo: **prvek dostane vlastní položku jen tehdy, když se obsah mezi jeho revizí a tou následující
opravdu liší.** Kde se změnilo jen to, co obsah nemění — rozšíření dokumentu, validační data,
razítko — se položky slévají do jedné, pojmenované podle **nejstaršího** prvku toho slitku: to je
ten, kdo tu podobu podepsal jako první.

Rozhoduje **vizuální a stránkový rozdíl z DSS**, nic jiného (změřeno — viz 5.1): prvek, po kterém
DSS nehlásí ani vizuální, ani stránkový rozdíl, vlastní verzi netvoří, ať už kategorie objektových
změn říkají cokoli.

#### Doplněno 22. 9. 2026: a prvek, který sám kreslí

**Nález uživatele:** dokument se čtyřmi podpisy, každý s vlastní vizualizací, nedostal nabídku
vůbec. Pravidlo výše je tím vinno právem: DSS porovnává stránky **se zakrytými podpisovými poli**,
takže nový podpis s razítkem přes půl stránky pro ně rozdíl není. Jenže právě tohle je otázka,
kvůli které se podoby nabízejí — jak dokument vypadal, než na něj ten podpis přibyl.

**Pravidlo se proto rozšiřuje o druhou podmínku:** vlastní položku dostane i prvek, jehož podpisové
pole je **na stránce vidět** (`PdfFieldVisibility.visible()`, údaj, který model nese už kvůli
kartě). Kdo nekreslí nic — dokumentové a archivní razítko, neviditelný podpis, validační data —,
se slévá dál, takže důvod původního rozhodnutí zůstává v platnosti.

**Změřeno na 181 vícepodpisových PDF** (`VersionMenuScan`, sítko podle `/SubFilter` nad 2 537
soubory ve třech sbírkách):

| pravidlo | položek celkem | dokumentů s nabídkou |
|---|---|---|
| dnes (rozdíl podle DSS) | 183 | 2 ze 181 |
| **dnes + vizualizace** | **318** | **100 ze 181** |
| položka za každý prvek | 549 | 181 ze 181 |

Nové pravidlo tedy otevře nabídku u **98 dokumentů, které ji dosud neměly**, typicky se dvěma až
třemi položkami (nejvíc šest). Varianta „položka za každý prvek" by proti němu přidala dalších 231
položek, a to je přesně to, co rozhodnutí z 18. 9. 2026 odmítlo.

Kategorie se k tomu původně měly použít taky a **měření to vyvrátilo**: na `D:\tmp` je 13 souborů,
kde DSS hlásí „vyplnění formulářů" nebo „změnu anotací", a přitom u nich nenajde jediný vizuální
rozdíl. Šlo by o 13 zbytečných položek v přepínači, každou s obsahem k nerozeznání od té vedlejší.

### 4.7 Jak se položky jmenují

Ne „revize 1, revize 2". Čtenář neví, co je revize, a ani vědět nemusí:

- **„Dnešní podoba souboru"** — výchozí, viz 4.8;
- **„Jak dokument vypadal, když jej podepsal Jan Novák (3. 8. 2024)"**;
- **„…když jej orazítkovala autorita PostSignum (3. 8. 2024, 10:14)"** — jen tam, kde razítko
  vlastní verzi opravdu tvoří (4.6).

Vše v pořadí, v jakém prvky vznikaly. Jméno a čas se berou z týchž údajů, které nese karta, takže
se s ní přepínač nemůže rozejít.

### 4.8 Výchozí je dnešní soubor — a vedle přepínače stojí, co ho od poslední revize dělí

Rozdíl se nemá schovávat do přepínače, nýbrž **napsat vedle něj**:

- „Poslední časové razítko kryje celý soubor; od podepsané podoby se neliší ničím." (uchovaný
  dokument)
- „Po posledním podpisu do souboru přibyla validační data a časové razítko. Obsah se nezměnil."
- „Po posledním podpisu se změnil obsah stránek 3 a 7." (oddíl 5)

Věta je to, co čtenář potřebuje; přepínač je to, čím si ji ověří.

### 4.9 U porušeného podpisu se podoby neukazují

**Rozhodnutí uživatele z 18. 9. 2026.** Tam, kde otisk krytého rozsahu nesedí (situace 1), není
„krytá revize" tím, co kdo podepsal, nýbrž tím, co po zásahu zbylo. Ukázat ji jako „jak to vypadalo
při podpisu" by byla nepravda.

Náhled se v takovém případě staví bez přepínače a ukazuje dnešní soubor. Že obsah neodpovídá
podepsanému, říká karta a je to sdělení, které náhled nemá čím doplnit.

---

## 5. Ukázat rozdíl, ne jen obě podoby

Přepnout mezi dvěma podobami dokumentu je málo — u dvacetistránkového dokumentu čtenář rozdíl
nenajde. **DSS ale ví, kde je**, a aplikace to dnes vypisuje jako holá čísla v Detailním rozboru
(`DetailedReportView` 541–546):

| co DSS vrací | dnes | v náhledu | je to rozdíl mezi revizemi? |
|---|---|---|---|
| `getPdfVisualDifferenceConcernedPages()` | „Vizuální rozdíly — stránky 3, 7" | skok na stránku 3, zvýrazněná v pruhu miniatur | **ano** |
| `getPdfPageDifferenceConcernedPages()` | „Přidané/odebrané stránky" | stránka, která v kryté revizi není vůbec | **ano** |
| `getPdfAnnotationsOverlapConcernedPages()` | „Překryv anotací" | vlastní sdělení, ne rozdíl — viz 5.1 | **ne** |

To je ta část, která z náhledu dělá nástroj a ne ozdobu: **čísla, která dnes nikdo nemá jak ověřit,
se změní v „podívej se sem".**

Čtyři kategorie objektových změn (`getPdfExtensionChanges()`, `…SignatureOrFormFillChanges()`,
`…AnnotationChanges()`, `…UndefinedChanges()`) slouží větě z oddílu 4.8: podle nich se pojmenuje,
**co** za posledním prvkem přibylo. Na otázku, jestli to čtenář uvidí, ale neodpovídají — na tu
odpovídají první dva řádky tabulky.

### 5.1 Změřeno 18. 9. 2026 — `PdfRevisionTailProbe` nad `D:\tmp`, 214 PDF

| | |
|---|---|
| souborů s kryjícím prvkem | **179** |
| poslední prvek sahá na konec souboru (není o čem mluvit) | **118** |
| za posledním prvkem ještě něco je | **61** |
| z toho jen rozšíření dokumentu a vyplnění polí | **48** |
| z toho s **vizuálním nebo stránkovým** rozdílem | **0** |

**Překryv anotací není rozdíl mezi revizemi. Změřeno, ne odvozeno:** 15 dokumentů má poslední
kryjící prvek na úplném konci souboru — za ním není ani bajt — a DSS u nich přesto hlásí překryv
anotací. Vizuální rozdíl v takovém případě nehlásí ani jednou. Překryv je tedy vlastnost **jedné**
revize (anotace překrývající se navzájem), ne rozdíl mezi dvěma.

První podoba návrhu ho mezi rozdíly počítala a první běh sondy podle toho hlásil **25** dokumentů
se změnou obsahu. Po opravě jich je **13**, a všech třináct je „vyplnění formulářů" nebo „změna
anotací" bez jediného vizuálního rozdílu — tedy struktura, ne obsah.

**Pravidlo, které z toho plyne:** obsah se změnil tehdy a jen tehdy, když DSS hlásí **vizuální
nebo stránkový** rozdíl. Kategorie objektových změn slouží k pojmenování toho, co přibylo, ne
k odpovědi na otázku, zda čtenář uvidí něco jiného.

### 5.2 Větev „změnil se obsah" — doměřeno na vyrobeném dokumentu

V `D:\tmp` není jediný dokument, u kterého by se po podpisu viditelně změnil obsah, takže se na
sbírce ta zajímavější půlka věty z 4.8 ověřit nedala. Dokument se proto **vyrobil**
(`TamperedRevisionMaker`): vzalo se podepsané PDF a přírůstkovou aktualizací se přes první
stranu dokreslil pruh s textem. Původní bajty zůstaly nedotčené, jak přírůstková aktualizace
velí — a s nimi i podepsaný prefix.

Naměřeno nad `cvicny dokument 4 1 s revinfo.pdf` (přibylo 2 284 B):

| | před zásahem | po zásahu |
|---|---|---|
| verdikt podpisu | INDETERMINATE / OUT_OF_BOUNDS_NOT_REVOKED | **týž** |
| vizuální rozdíl | žádný | **strana 1** |
| `RevisionTail.contentChanged()` | `false` | **`true`, strany [1]** |

**Verdikt se nezměnil ani o písmeno** — a to je přesně to, co má blok říkat: podpis zůstává,
jaký byl, protože podepsané bajty nikdo nerozbil, a přesto dnešní podoba dokumentu není celá ta
podepsaná. Kdyby se zásahem verdikt propadl, šlo by o situaci 1 a blok by se nestavěl
(oddíl 4.9).

Nástroj nic nepodepisuje ani nefalšuje; vstupní soubor nechává beze změny a vzorek ukládá stranou
od sbírky. **Vyrobený soubor se do repozitáře neukládá** — nese cizí podpis.

### 5.3 Acrobat nad tímtéž souborem hlásí neplatný podpis

Uživatel vyrobený vzorek otevřel v Adobe Acrobat Readeru a ten nad ním hlásí **neplatný podpis
kvůli změnám v dokumentu** — tedy něco jiného, než co říká AnEP.

Není to rozpor, nýbrž **rozdíl v nastavení**, a stojí za to ho mít zapsaný:

| konstrukt v politice DSS 6.5 | úroveň | co to dělá |
|---|---|---|
| `PdfPageDifference` | `FAIL` | přidání či odebrání stránky podpis shodí |
| `PdfVisualDifference` | `WARN` | **změna obsahu stránky jen varuje** |
| `DocMDP`, `FieldMDP`, `SigFieldLock`, `UndefinedChanges` | `WARN` | varují |

AnEP tedy dědí výchozí chování DSS: obsah stránky se změnit smí a verdikt se nehne. Acrobat je
přísnější a bere za porušení jakoukoli změnu obsahu po podpisu.

> **Pozor na `policy/constraint.xml` v kořeni projektu.** Je to **mrtvá kopie** — leží mimo
> resources, do jaru se nebalí a aplikace ji nečte (commit c135f7d). Živá politika je ta z jaru
> `dss-policy-jaxb`. Číst tu v kořeni znamená číst stav DSS 6.4 z doby, kdy se tam dostala.

**Rozhodnutí uživatele z 18. 9. 2026: `WARN` zůstává a jen se o tom mluví.** Zvýšit
`PdfVisualDifference` na `FAIL` by AnEP srovnalo s Acrobatem, ale odešlo by od výchozího chování
DSS; dopad na sbírku vypadá nulově, jenže měřen byl jen poslední kryjící prvek, takže by se to
před takovou změnou muselo doměřit pořádně.

**Text proto Acrobat nejmenuje** (rovněž rozhodnutí uživatele) a mluví o „nástrojích, které berou
jakoukoli změnu obsahu po podpisu za porušení". Jedno pozorování nad jedním dokumentem neopravňuje
k tvrzení o tom, jak se cizí produkt chová vždycky — táž úvaha, kvůli které se z bloku „Způsob
ověření" zrušila předpověď pro jiné validátory.

**Blok kvůli tomu neslibuje platnost.** Věta „podpis zůstává platný" v něm stála a byla přeceněná;
dnes mluví o zdejším verdiktu („verdikt uvedený výše se tím nemění") a rovnou říká, že přísnější
posouzení existuje. Hlídá to zkouška, aby se ten slib nevrátil.

Vlastní porovnání obrázků (diff dvou rastrů) návrh **nenavrhuje**. DSS už tu práci udělal a jeho
odpověď je věcnější — ví, co se změnilo v objektech, kdežto rozdíl rastrů by hlásil i to, co se
jen jinak vyhladilo.

---

## 6. Kudy se náhled otevírá

**Rozhodnutí uživatele z 18. 9. 2026: tlačítko v záhlaví, ne záložka.** Ikonka brýlí v pruhu stavu,
**vlevo od stávajícího `statusOpenBtn` („↗", otevřít v přidružené aplikaci)**. Tedy
`new HBox(8, progress, previewBtn, statusOpenBtn, statusAttachBtn, statusBox)` na
`MainView` řádek 627.

Je to lepší než záložka ze tří důvodů, které za rozhodnutím stojí:

- **Sousedství dává smysl.** Vedlejší tlačítko otevírá dokument v Acrobatu; tohle ho otevírá
  v AnEP. Dvě odpovědi na touž potřebu vedle sebe.
- **Startu se to nedotkne vůbec.** Záložka by potřebovala líné stavění po vzoru `protokolTab`;
  tlačítko nepotřebuje ani to — dokud na ně nikdo neklikne, neexistuje ani pohled.
- **Viditelnost se řídí obsahem.** `statusOpenBtn` se už dnes skrývá podle toho, co je otevřené;
  náhled se skryje u ne-PDF stejně.

Klik otevře **vlastní okno**, ne panel v hlavním. Vzor je `MainView.detachTab()`, který přesně
tohle dělá pro záložky: nová scéna nad přestěhovaným kořenem. Dokument tak jde mít vedle Analýzy
na druhém monitoru, což je u porovnávání verdiktu s obsahem to, oč jde.

### 6.1 Jedno okno, a nové ověření je zavře

**Rozhodnutí uživatele z 18. 9. 2026.** Okno je **jedno**: další stisk tlačítka to stávající
vytáhne dopředu, nezaloží druhé.

**Nové ověření je zavře.** Náhled ukazuje dokument, o kterém mluví verdikt vedle — a nechat ho
otevřený nad souborem, který se právě přestal ověřovat, znamená mít na obrazovce dvě tvrzení
o dvou různých dokumentech, aniž by to na kterémkoli z nich bylo napsané. Cenou je, že kdo si
náhled odložil na druhý monitor, o něj při každém ověření přijde; je to cena za to, že se
nespárují nespárovatelné údaje, a v téhle aplikaci se taková cena platí i jinde.

Bylo-li okno otevřené, **otevře se po dokončení ověření znovu, už nad novým souborem.** Kdo si ho
jednou vyžádal, nemá o něj přijít proto, že sáhl po dalším dokumentu — a u ne-PDF souboru se prostě
neotevře, stejně jako se u nich neukáže tlačítko.

---

## 7. Stavba pohledu

Nový `PdfPreviewView` v `ui/`, stavěný až při stisku tlačítka.

### 7.1 Výkon a paměť — změřeno 18. 9. 2026

`PdfRenderCostProbe` nad `D:\tmp` (214 souborů, 644 stran), měřítko 1,5 (asi 108 DPI):

| | medián | nejhorší |
|---|---|---|
| otevření dokumentu | 1 ms | 227 ms |
| vykreslení stránky | **32 ms** | **454 ms** |
| převod na PNG | 33 ms | 105 ms |
| rastr v paměti | **3,2 MB** | 3,3 MB |
| výsledné PNG | 75 kB | 488 kB |

**Odhad 8,7 MB na stránku byl mimo** — platil by pro 150 DPI, kdežto tohle je 1,5násobek. Rastr
zabírá 3,2 MB, takže cache o osmi stranách je asi 26 MB a listování tam a zpět pokryje.

**Medián by se na obslužném vlákně ztratil, nejhorší případ ne**: půl vteřiny zamrzlého okna je
vidět. Vykresluje se proto na vlákně na pozadí.

**Vlákna jsou dvě, a to od páté etapy.** Stránka a miniatury se o jedno dělit nemohou: miniatur
je tolik, kolik má dokument stran, a fronta plná miniatur by tu jednu stránku, na kterou se
čtenář dívá, odsunula za ně. Hledání běží na vlákně miniatur — je to rovněž práce, která smí
počkat. Miniatury mají i cache vlastní: při měřítku 0,2 jsou asi stokrát menší než stránka
a vytlačovat kvůli nim stránky z její cache by bylo obráceně.

### 7.2 Dvě vlákna ano, dva přístupy k dokumentu ne

**Nález uživatele z 18. 9. 2026, hned po páté etapě:** v náhledu měla vizualizace podpisu
přeházená písmena — „RNDr. Ing. Jiří Peterka“ se vykreslilo jako „RNdr. Lng. Jiví P%terkb“ —
zatímco zbytek téže stránky byl v pořádku a týž soubor vykreslený mimo aplikaci vyšel správně.

Postup, kterým se to našlo, stojí za zapsání, protože podezřelí byli tři a dva z nich nevinní:

| podezřelý | jak se vyloučil |
|---|---|
| PDFBox nad tím dokumentem | originál vykreslený zvlášť vyšel správně |
| ořez prefixu (etapa 3) | prefix je **bajt po bajtu shodný** s originálem a vykreslí se správně |
| **souběh dvou vláken** | ✔ změřeno, viz níže |

`ConcurrentRenderProbe`: táž stránka z téhož `PDDocument`, vykreslená dvacetkrát, zatímco druhé
vlákno vykresluje miniatury.

| | rastrů odlišných od měřítka |
|---|---|
| bez závory | **19 z 20** |
| se závorou | **0 z 20** |

**`PDDocument` není bezpečný pro souběžné použití a písma jsou to první, co se z toho sype:**
kódy zůstanou, ale sáhnou vedle. Do PDFBoxu proto smí vždy jen jedno vlákno — vykreslování
prochází jednocestnou závorou, a v ní je i zavření dokumentu (zavřít ho pod rukama
vykreslujícího vlákna je táž chyba, jen hlučnější).

Dvě fronty mají smysl dál: požadavek na stránku počká nanejvýš na jednu rozdělanou miniaturu,
tedy pár desítek milisekund, ne na celou frontu.

Zbývá v platnosti:

- Rastr se do JavaFX předává přes PNG (`ByteArrayInputStream`), jak už to dělají vizualizace:
  `SwingFXUtils` je v modulu `javafx.swing`, který aplikace v závislostech nemá. Stojí to podle
  měření medián 33 ms navíc.
- Měřítko se násobí měřítkem displeje, jinak je obraz na obrazovce s vysokým rozlišením rozmazaný —
  obrázek se totiž roztahuje na šířku okna.
- Miniatury v pruhu se vykreslují v mnohem menším měřítku a drží se zvlášť; jinak by cache stránek
  vytlačily.
- **Přepnutí podoby zdvojnásobí nároky:** krytá revize je druhý `PDDocument` nad druhým polem
  bajtů. Návrh drží otevřenou vždy jen jednu podobu a při přepnutí tu předchozí zavře; porovnávat
  je vedle sebe návrh nenabízí.

> ⚠ **Čím sbírka neposloužila.** 214 dokumentů má dohromady 644 stran, tedy zhruba tři na dokument.
> Chování nad dokumentem o stovkách stran se z toho usoudit nedá. Cache omezená počtem,
> vykreslování jen viditelné stránky a **virtualizovaný pruh miniatur** (`ListView` staví jen ty
> buňky, které jsou vidět, takže stostránkový dokument nevykreslí sto miniatur, nýbrž deset) jsou
> opatření proti případu, který se nenaměřil — ne závěr z měření.

### 7.2 Ovládání

Listování, zoom, přizpůsobit šířce. Pruh miniatur vlevo, v něm zvýrazněné stránky s rozdíly
(oddíl 5).

**Vlastní měřítko v procentech** (rozhodnutí uživatele 20. 9. 2026, na místě zrušeného
vyhledávání): tlačítka `−` a `+` násobí a dělí 1,25, takže na rovných sto procent nedosáhnou.
Údaj o měřítku je proto **klikací** — kliknutím se na jeho místě otevře políčko s hodnotou,
která právě platí, a ta se přepíše. Enter i odchod jinam hodnotu uplatní, Esc ji zruší;
nesmysl se mlčky zahodí a hodnota mimo 25–500 % se ořízne, stejně jako u tlačítek. Políčko
v liště trvale nestojí: bylo by to její nejnápadnější prvek a použije se zřídka.

### 7.3 Vyhledávání v textu — ❌ ZRUŠENO 20. 9. 2026

**Rozhodnutí uživatele:** *„odstraň možnost vyhledávání v náhledu — je to skutečně jen náhled,
když někdo chce hledat, může si spustit Adobe Acrobat Reader."* Políčko, tlačítko „Další ▸",
Ctrl+F, F3, značka nálezu v pruhu miniatur i celý `PDFTextStripper` jsou z okna pryč.

Původní zadání z 18. 9. 2026 znělo „ano, není-li to moc náročné", a etapa **A** (najít
**stránky**, na kterých hledaný text je, a skočit na ně) se udělala. Náročná nebyla; zrušila ji
úvaha o tom, **čím to okno je** — náhledem, ne prohlížečem. Půlka funkce, která umí říct jen
„je to na straně 3", vedle nástrojů, které umí ukázat přímo to místo, je horší než žádná.

Etapa **B** (zvýraznit **místo** na stránce — potomek `PDFTextStripper`, který si v
`writeString(String, List<TextPosition>)` sbírá souřadnice glyfů a přepočítává je týmž postupem
jako výřezy vizualizací, i s rotací a CropBoxem) se nikdy nedělala a tímhle rozhodnutím padá
taky. Kdyby se k tomu mělo někdy vrátit, tohle je to, co by bylo potřeba — a proč se to
nevyplatilo.

Co z toho **zůstává v platnosti**: rozdíly mezi podobami se ukazují dál, jen ne přes text.
Ukazatel stran s rozdílem (oddíl 5) je na tomtéž místě v pruhu miniatur, kde byla značka nálezu,
a odpovídá na otázku, kvůli které se do náhledu chodí: *„přibylo tam něco po podpisu?"*

---

## 8. Vazba na zbytek aplikace

- **Analýza → náhled.** U prvku tlačítko „Ukázat v dokumentu": otevře náhled na stránce a na
  podpisovém poli. Aplikace má k tomu všechno — polohu pole zná
  `PdfSignatureAppearanceExtractor`, který ji dnes používá jen k výřezu.
- **Blok „Po podepsání se v dokumentu změnil obsah" → náhled.** ✅ Hotovo. Čísla stran v něm
  jsou **odkazy**: klik otevře náhled na té straně, v podobě kryté podpisem, a nabídka vedle
  dovolí skočit na dnešní. Míří se na krytou podobu schválně — dnešní ukáže čtenáři kterákoli
  prohlížečka, kdežto to, co bylo podepsáno, žádná. Bez náhledu (sondy, zkoušky) se čísla vysází
  jako prostý text: odkaz, který nikam nevede, je horší než text.
- **V okně samém** ukazuje stavový řádek u každé strany, zda se na ní obsah po podpisu liší, a
  tlačítko „Na rozdíl ▸" skáče mezi takovými stranami dokola. Ukazatel se ptá, zda se ta strana
  liší **mezi podobami**, ne zda se liší právě zobrazená — platí tedy, ať se čtenář dívá na
  kteroukoli.
- **Prázdná podpisová pole.** Aplikace o nich ví a umí předpovědět jejich vzhled; náhled ukáže,
  kde jsou.
- **Protokol.** Náhled se do protokolu **nevysází**. Protokol je o ověření, ne kopie dokumentu, a
  přiložit celý soubor už umí zaškrtávátko „vložit ověřovaný soubor".

---

## 9. Etapy

| # | co | proč v tomhle pořadí |
|---|---|---|
| 1 | **věta „co dělí dnešní soubor od poslední kryté revize"** (4.8) | ✅ **hotovo 18. 9. 2026** — `RevisionTail` + blok v Analýze |
| 2 | tlačítko, okno, jedna stránka, listování | ✅ **hotovo 18. 9. 2026** — `PdfPreviewWindow`; výkon změřen (7.1) |
| 3 | **přepínač verzí** (oddíl 4, i s pravidlem 4.6) | ✅ **hotovo 18. 9. 2026** — `DocumentVersions` + nabídka v okně |
| 4 | zvýraznění stránek s rozdíly (oddíl 5) | ✅ **hotovo 18. 9. 2026** — ukazatel a skok v okně, odkazy z bloku |
| 5 | zoom, přizpůsobit šířce, pruh miniatur | ✅ **hotovo 18. 9. 2026** — všechny etapy návrhu jsou tím uzavřené. Hledání v textu k etapě patřilo a **20. 9. 2026 bylo zrušeno** (7.3) |

**Etapa 1 je nejlevnější ze všech** a nepotřebuje z náhledu ani řádek. Kdyby se zbytek nikdy
nedodělal, ta věta má cenu sama.

**Etapy 1 a 3 před etapou 5** je záměr. Náhled bez zoomu je nepohodlný; náhled bez verzí umí
tvrdit nepravdu.

---

## 10. Rozhodnutá místa

Všechno rozhodl uživatel 18. 9. 2026. Zapsáno tady pohromadě, aby se k tomu nemusel nikdo vracet
skrz celý text — a aby bylo vidět, co je rozhodnutí a co úvaha.

| co | jak | kde |
|---|---|---|
| kolik položek v přepínači | jen tolik, kolik je **různých verzí** | 4.6 |
| kudy se náhled otevírá | **tlačítko v záhlaví** (brýle), vlevo od „↗" | oddíl 6 |
| kolik oken | **jedno**, další stisk je vytáhne dopředu | 6.1 |
| okno při novém ověření | **zavřít**, a bylo-li otevřené, otevřít znovu nad novým souborem | 6.1 |
| vyhledávání v textu | **ne** — zrušeno 20. 9. 2026; náhled je náhled (do 18. 9. 2026 „ano, na úrovni stránek") | 7.3 |
| podoby u porušeného podpisu | **neukazovat** | 4.9 |

**Nic otevřeného nezbývá.** Co zbývá, je jedno **měření**, a to před prvním řádkem kódu: co
doopravdy patří do `getPdfExtensionChanges()` (varování v 4.6) a jak dlouho trvá vykreslení
stránky (7.1).

---

## 11. Čemu se návrh vyhýbá

- **PDF.js ve `WebView`.** `javafx-web` v závislostech je, ale WebKit se v téhle aplikaci schválně
  startuje líně kvůli rozboru startu, a druhá vykreslovací cesta vedle PDFBoxu je nutně druhá sada
  chyb. PDFBox už v aplikaci vykresluje a rotaci i CropBox má vyřešené.
- **Úpravy dokumentu.** Náhled ukazuje, nic nemění.
- **Dvě podoby vedle sebe.** Viz 7.1; paměť je tu to omezení, ne chuť.
- **Vlastní porovnávání rastrů.** Viz oddíl 5.
- **Zvýraznění nalezeného místa na stránce.** Viz 7.3 B — možnost, ne součást návrhu.
