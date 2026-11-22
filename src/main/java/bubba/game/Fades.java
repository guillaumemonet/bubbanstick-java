package bubba.game;

import bubba.cpu.GameMemory;

/**
 * Palette fades of the original, run by the VBL interrupt (code 0x5A30): while the counter $42A8
 * is positive, the routine at ($42AC) is called once per VBL. A fade is requested by 0x79EE
 * (list $42A2, mask $42A6 = $000F, counter $42A8 = $30, target $42AA, routine $42AC, table
 * $42B0). The colour lists are (address.l, count-1.w, step.w) entries up to a negative count;
 * the target of the fades in is the snapshot at $1AC5C (code 0x4092).
 * <p>
 * The list $9966 / $999E ends with entries the level start builds for the copper sky (code
 * 0x9DDC), not in this memory: an entry at address 0 ends the list here.
 */
public final class Fades {

    static final int LIST = 0x42a2, MASK = 0x42a6, COUNT = 0x42a8, TARGET = 0x42aa, ROUTINE = 0x42ac,
            TABLE = 0x42b0, SNAPSHOT = 0x1ac5c;

    private static boolean reported;

    private Fades() {
    }

    /**
     * 0x79EE: a fade of the colour list {@code list} by {@code routine} (first called at the next
     * VBL), {@code d0} = target colour (0x420A), {@code a2} = target table (0x424E).
     */
    public static void request(GameMemory m, int routine, int list, int d0, int a2) {
        m.write32(LIST, list);
        m.write32(MASK, 0xf0030);
        m.write16(TARGET, d0);
        m.write32(ROUTINE, routine);
        m.write32(TABLE, a2);
    }

    /** True while a fade runs (counter $42A8 not zero, code 0x3FE8). */
    public static boolean running(GameMemory m) {
        return m.read16(COUNT) != 0;
    }

    /** One VBL: the fade step, if one is running. */
    public static void vbl(GameMemory m) {
        if ((short) m.read16(COUNT) > 0) {
            run(m, m.read32(ROUTINE));
        }
    }

    static void run(GameMemory m, int routine) {
        switch (routine) {
            case 0x4092 -> snapshot(m);
            case 0x407a -> { // snapshot, then 16 steps down
                snapshot(m);
                m.write16(COUNT, 0x10);
                m.write32(ROUTINE, 0x40f8);
            }
            case 0x40f8 -> down(m);
            case 0x4136 -> maskDown(m);
            case 0x416c -> { // 16 steps up to the snapshot
                m.write16(COUNT, 0x10);
                m.write32(ROUTINE, 0x417e);
                up(m);
            }
            case 0x417e -> up(m);
            case 0x41ca -> maskUp(m);
            case 0x420a, 0x420e -> toConstant(m);
            case 0x424e, 0x4252 -> toTable(m);
            default -> {
                if (!reported) {
                    reported = true;
                    System.err.printf("fade routine $%05X unknown: stopped%n", routine);
                }
                m.write16(COUNT, 0);
            }
        }
    }

    /** Calls {@code fn(address)} for every colour of the list at ($42A2). */
    private interface Colour {
        void at(int address);
    }

    private static void each(GameMemory m, Colour fn) {
        int a = m.read32(LIST);
        for (int guard = 0; guard < 256; guard++, a += 8) {
            int addr = m.read32(a);
            int n = (short) m.read16(a + 4);
            if (n < 0 || addr == 0) {
                return;
            }
            int step = (short) m.read16(a + 6);
            for (int i = 0; i <= n; i++, addr += step) {
                fn.at(addr);
            }
        }
    }

    /** 0x4092: the colours of the list into $1AC5C; the next VBLs fade them out (0x4136). */
    public static void snapshot(GameMemory m) {
        int[] p = {SNAPSHOT};
        each(m, addr -> {
            m.write16(p[0], m.read16(addr));
            p[0] += 2;
        });
        m.write16(0x40ca, (p[0] - SNAPSHOT) >> 1);
        m.write32(ROUTINE, 0x4136);
    }

