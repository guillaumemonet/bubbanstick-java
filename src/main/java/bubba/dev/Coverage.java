package bubba.dev;

import bubba.emu.GameEmulator;
import bubba.engine.Assets;
import bubba.level.LevelData;

import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Dev tool for the Java port: which original code still runs on the interpreter. Plays a random
 * joystick on each stage and counts the distinct instructions executed, grouped by the main-loop
 * routine (the last JSR/BSR target from the main loop) they belong to.
 * Usage: Coverage <assets> [frames per stage]
 */
public final class Coverage {

    private Coverage() {
    }

    public static void main(String[] args) throws Exception {
        Assets assets = new Assets(Paths.get(args[0]));
        int frames = args.length > 1 ? Integer.parseInt(args[1]) : 1500;
        TreeSet<Integer> all = new TreeSet<>();
        TreeSet<Integer> perStage = new TreeSet<>();
        Map<Integer, TreeSet<Integer>> byRange = new TreeMap<>();
        for (int stage = 1; stage <= 5; stage++) {
            GameEmulator emu = new GameEmulator(assets, LevelData.stage(assets, stage), 9, 6, null);
            java.util.Random r = new java.util.Random(stage);
            final int st = stage;
            emu.cpu.trace = pc -> {
                if (pc < 0x60000) {
                    all.add(pc);
                    if (pc >= bubba.cpu.GameMemory.SET_BASE + 0x1000) {
                        perStage.add(st << 24 | pc); // set code: per stage
                    }
                }
            };
            int joy = 0;
            for (int f = 0; f < frames; f++) {
                if (f % 12 == 0) {
                    int[] choices = {0, 8, 4, 9, 5, 0x80, 0x88, 0x84, 2, 1, 8, 8, 4};
                    joy = choices[r.nextInt(choices.length)];
                }
                try {
                    if (!emu.frame(joy)) {
                        break;
                    }
                } catch (RuntimeException e) {
                    System.out.println("stage " + stage + " frame " + f + ": " + e.getMessage());
                    break;
                }
            }
        }
        // group by 4 KB areas
        for (int pc : all) {
            byRange.computeIfAbsent(pc & ~0xfff, k -> new TreeSet<>()).add(pc);
        }
        System.out.println("distinct instructions executed: " + all.size());
        if (System.getProperty("coverage.out") != null) {
            StringBuilder b = new StringBuilder();
            for (int pc : all) {
                b.append(String.format("%05x%n", pc));
            }
            for (int v : perStage) {
                b.append(String.format("s%d %05x%n", v >>> 24, v & 0xffffff));
            }
            java.nio.file.Files.writeString(Paths.get(System.getProperty("coverage.out")), b);
        }
        byRange.forEach((a, s) -> System.out.printf("  $%05X-$%05X: %d%n", a, a + 0xfff, s.size()));
    }
}
