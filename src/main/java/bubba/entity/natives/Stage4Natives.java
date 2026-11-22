package bubba.entity.natives;

import bubba.cpu.GameMemory;
import bubba.entity.LevelObject;

import java.util.Map;

import static bubba.entity.natives.NativeContext.SET;

/**
 * Native routines of stage 4 (set4.bin), ported from the 68k code. Addresses are offsets in
 * set4.bin (loaded at $108EE); see Stage1Natives for the conventions.
 * Stage 4 is the water level: $10A50 = water surface y, $10A5E = second level line (long, y.w:frac).
 */
final class Stage4Natives {

    /** Water surface y (word). */
    private static final int WATER = 0x10a50;
    /** Second reference line (long: y word + fraction). */
    private static final int LINE = 0x10a5e;
    private static final int HERO_X = 0x10912, HERO_Y = 0x10916;

    private Stage4Natives() {
    }

    static void register(Map<Integer, NativeRoutine> r) {
        r.put(0x2060, Stage4Natives::aboveWater44);
        r.put(0x2eca, Stage4Natives::leaveWater);
        r.put(0x18b4, Stage4Natives::aboveLine);
        r.put(0x18cc, Stage4Natives::belowLine);
        r.put(0x1b0a, Stage4Natives::walkerWatch);
        r.put(0x1b3a, Stage4Natives::walkerStep);
        r.put(0x1f2c, Stage4Natives::everyFourth);
        r.put(0x1f8a, Stage4Natives::sink45);
        r.put(0x213a, Stage4Natives::fish);
        r.put(0x224e, Stage4Natives::swimmerSurface);
        r.put(0x24c8, Stage4Natives::bob);
        r.put(0x2816, Stage4Natives::surfaceAnim);
        r.put(0x2d04, (c, o, a4) -> (c.ram.read16(0x10896) & 0xffff) < 0x2000 ? a4 : SET + 0x2d1c);
        r.put(0x2e18, (c, o, a4) -> {
            o.setLong(0x44, o.getLong(0x10));
            o.setLong(0x10, 0);
            return a4;
        });
        r.put(0x2e24, (c, o, a4) -> {
            o.setLong(0x10, o.getLong(0x44));
            return a4;
        });
        r.put(0x2e38, Stage4Natives::swimOrSink);
        r.put(0x3252, (c, o, a4) -> {
            heroClose(o);
            return a4;
        });
        r.put(0x32de, (c, o, a4) -> {
            o.setWord(0x46, o.word(0x06));
            return a4;
        });
        r.put(0x32e6, Stage4Natives::floater);
        r.put(0x3398, (c, o, a4) -> {
            o.setByte(0x44, (short) (o.y() - 4) < (short) c.ram.read16(WATER) ? 0xff : 0);
            return a4;
        });
        r.put(0x33b4, Stage4Natives::floaterRestore);
        r.put(0x340a, (c, o, a4) -> {
            o.setLong(0x06, c.ram.read32(LINE) + 0xc0000);
            o.setLong(0x2c, 0);
            return a4;
        });
        r.put(0x3df8, (c, o, a4) -> {
            o.setWord(0x44, c.random());
            return a4;
        });
        r.put(0x3e12, Stage4Natives::clearLists63);
        r.put(0x3e32, Stage4Natives::protection);
        r.put(0x3e6c, Stage4Natives::platform);
    }

    private static short water(NativeContext c) {
        return (short) c.ram.read16(WATER);
    }

    /** $18B4: if y-8 <= line ($10A5E): +$82 = 0, a4 = $189E; else keep a4. */
    static int aboveLine(NativeContext c, LevelObject o, int a4) {
        if ((short) (o.y() - 8) > (short) c.ram.read16(LINE)) {
            return a4;
        }
        o.setWord(0x82, 0);
        return SET + 0x189e;
    }

    /** $18CC: the reverse of $18B4: if y-8 > line: +$82 = 8, a4 = $1882. */
    static int belowLine(NativeContext c, LevelObject o, int a4) {
        if ((short) (o.y() - 8) <= (short) c.ram.read16(LINE)) {
            return a4;
        }
        o.setWord(0x82, 8);
        return SET + 0x1882;
    }

