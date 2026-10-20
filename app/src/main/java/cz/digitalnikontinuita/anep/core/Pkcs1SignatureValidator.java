package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.PdfOrphanSignatureDetector.OrphanSignature;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.enumerations.CertificateQualification;
import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.enumerations.SubIndication;
import eu.europa.esig.dss.enumerations.TimestampType;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CertificateVerifier;
import eu.europa.esig.dss.validation.CertificateValidator;
import eu.europa.esig.dss.validation.reports.CertificateReports;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Plné ověření podpisu {@code adbe.x509.rsa_sha1}, který DSS nezpracuje.
 *
 * <p>Skládá se ze tří částí, v pořadí, v jakém je klade ETSI EN 319 102-1:
 * <ol>
 *   <li><b>Kryptografická kontrola podpisové hodnoty</b> — {@link Pkcs1PdfSignature}.</li>
 *   <li><b>Ověření certifikátu podpisu</b> — řetěz ke kotvě z důvěryhodných seznamů, revokace
 *       a kvalifikovanost. Dělá ho DSS ({@link CertificateValidator}) s týmž
 *       {@link CertificateVerifier} jako hlavní ověření, takže platí tytéž kotvy, totéž
 *       vlastní úložiště CRL i totéž stahování revokačních údajů.</li>
 *   <li><b>Kryptografická omezení</b> — hašovací funkce a délka klíče RSA podle výchozí politiky
 *       DSS (ETSI TS 119 312): SHA-1 od 1. 8. 2012, MD5 od 1. 8. 2004, RSA pod 1 900 bitů od
 *       1. 10. 2019, pod 3 000 bitů od 1. 1. 2027. Kopie dat z {@code policy/constraint.xml}
 *       v {@code dss-policy-jaxb}; SHA-1 respektuje volbu „ignorovat zastaralost SHA-1“.</li>
 * </ol>
 *
 * <p>Kontrolním okamžikem je čas ověření (nebo čas zadaný v nastavení). Kryje-li podpis platné
 * dokumentové časové razítko, sníží se na jeho čas — jde o důkaz existence podle kroku 5.5.4
 * téže normy. Deklarovaný čas podpisu ({@code /M}) se za důkaz nepovažuje, stejně jako u DSS.
 */
public final class Pkcs1SignatureValidator {

    private static final Instant SHA1_EXPIRY = Instant.parse("2012-08-01T00:00:00Z");
    private static final Instant MD5_EXPIRY = Instant.parse("2004-08-01T00:00:00Z");
    private static final Instant RSA_1900_EXPIRY = Instant.parse("2019-10-01T00:00:00Z");
    private static final Instant RSA_3000_EXPIRY = Instant.parse("2027-01-01T00:00:00Z");

    /**
     * Výsledek ověření.
     *
     * @param indication      TOTAL_PASSED / INDETERMINATE / TOTAL_FAILED
     * @param subIndication   jméno sub-indikace, nebo null
     * @param situations      situace ve smyslu {@code ReportMapper.detectFailureSituations}
     * @param controlTime     okamžik, ke kterému se posuzovalo
     * @param proofOfExistence čas dokumentového razítka, které podpis kryje, nebo null
     * @param signingCert     certifikát podpisu, jak ho vidí DSS (řetěz, revokace), nebo null
     * @param qualificationAtIssuance kvalifikace certifikátu k vydání podle DSS, nebo null
     */
    public record Verification(
            String indication,
            String subIndication,
            List<Integer> situations,
            Instant controlTime,
            Instant proofOfExistence,
            CertificateWrapper signingCert,
            CertificateQualification qualificationAtIssuance,
            Pkcs1PdfSignature signature) {
        public Verification {
            situations = List.copyOf(situations);
        }
    }

    private Pkcs1SignatureValidator() { }

    /**
     * Ověří každý osiřelý podpis {@code adbe.x509.rsa_sha1}; ostatní vrátí beze změny.
     *
     * @param dd          diagnostická data hlavního ověření (kvůli dokumentovým razítkům)
     * @param simpleReport týž běh — rozhoduje, které razítko obstálo
     */
    public static List<OrphanSignature> verifyAll(List<OrphanSignature> orphans,
                                                  CertificateVerifier verifier,
                                                  AppSettings settings,
                                                  DiagnosticData dd, SimpleReport simpleReport) {
        List<OrphanSignature> out = new ArrayList<>(orphans.size());
        for (OrphanSignature o : orphans) {
            if (o.pkcs1() == null) { out.add(o); continue; }
            Instant poe = coveringDocumentTimestamp(o.byteRange(), dd, simpleReport);
            out.add(o.withVerification(verify(o.pkcs1(), verifier, settings, poe)));
        }
        return out;
    }

