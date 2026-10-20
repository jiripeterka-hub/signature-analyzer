package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.job.DownloadInfoRecord;
import eu.europa.esig.dss.model.tsl.TLParsingInfoRecord;
import eu.europa.esig.dss.model.tsl.TLInfo;
import eu.europa.esig.dss.model.job.ValidationInfoRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Počet důvěryhodných seznamů neříká, kolik jich do stavu důvěry opravdu přispělo. Seznam, který
 * se nestáhne, si svůj záznam podrží a počítá se dál — a tichý výpadek se pak projeví až tím, že
 * se kvalifikovaná pečeť ohlásí jako pouze zaručená.
 */
class TrustStateCoverageTest {

    @Test
    @DisplayName("Země bez jediného certifikátu se vypíše jménem")
    void emptyTerritoryIsNamed() {
        List<TLInfo> tls = List.of(tl("PT", "https://pt/tsl"), tl("CZ", "https://cz/tsl"));
        Map<String, Integer> counts = Map.of("https://cz/tsl", 530);
        assertEquals(List.of("PT"), TrustConfig.emptyTerritories(tls, counts));
    }

    @Test
    @DisplayName("Když přispěly všechny, nepíše se nic")
    void completeStateSaysNothing() {
        List<TLInfo> tls = List.of(tl("PT", "https://pt/tsl"), tl("CZ", "https://cz/tsl"));
        Map<String, Integer> counts = Map.of("https://pt/tsl", 105, "https://cz/tsl", 530);
        assertEquals(List.of(), TrustConfig.emptyTerritories(tls, counts));
    }

    @Test
    @DisplayName("Vypadne-li zemí víc, vyjmenují se všechny")
    void severalEmptyTerritories() {
        List<TLInfo> tls = List.of(tl("PT", "https://pt/tsl"), tl("IE", "https://ie/tsl"),
                tl("CZ", "https://cz/tsl"));
        Map<String, Integer> counts = Map.of("https://cz/tsl", 530);
        assertEquals(List.of("PT", "IE"), TrustConfig.emptyTerritories(tls, counts));
    }

    @Test
    @DisplayName("Bez známého území se uvede aspoň adresa seznamu")
    void unknownTerritoryFallsBackToUrl() {
        List<TLInfo> tls = List.of(tl("", "https://kdesi/tsl"));
        assertEquals(List.of("https://kdesi/tsl"),
                TrustConfig.emptyTerritories(tls, Map.of()));
    }

    @Test
    @DisplayName("Zástupný záznam se nepočítá — není to země")
    void stubIsIgnored() {
        assertEquals(List.of(), TrustConfig.emptyTerritories(
                List.of(tl("", "cache://stub")), Map.of()));
    }

    /**
     * TLInfo, z něhož test čte jedinou věc — území. Ostatní metody těch tří rozhraní jsou
     * odbyté zástupcem: DSS jich má přes třicet a přibývají mezi verzemi, takže je vypisovat
     * ručně znamená test, který spadne při každé aktualizaci knihovny, aniž by o ní cokoli tvrdil.
     */
    private static TLInfo tl(String territory, String url) {
        Object rec = java.lang.reflect.Proxy.newProxyInstance(
                TrustStateCoverageTest.class.getClassLoader(),
                new Class<?>[] { DownloadInfoRecord.class, TLParsingInfoRecord.class,
                                 ValidationInfoRecord.class },
                (proxy, method, args) -> {
                    if ("getTerritory".equals(method.getName())) return territory;
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == List.class) return List.of();
                    return null;
                });
        return new TLInfo((DownloadInfoRecord) rec, (TLParsingInfoRecord) rec,
                (ValidationInfoRecord) rec, url);
    }
}