    /** $1B0A (walker idle, same logic as stage 1 $1910): Bubba close -> face him and walk ($1A9E). */
    static int walkerWatch(NativeContext c, LevelObject o, int a4) {
        int dy = (short) o.word(0x88), dx = (short) o.word(0x86);
        if (dy <= -124 || dy >= 80 || dx <= -160 || dx >= 160) {
            return SET + 0x1a92;
        }
        o.setByte(0x30, dx < 0 ? 0xff : 0);
        return SET + 0x1a9e;
    }

    /**
     * $1B3A (walker walking, same logic as stage 1 $1940): back to idle when Bubba is far, turn
     * around on a wall hit / Bubba close behind / no ground ahead / wall ahead. The map is read
     * exactly as the 68k does (direct table reads, register d1 keeping the high word of mulu).
     */
    static int walkerStep(NativeContext c, LevelObject o, int a4) {
        if (o.getByte(0x64) == 0 || o.getByte(0x46) != 0) {
            return walkExit(o);
        }
        int dy = (short) o.word(0x88), dx = (short) o.word(0x86);
        if (dy <= -164 || dy >= 120 || dx <= -256 || dx >= 256) {
            o.setLong(0x28, 0);
            o.setLong(0x2c, 0);
            setAnim(o, SET + 0x1a36);
            return SET + 0x1a92;
        }
        if (o.getByte(0x9e) != 0) { // $1B86: wall hit, turn around
            o.setByte(0x30, ~o.getByte(0x30));
            return walkExit(o);
        }
        // $1B94: Bubba close behind?
        int face = (byte) o.getByte(0x30);
        if ((((short) face ^ dx) & 0x8000) != 0 && Math.abs(dx) <= 0x58 && dy < 0x28) {
            return turn(o);
        }
        // $1BB4: only test in the half of the block facing the walking direction
        int xl = o.x() & 15;
        if (face < 0 ? xl - 8 >= 0 : xl - 8 < 0) {
            return walkExit(o);
        }
        GameMemory m = c.ram;
        int d0 = (short) (face << 2);
        int row = m.read16(0x10a48);
        int d1 = (((short) (o.y() - 1)) >> 4) & 0xffff;
        d1 = d1 * row; // mulu.w
        int d2 = ((((short) o.x()) >> 4) * 2) & 0xffff;
        d1 = (d1 & 0xffff0000) | ((d1 + d2) & 0xffff); // add.w
        int a0 = m.read32(0x10888) + d1;
        int a1 = m.read32(0x10890);
        int a2 = a0;
        a0 += (short) row;
        d1 = (d1 & 0xffff0000) | m.read16(a0);
        if ((m.read8(a1 + d1 + 1) & 4) != 0) { // $1C3A: special block below
            return face < 0 ? SET + 0x1ace : SET + 0x1aec;
        }
        d1 = (d1 & 0xffff0000) | m.read16(a0 + 2 + d0);
        if ((m.read16(a1 + d1) & 0x13) == 0) {
            return turn(o); // no ground ahead
        }
        for (int k = 0; k < 4; k++) { // dbne loop
            d1 = (d1 & 0xffff0000) | m.read16(a2);
            a2 += 2 + d0;
            if ((m.read16(a1 + d1) & 0x11) != 0) {
                return turn(o); // wall ahead
            }
        }
        return walkExit(o);
    }

    private static int turn(LevelObject o) { // $1C1E
        o.setByte(0x46, 0xff);
        setAnim(o, SET + 0x1a46);
        return walkExit(o);
    }

    private static int walkExit(LevelObject o) { // $1C2A
        return (byte) o.getByte(0x30) < 0 ? SET + 0x1ab6 : SET + 0x1ac2;
    }

    private static void setAnim(LevelObject o, int script) { // $1B78
        o.setLong(0x0c, script);
        o.setWord(0x34, 0);
        o.setWord(0x32, 0);
    }

