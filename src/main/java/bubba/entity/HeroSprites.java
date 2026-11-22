package bubba.entity;

import bubba.engine.Assets;
import bubba.engine.Json;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bubba + Stix animation frames (assets/hero/frames/NNN.png, hotspots in frames.json),
 * extracted from the hero frame table at code 0x1C174. A frame is drawn with its hotspot
 * on the character's feet; mirrored frames use {@code hotspot_x_flipped}.
 */
public final class HeroSprites {

    private record Frame(BufferedImage img, BufferedImage mirrored, int dx, int dy, int dxFlip) {
    }

    private final Map<Integer, Frame> frames = new HashMap<>();

    public HeroSprites(Assets assets) {
        List<Object> list = Json.array(Json.parse(assets.text("hero/frames.json")));
        for (Object o : list) {
            Map<String, Object> m = Json.object(o);
            BufferedImage img = assets.image("hero/frames/" + m.get("file"));
            BufferedImage mir = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    mir.setRGB(img.getWidth() - 1 - x, y, img.getRGB(x, y));
                }
            }
            frames.put(Json.integer(m.get("index")), new Frame(img, mir,
                    Json.integer(m.get("hotspot_x")), Json.integer(m.get("hotspot_y")),
                    Json.integer(m.get("hotspot_x_flipped"))));
        }
    }

    /** Draw frame {@code index} with its hotspot at screen position (x, y). */
    public void draw(Graphics2D g, int index, int x, int y, boolean facingLeft) {
        Frame f = frames.get(index);
        if (f == null) {
            return;
        }
        if (facingLeft) {
            // the whole frame is mirrored around the hotspot: -(dx + width). The table value
            // (dxFlip) is the same for 32-pixel frames only; wider ones (Stix held out: 48 / 64
            // pixels) must keep the body in place and send the extra columns to the left.
            g.drawImage(f.mirrored, x - f.dx - f.img.getWidth(), y + f.dy, null);
        } else {
            g.drawImage(f.img, x + f.dx, y + f.dy, null);
        }
    }
}
