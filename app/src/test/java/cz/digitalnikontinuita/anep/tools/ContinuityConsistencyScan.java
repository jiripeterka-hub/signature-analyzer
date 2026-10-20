package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.FixationService;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.AdvisorView;
import cz.digitalnikontinuita.anep.ui.ContinuityView;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hledá v textech aplikace <b>vnitřní rozpory</b> — věty, které si na jedné kartě odporují —
 * a <b>nadpisy, které slibují obsah, jenž pod nimi není</b>.
 *
 * <p>Od 14. 9. 2026 pokrývá čtyři záložky, ne jen Digitální kontinuitu: téhož dne se ukázalo,
 * že dvě chyby ležely v Analýze a v Rozšířeném přehledu, kam sonda neviděla.
 *
 * <h2>Proč to nešlo hledat okem</h2>
 *
 * <p>8. 9. 2026 našel uživatel čtyři chyby v textech a všechny měly týž vzorec: <b>věta si
 * přivlastnila údaj, který popisuje něco jiného</b> — datum z jiné cesty, certifikát z jiného
 * místa řetězu, horizont bez ohledu na souvislost řady razítek, druh revokačního údaje z toho,
 * co se použilo, místo z toho, co dokument nese. Každou z nich odhalil jeden konkrétní dokument
 * a na snímku ji bylo vidět jen tomu, kdo věděl, co hledat.
 *
 * <p>Rozpory mezi bloky se přitom dají hledat strojově: karta odpovídá na tutéž otázku z několika
 * stran, takže dvě odpovědi, které se vylučují, jsou nález bez ohledu na to, která z nich je
 * správná. Tahle sonda proto nestaví na znalosti pravidel, nýbrž na <b>protikladech ve slovech</b>,
 * která texty používají.
 *
 * <h2>Co sonda netvrdí</h2>
 *
 * <p>Nález není důkaz chyby, jen místo, kam se podívat: pravidla jsou hrubá a dokument může nést
 * několik prvků, jejichž texty se do jednoho balíku slijí. Nulový nález naopak není důkaz
 * správnosti — pravidla pokrývají jen vzorce, na které se už přišlo.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.tools.ContinuityConsistencyScan \
 *       -Dexec.args="-out D:\out\rozpory.txt D:\tmp"
 * </pre>
 */
public final class ContinuityConsistencyScan extends Application {

    /** Vypisuje, kolik textu se z které záložky sebralo — pravidlo, které nemá co prohledat,
     *  mě žádný nález nedá a vypadá to jako čistý výsledek. */
    private static boolean debug;

    /** Záložka, na jejíž text pravidlo míří. */
    private enum Tab {
        KONTINUITA("Digitální kontinuita"), ANALYZA("Analýza"),
        ZJEDNODUSENY("Zjednodušený přehled"), ROZSIRENY("Rozšířený přehled"),
        /** Pravidlo bez vazby na záložku — zkouší se na všech. */
        VSUDE("(všude)");

