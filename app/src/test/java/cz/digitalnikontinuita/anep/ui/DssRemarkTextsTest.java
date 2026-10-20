package cz.digitalnikontinuita.anep.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Soubor {@code texty/dss-pripominky.txt} a jeho obsah.
 *
 * <p>Výklady k připomínkám knihovny DSS nejsou v Javě, takže překlep v názvu oddílu nebo klíče
 * se nepozná při překladu — projevil by se až prázdným místem u uživatele. Tenhle test stojí
 * přesně tam; co je v textech napsáno, nezkoumá.
 */
class DssRemarkTextsTest {

    /**
     * Klíče, které sonda {@code DssWarningScan} naměřila nad sbírkami u prvků, <b>které
     * obstály</b> — tedy přesně ty, které podblok „Připomínky od knihovny DSS“ ukáže.
     * Zmizí-li některému výklad, ukáže se čtenáři anglická věta knihovny a nic víc.
     *
     * <p>Výchozí běh sondy je <b>přísný</b>, takže na hlášení, které vystoupí teprve po
     * zaškrtnutí některého ústupku, nedosáhne; od 19. 9. 2026 má proto volbu {@code -all},
     * která ústupky zapne. Oba běhy nad {@code D:\tmp} (231 prvků, přísně obstálo 48,
     * s ústupky 90) se rozešly o pět klíčů, které vystoupí <b>jen s ústupkem</b>:
     * {@code LTV_ISCKNR_ANS1} (28×), {@code BBB_ICS_ISASCP_ANS} a
     * {@code BBB_SAV_ISQPMDOSPP_ANS} (po 5), {@code BBB_SAV_DMICTSTMCMI_ANS}
     * a {@code PSV_DIURDSCHPVR_ANS} (po 1).
     *
     * <p>Opačným směrem to souměrné není: {@code ACCM_ANS} a {@code ASCCM_AR_ANS_ANR}
     * (po 11) se naměří jen <b>přísně</b>, protože ústupek pro SHA-1 je nesráží na WARN,
     * nýbrž celé vypíná. Výklad k nim tedy platí jen pro přísný režim.
     *
     * <p>{@code ASCCM_AR_ANS_AKSNR} se ve sbírkách k dnešku nenaměří, a přesto do výčtu patří:
     * vystoupí u každého prvku s klíčem RSA o 2 048 bitech ověřovaného po 1. 1. 2027. Změřeno
     * 5. 10. 2026 ověřením téhož dokumentu k 1. 3. 2027 — prvek obstál a hlášení přišlo třikrát
     * (podpis, razítko, revokační údaj) jako varování.
     *
     * <p>Oba běhy nad {@code D:\Downloads} (1218 prvků, přísně obstálo 507, s ústupky 785)
     * ukázaly, že <b>chybějící výklad nemusí být vina ústupků</b>. {@code BBB_CV_IAFS_ANS}
     * (66× s ústupky, <b>50× i přísně</b>) a {@code TSV_ASTPTCT_ANS} (2× v obou) se naměří
     * i bez nich — do souboru nikdy nepřišly prostě proto, že sbírka mezitím povyrostla
     * o soubory, které tam při dřívějším měření nebyly. Všech pět dostalo výklad 19. 9. 2026.
     *
     * <p><b>A „ústupkem odemčené“ znamená dvojí</b>, což se ukázalo na jednom reportu
     * (měřeno {@code StrictProbe}, 19. 9. 2026). Přísně skončí ten podpis jako
     * INDETERMINATE/CERTIFICATE_CHAIN_GENERAL_FAILURE, s ústupky jako TOTAL_PASSED — a přitom:
     * <ul>
     *   <li>{@code BBB_XCV_IARDPFC_ANS} ústupek <b>srazil z chyby na připomínku</b>, stejně jako
     *       u {@code LTV_ISCKNR_ANS1};</li>
     *   <li>{@code QUAL_HAS_CAQC_ANS} a {@code QUAL_HAS_GRANTED_ANS} jsou připomínkou <b>v obou
     *       režimech</b> — do výčtu se dostaly jen proto, že prvek teprve s ústupkem obstál,
     *       a podblok ukazuje připomínky pouze u prvků, které obstály.</li>
     * </ul>
     *
     * <p>Běhy s {@code -all} z 12. 10. 2026 — {@code D:\tmp} (231 prvků, obstálo 88),
     * {@code D:\Downloads} (1 238, obstálo 791) a třetí sbírka (6 518, obstálo 3 050) —
     * nenašly žádný klíč bez výkladu, ale u prvků, které obstály, naměřily sedm klíčů, které ve
     * výčtu dosud nebyly: {@code ADEST_VFDTAOCST_ANS}, {@code BBB_FC_ISVAFMDPD_ANS},
     * {@code BBB_FC_ISVASFLD_ANS}, {@code BBB_FC_IVDBSFR_ANS}, {@code BBB_SAV_ISQPSTP_ANS},
     * {@code BBB_SAV_ISQPSTWSCVR_ANS} a {@code BBB_XCV_ICTIVRCIRI_ANS}. Výklad měly už předtím;
     * {@code BBB_FC_IVDBSFR_ANS} jen díky rozboru politiky ({@link #POSSIBLE_UNDER_ANEP_POLICY}).
     */
    private static final List<String> MEASURED_ON_PASSED = List.of(
            "ACCM_ANS",
            "ADEST_VFDTAOCST_ANS",
            "ASCCM_AR_ANS_AKSNR",
            "ASCCM_AR_ANS_ANR",
            "BBB_CV_IAFS_ANS",
            "BBB_FC_DSCNUOM_ANS",
            "BBB_FC_IAOD_ANS",
            "BBB_FC_ISVAFMDPD_ANS",
            "BBB_FC_ISVASFLD_ANS",
            "BBB_FC_IVDBSFR_ANS",
            "BBB_ICS_ISASCP_ANS",
            "BBB_SAV_DMICTSTMCMI_ANS",
            "BBB_SAV_ISQPMDOSPP_ANS",
            "BBB_SAV_ISQPSTP_ANS",
            "BBB_SAV_ISQPSTWSCVR_ANS",
            "BBB_XCV_AIA_PRES_ANS",
            "BBB_XCV_IARDPFC_ANS",
            "BBB_XCV_ICTIVRCIRI_ANS",
            "BBB_XCV_IRDPFRC_ANS",
            "BBB_XCV_ISCGKU_ANS",
            "LTV_ISCKNR_ANS1",
            "PSV_DIURDSCHPVR_ANS",
            "QUAL_CERT_TYPE_AT_CC_ANS",
            "QUAL_CERT_TYPE_AT_ST_ANS",
            "QUAL_HAS_CAQC_ANS",
            "QUAL_HAS_GRANTED_ANS",
            "QUAL_QC_AT_CC_ANS",
            "QUAL_QC_AT_ST_ANS",
            "QUAL_QSCD_AT_CC_ANS",
            "QUAL_QSCD_AT_ST_ANS",
            "TSV_ASTPTCT_ANS");

