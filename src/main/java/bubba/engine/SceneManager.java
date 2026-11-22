package bubba.engine;

/**
 * Holds the active scene. Scene changes requested during an update are applied
 * at the start of the next tick so a scene never runs after its exit().
 */
public final class SceneManager {

    private final Game game;
    private Scene current;
    private Scene pending;

    SceneManager(Game game) {
        this.game = game;
    }

    /** Switch to another scene (applied before the next update). */
    public void set(Scene next) {
        pending = next;
    }

    public Scene current() {
        return current;
    }

    void applyPending() {
        if (pending == null) {
            return;
        }
        if (current != null) {
            current.exit(game);
        }
        current = pending;
        pending = null;
        current.enter(game);
    }
}
