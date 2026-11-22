package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * 0xBEA8: Stix once thrown (or planted, or coming back), state +$9C. Exact port, with the
 * oddities of the original kept (they are part of the behaviour):
 * <ul>
 *   <li>state 0 (0xC020): flying, +$34 frames left, then state 2;</li>
 *   <li>state 2 (0xC2EC): slowing down along the axis +$44 points to, then state 4;</li>
 *   <li>state 4 (0xC342): coming back to Bubba (acceleration ±1 clamped to ±12, direction
 *       +$A turned one step towards him, 0xC574); caught when close;</li>
 *   <li>state 6 (0xC46E): riding a carrier (+$44) / planted (+$34 &lt; 0: shaking);</li>
 *   <li>state 8 (0xC520): bounced off a wall / solid object, decelerating, spinning;</li>
 *   <li>state A (0xC2C4): bounced off an enemy (speed halved each frame; the original writes the
 *       halved speed to the absolute address $28 instead of +$28);</li>
 *   <li>state C (0xBF60): stuck in the ground, animation script +$C (tables 0xBFB4 / 0xBFC4);</li>
 *   <li>state -2 (0xBF06, entry just before the table): carried, +$44 cleared.</li>
 * </ul>
 * Farther than $1B0 / $138 from Bubba in states 6..C, Stix comes back (state 4). The flying
 * states then test (0xC03E) the map, the solid objects and the enemies (+$9D hit points) with
 * the box $BDCA..$BDD0.
 */
final class StixPort {

    private static final int S = 0x10988, H = 0x10910, FLAGS = 0x1750b;


    private StixPort() {
    }

    static void register(Map<Integer, Ports.Port> ports) {
        ports.put(0xbea8, StixPort::run);
    }

    /** Registers d0-d7 / a3 / a5 kept as in the original around the calls it makes. */
    private static final class St {
        final M68k c;
        final GameMemory m;
        int d0, d1, d2, d3, d5, d6, d7, a3, a5;

        St(M68k c) {
            this.c = c;
            m = c.mem;
        }

        int w(int a) {
            return (short) m.read16(a);
        }

        void call(int addr) {
            c.d[0] = d0;
            c.d[1] = d1;
            c.d[2] = d2;
            c.d[3] = d3;
            c.d[5] = d5;
            c.d[6] = d6;
            c.d[7] = d7;
            c.a[0] = S;
            c.a[1] = H;
            c.a[3] = a3;
            c.a[5] = a5;
            Calls.call(c, addr);
            d0 = c.d[0];
            d1 = c.d[1];
            d2 = c.d[2];
            d3 = c.d[3];
            d5 = c.d[5];
            d6 = c.d[6];
            d7 = c.d[7];
            a3 = c.a[3];
            a5 = c.a[5];
        }

        /** 0xBDD8: sound d0 at Stix (all registers kept). */
        void sound(int n) {
            int[] regs = c.saveRegisters();
            c.d[0] = n;
            c.d[1] = 0;
            c.a[0] = S;
            c.a[5] = S;
            Calls.call(c, 0x7bc8);
            c.restoreRegisters(regs);
        }

        void flag(int bit, boolean on) {
            int v = m.read8(FLAGS);
            m.write8(FLAGS, on ? v | (1 << bit) : v & ~(1 << bit));
        }
    }

    static void run(M68k c) {
        St s = new St(c);
        GameMemory m = s.m;
        m.write16(S + 0x82, m.read16(S + 0x82) & 0xff7d);
        int state = s.w(S + 0x9c);
        if (state > 4) {
            int dx = (short) (m.read16(S + 2) - m.read16(H + 2));
            if ((short) Math.abs(dx) >= 0x1b0) {
                back(s);
                return;
            }
            int dy = (short) (m.read16(S + 6) - m.read16(H + 6) + 0x20);
            if ((short) Math.abs(dy) >= 0x138) {
                back(s);
                return;
            }
        }
        int target = 0xbef8 + s.w(0xbef8 + state);
        switch (target) {
            case 0xc020 -> flying(s);
            case 0xc2ec -> slowing(s);
            case 0xc342 -> coming(s);
            case 0xc46e -> riding(s);
            case 0xc520 -> bounced(s);
            case 0xc2c4 -> hitEnemy(s);
            case 0xbf60 -> stuck(s);
            case 0xbf06 -> { // state -2 (the word before the table): held by Bubba
                m.write32(S + 0x44, 0);
                s.flag(2, false);
            }
            default -> throw new IllegalStateException(String.format("Stix state %d -> $%X", state, target));
        }
    }