    /**
     * Klíče, které může knihovna DSS 6.5 vydat jako připomínku (WARN) při politice, se kterou
     * ověřuje AnEP — výchozí {@code constraint.xml} spolu s trvalými úpravami a se všemi ústupky
     * z {@code ValidationPolicyFactory}.
     *
     * <p>Výčet nevznikl měřením, nýbrž rozborem knihovny (12. 10. 2026): z přeložených tříd
     * {@code dss-validation} se ke každé kontrole zjistilo, které hlášení vydává a podle kterého
     * prvku politiky se řídí její úroveň, a z {@code constraint.xml} se vzalo, které prvky jsou
     * nebo mohou být na WARN. K tomu kontroly s pevnou úrovní WARN ({@code getWarnLevelRule})
     * a ty, které při chybějícím prvku politiky na WARN spadnou ({@code TimestampValid},
     * {@code EvidenceRecordValid}). U kryptografických kontrol rozhoduje
     * {@code LevelAfterUpdate="WARN"} v {@code AlgoExpirationDate}, takže připomínkou mohou být
     * jen hlášení o algoritmu, jehož datum už uplynulo.
     *
     * <p>Vynechána jsou hlášení ověřování atestací EAA, seznamů LoTE a certifikátů QWAC
     * a kvalifikovanost k okamžiku ověření ({@code …_AT_VT_…}) — ta patří k ověřování samotného
     * certifikátu, a AnEP připomínky čte jen ze simple reportu podpisů a razítek. Rozbor je
     * nadmnožina: část klíčů vystoupí jen u prvku, který nakonec neobstál, a pak se na kartě
     * neukáže. Po přechodu na novější DSS je třeba rozbor zopakovat.
     */
    private static final List<String> POSSIBLE_UNDER_ANEP_POLICY = List.of(
            "ACCM_ANS",
            "ADEST_IBSVPTADC_ANS",
            "ADEST_IBSVPTC_ANS",
            "ADEST_IRERVPC_ANS",
            "ADEST_RORPIIC_ANS",
            "ADEST_VFDTAOCST_ANS",
            "ARCH_ICHFCRLPOET_ANS",
            "ASCCM_AR_ANS_AKSNR",
            "ASCCM_AR_ANS_ANR",
            "BBB_ACCEPT_ANS",
            "BBB_CV_AAMEF_ANS",
            "BBB_CV_DMENMND_ANS",
            "BBB_CV_DRNMND_ANS",
            "BBB_CV_ER_DFHVLCDOG_ANS",
            "BBB_CV_ER_HASSDOC_ANS",
            "BBB_CV_IAFS_ANS",
            "BBB_CV_ISMEC_ANS",
            "BBB_CV_ISMEC_ANS_2",
            "BBB_FC_DBTOOST_ANS",
            "BBB_FC_DSCNUOM_ANS",
            "BBB_FC_IAHIV_ANS",
            "BBB_FC_IAOD_ANS",
            "BBB_FC_IECKSCDA_ANS1",
            "BBB_FC_IECKSCDA_ANS2",
            "BBB_FC_IECKSCDA_ANS3",
            "BBB_FC_IECKSCDA_ANS4",
            "BBB_FC_IECKSCDA_ANS5",
            "BBB_FC_IEMCF_ANS",
            "BBB_FC_IMFCS_ANS",
            "BBB_FC_ISFCS_ANS",
            "BBB_FC_ISFP_ASTFORAMC_ANS",
            "BBB_FC_ISVADMDPD_ANS",
            "BBB_FC_ISVAFMDPD_ANS",
            "BBB_FC_ISVASFLD_ANS",
            "BBB_FC_IVDBSFR_ANS",
            "BBB_ICS_AIDNASNE_ANS",
            "BBB_ICS_DKIDVM_ANS",
            "BBB_ICS_ISACDP_ANS",
            "BBB_ICS_ISASCPU_ANS",
            "BBB_ICS_ISASCP_ANS",
            "BBB_RFC_NUP_ANS",
            "BBB_SAV_DMICTSTMCMI_ANS",
            "BBB_SAV_DSCACRCC_ANS",
            "BBB_SAV_ICTVS_ANS",
            "BBB_SAV_ISQPMDOSPP_ANS",
            "BBB_SAV_ISQPSTP_ANS",
            "BBB_SAV_ISQPSTWSCVR_ANS",
            "BBB_SAV_ISSV_ANS",
            "BBB_TAV_DTSAVM_ANS",
            "BBB_VCI_ISPM_ANS",
            "BBB_VCI_IZHSP_ANS",
            "BBB_XCV_AIA_PRES_ANS",
            "BBB_XCV_DCCUCE_ANS",
            "BBB_XCV_DCSBSINC_ANS",
            "BBB_XCV_IAKIP_ANS",
            "BBB_XCV_IARDPFC_ANS",
            "BBB_XCV_ICNRAEV_ANS",
            "BBB_XCV_ICPTV_ANS",
            "BBB_XCV_ICTIVRCIRI_ANS",
            "BBB_XCV_IRDPFC_ANS",
            "BBB_XCV_IRDPFRC_ANS",
            "BBB_XCV_ISCGKU_ANS",
            "BBB_XCV_ISCGKU_ANS_CERT",
            "BBB_XCV_ISNSSC_ANS",
            "BBB_XCV_ISSSC_ANS",
            "BBB_XCV_RAC_ANS",
            "BBB_XCV_REVOC_ISSUER_VALID_AT_PROD_ANS",
            "BBB_XCV_REVOC_PRES_ANS",
            "BBB_XCV_REVOC_SELF_ISSUED_OCSP_ANS",
            "BSV_ICTGTNASCET_ANS",
            "BSV_ICTGTNASCRT_ANS",
            "BSV_ISAVRC_ANS",
            "BSV_ISCCTC_ANS",
            "BSV_ISCRAVTC_ANS",
            "BSV_IVTAVRSC_ANS",
            "BSV_IXCVRC_ANS",
            "LTV_ISCKNR_ANS0",
            "LTV_ISCKNR_ANS1",
            "PCV_IVTSC_ANS",
            "PSV_HRDBIBCT_ANS",
            "PSV_ICRDIT_ANS",
            "PSV_IPCRIAIDBEDC_ANS",
            "PSV_ITPOOBCT_ANS",
            "PSV_ITPOSVAOBCT_ANS",
            "QUAL_CERT_TYPE_AT_CC_ANS",
            "QUAL_CERT_TYPE_AT_ST_ANS",
            "QUAL_HAS_CAQC_ANS",
            "QUAL_HAS_CAQC_ANS_2",
            "QUAL_HAS_GRANTED_ANS",
            "QUAL_HAS_GRANTED_ANS_2",
            "QUAL_HAS_METS_ANS",
            "QUAL_HAS_METS_HCCECBA_ANS",
            "QUAL_HAS_METS_HCCECBA_ANS_2",
            "QUAL_HAS_METS_HCCECBA_ANS_3",
            "QUAL_IS_TRUST_CERT_MATCH_SERVICE_ANS0",
            "QUAL_IS_TRUST_CERT_MATCH_SERVICE_ANS1",
            "QUAL_IS_TRUST_CERT_MATCH_SERVICE_ANS2",
            "QUAL_LIST_OF_TRUSTED_LISTS_ACCEPT_ANS",
            "QUAL_QC_AT_CC_ANS",
            "QUAL_QC_AT_ST_ANS",
            "QUAL_QSCD_AT_CC_ANS",
            "QUAL_QSCD_AT_ST_ANS",
            "QUAL_TL_EXP_ANS",
            "QUAL_TL_FRESH_ANS",
            "QUAL_TL_SERV_CONS_ANS0",
            "QUAL_TL_SERV_CONS_ANS1",
            "QUAL_TL_SERV_CONS_ANS3",
            "QUAL_TL_SERV_CONS_ANS3A",
            "QUAL_TL_SERV_CONS_ANS3B",
            "QUAL_TL_SERV_CONS_ANS3C",
            "QUAL_TL_SERV_CONS_ANS4",
            "QUAL_TL_SERV_CONS_ANS5",
            "QUAL_TL_SERV_CONS_ANS6",
            "QUAL_TL_SERV_CONS_ANS7",
            "QUAL_TL_SV_ANS",
            "QUAL_TL_WS_ANS",
            "QUAL_TRUSTED_LIST_ACCEPT_ANS",
            "QUAL_UNIQUE_CERT_ANS",
            "TSV_ASTPTCT_ANS");

