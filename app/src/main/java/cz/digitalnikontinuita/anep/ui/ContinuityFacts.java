package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.TimeFormat;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import cz.digitalnikontinuita.anep.model.TimestampChain;
import cz.digitalnikontinuita.anep.model.ValidationCourse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Základní fakta o jednom autentizačním prvku — to, čím záložka Digitální kontinuita o prvku
 * začíná.
 *
 * <h2>Proč fakta, a proč první</h2>
 *
 * <p>Do 7. 9. 2026 začínaly bloky u prvku výkladem: co znamená striktní a volnější hodnocení
 * razítek, za jakých pravidel se ověřovalo, co je sunset. Čtenář se tedy dozvěděl, jak se věci
 * posuzují, ale ne <b>co má v ruce</b> — že ta lhůta v nadpisu je konec platnosti jeho vlastního
 * certifikátu, si musel domyslet, případně dohledat v Analýze a Rozšířeném přehledu.
 *
 * <p>Fakta jsou přitom to jediné, co si čtenář může sám ověřit a co nezávisí na výkladu pravidel.
 * Stojí proto nahoře, holá, a bloky pod nimi z nich už jen <b>odvozují</b> — každý pro svůj okruh
 * nástrojů. Výklad pravidel je jednou na začátku záložky, ne pětkrát u každého prvku.
 *
 * <h2>Bez nadpisu a bez rozbalování</h2>
 *
 * <p>Fakta nejsou otázka, na kterou se odpovídá, takže nedostávají rozbalovací blok: stojí přímo
 * pod jménem prvku. Rozhodnutí uživatele, a věcně souhlasí — blok se dá zavřít a čtenář by pak
 * čísla, ze kterých všechno ostatní plyne, neměl před sebou.
 *
 * <h2>Skládá se z faktů, ne z úrovně</h2>
 *
 * <p>Úroveň podle ETSI je jen úvodní slovo. Věty o razítkách a revokačních informacích se skládají
 * z toho, co je v modelu, protože úroveň a obsah se rozcházejí: PKCS #7 úroveň nemá a razítko nést
 * může, dokument v úrovni B může nést razítko VRI a LT bez důkazu existence u vloženého údaje
 * není totéž co LT s ním.
 */
final class ContinuityFacts {

    private ContinuityFacts() { }

    /**
     * Fakta jako odstavce, v pořadí: co to je → podpisový certifikát → certifikát razítka →
     * revokační údaj. Odstavce, o kterých není co říct, chybí.
     */
    static List<String> paragraphs(SignatureSummary sig) {
        List<String> out = new ArrayList<>();
        out.add(whatItIs(sig));
        String cert = signingCertificate(sig);
        if (cert != null) out.add(cert);
        String stamp = timestampCertificate(sig);
        if (stamp != null) out.add(stamp);
        String revocation = revocation(sig);
        if (revocation != null) out.add(revocation);
        return out;
    }

    // ── 1. Co to je ──────────────────────────────────────────────────────────────────────────

