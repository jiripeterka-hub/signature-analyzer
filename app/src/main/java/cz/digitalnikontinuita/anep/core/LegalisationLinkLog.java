package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Check;
import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Link;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Co uživatel o odkazech z eLegalizace za tohoto běhu zjistil.
 *
 * <p>Odkaz v příloze {@code legalisation-metadata.xml} míří na dokument, který v souboru není a
 * nikdy nebude — ověřit ho jde jedině tak, že ten druhý dokument někdo předloží. Děje se to
 * v samostatném okně ({@code LegalisationLinkWindow}), a to <b>kdykoli</b>: klidně dřív, než
 * uživatel dá pokyn k ověření předloženého dokumentu. Výsledek by se tím ztratil, protože karta
 * v Rozšířeném přehledu se staví z výsledku ověření a o žádném okně neví.
 *
 * <p>Tenhle záznam je tedy most mezi obojím: okno sem po každé kontrole zapíše, co porovnávalo
 * a jak to dopadlo, a blok „Odkazované dokumenty z eLegalizace“ si to odsud přečte — bez ohledu
 * na to, co bylo dřív.
 *
 * <p><b>Řetěz se neukládá, počítá se.</b> Každá kontrola je zapsaná pod dokumentem, ve kterém
 * odkaz stál; předložený dokument může nést odkaz vlastní a jeho kontrola se zapíše pod ním.
 * {@link #chain(File)} pak jde od zadaného souboru přes předložené dokumenty tak daleko, kam
 * uživatel došel. Ukládat řetěz jako celek by znamenalo mít jednu věc na dvou místech a čekat,
 * až se rozejdou.
 *
 * <p>Velikost a čas změny se berou <b>v okamžiku kontroly</b>, ne až při vykreslení: soubor se
 * mezitím mohl změnit a karta má říkat, co se porovnávalo, ne co na disku leží teď.
 */
public final class LegalisationLinkLog {

    private LegalisationLinkLog() { }

    /**
     * Jedna provedená kontrola.
     *
     * @param source            dokument, ve kterém odkaz stojí
     * @param link              odkaz z jeho přílohy
     * @param candidate         soubor předložený jako ten odkazovaný
     * @param candidateSize     jeho velikost v bajtech v době kontroly
     * @param candidateModified čas jeho poslední změny v době kontroly; {@code null}, nešel-li zjistit
     * @param check             jak porovnání otisků dopadlo
     * @param candidateLink     odkaz nalezený v předloženém dokumentu, nebo {@code null}
     */
    public record Step(File source, Link link, File candidate, long candidateSize,
                       Instant candidateModified, Check check, Link candidateLink) { }

    /** Klíč je kanonická cesta dokumentu, ve kterém odkaz stojí; hodnota poslední kontrola. */
    private static final Map<String, Step> STEPS = new LinkedHashMap<>();

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    /**
     * Zapíše výsledek kontroly a dá vědět tomu, kdo poslouchá.
     *
     * <p>Opakovaná kontrola téhož dokumentu předchozí zápis přepíše: platí to, co uživatel udělal
     * naposled, a dvě odpovědi na tutéž otázku by si na kartě odporovaly.
     */
    public static synchronized void record(File source, Link link, File candidate,
                                           Check check, Link candidateLink) {
        if (source == null || candidate == null || check == null) return;
        long size = candidate.isFile() ? candidate.length() : -1;
        Instant modified = candidate.isFile() && candidate.lastModified() > 0
                ? Instant.ofEpochMilli(candidate.lastModified()) : null;
        STEPS.put(key(source),
                new Step(source, link, candidate, size, modified, check, candidateLink));
        for (Runnable r : LISTENERS) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // Posluchač je kus rozhraní; jeho pád nesmí shodit kontrolu, která právě doběhla.
            }
        }
    }

    /**
     * Kontroly od zadaného dokumentu dál — první je ta jeho, další patří dokumentům, které
     * uživatel postupně předložil. Prázdný seznam znamená, že se zatím nekontrolovalo nic.
     *
     * <p>Řetěz se hlídá proti kruhu: dokument, který už v něm je, se podruhé nenavštíví. Dva
     * soubory, které odkazují na sebe navzájem, by jinak procházku nezastavily.
     */
    public static synchronized List<Step> chain(File source) {
        List<Step> out = new ArrayList<>();
        if (source == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        File at = source;
        while (at != null && seen.add(key(at))) {
            Step step = STEPS.get(key(at));
            if (step == null) break;
            out.add(step);
            at = step.candidate();
        }
        return out;
    }

    /** Posluchač, kterému se dá vědět po každém zápisu. Volá se ve vlákně, které zapisovalo. */
    public static void onChange(Runnable listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /** Pro sondy a testy: zahodí vše, co záznam drží. */
    public static synchronized void clear() {
        STEPS.clear();
    }

    private static String key(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Exception e) {
            return f.getAbsolutePath();
        }
    }
}
