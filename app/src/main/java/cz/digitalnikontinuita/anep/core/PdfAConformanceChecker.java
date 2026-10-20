package cz.digitalnikontinuita.anep.core;

import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.ValidationResult;
import org.verapdf.pdfa.validation.profiles.RuleId;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Dostál dokument tomu, k čemu se hlásí? {@link PdfProfileExtractor} přečte prohlášení o PDF/A,
 * tenhle nástroj ho prověří — referenčním validátorem veraPDF, proti tomu znění ISO 19005,
 * ke kterému se dokument sám přihlásil.
 *
 * <p><b>Proč to stojí za to:</b> nad korpusem 2 260 PDF se jich 456 hlásí k nějakému profilu
 * PDF/A a <b>150 z nich (třetina) podmínky své vlastní deklarace nesplňuje</b>. Věta
 * „podtypu PDF/A-2b" v záhlaví je tedy u třetiny souborů nepravdivá a bez ověření se to
 * nepozná.
 *
 * <p><b>Ověřuje se jen to, co dokument deklaruje</b>, a jen PDF/A. Nabídnout ověření proti
 * profilu, ke kterému se dokument nehlásí („prošlo by to jako PDF/A-2b?"), je jiná otázka a
 * odpověď na ni je u běžného PDF skoro vždy záporná. PDF/UA se neověřuje vůbec: ISO 14289 má
 * vedle strojových podmínek i tzv. lidské kontroly, které stroj rozhodnout nemůže, takže
 * „vyhovuje" by tvrdilo víc, než veraPDF ví. (V korpusu je 16 souborů hlásících se jen
 * k PDF/UA-1; strojově z nich neprojde 12, a co to o nich vypovídá, se z toho říct nedá.)
 *
 * <p><b>Na verdikt to nemá vliv.</b> Nekonformní PDF/A je pořád platně podepsaný dokument.
 * Nález je o souboru, ne o autentizačním prvku, a nesmí se dostat mezi situace ani důvody —
 * proto se nese na {@code ValidationResult} vedle výsledku, ne v něm.
 */
public final class PdfAConformanceChecker {

    /**
     * Kolik porušených pravidel si nechat. Jeden vadný dokument jich umí vydat stovky (typicky
     * jedno pravidlo porušené na každé stránce) a k ničemu to není: čtenáři stačí vědět které,
     * ne kolikrát. Počet výskytů se u pravidla nese zvlášť.
     */
    private static final int MAX_RULES = 40;
    // Měřeno: nejhorší dokument z korpusu poruší 11 různých pravidel, takže strop nikdy
    // nepadne. Je to pojistka proti souboru, který by se choval jinak, ne běžná praxe.

    private PdfAConformanceChecker() {}

    /** Jedno porušené pravidlo normy, i s tím, kolikrát se ho dokument dopustil. */
    public record Violation(String specification, String clause, int test, String message,
                             int occurrences) {
        /** „ISO 19005-2:2011, 6.1.12-2" — odkaz do normy, dohledatelný. */
        public String reference() {
            return specification + ", " + clause + "-" + test;
        }
    }

    /**
     * Výsledek prověření.
     *
     * @param flavour    profil, proti kterému se měřilo, tak jak ho pojmenovává veraPDF („2b")
     * @param compliant  dokument podmínky splňuje
     * @param violations porušená pravidla, nejčastější napřed; prázdné, když dokument vyhověl
     *                   nebo když ověření neproběhlo
     * @param failure    proč ověření neproběhlo, nebo {@code null}, když proběhlo
     */
    public record Conformance(String flavour, boolean compliant, List<Violation> violations,
                               String failure) {
        public Conformance {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }
        /** Ověření se nezdařilo — pak se nedá tvrdit ani „vyhovuje", ani „nevyhovuje". */
        public boolean inconclusive() { return failure != null; }
    }

    /**
     * Prověří dokument proti profilu, ke kterému se hlásí.
     *
     * @param source  ověřovaný dokument
     * @param profile co o sobě prohlašuje (z {@link PdfProfileExtractor})
     * @return výsledek, nebo {@code null}, nehlásí-li se dokument k žádnému jednoznačnému
     *         profilu PDF/A — pak není co prověřovat
     */
    public static Conformance check(PdfSource source, PdfProfileExtractor.Profile profile) {
        if (source == null || profile == null || profile.pdfaFlavour() == null) return null;
        PDFAFlavour flavour = PDFAFlavour.byFlavourId(profile.pdfaFlavour());
        if (flavour == PDFAFlavour.NO_FLAVOUR) return null;

        try {
            VeraGreenfieldFoundryProvider.initialise();
            try (InputStream in = source.openRaw();
                 PDFAParser parser = Foundries.defaultInstance().createParser(in, flavour);
                 PDFAValidator validator =
                         Foundries.defaultInstance().createValidator(flavour, false)) {
                ValidationResult result = validator.validate(parser);
                return new Conformance(flavour.getId(), result.isCompliant(),
                        violations(result), null);
            }
        } catch (Throwable e) {
            // Pád nástroje není nález o dokumentu: tvrdit kvůli němu „nevyhovuje" by bylo
            // nepravdivé obvinění, proto má neúspěch vlastní, třetí výsledek.
            //
            // Že se to opravdu stává, ukázalo měření: dokud se profil nechával rozpoznat
            // parseru ({@code NO_FLAVOUR}), spadl nad 25 soubory ze 473 na
            // IndexOutOfBoundsException. Tady se profil předává výslovně — je stejně znám
            // z XMP — a při témž běhu pak neselhal ani jednou ze 456. Záchrana tedy zůstává
            // pro jistotu, ne pro každodenní provoz. Chytá se Throwable, protože odsud chodí
            // i StackOverflowError.
            return new Conformance(flavour.getId(), false, List.of(), describe(e));
        }
    }

    /** Porušená pravidla, nejčastější napřed, bez duplicit. */
    private static List<Violation> violations(ValidationResult result) {
        Map<RuleId, Integer> failed = result.getFailedChecks();
        if (failed == null || failed.isEmpty()) return List.of();

        // Znění pravidla nese až kontrolní hlášení; mapa počtů zná jen identifikátory, takže se
        // texty dohledávají v profilu, proti kterému se měřilo.
        Map<RuleId, String> texts = new java.util.HashMap<>();
        for (org.verapdf.pdfa.results.TestAssertion assertion : result.getTestAssertions()) {
            if (assertion.getStatus() == org.verapdf.pdfa.results.TestAssertion.Status.FAILED) {
                texts.putIfAbsent(assertion.getRuleId(), assertion.getMessage());
            }
        }

        List<Violation> violations = new ArrayList<>();
        for (Map.Entry<RuleId, Integer> entry : failed.entrySet()) {
            RuleId rule = entry.getKey();
            violations.add(new Violation(rule.getSpecification().getId(), rule.getClause(),
                    rule.getTestNumber(), texts.get(rule),
                    entry.getValue() == null ? 1 : entry.getValue()));
        }
        violations.sort(Comparator.comparingInt(Violation::occurrences).reversed()
                .thenComparing(Violation::clause));
        return violations.size() > MAX_RULES ? List.copyOf(violations.subList(0, MAX_RULES))
                                             : List.copyOf(violations);
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message.replace('\n', ' ').trim();
    }
}
