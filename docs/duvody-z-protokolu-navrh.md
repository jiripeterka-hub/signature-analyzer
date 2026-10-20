# Návrh: důvody sestavené z protokolů DSS

**Stav: zavedeno 14. 9. 2026.** Důvody 31, 32 a 33 jsou v aplikaci; znění níže je jejich
závazná podoba — při úpravě textu se mění tady i v `AdvisorView`, aby si dokument a aplikace
odpovídaly. Způsob zařazení příčiny se oproti původnímu návrhu změnil, viz dodatek na konci.
Vzniklo 14. 9. 2026 z podnětu nad `priklady 2018\T uznavany ICA s razitkem BE.pdf`, kde karta
v Analýze neuvedla žádný důvod, ačkoli protokol DSS příčinu pojmenovává zcela jasně.

Texty níže jsou psané pro laického čtenáře. Klíče hlášení DSS (`BBB_XCV_…`) jsou v návrhu jen
jako doklad, odkud se ten závěr bere — **do textů pro čtenáře nepatří** a nejsou v nich.

---

## Co se zjistilo

Situací aplikace rozeznává 51. **Čtrnáct z nich nemá vlastní důvod:**

    13, 18, 20, 31, 32, 33, 34, 37, 38, 39, 40, 41, 44, 45

Nejsou to ale jeden druh. Rozpadají se na tři skupiny a jen u jedné dává důvod smysl.

### Skupina 1 — nemají mít důvod, a je to správně

| situace | co znamená |
|---|---|
| 31 | řada časových razítek je souvislá |
| 32 | poslední razítko je stále kryté svým certifikátem |
| 33 | revokační informace jsou pokryté |
| 34 | verdikt vznikl bez ústupků |
| 45 | pokrytí je úplné až na volnější čtení |

Tohle jsou **vlastnosti**, podle kterých se rozhoduje nabídka v Digitální kontinuitě, ne závady.
Blok se jmenuje „Zjištěné důvody a doporučení" a vypisuje důvody *neúspěšného ověření* — dát sem
zjištění „řada razítek je souvislá" by čtenáře pletlo. **Návrh: nechat beze změny.**

### Skupina 2 — mluví o nich jiné místo karty

| situace | co znamená | kde už to stojí |
|---|---|---|
| 18 | podpis je PKCS #7, neodpovídá CAdES | blok „Formát podpisu" |
| 20 | ověření dopadlo kladně, ale ignorovaný údaj ukazuje na odvolání | poznámka k výsledku; brána Kontinuity |
| 44 | odvolání brání uchování | Digitální kontinuita |

**Návrh: nechat beze změny**, ledaže byste chtěl, aby se to objevilo i v Analýze. Riziko je, že
totéž řekneme dvakrát a čtenář bude hledat rozdíl, který tam není.

### Skupina 3 — chybí tu odpověď a protokol DSS ji má

| situace | co znamená |
|---|---|
| 13 | soubor, který byl podepsán, u podpisu není |
| 37 | evidenční záznam má vadu ve struktuře |
| 38 | evidenční záznam nepokrývá všechno, co podpis podepisuje |
| 39 | evidenční záznam má nesouhlasný otisk |
| 40 | evidenční záznam nemá platné časové razítko |
| 41 | záznam kryje podpis, který sám nenese validační materiál |

---

## Mezera, kterou ukázal váš příklad

Ta je jinde než v seznamu výše a je **širší**: `ReportMapper` dává samostatným časovým razítkům
důvody prázdné natvrdo, s poznámkou *„failure reasons not applicable to standalone timestamps"*.
Takže **žádné neúspěšné samostatné razítko nemá důvod**, ať je příčina jakákoli.

U `T uznavany ICA s razitkem BE.pdf` přitom protokol DSS říká:

    INDETERMINATE / OUT_OF_BOUNDS_NOT_REVOKED
    BBB_XCV_ICTIVRSC_ANS   certifikátu razítka skončila platnost
    ASCCM_AR_ANS_AKSNR     RSA se SHA-1 už není spolehlivá
    PSV_IPTVC_ANS          ověření podle minulosti nebylo průkazné

