package bubba.level;

import bubba.engine.Assets;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Background parallax of a level, rebuilt in Java from the original data:
 * <p>
 * <b>Hills</b> (mparasp*.mac, VBL routine 0x8E32): a band of 32 lines just under the 31-line sky
 * gradient (display lines $4E-$6D, window lines 30-61). Each line shows 19 tiles of 16 pixels
 * (the copper repositions sprite 7 and pokes its data every 16 pixels); a pixel set = COLOR29 of
 * that line (the 32 colours after the sky in info+$52). Tiles: 1 plane, 32 rows, data inverted at
 * level start (0x6B6C); the strip = 83 tile offsets at info+[$E4]. Scrolling: info+$4E = mask,
 * info+$50 = shift: the strip moves at camera x / 2^(shift-1), over (mask+1) / 2^(shift-1) pixels
 * (64 tiles) before it repeats.
 */
public final class Parallax {

    private static final int HILLS_Y = 30;
    private static final int TILES = 83;

    private final BufferedImage strip;
    /** Line (0-31) of each strip pixel set, -1 = clear: the strip is redrawn when colours change. */
    private final byte[] lines;
    private int[] stripColors;
    /** Sky objects (level 1 only: mplxobj1.hs), null when the level has none. */
    private final ParallaxObjects objects;
    private final int shift;
    private final int period;

    public Parallax(Assets assets, LevelData level) {
        byte[] info = level.info();
        byte[] tiles = assets.bytes("levels/" + level.name() + "_mparasp.bin");
        int mask = u16(info, 0x4e);
        shift = Math.max(1, u16(info, 0x50));
        period = (mask + 1) >> (shift - 1);
        int list = u16(info, 0xe4);
        int[] colors = level.hillColors();
        ParallaxObjects objs = null;
        try {
            objs = new ParallaxObjects(assets, level);
        } catch (RuntimeException e) {
            // no mplxobj file for this level
        }
        objects = objs;
        strip = new BufferedImage(TILES * 16, 32, BufferedImage.TYPE_INT_ARGB);
        lines = new byte[TILES * 16 * 32];
        java.util.Arrays.fill(lines, (byte) -1);
        for (int k = 0; k < TILES; k++) {
            int off = (short) u16(info, list + 2 * k);
            for (int y = 0; y < 32; y++) {
                int p = off + 2 * y;
                if (p < 0 || p + 1 >= tiles.length) {
                    continue;
                }
                int w = ~(((tiles[p] & 0xff) << 8) | (tiles[p + 1] & 0xff)) & 0xffff;
                for (int x = 0; x < 16; x++) {
                    if ((w >> (15 - x) & 1) != 0) {
                        strip.setRGB(k * 16 + x, y, colors[y]);
                        lines[y * TILES * 16 + k * 16 + x] = (byte) y;
                    }
                }
            }
        }
    }

    /** Colours of the 32 hill lines from now on (ARGB; they fade with the rest of the screen). */
    public void setColors(int[] colors) {
        if (java.util.Arrays.equals(colors, stripColors)) {
            return;
        }
        stripColors = colors.clone();
        int w = TILES * 16;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i] >= 0) {
                strip.setRGB(i % w, i / w, colors[lines[i]]);
            }
        }
    }

    /** One VBL of the sky objects (twice per 25 Hz logic frame). */
    public void vbl(int camX) {
        if (objects != null) {
            objects.vbl(camX);
        }
    }

    /**
     * Draw the background layers in window coordinates: {@code camX} = playfield x origin,
     * {@code scrollX} = camera x (L_051CA) for the sky objects that move in the world.
     */
    public void draw(Graphics2D g, int camX, int viewW, int scrollX) {
        int p = Math.floorMod(camX >> (shift - 1), Math.max(16, period));
        g.drawImage(strip, -p, HILLS_Y, null);
        if (-p + strip.getWidth() < viewW) {
            g.drawImage(strip, -p + period, HILLS_Y, null);
        }
        if (objects != null) {
            objects.draw(g, scrollX); // lower sprite channels: in front of the hills (sprite 7)
        }
    }

    private static int u16(byte[] b, int o) {
        return o >= 0 && o + 1 < b.length ? ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff) : 0;
    }
}
