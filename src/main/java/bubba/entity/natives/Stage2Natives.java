package bubba.entity.natives;

import bubba.cpu.GameMemory;
import bubba.entity.LevelObject;

import java.util.Map;

import static bubba.entity.natives.NativeContext.SET;

/**
 * Native routines of stage 2 (set2.bin), ported from the 68k code. Addresses are offsets in the
 * stage's set*.bin (loaded at $108EE); see Stage1Natives for the conventions.
 */
final class Stage2Natives {

    /** Level variables of set2.bin (absolute addresses). */
    private static final int FRAME_COUNTER = SET + 0x21c;  // word, cycles (used for blinking)
    private static final int OBJECT_LIST = SET + 0x1be;    // long -> word count, then object pointers
    private static final int ROW_BYTES = SET + 0x15a;      // $10A48: bytes per map row
    private static final int MAP_PTR = 0x10888;            // (L_10888) map, cells = block table offsets
    private static final int BLOCKS_PTR = 0x10890;         // (L_10890) block table, attribute word at +0

    private Stage2Natives() {
    }

    static void register(Map<Integer, NativeRoutine> r) {
        r.put(0x21dc, Stage2Natives::walkerWatch);
        r.put(0x220c, Stage2Natives::walkerStep);
        r.put(0x3638, Stage2Natives::blinkCountdown);
        r.put(0x364a, Stage2Natives::blink);
        r.put(0x36d8, Stage2Natives::verticalCrawler);
        r.put(0x38a8, Stage2Natives::grabObject);
        r.put(0x3abe, Stage2Natives::blinkDC);
        r.put(0x5174, Stage2Natives::heldByHero);
        r.put(0x538c, Stage2Natives::flag2369Is4);
        r.put(0x54a4, Stage2Natives::savePosition);
        r.put(0x5504, Stage2Natives::restorePosition);
        r.put(0x551c, Stage2Natives::storeXInTable);
        r.put(0x5618, Stage2Natives::random44);
    }

    // ------------------------------------------------------------------ type 16: walker

    /**
     * $21DC (type 16, idle): Bubba close (-124 < $88 < 80, -160 < $86 < 160)? face him
     * (+$30 = $86 < 0) and go to the walking script $2170, else stay in the idle loop $2164.
     */
    static int walkerWatch(NativeContext c, LevelObject o, int a4) {
        int dy = (short) o.word(0x88), dx = (short) o.word(0x86);
        if (dy <= -124 || dy >= 80 || dx <= -160 || dx >= 160) {
            return SET + 0x2164;
        }
        o.setByte(0x30, dx < 0 ? 0xff : 0);                       // tst.w / smi
        return SET + 0x2170;
    }

    /**
     * $220C (type 16, walking): same logic as stage 1's $1940 (back to idle when Bubba is far,
     * turn around on a wall hit, when Bubba is close behind, at a platform edge or in front of
     * a wall), with stage 2 script addresses. The map is read from memory with the exact 68k
     * register arithmetic (word add after mulu, d1.l index keeping the upper word).
     */
    static int walkerStep(NativeContext c, LevelObject o, int a4) {
        if (o.getByte(0x64) == 0 || o.getByte(0x46) != 0) {
            return walkExit(o);                                    // $22FC
        }
        int dy = (short) o.word(0x88), dx = (short) o.word(0x86);
        if (dy <= -164 || dy >= 120 || dx <= -256 || dx >= 256) { // $223A: far
            o.setLong(0x28, 0);
            o.setLong(0x2c, 0);
            setAnim(o, SET + 0x2108);
            return SET + 0x2164;
        }
        if (o.getByte(0x9e) != 0) {                                // $225E: wall hit
            o.setByte(0x30, ~o.getByte(0x30));
            return walkExit(o);
        }
        int face = (byte) o.getByte(0x30);
        // $2266: Bubba behind, |dx| <= $58 (neg.w of $8000 stays negative) and dy < 40
        if ((((short) face ^ dx) & 0x8000) != 0) {
            int ad = (short) (dx < 0 ? -dx : dx);
            if (ad <= 0x58 && dy < 0x28) {
                return turn(o);
            }
        }
        // $2286: test only in the half of the block facing the walking direction
        int xl = (o.word(0x02) & 15) - 8;
        if (face < 0 ? xl >= 0 : xl < 0) {
            return walkExit(o);
        }
        GameMemory m = c.ram;
        int d0 = (short) (face * 4);                               // ext.w + asl.w #2: 0 or -4
        int rb = m.read16(ROW_BYTES);
        int d1 = cellOffset(m, o.word(0x02), (o.word(0x06) - 1) & 0xffff);
        int a0 = m.read32(MAP_PTR) + d1;
        int a1 = m.read32(BLOCKS_PTR);
        int a2 = a0;
        a0 += (short) rb;                                          // row below
        d1 = (d1 & 0xffff0000) | m.read16(a0);
        if ((m.read8(a1 + d1 + 1) & 4) != 0) {                     // $230C: special block below
            return face < 0 ? SET + 0x21a0 : SET + 0x21be;
        }
        d1 = (d1 & 0xffff0000) | m.read16(a0 + 2 + d0);
        if ((m.read16(a1 + d1) & 0x13) == 0) {
            return turn(o);                                        // no ground ahead
        }
        for (int k = 0; k < 4; k++) {                              // $22E0 dbne loop
            d1 = (d1 & 0xffff0000) | m.read16(a2);
            a2 += 2 + d0;
            if ((m.read16(a1 + d1) & 0x11) != 0) {
                return turn(o);                                    // wall ahead
            }
        }
        return walkExit(o);
    }