    // ------------------------------------------------------------------ states

    /** 0xBF18 / 0xBF1C: stuck in the ground (d1 = 7: start of the animation, 0: end). */
    private static void stick(St s, int d1) {
        GameMemory m = s.m;
        m.write32(0xbf14, m.read32(S + 0x44));
        m.write16(S + 0x9c, 0xc);
        int d0 = 0xc0000;
        int a2 = 0xbfb4;
        if (s.w(S + 0xa) >= 8) {
            a2 = 0xbfc4;
            d0 = -d0;
        }
        a2 += 2 * (short) d1;
        m.write32(S + 0xc, a2);
        m.write16(S + 0x4e, 8 - d1);
        m.write16(S + 0x34, 0);
        m.write32(S + 0x44, d0);
        m.write32(S + 0x28, 0);
        stuck(s);
    }

    /** 0xBF60. */
    private static void stuck(St s) {
        GameMemory m = s.m;
        int t = (byte) (m.read8(S + 0x34) - 1);
        m.write8(S + 0x34, t);
        if (t <= 0) {
            int n = (short) (m.read16(S + 0x4e) - 1);
            m.write16(S + 0x4e, n);
            if (n < 0) {
                endStuck(s);
                return;
            }
            int a2 = m.read32(S + 0xc);
            m.write8(S + 0x34, m.read8(a2));
            m.write8(S + 0xb, m.read8(a2 + 1));
            m.write32(S + 0xc, a2 + 2);
        }
        s.flag(1, true);
        m.write16(S + 0x82, m.read16(S + 0x82) | 0x82);
        int carrier = m.read32(0xbf14);
        if (carrier == 0) {
            return;
        }
        if (m.read8(carrier + 0x9c) == 0) {
            planted(s);
            return;
        }
        follow(m, carrier);
    }

    private static void follow(GameMemory m, int carrier) {
        int vx = m.read32(carrier + 0x28), vy = m.read32(carrier + 0x2c);
        m.write32(S + 0x28, vx);
        m.write32(S + 0x2c, vy);
        m.write32(S + 2, m.read32(S + 2) + vx);
        m.write32(S + 6, m.read32(S + 6) + vy);
    }

    /** 0xBFD4: out of the ground, flying again. */
    private static void endStuck(St s) {
        GameMemory m = s.m;
        s.sound(0xb);
        int carrier = m.read32(0xbf14);
        if (carrier != 0) {
            m.write8(carrier + 0x9c, 0);
        }
        m.write16(S + 0x34, 8);
        m.write32(S + 0x28, m.read32(S + 0x44));
        m.write32(S + 0x2c, 0);
        m.write16(S + 0xa, (m.read16(S + 0xa) & 1) * 8 + 4);
        launch(s);
    }

    /** 0xC006. */
    private static void launch(St s) {
        GameMemory m = s.m;
        if (m.read32(S + 0x28) == 0) {
            back(s);
            return;
        }
        m.write32(S + 0x44, S + 0x28);
        m.write8(0xbdd7, 0xff);
        m.write16(S + 0x9c, 0);
        flying(s);
    }

    /** 0xC020: state 0. */
    private static void flying(St s) {
        GameMemory m = s.m;
        s.flag(2, true);
        int t = (short) (m.read16(S + 0x34) - 1);
        m.write16(S + 0x34, t);
        if (t < 0) {
            m.write16(S + 0x9c, 2);
            slowing(s);
            return;
        }
        m.write32(S + 2, m.read32(S + 2) + m.read32(S + 0x28));
        m.write32(S + 6, m.read32(S + 6) + m.read32(S + 0x2c));
        collide(s);
    }

