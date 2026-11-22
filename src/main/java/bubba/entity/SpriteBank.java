package bubba.entity;

import bubba.engine.Assets;
import bubba.engine.Json;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprites of a level (sprites*.adt / .ant), keyed by the sprite id used by the game scripts
 * (2 * entry index of the .ant table). Files: assets/sprites/levelX/NNNN.png + frames.json.
 * <p>
 * The original never mirrors a BOB. At load (code 0x6CDA) every entry of the table becomes a
 * slot of two words, both pointing to the entry; the level's list at $1133E (code 0x6DCA:
 * destination slot, count, source slot) then points the second word of some slots to other
 * pictures. The renderer (code 0x8484) reads the word at 2 * id, plus 2 when bit 15 is set.
 */
public final class SpriteBank {

    private record Frame(bubba.engine.PalettedImage img, int dx, int dy) {
    }

    /** The colours the sprites are drawn with (16 x 0xRRGGBB). */
    private int[] palette;

    private final Map<Integer, Frame> frames = new HashMap<>();
    /** Second word of a slot, when the level's list redirects it: slot -> source slot. */
    private final Map<Integer, Integer> second = new HashMap<>();

    /** {@code palette}: the colours the pictures were extracted with (the level's COLOR00-15). */
    public SpriteBank(Assets assets, String dir, int[] palette) {
        this.palette = palette.clone();
        List<Object> list = Json.array(Json.parse(assets.text(dir + "/frames.json")));
        for (Object o : list) {
            Map<String, Object> m = Json.object(o);
            BufferedImage img = assets.rawImage(dir + "/" + m.get("file"));
            frames.put(Json.integer(m.get("id")), new Frame(bubba.engine.PalettedImage.of(img, palette),
                    Json.integer(m.get("hotspot_x")), Json.integer(m.get("hotspot_y"))));
        }
    }

    /** Colours to draw the sprites with from now on (16 x 0xRRGGBB). */
    public void setPalette(int[] rgb) {
        palette = rgb.clone();
    }

    /** Reads the level's redirection list at $1133E (code 0x6DCA), once the set file is loaded. */
    public void redirections(bubba.cpu.GameMemory m) {
        second.clear();
        for (int a = 0x1133e; (short) m.read16(a) >= 0; a += 6) {
            int dst = m.read16(a), n = m.read16(a + 2), src = m.read16(a + 4);
            for (int i = 0; i < n; i++) {
                second.put(dst + i, src + i);
            }
        }
    }

    public boolean has(int id) {
        return frames.containsKey(id & 0x7ffe);
    }

    /**
     * Draw sprite {@code id} (object +$0A + $A0, code 0x8484) with its hotspot at (x, y): word
     * index id & $7FFF, plus 1 when bit 15 is set; ids with bits 15 and 14 set are not drawn.
     */
    public void draw(Graphics2D g, int id, int x, int y) {
        id &= 0xffff;
        if ((id & 0xc000) == 0xc000) {
            return;
        }
        drawWord(g, (id & 0x7fff) + (id >> 15), x, y);
    }

    /** Draw the picture of table word {@code w} (slot w / 2, second word when w is odd). */
    public void drawWord(Graphics2D g, int w, int x, int y) {
        int slot = w >> 1;
        if ((w & 1) != 0) {
            slot = second.getOrDefault(slot, slot);
        }
        Frame f = frames.get(2 * slot);
        if (f != null) {
            g.drawImage(f.img.image(palette), x + f.dx, y + f.dy, null);
        }
    }
}
