package bubba.entity;

import bubba.engine.Input;
import bubba.engine.Input.Button;
import bubba.level.LevelData;

import java.awt.Graphics2D;
import java.util.Random;

/**
 * Bubba. Values and animations come from the original hero code (state machine at 0x9FCE,
 * one handler per state, frame = table[state][index], table at 0xA084):
 * <ul>
 *   <li>walk: first step 2 px, then 4 px/frame; releasing stops at once (0xA54A);</li>
 *   <li>jump: vy = -11.75 px/frame, gravity 0.89 px/frame^2 ($E400), walking off a ledge
 *       starts falling at 1 px/frame (0xA7B6, 0xAFD8);</li>
 *   <li>idle (state 0, 0xA3B2): frame 10 (facing the camera); after a random wait
 *       (250 + random frames at first, then 25 + random/4) plays a random idle sequence from the
 *       table at 0xA344, never the same twice, and after 7 of them a "special" one.</li>
 * </ul>
 * Position (x, y) is the point between the feet.
 */
public final class Player {

    private static final double WALK_FIRST = 2.0;
    private static final double WALK_SPEED = 4.0;
    private static final double JUMP_SPEED = -11.75;
    private static final double GRAVITY = 0xE400 / 65536.0;
    private static final double FALL_START = 1.0;
    private static final double MAX_FALL = 10.0;
    private static final int HALF_WIDTH = 6;
    private static final int HEIGHT = 44;
    private static final int STEP = 6;

    /** Frame lists of the states (code table 0xA084). */
    private static final int[] IDLE_FRAMES = {10, 11, 12, 13, 46, 47, 48, 49, 50, 51, 52, 53, 54, 66, 103, 104,
        105, 106, 107, 14, 15, 100, 101, 102};
    private static final int[] WALK_FRAMES = {12, 1, 2, 3, 4, 5, 6, 7, 8, 18, 19, 20, 21, 22, 23};
    private static final int[] JUMP_FRAMES = {25, 26, 27, 28, 29, 30, 31, 32, 33};
    /** Idle sequences (index into IDLE_FRAMES, delay), code table 0xA344: 0-3 normal, 4-7 special. */
    private static final int[][][] IDLE_SEQUENCES = {
        {{1, 2}, {2, 20}},
        {{18, 16}},
        {{19, 4}},
        {{20, 20}},
        {{4, 2}, {5, 2}, {6, 2}, {7, 2}, {8, 2}, {9, 2}, {10, 2}, {11, 2}, {6, 2}, {7, 2}, {8, 2}, {9, 2},
            {10, 2}, {11, 2}, {12, 2}, {5, 2}, {4, 2}},
        {{13, 3}, {14, 3}, {15, 3}, {16, 3}, {17, 3}, {14, 3}, {15, 3}, {16, 3}, {17, 3}, {14, 3}, {15, 3},
            {16, 3}, {17, 3}, {14, 3}, {15, 3}, {16, 3}, {17, 3}, {13, 3}},
        {{21, 3}, {22, 70}, {21, 3}},
        {{21, 3}, {22, 70}, {21, 3}}};

    /** Crouch state 6 (handler 0xABE6, entry 0xAB94). */
    private static final int[] CROUCH_FRAMES = {61, 62, 64, 55, 56, 57, 58, 62, 64};
    private static final double CRAWL_SPEED = 2.0;

    private enum State { IDLE, WALK, AIR, CROUCH, HURT, DEAD }

    /** Invincibility after a hit: hero +$3A = $23 updates (code 0xD97E). */
    private static final int INVINCIBLE = 0x23;
    /** Hurt frames (table 0xA04C, state $12, sub-animation 4) and death frames (sub-animation $18). */
    private static final int[] HURT_FRAMES = {42, 43};
    private static final int[] DEAD_FRAMES = {80, 81};
    private int invincible;
    private int stateTimer;
    private enum Crouch { DOWN, HOLD, UP }

    private Crouch crouch;
    private int crouchIndex;
    private int crouchTimer;
    private int crawlTick;
    private double slide;

    private final Random random = new Random();
    private double x, y, vx, vy;
    private boolean onGround;
    private boolean facingLeft;
    private State state = State.IDLE;
    private int frame = IDLE_FRAMES[0];

    // walk animation
    private int walkIndex = 1;
    private boolean walkToggle;
    // jump animation
    private int jumpIndex;
    private boolean jumpToggle;
    // idle animation
    private int idleTimer;
    private int idleIndex;
    private int[][] idleSeq;
    private int idleSeqPos;
    private int idleDelay;
    private int idleCount;
    private int lastNormal = -1, lastSpecial = -1;

    public Player(LevelData level) {
        spawn(level);
    }

    public void spawn(LevelData level) {
        spawnAt(level, level.startX(), level.startY());
    }

    /** Start (or restart) at (sx, sy): idle on the ground. */
    public void spawnAt(LevelData level, int sx, int sy) {
        x = sx;
        y = sy;
        state = State.IDLE;
        invincible = 0;
        vx = vy = 0;
        int guard = 0;
        while (level.solidAt((int) x, (int) y - 1) && guard++ < 64) {
            y--; // the start point can be inside the ground
        }
        onGround = false;
        enterIdle();
    }

