package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * Object lifecycle, exact ports:
 * <ul>
 *   <li>0xFEAA: activation of the mtrg records entering the window. The records are sorted on
 *       one axis (L_178C6 != 0: x, else y); pointers $17CD0/$17CD4 delimit the records inside
 *       the window on that axis, moved when the camera scrolls ($8264/$8266); records entering
 *       on either axis spawn (flags bit 15 set, condition bits $3000 against $1750E); records
 *       without bit 15 are zone triggers spawned when 0x103D8 says so;</li>
 *   <li>0x10754 / 0x105E6 / 0x107B2: object creation (small pool $17ED8 + pending list
 *       $17844, big pool $1A0BA + list $17596, children of a5);</li>
 *   <li>0x103D8: trigger zones of a record (a2), a5 = object or 0;</li>
 *   <li>0xDCB6: end of frame (out of window -&gt; destroyed, new objects join, destruction list
 *       $17782, active list reordered by layer +$1E).</li>
 * </ul>
 */
final class ObjectLife {

    private static final int ACTIVE = 0x17514, BIG = 0x17596, PIECES = 0x175d8, NEW = 0x17844;
    private static final int KILL = 0x17782, LIST83 = 0x1767a, LISTSTATE = 0x176c0;
    private static final int P0 = 0x17cd0, P1 = 0x17cd4;

    private ObjectLife() {
    }

    static void register(Map<Integer, Ports.Port> ports) {
        ports.put(0xfeaa, ObjectLife::activate);
        ports.put(0xdcb6, ObjectLife::endOfFrame);
        ports.put(0x103d8, new Ports.Port() {
            @Override
            public void run(M68k c) {
                int a2 = c.a[2];
                boolean f = zones(c, c.mem, a2, c.a[5]);
                c.a[2] = a2;
                c.d[5] = f ? 1 : 0;
                c.n = false;
                c.z = !f;
                c.v = false;
                c.c = false;
            }

            @Override
            public int[] outputs() {
                return new int[]{5, 16};
            }
        });
        ports.put(0x107b2, new Ports.Port() {
            @Override
            public void run(M68k c) {
                spawnChild(c);
            }

            @Override
            public int[] outputs() {
                return new int[]{13, 16}; // a5, ccr (a4 is scratch at the exit: callers save theirs)
            }
        });
    }

    private static int w(GameMemory m, int a) {
        return (short) m.read16(a);
    }

    // ------------------------------------------------------------------ 0xFEAA

