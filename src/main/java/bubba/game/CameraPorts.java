package bubba.game;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * Camera and scroll routines of the main loop.
 * <ul>
 *   <li>0x4A3A: camera. y = hero y - $90 (locked); x follows the hero with a velocity ($108D2)
 *       accelerated / braked by a law chosen from the hero's distance to the screen centre and
 *       direction (table 0x4B32 + 0x4BCA), clamped to the level ($108FA..$10902); a hero jump of
 *       more than 16 pixels (x) or 16 lines (y) moves the camera at once. Also the scroll deltas
 *       $8264/$8266 used by the object activation.</li>
 *   <li>0x4BFC: playfield scroll values (block column/row, fine scroll, BOB origin $829A/$829C).</li>
 * </ul>
 */
final class CameraPorts {

    private CameraPorts() {
    }

    static void register(Map<Integer, Ports.Port> ports) {
        ports.put(0x4a3a, CameraPorts::camera);
        ports.put(0x4bfc, CameraPorts::scroll);
    }

    private static int w(GameMemory m, int a) {
        return (short) m.read16(a);
    }

    /** 0x4A3A. */
    static void camera(M68k c) {
        GameMemory m = c.mem;
        m.write8(0x8273, 0);
        int camY = (short) (w(m, 0x10916) - 0x90);
        int dy = (short) (camY - w(m, 0x108c8));
        m.write16(0x108d6, dy);
        m.write16(0x108ce, camY);
        int d5 = m.read32(0x10912) - m.read32(0xcada); // hero x move (16.16)
        int d4 = Math.abs(d5);
        int vel;
        if (d4 > 0x100000 || Math.abs(dy) > 16
                || m.read8(0x8275) != 0) {
            // teleport-like move: the camera jumps by the same amount (0x4A32)
            m.write8(0x8273, 0xff);
            vel = d5;
        } else {
            int d3 = 0, d6 = d5;
            int d7 = (short) (w(m, 0x10912) - w(m, 0x108ca) - 0x90);
            if (d7 < 0) {
                d3 = -1;
                d6 -= 1;
                d7 = -d7;
            }
            int sel = d7 >= 0x30 ? (d7 >= 0x40 ? 8 : 4) : 0;
            if ((d3 ^ d6) < 0) {
                sel += 2;
            }
            int a, max, brake;
            switch (sel) {
                case 0, 2 -> {
                    a = d4 >> 2;
                    max = d4 + (d4 >> 1);
                    brake = 0x8000;
                }
                case 4 -> {
                    a = d4 >> 2;
                    max = d4 + (d4 >> 1);
                    brake = 0x10000;
                }
                case 6 -> {
                    a = d4 >> 2;
                    max = d4;
                    brake = (a >> 1) + 0x8000;
                }
                case 8 -> {
                    a = d4 >> 1;
                    max = d4 + d4;
                    brake = d4 != 0 ? d4 >> 2 : Math.abs(m.read32(0x108d2)) >> 1;
                }
                default -> { // 10
                    a = d4 >> 1;
                    max = d4 - (d4 >> 1);
                    brake = (d4 >> 1) + 0x20000;
                }
            }
            vel = accelerate(d5, m.read32(0x108d2), a, max, brake);
        }
        // 0x4ACE: apply and clamp
        m.write32(0x108d2, vel);
        int x = m.read32(0x108ca) + vel;
        if (x < m.read32(0x108fa)) {
            x = m.read32(0x108fa);
            m.write32(0x108d2, 0);
        } else if (x > m.read32(0x10902)) {
            x = m.read32(0x10902);
            m.write32(0x108d2, 0);
        }
        m.write32(0x108ca, x);
        m.write32(0x11142, x);
        int y = m.read16(0x108ce);
        m.write16(0x11146, y);
        int oldX = w(m, 0x108c6), oldY = w(m, 0x108c8);
        int nx = x >> 16;
        m.write16(0x108c6, nx);
        m.write16(0x108c8, y);
        m.write16(0x8264, nx - oldX);
        m.write16(0x8266, y - oldY);
    }

    /** 0x4BCA: velocity change (d3 = hero move, d4 = velocity, d0 = acceleration, d1 = limit, d2 = brake). */
    private static int accelerate(int d3, int d4, int d0, int d1, int d2) {
        if (d3 == 0) {
            if (d4 > 0) {
                d4 -= d2;
                return d4 < 0 ? 0 : d4;
            }
            if (d4 < 0) {
                d4 += d2;
                return d4 > 0 ? 0 : d4;
            }
            return d4;
        }
        if (d3 < 0) {
            d0 = -d0;
        }
        d4 += d0;
        if (d4 >= 0) {
            return d4 > d1 ? d1 : d4;
        }
        return d4 < -d1 ? -d1 : d4;
    }

    /** 0x4BFC. */
    static void scroll(M68k c) {
        GameMemory m = c.mem;
        int x = (short) (m.read16(0x108ca) + m.read16(0x1090a));
        m.write16(0x828a, x >> 4);
        int fine = (-x) & 15;
        m.write16(0x828e, fine | fine << 4);
        m.write16(0x8290, x & 15);
        int y = (short) (m.read16(0x108ce) + m.read16(0x1090c) - 6);
        if (y < 0) {
            y = 0;
        }
        m.write16(0x828c, y >> 4);
        m.write16(0x8292, y & 15);
        int d0 = -(x & 0xfff0);
        if ((short) m.read16(0x8264) >= 0) {
            d0 += 0x10;
        }
        int d1 = (short) (m.read16(0x8292) - m.read16(0x108ce));
        int d2 = (short) m.read16(0x828c), d3 = d2;
        if (d2 > 0 && !((short) m.read16(0x8266) < 0 && (short) m.read16(0x8292) > 3)) {
            d1 += 0x10;
            d2 -= 1;
        }
        m.write16(0x51d0, d3 - d2);
        d1 -= m.read16(0x1090c);
        m.write16(0x829a, d0);
        m.write16(0x829c, d1);
    }
}
