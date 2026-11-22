package bubba.engine;

import bubba.audio.ModPlayer;
import bubba.audio.Sfx;

import javax.swing.JFrame;
import javax.swing.WindowConstants;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;

/**
 * Window + fixed-rate game loop.
 * <p>
 * The game logic runs at 50 Hz (Amiga PAL vertical blank) on a dedicated thread and draws
 * into a 320x256 buffer, which is scaled to the window with nearest-neighbour filtering.
 * Keys: F11 toggles fullscreen, F1-F4 set the window scale.
 */
public final class Game {

    public static final int WIDTH = 320;
    public static final int HEIGHT = 256;
    public static final int FPS = 50;

    private final String title;
    private final Assets assets;
    private final Input input = new Input();
    private final SceneManager scenes = new SceneManager(this);
    private final ModPlayer music = new ModPlayer();
    private final Sfx sfx;
    private final BufferedImage screen = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);

    private JFrame frame;
    private Canvas canvas;
    private volatile boolean running;
    private long ticks;
    private boolean fullscreen;

    public Game(String title, Assets assets) {
        this.title = title;
        this.assets = assets;
        this.sfx = new Sfx(assets);
    }

    public Assets assets() {
        return assets;
    }

    public SceneManager scenes() {
        return scenes;
    }

    public ModPlayer music() {
        return music;
    }

    public Sfx sfx() {
        return sfx;
    }

    /** Number of 50 Hz ticks since start. */
    public long ticks() {
        return ticks;
    }

    /** Create the window (must be called on the EDT). */
    public void open() {
        canvas = new Canvas();
        canvas.setBackground(Color.BLACK);
        canvas.setPreferredSize(new Dimension(WIDTH * 3, HEIGHT * 3));
        canvas.setIgnoreRepaint(true);
        canvas.addKeyListener(input);
        canvas.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_F11 -> toggleFullscreen();
                    case KeyEvent.VK_F1 -> setScale(1);
                    case KeyEvent.VK_F2 -> setScale(2);
                    case KeyEvent.VK_F3 -> setScale(3);
                    case KeyEvent.VK_F4 -> setScale(4);
                    default -> { }
                }
            }
        });

        frame = new JFrame(title);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                quit();
            }
        });
        frame.add(canvas);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        canvas.createBufferStrategy(2);
        canvas.requestFocus();
    }

    public void start() {
        running = true;
        Thread loop = new Thread(this::loop, "game-loop");
        loop.setDaemon(false);
        loop.start();
    }

    public void quit() {
        running = false;
    }

    private void loop() {
        final long step = 1_000_000_000L / FPS;
        long next = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            if (now - next > step * 10) {
                next = now; // we fell far behind (debugger, sleep): don't fast-forward
            }
            int updates = 0;
            while (now >= next && updates < 5) {
                tick();
                next += step;
                updates++;
            }
            if (updates > 0) {
                present();
            }
            long sleep = (next - System.nanoTime()) / 1_000_000L;
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        music.stop();
        if (frame != null) {
            frame.dispose();
        }
        System.exit(0);
    }

    private void tick() {
        scenes.applyPending();
        Scene s = scenes.current();
        // input is sampled only when the scene updates: presses shorter than an update are latched
        if (s != null && ticks % Math.max(1, s.vblPerUpdate()) == 0) {
            input.poll();
            s.update(this, input);
        }
        ticks++;
    }

    private void present() {
        Graphics2D g = screen.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            Scene s = scenes.current();
            if (s != null) {
                s.render(this, g);
            }
        } finally {
            g.dispose();
        }
        BufferStrategy bs = canvas.getBufferStrategy();
        if (bs == null) {
            return;
        }
        do {
            do {
                Graphics2D wg = (Graphics2D) bs.getDrawGraphics();
                try {
                    int cw = canvas.getWidth();
                    int ch = canvas.getHeight();
                    // keep the 5:4 low-res aspect with integer-free scaling, letterboxed
                    double scale = Math.min(cw / (double) WIDTH, ch / (double) HEIGHT);
                    int w = (int) (WIDTH * scale);
                    int h = (int) (HEIGHT * scale);
                    int x = (cw - w) / 2;
                    int y = (ch - h) / 2;
                    wg.setColor(Color.BLACK);
                    wg.fillRect(0, 0, cw, ch);
                    wg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    wg.drawImage(screen, x, y, w, h, null);
                } finally {
                    wg.dispose();
                }
            } while (bs.contentsRestored());
            bs.show();
        } while (bs.contentsLost());
    }

    private void setScale(int s) {
        if (fullscreen) {
            return;
        }
        java.awt.EventQueue.invokeLater(() -> {
            canvas.setPreferredSize(new Dimension(WIDTH * s, HEIGHT * s));
            frame.pack();
        });
    }

    private void toggleFullscreen() {
        java.awt.EventQueue.invokeLater(() -> {
            fullscreen = !fullscreen;
            frame.dispose();
            frame.setUndecorated(fullscreen);
            if (fullscreen) {
                frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            } else {
                frame.setExtendedState(JFrame.NORMAL);
                frame.pack();
                frame.setLocationRelativeTo(null);
            }
            frame.setVisible(true);
            canvas.requestFocus();
        });
    }
}
