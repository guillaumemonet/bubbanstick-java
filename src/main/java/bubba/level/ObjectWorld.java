package bubba.level;

import bubba.cpu.GameMemory;
import bubba.entity.LevelObject;
import bubba.entity.NativeCalls;

import java.util.HashMap;
import java.util.Map;

/**
 * The level's objects, managed in the emulated memory exactly like the original object system
 * (so the scripts and native routines see the same lists):
 * <ul>
 *   <li><b>mtrg records</b> copied to {@link #RECORDS} (type, x, y, flags; bit 7 of the first byte
 *       = "an object is alive for this record");</li>
 *   <li><b>activation</b> (code 0xFEAA / 0x1002A): a record spawns when it <i>enters</i> the window
 *       x in [cam-$40, cam+$160), y in [cam-$40, cam+$120) because the camera moved (plus the
 *       initial fill), if it is free and its condition bits $3000 match L_1750E;</li>
 *   <li><b>allocation</b> (0x1081C): at most 32 objects (active $17514 + new $17844), first
 *       structure with word +0 &lt;= 0 from $17ED8; initialisation 0x10754 / 0x107B2 / 0x106AE;</li>
 *   <li><b>children</b> (0x107B2): type index in d0, parent position; they run from the next
 *       frame;</li>
 *   <li><b>end of frame</b> (0xDCB6): objects out of the window cam-$80..+$1A0 / -$80..+$160
 *       (widened by +$1C, unless +$63) go to the destruction list $17782 (routine $DC90), new
 *       objects join the active list, destroyed objects free their record (except one-shot
 *       records, flag $4000, code 0xDE2C) and leave the lists; the active list is reordered by
 *       draw layer +$1E (0xDD86).</li>
 * </ul>
 * Not yet: the "big objects" (records with low byte 2: lifts, ropes..., pool $1A0BA) and the
 * records without bit 15.
 */
public final class ObjectWorld {

    public static final int RECORDS = 0x5d000;
    private static final int POOL = 0x17ed8;
    private static final int TYPE_TABLE = 0x111a6;
    private static final int ACTIVE = 0x17514;   // count, then pointers at $17516
    private static final int NEW = 0x17844;      // count, then pointers
    private static final int KILL = 0x17782;     // count, then pointers
    private static final int LIST_83 = 0x1767a;  // objects with +$83 bit 0
    private static final int LIST_STATE = 0x176c0; // objects with byte +0 != 0
    private static final int BUCKETS = 0x17cd8;  // 4 layers x ($82 bytes)
    private static final int RNG = 0x10894;

    private final GameMemory ram;
    private final LevelData level;
    private NativeCalls natives;
    private final int recordCount;
    private final boolean[] wasInWindow;
    private boolean firstFrame = true;
    private final Map<Integer, LevelObject> views = new HashMap<>();

    public ObjectWorld(GameMemory ram, LevelData level) {
        this.ram = ram;
        this.level = level;
        int i = 0;
        for (ObjectPlacement p : level.placements()) {
            int r = RECORDS + 8 * i++;
            ram.write16(r, p.type());
            ram.write16(r + 2, p.x());
            ram.write16(r + 4, p.y());
            ram.write16(r + 6, p.flags());
        }
        recordCount = i;
        wasInWindow = new boolean[i];
        for (int list : new int[]{ACTIVE, NEW, KILL, LIST_83, LIST_STATE, 0x17596}) {
            ram.write16(list, 0);
        }
    }

    public void setNatives(NativeCalls natives) {
        this.natives = natives;
    }

    public LevelObject view(int base) {
        return views.computeIfAbsent(base, b -> new LevelObject(ram, b));
    }

    /** Active objects in drawing order (the list at $17516). */
    public LevelObject[] active() {
        int n = ram.read16(ACTIVE);
        LevelObject[] out = new LevelObject[n];
        for (int i = 0; i < n; i++) {
            out[i] = view(ram.read32(ACTIVE + 2 + 4 * i));
        }
        return out;
    }

    /**
     * One logic frame: activation, object updates (0xDEE0), contacts with Bubba (0xF5F6), end of
     * frame (0xDCB6). Returns true when Bubba gets hurt.
     */
    public boolean update(int camX, int camY, int heroX, int heroY, int bodyHeight) {
        ram.write16(0x108ca, camX);
        ram.write16(0x108ce, camY);
        activate(camX, camY);
        for (LevelObject o : active()) {
            o.update(level, natives, heroX, heroY);
        }
        boolean hurt = heroContacts(heroX, heroY, bodyHeight);
        endOfFrame(camX, camY);
        return hurt;
    }

    // ------------------------------------------------------------------ activation (0xFEAA)

