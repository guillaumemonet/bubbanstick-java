package bubba.level;

import bubba.engine.Assets;
import bubba.engine.Json;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One level, read from {@code assets/levels/stageN.json} (or {@code bonusN.json}), written by
 * tools/extract_all.py from the original files:
 * <ul>
 *   <li>map: 16x16 blocks, row-major, low 10 bits = block index (blocks image: colour 0 transparent);</li>
 *   <li>collision: one attribute per block (0 empty, 1 solid, 16 walkable top, 8 slope, 4 flag) and
 *       16-column height profiles for slope blocks (surface row = 15 - height);</li>
 *   <li>sky: the COLOR00 copper gradient (31 single lines then 32 bands), start position, music.</li>
 * </ul>
 */
public final class LevelData {

    public static final int BLOCK = 16;
    private static final int ATTR_SOLID = 0x01;
    private static final int ATTR_TOP = 0x10;
    private static final int ATTR_SLOPE = 0x08;

    private final String name;
    private final String fileId;
    private final String music;
    private final int startX, startY;
    private final int width, height;
    private final int[] tiles;
    private final int[] rawTiles;
    private final byte[] info;
    private final int[] attributes;
    private final int[][] profiles;
    private final Map<Integer, Integer> profileOfBlock = new HashMap<>();
    private final Color[] skyLines = new Color[256];
    private final int[] hillColors = new int[32];

    /** Colour of each of the 32 lines of the parallax hills (ARGB). */
    public int[] hillColors() {
        return hillColors.clone();
    }

    private final BufferedImage[] blocks;

    /** Number of block images in the sheet. */
    public int blockCount() {
        return blocks.length;
    }
    private final String spritesDir;
    private final byte[] setImage;
    private final Map<Integer, ObjectType> types = new HashMap<>();
    private final List<ObjectPlacement> placements = new java.util.ArrayList<>();

    private LevelData(Assets assets, String file) {
        Map<String, Object> doc = Json.object(Json.parse(assets.text("levels/" + file + ".json")));
        name = (String) doc.get("name");
        fileId = (String) doc.get("file_id");
        music = (String) doc.get("music");
        Map<String, Object> start = Json.object(doc.get("start"));
        startX = Json.integer(start.get("x"));
        startY = Json.integer(start.get("y"));

        Map<String, Object> map = Json.object(doc.get("map"));
        width = Json.integer(map.get("width"));
        height = Json.integer(map.get("height"));
        tiles = new int[width * height];
        rawTiles = new int[width * height];
        List<Object> rows = Json.array(map.get("rows"));
        for (int y = 0; y < height; y++) {
            List<Object> r = Json.array(rows.get(y));
            for (int x = 0; x < width; x++) {
                rawTiles[y * width + x] = Json.integer(r.get(x));
                tiles[y * width + x] = rawTiles[y * width + x] & 0x3ff;
            }
        }

        Map<String, Object> col = Json.object(doc.get("collision"));
        attributes = Json.array(col.get("attributes")).stream().mapToInt(Json::integer).toArray();
        List<Object> pr = Json.array(col.get("profiles"));
        profiles = new int[pr.size()][];
        for (int i = 0; i < pr.size(); i++) {
            profiles[i] = Json.array(pr.get(i)).stream().mapToInt(Json::integer).toArray();
        }
        Json.object(col.get("profile_of_block")).forEach((k, v) -> profileOfBlock.put(Integer.parseInt(k), Json.integer(v)));

        // copper sky (code 0x9DDC): info+$52 = 63 colours. The first 31 are COLOR00 for the first
        // 31 lines of the window (the background then keeps the last one); the next 32 are
        // COLOR29 of the 32 lines of the parallax hills just below ($605DA + $AC per line)
        List<Object> sky = Json.array(Json.object(doc.get("sky")).get("colors"));
        for (int line = 0; line < skyLines.length; line++) {
            skyLines[line] = Color.decode("#" + expand((String) sky.get(Math.min(line, 30))));
        }
        for (int i = 0; i < 32; i++) {
            hillColors[i] = Color.decode("#" + expand((String) sky.get(31 + i))).getRGB();
        }

        // objects (mtrg*.trg) and their classes (set*.bin)
        spritesDir = (String) doc.get("sprites_dir");
        setImage = assets.bytes((String) doc.get("set_file"));
        info = assets.bytes((String) doc.get("info_file"));
        Json.object(doc.get("object_types")).forEach((k, v) -> {
            Map<String, Object> t = Json.object(v);
            List<Object> hb = Json.array(t.get("hitbox"));
            Object spr = t.get("sprite");
            int id = Integer.parseInt(k);
            types.put(id, new ObjectType(id, spr == null ? null : Json.integer(spr),
                    Json.integer(hb.get(0)), Json.integer(hb.get(1)), Json.integer(hb.get(2)), Json.integer(hb.get(3)),
                    Json.integer(t.get("anim_script")), Json.integer(t.get("behaviour_script")),
                    java.util.HexFormat.of().parseHex((String) t.get("params"))));
        });
        java.io.ByteArrayOutputStream trg = new java.io.ByteArrayOutputStream();
        for (Object o : Json.array(doc.get("objects"))) {
            Map<String, Object> m = Json.object(o);
            // the mtrg*.trg file as loaded by the game (records read by code 0x102C4)
            writeWord(trg, Json.integer(m.get("type")));
            writeWord(trg, Json.integer(m.get("x")));
            writeWord(trg, Json.integer(m.get("y")));
            writeWord(trg, Integer.decode((String) m.get("flags")));
            if (m.containsKey("zone")) {
                for (Object z : Json.array(m.get("zone"))) {
                    for (Object v : Json.array(z)) {
                        writeWord(trg, Json.integer(v));
                    }
                }
            }
            placements.add(new ObjectPlacement(Json.integer(m.get("type")), Json.integer(m.get("x")),
                    Json.integer(m.get("y")), Integer.decode((String) m.get("flags")), m.containsKey("zone")));
        }

        writeWord(trg, 0);
        triggers = trg.toByteArray();

        BufferedImage sheet = assets.image((String) doc.get("blocks_image"));
        int cols = sheet.getWidth() / BLOCK;
        int count = cols * (sheet.getHeight() / BLOCK);
        blocks = new BufferedImage[count];
        // colour 0 is transparent in the sheet (PNG tRNS), colour 1 is opaque black
        for (int i = 0; i < count; i++) {
            BufferedImage t = new BufferedImage(BLOCK, BLOCK, BufferedImage.TYPE_INT_ARGB);
            t.getGraphics().drawImage(sheet.getSubimage((i % cols) * BLOCK, (i / cols) * BLOCK, BLOCK, BLOCK), 0, 0, null);
            blocks[i] = t;
        }
    }

