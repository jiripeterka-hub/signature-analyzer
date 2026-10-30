package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.enumerations.SubIndication;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.spi.validation.CertificateVerifier;
import eu.europa.esig.dss.validation.CertificateValidator;
import eu.europa.esig.dss.validation.reports.CertificateReports;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.tsp.TimeStampToken;

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Podpis kódu (Authenticode) — etapa 3: <b>certifikát podepisujícího a autority razítka</b>.
 * <p>
 * Kotvy důvěry jsou kořeny z úložiště Windows (rozhodnutí uživatele 30. 10. 2026): podpis kódu
 * se k důvěryhodným seznamům EU neváže a programy, které AnEP takto ověřuje, jsou programy pro
 * Windows. Na jiném systému se certifikát neověří a souhrn to řekne; integrita a matematika
 * podpisu (etapa 2) platí všude. Java vidí jen kořeny v počítači skutečně nainstalované —
 * kořen, který by si Windows dotáhly až při první potřebě, AnEP nezná, a nevidí ani omezení
 * účelu, která Windows u kořenů evidují.
 * <p>
 * Řetěz a revokaci ověřuje DSS ({@link CertificateValidator}) k dnešku, stejně jako u podpisů
 * {@code adbe.x509.rsa_sha1} ({@link Pkcs1SignatureValidator}); platné časové razítko pak slouží
 * jako důkaz existence: certifikát, který mezitím vypršel nebo byl zneplatněn až po čase razítka,
 * obstojí (EN 319 102-1, čl. 5.6.2.4). Účel certifikátu (EKU) se kontroluje zvlášť — podpis kódu
 * 1.3.6.1.5.5.7.3.3, razítko 1.3.6.1.5.5.7.3.8; certifikát bez rozšíření EKU Windows berou pro
 * jakýkoli účel.
 * <p>
 * Běží přes síť (revokace, AIA), a proto ho prohlížeč volá až po otevření okna, na pozadí.
 */
public final class AuthenticodeCertificateCheck {

    private AuthenticodeCertificateCheck() {}

    static final String EKU_CODE_SIGNING = "1.3.6.1.5.5.7.3.3";
    static final String EKU_TIME_STAMPING = "1.3.6.1.5.5.7.3.8";

    public static final String KEY_SIGNER = "Certifikát podepisujícího";
    public static final String KEY_TSA = "Certifikát autority razítka";
    public static final String KEY_VERDICT = "Platnost podpisu";
    /** Co souhrn ukazuje, dokud ověření běží. */
    public static final String PENDING = "ověřuje se (řetěz ke kořeni důvěryhodnému ve Windows, revokace)…";

    private static volatile List<CertificateToken> windowsRoots;

    /** Je co ověřovat — podepsaný program s výsledkem etapy 2? */
    public static boolean applies(ContainerInspector.Result r) {
        return r != null && r.kind() == ContainerInspector.Kind.CODE && signature(r) != null;
    }

    private static byte[] signature(ContainerInspector.Result r) {
        if (r.root() == null || r.root().getChildren().isEmpty()) return null;
        ContainerInspector.Node first = r.root().getChildren().get(0);
        return "podpis-authenticode.p7s".equals(first.getName()) ? first.getData() : null;
    }

    /**
     * Řádky souhrnu, které ověření doplní nebo nahradí: certifikát podepisujícího, certifikát
     * autority razítka a celkový verdikt. Nikdy nevyhazuje — co se nepodaří, je „?“.
     */
    public static Map<String, String> run(ContainerInspector.Result r) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            CMSSignedData cms = new CMSSignedData(signature(r));
            SignerInformation si = cms.getSignerInfos().getSigners().iterator().next();
            X509CertificateHolder signer = match(cms, si);

            // Důkaz existence: čas platného razítka (RFC 3161 nebo protipodpis).
            Instant poe = null;
            X509CertificateHolder tsa = null;
            List<CertificateToken> material = new ArrayList<>(tokens(cms));
            AttributeTable unsigned = si.getUnsignedAttributes();
            if (unsigned != null) {
                Attribute rfc = unsigned.get(new ASN1ObjectIdentifier(AuthenticodeInspector.MS_RFC3161_TIMESTAMP));
                Attribute counter = unsigned.get(new ASN1ObjectIdentifier(AuthenticodeInspector.COUNTERSIGNATURE));
                if (rfc != null) {
                    TimeStampToken token = new TimeStampToken(new CMSSignedData(
                            ContentInfo.getInstance(rfc.getAttrValues().getObjectAt(0))));
                    material.addAll(tokens(token.toCMSSignedData()));
                    if (AuthenticodeVerifier.rfc3161(token, si.getSignature()).ok() == Boolean.TRUE) {
                        poe = token.getTimeStampInfo().getGenTime().toInstant();
                        tsa = match(token.toCMSSignedData(), token.toCMSSignedData().getSignerInfos()
                                .getSigners().iterator().next());
                    }
                } else if (counter != null
                        && AuthenticodeVerifier.countersignature(cms, si).ok() == Boolean.TRUE) {
                    SignerInformation csi = si.getCounterSignatures().getSigners().iterator().next();
                    Date t = signingTime(csi);
                    if (t != null) poe = t.toInstant();
                    tsa = match(cms, csi);
                }
            }

