package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSUpdateInfo;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

import java.io.File;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.List;

/**
 * Vyrobí dokument, jaký sbírka nemá: <b>platný podpis a po něm změněný obsah</b>.
 *
 * <p>Změřeno 18. 9. 2026 ({@code PdfRevisionTailProbe}): v {@code D:\tmp} není ani jeden ze 214
 * dokumentů, u kterého by se po podpisu viditelně změnil obsah. Větev „změnil se obsah“
 * v {@link RevisionTail} a v bloku nad kartami se tedy na skutečném souboru vyzkoušet nedá
 * a spoléhá se na to, že DSS takový rozdíl ohlásí. Tenhle nástroj to spoléhání promění v měření.
 *
 * <p><b>Jak.</b> Přírůstkovou aktualizací, tedy tím, co PDF samo dovoluje: původní bajty zůstanou
 * nedotčené — a s nimi i podepsaný prefix, takže podpis <b>zůstane platný</b> — a za ně se připíše
 * nový obsahový proud, který přes stránku něco nakreslí. Přesně tak vypadá maskování obsahu,
 * proti kterému má náhled sloužit.
 *
 * <p>Nic se nepodepisuje ani nefalšuje: vzniká vzorek na zkoušení vlastního nástroje, uložený
 * stranou od sbírky. Vstupní soubor se nemění.
 *
 * <pre>java -cp … TamperedRevisionMaker &lt;vstup.pdf&gt; &lt;výstup.pdf&gt; [strana] [text]</pre>
 */
