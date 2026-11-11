package cz.digitalnikontinuita.anep.core;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Kind;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Node;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Result;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rozbor podpisu CB-AdES — podpisu COSE (RFC 9052) v binárním formátu CBOR podle
 * ETSI TS 119 152-1 — na strom pro prohlížeč kontejneru.
 *
 * <p>Od 11. 11. 2026. Aplikace CB-AdES ověřuje od 31. 10. 2026 (DSS 6.5.1, modul dss-cb-ades),
 * ale prohlížeč kontejneru soubor .cose nepoznal, takže ikona pro prohlížení obsahu se u něj
 * nenabízela vůbec. U obalujícího podpisu tak podepsaný dokument zůstal uvnitř souboru, ke
 * kterému se čtenář jinak nedostane. Naměřeno na dvou vzorcích CB-AdES B-T z DSS (oddělený
 * a obalující s PDF, oba i rozšířené na LTA).
 *
 * <p>Stavba odpovídá {@link JadesInspector}, protože CB-AdES je JAdES přeložený do CBOR: táž
 * chráněná hlavička (zde mapa s číselnými klíči), týž řetěz certifikátů a tytéž nepodepsané
 * vlastnosti, jen pod klíčem 268 ({@code uHeaders}) místo {@code etsiU}. Strom ukazuje soubor tak,
 * jak je uložen, a nic neověřuje — na to jsou záložky výsledku.
 *
 * <p>CBOR čte knihovna {@code co.nstant.in:cbor}, kterou si DSS přináší s modulem CB-AdES;
 * třídy DSS samy se nepoužívají ze stejného důvodu jako u JAdES: soubor, který DSS odmítne, je
 * právě ten, do kterého se čtenář potřebuje podívat.
 */
public final class CbadesInspector {

    private CbadesInspector() {}

    /** Stejná mez jako u JAdES; větší soubor se nerozebírá (viz {@code JadesInspector}). */
    private static final int MAX_PARSE_BYTES = 64 * 1024 * 1024;

    private static final long TAG_COSE_SIGN = 98;
    private static final long TAG_COSE_SIGN1 = 18;
    private static final long TAG_SELF_DESCRIBED = 55799;

    // Klíče chráněné hlavičky (RFC 9052, RFC 9360, ETSI TS 119 152-1).
    private static final long H_ALG = 1, H_CRIT = 2, H_CONTENT_TYPE = 3, H_KID = 4;
    private static final long H_COUNTER_SIGNATURE = 7, H_COUNTER_SIGNATURE0 = 9;
    private static final long H_COUNTER_SIGNATURE_V2 = 11, H_COUNTER_SIGNATURE0_V2 = 12;
    private static final long H_CWT_CLAIMS = 15, H_TYP = 16;
    private static final long H_X5BAG = 32, H_X5CHAIN = 33, H_X5T = 34, H_X5U = 35;
    private static final long H_SIG_D = 267, H_U_HEADERS = 268;

    // Klíče nepodepsaných vlastností v uHeaders.
    private static final long U_SIG_TST = 1, U_VAL_DATA = 2, U_ARC_TST = 3, U_REFS = 4;
    private static final long U_SIG_R_TST = 5, U_RFS_TST = 6, U_SIG_PST = 7;

    // ---------------------------------------------------------------- detection

    /**
     * Pozná podpis COSE podle prvních bajtů: značka 98 (COSE_Sign) nebo 18 (COSE_Sign1), po ní
     * pole o čtyřech prvcích, případně celé za značkou samopopisného CBOR (55799).
     *
     * <p>Jen označené tvary. RFC 9052 připouští i neoznačené pole, jenže samotné {@code 0x84}
     * by se v binárních přílohách našlo náhodou a příloha by dostala odznak podpisu CB-AdES.
     * DSS značku zapisuje vždy (vzorky 20. 10. 2026). Volající dávají i jen 16 bajtů.
     */
    static boolean looksLikeCose(byte[] b) {
        if (b == null || b.length < 3) return false;
        int i = 0;
        if (b.length >= 6 && (b[0] & 0xFF) == 0xD9 && (b[1] & 0xFF) == 0xD9 && (b[2] & 0xFF) == 0xF7) {
            i = 3;
        }
        if (i + 2 >= b.length) return false;
        int first = b[i] & 0xFF;
        if (first == 0xD8 && (b[i + 1] & 0xFF) == 0x62) return (b[i + 2] & 0xFF) == 0x84;
        if (first == 0xD2) return (b[i + 1] & 0xFF) == 0x84;
        return false;
    }

    // ---------------------------------------------------------------- entry point

