package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.CustomCrlStore;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.FixationService;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.RestampingService;
import cz.digitalnikontinuita.anep.core.TimestampingService;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.AdvisorView;
import eu.europa.esig.dss.diagnostic.CertificateRevocationWrapper;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.RevocationWrapper;
import eu.europa.esig.dss.enumerations.RevocationType;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Co přerazítkování do dokumentu skutečně přidá — a k čemu je to komu.
 *
 * <p>Otázka (17. 10. 2026): přerazítkování vkládá revokační údaje doslova, i ty vydané po expiraci
 * certifikátu. Validátoru podle standardů nepomohou (rozhodný okamžik leží před expirací) a Acrobat
 * je podle měření z 21. 9. 2026 nevyužije také. Zbývají validátory s vlastní zásobou revokačních
 * údajů — a těm vložený údaj pomůže jen tehdy, když ho samy nemají a nesežene ho ani on-line.
 * Sonda proto u každé nově přidané položky zjistí dvě věci: zda je post-expirační, a odkud přišla
 * (dnes stažená on-line — tu si validátor se zásobou opatří sám; z vlastního úložiště CRL
 * uživatele — tu nejspíš nemá nikdo jiný).
 *
 * <p>Zda se přerazítkování nabídne, rozhoduje skutečný {@code AdvisorView.show()}, převzato
 * z {@link ContinuityOfferScan}. Materiál se skládá stejně jako v {@code AdvisorView} před voláním
 * {@code embedAll} (diagnostická data + CRL ze situací 33 a 45); „přidané“ je to, co ve slovníku
 * {@code /DSS} dokumentu dosud nebylo — stejné pravidlo jako {@code RestampingService.inject}.
 *
 * <p>S {@code -acrobat <složka>} uloží u dokumentů s post-expiračním přírůstkem tři podoby pro
 * ruční srovnání v Acrobatu: původní, jen razítko, materiál + razítko. Razítko jde přes zvolený
 * zdroj razítek v nastavení.
 *
 * <pre>
 *   ... RestampMaterialScan -out app/target/scan/restamp.txt [-acrobat dir] [-max 3] D:\tmp
 * </pre>
 */
public final class RestampMaterialScan extends Application {

    @Override
    public void start(Stage stage) {
        List<String> args = getParameters().getRaw();
        new Thread(() -> {
            try {
                run(args);
            } catch (Throwable e) {
                e.printStackTrace(System.out);
            }
            Platform.exit();
        }, "restamp-scan").start();
    }

    private record Item(String kind, String origin, String expiry, String detail) {}

