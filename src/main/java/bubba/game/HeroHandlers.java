package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * Handlers of Bubba's state machine (table 0xA014, called by 0x9EFA with d0 = joystick byte and
 * the condition codes loaded from it: C = up, V = down, Z = left, N = right, X = $10; d1 = sub
 * state +$4E, a0 = $BDA6, a1 = Bubba). Exact ports at register level, like {@link HeroPhysics}.
 * <p>
 * A branch to code not ported yet is replaced by a call of that code on the interpreter: it ends
 * with the handler's own RTS, so calling it then returning is the same.
 * <p>
 * Work area at $BDA6 (a0): +0 timer, +2 counter, +4 animation script pointer, +8 delay,
 * +$A / +$B last idle animations, +$C previous state, +$E flag.
 */
final class HeroHandlers {

    private static final int[] ALL = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};

    private HeroHandlers() {
    }

    private interface Body {
        void run(R r);
    }

    private static void port(Map<Integer, Ports.Port> ports, int addr, Body body) {
        ports.put(addr, new Ports.Port() {
            @Override
            public void run(M68k c) {
                R r = new R(c);
                body.run(r);
                r.sync();
            }

            @Override
            public int[] outputs() {
                return ALL;
            }
        });
    }

    static void register(Map<Integer, Ports.Port> ports) {
        port(ports, 0xa3b2, R::state00);
        port(ports, 0xa54a, R::state02);
        port(ports, 0xa6c4, R::state04);
        port(ports, 0xa706, R::state06);
        port(ports, 0xa9f4, R::state08);
        port(ports, 0xaa88, R::state0a);
        port(ports, 0xabe6, R::state0c);
        port(ports, 0xacae, R::state0e);
        port(ports, 0xacc6, R::state10);
        port(ports, 0xad08, R::state12);
        port(ports, 0xadde, R::adde);
        port(ports, 0xae38, R::state14);
        port(ports, 0xb028, R::state16);
        port(ports, 0xb094, R::state18);
        port(ports, 0xb1d8, R::state1a);
        port(ports, 0xb248, R::state1c);
        port(ports, 0xb298, R::state1e);
        port(ports, 0xb2ca, R::state20);
        port(ports, 0xb2fe, R::state22);
    }

    private static int sw(int reg, int v) {
        return (reg & 0xffff0000) | (v & 0xffff);
    }

    private static int sb(int reg, int v) {
        return (reg & 0xffffff00) | (v & 0xff);
    }

    /** Registers and routines. */
    static final class R {
        final M68k c;
        final GameMemory m;
        int d0, d1, d2, d3, d4, d5, d6, d7, a0, a1, a2, a3, a4, a5, a6;

        R(M68k c) {
            this.c = c;
            m = c.mem;
            load();
        }

        void load() {
            d0 = c.d[0];
            d1 = c.d[1];
            d2 = c.d[2];
            d3 = c.d[3];
            d4 = c.d[4];
            d5 = c.d[5];
            d6 = c.d[6];
            d7 = c.d[7];
            a0 = c.a[0];
            a1 = c.a[1];
            a2 = c.a[2];
            a3 = c.a[3];
            a4 = c.a[4];
            a5 = c.a[5];
            a6 = c.a[6];
        }

        void sync() {
            c.d[0] = d0;
            c.d[1] = d1;
            c.d[2] = d2;
            c.d[3] = d3;
            c.d[4] = d4;
            c.d[5] = d5;
            c.d[6] = d6;
            c.d[7] = d7;
            c.a[0] = a0;
            c.a[1] = a1;
            c.a[2] = a2;
            c.a[3] = a3;
            c.a[4] = a4;
            c.a[5] = a5;
            c.a[6] = a6;
        }

        /** Code not ported yet: run it on the interpreter (it returns with the handler's RTS). */
        void jump(int addr) {
            sync();
            Calls.call(c, addr);
            load();
        }

        int r8(int a) {
            return m.read8(a);
        }

        int r16(int a) {
            return m.read16(a);
        }

        int s16(int a) {
            return (short) m.read16(a);
        }

        int r32(int a) {
            return m.read32(a);
        }

        void w8(int a, int v) {
            m.write8(a, v);
        }

        void w16(int a, int v) {
            m.write16(a, v);
        }

        void w32(int a, int v) {
            m.write32(a, v);
        }

        boolean up() {
            return (d0 & 1) != 0;
        }

        boolean down() {
            return (d0 & 2) != 0;
        }

        boolean left() {
            return (d0 & 4) != 0;
        }

        boolean right() {
            return (d0 & 8) != 0;
        }

        // ---------------------------------------------------------------- shared

        /** 0xA318: new state d0 (+$6C = walking, +$5B from the bit table 0xA340). */
        void setState() {
            w16(a1 + 0x4c, 0);
            w16(a1 + 0x5a, 0);
            w8(a1 + 0x6c, (d0 & 0xffff) == 2 ? 0xff : 0);
            w16(a0 - 2, d0);
            d0 = sw(d0, (d0 & 0xffff) >>> 1);
            d2 = sw(d2, d0);
            d0 = sw(d0, d0 & 7);
            d2 = sw(d2, (d2 & 0xffff) >>> 3);
            boolean bit = (r8(0xa340 + (short) d2) & (1 << (d0 & 7))) != 0;
            w8(a1 + 0x5b, bit ? 0xff : 0);
        }

        /** 0xA510: x speed towards 0 by $7BB4. */
        void brake() {
            a2 = a1 + 0x28;
            int v = r32(a2);
            if ((short) (v >>> 16) < 0) {
                v += 0x7bb4;
                w32(a2, v < 0 ? v : 0);
            } else {
                v -= 0x7bb4;
                w32(a2, v >= 0 ? v : 0);
            }
        }

        /** 0xA540: back to the previous state's sub-state, state 4. */
        void a540() {
            w16(a0, r16(0xbda4));
            d0 = 4;
            setState();
        }

        /** 0xA674: jump (up pressed). */
        void a674() {
            d0 = 0xff001078;
            d1 = 0;
            a67c();
        }

        void a67c() {
            d2 = 0x40000;
            if ((r8(a1 + 0x57) & 0x80) != 0) {
                d2 = 0x60000;
            }
            w32(a0, d0);
            w32(a0 + 4, d2);
            w32(a0 + 8, -d2);
            d2 = -d2;
            d0 = 6;
            setState();
        }

        /** 0xA6A8: in the air. */
        void a6a8() {
            if (s16(a1 + 0x2c) < 0) {
                d0 = 0x1078;
                d1 = 2;
            } else {
                d0 = 0x1001078;
                d1 = 4;
            }
            a67c();
        }

        // ---------------------------------------------------------------- calls

        /** 0xAAD0: sound d2 with pan d3 (d0-d1 / a0-a1 kept, a5 = Bubba). */
        void sound() {
            int s0 = d0, s1 = d1, sa0 = a0, sa1 = a1;
            d1 = sw(d1, d3);
            d0 = sw(d0, d2);
            a5 = a1;
            jump(0x7bc8);
            d0 = s0;
            d1 = s1;
            a0 = sa0;
            a1 = sa1;
        }

        /** bsr to a routine with d1 / a0 / a1 saved around it (movem). */
        void callKeeping(int addr) {
            int s1 = d1, sa0 = a0, sa1 = a1;
            jump(addr);
            d1 = s1;
            a0 = sa0;
            a1 = sa1;
        }

        // ---------------------------------------------------------------- state 2: walking

        /** 0xA54A. */
        void state02() {
            if (r8(a1 + 0x72) == 0) {
                a6a8();
                return;
            }
            d3 = 0x40000;
            d4 = d3;
            d5 = d3;
            if ((r8(a1 + 0x57) & 0x80) != 0) { // running
                d3 = 0x7bb4;
                d4 = d3;
                d3 += d3;
                d5 = 0x80000;
            }
            if (up()) {
                a674();
                return;
            }
            if (down()) {
                ab94();
                return;
            }
            if (left()) {
                d5 = -d5;
                if ((r8(a1 + 0x55) & 0x80) == 0) {
                    a530();
                    return;
                }
                d2 = r32(a1 + 0x28);
                if (d2 == 0) {
                    d2 = 0xfffe0000;
                } else {
                    d2 -= d3;
                    if (d2 < d5) {
                        d2 = d5;
                    }
                }
            } else if (right()) {
                if ((r8(a1 + 0x55) & 0x80) != 0) {
                    a530();
                    return;
                }
                d2 = r32(a1 + 0x28);
                if (d2 == 0) {
                    d2 = 0x20000;
                } else {
                    d2 += d3;
                    if (d2 > d5) {
                        d2 = d5;
                    }
                }
            } else {
                a60e();
                return;
            }
            // 0xA5C0
            if ((r8(a1 + 0x57) & 0x80) == 0 && s16(a0) > 0) {
                w16(a0, r16(a0) - 1);
            }
            a5d6();
        }

        /** 0xA530: turning round while walking. */
        private void a530() {
            d1 = sb(d1, r8(a1 + 0x57));
            if ((d1 & 0x80) == 0) {
                w32(a1 + 0x28, 0);
            }
            d1 = sw(d1, -2 * (byte) d1);
            a540();
        }

        /** 0xA5D6: speed set, walk animation. */
        private void a5d6() {
            w32(a1 + 0x28, d2);
            d2 = sw(d2, r16(a1 + 6) - 0x10 - r16(0x10a5e));
            if ((short) d2 >= 0) {
                w16(a0, 6);
                w8(a1 + 0x57, 0);
            }
            int v = ~r8(a1 + 0x4c) & 0xff;
            w8(a1 + 0x4c, v);
            if ((v & 0x80) != 0) {
                return;
            }
            d2 = 2;
            d3 = 0x12;
            if ((r8(a1 + 0x57) & 0x80) != 0) {
                d2 = 0x12;
                d3 = 0x1e;
            }
            d1 = sw(d1, d1 + 2);
            if ((short) d1 >= (short) d3) {
                d1 = sw(d1, d2);
            }
        }

        /** 0xA60E: no direction: slowing down. */
        private void a60e() {
            d2 = r32(a1 + 0x28);
            boolean stop;
            if (d2 >= 0) {
                d2 -= d4;
                stop = d2 <= 0x8000;
            } else {
                d2 += d4;
                stop = d2 >= 0xffff8000;
            }
            if (stop) {
                if (s16(a0) > 0) {
                    a652();
                } else {
                    w16(a0 + 0xc, 4);
                    a656();
                }
                return;
            }
            // 0xA62A
            if ((r8(a1 + 0x57) & 0x80) == 0) {
                w16(a0, 6);
                a5d6();
                return;
            }
            d3 = d2 < 0 ? -d2 : d2;
            if (d3 > 0x40000) {
                a5d6();
                return;
            }
            w8(a1 + 0x57, 0);
            d1 = 0;
            w8(a1 + 0x4c, 0xff);
            w16(a0, 6);
            a5d6();
        }

        /** 0xA652: standing again. */
        private void a652() {
            w16(a0 + 0xc, 0);
            a656();
        }

        private void a656() {
            w32(a1 + 0x28, 0);
            w8(a1 + 0x57, 0);
            d2 = sw(d2, 0);
            d2 = sb(d2, r8(0x10894));
            d2 = sw(d2, d2 + 0xfa);
            w32(a0, 0);
            w16(a0, d2);
            d1 = 0;
            d0 = 0;
            setState();
        }

        // ---------------------------------------------------------------- state 4: turning

        /** 0xA6C4. */
        void state04() {
            int v = ~r8(a1 + 0x4c) & 0xff;
            w8(a1 + 0x4c, v);
            if ((v & 0x80) != 0) {
                return;
            }
            w8(a1 + 0x55, ~r8(a1 + 0x55));
            a6ce();
        }

        /** 0xA6CE. */
        private void a6ce() {
            d2 = r32(a1 + 0x28);
            if (d2 < 0) {
                d2 = -d2;
            }
            d0 = sw(d0, r16(a0));
            d1 = 6;
            if ((d0 & 0xffff) == 0xc) {
                ab96();
                return;
            }
            if (d2 <= 0x8000) {
                a652();
                return;
            }
            d1 = 2;
            boolean run = d2 > 0x40000;
            w8(a1 + 0x57, run ? 0xff : 0);
            if (run) {
                d1 = 0x12;
            }
            w16(a0, 6);
            d0 = 2;
            setState();
        }

        // ---------------------------------------------------------------- state 6: jumping

        /** 0xA706. */
        void state06() {
            if (left()) {
                w8(a1 + 0x55, 0xff);
                d2 = r32(a1 + 0x28);
                if (d2 == 0) {
                    d2 = 0xfffe0000;
                } else {
                    int lim = r32(a0 + 8);
                    if (d2 < lim) {
                        brake();
                        a756();
                        return;
                    }
                    if (d2 == lim) {
                        a756();
                        return;
                    }
                    d2 -= 0x8000;
                }
                w32(a1 + 0x28, d2);
                a756();
                return;
            }
            if (!right()) {
                brake();
                a756();
                return;
            }
            w8(a1 + 0x55, 0);
            d2 = r32(a1 + 0x28);
            if (d2 == 0) {
                d2 = 0x20000;
            } else {
                int lim = r32(a0 + 4);
                if (d2 > lim) {
                    brake();
                    a756();
                    return;
                }
                if (d2 == lim) {
                    a756();
                    return;
                }
                d2 += 0x8000;
            }
            w32(a1 + 0x28, d2);
            a756();
        }

        /** 0xA756: the jump phases (+0 of the work area). */
        private void a756() {
            d2 = sb(d2, r8(a0));
            int ph = (byte) d2;
            if (ph < 0) {
                int v = ~r8(a1 + 0x4c) & 0xff;
                w8(a1 + 0x4c, v);
                if ((v & 0x80) != 0) {
                    return;
                }
                if ((r8(a0 + 1) & 0x80) != 0) {
                    w32(a1 + 0x2c, 0xfff00000);
                } else if (r8(a1 + 0x74) != 0) {
                    w32(a1 + 0x2c, 0xfff70000);
                } else {
                    w32(a1 + 0x2c, 0xfff44000);
                }
                w8(a1 + 0x72, 0);
                d1 = 2;
                w8(a0, r8(a0) + 1);
                return;
            }
            if (ph == 0) {
                if (r32(a1 + 0x2c) <= 0xfffd0000) {
                    return;
                }
                w8(a0, r8(a0) + 1);
            }
            // 0xA768
            d2 = sb(d2, d2 - 2);
            if ((d2 & 0xff) == 0) {
                a7fe();
                return;
            }
            if (r8(a1 + 0x72) != 0) {
                a7c8();
                return;
            }
            if ((short) d1 >= 0xc) {
                if (s16(a1 + 0x2c) >= 0) {
                    return;
                }
                w8(a0, 0);
                d1 = 2;
                return;
            }
            int v = ~r8(a1 + 0x4c) & 0xff;
            w8(a1 + 0x4c, v);
            if ((v & 0x80) != 0) {
                return;
            }
            d1 = sw(d1, d1 + 2);
        }

        /** 0xA7C8: landing. */
        private void a7c8() {
            w16(a1 + 0x16, 0xffc0);
            w16(a1 + 0x1a, 0x40);
            d2 = sw(d2, r16(0x108b6));
            if ((d2 & 0xffff) == 0 && r8(0x10aba) != 0) {
                b31e();
                return;
            }
            d0 &= ~1;
            w8(a0, r8(a0) + 1);
            d2 = 7;
            d3 = 0; // 0xAACE
            sound();
            if (s16(a1 + 0x58) >= 0xc) {
                w16(a0 + 2, 0x418);
            }
            a7fe();
        }

        /** 0xA7FE. */
        private void a7fe() {
            if (r8(a1 + 0x72) == 0) {
                a6a8();
                return;
            }
            if (down()) {
                ab94();
                return;
            }
            if (up()) {
                a674();
                return;
            }
            d1 = 0;
            int v = (r16(a0 + 2) << 1) & 0xffff;
            w16(a0 + 2, v);
            if ((v & 0x8000) != 0) {
                a6ce();
                return;
            }
            if ((r8(a0 + 3) & 0x80) == 0) {
                return;
            }
            d1 = 0xe;
        }

        // ---------------------------------------------------------------- states 8 / A: Stix

        /** 0xA9F4: throwing Stix. */
        void state08() {
            int v = ~r8(a1 + 0x4c) & 0xff;
            w8(a1 + 0x4c, v);
            if ((v & 0x80) != 0) {
                return;
            }
            if ((short) d1 >= 0x20 && r8(a1 + 0x72) != 0 && r32(a1 + 0x28) == 0) {
                d1 = sw(d1, d1 - 0x1e);
            }
            if (r16(a0 + 0x10) == 4) {
                d2 = 5;
                d3 = sw(d3, 0x8c);
                sound();
            }
            d1 = sw(d1, d1 + 2);
            int t = (short) (r16(a0 + 0x10) - 1);
            w16(a0 + 0x10, t);
            if (t <= 0) {
                aa46();
                return;
            }
            if (r16(a0 + 0x10) == 2) {
                d7 = 0;
                int s1 = d1, sa0 = a0, sa1 = a1;
                w8(a1 + 0x56, 0);
                jump(0xbdea); // Stix leaves the hand
                d1 = s1;
                a0 = sa0;
                a1 = sa1;
            }
        }

        /** 0xAA88: hitting with Stix. */
        void state0a() {
            int v = ~r8(a1 + 0x4c) & 0xff;
            w8(a1 + 0x4c, v);
            if ((v & 0x80) == 0) {
                if ((short) d1 >= 0x2a && r8(a1 + 0x72) != 0) {
                    d1 = sw(d1, d1 - 0x2a);
                }
                d1 = sw(d1, d1 + 2);
                int t = (short) (r16(a0 + 0x10) - 1);
                w16(a0 + 0x10, t);
                if (t <= 0) {
                    aa46();
                    return;
                }
                w16(a0 + 0x18, r16(a0 + 0x18) - 1);
                if (r16(a0 + 0x18) == 1) {
                    d2 = 5;
                    d3 = 0;
                    sound();
                }
            }
            if (r16(a0 + 0x18) > 1) {
                return;
            }
            callKeeping(0xf7fe);
        }

        /** 0xAA46: end of the throw / hit, back to the state saved in +$C (sub-state +$E). */
        private void aa46() {
            d2 = sw(d2, d0);
            d0 = s16(a0 + 0xc);
            d1 = s16(a0 + 0xe);
            if ((d0 & 0xffff) == 0x10) {
                if ((d2 & 1) != 0) {
                    setState();
                } else {
                    a652();
                }
                return;
            }
            if ((d0 & 0xffff) == 0) {
                a652();
                return;
            }
            if ((d0 & 0xffff) != 6) {
                setState();
                return;
            }
            if (s16(a1 + 0x2c) < 0) {
                w8(a0, 0);
                d1 = 2;
                setState();
                return;
            }
            if (r8(a1 + 0x72) == 0) {
                setState();
                return;
            }
            a6ce();
        }

        // ---------------------------------------------------------------- state C entry

        /** 0xAB94 / 0xAB96: crouching. */
        void ab94() {
            d1 = 4;
            ab96();
        }

        private void ab96() {
            w8(a1 + 0x72, 0xff);
            w32(a1 + 0x2c, r32(a1 + 0x60));
            w16(a1 + 0x16, 0xffe0);
            w16(a1 + 0x1a, 0x20);
            w16(a0, 2);
            w16(a0 + 6, 1);
            d3 = 0;
            d2 = r32(a1 + 0x28);
            boolean zero;
            if (d2 < 0) {
                d2 += 0x20000;
                zero = d2 >= 0;
            } else {
                d2 -= 0x20000;
                zero = d2 <= 0;
            }
            if (zero) {
                d2 = 0;
                w16(a0 + 6, r16(a0 + 6) + 1);
            }
            d2 >>= 2;
            w32(a0 + 2, d2);
            d0 = 0xc;
            setState();
        }

        // ---------------------------------------------------------------- state C: crouching

        /** 0xABE6. */
        void state0c() {
            w8(a1 + 0x6c, 0);
            if (r8(a1 + 0x72) == 0) {
                a6a8();
                return;
            }
            if ((d1 & 0xffff) != 0) {
                boolean low = (short) d1 < 6;
                w8(a1 + 0x5b, low ? 0xff : 0);
                if (low) {
                    // 0xAC4C: getting down
                    if (r32(a1 + 0x28) != 0) {
                        d2 = r32(a0 + 2);
                        w32(a1 + 0x28, r32(a1 + 0x28) - d2);
                    }
                    int v = ~r8(a1 + 0x4c) & 0xff;
                    w8(a1 + 0x4c, v);
                    if ((v & 0x80) != 0) {
                        return;
                    }
                    d1 = sw(d1, d1 - 2);
                    int t = (short) (r16(a0 + 6) - 1);
                    w16(a0 + 6, t);
                    if (t > 0) {
                        return;
                    }
                    d1 = 6;
                    state0c();
                    return;
                }
            }
            if (!down() && !ceiling()) {
                // 0xAC8A: standing up
                w8(a1 + 0x5b, 0xff);
                w32(a1 + 0x28, 0);
                d1 = 0x10;
                int t = (short) (r16(a0) - 1);
                w16(a0, t);
                if (t >= 0) {
                    return;
                }
                w16(a1 + 0x16, 0xffc0);
                w16(a1 + 0x1a, 0x40);
                // the original loads the joystick into the condition codes, then these moves clear
                // C: the jump branch (0xACAA) is never taken
                a652();
                return;
            }
            if (left()) {
                if ((r8(a1 + 0x55) & 0x80) == 0) {
                    ac42();
                    return;
                }
                w32(a1 + 0x28, 0xfffe0000);
            } else if (right()) {
                if ((r8(a1 + 0x55) & 0x80) != 0) {
                    ac42();
                    return;
                }
                w32(a1 + 0x28, 0x20000);
            } else {
                w32(a1 + 0x28, 0);
                d1 = 0xe;
                return;
            }
            // 0xAC6E: crawling
            w8(a1 + 0x6c, 0xff);
            w8(a1 + 0x4c, r8(a1 + 0x4c) + 1);
            if ((byte) r8(a1 + 0x4c) < 3) {
                return;
            }
            w8(a1 + 0x4c, 0);
            d1 = sw(d1, d1 + 2);
            if ((short) d1 < 0xe) {
                return;
            }
            d1 = 6;
        }

        private void ac42() {
            w32(a1 + 0x28, 0);
            d1 = 4;
            a540();
        }

        /**
         * 0xAAE4: something above Bubba's head (map blocks with bit 0 on the 1 or 2 columns, or a
         * solid object)? Returns true when blocked (Z clear in the original).
         */
        private boolean ceiling() {
            d2 = r32(a1 + 6) + r32(a1 + 0x2c);
            d2 = (d2 << 16) | (d2 >>> 16);
            d2 = sw(d2, d2 - 0x40);
            d2 = sw(d2, ((short) d2 >> 4));
            d5 = sw(d5, r16(0x10a48));
            d2 = (d2 & 0xffff) * (d5 & 0xffff);
            d3 = sw(d3, r16(a1 + 2) + r16(a1 + 0x14));
            d4 = sw(d4, d3);
            d3 = sw(d3, ((short) d3 >> 4));
            d2 = sw(d2, d2 + d3);
            d2 = sw(d2, d2 + d3);
            d3 = 1;
            d4 = sw(d4, d4 & 0xf);
            if ((d4 & 0xffff) != 0) {
                d3 = 2;
            }
            a2 = d2 + r32(0x10888);
            a3 = r32(0x10890);
            while (true) {
                d2 = sw(d2, r16(a2));
                a2 += 2;
                if ((r8(a3 + d2 + 1) & 1) != 0) {
                    return true;
                }
                d2 = sw(d2, r16(a2 - 2 + (short) d5));
                if ((r8(a3 + d2 + 1) & 1) != 0) {
                    return true;
                }
                d3 = sw(d3, d3 - 1);
                if ((d3 & 0xffff) == 0xffff) {
                    break;
                }
            }
            d2 = sw(d2, r16(a1 + 0x14));
            d3 = 0xffffffc0;
            d4 = sw(d4, r16(a1 + 0x18));
            d5 = 0x20;
            d2 = sw(d2, d2 + r16(a1 + 2));
            d3 = sw(d3, d3 + r16(a1 + 6));
            d4 = sw(d4, d4 + d2);
            d5 = sw(d5, d5 + d3);
            a2 = 0x1767a;
            d7 = sw(d7, r16(a2));
            a2 += 2;
            while (true) {
                d7 = sw(d7, d7 - 1);
                if ((d7 & 0xffff) == 0xffff) {
                    break;
                }
                a3 = r32(a2);
                a2 += 4;
                d6 = sw(d6, r16(a3 + 2) + r16(a3 + 0x14));
                if ((short) d4 <= (short) d6) {
                    continue;
                }
                d6 = sw(d6, d6 + r16(a3 + 0x18));
                if ((short) d2 >= (short) d6) {
                    continue;
                }
                d6 = sw(d6, r16(a3 + 6) + r16(a3 + 0x16));
                if ((short) d5 <= (short) d6) {
                    continue;
                }
                d6 = sw(d6, d6 + r16(a3 + 0x1a));
                if ((short) d3 >= (short) d6) {
                    continue;
                }
                if ((short) d6 > (short) d5) {
                    continue;
                }
                d3 = 1;
                return true;
            }
            d3 = 0;
            return false;
        }

        // ---------------------------------------------------------------- states E / 10

        /** 0xACAE: turning round, then standing. */
        void state0e() {
            int v = ~r8(a1 + 0x4c) & 0xff;
            w8(a1 + 0x4c, v);
            if ((v & 0x80) != 0) {
                return;
            }
            w8(a1 + 0x55, ~r8(a1 + 0x55));
            a652();
        }

        /** 0xACC6: looking up (up held). */
        void state10() {
            if (r8(a1 + 0x72) == 0) {
                a6a8();
                return;
            }
            if (!up()) {
                a652();
                return;
            }
            boolean turn;
            if (left()) {
                if ((d1 & 0xffff) != 0) {
                    acf2();
                    return;
                }
                turn = (r8(a1 + 0x55) & 0x80) == 0;
            } else if (right()) {
                if ((d1 & 0xffff) != 0) {
                    acf2();
                    return;
                }
                turn = (r8(a1 + 0x55) & 0x80) != 0;
            } else {
                turn = false;
            }
            if (turn) {
                w8(a1 + 0x4c, 0);
                d1 = 2;
                return;
            }
            if ((d1 & 0xffff) == 0) {
                return;
            }
            acf2();
        }

        private void acf2() {
            int v = ~r8(a1 + 0x4c) & 0xff;
            w8(a1 + 0x4c, v);
            if ((v & 0x80) != 0) {
                return;
            }
            w8(a1 + 0x55, ~r8(a1 + 0x55));
            d1 = 0;
        }

        // ---------------------------------------------------------------- state 12: hurt

        /** 0xAD08. */
        void state12() {
            int v = ~r8(a1 + 0x4c) & 0xff;
            w8(a1 + 0x4c, v);
            if ((v & 0x80) != 0) {
                return;
            }
            d1 = sw(d1, d1 + 2);
            int t = (short) (r16(a0) - 1);
            w16(a0, t);
            if (t > 0) {
                return;
            }
            if (r16(a0 + 0x10) == 0x14) {
                d2 = sb(d2, r8(0x10aba));
                if ((d2 & 0xff) != 0) {
                    b300();
                    return;
                }
            }
            d2 = sb(d2, r8(a1 + 0x72) & r8(0x10aba));
            if ((d2 & 0xff) != 0) {
                b31e();
                return;
            }
            w16(0x10ac8, 0);
            int prev = r16(a0 + 0x10);
            if (prev == 0x16 || prev == 0x14) {
                d0 = sw(d0, r16(a1 + 6) - 0x30 - r16(0x10a5e));
                if ((short) d0 >= 0) {
                    w16(0x10ac8, 0xe80);
                    d1 = 2;
                    if (r8(a1 + 0x56) == 0) {
                        d1 = 0x10;
                    }
                    d0 = 0x14;
                    setState();
                    return;
                }
                w32(a1 + 0x5c, 0xe400);
                w32(a1 + 0x60, 0x10000);
                if (r8(a1 + 0x72) == 0) {
                    adcc();
                    return;
                }
                w16(a1 + 0x16, 0xffc0);
                w16(a1 + 0x1a, 0x40);
            }
            prev = r16(a0 + 0x10);
            boolean crouch = ((prev == 4 || prev == 8 || prev == 0x1a) && r16(a1 + 0x1a) == 0x20) || prev == 0xc;
            if (crouch && r8(a1 + 0x72) != 0) {
                ab94();
                return;
            }
            if (r8(a1 + 0x72) != 0) {
                a6ce();
                return;
            }
            adcc();
        }

        private void adcc() {
            d0 = 0x1078;
            d1 = 2;
            w8(a1 + 0x57, 0xff);
            a67c();
        }

        // ---------------------------------------------------------------- 0xADDE: into the water

        /** 0xADDE (from 0x9EFA), ends through the epilogue 0x9FD8 it pushed. */
        void adde() {
            w16(a1 + 0x16, 0xffe0);
            w16(a1 + 0x1a, 0x20);
            adee();
            sync();
            HeroStates.epilogue(c);
            load();
        }

        /** 0xADEE: the body of 0xADDE after it pushed the epilogue. */
        void adee() {
            w16(0x10ac8, 0xe80);
            d0 = r32(a1 + 0x28) >> 1;
            d1 = r32(a1 + 0x2c) >> 2;
            w32(a1 + 0x28, d0);
            w32(a1 + 0x2c, d1);
            w32(a1 + 0x5c, 0);
            w32(a1 + 0x60, 0);
            w8(a1 + 0x57, 0);
            d1 = 0;
            if (r8(a1 + 0x56) == 0) {
                d1 = 0x10;
            }
            if (r8(a1 + 0x72) == 0 && r16(0xbda4) != 0x16) {
                d2 = 8;
                d3 = 0;
                sound();
            }
            d0 = 0x14;
            setState();
        }

        // ---------------------------------------------------------------- state 14: swimming

        /** 0xAE38. */
        void state14() {
            d2 = sw(d2, r16(0x108b6));
            if ((d2 & 0xffff) == 0) {
                d2 = sb(d2, r8(0x10aba));
                if ((d2 & 0xff) != 0) {
                    b300();
                    return;
                }
            }
            if ((short) d1 >= 0xe) {
                if ((d1 & 0xffff) == 0xe) {
                    if (r8(a1 + 0x4c) == 2) {
                        d1 = 2;
                        w8(a1 + 0x4c, 0);
                    }
                } else {
                    if (r8(a1 + 0x4c) == 3) {
                        w8(a1 + 0x4c, 0);
                        d1 = sw(d1, d1 + 2);
                        if ((short) d1 >= 0x18) {
                            d1 = 0x12;
                        }
                    }
                    if (r8(a1 + 0x56) != 0) {
                        w8(a1 + 0x4c, 0);
                        d1 = 0xe;
                    }
                }
                d0 = sb(d0, d0 & 0xf0); // the stroke goes on: directions ignored
            }
            if (left()) {
                if ((r8(a1 + 0x55) & 0x80) == 0 && (d1 & 0xffff) != 0xc) {
                    d1 = 0xc;
                    w8(a1 + 0x4c, 0);
                }
                d2 = r32(a1 + 0x28);
                if (d2 == 0) {
                    d2 = 0xffff0000;
                } else {
                    d2 -= 0x4000;
                    if (d2 < 0xfffc0000) {
                        d2 = 0xfffc0000;
                    }
                }
            } else if (right()) {
                if ((r8(a1 + 0x55) & 0x80) != 0 && (d1 & 0xffff) != 0xc) {
                    d1 = 0xc;
                    w8(a1 + 0x4c, 0);
                }
                d2 = r32(a1 + 0x28);
                if (d2 == 0) {
                    d2 = 0x10000;
                } else {
                    d2 += 0x4000;
                    if (d2 > 0x40000) {
                        d2 = 0x40000;
                    }
                }
            } else {
                d2 = r32(a1 + 0x28);
                if (d2 >= 0) {
                    d2 -= 0x2000;
                    if (d2 < 0) {
                        d2 = 0;
                    }
                } else {
                    d2 += 0x2000;
                    if (d2 >= 0) {
                        d2 = 0;
                    }
                }
            }
            w32(a1 + 0x28, d2);
            // vertical: current of the level ($10A58 / $10A5C), up / down
            d2 = r32(0x10a58) * 2;
            d3 = sb(d3, r8(0x10a5c));
            if ((byte) d3 > 0) {
                d2 = -d2;
            }
            d2 += r32(a1 + 0x2c);
            d3 = sw(d3, r16(a1 + 6) - 0x20 - r16(0x10a5e));
            boolean surface = false;
            if (up()) {
                if ((short) d3 >= 0) {
                    d2 = r32(a1 + 0x2c) - 0x8000;
                    if (d2 < 0xfffa0000) {
                        d2 = 0xfffa0000;
                    }
                } else if (d2 > 0xfffc0000) {
                    d2 = afa2();
                } else {
                    surface = true;
                }
            } else if (down()) {
                if ((short) d3 < 0 && d2 <= 0xfffc0000) {
                    surface = true;
                } else {
                    d2 = r32(a1 + 0x2c) + 0x4000;
                    if (d2 > 0x40000) {
                        d2 = 0x40000;
                    }
                }
            } else if ((short) d3 >= 0) {
                d2 = r32(a1 + 0x2c);
                if (d2 > 0xffff0000) {
                    d2 += 0xffffc000;
                }
            } else if (d2 <= 0xfffc0000) {
                surface = true;
            } else {
                d2 = afa2();
            }
            if (surface) {
                // 0xAF3E: jumping out of the water
                w32(a0, d2);
                d1 = 0;
                w8(a1 + 0x73, 0);
                w16(0x10ac8, 0);
                d0 = 0x16;
                setState();
                return;
            }
            w32(a1 + 0x2c, d2);
            if (r8(a1 + 0x72) != 0) {
                d3 = sw(d3, d3 - 0x10);
                if ((short) d3 < 0) {
                    w32(a1 + 0x60, 0x10000);
                    w32(a1 + 0x5c, 0xe400);
                    d1 = 6;
                    w16(0x10ac8, 0);
                    ab96();
                    return;
                }
            }
            // 0xAFF6: swim animation
            w8(a1 + 0x4c, r8(a1 + 0x4c) + 1);
            if ((short) d1 < 0xe && (byte) r8(a1 + 0x4c) >= 3) {
                w8(a1 + 0x4c, 0);
                if ((d1 & 0xffff) == 0xc) {
                    w8(a1 + 0x55, ~r8(a1 + 0x55));
                    d1 = 2;
                } else {
                    d1 = sw(d1, d1 + 2);
                    if ((short) d1 >= 0xc) {
                        d1 = 2;
                    }
                }
            }
            w8(a1 + 0x72, 0);
        }

        /** 0xAFA2: floating up towards the surface. */
        private int afa2() {
            int v = r32(a1 + 0x2c);
            if (v < 0x20000) {
                v += 0xaaaa;
            }
            return v;
        }

        // ---------------------------------------------------------------- states 16 / 18

        /** 0xB028: out of the water. */
        void state16() {
            int v = ~r8(a1 + 0x4c) & 0xff;
            w8(a1 + 0x4c, v);
            if ((v & 0x80) != 0 || r8(a1 + 0x73) != 0) {
                return;
            }
            w32(a1 + 0x60, 0x10000);
            w32(a1 + 0x5c, 0xe400);
            w8(a1 + 0x4c, 0xff);
            if (ceiling()) {
                w32(a1 + 0x2c, 0x20000);
                w8(a1 + 0x73, 0xff);
                adee();
                return;
            }
            w16(a1 + 0x16, 0xffc0);
            w16(a1 + 0x1a, 0x40);
            d2 = r32(a0);
            if (d2 <= 0xfff80000) {
                d2 = 0;
            } else {
                d2 >>= 2;
                d3 = d2 >> 1;
                d2 += d3;
                d2 += r32(a0);
            }
            w32(a1 + 0x2c, d2);
            d0 = 0x1078;
            d1 = 2;
            a67c();
        }

        /** 0xB094. */
        void state18() {
            int v = ~r8(a1 + 0x4c) & 0xff;
            w8(a1 + 0x4c, v);
            if ((v & 0x80) == 0) {
                a652();
            }
        }

        // ---------------------------------------------------------------- states 1A-22: Stix actions

        private void flag1750b(int bit) {
            w8(0x1750b, r8(0x1750b) | (1 << bit));
        }

        /** 0xB1D8. */
        void state1a() {
            if (r8(a1 + 0x72) == 0) {
                a6a8();
                return;
            }
            if (r8(a0 + 6) != 0) {
                w8(a0 + 6, 0);
                if (r16(a0 + 2) != 0xc) {
                    d1 = sw(d1, d1 + 2);
                    d2 = r32(a1 + 0x48);
                    if (d2 == 0) {
                        return;
                    }
                    a2 = d2;
                    if (r32(a2 + 0x2c) != 0) {
                        b238();
                    }
                    return;
                }
            }
            w8(a1 + 0x4c, r8(a1 + 0x4c) + 1);
            if ((byte) r8(a1 + 0x4c) < 3) {
                return;
            }
            w8(a1 + 0x4c, 0);
            int t = (short) (r16(a0) - 1);
            w16(a0, t);
            if (t <= 0) {
                b238();
                return;
            }
            d1 = sw(d1, d1 + 2);
            d2 = sw(d2, r16(a0 + 4));
            if ((d2 & 0xffff) != r16(a0)) {
                return;
            }
            if (r16(a0 + 2) != 0xc) {
                w8(a1 + 0x4c, r8(a1 + 0x4c) - 2);
            }
            flag1750b(1);
        }

        /** 0xB238: end of the action, back to standing (or crouching). */
        private void b238() {
            if (r16(a0 + 2) != 0xc) {
                a652();
                return;
            }
            d1 = 0xe;
            ab96();
        }

        /** 0xB248. */
        void state1c() {
            if (r8(a1 + 0x72) == 0) {
                a6a8();
                return;
            }
            int x = s16(a0);
            if (x <= 3 && x >= 2) {
                flag1750b(1);
            }
            w8(a1 + 0x4c, r8(a1 + 0x4c) + 1);
            if ((byte) r8(a1 + 0x4c) < 3) {
                return;
            }
            w8(a1 + 0x4c, 0);
            int t = (short) (r16(a0) - 1);
            w16(a0, t);
            if (t <= 0) {
                b238();
                return;
            }
            d1 = sw(d1, d1 + 2);
            if (r16(a0) == 1) {
                w16(a0 + 0x12, r16(a0 + 0x12) - 0x14);
                d7 = 6;
                // 0xAA34
                int s1 = d1, sa0 = a0, sa1 = a1;
                w8(a1 + 0x56, 0);
                jump(0xbdea);
                d1 = s1;
                a0 = sa0;
                a1 = sa1;
                return;
            }
            if (r16(a0) == 3) {
                w8(a1 + 0x4c, r8(a1 + 0x4c) - 2);
            }
        }

        /** 0xB298. */
        void state1e() {
            int v = ~r8(a1 + 0x4c) & 0xff;
            w8(a1 + 0x4c, v);
            if ((v & 0x80) == 0) {
                int t = (short) (r16(a0) - 1);
                w16(a0, t);
                if (t <= 0) {
                    b238();
                    return;
                }
                d1 = sw(d1, d1 + 2);
            }
            int s = (short) d1;
            if (s < 4 || s > 0x14) {
                return;
            }
            if (s == 0x14 && (r8(a1 + 0x4c) & 0x80) == 0) {
                if ((d0 & 0x80) == 0) {
                    return;
                }
                d1 = 4;
                w16(a0, r16(a0) + 8);
            }
            flag1750b(6);
        }

        /** 0xB2CA. */
        void state20() {
            w8(a1 + 0x4c, r8(a1 + 0x4c) + 1);
            d0 = sw(d0, r16(a0));
            d0 = sb(d0, r8(0xb2f5 + (short) d0));
            if ((byte) d0 > (byte) r8(a1 + 0x4c)) {
                return;
            }
            w8(a1 + 0x4c, 0);
            int t = (short) (r16(a0) - 1);
            w16(a0, t);
            if (t <= 0) {
                b238();
                return;
            }
            if (r16(a0) == 3) {
                flag1750b(5);
            }
            d1 = sw(d1, d1 + 2);
        }

        /** 0xB2FE. */
        void state22() {
        }

        // ---------------------------------------------------------------- death entries

        /** 0xB300: drowned / fallen. */
        void b300() {
            w8(0x108e3, 0xec);
            d1 = 0x30;
            w16(a0, 0x29);
            w32(a1 + 0x2c, 0x10000);
            w16(0x10a58, 0);
            b330();
        }

        /** 0xB31E: out of energy. */
        void b31e() {
            w8(0x108e3, 0xf8);
            d1 = 0;
            w16(a0, 0x19);
            w32(a1 + 0x2c, 0);
            b330();
        }

        private void b330() {
            w32(a1 + 0x28, 0);
            w16(0x10ac8, 0x1220);
            d2 = sb(d2, r8(0x8276));
            if ((d2 & 0x80) != 0) {
                w8(0x108e2, 0xff);
                a2 = 0xb4c4;
                w16(a2 + 0x44, 0);
                w32(0x5a72, a2);
            }
            d0 = 0x24;
            setState();
        }

        // ---------------------------------------------------------------- state 0: standing

        /** 0xA3B2. */
        void state00() {
            if (r8(a1 + 0x72) == 0) {
                a6a8();
                return;
            }
            if (left()) {
                if ((r8(a1 + 0x55) & 0x80) == 0) {
                    a40a();
                    return;
                }
                w32(a1 + 0x28, 0xfffe0000);
                startWalk();
                return;
            }
            if (right()) {
                if ((r8(a1 + 0x55) & 0x80) != 0) {
                    a40a();
                    return;
                }
                w32(a1 + 0x28, 0x20000);
                startWalk();
                return;
            }
            if (up()) {
                a674();
                return;
            }
            if (down()) {
                ab94();
                return;
            }
            d2 = sb(d2, r8(0x108e9));
            if ((d2 & 0xff) != 0) {
                d2 = sb(d2, s16(a1 + 2) > 0x230 ? 0xff : 0);
                d3 = sb(d3, r8(a1 + 0x55));
                d2 = sb(d2, d2 ^ d3);
                if ((d2 & 0x80) != 0) {
                    a40a();
                    return;
                }
                d1 = 0;
                d0 = 0x36;
                setState();
                return;
            }
            idle();
        }

        /** 0xA3DE. */
        private void startWalk() {
            w16(a0, 6);
            d1 = 2;
            boolean run = s16(a0 + 0xc) > 0;
            w8(a1 + 0x57, run ? 0xff : 0);
            if (run) {
                w16(a0, 0);
                d1 = 0x12;
            }
            d3 = 0x40000;
            if ((r8(a1 + 0x55) & 0x80) != 0) {
                d3 = -d3;
            }
            w32(a1 + 0x28, d3);
            d0 = 2;
            setState();
        }

        /** 0xA40A: turning round. */
        private void a40a() {
            d1 = 2;
            if (r32(a1 + 0x28) != 0) {
                a540();
                return;
            }
            d0 = 0xe;
            setState();
        }

        /** 0xA440: no direction: idle animations. */
        private void idle() {
            if (s16(a0 + 0xc) > 0) {
                w16(a0 + 0xc, r16(a0 + 0xc) - 1);
            }
            a2 = 0x10a67;
            d2 = sb(d2, r8(a2));
            if ((d2 & 0xff) != 0) {
                d2 = sb(d2, (d2 & 0x80) != 0 ? 0xff : 0);
                w8(a2, 0);
                if ((d2 & 0xff) == r8(a1 + 0x55)) {
                    boolean push;
                    if ((d2 & 0x80) != 0) {
                        push = !(r32(a1 + 0x40) > 0xfffe0000);
                    } else {
                        push = !(r32(a1 + 0x40) < 0x20000);
                    }
                    if (push) {
                        d1 = 0x2e;
                        brake();
                        return;
                    }
                }
            }
            if ((d1 & 0xffff) == 0x2e) {
                d1 = 0;
            }
            int t = (short) (r16(a0) - 1);
            w16(a0, t);
            if (t > 0) {
                brake();
                return;
            }
            if (t == 0) {
                w16(a0 + 2, r16(a0 + 2) + 1);
                d2 = sw(d2, r16(0x10894) & 6);
                if ((d2 & 0xff) == r8(a0 + 0xa)) {
                    d2 = sw(d2, (d2 + 2) & 6);
                }
                while (true) {
                    if (s16(a0 + 2) < 7) {
                        w8(a0 + 0xa, d2);
                        w8(a0 + 0xe, 0);
                        break;
                    }
                    if ((d2 & 0xff) == r8(a0 + 0xb) || (r8(a1 + 0x56) == 0 && (d2 & 0xffff) == 2)) {
                        d2 = sw(d2, (d2 + 2) & 6);
                        continue;
                    }
                    w8(a0 + 0xb, d2);
                    w16(a0 + 2, 0);
                    d2 = sw(d2, d2 + 8);
                    w8(a0 + 0xe, 0xff);
                    break;
                }
                a2 = 0xa344 + s16(0xa344 + (short) d2);
                w32(a0 + 4, a2);
                w8(a0 + 8, 0);
            }
            // 0xA4E6: animation script (frame, delay) pairs, 0 = end
            int dl = (byte) (r8(a0 + 8) - 1);
            w8(a0 + 8, dl);
            if (dl > 0) {
                brake();
                return;
            }
            a2 = r32(a0 + 4);
            d1 = sw(d1, 0);
            d1 = sb(d1, r8(a2));
            a2++;
            if ((d1 & 0xff) == 0) {
                d2 = sw(d2, 0);
                d2 = sb(d2, (r8(0x10894) & 0xff) >>> 2);
                d2 = sw(d2, d2 + 0x19);
                w16(a0, d2);
                brake();
                return;
            }
            w8(a0 + 8, r8(a2));
            a2++;
            d1 = sw(d1, d1 * 2);
            w32(a0 + 4, a2);
            brake();
        }
    }
}
