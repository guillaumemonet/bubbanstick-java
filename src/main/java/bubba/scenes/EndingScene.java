package bubba.scenes;

import bubba.cpu.GameMemory;
import bubba.engine.Game;
import bubba.engine.Input;
import bubba.engine.Input.Button;
import bubba.engine.PalettedImage;
import bubba.engine.Scene;
import bubba.extract.Rnc;
import bubba.game.Fades;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The end of the game (code 0x0004, after stage 5 when $108E9 is set), at 50 Hz like the
 * original:
 * <ol>
 *   <li>music "complete"; the 10 pictures of endseq.bit, each faded in (0x416C, 16 VBL) to the
 *       palette at 0x460 (the last one: 0x47E), shown 110 VBL, faded out (0x407A) before the
 *       next; the last one stays 300 VBL; music and picture fade out (0x4092);</li>
 *   <li>the title music and the credits: a script (RNC data at $56E24) interpreted by 0x2CC,
 *       texts in the font at $5665E (2 bitplanes, drawn by 0x4936) on a 320 x 112 screen,
 *       colours faded to the table at 0x49C; then "THE END" (script at 0x44C); music and
 *       screen fade out, 150 VBL, back to the title.</li>
 * </ol>
 * The colour lists, palettes and the copper colour words are those of the game memory; the
 * fades are the game's ({@link Fades}).
 */
public final class EndingScene implements Scene {

    /** Fade lists: COLOR01-15 of the picture copper list; the 9 colours of the credits list. */
    private static final int PICTURE_LIST = 0x9d58, TEXT_LIST = 0x9d66;
    private static final int PICTURE_COLOURS = 0x640e2, TEXT_COLOUR0 = 0x64052;
    /** COLOR00 of the bands of the credits copper list (display lines $2C, $68, $88...). */
    private static final int[][] BANDS = {{0x2c, 0x64052}, {0x68, 0x64066}, {0x88, 0x6406e},
            {0xa8, 0x64076}, {0xc8, 0x6407e}, {0xe8, 0x6408e}};
    private static final int TEXT_W = 320, TEXT_H = 112, TEXT_LINE = 0x70;

    private GameMemory m;
    private Game game;
    private final List<BooleanSupplier> steps = new ArrayList<>();
    private int step;
    /** VBL timer L_002BA (code 0x2BC / 0x2C4). */
    private int timer;
    /** Music volume $108E4 (0-$100) and its fade step $108E3 (code 0x5A50...). */
    private int volume, volumeStep;
    private boolean musicOn;

    private final List<PalettedImage> pictures = new ArrayList<>();
    private int picture = -1;
    private boolean textScreen;
    private final byte[] text = new byte[TEXT_W * TEXT_H];
    private final BufferedImage textImage = new BufferedImage(TEXT_W, TEXT_H, BufferedImage.TYPE_INT_RGB);
    private byte[] font;
    private Script script;

    @Override
    public void enter(Game game) {
        this.game = game;
        m = new GameMemory(game.assets());
        musicOn = bubba.engine.Settings.sound.music();
        // pictures: endseq.bit as extracted (COLOR00 black, COLOR01-15 from 0x460 / 0x47E)
        for (int i = 0; i < 10; i++) {
            int[] pal = new int[16];
            for (int c = 1; c < 16; c++) {
                pal[c] = rgb(m.read16((i == 9 ? 0x47e : 0x460) + 2 * (c - 1)));
            }
            BufferedImage img = game.assets().rawImage(String.format("ending/ending_%02d.png", i));
            pictures.add(PalettedImage.of(img, pal));
        }
        font = Rnc.unpack1(bytes(), 0x5665e).data();
        byte[] credits = Rnc.unpack1(bytes(), 0x56e24).data();
        byte[] theEnd = new byte[0x20];
        for (int i = 0; i < theEnd.length; i++) {
            theEnd[i] = (byte) m.read8(0x44c + i);
        }
        program(credits, theEnd);
    }

    private byte[] memoryImage;

    private byte[] bytes() {
        if (memoryImage == null) {
            memoryImage = game.assets().bytes("game/memory.bin");
        }
        return memoryImage;
    }

