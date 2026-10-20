package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.FixationService;
import cz.digitalnikontinuita.anep.core.OfficeSignaturePeek;
import cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.Finding;
import cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.Revocation;
import cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.Signature;
import cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.Timestamp;
import cz.digitalnikontinuita.anep.core.TimeFormat;

import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.spi.QcStatementUtils;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Nahlédnutí do podpisu v kancelářském dokumentu — okno, které říká, co je v podpisu napsáno,
 * a odděleně to, co z toho šlo ověřit.
 *
 * <h2>Proč je verdikt záměrně žádný</h2>
 *
 * <p>Aplikace tenhle druh podpisu neověřuje: OOXML potřebuje {@code RelationshipTransform}, který
 * DSS nezná, takže <b>není doloženo, že podpis kryje obsah, který čtenář vidí</b>. Karta proto
 * nemá barvu výsledku — ani zelenou, ani okrovou, ani červenou. Šedomodrý pruh je vlastní stav
 * „neověřeno“ a je to jediná poctivá odpověď.
 *
 * <p>Kdyby se jméno podepsané osoby vysadilo do obvyklé karty, čtenář by si odnesl, že dokument
 * podepsal ten člověk. To by bylo tvrzení, které tady nikdo neprokázal — a je to přesně ta chyba,
 * které se aplikace jinde systematicky brání.
 *
 * <h2>Co se ověřit dá i tak</h2>
 *
 * <p>Dvě věci na transformacích nezávisí: <b>token časového razítka</b> (vlastní podpis nad
 * otiskem podpisové hodnoty) a <b>vlastnosti certifikátu</b> — doba platnosti, kvalifikovanost
 * podle QcStatements a to, zda jeho vydavatel figuruje v evropských důvěryhodných seznamech.
 * Zjišťuje se to napřímo, ne přes plné ověření podpisu, a text u toho říká, co z toho neplyne.
 */
final class OfficePeekDialog {

    private OfficePeekDialog() { }

    /** Šedomodrá „neověřeno“ — schválně žádná z barev verdiktu. */
    private static final String NEUTRAL = "#4a5a6a";

    /** Okno právě na obrazovce; viz začátek {@link #show}. */
    private static Stage current;

    static void show(Window owner, File file, Finding finding,
                     AtomicReference<CommonCertificateVerifier> verifierRef,
                     FixationService fixationService, AppSettings settings) {
        // Dvě okna o dvou různých dokumentech vedle sebe být nesmějí. Staré zavírá už začátek
        // nového ověření (MainView.analyze → close()); tohle je pojistka pro cestu mimo něj.
        close();
        Stage stage = new Stage();
        current = stage;
        stage.setOnHidden(e -> { if (current == stage) current = null; });
        stage.setTitle("Podpis v kancelářském dokumentu");
        stage.initOwner(owner);
        // Nemodální: čtenář musí moct vložit další soubor, aniž by tohle okno napřed zavíral.
        // Do 24. 9. 2026 bylo APPLICATION_MODAL a hlavní okno další soubor nepřijalo, dokud
        // tohle okno nezavřel ručně.
        stage.setResizable(true);

        VBox content = buildContent(file, finding, verifierRef);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button forensic = new Button("Vytvořit forenzní balíček");
        forensic.setOnAction(e -> {
            stage.close();
            List<File> files = new ArrayList<>();
            files.add(file);
            ForensicPackageDialog.show(owner, files, file.getName(),
                    cz.digitalnikontinuita.anep.core.FixationService.Reason.OFFICE_SIGNED, fixationService, settings);
        });

        Button close = new Button("Zavřít");
        close.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, forensic, spacer, close);
        buttons.setPadding(new Insets(10, 18, 14, 18));