    public int x() {
        return (int) Math.floor(x);
    }

    /** Hero struct +$54 in the original: 0 = facing right, $FF = facing left. */
    public boolean facingLeft() {
        return facingLeft;
    }

    public int y() {
        return (int) Math.floor(y);
    }

    /** Body height: 64 standing, 32 crouched (hitbox $1A set by 0xAB94 / 0xACA0). */
    public int bodyHeight() {
        return state == State.CROUCH ? 32 : 64;
    }

    /** Hero +$3A: frames of invincibility left. */
    public int invincibility() {
        return invincible;
    }

    public boolean dead() {
        return state == State.DEAD;
    }

    /** Frames since death (the scene restarts Bubba after a while). */
    public int deadTime() {
        return state == State.DEAD ? stateTimer : 0;
    }

    /** Hit by an object (0xD850): knocked back, invincible for a while. */
    public void hurt() {
        if (state == State.DEAD) {
            return;
        }
        invincible = INVINCIBLE;
        state = State.HURT;
        stateTimer = 0;
        vx = facingLeft ? 2 : -2;
        vy = -6;
        onGround = false;
    }

    /** Out of energy: death jump, Bubba falls through the level. */
    public void die() {
        state = State.DEAD;
        stateTimer = 0;
        invincible = 0;
        vx = 0;
        vy = JUMP_SPEED;
    }

    public void update(Input in, LevelData level) {
        if (invincible > 0) {
            invincible--;
        }
        if (state == State.DEAD) {
            stateTimer++;
            vy = Math.min(MAX_FALL, vy + GRAVITY);
            y += vy;
            frame = DEAD_FRAMES[(stateTimer >> 1) & 1];
            return;
        }
        if (state == State.HURT) {
            stateTimer++;
            moveHorizontal(level);
            moveVertical(level);
            frame = HURT_FRAMES[(stateTimer >> 1) & 1];
            if (onGround && stateTimer > 4) {
                state = State.IDLE;
                vx = 0;
                enterIdle();
            }
            if (y > level.heightPx() + 64) {
                spawn(level);
            }
            return;
        }
        boolean left = in.isDown(Button.LEFT), right = in.isDown(Button.RIGHT);
        int dir = left == right ? 0 : (left ? -1 : 1);
        if (state == State.CROUCH || (onGround && in.isDown(Button.DOWN))) {
            updateCrouch(in, level, dir);
            return;
        }
        if (dir != 0) {
            facingLeft = dir < 0;
            if (vx == 0 || Math.signum(vx) != dir) {
                vx = dir * WALK_FIRST;
            } else {
                vx = dir * WALK_SPEED;
            }
        } else {
            vx = 0;
        }
        if (onGround && in.pressed(Button.UP)) {
            vy = JUMP_SPEED;
            onGround = false;
        }

        moveHorizontal(level);
        moveVertical(level);
        if (y > level.heightPx() + 64) {
            spawn(level);
        }
        animate();
    }

    private void moveHorizontal(LevelData level) {
        if (vx == 0) {
            return;
        }
        double nx = x + vx;
        int edge = (int) Math.floor(nx) + (vx > 0 ? HALF_WIDTH : -HALF_WIDTH);
        int top = state == State.CROUCH ? 28 : HEIGHT;
        for (int h = STEP + 1; h <= top; h += 8) {
            if (level.solidAt(edge, y() - h)) {
                vx = 0;
                return;
            }
        }
        x = nx;
        int guard = 0;
        while (level.solidAt(x(), y() - 1) && guard++ < STEP) {
            y -= 1; // climb slopes and small steps
        }
    }

    private void moveVertical(LevelData level) {
        if (onGround) {
            if (!level.solidAt(x(), y())) {
                int k = 1;
                while (k <= STEP && !level.solidAt(x(), y() + k)) {
                    k++;
                }
                if (k <= STEP) {
                    y += k; // follow the ground down slopes
                } else {
                    onGround = false;
                    vy = FALL_START;
                }
            }
            if (onGround) {
                vy = 0;
                return;
            }
        } else {
            vy = Math.min(MAX_FALL, vy + GRAVITY);
        }
        double target = y + vy;
        if (vy < 0) {
            while (y > target) {
                if (level.solidAt(x(), y() - HEIGHT - 1)) {
                    vy = 0;
                    break;
                }
                y = Math.max(target, y - 1);
            }
        } else {
            while (y < target) {
                double ny = Math.min(target, y + 1);
                if (level.solidAt(x(), (int) Math.floor(ny))) {
                    y = Math.floor(ny);
                    while (level.solidAt(x(), y() - 1)) {
                        y--;
                    }
                    vy = 0;
                    onGround = true;
                    break;
                }
                y = ny;
            }
        }
    }

    // ------------------------------------------------------------------ crouch / crawl

