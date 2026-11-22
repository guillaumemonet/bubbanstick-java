package bubba.scenes;

import bubba.cpu.GameMemory;
import bubba.emu.GameEmulator;
import bubba.engine.Game;
import bubba.engine.Input;
import bubba.engine.Input.Button;
import bubba.engine.Scene;
import bubba.entity.HeroSprites;
import bubba.entity.SpriteBank;
import bubba.level.LevelData;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;

/**
 * A stage, played by the original game logic running on the 68000 interpreter
 * ({@link GameEmulator}): Bubba, Stix, objects, camera, energy and lives are the original ones.
 * This scene feeds the joystick and draws the memory: copper sky, block map, BOBs, hero sprite.
 * <p>
 * Display window of the original (copper list $600D0: DIWSTRT $3094, DIWSTOP $00B0): 284 x 208
 * pixels, 19 pixels right of and 4 lines below a standard 320 x 256 PAL screen. Hero sprite:
 * screen x = x + $91 - camera x (hardware), i.e. x - camera x - 3 in the window; y - camera y.
 * <p>
 * Keys: arrows/WASD = joystick, Space/Ctrl/Enter = fire, P = pause (the original's: any key
 * goes on), Esc = title, N = next stage, B = debug overlay.
 */
public final class LevelScene implements Scene {

    private static final int VIEW_X = 19, VIEW_Y = 4, VIEW_W = 284, VIEW_H = 208;
    /** Hardware sprite / window offset: $91 - $94. */
    private static final int SCREEN_DX = -3;
    /** Space as the keyboard interrupt stores it (raw $40 inverted). */
    private static final int KEY_SPACE = 0xbf;

    private final int stage;
    private final int startLives, startEnergy;
    private final byte[] startScore;
    private LevelData level;
    private HeroSprites heroSprites;
    private SpriteBank sprites;
    private final bubba.emu.StatusBar statusBar = new bubba.emu.StatusBar();
    private GameEmulator emu;
    private bubba.level.Parallax parallax;
    private bubba.level.ForegroundParallax foreground;
    private boolean debug;
    private String failure;

    public LevelScene(int stage) {
        this(stage, 3, 6, null);
    }

    private LevelScene(int stage, int lives, int energy, byte[] score) {
        this.stage = stage;
        this.startLives = lives;
        this.startEnergy = energy;
        this.startScore = score;
    }

    @Override
    public void enter(Game game) {
        level = LevelData.stage(game.assets(), stage);
        heroSprites = new HeroSprites(game.assets());
        sprites = new SpriteBank(game.assets(), level.spritesDir(), level.basePalette());
        try {
            parallax = new bubba.level.Parallax(game.assets(), level);
        } catch (RuntimeException e) {
            parallax = null; // level without parallax file
        }
        try {
            foreground = new bubba.level.ForegroundParallax(game.assets(), level);
        } catch (RuntimeException e) {
            foreground = null; // only level 1 has one
        }
        emu = new GameEmulator(game.assets(), level, startLives, startEnergy, startScore);
        sprites.redirections(emu.ram);
        game.music().setVolume(0.8f);
        bubba.engine.Settings.Sound option = bubba.engine.Settings.sound;
        if (option.music()) {
            game.music().play(game.assets().bytes(level.music()));
        } else {
            game.music().stop();
        }
        if (option.effects()) {
            emu.sounds = new bubba.emu.GameSounds(emu.ram,
                    new bubba.audio.SoundBank(game.assets(), emu.soundBankName()), game.music());
        }
    }

    @Override
    public int vblPerUpdate() {
        return 2; // the in-game logic of the original runs at 25 Hz
    }

    @Override
    public void exit(Game game) {
        game.music().stop();
    }

    @Override
    public void update(Game game, Input input) {
        if (input.pressed(Button.CANCEL)) {
            game.scenes().set(new TitleScene());
            return;
        }
        if (input.pressed(Button.NEXT)) {
            game.scenes().set(new LevelScene(stage % 5 + 1));
            return;
        }
        if (input.pressed(Button.DEBUG)) {
            debug = !debug;
        }
        if (failure != null) {
            return;
        }
        // keyboard of the original: P = pause (code 0x3B6E); in the pause, any key goes on
        if (input.pressed(Button.PAUSE)) {
            emu.key(bubba.game.MainLoop.KEY_P);
        } else if (emu.paused() && input.pressed(Button.FIRE)) {
            emu.key(KEY_SPACE);
        }
        int joy = 0;
        if (input.isDown(Button.UP)) {
            joy |= GameEmulator.UP;
        }
        if (input.isDown(Button.DOWN)) {
            joy |= GameEmulator.DOWN;
        }
        if (input.isDown(Button.LEFT)) {
            joy |= GameEmulator.LEFT;
        }
        if (input.isDown(Button.RIGHT)) {
            joy |= GameEmulator.RIGHT;
        }
        if (input.isDown(Button.FIRE)) {
            joy |= GameEmulator.FIRE;
        }
        if (parallax != null) {
            parallax.vbl(emu.camX());
            parallax.vbl(emu.camX());
        }
        boolean running;
        try {
            running = emu.frame(joy);
        } catch (RuntimeException e) {
            failure = e.getMessage();
            System.err.println("game logic stopped: " + failure);
            return;
        }
        if (!running) {
            if (emu.lives() < 0) {
                game.scenes().set(new TitleScene()); // game over
            } else if (emu.ram.read8(0x108e9) != 0) {
                game.scenes().set(new EndingScene()); // code 0x3F82: the end of the game
            } else {
                game.scenes().set(new LevelScene(stage % 5 + 1, emu.lives(), emu.energy(), emu.score()));
            }
        }
    }