    private static int rgb(int c) {
        return ((c >> 8 & 15) * 17) << 16 | ((c >> 4 & 15) * 17) << 8 | (c & 15) * 17;
    }

    // ------------------------------------------------------------------ the program (code 0x4)

    private void then(BooleanSupplier s) {
        steps.add(s);
    }

    private void now(Runnable r) {
        steps.add(() -> {
            r.run();
            return true;
        });
    }

    private void program(byte[] credits, byte[] theEnd) {
        now(() -> {
            m.write32(0x42a2, PICTURE_LIST); // 0x2C: colours saved then cleared
            Fades.snapshotAndClear(m);
            m.write16(0x42a8, 0);
            music("music/complete.mod");
            timer = 0;
        });
        for (int i = 0; i < 10; i++) {
            int p = i;
            now(() -> volume = 0x100); // 0x10A
            then(() -> timer <= 0);
            if (p > 0) {
                now(() -> Fades.request(m, 0x407a, PICTURE_LIST, 0, 0));
                then(() -> !Fades.running(m));
            }
            now(() -> {
                picture = p;
                for (int c = 0; c < 16; c++) { // 0x170: the fade target
                    m.write16(0x1ac5c + 2 * c, m.read16((p == 9 ? 0x47e : 0x460) + 2 * c));
                }
                Fades.request(m, 0x416c, PICTURE_LIST, 0, 0);
                timer = 0x6e;
            });
        }
        now(() -> timer = 0x12c);
        then(() -> timer <= 0);
        now(() -> {
            volumeStep = -5; // 0x1BC
            Fades.request(m, 0x4092, PICTURE_LIST, 0, 0);
        });
        then(() -> volume == 0);
        then(() -> !Fades.running(m));
        now(() -> { // 0x1DE: credits screen
            picture = -1;
            textScreen = true;
            music("music/title.mod");
            m.write32(0x42a2, TEXT_LIST);
            Fades.clear(m);
            for (int c = 0; c < 9; c++) {
                m.write16(0x1ac5c + 2 * c, m.read16(0x49c + 2 * c));
            }
            volume = 0x100;
            volumeStep = 0;
            script = new Script(credits, 0);
        });
        then(() -> script.vbl());
        now(() -> timer = 0x12c);
        then(() -> timer <= 0);
        now(() -> Fades.request(m, 0x420a, m.read32(0x42a2), m.read16(0x49c), 0));
        then(() -> !Fades.running(m));
        now(() -> script = new Script(theEnd, 0));
        then(() -> script.vbl());
        now(() -> {
            volumeStep = -2; // 0x26E
            Fades.request(m, 0x4092, m.read32(0x42a2), 0, 0);
            timer = 0x96;
        });
        then(() -> timer <= 0);
        now(() -> game.scenes().set(new TitleScene()));
    }

    private void music(String file) {
        if (musicOn) {
            game.music().play(game.assets().bytes(file));
        }
    }

    @Override
    public void update(Game game, Input input) {
        if (input.pressed(Button.CANCEL)) {
            game.scenes().set(new TitleScene());
            return;
        }
        // the VBL interrupt: fades (0x5A30), timer (0x2A4), music volume
        Fades.vbl(m);
        if (timer > 0) {
            timer--;
        }
        if (volumeStep != 0) {
            volume = Math.max(0, volume + volumeStep);
            if (volume == 0) {
                volumeStep = 0;
            }
        }
        game.music().setVolume(0.8f * volume / 256f);
        while (step < steps.size()) {
            if (!steps.get(step).getAsBoolean()) {
                return;
            }
            step++;
            if (game.scenes().current() != this) {
                return;
            }
        }
    }

    @Override
    public void exit(Game game) {
        game.music().stop();
        game.music().setVolume(0.8f);
    }

    // ------------------------------------------------------------------ credits script (0x2CC)

