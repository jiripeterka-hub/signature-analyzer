package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TimeFormat;
import cz.digitalnikontinuita.anep.core.TsaProfile;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationCourse;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Deviations;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Horizon;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Kind;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Limit;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Run;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.time.Instant;

/**
 * Prostřední část záložky Digitální kontinuita — rozbalovací bloky pro jeden prvek a pro
 * dokument jako celek.
 *
 * <h2>Podle čeho jsou rozdělené</h2>
 *
 * <p>Do 28. 8. 2026 tu stál jeden blok na prvek a dělil se podle toho, <b>který úkon</b> se
 * nabízí: uchování, prodloužení, přerazítkování, zafixování. Čtenář se ale neptá, jak se úkon
 * jmenuje, nýbrž <b>kde a do kdy jeho dokument obstojí</b> — a to je otázka, na kterou má každý
 * okruh nástrojů jinou odpověď. Nové dělení je proto podle čtenáře výsledku:
 *
 * <ol>
 *   <li><b>AnEP</b> tak, jak se právě ověřovalo — se všemi ústupky a vlastními zásobami.</li>
 *   <li><b>Validátory podle standardů</b> — jen tam, kde se zdejší běh od standardů odchýlil;
 *       kde se neodchýlil, řekl by tenhle blok podruhé totéž co první.</li>
 *   <li><b>CzechPOINT, podatelny a další</b> — vlastní zásoba revokačních informací, ale razítka
 *       posuzovaná podle platnosti certifikátů.</li>
 *   <li><b>AnEP nabízí možnost…</b> — jméno varianty a termín stojí v nadpisu, protože je to
 *       jediný blok, po kterém se něco dělá. Od 8. 9. 2026 jsou <b>sbalené všechny</b>: čtenář
 *       má nejprve vidět tlačítko a nadpisy, podrobnosti až na vyžádání.</li>
 * </ol>
 *
 * <h2>Kde který blok stojí</h2>
 *
 * <p>Tři bloky výše odpovídají na otázku <b>o prvku</b> („kdo a do kdy ho ověří“) a stojí u něj
 * vždycky. Dva bloky o úkonu — „Jak dlouho je možné zachovat ověřitelnost?“ a „AnEP nabízí
 * možnost…“ — jsou naproti tomu otázka <b>o souboru</b>: úkon se provádí jednou pro celý
 * dokument, takže u dokumentu s několika prvky stojí jednou, pod výčtem prvků, a to, čím se
 * prvky liší, říká výčet uvnitř nich ({@link #addPerElement}). U dokumentu s jediným prvkem
 * zůstávají u něj — dokumentová část by tam říkala totéž podruhé. Změna z 9. 9. 2026, pokyn
 * uživatele; do té doby se obě otázky vysázely u každého prvku zvlášť a čtenář z pěti nabídek
 * pod sebou nepoznal, že jde o jedno tlačítko.
 *
 * <p>Data počítá {@link ContinuityHorizon}; tahle třída je jen sází do vět. Rozdělení není
 * kosmetické: dokud se počítalo a psalo na jednom místě, opravovala se formulace tam, kde měl
 * být opraven výpočet.
 *
 * <h2>Čím se řídí tón</h2>
 *
 * <p>O AnEP a o validátorech podle standardů se mluví <b>v jistotě</b> — jejich pravidla jsou
 * známá a úplná. O okruhu CzechPOINTu <b>v možnosti</b> („mohly by“), protože aplikace nezná ani
 * jejich verzi, ani nastavení, ani obsah jejich zásob revokačních informací. Je to pravidlo od
 * uživatele a platí i tam, kde se odpověď zdá jistá.
 */
final class ContinuityElementBlocks {

    private ContinuityElementBlocks() { }

    /** Které cesty brána pro tenhle dokument otevřela. Platí pro soubor, ne pro prvek. */
    record Gates(boolean preserve, boolean prolong, boolean restamp, boolean fix) { }

    /**
     * Vybraný zdroj časových razítek tak, jak ho bloky potřebují znát.
     *
     * <p>Tři údaje, které spolu cestovaly jako tři parametry, dokud nepřibyl třetí. Sloučeny
     * 18. 9. 2026 a rovnou to opravilo chybu, kterou tři parametry umožňovaly: „je zdroj
     * vybrán?" se poznávalo podle toho, zda má <b>jméno</b> — jenže jméno je nepovinné, takže
     * zdroj bez názvu vycházel jako nevybraný. Teď to ví {@link #chosen()} od toho, kdo profil
     * předal.
     *
     * @param name    jméno zdroje pro texty, nebo prázdné u zdroje bez názvu
     * @param until   konec platnosti razítkovacího certifikátu, nebo {@code null} („nezjištěno")
     * @param quality co se naměřilo o kvalifikovanosti; viz {@link TsaProfile.Quality}
     */
    record Source(String name, java.util.Date until, TsaProfile.Quality quality,
                  cz.digitalnikontinuita.anep.core.TsaSigningKey.Weakness weakKey) {

        Source(String name, java.util.Date until, TsaProfile.Quality quality) {
            this(name, until, quality, null);
        }

        /** Žádný zdroj vybrán není. */
        static final Source NONE = new Source(null, null, TsaProfile.Quality.UNKNOWN);

        static Source of(TsaProfile profile) {
            return of(profile, null);
        }

        /**
         * @param policy politika ověřování, z jejíž kryptografické sady se pozná slabý klíč
         *               autority (viz {@link #addWeakSourceNote}); {@code null} = nezkoumat
         */
        static Source of(TsaProfile profile,
                         eu.europa.esig.dss.model.policy.ValidationPolicy policy) {
            return profile == null ? NONE
                    : new Source(profile.getName(), profile.getCertNotAfter(),
                            profile.getQuality(),
                            cz.digitalnikontinuita.anep.core.TsaSigningKey.weakness(profile,
                                    policy));
        }

        /**
         * Zdroj popsaný zvenčí — pro sondy a zkoušky, které žádný profil nemají.
         *
         * <p>Kvalifikovanost vychází jako nezjištěná, a to je pro ně ta správná odpověď: texty
         * se pak chovají tak, jak se chovaly vždycky, takže zkouška měří to, co měřit chce,
         * a ne výhradu o zdroji.
         */
        static Source named(String name, java.util.Date until) {
            return new Source(name, until, TsaProfile.Quality.UNKNOWN);
        }

        boolean chosen() { return name != null; }

        /** Má zdroj jméno, kterým se dá v textu nazvat? */
        boolean named() { return name != null && !name.isBlank(); }

        /**
         * Je změřeno, že razítka odtud <b>nejsou</b> kvalifikovaná?
         *
         * <p>Jen změřeno. Nezjištěno se sem nepočítá a texty pak mluví jako vždycky — tvrdit
         * nekvalifikovanost z toho, že se něco nepodařilo změřit, by bylo horší než mlčet.
         */
        boolean unqualified() { return quality == TsaProfile.Quality.UNQUALIFIED; }
    }

    /**
     * „(kvalifikovaným elektronickým) časovým razítkem“ — a u změřeně nekvalifikovaného zdroje
     * bez toho příslibu.
     *
     * <p>Závorky v tom obvyklém znění nejsou ozdoba: říkají „zpravidla kvalifikovaným, podle toho,
     * co je vybráno". Nad zdrojem, o kterém se <b>ví</b>, že kvalifikovaný není, by ale i závorka
     * slibovala něco, co se nestane. Podrobnosti pak doříká vlastní odstavec
     * ({@link #addUnqualifiedSourceNote}); tady jde jen o to nelhát v jedné jmenné frázi.
     */
    static String stampInstr(Source tsa) {
        return tsa.unqualified()
                ? "časovým razítkem" : "(kvalifikovaným elektronickým) časovým razítkem";
    }

    /** Totéž ve druhém pádě. */
    static String stampGen(Source tsa) {
        return tsa.unqualified()
                ? "časového razítka" : "(kvalifikovaného elektronického) časového razítka";
    }

    /**
     * Celá sada bloků pro jeden prvek, i s oběma bloky o úkonu — tvar pro dokument, ve kterém
     * je prvek jediný. Při souběhu se staví přes {@link #build(Element, Gates, String,
     * java.util.Date, java.util.function.Consumer, boolean)} s {@code actionBlocks = false}.
     *
     * @param verdict          verdikt tak, jak ho ukazuje karta v Analýze — předává se, protože
     *                         karta ho umí přebít a {@code sig.getIndication()} o tom neví
     * @param strictTimestamps zda kartě snížilo verdikt zpřísněné posuzování razítek
     * @param obstacle         proč se na prvek nedosáhne, nebo {@code null}
     * @param tsa              vybraný zdroj časových razítek; {@link Source#NONE}, není-li žádný
     * @param open             otevře výklad na začátku záložky, kam odkazy míří; {@code null}
     *                         u sond, které záložku nestaví celou — pak se odkazy vysází jako
     *                         prostý text
     */
    static VBox build(SignatureSummary sig, String verdict, Run run, boolean strictTimestamps,
                      Gates gates, String obstacle, boolean actionReaches,
                      Source tsa,
                      java.util.function.Consumer<ContinuityView.Outlook> open) {
        // Bez profilu PDF/A: tahle zkratka slouží sondám a snímkům jednotlivých karet, které
        // shodu s normou nezjišťují. Věta o archivní podobě se v nich tedy nevysází — a nemá.
        return build(element(sig, verdict, run, strictTimestamps, actionReaches, obstacle),
                gates, tsa, open, true, null);
    }

    /**
     * Jeden prvek se vším, co o něm bloky potřebují vědět.
     *
     * <p>Vznikl 9. 9. 2026, kdy se poslední dva bloky při souběhu prvků přestěhovaly pod výčet:
     * tytéž horizonty od té chvíle potřebuje blok u prvku i souhrn za dokument, a počítat je na
     * dvou místech znamená riskovat, že se rozejdou. Počítají se jednou, v {@link #element},
     * a obě strany si je odtud berou.
     */
    record Element(SignatureSummary sig, String verdict, Horizon anep, Horizon standards,
                   Horizon tolerant, Deviations dev, boolean actionReaches, String obstacle,
                   Coverage anepVia, Coverage tolerantVia) {

        Element(SignatureSummary sig, String verdict, Horizon anep, Horizon standards,
                Horizon tolerant, Deviations dev, boolean actionReaches, String obstacle) {
            this(sig, verdict, anep, standards, tolerant, dev, actionReaches, obstacle, null, null);
        }

        /** Týž prvek s horizonty převzatými z pozdějšího razítka; viz {@link #coveredBy}. */
        Element withCoverage(Horizon anep, Coverage anepVia, Horizon tolerant,
                             Coverage tolerantVia) {
            return new Element(sig, verdict, anep, standards, tolerant, dev, actionReaches,
                    obstacle, anepVia, tolerantVia);
        }
    }

    /**
     * Pozdější razítko, od kterého prvek převzal horizont: jeho číslo ve výčtu a ono samo.
     * Je to <b>poslední</b> článek řady, ne nutně ten, který prvek kryje přímo — horizont se po
     * řadě přenáší, a popsat se musí razítko, které ho doopravdy drží.
     */
    record Coverage(int number, SignatureSummary sig) { }

    /** Horizonty prvku. Vstupy jsou tytéž jako u {@link #build}. */
    static Element element(SignatureSummary sig, String verdict, Run run,
                           boolean strictTimestamps, boolean actionReaches, String obstacle) {
        Deviations dev = ContinuityHorizon.deviations(sig, run, strictTimestamps, verdict);
        return new Element(sig, verdict,
                ContinuityHorizon.anep(sig, verdict, run),
                ContinuityHorizon.standards(sig, verdict, run, dev),
                ContinuityHorizon.tolerant(sig, verdict), dev, actionReaches, obstacle);
    }

    /**
     * Razítka, která kryje pozdější razítko téhož dokumentu, převezmou jeho horizont — je-li
     * lepší než vlastní a vzniklo-li to pozdější ještě v době, kdy bylo to dřívější uznatelné.
     *
     * <p>Každý prvek se do 25. 9. 2026 počítal, jako by byl „svým vlastním důkazem existence“.
     * Nad dokumentem se čtyřmi razítky (2014, 2014, 2019, 2024) tak vyšlo razítko č. 2 jako
     * ověřitelné „do 4. 10. 2019 — již uplynulo“ a CzechPOINT u prvních tří „dnes již ověřit
     * nedokáže“, ačkoli řada je souvislá — každé další razítko vzniklo za platnosti certifikátu
     * předchozího — a blok za dokument týmž dechem psal, že celek je ověřitelný do 13. 10. 2030
     * (nález uživatele). Razítko č. 2 dnes obstojí právě proto, že ho kryje razítko č. 3.
     *
     * <p>Podmínka „vzniklo, dokud bylo uznatelné“ se posuzuje zvlášť pro každé čtení: pro AnEP
     * vlastní horizont prvku (u kvalifikovaného razítka po expiraci je otevřený), pro okruh
     * CzechPOINTu konec platnosti certifikátu razítka — ten okruh razítko po expiraci neuzná.
     * Horizont se přenáší od nejmladšího razítka ke staršímu, takže projde celou řadou.
     *
     * @param covers pro index prvku indexy prvků, které ho kryjí (vazba z DSS)
     * @param made   čas vzniku razítka podle indexu prvku; {@code null} u prvku, který razítkem
     *               není
     */
    static java.util.List<Element> coveredBy(java.util.List<Element> elements,
                                             java.util.Map<Integer, java.util.List<Integer>> covers,
                                             java.util.List<Instant> made) {
        java.util.List<Element> out = new java.util.ArrayList<>(elements);
        java.util.List<Integer> order = new java.util.ArrayList<>();
        for (int i = 0; i < out.size(); i++) if (made.get(i) != null) order.add(i);
        order.sort((a, b) -> made.get(b).compareTo(made.get(a)));   // nejmladší napřed
        for (int i : order) {
            Element el = out.get(i);
            if (!"TOTAL_PASSED".equals(el.verdict())) continue;
            Horizon anep = el.anep(), tolerant = el.tolerant();
            Coverage anepVia = null, tolerantVia = null;
            for (int j : covers.getOrDefault(i, java.util.List.of())) {
                Element cov = out.get(j);
                Instant when = made.get(j);
                if (when == null || !"TOTAL_PASSED".equals(cov.verdict())) continue;
                if (stillAcceptable(el.anep(), when) && improves(anep, cov.anep())) {
                    anep = cov.anep();
                    anepVia = cov.anepVia() != null ? cov.anepVia() : new Coverage(j + 1, cov.sig());
                }
                Instant certEnd = el.sig().getCertNotAfter();
                if (certEnd != null && when.isBefore(certEnd)
                        && improves(tolerant, cov.tolerant())) {
                    tolerant = cov.tolerant();
                    tolerantVia = cov.tolerantVia() != null ? cov.tolerantVia()
                            : new Coverage(j + 1, cov.sig());
                }
            }
            if (anepVia != null || tolerantVia != null) {
                out.set(i, el.withCoverage(anep, anepVia != null ? anepVia : el.anepVia(),
                        tolerant, tolerantVia != null ? tolerantVia : el.tolerantVia()));
            }
        }
        return out;
    }

    /** Bylo razítko v okamžiku {@code when} podle tohoto čtení ještě uznatelné? */
    private static boolean stillAcceptable(Horizon own, Instant when) {
        return switch (own.kind()) {
            case OPEN -> true;
            case UNTIL -> when.isBefore(own.when());
            default -> false;
        };
    }

    /**
     * Je horizont kryjícího razítka lepší? Otevřený horizont nahradí jen pevnou mez, ne spodní
     * („nejméně do“) — ta zůstává pravdivá a nese datum, kdežto otevřený ho nenese.
     */
    private static boolean improves(Horizon own, Horizon other) {
        if (!other.reachable()) return false;
        if (!own.reachable()) return true;
        if (own.kind() == Kind.OPEN) return false;
        if (other.kind() == Kind.UNTIL) return other.when().isAfter(own.when());
        return elapsed(own.when()) || !lowerBoundOnly(own);
    }

    /**
     * Bloky pro jeden prvek.
     *
     * @param actionBlocks zda k prvku patří i poslední dva bloky — „Jak dlouho je možné zachovat
     *                     ověřitelnost?“ a „AnEP nabízí možnost…“. <b>Při souběhu prvků
     *                     nepatří:</b> úkon je dokumentový, kdežto tyhle dva bloky stály
     *                     u každého prvku zvlášť a čtenář z pěti nabídek pod sebou nepoznal, že
     *                     jde o jedno tlačítko. Od 9. 9. 2026 tedy stojí jednou, za výčtem prvků
     *                     (viz {@link #documentBlocks}), a jak se úkon projeví na kterém prvku,
     *                     říká výčet uvnitř nich.
     */
    static VBox build(Element el, Gates gates, Source tsa,
                      java.util.function.Consumer<ContinuityView.Outlook> open,
                      boolean actionBlocks, String pdfaProfile) {
        return build(el, gates, tsa, open, actionBlocks, pdfaProfile, null);
    }

    /**
     * @param document analyzovaný soubor — do připomínky do kalendáře na konci posledního bloku;
     *                 {@code null} připomínku nenabídne (sondy, snímky jednotlivých karet)
     */
    static VBox build(Element el, Gates gates, Source tsa,
                      java.util.function.Consumer<ContinuityView.Outlook> open,
                      boolean actionBlocks, String pdfaProfile, java.io.File document) {
        VBox blocks = new VBox(4);
        blocks.getChildren().add(anepBlock(el.sig(), el.anep(), el.dev(), open, el.anepVia()));
        if (el.dev().any()) {
            blocks.getChildren().add(standardsBlock(el.sig(), el.standards(), el.dev()));
        }
        blocks.getChildren().add(tolerantBlock(el.sig(), el.tolerant(), open,
                el.tolerantVia()));
        if (!actionBlocks) return blocks;
        if (el.actionReaches()) {
            // Táž mez, jakou počítá todoBlock() pro lhůtu: pozdější z obou čtení. Podle ní se
            // rozhoduje, jestli má datum z certifikátu vybrané autority v nadpisu co dělat.
            blocks.getChildren().add(preservationReachBlock(anepDeadline(el.anep()),
                    salvageableUntil(el.anep(), el.tolerant()), tsa, gates.preserve(),
                    willEmbed(el), willFix(el)));
        }
        blocks.getChildren().add(todoBlock(el, gates, tsa, pdfaProfile, open, document));
        return blocks;
    }

    // ── 4. Jak dlouho je možné zachovat ověřitelnost ────────────────

    private static final String PRESERVATION_REACH_TITLE =
            "Jak dlouho je možné zachovat ověřitelnost?";

    /**
     * Kam až dosáhne úkon, který se nabízí — a proč právě tam.
     *
     * <p>Odpověď nezávisí na dokumentu, nýbrž na <b>vybraném zdroji časových razítek</b>: nové
     * razítko stojí na certifikátu jeho autority a dál než ten certifikát nedosáhne. Aplikace si
     * ten konec pamatuje od chvíle, kdy se zdroj nastavoval, právě proto, aby se dal napsat sem —
     * zjistit ho jinak než pořízením razítka nelze a čtenář se má rozhodovat předem.
     *
     * <p>Ukazuje se jen tam, kde se úkon opravdu nabízí a dosáhne na tenhle prvek. Nadpis se ptá
     * „jak dlouho je možné zachovat ověřitelnost“ a nad prvkem, u kterého ji zachovat nelze, by
     * si odporoval s blokem pod sebou.
     *
     * <h2>Když zdroj razítek nedosáhne dál než dokument sám</h2>
     *
     * <p>Datum z certifikátu vybrané autority může být <b>stejné nebo dřívější</b> než horizonty
     * ve dvou blocích nad tímto — a pak nadpis „Jak dlouho je možné zachovat ověřitelnost?  Do
     * 6. 3. 2029“ nad blokem hlásícím 1. 12. 2030 čte jako by úkon ověřitelnost <i>zkracoval</i>.
     * Naměřeno na „pecet uznavana 2020 09 09 _preserved_2025-10-13“ (zdroj Izenpe do 6. 3. 2029,
     * dokument do 1. 12. 2030).
     *
     * <p>V takovém případě se <b>datum z nadpisu vypustí</b> (zůstane holá otázka) a poslední
     * věta jde do podmiňovacího způsobu: ne „certifikát, na kterém bude toto razítko založeno“,
     * nýbrž „na kterém by bylo“ — protože žádné takové razítko se pořizovat nemá. Pokyn
     * uživatele z 8. 9. 2026.
     *
     * <p>Mez je {@link #sourceMovesNothing}, tedy táž, jakou se řídí větev {@code backwards}
     * v {@link #addSourceTooShort} o blok níž: „stejné nebo dřívější“. Malý posun (48 minut nad
     * „PAdES B-T kvalifikovany ICA s popisem_Do_02_05_2032.pdf“) sem nepatří — posun to je,
     * datum tedy zůstává a od „jen málo“ je tam poznámka dole.
     *
     * <h2>„Žádný přínos“ ale platilo jen o datu, a ne vždy ani o tom</h2>
     *
     * <p>Nález uživatele (20. 9. 2026): nad „PAdES B-LT kvalifikovany PS neviditelny.pdf“ stálo
     * v tomhle bloku „přidání takového razítka by nemělo žádný přínos“ a hned pod ním blok
     * „AnEP nabízí možnost uchování. Do kdy je třeba ji využít?  Do 23. 3. 2027“. Dvě sdělení,
     * která si čtenář nemá jak srovnat.
     *
     * <p>Pravdivé je to druhé. Úkon totiž <b>neslouží jen k posunutí data</b>: revokační
     * informace nejsou dostupné natrvalo, takže jeho druhá půlka je vloží a ty už vložené
     * zafixuje. A i to datum tu bylo měřeno proti nesprávné mezi — proti <i>nejzazšímu</i>
     * z obou čtení, tedy proti tomu, kam dosáhnou validátory s vlastní zásobou revokačních
     * informací. Úkon přitom zachraňuje to <i>bližší</i>, zdejší čtení podle standardů.
     * {@link #reachAfterAction} a {@link #reachBeforeAction} měří obojí tak, jak to nastane.
     *
     * @param anepReach dokdy dokáže platnost ověřit aplikace sama, tedy to, co úkon zachraňuje
     * @param reach dokdy dokument dosáhne i bez zásahu — pozdější z obou čtení
     *              ({@code salvageableUntil}), nebo {@code null}, je-li jedno z nich otevřené.
     *              Otevřený horizont srovnávat nelze a úkon u něj přínos má: promění „platí,
     *              dokud nepřijde sunset“ v doložený stav.
     * @param preserve nabízí se plnohodnotné uchování. Jen tehdy se dá počítat s tím, že nové
     *              razítko do řady zapadne — brána uchování žádá souvislou řadu (situace 31
     *              i 32), kdežto prodloužení a přerazítkování se nabízejí právě tam, kde
     *              souvislá není.
     * @param willEmbed je co vložit — viz {@link #willEmbed}
     * @param willFix   je co zafixovat — viz {@link #willFix}
     */
    private static VBox preservationReachBlock(Instant anepReach, Instant reach, Source tsa,
                                               boolean preserve, boolean willEmbed,
                                               boolean willFix) {
        boolean materialToSecure = willEmbed || willFix;
        VBox body = body();
        String named = tsa.named() ? " (" + tsa.name() + ")" : "";
        String current = tsa.named() ? " (aktuálně " + tsa.name() + ")" : "";

        if (tsa.until() == null) {
            body.getChildren().add(AdvisorView.continuityText(!tsa.chosen()
                    ? "Žádný zdroj časových razítek zatím není vybrán, takže se nedá říct, kam až"
                      + " by ověřitelnost sahala. Vybrat se dá v nastavení; bez zdroje nelze"
                      + " razítko pořídit vůbec."
                    : "U vybraného zdroje časových razítek" + named + " zatím není známo, do kdy"
                      + " platí certifikát jeho autority, takže se nedá říct, kam až by"
                      + " ověřitelnost sahala. Zjistí se to při pořízení nejbližšího razítka."));
            addUnqualifiedSourceNote(body, tsa);
            addWeakSourceNote(body, tsa);
            return AdvisorView.collapsibleBlock(PRESERVATION_REACH_TITLE, body, false,
                    AdvisorView.BlockTint.BLUE);
        }

        // Nekvalifikovaný zdroj nemá v tomhle bloku co slibovat. Nadpis „Do 12. 2. 2032" a věta
        // „to je také doba, do které bude prodloužena možnost ověření" platí o kvalifikovaném
        // razítku; razítko, které za důkaz existence v čase nikdo nevezme, ověřitelnost
        // neprodlouží ani o den. Datum proto z nadpisu vypadne — týž postup jako u zdroje, který
        // nedosáhne dál než dokument sám.
        if (tsa.unqualified()) {
            body.getChildren().add(AdvisorView.continuityText(
                    "Zachovat ověřitelnost lze přidáním chybějících revokačních informací"
                    + " a dalšího (kvalifikovaného elektronického) časového razítka. To se"
                    + " pořizuje od zdroje, který je vybrán v nastavení" + current + "."));
            addUnqualifiedSourceNote(body, tsa);
            return AdvisorView.collapsibleBlock(PRESERVATION_REACH_TITLE, body, false,
                    AdvisorView.BlockTint.BLUE);
        }

        Instant until = tsa.until().toInstant();
        Instant after = reachAfterAction(reach, tsa.until(), preserve);
        Instant before = reachBeforeAction(anepReach, reach, preserve);
        boolean noGain = sourceMovesNothing(before, after);
        // Řadu razítek nese dokument sám a nové razítko ji nezkrátí: dosah úkonu pak není
        // koncem platnosti certifikátu vybraného zdroje, nýbrž tím pozdějším z obou.
        boolean carriedByChain = !noGain && after.isAfter(until);
        // Ne „všemi validátory": to platí jen u uchování, kdežto prodloužení ověřitelnost
        // nevrátí nástrojům, které razítka posuzují podle platnosti certifikátů, a
        // přerazítkování naopak těm, které se drží standardů (potkáno na zafixovaném
        // dokumentu s razítkem do roku 2029). Odkaz na to, co stojí v blocích výše, je proti
        // vypisování okruhů podle brány univerzální a nemůže se s nimi rozejít.
        body.getChildren().add(AdvisorView.continuityText(
                "Zachovat ověřitelnost lze přidáním chybějících revokačních informací a dalšího"
                + " (kvalifikovaného elektronického) časového razítka. To se pořizuje od zdroje,"
                + " který je vybrán v nastavení" + current + "."
                + (noGain
                    ? " Certifikát, na kterém by toto razítko bylo založeno, je ale platný jen do "
                      + TimeFormat.full(until)
                      + (materialToSecure
                            ? " — přidání takového razítka by datum, do kterého lze platnost"
                              + " ověřit, neposunulo."
                            : " — přidání takového razítka by nemělo žádný přínos.")
                    : carriedByChain ? ""
                    : " Certifikát, na kterém bude toto razítko založeno, je platný do "
                      + TimeFormat.full(until) + " — a to je také doba, do které bude prodloužena"
                      + " možnost ověření výše uvedeným způsobem.")));
        addWeakSourceNote(body, tsa);

        if (carriedByChain) {
            addPreservationPurpose(body, willEmbed, willFix);
            // Proč se dosah nebere z certifikátu vybraného zdroje: nové razítko se přidává
            // k řadě, kterou dokument už nese, a mladší razítko s kratší platností certifikátu
            // ji nezkracuje — totéž říká výklad na začátku záložky a je to změřeno (viz
            // {@link #reachAfterAction}).
            body.getChildren().add(AdvisorView.continuityText(
                    "Certifikát, na kterém by nové razítko bylo založeno, je přitom platný jen do "
                    + TimeFormat.full(until) + ", tedy kratší dobu, než kam dosahuje řada"
                    + " časových razítek, kterou dokument už nese. Mladší razítko ale řadu"
                    + " nezkracuje: ověřitelnost proto zůstane zachována do "
                    + TimeFormat.full(after)
                    + (before != null
                        ? ", a to nově i v aplikaci AnEP a v dalších validátorech, které se drží"
                          + " technických standardů — u těch dnes možnost ověřit platnost končí "
                          + TimeFormat.full(before) + "."
                        : ".")));
            return AdvisorView.collapsibleBlock(PRESERVATION_REACH_TITLE
                    + "  Do " + TimeFormat.full(after), body, false, AdvisorView.BlockTint.BLUE);
        }

        if (noGain) {
            // Datum se nikam neposune, úkon ale pořád má co dělat: vložit to, co je dostupné
            // dnes, a zafixovat to, co v dokumentu leží bez důkazu existence. Bez téhle věty
            // stálo nad nabídkou s konkrétní lhůtou ploché „žádný přínos“ a čtenář si to neměl
            // jak srovnat (nález uživatele, 20. 9. 2026).
            if (materialToSecure) addPreservationPurpose(body, willEmbed, willFix);
            // Datum do nadpisu nepatří: bylo by nižší než v obou blocích nad tímhle a četlo by
            // se jako zkrácení. Zůstane holá otázka a odpověď na ni je ve větě výš.
            return AdvisorView.collapsibleBlock(PRESERVATION_REACH_TITLE, body, false,
                    AdvisorView.BlockTint.BLUE);
        }

        // Poznámka o krátkém zdroji razítek tu stávala a je pryč (8. 9. 2026, pokyn uživatele):
        // říkala doslova totéž co {@code addSourceTooShort()} v bloku o kus níž, jen jinými
        // slovy a se stejnými dvěma daty. Naměřeno na „pecet uznavana 2020 09 09
        // _preserved_2025-10-13“ — obě věty na jedné kartě, obě o zdroji do 6. 3. 2029 proti
        // dokumentu do 1. 12. 2030. Sonda je nechytla: neodporují si, jen se opakují.
        //
        // Zůstala ta druhá, protože je lepší — pokrývá i případ „posune jen málo“ (48 minut nad
        // „PAdES B-T kvalifikovany ICA s popisem_Do_02_05_2032.pdf“), umí větev {@code same} pro
        // dvě shodná data a stojí tam, kde se o úkonu rozhoduje. Aby o tenhle případ nepřišel
        // dokument, u kterého je tolerantní horizont otevřený (tehdy je {@code salvage} null
        // a poznámka dole by se přeskočila), přebírá volání dole zdejší datum jako náhradu.

        return AdvisorView.collapsibleBlock(PRESERVATION_REACH_TITLE
                + "  Do " + TimeFormat.full(until), body, false, AdvisorView.BlockTint.BLUE);
    }

