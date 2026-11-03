package cz.digitalnikontinuita.anep.ui;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zjištění, zda je na stránce aplikace vydaná novější verze.
 *
 * <h2>Proč takhle (1. 11. 2026)</h2>
 *
 * <p>Kontrola běží jen na stisk odkazu v okně „O aplikaci“, nikdy sama při startu. AnEP slouží
 * i k forenznímu posouzení a často běží bez sítě; aplikace, která se při každém spuštění sama
 * někam ozve, by navíc prozrazovala, kdy a kde se s ní pracuje. Na stisk je to rozhodnutí
 * uživatele.
 *
 * <p>Aplikace nic nestahuje ani neinstaluje — jen řekne, že novější verze je, a nabídne stránku
 * {@link #DOWNLOAD_URL}. Stáhnout a nainstalovat si ji má uživatel sám (jeho pokyn). Proto tu
 * není ani kontrola otisku balíčku: ověřit, co si stáhl, je věc stránky a podpisu instalátoru,
 * ne téhle zprávy.
 *
 * <p>Údaj o poslední verzi je malý soubor {@link #INFO_URL} na téže stránce, kterou vydání
 * spravuje ručně:
 * <pre>{"verze": "1.1.0", "datum": "2026-11-15"}</pre>
 * Čte se z něj jen tohle dvojí a vlastním vzorem, ne knihovnou JSON: Jackson je na třídní cestě
 * jen díky DSS a kvůli dvěma řetězcům na něj aplikaci vázat nestojí. Datum je nepovinné.
 */
final class NewVersionCheck {

    private NewVersionCheck() { }

    /** Stránka, ze které si uživatel novou verzi stáhne. */
    static final String DOWNLOAD_URL = "https://www.bajecnysvet.cz/anep/ziskat.php";

    /** Soubor s číslem poslední vydané verze. Není {@code final} jen kvůli sondě, která ho podvrhne. */
    static URI INFO_URL = URI.create("https://www.bajecnysvet.cz/anep/verze.json");

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern VERSION = Pattern.compile("\"verze\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DATE = Pattern.compile("\"datum\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern NUMERIC = Pattern.compile("\\d+(\\.\\d+)*");

    /** Poslední vydaná verze; {@code date} je {@code null}, když ho soubor neuvádí. */
    record Latest(String version, LocalDate date) { }

    /**
     * Stáhne a přečte {@link #INFO_URL}.
     *
     * @throws Exception když stránka nedopoví, vrátí jiný kód než 200, nebo v odpovědi
     *         není číslo verze v podobě „1.2.3“
     */
    static Latest fetch(String currentVersion) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                // Táž volba proxy jako u ostatních spojení (Nastavení, 3. 11. 2026).
                .proxy(cz.digitalnikontinuita.anep.core.NetworkProxy.SELECTOR)
                .build();
        HttpRequest req = HttpRequest.newBuilder(INFO_URL)
                .timeout(TIMEOUT)
                // Ať odpověď nepodrží mezipaměť po cestě: po vydání se má nová verze
                // ohlásit hned, ne až vyprší něčí cache.
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "AnEP/" + (currentVersion == null ? "dev" : currentVersion))
                .GET()
                .build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new java.io.IOException("Stránka vrátila kód " + resp.statusCode());
        }
        Latest latest = parse(new String(resp.body(), StandardCharsets.UTF_8));
        if (latest == null) throw new java.io.IOException("Odpověď neuvádí číslo verze");
        return latest;
    }

    /** Číslo a datum z obsahu souboru; {@code null}, když v něm číslo verze není. */
    static Latest parse(String body) {
        if (body == null) return null;
        Matcher v = VERSION.matcher(body);
        if (!v.find()) return null;
        String version = v.group(1).trim();
        if (!NUMERIC.matcher(version).matches()) return null;
        LocalDate date = null;
        Matcher d = DATE.matcher(body);
        if (d.find()) {
            try {
                date = LocalDate.parse(d.group(1).trim());
            } catch (java.time.format.DateTimeParseException ignored) {
                // Datum je jen doplněk; špatně zapsané nesmí shodit ohlášení verze.
            }
        }
        return new Latest(version, date);
    }

    /**
     * Porovná dvě čísla verzí po složkách; chybějící složka je nula, takže „1.0“ = „1.0.0“.
     * To není akademické: okno „O aplikaci“ ukazuje u verze 1.0.0 jméno vydání „1.0“.
     *
     * <p>Přípona za číslicemi („1.1.0-rc1“) se nebere v potaz — vydaná verze ji nemá.
     */
    static int compare(String a, String b) {
        String[] pa = numericPart(a).split("\\.");
        String[] pb = numericPart(b).split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = i < pa.length && !pa[i].isEmpty() ? Long.parseLong(pa[i]) : 0;
            long y = i < pb.length && !pb[i].isEmpty() ? Long.parseLong(pb[i]) : 0;
            if (x != y) return Long.compare(x, y);
        }
        return 0;
    }

    private static String numericPart(String v) {
        Matcher m = Pattern.compile("^\\d+(\\.\\d+)*").matcher(v == null ? "" : v.trim());
        return m.find() ? m.group() : "0";
    }
}
