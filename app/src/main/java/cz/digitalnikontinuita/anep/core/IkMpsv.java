package cz.digitalnikontinuita.anep.core;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Identifikátor klienta MPSV zapsaný v certifikátu.
 *
 * <p>Leží v rozšíření <b>alternativní jméno předmětu</b> (subjectAltName) jako položka
 * {@code otherName} s vlastním OID {@code 1.3.6.1.4.1.11801.2.1}. Hodnotou je desetimístné
 * číslo uložené jako řetězec ASN.1 — v praxi {@code 0C 0A} (UTF8String délky 10) a za tím
 * deset číslic v ASCII, takže třeba {@code 31 36 38 30 39 33 37 35 39 39} je 1680937599.
 *
 * <p><b>Značka řetězce se nekontroluje.</b> Norma připouští víc druhů ({@code 0C} UTF8String,
 * {@code 13} PrintableString a další) a na tom, který z nich certifikát použil, tady nezáleží:
 * hledá se délka 10 a za ní deset číslic. Pravidlo se tím nestane volnějším, než má být —
 * desetimístné číslo na tomhle místě je právě ten identifikátor.
 *
 * <p>Třída sedí v {@code core}, protože se na totéž ptají dvě místa: {@link ReportMapper}
 * kvůli řádku „IK MPSV" v Rozšířeném přehledu a {@link ContainerInspector} kvůli výpisu
 * v prohlížeči certifikátů. Dvě kopie téhož pravidla by se dřív nebo později rozešly — táž
 * úvaha jako u {@link IdentityDocument}.
 */
public final class IkMpsv {

    private IkMpsv() { }

    /** OID, pod kterým MPSV identifikátor do alternativního jména zapisuje. */
    public static final String OID = "1.3.6.1.4.1.11801.2.1";

    /** Kolik číslic identifikátor má; podle toho se v hodnotě pozná. */
    private static final int DIGITS = 10;

    /**
     * Identifikátor z certifikátu, nebo {@code null}, nenese-li jej — což je většina
     * certifikátů, takže prázdný výsledek není nález.
     *
     * @param certDer bajty certifikátu; {@code null} i poškozené vrátí {@code null}
     */
    public static String of(byte[] certDer) {
        if (certDer == null) return null;
        try {
            return of(new X509CertificateHolder(certDer));
        } catch (Exception malformed) {
            return null;
        }
    }

    /** Totéž nad už přečteným certifikátem — prohlížeč ho má po ruce a znovu ho rozebírat nemusí. */
    public static String of(X509CertificateHolder cert) {
        if (cert == null) return null;
        try {
            Extension san = cert.getExtension(Extension.subjectAlternativeName);
            if (san == null) return null;
            GeneralNames names = GeneralNames.getInstance(san.getParsedValue());
            ASN1ObjectIdentifier wanted = new ASN1ObjectIdentifier(OID);

            for (GeneralName name : names.getNames()) {
                if (name.getTagNo() != GeneralName.otherName) continue;
                ASN1Sequence seq = ASN1Sequence.getInstance(name.getName());
                if (seq.size() < 2) continue;
                if (!wanted.equals(ASN1ObjectIdentifier.getInstance(seq.getObjectAt(0)))) continue;

                // Hodnota bývá ještě zabalená v [0], takže se místo rozbalování prohledají
                // její bajty: za značkou řetězce stojí délka 10 a za ní deset číslic.
                byte[] value = seq.getObjectAt(1).toASN1Primitive().getEncoded();
                for (int i = 0; i + 2 + DIGITS <= value.length; i++) {
                    if ((value[i + 1] & 0xFF) != DIGITS) continue;
                    String digits = new String(Arrays.copyOfRange(value, i + 2, i + 2 + DIGITS),
                            StandardCharsets.US_ASCII);
                    if (digits.chars().allMatch(Character::isDigit)) return digits;
                }
            }
        } catch (Exception malformed) {
            // Vadné rozšíření znamená, že identifikátor nemáme — ne že se má zbytek výpisu vzdát.
        }
        return null;
    }
}
