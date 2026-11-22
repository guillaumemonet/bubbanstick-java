package bubba.entity.natives;

import bubba.cpu.GameMemory;
import bubba.entity.LevelObject;

import java.util.Map;

import static bubba.entity.natives.NativeContext.SET;

/**
 * Native routines of stage 3 (set3.bin), ported from the 68k code. Addresses are offsets in
 * set3.bin (loaded at $108EE); see Stage1Natives for the conventions. Absolute addresses of game
 * variables ($10912 hero x, $10916 hero y, $10B0A frame counter...) are kept as in the original.
 */
final class Stage3Natives {

    private Stage3Natives() {
    }

    /** Byte table indexed by +$46 (switch / door states), set offset $5A7 = $12E95. */
    private static final int STATES = 0x12e95;

    static void register(Map<Integer, NativeRoutine> r) {
        r.put(0x4a9a, (c, o, a4) -> copyMotionTo(c, o, a4, 0x10aee));
        r.put(0x4d0e, (c, o, a4) -> copyMotionTo(c, o, a4, 0x10af2));
        r.put(0x22b2, Stage3Natives::walkerWatch);
        r.put(0x22e2, Stage3Natives::walkerStep);
        r.put(0x2830, Stage3Natives::bobInit);
        r.put(0x285e, Stage3Natives::bobVelocity);
        r.put(0x2898, Stage3Natives::cycleSprite);
        r.put(0x2b6a, Stage3Natives::heroInside);
        r.put(0x2f74, Stage3Natives::randomOffsets);
        r.put(0x2ffc, Stage3Natives::chaseLimits);
        r.put(0x35b0, Stage3Natives::stateSprite);
        r.put(0x368c, Stage3Natives::readState);
        r.put(0x369a, (c, o, a4) -> {
            o.setWord(0x0a, ((o.word(0x46) + 0x85) * 2) & 0xffff); // $369A: sprite = ($46 + $85) * 2
            return a4;
        });
        r.put(0x36f2, Stage3Natives::sinkStep);
        r.put(0x3748, (c, o, a4) -> {
            o.setLong(0x50, 0x10ad9); // $3748: +$50 = pointer to the flag byte $10AD9
            return a4;
        });
        r.put(0x37d8, (c, o, a4) -> {
            o.setByte(0x44, c.ram.read8(o.getLong(0x50))); // $37D8: $44 = (+$50).b
            return a4;
        });
        r.put(0x37e2, (c, o, a4) -> {
            c.ram.write8(o.getLong(0x50), 0); // $37E2: clr.b (+$50)
            return a4;
        });
        r.put(0x37ea, Stage3Natives::flagCount);
        r.put(0x407e, (c, o, a4) -> {
            c.ram.write32(0x10aea, o.base); // $407E: register this object at $10AEA
            return a4;
        });
        r.put(0x413e, Stage3Natives::liftToFloor);
        r.put(0x4464, (c, o, a4) -> {
            o.setLong(0x50, 0x12e9d); // $4464: +$50 = pointer to the flag byte $12E9D
            return a4;
        });
        r.put(0x45b0, Stage3Natives::heroOnTop);
        r.put(0x45ec, (c, o, a4) -> {
            o.setLong(0x44, o.base); // $45EC: +$44.l = own address
            return a4;
        });
        r.put(0x45f2, (c, o, a4) -> {
            o.setWord(0xa0, o.word(0xa0) ^ 0x8000); // $45F2: flip sprite base and direction
            o.setByte(0x30, -o.getByte(0x30));
            return a4;
        });
        r.put(0x4918, (c, o, a4) -> {
            o.setByte(0x44, (c.ram.read16(0x10b0a) & 0x3f) != 0 ? 0xff : 0); // $4918: sne
            return a4;
        });
        r.put(0x496c, (c, o, a4) -> {
            o.setByte(0x44, (c.ram.read16(0x10b0a) & 0x7f) != 0x1e ? 0xff : 0); // $496C: sne
            return a4;
        });
        r.put(0x4f0e, (c, o, a4) -> {
            o.setWord(0x44, c.random()); // $4F0E: $44 = random word
            return a4;
        });
    }

    // ---------------------------------------------------------------- walker (same as stage 1)

    /**
     * $22B2 (idle walker): is Bubba close? (-124 < dy < 80, -160 < dx < 160) - if so face him
     * (+$30 = dx < 0) and go to $2246, else stay in the idle loop $223A.
     */
    static int walkerWatch(NativeContext c, LevelObject o, int a4) {
        int dy = (short) o.word(0x88), dx = (short) o.word(0x86);
        if (dy <= -124 || dy >= 0x50 || dx <= -160 || dx >= 0xa0) {
            return SET + 0x223a;
        }
        o.setByte(0x30, dx < 0 ? 0xff : 0);
        return SET + 0x2246;
    }