    /** The script interpreter of 0x2CC: 8 texts (slots of 16 bytes at $17CD8) moved every frame. */
    private final class Script {
        private final byte[] s;
        private int pc;
        private final int[] ptr = new int[8], x = new int[8], y = new int[8], dx = new int[8],
                dxCount = new int[8], dy = new int[8], dyCount = new int[8];
        private int waitCounter;
        private boolean draw, waitFade;

        Script(byte[] s, int start) {
            this.s = s;
            this.pc = start;
            java.util.Arrays.fill(ptr, -1);
        }

        private int u8() {
            return s[pc++] & 0xff;
        }

        private int s8() {
            return s[pc++];
        }

        /** One VBL; true when the script has ended. */
        boolean vbl() {
            if (waitFade) { // command $14: busy wait, the screen is not redrawn
                if (Fades.running(m)) {
                    return false;
                }
                waitFade = false;
            } else if (draw) {
                frame();
            }
            draw = false;
            while (true) {
                int r = command();
                if (r < 0) {
                    return true;
                }
                if (waitFade) {
                    return false;
                }
                if (r > 0) {
                    draw = true;
                    return false;
                }
            }
        }

        /** One command: 0 = go on, 1 = frame, -1 = end. */
        private int command() {
            int c = u8();
            switch (c) {
                case 0x00 -> { // 0x37C: text in slot
                    int slot = u8() >> 4;
                    int at = pc;
                    int rel = (short) ((u8() << 8) | u8());
                    ptr[slot] = at + rel;
                    x[slot] = (short) ((u8() << 8) | u8());
                    y[slot] = s8();
                    return 0;
                }
                case 0x04 -> { // 0x3A6: motion
                    int slot = u8() >> 4;
                    dx[slot] = s8();
                    dxCount[slot] = u8();
                    dy[slot] = s8();
                    dyCount[slot] = u8();
                    return 0;
                }
                case 0x08 -> { // 0x3CE: wait (2n - 1 frames)
                    int n = 2 * u8();
                    if (++waitCounter == n) {
                        waitCounter = 0;
                        return 0;
                    }
                    pc -= 2;
                    return 1;
                }
                case 0x0c -> { // 0x3EA: colours to the background colour
                    Fades.request(m, 0x420a, m.read32(0x42a2), m.read16(0x49c), 0);
                    return 0;
                }
                case 0x10 -> { // 0x404: colours to the table 0x49C
                    Fades.request(m, 0x424e, m.read32(0x42a2), 0, 0x49c);
                    return 0;
                }
                case 0x14 -> { // 0x41C: wait for the end of the fade
                    if (Fades.running(m)) {
                        waitFade = true;
                    }
                    return 0;
                }
                case 0x18 -> { // 0x426
                    return -1;
                }
                case 0x1c -> { // 0x42A: slot cleared
                    ptr[u8() >> 4] = -1;
                    return 0;
                }
                case 0x20 -> { // 0x43C: st $108E6 (music on)
                    return 0;
                }
                default -> {
                    return -1;
                }
            }
        }

        /** 0x308: screen cleared, then each text drawn and moved. */
        private void frame() {
            java.util.Arrays.fill(text, (byte) 0);
            for (int k = 0; k < 8; k++) {
                if (ptr[k] < 0) {
                    continue;
                }
                drawText(ptr[k], x[k], y[k]);
                if (dxCount[k] != 0) {
                    dxCount[k]--;
                    x[k] += dx[k];
                }
                if (dyCount[k] != 0) { // the original sets y (move.w d0,-8(a1))
                    dyCount[k]--;
                    y[k] = dy[k];
                }
            }
        }

