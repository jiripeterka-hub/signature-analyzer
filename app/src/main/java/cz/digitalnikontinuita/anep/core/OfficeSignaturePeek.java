package cz.digitalnikontinuita.anep.core;

import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.tsp.TimeStampToken;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Nahlédnutí do podpisu v kancelářském formátu — <b>bez ověřování</b>.
 *
 * <h2>Proč to vůbec je</h2>
 *
 * <p>Podpisy v DOCX/XLSX/PPTX aplikace neověřuje: OOXML potřebuje vlastní
 * {@code RelationshipTransform} (ECMA-376 Part 2 §13.2.4.24), který DSS nezná, a bez něj nelze
 * prokázat, že podpis kryje obsah dokumentu. Dosud takový soubor skončil hláškou „formát
 * aplikace nepodporuje“, ačkoli je v něm napsáno mnohem víc.
 *
 * <p><b>ODF je jiný případ a měřením se to ukázalo obráceně, než jsem čekal:</b> podepsaný ODT
 * ověří {@code ASiCContainerWithXAdESValidator} v DSS úplně a normálně — XAdES-BASELINE-T,
 * integrita v pořádku, razítko nalezené. Rozvržení OpenDocumentu mu stačí, žádné transformace
 * navíc nepotřebuje. Větev pro ODF tady proto <b>není náhrada ověřování</b>, nýbrž záchytná síť
 * pro kontejner, který se ověřit nepodařilo (poškozený obal, podpis jen nad makry) — v takovém
 * případě je pořád lepší přečíst, co v podpisu stojí, než mlčet.
 *
 * <p><b>Změřeno na sbírce</b> (D:\tmp a D:\Downloads, sonda {@code OfficePeekProbe},
 * 1. 9. 2026): 650 kandidátů, z toho <b>24 podepsaných</b> a v nich 24 podpisů a 29 razítek.
 * Certifikát podepsané osoby, algoritmy a pokrytí částí nese 100 % podpisů, razítko 79 %,
 * vložené revokační údaje 62 %. A hlavně: SHA-1 je pořád dominantní nález — ten je znám
 * okamžitě a plné ověření by ho nezměnilo.
 *
 * <p>Zápis z 31. 8. 2026 tu uváděl 140 podepsaných souborů a 103 razítek. <b>Nereprodukuje
 * se to</b>: nad týmiž dvěma adresáři vychází dnes 24 a 29. Čím to je, nevím — D:\tmp je
 * dočasný adresář a jeho obsah se mění. Nechávám tu proto číslo, které si čtenář může
 * ověřit spuštěním sondy, ne to původní.
 *
 * <h2>Hranice, kterou tahle třída nesmí překročit</h2>
 *
 * <p>Jméno podepsané osoby a deklarovaný čas jsou <b>tvrzení podpisu</b>, ne zjištění. Dokud se
 * neověří reference, není doloženo, že podpis kryje obsah, který čtenář vidí. Model proto drží
 * obojí odděleně a nikde nevydává jedno za druhé; text, který to čtenáři říká, je
 * v {@code OfficePeekDialog}.
 *
 * <p>Samostatně ověřitelné je jen to, co na transformacích nezávisí: neporušenost tokenu
 * časového razítka (je to vlastní podpis nad otiskem podpisové hodnoty) a vlastnosti certifikátu.
 *
 * <p><b>Třetí kategorie vedle „tvrdí“ a „ověřeno“ je „co v souboru leží“:</b> úroveň podpisu
 * ({@link Level}), počet a druh vložených revokačních údajů ({@link Revocation}) a konec
 * platnosti certifikátu časového razítka ({@link Timestamp}). Nejsou to tvrzení podepisujícího
 * ani výsledky ověření — jsou to fakta o obsahu souboru, ze kterých čtenář odhadne, do kdy má
 * ověření vůbec šanci dopadnout. Okno je proto drží zvlášť a nevydává je za verdikt.
 */
public final class OfficeSignaturePeek {

    private OfficeSignaturePeek() { }

