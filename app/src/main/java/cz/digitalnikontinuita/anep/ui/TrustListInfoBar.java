package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.TrustConfig;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.Collections;
import java.util.List;

/**
 * Trusted-List status header shown above the cards of both "Rozšířený přehled" and "Analýza".
 *
 * <p>Holds the state each view receives from {@code TrustConfig} and renders it identically in
 * both places — the wording is a statement about the validation run as a whole, so the two tabs
 * must never drift apart.
 */
class TrustListInfoBar {

    /** Amber of the stale-list warning: the same tone the names used to be printed in. */
    private static final String STALE_COLOR = "#8a6d00";

    /** A missing list is a different order of trouble from a stale one, so a different colour. */
    private static final String MISSING_COLOR = "#b00020";

    /** Kdy AnEP seznamy naposledy porovnal se zdrojem — {@link TrustConfig#getLastSourceCheckTime()}. */
    private long sourceCheckMs = -1;
    private boolean refreshInProgress = false;
    /** Naposledy vydaný národní seznam; text za ikonou ⓘ na hlavním řádku, nebo {@code null}. */
    private TrustConfig.TrustListVersion latestList;
    private List<TrustConfig.StaleTrustList> staleTrustLists = Collections.emptyList();
    private List<String> emptyTerritories = Collections.emptyList();

    /**
     * Uzel, který si karta jednou vloží do stromu a pak už s ním nehýbe; obsah se do něj
     * překresluje.
     *
     * <p>Dřív se pruh stavěl nanovo při každém {@code show(result)} a jinak nikdy — takže obnova
     * seznamů v Nastavení stav opravila, ale varování „Chybějící důvěryhodné seznamy" viselo
     * v hlavičce dál až do dalšího ověření. Uživateli to přirozeně vypadalo, že pokyn k načtení
     * nic neudělal, i když udělal. Trvalý uzel je jediný způsob, jak jde překreslit i mimo
     * vykreslování celé karty.
     */
    private final VBox host = new VBox();

    /** Uzel k vložení do karty. Vkládá se vždycky; když není co říct, nezabírá místo. */
    VBox node() {
        return host;
    }

    void set(long sourceCheckMs, boolean refreshInProgress,
             TrustConfig.TrustListVersion latestList,
             List<TrustConfig.StaleTrustList> staleTrustLists,
             List<String> emptyTerritories) {
        this.sourceCheckMs = sourceCheckMs;
        this.refreshInProgress = refreshInProgress;
        this.latestList = latestList;
        this.staleTrustLists = staleTrustLists != null ? staleTrustLists : Collections.emptyList();
        this.emptyTerritories = emptyTerritories != null ? emptyTerritories : Collections.emptyList();
        render();
    }

    /** Whether there is anything to show at all — no state means no header row. */
    boolean hasContent() {
        return sourceCheckMs > 0 || refreshInProgress
                || !staleTrustLists.isEmpty() || !emptyTerritories.isEmpty();
    }

    /**
     * Přenese současný stav do trvalého uzlu.
     *
     * <p>Prázdný pruh se schovává přes {@code setManaged(false)}, ne jen {@code setVisible(false)}:
     * neviditelný, ale spravovaný uzel by si nad kartou pořád držel svou výšku i odsazení.
     */
    void render() {
        host.getChildren().setAll(build().getChildren());
        boolean show = hasContent();
        host.setPadding(show ? new Insets(0, 0, 10, 0) : Insets.EMPTY);
        host.setVisible(show);
        host.setManaged(show);
    }

    /**
     * One line: what the Trusted Lists are doing, and — when some of them could not be confirmed —
     * a warning icon right behind it that opens the particulars.
     *
     * <p>The icon and the sentence share a {@link TextFlow} rather than an {@code HBox} so the
     * icon follows the last word even when the sentence wraps; a wrapping {@code Label} beside an
     * icon in an HBox centres the icon against the whole block instead.
     */
    private VBox build() {
        VBox bar = new VBox(2);
        boolean hasRefresh = sourceCheckMs > 0 || refreshInProgress;
        if (!hasRefresh && staleTrustLists.isEmpty() && emptyTerritories.isEmpty()) return bar;

        Text message = new Text(hasRefresh ? refreshText() : "Důvěryhodné seznamy EU");
        message.setStyle("-fx-font-size: 11px; -fx-fill: #555555;");
        TextFlow flow = new TextFlow(message);
        String latest = latestListText(latestList);
        if (latest != null) {
            flow.getChildren().add(clickInfoIcon(latest));
        }
        Label icon = staleWarningIcon(staleTrustLists);
        if (icon != null) {
            flow.getChildren().add(icon);
        }
        // Za zastaralými, protože chybějící seznam je vážnější a má zůstat poslední na řádku,
        // tedy nejblíž tomu, co uživatel čte naposled.
        Label missing = missingWarningIcon(emptyTerritories);
        if (missing != null) {
            flow.getChildren().add(missing);
        }
        bar.getChildren().add(flow);
        return bar;
    }

