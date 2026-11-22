package bubba.level;

import bubba.engine.Assets;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Sky objects of the parallax (planet, stars, flying creatures...), rebuilt in Java from the level
 * start routine 0x91A0 and the VBL routine 0x8F6C.
 * <p>
 * Records at info+[$DC] (type -1 = end), shapes read one after the other from mplxobj*.hs (word
 * columns-1, word height, then per column `height` lines of 2 sprite words; animated shapes have
 * one such block per frame, each frame after the first preceded by 4 bytes):
 * <ul>
 *   <li>type 0: x, y - fixed on screen (hardware position);</li>
 *   <li>type 1: speed, pos, y - drifts: pos += speed - 32 * scroll per VBL, x = pos / 128;</li>
 *   <li>type 2+: speed, pos, y, shift, mask, frames, offset of an animation table (byte pairs
 *       duration, frame*4; 0 = loop) - pos += speed per VBL; x = pos (shift 0: fixed on screen)
 *       or (pos >> shift) - camera - 1, wrapped by mask (shift > 0: moves in the world).</li>
 * </ul>
 * Each 16-pixel column is a hardware sprite: the channel is the first one free at that height
 * (0..7, columns of one object on the following channels), its colours are those of its pair
 * (info+$32: 17-19, 21-23, 25-27, 29-31; COLOR29 changes on every line of the hills band).
 * Positions: x on screen = hardware x - $94, y = line - $30; x is clamped to $1B0 (hidden).
 */
public final class ParallaxObjects {

    private static final int HILLS_FIRST = 0x4e, HILLS_END = 0x6e;

    private static final class Obj {
        int type, speed, pos, y, shift, mask;
        int height;
        int[] channel;                      // per column
        BufferedImage[][] frames;           // [frame][column]
        int[] anim = new int[0];            // duration, frame pairs
        int animPos, timer, frame;
    }

    private final List<Obj> objects = new ArrayList<>();
    private int lastCam = Integer.MIN_VALUE;

    public ParallaxObjects(Assets assets, LevelData level) {
        byte[] info = level.info();
        byte[] shapes = assets.bytes("levels/" + level.name() + "_mplxobj.bin");
        int[][] colors = spriteColors(info);
        int[] hill = level.hillColors();
        int[] freeLine = new int[8];
        int a1 = u16(info, 0xdc), a0 = 0;
        while (a1 + 2 <= info.length) {
            int type = s16(info, a1);
            a1 += 2;
            if (type < 0) {
                break;
            }
            Obj o = new Obj();
            o.type = type;
            int frameCount = 1;
            if (type == 0) {
                o.pos = s16(info, a1);
                o.y = s16(info, a1 + 2);
                a1 += 4;
            } else if (type == 1) {
                o.speed = s16(info, a1);
                o.pos = s16(info, a1 + 2);
                o.y = s16(info, a1 + 4);
                a1 += 6;
            } else {
                o.speed = s16(info, a1);
                o.pos = s16(info, a1 + 2);
                o.y = s16(info, a1 + 4);
                o.shift = s16(info, a1 + 6);
                o.mask = s16(info, a1 + 8);
                frameCount = Math.max(1, s16(info, a1 + 10));
                int table = a1 + 12 + s16(info, a1 + 12);
                List<Integer> anim = new ArrayList<>();
                for (int p = table; p + 1 < info.length && info[p] != 0 && anim.size() < 128; p += 2) {
                    anim.add(info[p] & 0xff);
                    anim.add(info[p + 1] & 0xff);
                }
                o.anim = anim.stream().mapToInt(Integer::intValue).toArray();
                a1 += 14;
            }
            int cols = s16(shapes, a0) + 1;
            o.height = s16(shapes, a0 + 2);
            a0 += 4;
            // channel allocation (0x9238): first channel whose last line is above y
            o.channel = new int[cols];
            int ch = 0;
            for (int c = 0; c < cols; c++) {
                while (ch < 8 && !(o.y > freeLine[ch])) {
                    ch++;
                }
                if (ch >= 8) {
                    o.channel[c] = -1;
                    continue;
                }
                o.channel[c] = ch;
                freeLine[ch] = o.y + o.height;
                ch++;
            }
            o.frames = new BufferedImage[frameCount][cols];
            for (int f = 0; f < frameCount; f++) {
                if (f > 0) {
                    a0 += 4;
                }
                for (int c = 0; c < cols; c++) {
                    o.frames[f][c] = column(shapes, a0, o.height, o.y, o.channel[c], colors, hill);
                    a0 += 4 * o.height;
                }
            }
            if (o.anim.length > 0) {
                o.timer = o.anim[0];
                o.frame = o.anim[1] / 4;
                o.animPos = 2;
            }
            objects.add(o);
        }
    }

