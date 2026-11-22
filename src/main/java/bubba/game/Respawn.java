package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * Bubba back at the check point and the level's objects reloaded, exact ports (register level):
 * <ul>
 *   <li>0xCAEA: hero, camera and status reset at the check point ($108EE / $108F2), level init
 *       routine of the set (offset table $10B16), then 0x102C4;</li>
 *   <li>0x102C4: trigger list $178C8.. rebuilt from the records at (L_082AA) (sentinels before
 *       and after), object pools cleared, 0x10354, 0x103AA, 0x10268;</li>
 *   <li>0x10354: every active / big object into the kill list $17782, then 0xDCB6;</li>
 *   <li>0x103AA: triggers of types $B-$C and above $14 re-armed (bit 15 cleared);</li>
 *   <li>0x10268 and 0x10184 / 0x101BC / 0x101F4 / 0x1022E: the window cursors $17CD0 /
 *       $17CD4 over the trigger list, along y ($178C6 = 0) or x, then activation (0xFEAA).</li>
 * </ul>
 */
final class Respawn {

    private static final int[] ALL = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};

    private Respawn() {
    }

    private static void add(Map<Integer, Ports.Port> ports, int addr, M68k.Hook h) {
        ports.put(addr, new Ports.Port() {
            @Override
            public void run(M68k c) {
                h.run(c);
            }

            @Override
            public int[] outputs() {
                return ALL;
            }
        });
    }

    static void register(Map<Integer, Ports.Port> ports) {
        add(ports, 0xcaea, Respawn::caea);
        add(ports, 0x102c4, Respawn::loadObjects);
        add(ports, 0x10354, Respawn::killAll);
        add(ports, 0x103aa, Respawn::rearm);
        add(ports, 0x10268, Respawn::window);
        add(ports, 0x10184, c -> forward(c, 0x108ce, 4, 0x160));
        add(ports, 0x101bc, c -> forward(c, 0x108ca, 2, 0x1a0));
        add(ports, 0x101f4, c -> backward(c, 0x108ce, 4, 0x160));
        add(ports, 0x1022e, c -> backward(c, 0x108ca, 2, 0x1a0));
    }

    private static int sw(int reg, int v) {
        return (reg & 0xffff0000) | (v & 0xffff);
    }

    private static int sb(int reg, int v) {
        return (reg & 0xffffff00) | (v & 0xff);
    }

    // ------------------------------------------------------------------ 0xCAEA

    static void caea(M68k c) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        a[5] = 0x10910;
        a[0] = 0x8282;
        d[0] = m.read32(0x108ee) - m.read32(a[5] + 2);
        m.write32(a[5] + 2, m.read32(a[5] + 2) + d[0]);
        m.write32(0xcada, m.read32(a[5] + 2));
        m.write32(0x108ca, m.read32(0x108ca) + d[0]);
        d[0] = sw(d[0], m.read16(0x108ca));
        m.write16(0x108c6, d[0]);
        d[0] = sw(d[0], (d[0] & 0xffff) >>> 4);
        m.write16(a[0] + 8, d[0]);
        d[0] = m.read32(0x108f2);
        m.write32(a[5] + 6, d[0]);
        m.write32(0xcade, d[0]);
        d[0] -= 0x900000;
        m.write32(0x108ce, d[0]);
        d[0] = (d[0] << 16) | (d[0] >>> 16);
        m.write16(0x108c8, d[0]);
        d[0] = sw(d[0], (d[0] & 0xffff) >>> 4);
        m.write16(a[0] + 0xa, d[0]);
        m.write32(a[0] + 0xc, 0);
        m.write16(a[0] + 0x10, 0);
        m.write32(a[0] - 0x1e, 0);
        m.write32(0x108d2, 0);
        m.write32(0x108d6, 0);
        m.write16(a[0] - 0x30c6, 0);
        int h = a[5];
        m.write16(h + 0x14, 0xfff0);
        m.write16(h + 0x16, 0xffc0);
        m.write16(h + 0x18, 0x20);
        m.write16(h + 0x1a, 0x40);
        m.write32(h + 0x60, 0x10000);
        m.write32(h + 0x2c, m.read32(h + 0x60));
        m.write32(h + 0x5c, 0xe400);
        m.write16(h + 0x3a, 0);
        m.write32(h + 0x28, 0);
        m.write8(h + 0x72, 0xff);
        m.write8(h + 0x74, 0);
        m.write8(h + 0x56, 0xff);
        m.write16(h + 0xfa, 0);
        m.write16(h + 0x68, 0);
        m.write8(0x8e26, 0xff);
        m.write8(0x8e2a, 0xff);
        m.write32(0x10ac6, 0xffff0000);
        m.write16(0x10ac0, 0x1d00);
        m.write16(0x10a42, m.read16(0x10b14));
        m.write16(0x10a54, m.read16(0x108f8));
        d[0] = 0;
        a[0] = 0x10a58;
        if (m.read32(0x10a50) != m.read32(0x10a54)) {
            d[0] = 0x80000;
        }
        m.write32(0x10a58, d[0]);
        for (int i = 0; i < 8; i++) {
            d[i] = 0;
        }
        for (int i = 0; i < 2; i++) {
            m.write32(0x10ad2 + 4 * i, 0);
        }
        for (int i = 0; i < 4; i++) {
            m.write32(0x10ada + 4 * i, 0);
        }
        for (int i = 0; i < 8; i++) {
            m.write32(0x10aea + 4 * i, 0);
        }
        m.write32(0x1090a, 0);
        m.write16(0x1090e, 0);
        m.write8(0x1112e, 0);
        m.write8(0x1112f, 0);
        m.write8(0x10a67, 0);
        m.write16(0x10abe, 0);
        a[0] = 0x10b16 + (short) m.read16(0x10b16);
        Calls.call(c, a[0]); // the level's own init
        d[0] = sb(d[0], m.read8(0x8276));
        if ((d[0] & 0x80) != 0) {
            d[0] = sb(d[0], m.read8(0x8279));
            if ((d[0] & 0xff) == 0) {
                m.write8(0x108e2, 0xff);
                m.write8(0x108e3, 0xec);
                a[0] = 0xcc7c;
                m.write16(a[0] + 0x4c, 0);
                m.write32(0x5a72, a[0]);
            }
        }
        m.write8(0x8279, 0);
        m.write8(0x11139, 0xff);
        loadObjects(c);
    }

    // ------------------------------------------------------------------ 0x102C4

    static void loadObjects(M68k c) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        a[1] = m.read32(0x82aa);
        a[2] = 0x178c8;
        a[1] -= 2;
        m.write16(a[1], 0);
        a[1] -= 2;
        m.write16(a[1], 0x8000);
        a[1] -= 2;
        m.write16(a[1], 0x8000);
        a[1] -= 2;
        m.write16(a[1], 0);
        m.write32(a[2], a[1]);
        a[2] += 4;
        a[1] += 8;
        while (true) {
            m.write32(a[2], a[1]);
            a[2] += 4;
            int v = m.read16(a[1]);
            a[1] += 2;
            if (v == 0) {
                break;
            }
            a[1] += 4;
            d[0] = sb(d[0], m.read8(a[1]));
            d[0] = sw(d[0], d[0] & 3);
            d[0] = sw(d[0], d[0] * 2);
            d[1] = sw(d[1], d[0]);
            d[1] = sw(d[1], d[1] << 2);
            d[0] = sw(d[0], d[0] + d[1]);
            a[1] = a[1] + 2 + (short) d[0];
        }
        m.write16(a[1], 0x7fff);
        m.write16(a[1] + 2, 0x7fff);
        m.write16(a[1] + 4, 0);
        a[1] += 6;
        killAll(c);
        a[0] = 0x17ed8;
        for (int i = 0; i < 32; i++) {
            m.write16(a[0], 0);
            a[0] += 0xba;
        }
        a[0] = 0x1a0ba;
        for (int i = 0; i < 16; i++) {
            m.write16(a[0], 0);
            a[0] += 0xba;
        }
        a[0] = 0x1961a;
        for (int i = 0; i < 40; i++) {
            m.write16(a[0], 0);
            a[0] += 0x44;
        }
        d[0] = 0xffff; // moveq then dbra
        m.write8(0x19618, 0xff);
        m.write8(0x1ac5a, 0xff);
        rearm(c);
        window(c);
    }

    /** 0x10354. */
    static void killAll(M68k c) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        m.write16(0x17782, 0);
        m.write16(0x17844, 0);
        a[6] = 0x17782;
        a[0] = 0x17514;
        a[1] = 0x17596;
        d[0] = sw(d[0], m.read16(a[0]));
        a[0] += 2;
        d[1] = sw(d[1], m.read16(a[1]));
        a[1] += 2;
        m.write16(a[6], d[0]);
        m.write16(a[6], m.read16(a[6]) + d[1]);
        a[6] += 2;
        d[2] = 1;
        while (true) {
            while (true) {
                d[0] = sw(d[0], d[0] - 1);
                if ((d[0] & 0xffff) == 0xffff) {
                    break;
                }
                m.write32(a[6], m.read32(a[0]));
                a[0] += 4;
                a[6] += 4;
            }
            a[0] = a[1];
            d[0] = sw(d[0], d[1]);
            d[2] = sw(d[2], d[2] - 1);
            if ((d[2] & 0xffff) == 0xffff) {
                break;
            }
        }
        Calls.call(c, 0xdcb6);
        m.write16(0x17514, 0);
        m.write16(0x17596, 0);
        m.write16(0x175d8, 0);
        m.write16(0x1767a, 0);
        m.write16(0x176c0, 0);
    }

    /** 0x103AA. */
    static void rearm(M68k c) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        if (m.read8(0x11139) == 0) {
            return;
        }
        a[1] = 0x178cc;
        while (true) {
            a[2] = m.read32(a[1]);
            a[1] += 4;
            d[0] = sw(d[0], m.read16(a[2]));
            if ((d[0] & 0xffff) == 0) {
                return;
            }
            d[0] = sw(d[0], d[0] & 0x7fff);
            d[0] = sw(d[0], d[0] - 0xb);
            if ((short) d[0] < 0) {
                continue;
            }
            d[0] = sw(d[0], d[0] - 2);
            if ((short) d[0] >= 0) {
                d[0] = sw(d[0], d[0] - 7);
                if ((short) d[0] <= 0) {
                    continue;
                }
            }
            m.write8(a[2], m.read8(a[2]) & 0x7f);
        }
    }

    /** 0x10268. */
    static void window(M68k c) {
        GameMemory m = c.mem;
        m.write32(0x17cd0, 0x178cc);
        m.write32(0x17cd4, 0x178cc);
        boolean vertical = m.read16(0x178c6) == 0;
        if (vertical) {
            forward(c, 0x108ce, 4, 0x160);
        } else {
            forward(c, 0x108ca, 2, 0x1a0);
        }
        c.a[0] = 0x17cd4;
        m.write32(0x17cd4, m.read32(0x17cd0));
        m.write32(0x17cd4, m.read32(0x17cd4) - 4);
        int flag = vertical ? 0x8266 : 0x8264;
        m.write16(flag, 1);
        Calls.call(c, 0xfeaa);
        m.write16(flag, 0);
    }

    /** 0x10184 / 0x101BC: cursors moved forward to camera - $40 and camera + span. */
    static void forward(M68k c, int cam, int field, int span) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        a[1] = m.read32(0x17cd0);
        d[0] = sw(d[0], m.read16(cam) - 0x40);
        do {
            a[2] = m.read32(a[1]);
            a[1] += 4;
        } while ((short) d[0] >= (short) m.read16(a[2] + field));
        a[1] -= 4;
        m.write32(0x17cd0, a[1]);
        a[1] = m.read32(0x17cd4);
        d[0] = sw(d[0], d[0] + span);
        do {
            a[2] = m.read32(a[1]);
            a[1] += 4;
        } while ((short) d[0] >= (short) m.read16(a[2] + field));
        a[1] -= 8;
        m.write32(0x17cd4, a[1]);
    }

    /** 0x101F4 / 0x1022E: the same, backwards. */
    static void backward(M68k c, int cam, int field, int span) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        a[1] = m.read32(0x17cd0);
        d[0] = sw(d[0], m.read16(cam) - 0x40);
        a[1] += 4;
        do {
            a[1] -= 4;
            a[2] = m.read32(a[1]);
        } while ((short) d[0] <= (short) m.read16(a[2] + field));
        a[1] += 4;
        m.write32(0x17cd0, a[1]);
        a[1] = m.read32(0x17cd4);
        d[0] = sw(d[0], d[0] + span);
        a[1] += 4;
        do {
            a[1] -= 4;
            a[2] = m.read32(a[1]);
        } while ((short) d[0] <= (short) m.read16(a[2] + field));
        m.write32(0x17cd4, a[1]);
    }
}