    /**
     * $22E2 (walking): back to idle when Bubba is far, turn around on a wall hit, when Bubba is
     * close behind, at the edge of a platform or in front of a wall. The map test mirrors the
     * original pointer arithmetic exactly (map L_10888, block table L_10890, row bytes $10A48).
     */
    static int walkerStep(NativeContext c, LevelObject o, int a4) {
        if (o.getByte(0x64) == 0 || o.getByte(0x46) != 0) {
            return walkExit(o);
        }
        int dy = (short) o.word(0x88), dx = (short) o.word(0x86);
        if (dy <= -164 || dy >= 0x78 || dx <= -256 || dx >= 0x100) { // $2310
            o.setLong(0x28, 0);
            o.setLong(0x2c, 0);
            setAnim(o, SET + 0x21de);
            return SET + 0x223a;
        }
        if (o.getByte(0x9e) != 0) { // $232E: hit a wall, turn around
            o.setByte(0x30, ~o.getByte(0x30));
            return walkExit(o);
        }
        int face = (byte) o.getByte(0x30);
        if (((face ^ dx) & 0x8000) != 0) { // $233C: Bubba behind
            int ad = dx < 0 ? (short) -dx : dx;
            if (ad <= 0x58 && dy < 0x28) {
                return turn(o);
            }
        }
        // $235C: only in the half of the block facing the walking direction
        int xl = o.word(0x02) & 15;
        if (face < 0 ? xl - 8 >= 0 : xl - 8 < 0) {
            return walkExit(o);
        }
        GameMemory m = c.ram;
        int d0 = (short) (face << 2);
        int rowBytes = m.read16(0x10a48);
        int mul = (((short) (o.word(0x06) - 1) >> 4) & 0xffff) * rowBytes;
        int d1 = (mul & 0xffff0000) | ((mul + 2 * ((short) o.word(0x02) >> 4)) & 0xffff);
        int hi = d1 & 0xffff0000;
        int a0 = m.read32(0x10888) + d1;
        int a1 = m.read32(0x10890);
        int a2 = a0;
        a0 += (short) rowBytes;
        if ((m.read8(a1 + (hi | m.read16(a0)) + 1) & 4) != 0) { // special block below
            return (byte) o.getByte(0x30) < 0 ? SET + 0x2276 : SET + 0x2294;
        }
        if ((m.read16(a1 + (hi | m.read16(a0 + 2 + d0))) & 0x13) == 0) {
            return turn(o); // no ground ahead
        }
        for (int k = 0; k < 4; k++) { // dbne d3 loop
            int cell = hi | m.read16(a2);
            a2 += 2 + d0;
            if ((m.read16(a1 + cell) & 0x11) != 0) {
                return turn(o); // wall ahead
            }
        }
        return walkExit(o);
    }

    /** $23C6: start turning (+$46 = $FF, anim $21EE). */
    private static int turn(LevelObject o) {
        o.setByte(0x46, 0xff);
        setAnim(o, SET + 0x21ee);
        return walkExit(o);
    }

    /** $23D2. */
    private static int walkExit(LevelObject o) {
        return (byte) o.getByte(0x30) < 0 ? SET + 0x225e : SET + 0x226a;
    }

    /** $2320. */
    private static void setAnim(LevelObject o, int script) {
        o.setLong(0x0c, script);
        o.setWord(0x34, 0);
        o.setWord(0x32, 0);
    }

    // ---------------------------------------------------------------- bobbing objects

    /**
     * $2830: centre +$44 = y - $60; phase +$46 += ((frame counter $10B0A mod $60) & ~1) * 2,
     * wrapped below $C0.
     */
    static int bobInit(NativeContext c, LevelObject o, int a4) {
        o.setWord(0x44, o.word(0x06) - 0x60);
        int d0 = (c.ram.read16(0x10b0a) % 0x60) & 0xfffe;
        d0 = (short) (d0 * 2 + o.word(0x46));
        if (d0 >= 0xc0) {
            d0 -= 0xc0;
        }
        o.setWord(0x46, d0);
        return a4;
    }

    /** $285E: vertical velocity +$2C = ((centre - y) * 2 + phase) as 16.16, halved. */
    static int bobVelocity(NativeContext c, LevelObject o, int a4) {
        int d0 = (short) ((o.word(0x44) - o.word(0x06)) * 2 + o.word(0x46));
        o.setLong(0x2c, (d0 << 16) >> 1);
        return a4;
    }

