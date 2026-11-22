package bubba.engine;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.IndexColorModel;
import java.util.Arrays;

/**
 * A 16-colour picture kept as colour indices, drawn with the palette of the moment (the game
 * changes its colours: fades, the end of stage 5). Index 0 is transparent.
 * <p>
 * Built from an indexed PNG, or from an RGB one by finding each pixel's colour in the palette it
 * was drawn with (the first entry from 1 that matches; transparent pixels are index 0).
 */
public final class PalettedImage {

    private final int w, h;
    private final byte[] px;
    private BufferedImage cache;
    private int[] cachePalette;

    private PalettedImage(int w, int h, byte[] px) {
        this.w = w;
        this.h = h;
        this.px = px;
    }

    /** The part (x, y, w, h) of {@code img}, whose colours come from {@code palette} (0xRRGGBB). */
    public static PalettedImage of(BufferedImage img, int x0, int y0, int w, int h, int[] palette) {
        byte[] px = new byte[w * h];
        boolean indexed = img.getColorModel() instanceof IndexColorModel;
        java.awt.image.Raster r = img.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v;
                if (indexed) {
                    v = r.getSample(x0 + x, y0 + y, 0) & 15;
                } else {
                    v = index(img.getRGB(x0 + x, y0 + y), palette);
                }
                px[y * w + x] = (byte) v;
            }
        }
        return new PalettedImage(w, h, px);
    }

    public static PalettedImage of(BufferedImage img, int[] palette) {
        return of(img, 0, 0, img.getWidth(), img.getHeight(), palette);
    }

    private static int index(int argb, int[] palette) {
        if ((argb >>> 24) < 0x80) {
            return 0;
        }
        int rgb = argb & 0xffffff, best = 1, dist = Integer.MAX_VALUE;
        for (int i = 1; i < palette.length && i < 16; i++) {
            int p = palette[i] & 0xffffff;
            if (p == rgb) {
                return i;
            }
            int dr = (p >> 16) - (rgb >> 16), dg = (p >> 8 & 0xff) - (rgb >> 8 & 0xff), db = (p & 0xff) - (rgb & 0xff);
            int d = dr * dr + dg * dg + db * db;
            if (d < dist) {
                dist = d;
                best = i;
            }
        }
        return best;
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    /** The picture in {@code palette} (16 colours 0xRRGGBB), rebuilt only when it changed. */
    public BufferedImage image(int[] palette) {
        if (cache == null || !Arrays.equals(cachePalette, palette)) {
            if (cache == null) {
                cache = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            }
            int[] out = ((DataBufferInt) cache.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < px.length; i++) {
                int v = px[i];
                out[i] = v == 0 ? 0 : 0xff000000 | palette[v];
            }
            cachePalette = palette.clone();
        }
        return cache;
    }
}