    // ── 1. AnEP ──────────────────────────────────────────────────────────────────────────────

    /**
     * První věta bloku: jak se ověřovalo a co k tomu aplikace použila.
     *
     * <p>Nahradila výčet podmínek („Ověřovalo se přísně podle technických standardů: bez
     * jakýchkoli ústupků, s kotvami důvěry z evropských důvěryhodných seznamů, bez sahání do
     * vlastních zásob…“) plus odrážky. Ten výčet vypisoval i to, co nenastalo, a hlavně
     * nespojoval způsob ověření s tím, co z něj plyne pro lhůtu. Věta proto končí tím, co je
     * <b>proto</b> rozhodující — a druhá věta ({@link #whatDecides}) na to odpoví datem.
     */
    private static String howItWasVerified(SignatureSummary sig, Deviations dev, Horizon h,
                                           boolean actNow) {
        String gen = AdvisorView.genitiveType(sig);
        StringBuilder sb = new StringBuilder("Aplikace AnEP ověřila platnost " + gen + " ");
        if (dev.namedLenient().isEmpty() && !dev.strictTimestamps()) {
            sb.append("podle standardů, bez jakýchkoli ústupků");
        } else if (!dev.namedLenient().isEmpty()) {
            sb.append("s ").append(dev.namedLenient().size() == 1 ? "ústupkem" : "ústupky")
              .append(" oproti standardům (")
              .append(String.join(", ", dev.namedLenient())).append(")");
        } else {
            sb.append("přísněji, než žádají standardy — časová razítka posuzovala podle řádné"
                    + " doby platnosti jejich certifikátů");
        }
        sb.append(materialUsed(sig, dev));
        // Vlastní kotva důvěry a ručně zadaný čas byly dřív v odrážkách pod výčtem podmínek.
        // Ústupky to nejsou, ale výsledek na nich stojí stejně silně — bez nich by věta „podle
        // standardů, bez jakýchkoli ústupků" zamlčela, že se ověřovalo proti kotvě, kterou
        // evropské důvěryhodné seznamy neuvádějí.
        if (dev.customAnchor()) {
            sb.append(", s kotvou důvěry z vlastního úložiště certifikátů")
              .append(dev.anchorName() != null ? " (" + dev.anchorName() + ")" : "");
        }
        if (dev.customTime() != null) {
            sb.append(", a to k ručně zadanému okamžiku ")
              .append(TimeFormat.full(dev.customTime()));
        }
        sb.append('.');

        // Co je proto rozhodující. Bez tohohle spojení by druhá věta začínala datem, o kterém
        // čtenář neví, odkud se vzalo.
        //
        // Kde závěr stojí na údaji staženém on-line, na jehož další dostupnost není spoleh,
        // je rozhodující právě ta dostupnost — a ne to, co říká {@code h.limit()}. Nad
        // „2 podpisy signed.pdf“ tu jinak stálo „Dřívější existenci podpisu prokazuje časové
        // razítko, takže je rozhodující, jak dlouho bude uznatelné ono“: razítko prokazuje
        // existenci podpisu, o možnosti znovu zjistit stav revokace ale neříká nic.
        sb.append(actNow ? " Proto je pro ni rozhodující, jak dlouho budou tyto informace"
                + " dostupné." : switch (h.limit()) {
            case REVOCATION -> " Proto je pro ni rozhodující, jak dlouho budou tyto informace"
                    + " dostupné.";
            // Ne „jak dlouho bude tento vložený údaj uznatelný": vedou sem dvě cesty — vložený
            // údaj a čerstvé stažení — a rozhoduje ta, která vydrží dél. Uznatelnost vloženého
            // údaje může skončit o roky dřív, než skončí možnost stav zjistit.
            case REVOCATION_PROOF -> " Proto je pro ni rozhodující, jak dlouho bude stav revokace "
                    + revocationSubject(sig) + " zjistitelný.";
            case SIGNING_CERT -> " Dřívější existenci " + gen + " přitom neprokazuje žádné časové"
                    + " razítko, takže se platnost posuzuje k aktuálnímu okamžiku.";
            // Ne „všechny potřebné revokační informace jsou v dokumentu vloženy": tyhle limity
            // vznikají z povahy razítka, ne z toho, co dokument nese. U nekvalifikovaného
            // razítka nad podpisem se stahovanou revokací by to bylo tvrzení, které nikdo
            // neověřil — a co dokument nese, stojí ve faktech nad blokem.
            case TIMESTAMP_CERT, QUALIFIED_TIMESTAMP_CERT, SUNSET ->
                    ContinuityHorizon.isTimestampElement(sig)
                    // „Existenci razítka prokazuje razítko" je tautologie. Samostatné razítko je
                    // svým vlastním důkazem a nic pod sebou nemá.
                    ? " Razítko je svým vlastním důkazem existence, takže rozhoduje, jak dlouho"
                      + " bude uznatelné ono samo."
                    // Prvek, jehož důkaz existence nese jiný prvek (situace 54): „prokazuje časové
                    // razítko" by u podpisu bez razítka znělo, jako by ho měl. Mez je přitom táž —
                    // uznatelnost toho cizího razítka.
                    : sig.getCoveringProofTimestamp() != null
                      ? " Dřívější existenci " + gen + " prokazuje časové razítko jiného prvku"
                        + " téhož dokumentu, takže je rozhodující, jak dlouho bude uznatelné ono."
                      : " Dřívější existenci " + gen + " prokazuje časové razítko, takže je"
                        + " rozhodující, jak dlouho bude uznatelné ono.";
            case FIXED_TIME -> " Ověřovalo se k ručně zadanému okamžiku, a ten se sám nemění.";
            case TRUST_ANCHOR -> " Podpisový certifikát je přitom sám kotvou důvěry, takže se"
                    + " neposuzuje ani jeho doba platnosti, ani jeho revokace.";
            case NONE -> "";
        });
        return sb.toString();
    }

    /**
     * Odpověď na „jak dlouho“ tam, kde ji nelze dát datem — a co by platilo, kdyby šla.
     *
     * <p>Znění je návrh uživatele z 9. 9. 2026: ověření závisí na revokační informaci, kterou
     * se teď podařilo stáhnout, do budoucna to ale zaručeno není; dobu proto stanovit nelze;
     * a kdyby ta informace dostupná zůstala, sahala by ověřitelnost do data, které drží
     * článek pojmenovaný v druhé půlce věty.
     *
     * <p>Datum se tedy <b>neztrácí</b>, jen přestává být slibem: čtenář ho má k dispozici
     * i s podmínkou, za které platí. To je celý rozdíl proti stavu, kdy stálo v nadpisu.
     */
    private static String onlineRevocationCaveat(SignatureSummary sig, Horizon h, String gen) {
        StringBuilder sb = new StringBuilder(
                "Tu informaci se teď stáhnout podařilo; že bude dostupná i příště, ale"
                + " zaručeno není. Dobu, do které dokáže AnEP platnost " + gen + " ověřit,"
                + " proto stanovit nelze.");
        if (h.when() != null) {
            String noun = deadlineNoun(sig, h.limit(), h.when());
            sb.append(" Kdyby ta informace dostupná zůstala, dokázala by aplikace ověřit"
                    + " platnost do ").append(TimeFormat.full(h.when()));
            sb.append(noun != null ? " — rozhoduje " + noun + "." : ".");
        }
        return sb.toString();
    }

    /** „a přitom využila revokační informace, stažené on-line od jejich vydavatele“ a obdoby. */
    private static String materialUsed(SignatureSummary sig, Deviations dev) {
        if (dev.customCrl()) {
            return ", a přitom využila seznam CRL ze svého vlastního úložiště";
        }
        cz.digitalnikontinuita.anep.model.ValidationCourse vc = sig.getValidationCourse();
        var fact = vc == null ? null : vc.signingCertRevocation();
        if (fact == null) return "";
        String what = fact.ocsp() ? "odpověď OCSP" : "seznam CRL";
        return switch (fact.origin() == null ? "" : fact.origin()) {
            case "online" -> ", a přitom využila " + what
                    + ", stažen" + (fact.ocsp() ? "ou" : "ý") + " on-line od "
                    + (fact.ocsp() ? "jejího" : "jeho") + " vydavatele";
            case "document" -> ", a přitom využila " + what + " vložen"
                    + (fact.ocsp() ? "ou" : "ý") + " v dokumentu";
            case "store" -> ", a přitom využila " + what + " ze svého vlastního úložiště";
            default -> "";
        };
    }

    /**
     * Čí certifikát to datum drží — certifikát razítka, nebo některé autority nad ním.
     *
     * <p>{@code verifiableUntil} je <b>nejbližší</b> konec platnosti v celém řetězu razítka
     * (viz {@code ReportMapper.chainExpiry}), takže „skončí s jeho certifikátem“ je pravda jen
     * tehdy, když se to datum s koncem platnosti razítka opravdu shoduje. Jinak lhůtu drží
     * někdo nad ním a věta by jmenovala nesprávný certifikát — táž chyba, jaká 7. 9. 2026 vyšla
     * najevo u vložené odpovědi OCSP, jen o řetěz vedle.
     */
    private static String whoseCertificate(SignatureSummary sig, Horizon h) {
        // Samostatné razítko nemá getAllTimestamps() — lhůtu u něj drží jeho vlastní certifikát
        // (getCertNotAfter() je u razítka konec platnosti TSU). Bez téhle větve by se o něm
        // řeklo to mlhavější „nejbližší konec platnosti v řetězu“, ačkoli přesná odpověď je po
        // ruce.
        if (ContinuityHorizon.isTimestampElement(sig)) return "konec platnosti jeho certifikátu";
        Instant stampUntil = null;
        for (var ts : sig.getAllTimestamps()) {
            if ("content".equals(ts.tsKind()) || "vri_bare".equals(ts.tsKind())) continue;
            if (ts.notAfter() != null) stampUntil = ts.notAfter();
        }
        return stampUntil != null && stampUntil.equals(h.when())
                ? "konec platnosti jeho certifikátu"
                : "nejbližší konec platnosti v řetězu, na kterém razítko stojí";
    }

    /** Čí revokační stav se u tohohle prvku zjišťuje — u razítka není podpisový certifikát. */
    private static String revocationSubject(SignatureSummary sig) {
        return ContinuityHorizon.isTimestampElement(sig)
                ? "certifikátu razítkovací autority" : "podpisového certifikátu";
    }

    /**
     * Druhá věta bloku: co lhůtu určuje, do kdy, a co z toho plyne.
     *
     * <p>Nahradila {@code limitExplained()} — dva a půl tisíce znaků výkladu o tom, jak se
     * posuzují razítka a co je sunset. Ten výklad má dnes své místo na začátku záložky; sem
     * patří jen odvození z faktů, která stojí nad blokem.
     */
    private static String whatDecides(SignatureSummary sig, Horizon h, String gen,
                                      boolean elapsed) {
        String date = h.when() == null ? null : TimeFormat.full(h.when());
        // „Právě do" jen tam, kde je datum opravdu mez. U kvalifikovaného razítka je to spodní
        // mez (nadpis bloku proto říká „Nejméně do"), a věta „trvá právě do" by mu odporovala
        // o dva řádky níž — potkáno na „T kvalifikovany ICA rozdil v case.pdf".
        String reach = lowerBoundOnly(h) ? "nejméně do uvedeného data" : "právě do uvedeného data";
        // Uplynulou lhůtu nese minulý čas, ne přívěsek „, které již minulo": to, že datum minulo,
        // říká hned za touhle větou poznámka {@link #elapsedNote}, a to i s tím, co z toho plyne.
        // Dvakrát totéž ve dvou po sobě jdoucích odstavcích našla sonda
        // ContinuityConsistencyScan na šesti dokumentech.
        String closing = date == null
                ? " Do kdy bude platnost " + gen + " takto ověřitelná, stanovit nelze."
                : " Proto možnost ověřit platnost " + gen + " v aplikaci AnEP "
                  + (elapsed ? "trvala " : "trvá ") + reach + ".";
        return switch (h.limit()) {
            // Který certifikát tu lhůtu drží, ví model — a nemusí to být podpisový certifikát,
            // nýbrž kterýkoli v řetězu podpisu i razítek, ke kterému chybí revokační údaje.
            // Vlastní věta o „podpisovém certifikátu" by tedy někdy lhala; ta z modelu jmenuje
            // ten správný (viz ReportMapper.certRole).
            case REVOCATION -> (sig.getAugmentationDeadline() != null
                        // Věta z modelu sama končí tím, co se stane po lhůtě („po expiraci už je
                        // zpravidla nebude kde vzít“), takže se to už nedodává.
                        ? sig.getAugmentationDeadline().reason()
                        : "Jejich dostupnost je zajištěna jen po dobu platnosti certifikátu,"
                          + " ke kterému se vztahují, tedy do " + date
                          + "; později už nemusí být dostupné ani využitelné.")
                    + closing;
            // Věta z modelu, ne vlastní: uznatelnost vloženého údaje a dostupnost čerstvých
            // údajů jsou dvě různé cesty a rozhoduje ta, která vydrží dél. Vlastní věta tu
            // tvrdila, že datum je konec platnosti vydavatele vloženého údaje — a u dokumentu,
            // kde síť vydrží dél (PAdES B-LT uznavany viditelny PS.pdf: respondér do 23. 3.
            // 2027, podpisový certifikát do 15. 9. 2029), to byla nepravda o dva a půl roku.
            case REVOCATION_PROOF -> (sig.getUnprovenRevocationDeadline() != null
                        ? sig.getUnprovenRevocationDeadline().reason()
                        : "Vložený údaj nekryje žádné archivní ani dokumentové časové razítko,"
                          + " takže nemá vlastní důkaz existence a uznat jej lze jen do konce"
                          + " platnosti certifikátu jeho vydavatele, tedy do " + date + ".")
                    + closing;
            case SIGNING_CERT -> "Rozhoduje tedy konec řádné doby platnosti podpisového"
                    + " certifikátu, tedy " + date + ": po něm už ověření k aktuálnímu času"
                    + " neprojde a bude potřeba důkaz existence v dřívější době, který ale"
                    + " zpětně nevznikne." + closing;
            case TIMESTAMP_CERT -> "Razítko není kvalifikované, takže se za ně důvěryhodné"
                    + " seznamy nezaručují; rozhoduje tedy " + whoseCertificate(sig, h) + ", a to"
                    + " je " + date + "." + closing;
            case QUALIFIED_TIMESTAMP_CERT -> "Razítko je kvalifikované, takže je „volnější“"
                    + " hodnocení uzná i po expiraci certifikátu — jak dlouho, se ale předem"
                    + " zjistit nedá (viz [varianta „uchování“ a „prodloužení“](PRESERVE_PROLONG)"
                    + " ve výkladu na začátku záložky). Uvedeno je proto datum, na kterém se obě"
                    + " hodnocení shodnou: " + whoseCertificate(sig, h) + ", tedy " + date + "."
                    + closing;
            case SUNSET -> "Razítko je kvalifikované, takže podle standardů drží až do tzv."
                    + " sunsetu — a ten se předem zjistit nedá; viz"
                    + " [varianta „uchování“ a „prodloužení“](PRESERVE_PROLONG) ve výkladu na"
                    + " začátku záložky. Do té doby je platnost " + gen + " ověřitelná bez"
                    + " omezení.";
            // Kotva z vlastního úložiště žádný status nemá — tam drží závěr jen to, že ji
            // uživatel v úložišti ponechá.
            case TRUST_ANCHOR -> sig.isTrustedViaCustomStore()
                    ? "Kotvou je certifikát z vlastního úložiště aplikace, a ten žádný status ani"
                      + " sunset nemá: dokud v úložišti zůstane, je platnost " + gen + " v aplikaci"
                      + " AnEP ověřitelná bez omezení, a to i po skončení řádné doby platnosti"
                      + " certifikátu."
                    : "Evropský důvěryhodný seznam vede podpisový certifikát jako identitu služby,"
                      + " a dokud ji tam vede jako aktivní (status „granted“), je"
                      + " platnost " + gen + " ověřitelná bez omezení — i po skončení řádné doby"
                      + " platnosti certifikátu a bez časového razítka. Kdy služba status ztratí,"
                      + " se ale předem zjistit nedá, a pak už platnost bez důkazu existence"
                      + " z dřívější doby ověřit nepůjde.";
            case FIXED_TIME -> "Zopakuje-li se ověření se stejně zadaným časem, dopadne stejně,"
                    + " dokud budou po ruce tytéž revokační informace. Závěr ale neříká nic"
                    + " o platnosti k dnešnímu dni.";
            case NONE -> "Určující článek se pojmenovat nedá." + closing;
        };
    }

    /** Věta u razítka, jehož horizont drží pozdější razítko: místo „je svým vlastním důkazem“. */
    private static String coveredVerified(String text, Coverage via) {
        if (via == null) return text;
        return text.replace(" Razítko je svým vlastním důkazem existence, takže rozhoduje, jak dlouho"
                        + " bude uznatelné ono samo.",
                " Razítko je ale kryto pozdějším razítkem — případně řadou dalších — až po razítko č. "
                + via.number() + "; každé z nich vzniklo, dokud bylo to předchozí uznatelné."
                + " Rozhoduje proto, jak dlouho bude uznatelné razítko č. " + via.number() + ".");
    }

    /** Druhá věta bloku — o razítku, které horizont drží, je-li to jiné razítko. */
    private static String decides(SignatureSummary sig, Horizon h, String gen, boolean elapsed,
                                  Coverage via) {
        if (via == null) return whatDecides(sig, h, gen, elapsed);
        String text = whatDecides(via.sig(), h, gen, elapsed);
        return text.startsWith("Razítko ")
                ? "Razítko č. " + via.number() + " " + text.substring("Razítko ".length())
                : text;
    }

    private static VBox anepBlock(SignatureSummary sig, Horizon h, Deviations dev,
                                  java.util.function.Consumer<ContinuityView.Outlook> open,
                                  Coverage via) {
        VBox body = body();
        String gen = AdvisorView.genitiveType(sig);
        // Závěr stojí na údaji, který se teď stáhnout podařilo a příště nemusí. Datum, které
        // by tu jinak stálo, mluví o certifikátu razítka — ne o dostupnosti toho údaje.
        boolean actNow = actNow(sig);

        switch (h.kind()) {
            case UNTIL -> {
                body.getChildren().add(AdvisorView.continuityText(
                        coveredVerified(howItWasVerified(sig, dev, h, actNow), via)));
                if (actNow) {
                    body.getChildren().add(
                            AdvisorView.continuityNote(onlineRevocationCaveat(sig, h, gen)));
                } else {
                    body.getChildren().add(AdvisorView.continuityLinked(
                            decides(sig, h, gen, elapsed(h.when()), via), open));
                    if (elapsed(h.when())) body.getChildren().add(
                            AdvisorView.continuityNote(elapsedNote(h.limit())));
                }
                addConcessionOnly(body, sig, dev, gen);
            }
            case OPEN -> {
                body.getChildren().add(AdvisorView.continuityText(
                        coveredVerified(howItWasVerified(sig, dev, h, actNow), via)));
                body.getChildren().add(actNow
                        ? AdvisorView.continuityNote(onlineRevocationCaveat(sig, h, gen))
                        : AdvisorView.continuityLinked(decides(sig, h, gen, false, via), open));
                addConcessionOnly(body, sig, dev, gen);
            }
            case LOST -> {
                body.getChildren().add(AdvisorView.continuityText(
                        "Ověřování skončilo tím, že stav platnosti " + gen + " zůstal neznámý —"
                        + " není to závěr o neplatnosti, nýbrž konstatování, že se platnost"
                        + " prokázat nepodařilo. Co konkrétně chybělo, stojí u "
                        + AdvisorView.gendered(sig, "tohoto podpisu", "této pečeti",
                                "tohoto časového razítka")
                        + " v Analýze, v blocích „V čem je problém“ a „Průběh ověřování“."));
                body.getChildren().add(AdvisorView.continuityText(lostOutlook(sig, dev)));
            }
            case INVALID -> {
                // Dvě různé neplatnosti. Změněný obsah je zjištění, na kterém se shodnou všichni;
                // neověřená podpisová hodnota nad nezměněným obsahem (situace 53) je vada uvnitř
                // podpisu, kterou část validátorů nesdílí — Adobe Reader, Signer i CzechPOINT
                // takový podpis ověří. Věta „k témuž závěru dojde každý validátor" tam byla
                // nepravda, a to prokazatelná: stačí týž soubor předložit jinde.
                if (ContinuityHorizon.valueDefectOnly(sig)) {
                    body.getChildren().add(AdvisorView.continuityText(
                            // Bez rodu: „podpisovou hodnotu podpisu" je hromada, a u pečeti ani
                            // razítka se ta hodnota nejmenuje jinak.
                            "Zdejší ověření skončilo záporně: podpisovou hodnotu se nepodařilo"
                            + " ověřit veřejným klíčem z podpisového certifikátu."
                            + " Podepsaný obsah je přitom nezměněný — otisk podepsaných dat"
                            + " souhlasí — takže vada je uvnitř samotného podpisu, ne v dokumentu."
                            + " Na tom se časem nic nezmění: podpis už jinak nevznikne a doplnit"
                            + " se k němu nedá nic, co by na tom něco změnilo."));
                    body.getChildren().add(AdvisorView.continuityText(
                            "Otázka „do kdy“ tu proto nedává smysl. Neznamená to ale, že k témuž"
                            + " závěru dojde každý: validátory, které se řídí údajem o hašovací"
                            + " funkci uvnitř podpisu (polem digestAlgorithm), "
                            + AdvisorView.gendered(sig,
                                    "tento podpis ověří jako platný",
                                    "tuto pečeť ověří jako platnou",
                                    "toto časové razítko ověří jako platné")
                            + ". Podrobněji o tom situace 53 v Analýze; o okruhu"
                            + " CzechPOINTu a podatelen mluví blok níže."));
                } else {
                    body.getChildren().add(AdvisorView.continuityText(
                            "Neplatnost " + gen + " je prokázána. Na rozdíl od neznámého stavu"
                            + " platnosti to není mezera v podkladech, kterou by šlo doplnit: je to"
                            + " zjištění, a to se během času nemění. Ani nové časové razítko, ani"
                            + " další revokační informace na něm nic nezmění."));
                    body.getChildren().add(AdvisorView.continuityText(
                            "Otázka „do kdy“ tu proto nedává smysl. K témuž závěru dojde každý"
                            + " validátor, dnes i později."));
                }
            }
        }

        // Poznámka o závislosti na síti tu stávala zvlášť. Dnes ji nese už první věta bloku
        // („a přitom využila odpověď OCSP, stahovanou on-line od jejího vydavatele“) společně
        // s druhou („jejich dostupnost je zajištěna do …“), takže by se říkala třetí.

        // Varování o jednorázové platnosti nastavení jen tam, kde na tom nastavení výsledek
        // opravdu stojí: uplatnil se ústupek, nebo se ověřovalo k ručně zadanému okamžiku.
        // Právě tyhle dvě věci aplikace po ověření zahazuje ({@code resetValidationPolicy});
        // vlastní kotva důvěry ani úložiště CRL se nevracejí, ty zůstávají. Nad podpisem, který
        // prošel bez ústupku, tedy věta strašila nastavením, které se ničeho nedotklo.
        if ((h.kind() == Kind.UNTIL || h.kind() == Kind.OPEN)
                && (!dev.namedLenient().isEmpty() || dev.customTime() != null)) {
            body.getChildren().add(AdvisorView.continuityNote(
                    "Uvedené platí pro ověření provedené stejným způsobem. Nastavení validační"
                    + " politiky (ústupky i zadaný čas ověření) platí v aplikaci vždy jen na"
                    + " jedno ověření — pro zopakování je nutné je zadat znovu."));
        }

        return AdvisorView.collapsibleBlock(
                anepTitle(h, ContinuityHorizon.valueDefectOnly(sig), actNow),
                body, false, tint(h.kind()));
    }

