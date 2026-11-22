package bubba.game;

import static bubba.game.Regs.*;

import bubba.cpu.GameMemory;
import bubba.cpu.M68k;

import java.util.Map;

/**
 * The final boss of stage 5 (level file "6"): the object-script natives of its parts, ported at
 * register level. The parts are the objects whose addresses are kept at $10AEA, $10AEE, $10AF2,
 * $10AF6, $10AFA (body pieces) and $10AFE (the brain: its +$9D is the boss energy, beaten at
 * $6C or below). $10AD6 = the phase, $10ADA = the sequencer counter (0x14E9C clears it).
 * <p>
 * $12356 is the end of the game: $108E9 set, then 1000 frames of the recorded exit walk
 * ($BDA1 / $BDA2), after which the level ends and the ending starts.
 */
final class Stage5Boss {

    private static final int[] ALL = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};
    private static final int P0 = 0x10aea, P1 = 0x10aee, P2 = 0x10af2, P3 = 0x10af6, P4 = 0x10afa,
            BRAIN = 0x10afe, PHASE = 0x10ad6;

    private Stage5Boss() {
    }

    private static void add(Map<Integer, Ports.Port> m, int addr, M68k.Hook h) {
        m.put(addr, new Ports.Port() {
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

    static void register(Map<Integer, Ports.Port> m) {
        add(m, 0x12356, c -> { // the end of the game
            GameMemory g = c.mem;
            g.write8(0x108e9, 0xff);
            g.write8(0xbda1, 0);
            g.write16(0xbda2, 0x3e8);
            c.a[0] = 0xbda4;
        });
        add(m, 0x12414, c -> { // (a0): random x offset -8..7, no position
            rngA(c, 1);
            c.d[1] = sw(c.d[1], c.d[1] & 0xf);
            c.d[1] = sw(c.d[1], c.d[1] - 8);
            c.mem.write16(c.a[0] + 0x38, c.mem.read16(c.a[0] + 0x38) + c.d[1]);
            c.mem.write32(c.a[0] + 2, 0);
        });
        add(m, 0x12508, c -> nearPart1(c, 0x1e));
        add(m, 0x12522, c -> nearPart1(c, 7));
        add(m, 0x124d2, c -> {
            nearPart1(c, 0x1e);
            releaseUnlessFlag(c);
        });
        add(m, 0x124d6, c -> {
            nearPart1(c, 7);
            releaseUnlessFlag(c);
        });
        add(m, 0x1380E, c -> {
            GameMemory g = c.mem;
            g.write16(0x10a60, 0x40);
            g.write16(0x10a62, g.read16(0x12d58));
            g.write32(0x1089c, g.read32(0x12d5a));
            c.a[0] = 0x10a62;
        });
        add(m, 0x148e2, Stage5Boss::spawnRandom);
        add(m, 0x14aba, c -> { // a new random frame (2, 4 or 6, not the current one)
            GameMemory g = c.mem;
            rngA(c, 0);
            c.d[2] = sw(c.d[2], g.read16(c.a[5] + 0x44));
            for (int guard = 0; guard < 16; guard++) {
                c.d[1] = sw(c.d[1], c.d[1] & 6);
                if ((c.d[1] & 0xffff) != 0 && (c.d[1] & 0xffff) != (c.d[2] & 0xffff)) {
                    break;
                }
                c.d[1] = sw(c.d[1], c.d[1] - 6);
            }
            g.write16(c.a[5] + 0x44, c.d[1]);
            c.d[1] = sw(c.d[1], c.d[1] + 0x104);
            g.write16(c.a[5] + 0xa, c.d[1]);
        });
        add(m, 0x14b44, c -> randomSpeed(c, 0));
        add(m, 0x14b82, c -> randomSpeed(c, -1));
        add(m, 0x14bda, c -> atPart1(c, sw(c.d[0], 0xff0b)));
        add(m, 0x14be0, c -> atPart1(c, 0x22));
        add(m, 0x14dbe, c -> {
            GameMemory g = c.mem;
            c.a[0] = g.read32(P2);
            g.write32(c.a[5] + 2, g.read32(c.a[0] + 2));
            g.write32(c.a[5] + 6, g.read32(c.a[0] + 6));
        });
        add(m, 0x14eba, c -> { // the parts gather on part 0 (x), 30 lower
            GameMemory g = c.mem;
            c.a[0] = 0x154b2;
            g.write32(c.a[5] + 0xc, c.a[0]);
            g.write16(0x10ada, 0x48);
            int a3 = c.a[3];
            c.a[1] = g.read32(P0);
            c.a[0] = 0x14f2e;
            g.write32(c.a[1] + 0xc, c.a[0]);
            c.a[0] = 0x14f6e;
            g.write32(c.a[1] + 0x10, c.a[0]);
            c.d[1] = sw(c.d[1], g.read16(c.a[1] + 2));
            c.a[0] = P1;
            c.a[1] = 0x14f1a;
            c.a[3] = 0x14f12;
            for (int i = 0; i < 4; i++) {
                c.a[2] = g.read32(c.a[0]);
                c.a[0] += 4;
                g.write16(c.a[2] + 0xa, g.read16(c.a[3]));
                c.a[3] += 2;
                g.write16(c.a[2] + 2, c.d[1]);
                g.write16(c.a[2] + 6, g.read16(c.a[2] + 6) + 0x1e);
                g.write32(c.a[2] + 0x10, c.a[1]);
            }
            c.d[0] = 0xffff;
            c.a[3] = a3;
            below(c, P4);
        });
        add(m, 0x1505a, c -> below(c, P4));
        add(m, 0x16628, c -> below(c, P3));
        add(m, 0x1507c, c -> {
            GameMemory g = c.mem;
            c.a[0] = g.read32(P0);
            c.a[1] = 0x150a0;
            g.write32(c.a[0] + 0x10, c.a[1]);
            c.a[0] = 0x152a6;
            c.a[1] = g.read32(P4);
            g.write32(c.a[1] + 0xc, c.a[0]);
            c.a[0] = 0x152b2;
            g.write32(c.a[1] + 0x10, c.a[0]);
            resetSequencer(c);
        });
        for (int a : new int[]{0x15146, 0x15872, 0x15f88}) {
            add(m, a, c -> brainBeaten(c, 0x64));
        }
        for (int a : new int[]{0x161aa, 0x1635a}) {
            add(m, a, c -> brainBeaten(c, 0x46));
        }
        add(m, 0x16618, c -> brainBeaten(c, 0x44));
        add(m, 0x1529c, c -> {
            c.a[0] = PHASE;
            c.mem.write8(PHASE, c.mem.read8(c.a[5] + 0x64));
        });
        add(m, 0x15440, c -> { // y speed -(4 - n), n counting up
            GameMemory g = c.mem;
            c.d[0] = sb(c.d[0], g.read8(c.a[5] + 0x44));
            g.write8(c.a[5] + 0x44, g.read8(c.a[5] + 0x44) + 1);
            c.d[1] = 4;
            c.d[1] = sb(c.d[1], c.d[1] - c.d[0]);
            c.d[1] = sw(c.d[1], -c.d[1]);
            c.d[1] = swap(c.d[1]);
            g.write32(c.a[5] + 0x2c, c.d[1]);
        });
        add(m, 0x154a2, c -> {
            GameMemory g = c.mem;
            c.a[0] = g.read32(P4);
            c.d[0] = sw(c.d[0], g.read16(c.a[0] + 2) + 6);
            g.write16(c.a[5] + 2, c.d[0]);
        });
        add(m, 0x154d0, c -> {
            GameMemory g = c.mem;
            c.a[2] = g.read32(P0);
            c.a[0] = g.read32(P3);
            c.d[1] = g.read32(c.a[0] + 2);
            c.a[0] = P0;
            c.d[0] = 2;
            arrange(c);
        });
        add(m, 0x154e4, c -> {
            GameMemory g = c.mem;
            c.a[2] = g.read32(P0);
            c.a[0] = g.read32(P1);
            c.d[1] = g.read32(c.a[0] + 2);
            c.d[2] = c.d[1] - g.read32(c.a[2] + 2);
            g.write32(c.a[2] + 0x28, c.d[2]);
            g.write32(c.a[2] + 2, c.d[1]);
            c.a[0] = P2;
            c.d[0] = 2;
            arrange(c);
        });
        add(m, 0x15506, c -> {
            GameMemory g = c.mem;
            c.a[2] = g.read32(P0);
            c.d[1] = g.read32(c.a[2] + 2);
            c.a[0] = P1;
            c.d[0] = 3;
            arrange(c);
        });
        add(m, 0x155ee, Stage5Boss::hitSparks);
        add(m, 0x1577e, c -> {
            setScript(c, P0, 0x10, 0x157e6);
            setScript(c, P4, 0x10, 0x158ca);
            c.mem.write16(c.a[0] + 0x4e, 0);
            c.mem.write16(c.a[0] + 0x4c, 0);
            resetSequencer(c);
        });
        add(m, 0x157c0, c -> {
            GameMemory g = c.mem;
            g.write8(c.a[5] + 0x46, 0);
            c.a[0] = g.read32(P1);
            boolean low = (short) g.read16(c.a[0] + 0x86) < -0x16;
            boolean neg = (g.read8(c.a[0] + 0x30) & 0x80) != 0;
            if (neg == low) {
                g.write8(c.a[5] + 0x46, 0xff);
            }
        });
        add(m, 0x15882, c -> {
            c.d[0] = c.mem.read32(c.a[5] + 0x28) >> 1;
            c.mem.write32(c.a[5] + 0x28, c.d[0]);
        });
        add(m, 0x1588e, c -> { // x speed towards Bubba's x (kept within $1B0..$2B0)
            GameMemory g = c.mem;
            c.d[0] = swap(g.read32(0x10912));
            short x = (short) c.d[0];
            if (x <= 0x1b0) {
                c.d[0] = sw(c.d[0], 0x1b0);
            } else if (x >= 0x2b0) {
                c.d[0] = sw(c.d[0], 0x2b0);
            }
            c.d[0] = swap(c.d[0]);
            c.d[0] -= g.read32(c.a[5] + 2);
            c.d[0] -= 0x160000;
            c.d[1] = 0xc;
            c.d[0] = (short) (c.d[0] >> 12);
            c.d[0] = divs(c.d[0], 0x18);
            c.d[0] = (short) c.d[0];
            c.d[0] <<= 12;
            g.write32(c.a[5] + 0x28, c.d[0]);
        });
        add(m, 0x1592c, c -> {
            GameMemory g = c.mem;
            c.d[0] = g.read32(c.a[5] + 0x44) >> 1;
            g.write32(c.a[5] + 0x44, c.d[0]);
            if (c.d[0] <= -0x10000) {
                g.write32(c.a[5] + 0x2c, c.d[0]);
                c.a[4] = 0x15902;
            }
        });
        add(m, 0x15948, c -> c.mem.write32(c.a[5] + 0x44, c.mem.read32(c.a[5] + 0x2c)));
        add(m, 0x15950, c -> {
            setScript(c, P0, 0x10, 0x15974);
            setScript(c, P4, 0x10, 0x15a58);
            c.mem.write16(c.a[0] + 0x4e, 0);
            c.mem.write16(c.a[0] + 0x4c, 0);
            resetSequencer(c);
        });
        add(m, 0x159ec, c -> { // the next phase: 3 when beaten, 2 when within one of the x ranges
            GameMemory g = c.mem;
            c.a[0] = g.read32(BRAIN);
            if ((byte) g.read8(c.a[0] + 0x9d) <= 0x6c) {
                c.a[0] = PHASE;
                g.write8(PHASE, 3);
                return;
            }
            c.a[0] = 0x15a28;
            c.d[0] = 2;
            c.d[1] = sw(c.d[1], g.read16(c.a[5] + 2));
            while (true) {
                c.d[2] = g.read32(c.a[0]);
                c.a[0] += 4;
                if ((short) c.d[2] >= (short) c.d[1]) {
                    c.d[2] = swap(c.d[2]);
                    if ((short) c.d[2] <= (short) c.d[1]) {
                        c.a[0] = PHASE;
                        g.write8(PHASE, 2);
                        return;
                    }
                }
                c.d[0] = sw(c.d[0], c.d[0] - 1);
                if ((c.d[0] & 0xffff) == 0xffff) {
                    return;
                }
            }
        });
        add(m, 0x15ad0, c -> {
            setScript(c, P0, 0x10, 0x15ae0);
            resetSequencer(c);
        });
        add(m, 0x15b0c, c -> {
            c.d[0] = c.mem.read32(c.a[5] + 0x28) >> 2;
            c.mem.write32(c.a[5] + 0x28, c.mem.read32(c.a[5] + 0x28) - c.d[0]);
        });
        add(m, 0x15b18, c -> {
            setScript(c, P3, 0x10, 0x15b34);
            setScript(c, P4, 0x10, 0x15b44);
            resetSequencer(c);
        });
        add(m, 0x15be2, c -> {
            setScript(c, P4, 0x10, 0x15bf2);
            resetSequencer(c);
        });
        add(m, 0x15c74, c -> { // parts 0-2 move with part 4 (y)
            GameMemory g = c.mem;
            c.a[0] = g.read32(P4);
            c.d[0] = g.read32(c.a[0] + 0x2c);
            c.a[0] = P0;
            for (int i = 0; i < 3; i++) {
                c.a[1] = g.read32(c.a[0]);
                c.a[0] += 4;
                g.write32(c.a[1] + 0x2c, c.d[0]);
                g.write32(c.a[1] + 6, g.read32(c.a[1] + 6) + c.d[0]);
            }
            c.d[1] = 0xffff;
        });
        add(m, 0x15c92, c -> {
            c.mem.write16(c.a[5] + 0x46, 0xffff);
            setScript(c, P0, 0x10, 0x15cb4);
            setScript(c, P3, 0x10, 0x16370);
            resetSequencer(c);
        });
        add(m, 0x15f98, c -> {
            GameMemory g = c.mem;
            c.a[0] = g.read32(P1);
            c.d[0] = sb(c.d[0], g.read8(c.a[0] + 0x30));
            c.a[0] = g.read32(P2);
            g.write8(c.a[5] + 0x64, (c.d[0] & 0xff) != g.read8(c.a[0] + 0x30) ? 0xff : 0);
        });
        add(m, 0x15fae, c -> {
            c.a[0] = c.mem.read32(BRAIN);
            c.mem.write8(c.a[0] + 0x9d, 0x78);
        });
        add(m, 0x15fba, c -> {
            setScript(c, P4, 0xc, 0x15fcc);
            c.mem.write32(c.a[0] + 0x32, 0);
        });
        add(m, 0x1576c, c -> { // part 4 follows part 0's y speed
            GameMemory g = c.mem;
            c.a[0] = g.read32(P0);
            c.d[0] = g.read32(c.a[0] + 0x2c);
            c.a[0] = g.read32(P4);
            g.write32(c.a[0] + 6, g.read32(c.a[0] + 6) + c.d[0]);
        });
        add(m, 0x15250, c -> {
            c.mem.write32(c.a[5] + 6, 0x14b0000);
            c.mem.write32(c.a[5] + 0x24, 0x14b0000);
        });
        add(m, 0x13e2a, c -> {
            c.d[0] = c.mem.read32(c.a[5] + 0x44);
            if (c.d[0] != 0) {
                c.a[0] = c.d[0];
                c.mem.write8(c.a[0] + 0x63, 0);
            }
        });
        add(m, 0x14c36, c -> { // on part 1 (left or right of it), or the script waits
            GameMemory g = c.mem;
            c.a[0] = g.read32(P1);
            boolean neg = (g.read8(c.a[0] + 0x30) & 0x80) != 0;
            c.d[0] = sb(c.d[0], g.read8(0x10ad4));
            if ((c.d[0] & 0x80) != 0) {
                if (neg) {
                    atPart1(c, sw(c.d[0], 0xff0b));
                } else {
                    atPart1(c, 0x22);
                }
                return;
            }
            c.a[4] = neg ? 0x14c2c : 0x14c22;
        });
        add(m, 0x16202, c -> {
            rngB(c);
            c.d[0] = sw(c.d[0], c.d[0] & 3);
            c.mem.write16(c.a[5] + 0x46, c.d[0]);
        });
        add(m, 0x16e2c, Stage5Boss::carrier);
        add(m, 0x16f0e, c -> { // object $1C at Bubba's x
            GameMemory g = c.mem;
            c.a[7] -= 2;
            g.write16(c.a[7], g.read16(c.a[5] + 2));
            c.a[7] -= 12;
            g.write32(c.a[7], c.a[3]);
            g.write32(c.a[7] + 4, c.a[4]);
            g.write32(c.a[7] + 8, c.a[5]);
            g.write16(c.a[5] + 2, g.read16(0x10912));
            c.d[0] = 0x1c;
            Calls.call(c, 0x107b2);
            c.a[3] = g.read32(c.a[7]);
            c.a[4] = g.read32(c.a[7] + 4);
            c.a[5] = g.read32(c.a[7] + 8);
            c.a[7] += 12;
            g.write16(c.a[5] + 2, g.read16(c.a[7]));
            c.a[7] += 2;
        });
        add(m, 0x16f2c, c -> { // speed from the distance to Bubba (table $16F54)
            GameMemory g = c.mem;
            c.d[0] = sw(c.d[0], g.read16(c.a[5] + 2));
            c.d[0] = sw(c.d[0], c.d[0] - g.read16(0x10912));
            if ((short) c.d[0] < 0) {
                c.a[4] = 0x16ee4;
                g.write16(c.a[5] + 0x32, 0);
                g.write32(c.a[5] + 0x44, 0xffff);
                return;
            }
            c.a[0] = 0x16f54;
            for (int guard = 0; guard < 0x8000; guard++) {
                boolean ge = (short) c.d[0] >= (short) g.read16(c.a[0]);
                c.a[0] += 4;
                if (!ge) {
                    break;
                }
            }
            c.a[0] -= 2;
            c.d[0] = sw(c.d[0], g.read16(c.a[0]));
            c.d[0] = ((c.d[0] * 2) & 0xffff) << 16;
            g.write32(c.a[5] + 0x90, c.d[0]);
            c.d[0] = -c.d[0];
            g.write32(c.a[5] + 0x8c, c.d[0]);
        });
        add(m, 0x17020, c -> {
            c.a[0] = 0x10adc;
            c.mem.write16(0x10adc, c.mem.read16(0x10adc) + 1);
        });
        add(m, 0x1709e, c -> { // next position / frame of the table $170C6, until the end
            GameMemory g = c.mem;
            g.write16(c.a[5] + 6, 0x10);
            c.a[0] = 0x10ada;
            c.d[0] = sw(c.d[0], g.read16(0x10ada));
            if ((c.d[0] & 0xffff) == 0x20) {
                c.a[4] = 0x170e6;
                return;
            }
            g.write16(0x10ada, g.read16(0x10ada) + 4);
            g.write16(c.a[5] + 2, g.read16(0x170c6 + (short) c.d[0]));
            g.write16(c.a[5] + 0xa, g.read16(0x170c8 + (short) c.d[0]));
        });
        add(m, 0x173f8, c -> {
            GameMemory g = c.mem;
            c.d[0] = sb(c.d[0], g.read8(0x10ad5));
            byte v = (byte) c.d[0];
            if (v == 0) {
                return;
            }
            if (v < 0) {
                c.d[0] = sw(c.d[0], g.read16(0x10ade));
                if ((c.d[0] & 0xffff) != g.read16(c.a[5] + 0x44)) {
                    return;
                }
            }
            c.a[4] = 0x173b6;
        });
        add(m, 0x17490, c -> {
            GameMemory g = c.mem;
            c.a[0] = 0x10ade;
            c.d[0] = sw(c.d[0], g.read16(0x10ade));
            if ((short) c.d[0] >= (short) g.read16(0x108c0)) {
                c.a[4] = 0x173f6;
                return;
            }
            g.write16(0x10ade, g.read16(0x10ade) + 1);
            g.write16(c.a[5] + 0x44, g.read16(0x10ade));
            c.d[0] = (c.d[0] & 0xffff) * 0x16;
            c.d[0] = sw(c.d[0], c.d[0] + 0xb4);
            g.write16(c.a[5] + 2, c.d[0]);
            g.write16(c.a[5] + 6, 0);
        });
        add(m, 0x15fec, c -> c.mem.write16(c.a[5] + 0x44, c.mem.read16(c.a[5] + 6)));
        add(m, 0x15ff4, c -> {
            c.d[0] = c.mem.read16(c.a[5] + 0x44) << 16;
            c.mem.write32(c.a[5] + 6, c.d[0]);
            c.mem.write32(c.a[5] + 0x24, c.d[0]);
        });
        add(m, 0x161de, c -> {
            rngB(c);
            c.d[0] = sw(c.d[0], c.d[0] & 1);
            c.mem.write16(c.a[5] + 0x46, c.d[0]);
        });
        add(m, 0x162a0, c -> {
            c.a[0] = 0x15644;
            c.mem.write32(c.a[5] + 0xc, c.a[0]);
            setScript(c, P2, 0x10, 0x162b8);
            resetSequencer(c);
        });
        add(m, 0x16460, c -> setScript(c, P4, 0xc, 0x1646e));
        add(m, 0x1648a, c -> {
            c.a[0] = 0x154bc;
            c.mem.write32(c.a[5] + 0xc, c.a[0]);
            setScript(c, P1, 0x10, 0x164a8);
            c.mem.write8(c.a[5] + 0x9d, 0x78);
            resetSequencer(c);
        });
        add(m, 0x166d2, c -> {
            c.mem.write32(c.a[5] + 2, 0x21a0000);
            c.mem.write32(c.a[5] + 0x20, 0x21a0000);
        });
        add(m, 0x166e2, c -> {
            c.a[0] = 0x154c6;
            c.mem.write32(c.a[5] + 0xc, c.a[0]);
            setScript(c, P0, 0x10, 0x16706);
            setScript(c, P3, 0x10, 0x16796);
            resetSequencer(c);
        });
        add(m, 0x16786, c -> {
            c.mem.write32(c.a[5] + 6, 0x1770000);
            c.mem.write32(c.a[5] + 0x24, 0x1770000);
        });
        add(m, 0x167f6, c -> {
            c.mem.write16(c.a[5] + 0x46, 0);
            setScript(c, P0, 0x10, 0x16816);
            setScript(c, P3, 0x10, 0x16a22);
            resetSequencer(c);
        });
        add(m, 0x16a9c, c -> {
            GameMemory g = c.mem;
            c.d[1] = g.read32(c.a[5] + 2);
            c.d[0] = 0x2190000;
            c.d[1] -= c.d[0];
            g.write32(c.a[5] + 0x28, c.d[1]);
            g.write32(c.a[5] + 2, c.d[0]);
            g.write32(c.a[5] + 0x20, c.d[0]);
        });
        add(m, 0x16ab8, c -> {
            c.a[0] = c.mem.read32(P1);
            c.mem.write8(c.a[5] + 0x30, c.mem.read8(c.a[0] + 0x30));
        });
        add(m, 0x16ad0, c -> {
            c.mem.write16(0x10ade, c.mem.read16(c.a[5] + 2));
            c.mem.write16(0x10ae0, c.mem.read16(c.a[5] + 6));
            c.a[0] = 0x10ae2;
        });
        add(m, 0x16c34, c -> {
            c.mem.write16(c.a[5] + 2, c.mem.read16(0x10ade));
            c.mem.write16(c.a[5] + 6, c.mem.read16(0x10ae0));
        });
        add(m, 0x16c42, c -> { // every part stops
            GameMemory g = c.mem;
            g.write32(c.a[5] + 0xc, 0);
            c.a[4] = 0x16c70;
            c.a[0] = P0;
            c.a[1] = 0x16c64;
            for (int i = 0; i < 5; i++) {
                c.a[2] = g.read32(c.a[0]);
                c.a[0] += 4;
                g.write32(c.a[2] + 0xc, c.a[1]);
                g.write32(c.a[2] + 0x10, 0);
            }
            c.d[0] = 0xffff;
        });
    }

    /** The random generator of $12414 / $14ABA / $14B44 (address register {@code reg} = $10894). */
    private static void rngA(M68k c, int reg) {
        c.d[0] = random(c.mem);
        c.d[1] = c.mem.read32(RND);
        c.a[reg] = RND;
    }

    /** The random generator of $148E2 / $161DE (a0 = $10894, movem.w: sign-extended). */
    private static void rngB(M68k c) {
        GameMemory g = c.mem;
        c.a[0] = RND;
        int d0 = (short) g.read16(RND), d1 = (short) g.read16(RND + 2), d2 = (short) g.read16(RND + 4),
                d3 = (short) g.read16(RND + 6);
        d1 = sw(d1, d1 ^ d0);
        d2 = sw(d2, d2 ^ d1);
        d3 = sw(d3, d3 ^ d2);
        int w = d3 & 0xffff;
        d3 = sw(d3, (w >>> 1) | (w << 15));
        d0 = sw(d0, d0 ^ d3);
        d2 = sw(d2, d2 + d0);
        d1 = sw(d1, d1 - d3);
        g.write16(RND, d0);
        g.write16(RND + 2, d1);
        g.write16(RND + 4, d2);
        g.write16(RND + 6, d3);
        c.d[0] = d0;
        c.d[1] = d1;
        c.d[2] = d2;
        c.d[3] = d3;
    }

    /** $148E2: one time in 5, a new object $9A-$9D (in turn), the script waiting at $14930. */
    private static void spawnRandom(M68k c) {
        GameMemory g = c.mem;
        rngB(c);
        if ((c.d[0] & 0xffff) >= 0x3333) {
            return;
        }
        c.d[1] = sw(c.d[1], g.read16(c.a[5] + 0x46));
        c.d[0] = sw(c.d[0], 0x9a);
        c.d[0] = sw(c.d[0], c.d[0] + c.d[1]);
        c.d[1] = sw(c.d[1], c.d[1] + 1);
        if ((short) c.d[1] >= 4) {
            c.d[1] = 0;
        }
        g.write16(c.a[5] + 0x46, c.d[1]);
        push(c, c.a[4]);
        push(c, c.a[5]);
        Calls.call(c, 0x107b2);
        c.a[5] = pop(c);
        c.a[4] = pop(c);
        g.write32(c.a[5] + 0x36, c.a[4]);
        c.a[4] = 0x14930;
    }

    /** $12508 / $12522: (a0) +$38/+$3C = part 1's position + (d0, -18). */
    private static void nearPart1(M68k c, int d0) {
        GameMemory g = c.mem;
        c.d[0] = d0;
        c.a[1] = g.read32(P1);
        c.d[0] = sw(c.d[0], c.d[0] + g.read16(c.a[1] + 2));
        g.write16(c.a[0] + 0x38, c.d[0]);
        c.d[0] = 0xffffffee;
        c.d[0] = sw(c.d[0], c.d[0] + g.read16(c.a[1] + 6));
        g.write16(c.a[0] + 0x3c, c.d[0]);
    }

    /** $124D8: unless $10AD4 is negative, (a0) +$40 = 0 and its script $16DB4, at rest. */
    private static void releaseUnlessFlag(M68k c) {
        GameMemory g = c.mem;
        c.d[0] = sb(c.d[0], g.read8(0x10ad4));
        if ((c.d[0] & 0x80) != 0) {
            return;
        }
        g.write16(c.a[0] + 0x40, 0);
        c.a[1] = 0x16db4;
        g.write32(c.a[0] + 0x14, c.a[1]);
        g.write32(c.a[0] + 2, 0);
    }

    /** $14BDA / $14BE0: the object at part 1's position + (d0, -18). */
    private static void atPart1(M68k c, int d0) {
        GameMemory g = c.mem;
        c.d[0] = d0;
        c.a[0] = g.read32(P1);
        c.d[0] = sw(c.d[0], c.d[0] + g.read16(c.a[0] + 2));
        g.write16(c.a[5] + 2, c.d[0]);
        c.d[0] = 0xffffffee;
        c.d[0] = sw(c.d[0], c.d[0] + g.read16(c.a[0] + 6));
        g.write16(c.a[5] + 6, c.d[0]);
    }

    /** $14B44 / $14B82: random speed, x $50000-$8C000 (to the left when d2 < 0), y up. */
    private static void randomSpeed(M68k c, int d2) {
        c.d[2] = d2;
        rngA(c, 0);
        c.d[0] = (c.d[0] & 0x3c000) + 0x50000;
        if ((short) c.d[2] < 0) {
            c.d[0] = -c.d[0];
        }
        c.d[1] = (c.d[1] & 0x1f000) + 0x40000;
        c.d[1] = -c.d[1];
        c.mem.write32(c.a[5] + 0x28, c.d[0]);
        c.mem.write32(c.a[5] + 0x2c, c.d[1]);
    }

    /** $1505A / $16628: the part at ({@code ptr}) 32 below part 0 (y, with its speed). */
    private static void below(M68k c, int ptr) {
        GameMemory g = c.mem;
        c.a[0] = g.read32(P0);
        c.d[0] = g.read32(c.a[0] + 6) + 0x200000;
        c.a[0] = g.read32(ptr);
        c.d[1] = g.read32(c.a[0] + 6);
        g.write32(c.a[0] + 6, c.d[0]);
        c.d[0] -= c.d[1];
        g.write32(c.a[0] + 0x2c, c.d[0]);
    }

    /**
     * $16E2C: an object carrying Bubba swings around ($6C, $A2): on each axis the speed grows by
     * $8000 towards the centre, halved when the side changes; it stops (+$32 / +$33) once slow.
     * Bubba moves with it.
     */
    private static void carrier(M68k c) {
        GameMemory g = c.mem;
        swing(c, 0x32, 2, 0x6c, 0x44, 0x28);
        swing(c, 0x33, 6, 0xa2, 0x46, 0x2c);
        int o = c.a[5];
        c.d[0] = g.read32(o + 0x28);
        c.d[1] = g.read32(o + 0x2c);
        g.write32(o + 2, g.read32(o + 2) + c.d[0]);
        g.write32(o + 6, g.read32(o + 6) + c.d[1]);
        g.write32(0x10912, g.read32(0x10912) + c.d[0]);
        c.a[0] = 0x10916;
        c.d[2] = sw(c.d[2], g.read16(o + 6));
        c.d[2] = sw(c.d[2], c.d[2] - g.read16(0x10916));
        g.write16(0x1090e, c.d[2]);
        g.write8(0x108c2, 0xff);
        if (g.read16(o + 0x32) == 0xffff) {
            c.a[4] = 0x16ef2;
        }
    }

    private static void swing(M68k c, int stop, int pos, int centre, int side, int speed) {
        GameMemory g = c.mem;
        int o = c.a[5];
        if (g.read8(o + stop) != 0) {
            g.write32(o + speed, 0);
            return;
        }
        c.d[1] = 0x8000;
        c.d[0] = sw(c.d[0], g.read16(o + pos));
        c.d[0] = sw(c.d[0], c.d[0] - centre);
        if ((short) c.d[0] >= 0) {
            c.d[1] = -c.d[1];
        }
        c.d[2] = sw(c.d[2], g.read16(o + side));
        c.d[2] = sw(c.d[2], c.d[2] ^ c.d[0]);
        if ((short) c.d[2] < 0) {
            g.write32(o + speed, g.read32(o + speed) >> 1); // asr.w / roxr.w
            c.d[2] = sw(c.d[2], g.read16(o + speed));
            if ((short) c.d[2] < 0) {
                c.d[2] = sw(c.d[2], -c.d[2]);
            }
            boolean le = (short) c.d[2] <= 1; // subq.w #1 / sle (with overflow)
            c.d[2] = sw(c.d[2], c.d[2] - 1);
            g.write8(o + stop, le ? 0xff : 0);
        }
        g.write16(o + side, c.d[0]);
        g.write32(o + speed, g.read32(o + speed) + c.d[1]);
    }

    /** $15146...: {@code field} = $FF when the brain's energy is $6C or below. */
    private static void brainBeaten(M68k c, int field) {
        c.a[0] = c.mem.read32(BRAIN);
        c.mem.write8(c.a[5] + field, (byte) c.mem.read8(c.a[0] + 0x9d) <= 0x6c ? 0xff : 0);
    }

    /** ((ptr)) + off = script (a0 = the part, a1 = the script). */
    private static void setScript(M68k c, int ptr, int off, int script) {
        c.a[0] = c.mem.read32(ptr);
        c.a[1] = script;
        c.mem.write32(c.a[0] + off, c.a[1]);
    }

    /** 0x14E9C. */
    private static void resetSequencer(M68k c) {
        c.a[0] = 0x10ada;
        c.mem.write16(0x10ada, 0);
    }

    private static int divs(int dividend, int divisor) {
        int q = dividend / (short) divisor, r = dividend % (short) divisor;
        return (r << 16) | (q & 0xffff);
    }

    /** $15514...: the parts arranged around part 0 (x = d1 from a0's list, then y), then $155CE. */
    private static void arrange(M68k c) {
        GameMemory g = c.mem;
        do {
            c.a[1] = g.read32(c.a[0]);
            c.a[0] += 4;
            c.d[2] = c.d[1] - g.read32(c.a[1] + 2);
            g.write32(c.a[1] + 0x28, c.d[2]);
            g.write32(c.a[1] + 2, c.d[1]);
            c.d[0] = sw(c.d[0], c.d[0] - 1);
        } while ((c.d[0] & 0xffff) != 0xffff);
        c.d[0] = g.read32(c.a[2] + 6) + 0x1e0000;
        c.a[0] = g.read32(P1);
        c.d[1] = c.d[0] - g.read32(c.a[0] + 6);
        g.write32(c.a[0] + 0x2c, c.d[1]);
        g.write32(c.a[0] + 6, c.d[0]);
        c.d[4] = 0x540000;
        c.d[3] = 3;
        short mode = (short) g.read16(c.a[5] + 0x46);
        boolean far;
        if (mode == 0) {
            c.d[4] = 0x7f0000;
            far = true;
        } else {
            c.a[2] = g.read32(P4);
            far = mode < 0;
            if (far) {
                c.d[4] = 0x380000;
            }
        }
        if (far) {
            c.a[2] = g.read32(P3);
            c.d[3] = 2;
        }
        c.d[1] = g.read32(c.a[2] + 6);
        c.a[1] = g.read32(P1);
        c.d[0] = g.read32(c.a[1] + 6);
        c.d[1] -= c.d[0];
        c.d[2] = c.d[1] - c.d[4];
        if (c.d[1] > c.d[4]) { // sub.l / ble: the signed comparison (with overflow)
            c.d[1] -= c.d[2];
            g.write32(c.a[2] + 6, g.read32(c.a[2] + 6) - c.d[2]);
        }
        c.d[1] = (short) (c.d[1] >> 8);
        c.d[1] = divs(c.d[1], c.d[3]);
        c.d[1] = (short) c.d[1];
        c.d[1] <<= 8;
        c.a[0] = g.read32(P2);
        c.d[0] += c.d[1];
        c.d[2] = c.d[0] - g.read32(c.a[0] + 6);
        g.write32(c.a[0] + 0x2c, c.d[2]);
        g.write32(c.a[0] + 6, c.d[0]);
        c.a[0] = g.read32(P3);
        if ((short) g.read16(c.a[5] + 0x46) < 0) {
            c.d[0] = 0;
            c.d[1] = g.read32(c.a[0] + 6) + 0x180000;
            c.a[0] = g.read32(P4);
        }
        c.d[0] += c.d[1];
        c.d[2] = c.d[0] - g.read32(c.a[0] + 6);
        g.write32(c.a[0] + 0x2c, c.d[2]);
        g.write32(c.a[0] + 6, c.d[0]);
        c.d[0] = sw(c.d[0], g.read16(c.a[2] + 6));
        c.a[0] = g.read32(P0);
        g.write16(c.a[5] + 2, g.read16(c.a[0] + 2));
        g.write16(c.a[5] + 6, g.read16(c.a[0] + 6));
        c.d[0] = sw(c.d[0], c.d[0] - g.read16(c.a[0] + 6));
        c.d[0] = sw(c.d[0], c.d[0] - g.read16(c.a[5] + 0x16));
        g.write16(c.a[5] + 0x1a, c.d[0]);
        hitSparks(c);
    }

    /** $155EE: in state 7, the script waits at $1563C; sparks $AF and $B0 at the hit point $12D5E. */
    private static void hitSparks(M68k c) {
        GameMemory g = c.mem;
        if (g.read16(c.a[5] + 0x3a) != 7) {
            return;
        }
        g.write32(c.a[5] + 0x36, c.a[4]);
        c.a[4] = 0x1563c;
        c.d[0] = g.read32(0x12d5e);
        if (c.d[0] == 0) {
            return;
        }
        push(c, c.a[4]);
        push(c, c.a[5]);
        c.a[7] -= 2;
        g.write16(c.a[7], g.read16(c.a[5] + 2));
        c.a[7] -= 2;
        g.write16(c.a[7], g.read16(c.a[5] + 6));
        g.write16(c.a[5] + 2, g.read16(0x12d5e));
        g.write16(c.a[5] + 6, g.read16(0x12d60));
        c.a[0] = 0x12d5e;
        g.write32(0x12d5e, 0);
        c.d[0] = sw(c.d[0], 0xaf);
        Calls.call(c, 0xfb58);
        c.d[0] = sw(c.d[0], 0xb0);
        Calls.call(c, 0xfb58);
        g.write16(c.a[5] + 6, g.read16(c.a[7]));
        c.a[7] += 2;
        g.write16(c.a[5] + 2, g.read16(c.a[7]));
        c.a[7] += 2;
        c.a[5] = pop(c);
        c.a[4] = pop(c);
    }
}