    /**
     * Stands in for the list of national Trusted Lists that could not be confirmed against their
     * source for longer than the threshold, or {@code null} when they are all current. Their
     * cached copies are still used, so the caller has nothing to report beyond "there is something
     * here to look at" — the territories, addresses and dates used to be spelled out inline in
     * small amber type, which gave a permanent line of detail to a state that is informational and
     * rarely acted on. They are one click away.
     *
     * <p>Static and public to the package because three places say this now — both tabs' headers
     * and the main window's status line — and the glyph, the colour and the wording have to be the
     * same statement in all of them.
     */
    static Label staleWarningIcon(List<TrustConfig.StaleTrustList> stale) {
        if (stale == null || stale.isEmpty()) return null;
        Label icon = new Label("⚠");
        icon.setStyle("-fx-font-size: 12px; -fx-text-fill: " + STALE_COLOR
                + "; -fx-cursor: hand; -fx-padding: 0 0 0 6;");
        Tooltip tip = new Tooltip(staleDetailText(stale));
        tip.setWrapText(true);
        tip.setMaxWidth(560);
        Tooltip.install(icon, tip);
        icon.setOnMouseClicked(e -> tip.show(icon, e.getScreenX() + 12, e.getScreenY() + 12));
        return icon;
    }

    /**
     * Stands in for the countries whose Trusted List the state knows about but from which not one
     * certificate arrived, or {@code null} when every list contributed.
     *
     * <p>Separate from the stale-list warning, and louder, because the two say different things. A
     * stale list is still used and still works; validation is merely running on a copy that may
     * have missed a change. A list that contributed nothing is not being used at all, and a
     * signature issued in that country cannot come out qualified — there is no chain to build. The
     * reader sees only the downgraded verdict, which looks exactly like a signature that never was
     * qualified, so nothing but this line can tell the two apart.
     */
    static Label missingWarningIcon(List<String> territories) {
        if (territories == null || territories.isEmpty()) return null;
        Label icon = new Label("⚠");
        icon.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MISSING_COLOR
                + "; -fx-cursor: hand; -fx-padding: 0 0 0 6;");
        Tooltip tip = new Tooltip(missingDetailText(territories));
        tip.setWrapText(true);
        tip.setMaxWidth(560);
        Tooltip.install(icon, tip);
        icon.setOnMouseClicked(e -> tip.show(icon, e.getScreenX() + 12, e.getScreenY() + 12));
        return icon;
    }

    private static String missingDetailText(List<String> territories) {
        return "Chybějící důvěryhodné seznamy: " + String.join(", ", territories) + "\n\n"
                + "Seznam těchto zemí je sice v uloženém stavu důvěry veden, ale nepřišel z něj"
                + " jediný certifikát — nejspíš se ho při poslední aktualizaci nepodařilo stáhnout"
                + " nebo ověřit.\n\n"
                + "Důsledek: podpis nebo pečeť vydaná v takové zemi se neověří jako kvalifikovaná."
                + " Nelze totiž postavit řetěz k důvěryhodné kotvě, takže výsledek vyjde jako"
                + " pouze zaručený — a od podpisu, který kvalifikovaný nikdy nebyl, se to v samotném"
                + " výsledku nepozná.\n\n"
                + "Napraví to stažení seznamů: v nastavení zvolte obnovení, nebo aplikaci spusťte"
                + " znovu — o aktualizaci se pokouší při každém spuštění.";
    }

    /**
     * The particulars behind the icon. Opens with the summary the visible label used to carry, so
     * that moving it out of sight loses nothing.
     */
    private static String staleDetailText(List<TrustConfig.StaleTrustList> staleTrustLists) {
        String names = staleTrustLists.stream()
                .map(s -> (s.territory() != null ? s.territory() : shortHost(s.url()))
                        + " (" + s.days() + " dní)")
                .collect(java.util.stream.Collectors.joining(", "));
        StringBuilder tip = new StringBuilder();
        tip.append("Neověřené důvěryhodné seznamy: ").append(names).append("\n\n");
        tip.append("Tyto národní seznamy důvěryhodných služeb se nepodařilo stáhnout déle než ")
           .append(TrustConfig.STALE_TL_WARNING_DAYS)
           .append(" dní. Ověřování používá jejich poslední uloženou kopii, takže výsledek"
                 + " zůstává platný — ale případné změny (nový či odebraný poskytovatel,"
                 + " změna kvalifikovaného statusu) se v něm nemusí projevit.\n\n"
                 + "AnEP zkouší seznamy stáhnout znovu při každém spuštění.\n");
        for (TrustConfig.StaleTrustList s : staleTrustLists) {
            tip.append('\n').append(s.territory() != null ? s.territory() + " — " : "")
               .append(s.url())
               .append("\n    poslední úspěšné ověření: ")
               .append(TimeUtil.format(s.lastCheckedMillis()));
        }
        return tip.toString();
    }

    /** Host part of a TL URL, used as a fallback label when the country code is unknown. */
    private static String shortHost(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Ikona ⓘ za větou o seznamech, která text ukáže jen na kliknutí. Do 16. 10. 2026 visela
     * bublina přímo na větě a otvírala se najetím myší; uživatel chtěl ikonu, na kterou se
     * klikne. Vzhledem odpovídá {@code ExtendedView.infoIcon}, ale bez {@code Tooltip.install}
     * — ta by bublinu otvírala i najetím.
     */
    private static Label clickInfoIcon(String text) {
        Label icon = new Label("ⓘ");
        icon.setStyle("-fx-font-size: 12px; -fx-text-fill: #003189; -fx-cursor: hand;"
                + " -fx-padding: 0 0 0 6;");
        Tooltip tip = new Tooltip(text);
        tip.setWrapText(true);
        tip.setMaxWidth(560);
        tip.setShowDuration(javafx.util.Duration.INDEFINITE);
        tip.setStyle("-fx-font-size: 11px; -fx-background-color: #ffffff; -fx-text-fill: #111111;"
                + " -fx-border-color: #cccccc; -fx-border-width: 1;");
        // Zavře ji klik kamkoli jinam i druhý klik na ikonu — viz ExtendedView.infoIcon: autoHide
        // reaguje na stisk dřív než tento handler, takže bez té pojistky by se znovu otevřela.
        tip.setAutoHide(true);
        long[] lastHidden = {0};
        tip.setOnHidden(e -> lastHidden[0] = System.currentTimeMillis());
        icon.setOnMouseClicked(e -> {
            if (tip.isShowing()) { tip.hide(); return; }
            if (System.currentTimeMillis() - lastHidden[0] < 250) return;
            tip.show(icon, e.getScreenX() + 12, e.getScreenY() + 12);
        });
        return icon;
    }

    /**
     * Obsah bubliny za ikonou ⓘ: jen naposledy vydaný národní seznam, ne výčet všech zemí
     * (pokyn uživatele 16. 10. 2026). Hlavní řádek říká, kdy AnEP kopie porovnal se zdrojem;
     * tohle je údaj ze seznamu samotného — a bez té druhé věty by se obě data snadno spletla.
     * Statické, aby totéž znění mohl použít i stavový řádek hlavního okna.
     */
    static String latestListText(TrustConfig.TrustListVersion v) {
        if (v == null || v.issueDate() == null) return null;
        StringBuilder sb = new StringBuilder("Naposledy vydaný národní seznam: ");
        sb.append(v.territory() != null && !v.territory().isBlank() ? v.territory() : "neznámá země");
        if (v.sequenceNumber() != null) sb.append(", pořadové číslo ").append(v.sequenceNumber());
        sb.append(", vydán ").append(TimeUtil.format(v.issueDate())).append(".\n\n");
        // Kontrola běží jen při spuštění (TrustConfig.buildLotlCertificateVerifier), za běhu se
        // neopakuje — „každých 12 hodin“ by u aplikace otevřené přes noc neplatilo.
        sb.append("Čas v hlavičce říká, kdy AnEP své kopie seznamů naposledy porovnal se zdrojem"
                + " (dělá to při spuštění aplikace, nejvýš jednou za "
                + TrustConfig.TL_CHECK_MIN_INTERVAL_HOURS + " hodin);"
                + " toto je datum aktualizace příslušného národního seznamu.");
        return sb.toString();
    }

    /**
     * Do 16. 10. 2026 tu stálo „poslední síťová aktualizace“ s časem posledního úplného stažení.
     * To se ale děje jen tehdy, když kontrola najde novější seznam, a čtenář to četl jako datum
     * aktualizace seznamů samotných. Teď je tu čas posledního porovnání se zdrojem a datum vydání
     * nese bublina ({@link #latestListText}).
     */
    private String refreshText() {
        if (sourceCheckMs > 0 && refreshInProgress) {
            return "Důvěryhodné seznamy EU: naposledy ověřeny u zdroje "
                    + TimeUtil.format(sourceCheckMs)
                    + "  —  probíhá stahování aktuální verze na pozadí";
        }
        if (sourceCheckMs > 0) {
            return "Důvěryhodné seznamy EU: naposledy ověřeny u zdroje "
                    + TimeUtil.format(sourceCheckMs);
        }
        return "Důvěryhodné seznamy EU: probíhá stahování aktuální verze na pozadí";
    }
}
