package cz.digitalnikontinuita.anep.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Jeden prvek podpisové struktury, jak ho ukazuje Inspektor (PDF, CAdES, ASiC).
 *
 * <p>Uzel nese to, co v souboru skutečně stojí — řádky {@link #rows()} jsou přečtené hodnoty,
 * ne závěry. Závěry jsou zvlášť v {@link #notes()} a mají dva původy: strukturní kontrolu, kterou
 * dělá sám Inspektor (rozsah {@code /ByteRange} nekončí na konci revize, klíč VRI nepatří
 * k žádnému podpisu), a výklad podle výsledku ověření ({@link StructureAnnotations}), který se
 * přidává až dodatečně. Oddělené jsou proto, aby čtenář vždy viděl, co je fakt o souboru a co
 * o něm řekl DSS.
 *
 * <p><b>Dva klíče, dva účely.</b> {@link #key()} je totožnost prvku v souboru — týž certifikát
 * uložený v CMS, v {@code /DSS} i ve VRI má týž klíč, a okno podle něj ukazuje „další výskyty“.
 * {@link #dssKey()} slouží k napojení na diagnostická data DSS a smí ho mít i uzel, který sám
 * prvkem DSS není (slovník podpisu v PDF nese klíč hodnoty podpisu ze svého CMS).
 */
public final class StructureNode {

    /** Druh prvku — podle něj okno volí ikonu a nabídku tlačítek. */
    public enum Kind {
        GROUP, SIGNATURE, DOC_TIMESTAMP, USAGE_RIGHTS, EMPTY_FIELD, CMS, SIGNER, ATTRIBUTE,
        TIMESTAMP, CERTIFICATE, CRL, OCSP, DSS, VRI, REVISION, MANIFEST, FILE, VALUE
    }

    public enum Severity { INFO, OK, WARNING, ERROR }

    /**
     * Řádek detailu. {@code link} je klíč jiného uzlu, na který se dá z řádku přejít, nebo
     * {@code null}.
     */
    public record Row(String label, String value, String link) {}

    /** {@code fromValidation} odlišuje výklad podle ověření od strukturního zjištění. */
    public record Note(Severity severity, String text, boolean fromValidation) {}

    /** Úsek souboru v bajtech, polootevřený {@code [start, end)}; {@code gap} = mezera v rozsahu. */
    public record Span(long start, long end, boolean gap) {}

    private final Kind kind;
    private String title;
    private String subtitle;
    private final List<Row> rows = new ArrayList<>();
    private final List<Note> notes = new ArrayList<>();
    private final List<StructureNode> children = new ArrayList<>();
    private final List<Span> spans = new ArrayList<>();
    private byte[] data;
    private String saveName;
    private String key;
    private String dssKey;
    private boolean expanded;
    private java.util.Date time;

    public StructureNode(Kind kind, String title) {
        this.kind = kind;
        this.title = title;
    }

    public Kind kind()                  { return kind; }
    public String title()               { return title; }
    /** Krátké shrnutí šedě za názvem ve stromu, nebo {@code null}. */
    public String subtitle()            { return subtitle; }
    public List<Row> rows()             { return rows; }
    public List<Note> notes()           { return notes; }
    public List<StructureNode> children() { return children; }
    /** Úseky souboru, které prvek pokrývá — pro pruh nad stromem; prázdné u prvků bez polohy. */
    public List<Span> spans()           { return spans; }
    /** Bajty prvku k uložení a k podrobnému výpisu, nebo {@code null}. */
    public byte[] data()                { return data; }
    public String saveName()            { return saveName; }
    public String key()                 { return key; }
    public String dssKey()              { return dssKey; }
    /** Má se uzel ve stromu otevřít hned — jen u skupin, které nic neskrývají. */
    public boolean expanded()           { return expanded; }
    /** Okamžik, ke kterému se prvek vztahuje (genTime razítka) — k řazení; jinak {@code null}. */
    public java.util.Date time()        { return time; }
    public StructureNode time(java.util.Date v) { this.time = v; return this; }

    public StructureNode title(String v)    { this.title = v; return this; }
    public StructureNode subtitle(String v) { this.subtitle = v; return this; }
    public StructureNode key(String v)      { this.key = v; return this; }
    public StructureNode dssKey(String v)   { this.dssKey = v; return this; }
    public StructureNode expanded(boolean v) { this.expanded = v; return this; }

    public StructureNode data(byte[] bytes, String name) {
        this.data = bytes;
        this.saveName = name;
        return this;
    }

    public StructureNode row(String label, String value) {
        if (value != null && !value.isBlank()) rows.add(new Row(label, value, null));
        return this;
    }

    public StructureNode row(String label, String value, String link) {
        if (value != null && !value.isBlank()) rows.add(new Row(label, value, link));
        return this;
    }

    public StructureNode note(Severity severity, String text) {
        notes.add(new Note(severity, text, false));
        return this;
    }

    public StructureNode validationNote(Severity severity, String text) {
        notes.add(new Note(severity, text, true));
        return this;
    }

    public StructureNode span(long start, long end, boolean gap) {
        if (end > start) spans.add(new Span(start, end, gap));
        return this;
    }

    public StructureNode add(StructureNode child) {
        if (child != null) children.add(child);
        return child;
    }

    /** Nejhorší závažnost poznámek tohoto uzlu (bez potomků), nebo {@code null}. */
    public Severity worstSeverity() {
        Severity worst = null;
        for (Note n : notes) {
            if (n.severity() == Severity.INFO) continue;
            if (worst == null || n.severity().ordinal() > worst.ordinal()) worst = n.severity();
        }
        return worst;
    }

    @Override
    public String toString() { return title; }
}
