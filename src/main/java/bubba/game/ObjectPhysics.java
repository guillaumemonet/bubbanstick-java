package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * Physics of the objects of behaviour engine 8 (opcode $04, 0xEAB6) and the map / platform
 * probes it uses (0xF3E6, 0xF2C2). Exact ports; positions are 16.16 (int part = high word).
 * Work variables of the original kept in memory: $CAC2 slide flag, $CAC4 y probed, $CAC8
 * attributes, $CACA platform top, $CACC/$CAD0 platform velocity, $CAD4 platform search flag,
 * $CAD6 y for the platform search.
 */
final class ObjectPhysics {

    private ObjectPhysics() {
    }

    static void register(Map<Integer, Ports.Port> ports) {
        ports.put(0xf3e6, port(ObjectPhysics::groundProbe, 0));
        ports.put(0xf2c2, port(ObjectPhysics::slopeProbe, 0, 3));
    }

    private static Ports.Port port(M68k.Hook h, int... outputs) {
        return new Ports.Port() {
            @Override
            public void run(M68k c) {
                h.run(c);
            }

            @Override
            public int[] outputs() {
                return outputs;
            }
        };
    }

    private static int w(GameMemory m, int a) {
        return (short) m.read16(a);
    }

    // ------------------------------------------------------------------ 0xEAB6

    private static int probe(M68k c, int x, int y) {
        c.d[0] = x;
        c.d[1] = y;
        groundProbe(c);
        return c.d[0] & 0xffff;
    }

    /** Snap of a coordinate (int part) against a block edge, before / after (+$14/$18 or $16/$1A). */
    private static int snapAfter(int v, int off, int size) {
        return (short) ((((v + off + size - 16) | 15) - off - size) + 1);
    }

    private static int snapBefore(int v, int off) {
        return (short) (((v + off + 16) & 0xfff0) - off);
    }