    private void activate(int camX, int camY) {
        int state = ((ram.read8(0x10a42) & 0x20) | (ram.read8(0x10a43) & 0x10)) << 8;
        ram.write16(0x1750e, state);
        for (int i = 0; i < recordCount; i++) {
            int r = RECORDS + 8 * i;
            int x = (short) ram.read16(r + 2), y = (short) ram.read16(r + 4);
            boolean in = x >= camX - 0x40 && x < camX + 0x160 && y >= camY - 0x40 && y < camY + 0x120;
            boolean entered = in && (firstFrame || !wasInWindow[i]);
            wasInWindow[i] = in;
            if (!entered) {
                continue;
            }
            int type = (short) ram.read16(r);
            int flags = ram.read16(r + 6);
            if (type < 0 || (flags & 0x8000) == 0 || (flags & 0xff) > 1) {
                continue; // alive already / other systems (big objects: TODO)
            }
            int cond = flags & 0x3000;
            if (cond != 0 && (cond & state) == 0) {
                continue;
            }
            spawnPlaced(r);
        }
        firstFrame = false;
    }

    private int definition(int index) {
        int a = TYPE_TABLE + 2 * index;
        return a + (short) ram.read16(a);
    }

    /** 0x10754: object for the record at {@code r}. */
    private void spawnPlaced(int r) {
        int index = ram.read16(r) - 1;
        int def = definition(index);
        int o = allocate();
        if (o < 0) {
            return;
        }
        ram.write16(o, 1);
        ram.write8(r, ram.read8(r) | 0x80);
        ram.write32(o + 0x54, r);
        initFromDefinition(o, def, index);
        ram.write16(o + 0x02, ram.read16(r + 2)); // 0x1069A
        ram.write16(o + 0x06, ram.read16(r + 4));
        ram.write16(o + 0x04, 0);
        ram.write16(o + 0x08, 0);
        finishInit(o);
    }

    /**
     * 0x107B2: child of the object at {@code parent}, type index {@code index}. Returns the new
     * structure, or -1 when 32 objects exist already.
     */
    public int spawnChild(int parent, int index) {
        int def = definition(index);
        int o = allocate();
        if (o < 0) {
            return -1;
        }
        ram.write16(o, 1);
        ram.write32(o + 0x54, 0);
        initFromDefinition(o, def, index);
        ram.write32(o + 0x02, ram.read32(parent + 0x02));
        ram.write32(o + 0x06, ram.read32(parent + 0x06));
        finishInit(o);
        return o;
    }

    private void initFromDefinition(int o, int def, int index) {
        ram.write32(o + 0x58, def);
        ram.write16(o + 0x5c, index);
        ram.write16(o + 0x6e, 0);
        ram.write16(o + 0x5e, ram.read16(def + 0x12));
        ram.write16(o + 0x82, ram.read16(def + 0x14));
        ram.write8(o + 0x9d, ram.read8(def + 0x16));
        ram.write16(o + 0x0a, ram.read16(def));
        ram.write32(o + 0x0c, def + 2 + (short) ram.read16(def + 2));
        ram.write32(o + 0x10, def + 4 + (short) ram.read16(def + 4));
        for (int k = 0; k < 12; k += 2) {
            ram.write16(o + 0x14 + k, ram.read16(def + 6 + k));
        }
    }

    /** 0x106AE: clear the run-time fields, register in the lists, random +$8A. */
    private void finishInit(int o) {
        for (int w : new int[]{0x34, 0x32, 0x4e, 0x4c, 0x3a, 0xa0}) {
            ram.write16(o + w, 0);
        }
        for (int b : new int[]{0x62, 0x66, 0x67, 0x68, 0x63, 0x6b, 0x6a, 0x69, 0x30}) {
            ram.write8(o + b, 0);
        }
        for (int l : new int[]{0x28, 0x2c, 0x40, 0x3c, 0x44, 0x48}) {
            ram.write32(o + l, 0);
        }
        if ((ram.read8(o + 0x83) & 1) != 0) {
            add(LIST_83, o);
        }
        if (ram.read16(o) != 2 && (ram.read16(o + 0x82) & 0x0c) != 0) {
            ram.write8(o, 1);
        }
        ram.write16(o + 0x8a, random() & 0xff);
        ram.write8(o + 0x64, 0);
    }

    /** 0x1081C. */
    private int allocate() {
        int count = ram.read16(ACTIVE) + ram.read16(NEW);
        if (count >= 0x20) {
            return -1;
        }
        int o = POOL;
        while ((short) ram.read16(o) > 0) {
            o += LevelObject.STRUCT_SIZE;
        }
        ram.write32(ACTIVE + 2 + 4 * count, o);
        add(NEW, o);
        return o;
    }

