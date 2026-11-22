package bubba.entity;

import bubba.cpu.GameMemory;
import bubba.level.LevelData;

import static bubba.entity.AnimVM.sw;

/**
 * Object behaviour scripts (movement), port of the original interpreters:
 * engine 0 = code 0xE6EC (table 0xE39A), engine 1 = 0xE4EA (table 0xE2F2), identical except
 * ops $04/$A0 which move with map collision (0xE53E); engine 2 = table 0xE442, op $04 = physics
 * step (0xEAB6).
 * The script pointer is the long at obj+$10 (0 = none); native 68k routines ($0C) run on the
 * 68000 interpreter. Object spawning and sounds are not done yet.
 */
public final class BehaviourVM {

    private BehaviourVM() {
    }

    /** engine: obj+$1F = 0 (scripted moves), 4 (moves with collision), 8 (physics, op $04 = 0xEAB6). */
    public static void step(LevelObject o, GameMemory m, LevelData level, NativeCalls natives, int engine) {
        boolean collisionEngine = engine == 4;
        // entry (0xE4EE): remember the position, used to compute the velocity when yielding
        o.setLong(0x20, o.getLong(0x02));
        o.setLong(0x24, o.getLong(0x06));
        int pc = o.getLong(0x10);
        int guard = 0;
        while (pc != 0 && guard++ < 64) {
            int code = m.read16(pc);
            int a = pc + 2;
            switch (code) {
                case 0x04 -> {
                    if (engine == 8) { // physics step (n, gravity.l) - 0xEAB6
                        physicsStep(o, level, m.read32(a + 2));
                        o.setLong(0x10, count(o, sw(m, a), pc, a + 6));
                    } else {
                        move(o, m, level, pc, a, false, collisionEngine);
                    }
                    return;
                }
                case 0xa0 -> { // move, byte operands
                    move(o, m, level, pc, a, true, collisionEngine);
                    return;
                }
                case 0x4c -> { // move fixed point (n, dx.l, dy.l)
                    o.setLong(0x02, o.getLong(0x02) + m.read32(a + 2));
                    o.setLong(0x06, o.getLong(0x06) + m.read32(a + 6));
                    endFrame(o, count(o, sw(m, a), pc, a + 10));
                    return;
                }
                case 0x94 -> { // move fixed point, byte operands (n, xi, xf, yi, yf)
                    o.setLong(0x02, o.getLong(0x02) + (((byte) m.read8(a + 2)) << 16 | m.read8(a + 3) << 8));
                    o.setLong(0x06, o.getLong(0x06) + (((byte) m.read8(a + 4)) << 16 | m.read8(a + 5) << 8));
                    endFrame(o, count(o, sw(m, a), pc, a + 6));
                    return;
                }
                case 0x78 -> { // move by velocity n frames
                    o.setLong(0x02, o.getLong(0x02) + o.getLong(0x28));
                    o.setLong(0x06, o.getLong(0x06) + o.getLong(0x2c));
                    endFrame(o, count(o, sw(m, a), pc, a + 2));
                    return;
                }
                case 0x58 -> { // accelerate + move (n, ax, ay)
                    int[] v = accel(o, m, a);
                    o.setLong(0x02, o.getLong(0x02) + v[0]);
                    o.setLong(0x06, o.getLong(0x06) + v[1]);
                    endFrame(o, count(o, sw(m, a), pc, a + 6));
                    return;
                }
                case 0x90 -> { // accelerate only
                    int[] v = accel(o, m, a);
                    o.setLong(0x28, v[0]);
                    o.setLong(0x2c, v[1]);
                    o.setWord(0x4e, 0);
                    pc = a + 6;
                }
                case 0x50 -> {
                    o.setLong(0x28, m.read32(a));
                    o.setLong(0x2c, m.read32(a + 4));
                    pc = a + 8;
                }
                case 0x54 -> { // velocity limits
                    int vx = m.read32(a), vy = m.read32(a + 4);
                    o.setLong(0x90, vx);
                    o.setLong(0x8c, -vx);
                    o.setLong(0x98, vy);
                    o.setLong(0x94, -vy);
                    pc = a + 8;
                }
                case 0x7c -> {
                    o.setLong(0x28, m.read32(a));
                    pc = a + 4;
                }
                case 0x80 -> {
                    o.setLong(0x2c, m.read32(a));
                    pc = a + 4;
                }
                case 0x08 -> {
                    pc = a + sw(m, a);
                    o.setWord(0x4e, 0);
                }
                case 0x10 -> {
                    o.setLong(0x50, a + 2);
                    pc = a + sw(m, a);
                }
                case 0x14 -> pc = o.getLong(0x50);
                case 0x1c -> { // change animation script
                    o.setLong(0x0c, a + sw(m, a));
                    o.setWord(0x34, 0);
                    o.setWord(0x32, 0);
                    pc = a + 2;
                }
                case 0x84 -> {
                    o.setLong(0x0c, 0);
                    pc = a;
                }
                case 0x88 -> { // end
                    endFrame(o, 0);
                    return;
                }
                case 0x40 -> { // register in the list at $17782 (0xDC90) and yield
                    natives.callCode(0xdc90, o, 0);
                    endFrame(o, a);
                    return;
                }
                case 0xac -> { // native physics (0xEAB6): not emulated, yield
                    endFrame(o, a);
                    return;
                }
                case 0x20 -> {
                    m.write16(a + sw(m, a), m.read16(a + 2));
                    pc = a + 4;
                }
                case 0x6c -> {
                    m.write8(a + sw(m, a), m.read16(a + 2));
                    pc = a + 4;
                }
                case 0x24, 0x28, 0x2c, 0x30 -> {
                    int mem = (short) o.word(m.read16(a));
                    pc = branch(o, m, a + 4, AnimVM.test(code - 0x24, sw(m, a + 2), mem));
                }
                case 0x5c, 0x60, 0x64, 0x68 -> {
                    int mem = (byte) o.getByte(m.read8(a));
                    pc = branch(o, m, a + 2, AnimVM.test(code - 0x5c, (byte) m.read8(a + 1), mem));
                }
                case 0x48 -> { // loop on +$4C
                    int c = o.word(0x4c) + 1;
                    o.setWord(0x4c, c);
                    if (c == m.read16(a)) {
                        o.setWord(0x4c, 0);
                        pc = a + 4;
                    } else {
                        pc = a + 2 + sw(m, a + 2);
                    }
                }
                case 0x3c -> {
                    o.setWord(m.read16(a), m.read16(a + 2));
                    pc = a + 4;
                }
                case 0x74 -> {
                    o.setByte(m.read8(a), m.read8(a + 1));
                    pc = a + 2;
                }
                case 0x44 -> {
                    o.setWord(0x44, m.read16(a + sw(m, a)));
                    pc = a + 2;
                }
                case 0x70 -> {
                    o.setByte(0x44, m.read8(a + sw(m, a)));
                    pc = a + 2;
                }
                case 0x8c -> { // scroll / water parameters (0xE9E4)
                    m.write16(0x10a54, m.read16(a));
                    m.write32(0x10a58, (m.read8(a + 2) << 16) | (m.read8(a + 3) << 8));
                    pc = a + 4;
                }
                case 0x0c -> pc = natives.call(a + sw(m, a), o, a + 2); // native 68k routine
                case 0x38 -> {
                    natives.callCode(0xfb58, o, m.read16(a));
                    pc = a + 2;
                }
                case 0x34, 0x98, 0x9c -> { // create an object (0xE7B4 / 0xEA34 / 0xEA50)
                    AnimVM.spawn(o, m, natives, code, m.read16(a));
                    pc = a + 2;
                }
                case 0x18 -> pc = a + 4;             // sound
                case 0xa4 -> pc = a + 6;             // sound
                default -> {
                    o.setLong(0x10, 0);
                    return;
                }
            }
        }
        o.setLong(0x10, pc);
    }

