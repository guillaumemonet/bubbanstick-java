package bubba.engine;

import java.awt.Graphics2D;

/**
 * One screen of the game (title, options, map, level...).
 * Updated at a fixed 50 Hz (PAL frame rate, like the Amiga original) and drawn
 * on a 320x256 low-res buffer.
 */
public interface Scene {

    /** Called once when the scene becomes active. */
    default void enter(Game game) {
    }

    /** One logic update (every {@link #vblPerUpdate()} VBL). */
    void update(Game game, Input input);

    /**
     * Number of 50 Hz VBLs per logic update. The in-game loop of the original waits for two
     * frame interrupts per iteration (code 0x3E9A / 0x3F18), i.e. runs at 25 Hz.
     */
    default int vblPerUpdate() {
        return 1;
    }

    /** Draw on the 320x256 screen buffer. */
    void render(Game game, Graphics2D g);

    /** Called when the scene is replaced. */
    default void exit(Game game) {
    }
}
