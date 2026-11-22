package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

/**
 * Exact port of the object behaviour interpreters: engine 0 (0xE6EC, table 0xE39A), engine 4
 * (0xE4EA, table 0xE2F2, moves with map collisions) and engine 8 (0xEAAE, table 0xE442,
 * physics). a5 = object, script pointer +$10.
 * <p>
 * Entry (0xE4EE): +$20/+$24 = position; then opcodes run until one ends the frame (0xE504):
 * velocity +$28/+$2C = position - saved position, script pointer stored back. Dispatch goes
 * through the jump tables in memory like the original (each opcode = a "bra.w" to its handler),
 * and the handlers are identified by address.
 * <p>
 * Not yet ported (run on the interpreter as fragments until they come back to the dispatcher):
 * the collision moves 0xE522 / 0xE53E (engine 4) and the physics step 0xEAB6 (engine 8).
 */
final class BehaviourPort {

    private static final int DISPATCH = 0xe4fe, END = 0xe504;

    private BehaviourPort() {
    }

    static void engine0(M68k c) {
        run(c, 0xe39a);
    }

    static void engine4(M68k c) {
        run(c, 0xe2f2);
    }

    static void engine8(M68k c) {
        run(c, 0xe442);
    }

    /** 0xF00C: big objects' interpreter (table 0xEF64), called by 0xF04E. */
    static void engineBig(M68k c) {
        run(c, 0xef64);
    }

    private static void run(M68k c, int table) {
        GameMemory m = c.mem;
        int o = c.a[5];
        m.write32(o + 0x20, m.read32(o + 0x02));
        m.write32(o + 0x24, m.read32(o + 0x06));
        int a4 = m.read32(o + 0x10);
        for (int guard = 0; guard < 10000; guard++) {
            int op = (short) m.read16(a4);
            a4 += 2;
            int entry = table + op;
            if (m.read16(entry) != 0x6000) {
                throw new IllegalStateException(String.format("behaviour opcode $%X at $%X", op, a4 - 2));
            }
            int handler = entry + 2 + (short) m.read16(entry + 2);
            int next = handle(c, m, o, table, handler, a4);
            if (next == Integer.MIN_VALUE) {
                return; // frame ended (stored by endFrame)
            }
            a4 = next;
        }
        throw new IllegalStateException(String.format("behaviour script of $%X does not yield", o));
    }

    /** 0xE504. */
    private static int endFrame(GameMemory m, int o, int a4) {
        m.write32(o + 0x28, m.read32(o + 0x02) - m.read32(o + 0x20));
        m.write32(o + 0x2c, m.read32(o + 0x06) - m.read32(o + 0x24));
        m.write32(o + 0x10, a4);
        return Integer.MIN_VALUE;
    }

    private static int branch(GameMemory m, int o, int a4, boolean jump) {
        if (jump) {
            m.write16(o + 0x4e, 0);
            return a4 + (short) m.read16(a4);
        }
        return a4 + 2;
    }

