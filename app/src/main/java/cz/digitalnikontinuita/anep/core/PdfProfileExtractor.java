package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Jakou podobu PDF má dokument, který se ověřuje: verzi souboru a standardizované podmnožiny
 * PDF, ke kterým se hlásí (PDF/A, PDF/UA, PDF/X, PDF/E, PDF/VT).
 *
 * <p><b>Verze</b> je podle ISO 32000 na dvou místech. V hlavičce souboru stojí
 * {@code %PDF-1.7} nebo {@code %PDF-2.0}; od PDF 1.4 smí katalog dokumentu nést ještě klíč
 * {@code /Version} a ten hlavičku <b>přebíjí, je-li pozdější</b> (ISO 32000-1, 7.5.5 —
 * inkrementální aktualizace nemůže přepsat začátek souboru, takže povýšení verze se zapisuje
 * sem). Přesně tak se ptá i {@link PDDocument#getVersion()}, takže se používá on. PDF 1.7 je
 * ISO 32000-1, PDF 2.0 je ISO 32000-2.
 *
 * <p><b>Podtyp</b> se nepozná z obsahu, ale z XMP metadat v katalogu ({@code /Metadata}): každá
 * z ISO podmnožin PDF tam má vlastní identifikační schéma s vlastním jmenným prostorem.
 * U PDF/A je to {@code pdfaid} ({@code part}, {@code conformance}, u části 4 povinně
 * {@code rev}), u PDF/UA {@code pdfuaid}, u zbylých jediný textový klíč s hotovým názvem verze.
 *
 * <p><b>Je to prohlášení dokumentu o sobě samém, ne ověřený fakt.</b> PDF Association to říká
 * naplno: přítomnost identifikačních metadat není zárukou shody se standardem — tu dá až
 * validátor, který projde celou sadu kontrol dané části ISO. AnEP takový validátor není a nemá
 * jím být; čte prohlášení a takto je i vypisuje. Rovněž platí opačný směr: soubor, který
 * všechny podmínky PDF/A splňuje, ale nemá XMP záznam, se za PDF/A nepovažuje, takže tady
 * nepřítomnost údaje znamená „nehlásí se", ne „nesplňuje".
 *
 * <p>Hodnoty mimo to, co ISO definuje (neznámá část, neznámá úroveň shody, jiný rok vydání),
 * se nezahazují ani neopravují — vypíšou se, jak stojí, a profil se označí příznakem
 * {@link Profile#unexpected()}. Tak to doporučuje PDF Association pro soubory „z budoucnosti":
 * nová datovaná vydání ISO 19005-4 a ISO 15930-6 jsou ohlášena a mlčky je vydávat za dnešní
 * znění by čtenáře uvedlo v omyl.
 */
public final class PdfProfileExtractor {

    /** Jmenné prostory identifikačních schémat. Prefixy v souboru na ničem nezáleží. */
    private static final String NS_PDFA   = "http://www.aiim.org/pdfa/ns/id/";
    private static final String NS_PDFUA  = "http://www.aiim.org/pdfua/ns/id/";
    private static final String NS_PDFX   = "http://www.npes.org/pdfx/ns/id/";
    private static final String NS_PDFE   = "http://www.aiim.org/pdfe/ns/id/";
    private static final String NS_PDFVT  = "http://www.npes.org/pdfvt/ns/id/";

    private PdfProfileExtractor() {}

    /**
     * Co dokument o své podobě prozrazuje.
     *
     * @param version    „1.7", „2.0" — verze podle hlavičky, případně povýšená katalogem;
     *                   {@code null}, když ji nešlo přečíst
     * @param standards  názvy podmnožin, ke kterým se dokument hlásí („PDF/A-2b", „PDF/UA-1"),
     *                   v pořadí, ve kterém se hledají; prázdný seznam u obyčejného PDF
     * @param unexpected aspoň jeden identifikační údaj je mimo hodnoty, které dnešní ISO
     *                   publikace definují
     * @param pdfaFlavour  jednoznačné označení části a úrovně PDF/A („2b", „4f"), proti kterému
     *                   se dá shoda ověřit — nebo {@code null}, hlásí-li se dokument jinam než
     *                   k PDF/A, nebo je-li deklarace natolik nezvyklá, že se neví, které znění
     *                   normy by se na ni mělo použít
     */
    public record Profile(String version, List<String> standards, boolean unexpected,
                          String pdfaFlavour) {
        public Profile {
            standards = standards == null ? List.of() : List.copyOf(standards);
        }
        /** Nic se nepodařilo zjistit — pak není o čem psát. */
        public boolean isEmpty() {
            return version == null && standards.isEmpty();
        }
    }

    /**
     * Otevře PDF a přečte jeho verzi a deklarované podmnožiny.
     *
     * @param source   ověřovaný dokument; nad jiným obsahem než PDF vrací {@code null}
     * @param password heslo k otevření, nebo {@code null}
     * @return profil dokumentu, nebo {@code null}, nejde-li o PDF nebo nejde-li otevřít
     */
    public static Profile extract(PdfSource source, char[] password) {
        if (source == null || !source.isPdf()) return null;
        try (PDDocument doc = source.open(password)) {
            String version = formatVersion(doc.getVersion());
            List<String> standards = new ArrayList<>();
            boolean[] unexpected = { false };
            String[] flavour = { null };
            Map<String, String> xmp = readIdentificationFields(doc);
            if (!xmp.isEmpty()) {
                addPdfA(xmp, standards, unexpected, flavour);
                addPdfUa(xmp, standards, unexpected);
                addVerbatim(xmp, NS_PDFX,  "GTS_PDFXVersion",  standards, unexpected);
                addVerbatim(xmp, NS_PDFE,  "ISO_PDFEVersion",  standards, unexpected);
                addVerbatim(xmp, NS_PDFVT, "GTS_PDFVTVersion", standards, unexpected);
            }
            Profile profile = new Profile(version, standards, unexpected[0], flavour[0]);
            return profile.isEmpty() ? null : profile;
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code 1.7f} → „1.7". Verze PDF mají vždy jedno desetinné místo. */
    private static String formatVersion(float version) {
        if (version <= 0) return null;
        return String.format(Locale.ROOT, "%.1f", version);
    }

    /**
     * PDF/A: {@code pdfaid:part} 1–4 a úroveň shody. Části 1–3 ji mají povinně (A, B, a od
     * části 2 i U), část 4 ji má nepovinnou a v jiném významu — prázdná znamená obyčejné
     * PDF/A-4, {@code E} a {@code F} jsou jeho varianty pro technickou dokumentaci a pro
     * dokumenty s přílohami. Část 4 navíc povinně nese rok vydání ISO ({@code rev}), zatím 2020.
     */
    private static void addPdfA(Map<String, String> xmp, List<String> out, boolean[] unexpected,
                                 String[] flavour) {
        String rawPart = value(xmp, NS_PDFA, "part");
        if (rawPart == null) return;
        int part = partNumber(rawPart);
        if (part < 0) {
            // Ne každý zápis v tom poli je číslo části. V korpusu je soubor, kterému producent
            // do obou polí zapsal názvy vlastních tříd („iText.Kernel.Pdf.PdfAConformanceLevel")
            // — z takového údaje se podtyp určit nedá a vysázet ho do záhlaví by bylo horší než
            // nenapsat nic.
            unexpected[0] = true;
            return;
        }

        String conformance = conformanceLetter(value(xmp, NS_PDFA, "conformance"));
        String rev = value(xmp, NS_PDFA, "rev");

        String id;
        switch (part) {
            case 1, 2, 3 -> {
                String allowed = part == 1 ? "AB" : "ABU";
                if (conformance == null || allowed.indexOf(conformance.charAt(0)) < 0) {
                    // Úroveň shody je u částí 1–3 povinná; chybí-li nebo je nečekaná, neuhodne
                    // se — dokument se prostě hlásí k té části a víc se o něm neví.
                    unexpected[0] = true;
                    id = "PDF/A-" + part;
                } else {
                    id = "PDF/A-" + part + conformance.toLowerCase(Locale.ROOT);
                    // Část i úroveň jsou jednoznačné, takže se shoda dá ověřit. Nezvyklý rok
                    // vydání tomu nebrání: u částí 1–3 žádná datovaná vydání nejsou, takže
                    // znění normy, proti kterému se má měřit, je jen jedno.
                    flavour[0] = part + conformance.toLowerCase(Locale.ROOT);
                }
                if (rev != null) unexpected[0] = true; // pro části 1–3 rev definován není
            }
            case 4 -> {
                String level;
                if (conformance == null) {
                    level = "";
                } else if ("E".equals(conformance) || "F".equals(conformance)) {
                    level = conformance.toLowerCase(Locale.ROOT);
                } else {
                    unexpected[0] = true;
                    level = null;
                }
                id = "PDF/A-4" + (level == null ? "" : level);
                if ("2020".equals(rev)) {
                    // Proti známému znění se shoda ověřit dá — pokud je jednoznačná i úroveň.
                    if (level != null) flavour[0] = "4" + level;
                } else {
                    // Rok vydání je u části 4 povinný. Jiný než 2020 znamená datované vydání,
                    // které tahle verze AnEP nezná — ať je vidět, o které jde. Shoda se proti
                    // němu neověřuje: měřilo by se zněním z roku 2020, tedy jiným, než ke
                    // kterému se dokument hlásí, a nálezy by o něm nic nevypovídaly.
                    unexpected[0] = true;
                    if (rev != null) id = id + " (" + rev + ")";
                }
            }
            default -> {
                unexpected[0] = true;
                id = "PDF/A-" + part;
            }
        }
        out.add(id + amendment(value(xmp, NS_PDFA, "amd")));
    }

    /**
     * PDF/UA: {@code pdfuaid:part} 1 nebo 2. Část 2 (ISO 14289:2024) navíc povinně nese rok
     * vydání, část 1 ho nemá definovaný a místo něj může mít číslo dodatku.
     */
    private static void addPdfUa(Map<String, String> xmp, List<String> out, boolean[] unexpected) {
        String rawPart = value(xmp, NS_PDFUA, "part");
        if (rawPart == null) return;
        int part = partNumber(rawPart);
        if (part < 0) {
            unexpected[0] = true;
            return;
        }

        String id = "PDF/UA-" + part;
        String amd = value(xmp, NS_PDFUA, "amd");
        String rev = value(xmp, NS_PDFUA, "rev");
        switch (part) {
            case 1 -> {
                if (rev != null) unexpected[0] = true;
                id = id + amendment(amd);
            }
            case 2 -> {
                if (amd != null) unexpected[0] = true; // u části 2 dodatky definované nejsou
                if (!"2024".equals(rev)) {
                    unexpected[0] = true;
                    if (rev != null) id = id + " (" + rev + ")";
                }
            }
            default -> unexpected[0] = true;
        }
        out.add(id);
    }

    /**
     * PDF/X, PDF/E a PDF/VT nemají číslo části zvlášť — jejich jediný identifikační klíč nese
     * rovnou hotový název verze („PDF/X-4", „PDF/E-1", „PDF/VT-2"), takže se přebírá, jak je.
     * Jen se ověří, že to opravdu je název podmnožiny a ne cokoli jiného, co tam producent
     * zapsal: do záhlaví jde text ze souboru a nemá odtamtud jít nic, co se za název nedá vzít.
     */
    private static void addVerbatim(Map<String, String> xmp, String ns, String key,
                                    List<String> out, boolean[] unexpected) {
        String value = value(xmp, ns, key);
        if (value == null) return;
        if (!value.startsWith("PDF/") || value.length() > 20) {
            unexpected[0] = true;
            return;
        }
        out.add(value);
    }

    /** Číslo části, nebo {@code -1}, není-li v tom poli malé kladné číslo. */
    private static int partNumber(String raw) {
        if (raw.length() > 2) return -1;
        try {
            int part = Integer.parseInt(raw);
            return part > 0 ? part : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Úroveň shody velkým písmenem, nebo {@code null}, není-li to jediné písmeno. */
    private static String conformanceLetter(String raw) {
        if (raw == null || raw.length() != 1 || !Character.isLetter(raw.charAt(0))) return null;
        return raw.toUpperCase(Locale.ROOT);
    }

    /**
     * Dodatek k identifikátoru, je-li zapsaný v definovaném tvaru „číslo dodatku, DVOJTEČKA,
     * čtyřmístný rok". Jinak prázdný řetězec.
     *
     * <p>Mimo ten tvar se do pole zapisuje ledacos — 22 souborů z 2 046 měřených má
     * {@code pdfaid:amd} rovno holému roku vydání základní normy („2005" u PDF/A-1, „2010"
     * u PDF/A-2). Je to zvyk některých producentů, ne dodatek, a hlavně to nic nemění na tom,
     * ke které části a úrovni se dokument hlásí. Do záhlaví tedy nepatří a za nečekaný údaj
     * hodný upozornění se to taky nebere — u pětiny deklarací by se z upozornění stal šum.
     */
    private static String amendment(String raw) {
        return raw != null && raw.matches("\\d{1,2}:\\d{4}") ? " Amd " + raw : "";
    }

    private static String value(Map<String, String> xmp, String ns, String key) {
        String v = xmp.get(ns + key);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * Vytáhne z XMP všechny hodnoty z identifikačních jmenných prostorů, klíčované
     * „jmenný prostor + název".
     *
     * <p>XMP je RDF/XML a tentýž údaj se smí zapsat dvěma způsoby: jako atribut na
     * {@code rdf:Description}, nebo jako jeho podřízený prvek. Obojí se v souborech běžně
     * vyskytuje, tak se čte obojí. Prefixy ({@code pdfaid:}, …) jsou v RDF libovolné, rozhoduje
     * jmenný prostor — proto se parsuje s ohledem na jmenné prostory a ne textově.
     */
    private static Map<String, String> readIdentificationFields(PDDocument doc) {
        Map<String, String> found = new HashMap<>();
        PDMetadata metadata = doc.getDocumentCatalog().getMetadata();
        if (metadata == null) return found;
        try (InputStream in = metadata.exportXMPMetadata()) {
            byte[] xmp = in.readAllBytes();
            if (xmp.length == 0) return found;

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // XMP je cizí obsah z ověřovaného souboru — žádné externí entity ani DTD.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            org.w3c.dom.Document dom = builder.parse(new ByteArrayInputStream(xmp));
            walk(dom.getDocumentElement(), found);
        } catch (Exception ignored) {
            // Poškozená nebo nečitelná metadata nejsou důvod, aby ověřování cokoli hlásilo —
            // dokument se pak prostě k ničemu nehlásí.
        }
        return found;
    }

    private static void walk(Node node, Map<String, String> found) {
        if (node == null || node.getNodeType() != Node.ELEMENT_NODE) return;
        Element element = (Element) node;

        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            collect(attribute.getNamespaceURI(), attribute.getLocalName(),
                    attribute.getNodeValue(), found);
        }
        collect(element.getNamespaceURI(), element.getLocalName(),
                element.getTextContent(), found);

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            walk(children.item(i), found);
        }
    }

    private static void collect(String ns, String name, String value, Map<String, String> found) {
        if (ns == null || name == null || value == null) return;
        if (!NS_PDFA.equals(ns) && !NS_PDFUA.equals(ns) && !NS_PDFX.equals(ns)
                && !NS_PDFE.equals(ns) && !NS_PDFVT.equals(ns)) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return;
        // První nález vyhrává: atributová podoba se čte dřív než prvková a smysl mají tytéž.
        found.putIfAbsent(ns + name, trimmed);
    }
}
