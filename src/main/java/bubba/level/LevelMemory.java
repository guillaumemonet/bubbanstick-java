package bubba.level;

import bubba.cpu.GameMemory;

/**
 * Builds the level structures in the emulated game memory exactly like the level start code of
 * the original, so that the native routines and the game's collision routines (which read them)
 * run unchanged on the 68000 interpreter:
 * <ul>
 *   <li>level variables (code 0x65BA): width $10A4A, height $10A4C, cells $10A4E, row bytes $10A48,
 *       water level $10A50/$10A54/$10A5E;</li>
 *   <li>block table at (L_10890) and per-block offsets at $10B20 (code 0x6A62): for each block
 *       the attribute word, the graphics pointers and, for slopes, 16 bytes "15 - height";</li>
 *   <li>graphics pointer overrides from info+[$E2] (code 0x6B0C);</li>
 *   <li>the map at (L_10888), each cell replaced by the offset of its block entry (code 0x6B48).</li>
 * </ul>
 */
public final class LevelMemory {

    /** Buffers placed in spare RAM (the original allocates them at boot). */
    public static final int BLOCK_GFX = 0x80000;   // L_1088c: 0x80 bytes per block
    public static final int BLOCK_TABLE = 0x9a000; // L_10890
    public static final int MAP = 0xa6000;         // L_10888

    private LevelMemory() {
    }

    public static void build(GameMemory ram, byte[] info, int[] rawMap) {
        int width = u16(info, 0), height = u16(info, 2);
        // level variables (0x65BA)
        ram.write16(0x10a54, u16(info, 0x4a));
        ram.write16(0x10a5e, u16(info, 0x4a));
        ram.write32(0x10a50, u16(info, 0x4a) << 16);
        ram.write16(0x10a58, 0);
        ram.write16(0x10a5c, 0);
        ram.write32(0x10a4a, (width << 16) | height);
        ram.write16(0x10a4e, width * height);
        ram.write16(0x10a48, width * 2);
        ram.write16(0x10a46, height);
        // L_1113A = C_000D0 (code 0x6C18) and the protection key written at boot (code 0x38EE):
        // some object scripts check (L_1113A)+$4098 == $38891291 and sabotage themselves otherwise
        ram.write32(0x1113a, 0x60000 + 0xd0);
        ram.write32(0x60000 + 0x4168, 0x38891291);
        ram.write32(0xf4, 0x38891291);
        ram.write32(0x1088c, BLOCK_GFX);
        ram.write32(0x10890, BLOCK_TABLE);
        ram.write32(0x10888, MAP);

        // block table (0x6A62)
        int count = u16(info, 4);
        int attrs = 0xe6;
        int profiles = u16(info, 0xde);
        int pairs = u16(info, 0xe0);
        int pointersPerBlock = 0x10;
        int nextProfileBlock = u16(info, pairs);
        int pairPos = pairs + 2;
        if (nextProfileBlock == 0) {
            pointersPerBlock = u16(info, 0x0a) >> 2;
        }
        int a0 = BLOCK_TABLE;
        int gfx = BLOCK_GFX;
        for (int block = 0; block < count; block++) {
            ram.write16(0x10b20 + 2 * block, a0 - BLOCK_TABLE);
            ram.write16(a0, u16(info, attrs + 2 * block));
            a0 += 2;
            for (int k = 0; k < pointersPerBlock; k++) {
                ram.write32(a0, gfx);
                a0 += 4;
            }
            if (block == nextProfileBlock) {
                // "lsl.w #4,d3 / lea (a2,d3.w),a5": 16-bit offset, sign extended ($FFFF -> -16)
                int p = profiles + (short) (u16(info, pairPos) << 4);
                pairPos += 2;
                for (int k = 0; k < 16; k++) {
                    int b = p + k >= 0 && p + k < info.length ? info[p + k] & 0xff : 0;
                    ram.write8(a0++, 15 - b);
                }
                nextProfileBlock = u16(info, pairPos);
                pairPos += 2;
            }
            gfx += 0x80;
        }
        if (a0 - BLOCK_TABLE > 0xa000) {
            throw new IllegalStateException("block table too large: " + Integer.toHexString(a0 - BLOCK_TABLE));
        }

        // graphics pointer overrides (0x6AEE)
        int columns = u16(info, 0x0a) >> 2;
        ram.write16(0x10a60, u16(info, 0x0a));
        ram.write16(0x10a62, u16(info, 0x0c));
        ram.write16(0x10a64, 0);
        int p = u16(info, 0xe2);
        while (p + 2 <= info.length) {
            int b = (short) u16(info, p);
            p += 2;
            if (b < 0) {
                break;
            }
            int a3 = BLOCK_TABLE + ram.read16(0x10b20 + 2 * b) + 2;
            for (int k = 0; k < columns && p + 2 <= info.length; k++) {
                ram.write32(a3, BLOCK_GFX + (u16(info, p) << 7));
                a3 += 4;
                p += 2;
            }
        }

        // map converted to block entry offsets (0x6B48)
        int cells = width * height;
        for (int i = 0; i < cells && i < rawMap.length; i++) {
            ram.write16(MAP + 2 * i, ram.read16(0x10b20 + 2 * (rawMap[i] & 0x3ff)));
        }
        ram.write16(MAP + 2 * cells, 0);
    }

    private static int u16(byte[] b, int o) {
        return o + 1 < b.length ? ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff) : 0;
    }
}
