package bubba.extract;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Builds the assets directory the game reads from the two original disks: music (ProTracker),
 * sound effects (WAV), title and ending screens, levels, level sprites, Bubba and Stix, menu
 * font and the memory image of the original code.
 * <p>
 * Formats are documented in each stage; addresses are those of the decrunched code hunk.
 */
public final class Extractor {

    /** Bumped when the produced files change, so an older assets directory is rebuilt. */
    public static final int VERSION = 1;
    public static final String MARKER = "extract.version";

    private static final int PAL_CLOCK = 3546895;

    private final Disks disks;
    private final Path out;
    private final Consumer<String> progress;

    private Extractor(Disks disks, Path out, Consumer<String> progress) {
        this.disks = disks;
        this.out = out;
        this.progress = progress;
    }

    /** Extract everything into {@code out} (created if needed), then write the version marker. */
    public static void run(Disks disks, Path out, Consumer<String> progress) throws IOException {
        Extractor x = new Extractor(disks, out, progress);
        try {
            x.audio();
            x.screens();
            x.levels();
            x.hero();
            x.fonts();
            x.code();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        Files.writeString(out.resolve(MARKER), Integer.toString(VERSION));
    }

    /** True when {@code dir} holds a complete extraction of the current version. */
    public static boolean upToDate(Path dir) {
        try {
            return Files.readString(dir.resolve(MARKER)).trim().equals(Integer.toString(VERSION));
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) << 8 | (b[o + 1] & 0xff);
    }

    private static int s16(byte[] b, int o) {
        return (short) u16(b, o);
    }

    private static int u32(byte[] b, int o) {
        return u16(b, o) << 16 | u16(b, o + 2);
    }

    private void write(String rel, byte[] data) {
        try {
            Path p = out.resolve(rel);
            Files.createDirectories(p.getParent());
            Files.write(p, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void json(String rel, Object v) {
        write(rel, JsonOut.write(v).getBytes(StandardCharsets.UTF_8));
    }

    private void png(String rel, BufferedImage img) {
        try {
            Path p = out.resolve(rel);
            Files.createDirectories(p.getParent());
            if (!ImageIO.write(img, "png", p.toFile())) {
                throw new IOException("no PNG writer");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Amiga 12-bit colours to 0xRRGGBB (4-bit components * 17), padded with black to 256. */
    private static int[] amigaPalette(int... words) {
        int[] p = new int[256];
        for (int i = 0; i < words.length && i < 256; i++) {
            int v = words[i];
            p[i] = ((v >> 8) & 15) * 17 << 16 | ((v >> 4) & 15) * 17 << 8 | (v & 15) * 17;
        }
        return p;
    }

    private static int[] words(byte[] b, int o, int n) {
        int[] w = new int[n];
        for (int i = 0; i < n; i++) {
            w[i] = u16(b, o + 2 * i);
        }
        return w;
    }

    /** Indexed pixels to an ARGB image; colour 0 is transparent when {@code clear0}. */
    private static BufferedImage image(byte[] px, int w, int h, int[] pal, boolean clear0) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = px[y * w + x] & 0xff;
                img.setRGB(x, y, clear0 && v == 0 ? 0 : 0xff000000 | pal[v]);
            }
        }
        return img;
    }

    private static String hex3(int v) {
        return String.format("#%03x", v);
    }

    // ------------------------------------------------------------------ audio

    private void audio() {
        progress.accept("musique");
        List<String> np = new ArrayList<>();
        for (String n : disks.names()) {
            boolean top = n.matches("disk[12]/[^/]*\\.np") || n.matches("disk1/BubbaData/[^/]*\\.np");
            if (top) {
                np.add(n);
            }
        }
        java.util.Collections.sort(np);
        Set<String> seen = new HashSet<>();
        for (String f : np) {
            String base = f.substring(f.lastIndexOf('/') + 1);
            String n = base.substring(0, base.lastIndexOf('.'));
            if (seen.add(n)) {
                write("music/" + n + ".mod", NoisePacker.toMod(disks.file(f), "bubba " + n));
            }
        }
        progress.accept("bruitages");
        Map<ByteBuffer, String> same = new HashMap<>();
        List<Object> meta = new ArrayList<>();
        for (String d : new String[]{"disk1", "disk2"}) {
            List<Spak.Sample> list = Spak.parse(disks.file(d + "/" + d + ".spk"));
            for (int i = 0; i < list.size(); i++) {
                Spak.Sample s = list.get(i);
                int[] e = s.entry();
                int per = e[6] != 0 ? e[6] : 214;
                ByteBuffer key = ByteBuffer.wrap(s.pcm());
                String nm = same.get(key);
                if (nm == null) {
                    nm = String.format("%s_%02d", d, i);
                    same.put(key, nm);
                    write("sfx/" + nm + ".wav", wav(s.pcm(), PAL_CLOCK / per));
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("file", nm + ".wav");
                m.put("bank", d);
                m.put("index", i);
                m.put("period", per);
                m.put("rate_hz", roundHalfEven(PAL_CLOCK, per));
                m.put("volume", e[10]);
                m.put("length", s.pcm().length);
                m.put("period_mask", e[5]);
                m.put("priority", e[11] >> 8);
                m.put("fixed", e[11] & 0xff);
                meta.add(m);
            }
        }
        json("sfx/sfx.json", meta);
    }

    private static int roundHalfEven(int a, int b) {
        int q = a / b, r = a % b;
        if (2 * r > b || (2 * r == b && (q & 1) != 0)) {
            q++;
        }
        return q;
    }

    /** 8-bit mono WAV of signed samples. */
    private static byte[] wav(byte[] pcm, int rate) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate)
                .putShort((short) 1).putShort((short) 8)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
        for (byte v : pcm) {
            b.put((byte) (v + 128));
        }
        return b.array();
    }

    // ------------------------------------------------------------------ title and ending

    /** 320x256, 5 interleaved bitplanes (40 bytes per plane row) to chunky. */
    private static byte[] planarToChunky(byte[] buf) {
        byte[] px = new byte[320 * 256];
        for (int y = 0; y < 256; y++) {
            for (int pl = 0; pl < 5; pl++) {
                int o = (y * 5 + pl) * 40;
                for (int xb = 0; xb < 40; xb++) {
                    int v = buf[o + xb] & 0xff;
                    for (int k = 0; v != 0 && k < 8; k++) {
                        if ((v & (0x80 >> k)) != 0) {
                            px[y * 320 + xb * 8 + k] |= (byte) (1 << pl);
                        }
                    }
                }
            }
        }
        return px;
    }

    private static byte[] unByteRun(byte[] d, int n) {
        byte[] o = new byte[n];
        int k = 0, i = 0;
        while (k < n) {
            int c = d[i++] & 0xff;
            if (c < 128) {
                for (int j = 0; j <= c && k < n; j++) {
                    o[k++] = d[i + j];
                }
                i += c + 1;
            } else if (c > 128) {
                for (int j = 0; j < 257 - c && k < n; j++) {
                    o[k++] = d[i];
                }
                i++;
            }
        }
        return o;
    }

    private void screens() {
        progress.accept("écran titre");
        // titlepic.pak: IFF ILBM 320x256, 5 planes, ByteRun1
        byte[] ilbm = disks.file("disk1/BubbaData/titlepic.pak");
        Map<String, byte[]> ch = new HashMap<>();
        for (int p = 12; p + 8 <= ilbm.length; ) {
            String id = new String(ilbm, p, 4, StandardCharsets.ISO_8859_1);
            int ln = u32(ilbm, p + 4);
            ch.put(id, java.util.Arrays.copyOfRange(ilbm, p + 8, Math.min(ilbm.length, p + 8 + ln)));
            p += 8 + ln + (ln & 1);
        }
        byte[] body = unByteRun(ch.get("BODY"), 200 * 256);
        byte[] cmap = ch.get("CMAP");
        // titledlt.pak: 18 delta frames on the bitmap, double buffered (frame k on buffer k&1)
        byte[][] bufs = {java.util.Arrays.copyOf(body, body.length + 8192),
                java.util.Arrays.copyOf(body, body.length + 8192)};
        byte[] d = disks.file("disk1/BubbaData/titledlt.pak");
        List<byte[]> frames = new ArrayList<>();
        int p = 0;
        for (int f = 0; f < 18; f++) {
            byte[] buf = bufs[f & 1];
            int a = 0;
            int n = u16(d, p);
            p += 2;
            for (int r = 0; r < n; r++) {
                if (r > 0 || s16(d, p) < 0) {
                    a -= 2 * s16(d, p);
                    p += 2;
                }
                int c = u16(d, p);
                p += 2;
                System.arraycopy(d, p, buf, a, 2 * c);
                p += 2 * c;
                a += 2 * c;
            }
            frames.add(planarToChunky(buf));
        }
        if (p != d.length) {
            throw new IllegalStateException("titledlt.pak: unexpected size");
        }
        // copper split (code 0x24DA): COLOR00 = $203; colours 1-31 from the CMAP above picture
        // line 110, from the palette at code 0x2DD2 below
        int[] top = new int[32], bot = new int[32];
        top[0] = bot[0] = 0x203;
        for (int i = 1; i < 32; i++) {
            top[i] = ((cmap[3 * i] & 0xff) >> 4) << 8 | ((cmap[3 * i + 1] & 0xff) >> 4) << 4 | ((cmap[3 * i + 2] & 0xff) >> 4);
            bot[i] = u16(disks.code(), 0x2dd2 + 2 * (i - 1));
        }
        int[] pt = amigaPalette(top), pb = amigaPalette(bot);
        int split = 110;
        png("title/title_base.png", titleImage(planarToChunky(body), pt, pb, split));
        for (int f = 0; f < frames.size(); f++) {
            png(String.format("title/title_%02d.png", f), titleImage(frames.get(f), pt, pb, split));
        }
        Map<String, Object> tj = new LinkedHashMap<>();
        tj.put("background", "#203");
        tj.put("split_line", split);
        json("title/title.json", tj);

        progress.accept("fin du jeu");
        byte[] code = disks.code();
        int[] pa = amigaPalette(words(code, 0x45e, 16));
        int[] w9 = words(code, 0x47e, 16);
        w9[0] = 0;
        int[] p9 = amigaPalette(w9);
        int i = 0;
        for (byte[] fr : disks.blocks("disk1/endseq.bit").values()) {
            BufferedImage img = image(java.util.Arrays.copyOf(fr, 288 * 208), 288, 208, i == 9 ? p9 : pa, false);
            png(String.format("ending/ending_%02d.png", i), img);
            i++;
        }
    }

    private static BufferedImage titleImage(byte[] px, int[] top, int[] bot, int split) {
        BufferedImage img = new BufferedImage(320, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            int[] pal = y < split ? top : bot;
            for (int x = 0; x < 320; x++) {
                img.setRGB(x, y, pal[px[y * 320 + x] & 0xff]);
            }
        }
        return img;
    }

    // ------------------------------------------------------------------ levels

    /** Level file id, disk, stage number, music. Loader groups 60, 55, 50, 45, 40, 35. */
    private record Level(String id, String disk, Object stage, String music, String set) {
    }

    private static final Level[] LEVELS = {
            new Level("1", "disk1", 1, "level1", "set1.bin"),
            new Level("3", "disk2", 3, "level3", "set3.bin"),
            new Level("4", "disk2", 4, "level4", "set4.bin"),
            new Level("5", "disk2", 2, "level2", "set2.bin"),
            new Level("6", "disk1", 5, "level6", "set5.bin"),
            new Level("b", "disk2", "bonus", "bonus", "setb.bin"),
    };

    private static final int SET_BASE = 0x108ee, TYPE_TABLE = 0x111a6;

    private void levels() {
        for (Level lv : LEVELS) {
            progress.accept("niveau " + lv.id);
            String l = lv.id, dk = lv.disk + "/";
            byte[] info = disks.file(dk + "lev" + l + "info.obj");
            int mw = u16(info, 0), mh = u16(info, 2);
            // code 0x6692: info+0x12 -> COLOR00-15, COLOR00 then cleared (sky = copper gradient)
            int[] pw = words(info, 0x12, 16);
            pw[0] = 0;
            int[] pal = amigaPalette(pw);
            int[] spc = words(info, 0x32, 12);
            blocks(l, disks.file(dk + "mblk" + l + "am.chr"), disks.file(dk + "mblk" + l + "am.pts"), pal);
            sprites(l, disks.file(dk + "sprites" + l + ".ant"), disks.file(dk + "sprites" + l + ".adt"), pal);
            Map<String, Object> coll = collision(info);
            int[] sky = words(info, 0x52, 63);
            List<String> maps = new ArrayList<>();
            if (l.equals("b")) {
                for (int i = 1; i <= 5; i++) {
                    maps.add("b" + i);
                }
            } else {
                maps.add(l);
            }
            for (int k = 0; k < maps.size(); k++) {
                String mn = maps.get(k);
                String name = l.equals("b") ? "bonus" + (k + 1) : "stage" + lv.stage;
                byte[] m = disks.file(dk + "mfmap" + mn + ".dat");
                List<Object> rows = new ArrayList<>();
                for (int y = 0; y < mh; y++) {
                    List<Object> row = new ArrayList<>();
                    for (int x = 0; x < mw; x++) {
                        int o = 2 * (y * mw + x);
                        if (o + 1 < m.length) {
                            row.add(u16(m, o));
                        }
                    }
                    rows.add(row);
                }
                List<Object> objs = triggers(disks.file(dk + "mtrg" + mn + ".trg"));
                byte[] set = disks.file(dk + lv.set);
                Set<Integer> used = new TreeSet<>();
                for (Object o : objs) {
                    @SuppressWarnings("unchecked") Map<String, Object> om = (Map<String, Object>) o;
                    if (!om.containsKey("zone")) {
                        used.add((Integer) om.get("type"));
                    }
                }
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("name", name);
                doc.put("stage", lv.stage);
                doc.put("file_id", l);
                doc.put("source_files", List.of("lev" + l + "info.obj", "mblk" + l + "am.chr", "mblk" + l + "am.pts", "mfmap" + mn + ".dat"));
                doc.put("music", "music/" + lv.music + ".mod");
                doc.put("blocks_image", "levels/level" + l + "_blocks.png");
                doc.put("sprites_dir", "sprites/level" + l);
                doc.put("parallax_image", "parallax/parallax_level" + l + ".png");
                Map<String, Object> start = new LinkedHashMap<>();
                start.put("x", u16(info, 6));
                start.put("y", u16(info, 8));
                doc.put("start", start);
                doc.put("palette", hexList(pw));
                doc.put("sprite_colors_17_31", hexList(spc));
                Map<String, Object> sk = new LinkedHashMap<>();
                sk.put("comment", "COLOR00 copper gradient: 31 single lines then 32 bands");
                sk.put("colors", hexList(sky));
                doc.put("sky", sk);
                doc.put("objects", objs);
                doc.put("object_types", objectTypes(set, used));
                doc.put("set_file", "levels/" + name + "_set.bin");
                doc.put("info_file", "levels/" + name + "_info.bin");
                doc.put("collision", coll);
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("width", mw);
                map.put("height", mh);
                map.put("block_size", 16);
                map.put("comment", "block index per cell (low 10 bits), row by row");
                map.put("rows", rows);
                doc.put("map", map);
                json("levels/" + name + ".json", doc);
                write("levels/" + name + "_set.bin", set);
                // raw lev*info.obj: the game rebuilds its level tables from it (code 0x6A62)
                write("levels/" + name + "_info.bin", info);
                // parallax files, loaded by the game at chip $64A40 / $66030 / $66C30 / $6B8BE
                String[][] px = {{"mparasp", "mparasp" + l + ".mac"}, {"mplxobj", "mplxobj" + l + ".hs"},
                        {"fgplaxa", "fgplax" + l + "a.dat"}, {"fgplaxb", "fgplax" + l + "b.dat"}};
                for (String[] e : px) {
                    if (disks.exists(dk + e[1])) {
                        write("levels/" + name + "_" + e[0] + ".bin", disks.file(dk + e[1]));
                    }
                }
            }
        }
    }

    private static List<Object> hexList(int[] w) {
        List<Object> l = new ArrayList<>();
        for (int v : w) {
            l.add(hex3(v));
        }
        return l;
    }

    /** Blocks 16x16 = 4 chars 8x8 (chunky 4bpp, 32 bytes); bits 0-10 char, $800 h-flip, $1000 v-flip. */
    private void blocks(String l, byte[] chr, byte[] pts, int[] pal) {
        int nc = chr.length / 32, nb = pts.length / 8;
        int rows = (nb + 31) / 32;
        byte[] px = new byte[32 * 16 * rows * 16];
        int sw = 32 * 16;
        for (int b = 0; b < nb; b++) {
            int bx = (b % 32) * 16, by = (b / 32) * 16;
            for (int k = 0; k < 4; k++) {
                int v = u16(pts, 8 * b + 2 * k);
                int c = v & 0x7ff;
                if (c >= nc) {
                    continue;
                }
                boolean hf = (v & 0x800) != 0, vf = (v & 0x1000) != 0;
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        int sx = hf ? 7 - x : x, sy = vf ? 7 - y : y;
                        int byt = chr[c * 32 + sy * 4 + (sx >> 1)] & 0xff;
                        int val = (sx & 1) == 0 ? byt >> 4 : byt & 15;
                        px[(by + (k >> 1) * 8 + y) * sw + bx + (k & 1) * 8 + x] = (byte) val;
                    }
                }
            }
        }
        png("levels/level" + l + "_blocks.png", image(px, sw, rows * 16, pal, true));
    }

    /** sprites*.adt piece: width-1, height, then rows of 4 interleaved plane words. */
    private record Piece(int dx, int dy, int w, int h, byte[] px) {
    }

    private static Piece piece(byte[] adt, int o, int dx, int dy) {
        if (o < 0 || o + 4 > adt.length) {
            return null;
        }
        int w = u16(adt, o) + 1, h = u16(adt, o + 2), wpr = (w + 15) / 16;
        if (h == 0 || h > 256 || w > 320 || (long) o + 4 + (long) wpr * 8 * h > adt.length) {
            return null;
        }
        byte[] px = new byte[w * h];
        int p = o + 4;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = 0;
                for (int q = 0; q < 4; q++) {
                    if ((adt[p + q * wpr * 2 + (x >> 3)] & (0x80 >> (x & 7))) != 0) {
                        v |= 1 << q;
                    }
                }
                px[y * w + x] = (byte) v;
            }
            p += wpr * 8;
        }
        return new Piece(dx, dy, w, h, px);
    }

    /**
     * Level sprites (BOBs). Sprite id = 2 * entry index of the .ant table (as used by the game
     * scripts, code 0x6CDA); an entry is a word n then n+1 pieces (dx, dy, .adt offset.l).
     */
    private void sprites(String l, byte[] ant, byte[] adt, int[] pal) {
        String dir = "sprites/level" + l;
        List<Object> meta = new ArrayList<>();
        int count = u16(ant, 0) + 1;
        for (int k = 0; k < count; k++) {
            int rec = u16(ant, 2 + 2 * k);
            if (rec < 2 + 2 * count || rec + 10 > ant.length) {
                continue;
            }
            int n = u16(ant, rec);
            if (n > 16 || rec + 2 + 8 * (n + 1) > ant.length) {
                continue;
            }
            List<Piece> pieces = new ArrayList<>();
            for (int j = 0; j <= n; j++) {
                int q = rec + 2 + 8 * j;
                long off = u32(ant, q + 4) & 0xffffffffL;
                Piece pc = off > Integer.MAX_VALUE ? null : piece(adt, (int) off, s16(ant, q), s16(ant, q + 2));
                if (pc != null) {
                    pieces.add(pc);
                }
            }
            if (pieces.isEmpty()) {
                continue;
            }
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
            for (Piece pc : pieces) {
                x0 = Math.min(x0, pc.dx);
                y0 = Math.min(y0, pc.dy);
                x1 = Math.max(x1, pc.dx + pc.w);
                y1 = Math.max(y1, pc.dy + pc.h);
            }
            int w = x1 - x0, h = y1 - y0;
            byte[] px = new byte[w * h];
            for (Piece pc : pieces) { // later pieces drawn over earlier ones
                for (int y = 0; y < pc.h; y++) {
                    for (int x = 0; x < pc.w; x++) {
                        byte v = pc.px[y * pc.w + x];
                        if (v != 0) {
                            px[(pc.dy - y0 + y) * w + pc.dx - x0 + x] = v;
                        }
                    }
                }
            }
            int sid = 2 * k;
            String file = String.format("%04d.png", sid);
            png(dir + "/" + file, image(px, w, h, pal, true));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", sid);
            m.put("file", file);
            m.put("w", w);
            m.put("h", h);
            m.put("hotspot_x", x0);
            m.put("hotspot_y", y0);
            m.put("flags", n);
            meta.add(m);
        }
        json(dir + "/frames.json", meta);
    }

    /**
     * Collision (level start, code 0x6A62): one attribute word per block from info+$E6
     * (0 empty, 1 solid, $10 walkable top, 8 slope, 4 flag); slope blocks get a 16-column
     * height profile (info+[$DE]), assigned by (block, profile) pairs at info+[$E0].
     */
    private static Map<String, Object> collision(byte[] info) {
        int endAttr = u16(info, 0xda), de = u16(info, 0xde), e0 = u16(info, 0xe0), e2 = u16(info, 0xe2);
        List<Object> attrs = new ArrayList<>();
        for (int i = 0; i < Math.floorDiv(endAttr - 0xe6, 2); i++) {
            attrs.add(u16(info, 0xe6 + 2 * i));
        }
        List<Object> profiles = new ArrayList<>();
        for (int i = 0; i < (e0 - de) / 16; i++) {
            List<Object> pr = new ArrayList<>();
            for (int k = 0; k < 16; k++) {
                pr.add(info[de + 16 * i + k] & 0xff);
            }
            profiles.add(pr);
        }
        Map<String, Object> of = new LinkedHashMap<>();
        int np = (e2 - e0) / 2, last = -1;
        for (int k = 0; k < np - 1; k += 2) {
            int blk = u16(info, e0 + 2 * k), pi = u16(info, e0 + 2 * k + 2);
            if (blk <= last || pi >= profiles.size()) {
                break;
            }
            of.put(Integer.toString(blk), pi);
            last = blk;
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("comment", "attribute per block: 0 empty, 1 solid, 16 walkable top, 8 slope (uses profile), 4 flag; profile = surface height (0-16) per pixel column");
        c.put("attributes", attrs);
        c.put("profiles", profiles);
        c.put("profile_of_block", of);
        return c;
    }

    /** mtrg*.trg (code 0x102E0): type, x, y, flags; (flags >> 8) &amp; 3 = number of 5-word zones. */
    private static List<Object> triggers(byte[] t) {
        int n = t.length / 2;
        List<Object> out = new ArrayList<>();
        int i = 0;
        while (i + 3 < n && u16(t, 2 * i) != 0) {
            int ty = u16(t, 2 * i), f = u16(t, 2 * i + 6);
            int nz = (f >> 8) & 3;
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("type", ty & 0x7fff);
            o.put("x", u16(t, 2 * i + 2));
            o.put("y", u16(t, 2 * i + 4));
            o.put("flags", String.format("0x%04x", f));
            if (nz != 0) {
                List<Object> zones = new ArrayList<>();
                for (int k = 0; k < nz; k++) {
                    List<Object> z = new ArrayList<>();
                    for (int j = i + 4 + 5 * k; j < i + 9 + 5 * k && j < n; j++) {
                        z.add(u16(t, 2 * j));
                    }
                    zones.add(z);
                }
                o.put("zone", zones);
            }
            out.add(o);
            i += 4 + 5 * nz;
        }
        return out;
    }

    /** Object definitions (set*.bin loaded at $108EE, table at $111A6, code 0x10758). */
    private static Map<String, Object> objectTypes(byte[] s, Set<Integer> used) {
        int tt = TYPE_TABLE - SET_BASE;
        Map<String, Object> out = new LinkedHashMap<>();
        for (int t : used) {
            int o = tt + 2 * (t - 1);
            if (t < 1 || o + 2 > s.length) {
                continue;
            }
            int dd = o + s16(s, o);
            if (dd < 0 || dd >= s.length - 0x18) {
                continue;
            }
            int w0 = u16(s, dd);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sprite", w0 >= 0xfffe ? null : w0);
            m.put("hitbox", List.of(s16(s, dd + 6), s16(s, dd + 8), s16(s, dd + 10), s16(s, dd + 12)));
            m.put("anim_script", dd + 2 + s16(s, dd + 2));
            m.put("behaviour_script", dd + 4 + s16(s, dd + 4));
            StringBuilder hx = new StringBuilder();
            for (int k = dd + 14; k < dd + 0x18; k++) {
                hx.append(String.format("%02x", s[k] & 0xff));
            }
            m.put("params", hx.toString());
            out.put(Integer.toString(t), m);
        }
        return out;
    }

    // ------------------------------------------------------------------ hero

    /**
     * Bubba + Stix frames, inside the code: table at $1C174 (count, word offsets), each frame
     * dx, dy, dx_flipped, parts-1, then (y offset, data offset.l) per 16-pixel column; graphics
     * at $1D36A: height, sprite A (planes 0/1), sprite B (planes 2/3, stored bit-reversed).
     */
    private void hero() {
        progress.accept("Bubba et Stix");
        byte[] b = disks.code();
        int[] pal = amigaPalette(words(b, 0x81a2, 16));
        int table = 0x1c174, gfx = 0x1d36a;
        int n = u16(b, table);
        List<Object> meta = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int o = table + u16(b, table + 2 + 2 * i);
            int dx = s16(b, o), dy = s16(b, o + 2), dx2 = s16(b, o + 4), cnt = u16(b, o + 6);
            List<int[]> cols = new ArrayList<>();
            List<byte[]> data = new ArrayList<>();
            boolean ok = true;
            for (int k = 0; k <= cnt; k++) {
                int yo = u16(b, o + 8 + 6 * k);
                long dof = u32(b, o + 10 + 6 * k) & 0xffffffffL;
                if (gfx + dof + 2 > b.length || dof > 0x40000) {
                    ok = false;
                    break;
                }
                int c = gfx + (int) dof;
                int h = u16(b, c);
                if (h > 200) {
                    ok = false;
                    break;
                }
                byte[] px = new byte[16 * h];
                for (int y = 0; y < h; y++) {
                    int a0 = u16(b, c + 2 + y * 4), a1 = u16(b, c + 4 + y * 4);
                    int c0 = reverse16(u16(b, c + 2 + h * 4 + y * 4)), c1 = reverse16(u16(b, c + 4 + h * 4 + y * 4));
                    int[] ws = {a0, a1, c0, c1};
                    for (int x = 0; x < 16; x++) {
                        int v = 0;
                        for (int q = 0; q < 4; q++) {
                            if ((ws[q] >> (15 - x) & 1) != 0) {
                                v |= 1 << q;
                            }
                        }
                        px[y * 16 + x] = (byte) v;
                    }
                }
                cols.add(new int[]{k * 16, yo, h});
                data.add(px);
            }
            if (!ok || cols.isEmpty()) {
                continue;
            }
            int w = 16 * cols.size(), hh = 0;
            for (int[] c : cols) {
                hh = Math.max(hh, c[1] + c[2]);
            }
            byte[] img = new byte[w * hh];
            for (int k = 0; k < cols.size(); k++) {
                int[] c = cols.get(k);
                for (int y = 0; y < c[2]; y++) {
                    System.arraycopy(data.get(k), y * 16, img, (y + c[1]) * w + c[0], 16);
                }
            }
            String file = String.format("%03d.png", i);
            png("hero/frames/" + file, image(img, w, hh, pal, true));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("file", file);
            m.put("w", w);
            m.put("h", hh);
            m.put("hotspot_x", dx);
            m.put("hotspot_y", dy);
            m.put("hotspot_x_flipped", dx2);
            meta.add(m);
        }
        json("hero/frames.json", meta);
    }

    private static int reverse16(int v) {
        return Integer.reverse(v) >>> 16;
    }

    // ------------------------------------------------------------------ menu font

    /** Menu font: RNC block at code $5665E; glyph order of the text routine (code 0x4936). */
    private void fonts() {
        progress.accept("police");
        byte[] f = Rnc.unpack1(disks.code(), 0x5665e).data();
        String chars = "0123456789!.-ABCDEFGHIJKLMNOPQRSTUVWXYZ>?'";
        // colours from the options copper list (code 0x404C)
        int[] cols = {0, 0xff000000, 0xff44ccee, 0xffeeeeee};
        int n = u16(f, 0) / 2;
        BufferedImage sheet = new BufferedImage(16 * n, 16, BufferedImage.TYPE_INT_ARGB);
        Map<String, Object> glyphs = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int o = u16(f, 2 * i);
            int dx = s16(f, o), dy = s16(f, o + 2), w = s16(f, o + 4), h = s16(f, o + 6);
            int wpr = (w >> 4) + 1, p = o + 8;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int wi = x >> 4, bit = 15 - (x & 15);
                    int p0 = u16(f, p + (y * 2 * wpr + wi) * 2) >> bit & 1;
                    int p1 = u16(f, p + (y * 2 * wpr + wpr + wi) * 2) >> bit & 1;
                    int sx = 16 * i + x;
                    if (sx < sheet.getWidth() && y < 16) {
                        sheet.setRGB(sx, y, cols[p0 | p1 << 1]);
                    }
                }
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("x", 16 * i);
            g.put("y", 0);
            g.put("w", w);
            g.put("h", h);
            g.put("dx", dx);
            g.put("dy", dy);
            glyphs.put(String.valueOf(chars.charAt(i)), g);
        }
        png("fonts/menu_font.png", sheet);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("space_width", 12);
        meta.put("spacing", 1);
        meta.put("height", 16);
        meta.put("glyphs", glyphs);
        json("fonts/menu_font.json", meta);
    }

    // ------------------------------------------------------------------ code image

    private static final int CHIP_BASE = 0x60000;

    /**
     * game/memory.bin: the code hunk at 0 and the chip hunk at $60000, chip relocations applied
     * (code relocations are hunk-relative, i.e. already absolute with base 0).
     */
    private void code() {
        progress.accept("code du jeu");
        byte[] code = disks.code().clone(), chip = disks.chip();
        int p = 0x57318 + 4;
        while (true) {
            int n = u32(code, p);
            p += 4;
            if (n == 0) {
                break;
            }
            int h = u32(code, p);
            p += 4;
            for (int k = 0; k < n; k++) {
                int loc = u32(code, p + 4 * k);
                if (h == 1) {
                    int v = u32(code, loc) + CHIP_BASE;
                    code[loc] = (byte) (v >>> 24);
                    code[loc + 1] = (byte) (v >>> 16);
                    code[loc + 2] = (byte) (v >>> 8);
                    code[loc + 3] = (byte) v;
                }
            }
            p += 4 * n;
        }
        byte[] mem = new byte[CHIP_BASE + chip.length];
        System.arraycopy(code, 0, mem, 0, code.length);
        System.arraycopy(chip, 0, mem, CHIP_BASE, chip.length);
        write("game/memory.bin", mem);
    }
}
