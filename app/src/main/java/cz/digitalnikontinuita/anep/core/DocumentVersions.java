package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.PDFRevisionWrapper;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Podoby, které dokument v souboru má — dnešní a ty, které kryjí jednotlivé podpisy a razítka.
 *
 * <p>PDF s přírůstkovými aktualizacemi je několik dokumentů v jednom souboru. Tahle třída z nich
 * udělá <b>seznam, ze kterého se dá vybírat</b>: nejstarší podoba napřed, dnešní naposled.
 * Návrh a měření jsou v {@code docs/nahled-pdf-navrh.md}, oddíly 4 a 5.
 *
 * <h2>Jen tolik položek, kolik je různých verzí</h2>
 *
 * <p>Rozhodnutí uživatele z 18. 9. 2026. Dokument s pěti podpisy a třemi razítky má osm krytých
 * revizí, ale verzí, které se od sebe pro čtenáře liší, bývá podstatně míň: uchování přidá
 * validační data a razítko, obsah se přitom nezmění o jediný pixel. Osm položek by tedy bylo osm
 * odpovědí na otázku, která má dvě.
 *
 * <p>Slévá se podle toho, <b>čím se revize liší od dnešní podoby</b> — tedy podle seznamu stran
 * s vizuálním či stránkovým rozdílem, který k ní hlásí DSS:
 *
 * <ul>
 *   <li><b>Prázdný seznam</b> znamená, že ta revize vypadá jako dnešní soubor. Vlastní položku
 *       nedostane; splyne s „dnešní podobou".</li>
 *   <li><b>Shodné seznamy</b> u několika po sobě jdoucích prvků znamenají revize, které se od
 *       dneška liší týmž. Splynou v jednu položku, pojmenovanou podle <b>nejstaršího</b> z nich —
 *       to je ten, kdo tu podobu podepsal jako první.</li>
 * </ul>
 *
 * <p><b>Co se tím předpokládá a co ne.</b> DSS hlásí rozdíl vždy <b>proti dnešnímu souboru</b>,
 * ne mezi dvěma revizemi navzájem. Dvě revize, které se od dneška liší na týchž stranách, se tu
 * proto považují za tutéž podobu — dokázané to není, jen je to jediné, co se z dostupných údajů
 * dá usoudit, a chybou by bylo nanejvýš o jednu položku míň.
 *
 * <h2>A prvek, který sám kreslí</h2>
 *
 * <p>Nález uživatele z 22. 9. 2026: dokument se čtyřmi podpisy, každý s vlastní vizualizací,
 * nabídku nedostal vůbec. DSS totiž porovnává stránky <b>se zakrytými podpisovými poli</b>, takže
 * nový podpis s razítkem přes půl stránky pro ně rozdíl není — a pravidlo výše z toho udělalo
 * jedinou položku. Přitom právě tohle je otázka, kvůli které se podoby nabízejí: jak dokument
 * vypadal, než na něj tenhle podpis přibyl.
 *
 * <p>Proto druhá podmínka: <b>vlastní položku dostane i prvek, jehož podpisové pole je na stránce
 * vidět</b>. Kdo nekreslí nic — dokumentové a archivní razítko, neviditelný podpis, validační
 * data —, se slévá dál, takže důvod původního pravidla zůstává v platnosti.
 *
 * <p><b>Ale jen dokud po něm kreslí ještě někdo další.</b> Nález uživatele z 23. 9. 2026: dokument
 * s jedním podpisem, uchovaný z B-T na B-LTA (jen validační data a archivní razítko, obsah beze
 * změny), dostal podle druhé podmínky vlastní položku i tak — k nerozeznání od dnešní podoby. Maska
 * schová jen rozdíl způsobený <b>novým</b> polem; nepřibude-li po prvku žádné další viditelné pole,
 * nemá co schovávat, a prázdný výsledek DSS platí i pro něj. Druhá podmínka proto platí jen tehdy,
 * kreslí-li po tomhle prvku ještě aspoň jeden pozdější.
 *
 * <p><b>Překryv anotací se nepočítá.</b> Změřeno 18. 9. 2026: 15 dokumentů z {@code D:\tmp} hlásí
 * překryv, ačkoli za jejich posledním kryjícím prvkem není ani bajt. Je to vlastnost jedné revize,
 * ne rozdíl mezi dvěma. Viz {@link RevisionTail}.
 */
public final class DocumentVersions {