    /** The game's random generator (code 0x1072E). */
    private int random() {
        int d0 = ram.read32(RNG), d1 = ram.read32(RNG + 4);
        d1 = (d1 & 0xffff0000) | ((d1 ^ d0) & 0xffff);
        int w = d1 & 0xffff;
        d1 = (d1 & 0xffff0000) | (((w >>> 1) | (w << 15)) & 0xffff);
        d0 = (d0 & 0xffff0000) | ((d0 ^ d1) & 0xffff);
        d0 = (d0 << 16) | (d0 >>> 16);
        ram.write32(RNG + 4, d0);
        ram.write32(RNG, d1);
        return d0;
    }

    // ------------------------------------------------------------------ lists

    private void add(int list, int o) {
        int n = ram.read16(list);
        ram.write32(list + 2 + 4 * n, o);
        ram.write16(list, n + 1);
    }

    /** Remove by moving the last entry into the hole (code 0xDEA0 / 0xE214). */
    private void removeSwap(int list, int o) {
        int n = ram.read16(list) - 1;
        ram.write16(list, n);
        int last = ram.read32(list + 2 + 4 * n);
        for (int i = 0; i <= n; i++) {
            if (ram.read32(list + 2 + 4 * i) == o) {
                ram.write32(list + 2 + 4 * i, last);
                return;
            }
        }
    }

    /** Routine $DC90: put the object in the destruction list (once). */
    public void markDestroyed(int o) {
        int n = ram.read16(KILL);
        for (int i = 0; i < n; i++) {
            if (ram.read32(KILL + 2 + 4 * i) == o) {
                return;
            }
        }
        add(KILL, o);
    }

    // ------------------------------------------------------------------ Bubba against objects (0xF5F6)

    /** Hero structure $10910: box +$14..+$1A, invincibility timer +$3A. */
    private static final int HERO = 0x10910;

    /**
     * Port of 0xF5F6: Bubba's box against the objects of the list $176C0 (objects with +$83 bit
     * 2 or 3). Bit 3 = item: its effect (by type index, table 0xF6E2), score + its BCD value
     * (class +$12, routine 0x85D8), sound $15 / sparkle (0xFB58), destroyed for good (+$54 cleared
     * so its record stays used). Bit 2 = harmful: unless Bubba is invincible, the object gets +$68
     * = $FF and Bubba is hurt. Returns true when Bubba is hurt (routine 0xD850 in the original).
     */
    public boolean heroContacts(int heroX, int heroY, int bodyHeight) {
        ram.write16(HERO + 0x14, -16);
        ram.write16(HERO + 0x16, -bodyHeight);
        ram.write16(HERO + 0x18, 32);
        ram.write16(HERO + 0x1a, bodyHeight);
        if (ram.read8(0x10aba) != 0) {
            return false; // Bubba is dying
        }
        int x0 = heroX - 16, y0 = heroY - bodyHeight, x1 = x0 + 32, y1 = heroY;
        boolean hurt = false;
        int n = ram.read16(LIST_STATE);
        for (int i = 0; i < n; i++) {
            int o = ram.read32(LIST_STATE + 2 + 4 * i);
            int ox = (short) ram.read16(o + 2) + (short) ram.read16(o + 0x14);
            int oy = (short) ram.read16(o + 6) + (short) ram.read16(o + 0x16);
            if (x1 < ox || x0 > ox + (short) ram.read16(o + 0x18)
                    || y1 < oy || y0 > oy + (short) ram.read16(o + 0x1a)) {
                continue;
            }
            int b83 = ram.read8(o + 0x83);
            if ((b83 & 8) != 0) {
                pickUp(o);
            } else if ((b83 & 4) != 0 && ram.read16(HERO + 0x3a) == 0 && !hurt) {
                ram.write8(o + 0x68, 0xff);
                hurt = true;
            }
        }
        return hurt;
    }

    private void pickUp(int o) {
        int index = ram.read16(o + 0x5c);
        switch (index) {
            case 3, 4, 5 -> { // pieces collected (0xF776): list at $108A0
                int c = ram.read16(0x108a0);
                ram.write16(0x108a0, c + 1);
                ram.write8(0x108a2 + c, index - 2);
            }
            case 10 -> ram.write16(0x108c0, ram.read16(0x108c0) + 1);     // 0xF796
            case 11 -> {                                                   // 0x85F0: +1 energy
                ram.write16(0x10ab6, 1);
                ram.write16(0x10ab8, ram.read16(0x10ab8) + 1);
            }
            case 12 -> ram.write16(0x10abe, 0x1d);                        // 0x8602
            case 13 -> ram.write16(0x10ab4, ram.read16(0x10ab4) + 1);     // 0x860E: +1 life
            case 14 -> {                                                   // 0xF7A4: restart point
                ram.write32(0x108ee, ram.read32(o + 2));
                ram.write32(0x108f2, ram.read32(o + 6));
                ram.write16(0x10b14, ram.read16(0x10a42));
                ram.write16(0x108f8, ram.read16(0x10a54));
            }
            case 15 -> {                                                   // 0xF7C8: end of level
                ram.write8(0x108e7, 0xff);
                ram.write8(0x827f, 0xff);
            }
            default -> { } // sound only
        }
        // score += BCD word of the class (+$12), routine 0x85D8
        int def = ram.read32(o + 0x58);
        int score = bcdToInt(ram.read8(0x10ab0)) * 10000 + bcdToInt(ram.read8(0x10ab1)) * 100
                + bcdToInt(ram.read8(0x10ab2));
        score = (score + bcdToInt(ram.read8(def + 0x12)) * 100 + bcdToInt(ram.read8(def + 0x13))) % 1_000_000;
        ram.write8(0x10ab0, intToBcd(score / 10000));
        ram.write8(0x10ab1, intToBcd(score / 100 % 100));
        ram.write8(0x10ab2, intToBcd(score % 100));
        ram.write32(o + 0x54, 0);
        if (natives != null) {
            natives.callCode(0xfb58, view(o), 0x15);
        }
        markDestroyed(o);
    }

