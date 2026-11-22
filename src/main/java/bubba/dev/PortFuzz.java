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
            if (routine >= 0xa318 && routine < 0xbea8) {
                setupHero(c, m, rnd, routine);
            } else if (routine >= 0x11000 && routine != 0x12c18) { // an object native: flags, held object
                int n = m.read16(0x17514);
                int o = n > 0 ? m.read32(0x17516 + 4 * rnd.nextInt(n)) : 0x17ed8;
                c.a[5] = o;
                c.a[4] = 0x12000 + 2 * rnd.nextInt(0x800);
                m.write8(0x1750b, rnd.nextInt(256));
                m.write32(0x17506, rnd.nextBoolean() ? o : 0);
                m.write32(0x1098a, (m.read16(o + 2) + rnd.nextInt(64) - 32) << 16);
                m.write32(0x1098e, (m.read16(o + 6) + rnd.nextInt(64) - 32) << 16);
            } else if (routine == 0x129ee) { // stage 5 callback: sequences
                m.write8(0x12d52, new int[]{0, 0, 1, 0x80}[rnd.nextInt(4)]);
                m.write8(0x12d53, new int[]{0, 1, 0x80}[rnd.nextInt(3)]);
                m.write8(0x12d54, new int[]{0, 1, 0x80}[rnd.nextInt(3)]);
                m.write8(0x12d55, new int[]{0, 1, 0x80}[rnd.nextInt(3)]);
                m.write8(0x12d57, rnd.nextInt(4) == 0 ? 1 : 0);
                m.write8(0x12cc4 + 0x91, rnd.nextInt(3));
                m.write8(0x12c4e + 0x105, rnd.nextInt(3));
                m.write8(0x12c32 + 0x122, rnd.nextInt(3));
                m.write16(0x12b1a, rnd.nextInt(3));
                m.write16(0x10912, rnd.nextInt(0x1000));
                c.a[0] = 0xc750 + 2 + 0x16;
            } else if (routine == 0x12c18) { // stage 2 callback: map switch requested or not
                m.write8(0x10ad9, rnd.nextInt(2));
                m.write8(0x12c57, rnd.nextInt(256));
                c.a[0] = 0xc750 + 2 + 0x16;
            } else if (routine == 0xbea8) {
                setupStix(m, rnd);
            } else {
                setup(m, rnd);
            }
            byte[] before = m.snapshot();
            int[] setRegs = c.saveRegisters();
            try {
                c.callNested(routine, 200_000, true);
            } catch (IllegalStateException e) {
                skipped++; // the random state loops the original too (carrier cycle)
                continue;
            }
            byte[] orig = m.snapshot();
            int[] origRegs = c.saveRegisters();
            m.restore(before);
            c.restoreRegisters(setRegs);
            Ports.runJava(c, routine);
            byte[] mine = m.snapshot();
            int[] mineRegs = c.saveRegisters();
            if (Boolean.getBoolean("fuzz.regs")) {
                for (int r = 0; r < 15; r++) {
                    if (r != 7 && origRegs[r] != mineRegs[r]) {
                        if (bad < 10) {
                            System.out.printf("trial %d: register %d original %X java %X%n", t, r, origRegs[r], mineRegs[r]);
                        }
                        bad++;
                        break;
                    }
                }
            }
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

    /** Bubba's state handlers: random joystick (and condition codes), sub-state, hero fields. */
    private static void setupHero(M68k c, GameMemory m, Random r, int routine) {
        int h = 0x10910;
        int joy = r.nextInt(256);
        m.write8(0x8269, joy);
        c.d[0] = (c.d[0] & 0xffffff00) | joy;
        c.setCcr(joy & 0x1f);
        c.d[1] = r.nextInt(0x1a) * 2;
        c.a[0] = 0xbda6;
        c.a[1] = h;
        for (int i = 0; i < 0x1a; i++) {
            if (r.nextInt(3) == 0) {
                m.write8(0xbda6 + i, r.nextInt(256));
            }
        }
        m.write16(0xbda6, r.nextInt(0x50) - 0x28);
        m.write8(0xbda8, r.nextBoolean() ? 0xff : 0);
        m.write8(0xbda9, r.nextInt(5));
        m.write8(h + 0x4c, r.nextInt(256));
        m.write8(h + 0x55, r.nextBoolean() ? 0xff : 0);
        m.write8(h + 0x56, r.nextBoolean() ? 0xff : 0);
        m.write8(h + 0x57, r.nextBoolean() ? 0xff : 0);
        m.write8(h + 0x72, r.nextBoolean() ? 0xff : 0);
        m.write32(h + 0x28, r.nextInt(0x100000) - 0x80000);
        m.write32(h + 0x2c, r.nextInt(0x100000) - 0x80000);
        m.write16(h + 0x3a, r.nextInt(3) == 0 ? 5 : 0);
        m.write8(0xcad9, r.nextInt(256));
        m.write8(0x10ad5, new int[]{0, 1, 0xff}[r.nextInt(3)]);
        m.write8(0x10ad2, r.nextInt(3));
        m.write8(0x10acb, r.nextInt(5));
        m.write8(0x10acc, r.nextInt(2));
        m.write8(0x10acd, r.nextInt(2));
        m.write8(h + 0x1bb, r.nextInt(5));
        m.write16(0x42a8, r.nextInt(3) - 1);
        m.write8(0x11138, r.nextInt(2));
        m.write8(0x1112e, r.nextInt(4) == 0 ? 1 : 0);
        m.write8(0x1112f, r.nextInt(2));
        m.write32(0xbdc6, new int[]{0xb966, 0xbd28, 0xbd2c}[r.nextInt(3)]);
        m.write16(h, r.nextInt(0x14));
        m.write16(0x10ab4, 0);
    }

    /** Stix (0xBEA8): random state, timers, speeds, carrier, near Bubba or an enemy, or far. */
    private static void setupStix(GameMemory m, Random r) {
        int s = 0x10988, h = 0x10910;
        int n = m.read16(0x17514);
        int[] objs = new int[n];
        for (int i = 0; i < n; i++) {
            objs[i] = m.read32(0x17516 + 4 * i);
            if (r.nextInt(3) == 0) {
                m.write8(objs[i] + 0x9d, r.nextInt(4));
                m.write16(objs[i] + 0x3a, r.nextInt(4) == 0 ? 5 : 0);
            }
        }
        int[] states = {0, 2, 4, 6, 8, 0xa, 0xc};
        m.write16(s + 0x9c, states[r.nextInt(states.length)]);
        int x, y;
        int where = r.nextInt(4);
        if (where == 0 && n > 0) {
            int o = objs[r.nextInt(n)];
            x = (short) m.read16(o + 2) + r.nextInt(25) - 12;
            y = (short) m.read16(o + 6) + r.nextInt(25) - 12;
        } else if (where == 1) {
            x = (short) m.read16(h + 2) + r.nextInt(1200) - 600;
            y = (short) m.read16(h + 6) + r.nextInt(800) - 400;
        } else {
            x = (short) m.read16(h + 2) + r.nextInt(97) - 48;
            y = (short) m.read16(h + 6) + r.nextInt(97) - 48;
        }
        m.write32(s + 2, x << 16 | r.nextInt(0x10000));
        m.write32(s + 6, y << 16 | r.nextInt(0x10000));
        m.write16(s + 0x34, r.nextInt(17) - 6);
        m.write16(s + 0x4e, r.nextInt(12) - 1);
        m.write32(s + 0x28, r.nextInt(0x180000) - 0xc0000);
        m.write32(s + 0x2c, r.nextInt(0x180000) - 0xc0000);
        int carrier = n > 0 ? objs[r.nextInt(n)] : 0;
        if (carrier != 0) {
            m.write8(carrier + 0x9c, r.nextInt(2));
        }
        int[] p44 = {0, s + 0x28, s + 0x2c, carrier};
        m.write32(s + 0x44, p44[r.nextInt(4)]);
        m.write32(0xbf14, r.nextBoolean() ? 0 : carrier);
        m.write32(s + 0xc, (r.nextBoolean() ? 0xbfb4 : 0xbfc4) + 2 * r.nextInt(8));
        m.write16(s + 0xa, r.nextInt(16));
        m.write8(s + 0x30, r.nextBoolean() ? 0xff : 0);
        m.write8(0x1750b, r.nextInt(8));
        m.write8(0xbdd7, r.nextBoolean() ? 0xff : 0);
        m.write16(0xbdd2, r.nextInt(0x10000));
        m.write16(0xbdd4, r.nextInt(0x10000));
        m.write16(0x7ef8, r.nextInt(3) == 0 ? 2 : 0);
        m.write16(0xbda4, new int[]{0, 2, 0x14, 0x26, 0x34}[r.nextInt(5)]);
        m.write8(h + 0x55, r.nextBoolean() ? 0x80 : 0);
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
