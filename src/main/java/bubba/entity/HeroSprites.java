package bubba.entity;

import bubba.engine.Assets;
import bubba.engine.Json;
import bubba.engine.PalettedImage;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bubba + Stix animation frames (assets/hero/frames/NNN.png, hotspots in frames.json),
 * extracted from the hero frame table at code 0x1C174. A frame is drawn with its hotspot
 * on the character's feet; mirrored frames use {@code hotspot_x_flipped}.
 * <p>
 * The frames are kept as colour indices and drawn with the sprite colours of the moment
 * (COLOR17-31 of the copper list), so Bubba fades with the screen.
 */
public final class HeroSprites {

    private record Frame(PalettedImage img, PalettedImage mirrored, int dx, int dy, int dxFlip) {
    }

    private final Map<Integer, Frame> frames = new HashMap<>();

    public HeroSprites(Assets assets) {
        int[] extracted = extractionPalette(assets);
        List<Object> list = Json.array(Json.parse(assets.text("hero/frames.json")));
        for (Object o : list) {
            Map<String, Object> m = Json.object(o);
            BufferedImage img = assets.rawImage("hero/frames/" + m.get("file"));
            BufferedImage mir = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    mir.setRGB(img.getWidth() - 1 - x, y, img.getRGB(x, y));
                }
            }
            frames.put(Json.integer(m.get("index")), new Frame(PalettedImage.of(img, extracted),
                    PalettedImage.of(mir, extracted), Json.integer(m.get("hotspot_x")),
                    Json.integer(m.get("hotspot_y")), Json.integer(m.get("hotspot_x_flipped"))));
        }
    }

    /** The colours the frames were extracted with: the hero palette at $81A2 of the code. */
    private static int[] extractionPalette(Assets assets) {
        byte[] mem = assets.bytes("game/memory.bin");
        int[] p = new int[16];
        for (int i = 0; i < 16; i++) {
            int c = ((mem[0x81a2 + 2 * i] & 0xff) << 8) | (mem[0x81a3 + 2 * i] & 0xff);
            p[i] = ((c >> 8 & 15) * 17) << 16 | ((c >> 4 & 15) * 17) << 8 | (c & 15) * 17;
        }
        return p;
    }

    /**
     * Draw frame {@code index} with its hotspot at screen position (x, y), in {@code palette}
     * (16 x 0xRRGGBB, entry 0 transparent).
     */
    public void draw(Graphics2D g, int index, int x, int y, boolean facingLeft, int[] palette) {
        Frame f = frames.get(index);
        if (f == null) {
            return;
        }
        if (facingLeft) {
            // the whole frame is mirrored around the hotspot: -(dx + width). The table value
            // (dxFlip) is the same for 32-pixel frames only; wider ones (Stix held out: 48 / 64
            // pixels) must keep the body in place and send the extra columns to the left.
            g.drawImage(f.mirrored.image(palette), x - f.dx - f.img.width(), y + f.dy, null);
        } else {
            g.drawImage(f.img.image(palette), x + f.dx, y + f.dy, null);
        }
    }
}
