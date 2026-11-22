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
 * (2 * entry index of the .ant table). Bit 15 of an id selects the horizontally mirrored copy,
 * like the original renderer (code 0x8484). Files: assets/sprites/levelX/NNNN.png + frames.json.
 */
public final class SpriteBank {

    private record Frame(BufferedImage img, BufferedImage mirrored, int dx, int dy) {
    }

    private final Map<Integer, Frame> frames = new HashMap<>();

    public SpriteBank(Assets assets, String dir) {
        List<Object> list = Json.array(Json.parse(assets.text(dir + "/frames.json")));
        for (Object o : list) {
            Map<String, Object> m = Json.object(o);
            BufferedImage img = assets.image(dir + "/" + m.get("file"));
            BufferedImage mir = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    mir.setRGB(img.getWidth() - 1 - x, y, img.getRGB(x, y));
                }
            }
            frames.put(Json.integer(m.get("id")), new Frame(img, mir,
                    Json.integer(m.get("hotspot_x")), Json.integer(m.get("hotspot_y"))));
        }
    }

    public boolean has(int id) {
        return frames.containsKey(id & 0x7fff);
    }

    /** Draw sprite {@code id} with its hotspot at screen position (x, y). */
    public void draw(Graphics2D g, int id, int x, int y) {
        Frame f = frames.get(id & 0x7fff);
        if (f == null) {
            return;
        }
        if ((id & 0x8000) != 0) {
            g.drawImage(f.mirrored, x - f.dx - f.img.getWidth(), y + f.dy, null);
        } else {
            g.drawImage(f.img, x + f.dx, y + f.dy, null);
        }
    }
}
