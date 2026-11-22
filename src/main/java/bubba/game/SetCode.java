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
        levelPorts(fileId, m);
        startRoutines(fileId, m);
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

    // ------------------------------------------------------------------ helpers

    private static final int[] ALL = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};

    private static void add(Map<Integer, Ports.Port> m, int addr, M68k.Hook h) {
        m.put(addr, new Ports.Port() {
            @Override
            public void run(M68k c) {
                h.run(c);
            }

            @Override
            public int[] outputs() {
                return ALL;
            }
        });
    }

    static void levelPorts(String fileId, Map<Integer, Ports.Port> m) {
        switch (fileId) {
            case "5" -> {
                add(m, 0x12c18, SetCode::s2Callback);
                add(m, 0x12c12, c -> {
                    c.mem.write16(c.a[0] - 0x16, 1);
                    s2Callback(c);
                });
                add(m, 0x1302e, SetCode::s2Bounce);
            }
            case "3" -> {
                add(m, 0x12cf0, SetCode::s3Check);
                add(m, 0x12d26, SetCode::s3Callback);
                add(m, 0x15836, SetCode::s3Native);
            }
            case "4" -> {
                add(m, 0x12586, c -> {
                    c.mem.write16(c.a[0] - 0x16, 1);
                    s4Current(c);
                });
                add(m, 0x1323a, c -> {
                    c.mem.write32(c.a[0] + 2, 0);
                    s4Bubbles(c);
                });
                add(m, 0x1323e, SetCode::s4Bubbles);
            }
            case "6" -> {
                add(m, 0x12622, c -> {
                    c.a[1] = 0x12d5e;
                    c.mem.write32(c.a[1], 0);
                });
                add(m, 0x129ee, SetCode::s5Callback);
                add(m, 0x12fcc, c -> {
                    c.a[0] = 0x1112a;
                    c.mem.write32(c.a[0], c.a[5]);
                    c.a[0] += 4;
                    c.mem.write8(c.a[0], 0xff);
                });
                add(m, 0x1316c, c -> { // Stix put on the object a5
                    GameMemory g = c.mem;
                    int o = c.a[5], st = 0x10988;
                    c.a[0] = st;
                    c.d[0] = sw(c.d[0], g.read16(o + 0xa));
                    c.d[0] = sw(c.d[0], (short) c.d[0] >> 1);
                    g.write16(st + 0xa, c.d[0]);
                    g.write32(st + 2, g.read32(o + 2));
                    g.write32(st + 6, g.read32(o + 6));
                    g.write32(st + 0x28, 0);
                    g.write32(st + 0x2c, 0);
                    g.write16(st + 0x9c, 4);
                    g.write8(st + 0x83, g.read8(st + 0x83) & ~2);
                    g.write8(st + 0x9c, 0);
                    g.write32(st + 0x44, 0);
                    g.write16(o + 0xa, 0xffff);
                });
                add(m, 0x1315e, c -> {
                    c.mem.write32(c.a[5] + 2, c.mem.read32(0x1098a));
                    c.mem.write32(c.a[5] + 6, c.mem.read32(0x1098e));
                });
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ stage 2

    /** $12C18: callback; the rare part (switch of $10A42, objects reloaded) runs on the interpreter. */
    static void s2Callback(M68k c) {
        GameMemory m = c.mem;
        m.write16(c.a[0] - 0x10, 0);
        c.a[0] = 0x10ad9;
        if (m.read8(c.a[0]) != 0) {
            Calls.call(c, 0x12c24);
        }
    }

    /** $1302E: object native: bouncing, speed kept in +$44. */
    static void s2Bounce(M68k c) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        int o = a[5];
        if (m.read32(o + 0x28) == 0) {
            a[0] = 0x12fac;
            d[0] = 6;
            int v = ~m.read8(o + 0x30) & 0xff;
            m.write8(o + 0x30, v);
            if ((v & 0x80) != 0) {
                a[0] = 0x12fca;
                d[0] = sw(d[0], -6);
            }
            m.write32(o + 0xc, a[0]);
            m.write16(o + 0x34, 0);
            m.write16(o + 0x28, d[0]);
            m.write32(o + 0x2c, 0xfffc0000);
        }
        if (m.read8(o + 0x64) != 0) {
            d[0] = m.read32(o + 0x44);
            if (d[0] > 0x10000) {
                d[0] = -d[0];
                d[1] = d[0] >> 2;
                d[0] -= d[1];
                m.write32(o + 0x2c, d[0]);
            }
        }
        m.write32(o + 0x44, m.read32(o + 0x2c));
    }

    // ------------------------------------------------------------------ stage 3

    /**
     * $12CF0: clears the level's work bytes, then a copy-protection check: the 5 longs of code at
     * $15836 must xor to $B043C7E6, else the return address is shifted (the game crashes).
     */
    static void s3Check(M68k c) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        a[0] = 0x12e90;
        m.write32(a[0], 0);
        a[0] += 4;
        m.write8(a[0], 0);
        a[0] = 0x12e9e;
        for (int i = 0; i < 6; i++) {
            m.write8(a[0]++, 0);
        }
        a[0]++;
        for (int k = 0; k < 5; k++) {
            d[k] = m.read32(0x15836 + 4 * k);
        }
        d[0] ^= d[1];
        d[0] ^= d[2];
        d[0] ^= d[3];
        d[0] ^= d[4];
        d[0] = sb(d[0], d[0] != 0xb043c7e6 ? 0xff : 0);
        m.write8(a[7] + 3, m.read8(a[7] + 3) + (d[0] & 0xff));
    }

    /** $15836: object native: copy-protection check on the code (a4 - 1 when it fails). */
    static void s3Native(M68k c) {
        GameMemory m = c.mem;
        c.a[0] = m.read32(0x1113a);
        c.d[0] = sb(c.d[0], m.read32(c.a[0] + 0x4098) != 0x38891291 ? 0xff : 0);
        c.d[0] = sw(c.d[0], (byte) c.d[0]);
        c.a[4] += (short) c.d[0];
    }

    /**
     * $12D26: callback (timer 1): the graphics of the 3 lava blocks follow the object $10AEA's
     * y; while $12E94 is set, the screen shakes ($1090A..$1090F from the tables $12E48) and the
     * sound channel found in the driver ($108EA + $7BA, end mark $FFFE) has its volume ramped.
     */
    static void s3Callback(M68k c) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        m.write16(a[0] - 0x16, 1);
        m.write16(a[0] - 0x10, 0);
        d[0] = m.read32(0x10aea);
        if (d[0] != 0) {
            a[1] = d[0];
            d[0] = sw(d[0], m.read16(a[1] + 6) & 7);
            a[2] = m.read32(0x1088c) + 0xdd80;
            d[0] = sw(d[0], d[0] << 7);
            a[2] += (short) d[0];
            a[3] = 0x10dd0;
            a[1] = m.read32(0x10890);
            d[0] = sw(d[0], m.read16(0x10a64) & 0xfffc);
            a[1] = a[1] + 2 + (short) d[0];
            d[0] = m.read16(a[3]);
            a[3] += 2;
            d[1] = m.read16(a[3]);
            a[3] += 2;
            m.write32(a[1] + d[1], a[2]);
            a[2] += 0x400;
            m.write32(a[1] + d[0], a[2]);
            d[0] = sw(d[0], m.read16(a[3]));
            a[3] += 2;
            a[2] += 0x400;
            m.write32(a[1] + d[0], a[2]);
        }
        d[0] = sb(d[0], m.read8(0x12e94));
        if ((d[0] & 0xff) != 0) {
            d[0] = sw(d[0], (byte) d[0]);
            d[0] = sw(d[0], d[0] - 1);
            d[0] = sw(d[0], d[0] * 2);
            a[2] = 0x12e48;
            d[1] = sw(d[1], m.read16(a[2] + 2 + (short) d[0]));
            d[2] = sw(d[2], m.read16(a[2] + (short) d[0]));
            d[0] = sw(d[0], d[0] << 2);
            a[3] = a[2] + 0x28 + (short) d[0];
            d[1] = sw(d[1], d[1] - d[2]);
            a[2] += (short) d[2];
            a[0] = 0x1090a;
            a[1] = 0x12e90;
            d[0] = sw(d[0], m.read16(a[1]));
            m.write8(a[0]++, 0);
            m.write8(a[0]++, m.read8(a[2] + (short) d[0]));
            d[0] = sw(d[0], d[0] + 1);
            if ((short) d[0] >= (short) d[1]) {
                d[0] = 0;
            }
            m.write16(a[1], d[0]);
            a[1] += 2;
            d[0] = 1;
            int v = ~m.read16(a[1]) & 0xffff;
            m.write16(a[1], v);
            if ((v & 0x8000) == 0) {
                d[0] = 0;
            }
            m.write16(a[0], d[0]);
            a[0] += 2;
            d[0] = sw(d[0], -d[0]);
            m.write16(a[0], d[0]);
            m.write8(a[0] - 0x4c, 0xff);
        }
        a[2] = 0x12ea6;
        if (m.read16(a[2]) == 0) {
            return;
        }
        a[0] = m.read32(0x108ea) + 0x7ba;
        a[1] = 0xdff096;
        if (!findChannel(c, m)) {
            return;
        }
        a[0] += 0xa;
        d[0] = sb(d[0], m.read8(0x12e94));
        if ((d[0] & 0xff) == 0) {
            int t = (short) (m.read16(a[0]) - 1);
            m.write16(a[0], t);
            if (t < 0) {
                return;
            }
            m.write16(a[1] + 2, t);
            if (t > 0) {
                return;
            }
            m.write16(a[0] - 0xa, 0);
            m.write16(a[2], 0);
            return;
        }
        d[0] = sw(d[0], m.read16(a[3]));
        a[3] += 2;
        d[1] = sw(d[1], m.read16(a[3]));
        a[3] += 2;
        m.write16(a[2], m.read16(a[2]) + d[1]);
        if ((short) d[1] < 0) {
            if ((short) d[0] > (short) m.read16(a[2])) {
                m.write16(a[2], d[0]);
            }
        } else if ((short) d[0] < (short) m.read16(a[2])) {
            m.write16(a[2], d[0]);
        }
        m.write16(a[1], m.read16(a[2]));
        a[1] += 2;
        d[0] = sw(d[0], m.read16(a[3]));
        a[3] += 2;
        d[1] = sw(d[1], m.read16(a[3]));
        a[3] += 2;
        boolean up = (short) d[1] >= 0;
        d[1] = sw(d[1], d[1] + m.read16(a[0]));
        if (up ? (short) d[1] > (short) d[0] : (short) d[1] < (short) d[0]) {
            d[1] = sw(d[1], d[0]);
        }
        m.write16(a[0], d[1]);
        m.write16(a[1], d[1]);
    }

    /** The driver's channel records ($E bytes, 4 of them) up to the end mark $FFFE (dbeq). */
    private static boolean findChannel(M68k c, GameMemory m) {
        c.d[0] = 3;
        while (true) {
            c.a[0] += 0xe;
            c.a[1] += 0x10;
            if (m.read16(c.a[0]) == 0xfffe) {
                return true;
            }
            c.d[0] = sw(c.d[0], c.d[0] - 1);
            if ((c.d[0] & 0xffff) == 0xffff) {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ stage 4

    /** $1258C: the current's sound (sound $44 / $47 by the direction of $10A5C, every 3 calls). */
    static void s4Current(M68k c) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        m.write16(a[0] - 0x10, 0);
        a[2] = 0x125f7;
        if ((byte) m.read8(a[2]) > 0) {
            m.write8(a[2], m.read8(a[2]) - 1);
        }
        a[0] = m.read32(0x108ea) + 0x7ba;
        a[1] = 0xdff098;
        d[0] = 3;
        int last;
        while (true) {
            a[0] += 0xe;
            a[1] += 0x10;
            last = (short) m.read16(a[0]);
            if (last <= -2) {
                break;
            }
            d[0] = sw(d[0], d[0] - 1);
            if ((d[0] & 0xffff) == 0xffff) {
                break;
            }
        }
        if (last <= -2) {
            d[1] = sb(d[1], last == -2 ? 0xff : 0);
            d[0] = sb(d[0], m.read8(0x10a5c));
            boolean same = false;
            if ((d[0] & 0xff) != 0) {
                d[0] = sb(d[0], (d[0] & 0x80) != 0 ? 0xff : 0);
                same = (d[0] & 0xff) == (d[1] & 0xff);
            }
            if (same) {
                return;
            }
            d[1] = 0;
            m.write16(a[0] + 0xa, 0);
            m.write16(a[0], 0);
            m.write16(a[1], 0);
        }
        d[0] = 0x44;
        d[1] = sb(d[1], m.read8(0x10a5c));
        if ((d[1] & 0xff) == 0) {
            return;
        }
        if ((d[1] & 0x80) == 0) {
            d[0] = 0x47;
        }
        if ((byte) m.read8(a[2]) > 0) {
            return;
        }
        a[5] = 0x10910;
        d[1] = 0;
        m.write8(a[2], 3);
        a[0] = m.read32(0x108ea);
        Calls.call(c, a[0]);
    }

    /** $1323E: sound $51 at the object a0's particle position (a fake object built on the stack). */
    static void s4Bubbles(M68k c) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        d[0] = 0x51;
        d[1] = 0;
        d[2] = 0xffffffe0;
        // $14F80
        a[7] -= 8;
        m.write32(a[7], a[0]);
        m.write32(a[7] + 4, a[5]);
        a[7] -= 8;
        a[5] = a[7];
        m.write16(a[5] + 2, m.read16(a[0] + 0x38));
        m.write16(a[5] + 6, m.read16(a[0] + 0x3c));
        a[0] = m.read32(0x108ea);
        Calls.call(c, a[0] + 2);
        a[7] += 8;
        a[0] = m.read32(a[7]);
        a[5] = m.read32(a[7] + 4);
        a[7] += 8;
    }

    // ------------------------------------------------------------------ stage 5

    /** $129EE: callback; the special sequences ($12D52 / $12D57 set) run on the interpreter. */
    static void s5Callback(M68k c) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        m.write16(a[0] - 0x16, 1);
        m.write16(a[0] - 0x10, 0);
        d[0] = sb(d[0], m.read8(0x12d52));
        if ((d[0] & 0xff) != 0) {
            int v = (byte) d[0];
            c.setCcr((v < 0 ? 8 : 0)); // move.b flags: N, Z clear
            Calls.call(c, 0x12a0e);
            return;
        }
        d[0] = sb(d[0], m.read8(0x12d57));
        if ((d[0] & 0xff) == 0) {
            return;
        }
        a[0] = 0x12d57;
        m.write8(a[0], 0);
        Calls.call(c, 0x129a2);
    }

    // ------------------------------------------------------------------ level start / respawn routines

    /**
     * Copy protection of the set code: 5 longs at addr must xor to {@code expected}, else the
     * return address is shifted by -1 (the game crashes). The movem.l source is PC-relative:
     * the disassembly shows it 2 bytes too low.
     */
    private static void check(M68k c, int addr, int expected) {
        GameMemory m = c.mem;
        int[] d = c.d;
        for (int k = 0; k < 5; k++) {
            d[k] = m.read32(addr + 4 * k);
        }
        d[0] ^= d[1];
        d[0] ^= d[2];
        d[0] ^= d[3];
        d[0] ^= d[4];
        d[0] = sb(d[0], d[0] != expected ? 0xff : 0);
        m.write8(c.a[7] + 3, m.read8(c.a[7] + 3) + (d[0] & 0xff));
    }

    /** Start of the block animation of callback {@code routine} (a0 = $10B1A, timer set after). */
    private static void startCallback(M68k c, int routine) {
        c.a[0] = 0x10b1a;
        c.a[1] = -routine;
        c.d[0] = 0;
        c.d[1] = 0;
        Calls.call(c, 0xc67a);
    }

    static void startRoutines(String fileId, Map<Integer, Ports.Port> m) {
        switch (fileId) {
            case "1" -> {
                add(m, 0x11caa, c -> {
                    GameMemory g = c.mem;
                    c.d[0] = sb(c.d[0], g.read8(0x10a42));
                    if ((c.d[0] & 0xff) != 0) {
                        Calls.call(c, 0x13636);
                    }
                    c.a[0] = 0x10ac4;
                    g.write16(c.a[0], 0x19);
                    c.a[0] = 0x11e3a;
                    g.write8(c.a[0], 0);
                    startCallback(c, 0x11ce0);
                    g.write16(c.a[6] - 0x16, 0x32);
                });
                add(m, 0x12346, c -> check(c, 0x13e96, 0xb043ac46));
            }
            case "5" -> {
                add(m, 0x12612, c -> {
                    GameMemory g = c.mem;
                    int[] a = c.a;
                    c.d[0] = sb(c.d[0], g.read8(0x108e8));
                    if ((c.d[0] & 0xff) == 0) {
                        a[0] = 0x12c4a;
                        g.write32(a[0], 0xffffffff);
                        a[0] += 4;
                        for (int i = 0; i < 6; i++) {
                            g.write8(a[0]++, 0);
                        }
                    }
                    a[0] = 0x10906;
                    g.write32(a[0], g.read32(a[0]) - 0x1e00000);
                    g.write32(a[0] - 0x28, g.read32(a[0] - 0x28) - 0x1e00000);
                    a[2] = g.read32(0x10888);
                    a[1] = 0x10b0c;
                    g.write32(a[1], a[2]);
                    a[1] += 4;
                    a[2] += 0x2148;
                    g.write32(a[1], a[2]);
                    a[1] += 4;
                    startCallback(c, 0x12c12);
                });
                add(m, 0x1258c, c -> { // the map half chosen by $10A42, sound filter, check
                    GameMemory g = c.mem;
                    int[] d = c.d, a = c.a;
                    a[0] = 0x12c55;
                    for (int i = 0; i < 7; i++) {
                        g.write8(a[0]++, 0);
                    }
                    g.write8(a[0]++, g.read8(0x12c51));
                    for (int i = 0; i < 9; i++) {
                        g.write8(a[0]++, 0);
                    }
                    a[1] = 0x108e2;
                    g.write8(a[1], 0);
                    d[1] = 0;
                    d[2] = sw(d[2], 0x80);
                    a[0] = 0x10b0c;
                    d[0] = sw(d[0], g.read16(0x10a42));
                    if ((short) d[0] >= 0) {
                        a[0] += 4;
                        d[1] = 2;
                        d[2] = sw(d[2], d[2] * 2);
                    }
                    a[2] = g.read32(0x108ea);
                    if ((g.read8(a[2] + 0x6ae) & 0x80) != 0) {
                        g.write16(a[1] + 2, d[2]);
                        g.write8(0xbfe001, g.read8(0xbfe001) & 0xfd); // CIA: audio filter
                        g.write8(0xbfe001, g.read8(0xbfe001) | (d[1] & 0xff));
                    }
                    a[1] = 0x10888;
                    g.write32(a[1], g.read32(a[0]));
                    check(c, 0x15f40, 0xb043cf1c);
                });
            }
            case "3" -> add(m, 0x11376, c -> {
                c.a[0] = 0x10ac4;
                c.mem.write16(c.a[0], 0x19);
                c.mem.write16(0x12d26 - 0x242c, 0x10);
                startCallback(c, 0x12d26);
            });
            case "4" -> {
                add(m, 0x12566, c -> {
                    c.mem.write8(0x12586 + 0x71, 0);
                    c.mem.write16(0x12586 - 0x1c8c, 0x10);
                    startCallback(c, 0x12586);
                });
                add(m, 0x12540, c -> {
                    c.a[0] = 0x10ad4;
                    c.mem.write32(c.a[0], 0x03060700);
                    check(c, 0x14720, 0xb043b4fc);
                });
            }
            case "6" -> {
                add(m, 0x12386, c -> {
                    c.d[0] = sb(c.d[0], c.mem.read8(0x10a43));
                    if ((c.d[0] & 0xff) != 0) {
                        Calls.call(c, 0x16d2e);
                    }
                    s5Water(c);
                    startCallback(c, 0x129ee);
                });
                add(m, 0x137f8, SetCode::s5Water);
                add(m, 0x129a2, SetCode::s5Blocks);
                add(m, 0x123aa, c -> {
                    GameMemory g = c.mem;
                    int[] a = c.a;
                    a[1] = 0x108fa;
                    g.write32(a[1], g.read32(0x1114a));
                    g.write32(a[1] + 8, g.read32(0x1114e));
                    a[0] = 0x10a66;
                    g.write8(a[0], 0);
                    g.write8(a[0] + 0x22ec, 0);
                    g.write8(a[0] + 0x22ef, 0);
                    g.write8(a[0] + 0x22ee, 0);
                    g.write8(a[0] + 0x22ed, 0);
                    g.write8(a[0] + 0x22f1, 0);
                    g.write32(a[0] + 0x22f8, 0);
                    s5Blocks(c);
                    a[0] = g.read32(0x1113e);
                    g.write16(a[0] + 8, 0x704);
                    g.write16(a[0] + 0xa, 0x806);
                    g.write16(a[0] + 0x10, 0x900);
                    g.write16(a[0] + 0x16, 0xa60);
                    check(c, 0x16df2, 0xb043dda2);
                    // 0x1380E
                    a[0] = 0x10a60;
                    g.write16(a[0], 0x40);
                    a[0] += 2;
                    g.write16(a[0], g.read16(0x12d58));
                    g.write32(a[0] - 0x1c6, g.read32(0x12d5a));
                });
            }
            default -> {
            }
        }
    }

    /** Stage 5, $137F8. */
    static void s5Water(M68k c) {
        GameMemory g = c.mem;
        c.a[0] = 0x10a62;
        g.write16(c.a[0] + 0x22f6, g.read16(c.a[0]));
        g.write16(c.a[0], 0);
        g.write32(c.a[0] + 0x22f8, g.read32(0x1089c));
        g.write32(c.a[0] - 0x1c6, 0);
    }

    /** Stage 5, $129A2: graphics pointers of 16 + 4 * 8 blocks from the list $1290C. */
    static void s5Blocks(M68k c) {
        GameMemory g = c.mem;
        int[] d = c.d, a = c.a;
        a[0] = 0x1290c;
        a[1] = g.read32(0x10890);
        a[2] = 0x10e4c;
        a[3] = g.read32(0x1088c);
        d[0] = g.read16(a[2]);
        a[2] += 2;
        a[4] = a[1] + 2 + d[0];
        for (int i = 0; i < 16; i++) {
            d[3] = g.read16(a[0]);
            a[0] += 2;
            d[3] <<= 7;
            a[5] = a[3] + d[3];
            g.write32(a[4], a[5]);
            a[4] += 4;
        }
        d[2] = 0xffff;
        d[1] = 3;
        for (int k = 0; k < 4; k++) {
            d[0] = sw(d[0], g.read16(a[2]));
            a[2] += 2;
            a[4] = a[1] + 2 + d[0];
            for (int i = 0; i < 8; i++) {
                d[3] = g.read16(a[0]);
                a[0] += 2;
                d[3] <<= 7;
                a[5] = a[3] + d[3];
                g.write32(a[4], a[5]);
                a[4] += 4;
                g.write32(a[4], a[5]);
                a[4] += 4;
            }
            d[2] = 0xffff;
            d[1] = sw(d[1], d[1] - 1);
        }
    }
}