    private static String expand(String rgb12) {
        String s = rgb12.startsWith("#") ? rgb12.substring(1) : rgb12;
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            b.append(c).append(c);
        }
        return b.toString();
    }

    /** Stage 1-5 (files stage1.json ... stage5.json, original order). */
    public static LevelData stage(Assets assets, int stage) {
        return new LevelData(assets, "stage" + stage);
    }

    public static LevelData bonus(Assets assets, int n) {
        return new LevelData(assets, "bonus" + n);
    }

    public String name() {
        return name;
    }

    /** File id of the level on the original disks (1, 3, 4, 5, 6, b). */
    public String fileId() {
        return fileId;
    }

    public String spritesDir() {
        return spritesDir;
    }

    /** Raw lev*info.obj (used to rebuild the level tables in the emulated memory). */
    public byte[] info() {
        return info;
    }

    /** Map words as stored in mfmap*.dat. */
    public int[] rawMap() {
        return rawTiles;
    }

    private byte[] triggers;

    private static void writeWord(java.io.ByteArrayOutputStream out, int v) {
        out.write(v >> 8);
        out.write(v);
    }

    /** The object records file (mtrg*.trg, unpacked) as the game loads it. */
    public byte[] triggers() {
        return triggers.clone();
    }

    /** A fresh copy of set*.bin (scripts may write into it). */
    public byte[] setImage() {
        return setImage.clone();
    }

    public List<ObjectPlacement> placements() {
        return placements;
    }

    public ObjectType type(int id) {
        return types.get(id);
    }

    public String music() {
        return music;
    }

    public int startX() {
        return startX;
    }

    public int startY() {
        return startY;
    }

    public int widthPx() {
        return width * BLOCK;
    }

    public int heightPx() {
        return height * BLOCK;
    }

    public int block(int bx, int by) {
        if (bx < 0 || by < 0 || bx >= width || by >= height) {
            return 0;
        }
        return tiles[by * width + bx];
    }

    public int attribute(int block) {
        return block < attributes.length ? attributes[block] : 0;
    }

    /** True if the pixel (px, py) of the level is solid ground. */
    public boolean solidAt(int px, int py) {
        if (px < 0 || px >= widthPx()) {
            return true; // level edges act as walls
        }
        if (py < 0 || py >= heightPx()) {
            return false;
        }
        int b = block(px >> 4, py >> 4);
        if (b == 0) {
            return false;
        }
        Integer prof = profileOfBlock.get(b);
        if (prof != null) {
            int h = profiles[prof][px & 15];
            return (py & 15) >= 15 - h;
        }
        int a = attribute(b);
        return (a & (ATTR_SOLID | ATTR_TOP | ATTR_SLOPE)) != 0;
    }

    /** Copper sky (fixed to the screen like on the Amiga). */
    public void drawSky(Graphics2D g, int viewW, int viewH) {
        for (int y = 0; y < viewH; y++) {
            g.setColor(skyLines[Math.min(y, skyLines.length - 1)]);
            g.fillRect(0, y, viewW, 1);
        }
    }

    /** Draw the part of the map visible from camera (cx, cy). */
    public void draw(Graphics2D g, int cx, int cy, int viewW, int viewH) {
        draw(g, cx, cy, viewW, viewH, this::block);
    }

    /** Same with the block of each cell given by {@code blockAt(bx, by)} (e.g. the map in memory). */
    public void draw(Graphics2D g, int cx, int cy, int viewW, int viewH,
            java.util.function.IntBinaryOperator blockAt) {
        int bx0 = Math.floorDiv(cx, BLOCK);
        int by0 = Math.floorDiv(cy, BLOCK);
        int bx1 = Math.floorDiv(cx + viewW - 1, BLOCK);
        int by1 = Math.floorDiv(cy + viewH - 1, BLOCK);
        for (int by = by0; by <= by1; by++) {
            for (int bx = bx0; bx <= bx1; bx++) {
                int b = blockAt.applyAsInt(bx, by);
                if (b > 0 && b < blocks.length) {
                    g.drawImage(blocks[b], bx * BLOCK - cx, by * BLOCK - cy, null);
                }
            }
        }
    }
}