    /**
     * Proč dnešní ověření uspělo, když lhůta už uplynula.
     *
     * <p>Stává se to a není to rozpor: lhůta říká, dokdy je výsledek <b>spolehlivě</b>
     * dosažitelný, ne kdy přestane vycházet. Nejčastěji za tím stojí vydavatel, který revokační
     * informace zveřejňuje i po expiraci certifikátu, ačkoli nemusí. Odpověď proto musí být
     * konkrétní — obecné „opřelo se o to, co je po ruce“ čtenáři neřekne, na čem tedy stojí.
     */
    private static String elapsedNote(Limit limit) {
        return switch (limit) {
            case REVOCATION -> "Ta doba už uplynula — potřebné revokační informace se dnes přesto"
                    + " ještě podařilo získat. Že se to podaří i příště, zaručit nelze: vydavatel"
                    + " je po expiraci certifikátu zveřejňovat nemusí a zpravidla toho jednou"
                    + " zanechá.";
            case REVOCATION_PROOF -> "Ta doba už uplynula: revokační informace vložená v dokumentu je"
                    + " dnes informací od vydavatele, jemuž vypršel certifikát. Ověření uspělo jen"
                    + " díky ústupku, který to v aplikaci připouští; validátor držící se"
                    + " standardů takovou informaci neuzná. Uchování ověřitelnosti to napraví jen"
                    + " zčásti — nové razítko prokáže existenci dnešního stavu, nikoli stavu"
                    + " v době, kdy vydavatel ještě platil.";
            case TIMESTAMP_CERT -> "Ta doba už uplynula. Dnešní ověření přesto uspělo, ale stojí"
                    + " na materiálu, který je v dokumentu vložen; jakmile by bylo potřeba cokoli"
                    + " doplnit zvenčí, výsledek se zopakovat nemusí.";
            default -> "Ta doba už uplynula. Dnešní ověření přesto uspělo — spoléhat na to ale"
                    + " nelze: je to stav, který se nemusí zopakovat.";
        };
    }

    /**
     * @param valueDefect situace 53 — obsah je nezměněný a selhalo jen ověření podpisové hodnoty.
     *                    „Neplatnost je prokázána" by u ní tvrdilo víc, než co se zjistilo:
     *                    validátory, které se řídí polem digestAlgorithm, takový podpis ověří.
     */
    /**
     * @param actNow závěr stojí na revokačním údaji staženém on-line, na jehož další
     *               dostupnost není spoleh. Datum se pak z nadpisu vypouští <b>bez
     *               náhrady</b> (pokyn uživatele, 9. 9. 2026): jakákoli náhrada by byla
     *               dalším tvrzením o době, a právě to se tvrdit nedá. Odpověď na otázku
     *               je pak celá v těle bloku — viz {@link #onlineRevocationCaveat}.
     */
    private static String anepTitle(Horizon h, boolean valueDefect, boolean actNow) {
        if (actNow && (h.kind() == Kind.UNTIL || h.kind() == Kind.OPEN)) {
            return "Jak dlouho dokáže AnEP ověřit platnost?";
        }
        return switch (h.kind()) {
            case UNTIL -> "Jak dlouho dokáže AnEP ověřit platnost?  " + upperCaseBound(h)
                    + TimeFormat.full(h.when())
                    + (elapsed(h.when()) ? " — již uplynulo" : "");
            case OPEN -> "Jak dlouho dokáže AnEP ověřit platnost?";
            case LOST -> "Ověřit platnost dle AnEP dnes již nedokáže";
            case INVALID -> valueDefect
                    ? "Zdejší ověření skončilo záporně; na tom už čas nic nezmění"
                    : "Neplatnost je prokázána; na tom už čas nic nezmění";
        };
    }

    /**
     * Dnešní „platné“ stojí na ústupku — a od kdy.
     *
     * <p>Datum, které už uplynulo, <b>není lhůta</b>: {@link ContinuityHorizon#tightened} ho do
     * horizontu nepouští, protože AnEP ověří i zítra. Patří sem, do věty, která řekne, čím to
     * je — jinak by čtenář záhlaví četl jako „jde to trvale“ a nedozvěděl se, že to trvá jen
     * potud, dokud v aplikaci zůstane zapnutý ten ústupek.
     *
     * <p>Bez téhle věty vypadal blok nad „T kvalifikovany PS razitko PS 21 12.pdf“ tak, že se
     * ověřovalo běžně — výčet ústupků nahoře sice stojí, ale nespojoval se s datem, ke
     * kterému se váže.
     */
    private static void addConcessionOnly(VBox body, SignatureSummary sig, Deviations dev,
                                           String gen) {
        if (!dev.measured() || dev.namedLenient().isEmpty()) return;
        java.util.List<String> revocationRules = dev.namedLenient().stream()
                .filter(ContinuityHorizon::remediableByRevocationData).toList();
        if (revocationRules.isEmpty()) return;
        Instant since = ContinuityHorizon.concessionNeededSince(sig);
        if (since == null) return;

        body.getChildren().add(AdvisorView.continuityNote(
                "Dnešní závěr o platnosti " + gen + " ale stojí na ústupku, který dovoluje"
                + " využít revokační informace " + revocationRuleNames(revocationRules, sig)
                + ". Bez něj by ověření neprocházelo už od "
                + TimeFormat.full(since) + ": tehdy skončila doba, po kterou byly revokační"
                + " informace k celému řetězu spolehlivě dostupné. Datum výše proto platí jen"
                + " potud,"
                + " dokud zůstane ten ústupek v nastavení aplikace zapnutý — není to lhůta, která"
                + " běží, nýbrž podmínka, která už platí."));
    }

    /**
     * Zda ověření narazilo na revokační údaj, který by jiné nastavení aplikace propustilo:
     * důvod 2 (údaj vydaný po expiraci certifikátu) nebo důvod 6 (vložený údaj od respondéra,
     * jehož certifikát mezitím expiroval). Obojí má v Nastavení svoje zaškrtávátko.
     *
     * <p>Věty „nemá kde vzít" a „ověřit nelze" pak tvrdí víc, než je pravda — údaj po ruce je,
     * jen ho tohle nastavení nepouští dál. Platí tedy o tomhle nastavení, ne vůbec, a text to
     * musí říct: čtenář jinak odejde s tím, že cesta není, ačkoli vede přes zaškrtávátko.
     *
     * <p>Čte se výčet důvodů <b>po potlačovací tabulce</b>, ne před ní, a je to tak správně:
     * ptáme se, co má čtenář před sebou. Změřeno na „casove razitko s SHA1.pdf“ — situace 6
     * nastane, ale důvod 2 z ní potlačí důvod 22 (revokovaný certifikát), a tam žádné nastavení
     * nepomůže. Věta proto zůstane v původním znění, a to je ten správný závěr.
     */
    private static boolean settingsCouldHelp(SignatureSummary sig) {
        java.util.List<Integer> reasons = sig.getDetectedReasons();
        return reasons != null && (reasons.contains(2) || reasons.contains(6));
    }

    /**
     * Co by muselo přijít, aby se neznámý stav platnosti změnil.
     *
     * <p><b>U situace 35 nic nepřichází</b> — a proto se tu 3. 9. 2026 lhalo. Obě původní věty
     * stojí na tom, že „chybějí revokační informace“ (jedna dodává „a aplikace je nemá kde vzít“,
     * druhá „podklady s odstupem ubývají“), jenže S35 říká pravý opak: data v dokumentu jsou.
     * Nad „T kvalifikovany PS.pdf“ pak první blok tvrdil, že není kde brát, zatímco poslední blok
     * nabízel úkon, který to spraví.
     *
     * <p><b>Čím to je, se opravilo 12. 9. 2026.</b> Náhradní věta tvrdila, že vloženým informacím
     * chybí důkaz, že v dokumentu byly už v době, kdy šly uznat, a že ten důkaz vytvoří archivní
     * razítko. Obojí je špatně, a je to táž chyba, kterou téhož týdne dostal důvod 30: razítko
     * vydané dnes dokládá dnešek, kdežto důkaz o minulosti dokument <b>už nese</b> — situace 35
     * se ohlásí právě a jen tehdy, když existující platné razítko kryje certifikát vydavatele
     * vložené informace ještě za jeho platnosti (viz {@code preservationWouldRestoreValidity}).
     * Rozhoduje tedy proces, kterým ověřování projde, ne nově přidané razítko.
     *
     * <p>Nález uživatele na dokumentu s odpovědí OCSP v podepsaném
     * atributu {@code adbe-revocationInfoArchival}, dokumentové razítko z 23. 7. 2024 ji i jejího
     * vydavatele kryje — a přesto ověření skončilo s neznámým stavem, protože bez validačního
     * materiálu v dlouhodobém úložišti dokumentu neprojde v DSS kontrola {@code ARCH_LTAIVMP}
     * a k části procesu, která s důkazy existence pracuje, se ověřování nedostane.
     */
    private static String lostOutlook(SignatureSummary sig, Deviations dev) {
        if (sig.getDetectedSituations().contains(35)) {
            return "Opakování téhož ověření k tomu nepovede. Nechybějí ale revokační informace —"
                    + " ty dokument nese; jenže tam, kde leží, na ně posuzování podle standardů"
                    + " nedosáhne. Rozhoduje totiž, kterým procesem ověřování projde: bez"
                    + " revokačních informací v dlouhodobém úložišti dokumentu a bez časového"
                    + " razítka nad ním skončí u kroku, který žádá, aby certifikát vydavatele"
                    + " takové informace platil ještě dnes. S nimi se dostane ke kroku, který"
                    + " pracuje s důkazy existence — a ten důkaz dokument už nese, ve svém"
                    + " stávajícím časovém razítku. Přenést informace do úložiště a zafixovat je"
                    + " dalším razítkem umí uchování; po něm by platnost "
                    + AdvisorView.genitiveType(sig) + " ověřil i validátor podle standardů."
                    + (AdvisorView.shouldOfferOtherValidators(sig)
                        ? " Nástroj s vlastní zásobou revokačních informací je na tom už dnes"
                          + " jinak; o tom je blok níže." : "");
        }
        if (AdvisorView.shouldOfferOtherValidators(sig)) {
            return "Opakování téhož ověření k tomu nepovede — chybí revokační informace"
                    + " a ty aplikace " + (settingsCouldHelp(sig) ? "při svém aktuálním nastavení " : "")
                    + "nemá kde vzít. Nástroj s vlastní zásobou revokačních"
                    + " informací je na tom ale jinak; o tom je blok níže.";
        }
        // Poslední větev je společná všem prvkům, jejichž ověření skončilo neznámým stavem —
        // a těch je celá řada z důvodů, které s revokací nemají co dělat. Věta přesto tvrdila
        // „revokační informace, které chybějí“, tedy si příčinu domyslela. Potkáno 14. 9. 2026 na
        // „rozdily v case\rozdil v case_preserved_…_preserved_….pdf": jediný indikovaný důvod je
        // 18 (chybí atribut signing-time), revokační pokrytí je přitom úplné (situace 33, nic
        // nechybí). Týž vzorec jako u samostatného razítka o dva commity dřív: záložní větev
        // dosadila důvod, který nemá odkud znát.
        //
        // Revokace se proto jmenuje jen tam, kde je opravdu tou mezerou —
        // {@link AdvisorView#REVOCATION_GAP_REASONS} je táž množina, podle které se rozhoduje
        // nabídka cizí zásoby revokačních dat, takže si ta dvě místa nemohou protiřečit.
        boolean revocationIsTheGap = sig.getDetectedReasons().stream()
                .anyMatch(AdvisorView.REVOCATION_GAP_REASONS::contains);
        return "Opakování téhož ověření k tomu nepovede a čas situaci nezlepší: "
                + (revocationIsTheGap
                    ? "revokační informace, které chybějí, s odstupem nepřibývají, nýbrž ubývají."
                    : "vada, na které ověřování skončilo, se odstupem sama nespraví.")
                + (dev.any() ? " Ověřování už přitom proběhlo shovívavěji, než žádají standardy,"
                        + " takže ani další povolení v nastavení tu nic nezmění." : "");
    }

    // ── 2. Validátory podle standardů ────────────────────────────────────────────────────────

    private static VBox standardsBlock(SignatureSummary sig, Horizon h, Deviations dev) {
        VBox body = body();
        String gen = AdvisorView.genitiveType(sig);

        body.getChildren().add(AdvisorView.continuityText(
                // Že jsou to služby postavené na DSS, stojí ve výkladu na začátku záložky.
                "Řeč je o validátorech, které ověřují striktně podle technických standardů"
                + " (ETSI EN 319 102-1 a navazujících)."));

        if (h.kind() == Kind.INVALID) {
            // Napřed, ne až na konci: větev o ústupcích by pod prokázanou neplatností tvrdila,
            // že u přísného validátoru „zůstane stav platnosti neznámý“ — jenže neznámý nezůstane,
            // vyjde neplatný. Ústupky na tom nic nemění, nedosáhne na ně.
            body.getChildren().add(AdvisorView.continuityText(
                    ContinuityHorizon.valueDefectOnly(sig)
                    ? "K témuž závěru dojde i validátor podle standardů: ověřuje podpisovou"
                      + " hodnotu podle algoritmu, který podpis sám deklaruje, a ten se s ní"
                      + " rozchází. Odchylky, za kterých se tu ověřovalo, na tom nic nemění —"
                      + " ústupky na revokačních informacích ani na formátu na neověřenou"
                      + " podpisovou hodnotu nedosáhnou."
                    : "Neplatnost " + gen + " je prokázána a k témuž závěru dojde i validátor podle"
                      + " standardů. Odchylky, za kterých se tu ověřovalo, na tom nic nemění: na"
                      + " porušenou integritu ani na odvolaný certifikát žádná z nich nedosáhne."));
            return AdvisorView.collapsibleBlock(standardsTitle(h), body, false, tint(h.kind()));
        }

        if (dev.lenient()) {
            // Napřed „dokdy to šlo“, teprve pak „co by potřebovaly“. Datum je odpověď na otázku
            // v nadpisu; kdyby stálo až pod výkladem, hledal by ho čtenář pod třemi odstavci
            // o materiálu a pravidlech.
            if (h.kind() == Kind.LOST && h.when() != null) {
                body.getChildren().add(AdvisorView.continuityText(
                        "Platnost " + gen + " tyto validátory ověřit dokázaly do "
                        + TimeFormat.full(h.when()) + ". Tehdy skončila doba, po kterou byly"
                        + " revokační informace k celému řetězu dostupné tak, jak standardy"
                        + " žádají; od té chvíle je na ně platnost " + gen + " neověřitelná"
                        + " a stav zůstane neznámý. Není to lhůta, kterou by šlo ještě stihnout —"
                        + " uplynula a zpětně ji zachovat nelze."));
            }
            addWhatTheyWouldNeed(body, sig, dev, gen);
        } else if (dev.strictTimestamps() && h.reachable()) {
            body.getChildren().add(AdvisorView.continuityText(
                    "Zdejší ověření bylo naopak přísnější, než standardy žádají: časová razítka"
                    + " se posuzovala podle řádné doby platnosti jejich certifikátů. Validátor"
                    + " podle standardů kvalifikované elektronické časové razítko uzná i po expiraci"
                    + " certifikátu, dokud služba drží kvalifikovaný status."));
            body.getChildren().add(AdvisorView.continuityText(
                    "U něj by tedy platnost " + gen + " ověřit šlo, a to "
                    + (h.kind() == Kind.UNTIL ? lowerBound(h) + TimeFormat.full(h.when()) + "."
                            : "bez data, které by šlo předem stanovit.")));
        } else {
            body.getChildren().add(AdvisorView.continuityText(
                    "Odchylky nastavené pro tenhle běh se na závěru neprojevily, takže validátor"
                    + " podle standardů je na tom stejně jako aplikace AnEP: "
                    + (h.kind() == Kind.UNTIL
                        ? "platnost " + gen + " lze ověřit " + lowerBound(h)
                          + TimeFormat.full(h.when()) + "."
                        : h.kind() == Kind.OPEN
                            ? "platnost " + gen + " lze ověřit, ale datum stanovit nelze."
                            : "stav platnosti " + gen + " zůstane neznámý.")));
        }

        return AdvisorView.collapsibleBlock(standardsTitle(h), body, false, tint(h.kind()));
    }

    /**
     * Co by cizí validátor potřeboval, aby došel k témuž.
     *
     * <p>Rozdíl, na kterém tenhle blok stojí: <b>materiál</b> se dá opatřit, <b>pravidlo</b> ne —
     * aspoň ne dodáním souboru. Seznam CRL z vlastního úložiště je docela obyčejná revokační
     * informace a standardům neodporuje — kdo ji má, dojde k témuž závěru; otázkou je jen, jestli
     * mu ten nástroj dovolí ji podstrčit. Naproti tomu ústupek (SHA-1, post-expirační údaj,
     * nereferenční formát) je uvolněné pravidlo a k tomu nepomůže žádný doplněný soubor.
     *
     * <p>Neznamená to ale, že se nedá udělat nikde jinde. Pravidla nese validační politika, a
     * kdo ji smí vyměnit, může v ní udělat týž ústupek: ověřeno 29. 8. 2026 na referenční on-line
     * službě DSS, které lze politiku předložit — se SHA-1 sníženou na pouhé varování prošly
     * u téhož dokumentu bloky {@code Signature Acceptance Validation} i {@code Algorithm
     * Obsolescence Validation}, které s výchozí politikou končily na
     * {@code CRYPTO_CONSTRAINTS_FAILURE_NO_POE}. Text proto mluví o možnosti, kterou některé
     * nástroje mají a jiné ne, ne o vyloučení.
     *
     * <p>Dokud tu stálo ploché „stav platnosti by zůstal neznámý“, vypadal případ s vlastní CRL
     * jako beznadějný, ačkoli je to jen otázka toho, co má ověřovatel po ruce.
     */
    private static void addWhatTheyWouldNeed(VBox body, SignatureSummary sig, Deviations dev,
                                              String gen) {
        // Ústupek na revokačních informacích patří k materiálu, ne k pravidlům: dodáním seznamu
        // CRL z doby, kdy certifikát ještě platil, potřeba toho ústupku odpadá. Dodáním souboru
        // nespravitelné jsou jen SHA-1 a nereferenční formát — vlastnosti samotného podpisu.
        // Nespravitelné vůbec ale nejsou: kdo smí vyměnit validační politiku, udělá týž ústupek
        // u sebe. Viz javadoc výš.
        java.util.List<String> revocationRules = dev.namedLenient().stream()
                .filter(ContinuityHorizon::remediableByRevocationData).toList();
        java.util.List<String> hardRules = dev.namedLenient().stream()
                .filter(n -> !ContinuityHorizon.remediableByRevocationData(n)).toList();

        boolean material = dev.customCrl() || dev.customAnchor() || !revocationRules.isEmpty();
        boolean rule = !hardRules.isEmpty() || dev.customTime() != null;

        if (material) {
            if (!revocationRules.isEmpty()) {
                body.getChildren().add(AdvisorView.continuityText(
                        "Tyto služby vesměs nemají možnost přijmout stejný ústupek jako v tomto"
                        + " případě aplikace AnEP, a využít revokační informace "
                        + revocationRuleNames(revocationRules, sig) + ". Proto nedospějí ke stejnému"
                        + " závěru jako AnEP a stav platnosti " + gen + " hodnotí jako neznámý."
                        + " Jedinou možností, jak by tyto služby mohly dospět ke stejnému závěru"
                        + " a hodnotit " + AdvisorView.gendered(sig, "podpis jako platný",
                                "pečeť jako platnou", "razítko jako platné")
                        + ", by bylo dodat jim z externích zdrojů použitelné revokační informace"
                        + " (seznam CRL) — pokud ovšem takovou možnost vůbec nabízejí."));
            }
            if (!dev.customCrl() && !dev.customAnchor()) return;

            StringBuilder sb = new StringBuilder("Aby takový validátor došel k témuž závěru,"
                    + " potřeboval by mít po ruce totéž co aplikace AnEP: ");
            if (dev.customCrl() && dev.customAnchor()) {
                sb.append("tentýž seznam CRL, o který se závěr opřel a který má aplikace ve svém"
                        + " vlastním úložišti, a navíc tutéž kotvu důvěry"
                        + (dev.anchorName() != null ? " (" + dev.anchorName() + ")" : "")
                        + ", kterou evropské důvěryhodné seznamy neuvádějí");
            } else if (dev.customCrl()) {
                sb.append("tentýž seznam CRL, o který se závěr opřel a který má aplikace ve svém"
                        + " vlastním úložišti — sám o sobě je to zcela běžná revokační informace,"
                        + " standardům nijak neodporuje, jen ji ten validátor musí odněkud vzít");
            } else {
                sb.append("tutéž kotvu důvěry"
                        + (dev.anchorName() != null ? " (" + dev.anchorName() + ")" : "")
                        + " z vlastního úložiště certifikátů, kterou evropské důvěryhodné seznamy"
                        + " neuvádějí");
            }
            sb.append(".");
            body.getChildren().add(AdvisorView.continuityText(sb.toString()));

            body.getChildren().add(AdvisorView.continuityText(
                    "Jestli to takový validátor umožňuje, není vždy jisté: některé nástroje"
                    + " dovolují revokační informace i kotvy důvěry doplnit ručně, jiné pracují"
                    + " výhradně s tím, co je v dokumentu a co si samy stáhnou. Bez toho by u nich"
                    + " stav platnosti " + gen + " zůstal neznámý."));
        }

        if (rule) {
            body.getChildren().add(AdvisorView.continuityText(
                    (material ? "Nadto se zdejší závěr opřel" : "Zdejší závěr se opřel")
                    + " o to, co přísný výklad standardů nepřipouští"
                    + (hardRules.isEmpty() ? "" : " (" + String.join(", ", hardRules) + ")")
                    + ". To už není otázka toho, co má kdo po ruce vedle dokumentu: uvolněné"
                    + " pravidlo nenahradí žádný doplněný soubor, protože nejde o materiál"
                    + " k ověření, ale o měřítko, kterým se ověřuje. Změnit je lze jedině"
                    + " v nastavení samotného validátoru — a jestli to dovolí, se liší nástroj"
                    + " od nástroje: některé umožňují předložit vlastní validační politiku"
                    + " a týž ústupek v ní udělat (umí to i referenční on-line služba DSS),"
                    + " jiné pracují výhradně s výchozí politikou a u těch by stav platnosti "
                    + gen + " zůstal neznámý."));
        }
    }

    /**
     * Nadpis v minulém čase, kde je co datovat.
     *
     * <p>„Co ostatní validátory?“ je otázka o dnešku a odpověď „nedokáží“ patří do těla. Kde je
     * ale známo, <b>dokdy to dokázaly</b>, je to jediné číslo, které si čtenář z bloku odnese —
     * a minulý čas v nadpisu je to, co ho odliší od lhůt, které ještě běží.
     */
    private static String standardsTitle(Horizon h) {
        String base = "Co ostatní validátory, ověřující podle standardů?";
        return switch (h.kind()) {
            case UNTIL -> base + "  " + upperCaseBound(h) + TimeFormat.full(h.when())
                    + (elapsed(h.when()) ? " — již uplynulo" : "");
            case LOST -> h.when() == null ? base
                    : "Jak dlouho dokázaly ověřit platnost validátory podle standardů?  Do "
                      + TimeFormat.full(h.when());
            case OPEN -> base;
            case INVALID -> "Neplatnost je prokázána; potvrdí ji i validátory podle standardů";
        };
    }

    // ── 3. CzechPOINT, podatelny a další ─────────────────────────────────────────────────────

    private static VBox tolerantBlock(SignatureSummary sig, Horizon h,
                                      java.util.function.Consumer<ContinuityView.Outlook> open,
                                      Coverage via) {
        VBox body = body();
        String gen = AdvisorView.genitiveType(sig);

        switch (h.kind()) {
            case UNTIL -> {
                // Jedna věta, která říká, čím se ten okruh nástrojů liší, a hned z toho odvodí
                // datum. Do 7. 9. 2026 tu stálo pět vět výkladu o jejich odchylkách a teprve
                // pak odvození; výklad je dnes na začátku záložky.
                //
                // Odchylka není jen jedna. Vlastní zásoba revokačních informací je ta nejnápadnější,
                // ale tyhle nástroje promíjejí i požadavky samotných standardů — starší hashovací
                // funkce, nereferenční formáty, a jak se ukázalo na „T uznávaný_preserved_…asics",
                // i podepsaný čas (signing-time) zapsaný nestandardně, který DSS nepřečte a hlásí
                // jako chybějící (upřesněno 3. 10. 2026: GeneralizedTime místo UTCTime).
                // Věta o tom mluví obecně, protože výčet by byl slib, který aplikace nedrží:
                // nezná ani verzi, ani nastavení těch nástrojů.
                //
                // A proto „mohou ověřit", ne „ověří" ani „měly by být schopné ověřit". Odhad stojí
                // na dvou soustavných odchylkách, ne na znalosti konkrétního nástroje; poznámka
                // pod blokem to říká taky, ale hlavní věta ho nesmí slibovat jistotou.
                body.getChildren().add(AdvisorView.continuityLinked(
                        "Validátory s vlastní zásobou revokačních informací, jako CzechPOINT,"
                        + " podatelny OVM, služba SecuSign či program Signer, nejsou závislé na"
                        + " dostupnosti revokačních informací on-line, od jejich vydavatelů"
                        + " ([viz výklad na začátku záložky](OPTIONS)), a jsou tolerantnější"
                        + " i vůči některým požadavkům technických standardů. Platnost " + gen
                        + " proto mohou ověřit až do konce řádné doby platnosti "
                        + (via != null
                            // Horizont drží pozdější razítko, které tohle kryje; o vlastním
                            // certifikátu tohoto razítka tu mluvit nelze, ten už expiroval.
                            ? "certifikátu razítka č. " + via.number() + " — to toto razítko"
                              + " kryje (případně přes další razítka) a každé v řadě vzniklo ještě"
                              + " za platnosti certifikátu předchozího —"
                            : ContinuityHorizon.isTimestampElement(sig)
                            ? "časového razítka"
                            : h.limit() == Limit.TIMESTAMP_CERT
                            // Ne „časového razítka": mez drží to razítko, jehož certifikát
                            // platí nejdéle, a nemusí to být to poslední v řadě.
                            ? "certifikátu toho z časových razítek, které kryje nejdéle"
                            : "podpisového certifikátu — žádné časové razítko tu neprokazuje"
                              + " dřívější existenci, takže i tyto nástroje by ověřovaly"
                              + " k aktuálnímu okamžiku —")
                        + ", tj. do " + TimeFormat.full(h.when()) + ".", open));
            }
            case LOST -> body.getChildren().add(AdvisorView.continuityText(lostForTolerant(sig, h)));
            case OPEN -> body.getChildren().add(AdvisorView.continuityText(
                    "Do kdy by tyto validátory platnost " + gen + " ověřit dokázaly, se"
                    + " stanovit nedá."));
            case INVALID -> body.getChildren().add(AdvisorView.continuityText(
                    "Neplatnost " + gen + " je prokázána a k témuž závěru by dospěly i tyto"
                    + " nástroje. Shovívavost k revokačním informacím ani k formátu na tom nic"
                    + " nemění."));
        }

        if (h.kind() != Kind.INVALID) {
            body.getChildren().add(AdvisorView.continuityNote(
                    "Jde o odhad, ne o jistotu. Aplikace nezná verzi ani nastavení těchto"
                    + " nástrojů, natož obsah jejich zásob revokačních informací; odhad vychází"
                    + " z toho, co je o dokumentu známo, a z jejich dvou soustavných odchylek"
                    + " popsaných výše."));
        }

        return AdvisorView.collapsibleBlock(tolerantTitle(sig, h), body, false, tint(h.kind()));
    }

