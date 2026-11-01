package cz.digitalnikontinuita.anep.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Údaje elektronického autorizačního razítka z předmětu podpisového certifikátu.
 *
 * <p>Elektronické autorizační razítko podle § 13 odst. 3 písm. b) zákona č. 360/1992 Sb. je
 * kvalifikovaný elektronický podpis autorizované osoby s kvalifikovaným elektronickým časovým
 * razítkem, jehož certifikát nese jméno autorizované osoby, číslo, pod nímž je zapsána
 * v seznamu autorizovaných osob vedeném Komorou, obor, popřípadě specializaci, a označení
 * Komory.
 *
 * <p>Tvar předmětu podle certifikátů ČKA a ČKAIT, které vydává PostSignum Qualified CA 4
 * (zadání uživatele 1. 11. 2026, šest vzorků z let 2024–2026):
 * <ul>
 *   <li>OU „Elektronické autorizační razítko“ — podle něj se razítko pozná;</li>
 *   <li>druhá OU — číslo autorizace: pět číslic u ČKA, sedm u ČKAIT;</li>
 *   <li>CN — jméno autorizované osoby, s tituly;</li>
 *   <li>title — obory, případně specializace („A: architektura (A1)“, „IP00“), u více oborů
 *       v jedné položce oddělené čárkou;</li>
 *   <li>O a organizationIdentifier — název a IČO Komory.</li>
 * </ul>
 *
 * @param name      jméno autorizované osoby (CN), nebo {@code null}
 * @param number    číslo autorizace (druhá OU), nebo {@code null}
 * @param fields    obor, popřípadě specializace (title), nebo {@code null}
 * @param chamber   označení Komory (O), nebo {@code null}
 * @param chamberId IČO z organizationIdentifier bez předpony {@code NTRCZ-}, nebo {@code null}
 */
record AuthorizationStamp(String name, String number, String fields, String chamber,
                          String chamberId) {

    /** Hodnota OU, podle které se elektronické autorizační razítko pozná. */
    static final String MARKER = "Elektronické autorizační razítko";

    /**
     * Komory, které aplikace zná podle IČO. Podle IČO, ne podle názvu: název v O píše každá
     * komora jinak (ČKA verzálkami), IČO je jednoznačné.
     */
    enum Chamber {
        CKA("ČKA", "45769371", 5),
        CKAIT("ČKAIT", "45770743", 7);

        final String abbreviation;
        final String ico;
        /** Kolik číslic má číslo autorizace v certifikátech této komory. */
        final int numberDigits;

        Chamber(String abbreviation, String ico, int numberDigits) {
            this.abbreviation = abbreviation;
            this.ico = ico;
            this.numberDigits = numberDigits;
        }
    }

    /**
     * Údaje razítka z předmětu certifikátu, nebo {@code null}, když předmět OU
     * „Elektronické autorizační razítko“ nenese.
     */
    static AuthorizationStamp of(String rawDn) {
        List<String> ous = DnUtil.dnFieldAll(rawDn, "2.5.4.11", "OU");
        boolean marked = false;
        List<String> others = new ArrayList<>();
        for (String ou : ous) {
            if (isMarker(ou)) marked = true;
            else others.add(ou.trim());
        }
        if (!marked) return null;

        // Číslo autorizace je ta OU, která není značkou. Kdyby jich bylo víc, má přednost
        // číselná — v certifikátech jiných vydavatelů bývá v OU i útvar organizace.
        String number = others.stream().filter(v -> v.matches("\\d+")).findFirst()
                .orElse(others.isEmpty() ? null : others.get(0));

        String orgId = DnUtil.dnField(rawDn, "2.5.4.97", "OrgId");
        String chamberId = null;
        if (orgId != null && !orgId.isBlank()) {
            chamberId = orgId.startsWith("NTRCZ-") ? orgId.substring("NTRCZ-".length()) : orgId;
        }
        return new AuthorizationStamp(
                blankToNull(DnUtil.dnField(rawDn, "2.5.4.3", "CN")),
                blankToNull(number),
                blankToNull(DnUtil.dnField(rawDn, "2.5.4.12", "T")),
                blankToNull(DnUtil.dnField(rawDn, "2.5.4.10", "O")),
                blankToNull(chamberId));
    }

    /** Komora podle IČO, nebo {@code null}, když ji aplikace nezná. */
    Chamber knownChamber() {
        if (chamberId == null) return null;
        for (Chamber c : Chamber.values()) {
            if (c.ico.equals(chamberId.trim())) return c;
        }
        return null;
    }

    /**
     * Odpovídá číslo autorizace tvaru, jaký komora používá? {@code null}, když to posoudit
     * nelze — komora není známa nebo číslo chybí.
     */
    Boolean numberFitsChamber() {
        Chamber c = knownChamber();
        if (c == null || number == null) return null;
        return number.matches("\\d{" + c.numberDigits + "}");
    }

    /** Jsou v certifikátu všechny čtyři údaje, které zákon od razítka žádá? */
    boolean contentComplete() {
        return name != null && number != null && fields != null && chamber != null;
    }

    private static boolean isMarker(String ou) {
        return ou != null && ou.trim().toLowerCase(Locale.ROOT)
                .equals(MARKER.toLowerCase(Locale.ROOT));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