    /** „Jde o elektronický podpis v úrovni T, tedy s jedním platným … razítkem, ale bez …" */
    private static String whatItIs(SignatureSummary sig) {
        if (ContinuityHorizon.isTimestampElement(sig)) return loneTimestamp(sig);

        String level = level(sig.getFormat());
        StringBuilder sb = new StringBuilder("Jde o ")
                .append(AdvisorView.gendered(sig, "elektronický podpis", "elektronickou pečeť",
                        "časové razítko"));
        if (level != null) {
            // „bez časového razítka, ale bez vložených…" — spojka „ale" staví do protikladu dvě
            // věci, které jsou obě nedostatkem. Po záporu ji vypouštíme (pokyn uživatele 1. 10. 2026).
            String embedded = embeddedPhrase(sig);
            if (countedTimestamps(sig).isEmpty() && embedded.startsWith(", ale bez")) {
                embedded = ", bez" + embedded.substring(", ale bez".length());
            }
            return sb.append(" v úrovni ").append(level).append(", tedy ")
                     .append(timestampPhrase(sig)).append(embedded).toString();
        }
        // Nereferenční formát: úroveň by se dala odhadnout z obsahu, ale byla by to naše
        // konstrukce, ne vlastnost dokumentu. Radši se řekne, jak to je. Jméno formátu dává
        // AdvisorView.formatLabel(), takže z „PKCS7-T" je „PKCS #7" — úroveň nemá co dělat
        // zrovna ve větě, která říká, že pro ten formát žádná definovaná není.
        sb.append(" ve formátu ")
          .append(sig.getFormat() == null ? "neznámém" : AdvisorView.formatLabel(sig.getFormat()))
          .append(", pro který standardy úrovně nedefinují. ");
        // Věta o razítkách tu stojí samostatně, ne jako pokračování za „tedy". Po tečce se
        // předložkovou vazbou pokračovat nedá a vycházelo z toho „Nese s jedním platným
        // (kvalifikovaným elektronickým) časovým razítkem" — nález uživatele z 12. 9. 2026.
        if (countedTimestamps(sig).isEmpty()) {
            sb.append(AdvisorView.gendered(sig, "Není opatřen", "Není opatřena", "Není opatřeno"))
              .append(" žádným časovým razítkem");
            // „bez razítka, ale bez informací" se nedá přečíst; po záporné větě se druhá vada
            // řekne zvlášť — táž úprava, jakou dělá loneTimestamp().
            String embedded = embeddedPhrase(sig);
            return sb.append(embedded.startsWith(", ale bez")
                    ? ". Revokační informace vloženy nejsou." : embedded).toString();
        }
        return sb.append(AdvisorView.gendered(sig, "Je opatřen", "Je opatřena", "Je opatřeno"))
                 .append(" ").append(timestampGroup(sig))
                 .append(embeddedPhrase(sig)).toString();
    }

    /**
     * Samostatné časové razítko nad dokumentem — nemá podpisový certifikát ani podepsanou osobu.
     *
     * <p>Kvalifikovanost se bere z {@code sig.isQualified()}, ne z {@link #last}: u samostatného
     * razítka je {@code getAllTimestamps()} prázdné, takže by vyšlo „nekvalifikované“ i tam, kde
     * blok pod fakty správně říká „razítko je kvalifikované“.
     */
    private static String loneTimestamp(SignatureSummary sig) {
        String embedded = embeddedPhrase(sig);
        // Dvě „bez“ v jedné větě („bez podpisu, ale bez informací“) se nedají přečíst; když
        // vložené informace chybí, řekne se to samostatnou větou.
        if (embedded.startsWith(", ale bez")) embedded = ". Revokační informace vloženy nejsou.";
        return "Jde o samostatné " + (sig.isQualified() ? "(kvalifikované elektronické) " : "")
                + "časové razítko nad dokumentem, bez elektronického podpisu či pečeti"
                + embedded;
    }

    /** „s jedním platným (kvalifikovaným elektronickým) časovým razítkem" a jeho obdoby. */
    private static String timestampPhrase(SignatureSummary sig) {
        return countedTimestamps(sig).isEmpty()
                ? "bez časového razítka" : "s " + timestampGroup(sig);
    }

