package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.policy.ValidationPolicy;
import eu.europa.esig.dss.policy.ValidationPolicyFacade;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds a DSS {@link ValidationPolicy} from the bundled default {@code policy/constraint.xml},
 * optionally relaxing constraints based on {@link AppSettings}.
 */
public class ValidationPolicyFactory {

    /**
     * Returns a (possibly modified) validation policy.
     * Returns {@code null} when no modifications are needed — caller uses DSS built-in default.
     */
    public static ValidationPolicy build(AppSettings settings) {
        return build(settings, false);
    }

    /**
     * @param forceRevocationRelaxation also applies the "newer revocation data" relaxation
     *                                  regardless of {@code settings}, e.g. for the "Zkusit
     *                                  ověřit s vlastními CRL" action — a manually supplied
     *                                  CRL is, by definition, usually fetched well after the
     *                                  certificate's own expiry, exactly the case these
     *                                  relaxations exist for.
     */
    public static ValidationPolicy build(AppSettings settings, boolean forceRevocationRelaxation) {
        // Zadané datum ověření uděluje ústupek k post-expiračním revokačním údajům samo — jinak
        // by k datu v minulosti nešlo ověřit nic, protože každý dnes stažený údaj je vůči tehdejším
        // certifikátům post-expirační. Viz ValidatorFactory.validate().
        boolean dateGrants = settings.isUseCustomValidationTime()
                && settings.getValidationDate() != null;
        boolean needOcspRelaxation =
                forceRevocationRelaxation || settings.isAcceptNewerOcsp() || dateGrants;
        // Varianta (d) zúží ústupek udělený datem: RevocationHasInformationAboutCertificate se
        // smí ztlumit (jinak by neprošel ani post-expirační REVOKED), ale AcceptableRevocationDataFound
        // musí zůstat na FAIL — jinak by „nezbyl žádný použitelný údaj" propadlo jako pouhé WARN
        // a řetěz by prošel právě tam, kde má skončit nerozhodně. U zaškrtávátka se nezužuje nic:
        // tam si uživatel ústupek zvolil.
        boolean narrowOcspRelaxation =
                dateGrants && !forceRevocationRelaxation && !settings.isAcceptNewerOcsp();
        // acceptExpiredRevocationSources relaxes RevocationIssuerNotExpired independently of
        // needOcspRelaxation: the two settings cover orthogonal conditions and must not be
        // merged — applyNewerOcspRelaxation intentionally does NOT touch RevocationIssuerNotExpired.
        boolean needExpiredRevocRelaxation = settings.isAcceptExpiredRevocationSources();
        // When PDF-NOT-ETSI is accepted, relax the SAV constraint that PAdES requires but basic
        // PDF signatures (adbe.pkcs7.detached) never carry (message-digest / SignedProperties).
        // When not accepted, no restriction is added — DSS naturally returns INDETERMINATE from
        // the SAV constraint, which matches the behavior of the online DSS reference service.
        boolean needPdfRelaxation = settings.isAcceptPdfNotEtsi();
        // Politika se staví vždy, i bez jediného ústupku: požadavek na atribut signing-time je
        // ztlumený trvale (viz applySigningTimeRelaxation), takže výchozí politika DSS už
        // nikdy není ta, podle které AnEP ověřuje.
        try {
            InputStream defaultXml = ValidationPolicyFacade.class
                    .getResourceAsStream("/policy/constraint.xml");
            if (defaultXml == null) {
                return null;
            }
            // Normalize line endings so string replacements work regardless of file encoding.
            String xml = new String(defaultXml.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace("\r", "\n");

            if (settings.isIgnoreSha1Deprecated()) {
                xml = applySha1Relaxation(xml);
            }
            if (needOcspRelaxation) {
                xml = applyNewerOcspRelaxation(xml, narrowOcspRelaxation);
            }
            if (needExpiredRevocRelaxation) {
                xml = applyExpiredRevocationSourcesRelaxation(xml);
            }
            if (needPdfRelaxation) {
                xml = applyPdfNotEtsiRelaxation(xml);
            }
            xml = applySigningTimeRelaxation(xml);

            try (InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
                return ValidationPolicyFacade.newFacade().getValidationPolicy(in);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extends SHA1 expiration to 2099-01-01 so it is no longer treated as expired.
     * The default constraint.xml has: {@code <Algo Date="2012-08-01">SHA1</Algo>}
     */
    private static String applySha1Relaxation(String xml) {
        return xml.replace(
                "<Algo Date=\"2012-08-01\">SHA1</Algo>",
                "<Algo Date=\"2099-01-01\">SHA1</Algo>");
    }

    /**
     * Demotes to WARN the constraints that cause DSS to reject OCSP responses whose
     * thisUpdate is later than the certificate's expiration date (NotAfter):
     *
     * - RevocationHasInformationAboutCertificate: the core check — DSS verifies that
     *   the OCSP thisUpdate falls within the certificate's validity period. For certs
     *   validated after expiry the only available OCSP has thisUpdate > NotAfter.
     * - AcceptableRevocationDataFound: fails when all available revocation data is
     *   considered "unusable" at the control time — a consequence of the above.
     * - RevocationIssuerValidAtProductionTime: fails when the OCSP signing cert was not
     *   valid at the OCSP production time — can trigger with short-lived OCSP keys.
     */
    private static String applyNewerOcspRelaxation(String xml, boolean narrow) {
        // IGNORE: user has explicitly accepted post-expiry OCSP responses — suppress
        // this constraint entirely so it does not appear in the protocol at all.
        xml = xml.replace(
                "<RevocationHasInformationAboutCertificate Level=\"FAIL\" />",
                "<RevocationHasInformationAboutCertificate Level=\"IGNORE\" />");
        if (!narrow) {
            xml = xml.replace(
                    "<AcceptableRevocationDataFound Level=\"FAIL\" />",
                    "<AcceptableRevocationDataFound Level=\"WARN\" />");
        }
        // NOTE: RevocationTimeAgainstBestSignatureTime is intentionally NOT relaxed here.
        // It is the same policy constraint DSS uses for RevocationDateAfterBestSignatureTimeCheck
        // (ADEST_IRTPTBST) — the check that catches "signing certificate was already revoked
        // before the signature was made" (revocationDate <= bestSignatureTime). Relaxing it
        // would silently let a signature created after its certificate's revocation pass as
        // TOTAL_PASSED, which defeats the whole point of revocation checking.
        //
        // NOTE: BestSignatureTimeBeforeExpirationDateOfSigningCertificate is likewise NOT
        // relaxed. It fails when BST >= cert.NotAfter — i.e. there is no proof the signature
        // existed while the certificate was still valid. For a B-T/B-LTA signature with a
        // genuine timestamp, BST comes from that timestamp and this naturally passes when the
        // timestamp predates expiry; no relaxation is needed. For a B-B signature with no
        // timestamp, BST falls back to "now", so relaxing this would let ANY expired-certificate
        // signature pass as long as a fresh "Good" OCSP can be fetched today — a backdating hole.
        xml = xml.replace(
                "<RevocationIssuerValidAtProductionTime Level=\"FAIL\" />",
                "<RevocationIssuerValidAtProductionTime Level=\"WARN\" />");
        // RevocationIssuerNotExpired is intentionally NOT relaxed here — it is the constraint
        // that blocks embedded revocation from a responder whose cert has since expired, and
        // that is exclusively the domain of acceptExpiredRevocationSources / applyExpiredRevocationSourcesRelaxation.
        // Relaxing it here would cause acceptNewerOcsp to inadvertently accept embedded OCSPs
        // from expired responders, which is a different (and stricter) condition.
        return xml;
    }

    /**
     * Targeted relaxation for "Využij i revokační informace od již expirovaného vydavatele":
     * demotes the constraint that blocks LTV when the OCSP responder's or CRL issuer's own
     * certificate is outside its validity range at the control time — expired, or (validating
     * at a past moment) not yet issued.
     *
     * RevocationIssuerNotExpired drives LTV_ISCKNR_ANS1 — "Current time is not in
     * the validity range of the revocation data issuer's certificate". Demoting it
     * to WARN lets DSS accept embedded revocation data whose issuer cert has since
     * expired, as long as the data itself was valid when produced.
     *
     * The broader OCSP-timing constraints (RevocationHasInformationAboutCertificate,
     * AcceptableRevocationDataFound) are intentionally NOT relaxed here — this
     * setting is only about expired issuers, not about post-expiry OCSP responses.
     */
    private static String applyExpiredRevocationSourcesRelaxation(String xml) {
        return xml.replace(
                "<RevocationIssuerNotExpired Level=\"FAIL\" />",
                "<RevocationIssuerNotExpired Level=\"WARN\" />");
    }

    /**
     * Demotes the SAV constraint that fails for PDF-NOT-ETSI (adbe.pkcs7.detached)
     * signatures: {@code MessageDigestOrSignedPropertiesPresent}.
     *
     * PAdES requires that the PKCS #7 SignedData carries either a {@code message-digest}
     * CMS signed attribute (CAdES-style) or an {@code SignedProperties} XML element
     * (XAdES-style). Plain Adobe PDF signatures use neither — the signed data is the
     * raw PDF byte-range, not a digest in a signed attribute.  DSS enforces this as a
     * hard FAIL, making the SAV building-block non-conclusive and propagating
     * SIG_CONSTRAINTS_FAILURE through LTV and archival validation.
     *
     * Demoting to WARN lets DSS complete the cryptographic and revocation checks and
     * produce a meaningful result (TOTAL_PASSED / INDETERMINATE) instead of a
     * policy-driven failure that says nothing about the cryptographic validity.
     */
    private static String applyPdfNotEtsiRelaxation(String xml) {
        return xml.replace(
                "<MessageDigestOrSignedPropertiesPresent Level=\"FAIL\" />",
                "<MessageDigestOrSignedPropertiesPresent Level=\"WARN\" />");
    }

    /**
     * Demotes {@code <SigningTime Level="FAIL" />} — the requirement that the signature carries
     * a claimed signing time among its signed attributes — to WARN.
     *
     * <p>EN 319 102-1 needs the claimed signing time only for the time-stamp delay check
     * (5.5.3 step 5a), which the same default policy switches off ({@code TimestampDelay
     * Level="IGNORE"}); when the signature is time-stamped, the time-stamp is what places it in
     * time. DSS nevertheless enforces the attribute as a hard FAIL, and also when it is present
     * but encoded in a form DSS cannot read (GeneralizedTime instead of the UTCTime RFC 5652
     * prescribes for 1950–2049). {@code SigningTimeInCertRange} — the claimed time must fall within
     * the signing certificate's validity — is demoted too (decision of 4. 10. 2026): the claimed
     * time comes from the signer's clock and proves nothing, and NCA/I.CA QVerify, a qualified
     * validation service, does not process it at all. Situation 55 notes it instead.
     *
     * <p>Applied always, not as a user-selectable concession (decision of 3. 10. 2026): the
     * attribute is a requirement of the DSS default policy, not a condition of the EN 319 102-1
     * validation process. Measured on
     * D:\tmp and D:\Downloads (1 466 signatures): it changes exactly two of them, both carrying
     * the attribute in a form DSS cannot read. Situation 22 still notes the absence, since
     * validators running the DSS default policy will reject such a signature.
     */
    private static String applySigningTimeRelaxation(String xml) {
        return xml.replace(
                "<SigningTime Level=\"FAIL\" />",
                "<SigningTime Level=\"WARN\" />")
                .replace(
                "<SigningTimeInCertRange Level=\"FAIL\" />",
                "<SigningTimeInCertRange Level=\"WARN\" />");
    }

}
