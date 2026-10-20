# Archiv: texty prostřední části záložky Digitální kontinuita (do 28. 8. 2026)

Prostřední část záložky byla do 28. 8. 2026 dělená podle **úkonu**, který se nabízí: uchování,
prodloužení, přerazítkování, zafixování, a k tomu dvě odvozené varianty. Texty diktoval uživatel.

Na jeho pokyn („dosavadní části per podpis zahoď“) se dělení změnilo na dělení podle **čtenáře
výsledku** — AnEP, validátory podle standardů, okruh CzechPOINTu — a k tomu blok o tom, co
a do kdy je třeba udělat. Nové texty jsou v `ContinuityElementBlocks`.

Zrušený kód se archivuje ze stejného důvodu jako u zrušené předpovědi v bloku „Způsob ověření“
(`docs/zpusob-overeni-navrh.md`): tvrzení v něm jsou vyladěná měřením na sbírce a při návratu
k některé z variant je levnější je přečíst než odvodit znovu.

## Která varianta se kdy sázela

| varianta | podmínka | metoda |
|---|---|---|
| uchování | brána `canPreserve` a prvek bez překážky | `preservationText` |
| přerazítkování | brána `canRestamp` a prvek bez překážky | `restampingText` |
| prodloužení | brána `canProlong` a prvek bez překážky | `prolongationText` |
| souběh dvou faktorů | `canProlong` a `toleratedRevocationWithExpiredTsu` | `combinedFactorsText` |
| zafixování | překážka a prvek neobstál, brána `canFix` | `fixationText` |
| holý důvod | překážka a prvek obstál | přímo v `addElementBlocks` |
| zbytek | prvek bez překážky, žádná brána (typicky chybí zdroj razítek) | `legacyReachedText` |

## Původní kód