Je to táž informace, ze které od dneška staví svůj text důvod 21 u razítek *uvnitř podpisu*
(`ReportMapper.timestampFailure()` zařazuje příčinu podle dílčí indikace). Pro samostatná razítka
se nepoužívá jen proto, že tudy nevede cesta.

---

## Návrh A: jeden důvod pro samostatné razítko *(doporučuji)*

Nové číslo **31**. Text se řídí příčinou, stejně jako u důvodu 21 — jedno číslo, šest znění.
Alternativa (šest samostatných čísel) je v části „K rozhodnutí" na konci.

### 31 — samostatné časové razítko neobstálo

Pracovní název do výpisu situací: *samostatné razítko neobstálo*

#### a) skončila platnost certifikátu razítkovací autority
*(dílčí indikace `OUT_OF_BOUNDS_NO_POE`, `OUT_OF_BOUNDS_NOT_REVOKED`)*

> **V čem je problém:** Razítko potvrzuje, že dokument v určitý čas existoval v té podobě, v jaké
> ho vidíte. Aby to potvrzení platilo, musí jít ověřit certifikát, na kterém razítko stojí —
> a tomuto certifikátu skončila platnost DD. MM. RRRR. Že razítko vzniklo ještě předtím, není čím doložit:
> čas, který razítko samo uvádí, je právě to, co se má teprve prokázat.

> **Co může pomoci:** Doložit to zpětně už nelze; mělo se to ošetřit dalším razítkem ještě v době,
> kdy certifikát platil. Pokud té razítkovací autoritě důvěřujete, můžete její certifikát vložit
> do vlastního úložiště (Nastavení → Pokročilé nastavení… → Vlastní důvěryhodné certifikáty →
> Spravovat); ověření pak jeho platnost přestane zkoumat. Je to ústupek zdejší aplikace, ne oprava
> dokumentu — jiný ověřovatel dojde k témuž závěru jako aplikace teď.

#### b) zastaralý algoritmus
*(`CRYPTO_CONSTRAINTS_FAILURE`, `CRYPTO_CONSTRAINTS_FAILURE_NO_POE`)*

> **V čem je problém:** Razítko je postavené na výpočtu, který se dnes už nepovažuje za dost
> odolný. U každého takového výpočtu je stanovený den, po kterém se přestává uznávat, a ten už
> uplynul.

> **Co může pomoci:** V pokročilém nastavení lze zapnout volbu „Ignorovat zastaralost algoritmu
> SHA1". Odolnost razítka tím nevzroste — ověření jen přestane zastaralý výpočet odmítat, a je
> na vás, zda je to v daném případě únosné.

#### c) razítkovací autorita není mezi důvěryhodnými
*(`NO_CERTIFICATE_CHAIN_FOUND`, `NO_CERTIFICATE_CHAIN_FOUND_NO_POE`, `CERTIFICATE_CHAIN_GENERAL_FAILURE`)*

> **V čem je problém:** Důvěra v razítko nestojí na razítku samotném, ale na tom, že se od jeho
> vydavatele dojde až k někomu, komu se věří předem (kdo je tzv. kotvou důvěry). Tady taková cesta nikam nevede: autorita
> v evropských důvěryhodných seznamech buď vůbec není — tak to bývá u interních, zkušebních
> a mimoevropských služeb — nebo tam v rozhodné době nebyla.

> **Co může pomoci:** Pokud té autoritě důvěřujete, vložte její certifikát do vlastního úložiště
> (Nastavení → Pokročilé nastavení… → Vlastní důvěryhodné certifikáty → Spravovat) a ověřte znovu.

#### d) certifikát razítkovací autority byl odvolán
*(`REVOKED`, `REVOKED_NO_POE`, `REVOKED_CA_NO_POE`)*