    static void activate(M68k c) {
        GameMemory m = c.mem;
        m.write8(0x1750c, 0);
        m.write8(0x1750e, (m.read8(0x10a42) & 0x20) | (m.read8(0x10a43) & 0x10));
        boolean byX = m.read16(0x178c6) != 0;
        int scrollMain = w(m, byX ? 0x8264 : 0x8266);   // axis of the sorted list
        int scrollOther = w(m, byX ? 0x8266 : 0x8264);
        int camA = w(m, byX ? 0x108ca : 0x108ce);     // sorted axis
        int camB = w(m, byX ? 0x108ce : 0x108ca);     // checked axis
        int offA = byX ? 2 : 4, offB = byX ? 4 : 2;     // record fields
        int spanB = byX ? 0x160 : 0x1a0;
        // 1. scrolled along the sorted axis: records that entered the window on that axis
        if (scrollMain != 0) {
            int a1, d3;
            if (scrollMain < 0) {
                int old = m.read32(P0);
                movePointersBack(m, offA, camA, byX);
                a1 = m.read32(P0);
                d3 = old - a1;
                if (d3 <= 0) {
                    d3 = -1;
                }
            } else {
                int old = m.read32(P1);
                movePointersForward(m, offA, camA, byX);
                a1 = old;
                d3 = old - m.read32(P1);
                if (d3 >= 0) {
                    d3 = -1;
                } else {
                    a1 += 4;
                    d3 = (short) -d3;
                }
            }
            if (d3 > 0) {
                int count = (((d3 & 0xffff) >>> 2) - 1) & 0xffff;
                int lo = camB - 0x40, hi = lo + spanB;
                for (int k = 0; k <= count; k++) {
                    int a2 = m.read32(a1);
                    a1 += 4;
                    if (w(m, a2) < 0) {
                        continue;
                    }
                    int flags = m.read16(a2 + 6);
                    if ((short) flags >= 0) {
                        continue;
                    }
                    int v = w(m, a2 + offB);
                    if (v < (short) lo || v >= (short) hi) {
                        continue;
                    }
                    if (!condition(m, flags)) {
                        continue;
                    }
                    spawnRecord(c, m, a2, flags);
                }
            }
        }
        // 2. scrolled along the other axis: records in the new strip
        if (scrollOther != 0) {
            int lo, hi;
            int edge = byX ? 0x120 : 0x160;
            if (scrollOther < 0) {
                lo = (short) (camB - 0x40);
                hi = (short) (lo - scrollOther);
            } else {
                hi = (short) (camB + edge);
                lo = (short) (hi - scrollOther);
            }
            int a1 = m.read32(P0);
            int d3 = m.read32(P1) - a1;
            if (d3 < 0) {
                return;
            }
            int count = (d3 & 0xffff) >>> 2;
            for (int k = 0; k <= count; k++) {
                int a2 = m.read32(a1);
                a1 += 4;
                int v = w(m, a2 + offB);
                if (v < lo || v >= hi) {
                    continue;
                }
                if (w(m, a2) < 0) {
                    continue;
                }
                int flags = m.read16(a2 + 6);
                if ((short) flags >= 0) {
                    continue;
                }
                if (!condition(m, flags)) {
                    continue;
                }
                spawnRecord(c, m, a2, flags);
            }
        }
        // 3. trigger zones (records without bit 15)
        int a1 = m.read32(P0);
        int d3 = m.read32(P1) - a1;
        if (d3 < 0) {
            return;
        }
        int count = (d3 & 0xffff) >>> 2;
        int lo = (short) (camB - 0x40), hi = (short) (lo + spanB);
        for (int k = 0; k <= count; k++) {
            int a2 = m.read32(a1);
            a1 += 4;
            if (w(m, a2) < 0) {
                continue;
            }
            int flags = m.read16(a2 + 6);
            if ((short) flags < 0) {
                continue;
            }
            int v = w(m, a2 + offB);
            if (v < lo || v > hi) {
                continue;
            }
            if (!zones(c, m, a2, 0)) {
                continue;
            }
            if (!condition(m, flags)) {
                continue;
            }
            spawnRecord(c, m, a2, flags);
        }
    }

    private static boolean condition(GameMemory m, int flags) {
        int cond = flags & 0x3000;
        return cond == 0 || (cond & m.read16(0x1750e)) != 0;
    }

    private static void spawnRecord(M68k c, GameMemory m, int a2, int flags) {
        int b = flags & 0xff;
        if (b == 0 || b == 1) {
            spawnSmall(c, m, a2);
        } else {
            spawnBig(c, m, a2);
        }
    }

    /** 0x1022E (x) / 0x101F4 (y): camera moved back. */
    private static void movePointersBack(GameMemory m, int off, int cam, boolean byX) {
        int d0 = (short) (cam - 0x40);
        int a1 = m.read32(P0) + 4;
        do {
            a1 -= 4;
        } while (d0 <= w(m, m.read32(a1) + off));
        m.write32(P0, a1 + 4);
        d0 = (short) (d0 + (byX ? 0x1a0 : 0x160));
        a1 = m.read32(P1) + 4;
        do {
            a1 -= 4;
        } while (d0 <= w(m, m.read32(a1) + off));
        m.write32(P1, a1);
    }

