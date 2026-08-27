package fr.ekaii.litematica.paste;

import fr.ekaii.litematica.core.LitematicNbt;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static fr.ekaii.litematica.paste.PlacementTransform.Mir;
import static fr.ekaii.litematica.paste.PlacementTransform.Rot;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the issue #4 placement-fidelity primitives: the
 * int/double coordinate transforms, sub-mirror axis swap, rotation
 * composition, replace-mode parsing, layer-range filter, and SubRegions
 * override parsing. Semantics are byte-matched to upstream Litematica
 * {@code PositionUtils} / Servux {@code SchematicPlacingUtils}.
 */
class PlacementFidelityTest {

    // ------------------------------------------------ int transform

    @Test
    void int_rotations_match_vanilla_block_pos_rotate() {
        assertArrayEquals(new int[] {2, 3},  PlacementTransform.transformInt(2, 3, Mir.NONE, Rot.NONE));
        assertArrayEquals(new int[] {-3, 2}, PlacementTransform.transformInt(2, 3, Mir.NONE, Rot.CLOCKWISE_90));
        assertArrayEquals(new int[] {-2, -3}, PlacementTransform.transformInt(2, 3, Mir.NONE, Rot.CLOCKWISE_180));
        assertArrayEquals(new int[] {3, -2}, PlacementTransform.transformInt(2, 3, Mir.NONE, Rot.COUNTERCLOCKWISE_90));
    }

    @Test
    void int_mirror_applies_before_rotation() {
        // LEFT_RIGHT mirrors Z, FRONT_BACK mirrors X (upstream getTransformedBlockPos).
        assertArrayEquals(new int[] {2, -3}, PlacementTransform.transformInt(2, 3, Mir.LEFT_RIGHT, Rot.NONE));
        assertArrayEquals(new int[] {-2, 3}, PlacementTransform.transformInt(2, 3, Mir.FRONT_BACK, Rot.NONE));
        // mirror z=-3 then CW90 -> (3, 2)
        assertArrayEquals(new int[] {3, 2},  PlacementTransform.transformInt(2, 3, Mir.LEFT_RIGHT, Rot.CLOCKWISE_90));
    }

    // ------------------------------------------------ double transform

    @Test
    void vec_rotation_uses_cell_preserving_convention() {
        // Upstream getTransformedPosition: CW90 (x,z) -> (1-z, x). The issue's
        // suggested plain negation (-z, x) would put the entity one whole
        // block off; the correct transform for (2.5, 3.25) is (-2.25, 2.5).
        assertArrayEquals(new double[] {-2.25, 2.5},
                PlacementTransform.transformVec(2.5, 3.25, Mir.NONE, Rot.CLOCKWISE_90), 1e-9);
        assertArrayEquals(new double[] {-1.5, -2.25},
                PlacementTransform.transformVec(2.5, 3.25, Mir.NONE, Rot.CLOCKWISE_180), 1e-9);
        assertArrayEquals(new double[] {3.25, -1.5},
                PlacementTransform.transformVec(2.5, 3.25, Mir.NONE, Rot.COUNTERCLOCKWISE_90), 1e-9);
        assertArrayEquals(new double[] {2.5, 3.25},
                PlacementTransform.transformVec(2.5, 3.25, Mir.NONE, Rot.NONE), 1e-9);
    }

    @Test
    void vec_transform_keeps_entity_in_rotated_block_cell() {
        // Property: an entity strictly inside block cell (bx, bz) must land
        // inside the transformed block cell for every rotation/mirror combo.
        // Exact cell corners (offset 0.0) are excluded: upstream's 1.0-v
        // convention maps the half-open interval [n, n+1) onto (m, m+1], so
        // an entity exactly on the corner sits on the boundary — inherent to
        // the upstream formula, reproduced faithfully.
        int bx = 2, bz = 3;
        double[][] offsets = { {0.5, 0.5}, {0.01, 0.99}, {0.99, 0.01}, {0.25, 0.75} };
        for (Rot rot : Rot.values()) {
            for (Mir mir : Mir.values()) {
                int[] cell = PlacementTransform.transformInt(bx, bz, mir, rot);
                for (double[] off : offsets) {
                    double[] v = PlacementTransform.transformVec(bx + off[0], bz + off[1], mir, rot);
                    assertEquals(cell[0], (int) Math.floor(v[0]), 1e-9,
                            "x cell mismatch rot=" + rot + " mir=" + mir + " off=" + off[0] + "," + off[1]);
                    assertEquals(cell[1], (int) Math.floor(v[1]), 1e-9,
                            "z cell mismatch rot=" + rot + " mir=" + mir + " off=" + off[0] + "," + off[1]);
                }
            }
        }
    }

