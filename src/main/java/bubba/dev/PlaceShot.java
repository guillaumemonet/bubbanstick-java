package bubba.dev;

import bubba.emu.GameEmulator;
import bubba.engine.Assets;
import bubba.entity.HeroSprites;
import bubba.entity.SpriteBank;
import bubba.level.LevelData;
import bubba.level.ObjectPlacement;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Paths;
import java.util.Random;

/**
 * Dev tool: lists the object placements of a stage, or puts Bubba next to one placement and
 * saves screenshots while he plays randomly (invincible).
 * Usage: PlaceShot &lt;assets&gt; &lt;stage&gt; [placement index] [frames] [shot every] [out dir]
 */
public final class PlaceShot {

    private PlaceShot() {
    }

    public static void main(String[] args) throws Exception {
        Assets assets = new Assets(Paths.get(args[0]));
        LevelData level = LevelData.stage(assets, Integer.parseInt(args[1]));
        java.util.List<ObjectPlacement> list = level.placements();
        if (args.length < 3) {
            for (int i = 0; i < list.size(); i++) {
                ObjectPlacement p = list.get(i);
                System.out.printf("%3d type %3d at %5d,%5d%n", i, p.type(), p.x(), p.y());
            }
            return;
        }
        ObjectPlacement p = list.get(Integer.parseInt(args[2]));
        int frames = args.length > 3 ? Integer.parseInt(args[3]) : 600;
        int every = args.length > 4 ? Integer.parseInt(args[4]) : 50;
        File out = new File(args.length > 5 ? args[5] : "shots");
        out.mkdirs();
        GameEmulator emu = new GameEmulator(assets, level, 9, 6, null);
        for (int f = 0; f < 30; f++) {
            emu.frame(0);
        }
        int dx = Integer.getInteger("shot.dx", -40);
        emu.ram.write16(GameEmulator.HERO + 2, p.x() + dx);
        emu.ram.write16(GameEmulator.HERO + 6, p.y() - 8);
        SpriteBank sprites = new SpriteBank(assets, level.spritesDir(), level.basePalette());
        sprites.redirections(emu.ram);
        HeroSprites hero = new HeroSprites(assets);
        Random r = new Random(1);
        int[] choices = {0, 8, 4, 9, 5, 0x80, 0x88, 0x84, 1, 0x89, 0x85};
        int joy = 0;
        for (int f = 1; f <= frames; f++) {
            if (f % 12 == 0) {
                joy = choices[r.nextInt(choices.length)];
            }
            if (!emu.frame(joy)) {
                System.out.println("stage ended at frame " + f);
                ImageIO.write(EmuShot.render(emu, level, sprites, hero), "png", new File(out, String.format("f%05d.png", f)));
                break;
            }
            if (f % every == 0) {
                ImageIO.write(EmuShot.render(emu, level, sprites, hero), "png", new File(out, String.format("f%05d.png", f)));
            }
        }
        System.exit(0);
    }
}
