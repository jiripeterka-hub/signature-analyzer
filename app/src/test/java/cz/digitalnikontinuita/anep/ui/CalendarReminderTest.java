package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.TsaProfile;

import javafx.application.Platform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Připomínka do kalendáře k záznamu o úkonu (soubor .ics). */
class CalendarReminderTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2029-03-06T10:00:00Z");

    @Test
    @DisplayName("den připomínky je termín minus předstih, nejdříve dnešek")
    void reminderDay() {
        assertEquals(LocalDate.of(2029, 2, 4), CalendarReminder.reminderDay(DEADLINE, 30, NOW));
        assertEquals(LocalDate.of(2029, 3, 5), CalendarReminder.reminderDay(DEADLINE, 1, NOW));
        Instant soon = Instant.parse("2026-10-10T10:00:00Z");
        assertEquals(LocalDate.of(2026, 10, 4), CalendarReminder.reminderDay(soon, 30, NOW));
    }

    @Test
    @DisplayName("soubor je platný iCalendar: CRLF, řádky do 75 oktetů, celodenní událost")
    void icsIsWellFormed() {
        String ics = CalendarReminder.afterAction("Uchování", new File("smlouva; verze, 2.pdf"),
                DEADLINE, 10, NOW);
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"), ics);
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"), ics);
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20290224\r\n"), ics);
        assertTrue(ics.contains("DTEND;VALUE=DATE:20290225\r\n"), ics);
        assertTrue(ics.contains("BEGIN:VALARM"), ics);
        for (String line : ics.split("\r\n")) {
            assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 75, "dlouhý řádek: " + line);
        }
        assertTrue(!ics.replace("\r\n", "").contains("\n"), "osamělé LF");
        String unfolded = ics.replace("\r\n ", "");
        assertTrue(unfolded.contains("smlouva\\; verze\\, 2.pdf"), unfolded);
    }

    @Test
    @DisplayName("obě připomínky nesou plnou cestu k souboru a jeho složku")
    void icsCarriesThePath() {
        File file = new File(System.getProperty("java.io.tmpdir"), "smlouva.pdf")
                .getAbsoluteFile();
        for (String ics : List.of(
                CalendarReminder.afterAction("Uchování", file, DEADLINE, 10, NOW),
                CalendarReminder.beforeDeadline("Uchovat", "uchování", file, DEADLINE, 10,
                        NOW))) {
            String unfolded = ics.replace("\r\n ", "");
            assertTrue(unfolded.contains("Umístění souboru: "
                    + CalendarReminder.escape(file.getPath())), unfolded);
            assertTrue(unfolded.contains("Složka: "
                    + CalendarReminder.escape(file.getParent())), unfolded);
        }
        // Holé jméno bez cesty: složku si nedomýšlet z pracovního adresáře aplikace.
        String bare = CalendarReminder.afterAction("Uchování", new File("smlouva.pdf"),
                DEADLINE, 10, NOW);
        assertTrue(!bare.contains("Umístění souboru"), bare);
    }

    @Test
    @DisplayName("jméno souboru: jméno výsledku bez přípony a _pripominka.ics")
    void fileName() {
        assertEquals("smlouva_Uchovano_do_06_03_2029_pripominka.ics",
                CalendarReminder.fileName("smlouva_Uchovano_do_06_03_2029.pdf"));
        assertEquals("pripominka.ics", CalendarReminder.fileName(null));
    }

    @Test
    @DisplayName("tlačítko v okně záznamu po stisku uloží připomínku s vybraným předstihem")
    void buttonWritesFile() throws Exception {
        Assumptions.assumeTrue(FxToolkit.available(), "JavaFX toolkit není k dispozici");
        ActionRecord r = new ActionRecord(AdvisorView.ContinuityMode.PRESERVE, "Zkušební TSA",
                TsaProfile.Quality.QUALIFIED, List.of(), null);
        r.after = List.of();
        r.afterDocUntil = DEADLINE;
        File output = new File(System.getProperty("java.io.tmpdir"), "smlouva_Uchovano.pdf")
                .getAbsoluteFile();
        r.output = new ActionRecord.FileFact("smlouva_Uchovano.pdf", 1, "aa".repeat(32),
                output.getPath());
        File target = File.createTempFile("anep-pripominka-", ".ics");
        var previous = CalendarReminder.target;
        CalendarReminder.target = (owner, name) -> target;
        try {
            String outcome = onFx(() -> {
                javafx.stage.Stage w = ActionRecordWindow.show(null, r, null);
                try {
                    javafx.scene.control.ComboBox<?> lead = find(w.getScene().getRoot(),
                            javafx.scene.control.ComboBox.class);
                    javafx.scene.control.Button b = findButton(w.getScene().getRoot(),
                            "Uložit připomínku (.ics)…");
                    if (lead == null || b == null) return "ovládací prvky nenalezeny";
                    @SuppressWarnings("unchecked")
                    javafx.scene.control.ComboBox<Integer> l =
                            (javafx.scene.control.ComboBox<Integer>) lead;
                    l.setValue(5);
                    b.fire();
                    return "stisknuto";
                } finally {
                    w.close();
                }
            });
            assertEquals("stisknuto", outcome);
            String ics = Files.readString(target.toPath(), StandardCharsets.UTF_8);
            // 6. 3. 2029 minus 5 dní
            assertTrue(ics.contains("DTSTART;VALUE=DATE:20290301"), ics);
            assertTrue(ics.replace("\r\n ", "").contains(
                    CalendarReminder.escape(output.getPath())), ics);
        } finally {
            CalendarReminder.target = previous;
            Files.deleteIfExists(target.toPath());
        }
    }

    private static <T> T find(javafx.scene.Node node, Class<T> type) {
        if (type.isInstance(node)) return type.cast(node);
        if (node instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node c : p.getChildrenUnmodifiable()) {
                T found = find(c, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static javafx.scene.control.Button findButton(javafx.scene.Node node, String text) {
        if (node instanceof javafx.scene.control.Button b && text.equals(b.getText())) return b;
        if (node instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node c : p.getChildrenUnmodifiable()) {
                javafx.scene.control.Button found = findButton(c, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private interface FxCall<T> { T call() throws Exception; }

    private static <T> T onFx(FxCall<T> call) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                value.set(call.call());
            } catch (Throwable t) {
                failure.set(t);
            }
            done.countDown();
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "vlákno JavaFX neodpovědělo");
        if (failure.get() != null) throw new AssertionError(failure.get());
        return value.get();
    }
}