    /** Proč u nich naděje není. Důvod se pojmenuje, jinak si ho čtenář vyloží jako jejich vadu. */
    private static String lostForTolerant(SignatureSummary sig, Horizon h) {
        String gen = AdvisorView.genitiveType(sig);
        if (ContinuityHorizon.unsupportedDigest(sig)) {
            return AdvisorView.capitalize(AdvisorView.gendered(sig, "podpis", "pečeť", "razítko"))
                    + " stojí na hashovací funkci " + sig.getDigestAlgorithm() + " z rodiny SHA3."
                    + " Tu CzechPOINT a nejspíš ani podatelny zatím nepodporují, takže by platnost "
                    + gen + " neověřily — bez ohledu na revokační informace a na časová razítka.";
        }
        if (sig.isTrustedViaCustomStore()) {
            return "Důvěra ke " + gen + " tu stojí na certifikátu z vlastního úložiště aplikace."
                    + " Ten tyto nástroje nemají odkud vzít, takže by u nich stav platnosti"
                    + " zůstal neznámý — bez ohledu na revokační informace a na časová razítka.";
        }
        if (h.limit() == Limit.TIMESTAMP_CERT) {
            if (ContinuityHorizon.isTimestampElement(sig)) {
                return "Certifikát razítkovací autority, která toto razítko vydala, už expiroval."
                        + " Tyto nástroje razítko po expiraci jeho certifikátu za platné"
                        + " nepovažují, takže by jeho platnost neověřily — bez ohledu na to, že"
                        + " služba může mít kvalifikovaný status dál.";
            }
            // Přerušená řada je jiný důvod než expirovaný poslední článek: poslední razítko tu
            // certifikát platný mít může (a mívá), jenže mezi ním a podpisem je mezera, kterou
            // tenhle okruh nástrojů nepřenese. Bez téhle věty by se u zafixovaného
            // dokumentu tvrdilo, že expiroval certifikát razítka z roku 2026, který platí
            // do roku 2029.
            if (ContinuityHorizon.brokenChainStopsTolerant(sig)) {
                return "Řada časových razítek je přerušena — některé razítko vzniklo až po"
                        + " skončení platnosti certifikátu toho předchozího (viz fakta výše)."
                        + " Tyto nástroje posuzují razítka podle řádné doby platnosti jejich"
                        + " certifikátů, takže jim řada s takovou mezerou důkaz existence "
                        + gen + " nedá; " + AdvisorView.elementCert(sig,
                                AdvisorView.Case.NOMINATIV)
                        + " je přitom už po expiraci, a stav"
                        + " platnosti by tedy u nich zůstal neznámý. Vlastní zásoba revokačních"
                        + " informací tu nepomůže: nechybí revokační informace, nýbrž souvislé"
                        + " ukotvení v čase.";
            }
            // Ne „poslední razítko": stačí jedno razítko s dosud platným certifikátem, a proto
            // se sem dojde teprve tehdy, když je po expiraci celá řada. Dokud se tu mluvilo
            // o posledním, tvrdila věta nemožnost ověření nad dokumentem, který CzechPOINT
            // ověřil — „podpis s razitkem-extended-pades-baseline-lta.pdf", 18. 9. 2026.
            return "Certifikátům všech časových razítek, která " + gen + " kryjí, už skončila"
                    + " řádná doba platnosti. Tyto nástroje razítko po expiraci jeho certifikátu"
                    + " za platné nepovažují, takže by jim žádné z nich nemohlo posloužit jako"
                    + " důkaz existence " + gen + " v dřívější době — a bez něj by stav platnosti"
                    + " zůstal neznámý.";
        }
        if (h.limit() == Limit.SIGNING_CERT) {
            // „V dokumentu není časové razítko" se smí říct jen tam, kde opravdu není. U prvku
            // se situací 54 razítko v dokumentu je — jen na jiném prvku, který tenhle kryje —
            // a tvrdit opak vyvrací sám dokument. Nález z 13. 9. 2026: CzechPOINT přesně takový
            // podpis uznal za platný, zatímco tenhle blok o témže souboru psal, že mu razítko
            // chybí.
            //
            // Datum se tu přesto nedopočítává. Jestli si cizí nástroj tu úvahu udělá, aplikace
            // nemá odkud vědět; ví jen o jednom případu, kdy se tak stalo, a to je pozorování,
            // ne pravidlo.
            if (sig.getCoveringProofTimestamp() != null) {
                return AdvisorView.capitalize(AdvisorView.elementCert(sig, AdvisorView.Case.NOMINATIV))
                        + " už expiroval a tento " + AdvisorView.gendered(sig,
                        "podpis", "pečeť", "prvek") + " sám žádné časové razítko nenese."
                        + " Dokument ale razítko nese — na jiném prvku, který tenhle kryje."
                        + " Zda z něj tyto nástroje odvodí důkaz existence i pro něj, se předem"
                        + " říct nedá; u jednoho takového dokumentu to CzechPOINT udělal"
                        + " a platnost uznal.";
            }
            return AdvisorView.capitalize(AdvisorView.elementCert(sig, AdvisorView.Case.NOMINATIV))
                    + " už expiroval a v dokumentu není časové razítko, které by"
                    + " těmto nástrojům posloužilo jako důkaz existence " + gen + " v dřívější"
                    + " době. Vlastní zásoba revokačních informací tu nepomůže: nechybí"
                    + " revokační informace, nýbrž ukotvení v čase.";
        }
        return "Překážka, na které zdejší ověření skončilo, není v revokačních informacích,"
                + " a vlastní zásobou se tedy obejít nedá. Co konkrétně to je, stojí u "
                + AdvisorView.gendered(sig, "tohoto podpisu", "této pečeti",
                        "tohoto časového razítka") + " v Analýze.";
    }

    private static String tolerantTitle(SignatureSummary sig, Horizon h) {
        // U SHA3 ne „dnes již": ty nástroje takový podpis neověřily nikdy, nejde o lhůtu,
        // která uplynula.
        if (h.kind() == Kind.LOST && ContinuityHorizon.unsupportedDigest(sig)) {
            return "CzechPOINT a podatelny platnost nejspíš neověří";
        }
        return switch (h.kind()) {
            case UNTIL -> "Jak dlouho dokáží ověřit platnost CzechPOINT, podatelny a další"
                    + " validátory?  Do " + TimeFormat.full(h.when());
            case OPEN -> "Jak dlouho dokáží ověřit platnost CzechPOINT, podatelny a další"
                    + " validátory?";
            case LOST -> "CzechPOINT, podatelny a další validátory platnost dnes již ověřit"
                    + " nedokáží";
            case INVALID -> "Neplatnost je prokázána; potvrdí ji i CzechPOINT a podatelny";
        };
    }

    // ── 5. AnEP nabízí možnost… (uchování / prodloužení / přerazítkování / balíček) ──────────

    private static VBox todoBlock(Element el, Gates gates, Source tsa, String pdfaProfile,
                                   java.util.function.Consumer<ContinuityView.Outlook> open,
                                   java.io.File document) {
        SignatureSummary sig = el.sig();
        String verdict = el.verdict();
        Horizon anep = el.anep();
        Horizon tolerant = el.tolerant();
        Deviations dev = el.dev();
        String obstacle = el.obstacle();
        boolean actionReaches = el.actionReaches();
        VBox body = body();
        String gen = AdvisorView.genitiveType(sig);
        boolean valid = "TOTAL_PASSED".equals(verdict);

        // Brány platí pro dokument, překážka pro prvek. Úkon, na který tenhle prvek nedosáhne,
        // se u něj nesmí popisovat jako jeho řešení — razítko dokument pokryje, ale tomuhle
        // prvku nepomůže, a slib „zachová se ověřitelnost" by u něj byl nepravdivý.
        // Dosah počítá AdvisorView a předává ho sem hotový. Zdejší {@code reaches()} se ptalo
        // jen na to, zda prvek nemá překážku — což stačilo, dokud brány žádaly, aby úkon vyšel
        // všem dosažitelným. S pravidlem „stačí jeden prvek" se ty dvě otázky rozešly a nad
        // „dva podpisy T.pdf" z toho vyšlo, že prvek č. 2 má vlastní blok „AnEP nabízí možnost
        // uchování", zatímco souhrn o pár řádků níž říká, že mu úkon nepomůže.

        // Lhůta k úkonu je konec zdejší ověřitelnosti, nic dřívějšího. Stálo tu
        // min(getAugmentationDeadline(), horizont) a bylo to špatně: horizont už tu lhůtu
        // v sobě má — ale jen tehdy, když na ní opravdu záleží. Kde rozhodující revokační údaj
        // leží ve vlastním úložišti, ho ContinuityHorizon správně zahodí a tenhle výpočet ho
        // vzápětí přitáhl zpátky („Lhůta pro zásah uplynula 22. 9. 2023" nad dokumentem, který
        // je ověřitelný do roku 2034).
        // Lhůta k úkonu je konec zdejší ověřitelnosti, nic dřívějšího. Stálo tu
        // min(getAugmentationDeadline(), horizont) a bylo to špatně: horizont už tu lhůtu
        // v sobě má — ale jen tehdy, když na ní opravdu záleží. Kde rozhodující revokační údaj
        // leží ve vlastním úložišti, ho ContinuityHorizon správně zahodí a tenhle výpočet ho
        // vzápětí přitáhl zpátky („Lhůta pro zásah uplynula 22. 9. 2023" nad dokumentem, který
        // je ověřitelný do roku 2034).
        Instant anepDl = anepDeadline(anep);
        Instant tolerantDl = tolerant.kind() == Kind.UNTIL ? tolerant.when() : null;
        Instant salvage = salvageableUntil(anep, tolerant);
        // Kam úkon dosáhne a odkud se to měří — jedny hodnoty pro celý blok, aby si dva
        // odstavce nemohly o témže zásahu tvrdit opak. Viz {@link #reachAfterAction}.
        Instant actionAfter = reachAfterAction(salvage, tsa.until(), gates.preserve());
        Instant actionBefore = reachBeforeAction(anepDl, salvage, gates.preserve());
        boolean movesNothing = sourceMovesNothing(actionBefore, actionAfter);

        // Které z obou dat patří do nadpisu. Do 2. 9. 2026 to bylo vždycky {@code salvage}, tedy
        // <b>pozdější</b> z obou čtení — a nad přijatou zprávou z ISDS z toho vyšlo „Do
        // 13. 10. 2031", ačkoli dokument, který ověří kdokoli, z něj jde udělat jen do 11. 3. 2028.
        //
        // <p>Ta dvě data neměří totéž a nejde je zaměnit:
        // <ul>
        //   <li><b>zdejší lhůta</b> je „dokud je co vložit" — dokud vydavatel zveřejňuje revokační
        //       informace. Úkon do ní vytvoří samonosný prvek, který ověří kdokoli.</li>
        //   <li><b>tolerantní lhůta</b> je životnost certifikátu razítkovací autority. Po zdejší
        //       lhůtě už není co vkládat, takže úkon zmůže jen nové razítko nad dnešním stavem —
        //       a jeho výsledek ověří jedině ten, kdo si revokační údaje doplní z vlastní zásoby.
        //       To je záložní varianta, ne rovnocenná.</li>
        // </ul>
        //
        // <p>Maximum se sem dostalo 30. 8. 2026 kvůli případu, kdy je <b>zdejší lhůta už
        // v minulosti</b>: nad „T kvalifikovany PS razitko PS 21 12.pdf" hlásil blok „Lhůta
        // uplynula 5. 12. 2022" o dokumentu, který CzechPOINT ověří do 18. 10. 2027. Tam je
        // maximum správně a zůstává. Chyba byla v tom, že se totéž pravidlo uplatnilo i tam, kde
        // obě lhůty teprve běží — a tam rozhoduje ta, která zachrání víc.
        Instant deadline = actionDeadline(sig, anep, tolerant, gates);
        // Pozdější lhůta z vlastního důkazu (viz notCappedByOwnObstacle) není lhůta z tolerantního
        // čtení, i když je rovněž pozdější než zdejší. Bez tohohle rozlišení se u ní vysázel
        // odstavec „lhůta pro podpis, který by ověřil kdokoli, uplynula …“ o datu, které je
        // v budoucnosti — vidět nad jedním dokladem hned po rozpojení lhůt.
        boolean deadlineFromTolerant = fromTolerantOnly(anepDl, tolerantDl, deadline)
                && !deadlineFromEmbeddedProof(sig, anep, deadline);
        boolean fallbackAhead = tolerantDl != null && anepDl != null
                && tolerantDl.isAfter(anepDl);

        // Lhůta se od 8. 9. 2026 nevysází samostatným odstavcem, nýbrž větou uvnitř odstavce
        // o nabídce („Tuto možnost je třeba využít nejpozději do…“) — návrh uživatele, jehož
        // cílem je kratší a přehlednější blok. Odvození (why) i pobídka (pace) zůstávají beze
        // změny; obojí je naměřené a javadocy u nich říkají, na čem.
        String deadlineSentence = "";
        String paceSentence = "";
        // Co do lhůty vznikne. Připojuje se k větě o lhůtě, ne k pobídce — je to totiž věta
        // o výsledku, ne o datu, a čtenář ji má číst hned za termínem, ke kterému se váže.
        String resultSentence = "";
        boolean deadlineElapsed = deadline != null && elapsed(deadline);
        // Lhůta, kterou nelze stanovit, protože rozhodující materiál může zmizet kdykoli.
        // Datum v nadpisu by tu bylo tvrzení o razítku vydávané za tvrzení o dostupnosti
        // revokačního údaje — viz {@link #actNow}.
        boolean onlineNow = actionReaches && actNow(sig);
        // Druhý důvod pro „nejlépe ihned“ (5. 10. 2026, pokyn uživatele): algoritmus
        // nejnovějšího razítka přestane být spolehlivý dřív, než uplyne lhůta. Viz
        // {@link #weakKeyNow}.
        boolean keyNow = actionReaches && !onlineNow && weakKeyNow(sig, deadline);
        boolean actNow = onlineNow || keyNow;
        if (onlineNow) {
            paceSentence = actNowExplanation(sig);
        } else if (keyNow) {
            paceSentence = weakKeyExplanation(sig, tsa);
        } else if (deadline != null && actionReaches) {
            // Proč zrovna tohle datum, tu už řekl blok „Jak dlouho dokáže AnEP ověřit platnost?"
            // dvěma větami nahoře. Do 7. 9. 2026 se tady táž věta z modelu opakovala potřetí na
            // jedné kartě — jednou ve faktech, jednou v odvození a jednou tady.
            String limitReason = "";
            String why;
            if (deadlineFromTolerant) {
                // Datum pochází z tolerantního čtení, takže odtud musí být i zdůvodnění.
                if (anepDl == null) {
                    // Prázdné, a je to oprava. Stálo tu „Zdejší ověření dosáhne dál jen díky
                    // ústupku; tohle je poslední okamžik, kdy je co zachovat i pro ostatní“ —
                    // věta psaná pro horizont OPEN (sunset), jenže {@code anepDl == null} platí
                    // i pro LOST, tedy pro prvek, u kterého zdejší ověření <b>nedosáhlo nikam</b>.
                    // Nad „Pecet kvalifikovana.pdf“ tak nad sebou stálo „Ověřit platnost dle AnEP
                    // dnes již nedokáže“ a „zdejší ověření dosáhne dál“. Datum si teď vysvětlí
                    // {@link #deadlineNoun} jednou jmennou frází, která platí v obou případech.
                    why = "";
                } else if (gates.preserve()) {
                    // Uchování se nabízí a zdejší lhůta už uplynula: samonosný prvek z dokumentu
                    // udělat nejde a zbývá jen záloha. Říct to nahlas je poctivější než mlčky
                    // ukázat pozdější datum, které slibuje míň, než se zdá.
                    why = "Lhůta pro " + AdvisorView.gendered(sig,
                                    "podpis, který by ověřil kdokoli",
                                    "pečeť, kterou by ověřil kdokoli",
                                    "časové razítko, které by ověřil kdokoli")
                            + ", uplynula " + TimeFormat.full(anepDl)
                            + "; zbývá záložní varianta — nové časové razítko nad dnešním stavem,"
                            + " jehož výsledek dokáží ověřit pouze validátory s vlastní zásobou"
                            + " revokačních informací. ";
                } else {
                    // Přerazítkování: záloha je tu sám úkon, takže se o ní nemluví jako o tom,
                    // co teprve přijde. Co patří pod datum, je čím je určeno — a to je řádná doba
                    // platnosti certifikátu autority posledního neporušeného razítka, tedy táž
                    // věta, jakou o kus výš uvádí blok o CzechPOINTu.
                    why = "Určuje ji řádná doba platnosti certifikátu autority, která vydala"
                            + " poslední neporušené časové razítko; do té doby může sloužit jako"
                            + " důkaz existence " + AdvisorView.genitiveType(sig)
                            + " v dřívější době. ";
                }
            } else if (gates.preserve() && fallbackAhead) {
                // Věta o tom, <b>co do lhůty vznikne</b> — ne o tom, čím je lhůta určena. Proto
                // se od 8. 9. 2026 připojuje rovnou k větě o lhůtě, a ne k pobídce o odstavec
                // níž; „což je …“ vedle ní může zůstat, protože si neodporují.
                //
                // Druhá věta („Později už jen do … a jen jako záložní varianta: nové časové
                // razítko nad dnešním stavem, jehož výsledek dokáží ověřit pouze validátory
                // s vlastní zásobou …“) je pryč. Popisovala, co se stane, když čtenář lhůtu
                // nedodrží, a byla na to příliš složitá — pokyn uživatele.
                //
                // Kdo výsledek ověří, se sem <b>nedopisuje</b>: říká to odstavec o dosahu
                // o dva odstavce níž, a to i s odkazem do výkladu. Věta tu končila slovy
                // „a jeho platnost by měl dokázat ověřit každý validátor“ a byly to dvě věty
                // o téže věci na jedné kartě.
                resultSentence = " Do tohoto data " + AdvisorView.actionNounInstr(gates)
                        + " vznikne " + AdvisorView.gendered(sig,
                                "podpis, který", "pečeť, která", "časové razítko, které")
                        + " si ponese vše potřebné s sebou.";
                why = limitReason;
            } else {
                why = limitReason;
            }
            // Které z obou čtení lhůtu určilo — a tím i čím je určena; podle toho se pojmenuje
            // v {@link #deadlineNoun}.
            Limit deadlineLimit = deadline.equals(anepDl) ? anep.limit() : tolerant.limit();
            // Pobídka „Nejlépe ihned — čekat na konec lhůty nepřináší nic“ je pryč (9. 9. 2026,
            // pokyn uživatele). Nebyla pravdivá: čekáním se dá získat časové razítko od zdroje
            // s déle platným certifikátem, a tím delší dosah úkonu. Rozlišení spěchá/nespěchá
            // tím zaniklo celé — od toho je v bloku lhůta. Zůstává jen věta u lhůty, která už
            // uplynula, a ta o spěchu nemluví kvůli certifikátu, nýbrž kvůli podkladům, které
            // se mezitím ztrácejí.
            if (deadlineElapsed) {
                deadlineSentence = " Lhůta k využití této možnosti uplynula "
                        + TimeFormat.full(deadline) + ".";
                paceSentence = "Tím spíš je na místě jednat hned: každý další den se podklady"
                        + " spíš ztrácejí, než přibývají."
                        + (movesNothing
                            ? " Ne ale s dnes vybraným zdrojem časových razítek — ten by"
                              + " ověřitelnost neposunul (viz níže)." : "");
            } else {
                // „což je …“ se doplní jen tam, kde odvození (why) chybí. Kde je, řeklo by se
                // totéž dvakrát za sebou — a právě takhle vznikaly rozpory, které dnes hledá
                // ContinuityConsistencyScan.
                String noun = why.isEmpty()
                        ? deadlineNoun(sig, deadlineLimit, deadline) : null;
                deadlineSentence = " Tuto možnost je třeba využít nejpozději do "
                        + TimeFormat.full(deadline)
                        + (noun != null ? ", což je " + noun : "") + "." + resultSentence;
                paceSentence = why;
            }
        } else if (actionReaches) {
            // Datum neexistuje, a nadpis se přitom ptá „do kdy“. Nastane to u horizontu OPEN:
            // kvalifikované razítko drží podle standardů až do sunsetu, nebo se ověřovalo
            // k pevně zadanému okamžiku. Nechat otázku bez odpovědi by vypadalo jako chyba
            // výpočtu; {@code salvageableUntil()} v takovém případě vrací null schválně.
            deadlineSentence = " Do kdy je této možnosti třeba využít, se stanovit nedá: "
                    + noDeadlineReason(anep, tolerant) + ".";
        }

        if (actionReaches) {
            // Dva odstavce nahoře, zbytek jen tam, kde je co dodat: co se nabízí a dokdy, a hned
            // za tím, kdo výsledek ověří. Dosud stálo v běžném případě osm odstavců a odpověď na
            // otázku „co s tím tedy udělat" byla rozprostřená mezi ně.
            // Úvodní odstavec ustoupí výkladu rozdílu obou dat: říká totéž, jen hůř a s týmž
            // datem potřetí na jedné kartě. Vypouští se jen u uchování — u prodloužení
            // a přerazítkování nese i premisu, proč zrovna ta varianta, a ta jinde nestojí.
            if (!(gates.preserve() && horizonGapExplained(anep, deadline))) {
                addOfferIntro(body, sig, gates, deadlineSentence);
            }
            addHorizonGapNote(body, sig, anep, deadline);
            // Hned za nabídkou, ne až pod ní: čtenář, který čte shora, má výhradu vidět dřív,
            // než se dostane k tlačítku a k datům, která na kvalifikovaném razítku stojí.
            addUnqualifiedSourceNote(body, tsa);
            if (!paceSentence.isBlank()) {
                body.getChildren().add(deadlineElapsed || actNow
                        ? AdvisorView.continuityNote(paceSentence)
                        : AdvisorView.continuityText(paceSentence));
            }
            // Tlačítko se jmenuje podle varianty, takže se jeho jméno musí brát odtamtud, ne
            // opsat do věty. Jinak by text posílal čtenáře k tlačítku, které dole nestojí.
            //
            // Stojí <b>před</b> odstavcem o výsledku (pokyn uživatele, 8. 9. 2026): „co se
            // nabízí a dokdy“ → „spustí se tímhle tlačítkem“ → „a výsledkem bude…“ na sebe
            // navazuje, kdežto dosud stálo tohle až za výsledkem a mezi nimi ještě čtyři
            // podmíněné odstavce.
            body.getChildren().add(AdvisorView.continuityText(
                    "Spustí se tlačítkem „" + AdvisorView.actionVerb(gates)
                    + "“ dole pod záložkou."
                    + " Úkon je dokumentový: nové časové razítko kryje celý soubor, takže se"
                    + " provádí jednou pro všechny prvky v něm."));
            addArchivalFormNote(body, gates, pdfaProfile);
            addReachExplained(body, sig, gates, open);
            // Proti salvage, ne proti lhůtě z nadpisu: otázka zní „posune vybraný zdroj
            // ověřitelnost o něco?" a měří se proti tomu, kam dokument dosáhne i bez
            // zásahu — tedy k pozdějšímu z obou čtení.
            // Náhradní datum, když je jedno ze čtení otevřené: {@code salvageableUntil()} tehdy
            // vrací null a poznámka by se přeskočila, ačkoli zdejší lhůta datum má. Modrý blok ji
            // v takovém případě uměl a od 8. 9. 2026 už žádnou poznámku o zdroji nemá.
            addSourceTooShort(body, salvage != null ? salvage : anepDl, tsa.until(), gates,
                    actNow ? null : deadline, movesNothing);
            // Odstavec „Zachovat ověřitelnost má smysl i tak, a to právě kvůli ostatním“ je pryč
            // (8. 9. 2026, nález uživatele na „PAdES B-T kvalifikovaný PS 4096.pdf“). Byl to
            // popis <b>záložní</b> varianty — „pomůže aspoň validátorům s vlastní zásobou“ —
            // a nad podpisem, kterému se nabízí plnohodnotné uchování, tvrdil o výsledku míň, než
            // co o něm platí: odstavec o dosahu o dva řádky výš slibuje ověření ve všech
            // obvyklých validátorech. U přerazítkování a prodloužení se nevysázel už dřív, takže
            // tímhle zmizel úplně.
            //
            // Odstavec o materiálu zůstává jen tam, kde říká něco, co odstavec o dosahu neřekne.
            //
            // Vlastní CRL: seznam z vlastního úložiště se do uchované verze vloží, a tím přestane
            // být podmínkou, kterou splní jen tahle aplikace. Kotvu důvěry vložit nelze — ta není
            // materiálem k ověření, nýbrž předpokladem toho, kdo ověřuje — a právě proto se o ní
            // mluví o odstavec výš, v {@code addReachExplained()}, a ne tady. Dokud tu obě
            // závislosti měly společnou větu, slibovala nad prvkem s připnutým kořenem, že
            // „odpadne to, co dnes brání v ověření validátorům pracujícím podle standardů“ —
            // pravý opak toho, co o témž prvku říká blok o CzechPOINTu (naměřeno na
            // „PAdES LTA zaruceny PSS_Do_02_05_2032.pdf“).
            if (dev.customCrl()) {
                body.getChildren().add(AdvisorView.continuityText(
                        "Podstatné je, že materiál, na kterém dnešní závěr stojí, má aplikace po"
                        + " ruce: seznam CRL z vlastního úložiště se do uchované verze vloží,"
                        + " takže si jej dokument ponese s sebou. Co je dnes podmínkou, kterou"
                        + " splní jen tahle aplikace, se tím stane součástí souboru."));
            // …a u uchování se nevysází ani ten druhý (8. 9. 2026, tentýž nález). „Právě to je
            // důvod, proč s úkonem neotálet“ je rada do zad tomu, komu hrozí, že nebude co
            // vložit — jenže u plnohodnotného uchování odstavec o dosahu právě řekl, že výsledek
            // ověří kdokoli, a hned nad ním může stát pobídka <b>počkat</b>, protože vybraný
            // zdroj razítek nedosáhne dál. Dvě rady o načasování proti sobě na jedné kartě.
            } else if (!actNow && !gates.preserve()
                    && ContinuityHorizon.dependsOnOnlineRevocation(sig)) {
                body.getChildren().add(AdvisorView.continuityText(
                        // „Úkon", ne „uchování": tenhle odstavec se ukazuje u prodloužení
                        // i u přerazítkování, a jmenovat tam cizí variantu je rozpor s nadpisem
                        // i s tlačítkem (vidět na „LT pred revokaci.pdf“).
                        "Podstatné je, že revokační informace o podpisovém certifikátu se dnes"
                        + " stáhnout podařilo — a úkon je do dokumentu vloží. Tím se z ověření,"
                        + " které dnes závisí na tom, že je vydavatel stále zveřejňuje, stane"
                        + " ověření z toho, co dokument nese sám. Právě to je důvod, proč s úkonem"
                        + " neotálet: až vydavatel zveřejňování ukončí, nebude co vkládat."));
            }
            // Doporučení k načasování, ne překážka — a stojí schválně za odstavcem o tom, proč
            // s úkonem neotálet: ty dvě rady si neodporují, měří jinou dobu. „Neotálet" je
            // o měsících, tohle o hodinách.
            addWaitBeforePreserving(body, sig, valid);
            // Dole pod záložkou svítí dvě tlačítka a blok dosud vysvětloval jedno. Vedle
            // zachování stojí zafixování jen v expertním módu ({@code canFix} v AdvisorView:
            // mimo něj se nabízí teprve tam, kde zachovat nelze) — u přerazítkování ale i bez
            // něj, takže se mód jmenuje jen tam, kde o něj opravdu jde. Výklad je krátký;
            // celý ho má větev níž, kde je zafixování jediný úkon, který zbývá.
            if (gates.fix()) {
                body.getChildren().add(AdvisorView.continuityLinked(
                        "Jako další možnost nabízí aplikace"
                        + (gates.preserve() ? " v expertním módu" : "") + " zafixování —"
                        + " tlačítko „Vytvořit forenzní balíček“ dole pod záložkou. Ověřitelnost"
                        + " dokumentu neprodlouží; co balíček obsahuje a k čemu je, stojí"
                        + " [na konci výkladu na začátku záložky](OPTIONS).", open));
            }
        } else if (gates.fix()) {
            // Důvod se říká dál, i když návrh textu ho neměl: bez něj je „už to nejde“ tvrzení
            // bez opory a čtenář nemá kam se podívat. Je to táž věta, kterou vrací brána
            // {@code continuityObstacle()}, takže si text a tlačítko nemohou protiřečit.
            body.getChildren().add(AdvisorView.continuityText(
                    "U " + AdvisorView.gendered(sig, "tohoto podpisu", "této pečeti",
                            "tohoto časového razítka")
                    + " už není možné, nebo účelné, zachovávat ověřitelnost"
                    + (valid
                        ? (obstacle != null ? ": " + obstacle : "")
                        : (obstacle != null
                            ? ": " + obstacle
                            : ": platnost " + gen
                              + (settingsCouldHelp(sig)
                                    ? " se při aktuálním nastavení aplikace ověřit nepodařila"
                                    : " se ověřit nepodařila")))
                    + "."));
            body.getChildren().add(AdvisorView.continuityLinked(
                    "Aplikace AnEP pro " + AdvisorView.gendered(sig, "něj", "ni", "ně")
                    + " nabízí pouze možnost vytvoření tzv. forenzního balíčku: shromáždit vše,"
                    + " co o dokumentu ví a má aktuálně k dispozici, zabalit to do jednoho celku"
                    + " (kontejneru ASiC) a ten opatřit " + stampInstr(tsa)
                    + " coby důkazem o existenci v čase; co přesně balíček obsahuje,"
                    + " stojí [na konci výkladu na začátku záložky](OPTIONS).", open));
            addUnqualifiedSourceNote(body, tsa);
            body.getChildren().add(AdvisorView.continuityText(
                    "Spustí se tlačítkem „Vytvořit forenzní balíček“ dole pod záložkou."
                    + " Ověřitelnost samotného dokumentu to neprodlouží."));
            // Balíček lhůtu nemá — nic neprodlužuje, takže nadpis bloku u něj zůstává bez data.
            // Jenže jeho obsah lhůtu svým způsobem má: zachytí se v něm jen to, co je o dokumentu
            // známo v okamžiku zafixování, a toho časem ubývá. Bez téhle věty vypadá zafixování
            // jako úkon, se kterým se dá čekat libovolně dlouho, protože „stejně nic
            // neprodlužuje".
            body.getChildren().add(AdvisorView.continuityText(
                    "Vytvořit takovýto balíček je vhodné co nejdříve. Později už nemusí být"
                    + " dostupné některé informace, které dnes ještě dostupné jsou: vydavatelé"
                    + " přestávají zveřejňovat revokační informace a certifikátům časových"
                    + " razítek končí platnost. Balíček pořízený dnes proto může být průkaznější"
                    + " než týž balíček vytvořený později."));
        } else if (obstacle != null
                && (gates.preserve() || gates.prolong() || gates.restamp())) {
            // Dokument se zachovat dá, tenhle prvek z toho ale nic nemá. Řečeno takhle, a ne
            // „nedá se udělat nic": tlačítko dole svítí a čtenář by ty dvě věci nespároval.
            body.getChildren().add(AdvisorView.continuityText(
                    "Úkon, který se pro dokument nabízí, na " + AdvisorView.gendered(sig,
                            "tento podpis", "tuto pečeť", "toto časové razítko")
                    + " nedosáhne: " + obstacle + ". Nové časové razítko dokument pokryje celý"
                    + " a ostatním prvkům v něm prospěje, ale co je sporné na tomto, zůstane"
                    + " sporné i potom."));
        } else {
            body.getChildren().add(AdvisorView.continuityText(
                    "S " + AdvisorView.gendered(sig, "tímto podpisem", "touto pečetí",
                            "tímto časovým razítkem")
                    + " se v tuto chvíli nedá udělat nic z toho, co aplikace nabízí."
                    + (obstacle != null ? " Důvod: " + obstacle + "." : "")));
            addNoTsaNote(body, tsa, obstacle != null);
            body.getChildren().add(AdvisorView.continuityText(
                    "Dokument samotný tím nepozbývá ceny: co o něm dnes platí, je zaznamenáno"
                    + " v protokolu, který lze z aplikace vytisknout nebo uložit."));
        }

        if (actionReaches && !actNow) addCalendarReminder(body, gates, document, deadline);
        return AdvisorView.collapsibleBlock(todoTitle(gates, actionReaches, deadline, actNow),
                body, false, AdvisorView.BlockTint.RED);
    }

