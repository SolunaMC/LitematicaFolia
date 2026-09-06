package fr.ekaii.litematica.paste;

import fr.ekaii.litematica.core.LitematicNbt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-sub-region placement override, from the {@code SubRegions} compound of
 * a Litematica Direct Paste placement
 * ({@code SchematicPlacement.toData()}): the client's effective sub-region
 * position/rotation/mirror/enabled state, which REPLACES the raw values
 * baked into the schematic file.
 *
 * @param hasPos         whether the override carries a position (int[3] "Pos")
 * @param posX           override region position (pos1 corner, placement-relative)
 * @param posY           override region position
 * @param posZ           override region position
 * @param rotation       extra per-region rotation
 * @param mirror         extra per-region mirror (pre axis-swap)
 * @param enabled        disabled regions are not pasted at all
 * @param ignoreEntities region-local entity suppression
 */
public record SubRegionOverride(
        boolean hasPos,
        int posX, int posY, int posZ,
        PlacementTransform.Rot rotation,
        PlacementTransform.Mir mirror,
        boolean enabled,
        boolean ignoreEntities
) {

    /**
     * Parses the whole {@code SubRegions} compound; keys are region names.
     * Returns an empty map when absent (an empty map means "no overrides",
     * every region pastes as saved).
     */
    public static Map<String, SubRegionOverride> parseAll(LitematicNbt.NbtCompound subRegions) {
        Map<String, SubRegionOverride> out = new LinkedHashMap<>();
        if (subRegions == null) return out;
        for (Map.Entry<String, LitematicNbt.NbtTag> e : subRegions.entries().entrySet()) {
            if (e.getValue() instanceof LitematicNbt.NbtCompound c) {
                out.put(e.getKey(), parseOne(c));
            }
        }
        return out;
    }

    static SubRegionOverride parseOne(LitematicNbt.NbtCompound c) {
        int[] pos = c.getIntArray("Pos");
        boolean hasPos = pos != null && pos.length >= 3;
        Byte enabled = c.getByte("Enabled");
        Byte ignoreEnt = c.getByte("IgnoreEntities");
        return new SubRegionOverride(
                hasPos,
                hasPos ? pos[0] : 0, hasPos ? pos[1] : 0, hasPos ? pos[2] : 0,
                PlacementTransform.readRotation(c, "Rotation"),
                PlacementTransform.readMirror(c, "Mirror"),
                enabled == null || enabled != 0,
                ignoreEnt != null && ignoreEnt != 0);
    }
}
