package bubba.entity;

import bubba.cpu.GameMemory;

/**
 * Interpreter for the object animation scripts (set*.bin), port of the original routine at code
 * 0xDF38 (jump table 0xDF4C, one opcode every 4). The script pointer is the long at obj+$0C
 * (0 = no animation), addresses are absolute in the game memory. Branch offsets are relative to
 * the address of the offset word.
 */
public final class AnimVM {

    private AnimVM() {
    }

    /** Run the script of {@code o} until it yields for this frame. */
    public static void step(LevelObject o, GameMemory m, NativeCalls natives) {
        int pc = o.getLong(0x0c);
        int guard = 0;
        while (pc != 0 && guard++ < 64) {
            int code = m.read16(pc);
            int a = pc + 2; // first operand
            switch (code) {
                case 0x04 -> { // show sprite (word) for n frames
                    int n = m.read16(a);
                    o.setWord(0x0a, m.read16(a + 2));
                    if (counter(o) == n) {
                        o.setWord(0x34, 0);
                        pc = a + 4;
                    }
                    o.setLong(0x0c, pc);
                    return;
                }
                case 0x8c -> { // show frame (byte * 2) for n frames
                    int n = m.read8(a);
                    o.setWord(0x0a, m.read8(a + 1) * 2);
                    if (counter(o) == n) {
                        o.setWord(0x34, 0);
                        pc = a + 2;
                    }
                    o.setLong(0x0c, pc);
                    return;
                }
                case 0x68 -> { // wait n frames
                    int n = m.read16(a);
                    if (counter(o) == n) {
                        o.setWord(0x34, 0);
                        pc = a + 2;
                    }
                    o.setLong(0x0c, pc);
                    return;
                }
                case 0x6c, 0x7c -> { // end of frame (0x7C restores a saved state: not emulated)
                    o.setLong(0x0c, a);
                    return;
                }
                case 0x40 -> { // register in the list at $17782 (0xDC90) and end the frame
                    natives.callCode(0xdc90, o, 0);
                    o.setLong(0x0c, a);
                    return;
                }
                case 0x74, 0x00 -> { // end of script
                    o.setLong(0x0c, 0);
                    return;
                }
                case 0x08 -> {
                    pc = a + sw(m, a);
                    o.setWord(0x34, 0);
                }
                case 0x10 -> { // call
                    o.setLong(0x36, a + 2);
                    pc = a + sw(m, a);
                }
                case 0x14 -> pc = o.getLong(0x36); // return
                case 0x1c -> { // set behaviour script
                    o.setLong(0x10, a + sw(m, a));
                    o.setWord(0x4e, 0);
                    o.setWord(0x4c, 0);
                    pc = a + 2;
                }
                case 0x20 -> { // store word into the script data
                    m.write16(a + sw(m, a), m.read16(a + 2));
                    pc = a + 4;
                }
                case 0x24, 0x28, 0x2c, 0x30 -> { // compare object word, branch
                    int mem = (short) o.word(m.read16(a));
                    int val = sw(m, a + 2);
                    pc = branch(o, m, a + 4, test(code - 0x24, val, mem));
                }
                case 0x4c, 0x50, 0x54, 0x58 -> { // compare object byte, branch
                    int mem = (byte) o.getByte(m.read8(a));
                    int val = (byte) m.read8(a + 1);
                    pc = branch(o, m, a + 2, test(code - 0x4c, val, mem));
                }
                case 0x48 -> { // loop n times on +$32
                    int c = o.word(0x32) + 1;
                    o.setWord(0x32, c);
                    if (c == m.read16(a)) {
                        o.setWord(0x32, 0);
                        pc = a + 4;
                    } else {
                        pc = a + 2 + sw(m, a + 2);
                    }
                }
                case 0x3c -> { // object word = value
                    o.setWord(m.read16(a), m.read16(a + 2));
                    pc = a + 4;
                }
                case 0x64 -> { // object byte = value
                    o.setByte(m.read8(a), m.read8(a + 1));
                    pc = a + 2;
                }
                case 0x44 -> { // object $44 = word from script data
                    o.setWord(0x44, m.read16(a + sw(m, a)));
                    pc = a + 2;
                }
                case 0x60 -> { // object $44 = byte from script data
                    o.setByte(0x44, m.read8(a + sw(m, a)));
                    pc = a + 2;
                }
                case 0x5c -> { // store byte into the script data
                    m.write8(a + sw(m, a), m.read16(a + 2));
                    pc = a + 4;
                }
                case 0x78 -> { // sprite base
                    o.setWord(0xa0, m.read16(a));
                    pc = a + 2;
                }
                case 0x70 -> { // stop behaviour
                    o.setLong(0x10, 0);
                    pc = a;
                }
                case 0x0c -> pc = natives.call(a + sw(m, a), o, a + 2); // native 68k routine
                case 0x38 -> { // game routine 0xFB58 with d0 = operand
                    natives.callCode(0xfb58, o, m.read16(a));
                    pc = a + 2;
                }
                case 0x80 -> { // leave the list $1767A (0xE214)
                    if ((o.getByte(0x83) & 1) != 0) {
                        o.setByte(0x83, o.getByte(0x83) & ~1);
                        removeSwap(m, 0x1767a, o.base);
                    }
                    pc = a;
                }
                case 0x90 -> { // join the list $1767A (0xE2D6)
                    if ((o.getByte(0x83) & 1) == 0) {
                        o.setByte(0x83, o.getByte(0x83) | 1);
                        int n = m.read16(0x1767a);
                        m.write32(0x1767c + 4 * n, o.base);
                        m.write16(0x1767a, n + 1);
                    }
                    pc = a;
                }
                case 0x34, 0x84, 0x88 -> { // create an object (0xE080 / 0xE236 / 0xE252)
                    spawn(o, m, natives, code, m.read16(a));
                    pc = a + 2;
                }
                case 0x18 -> pc = a + 4;                   // sound
                case 0x94 -> pc = a + 6;                   // sound
                default -> {
                    o.setLong(0x0c, 0); // unknown opcode: stop rather than run garbage
                    return;
                }
            }
        }
        o.setLong(0x0c, pc);
    }

