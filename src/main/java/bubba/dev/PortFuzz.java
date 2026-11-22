package bubba.dev;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;
import bubba.emu.GameEmulator;
import bubba.engine.Assets;
import bubba.game.Ports;
import bubba.level.LevelData;

import java.nio.file.Paths;
import java.util.Random;

/**
 * Checks a Java port against the original routine on random object states (for the routines the
 * scripted runs do not reach). Usage: PortFuzz <assets> <stage> <routine hex> [trials].
 * The level is started (60 frames), then for every trial random objects are set up, the
 * original runs on the interpreter and the port in Java from the same memory; the whole memory
 * must be identical.
 */
public final class PortFuzz {

    private PortFuzz() {
    }

    public static void main(String[] args) throws Exception {
        Assets assets = new Assets(Paths.get(args[0]));
        LevelData level = LevelData.stage(assets, Integer.parseInt(args[1]));
        int routine = Integer.parseInt(args[2], 16);
        int trials = args.length > 3 ? Integer.parseInt(args[3]) : 2000;
        GameEmulator emu = new GameEmulator(assets, level, 3, 6, null);
        for (int i = 0; i < 60; i++) {
            emu.frame(0);
        }
        GameMemory m = emu.ram;
        M68k c = emu.cpu;
        byte[] base = m.snapshot();
        int[] regs = c.saveRegisters();
        Random rnd = new Random(routine);
        int bad = 0, skipped = 0;
        int sp = regs[15];
        for (int t = 0; t < trials; t++) {
            m.restore(base);
            c.restoreRegisters(regs);
            setup(m, rnd);
            byte[] before = m.snapshot();
            try {
                c.callNested(routine, 200_000, true);
            } catch (IllegalStateException e) {
                skipped++; // the random state loops the original too (carrier cycle)
                continue;
            }
            byte[] orig = m.snapshot();
            m.restore(before);
            c.restoreRegisters(regs);
            Ports.runJava(c, routine);
            byte[] mine = m.snapshot();
            for (int a = 0; a < orig.length; a++) {
                if (orig[a] != mine[a] && (a >= sp || a < sp - 0x400)) {
                    if (bad < 10) {
                        System.out.printf("trial %d: $%05X original %02X java %02X%n", t, a, orig[a] & 0xff, mine[a] & 0xff);
                    }
                    bad++;
                    break;
                }
            }
        }
        System.out.printf("$%X: %d trials, %d skipped, %d different%n", routine, trials, skipped, bad);
    }

    private static void setup(GameMemory m, Random r) {
        int k = 2 + r.nextInt(6);
        int[] objs = new int[k];
        for (int i = 0; i < k; i++) {
            int o = 0x17ed8 + 0xba * (i + 3);
            objs[i] = o;
            m.write16(o, 1);
            m.write32(o + 2, (1000 + r.nextInt(80)) << 16 | r.nextInt(0x10000));
            m.write32(o + 6, (500 + r.nextInt(80)) << 16 | r.nextInt(0x10000));
            m.write16(o + 0x14, -r.nextInt(17));
            m.write16(o + 0x16, -r.nextInt(33));
            m.write16(o + 0x18, 8 + r.nextInt(25));
            m.write16(o + 0x1a, 8 + r.nextInt(33));
            m.write32(o + 0x28, r.nextInt(0x60000) - 0x30000);
            m.write32(o + 0x2c, r.nextInt(0x60000) - 0x30000);
            m.write32(o + 0x40, r.nextInt(0x20000) - 0x10000);
            boolean carried = i > 0 && r.nextInt(3) == 0;
            m.write8(o + 0x6b, carried ? 0xff : 0);
            m.write32(o + 0x48, carried ? objs[r.nextInt(i)] : 0);
            m.write8(o + 0x9e, new int[]{0, 1, 0xff}[r.nextInt(3)]);
            m.write8(o + 0x64, r.nextBoolean() ? 0xff : 0);
            m.write8(o + 0x83, r.nextInt(4) == 0 ? 0 : 0x80);
        }
        m.write16(0x17514, k);
        for (int i = 0; i < k; i++) {
            m.write32(0x17516 + 4 * i, objs[i]);
        }
        int n = r.nextInt(k + 1);
        m.write16(0x1767a, n);
        for (int i = 0; i < n; i++) {
            m.write32(0x1767c + 4 * i, objs[r.nextInt(k)]);
        }
        int s = 0x10988;
        m.write8(s + 0x83, r.nextBoolean() ? 0x80 : 0);
        m.write32(s + 2, (1000 + r.nextInt(80)) << 16);
        m.write32(s + 6, (500 + r.nextInt(80)) << 16);
        m.write16(s + 0x0a, r.nextInt(16));
        m.write16(s + 0x16, -r.nextInt(17));
        m.write16(s + 0x1a, 8 + r.nextInt(17));
        m.write32(s + 0x28, r.nextInt(0x40000) - 0x20000);
        m.write32(s + 0x2c, r.nextInt(0x40000) - 0x20000);
        m.write8(s + 0x6b, 0);
        m.write8(s + 0x9e, new int[]{0, 1, 0xff}[r.nextInt(3)]);
    }
}