    /** Ops $04/$A0: move (n, dx, dy) - plain (engine 0) or with map collision (engine 1). Ends the frame. */
    private static void move(LevelObject o, GameMemory m, LevelData level, int pc, int a, boolean bytes, boolean collision) {
        int n = sw(m, a);
        int dx = bytes ? (byte) m.read8(a + 2) : sw(m, a + 2);
        int dy = bytes ? (byte) m.read8(a + 3) : sw(m, a + 4);
        int len = bytes ? 4 : 6;
        if (collision) {
            collideMove(o, level, n, dx, dy);
            int c = o.word(0x4e) + 1;
            o.setWord(0x4e, c);
            int next = pc;
            if (n >= 0) {
                if (c == n) {
                    o.setWord(0x4e, 0);
                    next = a + len;
                }
            } else if (c == -n) {
                // negative count: velocity kept, the instruction is only (op, n) (0xE6D6)
                o.setWord(0x4e, 0);
                next = a + 2;
            }
            o.setLong(0x10, next); // no velocity recompute (0xE51C)
            return;
        }
        o.setWord(0x02, o.word(0x02) + dx);
        o.setWord(0x06, o.word(0x06) + dy);
        endFrame(o, count(o, n, pc, a + len));
    }

    /**
     * Engine 2 op $04 (0xEAB6), simplified: horizontal move by the velocity with wall test
     * (+$9E = -1 / 1), then gravity added to the vertical velocity and ground / ceiling test
     * (+$64 = landed). Conveyors, slopes and platform carrying of the original are not done yet.
     */
    private static void physicsStep(LevelObject o, LevelData level, int gravity) {
        o.setByte(0x9e, 0);
        int hx = (short) o.word(0x14), hy = (short) o.word(0x16);
        int hw = Math.max(1, (short) o.word(0x18)), hh = Math.max(1, (short) o.word(0x1a));
        boolean wasLanded = o.getByte(0x64) != 0;
        // horizontal
        int vx = o.getLong(0x28) + o.getLong(0x40) + o.getLong(0x3c);
        int nx = (o.getLong(0x02) + vx) >> 16;
        int probeY = o.y() + hy + hh / 2;
        if (vx != 0) {
            int side = vx < 0 ? nx + hx : nx + hx + hw - 1;
            if (level.solidAt(side, probeY) && level.solidAt(side, o.y() - 4)) {
                o.setByte(0x9e, vx < 0 ? 0xff : 1);
                o.setLong(0x28, 0);
            } else {
                o.setLong(0x02, o.getLong(0x02) + vx);
            }
        }
        o.setLong(0x40, 0);
        o.setLong(0x3c, 0);
        // vertical
        o.setByte(0x64, 0);
        int vy = o.getLong(0x2c) + gravity;
        o.setLong(0x2c, vy);
        int x = o.x();
        if (vy >= 0) {
            int target = (o.getLong(0x06) + vy) >> 16;
            int y = o.y();
            boolean landed = false;
            while (y < target) {
                if (level.solidAt(x, y + 1)) {
                    landed = true;
                    break;
                }
                y++;
            }
            if (!landed && wasLanded) { // follow the ground down slopes
                for (int k = 1; k <= 4 && !landed; k++) {
                    if (level.solidAt(x, y + k)) {
                        y += k - 1;
                        landed = true;
                    }
                }
            }
            while (level.solidAt(x, y) && y > 0) { // climb out of slopes
                y--;
                landed = true;
            }
            if (landed || level.solidAt(x, y + 1)) {
                o.setByte(0x64, 0xff);
                o.setLong(0x2c, 0);
                o.setLong(0x06, y << 16);
            } else {
                o.setLong(0x06, o.getLong(0x06) + vy);
            }
        } else {
            int ny = (o.getLong(0x06) + vy) >> 16;
            if (level.solidAt(x, ny + hy)) {
                o.setLong(0x2c, 0);
            } else {
                o.setLong(0x06, o.getLong(0x06) + vy);
            }
        }
    }