    /** 0xC2C4: state A. */
    private static void hitEnemy(St s) {
        GameMemory m = s.m;
        int n = (short) (m.read16(S + 0x4e) - 1);
        m.write16(S + 0x4e, n);
        if (n < 0) {
            back(s);
            return;
        }
        int d0 = m.read32(S + 0x28) >> 1, d1 = m.read32(S + 0x2c) >> 1;
        m.write32(0x28, d0); // movem.l d0-d1, $28.w: absolute address in the original
        m.write32(0x2c, d1);
        m.write32(S + 2, m.read32(S + 2) + d0);
        m.write32(S + 6, m.read32(S + 6) + d1);
        collide(s);
    }

    /** 0xC2EC: state 2. */
    private static void slowing(St s) {
        GameMemory m = s.m;
        s.flag(2, true);
        int a2 = m.read32(S + 0x44);
        int d0 = (short) m.read16(a2) < 0 ? 0x30000 : 0xfffd0000;
        int v = m.read32(a2) + d0;
        m.write32(a2, v);
        if (v >= 0 ? v <= 0x60000 : v >= 0xfffa0000) {
            back(s);
            return;
        }
        m.write32(a2 - 0x26, m.read32(a2 - 0x26) + v);
        collide(s);
    }

    /** 0xC324: back to Bubba. */
    private static void back(St s) {
        GameMemory m = s.m;
        m.write32(S + 0x44, 0);
        m.write16(S + 0x34, 0);
        m.write16(S + 0x9c, 4);
        coming(s);
    }

    /** 0xC342: state 4. */
    private static void coming(St s) {
        GameMemory m = s.m;
        s.flag(2, true);
        int d4 = (short) ((s.w(H + 0x1a) >> 1) + m.read16(H + 0x16));
        int hy = (short) (m.read16(H + 6) + d4);
        d4 = (short) -d4;
        int d0 = (short) (m.read16(S + 2) - m.read16(H + 2));
        int d1 = (short) (m.read16(S + 6) - hy);
        int ax = (short) Math.abs(d0), ay = (short) Math.abs(d1);
        if (ax <= 0x10 && ay <= 0x18 && m.read16(0xbda4) != 0x34) {
            m.write8(H + 0x56, 0xff);
            s.flag(2, false);
            return;
        }
        if ((short) (m.read16(0xbdd2) ^ d0) < 0) { // previous dx / dy ($BDD2 / $BDD4)
            int v = m.read32(S + 0x28);
            m.write32(S + 0x28, v - (v >> 2));
        }
        if ((short) (m.read16(0xbdd4) ^ d1) < 0) {
            int v = m.read32(S + 0x2c) >> 1;
            m.write32(S + 0x2c, v - (v >> 2));
        }
        m.write16(0xbdd2, d0);
        m.write16(0xbdd4, d1);
        int vx = (d0 < 0 ? 0x10000 : -0x10000) + m.read32(S + 0x28);
        if (vx < 0) {
            if (vx < 0xfff40000) {
                vx = 0xfff40000;
            }
        } else if (vx > 0xc0000) {
            vx = 0xc0000;
        }
        int vy = (d1 < 0 ? 0x10000 : -0x10000) + m.read32(S + 0x2c);
        if (vy < 0xfff40000) {
            vy = 0xfff40000;
        }
        m.write32(S + 0x28, vx);
        m.write32(S + 0x2c, vy);
        m.write32(S + 2, m.read32(S + 2) + vx);
        m.write32(S + 6, m.read32(S + 6) + vy);
        int dir = direction(s.w(S + 2), s.w(S + 6), s.w(H + 2), (short) (m.read16(H + 6) - d4));
        int diff = (short) (dir - m.read16(S + 0xa));
        if (diff != 0) {
            int step;
            if (diff < 0) {
                step = diff + 8 >= 0 ? -1 : 1;
            } else {
                step = diff - 8 < 0 ? 1 : -1;
            }
            m.write16(S + 0xa, (m.read16(S + 0xa) + step) & 0xf);
        }
        collide(s);
    }

    /** 0xC46E: state 6. */
    private static void riding(St s) {
        GameMemory m = s.m;
        int carrier = m.read32(S + 0x44);
        if (carrier != 0) {
            if (m.read8(carrier + 0x9c) == 0) {
                s.sound(0xb);
                launch(s);
                return;
            }
            follow(m, carrier);
        }
        planted(s);
    }

