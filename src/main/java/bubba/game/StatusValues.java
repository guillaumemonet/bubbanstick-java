package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * 0x872A: the status bar values, once per frame. Exact port.
 * <ul>
 *   <li><b>score</b>: the points of the frame ($10AB0..$10AB2, BCD) are added to the shown score
 *       ($108B8..$108BA) and cleared; its 6 digits are drawn into the sprite data of the bar
 *       ((L_082A6) + $1440, glyphs 8x12 at $45FBC, 2 planes byte-interleaved: movep);</li>
 *   <li><b>energy</b>: change $10AB6 applied to $108BE (max 6, box glyphs $45F8C / $45FA4);
 *       at 0 the hero dies ($10ABA, $10AC8 = $1220, $10ABB = last life, $10AB4 = -1 life);
 *       $10AB8 counts the frames before the change is cleared;</li>
 *   <li><b>lives</b>: change $10AB4 applied to $108BC (0..9), digit at +$1585;</li>
 *   <li><b>air gauge</b> (+$15F8, 4 sprites of 29 lines from $4E14C / $4E31C): $10AC0 = level
 *       (high byte) / sub-step, $10ABE = delay; out of the water ($10916 - $10986 + 12 &gt;
 *       $10A5E) it fills, under water it empties and at 0 Bubba is hurt (0xD850);</li>
 *   <li><b>gauge picture</b>: $10AC8 (wanted) / $10AC6 (shown) / $10AC2 (timer).</li>
 * </ul>
 */
final class StatusValues {

    private StatusValues() {
    }

    static void register(Map<Integer, Ports.Port> ports) {
        ports.put(0x872a, StatusValues::run);
    }

    private static int w(GameMemory m, int a) {
        return (short) m.read16(a);
    }

    /** movem.l (src), d2-d7 then movep.l to dst + 0, 8, ... $28: 24 bytes to every other byte. */
    private static void glyph(GameMemory m, int src, int dst) {
        for (int i = 0; i < 24; i++) {
            m.write8(dst + 8 * (i >> 2) + 2 * (i & 3), m.read8(src + i));
        }
    }

    static void run(M68k c) {
        GameMemory m = c.mem;
        int a6 = m.read32(0x82a6);
        score(m, a6);
        energy(m, a6);
        lives(m, a6);
        air(c, m, a6);
        gauge(m, a6);
    }

    private static void score(GameMemory m, int a6) {
        if (m.read32(0x10ab0) == 0) {
            return;
        }
        boolean x = false;
        for (int i = 2; i >= 0; i--) { // abcd -(a0), -(a1)
            int s = m.read8(0x10ab0 + i), d = m.read8(0x108b8 + i);
            int t = (d & 0x0f) + (s & 0x0f) + (x ? 1 : 0);
            if (t > 9) {
                t += 6;
            }
            t += (d & 0xf0) + (s & 0xf0);
            x = t > 0x99;
            if (x) {
                t -= 0xa0;
            }
            m.write8(0x108b8 + i, t);
        }
        m.write32(0x10ab0, 0);
        int a2 = a6 + 0x1440;
        for (int i = 0; i < 3; i++) {
            int v = m.read8(0x108b8 + i);
            int lo = v & 15, hi = v - lo;
            glyph(m, 0x45fbc + hi + (hi >> 1), a2);
            glyph(m, 0x45fbc + lo * 24, a2 + 1);
            a2 += 0x6c;
        }
    }

    private static void energy(GameMemory m, int a6) {
        int d0 = w(m, 0x10ab6);
        if (d0 == 0) {
            return;
        }
        int a1, d1, d2;
        boolean draw = true;
        if (d0 < 0) {
            a1 = 0x45fa4;
            d1 = (short) (m.read16(0x108be) + d0);
            d2 = d1;
            if (d1 <= 0) {
                m.write16(0x10ac8, 0x1220);
                m.write8(0x10aba, 0xff);
                m.write16(0x10ab8, 0);
                boolean last = w(m, 0x108bc) <= 0;
                m.write8(0x10abb, last ? 0xff : 0);
                if (!last) {
                    m.write16(0x10ab4, 0xffff);
                }
            }
        } else {
            a1 = 0x45f8c;
            d2 = w(m, 0x108be);
            d1 = (short) (d2 + d0);
            if (d1 > 6) {
                m.write16(0x10ab8, 0);
                draw = false;
            }
        }
        if (draw) {
            m.write16(0x108be, d1);
            int u = d2 & 0xffff;
            int a2 = a6 + 0x1478 + (u & 1) + 0x6c * (u >>> 1);
            glyph(m, a1, a2);
        }
        int t = (short) (m.read16(0x10ab8) - 1);
        m.write16(0x10ab8, t);
        if (t <= 0) {
            m.write16(0x10ab6, 0);
            m.write16(0x10ab8, 0);
        }
    }