    private static int branch(LevelObject o, GameMemory m, int offAddr, boolean take) {
        if (take) {
            o.setWord(0x4e, 0);
            return offAddr + sw(m, offAddr);
        }
        return offAddr + 2;
    }

    /** Count frames on +$4E: stay on the opcode until n frames are done. */
    private static int count(LevelObject o, int n, int self, int next) {
        int c = o.word(0x4e) + 1;
        o.setWord(0x4e, c);
        if (c == n) {
            o.setWord(0x4e, 0);
            return next;
        }
        return self;
    }

    /** End the frame (0xE504): velocity = displacement since the start of the frame. */
    private static void endFrame(LevelObject o, int pc) {
        o.setLong(0x28, o.getLong(0x02) - o.getLong(0x20));
        o.setLong(0x2c, o.getLong(0x06) - o.getLong(0x24));
        o.setLong(0x10, pc);
    }

    /** Velocity + (a << 8), clamped to the limits (code 0xE8AA). */
    private static int[] accel(LevelObject o, GameMemory m, int a) {
        int vx = o.getLong(0x28) + (sw(m, a + 2) << 8);
        vx = vx < 0 ? Math.max(vx, o.getLong(0x8c)) : Math.min(vx, o.getLong(0x90));
        int vy = o.getLong(0x2c) + (sw(m, a + 4) << 8);
        vy = vy < 0 ? Math.max(vy, o.getLong(0x94)) : Math.min(vy, o.getLong(0x98));
        return new int[] {vx, vy};
    }