    /** kind 0 eq, 4 ne, 8 le (val <= mem), 12 ge (val >= mem): "cmp mem,val; bxx". */
    static boolean test(int kind, int val, int mem) {
        return switch (kind) {
            case 0 -> val == mem;
            case 4 -> val != mem;
            case 8 -> val <= mem;
            default -> val >= mem;
        };
    }

    private static int branch(LevelObject o, GameMemory m, int offAddr, boolean take) {
        if (take) {
            o.setWord(0x34, 0);
            return offAddr + sw(m, offAddr);
        }
        return offAddr + 2;
    }

    private static int counter(LevelObject o) {
        int c = o.word(0x34) + 1;
        o.setWord(0x34, c);
        return c;
    }

    static int sw(GameMemory m, int addr) {
        return (short) m.read16(addr);
    }

    /** List removal by moving the last entry into the hole. */
    static void removeSwap(GameMemory m, int list, int o) {
        int n = m.read16(list) - 1;
        m.write16(list, n);
        int last = m.read32(list + 2 + 4 * n);
        for (int i = 0; i <= n; i++) {
            if (m.read32(list + 2 + 4 * i) == o) {
                m.write32(list + 2 + 4 * i, last);
                return;
            }
        }
    }

    /**
     * Spawn opcodes shared by the animation ($34/$84/$88) and behaviour ($34/$98/$9C) scripts:
     * plain child; child sharing +$44 with its parent; or "successor" which waits until the
     * parent's record is free (code-resident behaviour $E286 calling $E292), keeping the parent's
     * script for later in +$50/+$36.
     */
    static void spawn(LevelObject o, GameMemory m, NativeCalls natives, int kind, int index) {
        int c = natives.spawn(o, index);
        if (c < 0) {
            return;
        }
        switch (kind) {
            case 0x84, 0x98 -> m.write32(c + 0x44, o.getLong(0x44));
            case 0x88, 0x9c -> {
                m.write32(c + 0x54, o.getLong(0x54));
                m.write32(c + 0x50, m.read32(c + 0x10));
                m.write32(c + 0x36, m.read32(c + 0x0c));
                m.write32(c + 0x0c, 0);
                m.write32(c + 0x10, 0xe286);
            }
            default -> { }
        }
    }
}
