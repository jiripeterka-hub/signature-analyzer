package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proxy pro spojení aplikace (3. 11. 2026). Do té doby šla knihovna DSS vždy přímo, i když byla
 * proxy nastavená — změřeno s neexistující proxy, viz {@link NetworkProxy}.
 *
 * <p>Bez internetu: cíl je {@code anep-proxy-test.invalid}, jméno, které nikdy nejde přeložit
 * (RFC 6761). Odpovědět na něj může jen místní „proxy“ v testu, která žádost zapíše a odpoví
 * sama. Přímé spojení tedy skončí chybou překladu jména, cesta přes proxy odpovědí.
 */
class NetworkProxyTest {

    private static final String TARGET = "http://anep-proxy-test.invalid/seznam.crl";

    /** Adresa bez překladu jména: SOCKS jde až po překladu v počítači. Skutečně se na ni nejde. */
    private static final String SOCKS_TARGET = "http://192.0.2.5/seznam.crl";

    private final ProxySelector defaultBefore = ProxySelector.getDefault();

    @AfterEach
    void reset() {
        NetworkProxy.configure(NetworkProxy.DEFAULT);
        NetworkProxy.uninstall(defaultBefore);
    }

    @Test
    @DisplayName("Spojení na zadanou HTTP proxy nejde přes SOCKS ze systému")
    void hopToManualHttpProxyIgnoresSystemSocks() throws Exception {
        try (FakeProxy proxy = new FakeProxy(); FakeSocks socks = new FakeSocks()) {
            NetworkProxy.install(alwaysSocks(socks.port()));
            NetworkProxy.configure(new NetworkProxy.Config(NetworkProxy.Mode.MANUAL,
                    "127.0.0.1", proxy.port(), List.of()));
            assertEquals("ok", new String(TrustConfig.createDataLoader().get(TARGET),
                    StandardCharsets.US_ASCII));
            assertEquals(1, proxy.requests.size());
            assertEquals(0, socks.targets.size(), socks.targets.toString());
        }
    }

    @Test
    @DisplayName("Spojení na HTTP proxy ze systému nejde přes SOCKS ze systému")
    void hopToSystemHttpProxyIgnoresSystemSocks() throws Exception {
        try (FakeProxy proxy = new FakeProxy(); FakeSocks socks = new FakeSocks()) {
            // Jako nastavení sítě u uživatele: pro HTTP(S) proxy HTTP, pro sokety SOCKS.
            NetworkProxy.install(new ProxySelector() {
                @Override public List<Proxy> select(URI u) {
                    return "socket".equals(u.getScheme())
                            ? alwaysSocks(socks.port()).select(u)
                            : List.of(new Proxy(Proxy.Type.HTTP,
                                    new java.net.InetSocketAddress("127.0.0.1", proxy.port())));
                }
                @Override public void connectFailed(URI u, SocketAddress s, IOException e) { }
            });
            assertEquals("ok", new String(TrustConfig.createDataLoader().get(TARGET),
                    StandardCharsets.US_ASCII));
            assertEquals(1, proxy.requests.size());
            assertEquals(0, socks.targets.size(), socks.targets.toString());
        }
    }

    @Test
    @DisplayName("Volba bez proxy nejde přes SOCKS ze systému")
    void directModeIgnoresSystemSocks() throws Exception {
        try (FakeSocks socks = new FakeSocks()) {
            NetworkProxy.install(alwaysSocks(socks.port()));
            NetworkProxy.configure(new NetworkProxy.Config(NetworkProxy.Mode.DIRECT, "", 0, List.of()));
            assertEquals(List.of(Proxy.NO_PROXY),
                    NetworkProxy.SELECTOR.select(URI.create("socket://192.0.2.5:80")));
        }
    }

    @Test
    @DisplayName("Síť jen se SOCKS v systému funguje dál")
    void systemSocksOnlyStillUsed() throws Exception {
        try (FakeSocks socks = new FakeSocks()) {
            NetworkProxy.install(alwaysSocks(socks.port()));
            assertEquals("ok", new String(TrustConfig.createDataLoader().get(SOCKS_TARGET),
                    StandardCharsets.US_ASCII));
            assertEquals(List.of("192.0.2.5:80"), socks.targets);
        }
    }

