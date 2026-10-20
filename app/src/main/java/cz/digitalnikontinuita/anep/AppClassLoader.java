package cz.digitalnikontinuita.anep;

/** Holds the app classloader captured before JavaFX replaces it on its threads. */
public class AppClassLoader {
    private static ClassLoader instance;

    public static void set(ClassLoader cl) { instance = cl; }
    public static ClassLoader get() { return instance; }
}
