package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.StructureNode.Kind;
import cz.digitalnikontinuita.anep.core.StructureNode.Severity;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import eu.europa.esig.dss.diagnostic.CertificateRevocationWrapper;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.RevocationWrapper;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.simplereport.SimpleReport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Výklad podpisové struktury podle výsledku ověření: u každého certifikátu, CRL, odpovědi OCSP,
 * podpisu a razítka, které se v souboru najde, řekne, co s ním udělal DSS.
 *
 * <p>Rozhodnutí uživatele z 13. 10. 2026 (otázka 1: „ano“). Inspektor sám čte soubor nezávisle
 * na DSS a odborníka zajímá právě rozdíl: které položky {@code /DSS} ověřování použilo, které
 * odmítlo a které tam leží zbytečně. Párování je podle bajtů — certifikát podle SHA-256 svého DER,
 * odpověď OCSP podle vnitřní {@code BasicOCSPResponse} (CMS ji nese holou, PDF v obálce), podpis
 * podle hodnoty podpisu, razítko podle celého tokenu.
 *
 * <p>Poznámky jsou vždy označené jako výklad ({@link StructureNode.Note#fromValidation()}), aby
 * se v detailu nemísily se zjištěními o souboru. Výsledek podpisu se bere ze souhrnu AnEP
 * ({@link SignatureSummary}), ne ze surového DSS — týž podpis se tu má jmenovat stejně jako
 * v ostatních záložkách.
 */
public final class StructureAnnotations {

    private StructureAnnotations() {}

    /**
     * Podklady z ověření.
     *
     * @param summaries         souhrny AnEP podle id prvku
     * @param simple            zjednodušená zpráva DSS (výsledek razítek), může být {@code null}
     * @param usedRevocationByCert id certifikátu → id revokačního údaje, o který se závěr opřel
     * @param rejectedRevocations  id revokačního údaje → důvody, proč ho DSS nepřijal
     */
    public record Context(DiagnosticData dd, Map<String, SignatureSummary> summaries, SimpleReport simple,
                          Map<String, String> usedRevocationByCert, Map<String, List<String>> rejectedRevocations) {}

    public static void apply(List<StructureNode> roots, Context ctx) {
        if (ctx == null || ctx.dd() == null) return;
        DiagnosticData dd = ctx.dd();

        Map<String, CertificateWrapper> certs = new HashMap<>();
        for (CertificateWrapper c : dd.getUsedCertificates()) {
            if (c.getBinaries() != null) certs.putIfAbsent(CmsStructure.certKey(c.getBinaries()), c);
        }
        Map<String, RevocationWrapper> revocations = new HashMap<>();
        for (RevocationWrapper r : dd.getAllRevocationData()) {
            String k = revocationKey(r);
            if (k != null) revocations.putIfAbsent(k, r);
        }
        // Osiřelé prvky: DSS je v souboru našel, ale k žádnému řetězu ani certifikátu je nepřiřadil.
        // Bez nich hlásila sonda 13. 10. 2026 u uchovaných kontejnerů ASiC, že DSS položku
        // „nemá“, ačkoli ji měl — jen mezi osiřelými.
        Set<String> orphans = new java.util.HashSet<>();
        for (var o : dd.getAllOrphanCertificateObjects()) {
            if (o.getBinaries() != null) orphans.add(CmsStructure.certKey(o.getBinaries()));
        }
        for (var o : dd.getAllOrphanRevocationObjects()) {
            byte[] b = o.getBinaries();
            if (b == null) continue;
            if (o.getRevocationType() != null && "OCSP".equals(o.getRevocationType().name())) {
                var basic = CmsStructure.basicOcsp(b);
                try {
                    if (basic != null) orphans.add(CmsStructure.ocspKey(basic.getEncoded("DER")));
                } catch (Exception ignored) { }
            } else {
                orphans.add(CmsStructure.crlKey(b));
            }
        }
        Map<String, SignatureWrapper> signatures = new HashMap<>();
        for (SignatureWrapper s : dd.getSignatures()) {
            if (s.getSignatureValue() != null) {
                signatures.putIfAbsent(CmsStructure.signatureValueKey(s.getSignatureValue()), s);
            }
        }
        Map<String, TimestampWrapper> timestamps = new HashMap<>();
        for (TimestampWrapper t : dd.getTimestampList()) {
            if (t.getBinaries() != null) timestamps.putIfAbsent(CmsStructure.tstKey(t.getBinaries()), t);
        }

        // Role certifikátů: kde je DSS v řetězech použil.
        Map<String, List<String>> roles = new LinkedHashMap<>();
        for (SignatureWrapper s : dd.getSignatures()) {
            String who = tokenLabel(s.getId(), ctx, "podpisu");
            chainRoles(roles, s.getSigningCertificate(), s.getCertificateChain(), who);
        }
        for (TimestampWrapper t : dd.getTimestampList()) {
            chainRoles(roles, t.getSigningCertificate(), t.getCertificateChain(), "razítka z "
                    + ContainerInspector.formatDate(t.getProductionTime()));
        }
        for (RevocationWrapper r : dd.getAllRevocationData()) {
            if (r.getSigningCertificate() != null) {
                roles.computeIfAbsent(r.getSigningCertificate().getId(), k -> new ArrayList<>())
                        .add("vydavatel " + (isOcsp(r) ? "odpovědi OCSP" : "seznamu CRL"));
            }
        }
        // Revokační údaje podle id → certifikáty, k nimž se vztahují.
        Map<String, List<String>> revocationFor = new HashMap<>();
        for (CertificateWrapper c : dd.getUsedCertificates()) {
            for (CertificateRevocationWrapper crw : c.getCertificateRevocationData()) {
                revocationFor.computeIfAbsent(crw.getId(), k -> new ArrayList<>())
                        .add(name(c) + " — " + statusLabel(crw));
            }
        }
        Map<String, String> certByUsedRevocation = new HashMap<>();
        if (ctx.usedRevocationByCert() != null) {
            for (var e : ctx.usedRevocationByCert().entrySet()) {
                CertificateWrapper c = dd.getUsedCertificateById(e.getKey());
                certByUsedRevocation.merge(e.getValue(), c != null ? name(c) : e.getKey(), (a, b) -> a + ", " + b);
            }
        }

        Walker w = new Walker(ctx, certs, revocations, signatures, timestamps, roles, revocationFor,
                certByUsedRevocation, orphans,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        for (StructureNode r : roots) w.walk(r);
    }

    private record Walker(Context ctx, Map<String, CertificateWrapper> certs,
                          Map<String, RevocationWrapper> revocations, Map<String, SignatureWrapper> signatures,
                          Map<String, TimestampWrapper> timestamps, Map<String, List<String>> roles,
                          Map<String, List<String>> revocationFor, Map<String, String> certByUsedRevocation,
                          Set<String> orphans, Set<StructureNode> visited) {

        void walk(StructureNode n) {
            // Razítko vnořené v podpisu visí i ve skupině „Časová razítka“ — týž uzel dvakrát.
            if (!visited.add(n)) return;
            String k = n.dssKey();
            if (k != null) {
                switch (n.kind()) {
                    case CERTIFICATE -> certificate(n, certs.get(k));
                    case CRL, OCSP -> revocation(n, revocations.get(k));
                    case SIGNER, SIGNATURE -> signature(n, signatures.get(k));
                    case TIMESTAMP, DOC_TIMESTAMP -> timestamp(n, timestamps.get(k));
                    default -> { }
                }
            }
            for (StructureNode c : n.children()) walk(c);
        }

        void certificate(StructureNode n, CertificateWrapper c) {
            if (c == null) {
                n.validationNote(Severity.INFO, orphans.contains(n.dssKey())
                        ? "DSS certifikát v souboru našel, ale nepoužil ho (osiřelý prvek)."
                        : "DSS tento certifikát mezi použitými nemá.");
                return;
            }
            List<String> r = roles.getOrDefault(c.getId(), List.of());
            if (c.isTrusted()) n.validationNote(Severity.OK, "DSS: kotva důvěry (certifikát z důvěryhodného seznamu"
                    + " či vlastního úložiště).");
            if (r.isEmpty()) {
                n.validationNote(Severity.INFO, "DSS tento certifikát v žádném řetězu nepoužil.");
            } else {
                n.validationNote(Severity.INFO, "DSS: " + String.join("; ", r) + ".");
            }
        }

        void revocation(StructureNode n, RevocationWrapper r) {
            if (r == null) {
                n.validationNote(Severity.INFO, orphans.contains(n.dssKey())
                        ? "DSS údaj v souboru našel, ale k žádnému certifikátu ho nepřiřadil (osiřelý prvek)."
                        : "DSS tento revokační údaj mezi diagnostickými daty nemá.");
                return;
            }
            List<String> about = revocationFor.getOrDefault(r.getId(), List.of());
            if (!about.isEmpty()) {
                n.validationNote(Severity.INFO, "DSS: údaj o certifikátu " + String.join("; ", about) + ".");
            }
            String used = certByUsedRevocation.get(r.getId());
            if (used != null) {
                n.validationNote(Severity.OK, "Závěr o stavu certifikátu " + used + " se opřel o tento údaj.");
            }
            List<String> rejected = ctx.rejectedRevocations() != null
                    ? ctx.rejectedRevocations().get(r.getId()) : null;
            if (rejected != null && !rejected.isEmpty()) {
                n.validationNote(Severity.WARNING, "DSS tento údaj nepřijal: " + String.join("; ", rejected));
            } else if (about.isEmpty() && used == null) {
                n.validationNote(Severity.INFO, "DSS tento údaj k žádnému certifikátu nepřiřadil.");
            }
        }

        void signature(StructureNode n, SignatureWrapper s) {
            if (s == null) {
                if (n.kind() == Kind.SIGNATURE) {
                    n.validationNote(Severity.WARNING, "DSS tento podpis mezi ověřenými nemá.");
                }
                return;
            }
            SignatureSummary sum = ctx.summaries() != null ? ctx.summaries().get(s.getId()) : null;
            String ind = sum != null ? sum.getIndication() : ctx.simple() != null
                    ? String.valueOf(ctx.simple().getIndication(s.getId())) : null;
            String sub = sum != null ? sum.getSubIndication() : ctx.simple() != null && ctx.simple().getSubIndication(s.getId()) != null
                    ? String.valueOf(ctx.simple().getSubIndication(s.getId())) : null;
            n.validationNote(severity(ind), "Výsledek ověření: " + indicationLabel(ind, sub)
                    + (s.getSignatureFormat() != null ? ", formát " + s.getSignatureFormat() : "")
                    + " (id " + s.getId() + ").");
            if (n.kind() == Kind.SIGNATURE && sum != null && n.title().startsWith("Podpis")) {
                String word = sum.isMark() ? "Značka" : sum.isSeal() ? "Pečeť" : "Podpis";
                n.title(word + n.title().substring("Podpis".length()));
            }
        }

        void timestamp(StructureNode n, TimestampWrapper t) {
            if (t == null) {
                n.validationNote(Severity.INFO, "DSS toto razítko mezi ověřenými nemá.");
                return;
            }
            SignatureSummary sum = ctx.summaries() != null ? ctx.summaries().get(t.getId()) : null;
            String ind = sum != null ? sum.getIndication() : ctx.simple() != null && ctx.simple().getIndication(t.getId()) != null
                    ? String.valueOf(ctx.simple().getIndication(t.getId())) : null;
            String sub = sum != null ? sum.getSubIndication() : ctx.simple() != null && ctx.simple().getSubIndication(t.getId()) != null
                    ? String.valueOf(ctx.simple().getSubIndication(t.getId())) : null;
            n.validationNote(severity(ind), "Výsledek ověření razítka: " + indicationLabel(ind, sub)
                    + (t.getType() != null ? ", druh " + t.getType() : "") + " (id " + t.getId() + ").");
        }
    }

    private static void chainRoles(Map<String, List<String>> roles, CertificateWrapper signing,
                                   List<CertificateWrapper> chain, String who) {
        if (signing != null) {
            roles.computeIfAbsent(signing.getId(), k -> new ArrayList<>()).add("podpisový certifikát " + who);
        }
        if (chain == null) return;
        Set<String> seen = new java.util.HashSet<>();
        for (CertificateWrapper c : chain) {
            if (signing != null && c.getId().equals(signing.getId())) continue;
            if (seen.add(c.getId())) {
                roles.computeIfAbsent(c.getId(), k -> new ArrayList<>()).add("v řetězu " + who);
            }
        }
    }

    private static String tokenLabel(String id, Context ctx, String genitive) {
        SignatureSummary s = ctx.summaries() != null ? ctx.summaries().get(id) : null;
        if (s != null && s.isSeal()) genitive = "pečeti";
        return genitive + " " + id;
    }

    private static String revocationKey(RevocationWrapper r) {
        byte[] b = r.getBinaries();
        if (b == null) return null;
        if (isOcsp(r)) {
            var basic = CmsStructure.basicOcsp(b);
            try {
                return basic != null ? CmsStructure.ocspKey(basic.getEncoded("DER")) : null;
            } catch (Exception e) {
                return null;
            }
        }
        return CmsStructure.crlKey(b);
    }

    private static boolean isOcsp(RevocationWrapper r) {
        return r.getRevocationType() != null && "OCSP".equals(r.getRevocationType().name());
    }

    private static String name(CertificateWrapper c) {
        String cn = c.getCommonName();
        return cn != null && !cn.isBlank() ? cn : c.getId();
    }

    private static String statusLabel(CertificateRevocationWrapper crw) {
        if (crw.getStatus() == null) return "stav neuveden";
        return switch (crw.getStatus()) {
            case GOOD -> "platný";
            case REVOKED -> "zneplatněný" + (crw.getRevocationDate() != null
                    ? " k " + ContainerInspector.formatDate(crw.getRevocationDate()) : "");
            default -> "stav neznámý";
        };
    }

    private static Severity severity(String indication) {
        if (indication == null) return Severity.INFO;
        return switch (indication) {
            case "TOTAL_PASSED", "PASSED" -> Severity.OK;
            case "TOTAL_FAILED", "FAILED" -> Severity.ERROR;
            default -> Severity.WARNING;
        };
    }

    private static String indicationLabel(String ind, String sub) {
        if (ind == null) return "neuveden";
        String word = switch (ind) {
            case "TOTAL_PASSED", "PASSED" -> "platný";
            case "TOTAL_FAILED", "FAILED" -> "neplatný";
            case "INDETERMINATE" -> "neurčitý";
            default -> ind;
        };
        return word + " (" + ind + (sub != null && !sub.isBlank() && !"null".equals(sub) ? " / " + sub : "") + ")";
    }
}