> **V čem je problém:** Certifikát, na kterém razítko stojí, byl revokován, resp. odvolán — tedy prohlášen za
> nedůvěryhodný ještě před koncem své platnosti. Razítko od něj má cenu jen tehdy, dá-li se
> doložit, že vzniklo dříve, ještě před revokací; a právě to doložit nelze.

> **Co může pomoci:** Nastavení, které by výsledek poctivě změnilo, tu není. Obejít se to zdánlivě
> dá vložením certifikátu té autority do vlastního úložiště, protože u kotvy důvěry se odvolání
> nezjišťuje — jenže tím odvolání nezmizí, jen se na ně aplikace přestane ptát. Kdo dokument někomu
> předkládá, tím nezíská nic.

#### e) nepodařilo se zjistit, zda certifikát nebyl odvolán
*(`TRY_LATER`, `REVOCATION_OUT_OF_BOUNDS_NO_POE`)*

> **V čem je problém:** U certifikátu se ověřuje i to, jestli nebyl mezitím odvolán. Odpověď na
> tuto otázku se získat nepodařilo — a mlčení se za „v pořádku" nebere. U starších razítek to
> bývá tím, že autorita už na otázky neodpovídá, nebo že adresa v certifikátu (pro kladení otázek) dnes nikam
> nevede.

> **Co může pomoci:** Máte-li seznam odvolaných certifikátů (CRL) z doby, kdy certifikát ještě
> platil, vložte jej do vlastního úložiště (Nastavení → Pokročilé nastavení… → Vlastní CRL
> seznamy → Spravovat). Na seznamu vydaném až po skončení platnosti certifikátu nezáleží —
> o tomto certifikátu už nevypovídá nic.

#### f) otisk nesouhlasí
*(`HASH_FAILURE`, `SIG_CRYPTO_FAILURE`)*

> **V čem je problém:** Razítko obsahuje otisk dokumentu, pořízený v okamžiku, kdy razítko
> vzniklo. Při ověřování se otisk spočítá znovu a porovná — a zde nesouhlasí. Typicky to znamená,
> že dokument byl po orazítkování změněn.

> **Co může pomoci:** Opravit to nelze a žádné nastavení na tom nic nezmění. Máte-li původní,
> nezměněnou podobu dokumentu, ověřte ji.

#### g) krytá data se nenašla
*(`SIGNED_DATA_NOT_FOUND`; od 25. 9. 2026 odděleno od f) — samostatné razítko předložené bez
dat dostávalo větu, že otisk nesouhlasí)*

> **V čem je problém:** Razítko nenese orazítkovaná data samotná, jen jejich otisk — a data, ke
> kterým patří, nebyla k ověření předložena ani se nenašla. Otisk tak není s čím porovnat a nelze
> říct, co razítko vlastně potvrzuje. Nic z toho nesvědčí o tom, že by se data změnila.

> **Co může pomoci:** Ověřte razítko společně se souborem, ke kterému patří — teprve pak je otisk
> s čím porovnat.

---

## Návrh B: důvody pro evidenční záznamy (situace 37–41)

Tyhle nastávají jen u dokumentů s evidenčním záznamem (ERS/XMLERS), tedy zřídka. Navrhuji
**jedno číslo 32** se čtyřmi zněními, stejným způsobem jako výše.

### 32 — evidenčnímu záznamu něco schází

#### a) vada ve struktuře *(situace 37)*

> **V čem je problém:** Evidenční záznam je zvláštní příloha, která má prokazovat, že dokument
> existoval v určitém čase a od té doby se nezměnil. Tento záznam neodpovídá tvaru, který pro
> něj předepisuje norma, takže se na něj nedá spolehnout tak, jak by mělo být možné.

> **Co může pomoci:** Vada je v samotném záznamu a opravit ji může jen ten, kdo záznam vytvořil.

#### b) nepokrývá celý podpis *(situace 38)*

> **V čem je problém:** Záznam nepokrývá všechno, co podpis podepisuje. O části dokumentu tedy
> neříká nic.

> **Co může pomoci:** Potřebný je záznam pořízený nad celým podepsaným obsahem; tenhle nahradit
> nelze.

