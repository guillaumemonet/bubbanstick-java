package bubba.game;

import static bubba.game.Regs.*;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * Object update loop and effects: exact ports of
 * <ul>
 *   <li>0xDEE0: every active object (list $17514): timer +$3A, behaviour engine +$1F (table
 *       0xDF2C), position relative to Bubba +$86/+$88, counter +$8B, animation 0xDF38;</li>
 *   <li>0xDC90: put the object a5 in the destruction list $17782 (once);</li>
 *   <li>0xFB58: effect d0 (entry of the type table $111A6): particles ($44 bytes each, pool
 *       $1961A, list $175D8, at most 32), fixed or with random variations (count &lt; 0);</li>
 *   <li>0xFDBA: particles update (callback, animation, motion with acceleration and limits).</li>
 * </ul>
 */
final class ObjectLoop {

    private static final int PARTICLES = 0x175d8;

    private ObjectLoop() {
    }

    static void register(Map<Integer, Ports.Port> ports) {
        ports.put(0xdee0, ObjectLoop::update);
        ports.put(0xdc90, ObjectLoop::destroyLater);
        ports.put(0xfb58, ObjectLoop::effect);
        ports.put(0xfdba, ObjectLoop::particles);
    }

    /** 0xDEE0. */
    static void update(M68k c) {
        GameMemory m = c.mem;
        int n = m.read16(0x17514);
        for (int i = 0; i < n; i++) {
            int o = m.read32(0x17516 + 4 * i);
            if (m.read16(o + 0x3a) != 0) {
                m.write16(o + 0x3a, m.read16(o + 0x3a) - 1);
            }
            if (m.read32(o + 0x10) != 0) {
                int e = 0xdf2c + m.read8(o + 0x1f);
                c.a[5] = o;
                c.d[0] = m.read8(o + 0x1f);
                Calls.call(c, e + 2 + (short) m.read16(e + 2)); // the "bra.w" of the table
            }
            m.write16(o + 0x86, m.read16(o + 2) - m.read16(0x10912));
            m.write16(o + 0x88, m.read16(o + 6) - m.read16(0x10916));
            m.write8(o + 0x8b, m.read8(o + 0x8b) + 2);
            c.a[5] = o;
            Calls.call(c, 0xdf38);
        }
    }

    /** 0xDC90. */
    static void destroyLater(M68k c) {
        GameMemory m = c.mem;
        int o = c.a[5];
        int n = (short) m.read16(0x17782);
        for (int i = 0; i < n; i++) {
            if (m.read32(0x17784 + 4 * i) == o) {
                return;
            }
        }
        m.write16(0x17782, n + 1);
        m.write32(0x17784 + 4 * (n & 0xffff), o);
    }

    // ------------------------------------------------------------------ 0xFB58