    @Test
    @DisplayName("Zavaděče DSS jdou přes zadanou proxy SOCKS")
    void dssLoadersUseManualSocks() throws Exception {
        try (FakeSocks socks = new FakeSocks()) {
            NetworkProxy.install(alwaysDirect());
            NetworkProxy.configure(new NetworkProxy.Config(NetworkProxy.Mode.MANUAL,
                    "127.0.0.1", socks.port(), List.of(), NetworkProxy.Protocol.SOCKS));
            assertEquals("ok", new String(TrustConfig.createDataLoader().get(SOCKS_TARGET),
                    StandardCharsets.US_ASCII));
            TrustConfig.createOcspDataLoader().post(SOCKS_TARGET, new byte[] {1, 2, 3});
            TrustConfig.createTimestampDataLoader().post(SOCKS_TARGET, new byte[] {4, 5, 6});
            assertEquals(List.of("192.0.2.5:80", "192.0.2.5:80", "192.0.2.5:80"), socks.targets);
        }
    }

    @Test
    @DisplayName("Výjimka jde mimo zadanou proxy SOCKS")
    void socksExclusionGoesDirect() throws Exception {
        NetworkProxy.install(alwaysDirect());
        NetworkProxy.configure(new NetworkProxy.Config(NetworkProxy.Mode.MANUAL,
                "socks.example", 1080, List.of("*.justice.cz"), NetworkProxy.Protocol.SOCKS));
        assertEquals(Proxy.Type.SOCKS,
                NetworkProxy.SELECTOR.select(URI.create("socket://crl.ica.cz:80")).get(0).type());
        assertEquals(List.of(Proxy.NO_PROXY),
                NetworkProxy.SELECTOR.select(URI.create("socket://a.justice.cz:80")));
    }

    @Test
    @DisplayName("Spojení přes URLConnection (služba ReportMapper) jde přes zadanou proxy SOCKS")
    void urlConnectionUsesManualSocks() throws Exception {
        try (FakeSocks socks = new FakeSocks()) {
            NetworkProxy.configure(new NetworkProxy.Config(NetworkProxy.Mode.MANUAL,
                    "127.0.0.1", socks.port(), List.of(), NetworkProxy.Protocol.SOCKS));
            URI uri = URI.create(SOCKS_TARGET);
            var conn = uri.toURL().openConnection(NetworkProxy.proxyFor(uri));
            try (var in = conn.getInputStream()) {
                assertEquals("ok", new String(in.readAllBytes(), StandardCharsets.US_ASCII));
            }
            assertEquals(List.of("192.0.2.5:80"), socks.targets);
        }
    }