    /**
     * Táž skupina v sedmém pádě, ale bez předložky — pro větu, která si vazbu nese sama
     * („Je opatřen jedním platným … časovým razítkem“).
     *
     * <p>Volá se jen tam, kde nějaké razítko je: zápor („bez časového razítka“) se s tou vazbou
     * nesnese a řeší se ve volající větě.
     */
    private static String timestampGroup(SignatureSummary sig) {
        List<TimestampEntry> stamps = countedTimestamps(sig);
        long valid = stamps.stream().filter(TimestampEntry::passed).count();
        boolean allQualified = stamps.stream().allMatch(TimestampEntry::qualified);
        String kind = allQualified ? "(kvalifikovaným elektronickým) " : "";
        if (stamps.size() == 1) {
            return valid == 1
                    ? "jedním platným " + kind + "časovým razítkem"
                    : "jedním časovým razítkem, které ale neobstálo";
        }
        // Číslovka mění pád celé skupiny: do čtyř se počítané jméno řídí předložkou („se dvěma
        // časovými razítky“ — 7. pád), od pěti stojí v druhém („s 5 časových razítek“). Přívlastek
        // musí jít s ním, jinak vyjde „s 2 kvalifikovaných elektronických časovými razítky“.
        boolean instrumental = stamps.size() < 5;
        String kindPlural = allQualified
                ? (instrumental ? "(kvalifikovanými elektronickými) "
                                : "(kvalifikovaných elektronických) ")
                : "";
        String count = stamps.size() + " " + kindPlural
                + (instrumental ? "časovými razítky" : "časových razítek");
        // „Všemi platnými“ se také řídí 7. pádem, takže u pěti a víc nesedí; „z nichž …“ platí
        // pro obojí.
        return valid == stamps.size()
                ? count + ", z nichž všechna jsou platná"
                : count + ", z nichž " + (valid == 1 ? "je platné jedno"
                        : "jsou platná " + valid);
    }

    /**
     * „, ale bez vložených revokačních informací." / „. Vloženy jsou … v podobě …"
     *
     * <p>Druh se bere z {@link ValidationCourse#signingCertEmbeddedFact()}, tedy z údaje, který
     * dokument opravdu nese. Dřív se bral z využitého údaje a ten je u prvku, který neobstál,
     * prázdný — výchozí hodnota pak z každé vložené odpovědi OCSP udělala „CRL seznam“
     * (potkáno na „priklad6_17.pdf“). Kde druh známý není, věta ho netvrdí.
     */
    private static String embeddedPhrase(SignatureSummary sig) {
        ValidationCourse vc = sig.getValidationCourse();
        if (vc == null || !vc.signingCertHasEmbeddedRevocation()) {
            return ", ale bez vložených revokačních informací.";
        }
        ValidationCourse.RevocationFact fact = vc.signingCertEmbeddedFact();
        if (fact == null) return ". Vloženy jsou revokační informace.";
        return ". Vloženy jsou revokační informace v podobě "
                + (fact.ocsp() ? "odpovědi OCSP" : "CRL seznamu") + ".";
    }

    // ── 2. Podpisový certifikát ──────────────────────────────────────────────────────────────

    private static String signingCertificate(SignatureSummary sig) {
        if (ContinuityHorizon.isTimestampElement(sig)) return null;
        Instant until = sig.getCertNotAfter();
        if (until == null) return null;
        String holder = holder(sig);
        return AdvisorView.gendered(sig, "Podpis je založen", "Pečeť je založena",
                        "Časové razítko je založeno")
                + " na certifikátu" + (holder == null ? "" : " (držitele " + holder + ")")
                + ", kterému " + (elapsed(until) ? "skončila" : "končí")
                + " řádná doba jeho platnosti " + TimeFormat.full(until) + ".";
    }

    /** Jméno bez e-mailu v závorce — do věty se hodí jméno, ne celý řádek z certifikátu. */
    private static String holder(SignatureSummary sig) {
        String name = sig.getSignerDisplayName();
        if (name == null || name.isBlank()) return null;
        int paren = name.indexOf(" (");
        return paren > 0 ? name.substring(0, paren) : name;
    }

    // ── 3. Certifikát časového razítka ───────────────────────────────────────────────────────