    /** 0xFB58: d0 = effect type, a5 = object (position). */
    static void effect(M68k c) {
        GameMemory m = c.mem;
        int o = c.a[5];
        int a0 = 0x111a6 + 2 * (c.d[0] & 0xffff);
        a0 += (short) m.read16(a0);
        int count = (short) m.read16(a0);
        a0 += 2;
        if (count < 0) {
            randomBurst(m, o, a0, count);
            return;
        }
        for (int k = 0; k < count; k++) {
            int listCount = m.read16(PARTICLES);
            int p = m.read32(PARTICLES + 2);
            if ((short) listCount < 0x20) {
                m.write16(PARTICLES, listCount + 1);
                p = 0x195d6;
                do {
                    p += 0x44;
                } while ((short) m.read16(p) > 0);
            }
            m.write32(PARTICLES + 2 + 4 * listCount, p);
            int x = m.read16(o + 2), y = m.read16(o + 6);
            int a = p;
            m.write16(a, 3);
            a += 2;
            int d2 = (short) m.read16(a0);
            a0 += 2;
            m.write32(a, d2 > 0 ? a0 - 2 + d2 : 0);
            a += 4;
            x = (x + m.read16(a0)) & 0xffff;
            y = (y + m.read16(a0 + 2)) & 0xffff;
            a0 += 4;
            int anim = a0 + (short) m.read16(a0);
            a0 += 2;
            int d4 = m.read16(anim);
            anim += 2;
            int d5 = anim;
            int timer = m.read8(anim);
            int frame = m.read8(anim + 1) * 4;
            anim += 2;
            m.write32(a, anim);
            m.write16(a + 4, timer);
            m.write16(a + 6, d4);
            m.write32(a + 8, d5);
            m.write16(a + 12, frame);
            a += 14;
            int motion = a0 + (short) m.read16(a0);
            a0 += 2;
            int ax = 0, ay = 0;
            int cnt = m.read16(motion);
            motion += 2;
            if ((short) cnt >= 0) {
                ax = (short) m.read16(motion) << 8;
                ay = (short) m.read16(motion + 2) << 8;
                motion += 4;
            }
            m.write32(a, motion);
            m.write32(a + 4, (short) m.read16(a0) << 8);
            m.write32(a + 8, (short) m.read16(a0 + 2) << 8);
            a0 += 4;
            m.write32(a + 12, ax);
            m.write32(a + 16, ay);
            int lx = m.read16(a0) << 16;
            m.write32(a + 20, lx);
            m.write32(a + 24, -lx);
            int ly = m.read16(a0 + 2) << 16;
            m.write32(a + 28, ly);
            m.write32(a + 32, -ly);
            a0 += 4;
            m.write32(a + 36, x << 16);
            m.write32(a + 40, y << 16);
            m.write16(a + 44, cnt);
            m.write16(a + 46, 0);
        }
    }

    private static int lo(int v, int w) {
        return (v & 0xffff0000) | (w & 0xffff);
    }

