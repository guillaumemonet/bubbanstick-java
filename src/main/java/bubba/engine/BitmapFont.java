package bubba.engine;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * The game's proportional menu font (RNC block at code 0x5665E, drawn by the routine at 0x4936):
 * glyph advance = width + 1, space = 12 pixels, unknown characters are skipped.
 */
public final class BitmapFont {

    private record Glyph(BufferedImage img, int dx, int dy, int w) {
    }

    private final Map<Character, Glyph> glyphs = new HashMap<>();
    private final int space;
    private final int spacing;
    private final int height;

    public BitmapFont(Assets assets, String name) {
        Map<String, Object> meta = Json.object(Json.parse(assets.text("fonts/" + name + ".json")));
        BufferedImage sheet = assets.image("fonts/" + name + ".png");
        space = Json.integer(meta.get("space_width"));
        spacing = Json.integer(meta.get("spacing"));
        height = Json.integer(meta.get("height"));
        Json.object(meta.get("glyphs")).forEach((k, v) -> {
            Map<String, Object> gm = Json.object(v);
            int w = Json.integer(gm.get("w"));
            int h = Json.integer(gm.get("h"));
            BufferedImage img = sheet.getSubimage(Json.integer(gm.get("x")), Json.integer(gm.get("y")), w, h);
            glyphs.put(k.charAt(0), new Glyph(img, Json.integer(gm.get("dx")), Json.integer(gm.get("dy")), w));
        });
    }

    public int height() {
        return height;
    }

    public int width(String text) {
        int w = 0;
        for (char c : text.toCharArray()) {
            if (c == ' ') {
                w += space;
            } else {
                Glyph g = glyphs.get(Character.toUpperCase(c));
                if (g != null) {
                    w += g.w + spacing;
                }
            }
        }
        return w;
    }

    /** Draw with the top-left corner at (x, y). */
    public void draw(Graphics2D g, String text, int x, int y) {
        for (char c : text.toCharArray()) {
            if (c == ' ') {
                x += space;
                continue;
            }
            Glyph gl = glyphs.get(Character.toUpperCase(c));
            if (gl == null) {
                continue;
            }
            g.drawImage(gl.img, x + gl.dx, y + gl.dy, null);
            x += gl.w + spacing;
        }
    }

    /** Draw horizontally centred on cx (like the original, which subtracts width/2). */
    public void drawCentered(Graphics2D g, String text, int cx, int y) {
        draw(g, text, cx - width(text) / 2, y);
    }
}
