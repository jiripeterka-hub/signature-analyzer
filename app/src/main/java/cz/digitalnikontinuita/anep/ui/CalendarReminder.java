package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.TimeFormat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Připomínka do kalendáře ve formátu iCalendar (RFC 5545) — k záznamu o úkonu, aby si uživatel
 * nemusel datum, do kdy je dokument ověřitelný, přepisovat ručně. Pokyn uživatele z 4. 10. 2026.
 * Od 5. 10. 2026 také k nabídce úkonu na záložce Digitální kontinuita, je-li v ní konkrétní lhůta.
 *
 * <p><b>Celodenní událost v den „termín minus předstih“</b>, ne událost v den termínu
 * s upozorněním o N dní dřív. Upozornění (VALARM) s předstihem několika týdnů klienti
 * nezpracovávají stejně — některé ho při importu zkrátí nebo zahodí —, kdežto událost
 * v konkrétní den se v kalendáři ukáže vždy. Upozornění je k ní přidané jen na její začátek.
 *
 * <p>Den se počítá v pražském čase, stejně jako data v záznamu. Připadl-li by do minulosti
 * (lhůta kratší než předstih), posune se na dnešek: připomínka do minulosti nikoho nepřipomene.
 */
final class CalendarReminder {

    /** Předstihy, které okno nabízí, ve dnech. */
    static final List<Integer> LEADS = List.of(1, 5, 10, 30);

    private static final ZoneId PRAGUE = ZoneId.of("Europe/Prague");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private CalendarReminder() { }