public final class TamperedRevisionMaker {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: TamperedRevisionMaker <vstup.pdf> <výstup.pdf> [strana] [text]");
            return;
        }
        File in = new File(args[0]);
        File out = new File(args[1]);
        int pageNo = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        String text = args.length > 3 ? args[3] : "ZMENENO PO PODPISU";

        // Napřed se ověří VSTUP. Bez toho by se o výsledku nedalo tvrdit to podstatné — že
        // podpis byl platný a přírůstkovou aktualizací platný zůstal. Verdikt, který by byl
        // nekladný už předtím, by tu větu proměnil v dohad.
        System.out.println("=== PŘED ZÁSAHEM ===");
        report(in);
        System.out.println();

        Files.copy(in.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        long before = out.length();

        try (PDDocument doc = Loader.loadPDF(out)) {
            PDPage page = doc.getPage(pageNo - 1);
            PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();

            // AppendMode.APPEND: k dosavadnímu obsahu stránky se připíše další proud, ten původní
            // zůstane. Tak se obsah v PDF „přepisuje“ — nic se nemaže, jen se přes to kreslí.
            try (PDPageContentStream cs = new PDPageContentStream(
                    doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                // Poloprůhledný pruh: má být vidět, že pod ním něco je. Neprůhledný obdélník by
                // vypadal jako součást návrhu stránky.
                PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                gs.setNonStrokingAlphaConstant(0.85f);
                cs.setGraphicsStateParameters(gs);

                float h = 54;
                float y = box.getUpperRightY() - box.getHeight() / 2 - h / 2;
                cs.setNonStrokingColor(1f, 0.95f, 0.4f);
                cs.addRect(box.getLowerLeftX() + 24, y, box.getWidth() - 48, h);
                cs.fill();

                cs.setNonStrokingColor(0.6f, 0f, 0f);
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 18);
                cs.newLineAtOffset(box.getLowerLeftX() + 40, y + h / 2 - 6);
                cs.showText(text);
                cs.endText();
            }

            // Bez tohohle by přírůstkový zápis nové objekty připsal, ale stránka by na ně dál
            // neukazovala — soubor by se zvětšil a nezměnilo by se nic. Označit je třeba stránku
            // i její /Contents: právě to jsou objekty, které se od původní revize liší.
            mark(page.getCOSObject());
            mark(page.getCOSObject().getDictionaryObject(COSName.CONTENTS));
            mark(page.getResources() != null ? page.getResources().getCOSObject() : null);

            try (OutputStream os = Files.newOutputStream(out.toPath(),
                    java.nio.file.StandardOpenOption.WRITE)) {
                doc.saveIncremental(os);
            }
        }

        long after = out.length();
        System.out.println("vstup:   " + in + "  (" + before + " B)");
        System.out.println("výstup:  " + out + "  (" + after + " B, přibylo " + (after - before) + " B)");
        System.out.println();
        System.out.println("=== PO ZÁSAHU ===");
        // A hned se zeptáme aplikace, co si o tom myslí. To je celý smysl nástroje: ne vyrobit
        // soubor, nýbrž zjistit, jestli se rozdíl opravdu ohlásí.
        report(out);
    }

    private static void mark(COSBase object) {
        if (object instanceof COSUpdateInfo u) u.setNeedToBeUpdated(true);
    }

    /** Co o vyrobeném souboru řekne ověření — podpis, rozdíly, a odpověď {@link RevisionTail}. */
    private static void report(File file) throws Exception {
        AppSettings settings = AppSettings.load();
        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        var outcome = new ValidatorFactory(settings)
                .validate(new DocumentLoader().load(file), null, verifier, null);
        var dd = outcome.reports().getDiagnosticData();
        var simple = outcome.reports().getSimpleReport();

        for (var sw : dd.getSignatures()) {
            System.out.println("podpis " + sw.getId().substring(0, 12)
                    + "  verdikt=" + simple.getIndication(sw.getId())
                    + "  " + (simple.getSubIndication(sw.getId()) == null
                            ? "" : simple.getSubIndication(sw.getId())));
            var rev = sw.getPDFRevision();
            if (rev == null) continue;
            System.out.println("   změny detekovány: " + rev.arePdfModificationsDetected());
            System.out.println("   vizuální rozdíl — strany: " + pages(rev.getPdfVisualDifferenceConcernedPages()));
            System.out.println("   stránkový rozdíl — strany: " + pages(rev.getPdfPageDifferenceConcernedPages()));
            System.out.println("   překryv anotací — strany: " + pages(rev.getPdfAnnotationsOverlapConcernedPages()));
        }

        // Podoby dokumentu — a hlavně: otevře se ten prefix doopravdy? To je jádro třetí
        // etapy a tvrdit to bez vyzkoušení by znamenalo spoléhat na výklad /ByteRange.
        var versions = DocumentVersions.of(dd, file.length());
        System.out.println();
        System.out.println("podoby dokumentu: " + versions.size());
        byte[] all = java.nio.file.Files.readAllBytes(file.toPath());
        for (var v : versions) {
            String opened;
            try {
                byte[] slice = v.current() ? all
                        : java.util.Arrays.copyOf(all, (int) v.endOffset());
                try (org.apache.pdfbox.pdmodel.PDDocument d =
                             PdfSource.of(slice).open(null)) {
                    opened = "otevřeno, " + d.getNumberOfPages() + " str.";
                }
            } catch (Exception e) {
                opened = "NEOTEVŘENO: " + e;
            }
            System.out.printf("   %-8d B  %-28s  %s  strany %s%n", v.endOffset(),
                    v.current() ? "(dnešní podoba)" : "(krytá revize)", opened, v.changedPages());
        }

        RevisionTail tail = RevisionTail.of(dd, file.length());
        System.out.println();
        System.out.println("RevisionTail: " + (tail == null ? "null (nic k oznámení)" : tail));
        if (tail != null) {
            System.out.println("   obsah změněn: " + tail.contentChanged()
                    + ", strany " + tail.changedPages());
        }
    }

    private static String pages(List<BigInteger> pages) {
        return pages == null || pages.isEmpty() ? "(žádné)" : pages.toString();
    }

    private TamperedRevisionMaker() { }
}
