package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

/**
 * 68000 register arithmetic shared by the ports: word and byte writes into a 32-bit register
 * (the upper part is kept, as {@code move.w} / {@code move.b} do), swap, rotate, the stack, and
 * the game's random generator.
 */
final class Regs {

    /** The random generator's state: two longs at $10894 / $10898. */
    static final int RND = 0x10894;

    private Regs() {
    }

    /** {@code move.w v,reg}: the low word replaced. */
    static int sw(int reg, int v) {
        return (reg & 0xffff0000) | (v & 0xffff);
    }

    /** {@code move.b v,reg}: the low byte replaced. */
    static int sb(int reg, int v) {
        return (reg & 0xffffff00) | (v & 0xff);
    }

    static int swap(int v) {
        return (v << 16) | (v >>> 16);
    }

    /** {@code ror.w #1}: the low word rotated right by one bit. */
    static int rorw(int v) {
        int w = v & 0xffff;
        return (v & 0xffff0000) | (((w >>> 1) | (w << 15)) & 0xffff);
    }

    /** {@code move.l v,-(sp)}. */
    static void push(M68k c, int v) {
        c.a[7] -= 4;
        c.mem.write32(c.a[7], v);
    }

    /** {@code move.l (sp)+,...}. */
    static int pop(M68k c) {
        int v = c.mem.read32(c.a[7]);
        c.a[7] += 4;
        return v;
    }

    /**
     * One step of the generator, inlined in many routines: move.l (a0)+,d0 / move.l (a0),d1 /
     * eor.w d0,d1 / ror.w #1,d1 / eor.w d1,d0 / swap d0 / move.l d0,(a0) / move.l d1,-(a0).
     * Returns d0; d1 is the new long at $10894.
     */
    static int random(GameMemory m) {
        int d0 = m.read32(RND), d1 = m.read32(RND + 4);
        d1 = rorw(sw(d1, d1 ^ d0));
        d0 = swap(sw(d0, d0 ^ d1));
        m.write32(RND + 4, d0);
        m.write32(RND, d1);
        return d0;
    }
}