    /**
     * Řádek „Připomínka do kalendáře“: výběr předstihu a tlačítko, které uloží soubor .ics.
     * Sdílí ho okno záznamu o úkonu i poslední blok záložky Digitální kontinuita, aby obě místa
     * nabízela totéž (pokyn uživatele 5. 10. 2026: „se stejnou nabídkou časových odstupů“).
     *
     * @param suggestedName  jméno souboru, které nabídne dialog
     * @param content        obsah souboru .ics pro zvolený předstih ve dnech
     */
    static javafx.scene.layout.HBox row(String suggestedName,
                                        java.util.function.IntFunction<String> content) {
        javafx.scene.control.Label label =
                new javafx.scene.control.Label("Připomínka do kalendáře:");
        // Barva výslovně, jako u odstavců záložky (AdvisorView.continuityText): v bloku
        // Digitální kontinuity se jinak zdědila bílá a popisek nebyl vidět (nález uživatele
        // 5. 10. 2026).
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: #111111;");
        javafx.scene.control.ComboBox<Integer> lead = new javafx.scene.control.ComboBox<>(
                javafx.collections.FXCollections.observableArrayList(LEADS));
        lead.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Integer n) {
                return n == null ? "" : n + (n == 1 ? " den" : n < 5 ? " dny" : " dní") + " předem";
            }
            @Override public Integer fromString(String s) { return null; }
        });
        lead.setValue(30);
        javafx.scene.control.Button save =
                new javafx.scene.control.Button("Uložit připomínku (.ics)…");
        save.setOnAction(e -> {
            Integer days = lead.getValue();
            if (days == null) return;
            javafx.stage.Window owner = save.getScene() != null ? save.getScene().getWindow()
                    : null;
            java.io.File file = target.apply(owner, suggestedName);
            if (file == null) return;
            try {
                java.nio.file.Files.writeString(file.toPath(), content.apply(days),
                        StandardCharsets.UTF_8);
            } catch (Exception ex) {
                PrintUtil.showExportFailure(owner, ex, file);
            }
        });
        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(10, label, lead, save);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Kam připomínku uložit — dialog pro výběr souboru. Vyčleněno kvůli testu, který tlačítko
     * stiskne skutečně ({@code fire()}) a modální dialog by ho zastavil.
     */
    static java.util.function.BiFunction<javafx.stage.Window, String, java.io.File> target =
            (owner, name) -> {
                javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
                chooser.setTitle("Uložit připomínku do kalendáře");
                chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                        "Kalendář iCalendar (*.ics)", "*.ics"));
                chooser.setInitialFileName(name);
                return chooser.showSaveDialog(owner);
            };

    /** Den připomínky: termín minus předstih, nejdříve dnešek. */
    static LocalDate reminderDay(Instant deadline, int leadDays, Instant now) {
        LocalDate day = deadline.atZone(PRAGUE).toLocalDate().minusDays(leadDays);
        LocalDate today = now.atZone(PRAGUE).toLocalDate();
        return day.isBefore(today) ? today : day;
    }

    /**
     * Připomínka k záznamu o úkonu: úkon proběhl, dokument je ověřitelný do {@code deadline}
     * a před tím termínem je třeba ověřitelnost znovu prodloužit.
     *
     * @param action    jméno úkonu („Uchování“, …), do textu připomínky
     * @param file      výsledný soubor, o kterém připomínka je; smí být {@code null}
     * @param deadline  dokdy je dokument ověřitelný
     */
    static String afterAction(String action, java.io.File file, Instant deadline, int leadDays,
                              Instant now) {
        String name = file != null ? file.getName() : "dokument";
        String description = action + " v AnEP " + TimeFormat.full(now) + ". Dokument "
                + name + " je podle záznamu o úkonu ověřitelný do " + TimeFormat.full(deadline)
                + ". Před tímto termínem je třeba ho znovu otevřít v AnEP a ověřitelnost"
                + " prodloužit (záložka Digitální kontinuita)." + location(file);
        return ics("Prodloužit ověřitelnost: " + name, description, deadline, leadDays, now);
    }

    /**
     * Připomínka k nabídce na záložce Digitální kontinuita: úkon zatím neproběhl a stihnout ho je
     * třeba do {@code deadline} — data z nadpisu posledního bloku (pokyn uživatele 5. 10. 2026).
     *
     * @param verb      sloveso z tlačítka úkonu („Uchovat“, …)
     * @param noun      jméno úkonu doprostřed věty („uchování“, …)
     * @param file      analyzovaný dokument; smí být {@code null}
     */
    static String beforeDeadline(String verb, String noun, java.io.File file, Instant deadline,
                                 int leadDays, Instant now) {
        String name = file != null ? file.getName() : "dokument";
        String description = "Podle analýzy v AnEP z " + TimeFormat.full(now) + " nabízí"
                + " aplikace pro dokument " + name + " možnost " + noun + ", a to nejpozději do "
                + TimeFormat.full(deadline) + ". Před tímto termínem je třeba dokument otevřít"
                + " v AnEP a úkon provést tlačítkem „" + verb + "“ (záložka Digitální"
                + " kontinuita)." + location(file);
        return ics(verb + " dokument: " + name, description, deadline, leadDays, now);
    }

    /**
     * Kde soubor leží — plná cesta na samostatném řádku popisu (pokyn uživatele 5. 10. 2026).
     * Jméno samo nestačí: soubory téhož jména bývají v několika složkách a připomínka přichází
     * za měsíce či roky, kdy už nikdo neví, ve které.
     */
    private static String location(java.io.File file) {
        // Holé jméno (záznam bez cesty) by se tu doplnilo o pracovní složku aplikace — cestu,
        // kde soubor není.
        if (file == null || !file.isAbsolute()) return "";
        java.io.File abs = file;
        String folder = abs.getParent();
        return "\n\nUmístění souboru: " + abs.getPath()
                + (folder != null ? "\nSložka: " + folder : "");
    }

    /** Obsah souboru .ics: celodenní událost v den „termín minus předstih“ s upozorněním. */
    static String ics(String summary, String description, Instant deadline, int leadDays,
                      Instant now) {
        LocalDate day = reminderDay(deadline, leadDays, now);
        StringBuilder sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR");
        line(sb, "VERSION:2.0");
        line(sb, "PRODID:-//AnEP//Pripominka digitalni kontinuity//CS");
        line(sb, "CALSCALE:GREGORIAN");
        line(sb, "METHOD:PUBLISH");
        line(sb, "BEGIN:VEVENT");
        line(sb, "UID:" + UUID.randomUUID() + "@anep");
        line(sb, "DTSTAMP:" + STAMP.format(now));
        line(sb, "DTSTART;VALUE=DATE:" + DAY.format(day));
        line(sb, "DTEND;VALUE=DATE:" + DAY.format(day.plusDays(1)));
        line(sb, "SUMMARY:" + escape(summary));
        line(sb, "DESCRIPTION:" + escape(description));
        line(sb, "TRANSP:TRANSPARENT");
        line(sb, "BEGIN:VALARM");
        line(sb, "ACTION:DISPLAY");
        line(sb, "DESCRIPTION:" + escape(summary));
        line(sb, "TRIGGER;RELATED=START:PT0S");
        line(sb, "END:VALARM");
        line(sb, "END:VEVENT");
        line(sb, "END:VCALENDAR");
        return sb.toString();
    }

    /** Jméno souboru s připomínkou: jméno výsledného souboru bez přípony a „.ics“. */
    static String fileName(String outputName) {
        if (outputName == null || outputName.isBlank()) return "pripominka.ics";
        int dot = outputName.lastIndexOf('.');
        return (dot > 0 ? outputName.substring(0, dot) : outputName) + "_pripominka.ics";
    }

    /** Text podle RFC 5545, 3.3.11: zpětné lomítko, středník, čárka a konec řádku. */
    static String escape(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n");
    }

    /**
     * Řádek ukončený CRLF a zalomený po 75 oktetech (RFC 5545, 3.1). Zalamuje se po bajtech
     * UTF-8, ne po znacích — česká písmena mají dva bajty a znak se rozdělit nesmí.
     */
    private static void line(StringBuilder sb, String content) {
        int octets = 0;
        int limit = 75;
        for (int i = 0; i < content.length(); ) {
            int cp = content.codePointAt(i);
            int len = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (octets + len > limit) {
                sb.append("\r\n ");
                octets = 1;
            }
            sb.appendCodePoint(cp);
            octets += len;
            i += Character.charCount(cp);
        }
        sb.append("\r\n");
    }
}