    /** 0x4092 then 0x40CC, as at the start of a level (code 0x3A86). */
    public static void snapshotAndClear(GameMemory m) {
        snapshot(m);
        clear(m);
    }

    /** 0x40CC: every colour of the list to black. */
    public static void clear(GameMemory m) {
        each(m, addr -> m.write16(addr, 0));
    }

    /** 0x40F8: every component one step down. */
    private static void down(GameMemory m) {
        each(m, addr -> {
            int c = m.read16(addr);
            if ((c & 0xf00) != 0) {
                c -= 0x100;
            }
            if ((c & 0xf0) != 0) {
                c -= 0x10;
            }
            if ((c & 0xf) != 0) {
                c -= 1;
            }
            m.write16(addr, c);
        });
        m.write16(COUNT, m.read16(COUNT) - 1);
    }

    /** 0x417E: every component one step towards the snapshot. */
    private static void up(GameMemory m) {
        int[] t = {SNAPSHOT};
        each(m, addr -> {
            int c = m.read16(addr), d = c ^ m.read16(t[0]);
            t[0] += 2;
            if ((d & 0xf00) != 0) {
                c += 0x100;
            }
            if ((d & 0xf0) != 0) {
                c += 0x10;
            }
            if ((d & 0xf) != 0) {
                c += 1;
            }
            m.write16(addr, c);
        });
        m.write16(COUNT, m.read16(COUNT) - 1);
    }

    /** Component mask of this step ($42A6): blue, red, green, blue... */
    private static void nextMask(GameMemory m, int mask) {
        mask >>= 4;
        if (mask == 0) {
            mask = 0xf00;
        }
        m.write16(MASK, mask);
        m.write16(COUNT, m.read16(COUNT) - 1);
    }

    /** 0x4136: the component of the mask one step down. */
    private static void maskDown(GameMemory m) {
        int mask = m.read16(MASK), one = mask & 0x111;
        each(m, addr -> {
            if ((m.read16(addr) & mask) != 0) {
                m.write16(addr, m.read16(addr) - one);
            }
        });
        nextMask(m, mask);
    }

    /** 0x41CA: the component of the mask one step up, unless it equals the snapshot's. */
    private static void maskUp(GameMemory m) {
        int mask = m.read16(MASK), one = mask & 0x111;
        int[] t = {SNAPSHOT};
        each(m, addr -> {
            int c = m.read16(addr), d = (c ^ m.read16(t[0])) & mask;
            t[0] += 2;
            if (d != 0) {
                m.write16(addr, c + one);
            }
        });
        nextMask(m, mask);
    }

    /** 0x420E: the component of the mask one step towards the one of the colour $42AA. */
    private static void toConstant(GameMemory m) {
        int mask = m.read16(MASK), one = mask & 0x111, target = m.read16(TARGET) & mask;
        each(m, addr -> {
            int c = m.read16(addr) & mask;
            if (c > target) {
                m.write16(addr, m.read16(addr) - one);
            } else if (c < target) {
                m.write16(addr, m.read16(addr) + one);
            }
        });
        nextMask(m, mask);
    }

    /**
     * 0x4252: the component of the mask one step towards the table ($42B0), indexed by the
     * colour register of the copper move (word before the colour - $180).
     */
    private static void toTable(GameMemory m) {
        int mask = m.read16(MASK), one = mask & 0x111, table = m.read32(TABLE);
        int a = m.read32(LIST);
        for (int guard = 0; guard < 256; guard++, a += 8) {
            int addr = m.read32(a);
            int n = (short) m.read16(a + 4);
            if (n < 0 || addr == 0) {
                break;
            }
            int t = table + (short) (m.read16(addr - 2) - 0x180);
            int step = (short) m.read16(a + 6);
            for (int i = 0; i <= n; i++, addr += step, t += 2) {
                int c = m.read16(addr) & mask, target = m.read16(t) & mask;
                if (c > target) {
                    m.write16(addr, m.read16(addr) - one);
                } else if (c < target) {
                    m.write16(addr, m.read16(addr) + one);
                }
            }
        }
        nextMask(m, mask);
    }
}
