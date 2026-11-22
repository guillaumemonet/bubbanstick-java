package bubba.entity.natives;

import bubba.entity.LevelObject;

/**
 * Java port of a native 68k routine called by an object script (opcode $0C).
 * Receives the object (a5) and the script pointer after the operand (a4); returns the new a4.
 */
@FunctionalInterface
public interface NativeRoutine {

    int run(NativeContext ctx, LevelObject o, int a4);
}
