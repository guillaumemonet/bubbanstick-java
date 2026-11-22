package bubba.entity;

import bubba.cpu.GameMemory;
import bubba.level.LevelData;

/**
 * View on an object structure of the emulated game memory (original pool at $17ED8, $BA bytes
 * each). The scripts and the native 68k routines read and write the very same fields:
 * <pre>
 *  +$00 state (0 = free, byte +1 = 1 small / 2 big object)   +$02.l x (16.16)   +$06.l y (16.16)
 *  +$0A sprite   +$0C.l anim script   +$10.l behaviour script   +$14..$1B hitbox dx,dy,w,h
 *  +$1C removal margin   +$1E draw layer   +$1F behaviour engine   +$28.l/$2C.l velocity
 *  +$32/$34 anim counters   +$36.l anim return   +$3A timer   +$4C/$4E behaviour counters
 *  +$50.l behaviour return   +$54.l mtrg record   +$58.l class definition   +$5C type index
 *  +$63 never removed off screen   +$64 landed   +$86/$88 position relative to Bubba
 *  +$8A random   +$8B counter   +$8C..$98 velocity limits   +$9E/$9F wall/floor hit   +$A0 sprite base
 * </pre>
 * Per-frame order is the original one (code 0xDEEA): timer, behaviour, relative position,
 * counter, animation.
 */
public final class LevelObject {

    public static final int STRUCT_SIZE = 0xba;

    private final GameMemory ram;
    public final int base;

    public LevelObject(GameMemory ram, int base) {
        this.ram = ram;
        this.base = base;
    }

    /** Type number as in the mtrg records (index + 1). */
    public int typeId() {
        return word(0x5c) + 1;
    }

    public void update(LevelData level, NativeCalls natives, int playerX, int playerY) {
        if (word(0x3a) != 0) {
            setWord(0x3a, word(0x3a) - 1);
        }
        if (getLong(0x10) != 0) {
            BehaviourVM.step(this, ram, level, natives, getByte(0x1f));
        }
        setWord(0x86, x() - playerX);
        setWord(0x88, y() - playerY);
        setByte(0x8b, getByte(0x8b) + 2);
        if (getLong(0x0c) != 0) {
            AnimVM.step(this, ram, natives);
        }
    }

    public int x() {
        return (short) word(0x02);
    }

    public int y() {
        return (short) word(0x06);
    }

    /** Sprite to draw (frame + base, like the renderer at 0x8484), or >= $FFFE for nothing. */
    public int sprite() {
        int s = word(0x0a);
        return s >= 0xfffe ? s : (s + word(0xa0)) & 0xffff;
    }

    public int getByte(int off) {
        return ram.read8(base + off);
    }

    public void setByte(int off, int v) {
        ram.write8(base + off, v);
    }

    public int word(int off) {
        return ram.read16(base + off);
    }

    public void setWord(int off, int v) {
        ram.write16(base + off, v);
    }

    public int getLong(int off) {
        return ram.read32(base + off);
    }

    public void setLong(int off, int v) {
        ram.write32(base + off, v);
    }
}
