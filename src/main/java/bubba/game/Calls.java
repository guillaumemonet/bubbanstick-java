package bubba.game;

import bubba.cpu.M68k;

import java.util.Set;
import java.util.TreeSet;

/**
 * Calls from ported code to other routines of the game: a plain Java call of the routine's
 * port, with the return address pushed like the original JSR (some routines read or change it).
 * A routine without a port still runs on the 68000 interpreter (reported once): none is reached
 * in play any more; the interpreter remains the reference of the verification mode.
 */
public final class Calls {

    private static final int RETURN = 0x00ffffe0;
    /** Routines that had to run on the interpreter (report). */
    public static final Set<Integer> INTERPRETED = new TreeSet<>();

    private Calls() {
    }

    static void call(M68k c, int addr) {
        M68k.Hook h = Ports.VERIFY ? null : c.hookAt(addr);
        if (h == null) {
            if (!Ports.VERIFY && INTERPRETED.add(addr)) {
                System.err.printf("game routine $%05X has no Java port: run on the 68000 interpreter (frame %d)%n",
                        addr, bubba.cpu.GameMemory.frame);
            }
            c.callNested(addr, 5_000_000, false);
            return;
        }
        c.a[7] -= 4;
        c.mem.write32(c.a[7], RETURN);
        h.run(c);
        c.a[7] += 4;
    }
}
