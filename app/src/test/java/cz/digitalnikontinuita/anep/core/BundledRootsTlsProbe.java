package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.x509.CertificateToken;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ověří, že k tuzemským razítkovacím a revokačním službám lze navázat spojení <b>jen</b>
 * na certifikátech přibalených v aplikaci — tedy bez úložiště Windows a bez systémových
 * {@code cacerts}.
 *
 * <p>Na tom stojí použitelnost mimo Windows: v {@code cacerts} tohoto JDK je 118 kořenů
 * a ani jeden český, takže bez tohoto zdroje by spojení skončilo chybou „PKIX path building
 * failed". Systémové úložiště sonda schválně nepoužívá — s ním by na Windows prošlo i tehdy,
 * kdyby přibalené certifikáty k ničemu nebyly, a nedokázalo by to nic.
 *
 * <p><b>Pozor na antivirus, který rozřezává TLS.</b> Na stroji s takovým programem (Avast,
 * ESET, Kaspersky a další) nedostane sonda certifikát serveru, nýbrž certifikát vystavený
 * antivirem — a výsledek pak neříká nic o přibalených certifikátech. Sonda proto nejdřív
 * vypíše, kdo certifikát vydal, a když poznává vydavatele jako takový program, řekne rovnou,
 * že měření na tomto stroji nemá výpovědní hodnotu. Přesně na tomhle jsem se 1. 9. 2026 spálil
 * a málem z toho vyvodil, že přibalené certifikáty nestačí.
 *
 * <p>Spouští se ručně; potřebuje síť.
 */
public final class BundledRootsTlsProbe {

    private BundledRootsTlsProbe() { }

    /** Služby, se kterými aplikace opravdu mluví. */
    private static final String[] HOSTS = {
        "tsabase.ica.cz",
        "www.postsignum.cz",
        "www.ica.cz",
    };

    /** Podle čeho se pozná, že do spojení mluví antivirus. */
    private static final String[] INTERCEPTORS = {
        "avast", "avg", "eset", "kaspersky", "bitdefender", "fortinet", "zscaler",
        "sophos", "mcafee", "ssl scanning", "web/mail shield",
    };

    public static void main(String[] args) throws Exception {
        List<CertificateToken> certs = new ArrayList<>(CzechCommercialCATrust.loadAllRoots());
        certs.addAll(CzechCommercialCATrust.loadCommercialIssuingCAs());
        System.out.println("přibalených certifikátů: " + certs.size()
                + " (kořeny i vydávající autority)");

        // 1. Kdo servery podepisuje doopravdy — bez ověřování, jen se podívat.
        System.out.println();
        System.out.println("--- vydavatel certifikátu serveru ---");
        boolean intercepted = false;
        for (String host : HOSTS) {
            String issuer = peerIssuer(host);
            System.out.printf("%-22s %s%n", host, issuer);
            if (issuer != null && looksIntercepted(issuer)) intercepted = true;
        }
        if (intercepted) {
            System.out.println();
            System.out.println("!!! Na tomto stroji rozřezává TLS antivirus. Certifikáty serverů");
            System.out.println("!!! sem nedorazí a test níže o přibalených certifikátech nic");
            System.out.println("!!! nedokazuje — spusťte ho na stroji bez takového programu.");
        }

        // 2. Vlastní test: projde spojení jen na přibalených certifikátech?
        System.out.println();
        System.out.println("--- spojení jen na přibalených certifikátech ---");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        int i = 0;
        for (CertificateToken t : certs) ks.setCertificateEntry("anep-" + (i++), t.getCertificate());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);

        int ok = 0;
        for (String host : HOSTS) {
            System.out.printf("%-22s ", host);
            try (SSLSocket socket = (SSLSocket) ctx.getSocketFactory().createSocket()) {
                socket.connect(new java.net.InetSocketAddress(host, 443), 15_000);
                socket.setSoTimeout(15_000);
                socket.startHandshake();
                System.out.println("OK (" + socket.getSession().getProtocol() + ")");
                ok++;
            } catch (Exception e) {
                System.out.println("SELHALO  " + e.getClass().getSimpleName()
                        + ": " + shorten(e.getMessage()));
            }
        }
        System.out.println("uspělo " + ok + " z " + HOSTS.length
                + (intercepted ? "  (výsledek zkreslený antivirem, viz výše)" : ""));
    }

    /** Jméno vydavatele certifikátu serveru, bez ověřování řetězu. */
    private static String peerIssuer(String host) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {ACCEPT_ALL}, null);
            SSLSocketFactory f = ctx.getSocketFactory();
            try (SSLSocket socket = (SSLSocket) f.createSocket()) {
                socket.connect(new java.net.InetSocketAddress(host, 443), 15_000);
                socket.setSoTimeout(15_000);
                socket.startHandshake();
                java.security.cert.Certificate[] chain =
                        socket.getSession().getPeerCertificates();
                if (chain.length == 0) return null;
                return ((X509Certificate) chain[0]).getIssuerX500Principal().getName();
            }
        } catch (Exception e) {
            return "(nedostupné: " + e.getClass().getSimpleName() + ")";
        }
    }

    private static boolean looksIntercepted(String issuer) {
        String s = issuer.toLowerCase(Locale.ROOT);
        for (String marker : INTERCEPTORS) if (s.contains(marker)) return true;
        return false;
    }

    private static String shorten(String msg) {
        if (msg == null) return "";
        return msg.length() > 90 ? msg.substring(0, 90) + "…" : msg;
    }

    /**
     * Přijme cokoli — používá se <b>jen</b> k tomu, aby se dal přečíst certifikát serveru
     * a poznat, jestli do spojení nemluví antivirus. K vlastnímu testu se nepoužívá.
     */
    private static final X509TrustManager ACCEPT_ALL = new X509TrustManager() {
        public void checkClientTrusted(X509Certificate[] c, String a) { }
        public void checkServerTrusted(X509Certificate[] c, String a) { }
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };
}
