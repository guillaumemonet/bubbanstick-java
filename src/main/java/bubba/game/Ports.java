package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Java ports of the game's main-loop routines, installed in place of the 68000 code.
 * <p>
 * Goal: the whole game logic in Java (no emulation). Routines are ported one by one; each port
 * replaces its routine (hook at its address). With -Dbubba.verifyPorts=true every call also runs
 * the original code from the same state and reports the first difference in memory (and in the
 * registers the callers use), like {@link bubba.entity.NativeCalls} did for the natives.
 */
public final class Ports {

    /** A ported routine: same effect on memory (and on {@link #outputs} registers) as the original. */
    public interface Port extends M68k.Hook {
        /** Registers the callers read after the call (indices 0-7 = d0-d7, 8-15 = a0-a7, 16 = ccr). */
        default int[] outputs() {
            return new int[0];
        }
    }

    public static final boolean VERIFY = Boolean.getBoolean("bubba.verifyPorts");

    private static final Map<Integer, Port> PORTS = new LinkedHashMap<>();

    static {
        CameraPorts.register(PORTS);
        MapCollision.register(PORTS);
        ObjectPhysics.register(PORTS);
        ObjectLoop.register(PORTS);
        ObjectLife.register(PORTS);
        BigObjects.register(PORTS);
        HeroContacts.register(PORTS);
        SolidObjects.register(PORTS);
        BlockAnims.register(PORTS);
        HeroSprite.register(PORTS);
        StatusValues.register(PORTS);
        StixPort.register(PORTS);
        PORTS.put(0xdf38, AnimPort::run);
        PORTS.put(0xe6ec, BehaviourPort::engine0);
        PORTS.put(0xe4ea, BehaviourPort::engine4);
        PORTS.put(0xeaae, BehaviourPort::engine8);
    }

    private Ports() {
    }

    /** Ported routine addresses (for reports). */
    public static Set<Integer> addresses() {
        return PORTS.keySet();
    }

    /** Statistics of the verification: calls and differences per routine. */
    public static final Map<Integer, int[]> STATS = new java.util.TreeMap<>();
    private static final Set<Integer> REPORTED = new HashSet<>();

    static {
        if (VERIFY) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> STATS.forEach((a, st) ->
                    System.err.printf("port $%05X: %d calls, %d different%n", a, st[0], st[1]))));
        }
    }

    /** Runs the Java port of the routine at addr (dev tools: fuzzing against the original). */
    public static void runJava(M68k c, int addr) {
        PORTS.get(addr).run(c);
    }

    public static void install(M68k cpu) {
        for (Map.Entry<Integer, Port> e : PORTS.entrySet()) {
            int addr = e.getKey();
            Port port = e.getValue();
            cpu.hook(addr, VERIFY ? c -> verify(c, addr, port) : port);
        }
    }

    private static void verify(M68k c, int addr, Port port) {
        GameMemory ram = c.mem;
        int[] regs = c.saveRegisters();
        byte[] before = ram.snapshot();
        c.callNested(addr, 5_000_000, true);
        int[] origRegs = c.saveRegisters();
        byte[] orig = ram.snapshot();
        ram.restore(before);
        c.restoreRegisters(regs);
        port.run(c);
        int diff = ram.firstDifference(orig);
        int badReg = -1;
        for (int r : port.outputs()) {
            int mask = r == 16 ? 0x0f : -1; // ccr: N Z V C (callers do not test X)
            if ((c.saveRegisters()[r] & mask) != (origRegs[r] & mask)) {
                badReg = r;
                break;
            }
        }
        int[] st = STATS.computeIfAbsent(addr, k -> new int[2]);
        st[0]++;
        if (diff >= 0 || badReg >= 0) {
            st[1]++;
            if (REPORTED.add(addr)) {
                System.err.printf("PORT $%05X differs: memory at $%X (68k %02X / java %02X)%s%n", addr, diff,
                        diff >= 0 ? orig[diff] & 0xff : 0, diff >= 0 ? ram.read8(diff) : 0,
                        badReg >= 0 ? String.format(", register %d 68k %X / java %X", badReg, origRegs[badReg],
                                c.saveRegisters()[badReg]) : "");
                // every differing byte (address: before / 68k / java), to locate the cause
                StringBuilder b = new StringBuilder("   ");
                int shown = 0;
                for (int a = 0; a < orig.length && shown < 32; a++) {
                    int j = ram.read8(a);
                    if ((orig[a] & 0xff) != j) {
                        b.append(String.format(" $%X:%02X/%02X/%02X", a, before[a] & 0xff, orig[a] & 0xff, j));
                        shown++;
                    }
                }
                System.err.println(b);
            }
            // keep the original's result so the game goes on exactly
            ram.restore(orig);
            c.restoreRegisters(origRegs);
        }
    }
}