    /** Rodina formátů. Liší se jen tím, kde podpisy leží a co je kolem nich. */
    public enum Family {
        /** DOCX, XLSX, PPTX — podpisy v {@code _xmlsignatures/sig*.xml}. */
        OOXML("kancelářský formát Microsoft Office (OOXML)"),
        /** ODT, ODS, ODP — podpisy v {@code META-INF/documentsignatures.xml}. */
        ODF("otevřený formát OpenDocument (ODF)");

        private final String label;
        Family(String label) { this.label = label; }
        public String label() { return label; }
    }

    /**
     * Co se v souboru našlo.
     *
     * @param family        rodina formátů
     * @param signatures    jeden záznam na podpis
     * @param partCount     kolik částí soubor obsahuje (bez adresářů)
     * @param uncoveredParts části, na které se neodkazuje žádný podpis
     */
    public record Finding(Family family, List<Signature> signatures, int partCount,
                          List<String> uncoveredParts) {

        public Finding {
            signatures = List.copyOf(signatures);
            uncoveredParts = List.copyOf(uncoveredParts);
        }
    }

    /**
     * Jedno časové razítko nalezené v podpisu.
     *
     * <p>Rozlišení podle {@link Kind} není kosmetika: <b>horizont ověřitelnosti určuje poslední
     * článek řetězu</b>, ne první. U podpisu s archivním razítkem je proto rozhodující certifikát
     * archivního razítka, i když je podpisové razítko starší a nápadnější.
     *
     * @param kind          podpisové, nebo archivní
     * @param time          čas, který razítko uvádí
     * @param tsaName       jméno autority z pole {@code tsa} v tokenu; bývá prázdné
     * @param intact        token je vnitřně neporušený — podpis autority nad ním sedí
     * @param certNotAfter  konec platnosti certifikátu, kterým je token podepsán
     * @param certSubject   jméno z certifikátu razítka; spolehlivější než {@code tsaName}
     */
    public record Timestamp(Kind kind, Instant time, String tsaName, boolean intact,
                            Instant certNotAfter, String certSubject) {

        public enum Kind {
            SIGNATURE("podpisové"),
            ARCHIVE("archivní");

            private final String label;
            Kind(String label) { this.label = label; }
            public String label() { return label; }
        }
    }

    /**
     * Jeden revokační údaj vložený do podpisu ({@code RevocationValues}).
     *
     * <p>Že tam je, ještě neznamená, že se hodí na certifikát podepsané osoby ani že by ověření
     * prošlo — to se pozná až ověřením. Pro čtenáře je to ale rozdíl, který stojí za zobrazení:
     * podpis, který si revokační údaje veze s sebou, jde ověřit i tehdy, když už služba
     * vydavatele neodpovídá.
     *
     * @param ocsp       {@code true} pro odpověď OCSP, {@code false} pro CRL
     * @param issuer     kdo údaj vydal, pokud se to podařilo přečíst
     * @param produced   {@code thisUpdate} u CRL, {@code producedAt} u OCSP
     * @param nextUpdate ohlášené příští vydání; jen u CRL a jen někdy
     */
    public record Revocation(boolean ocsp, String issuer, Instant produced, Instant nextUpdate) {

        public String kindLabel() { return ocsp ? "OCSP" : "CRL"; }
    }

    /**
     * Úroveň podle toho, <b>co v podpisu leží</b> — ne podle ověření.
     *
     * <p>Tohle je nutné číst opatrně a okno to čtenáři říká. Skutečnou úroveň přiřazuje validátor
     * až po ověření a může vyjít <b>nižší</b>: podpis, který nese {@code CertificateValues}
     * a {@code RevocationValues}, tady vyjde jako LT, ale je-li ten materiál neúplný nebo se
     * nehodí na certifikáty v cestě, zůstane po ověření na B. Opačným směrem se to plést nemůže —
     * co v souboru není, to ověřením nevznikne.
     *
     * <p>Změřeno na sbírce (24 podepsaných souborů OOXML): všech 24 je XAdES, 19 má podpisové
     * razítko, 15 nese revokační údaje a 10 i archivní razítko — všechna z uchovávací služby.
     */
    public enum Level {
        /** Holý XMLDSIG: chybí {@code QualifyingProperties}, tedy vůbec nejde o XAdES. */
        XMLDSIG("XMLDSIG (bez vlastností XAdES)"),
        /** XAdES bez razítka: podepsané vlastnosti ano, čas nikdo nedosvědčil. */
        B("XAdES-B"),
        /** S podpisovým razítkem. */
        T("XAdES-T"),
        /** S vloženými certifikáty i revokačními údaji. */
        LT("XAdES-LT"),
        /** S archivním razítkem přes celý podpis. */
        LTA("XAdES-LTA");

