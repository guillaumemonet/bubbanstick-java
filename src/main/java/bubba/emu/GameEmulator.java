package bubba.emu;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;
import bubba.engine.Assets;
import bubba.level.LevelData;
import bubba.level.LevelMemory;

import java.util.function.IntConsumer;

/**
 * Runs the original game logic on the 68000 interpreter: one turn of the main loop (code 0x3AF0)
 * per logic frame - Bubba and Stix (0x9EFA, 0xD042, 0xBEA8...), objects (0xDEE0, 0xFEAA, 0xDCB6),
 * contacts (0xF5F6), camera... Java only feeds the joystick and draws what the memory holds; the
 * routines already ported run in Java ({@link bubba.game.Ports}).
 * <p>
 * Replaced by Java: the BOB display (0x8470), the keyboard (0x79CE),
 * the VBL waits, the sound calls (0x7BC8, reported to {@link #onSound}), file loading and the boot
 * allocations (buffers placed in spare memory), and the display part of the level start.
 */
public final class GameEmulator {

    /** Joystick bits of the byte $8269 (built from JOY1DAT by the VBL interrupt, table 0x4C98). */
    public static final int UP = 1, DOWN = 2, LEFT = 4, RIGHT = 8, BUTTON2 = 0x40, FIRE = 0x80;

    public static final int HERO = 0x10910;
    public static final int STIX = 0x10988;
    public static final int CAM_X = 0x108ca, CAM_Y = 0x108ce;
    public static final int LIVES = 0x108bc, ENERGY = 0x108be, SCORE = 0x10ab0;

    private static final int MAIN_LOOP = 0x3af0;
    private static final int LEVEL_EXIT = 0x3cf0;
    private static final int INFO = 0xc0000;
    private static final int TRIGGERS = 0xc8008;
    private static final int MAX_STEPS = 5_000_000;

    /** -Dbubba.invincible=true (gradlew run -Pinvincible): Bubba cannot be hurt. */
    public static final boolean INVINCIBLE = Boolean.getBoolean("bubba.invincible");

    /** Dev: trace of the original code run by the level start (coverage measures). */
    public static java.util.function.IntConsumer startTrace;

    public final GameMemory ram;
    public final M68k cpu;
    public final LevelData level;
    private boolean ended;
    /** Sound effects (SFX option), null = silent (MUSIC option: the game plays no effects). */
    public GameSounds sounds;
    /** Sound effect requests (d0 of routine 0x7BC8). */
    public IntConsumer onSound = n -> { };

    /**
     * Load a level like the game does after the disk loader (0x65BA, 0x6C18), then the status
     * bar (0x8638). {@code lives}/{@code energy}: values carried from the previous level (3 / 6 at
     * the start of a game, code 0x3FA4).
     */
    public GameEmulator(Assets assets, LevelData level, int lives, int energy, byte[] score) {
        this.level = level;
        ram = new GameMemory(assets);
        ram.loadSet(level.setImage());
        LevelMemory.build(ram, level.info(), level.rawMap());
        cpu = new M68k(ram);
        GameMemory.pcSource = () -> cpu.pc;
        cpu.trace = startTrace;
        cpu.hook(0x7bc8, c -> {
            onSound.accept(c.d[0] & 0xff);
            if (sounds != null) {
                sounds.request(c, false);
            }
        });
        cpu.hook(0x7bca, c -> {
            onSound.accept(c.d[0] & 0xff);
            if (sounds != null) {
                sounds.request(c, true);
            }
        });
        cpu.hook(0x8470, c -> { });          // BOB display: done in Java
        ram.write32(0x7ee0, 0);              // keyboard buffer empty (code 0x3AE4)
        bubba.game.Ports.install(cpu);       // routines already ported to Java
        bubba.game.Ports.installNatives(cpu, level); // the level's own routines ported so far
        ram.write16(0x3b2c, 0x4e71);         // VBL waits of the main loop -> nop
        ram.write16(0x3cd0, 0x4e71);
        ram.write16(0x53a2, 0x4e71);         // VBL wait of the hero sprite routine (it also keeps +$54)
        relocateHeroFrames();

        byte[] info = level.info();
        for (int i = 0; i < info.length; i++) {
            ram.write8(INFO + i, info[i]);
        }
        ram.write32(0x8142, INFO);
        // buffers allocated at boot (code 0x6060..0x6186), placed in spare memory
        ram.write32(0x8282, 0xd0002);        // two screens
        ram.write32(0x8286, 0xe0002);
        ram.write32(0x1bada, 0xb0000);
        ram.write32(0x1bac2, 0xb0000);
        ram.write32(0x82a6, 0xc2000);
        ram.write32(0x82ae, LevelMemory.MAP);
        ram.write32(0x935a, 0xc4000);
        ram.write32(0x82aa, TRIGGERS);
        ram.write32(0x174fe, 0xc9000);
        byte[] trg = level.triggers();       // object records at (L_082AA), read by 0x102C4
        for (int i = 0; i < trg.length; i++) {
            ram.write8(TRIGGERS + i, trg[i]);
        }
        levelVariables(info);
        ram.write16(LIVES, lives);
        ram.write16(ENERGY, energy);
        cpu.a[7] = GameMemory.STACK_TOP;
        bubba.game.Ports.call(cpu, 0x6c18);  // end of the level start: hero, objects, Stix
        bubba.game.Ports.call(cpu, 0x8638);  // status bar: energy refilled step by step
        levelColours(info);
        if (score != null) {
            for (int i = 0; i < 3; i++) {
                ram.write8(SCORE + i, score[i]);
            }
        }
    }