    private static String timestampCertificate(SignatureSummary sig) {
        // Samostatné razítko nemá getAllTimestamps(); konec platnosti jeho certifikátu nese
        // getCertNotAfter() a jméno autority getSignerDisplayName(). Bez téhle větve stála
        // fakta o samostatném razítku bez jediného data, ačkoli je model zná.
        if (ContinuityHorizon.isTimestampElement(sig)) {
            Instant until = sig.getCertNotAfter();
            if (until == null) return null;
            String authority = holder(sig);
            return "Časové razítko je založeno na certifikátu"
                    + (authority == null ? "" : " (autority " + authority + ")")
                    + ", kterému " + (elapsed(until) ? "skončila" : "končí")
                    + " řádná doba jeho platnosti " + TimeFormat.full(until) + ".";
        }
        TimestampEntry ts = last(sig);
        if (ts == null || ts.notAfter() == null) return null;
        List<TimestampEntry> stamps = countedTimestamps(sig);
        String lead = stamps.size() > 1
                ? "Poslední z časových razítek je založeno"
                : "Časové razítko je založeno";
        String tail = stamps.size() > 1 ? chainPhrase(sig, stamps) : "";
        return lead + " na certifikátu"
                + (ts.issuer() == null ? "" : " (autority " + ts.issuer() + ")")
                + ", kterému " + (elapsed(ts.notAfter()) ? "skončila" : "končí")
                + " řádná doba jeho platnosti " + TimeFormat.full(ts.notAfter()) + "." + tail;
    }

    /**
     * Jak je řada razítek propojená. Rozhoduje o tom, zda pozdější razítko vzniklo ještě
     * v platnosti certifikátu toho předchozího — právě ta souvislost je podmínkou, aby řadu
     * uznaly i validátory posuzující razítka podle platnosti certifikátů.
     */
    private static String chainPhrase(SignatureSummary sig, List<TimestampEntry> stamps) {
        List<TimestampChain.Gap> gaps = TimestampChain.gaps(stamps);
        if (!gaps.isEmpty()) {
            return " Řada razítek je přerušena: razítko z " + TimeFormat.full(gaps.get(0).to())
                    + " vzniklo až po skončení platnosti certifikátů všech razítek, která mu"
                    + " předcházejí.";
        }
        // Konec krytí, ne konec platnosti certifikátu posledního razítka — viz TimestampChain.
        // Věta ho uvádí proto, že je to jediný údaj, který čtenář z faktů potřebuje a který se
        // z vypsaných razítek sám nedopočítá, stojí-li poslední z nich na kratším certifikátu.
        Instant reach = TimestampChain.reachFromStart(stamps);
        String until = reach == null ? ""
                : " Řada razítek tak kryje dobu do " + TimeFormat.full(reach) + ".";
        return " Každé razítko vzniklo ještě za platnosti certifikátu některého z těch"
                + " předchozích, takže řada razítek není přerušena." + until;
    }

    // ── 4. Revokační údaj ────────────────────────────────────────────────────────────────────

    /** K čemu je revokační údaj, o který se závěr opřel — a odkud se vzal. */
    private static String revocation(SignatureSummary sig) {
        ValidationCourse vc = sig.getValidationCourse();
        if (vc == null) return null;
        ValidationCourse.RevocationFact fact = vc.signingCertRevocation();
        // Prvek, který neobstál, žádný využitý údaj nemá — ale vložený nést může, a mlčet o něm
        // by znamenalo zamlčet fakt, který čtenář v souboru najde.
        if (fact == null || fact.issued() == null) return embeddedButUnused(sig, vc);
        if (stateNotEstablished(sig, vc)) return notEstablished(sig, vc, fact);

        String subject = ContinuityHorizon.isTimestampElement(sig)
                ? "certifikátu razítkovací autority" : "podpisového certifikátu";
        Instant decisive = sig.getBestSignatureTime();
        // Napřed to, k čemu údaj posloužil, teprve pak údaj sám: čtenář se ptá po stavu revokace
        // k rozhodnému okamžiku, ne po tom, kdy byla vydána odpověď.
        //
        // Rozhodný okamžik stále stojí za „tj. k", ne v závorce: TimeFormat.full sám nese
        // závorku s místním časem a vnořené závorky se nedají přečíst.
        String moment = decisive == null
                ? "" : " k rozhodnému okamžiku, tj. k " + TimeFormat.full(decisive) + ",";
        String what = fact.ocsp()
                ? " byla využita odpověď OCSP, vydaná "
                : " byl využit CRL seznam, vydaný ";
        return "Pro zjištění stavu revokace " + subject + moment + what
                + TimeFormat.full(fact.issued()) + "."
                + originPhrase(fact);
    }

