package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.HashMap;
import java.util.Map;

/**
 * Routines of the levels' own code (set*.bin, loaded at $108EE) ported at register level, by
 * level file id and address. The object-script natives already ported to Java are in
 * {@link bubba.entity.natives}; these are the others (block animation callbacks...).
 * <p>
 * Also 0xC67A (shared): start a block animation.
 */
final class SetCode {

    private SetCode() {
    }

    /** Ports of the level with this file id ("1", "5", "3", "4", "6"). */
    static Map<Integer, Ports.Port> forLevel(String fileId) {
        Map<Integer, Ports.Port> m = new HashMap<>();
        if (fileId.equals("1")) {
            m.put(0x11ce0, new Ports.Port() {
                @Override
                public void run(M68k c) {
                    stage1Lights(c);
                }

                @Override
                public int[] outputs() {
                    return new int[]{1, 2, 3, 4, 5, 6, 7, 10, 14};
                }
            });
        }
        return m;
    }

    private static int sw(int reg, int v) {
        return (reg & 0xffff0000) | (v & 0xffff);
    }

    private static int sb(int reg, int v) {
        return (reg & 0xffffff00) | (v & 0xff);
    }

    // ------------------------------------------------------------------ 0xC67A

    /**
     * 0xC67A: a new entry in the block animation queue $C750 (20 entries, the last free one):
     * timer 1, frame list a0, size d0, source a1, destination a2, step d1, blit size from d0.
     * Returns a6 = end of the entry (the callers set its timer at -$16(a6)), d7 = old count.
     */
    static void startAnim(M68k c) {
        GameMemory m = c.mem;
        int a6 = 0xc750;
        c.d[7] = sw(c.d[7], m.read16(a6));
        a6 += 2;
        if ((short) c.d[7] >= 0x14) {
            c.a[6] = a6;
            return;
        }
        a6 += 0x1b8;
        do {
            a6 -= 0x16;
        } while ((short) m.read16(a6) > 0);
        m.write16(0xc750, m.read16(0xc750) + 1);
        m.write16(a6, 1);
        m.write32(a6 + 2, c.a[0]);
        m.write16(a6 + 6, 0);
        m.write16(a6 + 8, c.d[0]);
        m.write32(a6 + 0xa, c.a[1]);
        m.write32(a6 + 0xe, c.a[2]);
        m.write16(a6 + 0x12, c.d[1]);
        int d0 = c.d[0];
        d0 = sw(d0, (d0 & 0xffff) >>> 1);
        d0 = sw(d0, d0 << 6);
        d0 = sw(d0, d0 + 1);
        c.d[0] = d0;
        m.write16(a6 + 0x14, d0);
        c.a[6] = a6 + 0x16;
    }

    // ------------------------------------------------------------------ stage 1

    /** One step of the game's 4-word generator at $10894 (movem.w / eor / ror / add). */
    private static int[] rng(GameMemory m, int a1) {
        int[] old = {(short) m.read16(a1), (short) m.read16(a1 + 2), (short) m.read16(a1 + 4), (short) m.read16(a1 + 6)};
        int d2 = m.read16(a1), d3 = m.read16(a1 + 2), d4 = m.read16(a1 + 4), d5 = m.read16(a1 + 6);
        d3 ^= d2;
        d4 ^= d3;
        d5 ^= d4;
        d5 = ((d5 >>> 1) | (d5 << 15)) & 0xffff;
        d2 ^= d5;
        d4 = (d4 + d2) & 0xffff;
        m.write16(a1, d2);
        m.write16(a1 + 2, d3);
        m.write16(a1 + 4, d4);
        m.write16(a1 + 6, d5);
        return old;
    }

    /** Registers after the generator: upper words from the old words (movem.w sign-extends). */
    private static void rngRegs(M68k c, GameMemory m, int a1) {
        int[] old = rng(m, a1);
        for (int k = 0; k < 4; k++) {
            c.d[2 + k] = (old[k] & 0xffff0000) | m.read16(a1 + 2 * k);
        }
    }