    /** 0xC49E. */
    private static void planted(St s) {
        GameMemory m = s.m;
        s.flag(1, true);
        m.write16(S + 0x82, m.read16(S + 0x82) | 0x82);
        int d0 = s.w(S + 0x34);
        if (d0 < 0) {
            d0 = (short) (d0 + 1);
            int d1 = m.read32(0xc50c + (short) (((d0 + 4) << 2) & 0xffff));
            if ((m.read8(S + 0x30) & 0x80) != 0) {
                d1 = -d1;
            }
            m.write32(S + 2, m.read32(S + 2) + d1);
            if (d0 >= 0) {
                m.write16(S + 0x34, 0xfa);
                m.write16(S + 0xa, (m.read16(S + 0xa) & 1) * 8 + 4);
                collide(s);
                return;
            }
        } else {
            if (m.read16(0x7ef8) != 2 && m.read16(0xbda4) == 0x26) {
                stick(s, 7);
                return;
            }
            d0 = (short) (d0 - 1);
        }
        m.write16(S + 0x34, d0);
        if (d0 != 0) {
            collide(s);
        } else {
            stick(s, 0);
        }
    }

    /** 0xC520: state 8. */
    private static void bounced(St s) {
        GameMemory m = s.m;
        s.flag(2, false);
        int n = (short) (m.read16(S + 0x4e) - 1);
        m.write16(S + 0x4e, n);
        if (n <= 0) {
            back(s);
            return;
        }
        int vx = slow(m.read32(S + 0x28));
        m.write32(S + 0x28, vx);
        m.write32(S + 2, m.read32(S + 2) + vx);
        int vy = slow(m.read32(S + 0x2c));
        m.write32(S + 0x2c, vy);
        m.write32(S + 6, m.read32(S + 6) + vy);
        m.write16(S + 0xa, (m.read16(S + 0xa) - 1) & 0xf);
        collide(s);
    }

    /** 0xC560: speed towards 0 by $8000. */
    private static int slow(int v) {
        if (v >= 0) {
            v -= 0x8000;
            return v >= 0 ? v : 0;
        }
        v += 0x8000;
        return v < 0 ? v : 0;
    }

    // ------------------------------------------------------------------ 0xC03E: collisions

    private static void collide(St s) {
        GameMemory m = s.m;
        int x = s.w(S + 2), y = s.w(S + 6);
        if ((m.read8(FLAGS) & 4) == 0) {
            return;
        }
        int x0 = x - 4, y0 = y - 4, x1 = x + 4, y1 = y + 4;
        if (m.read8(0xbdd7) != 0) {
            if ((m.read8(H + 0x55) & 0x80) != 0) {
                x1 += 0x18;
            } else {
                x0 -= 0x18;
            }
        }
        m.write16(0xbdca, x0);
        m.write16(0xbdcc, y0);
        m.write16(0xbdce, x1);
        m.write16(0xbdd0, y1);
        if (s.w(S + 0x9c) < 4) {
            // the map: block attribute under Stix
            int a3 = m.read32(0x10888);
            int d3 = ((short) y >> 4 & 0xffff) * m.read16(0x10a48);
            d3 = (d3 & 0xffff0000) | ((d3 + 2 * ((short) x >> 4)) & 0xffff);
            d3 = (d3 & 0xffff0000) | m.read16(a3 + d3);
            d3 = (d3 & 0xffff0000) | m.read16(m.read32(0x10890) + d3);
            boolean wall = (d3 & 0x11) != 0;
            if (!wall) {
                // solid objects against the box
                int d0 = s.w(0xbdca), d1 = s.w(0xbdcc), d2 = s.w(0xbdce), b3 = s.w(0xbdd0);
                int n = m.read16(0x1767a);
                for (int i = 0; i < n; i++) {
                    int o = m.read32(0x1767c + 4 * i);
                    int e = (short) (m.read16(o + 2) + m.read16(o + 0x14));
                    if (d2 <= e) {
                        continue;
                    }
                    e = (short) (e + m.read16(o + 0x18));
                    if (d0 >= e) {
                        continue;
                    }
                    e = (short) (m.read16(o + 6) + m.read16(o + 0x16));
                    if (b3 <= e) {
                        continue;
                    }
                    e = (short) (e + m.read16(o + 0x1a));
                    if (d1 < e) {
                        m.write8(o + 0x67, 0xff);
                        wall = true;
                        break;
                    }
                }
            }
            if (wall) {
                m.write8(0xbdd6, 0xff);
                s.sound(6);
                m.write16(S + 0x9c, 8);
                m.write32(S + 0x28, -m.read32(S + 0x28));
                m.write32(S + 0x2c, -m.read32(S + 0x2c));
                m.write16(S + 0x4e, 0x20);
            }
        }
        enemies(s);
    }

