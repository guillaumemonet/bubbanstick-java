package bubba.cpu;

import bubba.engine.Assets;

/**
 * The game's address space, rebuilt like the original: decrunched code hunk at 0, chip hunk at
 * $60000 (relocated), the level's set*.bin at $108EE, object structures in the original pools,
 * level tables (map, block table) from $80000 (see LevelMemory).
 * Custom chip / CIA registers ($BFxxxx, $DFFxxx) read as 0 and ignore writes.
 */
public final class GameMemory {

    public static final int SIZE = 0x100000;
    public static final int SET_BASE = 0x108ee;
    public static final int STACK_TOP = 0xffff0;

    private final byte[] ram = new byte[SIZE];

    /**
     * Block graphics copied by the blitter (0xC6B4): the bitmaps are not in this memory (the
     * renderer has them as images), so a blit of graphics slot s onto slot d is kept as
     * {@code blockGfx[d] = blockGfx[s]} (slot = (address - $80000) / $80, identity at start).
     */
    public final int[] blockGfx = new int[0x1a000 / 0x80];

    {
        for (int i = 0; i < blockGfx.length; i++) {
            blockGfx[i] = i;
        }
    }

    public GameMemory(Assets assets) {
        byte[] img = assets.bytes("game/memory.bin");
        System.arraycopy(img, 0, ram, 0, Math.min(img.length, SIZE));
    }

    /** Copy a level memory image (set*.bin) to $108EE, as the loader does. */
    public void loadSet(byte[] set) {
        System.arraycopy(set, 0, ram, SET_BASE, set.length);
    }

    private static boolean mapped(int addr) {
        return addr >= 0 && addr < SIZE;
    }

    public int read8(int addr) {
        addr &= 0xffffff;
        return mapped(addr) ? ram[addr] & 0xff : 0;
    }

    public int read16(int addr) {
        return (read8(addr) << 8) | read8(addr + 1);
    }

    public int read32(int addr) {
        return (read16(addr) << 16) | read16(addr + 2);
    }

    public void write8(int addr, int v) {
        addr &= 0xffffff;
        if (mapped(addr)) {
            ram[addr] = (byte) v;
        }
    }

    public void write16(int addr, int v) {
        write8(addr, v >> 8);
        write8(addr + 1, v);
    }

    public void write32(int addr, int v) {
        write16(addr, v >>> 16);
        write16(addr + 2, v);
    }

    public byte[] snapshot() {
        return ram.clone();
    }

    public void restore(byte[] snap) {
        System.arraycopy(snap, 0, ram, 0, SIZE);
    }

    /** First address (below the stack area) where this memory differs from {@code other}, or -1. */
    public int firstDifference(byte[] other) {
        for (int i = 0; i < STACK_TOP - 0x1000; i++) {
            if (ram[i] != other[i]) {
                return i;
            }
        }
        return -1;
    }

    public void clear(int addr, int len) {
        for (int i = 0; i < len; i++) {
            write8(addr + i, 0);
        }
    }
}
