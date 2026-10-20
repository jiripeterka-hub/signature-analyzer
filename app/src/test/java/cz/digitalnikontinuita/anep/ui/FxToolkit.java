package cz.digitalnikontinuita.anep.ui;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Startuje toolkit JavaFX jednou za JVM a nechává ho běžet až do jeho konce.
 *
 * <p>Každá testovací třída si ho dřív startovala sama a ve svém {@code @AfterAll} ho zase
 * ukončila. Kdo přišel na řadu druhý, tomu {@code Platform.startup()} vyhodil výjimku, test se
 * přes {@code Assumptions} přeskočil — a přeskočený test vypadá v protokolu zeleně. Právě takhle
 * se stalo, že se dva nově napsané testy nikdy nespustily a nikdo si toho nevšiml. Toolkit se
 * proto startuje odsud a ukončuje se až se samotným JVM.
 */
final class FxToolkit {

    private static Boolean up;

    /** True, když se na toolkit dá spolehnout. Bezpečné volat z libovolné třídy i opakovaně. */
    static synchronized boolean available() {
        if (up != null) return up;
        try {
            CountDownLatch ready = new CountDownLatch(1);
            Platform.startup(ready::countDown);
            up = ready.await(30, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            // Nastartoval ho někdo před námi — přesně to chceme.
            up = true;
        } catch (Throwable t) {
            // Bez displeje: přeskočit je lepší než spadnout na stroji, kde by se stejně nedala
            // spustit ani aplikace.
            up = false;
        }
        if (Boolean.TRUE.equals(up)) {
            // Bez tohohle se toolkit ukončí sám ve chvíli, kdy se zavře poslední okno — a od té
            // chvíle se žádné Platform.runLater() nikdy neprovede. Dokud žádná zkouška okno
            // neukazovala, nebylo to vidět; TsaEditDialogSizeTest okna otevírá a zavírá, a tím
            // shodila samu sebe: spuštěná po jedné procházela, spuštěné pohromadě čekaly tři ze
            // čtyř na vlákno, které už neexistovalo. Ukončení obstará shutdown hook níž.
            Platform.setImplicitExit(false);
            Runtime.getRuntime().addShutdownHook(new Thread(Platform::exit, "fx-toolkit-shutdown"));
        }
        return up;
    }

    private FxToolkit() { }
}