    /** One VBL (the original moves them at 50 Hz: call twice per 25 Hz logic frame). */
    public void vbl(int camX) {
        int scroll = lastCam == Integer.MIN_VALUE ? 0 : camX - lastCam;
        lastCam = camX;
        for (Obj o : objects) {
            if (o.type == 1) {
                o.pos = (short) (o.pos + o.speed - 32 * scroll);
            } else if (o.type >= 2) {
                o.pos = (short) (o.pos + o.speed);
                if (o.anim.length > 0 && --o.timer <= 0) {
                    if (o.animPos >= o.anim.length) {
                        o.animPos = 0;
                    }
                    o.timer = o.anim[o.animPos];
                    o.frame = Math.min(o.frames.length - 1, o.anim[o.animPos + 1] / 4);
                    o.animPos += 2;
                }
            }
        }
    }

    public void draw(Graphics2D g, int camX) {
        for (Obj o : objects) {
            int x;
            if (o.type == 0) {
                x = o.pos;
            } else if (o.type == 1) {
                x = (o.pos & 0xffff) >> 7;
            } else if (o.shift == 0) {
                x = o.pos & o.mask;
            } else {
                x = (((o.pos & 0xffff) >> o.shift) + ~camX) & o.mask;
            }
            if (x > 0x1b0) {
                continue;
            }
            BufferedImage[] cols = o.frames[o.frame];
            for (int c = 0; c < cols.length; c++) {
                if (cols[c] != null) {
                    g.drawImage(cols[c], x - 0x94 + 16 * c, o.y - 0x30, null);
                }
            }
        }
    }

    private static BufferedImage column(byte[] d, int at, int h, int y0, int ch, int[][] colors, int[] hill) {
        if (ch < 0) {
            return null;
        }
        BufferedImage img = new BufferedImage(16, Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            int p = at + 4 * y;
            if (p + 3 >= d.length) {
                break;
            }
            int a = ((d[p] & 0xff) << 8) | (d[p + 1] & 0xff);
            int b = ((d[p + 2] & 0xff) << 8) | (d[p + 3] & 0xff);
            int line = y0 + y;
            for (int x = 0; x < 16; x++) {
                int v = (a >> (15 - x) & 1) | (b >> (15 - x) & 1) << 1;
                if (v != 0) {
                    int rgb = colors[ch / 2][v - 1];
                    if (ch / 2 == 3 && v == 1 && line >= HILLS_FIRST && line < HILLS_END) {
                        rgb = hill[line - HILLS_FIRST]; // COLOR29 is rewritten on these lines
                    }
                    img.setRGB(x, y, rgb);
                }
            }
        }
        return img;
    }

    /** info+$32: colours 17-19, 21-23, 25-27, 29-31 (code 0x66F2). */
    private static int[][] spriteColors(byte[] info) {
        int[][] c = new int[4][3];
        for (int k = 0; k < 12; k++) {
            int v = u16(info, 0x32 + 2 * k);
            c[k / 3][k % 3] = 0xff000000 | ((v >> 8 & 15) * 0x110000) | ((v >> 4 & 15) * 0x1100) | ((v & 15) * 0x11);
        }
        return c;
    }

    private static int u16(byte[] b, int o) {
        return o >= 0 && o + 1 < b.length ? ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff) : 0;
    }

    private static int s16(byte[] b, int o) {
        return (short) u16(b, o);
    }
}