    /** 0x101BC (x) / 0x10184 (y): camera moved forward. */
    private static void movePointersForward(GameMemory m, int off, int cam, boolean byX) {
        int d0 = (short) (cam - 0x40);
        int a1 = m.read32(P0);
        int a2;
        do {
            a2 = m.read32(a1);
            a1 += 4;
        } while (d0 >= w(m, a2 + off));
        m.write32(P0, a1 - 4);
        d0 = (short) (d0 + (byX ? 0x1a0 : 0x160));
        a1 = m.read32(P1);
        do {
            a2 = m.read32(a1);
            a1 += 4;
        } while (d0 >= w(m, a2 + off));
        m.write32(P1, a1 - 8);
    }

    // ------------------------------------------------------------------ creation

    private static int definition(GameMemory m, int index) {
        int a = 0x111a6 + 2 * index;
        return a + (short) m.read16(a);
    }

    /** 0x1081C: small object structure, or -1 (32 objects). */
    private static int allocSmall(GameMemory m) {
        int count = (m.read16(ACTIVE) + m.read16(NEW)) & 0xffff;
        if ((short) count >= 0x20) {
            return -1;
        }
        int a4 = ACTIVE + 2 + 4 * count;
        int n = m.read16(NEW);
        m.write16(NEW, n + 1);
        int slot = NEW + 2 + 4 * n;
        int o = 0x17e1e;
        do {
            o += 0xba;
        } while (w(m, o) > 0);
        m.write32(a4, o);
        m.write32(slot, o);
        return o;
    }

    /** 0x1085C: big object structure, or -1 (16 objects). */
    private static int allocBig(GameMemory m) {
        int n = m.read16(BIG);
        if ((short) n >= 0x10) {
            return -1;
        }
        m.write16(BIG, n + 1);
        int o = 0x1a000;
        do {
            o += 0xba;
        } while (w(m, o) > 0);
        m.write32(BIG + 2 + 4 * n, o);
        return o;
    }

    /** Fields from the class definition (0x10780..0x107AA). */
    private static void fromDefinition(GameMemory m, int o, int def, int index) {
        m.write32(o + 0x58, def);
        m.write16(o + 0x5c, index);
        m.write16(o + 0x6e, 0);
        m.write16(o + 0x5e, m.read16(def + 0x12));
        m.write16(o + 0x82, m.read16(def + 0x14));
        m.write8(o + 0x9d, m.read8(def + 0x16));
        copyHeader(m, o, def, 12);
    }

    /** $0A sprite, $0C anim, $10 behaviour, then {@code bytes} bytes from def+6 to +$14. */
    private static void copyHeader(GameMemory m, int o, int def, int bytes) {
        m.write16(o + 0x0a, m.read16(def));
        m.write32(o + 0x0c, def + 2 + w(m, def + 2));
        m.write32(o + 0x10, def + 4 + w(m, def + 4));
        for (int k = 0; k < bytes; k++) {
            m.write8(o + 0x14 + k, m.read8(def + 6 + k));
        }
    }

    /** 0x10754 + 0x1069A. */
    private static void spawnSmall(M68k c, GameMemory m, int a2) {
        int index = (m.read16(a2) - 1) & 0xffff;
        int def = definition(m, index);
        int o = allocSmall(m);
        if (o < 0) {
            return;
        }
        m.write16(o, 1);
        m.write8(a2, m.read8(a2) | 0x80);
        m.write32(o + 0x54, a2);
        fromDefinition(m, o, def, index);
        placeAtRecord(m, o, a2);
        finish(m, o);
    }

