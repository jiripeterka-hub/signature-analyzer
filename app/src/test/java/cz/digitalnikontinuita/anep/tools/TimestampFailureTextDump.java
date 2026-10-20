package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.model.SignatureSummary;

/**
 * Vypíše všechny texty, kterými důvod 21 pojmenovává příčinu selhání razítka — aby se daly
 * přečíst vcelku a jako souvislá řeč, ne po kouscích ve zdrojáku.
 *
 * <pre>java -cp … cz.digitalnikontinuita.anep.tools.TimestampFailureTextDump</pre>
 */
public final class TimestampFailureTextDump {

    public static void main(String[] args) {
        cz.digitalnikontinuita.anep.ui.TimestampFailureTextPeek.dump(
                System.out, SignatureSummary.TimestampFailure.values());
    }
}
