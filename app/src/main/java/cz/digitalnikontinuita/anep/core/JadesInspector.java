package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.ContainerInspector.Kind;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Node;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Result;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.IssuerSerial;
import org.bouncycastle.cert.X509CRLHolder;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.jose4j.json.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Presents a JAdES signature — a JSON file built on JWS (RFC 7515, ETSI TS 119 182-1) — as a
 * browsable tree.
 *
 * <p>Until now the container browser showed such a file as one element: "dokument JSON", 160 kB
 * of text. Everything a reader comes for was inside it, base64-encoded and unreachable — above
 * all the signed document itself, which an <em>enveloping</em> JAdES carries in its
 * {@code payload} member and which no other part of the application hands out either. This class
 * takes the file apart: the payload as a document with its own bytes, the protected header read
 * out parameter by parameter, the signer's certificates, and every unsigned property in
 * {@code etsiU} — time-stamps, certificates and revocation data — each as an element that can be
 * previewed, hashed, saved or verified in its own right.
 *
 * <p>Like the rest of {@link ContainerInspector} this reads the file as it is stored, not as DSS
 * sees it: components DSS would reject are still shown, in the order they really appear (which
 * in JAdES carries meaning — an archive time-stamp covers what precedes it). Nothing here says
 * whether a signature is valid; that is what the validation tabs are for.
 *
 * <p>The JSON is read with jose4j and the DER with Bouncy Castle, both format libraries rather
 * than validators — the same footing on which {@code ContainerInspector} parses CMS and XML.
 * DSS's own JAdES classes are deliberately not used: they answer what a signature <em>is</em>,
 * and a file they refuse to accept is exactly the one whose insides a reader needs to see.
 */
public final class JadesInspector {

    private JadesInspector() {}

    /**
     * Above this the file is not taken apart. Parsing means holding the text, the parsed tree and
     * the decoded payload at once, so a JAdES enveloping a large video would cost several times
     * its own size on the thread that draws the window. Such a file still opens — as text, by the
     * plain-JSON path — which is the same outcome as before this class existed.
     */
    private static final int MAX_PARSE_BYTES = 64 * 1024 * 1024;

    /**
     * Builds the tree for a JWS/JAdES document.
     *
     * @return the result, or {@code null} when the bytes are not a JWS at all — an ordinary JSON
     *         document, which the caller then presents as one
     */
    static Result inspect(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PARSE_BYTES) return null;
        Map<String, Object> top;
        try {
            top = JsonUtil.parseJson(jsonText(bytes));
        } catch (Exception e) {
            return null;
        }

        // JWS has three serialisations. The compact one is not JSON and never reaches here; the
        // other two differ only in whether the single signature keeps its own object or is
        // flattened into the top level, so they are unfolded into one list and treated alike.
        List<Map<?, ?>> signatures = new ArrayList<>();
        String serialization;
        List<?> sigList = list(top, "signatures");
        // A member called "signatures" is not enough: any JSON document may have one. RFC 7515
        // makes "signature" mandatory inside each entry, so that is what decides — otherwise an
        // ordinary configuration file would be presented to the reader as a signature.
        if (sigList != null && holdsSignature(sigList)) {
            serialization = "obecná (JSON Serialization)";
            for (Object o : sigList) {
                if (o instanceof Map<?, ?> m) signatures.add(m);
            }
        } else if (top.get("signature") instanceof String) {
            serialization = "zploštělá (Flattened JSON Serialization)";
            signatures.add(top);
        } else {
            return null;
        }

        List<String> warnings = new ArrayList<>();
        Node root = new Node(fileName, "", true, null);

        Map<?, ?> firstHeader = signatures.isEmpty() ? null : protectedHeader(signatures.get(0));
        byte[] payload = addPayload(root, top, signatures, firstHeader, warnings);

        // A set, because two signatures of the same level carry the same properties and the
        // summary is a list of what the file holds, not a tally.
        Collection<String> properties = new LinkedHashSet<>();
        int index = 0;
        for (Map<?, ?> signature : signatures) {
            addSignature(root, signature, ++index, signatures.size(), properties, warnings);
        }

        boolean detached = payload == null;
        String typeLabel = detached ? "oddělený podpis JAdES (JSON)"
                                    : "podpis JAdES s vloženým obsahem (JSON)";

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Formát", typeLabel);
        summary.put("Serializace", serialization);
        summary.put("Velikost souboru", ContainerInspector.formatSize(bytes.length));
        summary.put("Počet podpisů", String.valueOf(signatures.size()));
        String detachedNames = detachedNames(firstHeader == null ? null : map(firstHeader, "sigD"));
        summary.put("Podepsaný obsah", payload != null
                ? "vložen v podpisu (payload), " + kindName(payload) + ", "
                  + ContainerInspector.formatSize(payload.length)
                : "není součástí souboru"
                  + (detachedNames == null ? "" : " — podpis odkazuje na " + detachedNames));
        // With more than one signature these two belong to a particular signature, not to the
        // file, and a summary that picked the first one would quietly speak for all of them.
        if (signatures.size() == 1 && firstHeader != null) {
            String alg = str(firstHeader, "alg");
            if (alg != null) summary.put("Podpisový algoritmus", alg + " — " + algName(alg));
            String claimed = claimedSigningTime(firstHeader);
            if (claimed != null) summary.put("Deklarovaný čas podpisu", claimed);
        }
        if (!properties.isEmpty()) {
            summary.put("Nepodepsané vlastnosti", String.join(", ", properties));
        }