        private final String label;
        Level(String label) { this.label = label; }
        public String label() { return label; }
    }

    /**
     * Jeden podpis tak, jak o sobě mluví.
     *
     * @param entryName        jméno souboru s podpisem uvnitř kontejneru
     * @param signer           certifikát podepsané osoby, nebo {@code null}
     * @param chain            další certifikáty přiložené k podpisu
     * @param claimedTime      čas, který podpis <b>tvrdí</b>; neověřený
     * @param timestamps       nalezená časová razítka v pořadí podpisové → archivní
     * @param digestAlgorithms otiskové algoritmy použité v referencích
     * @param signatureAlgorithm algoritmus podpisové hodnoty
     * @param references       počet referencí, tedy krytých částí
     * @param coveredParts     jména krytých částí, jak je uvádějí reference
     * @param level            úroveň odečtená ze struktury, ne z ověření — viz {@link Level}
     * @param revocations      revokační údaje vložené do podpisu
     * @param hasRevocationValues podpis nese prvek {@code RevocationValues}; může být i tehdy,
     *                            když se z něj nepodařilo přečíst jediný údaj
     * @param hasPolicy        podpis se odvolává na podpisovou politiku
     * @param provider         čím byl dokument podepsán (jen OOXML)
     * @param purpose          účel podpisu, jak jej podepisující napsal (jen OOXML)
     */
    public record Signature(String entryName, X509Certificate signer, List<X509Certificate> chain,
                            Instant claimedTime, List<Timestamp> timestamps,
                            Set<String> digestAlgorithms,
                            String signatureAlgorithm, int references, List<String> coveredParts,
                            Level level, List<Revocation> revocations,
                            boolean hasRevocationValues, boolean hasPolicy,
                            String provider, String purpose) {

        public Signature {
            chain = chain != null ? List.copyOf(chain) : List.of();
            timestamps = timestamps != null ? List.copyOf(timestamps) : List.of();
            revocations = revocations != null ? List.copyOf(revocations) : List.of();
            digestAlgorithms = digestAlgorithms != null
                    ? Collections.unmodifiableSet(new LinkedHashSet<>(digestAlgorithms)) : Set.of();
            coveredParts = coveredParts != null ? List.copyOf(coveredParts) : List.of();
        }

        /**
         * Poslední článek řetězu razítek — ten, na kterém stojí horizont ověřitelnosti. U podpisu
         * bez archivního razítka je to razítko podpisové; bez razítek {@code null}.
         *
         * <p>Bere se poslední <b>v čase</b>, ne poslední v pořadí zápisu: archivní razítko je
         * v souboru až za podpisovým, ale spoléhat se na pořadí zápisu by znamenalo věřit
         * podepisujícímu programu v něčem, co se dá spočítat.
         */
        public Timestamp lastTimestamp() {
            Timestamp best = null;
            for (Timestamp t : timestamps) {
                if (t.time() == null) continue;
                if (best == null || t.time().isAfter(best.time())) best = t;
            }
            return best != null ? best : (timestamps.isEmpty() ? null : timestamps.get(0));
        }

        /**
         * Používá podpis SHA-1? Dominantní nález na sbírce a jediný, který sám o sobě rozhodne —
         * zastaralý algoritmus stav platnosti zneznámí bez ohledu na to, jak dobře je podpis
         * udělaný.
         */
        public boolean usesSha1() {
            if (digestAlgorithms.stream().anyMatch(a -> a.toLowerCase(Locale.ROOT).contains("sha1"))) {
                return true;
            }
            return signatureAlgorithm != null
                    && signatureAlgorithm.toLowerCase(Locale.ROOT).contains("sha1");
        }
    }

