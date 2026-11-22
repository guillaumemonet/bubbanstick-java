package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * Contacts between Bubba and the objects, exact ports:
 * <ul>
 *   <li>0xF5F6: Bubba's box against the objects of list $176C0. Bonus objects (+$83 bit 3) are
 *       collected (handler of the table 0xF6E2 by kind +$5C, score of the record +$58, effect
 *       $15, destroyed); harmful ones (+$83 bit 2) hurt him (0xD850) unless he is invulnerable
 *       (+$3A) - the objects he holds ($1112A / $11130) give the special hits -1 / -2;</li>
 *   <li>0xD850: Bubba hurt, d6 = kind (2 = normal, -1 = by the object he holds, -2 = special
 *       death); energy $10AB6 - 1, sound, state $BDA4 changed;</li>
 *   <li>0x85D8: score += the 3 BCD bytes before a0.</li>
 * </ul>
 */
final class HeroContacts {

    static final int HERO = 0x10910;
    private static final int HURT_KIND = 0xf774, LIST = 0x176c0;

    private HeroContacts() {
    }

    static void register(Map<Integer, Ports.Port> ports) {
        ports.put(0xf5f6, HeroContacts::contacts);
        ports.put(0xd850, new Ports.Port() {
            @Override
            public void run(M68k c) {
                hurt(c);
            }

            @Override
            public int[] outputs() {
                return new int[]{6, 8}; // d6, a0
            }
        });
    }

    private static int w(GameMemory m, int a) {
        return (short) m.read16(a);
    }

    // ------------------------------------------------------------------ 0xF5F6

    static void contacts(M68k c) {
        GameMemory m = c.mem;
        if (m.read8(0x10aba) != 0) {
            return; // the tail calls 0xD850, which returns at once in that case
        }
        m.write16(HURT_KIND, 0);
        int h = HERO;
        int x0 = (short) (w(m, h + 2) + w(m, h + 0x14));
        int y0 = (short) (w(m, h + 6) + w(m, h + 0x16));
        int x1 = (short) (x0 + w(m, h + 0x18));
        int y1 = (short) (y0 + w(m, h + 0x1a));
        int n = m.read16(LIST);
        for (int i = 0; i < n; i++) {
            int o = m.read32(LIST + 2 + 4 * i);
            int e = (short) (w(m, o + 2) + w(m, o + 0x14));
            if (x1 < e) {
                continue;
            }
            e = (short) (e + w(m, o + 0x18));
            if (x0 > e) {
                continue;
            }
            e = (short) (w(m, o + 6) + w(m, o + 0x16));
            if (y1 < e) {
                continue;
            }
            e = (short) (e + w(m, o + 0x1a));
            if (y0 > e) {
                continue;
            }
            int f = m.read8(o + 0x83);
            if ((f & 0x08) != 0) {
                collect(c, m, o);
            } else if ((f & 0x04) != 0) {
                int kind = 0;
                if (m.read8(0x1112f) != 0 && o == m.read32(0x11130) && m.read16(0xbda4) != 0x34) {
                    kind = -2;
                } else if (m.read16(h + 0x3a) != 0) {
                    continue;
                } else if (m.read8(0x1112e) != 0 && o == m.read32(0x1112a)) {
                    kind = -1;
                } else if (m.read16(HURT_KIND) != 0) {
                    continue;
                } else {
                    kind = 2;
                }
                m.write8(o + 0x68, 0xff);
                m.write16(HURT_KIND, kind);
            }
        }
        int kind = m.read16(HURT_KIND);
        if (kind != 0) {
            c.d[6] = (c.d[6] & 0xffff0000) | kind;
            c.a[5] = h;
            Calls.call(c, 0xd850);
        }
    }

