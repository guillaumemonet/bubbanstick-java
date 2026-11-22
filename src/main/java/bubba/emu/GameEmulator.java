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
        cpu.hook(0x79ce, c -> c.z = true);   // keyboard: no key pressed
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
        cpu.call(0x6c18, 50_000_000);        // end of the level start: hero, objects, Stix
        cpu.call(0x8638, 1_000_000);         // status bar: energy refilled step by step
        if (score != null) {
            for (int i = 0; i < 3; i++) {
                ram.write8(SCORE + i, score[i]);
            }
        }
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
        ram.write8(0x8269, joy);
        cpu.a[7] = GameMemory.STACK_TOP - 0x100;
        int stop = cpu.runUntil(MAIN_LOOP, new int[]{MAIN_LOOP, LEVEL_EXIT}, MAX_STEPS);
        ended = stop == LEVEL_EXIT;
        return !ended;
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