    private void run(List<String> args) throws Exception {
        List<Path> roots = new ArrayList<>();
        List<String> lists = new ArrayList<>();
        Path out = null, acrobat = null;
        int maxAcrobat = 3;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "-out" -> out = Path.of(args.get(++i));
                case "-acrobat" -> acrobat = Path.of(args.get(++i));
                case "-max" -> maxAcrobat = Integer.parseInt(args.get(++i));
                // "@C:\..." není platná cesta, proto se seznam odkládá zvlášť (viz ContinuityOfferScan).
                default -> {
                    String v = args.get(i);
                    if (v.startsWith("@")) lists.add(v.substring(1)); else roots.add(Path.of(v));
                }
            }
        }
        if ((roots.isEmpty() && lists.isEmpty()) || out == null) {
            System.out.println("usage: RestampMaterialScan -out <file> [-acrobat dir] <folder>...");
            return;
        }

        AppSettings settings = AppSettings.load();
        settings.setExpertMode(false);
        Files.createDirectories(out.toAbsolutePath().getParent());

        Set<String> customCrls = new HashSet<>();
        for (CustomCrlStore.Entry e : CustomCrlStore.listCrls()) {
            customCrls.add(sha(e.crl().getEncoded()));
        }

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("# ustupky: newerOcsp=" + settings.isAcceptNewerOcsp()
                    + " expiredRev=" + settings.isAcceptExpiredRevocationSources()
                    + " sha1=" + settings.isIgnoreSha1Deprecated()
                    + " pdfNotEtsi=" + settings.isAcceptPdfNotEtsi());
            w.println("# vlastni uloziste CRL: " + customCrls.size() + " seznamu");

            TrustConfig trustConfig = new TrustConfig(settings);
            CommonCertificateVerifier verifier;
            try {
                verifier = trustConfig.buildLotlCertificateVerifier();
            } catch (Exception e) {
                verifier = trustConfig.buildOfflineCertificateVerifier();
                w.println("# duveryhodne seznamy: NEDOSTUPNE (" + e + ")");
            }
            ValidatorFactory factory = new ValidatorFactory(settings);
            DocumentLoader loader = new DocumentLoader();
            ReportMapper mapper = new ReportMapper();
            RestampingService restamp = new RestampingService();
            TimestampingService tsa = new TimestampingService(
                    new java.util.concurrent.atomic.AtomicReference<>(verifier));

            List<Path> files = new ArrayList<>();
            for (String listFile : lists) {
                for (String line : Files.readAllLines(Path.of(listFile))) {
                    if (!line.isBlank()) files.add(Path.of(line.trim()));
                }
            }
            for (Path root : roots) {
                try (var walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                        .sorted().forEach(files::add);
                }
            }
            System.out.println("### souboru: " + files.size());

            int done = 0, offered = 0, withAdded = 0, withPostExpiry = 0, onlyPostExpiry = 0,
                    withCustom = 0, savedForAcrobat = 0, withSole = 0;
            Map<String, Integer> tally = new HashMap<>();
            for (Path p : files) {
                if (++done % 25 == 0) System.out.println("### " + done + "/" + files.size());
                File f = p.toFile();
                ValidationResult result;
                DSSDocument doc;
                try {
                    doc = loader.load(f);
                    result = mapper.map(f.getName(), null,
                            factory.validate(doc, null, verifier, null), settings);
                } catch (Throwable e) {
                    continue;
                }
                String buttons = buttons(result, f, verifier, settings);
                if (!buttons.contains("Přerazítkovat")) continue;
                offered++;

                DiagnosticData dd = result.getDiagnosticData();
                List<byte[]> extra = new ArrayList<>();
                for (SignatureSummary sig : result.getSignatures()) {
                    extra.addAll(sig.getS33CrlBytes());
                    extra.addAll(sig.getS45CrlBytes());
                }
                List<Item> items = classify(doc, dd, extra, customCrls);

                boolean post = items.stream().anyMatch(i -> i.expiry().startsWith("PO-EXPIRACI"));
                boolean sole = items.stream().anyMatch(i -> i.expiry().endsWith("JEDINY"));
                boolean onlyPost = !items.isEmpty()
                        && items.stream().allMatch(i -> i.expiry().startsWith("PO-EXPIRACI"));
                if (sole) withSole++;
                boolean custom = items.stream().anyMatch(i -> i.origin().equals("VLASTNI-ULOZISTE"));
                if (!items.isEmpty()) withAdded++;
                if (post) withPostExpiry++;
                if (onlyPost) onlyPostExpiry++;
                if (custom) withCustom++;
                for (Item i : items) tally.merge(i.kind() + " " + i.expiry() + " " + i.origin(), 1, Integer::sum);

                w.println("PRERAZ\t" + p + "\tpridano=" + items.size()
                        + (post ? "\tPOST-EXP" : "") + (sole ? "\tJEDINY" : "")
                        + (custom ? "\tVLASTNI" : ""));
                for (SignatureSummary sig : result.getSignatures()) {
                    w.println("\t\t" + AdvisorView.displayedIndication(sig)
                            + (sig.getSubIndication() != null ? "/" + sig.getSubIndication() : "")
                            + "\t" + sig.getFormat() + "\tsituace=" + sig.getDetectedSituations());
                }
                for (Item i : items) {
                    w.println("\t" + i.kind() + "\t" + i.expiry() + "\t" + i.origin() + "\t" + i.detail());
                }
                // Co skutečně vloží RestampingService (od 17. 10. 2026 s pravidlem speaks): klasifikace
                // výše je vlastní kopie postupu, tohle je ostrá cesta.
                try {
                    var rep = restamp.embedAll(f, null, dd, extra).report();
                    w.println("\tEMBED\tcrl=" + rep.crls() + " ocsp=" + rep.ocsps()
                            + " vynechano=" + rep.withheld());
                    tally.merge("EMBED vynechano", rep.withheld(), Integer::sum);
                    tally.merge("EMBED vlozeno", rep.crls() + rep.ocsps(), Integer::sum);
                } catch (Exception e) {
                    w.println("\tEMBED\tselhalo: " + e);
                }
                w.flush();

                if (acrobat != null && sole && savedForAcrobat < maxAcrobat
                        && settings.getSelectedTsa() != null) {
                    savedForAcrobat++;
                    saveVariants(acrobat, savedForAcrobat, f, doc, dd, extra, restamp, tsa, settings, w);
                }
            }
            w.println();
            w.println("# souboru=" + files.size() + " prerazitkovani nabidnuto=" + offered
                    + " s pridanym materialem=" + withAdded + " s post-expiracnim=" + withPostExpiry
                    + " jen post-expiracni=" + onlyPostExpiry
                    + " post-expiracni jediny k certifikatu=" + withSole
                    + " z vlastniho uloziste=" + withCustom);
            tally.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> w.println("#   " + e.getKey() + " = " + e.getValue()));
            System.out.println("### hotovo: nabidnuto=" + offered + " post-exp=" + withPostExpiry);
        }
    }

    /** Totéž, co skládá AdvisorView a vkládá RestampingService — a jen to, co v /DSS ještě není. */
    private static List<Item> classify(DSSDocument doc, DiagnosticData dd, List<byte[]> extra,
                                       Set<String> customCrls) throws Exception {
        Set<String> present = dssStreams(doc);
        Set<String> seen = new HashSet<>();
        List<Item> items = new ArrayList<>();

        Map<String, RevocationWrapper> byHash = new HashMap<>();
        if (dd != null) {
            for (RevocationWrapper rev : dd.getAllRevocationData()) {
                byte[] bin = rev.getBinaries();
                if (bin == null || bin.length == 0) continue;
                byHash.putIfAbsent(sha(bin), rev);
            }
        }
        List<byte[]> material = new ArrayList<>();
        if (dd != null) {
            for (RevocationWrapper rev : dd.getAllRevocationData()) {
                if (rev.getBinaries() != null && rev.getBinaries().length > 0) material.add(rev.getBinaries());
            }
        }
        Set<String> extraHashes = new HashSet<>();
        for (byte[] b : extra) {
            if (b != null && b.length > 0) { material.add(b); extraHashes.add(sha(b)); }
        }

        for (byte[] bin : material) {
            String h = sha(bin);
            if (present.contains(h) || !seen.add(h)) continue;
            RevocationWrapper rev = byHash.get(h);
            String kind;
            Date issued;
            Date expiredOnCrl = null;
            List<CertificateWrapper> related = new ArrayList<>();
            String origin;
            if (customCrls.contains(h)) origin = "VLASTNI-ULOZISTE";
            else if (rev != null && rev.isInternalRevocationOrigin()) origin = "UZ-V-DOKUMENTU(" + rev.getOrigin() + ")";
            else if (rev != null) origin = "ONLINE(" + rev.getOrigin() + ")";
            else if (extraHashes.contains(h)) origin = "ONLINE(S33/S45)";
            else origin = "?";

            if (rev != null) {
                kind = rev.getRevocationType() == RevocationType.CRL ? "CRL" : "OCSP";
                issued = rev.getRevocationType() == RevocationType.CRL
                        ? rev.getThisUpdate() : rev.getProductionDate();
                expiredOnCrl = rev.getExpiredCertsOnCRL();
                for (CertificateWrapper c : dd.getUsedCertificates()) {
                    for (CertificateRevocationWrapper cr : c.getCertificateRevocationData()) {
                        if (cr != null && cr.getId().equals(rev.getId())) { related.add(c); break; }
                    }
                }
            } else {
                kind = "CRL";
                X509CRL crl = (X509CRL) CertificateFactory.getInstance("X.509")
                        .generateCRL(new ByteArrayInputStream(bin));
                issued = crl.getThisUpdate();
                if (dd != null) {
                    for (CertificateWrapper c : dd.getUsedCertificates()) {
                        try {
                            if (new X500Principal(c.getCertificateIssuerDN())
                                    .equals(crl.getIssuerX500Principal())) related.add(c);
                        } catch (Exception ignored) { }
                    }
                }
            }

            String expiry;
            if (related.isEmpty() || issued == null) {
                expiry = "NEZARAZENO";
            } else {
                boolean allAfter = true;
                for (CertificateWrapper c : related) {
                    Date na = c.getNotAfter();
                    boolean coveredByEcoc = expiredOnCrl != null && !expiredOnCrl.after(na);
                    if (na == null || !issued.after(na) || coveredByEcoc) { allAfter = false; break; }
                }
                expiry = allAfter ? "PO-EXPIRACI" : "PRED-EXPIRACI";
                // Má certifikát v dokumentu už údaj z doby své platnosti? Pak post-expirační
                // přírůstek nic nenese: změřeno 17. 10. 2026 — Acrobat u dokumentu s vloženou OCSP
                // z doby platnosti použil tu a přírůstek pominul, všechny tři podoby platné.
                if (allAfter) expiry += hasInternalBeforeExpiry(related) ? " NAVIC" : " JEDINY";
            }
            StringBuilder d = new StringBuilder("vydano=" + iso(issued));
            for (CertificateWrapper c : related) {
                d.append(" | cert do ").append(iso(c.getNotAfter()))
                 .append(c.isTrusted() ? " (kotva)" : "");
            }
            items.add(new Item(kind, origin, expiry, d.toString()));
        }
        return items;
    }

    private static boolean hasInternalBeforeExpiry(List<CertificateWrapper> certs) {
        for (CertificateWrapper c : certs) {
            boolean found = false;
            for (CertificateRevocationWrapper r : c.getCertificateRevocationData()) {
                if (r == null || !r.isInternalRevocationOrigin()) continue;
                Date d = r.getProductionDate() != null ? r.getProductionDate() : r.getThisUpdate();
                if (d != null && c.getNotAfter() != null && !d.after(c.getNotAfter())) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    private static void saveVariants(Path dir, int n, File f, DSSDocument doc, DiagnosticData dd,
                                     List<byte[]> extra, RestampingService restamp,
                                     TimestampingService tsa, AppSettings settings, PrintWriter w) {
        try {
            Files.createDirectories(dir);
            var alg = TimestampingService.resolveAlgorithm(settings.getSelectedTsa().getHashAlgorithm());
            Files.copy(f.toPath(), dir.resolve(n + "-a-puvodni.pdf"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            DSSDocument onlyTs = tsa.addTimestamp(doc, null, settings.getSelectedTsa(), alg).doc();
            write(onlyTs, dir.resolve(n + "-b-jen-razitko.pdf"));
            DSSDocument embedded = restamp.embedAll(f, null, dd, extra).document();
            DSSDocument both = tsa.addTimestamp(embedded, null, settings.getSelectedTsa(), alg).doc();
            write(both, dir.resolve(n + "-c-material-a-razitko.pdf"));
            w.println("\tACROBAT " + n + " <- " + f);
        } catch (Exception e) {
            w.println("\tACROBAT " + n + " selhalo: " + e);
        }
    }

    private static void write(DSSDocument d, Path to) throws Exception {
        try (InputStream is = d.openStream(); OutputStream os = Files.newOutputStream(to)) {
            is.transferTo(os);
        }
    }

    private static Set<String> dssStreams(DSSDocument doc) throws Exception {
        Set<String> out = new HashSet<>();
        byte[] bytes;
        try (InputStream is = doc.openStream()) { bytes = is.readAllBytes(); }
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            COSBase dssBase = pdf.getDocumentCatalog().getCOSObject()
                    .getDictionaryObject(COSName.getPDFName("DSS"));
            if (!(dssBase instanceof COSDictionary dss)) return out;
            for (String key : List.of("CRLs", "OCSPs")) {
                if (dss.getDictionaryObject(COSName.getPDFName(key)) instanceof COSArray a) {
                    for (int i = 0; i < a.size(); i++) {
                        if (a.getObject(i) instanceof COSStream s) {
                            try (InputStream is = s.createInputStream()) { out.add(sha(is.readAllBytes())); }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Zaheslované či nečitelné PDF: nic se nepovažuje za přítomné.
        }
        return out;
    }

    private static String sha(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String iso(Date d) {
        return d == null ? "?" : d.toInstant().toString().substring(0, 10);
    }

    private String buttons(ValidationResult result, File file, CommonCertificateVerifier verifier,
                           AppSettings settings) throws Exception {
        final List<String> visible = new ArrayList<>();
        final Object lock = new Object();
        final boolean[] done = { false };
        Platform.runLater(() -> {
            try {
                AdvisorView view = new AdvisorView();
                view.setAppSettings(settings);
                view.setSourceFile(file, null, null);
                var ref = new java.util.concurrent.atomic.AtomicReference<>(verifier);
                view.setFixationService(new FixationService(ref));
                view.setRestampingService(new RestampingService());
                view.setAugmentationService(new cz.digitalnikontinuita.anep.core.AugmentationService(ref));
                view.setExtensionService(new cz.digitalnikontinuita.anep.core.SignatureExtensionService(ref));
                view.setTimestampingService(new TimestampingService(ref));
                view.show(result);
                collect(view.getActionRow(), visible);
            } catch (Exception e) {
                visible.add("!! " + e);
            }
            synchronized (lock) { done[0] = true; lock.notifyAll(); }
        });
        synchronized (lock) {
            while (!done[0]) lock.wait();
        }
        return String.join(" + ", visible);
    }

    private static void collect(Node node, List<String> out) {
        if (node instanceof Button b && b.isVisible() && b.getText() != null) out.add(b.getText());
        if (node instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) collect(child, out);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
