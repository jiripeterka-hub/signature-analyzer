# Sloučený blok „Průběh ověřování“ — návrh textů

Bloky **„Průběh ověřování“** a **„Způsob ověření“** se slučují do jednoho, pojmenovaného
**Průběh ověřování**. Stavba:

1. **Za jakých pravidel se ověřovalo** — dnešní první část bloku „Způsob ověření“, beze změny.
2. **Jak AnEP postupoval** — slovní popis toho, co dnes ukazují řádky a seznamy bloku „Průběh
   ověřování“: ověření k aktuálnímu času, posun na časové razítko, revokační informace, závěr.
3. U neznámé platnosti končí odkazem na blok **Zjištěné důvody a doporučení**, kde jsou důvody
   rozebrány podrobně.

Texty níže jsou vysázené pro `PAdES B-T neviditelny PS.pdf`, ve dvou režimech.

---

## Zjištěná fakta o příkladu

| Údaj | Hodnota |
|---|---|
| Formát | PAdES-BASELINE-T (referenční) |
| Podepsaná osoba | RNDr. Ing. Jiří Peterka |
| Tvrzený čas podpisu | 14. 1. 2026 10:39:11 |
| Podpisový certifikát | PostSignum Qualified CA 4, platný 7. 6. 2025 – **27. 6. 2026** |
| Odvolán | **28. 4. 2026 15:20** |
| Časová razítka | jedno podpisové, Sectigo Qualified Time Stamping Signer #3, **14. 1. 2026 10:39:13**, ověřeno jako platné, certifikát autority do 2. 8. 2034 |
| Dnes | 19. 8. 2026 |

Podstata případu: **odvolání přišlo až po čase, který dokládá razítko** (28. 4. vs. 14. 1.).
Rozhoduje tedy jediné — smí se ten údaj o odvolání vůbec použít, když vznikl až po expiraci
certifikátu.

---

# A. Přísný režim (dle standardů) — výsledek neznámý

### Část 1 — za jakých pravidel se ověřovalo

> Ověřování proběhlo **zcela podle technických standardů** (ETSI EN 319 102-1 a navazujících),
> bez jakýchkoli ústupků a bez zvláštních nastavení. Ověřovalo se k aktuálnímu času.

### Část 2 — jak AnEP postupoval

> **Ověření k aktuálnímu času.** Ověřování začíná u dneška: posuzuje se, zda podpis obstojí
> k okamžiku, kdy se na něj někdo dívá (19. 8. 2026 16:48). Neobstál — podpisovému certifikátu
> skončila řádná doba platnosti 27. 6. 2026.
>
> **Důkaz existence v čase.** Pro takový případ standardy dovolují opřít se o doklad, že podpis
> existoval už dříve, a posuzovat jej k onomu dřívějšímu okamžiku. Podpis nese jedno časové
> razítko — podpisové, od služby Sectigo Qualified Time Stamping Signer #3, s časem
> 14. 1. 2026 10:39:13. Razítko bylo ověřeno jako platné, takže existenci podpisu k tomuto
> okamžiku skutečně dokládá. Řetězec razítek je nepřerušený: razítko vzniklo ještě za platnosti
> podpisového certifikátu a certifikát razítkovací autority sám platí až do 2. 8. 2034.
>
> **Revokační informace.** K certifikátu razítkovací autority se podařilo získat on-line odpověď
> OCSP (vydána 17. 8. 2026), podle níž odvolán není; ta se použít dala. K podpisovému certifikátu
> byla rovněž získána odpověď OCSP (vydána 19. 8. 2026), podle níž byl certifikát odvolán
> 28. 4. 2026 — tedy až tři a půl měsíce po čase, který dokládá razítko. **Použít ji ale nešlo:**
> vznikla až po skončení řádné doby platnosti certifikátu (27. 6. 2026) a takovému údaji standardy
> vypovídací hodnotu nepřiznávají. Žádný dřívější údaj o stavu tohoto certifikátu k dispozici není.
>
> **Závěr.** Bez použitelného údaje o stavu podpisového certifikátu nelze rozhodnout, zda byl
> certifikát v rozhodném okamžiku v pořádku — a to platí bez ohledu na to, ke kterému okamžiku se
> podpis posuzuje. Ověřování se proto zastavilo dřív, než stačilo rozhodný okamžik na čas razítka
> vůbec posunout. **Stav platnosti podpisu zůstává neznámý — nikoli neplatný.** Podrobnosti jsou
> v bloku Zjištěné důvody a doporučení.

---

