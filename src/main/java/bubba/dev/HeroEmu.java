package bubba.dev;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;
import bubba.engine.Assets;
import bubba.level.LevelData;
import bubba.level.LevelMemory;

import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;

/**
 * Experiment: run the original main loop (code 0x3AF0, one turn = one logic frame) on the 68000
 * interpreter, after the end of the level setup (0x6C18), with a scripted joystick.
 * Usage: HeroEmu <assets> <stage> [frames].
 */
public final class HeroEmu {

    private static final int INFO = 0xc0000;

    private HeroEmu() {
    }

    public static void main(String[] args) throws Exception {
        Assets assets = new Assets(Paths.get(args[0]));
        int stage = Integer.parseInt(args[1]);
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 300;
        LevelData level = LevelData.stage(assets, stage);
        GameMemory ram = new GameMemory(assets);
        ram.loadSet(level.setImage());
        LevelMemory.build(ram, level.info(), level.rawMap());
        M68k cpu = new M68k(ram);
        Map<Integer, Integer> sounds = new TreeMap<>();
        cpu.hook(0x7bc8, c -> sounds.merge(c.d[0] & 0xff, 1, Integer::sum));
        cpu.hook(0x7bca, c -> { });
        cpu.hook(0x8470, c -> { });          // display: done in Java
        cpu.hook(0x79ce, c -> c.z = true);   // keyboard: no key
        ram.write16(0x3b2c, 0x4e71);         // VBL waits -> nop
        ram.write16(0x3cd0, 0x4e71);
        cpu.hook(0x539e, c -> { });          // hero sprite display: done in Java
        // level data as the loader leaves it: info file at (L_08142), raw map at (L_10888)
        byte[] info = level.info();
        for (int i = 0; i < info.length; i++) {
            ram.write8(INFO + i, info[i]);
        }
        ram.write32(0x8142, INFO);
        // buffers allocated at boot (code 0x6060..0x6186), placed in spare memory
        ram.write32(0x8282, 0xd0002);        // two screens
        ram.write32(0x8286, 0xe0002);
        ram.write32(0x1bada, 0xb0000);       // 0x10000
        ram.write32(0x1bac2, 0xb0000);
        ram.write32(0x82a6, 0xc2000);        // 0x17d8
        ram.write32(0x82ae, LevelMemory.MAP);
        ram.write32(0x935a, 0xc4000);        // 0x3840
        ram.write32(0x82aa, 0xc8008);        // 0xc00
        ram.write32(0x174fe, 0xc9000);       // 0x1c00
        byte[] trg = level.triggers();       // object records at (L_082AA)
        for (int i = 0; i < trg.length; i++) {
            ram.write8(0xc8008 + i, trg[i]);
        }
        cpu.a[7] = GameMemory.STACK_TOP;
        int[] ring = new int[256];
        int[] st = {0, -1};
        cpu.trace = pc -> {
            if (st[1] >= 0 && (pc < st[1] || pc > st[1] + 10) && (pc & 0xff000000) == 0) {
                long key = ((long) st[1] << 32) | pc;
                int k = st[0] & 255;
                int prevIdx = (st[0] - 1) & 255;
                if (st[0] == 0 || ring[prevIdx] != pc) {
                    ring[k] = pc;
                    st[0]++;
                }
            }
            st[1] = pc;
        };
        try {
            levelVariables(ram, info);
            ram.write16(0x108bc, 3);         // new game (0x3FA4): 3 lives, energy 6
            ram.write16(0x108be, 6);
            cpu.call(0x6c18, 50_000_000);
            cpu.call(0x8638, 1_000_000);     // status bar init (0x3A38)
        } catch (RuntimeException e) {
            System.out.println("init: " + e.getMessage());
            StringBuilder b2 = new StringBuilder();
            for (int i = Math.max(0, st[0] - 256); i < st[0]; i++) {
                b2.append(String.format(" %05X", ring[i & 255]));
            }
            System.out.println("flow:" + b2);
        }
        cpu.trace = null;
        dump(ram, -1, 0);
        for (int f = 0; f < frames; f++) {
            int joy = f < 25 ? 0 : f < 100 ? 8 : f < 104 ? 9 : f < 140 ? 8 : f < 150 ? 0x80 : f < 200 ? 0 : f < 210 ? 0x84 : 0;
            ram.write8(0x8269, joy);
            cpu.a[7] = GameMemory.STACK_TOP - 0x100;
            try {
                cpu.runUntil(0x3af0, 0x3af0, 5_000_000);
            } catch (RuntimeException e) {
                System.out.println("frame " + f + ": " + e.getMessage());
                break;
            }
            if (f % 10 == 0 || (joy & 0x80) != 0) {
                dump(ram, f, joy);
            }
        }
        System.out.println("sounds " + sounds);
    }

    /** The logic part of the level start 0x65BA..0x66E2 (the rest prepares the display). */
    static void levelVariables(GameMemory ram, byte[] info) {
        java.util.function.IntUnaryOperator u16 = o -> ((info[o] & 0xff) << 8) | (info[o + 1] & 0xff);
        ram.write16(0x178c6, u16.applyAsInt(0x10));
        int w = u16.applyAsInt(0), h = u16.applyAsInt(2);
        // camera limits (0x6614..0x665E)
        ram.write32(0x108fa, 0);
        ram.write32(0x10902, ((w - 0x12) << 4) << 16);
        ram.write32(0x108da, (((w - 0x12) << 4) << 16) + 0x1100000);
        ram.write16(0x10a46, h);
        ram.write32(0x108fe, 0);
        ram.write32(0x10906, ((h - 0x0e) << 4) << 16);
        ram.write32(0x108de, (((h - 0x0e) << 4) << 16) + 0x900000);
        // start position, water level, flags (0x66A2..0x66E2)
        ram.write32(0x108ee, u16.applyAsInt(6) << 16);
        ram.write32(0x108f2, u16.applyAsInt(8) << 16);
        ram.write8(0x108f6, 0);
        ram.write16(0x108f8, ram.read16(0x10a50));
        ram.write8(0x10910 + 0x54, 0);
        ram.write8(0x10910 + 0x55, 0);
        ram.write8(0x10910 + 0x65, 0);
        ram.write16(0x10b14, u16.applyAsInt(0x0e));
        ram.write16(0x10a42, 0);
    }

    private static void dump(GameMemory ram, int f, int joy) {
        int h = 0x10910, s = 0x10988;
        System.out.printf("f%4d joy %02X hero %5d,%5d frame %3d state %3d | stix %5d,%5d spr %04X | cam %d,%d objs %d energy %d lives %d org %d,%d%n",
                f, joy, (short) ram.read16(h + 2), (short) ram.read16(h + 6), ram.read16(h + 0x50), ram.read16(0xbda4),
                (short) ram.read16(s + 2), (short) ram.read16(s + 6), ram.read16(s + 0x0a),
                (short) ram.read16(0x108ca), (short) ram.read16(0x108ce), ram.read16(0x17514), ram.read16(0x108be), ram.read16(0x108bc), (short) ram.read16(0x829a), (short) ram.read16(0x829c));
    }
}
