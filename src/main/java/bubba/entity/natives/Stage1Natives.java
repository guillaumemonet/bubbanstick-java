package bubba.entity.natives;

import bubba.entity.LevelObject;

import java.util.Map;

import static bubba.entity.natives.NativeContext.SET;

/**
 * Native routines of stage 1 (set1.bin), ported from the 68k code. Addresses are offsets in
 * set1.bin (loaded at $108EE); comments give the original instructions' logic.
 */
final class Stage1Natives {

    private Stage1Natives() {
    }

    static void register(Map<Integer, NativeRoutine> r) {
        r.put(0x1910, Stage1Natives::walkerWatch);
        r.put(0x1940, Stage1Natives::walkerStep);
        r.put(0x2a62, (c, o, a4) -> markerSprite(o, a4, 0x90));
        r.put(0x2a78, (c, o, a4) -> markerSprite(o, a4, 0x8f));
        r.put(0x356e, Stage1Natives::random44);
    }

    /**
     * $1910 (type 16, idle): is Bubba close? (-124 < dy < 80, -160 < dx < 160) - if so, face him
     * (+$30 = dx < 0) and go to the walking script $18A4, else stay in the idle loop $1898.
     */
    static int walkerWatch(NativeContext c, LevelObject o, int a4) {
        int dy = (short) o.word(0x88), dx = (short) o.word(0x86);
        if (dy <= -124 || dy >= 80 || dx <= -160 || dx >= 160) {
            return SET + 0x1898;
        }
        o.setByte(0x30, dx < 0 ? 0xff : 0);
        return SET + 0x18a4;
    }

    /**
     * $1940 (type 16, walking): back to idle when Bubba is far, turn around on a wall hit, when
     * Bubba is close behind, at the edge of a platform or in front of a wall (map test on the
     * block attributes: $13 = ground, $11 = wall, 4 = special block below).
     */
    static int walkerStep(NativeContext c, LevelObject o, int a4) {
        if (o.getByte(0x64) == 0 || o.getByte(0x46) != 0) {
            return walkExit(o);
        }
        int dy = (short) o.word(0x88), dx = (short) o.word(0x86);
        if (dy <= -164 || dy >= 120 || dx <= -256 || dx >= 256) {
            o.setLong(0x28, 0);
            o.setLong(0x2c, 0);
            setAnim(o, SET + 0x183c);
            return SET + 0x1898; // Bubba far: back to the idle script
        }
        if (o.getByte(0x9e) != 0) { // hit a wall: turn around
            o.setByte(0x30, ~o.getByte(0x30));
            return walkExit(o);
        }
        int face = (byte) o.getByte(0x30);
        if (((face ^ dx) & 0x8000) != 0 && Math.abs(dx) <= 0x58 && dy < 40) {
            return turn(o); // Bubba close behind
        }
        // edge / wall test, only in the half of the block facing the walking direction
        int xl = o.x() & 15;
        if (face < 0 ? xl >= 8 : xl < 8) {
            return walkExit(o);
        }
        int bx = o.x() >> 4, by = (o.y() - 1) >> 4;
        if ((c.cellAttribute(bx, by + 1) & 4) != 0) {
            return (byte) o.getByte(0x30) < 0 ? SET + 0x18d4 : SET + 0x18f2;
        }
        int dir = face < 0 ? -1 : 1;
        if ((c.cellAttribute(bx + dir, by + 1) & 0x13) == 0) {
            return turn(o); // no ground ahead
        }
        for (int k = 0; k < 4; k++) {
            if ((c.cellAttribute(bx + k * dir, by) & 0x11) != 0) {
                return turn(o); // wall ahead
            }
        }
        return walkExit(o);
    }

    private static int turn(LevelObject o) {
        o.setByte(0x46, 0xff);
        setAnim(o, SET + 0x184c);
        return walkExit(o);
    }

    private static int walkExit(LevelObject o) {
        return (byte) o.getByte(0x30) < 0 ? SET + 0x18bc : SET + 0x18c8;
    }

    private static void setAnim(LevelObject o, int script) {
        o.setLong(0x0c, script);
        o.setWord(0x34, 0);
        o.setWord(0x32, 0);
    }

    /** $2A62 / $2A78 (types 65-76): sprite = ((type index - $40) * 2 + base) * 2. */
    static int markerSprite(LevelObject o, int a4, int base) {
        int d0 = (o.word(0x5c) - 0x40) & 0xffff;
        d0 = ((d0 * 2 + base) * 2) & 0xffff;
        o.setWord(0x0a, d0);
        return a4;
    }

    /** $356E (types 79, 83): +$44 = random word. */
    static int random44(NativeContext c, LevelObject o, int a4) {
        o.setWord(0x44, c.random());
        return a4;
    }
}
