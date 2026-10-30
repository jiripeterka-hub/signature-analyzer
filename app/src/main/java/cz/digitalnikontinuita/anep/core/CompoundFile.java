package cz.digitalnikontinuita.anep.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Čtení složeného dokumentu OLE (Compound File Binary, [MS-CFB]) — jen tolik, kolik potřebuje
 * podpis balíčku MSI: strom úložišť a proudů, obsah proudů a údaje adresáře.
 * <p>
 * Napsáno vlastní, ne převzato z Apache POI (30. 10. 2026, podpis kódu): podpis MSI leží
 * v jediném proudu {@code \u0005DigitalSignature} a jeho otisk se počítá ze všech proudů
 * a z polí adresáře; kvůli tomu nestojí za to přibalit knihovnu o několika megabajtech. Zápis
 * neumí. Poškozený soubor končí výjimkou, ne zacyklením: každý řetěz sektorů je omezen jejich
 * počtem.
 */
final class CompoundFile {

    static final byte[] MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    static final int STORAGE = 1, STREAM = 2, ROOT = 5;
    private static final int FREE = 0xFFFFFFFF, END_OF_CHAIN = 0xFFFFFFFE;

    /** Položka adresáře; {@code raw} je celých jejích 128 bajtů, jak leží v souboru. */
    static final class Entry {
        final byte[] raw;
        final List<Entry> children = new ArrayList<>();

        Entry(byte[] raw) { this.raw = raw; }

        int type() { return raw[0x42]; }
        /** Délka jména v bajtech včetně koncové nuly UTF-16 (pole adresáře). */
        int nameLen() { int n = le16(raw, 0x40); return n >= 2 && n <= 64 ? n : 0; }
        /** Jméno v UTF-16LE včetně koncové nuly, jak se porovnává při výpočtu otisku. */
        byte[] nameBytes() { return Arrays.copyOf(raw, nameLen()); }
        String name() {
            int n = nameLen();
            return n >= 2 ? new String(raw, 0, n - 2, StandardCharsets.UTF_16LE) : "";
        }
        byte[] clsid() { return Arrays.copyOfRange(raw, 0x50, 0x60); }
        long start() { return le32(raw, 0x74); }
        long size() { return le64(raw, 0x78); }
    }

    private final byte[] bytes;
    private final int sectorSize, miniSectorSize, miniCutoff;
    private final int[] fat;
    private final int[] miniFat;
    private final byte[] miniStream;
    private final Entry root;

    static boolean looksLike(byte[] b) {
        if (b == null || b.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) if (b[i] != MAGIC[i]) return false;
        return true;
    }

    CompoundFile(byte[] bytes) {
        if (!looksLike(bytes) || bytes.length < 512) throw new IllegalArgumentException("není složený dokument OLE");
        this.bytes = bytes;
        int sectorShift = u16(0x1E), miniShift = u16(0x20);
        if (sectorShift != 9 && sectorShift != 12) throw new IllegalArgumentException("neznámá velikost sektoru");
        sectorSize = 1 << sectorShift;
        miniSectorSize = 1 << miniShift;
        miniCutoff = (int) u32(0x38);
        int fatSectors = (int) u32(0x2C);
        int firstDir = (int) u32(0x30);
        int firstMiniFat = (int) u32(0x3C);
        int firstDifat = (int) u32(0x44);
        int difatSectors = (int) u32(0x48);

        // DIFAT: 109 položek v hlavičce, další v řetězu sektorů DIFAT.
        List<Integer> fatSectorList = new ArrayList<>();
        for (int i = 0; i < 109 && fatSectorList.size() < fatSectors; i++) {
            int s = (int) u32(0x4C + 4 * i);
            if (s != FREE) fatSectorList.add(s);
        }
        int next = firstDifat;
        int per = sectorSize / 4 - 1;
        for (int d = 0; d < difatSectors && next != END_OF_CHAIN && next != FREE; d++) {
            int off = offset(next);
            for (int i = 0; i < per && fatSectorList.size() < fatSectors; i++) {
                int s = (int) u32(off + 4 * i);
                if (s != FREE) fatSectorList.add(s);
            }
            next = (int) u32(off + 4 * per);
        }
        fat = new int[fatSectorList.size() * (sectorSize / 4)];
        int k = 0;
        for (int s : fatSectorList) {
            int off = offset(s);
            for (int i = 0; i < sectorSize / 4; i++) fat[k++] = (int) u32(off + 4 * i);
        }

        byte[] dir = readChain(firstDir, -1);
        miniFat = toInts(firstMiniFat == END_OF_CHAIN || firstMiniFat == FREE
                ? new byte[0] : readChain(firstMiniFat, -1));

        int count = dir.length / 128;
        if (count < 1 || dir[0x42] != ROOT) throw new IllegalArgumentException("chybí kořenová položka");
        Entry[] entries = new Entry[count];
        for (int i = 0; i < count; i++) entries[i] = new Entry(Arrays.copyOfRange(dir, i * 128, i * 128 + 128));
        root = entries[0];
        // Kořen nese začátek a délku mini-proudu.
        long rootSize = root.size();
        miniStream = rootSize > 0 ? readChain((int) root.start(), rootSize) : new byte[0];

        // Potomci úložiště jsou všechny uzly binárního stromu pod jeho polem „child“.
        Set<Integer> placed = new HashSet<>();
        placed.add(0);
        List<Object[]> todo = new ArrayList<>();
        todo.add(new Object[]{root, 0});
        while (!todo.isEmpty()) {
            Object[] item = todo.remove(todo.size() - 1);
            Entry parent = (Entry) item[0];
            int parentId = (int) item[1];
            List<Integer> stack = new ArrayList<>();
            stack.add((int) le32(entries[parentId].raw, 0x4C));
            while (!stack.isEmpty()) {
                int id = stack.remove(stack.size() - 1);
                if (id < 0 || id >= count || !placed.add(id)) continue;
                Entry e = entries[id];
                if (e.type() == STREAM || e.type() == STORAGE) {
                    parent.children.add(e);
                    if (e.type() == STORAGE) todo.add(new Object[]{e, id});
                }
                stack.add((int) le32(e.raw, 0x44));
                stack.add((int) le32(e.raw, 0x48));
            }
        }
    }

