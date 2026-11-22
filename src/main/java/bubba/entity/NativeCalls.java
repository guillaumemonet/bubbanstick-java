package bubba.entity;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;
import bubba.entity.natives.NativeContext;
import bubba.entity.natives.NativeRoutine;
import bubba.entity.natives.Natives;
import bubba.level.LevelData;

import java.util.HashSet;
import java.util.Set;

/**
 * Runs the native 68k routines called by the object scripts (opcode $0C: "move.w (a4)+,d0;
 * jsr -2(a4,d0.w)"), with a5 = object structure and a4 = script pointer. The routine may change
 * a4 to redirect the script, exactly like in the original.
 * <p>
 * Game routines that need things not emulated here are replaced by Java hooks:
 * sound ($7BC8/$7BCA) and object spawning ($107B2, reported as failed for now).
 */
public final class NativeCalls {

    private static final int MAX_STEPS = 200_000;

    /** -Dbubba.verifyNatives=true: run ported routines on the interpreter too and compare. */
    private static final boolean VERIFY = Boolean.getBoolean("bubba.verifyNatives");

    private final M68k cpu;
    private final GameMemory ram;
    private final NativeContext context;
    private final String fileId;
    private final Set<Integer> reported = new HashSet<>();
    /** Statistics (debug): calls served by a Java port / by the interpreter, per set offset. */
    public final java.util.Map<Integer, Integer> javaCalls = new java.util.TreeMap<>();
    public final java.util.Map<Integer, Integer> emulatedCalls = new java.util.TreeMap<>();

    public NativeCalls(GameMemory ram, LevelData level) {
        this.ram = ram;
        this.context = new NativeContext(ram, level);
        this.fileId = level.fileId();
        cpu = new M68k(ram);
        cpu.hook(0x7bc8, c -> { });          // play sound d0 on channel d1
        cpu.hook(0x7bca, c -> { });          // play sound (3 parameters)
        cpu.hook(0x107b2, c -> c.z = false); // spawn object: failure until a world is attached
    }

    /** Spawner of child objects (routine $107B2 and the script spawn opcodes). */
    public interface Spawner {
        /** New object of type index {@code index} at the parent's position, or -1 if full. */
        int spawnChild(int parent, int index);
    }

    private Spawner spawner;

    /**
     * Install the object spawner: $107B2 then returns like the original, a5 = child, a4 = parent,
     * d0 = 0 and Z set on success (d0 = 1, Z clear when the 32 objects are in use).
     */
    public void setSpawner(Spawner s) {
        this.spawner = s;
        M68k.Hook h = c -> {
            int parent = c.a[5];
            int child = s.spawnChild(parent, c.d[0] & 0xffff);
            c.d[1] = (c.d[1] & 0xffff0000) | (c.d[0] & 0xffff);
            c.a[4] = parent;
            if (child < 0) {
                c.d[0] = 1;
                c.z = false;
            } else {
                c.a[5] = child;
                c.d[0] = 0;
                c.z = true;
            }
        };
        cpu.hook(0x107b2, h);
        context.hook(0x107b2, h);
    }

    /** Script spawn opcodes: child of {@code o}, or -1. */
    public int spawn(LevelObject o, int index) {
        return spawner == null ? -1 : spawner.spawnChild(o.base, index);
    }

    public M68k cpu() {
        return cpu;
    }

    /** Call a routine of the game code with a5 = object and d0 = parameter (e.g. 0xFB58, 0xDC90). */
    public void callCode(int target, LevelObject o, int d0) {
        java.util.Arrays.fill(cpu.d, 0);
        cpu.d[0] = d0;
        cpu.a[5] = o.base;
        try {
            cpu.call(target, MAX_STEPS);
        } catch (RuntimeException e) {
            if (reported.add(target)) {
                System.err.printf("code $%05X (type %d): %s%n", target, o.typeId(), e.getMessage());
            }
        }
    }

    /**
     * Call the native routine at {@code target} for object {@code o}; {@code a4} = script pointer
     * after the operand. Returns the (possibly modified) script pointer.
     */
    public int call(int target, LevelObject o, int a4) {
        NativeRoutine port = Natives.find(fileId, target - GameMemory.SET_BASE);
        (port != null ? javaCalls : emulatedCalls).merge(target - GameMemory.SET_BASE, 1, Integer::sum);
        if (port != null) {
            if (VERIFY && Natives.verifiable(fileId, target - GameMemory.SET_BASE)) {
                return verify(port, target, o, a4);
            }
            return port.run(context, o, a4);
        }
        return emulate(target, o, a4);
    }

    /** Run both versions from the same state and report any difference (debug mode). */
    private int verify(NativeRoutine port, int target, LevelObject o, int a4) {
        byte[] before = ram.snapshot();
        int emuA4 = emulate(target, o, a4);
        byte[] emu = ram.snapshot();
        ram.restore(before);
        int javaA4 = port.run(context, o, a4);
        int diff = ram.firstDifference(emu);
        if (emuA4 != javaA4 || diff >= 0) {
            System.err.printf("VERIFY native set $%04X (type %d): a4 68k=$%X java=$%X, first memory difference at $%X%n",
                    target - GameMemory.SET_BASE, o.typeId(), emuA4, javaA4, diff);
        }
        return javaA4;
    }

    private int emulate(int target, LevelObject o, int a4) {
        java.util.Arrays.fill(cpu.d, 0);
        cpu.a[4] = a4;
        cpu.a[5] = o.base;
        try {
            cpu.call(target, MAX_STEPS);
            return cpu.a[4];
        } catch (RuntimeException e) {
            if (reported.add(target)) {
                System.err.printf("native $%05X (set $%04X, type %d): %s%n", target,
                        target - GameMemory.SET_BASE, o.typeId(), e.getMessage());
            }
            return a4;
        }
    }
}
