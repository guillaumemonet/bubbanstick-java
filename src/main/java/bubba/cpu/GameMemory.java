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
     * Dev spy (-Dbubba.watch=108e9,10ad5, gradlew run -Pwatch): every change of these bytes is
     * printed with the frame, the 68000 PC (when the interpreter runs) and the Java callers.
     */
    private static final java.util.BitSet WATCH = watchList(System.getProperty("bubba.watch"));
    /** Program counter of the interpreter, for the spy. */
    public static java.util.function.IntSupplier pcSource = () -> -1;
    /** Frame number, for the spy and the reports. */
    public static int frame;

    private static java.util.BitSet watchList(String list) {
        if (list == null || list.isBlank()) {
            return null;
        }
        java.util.BitSet set = new java.util.BitSet();
        for (String a : list.split("[,; ]+")) {
            set.set(Integer.parseInt(a.replace("$", ""), 16) & 0xfffff);
        }
        System.err.println("[spy] watching " + list);
        return set;
    }

    private static void report(int addr, int old, int v) {
        StringBuilder sb = new StringBuilder(String.format(
                "[spy] frame %d: $%05X %02X -> %02X, pc $%05X", frame, addr, old, v & 0xff, pcSource.getAsInt()));
        int shown = 0;
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("bubba.") && !c.equals(GameMemory.class.getName()) && shown++ < 8) {
                sb.append("\n        at ").append(c.substring(6)).append('.').append(e.getMethodName())
                        .append(':').append(e.getLineNumber());
            }
        }
        System.err.println(sb);
    }

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
            if (WATCH != null && WATCH.get(addr) && ram[addr] != (byte) v) {
                report(addr, ram[addr] & 0xff, v);
            }
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
