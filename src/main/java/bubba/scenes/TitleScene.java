package bubba.scenes;

import bubba.engine.Game;
import bubba.engine.Input;
import bubba.engine.Input.Button;
import bubba.engine.Scene;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Title screen, reproducing the original sequence (Bubba.code.bin, routine at 0x2682):
 * <ol>
 *   <li>the picture drops from 120 lines above with gravity and bounces
 *       (position 1/64 line units: start 0xE200, accel 0x40, bounce v = -v + 0xC0,
 *       stops when settled and |v| &lt;= 0x100);</li>
 *   <li>the "Bubba 'n' Stix" logo animation (titledlt.pak, 18 delta frames, one every 2 VBL);</li>
 *   <li>wait for fire. Music: title.np, from the start.</li>
 * </ol>
 * Fire skips the intro; Esc quits.
 */
public final class TitleScene implements Scene {

    private enum State { DROP, LOGO, READY, FADE_OUT }

    private static final int LOGO_FRAMES = 18;
    private static final int FADE_TICKS = 25;
    /** COLOR00 = $203 on the title copper list (frames have the palette split baked in). */
    private static final Color BACKGROUND = new Color(0x22, 0x00, 0x33);

    private BufferedImage base;
    private final BufferedImage[] logo = new BufferedImage[LOGO_FRAMES];

    private State state = State.DROP;
    private int pos = (short) 0xE200; // d7
    private int vel = 0;               // d6
    private final int accel = 0x40;    // d5
    private int logoFrame;
    private int timer;
    private boolean quitAfterFade;

    @Override
    public void enter(Game game) {
        base = game.assets().image("title/title_base.png");
        for (int i = 0; i < LOGO_FRAMES; i++) {
            logo[i] = game.assets().image(String.format("title/title_%02d.png", i));
        }
        game.music().setVolume(0.8f);
        if (!game.music().isPlaying()) {
            game.music().play(game.assets().bytes("music/title.mod"));
        }
    }

    @Override
    public void update(Game game, Input input) {
        if (input.pressed(Button.CANCEL) && state != State.FADE_OUT) {
            quitAfterFade = true;
            startFade();
            return;
        }
        switch (state) {
            case DROP -> {
                vel += accel;
                pos += vel;
                if (pos >= 0) {
                    pos = 0;
                    vel = -vel + 0xC0;
                }
                if (pos >= 0 && vel >= -0x100) {
                    state = State.LOGO;
                    timer = 0;
                    logoFrame = 0;
                }
                if (input.pressed(Button.FIRE)) {
                    skipToReady();
                }
            }
            case LOGO -> {
                if (++timer >= 2) {
                    timer = 0;
                    if (++logoFrame >= LOGO_FRAMES) {
                        logoFrame = LOGO_FRAMES - 1;
                        state = State.READY;
                    }
                }
                if (input.pressed(Button.FIRE)) {
                    skipToReady();
                }
            }
            case READY -> {
                if (input.pressed(Button.FIRE)) {
                    startFade();
                }
            }
            case FADE_OUT -> {
                timer++;
                if (quitAfterFade) {
                    game.music().setVolume(0.8f * (1f - timer / (float) FADE_TICKS));
                }
                if (timer >= FADE_TICKS) {
                    if (quitAfterFade) {
                        game.music().stop();
                        game.quit();
                    } else {
                        // title music keeps playing on the options screen
                        game.scenes().set(new OptionsScene());
                    }
                }
            }
        }
    }

    private void skipToReady() {
        pos = 0;
        logoFrame = LOGO_FRAMES - 1;
        state = State.READY;
    }

    private void startFade() {
        if (state == State.DROP) {
            skipToReady();
        }
        state = State.FADE_OUT;
        timer = 0;
    }

    @Override
    public void render(Game game, Graphics2D g) {
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, Game.WIDTH, Game.HEIGHT);
        if (state == State.DROP) {
            g.drawImage(base, 0, pos >> 6, null); // asr #6 like the original
        } else {
            g.drawImage(logo[logoFrame], 0, 0, null);
        }
        if (state == State.FADE_OUT) {
            float a = Math.min(1f, timer / (float) FADE_TICKS);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, Game.WIDTH, Game.HEIGHT);
            g.setComposite(AlphaComposite.SrcOver);
        }
    }
}