    /**
     * Engine 8 opcode $04 (n, gravity.l): one physics step. Horizontal move by velocity + the
     * pushes +$40/+$3C (stops at walls), standing on a moving platform (+$48), gravity, slopes
     * (slide speed $1089C on blocks with bit 8), ground / ceiling / ladders and the special
     * blocks (bit 2 = bounce up at -12, bit 6 = springs: they push up, fire held = higher), all
     * with the probes 0xF3E6 / 0xF2C2. Returns the script pointer for the end of the frame.
     */
    static int step(M68k c, int o, int a4) {
        GameMemory m = c.mem;
        int sx = c.d[7], sa3 = c.a[3];
        m.write16(o + 0x9e, 0);
        m.write8(0xcac2, 0);
        if (m.read8(o + 0x65) != 0) {
            int v = m.read32(o + 0x28);
            if (v != 0) {
                v = Math.max(-0x20000, Math.min(0x20000, v)); // 0xEE60
            }
            m.write32(o + 0x28, v);
        }
        // ---- horizontal
        int vx = m.read32(o + 0x28) + m.read32(o + 0x40) + m.read32(o + 0x3c);
        int x = m.read32(o + 0x02) + vx;
        int xi = (short) (x >> 16), xf = x & 0xffff;
        m.write8(0xcad4, 0);
        int f = probe(c, xi, w(m, o + 0x24));
        if ((f & 4) != 0) {
            // 0xEE8C: thrown back from a wall
            int d2 = (m.read32(o + 0x28) & 0xffff0000) | ((m.read32(o + 0x28) + m.read16(o + 0x3c)) & 0xffff);
            if (d2 != 0) {
                xi = d2 < 0 ? snapBefore(xi, w(m, o + 0x14)) : snapAfter(xi, w(m, o + 0x14), w(m, o + 0x18));
                m.write32(o + 0x28, 0xfffc0000);
                m.write32(o + 0x3c, 0);
                xf = 0;
            }
            m.write32(o + 0x02, (xi << 16) | xf);
        } else {
            if ((f & 0x21) != 0 && vx != 0) {
                if (vx < 0) {
                    xi = snapBefore(xi, w(m, o + 0x14));
                    m.write8(o + 0x9e, 0xff);
                } else {
                    xi = snapAfter(xi, w(m, o + 0x14), w(m, o + 0x18));
                    m.write8(o + 0x9e, 1);
                }
                m.write32(o + 0x28, 0);
                xf = 0;
            }
            m.write32(o + 0x02, (xi << 16) | xf);
        }
        // ---- gravity / platform carry (0xEB68)
        int plat = m.read32(o + 0x48);
        if (m.read8(o + 0x64) != 0 && plat != 0 && w(m, plat) > 0) {
            int d0 = 1;
            int d1 = w(m, plat + 0x2c);
            if (d1 > 0) {
                d0 = d1 + 1;
            }
            m.write32(o + 0x2c, d0 << 16);
        } else {
            m.write32(o + 0x2c, m.read32(o + 0x2c) + m.read32(a4 + 2));
        }
        m.write8(o + 0x64, 0);
        m.write8(o + 0x6b, 0);
        m.write8(o + 0x6a, 0);
        m.write32(o + 0x48, 0);
        m.write32(o + 0x40, 0);
        m.write32(o + 0x3c, 0);
        m.write8(o + 0x65, 0);
        int y = m.read32(o + 0x2c) + m.read32(o + 0x06);
        if ((short) ((y >> 16) + m.read16(o + 0x16)) < 0) {
            y = 0;
            m.write32(o + 0x2c, 0);
        }
        int yi = (short) (y >> 16), yf = y & 0xffff;
        int d0;
        if (w(m, o + 0x2c) >= 0) {
            c.d[0] = (short) m.read16(o + 0x02);
            c.d[1] = yi;
            slopeProbe(c);
            d0 = c.d[0] & 0xffff;
            int d3 = c.d[3] & 0xffff;
            if ((d0 & 0x200) != 0) {
                m.write8(o + 0x65, 0xff);
            }
            if (d3 != 0) {
                yi = (short) d3;
                if (m.read16(0xcac2) == 0 && (d0 & 0x100) != 0) {
                    m.write8(0xcac2, 0xff);
                    int slide = m.read32(0x1089c);
                    boolean neg = (d0 & 0x80) != 0;
                    d0 &= ~0x80;
                    m.write32(o + 0x3c, neg ? -slide : slide);
                }
            }
        }
        // ---- ground / platforms (0xEC14)
        m.write8(0xcad4, 0xff);
        m.write16(0xcad6, yi);
        d0 = probe(c, w(m, o + 0x02), yi);
        boolean onPlatform = m.read16(0xcac2) == 0 && m.read8(o + 0x6b) != 0;
        int mode; // 0 = 0xED4E, 1 = 0xEE20, 2 = 0xEE38, 3 = 0xED58
        if (onPlatform) {
            yi = (short) (m.read16(0xcaca) - 1);
            int saved = m.read16(0xcac8);
            platformPush(c, m, o);
            m.write16(0xcac8, saved);
            m.write8(0xcad4, 0);
            int savedD0 = m.read32(0xcad0);
            int savedC8 = m.read16(0xcac8);
            int g = probe(c, w(m, o + 0x02), yi);
            m.write16(0xcac8, savedC8);
            m.write32(0xcad0, savedD0);
            if ((g & 0xfffd) != 0) {
                int d7 = m.read32(o + 0x2c) + 0xe400 + m.read32(0xcad0);
                if (d7 < 0) {
                    d7 += m.read32(o + 0x06);
                    yi = snapBefore((short) (d7 >> 16), w(m, o + 0x16));
                    m.write8(o + 0x9f, 0xff);
                } else {
                    d7 += m.read32(o + 0x06);
                    yi = snapAfter((short) (d7 >> 16), w(m, o + 0x16), w(m, o + 0x1a));
                    m.write8(o + 0x9f, 1);
                }
                yf = d7 & 0xffff;
            }
            d0 = m.read16(0xcac8);
            if (d0 == 0) {
                mode = 1;
            } else if ((d0 & 0x40) != 0) {
                int v = -m.read32(o + 0x2c);
                if ((byte) m.read8(0x8269) < 0) {
                    v -= 0x10000;
                    m.write32(o + 0x2c, v);
                    mode = 2;
                } else if (v >= 0xfffc0000) {
                    mode = 1;
                } else {
                    m.write32(o + 0x2c, v + 0x40000);
                    mode = 2;
                }
            } else if ((d0 & 0x21) != 0 || (d0 & 2) != 0 || (d0 & 0x20) != 0 || (d0 & 0x200) != 0) {
                mode = 1;
            } else {
                mode = 3;
            }
        } else {
            mode = 0;
        }
        if (mode == 0) {
            if ((d0 & 0x200) != 0) {
                m.write8(o + 0x65, 0xff);
            }
            mode = 3;
        }
        int dy = w(m, o + 0x16), h = w(m, o + 0x1a);
        if (mode == 3) {
            mode = -1;
            if (m.read32(o + 0x2c) >= 0) {
                if ((d0 & 4) != 0) {
                    m.write8(o + 0x64, 0xff);
                    m.write32(o + 0x2c, 0xfff40000);
                    yi = (short) (((yi + dy + h - 16) | 15) - dy - h); // 0xEDC4
                    mode = 2;
                } else if ((d0 & 2) != 0 && w(m, o + 0x2c) >= 0
                        && (short) ((yi + dy + h - 1) & 15) <= w(m, o + 0x2c)) {
                    mode = 4; // 0xEE06
                } else if ((d0 & 0x40) != 0) {
                    m.write8(o + 0x64, 0);
                    int v = -m.read32(o + 0x2c);
                    if (v >= 0xfffc0000) {
                        mode = 4;
                    } else {
                        m.write32(o + 0x2c, v + 0x40000);
                        yi = (short) (((yi + dy + h - 16) | 15) - dy - h);
                        mode = 2;
                    }
                }
            }
            if (mode == -1) { // 0xEDDE
                if ((d0 & 0x21) == 0) {
                    mode = 2;
                } else if (w(m, o + 0x2c) >= 0) {
                    mode = 4;
                } else {
                    yi = snapBefore(yi, dy);
                    m.write32(o + 0x2c, 0);
                    m.write8(o + 0x9f, 0xff);
                    yf = 0;
                    mode = 2;
                }
            }
        }
        if (mode == 4) {
            yi = snapAfter(yi, dy, h);
            mode = 1;
        }
        if (mode == 1) { // 0xEE20
            m.write32(o + 0x2c, 0x10000);
            m.write8(o + 0x64, 0xff);
            m.write8(o + 0x9f, 1);
            yf = 0;
        }
        m.write32(o + 0x06, (yi << 16) | (yf & 0xffff));
        c.d[7] = sx;
        c.a[3] = sa3;
        // count (0xEE44)
        int n = m.read16(a4);
        int t = (m.read16(o + 0x4e) + 1) & 0xffff;
        m.write16(o + 0x4e, t);
        if (n == t) {
            m.write16(o + 0x4e, 0);
            return a4 + 6;
        }
        return a4 - 2;
    }

