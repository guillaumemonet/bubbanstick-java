package bubba.game;

import bubba.cpu.M68k;

/** Calls from ported code to other routines of the game. */
final class Calls {

    private Calls() {
    }

    /**
     * JSR to {@code addr} with the current registers: runs its Java port if it has one (hook),
     * the original code on the interpreter otherwise. Shrinks to plain Java calls once everything
     * is ported.
     */
    static void call(M68k c, int addr) {
        c.callNested(addr, 5_000_000, false);
    }
}
