package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import eu.europa.esig.dss.policy.ValidationPolicyFacade;
import eu.europa.esig.dss.validation.executor.signature.DefaultSignatureProcessExecutor;

import java.io.File;
import java.time.Instant;
import java.util.Date;

/**
 * Pustí týž proces DSS nad týmiž zjištěnými daty k několika zadaným okamžikům a vypíše, co z toho
 * pro každý prvek vyjde.
 *
 * <p>Tím se dá ukázat, čím přesně se liší závěr aplikace od závěru knihovny: AnEP si u prvku
 * krytého pozdějším prvkem posouvá „current time" k doloženému okamžiku, a teprve tím se stane
 * použitelným revokační údaj, jehož vydavateli mezitím skončila platnost.
 *
 * <pre>java -cp … RecomputeAtTimeProbe &lt;soubor&gt; &lt;ISO-okamžik&gt; [další okamžiky…]</pre>
 */
public final class RecomputeAtTimeProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: RecomputeAtTimeProbe <soubor> <ISO-okamžik>...");
            return;
        }
        File file = new File(args[0]);
        AppSettings settings = AppSettings.load();
        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        var outcome = new ValidatorFactory(settings)
                .validate(new DocumentLoader().load(file), null, verifier, null);

        var policy = ValidationPolicyFacade.newFacade().getValidationPolicy(
                ValidationPolicyFacade.class.getResourceAsStream("/policy/constraint.xml"));

        System.out.println();
        report("teď (jak to dělá knihovna)", outcome.reports().getSimpleReport());
        for (int i = 1; i < args.length; i++) {
            Date when = Date.from(Instant.parse(args[i]));
            var executor = new DefaultSignatureProcessExecutor();
            executor.setDiagnosticData(outcome.reports().getDiagnosticDataJaxb());
            executor.setValidationPolicy(policy);
            executor.setCurrentTime(when);
            report("k " + args[i], executor.execute().getSimpleReport());
        }
    }

    private static void report(String label, eu.europa.esig.dss.simplereport.SimpleReport r) {
        System.out.println("===== " + label);
        int n = 0;
        for (String id : r.getSignatureIdList()) {
            n++;
            System.out.printf("   prvek č. %d : %s / %s%n", n,
                    r.getIndication(id), r.getSubIndication(id));
        }
    }
}