    /** 0xEEE8: horizontal push of the platform (+$40), stopped by walls. */
    private static void platformPush(M68k c, GameMemory m, int o) {
        int x = m.read32(o + 0x02) + m.read32(o + 0x40);
        int xi = (short) (x >> 16), xf = x & 0xffff;
        m.write8(0xcad4, 0);
        int f = probe(c, xi, w(m, o + 0x24));
        if ((f & 0x21) != 0) {
            int d2 = m.read32(o + 0x28) + m.read32(o + 0x40);
            if (d2 != 0) {
                xi = d2 < 0 ? snapBefore(xi, w(m, o + 0x14)) : snapAfter(xi, w(m, o + 0x14), w(m, o + 0x18));
                m.write32(o + 0x40, 0);
                xf = 0;
            }
        }
        m.write32(o + 0x02, (xi << 16) | xf);
    }

    // ------------------------------------------------------------------ 0xF3E6

    /**
     * Attributes under the box at (d0, d1) (blocks marked $A lose bits 1-2), then, when the
     * object falls or stands ($2C >= 0) and $CAD4 is set: is it standing on Stix (thrown and
     * planted, its +$83 bit 1) or on the top of a big object with +$83 bit 1 (lifts, logs...)?
     * The highest such top below the feet wins: +$48 = that object, $CACA = its top,
     * $CACC/$CAD0 = its velocity, +$6B set.
     */
    static void groundProbe(M68k c) {
        GameMemory m = c.mem;
        int o = c.a[5];
        m.write16(0xcac8, 0);
        m.write16(0xcaca, 0x7fff);
        int x0 = (short) (c.d[0] + m.read16(o + 0x14));
        int y0 = (short) (c.d[1] + m.read16(o + 0x16));
        int x1 = (short) (x0 + m.read16(o + 0x18));
        int y1 = (short) (y0 + m.read16(o + 0x1a));
        int rowBytes = m.read16(0x10a48);
        int map = m.read32(0x10888), table = m.read32(0x10890);
        int a0 = map + (short) ((short) (x0 & 0xfff0) >> 3)
                + (int) ((long) (((short) (y0 & 0xfff0) >> 4) & 0xffff) * rowBytes);
        if ((x1 & 15) != 0) {
            x1 = (short) ((x1 + 16) & 0xfff0);
        }
        int cols = (short) ((short) (x1 - (x0 & 0xfff0)) >> 4) & 0xffff;
        if ((y1 & 15) != 0) {
            y1 = (short) ((y1 + 16) & 0xfff0);
        }
        int rows = (short) ((short) (y1 - (y0 & 0xfff0)) >> 4);
        int d0 = 0;
        int a4 = a0;
        if (rows - 1 >= 0) {
            for (int k = 0; k < rows - 1; k++) {
                for (int i = 0; i < cols; i++) {
                    d0 |= m.read16(table + m.read16(a0));
                    a0 += 2;
                }
                a4 += rowBytes;
                a0 = a4;
            }
            d0 &= 0xffd1;
        }
        for (int i = 0; i < cols; i++) {
            int attr = m.read16(table + m.read16(a0));
            a0 += 2;
            if ((attr & 0xa) == 0xa) {
                attr &= 0xfff9;
            }
            d0 |= attr;
        }
        m.write16(0xcac8, d0);
        platforms(m, o);
        c.d[0] = m.read16(0xcac8);
    }

