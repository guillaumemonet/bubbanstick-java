package bubba.game;

import static bubba.game.Regs.*;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * 0x9EFA: Bubba's state machine, once per logic frame, and its epilogue 0x9FD8. Exact port.
 * <p>
 * State word $BDA4 (offset into the handler table 0xA014), sub-state +$4E (in d1). The
 * joystick byte $8269 is first passed to the input routine (L_0833E), or replaced by a scripted
 * one while $BDA2 &gt; 0 ($BDA1, $BDA0 set at the end). Before the handler: Stix control
 * (+$114 = 4 when fire is held long enough, +$5A counter, 0xA8E6 / 0xA970 throw), and
 * the out-of-the-map check (0xADDE) or the special mode +$1BA (0xB6FC). The handler is called
 * with the condition codes loaded from the joystick byte (move.w d0, ccr) - handlers that are
 * not ported yet run on the interpreter.
 * <p>
 * Epilogue: +$4E = d1, +$52 = joystick, +$3A (invulnerability) counted down, pending state
 * $2C when $BDA0 and $BDC6, then the animation frame +$50 from the tables 0xA04C.
 */
final class HeroStates {

    private static final int H = 0x10910;
    private static final int[] ALL = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};

    private HeroStates() {
    }

    static void register(Map<Integer, Ports.Port> ports) {
        ports.put(0x9efa, new Ports.Port() {
            @Override
            public void run(M68k c) {
                dispatch(c);
            }

            @Override
            public int[] outputs() {
                return ALL;
            }
        });
        ports.put(0x9fd8, new Ports.Port() {
            @Override
            public void run(M68k c) {
                epilogue(c);
            }

            @Override
            public int[] outputs() {
                return ALL;
            }
        });
    }

    static void dispatch(M68k c) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        a[0] = 0xbda4;
        if ((short) m.read16(0xbda2) > 0) {
            // 0x9EEA: scripted joystick
            int t = (m.read16(0xbda2) - 1) & 0xffff;
            m.write16(0xbda2, t);
            m.write8(0xbda0, t == 0 ? 0xff : 0);
            m.write8(0x8269, m.read8(0xbda1));
            a[0] = 0xbda4;
        } else {
            a[1] = 0x833e + 8;
            a[2] = m.read32(0x833e);
            d[0] = sb(d[0], m.read8(0x8269));
            Calls.call(c, a[2]);
        }
        // 0x9F10
        d[2] = sw(d[2], m.read16(a[0]));
        a[0] += 2;
        a[1] = H;
        m.write8(H + 0x55, m.read8(H + 0x54));
        d[1] = sw(d[1], m.read16(H + 0x4e));
        d[0] = sb(d[0], m.read8(0x8269));
        d[3] = sb(d[3], m.read8(H + 0x1ba));
        if ((d[3] & 0xff) != 0) {
            Calls.call(c, 0xb6fc);
            return;
        }
        int st = d[2] & 0xffff;
        if (st != 0x14 && st != 0x12 && st != 0x24) {
            d[3] = sw(d[3], m.read16(H + 6) - 0x30 - m.read16(0x10a5e));
            if ((short) d[3] >= 0) {
                Calls.call(c, 0xadde); // fell out of the map
                return;
            }
        }
        // 0x9F50: Stix
        int target = 0;
        int joy = d[0] & 0xff;
        if ((m.read8(H + 0x56) & 0x80) == 0) {
            if (joy >= 0x40) {
                d[3] = 4;
                if (4 < (short) m.read16(H + 0x114)) {
                    d[4] = sw(d[4], m.read16(H + 0x114));
                    m.write16(H + 0x114, 4);
                    m.write8(H + 0x5a, 0);
                    m.write8(H + 0xfb, m.read8(H + 0xfb) & ~2);
                    boolean clear = true;
                    if ((d[4] & 0xffff) == 6) {
                        d[3] = m.read32(H + 0xbc);
                        if (d[3] == 0) {
                            clear = false;
                        } else {
                            a[2] = d[3];
                            m.write8(a[2] + 0x9c, 0);
                        }
                    }
                    if (clear) {
                        m.write32(H + 0xbc, 0);
                    }
                }
            }
        } else if ((m.read8(H + 0x5b) & 0x80) == 0) {
            d[3] = sb(d[3], m.read8(0x827d));
            if ((d[3] & 0xff) != 0) {
                if ((joy & 0x80) != 0) {
                    target = 0xa8e6;
                } else if ((joy & 0x40) != 0) {
                    target = 0xa970;
                }
            } else if ((joy & 0x80) != 0) {
                int n = (m.read8(H + 0x5a) + 1) & 0xff;
                m.write8(H + 0x5a, n);
                if ((byte) n >= 6) {
                    m.write8(H + 0x5a, 0);
                    target = 0xa970;
                }
            } else if ((byte) m.read8(H + 0x5a) > 0) {
                m.write8(H + 0x5a, 0);
                target = 0xa8e6;
            }
        }
        if (target != 0) {
            Calls.call(c, target); // they end through the epilogue themselves
            return;
        }
        // 0x9FCE: the handler, condition codes = joystick byte
        d[2] = sw(d[2], m.read16(0xa014 + (short) d[2]));
        c.setCcr(d[0] & 0x1f);
        Calls.call(c, 0xa014 + (short) d[2]);
        epilogue(c);
    }

    /** 0x9FD8. */
    static void epilogue(M68k c) {
        GameMemory m = c.mem;
        int[] d = c.d, a = c.a;
        int h = a[1];
        m.write16(h + 0x4e, d[1]);
        m.write8(h + 0x52, m.read8(0x8269));
        if ((short) m.read16(h + 0x3a) > 0) {
            m.write16(h + 0x3a, m.read16(h + 0x3a) - 1);
        }
        a[0] -= 2;
        d[2] = sw(d[2], m.read16(a[0]));
        if (m.read8(a[0] - 4) != 0) {
            m.write8(a[0] - 4, 0);
            if (m.read32(a[0] + 0x22) != 0) {
                m.write16(a[0], 0x2c);
            }
        }
        a[2] = 0xa04c;
        d[2] = sw(d[2], m.read16(a[2] + (short) d[2]));
        d[2] = sw(d[2], d[2] + d[1]);
        m.write16(h + 0x50, m.read16(a[2] + 0x38 + (short) d[2]));
    }
}
