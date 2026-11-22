package bubba.dev;

import bubba.emu.GameEmulator;
import bubba.engine.Assets;
import bubba.game.Calls;
import bubba.level.LevelData;
import bubba.level.ObjectPlacement;

import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Dev tool: finds the game routines still run on the interpreter by visiting every kind of
 * object of each stage - Bubba is put next to one placement of each type, then plays randomly
 * for a while. Prints, per stage, the routines without a Java port.
 * Usage: NativeHunt &lt;assets&gt; [frames per object] [placements per type]
 */
public final class NativeHunt {

    private NativeHunt() {
    }

    public static void main(String[] args) throws Exception {
        Assets assets = new Assets(Paths.get(args[0]));
        int frames = args.length > 1 ? Integer.parseInt(args[1]) : 150;
        int perType = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        int[] choices = {0, 8, 4, 9, 5, 0x80, 0x88, 0x84, 2, 1, 8, 4, 0x40, 0x48, 0x44};
        String only = args.length > 3 ? args[3] : "12345";
        for (int stage = 1; stage <= 5; stage++) {
            if (only.indexOf((char) ('0' + stage)) < 0) {
                continue;
            }
            Calls.INTERPRETED.clear();
            Set<Integer> traced = new TreeSet<>();
            GameEmulator.startTrace = Boolean.getBoolean("hunt.trace") ? traced::add : null;
            LevelData level = LevelData.stage(assets, stage);
            Random r = new Random(stage * 31L);
            java.util.Map<Integer, Integer> seen = new java.util.HashMap<>();
            int visits = 0;
            for (ObjectPlacement p : level.placements()) {
                if (seen.merge(p.type(), 1, Integer::sum) > perType) {
                    continue;
                }
                GameEmulator emu = new GameEmulator(assets, level, 9, 6, null);
                for (int f = 0; f < 30; f++) {
                    emu.frame(0);
                }
                emu.ram.write16(GameEmulator.HERO + 2, p.x() - 24 + r.nextInt(48));
                emu.ram.write16(GameEmulator.HERO + 6, p.y() - 8);
                int joy = 0;
                try {
                    for (int f = 0; f < frames; f++) {
                        if (f % 10 == 0) {
                            joy = choices[r.nextInt(choices.length)];
                        }
                        if (!emu.frame(joy)) {
                            break;
                        }
                    }
                } catch (RuntimeException e) {
                    System.out.printf("stage %d type %d: %s%n", stage, p.type(), e.getMessage());
                }
                visits++;
            }
            Set<String> out = new TreeSet<>();
            for (int a : Calls.INTERPRETED) {
                out.add(String.format("$%05X", a));
            }
            System.out.printf("stage %d (%d visits): %s%n", stage, visits, out);
            if (!traced.isEmpty()) {
                StringBuilder b = new StringBuilder();
                for (int pc : traced) {
                    b.append(String.format("%05x ", pc));
                }
                System.out.println("  interpreted: " + b);
            }
        }
        System.exit(0);
    }
}