    /**
     * Sestaví strom podpisu COSE. Nikdy nevyhazuje: co nejde přečíst, vrátí jako
     * {@link Result} s chybou, jak to prohlížeč čeká.
     */
    static Result inspect(byte[] bytes, String fileName) {
        Node root = new Node(fileName, "", true, null);
        Map<String, String> summary = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        summary.put("Formát", "podpis CB-AdES (COSE)");
        summary.put("Velikost souboru", ContainerInspector.formatSize(bytes.length));
        if (bytes.length > MAX_PARSE_BYTES) {
            return new Result(Kind.COSE, "podpis CB-AdES (COSE)", root, summary, warnings,
                    "soubor je větší než " + ContainerInspector.formatSize(MAX_PARSE_BYTES)
                    + " a prohlížeč ho nerozebírá");
        }
        DataItem top;
        try {
            List<DataItem> items = CborDecoder.decode(bytes);
            if (items.isEmpty()) throw new IllegalArgumentException("soubor neobsahuje data CBOR");
            top = items.get(0);
        } catch (Exception e) {
            return new Result(Kind.COSE, "podpis CB-AdES (COSE)", root, summary, warnings,
                    "data CBOR se nepodařilo přečíst (" + ContainerInspector.describe(e) + ")");
        }
        // Samopopisný CBOR je značka nad značkou; knihovna drží vnější značku nad vnitřní.
        long tag = coseTag(top);
        if (!(top instanceof Array array) || array.getDataItems().size() != 4
                || (tag != TAG_COSE_SIGN && tag != TAG_COSE_SIGN1)) {
            return new Result(Kind.COSE, "podpis CB-AdES (COSE)", root, summary, warnings,
                    "soubor nemá podobu podpisu COSE (pole o čtyřech prvcích se značkou 98 či 18)");
        }
        List<DataItem> parts = array.getDataItems();
        boolean multi = tag == TAG_COSE_SIGN;

        // Hlavičky těla. U COSE_Sign1 jsou to zároveň hlavičky jediného podpisu.
        co.nstant.in.cbor.model.Map bodyProtected = decodeMap(bytesOf(parts.get(0)));
        co.nstant.in.cbor.model.Map bodyUnprotected = parts.get(1) instanceof co.nstant.in.cbor.model.Map m ? m : null;

        List<Sig> signatures = new ArrayList<>();
        if (multi) {
            if (parts.get(3) instanceof Array list) {
                for (DataItem entry : list.getDataItems()) {
                    if (entry instanceof Array s && s.getDataItems().size() == 3) {
                        List<DataItem> p = s.getDataItems();
                        signatures.add(new Sig(bytesOf(p.get(0)),
                                p.get(1) instanceof co.nstant.in.cbor.model.Map um ? um : null,
                                bytesOf(p.get(2))));
                    } else {
                        warnings.add("Jeden ze záznamů v poli podpisů nemá podobu COSE_Signature"
                                + " (tři prvky); vynechává se.");
                    }
                }
            }
        } else {
            signatures.add(new Sig(bytesOf(parts.get(0)), bodyUnprotected, bytesOf(parts.get(3))));
        }

        co.nstant.in.cbor.model.Map firstHeader = signatures.isEmpty() ? null
                : decodeMap(signatures.get(0).protectedBytes());
        byte[] payload = addPayload(root, parts.get(2), firstHeader, bodyProtected,
                signatures.size(), warnings);

        Collection<String> properties = new LinkedHashSet<>();
        int index = 0;
        for (Sig sig : signatures) {
            addSignature(root, sig, ++index, signatures.size(), properties, warnings);
        }

        String typeLabel = payload == null ? "oddělený podpis CB-AdES (COSE)"
                                           : "podpis CB-AdES s vloženým obsahem (COSE)";
        summary.put("Formát", typeLabel);
        summary.put("Struktura", multi
                ? "COSE_Sign (značka 98) — podpisy v samostatných záznamech"
                : "COSE_Sign1 (značka 18) — jediný podpis");
        summary.put("Počet podpisů", String.valueOf(signatures.size()));
        String detachedNames = detachedNames(firstHeader == null ? null : map(firstHeader, H_SIG_D));
        summary.put("Podepsaný obsah", payload != null
                ? "vložen v podpisu (payload), " + JadesInspector.kindName(payload) + ", "
                  + ContainerInspector.formatSize(payload.length)
                : "není součástí souboru"
                  + (detachedNames == null ? "" : " — podpis odkazuje na " + detachedNames));
        if (signatures.size() == 1 && firstHeader != null) {
            String alg = algorithm(firstHeader.get(key(H_ALG)));
            if (alg != null) summary.put("Podpisový algoritmus", alg);
            String claimed = claimedSigningTime(firstHeader);
            if (claimed != null) summary.put("Deklarovaný čas podpisu", claimed);
        }
        if (!properties.isEmpty()) {
            summary.put("Nepodepsané vlastnosti", String.join(", ", properties));
        }
        if (bodyProtected != null && !bodyProtected.getKeys().isEmpty() && multi) {
            summary.put("Chráněná hlavička těla", describeMapInline(bodyProtected));
        }
        return new Result(Kind.COSE, typeLabel, root, summary, warnings, null);
    }