    /**
     * $2898: animation cycle on +$46 (0..$BE step 2): sprite from the (limit, frame) table $28EC
     * (frame < 0 = sprite as is, else ($92 + frame) * 2), +$82 = 2 while $40 <= phase < $80;
     * script jumps to $2884 at phase $38 and $288E at phase $80.
     */
    static int cycleSprite(NativeContext c, LevelObject o, int a4) {
        int d0 = (short) o.word(0x46);
        int d1 = (short) lookup(c.ram, SET + 0x28ec, d0, true);
        o.setWord(0x0a, d1 < 0 ? d1 : (d1 + 0x92) * 2);
        o.setWord(0x82, d0 >= 0x40 && d0 < 0x80 ? 2 : 0);
        d0 = (short) (d0 + 2);
        if (d0 == 0xc0) {
            d0 = 0;
        }
        o.setWord(0x46, d0);
        if (d0 == 0x38) {
            a4 = SET + 0x2884;
        }
        if (d0 == 0x80) {
            a4 = SET + 0x288e;
        }
        return a4;
    }

    /**
     * Table of (key, value) word pairs: value of the first pair with key >= d (bgt loop) or
     * key > d (bge loop), keys compared signed.
     */
    private static int lookup(GameMemory m, int a, int d, boolean ge) {
        for (int guard = 0; guard < 100_000; guard++, a += 4) {
            int k = (short) m.read16(a);
            if (ge ? d >= k : d > k) {
                continue;
            }
            return m.read16(a + 2);
        }
        throw new IllegalStateException("table loop");
    }

    // ---------------------------------------------------------------- misc

    /**
     * $2B6A: +$44 = $FF when bit 1 of $1750B is set and the point ($1098A, $1098E) is inside the
     * object's box (x-8-15 .. x-8 horizontally, above y+$16, within +$1A).
     */
    static int heroInside(NativeContext c, LevelObject o, int a4) {
        o.setByte(0x44, 0);
        if ((c.ram.read8(0x1750b) & 2) == 0) {
            return a4;
        }
        int a = (short) (o.word(0x02) - 8), b = (short) c.ram.read16(0x1098a);
        if (a > b) { // sub.w / bgt: signed compare
            return a4;
        }
        if ((short) (a - b + 15) < 0) {
            return a4;
        }
        int d0 = (short) (o.word(0x06) + o.word(0x16) - c.ram.read16(0x1098e));
        if (d0 >= 0) {
            return a4;
        }
        o.setByte(0x44, (short) (d0 - o.word(0x1a)) < 0 ? 0xff : 0);
        return a4;
    }

    /** $2F74: random offsets: +$44 / +$46 = top nibble of each RNG word, signed (-8..7). */
    static int randomOffsets(NativeContext c, LevelObject o, int a4) {
        int d0 = c.random();
        int d1 = c.ram.read32(0x10894);
        o.setWord(0x44, (short) (d1 & 0xf000) >> 12);
        o.setWord(0x46, (short) (d0 & 0xf000) >> 12);
        return a4;
    }

    /**
     * $2FFC: chase Bubba with an offset (+$44, +$46 - $3C): speed limits +$8C/$90 and +$94/$98
     * from the distance tables $308C / $3090 plus Bubba's speed ($10938 / $1093C); script by the
     * direction ($2FCC, $2FD8, $2FE4, $2FF0).
     */
    static int chaseLimits(NativeContext c, LevelObject o, int a4) {
        GameMemory m = c.ram;
        int d0 = (short) (m.read16(0x10912) + o.word(0x44) - o.word(0x02));
        int d2 = lookup(m, SET + 0x308c, d0 < 0 ? (short) -d0 : d0, false);
        int d3 = (short) m.read16(0x10938);
        d2 = ((d2 + (d3 < 0 ? -d3 : d3)) & 0xffff) << 16;
        o.setLong(0x90, d2);
        o.setLong(0x8c, -d2);
        int d1 = (short) (m.read16(0x10916) - 0x3c + o.word(0x46) - o.word(0x06));
        d2 = lookup(m, SET + 0x3090, d1 < 0 ? (short) -d1 : d1, false);
        d3 = (short) m.read16(0x1093c);
        d2 = ((d2 + (d3 < 0 ? -d3 : d3)) & 0xffff) << 16;
        o.setLong(0x98, d2);
        o.setLong(0x94, -d2);
        if (d0 >= 0) {
            return d1 < 0 ? SET + 0x2fe4 : SET + 0x2ff0;
        }
        return d1 < 0 ? SET + 0x2fcc : SET + 0x2fd8;
    }

    /** $35B0: +$44 = state[$46] != 0; sprite $F8 (off) or $FA (on). */
    static int stateSprite(NativeContext c, LevelObject o, int a4) {
        boolean on = c.ram.read8(STATES + (short) o.word(0x46)) != 0;
        o.setByte(0x44, on ? 0xff : 0);
        o.setWord(0x0a, on ? 0xfa : 0xf8);
        return a4;
    }

    /** $368C: +$44 = state[$46]. */
    static int readState(NativeContext c, LevelObject o, int a4) {
        o.setByte(0x44, c.ram.read8(STATES + (short) o.word(0x46)));
        return a4;
    }

