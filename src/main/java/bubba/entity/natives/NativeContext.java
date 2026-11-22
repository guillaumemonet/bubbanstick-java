package bubba.entity.natives;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;
import bubba.level.LevelData;

/** What a ported native routine can access: game memory and the level (map, block attributes). */
public final class NativeContext {

    public static final int SET = GameMemory.SET_BASE;
    /** Random generator state of the game (two longs at $10894, routine inlined in many places). */
    private static final int RNG = 0x10894;

    public final GameMemory ram;
    public final LevelData level;
    private final M68k cpu;

    public NativeContext(GameMemory ram, LevelData level) {
        this.ram = ram;
        this.level = level;
        this.cpu = new M68k(ram);
        cpu.hook(0x7bc8, c -> { });          // sound: ignored (same as NativeCalls)
        cpu.hook(0x7bca, c -> { });
        cpu.hook(0x107b2, c -> c.z = false); // spawn: failure until a spawner is installed
    }

    /** Hook a game routine of this context's interpreter (e.g. the object spawner $107B2). */
    public void hook(int address, M68k.Hook h) {
        cpu.hook(address, h);
    }

    /**
     * Call a shared routine of the game code (e.g. the map collision routines $F222 / $F3E6)
     * through the 68000 interpreter, for ports that have not reimplemented it. {@code d} and
     * {@code a} (8 registers each) are loaded before the call and hold the registers after it;
     * the condition codes after the call are returned (X N Z V C bits).
     */
    public int callGame(int address, int[] d, int[] a) {
        System.arraycopy(d, 0, cpu.d, 0, 8);
        System.arraycopy(a, 0, cpu.a, 0, 7);
        cpu.call(address, 200_000);
        System.arraycopy(cpu.d, 0, d, 0, 8);
        System.arraycopy(cpu.a, 0, a, 0, 7);
        return cpu.ccr();
    }

    /**
     * The game's random generator: move.l (a0)+,d0 / move.l (a0),d1 / eor.w d0,d1 / ror.w #1,d1 /
     * eor.w d1,d0 / swap d0 / move.l d0,(a0) / move.l d1,-(a0). Returns d0 (32 bits).
     */
    public int random() {
        int d0 = ram.read32(RNG), d1 = ram.read32(RNG + 4);
        d1 = (d1 & 0xffff0000) | ((d1 ^ d0) & 0xffff);
        int w = d1 & 0xffff;
        d1 = (d1 & 0xffff0000) | (((w >>> 1) | (w << 15)) & 0xffff);
        d0 = (d0 & 0xffff0000) | ((d0 ^ d1) & 0xffff);
        d0 = (d0 << 16) | (d0 >>> 16);
        ram.write32(RNG + 4, d0);
        ram.write32(RNG, d1);
        return d0;
    }

    /** Block attribute word of the map cell (bx, by) - what the game reads through L_10888/L_10890. */
    public int cellAttribute(int bx, int by) {
        return level.attribute(level.block(bx, by));
    }
}
