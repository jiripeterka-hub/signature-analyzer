package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prints, per signature, what the lenient run concluded, whether the strict run reached the same
 * conclusion, and which relaxations the application names as decisive once that evidence is taken
 * into account.
 *
 * <p>Mirrors {@code MainView.requiredRelaxations()} — including its second pass, the one that tells
 * tolerances standing in for each other apart from a genuine conjunction. Without that pass the
 * probe would measure something the application no longer measures, and a probe that answers a
 * different question than the code it checks is worse than none. A relaxation named beside a
 * signature the strict run also accepted is a false claim; this makes those visible.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 */
public final class DecisiveVerifiedProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: DecisiveVerifiedProbe <file>…");
            return;
        }
        CommonCertificateVerifier verifier =
                new TrustConfig(AppSettings.load()).buildLotlCertificateVerifier();
        DocumentLoader loader = new DocumentLoader();

        for (String arg : args) {
            Path file = Path.of(arg);
            System.out.println();
            System.out.println(file.getFileName());

            AppSettings lenient = AppSettings.load();
            lenient.setAcceptNewerOcsp(true);
            lenient.setAcceptExpiredRevocationSources(true);
            lenient.setIgnoreSha1Deprecated(true);
            lenient.setAcceptPdfNotEtsi(true);

            DSSDocument doc = loader.load(file.toFile());
            ValidationOutcome lenientOutcome =
                    new ValidatorFactory(lenient).validate(doc, null, verifier, null);
            SimpleReport lenientReport = lenientOutcome.reports().getSimpleReport();
            List<String> accepted = new java.util.ArrayList<>();
            for (String id : lenientReport.getSignatureIdList()) {
                if (lenientReport.getIndication(id) == Indication.TOTAL_PASSED) accepted.add(id);
            }

            Set<String> passingStrict = passing(List.of(), lenient, file, loader, verifier);
            Map<String, ReportMapper.RequiredRelaxations> required =
                    measure(accepted, passingStrict, lenient, file, loader, verifier);

            ValidationResult before = new ReportMapper().map(file.getFileName().toString(), null,
                    lenientOutcome, lenient);
            ValidationResult after = new ReportMapper().map(file.getFileName().toString(), null,
                    lenientOutcome, lenient, Map.of(), List.of(), false, Map.of(), required);

            for (int i = 0; i < after.getSignatures().size(); i++) {
                SignatureSummary b = before.getSignatures().get(i);
                SignatureSummary a = after.getSignatures().get(i);
                System.out.printf("   %-14s přísně: %-14s%n", a.getIndication(),
                        passingStrict.contains(a.getSignatureId()) ? "také platný" : "neprojde");
                System.out.println("      dosud tvrdilo: " + names(b)
                        + "   situace=" + b.getDetectedSituations());
                System.out.println("      nově tvrdí:    " + names(a)
                        + "   situace=" + a.getDetectedSituations());
            }
        }
    }

    /**
     * Leave-one-out, then — for signatures that survive it with nothing pinned down — each
     * tolerance on its own. Necessity first, sufficiency where necessity says nothing; a tolerance
     * that suffices alone is an alternative, and only when not even one does is the whole enabled
     * set named. Copy of the same reasoning in {@code MainView.requiredRelaxations()}.
     */
    private static Map<String, ReportMapper.RequiredRelaxations> measure(
            List<String> accepted, Set<String> withoutAny, AppSettings settings,
            Path file, DocumentLoader loader, CommonCertificateVerifier verifier) throws Exception {

        List<ReportMapper.Relaxation> enabled = ReportMapper.Relaxation.enabledIn(settings);
        Map<String, ReportMapper.RequiredRelaxations> required = new HashMap<>();
        if (enabled.isEmpty() || accepted.isEmpty()) return required;

        List<String> needsSomething = new java.util.ArrayList<>();
        for (String id : accepted) {
            if (withoutAny.contains(id)) {
                required.put(id, new ReportMapper.RequiredRelaxations(
                        EnumSet.noneOf(ReportMapper.Relaxation.class), false));
            } else {
                needsSomething.add(id);
            }
        }
        if (needsSomething.isEmpty()) return required;

        if (enabled.size() == 1) {
            for (String id : needsSomething) {
                required.put(id, new ReportMapper.RequiredRelaxations(
                        EnumSet.of(enabled.get(0)), false));
            }
            return required;
        }

        Map<String, Set<ReportMapper.Relaxation>> needed = new HashMap<>();
        for (String id : needsSomething) {
            needed.put(id, EnumSet.noneOf(ReportMapper.Relaxation.class));
        }
        for (ReportMapper.Relaxation withdrawn : enabled) {
            List<ReportMapper.Relaxation> rest = new java.util.ArrayList<>(enabled);
            rest.remove(withdrawn);
            Set<String> stillPassing = passing(rest, settings, file, loader, verifier);
            for (String id : needsSomething) {
                if (!stillPassing.contains(id)) needed.get(id).add(withdrawn);
            }
        }

        Set<String> interchangeable = new HashSet<>();
        Map<ReportMapper.Relaxation, Set<String>> alone = null;
        for (String id : needsSomething) {
            if (!needed.get(id).isEmpty()) continue;
            if (alone == null) {
                alone = new EnumMap<>(ReportMapper.Relaxation.class);
                for (ReportMapper.Relaxation r : enabled) {
                    alone.put(r, passing(List.of(r), settings, file, loader, verifier));
                }
            }
            Set<ReportMapper.Relaxation> alternatives =
                    EnumSet.noneOf(ReportMapper.Relaxation.class);
            for (ReportMapper.Relaxation r : enabled) {
                if (alone.get(r).contains(id)) alternatives.add(r);
            }
            if (!alternatives.isEmpty()) interchangeable.add(id);
            needed.put(id, alternatives.isEmpty() ? EnumSet.copyOf(enabled) : alternatives);
        }
        for (String id : needsSomething) {
            required.put(id, new ReportMapper.RequiredRelaxations(
                    needed.get(id), interchangeable.contains(id)));
        }
        return required;
    }

    /** Signatures accepted with exactly these tolerances switched on; settings put back after. */
    private static Set<String> passing(List<ReportMapper.Relaxation> active,
            AppSettings settings, Path file, DocumentLoader loader,
            CommonCertificateVerifier verifier) throws Exception {
        Map<ReportMapper.Relaxation, Boolean> saved =
                new EnumMap<>(ReportMapper.Relaxation.class);
        for (ReportMapper.Relaxation r : ReportMapper.Relaxation.values()) {
            saved.put(r, r.isEnabled(settings));
            r.setEnabled(settings, active.contains(r));
        }
        try {
            SimpleReport report = new ValidatorFactory(settings)
                    .validate(loader.load(file.toFile()), null, verifier, null)
                    .reports().getSimpleReport();
            Set<String> out = new HashSet<>();
            for (String id : report.getSignatureIdList()) {
                if (report.getIndication(id) == Indication.TOTAL_PASSED) out.add(id);
            }
            return out;
        } finally {
            saved.forEach((r, was) -> r.setEnabled(settings, was));
        }
    }

    private static String names(SignatureSummary sig) {
        return sig.getDecisiveSettings().isEmpty() ? "nic" : sig.getDecisiveSettings().toString();
    }
}