    private static int bcdToInt(int b) {
        return (b >> 4) * 10 + (b & 15);
    }

    private static int intToBcd(int v) {
        return ((v / 10) << 4) | (v % 10);
    }

    // ------------------------------------------------------------------ end of frame (0xDCB6)

    private void endOfFrame(int camX, int camY) {
        int x0 = camX - 0x80, x1 = x0 + 0x220, y0 = camY - 0x80, y1 = y0 + 0x1e0;
        int n = ram.read16(ACTIVE);
        for (int i = 0; i < n; i++) {
            int o = ram.read32(ACTIVE + 2 + 4 * i);
            if (ram.read8(o + 0x63) != 0) {
                continue;
            }
            int m = (short) ram.read16(o + 0x1c);
            int x = (short) ram.read16(o + 2), y = (short) ram.read16(o + 6);
            if (x < x0 - m || x > x1 + m || y < y0 - m || y >= y1 + m) {
                markDestroyed(o);
            }
        }
        // new objects join the active list (their pointers are already after the active ones)
        int fresh = ram.read16(NEW);
        ram.write16(NEW, 0);
        ram.write16(ACTIVE, n + fresh);
        for (int i = 0; i < fresh; i++) {
            int o = ram.read32(NEW + 2 + 4 * i);
            if ((byte) ram.read8(o) > 0) {
                add(LIST_STATE, o);
            }
        }
        // destruction list
        int k = ram.read16(KILL);
        ram.write16(KILL, 0);
        for (int i = 0; i < k; i++) {
            destroy(ram.read32(KILL + 2 + 4 * i));
        }
        // active list reordered by draw layer (0xDD86)
        int count = ram.read16(ACTIVE);
        int[][] layers = new int[4][0x20];
        int[] sizes = new int[4];
        for (int i = 0, src = ACTIVE + 2; i < count; i++) {
            int o;
            do {
                o = ram.read32(src);
                src += 4;
            } while (o == 0);
            int layer = ram.read8(o + 0x1e) & 3;
            layers[layer][sizes[layer]++] = o;
        }
        int dst = ACTIVE + 2;
        for (int l = 0; l < 4; l++) {
            ram.write16(BUCKETS + 0x82 * l, 0);
            for (int i = 0; i < sizes[l]; i++, dst += 4) {
                ram.write32(dst, layers[l][i]);
            }
        }
    }

    /** 0xDE22 / 0xDE2C: free the record (unless one-shot), leave the lists, free the structure. */
    private void destroy(int o) {
        int r = ram.read32(o + 0x54);
        if (r != 0) {
            boolean big = ram.read8(o + 1) == 2;
            if (big || (ram.read8(r + 6) & 0x40) == 0) {
                ram.write8(r, ram.read8(r) & 0x7f);
            }
        }
        if (ram.read8(o + 0x9c) != 0) {
            ram.write8(o + 0x9c, 0);
            if (ram.read32(0x109cc) == o && ram.read16(0x10a24) == 6) {
                ram.write16(0x109bc, 8);
            }
        }
        // small objects: the entry becomes 0 (skipped when the list is rebuilt)
        int n = ram.read16(ACTIVE) - 1;
        ram.write16(ACTIVE, n);
        for (int i = 0; i <= 0x40; i++) {
            if (ram.read32(ACTIVE + 2 + 4 * i) == o) {
                ram.write32(ACTIVE + 2 + 4 * i, 0);
                break;
            }
        }
        if ((ram.read8(o + 0x83) & 1) != 0) {
            removeSwap(LIST_83, o);
        }
        if (ram.read8(o) != 0) {
            removeSwap(LIST_STATE, o);
        }
        ram.write16(o, 0);
    }
}