    /**
     * Jedna podoba dokumentu.
     *
     * @param label        jak se jmenuje v nabídce
     * @param endOffset    kolik prvních bajtů souboru ji tvoří; u dnešní podoby délka souboru
     * @param current      jde o dnešní podobu souboru?
     * @param changedPages strany, kterými se liší od dnešní podoby; u dnešní prázdné
     */
    public record Version(String label, long endOffset, boolean current,
                          List<Integer> changedPages) { }

    private DocumentVersions() { }

    /**
     * Seznam podob, od nejstarší po dnešní. Poslední je vždy dnešní soubor.
     *
     * <p>Vrací <b>jednu položku</b> (dnešní podobu), kdykoli není z čeho vybírat — dokument bez
     * kryjícího prvku, nebo takový, jehož všechny revize vypadají jako dnešek. Volající podle
     * toho nabídku vůbec nestaví.
     */
    public static List<Version> of(DiagnosticData diagnosticData, long fileLength) {
        return of(diagnosticData, fileLength, Set.of());
    }

    /**
     * Totéž, a navíc s prvky, které samy kreslí viditelnou vizualizaci.
     *
     * @param visibleAppearances identifikátory prvků (z {@code DiagnosticData}), jejichž podpisové
     *                           pole je na stránce vidět. Každý takový dostane vlastní položku,
     *                           i když o něm DSS žádný rozdíl nehlásí.
     */
    public static List<Version> of(DiagnosticData diagnosticData, long fileLength,
                                   Set<String> visibleAppearances) {
        Set<String> visible = visibleAppearances == null ? Set.of() : visibleAppearances;
        List<Version> out = new ArrayList<>();
        if (diagnosticData != null && fileLength > 0) {
            List<Element> elements = elements(diagnosticData);
            elements.sort(Comparator.comparingLong(Element::end));

            // Kreslí ještě NĚKDO pozdější? DSS maskuje pole, která zná z dnešního dokumentu, když
            // porovnává kteroukoli revizi s dneškem — a prázdný výsledek u prvku, který sám kreslí,
            // proto nejde věřit, přibude-li po něm pole další: to je přesně ten rozdíl, který
            // maska schová. Nepřibude-li po něm žádné, maska nic neschovává — jeho vlastní pole
            // vzniklo jednou a do dneška se nezměnilo, takže prázdný výsledek platí i pro něj.
            boolean[] laterDraws = new boolean[elements.size()];
            boolean seenDraw = false;
            for (int i = elements.size() - 1; i >= 0; i--) {
                laterDraws[i] = seenDraw;
                if (visible.contains(elements.get(i).id())) seenDraw = true;
            }

            List<Integer> groupPages = null;
            Element groupFirst = null;
            for (int i = 0; i < elements.size(); i++) {
                Element e = elements.get(i);
                boolean draws = visible.contains(e.id());
                // Nález uživatele z 23. 9. 2026: uchování jednoho podepsaného dokumentu (přidá jen
                // archivní razítko, obsah beze změny) nabízelo dvě podoby k nerozeznání, protože
                // podpis draws a leží před koncem souboru. Prázdnému výsledku DSS se ale smí věřit,
                // dokud po prvku nekreslí nikdo další — viz laterDraws výš.
                boolean maskUnreliable = draws && laterDraws[i];
                if (e.end() >= fileLength || (e.changedPages().isEmpty() && !maskUnreliable)) {
                    // Revize vypadá jako dnešní soubor — vlastní položku nedostane.
                    continue;
                }
                // Prvek s vlastní vizualizací otevírá novou skupinu vždycky: podoba před ním se
                // od té za ním liší aspoň o jeho razítko na stránce, i když o tom DSS mlčí.
                if (draws || groupPages == null || !groupPages.equals(e.changedPages())) {
                    if (groupFirst != null) out.add(groupFirst.toVersion());
                    groupFirst = e;
                    groupPages = e.changedPages();
                }
                // Jinak se prvek přidá k běžící skupině: jméno nese ten nejstarší, tedy ten,
                // který skupinu otevřel.
            }
            if (groupFirst != null) out.add(groupFirst.toVersion());
        }
        out.add(new Version("Dnešní podoba souboru", fileLength, true, List.of()));
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ sběr

    private record Element(String id, String label, long end, List<Integer> changedPages) {
        Version toVersion() {
            return new Version(label, end, false, changedPages);
        }
    }

    private static List<Element> elements(DiagnosticData dd) {
        List<Element> out = new ArrayList<>();
        for (SignatureWrapper sw : dd.getSignatures()) {
            long end = RevisionTail.coveredUntil(sw.getPDFRevision());
            if (end <= 0) continue;
            out.add(new Element(sw.getId(), signatureLabel(sw), end,
                    changedPages(sw.getPDFRevision())));
        }
        for (TimestampWrapper tw : dd.getTimestampList()) {
            long end = RevisionTail.coveredUntil(tw.getPDFRevision());
            if (end <= 0) continue;
            out.add(new Element(tw.getId(), timestampLabel(tw), end,
                    changedPages(tw.getPDFRevision())));
        }
        return out;
    }

    /**
     * Čím se revize liší od dnešní podoby. Vizuální a stránkový rozdíl, nic jiného — kategorie
     * objektových změn na otázku „uvidí to čtenář" neodpovídají a překryv anotací není rozdíl
     * mezi revizemi vůbec (obojí změřeno, viz {@link RevisionTail}).
     */
    private static List<Integer> changedPages(PDFRevisionWrapper revision) {
        Set<Integer> pages = new LinkedHashSet<>();
        add(pages, revision.getPdfVisualDifferenceConcernedPages());
        add(pages, revision.getPdfPageDifferenceConcernedPages());
        return pages.stream().sorted().toList();
    }

    private static void add(Set<Integer> into, List<BigInteger> pages) {
        if (pages == null) return;
        for (BigInteger p : pages) {
            if (p != null) into.add(p.intValue());
        }
    }

    /**
     * „Jak dokument vypadal, když jej podepsal/a Jan Novák (3. 8. 2024)“.
     *
     * <p>Ne „revize 2“: čtenář neví, co je revize, a vědět to nemusí. Jméno se bere z podpisového
     * certifikátu, protože to je ten údaj, který o podepsaném platí i tehdy, když se {@code /Name}
     * ve slovníku podpisu nevyplnilo nebo lže.
     *
     * <p><b>„podepsal/a"</b>, ne „podepsal": z certifikátu se pohlaví nepozná a nabídka stojí
     * vedle jmen, která jsou často ženská (pokyn uživatele 22. 9. 2026). U razítka ta otázka
     * nevzniká — razítkuje autorita, a ta je v češtině rodu ženského.
     */
    private static String signatureLabel(SignatureWrapper sw) {
        String who = name(sw.getSigningCertificate());
        Date when = sw.getClaimedSigningTime();
        return "Jak dokument vypadal, když jej podepsal/a"
                + (who == null ? "" : " " + who)
                + (when == null ? "" : " (" + day(when) + ")");
    }

    /** „…když jej orazítkovala autorita PostSignum (3. 8. 2024, 10:14)“. */
    private static String timestampLabel(TimestampWrapper tw) {
        String who = name(tw.getSigningCertificate());
        Date when = tw.getProductionTime();
        return "Jak dokument vypadal, když jej orazítkovala autorita"
                + (who == null ? "" : " " + who)
                + (when == null ? "" : " (" + minute(when) + ")");
    }

    /**
     * Krátký čas do nabídky.
     *
     * <p>{@code TimeFormat.full()} se sem nehodí: píše okamžik v UTC <b>i</b> v místním čase,
     * protože slouží faktům, u kterých na přesném okamžiku záleží. V rozbalovací nabídce by
     * z toho byl řádek na dvě šířky okna. Tady stačí den — a u razítka den a minuta, protože
     * razítek bývá nad jedním dokumentem několik za sebou a datum by je nerozlišilo.
     */
    private static String day(Date when) {
        return DAY.withZone(PRAGUE).format(when.toInstant());
    }

    private static String minute(Date when) {
        return MINUTE.withZone(PRAGUE).format(when.toInstant());
    }

    private static final java.time.ZoneId PRAGUE = java.time.ZoneId.of("Europe/Prague");
    private static final java.time.format.DateTimeFormatter DAY =
            java.time.format.DateTimeFormatter.ofPattern("d. M. yyyy");
    private static final java.time.format.DateTimeFormatter MINUTE =
            java.time.format.DateTimeFormatter.ofPattern("d. M. yyyy H:mm");

    /** Čitelné jméno z certifikátu; {@code null}, když se nedá získat. */
    private static String name(CertificateWrapper certificate) {
        if (certificate == null) return null;
        String cn = certificate.getCommonName();
        if (cn != null && !cn.isBlank()) return cn;
        String org = certificate.getOrganizationName();
        return org != null && !org.isBlank() ? org : null;
    }
}