    @Test
    @DisplayName("soubor se přečte celý a bez výhrad")
    void fileParses() {
        assertEquals(List.of(), DssRemarkTexts.errors(),
                "v souboru texty/dss-pripominky.txt jsou řádky, kterým čtení nerozumí");
    }

    @Test
    @DisplayName("každé naměřené hlášení má krátkou větu i výklad")
    void measuredKeysHaveText() {
        List<String> missing = new ArrayList<>();
        for (String key : MEASURED_ON_PASSED) {
            if (DssRemarkTexts.shortText(key) == null) missing.add(key + " / kratce");
            if (DssRemarkTexts.explanation(key) == null) missing.add(key + " / vyklad");
        }
        assertEquals(List.of(), missing, "hlášení bez výkladu v texty/dss-pripominky.txt");
    }

    @Test
    @DisplayName("každé hlášení, které politika AnEP připouští jako připomínku, má výklad")
    void policyKeysHaveText() {
        List<String> missing = new ArrayList<>();
        for (String key : POSSIBLE_UNDER_ANEP_POLICY) {
            if (DssRemarkTexts.shortText(key) == null) missing.add(key + " / kratce");
            if (DssRemarkTexts.explanation(key) == null) missing.add(key + " / vyklad");
        }
        assertEquals(List.of(), missing, "hlášení bez výkladu v texty/dss-pripominky.txt");
    }

