package bubba.dev;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;
import bubba.engine.Assets;
import bubba.entity.LevelObject;
import bubba.entity.natives.NativeContext;
import bubba.entity.natives.NativeRoutine;
import bubba.entity.natives.Natives;
import bubba.level.LevelData;
import bubba.level.LevelMemory;
import bubba.level.ObjectType;

import java.nio.file.Paths;
import java.util.Random;

/**
 * Checks the Java ports of the native routines against the 68000 interpreter on random object
 * states. Usage: NativeFuzz <assets dir> <stage 1-5> [trials] [set offset hex].
 * For every trial: random object fields, hero position and random seed; the routine runs on the
 * interpreter and in Java from the same memory; the new script pointer (a4) and the whole memory
 * must be identical. Prints the first differences and a summary per routine.
 */
public final class NativeFuzz {

    private NativeFuzz() {
    }

    public static void main(String[] args) throws Exception {
        Assets assets = new Assets(Paths.get(args[0]));
        int stage = Integer.parseInt(args[1]);
        int trials = args.length > 2 ? Integer.parseInt(args[2]) : 300;
        Integer only = args.length > 3 ? Integer.parseInt(args[3], 16) : null;
        LevelData level = LevelData.stage(assets, stage);
        GameMemory ram = new GameMemory(assets);
        ram.loadSet(level.setImage());
        LevelMemory.build(ram, level.info(), level.rawMap());
        M68k cpu = new M68k(ram);
        cpu.hook(0x7bc8, c -> { });
        cpu.hook(0x7bca, c -> { });
        cpu.hook(0x107b2, c -> c.z = false);
        NativeContext ctx = new NativeContext(ram, level);
        ObjectType anyType = level.placements().stream().map(p -> level.type(p.type()))
                .filter(t -> t != null).findFirst().orElseThrow();
        byte[] clean = ram.snapshot();
        int failedRoutines = 0;
        for (int off : Natives.ported(level.fileId())) {
            if (only != null && off != only) {
                continue;
            }
            NativeRoutine port = Natives.find(level.fileId(), off);
            Random rnd = new Random(off);
            int ok = 0, bad = 0, crash = 0;
            for (int t = 0; t < trials; t++) {
                ram.restore(clean);
                LevelObject o = new LevelObject(ram, 0x59000);
                init(ram, o, anyType);
                randomise(ram, o, level, rnd);
                int a4 = GameMemory.SET_BASE + off + 0x100; // arbitrary "after operand" pointer
                byte[] before = ram.snapshot();
                int emuA4;
                try {
                    cpu.a[4] = a4;
                    cpu.a[5] = o.base;
                    java.util.Arrays.fill(cpu.d, 0);
                    cpu.call(GameMemory.SET_BASE + off, 200_000);
                    emuA4 = cpu.a[4];
                } catch (RuntimeException e) {
                    crash++;
                    continue; // state the original could not handle either
                }
                byte[] emu = ram.snapshot();
                ram.restore(before);
                int javaA4 = port.run(ctx, o, a4);
                int diff = ram.firstDifference(emu);
                if (javaA4 != emuA4 || diff >= 0) {
                    if (bad == 0 && Boolean.getBoolean("fuzz.dump")) {
                        ram.restore(before);
                        System.out.printf("  state: hero x=%04X y=%04X box=%04X %04X %04X %04X | obj x=%04X y=%04X hit=%04X %04X %04X %04X%n",
                                ram.read16(0x10912), ram.read16(0x10916), ram.read16(0x10922), ram.read16(0x10924),
                                ram.read16(0x10926), ram.read16(0x10928), o.word(2), o.word(6), o.word(0x14),
                                o.word(0x16), o.word(0x18), o.word(0x1a));
                    }
                    if (bad < 3) {
                        System.out.printf("  $%04X trial %d: a4 68k=$%X java=$%X, memory diff at $%X (68k %02X / java %02X)%n",
                                off, t, emuA4, javaA4, diff, diff >= 0 ? emu[diff] & 0xff : 0, diff >= 0 ? ram.read8(diff) : 0);
                    }
                    bad++;
                } else {
                    ok++;
                }
            }
            System.out.printf("set $%04X: %d ok, %d different, %d interpreter errors%n", off, ok, bad, crash);
            if (bad > 0) {
                failedRoutines++;
            }
        }
        System.out.println(failedRoutines == 0 ? "ALL PORTS MATCH" : failedRoutines + " ROUTINE(S) DIFFER");
    }