    private static void platforms(GameMemory m, int o) {
        if (m.read32(o + 0x48) != 0) {
            return;
        }
        m.write32(0xcacc, 0);
        m.write32(0xcad0, 0);
        if (w(m, o + 0x2c) < 0 || m.read16(0xcad4) == 0) {
            return;
        }
        m.write16(0xcad4, 0);
        int x = w(m, o + 0x02), y = w(m, 0xcad6);
        int s = 0x10988; // Stix
        if ((m.read8(s + 0x83) & 2) != 0) {
            int dx = (short) (x - m.read16(s + 2) - 0x14);
            if (dx <= 0 && (short) (dx + 0x28) >= 0) {
                int dy = (short) (y - m.read16(s + 6));
                if (dy >= 0 && dy <= 0x10) {
                    m.write8(o + 0x6b, 0xff);
                    m.write8(o + 0x6d, 0xff);
                    m.write32(o + 0x48, s);
                    m.write16(0xcaca, m.read16(s + 6) + 1);
                    m.write32(0xcacc, m.read32(s + 0x28));
                    m.write32(0xcad0, m.read32(s + 0x2c));
                    return;
                }
            }
        }
        int left = (short) (x + m.read16(o + 0x14));
        int right = (short) (left + m.read16(o + 0x18));
        int feet = (short) (y + m.read16(o + 0x16) + m.read16(o + 0x1a));
        int best = 0;
        int a6 = w(m, 0xcaca);
        for (int p = 0x1a000 + 0xba; ; p += 0xba) {
            int st = w(m, p);
            if (st < 0) {
                break;
            }
            if (st == 0 || (m.read8(p + 0x83) & 2) == 0) {
                continue;
            }
            int top = (short) (m.read16(p + 6) + m.read16(p + 0x16));
            if (top > a6) {
                continue;
            }
            int l = (short) (m.read16(p + 2) + m.read16(p + 0x14));
            if (right <= l) {
                continue;
            }
            int r = (short) (l + m.read16(p + 0x18));
            if (left >= r) {
                continue;
            }
            int d = (short) (feet - top);
            if (d < 0) {
                continue;
            }
            d = (short) (d + m.read16(p + 0x2c));
            if (d > w(m, o + 0x2c)) {
                continue;
            }
            a6 = (short) (top - m.read16(o + 0x16) - m.read16(o + 0x1a) + 1);
            best = p;
        }
        m.write32(o + 0x48, best);
        if (best != 0) {
            m.write16(0xcaca, a6);
            m.write8(o + 0x6b, 0xff);
            m.write32(0xcad0, m.read32(best + 0x2c));
            m.write32(0xcacc, m.read32(best + 0x28) + m.read32(best + 0x3c));
        }
    }

