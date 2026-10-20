package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.service.tsp.OnlineTSPSource;
import eu.europa.esig.dss.model.TimestampBinary;

import java.net.URI;
import java.security.MessageDigest;

/**
 * Zeptá se nastavené autority na jedno časové razítko — jednou loaderem, jaký DSS staví sám,
 * a jednou tím, který aplikace používá. Ukáže tak, jestli hlášku „Unsupported Content-Encoding:
 * binary" opravdu působí rozbalování odpovědi, a jestli ji vypnutí toho článku odstraní.
 *
 * <p>Dva síťové dotazy na autoritu, tedy dvě vydaná razítka. Bez nich se to ověřit nedá:
 * chyba vzniká až při zpracování odpovědi konkrétního serveru.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.core.TsaContentEncodingProbe
 * </pre>
 */
public final class TsaContentEncodingProbe {

    public static void main(String[] args) throws Exception {
        AppSettings settings = AppSettings.load();
        TsaProfile profile = settings.getSelectedTsa();
        if (profile == null) {
            System.out.println("V nastavení není vybraná žádná autorita časových razítek.");
            return;
        }
        System.out.println("autorita: " + profile.getUrl()
                + (profile.hasAuth() ? "  (s přihlášením)" : ""));

        byte[] digest = MessageDigest.getInstance("SHA-512").digest("AnEP".getBytes("UTF-8"));

        // Standardně jen jeden dotaz: každý stojí jedno vydané razítko. Reprodukci chyby
        // loaderem, jaký staví DSS sám, si vyžádá argument -both.
        boolean both = args.length > 0 && "-both".equals(args[0]);
        if (both) ask("loader, jaký staví DSS sám", plainLoader(profile), profile, digest);
        ask("loader aplikace (bez rozbalování)", appLoader(profile), profile, digest);
    }

    private static void ask(String label, OnlineTSPSource source, TsaProfile profile, byte[] digest) {
        try {
            TimestampBinary ts = source.getTimeStampResponse(DigestAlgorithm.SHA512, digest);
            System.out.printf("%-38s OK, token %d B%n", label, ts.getBytes().length);
        } catch (Exception e) {
            System.out.printf("%-38s CHYBA: %s%n", label, e.getMessage());
        }
    }

    /** Přesně to, co by aplikace dělala bez zásahu v TrustConfig. */
    private static OnlineTSPSource plainLoader(TsaProfile profile) {
        var loader = new eu.europa.esig.dss.service.http.commons.TimestampDataLoader();
        addAuth(loader, profile);
        OnlineTSPSource source = new OnlineTSPSource(profile.getUrl());
        source.setDataLoader(loader);
        return source;
    }

    private static OnlineTSPSource appLoader(TsaProfile profile) {
        var loader = TrustConfig.createTimestampDataLoader();
        addAuth(loader, profile);
        OnlineTSPSource source = new OnlineTSPSource(profile.getUrl());
        source.setDataLoader(loader);
        return source;
    }

    private static void addAuth(eu.europa.esig.dss.service.http.commons.CommonsDataLoader loader,
                                TsaProfile profile) {
        if (!profile.hasAuth()) return;
        try {
            URI uri = new URI(profile.getUrl());
            loader.addAuthentication(uri.getHost(), uri.getPort(), null,
                    profile.getUsername(), profile.getPassword().toCharArray());
            loader.setPreemptiveAuthentication(true);
        } catch (Exception ignored) {}
    }

    private TsaContentEncodingProbe() { }
}
