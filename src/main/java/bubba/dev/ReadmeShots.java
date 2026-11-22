package bubba.dev;

import bubba.engine.Assets;
import bubba.engine.Game;
import bubba.engine.Input.Button;
import bubba.engine.Settings;
import bubba.scenes.LevelScene;
import bubba.scenes.TitleScene;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Paths;

/**
 * Screenshots of the real game screens (title, stages 1-5) rendered off screen while a joystick
 * script plays, x2 scaled. Usage: ReadmeShots &lt;assets&gt; &lt;out dir&gt; [script] where the
 * script is "ticks:buttons,..." (buttons among U D L R F, "-" for none); a shot is saved after
 * every step, named titleNN.png / stageS_NN.png.
 */
public final class ReadmeShots {

    private static final String SCRIPT = "100:-,60:R,30:RF,60:R,40:F,80:R,30:RU,60:R,40:L,60:R,30:RF,80:R,60:R,60:F,80:R,80:R";

    private ReadmeShots() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Settings.sound = Settings.Sound.SFX;
        Game game = new Game("shots", new Assets(Paths.get(args[0])));
        File out = new File(args[1]);
        out.mkdirs();
        String script = args.length > 2 ? args[2] : SCRIPT;

        game.scenes().set(new TitleScene());
        for (int i = 0; i < 6; i++) {
            run(game, 50, "-");
            save(game, new File(out, String.format("title%02d.png", i)));
        }
        for (int stage = 1; stage <= 5; stage++) {
            game.scenes().set(new LevelScene(stage));
            int n = 0;
            for (String part : script.split(",")) {
                String[] p = part.split(":");
                run(game, Integer.parseInt(p[0]), p[1]);
                save(game, new File(out, String.format("stage%d_%02d.png", stage, n++)));
            }
        }
        game.music().stop();
        System.exit(0);
    }

    private static void run(Game game, int ticks, String buttons) {
        for (Button b : new Button[]{Button.UP, Button.DOWN, Button.LEFT, Button.RIGHT, Button.FIRE}) {
            game.input().set(b, buttons.indexOf(b.name().charAt(0)) >= 0);
        }
        for (int i = 0; i < ticks; i++) {
            game.step();
        }
    }

    private static void save(Game game, File f) throws Exception {
        BufferedImage src = game.snapshot();
        BufferedImage img = new BufferedImage(src.getWidth() * 2, src.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, img.getWidth(), img.getHeight(), null);
        g.dispose();
        ImageIO.write(img, "png", f);
    }
}
