package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampFailure;

import java.io.PrintStream;
import java.time.Instant;
import java.util.List;

/**
 * Průchod k textům důvodu 21 zvenčí balíčku {@code ui} — ty metody jsou balíčkově viditelné,
 * aby je viděly testy, a sonda v {@code tools} by se k nim jinak nedostala.
 */
public final class TimestampFailureTextPeek {

    private TimestampFailureTextPeek() { }

    public static void dump(PrintStream out, TimestampFailure... causes) {
        for (TimestampFailure cause : causes) {
            for (boolean sha1 : new boolean[] {false, true}) {
                SignatureSummary sig = SigFixture.sig()
                        .indication("INDETERMINATE")
                        .reasons(sha1 ? new Integer[] {15, 21} : new Integer[] {21})
                        .timestamps(List.of(new TimestampEntry(
                                Instant.parse("2012-01-17T22:09:02Z"), false,
                                "I.CA - Time Stamping Authority, TSS/TSU 3, 12/2011",
                                Instant.parse("2016-12-21T00:00:00Z"),
                                "sig", false, true, cause)))
                        .build();

                String problem = AdvisorView.timestampFailureProblem(sig);
                String detail = AdvisorView.timestampFailureDetail(sig);
                String solution = AdvisorView.timestampFailureSolution(sig);
                if (problem == null && detail == null && solution == null) {
                    if (!sha1) out.println("\n##### " + cause + " — bez pojmenování, platí obecné znění");
                    continue;
                }
                out.println("\n##### " + cause + (sha1 ? "  (vedle je i důvod 15)" : ""));
                out.println("  V ČEM JE PROBLÉM: " + problem);
                out.println("  PODROBNĚJI …:     " + (detail == null ? "—" : detail));
                out.println("  CO MŮŽE POMOCI:   " + (solution == null ? "—" : solution));
                if (cause == TimestampFailure.OTHER) break;
            }
        }
    }
}
