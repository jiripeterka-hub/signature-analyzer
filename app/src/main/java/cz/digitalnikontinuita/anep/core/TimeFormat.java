package cz.digitalnikontinuita.anep.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * Jediný způsob, jak aplikace píše okamžik: {@code d. M. yyyy H:mm:ss UTC (H:mm:ss SEČ/SELČ)}.
 *
 * <p>Do 25. 8. 2026 měla aplikace formátů osm — někde samotné datum, jinde datum s časem bez
 * pásma, jinde tuhle plnou podobu. Týž okamžik se pak na dvou kartách četl jinak a čtenář nemohl
 * poznat, jestli jde o dva různé časy, nebo o jeden zapsaný dvakrát. Zvlášť u revokačních údajů,
 * kde o závěru rozhoduje pořadí v rámci jediného dne, to nebyla kosmetika.
 *
 * <p>Obojí pásmo proto, že materiál elektronických podpisů nese časy v UTC, kdežto čtenář žije
 * v SEČ nebo SELČ; bez obojího si tu dvojici nespáruje. Datum se v závorce zopakuje jen tehdy,
 * když se posunem pásma změní den — jinak by se opakovalo v každém řádku pro nic.
 *
 * <p>Bydlí v {@code core}, ne v {@code ui}: čte odsud i {@code ReportMapper} a
 * {@code ContainerInspector}, a jádro do balíčku oken sahat nesmí. Okna mají
 * {@code ui.TimeUtil}, který sem jen deleguje.
 *
 * <p><b>Netýká se strojových zápisů.</b> Časy v manifestech forenzního balíčku zůstávají v ISO
 * 8601 — ty nečte člověk, nýbrž program, který balíček zpracuje.
 */
public final class TimeFormat {

    private TimeFormat() { }

    private static final ZoneId PRAGUE = ZoneId.of("Europe/Prague");
    private static final ZoneOffset CEST = ZoneOffset.ofHours(2);

    private static final DateTimeFormatter UTC_FMT =
            DateTimeFormatter.ofPattern("d. M. yyyy H:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CZ_FULL =
            DateTimeFormatter.ofPattern("d. M. yyyy H:mm:ss").withZone(PRAGUE);
    private static final DateTimeFormatter CZ_TIME =
            DateTimeFormatter.ofPattern("H:mm:ss").withZone(PRAGUE);

    /** Pomlčka, ne prázdno: chybějící údaj má být na řádku vidět. */
    public static final String MISSING = "—";

    public static String full(Instant instant) {
        if (instant == null) return MISSING;
        String abbr = CEST.equals(PRAGUE.getRules().getOffset(instant)) ? "SELČ" : "SEČ";
        LocalDate utcDay = LocalDate.ofInstant(instant, ZoneOffset.UTC);
        LocalDate przDay = LocalDate.ofInstant(instant, PRAGUE);
        String cz = utcDay.equals(przDay) ? CZ_TIME.format(instant) : CZ_FULL.format(instant);
        return UTC_FMT.format(instant) + " (" + cz + " " + abbr + ")";
    }

    /**
     * Jen den, v pražském čase — „1. 1. 2027“. Pro data, která okamžikem nejsou, typicky
     * konec spolehlivosti algoritmu podle ETSI TS 119 312: „1. 1. 2027 0:00:00 UTC (1:00:00 SEČ)“
     * by tvrdilo přesnost, kterou to datum nemá.
     */
    public static String day(Instant instant) {
        return instant == null ? MISSING : DAY.format(instant);
    }

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d. M. yyyy").withZone(PRAGUE);

    public static String full(Date date) {
        return date == null ? MISSING : full(date.toInstant());
    }

    public static String full(long epochMs) {
        return full(Instant.ofEpochMilli(epochMs));
    }
}