```java
    private void legacyReachedText(VBox body, SignatureSummary sig, boolean withDate) {
        java.time.Instant until = sig.getVerifiableUntil();
        // U prvku, který neobstál, se datum nepíše. Konec ověřitelnosti se počítá bez ohledu na
        // verdikt, takže neplatnému podpisu vyjde stejně jako platnému.
        if (until != null && withDate && "TOTAL_PASSED".equals(displayedIndication(sig))) {
            String when = shortDate(java.util.Date.from(until));
            body.getChildren().add(continuityText(until.isAfter(java.time.Instant.now())
                    ? "Ověřitelný do " + when + "."
                    : "Doba jeho ověřitelnosti uplynula " + when + "."));
        }
        body.getChildren().add(continuityText("Nové razítko "
                + gendered(sig, "na něj", "na ni", "na ně")
                + " dosáhne — zapečetí jeho dnešní stav a lhůtu posune."));
    }

    /**
     * Text pro uchování: dokument má vše potřebné a výsledek obstojí všude.
     *
     * <p>Obě data jsou tu už dřív spočítaná — nové se nezjišťuje nic. „Až do“ je konec platnosti
     * certifikátu vybrané autority časových razítek, který si aplikace pamatuje od chvíle, kdy se
     * zdroj nastavoval; „jinak skončí“ je konec ověřitelnosti tohoto prvku, tedy zároveň lhůta,
     * do kdy je úkon třeba provést.
     */
    private void preservationText(VBox body, SignatureSummary sig, java.util.Date tsaUntil) {
        String gen = gendered(sig, "tohoto podpisu", "této pečeti", "tohoto časového razítka");
        java.time.Instant sigUntil = sig.getVerifiableUntil();

        body.getChildren().add(continuityText("Možnost ověřit platnost " + gen
                + (tsaUntil != null
                    ? " lze zachovat (resp. prodloužit) až do " + shortDate(tsaUntil)
                      + " (což je dáno aktuálně nastaveným zdrojem časových razítek)."
                    : " lze zachovat (resp. prodloužit); dokdy, se ukáže až podle certifikátu"
                      + " vybraného zdroje časových razítek.")
                + (sigUntil != null
                    ? " Jinak tato možnost skončí " + shortDateTime(sigUntil) + "."
                    : "")));

        // Zdroj, jehož certifikát končí dřív než dosavadní ověřitelnost, by lhůtu neposunul.
        // Potkáno na dokumentu platném do roku 2034 se zdrojem, jehož certifikát končí 2029.
        if (tsaUntil != null && sigUntil != null && !tsaUntil.toInstant().isAfter(sigUntil)) {
            body.getChildren().add(continuityNote(
                    "Certifikát vybraného zdroje časových razítek ale platí jen do "
                    + shortDate(tsaUntil) + ", tedy dříve, než dosavadní možnost ověření končí. "
                    + "Úkon by ji tím dál neposunul — vyplatí se vybrat zdroj, jehož certifikát "
                    + "platí déle."));
        }

        body.getChildren().add(continuityText(
                "Aplikace AnEP má nyní k dispozici vše potřebné pro úkon zachování (prodloužení). "
                + (sigUntil != null
                    ? "Tento úkon je ale třeba provést nejpozději " + shortDateTime(sigUntil) + ". "
                    : "Tento úkon je ale třeba provést včas. ")
                + "Důvodem je omezená dostupnost revokačních informací v čase. Nejlépe je provést "
                + "potřebný úkon ihned."));

        String selfName = gendered(sig, "„samonosný“ podpis", "„samonosná“ pečeť",
                "„samonosné“ časové razítko");
        String rel = gendered(sig, "který", "která", "které");
        String what = gendered(sig, "podpisu", "pečeti", "časového razítka");
        body.getChildren().add(continuityText(
                "Výsledkem bude " + selfName + ", " + rel + " si v sobě ponese vše potřebné pro "
                + "ověření své platnosti i později v čase"
                + (tsaUntil != null ? " (až do " + shortDateTime(tsaUntil) + ")" : "")
                + ". Díky tomu by platnost " + what + " mělo být možné ověřit pomocí všech "
                + "programových nástrojů a služeb. Tedy i na CzechPOINTech (pro potřeby "
                + "autorizované konverze), na podatelnách OVM, či v programech jako je Adobe "
                + "Acrobat Reader či Signer. Stejně jako pomocí služby DSS či na validátorech, "
                + "které z DSS vycházejí."));
    }

    /**
     * Text pro prodloužení: platí jen podle standardů, a tak to i zůstane.
     *
     * <p>Bez dat, a je to tak správně. Prodloužení se nabízí právě tam, kde certifikát posledního
     * razítka už skončil a prvek stojí na shovívavosti norem — na tom, že za službu ručí
     * důvěryhodný seznam až do sunsetu. Kdy sunset přijde, nikdo předem neví, takže lhůta k úkonu
     * se spočítat nedá; jediné poctivé sdělení je „co nejdříve“.
     */
    private void prolongationText(VBox body, SignatureSummary sig) {
        String subj = gendered(sig, "Tento podpis", "Tato pečeť", "Toto časové razítko");
        String adj = gendered(sig, "platný", "platnou", "platné");
        String atThis = gendered(sig, "u tohoto podpisu", "u této pečeti",
                "u tohoto časového razítka");
        String poss = gendered(sig, "jeho", "její", "jeho");
        String what = gendered(sig, "podpisu", "pečeti", "časového razítka");

        body.getChildren().add(continuityText(subj + " je dnes možné ověřit jako " + adj
                + " – ale jen v rámci služeb pro ověřování platnosti (tzv. validátorů), které "
                + "pracují s časovými razítky podle technických standardů. Tedy například v rámci "
                + "validátorů na bázi knihovny DSS. Nikoli třeba na CzechPOINTu, většině podatelen "
                + "OVM, či v programu Signer."));

        body.getChildren().add(continuityText(
                "Do kdy bude tato možnost dostupná, není možné dopředu stanovit. Je to dáno tím, "
                + "že technické standardy hodnotí platnost (kvalifikovaných elektronických) "
                + "časových razítek podle toho, zda je služba, v rámci které bylo konkrétní "
                + "razítko vydáno, stále zapsána na tzv. důvěryhodných seznamech jako služba "
                + "s kvalifikovaným statusem. Po celou tuto dobu lze časové razítko stále hodnotit "
                + "jako platné, i když v mezidobí již mohl expirovat certifikát, na kterém je "
                + "časové razítko založeno. Vše skončí až s tzv. sunsetem (doslova západem "
                + "slunce), kdy bude kvalifikovaný status příslušné službě odebrán."));

        body.getChildren().add(continuityText(
                "Naproti tomu validátory jako CzechPOINT, většina podatelen OVM či program Signer "
                + "hodnotí platnost časových razítek pouze podle doby platnosti certifikátu, na "
                + "kterém je časové razítko založeno. Takže po jeho expiraci (po skončení řádné "
                + "doby jeho platnosti) takovéto časové razítko již není hodnoceno jako platné, "
                + "a nemůže sloužit jako důkaz existence " + what + " v čase (jako PoE). K tomu "
                + "došlo i " + atThis + ", a proto již tyto validátory neověří " + poss
                + " platnost (ale musí konstatovat, že pro ně zůstává stav platnosti neznámý)."));

        body.getChildren().add(continuityText(
                "Provedením úkonu zachování (resp. prodloužení) bude " + atThis + " zachována "
                + "stejná možnost ověření platnosti. Tedy jen u těch služeb, které hodnotí časová "
                + "razítka podle tzv. sunsetu. Nikoli u těch, které se striktně řídí řádnou dobou "
                + "platnosti certifikátu, na kterém je časové razítko založeno."));

        body.getChildren().add(continuityText(
                "Určit lhůtu, do které musí být potřebný úkon zachování proveden, není dnes "
                + "možné. Vhodné je ale provést potřebný úkon co nejdříve."));
    }

    /**
     * Text pro přerazítkování: ověřit to jde už dnes jen někde, a tak to i zůstane.
     *
     * <p>Data jsou tatáž jako u uchování a mají tentýž původ. Co se liší, je dosah výsledku:
     * revokační údaje se vloží tak, jak jsou, včetně těch, které přísné čtení norem neuznává, takže
     * se prodlužuje právě ta možnost ověření, která dnes existuje — ne širší.
     */
    private void restampingText(VBox body, SignatureSummary sig, java.util.Date tsaUntil) {
        String gen = gendered(sig, "tohoto podpisu", "této pečeti", "tohoto časového razítka");
        java.time.Instant sigUntil = sig.getVerifiableUntil();

        body.getChildren().add(continuityText("Možnost ověřit platnost " + gen
                + (tsaUntil != null
                    ? " lze zachovat (resp. prodloužit) až do " + shortDate(tsaUntil)
                      + " (což je dáno aktuálně nastaveným zdrojem časových razítek)."
                    : " lze zachovat (resp. prodloužit); dokdy, se ukáže až podle certifikátu"
                      + " vybraného zdroje časových razítek.")
                + (sigUntil != null
                    ? " Jinak tato možnost skončí " + shortDateTime(sigUntil) + "."
                    : "")));

        body.getChildren().add(continuityText(
                "Již dnes je ale možné ověřit platnost " + gen + " jen pomocí takových nástrojů "
                + "a služeb pro ověření (tzv. validátorů), které mohou čerpat chybějící revokační "
                + "informace z vlastních zdrojů, případně využívají i takové revokační informace "
                + "(typicky vložené přímo v ověřovaných podpisech/dokumentech), které by podle "
                + "standardů již neměly být využívány. Tedy např. na CzechPOINTech (pro účely "
                + "autorizované konverze), na většině podatelen OVM, v programu Signer a dalších. "
                + "Nikoli na validátorech, které nemají vlastní zdroje revokačních informací, nebo "
                + "se striktně drží standardů, pokud jde o práci s revokačními informacemi."));

        body.getChildren().add(continuityText(
                "Aplikace AnEP při zachování (resp. prodloužení) přidá všechny chybějící revokační "
                + "informace, které má k dispozici, a přidá i další časové razítko. Tím se "
                + "prodlouží stejná možnost ověření i do "
                + (tsaUntil != null ? shortDate(tsaUntil)
                    : "konce platnosti certifikátu vybraného zdroje časových razítek")
                + ". Tedy „jen někde“, kde použitý validátor bude mít k dispozici chybějící "
                + "revokační informace, nebo bude ochoten využít i takové, které by podle "
                + "standardů již neměly být využívány."));

        body.getChildren().add(continuityText(
                "Potřebný úkon (pro zachování) je " + gendered(sig, "u tohoto podpisu",
                        "u této pečeti", "u tohoto časového razítka") + " nutné provést "
                + (sigUntil != null ? "nejpozději do " + shortDateTime(sigUntil) + ". " : "včas. ")
                + "Důvodem je omezená dostupnost revokačních informací v čase. Nejlépe je provést "
                + "příslušný úkon ihned."));
    }

    /**
     * Text pro zafixování: ověřit už to nejde, zbývá uchovat podklady pro znalce.
     *
     * <p>Píše se jen tam, kde prvek neobstál — jeho první věta je o tom, že platnost ověřit nelze,
     * a prvku, který obstál a jen na něj razítko nedosáhne, by lhala.
     *
     * <p>Datum v názvu balíčku se skutečně tvoří z konce platnosti certifikátu autority
     * ({@code fixationSuffix} v {@link #runFixation()}), takže se tu neslibuje nic, co by soubor
     * pak nenesl.
     */
    private void fixationText(VBox body, SignatureSummary sig, java.util.Date tsaUntil,
                              String obstacle) {
        String gen = gendered(sig, "tohoto podpisu", "této pečeti", "tohoto časového razítka");
        String what = gendered(sig, "podpisu", "pečeti", "časového razítka");

        body.getChildren().add(continuityText("Platnost " + gen + " dnes již není možné ověřit. "
                + "Tudíž ani zachovat, resp. prodloužit v čase."));
        // Důvod, proč to nejde. Ve větě výše chybí a čtenáři, který ho neuvidí ani tady, nezbude
        // než ho hledat v Rozboru.
        body.getChildren().add(continuityNote("Důvod: " + obstacle + "."));

        body.getChildren().add(continuityText(
                "Co možné je, je připravit podklady pro budoucí posouzení " + what + " i celého "
                + "dokumentu znalcem. Typicky soudním znalcem, pro potřeby řešení nějakého sporu."));

        forensicPackageTail(body, tsaUntil);
    }

    /**
     * Závěr o forenzním balíčku — co vznikne a jak se to bude jmenovat.
     *
     * <p>Společný pro čtvrtou i šestou variantu, protože je v obou doslova týž. Dvě kopie by se
     * při první úpravě rozešly a čtenář by pak podle toho, kudy se k textu dostal, četl o téže
     * věci dvakrát jinak.
     */
    private void forensicPackageTail(VBox body, java.util.Date tsaUntil) {
        body.getChildren().add(continuityText(
                "Aplikace AnEP může shromáždit všechny jí dostupné informace, které by znalec mohl "
                + "v budoucnu využít, společně se samotným dokumentem je zafixovat v čase – "
                + "vytvořením tzv. forenzního balíčku. Cílem je to, aby znalec při svém zkoumání "
                + "mohl mít jistotu, že v době zafixování všechny podklady v balíčku již "
                + "existovaly, a od té doby se nijak nezměnily. Konkrétní provedení je takové, že "
                + "aplikace AnEP vytvoří ASiC-E kontejner, do kterého vše vloží, a ten opatří "
                + "časovým razítkem."));

        body.getChildren().add(continuityText(
                "Jméno tohoto kontejneru je stejné jako jméno dokumentu, pouze je opatřeno "
                + "prefixem (předponou) „FB_“ a sufixem (příponou) „Do_"
                + (tsaUntil != null ? fixationDateInName(tsaUntil) : "DD_MM_RRRR")
                + "“. Datum v sufixu udává, do kdy trvá řádná doba platnosti certifikátu časového "
                + "razítka."));
    }

    /**
     * Šestá varianta: podpis ověří jen nástroj, který kombinuje obě čtení.
     *
     * <p>Nabízí se obojí — zachování i balíček — a je to promyšlené, ne rozpačité. Zachování sem
     * nevrátí validátory, které razítka posuzují podle platnosti certifikátů; mezera v řadě je
     * u nich trvalá. Udrží ale to, co dnes platí: nové razítko od služby, která má status teď,
     * dokládá, že celý ten stav existoval, dokud ho měla i služba stará — a ten důkaz přežije
     * sunset, jímž jinak zmizí jediná cesta k dnešnímu verdiktu.
     *
     * <p>Datum expirace certifikátu posledního razítka je v modelu k dispozici
     * ({@code TimestampEntry.notAfter}). Datum expirace respondéra už ne: plní se jen tam, kde ho
     * přísný verdikt uvede mezi důvody, a tady je verdikt platný — proto o něm text mluví bez data.
     */
    private void combinedFactorsText(VBox body, SignatureSummary sig, java.util.Date tsaUntil) {
        String gen = gendered(sig, "tohoto podpisu", "této pečeti", "tohoto časového razítka");
        String what = gendered(sig, "podpisu", "pečeti", "časového razítka");

        body.getChildren().add(continuityText(
                "Aplikace AnEP dnes dokáže ověřit platnost " + gen + "."));
        body.getChildren().add(continuityText(
                "Je to ale výsledek souběhu dvou faktorů, ke kterému v rámci jiných nástrojů "
                + "a služeb nedochází současně:"));

        java.time.Instant tsuExpiry = lastTimestampCertExpiry(sig);
        body.getChildren().add(bulletParagraph(
                "poslednímu časovému razítku již expiroval jeho certifikát"
                + (tsuExpiry != null ? " (zde konkrétně " + shortDateTime(tsuExpiry) + ")" : "")
                + ". Díky ověřování podle standardů je ale v AnEP i takovéto časové razítko stále "
                + "považováno za platné."));
        body.getChildren().add(bulletParagraph(
                "ke kontrole revokace byly v AnEP využity revokační informace od vydavatele, "
                + "jehož certifikát k rozhodnému okamžiku neplatil, díky povolení příslušné "
                + "možnosti (zde konkrétně OCSP odpověď od OCSP responderu, jehož certifikát "
                + "buď už vypršel, nebo — ověřuje-li se k okamžiku v minulosti — tehdy ještě "
                + "vydaný nebyl)."));

        body.getChildren().add(continuityText(
                "První faktor je splněn u validátorů, které se při ověřování striktně drží "
                + "technických standardů. Pak ale nepřipouští využití revokačních informací od "
                + "vydavatele, jehož certifikát k rozhodnému okamžiku neplatil."));

        body.getChildren().add(continuityText(
                "Druhý faktor je splněn u validátorů, které jsou tolerantnější vůči zdrojům "
                + "revokačních informací, případně je mohou čerpat z vlastních zásob (jde např. "
                + "o CzechPOINTy, většinu podatelen OVM, program Signer a další). Takovéto "
                + "validátory ale zase hodnotí časová razítka striktně podle doby platnosti jejich "
                + "certifikátu. Takže jakmile tento certifikát expiruje, časové razítko není "
                + "hodnoceno jako platné a nemůže sloužit jako důkaz o existenci v čase "
                + "(PoE, Proof of Existence)."));

        body.getChildren().add(continuityText(
                "Souběh obou faktorů je možný u aplikace AnEP, a je možné, že budou existovat "
                + "i takové validátory, které jej také umožní. Proto je zde nabízena možnost "
                + "zachování (resp. prodloužení) ověřitelnosti, byť vyžadující právě popsaný "
                + "souběh."));

        body.getChildren().add(continuityText(
                "Další možností je připravit podklady pro budoucí posouzení " + what + " i celého "
                + "dokumentu znalcem. Typicky soudním znalcem, pro potřeby řešení nějakého sporu."));

        forensicPackageTail(body, tsaUntil);
    }

    /** Konec platnosti certifikátu autority, která vydala nejnovější razítko tohoto prvku. */
```
