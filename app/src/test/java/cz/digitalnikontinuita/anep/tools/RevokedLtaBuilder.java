package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.AugmentationService;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TimestampingService;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.TsaProfile;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import eu.europa.esig.dss.alert.StatusAlert;
import eu.europa.esig.dss.alert.status.Status;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.pades.signature.PAdESExtensionService;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Vyrobí z podpisu úrovně B nebo B-T úroveň B-LTA i tam, kde je podpisový certifikát odvolaný —
 * tedy přesně tam, kde to aplikace odmítá a kde to norma zakazuje.
 *
 * <p><b>Vzniká tím záměrně nekonformní dokument.</b> ETSI EN 319 142-1, čl. 6.3 písm. x) a EN 319
 * 122-1, čl. 6.3 písm. m) žádají, aby archivní časové razítko vzniklo dřív, než byl certifikát
 * odvolán; to je také důvod, proč situace 44 uchování zakazuje a proč tlačítko „Uchování“ takový
 * dokument nenabízí. Nástroj tu není proto, aby se to obcházelo v provozu, ale aby vznikl
 * testovací vstup: vložená OCSP odpověď revokaci <em>prokazuje</em>, takže dokument sám o sobě
 * říká, že platný není, a jde na něm změřit, co o takové kombinaci soudí AnEP i cizí ověřovatelé.
 * Hodnota přijde s časem — jakmile podpisový certifikát expiruje a vydavatel přestane revokaci
 * zveřejňovat, bude tenhle dokument jediný, který ji doloží i offline, kdežto jeho varianty B
 * a B-T se ze sítě už nedozvědí nic.
 *
 * <p>Od toho, co dělá „Uchování“ v aplikaci, se liší jedinou věcí: poplachy, kvůli kterým by DSS
 * úkon odmítlo, tu nekončí výjimkou, ale výpisem. Kolik jich zabere, závisí na dokumentu, a to
 * číslo je součástí výsledku — nástroj ho na konci vypíše. Podpis na odvolaném, ale dosud platném
 * certifikátu obejde jediné pravidlo („Revoked/Suspended certificate(s) detected“); podpis
 * vytvořený až po expiraci certifikátu dvě, protože k době platnosti certifikátu navíc neexistuje
 * žádný důkaz existence („The signing certificate has expired and there is no POE during its
 * validity range“). Všechno ostatní — zdroje revokačních dat, pořadí kroků, razítkovací služba
 * z nastavení — je táž cesta, a to je záměr: kdyby si nástroj skládal vlastní, přestal by vyrábět
 * artefakt, který o aplikaci něco vypovídá.
 *
 * <p><b>Čím víc pravidel bylo nutné obejít, tím míň výsledek dokládá.</b> U odvolaného, ale dosud
 * platného certifikátu je vložená odpověď OCSP autoritativní a dokument s ní revokaci unese sám.
 * U podpisu vytvořeného po expiraci certifikátu je i ta dnes stažená odpověď post-expirační, tedy
 * pro přísného ověřovatele bezcenná — vloží se, ale nic nedokládá, a výsledek ověření se oproti
 * vstupu nezmění. Takový artefakt je testem toho, že se ověřovatel nedá zmást obalem úrovně LTA.
 *
 * <p>Podpis úrovně B (bez časového razítka) potřebuje dvě razítka a tedy dvě kola, stejně jako
 * v aplikaci: první dodá úroveň T, protože teprve pak má vkládání revokačních dat k čemu se
 * vztáhnout, druhé je to archivní a kryje je. EN 319 142-1 požadavek x) chce materiál v dokumentu
 * dřív než razítko dokumentu, a tohle je přesně ten sled.
 *
 * <pre>
 *   java -cp "app/target/test-classes;app/target/classes;$(cat cp.txt)" \
 *        cz.digitalnikontinuita.anep.tools.RevokedLtaBuilder vstup.pdf vystup.pdf
 * </pre>
 */