        private final String label;
        Tab(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    /**
     * Co pravidlo hledá.
     *
     * <p>{@link #ROZPOR} byl dlouho jediný druh a na obě chyby ze 14. 9. 2026 nedosáhl: nadpis
     * „Aplikace detekovala následující důvod(y)…" bez jediného důvodu pod sebou a blok „Použité
     * kryptografické algoritmy" bez algoritmu si totiž s ničím neodporují — jen slibují, co
     * nesplní. Na to je {@link #CHYBI}.
     */
    private enum Kind { ROZPOR, CHYBI }

    /**
     * Jedno pravidlo.
     *
     * @param kind        {@code ROZPOR} = nález, když jsou obě fráze; {@code CHYBI} = nález,
     *                    když je první a druhá ne
     * @param second      u {@code CHYBI} smí nést několik přijatelných podob oddělených „|";
     *                    stačí kterákoli z nich
     * @param withinBlock hledat obě v témže bloku (nadpis + tělo), ne kdekoli na kartě
     */
    private record Rule(String name, Tab tab, Kind kind, String first, String second,
                        boolean withinBlock) {

        /** Dosavadní podoba: rozpor v záložce Digitální kontinuita. */
        Rule(String name, String first, String second, boolean withinBlock) {
            this(name, Tab.KONTINUITA, Kind.ROZPOR, first, second, withinBlock);
        }

        /** Je pravidlo splněno na tomhle kusu textu? */
        boolean hits(String haystack) {
            if (!haystack.contains(first)) return false;
            if (kind == Kind.CHYBI) {
                for (String alt : second.split("\\|")) {
                    if (haystack.contains(alt)) return false;
                }
                return true;
            }
            // Prázdná druhá fráze = pravidlo o jediném řetězci, který se na kartě nemá objevit
            // vůbec (nedoplněný text, značka odkazu, tautologie).
            return second.isEmpty() || haystack.contains(second);
        }
    }

    /**
     * Nadpisy, kterými začíná část o jednom prvku. Podle nich se text karty dělí, aby se
     * nesrovnávala tvrzení o <b>různých</b> podpisech: „pet podpisu LT.pdf" nese pět podpisů,
     * z nichž jeden má vloženou CRL a druhý odpověď OCSP — a sonda to bez dělení hlásila jako
     * rozpor v druhu vloženého údaje.
     *
     * <p>Od 9. 9. 2026 jsou v seznamu i dva nadpisy společné části: „Dokument jako celek“ a
     * „Prvek č. N“ ve výčtu uvnitř bloku „AnEP nabízí možnost…“. Bez nich by se celá ta část
     * slila s posledním prvkem výčtu nad ní a sonda by srovnávala jeho tvrzení s tvrzeními
     * o všech ostatních.
     */
    private static final java.util.regex.Pattern ELEMENT_HEAD = java.util.regex.Pattern.compile(
            "(?m)^(?=Autentizační prvek č\\. |Prvek č\\. |Dokument jako celek$"
            + "|Podpis$|Pečeť$|Časové razítko$)");

    /**
     * Pravidla se dělí podle rozsahu, a to je podstatné.
     *
     * <p>Karta odpovídá na tutéž otázku z několika stran, takže dvě „vylučující" věty daleko od
     * sebe často mluví o <b>různých lhůtách</b> a rozpor to není. Ukázalo se to hned při první
     * zkoušce: pravidlo „ověří jej kdokoli" × „ověří pouze validátory s vlastní zásobou" hlásilo
     * nález na dvou zdravých dokumentech, kde první věta patří uchování do jednoho data a druhá
     * pozdější záložní variantě. Takové pravidlo bylo zahozeno — sonda, která křičí na zdravé
     * dokumenty, se přestane čítat.
     */
    private static final List<Rule> RULES = List.of(
            // Uvnitř jednoho bloku: nadpis říká „nedokáží“, tělo slibuje ověření.
            new Rule("tolerantní blok: nadpis „nedokáží“ vs. slib v těle",
                    "ověřit nedokáží", "proto mohou ověřit až do konce", true),
            new Rule("nadpis „Nejméně do“ vs. „právě do“ v těle",
                    "Nejméně do", "právě do uvedeného data", true),
            new Rule("nadpis „nedokáže“ vs. datum v těle",
                    "dnes již nedokáže", "Proto možnost ověřit platnost", true),
            // Napříč kartou: tvrzení, která se vylučují bez ohledu na to, kde stojí.
            new Rule("dosah: „všemi validátory“ vs. „nevrátí“",
                    "platnosti všemi validátory", "ověřitelnost nevrátí", false),
            new Rule("vložený údaj: „bez vložených“ vs. „vložená je“",
                    "ale bez vložených revokačních informací", "v dokumentu vložená je", false),
            new Rule("druh vloženého údaje: CRL vs. OCSP",
                    "v podobě CRL seznamu", "Odpověď OCSP vložená v dokumentu", false),
            new Rule("druh vloženého údaje: OCSP vs. CRL",
                    "v podobě odpovědi OCSP", "CRL seznam vložený v dokumentu", false),
            new Rule("přerušená řada vs. slib tolerantního okruhu",
                    "Řada razítek je přerušena", "proto mohou ověřit až do konce", false),
            // Vlastní kotva důvěry. Tolerantní blok o ní říká pravdu („Ten tyto nástroje nemají
            // odkud vzít"), zatímco tři jiná místa téže karty slibovala opak — a všechna tři
            // proto, že se ptala predikátu shouldOfferOtherValidators, který se na kotvu neptal.
            // Naměřeno 8. 9. 2026 na „PAdES T zaruceny ICA.pdf" (pouze zaručený podpis ověřený
            // jen díky připnutému kořeni I.CA) a na „PAdES LTA zaruceny PSS_Do_02_05_2032.pdf",
            // kde slib „ověřit všemi obvyklými nástroji" stál u kladného verdiktu.
            new Rule("vlastní kotva vs. slib všem obvyklým nástrojům",
                    "Ten tyto nástroje nemají odkud vzít", "ověřit všemi obvyklými nástroji",
                    false),
            new Rule("vlastní kotva vs. slib CzechPOINTu",
                    "Ten tyto nástroje nemají odkud vzít", "ověřit jen na CzechPOINTu", false),
            new Rule("vlastní kotva vs. „zásoba je na tom jinak“",
                    "Ten tyto nástroje nemají odkud vzít",
                    "Nástroj s vlastní zásobou revokačních informací je na tom ale jinak", false),
            new Rule("vlastní kotva vs. „odpadne, co brání validátorům podle standardů“",
                    "Ten tyto nástroje nemají odkud vzít",
                    "odpadne tak i to, co dnes brání v ověření validátorům", false),
            // Uplynulá lhůta řečená dvakrát ve dvou po sobě jdoucích odstavcích. Původní podoba
            // tohohle pravidla hledala „skončila platnost" × „které již minulo" a byla nesmyslná:
            // ta dvě tvrzení spolu souhlasí. Nález přesto ukázal duplicitu, kterou nikdo nehledal
            // — proto pravidlo míří na ni.
            new Rule("uplynulá lhůta řečená dvakrát",
                    "které již minulo", "Ta doba už uplynula", false),
            // Samostatné razítko nemá podpisový certifikát ani podepsanou osobu.
            new Rule("samostatné razítko vs. podpisový certifikát",
                    "Jde o samostatné", "Podpis je založen na certifikátu", false),
            new Rule("samostatné razítko vs. „stav revokace podpisového certifikátu“",
                    "Jde o samostatné", "stav revokace podpisového certifikátu", false),
            // Tautologie, kterou dnes odhalil jeden dokument.
            new Rule("tautologie: razítko prokazuje razítko",
                    "Dřívější existenci časového razítka prokazuje časové razítko", "", false),
            // Značka odkazu, která se nesmí dostat na obrazovku.
            new Rule("neroztržená značka odkazu", "](", "", false),
            // Nevyplněný text.
            new Rule("nedoplněný text", "(Text bude doplněn.)", "", false),
            // Nadpis bloku s fakty se řídí druhem prvku, takže se s nimi nesmí rozejít.
            new Rule("nadpis „Rozbor podpisu“ nad pečetí",
                    "Rozbor podpisu", "Jde o elektronickou pečeť", true),
            new Rule("nadpis „Rozbor pečeti“ nad podpisem",
                    "Rozbor pečeti", "Jde o elektronický podpis", true),
            new Rule("nadpis „Rozbor časového razítka“ nad podpisem",
                    "Rozbor časového razítka", "Jde o elektronický podpis", true),
            // Prvek, jehož platnost aplikace ověřila, a týž prvek, o kterém výčet ve společné
            // části tvrdí, že se ověřit nepodařila. Tohle 14. 9. 2026 nastalo u samostatného
            // časového razítka: výčet dosazoval důvod domněnkou, protože prvek bez překážky
            // nedokázal jinak vysvětlit. Pravidlo je napříč kartou, protože obě věty stojí
            // v různých blocích.
            new Rule("ověřeno vs. „platnost se ověřit nepodařila“",
                    "ověřila platnost", "se ověřit nepodařila", false),
            // Táž dvojice o kus dál: prvek, kterému nic nebrání, a věta tvrdící, že mu chybí
            // revokační informace. Ta věta je poslední větev brány a dřív se ozývala i tam,
            // kde nechybí nic — u razítka, jehož certifikát je sám kotvou důvěry.
            new Rule("bez překážky vs. „chybí revokační informace“",
                    "žádná překážka není", "chybí revokační informace", false),

            // ── Pravidla druhu CHYBI, zavedená 14. 9. 2026 ────────────────────────────────
            //
            // Obě míří na chybu, kterou sonda v dosavadní podobě chytit nemohla: nadpis slibuje
            // obsah, který pod ním není. Nález to není jen esteticky — čtenář rozbalí blok
            // a musí sám odvodit, jestli se nic nenašlo, nebo nezobrazilo.

            // Analýza: úvodní věta výčtu důvodů bez jediného důvodu. Stávalo se u prvku, který má
            // situaci bez odpovídajícího důvodu (dvacet ze čtyřiceti osmi jich takových je)
            // a u každého neúspěšného samostatného časového razítka.
            new Rule("úvod výčtu důvodů bez jediného důvodu", Tab.ANALYZA, Kind.CHYBI,
                    "Aplikace detekovala následující důvod(y)", "V čem je problém:", false),

            // Rozšířený přehled: blok algoritmů, ve kterém žádný algoritmus není. Stávalo se
            // u všech samostatných časových razítek — ReportMapper jim ty dvě hodnoty nevyplňoval,
            // ačkoli je DSS zná. Pečeť má vlastní popisek, proto dvě přijatelné podoby.
            new Rule("blok algoritmů bez algoritmu", Tab.ROZSIRENY, Kind.CHYBI,
                    "Použité kryptografické algoritmy",
                    "Podpisový algoritmus:|Algoritmus pečeti:|Použitá hašovací funkce:", true),

            // Rozšířený přehled: blok o revokaci, který neřekne, oč se závěr opřel.
            new Rule("blok revokace bez využitého údaje", Tab.ROZSIRENY, Kind.CHYBI,
                    "Kontrola revokace", "Využité revokační informace:|Revokační informace:", true),

            // ── Pravidla, která mají smysl na kterékoli záložce ───────────────────────────
            new Rule("neroztržená značka odkazu", Tab.VSUDE, Kind.ROZPOR, "](", "", false),
            new Rule("nedoplněný text", Tab.VSUDE, Kind.ROZPOR, "(Text bude doplněn.)", "", false),
            // Zástupný text z formátovacího řetězce, který se nedostal k hodnotě.
            new Rule("nenahrazená zástupka", Tab.VSUDE, Kind.ROZPOR, "%s", "", false),
            new Rule("nenahrazená zástupka (číslo)", Tab.VSUDE, Kind.ROZPOR, "%d", "", false),
            // Prázdná hodnota tam, kde měl být objekt — typicky nedopatřením vypsané null.
            new Rule("vypsané null", Tab.VSUDE, Kind.ROZPOR, ": null", "", false));

    @Override
    public void start(Stage stage) {
        List<String> args = getParameters().getRaw();
        Path out = null;
        List<Path> roots = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            if ("-debug".equals(args.get(i))) debug = true;
            else if ("-out".equals(args.get(i))) out = Path.of(args.get(++i));
            else roots.add(Path.of(args.get(i)));
        }
        if (out == null || roots.isEmpty()) {
            System.out.println("usage: ContinuityConsistencyScan -out <file> <folder>...");
            Platform.exit();
            return;
        }
        final Path outPath = out;
        new Thread(() -> {
            try {
                run(outPath, roots);
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
            Platform.exit();
        }, "consistency-scan").start();
    }

    private void run(Path out, List<Path> roots) throws Exception {
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            try (var walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".pdf"))
                    .forEach(files::add);
            }
        }
        System.out.println("souborů: " + files.size());

