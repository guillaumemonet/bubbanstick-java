package bubba;

import bubba.engine.Assets;
import bubba.engine.Game;
import bubba.extract.AssetSetup;
import bubba.scenes.TitleScene;

import javax.swing.SwingUtilities;

/**
 * Bubba 'n' Stix - Java remake.
 * <p>
 * The game data is extracted from the original disks on the first launch (see
 * {@link AssetSetup}); {@code -Dbubba.assets=<dir>} uses an existing assets directory.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        java.nio.file.Path dir;
        try {
            dir = AssetSetup.resolve();
        } catch (Exception e) {
            e.printStackTrace();
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                javax.swing.JOptionPane.showMessageDialog(null, "Impossible de préparer les données du jeu :\n" + e,
                        "Bubba 'n' Stix", javax.swing.JOptionPane.ERROR_MESSAGE);
            }
            System.exit(1);
            return;
        }
        if (dir == null) {
            System.exit(0);
            return;
        }
        Assets assets = new Assets(dir);
        Game game = new Game("Bubba 'n' Stix", assets);
        SwingUtilities.invokeLater(() -> {
            game.open();
            int stage = Integer.getInteger("bubba.stage", 0); // gradlew run -Pstage=5
            game.scenes().set(stage >= 1 && stage <= 5 ? new bubba.scenes.LevelScene(stage) : new TitleScene());
            game.start();
        });
    }
}