    /**
     * Stage 1, $11CE0: callback of a block animation (a0 = end of its queue entry). Every other
     * call it builds new frame lists at $13A4E (a random pattern, or 16 shifted ones) and starts
     * 16 one-block animations (frames from slot 512+, destinations from the table $12362).
     */
    static void stage1Lights(M68k c) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        m.write16(a[0] - 0x10, 0);
        a[1] = 0x10894;
        a[2] = 0x11e3a;
        int flip = ~m.read8(a[2]) & 0xff;
        m.write8(a[2], flip);
        if ((flip & 0x80) == 0) {
            // one random pattern shared by the 16 blocks
            rngRegs(c, m, a[1]);
            a[2] = 0x13a4e;
            d[0] = 3;
            d[1] = sb(d[1], m.read8(a[1]++));
            d[1] = sw(d[1], d[1] & 1);
            d[0] = sw(d[0], d[0] - d[1]);
            d[1] = sb(d[1], m.read8(a[1]++));
            d[1] = sw(d[1], d[1] & 1);
            d[1] = sw(d[1], d[1] + 1);
            d[3] = 0;
            d[4] = 4;
            while (true) {
                d[2] = sw(d[2], d[1]);
                d[5] = 0;
                if ((short) d[0] <= 0) {
                    d[1] = sb(d[1], m.read8(a[1]++));
                    d[1] = sw(d[1], d[1] & 3);
                    d[2] = sw(d[2], d[2] + d[1]);
                    a[3] = a[2];
                }
                do {
                    m.write8(a[2]++, 0);
                    m.write8(a[2]++, d[4]);
                    m.write8(a[2]++, 1);
                    m.write8(a[2]++, d[4]);
                    m.write8(a[2]++, 2);
                    m.write8(a[2]++, d[4]);
                    d[5] = sw(d[5], d[5] + 3 * d[4]);
                    d[2] = sw(d[2], d[2] - 1);
                } while ((d[2] & 0xffff) != 0xffff);
                d[3] = sw(d[3], d[3] + d[5]);
                if ((d[0] & 0xffff) != 0) {
                    d[3] = sw(d[3], d[3] + d[5]);
                }
                d[4] = sw(d[4], d[4] - 1);
                d[1] = sw(d[1], d[1] + 1);
                d[0] = sw(d[0], d[0] - 1);
                if ((d[0] & 0xffff) == 0xffff) {
                    break;
                }
            }
            a[5] = 0x13a4e;
            int n = a[3] - a[5];
            d[0] = ((n % 6) << 16) | ((n / 6) & 0xffff); // divu.w #6
            while (true) {
                d[0] = sw(d[0], d[0] - 1);
                if ((d[0] & 0xffff) == 0xffff) {
                    break;
                }
                a[3] -= 4;
                d[2] = m.read32(a[3]);
                a[3] -= 2;
                d[1] = sw(d[1], m.read16(a[3]));
                m.write16(a[2], d[1]);
                a[2] += 2;
                m.write32(a[2], d[2]);
                a[2] += 4;
            }
            d[3] = sw(d[3], d[3] + 4);
            m.write16(a[0] - 0x16, d[3]);
            m.write16(a[2], 0);
            a[2] += 2;
            a[5] = 0x10910;
            d[0] = sw(d[0], 0x8c);
            Calls.call(c, 0xfb58);
            d[4] = 0;
        } else {
            // 16 lists, each a random series of frames
            d[0] = sw(d[0], 0);
            d[0] = sb(d[0], m.read8(a[1]));
            d[0] = sw(d[0], d[0] + 0x100);
            m.write16(a[0] - 0x16, d[0]);
            a[0] = 0x13a4e;
            d[0] = sw(d[0], d[0] - 8);
            d[1] = 0xf;
            while (true) {
                rngRegs(c, m, a[1]);
                a[2] = a[1];
                d[2] = 6;
                d[3] = 0x7f;
                d[6] = sw(d[6], d[0]);
                a[3] = a[0];
                m.write8(a[0]++, 0);
                while (true) {
                    d[4] = sb(d[4], m.read8(a[2]++));
                    d[4] = sw(d[4], d[4] & d[3]);
                    d[4] = sw(d[4], d[4] + 8);
                    d[6] = sw(d[6], d[6] - d[4]);
                    d[6] = sw(d[6], d[6] - 0xc);
                    if ((short) d[6] < 0) {
                        break;
                    }
                    m.write8(a[0]++, d[4]);
                    m.write32(a[0], 0x01030203);
                    a[0] += 4;
                    m.write16(a[0], 0x103);
                    a[0] += 2;
                    m.write8(a[0]++, 0);
                    d[2] = sw(d[2], d[2] - 1);
                    if ((d[2] & 0xffff) == 0xffff) {
                        break;
                    }
                }
                m.write8(a[0], 0);
                a[0] = a[3] + 0x40;
                d[1] = sw(d[1], d[1] - 1);
                if ((d[1] & 0xffff) == 0xffff) {
                    break;
                }
            }
            d[4] = 0x40;
        }
        // 0x11DEE: the 16 animations
        a[0] = 0x13a4e;
        a[4] = 0x12362;
        a[5] = m.read32(0x1088c) + 0x10000;
        d[5] = 0;
        d[6] = 0xf;
        while (true) {
            a[1] = a[5];
            d[0] = sw(d[0], d[5]);
            d[0] = (d[0] & 0xffff) * 0x180;
            a[1] += (short) d[0];
            a[2] = m.read32(0x1088c);
            d[0] = 0;
            d[0] = sw(d[0], m.read16(a[4]));
            a[4] += 2;
            a[2] += d[0];
            d[0] = sw(d[0], 0x80);
            d[1] = 0;
            Calls.call(c, 0xc67a);
            if ((d[4] & 0xffff) == 0) {
                d[0] = sw(d[0], d[5]);
                d[0] = sw(d[0], (d[0] & 0xffff) >>> 2);
                d[0] = sw(d[0], d[0] + 1);
                m.write16(a[6] - 0x16, d[0]);
            }
            d[5] = sw(d[5], d[5] + 1);
            a[0] += (short) d[4];
            d[6] = sw(d[6], d[6] - 1);
            if ((d[6] & 0xffff) == 0xffff) {
                break;
            }
        }
    }
}
