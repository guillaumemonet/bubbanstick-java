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
        PORTS.put(0xe292, new Port() { // zone +$54 entered once: script +$50 / animation +$36
            @Override
            public void run(M68k c) {
                GameMemory m = c.mem;
                int o = c.a[5];
                c.a[0] = m.read32(o + 0x54);
                boolean go = (m.read8(c.a[0] + 6) & 0x40) != 0;
                if (!go) {
                    int v = m.read8(c.a[0]);
                    m.write8(c.a[0], v | 0x80);
                    go = (v & 0x80) == 0;
                }
                if (go) {
                    c.a[4] = m.read32(o + 0x50);
                    m.write32(o + 0xc, m.read32(o + 0x36));
                }
            }

            @Override
            public int[] outputs() {
                return new int[]{8, 12};
            }
        });
        PORTS.put(0x7956, new Port() { // clear the two screens
            @Override
            public void run(M68k c) {
                GameMemory m = c.mem;
                c.a[0] = m.read32(0x8282); // movem.l $8282(pc) (shown $8280 by the disassembly)
                c.a[1] = m.read32(0x8286);
                c.d[1] = 0;
                for (int i = 0; i < 0x2580; i++) {
                    m.write32(c.a[0], 0);
                    m.write32(c.a[1], 0);
                    c.a[0] += 4;
                    c.a[1] += 4;
                }
                c.d[0] = (c.d[0] & 0xffff0000) | 0xffff;
            }

            @Override
            public int[] outputs() {
                return new int[]{0, 1, 8, 9};
            }
        });
        PORTS.put(0x79ee, new Port() { // display request (palette fade) at $42A2
            @Override
            public void run(M68k c) {
                GameMemory m = c.mem;
                int a3 = 0x42a2;
                m.write32(a3, c.a[1]);
                m.write32(a3 + 4, 0xf0030);
                m.write16(a3 + 8, c.d[0]);
                m.write32(a3 + 10, c.a[0]);
                m.write32(a3 + 14, c.a[2]);
                c.a[3] = a3 + 18;
            }

            @Override
            public int[] outputs() {
                return new int[]{11};
            }
        });
        PORTS.put(0xc67a, new Port() {
            @Override
            public void run(M68k c) {
                SetCode.startAnim(c);
            }

            @Override
            public int[] outputs() {
                return new int[]{0, 7, 14};
            }
        });
        HeroSprite.register(PORTS);
        StatusValues.register(PORTS);
        StixPort.register(PORTS);
        HeroPhysics.register(PORTS);
        HeroStates.register(PORTS);
        HeroHandlers.register(PORTS);
        Respawn.register(PORTS);
        LevelStart.register(PORTS);
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

    /** A game routine called from outside the ported code (level start). */
    public static void call(M68k c, int addr) {
        Calls.call(c, addr);
    }

    /** Runs the Java port of the routine at addr (dev tools: fuzzing against the original). */
    public static void runJava(M68k c, int addr) {
        Port p = PORTS.get(addr);
        if (p != null) {
            p.run(c);
        } else {
            c.hookAt(addr).run(c); // level ports, installed on the processor
        }
    }

    public static void install(M68k cpu) {
        for (Map.Entry<Integer, Port> e : PORTS.entrySet()) {
            int addr = e.getKey();
            Port port = e.getValue();
            cpu.hook(addr, VERIFY ? c -> verify(c, addr, port) : port);
        }
    }

    /**
     * The level's native routines (set code) already ported to Java ({@link bubba.entity.natives}),
     * installed at their address in the loaded set ($108EE + offset). They return a4 (the script
     * pointer, possibly redirected).
     */
    public static void installNatives(M68k cpu, bubba.level.LevelData level) {
        bubba.entity.natives.NativeContext ctx = new bubba.entity.natives.NativeContext(cpu.mem, level, cpu);
        for (int off : bubba.entity.natives.Natives.ported(level.fileId())) {
            bubba.entity.natives.NativeRoutine r = bubba.entity.natives.Natives.find(level.fileId(), off);
            int addr = GameMemory.SET_BASE + off;
            Port port = new Port() {
                @Override
                public void run(M68k c) {
                    c.a[4] = r.run(ctx, new bubba.entity.LevelObject(c.mem, c.a[5]), c.a[4]);
                }

                @Override
                public int[] outputs() {
                    return new int[]{12};
                }
            };
            cpu.hook(addr, VERIFY ? c -> verify(c, addr, port) : port);
        }
        for (Map.Entry<Integer, Port> e : SetCode.forLevel(level.fileId()).entrySet()) {
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