    /**
     * Připomínka do kalendáře na konci bloku „AnEP nabízí možnost…“ — pokyn uživatele
     * z 5. 10. 2026, se stejnou nabídkou předstihů jako okno záznamu o úkonu.
     *
     * <p>Jen tam, kde nadpis bloku nese <b>datum v budoucnu</b>: u „nejlépe ihned“ není k čemu se
     * vázat, u lhůty, která uplynula, by připomínka připadla na dnešek, a bez data (horizont
     * {@code OPEN}) není termín vůbec. Termín je týž {@code deadline}, ze kterého vzniká nadpis,
     * aby si nadpis a připomínka nemohly protiřečit.
     */
    private static void addCalendarReminder(VBox body, Gates gates, java.io.File document,
                                            Instant deadline) {
        if (document == null || deadline == null || elapsed(deadline)) return;
        if (!gates.preserve() && !gates.prolong() && !gates.restamp()) return;
        String verb = AdvisorView.actionVerb(gates);
        String noun = AdvisorView.actionNoun(gates);
        javafx.scene.layout.HBox row = CalendarReminder.row(
                CalendarReminder.fileName(document.getName()),
                days -> CalendarReminder.beforeDeadline(verb, noun, document, deadline, days,
                        Instant.now()));
        row.setPadding(new Insets(4, 0, 0, 0));
        body.getChildren().add(row);
    }

    /**
     * „Počkejte hodinu, teprve pak uchovávejte" — u čerstvě podepsaného dokumentu.
     *
     * <p>Uchování se od 4. 9. 2026 nabízí i vteřinu po podpisu, jenže tehdy vloží do dokumentu
     * revokační údaj starší než podpis a archivní razítko ho zafixuje natrvalo. Kdy to hrozí,
     * rozhoduje {@link AdvisorView#fresherRevocationLikelySoon(SignatureSummary, boolean)};
     * tam je i odůvodnění podmínek.
     */
    private static void addWaitBeforePreserving(VBox body, SignatureSummary sig, boolean valid) {
        if (!AdvisorView.fresherRevocationLikelySoon(sig, valid)) return;
        String el = AdvisorView.gendered(sig, "podpis", "pečeť", "razítko");
        String moment = AdvisorView.gendered(sig, "podpisu", "zapečetění", "vydání razítka");
        body.getChildren().add(AdvisorView.continuityText(
                "Jednu věc ale zvažte: jde o čerstvě " + AdvisorView.gendered(sig,
                        "podepsaný", "zapečetěný", "orazítkovaný")
                + " dokument a nejnovější revokační údaj o certifikátu je zatím starší než "
                + el + " sám. Vydavatelé na žádost o revokační informace mohou odpovídat"
                + " z vyrovnávací paměti a údaj mladší než " + el + " zveřejní až s odstupem,"
                + " někdy i několika hodin."));
        body.getChildren().add(AdvisorView.continuityText(
                "Uchování vloží do dokumentu to, co je k dispozici teď, a archivní razítko to"
                + " zafixuje — výhrada z bloku „Poznámky k výsledku“ v záložce Analýza tím"
                + " v dokumentu zůstane natrvalo. Počkat hodinu či dvě proto stojí za to; není"
                + " to odkládání úkonu, jde o to, aby se do dokumentu vložil doklad, který"
                + " o okamžiku " + moment + " vypovídá bez výhrad."));
    }

    /** Pochází lhůta z tolerantního čtení, tedy z toho, které zachrání míň? */
    private static boolean fromTolerantOnly(Instant anepDl, Instant tolerantDl, Instant deadline) {
        return deadline != null && tolerantDl != null && deadline.equals(tolerantDl)
                && (anepDl == null || tolerantDl.isAfter(anepDl));
    }

    /**
     * Dokdy má úkon smysl — <b>nejzazší</b> ze čtení, která ještě zachrání.
     *
     * <p>Maximum, ne minimum, a to je oprava z 30. 8. 2026. Stálo tu čtení AnEP samo a nad
     * „T kvalifikovany PS razitko PS 21 12.pdf“ z toho vyšlo „Lhůta uplynula 5. 12. 2022“ —
     * tedy „je pozdě“ — ačkoli CzechPOINT a podatelny ten podpis ověří do 18. 10. 2027 a uchování
     * jim tu možnost posune o dvanáct let. Věta, která od užitečného úkonu odrazuje, je horší než
     * žádná.
     *
     * <p>{@code null} znamená „datum se stanovit nedá“, ne „není co dělat“: u otevřeného horizontu
     * (sunset) žádné číslo neexistuje a nadpis i tělo se bez něj obejdou.
     */
    static Instant salvageableUntil(Horizon anep, Horizon tolerant) {
        if (anep.kind() == Kind.OPEN || tolerant.kind() == Kind.OPEN) return null;
        Instant a = anepDeadline(anep);
        Instant t = tolerant.kind() == Kind.UNTIL ? tolerant.when() : null;
        if (a == null) return t;
        if (t == null) return a;
        return t.isAfter(a) ? t : a;
    }

    /**
     * Lhůta k úkonu u jednoho prvku — které z obou čtení ji určuje.
     *
     * <p>Vytaženo z {@code todoBlock()} ven 9. 9. 2026: od chvíle, kdy blok „AnEP nabízí
     * možnost…“ při souběhu prvků stojí jednou za celý dokument, se na tutéž otázku ptají dvě
     * místa — nadpis dokumentového bloku (nejbližší z lhůt) a výčet po prvcích (lhůta toho
     * kterého prvku). Kdyby si každé počítalo po svém, rozešla by se.
     */
    static Instant actionDeadline(SignatureSummary sig, Horizon anep, Horizon tolerant,
                                  Gates gates) {
        Instant anepDl = anepDeadline(anep);
        boolean anepStillAhead = anepDl != null && !elapsed(anepDl);
        // <p><b>Který úkon se nabízí, rozhoduje i o tom, která lhůta platí.</b> Zdejší lhůta je
        // lhůtou <i>uchování</i> — po ní není co vkládat. Přerazítkování ale žádný materiál
        // nevkládá; jeho celý smysl je nové razítko nad dnešním stavem pro okruh validátorů
        // s vlastní zásobou revokačních informací, a tomu odpovídá lhůta tolerantní. Prodloužení
        // je naopak pro nástroje držící se standardů, takže patří k lhůtě zdejší.
        //
        // <p>Bez tohohle rozlišení by přerazítkování dostalo do nadpisu datum, které o jeho
        // vlastním užitku nic neříká — a text pod ním by mu sliboval samonosný prvek, ačkoli
        // {@code addReachExplained()} o pár odstavců níž u téhož úkonu píše „prodlouží právě tu
        // možnost ověření, která existuje dnes — ne širší".

        Instant base = (gates.restamp() || !anepStillAhead)
                ? salvageableUntil(anep, tolerant) : anepDl;
        return notCappedByOwnObstacle(sig, anep, base);
    }

    /** Vzala lhůta k úkonu datum z vlastního důkazu v dokumentu místo z horizontu? */
    private static boolean deadlineFromEmbeddedProof(SignatureSummary sig, Horizon anep,
                                                     Instant deadline) {
        if (sig == null || deadline == null || anep.limit() != Limit.REVOCATION_PROOF) return false;
        SignatureSummary.AugmentationDeadline d = sig.getAugmentationDeadline();
        return d != null && deadline.equals(d.when());
    }

    /**
     * Lhůta k úkonu nekončí tam, kde končí možnost ověřit — pokud je překážkou právě to, co úkon
     * odstraní.
     *
     * <p>Mez {@link Limit#REVOCATION_PROOF} říká, že vložený revokační údaj přestane být
     * uznatelný, protože jeho vydavateli skončí certifikát a v procesu 5.5 se na důkazy existence
     * nehledí. Úkon ale dostane podpis na úroveň LTA, kde se hledí — a důkaz tam přinese razítko,
     * které v dokumentu <b>už dnes je</b>. Úkon tedy zůstává možný, dokud jde ověřit ono razítko,
     * tedy do konce platnosti jeho certifikátu; přesně tuhle lhůtu drží
     * {@link SignatureSummary#getAugmentationDeadline()}.
     *
     * <p>Změřeno 11. 9. 2026 na uchované příloze: uchování provedené
     * <b>tři roky</b> po expiraci respondéra vydalo podpis, který se ověří i bez sítě, a to
     * o vloženou odpověď z roku 2023. Zastropovat lhůtu horizontem by u takového dokumentu
     * znamenalo poslat čtenáře pryč od úkonu, který by mu pořád vyšel.
     *
     * <p>Obě data se pak liší a blok o nabídce ten rozdíl vysvětlí — viz
     * {@link #addHorizonGapNote}.
     */
    private static Instant notCappedByOwnObstacle(SignatureSummary sig, Horizon anep,
                                                  Instant base) {
        if (sig == null || base == null || anep.limit() != Limit.REVOCATION_PROOF) return base;
        SignatureSummary.AugmentationDeadline d = sig.getAugmentationDeadline();
        if (d == null || d.when() == null) return base;
        return d.when().isAfter(base) ? d.when() : base;
    }

    /**
     * Lhůtu stanovit nelze a jediná poctivá odpověď je <b>„nejlépe ihned“</b>.
     *
     * <p>Nastane, když dnešní kladný závěr stojí na revokačním údaji <b>staženém on-line</b>
     * a doba, po kterou se na jeho dostupnost dalo spoléhat, už uplynula — typicky u podpisu
     * v úrovni T, jehož certifikát expiroval a jehož OCSP si aplikace stáhla dnes.
     *
     * <p><b>Proč do nadpisu nepatří datum.</b> {@code perishableRevocation()} takovou uplynulou
     * lhůtu schválně zahazuje: běh, který právě proběhl, ji vyvrátil, protože údaj se stáhnout
     * podařilo. Horizont pak spadne na certifikát časového razítka — jenže to je tvrzení
     * o razítku, ne o dostupnosti údaje. Nad „2 podpisy signed.pdf“ z toho vyšla lhůta
     * 13. 10. 2030 nad podpisem, jehož certifikát expiroval 3. 9. 2025 a jehož závěr stojí na
     * OCSP staženém téhož dne, kdy se ověřovalo. Nález uživatele z 9. 9. 2026: „Kde bereš
     * jistotu, že takováto OCSP bude dostupná i později?“ — nikde. Datum tedy ustoupí větě
     * „nejlépe ihned“ a proč, říká {@link #actNowExplanation} uvnitř bloku.
     *
     * <p><b>Přísnější než {@code dependsOnOnlineRevocation()}</b>, a záměrně. Tam je neznámý
     * původ údaje ({@code fact == null}) brán jako závislost na síti, protože z toho plyne jen
     * opatrnější, kratší horizont. Tady se o původu tvrdí konkrétní věta, a ta musí být
     * pravdivá.
     *
     * <p>Sken 339 prvků (D:\tmp a „test zachovani“, 9. 9. 2026): širší podmínka 84 zásahů,
     * zdejší 82; z nich <b>25 s kladným verdiktem</b>, tedy tam, kde se úkon opravdu nabízí.
     * <b>Úroveň LT zásah nevylučuje</b> a je to tak správně: nad „pet podpisu LT.pdf“ je
     * u prvků č. 2 a 3 vložený údaj sice v dokumentu, ale bez důkazu existence, takže ho uznat
     * nelze a dnešní závěr stojí na staženém seznamu CRL. Věta o původu tedy platí i tam.
     */
    static boolean actNow(SignatureSummary sig) {
        ValidationCourse vc = sig.getValidationCourse();
        if (vc == null || !vc.revocationFetchedOnline()) return false;
        ValidationCourse.RevocationFact fact = vc.signingCertRevocation();
        if (fact == null || !"online".equals(fact.origin())) return false;
        SignatureSummary.AugmentationDeadline d = sig.getAugmentationDeadline();
        return d != null && d.when() != null && elapsed(d.when());
    }

    /**
     * Proč se lhůta stanovit nedá. Certifikát se nejmenuje: {@code getAugmentationDeadline()} je
     * minimum přes celý řetěz, takže to nemusí být ten podpisový, a pojmenovat špatný článek je
     * horší než nepojmenovat žádný.
     */
    private static String actNowExplanation(SignatureSummary sig) {
        Instant when = sig.getAugmentationDeadline().when();
        return "Lhůtu k využití této možnosti stanovit nelze. Dnešní závěr stojí na revokačním"
                + " údaji, který si aplikace stáhla on-line od jeho vydavatele — a doba, po"
                + " kterou se na jeho dostupnost dalo spoléhat, uplynula "
                + TimeFormat.full(when) + ". Že se ho podařilo stáhnout dnes, neříká nic o tom,"
                + " zda půjde stáhnout i příště; až ho vydavatel přestane zveřejňovat, nebude co"
                + " do dokumentu vložit.";
    }

    /**
     * „Nejlépe ihned“ kvůli algoritmu nejnovějšího razítka — pokyn uživatele z 5. 10. 2026.
     *
     * <p>Nejnovější razítko drží důkaz existence všeho pod ním. Přestane-li jeho algoritmus být
     * spolehlivý (dnes typicky RSA o 2 048 bitech, 1. 1. 2027), validátory s aktualizovanými
     * pravidly ho mohou odmítnout a s ním i všechno, co dokládá. Nové razítko se silnějším
     * algoritmem musí přijít dřív — a to datum leží u většiny dokumentů do tří měsíců, takže
     * lhůta odvozená z certifikátu (roky 2028 až 2036) by sváděla k odkladu.
     *
     * <p>Ověření v AnEP samo na to datum reaguje jen varováním (politika ověřování, oddíl 10),
     * horizont AnEP se proto nemění — mění se jen rada, kdy jednat. Změřeno sondou
     * {@code CryptoHorizonScan}: týká se 60 % prvků s datem ve sbírkách.
     *
     * <p>Neplatí, když lhůta už uplynula — o tom mluví nadpis a jeho věta je důležitější —
     * a když algoritmus vydrží déle než lhůta (RSA-4096 do 2035 u certifikátu do 2034).
     */
    static boolean weakKeyNow(SignatureSummary sig, Instant deadline) {
        SignatureSummary.TimestampKey k = sig.getNewestTimestampKey();
        if (k == null || k.reliableUntil() == null) return false;
        if (deadline != null && elapsed(deadline)) return false;
        return deadline == null || k.reliableUntil().isBefore(deadline);
    }

    /** Proč nejlépe ihned — u prvku. */
    private static String weakKeyExplanation(SignatureSummary sig, Source tsa) {
        SignatureSummary.TimestampKey k = sig.getNewestTimestampKey();
        String acc = AdvisorView.gendered(sig, "tento podpis", "tuto pečeť",
                "toto časové razítko");
        boolean self = ContinuityHorizon.isTimestampElement(sig);
        return "Tuto možnost je vhodné využít nejlépe ihned. "
                + (self ? "Nejnovější časové razítko, které " + acc + " kryje (případně ono"
                          + " samo),"
                        : "Nejnovější časové razítko, které " + acc + " kryje,")
                + " je podepsané algoritmem " + k.description() + ", " + weakKeyClause(k)
                + weakSourceAddendum(tsa);
    }

    /**
     * Společná část vysvětlení: od kdy algoritmus neplatí za spolehlivý, co na to ověření
     * a co pomůže. Data za aktualizací kryptografické sady jsou odhad a ověření na ně reaguje
     * jen varováním — text to říká, aby neslíbil konec ověřitelnosti, který v AnEP nenastane.
     */
    private static String weakKeyClause(SignatureSummary.TimestampKey k) {
        boolean past = elapsed(k.reliableUntil());
        String day = TimeFormat.day(k.reliableUntil());
        // Původcem data je knihovna DSS, ne ETSI (pokyn uživatele z 22. 10. 2026): ETSI TS 119 312
        // to datum míní pro vydávání nových certifikátů TSA, kdežto výchozí politika DSS 6.5 ho
        // uplatňuje i na razítko vydané dřív, pokud jeho užití nedoloží pozdější razítko.
        return "a ten knihovna DSS, nad kterou AnEP ověřuje, " + (past ? "už od " : "od ") + day
                + " nepovažuje za spolehlivý."
                + (k.forecast()
                    ? " Aplikace AnEP na to zatím reaguje jen varováním a ověřitelnost tím"
                      + " nekončí; jiné validátory ale mohou takové razítko po tomto datu odmítnout"
                      + " — a s ním i to, co dokládá."
                    : " Ověření takové razítko po tomto datu nepřijme.")
                + (past
                    ? " Nové časové razítko se silnějším algoritmem proto pořiďte co nejdřív:"
                      + " čím později přijde, tím méně validátorů dosavadní razítka uzná."
                    : " Nové časové razítko se silnějším algoritmem, přidané před tímto datem,"
                      + " doloží, že dokument i dosavadní razítka existovaly v době, kdy byl"
                      + " algoritmus ještě spolehlivý.");
    }

    /** Dodatek, když by nové razítko od vybraného zdroje mělo týž problém. */
    private static String weakSourceAddendum(Source tsa) {
        if (tsa == null || tsa.weakKey() == null) return "";
        return " Vybraný zdroj časových razítek" + (tsa.named() ? " (" + tsa.name() + ")" : "")
                + " ale podepisuje rovněž algoritmem "
                + tsa.weakKey().key().describe() + ", který přestává být spolehlivý "
                + TimeFormat.day(tsa.weakKey().until().toInstant())
                + " — jeho razítko by tenhle problém nevyřešilo. Vyberte v nastavení zdroj"
                + " s klíčem RSA aspoň 3 000 bitů nebo ECDSA.";
    }

    /**
     * Upozornění na slabý klíč vybraného zdroje v bloku „Jak dlouho je možné zachovat
     * ověřitelnost?“ — pokyn uživatele z 5. 10. 2026. Datum v nadpisu bloku se nemění: je to
     * konec certifikátu autority a ověření v AnEP na dřívější datum algoritmu reaguje jen
     * varováním. Mění se to, co čtenář ví, než zmáčkne tlačítko.
     */
    private static void addWeakSourceNote(VBox body, Source tsa) {
        if (tsa == null || tsa.weakKey() == null) return;
        var w = tsa.weakKey();
        boolean past = w.until().before(new java.util.Date());
        body.getChildren().add(AdvisorView.continuityNote(
                "Vybraný zdroj časových razítek" + (tsa.named() ? " (" + tsa.name() + ")" : "")
                + " podepisuje razítka algoritmem " + w.key().describe() + ", který knihovna"
                + " DSS, nad kterou AnEP ověřuje, " + (past ? "už od " : "od ")
                + TimeFormat.day(w.until().toInstant()) + " nepovažuje za spolehlivý — dřív,"
                + " než skončí platnost certifikátu jeho autority."
                + (w.forecast()
                    ? " Aplikace AnEP na to zatím reaguje jen varováním; jiné validátory ale mohou"
                      + " razítko od tohoto zdroje po tomto datu odmítnout, a ověřitelnost zachovaná"
                      + " tímto úkonem by pro ně skončila už tehdy."
                    : " Ověření takové razítko po tomto datu nepřijme.")
                + " Vhodnější je zdroj s klíčem RSA aspoň 3 000 bitů nebo ECDSA; vybrat ho lze"
                + " v nastavení."));
    }

    /** Lhůta z vlastního čtení, nebo {@code null}, když žádné datum nemá. */
    private static Instant anepDeadline(Horizon anep) {
        return anep.kind() == Kind.UNTIL ? anep.when() : null;
    }

    /**
     * Varování, že vybraný zdroj razítek zásahu nic nepřinese.
     *
     * <p>Patří sem, k úkonu, a ne jen o blok výš: tady se čtenář rozhoduje, jestli zmáčknout
     * tlačítko. Když zdroj dosáhne sám sotva dál než dokument, je první krok jiný — vybrat
     * v nastavení zdroj s delším certifikátem.
     *
     * <p>Získaný čas se vždy vyčíslí. „Posune jen málo“ nad rozdílem 48 minut a táž věta nad
     * rozdílem tří měsíců jsou dvě různé zprávy a čtenář má vědět, o kterou jde.
     */
    // Metoda urgent() a s ní celá rozvaha „spěchat, nebo to jen stihnout“ jsou pryč
    // (9. 9. 2026, pokyn uživatele). Rozhodovaly o větě „Nejlépe ihned — čekat na konec lhůty
    // nepřináší nic“, a ta neplatí: čekáním se dá získat razítko od zdroje s déle platným
    // certifikátem, tedy delší dosah úkonu. Od toho, dokdy se má zasáhnout, je v bloku lhůta.
    //
    // Co z té rozvahy platí dál a nemá se ztratit: kde závěr stojí na revokačním údaji
    // staženém ze sítě, opravdu se otálet nemá — vydavatel jednou zveřejňování ukončí a pak
    // nebude co vložit. To ale není věta o tempu, nýbrž o materiálu, a říká ji odstavec
    // {@code addDocumentMaterialNote()} / jeho protějšek v {@code todoBlock()}.

    /**
     * Že úkon ověřitelnost neposune ani o den.
     *
     * <p>Táž podmínka jako větev {@code backwards} v {@link #addSourceTooShort}, vytažená ven,
     * aby se o ní dva odstavce v jednom bloku nemohly rozejít: lhůta nahoře pobízí ke spěchu,
     * poznámka dole říká, že by byl k ničemu.
     *
     * <p>Obě meze si volající spočítá {@link #reachBeforeAction} a {@link #reachAfterAction};
     * do 20. 9. 2026 se tu srovnávalo „konec platnosti certifikátu vybraného zdroje“ proti
     * „nejzazšímu z obou čtení“ a obojí bylo vedle.
     */
    private static boolean sourceMovesNothing(Instant before, Instant after) {
        return before != null && after != null && !after.isAfter(before);
    }

