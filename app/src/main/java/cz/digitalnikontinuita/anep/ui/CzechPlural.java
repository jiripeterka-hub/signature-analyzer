package cz.digitalnikontinuita.anep.ui;

/**
 * Czech number agreement for counted nouns.
 *
 * <p>Czech picks one of three forms by the count: 1 takes the nominative singular, 2–4 the
 * nominative plural, 5 and above the genitive plural. Getting it wrong ("3 objektů") reads as
 * carelessness in a tool whose whole job is to be precise about what it found, so the rule is
 * written down once here instead of being reinvented at each call site.
 */
public final class CzechPlural {

    private CzechPlural() { }

    /**
     * @param one  form for 1 — "objekt"
     * @param few  form for 2–4 — "objekty"
     * @param many form for 0 and 5+ — "objektů"
     * @return the count followed by the matching form, e.g. {@code "3 objekty"}
     */
    public static String count(int n, String one, String few, String many) {
        return n + " " + form(n, one, few, many);
    }

    /** The matching noun form on its own, for sentences that place the number elsewhere. */
    public static String form(int n, String one, String few, String many) {
        // Negative counts have no sensible reading; treat them like the genitive plural rather
        // than letting a stray minus produce "-1 objekt".
        if (n == 1) return one;
        if (n >= 2 && n <= 4) return few;
        return many;
    }

    /** "objekt / objekty / objektů" — the form used throughout the evidence-record screens. */
    public static String objects(int n) {
        return count(n, "objekt", "objekty", "objektů");
    }

    /** "soubor / soubory / souborů". */
    public static String files(int n) {
        return count(n, "soubor", "soubory", "souborů");
    }
}
