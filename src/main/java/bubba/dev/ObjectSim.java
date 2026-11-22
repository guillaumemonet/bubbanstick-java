package bubba.dev;

import bubba.cpu.GameMemory;
import bubba.engine.Assets;
import bubba.entity.LevelObject;
import bubba.entity.NativeCalls;
import bubba.level.LevelData;
import bubba.level.LevelMemory;
import bubba.level.ObjectWorld;

import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Headless check of the object system: the camera sweeps the level (hero in the middle of the
 * screen) and the objects live their lives. Usage: ObjectSim <assets> <stage> [frames].
 * Prints the number of objects over time, the types seen and the consistency of the lists.
 */
public final class ObjectSim {

    private ObjectSim() {
    }

    public static void main(String[] args) throws Exception {
        Assets assets = new Assets(Paths.get(args[0]));
        int stage = Integer.parseInt(args[1]);
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 3000;
        LevelData level = LevelData.stage(assets, stage);
        GameMemory ram = new GameMemory(assets);
        ram.loadSet(level.setImage());
        LevelMemory.build(ram, level.info(), level.rawMap());
        ram.write16(0x10a42, ((level.info()[0x0e] & 0xff) << 8) | (level.info()[0x0f] & 0xff));
        NativeCalls natives = new NativeCalls(ram, level);
        ObjectWorld world = new ObjectWorld(ram, level);
        world.setNatives(natives);
        Map<Integer, Integer> spawned = new TreeMap<>();
        natives.setSpawner((parent, index) -> {
            int c = world.spawnChild(parent, index);
            if (c >= 0) {
                spawned.merge(index + 1, 1, Integer::sum);
            }
            return c;
        });
        int w = level.widthPx() - 320, h = level.heightPx() - 256;
        int max = 0, hurts = 0;
        Set<Integer> items = new java.util.TreeSet<>(), harmful = new java.util.TreeSet<>();
        Set<Integer> types = new HashSet<>();
        double cx = 0, cy = Math.max(0, Math.min(h, level.startY() - 160));
        boolean vertical = level.heightPx() > level.widthPx();
        for (int f = 0; f < frames; f++) {
            // sweep: 3 px per frame along the long axis, back and forth
            double t = (f * 3.0) % (2.0 * (vertical ? h : w));
            double pos = t < (vertical ? h : w) ? t : 2.0 * (vertical ? h : w) - t;
            if (vertical) {
                cy = pos;
                cx = Math.max(0, Math.min(w, level.startX() - 160));
            } else {
                cx = pos;
            }
            int hx = (int) cx + 160, hy = (int) cy + 160;
            ram.write16(0x10912, hx);
            ram.write16(0x10916, hy);
            if (world.update((int) cx, (int) cy, hx, hy, 64)) {
                hurts++;
            }
            LevelObject[] act = world.active();
            max = Math.max(max, act.length);
            Set<Integer> bases = new HashSet<>();
            for (LevelObject o : act) {
                types.add(o.typeId());
                if ((o.getByte(0x83) & 8) != 0) {
                    items.add(o.typeId());
                } else if ((o.getByte(0x83) & 4) != 0) {
                    harmful.add(o.typeId());
                }
                if (!bases.add(o.base) || o.word(0) == 0) {
                    System.out.printf("frame %d: bad list entry $%X (state %04X)%n", f, o.base, o.word(0));
                }
            }
            if (f % 500 == 0) {
                System.out.printf("frame %5d cam %4d,%4d: %2d objects%n", f, (int) cx, (int) cy, act.length);
            }
        }
        System.out.printf("stage %d: max %d objects, types alive %s%n", stage, max, new java.util.TreeSet<>(types));
        System.out.println("items " + items + " harmful " + harmful + " hurts " + hurts + " score " + Integer.toHexString(ram.read32(0x10ab0)));
        System.out.println("children created by scripts (type: count): " + spawned);
        System.out.println("natives java " + natives.javaCalls.size() + " / emulated " + natives.emulatedCalls.keySet());
    }
}