    /** One opcode; returns the next script pointer, or MIN_VALUE when the frame ended. */
    private static int handle(M68k c, GameMemory m, int o, int table, int h, int a4) {
        switch (h) {
            case 0xe6f4 -> { // destroy (0xDC90), end of frame
                c.a[5] = o;
                Calls.call(c, 0xdc90);
                c.a[5] = o;
                return endFrame(m, o, a4);
            }
            case 0xf014 -> { // big objects: jump, flag $DC8E, end of frame
                a4 += (short) m.read16(a4);
                m.write16(o + 0x4e, 0);
                m.write16(o + 0x4c, 0);
                m.write8(0xdc8e, 0xff);
                return endFrame(m, o, a4);
            }
            case 0xf028 -> { // big objects: move n, dx, dy (words)
                int n = m.read16(a4), dx = m.read16(a4 + 2), dy = m.read16(a4 + 4);
                a4 += 6;
                m.write16(o + 0x02, m.read16(o + 0x02) + dx);
                m.write16(o + 0x06, m.read16(o + 0x06) + dy);
                return count(m, o, a4, n, 8);
            }
            case 0xe6fc -> { // +$44 = word of data
                int d0 = (short) m.read16(a4);
                a4 += 2;
                m.write16(o + 0x44, m.read16(a4 - 2 + d0));
                return a4;
            }
            case 0xe708 -> { // move n, dx, dy (words)
                int n = m.read16(a4), dx = m.read16(a4 + 2), dy = m.read16(a4 + 4);
                a4 += 6;
                m.write16(o + 0x02, m.read16(o + 0x02) + dx);
                m.write16(o + 0x06, m.read16(o + 0x06) + dy);
                return count(m, o, a4, n, 8);
            }
            case 0xe72e -> { // loop n
                int n = m.read16(a4);
                a4 += 2;
                int t = (m.read16(o + 0x4c) + 1) & 0xffff;
                m.write16(o + 0x4c, t);
                if (n == t) {
                    m.write16(o + 0x4c, 0);
                    return a4 + 2;
                }
                return a4 + (short) m.read16(a4);
            }
            case 0xe74a -> { // jump
                m.write16(o + 0x4e, 0);
                return a4 + (short) m.read16(a4);
            }
            case 0xe754 -> { // native routine
                int d0 = (short) m.read16(a4);
                a4 += 2;
                c.d[0] = (c.d[0] & 0xffff0000) | (d0 & 0xffff);
                c.d[1] = (c.d[1] & 0xffff0000) | (op(m, a4 - 4) & 0xffff);
                c.a[3] = table;
                c.a[4] = a4;
                c.a[5] = o;
                Calls.call(c, a4 - 2 + d0);
                c.a[5] = o;
                return c.a[4];
            }
            case 0xe75e -> { // call
                m.write32(o + 0x50, a4 + 2);
                return a4 + (short) m.read16(a4);
            }
            case 0xe76c -> { // return
                return m.read32(o + 0x50);
            }
            case 0xe774 -> { // sound
                c.d[0] = (c.d[0] & 0xffff0000) | m.read16(a4);
                c.d[1] = (c.d[1] & 0xffff0000) | m.read16(a4 + 2);
                a4 += 4;
                c.a[4] = a4;
                c.a[5] = o;
                Calls.call(c, 0x7bc8);
                c.a[5] = o;
                return a4;
            }
            case 0xe780 -> { // sound (3 parameters)
                c.d[0] = (short) m.read16(a4);
                c.d[1] = (short) m.read16(a4 + 2);
                c.d[2] = (short) m.read16(a4 + 4);
                a4 += 6;
                c.a[4] = a4;
                c.a[5] = o;
                Calls.call(c, 0x7bca);
                c.a[5] = o;
                return a4;
            }
            case 0xe78c -> { // poke a word into the script
                int d0 = (short) m.read16(a4);
                int v = m.read16(a4 + 2);
                a4 += 4;
                m.write16(a4 - 4 + d0, v);
                return a4;
            }
            case 0xe796 -> { // set animation script
                m.write32(o + 0x0c, a4 + (short) m.read16(a4));
                m.write16(o + 0x34, 0);
                m.write16(o + 0x32, 0);
                return a4 + 2;
            }
            case 0xe7aa -> { // object word = v
                int d1 = (short) m.read16(a4);
                m.write16(o + d1, m.read16(a4 + 2));
                return a4 + 4;
            }
            case 0xe7b4, 0xea34, 0xea50 -> { // create an object
                int type = m.read16(a4);
                a4 += 2;
                c.a[3] = table;
                AnimPort.spawn(c, m, o, h == 0xe7b4 ? 0x34 : h == 0xea34 ? 0x98 : 0x9c, type, a4);
                return a4;
            }
            case 0xe7c6 -> { // effect 0xFB58
                c.d[0] = (c.d[0] & 0xffff0000) | m.read16(a4);
                a4 += 2;
                c.a[4] = a4;
                c.a[5] = o;
                Calls.call(c, 0xfb58);
                c.a[5] = o;
                return a4;
            }
            case 0xe7d0, 0xe7ea, 0xe804, 0xe81e -> { // compare object word, branch
                int d0 = (short) m.read16(a4);
                int d1 = (short) m.read16(a4 + 2);
                a4 += 4;
                int v = (short) m.read16(o + d0);
                boolean j = switch (h) {
                    case 0xe7d0 -> d1 == v;
                    case 0xe7ea -> d1 != v;
                    case 0xe804 -> d1 <= v;
                    default -> d1 >= v;
                };
                return branch(m, o, a4, j);
            }
            case 0xe838 -> { // move n, dx.l, dy.l
                int n = m.read16(a4);
                int dx = m.read32(a4 + 2), dy = m.read32(a4 + 6);
                a4 += 10;
                m.write32(o + 0x02, m.read32(o + 0x02) + dx);
                m.write32(o + 0x06, m.read32(o + 0x06) + dy);
                return count(m, o, a4, n, 12);
            }
            case 0xe860 -> { // velocity
                m.write32(o + 0x28, m.read32(a4));
                m.write32(o + 0x2c, m.read32(a4 + 4));
                return a4 + 8;
            }
            case 0xe86c -> { // velocity limits
                int x = m.read32(a4), y = m.read32(a4 + 4);
                m.write32(o + 0x90, x);
                m.write32(o + 0x8c, -x);
                m.write32(o + 0x98, y);
                m.write32(o + 0x94, -y);
                return a4 + 8;
            }
            case 0xe888, 0xe8fa -> { // accelerate (+ move)
                int n = m.read16(a4);
                int vx = clamp(m, o, ((short) m.read16(a4 + 2) << 8) + m.read32(o + 0x28), 0x8c, 0x90);
                int vy = clamp(m, o, ((short) m.read16(a4 + 4) << 8) + m.read32(o + 0x2c), 0x94, 0x98);
                a4 += 6;
                int t = (m.read16(o + 0x4e) + 1) & 0xffff;
                m.write16(o + 0x4e, t);
                if (h == 0xe8fa) {
                    m.write32(o + 0x28, vx);
                    m.write32(o + 0x2c, vy);
                    m.write16(o + 0x4e, 0);
                    return a4;
                }
                m.write32(o + 0x02, m.read32(o + 0x02) + vx);
                m.write32(o + 0x06, m.read32(o + 0x06) + vy);
                if (n == t) {
                    m.write16(o + 0x4e, 0);
                    return endFrame(m, o, a4);
                }
                return endFrame(m, o, a4 - 8);
            }
            case 0xe90a, 0xe926, 0xe942, 0xe95e -> { // compare object byte, branch
                int d0 = m.read8(a4);
                int d1 = (byte) m.read8(a4 + 1);
                a4 += 2;
                int v = (byte) m.read8(o + d0);
                boolean j = switch (h) {
                    case 0xe90a -> d1 == v;
                    case 0xe926 -> d1 != v;
                    case 0xe942 -> d1 <= v;
                    default -> d1 >= v;
                };
                return branch(m, o, a4, j);
            }
            case 0xe97a -> { // poke a byte into the script
                int d1 = (short) m.read16(a4);
                int d0 = m.read16(a4 + 2);
                a4 += 4;
                m.write8(a4 - 4 + d1, d0);
                return a4;
            }
            case 0xe986 -> { // +$44 = byte of data
                int d0 = (short) m.read16(a4);
                a4 += 2;
                m.write8(o + 0x44, m.read8(a4 - 2 + d0));
                return a4;
            }
            case 0xe992 -> { // object byte = v
                m.write8(o + m.read8(a4), m.read8(a4 + 1));
                return a4 + 2;
            }
            case 0xe99e -> { // move by velocity n frames
                int n = m.read16(a4);
                a4 += 2;
                m.write32(o + 0x02, m.read32(o + 0x02) + m.read32(o + 0x28));
                m.write32(o + 0x06, m.read32(o + 0x06) + m.read32(o + 0x2c));
                return count(m, o, a4, n, 4);
            }
            case 0xe9c6 -> {
                m.write32(o + 0x28, m.read32(a4));
                return a4 + 4;
            }
            case 0xe9ce -> {
                m.write32(o + 0x2c, m.read32(a4));
                return a4 + 4;
            }
            case 0xe9d6 -> { // stop the animation
                m.write32(o + 0x0c, 0);
                return a4;
            }
            case 0xe9de -> { // end of the behaviour
                return endFrame(m, o, 0);
            }
            case 0xe9e4 -> { // water level / current ($10A54, $10A58)
                m.write16(0x10a54, m.read16(a4));
                int v = (m.read8(a4 + 2) << 16) | (m.read8(a4 + 3) << 8);
                m.write32(0x10a58, v);
                return a4 + 4;
            }
            case 0xe9fe -> { // move n, dx, dy in 8.8 bytes
                int n = m.read16(a4);
                int dx = ((byte) m.read8(a4 + 2) << 16) | (m.read8(a4 + 3) << 8);
                int dy = ((byte) m.read8(a4 + 4) << 16) | (m.read8(a4 + 5) << 8);
                a4 += 6;
                m.write32(o + 0x02, m.read32(o + 0x02) + dx);
                m.write32(o + 0x06, m.read32(o + 0x06) + dy);
                return count(m, o, a4, n, 8);
            }
            case 0xea84 -> { // move n, dx, dy (bytes)
                int n = m.read16(a4);
                int dx = (byte) m.read8(a4 + 2), dy = (byte) m.read8(a4 + 3);
                a4 += 4;
                m.write16(o + 0x02, m.read16(o + 0x02) + dx);
                m.write16(o + 0x06, m.read16(o + 0x06) + dy);
                return count(m, o, a4, n, 6);
            }
            case 0xeab6 -> { // engine 8 (and engine 0 $AC): physics step
                c.a[3] = table;
                c.a[5] = o;
                int next = ObjectPhysics.step(c, o, a4);
                return endFrame(m, o, next);
            }
            case 0xe53e -> { // engine 4: move n, vx, vy (words) with map collisions
                return collisionMove(c, m, o, a4, a4, false);
            }
            case 0xe522 -> { // engine 4: same with vx, vy in bytes (expanded to $17CD8)
                m.write16(0x17cd8, m.read16(a4));
                m.write16(0x17cda, (byte) m.read8(a4 + 2));
                m.write16(0x17cdc, (byte) m.read8(a4 + 3));
                return collisionMove(c, m, o, a4, 0x17cd8, true);
            }
            default -> { // not ported yet: original fragment until it is back at the dispatcher
                c.a[3] = table;
                c.a[4] = a4;
                c.a[5] = o;
                c.d[1] = (c.d[1] & 0xffff0000) | (op(m, a4 - 2) & 0xffff);
                int sp = c.a[7];
                int stop = c.runFragment(h, new int[]{DISPATCH, END}, sp, 5_000_000);
                if (stop < 0) {
                    // the fragment ended the frame itself with RTS (its saved registers popped):
                    // leave the return address in place for the hook epilogue
                    c.a[7] = sp;
                    return Integer.MIN_VALUE;
                }
                c.a[5] = o;
                if (stop == END) {
                    return endFrame(m, o, c.a[4]);
                }
                return c.a[4];
            }
        }
    }