    /** 0x105E6. */
    private static void spawnBig(M68k c, GameMemory m, int a2) {
        int index = (m.read16(a2) - 1) & 0xffff;
        int def = definition(m, index);
        int o = allocBig(m);
        if (o < 0) {
            return;
        }
        m.write16(o, 2);
        m.write8(a2, m.read8(a2) | 0x80);
        m.write32(o + 0x54, a2);
        m.write32(o + 0x58, def);
        m.write16(o + 0x5c, index);
        m.write8(o + 0x6d, m.read8(0x1750c));
        m.write8(o + 0x6c, 0);
        int a4 = def + 2;
        m.write32(o + 0x72, a4 + w(m, a4));
        a4 += 2;
        m.write32(o + 0x76, a4 + w(m, a4));
        a4 += 2 + 0x0a;
        m.write32(o + 0x7a, a4 + w(m, a4));
        a4 += 2;
        m.write32(o + 0x7e, a4 + w(m, a4));
        int kind = w(m, def + 0x14);
        int flags = 0;
        if (kind == 0x10) {
            flags |= 0x40;
        }
        if (kind == 0 || kind == 0x34 || kind == 0x24) {
            flags |= 0x08;
        }
        m.write16(o + 0x70, kind);
        m.write32(o + 0x82, m.read32(def + 0x16));
        m.write8(o + 0x9d, 0);
        m.write8(o + 0x6e, flags);
        copyHeader(m, o, def, 10);
        placeAtRecord(m, o, a2);
        finish(m, o);
    }

    private static void placeAtRecord(GameMemory m, int o, int a2) {
        m.write16(o + 0x02, m.read16(a2 + 2));
        m.write16(o + 0x06, m.read16(a2 + 4));
        m.write16(o + 0x04, 0);
        m.write16(o + 0x08, 0);
    }

    /** 0x107B2: child of a5, type index d0. Z set and a5 = child on success; a4 = parent. */
    static void spawnChild(M68k c) {
        GameMemory m = c.mem;
        int parent = c.a[5];
        int index = c.d[0] & 0xffff;
        c.d[1] = (c.d[1] & 0xffff0000) | index;
        int def = definition(m, (short) index);
        c.a[0] = def;
        int o = allocSmall(m);
        c.a[4] = parent;
        if (o < 0) {
            c.d[0] = 1;
            c.n = false;
            c.z = false;
            c.v = false;
            c.c = false;
            c.a[5] = NEW; // the allocator fails before its (a5)+
            return;
        }
        m.write16(o, 1);
        m.write32(o + 0x54, 0);
        fromDefinition(m, o, def, index);
        m.write32(o + 0x02, m.read32(parent + 0x02));
        m.write32(o + 0x06, m.read32(parent + 0x06));
        int d0 = finish(m, o);
        c.d[0] = d0;
        c.a[5] = o;
        c.n = false;
        c.z = true;
        c.v = false;
        c.c = false;
    }

    /** 0x106AE: clear the run-time fields, lists, state byte, random +$8A. Returns d0. */
    private static int finish(GameMemory m, int o) {
        for (int f : new int[]{0x34, 0x32, 0x4e, 0x4c}) {
            m.write16(o + f, 0);
        }
        for (int f : new int[]{0x62, 0x66, 0x67, 0x68, 0x63}) {
            m.write8(o + f, 0);
        }
        m.write16(o + 0x3a, 0);
        for (int f : new int[]{0x28, 0x2c, 0x40, 0x3c}) {
            m.write32(o + f, 0);
        }
        m.write16(o + 0xa0, 0);
        m.write32(o + 0x44, 0);
        m.write32(o + 0x48, 0);
        for (int f : new int[]{0x6b, 0x6a, 0x69, 0x30}) {
            m.write8(o + f, 0);
        }
        if ((m.read8(o + 0x83) & 1) != 0) {
            int n = m.read16(LIST83);
            m.write16(LIST83, n + 1);
            m.write32(LIST83 + 2 + 4 * n, o);
        }
        if (m.read16(o) != 2 && (m.read16(o + 0x82) & 0xc) != 0) {
            m.write8(o, 1);
        }
        int d0 = m.read32(0x10894), d1 = m.read32(0x10898);
        d1 = (d1 & 0xffff0000) | ((d1 ^ d0) & 0xffff);
        int ww = d1 & 0xffff;
        d1 = (d1 & 0xffff0000) | (((ww >>> 1) | (ww << 15)) & 0xffff);
        d0 = (d0 & 0xffff0000) | ((d0 ^ d1) & 0xffff);
        d0 = (d0 << 16) | (d0 >>> 16);
        m.write32(0x10898, d0);
        m.write32(0x10894, d1);
        d0 = (d0 & 0xffff0000) | (d0 & 0xff);
        m.write16(o + 0x8a, d0 & 0xffff);
        m.write8(o + 0x64, 0);
        return d0;
    }