    /** Move with collision against the level blocks (engine 1, code 0xE53E). */
    private static void collideMove(LevelObject o, LevelData level, int n, int dx, int dy) {
        o.setByte(0x64, 0);
        o.setByte(0x9e, 0);
        if (n >= 0) {
            o.setLong(0x2c, dy << 16);
            o.setLong(0x28, dx << 16);
        }
        int hx = (short) o.word(0x14), hy = (short) o.word(0x16);
        int hw = Math.max(1, (short) o.word(0x18)), hh = Math.max(1, (short) o.word(0x1a));
        // vertical
        int vy = o.getLong(0x2c);
        int ny = (o.getLong(0x06) + vy) >> 16;
        int cx = o.x() + hx + hw / 2;
        if (vy >= 0) {
            int bottom = ny + hy + hh - 1;
            if (level.solidAt(cx, bottom)) {
                ny = (bottom & ~15) - hy - hh;
                while (level.solidAt(cx, ny + hy + hh - 1) && ny > 0) {
                    ny--;
                }
                o.setByte(0x64, 0xff);
                o.setByte(0x9f, 1);
                o.setLong(0x2c, 0);
                o.setLong(0x06, ny << 16);
            } else {
                o.setLong(0x06, o.getLong(0x06) + vy);
            }
        } else {
            int top = ny + hy;
            if (level.solidAt(cx, top)) {
                o.setByte(0x9f, 0xff);
                o.setLong(0x2c, 0);
                o.setLong(0x06, (((top + 16) & ~15) - hy) << 16);
            } else {
                o.setLong(0x06, o.getLong(0x06) + vy);
            }
        }
        // horizontal
        int vx = o.getLong(0x28);
        int nx = (o.getLong(0x02) + vx) >> 16;
        int probeY = o.y() + hy + hh - 6;
        int side = vx < 0 ? nx + hx : nx + hx + hw - 1;
        if (vx != 0 && level.solidAt(side, probeY)) {
            o.setByte(0x9e, vx < 0 ? 0xff : 1);
            o.setLong(0x28, 0);
        } else {
            o.setLong(0x02, o.getLong(0x02) + vx);
        }
    }
}