    /**
     * Colours at the start of the level: info+$12 into the buffer $8162 with COLOR00 cleared
     * (code 0x6692 / 0x66EC), COLOR00-15 of the copper list $600D0 from it (code 0x3A3C), then
     * the sprite colours of the list $9966 saved, cleared and faded in (code 0x3A86).
     */
    private void levelColours(byte[] info) {
        for (int i = 0; i < 16; i++) {
            int c = i == 0 ? 0 : ((info[0x12 + 2 * i] & 0xff) << 8) | (info[0x13 + 2 * i] & 0xff);
            ram.write16(0x8162 + 2 * i, c);
            ram.write16(COPPER_COLOURS + 4 * i, c);
        }
        ram.write16(0x600d2 + 0x1e60, 0);
        // boot (code 0x5FA2): the hero palette $81A2 into COLOR16-31 of the buffer ($8182)
        for (int i = 0; i < 16; i++) {
            ram.write16(0x8182 + 2 * i, ram.read16(0x81a2 + 2 * i));
        }
        // sprite colours: COLOR17-31 of the copper list from $8184 (code 0x3A54), and COLOR21-23
        // of the status bar's copper moves (code 0x671E): Bubba and the bar fade with the screen
        for (int i = 0; i < 15; i++) {
            ram.write16(SPRITE_COLOURS + 4 * i, ram.read16(0x8184 + 2 * i));
        }
        for (int k = 0; k < 3; k++) {
            int c = ram.read16(0x8184 + 2 * k);
            for (int a : new int[]{0x6200e, 0x620ae, 0x61fbe, 0x61f7e}) {
                ram.write16(a + 4 * k, c);
            }
        }
        // the sky (31 lines) and hill colours (32 lines) of info+$52, and the end of the fade
        // lists (code 0x9DDC): the original points them into its copper sky; here they point to
        // these 63 words, which the renderer reads. No water copper: the entry $9996 points to
        // the dummy word $9DDA, as when the level has no water.
        for (int i = 0; i < 63; i++) {
            ram.write16(SKY + 2 * i, ((info[0x52 + 2 * i] & 0xff) << 8) | (info[0x53 + 2 * i] & 0xff));
        }
        ram.write32(0x9996, 0x9dda);
        ram.write32(0x999a, 0);
        ram.write32(0x99fe, SKY);
        ram.write16(0x9a02, 30);
        ram.write16(0x9a04, 2);
        ram.write32(0x9a06, SKY + 62);
        ram.write16(0x9a0a, 31);
        ram.write16(0x9a0c, 2);
        ram.write32(0x9a0e, 0);
        ram.write16(0x9a12, 0xffff);
        ram.write32(0x42a2, 0x9966);
        bubba.game.Fades.snapshotAndClear(ram);
        cpu.a[0] = 0x41ca;
        cpu.a[1] = 0x9966;
        bubba.game.Ports.call(cpu, 0x79ee);
    }

    /** COLOR00-15 values in the main copper list ($600D0, one MOVE every 4 bytes). */
    private static final int COPPER_COLOURS = 0x600d2;

    /** Sky and hill colours (63 words), faded with the screen. */
    private static final int SKY = 0xcf000;

    private static int rgb(int c) {
        return ((c >> 8 & 15) * 17) << 16 | ((c >> 4 & 15) * 17) << 8 | (c & 15) * 17;
    }

    /** COLOR00 of the 31 sky lines (0xRRGGBB). */
    public int[] skyColours() {
        int[] p = new int[31];
        for (int i = 0; i < 31; i++) {
            p[i] = rgb(ram.read16(SKY + 2 * i));
        }
        return p;
    }

    /** Colour of the 32 hill lines (ARGB). */
    public int[] hillColours() {
        int[] p = new int[32];
        for (int i = 0; i < 32; i++) {
            p[i] = 0xff000000 | rgb(ram.read16(SKY + 62 + 2 * i));
        }
        return p;
    }