    /**
     * 0xE53E: operands (at {@code ops}): n (negative = keep the current velocity), vx, vy. The
     * object moves by its velocity, vertically then horizontally, each axis tested with 0xF222:
     * landing on a floor / ladder top sets +$64 and +$9F = 1, a ceiling +$9F = $FF, a wall +$9E =
     * $FF / 1 (the position is snapped to the block edge and that velocity cleared). Ends the
     * frame itself (0xE51C: script pointer stored, velocity kept).
     */
    private static int collisionMove(M68k c, GameMemory m, int o, int a4, int ops, boolean bytes) {
        m.write8(o + 0x64, 0);
        m.write16(o + 0x9e, 0);
        boolean setVel = (short) m.read16(ops) >= 0;
        if (setVel) {
            m.write32(o + 0x2c, m.read16(ops + 4) << 16);
        }
        // vertical
        int y = m.read32(o + 0x06) + m.read32(o + 0x2c);
        int yi = (short) (y >> 16);
        int f = probe(c, (short) m.read16(o + 0x02), yi);
        int dy = (short) m.read16(o + 0x16), h = (short) m.read16(o + 0x1a);
        boolean land = false, stopped = false;
        if ((f & 2) != 0 && (short) m.read16(o + 0x2c) >= 0
                && (short) ((yi + dy + h - 1) & 15) <= (short) m.read16(o + 0x2c)) {
            land = true;
        } else if ((f & 0x18) != 0 || (f & 0x21) != 0) {
            if ((short) m.read16(o + 0x2c) < 0) {
                yi = (short) (((yi + dy + 16) & 0xfff0) - dy);
                m.write8(o + 0x9f, 0xff);
                stopped = true;
            } else {
                land = true;
            }
        }
        if (land) {
            yi = (short) ((((yi + dy + h - 16) | 15) - dy - h) + 1);
            m.write8(o + 0x64, 0xff);
            m.write8(o + 0x9f, 1);
            stopped = true;
        }
        if (stopped) {
            m.write32(o + 0x2c, 0);
            m.write32(o + 0x06, (yi & 0xffff) << 16);
        } else {
            m.write32(o + 0x06, y);
        }
        // horizontal
        if (setVel) {
            m.write32(o + 0x28, m.read16(ops + 2) << 16);
        }
        int x = m.read32(o + 0x02) + m.read32(o + 0x28) + m.read32(o + 0x40) + m.read32(o + 0x3c);
        int xi = (short) (x >> 16);
        m.write8(o + 0x6b, 0);
        m.write8(o + 0x6a, 0);
        m.write32(o + 0x48, 0);
        m.write32(o + 0x40, 0);
        m.write32(o + 0x3c, 0);
        f = probe(c, xi, (short) m.read16(o + 0x06));
        if ((f & 0x18) != 0 || (f & 0x21) != 0) {
            int dx = (short) m.read16(o + 0x14), w = (short) m.read16(o + 0x18);
            if ((short) m.read16(o + 0x28) < 0) {
                xi = (short) (((xi + dx + 16) & 0xfff0) - dx);
                m.write8(o + 0x9e, 0xff);
            } else {
                xi = (short) ((((xi + dx + w - 16) | 15) - dx - w) + 1);
                m.write8(o + 0x9e, 1);
            }
            m.write32(o + 0x28, 0);
            m.write32(o + 0x02, (xi & 0xffff) << 16);
        } else {
            m.write32(o + 0x02, x);
        }
        // count (0xE6B4)
        int t = (m.read16(o + 0x4e) + 1) & 0xffff;
        m.write16(o + 0x4e, t);
        int n = (short) m.read16(a4);
        int next = a4 + 2;
        if (n < 0) {
            if (-n == t) {
                m.write16(o + 0x4e, 0);
            } else {
                next -= 4;
            }
        } else if (n == t) {
            m.write16(o + 0x4e, 0);
            next += bytes ? 2 : 4;
        } else {
            next -= 4;
        }
        m.write32(o + 0x10, next);
        return Integer.MIN_VALUE;
    }

    /** 0xF222 at (x, y) for the object in a5: attribute bits. */
    private static int probe(M68k c, int x, int y) {
        c.d[0] = x;
        c.d[1] = y;
        MapCollision.boxAttributes(c);
        return c.d[0] & 0xffff;
    }

    /** Counted move: +$4E++, frame ends; at the count it is reset, else the opcode repeats. */
    private static int count(GameMemory m, int o, int a4, int n, int size) {
        int t = (m.read16(o + 0x4e) + 1) & 0xffff;
        m.write16(o + 0x4e, t);
        if (n == t) {
            m.write16(o + 0x4e, 0);
            return endFrame(m, o, a4);
        }
        return endFrame(m, o, a4 - size);
    }

    private static int clamp(GameMemory m, int o, int v, int min, int max) {
        if (v >= 0) {
            int hi = m.read32(o + max);
            return v > hi ? hi : v;
        }
        int lo = m.read32(o + min);
        return v < lo ? lo : v;
    }

    private static int op(GameMemory m, int a) {
        return m.read16(a);
    }
}