# B. Tolerantní režim — výsledek platný

### Část 1 — za jakých pravidel se ověřovalo

> Ověřování **neproběhlo zcela podle technických standardů**. Uvedeného výsledku bylo dosaženo
> za těchto konkrétních podmínek:
>
> • **Byly využity revokační informace vydané po skončení řádné doby platnosti certifikátu.**
>   Standardy takový údaj nepřipouštějí: o certifikátu, jehož platnost už skončila, se podle nich
>   nedá zpětně nic dokládat seznamem či odpovědí, které vznikly až potom. Bez tohoto ústupku by
>   stav revokace zůstal nezjištěný a výsledek by nebyl kladný.
>
> Zapnuty byly i další ústupky (Přijmout i revokační informace od expirovaného vydavatele,
> Ignorovat zastaralost algoritmu SHA1, Ověřovat i podpisy ve formátu PDF-NOT-ETSI), na výsledku
> podpisu se ale neprojevily — bez nich by dopadl stejně.

### Část 2 — jak AnEP postupoval

> **Ověření k aktuálnímu času.** Ověřování začíná u dneška: posuzuje se, zda podpis obstojí
> k okamžiku, kdy se na něj někdo dívá (19. 8. 2026 16:48). Neobstál — podpisovému certifikátu
> skončila řádná doba platnosti 27. 6. 2026 a k dnešnímu dni je navíc odvolán.
>
> **Důkaz existence v čase.** Pro takový případ standardy dovolují opřít se o doklad, že podpis
> existoval už dříve, a posuzovat jej k onomu dřívějšímu okamžiku. Podpis nese jedno časové
> razítko — podpisové, od služby Sectigo Qualified Time Stamping Signer #3, s časem
> 14. 1. 2026 10:39:13. Razítko bylo ověřeno jako platné, takže **rozhodným okamžikem se stal
> tento čas**. Řetězec razítek je nepřerušený: razítko vzniklo ještě za platnosti podpisového
> certifikátu a certifikát razítkovací autority sám platí až do 2. 8. 2034.
>
> **Revokační informace.** K podpisovému certifikátu se podařilo získat on-line odpověď OCSP
> (vydána 19. 8. 2026), podle níž byl certifikát odvolán 28. 4. 2026. Vznikla až po skončení řádné
> doby platnosti certifikátu (27. 6. 2026), takže ji standardy nepřipouštějí — **díky zapnutému
> ústupku se však použila**. K certifikátu razítkovací autority byla k dispozici odpověď OCSP
> z 17. 8. 2026, podle níž odvolán není.
>
> **Závěr.** K rozhodnému okamžiku 14. 1. 2026 10:39:13 byl podpisový certifikát platný a odvolán
> nebyl — odvolání přišlo až 28. 4. 2026, tedy o tři a půl měsíce později. **Podpis je proto
> platný.** Stojí to ale celé na uvedeném ústupku: bez něj by o stavu certifikátu nebylo čím
> rozhodnout a výsledek by zůstal neznámý.

---

# Rozhodnuto a zabudováno (19. 8. 2026)

Všech pět bodů níže je vyřízeno; blok je v `AdvisorView.addValidationCourseBlock()`.

1. **Podrobný výčet** — složený pododdíl pod textem, s původními řádky a seznamy beze změny.
2. **Rozhodný okamžik dle DSS** — v podrobném výčtu, z textu zmizel.
3. **Tučná návěští odstavců** — ponechána.
4. **„Výsledek tedy nezávisí na tom, čím byl získán.“** — vypuštěna.
5. Věta o ověření k aktuálnímu času přepsána podle zadání.

## Co si vyžádalo úpravu oproti návrhu

- **Data certifikátů bez času.** V textu „skončila řádná doba platnosti 27. 6. 2026“, ne
  „27. 6. 2026 10:35“ — hodina a minuta u hranice platnosti nic nemění a tříští větu. Přesný údaj
  nese podrobný výčet. Čas se v textu drží jen u okamžiku, který dokládá razítko.
- **Malá čísla slovem.** „o tři a půl měsíce“, ne „o 3 a půl měsíce“.
- **Odvolání v prvním odstavci jen tehdy, když se o něm dalo rozhodnout.** V přísném režimu se
  o odvolání mlčí až do třetího odstavce, kde zazní i s důvodem, proč se údaj nedal použít —
  jinak by první odstavec tvrdil zjištění, které ten běh neudělal. Právě v tom se obě varianty
  textu liší a je to ta podstatná věc na celém příkladu.
