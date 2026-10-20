package cz.digitalnikontinuita.anep.ui;

import java.lang.reflect.Method;

/**
 * Ukáže, která písma našel {@code PrintUtil} na tomto systému pro export do PDF/A.
 *
 * <p>Hodí se při přenosu na jiný systém: když se nenajde nic, protokol se uloží jako obyčejné
 * PDF a PDF/A z něj nebude. Sonda to řekne dřív, než na to někdo přijde z výsledku.
 *
 * <p>Sahá dovnitř přes reflexi schválně: {@code fontConfig()} je vnitřní věc a kvůli sondě
 * nemá být veřejná.
 */
public final class FontLookupProbe {

    private FontLookupProbe() { }

    public static void main(String[] args) throws Exception {
        System.out.println("systém: " + System.getProperty("os.name")
                + " " + System.getProperty("os.version"));

        Method dirs = PrintUtil.class.getDeclaredMethod("fontDirs");
        dirs.setAccessible(true);
        System.out.println();
        System.out.println("prohledané adresáře:");
        for (Object d : (java.util.List<?>) dirs.invoke(null)) System.out.println("  " + d);

        Method faces = PrintUtil.class.getDeclaredMethod("findFaces");
        faces.setAccessible(true);
        java.io.File[] found = (java.io.File[]) faces.invoke(null);
        String[] names = {"obyčejné", "tučné", "kurzíva", "tučná kurzíva"};
        System.out.println();
        System.out.println("nalezené řezy:");
        int n = 0;
        for (int i = 0; i < found.length; i++) {
            System.out.printf("  %-14s %s%n", names[i],
                    found[i] == null ? "— nenalezeno" : found[i].getPath());
            if (found[i] != null) n++;
        }
        System.out.println();
        System.out.println(n == 0
                ? "ŽÁDNÉ písmo pro base-14 — protokol půjde uložit jen jako obyčejné PDF, ne PDF/A."
                : "Nalezeno " + n + " ze 4; PDF/A je dosažitelné.");
    }
}
