package cz.digitalnikontinuita.anep.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code continuityObstacle()} je zároveň brána i text: vrátí-li {@code null}, prvek se do nabídky
 * dostane, jinak se čtenáři řekne proč. Měří se tady proto, že to jsou dvě věci, které se dají
 * rozejít tiše — a jednou se to už stalo.
 *
 * <p>Podpis krytý evidenčním záznamem byl přesně takový případ. Nabídku tří úkonů mu stahovalo
 * {@code preservedByRecord} v {@code show()}, ale překážka vracela {@code null}, takže záložka
 * Digitální kontinuita mlčela a čtenář se důvod nedozvěděl nikde. Situace 41 k tomu navíc nesla
 * zjištění, které nečetla žádná větev.
 */
class ContinuityObstacleTest {

    /** Kotvy důvěry za posledním razítkem se tenhle test nedotýká; prázdná množina stačí. */
    private static final Set<String> NO_TRUSTED_LAST = Set.of();

    @Test
    @DisplayName("Záznam nad prvkem je překážka, i když je verdikt platný")
    void anEvidenceRecordBlocksEvenAValidSignature() {
        var sig = SigFixture.sig()
                .certNotAfter(SigFixture.FUTURE)
                .bestSignatureTime(SigFixture.PAST)
                .situations(31, 32, 33)
                .evidenceRecord()
                .build();

        String obstacle = AdvisorView.continuityObstacle(sig, NO_TRUSTED_LAST);

        assertNotNull(obstacle, "prvek krytý záznamem se do nabídky dostat nesmí — razítko navíc"
                + " by přepsalo bajty, které záznam stvrzuje");
        assertTrue(obstacle.contains("evidenční záznam"), obstacle);
        assertTrue(obstacle.contains("další záznam nebo obnovit stávající"),
                "čtenář se má dozvědět, co normy místo toho dovolují: " + obstacle);
    }

    @Test
    @DisplayName("Situace 41 přidá, že podpis se stane neověřitelným a spravit to nepůjde")
    void situation41AddsTheLossOfRevocationEvidence() {
        var withoutMaterial = AdvisorView.continuityObstacle(
                SigFixture.sig().certNotAfter(SigFixture.FUTURE).evidenceRecord()
                        .situations(41).build(),
                NO_TRUSTED_LAST);
        var withMaterial = AdvisorView.continuityObstacle(
                SigFixture.sig().certNotAfter(SigFixture.FUTURE).evidenceRecord().build(),
                NO_TRUSTED_LAST);

        assertNotNull(withoutMaterial);
        assertNotNull(withMaterial);
        assertTrue(withoutMaterial.startsWith(withMaterial),
                "užší případ má stavět na širším, ne vyprávět jinou historku");
        assertTrue(withoutMaterial.length() > withMaterial.length(),
                "S41 musí říct něco navíc, jinak je pořád neviditelná");
        assertTrue(withoutMaterial.contains("nebyl odvolán"),
                "to podstatné je, že po expiraci certifikátu nepůjde doložit neodvolání: "
                + withoutMaterial);
    }

    @Test
    @DisplayName("Bez záznamu se kaskáda chová jako dřív")
    void withoutARecordNothingChanges() {
        var clean = SigFixture.sig()
                .certNotAfter(SigFixture.FUTURE)
                .bestSignatureTime(SigFixture.PAST)
                .situations(31, 32, 33)
                .build();

        assertNull(AdvisorView.continuityObstacle(clean, NO_TRUSTED_LAST),
                "prvek s úplným pokrytím a živým řetězem razítek překážku mít nemá");
    }

    /**
     * Obrácený test proti stavu z 2. 9. 2026, kdy S51 nabídku brala. Změněno 4. 9. 2026:
     * dopadalo to na každý čerstvě podepsaný dokument (ten má nejnovější údaj starší než sebe
     * vždycky) i na poctivé dokumenty s dávkovanou CRL, kde údaj okamžik vzniku pokrývá —
     * hlášeno nad pečetí z roku 2019. Ze sbírky D:	mp z 28 prvků se situací 51
     * bylo 25 INDETERMINATE (zastaví je verdikt) a 2 kladné, obě kopie téhož podvrhu; jediný
     * zásah proti všem poctivým. Brzdou zůstává situace 20 — nález, ne domněnka z odstupu.
     */
    @Test
    @DisplayName("Údaj o neodvolání starší než podpis (S51) sám nabídku nebere")
    void staleRevocationAloneDoesNotBlock() {
        var sig = SigFixture.sig()
                .certNotAfter(SigFixture.FUTURE)
                .bestSignatureTime(SigFixture.PAST)
                .situations(31, 32, 33, 34, 51)
                .build();

        assertNull(AdvisorView.continuityObstacle(sig, NO_TRUSTED_LAST),
                "odstup sám o sobě není doklad, že se něco stalo");
    }

    @Test
    @DisplayName("Doklad o odvolání (S20) nabídku bere i vedle S51")
    void provenRevocationOutranksTheStaleDatum() {
        var both = SigFixture.sig()
                .certNotAfter(SigFixture.FUTURE)
                .bestSignatureTime(SigFixture.PAST)
                .situations(20, 31, 32, 33, 34, 51)
                .build();

        String obstacle = AdvisorView.continuityObstacle(both, NO_TRUSTED_LAST);

        assertNotNull(obstacle);
        assertTrue(obstacle.contains("už odvolaný"),
                "doložené odvolání je silnější sdělení než neznalost: " + obstacle);
    }

    @Test
    @DisplayName("Porušená integrita a neplatný verdikt mají přednost před záznamem")
    void integrityAndVerdictComeFirst() {
        var broken = SigFixture.sig().evidenceRecord().situations(1).build();
        assertTrue(AdvisorView.continuityObstacle(broken, NO_TRUSTED_LAST)
                        .contains("neodpovídá tomu, co bylo podepsáno"),
                "razítko nad prokazatelně změněným obsahem je horší zpráva než zákaz augmentace");

        var indeterminate = SigFixture.sig().evidenceRecord()
                .indication("INDETERMINATE").build();
        assertTrue(AdvisorView.continuityObstacle(indeterminate, NO_TRUSTED_LAST)
                        .contains("není co zapečetit"),
                "u prvku bez platného výsledku nemá smysl vykládat, co by se s ním smělo dělat");
    }
}
