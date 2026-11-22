package bubba.extract;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * NoisePacker 3 variant of the game's music ({@code *.np}) to a ProTracker "M.K." module.
 * Effects: 8 -&gt; none, 7 -&gt; A (volume slide), 5 / 6 / 7 slide parameters signed -&gt; nibbles;
 * the track table is stored channel 4 first.
 */
final class NoisePacker {

    private static final int[] PERIODS = {856, 808, 762, 720, 678, 640, 604, 570, 538, 508, 480, 453,
            428, 404, 381, 360, 339, 320, 302, 285, 269, 254, 240, 226, 214, 202, 190, 180, 170, 160, 151, 143,
            135, 127, 120, 113};

    private NoisePacker() {
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) << 8 | (b[o + 1] & 0xff);
    }

    private static int sconv(int v) {
        return v >= 0x80 ? (0x100 - v) & 0x0f : (v << 4) & 0xf0;
    }

    static byte[] toMod(byte[] b, String title) {
        int h0 = u16(b, 0), h1 = u16(b, 2), h2 = u16(b, 4), h3 = u16(b, 6);
        int ns = (h0 - 12) / 16;
        int p = 8 + ns * 16 + 4;
        int[] pos = new int[h1 / 2];
        for (int i = 0; i < pos.length; i++) {
            pos[i] = u16(b, p + 2 * i) / 8;
        }
        p += h1;
        int[] tt = new int[h2 / 2];
        for (int i = 0; i < tt.length; i++) {
            tt[i] = u16(b, p + 2 * i);
        }
        p += h2;
        int td = p, tdEnd = Math.min(b.length, p + h3);
        int npat = h2 / 8;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] t = title.getBytes(StandardCharsets.ISO_8859_1);
        out.write(t, 0, Math.min(20, t.length));
        for (int i = t.length; i < 20; i++) {
            out.write(0);
        }
        for (int i = 0; i < 31; i++) {
            int ln = 0, ft = 0, vol = 0, ls = 0, ll = 1;
            if (i < ns) {
                int s = 8 + 16 * i;
                ft = b[s] & 0xff;
                vol = b[s + 1] & 0xff;
                ln = u16(b, s + 6);
                ll = u16(b, s + 12);
                ls = u16(b, s + 14);
            }
            out.write(new byte[22], 0, 22);
            word(out, ln);
            out.write(ft & 15);
            out.write(Math.min(vol, 64));
            word(out, ls);
            word(out, Math.max(ll, 1));
        }
        out.write(pos.length);
        out.write(127);
        for (int i = 0; i < 128; i++) {
            out.write(i < pos.length ? pos[i] : 0);
        }
        out.write('M');
        out.write('.');
        out.write('K');
        out.write('.');
        for (int pi = 0; pi < npat; pi++) {
            int[][][] ch = new int[4][][];
            for (int c = 0; c < 4; c++) {
                ch[c] = track(b, td, tdEnd, td + tt[pi * 4 + 3 - c]);
            }
            for (int r = 0; r < 64; r++) {
                for (int c = 0; c < 4; c++) {
                    int[] e = ch[c][r];
                    int per = e[0], ins = e[1];
                    out.write((ins & 0x10) | (per >> 8));
                    out.write(per & 0xff);
                    out.write(((ins & 15) << 4) | e[2]);
                    out.write(e[3]);
                }
            }
        }
        out.write(b, tdEnd, b.length - tdEnd);
        return out.toByteArray();
    }

    private static void word(ByteArrayOutputStream o, int v) {
        o.write(v >> 8);
        o.write(v);
    }

    /** One track: 64 rows of (period, instrument, effect, parameter). */
    private static int[][] track(byte[] b, int td, int end, int k) {
        int[][] rows = new int[64][4];
        int r = 0;
        while (r < 64 && k < end) {
            int c = b[k] & 0xff;
            if (c >= 0x80) {
                r += 0x100 - c;
                k++;
                continue;
            }
            int c1 = c, c2 = k + 1 < end ? b[k + 1] & 0xff : 0, c3 = k + 2 < end ? b[k + 2] & 0xff : 0;
            k += 3;
            int note = c1 >> 1, ins = ((c1 & 1) << 4) | (c2 >> 4), fx = c2 & 15, pr = c3;
            if (fx == 8) {
                fx = 0;
            } else if (fx == 7) {
                fx = 0xa;
                pr = sconv(pr);
            } else if (fx == 5 || fx == 6) {
                pr = sconv(pr);
            } else if (fx == 0) {
                pr = 0;
            }
            int per = note >= 1 && note <= 36 ? PERIODS[note - 1] : 0;
            rows[r] = new int[]{per, ins, fx, pr};
            r++;
        }
        return rows;
    }
}