    /**
     * Kam ověřitelnost dosáhne po úkonu.
     *
     * <p>Ne prostě „kam sahá certifikát vybraného zdroje razítek“. Nové razítko se přidává
     * k řadě, kterou dokument už nese, a <b>mladší razítko s kratší platností certifikátu tu
     * řadu nezkracuje</b> — říká to výklad na začátku záložky („nepřidá nic, ale ani nic
     * nebere“) a je to změřeno: „PAdES B-LT kvalifikovany PS neviditelny.pdf“ (řada do
     * 1. 11. 2031, vybraný zdroj ACCV do 25. 2. 2029) vyšel po skutečně provedeném uchování
     * jako podpis v úrovni LTA, ověřitelný „nejméně do 1. 11. 2031“ — nikoli do roku 2029.
     *
     * <p>Počítá se s tím <b>jen u uchování</b>: jeho brána žádá souvislou řadu razítek (situace
     * 31 i 32), takže nové razítko do ní zapadne. Prodloužení a přerazítkování se nabízejí
     * právě tam, kde souvislá není, a tam dosah úkonu určuje nové razítko samo.
     */
    private static Instant reachAfterAction(Instant reach, java.util.Date tsaUntil,
                                            boolean preserve) {
        if (tsaUntil == null) return null;
        Instant until = tsaUntil.toInstant();
        if (!preserve || reach == null) return until;
        return until.isAfter(reach) ? until : reach;
    }

    /**
     * Odkud se ten posun měří — tedy co úkon zachraňuje.
     *
     * <p>U uchování je to <b>zdejší</b> čtení: co dokáže ověřit aplikace sama, potažmo validátory
     * držící se standardů. Právě to totiž úkon zachraňuje — vloží chybějící revokační informace
     * a ty už vložené zafixuje. Nejzazší z obou čtení, kterým se měřilo dosud, patří validátorům
     * s vlastní zásobou revokačních informací; těm úkon nic nebere, ale ani je nezachraňuje,
     * takže z jejich data vycházelo, že „přínos není žádný“ i nad podpisem, kterému úkon
     * prodlouží ověřitelnost o čtyři a půl roku.
     *
     * <p>Kde zdejší čtení datum nemá (otevřený horizont, nebo prvek, u kterého se ověřit
     * nepodařilo), zbývá nejzazší mez jako dosud.
     */
    private static Instant reachBeforeAction(Instant anepReach, Instant reach, boolean preserve) {
        return preserve && anepReach != null ? anepReach : reach;
    }

    /**
     * Má úkon co vložit nebo co zafixovat?
     *
     * <p>Otázka, která s posunutím data nesouvisí, a právě proto existuje: kde všechny potřebné
     * revokační informace v dokumentu leží a kryje je časové razítko, je „žádný přínos“ pravda.
     * Kde jsou jen <b>dostupné</b> (staženy on-line, nebo ve vlastním úložišti seznamů CRL),
     * nebo kde v dokumentu sice leží, ale bez důkazu existence, tam úkon udělá právě tohle —
     * i když datum, do kterého lze platnost ověřit, nikam neposune.
     *
     * <p>{@code REVOCATION} i {@code REVOCATION_PROOF} jmenují obě půlky: dostupnost, která
     * jednou skončí, a vložený údaj, který po expiraci svého vydavatele nikdo neuzná.
     */
    private static boolean materialToSecure(Element el) {
        return willEmbed(el) || willFix(el);
    }

    /**
     * Je co <b>vložit</b> — leží rozhodující revokační informace mimo dokument?
     *
     * <p>Tři cesty, a všechny znamenají totéž: údaj je dnes k dispozici, ale dokument ho nenese.
     * Stažený on-line, vzatý z vlastního úložiště seznamů CRL, nebo horizont držený samotnou
     * dostupností ({@code REVOCATION}).
     */
    private static boolean willEmbed(Element el) {
        return el.dev().customCrl()
                || ContinuityHorizon.dependsOnOnlineRevocation(el.sig())
                || el.anep().limit() == Limit.REVOCATION;
    }

    /**
     * Je co <b>zafixovat</b> — leží revokační informace v dokumentu bez důkazu existence?
     *
     * <p>Situace 35 je táž věc řečená z druhé strany: data v dokumentu jsou a chybí jim jen
     * razítko nad celým souborem. Právě proto se u ní <b>nesmí</b> tvrdit, že úkon vloží to, co
     * je dostupné dnes — o tomtéž podpisu první blok píše, že aplikace nemá odkud co vzít
     * (nález uživatele z 3. 9. 2026, „T kvalifikovany PS.pdf“).
     */
    private static boolean willFix(Element el) {
        return el.anep().limit() == Limit.REVOCATION_PROOF
                || el.sig().getDetectedSituations().contains(35);
    }

    /**
     * Že úkon neslouží jen k posunutí data, nýbrž i k tomu, aby bylo z čeho ověřovat.
     *
     * <p>Znění podle pokynu uživatele (20. 9. 2026). Vysází se tam, kde by jinak z porovnání
     * dvou dat vyšlo, že se nemá co získat: certifikát vybraného zdroje razítek sahá míň daleko
     * než řada, kterou dokument nese. To je ale odpověď jen na otázku „o kolik dál“ — druhá
     * půlka úkonu vůbec nestojí na dosahu razítka, nýbrž na tom, že revokační informace se
     * časem přestanou dát opatřit i doložit.
     */
    private static void addPreservationPurpose(VBox body, boolean willEmbed, boolean willFix) {
        String lead = "Nejde přitom jen o prodloužení ověřitelnosti, ale i o její zachování. ";
        String available = "Revokační informace nejsou dostupné natrvalo: jejich vydavatel je"
                + " zveřejňuje jen po dobu platnosti certifikátu, ke kterému se vztahují";
        String proof = "nové časové razítko je opatří důkazem o tom, že existovaly ještě"
                + " v době, kdy jejich vydavatel platil.";
        // Tři znění, ne skládačka: „ty, které“ bez věty, která je uvede, nemá na co ukazovat,
        // a právě tak to z první verze vyšlo u podpisu, kde je co zafixovat a není co vložit.
        String text;
        if (willEmbed && willFix) {
            text = lead + available + ", a ty, které v dokumentu už vloženy jsou, přestanou být"
                    + " po expiraci svého vydavatele využitelné. Úkon do dokumentu vloží"
                    + " revokační informace dostupné dnes a ty už vložené zafixuje: " + proof;
        } else if (willEmbed) {
            // Ne „ponese si je s sebou": tu větu má o blok níž odstavec o vlastním úložišti
            // seznamů CRL a byla by to podruhé táž věta na jedné kartě.
            text = lead + available + ". Úkon do dokumentu vloží ty, které jsou dostupné dnes,"
                    + " takže pozdější ověření už na jejich dostupnosti záviset nebude.";
        } else {
            text = lead + "Revokační informace, které v dokumentu už vloženy jsou, přestanou"
                    + " být po expiraci svého vydavatele využitelné. Úkon je zafixuje: " + proof;
        }
        body.getChildren().add(AdvisorView.continuityText(text));
    }

    /**
     * Vybraný zdroj vydává razítka, která <b>nejsou kvalifikovaná</b>.
     *
     * <p>Není to překážka úkonu a nemá jí být: zkoušet si uchování nad testovací autoritou je
     * rozumné a PostSignum DEMO je v nastavení právě proto. Je to ale překážka <b>slibu</b>,
     * který zbytek téhle záložky dává — že se ověřitelnost protáhne do data, kam sahá certifikát
     * autority. Nekvalifikované razítko neprotáhne nic, takže by ten slib byl nepravdivý, a to
     * u jediného údaje, který čtenáře přiměje tlačítko zmáčknout.
     *
     * <p>Mluví se jen o tom, co se <b>změřilo</b>: aplikace si od zdroje vyžádala razítko a
     * v důvěryhodných seznamech k němu kvalifikovanou službu nenašla (viz
     * {@code TimestampingService.measureQuality}). Zdroj, u kterého měření neproběhlo, tenhle
     * odstavec nedostane — nezjištěno není totéž co nekvalifikováno.
     *
     * <p>Co se <b>netvrdí</b>: že takové razítko neobstojí při ověření. Obstát může, má-li ten,
     * kdo ověřuje, jeho autoritu ve vlastním úložišti důvěry — a uživatel téhle aplikace ji tam
     * mít může. Kvalifikované ale není a nástroje, které se řídí důvěryhodnými seznamy, se o něj
     * neopřou; právě to je rozdíl, o který jde.
     */
    private static void addUnqualifiedSourceNote(VBox body, Source tsa) {
        if (!tsa.unqualified()) return;
        body.getChildren().add(AdvisorView.continuityNote(
                "Vybraný zdroj časových razítek" + (tsa.named() ? " (" + tsa.name() + ")" : "")
                + " vydává razítka, která nejsou kvalifikovaná: aplikace si od něj jedno razítko"
                + " vyžádala a v evropských důvěryhodných seznamech k němu kvalifikovanou službu"
                + " nenašla. Úkon se s ním provést dá a aplikace ho nabízí, ale to, oč při něm"
                + " jde, nepřinese — nekvalifikované razítko není důkazem o existenci v čase, na"
                + " který by se dalo spolehnout, a nástroje, které se řídí důvěryhodnými seznamy,"
                + " se o ně neopřou. Na vyzkoušení, jak úkon probíhá a co s dokumentem udělá, to"
                + " stačí; k uchování naostro vyberte v nastavení zdroj, jehož razítka"
                + " kvalifikovaná jsou."));
    }

    /**
     * Proč se nenabízí <b>vůbec nic</b>: v nastavení není vybrán zdroj časových razítek.
     *
     * <p>Nález uživatele (18. 9. 2026). Bez zdroje jsou zavřené všechny čtyři brány naráz —
     * uchování, prodloužení, přerazítkování i zafixování se na něj ptají shodně ({@code
     * tsaConfigured} a {@code settings.getSelectedTsa()} v {@link AdvisorView}) — takže blok
     * skončí ve větvi „nedá se udělat nic“. Ta dosud uměla říct jen to, co brání <b>prvku</b>,
     * a tady prvku nebrání nic: chybí nástroj. Věta „nedá se dělat nic“ bez důvodu přitom čte
     * jako vlastnost dokumentu, ačkoli je to jediný důvod z celé té větve, který čtenář
     * odstraní sám a hned.
     *
     * <p>Blok {@code preservationReachBlock} tutéž věc říká taky, jenže se nad prvkem, na který
     * úkon nedosáhne, nevysází vůbec — a bez zdroje na něj nedosáhne úkon žádný. O dosahu se
     * tu proto nemluví: kam by ověřitelnost sahala, se bez vybraného zdroje stejně neví.
     *
     * @param otherReason zda týž odstavec už jeden důvod řekl. Pak je tenhle důvod „navíc“
     *                    a hlavně se nesmí tvářit jako ten jediný: prvek s vlastní překážkou by
     *                    nabídku uchování nedostal ani se zdrojem. Zafixování by dostal —
     *                    a právě proto věta stojí i tam.
     */
    private static void addNoTsaNote(VBox body, Source tsa, boolean otherReason) {
        if (tsa.chosen()) return;
        body.getChildren().add(AdvisorView.continuityNote(
                (otherReason
                    ? "Nezávisle na tom není v nastavení aplikace vybrán žádný zdroj časových"
                      + " razítek."
                    : "Důvod je v nastavení aplikace: není v něm vybrán žádný zdroj časových"
                      + " razítek.")
                + " Každý z úkonů, které aplikace nabízí — uchování, prodloužení,"
                + " přerazítkování i vytvoření forenzního balíčku — končí novým"
                + " (kvalifikovaným elektronickým) časovým razítkem, a to bez vybraného zdroje"
                + " pořídit nelze; proto se nenabízí ani jeden z nich a tlačítka dole pod"
                + " záložkou nejsou vidět. Zdroj se vybírá v nastavení aplikace; co s dokumentem"
                + " udělat lze, aplikace posoudí znovu při nejbližším ověření."));
    }

    /**
     * @param reach          dokdy lze platnost ověřit i bez přidání nového razítka — pozdější
     *                       z obou čtení
     * @param actionDeadline lhůta k provedení úkonu, tedy datum z nadpisu bloku. Je to
     *                       <b>jiné datum</b> než {@code reach} a plete se to: nad „PAdES B-T
     *                       kvalifikovaný PS 4096.pdf“ je lhůta 18. 5. 2027, kdežto ověřit
     *                       platnost lze do 2. 11. 2031.
     * @param movesNothing   úkon neposune ověřitelnost ani o den. <b>Není to totéž co krátký
     *                       certifikát vybraného zdroje</b>, a právě ta záměna tu byla do
     *                       20. 9. 2026: poznámka hlásila „ověřitelnost neposune vůbec“
     *                       i nad podpisem, kterému úkon prodlouží zdejší ověřitelnost
     *                       o čtyři a půl roku, protože nové razítko zafixuje vložený
     *                       revokační údaj a řadu razítek přitom nezkrátí. Krátký certifikát
     *                       je pak důvod ke zvážení jiného zdroje, ne ke slovu „vůbec“.
     */
    private static void addSourceTooShort(VBox body, Instant reach, java.util.Date tsaUntil,
                                          Gates gates, Instant actionDeadline,
                                          boolean movesNothing) {
        if (tsaUntil == null || reach == null) return;
        Instant until = tsaUntil.toInstant();
        if (ContinuityHorizon.worthTheAction(reach, until)) return;

        boolean backwards = !until.isAfter(reach);
        // „Expiruje dřív" nad dvěma shodnými daty je nesmysl, který si čtenář ověří na první
        // pohled: obě čísla má vedle sebe v jedné větě. A shoda není vzácnost — stávající i nové
        // razítko běžně stojí na certifikátu téže autority.
        boolean same = backwards && Math.abs(java.time.Duration.between(until, reach)
                .toMinutes()) < 1;
        // Znění je od uživatele (8. 9. 2026). Podstatná je jeho druhá půlka: dosavadní text
        // radil jen „vybrat v nastavení zdroj, jehož certifikát platí déle", což je rada pro
        // toho, kdo takový zdroj má. Kdo ho nemá, má počkat — nové razítko dosáhne tam, kam sahá
        // certifikát jeho autority, ať se přidá dnes nebo za rok, takže dřívější zásah nic
        // nepřináší a jen spotřebuje razítko.
        StringBuilder sb = new StringBuilder("Aktuálně vybraný zdroj časových razítek ale"
                + " ověřitelnost " + (!backwards ? "posune jen málo"
                        : movesNothing ? "neposune vůbec"
                        : "neposune dál, než kam dokument dosáhne i bez něj")
                + ": certifikát, na kterém by nové razítko stálo, platí do "
                + TimeFormat.full(until) + ", a ");
        sb.append(same
                ? "sahá tedy právě tam, kam lze platnost ověřit i bez přidání nového časového"
                  + " razítka"
                : backwards
                ? "expiruje tedy dřív, než do kdy lze platnost ověřit bez přidání nového časového"
                  + " razítka (do " + TimeFormat.full(reach) + ")"
                : "prodloužil by ověřitelnost jen o " + gainDescription(reach, until)
                  + " — bez přidání nového časového razítka lze platnost ověřit do "
                  + TimeFormat.full(reach));
        // „Počkat" jen tam, kde je na co. Uplynulá lhůta si o dva odstavce výš říká o opak
        // („Tím spíš je na místě jednat hned“) a dvě rady o načasování proti sobě jsou horší než
        // jedna chybějící.
        sb.append(actionDeadline != null && !elapsed(actionDeadline)
                ? ". Proto je vhodné s " + AdvisorView.actionNounInstr(gates) + " počkat"
                  + " a provést jej s vhodným předstihem před lhůtou "
                  + TimeFormat.full(actionDeadline) + "."
                : ".");
        sb.append(" Případně, jsou-li již dnes k dispozici, využít časová razítka, založená na"
                + " certifikátu s delší platností.");
        body.getChildren().add(AdvisorView.continuityNote(sb.toString()));
    }

    /** Získaný čas slovy, v jednotce, kterou čtenář pozná bez počítání. */
    private static String gainDescription(Instant from, Instant to) {
        java.time.Duration d = java.time.Duration.between(from, to);
        long minutes = d.toMinutes();
        if (minutes < 60) return CzechPlural.count((int) minutes, "minutu", "minuty", "minut");
        long hours = d.toHours();
        if (hours < 48) return CzechPlural.count((int) hours, "hodinu", "hodiny", "hodin");
        long days = d.toDays();
        if (days < 62) return CzechPlural.count((int) days, "den", "dny", "dní");
        return CzechPlural.count((int) (days / 30), "měsíc", "měsíce", "měsíců");
    }


    /**
     * Nadpis, který jmenuje variantu a termín.
     *
     * <p>Od 8. 9. 2026 stojí varianta v nadpisu (dosud „Co nabízí AnEP a do kdy je třeba toho
     * využít“, tedy bez jména). Se sbalenými bloky je nadpis to jediné, co čtenář vidí, než
     * něco rozklikne — a jméno úkonu se musí shodovat s tlačítkem dole, proto se bere ze
     * {@code ContinuityMode} přes {@link AdvisorView#actionNoun}, ne opisem.
     *
     * <p>Tři stavy termínu, a všechny nastávají: datum v budoucnu, datum uplynulé (nadpis to
     * řekne — holé datum by se četlo jako čas, který ještě je) a <b>žádné datum</b>. Poslední
     * je horizont {@code OPEN}: kvalifikované razítko drží podle standardů až do sunsetu, a ten
     * se předem neví. Otázka pak v nadpisu zůstane a odpověď na ni je v prvním odstavci těla.
     *
     * <p>Tam, kam úkon na tenhle prvek nedosáhne, nadpis variantu nejmenuje: tlačítko dole svítí
     * pro dokument, ale tomuhle prvku nepomůže, a jméno v nadpisu by slibovalo, co v těle
     * nestojí.
     */
    static String todoTitle(Gates gates, boolean actionReaches, Instant deadline) {
        return todoTitle(gates, actionReaches, deadline, false);
    }

    /**
     * @param actNow lhůtu stanovit nelze a odpovědí je „nejlépe ihned“ — viz {@link #actNow}.
     *               Mění se s ní i otázka: „do kdy“ se ptá po datu, kdežto tady je odpovědí
     *               okamžik, ne mez.
     */
    static String todoTitle(Gates gates, boolean actionReaches, Instant deadline,
                            boolean actNow) {
        if (!actionReaches) {
            boolean fixOnly = gates.fix()
                    && !gates.preserve() && !gates.prolong() && !gates.restamp();
            return fixOnly
                    ? "AnEP nabízí pouze možnost vytvoření forenzního balíčku"
                    : "Co AnEP nabízí";
        }
        String base = "AnEP nabízí možnost " + AdvisorView.actionNoun(gates) + ".";
        if (actNow) return base + " Kdy je třeba ji využít?  Nejlépe ihned";
        if (deadline == null) return base + " Do kdy je třeba ji využít?";
        return elapsed(deadline)
                ? base + " Lhůta k jejímu využití uplynula " + TimeFormat.full(deadline)
                : base + " Do kdy je třeba ji využít?  Do " + TimeFormat.full(deadline);
    }

    /**
     * Zůstane dokument tím, čím byl — totiž archivním — i po úkonu?
     *
     * <p>Píše se to jen tam, kde dokument opravdu je konformní PDF/A a kde se úkon doopravdy
     * nabízí; jinak by to byl slib o ničem. Právě proto věta stojí tady a ne v bloku „Shoda
     * s normou PDF/A" v Analýze: ten neví, který úkon se nabízí, a musel by mluvit podmíněně.
     * Tady se úkon jmenuje.
     *
     * <p>Je to <b>tvrzení o tom, co bylo změřeno</b>, ne záruka do budoucna, a tak je i psané.
     * Měřeno celým úkonem — vložením materiálu i archivním razítkem ({@code PdfAAfterActionProbe}):
     * 80 dokumentů, které normě vyhovovaly, uchováním i přerazítkováním, dohromady 157
     * dokončených průchodů, ani jedna ztráta shody. Razítko vydávala jednorázová autorita,
     * jejíž zaměnitelnost s ostrou ověřuje {@code TsaCrossCheckProbe} proti testovací TSA
     * PostSignum.
     *
     * <p>Prodloužení má vlastní znění, ale totéž číslo: nad dokumentem PDF je to tentýž kód
     * jako uchování — změřeno, {@code SignatureExtensionService.handles()} nepustilo druhou
     * cestou ani jeden z 60 podepsaných dokumentů PDF/A.
     */
    private static void addArchivalFormNote(VBox body, Gates gates, String pdfaProfile) {
        if (pdfaProfile == null) return;
        body.getChildren().add(AdvisorView.continuityText(
                "Archivní podobu dokumentu úkon zachová. Dokument se hlásí k " + pdfaProfile
                + " a podmínky té normy splňuje; " + AdvisorView.actionNoun(gates)
                + " do něj zapíše revokační údaje a časové razítko jako přírůstkovou"
                + " aktualizaci a podmínek PDF/A se nedotkne."));
    }

    /**
     * Proč je datum v nadpisu pozdější než to, dokdy aplikace platnost ověří.
     *
     * <p>Dvě data bez vysvětlení vypadají jako chyba výpočtu: nahoře „ověříme do roku 2027",
     * o kus níž „úkon stihnete do roku 2032". Obojí přitom platí a souvisí spolu — úkon je
     * právě to, co tu kratší lhůtu odstraní.
     *
     * <p>Jen u meze {@link ContinuityHorizon.Limit#REVOCATION_PROOF}: tam je rozdíl mezi oběma
     * daty vysvětlitelný jednou větou, protože se ví, co ho působí — certifikát vydavatele
     * vloženého revokačního údaje. U ostatních mezí by věta musela znít jinak a žádná se zatím
     * nenaměřila, tak se nevymýšlí.
     */
    /**
     * Vysvětluje se tu rozdíl obou dat? Sdílený predikát, protože na odpovědi visí i to, zda se
     * vysází úvodní odstavec — a kdyby si každé místo počítalo po svém, rozešla by se.
     */
    private static boolean horizonGapExplained(ContinuityHorizon.Horizon anep, Instant deadline) {
        return anep != null && deadline != null
                && anep.kind() == ContinuityHorizon.Kind.UNTIL
                && anep.limit() == ContinuityHorizon.Limit.REVOCATION_PROOF
                && anep.when() != null && deadline.isAfter(anep.when());
    }

    private static void addHorizonGapNote(VBox body, SignatureSummary sig,
                                          ContinuityHorizon.Horizon anep, Instant deadline) {
        if (!horizonGapExplained(anep, deadline)) return;
        // Znění je od uživatele (11. 9. 2026) a nahradilo moje. To vysvětlovalo rozdíl obou dat
        // odkazem na důkaz existence a na expiraci vydavatele — tedy mechanismem, o kterém
        // čtenář téhle záložky nemusí vědět vůbec nic. Tohle znění mluví o tom, co v dokumentu
        // je a co ho odemkne, a mechanismus nechává stranou.
        body.getChildren().add(AdvisorView.continuityText(
                AdvisorView.gendered(sig, "Tento podpis", "Tato pečeť", "Toto časové razítko")
                + " již obsahuje vložené revokační informace, prokazující stav revokace"
                + " podpisového certifikátu. Při ověřování ve striktním režimu (dle standardů)"
                + " je ale nelze využít. Tato možnost se odemkne až připojením dalšího"
                + " (dokumentového) časového razítka, a to nejpozději do expirace certifikátu"
                + " stávajícího časového razítka: do " + TimeFormat.full(deadline) + "."));
        body.getChildren().add(AdvisorView.continuityText(
                "Aplikace AnEP proto nabízí možnost plnohodnotného uchování i po "
                + TimeFormat.full(anep.when()) + ", kdy sama již nedokáže platnost "
                + AdvisorView.genitiveType(sig) + " (postupem podle standardů) ověřit. Proto"
                + " jako lhůtu pro možnost uchování uvádí datum "
                + TimeFormat.full(deadline) + " — konec řádné doby platnosti certifikátu"
                + " autority, která vydala poslední použitelné časové razítko."));
    }

    /**
     * První odstavec bloku: co se nabízí, a rovnou i dokdy. U prodloužení a přerazítkování mu
     * předchází odstavec, proč zrovna tahle varianta a ne plnohodnotné uchování.
     *
     * <p>Text je návrh uživatele z 8. 9. 2026. Doplněné jsou tři větve, které v návrhu nebyly
     * a v datech nastávají: chybějící datum ({@code deadlineSentence} je řeší), uplynulá lhůta
     * a u prodloužení dvojí důvod — viz {@link #prolongPremise}.
     */
    private static void addOfferIntro(VBox body, SignatureSummary sig, Gates gates,
                                      String deadlineSentence) {
        // Dva pády: „pro tento podpis“ (4. p.) a „u tohoto podpisu“ (2. p.). Jeden tvar pro obojí
        // dal „Pro tohoto podpisu“ — vidět na první vykreslené kartě.
        String elAcc = AdvisorView.gendered(sig, "tento podpis", "tuto pečeť",
                "toto časové razítko");
        String elGen = AdvisorView.gendered(sig, "tohoto podpisu", "této pečeti",
                "tohoto časového razítka");
        if (gates.preserve()) {
            body.getChildren().add(AdvisorView.continuityText(
                    "Pro " + elAcc + " nabízí aplikace AnEP možnost plnohodnotného uchování:"
                    + " přidání dosud chybějících informací, potřebných pro pozdější ověření,"
                    + " a jejich zafixování dalším časovým razítkem." + deadlineSentence));
            return;
        }
        // „Z tohoto důvodu" jen tam, kde ten důvod na kartě opravdu stojí. Prázdný odstavec
        // premisy nastane u samostatného razítka, jehož certifikát ještě platí; viz
        // {@link #prolongPremise}.
        String premise = gates.prolong() ? prolongPremise(sig) : restampPremise(sig);
        String lead = premise.isBlank()
                ? "Aplikace AnEP nabízí u " + elGen + " jen možnost označovanou jako "
                : "Z tohoto důvodu nabízí aplikace AnEP u " + elGen + " jen možnost označovanou"
                  + " jako ";
        if (!premise.isBlank()) {
            body.getChildren().add(AdvisorView.continuityText(premise));
        }
        if (gates.prolong()) {
            body.getChildren().add(AdvisorView.continuityText(
                    lead + "prodloužení: přidání chybějících informací, potřebných pro pozdější"
                    + " ověření platnosti, a jejich zafixování dalším časovým razítkem."
                    + deadlineSentence));
            return;
        }
        body.getChildren().add(AdvisorView.continuityText(
                lead + "přerazítkování: přidání těch chybějících informací potřebných pro"
                + " pozdější ověření platnosti, které má aplikace k dispozici, a jejich"
                + " zafixování dalším časovým razítkem." + deadlineSentence));
    }

