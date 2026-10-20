package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.model.SignatureSummary;

/**
 * Který článek drží lhůtu — jedním řádkem, pro sondy.
 *
 * <p>{@code ContinuityHorizon} je package-private v {@code ui}, takže se na něj sondy z tohohle
 * balíku nedostanou. Reflexe je tu proto, že jde o měřicí přístroj: kdyby se kvůli němu měla
 * zveřejnit vnitřní třída, platila by cena za pohodlí sondy v návrhu aplikace.
 */
final class ContinuityHorizonProbe {

    private ContinuityHorizonProbe() { }

    static String describe(SignatureSummary sig) {
        try {
            Class<?> horizonClass = Class.forName(
                    "cz.digitalnikontinuita.anep.ui.ContinuityHorizon");
            Class<?> runClass = Class.forName(
                    "cz.digitalnikontinuita.anep.ui.ContinuityHorizon$Run");
            var runCtor = runClass.getDeclaredConstructors()[0];
            runCtor.setAccessible(true);
            Object run = runCtor.newInstance(true, java.util.List.of(), null);

            var anep = horizonClass.getDeclaredMethod("anep", SignatureSummary.class,
                    String.class, runClass);
            anep.setAccessible(true);
            Object h = anep.invoke(null, sig, sig.getIndication(), run);

            var tolerantMethod = horizonClass.getDeclaredMethod("tolerant",
                    SignatureSummary.class, String.class);
            tolerantMethod.setAccessible(true);
            Object t = tolerantMethod.invoke(null, sig, sig.getIndication());

            var broken = horizonClass.getDeclaredMethod("brokenChainStopsTolerant",
                    SignatureSummary.class);
            broken.setAccessible(true);

            return describe(h) + "  | tolerant: " + describe(t)
                    + "  | přerušená řada bere tolerantním: " + broken.invoke(null, sig);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "(nezjištěno: " + e + ")";
        }
    }

    private static String describe(Object h) {
        try {
            var kind = h.getClass().getMethod("kind");
            var when = h.getClass().getMethod("when");
            var limit = h.getClass().getMethod("limit");
            kind.setAccessible(true);
            when.setAccessible(true);
            limit.setAccessible(true);
            return kind.invoke(h) + " / " + limit.invoke(h) + " / " + when.invoke(h);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "(nezjištěno: " + e + ")";
        }
    }
}
