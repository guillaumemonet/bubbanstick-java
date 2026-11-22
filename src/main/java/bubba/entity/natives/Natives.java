package bubba.entity.natives;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry of the native routines ported to Java, per level (file id) and offset in set*.bin.
 * Routines not listed here run on the 68000 interpreter.
 */
public final class Natives {

    private static final Map<String, Map<Integer, NativeRoutine>> BY_LEVEL = new HashMap<>();
    /** Routines that read the map tables: can't be checked against the interpreter. */
    private static final Set<String> USES_MAP = Set.of();

    static {
        // file ids: stage 1 = "1", stage 2 = "5", stage 3 = "3", stage 4 = "4", stage 5 = "6"
        Map<Integer, NativeRoutine> s1 = new HashMap<>();
        Stage1Natives.register(s1);
        BY_LEVEL.put("1", s1);
        Map<Integer, NativeRoutine> s2 = new HashMap<>();
        Stage2Natives.register(s2);
        BY_LEVEL.put("5", s2);
        Map<Integer, NativeRoutine> s3 = new HashMap<>();
        Stage3Natives.register(s3);
        BY_LEVEL.put("3", s3);
        Map<Integer, NativeRoutine> s4 = new HashMap<>();
        Stage4Natives.register(s4);
        BY_LEVEL.put("4", s4);
        Map<Integer, NativeRoutine> s5 = new HashMap<>();
        Stage5Natives.register(s5);
        BY_LEVEL.put("6", s5);
    }

    private Natives() {
    }

    public static NativeRoutine find(String fileId, int setOffset) {
        Map<Integer, NativeRoutine> m = BY_LEVEL.get(fileId);
        return m == null ? null : m.get(setOffset);
    }

    /** Offsets of the routines ported for a level (used by the fuzz tester). */
    public static java.util.Set<Integer> ported(String fileId) {
        Map<Integer, NativeRoutine> m = BY_LEVEL.get(fileId);
        return m == null ? java.util.Set.of() : new java.util.TreeSet<>(m.keySet());
    }

    public static boolean verifiable(String fileId, int setOffset) {
        return !USES_MAP.contains(fileId + ":" + Integer.toHexString(setOffset));
    }
}
