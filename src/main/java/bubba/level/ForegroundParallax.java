package bubba.level;

import bubba.engine.Assets;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Foreground parallax (fgplax?a.dat / fgplax?b.dat, level 1), rebuilt from the level start
 * (0x68BC, 0x6986) and the VBL routine 0x9812. On the Amiga it is the second playfield of a dual
 * playfield display, rising from the bottom of the screen when the camera goes down.
 * <p>
 * File: 7 colour words (stored halved: the game doubles them; layer A = COLOR09-15, layer B =
 * COLOR01-07), then rows of 3 bitplanes of $66 bytes (816 pixels). Parameters at info+$CE:
 * A: lines, f, v (16.16 factors), B at info+$D4 likewise. The top of a layer is at
 * maxCameraY - 6 - lines * f * 2; when the camera is below it, n = (cameraY - top) * v * 2 rows
 * are shown at the bottom of the window (they end on its last line, DIWSTOP $100). Horizontal scroll:
 * camera x * 2 (A) or * 3 (B), repeating every 512 pixels. Layer A goes in front of Bubba once
 * 31 rows or more are visible (BPLCON2 $44 instead of $64).
 */
public final class ForegroundParallax {

    private static final class Layer {
        BufferedImage img;
        int top, rows, v, speed;
    }

    private final Layer a, b;

    public ForegroundParallax(Assets assets, LevelData level) {
        byte[] info = level.info();
        int maxCamY = (u16(info, 2) - 0x0e) << 4;
        a = layer(assets.bytes("levels/" + level.name() + "_fgplaxa.bin"), info, 0xce, maxCamY, 9, 2);
        b = layer(assets.bytes("levels/" + level.name() + "_fgplaxb.bin"), info, 0xd4, maxCamY, 1, 3);
    }

    private static Layer layer(byte[] d, byte[] info, int p, int maxCamY, int firstColour, int speed) {
        Layer l = new Layer();
        l.rows = u16(info, p);
        int f = u16(info, p + 2);
        l.v = u16(info, p + 4);
        l.top = maxCamY - 6 - (int) (((long) l.rows * f * 2) >>> 16);
        l.speed = speed;
        int[] pal = new int[8];
        for (int i = 1; i < 8; i++) {
            int c = (u16(d, 2 * (i - 1)) * 2) & 0xfff;
            pal[i] = 0xff000000 | ((c >> 8 & 15) * 0x110000) | ((c >> 4 & 15) * 0x1100) | ((c & 15) * 0x11);
        }
        int rows = (d.length - 14) / (3 * 0x66);
        l.img = new BufferedImage(0x66 * 8, Math.max(1, rows), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < rows; y++) {
            int row = 14 + y * 3 * 0x66;
            for (int x = 0; x < 0x66 * 8; x++) {
                int v = 0;
                for (int pl = 0; pl < 3; pl++) {
                    v |= ((d[row + pl * 0x66 + (x >> 3)] >> (7 - (x & 7))) & 1) << pl;
                }
                if (v != 0) {
                    l.img.setRGB(x, y, pal[v]);
                }
            }
        }
        return l;
    }

    /** Rows of layer A shown for camera y (0 = hidden). */
    public int rowsA(int camY) {
        return shown(a, camY);
    }

    private static int shown(Layer l, int camY) {
        int d = camY - l.top;
        if (d <= 0) {
            return 0;
        }
        return Math.min(l.img.getHeight(), (int) (((long) d * l.v * 2) >>> 16));
    }

    /** Draw layer B and/or A (window coordinates). */
    public void draw(Graphics2D g, int camX, int camY, int viewW, boolean layerA, boolean layerB) {
        if (layerB) {
            drawLayer(g, b, camX, camY, viewW);
        }
        if (layerA) {
            drawLayer(g, a, camX, camY, viewW);
        }
    }

    private static void drawLayer(Graphics2D g, Layer l, int camX, int camY, int viewW) {
        int n = shown(l, camY);
        if (n <= 0) {
            return;
        }
        // the copper wait is at line $FE - n and the new bitplane pointers apply from the next
        // lines: the n rows end on the last line of the window (DIWSTOP $100)
        int y = 0x100 - n - 0x30;
        int p = Math.floorMod(camX * l.speed, 512);
        BufferedImage rows = l.img.getSubimage(0, 0, l.img.getWidth(), n);
        g.drawImage(rows, -p, y, null);
        if (-p + rows.getWidth() < viewW) {
            g.drawImage(rows, -p + 512, y, null);
        }
    }

    private static int u16(byte[] b, int o) {
        return o >= 0 && o + 1 < b.length ? ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff) : 0;
    }
}