    /** Přípony, u kterých má smysl se do souboru vůbec podívat. */
    private static final Set<String> EXTENSIONS = Set.of(
            "docx", "xlsx", "pptx", "docm", "xlsm", "pptm",
            "odt", "ods", "odp", "odg", "odf");

    /** Vypadá soubor jako kancelářský dokument? Levná otázka podle jména, ne podle obsahu. */
    public static boolean isCandidate(File file) {
        if (file == null || !file.isFile()) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1));
    }

    /**
     * Nahlédne do souboru. {@code null}, když to není kancelářský dokument nebo v něm žádný
     * podpis není — volající pak pokračuje po své obvyklé cestě.
     */
    public static Finding peek(File file) {
        if (!isCandidate(file)) return null;
        try (ZipFile zip = new ZipFile(file)) {
            List<ZipEntry> entries = new ArrayList<>();
            int parts = 0;
            for (java.util.Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.isDirectory()) { entries.add(entry); parts++; }
            }

            Family family = null;
            List<ZipEntry> sigEntries = new ArrayList<>();
            for (ZipEntry entry : entries) {
                String n = entry.getName();
                if (n.startsWith("_xmlsignatures/") && n.endsWith(".xml")
                        && baseName(n).startsWith("sig")) {
                    family = Family.OOXML;
                    sigEntries.add(entry);
                } else if ("META-INF/documentsignatures.xml".equals(n)
                        || "META-INF/macrosignatures.xml".equals(n)) {
                    if (family == null) family = Family.ODF;
                    sigEntries.add(entry);
                }
            }
            if (family == null || sigEntries.isEmpty()) return null;

            List<Signature> signatures = new ArrayList<>();
            for (ZipEntry entry : sigEntries) {
                try (InputStream in = zip.getInputStream(entry)) {
                    signatures.addAll(readSignatures(entry.getName(), in));
                } catch (Exception ignored) {
                    // Jeden nečitelný podpis nemá zahodit ostatní ani celý nález.
                }
            }
            if (signatures.isEmpty()) return null;

            Set<String> covered = new LinkedHashSet<>();
            for (Signature s : signatures) covered.addAll(s.coveredParts());
            List<String> uncovered = new ArrayList<>();
            for (ZipEntry entry : entries) {
                String n = entry.getName();
                if (n.startsWith("_xmlsignatures/") || n.startsWith("META-INF/")) continue;
                if (!covered.contains(n) && !covered.contains("/" + n)) uncovered.add(n);
            }
            return new Finding(family, signatures, parts, uncovered);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ODF drží víc podpisů v jednom souboru pod kořenem {@code document-signatures}; OOXML má
     * jeden podpis na soubor. Obojí se čte stejně — projdou se všechny prvky {@code Signature}.
     */
    private static List<Signature> readSignatures(String entryName, InputStream in)
            throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        // Bez tohohle nenajde getElementsByTagNameNS nic a celé nahlédnutí vrátí prázdno —
        // XML podpisu je vždy v jmenném prostoru.
        dbf.setNamespaceAware(true);
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setExpandEntityReferences(false);
        Document doc = dbf.newDocumentBuilder().parse(in);

        NodeList sigNodes = doc.getElementsByTagNameNS(
                "http://www.w3.org/2000/09/xmldsig#", "Signature");
        List<Signature> out = new ArrayList<>();
        for (int i = 0; i < sigNodes.getLength(); i++) {
            out.add(readOne(entryName, (Element) sigNodes.item(i)));
        }
        return out;
    }

    private static Signature readOne(String entryName, Element sig) {
        List<X509Certificate> certs = new ArrayList<>();
        for (Element e : elements(sig, "X509Certificate")) {
            X509Certificate c = certificate(e.getTextContent());
            if (c != null) certs.add(c);
        }

        Instant claimed = instant(first(sig, "SigningTime"));

        List<Timestamp> timestamps = readTimestamps(sig);

        Set<String> digests = new LinkedHashSet<>();
        List<String> parts = new ArrayList<>();
        int refs = 0;
        for (Element ref : elements(sig, "Reference")) {
            String uri = ref.getAttribute("URI");
            // Reference na vlastní části podpisu (#idPackageObject) nejsou části dokumentu.
            if (uri != null && !uri.isBlank() && !uri.startsWith("#")) {
                refs++;
                parts.add(partName(uri));
            }
            Element dm = firstChildByLocal(ref, "DigestMethod");
            if (dm != null) digests.add(algorithmName(dm.getAttribute("Algorithm")));
        }

        Element sm = first(sig, "SignatureMethod");
        String sigAlg = sm != null ? algorithmName(sm.getAttribute("Algorithm")) : null;

        boolean hasRevValues = first(sig, "RevocationValues") != null;

        return new Signature(entryName,
                certs.isEmpty() ? null : certs.get(0),
                certs.size() > 1 ? certs.subList(1, certs.size()) : List.of(),
                claimed, timestamps, digests, sigAlg, refs, parts,
                readLevel(sig, hasRevValues),
                readRevocations(sig),
                hasRevValues,
                first(sig, "SignaturePolicyIdentifier") != null,
                textOf(first(sig, "SignatureProviderId")),
                textOf(first(sig, "SignatureComments")));
    }

    /**
     * Úroveň odečtená ze struktury. Kaskáda shora dolů: co je podmíněno vyšším stupněm, se
     * nezkoumá, když nižší nesedí.
     *
     * <p>Rozhoduje <b>přítomnost prvku</b>, ne to, zda se token podařilo rozebrat. Jinak by
     * podpis s vadným archivním razítkem tiše spadl na LT a čtenář by se o tom archivním
     * nedozvěděl — přitom právě to je věc, kterou potřebuje vidět. Že se token nepodařilo
     * přečíst, se hlásí zvlášť u razítka samotného.
     *
     * <p>{@code CertificateValues} bez {@code RevocationValues} na LT nestačí — samotné
     * certifikáty ověření po expiraci nezachrání, chybí čím doložit, že nebyly odvolány. Ve
     * sbírce takové podpisy jsou (20 nese certifikáty, jen 15 revokační údaje).
     */
    private static Level readLevel(Element sig, boolean hasRevValues) {
        if (first(sig, "QualifyingProperties") == null) return Level.XMLDSIG;
        if (first(sig, "ArchiveTimeStamp") != null) return Level.LTA;
        if (first(sig, "CertificateValues") != null && hasRevValues) return Level.LT;
        boolean timestamped = first(sig, "SignatureTimeStamp") != null
                || first(sig, "EncapsulatedTimeStamp") != null;
        return timestamped ? Level.T : Level.B;
    }

    /**
     * Razítka podpisu, roztříděná podle toho, kde v podpisu leží.
     *
     * <p>Dřív se bral první {@code EncapsulatedTimeStamp} kdekoli v podpisu. Na sbírce to
     * fungovalo náhodou — v pořadí zápisu je podpisové razítko první — ale u podpisu
     * s archivním razítkem je to sázka na to, jak podepisující program řadí prvky. Tady se
     * proto nejdřív najde obalující prvek ({@code SignatureTimeStamp}, {@code ArchiveTimeStamp})
     * a token se čte až uvnitř něj.
     *
     * <p>Záchytná větev na konci pokrývá podpis, který token nese, ale ani v jednom z obou
     * obalů — pak je pořád lepší čas ukázat než mlčet, a označí se jako podpisový.
     */
    private static List<Timestamp> readTimestamps(Element sig) {
        List<Timestamp> out = new ArrayList<>();
        for (Element holder : elements(sig, "SignatureTimeStamp")) {
            addTimestamps(out, holder, Timestamp.Kind.SIGNATURE);
        }
        for (Element holder : elements(sig, "ArchiveTimeStamp")) {
            addTimestamps(out, holder, Timestamp.Kind.ARCHIVE);
        }
        if (out.isEmpty()) {
            Element loose = first(sig, "EncapsulatedTimeStamp");
            if (loose != null) {
                Timestamp t = readTimestamp(loose, Timestamp.Kind.SIGNATURE);
                if (t != null) out.add(t);
            }
        }
        return out;
    }

    private static void addTimestamps(List<Timestamp> out, Element holder, Timestamp.Kind kind) {
        for (Element enc : elements(holder, "EncapsulatedTimeStamp")) {
            Timestamp t = readTimestamp(enc, kind);
            if (t != null) out.add(t);
        }
    }

    /**
     * Jeden token. Kromě času se z něj bere i <b>konec platnosti certifikátu, kterým je
     * podepsán</b> — to je údaj, ze kterého čtenář odhadne, do kdy má ověření šanci dopadnout:
     * po tomhle datu už razítko samo o sobě nestačí a je potřeba další razítko nad ním.
     */
    private static Timestamp readTimestamp(Element enc, Timestamp.Kind kind) {
        try {
            byte[] der = Base64.getMimeDecoder().decode(enc.getTextContent().trim());
            TimeStampToken token = new TimeStampToken(new CMSSignedData(der));
            Instant time = token.getTimeStampInfo().getGenTime().toInstant();
            String tsaName = token.getTimeStampInfo().getTsa() != null
                    ? token.getTimeStampInfo().getTsa().getName().toString() : null;

            org.bouncycastle.cert.X509CertificateHolder holder = signerCertificate(token);
            Instant notAfter = holder != null ? holder.getNotAfter().toInstant() : null;
            String subject = holder != null ? holder.getSubject().toString() : null;

            return new Timestamp(kind, time, tsaName, tokenIntact(token, holder),
                    notAfter, subject);
        } catch (Exception e) {
            // Razítko, které se nepodařilo rozebrat, se nehlásí jako platné ani jako vadné:
            // o obsahu dokumentu stejně nic neříká a tady se nic neověřuje.
            return null;
        }
    }

    /** Certifikát, kterým je token podepsán — token si ho skoro vždy veze s sebou. */
    private static org.bouncycastle.cert.X509CertificateHolder signerCertificate(
            TimeStampToken token) {
        try {
            var matches = token.getCertificates().getMatches(token.getSID());
            if (matches.isEmpty()) return null;
            return (org.bouncycastle.cert.X509CertificateHolder) matches.iterator().next();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Revokační údaje vložené do podpisu. Čtou se jen hlavičky — kdo údaj vydal a kdy — protože
     * o víc tady nejde: bez ověření podpisu se stejně neví, na který certifikát v cestě údaj
     * sedí a zda ho pokrývá.
     */
    private static List<Revocation> readRevocations(Element sig) {
        List<Revocation> out = new ArrayList<>();
        for (Element e : elements(sig, "EncapsulatedCRLValue")) {
            try {
                byte[] der = Base64.getMimeDecoder().decode(e.getTextContent().trim());
                java.security.cert.X509CRL crl = (java.security.cert.X509CRL)
                        CertificateFactory.getInstance("X.509")
                                .generateCRL(new ByteArrayInputStream(der));
                out.add(new Revocation(false,
                        crl.getIssuerX500Principal().getName(),
                        crl.getThisUpdate() != null ? crl.getThisUpdate().toInstant() : null,
                        crl.getNextUpdate() != null ? crl.getNextUpdate().toInstant() : null));
            } catch (Exception ignored) {
                // Nečitelný údaj se nepočítá; prvek RevocationValues se hlásí zvlášť.
            }
        }
        for (Element e : elements(sig, "EncapsulatedOCSPValue")) {
            try {
                byte[] der = Base64.getMimeDecoder().decode(e.getTextContent().trim());
                var resp = new org.bouncycastle.cert.ocsp.OCSPResp(der);
                if (!(resp.getResponseObject()
                        instanceof org.bouncycastle.cert.ocsp.BasicOCSPResp basic)) {
                    continue;
                }
                out.add(new Revocation(true, ocspResponder(basic),
                        basic.getProducedAt() != null ? basic.getProducedAt().toInstant() : null,
                        null));
            } catch (Exception ignored) {
                // Totéž co u CRL.
            }
        }
        return out;
    }

    /**
     * Kdo odpověď vydal. Respondér se v odpovědi představuje buď jménem, nebo otiskem klíče —
     * v druhém případě se sáhne po certifikátu přiloženém k odpovědi, a když ani ten není,
     * vrátí se {@code null} a okno o vydavateli mlčí.
     */
    private static String ocspResponder(org.bouncycastle.cert.ocsp.BasicOCSPResp basic) {
        try {
            var id = basic.getResponderId().toASN1Primitive();
            if (id.getName() != null) return id.getName().toString();
        } catch (Exception ignored) {
            // Padne-li čtení jména, zkusí se ještě certifikát níže.
        }
        try {
            var certs = basic.getCerts();
            if (certs != null && certs.length > 0) return certs[0].getSubject().toString();
        } catch (Exception ignored) {
            // Nevadí, vydavatel je doplněk.
        }
        return null;
    }

    /**
     * Sedí podpis samotného tokenu razítka? Ověřuje se certifikátem, který token nese —
     * neříká tedy nic o důvěře k autoritě, jen že token nikdo nepřepsal.
     *
     * <p>Certifikát se předává zvenčí, protože ho volající potřebuje i pro konec platnosti
     * a hledat ho v tokenu dvakrát nemá důvod.
     */
    private static boolean tokenIntact(TimeStampToken token,
                                       org.bouncycastle.cert.X509CertificateHolder holder) {
        if (holder == null) return false;
        try {
            // Poskytovatel se předává jako instance, ne jménem: jménem by to záviselo na tom, zda
            // je Bouncy Castle zaregistrovaný v JCA, a bez něj neumí standardní běhové prostředí
            // RSASSA-PSS. Změřeno na sbírce — s registrací jménem část razítek selhala právě na
            // „SHA256WITHRSASSA-PSS Signature not available"; s instancí prošla všechna.
            // Přeměřeno 1. 9. 2026: 29 z 29 razítek neporušených.
            token.validate(new org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
                    .build(holder));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Drobnosti ────────────────────────────────────────────────────────────────────────────

    private static List<Element> elements(Element scope, String localName) {
        NodeList list = scope.getElementsByTagNameNS("*", localName);
        List<Element> out = new ArrayList<>(list.getLength());
        for (int i = 0; i < list.getLength(); i++) out.add((Element) list.item(i));
        return out;
    }

    private static Element first(Element scope, String localName) {
        NodeList list = scope.getElementsByTagNameNS("*", localName);
        return list.getLength() > 0 ? (Element) list.item(0) : null;
    }

    private static Element firstChildByLocal(Element scope, String localName) {
        NodeList list = scope.getElementsByTagNameNS("*", localName);
        return list.getLength() > 0 ? (Element) list.item(0) : null;
    }

    private static String textOf(Element e) {
        if (e == null) return null;
        String t = e.getTextContent();
        return t == null || t.isBlank() ? null : t.trim();
    }

    private static X509Certificate certificate(String base64) {
        try {
            byte[] der = Base64.getMimeDecoder().decode(base64.trim());
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant instant(Element e) {
        String t = textOf(e);
        if (t == null) return null;
        try {
            return java.time.OffsetDateTime.parse(t).toInstant();
        } catch (Exception ignored) {
            try {
                return Instant.parse(t);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /** {@code /word/document.xml?ContentType=…} → {@code word/document.xml}. */
    private static String partName(String uri) {
        String s = uri;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        if (s.startsWith("/")) s = s.substring(1);
        return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** {@code …xmldsig#sha1} → {@code sha1}, {@code …xmldsig-more#rsa-sha256} → {@code rsa-sha256}. */
    private static String algorithmName(String uri) {
        if (uri == null || uri.isBlank()) return null;
        int hash = uri.lastIndexOf('#');
        return hash >= 0 ? uri.substring(hash + 1) : uri;
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