    /** Jeden podpis: chráněná hlavička (bajty), nechráněná hlavička, hodnota podpisu. */
    private record Sig(byte[] protectedBytes, co.nstant.in.cbor.model.Map unprotectedHeader,
                       byte[] value) {}

    // ---------------------------------------------------------------- payload

    private static byte[] addPayload(Node root, DataItem item, co.nstant.in.cbor.model.Map header,
                                     co.nstant.in.cbor.model.Map bodyHeader, int signatureCount,
                                     List<String> warnings) {
        co.nstant.in.cbor.model.Map sigD = header == null ? null : map(header, H_SIG_D);
        byte[] payload = bytesOf(item);
        if (payload == null || payload.length == 0) {
            String named = detachedNames(sigD);
            warnings.add(sigD != null
                    ? "Podpis je oddělený — podepsaná data" + (named == null ? "" : " (" + named + ")")
                      + " nejsou v tomto souboru a nelze je odsud vyjmout; soubor o nich nese jen"
                      + " otisky, podle kterých je lze ztotožnit."
                    : "Payload je prázdný (nil) — podepsaná data nejsou součástí souboru.");
            return null;
        }
        String contentType = contentType(header);
        if (contentType == null) contentType = contentType(bodyHeader);
        String name = payloadName(root.getName(), payload, contentType);
        Node node = root.add(new Node(name, name, false, "podepsaný obsah (payload)"));
        node.data(payload);
        node.put("Role", "podepsaný obsah (payload)");
        node.put("Kryto podpisem", (signatureCount == 1 ? "ano — podpisem v tomto souboru"
                : "ano — všemi podpisy v tomto souboru") + "; COSE podepisuje payload přímo");
        node.put("Platnost podpisu", "prohlížeč kontejneru ji neověřuje — tlačítkem „Ověřit "
                + "v AnEP“ necháte obsah i podpis posoudit rozborem");
        if (contentType != null) node.put("Typ obsahu dle hlavičky", contentType);
        node.put("Uložen v souboru", "přímo jako řetězec bajtů CBOR, bez kódování");
        if (sigD != null) {
            warnings.add("Hlavička obsahuje „sigD“ (popis oddělených dat) zároveň s vloženým "
                    + "obsahem; podepsaná data mohou být částečně mimo tento soubor.");
        }
        return payload;
    }