        /** 0x4936 with d2 = 0: the text centred on x. */
        private void drawText(int p, int cx, int ty) {
            List<int[]> glyphs = new ArrayList<>();
            int width = 0;
            for (int i = p; i < s.length && s[i] != 0; i++) {
                int ch = s[i] & 0xff, g;
                if (ch == ' ') {
                    glyphs.add(new int[]{12, -1});
                    width += 12;
                    continue;
                }
                switch (ch) {
                    case '!' -> g = 10;
                    case '.' -> g = 11;
                    case '-' -> g = 12;
                    case '>' -> g = 0x27;
                    case '?' -> g = 0x28;
                    case '\'' -> g = 0x29;
                    default -> {
                        int d = ch - 0x30;
                        if (d < 0) {
                            continue;
                        }
                        if (d >= 10) {
                            d -= 0x11;
                            if (d < 0 || d >= 0x1a) {
                                continue;
                            }
                            d += 0xd;
                        }
                        g = d;
                    }
                }
                int rec = u16(font, 2 * g);
                int adv = u16(font, rec + 4) + 1;
                glyphs.add(new int[]{adv, g});
                width += adv;
            }
            int gx = cx - ((width & 0xffff) >>> 1);
            for (int[] gl : glyphs) {
                int at = gx;
                gx += gl[0];
                if (gl[1] >= 0) {
                    glyph(gl[1], at, ty);
                }
            }
        }

        /** 0x484C: one glyph ORed into the 2 bitplanes (rows: plane 0 words, plane 1 words). */
        private void glyph(int g, int gx, int gy) {
            int rec = u16(font, 2 * g);
            int x0 = gx + (short) u16(font, rec), y0 = gy + (short) u16(font, rec + 2);
            int w = u16(font, rec + 4), h = u16(font, rec + 6);
            int words = (w >> 4) + 1, row = 4 * words, data = rec + 8;
            for (int yy = 0; yy < h; yy++) {
                int ty = y0 + yy;
                if (ty < 0 || ty >= TEXT_H) {
                    continue;
                }
                for (int xx = 0; xx < 16 * words; xx++) {
                    int tx = x0 + xx;
                    if (tx < 0 || tx >= TEXT_W) {
                        continue;
                    }
                    int b = data + yy * row + (xx >> 3);
                    if (b + 2 * words >= font.length) {
                        continue;
                    }
                    int bit = 0x80 >> (xx & 7);
                    int v = ((font[b] & bit) != 0 ? 1 : 0) | ((font[b + 2 * words] & bit) != 0 ? 2 : 0);
                    text[ty * TEXT_W + tx] |= (byte) v;
                }
            }
        }
    }

    private static int u16(byte[] b, int o) {
        return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff);
    }

    // ------------------------------------------------------------------ display

    @Override
    public void render(Game game, Graphics2D g) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, Game.WIDTH, Game.HEIGHT);
        if (picture >= 0) {
            // copper $4110: DIW $3094-$00B0, DDF $0040: the 288-pixel picture starts 16 pixels
            // right of a standard screen, shown from x = 19 (as the play window)
            int[] pal = new int[16];
            for (int c = 1; c < 16; c++) {
                pal[c] = rgb(m.read16(PICTURE_COLOURS + 4 * (c - 1)));
            }
            java.awt.Shape clip = g.getClip();
            g.clipRect(19, 4, 284, 208);
            g.setColor(Color.BLACK);
            g.fillRect(19, 4, 284, 208);
            g.drawImage(pictures.get(picture).image(pal), 16, 4, null);
            g.setClip(clip);
        } else if (textScreen) {
            // copper $3FD4: COLOR00 bands, the text window = display lines $70-$DF, x from 7
            for (int i = 0; i < BANDS.length; i++) {
                int top = BANDS[i][0] - 0x2c, bottom = i + 1 < BANDS.length ? BANDS[i + 1][0] - 0x2c : Game.HEIGHT;
                g.setColor(new Color(rgb(m.read16(BANDS[i][1]))));
                g.fillRect(0, top, Game.WIDTH, bottom - top);
            }
            int[] cols = new int[4];
            for (int c = 0; c < 4; c++) {
                cols[c] = rgb(m.read16(TEXT_COLOUR0 + 4 * c));
            }
            int[] out = ((DataBufferInt) textImage.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < text.length; i++) {
                out[i] = cols[text[i]];
            }
            java.awt.Shape clip = g.getClip();
            g.clipRect(7, TEXT_LINE - 0x2c, TEXT_W - 7, TEXT_H);
            g.drawImage(textImage, 0, TEXT_LINE - 0x2c, null);
            g.setClip(clip);
        }
    }
}
