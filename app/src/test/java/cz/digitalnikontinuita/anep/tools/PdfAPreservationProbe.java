package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.AugmentationService;
import cz.digitalnikontinuita.anep.core.PdfAConformanceChecker;
import cz.digitalnikontinuita.anep.core.PdfProfileExtractor;
import cz.digitalnikontinuita.anep.core.PdfSource;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import eu.europa.esig.dss.model.DSSDocument;
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
 * Přežije dokument, který se hlásí k PDF/A a opravdu mu vyhovuje, uchování v AnEP?
 *
 * <p>Je to otázka o digitální kontinuitě položená naruby: kdo přinese archivní dokument, aby ho
 * uchoval, nesmí dostat zpět soubor, který už archivní není. Formátová pravidla Národního
 * archivu na PDF/A stojí, takže ztráta konformity není kosmetická.
 *
 * <p>Měří se <b>první polovina úkonu</b> — vložení validačních dat
 * ({@link AugmentationService#augment}), které do souboru zapíše slovník {@code /DSS} a proudy
 * s revokačními údaji. Ta půlka žádné časové razítko nepotřebuje, takže běží bez sítě i bez
 * dotazu na razítkovací autoritu. Druhá polovina, archivní razítko, je měřena zvlášť.
 *
 * <p>Ověřovatel je záměrně offline: do dokumentu se pak vloží jen to, co v něm už je. Na
 * strukturu souboru to nemá vliv — {@code /DSS} a jeho proudy mají týž tvar bez ohledu na to,
 * kolik seznamů v nich leží — a otázka zní právě na strukturu.
 *
 * <p>Výstupy se zapisují do dočasného adresáře a po doměření se mažou; korpusu se sonda nedotkne.
 */
public final class PdfAPreservationProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: PdfAPreservationProbe <soubor|adresář>…");
            return;
        }
        List<File> files = new ArrayList<>();
        int max = Integer.MAX_VALUE;
        for (int i = 0; i < args.length; i++) {
            if ("-max".equals(args[i]) && i + 1 < args.length) { max = Integer.parseInt(args[++i]); }
            else collect(new File(args[i]), files);
        }

        AppSettings settings = AppSettings.load();
        TrustConfig trustConfig = new TrustConfig(settings);
        // Ověřovatel se sítí, ne offline: bez něj nemá uchování co vložit a vrátí dokument
        // nezměněný — což vypadá jako „konformita zachována“, a přitom se neměří nic. Padlo to
        // při prvním běhu (165 dokumentů „zachováno“, ani jeden nenarostl o bajt). Stahují se
        // revokační údaje od certifikačních autorit, tedy totéž, co dělá každé ověření; žádné
        // časové razítko se tu neobjednává.
        CommonCertificateVerifier verifier;
        try {
            verifier = trustConfig.buildLotlCertificateVerifier();
            System.out.println("důvěryhodné seznamy: načteny");
        } catch (Exception e) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
            System.out.println("důvěryhodné seznamy: NEDOSTUPNÉ (" + e + ")");
        }
        AugmentationService service = new AugmentationService(new AtomicReference<>(verifier));

        Path workDir = Files.createTempDirectory("anep-pdfa-uchovani");
        int candidates = 0, kept = 0, lost = 0, augmentFailed = 0, checkFailed = 0;
        int grew = 0, withDss = 0;
        long growth = 0;
        TreeMap<String, Integer> newlyBroken = new TreeMap<>();

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

            byte[] outBytes;
            try {
                DSSDocument out = service.augment(file, null, List.of(), false);
                try (InputStream in = out.openStream()) { outBytes = in.readAllBytes(); }
            } catch (Throwable e) {
                augmentFailed++;
                System.out.println("- uchování neproběhlo: " + file.getName()
                        + "   " + e.getClass().getSimpleName());
                continue;
            }

            Path outFile = workDir.resolve("out.pdf");
            Files.write(outFile, outBytes);
            PdfSource outSource = PdfSource.of(outBytes);
            PdfProfileExtractor.Profile outProfile = PdfProfileExtractor.extract(outSource, null);
            PdfAConformanceChecker.Conformance after = outProfile == null ? null
                    : PdfAConformanceChecker.check(outSource, outProfile);
            Files.deleteIfExists(outFile);

            if (after == null) {
                checkFailed++;
                System.out.println("? po uchování se dokument už k PDF/A nehlásí: "
                        + file.getName());
                continue;
            }
            if (after.inconclusive()) {
                checkFailed++;
                System.out.println("? ověření po uchování selhalo: " + file.getName()
                        + "   " + after.failure());
                continue;
            }
            if (after.compliant()) {
                kept++;
                // Prázdný zásah by dal týž výsledek jako zásah neškodný, a to je rozdíl, na
                // kterém tady všechno stojí. Proto se zvlášť počítá, kolikrát se soubor
                // opravdu změnil a přibyl v něm slovník /DSS.
                if (outBytes.length > file.length()) grew++;
                if (hasDss(outBytes)) withDss++;
                growth += outBytes.length - file.length();
                continue;
            }

            lost++;
            Set<String> was = refs(before);
            List<String> added = new ArrayList<>();
            for (PdfAConformanceChecker.Violation v : after.violations()) {
                if (!was.contains(v.reference())) {
                    added.add(v.reference());
                    newlyBroken.merge(v.reference(), 1, Integer::sum);
                }
            }
            System.out.println("! " + PdfAConformanceBlockName(before) + " ztraceno: "
                    + file.getName());
            for (String ref : added) System.out.println("      nově porušeno: " + ref);
        }

        Files.deleteIfExists(workDir);

        System.out.println();
        System.out.println("zpracováno položek: " + files.size());
        System.out.println("uchovatelných a před úkonem vyhovujících PDF/A: " + candidates);
        System.out.println("  konformita zachována: " + kept);
        System.out.println("     z toho soubor opravdu narostl: " + grew
                + ", nese slovník /DSS: " + withDss
                + (kept > 0 ? ", průměrný přírůstek " + (growth / kept / 1024) + " kB" : ""));
        System.out.println("  konformita ZTRACENA:  " + lost);
        System.out.println("  uchování neproběhlo:  " + augmentFailed);
        System.out.println("  ověření po úkonu se nezdařilo: " + checkFailed);
        if (!newlyBroken.isEmpty()) {
            System.out.println("pravidla, která úkon nově porušuje:");
            newlyBroken.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(e -> System.out.println("   " + e.getKey() + "   " + e.getValue()));
        }
    }

    /** „PDF/A-2b" — jen pro hlášku; blok z balíku ui sem nesmí. */
    private static String PdfAConformanceBlockName(PdfAConformanceChecker.Conformance c) {
        return "PDF/A-" + c.flavour();
    }

    /** Přibyl ve výstupu slovník s validačními daty? Bajtové síto stačí — hledá se klíč. */
    private static boolean hasDss(byte[] pdf) {
        byte[] needle = "/DSS".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + needle.length <= pdf.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (pdf[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static Set<String> refs(PdfAConformanceChecker.Conformance c) {
        Set<String> out = new LinkedHashSet<>();
        for (PdfAConformanceChecker.Violation v : c.violations()) out.add(v.reference());
        return out;
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