    @Test
    @DisplayName("Klient JDK (kontrola verze) proxy SOCKS nepoužije")
    void jdkHttpClientSkipsSocks() throws Exception {
        try (FakeSocks socks = new FakeSocks()) {
            NetworkProxy.configure(new NetworkProxy.Config(NetworkProxy.Mode.MANUAL,
                    "127.0.0.1", socks.port(), List.of(), NetworkProxy.Protocol.SOCKS));
            var client = java.net.http.HttpClient.newBuilder().proxy(NetworkProxy.SELECTOR).build();
            // Přímo na jméno, které nejde přeložit: selže, a SOCKS se nikdo nezeptá.
            assertThrows(Exception.class, () -> client.send(
                    java.net.http.HttpRequest.newBuilder(URI.create(TARGET)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()));
            assertEquals(0, socks.targets.size(), socks.targets.toString());
        }
    }

    @Test
    @DisplayName("U SOCKS se jméno serveru překládá v počítači")
    void socksResolvesNameLocally() throws Exception {
        try (FakeSocks socks = new FakeSocks()) {
            NetworkProxy.configure(new NetworkProxy.Config(NetworkProxy.Mode.MANUAL,
                    "127.0.0.1", socks.port(), List.of(), NetworkProxy.Protocol.SOCKS));
            NetworkProxy.install(alwaysDirect());
            assertThrows(Exception.class, () -> TrustConfig.createDataLoader().get(TARGET));
            assertEquals(0, socks.targets.size(), socks.targets.toString());
        }
    }

    private static ProxySelector alwaysSocks(int port) {
        return new ProxySelector() {
            @Override public List<Proxy> select(URI u) {
                return List.of(new Proxy(Proxy.Type.SOCKS,
                        new java.net.InetSocketAddress("127.0.0.1", port)));
            }
            @Override public void connectFailed(URI u, SocketAddress s, IOException e) { }
        };
    }

    private static ProxySelector alwaysDirect() {
        return new ProxySelector() {
            @Override public List<Proxy> select(URI u) { return List.of(Proxy.NO_PROXY); }
            @Override public void connectFailed(URI u, SocketAddress s, IOException e) { }
        };
    }

    @Test
    @DisplayName("Zavaděč DSS jde přes zadanou proxy")
    void dssLoaderUsesManualProxy() throws Exception {
        try (FakeProxy proxy = new FakeProxy()) {
            NetworkProxy.configure(new NetworkProxy.Config(NetworkProxy.Mode.MANUAL,
                    "127.0.0.1", proxy.port(), List.of()));
            byte[] body = TrustConfig.createDataLoader().get(TARGET);
            assertEquals("ok", new String(body, StandardCharsets.US_ASCII));
            assertEquals(1, proxy.requests.size());
            assertTrue(proxy.requests.get(0).startsWith("GET " + TARGET), proxy.requests.toString());
        }
    }

    @Test
    @DisplayName("Zavaděče OCSP a razítek jdou přes zadanou proxy")
    void ocspAndTimestampLoadersUseManualProxy() throws Exception {
        try (FakeProxy proxy = new FakeProxy()) {
            NetworkProxy.configure(new NetworkProxy.Config(NetworkProxy.Mode.MANUAL,
                    "127.0.0.1", proxy.port(), List.of()));
            TrustConfig.createOcspDataLoader().post(TARGET, new byte[] {1, 2, 3});
            TrustConfig.createTimestampDataLoader().post(TARGET, new byte[] {4, 5, 6});
            assertEquals(2, proxy.requests.size(), proxy.requests.toString());
            assertTrue(proxy.requests.stream().allMatch(r -> r.startsWith("POST " + TARGET)),
                    proxy.requests.toString());
        }
    }

    @Test
    @DisplayName("Změna volby platí i pro zavaděč, který už existuje")
    void changeAppliesToExistingLoader() throws Exception {
        var loader = TrustConfig.createDataLoader();
        try (FakeProxy proxy = new FakeProxy()) {
            NetworkProxy.configure(new NetworkProxy.Config(NetworkProxy.Mode.DIRECT,
                    "", 0, List.of()));
            assertThrows(Exception.class, () -> loader.get(TARGET));
            NetworkProxy.configure(new NetworkProxy.Config(NetworkProxy.Mode.MANUAL,
                    "127.0.0.1", proxy.port(), List.of()));
            assertEquals("ok", new String(loader.get(TARGET), StandardCharsets.US_ASCII));
            assertEquals(1, proxy.requests.size());
        }
    }

    @Test
    @DisplayName("Výjimka a režim bez proxy jdou přímo")
    void exclusionsAndDirectBypassProxy() throws Exception {
        try (FakeProxy proxy = new FakeProxy()) {
            NetworkProxy.configure(new NetworkProxy.Config(NetworkProxy.Mode.MANUAL,
                    "127.0.0.1", proxy.port(), List.of("*.invalid")));
            assertThrows(Exception.class, () -> TrustConfig.createDataLoader().get(TARGET));
            assertEquals(0, proxy.requests.size());
        }
    }

    @Test
    @DisplayName("Klient JDK (kontrola verze) jde přes zadanou proxy")
    void jdkHttpClientUsesSelector() throws Exception {
        try (FakeProxy proxy = new FakeProxy()) {
            NetworkProxy.configure(new NetworkProxy.Config(NetworkProxy.Mode.MANUAL,
                    "127.0.0.1", proxy.port(), List.of()));
            var client = java.net.http.HttpClient.newBuilder().proxy(NetworkProxy.SELECTOR).build();
            var resp = client.send(java.net.http.HttpRequest.newBuilder(URI.create(TARGET)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals("ok", resp.body());
            assertEquals(1, proxy.requests.size());
        }
    }

    @Test
    @DisplayName("Výběr proxy: výjimky, místní adresy, nepoužitelná ruční volba")
    void selection() {
        NetworkProxy.configure(new NetworkProxy.Config(NetworkProxy.Mode.MANUAL, "proxy.example",
                3128, NetworkProxy.parseExclusions("*.justice.cz; intranet , .local")));
        assertEquals(Proxy.Type.HTTP, NetworkProxy.proxyFor(URI.create("http://crl.ica.cz/x")).type());
        assertEquals(Proxy.NO_PROXY, NetworkProxy.proxyFor(URI.create("http://justice.cz/x")));
        assertEquals(Proxy.NO_PROXY, NetworkProxy.proxyFor(URI.create("https://a.b.justice.cz/x")));
        assertEquals(Proxy.NO_PROXY, NetworkProxy.proxyFor(URI.create("http://intranet/x")));
        assertEquals(Proxy.NO_PROXY, NetworkProxy.proxyFor(URI.create("http://pc.local/x")));
        assertEquals(Proxy.NO_PROXY, NetworkProxy.proxyFor(URI.create("http://localhost:8080/x")));
        assertEquals(Proxy.Type.HTTP,
                NetworkProxy.proxyFor(URI.create("http://notjustice.cz/x")).type());

        NetworkProxy.configure(new NetworkProxy.Config(NetworkProxy.Mode.MANUAL, "", 3128, List.of()));
        assertEquals(Proxy.NO_PROXY, NetworkProxy.proxyFor(URI.create("http://crl.ica.cz/x")));
    }

    @Test
    @DisplayName("Volba proxy se uloží a načte")
    void persistence() {
        assertEquals(List.of("*.justice.cz", "intranet"),
                NetworkProxy.parseExclusions(" *.Justice.cz,intranet ;"));
        assertEquals(NetworkProxy.Mode.SYSTEM, new AppSettings().getProxy().mode());
    }

    /**
     * Místní SOCKS5: zapíše cíl, odpoví „spojeno“ a na tomtéž soketu sám odpoví na žádost HTTP
     * „ok“, jako by to byl cílový server.
     */
    private static final class FakeSocks implements AutoCloseable {
        final List<String> targets = new CopyOnWriteArrayList<>();
        private final ServerSocket server;

        FakeSocks() throws Exception {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread t = new Thread(this::serve, "fake-socks");
            t.setDaemon(true);
            t.start();
        }

        int port() { return server.getLocalPort(); }

        private void serve() {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    OutputStream out = s.getOutputStream();
                    in.readByte();
                    in.readFully(new byte[in.readUnsignedByte()]);
                    out.write(new byte[] {5, 0});
                    out.flush();
                    in.readFully(new byte[3]);
                    int type = in.readUnsignedByte();
                    String host;
                    if (type == 1) {
                        byte[] a = new byte[4];
                        in.readFully(a);
                        host = InetAddress.getByAddress(a).getHostAddress();
                    } else {
                        byte[] a = new byte[in.readUnsignedByte()];
                        in.readFully(a);
                        host = new String(a, StandardCharsets.US_ASCII);
                    }
                    targets.add(host + ":" + in.readUnsignedShort());
                    out.write(new byte[] {5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
                    out.flush();
                    int length = 0;
                    StringBuilder line = new StringBuilder();
                    for (int c; (c = in.read()) >= 0; ) {
                        if (c == '\r') continue;
                        if (c != '\n') { line.append((char) c); continue; }
                        if (line.length() == 0) break;
                        String h = line.toString().toLowerCase();
                        if (h.startsWith("content-length:")) length = Integer.parseInt(h.substring(15).trim());
                        line.setLength(0);
                    }
                    for (int i = 0; i < length; i++) in.read();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                            + "Content-Length: 2\r\nConnection: close\r\n\r\nok")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (Exception ignored) {
                    // zavřeno, nebo klient odpadl — test to pozná z obsahu seznamu
                }
            }
        }

        @Override
        public void close() throws Exception { server.close(); }
    }

    /** Místní „proxy“: přijme žádost, zapíše její první řádek a odpoví „ok“. */
    private static final class FakeProxy implements AutoCloseable {
        final List<String> requests = new CopyOnWriteArrayList<>();
        private final ServerSocket server;

        FakeProxy() throws Exception {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread t = new Thread(this::serve, "fake-proxy");
            t.setDaemon(true);
            t.start();
        }

        int port() { return server.getLocalPort(); }

        private void serve() {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    String first = in.readLine();
                    int length = 0;
                    for (String h; (h = in.readLine()) != null && !h.isEmpty(); ) {
                        if (h.toLowerCase().startsWith("content-length:")) {
                            length = Integer.parseInt(h.substring(15).trim());
                        }
                    }
                    for (int i = 0; i < length; i++) in.read();
                    if (first != null) requests.add(first);
                    OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                            + "Content-Length: 2\r\nConnection: close\r\n\r\nok")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (Exception ignored) {
                    // zavřeno, nebo klient odpadl — test to pozná z počtu žádostí
                }
            }
        }

        @Override
        public void close() throws Exception { server.close(); }
    }
}
