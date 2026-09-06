import fr.ekaii.litematica.core.LitematicNbt;
import fr.ekaii.litematica.core.LitematicReader;
import fr.ekaii.litematica.core.LitematicRegion;
import fr.ekaii.litematica.core.LitematicSchematic;

import java.nio.file.Path;

/**
 * Harness oracle: dumps a .litematic to a stable grep-able text form.
 * Used by fidelity-smoke.sh to assert entity positions / yaw after a
 * paste-then-save round trip.
 *
 * Output lines:
 *   region <name> pos=<x>,<y>,<z> size=<sx>x<sy>x<sz> nonair=<n>
 *   block <x>,<y>,<z> <full blockstate string>      (non-air cells only)
 *   entity <id> pos=<x>,<y>,<z> yaw=<f>
 */
public final class DumpLitematic {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: DumpLitematic <file.litematic>");
            System.exit(2);
        }
        LitematicSchematic s = LitematicReader.read(Path.of(args[0]));
        for (LitematicRegion r : s.regionsList()) {
            System.out.println("region " + r.name
                    + " pos=" + r.originX + "," + r.originY + "," + r.originZ
                    + " size=" + r.sizeX + "x" + r.sizeY + "x" + r.sizeZ
                    + " nonair=" + r.countNonAir());
            for (int y = 0; y < r.sizeY; y++) {
                for (int z = 0; z < r.sizeZ; z++) {
                    for (int x = 0; x < r.sizeX; x++) {
                        int idx = r.blockIndexAt(x, y, z);
                        if (idx <= 0 || idx >= r.palette.size()) continue;
                        String state = r.palette.get(idx).toMinecraftString();
                        if (state.equals("minecraft:air")) continue;
                        System.out.println("block " + x + "," + y + "," + z + " " + state);
                    }
                }
            }
            if (r.entities == null) continue;
            for (LitematicNbt.NbtTag t : r.entities.values()) {
                if (!(t instanceof LitematicNbt.NbtCompound c)) continue;
                String id = c.getString("id");
                LitematicNbt.NbtList pos = c.getList("Pos");
                LitematicNbt.NbtList rot = c.getList("Rotation");
                double x = 0, y = 0, z = 0;
                if (pos != null && pos.size() >= 3) {
                    x = dbl(pos.get(0)); y = dbl(pos.get(1)); z = dbl(pos.get(2));
                }
                float yaw = 0;
                if (rot != null && rot.size() >= 1 && rot.get(0) instanceof LitematicNbt.NbtFloat f) {
                    yaw = f.value();
                }
                System.out.printf(java.util.Locale.ROOT,
                        "entity %s pos=%.3f,%.3f,%.3f yaw=%.1f%n", id, x, y, z, yaw);
            }
        }
    }

    private static double dbl(LitematicNbt.NbtTag t) {
        if (t instanceof LitematicNbt.NbtDouble d) return d.value();
        if (t instanceof LitematicNbt.NbtFloat f) return f.value();
        return 0;
    }
}
