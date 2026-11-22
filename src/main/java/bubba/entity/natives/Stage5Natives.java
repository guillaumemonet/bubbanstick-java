package bubba.entity.natives;

import bubba.cpu.GameMemory;
import bubba.entity.LevelObject;

import java.util.Map;

import static bubba.entity.natives.NativeContext.SET;

/**
 * Native routines of stage 5 (set5.bin), ported from the 68k code. Addresses are offsets in
 * set5.bin (loaded at $108EE); see Stage1Natives for the conventions.
 */
final class Stage5Natives {

    private Stage5Natives() {
    }

    static void register(Map<Integer, NativeRoutine> r) {
        r.put(0x6504, Stage5Natives::protectionCheck);
        r.put(0x207e, Stage5Natives::blockGraphics);
        r.put(0x2b6a, Stage5Natives::pushSelf44);
        r.put(0x2b76, Stage5Natives::pop44);
        r.put(0x2b80, Stage5Natives::heroInBox);
        r.put(0x34b4, Stage5Natives::heldCheck);
        r.put(0x3ae4, Stage5Natives::scrollLimit);
        r.put(0x3b04, Stage5Natives::setupScene);
        r.put(0x64e4, Stage5Natives::clearListFlags);
    }

    /**
     * $207E (type 65): redirect the graphics of 5 block-table entries to the 5 consecutive block
     * images at (L_1088C)+$CB00 (each entry: 16 graphics pointers after the attribute word). The
     * entry offsets are the words at set $55E. a3/a4 are saved and restored (movem).
     */
    static int blockGraphics(NativeContext c, LevelObject o, int a4) {
        GameMemory m = c.ram;
        int table = m.read32(0x10890);                 // movea.l L_10890,a1
        int words = SET + 0x55e;                       // lea $10E4C(pc),a2
        int gfx = m.read32(0x1088c) + 0xcb00;          // movea.l L_1088C,a3 / adda.l #$CB00,a3
        for (int k = 0; k < 5; k++) {                  // moveq #4,d3 ... dbra d3
            int entry = table + m.read16(words + 2 * k) + 2; // lea 2(a1,d0.l),a4 (d0 zero-extended)
            for (int i = 0; i < 16; i++) {             // moveq #$F,d1 / move.l a3,(a4)+ / dbra d1
                m.write32(entry + 4 * i, gfx);
            }
            gfx += 0x80;                               // lea $80(a3),a3
        }
        return a4;
    }

    /** $2B6A (types 70, 84, 96): +$48 = +$44, +$44 = own address (a5). */
    static int pushSelf44(NativeContext c, LevelObject o, int a4) {
        o.setLong(0x48, o.getLong(0x44));
        o.setLong(0x44, o.base);
        return a4;
    }

    /** $2B76 (types 70, 84, 96): +$44 = +$48 (undo of $2B6A). */
    static int pop44(NativeContext c, LevelObject o, int a4) {
        o.setLong(0x44, o.getLong(0x48));
        return a4;
    }

    /**
     * $2B80 (types 70, 84, 96): does the hero's box (hero struct +$14..$1A = words at $10924:
     * x0, y0, w, h - "movem.w d16(pc)": the base is the displacement word, after the register mask,
     * so the target is 2 bytes above what disassemblers print ($10922); relative to
     * the hero position $10912/$10916) overlap the object's box (x+$14, y+$16, size $18/$1A)?
     * Byte +$44 = $FF if so, else 0 (signed word compares).
     */
    static int heroInBox(NativeContext c, LevelObject o, int a4) {
        GameMemory m = c.ram;
        int x0 = (short) (m.read16(0x10924) + m.read16(0x10912)); // add.w $10912,d0
        int y0r = m.read16(0x10926);
        int w = m.read16(0x10928), h = m.read16(0x1092a);
        int x1 = (short) (x0 + w);                                   // add.w d0,d2
        int bx = (short) (o.word(0x02) + o.word(0x14));
        boolean in = false;
        if (x1 >= bx) {                                              // cmp.w d4,d2 / blt out
            int bx1 = (short) (bx + o.word(0x18));
            if (x0 <= bx1) {                                         // cmp.w d4,d0 / bgt out
                int y0 = (short) (y0r + m.read16(0x10916));
                int y1 = (short) (y0 + h);
                int by = (short) (o.word(0x06) + o.word(0x16));
                if (y1 >= by) {
                    int by1 = (short) (by + o.word(0x1a));
                    in = y0 <= by1;
                }
            }
        }
        o.setByte(0x44, in ? 0xff : 0);                              // st / clr.b $44(a5)
        return a4;
    }

    /**
     * $34B4 (type 90): if bit 5 of byte $1750B is set and the object is the one whose address is
     * the long at $17506, jump to script $3466; else continue.
     */
    static int heldCheck(NativeContext c, LevelObject o, int a4) {
        if ((c.ram.read8(0x1750b) & 0x20) != 0 && c.ram.read32(0x17506) == o.base) {
            return SET + 0x3466;
        }
        return a4;
    }

    /**
     * $3AE4 (type 113): long at $10902 = max(long at $11142, $1D80000) (signed); when the value is
     * above the limit, jump to script $3ADA.
     */
    static int scrollLimit(NativeContext c, LevelObject o, int a4) {
        int v = c.ram.read32(0x11142);
        if (v > 0x1d80000) {                 // cmpi.l / bgt
            a4 = SET + 0x3ada;               // lea $143C8(pc),a4
        } else {
            v = 0x1d80000;
        }
        c.ram.write32(0x10902, v);
        return a4;
    }

    /**
     * $3B04 (type 113): long $108FA = $1600000, byte $10A66 = $FF, and four words of the
     * structure pointed to by the long at $1113A (+$12 = $591, +$16 = $240, +$22 = $428,
     * +$2E = $D60).
     */
    static int setupScene(NativeContext c, LevelObject o, int a4) {
        GameMemory m = c.ram;
        m.write32(0x108fa, 0x1600000);
        m.write8(0x10a66, 0xff);
        int p = m.read32(0x1113a);
        m.write16(p + 0x12, 0x591);
        m.write16(p + 0x16, 0x240);
        m.write16(p + 0x22, 0x428);
        m.write16(p + 0x2e, 0xd60);
        return a4;
    }

    /**
     * $64E4 (type 113): for the two object lists pointed to by the longs at $10AA8 and $10AAC
     * (word count n, then n object pointers), clear byte +$63 of every object.
     */
    static int clearListFlags(NativeContext c, LevelObject o, int a4) {
        GameMemory m = c.ram;
        for (int list : new int[] {0x10aa8, 0x10aac}) {
            int p = m.read32(list);
            int n = m.read16(p);             // move.w (a0)+,d1 / bra dbra -> n iterations
            p += 2;
            for (int i = 0; i < n; i++) {
                m.write8(m.read32(p) + 0x63, 0); // movea.l (a0)+,a1 / clr.b $63(a1)
                p += 4;
            }
        }
        return a4;
    }

    /**
     * $6504: copy protection. If the long at (L_1113A)+$4098 is not $38891291 (written at boot),
     * a4 is decremented by one (sne / ext.w / adda.w), which makes the script crash on an odd
     * address. The key is set up by LevelMemory, so normally a4 is unchanged.
     */
    static int protectionCheck(NativeContext c, LevelObject o, int a4) {
        int base = c.ram.read32(0x1113a);
        return c.ram.read32(base + 0x4098) != 0x38891291 ? a4 - 1 : a4;
    }
}
