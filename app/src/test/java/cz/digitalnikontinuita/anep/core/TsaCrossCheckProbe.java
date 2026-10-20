package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lže měřicí přípravek? Tentýž dokument se orazítkuje {@link ThrowawayTsa} a skutečnou
 * autoritou a porovná se, co na to řekne ověření shody s PDF/A.
 *
 * <p>Je to sonda na přístroj, ne na aplikaci, a má svůj důvod: dokud {@link ThrowawayTsa}
 * nezapínala v žádosti příznak {@code certReq}, vydávala razítka bez certifikátu vydavatele
 * a měření z toho vyvodilo, že archivní razítko boří PDF/A. Deset dokumentů obviněných
 * z vady, kterou měl přípravek. Tohle je ta kontrola, která to odhalí podruhé.
 *
 * <p>Shodnou-li se obě autority, je ta jednorázová pro danou otázku plnohodnotnou náhradou
 * a měření může běžet bez sítě. Rozejdou-li se, platí jen to, co řekla skutečná.
 *
 * <p>Použitá autorita je <b>testovací TSA DEMO PostSignum</b>, zveřejněná i s přihlašovacími
 * údaji přímo pro tenhle účel (postsignum.cz → Kvalifikovaná časová razítka → Testovací časová
 * razítka). Razítka z ní nejsou důvěryhodná a být nemají; tady na tom nezáleží, protože se
 * měří tvar souboru, ne platnost razítka. Služba nemusí být trvale v provozu — nedostupnost
 * není nález.
 */
public final class TsaCrossCheckProbe {
    public static void main(String[] args) throws Exception {
        int max = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        List<File> files = new ArrayList<>();
        collect(new File(args[0]), files);

        AppSettings settings = AppSettings.load();
        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier;
        try { verifier = trustConfig.buildLotlCertificateVerifier(); }
        catch (Exception e) { verifier = trustConfig.buildOfflineCertificateVerifier(); }
        AtomicReference<CommonCertificateVerifier> ref = new AtomicReference<>(verifier);
        AugmentationService augmentation = new AugmentationService(ref);
        TimestampingService timestamping = new TimestampingService(ref);
        ThrowawayTsa fake = new ThrowawayTsa();

        TsaProfile demo = new TsaProfile("PostSignum DEMO",
                "https://demo.postsignum.cz:444/DEMO-TSS/HttpTspServer/",
                "demoTSA", "demoTSA2010", TsaProfile.HASH_SHA256);

        Path work = Files.createTempDirectory("anep-tsa-cross");
        int done = 0, agree = 0, disagree = 0;
        for (File f : files) {
            if (done >= max) break;
            PdfSource src = PdfSource.of(f);
            if (!src.isPdf()) continue;
            PdfProfileExtractor.Profile p = PdfProfileExtractor.extract(src, null);
            if (p == null || p.pdfaFlavour() == null) continue;
            PdfAConformanceChecker.Conformance before = PdfAConformanceChecker.check(src, p);
            if (before == null || before.inconclusive() || !before.compliant()) continue;

            byte[] withData;
            try {
                DSSDocument out = augmentation.augment(f, null, List.of(), false);
                try (InputStream in = out.openStream()) { withData = in.readAllBytes(); }
            } catch (Throwable e) { continue; }

            Boolean fakeOk = stampAndCheck(timestamping, work, withData, fake);
            Boolean realOk = stampAndCheckReal(timestamping, work, withData, demo);
            if (fakeOk == null || realOk == null) {
                System.out.println("? " + f.getName() + "   jednorazova=" + fakeOk
                        + "  PostSignum DEMO=" + realOk);
                continue;
            }
            done++;
            if (fakeOk.equals(realOk)) { agree++; }
            else {
                disagree++;
                System.out.println("! ROZPOR  " + f.getName()
                        + "   jednorazova=" + fakeOk + "  PostSignum DEMO=" + realOk);
            }
            System.out.println("  " + f.getName() + "  jednorazova=" + fakeOk
                    + "  PostSignum=" + realOk);
        }
        Files.deleteIfExists(work);
        System.out.println();
        System.out.println("porovnano dokumentu: " + done + ", shoda: " + agree
                + ", ROZPOR: " + disagree);
    }

    private static Boolean stampAndCheck(TimestampingService svc, Path work, byte[] pdf,
                                         ThrowawayTsa tsa) {
        try {
            Path tmp = work.resolve("a.pdf");
            Files.write(tmp, pdf);
            DSSDocument out = svc.addTimestampDirect(tmp.toFile(), null, tsa,
                    DigestAlgorithm.SHA256).doc();
            byte[] b;
            try (InputStream in = out.openStream()) { b = in.readAllBytes(); }
            Files.deleteIfExists(tmp);
            return conformant(b);
        } catch (Throwable e) {
            System.out.println("    jednorazova selhala: " + e);
            return null;
        }
    }

    private static Boolean stampAndCheckReal(TimestampingService svc, Path work, byte[] pdf,
                                             TsaProfile profile) {
        try {
            Path tmp = work.resolve("b.pdf");
            Files.write(tmp, pdf);
            DSSDocument out = svc.addTimestamp(tmp.toFile(), null, profile,
                    DigestAlgorithm.SHA256).doc();
            byte[] b;
            try (InputStream in = out.openStream()) { b = in.readAllBytes(); }
            Files.deleteIfExists(tmp);
            return conformant(b);
        } catch (Throwable e) {
            System.out.println("    PostSignum DEMO selhala: " + e);
            return null;
        }
    }

    private static Boolean conformant(byte[] pdf) {
        PdfSource s = PdfSource.of(pdf);
        PdfProfileExtractor.Profile p = PdfProfileExtractor.extract(s, null);
        if (p == null) return null;
        PdfAConformanceChecker.Conformance c = PdfAConformanceChecker.check(s, p);
        if (c == null || c.inconclusive()) return null;
        if (!c.compliant()) {
            for (PdfAConformanceChecker.Violation v : c.violations()) {
                System.out.println("      porusuje: " + v.reference());
            }
        }
        return c.compliant();
    }

    private static void collect(File f, List<File> out) {
        if (f.isDirectory()) {
            File[] k = f.listFiles();
            if (k != null) for (File x : k) collect(x, out);
        } else if (f.isFile()) out.add(f);
    }
}