    /**
     * Zůstal stav revokace nezjištěný, ačkoli si aplikace nějaký údaj označila za využitý?
     *
     * <p>„Využitý" je v modelu jen to, na co ukázal výběr rozhodujícího údaje, a ten se dělá
     * i tam, kde se ověření k závěru vůbec nedostalo. Když přitom běh odmítl právě údaj, který
     * nese sám dokument — tedy jediný současník rozhodného okamžiku —, a výběr propadl na něco
     * pořízeného až teď, nestojí závěr na ničem a věta „byla využita odpověď OCSP" tvrdí
     * o postupu víc, než co se stalo.
     *
     * <p>Naměřeno na dokumentu, kde odpověď OCSP leží v podepsaném atributu
     * {@code adbe-revocationInfoArchival}: podrobný protokol DSS vybral jako poslední přípustnou
     * tu vloženou z 22. 7. 2024, AnEP ji odmítl, a výběr propadl na odpověď staženou v tentýž
     * den, kdy ověřování běželo. Fakta pak o dokumentu z roku 2024 tvrdila, že stav revokace
     * k rozhodnému okamžiku zjistila odpověď z roku 2026.
     *
     * <p>Rozhoduje <b>volba podrobného protokolu DSS</b>, ne vlastnosti náhradní volby — viz
     * {@link ValidationCourse#signingCertSelectedRevocationUnusable()}. První pokus se ptal, zda
     * běh odmítl nějaký údaj z dokumentu a využitý vzal odjinud, a byl na 214 souborech v D:\tmp
     * změřen jako příliš široký: ohlásil se u 14 podpisů, u kterých se stav revokace zjistit
     * podařilo (DSS u nich vrací {@code OUT_OF_BOUNDS_NOT_REVOKED} nebo {@code REVOKED_NO_POE}),
     * a odmítnutý údaj s tím neměl nic společného.
     *
     * <p>Druhá podmínka je <b>INDETERMINATE podle DSS</b>: u prvku, který obstál nebo prokazatelně
     * neobstál, se stav zjistit podařilo a původní věta je v pořádku. Bere se
     * {@code sig.getIndication()}, ne verdikt karty — ta umí verdikt snížit kvůli razítkům
     * a s revokací to nemá co dělat.
     */
    private static boolean stateNotEstablished(SignatureSummary sig, ValidationCourse vc) {
        return "INDETERMINATE".equals(sig.getIndication())
                && vc.signingCertSelectedRevocationUnusable();
    }

    /**
     * Stav revokace se zjistit nepodařilo — co k tomu dokument nese a co si aplikace pořídila.
     *
     * <p>Pořadí vět kopíruje to, na co se čtenář ptá: napřed výsledek, pak vlastní materiál
     * dokumentu, a teprve nakonec to, co aplikace pořídila — právě proto, aby se pořízený údaj
     * nečetl jako podklad závěru. Proč se o vložený údaj opřít nešlo, sem nepatří: fakta jsou
     * fakta a důvod nese Analýza i blok pod nimi.
     */
    private static String notEstablished(SignatureSummary sig, ValidationCourse vc,
                                         ValidationCourse.RevocationFact used) {
        Instant decisive = sig.getBestSignatureTime();
        StringBuilder sb = new StringBuilder("Stav revokace ")
                .append(ContinuityHorizon.isTimestampElement(sig)
                        ? "certifikátu razítkovací autority" : "podpisového certifikátu");
        if (decisive != null) {
            sb.append(" k rozhodnému okamžiku, tj. k ").append(TimeFormat.full(decisive))
              .append(",");
        }
        sb.append(" se prokázat nepodařilo.");

        String embedded = embeddedButUnused(sig, vc);
        if (embedded != null) sb.append(" ").append(embedded);

        if (used.issued() != null && !"document".equals(used.origin())) {
            // Sloveso se řídí podmětem („aplikace"), takže je ženské vždycky; druh údaje mění
            // jen tvar přívlastku za ním — „odpověď … vydanou" × „seznam … vydaný".
            sb.append(" Aplikace si k témuž certifikátu ")
              .append("store".equals(used.origin())
                      ? "vzala z vlastního úložiště " : "stáhla on-line ")
              .append(used.ocsp() ? "odpověď OCSP, vydanou " : "CRL seznam, vydaný ")
              .append(TimeFormat.full(used.issued()))
              .append("; na závěru se to neprojevilo.");
        }
        return sb.toString();
    }