    // ------------------------------------------------------------------ 0xF2C2

    /**
     * Ground under the centre of the feet at (d0, d1): d0 = attributes of that cell (or of the
     * slope), d3 = corrected y of the object when it is on a slope / a ground top (0 = none).
     * Sets +$64 (on the ground) in some cases.
     */
    static void slopeProbe(M68k c) {
        GameMemory m = c.mem;
        int o = c.a[5];
        m.write16(0xcac4, c.d[1]);
        int cx = (short) (c.d[0] + m.read16(o + 0x14) + ((short) m.read16(o + 0x18) >> 1));
        int feet = (short) (c.d[1] + m.read16(o + 0x16) + m.read16(o + 0x1a));
        int rowBytes = m.read16(0x10a48);
        int table = m.read32(0x10890);
        int a0 = m.read32(0x10888) + (short) ((short) (cx & 0xfff0) >> 3)
                + (int) ((long) (((short) feet >> 4) & 0xffff) * rowBytes);
        int d5 = cx, d6 = feet, d3 = 0;
        int d2 = m.read16(a0);
        int d1 = m.read16(table + d2);
        boolean slope;
        int keep;
        if ((d1 & 0x10) != 0) {
            if ((d1 & 2) != 0 && w(m, o + 0x2c) >= 0 && (short) (m.read16(0xcac4) - d3) > 8) {
                keep = d1;
                d6 |= 15;
                d3 = d6;
                a0 -= rowBytes;
                d2 = m.read16(a0);
                d1 = m.read16(table + d2);
                if ((d1 & 8) != 0) {
                    slope = true;
                } else {
                    d3 = 0;
                    d1 = keep;
                    slope = false;
                }
            } else {
                keep = d1;
                d3 = (short) ((d6 - 16) | 15);
                d6 = d3;
                a0 -= rowBytes;
                d2 = m.read16(a0);
                d1 = m.read16(table + d2);
                if ((d1 & 8) != 0) {
                    slope = true;
                } else {
                    m.write8(o + 0x64, 0xff);
                    d1 = keep;
                    slope = false;
                }
            }
        } else {
            slope = (d1 & 8) != 0;
        }
        if (slope) {
            d3 = d6 & 0xfff0;
            d2 = (d2 + (d5 & 15)) & 0xffff;
            d3 = (short) (d3 + (byte) m.read8(table + d2 + 0x42));
            if (d3 > (short) d6) {
                c.d[0] = (c.d[0] & 0xffff0000) | d1;
                c.d[3] = 0;
                return;
            }
            if ((d1 & 2) != 0 && w(m, o + 0x2c) >= 0 && (short) (m.read16(0xcac4) - d3) > 8) {
                c.d[0] = (c.d[0] & 0xffff0000) | d1;
                c.d[3] = 0;
                return;
            }
            m.write8(o + 0x64, 0xff);
        }
        c.d[0] = (c.d[0] & 0xffff0000) | d1;
        if ((d3 & 0xffff) != 0) {
            d3 = (short) (d3 - m.read16(o + 0x16) - m.read16(o + 0x1a) + 1);
        }
        c.d[3] = d3 & 0xffff; // moveq #0,d3 then word operations
    }
}