    private void updateCrouch(Input in, LevelData level, int dir) {
        if (state != State.CROUCH) {
            // entry (0xAB94): keep a quarter of the momentum minus 2 px, frames 64 -> 62 -> 55
            state = State.CROUCH;
            crouch = Crouch.DOWN;
            crouchIndex = 2;
            crouchTimer = 0;
            double v = Math.abs(vx) - 2;
            slide = v > 0 ? Math.signum(vx) * v / 4 : 0;
            vx = 0;
        }
        switch (crouch) {
            case DOWN -> {
                vx = slide;
                if (++crouchTimer % 2 == 0 && --crouchIndex < 1) {
                    crouch = Crouch.HOLD;
                    crouchIndex = 3;
                    crawlTick = 0;
                    vx = 0;
                }
            }
            case HOLD -> {
                if (!in.isDown(Button.DOWN)) {
                    crouch = Crouch.UP;
                    crouchIndex = 8;
                    crouchTimer = 2;
                    vx = 0;
                } else if (dir != 0) {
                    // crawl: 2 px/frame, frames 55-58 every 3 VBL (0xAC6E)
                    facingLeft = dir < 0;
                    vx = dir * CRAWL_SPEED;
                    if (++crawlTick >= 3) {
                        crawlTick = 0;
                        crouchIndex = crouchIndex >= 6 ? 3 : crouchIndex + 1;
                    }
                } else {
                    vx = 0;
                    crouchIndex = 3;
                }
            }
            case UP -> {
                vx = 0;
                if (in.pressed(Button.UP)) {
                    state = State.AIR; // straight into a jump (0xAC9A -> 0xA674)
                    vy = JUMP_SPEED;
                    onGround = false;
                    jumpIndex = 0;
                    frame = JUMP_FRAMES[0];
                    return;
                }
                if (--crouchTimer < 0) {
                    state = State.IDLE;
                    enterIdle();
                    return;
                }
            }
        }
        moveHorizontal(level);
        moveVertical(level);
        if (!onGround) {
            state = State.AIR; // crawled off a ledge
            jumpIndex = 0;
        }
        frame = CROUCH_FRAMES[crouchIndex];
    }

    // ------------------------------------------------------------------ animation

    private void animate() {
        State next = !onGround ? State.AIR : (vx != 0 ? State.WALK : State.IDLE);
        if (next != state) {
            state = next;
            switch (state) {
                case IDLE -> enterIdle();
                case WALK -> {
                    walkIndex = 1;
                    walkToggle = false;
                }
                case AIR -> {
                    jumpIndex = 0;
                    jumpToggle = false;
                }
            }
        }
        switch (state) {
            case WALK -> {
                // index advances every 2 frames over 1..8 (code 0xA5F0)
                walkToggle = !walkToggle;
                if (walkToggle) {
                    walkIndex++;
                    if (walkIndex >= 9) {
                        walkIndex = 1;
                    }
                }
                frame = WALK_FRAMES[walkIndex];
            }
            case AIR -> {
                // rising: frames advance every 2 VBL up to index 6; falling: index 1 (code 0xA774)
                if (vy >= 0 && jumpIndex >= 6) {
                    jumpIndex = 1;
                } else {
                    jumpToggle = !jumpToggle;
                    if (jumpToggle && jumpIndex < 6) {
                        jumpIndex++;
                    }
                }
                frame = JUMP_FRAMES[jumpIndex];
            }
            case IDLE -> updateIdle();
        }
    }

    private void enterIdle() {
        idleTimer = 250 + random.nextInt(256);
        idleSeq = null;
        idleIndex = 0;
        frame = IDLE_FRAMES[0];
    }

    private void updateIdle() {
        if (idleSeq == null) {
            if (--idleTimer > 0) {
                frame = IDLE_FRAMES[idleIndex];
                return;
            }
            idleSeq = pickIdleSequence();
            idleSeqPos = 0;
            idleDelay = 0;
        }
        if (--idleDelay <= 0) {
            if (idleSeqPos >= idleSeq.length) {
                // sequence over: back to the base pose, wait 25 + random/4 frames
                idleSeq = null;
                idleIndex = 0;
                idleTimer = 25 + random.nextInt(256) / 4;
                frame = IDLE_FRAMES[0];
                return;
            }
            idleIndex = idleSeq[idleSeqPos][0];
            idleDelay = idleSeq[idleSeqPos][1];
            idleSeqPos++;
        }
        frame = IDLE_FRAMES[idleIndex];
    }

    /** Random sequence, never repeating the previous one; a special one after 7 normal ones. */
    private int[][] pickIdleSequence() {
        idleCount++;
        int d = random.nextInt(4);
        if (idleCount < 7) {
            if (d == lastNormal) {
                d = (d + 1) & 3;
            }
            lastNormal = d;
            return IDLE_SEQUENCES[d];
        }
        while (d == lastSpecial) {
            d = (d + 1) & 3;
        }
        lastSpecial = d;
        idleCount = 0;
        return IDLE_SEQUENCES[4 + d];
    }

    public void draw(Graphics2D g, HeroSprites sprites, int camX, int camY) {
        if (invincible > 0 && state != State.HURT && (invincible & 2) != 0) {
            return; // blinking while invincible
        }
        sprites.draw(g, frame, x() - camX, y() - camY, facingLeft);
    }
}
