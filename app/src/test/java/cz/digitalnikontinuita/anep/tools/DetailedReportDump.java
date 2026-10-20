package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Uloží podrobný protokol DSS tak, jak ho vidí tenhle běh aplikace.
 *
 * <p>Otázku „proč to dopadlo takhle" zodpoví protokol sám a bez výkladu: je v něm každá kontrola
 * i s hodnotami, se kterými počítala. Bez tohohle výpisu by se muselo hádat z toho, co z něj
 * aplikace přenesla do situací a důvodů — a právě ten převod je to, co se ověřuje.
 *
 * <pre>
 *   java -Dstdout.encoding=UTF-8 -cp "app/target/test-classes;app/target/classes;$(cat cp.txt)" \
 *        cz.digitalnikontinuita.anep.tools.DetailedReportDump &lt;soubor&gt; &lt;výstup.xml&gt;
 * </pre>
 */
public final class DetailedReportDump {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: DetailedReportDump <soubor> <výstup.xml>");
            return;
        }
        AppSettings settings = AppSettings.load();
        TrustConfig trust = new TrustConfig(settings);
        CommonCertificateVerifier verifier = trust.buildLotlCertificateVerifier();
        DSSDocument doc = new DocumentLoader().load(Path.of(args[0]).toFile());
        ValidationOutcome outcome =
                new ValidatorFactory(settings).validate(doc, null, verifier, null);

        Path detailed = Path.of(args[1]);
        Files.writeString(detailed, outcome.reports().getXmlDetailedReport(), StandardCharsets.UTF_8);
        Path simple = Path.of(args[1].replaceAll("\\.xml$", "") + "-simple.xml");
        Files.writeString(simple, outcome.reports().getXmlSimpleReport(), StandardCharsets.UTF_8);
        System.out.println("zapsáno: " + detailed + "  a  " + simple);
    }
}