    /**
     * Vložený revokační údaj, který zdejší ověření nepoužilo.
     *
     * <p>Fakt to je — dokument ten údaj nese —, ale věta o něm nesmí tvrdit, že „ho lze využít“.
     * Proč ho použít nešlo, patří do Analýzy; tady se řekne jen, co v souboru je.
     */
    private static String embeddedButUnused(SignatureSummary sig, ValidationCourse vc) {
        ValidationCourse.RevocationFact embedded = vc.signingCertEmbeddedFact();
        if (embedded == null || embedded.issued() == null) return null;
        return (embedded.ocsp()
                    ? "Odpověď OCSP vložená v dokumentu byla vydána "
                    : "CRL seznam vložený v dokumentu byl vydán ")
                + TimeFormat.full(embedded.issued())
                + "; zdejší ověření " + (embedded.ocsp() ? "ji" : "jej")
                + " ale využít nemohlo — proč, stojí u "
                + AdvisorView.gendered(sig, "tohoto podpisu", "této pečeti",
                        "tohoto časového razítka") + " v Analýze.";
    }

    /** Odkud údaj přišel — na tom stojí celé odvození lhůty v bloku o AnEP. */
    private static String originPhrase(ValidationCourse.RevocationFact fact) {
        // Rod se řídí druhem údaje: odpověď OCSP je ženská, CRL seznam mužský.
        String it = fact.ocsp() ? "ji" : "jej";
        String whose = fact.ocsp() ? "jejího" : "jeho";
        String embedded = fact.ocsp() ? "vložena není" : "vložen není";
        return switch (fact.origin() == null ? "" : fact.origin()) {
            case "online" -> " V dokumentu " + embedded + ", aplikace si " + it
                    + " stáhla on-line od " + whose + " vydavatele.";
            case "store" -> " V dokumentu " + embedded + ", aplikace " + it
                    + " má ve svém vlastním úložišti.";
            default -> "";
        };
    }

    // ── Pomůcky ──────────────────────────────────────────────────────────────────────────────

    /**
     * Razítka, která se do faktů počítají.
     *
     * <p>Holá razítka VRI se vynechávají: leží v nepodepsané přírůstkové aktualizaci, dají se
     * beze stopy odstranit, a ověřovací proces z nich důkaz existence neodvozuje — počítat je do
     * úrovně by tvrdilo víc, než dokument nese.
     */
    private static List<TimestampEntry> countedTimestamps(SignatureSummary sig) {
        List<TimestampEntry> out = new ArrayList<>();
        for (TimestampEntry ts : sig.getAllTimestamps()) {
            if (!"vri_bare".equals(ts.tsKind())) out.add(ts);
        }
        return out;
    }

    /** Poslední razítko v řadě — to, kterým řada končí a jehož certifikát tedy rozhoduje. */
    private static TimestampEntry last(SignatureSummary sig) {
        List<TimestampEntry> stamps = countedTimestamps(sig);
        return stamps.isEmpty() ? null : stamps.get(stamps.size() - 1);
    }

    private static String level(String format) {
        if (format == null) return null;
        int at = format.indexOf("BASELINE-");
        return at < 0 ? null : format.substring(at + "BASELINE-".length());
    }

    private static boolean elapsed(Instant when) {
        return when != null && when.isBefore(Instant.now());
    }
}