    private static void lives(GameMemory m, int a6) {
        int d0 = m.read16(0x10ab4);
        if (d0 == 0) {
            return;
        }
        m.write16(0x10ab4, 0);
        d0 = (short) (d0 + m.read16(0x108bc));
        if (d0 < 0) {
            return;
        }
        d0 = Math.min(d0, 9);
        m.write16(0x108bc, d0);
        glyph(m, 0x45fbc + 24 * d0, a6 + 0x1585);
    }

    private static void air(M68k c, GameMemory m, int a6) {
        int src, d0;
        if (m.read16(0x10abe) != 0) {
            m.write16(0x10abe, m.read16(0x10abe) - 1);
        } else if ((short) (m.read16(0x10916) - m.read16(0x10986) + 0xc - m.read16(0x10a5e)) > 0) {
            // under water: the gauge empties
            int v = m.read16(0x10ac0);
            if (v == 0) {
                if (m.read16(0x10910 + 0x3a) == 0) {
                    int[] regs = c.saveRegisters();
                    c.d[6] = 0;
                    c.a[5] = 0x10910;
                    Calls.call(c, 0xd850);
                    int d6 = c.d[6], a5 = c.a[5];
                    c.restoreRegisters(regs);
                    c.d[6] = d6; // d0 / a0 / a6 are saved around the call, not d6 / a5
                    c.a[5] = a5;
                }
                return;
            }
            m.write16(0x10ac0, v - 0x10);
            if ((v & 0xff) == 0) {
                return;
            }
            d0 = m.read8(0x10ac0);
            src = 0x4e31c;
            copyRow(m, a6, src, d0);
            return;
        }
        // out of the water (or delay running): the gauge fills
        if (w(m, 0x10ac0) >= 0x1d00) {
            return;
        }
        d0 = m.read8(0x10ac0);
        m.write8(0x10ac0, d0 + 1);
        m.write8(0x10ac1, 0);
        copyRow(m, a6, 0x4e14c, d0);
    }

    /** 0x8916: one line (4 longs) of the gauge picture into the 4 sprites. */
    private static void copyRow(GameMemory m, int a6, int src, int line) {
        int d0 = (line << 2) & 0xffff;
        int a1 = a6 + 0x15f8 + (short) d0;
        d0 = ((d0 << 2) + m.read16(0x10ac6)) & 0xffff;
        int a0 = src + (short) d0;
        m.write32(a1, m.read32(a0));
        m.write32(a1 + 0x78, m.read32(a0 + 4));
        m.write32(a1 + 0xf0, m.read32(a0 + 8));
        m.write32(a1 + 0x168, m.read32(a0 + 12));
    }

    private static void gauge(GameMemory m, int a6) {
        int want = m.read16(0x10ac8);
        if (m.read16(0x10ac6) == want) {
            int d0 = w(m, 0x10ac2);
            if (d0 <= 0) {
                return;
            }
            d0--;
            if (d0 == 0) {
                m.write16(0x10ac8, m.read16(0xbda4) == 0x14 ? 0xe80 : 0);
            }
            m.write16(0x10ac2, d0);
            return;
        }
        m.write16(0x10ac6, want);
        m.write16(0x10ac2, want == 0xae0 ? 0x32 : 0);
        int a0 = 0x4e14c + (short) want;
        int a1 = a6 + 0x15f8;
        int level = m.read8(0x10ac0);
        int rows = level;
        for (int pass = 0; pass < 2; pass++) {
            if (rows > 29) {
                throw new IllegalStateException("air gauge: " + rows + " lines");
            }
            for (int r = 0; r < rows; r++) {
                m.write32(a1, m.read32(a0));
                m.write32(a1 + 0x78, m.read32(a0 + 4));
                m.write32(a1 + 0xf0, m.read32(a0 + 8));
                m.write32(a1 + 0x168, m.read32(a0 + 12));
                a0 += 16;
                a1 += 4;
            }
            rows = (0x1d - level) & 0xff;
            a0 += 0x1d0;
        }
    }
}