    @Override
    public void render(Game game, Graphics2D g) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, Game.WIDTH, Game.HEIGHT);
        Shape clip = g.getClip();
        g.clipRect(VIEW_X, VIEW_Y, VIEW_W, VIEW_H);
        g.translate(VIEW_X, VIEW_Y);
        // playfield (map, BOBs): origin from the scroll routine 0x4BFC; hero sprite: camera
        int cx = emu.viewX() - SCREEN_DX, cy = emu.viewY();
        int[] pal = emu.palette(); // COLOR00-15 of the copper list: fades, stage 5 boss
        level.setPalette(pal);
        sprites.setPalette(pal);
        level.drawSky(g, VIEW_W, VIEW_H, emu.skyColours());
        if (parallax != null) {
            parallax.setColors(emu.hillColours());
            parallax.draw(g, cx, VIEW_W, emu.camX());
        }
        level.draw(g, cx, cy, VIEW_W, VIEW_H, emu::blockAt);
        GameMemory m = emu.ram;
        // BOBs: same x origin, but y from the camera (L_0829C = fine - camera y): the map alone
        // is shown from camera y - 6
        int by = emu.bobY();
        drawObjects(g, m, 0x17596, cx, by);
        drawObjects(g, m, 0x17514, cx, by);
        drawParticles(g, m, cx, by);
        drawStix(g, m, cx, by);
        // foreground (second playfield): in front of the map; layer A also in front of Bubba
        // once 31 rows or more are shown (BPLCON2, code 0x9832)
        boolean aFront = foreground != null && foreground.rowsA(emu.camY()) >= 31;
        if (foreground != null) {
            foreground.draw(g, emu.camX(), emu.camY(), VIEW_W, !aFront, true);
        }
        int h = GameEmulator.HERO;
        heroSprites.draw(g, m.read16(h + 0x50), (short) m.read16(h + 2) - (emu.camX() - SCREEN_DX),
                (short) m.read16(h + 6) - emu.camY(), (byte) m.read8(h + 0x55) < 0, emu.spritePalette());
        if (aFront) {
            foreground.draw(g, emu.camX(), emu.camY(), VIEW_W, true, false);
        }
        statusBar.draw(g, m, 8);
        if (debug) {
            drawDebug(g, m, cx, emu.bobY());
        }
        g.translate(-VIEW_X, -VIEW_Y);
        g.setClip(clip);
        if (failure != null) {
            g.setColor(Color.RED);
            g.drawString(failure, 4, 250);
        }
    }

    /** BOBs of an object list (code 0x8482): sprite = +$0A + base +$A0, position +$02/+$06. */
    private void drawObjects(Graphics2D g, GameMemory m, int list, int cx, int cy) {
        int n = m.read16(list);
        for (int i = 0; i < n && i < 64; i++) {
            int o = m.read32(list + 2 + 4 * i);
            if (o == 0) {
                continue;
            }
            int id = (short) m.read16(o + 0x0a) + (short) m.read16(o + 0xa0);
            if (id != (short) id) {
                continue; // bvs: overflow, not drawn
            }
            sprites.draw(g, id, (short) m.read16(o + 2) - cx, (short) m.read16(o + 6) - cy);
        }
    }

    /** Particles / effects (list $175D8, code 0x84DE): sprite +$12 (0 = none), position +$38/+$3C. */
    private void drawParticles(Graphics2D g, GameMemory m, int cx, int cy) {
        int n = m.read16(0x175d8);
        for (int i = 0; i < n && i < 64; i++) {
            int p = m.read32(0x175da + 4 * i);
            int s = m.read16(p + 0x12);
            if (s != 0) {
                sprites.drawWord(g, s >> 1, (short) m.read16(p + 0x38) - cx, (short) m.read16(p + 0x3c) - cy);
            }
        }
    }

    /** Stix (structure $10988, code 0x8514): drawn unless +$9C < 0 or $10966 set. */
    private void drawStix(Graphics2D g, GameMemory m, int cx, int cy) {
        int s = GameEmulator.STIX;
        if ((short) m.read16(s + 0x9c) < 0 || m.read8(0x10966) != 0) {
            return;
        }
        int w = (2 * m.read16(s + 0x0a)) & 0x7fff; // 4 * +$0A bytes: always a first word
        sprites.drawWord(g, w, (short) m.read16(s + 2) - cx, (short) m.read16(s + 6) - cy);
    }

    private void drawDebug(Graphics2D g, GameMemory m, int cx, int cy) {
        g.setFont(g.getFont().deriveFont(8f));
        int n = m.read16(0x17514);
        for (int i = 0; i < n; i++) {
            int o = m.read32(0x17516 + 4 * i);
            int x = (short) m.read16(o + 2) - cx, y = (short) m.read16(o + 6) - cy;
            g.setColor(Color.YELLOW);
            g.drawRect(x + (short) m.read16(o + 0x14), y + (short) m.read16(o + 0x16),
                    Math.max(1, (short) m.read16(o + 0x18)), Math.max(1, (short) m.read16(o + 0x1a)));
            g.setColor(Color.WHITE);
            g.drawString(String.valueOf(m.read16(o + 0x5c) + 1), x, y - 2);
        }
        int h = GameEmulator.HERO;
        int hx = (short) m.read16(h + 2) - cx, hy = (short) m.read16(h + 6) - cy;
        g.setColor(Color.CYAN);
        g.drawRect(hx + (short) m.read16(h + 0x14), hy + (short) m.read16(h + 0x16),
                (short) m.read16(h + 0x18), (short) m.read16(h + 0x1a));
        g.drawString(String.format("etat %d image %d  objets %d", m.read16(0xbda4), m.read16(h + 0x50), n), 2, 10);
    }
}
