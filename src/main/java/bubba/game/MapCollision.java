package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * Map collision routines (a5 = object, box +$14..+$1A, d0/d1 = position to test). The map at
 * (L_10888) holds, per cell, the offset of its block entry in the table at (L_10890), whose
 * first word is the block's attribute bits.
 */
final class MapCollision {

    private MapCollision() {
    }

    static void register(Map<Integer, Ports.Port> ports) {
        ports.put(0xf222, new Ports.Port() {
            @Override
            public void run(M68k c) {
                boxAttributes(c);
            }

            @Override
            public int[] outputs() {
                return new int[]{0};
            }
        });
    }

    /**
     * 0xF222: OR of the attributes of every cell under the box at (d0, d1); for the rows above
     * the last one only the bits $FFC1 count. Result in d0.w and $CAC8.
     */
    static void boxAttributes(M68k c) {
        GameMemory m = c.mem;
        int o = c.a[5];
        int x0 = (short) (c.d[0] + m.read16(o + 0x14));
        int y0 = (short) (c.d[1] + m.read16(o + 0x16));
        int x1 = (short) (x0 + m.read16(o + 0x18));
        int y1 = (short) (y0 + m.read16(o + 0x1a));
        int rowBytes = m.read16(0x10a48);
        int map = m.read32(0x10888), table = m.read32(0x10890);
        int a0 = map + (short) ((short) (x0 & 0xfff0) >> 3)
                + (int) ((long) (((short) y0 >> 4) & 0xffff) * rowBytes);
        if ((x1 & 15) != 0) {
            x1 = (short) ((x1 + 16) & 0xfff0);
        }
        int cols = (short) ((short) (x1 - (x0 & 0xfff0)) >> 4);
        if ((y1 & 15) != 0) {
            y1 = (short) ((y1 + 16) & 0xfff0);
        }
        int rows = (short) ((short) (y1 - (y0 & 0xfff0)) >> 4);
        int d0 = 0;
        int a4 = a0;
        int r = (short) (rows - 1);
        if (r > 0) {
            for (int k = 0; k < (r & 0xffff); k++) {
                for (int i = 0; i < (cols & 0xffff); i++) {
                    d0 |= m.read16(table + m.read16(a0));
                    a0 += 2;
                }
                a4 += rowBytes;
                a0 = a4;
            }
            d0 &= 0xffc1;
        }
        for (int i = 0; i < (cols & 0xffff); i++) {
            d0 |= m.read16(table + m.read16(a0));
            a0 += 2;
        }
        m.write16(0xcac8, d0);
        c.d[0] = d0; // moveq #0,d0 then or.w
    }
}
