package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Které ze zapnutých ústupků nesly kladný verdikt — zjištěno tím, že se odeberou a ověří znovu.
 *
 * <p>Bydlelo to do 19. 9. 2026 v {@code MainView}. Přesunuto sem, protože to není vykreslování,
 * nýbrž měření, a hlavně proto, že mimo {@code MainView} nebylo k dispozici: {@code CardSnapshot}
 * mapoval bez něj, takže vykreslil kartu, jakou uživatel nikdy nedostane — a právě na ní se
 * 19. 9. 2026 našla věta „bez nich by ověření dopadlo stejně“ nad podpisem, který bez ústupků
 * neprojde. Nástroj, který má ukazovat, co uvidí uživatel, musí jít toutéž cestou.
 *
 * <p>Postup: nejdřív se všemi ústupky vypnutými. Každý prvek, který i tak obstojí, nepotřeboval
 * žádný — to je běžný případ a stojí jedno ověření navíc. Teprve tam, kde něco propadlo, se
 * odebírá po jednom: prvek, který bez určitého ústupku přestane obstát, ho potřeboval. Je-li
 * zapnutý jediný ústupek, plyne odpověď už z prvního běhu a další se nedělá.
 *
 * <p>Napřed nutnost, kde nutnost mlčí, tam dostatečnost. Ústupky, které se navzájem zastupují,
 * vyjdou z odebírání tak, že není potřeba ani jeden — pravda, ale čtenáři k ničemu —, takže
 * takový prvek dostane druhý průchod, v němž se pouští každý ústupek samostatně: co samo stačí,
 * je skutečná alternativa a jmenuje se; zbytek do verdiktu nemluvil. Teprve když nestačí ani
 * jeden sám (verdikt stojí na souhře), jmenují se všechny zapnuté.
 *
 * <p>Mimo tolerantní režim, a tam, kde nebylo co uznat, se nespouští nic. Neúspěch tady stojí
 * čtenáře opravu, ne výsledek, takže se spolkne a vrátí {@code null} — „neměřeno“. Ten rozdíl
 * proti „naměřeno nic“ musí umět udržet i to, co si výsledek přebírá: prázdná množina znamená
 * „žádný ústupek nebyl potřeba“, kdežto {@code null} znamená, že se na to nikdo neptal.
 */
public final class RelaxationMeasurement {

    private RelaxationMeasurement() { }

    /**
     * @param settings       nastavení, v němž je zapnuto to, na co se ptáme; metoda jím po dobu
     *                       měření hýbe a na konci je vrátí, jak byla
     * @param lenientOutcome výsledek ověření, které už proběhlo — určuje, o kterých prvcích má
     *                       vůbec smysl se ptát
     * @return pro každý uznaný prvek ústupky, bez nichž by uznán nebyl; {@code null}, když se
     *         neměřilo (žádný ústupek zapnutý, nic uznaného, nebo chyba během měření)
     */
    public static Map<String, ReportMapper.RequiredRelaxations> measure(
            AppSettings settings, ValidatorFactory.ValidationOutcome lenientOutcome,
            DSSDocument sigDoc, DSSDocument detachedContent,
            CommonCertificateVerifier verifier, char[] pdfPassword,
            List<DSSDocument> evidenceRecords) {
        List<ReportMapper.Relaxation> enabled = ReportMapper.Relaxation.enabledIn(settings);
        if (enabled.isEmpty()) return null;

        List<String> accepted = new ArrayList<>(acceptedIn(lenientOutcome));
        if (accepted.isEmpty()) return null;

        Map<ReportMapper.Relaxation, Boolean> saved =
                new EnumMap<>(ReportMapper.Relaxation.class);
        for (ReportMapper.Relaxation r : ReportMapper.Relaxation.values()) {
            saved.put(r, r.isEnabled(settings));
        }
        try {
            Map<String, ReportMapper.RequiredRelaxations> required = new HashMap<>();
            // Zastupitelnost se pozná až v druhém měření níž; do té doby je každý nález konjunkce.
            Set<String> interchangeable = new HashSet<>();

            Set<String> withoutAny = passingWith(settings, List.of(),
                    sigDoc, detachedContent, verifier, pdfPassword, evidenceRecords);
            List<String> needsSomething = new ArrayList<>();
            for (String id : accepted) {
                if (withoutAny.contains(id)) {
                    required.put(id, new ReportMapper.RequiredRelaxations(
                            EnumSet.noneOf(ReportMapper.Relaxation.class), false));
                } else {
                    needsSomething.add(id);
                }
            }
            if (needsSomething.isEmpty()) return required;

            if (enabled.size() == 1) {
                for (String id : needsSomething) {
                    required.put(id, new ReportMapper.RequiredRelaxations(
                            EnumSet.of(enabled.get(0)), false));
                }
                return required;
            }

            Map<String, Set<ReportMapper.Relaxation>> needed = new HashMap<>();
            for (String id : needsSomething) {
                needed.put(id, EnumSet.noneOf(ReportMapper.Relaxation.class));
            }
            for (ReportMapper.Relaxation withdrawn : enabled) {
                List<ReportMapper.Relaxation> rest = new ArrayList<>(enabled);
                rest.remove(withdrawn);
                Set<String> passing = passingWith(settings, rest, sigDoc, detachedContent,
                        verifier, pdfPassword, evidenceRecords);
                for (String id : needsSomething) {
                    if (!passing.contains(id)) needed.get(id).add(withdrawn);
                }
            }
            // Když se odebíráním po jednom nevytkne nic, neznamená to, že se uplatnily všechny.
            // Znamená to, že se některé zastupují — stačí kterýkoli z nich a na zbytku nezáleží.
            // Blanketní výčet pak tvrdil i o SHA-1 a PDF-NOT-ETSI, že nesly verdikt, ačkoli se
            // jich dokument vůbec netýkal; stačilo zapnout tolerantní režim, který zaškrtne
            // všechny čtyři.
            //
            // Druhé měření to rozplete: pustí každý ústupek samostatně. Co samo stačí, je
            // skutečná alternativa a do výčtu patří; co ne, do verdiktu nemluvilo. Stojí to
            // nejvýš čtyři ověření a jen v téhle nejednoznačnosti.
            Map<ReportMapper.Relaxation, Set<String>> alone = null;
            for (String id : needsSomething) {
                if (!needed.get(id).isEmpty()) continue;
                if (alone == null) {
                    alone = new EnumMap<>(ReportMapper.Relaxation.class);
                    for (ReportMapper.Relaxation r : enabled) {
                        alone.put(r, passingWith(settings, List.of(r), sigDoc, detachedContent,
                                verifier, pdfPassword, evidenceRecords));
                    }
                }
                Set<ReportMapper.Relaxation> alternatives =
                        EnumSet.noneOf(ReportMapper.Relaxation.class);
                for (ReportMapper.Relaxation r : enabled) {
                    if (alone.get(r).contains(id)) alternatives.add(r);
                }
                // Nepomohl-li ani jeden sám, nese verdikt teprve jejich souhra — pak nezbývá než
                // jmenovat všechny, jako dřív.
                if (!alternatives.isEmpty()) interchangeable.add(id);
                needed.put(id, alternatives.isEmpty()
                        ? EnumSet.copyOf(enabled) : alternatives);
            }
            for (String id : needsSomething) {
                required.put(id, new ReportMapper.RequiredRelaxations(
                        needed.get(id), interchangeable.contains(id)));
            }
            return required;

        } catch (Exception e) {
            return null;
        } finally {
            saved.forEach((r, was) -> r.setEnabled(settings, was));
        }
    }

