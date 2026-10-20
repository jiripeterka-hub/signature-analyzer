package cz.digitalnikontinuita.anep.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Jméno, které aplikace navrhne pro protokol.
 *
 * <p>Přípona vstupního souboru se do jména vpisuje, místo aby se zahodila: protokoly ke
 * {@code smlouva.pdf} a {@code smlouva.asice} by jinak nesly totéž jméno a ve složce by se
 * navzájem přepsaly — a přepsaný protokol je horší než dvě jména, která se liší podtržítkem.
 */
class ProtocolFileNameTest {

    @Test
    @DisplayName("Přípona vstupu se stane součástí jména protokolu")
    void extensionBecomesPartOfTheName() {
        assertEquals("ZP_dokument_pdf.pdf",
                PrintUtil.protocolFileName("ZP", new File("D:/tmp/dokument.pdf")));
        assertEquals("RP_dokument_pdf.pdf",
                PrintUtil.protocolFileName("RP", new File("D:/tmp/dokument.pdf")));
    }

    @Test
    @DisplayName("Dva vstupy téhož jména s jinou příponou dají dvě různá jména protokolu")
    void twoInputsDoNotCollide() {
        String pdf = PrintUtil.protocolFileName("ZP", new File("smlouva.pdf"));
        String asice = PrintUtil.protocolFileName("ZP", new File("smlouva.asice"));
        assertEquals("ZP_smlouva_pdf.pdf", pdf);
        assertEquals("ZP_smlouva_asice.pdf", asice);
    }

    @Test
    @DisplayName("Tečky uvnitř jména zůstanou, dělí se až podle té poslední")
    void onlyTheLastDotSeparatesTheExtension() {
        assertEquals("ZP_verze 4 (1)_pdf.pdf",
                PrintUtil.protocolFileName("ZP", new File("verze 4 (1).pdf")));
        assertEquals("ZP_a.b_pdf.pdf", PrintUtil.protocolFileName("ZP", new File("a.b.pdf")));
    }

    @Test
    @DisplayName("Soubor bez přípony a chybějící soubor mají svá jména taky")
    void namesWithoutAnExtension() {
        assertEquals("ZP_dokument.pdf", PrintUtil.protocolFileName("ZP", new File("dokument")));
        assertEquals("RP_protokol.pdf", PrintUtil.protocolFileName("RP", null));
    }
}
