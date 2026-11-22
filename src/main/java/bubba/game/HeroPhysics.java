package bubba.game;

import static bubba.game.Regs.*;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * 0xD042: Bubba's movement against the map and the solid objects, once per frame. Exact port of
 * the routine and of everything it calls (0xD604, 0xD688, 0xD6EA, 0xD7D2, 0xD9C6, 0xDC1C,
 * 0xCCCA, 0xCDF6); 0xD850 (hurt) is called through the port table.
 * <p>
 * These routines talk through registers and leave values in them that the next one uses, so
 * the port keeps the 68000 registers (fields d0-d7, a0-a4, a6, full 32 bits) and does the same
 * operations, word or long. Oddities of the original are kept (they are the game's behaviour):
 * e.g. 0xD452 compares d0 instead of d1, 0xD5EA tests a bit of d1 chosen by +$70.
 * <p>
 * Work variables: $CAC2 (slope step done), $CAC6 (+$72 of the previous frame), $CAC8 (block
 * attributes under the box, 0xCDF6), $CACA (top of the carrier), $CACC / $CAD0 (carrier speed),
 * $CAD4 (test the carriers), $CAD6 (y tested), $CAD8 (attributes under the feet, 0xCCCA).
 * Note: the disassembly shows the PC-relative btst/movem addresses 2 bytes too low.
 */
final class HeroPhysics {

    private static final int H = 0x10910;

    private HeroPhysics() {
    }

    static void register(Map<Integer, Ports.Port> ports) {
        ports.put(0xd042, new Ports.Port() {
            @Override
            public void run(M68k c) {
                new HeroPhysics.R(c).d042();
            }

            @Override
            public int[] outputs() {
                return new int[]{13}; // a5: the main loop goes on with it
            }
        });
    }

    private static int mulu(int a, int b) {
        return (a & 0xffff) * (b & 0xffff);
    }

    private static int asr4(int x) {
        return ((short) x >> 4) & 0xffff;
    }

    /** The registers and the routines. */
    static final class R {
        final M68k c;
        final GameMemory m;
        int d0, d1, d2, d3, d4, d5, d6, d7, a0, a1, a2, a3, a4, a6;

        R(M68k c) {
            this.c = c;
            m = c.mem;
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
            a6 = c.a[6];
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

        void bset70(int bit) {
            w8(H + 0x70, r8(H + 0x70) | (1 << bit));
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
            c.a[5] = H;
            c.a[6] = a6;
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
            a6 = c.a[6];
        }

        /** 0xD850 (Bubba hurt, d6 = kind). */
        void hurt() {
            sync();
            Calls.call(c, 0xd850);
            load();
        }

        // ---------------------------------------------------------------- 0xD042

        void d042() {
            w8(0xcac6, r8(H + 0x72));
            w32(0x17506, 0);
            w8(H + 0x64, 0);
            w8(H + 0x6d, 0);
            d0 = r32(H + 2);
            if (d0 < 0x100000) {
                d0 = 0x100000;
                w32(H + 0x28, 0);
            } else if (d0 > r32(0x108da)) {
                d0 = r32(0x108da);
                w32(H + 0x28, 0);
            }
            w32(H + 2, d0);
            w16(H + 0x20, r16(H + 2));
            w16(H + 0x24, r16(H + 6));
            w16(H + 0x58, r16(H + 0x2c));
            w32(H + 0x3c, 0);
            w32(H + 0x40, 0);
            w16(H + 0x66, 0);
            w8(H + 0x70, 0);
            d6 = sw(d6, r16(0xbda4));
            if ((d6 & 0xffff) == 0x26 || (d6 & 0xffff) == 0x34) {
                sync();
                return;
            }
            w8(0xcac2, 0);
            if (r8(H + 0x74) != 0) {
                d1 = r32(H + 0x28) >> 1; // 0xD604
                w32(H + 0x28, d1);
            }
            d6 = 0;
            d0 = swap(r32(H + 2) + r32(H + 0x28));
            d1 = sw(d1, r16(H + 6));
            boolean any = d9c6();
            boolean horizontal = true;
            if (!any) {
                d1 = r32(H + 0x28);
                horizontal = d1 != 0;
            }
            if (horizontal) {
                d7 = swap(r32(H + 2) + r32(H + 0x28));
                d0 = sw(d0, d7);
                d1 = sw(d1, r16(H + 0x24));
                w8(0xcad4, 0);
                cdf6();
                if ((d0 & 4) != 0 && r16(H + 0x3a) == 0 && r8(0x10aba) == 0) {
                    d688();
                } else {
                    if ((d0 & 1) != 0) {
                        d2 = r32(H + 0x28) + r32(H + 2);
                        int t = d2 - r32(0xcae2);
                        if (t != 0) {
                            if (t < 0) {
                                d7 = sw(d7, (((d7 + s16(H + 0x14) + 0x10) & 0xfff0) - s16(H + 0x14)));
                                bset70(2);
                            } else {
                                d7 = sw(d7, ((d7 + s16(H + 0x14) + s16(H + 0x18) - 0x10) | 0xf)
                                        - s16(H + 0x14) - s16(H + 0x18) + 1);
                                bset70(3);
                            }
                            w32(H + 0x28, 0);
                            w32(H + 0x3c, 0);
                            d7 &= 0xffff;
                        }
                    }
                    d7 = swap(d7);
                    w32(H + 2, d7);
                }
            }
            // 0xD182: vertical
            d0 = r32(H + 6);
            if (r8(H + 0x72) != 0) {
                d0 = r32(H + 0x48);
                if (d0 != 0) {
                    a3 = d0;
                    if (r16(a3) != 0) {
                        d0 = 3 & r16(a3 + 0x82); // moveq #3, d0 / and.w
                        if (d0 != 0) {
                            w8(a3 + 0x62, 0);
                            while (r8(a3 + 0x6b) != 0) {
                                a3 = r32(a3 + 0x48);
                            }
                            d0 = sw(d0, r16(a3 + 0x2c));
                            if ((short) d0 >= 0) {
                                d0 = ((d0 + 2) & 0xffff) << 16;
                                w32(H + 0x2c, d0);
                            }
                        }
                    }
                }
            }
            d2 = s16(0x11120); // movem.w $11120, d2-d3 (sign-extended)
            d3 = s16(0x11122);
            d1 = sw(d1, r16(H + 0x2c));
            w16(0x11120, d1);
            w16(0x11122, d2);
            w16(0x11124, d3);
            d0 = sw(d0, r16(0xcac8));
            w16(H + 0x66, r16(H + 0x66) | (d0 & 0xffff));
            w8(H + 0x72, 0);
            w32(H + 0x48, 0);
            d0 = r32(H + 0x5c);
            d7 = r32(H + 0x2c) + d0;
            if (d7 >= 0) {
                if (d7 > 0xf0000) {
                    d7 = 0xf0000;
                }
            } else if (d7 < 0xfff00001) {
                d7 = 0xfff00001;
            }
            w8(H + 0x74, 0);
            w32(H + 0x2c, d7);
            d7 += r32(H + 6);
            d1 = swap(d7);
            d1 = sw(d1, d1 + r16(H + 0x16));
            if ((short) d1 < 0) {
                d7 = 0;
                w32(H + 0x2c, 0);
            }
            d0 = sw(d0, r16(H + 2));
            d7 = swap(d7);
            d1 = sw(d1, d7);
            w16(0xcad8, 0);
            if (s16(H + 0x2c) >= 0) {
                ccca();
                w16(0xcad8, d0);
                if ((d0 & 0x200) != 0) {
                    w8(H + 0x74, 0xff);
                }
                if ((d3 & 0xffff) != 0) {
                    d7 = sw(d7, d3);
                    if (r16(0xcac2) == 0 && (d0 & 0x100) != 0) {
                        w8(0xcac2, 0xff);
                        d1 = r32(0x1089c);
                        boolean was = (d0 & 0x80) != 0;
                        d0 &= ~0x80;
                        if (was) {
                            d1 = -d1;
                        }
                        w32(H + 0x3c, d1);
                        d6ea();
                    }
                }
            }
            d280();
            sync();
        }

        /** 0xD280..0xD600: the landing. */
        private void d280() {
            d0 = sw(d0, r16(H + 2));
            d1 = sw(d1, d7);
            w8(0xcad4, 0xff);
            w16(0xcad6, d1);
            cdf6();
            if (r16(0xcac2) != 0 || r8(H + 0x64) == 0) {
                d3de();
                return;
            }
            d1 = r16(0xcaca);
            d1 = sw(d1, d1 - 1);
            d7 = sw(d7, d1);
            d0 = r32(0xcacc);
            if (d0 != 0) {
                w32(H + 0x40, d0);
                int saved = r16(0xcac8);
                d7d2();
                w16(0xcac8, saved);
            }
            d0 = sw(d0, r16(H + 2));
            d1 = sw(d1, d7);
            w8(0xcad4, 0);
            int sCad0 = r32(0xcad0), sCac8 = r16(0xcac8), s64 = r16(H + 0x64);
            cdf6();
            w16(H + 0x64, s64);
            w16(0xcac8, sCac8);
            w32(0xcad0, sCad0);
            d0 = sw(d0, d0 & 0x13);
            if ((d0 & 0xffff) != 0) {
                d6 = d7;
                d7 = r32(H + 0x2c) + r32(H + 0x5c) + r32(0xcad0);
                if (d7 >= 0) {
                    d7 = swap(d7 + r32(H + 6));
                    d7 = sw(d7, ((d7 + s16(H + 0x16) + s16(H + 0x1a) - 0x10) | 0xf)
                            - s16(H + 0x16) - s16(H + 0x1a) + 1);
                } else if ((d0 & 2) != 0) {
                    d7 = d6;
                } else {
                    d7 = swap(d7 + r32(H + 6));
                    d7 = sw(d7, ((d7 + s16(H + 0x16) + 0x10) & 0xfff0) - s16(H + 0x16));
                }
            }
            // 0xD354
            d0 = sw(d0, r16(0xcac8));
            a4 = r32(H + 0x48);
            w8(a4 + 0x62, 0xff);
            d0 = sw(d0, r16(0xcac8));
            d1 = sw(d1, d0 & 0x2d7);
            if ((d1 & 0xffff) == 0) {
                d4dc();
                return;
            }
            if ((d0 & 0x40) != 0) {
                w8(H + 0x72, 0);
                d1 = sb(d1, r8(0xcac6));
                if ((d1 & 0xff) != 0) {
                    d4dc();
                    return;
                }
                d1 = r32(H + 0x2c);
                a4 = r32(H + 0x48);
                d1 = -(d1 - r32(a4 + 0x2c));
                if ((r8(0x8269) & 1) != 0) {
                    d1 -= 0x30000;
                } else {
                    if (d1 >= 0xfffc0000) {
                        d4dc();
                        return;
                    }
                    d1 += 0x40000;
                }
                w32(H + 0x2c, d1);
                w8(H + 0x71, 0xff);
                d5d0();
                return;
            }
            if ((d0 & 3) != 0) {
                d4dc();
                return;
            }
            if ((d0 & 0x200) != 0) {
                w8(H + 0x74, 0xff);
                d4dc();
                return;
            }
            d3e8();
        }

        private void d3de() {
            if ((d0 & 0x200) != 0) {
                w8(H + 0x74, 0xff);
            }
            d3e8();
        }

        private void d3e8() {
            if ((d0 & 4) != 0 && r16(H + 0x3a) == 0 && r8(0x10aba) == 0) {
                d6 = 8;
                hurt();
                if (s16(H + 0x2c) < 0) {
                    d48c();
                    return;
                }
                w32(H + 0x2c, 0xfff40000);
                d470();
                return;
            }
            if (s16(H + 0x2c) < 0) {
                d48c();
                return;
            }
            if ((d0 & 2) != 0 && s16(H + 0x2c) >= 0) {
                d1 = sw(d1, (d7 - 1) & 0xf);
                if ((short) d1 <= s16(H + 0x2c)) {
                    d4bc();
                    return;
                }
            }
            if ((d0 & 0x40) == 0) {
                d48c();
                return;
            }
            w8(H + 0x72, 0);
            d1 = -r32(H + 0x2c);
            if ((r8(0x8269) & 1) != 0) {
                d1 -= 0x30000;
            } else {
                if (d0 >= 0xfffc0000) { // cmpi.l #$FFFC0000, d0 in the original (d1 meant)
                    d4bc();
                    return;
                }
                d1 += 0x40000;
            }
            w32(H + 0x2c, d1);
            w8(H + 0x71, 0xff);
            d470();
        }

        private void d470() {
            d7 = sw(d7, ((d7 + s16(H + 0x16) + s16(H + 0x1a) - 0x10) | 0xf) - s16(H + 0x16) - s16(H + 0x1a));
            d5d0();
        }

        private void d48c() {
            if ((d0 & 1) == 0) {
                d4f2();
                return;
            }
            if ((r8(0xcac8) & 4) == 0) {
                boolean was = (d0 & 0x400) != 0;
                d0 &= ~0x400;
                if (was) {
                    d4dc();
                    return;
                }
            }
            if (s16(H + 0x2c) >= 0) {
                d4bc();
                return;
            }
            d7 = sw(d7, ((d7 + s16(H + 0x16) + 0x10) & 0xfff0) - s16(H + 0x16));
            w32(H + 0x2c, 0);
            d4e6();
        }

        private void d4bc() {
            if ((d0 & 8) == 0) {
                d7 = sw(d7, ((d7 + s16(H + 0x16) + s16(H + 0x1a) - 0x10) | 0xf)
                        - s16(H + 0x16) - s16(H + 0x1a) + 1);
            }
            d4dc();
        }

        private void d4dc() {
            w32(H + 0x2c, r32(H + 0x60));
            w8(H + 0x72, 0xff);
            d4e6();
        }

        private void d4e6() {
            d7 = swap(d7) & 0xffff0000;
            w32(H + 6, d7);
            d5d6();
        }

        private void d4f2() {
            if ((r8(0xcad9) & 0x80) == 0) {
                d5d0();
                return;
            }
            d6 = sw(d6, r16(H + 0x2c));
            if ((short) d6 < 0) {
                d5d0();
                return;
            }
            d1 = sw(d1, r16(0x108b6));
            if ((d1 & 0xffff) == 0) {
                d1 = sb(d1, r8(0x10aba));
                if ((d1 & 0xff) != 0) {
                    d5d0();
                    return;
                }
            }
            d1 = sw(d1, (d7 - 1) & 0xf);
            if ((short) d1 > (short) d6) {
                d5d0();
                return;
            }
            if ((r8(0xcad8) & 1) != 0) {
                d5d0();
                return;
            }
            d6 = sw(d6, r16(0xbda4));
            int st = d6 & 0xffff;
            if (st == 0x30) {
                d5d0();
                return;
            }
            if (st == 0x2e || (st == 4 && r16(H + 0x4e) == 6)) {
                d4bc();
                return;
            }
            if (r8(0xcac6) == 0 && s16(H + 0x2c) <= 1) {
                d5d0();
                return;
            }
            // 0xD55C: a fall: state $2E
            w16(H + 0x4c, 0);
            w16(H + 0x5a, 0);
            w8(H + 0x5b, 0xff);
            w8(H + 0x6c, 0);
            a0 = 0xbda6;
            w32(a0, 0);
            w16(a0 + 4, r16(0xbda4));
            boolean sound = true;
            if (r8(H + 0x56) != 0) {
                d1 = 0;
                d2 = sw(d2, r16(H + 0x2c) - 1);
                if ((short) d2 <= 0) {
                    sound = false;
                    if (st == 0xc) {
                        d1 = 0x22;
                    }
                }
            }
            if (sound) {
                d1 = 0x18;
                w16(a0, 0xfc18);
                w8(a0 + 2, 0xff);
                int[] regs = c.saveRegisters();
                c.d[0] = 0;
                c.d[1] = 0;
                c.a[5] = H;
                Calls.call(c, 0x7bc8);
                c.restoreRegisters(regs);
            }
            w16(H + 0x4e, d1);
            d6 = 0x2e;
            w16(a0 - 2, 0x2e);
            a0 = 0xa04c;
            d6 = sw(d6, r16(a0 + 0x2e));
            d6 = sw(d6, d6 + r16(H + 0x4e));
            w16(H + 0x50, r16(a0 + 0x38 + (short) d6));
            w32(H + 0x28, 0);
            d4bc();
        }

        private void d5d0() {
            d7 = swap(d7);
            w32(H + 6, d7);
            d5d6();
        }

        private void d5d6() {
            if (r8(H + 0x72) != 0) {
                bset70(1);
            }
            d0 = sb(d0, r8(H + 0x70));
            d1 = sw(d1, 0xf888);
            if ((d1 & (1 << (d0 & 31))) == 0) {
                return;
            }
            if (r16(H + 0x3a) != 0) {
                return;
            }
            d6 = (byte) ((d0 & 0xff) - 0xc) >= 0 ? 0xe : 0xc;
            hurt();
        }

        // ---------------------------------------------------------------- 0xD688

        private void d688() {
            d2 = r32(H + 0x28);
            d2 = sw(d2, d2 + r16(H + 0x3c));
            if (d2 != 0) {
                if (d2 > 0) {
                    d7 = sw(d7, ((d7 + s16(H + 0x14) + s16(H + 0x18) - 0x10) | 0xf)
                            - s16(H + 0x14) - s16(H + 0x18) + 1);
                    w32(H + 0x28, 0xfffc0000);
                } else {
                    d7 = sw(d7, ((d7 + s16(H + 0x14) + 0x10) & 0xfff0) - s16(H + 0x14));
                    w32(H + 0x28, 0x40000);
                }
                w32(H + 0x3c, 0);
                d7 &= 0xffff;
                d6 = 0;
                hurt();
            }
            d7 = swap(d7);
            w32(H + 2, d7);
        }

        // ---------------------------------------------------------------- 0xD6EA

        private void d6ea() {
            int sd0 = d0, sd7 = d7;
            d7 = swap(r32(H + 2) + d1);
            int saved28 = r32(H + 0x28);
            int pushed7 = d7;
            w32(H + 0x28, r32(H + 0x28) + d1);
            d6 = 0;
            d0 = sw(d0, d7);
            d1 = sw(d1, r16(H + 0x24));
            d9c6();
            d7 = pushed7;
            if ((d6 & 0xffff) != 0) {
                d7 = swap(r32(H + 2) + r32(H + 0x28));
                w32(H + 0x3c, 0);
                d7 &= 0xffff;
            } else {
                w32(H + 0x28, saved28);
                d0 = sw(d0, d7);
                d1 = sw(d1, r16(H + 0x24));
                w8(0xcad4, 0);
                cdf6();
                if ((d0 & 1) != 0) {
                    d2 = r32(H + 0x28) + r32(H + 0x3c);
                    if (d2 != 0) {
                        boolean wallAbove = false;
                        if (s16(H + 0x16) < (short) 0xffe0) {
                            a0 = r32(0x10888);
                            d1 = sw(d1, r16(H + 0x24) + r16(H + 0x16));
                            d1 = sw(d1, asr4(d1));
                            d1 = mulu(d1, r16(0x10a48));
                            a0 += d1;
                            d1 = sw(d1, d7);
                            d1 = sw(d1, asr4(d1));
                            d1 = sw(d1, 2 * d1);
                            d1 = sw(d1, r16(a0 + d1));
                            a0 = r32(0x10890);
                            d1 = sw(d1, r16(a0 + d1));
                            d1 = sw(d1, d1 & 1);
                            if ((d1 & 0xffff) != 0) {
                                w8(0xbda1, 2);
                                w16(0xbda2, 1);
                                a0 = 0xbda4;
                                wallAbove = true;
                            }
                        }
                        if (!wallAbove) {
                            if (d2 >= 0) {
                                d7 = sw(d7, ((d7 + s16(H + 0x14) + s16(H + 0x18) - 0x10) | 0xf)
                                        - s16(H + 0x14) - s16(H + 0x18) + 1);
                            } else {
                                d7 = sw(d7, ((d7 + s16(H + 0x14) + 0x10) & 0xfff0) - s16(H + 0x14));
                            }
                            w32(H + 0x3c, 0);
                            d7 &= 0xffff;
                        }
                    }
                }
            }
            d7 = swap(d7);
            w32(H + 2, d7);
            d0 = sd0;
            d7 = sd7;
        }

        // ---------------------------------------------------------------- 0xD7D2

        private void d7d2() {
            int sd0 = d0, sd7 = d7;
            d1 = r32(H + 0x40);
            d7 = swap(r32(H + 2) + d1);
            d0 = sw(d0, d7);
            d1 = sw(d1, r16(H + 0x24));
            w8(0xcad4, 0);
            int sCad0 = r32(0xcad0);
            cdf6();
            w32(0xcad0, sCad0);
            if ((d0 & 1) != 0) {
                d2 = r32(H + 0x28) + r32(H + 0x40);
                if (d2 != 0) {
                    if (d2 > 0) {
                        d7 = sw(d7, ((d7 + s16(H + 0x14) + s16(H + 0x18) - 0x10) | 0xf)
                                - s16(H + 0x14) - s16(H + 0x18) + 1);
                    } else {
                        d7 = sw(d7, ((d7 + s16(H + 0x14) + 0x10) & 0xfff0) - s16(H + 0x14));
                    }
                    w32(H + 0x40, 0);
                    d7 &= 0xffff;
                }
            }
            d7 = swap(d7);
            w32(H + 2, d7);
            d0 = sd0;
            d7 = sd7;
        }

        // ---------------------------------------------------------------- 0xD9C6

        /** Solid objects against the box at (d0, d1); returns true when +$44 (side) is not 0. */
        boolean d9c6() {
            w32(H + 0x44, 0);
            d0 = sw(d0, d0 + r16(H + 0x14));
            d2 = sw(d2, d0 + r16(H + 0x18));
            d1 = sw(d1, d1 + r16(H + 0x16));
            d3 = sw(d3, d1 + r16(H + 0x1a));
            a2 = 0x1767a;
            d7 = sw(d7, r16(a2));
            a2 += 2;
            boolean body = false;
            if ((d7 & 0xffff) != 0 && (d6 & 0xffff) == 0) {
                d7 = sw(d7, d7 - 1);
                d6 = sw(d6, d1);
                d6 = sw(d6, asr4(d6));
                a3 = s16(0x10a48);
                d4 = sw(d4, a3);
                d6 = mulu(d6, d4);
                d4 = sw(d4, d0);
                a4 = r32(H + 0x28) + r32(H + 0x40) + r32(H + 0x3c);
                d5 = a4;
                boolean clear = true;
                if (a4 != 0) {
                    if (a4 > 0) {
                        d4 = sw(d4, d2);
                    }
                    d4 = sw(d4, asr4(d4));
                    d4 = sw(d4, 2 * d4);
                    d6 = sw(d6, d6 + d4);
                    a0 = r32(0x10888) + d6;
                    a1 = r32(0x10890);
                    d4 = sw(d4, d3 - 1 - d1);
                    d4 = sw(d4, asr4(d4));
                    d5 = 0;
                    do {
                        d6 = sw(d6, r16(a0));
                        d5 = sw(d5, d5 | r16(a1 + d6));
                        a0 += (short) a3;
                        d4 = sw(d4, d4 - 1);
                    } while ((d4 & 0xffff) != 0xffff);
                    d6 = 0;
                    a3 = a4;
                    d5 = sw(d5, d5 & 1);
                    if ((d5 & 0xffff) == 0) {
                        clear = false;
                    } else if ((d4 = a4) < 0) {
                        d4 = sw(d4, (-d0) & 0xf);
                        d0 = sw(d0, d0 + d4);
                        d2 = sw(d2, d2 + d4);
                    } else {
                        d4 = sw(d4, d2 & 0xf);
                        d0 = sw(d0, d0 - d4);
                        d2 = sw(d2, d2 - d4);
                    }
                }
                if (clear) {
                    d6 = 0;
                    a3 = 0;
                }
                body = true;
            }
            // loop over the solid objects (d7 + 1 times when entered at the body)
            if (!body) {
                d7 = sw(d7, d7 - 1);
                if ((d7 & 0xffff) == 0xffff) {
                    d6 = sw(d6, r16(H + 0x44));
                    return (d6 & 0xffff) != 0;
                }
            }
            while (true) {
                if (object()) {
                    break; // 0xDBF2: left and right both blocked
                }
                d7 = sw(d7, d7 - 1);
                if ((d7 & 0xffff) == 0xffff) {
                    break;
                }
            }
            d6 = sw(d6, r16(H + 0x44));
            return (d6 & 0xffff) != 0;
        }

        /** 0xDA64: one solid object; true = leave the loop. */
        private boolean object() {
            a4 = r32(a2);
            a2 += 4;
            d5 = sw(d5, r16(a4 + 2) + r16(a4 + 0x14));
            if ((short) d2 < (short) d5) {
                return false;
            }
            a0 = (short) d5;
            d5 = sw(d5, d5 + r16(a4 + 0x18));
            if ((short) d0 > (short) d5) {
                return false;
            }
            d5 = sw(d5, r16(a4 + 6) + r16(a4 + 0x16));
            if ((short) d3 <= (short) d5) {
                return false;
            }
            d4 = sw(d4, d5);
            d5 = sw(d5, d5 + r16(a4 + 0x1a));
            if ((short) d1 >= (short) d5) {
                return false;
            }
            // 0xDA9C: touching
            if ((d6 & 0xffff) == 0) {
                return side();
            }
            if ((d2 & 0xffff) == (a0 & 0xffff)) {
                return false;
            }
            a1 = (short) a0 + s16(a4 + 0x18);
            if ((d0 & 0xffff) == (a1 & 0xffff)) {
                return false;
            }
            a1 = s16(H + 6) + s16(H + 0x16);
            if ((short) d4 > (short) a1) {
                d5 = sw(d5, d4);
                d4 = sw(d4, d3 - d5 - 1);
                a6 = a4;
                while (r8(a6 + 0x6b) != 0) {
                    a6 = r32(a6 + 0x48);
                }
                d4 = sw(d4, d4 + r16(a6 + 0x2c));
                if ((short) d4 > s16(H + 0x2c)) {
                    return false;
                }
                w32(H + 0x48, a4);
                d4 = sw(d4, r16(a4 + 6) + r16(a4 + 0x16) + 1);
                w16(0xcaca, d4);
                w8(H + 0x64, 0xff);
                w32(0xcad0, r32(a4 + 0x2c));
                w32(0xcacc, r32(a4 + 0x28));
                d5 = sw(d5, d5 + r16(H + 0x16));
                w8(a4 + 0x62, 0xff);
                bset70(1);
                w16(H + 0x44, 1);
            } else {
                // 0xDB1A
                d4 = sw(d4, d1 - d5 + 1 + r16(a4 + 0x2c));
                if ((short) d4 < s16(H + 0x2c)) {
                    return false;
                }
                bset70(0);
                if ((r8(0xcac9) & 1) != 0) {
                    return false;
                }
                w32(H + 0x2c, 0);
                if ((r8(H + 0x70) & 2) != 0) {
                    return false;
                }
                w16(H + 0x44, 0xffff);
            }
            // 0xDB50
            d5 = sw(d5, d5 + r16(H + 0x1a));
            w16(H + 0x46, d5);
            if (s16(H + 0x44) >= 0) {
                return false;
            }
            d5 = (d5 << 16) - r32(H + 6);
            if (d5 >= 0) {
                dc1c();
            }
            w32(H + 0x2c, d5);
            if (d5 == 0) {
                w32(H + 0x44, 0);
            }
            return false;
        }

        /** 0xDB7E: pushed from the side by a moving object. */
        private boolean side() {
            d4 = a3 - r32(a4 + 0x28);
            boolean right;
            if (d4 < 0) {
                right = false;
            } else if (d4 == 0) {
                d5 = sw(d5, r16(H + 2));
                right = !((short) d5 > s16(a4 + 2));
            } else {
                right = true;
            }
            if (right) {
                d4 = swap(d4);
                d4 = sw(d4, d4 + 1 - d2);
                int r = (short) d4 + (short) a0;
                d4 = sw(d4, r);
                if (r < 0) {
                    return false;
                }
                bset70(3);
                d5 = sw(d5, r16(a4 + 2) + r16(a4 + 0x14) - r16(H + 0x18) - r16(H + 0x14));
                w16(H + 0x44, 0xffff);
            } else {
                d4 = swap(d4);
                d4 = sw(d4, d4 - 1 - d0 + a0);
                int r = (short) d4 + s16(a4 + 0x18);
                d4 = sw(d4, r);
                if (r > 0) {
                    return false;
                }
                bset70(2);
                d5 = sw(d5, r16(a4 + 2) + r16(a4 + 0x14) + r16(a4 + 0x18) - r16(H + 0x14));
                w16(H + 0x44, 1);
            }
            // 0xDBE8
            d4 = 0xc & r8(H + 0x70);
            if (d4 == 0xc) {
                return true;
            }
            d5 = (d5 << 16) - r32(H + 2);
            w32(H + 0x28, d5);
            d5 = sw(d5, r16(a4 + 0x82) & 0xf801);
            w16(H + 0x66, r16(H + 0x66) | (d5 & 0xffff));
            w32(0x17506, a4);
            w8(H + 0x57, 0);
            return false;
        }

        // ---------------------------------------------------------------- 0xDC1C

        /** Landing on an object: d5 (fall) shortened when the map stops Bubba first. */
        private void dc1c() {
            int saved = d5;
            d6 = swap(r32(H + 6) + d5);
            int y = d6 & 0xffff;
            d6 = sw(d6, asr4(d6));
            d6 = mulu(d6, r16(0x10a48));
            d4 = sw(d4, r16(H + 2) + r16(H + 0x14));
            int x = d4 & 0xffff;
            d4 = sw(d4, asr4(d4));
            d6 = sw(d6, d6 + d4);
            d6 = sw(d6, d6 + d4);
            d5 = 1;
            d4 = sw(d4, x & 0xf);
            if ((d4 & 0xffff) != 0) {
                d5 = 2;
            }
            a0 = d6 + r32(0x10888);
            a1 = r32(0x10890);
            boolean hit;
            while (true) {
                d6 = sw(d6, r16(a0));
                a0 += 2;
                d4 = 0x13 & r16(a1 + d6);
                if (d4 != 0) {
                    hit = true;
                    break;
                }
                d5 = sw(d5, d5 - 1);
                if ((d5 & 0xffff) == 0xffff) {
                    hit = false;
                    break;
                }
            }
            d6 = sb(d6, hit ? 0xff : 0);
            d4 = sw(d4, y);
            if (hit) {
                a0 -= s16(0x10a48);
                a0 -= 2;
                d6 = sw(d6, r16(a0));
                d5 = 0x13 & r16(a1 + d6);
                if (d5 != 0) {
                    saved = sw(saved >>> 16, (saved >>> 16) - 0x10) << 16 | (saved & 0xffff);
                }
                d4 = sw(d4, d4 & 0xf);
                d4 = (d4 & 0xffff) << 16;
                saved -= d4;
                if (saved < 0) {
                    saved = 0;
                }
            }
            d5 = saved;
            d6 = 1;
        }

        // ---------------------------------------------------------------- 0xCCCA

        /** Ground under the feet (x centre, y bottom): d0 = attributes, d3 = new y or 0. */
        private void ccca() {
            int s6 = d6, s7 = d7;
            w16(0xcac4, d1);
            d0 = sw(d0, d0 + r16(H + 0x14));
            d5 = sw(d5, r16(H + 0x18));
            d5 = sw(d5, ((short) d5 >> 1));
            d0 = sw(d0, d0 + d5);
            d1 = sw(d1, d1 + r16(H + 0x16));
            d1 = sw(d1, d1 + r16(H + 0x1a));
            a0 = r32(0x10888);
            d5 = sw(d5, d0);
            d0 = sw(d0, asr4(d0));
            d6 = sw(d6, d1);
            d1 = sw(d1, asr4(d1));
            d1 = mulu(d1, r16(0x10a48));
            d1 = sw(d1, d1 + d0);
            d1 = sw(d1, d1 + d0);
            a0 += d1;
            d3 = 0;
            d2 = 0;
            d2 = sw(d2, r16(a0));
            a1 = r32(0x10890);
            d1 = sw(d1, r16(a1 + d2));
            int path;
            if ((d1 & 0x10) == 0) {
                path = (d1 & 8) == 0 ? 2 : 1; // 0xCD66: 2 = 0xCD9C, 1 = 0xCD6C
            } else {
                boolean high = false;
                if ((d1 & 2) != 0 && s16(H + 0x2c) >= 0) {
                    int r = (short) (r16(0xcac4) - d3) - 8;
                    d0 = sw(d0, r);
                    high = r > 0;
                }
                d4 = sw(d4, d1);
                if (high) {
                    d6 = sw(d6, d6 | 0xf);
                } else {
                    d6 = sw(d6, (d6 - 0x10) | 0xf);
                }
                d3 = sw(d3, d6);
                a0 -= s16(0x10a48);
                d2 = sw(d2, r16(a0));
                d1 = sw(d1, r16(a1 + d2));
                if ((d1 & 8) != 0) {
                    path = 1;
                } else {
                    if (high) {
                        d3 = sw(d3, 0);
                    } else {
                        w8(H + 0x72, 0xff);
                    }
                    path = 3; // 0xCD90
                }
            }
            if (path == 1) { // 0xCD6C: slope
                d3 = sw(d3, d6 & 0xfff0);
                d5 = sw(d5, d5 & 0xf);
                d2 = sw(d2, d2 + d5);
                d5 = sb(d5, r8(a1 + d2 + 0x42));
                d5 = sw(d5, (byte) d5);
                d3 = sw(d3, d3 + d5);
                if ((short) d3 > (short) d6) {
                    d3 = sw(d3, d6);
                    d0 = sw(d0, d1);
                    d3 = sw(d3, 0);
                    d6 = s6;
                    d7 = s7;
                    return;
                }
                // 0xCDB2
                if ((d1 & 2) != 0 && s16(H + 0x2c) >= 0) {
                    int r = (short) (r16(0xcac4) - d3) - 8;
                    d0 = sw(d0, r);
                    if (r > 0) {
                        d3 = sw(d3, 0);
                        d0 = sw(d0, d1);
                        d6 = s6;
                        d7 = s7;
                        return;
                    }
                }
                w8(H + 0x72, 0xff);
                d2 = r32(H + 0x60) * 2;
                w32(H + 0x2c, d2);
                d0 = sw(d0, d1);
                if ((d3 & 0xffff) != 0) {
                    d3 = sw(d3, d3 - r16(H + 0x16) - r16(H + 0x1a) + 1);
                }
                d6 = s6;
                d7 = s7;
                return;
            }
            if (path == 3) { // 0xCD90
                w8(H + 0x72, 0xff);
                w32(H + 0x2c, r32(H + 0x60));
                d1 = sw(d1, d4);
            }
            // 0xCD9C
            d0 = sw(d0, d1);
            if ((d3 & 0xffff) != 0) {
                d3 = sw(d3, d3 - r16(H + 0x16) - r16(H + 0x1a) + 1);
            }
            d6 = s6;
            d7 = s7;
        }

        // ---------------------------------------------------------------- 0xCDF6

        /** Blocks under the box at (d0, d1) or'ed into $CAC8 (and d0); carriers when $CAD4. */
        void cdf6() {
            w16(0xcac8, 0);
            w16(0xcaca, 0x7fff);
            int s1 = d1, s6 = d6, s7 = d7;
            d0 = sw(d0, d0 + r16(H + 0x14));
            d1 = sw(d1, d1 + r16(H + 0x16));
            d2 = sw(d2, d0);
            d3 = sw(d3, d1);
            d2 = sw(d2, d2 + r16(H + 0x18));
            d3 = sw(d3, d3 + r16(H + 0x1a));
            a0 = r32(0x10888);
            a1 = r32(0x10890);
            d5 = sw(d5, d0 & 0xfff0);
            d5 = sw(d5, (short) d5 >> 3);
            d6 = sw(d6, d1);
            d6 = sw(d6, asr4(d6));
            d6 = mulu(d6, r16(0x10a48));
            d6 = sw(d6, d6 + d5);
            a0 += d6;
            a4 = a0;
            d6 = sw(d6, d2 & 0xf);
            if ((d6 & 0xffff) != 0) {
                d2 = sw(d2, (d2 + 0x10) & 0xfff0);
            }
            d0 = sw(d0, d0 & 0xfff0);
            d2 = sw(d2, d2 - d0);
            d2 = sw(d2, asr4(d2));
            d7 = sw(d7, d3 & 0xf);
            if ((d7 & 0xffff) != 0) {
                d3 = sw(d3, (d3 + 0x10) & 0xfff0);
            }
            d1 = sw(d1, d1 & 0xfff0);
            d3 = sw(d3, d3 - d1);
            d3 = sw(d3, asr4(d3));
            d7 = sw(d7, d2);
            d6 = sw(d6, d3);
            d0 = 0;
            a4 = a0;
            d4 = 0;
            d6 = sw(d6, d6 - 1);
            if ((short) d6 >= 0) {
                while (true) {
                    d6 = sw(d6, d6 - 1); // 0xCE8E
                    if ((d6 & 0xffff) == 0xffff) {
                        break;
                    }
                    d5 = sw(d5, d7);
                    while (true) {
                        d5 = sw(d5, d5 - 1);
                        if ((d5 & 0xffff) == 0xffff) {
                            break;
                        }
                        d4 = sw(d4, r16(a0));
                        a0 += 2;
                        d0 = sw(d0, d0 | r16(a1 + d4));
                    }
                    a4 += s16(0x10a48);
                    a0 = a4;
                }
                d0 = sw(d0, d0 & 0xff55);
                d6 = 0xa;
                lastRow(false);
            } else {
                lastRow(true);
            }
            w16(0xcac8, d0);
            w32(0xcacc, 0);
            w32(0xcad0, 0);
            if (r8(0xcad4) == 0) {
                end(s1, s6, s7);
                return;
            }
            d0 = sw(d0, r16(H + 2));
            d1 = sw(d1, r16(0xcad6));
            d6 = 1;
            d9c6();
            d0 = sw(d0, r16(0xcac8));
            int side = s16(H + 0x44);
            if (side != 0) {
                if (side > 0) {
                    a4 = r32(H + 0x48);
                    d1 = sw(d1, r16(a4 + 0x82) & 0x240);
                    d0 = sw(d0, d0 | d1);
                }
                d1 = s1;
                d6 = s6;
                d7 = r16(H + 0x46);
                w16(0xcac8, d0);
                return;
            }
            if (s16(H + 0x2c) < 0) {
                end(s1, s6, s7);
                return;
            }
            w8(0xcad4, 0);
            d0 = sw(d0, r16(H + 2));
            d1 = sw(d1, r16(0xcad6));
            a4 = H + 0x78; // Stix planted
            if ((r8(a4 + 0x83) & 2) != 0) {
                d2 = s16(a4 + 0xa) >= 8 ? -10 : -22;
                d2 = sw(d2, d2 + r16(a4 + 2));
                int r = (short) d0 - (short) d2;
                d0 = sw(d0, r);
                if (r >= 0 && (short) d0 <= 0x20) {
                    int q = (short) d1 - s16(a4 + 6);
                    d1 = sw(d1, q);
                    if (q >= 0 && (short) d1 - s16(H + 0x2c) <= 0) {
                        w8(H + 0x64, 0xff);
                        w8(H + 0x6d, 0xff);
                        w32(H + 0x48, a4);
                        d0 = sw(d0, r16(a4 + 6) + 1);
                        w16(0xcaca, d0);
                        w32(0xcacc, r32(a4 + 0x28));
                        w32(0xcad0, r32(a4 + 0x2c));
                        end(s1, s6, s7);
                        return;
                    }
                }
            }
            // 0xCF7A: the objects that carry (+$83 bit 1), active then big ones
            d0 = sw(d0, r16(H + 2) + r16(H + 0x14));
            d2 = sw(d2, d0 + r16(H + 0x18));
            d1 = sw(d1, r16(0xcad6) + r16(H + 0x16) + r16(H + 0x1a));
            d3 = sw(d3, d1 + 8);
            int sa3 = a3, sa6 = a6;
            a3 = 0;
            a6 = s16(0xcaca);
            d6 = 1;
            a2 = 0x17514;
            while (true) {
                d7 = sw(d7, r16(a2));
                a2 += 2;
                while (true) {
                    d7 = sw(d7, d7 - 1);
                    if ((d7 & 0xffff) == 0xffff) {
                        break;
                    }
                    a4 = r32(a2);
                    a2 += 4;
                    if ((r8(a4 + 0x83) & 2) == 0) {
                        continue;
                    }
                    d5 = sw(d5, r16(a4 + 6) + r16(a4 + 0x16));
                    if ((short) d5 > (short) a6) {
                        continue;
                    }
                    d4 = sw(d4, r16(a4 + 2) + r16(a4 + 0x14));
                    if ((short) d2 <= (short) d4) {
                        continue;
                    }
                    d4 = sw(d4, d4 + r16(a4 + 0x18));
                    if ((short) d0 >= (short) d4) {
                        continue;
                    }
                    d4 = sw(d4, d1 - d5);
                    if ((short) d4 < 0) {
                        continue;
                    }
                    d4 = sw(d4, d4 + r16(a4 + 0x2c));
                    if ((short) d4 > s16(H + 0x2c)) {
                        continue;
                    }
                    d5 = sw(d5, d5 - r16(H + 0x16) - r16(H + 0x1a) + 1);
                    a6 = (short) d5;
                    a3 = a4;
                }
                a2 = 0x17596;
                d6 = sw(d6, d6 - 1);
                if ((d6 & 0xffff) == 0xffff) {
                    break;
                }
            }
            w32(H + 0x48, a3);
            if (a3 != 0) {
                d0 = sw(d0, r16(a3 + 0x82) & 0x240);
                w16(0xcac8, r16(0xcac8) | (d0 & 0xffff));
                w16(0xcaca, a6);
                w8(H + 0x64, 0xff);
                w32(0xcad0, r32(a3 + 0x2c));
                w32(0xcacc, r32(a3 + 0x28));
            }
            a6 = sa6;
            a3 = sa3;
            end(s1, s6, s7);
        }

        /** 0xCE9A loop: the last row, slopes kept only when fully set (mask d6). */
        private void lastRow(boolean enterBody) {
            if (!enterBody) {
                d7 = sw(d7, d7 - 1);
                if ((d7 & 0xffff) == 0xffff) {
                    return;
                }
            }
            while (true) {
                d4 = sw(d4, r16(a0));
                a0 += 2;
                d1 = sw(d1, r16(a1 + d4));
                d2 = sw(d2, d1);
                d1 = sw(d1, d1 & d6);
                if ((d1 & 0xffff) == (d6 & 0xffff)) {
                    d2 = sw(d2, d2 - d6);
                }
                d0 = sw(d0, d0 | d2);
                d7 = sw(d7, d7 - 1);
                if ((d7 & 0xffff) == 0xffff) {
                    return;
                }
            }
        }

        /** 0xD038. */
        private void end(int s1, int s6, int s7) {
            d1 = s1;
            d6 = s6;
            d7 = s7;
            d0 = sw(d0, r16(0xcac8));
        }
    }
}
