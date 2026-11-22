package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * 0x539E: Bubba's hardware sprites. Exact port.
 * <p>
 * Position: x = hero x + $91 - camera x (- $1090A when the screen is shaken / +$70 bit 1),
 * y = $C0 + frame dy (+ $1090E); +$76 = -dy. The control words (SPRxPOS / SPRxCTL) go to
 * $840C, copied to the 4 sprite pairs ($8410..$841B), $841C = number of 16-pixel columns - 1.
 * <p>
 * When the frame ($50) or the facing (+$55, kept in +$54) changed since the last call (cache
 * $108C2 / $108C4, $108C5 = unchanged flag), every column of the frame (table $1C174) is copied
 * into the sprite buffer (pointer $8442) as two attached sprites: A (planes 0/1) then B (planes
 * 2/3, stored bit-reversed). Facing left, A is copied through the bit-reverse table $573A and B
 * as is, which mirrors the picture. The start of each sprite's data goes to $841E..., its end to
 * $83EC... The line counts come from the table $55F6 (offsets into the unrolled copy loops).
 */
final class HeroSprite {

    private static final int HERO = 0x10910, CTL = 0x840c, REVERSE = 0x573a, COUNTS = 0x55f6;

    private HeroSprite() {
    }

    static void register(Map<Integer, Ports.Port> ports) {
        ports.put(0x539e, HeroSprite::run);
    }

    static void run(M68k c) {
        GameMemory m = c.mem;
        // the VBL wait at the start ($826C) is not part of the logic
        int frame = m.read16(HERO + 0x50);
        int a0 = 0x1c174;
        int a1 = m.read32(0x8442);
        int d0 = (m.read16(HERO + 2) + 0x91 - m.read16(0x108ca)) & 0xffff;
        int d1 = 0xc0;
        if (m.read8(0x10abc) != 0 || (m.read8(HERO + 0x70) & 2) != 0) {
            d0 = (d0 - m.read16(0x1090a)) & 0xffff;
            d1 = (d1 + m.read16(0x1090e)) & 0xffff;
        }
        a0 += (short) m.read16(a0 + 2 + ((2 * frame) & 0xffff));
        int dx = m.read16(a0), dy = m.read16(a0 + 2), dxFlipped = m.read16(a0 + 4);
        a0 += 6;
        d1 = (d1 + dy) & 0xffff;
        m.write16(HERO + 0x76, -dy);
        int facing = m.read8(HERO + 0x55);
        boolean left = (facing & 0x80) != 0;
        d0 = (d0 + (left ? dxFlipped : dx)) & 0xffff;
        // SPRxPOS / SPRxCTL: VSTART low bits, HSTART >> 1, then VSTART bit 8 and HSTART bit 0
        int x1 = d1 & 1;
        d1 >>>= 1;
        int sum = (d1 & 0xff) * 2 + x1;
        d1 = (d1 & 0xff00) | (sum & 0xff);
        int ctl = 0x40 + (sum >> 8);
        ctl = ctl * 2 + (d0 & 1);
        d0 >>>= 1;
        m.write8(CTL + 1, d0);
        m.write8(CTL + 3, ctl);

        boolean same = m.read16(0x108c2) == frame;
        m.write8(0x108c5, same ? 0xff : 0);
        if (same) {
            same = m.read8(0x108c4) == facing;
            m.write8(0x108c5, same ? 0xff : 0);
            if (same) {
                return;
            }
        }
        m.write8(0x108c4, facing);
        m.write16(0x108c2, frame);
        m.write8(CTL, d1);
        m.write8(CTL + 2, d1);
        int v = m.read32(CTL);
        m.write32(CTL + 4, v);
        m.write32(CTL + 8, v);
        m.write32(CTL + 12, v);
        int cols = m.read16(a0);
        a0 += 2;
        m.write16(0x841c, cols);
        int a5 = 0x841e, a2 = 0x83ec, a4 = CTL;
        int blocks = (((2 * cols + 1) & 0xffff)) + 1;
        m.write8(HERO + 0x54, facing);
        while (true) {
            // column header: y offset, data pointer, height
            m.write32(a5, a1);
            a5 += 4;
            int yo = m.read16(a0);
            a0 += 2;
            m.write8(a4, m.read8(a4) + yo);
            m.write8(a4 + 2, m.read8(a4 + 2) + yo);
            int a6 = m.read32(a0);
            a0 += 4;
            int h = m.read16(a6);
            a6 += 2;
            m.write8(a4 + 2, m.read8(a4 + 2) + h);
            a4 += 4;
            int t = COUNTS + (short) ((h << 2) & 0xffff);
            int longs = count(m.read8(t), m.read8(t + 1), 2, "long");
            int words = count(m.read8(t + 2), m.read8(t + 3), 12, "word");
            // first sprite
            if (left) {
                a6 = reversed(m, a6, a1, words);
                a1 += 2 * words;
            } else {
                copy(m, a6, a1, longs);
                a6 += 4 * longs;
                a1 += 4 * longs;
            }
            m.write32(a2, a1);
            a2 += 4;
            a1 += 4;
            if (--blocks == 0) {
                return;
            }
            // second sprite
            m.write32(a5, a1);
            a5 += 4;
            if (left) {
                copy(m, a6, a1, longs);
                a1 += 4 * longs;
            } else {
                reversed(m, a6, a1, words);
                a1 += 2 * words;
            }
            m.write32(a2, a1);
            a2 += 4;
            a1 += 4;
            if (--blocks == 0) {
                return;
            }
        }
    }

    /**
     * Units copied by an unrolled loop of 8 entered at byte offset {@code entry} (each unit
     * {@code size} bytes of code), then run {@code loops} more times.
     */
    private static int count(int entry, int loops, int size, String what) {
        if (entry % size != 0 || entry > 8 * size) {
            throw new IllegalStateException("hero sprite: bad " + what + " entry " + entry);
        }
        return 8 - entry / size + 8 * loops;
    }

    private static void copy(GameMemory m, int src, int dst, int longs) {
        for (int i = 0; i < longs; i++) {
            m.write32(dst + 4 * i, m.read32(src + 4 * i));
        }
    }

    /** Words copied with their bits reversed (bytes swapped, each through the table $573A). */
    private static int reversed(GameMemory m, int src, int dst, int words) {
        for (int i = 0; i < words; i++) {
            int b4 = m.read8(src), b5 = m.read8(src + 1);
            src += 2;
            m.write8(dst, m.read8(REVERSE + b5));
            m.write8(dst + 1, m.read8(REVERSE + b4));
            dst += 2;
        }
        return src;
    }
}
