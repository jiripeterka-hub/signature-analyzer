package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.CmsStructure.Content;
import cz.digitalnikontinuita.anep.core.StructureNode.Kind;
import cz.digitalnikontinuita.anep.core.StructureNode.Severity;
import org.bouncycastle.cert.X509CertificateHolder;
import org.jose4j.json.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Rozklad podpisu JAdES (JWS podle RFC 7515 a ETSI TS 119 182-1) pro Inspektor.
 *
 * <p>Doplněno 13. 10. 2026 na pokyn uživatele. Strom má touž stavbu jako u CAdES a XAdES:
 * podpis, pod ním chráněná hlavička (parametry, řetěz {@code x5c}, oddělená data {@code sigD},
 * razítko obsahu {@code adoTst}), podepsaný obsah, hodnota podpisu a nepodepsané vlastnosti
 * {@code etsiU} v uloženém pořadí — u JAdES na pořadí záleží, archivní razítko kryje to, co je
 * před ním. Razítka, certifikáty a revokační údaje jsou tytéž uzly jako jinde
 * ({@link CmsStructure}), takže se párují s DSS a objevují se ve skupině razítek.
 *
 * <p>Názvosloví hlaviček a vlastností přebírá od {@link JadesInspector} (prohlížeč kontejneru),
 * aby se týž parametr jmenoval v obou oknech stejně. Čte se přes jose4j jako formát, nic se
 * neověřuje kryptograficky; přepočítávají se jen otisky, které jdou zjistit bez politiky:
 * {@code x5t#S256} proti prvnímu certifikátu {@code x5c}, {@code sigD} proti předloženému
 * oddělenému souboru a otisk razítka podpisu.
 */
final class JadesStructure {

    private JadesStructure() {}

    /** Podpis JWS v serializaci JSON (obecné či zploštělé), nebo v kompaktní? */
    static boolean looksLikeJws(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 64L * 1024 * 1024) return false;
        try {
            Map<String, Object> top = JsonUtil.parseJson(JadesInspector.jsonText(bytes));
            List<?> sigs = JadesInspector.list(top, "signatures");
            return (sigs != null && JadesInspector.holdsSignature(sigs)) || top.get("signature") instanceof String;
        } catch (Exception e) {
            return compact(bytes) != null;
        }
    }

    /** Kompaktní serializace {@code hlavička.obsah.podpis} jako zploštělý objekt, nebo {@code null}. */
    private static Map<String, Object> compact(byte[] bytes) {
        // Hlavička JOSE je objekt JSON, v base64url tedy vždy začíná „ey“ ({"). Bez téhle zkratky
        // by se kvůli tlačítku rozebíral každý neznámý binární soubor až do 64 MB.
        if (bytes.length < 2 || bytes[0] != 'e' || bytes[1] != 'y') return null;
        String s = new String(bytes, StandardCharsets.US_ASCII).trim();
        String[] p = s.split("\\.", -1);
        if (p.length != 3 || !s.matches("[A-Za-z0-9_\\-.]+")) return null;
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("protected", p[0]);
        m.put("payload", p[1]);
        m.put("signature", p[2]);
        Map<?, ?> header = JadesInspector.protectedHeader(m);
        return header != null && header.get("alg") != null ? m : null;
    }

    static StructureInspector.Inspection inspect(byte[] bytes, String fileName, byte[] original,
                                                 String originalName) throws Exception {
        Map<String, Object> top;
        String serialization;
        List<Map<?, ?>> sigs = new ArrayList<>();
        Map<String, Object> asCompact = null;
        try {
            top = JsonUtil.parseJson(JadesInspector.jsonText(bytes));
        } catch (Exception e) {
            asCompact = compact(bytes);
            if (asCompact == null) throw e;
            top = asCompact;
        }
        List<?> list = JadesInspector.list(top, "signatures");
        if (asCompact != null) {
            serialization = "kompaktní (JWS Compact Serialization)";
            sigs.add(top);
        } else if (list != null && JadesInspector.holdsSignature(list)) {
            serialization = "obecná (JSON Serialization)";
            for (Object o : list) if (o instanceof Map<?, ?> m) sigs.add(m);
        } else {
            serialization = "zploštělá (Flattened JSON Serialization)";
            sigs.add(top);
        }

        String payloadB64 = JadesInspector.str(top, "payload");
        StructureNode group = new StructureNode(Kind.GROUP, "Podpisy a pečetě (" + sigs.size() + ")");
        int i = 0;
        for (Map<?, ?> s : sigs) {
            i++;
            group.add(signature(s, payloadB64, sigs.size() > 1 ? i : 0, original, originalName, false));
        }
        StructureNode ts = CadesStructure.timestampGroup(List.of(), group.children());
        List<StructureNode.Row> summary = new ArrayList<>();
        summary.add(new StructureNode.Row("Velikost", ContainerInspector.formatSize(bytes.length), null));
        summary.add(new StructureNode.Row("Serializace", serialization, null));
        summary.add(new StructureNode.Row("Obsah", payloadB64 != null && !payloadB64.isEmpty()
                ? "vložený (payload)" : "oddělený", null));
        summary.add(new StructureNode.Row("Podpisů", String.valueOf(sigs.size()), null));
        summary.add(new StructureNode.Row("Časových razítek", String.valueOf(ts.children().size()), null));
        return new StructureInspector.Inspection(StructureInspector.Format.JADES, fileName, bytes.length,
                List.of(), List.of(group, ts), summary, null);
    }

    // ------------------------------------------------------------------ podpis

    private static StructureNode signature(Map<?, ?> sig, String payloadB64, int index, byte[] original,
                                           String originalName, boolean counter) {
        String label = (counter ? "Protipodpis" : "Podpis") + (index > 0 ? " " + index : "");
        StructureNode node = new StructureNode(Kind.SIGNATURE, label);
        String protectedB64 = JadesInspector.str(sig, "protected");
        Map<?, ?> header = JadesInspector.protectedHeader(sig);
        String sigB64 = JadesInspector.str(sig, "signature");
        byte[] value = JadesInspector.decodeBase64(sigB64);
        if (value != null) node.dssKey(CmsStructure.signatureValueKey(value));

        List<byte[]> chain = new ArrayList<>();
        if (header != null) {
            String alg = JadesInspector.str(header, "alg");
            node.row("Podpisový algoritmus", alg != null ? alg + " — " + JadesInspector.algName(alg) : null);
            node.row("Deklarovaný čas podpisu", JadesInspector.claimedSigningTime(header));
            node.row("Typ obsahu (cty)", JadesInspector.str(header, "cty"));
            List<?> x5c = JadesInspector.list(header, "x5c");
            if (x5c != null) for (Object o : x5c) {
                byte[] der = o instanceof String s ? JadesInspector.decodeBase64(s) : null;
                if (der != null) chain.add(der);
            }
            if (!chain.isEmpty()) {
                try {
                    X509CertificateHolder h = new X509CertificateHolder(chain.get(0));
                    node.row("Certifikát podepisujícího", CmsStructure.label(h), CmsStructure.certKey(chain.get(0)));
                    node.subtitle(ContainerInspector.commonName(String.valueOf(h.getSubject())));
                } catch (Exception ignored) { }
            }
            if (Boolean.FALSE.equals(header.get("b64"))) node.row("Kódování obsahu", "bez base64url (b64 = false, RFC 7797)");
        } else {
            node.note(Severity.ERROR, "Podpis nemá čitelnou chráněnou hlavičku (protected).");
        }

        // Chráněná hlavička.
        if (header != null) {
            StructureNode h = node.add(new StructureNode(Kind.VALUE, "Chráněná hlavička (protected)"));
            byte[] raw = JadesInspector.decodeBase64(protectedB64);
            if (raw != null) h.data(raw, "chranena-hlavicka.json");
            for (Map.Entry<?, ?> e : header.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (k.equals("x5c") || k.equals("sigD") || k.equals("adoTst")) continue;
                String l = JadesInspector.headerLabel(k);
                h.row(l == null ? k : l + " (" + k + ")", JadesInspector.headerValue(k, e.getValue()));
            }
            checkX5t(h, header, chain);
            if (!chain.isEmpty()) {
                StructureNode g = h.add(new StructureNode(Kind.GROUP, "Řetěz certifikátů (x5c) (" + chain.size() + ")"));
                for (byte[] c : chain) g.add(CmsStructure.certificate(c));
            } else if (header.get("kid") == null && header.get("x5t#S256") == null && header.get("x5t#o") == null) {
                h.note(Severity.WARNING, "Hlavička certifikát podepisujícího neuvádí ani neidentifikuje.");
            }
            Map<?, ?> sigD = JadesInspector.map(header, "sigD");
            if (sigD != null) h.add(sigD(sigD, original, originalName, !Boolean.FALSE.equals(header.get("b64"))));
            Map<?, ?> ado = JadesInspector.map(header, "adoTst");
            if (ado != null) {
                StructureNode a = h.add(new StructureNode(Kind.ATTRIBUTE, "Razítko obsahu (adoTst)"));
                timestamps(a, ado, "razítko obsahu (adoTst)", "Razítko obsahu", List.of());
            }
        }

        // Podepsaný obsah.
        if (payloadB64 != null && !payloadB64.isEmpty()) {
            boolean b64 = header == null || !Boolean.FALSE.equals(header.get("b64"));
            byte[] payload = b64 ? JadesInspector.decodeBase64(payloadB64) : payloadB64.getBytes(StandardCharsets.UTF_8);
            StructureNode p = node.add(new StructureNode(Kind.VALUE, "Podepsaný obsah (payload)"));
            if (payload != null) {
                p.data(payload, "obsah.bin").subtitle(ContainerInspector.formatSize(payload.length));
                p.row("Velikost", ContainerInspector.formatSize(payload.length));
                p.row("SHA-256", ContainerInspector.sha256Hex(payload));
            }
            p.row("Uložení", b64 ? "base64url (RFC 7515)" : "přímo, bez kódování (RFC 7797)");
        } else if (!counter) {
            node.row("Obsah", "oddělený — v souboru není");
        }

        if (value != null) {
            node.add(new StructureNode(Kind.VALUE, "Hodnota podpisu (signature)")
                    .row("Délka", value.length + " B").row("Hodnota (hex)", CmsStructure.hex(value))
                    .data(value, "hodnota-podpisu.bin"));
        } else {
            node.note(Severity.ERROR, "Podpis nemá čitelnou hodnotu (signature).");
        }

        // Nepodepsané vlastnosti v uloženém pořadí.
        Map<?, ?> unprotected = JadesInspector.map(sig, "header");
        List<?> etsiU = unprotected == null ? null : JadesInspector.list(unprotected, "etsiU");
        if (etsiU != null && !etsiU.isEmpty()) {
            StructureNode g = node.add(new StructureNode(Kind.GROUP, "Nepodepsané vlastnosti (etsiU) (" + etsiU.size() + ")"));
            int order = 0;
            for (Object entry : etsiU) {
                order++;
                g.add(component(entry, order, etsiU.size(), sigB64, value, original, originalName));
            }
        }
        return node;
    }

    /** Kontrola {@code x5t#S256} a {@code x5t#o} proti prvnímu certifikátu {@code x5c}. */
    private static void checkX5t(StructureNode h, Map<?, ?> header, List<byte[]> chain) {
        if (chain.isEmpty()) return;
        byte[] declared = null;
        String jca = null;
        if (header.get("x5t#S256") instanceof String s) {
            declared = JadesInspector.decodeBase64(s);
            jca = "SHA-256";
        } else if (JadesInspector.map(header, "x5t#o") instanceof Map<?, ?> o) {
            declared = JadesInspector.decodeBase64(JadesInspector.str(o, "digVal"));
            jca = jcaFromUri(JadesInspector.str(o, "digAlg"));
        }
        if (declared == null || jca == null) return;
        try {
            boolean ok = Arrays.equals(MessageDigest.getInstance(jca).digest(chain.get(0)), declared);
            h.note(ok ? Severity.OK : Severity.ERROR, ok
                    ? "Otisk podpisového certifikátu v hlavičce souhlasí s prvním certifikátem x5c."
                    : "Otisk podpisového certifikátu v hlavičce nesouhlasí s prvním certifikátem x5c.");
        } catch (Exception ignored) { }
    }

    /** {@code sigD}: odkazy na oddělená data a jejich otisky, u předloženého souboru i s přepočtem. */
    /**
     * @param b64 hlavička nemá {@code b64: false} — otisk se pak podle TS 119 182-1 (5.2.8.3.3)
     *            počítá nad zápisem souboru v base64url bez doplňků „=“, ne nad jeho bajty.
     *            Do 13. 10. 2026 se počítal nad bajty a Inspektor hlásil nesoulad u podpisu,
     *            který AnEP i DSS hodnotí jako platný (nález uživatele).
     */
    private static StructureNode sigD(Map<?, ?> sigD, byte[] original, String originalName, boolean b64) {
        StructureNode n = new StructureNode(Kind.ATTRIBUTE, "Oddělená data (sigD)");
        String mId = JadesInspector.str(sigD, "mId");
        n.row("Mechanismus (mId)", mId != null ? JadesInspector.mechanismName(mId) : null);
        String hashM = JadesInspector.str(sigD, "hashM");
        n.row("Otisková funkce (hashM)", hashM != null ? JadesInspector.digestShorthand(hashM) : null);
        n.row("Otisk se počítá nad", b64 ? "zápisem souboru v base64url (b64 neuvedeno = true)"
                : "bajty souboru (b64 = false)");
        byte[] hashed = original == null ? null : b64
                ? java.util.Base64.getUrlEncoder().withoutPadding().encode(original) : original;
        List<?> pars = JadesInspector.list(sigD, "pars");
        List<?> hashV = JadesInspector.list(sigD, "hashV");
        if (pars == null) return n;
        n.subtitle(pars.size() == 1 ? String.valueOf(pars.get(0)) : pars.size() + " souborů");
        for (int i = 0; i < pars.size(); i++) {
            String name = String.valueOf(pars.get(i));
            byte[] d = hashV != null && i < hashV.size() && hashV.get(i) instanceof String s
                    ? JadesInspector.decodeBase64(s) : null;
            n.row("Soubor " + (i + 1), name);
            n.row("Otisk " + (i + 1), CmsStructure.hex(d));
            String jca = jcaFromShorthand(hashM);
            if (original != null && d != null && jca != null
                    && "http://uri.etsi.org/19182/ObjectIdByURIHash".equals(mId)) {
                // Předložený soubor se porovná s položkou téhož jména, a je-li položka jediná,
                // s ní — oddělený soubor mohl být přejmenován.
                if (pars.size() == 1 || name.equals(originalName)) {
                    try {
                        boolean ok = Arrays.equals(MessageDigest.getInstance(jca).digest(hashed), d);
                        n.note(ok ? Severity.OK : Severity.ERROR, "Otisk „" + name + "“ "
                                + (ok ? "souhlasí" : "nesouhlasí") + " s předloženým souborem.");
                    } catch (Exception ignored) { }
                }
            }
        }
        if (original == null) n.note(Severity.INFO, "Podepsaný soubor nebyl předložen; otisk se nepřepočítává.");
        return n;
    }

    // ------------------------------------------------------------------ etsiU

    private static StructureNode component(Object entry, int order, int total, String sigB64, byte[] value,
                                           byte[] original, String originalName) {
        byte[] raw = entry instanceof String s ? JadesInspector.decodeBase64(s) : null;
        Map<?, ?> comp = null;
        if (raw != null) {
            try {
                comp = JsonUtil.parseJson(new String(raw, StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        } else if (entry instanceof Map<?, ?> m) {
            comp = m;
        }
        if (comp == null || comp.isEmpty()) {
            return new StructureNode(Kind.ATTRIBUTE, order + ". (nečitelná položka)")
                    .note(Severity.WARNING, "Položku se nepodařilo přečíst.");
        }
        String key = String.valueOf(comp.keySet().iterator().next());
        Object body = comp.get(key);
        String l = JadesInspector.etsiULabel(key);
        StructureNode n = new StructureNode(Kind.ATTRIBUTE, order + ". " + (l == null ? key : l + " (" + key + ")"));
        n.row("Pořadí v etsiU", order + ". z " + total);
        n.row("Uložení", raw != null ? "base64url" : "nekódovaný JSON");
        try {
            n.data(JsonUtil.toJson((Map<String, Object>) comp).getBytes(StandardCharsets.UTF_8), key + ".json");
        } catch (Exception ignored) { }
        switch (key) {
            case "sigTst" -> {
                // TS 119 182-1: otisk razítka podpisu se počítá nad hodnotou podpisu tak, jak je
                // v JWS zapsaná (base64url); kvůli starším implementacím se zkouší i bajty.
                List<Content> imprint = new ArrayList<>();
                if (sigB64 != null) imprint.add(Content.of("hodnota podpisu v base64url", sigB64.getBytes(StandardCharsets.US_ASCII)));
                if (value != null) imprint.add(Content.of("hodnota podpisu (bajty)", value));
                timestamps(n, JadesInspector.map(body), "razítko podpisu (sigTst)", "Razítko podpisu", imprint);
            }
            case "arcTst" -> {
                n.row("Co razítko kryje", "podpis a vlastnosti uvedené v etsiU před ním (" + (order - 1) + " z " + total + ")");
                timestamps(n, JadesInspector.map(body), "archivní razítko (arcTst)", "Archivní razítko", List.of());
            }
            case "sigRTst" -> timestamps(n, JadesInspector.map(body), "razítko nad podpisem a odkazy (sigRTst)",
                    "Razítko nad podpisem a odkazy", List.of());
            case "rfsTst" -> timestamps(n, JadesInspector.map(body), "razítko nad odkazy (rfsTst)",
                    "Razítko nad odkazy", List.of());
            case "xVals", "axVals" -> certificates(n, JadesInspector.list(body));
            case "rVals", "arVals" -> revocations(n, JadesInspector.map(body));
            case "tstVD", "stVD" -> {
                Map<?, ?> vd = JadesInspector.map(body);
                certificates(n, JadesInspector.list(vd, "xVals"));
                revocations(n, JadesInspector.map(vd, "rVals"));
                n.subtitle(n.children().size() + " údajů");
            }
            case "cSig" -> {
                Map<?, ?> cs = JadesInspector.map(body);
                if (cs != null) n.add(signature(cs, JadesInspector.str(cs, "payload"), 0, null, null, true));
            }
            default -> n.row("Obsah", JadesInspector.compactJson(body, 2000));
        }
        return n;
    }

    private static void timestamps(StructureNode n, Map<?, ?> container, String role, String title,
                                   List<Content> imprint) {
        if (container == null) return;
        n.row("Kanonikalizace (canonAlg)", JadesInspector.str(container, "canonAlg"));
        List<?> tokens = JadesInspector.list(container, "tstTokens");
        int c = 0;
        if (tokens != null) for (Object o : tokens) {
            byte[] der = o instanceof Map<?, ?> m ? JadesInspector.decodeBase64(JadesInspector.str(m, "val")) : null;
            if (der == null) continue;
            c++;
            n.add(CmsStructure.timestamp(der, role, imprint)).title(title);
        }
        n.subtitle(c == 1 ? "1 razítko" : c + " razítka");
    }

    private static void certificates(StructureNode n, List<?> values) {
        if (values == null) return;
        for (Object o : values) {
            Map<?, ?> item = JadesInspector.map(o);
            Map<?, ?> v = item == null ? null : JadesInspector.map(item, "x509Cert");
            if (v == null && item != null) v = JadesInspector.map(item, "otherCert");
            byte[] der = v == null ? null : JadesInspector.decodeBase64(JadesInspector.str(v, "val"));
            if (der != null) n.add(CmsStructure.certificate(der));
        }
        if (n.subtitle() == null) n.subtitle(n.children().size() + " certifikátů");
    }

    private static void revocations(StructureNode n, Map<?, ?> container) {
        if (container == null) return;
        int crl = 0, ocsp = 0;
        List<?> crls = JadesInspector.list(container, "crlVals");
        if (crls != null) for (Object o : crls) {
            byte[] der = o instanceof Map<?, ?> m ? JadesInspector.decodeBase64(JadesInspector.str(m, "val")) : null;
            if (der != null) { n.add(CmsStructure.crl(der)); crl++; }
        }
        List<?> ocsps = JadesInspector.list(container, "ocspVals");
        if (ocsps != null) for (Object o : ocsps) {
            byte[] der = o instanceof Map<?, ?> m ? JadesInspector.decodeBase64(JadesInspector.str(m, "val")) : null;
            if (der != null) { n.add(CmsStructure.ocsp(der)); ocsp++; }
        }
        n.subtitle(crl + " CRL, " + ocsp + " OCSP");
    }

    private static String jcaFromShorthand(String s) {
        if (s == null) return null;
        return switch (s) {
            case "S256" -> "SHA-256";
            case "S384" -> "SHA-384";
            case "S512" -> "SHA-512";
            default -> jcaFromUri(s);
        };
    }

    private static String jcaFromUri(String uri) {
        if (uri == null) return null;
        String u = uri.toLowerCase(java.util.Locale.ROOT);
        if (u.endsWith("sha256")) return "SHA-256";
        if (u.endsWith("sha384")) return "SHA-384";
        if (u.endsWith("sha512")) return "SHA-512";
        if (u.endsWith("#sha1")) return "SHA-1";
        return null;
    }
}
