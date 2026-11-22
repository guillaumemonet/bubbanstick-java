package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

/**
 * Exact port of the object animation interpreter 0xDF38 (jump table 0xDF4C), a5 = object.
 * The script pointer is +$0C (0 = none); the routine runs opcodes until one yields, then stores
 * the pointer back (0xDF46). Calls to other routines of the game (sound 0x7BC8/0x7BCA, spawn
 * 0x107B2, effect 0xFB58, destruction 0xDC90, native code of the scripts) go through
 * {@link Calls#call}, i.e. their Java port when there is one.
 */
final class AnimPort {

    private AnimPort() {
    }

    static void run(M68k c) {
        GameMemory m = c.mem;
        int o = c.a[5];
        int a4 = m.read32(o + 0x0c);
        if (a4 == 0) {
            return;
        }
        for (int guard = 0; guard < 10000; guard++) {
            int op = m.read16(a4);
            a4 += 2;
            switch (op) {
                case 0x04 -> { // show sprite n frames
                    int n = m.read16(a4);
                    m.write16(o + 0x0a, m.read16(a4 + 2));
                    a4 += 4;
                    int t = (m.read16(o + 0x34) + 1) & 0xffff;
                    m.write16(o + 0x34, t);
                    if (n != t) {
                        a4 -= 6;
                    } else {
                        m.write16(o + 0x34, 0);
                    }
                    endFrame(m, o, a4);
                    return;
                }
                case 0x08 -> { // jump
                    a4 += (short) m.read16(a4);
                    m.write16(o + 0x34, 0);
                }
                case 0x0c -> { // native 68k routine: jsr -2(a4,d0) with a4 after the operand
                    int d0 = (short) m.read16(a4);
                    a4 += 2;
                    c.d[0] = (c.d[0] & 0xffff0000) | (d0 & 0xffff);
                    c.a[4] = a4;
                    c.a[5] = o;
                    Calls.call(c, a4 - 2 + d0);
                    a4 = c.a[4];
                    c.a[5] = o;
                }
                case 0x10 -> { // call
                    m.write32(o + 0x36, a4 + 2);
                    a4 += (short) m.read16(a4);
                }
                case 0x14 -> a4 = m.read32(o + 0x36); // return
                case 0x18 -> { // sound d0, d1
                    c.d[0] = (c.d[0] & 0xffff0000) | m.read16(a4);
                    c.d[1] = (c.d[1] & 0xffff0000) | m.read16(a4 + 2);
                    a4 += 4;
                    c.a[4] = a4;
                    c.a[5] = o;
                    Calls.call(c, 0x7bc8);
                    c.a[5] = o;
                }
                case 0x1c -> { // set behaviour script
                    m.write32(o + 0x10, a4 + (short) m.read16(a4));
                    a4 += 2;
                    m.write16(o + 0x4e, 0);
                    m.write16(o + 0x4c, 0);
                }
                case 0x20 -> { // poke a word into the script
                    int d0 = (short) m.read16(a4);
                    int v = m.read16(a4 + 2);
                    a4 += 4;
                    m.write16(a4 - 4 + d0, v);
                }
                case 0x24, 0x28, 0x2c, 0x30 -> { // compare object word, branch
                    int d0 = (short) m.read16(a4);
                    int d1 = (short) m.read16(a4 + 2);
                    a4 += 4;
                    int v = (short) m.read16(o + d0);
                    boolean jump = switch (op) {
                        case 0x24 -> d1 == v;
                        case 0x28 -> d1 != v;
                        case 0x2c -> d1 <= v;
                        default -> d1 >= v;
                    };
                    if (jump) {
                        a4 += (short) m.read16(a4);
                        m.write16(o + 0x34, 0);
                    } else {
                        a4 += 2;
                    }
                }
                case 0x34, 0x84, 0x88 -> { // create an object (0xE080 / 0xE236 / 0xE252)
                    int d0 = m.read16(a4);
                    a4 += 2;
                    spawn(c, m, o, op, d0, a4);
                }
                case 0x38 -> { // effect 0xFB58
                    c.d[0] = (c.d[0] & 0xffff0000) | m.read16(a4);
                    a4 += 2;
                    c.a[4] = a4;
                    c.a[5] = o;
                    Calls.call(c, 0xfb58);
                    c.a[5] = o;
                }
                case 0x3c -> { // object word = v
                    int d1 = (short) m.read16(a4);
                    m.write16(o + d1, m.read16(a4 + 2));
                    a4 += 4;
                }
                case 0x40 -> { // destroy (0xDC90), end of frame
                    c.a[5] = o;
                    Calls.call(c, 0xdc90);
                    c.a[5] = o;
                    endFrame(m, o, a4);
                    return;
                }
                case 0x44 -> { // +$44 = word of data
                    int d0 = (short) m.read16(a4);
                    a4 += 2;
                    m.write16(o + 0x44, m.read16(a4 - 2 + d0));
                }
                case 0x48 -> { // loop n times
                    int n = m.read16(a4);
                    a4 += 2;
                    int t = (m.read16(o + 0x32) + 1) & 0xffff;
                    m.write16(o + 0x32, t);
                    if (n == t) {
                        a4 += 2;
                        m.write16(o + 0x32, 0);
                    } else {
                        a4 += (short) m.read16(a4);
                    }
                }
                case 0x4c, 0x50, 0x54, 0x58 -> { // compare object byte, branch
                    int d0 = m.read8(a4);
                    int d1 = (byte) m.read8(a4 + 1);
                    a4 += 2;
                    int v = (byte) m.read8(o + d0);
                    boolean jump = switch (op) {
                        case 0x4c -> d1 == v;
                        case 0x50 -> d1 != v;
                        case 0x54 -> d1 <= v;
                        default -> d1 >= v;
                    };
                    if (jump) {
                        a4 += (short) m.read16(a4);
                        m.write16(o + 0x34, 0);
                    } else {
                        a4 += 2;
                    }
                }
                case 0x5c -> { // poke a byte into the script
                    int d1 = (short) m.read16(a4);
                    int d0 = m.read16(a4 + 2);
                    a4 += 4;
                    m.write8(a4 - 4 + d1, d0);
                }
                case 0x60 -> { // +$44 = byte of data
                    int d0 = (short) m.read16(a4);
                    a4 += 2;
                    m.write8(o + 0x44, m.read8(a4 - 2 + d0));
                }
                case 0x64 -> { // object byte = v
                    m.write8(o + m.read8(a4), m.read8(a4 + 1));
                    a4 += 2;
                }
                case 0x68 -> { // wait n frames
                    int n = m.read16(a4);
                    a4 += 2;
                    int t = (m.read16(o + 0x34) + 1) & 0xffff;
                    m.write16(o + 0x34, t);
                    if (n == t) {
                        m.write16(o + 0x34, 0);
                    } else {
                        a4 -= 4;
                    }
                    endFrame(m, o, a4);
                    return;
                }
                case 0x6c -> { // end of frame
                    endFrame(m, o, a4);
                    return;
                }
                case 0x70 -> m.write32(o + 0x10, 0); // stop the behaviour
                case 0x74 -> { // end of the animation
                    endFrame(m, o, 0);
                    return;
                }
                case 0x78 -> { // sprite base
                    m.write16(o + 0xa0, m.read16(a4));
                    a4 += 2;
                }
                case 0x7c -> { // restore the scripts saved when hit (0xF8FE)
                    m.write32(o + 0x10, m.read32(o + 0xa2));
                    m.write32(o + 0x4c, m.read32(o + 0xa6));
                    m.write32(o + 0x50, m.read32(o + 0xaa));
                    a4 = m.read32(o + 0xae);
                    m.write32(o + 0x32, m.read32(o + 0xb2));
                    m.write32(o + 0x36, m.read32(o + 0xb6));
                    m.write16(o + 0x82, m.read16(o + 0x84));
                    endFrame(m, o, a4);
                    return;
                }
                case 0x80 -> { // leave the list $1767A
                    int b = m.read8(o + 0x83);
                    m.write8(o + 0x83, b & ~1);
                    if ((b & 1) != 0) {
                        removeSwap(m, 0x1767a, o);
                    }
                }
                case 0x8c -> { // show sprite f*2 for n frames
                    int n = m.read8(a4);
                    int f = m.read8(a4 + 1);
                    a4 += 2;
                    m.write16(o + 0x0a, f * 2);
                    int t = (m.read16(o + 0x34) + 1) & 0xffff;
                    m.write16(o + 0x34, t);
                    if (n == t) {
                        m.write16(o + 0x34, 0);
                    } else {
                        a4 -= 4;
                    }
                    endFrame(m, o, a4);
                    return;
                }
                case 0x90 -> { // join the list $1767A
                    int b = m.read8(o + 0x83);
                    m.write8(o + 0x83, b | 1);
                    if ((b & 1) == 0) {
                        int n = m.read16(0x1767a);
                        m.write16(0x1767a, n + 1);
                        m.write32(0x1767c + 4 * n, o);
                    }
                }
                case 0x94 -> { // sound d0, d1, d2 (0x7BCA)
                    c.d[0] = (c.d[0] & 0xffff0000) | m.read16(a4);
                    c.d[1] = (c.d[1] & 0xffff0000) | m.read16(a4 + 2);
                    c.d[2] = (c.d[2] & 0xffff0000) | m.read16(a4 + 4);
                    a4 += 6;
                    c.a[4] = a4;
                    c.a[5] = o;
                    Calls.call(c, 0x7bca);
                    c.a[5] = o;
                }
                default -> throw new IllegalStateException(String.format(
                        "anim opcode $%X at $%X (object $%X)", op, a4 - 2, o));
            }
        }
        throw new IllegalStateException(String.format("anim script of $%X does not yield", o));
    }