        return new Result(Kind.JSON, typeLabel, root, summary, warnings, null);
    }

    // ---------------------------------------------------------------- payload

    /**
     * Adds the signed document, which is what a reader of an enveloping JAdES came for — so it is
     * the first element of the tree and the one the window opens on.
     *
     * @return the payload bytes, or {@code null} when the signature is detached
     */
    private static byte[] addPayload(Node root, Map<String, Object> top, List<Map<?, ?>> signatures,
                                     Map<?, ?> firstHeader, List<String> warnings) {
        String encoded = str(top, "payload");
        Map<?, ?> sigD = firstHeader == null ? null : map(firstHeader, "sigD");
        if (encoded == null || encoded.isEmpty()) {
            String named = detachedNames(sigD);
            warnings.add(sigD != null
                    ? "Podpis je oddělený — podepsaná data" + (named == null ? "" : " (" + named + ")")
                      + " nejsou v tomto souboru a nelze je odsud vyjmout; soubor o nich nese jen"
                      + " otisky, podle kterých je lze ztotožnit."
                    : "Soubor neobsahuje člen „payload“ — podepsaná data nejsou jeho součástí.");
            return null;
        }

        // RFC 7797: with b64 = false the payload member holds the content itself rather than its
        // base64url form, and the octets signed are the octets of that string.
        boolean encodedBase64 = payloadIsBase64(signatures);
        byte[] payload = encodedBase64 ? decodeBase64(encoded)
                                       : encoded.getBytes(StandardCharsets.UTF_8);
        if (payload == null || payload.length == 0) {
            warnings.add("Člen „payload“ se nepodařilo dekódovat; podepsaný obsah proto nelze "
                    + "vyjmout.");
            return null;
        }

        String name = payloadName(root.getName(), payload, firstHeader);
        Node node = root.add(new Node(name, name, false, "podepsaný obsah (payload)"));
        node.data(payload);
        // Krátce: role se vypisuje i do řádku stromu vedle názvu, kde na delší větu není místo.
        node.put("Role", "podepsaný obsah (payload)");
        // The wording is deliberately narrower than the ✔ an ASiC entry gets: there the browser
        // recomputes the digest a manifest declares, here nothing is recomputed. JAdES signs the
        // payload directly, so *what* is covered is certain; whether the signature over it holds
        // is a question for the validation tabs.
        node.put("Kryto podpisem", (signatures.size() == 1 ? "ano — podpisem v tomto souboru"
                : "ano — všemi podpisy v tomto souboru") + "; JAdES podepisuje payload přímo");
        node.put("Platnost podpisu", "prohlížeč kontejneru ji neověřuje — tlačítkem „Ověřit "
                + "v AnEP“ necháte obsah i podpis posoudit rozborem");
        String cty = str(firstHeader, "cty");
        if (cty != null) node.put("Typ obsahu dle hlavičky (cty)", cty + " — " + mimeType(cty));
        node.put("Uložen v souboru", encodedBase64
                ? "zakódovaný v base64url (výchozí stav dle RFC 7515)"
                : "přímo, bez kódování (b64 = false dle RFC 7797)");
        if (sigD != null) {
            warnings.add("Hlavička obsahuje „sigD“ (popis oddělených dat) zároveň s vloženým "
                    + "obsahem; podepsaná data mohou být částečně mimo tento soubor.");
        }
        return payload;
    }

    /**
     * Names the extracted document.
     *
     * <p>Deliberately <b>not</b> after the signature file. A JAdES is named independently of what
     * it carries, so „JAdES B-LTA kvalifikovany enveloping PS.json“ would hand the reader a PDF
     * called „JAdES B-LTA kvalifikovany enveloping PS.pdf“ — a name that document never had. The
     * file itself says nothing about the name (only a detached signature states one, in
     * {@code sigD}), so a neutral one is used instead of an invented one.
     *
     * <p>The exception is the habit CMS made familiar: appending the signature's extension to the
     * document's own name. In „smlouva.pdf.json“ the name really is there to be read, and taking
     * the extension off gives it back.
     */
    private static String payloadName(String fileName, byte[] payload, Map<?, ?> header) {
        String extension = payloadExtension(payload, header);
        String base = fileName == null ? "" : fileName;
        String lower = base.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".json", ".jades", ".jws", ".sig"}) {
            if (lower.endsWith(ext)) {
                base = base.substring(0, base.length() - ext.length());
                break;
            }
        }
        return base.toLowerCase(Locale.ROOT).endsWith(extension) ? base : "dokument" + extension;
    }

    private static String payloadExtension(byte[] payload, Map<?, ?> header) {
        String extension = ContainerInspector.guessExtension(payload);
        // The sniffed type decides, because it is evidence; cty is a claim, and only fills in
        // when the bytes say nothing — an .odt payload sniffs as a plain ZIP.
        if (".bin".equals(extension) || ".zip".equals(extension)) {
            String fromCty = extensionFor(str(header, "cty"));
            if (fromCty != null) extension = fromCty;
        }
        return extension;
    }

    /** {@code b64}, which the signature may set in its protected header; absent means true. */
    private static boolean payloadIsBase64(List<Map<?, ?>> signatures) {
        for (Map<?, ?> signature : signatures) {
            Map<?, ?> header = protectedHeader(signature);
            if (header != null && Boolean.FALSE.equals(header.get("b64"))) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- one signature

    private static void addSignature(Node root, Map<?, ?> signature, int index, int total,
                                     Collection<String> properties, List<String> warnings) {
        String path = "podpis-" + index;
        Node node = root.add(new Node(total == 1 ? "Podpis" : "Podpis " + index, path, true,
                "podpis JAdES"));

        String encodedHeader = str(signature, "protected");
        Map<?, ?> header = protectedHeader(signature);
        if (encodedHeader != null) {
            byte[] raw = decodeBase64(encodedHeader);
            if (raw != null) {
                // The exact decoded octets, not a re-formatted copy: what the signature covers is
                // the base64url string above, so only these bytes hash to anything meaningful.
                Node headerNode = node.add(new Node("chranena-hlavicka.json",
                        path + "/chranena-hlavicka.json", false, "chráněná hlavička (protected)"));
                headerNode.data(raw);
                headerNode.put("Role", "chráněná hlavička podpisu — část, kterou podpis kryje");
                if (header != null) describeHeader(headerNode, header);
            }
        } else {
            warnings.add("Podpis " + index + " nemá chráněnou hlavičku („protected“); to JAdES "
                    + "nepřipouští.");
        }
        // The folder itself answers the questions a reader asks before opening anything: who
        // signed, with what, and when they say they did.
        String signer = header == null ? null : addCertificateChain(node, header, path);
        if (signer != null) node.put("Podepisující", signer);
        if (header != null) {
            String alg = str(header, "alg");
            if (alg != null) node.put("Podpisový algoritmus", alg + " — " + algName(alg));
            node.put("Deklarovaný čas podpisu", claimedSigningTime(header));
            describeDetachedData(node, map(header, "sigD"));
        }

        byte[] value = decodeBase64(str(signature, "signature"));
        if (value != null) {
            Node sigNode = node.add(new Node("hodnota-podpisu.sig", path + "/hodnota-podpisu.sig",
                    false, "hodnota podpisu"));
            sigNode.data(value);
            sigNode.put("Role", "vlastní hodnota podpisu (signature)");
        }

        Map<?, ?> unprotected = map(signature, "header");
        List<?> etsiU = unprotected == null ? null : list(unprotected, "etsiU");
        if (etsiU != null && !etsiU.isEmpty()) {
            addEtsiU(node, etsiU, path, properties, warnings);
        }
    }

    /**
     * Spells out {@code sigD} — which files the signature covers and by what digest.
     *
     * <p>For a detached signature this is the whole question: the document is elsewhere, and the
     * only way to tell whether the file in hand is the one signed is to compare its digest with
     * the value stated here. Left as raw JSON that comparison is not something anybody would do.
     */
    private static void describeDetachedData(Node node, Map<?, ?> sigD) {
        if (sigD == null) return;
        String mechanism = str(sigD, "mId");
        if (mechanism != null) node.put("Odkaz na podepsaná data", mechanismName(mechanism));
        List<?> names = list(sigD, "pars");
        List<?> digests = list(sigD, "hashV");
        String algorithm = digestShorthand(str(sigD, "hashM"));
        if (names == null) return;
        for (int i = 0; i < names.size(); i++) {
            String name = String.valueOf(names.get(i));
            byte[] digest = digests != null && i < digests.size() && digests.get(i) instanceof String s
                    ? decodeBase64(s) : null;
            node.put("Podepsaná data — " + name, digest == null
                    ? "otisk neuveden"
                    : (algorithm == null ? "" : algorithm + ": ") + hex(digest));
        }
    }

    /** The files a detached signature names, for a sentence that can say which they are. */
    static String detachedNames(Map<?, ?> sigD) {
        List<?> names = sigD == null ? null : list(sigD, "pars");
        if (names == null || names.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Object name : names) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(name);
        }
        return sb.toString();
    }

    static String mechanismName(String mId) {
        return switch (mId) {
            case "http://uri.etsi.org/19182/ObjectIdByURIHash" ->
                    "otiskem obsahu odkazovaných souborů (ObjectIdByURIHash)";
            case "http://uri.etsi.org/19182/ObjectIdByURI" ->
                    "obsahem odkazovaných souborů (ObjectIdByURI)";
            case "http://uri.etsi.org/19182/HttpHeaders" ->
                    "hlavičkami požadavku HTTP (HttpHeaders)";
            default -> mId;
        };
    }

    /** Reads the protected header out parameter by parameter, so the detail pane says something. */
    private static void describeHeader(Node node, Map<?, ?> header) {
        for (Map.Entry<?, ?> entry : header.entrySet()) {
            String key = String.valueOf(entry.getKey());
            // The chain gets elements of its own below; repeating three certificates as one
            // unreadable line of base64 would only bury everything else in this pane.
            if ("x5c".equals(key)) continue;
            String label = headerLabel(key);
            node.put(label == null ? key : label + " (" + key + ")",
                    headerValue(key, entry.getValue()));
        }
    }

    /** @return the signer's common name, when {@code x5c} names one */
    private static String addCertificateChain(Node parent, Map<?, ?> header, String path) {
        List<?> x5c = list(header, "x5c");
        if (x5c == null || x5c.isEmpty()) return null;
        Node folder = parent.add(new Node("Certifikáty podepisujícího (" + x5c.size() + ")",
                path + "/x5c", true, "řetěz certifikátů z hlavičky (x5c)"));
        String signer = null;
        int i = 0;
        for (Object entry : x5c) {
            i++;
            byte[] der = entry instanceof String s ? decodeBase64(s) : null;
            if (der == null) continue;
            // RFC 7515: the first certificate of x5c is the signer's, the rest are the chain to
            // an anchor — worth saying, because the reader wants to know which one signed.
            String name = addCertificate(folder, der, path + "/x5c", i,
                    i == 1 ? "certifikát podepisujícího" : "certifikát v řetězu (x5c)");
            if (i == 1) signer = name;
        }
        return signer;
    }

    // ---------------------------------------------------------------- etsiU

    private static void addEtsiU(Node parent, List<?> etsiU, String path,
                                 Collection<String> properties, List<String> warnings) {
        Node folder = parent.add(new Node("Nepodepsané vlastnosti (" + etsiU.size() + ")",
                path + "/etsiU", true, "etsiU — vlastnosti doplněné po podepsání"));
        int order = 0;
        for (Object entry : etsiU) {
            order++;
            // Components are base64url strings, unless the signature carries them unencoded —
            // both forms are legal and both appear in the wild, so both are read.
            byte[] raw = entry instanceof String s ? decodeBase64(s) : null;
            Map<?, ?> component = null;
            if (raw != null) {
                try {
                    component = JsonUtil.parseJson(new String(raw, StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    // Not JSON after decoding — kept below as the bytes it is.
                }
            } else if (entry instanceof Map<?, ?> m) {
                component = m;
            }
            if (component == null || component.isEmpty()) {
                warnings.add("Položku č. " + order + " v „etsiU“ se nepodařilo přečíst.");
                continue;
            }
            String key = String.valueOf(component.keySet().iterator().next());
            Object body = component.get(key);
            String label = etsiULabel(key);
            if (label != null) properties.add(label.toLowerCase(Locale.ROOT));

            String name = order + ". " + (label == null ? key : label + " (" + key + ")");
            String childPath = path + "/etsiU/" + order + "-" + ContainerInspector.safeFileName(key);
            boolean opens = opensIntoElements(key);
            // The name already says what the property is, so a role repeating it would only make
            // the row longer. A property this list does not know says so instead.
            Node node = folder.add(new Node(opens ? name : name + ".json", childPath, opens,
                    label == null ? "vlastnost, kterou tento výpis nezná" : null));
            if (raw != null) node.data(raw);
            node.put("Pořadí v etsiU", order + ". z " + etsiU.size());
            node.put("Klíč", key);
            // The order is not decoration: an archive time-stamp covers the components before it,
            // which is why one added later goes at the end of the list.
            if ("arcTst".equals(key)) {
                node.put("Co razítko kryje", "podpis a vlastnosti uvedené v „etsiU“ před ním,"
                        + " tedy prvních " + (order - 1) + " z " + etsiU.size());
            }
            if (!opens) node.put("Obsah", compactJson(body, 2000));
            addComponentBody(node, key, body, childPath);
        }
    }

    /** Dispatches a component to whichever of the four shapes JAdES gives its unsigned properties. */
    private static void addComponentBody(Node node, String key, Object body, String path) {
        switch (key) {
            case "sigTst", "arcTst", "sigRTst", "rfsTst", "adoTst" ->
                    addTimestamps(node, map(body), path,
                            etsiULabel(key).toLowerCase(Locale.ROOT));
            case "xVals", "axVals" -> addCertificates(node, list(body), path);
            case "rVals", "arVals" -> addRevocations(node, map(body), path);
            case "tstVD", "stVD" -> {
                Map<?, ?> validationData = map(body);
                if (validationData == null) return;
                addCertificates(node, list(validationData, "xVals"), path);
                addRevocations(node, map(validationData, "rVals"), path);
            }
            case "cSig" -> addCounterSignature(node, body, path);
            default -> { /* A property with no elements inside; its JSON is on the node. */ }
        }
    }

    /** True for the components that hold extractable elements rather than a few fields. */
    private static boolean opensIntoElements(String key) {
        return switch (key) {
            case "sigTst", "arcTst", "sigRTst", "rfsTst", "adoTst",
                 "xVals", "axVals", "rVals", "arVals", "tstVD", "stVD", "cSig" -> true;
            default -> false;
        };
    }

    private static void addTimestamps(Node parent, Map<?, ?> container, String path, String role) {
        if (container == null) return;
        String canonicalization = str(container, "canonAlg");
        if (canonicalization != null) parent.put("Kanonizace (canonAlg)", canonicalization);
        List<?> tokens = list(container, "tstTokens");
        if (tokens == null) return;
        int i = 0;
        for (Object entry : tokens) {
            i++;
            byte[] der = entry instanceof Map<?, ?> m ? decodeBase64(str(m, "val")) : null;
            if (der == null) continue;
            String name = tokens.size() == 1 ? "razitko.tst" : "razitko-" + i + ".tst";
            Node node = parent.add(new Node(name, path + "/" + name, false, role));
            node.data(der);
            node.put("Role", role);
            describeTimestamp(node, der);
        }
    }

    /**
     * Reads a time-stamp token out in words. Without this the element is 6 kB of DER whose only
     * interesting fact — when it was issued — costs the reader a trip into a nested window.
     *
     * <p>Čte to {@link ContainerInspector#describeTimestamp}, sdílené se zbylými dvěma cestami,
     * kterými se token do stromu dostane: nepodepsaným atributem CMS a samostatnou položkou
     * {@code .tst} v ZIPu. Do 16. 9. 2026 bylo znění zdejší a jen zdejší, takže týž token
     * vypadal jinak podle toho, v jakém nosiči ležel.
     */
    private static void describeTimestamp(Node node, byte[] der) {
        ContainerInspector.describeTimestamp(node, der);
    }

    private static void addCertificates(Node parent, List<?> values, String path) {
        if (values == null) return;
        int i = 0;
        for (Object entry : values) {
            i++;
            Map<?, ?> item = map(entry);
            if (item == null) continue;
            // x509Cert is the ordinary case; otherCert carries a certificate of another kind,
            // whose bytes are still worth handing over even when nothing here can read them.
            Map<?, ?> value = map(item, "x509Cert");
            boolean other = value == null;
            if (other) value = map(item, "otherCert");
            byte[] der = value == null ? null : decodeBase64(str(value, "val"));
            if (der == null) continue;
            addCertificate(parent, der, path, i,
                    other ? "certifikát jiného typu (otherCert)" : "certifikát pro ověření");
        }
    }

    /** @return the certificate's common name, or {@code null} when it could not be read */
    private static String addCertificate(Node parent, byte[] der, String path, int index, String role) {
        try {
            X509CertificateHolder holder = new X509CertificateHolder(der);
            String cn = ContainerInspector.commonName(String.valueOf(holder.getSubject()));
            String name = ContainerInspector.safeFileName(
                    cn.isEmpty() ? "certifikat-" + index : cn) + ".cer";
            Node node = parent.add(new Node(name, path + "/" + name, false, role));
            node.data(der);
            node.put("Role", role);
            node.put("Subjekt", String.valueOf(holder.getSubject()));
            node.put("Vydavatel", String.valueOf(holder.getIssuer()));
            node.put("Sériové číslo", ContainerInspector.hexNumber(holder.getSerialNumber()));
            node.put("Platnost od", ContainerInspector.formatDate(holder.getNotBefore()));
            node.put("Platnost do", ContainerInspector.formatDate(holder.getNotAfter()));
            return cn.isEmpty() ? null : cn;
        } catch (Exception e) {
            String name = "certifikat-" + index + ".cer";
            Node node = parent.add(new Node(name, path + "/" + name, false, role));
            node.data(der);
            node.put("Poznámka", "certifikát se nepodařilo přečíst ("
                    + ContainerInspector.describe(e) + ")");
            return null;
        }
    }

    private static void addRevocations(Node parent, Map<?, ?> container, String path) {
        if (container == null) return;
        int i = 0;
        for (Object entry : orEmpty(list(container, "crlVals"))) {
            i++;
            byte[] der = entry instanceof Map<?, ?> m ? decodeBase64(str(m, "val")) : null;
            if (der == null) continue;
            String name = "crl-" + i + ".crl";
            Node node = parent.add(new Node(name, path + "/" + name, false, "seznam CRL"));
            node.data(der);
            node.put("Role", "seznam CRL vložený do podpisu");
            try {
                X509CRLHolder holder = new X509CRLHolder(der);
                node.put("Vydavatel", String.valueOf(holder.getIssuer()));
                node.put("Vydáno", ContainerInspector.formatDate(holder.getThisUpdate()));
                if (holder.getNextUpdate() != null) {
                    node.put("Příští vydání", ContainerInspector.formatDate(holder.getNextUpdate()));
                }
            } catch (Exception ignored) {
                // Metadata are a nicety; the bytes are still extractable.
            }
        }
        i = 0;
        for (Object entry : orEmpty(list(container, "ocspVals"))) {
            i++;
            byte[] der = entry instanceof Map<?, ?> m ? decodeBase64(str(m, "val")) : null;
            if (der == null) continue;
            addOcsp(parent, der, path, i);
        }
    }

    /**
     * Adds an OCSP response, in the form in which it is a file rather than a fragment of one.
     *
     * <p>JAdES stores the whole {@code OCSPResponse}, unlike CMS, which keeps only the
     * {@code BasicOCSPResponse} inside. Both shapes are accepted and the bare one is wrapped on
     * the way out, so a response saved here is byte for byte the response saved anywhere else in
     * the application.
     */
    private static void addOcsp(Node parent, byte[] der, String path, int index) {
        byte[] whole = der;
        boolean wrapped = false;
        BasicOCSPResp basic = readOcsp(der);
        if (basic == null) {
            byte[] envelope = ContainerInspector.wrapAsOcspResponse(der);
            if (envelope != null) {
                whole = envelope;
                wrapped = true;
                basic = readOcsp(envelope);
            }
        }
        String name = "ocsp-" + index + ".ocsp";
        Node node = parent.add(new Node(name, path + "/" + name, false, "odpověď OCSP"));
        node.data(whole);
        node.put("Role", "odpověď OCSP vložená do podpisu");
        if (basic != null) {
            node.put("Vydáno", ContainerInspector.formatDate(basic.getProducedAt()));
            node.put("Počet záznamů", String.valueOf(basic.getResponses().length));
        }
        if (wrapped) {
            node.put("Forma", "v podpisu je uložena samotná struktura BasicOCSPResponse; obálka"
                    + " se stavem „successful“ se doplňuje až při vyjmutí, aby soubor šlo otevřít"
                    + " samostatně");
        }
    }

    /** The response's contents, or {@code null} when these bytes are not a whole OCSP response. */
    private static BasicOCSPResp readOcsp(byte[] wholeResponse) {
        try {
            Object contents = new OCSPResp(wholeResponse).getResponseObject();
            return contents instanceof BasicOCSPResp basic ? basic : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A counter-signature is a JAdES signature of its own. Its bytes are handed over as JSON, so
     * „Otevřít jako kontejner“ opens it in a window of its own and this class reads it again.
     */
    @SuppressWarnings("unchecked")
    private static void addCounterSignature(Node parent, Object body, String path) {
        Map<?, ?> container = map(body);
        if (container == null) return;
        byte[] json;
        try {
            // Written out as JSON again, not as the rendering the detail pane uses: the point of
            // this element is that it opens as a container of its own, and that needs a file a
            // parser will take. The keys come from the parser, so they are strings.
            json = JsonUtil.toJson((Map<String, Object>) container).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return;
        }
        Node node = parent.add(new Node("protipodpis.json", path + "/protipodpis.json", false,
                "protipodpis (cSig)"));
        node.data(json);
        node.put("Role", "protipodpis — samostatný podpis JAdES nad hodnotou tohoto podpisu");
        node.put("Forma", "protipodpis je v souboru uložen jako část většího celku; jako "
                + "samostatný soubor se zapisuje až při vyjmutí, takže jeho otisk se neshoduje "
                + "s ničím, co by bylo uloženo v původním souboru");
        node.put("Poznámka", "tlačítkem „Otevřít jako kontejner“ jej rozeberete stejně jako "
                + "tento soubor");
    }

    // ---------------------------------------------------------------- header vocabulary

    /** Czech name of a JOSE / JAdES header parameter, or {@code null} when there is none. */
    static String headerLabel(String key) {
        return switch (key) {
            case "alg"      -> "Podpisový algoritmus";
            case "cty"      -> "Typ podepsaného obsahu";
            case "typ"      -> "Typ objektu JOSE";
            case "kid"      -> "Identifikace podpisového certifikátu";
            case "x5u"      -> "Odkaz na certifikát podepisujícího";
            case "x5t"      -> "Otisk podpisového certifikátu (SHA-1)";
            case "x5t#S256" -> "Otisk podpisového certifikátu (SHA-256)";
            case "x5t#o"    -> "Otisk podpisového certifikátu";
            case "crit"     -> "Kritické hlavičky";
            case "b64"      -> "Payload zakódován v base64url";
            case "iat"      -> "Deklarovaný čas podpisu";
            case "sigT"     -> "Deklarovaný čas podpisu";
            case "sigD"     -> "Popis oddělených dat";
            case "sigPl"    -> "Místo podpisu";
            case "srCms"    -> "Tvrzený závazek podepisujícího";
            case "srAts"    -> "Tvrzené vlastnosti podepisujícího";
            case "adoTst"   -> "Časové razítko obsahu";
            case "sigPId"   -> "Identifikátor podpisové politiky";
            default -> null;
        };
    }

    /** Czech name of an {@code etsiU} component, or {@code null} for one this list does not know. */
    static String etsiULabel(String key) {
        return switch (key) {
            case "sigTst"  -> "Časové razítko podpisu";
            case "arcTst"  -> "Archivní časové razítko";
            case "sigRTst" -> "Časové razítko nad podpisem a odkazy";
            case "rfsTst"  -> "Časové razítko nad odkazy";
            case "adoTst"  -> "Časové razítko obsahu";
            case "xVals"   -> "Certifikáty pro ověření";
            case "rVals"   -> "Revokační údaje";
            case "axVals"  -> "Certifikáty atributových autorit";
            case "arVals"  -> "Revokační údaje atributových autorit";
            case "xRefs"   -> "Odkazy na certifikáty";
            case "rRefs"   -> "Odkazy na revokační údaje";
            case "axRefs"  -> "Odkazy na certifikáty atributových autorit";
            case "arRefs"  -> "Odkazy na revokační údaje atributových autorit";
            case "tstVD"   -> "Ověřovací údaje časových razítek";
            case "stVD"    -> "Ověřovací údaje";
            case "cSig"    -> "Protipodpis";
            case "sigPSt"  -> "Uložená podpisová politika";
            default -> null;
        };
    }

    static String headerValue(String key, Object value) {
        switch (key) {
            case "alg" -> {
                String alg = String.valueOf(value);
                return alg + " — " + algName(alg);
            }
            case "cty" -> {
                String cty = String.valueOf(value);
                return cty + " — " + mimeType(cty);
            }
            case "iat" -> {
                Date when = numericDate(value);
                return when == null ? compactJson(value, 200) : ContainerInspector.formatDate(when);
            }
            case "kid" -> {
                return describeKid(String.valueOf(value));
            }
            case "x5t", "x5t#S256" -> {
                byte[] digest = value instanceof String s ? decodeBase64(s) : null;
                return digest == null ? compactJson(value, 200) : hex(digest);
            }
            case "x5t#o" -> {
                return describeDigestObject(map(value));
            }
            default -> {
                return compactJson(value, 2000);
            }
        }
    }

    /** {@code x5t#o} is a digest with its algorithm beside it, not a bare value. */
    private static String describeDigestObject(Map<?, ?> value) {
        if (value == null) return null;
        String algorithm = str(value, "digAlg");
        byte[] digest = decodeBase64(str(value, "digVal"));
        String readable = algorithm == null ? null : digestShorthand(algorithm);
        return (readable == null ? "" : readable + ": ") + (digest == null ? "" : hex(digest));
    }

    /**
     * {@code kid} carries the issuer and serial number of the signing certificate, DER-encoded
     * and base64'd — unreadable as it stands, and exactly the pair a reader needs to tell which
     * certificate the signature claims.
     */
    private static String describeKid(String kid) {
        byte[] der = decodeBase64(kid);
        if (der == null) return kid;
        try {
            IssuerSerial issuerSerial = IssuerSerial.getInstance(ASN1Primitive.fromByteArray(der));
            GeneralNames names = issuerSerial.getIssuer();
            String issuer = names == null || names.getNames().length == 0 ? null
                    : generalName(names.getNames()[0]);
            String serial = ContainerInspector.hexNumber(issuerSerial.getSerial().getValue());
            return (issuer == null ? "" : "vydavatel " + issuer + ", ") + "sériové číslo " + serial;
        } catch (Exception e) {
            return kid;
        }
    }

    static String algName(String alg) {
        return switch (alg) {
            case "RS256" -> "RSASSA-PKCS1-v1_5 se SHA-256";
            case "RS384" -> "RSASSA-PKCS1-v1_5 se SHA-384";
            case "RS512" -> "RSASSA-PKCS1-v1_5 se SHA-512";
            case "PS256" -> "RSASSA-PSS se SHA-256";
            case "PS384" -> "RSASSA-PSS se SHA-384";
            case "PS512" -> "RSASSA-PSS se SHA-512";
            case "ES256" -> "ECDSA se SHA-256 (křivka P-256)";
            case "ES256K" -> "ECDSA se SHA-256 (křivka secp256k1)";
            case "ES384" -> "ECDSA se SHA-384 (křivka P-384)";
            case "ES512" -> "ECDSA se SHA-512 (křivka P-521)";
            case "EdDSA" -> "EdDSA (Ed25519 nebo Ed448)";
            case "HS256", "HS384", "HS512" -> "HMAC — sdílený klíč, nikoli elektronický podpis";
            case "none" -> "žádný — objekt není podepsán";
            default -> "algoritmus mimo běžný výčet JOSE";
        };
    }

    /** RFC 7515: a {@code cty} without a slash is the media type with {@code application/} dropped. */
    static String mimeType(String cty) {
        return cty.contains("/") ? cty : "application/" + cty;
    }

    private static String extensionFor(String cty) {
        if (cty == null) return null;
        String mime = mimeType(cty).toLowerCase(Locale.ROOT);
        return switch (mime) {
            case "application/pdf" -> ".pdf";
            case "application/xml", "text/xml" -> ".xml";
            case "application/json" -> ".json";
            case "text/plain" -> ".txt";
            case "application/vnd.oasis.opendocument.text" -> ".odt";
            case "application/vnd.oasis.opendocument.spreadsheet" -> ".ods";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            default -> null;
        };
    }

    static String digestShorthand(String algorithm) {
        return switch (algorithm) {
            case "S256" -> "SHA-256";
            case "S384" -> "SHA-384";
            case "S512" -> "SHA-512";
            default -> algorithm;
        };
    }

    /** The time the signer claims, from whichever of the two headers carries it. */
    static String claimedSigningTime(Map<?, ?> header) {
        String sigT = str(header, "sigT");
        if (sigT != null) return sigT;
        Date iat = numericDate(header.get("iat"));
        return iat == null ? null : ContainerInspector.formatDate(iat);
    }

    private static Date numericDate(Object value) {
        return value instanceof Number n ? new Date(n.longValue() * 1000L) : null;
    }

    private static String kindName(byte[] payload) {
        return switch (ContainerInspector.detectKind(payload)) {
            case PDF  -> "dokument PDF";
            case ZIP  -> "archiv ZIP (např. ASiC nebo dokument ODF/OOXML)";
            case XML  -> "dokument XML";
            case JSON -> "dokument JSON";
            case CMS  -> "objekt CMS / PKCS #7";
            case ZFO  -> "datová zpráva ISDS";
            case OTHER -> ContainerInspector.looksTextual(payload) ? "text" : "binární data";
        };
    }

    // ---------------------------------------------------------------- small helpers

    static String jsonText(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        int i = 0;
        while (i < text.length()
                && (text.charAt(i) == '﻿' || Character.isWhitespace(text.charAt(i)))) {
            i++;
        }
        return text.substring(i);
    }

    /** True when the list holds at least one JWS signature object — the member RFC 7515 requires. */
    static boolean holdsSignature(List<?> entries) {
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> m && m.get("signature") instanceof String) return true;
        }
        return false;
    }

    static Map<?, ?> protectedHeader(Map<?, ?> signature) {
        byte[] raw = decodeBase64(str(signature, "protected"));
        if (raw == null) return null;
        try {
            return JsonUtil.parseJson(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Decodes base64url and plain base64 alike. JAdES uses both — the JWS members are base64url,
     * while the certificates and tokens inside {@code etsiU} are base64 — and a decoder that
     * insisted on one would silently drop half the elements of a file.
     */
    static byte[] decodeBase64(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Base64.getMimeDecoder().decode(value.replace('-', '+').replace('_', '/'));
        } catch (Exception e) {
            return null;
        }
    }

    private static String generalName(GeneralName name) {
        if (name.getTagNo() == GeneralName.directoryName) {
            return String.valueOf(X500Name.getInstance(name.getName()));
        }
        return String.valueOf(name.getName());
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** A value rendered for the detail pane: readable JSON, cut off before it swamps the grid. */
    static String compactJson(Object value, int limit) {
        String text = compact(value);
        return text.length() <= limit ? text : text.substring(0, limit) + "… (zkráceno)";
    }

    private static String compact(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return s;
        if (value instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (sb.length() > 1) sb.append(", ");
                sb.append(e.getKey()).append(": ").append(compact(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (Object o : l) {
                if (sb.length() > 1) sb.append(", ");
                sb.append(compact(o));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(value);
    }

    private static List<?> orEmpty(List<?> value) {
        return value == null ? List.of() : value;
    }

    static String str(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof String s ? s : null;
    }

    static List<?> list(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof List<?> l ? l : null;
    }

    static List<?> list(Object value) {
        return value instanceof List<?> l ? l : null;
    }

    static Map<?, ?> map(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Map<?, ?> m ? m : null;
    }

    static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> m ? m : null;
    }
}