    public static Verification verify(Pkcs1PdfSignature sig, CertificateVerifier verifier,
                                      AppSettings settings, Instant proofOfExistence) {
        Instant validationTime = settings.isUseCustomValidationTime()
                && settings.getCustomValidationTime() != null
                ? settings.getCustomValidationTime().atZone(java.time.ZoneId.systemDefault()).toInstant()
                : Instant.now();
        Instant controlTime = proofOfExistence != null && proofOfExistence.isBefore(validationTime)
                ? proofOfExistence : validationTime;

        // Certifikát se ověřuje, i když podpisová hodnota neobstála: čtenář má vidět, čí podpis
        // to byl a jak je na tom jeho certifikát, a Rozšířený přehled z něj staví blok Certifikát.
        CertificateWrapper certWrapper = null;
        CertificateQualification qualification = null;
        Indication certInd = null;
        SubIndication certSub = null;
        Date certRevokedAt = null;
        if (sig.signingCertificate() != null) {
            try {
                CertificateToken token = new CertificateToken(sig.signingCertificate());
                CertificateValidator cv = CertificateValidator.fromCertificate(token);
                cv.setCertificateVerifier(verifier);
                // K času ověření, ne k důkazu existence: revokační údaj stažený dnes je vůči
                // dřívějšímu okamžiku „z budoucnosti“ a DSS by ho odmítl
                // (REVOCATION_OUT_OF_BOUNDS_NO_POE). Důkaz existence se uplatní až níž.
                cv.setValidationTime(Date.from(validationTime));
                // Táž politika jako u hlavního ověření — jinak by na certifikát nepůsobily
                // ústupky z nastavení (např. revokační údaje vydané po expiraci certifikátu).
                eu.europa.esig.dss.model.policy.ValidationPolicy policy =
                        ValidationPolicyFactory.build(settings);
                CertificateReports reports = policy != null ? cv.validate(policy) : cv.validate();
                String id = token.getDSSIdAsString();
                certInd = reports.getSimpleReport().getCertificateIndication(id);
                certSub = reports.getSimpleReport().getCertificateSubIndication(id);
                certRevokedAt = reports.getSimpleReport().getCertificateRevocationDate(id);
                qualification = reports.getSimpleReport().getQualificationAtCertificateIssuance();
                certWrapper = reports.getDiagnosticData().getCertificateById(id);
            } catch (Exception e) {
                certInd = Indication.INDETERMINATE;
                certSub = SubIndication.CERTIFICATE_CHAIN_GENERAL_FAILURE;
            }
        }

        List<Integer> situations = new ArrayList<>();
        // Obsah nebo podpisová hodnota. Tady se rozhoduje napřed a definitivně: podpis, jehož
        // hodnota neodpovídá obsahu, je neplatný bez ohledu na certifikát.
        if (Boolean.FALSE.equals(sig.intact())) {
            situations.add(1);
            return new Verification("TOTAL_FAILED",
                    sig.digestAlgorithm() == null ? SubIndication.SIG_CRYPTO_FAILURE.name()
                                                  : SubIndication.HASH_FAILURE.name(),
                    situations, controlTime, proofOfExistence, certWrapper, qualification, sig);
        }
        if (sig.intact() == null) {
            return new Verification("INDETERMINATE", SubIndication.FORMAT_FAILURE.name(),
                    situations, controlTime, proofOfExistence, certWrapper, qualification, sig);
        }

        // Ověření v minulosti (EN 319 102-1, čl. 5.6.2.4): certifikát, který k času ověření
        // neobstál jen proto, že mezitím expiroval či byl odvolán, obstojí, dokládá-li důkaz
        // existence, že podpis vznikl ještě před koncem platnosti, resp. před odvoláním.
        if (proofOfExistence != null && certSub != null && certWrapper != null
                && certWrapper.getNotAfter() != null && certWrapper.getNotBefore() != null) {
            Instant notAfter = certWrapper.getNotAfter().toInstant();
            Instant notBefore = certWrapper.getNotBefore().toInstant();
            boolean inValidity = proofOfExistence.isBefore(notAfter)
                    && !proofOfExistence.isBefore(notBefore);
            boolean lifted = switch (certSub) {
                case OUT_OF_BOUNDS_NO_POE, OUT_OF_BOUNDS_NOT_REVOKED -> inValidity;
                case REVOKED_NO_POE -> inValidity && certRevokedAt != null
                        && proofOfExistence.isBefore(certRevokedAt.toInstant());
                default -> false;
            };
            if (lifted) {
                certInd = Indication.PASSED;
                certSub = null;
            }
        }

        // Certifikát.
        if (certSub != null) {
            switch (certSub) {
                case OUT_OF_BOUNDS_NO_POE, OUT_OF_BOUNDS_NOT_REVOKED -> situations.add(3);
                case REVOKED_NO_POE, REVOKED -> situations.add(2);
                case REVOKED_CA_NO_POE -> situations.add(11);
                case NO_CERTIFICATE_CHAIN_FOUND, NO_CERTIFICATE_CHAIN_FOUND_NO_POE ->
                        situations.add(12);
                case NOT_YET_VALID -> situations.add(10);
                case TRY_LATER, REVOCATION_OUT_OF_BOUNDS_NO_POE -> situations.add(9);
                default -> { }
            }
        }
        // Situace 6 hlavní cesty (ReportMapper.hasNewerRevocationAnywhere): revokační údaj
        // o certifikátu podpisu existuje, ale vyšel až po skončení jeho platnosti, a standardy
        // ho proto nepřipouštějí. U starých pečetí PostSignum je to pravidlo — OCSP dnes na
        // prošlý certifikát odpoví „GOOD“, jenže DSS takovou odpověď za údaj o certifikátu
        // nepovažuje (CERTIFICATE_CHAIN_GENERAL_FAILURE) a bez situace zůstal verdikt bez důvodu.
        final CertificateWrapper cw = certWrapper;
        if (cw != null && !settings.isAcceptNewerOcsp()
                && certInd != Indication.PASSED && cw.getNotAfter() != null
                && cw.getCertificateRevocationData().stream().anyMatch(r ->
                        r.getThisUpdate() != null && r.getThisUpdate().after(cw.getNotAfter()))
                && !situations.contains(2)) {
            situations.add(6);
        }

        // Kryptografická omezení.
        boolean cryptoFails = false;
        if ("MD5".equals(sig.digestAlgorithm()) && !controlTime.isBefore(MD5_EXPIRY)) {
            situations.add(16);
            cryptoFails = true;
        }
        if ("SHA-1".equals(sig.digestAlgorithm()) && !controlTime.isBefore(SHA1_EXPIRY)
                && !settings.isIgnoreSha1Deprecated()) {
            situations.add(15);
            cryptoFails = true;
        }
        int bits = sig.keySize();
        if (bits > 0 && ((bits < 1900 && !controlTime.isBefore(RSA_1900_EXPIRY))
                || (bits < 3000 && !controlTime.isBefore(RSA_3000_EXPIRY)))) {
            cryptoFails = true;
        }

        if (certInd == Indication.FAILED || certInd == Indication.TOTAL_FAILED) {
            return new Verification("TOTAL_FAILED", certSub != null ? certSub.name() : null,
                    situations, controlTime, proofOfExistence, certWrapper, qualification, sig);
        }
        if (certInd == null) {
            return new Verification("INDETERMINATE",
                    SubIndication.NO_SIGNING_CERTIFICATE_FOUND.name(),
                    situations, controlTime, proofOfExistence, null, null, sig);
        }
        if (certInd != Indication.PASSED && certInd != Indication.TOTAL_PASSED) {
            return new Verification("INDETERMINATE", certSub != null ? certSub.name() : null,
                    situations, controlTime, proofOfExistence, certWrapper, qualification, sig);
        }
        if (cryptoFails) {
            return new Verification("INDETERMINATE",
                    SubIndication.CRYPTO_CONSTRAINTS_FAILURE_NO_POE.name(),
                    situations, controlTime, proofOfExistence, certWrapper, qualification, sig);
        }
        return new Verification("TOTAL_PASSED", null, situations, controlTime,
                proofOfExistence, certWrapper, qualification, sig);
    }