public final class RevokedLtaBuilder {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: RevokedLtaBuilder <vstup.pdf> <vystup.pdf>");
            return;
        }
        File input = new File(args[0]);
        File output = new File(args[1]);

        AppSettings settings = AppSettings.load();
        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier base = trustConfig.buildLotlCertificateVerifier();
        System.out.println("Důvěryhodné seznamy: načteny");

        TsaProfile profile = settings.getSelectedTsa();
        if (profile == null) {
            System.err.println("V nastavení není vybraná žádná služba časových razítek.");
            return;
        }
        System.out.println("Služba časových razítek: " + profile.getName()
                + "  (" + profile.getUrl() + ")");

        TimestampingService tsa = new TimestampingService(new AtomicReference<>(base));
        DigestAlgorithm alg = TimestampingService.resolveAlgorithm(profile.getHashAlgorithm());

        DSSDocument doc = new DocumentLoader().load(input);

        // Úroveň B: nejdřív podpisové razítko, jinak nemá vkládaný revokační materiál k čemu
        // se vztáhnout. Rozpoznáno podle toho, že v dokumentu není žádné razítko.
        if (ValidatorFactory.earliestTimestampTime(doc, null, null) == null) {
            System.out.println("— podpis nemá časové razítko, žádám o podpisové (úroveň T) …");
            doc = tsa.addTimestamp(doc, null, profile, alg).doc();
        }

        // Táž stavba jako v AugmentationService.augment() s revocationDecidesVerdict = true:
        // vložené odpovědi dokumentu nesmí předběhnout síť, protože právě to, co dokument
        // nenese — dnešní OCSP prokazující revokaci — je tu tím, oč jde.
        CommonCertificateVerifier augVerifier = AugmentationService.buildPreservationVerifier(
                base, List.of(), List.of(),
                ValidatorFactory.earliestTimestampTime(doc, null, null));

        // Ústupky proti tomu, co dělá aplikace. Nemlčí se: každý přehlížený nález se vypíše,
        // takže je vidět, kolik pravidel bylo pro daný dokument nutné obejít — a to je samo
        // o sobě údaj o tom, jak daleko od konformního artefaktu výsledek je.
        //
        // Který z nich zabere, závisí na dokumentu. Podpis na odvolaném, ale dosud platném
        // certifikátu potřebuje jen ten první; podpis vytvořený až po expiraci certifikátu
        // navíc druhý a třetí, protože k době platnosti certifikátu neexistuje žádný důkaz
        // existence a revokační údaj, který se dnes stáhne, je post-expirační.
        Overridden overridden = new Overridden();
        augVerifier.setAlertOnRevokedCertificate(overridden);
        augVerifier.setAlertOnExpiredCertificate(overridden);
        augVerifier.setAlertOnMissingRevocationData(overridden);
        augVerifier.setAlertOnNoRevocationAfterBestSignatureTime(overridden);
        augVerifier.setAlertOnUncoveredPOE(overridden);

        System.out.println("— vkládám revokační data (úroveň LT) …");
        DSSDocument lt = new PAdESExtensionService(augVerifier)
                .incorporateValidationData(doc, null);
        System.out.println("  po vložení: " + size(lt) + " B  (vstup " + input.length() + " B)");

        System.out.println("— žádám archivní časové razítko …");
        TimestampingService.TimestampResult stamped = tsa.addTimestamp(lt, null, profile, alg);

        try (OutputStream os = Files.newOutputStream(output.toPath())) {
            stamped.doc().writeTo(os);
        }
        System.out.println("Zapsáno: " + output + "  (" + output.length() + " B)");
        System.out.println("Obejitých pravidel DSS: " + overridden.count()
                + "  — výsledek je o tolik dál od konformního dokumentu.");
        System.out.println("Certifikát razítkovací autority platí do: " + stamped.tsaCertNotAfter());
    }

    /**
     * Poplach, který nález nezahodí ani kvůli němu neskončí, jen ho vypíše. Proti
     * {@code SilentOnStatusAlert} je tu schválně: mlčení by zakrylo, kolik a jakých pravidel
     * bylo u konkrétního dokumentu potřeba obejít, a právě to je na výsledném artefaktu
     * podstatné.
     */
    private static final class Overridden implements StatusAlert {
        private int count;

        @Override
        public void alert(Status status) {
            if (status == null || status.isEmpty()) return;
            count++;
            System.out.println("  ⚠ obcházím pravidlo DSS: " + status.getErrorString());
        }

        int count() {
            return count;
        }
    }

    private static long size(DSSDocument d) throws Exception {
        try (InputStream is = d.openStream()) {
            return is.readAllBytes().length;
        }
    }

    private RevokedLtaBuilder() { }
}