    /**
     * $36F2: sinking object: depth +$45 -= vy ($2D); clamp it to 0 .. $47-8 moving y by the
     * excess; sprite = ($6A - (($47 - depth) - 1) / 16) * 2 when $47 - depth >= 8.
     */
    static int sinkStep(NativeContext c, LevelObject o, int a4) {
        int d0 = (byte) (o.getByte(0x45) - o.getByte(0x2d));
        o.setByte(0x45, d0);
        boolean move = false;
        if (d0 < 0) {
            o.setByte(0x45, 0);
            move = true;
        } else {
            d0 = (byte) (d0 - o.getByte(0x47));
            if (d0 >= -8) {
                o.setByte(0x45, o.getByte(0x47) - 8);
                d0 = (byte) (d0 + 8);
                move = true;
            }
        }
        if (move) { // $371C
            o.setWord(0x06, o.word(0x06) + d0);
            o.setLong(0x2c, 0);
        }
        d0 = (byte) (o.getByte(0x47) - o.getByte(0x45));
        if (d0 >= 8) {
            int d1 = (0x6a - (((d0 - 1) & 0xffff) >>> 4)) * 2;
            o.setWord(0x0a, d1 & 0xffff);
        }
        return a4;
    }

    /** $37EA: +$44 = (+$50).b; increment it while negative, except the flag at $12E9D. */
    static int flagCount(NativeContext c, LevelObject o, int a4) {
        int a0 = o.getLong(0x50);
        int b = c.ram.read8(a0);
        o.setByte(0x44, b);
        if ((b & 0x80) != 0 && a0 != 0x12e9d) {
            c.ram.write8(a0, b + 1);
        }
        return a4;
    }

    /**
     * $413E: lift: nearest floor level to $10ADC in the table $41D0 ($41E8); at that level:
     * script $40B8 and +$44 = ($10ADA < x); else script $40AC (going down) / $40A0 (up) with
     * vertical limits +$94/$98 from the distance table $4198 (max 4 once when +$62 is set).
     */
    static int liftToFloor(NativeContext c, LevelObject o, int a4) {
        GameMemory m = c.ram;
        int d0 = m.read16(0x10adc);
        int best = 0xffff, d3 = 0;
        for (int i = 0; i < 6; i++) { // $41E8
            int t = m.read16(SET + 0x41d0 + 2 * i);
            int d4 = (d0 - t) & 0xffff;
            if ((d4 & 0x8000) != 0) {
                d4 = -d4 & 0xffff;
            }
            if (d4 < best) {
                best = d4;
                d3 = t;
            }
        }
        d3 = (short) (d3 - o.word(0x06));
        if (d3 == 0) { // $4186
            o.setByte(0x44, (short) (m.read16(0x10ada) - o.word(0x02)) < 0 ? 0xff : 0);
            return SET + 0x40b8;
        }
        a4 = SET + 0x40ac;
        if (d3 < 0) {
            d3 = (short) -d3;
            a4 = SET + 0x40a0;
        }
        int d2 = lookup(m, SET + 0x4198, d3, false);
        if ((short) d2 > 4 && o.getByte(0x62) != 0) {
            o.setByte(0x62, 0);
            d2 = 4;
        }
        d2 = (d2 & 0xffff) << 16;
        o.setLong(0x98, d2);
        o.setLong(0x94, -d2);
        return a4;
    }

    /**
     * $45B0: +$44 = $FF when Bubba stands exactly on top of the object (hero y = y + $16 + $1A),
     * within $B4 pixels horizontally and in front of it (same sign as the facing +$30).
     */
    static int heroOnTop(NativeContext c, LevelObject o, int a4) {
        GameMemory m = c.ram;
        int d0 = (m.read16(0x10916) - o.word(0x06) - o.word(0x16) - o.word(0x1a)) & 0xffff;
        if (d0 != 0) {
            o.setByte(0x44, 0);
            return a4;
        }
        d0 = (short) (m.read16(0x10912) - o.word(0x02));
        int d1 = d0 < 0 ? (short) -d0 : d0;
        if (d1 > 0xb4 || ((((byte) o.getByte(0x30)) ^ d0) & 0x8000) != 0) {
            o.setByte(0x44, 0);
            return a4;
        }
        o.setByte(0x44, 0xff);
        return a4;
    }

    /**
     * $4A9A / $4D0E: copy this object's x (long +$02) and x velocity (long +$28) into the object
     * whose structure pointer is the long at $10AEE / $10AF2 (movea.l / move.l / move.l).
     */
    static int copyMotionTo(NativeContext c, LevelObject o, int a4, int pointer) {
        int other = c.ram.read32(pointer);
        c.ram.write32(other + 0x02, o.getLong(0x02));
        c.ram.write32(other + 0x28, o.getLong(0x28));
        return a4;
    }
}
