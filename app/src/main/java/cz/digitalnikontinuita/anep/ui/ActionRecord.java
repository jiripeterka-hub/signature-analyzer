package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.TimeFormat;
import cz.digitalnikontinuita.anep.core.TsaProfile;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Horizon;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Kind;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Limit;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Záznam o úkonu — co se s dokumentem stalo při uchování, prodloužení nebo přerazítkování,
 * a zda výsledek dopadl tak, jak záložka Digitální kontinuita před úkonem předpověděla.
 *
 * <h2>Proč vznikl</h2>
 *
 * <p>Do 4. 10. 2026 úkon skončil uložením souboru. Co z něj vyšlo, se uživatel dozvěděl, jen
 * když si soubor sám znovu otevřel — a to, co záložka slibovala po prvcích („úkon na tento
 * podpis dosáhne, ověřitelnost se posune do …“), byl odhad, který nic nekontrolovalo. Podnětem
 * bylo srovnání s politikou kvalifikované služby uchovávání SecuSign (verze 1.3 z 10. 4. 2023):
 * ta po registraci vrací souhrn, které podpisy se ošetřit podařilo a které ne, a do auditního
 * záznamu ukládá otisk dokumentu před operací a po ní, stav každého podpisu při přijetí a datum
 * vypršení ověřitelnosti. AnEP žádný rejstřík nevede a vést nemá; záznam proto dostane do ruky
 * uživatel, jako protokol PDF/A, a otisky SHA-256 ho vážou k oběma souborům.
 *
 * <h2>Jak se stav „po“ zjišťuje</h2>
 *
 * <p>Výsledný soubor se ověří <b>tou cestou, kterou prochází každý jiný soubor</b>, ne zvláštním
 * ověřením na boku. Dvě cesty by se časem rozešly a záznam by pak tvrdil o souboru něco jiného,
 * než co o něm ukáže aplikace, když si ho uživatel otevře sám. Od 4. 10. 2026 se to děje na
 * pozadí ({@code MainView.verifyInBackground}, týž {@code validateFiles} a týž výpočet karty ve
 * skrytém pohledu) a hlavní okno dál ukazuje vstupní soubor — pokyn uživatele.
 *
 * <p>Prvky se párují podle identifikátoru DSS. Změřeno 4. 10. 2026 na dvojici PDF před
 * uchováním a po něm (i po druhém uchování): identifikátor podpisu i starších razítek zůstává
 * týž a nové razítko přibude s vlastním. Prvek, který se spárovat nepodaří, se nezamlčí — vypíše
 * se jako „po úkonu nenalezen“ a nový jako přibylý.
 *
 * <p>Ověření výsledného souboru proběhne <b>bez ústupků</b>: validační politika platí na jedno
 * ověření a po něm se vrací do výchozího stavu ({@code AppSettings.resetValidationPolicy()}).
 * U uchování a prodloužení je to žádoucí, protože obě se nabízejí jen nad závěrem bez ústupků
 * (situace 34). U přerazítkování, jehož výsledek je určen validátorům s vlastní zásobou
 * revokačních informací, se proto neposuzuje přísný verdikt, nýbrž odhad pro jejich okruh
 * ({@link ContinuityHorizon#tolerant}).
 */
final class ActionRecord {

    /** Druh prvku — kvůli rodu slov, ne kvůli výkladu. */
    enum ElementKind { SIGNATURE, SEAL, TIMESTAMP }

    /**
     * Jeden prvek v jednom okamžiku: co o něm řekla záložka Digitální kontinuita.
     *
     * @param reached  úkon na prvek podle předpovědi dosáhne — smysl má jen u stavu před úkonem
     * @param obstacle proč nedosáhne, je-li to známo
     * @param concessions ústupky, o které se závěr opřel (nebo které byly zapnuté, nebyl-li
     *                 jejich vliv změřen) — viz {@link ContinuityHorizon.Deviations#namedLenient}
     * @param made     čas vzniku, jak ho prvek uvádí — pro párování razítek, kterým úkon změnil
     *                 bajty, a tím i identifikátor (viz {@link #pairs})
     */
    record ElementState(String id, ElementKind kind, String name, String verdict,
                        Horizon anep, Horizon tolerant, boolean reached, String obstacle,
                        List<String> concessions, Instant certNotAfter, Instant made) {

        ElementState {
            concessions = concessions != null ? List.copyOf(concessions) : List.of();
        }

        static ElementState of(ContinuityElementBlocks.Element el) {
            SignatureSummary sig = el.sig();
            ElementKind kind = "TimeStampToken".equals(sig.getFormat()) ? ElementKind.TIMESTAMP
                    : "P".equals(AdvisorView.gendered(sig, "S", "P", "T")) ? ElementKind.SEAL
                    : ElementKind.SIGNATURE;
            return new ElementState(sig.getSignatureId(), kind, sig.getSignerDisplayName(),
                    el.verdict(), el.anep(), el.tolerant(), el.actionReaches(), el.obstacle(),
                    el.dev() != null ? el.dev().namedLenient() : List.of(),
                    sig.getCertNotAfter(), sig.getSigningTime());
        }

        String noun() {
            return switch (kind) {
                case SIGNATURE -> "podpis";
                case SEAL -> "pečeť";
                case TIMESTAMP -> "časové razítko";
            };
        }

        String gendered(String masc, String fem, String neut) {
            return switch (kind) {
                case SIGNATURE -> masc;
                case SEAL -> fem;
                case TIMESTAMP -> neut;
            };
        }
    }

    /**
     * Soubor tak, jak ho záznam popisuje: jméno, velikost a otisk.
     *
     * @param path plná cesta; do textu záznamu nejde, nese ji připomínka do kalendáře (pokyn
     *             uživatele 5. 10. 2026). {@code null}, není-li známa.
     */
    record FileFact(String name, long size, String sha256, String path) {
        FileFact(String name, long size, String sha256) {
            this(name, size, sha256, null);
        }

        static FileFact of(File f) {
            return new FileFact(f.getName(), f.length(), ActionRecord.sha256(f),
                    f.getAbsolutePath());
        }
    }

    /**
     * Jeden revokační údaj (CRL nebo odpověď OCSP) tak, jak ho záznam popisuje.
     *
     * <p>Doplněno 4. 10. 2026 na podnět uživatele: záznam o uchování, ve kterém nestálo, které
     * revokační informace úkon do dokumentu vložil a které tam byly už předtím, mlčel právě
     * o tom, co uchování dělá. Páruje se podle otisku bajtů, ne podle identifikátoru DSS — ten
     * se u razítek úkonem změnit umí (viz {@link #pairs}) a otisk je měřítko, které na DSS
     * nezávisí.
     *
     * @param key        otisk SHA-256 bajtů údaje
     * @param inDocument údaj leží v dokumentu (ne stažený při ověření ze sítě či z mezipaměti)
     * @param where      kde v dokumentu leží, slovy; u staženého {@code null}
     * @param about      certifikáty, o kterých údaj při ověření vypovídal (jména, pro čtenáře)
     * @param aboutIds   tytéž certifikáty identifikátorem DSS — jména se u dvou certifikátů
     *                   téže autority shodují, identifikátor ne
     * @param lastStamp  údaj vypovídá o certifikátu nejmladšího razítka v dokumentu — ten se do
     *                   dokumentu vložit ještě nedá, viz {@link #revocationsFetchedAfter}
     */
    record RevocationFact(String key, boolean crl, String issuer, Instant issued,
                          List<String> about, int size, boolean inDocument, String where,
                          boolean lastStamp, List<String> aboutIds) { }

    /**
     * Jedno časové razítko tak, jak ho záznam popisuje — kvůli kryptografickým parametrům
     * razítek, která úkon přidal. Doplněno 5. 10. 2026 na pokyn uživatele: záznam uváděl jen
     * konec platnosti certifikátu nového razítka, a ne algoritmus a délku klíče, na kterých
     * podle ETSI TS 119 312 záleží, dokdy razítko obstojí (typicky RSA-2048 a 1. 1. 2027).
     *
     * <p>Páruje se podle toho, co razítko podepisuje — času vzniku a otisku orazítkovaných
     * dat ({@link #stampKey}) —, ne podle identifikátoru DSS, který se úkonem změnit umí (viz
     * {@link #pairs}), a od 13. 10. 2026 ani podle otisku bajtů tokenu. Ty se mění také: při
     * uchování kontejneru ASiC-E doplní DSS validační data i do <b>stávajícího</b> razítka
     * kontejneru, do nepodepsaných atributů jeho tokenu, a záznam pak to staré razítko vypsal
     * jako druhé nové (nález uživatele nad kontejnerem s razítkem PostSignum z roku 2024).
     *
     * @param key            klíč razítka podle {@link #stampKey}
     * @param algorithm      podpisový algoritmus tokenu („RSA“, „ECDSA“…)
     * @param keyLength      délka klíče autority v bitech; 0, když ji DSS neuvádí
     * @param digest         hašovací funkce podpisu tokenu
     * @param imprint        hašovací funkce otisku orazítkovaných dat
     * @param reliableUntil  od kdy kryptografická sada politiky ověřování algoritmus s touto
     *                       délkou klíče za spolehlivý nepovažuje; {@code null} = bez omezení
     *                       nebo nezjištěno
     */
    record StampFact(String key, Instant time, String issuer, String algorithm, int keyLength,
                     String digest, String imprint, Instant reliableUntil) { }

    /** Razítka z diagnostických dat jednoho ověření, s datem spolehlivosti z {@code policy}. */
    static List<StampFact> stamps(eu.europa.esig.dss.diagnostic.DiagnosticData dd,
                                  eu.europa.esig.dss.model.policy.ValidationPolicy policy) {
        if (dd == null) return List.of();
        List<StampFact> out = new ArrayList<>();
        for (var ts : dd.getTimestampList()) {
            if (ts.getBinaries() == null) continue;
            int size = 0;
            try {
                size = Integer.parseInt(ts.getKeyLengthUsedToSignThisToken());
            } catch (Exception ignored) { }
            Instant until = null;
            if (policy != null && ts.getSignatureAlgorithm() != null && size > 0) {
                try {
                    var d = new cz.digitalnikontinuita.anep.core.TsaSigningKey(
                            ts.getSignatureAlgorithm(), size).reliableUntil(policy);
                    if (d != null) until = d.toInstant();
                } catch (Exception ignored) { }
            }
            var cert = ts.getSigningCertificate();
            String issuer = cert == null ? null
                    : cert.getCommonName() != null && !cert.getCommonName().isBlank()
                    ? cert.getCommonName() : cert.getReadableCertificateName();
            var imprint = ts.getMessageImprint();
            out.add(new StampFact(stampKey(ts),
                    ts.getProductionTime() != null ? ts.getProductionTime().toInstant() : null,
                    issuer,
                    ts.getEncryptionAlgorithm() != null ? ts.getEncryptionAlgorithm().getName() : null,
                    size,
                    ts.getDigestAlgorithm() != null ? ts.getDigestAlgorithm().getName() : null,
                    imprint != null && imprint.getDigestMethod() != null
                            ? imprint.getDigestMethod().getName() : null,
                    until));
        }
        return out;
    }

    /**
     * Totožnost razítka napříč úkonem: čas vzniku a otisk orazítkovaných dat, obojí z podepsané
     * části tokenu (TSTInfo), kterou žádné doplnění validačních dat nezmění. Dvě různá razítka
     * téhož okamžiku nad týmiž daty by splynula — ale to je pak opravdu totéž potvrzení.
     * Nemá-li DSS otisk k dispozici, zbude otisk bajtů tokenu.
     */
    static String stampKey(eu.europa.esig.dss.diagnostic.TimestampWrapper ts) {
        var imprint = ts.getMessageImprint();
        if (imprint == null || imprint.getDigestValue() == null || ts.getProductionTime() == null) {
            return ts.getBinaries() == null ? null : sha256(ts.getBinaries());
        }
        return ts.getProductionTime().getTime() + "|"
                + (imprint.getDigestMethod() != null ? imprint.getDigestMethod().name() : "") + "|"
                + HexFormat.of().formatHex(imprint.getDigestValue());
    }

    /**
     * Revokační údaje z diagnostických dat jednoho ověření. DSS do nich dává údaje, se kterými
     * ověření pracovalo — vložené v dokumentu i stažené; podle původu se pak rozdělí.
     */
    static List<RevocationFact> revocations(eu.europa.esig.dss.diagnostic.DiagnosticData dd) {
        if (dd == null) return List.of();
        Map<String, List<String>> about = new LinkedHashMap<>();
        Map<String, List<String>> aboutIds = new LinkedHashMap<>();
        for (eu.europa.esig.dss.diagnostic.CertificateWrapper cw : dd.getUsedCertificates()) {
            for (var crw : cw.getCertificateRevocationData()) {
                List<String> names = about.computeIfAbsent(crw.getId(), k -> new ArrayList<>());
                String n = cw.getReadableCertificateName();
                if (n != null && !names.contains(n)) names.add(n);
                List<String> ids = aboutIds.computeIfAbsent(crw.getId(), k -> new ArrayList<>());
                if (!ids.contains(cw.getId())) ids.add(cw.getId());
            }
        }
        // Revokační údaje o certifikátu nejmladšího razítka. Ten má zvláštní postavení: podle
        // EN 319 142-1, kap. 6.3, se jeho ověřovací údaje vkládají až před dalším razítkem,
        // takže v čerstvě uchovaném dokumentu chybějí vždy a právem.
        java.util.Set<String> lastStampRevocations = new java.util.HashSet<>();
        dd.getTimestampList().stream()
                .filter(ts -> ts.getProductionTime() != null && ts.getSigningCertificate() != null)
                .max(java.util.Comparator.comparing(
                        eu.europa.esig.dss.diagnostic.TimestampWrapper::getProductionTime))
                .ifPresent(ts -> ts.getSigningCertificate().getCertificateRevocationData()
                        .forEach(crw -> lastStampRevocations.add(crw.getId())));
        Map<String, RevocationFact> out = new LinkedHashMap<>();
        for (eu.europa.esig.dss.diagnostic.RevocationWrapper rev : dd.getAllRevocationData()) {
            byte[] bin = rev.getBinaries();
            if (bin == null || bin.length == 0) continue;
            String key = sha256(bin);
            boolean crl = rev.getRevocationType()
                    == eu.europa.esig.dss.enumerations.RevocationType.CRL;
            java.util.Date issued = crl ? rev.getThisUpdate() : rev.getProductionDate();
            boolean inDoc = rev.isInternalRevocationOrigin();
            RevocationFact f = new RevocationFact(key, crl,
                    rev.getSigningCertificate() != null
                            ? rev.getSigningCertificate().getReadableCertificateName() : null,
                    issued != null ? issued.toInstant() : null,
                    List.copyOf(about.getOrDefault(rev.getId(), List.of())), bin.length, inDoc,
                    inDoc ? originWords(rev.getOrigin()) : null,
                    lastStampRevocations.contains(rev.getId()),
                    List.copyOf(aboutIds.getOrDefault(rev.getId(), List.of())));
            // Tentýž údaj může DSS vést dvakrát (z dokumentu i ze sítě); přednost má ten
            // vložený, protože právě o něm záznam mluví.
            RevocationFact prev = out.get(key);
            if (prev == null || (!prev.inDocument() && inDoc)) out.put(key, f);
        }
        return List.copyOf(out.values());
    }

    /**
     * Místo v dokumentu slovy, nebo {@code null}, když ho DSS neuvádí konkrétně. U PDF hlásí
     * souhrnné {@code INPUT_DOCUMENT} (změřeno 4. 10. 2026 na uchovaném PAdES) a „uloženo:
     * v dokumentu“ by jen opakovalo nadpis skupiny.
     */
    private static String originWords(eu.europa.esig.dss.enumerations.RevocationOrigin o) {
        if (o == null) return null;
        return switch (o) {
            case DSS_DICTIONARY -> "slovník DSS";
            case VRI_DICTIONARY -> "slovník VRI";
            case ADBE_REVOCATION_INFO_ARCHIVAL -> "podepsaný atribut adbe-revocationInfoArchival";
            case CMS_SIGNED_DATA -> "SignedData podpisu";
            case REVOCATION_VALUES, ATTRIBUTE_REVOCATION_VALUES -> "atribut revocation-values";
            case TIMESTAMP_VALIDATION_DATA -> "ověřovací údaje razítka";
            case ANY_VALIDATION_DATA -> "ověřovací údaje";
            case EVIDENCE_RECORD -> "evidenční záznam";
            default -> null;
        };
    }

    // ── Údaje o úkonu ────────────────────────────────────────────────────────────────────────

    final AdvisorView.ContinuityMode mode;
    final String tsaName;
    /**
     * Kvalifikovanost zdroje razítek. Bere se z profilu, a je-li tam nezjištěná, doplní ji
     * {@code AdvisorView.fillAfter} z ověření výsledného souboru — proto ne {@code final}.
     */
    volatile TsaProfile.Quality tsaQuality;
    /** Profil, kterým se razítkovalo — aby se zjištěná kvalifikovanost dala zapamatovat. */
    volatile TsaProfile tsaProfile;
    final List<ElementState> before;
    final Instant beforeDocUntil;

    /** Vstupní soubor. Otisk se počítá na pracovním vlákně úkonu, proto volatile. */
    volatile FileFact input;
    /** Podepsaný obsah odloučeného podpisu — úkonem se nemění, otisk to doloží. */
    volatile FileFact detachedContent;
    volatile FileFact output;
    volatile Instant performedAt;
    volatile java.util.Date tsaCertNotAfter;
    /** Datum, které úkon vepsal do jména výsledného souboru. */
    volatile java.util.Date promisedUntil;
    /** Co úkon vložil, slovy — zná to jen přerazítkování; jinak {@code null}. */
    volatile String embedded;
    /**
     * Kolik revokačních údajů přerazítkování mělo, ale nevložilo, protože o certifikátu nic
     * nevypovídají ({@code RestampingService.speaks}). Bez toho stálo u prázdného „Přidané úkonem“
     * „potřebné údaje už dokument nesl“ i tam, kde dokument nenesl nic (nález uživatele
     * 17. 10. 2026 nad úředním výpisem s jedinou post-expirační OCSP).
     */
    volatile int withheldRevocations;

    /**
     * Formát a úroveň vstupního a výsledného souboru slovy ({@link AdvisorView#formatsWords}).
     * Doplněno 4. 10. 2026 na podnět uživatele: oddíl Soubory uváděl jméno, velikost a otisk,
     * ale ne to, co se úkonem na souboru mění především — úroveň podpisu.
     */
    String inputFormat;
    String outputFormat;

    /**
     * Úkon začal z úrovně B, tedy bez jediného razítka, a přidal proto dvě: první podpis ukotví
     * v čase (úroveň T), druhé je archivní. Do 4. 10. 2026 to ze záznamu poznat nešlo — popis
     * úkonu mluvil o jednom razítku a přibylé prvky nikdo nevysvětlil (nález uživatele).
     */
    boolean fromBaseline;

    /** Revokační údaje z ověření před úkonem a z ověření výsledného souboru. */
    List<RevocationFact> beforeRevocations = List.of();
    List<RevocationFact> afterRevocations = List.of();

    /** Razítka z ověření před úkonem a z ověření výsledného souboru — viz {@link StampFact}. */
    List<StampFact> beforeStamps = List.of();
    List<StampFact> afterStamps = List.of();

    List<ElementState> after;
    Instant afterDocUntil;
    List<String> afterEnabledLenient = List.of();
    Instant afterCustomTime;
    boolean afterTrustListsOffline;

    ActionRecord(AdvisorView.ContinuityMode mode, String tsaName, TsaProfile.Quality tsaQuality,
                 List<ElementState> before, Instant beforeDocUntil) {
        this.mode = mode;
        this.tsaName = tsaName;
        this.tsaQuality = tsaQuality != null ? tsaQuality : TsaProfile.Quality.UNKNOWN;
        this.before = List.copyOf(before);
        this.beforeDocUntil = beforeDocUntil;
    }

    // ── Porovnání ────────────────────────────────────────────────────────────────────────────

    /** Prvek před úkonem a po něm; jedna ze stran smí chybět. */
    record Pair(int number, ElementState before, ElementState after) { }

    /**
     * Prvky spárované podle identifikátoru, v pořadí dokumentu po úkonu; prvky po úkonu
     * nenalezené na konci. Číslo je pořadí ve výčtu po úkonu, aby sedělo s tím, co aplikace
     * o výsledném souboru ukazuje.
     *
     * <p><b>Druhé kolo pro razítka.</b> Identifikátor razítka je otisk jeho bajtů, a ty se úkonem
     * změnit mohou: při uchování kontejneru ASiC se ověřovací údaje vkládají do nepodepsaných
     * atributů samotného razítka. Změřeno 4. 10. 2026 na kontejneru ASiC-E se samotným razítkem
     * z roku 2019 před uchováním a po něm — {@code timestamp.tst} narostl z 2 893 na 12 928 B
     * a DSS mu přidělil jiný identifikátor, takže záznam hlásil původní razítko jako nenalezené
     * a totéž jako nové. Co se nezmění, je to podepsané: čas vzniku a vydavatel. Podle nich se
     * páruje to, co po prvním kole zbylo.
     */
    List<Pair> pairs() {
        List<ElementState> afterList = after != null ? after : List.of();
        ElementState[] match = new ElementState[afterList.size()];
        List<ElementState> left = new ArrayList<>(before);
        for (int i = 0; i < afterList.size(); i++) {
            ElementState a = afterList.get(i);
            for (ElementState b : left) {
                if (a.id() != null && a.id().equals(b.id())) {
                    match[i] = b;
                    left.remove(b);
                    break;
                }
            }
        }
        for (int i = 0; i < afterList.size(); i++) {
            if (match[i] != null) continue;
            ElementState a = afterList.get(i);
            if (a.made() == null) continue;
            for (ElementState b : left) {
                if (b.kind() == a.kind() && a.made().equals(b.made())
                        && java.util.Objects.equals(a.name(), b.name())) {
                    match[i] = b;
                    left.remove(b);
                    break;
                }
            }
        }
        List<Pair> out = new ArrayList<>();
        for (int i = 0; i < afterList.size(); i++) out.add(new Pair(i + 1, match[i], afterList.get(i)));
        for (ElementState b : left) out.add(new Pair(0, b, null));
        return out;
    }

    /**
     * V čem se výsledek rozešel s předpovědí. Prázdný seznam znamená shodu.
     *
     * <p>Co se od úkonu čeká, záleží na variantě. Uchování a prodloužení slibují, že prvek, na
     * který dosáhnou, bude platný i pro validátor podle standardů — proto se ptá na verdikt
     * a horizont AnEP. Přerazítkování slibuje ověřitelnost validátorům s vlastní zásobou
     * revokačních informací, takže se ptá na jejich odhad. Prvek, na který úkon dosáhnout neměl,
     * se nesmí zhoršit; víc se od něj nečeká.
     *
     * <p>Datum se srovnává po dnech: jméno souboru nese jen den, a rozdíl hodin mezi koncem
     * platnosti certifikátu a půlnocí by se jinak hlásil jako rozchod, který není.
     */
    List<String> discrepancies() {
        List<String> out = new ArrayList<>();
        boolean restamp = mode == AdvisorView.ContinuityMode.RESTAMP;
        for (Pair p : pairs()) {
            ElementState b = p.before(), a = p.after();
            if (b == null) continue;
            String who = a != null ? "Prvek č. " + p.number() + " (" + a.noun() + ")"
                                   : "Prvek (" + b.noun() + ")";
            if (a == null) {
                out.add(who + " se ve výsledném souboru nepodařilo najít.");
                continue;
            }
            if (b.reached()) {
                if (restamp) {
                    if (!a.tolerant().reachable()) {
                        out.add(who + ": úkon měl ověřitelnost pro CzechPOINT a podatelny"
                                + " zachovat, podle ověření výsledného souboru ji ale nemá.");
                    } else if (shortened(b.tolerant(), a.tolerant())) {
                        out.add(who + ": odhad ověřitelnosti pro CzechPOINT a podatelny se"
                                + " zkrátil z " + day(b.tolerant().when()) + " na "
                                + day(a.tolerant().when()) + ".");
                    }
                } else {
                    if (!"TOTAL_PASSED".equals(a.verdict())) {
                        out.add(who + ": úkon na " + a.gendered("něj", "ni", "něj")
                                + " měl dosáhnout, výsledný soubor ale "
                                + a.gendered("ho", "ji", "ho") + " ověřil jako "
                                + verdictWord(a) + ".");
                    } else if (shortened(b.anep(), a.anep())) {
                        out.add(who + ": ověřitelnost se zkrátila z " + day(b.anep().when())
                                + " na " + day(a.anep().when()) + ".");
                    }
                }
            } else if (rank(a.verdict()) < rank(b.verdict())) {
                out.add(who + ": úkon na " + a.gendered("něj", "ni", "něj")
                        + " dosáhnout neměl, ale výsledek se zhoršil — před úkonem "
                        + verdictWord(b) + ", po úkonu " + verdictWord(a) + ".");
            }
        }
        Instant reached = restamp ? tolerantDocUntil() : afterDocUntil;
        if (promisedUntil != null && reached != null
                && localDay(reached).isBefore(localDay(promisedUntil.toInstant()))) {
            out.add("Jméno výsledného souboru uvádí ověřitelnost do "
                    + day(promisedUntil.toInstant()) + ", ověření výsledného souboru "
                    + (restamp ? "odhaduje pro CzechPOINT a podatelny" : "ji ale dává")
                    + " jen do " + day(reached) + ".");
        }
        return out;
    }

    /** Nejvzdálenější odhad pro okruh CzechPOINTu přes podpisy (razítka jen bez podpisů). */
    private Instant tolerantDocUntil() {
        List<ElementState> list = after != null ? after : List.of();
        boolean anyReal = list.stream().anyMatch(e -> e.kind() != ElementKind.TIMESTAMP);
        Instant best = null;
        for (ElementState e : list) {
            if (anyReal && e.kind() == ElementKind.TIMESTAMP) continue;
            if (e.tolerant().kind() != Kind.UNTIL) continue;
            if (best == null || e.tolerant().when().isAfter(best)) best = e.tolerant().when();
        }
        return best;
    }

    private static boolean shortened(Horizon before, Horizon after) {
        return before.kind() == Kind.UNTIL && after.kind() == Kind.UNTIL
                && localDay(after.when()).isBefore(localDay(before.when()));
    }

    private static int rank(String verdict) {
        if ("TOTAL_PASSED".equals(verdict)) return 2;
        if ("TOTAL_FAILED".equals(verdict)) return 0;
        return 1;
    }

    private static LocalDate localDay(Instant t) {
        return t.atZone(ZoneId.of("Europe/Prague")).toLocalDate();
    }

    /** Vložené v dokumentu už před úkonem. */
    List<RevocationFact> revocationsKept() {
        return beforeRevocations.stream().filter(RevocationFact::inDocument).toList();
    }

    /** Vložené po úkonu, které v dokumentu před ním nebyly — to, co úkon přidal. */
    List<RevocationFact> revocationsAdded() {
        java.util.Set<String> had = new java.util.HashSet<>();
        for (RevocationFact f : beforeRevocations) if (f.inDocument()) had.add(f.key());
        return afterRevocations.stream()
                .filter(f -> f.inDocument() && !had.contains(f.key())).toList();
    }

    /**
     * Údaje, které si ověření výsledného souboru opatřilo mimo dokument, protože je dokument
     * nenese. Nemusí to být vada — u certifikátu, který dosud platí, si ověřovatel čerstvý údaj
     * vyžádá i tehdy, když vložený je —, ale čtenář má vědět, že výsledek na nich stál.
     */
    List<RevocationFact> revocationsFetchedAfter() {
        java.util.Set<String> inside = new java.util.HashSet<>();
        java.util.Set<String> coveredCerts = new java.util.HashSet<>();
        for (RevocationFact f : afterRevocations) {
            if (!f.inDocument()) continue;
            inside.add(f.key());
            coveredCerts.addAll(f.aboutIds());
        }
        // Stažený údaj o certifikátu, ke kterému dokument už vložený údaj nese, se nevypisuje.
        // Je to jen čerstvá odpověď, kterou si ověřovatel u platného certifikátu vyžádal vedle
        // vložené, a řádek o ní opakoval to, co stojí o dva řádky výš (nález uživatele
        // 4. 10. 2026 nad uchováním PAdES B-B: OCSP od Responderu 5 vložená úkonem, OCSP od
        // Responderu 6 stažená ověřením výsledku, obě o témže podpisovém certifikátu).
        // Údaj, o jehož certifikátech nic nevíme, zůstává — tvrdit pokrytí bez dokladu nelze.
        return afterRevocations.stream()
                .filter(f -> !f.inDocument() && !inside.contains(f.key()))
                .filter(f -> f.aboutIds().isEmpty() || !coveredCerts.containsAll(f.aboutIds()))
                .toList();
    }

    // ── Slova ────────────────────────────────────────────────────────────────────────────────

    static String verdictWord(ElementState e) {
        if ("TOTAL_PASSED".equals(e.verdict())) return e.gendered("platný", "platná", "platné");
        if ("TOTAL_FAILED".equals(e.verdict())) return e.gendered("neplatný", "neplatná", "neplatné");
        return "s neznámou platností";
    }

    /** Datum bez času — tak, jak ho nese jméno souboru. */
    /** Razítka, která výsledný soubor nese a vstupní ne — v pořadí vzniku. */
    List<StampFact> stampsAdded() {
        java.util.Set<String> had = new java.util.HashSet<>();
        for (StampFact s : beforeStamps) had.add(s.key());
        return afterStamps.stream()
                .filter(s -> s.key() != null && !had.contains(s.key()))
                .sorted(java.util.Comparator.comparing(StampFact::time,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Jméno řádku. U dvou razítek z úrovně B je první podpisové a druhé archivní — tak, jak je
     * popisuje úvodní věta úkonu; jinak „Nové razítko“, a kdyby jich bylo víc, s pořadím.
     */
    private String stampLabel(int i, int count) {
        if (count == 1) return "Algoritmy nového razítka:";
        if (count == 2 && twoStamps()) {
            return i == 0 ? "Algoritmy podpisového razítka:" : "Algoritmy archivního razítka:";
        }
        return "Algoritmy " + (i + 1) + ". nového razítka:";
    }

    /**
     * „RSA, klíč 4 096 bitů, hašovací funkce podpisu razítka SHA256, hašovací funkce otisku
     * dat SHA512“ — pojmenování jako v Rozšířeném přehledu —
     * a přestane-li algoritmus být spolehlivý dřív, než skončí certifikát nového razítka, i to.
     */
    String stampWords(StampFact s) {
        StringBuilder sb = new StringBuilder(s.algorithm() != null ? s.algorithm() : "algoritmus neznámý");
        if (s.keyLength() > 0) {
            sb.append(", klíč ").append(String.format(java.util.Locale.ROOT, "%,d", s.keyLength())
                    .replace(',', ' ')).append(" bitů");
        }
        if (s.digest() != null) sb.append(", hašovací funkce podpisu razítka ").append(s.digest());
        if (s.imprint() != null) sb.append(", hašovací funkce otisku dat ").append(s.imprint());
        if (s.reliableUntil() != null && (tsaCertNotAfter == null
                || s.reliableUntil().isBefore(tsaCertNotAfter.toInstant()))) {
            sb.append(" — podle ETSI TS 119 312 se ")
              .append(s.reliableUntil().isBefore(Instant.now()) ? "už od " : "od ")
              .append(TimeFormat.day(s.reliableUntil()))
              .append(" nepovažuje za spolehlivý, tedy dřív, než skončí certifikát razítka");
        }
        return sb.toString();
    }

    static String day(Instant t) {
        if (t == null) return TimeFormat.MISSING;
        LocalDate d = localDay(t);
        return d.getDayOfMonth() + ". " + d.getMonthValue() + ". " + d.getYear();
    }


    static String anepWords(Horizon h) {
        return switch (h.kind()) {
            case UNTIL -> "AnEP ověří "
                    + (h.limit() == Limit.QUALIFIED_TIMESTAMP_CERT ? "nejméně do " : "do ")
                    + day(h.when())
                    + (h.when().isAfter(Instant.now()) ? "" : " (již uplynulo)");
            case OPEN -> switch (h.limit()) {
                case SUNSET -> "AnEP ověří, dokud si služba, která vydala razítko, drží"
                        + " kvalifikovaný status (datum předem nelze stanovit)";
                case TRUST_ANCHOR -> "AnEP ověří, dokud služba, jíž je certifikát kotvou"
                        + " důvěry, drží svůj status (datum předem nelze stanovit)";
                case FIXED_TIME -> "ověřuje se k pevně zadanému okamžiku, výsledek se časem"
                        + " nemění";
                default -> "AnEP ověří, datum nelze stanovit";
            };
            case LOST -> "AnEP platnost ověřit nedokáže";
            case INVALID -> "neplatnost je prokázána";
        };
    }

    static String tolerantWords(Horizon h) {
        return switch (h.kind()) {
            case UNTIL -> "mohou ověřit do " + day(h.when());
            case OPEN -> "mohou ověřit, datum nelze stanovit";
            case LOST -> "platnost nejspíš neověří";
            case INVALID -> "neplatnost nejspíš potvrdí";
        };
    }

    private String actionDescription() {
        if (twoStamps()) {
            return mode.noun + " z úrovně B — přidána dvě časová razítka: první podpis ukotví"
                    + " v čase (úroveň T), pak doplnění revokačních informací a druhé, archivní"
                    + " časové razítko";
        }
        return switch (mode) {
            case PRESERVE -> "Uchování — doplnění revokačních informací a archivní časové razítko";
            // „Nad přerušenou řadou“ stálo i u souvislé řady, jejímuž poslednímu razítku jen
            // vypršel certifikát (situace 31 bez 32) — prodloužení se nabízí v obou případech
            // (politika uchovávání 6.5). Nalezeno 17. 10. 2026 sondou ProlongProbe.
            case PROLONG -> "Prodloužení — doplnění revokačních informací a nové časové razítko"
                    + " nad řadou razítek, která je přerušená nebo jejímu poslednímu razítku"
                    + " už vypršel certifikát";
            case RESTAMP -> "Přerazítkování — vložení revokačních informací, které o certifikátu"
                    + " něco vypovídají, a nové časové razítko";
        };
    }

    private String qualityWords() {
        return switch (tsaQuality) {
            case QUALIFIED -> "vydává kvalifikovaná časová razítka";
            case UNQUALIFIED -> "nevydává kvalifikovaná časová razítka";
            default -> "kvalifikovanost nezjištěna";
        };
    }

    // ── Obsah okna a protokolu ───────────────────────────────────────────────────────────────

    /** Titul protokolu i okna. */
    String title() {
        return "Záznam o úkonu: " + mode.noun;
    }

    /**
     * Obsah záznamu jako strom uzlů, ze kterého se staví okno i protokol.
     *
     * <p><b>Nahoře jedna věta, pod ní sbalené bloky</b> (pokyn uživatele 4. 10. 2026: okno po
     * uchování bylo pro běžného uživatele příliš složité). Běžný uživatel potřebuje vědět, zda
     * úkon dopadl a dokdy je dokument ověřitelný; otisky, revokační informace a stav prvků jsou
     * podklad pro toho, kdo se ptá dál. V protokolu jsou bloky rozbalené samy: {@link PrintUtil}
     * sází i obsah sbaleného bloku a jeho záhlaví vysází jako nadpis oddílu.
     *
     * <p>Blok „Shoda s předpovědí“ se při rozchodu otevře sám — to je jediný případ, kdy je
     * v podrobnostech něco, co by běžný uživatel měl vidět bez hledání.
     */
    VBox content() {
        VBox box = new VBox(6);
        box.setPadding(new Insets(10, 14, 10, 14));

        List<String> issues = discrepancies();
        box.getChildren().add(verdictRow(headline(issues), issues.isEmpty()));
        if (output != null) box.getChildren().add(text("Výsledný soubor: " + output.name()));
        if (!issues.isEmpty()) {
            box.getChildren().add(text("Výsledek se od toho, co AnEP před úkonem předpověděl,"
                    + " v některých bodech liší — viz blok „Shoda s předpovědí“ níže."));
        }
        // Blok „Ověření výsledného souboru“ vypadl 4. 10. 2026 (pokyn uživatele): datum ověření
        // a ověřitelnost dokumentu před úkonem a po něm opakovaly úvodní větu. Co z něj zůstává,
        // jsou výhrady — ověření s ústupky, k zadanému okamžiku nebo bez důvěryhodných seznamů
        // oslabuje všechno, co záznam o výsledku tvrdí, a zamlčet se proto nesmí. Objeví se jen
        // tehdy, když nastanou.
        for (String caveat : verificationCaveats()) box.getChildren().add(text("Upozornění: " + caveat));

        VBox action = body();
        action.getChildren().add(row("Úkon:", actionDescription()));
        action.getChildren().add(row("Proveden:", TimeFormat.full(performedAt)));
        String version = AboutDialog.version();
        action.getChildren().add(row("Aplikace:", "AnEP" + (version != null ? " " + version : "")));
        action.getChildren().add(row("Zdroj časových razítek:",
                (tsaName != null && !tsaName.isBlank() ? tsaName : "bez názvu")
                + " (" + qualityWords() + ")"));
        // U dvou razítek se datum bere z posledního (archivního) a musí to říct: první razítko
        // mohl vydat jiný certifikát téže autority.
        action.getChildren().add(row(twoStamps() ? "Certifikát archivního razítka platí do:"
                                                 : "Certifikát nového razítka platí do:",
                TimeFormat.full(tsaCertNotAfter)));
        List<StampFact> added = stampsAdded();
        for (int i = 0; i < added.size(); i++) {
            action.getChildren().add(row(stampLabel(i, added.size()), stampWords(added.get(i))));
        }
        if (promisedUntil != null) {
            action.getChildren().add(row("Datum ve jménu souboru:", day(promisedUntil.toInstant())));
        }
        if (embedded != null) action.getChildren().add(row("Vloženo:", embedded));
        box.getChildren().add(block("Úkon", action, false));

        VBox files = body();
        addFile(files, "Vstupní soubor:", input, inputFormat);
        if (detachedContent != null) {
            addFile(files, "Podepsaný obsah:", detachedContent, null);
            files.getChildren().add(row("", "Podepsaný obsah úkon nemění; nový je jen soubor"
                    + " s podpisem."));
        }
        files.getChildren().add(new javafx.scene.control.Separator());
        addFile(files, "Výsledný soubor:", output, outputFormat);
        files.getChildren().add(note("Otisky SHA-256 vážou tento záznam k oběma souborům. Shodu"
                + " lze ověřit kterýmkoli nástrojem pro výpočet otisku, ve Windows například"
                + " příkazem certutil -hashfile <soubor> SHA256."));
        box.getChildren().add(block("Soubory", files, false));

        VBox revocations = body();
        addRevocations(revocations, "Už v dokumentu před úkonem:", revocationsKept(), "žádné");
        String withheldWords = CzechPlural.count(withheldRevocations,
                "údaj vydaný", "údaje vydané", "údajů vydaných")
                + " až po expiraci certifikátu bez závazku vydavatele, že stav drží i po ní;"
                + " o certifikátu nic " + (withheldRevocations == 1 ? "nedokládá" : "nedokládají");
        String availableVerb = CzechPlural.form(withheldRevocations, "byl", "byly", "bylo");
        List<RevocationFact> kept = revocationsKept();
        addRevocations(revocations, "Přidané úkonem:", revocationsAdded(),
                withheldRevocations > 0
                        ? "žádné — k dispozici " + availableVerb + " jen " + withheldWords
                        : !kept.isEmpty() ? "žádné — potřebné údaje už dokument nesl"
                        : "žádné");
        if (withheldRevocations > 0 && !revocationsAdded().isEmpty()) {
            revocations.getChildren().add(row("Nevloženo:", withheldWords));
        }
        // Dvě různé příčiny, dvě různé věty. Do 4. 10. 2026 tu stála jediná obecná („ověřovatel
        // si čerstvý údaj vyžádá i tehdy, když vložený je"), a u údaje o certifikátu posledního
        // razítka se četla, jako by ho AnEP vložit zapomněl nebo odmítl. Nález uživatele nad
        // uchováním PAdES B-LT, kde jediným takovým údajem byla OCSP k razítku, které úkon právě
        // přidal.
        List<RevocationFact> fetched = revocationsFetchedAfter();
        List<RevocationFact> forLastStamp = fetched.stream().filter(RevocationFact::lastStamp).toList();
        List<RevocationFact> other = fetched.stream().filter(f -> !f.lastStamp()).toList();
        if (!forLastStamp.isEmpty()) {
            addRevocations(revocations, "K poslednímu razítku:", forLastStamp, "");
            revocations.getChildren().add(note("Údaj o certifikátu posledního (právě přidaného)"
                    + " časového razítka výsledný soubor nenese, a nést ještě nemůže: podle"
                    + " EN 319 142-1 (kap. 6.3) se vkládá až před dalším razítkem, tedy při příštím"
                    + " uchování. Do té doby si ho ověřovatel opatří sám, dokud certifikát razítka"
                    + " platí."));
        }
        if (!other.isEmpty()) {
            addRevocations(revocations, "Mimo dokument při ověření výsledku:", other, "");
            revocations.getChildren().add(note("Tyto údaje výsledný soubor nenese; ověření si je"
                    + " opatřilo ze sítě nebo z mezipaměti. Nemusí to znamenat, že v dokumentu"
                    + " něco chybí: u certifikátu, který dosud platí, si ověřovatel může vyžádat"
                    + " čerstvý údaj i vedle vloženého."));
        }
        box.getChildren().add(block("Revokační informace", revocations, false));

        VBox elements = body();
        for (Pair p : pairs()) addPair(elements, p);
        elements.getChildren().add(note("Odhad pro CzechPOINT, podatelny a další validátory je"
                + " odvozený z jejich chování; jejich verzi ani nastavení AnEP nezná."));
        box.getChildren().add(block("Prvky před úkonem a po něm", elements, false));

        VBox match = body();
        if (issues.isEmpty()) {
            match.getChildren().add(text((mode == AdvisorView.ContinuityMode.RESTAMP
                       ? "Pro každý prvek, na který měl úkon dosáhnout, zůstává odhad výsledku"
                         + " pro CzechPOINT a podatelny beze změny"
                       : "Každý prvek, na který měl úkon dosáhnout, po úkonu obstál")
                    + ", žádný se nezhoršil a datum ve jménu souboru odpovídá ověření"
                    + " výsledného souboru."));
        } else {
            for (String s : issues) match.getChildren().add(text("• " + s));
        }
        box.getChildren().add(block("Shoda s předpovědí", match, !issues.isEmpty()));
        return box;
    }

    /**
     * Věta nahoře: jak úkon dopadl a dokdy je dokument ověřitelný — s časem, ne jen dnem,
     * protože lhůta končí koncem platnosti certifikátu razítka a ten má hodinu i minutu.
     *
     * <p>U přerazítkování se datum bere z odhadu pro CzechPOINT a podatelny, protože pro ty úkon
     * vznikl; přísné ověření výsledného souboru ho neuzná a jeho datum by tu lhalo opačným směrem.
     */
    String headline(List<String> issues) {
        String noun = mode.noun;
        boolean restamp = mode == AdvisorView.ContinuityMode.RESTAMP;
        Instant until = verifiableUntil();
        String result = issues.isEmpty()
                ? noun + " bylo úspěšné"
                : noun + " proběhlo, výsledek se ale od předpovědi liší";
        if (until == null) return result + ".";
        if (restamp) {
            return result + "; CzechPOINT, podatelny a další validátory mohou dokument podle"
                    + " odhadu ověřit do " + TimeFormat.full(until) + ".";
        }
        boolean prolonged = beforeDocUntil == null || until.isAfter(beforeDocUntil);
        return result + (prolonged ? ", možnost ověření byla prodloužena do "
                                   : ", možnost ověření zůstává do ")
                + TimeFormat.full(until) + ".";
    }

    /**
     * Dokdy je dokument po úkonu ověřitelný — datum z úvodní věty. Odtud bere termín i připomínka
     * do kalendáře, aby si ty dvě údaje nemohly protiřečit. {@code null}, když se datum stanovit
     * nedá.
     */
    Instant verifiableUntil() {
        return mode == AdvisorView.ContinuityMode.RESTAMP ? tolerantDocUntil() : afterDocUntil;
    }

    /** Výhrady k ověření výsledného souboru; prázdné, když proběhlo běžně. */
    List<String> verificationCaveats() {
        List<String> out = new ArrayList<>();
        if (!afterEnabledLenient.isEmpty()) {
            out.add("Výsledný soubor byl ověřen s ústupky: "
                    + String.join(", ", afterEnabledLenient) + ".");
        }
        if (afterCustomTime != null) {
            out.add("Výsledný soubor byl ověřen k zadanému okamžiku "
                    + TimeFormat.full(afterCustomTime) + ", ne k okamžiku ověření.");
        }
        if (afterTrustListsOffline) {
            out.add("Důvěryhodné seznamy nebyly při ověření výsledného souboru dostupné;"
                    + " ověřeno bez nich.");
        }
        return out;
    }

    private static VBox body() {
        VBox b = new VBox(4);
        b.setPadding(new Insets(6, 12, 8, 12));
        return b;
    }

    private static VBox block(String title, VBox body, boolean expanded) {
        return AdvisorView.collapsibleBlock(title, body, expanded);
    }

    private void addRevocations(VBox box, String label, List<RevocationFact> list,
                                String empty) {
        int n = list.size();
        box.getChildren().add(row(label, n == 0 ? empty
                : n + (n == 1 ? " údaj" : n < 5 ? " údaje" : " údajů")));
        for (RevocationFact f : list) box.getChildren().add(text("• " + revocationLine(f)));
    }

    static String revocationLine(RevocationFact f) {
        StringBuilder sb = new StringBuilder(f.crl() ? "CRL" : "OCSP");
        if (f.issuer() != null) sb.append(", vydal ").append(f.issuer());
        if (f.issued() != null) {
            sb.append(f.crl() ? ", vydán " : ", vydána ").append(TimeFormat.full(f.issued()));
        }
        if (!f.about().isEmpty()) {
            sb.append(f.about().size() == 1 ? ", o certifikátu " : ", o certifikátech ")
              .append(String.join(", ", f.about()));
        }
        sb.append("; ").append(formatSize(f.size()));
        if (f.where() != null) sb.append("; uloženo: ").append(f.where());
        return sb.toString();
    }

    private boolean twoStamps() {
        return fromBaseline && mode != AdvisorView.ContinuityMode.RESTAMP;
    }

    private void addFile(VBox box, String label, FileFact f, String format) {
        if (f == null) return;
        box.getChildren().add(row(label, f.name()));
        if (format != null) box.getChildren().add(row("Formát a úroveň:", format));
        box.getChildren().add(row("Velikost:", formatSize(f.size())));
        box.getChildren().add(row("Otisk SHA-256:", f.sha256() != null ? f.sha256()
                                                                        : "nepodařilo se spočítat"));
    }

    /**
     * Jeden prvek v rámečku. Rámeček proto, že bez něj se v protokolu prvky slévaly: nadpis prvku
     * je obyčejný řádek a datové řádky pod ním se zarovnají do jedné tabulky s řádky prvku
     * dalšího. PrintUtil sází {@code VBox} s okrajem jako ohraničený blok.
     */
    private void addPair(VBox outer, Pair p) {
        VBox box = new VBox(2);
        box.setPadding(new Insets(4, 8, 6, 8));
        box.setStyle("-fx-border-color: #cccccc; -fx-border-width: 1;");
        VBox.setMargin(box, new Insets(4, 0, 2, 0));
        outer.getChildren().add(box);
        ElementState b = p.before(), a = p.after();
        ElementState any = a != null ? a : b;
        String name = any.name() != null && !any.name().isBlank() ? " — " + any.name() : "";
        String head = a != null ? "Prvek č. " + p.number() + ": " + any.noun() + name
                                : "Prvek nenalezený po úkonu: " + any.noun() + name;
        Label l = text(head);
        l.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");
        box.getChildren().add(l);
        if (b == null) {
            box.getChildren().add(row("Před úkonem:", "nebyl — přibyl úkonem"));
        } else {
            box.getChildren().add(row("Před úkonem:", verdictWord(b) + "; " + anepWords(b.anep())));
            if (!b.concessions().isEmpty()) {
                box.getChildren().add(row("Závěr stál na ústupku:", String.join(", ", b.concessions())));
            }
            // U dokumentu s jediným prvkem je řádek bezpředmětný (pokyn uživatele 5. 10. 2026):
            // brány nabídnou úkon jen tehdy, když dosáhne aspoň na jeden prvek, a ptají se stejnými
            // predikáty (AdvisorView.offeredActionReaches) nad týmž výčtem — u jediného prvku tedy
            // vyjde vždy „dosáhne“.
            if (before.size() > 1) {
                box.getChildren().add(row("Úkon na prvek:", b.reached() ? "dosáhne"
                        : "nedosáhne" + (b.obstacle() != null ? " — " + b.obstacle() : "")));
            }
        }
        if (a != null) {
            // Před úkonem se mohlo ověřovat s ústupkem, výsledný soubor se ověřuje bez něj (ústupek
            // platí jen pro jedno ověření). Bez upřesnění četl uživatel „platná → s neznámou
            // platností“ jako zhoršení, které úkon způsobil; změřeno 17. 10. 2026 na úředním
            // výpisu: původní i přerazítkovaný soubor bez ústupku INDETERMINATE, s ním
            // TOTAL_PASSED.
            boolean strictAfter = b != null && !b.concessions().isEmpty()
                    && afterEnabledLenient.isEmpty();
            box.getChildren().add(row(strictAfter ? "Po úkonu (bez ústupků):" : "Po úkonu:",
                    verdictWord(a) + "; " + anepWords(a.anep())));
            if (strictAfter) {
                box.getChildren().add(row("", "Před úkonem se ověřovalo s ústupkem, výsledný soubor"
                        + " bez něj. Se stejným ústupkem ho lze ověřit znovu tlačítkem „Otevřít"
                        + " výsledný soubor v AnEP“."));
            }
            if (b == null && a.kind() == ElementKind.TIMESTAMP && a.certNotAfter() != null) {
                box.getChildren().add(row("Certifikát razítka platí do:", day(a.certNotAfter())));
            }
        }
        if (b != null || a != null) {
            String tb = b != null ? tolerantWords(b.tolerant()) : null;
            String ta = a != null ? tolerantWords(a.tolerant()) : null;
            box.getChildren().add(row("CzechPOINT a podatelny:",
                    tb == null ? ta : ta == null ? tb : tb.equals(ta) ? ta
                            : "před úkonem " + tb + ", po úkonu " + ta));
        }
    }

    private static HBox verdictRow(String text, boolean ok) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;");
        HBox h = new HBox(l);
        h.setPadding(new Insets(6, 10, 6, 10));
        // Barvy jsou ty, které PrintUtil.verdictColour pozná jako řádek výsledku.
        h.setStyle("-fx-background-color: " + (ok ? "#1a7a1a" : "#9a6700") + ";");
        return h;
    }

    private static HBox row(String label, String value) {
        Label k = new Label(label);
        k.setMinWidth(230);
        k.setPrefWidth(230);
        k.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        Label v = new Label(value);
        v.setWrapText(true);
        v.setStyle("-fx-font-size: 12px;");
        HBox.setHgrow(v, javafx.scene.layout.Priority.ALWAYS);
        return new HBox(8, k, v);
    }

    private static Label text(String s) {
        Label l = new Label(s);
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 12px;");
        return l;
    }

    private static Label note(String s) {
        Label l = text(s);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: #555555;");
        return l;
    }

    private static String formatSize(long bytes) {
        return String.format(java.util.Locale.ROOT, "%,d B", bytes).replace(',', ' ');
    }

    // ── Otisk ────────────────────────────────────────────────────────────────────────────────

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            return null;
        }
    }

    /** SHA-256 souboru šestnáctkově, nebo {@code null}, když se soubor přečíst nedá. */
    static String sha256(File f) {
        if (f == null) return null;
        try (InputStream in = Files.newInputStream(f.toPath())) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }
}