    // ------------------------------------------------------------------ 0x103D8

    /**
     * Trigger zones of record a2 ((flags & 3) zones of 5 words: kind mask, x0, y0, x1, y1;
     * x0 &lt; 0 = relative to the object a5). A zone of a kind enabled in $1750A fires when
     * Bubba (1), Stix ($1098A, 2), the hero's hit box ($BDCA.., 4), a big object (8), an active
     * object (bits 4+), or the point $17510 is inside. Returns true when a zone fired ($6D / $1750C
     * = its number, the record's "fired" bit 7 of the zone word set for one-shot zones).
     */
    static boolean zones(M68k c, GameMemory m, int a2, int a5) {
        int count = m.read8(a2 + 6) & 3;
        if (count == 0) {
            return false;
        }
        int total = count;
        int a0 = a2 + 8;
        int mask = m.read16(0x1750a);
        for (int z = count - 1; z >= 0; z--) {
            int a4 = a0;
            int kind = m.read16(a0);
            int x0 = w(m, a0 + 2), y0 = w(m, a0 + 4), x1 = w(m, a0 + 6), y1 = w(m, a0 + 8);
            a0 += 10;
            int d4 = kind & mask;
            boolean fired = false;
            if (d4 != 0) {
                boolean rel = false;
                boolean skip = false;
                if (x0 < 0) {
                    if (a5 == 0) {
                        skip = true;
                    } else {
                        rel = true;
                        x0 = (short) (x0 + m.read16(a5 + 2));
                        y0 = (short) (y0 + m.read16(a5 + 6));
                        x1 = (short) (x0 + x1);
                        y1 = (short) (y0 + y1);
                    }
                }
                if (!skip) {
                    fired = test(c, m, d4, x0, y0, x1, y1, rel, a5);
                }
            }
            if (fired) {
                int zw = m.read16(a4);
                if ((short) zw < 0) {
                    continue; // already fired: next zone (0x1044C)
                }
                if ((byte) zw < 0) {
                    m.write8(a4, m.read8(a4) | 0x80);
                }
                int number = total - z;
                if (a5 != 0) {
                    m.write8(a5 + 0x6d, number);
                } else {
                    m.write8(0x1750c, number);
                }
                return true;
            }
            m.write8(a4, m.read8(a4) & 0x7f);
        }
        return false;
    }

    private static boolean inside(int px0, int py0, int px1, int py1, int x0, int y0, int x1, int y1) {
        return x1 > px0 && x0 < px1 && y1 > py0 && y0 < py1;
    }

