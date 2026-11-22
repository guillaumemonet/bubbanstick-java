package bubba.engine;

import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

/**
 * Keyboard mapped to the original one-button joystick:
 * arrows / WASD = directions, Space / Ctrl / Enter = fire, Esc = cancel.
 * <p>
 * Key events arrive on the AWT thread; {@link #poll()} snapshots them once per tick so
 * "pressed this frame" is stable during an update.
 */
public final class Input extends KeyAdapter {

    public enum Button { UP, DOWN, LEFT, RIGHT, FIRE, CANCEL, PAUSE, NEXT, DEBUG }

    private final boolean[] live = new boolean[Button.values().length];
    private final boolean[] latched = new boolean[Button.values().length];
    private final boolean[] down = new boolean[Button.values().length];
    private final boolean[] prev = new boolean[Button.values().length];

    @Override
    public void keyPressed(KeyEvent e) {
        Button b = map(e.getKeyCode());
        if (b != null) {
            synchronized (this) {
                live[b.ordinal()] = true;
                latched[b.ordinal()] = true;
            }
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        Button b = map(e.getKeyCode());
        if (b != null) {
            synchronized (this) {
                live[b.ordinal()] = false;
            }
        }
    }

    /** Take a snapshot for this tick (called by the game loop). */
    synchronized void poll() {
        for (int i = 0; i < down.length; i++) {
            prev[i] = down[i];
            // a press shorter than one tick still counts once
            down[i] = live[i] || latched[i];
            latched[i] = false;
        }
    }

    public boolean isDown(Button b) {
        return down[b.ordinal()];
    }

    /** True only on the tick the button went down. */
    public boolean pressed(Button b) {
        return down[b.ordinal()] && !prev[b.ordinal()];
    }

    private static Button map(int code) {
        return switch (code) {
            case KeyEvent.VK_UP, KeyEvent.VK_W, KeyEvent.VK_Z -> Button.UP;
            case KeyEvent.VK_DOWN, KeyEvent.VK_S -> Button.DOWN;
            case KeyEvent.VK_LEFT, KeyEvent.VK_A, KeyEvent.VK_Q -> Button.LEFT;
            case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> Button.RIGHT;
            case KeyEvent.VK_SPACE, KeyEvent.VK_CONTROL, KeyEvent.VK_ENTER -> Button.FIRE;
            case KeyEvent.VK_ESCAPE -> Button.CANCEL;
            case KeyEvent.VK_P -> Button.PAUSE;
            case KeyEvent.VK_N -> Button.NEXT;
            case KeyEvent.VK_B -> Button.DEBUG;
            default -> null;
        };
    }
}