    /**
     * Čas nejstaršího platného dokumentového razítka, které podpis kryje, nebo null.
     *
     * <p>Kryje ho razítko, jehož revize sahá dál než revize podpisu ({@code /ByteRange} → b + c):
     * PDF se aktualizuje přírůstkově, takže pozdější razítko otiskuje i bajty podpisu před ním.
     */
    static Instant coveringDocumentTimestamp(String orphanByteRange, DiagnosticData dd,
                                             SimpleReport simpleReport) {
        long orphanEnd = byteRangeEnd(orphanByteRange);
        if (orphanEnd < 0 || dd == null || simpleReport == null) return null;
        Instant best = null;
        for (TimestampWrapper ts : dd.getTimestampList()) {
            if (ts.getType() != TimestampType.DOCUMENT_TIMESTAMP) continue;
            if (ts.getProductionTime() == null || ts.getPDFRevision() == null) continue;
            if (simpleReport.getIndication(ts.getId()) != Indication.PASSED) continue;
            List<BigInteger> br = ts.getPDFRevision().getSignatureByteRange();
            if (br == null || br.size() < 4) continue;
            long tsEnd = br.get(2).longValue() + br.get(3).longValue();
            if (tsEnd <= orphanEnd) continue;
            Instant t = ts.getProductionTime().toInstant();
            if (best == null || t.isBefore(best)) best = t;
        }
        return best;
    }

    private static long byteRangeEnd(String byteRange) {
        if (byteRange == null) return -1;
        String[] p = byteRange.split(",");
        if (p.length < 4) return -1;
        try {
            return Long.parseLong(p[2].trim()) + Long.parseLong(p[3].trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
