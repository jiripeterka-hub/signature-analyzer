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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * <h2>Dvě rozhodnutí o jednom spojení (5. 11. 2026)</h2>
 *
 * <p>Nález uživatele: v síti s HTTP proxy, kde systém zároveň uváděl SOCKS, končilo každé spojení
 * timeoutem, i se zadanou proxy. Plánovač tras HTTP klienta rozhodne jen, přes kterou HTTP proxy se
 * jde. Soket k ní pak otevírá {@code new Socket()} a ten se sám ptá výchozího výběru JDK na adresu
 * {@code socket://proxy:port}; při {@code useSystemProxies=true} dostal SOCKS ze systému a spojení
 * na proxy se vydalo přes něj. Změřeno na falešné proxy mimo zpětnou smyčku: s SOCKS v JVM proxy
 * nedostala nic a po 44 s přišlo „Can't connect to SOCKS proxy“, s přímým soketem dostala CONNECT
 * za 1 s. Tuto třídu proto {@link #install()} dělá i výchozím výběrem JDK, aby volba platila také
 * pro sokety ({@link #selectForSocket}).
 *
 * <p>SOCKS jde jen touto cestou: plánovač tras SOCKS přeskočí, zvolí přímou trasu a tunel vznikne
 * na úrovni soketu. Jméno cílového serveru se přitom překládá v počítači, ne na proxy, protože
 * HTTP klient do soketu předává už přeloženou adresu. Klient JDK (kontrola verze) SOCKS nepoužije
 * a jde přímo; změřeno testem v {@code NetworkProxyTest}.
 */
public final class NetworkProxy extends ProxySelector {

    public enum Mode { SYSTEM, MANUAL, DIRECT }

    /** Protokol zadané proxy; v režimu {@link Mode#MANUAL}. */
    public enum Protocol { HTTP, SOCKS }

    /**
     * @param exclusions jména serverů, k nimž se jde přímo i v režimu {@link Mode#MANUAL};
     *                   {@code *.justice.cz} kryje i {@code justice.cz} samo
     */
    public record Config(Mode mode, String host, int port, List<String> exclusions, Protocol protocol) {
        public Config {
            mode = mode == null ? Mode.SYSTEM : mode;
            host = host == null ? "" : host.trim();
            exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
            protocol = protocol == null ? Protocol.HTTP : protocol;
        }

        public Config(Mode mode, String host, int port, List<String> exclusions) {
            this(mode, host, port, exclusions, Protocol.HTTP);
        }

        /** Ruční režim bez použitelné adresy se chová jako přímé spojení. */
        boolean manualUsable() {
            return !host.isEmpty() && port > 0 && port <= 65535;
        }
    }

    public static final Config DEFAULT = new Config(Mode.SYSTEM, "", 0, List.of());

    public static final NetworkProxy SELECTOR = new NetworkProxy();

    private static volatile Config config = DEFAULT;

    /** Výběr JDK podle systému, jaký byl před {@link #install()}; do té doby null. */
    private static volatile ProxySelector system;

    /**
     * HTTP proxy, které systémový výběr vrátil pro některý cíl. Soket na takovou adresu musí jít
     * přímo, i když systém uvádí zároveň SOCKS. Adresu nelze spočítat dopředu: při skriptu PAC
     * závisí na cíli. Velikost omezuje počet proxy v síti.
     */
    private static final Set<String> SYSTEM_HTTP_PROXIES = ConcurrentHashMap.newKeySet();

    private NetworkProxy() { }

    public static Config config() { return config; }

    public static void configure(Config c) {
        Config next = c == null ? DEFAULT : c;
        // Jiná trasa může dosáhnout i serverů, které předtím neodpověděly (NetworkWatch).
        if (!next.equals(config)) NetworkWatch.forgetUnreachable();
        config = next;
    }

    /**
     * Udělá z této třídy výchozí výběr proxy v JDK. Volá se z {@code Main.main} hned po nastavení
     * {@code java.net.useSystemProxies}. Sondy mimo {@code Main} to nemají a sokety se v nich řídí
     * systémem jako dřív.
     */
    public static synchronized void install() {
        ProxySelector current = ProxySelector.getDefault();
        if (current != SELECTOR) install(current);
    }

    /** Pro testy: systémový výběr se dá podstrčit, skutečné nastavení systému se měnit nedá. */
    static synchronized void install(ProxySelector systemSelector) {
        system = systemSelector;
        SYSTEM_HTTP_PROXIES.clear();
        ProxySelector.setDefault(SELECTOR);
    }

    /** Pro testy: vrátí výchozí výběr, aby nezůstal viset na další testy v téže JVM. */
    static synchronized void uninstall(ProxySelector previous) {
        system = null;
        SYSTEM_HTTP_PROXIES.clear();
        ProxySelector.setDefault(previous);
    }

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
        if ("socket".equalsIgnoreCase(uri.getScheme())) return selectForSocket(uri, host, c);
        switch (c.mode()) {
            case DIRECT:
                return List.of(Proxy.NO_PROXY);
            case MANUAL:
                if (!c.manualUsable() || excluded(host, c.exclusions())) return List.of(Proxy.NO_PROXY);
                return List.of(manualProxy(c));
            case SYSTEM:
            default:
                List<Proxy> l = askSystem(uri);
                for (Proxy p : l) {
                    if (p.type() == Proxy.Type.HTTP && p.address() instanceof InetSocketAddress a) {
                        SYSTEM_HTTP_PROXIES.add(key(a.getHostString(), a.getPort()));
                    }
                }
                return l;
        }
    }

    /**
     * Otázka soketu: {@code socket://cíl:port}, kde cílem je buď samotná HTTP proxy, nebo server
     * při přímé trase. SOCKS se použije, jen když je opravdu zvolený; jinak by se jím vydalo i
     * spojení na zadanou HTTP proxy, nebo spojení při volbě „bez proxy“.
     */
    private List<Proxy> selectForSocket(URI uri, String host, Config c) {
        switch (c.mode()) {
            case DIRECT:
                return List.of(Proxy.NO_PROXY);
            case MANUAL:
                if (c.protocol() != Protocol.SOCKS || !c.manualUsable()
                        || excluded(host, c.exclusions())) {
                    return List.of(Proxy.NO_PROXY);
                }
                return List.of(manualProxy(c));
            case SYSTEM:
            default:
                // Síť, kde systém uvádí jen SOCKS, musí fungovat dál: mimo známé HTTP proxy
                // rozhoduje systém.
                if (SYSTEM_HTTP_PROXIES.contains(key(host, uri.getPort()))) {
                    return List.of(Proxy.NO_PROXY);
                }
                return askSystem(uri);
        }
    }

    private List<Proxy> askSystem(URI uri) {
        ProxySelector sys = system != null ? system : ProxySelector.getDefault();
        // Bez té podmínky by se volal sám sebe, kdyby ho někdo nastavil jako výchozí.
        if (sys == null || sys == this) return List.of(Proxy.NO_PROXY);
        List<Proxy> l = sys.select(uri);
        return l == null || l.isEmpty() ? List.of(Proxy.NO_PROXY) : l;
    }

    private static Proxy manualProxy(Config c) {
        return new Proxy(c.protocol() == Protocol.SOCKS ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(c.host(), c.port()));
    }

    private static String key(String host, int port) {
        return host.toLowerCase(Locale.ROOT) + ":" + port;
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
