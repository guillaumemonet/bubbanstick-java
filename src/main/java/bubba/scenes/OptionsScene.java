package bubba.scenes;

import bubba.engine.BitmapFont;
import bubba.engine.Game;
import bubba.engine.Input;
import bubba.engine.Input.Button;
import bubba.engine.Scene;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;

/**
 * Options screen, after the original (code 0x2EB2-0x3280, copper list at chip 0x401C):
 * <ul>
 *   <li>4 lines, 32 pixels apart, centred; lines 1 and 3 slide in from the right, 2 and 4 from
 *       the left (offset $150, -$18 per frame, small overshoot);</li>
 *   <li>background $303, the band of the selected line is $606 (copper COLOR00 per band);</li>
 *   <li>SFX/MUSIC and 1/2 BUTTON JOYSTICK toggle with fire, CODE lets you type a 10 character
 *       password (up/down change the letter, left/right move), START GAME starts.</li>
 * </ul>
 */
public final class OptionsScene implements Scene {

    private static final Color BG = amiga(0x303);
    private static final Color SELECTED = amiga(0x606);
    /** Display window starts at line $70, the PAL screen at $2C. */
    private static final int TOP = 0x70 - 0x2C;
    private static final int ROW = 32;
    /** Characters allowed in a password (code 0x33F4). */
    private static final String CODE_CHARS = "123456789BCDFGHJKLMNPQRSTVWXYZ?!";
    private static final int FADE_TICKS = 25;

    private BitmapFont font;
    private int selected = 3;
    private int slide = 0x150;
    private int slideStep = 0;
    private int repeat;
    private bubba.engine.Settings.Sound sound = bubba.engine.Settings.sound; // SFX / MUSIC / both
    private boolean twoButtons;
    private final char[] code = "1111111111".toCharArray();
    private boolean editingCode;
    private int cursor;
    private int invalidTimer;
    private int fade = -1;
    private boolean backToTitle;

    private static Color amiga(int rgb12) {
        return new Color(((rgb12 >> 8) & 15) * 17, ((rgb12 >> 4) & 15) * 17, (rgb12 & 15) * 17);
    }

    @Override
    public void enter(Game game) {
        font = new BitmapFont(game.assets(), "menu_font");
        if (!game.music().isPlaying()) {
            game.music().setVolume(0.8f);
            game.music().play(game.assets().bytes("music/title.mod"));
        }
    }

    @Override
    public void update(Game game, Input input) {
        if (fade >= 0) {
            fade++;
            game.music().setVolume(0.8f * Math.max(0f, 1f - fade / (float) FADE_TICKS));
            if (fade >= FADE_TICKS) {
                if (backToTitle) {
                    game.scenes().set(new TitleScene());
                } else {
                    game.music().stop();
                    game.scenes().set(new LevelScene(1));
                }
            }
            return;
        }
        updateSlide();
        if (invalidTimer > 0) {
            invalidTimer--;
        }
        if (input.pressed(Button.CANCEL)) {
            if (editingCode) {
                editingCode = false;
            } else {
                backToTitle = true;
                fade = 0;
            }
            return;
        }
        if (editingCode) {
            updateCode(input);
            return;
        }
        if (repeat > 0) {
            repeat--;
        } else if (input.isDown(Button.UP)) {
            selected = (selected + 3) & 3;
            repeat = 6;
        } else if (input.isDown(Button.DOWN)) {
            selected = (selected + 1) & 3;
            repeat = 6;
        }
        if (input.pressed(Button.FIRE)) {
            switch (selected) {
                case 0 -> {
                    sound = sound.next();
                    bubba.engine.Settings.sound = sound;
                }
                case 1 -> twoButtons = !twoButtons;
                case 2 -> {
                    editingCode = true;
                    cursor = 0;
                }
                default -> fade = 0;
            }
        }
    }

    /** Slide counter logic of code 0x2FE6: 15 steps of $18, 2 back, 1 forward. */
    private void updateSlide() {
        if (slideStep == 0x12) {
            return;
        }
        int d = 0x18;
        if (slideStep > 0x0e && slideStep != 0x11) {
            d = -0x18;
        }
        slideStep++;
        slide -= d;
    }

    private void updateCode(Input input) {
        if (repeat > 0) {
            repeat--;
        } else {
            int idx = CODE_CHARS.indexOf(code[cursor]);
            if (input.isDown(Button.UP)) {
                code[cursor] = CODE_CHARS.charAt((idx + 1) & 31);
                repeat = 6;
            } else if (input.isDown(Button.DOWN)) {
                code[cursor] = CODE_CHARS.charAt((idx + 31) & 31);
                repeat = 6;
            } else if (input.isDown(Button.LEFT)) {
                cursor = (cursor + 9) % 10;
                repeat = 6;
            } else if (input.isDown(Button.RIGHT)) {
                cursor = (cursor + 1) % 10;
                repeat = 6;
            }
        }
        if (input.pressed(Button.FIRE)) {
            editingCode = false;
            // TODO: password check (code 0x33D6) - not decoded yet, every code is refused
            if (!new String(code).equals("1111111111")) {
                invalidTimer = 100;
            }
        }
    }

    @Override
    public void render(Game game, Graphics2D g) {
        g.setColor(BG);
        g.fillRect(0, 0, Game.WIDTH, Game.HEIGHT);
        // copper bands: line $68 + $20*i (the selected one is $606)
        g.setColor(SELECTED);
        g.fillRect(0, 0x68 - 0x2C + ROW * selected, Game.WIDTH, ROW);

        String[] lines = {
            sound.label,
            (twoButtons ? "2" : "1") + " BUTTON JOYSTICK",
            invalidTimer > 0 ? "CODE INVALID" : "CODE " + new String(code),
            "START GAME"};
        for (int i = 0; i < 4; i++) {
            int x = 160 + ((i & 1) == 0 ? slide : -slide);
            font.drawCentered(g, lines[i], x, TOP + ROW * i);
        }
        if (editingCode && (game.ticks() & 8) == 0) {
            // blinking cursor under the edited character
            String prefix = "CODE " + new String(code, 0, cursor);
            int x0 = 160 - font.width("CODE " + new String(code)) / 2 + font.width(prefix);
            int w = font.width(String.valueOf(code[cursor]));
            g.setColor(new Color(0xee, 0xee, 0xee));
            g.fillRect(x0, TOP + ROW * 2 + 17, Math.max(2, w - 1), 2);
        }
        if (fade >= 0) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1f, fade / (float) FADE_TICKS)));
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, Game.WIDTH, Game.HEIGHT);
            g.setComposite(AlphaComposite.SrcOver);
        }
    }
}