#### c) otisk nesouhlasí *(situace 39)*

> **V čem je problém:** Otisk uložený v evidenčním záznamu se neshoduje s tím, co se spočítá z dokumentu
> dnes. Záznam tedy patří k jiné podobě dokumentu, než jakou máte před sebou.

> **Co může pomoci:** Máte-li tu podobu dokumentu, ke které záznam patří, ověřte ji. Jinak záznam
> neprokazuje nic.

#### d) chybí platné časové razítko *(situace 40)*

> **V čem je problém:** Celá průkaznost evidenčního záznamu stojí na časovém razítku, které jej
> ukotvuje v čase. Tento záznam žádné platné nemá, takže neprokazuje, kdy dokument existoval.

> **Co může pomoci:** Chybu nese samotný záznam; doplnit razítko zpětně nelze.

---

## Návrh C: chybějící podepsaný soubor (situace 13)

### 33 — soubor, který byl podepsán, chybí

> **V čem je problém:** Tento podpis je externí, neboli je uložený zvlášť, mimo podepsaný soubor — 
a ten nebyl aplikaci předložen. Bez něj se nedá ověřit vůbec nic: podpis se
> porovnává právě s ním.

> **Co může pomoci:** Předložte podpis společně se souborem, ke kterému patří. V aplikaci k tomu
> slouží hlavní vstupní pole (vlevo), zatímco externí podpis se vkládá do vstupního pole zcela vpravo.

---

## K rozhodnutí

1. **Jedno číslo na více znění, nebo číslo na každou příčinu?** Návrh výše drží jedno číslo
   a mění text (jako důvod 21). Výhoda: katalog důvodů zůstane čitelný. Nevýhoda: výpis situací
   a číselník `docs/duvody-tisk.html` ukážou jen obecný název.

2. **Skupina 2 (situace 18, 20, 44)** — mají se objevit i v Analýze, nebo stačí tam, kde jsou?

3. **Číslování**: 1–30 je obsazeno, navrženo 31, 32, 33. Jestli chcete jiné pořadí nebo jiná
   čísla, je to jen otázka přečíslování.

4. **Samostatné razítko a „doporučení"**: u některých větví je poctivá odpověď „nedá se nic
   dělat". Má tam „Co může pomoci" zůstat s touto odpovědí, nebo se u takových případů celý
   řádek vynechá? (Dnes se řádek vynechává u důvodů 10, 11 a 12.)

5. **Rozsah**: tohle jsou návrhy pro *laického* čtenáře. Kde je text moc dlouhý nebo naopak
   zjednodušený tak, že by to vadilo, prosím o škrt přímo v tomhle souboru.

---

## Poznámka k tomu, co návrh neřeší

Nedotýká se toho, že samostatné razítko má dnes prázdné důvody **záměrně**. To rozhodnutí je
v kódu popsané jako úmysl, ne jako opomenutí, a nechci ho měnit bez vašeho souhlasu — návrh A
je právě návrhem na jeho zrušení.

---

## Dodatek po měření (14. 9. 2026) — způsob zařazení příиčiny se mění

Sken 416 dokumentů našel **209 prvků bez důvodu a všechny do jednoho jsou samostatná časová
razítka** — mezera je tedy přesně tam, kde ji ukázal Váš příklad, a jinde než v ní žádná není.

### Co měření vyvrátilo

Původní návrh zařazoval příčinu podle **dílčí indikace**. To je špatně: 58 z 209 prvků by
dostalo znení *c) autorita není mezi důvěryhodnými*, ačkoli autorita v seznamech **je** a chybí
jen revokační údaj.

