package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Co strom v prohlížeči kontejnerů říká o jednotlivých položkách.
 *
 * <p>Nález uživatele z 15. 9. 2026 nad {@code T kvalifikovany_Uchovano_do_03_08_2034.asice}:
 * validační data uložená jako samostatné položky mají strojová, na pohled nesrozumitelná jména
 * ({@code SCIsSnProducedAt!…!…!20201111203255Z.ors}) a strom je zařazoval do koše „metadata
 * kontejneru“ — čtenář tedy neměl jak poznat odpověď OCSP od certifikátu. Právě u takových
 * položek je popisek to jediné, co je vysvětlí.
 *
 * <p>Jména samotná zůstávají, jak jsou. Nejsou zkomolená: jsou to skutečná jména položek v ZIPu.
 */
class ContainerRoleTest {

    /** Kontejner s položkami pojmenovanými přesně jako v uživatelově příkladu. */
    private static byte[] container() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "mimetype", "application/vnd.etsi.asic-e+zip");
            put(zip, "dokument.pdf", "%PDF-1.7 ...");
            put(zip, "META-INF/signature.p7s", "x");
            put(zip, "META-INF/ASiCManifest.xml", "<x/>");
            put(zip, "META-INF/ASiCArchiveManifest.xml", "<x/>");
            put(zip, "META-INF/timestamp002.tst", "x");
            put(zip, "META-INF/arch_timestamp_2021-12-20_103015860_efc9.tst", "x");
            put(zip, "META-INF/SCIsSnProducedAt!d7omj!ALNGmA!20201111203255Z.ors", "x");
            put(zip, "META-INF/SuKeNBefore!d7omj!dIIIke!20160211121711Z.cer", "x");
            put(zip, "META-INF/neco.crl", "x");
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String body) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Čitelná jména podle cesty. */
    private static Map<String, String> names() throws IOException {
        Map<String, String> out = new HashMap<>();
        names(ContainerInspector.inspect(container(), "vzorek.asice").root(), out);
        return out;
    }

    private static void names(ContainerInspector.Node n, Map<String, String> out) {
        if (n == null) return;
        if (!n.isDirectory() && n.getPath() != null) out.put(n.getPath(), n.getDisplayName());
        for (ContainerInspector.Node child : n.getChildren()) names(child, out);
    }

    /** Role podle cesty, vytažené ze stromu, který postaví {@link ContainerInspector}. */
    private static Map<String, String> roles() throws IOException {
        Map<String, String> out = new HashMap<>();
        collect(ContainerInspector.inspect(container(), "vzorek.asice").root(), out);
        return out;
    }

    private static void collect(ContainerInspector.Node n, Map<String, String> out) {
        if (n == null) return;
        if (!n.isDirectory() && n.getPath() != null) {
            out.put(n.getPath(), n.getInfo().get("Role"));
        }
        for (ContainerInspector.Node child : n.getChildren()) collect(child, out);
    }

    @Test
    @DisplayName("validační data se pojmenují podle toho, co jsou")
    void validationDataNamed() throws Exception {
        Map<String, String> r = roles();
        assertEquals("odpověď OCSP (validační data)",
                r.get("META-INF/SCIsSnProducedAt!d7omj!ALNGmA!20201111203255Z.ors"));
        assertEquals("certifikát (validační data)",
                r.get("META-INF/SuKeNBefore!d7omj!dIIIke!20160211121711Z.cer"));
        assertEquals("seznam CRL (validační data)", r.get("META-INF/neco.crl"));
    }

    @Test
    @DisplayName("razítko se pozná podle přípony, ne podle začátku jména")
    void timestampByExtension() throws Exception {
        // ETSI říká timestamp001.tst, kontejnery z praxe se tím neřídí.
        Map<String, String> r = roles();
        assertEquals("časové razítko", r.get("META-INF/timestamp002.tst"));
        assertEquals("časové razítko",
                r.get("META-INF/arch_timestamp_2021-12-20_103015860_efc9.tst"));
    }

    @Test
    @DisplayName("razítko, které se nepodařilo přečíst, to na kartě řekne")
    void unreadableTimestampSaysSo() throws Exception {
        // Položky v přípravku nesou jediné písmeno, takže token z nich nevyjde. Karta v takovém
        // případě nesmí mlčet: prázdná karta u prvku, který se jmenuje „časové razítko", vypadá
        // jako porucha prohlížeče, kdežto poznámka řekne, že vadné jsou bajty, a co s nimi jde
        // dělat dál. Tři řádky, kvůli kterým se token čte, u něj chybět mají — vymýšlet se
        // nemají odkud.
        Map<String, Map<String, String>> info = details();
        Map<String, String> stamp = info.get("META-INF/timestamp002.tst");
        assertTrue(stamp.getOrDefault("Poznámka", "").startsWith("obsah razítka se nepodařilo"),
                "poznámka o nečitelném razítku: " + stamp);
        assertFalse(stamp.containsKey("Čas dle razítka"));
        assertFalse(stamp.containsKey("Vydavatel razítka"));
        assertFalse(stamp.containsKey("Certifikát razítka platí do"));
    }

    /** Údaje karty podle cesty — celá mapa, ne jen role. */
    private static Map<String, Map<String, String>> details() throws IOException {
        Map<String, Map<String, String>> out = new HashMap<>();
        detail(ContainerInspector.inspect(container(), "vzorek.asice").root(), out);
        return out;
    }

    private static void detail(ContainerInspector.Node n, Map<String, Map<String, String>> out) {
        if (n == null) return;
        if (!n.isDirectory() && n.getPath() != null) out.put(n.getPath(), n.getInfo());
        for (ContainerInspector.Node child : n.getChildren()) detail(child, out);
    }

    @Test
    @DisplayName("známé položky ASiC si popisek drží")
    void knownMembersUnchanged() throws Exception {
        Map<String, String> r = roles();
        assertEquals("deklarace typu kontejneru", r.get("mimetype"));
        assertEquals("podpis CAdES", r.get("META-INF/signature.p7s"));
        assertEquals("manifest ASiC (které soubory podpis kryje)",
                r.get("META-INF/ASiCManifest.xml"));
        assertEquals("archivní manifest ASiC", r.get("META-INF/ASiCArchiveManifest.xml"));
        assertEquals("podepisovaný obsah", r.get("dokument.pdf"));
    }

    @Test
    @DisplayName("strojové jméno ustoupí čitelnému, skutečné zůstává v getName()")
    void machineNamesReplaced() throws Exception {
        Map<String, String> n = names();
        assertEquals("odpoved-ocsp-1.ors",
                n.get("META-INF/SCIsSnProducedAt!d7omj!ALNGmA!20201111203255Z.ors"));
        assertEquals("certifikat-1.cer",
                n.get("META-INF/SuKeNBefore!d7omj!dIIIke!20160211121711Z.cer"));
    }

    @Test
    @DisplayName("poctivé jméno se nenahrazuje")
    void plainNamesKept() throws Exception {
        // Kontejner s vlastním cert.cer si ho nechá — nahrazuje se jen tam, kde je čemu.
        Map<String, String> n = names();
        assertEquals("neco.crl", n.get("META-INF/neco.crl"));
        assertEquals("signature.p7s", n.get("META-INF/signature.p7s"));
        assertEquals("dokument.pdf", n.get("dokument.pdf"));
    }
}
