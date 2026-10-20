package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.PDFRevisionWrapper;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Co je v souboru <b>za</b> posledním prvkem, který ho kryje.
 *
 * <p>PDF s přírůstkovými aktualizacemi je několik dokumentů v jednom souboru. Podpis ani razítko
 * nekryje celý soubor, nýbrž jednu revizi — tu, kterou vymezuje jeho {@code /ByteRange} — a
 * cokoli přibylo potom, kryté není. Tahle třída odpovídá na otázku, kterou aplikace dosud kladla
 * jen v Detailním rozboru a jen čísly: <b>je za posledním kryjícím prvkem ještě něco, a je to
 * vidět?</b>
 *
 * <p>Návrh a měření, na kterých to stojí, jsou v {@code docs/nahled-pdf-navrh.md}.
 *
 * <h2>Kryjícím prvkem je podpis i dokumentové razítko</h2>
 *
 * <p>Obojí má vlastní {@code /ByteRange}. Počítat jen podpisy je chyba, kterou návrh v první
 * podobě měl: po uchování je posledním kryjícím prvkem <b>archivní razítko</b> a kryje všechno
 * před sebou, takže by se u každého uchovaného dokumentu hlásil rozdíl, který tam není.
 *
 * <h2>Rozsah se bere z DiagnosticData, nikdy z bajtů</h2>
 *
 * <p>Hledat {@code /ByteRange} v souboru je past, kterou má popsanou {@code
 * ReportMapper.signedByteRange()}: nad {@code test na opacne poradi signed.pdf} nese soubor tři
 * takové rozsahy, ale jen dva jsou podpisy — první patří položce {@code /Perms /UR3}, tedy podpisu
 * užívacích práv, a kryje 303 541 bajtů.
 *
 * <h2>Změnil se obsah? Rozhodují dva seznamy, ne čtyři kategorie</h2>
 *
 * <p><b>Změřeno 18. 9. 2026 na 214 souborech z {@code D:\tmp}</b> ({@code PdfRevisionTailProbe}):
 *
 * <ul>
 *   <li><b>Překryv anotací není rozdíl mezi revizemi.</b> 15 dokumentů má poslední kryjící prvek
 *       na úplném konci souboru — za ním není ani bajt — a DSS u nich překryv přesto hlásí;
 *       vizuální rozdíl v takovém případě nehlásí ani jednou. Je to vlastnost <b>jedné</b> revize
 *       (anotace překrývající se navzájem), ne rozdíl mezi dvěma, a do téhle otázky nepatří.</li>
 *   <li><b>Kategorie objektových změn na otázku neodpovídají.</b> 13 souborů hlásí „vyplnění
 *       formulářů" nebo „změnu anotací" a přitom u nich DSS nenajde jediný vizuální rozdíl.
 *       Slouží tedy k pojmenování toho, <i>co</i> přibylo, ne k odpovědi, zda to čtenář uvidí.</li>
 * </ul>
 *
 * <p>Obsah se proto považuje za změněný tehdy a jen tehdy, když DSS hlásí <b>vizuální nebo
 * stránkový</b> rozdíl.
 *
 * @param lastElement    čím je poslední kryjící prvek — podpis, nebo razítko
 * @param tailBytes      kolik bajtů je za ním; vždy kladné, jinak záznam nevznikne
 * @param changedPages   stránky, na kterých se obsah liší; prázdné, když se obsah nezměnil
 * @param validationData po prvku přibyla validační data či jiné rozšíření dokumentu
 * @param formOrSignature po prvku se sáhlo na formulářová či podpisová pole
 * @param annotations    po prvku se sáhlo na anotace
 * @param undefined      DSS zařadil změnu jako nedefinovanou
 */
