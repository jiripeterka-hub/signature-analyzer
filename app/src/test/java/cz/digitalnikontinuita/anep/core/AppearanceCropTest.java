package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Výřez vizualizace podpisu na otočené a ořezané stránce.
 *
 * <p>Čísla nejsou spočítaná od stolu: pocházejí z měření na
 * mapovém podkladu s otočenou stránkou — A4 na výšku, {@code /Rotate 270},
 * podpisové pole {@code [10,10,60,110]} v pravém dolním rohu mapy. Ten dokument je mezi 5 592
 * podepsanými PDF korpusu <b>jediný</b> s otočenou stránkou, takže tahle třída je jediné místo,
 * kde se ty tři větve vůbec spustí. Zbylá natočení jsou změřená na jeho kopiích přeuložených
 * s jiným {@code /Rotate}.
 */
class AppearanceCropTest {

    /** A4 na výšku, počátek v nule — jak to má stránka toho dokumentu. */
    private static final PDRectangle A4 = new PDRectangle(0, 0, 595, 842);
    /** Podpisové pole v pravém dolním rohu po natočení: 50 × 100 v souřadnicích dokumentu. */
    private static final PDRectangle FIELD = new PDRectangle(10, 10, 50, 100);
    private static final float SCALE = 2f;

    @Test
    @DisplayName("bez natočení se výřez počítá od horního okraje")
    void noRotation() {
        assertArrayEquals(new int[]{20, 1464, 100, 200},
                PdfSignatureAppearanceExtractor.computeCrop(A4, FIELD, 0, SCALE));
    }

    /**
     * Tady byla chyba: svislá souřadnice u 90° a 270° byla prohozená, takže výřez seděl u opačného
     * okraje obrazu a místo vizualizace ukázal kus dokumentu pod ní. Vodorovná souřadnice i obě
     * velikosti byly správně, takže výřez měl správný tvar, nebyl prázdný a prošel dál.
     */
    @Test
    @DisplayName("při 90° a 270° leží výřez u dolního, resp. horního okraje — ne obráceně")
    void rotatedQuarterTurns() {
        assertAll(
                () -> assertArrayEquals(new int[]{20, 20, 200, 100},
                        PdfSignatureAppearanceExtractor.computeCrop(A4, FIELD, 90, SCALE)),
                () -> assertArrayEquals(new int[]{1464, 1070, 200, 100},
                        PdfSignatureAppearanceExtractor.computeCrop(A4, FIELD, 270, SCALE)));
    }

    @Test
    @DisplayName("při 180° se zrcadlí vodorovně")
    void halfTurn() {
        assertArrayEquals(new int[]{1070, 20, 100, 200},
                PdfSignatureAppearanceExtractor.computeCrop(A4, FIELD, 180, SCALE));
    }

    @Test
    @DisplayName("záporné natočení se bere jako jeho kladný zbytek")
    void negativeRotationNormalised() {
        assertArrayEquals(
                PdfSignatureAppearanceExtractor.computeCrop(A4, FIELD, 270, SCALE),
                PdfSignatureAppearanceExtractor.computeCrop(A4, FIELD, -90, SCALE));
    }

    /**
     * Renderer kreslí CropBox, takže z něj musí vycházet i výpočet — a to včetně jeho počátku.
     * Dokud se počítalo z MediaBoxu, vyšel u odlišného CropBoxu výřez mimo obraz a vizualizace
     * se ztratila úplně.
     */
    @Test
    @DisplayName("výřez se řídí vykreslovaným boxem, ne MediaBoxem")
    void cropBoxDrivesTheResult() {
        PDRectangle smaller = new PDRectangle(0, 0, 300, 400);
        assertArrayEquals(new int[]{20, 580, 100, 200},
                PdfSignatureAppearanceExtractor.computeCrop(smaller, FIELD, 0, SCALE));
    }

    @Test
    @DisplayName("nenulový počátek boxu se od pole odečte")
    void cropBoxOriginSubtracted() {
        PDRectangle shifted = new PDRectangle(5, 5, 295, 395);
        PDRectangle fieldInShifted = new PDRectangle(15, 15, 50, 100);
        // Pole posunuté o týž kus jako box musí dát týž výřez jako box s počátkem v nule.
        assertArrayEquals(
                PdfSignatureAppearanceExtractor.computeCrop(
                        new PDRectangle(0, 0, 295, 395), new PDRectangle(10, 10, 50, 100),
                        0, SCALE),
                PdfSignatureAppearanceExtractor.computeCrop(shifted, fieldInShifted, 0, SCALE));
    }
}
