package bubba.level;

/** One record of mtrg*.trg: object type, position (pixels), flags (see tools/FORMATS.md). */
public record ObjectPlacement(int type, int x, int y, int flags, boolean hasZone) {
}