    /** $1F2C: shared counter $125F6 = (+1) & 3; when it reaches 2: +$50 = a4, a4 = $1F46. */
    static int everyFourth(NativeContext c, LevelObject o, int a4) {
        int a = 0x125f6;
        int v = (c.ram.read8(a) + 1) & 3;
        c.ram.write8(a, v);
        if (v != 2) {
            return a4;
        }
        o.setLong(0x50, a4);
        return SET + 0x1f46;
    }

    /**
     * $1F8A: byte counter +$45 -= vy.w (low byte); clamped to [0, +$47], the overflow moves y;
     * vy cleared; sprite +$0A = ($FF - ((+$47 - +$45 - 1) >> 4)) * 2 when +$45 < +$47.
     */
    static int sink45(NativeContext c, LevelObject o, int a4) {
        int v45 = (o.getByte(0x45) - o.word(0x2c)) & 0xff;
        o.setByte(0x45, v45);
        int d0 = v45;
        boolean move = true;
        if ((byte) d0 < 0) {
            o.setByte(0x45, 0); // $1FA6
        } else {
            int v47 = o.getByte(0x47);
            d0 = (v45 - v47) & 0xff;
            if ((byte) v45 <= (byte) v47) {
                move = false; // ble $1FB4
            } else {
                o.setByte(0x45, v47);
            }
        }
        if (move) { // $1FAA
            o.setWord(0x06, o.word(0x06) + (byte) d0);
            o.setLong(0x2c, 0);
        }
        // $1FB4
        int v47 = o.getByte(0x47), w45 = o.getByte(0x45);
        if ((byte) v47 <= (byte) w45) {
            return a4;
        }
        int d = (((byte) (v47 - w45)) - 1) & 0xffff;
        d >>>= 4;
        o.setWord(0x0a, ((0xff - d) * 2) & 0xffff);
        return a4;
    }

    /** $3252: +$44 = $FF when Bubba is within (-56, 40) on both axes, else 0; returns that test. */
    private static boolean heroClose(LevelObject o) {
        int dx = (short) o.word(0x86), dy = (short) o.word(0x88);
        boolean close = dx > -56 && dx < 40 && dy > -56 && dy < 40;
        o.setByte(0x44, close ? 0xff : 0);
        return close;
    }

    /**
     * $213A (type 76, fish): gravity relative to the water ($10A50) and Bubba's y: out of the
     * water vy += 1.0 and a4 = $2036; under water vy += 0.25 (or -0.25 when below Bubba-16);
     * then when Bubba is close sometimes (RNG < $71C) jump (a4 = $2058), else face / swim
     * toward Bubba (a4 = $2012/$2016/$2026/$202A, +$46 = x - Bubba x).
     */
    static int fish(NativeContext c, LevelObject o, int a4) {
        boolean inAir = true;
        int d1 = 0x10000;
        int d0 = (short) (o.y() - 3);
        if (d0 >= water(c)) {
            inAir = false;
            d1 = 0x4000;
            int d2 = (short) (c.ram.read16(HERO_Y) - 0x10);
            if (d0 >= d2) {
                d1 = -d1;
            }
        }
        o.setLong(0x2c, o.getLong(0x2c) + d1); // $2164
        if (inAir) {
            return SET + 0x2036;
        }
        if (o.getByte(0x45) == 0 && heroClose(o)) {
            int r = c.random() & 0xffff;
            if (r < 0x71c) {
                return SET + 0x2058;
            }
        }
        // $2198
        int f = o.word(0x46);
        int dx = (o.word(0x02) - c.ram.read16(HERO_X)) & 0xffff;
        int na4;
        if ((dx & 0x8000) == 0) {
            na4 = SET + 0x2016;
            if (((f ^ dx) & 0x8000) != 0) {
                if (o.getByte(0x45) != 0) {
                    return na4;
                }
                na4 = SET + 0x2012;
            }
        } else {
            na4 = SET + 0x202a;
            if (((f ^ dx) & 0x8000) != 0) {
                if (o.getByte(0x45) != 0) {
                    return na4;
                }
                na4 = SET + 0x2026;
            }
        }
        o.setWord(0x46, dx); // $21CC
        return na4;
    }

