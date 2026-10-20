package cz.digitalnikontinuita.anep.model;

import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Dokdy sahá řada časových razítek a kde v ní je mezera.
 *
 * <h2>Proč to není „poslední razítko“</h2>
 *
 * <p>Do 18. 9. 2026 na tuhle otázku odpovídalo pět míst v aplikaci a všechna stejně špatně:
 * brala <b>poslední</b> razítko v řadě a jeho {@code notAfter} vydávala za konec krytí, mezeru
 * pak hledala porovnáním každé dvojice sousedů. Nad „podpis s razitkem-extended-pades-baseline
 * -lta.pdf“ z toho vyšlo, že řada končí 11. 11. 2025 — podle dokumentového razítka od BalTstamp
 * — ačkoli podpisové razítko pod ním stojí na certifikátu platném do 31. 12. 2028.
 *
 * <p><b>Změřeno na Úschovně Czech POINT 18. 9. 2026:</b> týž dokument prošel, podpis uznán za
 * platný a expirované dokumentové razítko odbyto jako „Neplatné samostatné časové razítko“.
 * Striktní validátor tedy nepotřebuje, aby platil certifikát <i>posledního</i> razítka — stačí
 * mu <b>kterékoli</b> razítko, které kryje podpis a jehož certifikát dosud platí. Mladší razítko
 * s kratším certifikátem starší řadu nezkracuje; nepřidá nic, ale ani nebere.
 *
 * <h2>Pravidlo</h2>
 *
 * <p>Každé razítko kryje od svého vzniku do konce platnosti certifikátu své autority, tedy
 * interval {@code [time, notAfter)}. Řada je souvislá, dokud tyhle intervaly na sebe navazují,
 * a sahá tam, kam dosáhne <b>nejzazší</b> z nich — ne poslední v pořadí. Je to obyčejné
 * slévání intervalů a od dosavadního porovnávání sousedů se liší přesně ve dvou případech:
 *
 * <ul>
 *   <li><b>konec:</b> mladší razítko s kratším certifikátem řadu nezkrátí;</li>
 *   <li><b>mezera:</b> razítko přidané až po expiraci certifikátu toho <i>bezprostředně</i>
 *       předchozího mezeru netvoří, pokud v té chvíli ještě platil certifikát některého
 *       staršího — právě o něj se důkaz existence opře.</li>
 * </ul>
 *
 * <p>Neznámý konec platnosti se počítá jako konec krytí, ne jako nekonečno: bez něj nelze
 * ukázat, že další razítko vzniklo včas.
 *
 * <p>Razítka se očekávají <b>seřazená vzestupně</b> podle {@code time()}, jak je vydává
 * {@code ReportMapper.allTimestamps}.
 */
public final class TimestampChain {

    private TimestampChain() { }

    /**
     * Mezera v řadě: od konce krytí dosavadní části do vzniku razítka, které na ni nenavázalo.
     * {@code from} je {@code null}, když konec krytí není znám.
     */
    public record Gap(Instant from, Instant to) { }

    /**
     * Dokdy sahá souvislá řada <b>od prvního razítka</b> — tedy dokdy je krytý prvek, který pod
     * ní leží. Za mezerou už se nepokračuje: co vzniklo po ní, se k podpisu neváže.
     *
     * <p>{@code null} znamená „není čím měřit“ — prázdný seznam, nebo první razítko bez známého
     * konce platnosti.
     */
    public static Instant reachFromStart(List<TimestampEntry> sorted) {
        Instant reach = null;
        boolean started = false;
        for (TimestampEntry ts : sorted) {
            if (!started) {
                started = true;
                reach = ts.notAfter();
                continue;
            }
            if (!connects(ts, reach)) return reach;
            if (ts.notAfter() != null && ts.notAfter().isAfter(reach)) reach = ts.notAfter();
        }
        return reach;
    }

    /**
     * Dokdy sahá <b>poslední</b> souvislá část řady. Po mezeře začíná nová část a tohle je konec
     * té poslední z nich; bez mezery je to totéž co {@link #reachFromStart}.
     *
     * <p>Je to údaj pro výpis řady, ne pro závěr o prvku: čtenář, který má nad sebou vypsanou
     * mezeru, se ptá, kam sahá to, co za ní přibylo.
     */
    public static Instant reachOfLastRun(List<TimestampEntry> sorted) {
        Instant reach = null;
        boolean started = false;
        for (TimestampEntry ts : sorted) {
            if (!started) {
                started = true;
                reach = ts.notAfter();
                continue;
            }
            if (!connects(ts, reach)) reach = ts.notAfter();
            else if (ts.notAfter() != null && ts.notAfter().isAfter(reach)) reach = ts.notAfter();
        }
        return reach;
    }

    /** Mezery v řadě, v pořadí, v jakém nastaly. */
    public static List<Gap> gaps(List<TimestampEntry> sorted) {
        List<Gap> gaps = new ArrayList<>();
        Instant reach = null;
        boolean started = false;
        for (TimestampEntry ts : sorted) {
            if (!started) {
                started = true;
                reach = ts.notAfter();
                continue;
            }
            if (!connects(ts, reach)) {
                gaps.add(new Gap(reach, ts.time()));
                reach = ts.notAfter();
            } else if (ts.notAfter() != null && ts.notAfter().isAfter(reach)) {
                reach = ts.notAfter();
            }
        }
        return List.copyOf(gaps);
    }

    /** Řada bez jediné mezery. */
    public static boolean unbroken(List<TimestampEntry> sorted) {
        return gaps(sorted).isEmpty();
    }

    /** Navazuje tohle razítko na dosavadní krytí? */
    private static boolean connects(TimestampEntry ts, Instant reach) {
        return reach != null && ts.time() != null && ts.time().isBefore(reach);
    }
}