    @Test
    @DisplayName("žádný oddíl nezůstal poloprázdný")
    void everySectionIsComplete() {
        List<String> incomplete = new ArrayList<>();
        for (String section : DssRemarkTexts.sections()) {
            if (DssRemarkTexts.shortText(section) == null) incomplete.add(section + " / kratce");
            if (DssRemarkTexts.explanation(section) == null) incomplete.add(section + " / vyklad");
        }
        assertEquals(List.of(), incomplete, "oddíl bez jedné z obou částí");
    }

    @Test
    @DisplayName("v souboru není klíč, který by aplikace nečetla")
    void noUnknownKeys() {
        // Klíč s překlepem se tiše ignoruje: text pod ním se u uživatele neukáže a nic to
        // neohlásí.
        Set<String> known = Set.of("kratce", "vyklad");
        List<String> unknown = new ArrayList<>();
        for (String section : DssRemarkTexts.sections()) {
            for (String key : DssRemarkTexts.keys(section)) {
                if (!known.contains(key)) unknown.add("[" + section + "] " + key);
            }
        }
        assertEquals(List.of(), unknown, "klíč, který aplikace nikdy nepřečte");
    }

    @Test
    @DisplayName("název oddílu je klíč hlášení DSS, ne věta")
    void sectionsLookLikeDssKeys() {
        // Oddíl se dohledává přesnou shodou s klíčem, který vydá knihovna. Mezera, malé
        // písmeno nebo české slovo v názvu znamenají, že se na ten oddíl nikdy nikdo nezeptá.
        List<String> odd = new ArrayList<>();
        for (String section : DssRemarkTexts.sections()) {
            if (!section.matches("[A-Z0-9_]+")) odd.add(section);
        }
        assertEquals(List.of(), odd, "název oddílu, na který se kód nemá jak zeptat");
    }