- **Závěr nesmí opakovat.** Že razítko chybí nebo neobstálo, řekl druhý odstavec; konec platnosti
  certifikátu první. Původní znění (převzaté z bloku, kde stálo samo) obojí opakovalo i s daty.
- **Revokační odstavec mluví adresně o podpisovém certifikátu** a zbytek řetězců shrne jednou
  větou. Aby to šlo, přibyla v `ValidationCourse` trojice `signingCertRevocationUsed /
  Unusable / Missing` — poznávat „ten správný“ řádek podle jména certifikátu v textu seznamu by
  se rozbilo při první změně formulace.

## Pořadí větví v závěru

Chybějící důkaz existence → situace 25/26 → nereferenční formát → stav revokace. Chybějící důkaz
existence je první schválně: situace 25 se u prvku bez platného razítka opírá jen o *tvrzený* čas
podpisu, a tvrzení není zjištění; formát zase o platnosti nevypovídá nic, kdežto chybějící
rozhodný okamžik ano.

## Ověřeno

35 souborů z `D:	mp` bez jediné výjimky. Prošly všechny třídy případů, které příklad sám
neukazuje: podpis bez razítka, razítko neuznané jako platné, nereferenční formát, podpis vzniklý
po expiraci certifikátu, kotva důvěry, pečeť (shoda rodu), neplatný podpis.

---

# Původní otázky k rozhodnutí (zodpovězeno výše)

### 1. Co bude s dnešními řádky a seznamy

Blok dnes ukazuje strukturovaně: *Ověřeno dne*, *Prokázaná existence*, *Rozhodný okamžik dle DSS*
+ vysvětlující ⓘ, a tři seznamy revokačních údajů (použitelné / dostupné ale nepoužitelné /
chybějící). Návrh je **vypráví slovy** a strukturu ruší.

U tohoto příkladu to vychází dobře — dva revokační údaje, jedno razítko. **U dokumentu na úrovni
LTA s několika razítky a desítkou certifikátů v řetězcích se to do plynulého textu nevejde.**
Návrh řešení: text zůstane, ale pod ním bude složený pododdíl **„Podrobný výčet“** s dnešními
seznamy tak, jak jsou. Textu se pak drží jen to podstatné: kolik razítek, které z nich rozhodlo,
a u kterého certifikátu údaj chyběl nebo se nedal použít.

### 2. Řádek „Rozhodný okamžik dle DSS“

V přísném režimu DSS uvádí jako rozhodný okamžik **aktuální čas**, protože se k posunu na razítko
vůbec nedostal (`CERTIFICATE_CHAIN_GENERAL_FAILURE`). Dnes to blok řeší zvláštním řádkem
a vysvětlivkou ⓘ. V navrženém textu je to vyjádřeno větou *„Ověřování se proto zastavilo dřív, než
stačilo rozhodný okamžik na čas razítka vůbec posunout.“* — **údaj tím z bloku mizí**. Pokud má
zůstat i číselně, patří do podrobného výčtu.

### 3. Nadpisy odstavců

Text výše používá tučné uvození *Ověření k aktuálnímu času / Důkaz existence v čase / Revokační
informace / Závěr*. Je to čtyřikrát pod sebou a dá se to i vypustit — pak vzniknou čtyři odstavce
bez návěští. Uvození ale pomáhá tomu, kdo v bloku hledá jednu konkrétní věc.

### 4. Věta „Výsledek tedy nezávisí na tom, čím byl získán.“

V přísné variantě jsem ji z první části **vypustil** — je to poslední zbytek zrušené předpovědi
pro cizí validátory a v sloučeném bloku, který hned pokračuje popisem postupu, působí jako cizí
těleso. Vrátit se dá jedním řádkem.

### 5. Případy, které tenhle příklad neukazuje

Text bude muset umět i: podpis **bez razítka**, **více razítek** (a jak se hodnotí řetěz),
razítko **neuznané** (vyřazená služba, porušená integrita), **chybějící** revokační údaje místo
nepoužitelných, a **kotvu důvěry**, u které se revokace nezjišťuje. Podle rozhodnutí o bodech
1–3 k nim doplním varianty.


---

# Odstavec o revokaci rozepsán (19. 8. 2026)

První verze psala v tolerantním režimu jen *„U podpisového certifikátu se stav revokace zjistit
podařilo: certifikát byl odvolán 28. 4. 2026.“* — a zamlčela to podstatné: **odkud údaj je a čemu
vděčí za to, že se vůbec směl použít.** Čtenář se z toho nedozvěděl, že za kladným výsledkem stojí
zapnutý ústupek a že bez něj by týž dokument dopadl jinak.