    /**
     * $224E: swimmer near the surface: chooses its script from its depth below the water
     * ($10A50) and Bubba's side (if +$62), sets vx = +-1.0 toward Bubba when vx is 0, and writes
     * the direction (-1/0/1) to $10A67.
     */
    static int swimmerSurface(NativeContext c, LevelObject o, int a4) {
        int d3 = 0;
        int d1 = 0;
        if (o.getByte(0x62) != 0) {
            d1 = (short) (o.word(0x02) - c.ram.read16(HERO_X));
            if ((short) Math.abs(d1) <= 0x0c) { // neg.w: -32768 stays negative, so <= 12 too
                d1 = 0;
            }
        }
        int d0 = (short) (water(c) - o.y() + 8);
        boolean setVx;
        if (d0 < 0) { // $22CA
            a4 = SET + 0x233a;
            if (d1 == 0) {
                return store(c, o, d3, a4);
            }
            d3 = -1;
            a4 = SET + 0x236e;
            if (d1 > 0) {
                d3 = 1;
                a4 = SET + 0x2354;
            }
            setVx = true;
        } else {
            a4 = SET + 0x22e4;
            if (d0 > 0x0c) {
                return store(c, o, d3, a4);
            }
            if (o.getByte(0x64) != 0) { // $22C4
                o.setLong(0x28, 0);
                c.ram.write8(0x10a67, 0);
                return a4;
            }
            if (d1 == 0) {
                return store(c, o, d3, a4);
            }
            d3 = -1;
            a4 = SET + 0x2320;
            if (d1 > 0) {
                d3 = 1;
                a4 = SET + 0x2306;
            }
            setVx = true;
        }
        if (setVx && o.getLong(0x28) == 0 && d1 != 0) { // $229C
            o.setLong(0x28, d1 > 0 ? 0x10000 : -0x10000);
        }
        return store(c, o, d3, a4);
    }

    private static int store(NativeContext c, LevelObject o, int d3, int a4) { // $22B4
        if (o.getByte(0x62) == 0) {
            d3 = 0;
        }
        c.ram.write8(0x10a67, d3);
        return a4;
    }

    /**
     * $24C8: floating object bobbing on the water: +$9D = 100, +$45 = (depth < 16); buoyancy on
     * vy ($2C) toward the surface, damped when it crosses the surface fast; $10ADA keeps vy.
     */
    static int bob(NativeContext c, LevelObject o, int a4) {
        o.setByte(0x9d, 0x64);
        int d0 = (short) (water(c) - o.y());
        o.setByte(0x45, d0 < 16 ? 0xff : 0);
        d0 = (short) (d0 + 0x20);
        boolean set46 = true;
        if (d0 < 0) {
            if ((short) o.word(0x2c) > -10) {
                int a0 = 0x10ada;
                if (o.getLong(0x2c) == 0 && o.getByte(0x46) == 0 && (short) c.ram.read16(a0) < 0) {
                    o.setLong(0x2c, 0x20000);
                }
                int v = o.getLong(0x2c) - 0x4000; // $2508
                o.setLong(0x2c, v);
                o.setByte(0x46, v == 0 ? 0xff : 0);
                c.ram.write32(a0, v);
                set46 = false;
            }
        } else {
            if ((short) o.word(0x2c) < 0x0c) {
                o.setLong(0x2c, o.getLong(0x2c) + 0xc000);
            }
        }
        if (set46) {
            o.setByte(0x46, 0xff); // $252A
        }
        // $252E
        int d3 = o.getLong(0x2c);
        int ny = (o.getLong(0x06) + d3) >>> 16;
        int d2 = (water(c) - ny + 0x20) & 0xffff;
        if (((d2 ^ d0) & 0x8000) != 0 && d3 > 0x30000) {
            o.setLong(0x2c, o.getLong(0x2c) - (d3 >> 2));
        }
        return a4;
    }

