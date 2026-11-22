package bubba.level;

/**
 * Definition of an object class (set*.bin, table at $111A6 in the original):
 * initial sprite (null = invisible), hitbox relative to the position, script offsets in set.bin,
 * and the parameter bytes 14..23 of the definition (copied into the object at spawn:
 * $1C-$1F, $5E, $82, $9D; byte $1F selects the behaviour engine).
 */
public record ObjectType(int id, Integer sprite, int hitX, int hitY, int hitW, int hitH,
                         int animScript, int behaviourScript, byte[] params) {
}