Nové znění (tolerantní režim):

> **Revokační informace.** K podpisovému certifikátu se podařilo získat on-line odpověď OCSP
> (vydána 19. 8. 2026), podle níž byl certifikát odvolán 28. 4. 2026. Vznikla až po skončení
> řádné doby platnosti certifikátu (27. 6. 2026), takže ji standardy nepřipouštějí — **díky
> zapnutému ústupku se však použila**. K certifikátu razítkovací autority byla k dispozici
> odpověď OCSP z 17. 8. 2026, podle níž odvolán není.

## Co k tomu bylo potřeba

Rozklad údaje na části, ze kterých jde větu poskládat — `ValidationCourse.RevocationFact`
(OCSP/CRL kvůli shodě rodu, původ, kdy vznikl, co říká, a které pravidlo muselo být uvolněno).
Skládá se pro podpisový certifikát, pro certifikát razítkovací autority a pro nepoužitelný údaj
u podpisového certifikátu.

**Uvolněné pravidlo se pozná srovnáním dvou čtení téhož údaje:** jednou s běžným nastavením,
jednou s čerstvým `AppSettings` (= dle standardů). Když s přísným neobstojí a s běžným ano,
rozhodl ústupek — a důvod z přísného čtení říká který.

## Past, kterou to odhalilo

Důvod odmítnutí se **nedá odvodit ze zjištěných situací**. U prvku s porušenou integritou se
situace 6 nehlásí, přestože revokační údaj post-expirační je, a text sklouzl k obecnému
„v podobě, v jaké je k dispozici, jej standardy nepřipouštějí“. Postihlo to 6 z 33 prvků korpusu.
Důvod se proto bere z rozkladu údaje a situace jsou jen záloha.

## Poslední věta kratším tvarem

Věta o certifikátu razítkovací autority nepoužívá plnou vazbu („se podařilo získat on-line…“) —
byla by podruhé za sebou a odstavec by se o ni zadrhl. Kratší tvar navíc odpovídá tomu, čím ta
věta je: doplněním, ne zjištěním, o které by se výsledek opíral.

## Ověřeno

35 souborů, žádná výjimka. Osm tvarů první věty, u všech sedí shoda rodu — „vložena odpověď OCSP
(vydána …)“ × „posloužil seznam CRL (vydán …)“.


---

# Přísný režim sjednocen s tolerantním (19. 8. 2026)

U **téhož podpisu** se oba režimy o revokačním údaji rozcházely v tom, co o něm vůbec prozradily:
tolerantní psal, že šlo o OCSP odpověď získanou on-line 19. 8. 2026, přísný jen že „se údaj našel“.
Přitom je to týž údaj a totéž zjištění — liší se jedině to, zda se směl použít.

Obě větve teď skládá jedna metoda a **liší se pouze zakončením**:

> K podpisovému certifikátu se podařilo získat on-line odpověď OCSP (vydána 19. 8. 2026), podle
> níž byl certifikát odvolán 28. 4. 2026. Vznikla až po skončení řádné doby platnosti certifikátu
> (27. 6. 2026), takže ji standardy nepřipouštějí — …
>
> - **tolerantní:** … **díky zapnutému ústupku se však použila**.
> - **přísný:** … **použít ji proto nešlo**.

Odstavec „Ověření k aktuálnímu času“ přitom v přísném režimu o odvolání dál mlčí. Není to rozpor:
tam se říká, co ověřování v prvním kroku zjistilo, tady co se našlo a proč to nestačilo.

## Přibyl třetí stav

`RevocationFact` měl jen `revoked` — a stav **„respondér stav nezná“** by v té vazbě vyšel jako
„odvolán není“, což je nepravda. Doplněn příznak `statusUnknown` a věta pro něj.

## Ověřeno

35 souborů, žádná výjimka. Třináct tvarů odstavce, u všech sedí shoda rodu i pádu; obecné
„v podobě, v jaké je k dispozici“ z korpusu zmizelo úplně.


---

# Drobnosti ve slovosledu (19. 8. 2026)

- „se **on-line podařilo získat**“ → „se **podařilo získat on-line**“
- „Ověření k aktuálnímu času: **začíná se** posouzením“ → „**začalo se** posouzením“

Druhá změna sjednocuje čas s okolím: celý výklad popisuje běh, který už proběhl, a jediné
přítomné „začíná se“ z něj vybočovalo.
