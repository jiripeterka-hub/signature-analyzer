package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.ReportMapper.ChainRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jak se ve vysvětlení lhůty jmenuje certifikát, který ji ukládá.
 *
 * <p>Do 7. 9. 2026 stálo ve všech případech „Lhůtu určuje certifikát Jan Novák". To čtenáři
 * neřeklo, který ze čtyř certifikátů v řetězu to je — a u řetězu časového razítka to jméno
 * vypadalo jako jméno podepsané osoby. Věta se proto řídí rolí řetězu a pozicí v něm, a tenhle
 * test hlídá, že se žádná z rolí nezačne jmenovat jako jiná.
 */
class CertRoleWordingTest {

    private static final String OSOBA = "Jan Novák";

    @Test
    @DisplayName("Koncový certifikát se jmenuje podle toho, čí řetěz to je")
    void leafIsNamedByRole() {
        assertEquals("podpisový certifikát (držitele " + OSOBA + ")",
                ReportMapper.certRole(ChainRole.SIGNATURE, true, OSOBA));
        assertEquals("certifikát pečeti (držitele Město Brno)",
                ReportMapper.certRole(ChainRole.SEAL, true, "Město Brno"));
        assertEquals("certifikát razítkovací autority (I.CA TSU)",
                ReportMapper.certRole(ChainRole.TIMESTAMP, true, "I.CA TSU"));
        assertEquals("certifikát vydavatele revokačních informací (PostSignum OCSP)",
                ReportMapper.certRole(ChainRole.REVOCATION_ISSUER, true, "PostSignum OCSP"));
    }

    @Test
    @DisplayName("Nad koncovým certifikátem je vždy autorita, ať je řetěz čí chce")
    void everythingAboveTheLeafIsAnAuthority() {
        for (ChainRole role : ChainRole.values()) {
            assertEquals("certifikát certifikační autority (I.CA Root)",
                    ReportMapper.certRole(role, false, "I.CA Root"),
                    "Certifikát nad koncovým nesmí dostat jméno podle role: " + role);
        }
    }

    @Test
    @DisplayName("Šestý pád se od prvního liší jen tvarem, ne obsahem")
    void locativeMatchesNominative() {
        assertEquals("podpisovém certifikátu (držitele " + OSOBA + ")",
                ReportMapper.certRoleLocative(ChainRole.SIGNATURE, true, OSOBA));
        assertEquals("certifikátu pečeti (držitele Město Brno)",
                ReportMapper.certRoleLocative(ChainRole.SEAL, true, "Město Brno"));
        assertEquals("certifikátu razítkovací autority (I.CA TSU)",
                ReportMapper.certRoleLocative(ChainRole.TIMESTAMP, true, "I.CA TSU"));
        assertEquals("certifikátu certifikační autority (I.CA Root)",
                ReportMapper.certRoleLocative(ChainRole.TIMESTAMP, false, "I.CA Root"));
        // Jméno držitele projde oběma tvary beze změny — jinak by se dvě věty o témže
        // certifikátu rozešly v tom, koho jmenují.
        for (ChainRole role : ChainRole.values()) {
            assertTrue(ReportMapper.certRole(role, true, OSOBA).contains(OSOBA));
            assertTrue(ReportMapper.certRoleLocative(role, true, OSOBA).contains(OSOBA));
        }
    }
}