    /** COLOR00-15 of the moment (0xRRGGBB), for the playfield and the BOBs. */
    public int[] palette() {
        int[] p = new int[16];
        for (int i = 0; i < 16; i++) {
            p[i] = rgb(ram.read16(COPPER_COLOURS + 4 * i));
        }
        return p;
    }

    /** COLOR17-31 of the copper list (copied from $8184 at the level start). */
    public static final int SPRITE_COLOURS = 0x61b66;

    /** Colours 1-15 of the hero sprites of the moment (COLOR17-31, 0xRRGGBB; entry 0 unused). */
    public int[] spritePalette() {
        return spritePalette(ram);
    }

    public static int[] spritePalette(GameMemory m) {
        int[] p = new int[16];
        for (int i = 1; i < 16; i++) {
            p[i] = rgb(m.read16(SPRITE_COLOURS + 4 * (i - 1)));
        }
        return p;
    }

    /** Boot code 0x61AC: the hero frame table $1C174 gets its data pointers relocated (+$1D36A). */
    private void relocateHeroFrames() {
        int table = 0x1c174, a2 = table;
        int n = (short) ram.read16(a2);
        a2 += 2;
        for (int k = 0; k <= n; k++) {
            int a3 = table + (short) ram.read16(a2) + 6;
            a2 += 2;
            int pieces = (short) ram.read16(a3);
            a3 += 2;
            for (int i = 0; i <= pieces; i++) {
                a3 += 2;
                ram.write32(a3, ram.read32(a3) + 0x1d36a);
                a3 += 4;
            }
        }
    }

    /** Sound bank of the level's disk: lev1 / lev6 are on disk 1, the others on disk 2. */
    public String soundBankName() {
        String id = level.fileId();
        return id.equals("1") || id.equals("6") ? "disk1" : "disk2";
    }

    /** The logic part of the level start 0x65BA..0x66E2 (the rest prepares the display). */
    private void levelVariables(byte[] info) {
        java.util.function.IntUnaryOperator u16 = o -> ((info[o] & 0xff) << 8) | (info[o + 1] & 0xff);
        ram.write16(0x178c6, u16.applyAsInt(0x10));
        int w = u16.applyAsInt(0), h = u16.applyAsInt(2);
        ram.write32(0x108fa, 0);             // camera limits (0x6614..0x665E)
        ram.write32(0x10902, ((w - 0x12) << 4) << 16);
        ram.write32(0x108da, (((w - 0x12) << 4) << 16) + 0x1100000);
        ram.write16(0x10a46, h);
        ram.write32(0x108fe, 0);
        ram.write32(0x10906, ((h - 0x0e) << 4) << 16);
        ram.write32(0x108de, (((h - 0x0e) << 4) << 16) + 0x900000);
        ram.write32(0x108ee, u16.applyAsInt(6) << 16); // start / restart point (0x66A2)
        ram.write32(0x108f2, u16.applyAsInt(8) << 16);
        ram.write8(0x108f6, 0);
        ram.write16(0x108f8, ram.read16(0x10a50));
        ram.write8(HERO + 0x54, 0);
        ram.write8(HERO + 0x55, 0);
        ram.write8(HERO + 0x65, 0);
        ram.write16(0x10b14, u16.applyAsInt(0x0e));
        ram.write16(0x10a42, 0);
    }

    /**
     * One logic frame (25 Hz) with the joystick byte {@code joy}. Returns false when the main loop
     * was left (end of level, game over): see {@link #levelDone()} and {@link #lives()}.
     */
    public boolean frame(int joy) {
        if (ended) {
            return false;
        }
        GameMemory.frame++;
        ram.write8(0x8269, joy);
        cpu.a[7] = GameMemory.STACK_TOP - 0x100;
        if (INVINCIBLE) { // cheat: the invulnerability timer kept running, energy and lives full
            if ((short) ram.read16(HERO + 0x3a) < 2) {
                ram.write16(HERO + 0x3a, 2);
            }
            ram.write16(ENERGY, 6);
            if ((short) ram.read16(LIVES) < 3) {
                ram.write16(LIVES, 3);
            }
        }
        boolean go;
        if (bubba.game.MainLoop.paused(ram)) {
            go = bubba.game.MainLoop.paused(cpu); // the pause loop (0x3B9C) until a key
        } else if (bubba.game.Ports.VERIFY) {
            go = verifiedFrame();
        } else {
            go = bubba.game.MainLoop.frame(cpu); // the main loop in Java
        }
        ended = !go;
        bubba.game.Fades.vbl(ram); // two VBLs per logic frame (the interrupt's palette fades)
        bubba.game.Fades.vbl(ram);
        return go;
    }

