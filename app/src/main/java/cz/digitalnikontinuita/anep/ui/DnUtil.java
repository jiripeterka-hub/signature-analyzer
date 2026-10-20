package cz.digitalnikontinuita.anep.ui;

import java.nio.charset.StandardCharsets;
import cz.digitalnikontinuita.anep.core.IdentityDocument;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Utility methods for extracting individual attributes from a raw RFC 2253 DN string
 * as produced by DSS / BouncyCastle (e.g. from CertificateWrapper.getCertificateDN()).
 */
class DnUtil {

    /** Returns the first value matching the given OID or short name, or null. */
    static String dnField(String rawDn, String oid, String shortName) {
        List<String> all = dnFieldAll(rawDn, oid, shortName);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Returns all values matching the given OID or short name.
     * Splits only on commas that are NOT preceded by a backslash (RFC 2253 escape).
     */
    static List<String> dnFieldAll(String rawDn, String oid, String shortName) {
        if (rawDn == null) return List.of();
        List<String> result = new ArrayList<>();
        // Split on comma not preceded by backslash; negative lookbehind (?<!\\) in Java = (?<!\\\\) in string
        for (String part : rawDn.split("(?<!\\\\),")) {
            part = part.trim();
            String v = extractAttr(part, oid);
            if (v == null && shortName != null) v = extractAttr(part, shortName);
            if (v != null && !v.isBlank()) result.add(v);
        }
        return result;
    }

    /**
     * Returns the non-numeric OU value, or the first OU if all are numeric, or null.
     * Czech qualified certificates often encode the CA registration number as OU=00000001.
     */
    static String bestOu(String rawDn) {
        List<String> all = dnFieldAll(rawDn, "2.5.4.11", "OU");
        for (String v : all) {
            if (!v.matches("\\d+")) return v;
        }
        return all.isEmpty() ? null : all.get(0);
    }

    private static String extractAttr(String rdn, String key) {
        String hexPrefix = key + "=#";
        String valPrefix = key + "=";
        if (rdn.startsWith(hexPrefix)) {
            return decodeAsn1Hex(rdn.substring(hexPrefix.length()).trim());
        }
        if (rdn.startsWith(valPrefix)) {
            String raw = rdn.substring(valPrefix.length()).trim();
            // Unescape RFC 2253 backslash sequences (\, \\ \+ \= etc.)
            return raw.replace("\\,", ",").replace("\\\\", "\\")
                      .replace("\\+", "+").replace("\\=", "=")
                      .replace("\\\"", "\"").replace("\\<", "<")
                      .replace("\\>", ">").replace("\\;", ";")
                      .replace("\\#", "#");
        }
        return null;
    }

    /**
     * Returns the formatted value of OID 2.5.4.97 (organizationIdentifier).
     * "NTRCZ-" prefix is stripped — only the number following it is returned.
     */
    static String orgId(String rawDn) {
        String raw = dnField(rawDn, "2.5.4.97", "OrgId");
        if (raw == null || raw.isBlank()) return null;
        if (raw.startsWith("NTRCZ-")) return raw.substring("NTRCZ-".length());
        return raw;
    }

    /**
     * {label, value} pro každou položku serialNumber (OID 2.5.4.5) předmětu, v pořadí DN;
     * prázdný seznam, když tam žádná není.
     *
     * <p><b>Položek může být víc.</b> Kvalifikovaný certifikát I.CA z roku 2026 nese dvě:
     * číslo cestovního pasu ({@code PASCZ-…}) a vedle něj klientské číslo vydavatele
     * ({@code ICA - …}). V pořadí RFC 2253, ve kterém DN předává DSS, stojí klientské číslo
     * první — dokud se četla jen první hodnota, doklad se v takovém certifikátu nenašel.
     * Nález uživatele 8. 10. 2026.
     */
    static List<String[]> serialNumberEntries(String rawDn) {
        List<String[]> out = new ArrayList<>();
        for (String raw : subjectSerialNumbers(rawDn)) out.add(serialNumberEntryOf(raw));
        return out;
    }

    /**
     * {label, value} pro jednu hodnotu serialNumber: "IDCCZ-" → "Číslo OP:", "PASCZ-" →
     * "Číslo pasu:", jinak popisek, který ji pojmenuje jako identifikátor držitele.
     */
    static String[] serialNumberEntryOf(String raw) {
        IdentityDocument.Document doc = IdentityDocument.of(raw);
        if (doc != null) return new String[]{doc.shortLabel() + ":", doc.number()};
        // Jiné schéma než české IDCCZ-/PASCZ- se nepřejmenovává: co za identifikátor to je,
        // říká jeho vlastní předpona a hádat za ni by znamenalo vydávat dohad za zjištění.
        // Popisek ale musí odlišit tenhle údaj od sériového čísla samotného certifikátu, vedle
        // kterého v bloku o certifikátu stojí.
        return new String[]{"Identifikátor držitele (serialNumber):", raw == null ? "" : raw.trim()};
    }

    /**
     * The holder's identity documents as a phrase for running text ("číslo občanského průkazu
     * 123456789"), or {@code null} when the certificate carries no such reference.
     *
     * <p>Projdou se všechny položky serialNumber, ne jen první — viz {@link #serialNumberEntries}.
     * Nese-li certifikát dokladů víc, vyjmenují se všechny („… a číslo cestovního pasu …“);
     * věty, do kterých se fráze vkládá, mluví o „údajích“ v množném čísle.
     *
     * <p>Která schémata se poznají a proč jen ta dvě, stojí u {@link IdentityDocument}.
     */
    static String identityDocument(String rawDn) {
        List<String> phrases = new ArrayList<>();
        for (String raw : subjectSerialNumbers(rawDn)) {
            IdentityDocument.Document doc = IdentityDocument.of(raw);
            if (doc != null) phrases.add(doc.phrase() + " " + doc.number());
        }
        if (phrases.isEmpty()) return null;
        if (phrases.size() == 1) return phrases.get(0);
        return String.join(", ", phrases.subList(0, phrases.size() - 1))
                + " a " + phrases.get(phrases.size() - 1);
    }

    /**
     * Všechny hodnoty OID 2.5.4.5 v pořadí DN. DSS je dává jako {@code 2.5.4.5=#hex},
     * BouncyCastle jako {@code SERIALNUMBER=}; {@link #matches} pokryje oboje i bez ohledu
     * na velikost písmen.
     */
    static List<String> subjectSerialNumbers(String rawDn) {
        List<String> out = new ArrayList<>();
        for (Attr a : attributes(rawDn)) {
            if (isSubjectSerialNumber(a.key())) out.add(a.value());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // The DN as a whole
    // ------------------------------------------------------------------

    /** One attribute of a DN: the key exactly as DSS spells it, and the decoded value. */
    record Attr(String key, String value) { }

    /**
     * Every attribute of {@code rawDn}, in the order the certificate carries them.
     *
     * <p>DSS hands out the DN in RFC 2253 form, which means short names for the handful the JDK
     * knows — {@code CN}, {@code C}, {@code L}, {@code ST}, {@code O}, {@code OU}, {@code STREET},
     * {@code DC}, {@code UID} — and a numeric OID with a DER-hex value for everything else.
     * Measured over 1 199 corpus files (939 signatures): surname, givenName, serialNumber, title,
     * businessCategory, postalCode, dnQualifier, pseudonym, organizationIdentifier, emailAddress
     * and one EV jurisdiction attribute all arrive in the hex form, so decoding it is not an edge
     * case but the normal path for anything past the first five.
     */
    static List<Attr> attributes(String rawDn) {
        if (rawDn == null || rawDn.isBlank()) return List.of();
        List<Attr> out = new ArrayList<>();
        for (String part : rawDn.split("(?<!\\\\),")) {
            part = part.trim();
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).trim();
            String value = extractAttr(part, key);
            if (value == null || value.isBlank()) continue;
            out.add(new Attr(key, value));
        }
        return out;
    }

    /** True for the three attributes the signer block leaves out; see {@link #attributes}. */
    static boolean isGivenNameOrSurname(String key) {
        return matches(key, "2.5.4.42", "GIVENNAME", "G")
                || matches(key, "2.5.4.4", "SURNAME", "SN");
    }

    /** True for the subject serialNumber, which the signer block leaves out. */
    static boolean isSubjectSerialNumber(String key) {
        return matches(key, "2.5.4.5", "SERIALNUMBER", "serialNumber");
    }

    /** True for the common name, whose label depends on what kind of element is being described. */
    static boolean isCommonName(String key) {
        return matches(key, "2.5.4.3", "CN", null);
    }

    private static boolean matches(String key, String oid, String shortName, String alt) {
        if (key == null) return false;
        String k = key.trim();
        if (k.equalsIgnoreCase(oid) || k.equalsIgnoreCase("OID." + oid)) return true;
        if (shortName != null && k.equalsIgnoreCase(shortName)) return true;
        return alt != null && k.equalsIgnoreCase(alt);
    }

    /**
     * The Czech label for a DN attribute, or the key itself when the attribute is not one this
     * application has a name for.
     *
     * <p>Falling back to the raw OID is deliberate. The block promises every item of the DN, and
     * an attribute shown under its number still tells the reader it is there and what it says;
     * dropping it because the label is missing would quietly break that promise.
     */
    static String label(String key) {
        if (key == null) return "";
        String k = key.trim();
        if (k.regionMatches(true, 0, "OID.", 0, 4)) k = k.substring(4);
        return switch (k.toUpperCase(java.util.Locale.ROOT)) {
            case "2.5.4.6", "C"                       -> "Stát";
            case "2.5.4.7", "L"                       -> "Lokalita";
            case "2.5.4.8", "ST"                      -> "Kraj, provincie";
            case "2.5.4.9", "STREET"                  -> "Ulice";
            case "2.5.4.10", "O"                      -> "Organizace";
            case "2.5.4.11", "OU"                     -> "Organizační jednotka";
            case "2.5.4.12", "T", "TITLE"             -> "Titul";
            case "2.5.4.13"                           -> "Popis";
            case "2.5.4.15"                           -> "Kategorie subjektu";
            case "2.5.4.16"                           -> "Poštovní adresa";
            case "2.5.4.17"                           -> "PSČ";
            case "2.5.4.20"                           -> "Telefon";
            case "2.5.4.43"                           -> "Iniciály";
            case "2.5.4.44"                           -> "Rozlišující dodatek jména";
            case "2.5.4.45"                           -> "Jedinečný identifikátor";
            case "2.5.4.46"                           -> "Rozlišovací doplněk (dnQualifier)";
            case "2.5.4.65"                           -> "Pseudonym";
            case "2.5.4.72"                           -> "Role";
            case "1.2.840.113549.1.9.1", "E", "EMAILADDRESS" -> "E-mailová adresa";
            case "1.2.840.113549.1.9.2"               -> "Nestrukturované jméno";
            case "0.9.2342.19200300.100.1.1", "UID"   -> "UID";
            case "0.9.2342.19200300.100.1.25", "DC"   -> "Doménová složka";
            case "1.3.6.1.4.1.311.60.2.1.1"           -> "Sídlo podle rejstříku — obec";
            case "1.3.6.1.4.1.311.60.2.1.2"           -> "Sídlo podle rejstříku — region";
            case "1.3.6.1.4.1.311.60.2.1.3"           -> "Sídlo podle rejstříku — stát";
            default -> k;
        };
    }

    /**
     * Kam položka patří ve výpisu — menší číslo výš.
     *
     * <p>Pořadí, ve kterém položky leží v certifikátu, se pro čtení nehodí: RFC 2253 je vypisuje
     * odzadu a jsou certifikáty, kde jméno držitele vyjde až za zemí. Blok proto řadí sám, od
     * toho, kdo to je, přes organizaci, kterou zastupuje, k adrese a kontaktům. Položky, které
     * aplikace nezná, jdou nakonec — nevědět, co znamenají, není důvod je vynechat, ale je to
     * důvod nestavět je před ty, o kterých se ví.
     */
    static int displayRank(String key) {
        if (key == null) return 500;
        String k = key.trim();
        if (k.regionMatches(true, 0, "OID.", 0, 4)) k = k.substring(4);
        return switch (k.toUpperCase(java.util.Locale.ROOT)) {
            case "2.5.4.3", "CN"                      -> 0;
            case "2.5.4.12", "T", "TITLE"             -> 10;
            case "2.5.4.65"                           -> 15;
            case "2.5.4.43"                           -> 20;
            case "2.5.4.44"                           -> 21;
            case "2.5.4.72"                           -> 25;
            case "2.5.4.10", "O"                      -> 30;
            case "2.5.4.11", "OU"                     -> 35;
            case "2.5.4.97", "ORGID", "ORGANIZATIONIDENTIFIER" -> 40;
            case "2.5.4.15"                           -> 45;
            case "2.5.4.9", "STREET"                  -> 50;
            case "2.5.4.16"                           -> 51;
            case "2.5.4.17"                           -> 52;
            case "2.5.4.7", "L"                       -> 55;
            case "2.5.4.8", "ST"                      -> 60;
            case "2.5.4.6", "C"                       -> 65;
            case "1.3.6.1.4.1.311.60.2.1.1"           -> 70;
            case "1.3.6.1.4.1.311.60.2.1.2"           -> 71;
            case "1.3.6.1.4.1.311.60.2.1.3"           -> 72;
            case "1.2.840.113549.1.9.1", "E", "EMAILADDRESS" -> 80;
            case "2.5.4.20"                           -> 81;
            case "0.9.2342.19200300.100.1.1", "UID"   -> 85;
            case "0.9.2342.19200300.100.1.25", "DC"   -> 86;
            case "1.2.840.113549.1.9.2"               -> 88;
            case "2.5.4.13"                           -> 90;
            case "2.5.4.45"                           -> 91;
            case "2.5.4.46"                           -> 92;
            default -> 500;
        };
    }

    /**
     * The organizationIdentifier as it should be shown: {@code NTRCZ-} names the Czech company
     * register, so what follows it is the IČO and is labelled as such. Any other scheme keeps its
     * value whole, because renaming an identifier this application does not recognise would be a
     * guess printed as a fact.
     */
    static String[] organizationIdentifierEntry(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.startsWith("NTRCZ-")) {
            return new String[]{ "IČO organizace", value.substring("NTRCZ-".length()) };
        }
        return new String[]{ "Identifikátor organizace", value };
    }

    /** True for the DN's own emailAddress attribute. */
    static boolean isEmail(String key) {
        return matches(key, "1.2.840.113549.1.9.1", "E", "EMAILADDRESS");
    }

    static boolean isOrganizationIdentifier(String key) {
        return matches(key, "2.5.4.97", "OrgId", "organizationIdentifier");
    }

    /** Decode an ASN.1 DER-encoded primitive from a hex string (tag + length + value). */
    static String decodeAsn1Hex(String hex) {
        try {
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++)
                bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            int tag = bytes[0] & 0xFF;
            int lenByte = bytes[1] & 0xFF;
            int valueOffset, valueLen;
            if (lenByte < 0x80) {
                valueOffset = 2;
                valueLen = lenByte;
            } else {
                int numBytes = lenByte & 0x7F;
                valueLen = 0;
                for (int i = 0; i < numBytes; i++)
                    valueLen = (valueLen << 8) | (bytes[2 + i] & 0xFF);
                valueOffset = 2 + numBytes;
            }
            byte[] value = Arrays.copyOfRange(bytes, valueOffset, valueOffset + valueLen);
            return switch (tag) {
                case 0x0C -> new String(value, StandardCharsets.UTF_8);
                case 0x1E -> new String(value, StandardCharsets.UTF_16BE);
                case 0x16 -> new String(value, StandardCharsets.US_ASCII);
                default   -> new String(value, StandardCharsets.ISO_8859_1);
            };
        } catch (Exception e) {
            return hex;
        }
    }
}