    /**
     * Prvky uznané, když je zapnuto právě {@code active} ústupků. Hýbe sdíleným nastavením
     * a volá se jedině z {@link #measure}, které je vrátí zpátky — ověřuje se jeden dokument
     * po druhém, takže mezitím do nastavení nikdo jiný nečte.
     */
    private static Set<String> passingWith(
            AppSettings settings, List<ReportMapper.Relaxation> active, DSSDocument sigDoc,
            DSSDocument detachedContent, CommonCertificateVerifier verifier, char[] pdfPassword,
            List<DSSDocument> evidenceRecords) {
        for (ReportMapper.Relaxation r : ReportMapper.Relaxation.values()) {
            r.setEnabled(settings, active.contains(r));
        }
        ValidatorFactory.ValidationOutcome outcome = new ValidatorFactory(settings)
                .validate(sigDoc, detachedContent, verifier, pdfPassword, evidenceRecords);
        return acceptedIn(outcome);
    }

    /**
     * Uznané prvky včetně těch, které uznal až druhý průchod — přepočet s důkazem existence od
     * prvku, který je kryje ({@link ValidatorFactory.ValidationOutcome#derivedTimeResults()}).
     *
     * <p>Dokud se tu četl jen protokol prvního průchodu, takové prvky se z měření vytratily:
     * neptal se na ně nikdo, a karta u nich proto nehlásila „díky tolerantnímu ověřování“,
     * ačkoli bez ústupku neprojdou. Nález uživatele 2. 10. 2026 — dva podpisy v úrovni B kryté
     * podpisem s razítkem; ten krycí štítek měl, kryté ne.
     */
    static Set<String> acceptedIn(ValidatorFactory.ValidationOutcome outcome) {
        Set<String> accepted = acceptedIn(outcome.reports().getSimpleReport());
        outcome.derivedTimeResults().forEach((id, d) -> {
            if (Indication.TOTAL_PASSED.name().equals(d.indication())) accepted.add(id);
        });
        return accepted;
    }

    /**
     * Všechno, co protokol uznal — podpisy i razítka.
     *
     * <p>Razítka sem patří proto, že kontejner nemusí nést nic jiného. Forenzní balíček je ten
     * případ: ptát se je na co jediného, na razítko, a vynechat je z měření znamenalo nechat
     * situaci 34 odvodit z toho, které tolerance jsou zapnuté, místo z toho, které verdikt
     * potřeboval — tedy přesně to odvození, kvůli kterému měření vzniklo. DSS hodnotí samostatné
     * razítko jako {@code PASSED}, ne {@code TOTAL_PASSED} — tvary {@code TOTAL_} má vyhrazené
     * pro podpisy —, takže se počítají obojí.
     */
    public static Set<String> acceptedIn(SimpleReport report) {
        Set<String> accepted = new LinkedHashSet<>();
        for (String id : report.getSignatureIdList()) {
            if (report.getIndication(id) == Indication.TOTAL_PASSED) accepted.add(id);
        }
        for (String id : report.getTimestampIdList()) {
            if (report.getIndication(id) == Indication.PASSED) accepted.add(id);
        }
        return accepted;
    }
}
