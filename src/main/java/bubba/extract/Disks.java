package bubba.extract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The two game disks, read and unpacked in memory.
 * <ul>
 *   <li>the ADF image comes from a TOSEC .zip or a bare .adf;</li>
 *   <li>its AmigaDOS (OFS / FFS) file system is read;</li>
 *   <li>every file that is one RNC method 1 block is replaced by its unpacked data; the blocks
 *       found inside other files are kept apart ({@link #blocks});</li>
 *   <li>the two hunks of the executable {@code Bubba} are RNC method 2 crunched in place.</li>
 * </ul>
 * File names are {@code disk1/...} and {@code disk2/...}, the sub-directory separator is '/'.
 */
public final class Disks {

    /** Unpacked files by name. */
    private final Map<String, byte[]> files = new TreeMap<>();
    /** RNC1 blocks found inside a file that is not a single block: offset -> data. */
    private final Map<String, Map<Integer, byte[]>> blocks = new TreeMap<>();
    private byte[] code, chip;

    /** Read the two disks (each a .zip containing an .adf, or an .adf). */
    public Disks(Path disk1, Path disk2) throws IOException {
        read("disk1", adf(disk1));
        read("disk2", adf(disk2));
        byte[] exe = file("disk1/Bubba");
        code = Rnc.unpack2(exe, 536 + 8);
        chip = Rnc.unpack2(exe, 210952 + 8);
    }

    public byte[] file(String name) {
        byte[] b = files.get(name);
        if (b == null) {
            throw new IllegalStateException("missing file on the disks: " + name);
        }
        return b;
    }

    public boolean exists(String name) {
        return files.containsKey(name);
    }

    /** All file names, sorted. */
    public List<String> names() {
        return new ArrayList<>(files.keySet());
    }

    /** RNC blocks embedded in a file, by offset (sorted). */
    public Map<Integer, byte[]> blocks(String name) {
        return blocks.getOrDefault(name, Map.of());
    }

    /** Decrunched code hunk of the executable. */
    public byte[] code() {
        return code;
    }

    /** Decrunched chip hunk of the executable. */
    public byte[] chip() {
        return chip;
    }

    // ------------------------------------------------------------------ disk image

    /** The ADF bytes of a .zip (first .adf entry) or of a bare .adf file. */
    static byte[] adf(Path p) throws IOException {
        if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".adf")) {
            return Files.readAllBytes(p);
        }
        try (InputStream in = Files.newInputStream(p); ZipInputStream z = new ZipInputStream(in)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.getName().toLowerCase(Locale.ROOT).endsWith(".adf")) {
                    return z.readAllBytes();
                }
            }
        }
        throw new IOException("no .adf in " + p);
    }

    /** Disk number (1 or 2) of an ADF image, from its volume name; 0 if unknown. */
    static int diskNumber(byte[] adf) {
        Map<String, byte[]> f = new TreeMap<>();
        new Adf(adf).walk(f, "");
        if (f.containsKey("Bubba")) {
            return 1;
        }
        if (f.containsKey("disk2.spk")) {
            return 2;
        }
        return 0;
    }

    private void read(String disk, byte[] adf) {
        Map<String, byte[]> raw = new TreeMap<>();
        new Adf(adf).walk(raw, "");
        if (!raw.containsKey(disk + ".spk")) {
            throw new IllegalStateException("this image is not Bubba 'n' Stix " + disk);
        }
        for (Map.Entry<String, byte[]> e : raw.entrySet()) {
            String name = disk + "/" + e.getKey();
            byte[] b = e.getValue();
            Map<Integer, byte[]> hits = new TreeMap<>();
            int i = find(b, 0);
            while (i >= 0) {
                int next;
                try {
                    Rnc.Unpacked u = Rnc.unpack1(b, i);
                    if (Rnc.crc16(u.data()) != ((b[i + 12] & 0xff) << 8 | (b[i + 13] & 0xff))) {
                        throw new IllegalStateException("crc");
                    }
                    hits.put(i, u.data());
                    next = i + u.packedSize();
                } catch (RuntimeException ex) {
                    next = i + 4;
                }
                i = find(b, next);
            }
            if (hits.size() == 1 && hits.containsKey(0)) {
                files.put(name, hits.get(0));
            } else {
                files.put(name, b);
                if (!hits.isEmpty()) {
                    blocks.put(name, hits);
                }
            }
        }
    }

    private static int find(byte[] b, int from) {
        for (int i = Math.max(0, from); i + 3 < b.length; i++) {
            if (b[i] == 'R' && b[i + 1] == 'N' && b[i + 2] == 'C' && b[i + 3] == 1) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ AmigaDOS

    /** AmigaDOS file system of an 880 KB disk (OFS or FFS). */
    private static final class Adf {
        private final byte[] d;
        private final boolean ffs;

        Adf(byte[] d) {
            if (d.length < 901120 || d[0] != 'D' || d[1] != 'O' || d[2] != 'S') {
                throw new IllegalStateException("not an AmigaDOS disk image");
            }
            this.d = d;
            ffs = (d[3] & 1) != 0;
        }

        int u32(int blk, int o) {
            int a = blk * 512 + o;
            return (d[a] & 0xff) << 24 | (d[a + 1] & 0xff) << 16 | (d[a + 2] & 0xff) << 8 | (d[a + 3] & 0xff);
        }

        String name(int blk) {
            int l = d[blk * 512 + 432] & 0xff;
            String n = new String(d, blk * 512 + 433, Math.min(l, 30), java.nio.charset.StandardCharsets.ISO_8859_1);
            n = n.replaceAll("[<>:\"/\\\\|?*]", "_").replaceAll("^[ .]+|[ .]+$", "");
            return n.isEmpty() ? "_" : n;
        }

        void walk(Map<String, byte[]> out, String path) {
            walk(880, out, path);
        }

        private void walk(int dir, Map<String, byte[]> out, String path) {
            for (int i = 0; i < 72; i++) {
                int h = u32(dir, 24 + 4 * i);
                while (h != 0) {
                    int type = u32(h, 508);
                    String p = path + name(h);
                    if (type == 2) {
                        walk(h, out, p + "/");
                    } else if (type == -3) {
                        out.put(p, file(h));
                    }
                    h = u32(h, 496);
                }
            }
        }

        private byte[] file(int hdr) {
            int size = u32(hdr, 324);
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(size);
            int cur = hdr;
            while (true) {
                int cnt = u32(cur, 8);
                for (int i = 0; i < cnt; i++) {
                    int db = u32(cur, 308 - 4 * i);
                    if (ffs) {
                        o.write(d, db * 512, 512);
                    } else {
                        o.write(d, db * 512 + 24, u32(db, 12));
                    }
                }
                int ext = u32(cur, 504);
                if (ext == 0) {
                    break;
                }
                cur = ext;
            }
            return java.util.Arrays.copyOf(o.toByteArray(), size);
        }
    }
}
