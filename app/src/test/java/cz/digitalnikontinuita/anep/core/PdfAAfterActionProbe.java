package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.FileDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Zůstane dokument, který se hlásí k PDF/A a opravdu mu vyhovuje, archivní i po úkonu?
 *
 * <p>Otázka digitální kontinuity položená naruby: kdo přinese archivní dokument, aby ho uchoval,
 * nesmí dostat zpět soubor, který už archivní není. Formátová pravidla Národního archivu na
 * PDF/A stojí, takže ztráta konformity není kosmetická vada.
 *
 * <p>Měří se <b>celý úkon</b>, obě jeho poloviny:
 * <ol>
 *   <li>vložení materiálu — u uchování {@link AugmentationService#augment}, u přerazítkování
 *       (přepínač {@code -prerazitkovani}) {@link RestampingService#embedAll}. Obojí zapíše do
 *       souboru slovník {@code /DSS} a proudy s revokačními údaji, jen se liší v tom, co do
 *       nich uloží;</li>
 *   <li>archivní časové razítko ({@link TimestampingService}) — přidá podpisové pole
 *       a přírůstkovou aktualizaci.</li>
 * </ol>
 *
 * <p><b>Prodloužení se zvlášť neměří</b>, a to z věcného důvodu: nad dokumentem PDF vede
 * {@code AdvisorView.runPreservation} obě varianty týmž kódem. Druhá cesta
 * ({@code SignatureExtensionService}) se otevře jen kontejnerům a formátům CAdES, XAdES
 * a JAdES — {@code handles()} žádný PDF nepustí a {@code isTimestampOnlyContainer()} žádá typ
 * kontejneru, který PDF nemá. Prodloužení se od uchování liší dotazem před úkonem a jménem
 * výstupu, ne tím, co zapíše do souboru.
 *
 * <p>Není to jen čtení kódu: {@code handles()} bylo puštěno nad 60 podepsanými dokumenty PDF/A
 * z korpusu a <b>ani jeden</b> by druhou cestou nešel. Kdyby se to jednou změnilo, je tohle
 * věta, kterou je třeba přeměřit.
 * Razítko vydává {@link ThrowawayTsa}, autorita na jedno použití uvnitř tohoto běhu: otázka je
 * o struktuře souboru, ne o důvěryhodnosti razítka, takže ostrá autorita by odpověděla totéž
 * a stála by za každý dokument jeden objednaný podpis.
 *
 * <p>Revokační data se naopak stahují doopravdy. Bez nich totiž uchování nemá co vložit a vrátí
 * dokument nezměněný — a měření, ve kterém se se souborem nic nestalo, vydá „konformita
 * zachována“ i tam, kde by ji úkon rozbil. Proto se u každého dokumentu zvlášť počítá, jestli
 * opravdu narostl.
 *
 * <p>Výstupy jsou měřicí odpad a mažou se; korpusu se sonda nedotkne.
 */
public final class PdfAAfterActionProbe {

    public static void main(String[] args) throws Exception {
        List<File> files = new ArrayList<>();
        int max = Integer.MAX_VALUE;
        boolean restamp = false;
        for (int i = 0; i < args.length; i++) {
            if ("-max".equals(args[i]) && i + 1 < args.length) max = Integer.parseInt(args[++i]);
            else if ("-prerazitkovani".equals(args[i])) restamp = true;
            else collect(new File(args[i]), files);
        }
        if (files.isEmpty()) {
            System.err.println("usage: PdfAAfterActionProbe [-max N] <soubor|adresář>…");
            return;
        }

        AppSettings settings = AppSettings.load();
        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier;
        try {
            verifier = trustConfig.buildLotlCertificateVerifier();
            System.out.println("důvěryhodné seznamy: načteny");
        } catch (Exception e) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
            System.out.println("důvěryhodné seznamy: NEDOSTUPNÉ (" + e + ")");
        }
        AtomicReference<CommonCertificateVerifier> ref = new AtomicReference<>(verifier);
        AugmentationService augmentation = new AugmentationService(ref);
        RestampingService restamping = new RestampingService();
        TimestampingService timestamping = new TimestampingService(ref);
        ValidatorFactory validators = new ValidatorFactory(settings);
        ThrowawayTsa tsa = new ThrowawayTsa();
        System.out.println("měří se: " + (restamp ? "přerazítkování" : "uchování"));

        Path work = Files.createTempDirectory("anep-pdfa-ukon");
        int candidates = 0, keptAfterData = 0, keptAfterStamp = 0, lostAtData = 0, lostAtStamp = 0;
        int dataFailed = 0, stampFailed = 0, grew = 0;
        TreeMap<String, Integer> broken = new TreeMap<>();

        for (File file : files) {
            PdfSource source = PdfSource.of(file);
            if (!source.isPdf()) continue;
            PdfProfileExtractor.Profile profile = PdfProfileExtractor.extract(source, null);
            if (profile == null || profile.pdfaFlavour() == null) continue;
            PdfAConformanceChecker.Conformance before =
                    PdfAConformanceChecker.check(source, profile);
            // Ptát se, co úkon zkazí, má smysl jen tam, kde bylo co zkazit.
            if (before == null || before.inconclusive() || !before.compliant()) continue;
            if (candidates >= max) break;
            candidates++;

            byte[] withData;
            try {
                DSSDocument out;
                if (restamp) {
                    // Přerazítkování staví na tom, co ověření o dokumentu zjistilo: vkládá
                    // materiál, o který se opřel verdikt. Bez diagnostických dat by se měřilo
                    // něco jiného než ten úkon.
                    var outcome = validators.validate(new FileDocument(file), (DSSDocument) null,
                            ref.get(), null, List.of());
                    out = restamping.embedAll(file, null,
                            outcome.reports().getDiagnosticData(), List.of()).document();
                } else {
                    out = augmentation.augment(file, null, List.of(), false);
                }
                try (InputStream in = out.openStream()) { withData = in.readAllBytes(); }
            } catch (Throwable e) {
                dataFailed++;
                continue;
            }
            boolean reallyChanged = withData.length > file.length();
            if (reallyChanged) grew++;

            PdfAConformanceChecker.Conformance afterData = conformanceOf(withData);
            if (afterData == null || afterData.inconclusive()) { dataFailed++; continue; }
            if (!afterData.compliant()) {
                lostAtData++;
                report("! data", file, before, afterData, broken);
                continue;
            }
            keptAfterData++;

            // Druhá polovina: archivní razítko nad tím, co z první vyšlo.
            Path tmp = work.resolve("stamped.pdf");
            byte[] stamped;
            try {
                Files.write(tmp, withData);
                DSSDocument out = timestamping.addTimestampDirect(
                        tmp.toFile(), null, tsa, DigestAlgorithm.SHA256).doc();
                try (InputStream in = out.openStream()) { stamped = in.readAllBytes(); }
            } catch (Throwable e) {
                stampFailed++;
                System.out.println("- razítko neproběhlo: " + file.getName()
                        + "   " + e.getClass().getSimpleName() + ": " + e.getMessage());
                continue;
            } finally {
                Files.deleteIfExists(tmp);
            }

            PdfAConformanceChecker.Conformance afterStamp = conformanceOf(stamped);
            if (afterStamp == null || afterStamp.inconclusive()) { stampFailed++; continue; }
            if (afterStamp.compliant()) {
                keptAfterStamp++;
            } else {
                lostAtStamp++;
                report("! razítko", file, afterData, afterStamp, broken);
            }
        }
        Files.deleteIfExists(work);

        System.out.println();
        System.out.println("uchovatelných a před úkonem vyhovujících PDF/A: " + candidates);
        System.out.println("  z toho soubor po vložení dat opravdu narostl: " + grew);
        System.out.println("  po vložení dat konformní: " + keptAfterData
                + ", ZTRACENO: " + lostAtData + ", neproběhlo: " + dataFailed);
        System.out.println("  po archivním razítku konformní: " + keptAfterStamp
                + ", ZTRACENO: " + lostAtStamp + ", neproběhlo: " + stampFailed);
        if (!broken.isEmpty()) {
            System.out.println("pravidla, která úkony nově porušují:");
            broken.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(e -> System.out.println("   " + e.getKey() + "   " + e.getValue()));
        }
    }

    private static PdfAConformanceChecker.Conformance conformanceOf(byte[] pdf) {
        PdfSource source = PdfSource.of(pdf);
        PdfProfileExtractor.Profile profile = PdfProfileExtractor.extract(source, null);
        return profile == null ? null : PdfAConformanceChecker.check(source, profile);
    }

    private static void report(String what, File file, PdfAConformanceChecker.Conformance before,
                               PdfAConformanceChecker.Conformance after,
                               TreeMap<String, Integer> broken) {
        Set<String> was = new LinkedHashSet<>();
        for (PdfAConformanceChecker.Violation v : before.violations()) was.add(v.reference());
        System.out.println(what + " rozbilo konformitu: " + file.getName());
        for (PdfAConformanceChecker.Violation v : after.violations()) {
            if (!was.contains(v.reference())) {
                System.out.println("      nově porušeno: " + v.reference() + "   " + v.message());
                broken.merge(v.reference(), 1, Integer::sum);
            }
        }
    }

    private static void collect(File file, List<File> out) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) collect(child, out);
        } else if (file.isFile()) {
            out.add(file);
        }
    }
}