| co říká protokol DSS | počet | správné znění | co by dal původní návrh |
|---|---|---|---|
| `BBB_XCV_ICTIVRSC_ANS` — skončila platnost certifikátu | 135 | **a)** | a) ✓ |
| `BBB_XCV_IRDPFC_ANS` — chybí revokační údaj | **58** | **e)** | c) ✗ |
| `BBB_XCV_HPCCVVT_ANS` — řetěz neplatí k dnešku | 5 | **a)** | c) ✗ |
| `BBB_XCV_CCCBB_TSP_ANS` — řetěz nevede ke kotvě | 2 | **c)** | c) ✓ |
| `BBB_CV_TSP_IRDOI_ANS` — otisk nesouhlasí | 4 | **f)** | f) ✓ |
| `BBB_XCV_IARDPFC_ANS`, `BBB_XCV_REVOC_HAS_CERT_INFO_ANS` | 4 | **e)** | c) ✗ |

**Znění textů se tím nemění** — mění se jen to, které z nich se kdy ukáže.

### Nové pořadí otázek

Rozhodují **klíče hlášení**, dílčí indikace je až záloha:

1. porušený token (otisk nebo podpis razítka nesedí) → **f)**
   - krytá data nenalezena (`SIGNED_DATA_NOT_FOUND`) → **g)**, ještě před klíči hlášení
2. `BBB_XCV_ISCR_ANS` (odvolán) → **d)**
3. `BBB_XCV_CCCBB*` (žádná kotva důvěry) → **c)**
4. `BBB_XCV_ICTIVRSC_ANS`, `BBB_XCV_HPCCVVT_ANS` (expirace) → **a)**
5. `BBB_XCV_IRDPFC_ANS`, `BBB_XCV_IARDPFC_ANS`, `BBB_XCV_REVOC_HAS_CERT_INFO_ANS` → **e)**
6. `ASCCM_*` (zastaralý algoritmus) → **b)**
7. jinak podle dílčí indikace jako dosud

Expirace před revokací i před algoritmem je záměr a je změřený: u dokumentu
`certifikacni_a_uznavany_s_razitky_s_revinfo.pdf` stojí SHA-1 v protokolu nahoře, ale verdikt
drží expirace — po zapnutí ústupku k SHA-1 se indikace nezměnila.

### Co z toho plyne pro již zavedený důvod 21

**Týž nedostatek má důvod 21**, zavedený dnes ráno (commit 529ac7c) — zařazuje razítka uvnitř
podpisu také podle dílčí indikace, takže i tam může u chybějícího revokačního údaje tvrdit
„autorita není mezi důvěryhodnými". Oprava je společná: obojí se ptá téže funkce.

### Znění do zásoby

Ve sbírce se nikdy neukázala větev **b)** (zastaralý algoritmus jako <b>rozhodující</b> příčina)
ani **d)** (odvolaný certifikát razítkovací autority). Zůstávají napsané, ale je počtivé vědět,
že nejsou ověřené na skutečném dokumentu.

---

## Co se zavedlo

| důvod | kdy se indikuje | znění |
|---|---|---|
| **31** | samostatné časové razítko, které neobstálo | šest větví podle příčiny |
| **32** | situace 37, 38, 39 nebo 40 (evidenční záznam) | čtyři větve |
| **33** | situace 13 (chybí podepsaný soubor) | jedno znění |

Zařazení příčiny je na jednom místě — `SignatureSummary.TimestampFailure.of()` — a ptá se ho
**i důvod 21** u razítek uvnitř podpisu. Tím se opravila i jeho chyba: dočasně zařazoval podle
dílčí indikace a u chybějícího revokačního údaje mohl tvrdit, že autorita není mezi
důvěryhodnými.

**Výsledek měření:** na `D:\tmp` (228 souborů, 156 nekladných prvků) klesl počet prvků bez
důvodu z 1 na **0**. Jištěno `TimestampFailureClassifierTest` (9 testů, kombinace klíčů pocházejí
ze skenu, ne z představy).

### Co zůstává neověřené

Větve **b)** (zastaralý algoritmus jako rozhodující příčina) a **d)** (odvolaný certifikát
razítkovací autority) se ve sbírce nikdy neukázaly; stojí jen na testech. Znění pro situace 37–41
a 13 se rovněž nemají na čem ověřit — v korpusu není dokument s evidenčním záznamem ani externí
podpis bez svého souboru.

