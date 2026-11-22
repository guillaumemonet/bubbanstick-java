package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * 0xF9A2: collisions between solid objects. Exact port.
 * <p>
 * The solid objects are the list $1767A, plus Stix ($10988) while he is planted (+$83 bit 7,
 * his box then set to x-2 (x-30 when facing left) width $20). Every active object with +$83
 * bit 7 is tested against them: falling onto one, it lands (+$48 = carrier, +$6B, +$69 for
 * Stix, its +$40 = the carrier's x velocity); hitting one from below stops its rise (+$6A);
 * from the side it is pushed out (+$9E = side, both objects).
 */
final class SolidObjects {

    private static final int LIST = 0x1767a, STIX = 0x10988;

    private SolidObjects() {
    }

    static void register(Map<Integer, Ports.Port> ports) {
        ports.put(0xf9a2, SolidObjects::run);
    }

    private static int w(GameMemory m, int a) {
        return (short) m.read16(a);
    }

    static void run(M68k c) {
        GameMemory m = c.mem;
        int count = m.read16(LIST);
        if ((m.read8(STIX + 0x83) & 0x80) != 0) {
            m.write16(STIX + 0x14, w(m, STIX + 0x0a) >= 8 ? 0xfffe : 0xffe2);
            m.write16(STIX + 0x18, 0x20);
            m.write32(LIST + 2 + 4 * count, STIX);
            count = (count + 1) & 0xffff;
        }
        if (count == 0) {
            return;
        }
        int n = m.read16(0x17514);
        for (int i = 0; i < n; i++) {
            int o = m.read32(0x17516 + 4 * i);
            if ((m.read8(o + 0x83) & 0x80) == 0) {
                continue;
            }
            object(m, o, count);
        }
    }

    private static void object(GameMemory m, int o, int count) {
        int x0 = (short) (w(m, o + 2) + w(m, o + 0x14));
        int x1 = (short) (x0 + w(m, o + 0x18));
        int y0 = (short) (w(m, o + 6) + w(m, o + 0x16));
        int y1 = (short) (y0 + w(m, o + 0x1a));
        for (int j = 0; j < count; j++) {
            int s = m.read32(LIST + 2 + 4 * j);
            if (s == o) {
                continue;
            }
            int left = (short) (w(m, s + 2) + w(m, s + 0x14));
            if (x1 <= left) {
                continue;
            }
            int right = (short) (left + w(m, s + 0x18));
            if (x0 >= right) {
                continue;
            }
            int top = (short) (w(m, s + 6) + w(m, s + 0x16));
            if (y1 <= top) {
                continue;
            }
            int bottom = (short) (top + w(m, s + 0x1a));
            if (y0 >= bottom) {
                continue;
            }
            int oTop = (short) (w(m, o + 6) + w(m, o + 0x16));
            if (top > oTop) { // lands on s
                int d4 = (short) (y1 - top - 1);
                int carrier = s;
                while (m.read8(carrier + 0x6b) != 0) {
                    carrier = m.read32(carrier + 0x48);
                }
                d4 = (short) (d4 + w(m, carrier + 0x2c));
                if (d4 <= w(m, o + 0x2c)) {
                    m.write32(o + 0x48, s);
                    boolean stix = s == STIX;
                    m.write8(o + 0x69, stix ? 0xff : 0);
                    if (!stix) {
                        m.write8(o + 0x6b, 0xff);
                    }
                    m.write8(s + 0x6a, 0xff);
                    int y = top - w(m, o + 0x16) - w(m, o + 0x1a);
                    m.write32(o + 0x40, m.read32(s + 0x28));
                    m.write8(o + 0x64, 0xff);
                    m.write32(o + 0x2c, 0x10000);
                    m.write32(o + 6, (y & 0xffff) << 16);
                    return;
                }
            } else { // under s
                int d4 = (short) (y0 - bottom + 1);
                d4 = (short) (d4 + w(m, s + 0x2c));
                if (d4 >= w(m, o + 0x2c)) {
                    if (m.read8(o + 0x64) != 0) {
                        return;
                    }
                    int y = bottom - w(m, o + 0x16);
                    m.write32(o + 0x2c, 0);
                    m.write8(o + 0x6a, 0xff);
                    m.write32(o + 6, (y & 0xffff) << 16);
                    return;
                }
            }
            // 0xFAD8: from the side
            int dv = m.read32(o + 0x28) - m.read32(s + 0x28);
            int x;
            if (dv >= 0) {
                if ((byte) m.read8(s + 0x9e) > 0) {
                    return;
                }
                if ((short) ((dv >> 16) + 1 - x1 + left) < 0) {
                    continue;
                }
                x = left - w(m, o + 0x18) - w(m, o + 0x14);
                m.write8(o + 0x9e, 1);
                m.write8(s + 0x9e, 0xff);
            } else {
                if ((byte) m.read8(s + 0x9e) < 0) {
                    return;
                }
                if ((short) ((dv >> 16) - 1 - x0 + left + w(m, s + 0x18)) > 0) {
                    continue;
                }
                x = left + w(m, s + 0x18) - w(m, o + 0x14);
                m.write8(o + 0x9e, 0xff);
                m.write8(s + 0x9e, 1);
            }
            m.write32(o + 2, (x & 0xffff) << 16);
            m.write32(o + 0x28, 0);
            return;
        }
    }
}