    /** $22F0: st $46, anim $2118, then exit. */
    private static int turn(LevelObject o) {
        o.setByte(0x46, 0xff);
        setAnim(o, SET + 0x2118);
        return walkExit(o);
    }

    /** $22FC: walking script left $2188 / right $2194. */
    private static int walkExit(LevelObject o) {
        return (byte) o.getByte(0x30) < 0 ? SET + 0x2188 : SET + 0x2194;
    }

    /** $224A: +$0C = animation script, clear +$34 / +$32. */
    private static void setAnim(LevelObject o, int script) {
        o.setLong(0x0c, script);
        o.setWord(0x34, 0);
        o.setWord(0x32, 0);
    }

    /**
     * Map byte offset of the cell holding pixel (x, y) as the 68k computes it: (y asr 4) mulu
     * row bytes, then add.w of (x asr 4) * 2 into the low word only.
     */
    private static int cellOffset(GameMemory m, int xWord, int yWord) {
        int row = ((short) yWord >> 4) & 0xffff;
        int d1 = (int) ((long) row * m.read16(ROW_BYTES));        // mulu.w
        int col = (((short) xWord >> 4) * 2) & 0xffff;
        return (d1 & 0xffff0000) | ((d1 + col) & 0xffff);
    }

    // ------------------------------------------------------------------ blinking sprites

    /**
     * $3638 (type 95, anim): +$44 = (+$44 >= 10 signed) ? $FF : 0; when below 10, clear the
     * level byte $2370; then the blink of $364A.
     */
    static int blinkCountdown(NativeContext c, LevelObject o, int a4) {
        boolean ge = (byte) o.getByte(0x44) >= 10;
        o.setByte(0x44, ge ? 0xff : 0);
        if (!ge) {
            c.ram.write8(SET + 0x2370, 0);
        }
        return blink(c, o, a4);
    }

    /**
     * $364A (types 97-111, anim): sprite +$0A = +$46, except when +$44 is set: then hidden ($FFFF)
     * during the first half of each 32-frame cycle of the counter $21C.
     */
    static int blink(NativeContext c, LevelObject o, int a4) {
        if (o.getByte(0x44) != 0 && (c.ram.read16(FRAME_COUNTER) & 0x1f) < 0x10) {
            o.setWord(0x0a, 0xffff);
        } else {
            o.setWord(0x0a, o.word(0x46));
        }
        return a4;
    }

    /** $3ABE (types 121-124, anim): sprite +$0A = $DC or hidden ($FFFF) when (counter & 31) < 10. */
    static int blinkDC(NativeContext c, LevelObject o, int a4) {
        o.setWord(0x0a, (c.ram.read16(FRAME_COUNTER) & 0x1f) < 10 ? 0xffff : 0xdc);
        return a4;
    }

    // ------------------------------------------------------------------ types 112-115

