package bubba.dev;

import bubba.cpu.GameMemory;
import bubba.emu.GameEmulator;
import bubba.engine.Assets;
import bubba.level.LevelData;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Dev tool (reverse engineering only): runs the original VBL handler 0x4CB8 after the level start
 * with the parallax files loaded where the loader puts them, and prints the copper lines of the
 * "copper sprite" hills ($605C4...) and the parallax state, while the camera moves.
 * Usage: ParallaxProbe <assets> <stage> [frames]
 */
public final class ParallaxProbe {

    private ParallaxProbe() {
    }

    public static void main(String[] args) throws Exception {
        Assets assets = new Assets(Paths.get(args[0]));
        int stage = Integer.parseInt(args[1]);
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 60;
        LevelData level = LevelData.stage(assets, stage);
        GameEmulator emu = new GameEmulator(assets, level, 3, 6, null);
        GameMemory m = emu.ram;
        load(m, args[0] + "/levels/stage" + stage + "_mparasp.bin", 0x64a40);
        load(m, args[0] + "/levels/stage" + stage + "_mplxobj.bin", 0x66030);
        load(m, args[0] + "/levels/stage" + stage + "_fgplaxa.bin", 0x66c30);
        load(m, args[0] + "/levels/stage" + stage + "_fgplaxb.bin", 0x6b8be);
        // level start 0x6B6C: mparasp inverted, hill tile list from info+$E4
        for (int i = 0; i <= 0xa5f; i++) {
            m.write16(0x64a40 + 2 * i, ~m.read16(0x64a40 + 2 * i));
        }
        int info = m.read32(0x8142);
        int list = info + m.read16(info + 0xe4);
        for (int i = 0; i <= 0x52; i++) {
            m.write16(0x8d80 + 2 * i, m.read16(list + 2 * i));
        }
        m.write32(0x6e14, 0x66030);
        try {
            emu.cpu.call(0x91a0, 2_000_000);
            System.out.println("0x91A0 ok: objects " + m.read16(0x935e) + " animated " + m.read16(0x9528));
        } catch (RuntimeException e) {
            System.out.println("0x91A0: " + e.getMessage());
        }
        for (int f = 0; f < frames; f++) {
            emu.frame(f < 10 ? 0 : 8);
            for (int v = 0; v < 2; v++) {
                try {
                    emu.cpu.a[7] = GameMemory.STACK_TOP - 0x400;
                    emu.cpu.call(0x4cb8, 2_000_000);
                } catch (RuntimeException e) {
                    System.out.println("frame " + f + " VBL: " + e.getMessage());
                    return;
                }
            }
            if (f % 10 == 0 || f == frames - 1) {
                System.out.printf("f%d cam %d,%d  51BC=%04X 51CA=%04X 8E2A=%04X 8E26=%04X %04X%n", f, emu.camX(), emu.camY(),
                        m.read16(0x51bc), m.read16(0x51ca), m.read16(0x8e2a), m.read16(0x8e26), m.read16(0x8e28));
                StringBuilder b = new StringBuilder();
                for (int a = 0x605c4; a < 0x605f0; a += 4) {
                    b.append(String.format(" %04X:%04X", m.read16(a), m.read16(a + 2)));
                }
                System.out.println("  head" + b);
                for (int line = 0; line < 34; line += 3) {
                    int a = 0x605e8 + line * 0xac;
                    System.out.printf("  line %2d wait %04X pos0 %04X data0 %04X data5 %04X col29 %04X%n", line, m.read16(a),
                            m.read16(a + 6), m.read16(a + 10), m.read16(a + 10 + 40), m.read16(a + 0x9e));
                }
                for (int k = 0; k < Math.min(4, m.read16(0x935e)); k++) {
                    int e = 0x9360 + 0x10 * k;
                    System.out.printf("  obj %d: %04X %04X %04X %04X %04X%n", k, m.read16(e), m.read16(e + 2),
                            m.read16(e + 4), m.read16(e + 6), m.read16(e + 8));
                }
            }
        }
    }

    private static void load(GameMemory m, String file, int at) throws Exception {
        if (!Files.exists(Paths.get(file))) {
            return;
        }
        byte[] b = Files.readAllBytes(Paths.get(file));
        for (int i = 0; i < b.length; i++) {
            m.write8(at + i, b[i]);
        }
    }
}
