package bubba.dev;

import bubba.cpu.GameMemory;
import bubba.emu.GameEmulator;
import bubba.engine.Assets;
import bubba.entity.HeroSprites;
import bubba.entity.SpriteBank;
import bubba.level.LevelData;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Paths;

/**
 * Headless check of the emulated game: plays a joystick script and saves screenshots of the play
 * window (same drawing as LevelScene, x3 scaled, hitboxes in yellow / cyan).
 * Usage: EmuShot <assets> <stage> <out dir> <script> where script = "frames:joy,..." (joy hex),
 * a shot is saved at the end of every step.
 */
public final class EmuShot {

    private EmuShot() {
    }

    public static void main(String[] args) throws Exception {
        Assets assets = new Assets(Paths.get(args[0]));
        LevelData level = LevelData.stage(assets, Integer.parseInt(args[1]));
        File out = new File(args[2]);
        out.mkdirs();
        GameEmulator emu = new GameEmulator(assets, level, 3, 6, null);
        java.util.Map<Integer, Integer> sounds = new java.util.TreeMap<>();
        emu.onSound = n -> sounds.merge(n, 1, Integer::sum);
        SpriteBank sprites = new SpriteBank(assets, level.spritesDir());
        HeroSprites hero = new HeroSprites(assets);
        int step = 0;
        for (String part : args[3].split(",")) {
            String[] p = part.split(":");
            if (p[0].equals("t")) { // test: put Bubba on the first active item (+$83 bit 3)
                GameMemory m = emu.ram;
                int cnt = m.read16(0x17514);
                for (int i = 0; i < cnt; i++) {
                    int o = m.read32(0x17516 + 4 * i);
                    if ((m.read8(o + 0x83) & 8) != 0) {
                        System.out.printf("teleport on item type %d at %d,%d%n", m.read16(o + 0x5c) + 1,
                                (short) m.read16(o + 2), (short) m.read16(o + 6));
                        m.write16(GameEmulator.HERO + 2, m.read16(o + 2));
                        m.write16(GameEmulator.HERO + 6, m.read16(o + 6) + 20);
                        break;
                    }
                }
                continue;
            }
            int n = Integer.parseInt(p[0]), joy = Integer.parseInt(p[1], 16);
            for (int i = 0; i < n; i++) {
                emu.frame(joy);
            }
            GameMemory m = emu.ram;
            int h = GameEmulator.HERO, s = GameEmulator.STIX;
            System.out.printf("step %d: hero %d,%d image %d state %d | stix %d,%d spr %d vis %d | energy %d particles %d score %02X%02X%n", step,
                    (short) m.read16(h + 2), (short) m.read16(h + 6), m.read16(h + 0x50), m.read16(0xbda4),
                    (short) m.read16(s + 2), (short) m.read16(s + 6), m.read16(s + 0x0a), (short) m.read16(s + 0x9c),
                    emu.energy(), m.read16(0x175d8), m.read8(0x10ab1), m.read8(0x10ab2));
            for (int i = 0; i < m.read16(0x175d8); i++) {
                int p2 = m.read32(0x175da + 4 * i);
                System.out.printf("   particle $%X spr %04X at %d,%d (sprite exists: %b)%n", p2, m.read16(p2 + 0x12),
                        (short) m.read16(p2 + 0x38), (short) m.read16(p2 + 0x3c), sprites.has(m.read16(p2 + 0x12) >> 1));
            }
            ImageIO.write(render(emu, level, sprites, hero), "png", new File(out, String.format("shot_%02d.png", step++)));
        }
        System.out.println("sounds " + sounds);
        if (bubba.game.Ports.VERIFY) {
            bubba.game.Ports.STATS.forEach((a, st) -> System.out.printf("port $%05X: %d calls, %d different%n", a, st[0], st[1]));
        }
    }

    static BufferedImage render(GameEmulator emu, LevelData level, SpriteBank sprites, HeroSprites heroSprites) {
        BufferedImage img = new BufferedImage(284, 208, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        GameMemory m = emu.ram;
        int cx = emu.viewX() + 3, cy = emu.viewY();
        level.drawSky(g, 284, 208);
        try {
            new bubba.level.Parallax(new Assets(java.nio.file.Paths.get(System.getProperty("bubba.assets", "../assets"))), level)
                    .draw(g, cx, 284, emu.camX());
        } catch (RuntimeException e) {
            // no parallax
        }
        level.draw(g, cx, cy, 284, 208, emu::blockAt);
        cy = emu.bobY();
        int np = m.read16(0x175d8);
        for (int i = 0; i < np; i++) {
            int p = m.read32(0x175da + 4 * i);
            if (m.read16(p + 0x12) != 0) {
                sprites.draw(g, m.read16(p + 0x12) >> 1, (short) m.read16(p + 0x38) - cx, (short) m.read16(p + 0x3c) - cy);
            }
        }
        for (int list : new int[]{0x17596, 0x17514}) {
            int n = m.read16(list);
            for (int i = 0; i < n; i++) {
                int o = m.read32(list + 2 + 4 * i);
                int sp = m.read16(o + 0x0a);
                int x = (short) m.read16(o + 2) - cx, y = (short) m.read16(o + 6) - cy;
                if (sp < 0xfffe) {
                    sprites.draw(g, (sp + m.read16(o + 0xa0)) & 0xffff, x, y);
                }
                g.setColor(Color.YELLOW);
                g.drawRect(x + (short) m.read16(o + 0x14), y + (short) m.read16(o + 0x16),
                        Math.max(1, (short) m.read16(o + 0x18)), Math.max(1, (short) m.read16(o + 0x1a)));
            }
        }
        int s = GameEmulator.STIX;
        if ((short) m.read16(s + 0x9c) >= 0 && m.read8(0x10966) == 0) {
            sprites.draw(g, 2 * m.read16(s + 0x0a), (short) m.read16(s + 2) - cx, (short) m.read16(s + 6) - cy);
        }
        try {
            new bubba.level.ForegroundParallax(new Assets(java.nio.file.Paths.get(System.getProperty("bubba.assets", "../assets"))), level)
                    .draw(g, emu.camX(), emu.camY(), 284, true, true);
        } catch (RuntimeException e) {
            // no foreground
        }
        int h = GameEmulator.HERO;
        int hx = (short) m.read16(h + 2) - emu.camX() - 3, hy = (short) m.read16(h + 6) - emu.camY();
        heroSprites.draw(g, m.read16(h + 0x50), hx, hy, (byte) m.read8(h + 0x55) < 0);
        g.setColor(Color.CYAN);
        g.drawRect(hx + (short) m.read16(h + 0x14), hy + (short) m.read16(h + 0x16),
                (short) m.read16(h + 0x18), (short) m.read16(h + 0x1a));
        new bubba.emu.StatusBar().draw(g, m, 8);
        g.dispose();
        BufferedImage big = new BufferedImage(284 * 3, 208 * 3, BufferedImage.TYPE_INT_RGB);
        Graphics2D b = big.createGraphics();
        b.drawImage(img, 0, 0, 284 * 3, 208 * 3, null);
        b.dispose();
        return big;
    }
}