    /** 0xF66E: a bonus touched. */
    private static void collect(M68k c, GameMemory m, int o) {
        int kind = w(m, o + 0x5c);
        if (kind <= 15) {
            int handler = m.read32(0xf6e2 + 4 * kind);
            int[] regs = c.saveRegisters();
            int d2 = random(m);
            int dir = direction((short) m.read16(0xf6e0), d2);
            m.write16(0xf6e0, dir);
            int pan = dir < 0 ? -0x78 : 0xff74;
            int snd = (dir + 3) & 0xffff;
            switch (handler) {
                case 0xf7d4 -> sound(c, snd, pan);
                case 0xf776, 0xf77a, 0xf77e -> {
                    int k = handler == 0xf776 ? 1 : handler == 0xf77a ? 2 : 3;
                    int i = w(m, 0x108a0);
                    m.write16(0x108a0, i + 1);
                    m.write8(0x108a2 + i, k);
                    sound(c, 0xb, 0);
                }
                case 0xf794 -> {
                }
                case 0xf796 -> {
                    m.write16(0x108c0, m.read16(0x108c0) + 1);
                    sound(c, 0x1d, 0);
                }
                case 0x85f0 -> {
                    m.write16(0x10ab6, 1);
                    m.write16(0x10ab8, m.read16(0x10ab8) + 1);
                    sound(c, snd, pan);
                }
                case 0x8602 -> {
                    m.write16(0x10abe, 0x1d);
                    sound(c, snd, pan);
                }
                case 0x860e -> {
                    m.write16(0x10ab4, m.read16(0x10ab4) + 1);
                    sound(c, snd, pan);
                }
                case 0xf7a4 -> {
                    m.write32(0x108ee, m.read32(o + 2));
                    m.write32(0x108f2, m.read32(o + 6));
                    m.write16(0x10b14, m.read16(0x10a42));
                    m.write16(0x108f8, m.read16(0x10a54));
                    sound(c, snd, pan);
                }
                case 0xf7c8 -> {
                    m.write8(0x108e7, 0xff);
                    m.write8(0x827f, 0xff);
                    sound(c, snd, pan);
                }
                default -> throw new IllegalStateException(String.format("bonus handler $%X", handler));
            }
            c.restoreRegisters(regs);
        }
        addScore(m, m.read32(o + 0x58) + 0x14);
        m.write32(o + 0x54, 0);
        c.a[5] = o;
        c.d[0] = 0x15;
        Calls.call(c, 0xfb58);
        c.a[5] = o;
        Calls.call(c, 0xdc90);
        c.a[5] = HERO;
    }

    // ------------------------------------------------------------------ 0xD850

