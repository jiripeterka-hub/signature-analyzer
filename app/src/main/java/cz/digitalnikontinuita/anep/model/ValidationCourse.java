package cz.digitalnikontinuita.anep.model;

import java.time.Instant;
import java.util.List;

/**
 * What actually happened during one signature's verification: which moment the verdict was
 * anchored to, which revocation information existed and which of it counted, and under what
 * settings the run took place.
 *
 * <p>Exists because the verdict alone hides decisions the reader may need to judge it. The
 * clearest example is the anchoring time: ETSI's best-signature-time starts at the current
 * time and is only lowered to a time-stamp once the validation process reaches that step, so a
 * run that stops earlier reports "now" even though a valid qualified time-stamp proves the
 * signature is years older. AnEP shows the time-stamp's time, DSS's own protocols show the
 * current time, and without this block the two look like a contradiction.
 *
 * <p>Every field is optional: a standalone time-stamp or an orphan signature has no course.
 */
public record ValidationCourse(

        /** When this verification ran (DSS validation time). */
        Instant validationTime,

        /** Best-signature-time as DSS itself reported it — the moment its verdict rests on. */
        Instant dssBestSignatureTime,

        /** Earliest time proven by a validated time-stamp; null when no time-stamp proves one. */
        Instant provenExistence,

        /** What proves {@link #provenExistence}, e.g. "časové razítko od …". */
        String provenExistenceNote,

        /** Why DSS did not anchor to {@link #provenExistence}; null when the two agree. */
        String bstDivergenceNote,

        /** Verification time the user set by hand, or null. */
        Instant customValidationTime,

        /**
         * Revokační údaje, o které se závěr opřel — v Analýze označené „využita".
         *
         * <p>Tři seznamy místo dvou, protože „přípustný" a „využitý" nejsou totéž. Údaj může projít
         * všemi pravidly a přesto na verdikt nedosáhnout, protože proces nemá krok, ve kterém by
         * na něm záleželo; dokud oba případy ležely pod jedním nadpisem, četl je čtenář jako
         * „použito" a pak marně hledal, proč z nich nic neplyne.
         */
        List<RevocationLine> revocationUsed,

        /**
         * Údaje, které standardy připouštějí, ale závěr na nich nestojí — „nevyužita".
         *
         * <p>Typicky odpověď stažená dnes, která hlásí odvolání, u podpisu uzavřeného podle údaje
         * vloženého v dokumentu. Vada to není, a proto to není ani {@link #revocationRejected()}.
         */
        List<RevocationLine> revocationNotDecisive,

        /** Údaje, které použít nešlo, každý s důvodem — „nedala se využít". */
        List<RevocationLine> revocationRejected,


        /** Certificates left without usable revocation information, each line saying why. */
        List<String> revocationMissing,

        /**
         * Why the signing certificate's revocation status was never asked about; empty whenever
         * it was.
         *
         * <p>Exists because the honest answer "nothing was checked, and correctly so" was
         * previously indistinguishable from a broken revocation pass: all three lists above came
         * back empty and the whole section vanished. That happens when the signing certificate is
         * itself a trust anchor — the seal on a qualified validation service's protocol is listed
         * in the Trusted List as that service's own digital identity — and ETSI EN 319 102-1 does
         * not establish a revocation status for a trust anchor. The Trusted List's own status
         * entry is what withdraws such a certificate, not a CRL.
         *
         * <p>Deliberately silent about time-stamps. A TSU certificate the Trusted List names
         * directly is in exactly the same position, but common enough that reporting it for every
         * time-stamp would bury the one line the reader came for.
         */
        List<String> revocationNotApplicable,

        /** Trust anchor, relaxed settings, custom stores — the context the run happened in. */
        List<String> contextNotes,

        /**
         * Nese dokument sám nějaký revokační údaj o podpisovém certifikátu?
         *
         * <p>Vlastní příznak, ne dotaz na {@link #signingCertRevocationUnusableFact()}: ten drží
         * <b>první</b> odmítnutý záznam v pořadí, v jakém data dorazila, a to pořadí je u stažených
         * údajů náhodné. Změřeno na „LT uznavany.sce" — dva běhy téhož sestavení, jednou první
         * vyšla stažená odpověď, podruhé vložená, a věta v Analýze se podle toho měnila. Otázka
         * „je v dokumentu vůbec něco" se přitom mezi běhy měnit nemůže.
         */
        boolean signingCertHasEmbeddedRevocation,

        /**
         * Whether a revocation entry that counted towards the verdict came from the user's own
         * CRL store rather than from the document or the network.
         *
         * <p>A boolean rather than something the "Způsob ověření" block could read out of
         * {@link #revocationUsed()}: those lines are prose meant for a reader, and deciding what
         * to tell them about other validators by matching a substring in them would break the
         * moment the wording changes.
         */
        boolean customCrlDecisive,

        /**
         * Whether any revocation entry that counted was fetched from the network rather than read
         * out of the document. Such a verdict is only reproducible while the issuer keeps
         * publishing.
         *
         * <p><b>Currently nobody reads this.</b> It was written for the part of the "Způsob
         * ověření" block that predicted how other validators would fare, and that part was
         * removed on 19. 8. 2026 — predicting a foreign tool needs facts about it that AnEP does
         * not have. The field stays because computing it is free (the loop that fills the
         * revocation lists knows it already) and because the prediction may come back; see
         * {@code docs/zpusob-overeni-navrh.md} for what it fed.
         */
        boolean revocationFetchedOnline,

        /**
         * Zda je v řetězci revokační údaj vydaný až po skončení platnosti certifikátu, o němž
         * vypovídá — bez ohledu na to, jestli ho AnEP zařadil mezi použitelné.
         *
         * <p>Při ověřování k okamžiku v minulosti to není výjimka, ale pravidlo: údaj stažený dnes
         * je vůči tehdejšímu certifikátu post-expirační vždycky. Standardy takový údaj nepřipouštějí
         * a AnEP ho v tomhle režimu přesto propouští — zúženě, viz
         * {@code PostExpiryRevokedOnlySource}. Blok „Způsob ověření" to potřebuje vědět, aby ten
         * ústupek přiznal; bez toho by nad ověřením k datu tvrdil, že proběhlo podle standardů.
         */
        boolean postExpiryRevocationPresent,

        /**
         * Revokační údaj, o který se závěr opřel, a jeho vady — nebo {@code null}, když se
         * nedá určit.
         *
         * <p>„Opřel se" znamená výběr, který udělalo samo DSS: {@code LatestAcceptableRevocationId}
         * v protokolu, tentýž údaj, jaký ukazuje řádek „Využité revokační informace". Bere se
         * proto, že rozhoduje o tom, který ústupek verdikt opravdu nesl. DSS přitom dává přednost
         * novějšímu údaji, a to je i věcně správně: odvolání je jednosměrné, kdežto „neodvolán"
         * platí jen k okamžiku vydání a od té chvíle se mohlo změnit.
         */
        PrimaryRevocation primaryRevocation,

        /**
         * The same three outcomes as the lists above, but for the signing certificate alone —
         * the one the narrative in "Průběh ověřování" has to speak about by name.
         *
         * <p>Each is the line as it would appear in the corresponding list, or {@code null} when
         * that outcome did not occur. At most one is non-null, except that a certificate can have
         * several entries; then the usable one wins, because that is what carried the verdict.
         *
         * <p>Separate fields rather than picking the right line out of {@link #revocationUsed()}
         * by matching the certificate's name: those lines are prose, and a narrative that decides
         * what to tell the reader by substring-matching them breaks the moment the wording moves.
         */
        String signingCertRevocationUsed,
        String signingCertRevocationUnusable,
        String signingCertRevocationMissing,

        /**
         * Rozepsaný údaj o revokaci, který o podpisovém certifikátu rozhodl; {@code null}, když
         * žádný použitelný nebyl.
         *
         * <p>Vedle {@link #signingCertRevocationUsed()}, který nese hotovou větu pro výčet, stojí
         * tenhle rozklad pro souvislý text: ten musí říct <b>odkud</b> údaj je, <b>kdy</b> vznikl
         * a <b>čemu</b> vděčí za to, že se vůbec směl použít. Z hotové věty by se to dalo dostat
         * jen hledáním podřetězců a rozpadlo by se to při první změně formulace.
         */
        RevocationFact signingCertRevocation,

        /** Totéž pro certifikát razítkovací autority prvního razítka, které mělo použitelný údaj. */
        RevocationFact timestampCertRevocation,

        /**
         * Rozklad údaje, který se u podpisového certifikátu <b>použít nedal</b>; {@code null},
         * když takový není. {@link RevocationFact#relaxedRule()} tu neříká, co bylo uvolněno,
         * nýbrž co údaj diskvalifikovalo — je to táž vlastnost čtená z druhé strany.
         *
         * <p>Existuje proto, že důvod odmítnutí se nedá spolehlivě odvodit ze zjištěných situací:
         * u prvku s porušenou integritou se situace 6 nehlásí, přestože revokační údaj je
         * post-expirační, a text pak sklouzl k obecné odpovědi.
         */
        RevocationFact signingCertRevocationUnusableFact,

        /**
         * Revokační údaj o podpisovém certifikátu, který <b>nese sám dokument</b> — bez ohledu na
         * to, zda ho ověření použilo, nebo odmítlo. {@code null}, když dokument žádný nenese.
         *
         * <p>Doplněno 8. 9. 2026. Záložka Digitální kontinuita o vloženém údaji mluví ve faktech
         * („vloženy jsou revokační informace v podobě …“) a brala je z
         * {@link #signingCertRevocation()} nebo {@link #signingCertRevocationUnusableFact()}.
         * Ani jeden na tu otázku neodpovídá: první je u prvku, který neobstál, prázdný, a druhý
         * drží <b>první odmítnutý</b> záznam v pořadí, v jakém data dorazila — u
         * „priklad6_17.pdf“ tedy jednou vloženou odpověď OCSP a podruhé CRL stažené on-line.
         * Fakta pak o dokumentu s OCSP tvrdila „CRL seznam“.
         *
         * <p>Souvisí s {@link #signingCertHasEmbeddedRevocation()}, které odpovídá na tutéž
         * otázku jen ano/ne; tenhle údaj k tomu přidává druh a datum vydání.
         */
        RevocationFact signingCertEmbeddedFact,

        /**
         * Neprošel tomuhle běhu právě ten revokační údaj, na který ukazuje podrobný protokol DSS
         * jako na „poslední přípustný" ({@code LatestAcceptableRevocationId}) pro podpisový
         * certifikát?
         *
         * <p>Pravdivá odpověď na otázku „zjistil se stav revokace?". Protokol je autorita na to,
         * oč se ověření opřelo — táž úvaha, pro kterou dává {@code chooseUsedRevocation} volbě
         * protokolu přednost —, takže když neprojde ani ona, proces se k závěru o revokaci
         * nedostal. Čím se pak model ohání jako „využitým" údajem, je jen náhradní volba, kterou
         * si udělal sám.
         *
         * <p>Vlastní příznak, protože z ostatních polí se to poskládat nedá:
         * {@link #signingCertRevocation()} nese náhradní volbu (a je tedy neprázdné právě tam, kde
         * se nic nezjistilo) a {@link #signingCertRevocationUnusableFact()} drží <b>první</b>
         * odmítnutý záznam v pořadí, v jakém data dorazila — což s volbou protokolu nemusí souviset.
         *
         * <p>Měřeno 12. 9. 2026 na 214 souborech v D:\tmp: volba protokolu neprošla u 46 podpisů
         * a u 45 z nich model žádný využitý údaj nejmenuje, takže se příznak nijak neprojeví.
         * Zbývá dokument s odpovědí OCSP v atributu ADBE — ten, kvůli
         * kterému příznak vznikl: DSS vybralo odpověď vloženou v dokumentu, AnEP ji odmítl,
         * a náhradní volba padla na odpověď staženou téhož dne, kdy ověřování běželo.
         */
        boolean signingCertSelectedRevocationUnusable) {

    /**
     * Jeden řádek výčtu revokačních informací: text pro čtenáře a to, co je potřeba, aby se ten
     * údaj dal také ukázat a uložit.
     *
     * <p>Dřív to byl holý {@code String}. Odkaz „Ukaž“ ale musí najít samotný údaj, a hledat ho
     * podle věty by znamenalo rozebírat text, který je psaný pro člověka. Identifikátor
     * z {@code DiagnosticData} je to, čím se údaj v aplikaci označuje všude jinde.
     *
     * @param text          věta, jak ji čte uživatel
     * @param revocationId  identifikátor údaje v {@code DiagnosticData}; {@code null} u údaje,
     *                      který se do diagnostiky nedostal — takový nemá co ukázat, protože
     *                      aplikace jeho bajty nedrží (OCSP odpověď přeskočená strategií)
     * @param ocsp          {@code true} pro OCSP odpověď, {@code false} pro seznam CRL
     * @param certSerial    sériové číslo certifikátu, o kterém řádek mluví; prohlížeč CRL se na
     *                      jeho záznamu otevře, {@code null} znamená „na prvním“
     */
    public record RevocationLine(String text, String revocationId, boolean ocsp,
                                 java.math.BigInteger certSerial) { }

    /**
     * Jeden revokační údaj rozložený na části, ze kterých se dá poskládat věta.
     *
     * @param ocsp        {@code true} pro OCSP („odpověď“, rod ženský), {@code false} pro CRL
     *                    („seznam“, rod mužský) — čeština to potřebuje vědět kvůli shodě
     * @param origin      {@code "online"}, {@code "document"} nebo {@code "store"}
     * @param issued      thisUpdate, tedy kdy údaj vznikl
     * @param revoked     zda podle něj byl certifikát odvolán
     * @param statusUnknown respondér stav certifikátu nezná — třetí možnost vedle „odvolán“
     *                    a „neodvolán“, kterou nelze vyjádřit jedním příznakem
     * @param revokedAt   kdy, je-li to známo
     * @param relaxedRule {@code null}, když údaj obstojí i podle standardů; jinak
     *                    {@code "postExpiry"} (vznikl po expiraci certifikátu, jehož se týká)
     *                    nebo {@code "expiredIssuer"} (vydavateli mezitím vypršel certifikát) —
     *                    tedy pravidlo, které muselo být uvolněno, aby se údaj směl použít
     */
    public record RevocationFact(boolean ocsp, String origin, Instant issued,
                                 boolean revoked, boolean statusUnknown, Instant revokedAt,
                                 String relaxedRule) { }

    /**
     * @param id             identifikátor údaje v DiagnosticData, aby si ho podle něj našel
     *                       i Rozšířený přehled a obě místa mluvila o témže
     * @param label          jak údaj pojmenovat ve větě, v akuzativu nehodící se — proto se do věty
     *                       vkládá jako přístavek za pomlčkami
     * @param postExpiry     vznikl až po skončení platnosti certifikátu, o němž vypovídá
     * @param issuerOutOfRange certifikát jeho vydavatele k rozhodnému okamžiku neplatil
     */
    public record PrimaryRevocation(String id, String label,
                                    boolean postExpiry, boolean issuerOutOfRange) {
        /** Obě vady naráz — hlavní ústupek pak určit nelze, nesou to oba společně. */
        public boolean bothFlaws() { return postExpiry && issuerOutOfRange; }
        /** Žádná z vad, na které míří tolerance — ústupek se uplatnil jinde v řetězci. */
        public boolean noFlaw()    { return !postExpiry && !issuerOutOfRange; }
    }

    public ValidationCourse {
        revocationUsed    = revocationUsed    != null ? List.copyOf(revocationUsed)    : List.of();
        revocationNotDecisive = revocationNotDecisive != null
                ? List.copyOf(revocationNotDecisive) : List.of();
        revocationRejected = revocationRejected != null
                ? List.copyOf(revocationRejected) : List.of();
        revocationMissing = revocationMissing != null ? List.copyOf(revocationMissing) : List.of();
        revocationNotApplicable = revocationNotApplicable != null
                ? List.copyOf(revocationNotApplicable) : List.of();
        contextNotes      = contextNotes      != null ? List.copyOf(contextNotes)      : List.of();
    }

    /** True when there is nothing worth showing. */
    public boolean isEmpty() {
        return validationTime == null && dssBestSignatureTime == null && provenExistence == null
                && revocationUsed.isEmpty() && revocationNotDecisive.isEmpty()
                && revocationRejected.isEmpty()
                && revocationMissing.isEmpty() && revocationNotApplicable.isEmpty()
                && contextNotes.isEmpty();
    }
}