        VBox root = new VBox(scroll, buttons);
        root.setPrefWidth(700);
        root.setPrefHeight(620);
        root.setStyle("-fx-background-color: #ffffff;");
        Scene scene = new Scene(root);
        // Písmo aplikace, ne výchozí písmo JavaFX: styly se přebírají z okna, které dialog
        // otevřelo, stejně jako to dělá MainView u odpojených záložek.
        if (owner instanceof Stage ownerStage && ownerStage.getScene() != null) {
            scene.getStylesheets().addAll(ownerStage.getScene().getStylesheets());
        }
        stage.setScene(scene);
        stage.show();
    }

    /** Zavře okno, je-li otevřené — volá se na začátku ověřování dalšího souboru. */
    static void close() {
        if (current != null) current.close();
        current = null;
    }

    /**
     * Obsah okna. Oddělené od {@link #show}, aby se dal vysadit a prohlédnout i bez otevírání
     * modálního okna — formulace jsou tu to hlavní a na ty se musí dát podívat.
     */
    static VBox buildContent(File file, Finding finding,
                             AtomicReference<CommonCertificateVerifier> verifierRef) {
        VBox content = new VBox(14);
        content.setPadding(new Insets(18));
        content.setStyle("-fx-background-color: #ffffff;");

        Label headline = new Label(file.getName());
        headline.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #111111;");
        wrap(headline);
        content.getChildren().add(headline);

        int count = finding.signatures().size();
        Label banner = new Label("Podpis nebyl ověřen — "
                + CzechPlural.count(count, "nalezen 1 podpis", "nalezeny " + count + " podpisy",
                        "nalezeno " + count + " podpisů").replaceFirst("^\\d+\\s", ""));
        banner.setStyle("-fx-background-color: " + NEUTRAL + "; -fx-text-fill: #ffffff;"
                + " -fx-font-weight: bold; -fx-padding: 6 10 6 10;");
        banner.setMaxWidth(Double.MAX_VALUE);
        wrap(banner);
        content.getChildren().add(banner);

        content.getChildren().add(paragraph(introText(finding.family())));

        int index = 1;
        for (Signature sig : finding.signatures()) {
            content.getChildren().add(signatureCard(sig, finding, index++, count, verifierRef));
        }

        return content;
    }

    /**
     * Proč se ověření nekonalo. Dva různé důvody, dvě různé věty — a je to rozdíl, ne odstín.
     *
     * <p>U OOXML aplikace ověřovat <b>neumí</b>: chybí {@code RelationshipTransform}. U ODF umí
     * a běžně to dělá — změřeno, podepsaný ODT projde jako XAdES-BASELINE-T — takže když se sem
     * ODF dostane, něco s tím souborem je. Jedna společná věta by v jednom z těch případů lhala.
     */
    static String introText(OfficeSignaturePeek.Family family) {
        if (family == OfficeSignaturePeek.Family.ODF) {
            return "Jde o " + family.label() + ". Podpisy v tomhle formátu aplikace běžně ověřuje,"
                    + " u tohoto souboru se to ale nepodařilo — bývá za tím poškozený obal"
                    + " kontejneru nebo podpis, který nekryje obsah dokumentu (například podpis"
                    + " maker). Níže je proto aspoň to, co se dá z podpisu přečíst, oddělené na"
                    + " jeho vlastní tvrzení a na to, co z toho šlo ověřit samostatně.";
        }
        return "Jde o " + family.label() + ". Podpisy v něm aplikace zatím neověřuje: potřebují"
                + " převod, který ověřovací knihovna nezná, a bez něj nelze doložit, že podpis"
                + " kryje právě ten obsah, který v dokumentu vidíte. Níže je proto odděleno, co"
                + " podpis o sobě tvrdí, a co se z toho dalo ověřit samostatně.";
    }

    // ── Karta jednoho podpisu ────────────────────────────────────────────────────────────────

    private static VBox signatureCard(Signature sig, Finding finding, int index, int total,
                                      AtomicReference<CommonCertificateVerifier> verifierRef) {
        VBox card = new VBox(6);
        card.setStyle("-fx-border-color: #cccccc; -fx-border-width: 1; -fx-padding: 12;"
                + " -fx-background-color: #ffffff;");

        if (total > 1) card.getChildren().add(sectionLabel("Podpis č. " + index));

        card.getChildren().add(sectionLabel("Co podpis tvrdí"));
        X509Certificate signer = sig.signer();
        if (signer != null) {
            card.getChildren().add(row("Podepsaná osoba:",
                    DnUtil.dnField(signer.getSubjectX500Principal().getName(), "2.5.4.3", "CN")));
            card.getChildren().add(row("Certifikát vydal:",
                    DnUtil.dnField(signer.getIssuerX500Principal().getName(), "2.5.4.3", "CN")));
        } else {
            card.getChildren().add(row("Podepsaná osoba:", "v podpisu není uveden certifikát"));
        }
        if (sig.claimedTime() != null) {
            card.getChildren().add(row("Deklarovaný čas:", TimeFormat.full(sig.claimedTime())));
        }
        if (sig.purpose() != null) card.getChildren().add(row("Účel podpisu:", sig.purpose()));
        String provider = readableProvider(sig.provider());
        if (provider != null) card.getChildren().add(row("Podepsáno programem:", provider));

        card.getChildren().add(note(
                "Tohle jsou údaje, které podpis o sobě uvádí. Neověřené: dokud se nepodaří ověřit"
                + " podpis, není doloženo, že dokument podepsala právě tato osoba ani že jeho"
                + " obsah je od té doby beze změny."));

        card.getChildren().add(sectionLabel("Co bylo ověřeno samostatně"));
        addCertificateFacts(card, signer, sig.claimedTime(), verifierRef);
        addTimestampFacts(card, sig);
        addAlgorithmFacts(card, sig);

        addContentFacts(card, sig);

        card.getChildren().add(sectionLabel("Co podpis kryje"));
        card.getChildren().add(row("Kryté části:",
                sig.references() + " z " + finding.partCount() + " částí souboru"));
        if (!finding.uncoveredParts().isEmpty()) {
            card.getChildren().add(row("Nekryté části:",
                    String.join(", ", finding.uncoveredParts())));
            card.getChildren().add(note(
                    "Nepokryté části jsou u tohoto formátu běžné — nekryje je ani Microsoft"
                    + " Office. Jsou v nich ale mimo jiné údaje o autorovi, názvu a datu úpravy"
                    + " dokumentu, takže je lze změnit, aniž se podpis poruší."));
        }
        return card;
    }

    /**
     * Vlastnosti certifikátu, které se dají zjistit bez ověření podpisu.
     *
     * <p>Doba platnosti a kvalifikovanost jsou v certifikátu samotném; důvěra se zjišťuje
     * dotazem do zásoby kotev, kterou aplikace drží z důvěryhodných seznamů. Není to plné
     * ověření certifikační cesty ani kontrola revokace — a text to říká.
     */
    private static void addCertificateFacts(VBox card, X509Certificate signer, Instant claimed,
                                            AtomicReference<CommonCertificateVerifier> verifierRef) {
        if (signer == null) return;

        card.getChildren().add(row("Platnost certifikátu:",
                TimeFormat.full(signer.getNotBefore().toInstant())
                + " – " + TimeFormat.full(signer.getNotAfter().toInstant())));

        if (claimed != null) {
            boolean inside = !claimed.isBefore(signer.getNotBefore().toInstant())
                    && !claimed.isAfter(signer.getNotAfter().toInstant());
            card.getChildren().add(row("K deklarovanému času:",
                    inside ? "certifikát byl v době platnosti"
                           : "certifikát v té době neplatil"));
        }

        CertificateToken token = new CertificateToken(signer);
        try {
            var qc = QcStatementUtils.getQcStatements(token);
            boolean qualified = qc != null && qc.isQcCompliance();
            card.getChildren().add(row("Druh certifikátu:", qualified
                    ? "podle svých údajů kvalifikovaný (QcCompliance)"
                    : "neuvádí, že je kvalifikovaný"));
            // Podpis, nebo pečeť? Rozdíl je právní, ne technický: pečetí právnická osoba a
            // neuplatní se u ní domněnka vlastnoručního podpisu. Pozná se z QcType, a když
            // certifikát typ neuvádí, je poctivější mlčet než dosadit „podpis" jako výchozí.
            String kind = signatureKind(qc);
            if (kind != null) card.getChildren().add(row("Druh podpisu:", kind));
        } catch (Exception ignored) {
            // QcStatements se nepodařilo přečíst; radši mlčet než hádat.
        }

        CommonCertificateVerifier verifier = verifierRef != null ? verifierRef.get() : null;
        if (verifier != null && verifier.getTrustedCertSources() != null) {
            try {
                boolean trustedIssuer = !verifier.getTrustedCertSources()
                        .getBySubject(new eu.europa.esig.dss.model.x509.X500PrincipalHelper(
                                signer.getIssuerX500Principal())).isEmpty();
                card.getChildren().add(row("Vydavatel:", trustedIssuer
                        ? "je uveden v evropských důvěryhodných seznamech"
                        : "v evropských důvěryhodných seznamech nenalezen"));
            } catch (Exception ignored) {
                // Zásoba kotev nemusí být hotová; nepřítomnost odpovědi je lepší než špatná.
            }
        }

        card.getChildren().add(note(
                "Certifikační cesta ani stav revokace se nekontrolovaly: má to smysl teprve"
                + " u podpisu, o kterém je doloženo, co kryje."));
    }

    /**
     * Podpis, nebo pečeť — podle {@code QcType} v certifikátu. {@code null}, když certifikát typ
     * neuvádí; dosadit „podpis" jako výchozí by bylo tvrzení, které nikdo nenapsal.
     */
    static String signatureKind(eu.europa.esig.dss.model.x509.extension.QcStatements qc) {
        if (qc == null || qc.getQcTypes() == null) return null;
        for (var t : qc.getQcTypes()) {
            if (eu.europa.esig.dss.enumerations.QCTypeEnum.QCT_ESIGN.equals(t)) {
                return "elektronický podpis (certifikát je vydán fyzické osobě)";
            }
            if (eu.europa.esig.dss.enumerations.QCTypeEnum.QCT_ESEAL.equals(t)) {
                return "elektronická pečeť (certifikát je vydán organizaci)";
            }
            if (eu.europa.esig.dss.enumerations.QCTypeEnum.QCT_WEB.equals(t)) {
                return "certifikát je určen pro webové stránky, ne k podepisování dokumentů";
            }
        }
        return null;
    }

    /**
     * Razítka podpisu. Vypisují se všechna, protože u podpisu z uchovávací služby jich je víc
     * a rozdíl mezi podpisovým a archivním je pro čtenáře podstatný.
     *
     * <p>Konec platnosti certifikátu razítka je tu ten nejužitečnější údaj: <b>po tomhle datu
     * už razítko samo ověření neunese</b> a musí ho krýt další razítko nad ním. Bere se
     * z posledního razítka řetězu, protože horizont určuje poslední článek, ne první.
     */
    private static void addTimestampFacts(VBox card, Signature sig) {
        List<Timestamp> stamps = sig.timestamps();
        if (stamps.isEmpty()) {
            card.getChildren().add(row("Časové razítko:", "podpis žádné nenese"));
            card.getChildren().add(note(
                    "Bez razítka není doloženo, kdy podpis vznikl. Jakmile skončí platnost"
                    + " certifikátu podepsané osoby, nebude už co ověřit — deklarovaný čas je"
                    + " jen tvrzení podepisujícího programu."));
            return;
        }

        boolean many = stamps.size() > 1;
        for (Timestamp ts : stamps) {
            String label = many ? "Razítko (" + ts.kind().label() + "):" : "Časové razítko:";
            String who = ts.tsaName() != null ? shortName(ts.tsaName())
                    : ts.certSubject() != null ? shortName(ts.certSubject()) : null;
            card.getChildren().add(row(label,
                    (ts.time() != null ? TimeFormat.full(ts.time()) : "čas se nepodařilo přečíst")
                    + (who != null ? "  (" + who + ")" : "")));
            card.getChildren().add(row(many ? "    token:" : "Token razítka:", ts.intact()
                    ? "neporušený — podpis autority nad ním sedí"
                    : "nepodařilo se ověřit"));
        }

        Timestamp last = sig.lastTimestamp();
        if (last != null && last.certNotAfter() != null) {
            card.getChildren().add(row("Certifikát razítka platí do:",
                    TimeFormat.full(last.certNotAfter())
                    + (many ? "   (" + last.kind().label() + " razítko)" : "")));
            card.getChildren().add(note(horizonNote(last.certNotAfter())));
        }

        card.getChildren().add(note(
                "Razítko dokládá, že podpisová hodnota v uvedený čas existovala. Neříká nic"
                + " o tom, jaký obsah ta hodnota kryje, ani zda je autorita důvěryhodná —"
                + " to by vyžadovalo ověření celého podpisu."));
    }

    /**
     * Věta o horizontu. Záměrně nevydává termín za verdikt: říká, co se tím datem mění, ne že
     * „do té doby je podpis platný“. Rozlišuje minulost a budoucnost, protože u podpisu
     * z uchovávací služby bývá datum dávno za námi a věta v budoucím čase by mátla.
     */
    static String horizonNote(Instant certNotAfter) {
        boolean past = certNotAfter.isBefore(Instant.now());
        if (past) {
            return "Platnost certifikátu razítka už skončila. Samo o sobě to razítko"
                    + " nezneplatňuje — pokud je nad ním další razítko z doby, kdy certifikát"
                    + " ještě platil, řetěz drží dál. Není-li, bude ověření nadále vycházet"
                    + " nerozhodně a zpětně to už dodat nelze.";
        }
        return "Do tohoto data má ověření šanci dopadnout na základě tohoto razítka. Má-li podpis"
                + " zůstat ověřitelný i potom, musí se do té doby opatřit dalším razítkem"
                + " — u tohoto formátu to aplikace neumí, ale jde zafixovat celý soubor"
                + " do kontejneru s razítkem.";
    }

    /**
     * Co v souboru leží — třetí kategorie vedle „co podpis tvrdí“ a „co bylo ověřeno“.
     *
     * <p>Nejsou to tvrzení podepisujícího ani výsledky ověření, ale fakta o obsahu souboru.
     * U úrovně je to nutné říct nahlas: odečítá se ze struktury a <b>ověřením může vyjít
     * nižší</b>, protože vložený materiál nemusí být úplný ani se hodit na certifikáty v cestě.
     * Opačným směrem se to zmýlit nemůže — co v souboru není, ověřením nevznikne.
     */
    private static void addContentFacts(VBox card, Signature sig) {
        card.getChildren().add(sectionLabel("Co v souboru leží"));

        card.getChildren().add(row("Úroveň podle struktury:", sig.level().label()));
        card.getChildren().add(note(levelNote(sig.level())));

        List<Revocation> revs = sig.revocations();
        if (revs.isEmpty()) {
            card.getChildren().add(row("Revokační informace:", sig.hasRevocationValues()
                    ? "podpis je nese, ale nepodařilo se je přečíst"
                    : "žádné podpis nenese"));
            if (!sig.hasRevocationValues()) {
                card.getChildren().add(note(
                        "Bez vložených revokačních informací se stav certifikátu musí zjišťovat"
                        + " za běhu u vydavatele. Až jeho služba přestane odpovídat — typicky"
                        + " po skončení platnosti certifikátu — nebude už podpis čím doložit."));
            }
        } else {
            long crls = revs.stream().filter(r -> !r.ocsp()).count();
            long ocsps = revs.stream().filter(Revocation::ocsp).count();
            List<String> pieces = new ArrayList<>();
            // count() číslo předsazuje samo; kdyby bylo i v tvarech, vyjde „5 5 seznamů".
            if (crls > 0) pieces.add(CzechPlural.count((int) crls, "seznam odvolaných (CRL)",
                    "seznamy odvolaných (CRL)", "seznamů odvolaných (CRL)"));
            if (ocsps > 0) pieces.add(CzechPlural.count((int) ocsps, "odpověď OCSP",
                    "odpovědi OCSP", "odpovědí OCSP"));
            card.getChildren().add(row("Revokační informace:", String.join(", ", pieces)));
            for (Revocation r : revs) {
                card.getChildren().add(row("    " + r.kindLabel() + ":", revocationLine(r)));
            }
            card.getChildren().add(note(
                    "Podpis si revokační informace veze s sebou, takže stav certifikátů jde"
                    + " doložit i tehdy, když už služba vydavatele neodpovídá. Zda se hodí"
                    + " právě na certifikáty v této cestě a zda ověření projde, se pozná"
                    + " až ověřením."));
        }

        if (sig.hasPolicy()) {
            card.getChildren().add(row("Podpisová politika:",
                    "podpis se na nějakou odvolává"));
        }
    }

    /** Jeden řádek revokačního údaje: kdo a kdy. Vydavatel se zkracuje na CN, jako všude jinde. */
    private static String revocationLine(Revocation r) {
        StringBuilder sb = new StringBuilder();
        if (r.issuer() != null) sb.append(shortName(r.issuer()));
        if (r.produced() != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(r.ocsp() ? "vydáno " : "od ").append(TimeFormat.full(r.produced()));
        }
        if (r.nextUpdate() != null) {
            sb.append(", příští vydání ").append(TimeFormat.full(r.nextUpdate()));
        }
        return sb.length() > 0 ? sb.toString() : "bez čitelných údajů";
    }

    /**
     * Co ta úroveň pro čtenáře znamená — jednou větou a bez zkratek z normy.
     *
     * <p>Výhrada „ověřením by mohla vyjít nižší“ se připojuje jen tam, kde má z čeho klesnout.
     * U B a XMLDSIG je úroveň daná tím, co v souboru <b>chybí</b>, a chybějící prvek ověřením
     * nevznikne — psát tam výhradu o neúplném vloženém materiálu by bylo matoucí, protože žádný
     * vložený materiál tam není.
     */
    static String levelNote(OfficeSignaturePeek.Level level) {
        return switch (level) {
            case XMLDSIG -> "Podpis nemá vlastnosti XAdES, tedy ani údaj o podepisujícím"
                    + " certifikátu v podepsané části. Je to holý XML podpis, na který se"
                    + " pravidla pro elektronické podpisy podle nařízení eIDAS nedají"
                    + " použít v plném rozsahu.";
            case B -> "Podpis nenese časové razítko ani revokační informace. Ověřitelný bude jen"
                    + " tak dlouho, dokud platí certifikát podepsané osoby a odpovídá služba"
                    + " jejího vydavatele.";
            case T -> "Podpis nese časové razítko, takže je doloženo, kdy vznikl. Nenese ale"
                    + " revokační informace — ty se musí zjišťovat za běhu u vydavatele."
                    + " Úroveň je odečtená z toho, co v souboru leží; ověřením by mohla vyjít"
                    + " nižší, kdyby se razítko ověřit nepodařilo.";
            case LT -> "Podpis nese certifikáty i revokační informace, takže ho lze ověřit i po"
                    + " skončení platnosti certifikátu podepsané osoby. Horizont určuje"
                    + " certifikát časového razítka."
                    + " Úroveň je odečtená z toho, co v souboru leží; ověřením by mohla vyjít"
                    + " nižší, protože vložený materiál nemusí být úplný ani se hodit na"
                    + " certifikáty v této cestě.";
            case LTA -> "Podpis nese archivní razítko přes celý svůj obsah včetně vloženého"
                    + " materiálu. To je podoba určená k dlouhodobému uchování; horizont se"
                    + " posouvá dalším archivním razítkem."
                    + " Úroveň je odečtená z toho, co v souboru leží; ověřením by mohla vyjít"
                    + " nižší, protože vložený materiál nemusí být úplný ani se hodit na"
                    + " certifikáty v této cestě.";
        };
    }

    private static void addAlgorithmFacts(VBox card, Signature sig) {
        List<String> algs = new ArrayList<>(sig.digestAlgorithms());
        if (sig.signatureAlgorithm() != null) algs.add(sig.signatureAlgorithm());
        if (algs.isEmpty()) return;
        card.getChildren().add(row("Použité algoritmy:", String.join(", ", algs)));
        if (sig.usesSha1()) {
            card.getChildren().add(note(
                    "Podpis stojí na algoritmu SHA-1, který je pro elektronické podpisy"
                    + " považován za zastaralý. I kdyby aplikace tento formát ověřovat uměla,"
                    + " skončil by stav platnosti jako neznámý — a stejně dopadne u každého"
                    + " validátoru, který se drží standardů."));
        }
    }

    // ── Stavební prvky ───────────────────────────────────────────────────────────────────────

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #003189;"
                + " -fx-padding: 8 0 2 0;");
        return l;
    }

    private static HBox row(String label, String value) {
        Label l = new Label(label);
        l.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111;");
        l.setMinWidth(150);
        l.setPrefWidth(150);
        Label v = new Label(value != null ? value : "—");
        // Barva napevno, ne zděděná. Hodnoty byly jediné popisky bez vlastní barvy — všechny
        // ostatní si ji v tomhle okně nastavují — a v aplikaci vyšly tak světlé, že je nebylo
        // vidět; při sejmutí mimo aplikaci přitom vyšly tmavě. Dědit se tu tedy nedá na co.
        v.setStyle("-fx-text-fill: #111111;");
        v.setWrapText(true);
        HBox.setHgrow(v, Priority.ALWAYS);
        HBox row = new HBox(6, l, v);
        row.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        return row;
    }

    private static Label paragraph(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #444444;");
        wrap(l);
        return l;
    }

    private static Label note(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: #7a4a00; -fx-padding: 2 0 4 0;");
        wrap(l);
        return l;
    }

    /**
     * Podpisový poskytovatel čitelně, nebo vůbec.
     *
     * <p>Office ho zapisuje jako GUID a u vlastního podpisového rozhraní je to samá nula —
     * řádek „Podepsáno programem: {00000000-…}" neřekne čtenáři nic, jen zabere místo. Nulový
     * GUID se proto vynechá a cizí se ukáže tak, jak je: pojmenovat všechny GUIDy, které kdo kdy
     * vydal, nejde, ale ten, kdo jeden zná, si ho v něm najde.
     */
    private static String readableProvider(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        if (v.replaceAll("[{}0-]", "").isEmpty()) return null;   // samé nuly = žádný poskytovatel
        return v;
    }

    /** Z DN razítkovací autority jen CN — celé DN v řádku nikdo nepřečte. */
    private static String shortName(String dn) {
        String cn = DnUtil.dnField(dn, "2.5.4.3", "CN");
        return cn != null && !cn.isBlank() ? cn : dn;
    }

    private static void wrap(Label l) {
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
    }
}
