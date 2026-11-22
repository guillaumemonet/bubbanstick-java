package bubba.game;

import static bubba.game.Regs.*;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * End of the level start and status bar init, exact ports (register level):
 * <ul>
 *   <li>0x6C18: work pointers, sound driver entry $108EA, block animation queue cleared
 *       (0xC666), the level's own start routine (offset table $10B18, set code), the check
 *       point (0xCAEA), the input routine $833E (0x6CD8: none), then Bubba standing (0xA652)
 *       or the end-of-level state (0xB96E), through the epilogue 0x9FD8;</li>
 *   <li>0x8638: status bar sprites drawn (score digits, energy boxes, lives) and the values
 *       reset ($10AB0.., energy refilled step by step from $108BE).</li>
 * </ul>
 * Some immediates of the original are relocated pointers (chip hunk): they are read from the
 * code image.
 */
final class LevelStart {

    private static final int[] ALL = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};

    private LevelStart() {
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
        add(ports, 0x6c18, LevelStart::start);
        add(ports, 0x8638, LevelStart::statusBar);
        add(ports, 0xc666, LevelStart::clearAnims);
        ports.put(0x6cd8, c -> { }); // input routine: nothing (rts)
    }

    /** 0x6C18. */
    static void start(M68k c) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        m.write32(0x1113a, m.read32(0x6c1a)); // relocated immediates
        m.write32(0x1113e, m.read32(0x6c24));
        m.write8(0x10a66, 0);
        a[0] = 0x1114a;
        m.write32(a[0], m.read32(0x108fa));
        m.write32(a[0] + 4, m.read32(0x10902));
        a[0] += 8;
        m.write32(0x108ea, 0x7bc8);
        clearAnims(c);
        a[0] = 0x10b18 + (short) m.read16(0x10b18);
        Calls.call(c, a[0]); // the level's start routine
        m.write32(0x42a2, m.read32(0x6c5e));
        m.write32(0x108ca, 0);
        m.write32(0x10912, 0x900000);
        Calls.call(c, 0xcaea);
        m.write16(0x108c2, 0);
        m.write16(0x108c4, 0);
        d[0] = sb(d[0], m.read8(0x108e8));
        if ((d[0] & 0xff) == 0) {
            a[0] = 0x6cd8;
            m.write32(0x833e, a[0]);
            m.write32(0x10aa8, 0x17596);
            m.write32(0x10aac, 0x17514);
            m.write16(0x10b0a, 0);
        }
        m.write32(0x5a6e, 0);
        a[0] = 0xbda6;
        a[1] = 0x10910;
        if (m.read8(0x108e7) == 0) {
            HeroHandlers.standing(c);
        } else {
            Calls.call(c, 0xb96e);
        }
        HeroStates.epilogue(c);
    }

    /** 0xC666: block animation queue cleared. */
    static void clearAnims(M68k c) {
        GameMemory m = c.mem;
        c.a[0] = 0xc750;
        m.write16(c.a[0], 0);
        c.a[0] += 2;
        for (int i = 0; i < 20; i++) {
            m.write16(c.a[0], 0);
            c.a[0] += 0x16;
        }
        c.d[0] = 0xffff;
    }

    /** 0x8638. */
    static void statusBar(M68k c) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        for (int k = 0; k < 8; k++) { // movem.l $8618(pc) (shown $8616 by the disassembly)
            d[k] = m.read32(0x8618 + 4 * k);
            m.write32(0x83cc + 4 * k, d[k]);
        }
        a[6] = m.read32(0x82a6);
        a[0] = 0x10ab0;
        m.write32(a[0], 0);
        m.write16(a[0] + 4, 0);
        m.write16(a[0] + 6, 1);
        m.write16(a[0] + 8, m.read16(0x108be));
        m.write16(0x108be, 0);
        a[0] += 10;
        m.write32(a[0], 0);
        // score digits
        a[0] = 0x45fbc;
        a[1] = a[6] + 0x1519;
        d[0] = 5;
        d[1] = m.read32(0x108b8) >>> 8;
        d[2] = 0x6b0001;
        while (true) {
            a[2] = a[1];
            d[3] = sw(d[3], d[1]);
            d[1] >>>= 4;
            d[3] = sw(d[3], d[3] & 0xf);
            d[3] = sw(d[3], d[3] << 3);
            d[4] = sw(d[4], d[3]);
            d[3] = sw(d[3], d[3] * 2);
            d[3] = sw(d[3], d[3] + d[4]);
            a[3] = a[0] + (short) d[3];
            for (int i = 0; i < 24; i++) {
                m.write8(a[2], m.read8(a[3]++));
                a[2] += 2;
            }
            d[5] = 0xffff;
            a[1] -= (short) d[2];
            d[2] = (d[2] << 16) | (d[2] >>> 16);
            d[0] = sw(d[0], d[0] - 1);
            if ((d[0] & 0xffff) == 0xffff) {
                break;
            }
        }
        // energy boxes: full ones then empty ones
        a[0] = a[6] + 0x1478;
        d[0] = sw(d[0], m.read16(0x108be));
        d[1] = 6;
        d[1] = sw(d[1], d[1] - d[0]);
        while (true) {
            d[0] = sw(d[0], d[0] - 1);
            if ((d[0] & 0xffff) == 0xffff) {
                break;
            }
            a[1] = 0x45f8c;
            a[2] = a[0];
            for (int i = 0; i < 24; i++) {
                m.write8(a[2], m.read8(a[1]++));
                a[2] += 2;
            }
            d[3] = 0xffff;
            a[0] += (short) d[2];
            d[2] = (d[2] << 16) | (d[2] >>> 16);
        }
        while (true) {
            d[1] = sw(d[1], d[1] - 1);
            if ((d[1] & 0xffff) == 0xffff) {
                break;
            }
            a[1] = 0x45fa4;
            a[2] = a[0];
            for (int i = 0; i < 24; i++) {
                m.write8(a[2], m.read8(a[1]++));
                a[2] += 2;
            }
            d[3] = 0xffff;
            a[0] += (short) d[2];
            d[2] = (d[2] << 16) | (d[2] >>> 16);
        }
        // lives: the "x" and the digit
        a[0] = 0x45fbc;
        a[1] = a[0] + 0xf0;
        d[0] = sw(d[0], m.read16(0x108bc));
        if ((short) d[0] > 10) {
            d[0] = 10;
        }
        d[0] = sw(d[0], d[0] << 3);
        d[1] = sw(d[1], d[0]);
        d[0] = sw(d[0], d[0] * 2);
        d[0] = sw(d[0], d[0] + d[1]);
        a[0] += (short) d[0];
        a[2] = a[6] + 0x1584;
        for (int i = 0; i < 24; i++) {
            m.write8(a[2]++, m.read8(a[1]++));
            m.write8(a[2]++, m.read8(a[0]++));
        }
        d[0] = 0xffff;
        m.write32(0x10ac6, 0xffff0000);
    }
}