    private static void endFrame(GameMemory m, int o, int a4) {
        m.write32(o + 0x0c, a4);
    }

    /** Spawn opcodes shared with the behaviour interpreter (0xE080 / 0xE236 / 0xE252 ...). */
    static void spawn(M68k c, GameMemory m, int o, int op, int type, int a4) {
        c.d[0] = (c.d[0] & 0xffff0000) | type;
        c.a[4] = a4;
        c.a[5] = o;
        Calls.call(c, 0x107b2);
        int child = c.a[5];
        boolean ok = c.z;
        c.a[5] = o;
        if (!ok) {
            return;
        }
        switch (op) {
            case 0x84, 0x98 -> m.write32(child + 0x44, m.read32(o + 0x44));
            case 0x88, 0x9c -> {
                m.write32(child + 0x54, m.read32(o + 0x54));
                m.write32(child + 0x50, m.read32(child + 0x10));
                m.write32(child + 0x36, m.read32(child + 0x0c));
                m.write32(child + 0x0c, 0);
                m.write32(child + 0x10, 0xe286);
            }
            default -> { }
        }
    }

    /** "subq.w #1,(a0); ... cmpa.l (a0)+,a5; bne; move.l d0,-(a0)": last entry into the hole. */
    static void removeSwap(GameMemory m, int list, int o) {
        int n = (m.read16(list) - 1) & 0xffff;
        m.write16(list, n);
        int last = m.read32(list + 2 + 4 * n);
        for (int i = 0; i <= n; i++) {
            if (m.read32(list + 2 + 4 * i) == o) {
                m.write32(list + 2 + 4 * i, last);
                return;
            }
        }
    }
}