    /** Jako u JAdES: jméno dokumentu se z názvu podpisu nevymýšlí, ledaže v něm opravdu je. */
    private static String payloadName(String fileName, byte[] payload, String contentType) {
        String extension = ContainerInspector.guessExtension(payload);
        if (".bin".equals(extension) || ".zip".equals(extension)) {
            String fromType = JadesInspector.extensionFor(contentType);
            if (fromType != null) extension = fromType;
        }
        String base = fileName == null ? "" : fileName;
        String lower = base.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".cose", ".cbor", ".cbades"}) {
            if (lower.endsWith(ext)) {
                base = base.substring(0, base.length() - ext.length());
                break;
            }
        }
        return base.toLowerCase(Locale.ROOT).endsWith(extension) ? base : "dokument" + extension;
    }

    // ---------------------------------------------------------------- one signature

    private static void addSignature(Node root, Sig sig, int index, int total,
                                     Collection<String> properties, List<String> warnings) {
        String path = "podpis-" + index;
        Node node = root.add(new Node(total == 1 ? "Podpis" : "Podpis " + index, path, true,
                "podpis CB-AdES"));
        co.nstant.in.cbor.model.Map header = decodeMap(sig.protectedBytes());
        if (sig.protectedBytes() != null && sig.protectedBytes().length > 0) {
            // Přesné bajty, které podpis kryje — ne znovu zakódovaná kopie.
            Node headerNode = node.add(new Node("chranena-hlavicka.cbor",
                    path + "/chranena-hlavicka.cbor", false, "chráněná hlavička (protected)"));
            headerNode.data(sig.protectedBytes());
            headerNode.put("Role", "chráněná hlavička podpisu — část, kterou podpis kryje");
            if (header != null) describeHeader(headerNode, header);
        }
        if (header == null || header.getKeys().isEmpty()) {
            warnings.add("Podpis " + index + " nemá chráněnou hlavičku; CB-AdES ji vyžaduje.");
        }
        String signer = header == null ? null : addCertificateChain(node, header, path);
        if (signer == null && sig.unprotectedHeader() != null) {
            // RFC 9360 dovoluje x5chain i v nechráněné hlavičce.
            signer = addCertificateChain(node, sig.unprotectedHeader(), path);
        }
        if (signer != null) node.put("Podepisující", signer);
        if (header != null) {
            node.put("Podpisový algoritmus", algorithm(header.get(key(H_ALG))));
            node.put("Deklarovaný čas podpisu", claimedSigningTime(header));
            describeDetachedData(node, map(header, H_SIG_D));
        }
        if (sig.value() != null) {
            Node sigNode = node.add(new Node("hodnota-podpisu.sig", path + "/hodnota-podpisu.sig",
                    false, "hodnota podpisu"));
            sigNode.data(sig.value());
            sigNode.put("Role", "vlastní hodnota podpisu (signature)");
        }
        co.nstant.in.cbor.model.Map unprotected = sig.unprotectedHeader();
        if (unprotected != null) {
            DataItem uHeaders = unprotected.get(key(H_U_HEADERS));
            if (uHeaders instanceof Array list && !list.getDataItems().isEmpty()) {
                addUHeaders(node, list.getDataItems(), path, properties, warnings);
            }
            for (long counter : new long[]{H_COUNTER_SIGNATURE, H_COUNTER_SIGNATURE0,
                    H_COUNTER_SIGNATURE_V2, H_COUNTER_SIGNATURE0_V2}) {
                if (unprotected.get(key(counter)) != null) {
                    node.put("Protipodpis", "nese (klíč " + counter + " v nechráněné hlavičce);"
                            + " prohlížeč ho dál nerozebírá");
                    properties.add("protipodpis");
                }
            }
        }
    }

    private static void describeHeader(Node node, co.nstant.in.cbor.model.Map header) {
        for (DataItem k : header.getKeys()) {
            long label = longOf(k, Long.MIN_VALUE);
            if (label == H_X5CHAIN) continue; // vlastní prvky níže
            DataItem value = header.get(k);
            String name = headerLabel(label);
            String shown = label == Long.MIN_VALUE ? diag(k, 100) : String.valueOf(label);
            // „Popis oddělených dat (sigD, 267)“, ne „(sigD) (267)“.
            String caption = name == null ? "Parametr " + shown
                    : name.endsWith(")") ? name.substring(0, name.length() - 1) + ", " + shown + ")"
                    : name + " (" + shown + ")";
            node.put(caption, headerValue(label, value));
        }
    }

    static String headerLabel(long label) {
        if (label == H_ALG) return "Podpisový algoritmus";
        if (label == H_CRIT) return "Kritické hlavičky";
        if (label == H_CONTENT_TYPE) return "Typ podepsaného obsahu";
        if (label == H_KID) return "Identifikace podpisového certifikátu";
        if (label == H_CWT_CLAIMS) return "Údaje CWT (mj. deklarovaný čas podpisu)";
        if (label == H_TYP) return "Typ objektu COSE";
        if (label == H_X5BAG) return "Další certifikáty (x5bag)";
        if (label == H_X5T) return "Otisk podpisového certifikátu (x5t)";
        if (label == H_X5U) return "Odkaz na certifikát podepisujícího (x5u)";
        if (label == 261) return "Otisky certifikátů (x5ts)";
        if (label == 262) return "Tvrzený závazek podepisujícího (srCms)";
        if (label == 263) return "Místo podpisu (sigPl)";
        if (label == 264) return "Tvrzené vlastnosti podepisujícího (srAts)";
        if (label == 265) return "Časové razítko obsahu (adoTst)";
        if (label == 266) return "Identifikátor podpisové politiky (sigPId)";
        if (label == H_SIG_D) return "Popis oddělených dat (sigD)";
        return null;
    }

    private static String headerValue(long label, DataItem value) {
        if (label == H_ALG) return algorithm(value);
        if (label == H_CONTENT_TYPE) return contentTypeText(value);
        if (label == H_KID) {
            byte[] der = bytesOf(value);
            String described = der == null ? null : JadesInspector.describeIssuerSerial(der);
            return described != null ? described : diag(value, 2000);
        }
        if (label == H_X5T && value instanceof Array a && a.getDataItems().size() == 2) {
            String alg = hashAlgorithm(a.getDataItems().get(0));
            byte[] digest = bytesOf(a.getDataItems().get(1));
            return (alg == null ? "" : alg + ": ") + (digest == null ? diag(a, 400) : hex(digest));
        }
        if (label == H_CWT_CLAIMS && value instanceof co.nstant.in.cbor.model.Map claims) {
            StringBuilder sb = new StringBuilder();
            for (DataItem k : claims.getKeys()) {
                long claim = longOf(k, Long.MIN_VALUE);
                DataItem v = claims.get(k);
                if (sb.length() > 0) sb.append("; ");
                String when = claim == 6 || claim == 4 || claim == 5 ? epoch(v) : null;
                String claimName = switch ((int) claim) {
                    case 1 -> "vydavatel (iss)";
                    case 2 -> "předmět (sub)";
                    case 4 -> "platnost do (exp)";
                    case 5 -> "platnost od (nbf)";
                    case 6 -> "čas vydání (iat)";
                    default -> "údaj " + diag(k, 40);
                };
                sb.append(claimName).append(": ").append(when != null ? when : diag(v, 400));
            }
            return sb.toString();
        }
        if (label == H_SIG_D && value instanceof co.nstant.in.cbor.model.Map sigD) {
            String mechanism = text(sigD.get(key(1)));
            String names = detachedNames(sigD);
            return (mechanism == null ? "" : mechanismName(mechanism))
                    + (names == null ? "" : (mechanism == null ? "" : "; ") + "soubory: " + names);
        }
        if (label == H_CRIT && value instanceof Array a) {
            StringBuilder sb = new StringBuilder();
            for (DataItem d : a.getDataItems()) {
                if (sb.length() > 0) sb.append(", ");
                long l = longOf(d, Long.MIN_VALUE);
                String n = l == Long.MIN_VALUE ? null : headerLabel(l);
                sb.append(diag(d, 40)).append(n == null ? "" : " — " + n);
            }
            return sb.toString();
        }
        return diag(value, 2000);
    }

    /** @return obecné jméno podepisujícího, když ho x5chain nese */
    private static String addCertificateChain(Node parent, co.nstant.in.cbor.model.Map header,
                                              String path) {
        DataItem chain = header.get(key(H_X5CHAIN));
        if (chain == null) return null;
        // RFC 9360: jediný certifikát smí stát sám jako řetězec bajtů, víc jich je v poli.
        List<byte[]> ders = new ArrayList<>();
        if (chain instanceof Array a) {
            for (DataItem d : a.getDataItems()) {
                byte[] der = bytesOf(d);
                if (der != null) ders.add(der);
            }
        } else {
            byte[] der = bytesOf(chain);
            if (der != null) ders.add(der);
        }
        if (ders.isEmpty()) return null;
        Node folder = parent.add(new Node("Certifikáty podepisujícího (" + ders.size() + ")",
                path + "/x5chain", true, "řetěz certifikátů z hlavičky (x5chain)"));
        String signer = null;
        for (int i = 0; i < ders.size(); i++) {
            String name = JadesInspector.addCertificate(folder, ders.get(i), path + "/x5chain", i + 1,
                    i == 0 ? "certifikát podepisujícího" : "certifikát v řetězu (x5chain)");
            if (i == 0) signer = name;
        }
        return signer;
    }

    private static void describeDetachedData(Node node, co.nstant.in.cbor.model.Map sigD) {
        if (sigD == null) return;
        String mechanism = text(sigD.get(key(1)));
        if (mechanism != null) node.put("Odkaz na podepsaná data", mechanismName(mechanism));
        List<DataItem> names = items(sigD.get(key(2)));
        List<DataItem> digests = items(sigD.get(key(4)));
        String algorithm = hashAlgorithm(sigD.get(key(3)));
        if (names == null) return;
        for (int i = 0; i < names.size(); i++) {
            String name = text(names.get(i));
            byte[] digest = digests != null && i < digests.size() ? bytesOf(digests.get(i)) : null;
            node.put("Podepsaná data — " + (name == null ? diag(names.get(i), 200) : name),
                    digest == null ? "otisk neuveden"
                            : (algorithm == null ? "" : algorithm + ": ") + hex(digest));
        }
    }

    static String detachedNames(co.nstant.in.cbor.model.Map sigD) {
        List<DataItem> names = sigD == null ? null : items(sigD.get(key(2)));
        if (names == null || names.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (DataItem name : names) {
            if (sb.length() > 0) sb.append(", ");
            String t = text(name);
            sb.append(t == null ? diag(name, 200) : t);
        }
        return sb.toString();
    }

    static String mechanismName(String mId) {
        return switch (mId) {
            case "http://uri.etsi.org/19152/ObjectIdByURIHash" ->
                    "otiskem obsahu odkazovaných souborů (ObjectIdByURIHash)";
            case "http://uri.etsi.org/19152/ObjectIdByURI" ->
                    "obsahem odkazovaných souborů (ObjectIdByURI)";
            default -> mId;
        };
    }

    // ---------------------------------------------------------------- uHeaders

    private static void addUHeaders(Node parent, List<DataItem> entries, String path,
                                    Collection<String> properties, List<String> warnings) {
        Node folder = parent.add(new Node("Nepodepsané vlastnosti (" + entries.size() + ")",
                path + "/uHeaders", true, "uHeaders — vlastnosti doplněné po podepsání"));
        int order = 0;
        for (DataItem entry : entries) {
            order++;
            // Každá vlastnost je mapa o jedné položce, uložená zakódovaná v řetězci bajtů
            // (tak ji zapisuje DSS); přímo vložená mapa se čte také.
            byte[] raw = bytesOf(entry);
            co.nstant.in.cbor.model.Map component = raw != null ? decodeMap(raw)
                    : entry instanceof co.nstant.in.cbor.model.Map m ? m : null;
            if (component == null || component.getKeys().isEmpty()) {
                warnings.add("Položku č. " + order + " v „uHeaders“ se nepodařilo přečíst.");
                continue;
            }
            DataItem k = component.getKeys().iterator().next();
            long id = longOf(k, Long.MIN_VALUE);
            DataItem body = component.get(k);
            String label = uHeaderLabel(id);
            if (label != null) properties.add(label.toLowerCase(Locale.ROOT));
            boolean opens = id == U_SIG_TST || id == U_ARC_TST || id == U_SIG_R_TST
                    || id == U_RFS_TST || id == U_VAL_DATA;
            String shown = id == Long.MIN_VALUE ? diag(k, 40) : String.valueOf(id);
            String name = order + ". " + (label == null ? "vlastnost " + shown : label);
            String childPath = path + "/uHeaders/" + order;
            Node node = folder.add(new Node(opens ? name : name + ".cbor", childPath, opens,
                    label == null ? "vlastnost, kterou tento výpis nezná" : null));
            if (raw != null) node.data(raw);
            node.put("Pořadí v uHeaders", order + ". z " + entries.size());
            node.put("Klíč", shown + (label == null ? "" : " — " + uHeaderKeyName(id)));
            if (id == U_ARC_TST) {
                node.put("Co razítko kryje", "podpis a vlastnosti uvedené v „uHeaders“ před ním,"
                        + " tedy prvních " + (order - 1) + " z " + entries.size());
            }
            if (!opens) node.put("Obsah", diag(body, 2000));
            if (id == U_VAL_DATA) {
                addValidationData(node, body, childPath);
            } else if (opens) {
                addTimestamps(node, body, childPath, label.toLowerCase(Locale.ROOT));
            }
        }
    }

    static String uHeaderLabel(long id) {
        if (id == U_SIG_TST) return "Časové razítko podpisu";
        if (id == U_VAL_DATA) return "Ověřovací údaje";
        if (id == U_ARC_TST) return "Archivní časové razítko";
        if (id == U_REFS) return "Odkazy na ověřovací údaje";
        if (id == U_SIG_R_TST) return "Časové razítko nad podpisem a odkazy";
        if (id == U_RFS_TST) return "Časové razítko nad odkazy";
        if (id == U_SIG_PST) return "Uložená podpisová politika";
        return null;
    }

    private static String uHeaderKeyName(long id) {
        if (id == U_SIG_TST) return "sigTst";
        if (id == U_VAL_DATA) return "valData";
        if (id == U_ARC_TST) return "arcTst";
        if (id == U_REFS) return "refs";
        if (id == U_SIG_R_TST) return "sigRTst";
        if (id == U_RFS_TST) return "rfsTst";
        if (id == U_SIG_PST) return "sigPSt";
        return "";
    }

    /** {@code {1: [ {1: token, 2: typ, …}, … ]}} — kontejner razítek. */
    private static void addTimestamps(Node parent, DataItem body, String path, String role) {
        if (!(body instanceof co.nstant.in.cbor.model.Map container)) return;
        List<DataItem> tokens = items(container.get(key(1)));
        if (tokens == null) return;
        int i = 0;
        for (DataItem entry : tokens) {
            i++;
            byte[] der = entry instanceof co.nstant.in.cbor.model.Map m ? bytesOf(m.get(key(1)))
                    : bytesOf(entry);
            if (der == null) continue;
            String name = tokens.size() == 1 ? "razitko.tst" : "razitko-" + i + ".tst";
            Node node = parent.add(new Node(name, path + "/" + name, false, role));
            node.data(der);
            node.put("Role", role);
            ContainerInspector.describeTimestamp(node, der);
        }
    }

    /**
     * {@code {1: xVals, 2: rVals}}: certifikáty jako {@code {1: {1: der}}} (x509Cert, val),
     * revokace jako {@code {1: crlVals, 2: ocspVals}} s položkami {@code {1: der}}.
     */
    private static void addValidationData(Node parent, DataItem body, String path) {
        if (!(body instanceof co.nstant.in.cbor.model.Map data)) return;
        List<DataItem> certs = items(data.get(key(1)));
        if (certs != null) {
            int i = 0;
            for (DataItem entry : certs) {
                i++;
                if (!(entry instanceof co.nstant.in.cbor.model.Map item)) continue;
                co.nstant.in.cbor.model.Map value = map(item, 1);
                boolean other = value == null;
                if (other) value = map(item, 2);
                byte[] der = value == null ? null : bytesOf(value.get(key(1)));
                if (der == null) continue;
                JadesInspector.addCertificate(parent, der, path, i,
                        other ? "certifikát jiného typu (otherCert)" : "certifikát pro ověření");
            }
        }
        co.nstant.in.cbor.model.Map revocations = map(data, 2);
        if (revocations != null) {
            int i = 0;
            for (DataItem entry : orEmpty(items(revocations.get(key(1))))) {
                i++;
                byte[] der = pkiValue(entry);
                if (der != null) JadesInspector.addCrl(parent, der, path, i);
            }
            i = 0;
            for (DataItem entry : orEmpty(items(revocations.get(key(2))))) {
                i++;
                byte[] der = pkiValue(entry);
                if (der != null) JadesInspector.addOcsp(parent, der, path, i);
            }
        }
    }

    private static byte[] pkiValue(DataItem entry) {
        return entry instanceof co.nstant.in.cbor.model.Map m ? bytesOf(m.get(key(1))) : bytesOf(entry);
    }

    // ---------------------------------------------------------------- vocabulary

    /** Algoritmus COSE (registr IANA) česky; {@code null}, když hodnota chybí. */
    static String algorithm(DataItem value) {
        if (value == null) return null;
        long alg = longOf(value, Long.MIN_VALUE);
        if (alg == Long.MIN_VALUE) return diag(value, 100);
        String name = switch ((int) alg) {
            case -7 -> "ES256 — ECDSA se SHA-256";
            case -35 -> "ES384 — ECDSA se SHA-384";
            case -36 -> "ES512 — ECDSA se SHA-512";
            case -47 -> "ES256K — ECDSA se SHA-256 (křivka secp256k1)";
            case -8 -> "EdDSA";
            case -19 -> "Ed25519";
            case -53 -> "Ed448";
            case -37 -> "PS256 — RSASSA-PSS se SHA-256";
            case -38 -> "PS384 — RSASSA-PSS se SHA-384";
            case -39 -> "PS512 — RSASSA-PSS se SHA-512";
            case -257 -> "RS256 — RSASSA-PKCS1-v1_5 se SHA-256";
            case -258 -> "RS384 — RSASSA-PKCS1-v1_5 se SHA-384";
            case -259 -> "RS512 — RSASSA-PKCS1-v1_5 se SHA-512";
            case 5, 6, 7 -> "HMAC — sdílený klíč, nikoli elektronický podpis";
            default -> "algoritmus mimo běžný výčet COSE";
        };
        return name + " (" + alg + ")";
    }

    static String hashAlgorithm(DataItem value) {
        long alg = longOf(value, Long.MIN_VALUE);
        if (alg == Long.MIN_VALUE) return value == null ? null : text(value);
        return switch ((int) alg) {
            case -14 -> "SHA-1";
            case -15 -> "SHA-256/64";
            case -16 -> "SHA-256";
            case -43 -> "SHA-384";
            case -44 -> "SHA-512";
            case -17 -> "SHA-512/256";
            case -18 -> "SHAKE128";
            case -45 -> "SHAKE256";
            default -> "algoritmus " + alg;
        };
    }

    private static String contentType(co.nstant.in.cbor.model.Map header) {
        if (header == null) return null;
        DataItem value = header.get(key(H_CONTENT_TYPE));
        return value == null ? null : text(value);
    }

    private static String contentTypeText(DataItem value) {
        String t = text(value);
        if (t != null) return t;
        long n = longOf(value, Long.MIN_VALUE);
        // Číselný tvar je formát obsahu CoAP; běžné hodnoty stačí pojmenovat.
        return switch ((int) n) {
            case 0 -> "text/plain (CoAP 0)";
            case 41 -> "application/xml (CoAP 41)";
            case 42 -> "application/octet-stream (CoAP 42)";
            case 50 -> "application/json (CoAP 50)";
            case 60 -> "application/cbor (CoAP 60)";
            default -> diag(value, 100);
        };
    }

    /** Deklarovaný čas podpisu: údaj {@code iat} (6) v CWT claims (15), jak ho zapisuje DSS. */
    static String claimedSigningTime(co.nstant.in.cbor.model.Map header) {
        co.nstant.in.cbor.model.Map claims = map(header, H_CWT_CLAIMS);
        return claims == null ? null : epoch(claims.get(key(6)));
    }

    private static String epoch(DataItem value) {
        long seconds = longOf(value, Long.MIN_VALUE);
        return seconds == Long.MIN_VALUE ? null : ContainerInspector.formatDate(new Date(seconds * 1000L));
    }

    // ---------------------------------------------------------------- CBOR helpers

    private static long coseTag(DataItem item) {
        co.nstant.in.cbor.model.Tag tag = item.hasTag() ? item.getTag() : null;
        while (tag != null) {
            long v = tag.getValue();
            if (v != TAG_SELF_DESCRIBED) return v;
            tag = tag.hasTag() ? tag.getTag() : null;
        }
        return -1;
    }

    static DataItem key(long k) {
        return k >= 0 ? new UnsignedInteger(k) : new NegativeInteger(k);
    }

    static co.nstant.in.cbor.model.Map map(co.nstant.in.cbor.model.Map m, long k) {
        DataItem v = m == null ? null : m.get(key(k));
        return v instanceof co.nstant.in.cbor.model.Map r ? r : null;
    }

    static List<DataItem> items(DataItem v) {
        return v instanceof Array a ? a.getDataItems() : null;
    }

    private static List<DataItem> orEmpty(List<DataItem> v) {
        return v == null ? List.of() : v;
    }

    static byte[] bytesOf(DataItem v) {
        return v instanceof ByteString b ? b.getBytes() : null;
    }

    static String text(DataItem v) {
        return v instanceof UnicodeString s ? s.getString() : null;
    }

    static long longOf(DataItem v, long fallback) {
        if (v instanceof co.nstant.in.cbor.model.Number n) {
            BigInteger value = n.getValue();
            if (value.bitLength() < 63) return value.longValue();
        }
        return fallback;
    }

    /** Mapa zakódovaná v řetězci bajtů (chráněná hlavička, položka uHeaders), nebo {@code null}. */
    static co.nstant.in.cbor.model.Map decodeMap(byte[] raw) {
        if (raw == null || raw.length == 0) return null;
        try {
            List<DataItem> items = CborDecoder.decode(raw);
            return !items.isEmpty() && items.get(0) instanceof co.nstant.in.cbor.model.Map m ? m : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String describeMapInline(co.nstant.in.cbor.model.Map m) {
        StringBuilder sb = new StringBuilder();
        for (DataItem k : m.getKeys()) {
            if (sb.length() > 0) sb.append("; ");
            long label = longOf(k, Long.MIN_VALUE);
            String name = label == Long.MIN_VALUE ? null : headerLabel(label);
            sb.append(name == null ? diag(k, 40) : name).append(": ")
              .append(headerValue(label, m.get(k)));
        }
        return sb.toString();
    }

    /** Hodnota CBOR v diagnostickém zápisu (RFC 8949, kap. 8), zkrácená na {@code limit} znaků. */
    static String diag(DataItem value, int limit) {
        StringBuilder sb = new StringBuilder();
        diag(value, sb, limit);
        return sb.length() <= limit ? sb.toString() : sb.substring(0, limit) + "… (zkráceno)";
    }

    private static void diag(DataItem v, StringBuilder sb, int limit) {
        if (sb.length() > limit) return;
        if (v == null) { sb.append("—"); return; }
        if (v.hasTag()) sb.append(v.getTag().getValue()).append('(');
        if (v instanceof co.nstant.in.cbor.model.Map m) {
            sb.append('{');
            boolean first = true;
            for (DataItem k : m.getKeys()) {
                if (!first) sb.append(", ");
                first = false;
                diag(k, sb, limit);
                sb.append(": ");
                diag(m.get(k), sb, limit);
                if (sb.length() > limit) break;
            }
            sb.append('}');
        } else if (v instanceof Array a) {
            sb.append('[');
            boolean first = true;
            for (DataItem d : a.getDataItems()) {
                if (!first) sb.append(", ");
                first = false;
                diag(d, sb, limit);
                if (sb.length() > limit) break;
            }
            sb.append(']');
        } else if (v instanceof ByteString b) {
            byte[] bytes = b.getBytes();
            int shown = Math.min(bytes.length, 64);
            sb.append("h'");
            for (int i = 0; i < shown; i++) sb.append(String.format("%02x", bytes[i]));
            if (shown < bytes.length) sb.append("…' (").append(bytes.length).append(" B)");
            else sb.append('\'');
        } else if (v instanceof UnicodeString s) {
            sb.append('"').append(s.getString()).append('"');
        } else if (v instanceof co.nstant.in.cbor.model.Number n) {
            sb.append(n.getValue());
        } else if (v instanceof SimpleValue s) {
            sb.append(switch (s.getSimpleValueType()) {
                case TRUE -> "true";
                case FALSE -> "false";
                case NULL -> "null";
                case UNDEFINED -> "undefined";
                default -> String.valueOf(s);
            });
        } else if (v.getMajorType() == MajorType.SPECIAL) {
            sb.append(v);
        } else {
            sb.append(v);
        }
        if (v.hasTag()) sb.append(')');
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
