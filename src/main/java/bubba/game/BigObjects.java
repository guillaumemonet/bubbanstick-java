package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * 0xF04E: update of the big objects (platforms, lifts, doors...), list $17596. Exact port.
 * <p>
 * Flags byte +$6E: bit 7 = trigger zones off, 6 = destroy after the script, 5 = waiting for the
 * other state, 4 = one-shot, 3 = frozen (no script, velocity 0). +$70 = action on trigger
 * (multiple of 4). +$72..+$85 = the other state (animation, script, 2 swapped words) exchanged
 * with +$0C/+$10 by {@link #swap}.
 */
final class BigObjects {

    private static final int LIST = 0x17596, JUMPED = 0xdc8e;

    private BigObjects() {
    }

    static void register(Map<Integer, Ports.Port> ports) {
        ports.put(0xf04e, BigObjects::update);
    }

    static void update(M68k c) {
        GameMemory m = c.mem;
        int n = m.read16(LIST);
        for (int i = 0; i < n; i++) { // bra to the dbra: n objects
            one(c, m, m.read32(LIST + 2 + 4 * i));
        }
    }

    private static void one(M68k c, GameMemory m, int o) {
        if ((m.read8(o + 0x6e) & 0x08) != 0) {
            m.write32(o + 0x28, 0);
            m.write32(o + 0x2c, 0);
        } else {
            while (true) {
                m.write8(o + 0x8b, m.read8(o + 0x8b) + 2);
                m.write8(JUMPED, 0);
                if (m.read32(o + 0x10) <= 0) {
                    break;
                }
                c.a[5] = o;
                BehaviourPort.engineBig(c);
                if (m.read8(JUMPED) == 0) {
                    break;
                }
                int f = m.read8(o + 0x6e);
                if ((f & 0x40) != 0) {
                    kill(c, m, o);
                    return;
                }
                if ((f & 0x20) == 0) {
                    continue;
                }
                f = m.read8(o + 0x6e);
                if ((f & 0x10) != 0) {
                    m.write8(o + 0x6e, f & ~0x10);
                } else {
                    m.write8(o + 0x6e, f & ~0x20);
                }
                int act = m.read16(o + 0x70);
                if (act == 0x2c) {
                    m.write8(o + 0x6e, m.read8(o + 0x6e) & ~0x80);
                }
                if (act == 0x14) {
                    m.write8(o + 0x6e, m.read8(o + 0x6e) | 0x40);
                }
                swap(m, o);
                return;
            }
            m.write16(o + 0x86, m.read16(o + 0x02) - m.read16(0x10912));
            m.write16(o + 0x88, m.read16(o + 0x06) - m.read16(0x10916));
            c.a[5] = o;
            Calls.call(c, 0xdf38);
        }
        // 0xF0EA: trigger zones
        int f = m.read8(o + 0x6e);
        if ((f & 0x80) != 0) {
            return;
        }
        int act = m.read16(o + 0x70);
        if ((f & 0x20) != 0 && act == 0x20) {
            return;
        }
        c.d[0] = (c.d[0] & 0xffff0000) | m.read16(o + 0x02);
        c.d[1] = (c.d[1] & 0xffff0000) | m.read16(o + 0x06);
        c.a[2] = m.read32(o + 0x54);
        c.a[5] = o;
        Calls.call(c, 0x103d8);
        if (c.z) {
            switch (act) {
                case 0x24 -> clear(m, o, 0x08);
                case 0x34 -> set(m, o, 0x08);
                case 0x1c -> kill(c, m, o);
                case 0x30 -> {
                    set(m, o, 0xc0);
                    swap(m, o);
                }
                default -> {
                }
            }
            return;
        }
        switch (act) { // jump table 0xF182
            case 0x00 -> m.write8(o + 0x6e, m.read8(o + 0x6e) ^ 0x08);
            case 0x04, 0x10, 0x28 -> set(m, o, 0x20);
            case 0x08, 0x14 -> set(m, o, 0xa0);
            case 0x0c -> kill(c, m, o);
            case 0x18 -> {
                set(m, o, 0xc0);
                swap(m, o);
            }
            case 0x20 -> set(m, o, 0x30);
            case 0x24 -> set(m, o, 0x08);
            case 0x2c -> {
                set(m, o, 0xa0);
                swap(m, o);
            }
            case 0x34 -> clear(m, o, 0x08);
            case 0x1c, 0x30 -> {
            }
            default -> throw new IllegalStateException(String.format("big object action $%X", act));
        }
    }

    private static void set(GameMemory m, int o, int bits) {
        m.write8(o + 0x6e, m.read8(o + 0x6e) | bits);
    }

    private static void clear(GameMemory m, int o, int bits) {
        m.write8(o + 0x6e, m.read8(o + 0x6e) & ~bits);
    }

    /** 0xF176. */
    private static void kill(M68k c, GameMemory m, int o) {
        m.write8(o + 0x01, 4);
        c.a[5] = o;
        Calls.call(c, 0xdc90);
    }

    /** 0xF150: exchange the two states, the new one starts. */
    private static void swap(GameMemory m, int o) {
        int d0 = m.read32(o + 0x72), d1 = m.read32(o + 0x76), d2 = m.read32(o + 0x7a);
        int d3 = m.read32(o + 0x7e), d4 = m.read32(o + 0x82);
        d4 = (d4 << 16) | (d4 >>> 16);
        m.write32(o + 0x72, d2);
        m.write32(o + 0x76, d3);
        m.write32(o + 0x7a, d0);
        m.write32(o + 0x7e, d1);
        m.write32(o + 0x82, d4);
        m.write32(o + 0x0c, d2);
        m.write32(o + 0x10, d3);
        m.write16(o + 0x34, 0);
        m.write16(o + 0x4e, 0);
    }
}