    Entry root() { return root; }

    /** Identifikátor třídy kořene ve tvaru {@code {XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX}}. */
    String rootClsid() { return clsid(root.raw, 0x50); }

    private Entry rootStream(String name) {
        for (Entry e : root.children) if (e.type() == STREAM && e.name().equals(name)) return e;
        return null;
    }

    boolean hasStream(String name) { return rootStream(name) != null; }

    /** Obsah proudu pod kořenem, nebo {@code null}, když tam není. */
    byte[] stream(String name) {
        Entry e = rootStream(name);
        return e == null ? null : read(e);
    }

    /** Obsah proudu. */
    byte[] read(Entry e) {
        long size = e.size();
        if (sectorSize == 512) size &= 0xFFFFFFFFL; // verze 3: horní polovina se nepoužívá
        if (size == 0) return new byte[0];
        return size < miniCutoff ? readMini((int) e.start(), size) : readChain((int) e.start(), size);
    }

    private byte[] readChain(int start, long size) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int s = start, guard = 0;
        while (s != END_OF_CHAIN && (size < 0 || out.size() < size)) {
            if (s < 0 || s >= fat.length || ++guard > fat.length) {
                throw new IllegalArgumentException("poškozený řetěz sektorů");
            }
            int off = offset(s);
            out.write(bytes, off, Math.min(sectorSize, bytes.length - off));
            s = fat[s];
        }
        byte[] all = out.toByteArray();
        if (size >= 0 && all.length < size) throw new IllegalArgumentException("proud je kratší, než udává adresář");
        return size >= 0 ? Arrays.copyOf(all, (int) size) : all;
    }

    private byte[] readMini(int start, long size) {
        byte[] out = new byte[(int) size];
        int s = start, pos = 0, guard = 0;
        while (pos < size) {
            if (s < 0 || s >= miniFat.length || ++guard > miniFat.length) {
                throw new IllegalArgumentException("poškozený řetěz mini-sektorů");
            }
            int off = s * miniSectorSize;
            int n = (int) Math.min(miniSectorSize, size - pos);
            if (off + n > miniStream.length) throw new IllegalArgumentException("mini-proud je kratší");
            System.arraycopy(miniStream, off, out, pos, n);
            pos += n;
            s = miniFat[s];
        }
        return out;
    }

    private int offset(int sector) {
        long off = ((long) sector + 1) * sectorSize;
        if (sector < 0 || off >= bytes.length) throw new IllegalArgumentException("sektor mimo soubor");
        return (int) off;
    }

    private int u16(int off) { return le16(bytes, off); }
    private long u32(int off) { return le32(bytes, off); }

    private static int[] toInts(byte[] b) {
        int[] out = new int[b.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = (int) le32(b, 4 * i);
        return out;
    }

    static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    static long le32(byte[] b, int off) {
        return (b[off] & 0xFFL) | (b[off + 1] & 0xFFL) << 8 | (b[off + 2] & 0xFFL) << 16
                | (b[off + 3] & 0xFFL) << 24;
    }

    private static long le64(byte[] b, int off) {
        return le32(b, off) | le32(b, off + 4) << 32;
    }

    private static String clsid(byte[] b, int off) {
        StringBuilder sb = new StringBuilder("{");
        sb.append(String.format("%08X-%04X-%04X-", le32(b, off), le16(b, off + 4), le16(b, off + 6)));
        for (int i = 8; i < 16; i++) {
            if (i == 10) sb.append('-');
            sb.append(String.format("%02X", b[off + i] & 0xFF));
        }
        return sb.append('}').toString();
    }
}