    private static boolean test(M68k c, GameMemory m, int d4, int x0, int y0, int x1, int y1, boolean rel, int a5) {
        int bits = d4;
        for (int pass = 0; pass < 2; pass++) {
            if ((bits & 1) != 0) { // Bubba
                int h = 0x10910;
                int hx = (short) (m.read16(h + 2) + m.read16(h + 0x14));
                int hy = (short) (m.read16(h + 6) + m.read16(h + 0x16));
                if (inside(hx, hy, (short) (hx + m.read16(h + 0x18)), (short) (hy + m.read16(h + 0x1a)), x0, y0, x1, y1)) {
                    if (rel && a5 != 0 && (m.read8(a5 + 0x83) & 4) != 0 && m.read16(h + 0x3a) == 0) {
                        c.a[5] = h;
                        c.a[3] = a5;
                        c.d[6] = 2;
                        Calls.call(c, 0xd850);
                        c.a[5] = a5;
                    }
                    return true;
                }
            }
            if ((bits & 2) != 0) { // Stix point
                int sx = w(m, 0x1098a), sy = w(m, 0x1098e);
                if (sx >= x0 && sx < x1 && sy >= y0 && sy < y1) {
                    return true;
                }
            }
            if ((bits & 4) != 0) { // hero attack box
                if (x1 > w(m, 0xbdca) && x0 < w(m, 0xbdce) && y1 > w(m, 0xbdcc) && y0 < w(m, 0xbdd0)) {
                    return true;
                }
            }
            if ((bits & 8) != 0) { // big objects
                int n = m.read16(BIG);
                for (int i = 0; i < n; i++) {
                    int o = m.read32(BIG + 2 + 4 * i);
                    if (o == a5) {
                        continue;
                    }
                    int ox = (short) (m.read16(o + 2) + m.read16(o + 0x14));
                    int oy = (short) (m.read16(o + 6) + m.read16(o + 0x16));
                    if (inside(ox, oy, (short) (ox + m.read16(o + 0x18)), (short) (oy + m.read16(o + 0x1a)), x0, y0, x1, y1)) {
                        m.write8(o + 0x66, 0xff);
                        return true;
                    }
                }
            }
            if (pass == 0) {
                bits >>>= 4;
                if (bits == 0) {
                    return false;
                }
                // second nibble: 1 = small objects, 2 / 4 = point $17510
                if ((bits & 1) != 0) {
                    int n = m.read16(ACTIVE);
                    for (int i = 0; i < n; i++) {
                        int o = m.read32(ACTIVE + 2 + 4 * i);
                        int ox = (short) (m.read16(o + 2) + m.read16(o + 0x14));
                        int oy = (short) (m.read16(o + 6) + m.read16(o + 0x16));
                        if (inside(ox, oy, (short) (ox + m.read16(o + 0x18)), (short) (oy + m.read16(o + 0x1a)), x0, y0, x1, y1)) {
                            m.write8(o + 0x66, 0xff);
                            if (a5 != 0) {
                                m.write8(a5 + 0x6c, 0xff);
                            }
                            return true;
                        }
                    }
                }
                if ((bits & 6) != 0) {
                    int px = w(m, 0x17510), py = w(m, 0x17512);
                    if (px >= x0 && px < x1 && py >= y0 && py < y1) {
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 0xDCB6

    static void endOfFrame(M68k c) {
        GameMemory m = c.mem;
        int x0 = (short) (m.read16(0x108ca) - 0x80), x1 = (short) (x0 + 0x220);
        int y0 = (short) (m.read16(0x108ce) - 0x80), y1 = (short) (y0 + 0x1e0);
        for (int list : new int[]{ACTIVE, BIG}) {
            int n = m.read16(list);
            for (int i = 0; i < n; i++) {
                int o = m.read32(list + 2 + 4 * i);
                if (m.read8(o + 0x63) != 0) {
                    continue;
                }
                int mg = w(m, o + 0x1c);
                int x = w(m, o + 2), y = w(m, o + 6);
                if ((short) (x0 - mg) > x || (short) (x1 + mg) < x || (short) (y0 - mg) > y
                        || !((short) (y1 + mg) > y)) {
                    c.a[5] = o;
                    destroyLater(m, o);
                }
            }
        }
        int fresh = m.read16(NEW);
        m.write16(NEW, 0);
        m.write16(ACTIVE, m.read16(ACTIVE) + fresh);
        int st = m.read16(LISTSTATE);
        int dst = LISTSTATE + 2 + 4 * st;
        for (int i = 0; i < fresh; i++) {
            int o = m.read32(NEW + 2 + 4 * i);
            if ((byte) m.read8(o) > 0) {
                m.write16(LISTSTATE, m.read16(LISTSTATE) + 1);
                m.write32(dst, o);
                dst += 4;
            }
        }
        int k = m.read16(KILL);
        if (((k + fresh) & 0xffff) == 0) {
            return;
        }
        m.write16(KILL, 0);
        for (int i = 0; i < k; i++) {
            int o = m.read32(KILL + 2 + 4 * i);
            if (m.read8(o + 1) == 2) {
                freeRecord(m, o, true);
            } else {
                freeRecord(m, o, false);
            }
            remove(m, o);
        }
        // active list by layer (0xDD86)
        int n = m.read16(ACTIVE);
        int src = ACTIVE + 2;
        for (int b = 0; b < 4; b++) {
            m.write16(0x17cd8 + 0x82 * b, 0);
        }
        for (int i = 0; i < n; i++) {
            int o;
            do {
                o = m.read32(src);
                src += 4;
            } while (o == 0);
            int l = m.read8(o + 0x1e) & 0xff;
            int bucket = 0x17cd8 + 2 * l;
            int bc = m.read16(bucket);
            m.write16(bucket, bc + 4);
            m.write32(bucket + 2 + bc, o);
        }
        int out = ACTIVE + 2;
        for (int b = 0; b < 4; b++) {
            int bucket = 0x17cd8 + 0x82 * b;
            int bytes = m.read16(bucket);
            for (int j = 0; j < bytes / 4; j++) {
                m.write32(out, m.read32(bucket + 2 + 4 * j));
                out += 4;
            }
        }
    }

    private static void destroyLater(GameMemory m, int o) {
        int n = (short) m.read16(KILL);
        for (int i = 0; i < n; i++) {
            if (m.read32(KILL + 2 + 4 * i) == o) {
                return;
            }
        }
        m.write16(KILL, n + 1);
        m.write32(KILL + 2 + 4 * (n & 0xffff), o);
    }

    /** 0xDE22 / 0xDE2C + 0xDE40. */
    private static void freeRecord(GameMemory m, int o, boolean big) {
        int r = m.read32(o + 0x54);
        if (r != 0 && (big || (m.read8(r + 6) & 0x40) == 0)) {
            m.write8(r, m.read8(r) & 0x7f);
        }
        if (m.read8(o + 0x9c) != 0) {
            m.write8(o + 0x9c, 0);
            if (m.read32(0x109cc) == o && m.read16(0x10a24) == 6) {
                m.write16(0x109bc, 8);
            }
        }
    }

    /** 0xDE62: out of its list(s), structure freed. */
    private static void remove(GameMemory m, int o) {
        int kind = m.read8(o + 1);
        if (kind - 2 < 0 || (byte) (kind - 2) < 0) {
            m.write16(ACTIVE, m.read16(ACTIVE) - 1);
            replace(m, ACTIVE + 2, o, 0);
        } else {
            int list = kind == 2 ? BIG : kind == 3 ? PIECES : BIG;
            int n = (m.read16(list) - 1) & 0xffff;
            m.write16(list, n);
            replace(m, list + 2, o, m.read32(list + 2 + 4 * n));
        }
        if ((m.read8(o + 0x83) & 1) != 0) {
            int n = (m.read16(LIST83) - 1) & 0xffff;
            m.write16(LIST83, n);
            replace(m, LIST83 + 2, o, m.read32(LIST83 + 2 + 4 * n));
        }
        if (m.read8(o) != 0) {
            int n = (m.read16(LISTSTATE) - 1) & 0xffff;
            m.write16(LISTSTATE, n);
            replace(m, LISTSTATE + 2, o, m.read32(LISTSTATE + 2 + 4 * n));
        }
        m.write16(o, 0);
    }

    private static void replace(GameMemory m, int a, int o, int v) {
        while (m.read32(a) != o) {
            a += 4;
        }
        m.write32(a, v);
    }
}