    /**
     * Proč prodloužení a ne uchování — a jsou to <b>dva</b> důvody, ne jeden.
     *
     * <p>Brána je {@code !(situace 31 && situace 32)}, takže ji spustí buď expirovaný certifikát
     * posledního razítka (chybí 32), nebo mezera v řadě (chybí 31: některé razítko vzniklo až po
     * skončení platnosti certifikátu toho předchozího), nebo obojí. Text, který by popisoval jen
     * první z nich, by nad dokumentem s mezerou tvrdil něco, co se v něm nestalo.
     *
     * <p>Samostatné časové razítko není „opatřeno razítkem“ — samo jím je. A je to typický
     * uživatel prodloužení: razítko z roku 2014, jehož certifikát TSU skončil v roce 2018
     * a které drží jen důvěryhodný seznam.
     */
    private static String prolongPremise(SignatureSummary sig) {
        String gen = AdvisorView.genitiveType(sig);
        boolean lone = ContinuityHorizon.isTimestampElement(sig);
        // Proč se samostatné razítko vylučuje, stojí u chainAlreadyBroken(); vidět to bylo na
        // první vykreslené kartě („priklad pouze s razitkem.pdf“).
        boolean chainBroken = chainAlreadyBroken(sig);
        boolean lastExpired = !sig.getDetectedSituations().contains(32);
        StringBuilder sb = new StringBuilder();
        if (lastExpired) {
            sb.append(lone
                    ? "Certifikát, na kterém je toto časové razítko založeno, už expiroval."
                      + " Přesto mohlo být ověřeno jako platné"
                    : "U " + gen + " je alespoň jedno časové razítko, jehož certifikát už"
                      + " expiroval. Přesto mohlo být ověřeno jako platné a posloužit jako důkaz"
                      + " o existenci " + gen + " v dřívějším čase (PoE)");
            sb.append(" — ale jen díky tomu, že se platnost razítek posuzuje podle standardů,"
                    + " tedy „volněji“: podle toho, zda si služba, která razítko vydala, dosud"
                    + " drží status kvalifikované služby (zda u ní ještě nedošlo k tzv."
                    + " sunsetu).");
        }
        if (chainBroken) {
            sb.append(lastExpired ? " Řada časových razítek je navíc přerušena: " : "Řada"
                    + " časových razítek " + gen + " je přerušena: ");
            sb.append("některé razítko vzniklo až po skončení platnosti certifikátu toho"
                    + " předchozího, takže pro toho, kdo razítka posuzuje podle platnosti jejich"
                    + " certifikátů, je v řadě mezera, kterou už nic nezacelí.");
        }
        // Zbývá případ, kdy branou prodloužení projde prvek, u kterého ani jedna z obou vět
        // neplatí — samostatné razítko s dosud platným certifikátem, kterému se uchování
        // nenabízí, protože není co doplnit. Vymýšlet mu důvod se nebude; odstavec prostě
        // odpadne a nabídka se řekne bez „z tohoto důvodu".
        return sb.toString();
    }

    /**
     * Je řada časových razítek přerušená <b>už dnes</b>?
     *
     * <p>Rozlišení, na kterém stojí dva různé texty, a proto na jednom místě: brána prodloužení
     * propouští dva různé stavy — řadu, ve které mezera <b>už je</b> (chybí situace 31), a řadu
     * dosud souvislou, jejímuž poslednímu razítku ale vypršel certifikát (chybí situace 32),
     * takže mezera teprve vznikne tím, že se nové razítko přidá. Tvrdit to druhé o prvním případě
     * znamená mluvit o budoucnosti tam, kde je to hotová věc.
     *
     * <p>Samostatné razítko žádnou řadu netvoří a situaci 31 nedostává nikdy, takže by mu věta
     * o přerušené řadě vyšla vždycky.
     */
    private static boolean chainAlreadyBroken(SignatureSummary sig) {
        return !ContinuityHorizon.isTimestampElement(sig)
                && !sig.getDetectedSituations().contains(31);
    }

    /**
     * Proč přerazítkování a ne uchování. Dvě odpovědi, protože branou vedou dvě cesty.
     *
     * <p>Obvyklá: platnost obstojí jen u toho, kdo si vypomůže revokačními údaji, které přísné
     * čtení nepřipouští. Druhá přibyla 8. 9. 2026 s vetem vlastní kotvy — prvek s kladným
     * verdiktem a připnutým kořenem sem spadne taky, jenže tomu chybí něco jiného než revokační
     * údaj a vlastní zásoba mu nepomůže.
     */
    private static String restampPremise(SignatureSummary sig) {
        String gen = AdvisorView.genitiveType(sig);
        // Třetí cesta, přibyla 8. 9. 2026: rozdíl není v tom, co má kdo po ruce, nýbrž v tom,
        // podle čeho se posuzují časová razítka. Neporušené razítko, které přesto neobstálo, je
        // toho jediný a přesný příznak — a je to táž podmínka, jakou se řídí brána
        // ({@code AdvisorView.tolerantReadingStillWorks}), takže si text a tlačítko nemohou
        // protiřečit. Bez téhle větve by nad
        // „LTA_kvalifikovany_2012_01_20_razitko_2016_do_2021_preserved_2020-11-14“ stálo, že
        // šanci mají validátory s vlastní zásobou revokačních informací — jenže o revokace tam
        // vůbec nejde.
        if (ContinuityHorizon.timestampJudgedDifferently(sig)) {
            return "Rozdíl je v hodnocení časových razítek. Zdejší ověření se ptá, zda služba,"
                    + " která razítko vydala, má v důvěryhodných seznamech stále kvalifikovaný"
                    + " status; validátory jako CzechPOINT, podatelny OVM či program Signer se"
                    + " ptají jen na řádnou dobu platnosti certifikátu, na kterém razítko stojí."
                    + " Některé razítko " + gen + " je neporušené, a přesto zde neobstálo — takže"
                    + " šanci ověřit platnost mají dnes už jen tyto nástroje.";
        }
        if (sig.isTrustedViaCustomStore()) {
            return "Šanci ověřit platnost " + gen + " mají jen takové nástroje, které důvěřují"
                    + " téže kotvě jako aplikace — a ta pochází z jejího vlastního úložiště"
                    + " certifikátů, evropské důvěryhodné seznamy ji neuvádějí (viz blok výše).";
        }
        return "Šanci ověřit platnost " + gen + " mají jen takové validátory, které mají vlastní"
                + " zásobu revokačních informací, případně uplatňují ústupky vůči ověřování podle"
                + " standardů.";
    }

    /**
     * Čím je lhůta určena, jednou jmennou frází do věty „…, což je …“.
     *
     * <p>Doplňuje se jen tam, kde odvození chybí; kde je, stálo by na kartě totéž dvakrát.
     * U {@code SUNSET}, {@code FIXED_TIME} a {@code NONE} se vrací {@code null} — tam žádné datum
     * není a otázku „do kdy“ zodpovídá {@link #noDeadlineReason}.
     */
    private static String deadlineNoun(SignatureSummary sig, Limit limit, Instant deadline) {
        return switch (limit) {
            case SIGNING_CERT -> "konec řádné doby platnosti podpisového certifikátu";
            case REVOCATION -> "poslední okamžik, do kterého lze počítat s dostupností"
                    + " potřebných revokačních informací";
            // Datum je maximum ze dvou: dokdy je uznatelný vložený údaj (platnost jeho vydavatele)
            // a dokdy si lze stáhnout čerstvý (platnost certifikátu, o kterém vypovídá) — viz
            // ReportMapper.networkOutlastsEmbedded(). Pojmenovat natvrdo jen tu první půlku
            // znamená u poloviny případů ukázat na certifikát, který o tom datu nerozhodl.
            // Nález uživatele z 11. 9. 2026 nad „PAdES B-T kvalifikovany ICA s OCSP v ADBE.pdf":
            // blok tvrdil „konec platnosti certifikátu vydavatele revokačního údaje" o datu
            // 24. 8. 2027, kdežto vydavateli skončila platnost 23. 9. 2026 a to datum patřilo
            // podpisovému certifikátu.
            case REVOCATION_PROOF -> signingCertDecided(sig, deadline)
                    ? "konec řádné doby platnosti podpisového certifikátu: do té doby si lze"
                      + " stáhnout čerstvý revokační údaj, kdežto ten vložený v dokumentu už"
                      + " uznat nelze"
                    : "konec platnosti certifikátu vydavatele revokačního údaje vloženého"
                      + " v dokumentu";
            case TIMESTAMP_CERT -> "konec řádné doby platnosti certifikátů autorit, které vydaly"
                    + " použitelná časová razítka — tedy to nejzazší z nich";
            // Spodní mez, ne konec — proto ta druhá půlka věty. Bez ní by tvrdila víc, než co
            // o kvalifikovaném razítku platí, a odporovala by nadpisu „Nejméně do“ o dva bloky
            // výš.
            case QUALIFIED_TIMESTAMP_CERT -> "nejzazší konec řádné doby platnosti v řetězech"
                    + " certifikátů, na kterých časová razítka stojí; po něm může kvalifikované"
                    + " razítko platit dál, ale předem se to zjistit nedá";
            case SUNSET, TRUST_ANCHOR, FIXED_TIME, NONE -> null;
        };
    }

    /** Rozhodl o tom datu podpisový certifikát, nebo vydavatel vloženého údaje? */
    private static boolean signingCertDecided(SignatureSummary sig, Instant deadline) {
        return sig != null && deadline != null && deadline.equals(sig.getCertNotAfter());
    }

    /** Proč se lhůta stanovit nedá. Nadpis se ptá „do kdy“, takže odpověď musí být. */
    private static String noDeadlineReason(Horizon anep, Horizon tolerant) {
        Limit limit = anep.kind() == Kind.OPEN ? anep.limit() : tolerant.limit();
        return switch (limit) {
            case SUNSET -> "platnost posledního časového razítka drží kvalifikovaný status"
                    + " služby, která je vydala, a kdy skončí, se předem neví";
            case FIXED_TIME -> "ověřovalo se k pevně zadanému okamžiku v minulosti, a ten se sám"
                    + " nemění";
            case TRUST_ANCHOR -> "podpisový certifikát je sám kotvou důvěry, a tu drží status"
                    + " služby v důvěryhodném seznamu, o jehož konci se předem neví";
            default -> "žádný z článků, na kterých dnešní závěr stojí, nemá pevně daný konec";
        };
    }

    /**
     * Jaký bude výsledek a <b>kdo ho ověří</b> — celá odpověď, pro blok u jednoho prvku.
     *
     * <p>Přerazítkování a prodloužení nejsou slabší uchování; jsou to odpovědi na dokument, na
     * který jedno ze dvou čtení pravidel už nedosáhne. Kdo to neví, čeká po úkonu výsledek, který
     * nepřijde, a bude ho hledat jako závadu.
     *
     * <p>Dokumentový blok si obě půlky bere zvlášť: {@link #addReachResult} u každého prvku,
     * {@link #addReachExtras} jednou za posledním z těch, na které úkon dosáhne.
     */
    private static void addReachExplained(VBox body, SignatureSummary sig, Gates gates,
                                          java.util.function.Consumer<ContinuityView.Outlook> open) {
        addReachResult(body, sig, gates, open);
        addReachExtras(body, gates, sig.isTrustedViaCustomStore(), chainAlreadyBroken(sig));
    }

    /** První odstavec dvojice: jaký bude výsledek u tohohle prvku a kdo ho ověří. */
    private static void addReachResult(VBox body, SignatureSummary sig, Gates gates,
                                       java.util.function.Consumer<ContinuityView.Outlook> open) {
        String gen = AdvisorView.genitiveType(sig);
        // Vlastní kotva důvěry mění odpověď na otázku „kdo to ověří", a proto patří sem. Úkon
        // doplní materiál, kotvu doplnit nemůže — slib „všemi obvyklými nástroji" by u prvku,
        // jehož kořen evropské důvěryhodné seznamy neuvádějí, byl nepravdivý zrovna v té jediné
        // vlastnosti, kvůli které tahle věta na kartě stojí. Blok o CzechPOINTu o kus výš to
        // o témž prvku říká rovnou („nedokáží“), takže bez tohohle rozlišení si dvě věty na
        // jedné kartě odporovaly. Naměřeno sondou ContinuityConsistencyScan, která na to má tři
        // pravidla: na „PAdES LTA zaruceny PSS_Do_02_05_2032.pdf“ hlásila slib „ověřit všemi
        // obvyklými nástroji“ u kladného verdiktu s připnutou kotvou, na „PAdES T zaruceny
        // ICA.pdf“ slib o CzechPOINTu u přerazítkování.
        boolean ownAnchor = sig.isTrustedViaCustomStore();
        String el = AdvisorView.gendered(sig, "podpis", "pečeť", "časové razítko");
        // Přívlastek se skloňuje s ním. „Výsledkem ale bude takový pečeť“ vyšlo hned na první
        // vykreslené kartě s pečetí („Pecet kvalifikovana.pdf“).
        String such = AdvisorView.gendered(sig, "takový", "taková", "takové");
        String which = AdvisorView.gendered(sig, "který", "kterou", "které");
        String whose = AdvisorView.gendered(sig, "jehož", "jejíž", "jehož");
        // Úroveň se slibuje jen tam, kde ji dokument dostane. Materiál se u přerazítkování
        // i prodloužení vkládá do slovníku /DSS, což je konstrukce na úrovni PDF, která o poli
        // SubFilter nic neví — nereferenční podpis (PDF-NOT-ETSI) tedy zůstane nereferenční
        // a „úroveň LTA" by v něm čtenář hledal marně. A právě pro takové podpisy přerazítkování
        // vzniklo.
        String level = AdvisorView.isPdfNotEtsi(sig.getFormat()) ? "" : " (v úrovni LTA)";
        // Kam výsledek dosáhne, je nejdůležitější věta celé záložky, takže se neschovává za
        // odkaz celá: zůstává v ní ta jedna vlastnost, na které čtenáři záleží (kdo to ověří),
        // a odkaz vede na výklad varianty, kde je totéž s odůvodněním.
        if (gates.preserve()) {
            body.getChildren().add(AdvisorView.continuityLinked(
                    "Výsledkem bude " + such + " " + el + level + ", "
                    + (ownAnchor
                        ? which + " si ponese vše potřebné s sebou, takže mu revokační informace"
                          + " chybět nebudou. Ověřit jeho platnost ale dokáže jen ten, kdo"
                          + " důvěřuje téže kotvě jako aplikace — a ta pochází z jejího vlastního"
                          + " úložiště certifikátů, evropské důvěryhodné seznamy ji neuvádějí"
                        // SHA3: „ve všech obvyklých" by odporovalo bloku o CzechPOINTu těsně nad
                        // tímhle, který říká, že ho neověří (pokyn uživatele 4. 10. 2026).
                        : ContinuityHorizon.unsupportedDigest(sig)
                        ? which + " by mělo být možné ověřit ve validátorech podle standardů —"
                          + " ne však v CzechPOINTu a nejspíš ani v podatelnách, které hashovací"
                          + " funkce SHA3 zatím nepodporují"
                        : which + " by mělo být možné ověřit ve všech obvyklých validátorech")
                    + "; viz [varianta „uchování“ a „prodloužení“](PRESERVE_PROLONG)"
                    + " ve výkladu na začátku záložky.", open));
        } else if (gates.restamp()) {
            body.getChildren().add(AdvisorView.continuityLinked(
                    "Výsledkem ale bude " + such + " " + el + level + ", u "
                    + AdvisorView.gendered(sig, "kterého", "které", "kterého")
                    + " bude šance na úspěšné ověření platnosti jen "
                    + (ownAnchor
                        ? "u toho, kdo důvěřuje téže kotvě jako aplikace — tedy certifikátu"
                          + " z jejího vlastního úložiště, který evropské důvěryhodné seznamy"
                          + " neuvádějí"
                        : ContinuityHorizon.timestampJudgedDifferently(sig)
                        ? "ve výše zmíněných validátorech, tedy v těch, které časová razítka"
                          + " posuzují podle řádné doby platnosti jejich certifikátů"
                        : "ve výše zmíněných validátorech, tedy v těch s vlastní zásobou"
                          + " revokačních informací")
                    + "; viz [varianta „přerazítkování“](RESTAMP) ve výkladu na začátku záložky.",
                    open));
        } else if (gates.prolong()) {
            body.getChildren().add(AdvisorView.continuityLinked(
                    "Výsledkem ale bude " + such + " " + el + level + ", " + whose + " platnost by"
                    + " mělo být možné ověřit jen v takových validátorech, které přistupují"
                    + " k časovým razítkům stejně — tedy podle standardů, „volněji“. Například"
                    + " v aplikaci AnEP, ve službě DSS a ve validátorech založených na kódu"
                    + " knihovny DSS; viz [varianta „uchování“ a"
                    + " „prodloužení“](PRESERVE_PROLONG) ve výkladu na začátku záložky.", open));
        }
    }

    /**
     * Dva odstavce, které patří k <b>variantě</b>, ne k prvku: co prodloužení neudrží a co udrží.
     *
     * <p>Vytaženo z {@link #addReachResult} ven 9. 9. 2026. U prvku se sázely za odstavcem
     * o výsledku a byly na něm nezávislé; v dokumentovém bloku by se s výčtem prvků zopakovaly
     * tolikrát, kolik je prvků, ačkoli pokaždé říkají totéž.
     *
     * @param ownAnchor kotva důvěry z vlastního úložiště — u dokumentu stačí, že ji má aspoň
     *                  jeden z prvků, na které úkon dosáhne: ověřit dokument znamená ověřit je
     *                  všechny.
     * @param gapAlready řada je přerušená už dnes (viz {@link #chainAlreadyBroken}) — u dokumentu
     *                  opět stačí jediný takový prvek, protože i pak platí o souboru jako celku,
     *                  že mezeru v něm striktní nástroj najde bez ohledu na nové razítko
     */
    private static void addReachExtras(VBox body, Gates gates, boolean ownAnchor,
                                       boolean gapAlready) {
        if (!gates.prolong()) return;
        // „Spíše ne", ne „nikoli". Osmnáct dokumentů předložených Úschovně Czech POINT
        // 2. 9. 2026 potvrdilo předpověď (14 shod ze 16 vypovídajících), ale ne mechanismus:
        // v jedné hlášce označili za platné razítko s certifikátem propadlým tři roky
        // předtím. Celá záložka o tomhle okruhu mluví v možnosti a končí poznámkou „Jde
        // o odhad, ne o jistotu"; kategorické „nikoli" by tu jedinou opatrnost zahodilo.
        body.getChildren().add(AdvisorView.continuityText(
                "Spíše ne už v nástrojích, které časová razítka hodnotí „striktně“, podle"
                + " řádné doby platnosti jejich certifikátů — jako CzechPOINT či podatelny"
                + " OVM."
                + (gapAlready
                    ? " V řadě, kterou tyto nástroje posuzují, je mezera už dnes a nové razítko"
                      + " ji nezacelí — vznikne až za ní."
                    : " Nové razítko totiž vznikne až po expiraci certifikátu toho dosavadního,"
                      + " takže v řadě, kterou tyto nástroje posuzují, mezera vznikne.")
                + (ownAnchor
                    ? " A platnost neověří ani ten, kdo nedůvěřuje téže kotvě jako aplikace —"
                      + " tu evropské důvěryhodné seznamy neuvádějí (viz blok výše)."
                    : "")));
        body.getChildren().add(AdvisorView.continuityText(
                "Udrží ale to podstatné: razítko od služby, která kvalifikovaný status má"
                + " dnes, doloží, že celý tenhle stav existoval, dokud jej měla i služba"
                + " původní — a takový důkaz přežije sunset."));
    }

    // ── Dokument jako celek ──────────────────────────────────────────────────────────────────

    /**
     * Poslední dva bloky pro dokument, ve kterém je prvků víc než jeden.
     *
     * <p><b>Proč nestojí u prvků.</b> Obě otázky, na které odpovídají — dokdy lze ověřitelnost
     * zachovat a co pro to AnEP nabízí — jsou otázky o <b>souboru</b>: nové časové razítko kryje
     * celý dokument ({@code ByteRange} razítka dokumentu podle ETSI EN 319 142-1) a tlačítko dole
     * je jedno. U pěti prvků se ale obojí vysázelo pětkrát, pokaždé s vlastním datem v nadpisu,
     * a z toho čtenář vyčetl pět nabídek k pěti různým úkonům. Od 9. 9. 2026 tedy stojí jednou,
     * pod výčtem prvků, a to, čím se prvky liší, říká výčet uvnitř bloku „AnEP nabízí možnost…“
     * ({@link #addPerElement}). Pokyn uživatele.
     *
     * <p><b>Lhůta je nejbližší z lhůt dosažených prvků.</b> Každý prvek má svou; ta nejbližší je
     * poslední okamžik, kdy úkon dá <i>všem</i> dosaženým prvkům, co dát může. Pozdější datum by
     * slibovalo víc, než co po něm platí — a čtenář, který se řídí nadpisem, by o část užitku
     * přišel, aniž by se to kde dozvěděl.
     *
     * @param combination popis souběhu — kolik je prvků, že úkon je jeden a komu prospěje.
     *                    Staví ho {@link AdvisorView}, protože stojí na jeho výpočtu horizontu
     *                    dokumentu, a od 9. 9. 2026 je součástí bloku „AnEP nabízí možnost…“:
     *                    samostatný blok „Souběh autentizačních prvků“ vedle nabídky mluvil
     *                    o téže věci podruhé.
     */
    static VBox documentBlocks(java.util.List<Element> elements, Gates gates,
                               java.util.List<Node> combination, Source tsa, String pdfaProfile,
                               java.util.function.Consumer<ContinuityView.Outlook> open) {
        return documentBlocks(elements, gates, combination, tsa, pdfaProfile, open, null);
    }

    /** @param document analyzovaný soubor, do připomínky do kalendáře; viz {@link #build}. */
    static VBox documentBlocks(java.util.List<Element> elements, Gates gates,
                               java.util.List<Node> combination, Source tsa, String pdfaProfile,
                               java.util.function.Consumer<ContinuityView.Outlook> open,
                               java.io.File document) {
        java.util.List<Element> reached = elements.stream()
                .filter(Element::actionReaches).toList();
        Instant reach = documentReach(reached);
        Instant anepReach = documentAnepReach(reached);
        Instant deadline = documentDeadline(reached, gates);

        VBox blocks = new VBox(4);
        if (!reached.isEmpty()) {
            // Úkon je jeden a provede se pro všechny naráz, takže stačí, že je co vložit
            // (nebo co zafixovat) u kteréhokoli z dosažených prvků.
            blocks.getChildren().add(preservationReachBlock(anepReach, reach, tsa,
                    gates.preserve(),
                    reached.stream().anyMatch(ContinuityElementBlocks::willEmbed),
                    reached.stream().anyMatch(ContinuityElementBlocks::willFix)));
        }
        blocks.getChildren().add(documentTodoBlock(elements, reached, gates, combination,
                reach, anepReach, deadline, tsa, pdfaProfile, open, document));
        return blocks;
    }

    /**
     * Dokdy dokument dosáhne i bez zásahu — <b>nejdřívější</b> z hodnot dosažených prvků.
     *
     * <p>Slouží jediné otázce: přinese vybraný zdroj časových razítek vůbec něco? Přinese, pokud
     * jeho certifikát sahá dál, než kam dosáhne aspoň jeden z prvků — a to je právě srovnání
     * s tím nejbližším. Maximum by nad dokumentem, jehož jeden prvek dosáhne daleko, umlčelo
     * poznámku o krátkém zdroji i tam, kde ostatním prvkům opravdu pomůže.
     *
     * <p>{@code null} znamená „srovnávat nelze“ — buď není co srovnávat, nebo je aspoň jeden
     * horizont otevřený; obojí zachází {@link #sourceMovesNothing} stejně a úkon má přínos.
     */
    private static Instant documentReach(java.util.List<Element> reached) {
        Instant earliest = null;
        for (Element e : reached) {
            Instant s = salvageableUntil(e.anep(), e.tolerant());
            if (s == null) return null;
            if (earliest == null || s.isBefore(earliest)) earliest = s;
        }
        return earliest;
    }

    /**
     * Dokdy dokáže platnost ověřit aplikace sama — <b>nejdřívější</b> z prvků, na které úkon
     * dosáhne; tedy to, co úkon za celý dokument zachraňuje.
     *
     * <p>Minimum ze stejného důvodu jako u {@link #documentReach}: stačí, že úkon pomůže aspoň
     * jednomu prvku. Prvek bez data (otevřený horizont, nebo ten, u kterého se ověřit
     * nepodařilo) se přeskakuje — {@link #reachBeforeAction} si pak vezme nejzazší mez jako
     * dosud.
     */
    private static Instant documentAnepReach(java.util.List<Element> reached) {
        Instant earliest = null;
        for (Element e : reached) {
            Instant a = anepDeadline(e.anep());
            if (a == null) continue;
            if (earliest == null || a.isBefore(earliest)) earliest = a;
        }
        return earliest;
    }

    /**
     * Lhůta k úkonu za celý dokument — nejbližší z lhůt prvků, na které úkon dosáhne, <b>které
     * ještě neuplynuly</b>.
     *
     * <p>Prvky bez data (otevřený horizont) se nepočítají: neomezují nic, takže by je minimum
     * jen zahodilo. Když datum nemá žádný, vrací se {@code null} a nadpis zůstane u otázky.
     *
     * <p>Uplynulá lhůta jednoho prvku neomezuje, co úkon dá ostatním, a do minima proto nepatří.
     * Do 25. 9. 2026 patřila: nad dokumentem se čtyřmi razítky (uchováván 2014, 2019 a 2024)
     * vyhrála lhůta prvku č. 2, 4. 10. 2019, a nadpis hlásil „Lhůta k jejímu využití uplynula“,
     * ačkoli prvek č. 4 vydrží do 13. 10. 2030 a zachovat ověřitelnost šlo do 2. 8. 2034 (nález
     * uživatele). Jen když uplynuly všechny, řekne to nadpis — a to s tou nejpozdější.
     */
    private static Instant documentDeadline(java.util.List<Element> reached, Gates gates) {
        Instant earliestAhead = null;
        Instant latestElapsed = null;
        boolean anyUndated = false;
        for (Element e : reached) {
            Instant d = actionDeadline(e.sig(), e.anep(), e.tolerant(), gates);
            if (d == null) {
                anyUndated = true;
            } else if (elapsed(d)) {
                if (latestElapsed == null || d.isAfter(latestElapsed)) latestElapsed = d;
            } else if (earliestAhead == null || d.isBefore(earliestAhead)) {
                earliestAhead = d;
            }
        }
        if (earliestAhead != null) return earliestAhead;
        return anyUndated ? null : latestElapsed;
    }