    /** 0xC118: the enemies (+$9D hit points) touched. */
    private static void enemies(St s) {
        GameMemory m = s.m;
        int x = s.w(S + 2), y = s.w(S + 6);
        s.d0 = x - 0xc;
        s.d1 = y - 4;
        s.d2 = x + 0xc;
        s.d3 = y + 4;
        s.a3 = 0x17516;
        int n = m.read16(0x17514);
        if (m.read16(0xbda4) == 0x34) {
            n = 0;
        }
        s.d7 = n - 1;
        while (s.d7 >= 0) {
            int o = m.read32(s.a3);
            s.a3 += 4;
            s.a5 = o;
            if (m.read8(o + 0x9d) != 0 && m.read16(o + 0x3a) == 0 && inside(s, o)) {
                hit(s, o);
            }
            s.d7 = (short) (s.d7 - 1);
        }
        if (m.read8(0xbdd6) != 0) {
            s.a5 = S;
            s.d0 = 0x18;
            s.call(0xfb58);
            m.write8(0xbdd6, 0);
        }
    }

    private static boolean inside(St s, int o) {
        GameMemory m = s.m;
        int e = (short) (m.read16(o + 2) + m.read16(o + 0x14));
        if ((short) s.d2 <= e) {
            return false;
        }
        e = (short) (e + m.read16(o + 0x18));
        if ((short) s.d0 >= e) {
            return false;
        }
        e = (short) (m.read16(o + 6) + m.read16(o + 0x16));
        if ((short) s.d3 <= e) {
            return false;
        }
        e = (short) (e + m.read16(o + 0x1a));
        return (short) s.d1 < e;
    }

    /** 0xC1D6: Stix hits an enemy. */
    private static void hit(St s, int o) {
        GameMemory m = s.m;
        m.write8(0xbdd6, 0xff);
        int k = (m.read16(S + 0xa) << 2) & 0xffff;
        int vx = m.read16(0xc196 + (short) k) << 16, vy = m.read16(0xc198 + (short) k) << 16;
        m.write32(S + 0x28, vx);
        m.write32(S + 0x2c, vy);
        m.write16(S + 0x4e, 4);
        m.write16(S + 0x9c, 0xa);
        s.flag(2, false);
        m.write8(o + 0x67, 0xff);
        s.d5 = (vx & 0xffff0000) | m.read16(o + 2);
        s.d6 = (vy & 0xffff0000) | m.read16(o + 0x5c);
        s.d6 = (s.d6 & 0xffff0000) | ((s.d6 * 2) & 0xffff);
        int a4 = 0x111a6 + (short) s.d6;
        a4 += (short) m.read16(a4);
        if (m.read8(a4 + 0x17) == 0) {
            int left = (m.read8(o + 0x9d) - 1) & 0xff;
            m.write8(o + 0x9d, left);
            if (left == 0) {
                HeroContacts.addScore(m, o + 0x60);
                s.d5 = (s.d5 & 0xffff0000) | (s.d0 & 0xffff);
                s.d6 = (s.d6 & 0xffff0000) | (s.d1 & 0xffff);
                s.a5 = o;
                s.call(0xdc90);
                s.d1 = (s.d1 & 0xffff0000) | (s.d6 & 0xffff);
                s.d0 = (s.d0 & 0xffff0000) | (s.d5 & 0xffff);
            }
            m.write16(o + 0x3a, 8);
            return;
        }
        a4 += 0x18;
        int left = (m.read8(o + 0x9d) - 1) & 0xff;
        m.write8(o + 0x9d, left);
        if (left == 0) {
            HeroContacts.addScore(m, o + 0x60);
            a4 += 6;
        }
        m.write16(o + 0x84, m.read16(o + 0x82));
        m.write8(o + 0x83, m.read8(o + 0x83) & ~4);
        m.write32(o + 0xa2, m.read32(o + 0x10));
        m.write32(o + 0xa6, m.read32(o + 0x4c));
        m.write32(o + 0xaa, m.read32(o + 0x50));
        m.write32(o + 0xae, m.read32(o + 0xc));
        m.write32(o + 0xb2, m.read32(o + 0x32));
        m.write32(o + 0xb6, m.read32(o + 0x36));
        m.write32(o + 0x4c, 0);
        m.write32(o + 0x32, 0);
        m.write16(o + 0x3a, 0x7fff);
        int a2 = a4 + (short) m.read16(a4);
        a4 += 2;
        if ((short) m.read16(S + 0x28) >= 0) {
            a2 += 2;
            a4 += 2;
        }
        a2 += (short) m.read16(a2);
        m.write32(o + 0x10, a2);
        a4 += (short) m.read16(a4);
        m.write32(o + 0xc, a4);
        m.write16(o + 0x4e, 0);
        m.write16(o + 0x4c, 0);
        m.write16(o + 0x34, 0);
        m.write16(o + 0x32, 0);
    }