    /**
     * $36D8 (types 112-115, behaviour): crawler moving vertically (+$44 < 0 = up, else down) and
     * facing +$45. Probes the map cell ahead (x +-16, or +-24 when going up) at the leading edge
     * (y + $16 [+ $1A] +- 1); +$2C = direction (1 / -1). When that cell is empty (attribute & $13
     * going down, & $11 going up), or when one of the two cells beyond it (in the moving
     * direction's opposite) is a wall (& $11), the horizontal direction +$45 is negated.
     */
    static int verticalCrawler(NativeContext c, LevelObject o, int a4) {
        GameMemory m = c.ram;
        int d0 = (byte) o.getByte(0x45) < 0 ? -16 : 16;
        boolean up = (byte) o.getByte(0x44) < 0;
        if (up) {
            d0 += d0 >> 1;
        }
        d0 = (d0 + o.word(0x02)) & 0xffff;
        int d1 = o.word(0x06) + o.word(0x16);
        int d2;
        if (!up) {
            d1 += o.word(0x1a);
            d2 = 1;
        } else {
            d2 = -1;
        }
        d1 = (d1 + d2) & 0xffff;
        o.setWord(0x2c, d2);
        // $373A
        int off = cellOffset(m, d0, d1);
        int a0 = m.read32(MAP_PTR) + off;
        d1 = (off & 0xffff0000) | m.read16(a0);
        int a1 = m.read32(BLOCKS_PTR);
        int mask = (short) o.word(0x2c) < 0 ? 0x11 : 0x13;
        if ((m.read16(a1 + d1) & mask) != 0) {
            int d3 = (short) m.read16(ROW_BYTES);
            if (d2 >= 0) {
                d3 = (short) -d3;
            }
            boolean wall = false;
            for (int k = 0; k < 2 && !wall; k++) {                 // $3722 dbne d4 (2 cells)
                d1 = (d1 & 0xffff0000) | m.read16(a0 + d3);
                d3 = (short) (d3 + d3);
                wall = (m.read16(a1 + d1) & 0x11) != 0;
            }
            if (!wall) {
                return a4;
            }
        }
        o.setByte(0x45, -o.getByte(0x45));                        // $3734 neg.b
        return a4;
    }

    // ------------------------------------------------------------------ type 116

    /**
     * $38A8 (type 116, anim): look in the object list ($1BE) for the first object of type $A6;
     * when it stands exactly at (x $833, y $2A0), keep it in +$44, stop it (+$54, +$10 = 0),
     * set +$63 and go to $381A.
     */
    static int grabObject(NativeContext c, LevelObject o, int a4) {
        GameMemory m = c.ram;
        int a0 = m.read32(OBJECT_LIST);
        int n = m.read16(a0);
        // n = 0 makes the 68k compare a stale a1: treated as "not found" (never happens in game)
        int a1 = 0;
        boolean found = false;
        for (int k = 0; k < n; k++) {
            a1 = m.read32(a0 + 2 + 4 * k);
            if (m.read16(a1 + 0x5c) == 0xa6) {
                found = true;
                break;
            }
        }
        if (!found || m.read16(a1 + 2) != 0x833 || m.read16(a1 + 6) != 0x2a0) {
            return a4;
        }
        o.setLong(0x44, a1);
        m.write32(a1 + 0x54, 0);
        m.write32(a1 + 0x10, 0);
        o.setByte(0x63, 0xff);
        return SET + 0x381a;
    }

    // ------------------------------------------------------------------ other types

    /** $5174 (type 146): +$44 = $FF when bit 6 of $1750B is set and (long $17506) is this object. */
    static int heldByHero(NativeContext c, LevelObject o, int a4) {
        boolean held = (c.ram.read8(0x1750b) & 0x40) != 0 && c.ram.read32(0x17506) == o.base;
        o.setByte(0x44, held ? 0xff : 0);
        return a4;
    }

    /** $538C (type 154): +$63 = (level byte $2369 == 4) ? $FF : 0. */
    static int flag2369Is4(NativeContext c, LevelObject o, int a4) {
        o.setByte(0x63, c.ram.read8(SET + 0x2369) == 4 ? 0xff : 0);
        return a4;
    }

    /** $54A4 (type 157): save the position (+$44 = x, +$46 = y). */
    static int savePosition(NativeContext c, LevelObject o, int a4) {
        o.setWord(0x44, o.word(0x02));
        o.setWord(0x46, o.word(0x06));
        return a4;
    }

    /** $5504 (type 157): back to the saved position, +$9D = 2, timer +$3A = 0. */
    static int restorePosition(NativeContext c, LevelObject o, int a4) {
        o.setWord(0x02, o.word(0x44));
        o.setWord(0x06, o.word(0x46));
        o.setByte(0x9d, 2);
        o.setWord(0x3a, 0);
        return a4;
    }

    /** $551C (type 133 behaviour, 169 anim): level table word at $1EC + (signed) +$46 = x. */
    static int storeXInTable(NativeContext c, LevelObject o, int a4) {
        c.ram.write16(SET + 0x1ec + (short) o.word(0x46), o.word(0x02));
        return a4;
    }

    /** $5618 (types 31, 127-129, 135, 143, 144, 160, 190): +$44 = random word. */
    static int random44(NativeContext c, LevelObject o, int a4) {
        o.setWord(0x44, c.random());
        return a4;
    }
}