    /**
     * $2816: deep under the water (depth > 32) the object gets an animation ($16CC, or $289C
     * when $10A5C > 0 and it has none) and sometimes (RNG, depending on $10A5C) a script among
     * $28A2..$28CE; sprite hidden ($FFFF).
     */
    static int surfaceAnim(NativeContext c, LevelObject o, int a4) {
        int a1 = SET + 0x16cc;
        a4 = SET + 0x28ce;
        int d1 = (short) (water(c) - o.y());
        if (d1 <= -0x20) {
            int flag = (byte) c.ram.read8(0x10a5c);
            if (flag == 0) { // $2872
                int r = c.random() & 0xffff;
                if (r < 0x51e) {
                    a4 = SET + 0x28b2;
                }
            } else if (flag < 0) { // $2840
                int r = c.random() & 0xffff;
                if (r < 0x888) {
                    int k = (r & 3) - 2;
                    a4 = k < 0 ? SET + 0x28ca : k == 0 ? SET + 0x28c2 : SET + 0x28ba;
                }
            } else {
                if (o.getLong(0x0c) != 0) {
                    return a4;
                }
                a1 = SET + 0x289c;
            }
        }
        o.setWord(0x0a, 0xffff); // $2890
        o.setLong(0x0c, a1);
        return a4;
    }

    /**
     * $2E38: above the water (y-16 <= $10A50): vx.w = +-4 by +$30 (0 if +$30 = 0); under it:
     * save the animation (+$36/+$44/+$46), set animation $2E8A, a4 = $2E92, vx.w = 0.
     */
    static int swimOrSink(NativeContext c, LevelObject o, int a4) {
        if ((short) (o.y() - 0x10) > water(c)) {
            o.setLong(0x36, o.getLong(0x0c));
            o.setWord(0x44, o.word(0x34));
            o.setWord(0x46, o.word(0x32));
            o.setWord(0x34, 0);
            o.setWord(0x32, 0);
            o.setLong(0x0c, SET + 0x2e8a);
            o.setWord(0x28, 0);
            return SET + 0x2e92;
        }
        int f = (byte) o.getByte(0x30);
        o.setWord(0x28, f == 0 ? 0 : f < 0 ? 0xfffc : 4);
        return a4;
    }

    /**
     * $32E6: floating object: if +$64 and not +$62 save the animation (+$36/+$50/+$52), set
     * animation $335A and a4 = $33A8; else vx.w = +$30 * 2 and choose $3290/$32AA/$32C4 from
     * y compared with the water and with +$46.
     */
    static int floater(NativeContext c, LevelObject o, int a4) {
        if (o.getByte(0x64) != 0) {
            o.setWord(0x28, 0);
            if (o.getByte(0x62) == 0) {
                o.setLong(0x36, o.getLong(0x0c));
                o.setWord(0x50, o.word(0x34));
                o.setWord(0x52, o.word(0x32));
                o.setLong(0x0c, SET + 0x335a);
                o.setWord(0x34, 0);
                o.setWord(0x32, 0);
                return SET + 0x33a8;
            }
        } else {
            o.setWord(0x28, (byte) o.getByte(0x30) * 2); // $331E
        }
        // $332A
        if ((short) (o.y() - 0x20) < water(c)) {
            return SET + 0x32aa;
        }
        int h = (short) o.word(0x46), y = (short) o.y();
        if (h == y) {
            o.setWord(0x2e, 0);
            return SET + 0x3290;
        }
        return h > y ? SET + 0x32aa : SET + 0x32c4;
    }

    /** $33B4: back under the water (y-32 >= water): restore the saved animation, clear +$64, $32E6. */
    static int floaterRestore(NativeContext c, LevelObject o, int a4) {
        if ((short) (o.y() - 0x20) < water(c)) {
            return a4;
        }
        o.setLong(0x0c, o.getLong(0x36));
        o.setWord(0x34, o.word(0x50));
        o.setWord(0x32, o.word(0x52));
        o.setByte(0x64, 0);
        return floater(c, o, a4);
    }