            List<CertificateToken> roots = windowsRoots();
            if (roots == null) {
                String why = "? nelze ověřit — na tomto systému není úložiště důvěryhodných kořenů"
                        + " Windows, ke kterému se podpis kódu ověřuje";
                out.put(KEY_SIGNER, why);
                out.put(KEY_VERDICT, "? nerozhodnuto — certifikát podepisujícího nelze na tomto"
                        + " systému ověřit");
                return out;
            }
            CertificateVerifier verifier = TrustConfig.buildAnchoredVerifier(roots, material);

            AuthenticodeVerifier.Check signerCheck = signer == null
                    ? AuthenticodeVerifier.Check.unknown("nelze ověřit — certifikát v podpisu chybí")
                    : evaluate(signer, poe, EKU_CODE_SIGNING, "podepisování kódu", verifier);
            out.put(KEY_SIGNER, mark(signerCheck));
            AuthenticodeVerifier.Check tsaCheck = null;
            if (tsa != null) {
                tsaCheck = evaluate(tsa, poe, EKU_TIME_STAMPING, "časová razítka", verifier);
                out.put(KEY_TSA, mark(tsaCheck));
            }

            out.put(KEY_VERDICT, verdict(r.summary(), signerCheck, tsaCheck, poe));
        } catch (Exception e) {
            out.put(KEY_SIGNER, "? nelze ověřit — " + ContainerInspector.describe(e));
            out.put(KEY_VERDICT, "? nerozhodnuto");
        }
        return out;
    }

    private static String verdict(Map<String, String> summary, AuthenticodeVerifier.Check signer,
                                  AuthenticodeVerifier.Check tsa, Instant poe) {
        String integrity = summary.getOrDefault("Integrita souboru", "?");
        String math = summary.getOrDefault("Matematika podpisu", "?");
        if (integrity.startsWith("✗")) return "✗ neplatný — soubor byl po podpisu změněn";
        if (math.startsWith("✗")) return "✗ neplatný — podpis matematicky nesedí";
        if (signer.ok() == Boolean.FALSE) return "✗ neplatný — certifikát podepisujícího neobstál";
        if (tsa != null && tsa.ok() == Boolean.FALSE) {
            return "✗ neplatný — certifikát autority časového razítka neobstál";
        }
        if (integrity.startsWith("✓") && math.startsWith("✓") && signer.ok() == Boolean.TRUE
                && (tsa == null || tsa.ok() == Boolean.TRUE)) {
            return "✓ platný podpis kódu" + (poe != null
                    ? " — k času časového razítka " + ContainerInspector.formatDate(Date.from(poe))
                    : " — k dnešku (bez časového razítka)");
        }
        return "? nerozhodnuto — část ověření se nepodařilo provést";
    }

    /** Řetěz ke kotvě, revokace, platnost v rozhodném čase a účel certifikátu. */
    static AuthenticodeVerifier.Check evaluate(X509CertificateHolder holder, Instant poe, String eku,
                                               String purpose, CertificateVerifier verifier) {
        try {
            X509Certificate x509 = new JcaX509CertificateConverter().getCertificate(holder);
            List<String> ekus = x509.getExtendedKeyUsage();
            if (ekus != null && !ekus.contains(eku) && !ekus.contains("2.5.29.37.0")) {
                return AuthenticodeVerifier.Check.no("NEPLATNÝ — certifikát není určen pro " + purpose
                        + " (chybí účel " + eku + ")");
            }
            CertificateToken token = new CertificateToken(x509);
            CertificateValidator cv = CertificateValidator.fromCertificate(token);
            cv.setCertificateVerifier(verifier);
            // K dnešku, ne k důkazu existence: revokační údaj stažený dnes by byl vůči dřívějšímu
            // okamžiku „z budoucnosti“ (viz Pkcs1SignatureValidator). Důkaz existence až níž.
            Instant now = Instant.now();
            cv.setValidationTime(Date.from(now));
            CertificateReports reports = cv.validate();
            String id = token.getDSSIdAsString();
            Indication ind = reports.getSimpleReport().getCertificateIndication(id);
            SubIndication sub = reports.getSimpleReport().getCertificateSubIndication(id);
            Date revokedAt = reports.getSimpleReport().getCertificateRevocationDate(id);
            CertificateWrapper w = reports.getDiagnosticData().getCertificateById(id);
            String anchor = anchorName(w);

            Instant at = poe != null ? poe : now;
            boolean inValidity = !at.isBefore(x509.getNotBefore().toInstant())
                    && at.isBefore(x509.getNotAfter().toInstant());
            if (sub != null && poe != null) {
                boolean lifted = switch (sub) {
                    case OUT_OF_BOUNDS_NO_POE, OUT_OF_BOUNDS_NOT_REVOKED -> inValidity;
                    case REVOKED_NO_POE -> inValidity && revokedAt != null && poe.isBefore(revokedAt.toInstant());
                    default -> false;
                };
                if (lifted) { ind = Indication.PASSED; sub = null; }
            }
            // Revokační údaj vydaný po vypršení certifikátu DSS nebere za výpověď o něm (RFC 5280
            // dovoluje prošlý certifikát z CRL vyřadit) a končí CERTIFICATE_CHAIN_GENERAL_FAILURE.
            // U podpisu kódu to neplatí: Baseline Requirements for Code Signing (CA/B Forum,
            // v3.11.0, oddíly 7.2 a 4.10.1) ukládají ponechat zneplatněný certifikát v CRL
            // i v odpovědích OCSP nejméně 10 let po jeho vypršení. Bez tohoto pravidla končilo
            // „nelze rozhodnout“ 145 z 207 programů, které Windows přijímají (30. 10. 2026).
            String postExpiry = null;
            if (sub == SubIndication.CERTIFICATE_CHAIN_GENERAL_FAILURE && anchor != null && w != null) {
                Instant notAfter = x509.getNotAfter().toInstant();
                Instant horizon = notAfter.atZone(java.time.ZoneOffset.UTC).plusYears(10).toInstant();
                Boolean good = null;
                boolean beyondHorizon = false;
                for (var rv : w.getCertificateRevocationData()) {
                    if (rv.getThisUpdate() != null && rv.getThisUpdate().toInstant().isAfter(horizon)) {
                        beyondHorizon = true;
                    }
                    if (rv.getThisUpdate() == null || rv.getThisUpdate().toInstant().isAfter(horizon)) continue;
                    if (rv.getStatus() != null && rv.getStatus().isRevoked()) {
                        Date when = rv.getRevocationDate();
                        boolean before = when == null || poe == null || !poe.isBefore(when.toInstant());
                        if (before) {
                            return AuthenticodeVerifier.Check.no("NEPLATNÝ — " + describe(SubIndication.REVOKED, when));
                        }
                        revokedAt = when;
                        good = Boolean.TRUE;
                    } else if (rv.getStatus() != null && rv.getStatus().isGood()) {
                        good = Boolean.TRUE;
                    }
                }
                if (good == Boolean.TRUE) {
                    ind = Indication.PASSED;
                    sub = null;
                    postExpiry = " (revokační údaj vydán po vypršení certifikátu; autorita ho podle"
                            + " pravidel CA/B Fóra pro podpis kódu musí vést ještě 10 let)";
                } else if (beyondHorizon) {
                    return AuthenticodeVerifier.Check.unknown("nelze rozhodnout — certifikát vypršel"
                            + " " + ContainerInspector.formatDate(x509.getNotAfter()) + " a dostupné"
                            + " revokační údaje vyšly víc než 10 let poté, kdy už je autorita vést"
                            + " nemusí; o zneplatnění nevypovídají");
                }
            }
            if (!inValidity) {
                return AuthenticodeVerifier.Check.no("NEPLATNÝ — certifikát " + (poe != null
                        ? "v čase časového razítka" : "dnes") + " neplatí (platnost "
                        + ContainerInspector.formatDate(x509.getNotBefore()) + " – "
                        + ContainerInspector.formatDate(x509.getNotAfter()) + ")");
            }
            if (ind == Indication.PASSED || ind == Indication.TOTAL_PASSED) {
                String chain = anchor != null ? "řetěz vede ke kořeni „" + anchor + "“ důvěryhodnému ve Windows"
                        : "řetěz vede ke kořeni důvěryhodnému ve Windows";
                return AuthenticodeVerifier.Check.yes("platný — " + chain
                        + (revokedAt != null ? "; zneplatněn až " + ContainerInspector.formatDate(revokedAt)
                                + ", po čase razítka" : "; nezneplatněn")
                        + (postExpiry != null ? postExpiry : ""));
            }
            String text = describe(sub, revokedAt);
            return ind == Indication.FAILED || ind == Indication.TOTAL_FAILED
                    || sub == SubIndication.NO_CERTIFICATE_CHAIN_FOUND
                    || sub == SubIndication.NO_CERTIFICATE_CHAIN_FOUND_NO_POE
                    || sub == SubIndication.REVOKED_NO_POE || sub == SubIndication.REVOKED
                    ? AuthenticodeVerifier.Check.no("NEPLATNÝ — " + text)
                    : AuthenticodeVerifier.Check.unknown("nelze rozhodnout — " + text);
        } catch (Exception e) {
            return AuthenticodeVerifier.Check.unknown("nelze ověřit — " + ContainerInspector.describe(e));
        }
    }

    private static String describe(SubIndication sub, Date revokedAt) {
        if (sub == null) return "ověření certifikátu neskončilo kladně";
        return switch (sub) {
            case NO_CERTIFICATE_CHAIN_FOUND, NO_CERTIFICATE_CHAIN_FOUND_NO_POE ->
                    "řetěz nevede k žádnému kořeni, kterému tento počítač ve Windows důvěřuje";
            case REVOKED, REVOKED_NO_POE -> "certifikát byl zneplatněn"
                    + (revokedAt != null ? " " + ContainerInspector.formatDate(revokedAt) : "")
                    + ", tedy před časem, který podpis dokládá";
            case REVOKED_CA_NO_POE -> "certifikát některé z autorit v řetězu byl zneplatněn";
            case TRY_LATER, REVOCATION_OUT_OF_BOUNDS_NO_POE ->
                    "chybí použitelné revokační údaje (je počítač připojen k internetu?)";
            case OUT_OF_BOUNDS_NO_POE, OUT_OF_BOUNDS_NOT_REVOKED ->
                    "certifikát dnes neplatí a podpis nekryje platné časové razítko";
            case CRYPTO_CONSTRAINTS_FAILURE, CRYPTO_CONSTRAINTS_FAILURE_NO_POE ->
                    "řetěz používá algoritmus, který se už nepovažuje za spolehlivý";
            case CERTIFICATE_CHAIN_GENERAL_FAILURE -> "řetěz certifikátů se nepodařilo ověřit";
            default -> "DSS: " + sub.name();
        };
    }

    private static String anchorName(CertificateWrapper w) {
        if (w == null) return null;
        List<CertificateWrapper> chain = w.getCertificateChain();
        CertificateWrapper last = chain == null || chain.isEmpty() ? w : chain.get(chain.size() - 1);
        return last.isTrusted() ? last.getCommonName() : null;
    }

    private static String mark(AuthenticodeVerifier.Check c) {
        return (c.ok() == null ? "? " : c.ok() ? "✓ " : "✗ ") + c.text();
    }

    private static X509CertificateHolder match(CMSSignedData cms, SignerInformation si) {
        for (Object o : cms.getCertificates().getMatches(si.getSID())) {
            if (o instanceof X509CertificateHolder h) return h;
        }
        return null;
    }

    private static List<CertificateToken> tokens(CMSSignedData cms) {
        List<CertificateToken> out = new ArrayList<>();
        JcaX509CertificateConverter conv = new JcaX509CertificateConverter();
        for (Object o : cms.getCertificates().getMatches(null)) {
            try {
                if (o instanceof X509CertificateHolder h) out.add(new CertificateToken(conv.getCertificate(h)));
            } catch (Exception ignored) {
                // Nečitelný certifikát řetězu nepomůže; vynechat.
            }
        }
        return out;
    }

    private static Date signingTime(SignerInformation csi) {
        try {
            Attribute st = csi.getSignedAttributes().get(new ASN1ObjectIdentifier("1.2.840.113549.1.9.5"));
            return org.bouncycastle.asn1.cms.Time.getInstance(st.getAttrValues().getObjectAt(0)).getDate();
        } catch (Exception e) {
            return null;
        }
    }

    /** Kořeny z úložiště Windows, nebo {@code null} na jiném systému či když se nedají přečíst. */
    static List<CertificateToken> windowsRoots() {
        List<CertificateToken> cached = windowsRoots;
        if (cached != null) return cached.isEmpty() ? null : cached;
        List<CertificateToken> out = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            try {
                KeyStore ks = KeyStore.getInstance("Windows-ROOT");
                ks.load(null, null);
                for (String alias : Collections.list(ks.aliases())) {
                    if (ks.getCertificate(alias) instanceof X509Certificate c) out.add(new CertificateToken(c));
                }
            } catch (Exception e) {
                out.clear();
            }
        }
        windowsRoots = out;
        return out.isEmpty() ? null : out;
    }
}
