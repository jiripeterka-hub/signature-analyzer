package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import cz.digitalnikontinuita.anep.model.TimestampChain;
import cz.digitalnikontinuita.anep.model.ValidationCourse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Do kdy půjde platnost prvku ověřit — třemi různýma očima.
 *
 * <h2>Proč tři čtení a ne jedno</h2>
 *
 * <p>„Do kdy je dokument ověřitelný“ není vlastnost dokumentu, nýbrž vztah mezi dokumentem
 * a tím, kdo ho ověřuje. Tytéž bajty vydrží u jednoho nástroje do sunsetu služby, u druhého do
 * expirace certifikátu razítkovací autority a u třetího jen do konce platnosti podpisového
 * certifikátu. Jediné datum by tedy muselo mlčky vybrat jednoho čtenáře a ostatním lhát.
 *
 * <ul>
 *   <li>{@link #anep} — aplikace AnEP <b>způsobem, jakým se právě ověřovalo</b>: se zapnutými
 *       ústupky, s vlastním úložištěm CRL, s vlastními kotvami důvěry, se zadaným časem.</li>
 *   <li>{@link #standards} — validátor, který se drží standardů a žádnou z těch odchylek nemá.</li>
 *   <li>{@link #tolerant} — okruh CzechPOINTu: vlastní zásoba revokačních informací, shovívavost
 *       k SHA-1, k post-expiračním údajům i k nereferenčním formátům, ale razítka posuzovaná
 *       podle řádné doby platnosti jejich certifikátů, ne podle sunsetu. To poslední je model
 *       odvozený z chování, ne pravidlo, které by ty nástroje kdekoli vyhlašovaly — co k němu
 *       je a co není změřeno, stojí u {@code AdvisorView.isTooLate}.</li>
 * </ul>
 *
 * <h2>Co určuje datum</h2>
 *
 * <p>Prvek visí v čase na posledním článku, který ho drží, a lhůtu určuje ten z článků, který
 * povolí nejdřív:
 *
 * <ol>
 *   <li><b>Důkaz existence.</b> Je-li platné časové razítko, je rozhodný okamžik ukotven
 *       v minulosti a podpisový certifikát smí být dávno po expiraci. Není-li žádné, ověřuje se
 *       k aktuálnímu času a vše končí s platností podpisového certifikátu.</li>
 *   <li><b>Dostupnost revokačních informací.</b> Údaj vložený v dokumentu nezvětrá a údaj ve
 *       vlastním úložišti zůstane ležet; údaj stažený ze sítě je dostupný jen tak dlouho, dokud ho
 *       vydavatel zveřejňuje — konvenčně do konce platnosti certifikátu, o kterém vypovídá.
 *       Odtud {@link SignatureSummary#getAugmentationDeadline()}.</li>
 *   <li><b>Uznatelnost vloženého revokačního údaje.</b> Že údaj zůstane v souboru, ještě
 *       neznamená, že ho půjde uznat: po expiraci certifikátu, kterým je podepsán, je to údaj od
 *       vydavatele s prošlým certifikátem, a takový standardy nepřipouštějí. Vyváže z toho jen
 *       důkaz existence — časové razítko nad celým souborem. Odtud
 *       {@link SignatureSummary#getUnprovenRevocationDeadline()}.</li>
 *   <li><b>Uznání razítka.</b> Podle standardů drží kvalifikované razítko až do sunsetu služby,
 *       což je datum, které nikdo předem nezná; podle druhého čtení končí s certifikátem
 *       razítkovací autority.</li>
 * </ol>
 *
 * <p>Čistá funkce nálezu, oddělená od stavby bloků, aby se dala změřit na sbírce stejně jako
 * {@link AdvisorView#shouldOfferOtherValidators}.
 */
public final class ContinuityHorizon {

    private ContinuityHorizon() { }

    /** Jak dopadla otázka „do kdy“ pro jeden okruh nástrojů. */
    public enum Kind {
        /** Ověřit lze a je známo dokdy. */
        UNTIL,
        /** Ověřit lze, ale datum stanovit nelze. */
        OPEN,
        /** Ověřit už nelze — platnost by zůstala neznámá. */
        LOST,
        /** Neplatnost je prokázána; na tom čas nic nezmění. */
        INVALID
    }

    /** Co lhůtu určuje. Nese to text v těle bloku, samotné datum by bez toho nic nevysvětlilo. */
    public enum Limit {
        /** Řádná doba platnosti podpisového certifikátu. */
        SIGNING_CERT,
        /** Dostupnost revokačních informací, které nejsou po ruce natrvalo. */
        REVOCATION,
        /**
         * Vložený revokační údaj bez vlastního důkazu existence — lhůtu určuje konec platnosti
         * certifikátu, kterým je podepsán. Bajty v souboru zůstanou, uznat je ale po expiraci
         * jejich vydavatele nepůjde jinak než ústupkem.
         */
        REVOCATION_PROOF,
        /** Řádná doba platnosti certifikátu autority posledního použitelného razítka. */
        TIMESTAMP_CERT,
        /**
         * Totéž u <b>kvalifikovaného</b> razítka, jehož certifikát dosud platí — a to je jiná
         * věta, ne jiné datum. Do konce platnosti certifikátu se obě čtení pravidel shodnou
         * a datum je pevné; po něm může razítko podle standardů platit dál, dokud si služba drží
         * kvalifikovaný status. Slíbit se dá jen ta pevná část, dohadovat sunset ne.
         */
        QUALIFIED_TIMESTAMP_CERT,
        /** Sunset služby, která razítko vydala — datum se předem neví. */
        SUNSET,
        /**
         * Podpisový certifikát je sám kotvou důvěry. Jeho doba platnosti se pak neposuzuje
         * (EN 319 102-1 kotvu nekontroluje) a závěr drží status služby, jejíž identitou ho
         * důvěryhodný seznam vede — datum se předem neví, stejně jako u sunsetu.
         */
        TRUST_ANCHOR,
        /** Ověřuje se k pevně zadanému okamžiku v minulosti, který se sám nemění. */
        FIXED_TIME,
        /** Nic z výše uvedeného; datum se stanovit nedá. */
        NONE
    }

    /**
     * @param kind  jak otázka dopadla
     * @param when  datum u {@link Kind#UNTIL}; u {@link Kind#LOST} okamžik, kdy to naposledy šlo,
     *              není-li znám, {@code null}; jinak {@code null}
     * @param limit co lhůtu určilo (i u {@link Kind#OPEN}, kde vysvětluje, proč datum není)
     */
    public record Horizon(Kind kind, Instant when, Limit limit) {

        /** Ověřit lze — ať už se datum stanovit dá, nebo ne. */
        public boolean reachable() { return kind == Kind.UNTIL || kind == Kind.OPEN; }

        static Horizon until(Instant when, Limit limit) {
            return when == null ? new Horizon(Kind.OPEN, null, Limit.NONE)
                                : new Horizon(Kind.UNTIL, when, limit);
        }
    }

    /**
     * V čem se právě proběhlé ověření odchýlilo od striktního čtení standardů.
     *
     * <p>Odchylky jsou dvojího směru a ten rozdíl je podstatný: ústupky, vlastní úložiště CRL,
     * vlastní kotvy a zadaný čas jsou <b>shovívavost</b> a validátor podle standardů se s nimi
     * nesrovná; naproti tomu vypnuté „Ověřování časových razítek podle standardů“ je
     * <b>přísnost</b> nad rámec standardů a takový validátor tam dopadne <em>lépe</em> než AnEP.
     *
     * @param decisiveLenient ústupky, o které se opřel kladný verdikt
     *                        ({@link SignatureSummary#getDecisiveSettings()})
     * @param enabledLenient  ústupky zapnuté pro tento běh; u prvku, který neobstál, je to jediné,
     *                        co se o nich ví — rozhodující ústupky se počítají jen u kladného
     *                        verdiktu, takže prázdný seznam tam nedokazuje, že nic nehrálo roli
     * @param customAnchor    důvěra stojí na kotvě z vlastního úložiště certifikátů
     * @param anchorName      jméno té kotvy, je-li známo
     * @param customCrl       závěr se opřel o CRL z vlastního úložiště
     * @param customTime      okamžik, k němuž se ověřovalo, byl zadán ručně
     * @param strictTimestamps razítka se posuzovala podle platnosti certifikátu, ne podle sunsetu
     */
    public record Deviations(List<String> decisiveLenient, List<String> enabledLenient,
                             boolean measured, boolean customAnchor, String anchorName,
                             boolean customCrl, Instant customTime, boolean strictTimestamps) {

        public Deviations {
            decisiveLenient = decisiveLenient != null ? List.copyOf(decisiveLenient) : List.of();
            enabledLenient = enabledLenient != null ? List.copyOf(enabledLenient) : List.of();
        }

        /**
         * Ústupky tak, jak o nich text smí mluvit.
         *
         * <p>Tam, kde se měřilo, jsou to ty <b>rozhodující</b> — ty, bez kterých by závěr
         * nevznikl. Kde se neměřilo, prázdno neznamená „nic nehrálo roli“, nýbrž „neptali
         * jsme se“, a vyjmenuje se, co bylo <b>zapnuté</b>; text o tom pak mluví jako
         * o povolení, ne o uplatnění.
         *
         * <p>Neměří se u nekladného verdiktu ({@code computeDecisiveSettings} tam vrací rovnou
         * prázdný seznam), ale ani u všech kladných: měření může selhat a prvek, jehož kladný
         * závěr vydal až druhý průchod k odvozenému okamžiku, v něm není nikdy. Odpovídá na to
         * {@code SignatureSummary.isDecisiveSettingsMeasured()}, ne sám verdikt.
         */
        public List<String> namedLenient() {
            return measured ? decisiveLenient : enabledLenient;
        }

        /** Shovívavost, kterou validátor podle standardů nesdílí. */
        public boolean lenient() {
            return !namedLenient().isEmpty() || customAnchor || customCrl || customTime != null;
        }

        /** Cokoli, co odlišuje tenhle běh od striktního čtení — brána pro blok o standardech. */
        public boolean any() { return lenient() || strictTimestamps; }
    }

    // ── Vstupy, které platí pro celý běh ─────────────────────────────────────────────────────

    /**
     * Nastavení, za kterého ověření proběhlo.
     *
     * <p>Čte se v okamžiku stavby bloků, tedy dřív, než {@code MainView} zavolá
     * {@code AppSettings.resetValidationPolicy()} — validační politika platí na jedno ověření
     * a po něm se vrací do výchozího stavu. Kdyby se sem sáhlo později, popisoval by blok
     * pravidla, podle kterých se neověřovalo.
     */
    public record Run(boolean timestampStandardsMode, List<String> enabledLenient,
                      Instant customValidationTime) {

        public Run {
            enabledLenient = enabledLenient != null ? List.copyOf(enabledLenient) : List.of();
        }
    }

    // ── Tři čtení ────────────────────────────────────────────────────────────────────────────

    /**
     * Aplikace AnEP způsobem, jakým se právě ověřovalo.
     *
     * <p>Postupuje se od článku, na kterém prvek visí, k tomu, co povolí dřív. Kladný verdikt je
     * podmínkou: kde se platnost prokázat nepodařilo, není co prodlužovat — a datum spočítané
     * z certifikátů by čtenáři slibovalo lhůtu k něčemu, co se nestalo.
     */
    public static Horizon anep(SignatureSummary sig, String verdict, Run run) {
        if ("TOTAL_FAILED".equals(verdict)) return new Horizon(Kind.INVALID, null, Limit.NONE);
        if (!"TOTAL_PASSED".equals(verdict)) return new Horizon(Kind.LOST, null, Limit.NONE);

        // Samostatné časové razítko je svým vlastním důkazem existence a žádné jiné pod sebou
        // nemá — getAllTimestamps() je u něj prázdné. Bez téhle větve by spadlo do případu „nic
        // neprokazuje dřívější existenci" a mluvilo se o podpisovém certifikátu, který u něj není.
        if (isTimestampElement(sig)) {
            if (!sig.isQualified()) {
                return tightened(Horizon.until(sig.getCertNotAfter(), Limit.TIMESTAMP_CERT), sig);
            }
            return tightened(elapsed(sig.getCertNotAfter())
                    ? new Horizon(Kind.OPEN, null, Limit.SUNSET)
                    : Horizon.until(sig.getCertNotAfter(), Limit.QUALIFIED_TIMESTAMP_CERT), sig);
        }

        // Podpisový certifikát, který je sám kotvou důvěry. Ověřovatel se u kotvy neptá na dobu
        // platnosti ani na revokaci, takže tu nedrží nic z článků níže: ani konec platnosti
        // certifikátu, ani razítko, ani dostupnost revokačních údajů. Drží to jen status služby
        // v důvěryhodném seznamu (DSS kotvě přiřkne období, kdy je služba granted), a kdy skončí,
        // se předem neví. Bez téhle větve spadl prvek do „žádné razítko" a záhlaví hlásilo konec
        // platnosti certifikátu — u protokolu SecuSign „Do 8. 3. 2018 — již uplynulo" nad pečetí,
        // kterou týž běh právě ověřil jako platnou.
        if (sig.isAnchorCert()) return new Horizon(Kind.OPEN, null, Limit.TRUST_ANCHOR);

        Instant base;
        Limit limit;
        TimestampEntry poe = newestProof(sig, TimestampEntry::passed);
        // Prvek bez vlastního razítka, jehož existenci dokládá prvek, který ho kryje (situace 54).
        // Otázka zní „jak dlouho bude uznatelný důkaz existence" — a tím důkazem je právě to cizí
        // razítko, takže se počítá z něj. Bez toho spadl výpočet do větve „žádné razítko", vzal za
        // mez konec platnosti podpisového certifikátu a blok pod ním psal, že se platnost posuzuje
        // k aktuálnímu okamžiku, ačkoli se posuzovala k okamžiku o dva roky staršímu.
        if (poe == null) poe = sig.getCoveringProofTimestamp();
        if (poe == null) {
            if (run.customValidationTime() != null) {
                // Ověřuje se k pevnému okamžiku v minulosti. Ten se sám nemění, takže expirace
                // podpisového certifikátu na výsledku nic nezmění — zopakuje-li se ověření se
                // stejně zadaným časem, dopadne stejně.
                base = null;
                limit = Limit.FIXED_TIME;
            } else {
                base = sig.getCertNotAfter();
                limit = Limit.SIGNING_CERT;
            }
        } else if (poe.qualified()) {
            // Kvalifikované razítko nakonec drží důvěryhodný seznam, ne certifikát pod ním —
            // ale teprve <b>až</b> certifikát doslouží. Dokud platí, je datum pevné a obě čtení
            // pravidel se na něm shodnou; teprve za ním začíná pásmo, které končí sunsetem a to
            // se předem neví. Říct „datum nelze stanovit" už teď by zatajilo jistou část
            // odpovědi — a přesně tak vypadala chyba na „T kvalifikovany ICA.pdf", kde
            // Rozšířený přehled hlásil rok 2034 a tenhle blok datum vůbec neuvedl.
            Instant chainEnd = chainEnd(sig, poe);
            if (elapsed(chainEnd)) {
                base = null;
                limit = Limit.SUNSET;
            } else {
                base = chainEnd;
                limit = Limit.QUALIFIED_TIMESTAMP_CERT;
            }
        } else {
            base = chainEnd(sig, poe);
            limit = Limit.TIMESTAMP_CERT;
        }

        Instant revocation = perishableRevocation(sig);
        if (revocation != null && (base == null || revocation.isBefore(base))) {
            base = revocation;
            limit = Limit.REVOCATION;
        }
        return tightened(base == null ? new Horizon(Kind.OPEN, null, limit)
                                      : Horizon.until(base, limit), sig);
    }

    /**
     * Zkrácení lhůty o článek, který se dosud nesledoval: <b>vložený revokační údaj bez vlastního
     * důkazu existence</b>.
     *
     * <p>Doplněno 30. 8. 2026. Vložený údaj vypadá jako to, co nezvětrá — a bajty v souboru
     * opravdu zůstanou. Uznat ho ale jde jen tak dlouho, dokud platí certifikát, kterým je
     * podepsán: pak už je to „údaj od vydavatele, jemuž vypršel certifikát“, tedy případ, který
     * standardy nepřipouštějí a AnEP propouští jen ústupkem. Podpisové razítko tomu nepomůže —
     * kryje podpisovou hodnotu, ne pole s revokačními údaji; teprve archivní nebo dokumentové
     * razítko dá takovému údaji důkaz existence, a pak {@code getUnprovenRevocationDeadline()}
     * nic nevrací.
     *
     * <p>Změřeno na úředním dokumentu: PAdES-BASELINE-T od PostSignum s OCSP
     * v {@code SignedData.crls}. Blok hlásil lhůtu 14. 2. 2032 podle certifikátu razítkovací
     * autority, ačkoli respondér, na kterém závěr o podpisovém certifikátu stojí, platí jen do
     * 23. 3. 2027.
     *
     * <p>Není to strop, nýbrž druhá cesta vedle sítě, a proto se v {@code ReportMapper} bere
     * <b>maximum</b>: dokud platí certifikát, o kterém údaj vypovídá, dá se stáhnout čerstvý —
     * podepsaný už nástupcem respondéra. Rozhoduje tedy pozdější z obou dat. Krátkoleský
     * respondér pod dlouholetým podpisovým certifikátem by jinak hlásil lhůtu o měsíce dřív, než
     * je pravda; potkáno na „PAdES B-LT kvalifikovany PS neviditelny.pdf“ (respondér do
     * 23. 3. 2027, podpisový certifikát do 18. 5. 2027).
     *
     * <p>Uplatní se i na lhůtu, která už uplynula, a na otevřený horizont (sunset): na rozdíl od
     * dostupnosti stahovaných údajů se tenhle článek dnešním úspěšným ověřením nevyvrací —
     * ověření prošlo právě proto, že aplikace ten ústupek povoluje.
     */
    private static Horizon tightened(Horizon h, SignatureSummary sig) {
        if (h.kind() != Kind.UNTIL && h.kind() != Kind.OPEN) return h;
        SignatureSummary.AugmentationDeadline d = sig.getUnprovenRevocationDeadline();
        if (d == null || d.when() == null) return h;
        if (elapsed(d.when())) return h;
        if (h.kind() == Kind.UNTIL && !d.when().isBefore(h.when())) return h;
        return new Horizon(Kind.UNTIL, d.when(), Limit.REVOCATION_PROOF);
    }

    /**
     * Odkdy je k ověření potřeba ústupek — nebo {@code null}, když není.
     *
     * <p>Uplynulá lhůta na revokačních podkladech <b>není horizont</b> a {@link #tightened} ji do
     * něj proto nepouští. Říká něco jiného: ne „potud AnEP ověří“, nýbrž „od tohohle dne je
     * k tomu potřeba ústupek“. AnEP ověří i zítra — ústupek s časem nemizí — a datum v minulosti
     * by v záhlaví tvrdilo opak. Změřeno na „T kvalifikovany PS razitko PS 21 12.pdf“: blok
     * hlásil „do 5. 12. 2022 — již uplynulo“, ačkoli týž běh právě prokázal, že ověření
     * (s ústupkem) dnes prochází a razítko ho drží nejméně do 18. 10. 2027.
     *
     * <p>Táž úvaha, jakou u druhého revokačního článku dělá {@link #perishableRevocation}: běh,
     * který právě proběhl, uplynulou lhůtu sám vyvrátí. Rozdíl je jen v tom, čím ji vyvrátí —
     * tam dostupností údaje, tady ústupkem — a to patří do věty, ne do data.
     *
     * <p>Bere se {@link SignatureSummary#getAugmentationDeadline()}, tedy minimum přes celý řetěz:
     * ověření bez ústupku potřebuje podklady ke <b>všem</b> certifikátům, a stačí tedy jeden,
     * který už je nemá kde vzít.
     */
    public static Instant concessionNeededSince(SignatureSummary sig) {
        SignatureSummary.AugmentationDeadline d = sig.getAugmentationDeadline() != null
                ? sig.getAugmentationDeadline() : sig.getUnprovenRevocationDeadline();
        if (d == null || d.when() == null) return null;
        return elapsed(d.when()) ? d.when() : null;
    }

    /**
     * Validátor, který se drží standardů a nemá nic z místních odchylek.
     *
     * <p>Tři možnosti a všechny tři nastávají:
     *
     * <ul>
     *   <li><b>Shovívavost byla v ceně verdiktu</b> — ústupek, vlastní CRL, vlastní kotva nebo
     *       zadaný čas. Přísný validátor tohle nemá, takže u něj platnost zůstane neznámá. Tvrdit
     *       jinak by znamenalo slíbit výsledek, který stojí na něčem, co ten nástroj nezná.</li>
     *   <li><b>AnEP byl naopak přísnější</b> (razítka podle certifikátů). Pak validátor podle
     *       standardů uspěje tam, kde zdejší ověření skončilo na neznámé platnosti — kvalifikované
     *       razítko mu poslouží jako důkaz existence až do sunsetu.</li>
     *   <li><b>Odchylky se neuplatnily</b> — týž výsledek jako {@link #anep}.</li>
     * </ul>
     */
    public static Horizon standards(SignatureSummary sig, String verdict, Run run,
                                     Deviations dev) {
        if ("TOTAL_FAILED".equals(verdict)) return new Horizon(Kind.INVALID, null, Limit.NONE);
        if (dev.lenient()) {
            // Nejen „nedokáže“, ale i <b>dokdy dokázal</b>. Datum dává smysl jen u ústupků na
            // revokačních informacích: ty jsou o materiálu, který do jistého dne byl dostupný a pak
            // přestal. U SHA-1 nebo nereferenčního formátu žádný takový den není — ty jsou
            // vlastností samotného podpisu od začátku a datum by si čtenář vyložil jako lhůtu,
            // kterou zmeškal.
            boolean revocationRule = dev.namedLenient().stream()
                    .anyMatch(ContinuityHorizon::remediableByRevocationData);
            Instant last = revocationRule ? concessionNeededSince(sig) : null;
            return new Horizon(Kind.LOST, last, last != null ? Limit.REVOCATION_PROOF
                                                             : Limit.NONE);
        }
        if (dev.strictTimestamps()) {
            // Zpřísnění dělá až karta: verdikt sníží na neznámý prvku, jehož razítko by podle
            // standardů obstálo. Tady se počítá to, co by vyšlo bez něj — proto se do výpočtu
            // posílá kladný verdikt a razítka se berou taková, jaká jsou.
            return anep(sig, "TOTAL_PASSED", new Run(true, run.enabledLenient(),
                    run.customValidationTime()));
        }
        return anep(sig, verdict, run);
    }

    /**
     * Okruh CzechPOINTu — validátory s vlastní zásobou revokačních informací.
     *
     * <p>Počítá se z nálezu, ne ze zdejšího verdiktu, a to je poučení z dřívějška: text, který
     * mlčky předpokládal, že cizí nástroj uzná totéž co AnEP, se na razítku od služby s odebraným
     * kvalifikovaným statusem mýlil. Proto se razítka berou podle {@link TimestampEntry#intact()},
     * tedy podle neporušenosti tokenu, ne podle důvěry k němu.
     *
     * <p>Co si tyhle nástroje odpustí, je vyjmenované v {@link ContinuityElementBlocks}; co si
     * neodpustí, jsou vady, které revokační zásoba nespraví — {@code HOPELESS} důvody a situace,
     * a vlastní kotva důvěry, kterou nemají odkud vzít.
     *
     * <p>Zjednodušení, které stojí za přiznání: bere se konec platnosti certifikátu razítkovací
     * autority, ne nejbližší konec v celém řetězci nad ním. Certifikát TSU bývá z celého řetězce
     * nejkratší, takže rozdíl je řídký; kde není, vyjde odhad shovívavější, a text o něm mluví
     * v možnosti, ne v jistotě.
     *
     * <p><b>Druhé přiznání, tentokrát k datu samotnému.</b> Že tenhle okruh přestane ověřovat
     * s koncem platnosti certifikátu razítka, je odhad z chování, ne jejich vyhlášené pravidlo.
     * Osmnáct dokumentů předložených Úschovně Czech POINT 2. 9. 2026 potvrdilo, u kterých
     * dokumentů to dopadne dobře a u kterých špatně (14 shod ze 16 vypovídajících), ale ne to,
     * <i>proč</i>: v jedné hlášce označili za platné razítko s certifikátem propadlým tři roky
     * předtím a v jiné uznali podpis, jehož razítko sami zavrhli. Datum odsud je tedy dobrý
     * odhad, ne slib — a text nad ním o něm nesmí mluvit v oznamovacím způsobu.
     */
    public static Horizon tolerant(SignatureSummary sig, String verdict) {
        // Záporný verdikt sem obvykle patří beze zbytku — jenže situace 53 je výjimka, a to
        // měřená: podpisová hodnota se neověřila kvůli vadě uvnitř podpisu, obsah je nedotčený
        // a tenhle okruh nástrojů takový podpis přijme (nad „certifikacni a dva uznavane
        // 2015 10 28 do 2026 10.pdf" to potvrdily Adobe Reader, Signer i CzechPOINT). Odpověď
        // „neplatnost potvrdí i oni" by u něj byla nepravda, kterou si čtenář vyvrátí prvním
        // pokusem jinde; horizont se proto počítá jako u kteréhokoli jiného dokumentu.
        if ("TOTAL_FAILED".equals(verdict) && !valueDefectOnly(sig)) {
            return new Horizon(Kind.INVALID, null, Limit.NONE);
        }
        // Před beznadějnými vadami, aby u SHA3-224 (důvod 35) padla ta konkrétnější odpověď:
        // tenhle okruh nástrojů podpis neověří kvůli rodině SHA3 jako celku, ne kvůli tomu, že
        // ji neuvádějí standardy. Text bloku to pozná z {@link #unsupportedDigest}.
        if (unsupportedDigest(sig)) return new Horizon(Kind.LOST, null, Limit.NONE);
        if (hopeless(sig)) return new Horizon(Kind.LOST, null, Limit.NONE);
        if (sig.isTrustedViaCustomStore()) return new Horizon(Kind.LOST, null, Limit.NONE);

        Instant now = Instant.now();
        // Kam sahá řada razítek, ne kam sahá to poslední. Mladší razítko s kratším certifikátem
        // starší nezkracuje — striktnímu validátoru stačí kterékoli razítko kryjící podpis,
        // jehož certifikát dosud platí. Změřeno na Úschovně Czech POINT 18. 9. 2026 (viz
        // {@link cz.digitalnikontinuita.anep.model.TimestampChain}); do té doby se tu bralo
        // {@code newestProof(...).notAfter()} a nad „podpis s razitkem-extended-pades-baseline
        // -lta.pdf“ z toho vyšlo „platnost dnes již ověřit nedokáží“, ačkoli CzechPOINT ověřil.
        Instant reach = TimestampChain.reachFromStart(strictChain(sig));
        if (reach != null) {
            // Řada razítek s mezerou tenhle okruh nástrojů nepřenese. Razítko posuzují podle
            // řádné doby platnosti jeho certifikátu, takže článek, jehož předchůdci certifikát
            // už skončil, jim důkaz existence podpisu nedá — a bez něj u expirovaného
            // podpisového certifikátu stav platnosti zůstane neznámý.
            //
            // Signálem je chybějící situace 31 („each subsequent timestamp produced before the
            // previous TSA cert's notAfter"), tedy táž, o kterou se opírá dialog prodloužení.
            // Bez téhle podmínky slíbil blok u zafixovaného dokumentu ověření do
            // 6. 3. 2029, ačkoli o dva bloky níž stálo, že úkon těmhle nástrojům ověřitelnost
            // nevrátí — dvě věty na jedné kartě, které si odporovaly.
            //
            // Vadí to jen u expirovaného podpisového certifikátu: dokud platí, ověřují
            // k aktuálnímu okamžiku a žádný důkaz existence nepotřebují. A jen u podpisů —
            // samostatné razítko žádnou řadu netvoří a situaci 31 nedostává nikdy.
            if (brokenChainStopsTolerant(sig)) {
                return new Horizon(Kind.LOST, null, Limit.TIMESTAMP_CERT);
            }
            return reach.isAfter(now)
                    ? Horizon.until(reach, Limit.TIMESTAMP_CERT)
                    : new Horizon(Kind.LOST, null, Limit.TIMESTAMP_CERT);
        }
        Instant certUntil = sig.getCertNotAfter();
        if (certUntil == null) return new Horizon(Kind.LOST, null, Limit.NONE);
        // U samostatného razítka je to certifikát razítkovací autority, ne podpisový — sunset
        // tenhle okruh nástrojů nezná, takže rozhoduje jeho řádná doba platnosti.
        Limit limit = isTimestampElement(sig) ? Limit.TIMESTAMP_CERT : Limit.SIGNING_CERT;
        return certUntil.isAfter(now)
                ? Horizon.until(certUntil, limit)
                : new Horizon(Kind.LOST, null, limit);
    }

    /**
     * Přerušená řada razítek nad expirovaným podpisovým certifikátem — případ, kdy okruh
     * nástrojů se „striktním“ hodnocením razítek platnost neověří vůbec.
     *
     * <p>Vyčleněno, aby se na tutéž otázku dalo odpovědět i v textu, který ten výsledek
     * vysvětluje: dvě kopie téhle podmínky by se rozešly a blok by pak tvrdil něco jiného, než
     * spočítal horizont.
     */
    /**
     * Liší se obě čtení <b>měřítkem na časová razítka</b>?
     *
     * <p>Neporušené razítko, které přesto neobstálo, je toho jediný a přesný příznak: zdejší
     * ověření se ptá, zda služba, která je vydala, má v důvěryhodných seznamech stále
     * kvalifikovaný status, kdežto okruh CzechPOINTu se ptá jen na řádnou dobu platnosti
     * certifikátu, na kterém razítko stojí.
     *
     * <p>Bydlí to tady, protože na tutéž otázku odpovídají tři místa — brána přerazítkování,
     * text o tom, proč se nabízí zrovna ono, a text o tom, kdo výsledek ověří. Tři kopie téhle
     * podmínky by se rozešly; přesně to už jednou stálo {@code shouldOfferOtherValidators} jednu
     * opravu.
     */
    static boolean timestampJudgedDifferently(SignatureSummary sig) {
        return sig.getAllTimestamps().stream().anyMatch(ts -> ts.intact() && !ts.passed());
    }

    static boolean brokenChainStopsTolerant(SignatureSummary sig) {
        if (isTimestampElement(sig)) return false;
        // Dokud podpisový certifikát platí, ověřuje tenhle okruh k aktuálnímu okamžiku a žádný
        // důkaz existence nepotřebuje — mezera v řadě mu pak nevadí.
        Instant certUntil = sig.getCertNotAfter();
        if (certUntil == null || certUntil.isAfter(Instant.now())) return false;

        java.util.List<TimestampEntry> chain = strictChain(sig);
        if (chain.isEmpty()) return false;

        // První článek musí vzniknout ještě za platnosti podpisového certifikátu, a řada pak
        // musí držet pohromadě. Táž dvojice podmínek jako u situace 31, jen nad jiným sítem
        // razítek — a souvislost počítá {@link TimestampChain}, ne porovnání sousedů.
        if (!chain.get(0).time().isBefore(certUntil)) return true;
        return !TimestampChain.unbroken(chain);
    }

    /**
     * Razítka tak, jak je vidí <b>okruh CzechPOINTu</b>: neporušená, kryjící prvek, seřazená.
     *
     * <p>Bez ohledu na zdejší verdikt. Opřít se o situaci 31 nešlo — ta se počítá jen z razítek,
     * která obstála v AnEP, takže u razítka odmítnutého kvůli sunsetu chybí, ačkoli pro tyhle
     * nástroje je řada v pořádku (potkáno na třech existujících testech).
     *
     * <h2>Razítko VRI se sem nepočítá — ani to kryté</h2>
     *
     * <p>Zdejší ({@link #anep}) čtení kryté razítko VRI za důkaz existence bere, a je to podle
     * standardů správně: dokumentové razítko nad ním dokazuje, že token existoval, a DSS z toho
     * rozhodný okamžik odvodí. Okruh CzechPOINTu ale žádné razítko VRI <b>nevidí</b>: leží
     * v nepodepsané přírůstkové aktualizaci uvnitř {@code /DSS//VRI}, není to podpisový slovník,
     * a jejich rozbor vypisuje právě jen podpisové slovníky.
     *
     * <p><b>Změřeno 18. 9. 2026 dvojicí dokumentů, které se liší jediným — druhem toho razítka:</b>
     *
     * <ul>
     *   <li>`bajecny svet\2023\05 02\PAdES B-B a dokumentove razitko.pdf` — podpis bez vlastního
     *       razítka, <b>dokumentové</b> razítko z 2. 5. 2023, podpisový certifikát platil do
     *       25. 5. 2023. Úschovna Czech POINT: <b>uznala</b>.</li>
     *   <li>`special\casove razitko v dokumentu\PAdES uznavany 2019 s razitkem v dokumentu.pdf` —
     *       podpis bez vlastního razítka, <b>razítko VRI</b> z 8. 10. 2019 (certifikát platil do
     *       23. 10. 2019) a dokumentové až z 18. 4. 2025. Úschovna: <b>odmítla</b> se slovy, že
     *       podpis „není označen časovým razítkem“ — ačkoli razítko z roku 2025 vypsala vedle
     *       toho jako samostatné.</li>
     * </ul>
     *
     * <p>První z nich vyvrací starší domněnku, že tenhle okruh nástrojů nepoužije dokumentové
     * razítko; druhý ukazuje, co doopravdy nepoužije.
     *
     * <p><b>Rozsah změny</b> (271 souborů korpusu s položkou {@code /TS} ve VRI, 415 prvků):
     * o slib přišlo 8 souborů, a je to osmkrát týž podpis z roku 2019 — právě ten odmítnutý;
     * žádný z nich není TOTAL_PASSED. Dalším třem se dosah zkrátil o 1,6 hodiny a o 21 sekund,
     * protože certifikát razítka VRI tam přežíval sousední dokumentové. Zdejšího čtení ani
     * {@code AdvisorView.isTooLate} se to nedotklo.
     */
    private static java.util.List<TimestampEntry> strictChain(SignatureSummary sig) {
        java.util.List<TimestampEntry> chain = new java.util.ArrayList<>();
        for (TimestampEntry ts : sig.getAllTimestamps()) {
            if (ts.time() == null || !ts.intact()) continue;
            if ("content".equals(ts.tsKind())) continue;
            if (ts.tsKind() != null && ts.tsKind().startsWith("vri")) continue;
            chain.add(ts);
        }
        chain.sort(java.util.Comparator.comparing(TimestampEntry::time));
        return chain;
    }

    /**
     * Dá se tenhle ústupek obejít tím, že se validátoru dodá jiný revokační údaj?
     *
     * <p>Rozdíl, který jsem měl zpočátku špatně a opravil ho uživatel. Ústupky na revokačních
     * informacích — <b>po expiraci certifikátu</b> a <b>od expirovaného vydavatele</b> — neříkají,
     * že se pravidlo musí ohnout; říkají, že po ruce byl jen takovýhle údaj. Kdo sežene seznam CRL
     * z doby, kdy certifikát ještě platil, obejde se bez ústupku úplně — a přesně na to je vlastní
     * úložiště CRL. Naproti tomu SHA-1 a nereferenční formát jsou vlastnosti samotného podpisu
     * a žádný doplněný soubor je nezmění.
     *
     * <p>Porovnává se přes {@code settingName()} samotného výčtu, ne podle podřetězce: týmž
     * řetězcem je klíčovaná i mapa popisů v Rozšířeném přehledu, takže hledat v něm slovo by se
     * rozpadlo při první změně názvu.
     */
    public static boolean remediableByRevocationData(String settingName) {
        return ReportMapper.Relaxation.NEWER_OCSP.settingName().equals(settingName)
                || ReportMapper.Relaxation.EXPIRED_REVOCATION_SOURCES.settingName()
                        .equals(settingName);
    }

    /**
     * Kolik času musí zásah přidat, aby se o něm dalo mluvit jako o posunutí ověřitelnosti.
     *
     * <p>Rok je úsudek, ne pravidlo z normy, a stojí na tom, co se tím čtenáři slíbí: zásah,
     * po kterém by se týž úkon musel do roka opakovat, není řešení, nýbrž odklad. Text už
     * získaný čas vždy vyčíslí, takže číslo mluví samo a hranice rozhoduje jen o tom, kdy se
     * přidá varování.
     */
    public static final java.time.Duration MEANINGFUL_GAIN = java.time.Duration.ofDays(365);

    /**
     * Posune vybraný zdroj časových razítek ověřitelnost o něco, co stálo za řeč?
     *
     * <p>Ostře nerovné porovnání tu nestačí a je to poznatek z praxe: nad
     * „PAdES B-T kvalifikovany ICA s popisem_Do_02_05_2032.pdf“ končí certifikát zdroje
     * <b>48 minut</b> po dosavadní ověřitelnosti dokumentu. Formálně je to posun, prakticky nic —
     * a žádné varování se neukázalo, protože podmínka zněla „není později“.
     *
     * @param docUntil kam dokument dosáhne i bez zásahu; {@code null} znamená „není s čím měřit“
     * @param tsaUntil konec platnosti certifikátu vybraného zdroje razítek
     */
    public static boolean worthTheAction(Instant docUntil, Instant tsaUntil) {
        if (tsaUntil == null) return true;
        if (docUntil == null) return true;
        return tsaUntil.isAfter(docUntil.plus(MEANINGFUL_GAIN));
    }

    /** Prvek je samostatné časové razítko, ne podpis ani pečeť. */
    public static boolean isTimestampElement(SignatureSummary sig) {
        return "TimeStampToken".equals(sig.getFormat());
    }

    // ── Pomůcky ──────────────────────────────────────────────────────────────────────────────

    /**
     * Co se stane s revokačními informacemi, na kterých závěr stojí.
     *
     * <p>{@code null} znamená „nic nezvětrá“: všechny využité údaje jsou buď vložené v dokumentu,
     * nebo leží ve vlastním úložišti a zůstanou tam. Jinak lhůtu určuje nejbližší konec platnosti
     * certifikátu, ke kterému vložený údaj není — po něm už nemá kdo revokační informaci vydávat.
     *
     * <p>{@code revocationFetchedOnline} se od zrušení předpovědi v bloku „Průběh ověřování“
     * počítalo a nikdo ho nečetl. Tohle je ten návrat, kvůli kterému se nechalo naživu.
     */
    private static Instant perishableRevocation(SignatureSummary sig) {
        ValidationCourse vc = sig.getValidationCourse();
        if (vc == null || !vc.revocationFetchedOnline()) return null;
        // A teď to podstatné: „něco se stáhlo ze sítě" ještě neznamená, že na síti závisí závěr.
        // revocationFetchedOnline() je příznak per záznam a certifikát běžně nese víc údajů
        // najednou. Rozhoduje ten, o který se opřel podpisový certifikát — a ten je k dispozici
        // rozepsaný, s původem „document" / „store" / „online".
        //
        // Bez tohohle kroku hlásil blok u „T kvalifikovany ICA.pdf" lhůtu 22. 9. 2023, ačkoli
        // rozhodující CRL leží ve vlastním úložišti a nikam se neztratí; Rozšířený přehled vedle
        // toho správně uváděl rok 2034.
        if (!dependsOnOnlineRevocation(sig)) return null;

        SignatureSummary.AugmentationDeadline deadline = sig.getAugmentationDeadline();
        if (deadline == null || deadline.when() == null) return null;

        // A druhá polovina téhož: lhůta, která už uplynula, není horizont. Běh, který právě
        // proběhl, ji sám vyvrátil — údaj se dnes stáhnout podařilo, ačkoli podle ní neměl být
        // kde ho vzít. O tom, dokdy to půjde příště, tedy neříká nic a horizont padá na další
        // článek v pořadí. Že je ta závislost na síti křehká, patří do poznámky, ne do data.
        //
        // Změřeno na „T kvalifikovany PS.pdf": blok hlásil 13. 6. 2025, Rozšířený přehled vedle
        // toho správně rok 2034. Táž chyba jako u vlastní CRL, jen z druhé strany.
        return elapsed(deadline.when()) ? null : deadline.when();
    }

    /**
     * Stojí závěr o podpisovém certifikátu na údaji staženém ze sítě?
     *
     * <p>Pak je ověření závislé na tom, že vydavatel údaj zveřejňuje dál — a to nikdo neslíbí.
     * Není to datum (viz výše), je to podmínka; text ji musí přiznat a uchování ji odstraní tím,
     * že údaj vloží do dokumentu.
     */
    public static boolean dependsOnOnlineRevocation(SignatureSummary sig) {
        ValidationCourse vc = sig.getValidationCourse();
        if (vc == null || !vc.revocationFetchedOnline()) return false;
        ValidationCourse.RevocationFact fact = vc.signingCertRevocation();
        return fact == null || "online".equals(fact.origin());
    }

    /**
     * Dokdy sahá řetěz razítek. {@code getVerifiableUntil()} je minimum přes celý řetěz certifikátů
     * a je to ta správná veličina; {@code notAfter} samotné razítkovací autority slouží jen jako
     * záloha, když souhrn nic nenese.
     */
    private static Instant chainEnd(SignatureSummary sig, TimestampEntry poe) {
        return sig.getVerifiableUntil() != null ? sig.getVerifiableUntil() : poe.notAfter();
    }

    private static boolean elapsed(Instant when) {
        return when == null || !when.isAfter(Instant.now());
    }

    /**
     * Nejnovější razítko, které může sloužit jako důkaz existence a projde daným sítem.
     *
     * <p>Vyloučená jsou obsahová razítka (vznikla <b>před</b> podpisem, takže o jeho existenci
     * nevypovídají) a holá razítka VRI (leží v nepodepsané přírůstkové aktualizaci a dají se beze
     * stopy odstranit). Táž dvojice, jakou vylučuje {@code canEstablishBestSignatureTime}.
     */
    private static TimestampEntry newestProof(SignatureSummary sig,
                                               java.util.function.Predicate<TimestampEntry> ok) {
        TimestampEntry newest = null;
        for (TimestampEntry ts : sig.getAllTimestamps()) {
            if (ts.time() == null || !ok.test(ts)) continue;
            if ("content".equals(ts.tsKind()) || "vri_bare".equals(ts.tsKind())) continue;
            if (newest == null || ts.time().isAfter(newest.time())) newest = ts;
        }
        return newest;
    }

    /**
     * Vada je jen v podpisové hodnotě, obsah je nezměněný (situace 53).
     *
     * <p>Rozděleno 3. 9. 2026 ze situace 1. Kdo se řídí polem digestAlgorithm — Adobe Reader,
     * Signer, CzechPOINT — takový podpis ověří; kdo bere doslova signatureAlgorithm (knihovna
     * DSS a tedy i AnEP), ověření neprovede. Pro záložku kontinuity je to rozdíl podstatný:
     * záporný verdikt tu nesmí mluvit za všechny.
     */
    public static boolean valueDefectOnly(SignatureSummary sig) {
        return sig.getDetectedSituations().contains(53);
    }

    /**
     * Podpis stojí na hashovací funkci, kterou CzechPOINT (a nejspíš ani podatelny) zatím
     * nepodporují — rodině SHA3. Údaj uživatele z 4. 10. 2026; AnEP sám SHA3-256 až SHA3-512
     * ověří, takže na kartě by jinak stálo datum, do kdy je ověří i oni, a pod ním nabídka
     * přerazítkování pro ně.
     *
     * <p>Ptá se na otisk podpisu samotného: ten tyhle nástroje spočítat musí vždy. Razítka
     * a certifikáty řetězu se nezkoumají — o tom, zda by je odmítly také, AnEP nic neví.
     */
    public static boolean unsupportedDigest(SignatureSummary sig) {
        return isSha3Family(sig.getDigestAlgorithm());
    }

    /**
     * Patří funkce do rodiny SHA3 (Keccak)? Podle celého názvu, ne podle začátku: dotaz
     * {@code startsWith("SHA3")} chytal i SHA384 z rodiny SHA-2, protože DSS ji jmenuje
     * „SHA384“ bez pomlčky — a karta pak o podpisu SHA-384 tvrdila, že ho CzechPOINT
     * neověří (nález uživatele 5. 10. 2026 nad dokumentem RSA SHA384). DSS píše rodinu SHA3
     * s pomlčkou („SHA3-256“); bez ní a s podtržítkem se přijímá taky, aby název z jiného
     * zdroje nevypadl.
     */
    static boolean isSha3Family(String digest) {
        if (digest == null) return false;
        String d = digest.trim().toUpperCase(java.util.Locale.ROOT);
        return d.matches("SHA3[-_]?(224|256|384|512)") || d.startsWith("SHAKE");
    }

    /** Vada, se kterou nepomůže žádná zásoba revokačních informací. */
    private static boolean hopeless(SignatureSummary sig) {
        return sig.getDetectedReasons().stream().anyMatch(AdvisorView.HOPELESS_REASONS::contains)
                || sig.getDetectedSituations().stream()
                        .anyMatch(AdvisorView.HOPELESS_SITUATIONS::contains);
    }

    /**
     * Odchylky tohoto běhu u tohoto prvku.
     *
     * @param strictTimestamps zda kartě právě tenhle prvek snížilo verdikt zpřísněné posuzování
     *                         razítek — počítá to {@code AdvisorView.show()} a předává sem, protože
     *                         {@code sig.getIndication()} o tom přebití neví
     * @param verdict          spolu s {@code sig.isDecisiveSettingsMeasured()} rozhoduje
     *                         o tom, zda se rozhodující ústupky vůbec změřily; viz
     *                         {@link Deviations#namedLenient()}
     */
    public static Deviations deviations(SignatureSummary sig, Run run, boolean strictTimestamps,
                                         String verdict) {
        ValidationCourse vc = sig.getValidationCourse();
        List<String> decisive = new ArrayList<>(sig.getDecisiveSettings());
        // „Změřeno“ není totéž co „kladně ověřeno“. Měření se dělá jen u kladného verdiktu,
        // ale i tam může chybět — prvek, jehož kladný závěr vydal až druhý průchod k odvozenému
        // okamžiku, v něm není, a nedokončené měření není v něm taky. Do 19. 9. 2026 se sem
        // dosazovalo samotné TOTAL_PASSED, takže prázdný výčet z nezměřeného běhu vypadal jako
        // zjištění „ústupky se neuplatnily“ a blok o standardech sliboval, že cizí validátor
        // dojde k témuž.
        return new Deviations(decisive, run.enabledLenient(),
                "TOTAL_PASSED".equals(verdict) && sig.isDecisiveSettingsMeasured(),
                sig.isTrustedViaCustomStore(), sig.getCustomTrustAnchorName(),
                vc != null && vc.customCrlDecisive(),
                run.customValidationTime(), strictTimestamps);
    }
}