    /** $3E12: clear +$63 of every object of the two lists ($10AA8 and $10AAC: word n, n pointers). */
    static int clearLists63(NativeContext c, LevelObject o, int a4) {
        for (int list : new int[] {0x10aa8, 0x10aac}) {
            int a0 = c.ram.read32(list);
            int n = c.ram.read16(a0);
            a0 += 2;
            for (int i = 0; i < n; i++) {
                int obj = c.ram.read32(a0);
                a0 += 4;
                c.ram.write8(obj + 0x63, 0);
            }
        }
        return a4;
    }

    /** $3E32: protection check: the long at ((L_1113A) + $4098) must be $38891291, else a4 - 1. */
    static int protection(NativeContext c, LevelObject o, int a4) {
        int a0 = c.ram.read32(0x1113a);
        return c.ram.read32(a0 + 0x4098) != 0x38891291 ? a4 - 1 : a4;
    }

    /**
     * $3E6C: platform oscillating around (x $6C, y $A2) with velocity steps of 0.5, halving the
     * speed when it crosses the centre (+$32 / +$33 = stopped flags); moves itself and Bubba's x,
     * stores y - Bubba y at $1090E and sets $108C2; a4 = $3F32 when both axes are stopped.
     */
    static int platform(NativeContext c, LevelObject o, int a4) {
        if (o.getByte(0x32) != 0) {
            o.setLong(0x28, 0);
        } else {
            axis(o, o.word(0x02), 0x6c, 0x44, 0x28, 0x32);
        }
        if (o.getByte(0x33) != 0) {
            o.setLong(0x2c, 0);
        } else {
            axis(o, o.word(0x06), 0xa2, 0x46, 0x2c, 0x33);
        }
        // $3EF4
        int vx = o.getLong(0x28), vy = o.getLong(0x2c);
        o.setLong(0x02, o.getLong(0x02) + vx);
        o.setLong(0x06, o.getLong(0x06) + vy);
        c.ram.write32(HERO_X, c.ram.read32(HERO_X) + vx);
        c.ram.write16(0x1090e, o.word(0x06) - c.ram.read16(HERO_Y));
        c.ram.write8(0x108c2, 0xff);
        if (o.word(0x32) == 0xffff) {
            return SET + 0x3f32;
        }
        return a4;
    }

    private static void axis(LevelObject o, int pos, int centre, int prev, int vel, int stop) {
        int d1 = 0x8000;
        int d0 = (pos - centre) & 0xffff;
        if ((d0 & 0x8000) == 0) {
            d1 = -d1;
        }
        if (((o.word(prev) ^ d0) & 0x8000) != 0) { // crossed the centre: v >>= 1 (asr/roxr)
            o.setLong(vel, o.getLong(vel) >> 1);
            int d2 = (short) o.word(vel);
            if (d2 < 0) {
                d2 = (short) -d2;
            }
            o.setByte(stop, d2 <= 1 ? 0xff : 0);
        }
        o.setWord(prev, d0);
        o.setLong(vel, o.getLong(vel) + d1);
    }

    /** $2060: byte +$44 = $FF when y - 16 is above the water surface (word $10A50), signed. */
    static int aboveWater44(NativeContext c, LevelObject o, int a4) {
        int d0 = (short) (o.word(0x06) - 0x10);
        o.setByte(0x44, d0 < (short) c.ram.read16(0x10a50) ? 0xff : 0); // cmp.w / slt
        return a4;
    }

    /**
     * $2ECA: d0 = y - 16 - water (word). If d0 <= 0 and the object has landed (+$64): restore the
     * saved animation (+$0C = +$36, +$34 = +$44, +$32 = +$46) and go to $2E2C. Otherwise go to
     * $2E96 when d0 >= 0, $2EB0 when d0 < 0.
     */
    static int leaveWater(NativeContext c, LevelObject o, int a4) {
        int d0 = (short) (o.word(0x06) - 0x10 - c.ram.read16(0x10a50));
        if (d0 <= 0 && o.getByte(0x64) != 0) {
            o.setLong(0x0c, o.getLong(0x36));
            o.setWord(0x34, o.word(0x44));
            o.setWord(0x32, o.word(0x46));
            return SET + 0x2e2c;
        }
        return d0 >= 0 ? SET + 0x2e96 : SET + 0x2eb0;
    }
}
