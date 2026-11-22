package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * More object-script natives of the levels (set code), ported at register level, by level file
 * id. Found with {@link bubba.dev.NativeHunt} (every kind of object visited).
 */
final class LevelNatives {

    private static final int[] ALL = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};
    private static final int H = 0x10910;

    private LevelNatives() {
    }

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

    static int sw(int reg, int v) {
        return (reg & 0xffff0000) | (v & 0xffff);
    }

    static int sb(int reg, int v) {
        return (reg & 0xffffff00) | (v & 0xff);
    }

    static void register(String fileId, Map<Integer, Ports.Port> m) {
        switch (fileId) {
            case "5" -> stage2(m);
            case "3" -> stage3(m);
            case "6" -> stage5(m);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ stage 2

    private static void stage2(Map<Integer, Ports.Port> m) {
        add(m, 0x12694, LevelNatives::s2Pull);
        add(m, 0x12f5c, c -> { // $10AD8 = 1, 2, 3, 1...
            GameMemory g = c.mem;
            c.a[0] = 0x10ad8;
            c.d[0] = sb(c.d[0], g.read8(c.a[0]) + 1);
            c.d[0] = sw(c.d[0], c.d[0] & 3);
            if ((c.d[0] & 0xffff) == 0) {
                c.d[0] = 1;
            }
            g.write8(c.a[0], c.d[0]);
        });
        add(m, 0x12f70, c -> { // Bubba grabbed by the object: camera on it
            GameMemory g = c.mem;
            g.write8(c.a[5] + 0x63, 0xff);
            c.a[0] = 0x10aca;
            g.write8(c.a[0], 0xff);
            c.a[0]++;
            g.write8(c.a[0], 1);
            g.write8(c.a[0] - 0x11, 0xff);
            c.a[0] = 0x10912;
            s2Follow(c);
        });
        add(m, 0x12f86, LevelNatives::s2Follow);
        add(m, 0x130a6, c -> {
            GameMemory g = c.mem;
            c.d[0] = sb(c.d[0], g.read8(0x10ad8));
            if ((c.d[0] & 0xff) != 0) {
                c.a[0] = 0x10ad3;
                c.d[0] = sw(c.d[0], (byte) c.d[0]);
                g.write8(c.a[0] + (short) c.d[0], 0xff);
            }
        });
        add(m, 0x131a2, c -> {
            c.a[0] = 0x10ad9;
            c.mem.write8(c.a[0], 0xff);
            c.mem.write8(c.a[0] + 0x218c, 0);
        });
        add(m, 0x13578, c -> { // next object of the table $135A6: type, x speed
            GameMemory g = c.mem;
            int o = c.a[5];
            c.a[0] = 0x12c4f;
            c.d[0] = sb(c.d[0], g.read8(c.a[0]));
            g.write8(c.a[0], g.read8(c.a[0]) + 1);
            c.d[0] = sw(c.d[0], (byte) c.d[0]);
            c.d[0] = sw(c.d[0], c.d[0] << 2);
            int k = (short) c.d[0];
            c.d[0] = (short) g.read16(0x135a6 + k); // movem.w $135A6(pc, d0.w) (shown $135A4)
            c.d[1] = (short) g.read16(0x135a8 + k);
            g.write16(o + 0x5c, c.d[0]);
            g.write16(o + 0x28, c.d[1]);
            g.write16(o + 0x2c, 0xfff8);
            c.d[0] = sw(c.d[0], c.d[0] - 3);
            c.d[0] = sw(c.d[0], c.d[0] << 3);
            c.d[0] = sw(c.d[0], c.d[0] + 0x48);
            g.write16(o + 0xa0, c.d[0]);
        });
    }

    /** $12F86: (a0) = the object's position, y + $20. */
    private static void s2Follow(M68k c) {
        GameMemory g = c.mem;
        g.write32(c.a[0], g.read32(c.a[5] + 2));
        c.a[0] += 4;
        g.write32(c.a[0], g.read32(c.a[5] + 6));
        g.write16(c.a[0], g.read16(c.a[0]) + 0x20);
    }

    /** $12694: object and Bubba moved together towards the point $10ACE / $10AD0 (max $30). */
    private static void s2Pull(M68k c) {
        GameMemory g = c.mem;
        int[] d = c.d, a = c.a;
        int o = a[5];
        d[1] = 0;
        d[2] = 0;
        a[0] = H;
        d[0] = sw(d[0], g.read16(0x10ace) - g.read16(o + 2));
        d[1] = clamp(d[0], d[1]);
        d[3] = sw(d[3], d[0]);
        d[0] = sw(d[0], g.read16(0x10ad0) - g.read16(o + 6));
        if ((d[0] & 0xffff) == 0) {
            if ((d[3] & 0xffff) == 0) {
                g.write8(a[0] + 0x1aa, 0);
                g.write8(a[0] + 0x1bc, 0);
                g.write32(a[0] - 0x3e, 0);
                a[4] = 0x12680;
            }
        } else {
            d[2] = clamp(d[0], d[2]);
        }
        d[1] = (d[1] & 0xffff) << 16;
        g.write32(o + 0x28, d[1]);
        g.write32(a[0] + 0x28, d[1]);
        d[2] = (d[2] & 0xffff) << 16;
        g.write32(o + 0x2c, d[2]);
        g.write32(a[0] + 0x2c, d[2]);
    }

    /** d0 clamped to +-$30 (moveq then move.w), r unchanged when d0 = 0. */
    private static int clamp(int d0, int r) {
        int v = (short) d0;
        if (v == 0) {
            return r;
        }
        if (v > 0) {
            return v >= 0x30 ? 0x30 : sw(0x30, v);
        }
        return v <= -0x30 ? 0xffffffd0 : sw(0xffffffd0, v);
    }

    // ------------------------------------------------------------------ stage 3

    private static int absw(int v) {
        int s = (short) v;
        return s < 0 ? -s : s;
    }

    private static void stage3(Map<Integer, Ports.Port> m) {
        add(m, 0x12530, LevelNatives::s3Pull);
        add(m, 0x12758, c -> { // camera on the object (y + $10, - $12 when +$44)
            GameMemory g = c.mem;
            int o = c.a[5];
            c.a[0] = 0x10912;
            g.write32(c.a[0], g.read32(o + 2));
            c.a[0] += 4;
            g.write32(c.a[0], g.read32(o + 6));
            g.write16(c.a[0], g.read16(c.a[0]) + 0x10);
            c.a[1] = 0xcade;
            g.write32(c.a[1], g.read32(c.a[0]));
            c.a[0] -= 4;
            c.a[1] -= 4;
            g.write32(c.a[1], g.read32(c.a[0]));
            if (g.read8(o + 0x44) != 0) {
                g.write16(c.a[1], g.read16(c.a[1]) - 0x12);
            }
        });
        add(m, 0x1277c, c -> { // Bubba's speed = the object's
            GameMemory g = c.mem;
            c.a[0] = 0x10938;
            g.write32(c.a[0], g.read32(c.a[5] + 0x28));
            c.a[0] += 4;
            g.write32(c.a[0], g.read32(c.a[5] + 0x2c));
            c.a[0] += 4;
        });
        add(m, 0x13842, c -> c.mem.write16(c.a[5] + 0x82, c.mem.read16(c.a[5] + 0x82) ^ 4));
        add(m, 0x13b66, c -> {
            GameMemory g = c.mem;
            int o = c.a[5];
            c.d[0] = 1;
            c.a[0] = 0x10aca;
            g.write8(c.a[0] + 0x23d6, 0);
            g.write8(c.a[0], 0xff);
            c.a[0]++;
            g.write8(c.a[0], c.d[0]);
            g.write8(o + 0x63, 0xff);
            g.write8(c.a[0] - 0x11, 0xff);
            c.a[0] = 0x10912;
            s2Follow(c);
        });
        add(m, 0x13f3e, c -> { // byte +$44 stored in the switch table $12E95 (+$45 = changed)
            GameMemory g = c.mem;
            int o = c.a[5];
            c.a[0] = 0x12e95 + (short) g.read16(o + 0x46);
            c.d[0] = sb(c.d[0], g.read8(o + 0x44));
            boolean same = (c.d[0] & 0xff) == g.read8(c.a[0]);
            g.write8(o + 0x45, same ? 0 : 0xff);
            if (!same) {
                g.write8(c.a[0], c.d[0]);
            }
        });
        add(m, 0x144a8, c -> {
            GameMemory g = c.mem;
            g.write32(c.a[5] + 0x50, g.read32(c.a[5] + 0x28));
            g.write32(c.a[5] + 0x44, g.read32(c.a[5] + 0x2c));
        });
        add(m, 0x144b6, c -> { // bounce: -7/8 of the speed kept in +$44
            GameMemory g = c.mem;
            c.d[0] = -g.read32(c.a[5] + 0x44);
            c.d[1] = c.d[0] >> 3;
            c.d[0] -= c.d[1];
            g.write32(c.a[5] + 0x2c, c.d[0]);
        });
        add(m, 0x1475e, c -> { // block under the object with attribute bit 2: script $14720
            GameMemory g = c.mem;
            int[] d = c.d, a = c.a;
            int o = a[5];
            a[0] = g.read32(0x10888);
            d[1] = sw(d[1], g.read16(o + 6) - 1);
            d[1] = sw(d[1], (short) d[1] >> 4);
            d[1] = (d[1] & 0xffff) * g.read16(0x10a48);
            d[2] = sw(d[2], g.read16(o + 2));
            d[2] = sw(d[2], (short) d[2] >> 4);
            d[2] = sw(d[2], d[2] * 2);
            d[1] = sw(d[1], d[1] + d[2]);
            a[0] += d[1];
            a[1] = g.read32(0x10890);
            a[2] = a[0];
            a[0] += (short) g.read16(0x10a48);
            d[1] = sw(d[1], g.read16(a[0]));
            if ((g.read8(a[1] + d[1] + 1) & 4) != 0) {
                a[4] = 0x14720;
            }
        });
        add(m, 0x14794, c -> { // Bubba below and behind: script $14834
            GameMemory g = c.mem;
            int[] d = c.d, a = c.a;
            int o = a[5];
            if (g.read8(o + 0x64) == 0) {
                return;
            }
            int diff = (short) g.read16(0x10916) - (short) g.read16(o + 6);
            d[0] = sw(d[0], diff);
            if (diff >= 0) {
                return;
            }
            d[0] = sw(d[0], g.read16(o + 0x86));
            d[1] = sb(d[1], g.read8(o + 0x30));
            d[1] = sw(d[1], (byte) d[1]);
            d[1] = sw(d[1], d[1] ^ d[0]);
            if ((short) d[1] < 0) {
                a[0] = 0x14834;
                g.write32(o + 0xc, a[0]);
                g.write32(o + 0x44, 0);
            }
        });
        add(m, 0x14b26, c -> { // carried by the object $10AEA
            GameMemory g = c.mem;
            int o = c.a[5];
            c.a[0] = g.read32(0x10aea);
            g.write32(o + 0x28, g.read32(c.a[0] + 0x28));
            g.write32(o + 0x2c, g.read32(c.a[0] + 0x2c));
            c.a[0] += (short) g.read16(o + 0x46);
            g.write8(c.a[0] + 0xa2, 0xff);
        });
        add(m, 0x14c00, LevelNatives::s3Floor);
        add(m, 0x14d10, c -> {
            GameMemory g = c.mem;
            int[] d = c.d, a = c.a;
            int o = a[5];
            d[0] = sw(d[0], g.read16(o + 6));
            s3Nearest(c);
            a[0] = g.read32(0x10aea);
            if ((d[3] & 0xffff) == g.read16(a[0] + 6)) {
                d[0] = sb(d[0], g.read8(0x12ea3));
                if ((d[0] & 0xff) != 0) {
                    a[4] = 0x14d0a;
                    return;
                }
            }
            a[0] = 0x12ea2;
            g.write8(a[0], 1);
            a[0] = 0x10ada;
            g.write16(a[0], g.read16(o + 2));
            a[0] += 2;
            g.write16(a[0], g.read16(o + 6));
            a[0] += 2;
        });
        add(m, 0x14fe6, c -> {
            GameMemory g = c.mem;
            int o = c.a[5];
            c.a[0] = g.read32(o + 0x44);
            int b = c.a[0];
            if (g.read8(b + 0x64) != 0) {
                return;
            }
            c.d[0] = sw(c.d[0], absw(g.read16(b + 2) - g.read16(o + 2)));
            if ((short) c.d[0] > 0xc) {
                c.a[4] = 0x14f5a;
                return;
            }
            c.d[0] = sw(c.d[0], g.read16(b + 6) - g.read16(o + 6));
            if ((c.d[0] & 0xffff) != 0xfff4) {
                c.a[4] = 0x14f5a;
                return;
            }
            c.a[4] = 0x14fb6;
            s3Release(g, b);
        });
        add(m, 0x1502a, c -> {
            GameMemory g = c.mem;
            int o = c.a[5];
            c.a[0] = g.read32(o + 0x44);
            int b = c.a[0];
            c.d[0] = sw(c.d[0], absw(g.read16(b + 2) - g.read16(o + 2)));
            if ((short) c.d[0] > 0xc) {
                return;
            }
            c.d[0] = sw(c.d[0], g.read16(b + 6) + g.read16(b + 0x16) + g.read16(b + 0x1a)
                    - g.read16(o + 6) - g.read16(o + 0x16) - g.read16(o + 0x1a));
            if ((c.d[0] & 0xffff) != 0) {
                return;
            }
            c.a[4] = 0x14fe0;
            s3Release(g, b);
        });
        add(m, 0x15062, c -> { // turn round when the object +$44 is behind
            GameMemory g = c.mem;
            int o = c.a[5];
            c.a[0] = g.read32(o + 0x44);
            c.d[0] = sw(c.d[0], g.read16(c.a[0] + 2) - g.read16(o + 2));
            c.d[1] = sb(c.d[1], g.read8(o + 0x30));
            c.d[1] = sw(c.d[1], (byte) c.d[1]);
            c.d[1] = sw(c.d[1], c.d[1] ^ c.d[0]);
            if ((short) c.d[1] < 0) {
                g.write16(o + 0xa0, g.read16(o + 0xa0) ^ 0x8000);
                g.write8(o + 0x30, -g.read8(o + 0x30));
            }
        });
        add(m, 0x15198, c -> {
            c.d[0] = (c.mem.read32(c.a[5] + 0x28) >> 1) & ~0xff;
            c.mem.write32(c.a[5] + 0x28, c.d[0]);
        });
        add(m, 0x151a6, c -> {
            GameMemory g = c.mem;
            int o = c.a[5];
            c.a[0] = g.read32(o + 0x44);
            g.write8(o + 0x30, g.read8(c.a[0] + 0x30));
            g.write16(o + 0xa0, g.read16(c.a[0] + 0xa0));
            g.write32(c.a[0] + 0x44, o);
        });
        add(m, 0x15440, c -> {
            c.a[0] = 0x10aee;
            c.mem.write32(c.a[0], c.a[5]);
        });
        add(m, 0x1574c, c -> { // +$44 = all six switches $12E96.. set
            GameMemory g = c.mem;
            c.a[0] = 0x12e96;
            c.d[0] = sb(c.d[0], g.read8(c.a[0]++));
            c.d[1] = 5;
            while (true) {
                c.d[0] = sb(c.d[0], c.d[0] & g.read8(c.a[0]++));
                if ((c.d[0] & 0xff) == 0) {
                    break;
                }
                c.d[1] = sw(c.d[1], c.d[1] - 1);
                if ((c.d[1] & 0xffff) == 0xffff) {
                    break;
                }
            }
            g.write8(c.a[5] + 0x44, (c.d[0] & 0xff) != 0 ? 0xff : 0);
        });
    }

    /** $15014. */
    private static void s3Release(GameMemory g, int b) {
        g.write8(b + 0x30, 0);
        g.write16(b + 0xa0, 0);
        g.write16(b + 0xa, 0xffff);
    }

    /** $12530: like stage 2's pull, then the script by $12EA0 when arrived. */
    private static void s3Pull(M68k c) {
        GameMemory g = c.mem;
        int[] d = c.d, a = c.a;
        int o = a[5];
        d[1] = 0;
        d[2] = 0;
        a[0] = H;
        d[0] = sw(d[0], g.read16(0x10ace) - g.read16(o + 2));
        d[1] = clamp(d[0], d[1]);
        d[3] = sw(d[3], d[0]);
        d[0] = sw(d[0], g.read16(0x10ad0) - g.read16(o + 6));
        if ((d[0] & 0xffff) == 0) {
            if ((d[3] & 0xffff) == 0) {
                g.write32(a[0] - 0x3e, 0);
                g.write8(a[0] + 0x1bd, 0);
                d[0] = sb(d[0], g.read8(0x12ea0));
                if ((d[0] & 0xff) == 0) {
                    a[4] = 0x1252e;
                    g.write8(a[0] + 0x1aa, 0);
                    g.write8(a[0] + 0x1bc, 0);
                } else {
                    a[4] = 0x125c8;
                    d[0] = sb(d[0], d[0] - 2);
                    int v = (byte) d[0];
                    if (v >= 0) {
                        a[4] = 0x12612;
                        if (v != 0) {
                            a[4] = 0x12666;
                        }
                    }
                }
            }
        } else {
            d[2] = clamp(d[0], d[2]);
        }
        d[1] = (d[1] & 0xffff) << 16;
        g.write32(o + 0x28, d[1]);
        g.write32(a[0] + 0x28, d[1]);
        d[2] = (d[2] & 0xffff) << 16;
        g.write32(o + 0x2c, d[2]);
        g.write32(a[0] + 0x2c, d[2]);
    }

    /** $14AD6: the floor of $14ABE nearest to d0 (d3 = its y, d2 = distance). */
    private static void s3Nearest(M68k c) {
        GameMemory g = c.mem;
        int[] d = c.d, a = c.a;
        a[0] = 0x14abe;
        d[1] = 5;
        d[2] = 0xffffffff;
        while (true) {
            d[4] = sw(d[4], d[0] - g.read16(a[0]));
            a[0] += 2;
            if ((short) d[4] < 0) {
                d[4] = sw(d[4], -d[4]);
            }
            if ((d[4] & 0xffff) < (d[2] & 0xffff)) {
                d[2] = sw(d[2], d[4]);
                d[3] = sw(d[3], g.read16(a[0] - 2));
            }
            d[1] = sw(d[1], d[1] - 1);
            if ((d[1] & 0xffff) == 0xffff) {
                return;
            }
        }
    }

    /** $14C00: the next floor of $14ABE above (+$45 set) or below the object: lift target. */
    private static void s3Floor(M68k c) {
        GameMemory g = c.mem;
        int[] d = c.d, a = c.a;
        int o = a[5];
        d[0] = 5;
        d[1] = sw(d[1], g.read16(o + 6));
        boolean found;
        if ((g.read8(o + 0x45) & 0x80) == 0) {
            a[0] = 0x14abe;
            while (true) {
                int v = (short) g.read16(a[0]);
                a[0] += 2;
                found = (short) d[1] < v;
                if (found) {
                    break;
                }
                d[0] = sw(d[0], d[0] - 1);
                if ((d[0] & 0xffff) == 0xffff) {
                    break;
                }
            }
        } else {
            a[0] = 0x14aca;
            while (true) {
                a[0] -= 2;
                found = (short) d[1] > (short) g.read16(a[0]);
                if (found) {
                    break;
                }
                d[0] = sw(d[0], d[0] - 1);
                if ((d[0] & 0xffff) == 0xffff) {
                    break;
                }
            }
            a[0] += 2;
        }
        if (!found) {
            return;
        }
        a[1] = 0x10adc;
        a[0] -= 2;
        g.write16(a[1], g.read16(a[0]));
        a[1] -= 2;
        g.write16(a[1], g.read16(a[0] + 0xc));
        g.write8(a[1] + 0x23c8, 0xff);
    }

    // ------------------------------------------------------------------ stage 5

    private static void stage5(Map<Integer, Ports.Port> m) {
        add(m, 0x1249a, c -> s5Gate(c, 0x12d50, true));
        add(m, 0x124aa, c -> s5Gate(c, 0x12d50, false));
        add(m, 0x124f0, c -> s5Gate(c, 0x12d51, true));
        add(m, 0x12500, c -> s5Gate(c, 0x12d51, false));
        add(m, 0x13300, LevelNatives::s5Block);
        add(m, 0x13536, c -> c.mem.write32(c.a[5] + 0x48, c.mem.read32(c.a[5] + 0x2c)));
        add(m, 0x1353e, c -> c.mem.write32(c.a[5] + 0x2c, c.mem.read32(c.a[5] + 0x48)));
        add(m, 0x135b4, c -> c.mem.write16(c.a[5] + 0x14, -c.mem.read16(c.a[5] + 0x14)));
        add(m, 0x135ba, c -> {
            c.a[0] = c.mem.read32(c.a[5] + 0x44);
            c.mem.write8(c.a[5] + 0x30, c.mem.read8(c.a[0] + 0x30));
        });
        add(m, 0x135c6, c -> {
            GameMemory g = c.mem;
            c.a[0] = g.read32(c.a[5] + 0x44);
            g.write32(c.a[5] + 0x28, g.read32(c.a[0] + 0x28));
            g.write32(c.a[5] + 0x2c, g.read32(c.a[0] + 0x2c));
        });
        add(m, 0x14188, c -> {
            GameMemory g = c.mem;
            int o = c.a[5];
            c.d[0] = sw(c.d[0], g.read16(o + 0x4a));
            g.write32(o + 0x48, g.read32(o + 0x44));
            g.write16(o + 0x44, g.read16(o + 2));
            g.write16(o + 0x46, c.d[0]);
        });
        add(m, 0x1419e, c -> c.mem.write32(c.a[5] + 0x44, c.mem.read32(c.a[5] + 0x48)));
        add(m, 0x144ee, c -> {
            GameMemory g = c.mem;
            int o = c.a[5];
            g.write32(o + 0x50, g.read32(o + 0x10));
            c.a[0] = 0x1476c;
            g.write32(o + 0x10, c.a[0]);
            g.write32(o + 0x28, 0);
        });
        add(m, 0x14502, c -> c.mem.write32(c.a[5] + 0x10, c.mem.read32(c.a[5] + 0x50)));
        add(m, 0x14938, c -> {
            c.a[0] = 0x10aea;
            c.mem.write32(c.a[0], c.a[5]);
        });
        add(m, 0x14940, c -> {
            GameMemory g = c.mem;
            g.write16(c.a[5] + 0xa0, g.read16(c.a[5] + 0xa0) ^ 0x8000);
            g.write8(c.a[5] + 0x30, ~g.read8(c.a[5] + 0x30));
        });
        add(m, 0x14aa2, c -> {
            c.a[0] = 0x10aee;
            c.mem.write32(c.a[0], c.a[5]);
        });
        add(m, 0x14dd0, c -> {
            c.a[0] = 0x10af2;
            c.mem.write32(c.a[0], c.a[5]);
        });
        add(m, 0x14dde, c -> {
            c.a[0] = 0x10af6;
            c.mem.write32(c.a[0], c.a[5]);
        });
        add(m, 0x14dec, c -> {
            c.a[0] = 0x10afa;
            c.mem.write32(c.a[0], c.a[5]);
        });
        add(m, 0x14e1e, c -> { // the 5 parts $10AEA.. put on this object
            GameMemory g = c.mem;
            int o = c.a[5];
            c.a[0] = 0x10afe;
            g.write32(c.a[0], o);
            g.write8(o + 0x63, 0xff);
            c.a[0] = 0x10aea;
            for (int i = 0; i < 5; i++) {
                c.a[1] = g.read32(c.a[0]);
                c.a[0] += 4;
                g.write16(c.a[1] + 2, g.read16(o + 2));
                g.write16(c.a[1] + 6, g.read16(o + 6));
                g.write8(c.a[1] + 0x63, 0xff);
            }
            c.d[0] = 0xffff; // moveq #4 then dbra
        });
        add(m, 0x14e46, LevelNatives::s5Sequencer);
        add(m, 0x12610, c -> { // the object's particle position kept at $12D5E
            GameMemory g = c.mem;
            c.a[1] = 0x12d5e;
            g.write16(c.a[1], g.read16(c.a[0] + 0x38));
            c.a[1] += 2;
            g.write16(c.a[1], g.read16(c.a[0] + 0x3c));
            c.a[1] += 2;
            g.write32(c.a[0] + 2, 0);
        });
        add(m, 0x16b40, c -> {
            GameMemory g = c.mem;
            g.write16(c.a[5] + 2, g.read16(c.a[5] + 0x44));
            g.write16(c.a[5] + 6, g.read16(c.a[5] + 0x46));
        });
        add(m, 0x16db8, c -> { // +$44 = random
            GameMemory g = c.mem;
            c.a[0] = 0x10894;
            c.d[0] = g.read32(c.a[0]);
            c.a[0] += 4;
            c.d[1] = g.read32(c.a[0]);
            c.d[1] = sw(c.d[1], c.d[1] ^ c.d[0]);
            int w = c.d[1] & 0xffff;
            c.d[1] = sw(c.d[1], (w >>> 1) | (w << 15));
            c.d[0] = sw(c.d[0], c.d[0] ^ c.d[1]);
            c.d[0] = (c.d[0] << 16) | (c.d[0] >>> 16);
            g.write32(c.a[0], c.d[0]);
            c.a[0] -= 4;
            g.write32(c.a[0], c.d[1]);
            g.write16(c.a[5] + 0x44, c.d[0]);
        });
    }

    /**
     * $14E46: stage 5 event dispatcher by $10ADA (a table of bra.w). Cases 0 (nothing) and 1
     * (the object $10AEA given two scripts, then $10ADA cleared) are ported; the others (the end
     * sequence) still run on the interpreter.
     */
    private static void s5Sequencer(M68k c) {
        GameMemory g = c.mem;
        c.d[0] = sw(c.d[0], g.read16(0x10ada));
        int entry = 0x14e4e + (short) c.d[0];
        int target = entry + 2 + (short) g.read16(entry + 2);
        switch (target) {
            case 0x14e9a -> {
            }
            case 0x14ea4 -> {
                c.a[1] = g.read32(0x10aea);
                c.a[0] = 0x14f82;
                g.write32(c.a[1] + 0xc, c.a[0]);
                c.a[0] = 0x14fd0;
                g.write32(c.a[1] + 0x10, c.a[0]);
                c.a[0] = 0x10ada;
                g.write16(c.a[0], 0);
            }
            default -> Calls.call(c, target);
        }
    }

    /**
     * $1249A / $124AA / $124F0 / $12500: a gate (a0 = its record) opens or closes with the
     * flag byte (negative = open: speed 7 and the open script $16DB4, else stopped).
     */
    private static void s5Gate(M68k c, int flag, boolean opening) {
        GameMemory g = c.mem;
        c.d[0] = sb(c.d[0], g.read8(flag));
        boolean neg = (c.d[0] & 0x80) != 0;
        if (opening) {
            if (!neg) {
                return;
            }
            g.write16(c.a[0] + 0x40, 7);
        } else {
            if (!neg) {
                return;
            }
            g.write16(c.a[0] + 0x40, 0);
        }
        c.a[1] = 0x16db4;
        g.write32(c.a[0] + 0x14, c.a[1]);
        g.write32(c.a[0] + 2, 0);
    }

    /**
     * $13300: a block pushed by its speed: stopped against the map vertically then horizontally
     * (+$9F / +$9E set), its image +$A swapped between $1A6 and $1A4 at each stop.
     */
    private static void s5Block(M68k c) {
        GameMemory g = c.mem;
        int[] d = c.d, a = c.a;
        int o = a[5];
        g.write16(o + 0x9e, 0);
        d[2] = sw(d[2], g.read16(o + 0xa));
        d[3] = sw(d[3], 0x1a6);
        if ((d[3] & 0xffff) == (d[2] & 0xffff)) {
            d[3] = sw(d[3], 0x1a4);
        }
        d[0] = sb(d[0], g.read8(0x12d56));
        boolean stopY = (d[0] & 0xff) != 0;
        if (!stopY) {
            d[0] = sw(d[0], g.read16(o + 2) - g.read16(o + 0x28));
            d[1] = sw(d[1], g.read16(o + 6));
            stopY = s5Map(c);
        }
        if (stopY) {
            a[0] = 0x12d56;
            g.write8(a[0], 0);
            d[0] = sw(d[0], g.read16(o + 0x2c));
            g.write16(o + 6, g.read16(o + 6) - d[0]);
            g.write16(o + 0x2c, 0);
            int t = d[2];
            d[2] = d[3];
            d[3] = t;
            g.write8(o + 0x9f, 0xff);
        }
        d[0] = sw(d[0], g.read16(o + 2));
        d[1] = sw(d[1], g.read16(o + 6) - g.read16(o + 0x2c));
        if (s5Map(c)) {
            d[0] = sw(d[0], g.read16(o + 0x28));
            g.write16(o + 2, g.read16(o + 2) - d[0]);
            g.write16(o + 0x28, 0);
            int t = d[2];
            d[2] = d[3];
            d[3] = t;
            g.write8(o + 0x9e, 0xff);
        }
        g.write16(o + 0xa, d[2]);
    }

    /** $13368: block attribute at (d0, d1) and $11 (or $13 going down); true when not 0. */
    private static boolean s5Map(M68k c) {
        GameMemory g = c.mem;
        int[] d = c.d, a = c.a;
        a[0] = g.read32(0x10888);
        d[1] = sw(d[1], (short) d[1] >> 4);
        d[1] = (d[1] & 0xffff) * g.read16(0x10a48);
        d[0] = sw(d[0], (short) d[0] >> 4);
        d[0] = sw(d[0], d[0] * 2);
        d[1] = sw(d[1], d[1] + d[0]);
        d[1] = sw(d[1], g.read16(a[0] + d[1]));
        a[1] = g.read32(0x10890);
        d[0] = 0x11;
        if ((short) g.read16(a[5] + 0x2c) >= 0) {
            d[0] = 0x13;
        }
        d[0] = sw(d[0], d[0] & g.read16(a[1] + d[1]));
        return (d[0] & 0xffff) != 0;
    }
}
