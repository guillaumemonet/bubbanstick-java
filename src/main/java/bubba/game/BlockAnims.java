package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * Block graphics animation, exact ports:
 * <ul>
 *   <li>0xC6B4: queue $C750 (count, 20 entries of $16 bytes, started by 0xC67A from the level
 *       natives): timer, frame list (byte frame, byte delay; delay 0 = last), index, size, source,
 *       destination, destination step, blit size. Every frame the entries whose timer runs out
 *       blit frame * size + source onto the destination - kept as a graphics slot redirection
 *       ({@link GameMemory#blockGfx}) - or, with a negative source, call the routine at -source
 *       (level native, d1 = frame * size);</li>
 *   <li>0x51D4: the playfield redraw (blitter, every block with its graphics pointer number
 *       $10A64 / 4); its only memory effect is the phase $10A64 += $10A62 modulo $10A60.</li>
 * </ul>
 */
final class BlockAnims {

    private static final int QUEUE = 0xc750;

    private BlockAnims() {
    }

    static void register(Map<Integer, Ports.Port> ports) {
        ports.put(0xc6b4, BlockAnims::run);
        ports.put(0x51d4, BlockAnims::phase);
    }

    static void phase(M68k c) {
        GameMemory m = c.mem;
        int max = (short) m.read16(0x10a60);
        int v = (short) (m.read16(0x10a64) + m.read16(0x10a62));
        if (max <= v) {
            v = (short) (v - max);
        }
        m.write16(0x10a64, v);
    }

    static void run(M68k c) {
        GameMemory m = c.mem;
        int count = m.read16(QUEUE);
        int e = QUEUE + 2;
        c.a[1] = QUEUE;
        c.a[3] = 0xdff050;
        c.a[4] = 0xdff054;
        c.a[5] = 0xdff058;
        for (int left = count - 1; left >= 0; left--) {
            while ((short) m.read16(e) <= 0) {
                e += 0x16;
            }
            int t = (m.read16(e) - 1) & 0xffff;
            m.write16(e, t);
            if ((short) t > 0) {
                e += 0x16;
                continue;
            }
            int list = m.read32(e + 2);
            int idx = m.read16(e + 6);
            int a2 = list + (short) idx;
            m.write16(e + 6, idx + 2);
            int frame = m.read8(a2), delay = m.read8(a2 + 1);
            if (delay == 0) {
                m.write16(QUEUE, m.read16(QUEUE) - 1);
            }
            m.write16(e, delay);
            int d1 = frame * m.read16(e + 8);
            c.d[0] = 0xffff0000 | left;
            c.d[1] = d1;
            c.d[2] = (c.d[2] & 0xffff0000) | delay;
            if ((short) m.read16(e + 10) < 0) {
                int routine = -m.read32(e + 10);
                c.a[0] = e + 0x16;
                c.a[1] = QUEUE;
                c.a[2] = routine;
                c.a[3] = 0xdff050;
                c.a[4] = 0xdff054;
                c.a[5] = 0xdff058;
                Calls.call(c, routine); // movem d0/a0-a1/a3-a5 around it
                c.d[0] = 0xffff0000 | left;
                c.a[0] = e + 0x16;
                c.a[1] = QUEUE;
                c.a[3] = 0xdff050;
                c.a[4] = 0xdff054;
                c.a[5] = 0xdff058;
            } else {
                int src = d1 + m.read32(e + 10);
                int dst = m.read32(e + 14);
                m.write32(e + 14, dst + (short) m.read16(e + 18));
                blit(m, src, dst, m.read16(e + 20));
                c.d[1] = src;
                c.a[2] = dst + (short) m.read16(e + 18);
            }
            e += 0x16;
        }
    }

    /** Blit A -> D, one word wide, modulos 0: (size >> 6) words copied. */
    static void blit(GameMemory m, int src, int dst, int bltsize) {
        int h = bltsize >> 6 & 0x3ff, w = bltsize & 0x3f;
        int bytes = (h == 0 ? 1024 : h) * (w == 0 ? 64 : w) * 2;
        int s = src - 0x80000, d = dst - 0x80000;
        if (s < 0 || d < 0 || (s & 0x7f) != 0 || (d & 0x7f) != 0 || (bytes & 0x7f) != 0) {
            throw new IllegalStateException(String.format("block blit $%X -> $%X size $%X", src, dst, bytes));
        }
        for (int k = 0; k < bytes >> 7; k++) {
            int ds = (d >> 7) + k, ss = (s >> 7) + k;
            if (ds < m.blockGfx.length && ss < m.blockGfx.length) {
                m.blockGfx[ds] = m.blockGfx[ss];
            }
        }
    }
}