    static void hurt(M68k c) {
        GameMemory m = c.mem;
        c.a[0] = 0x10aba;
        if (m.read8(0x10aba) != 0) {
            return;
        }
        int a5 = c.a[5];
        int d6 = (short) c.d[6];
        if (d6 != -2) {
            m.write16(0x10ab6, m.read16(0x10ab6) - 1);
            if (m.read16(0x108be) == 1) {
                m.write8(0xbda1, 0);
                m.write16(0xbda2, 0x3e8);
            }
            int[] regs = c.saveRegisters();
            int d2 = random(m);
            int dir = direction((short) m.read16(0xd9c4), d2);
            m.write16(0xd9c4, dir);
            sound(c, (dir + 3) & 0xffff, 0);
            c.restoreRegisters(regs);
        }
        m.write16(0x10ac8, (m.read8(0x10899) & 0x80) != 0 ? 0x740 : 0x3a0);
        int state = m.read16(0xbda4);
        if (d6 >= 0) {
            if (state == 0x14) {
                d6 = 4;
            }
            if ((state == 4 || state == 8 || state == 0x1a) && m.read16(a5 + 0x1a) == 0x20) {
                d6 = 6;
            } else if (state == 0xc) {
                d6 = 6;
            }
        }
        m.write16(a5 + 0x4c, 0);
        m.write16(a5 + 0x5a, 0x00ff);
        m.write8(a5 + 0x6c, 0);
        int a0 = 0xbda6;
        boolean clearVy = true, clearX = true;
        if (d6 == -2) {
            m.write16(a5 + 0x4e, m.read8(0x11138) != 0 ? 0 : 4);
            d6 = 0x34;
            m.write8(a5 + 0x1aa, 0xff);
        } else if (state == 0x2e) {
            d6 = 0x30;
            clearVy = false;
        } else if (d6 >= 0) {
            m.write16(a5 + 0x4e, m.read16(0xd9a4 + d6));
            m.write16(a0, m.read16(0xd9b4 + d6));
            m.write16(a5 + 0x3a, 0x23);
            d6 = 0x12;
            clearX = false;
        } else {
            m.write16(a0 + 0x12, m.read16(a5 + 0x28));
            int b = (byte) m.read8(0x1112e);
            if (b >= 0) {
                m.write8(a0 + 0x12, b - 2);
            }
            d6 = 0x32;
        }
        if (clearX) {
            if (clearVy) {
                m.write32(a5 + 0x2c, 0);
                m.write32(a5 + 0x5c, 0);
            }
            m.write32(a5 + 0x28, 0);
            m.write16(a5 + 0x3a, 0x7fff);
            m.write16(a5 + 0x16, 0xffc0);
            m.write16(a5 + 0x1a, 0x40);
            if (d6 != 0x34) {
                m.write16(a5 + 0x4e, 0);
            }
        }
        m.write16(a0 + 0x10, m.read16(0xbda4));
        m.write16(0xbda4, d6);
        int t = (m.read16(0xa04c + d6) + m.read16(a5 + 0x4e)) & 0xffff;
        m.write16(a5 + 0x50, m.read16(0xa04c + 0x38 + (short) t));
        c.d[6] = t; // moveq (upper word 0), then move.w / add.w
        c.a[0] = 0xa04c;
    }

    // ------------------------------------------------------------------ helpers

    /** 0x85D8: score (3 BCD bytes at $10AB0) += the 3 bytes before a0 ($10ABD adds to the top). */
    static void addScore(GameMemory m, int a0) {
        boolean x = false;
        int[] src = {a0 - 1, a0 - 2, 0x10abd};
        int[] dst = {0x10ab2, 0x10ab1, 0x10ab0};
        for (int i = 0; i < 3; i++) {
            int s = m.read8(src[i]), d = m.read8(dst[i]);
            int t = (d & 0x0f) + (s & 0x0f) + (x ? 1 : 0);
            if (t > 9) {
                t += 6;
            }
            t += (d & 0xf0) + (s & 0xf0);
            x = t > 0x99;
            if (x) {
                t -= 0xa0;
            }
            m.write8(dst[i], t & 0xff);
        }
    }

    /** RNG step of $10894/$10898 (as in 0xF68A / 0xD87A); returns the new d2 word. */
    static int random(GameMemory m) {
        int d0 = m.read32(0x10894), d2 = m.read32(0x10898);
        int lo = (d2 ^ d0) & 0xffff;
        lo = ((lo >>> 1) | (lo << 15)) & 0xffff;
        d2 = (d2 & 0xffff0000) | lo;
        d0 = (d0 & 0xffff0000) | ((d0 ^ lo) & 0xffff);
        d0 = (d0 << 16) | (d0 >>> 16);
        m.write32(0x10898, d0);
        m.write32(0x10894, d2);
        return (short) lo;
    }

    /** 0xF7E2: next pan step from the previous one v (-1, 0, 1) and a random word. */
    static int direction(int v, int r) {
        if (v < 0) {
            return r < 0 ? 1 : 0;
        }
        if (v == 0) {
            return r >= 0 ? -1 : 1;
        }
        return r >= 0 ? -1 : 0;
    }

    private static void sound(M68k c, int d0, int d1) {
        c.d[0] = (c.d[0] & 0xffff0000) | (d0 & 0xffff);
        c.d[1] = (c.d[1] & 0xffff0000) | (d1 & 0xffff);
        Calls.call(c, 0x7bc8);
    }
}
