package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.SignerDataWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.diagnostic.jaxb.XmlDigestAlgoAndValue;
import eu.europa.esig.dss.diagnostic.jaxb.XmlDigestMatcher;
import eu.europa.esig.dss.diagnostic.jaxb.XmlTimestamp;
import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.DSSUtils;
import eu.europa.esig.dss.validation.reports.Reports;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Důkaz existence prvku, který přichází z <b>obalu</b>: prvek byl předán k ověření z prohlížeče
 * kontejneru („Ověřit v AnEP") a obal nese platné časové razítko nad jeho bajty.
 *
 * <p>EN 319 102-1 V1.4.0 tab. 20 pro to má vstup „time indication for signature existence",
 * který ověřovací službě předává aplikace. AnEP ho používá už pro krytí pozdějším podpisem
 * uvnitř téhož souboru (situace 54); tohle je týž princip s důkazem zvenčí. Návrh a měření:
 * {@code docs/dukaz-existence-z-obalu-navrh.md} (1. 11. 2026). Na sbírkách změnil závěr
 * u kontejnerů ASiC z archivační služby, příloh datových zpráv i příloh PDF.
 *
 * <p>Důkaz dává <b>razítko</b>, ne podpis: neplatný obalový podpis s platným razítkem důkaz
 * dává, podpis bez razítka žádný. Razítko, které samo neobstálo, se nepočítá — o jeho platnosti
 * rozhoduje ověření obalu včetně archivních razítek, která ho drží.
 *
 * @param when         okamžik krycího razítka
 * @param envelopeName jméno obalu, jak ho vidí čtenář
 * @param elementPath  cesta k prvku v obalu
 * @param how          čím razítko prvek kryje, slovy pro čtenáře (věta o dokumentu)
 * @param timestamp    krycí razítko jako položka souhrnu; mezi razítka prvku nepatří
 * @param newest       nejnovější platné razítko obalu — to, na kterém důkaz nakonec stojí.
 *                     Krycí razítko z kontejneru po uchování drží v platnosti archivní razítko
 *                     a horizont ověřitelnosti se musí ptát na to; ptát se na krycí by na měřeném
 *                     kontejneru dalo mez, která už uplynula (certifikát TSU do 9/2025)
 * @param token        krycí razítko v diagnostických datech obalu — pro přepočet
 * @param envelope     celé protokoly ověření obalu — razítka, certifikáty a revokační údaje,
 *                     které platnost krycího razítka nesou
 * @param via          vnitřní obal, z něhož byl dokument předán, leží-li ten sám v obalu, jehož
 *                     razítko důkaz nese (ZFO → ASiC → PDF); jinak {@code null}
 * @param detached     „obalem" je externí (odloučený) podpis dokumentu, ne soubor, v němž dokument
 *                     leží — věta pro čtenáře se pak skládá jinak
 * @param support      důkaz z obalu o úroveň výš, bez kterého by krycí razítko samo už neobstálo
 *                     (razítko kontejneru, jemuž vypršel certifikát TSA, podržené razítkem datové
 *                     zprávy, v níž kontejner leží); {@code null}, když razítko obstálo samo.
 *                     Při přepočtu se připisuje spolu s ním.
 */
public record EnvelopeProof(Instant when, String envelopeName, String elementPath, String how,
                            TimestampEntry timestamp, TimestampEntry newest, XmlTimestamp token,
                            Reports envelope, String via, boolean detached, EnvelopeProof support) {

    public EnvelopeProof(Instant when, String envelopeName, String elementPath, String how,
                         TimestampEntry timestamp, TimestampEntry newest, XmlTimestamp token,
                         Reports envelope, String via, boolean detached) {
        this(when, envelopeName, elementPath, how, timestamp, newest, token, envelope, via,
                detached, null);
    }

    /** Týž důkaz, nesený obalem, v němž leží obal {@code inner} s dokumentem (vnoření). */
    EnvelopeProof through(String inner) {
        String newVia = via == null ? inner : via + " → " + inner;
        String newHow;
        if (via == null) {
            String innerHow = how.startsWith("dokument ")
                    ? "ten " + how.substring("dokument ".length()) : how;
            newHow = "kryje obal " + inner + ", v němž dokument leží (" + innerHow + ")";
        } else {
            newHow = how.replaceFirst("^kryje obal " + java.util.regex.Pattern.quote(via),
                    java.util.regex.Matcher.quoteReplacement("kryje obal " + newVia));
        }
        return new EnvelopeProof(when, envelopeName, elementPath, newHow, timestamp, newest, token,
                envelope, newVia, detached, support);
    }

    /**
     * Týž důkaz, jehož razítko obstálo jen díky důkazu {@code outer} z obalu o úroveň výš.
     * Horizont ověřitelnosti se pak ptá na razítka vnějšího obalu, která celý řetěz drží.
     */
    EnvelopeProof supportedBy(EnvelopeProof outer) {
        return new EnvelopeProof(when, envelopeName, elementPath, how, timestamp,
                outer.newest() != null ? outer.newest() : newest, token, envelope, via, detached,
                outer);
    }

    /**
     * Co prohlížeč obalu předává spolu s prvkem. Bajty prvku jsou ty, které prohlížeč z obalu
     * vyjmul — vazbu na bajty tak není třeba dokazovat znovu, stačí ji najít v obalu.
     *
     * @param detached obalem je externí podpis dokumentu (dokument je jeho odloučeným obsahem);
     *                 obal se pak ověřuje spolu s dokumentem
     * @param parent   jak se prohlížeč dostal k tomuto obalu, je-li sám vnořený v jiném: zdroj,
     *                 v němž je {@code envelopeBytes} prvkem. Razítko vnějšího obalu kryje i vše,
     *                 co leží ve vnitřním (1. 11. 2026). {@code null} u obalu otevřeného přímo.
     */
    public record Source(byte[] envelopeBytes, String envelopeName, String elementPath,
                         byte[] elementBytes, boolean detached, Source parent) {
        public Source(byte[] envelopeBytes, String envelopeName, String elementPath,
                      byte[] elementBytes) {
            this(envelopeBytes, envelopeName, elementPath, elementBytes, false, null);
        }
    }

    /**
     * Nejstarší razítko obalu s indikací PASSED, které prvek kryje; {@code null}, když žádné.
     *
     * <p>Jak se krytí pozná, záleží na druhu obalu — vždy ale podle bajtů, jméno souboru se
     * neporovnává:
     * <ul>
     *   <li><b>ASiC</b> — otisk prvku mezi podepsanými daty razítka (razítko kontejneru nad
     *       manifestem) nebo mezi otisky podpisu, který razítko kryje.</li>
     *   <li><b>obalující podpis</b> (CMS, XAdES, JAdES) — prvek je podepsaným obsahem podpisu,
     *       který razítko kryje, jak ho vydá DSS ({@code getOriginalDocuments}). Otisk tu
     *       porovnat nejde: XAdES podepisuje kanonizovaný {@code ds:Object}, JAdES vstup JWS.</li>
     *   <li><b>datová zpráva</b> (ZFO) — příloha leží v podepsaném obsahu pečeti, zakódovaná
     *       v base64; hledá se tam.</li>
     *   <li><b>PDF s přílohami</b> — příloha se stejným obsahem je už v té revizi dokumentu, kterou
     *       kryje podpis s razítkem nebo dokumentové razítko. Příloha přidaná pozdější
     *       přírůstkovou změnou je krytá jen pozdějšími razítky.</li>
     * </ul>
     *
     * <p>U obalujícího podpisu a zprávy se navíc žádá neporušená integrita podpisu: kdyby obsah
     * po podpisu někdo pozměnil, razítko by krylo jiné bajty, než jaké prohlížeč vyjmul.
     */
    static EnvelopeProof find(Source source, Reports envelope) {
        if (source == null || envelope == null || source.elementBytes() == null) return null;
        DiagnosticData dd = envelope.getDiagnosticData();
        SimpleReport sr = envelope.getSimpleReport();
        if (dd == null || sr == null) return null;
        byte[] data = source.elementBytes();
        ContainerInspector.Kind kind = source.envelopeBytes() == null ? null
                : ContainerInspector.detectKind(source.envelopeBytes());
        if (kind == null) return null;
        Coverage coverage = source.detached() ? detachedCoverage(data) : switch (kind) {
            case ZIP -> asicCoverage(data);
            case CMS, ZFO, XML, JSON -> envelopingCoverage(source, data);
            case PDF -> pdfCoverage(source.envelopeBytes(), data);
            default -> null;
        };
        if (coverage == null) return null;

        TimestampWrapper best = null;
        String bestHow = null;
        for (TimestampWrapper ts : dd.getTimestampList()) {
            if (ts.getProductionTime() == null) continue;
            if (sr.getIndication(ts.getId()) != Indication.PASSED) continue;
            String how = coverage.how(ts);
            if (how == null) continue;
            if (best == null || ts.getProductionTime().before(best.getProductionTime())) {
                best = ts;
                bestHow = how;
            }
        }
        if (best == null) return null;

        TimestampWrapper newest = null;
        for (TimestampWrapper ts : dd.getTimestampList()) {
            if (ts.getProductionTime() == null) continue;
            if (sr.getIndication(ts.getId()) != Indication.PASSED) continue;
            if (newest == null || ts.getProductionTime().after(newest.getProductionTime())) newest = ts;
        }

        XmlTimestamp token = null;
        for (XmlTimestamp t : envelope.getDiagnosticDataJaxb().getUsedTimestamps()) {
            if (best.getId().equals(t.getId())) token = t;
        }
        if (token == null) return null;
        return new EnvelopeProof(best.getProductionTime().toInstant(), source.envelopeName(),
                source.elementPath(), bestHow, ReportMapper.timestampEntry(best, sr),
                ReportMapper.timestampEntry(newest, sr), token, envelope, null, source.detached());
    }

    /**
     * Razítko podání datové zprávy ({@code dmQTimestamp}) — token a otisk, který kryje.
     *
     * <p>Je starší a cennější než razítko pečeti: pečeť s razítkem přidává ISDS až při stažení
     * zprávy, razítko podání vzniká při jejím podání. Kryje otisk obálky {@code dmDm}, a ta
     * obsahuje i písemnosti. Že otisk na obálku sedí, ověřuje {@link IsdsMessageInspector}
     * (údaj „Kontrola otisku zprávy" se znakem ✔); platnost razítka samého, tedy důvěru v TSA
     * a revokaci, musí posoudit DSS — viz {@code ValidatorFactory.withEnvelopeProof}.
     */
    public record Submission(byte[] token, eu.europa.esig.dss.enumerations.DigestAlgorithm algorithm,
                             byte[] imprint) { }

    /**
     * Razítko podání zprávy, je-li prvek její písemností a otisk zprávy souhlasí; jinak
     * {@code null}. Strom se staví týmž inspektorem jako v prohlížeči.
     */
    public static Submission submission(Source source) {
        if (source == null || source.envelopeBytes() == null) return null;
        ContainerInspector.Result tree;
        try {
            tree = ContainerInspector.inspect(source.envelopeBytes(), source.envelopeName());
        } catch (Exception e) {
            return null;
        }
        if (tree == null || tree.kind() != ContainerInspector.Kind.ZFO || tree.root() == null) return null;
        ContainerInspector.Node stamp = findNode(tree.root(), n -> n.getRole() != null
                && n.getRole().contains("dmQTimestamp") && n.getData() != null);
        if (stamp == null) return null;
        String verdict = stamp.getInfo().get("Kontrola otisku zprávy");
        if (verdict == null || !verdict.contains("✔")) return null;
        ContainerInspector.Node element = findNode(tree.root(), n -> n.isAttachment()
                && n.getData() != null && Arrays.equals(n.getData(), source.elementBytes()));
        if (element == null) return null;
        try {
            var token = new org.bouncycastle.tsp.TimeStampToken(
                    new org.bouncycastle.cms.CMSSignedData(stamp.getData()));
            var info = token.getTimeStampInfo();
            return new Submission(stamp.getData(),
                    eu.europa.esig.dss.enumerations.DigestAlgorithm.forOID(
                            info.getMessageImprintAlgOID().getId()),
                    info.getMessageImprintDigest());
        } catch (Exception e) {
            return null;
        }
    }

    /** Důkaz z razítka podání, které DSS ověřilo jako PASSED; jinak {@code null}. */
    static EnvelopeProof fromSubmission(Source source, Reports stamp) {
        if (stamp == null) return null;
        DiagnosticData dd = stamp.getDiagnosticData();
        SimpleReport sr = stamp.getSimpleReport();
        if (dd == null || sr == null) return null;
        for (TimestampWrapper ts : dd.getTimestampList()) {
            if (ts.getProductionTime() == null) continue;
            if (sr.getIndication(ts.getId()) != Indication.PASSED) continue;
            for (XmlTimestamp t : stamp.getDiagnosticDataJaxb().getUsedTimestamps()) {
                if (!ts.getId().equals(t.getId())) continue;
                TimestampEntry entry = ReportMapper.timestampEntry(ts, sr);
                return new EnvelopeProof(ts.getProductionTime().toInstant(), source.envelopeName(),
                        source.elementPath(),
                        "dokument je písemností zprávy, jejíž otisk kryje razítko podání (dmQTimestamp)",
                        entry, entry, t, stamp, null, false);
            }
        }
        return null;
    }

    private static ContainerInspector.Node findNode(ContainerInspector.Node node,
                                                    java.util.function.Predicate<ContainerInspector.Node> test) {
        for (ContainerInspector.Node c : node.getChildren()) {
            if (test.test(c)) return c;
            ContainerInspector.Node deeper = findNode(c, test);
            if (deeper != null) return deeper;
        }
        return null;
    }

    /** Čím razítko prvek kryje, slovy pro čtenáře; {@code null}, když ho razítko nekryje. */
    @FunctionalInterface
    private interface Coverage {
        String how(TimestampWrapper ts);
    }

    /**
     * Externí podpis: otisk dokumentu mezi otisky podpisu, který razítko kryje (CAdES
     * message-digest, reference XAdES bez transformací, sigD u JAdES). Podpis musí být neporušený
     * — obal se kvůli tomu ověřuje spolu s dokumentem.
     */
    private static Coverage detachedCoverage(byte[] data) {
        return ts -> {
            for (SignatureWrapper s : ts.getTimestampedSignatures()) {
                if (!intact(s)) continue;
                for (XmlDigestMatcher m : s.getDigestMatchers()) {
                    if (matches(m, data)) return "otisk dokumentu je v tomto podpisu";
                }
            }
            return null;
        };
    }

    private static Coverage asicCoverage(byte[] data) {
        return ts -> {
            for (SignerDataWrapper sd : ts.getTimestampedSignedData()) {
                if (matches(sd.getDigestAlgoAndValue(), data)) return "dokument je uveden otiskem v manifestu kontejneru";
            }
            for (SignatureWrapper s : ts.getTimestampedSignatures()) {
                for (XmlDigestMatcher m : s.getDigestMatchers()) {
                    if (matches(m, data)) return "dokument je uveden otiskem v podpisu kontejneru, který razítko kryje";
                }
            }
            return null;
        };
    }

    /**
     * Obalující podpis a datová zpráva: podepsaný obsah podpisu, který razítko kryje. Obsah se
     * od DSS vyžádá jednou na obal a podpis, ne pro každé razítko zvlášť.
     */
    private static Coverage envelopingCoverage(Source source, byte[] data) {
        java.util.Map<String, List<byte[]>> originals = new java.util.HashMap<>();
        // Base64 prvku bez zalomení — tak, jak ho zpráva ISDS nese v elementu přílohy, až na
        // zalomení řádků, která se před hledáním z obsahu odstraní.
        String encoded = java.util.Base64.getEncoder().encodeToString(data);
        return ts -> {
            for (SignatureWrapper s : ts.getTimestampedSignatures()) {
                if (!intact(s)) continue;
                List<byte[]> docs = originals.computeIfAbsent(s.getId(),
                        id -> originalDocuments(source, id));
                for (byte[] doc : docs) {
                    if (Arrays.equals(doc, data)) {
                        return "dokument je podepsaným obsahem podpisu, který ho obaluje";
                    }
                }
                for (byte[] doc : docs) {
                    if (doc.length > encoded.length() && containsBase64(doc, encoded)) {
                        return "dokument leží jako příloha v podepsaném obsahu";
                    }
                }
            }
            return null;
        };
    }

    /** PDF s přílohami: razítka revizí, které přílohu se stejným obsahem už obsahují. */
    private static Coverage pdfCoverage(byte[] pdf, byte[] data) {
        java.util.Map<Long, Boolean> holds = new java.util.HashMap<>();
        return ts -> {
            if (ts.getPDFRevision() != null
                    && revisionHolds(pdf, ts.getPDFRevision().getSignatureByteRange(), data, holds)) {
                return "dokument leží jako příloha v té revizi obalu, kterou kryje dokumentové razítko";
            }
            for (SignatureWrapper s : ts.getTimestampedSignatures()) {
                if (s.getPDFRevision() != null
                        && revisionHolds(pdf, s.getPDFRevision().getSignatureByteRange(), data, holds)) {
                    return "dokument leží jako příloha v té revizi obalu, kterou kryje podpis s tímto razítkem";
                }
            }
            return null;
        };
    }

    /** Je v revizi končící ByteRange podpisu příloha se stejným obsahem? */
    private static boolean revisionHolds(byte[] pdf, List<java.math.BigInteger> range, byte[] data,
                                         java.util.Map<Long, Boolean> cache) {
        if (range == null || range.size() != 4) return false;
        long end = range.get(2).longValue() + range.get(3).longValue();
        if (end <= 0 || end > pdf.length) return false;
        return cache.computeIfAbsent(end, e -> {
            byte[] revision = Arrays.copyOf(pdf, (int) (long) e);
            for (PdfAttachmentInspector.Embedded a : PdfAttachmentInspector.embeddedFiles(
                    PdfSource.of(revision), null)) {
                if (Arrays.equals(a.data(), data)) return true;
            }
            return false;
        });
    }

    /** Všechny otisky podpisu nalezené a neporušené. */
    private static boolean intact(SignatureWrapper s) {
        List<XmlDigestMatcher> matchers = s.getDigestMatchers();
        if (matchers == null || matchers.isEmpty()) return false;
        for (XmlDigestMatcher m : matchers) {
            if (!m.isDataFound() || !m.isDataIntact()) return false;
        }
        return true;
    }

    /** Podepsaný obsah podpisu, jak ho vydá DSS; prázdný seznam, když se to nepovede. */
    private static List<byte[]> originalDocuments(Source source, String signatureId) {
        try {
            var validator = eu.europa.esig.dss.validation.SignedDocumentValidator.fromDocument(
                    new eu.europa.esig.dss.model.InMemoryDocument(source.envelopeBytes(),
                            source.envelopeName()));
            validator.setCertificateVerifier(
                    new eu.europa.esig.dss.spi.validation.CommonCertificateVerifier());
            List<byte[]> out = new java.util.ArrayList<>();
            for (var doc : validator.getOriginalDocuments(signatureId)) {
                try (var in = doc.openStream()) {
                    out.add(in.readAllBytes());
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean containsBase64(byte[] doc, String encoded) {
        StringBuilder sb = new StringBuilder(doc.length);
        for (byte x : doc) {
            if (x != '\r' && x != '\n' && x != ' ' && x != '\t') sb.append((char) (x & 0xff));
        }
        return sb.indexOf(encoded) >= 0;
    }

    private static boolean matches(XmlDigestAlgoAndValue dv, byte[] data) {
        if (dv == null || dv.getDigestMethod() == null || dv.getDigestValue() == null) return false;
        try {
            return Arrays.equals(DSSUtils.digest(dv.getDigestMethod(), data), dv.getDigestValue());
        } catch (Exception e) {
            return false;
        }
    }
}