    /** Čísla prvků, u kterých lhůtu stanovit nelze — v pořadí výčtu nad blokem. */
    private static java.util.List<Integer> actNowElements(java.util.List<Element> elements) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            Element e = elements.get(i);
            if (e.actionReaches() && actNow(e.sig())) out.add(i + 1);
        }
        return out;
    }

    /**
     * Proč se lhůta za dokument stanovit nedá. Datum se sem nepíše — je u toho prvku, kterého
     * se týká, ve výčtu níž; tady by při dvou takových prvcích musela vyhrát jedna ze dvou dat.
     */
    private static String documentActNowExplanation(java.util.List<Integer> numbers) {
        return "Lhůtu k využití této možnosti stanovit nelze. U " + elementList(numbers)
                + " stojí dnešní závěr na revokačním údaji, který si aplikace stáhla on-line od"
                + " jeho vydavatele — a doba, po kterou se na jeho dostupnost dalo spoléhat, už"
                + " uplynula. Že se ho podařilo stáhnout dnes, neříká nic o tom, zda půjde"
                + " stáhnout i příště; až ho vydavatel přestane zveřejňovat, nebude co do"
                + " dokumentu vložit.";
    }

    /** Čísla prvků, u kterých je nejlépe ihned kvůli algoritmu razítka — viz {@link #weakKeyNow}. */
    private static java.util.List<Integer> weakKeyElements(java.util.List<Element> elements,
                                                           Gates gates) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            Element e = elements.get(i);
            if (!e.actionReaches() || actNow(e.sig())) continue;
            Instant d = actionDeadline(e.sig(), e.anep(), e.tolerant(), gates);
            if (weakKeyNow(e.sig(), d)) out.add(i + 1);
        }
        return out;
    }

    /**
     * Proč nejlépe ihned — za dokument. Oba důvody mohou nastat naráz u různých prvků; první
     * věta „lhůtu stanovit nelze“ patří tomu se staženým údajem, a když tam není, otevře text
     * věta o algoritmu.
     */
    private static String documentNowExplanation(java.util.List<Integer> now,
                                                 java.util.List<Integer> weak,
                                                 java.util.List<Element> elements, Source tsa) {
        StringBuilder sb = new StringBuilder();
        if (!now.isEmpty()) sb.append(documentActNowExplanation(now));
        if (!weak.isEmpty()) {
            // Nejdřívější datum vyhrává: rozhoduje prvek, kterému čas dojde nejdřív.
            SignatureSummary.TimestampKey first = null;
            java.util.Set<String> kinds = new java.util.LinkedHashSet<>();
            for (int n : weak) {
                SignatureSummary.TimestampKey k = elements.get(n - 1).sig().getNewestTimestampKey();
                kinds.add(k.description());
                if (first == null || k.reliableUntil().isBefore(first.reliableUntil())) first = k;
            }
            if (sb.length() > 0) sb.append(' ');
            else sb.append("Tuto možnost je vhodné využít nejlépe ihned. ");
            sb.append("U ").append(elementList(weak))
              .append(" je nejnovější časové razítko podepsané algoritmem ")
              .append(String.join(" či ", kinds)).append(", ")
              .append(weakKeyClause(first))
              .append(weakSourceAddendum(tsa));
        }
        return sb.toString();
    }

    /** „prvku č. 2“, „prvků č. 1 a 3“ — tvar do vazby s předložkou „u“. */
    private static String elementList(java.util.List<Integer> numbers) {
        StringBuilder sb = new StringBuilder(numbers.size() == 1 ? "prvku č. " : "prvků č. ");
        for (int i = 0; i < numbers.size(); i++) {
            if (i > 0) sb.append(i == numbers.size() - 1 ? " a " : ", ");
            sb.append(numbers.get(i));
        }
        return sb.toString();
    }

    /** Blok „AnEP nabízí možnost…“ za celý dokument. Větve odpovídají těm v {@link #todoBlock}. */
    private static VBox documentTodoBlock(java.util.List<Element> elements,
                                          java.util.List<Element> reached, Gates gates,
                                          java.util.List<Node> combination,
                                          Instant reach, Instant anepReach, Instant deadline,
                                          Source tsa, String pdfaProfile,
                                          java.util.function.Consumer<ContinuityView.Outlook> open,
                                          java.io.File document) {
        VBox body = body();
        body.getChildren().addAll(combination);
        boolean anyOffer = gates.preserve() || gates.prolong() || gates.restamp();
        boolean elapsedDeadline = deadline != null && elapsed(deadline);
        // Jedny meze pro celý blok — táž dvojice, jakou si počítá todoBlock() u prvku.
        boolean movesNothing = sourceMovesNothing(
                reachBeforeAction(anepReach, reach, gates.preserve()),
                reachAfterAction(reach, tsa.until(), gates.preserve()));
        // Stačí jeden prvek: úkon je jeden a provede se pro všechny naráz, takže o tempu
        // rozhoduje ten, jehož materiál může zmizet nejdřív. Viz {@link #actNow}.
        java.util.List<Integer> now = actNowElements(elements);
        // Prvky, u kterých je nejlépe ihned kvůli algoritmu nejnovějšího razítka (viz
        // {@link #weakKeyNow}); prvek se staženým údajem se sem nepočítá, má svůj důvod.
        java.util.List<Integer> weak = weakKeyElements(elements, gates);
        boolean actNow = !now.isEmpty() || !weak.isEmpty();

        if (!reached.isEmpty()) {
            addDocumentOfferIntro(body, gates, actNow
                    ? "" : deadlineSentence(reached, gates, deadline));
            addUnqualifiedSourceNote(body, tsa);
            String pace = actNow ? documentNowExplanation(now, weak, elements, tsa)
                    : documentPace(deadline, movesNothing);
            if (!pace.isBlank()) {
                body.getChildren().add(elapsedDeadline || actNow
                        ? AdvisorView.continuityNote(pace)
                        : AdvisorView.continuityText(pace));
            }
            body.getChildren().add(AdvisorView.continuityText(
                    "Spustí se tlačítkem „" + AdvisorView.actionVerb(gates)
                    + "“ dole pod záložkou. Úkon je dokumentový: nové časové razítko kryje celý"
                    + " soubor, takže se provádí jednou pro všechny prvky v něm."));
            addArchivalFormNote(body, gates, pdfaProfile);
            // Výčet po prvcích, a v něm i dva odstavce o variantě prodloužení: navazují
            // na větu „Výsledkem ale bude…“, která je od 9. 9. 2026 u každého prvku, takže
            // stát nad výčtem by je utrhlo od toho, k čemu se vážou.
            addPerElement(body, elements, gates, actNow ? null : deadline, true,
                    reached.stream().anyMatch(e -> e.sig().isTrustedViaCustomStore()), open);
            addSourceTooShort(body, reach, tsa.until(), gates, actNow ? null : deadline,
                    movesNothing);
            addDocumentMaterialNote(body, reached, gates, actNow);
            // Rada „počkejte hodinu“ platí o dokumentu, ne o prvku: razítko se pořizuje jednou.
            // Stačí tedy první prvek, u kterého na ni dojde — u ostatních by to byla táž věta.
            reached.stream()
                    .filter(e -> AdvisorView.fresherRevocationLikelySoon(e.sig(),
                            "TOTAL_PASSED".equals(e.verdict())))
                    .findFirst()
                    .ifPresent(e -> addWaitBeforePreserving(body, e.sig(),
                            "TOTAL_PASSED".equals(e.verdict())));            if (gates.fix()) {
                body.getChildren().add(AdvisorView.continuityLinked(
                        "Jako další možnost nabízí aplikace"
                        + (gates.preserve() ? " v expertním módu" : "") + " zafixování —"
                        + " tlačítko „Vytvořit forenzní balíček“ dole pod záložkou. Ověřitelnost"
                        + " dokumentu neprodlouží; co balíček obsahuje a k čemu je, stojí"
                        + " [na konci výkladu na začátku záložky](OPTIONS).", open));
            }
        } else if (gates.fix()) {
            // Věta „ani u jednoho z prvků" tvrdí o každém prvku totéž, a to nemusí platit:
            // úkon se rozhoduje podle podpisů, takže dokument může nést platné samostatné
            // razítko, kterému nic nebrání, a přesto se zachovat nedá. Kde to tak je, mluví
            // věta o dokumentu, ne o prvcích; výčet pod ní pak u každého řekne, jak je na tom.
            boolean allBlocked = elements.stream().allMatch(e -> e.obstacle() != null);
            body.getChildren().add(AdvisorView.continuityText(allBlocked
                    ? "Ani u jednoho z prvků tohoto dokumentu už není možné, nebo účelné,"
                      + " zachovávat ověřitelnost; u kterého proč, stojí níže."
                    : "Zachovat ověřitelnost tohoto dokumentu nelze. Úkon se rozhoduje za"
                      + " dokument jako celek; jak je na tom který prvek, stojí níže."));
            body.getChildren().add(AdvisorView.continuityLinked(
                    "Aplikace AnEP nabízí pro tento dokument pouze možnost vytvoření tzv."
                    + " forenzního balíčku: shromáždit vše, co o dokumentu ví a má aktuálně"
                    + " k dispozici, zabalit to do jednoho celku (kontejneru ASiC) a ten opatřit "
                    + stampInstr(tsa) + " coby důkazem o existenci"
                    + " v čase; co přesně balíček obsahuje, stojí [na konci výkladu na začátku"
                    + " záložky](OPTIONS).", open));
            addUnqualifiedSourceNote(body, tsa);
            body.getChildren().add(AdvisorView.continuityText(
                    "Spustí se tlačítkem „Vytvořit forenzní balíček“ dole pod záložkou."
                    + " Ověřitelnost samotného dokumentu to neprodlouží."));
            body.getChildren().add(AdvisorView.continuityText(
                    "Vytvořit takovýto balíček je vhodné co nejdříve. Později už nemusí být"
                    + " dostupné některé informace, které dnes ještě dostupné jsou: vydavatelé"
                    + " přestávají zveřejňovat revokační informace a certifikátům časových"
                    + " razítek končí platnost. Balíček pořízený dnes proto může být průkaznější"
                    + " než týž balíček vytvořený později."));
            addPerElement(body, elements, gates, deadline, false, false, open);
        } else {
            // Tahle věta stávala mimo blok, pod celou záložkou. Uvnitř dává čtenáři i to, co mu
            // dosud chybělo: proč to nejde u kterého prvku.
            body.getChildren().add(AdvisorView.continuityText(
                    "S tímto dokumentem se v tuto chvíli nedá udělat nic z toho, co aplikace"
                    + " nabízí"
                    + (anyOffer
                        ? ": úkon, který se pro dokument nabízí, nedosáhne na žádný z jeho prvků"
                        : "") + "."));
            addNoTsaNote(body, tsa, anyOffer);
            addPerElement(body, elements, gates, deadline, false, false, open);
            body.getChildren().add(AdvisorView.continuityText(
                    "Dokument samotný tím nepozbývá ceny: co o něm dnes platí, je zaznamenáno"
                    + " v protokolu, který lze z aplikace vytisknout nebo uložit."));
        }
        if (!reached.isEmpty() && !actNow) addCalendarReminder(body, gates, document, deadline);
        return AdvisorView.collapsibleBlock(
                todoTitle(gates, !reached.isEmpty(), deadline, actNow), body,
                false, AdvisorView.BlockTint.RED);
    }

    /**
     * Co se nabízí pro dokument. Táž trojice vět jako u prvku ({@link #addOfferIntro}), jen bez
     * <b>premisy</b>: ta říká, proč zrovna tahle varianta a ne uchování, a to je věc jednotlivého
     * prvku — u dokumentu s pěti prvky by z pěti různých důvodů musel jeden vyhrát. Premisy proto
     * stojí ve výčtu po prvcích, každá u svého.
     */
    private static void addDocumentOfferIntro(VBox body, Gates gates, String deadlineSentence) {
        if (gates.preserve()) {
            body.getChildren().add(AdvisorView.continuityText(
                    "Pro tento dokument nabízí aplikace AnEP možnost plnohodnotného uchování:"
                    + " přidání dosud chybějících informací, potřebných pro pozdější ověření,"
                    + " a jejich zafixování dalším časovým razítkem." + deadlineSentence));
        } else if (gates.prolong()) {
            body.getChildren().add(AdvisorView.continuityText(
                    "Aplikace AnEP nabízí pro tento dokument jen možnost označovanou jako"
                    + " prodloužení: přidání chybějících informací, potřebných pro pozdější"
                    + " ověření platnosti, a jejich zafixování dalším časovým razítkem."
                    + deadlineSentence));
        } else {
            body.getChildren().add(AdvisorView.continuityText(
                    "Aplikace AnEP nabízí pro tento dokument jen možnost označovanou jako"
                    + " přerazítkování: přidání těch chybějících informací potřebných pro"
                    + " pozdější ověření platnosti, které má aplikace k dispozici, a jejich"
                    + " zafixování dalším časovým razítkem." + deadlineSentence));
        }
    }

    /**
     * Věta o lhůtě za celý dokument. Kde se lhůty prvků liší, řekne se to nahlas — jinak by
     * čtenář datum v nadpisu bral za lhůtu všech a data ve výčtu níž by vypadala jako rozpor.
     */
    private static String deadlineSentence(java.util.List<Element> reached, Gates gates,
                                           Instant deadline) {
        if (deadline == null) {
            Element first = reached.get(0);
            return " Do kdy je této možnosti třeba využít, se stanovit nedá: "
                    + noDeadlineReason(first.anep(), first.tolerant()) + ".";
        }
        if (elapsed(deadline)) {
            return " Lhůta k využití této možnosti uplynula " + TimeFormat.full(deadline) + ".";
        }
        boolean differs = reached.stream()
                .map(e -> actionDeadline(e.sig(), e.anep(), e.tolerant(), gates))
                .anyMatch(d -> d == null || !d.equals(deadline));
        return " Tuto možnost je třeba využít nejpozději do " + TimeFormat.full(deadline) + "."
                + (differs
                    ? " Je to nejbližší z lhůt jednotlivých prvků: po ní už úkon nedá všem"
                      + " prvkům, na které dosáhne, vše, co dát může. Lhůty jednotlivých prvků"
                      + " stojí níže."
                    : "");
    }

    /**
     * Věta k lhůtě, která už uplynula. O spěchu se jinak nemluví — pobídka „Nejlépe ihned“
     * je od 9. 9. 2026 pryč (viz {@link #todoBlock}); od toho je v bloku lhůta.
     */
    private static String documentPace(Instant deadline, boolean movesNothing) {
        if (deadline == null || !elapsed(deadline)) return "";
        return "Tím spíš je na místě jednat hned: každý další den se podklady spíš ztrácejí,"
                + " než přibývají."
                + (movesNothing
                    ? " Ne ale s dnes vybraným zdrojem časových razítek — ten by"
                      + " ověřitelnost neposunul (viz níže)."
                    : "");
    }

    /**
     * Odstavec o materiálu, který má aplikace po ruce. U dokumentu stačí, že platí o kterémkoli
     * z dosažených prvků: úkon vloží materiál pro všechny naráz.
     */
    private static void addDocumentMaterialNote(VBox body, java.util.List<Element> reached,
                                                Gates gates, boolean actNow) {
        if (reached.stream().anyMatch(e -> e.dev().customCrl())) {
            body.getChildren().add(AdvisorView.continuityText(
                    "Podstatné je, že materiál, na kterém dnešní závěr stojí, má aplikace po"
                    + " ruce: seznam CRL z vlastního úložiště se do uchované verze vloží, takže"
                    + " si jej dokument ponese s sebou. Co je dnes podmínkou, kterou splní jen"
                    + " tahle aplikace, se tím stane součástí souboru."));
        } else if (!actNow && !gates.preserve() && reached.stream()
                .anyMatch(e -> ContinuityHorizon.dependsOnOnlineRevocation(e.sig()))) {
            body.getChildren().add(AdvisorView.continuityText(
                    "Podstatné je, že revokační informace o podpisových certifikátech se dnes"
                    + " stáhnout podařilo — a úkon je do dokumentu vloží. Tím se z ověření,"
                    + " které dnes závisí na tom, že je vydavatel stále zveřejňuje, stane"
                    + " ověření z toho, co dokument nese sám. Právě to je důvod, proč s úkonem"
                    + " neotálet: až vydavatel zveřejňování ukončí, nebude co vkládat."));
        }
    }

    /**
     * Proč na prvek nedosáhne úkon, který slibuje ověřitelnost podle standardů, když obecnou
     * překážku nemá — tedy když ho přísné čtení odmítá, ale validátory s vlastní zásobou ho
     * ověřit mohou. Dvě příčiny, tytéž, které ho pouštějí do nabídky přerazítkování
     * ({@code AdvisorView.continuityObstacle}). {@code null}, když prvek obstál nebo příčina
     * není ani jedna z nich.
     */
    static String strictObstacle(Element el) {
        if ("TOTAL_PASSED".equals(el.verdict())) return null;
        SignatureSummary sig = el.sig();
        String koho = AdvisorView.gendered(sig, "tohoto podpisu", "této pečeti",
                "tohoto časového razítka");
        if (AdvisorView.tolerantReaderMaySucceed(sig)) {
            return "překážkou je, že k ověření " + koho + " jsou k dispozici jen takové"
                    + " revokační informace, které standardy zapovídají použít, a proto "
                    + AdvisorView.gendered(sig, "ho", "ji", "ho") + " AnEP dnes ověřit nedokáže";
        }
        if (AdvisorView.tolerantReadingStillWorks(sig)) {
            return "překážkou je, že časové razítko, o které se "
                    + AdvisorView.gendered(sig, "tento podpis", "tato pečeť", "toto razítko")
                    + " opírá, podle standardů neobstálo, a proto "
                    + AdvisorView.gendered(sig, "ho", "ji", "ho") + " AnEP dnes ověřit nedokáže";
        }
        return null;
    }

    /**
     * Jak se úkon projeví na jednotlivých prvcích — pár vět na každý, v pořadí, ve kterém stojí
     * ve výčtu nad blokem.
     *
     * <p>Bez tohohle výčtu by se přesunem obou bloků dolů ztratilo to jediné, co je na prvcích
     * opravdu různé: komu úkon co přinese a do kdy. Souhrn souběhu umí jen vyjmenovat čísla
     * („prospěje prvkům č. 1 a 3“) a důvod odkázat jinam; tady stojí u toho prvku, o kterém je.
     *
     * @param anyReached zda úkon dosáhne aspoň na jeden prvek. Věta u nedosaženého prvku se tím
     *                   mění: „ostatním prospěje“ se nad dokumentem, kde nepomůže nikomu, nesmí
     *                   napsat.
     * @param ownAnchor  má některý z dosažených prvků kotvu z vlastního úložiště — pro
     *                   {@link #addReachExtras}, který se sází za posledním z nich
     */
    private static void addPerElement(VBox body, java.util.List<Element> elements, Gates gates,
                                      Instant docDeadline, boolean anyReached,
                                      boolean ownAnchor,
                                      java.util.function.Consumer<ContinuityView.Outlook> open) {
        int last = -1;
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i).actionReaches()) last = i;
        }
        for (int i = 0; i < elements.size(); i++) {
            Element el = elements.get(i);
            SignatureSummary sig = el.sig();
            body.getChildren().add(elementLabel("Prvek č. " + (i + 1) + " — "
                    + AdvisorView.gendered(sig, "podpis", "pečeť", "časové razítko")));
            if (!el.actionReaches()) {
                // Prvek bez vlastní překážky, na který přesto úkon nedosáhne. Stávalo tu
                // „platnost … se ověřit nepodařila“ — domněnka, že jinou příčinu než neúspěšné
                // ověření mít nemůže. U samostatného časového razítka je to nepravda: úkon se
                // rozhoduje podle podpisů v dokumentu (viz gateSigs v AdvisorView), takže razítko
                // zůstane nedosažené, i když je platné a nic mu nebrání. Potkáno na
                // „podvod s revokaci\priklad6_13.pdf“, kde je prvek č. 1 platné dokumentové
                // razítko a záložka o něm tvrdila nejdřív, že mu chybí revokační informace,
                // a po opravě brány, že se jeho platnost nepodařilo ověřit. Obojí vedle.
                // Prvek bez obecné překážky, kterého se přesto uchování netýká, protože ho přísné
                // čtení odmítá. Do 4. 10. 2026 o něm stálo, že „na jeho straně žádná překážka
                // není" — nález uživatele nad dokumentem se dvěma podpisy PAdES, z nichž druhý
                // měl k dispozici jen revokační údaje, které normy nedovolují použít. Překážka
                // tu je, jen ne pro validátory s vlastní zásobou; uchování ale slibuje
                // ověřitelnost podle standardů.
                String strictWhy = el.obstacle() == null ? strictObstacle(el) : null;
                if (el.obstacle() == null && strictWhy == null) {
                    // Rody se tu schválně obcházejí druhým pádem: „podpis sám“, „pečeť sama“
                    // a „razítko samo“ by se musely shodovat a věta se skládá ze tří kusů.
                    String koho = AdvisorView.gendered(sig, "tohoto podpisu", "této pečeti",
                            "tohoto časového razítka");
                    body.getChildren().add(AdvisorView.continuityText(anyReached
                            ? "Úkon na " + AdvisorView.gendered(sig, "tento podpis", "tuto pečeť",
                                    "toto časové razítko") + " nedosáhne, ačkoli na straně " + koho
                              + " žádná překážka není: rozhoduje se za dokument jako celek."
                              + " Nové časové razítko dokument pokryje celý."
                            : "Na straně " + koho + " žádná překážka není; úkon se ale rozhoduje"
                              + " za dokument jako celek a ten se provést nedá."));
                    continue;
                }
                String why = el.obstacle() != null ? el.obstacle() : strictWhy;
                body.getChildren().add(AdvisorView.continuityText(anyReached
                        ? "Úkon na " + AdvisorView.gendered(sig, "tento podpis", "tuto pečeť",
                                "toto časové razítko") + " nedosáhne: " + why + ". Nové časové"
                          + " razítko dokument pokryje celý a ostatním prvkům v něm prospěje, ale"
                          + " co je sporné na tomto, zůstane sporné i potom."
                        : "U " + AdvisorView.gendered(sig, "tohoto podpisu", "této pečeti",
                                "tohoto časového razítka") + " už není možné, nebo účelné,"
                          + " zachovávat ověřitelnost: " + why + "."));
                continue;
            }
            // Premisa (proč zrovna tahle varianta) je vlastnost prvku, ne dokumentu — u pěti
            // prvků má pět různých odpovědí. Právě proto ji dokumentový úvod nemá a stojí tady.
            String premise = gates.prolong() ? prolongPremise(sig)
                    : gates.restamp() ? restampPremise(sig) : "";
            if (!premise.isBlank()) body.getChildren().add(AdvisorView.continuityText(premise));
            addReachResult(body, sig, gates, open);
            // Dva odstavce o variantě jednou, za posledním prvkem, na který úkon dosáhne:
            // navazují na jeho větu o výsledku a u každého prvku by se opakovaly.
            if (i == last) {
                boolean gapAlready = elements.stream()
                        .filter(Element::actionReaches)
                        .anyMatch(e -> chainAlreadyBroken(e.sig()));
                addReachExtras(body, gates, ownAnchor, gapAlready);
            }
            // Datum, které odstavec nad výčtem schválně nenese: při dvou takových prvcích
            // by tam musela vyhrát jedna ze dvou hodnot. Tvrzení „lhůtu stanovit nelze“ se
            // sem neopisuje — stojí nahoře a tady by bylo podruhé.
            if (!actNow(sig)
                    && weakKeyNow(sig, actionDeadline(el.sig(), el.anep(), el.tolerant(), gates))) {
                SignatureSummary.TimestampKey k = sig.getNewestTimestampKey();
                body.getChildren().add(AdvisorView.continuityNote(
                        "Nejnovější časové razítko tohoto prvku je podepsané algoritmem "
                        + k.description() + ", který knihovna DSS, nad kterou AnEP ověřuje, "
                        + (elapsed(k.reliableUntil()) ? "už od " : "od ")
                        + TimeFormat.day(k.reliableUntil()) + " nepovažuje za spolehlivý."));
                continue;
            }
            if (actNow(sig)) {
                body.getChildren().add(AdvisorView.continuityNote(
                        "Doba, po kterou se u tohoto prvku dalo spoléhat na dostupnost"
                        + " rozhodujícího revokačního údaje, uplynula "
                        + TimeFormat.full(sig.getAugmentationDeadline().when()) + "."));
                continue;
            }
            Instant own = actionDeadline(el.sig(), el.anep(), el.tolerant(), gates);
            // Jen tam, kde se od lhůty v nadpisu liší. Zopakovat totéž datum u každého prvku
            // znamená zaplnit blok čísly, mezi kterými se to jedno odlišné ztratí.
            if (own != null && !own.equals(docDeadline)) {
                body.getChildren().add(AdvisorView.continuityText(elapsed(own)
                        ? "Lhůta k využití této možnosti u tohoto prvku uplynula "
                          + TimeFormat.full(own) + "."
                        : "U tohoto prvku je možnost využitelná do " + TimeFormat.full(own)
                          + "."));
            }
        }
    }

    /** Jméno prvku uvnitř výčtu. Tučné, ale bez vlastního záhlaví — je to řádek, ne další blok. */
    private static Label elementLabel(String text) {
        Label l = AdvisorView.continuityText(text);
        l.setStyle("-fx-font-size: 12px; -fx-text-fill: #111111; -fx-font-weight: bold;"
                + " -fx-padding: 6 0 0 0;");
        return l;
    }

    // ── Společné ─────────────────────────────────────────────────────────────────────────────

    private static VBox body() {
        VBox body = new VBox(8);
        body.setPadding(new Insets(4, 12, 10, 12));
        return body;
    }

    private static AdvisorView.BlockTint tint(Kind kind) {
        return switch (kind) {
            case UNTIL, OPEN -> AdvisorView.BlockTint.GREEN;
            case LOST -> AdvisorView.BlockTint.BROWN;
            case INVALID -> AdvisorView.BlockTint.RED;
        };
    }

    /**
     * „Do“, nebo „Nejméně do“?
     *
     * <p>Datum odvozené z certifikátu <b>kvalifikovaného</b> razítka není konec, nýbrž spodní mez:
     * za ním může razítko platit dál, dokud si služba drží kvalifikovaný status. Prezentovat je
     * jako definitivní by slibovalo méně, než co dokument unese — a čtenář by podle toho zbytečně
     * spěchal. Ostatní meze (podpisový certifikát, dostupnost revokace, certifikát nekvalifikované
     * autority) definitivní jsou, tam se nic nepřidává.
     */
    /**
     * Revokační ústupky pojmenované tak, jak se vejdou doprostřed věty — tedy podle toho, <b>co
     * je na tom údaji</b>, ne názvem zaškrtávátka v nastavení.
     */
    /**
     * Čím ten ústupek je — pojmenovaný podle vady, kterou v dokumentu opravdu prominá.
     *
     * <p>Zaškrtávátko {@code NEWER_OCSP} se jmenuje podle post-expiračního údaje, ale v politice
     * DSS sráží na WARN i {@code AcceptableRevocationDataFound}, tedy bránu „nezbyl žádný
     * použitelný údaj“. Ta chytne i opačnou mez: údaj vydaný dřív, než certifikát vůbec vznikl.
     * Nad takovým dokumentem tvrdila věta o údaji, že je „po expiraci“ certifikátu, který platí
     * ještě roky — potkáno 19. 9. 2026 na jednom reportu, kde CRL vznikla pět let před
     * certifikátem. Rozhoduje proto {@code postExpiryRevocationPresent()}, ne název volby.
     */
    private static String revocationRuleNames(java.util.List<String> names,
                                              SignatureSummary sig) {
        boolean postExpiry = sig.getValidationCourse() != null
                && sig.getValidationCourse().postExpiryRevocationPresent();
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String n : names) {
            parts.add(ReportMapper.Relaxation.NEWER_OCSP.settingName().equals(n)
                    ? (postExpiry ? "vydané po expiraci podpisového certifikátu"
                                  : "vydané mimo dobu platnosti certifikátu, o němž vypovídají")
                    : "od vydavatele, jemuž mezitím vypršel vlastní certifikát");
        }
        return String.join(", nebo ", parts);
    }


    private static boolean lowerBoundOnly(Horizon h) {
        return h.limit() == Limit.QUALIFIED_TIMESTAMP_CERT;
    }

    private static String upperCaseBound(Horizon h) {
        return lowerBoundOnly(h) ? "Nejméně do " : "Do ";
    }

    private static String lowerBound(Horizon h) {
        return lowerBoundOnly(h) ? "nejméně do " : "do ";
    }

    private static boolean elapsed(Instant when) {
        return when != null && !when.isAfter(Instant.now());
    }
}
