package bubba.emu;

import bubba.cpu.GameMemory;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * The original status bar, drawn from memory: 8 hardware sprites whose data the game's own
 * routines (0x8638 / 0x872A / 0x87BC) keep up to date at (L_082A6)+$1440 (copper $61FEE sets
 * the pointers, increments $6C,$6C,$6C,$74,$78,$78,$78):
 * <ul>
 *   <li>sprites 0-2: score digits (2 per sprite) above the energy boxes (2 per sprite);</li>
 *   <li>sprite 3: "x" and the lives digit;</li>
 *   <li>sprites 4+5 and 6+7: attached pairs, Bubba's portrait (15 colours).</li>
 * </ul>
 * Colours: the hero sprite palette (code 0x81A2, colours 16-31). The sprite pointers are set at
 * line $E0 (bottom of the play window); the horizontal positions are not in the data
 * (written to the sprite registers), the layout here follows the sprite order.
 */
public final class StatusBar {

    private static final int DATA = 0x1440;
    private static final int[] SIZES = {0x6c, 0x6c, 0x6c, 0x74, 0x78, 0x78, 0x78, 0x78};
    /** Window line of the bar (copper wait $E0 - DIWSTRT $30). */
    public static final int Y = 0xe0 - 0x30;

    private final BufferedImage img = new BufferedImage(112, 30, BufferedImage.TYPE_INT_ARGB);

    /** Draw the bar in window coordinates. */
    public void draw(Graphics2D g, GameMemory m, int x) {
        int base = m.read32(0x82a6) + DATA;
        int[] start = new int[8];
        for (int k = 0, a = base; k < 8; a += SIZES[k], k++) {
            start[k] = a;
        }
        int[] pal = new int[16];
        for (int i = 0; i < 16; i++) {
            int c = m.read16(0x81a2 + 2 * i);
            pal[i] = 0xff000000 | ((c >> 8 & 15) * 0x110000) | ((c >> 4 & 15) * 0x1100) | ((c & 15) * 0x11);
        }
        for (int y = 0; y < img.getHeight(); y++) {
            for (int px = 0; px < img.getWidth(); px++) {
                img.setRGB(px, y, 0);
            }
        }
        // portrait: attached pairs (4,5) and (6,7)
        for (int p = 0; p < 2; p++) {
            for (int y = 0; y < 29; y++) {
                int a = m.read16(start[4 + 2 * p] + 4 * y), b = m.read16(start[4 + 2 * p] + 4 * y + 2);
                int c = m.read16(start[5 + 2 * p] + 4 * y), d = m.read16(start[5 + 2 * p] + 4 * y + 2);
                for (int bit = 0; bit < 16; bit++) {
                    int s = 15 - bit;
                    int v = (a >> s & 1) | (b >> s & 1) << 1 | (c >> s & 1) << 2 | (d >> s & 1) << 3;
                    if (v != 0) {
                        img.setRGB(p * 16 + bit, y, pal[v]);
                    }
                }
            }
        }
        // score / energy / lives: sprites 0-3, 3 colours
        for (int k = 0; k < 4; k++) {
            for (int y = 0; y < 27; y++) {
                int a = m.read16(start[k] + 4 * y), b = m.read16(start[k] + 4 * y + 2);
                for (int bit = 0; bit < 16; bit++) {
                    int s = 15 - bit;
                    int v = (a >> s & 1) | (b >> s & 1) << 1;
                    if (v != 0) {
                        img.setRGB(40 + k * 16 + bit, y, pal[v]);
                    }
                }
            }
        }
        g.drawImage(img, x, Y, null);
    }
}
