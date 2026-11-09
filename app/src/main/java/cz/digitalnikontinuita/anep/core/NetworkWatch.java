package cz.digitalnikontinuita.anep.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Síťové žádosti při ověřování: které právě běží (pro stavový řádek) a které servery do konce
 * běhu přeskakovat, protože neodpověděly.
 *
 * <h2>Proč paměť nedostupných serverů</h2>
 *
 * <p>Podpis s razítky italského Ministerstva obrany (TSA {@code difesa.it}) se ověřoval přes
 * 50 s, a to při každém ověření znovu. Servery {@code ocsppkiff.difesa.it} (OCSP) a
 * {@code www.pki.difesa.it} (CRL) zjevně nepřijímají spojení zvenčí Itálie; každý pokus visel,
 * dokud ho po zhruba 21 s nevzdala sama Windows (curl i DSS stejně). Nález uživatele
 * 9. 11. 2026. Časové limity zůstaly beze změny (rozhodnutí uživatele); jen se server, který
 * jednou takhle propadl, už během téhož běhu znovu nezkouší.
 *
 * <p>Zapamatuje se jen server, na který se <b>čekalo</b> ({@link #SLOW_FAILURE_MS}) a pak
 * selhal na spojení nebo na vypršení času. Rychlé selhání (spojení odmítnuto, síť nedostupná,
 * neznámé jméno) čekání nestojí, a kdyby se pamatovalo, výpadek sítě při jediném ověření by
 * zablokoval všechny servery až do restartu. Odpověď s chybovým kódem HTTP se nepamatuje nikdy —
 * server odpověděl.
 *
 * <p>Změna proxy v Nastavení paměť maže ({@link #forgetUnreachable}): přes jinou trasu může být
 * dostupné to, co předtím nebylo.
 */
public final class NetworkWatch {

    /** Od kolika ms čekání stojí selhání za zapamatování. */
    static final long SLOW_FAILURE_MS = 5_000;

    /** Od kolika ms čekání se ukáže ve stavovém řádku. Běžná odpověď OCSP či CRL přijde pod 1 s. */
    public static final long SHOW_WAIT_MS = 3_000;

    /** Žádost, která právě běží. */
    public record Wait(String host, String kind, long startNanos) {
        public long elapsedMs() {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }
    }

    @FunctionalInterface
    public interface IoCall<T> {
        T call() throws IOException;
    }

    private static final Set<String> UNREACHABLE = ConcurrentHashMap.newKeySet();
    private static final Map<Long, Wait> ACTIVE = new ConcurrentHashMap<>();
    private static final AtomicLong IDS = new AtomicLong();

    private NetworkWatch() { }

    /**
     * Provede žádost na {@code uri} pod dohledem: u serveru z paměti nedostupných rovnou
     * vyhodí {@link IOException} (volající ji bere jako nedostupný zdroj), jinak žádost
     * zapíše mezi běžící a podle výsledku případně server zapamatuje.
     */
    public static <T> T watch(URI uri, String kind, IoCall<T> call) throws IOException {
        return watch(uri, kind, true, call);
    }

    /**
     * Totéž, ale s {@code remember == false} se paměť nedostupných ani nečte, ani neplní — pro
     * stahování důvěryhodných seznamů: jejich obnovu spouští uživatel i ručně, typicky právě
     * po potížích se sítí, a ta se má o spojení pokusit znovu.
     */
    public static <T> T watch(URI uri, String kind, boolean remember, IoCall<T> call)
            throws IOException {
        String host = uri == null ? null : uri.getHost();
        if (host == null) return call.call();
        String key = host.toLowerCase(Locale.ROOT);
        if (remember && UNREACHABLE.contains(key)) {
            throw new ConnectException("Server " + host + " v tomto běhu aplikace neodpověděl;"
                    + " další pokus se nekoná");
        }
        long id = IDS.incrementAndGet();
        Wait w = new Wait(host, kind != null ? kind : kindOf(uri), System.nanoTime());
        ACTIVE.put(id, w);
        try {
            return call.call();
        } catch (IOException | RuntimeException e) {
            if (remember && w.elapsedMs() >= SLOW_FAILURE_MS && isNoAnswer(e)) {
                UNREACHABLE.add(key);
            }
            throw e;
        } finally {
            ACTIVE.remove(id);
        }
    }

    /** Nejdéle běžící žádost, pokud běží déle než {@code minMs}. */
    public static Optional<Wait> longestWait(long minMs) {
        return ACTIVE.values().stream()
                .filter(w -> w.elapsedMs() >= minMs)
                .max(Comparator.comparingLong(Wait::elapsedMs));
    }

    /** Servery, které se v tomto běhu přeskakují. */
    public static Set<String> unreachable() {
        return Set.copyOf(UNREACHABLE);
    }

    public static void forgetUnreachable() {
        UNREACHABLE.clear();
    }

    /** Selhání, za kterým je mlčení serveru — ne odmítnutí ani chybová odpověď. */
    static boolean isNoAnswer(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException
                    || t instanceof NoRouteToHostException
                    || t instanceof ConnectException
                    // ConnectTimeoutException z Apache HttpClient je InterruptedIOException.
                    || (t instanceof InterruptedIOException
                        && !(t.getClass() == InterruptedIOException.class))) {
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    /** Odhad druhu žádosti z adresy, pro text ve stavovém řádku. */
    static String kindOf(URI uri) {
        String p = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (p.endsWith(".crl")) return "CRL";
        if (p.endsWith(".crt") || p.endsWith(".cer") || p.endsWith(".der") || p.endsWith(".p7c")) {
            return "certifikát vydavatele";
        }
        if (p.endsWith(".xml")) return "důvěryhodný seznam";
        return null;
    }
}