public record RevisionTail(Element lastElement, long tailBytes, List<Integer> changedPages,
                           boolean validationData, boolean formOrSignature,
                           boolean annotations, boolean undefined) {

    /** Čím poslední kryjící prvek je. Rozlišuje se kvůli textům, ne kvůli výpočtu. */
    public enum Element { PODPIS, RAZITKO }

    /** Uvidí čtenář na některé stránce něco jiného, než co ten prvek kryl? */
    public boolean contentChanged() {
        return !changedPages.isEmpty();
    }

    /**
     * Záznam o konci souboru, nebo {@code null}, když <b>není o čem mluvit</b>.
     *
     * <p>Null se vrací ve třech případech a všechny znamenají „nic": dokument žádný kryjící prvek
     * nemá (nepodepsané PDF, kontejner), poslední prvek sahá na konec souboru (uchovaný dokument),
     * nebo se rozsah nepodařilo přečíst. Volající podle toho blok vůbec nestaví — rozhodnutí
     * uživatele z 18. 9. 2026: blok se ukáže jen tam, kde je co říct.
     *
     * <p>Naměřený poměr: ze 179 souborů s kryjícím prvkem sahá poslední na konec u <b>118</b>,
     * takže mlčení je ta častější odpověď a nesmí zabírat místo.
     *
     * @param fileLength délka souboru v bajtech; {@code <= 0} znamená „neznámá" a vrací se null
     */
    public static RevisionTail of(DiagnosticData diagnosticData, long fileLength) {
        if (diagnosticData == null || fileLength <= 0) return null;

        Element kind = null;
        long end = 0;
        PDFRevisionWrapper lastRevision = null;
        for (Candidate c : candidates(diagnosticData)) {
            if (c.end() <= end) continue;
            end = c.end();
            kind = c.kind();
            lastRevision = c.revision();
        }
        if (lastRevision == null || end >= fileLength) return null;

        List<Integer> pages = new ArrayList<>();
        addPages(pages, lastRevision.getPdfVisualDifferenceConcernedPages());
        addPages(pages, lastRevision.getPdfPageDifferenceConcernedPages());
        pages.sort(Comparator.naturalOrder());

        return new RevisionTail(kind, fileLength - end, List.copyOf(pages),
                !lastRevision.getPdfExtensionChanges().isEmpty(),
                !lastRevision.getPdfSignatureOrFormFillChanges().isEmpty(),
                !lastRevision.getPdfAnnotationChanges().isEmpty(),
                !lastRevision.getPdfUndefinedChanges().isEmpty());
    }

    private record Candidate(Element kind, long end, PDFRevisionWrapper revision) { }

    private static List<Candidate> candidates(DiagnosticData dd) {
        List<Candidate> out = new ArrayList<>();
        for (SignatureWrapper sw : dd.getSignatures()) {
            long end = coveredUntil(sw.getPDFRevision());
            if (end > 0) out.add(new Candidate(Element.PODPIS, end, sw.getPDFRevision()));
        }
        for (TimestampWrapper tw : dd.getTimestampList()) {
            long end = coveredUntil(tw.getPDFRevision());
            if (end > 0) out.add(new Candidate(Element.RAZITKO, end, tw.getPDFRevision()));
        }
        return out;
    }

    /**
     * Kam revize sahá.
     *
     * <p>{@code /ByteRange} je {@code [0, a, b, c]}: prvek kryje bajty {@code 0..a} a
     * {@code b..b+c}, mezi {@code a} a {@code b} leží jeho vlastní {@code /Contents}. Revize tedy
     * končí na {@code b + c} a prefix souboru po tenhle bajt je úplné, samostatně načtitelné PDF.
     *
     * <p>Viditelné v balíku, aby {@link DocumentVersions} počítal totéž číslo touž cestou.
     * Je v něm past s {@code /Perms /UR3} i pojistka na neplatný rozsah, a mít to na dvou
     * místech znamená čekat, až se rozejdou.
     *
     * @return konec revize, nebo 0, když prvek rozsah nemá nebo je poškozený
     */
    static long coveredUntil(PDFRevisionWrapper revision) {
        if (revision == null) return 0;
        // Neplatný rozsah znamená, že prefix není tím, co prvek kryje — pak se o něm nedá tvrdit
        // vůbec nic a mlčet je jediná pravdivá odpověď.
        if (!revision.isSignatureByteRangeValid()) return 0;
        List<BigInteger> br = revision.getSignatureByteRange();
        if (br == null || br.size() != 4) return 0;
        if (br.get(2) == null || br.get(3) == null) return 0;
        long end = br.get(2).longValue() + br.get(3).longValue();
        return end > 0 ? end : 0;
    }

    private static void addPages(List<Integer> into, List<BigInteger> pages) {
        if (pages == null) return;
        for (BigInteger p : pages) {
            if (p == null) continue;
            int page = p.intValue();
            if (!into.contains(page)) into.add(page);
        }
    }
}