    /** Fields of a fresh object of type {@code t} (enough for the routines under test). */
    private static void init(GameMemory ram, LevelObject o, ObjectType t) {
        ram.clear(o.base, LevelObject.STRUCT_SIZE);
        o.setWord(0x00, 1);
        o.setWord(0x0a, t.sprite() == null ? 0xfffe : t.sprite());
        o.setLong(0x0c, GameMemory.SET_BASE + t.animScript());
        o.setLong(0x10, GameMemory.SET_BASE + t.behaviourScript());
        o.setWord(0x14, t.hitX());
        o.setWord(0x16, t.hitY());
        o.setWord(0x18, t.hitW());
        o.setWord(0x1a, t.hitH());
        byte[] p = t.params();
        for (int i = 0; i < 4 && i < p.length; i++) {
            o.setByte(0x1c + i, p[i]);
        }
        o.setWord(0x5c, t.id() - 1);
    }

    /** Plausible random state: position on the map, hero nearby, random fields and flags. */
    private static void randomise(GameMemory ram, LevelObject o, LevelData level, Random r) {
        int x = 16 + r.nextInt(Math.max(1, level.widthPx() - 32));
        int y = 16 + r.nextInt(Math.max(1, level.heightPx() - 32));
        o.setLong(0x02, (x << 16) | r.nextInt(0x10000));
        o.setLong(0x06, (y << 16) | r.nextInt(0x10000));
        int px = x + r.nextInt(600) - 300, py = y + r.nextInt(300) - 150;
        ram.write16(0x10912, px);
        ram.write16(0x10916, py);
        ram.write8(0x10964, r.nextBoolean() ? 0xff : 0);
        o.setWord(0x86, x - px);
        o.setWord(0x88, y - py);
        o.setLong(0x28, (r.nextInt(17) - 8) << 16 | r.nextInt(0x10000));
        o.setLong(0x2c, (r.nextInt(17) - 8) << 16 | r.nextInt(0x10000));
        int[] bytes = {0x30, 0x31, 0x3a, 0x44, 0x45, 0x46, 0x47, 0x5a, 0x5b, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67,
            0x68, 0x69, 0x6a, 0x6b, 0x8a, 0x8b, 0x9e, 0x9f};
        for (int b : bytes) {
            int v = r.nextInt(4);
            o.setByte(b, v == 0 ? 0 : v == 1 ? 0xff : v == 2 ? 1 : r.nextInt(256));
        }
        ram.write32(0x10894, r.nextInt());
        ram.write32(0x10898, r.nextInt());

        // game state read by some routines (lifts, switches, water, counters, held object...)
        int[] words = {0x10ada, 0x10adc, 0x1098a, 0x1098e, 0x10b0a, 0x10a5c, 0x10a54, 0x10938, 0x1093c,
            0x125f6, 0x1090e, 0x10922, 0x10924, 0x10926, 0x10928, 0x1092a};
        for (int w : words) {
            ram.write16(w, r.nextInt(3) == 0 ? 0 : r.nextInt(0x10000));
        }
        ram.write32(0x10a50, (y + r.nextInt(200) - 100) << 16);
        ram.write32(0x10a5e, (y + r.nextInt(200) - 100) << 16);
        int[] bytes2 = {0x17509, 0x10a66, 0x10a67, 0x108c2, 0x10ad9, 0x12e9d};
        for (int b : bytes2) {
            ram.write8(b, r.nextInt(4) == 0 ? 0 : r.nextInt(256));
        }
        for (int i = 0; i < 9; i++) {
            ram.write8(0x12e95 + i, r.nextInt(3) == 0 ? 0xff : 0);
        }
        // "held object" pointer: sometimes this very object
        ram.write32(0x17506, r.nextBoolean() ? o.base : 0x5c100);
        // two small object lists (count + pointers) for the routines that walk them
        ram.write32(0x10aa8, 0x5c000);
        ram.write32(0x10aac, 0x5c040);
        ram.write16(0x5c000, 2);
        ram.write32(0x5c002, 0x5c100);
        ram.write32(0x5c006, 0x5c200);
        ram.write16(0x5c040, r.nextInt(2));
        ram.write32(0x5c042, o.base);
        for (int i = 0; i < 0xc0; i++) {
            ram.write8(0x5c100 + i, r.nextInt(256));
            ram.write8(0x5c200 + i, r.nextInt(256));
        }
        // pointer fields: sometimes valid addresses (flags, other objects)
        int[] ptrs = {0x10ad9, 0x12e9d, 0x5c100, 0x5c200, 0};
        o.setLong(0x50, ptrs[r.nextInt(ptrs.length)]);
        if (r.nextBoolean()) {
            o.setLong(0x44, ptrs[r.nextInt(ptrs.length)]);
        }
        o.setLong(0x48, ptrs[r.nextInt(ptrs.length)]);
        o.setByte(0x45, r.nextInt(256));
        o.setByte(0x47, r.nextInt(256));
    }
}
