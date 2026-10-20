package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Check;
import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Link;
import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Záznam o kontrolách odkazu z eLegalizace — most mezi oknem, které kontroluje, a kartou,
 * která o tom má říct.
 *
 * <p>Řetěz se neukládá, počítá se z jednotlivých kroků, a právě to je na něm křehké: krok je
 * zapsaný pod dokumentem, ve kterém odkaz stál, takže druhý článek se najde jen tehdy, když se
 * jde přes předložený soubor dál. Dva soubory odkazující na sebe navzájem by přitom procházku
 * nesměly zacyklit.
 */
class LegalisationLinkLogTest {

    private static final Link LINK = new Link("legalisation-metadata.xml", "guid-1", "ORIGINAL",
            "dokument.pdf", List.of(new LegalisationLinkInspector.Digest("SHA-256", "abcd")));

    private static final Check MATCH = new Check(Outcome.MATCH, List.of(), null);

    @BeforeEach
    void clean() {
        LegalisationLinkLog.clear();
    }

    @Test
    @DisplayName("Bez kontroly je řetěz prázdný")
    void emptyWithoutAnyCheck(@TempDir Path dir) throws Exception {
        File source = soubor(dir, "zdroj.pdf");
        assertTrue(LegalisationLinkLog.chain(source).isEmpty());
    }

    @Test
    @DisplayName("Krok nese velikost a čas změny z okamžiku kontroly")
    void stepCarriesTheFileFacts(@TempDir Path dir) throws Exception {
        File source = soubor(dir, "zdroj.pdf");
        File candidate = soubor(dir, "odkazovany.pdf", "dvanáct bajtu");

        LegalisationLinkLog.record(source, LINK, candidate, MATCH, null);

        List<LegalisationLinkLog.Step> chain = LegalisationLinkLog.chain(source);
        assertEquals(1, chain.size());
        LegalisationLinkLog.Step step = chain.get(0);
        assertEquals(candidate.getName(), step.candidate().getName());
        assertEquals(candidate.length(), step.candidateSize());
        assertNotNull(step.candidateModified());
        assertEquals(Outcome.MATCH, step.check().outcome());
    }

    @Test
    @DisplayName("Řetěz jde přes předložený dokument dál")
    void chainFollowsTheCheckedDocument(@TempDir Path dir) throws Exception {
        File a = soubor(dir, "a.pdf");
        File b = soubor(dir, "b.pdf");
        File c = soubor(dir, "c.pdf");

        LegalisationLinkLog.record(a, LINK, b, MATCH, LINK);   // b nese vlastní odkaz
        LegalisationLinkLog.record(b, LINK, c, MATCH, null);

        List<LegalisationLinkLog.Step> chain = LegalisationLinkLog.chain(a);
        assertEquals(2, chain.size());
        assertEquals("b.pdf", chain.get(0).candidate().getName());
        assertEquals("c.pdf", chain.get(1).candidate().getName());
        assertNotNull(chain.get(0).candidateLink(), "První článek má vlastní odkaz.");
    }

    @Test
    @DisplayName("Opakovaná kontrola téhož dokumentu přepíše tu předchozí")
    void secondCheckReplacesTheFirst(@TempDir Path dir) throws Exception {
        File a = soubor(dir, "a.pdf");
        File prvni = soubor(dir, "prvni.pdf");
        File druhy = soubor(dir, "druhy.pdf");

        LegalisationLinkLog.record(a, LINK, prvni, new Check(Outcome.MISMATCH, List.of(), null), null);
        LegalisationLinkLog.record(a, LINK, druhy, MATCH, null);

        List<LegalisationLinkLog.Step> chain = LegalisationLinkLog.chain(a);
        assertEquals(1, chain.size(), "Dvě odpovědi na tutéž otázku by si odporovaly: " + chain);
        assertEquals("druhy.pdf", chain.get(0).candidate().getName());
    }

    @Test
    @DisplayName("Kruh v odkazech procházku zastaví")
    void aCycleDoesNotLoopForever(@TempDir Path dir) throws Exception {
        File a = soubor(dir, "a.pdf");
        File b = soubor(dir, "b.pdf");

        LegalisationLinkLog.record(a, LINK, b, MATCH, LINK);
        LegalisationLinkLog.record(b, LINK, a, MATCH, LINK);

        List<LegalisationLinkLog.Step> chain = LegalisationLinkLog.chain(a);
        assertEquals(2, chain.size(), "Po návratu k výchozímu souboru se má procházka zastavit.");
    }

    private static File soubor(Path dir, String name) throws Exception {
        return soubor(dir, name, name);
    }

    private static File soubor(Path dir, String name, String obsah) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, obsah);
        return p.toFile();
    }
}