    @Test
    void vec_mirror_matches_upstream() {
        assertArrayEquals(new double[] {2.5, -2.25},
                PlacementTransform.transformVec(2.5, 3.25, Mir.LEFT_RIGHT, Rot.NONE), 1e-9);
        assertArrayEquals(new double[] {-1.5, 3.25},
                PlacementTransform.transformVec(2.5, 3.25, Mir.FRONT_BACK, Rot.NONE), 1e-9);
    }

    // ------------------------------------------------ composition + sub mirror

    @Test
    void rotation_composition_matches_vanilla_getRotated() {
        assertEquals(Rot.CLOCKWISE_180, Rot.CLOCKWISE_90.combine(Rot.CLOCKWISE_90));
        assertEquals(Rot.NONE, Rot.CLOCKWISE_90.combine(Rot.COUNTERCLOCKWISE_90));
        assertEquals(Rot.COUNTERCLOCKWISE_90, Rot.CLOCKWISE_180.combine(Rot.CLOCKWISE_90));
        assertEquals(Rot.CLOCKWISE_90, Rot.NONE.combine(Rot.CLOCKWISE_90));
    }

    @Test
    void sub_mirror_axis_swaps_under_quarter_turns() {
        assertEquals(Mir.LEFT_RIGHT, PlacementTransform.effectiveSubMirror(Mir.FRONT_BACK, Rot.CLOCKWISE_90));
        assertEquals(Mir.FRONT_BACK, PlacementTransform.effectiveSubMirror(Mir.LEFT_RIGHT, Rot.COUNTERCLOCKWISE_90));
        assertEquals(Mir.LEFT_RIGHT, PlacementTransform.effectiveSubMirror(Mir.LEFT_RIGHT, Rot.CLOCKWISE_180));
        assertEquals(Mir.LEFT_RIGHT, PlacementTransform.effectiveSubMirror(Mir.LEFT_RIGHT, Rot.NONE));
        assertEquals(Mir.NONE, PlacementTransform.effectiveSubMirror(Mir.NONE, Rot.CLOCKWISE_90));
    }

    // ------------------------------------------------ wire parsing

    @Test
    void rotation_and_mirror_parse_both_wire_encodings() {
        LitematicNbt.NbtCompound v1 = new LitematicNbt.NbtCompound();
        v1.putString("Rotation", "COUNTERCLOCKWISE_90");
        v1.putString("Mirror", "FRONT_BACK");
        assertEquals(Rot.COUNTERCLOCKWISE_90, PlacementTransform.readRotation(v1, "Rotation"));
        assertEquals(Mir.FRONT_BACK, PlacementTransform.readMirror(v1, "Mirror"));

        LitematicNbt.NbtCompound v2 = new LitematicNbt.NbtCompound();
        v2.putInt("Rotation", 2);
        v2.putInt("Mirror", 1);
        assertEquals(Rot.CLOCKWISE_180, PlacementTransform.readRotation(v2, "Rotation"));
        assertEquals(Mir.LEFT_RIGHT, PlacementTransform.readMirror(v2, "Mirror"));

        LitematicNbt.NbtCompound empty = new LitematicNbt.NbtCompound();
        assertEquals(Rot.NONE, PlacementTransform.readRotation(empty, "Rotation"));
        assertEquals(Mir.NONE, PlacementTransform.readMirror(empty, "Mirror"));
    }

    @Test
    void replace_behavior_parses_config_strings_and_defaults() {
        assertEquals(ReplaceBehavior.NONE, ReplaceBehavior.fromString("none", ReplaceBehavior.WITH_NON_AIR));
        assertEquals(ReplaceBehavior.ALL, ReplaceBehavior.fromString("all", ReplaceBehavior.WITH_NON_AIR));
        assertEquals(ReplaceBehavior.WITH_NON_AIR, ReplaceBehavior.fromString("with_non_air", ReplaceBehavior.NONE));
        assertEquals(ReplaceBehavior.ALL, ReplaceBehavior.fromString("ALL", ReplaceBehavior.NONE));
        // Absent -> fallback (legacy compat); unknown -> NONE like Servux.
        assertEquals(ReplaceBehavior.WITH_NON_AIR, ReplaceBehavior.fromString(null, ReplaceBehavior.WITH_NON_AIR));
        assertEquals(ReplaceBehavior.NONE, ReplaceBehavior.fromString("bogus", ReplaceBehavior.WITH_NON_AIR));
    }

    // ------------------------------------------------ layer filter