        AppSettings settings = AppSettings.load();
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();

        Files.createDirectories(out.toAbsolutePath().getParent());
        int findings = 0;
        int scanned = 0;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out,
                StandardCharsets.UTF_8))) {
            for (Path file : files) {
                List<Scanned> card;
                try {
                    card = tabBlocks(file.toFile(), settings, verifier);
                } catch (Exception e) {
                    w.println("SKIP\t" + file + "\t" + e);
                    continue;
                }
                scanned++;
                if (debug) {
                    for (Scanned t : card) {
                        System.out.printf("  [ladění] %-22s znaků=%d bloků=%d%n",
                                t.tab(), t.text().length(), t.blocks().size());
                    }
                }
                for (Scanned tab : card) {
                    // Části po prvcích: tvrzení o dvou různých podpisech se nesmějí srovnávat.
                    List<String> elements = List.of(ELEMENT_HEAD.split(tab.text()));
                    for (Rule rule : RULES) {
                        if (rule.tab() != Tab.VSUDE && rule.tab() != tab.tab()) continue;
                        String where = null;
                        for (String haystack : rule.withinBlock() ? tab.blocks() : elements) {
                            if (rule.hits(haystack)) {
                                where = haystack;
                                break;
                            }
                        }
                        if (where == null) continue;
                        findings++;
                        w.println((rule.kind() == Kind.CHYBI ? "CHYBI" : "ROZPOR")
                                + "\t" + tab.tab() + "\t" + rule.name() + "\t" + file);
                        // Kde přesně to je. Bez toho se každý nález prověřuje snímkem a hádáním,
                        // což u pěti nálezů na 214 souborech spolykalo víc času než sken sám.
                        w.println("\tmísto: " + snippet(where, rule.first()));
                        if (rule.kind() == Kind.ROZPOR && !rule.second().isEmpty()) {
                            w.println("\tmísto: " + snippet(where, rule.second()));
                        }
                    }
                }
                if (scanned % 20 == 0) {
                    System.out.println("  zpracováno " + scanned + ", nálezů " + findings);
                    w.flush();
                }
            }
            w.println();
            w.println("# souborů=" + files.size() + " zpracováno=" + scanned
                    + " nálezů=" + findings);
        }
        System.out.println("hotovo: zpracováno " + scanned + ", nálezů " + findings);
    }

    /** Okolí nalezené fráze, na jeden řádek — kvůli prověřování nálezů. */
    private static String snippet(String text, String needle) {
        int at = text.indexOf(needle);
        if (at < 0) return "(nenalezeno)";
        int from = Math.max(0, at - 60);
        int to = Math.min(text.length(), at + needle.length() + 60);
        return text.substring(from, to).replace('\n', '⏎');
    }

    /**
     * Text karty a její bloky zvlášť.
     *
     * <p>Dřív obojí přišlo v jednom seznamu a pravidla „uvnitř bloku" se pak testovala i na
     * celém textu — sonda hlásila rozpor mezi nadpisem jednoho prvku a tělem druhého.
     */
    private record Scanned(Tab tab, String text, List<String> blocks) { }

    /**
     * Text čtyř záložek, každá zvlášť a k tomu její bloky.
     *
     * <p>Do 14. 9. 2026 se sbírala jen Digitální kontinuita. Obě chyby nalezené toho dne ale
     * ležely jinde — jedna v Analýze, druhá v Rozšířeném přehledu — a sonda se na ně neměla
     * jak podívat. Staví se proto všechny čtyři pohledy, každý se skutečnými službami.
     *
     * <p>Zjednodušený přehled a Rozšířený přehled nepotřebují akční služby: tlačítka nemají.
     * {@code AdvisorView} je potřebuje všechny, protože na nich visí nabídka úkonů.
     */
    private List<Scanned> tabBlocks(File file, AppSettings settings,
                                    CommonCertificateVerifier verifier) throws Exception {
        DSSDocument doc = new DocumentLoader().load(file);
        var pdfSource = cz.digitalnikontinuita.anep.core.PdfSource.of(file);
        ValidationResult result = new ReportMapper().map(file.getName(), null,
                new ValidatorFactory(settings).validate(doc, null, verifier, null), settings,
                cz.digitalnikontinuita.anep.core.PdfSignatureAppearanceExtractor
                        .extract(pdfSource, null));

        final Object lock = new Object();
        final boolean[] done = { false };
        final List<Scanned> tabs = new ArrayList<>();
        Platform.runLater(() -> {
            try {
                ContinuityView cv = new ContinuityView();
                AdvisorView av = new AdvisorView();
                av.setAppSettings(settings);
                av.setSourceFile(file, null, null);
                var ref = new java.util.concurrent.atomic.AtomicReference<>(verifier);
                av.setFixationService(new FixationService(ref));
                av.setRestampingService(new cz.digitalnikontinuita.anep.core.RestampingService());
                av.setAugmentationService(
                        new cz.digitalnikontinuita.anep.core.AugmentationService(ref));
                av.setExtensionService(
                        new cz.digitalnikontinuita.anep.core.SignatureExtensionService(ref));
                av.setTimestampingService(
                        new cz.digitalnikontinuita.anep.core.TimestampingService(ref));
                av.setContinuityView(cv);
                av.show(result);
                new Scene(cv.getRoot(), 980, 700);
                tabs.add(scanOne(Tab.KONTINUITA, cv.getRoot()));
                // Analýza staví týž AdvisorView; scéna je kvůli rolovací ploše, jejíž obsah
                // by se bez ní nevykreslil.
                new Scene(av.getRoot(), 980, 700);
                tabs.add(scanOne(Tab.ANALYZA, av.getRoot()));

                cz.digitalnikontinuita.anep.ui.ResultView rv =
                        new cz.digitalnikontinuita.anep.ui.ResultView();
                rv.show(result);
                new Scene(rv.getRoot(), 980, 700);
                tabs.add(scanOne(Tab.ZJEDNODUSENY, rv.getRoot()));

                cz.digitalnikontinuita.anep.ui.ExtendedView ev =
                        new cz.digitalnikontinuita.anep.ui.ExtendedView();
                ev.show(result);
                new Scene(ev.getRoot(), 980, 700);
                tabs.add(scanOne(Tab.ROZSIRENY, ev.getRoot()));
            } catch (Exception e) {
                tabs.add(new Scanned(Tab.KONTINUITA, "(chyba: " + e + ")", List.of()));
            } finally {
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
        });
        synchronized (lock) {
            while (!done[0]) lock.wait();
        }
        return List.copyOf(tabs);
    }

    /** Text jednoho pohledu a jeho bloky. */
    private static Scanned scanOne(Tab tab, Node root) {
        StringBuilder sb = new StringBuilder();
        List<String> blocks = new ArrayList<>();
        collect(root, sb);
        collectBlocks(root, blocks);
        return new Scanned(tab, sb.toString(), List.copyOf(blocks));
    }

    /**
     * Bloky postavené {@code AdvisorView.collapsibleBlock}: VBox, jehož prvním dítětem je HBox
     * se šipkou a nadpisem. Text bloku se bere z celého jeho podstromu, tedy včetně vnořených —
     * ty jsou součástí sdělení toho vnějšího.
     *
     * <p><b>Šipka je tvar, ne znak.</b> Poznávalo se to podle {@code Labeled} s „▸"/„▾", jenže
     * od 9. 9. 2026 kreslí {@code collapsibleBlock} trojúhelník jako {@code Polygon}
     * v {@code StackPane} — znaková dvojice byla nesouměrná. Od té chvíle tahle metoda
     * <b>nenašla ani jeden blok</b> a všechna pravidla „uvnitř bloku" tiše neplatila: sonda
     * hlásila nulu, protože neměla co prohledat. Odhaleno 14. 9. 2026 při rozšiřování na další
     * záložky, kdy nové pravidlo nevystřelilo ani nad chybou, kterou mělo chytit.
     */
    private static void collectBlocks(Node n, List<String> out) {
        if (n instanceof VBox box && box.getChildren().size() == 2
                && box.getChildren().get(0) instanceof javafx.scene.layout.HBox header
                && header.getChildren().size() == 2
                && header.getChildren().get(0) instanceof javafx.scene.layout.StackPane arrow
                && arrow.getChildren().size() == 1
                && arrow.getChildren().get(0) instanceof javafx.scene.shape.Polygon
                && header.getChildren().get(1) instanceof Labeled) {
            StringBuilder one = new StringBuilder();
            collect(box, one);
            out.add(one.toString());
        }
        if (n instanceof ScrollPane sp && sp.getContent() != null) {
            collectBlocks(sp.getContent(), out);
        }
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) collectBlocks(c, out);
        }
    }

    /** Texty z celého podstromu, včetně obsahu rolovací plochy, kterou skin bez scény neukáže. */
    private static void collect(Node n, StringBuilder sb) {
        if (n instanceof Labeled l && l.getText() != null) sb.append(l.getText()).append('\n');
        if (n instanceof javafx.scene.text.TextFlow tf) {
            for (Node c : tf.getChildrenUnmodifiable()) {
                if (c instanceof javafx.scene.text.Text t) sb.append(t.getText());
                if (c instanceof Labeled l && l.getText() != null) sb.append(l.getText());
            }
            sb.append('\n');
            return;
        }
        if (n instanceof ScrollPane sp && sp.getContent() != null) collect(sp.getContent(), sb);
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) collect(c, sb);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