    /** 0xFC2E: |count| particles (+ a random number), the same definition with random parts. */
    private static void randomBurst(GameMemory m, int o, int a6, int count) {
        int d0 = m.read32(0x10894), d1 = m.read32(0x10898);
        int d7 = -count;
        int d4 = m.read16(a6);
        a6 += 2;
        if (d4 != 0) {
            d1 += d0;
            d0 = (d0 >>> 1) | (d0 << 31);
            d1 += d0;
            d0 ^= d1;
            d4 &= d0;
            d7 = (short) (d7 + (d4 & 0xffff));
        }
        int d2 = m.read16(PARTICLES);
        int over = (short) (d2 + d7 - 0x20);
        if (over > 0) {
            d7 = (short) (d7 - over);
            if (d7 <= 0) {
                return;
            }
        }
        m.write16(PARTICLES, d2 + d7);
        int a4 = PARTICLES + 2 + 4 * d2;
        int a0 = 0x195d6 + 0x44;
        int a2 = 0;
        int d5 = 0, d6;
        for (int k = 0; k < (d7 & 0xffff); k++) {
            while ((short) m.read16(a0) > 0) {
                a0 += 0x44;
            }
            m.write32(a4, a0);
            a4 += 4;
            int a = a0;
            m.write16(a, 3);
            a += 2;
            a2 = 0;
            int v = (short) m.read16(a6);
            a6 += 2;
            if (v > 0) {
                a2 = a6 - 2 + v;
            }
            m.write32(a, a2);
            a += 4;
            int x = m.read16(a6);
            d4 = m.read16(a6 + 2);
            a6 += 4;
            if (d4 != 0) {
                d1 = lo(d1, d1 ^ d0);
                d1 = rorw(d1);
                d0 = lo(d0, d0 ^ d1);
                d0 = swap(d0);
                d4 &= d0;
                x += d4;
            }
            x = (x + m.read16(o + 2)) & 0xffff;
            int y = m.read16(a6);
            d4 = m.read16(a6 + 2);
            a6 += 4;
            if (d4 != 0) {
                d1 = lo(d1, d1 + d0);
                d1 = swap(d1);
                d0 = lo(d0, d0 ^ d1);
                d4 &= d0;
                y += d4;
            }
            y = (y + m.read16(o + 6)) & 0xffff;
            int a1 = a6 + (short) m.read16(a6);
            a6 += 2;
            d6 = (short) m.read16(a1);
            a1 += 2;
            if (d6 >= 0) {
                a2 = a1;
                d4 = m.read8(a1);
                d5 = m.read8(a1 + 1);
                a1 += 2;
            } else {
                d6 = -d6;
                d0 = lo(d0, d0 - d1);
                d0 = swap(d0);
                d1 = rorw(d1);
                d1 = lo(d1, d1 ^ d0);
                int r = d0 & 15;
                do {
                    r -= d6;
                } while ((short) r >= 0);
                r += d6;
                d5 = m.read8(a1 + (short) r);
                a1 = 0;
            }
            m.write32(a, a1);
            m.write16(a + 4, d4);
            m.write16(a + 6, d6);
            m.write32(a + 8, a2);
            m.write16(a + 12, (d5 * 4) & 0xffff);
            m.write32(a + 14, 0xfdb6);
            a += 18;
            int cnt = (short) m.read16(a6);
            d4 = m.read16(a6 + 2);
            a6 += 4;
            if (d4 != 0) {
                d0 = rorw(d0);
                d1 = lo(d1, d1 ^ d0);
                d0 = swap(d0);
                d4 &= d0;
                cnt = (short) (cnt + (short) d4);
            }
            int[] vals = new int[4];
            for (int j = 0; j < 4; j++) {
                int base = (short) m.read16(a6) << 8;
                d4 = m.read16(a6 + 2);
                a6 += 4;
                if (d4 != 0) {
                    switch (j) {
                        case 0 -> {
                            d0 = lo(d0, d0 - d1);
                            d1 = lo(d1, d1 ^ d0);
                            d1 = swap(d1);
                        }
                        case 1 -> {
                            d1 = rorw(d1);
                            d1 = lo(d1, d1 + d0);
                            d0 = lo(d0, d0 ^ d1);
                        }
                        case 2 -> {
                            d0 = swap(d0);
                            d1 = lo(d1, d1 ^ d0);
                            d0 = lo(d0, d0 - d1);
                        }
                        default -> {
                            d1 = swap(d1);
                            d0 = rorw(d0);
                            d1 = lo(d1, d1 ^ d0);
                            d0 = lo(d0, d0 + d1);
                        }
                    }
                    d4 &= d0;
                    base += (short) d4 << 8;
                }
                vals[j] = base;
            }
            for (int j = 0; j < 4; j++) {
                m.write32(a + 4 * j, vals[j]);
            }
            a += 16;
            int lx = m.read16(a6) << 16;
            int ly = m.read16(a6 + 2) << 16;
            a6 += 4;
            m.write32(a, lx);
            m.write32(a + 4, -lx);
            m.write32(a + 8, ly);
            m.write32(a + 12, -ly);
            m.write32(a + 16, x << 16);
            m.write32(a + 20, y << 16);
            m.write16(a + 24, cnt);
            m.write16(a + 26, 0);
            a6 -= 0x24;
        }
        m.write32(0x10894, d1);
        m.write32(0x10898, d0);
    }

    // ------------------------------------------------------------------ 0xFDBA

