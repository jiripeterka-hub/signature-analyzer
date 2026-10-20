package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.RevocationWrapper;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.model.policy.ValidationPolicy;
import eu.europa.esig.dss.policy.ValidationPolicyFacade;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.validation.executor.signature.DefaultSignatureProcessExecutor;
import eu.europa.esig.dss.validation.reports.Reports;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Hledá dokument, u kterého by na volbě odvozeného okamžiku záleželo.
 *
 * <p>Prvek krytý pozdějším prvkem (situace 54) se přepočítává k okamžiku
 * {@code min(vlastní nejstarší platné razítko, razítko krycího prvku)} — viz
 * {@link ValidatorFactory}. Ty dva okamžiky nejsou totéž a ten dřívější bývá <b>starší než
 * revokační údaj</b>, o který se přepočet opře; oprávnění posunout hodiny přitom dává až razítko
 * krycího prvku. Otázka zní, jestli se ta volba někdy projeví na výsledku.
 *
 * <p>Hlásí trojí:
 * <ul>
 *   <li><b>ROZDÍL</b> — přepočet k oběma okamžikům dá jinou indikaci. To je hledaný případ.</li>
 *   <li><b>PŘED ÚDAJEM</b> — odvozený okamžik předchází {@code thisUpdate} revokačního údaje
 *       k podpisovému certifikátu, tedy se ověřuje k době, kdy ten údaj ještě neexistoval.
 *       Výsledek to tu nemění, ale ukazuje, jak často se ta volba vůbec uplatní.</li>
 *   <li><b>ODSTUP</b> — jak daleko od sebe ty dva okamžiky jsou.</li>
 * </ul>
 *
 * <p>Oba přepočty běží nad <b>týmiž</b> zjištěnými daty, takže případná kontaminace sdíleným
 * verifierem obě strany posune stejně a na porovnání nemá vliv; přesto dostává každý soubor
 * vlastní {@code CertificateVerifier}, ať platí i vypsané absolutní počty.
 *
 * <pre>java -cp … DerivedInstantScan &lt;seznam-cest.txt&gt; [od] [do]</pre>
 */
public final class DerivedInstantScan {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: DerivedInstantScan <seznam-cest.txt> [od] [do]");
            return;
        }
        List<String> paths = Files.readAllLines(Path.of(args[0]));
        int from = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int to = args.length > 2 ? Integer.parseInt(args[2]) : paths.size();

        ValidationPolicy policy;
        try (var is = ValidationPolicyFacade.class.getResourceAsStream("/policy/constraint.xml")) {
            policy = ValidationPolicyFacade.newFacade().getValidationPolicy(is);
        }

        int files = 0, withProof = 0, differing = 0, beforeData = 0, gapOverDay = 0;
        for (int i = from; i < Math.min(to, paths.size()); i++) {
            String p = paths.get(i);
            if (p == null || p.isBlank()) continue;
            File f = new File(p.trim());
            if (!f.isFile()) continue;
            files++;
            if (files % 25 == 0) {
                System.out.printf("[%d/%d] prvků s krytím %d, rozdílů %d, před údajem %d%n",
                        i - from + 1, Math.min(to, paths.size()) - from,
                        withProof, differing, beforeData);
                System.out.flush();
            }
            try {
                AppSettings settings = AppSettings.load();
                var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
                Reports reports = new ValidatorFactory(settings)
                        .validate(new DocumentLoader().load(f), null, verifier, null).reports();
                DiagnosticData dd = reports.getDiagnosticData();
                SimpleReport now = reports.getSimpleReport();
                List<SignatureWrapper> sigs = dd.getSignatures();

                for (SignatureWrapper sw : sigs) {
                    if (now.getIndication(sw.getId()) != Indication.INDETERMINATE) continue;
                    ReportMapper.CoveringProof proof = ReportMapper.coveringProof(sw, sigs, now);
                    if (proof == null) continue;
                    withProof++;

                    Instant own = ReportMapper.earliestValidTimestampTime(sw, now);
                    Instant used = own != null && own.isBefore(proof.when()) ? own : proof.when();
                    if (own == null || !own.isBefore(proof.when())) continue;   // oba stejné

                    long gapSec = proof.when().getEpochSecond() - own.getEpochSecond();
                    if (gapSec > 86400) gapOverDay++;

                    Indication atOwn = recompute(reports, policy, used, sw.getId());
                    Indication atProof = recompute(reports, policy, proof.when(), sw.getId());

                    Instant earliestData = earliestRevocationThisUpdate(sw);
                    boolean before = earliestData != null && used.isBefore(earliestData);
                    if (before) beforeData++;

                    if (atOwn != atProof) {
                        differing++;
                        System.out.printf("%nROZDÍL  %s%n", f.getPath());
                        System.out.printf("   prvek %s: k vlastnímu razítku %s → %s,"
                                        + " ke krycímu %s → %s%n",
                                sw.getId().substring(0, 12), used, atOwn, proof.when(), atProof);
                    } else if (before) {
                        System.out.printf("PŘED ÚDAJEM  %s%n    prvek %s: ověřeno k %s,"
                                        + " revokační údaj až %s, odstup okamžiků %d s,"
                                        + " obojí %s%n",
                                f.getName(), sw.getId().substring(0, 12), used, earliestData,
                                gapSec, atOwn);
                    }
                    System.out.flush();
                }
            } catch (Exception e) {
                System.out.println("CHYBA  " + f.getName() + " : " + e);
            }
        }
        System.out.println();
        System.out.printf("HOTOVO: souborů %d, prvků s krycím důkazem %d, z toho s odlišnými"
                        + " okamžiky vyhodnoceno; ROZDÍLŮ %d, ověřeno před vznikem údaje %d,"
                        + " odstup přes den %d%n",
                files, withProof, differing, beforeData, gapOverDay);
    }

    private static Indication recompute(Reports reports, ValidationPolicy policy,
                                        Instant when, String sigId) {
        try {
            DefaultSignatureProcessExecutor ex = new DefaultSignatureProcessExecutor();
            ex.setDiagnosticData(reports.getDiagnosticDataJaxb());
            ex.setValidationPolicy(policy);
            ex.setCurrentTime(Date.from(when));
            return ex.execute().getSimpleReport().getIndication(sigId);
        } catch (Exception e) {
            return null;
        }
    }

    /** Nejstarší {@code thisUpdate} mezi revokačními údaji k podpisovému certifikátu. */
    private static Instant earliestRevocationThisUpdate(SignatureWrapper sw) {
        var sc = sw.getSigningCertificate();
        if (sc == null) return null;
        Instant best = null;
        for (RevocationWrapper r : sc.getCertificateRevocationData()) {
            if (r.getThisUpdate() == null) continue;
            Instant t = r.getThisUpdate().toInstant();
            if (best == null || t.isBefore(best)) best = t;
        }
        return best;
    }
}
