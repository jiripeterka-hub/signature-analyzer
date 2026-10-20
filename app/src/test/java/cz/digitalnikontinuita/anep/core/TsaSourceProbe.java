package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Co aplikace zjistí o zdroji časových razítek — vypsáno na jeden řádek, bez okna a bez uložení.
 *
 * <p>Táž sonda, kterou spouští nastavení při zadání nového zdroje ({@code TimestampingService
 * .probe}), jen s výpisem místo dialogu. Vznikla 18. 9. 2026 se dvěma otázkami, které se jinak
 * daly zodpovědět jedině proklikáním okna:
 *
 * <ul>
 *   <li><b>Funguje přihlášení klientským certifikátem?</b> Jiná vrstva než jméno a heslo — TLS
 *       místo hlavičky HTTP — takže „u hesla to jde" o něm nevypovídá nic.</li>
 *   <li><b>Pozná aplikace testovací autoritu?</b> Kvalifikovanost se od 18. 9. 2026 měří
 *       z vydaného razítka proti důvěryhodným seznamům, a měření, které nikdo nezkusil nad
 *       autoritou, o níž se odpověď ví předem, je jen kód.</li>
 * </ul>
 *
 * <p>Heslo se bere z argumentu, ne z nastavení: sonda nemá sahat na profily, které si uživatel
 * uložil, a nemá je ani měnit. Nic se neukládá, razítko se zahodí.
 *
 * <p>Použití:
 * <pre>
 *   TsaSourceProbe &lt;url&gt;                          — veřejný zdroj
 *   TsaSourceProbe &lt;url&gt; -basic &lt;jméno&gt; &lt;heslo&gt;    — jméno a heslo
 *   TsaSourceProbe &lt;url&gt; -cert &lt;soubor.p12&gt; &lt;heslo&gt; — klientský certifikát
 *   TsaSourceProbe -demo-basic                     — PostSignum DEMO, veřejné údaje
 *   TsaSourceProbe -demo-cert &lt;soubor.p12&gt; &lt;heslo&gt;  — PostSignum DEMO přes certifikát
 * </pre>
 */
public final class TsaSourceProbe {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Použití: TsaSourceProbe <url> [-basic <jmeno> <heslo>"
                    + " | -cert <soubor> <heslo>]   /   -demo-basic   /   -demo-cert <soubor> <heslo>");
            return;
        }

        TsaProfile profile = buildProfile(args);
        System.out.println("Zdroj:     " + profile.getName());
        System.out.println("URL:       " + profile.getUrl());
        System.out.println("Přihlášení: " + profile.auth()
                + (profile.hasClientCert()
                    ? " (" + profile.getClientCertPath() + ", " + profile.clientCertStoreType() + ")"
                    : profile.hasAuth() ? " (" + profile.getUsername() + ")" : ""));
        System.out.println();

        AppSettings settings = AppSettings.load();
        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier;
        try {
            verifier = trustConfig.buildLotlCertificateVerifier();
            System.out.println("Důvěryhodné seznamy: načteny");
        } catch (Exception e) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
            System.out.println("Důvěryhodné seznamy: NENAČTENY (" + e + ")"
                    + " — kvalifikovanost vyjde jako nezjištěná");
        }
        TimestampingService timestamping =
                new TimestampingService(new AtomicReference<>(verifier));

        long started = System.currentTimeMillis();
        try {
            TimestampingService.TsaProbe probe = timestamping.probe(profile);
            System.out.println();
            System.out.println("VYDAL RAZÍTKO");
            System.out.println("  otiskové funkce:  " + String.join(", ", probe.supportedHashes()));
            System.out.println("  certifikát platí do: " + probe.certNotAfter());
            System.out.println("  kvalifikovanost:  " + probe.quality());
        } catch (Exception e) {
            System.out.println();
            System.out.println("NEVYDAL RAZÍTKO");
            for (Throwable t = e; t != null; t = t.getCause()) {
                System.out.println("  " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        System.out.println("  (" + (System.currentTimeMillis() - started) + " ms)");
    }

    private static TsaProfile buildProfile(String[] args) {
        if ("-demo-basic".equals(args[0])) {
            return TsaProfile.postSignumDemoBasic();
        }
        if ("-demo-cert".equals(args[0])) {
            TsaProfile p = TsaProfile.postSignumDemoCertificate();
            p.setClientCertPath(args[1]);
            p.setClientCertPassword(args.length > 2 ? args[2] : "");
            return p;
        }
        TsaProfile p = new TsaProfile("(zadaný zdroj)", args[0], "", "", TsaProfile.HASH_SHA256);
        if (args.length >= 3 && "-basic".equals(args[1])) {
            p.setUsername(args[2]);
            p.setPassword(args.length > 3 ? args[3] : "");
        } else if (args.length >= 3 && "-cert".equals(args[1])) {
            p.setClientCertPath(args[2]);
            p.setClientCertPassword(args.length > 3 ? args[3] : "");
        }
        return p;
    }

    private TsaSourceProbe() { }
}