    /**
     * A key press, as the keyboard interrupt (0x799E) records it: the raw Amiga code inverted
     * ({@link bubba.game.MainLoop#KEY_P}...) into the 8-byte buffer $7EE4, unless it is the
     * same byte as the last one ($826E); the release follows.
     */
    public void key(int stored) {
        if (ram.read8(0x826e) != stored) {
            int w = ram.read16(0x7ee0);
            ram.write8(0x7ee4 + w, stored);
            w = (w + 1) & 7;
            if (w != ram.read16(0x7ee2)) {
                ram.write16(0x7ee0, w);
            }
        }
        ram.write8(0x826e, stored & 0x7f); // key up: bit 7 clear, not stored
    }

    /** True while the game waits in its pause loop. */
    public boolean paused() {
        return bubba.game.MainLoop.paused(ram);
    }

    private boolean mainLoopReported;

    /** -Dbubba.verifyPorts: the original main loop and the Java one from the same state. */
    private boolean verifiedFrame() {
        byte[] before = ram.snapshot();
        int[] regs = cpu.saveRegisters();
        int stop = cpu.runUntil(MAIN_LOOP, new int[]{MAIN_LOOP, LEVEL_EXIT}, MAX_STEPS);
        boolean origGo = stop != LEVEL_EXIT;
        byte[] orig = ram.snapshot();
        int[] origRegs = cpu.saveRegisters();
        ram.restore(before);
        cpu.restoreRegisters(regs);
        boolean go = bubba.game.MainLoop.frame(cpu);
        int diff = ram.firstDifference(orig);
        int bad = -1;
        int[] mine = cpu.saveRegisters();
        for (int r = 0; r < 15 && go && origGo; r++) {
            if (mine[r] != origRegs[r]) {
                bad = r;
                break;
            }
        }
        int[] st = bubba.game.Ports.STATS.computeIfAbsent(MAIN_LOOP, k -> new int[2]);
        st[0]++;
        if (diff >= 0 || bad >= 0 || go != origGo) {
            st[1]++;
            if (!mainLoopReported) {
                mainLoopReported = true;
                System.err.printf("MAIN LOOP differs: memory $%X (before %02X 68k %02X java %02X)%s%s%n", diff,
                        diff >= 0 ? before[diff] & 0xff : 0, diff >= 0 ? orig[diff] & 0xff : 0, diff >= 0 ? ram.read8(diff) : 0,
                        bad >= 0 ? String.format(", register %d 68k %X / java %X", bad, origRegs[bad], mine[bad]) : "",
                        go != origGo ? ", level end" : "");
            }
            ram.restore(orig);
            cpu.restoreRegisters(origRegs);
            return origGo;
        }
        return go;
    }

    /** The end-of-level flag (item, code 0xF7C8) or the exit of the main loop. */
    public boolean levelDone() {
        return ram.read8(0x108e7) != 0;
    }


    /**
     * Block image shown in map cell (bx, by), read from the map in memory (L_10888: each cell =
     * offset of its block entry, code 0x6B48 - the scripts change it: doors, walls, teleports).
     * The image is the entry's graphics pointer for the animation phase $10A64 (stages 3 and 5
     * cycle them), redirected by the block animations ({@link GameMemory#blockGfx}).
     */
    public int blockAt(int bx, int by) {
        int w = ram.read16(0x10a4a), h = ram.read16(0x10a4c);
        if (bx < 0 || by < 0 || bx >= w || by >= h) {
            return 0;
        }
        int off = ram.read16(ram.read32(0x10888) + 2 * (by * w + bx));
        // graphics pointer number $10A64 / 4 of the block entry (0x51D4), then the blits (0xC6B4)
        int ptr = ram.read32(ram.read32(0x10890) + off + 2 + (ram.read16(0x10a64) & 0xfffc));
        int slot = (ptr - bubba.level.LevelMemory.BLOCK_GFX) >> 7;
        return slot >= 0 && slot < ram.blockGfx.length ? ram.blockGfx[slot] : 0;
    }

    /** Playfield origin x (code 0x4C00: camera x + shake $1090A). */
    public int viewX() {
        return camX() + (short) ram.read16(0x1090a);
    }

    /** Playfield origin y (code 0x4C2C: camera y + shake $1090C - 6, not below 0). */
    public int viewY() {
        return Math.max(0, camY() + (short) ram.read16(0x1090c) - 6);
    }

    /** Origin of the BOBs (objects, particles, thrown Stix): camera + shake, without the map's -6. */
    public int bobY() {
        return camY() + (short) ram.read16(0x1090c);
    }

    public int camX() {
        return (short) ram.read16(CAM_X);
    }

    public int camY() {
        return (short) ram.read16(CAM_Y);
    }

    public int lives() {
        return (short) ram.read16(LIVES);
    }

    public int energy() {
        return (short) ram.read16(ENERGY);
    }

    /** 6 BCD digits. */
    public byte[] score() {
        return new byte[]{(byte) ram.read8(SCORE), (byte) ram.read8(SCORE + 1), (byte) ram.read8(SCORE + 2)};
    }
}
