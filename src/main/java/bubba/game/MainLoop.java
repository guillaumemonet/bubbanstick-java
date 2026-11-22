package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

/**
 * 0x3AF0: one turn of the game's main loop, in Java (register level: the routines it calls
 * receive the registers the original leaves). The VBL waits are not part of the logic; the
 * keyboard (0x79CE) and the BOB display (0x8470) are hooks of the emulator.
 */
public final class MainLoop {

    private MainLoop() {
    }

    private static int sw(int reg, int v) {
        return (reg & 0xffff0000) | (v & 0xffff);
    }

    private static int sb(int reg, int v) {
        return (reg & 0xffffff00) | (v & 0xff);
    }

    /** One frame; returns false when the level is over (the original goes to 0x3CF0). */
    public static boolean frame(M68k c) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        m.write8(0x827c, 0);
        m.write16(0x10b0a, m.read16(0x10b0a) + 1);
        m.write32(0xcae6, m.read32(0x108ca));
        // random generator: 4 steps
        a[0] = 0x10894;
        for (int k = 0; k < 4; k++) {
            d[k] = (short) m.read16(a[0] + 2 * k);
        }
        d[4] = 3;
        for (int i = 0; i < 4; i++) {
            d[1] = sw(d[1], d[1] ^ d[0]);
            d[2] = sw(d[2], d[2] ^ d[1]);
            d[3] = sw(d[3], d[3] ^ d[2]);
            int w = d[3] & 0xffff;
            d[3] = sw(d[3], (w >>> 1) | (w << 15));
            d[0] = sw(d[0], d[0] ^ d[3]);
            d[2] = sw(d[2], d[2] + d[0]);
            d[1] = sw(d[1], d[1] - d[3]);
        }
        d[4] = 0xffff;
        for (int k = 0; k < 4; k++) {
            m.write16(a[0] + 2 * k, d[k]);
        }
        d[4] = sb(d[4], m.read8(0x826c)); // VBL wait (not part of the logic)
        m.write32(0xcae2, m.read32(0xcada));
        m.write32(0xcada, m.read32(0x10912));
        m.write32(0xcade, m.read32(0x10916));
        d[0] = sb(d[0], m.read8(0x10abc));
        if ((d[0] & 0xff) != 0) {
            int t = (short) (m.read16(0x10ac4) - 1);
            m.write16(0x10ac4, t);
            if (t <= 0) {
                return false;
            }
        }
        d[0] = sb(d[0], m.read8(0x4050));
        if ((d[0] & 0xff) != 0) {
            throw new IllegalStateException("pause mode (0x3B9C) not supported");
        }
        Calls.call(c, 0x79ce); // keyboard
        if (!c.z) {
            int key = d[0] & 0xff;
            if (key == 0xe6) {
                throw new IllegalStateException("pause key (0x3B9C) not supported");
            }
            if (key == 0xba) { // give up the level
                m.write8(0x8278, 0xff);
                m.write8(0x10abc, 0xff);
                m.write16(0x10ac4, 0);
                m.write8(0x108e7, 0);
                m.write8(0x827f, 0);
            }
        }
        Calls.call(c, 0xc6b4);
        Calls.call(c, 0xdee0);
        a[0] = 0x1750b;
        if (m.read8(0x10966) == 0) {
            m.write8(a[0], m.read8(a[0]) & 0xf9);
            Calls.call(c, 0xbea8);
        } else {
            m.write8(a[0], m.read8(a[0]) & 0xfb);
        }
        Calls.call(c, 0xf04e);
        a[0] = 0x1750b;
        m.write8(a[0], (m.read8(a[0]) & 6) | 0x19);
        if (m.read8(0x10aba) != 0 || m.read16(0xbda4) == 0x32) {
            m.write8(a[0], m.read8(a[0]) & ~1);
        }
        Calls.call(c, 0xf9a2);
        if (m.read8(0x10966) != 0) {
            m.write8(0x1750b, m.read8(0x1750b) & 0xfd);
        }
        Calls.call(c, 0x9efa);
        Calls.call(c, 0xd042);
        d[1] = (m.read8(0xcac8) & 4) != 0 ? 0 : 0x24;
        m.write16(0x61b62, d[1]); // chip hunk +$1B62 (copper list)
        int h = a[5];
        d[0] = sw(d[0], m.read16(h + 0x66));
        boolean moved = (d[0] & 1) != 0;
        if (!moved) {
            d[1] = sw(d[1], m.read16(h + 2));
            moved = (d[1] & 0xffff) != m.read16(h + 0x20);
        }
        if (moved) {
            m.write16(h + 0x68, d[0]);
            m.write8(h + 0x65, m.read8(h + 0x55));
            m.write16(h + 0x6a, m.read16(h + 6));
        }
        if (m.read8(h + 0x6c) != 0 && m.read32(h + 0x28) == 0) {
            m.write8(h + 0x57, 0);
            a[0] = 0xbda6;
            a[1] = h;
            d[1] = 0;
            Calls.call(c, 0x9fd8);
        }
        Calls.call(c, 0xf5f6);
        Calls.call(c, 0x4a3a);
        Calls.call(c, 0x4bfc);
        Calls.call(c, 0x51d4);
        Calls.call(c, 0xfeaa);
        Calls.call(c, 0xfdba);
        Calls.call(c, 0x539e);
        Calls.call(c, 0x872a);
        m.write32(0x8296, m.read32(0x8286));
        m.write8(0x8270, 0xff);
        Calls.call(c, 0x8470);
        a[0] = 0x826c;
        if (m.read8(a[0] + 4) != 0) {
            m.write8(a[0], 0xff);
        }
        Calls.call(c, 0xdcb6);
        return true;
    }
}