    @Test
    @DisplayName("neznámé hlášení se nepředstírá, že má výklad")
    void unknownKeyStaysUnknown() {
        assertFalse(DssRemarkTexts.known("TOHLE_HLASENI_NEEXISTUJE"));
        assertNull(DssRemarkTexts.shortText("TOHLE_HLASENI_NEEXISTUJE"));
        assertNull(DssRemarkTexts.explanation("TOHLE_HLASENI_NEEXISTUJE"));
        assertNull(DssRemarkTexts.shortText(null));
        assertNull(DssRemarkTexts.explanation(null));
        assertFalse(DssRemarkTexts.known(null));
    }

    @Test
    @DisplayName("dvojice AT_ST / AT_CC nemluví o témže okamžiku dvakrát stejně")
    void theTwoMomentsReadDifferently() {
        // Obě poloviny dvojice se u téhož podpisu ukazují pod sebou. Shodné znění by z nich
        // udělalo zdvojený řádek, ze kterého čtenář nepozná, proč tam je dvakrát.
        for (String base : List.of("QUAL_QSCD", "QUAL_QC", "QUAL_CERT_TYPE")) {
            String atSigning = DssRemarkTexts.shortText(base + "_AT_ST_ANS");
            String atIssuance = DssRemarkTexts.shortText(base + "_AT_CC_ANS");
            assertNotNull(atSigning, base);
            assertNotNull(atIssuance, base);
            assertTrue(!atSigning.equals(atIssuance), base + ": obě věty jsou totožné");
        }
    }
}