    private static LitematicNbt.NbtCompound range(String mode, String axis) {
        LitematicNbt.NbtCompound c = new LitematicNbt.NbtCompound();
        c.putString("mode", mode);
        c.putString("axis", axis);
        c.putInt("layer_single", 64);
        c.putInt("layer_above", 10);
        c.putInt("layer_below", -5);
        c.putInt("layer_range_min", 60);
        c.putInt("layer_range_max", 70);
        return c;
    }

    @Test
    void layer_filter_inactive_for_all_behavior_or_all_mode() {
        assertNull(LayerFilter.fromNbt("all", range("single_layer", "y")));
        assertNull(LayerFilter.fromNbt(null, range("single_layer", "y")));
        assertNull(LayerFilter.fromNbt("rendered_only", null));
        assertNull(LayerFilter.fromNbt("rendered_only", range("all", "y")));
    }

    @Test
    void layer_filter_modes() {
        LayerFilter single = LayerFilter.fromNbt("rendered_only", range("single_layer", "y"));
        assertNotNull(single);
        assertTrue(single.test(0, 64, 0));
        assertFalse(single.test(0, 65, 0));

        LayerFilter above = LayerFilter.fromNbt("rendered_only", range("all_above", "y"));
        assertNotNull(above);
        assertTrue(above.test(0, 10, 0));
        assertTrue(above.test(0, 300, 0));
        assertFalse(above.test(0, 9, 0));

        LayerFilter below = LayerFilter.fromNbt("rendered_only", range("all_below", "y"));
        assertNotNull(below);
        assertTrue(below.test(0, -5, 0));
        assertFalse(below.test(0, -4, 0));

        LayerFilter band = LayerFilter.fromNbt("rendered_only", range("layer_range", "y"));
        assertNotNull(band);
        assertTrue(band.test(0, 60, 0));
        assertTrue(band.test(0, 70, 0));
        assertFalse(band.test(0, 59, 0));
        assertFalse(band.test(0, 71, 0));

        LayerFilter xAxis = LayerFilter.fromNbt("rendered_only", range("single_layer", "x"));
        assertNotNull(xAxis);
        assertTrue(xAxis.test(64, 0, 0));
        assertFalse(xAxis.test(63, 0, 0));
    }

    // ------------------------------------------------ SubRegions overrides

    @Test
    void sub_regions_parse_pos_rotation_enabled() {
        LitematicNbt.NbtCompound subs = new LitematicNbt.NbtCompound();

        LitematicNbt.NbtCompound a = new LitematicNbt.NbtCompound();
        a.putIntArray("Pos", new int[] {10, 0, -4});
        a.putInt("Rotation", 1);
        a.putInt("Mirror", 0);
        a.putByte("Enabled", (byte) 1);
        a.putByte("IgnoreEntities", (byte) 0);
        subs.put("towerA", a);

        LitematicNbt.NbtCompound b = new LitematicNbt.NbtCompound();
        b.putIntArray("Pos", new int[] {0, 0, 0});
        b.putByte("Enabled", (byte) 0);
        subs.put("towerB", b);

        Map<String, SubRegionOverride> parsed = SubRegionOverride.parseAll(subs);
        assertEquals(2, parsed.size());

        SubRegionOverride ovA = parsed.get("towerA");
        assertTrue(ovA.hasPos());
        assertEquals(10, ovA.posX());
        assertEquals(-4, ovA.posZ());
        assertEquals(Rot.CLOCKWISE_90, ovA.rotation());
        assertEquals(Mir.NONE, ovA.mirror());
        assertTrue(ovA.enabled());
        assertFalse(ovA.ignoreEntities());

        SubRegionOverride ovB = parsed.get("towerB");
        assertFalse(ovB.enabled());

        // Absent compound -> empty map, and absent Enabled defaults true.
        assertTrue(SubRegionOverride.parseAll(null).isEmpty());
        SubRegionOverride minimal = SubRegionOverride.parseOne(new LitematicNbt.NbtCompound());
        assertTrue(minimal.enabled());
        assertFalse(minimal.hasPos());
    }

    // ------------------------------------------------ issue #4.2 regression math

    @Test
    void entity_world_position_is_origin_plus_region_pos_plus_local() {
        // Reproduces the issue's worked example: region Position X = 100,
        // entity local Pos X = 2.5, no rotation. Expected world X =
        // pasteOriginX + 100 + 2.5 (the old code produced originX + 2.5).
        int originX = 1000;
        int regionPosX = 100;
        double entityLocalX = 2.5;
        int[] base = PlacementTransform.transformInt(regionPosX, 0, Mir.NONE, Rot.NONE);
        double[] local = PlacementTransform.transformVec(entityLocalX, 0, Mir.NONE, Rot.NONE);
        assertEquals(1102.5, originX + base[0] + local[0], 1e-9);
    }
}
