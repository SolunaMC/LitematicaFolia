package fr.ekaii.litematica.paste;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Configuration for a single {@link PasteOperation} execution.
 *
 * @param origin             World location where the schematic's placement
 *                           origin lands.
 * @param placeEntities      Whether to spawn entities recorded in the
 *                           regions (global {@code IgnoreEntities} gate;
 *                           per-region suppression comes from
 *                           {@link SubRegionOverride#ignoreEntities()}).
 * @param placeTileEntities  Whether to load tile-entity NBT into the
 *                           placed blocks.
 * @param placePendingTicks  Whether to re-schedule pending block & fluid
 *                           ticks.
 * @param deferredPhysics    If {@code true}, blocks are placed with
 *                           physics disabled and a sweep is performed
 *                           after to trigger physics in a controlled
 *                           order.
 * @param observersLast      Two-pass placement — inert blocks first,
 *                           then observers / pistons / comparators /
 *                           repeaters / redstone_wire / sticky_piston.
 *                           Mitigates the Litematica issue #538 class of
 *                           feedback-loop activation. See
 *                           {@code PasteOperation#ACTIVE_BLOCK_NAMES}.
 * @param maxBlocksPerChunkTask Hard cap on blocks written per scheduled
 *                           region task. Larger per-chunk write lists are
 *                           split into batches that yield back to the
 *                           region scheduler between batches (issue #4.7).
 * @param rotation           Global placement rotation, applied around the
 *                           placement origin.
 * @param mirror             Global placement mirror (applied before the
 *                           rotation, upstream order).
 * @param replaceBehavior    NONE / WITH_NON_AIR / ALL replace semantics
 *                           (issue #4.1). See {@link ReplaceBehavior}.
 * @param layerFilter        Optional world-position filter for
 *                           layer-limited pastes (issue #4.6); null pastes
 *                           everything.
 * @param subRegions         Per-region placement overrides keyed by region
 *                           name (issue #4.5); empty map = paste regions
 *                           as saved in the schematic.
 * @param progress           Optional progress callback. Invoked from
 *                           arbitrary threads; implementations must be
 *                           thread-safe.
 */
public record PasteOptions(
        Location origin,
        boolean placeEntities,
        boolean placeTileEntities,
        boolean placePendingTicks,
        boolean deferredPhysics,
        boolean observersLast,
        int maxBlocksPerChunkTask,
        PlacementTransform.Rot rotation,
        PlacementTransform.Mir mirror,
        ReplaceBehavior replaceBehavior,
        LayerFilter layerFilter,
        Map<String, SubRegionOverride> subRegions,
        BiConsumer<Player, String> progress
) {
    public PasteOptions {
        if (origin == null) throw new IllegalArgumentException("origin is null");
        if (rotation == null) rotation = PlacementTransform.Rot.NONE;
        if (mirror == null) mirror = PlacementTransform.Mir.NONE;
        if (replaceBehavior == null) replaceBehavior = ReplaceBehavior.WITH_NON_AIR;
        if (subRegions == null) subRegions = Map.of();
        if (maxBlocksPerChunkTask <= 0) {
            throw new IllegalArgumentException("maxBlocksPerChunkTask must be > 0");
        }
    }

    /** Legacy yaw view of {@link #rotation()} for logging (0/90/180/270). */
    public int yawRotation() {
        return rotation.degrees;
    }

    /** Override for a region name, or null when the region pastes as saved. */
    public SubRegionOverride subRegionFor(String regionName) {
        return subRegions.get(regionName);
    }

    /** Sensible defaults: everything on, deferred physics, observers-last. */
    public static PasteOptions defaults(Location origin) {
        return new PasteOptions(
                origin,
                /* placeEntities */     true,
                /* placeTileEntities */ true,
                /* placePendingTicks */ true,
                /* deferredPhysics */   true,
                /* observersLast */     true,
                /* maxBlocksPerChunkTask */ 4096,
                /* rotation */ PlacementTransform.Rot.NONE,
                /* mirror */   PlacementTransform.Mir.NONE,
                /* replaceBehavior */ ReplaceBehavior.WITH_NON_AIR,
                /* layerFilter */ null,
                /* subRegions */  Map.of(),
                /* progress */    null
        );
    }
}
