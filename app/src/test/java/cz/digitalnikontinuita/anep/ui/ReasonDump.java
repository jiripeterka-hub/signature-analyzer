package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.ReportMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Dumps the reason catalogue and everything that links the reasons together, straight from the
 * code that decides them: the texts from ResultView.REASONS, the suppression table from
 * ReportMapper, and the situation-to-reason mapping measured by calling detectReasons() rather
 * than read off a comment that could have drifted.
 */
public final class ReasonDump {

    private static final String NL = System.lineSeparator();

    public static void main(String[] a) throws Exception {
        Method detect = ReportMapper.class.getDeclaredMethod(
                "detectReasons", List.class, AppSettings.class);
        detect.setAccessible(true);

        Field fSup = ReportMapper.class.getDeclaredField("REASON_SUPPRESSIONS");
        fSup.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, List<Integer>> suppressions = (Map<Integer, List<Integer>>) fSup.get(null);

        AppSettings strict = new AppSettings();
        AppSettings lenientTs = new AppSettings();
        lenientTs.setTimestampStandardsMode(false);

        int maxSituation = 50;

        // Která situace sama o sobě vyvolá který důvod — a jestli k tomu potřebuje jiný režim.
        Map<Integer, List<String>> triggers = new TreeMap<>();
        for (int s = 1; s <= maxSituation; s++) {
            for (int r : call(detect, List.of(s), strict)) {
                triggers.computeIfAbsent(r, k -> new ArrayList<>()).add("S" + s);
            }
            for (int r : call(detect, List.of(s), lenientTs)) {
                List<String> t = triggers.computeIfAbsent(r, k -> new ArrayList<>());
                if (!t.contains("S" + s)) t.add("S" + s + "|jen mimo režim standardů");
            }
        }

        // Které situace důvod naopak umlčí, když se přidají k té, co ho vyvolala. Měří se, ne
        // odvozuje: podchytí to jak tabulku potlačení, tak podmínky uvnitř detectReasons(),
        // které v žádné tabulce nejsou.
        Map<Integer, List<String>> blockedBy = new TreeMap<>();
        for (Map.Entry<Integer, List<String>> e : triggers.entrySet()) {
            int reason = e.getKey();
            int base = baseSituation(e.getValue());
            if (base < 0) continue;
            for (int s = 1; s <= maxSituation; s++) {
                if (s == base) continue;
                if (!call(detect, List.of(base, s), strict).contains(reason)) {
                    blockedBy.computeIfAbsent(reason, k -> new ArrayList<>()).add("S" + s);
                }
            }
        }

        StringBuilder out = new StringBuilder();
        for (Map.Entry<Integer, String> e : new TreeMap<>(ResultView.SITUATION_LABELS).entrySet()) {
            out.append("@SIT\t").append(e.getKey()).append('\t')
               .append(oneLine(e.getValue())).append(NL);
        }
        for (int r = 1; r <= 40; r++) {
            ResultView.ReasonEntry re = ResultView.REASONS.get(r);
            if (re == null && !triggers.containsKey(r)) continue;
            final int rr = r;
            List<Integer> suppressedBy = new ArrayList<>();
            suppressions.forEach((k, v) -> { if (v.contains(rr)) suppressedBy.add(k); });
            Collections.sort(suppressedBy);

            out.append("### DUVOD ").append(r).append(NL);
            out.append("NAZEV\t")
               .append(ResultView.REASON_WORKING_LABELS.getOrDefault(r, "")).append(NL);
            out.append("TRIGGER\t").append(String.join(", ",
                    triggers.getOrDefault(r, List.of("(nezjištěno)")))).append(NL);
            out.append("POTLACI\t").append(join(suppressions.getOrDefault(r, List.of()))).append(NL);
            out.append("POTLACEN\t").append(join(suppressedBy)).append(NL);
            out.append("BLOKUJI\t").append(String.join(", ",
                    blockedBy.getOrDefault(r, List.of()))).append(NL);
            out.append("PROBLEM\t").append(re == null ? "" : oneLine(re.problem())).append(NL);
            out.append("DETAIL\t").append(re == null ? "" : oneLine(re.detail())).append(NL);
            out.append("RESENI\t").append(re == null ? "" : oneLine(re.solution())).append(NL);
            // Rada pro zkušenější uživatele stojí v Analýze vlastním řádkem pod spodním z těch
            // důvodů, kterých se týká. Na listu musí být u každého z nich, jinak by u důvodů
            // 10 až 12 nezbylo žádné doporučení. Od 15. 9. 2026 je po důvodech v souboru,
            // takže se bere odtud a smí být u každého jiná.
            out.append("RADA\t").append(oneLine(
                    ReasonTexts.get(String.valueOf(r), "zkusenejsi"))).append(NL);
        }
        System.out.print(out);
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static int baseSituation(List<String> triggers) {
        for (String t : triggers) {
            String digits = t.split("\\|")[0].replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) return Integer.parseInt(digits);
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> call(Method m, List<Integer> situations, AppSettings s)
            throws Exception {
        return (List<Integer>) m.invoke(null, situations, s);
    }

    private static String join(List<Integer> xs) {
        if (xs.isEmpty()) return "";
        List<Integer> sorted = new ArrayList<>(xs);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (int x : sorted) { if (sb.length() > 0) sb.append(", "); sb.append("R").append(x); }
        return sb.toString();
    }
}
