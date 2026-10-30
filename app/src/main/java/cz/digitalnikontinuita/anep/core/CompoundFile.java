package cz.digitalnikontinuita.anep.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Čtení složeného dokumentu OLE (Compound File Binary, [MS-CFB]) — jen tolik, kolik potřebuje
 * podpis balíčku MSI: proudy na nejvyšší úrovni a identifikátor třídy kořene.
 * <p>
 * Napsáno vlastní, ne převzato z Apache POI (30. 10. 2026, etapa 1 podpisu kódu): podpis MSI leží
 * v jediném proudu {@code \u0005DigitalSignature} a kvůli němu nestojí za to přibalit knihovnu
 * o několika megabajtech. Zápis neumí a vnořená úložiště neprochází. Poškozený soubor končí
 * výjimkou, ne zacyklením: každý řetěz sektorů je omezen jejich počtem.
 */
final class CompoundFile {

    static final byte[] MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private static final int FREE = 0xFFFFFFFF, END_OF_CHAIN = 0xFFFFFFFE;

    private final byte[] bytes;
    private final int sectorSize, miniSectorSize, miniCutoff;
    private final int[] fat;
    private final int[] miniFat;
    private final byte[] miniStream;
    private final String rootClsid;
    /** Proudy pod kořenem: jméno → [první sektor, velikost]. */
    private final Map<String, long[]> streams = new LinkedHashMap<>();

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

        // Kořen (položka 0) nese začátek a délku mini-proudu a identifikátor třídy.
        if (dir.length < 128 || dir[0x42] != 5) throw new IllegalArgumentException("chybí kořenová položka");
        rootClsid = clsid(dir, 0x50);
        long rootStart = le32(dir, 0x74), rootSize = le64(dir, 0x78);
        miniStream = rootSize > 0 ? readChain((int) rootStart, rootSize) : new byte[0];

        // Proudy přímo pod kořenem: binární strom od potomka kořene přes levé a pravé sourozence.
        int entries = dir.length / 128;
        List<Integer> todo = new ArrayList<>();
        todo.add((int) le32(dir, 0x4C));
        Set<Integer> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            int id = todo.remove(todo.size() - 1);
            if (id < 0 || id >= entries || !seen.add(id)) continue;
            int base = id * 128;
            int nameLen = le16(dir, base + 0x40);
            String name = nameLen >= 2 && nameLen <= 64
                    ? new String(dir, base, nameLen - 2, StandardCharsets.UTF_16LE) : "";
            if (dir[base + 0x42] == 2) {
                streams.put(name, new long[]{le32(dir, base + 0x74), le64(dir, base + 0x78)});
            }
            todo.add((int) le32(dir, base + 0x44));
            todo.add((int) le32(dir, base + 0x48));
        }
    }

    /** Identifikátor třídy kořene ve tvaru {@code {XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX}}. */
    String rootClsid() { return rootClsid; }

    boolean hasStream(String name) { return streams.containsKey(name); }

    /** Obsah proudu pod kořenem, nebo {@code null}, když tam není. */
    byte[] stream(String name) {
        long[] s = streams.get(name);
        if (s == null) return null;
        long size = s[1];
        if (sectorSize == 512) size &= 0xFFFFFFFFL; // verze 3: horní polovina se nepoužívá
        if (size == 0) return new byte[0];
        return size < miniCutoff ? readMini((int) s[0], size) : readChain((int) s[0], size);
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
        return size >= 0 ? java.util.Arrays.copyOf(all, (int) size) : all;
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
