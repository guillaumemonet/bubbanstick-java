package bubba.dev;

import bubba.engine.Assets;
import bubba.engine.Game;
import bubba.scenes.EndingScene;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Paths;

/**
 * Dev tool: runs the end sequence headless and saves a screenshot every n VBLs.
 * Usage: EndingShots &lt;assets&gt; &lt;out dir&gt; [every] [max VBLs]
 */
public final class EndingShots {

    private EndingShots() {
    }

    public static void main(String[] args) throws Exception {
        Game game = new Game("test", new Assets(Paths.get(args[0])));
        File out = new File(args[1]);
        out.mkdirs();
        int every = args.length > 2 ? Integer.parseInt(args[2]) : 100;
        int max = args.length > 3 ? Integer.parseInt(args[3]) : 6000;
        EndingScene s = new EndingScene();
        s.enter(game);
        bubba.engine.Input input = new bubba.engine.Input();
        BufferedImage img = new BufferedImage(Game.WIDTH, Game.HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int v = 1; v <= max; v++) {
            s.update(game, input);
            if (v % every == 0) {
                Graphics2D g = img.createGraphics();
                s.render(game, g);
                g.dispose();
                ImageIO.write(img, "png", new File(out, String.format("v%05d.png", v)));
            }
        }
        System.exit(0);
    }
}