    // ------------------------------------------------------------------ 0xC574

    /**
     * Direction (0-15, 0 = up, clockwise) from (x0, y0) to (x1, y1): |dx| and |dy| compared
     * after doublings, branch for branch as the original.
     */
    static int direction(int x0, int y0, int x1, int y1) {
        int d2 = (short) (x1 - x0);
        int d3;
        if (d2 >= 0) {
            d3 = (short) (y1 - y0);
            if (d3 >= 0) {
                if (d3 < d2) { // C59A
                    d3 = (short) (d3 * 2);
                    if (d3 < d2) { // C5AA
                        d3 = (short) (d3 * 2);
                        return d3 > d2 ? 5 : 4;
                    }
                    d2 = (short) (d2 * 2);
                    return d3 > d2 ? 6 : 5;
                }
                d2 = (short) (d2 * 2);
                if (d3 < d2) { // C590
                    d3 = (short) (d3 * 2);
                    return d3 > d2 ? 7 : 6;
                }
                d2 = (short) (d2 * 2);
                return d3 > d2 ? 8 : 7;
            }
            d3 = (short) -d3; // C5B4
            if (d3 < d2) { // C5D4
                d3 = (short) (d3 * 2);
                if (d3 < d2) { // C5E4
                    d3 = (short) (d3 * 2);
                    return d3 < d2 ? 4 : 3;
                }
                d2 = (short) (d2 * 2);
                return d3 < d2 ? 3 : 2;
            }
            d2 = (short) (d2 * 2);
            if (d3 < d2) { // C5CA
                d3 = (short) (d3 * 2);
                return d3 < d2 ? 2 : 1;
            }
            d2 = (short) (d2 * 2);
            return d3 < d2 ? 1 : 0;
        }
        d2 = (short) -d2; // C5EE
        d3 = (short) (y1 - y0);
        if (d3 >= 0) {
            if (d3 < d2) { // C612
                d2 = (short) (d2 * 2);
                if (d3 < d2) { // C622
                    d3 = (short) (d3 * 2);
                    return d3 < d2 ? 12 : 11;
                }
                d2 = (short) (d2 * 2);
                return d3 < d2 ? 11 : 10;
            }
            d3 = (short) (d3 * 2);
            if (d3 < d2) { // C608
                d3 = (short) (d3 * 2);
                return d3 < d2 ? 10 : 9;
            }
            d2 = (short) (d2 * 2);
            return d3 < d2 ? 9 : 8;
        }
        d3 = (short) -d3; // C62C
        if (d3 < d2) { // C64C
            d3 = (short) (d3 * 2);
            if (d3 < d2) { // C65C
                d3 = (short) (d3 * 2);
                return d3 > d2 ? 13 : 12;
            }
            d2 = (short) (d2 * 2);
            return d3 > d2 ? 14 : 13;
        }
        d2 = (short) (d2 * 2);
        if (d3 < d2) { // C642
            d3 = (short) (d3 * 2);
            return d3 > d2 ? 15 : 14;
        }
        d2 = (short) (d2 * 2);
        return d3 > d2 ? 0 : 15;
    }
}
