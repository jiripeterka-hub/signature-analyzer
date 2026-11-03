package cz.digitalnikontinuita.anep.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Volba proxy pro všechna spojení aplikace — jediné místo, které o ní rozhoduje.
 *
 * <h2>Proč (3. 11. 2026)</h2>
 *
 * <p>Knihovna DSS proxy nepoužívala vůbec. Změřeno na DSS 6.5.1 s Apache HttpClient 5.6:
 * {@code CommonsDataLoader} se s nastavenou neexistující proxy ({@code https.proxyHost=127.0.0.1},
 * port 9) spojil se serverem přímo, stejně jako bez ní. Builder klienta si systémové vlastnosti
 * bere jen při {@code setUseSystemProperties(true)}, a to aplikace nevolala. V síti, kde přímý
 * odchozí provoz zakázán a vše jde přes proxy (síť justice), proto neprošla obnova důvěryhodných
 * seznamů, online revokace ani časová razítka, a aplikace se tvářila jako offline.
 *
 * <p>Třída je zároveň {@link ProxySelector}, a to jeden pro celý proces ({@link #SELECTOR}).
 * Zavaděče DSS ho dostanou jako plánovač tras ({@link TrustConfig}), kontrola verze jako proxy
 * klienta, stahování CRL z CDP při každém spojení. Nastavení se čte až při každé žádosti, ne při
 * stavbě zavaděče: zavaděč pro důvěryhodné seznamy vzniká jednou při startu a žije do konce, takže
 * kdyby si volbu zapamatoval, změna v Nastavení by se projevila až po restartu.
 *
 * <h2>Režimy</h2>
 *
 * <ul>
 *   <li>{@link Mode#SYSTEM} (výchozí) — proxy podle nastavení systému; na Windows nastavení
 *       Internetu uživatele. Kde systém žádnou proxy nemá, jde se přímo, tedy jako dřív.
 *       Vyžaduje {@code java.net.useSystemProxies=true} dřív, než se zavede třída
 *       {@code ProxySelector} — výchozí výběr JDK tu vlastnost čte jen jednou, při svém vzniku.
 *       Nastavuje ji proto {@code Main.main} jako první věc; statický blok tady by byl pozdě,
 *       protože tahle třída {@code ProxySelector} rozšiřuje a jeho zavedení předchází jejímu.
 *       Sondy, které jdou mimo {@code Main}, si ji musí předat jako {@code -D}.</li>
 *   <li>{@link Mode#MANUAL} — zadaná adresa a port, s výjimkami.</li>
 *   <li>{@link Mode#DIRECT} — vždy přímo.</li>
 * </ul>
 *
 * <p>Přihlášení k proxy aplikace neumí; proxy, která ho vyžaduje (HTTP 407), spojení odmítne.
 */
public final class NetworkProxy extends ProxySelector {

    public enum Mode { SYSTEM, MANUAL, DIRECT }

    /**
     * @param exclusions jména serverů, k nimž se jde přímo i v režimu {@link Mode#MANUAL};
     *                   {@code *.justice.cz} kryje i {@code justice.cz} samo
     */
    public record Config(Mode mode, String host, int port, List<String> exclusions) {
        public Config {
            mode = mode == null ? Mode.SYSTEM : mode;
            host = host == null ? "" : host.trim();
            exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        }

        /** Ruční režim bez použitelné adresy se chová jako přímé spojení. */
        boolean manualUsable() {
            return !host.isEmpty() && port > 0 && port <= 65535;
        }
    }

    public static final Config DEFAULT = new Config(Mode.SYSTEM, "", 0, List.of());

    public static final NetworkProxy SELECTOR = new NetworkProxy();

    private static volatile Config config = DEFAULT;

    private NetworkProxy() { }

    public static Config config() { return config; }

    public static void configure(Config c) { config = c == null ? DEFAULT : c; }

    /**
     * Výjimky ze zápisu uživatele: odděluje čárka, středník nebo mezera, jako ve Windows.
     * Prázdné položky se vynechají.
     */
    public static List<String> parseExclusions(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String s : text.split("[,;\\s]+")) {
            if (!s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Proxy pro jednu adresu — pro {@code URLConnection}, která {@code ProxySelector} nebere. */
    public static Proxy proxyFor(URI uri) {
        List<Proxy> l = SELECTOR.select(uri);
        return l.isEmpty() ? Proxy.NO_PROXY : l.get(0);
    }

    @Override
    public List<Proxy> select(URI uri) {
        if (uri == null) throw new IllegalArgumentException("URI je null");
        Config c = config;
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (isLocal(host)) return List.of(Proxy.NO_PROXY);
        switch (c.mode()) {
            case DIRECT:
                return List.of(Proxy.NO_PROXY);
            case MANUAL:
                if (!c.manualUsable() || excluded(host, c.exclusions())) return List.of(Proxy.NO_PROXY);
                return List.of(new Proxy(Proxy.Type.HTTP,
                        InetSocketAddress.createUnresolved(c.host(), c.port())));
            case SYSTEM:
            default:
                ProxySelector sys = ProxySelector.getDefault();
                // Bez té podmínky by se volal sám sebe, kdyby ho někdo nastavil jako výchozí.
                if (sys == null || sys == this) return List.of(Proxy.NO_PROXY);
                List<Proxy> l = sys.select(uri);
                return l == null || l.isEmpty() ? List.of(Proxy.NO_PROXY) : l;
        }
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        // Náhradní proxy nevolíme: když zadaná nedopoví, má to uživatel vidět, ne aby se tiše
        // šlo přímo — v síti, kde přímo nelze, by to jen prodloužilo čekání na tutéž chybu.
    }

    static boolean isLocal(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")
                || host.equals("::1");
    }

    static boolean excluded(String host, List<String> exclusions) {
        for (String e : exclusions) {
            if (e.startsWith("*.")) {
                String domain = e.substring(2);
                if (host.equals(domain) || host.endsWith("." + domain)) return true;
            } else if (e.startsWith(".")) {
                if (host.endsWith(e) || host.equals(e.substring(1))) return true;
            } else if (host.equals(e)) {
                return true;
            }
        }
        return false;
    }
}
