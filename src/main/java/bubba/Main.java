package bubba;

import bubba.engine.Assets;
import bubba.engine.Game;
import bubba.scenes.TitleScene;

import javax.swing.SwingUtilities;

/**
 * Bubba 'n' Stix - Java remake.
 * <p>
 * Assets are read from the directory given by the {@code bubba.assets} system property
 * (default: {@code ../assets}), produced by {@code tools/extract_all.py}.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Assets assets = Assets.fromSystemProperty();
        Game game = new Game("Bubba 'n' Stix", assets);
        SwingUtilities.invokeLater(() -> {
            game.open();
            game.scenes().set(new TitleScene());
            game.start();
        });
    }
}