    /** 0xFDBA: every particle of the list $175D8. */
    static void particles(M68k c) {
        GameMemory m = c.mem;
        int n = m.read16(PARTICLES);
        for (int i = 0; i < n; i++) {
            int base = m.read32(PARTICLES + 2 + 4 * i);
            int cb = m.read32(base + 2);
            if (cb != 0) {
                c.a[0] = base;
                c.a[5] = base + 6;
                Calls.call(c, cb);
            }
            // animation: +6 pointer, +$A timer, +$C loops, +$E start, +$12 frame*4
            int anim = m.read32(base + 6);
            if (anim != 0) {
                int t = (m.read16(base + 0x0a) - 1) & 0xffff;
                m.write16(base + 0x0a, t);
                if (t == 0) {
                    int a0 = anim;
                    while (true) {
                        int d5 = m.read8(a0);
                        a0++;
                        if (d5 != 0) {
                            m.write16(base + 0x0a, d5);
                            m.write16(base + 0x12, (m.read8(a0) * 4) & 0xffff);
                            a0++;
                            m.write32(base + 6, a0);
                            break;
                        }
                        // end of the table (0xFDC6): loop count +$C
                        a0++;
                        int loops = (m.read16(base + 0x0c) - 1) & 0xffff;
                        m.write16(base + 0x0c, loops);
                        if ((short) loops > 0) {
                            a0 = m.read32(base + 0x0e);
                        } else if ((short) loops < 0) {
                            a0 = m.read32(base + 0x0e);
                            m.write16(base + 0x0c, 0);
                        } else {
                            m.write16(base + 0x0c, m.read16(a0));
                            a0 += 2;
                            m.write32(base + 0x0e, a0);
                        }
                    }
                }
            }
            // motion: +$14 pointer, +$18.. velocity, acceleration, limits, +$38/$3C position
            int a4 = base + 0x14;
            int motion = m.read32(a4);
            if (motion == 0) {
                continue;
            }
            int vx = m.read32(base + 0x18), vy = m.read32(base + 0x1c);
            int ax = m.read32(base + 0x20), ay = m.read32(base + 0x24);
            int maxx = m.read32(base + 0x28), minx = m.read32(base + 0x2c);
            int maxy = m.read32(base + 0x30), miny = m.read32(base + 0x34);
            m.write32(base + 0x38, m.read32(base + 0x38) + vx);
            m.write32(base + 0x3c, m.read32(base + 0x3c) + vy);
            vx += ax;
            vy += ay;
            if (vx > maxx) {
                vx = maxx;
            } else if (vx < minx) {
                vx = minx;
            }
            if (vy > maxy) {
                vy = maxy;
            } else if (vy < miny) {
                vy = miny;
            }
            m.write32(base + 0x18, vx);
            m.write32(base + 0x1c, vy);
            int cnt = (short) m.read16(base + 0x40);
            if (cnt < 0) {
                cnt++;
                m.write16(base + 0x40, cnt);
                if (cnt < 0) {
                    continue;
                }
            } else {
                cnt--;
                m.write16(base + 0x40, cnt);
                if (cnt > 0) {
                    continue;
                }
            }
            int a0 = motion;
            while (true) {
                int v = m.read16(a0);
                a0 += 2;
                m.write16(base + 0x40, v);
                if (v != 0) {
                    if ((short) v > 0) {
                        m.write32(base + 0x20, (short) m.read16(a0) << 8);
                        m.write32(base + 0x24, (short) m.read16(a0 + 2) << 8);
                        a0 += 4;
                    }
                    m.write32(base + 0x14, a0);
                    break;
                }
                int jump = (short) m.read16(a0);
                a0 += 2;
                if (jump == 0) {
                    // the particle ends: out of the list, structure freed
                    int k = (m.read16(PARTICLES) - 1) & 0xffff;
                    m.write16(PARTICLES, k);
                    int last = m.read32(PARTICLES + 2 + 4 * k);
                    for (int j = 0; j <= k; j++) {
                        if (m.read32(PARTICLES + 2 + 4 * j) == base) {
                            m.write32(PARTICLES + 2 + 4 * j, last);
                            break;
                        }
                    }
                    m.write16(base, 0);
                    // like the original, the loop goes on with the next slots (the moved last
                    // particle is still met at its old slot)
                    break;
                }
                a0 = a0 - 2 + jump;
            }
        }
    }
}
