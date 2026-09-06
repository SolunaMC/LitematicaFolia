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

    // ------------------------------------------------ defect 1: positions use RAW sub mirror

    @Test
    void sub_mirror_positions_use_unswapped_mirror_under_global_quarter_turn() {
        // Audit counterexample: region-local (1,2), global rot CW90, sub
        // mirror FRONT_BACK. Upstream getTransformedPlacementPosition:
        // Tg: (1,2) -> (-2,1); then FRONT_BACK (x=-x), UNSWAPPED -> (2,1).
        assertArrayEquals(new int[] {2, 1}, PlacementTransform.transformPlacementInt(
                1, 2, Mir.NONE, Rot.CLOCKWISE_90, Mir.FRONT_BACK, Rot.NONE));
        // The pre-fix code fed the axis-SWAPPED mirror (LEFT_RIGHT) into the
        // position transform and produced (-2,-1): mirrored along the wrong
        // axis. Pin the distinction so a regression is caught immediately.
        int[] tg = PlacementTransform.transformInt(1, 2, Mir.NONE, Rot.CLOCKWISE_90);
        assertArrayEquals(new int[] {-2, -1}, PlacementTransform.transformInt(
                tg[0], tg[1],
                PlacementTransform.effectiveSubMirror(Mir.FRONT_BACK, Rot.CLOCKWISE_90),
                Rot.NONE),
                "swapped-mirror composition must stay distinguishable from the correct one");

        // CCW90 + LEFT_RIGHT sub: Tg: (1,2) -> (2,-1); LEFT_RIGHT (z=-z) -> (2,1).
        assertArrayEquals(new int[] {2, 1}, PlacementTransform.transformPlacementInt(
                1, 2, Mir.NONE, Rot.COUNTERCLOCKWISE_90, Mir.LEFT_RIGHT, Rot.NONE));
        // Global 180: no axis swap applies anyway. Tg: (-1,-2); FB -> (1,-2).
        assertArrayEquals(new int[] {1, -2}, PlacementTransform.transformPlacementInt(
                1, 2, Mir.NONE, Rot.CLOCKWISE_180, Mir.FRONT_BACK, Rot.NONE));
        // Sub rotation only: Ts CW90 on (1,2) -> (-2,1).
        assertArrayEquals(new int[] {-2, 1}, PlacementTransform.transformPlacementInt(
                1, 2, Mir.NONE, Rot.NONE, Mir.NONE, Rot.CLOCKWISE_90));
        // two-towers S11 cells: A local (1,0) under rotG=CW90 + sub FRONT_BACK
        // lands at world delta (0,1); local (1,1) at (1,1).
        assertArrayEquals(new int[] {0, 1}, PlacementTransform.transformPlacementInt(
                1, 0, Mir.NONE, Rot.CLOCKWISE_90, Mir.FRONT_BACK, Rot.NONE));
        assertArrayEquals(new int[] {1, 1}, PlacementTransform.transformPlacementInt(
                1, 1, Mir.NONE, Rot.CLOCKWISE_90, Mir.FRONT_BACK, Rot.NONE));
    }

    @Test
    void sub_mirror_entity_positions_use_unswapped_mirror_too() {
        // Same convention for the double (entity) transform: upstream
        // placeEntitiesToWorldWithinChunk chains getTransformedPosition with
        // the RAW placement.getMirror().
        // Tg CW90: (1.5, 2.25) -> (1-2.25, 1.5) = (-1.25, 1.5);
        // FRONT_BACK: x = 1-x -> (2.25, 1.5).
        assertArrayEquals(new double[] {2.25, 1.5}, PlacementTransform.transformPlacementVec(
                1.5, 2.25, Mir.NONE, Rot.CLOCKWISE_90, Mir.FRONT_BACK, Rot.NONE), 1e-9);
        // No sub override: composition degenerates to the global transform.
        assertArrayEquals(
                PlacementTransform.transformVec(1.5, 2.25, Mir.LEFT_RIGHT, Rot.CLOCKWISE_90),
                PlacementTransform.transformPlacementVec(
                        1.5, 2.25, Mir.LEFT_RIGHT, Rot.CLOCKWISE_90, Mir.NONE, Rot.NONE), 1e-9);
    }

    // ------------------------------------------------ defect 2: client entity yaw convention

    @Test
    void entity_yaw_matches_client_rotate_entity_formula() {
        // Client rotateEntity (WorldPlacingUtils / SchematicPlacingUtils):
        // yaw += getYRot() - rotate(rot), where vanilla Entity.rotate(CW90)
        // returns wrap(yaw)+90. Net effect: yaw MINUS the rotation degrees,
        // the OPPOSITE of vanilla StructureTemplate placement on quarter
        // turns. This is what the client preview renders, so it is what the
        // server paste must produce.
        assertEquals(-90.0f, PlacementTransform.transformYaw(0.0f, Mir.NONE, Mir.NONE, Rot.CLOCKWISE_90));
        assertEquals(-270.0f, PlacementTransform.transformYaw(0.0f, Mir.NONE, Mir.NONE, Rot.COUNTERCLOCKWISE_90));
        assertEquals(-135.0f, PlacementTransform.transformYaw(45.0f, Mir.NONE, Mir.NONE, Rot.CLOCKWISE_180));
        // Unwrapped input keeps the upstream wrap quirk: 270 -> 270+270-0 = 540 (== 180 mod 360).
        assertEquals(540.0f, PlacementTransform.transformYaw(270.0f, Mir.NONE, Mir.NONE, Rot.CLOCKWISE_90));
        // Vanilla Entity.mirror return values (verified against the mapped
        // 26.2 bytecode): FRONT_BACK -> -yaw, LEFT_RIGHT -> 180-yaw.
        // Consistent with the position mirror: LEFT_RIGHT flips Z
        // (south<->north), FRONT_BACK flips X (west<->east).
        assertEquals(-30.0f, PlacementTransform.transformYaw(30.0f, Mir.FRONT_BACK, Mir.NONE, Rot.NONE));
        assertEquals(150.0f, PlacementTransform.transformYaw(30.0f, Mir.LEFT_RIGHT, Mir.NONE, Rot.NONE));
        // Upstream quirk: the sub mirror is recomputed from the ORIGINAL yaw
        // and REPLACES the main-mirror value (it does not compose).
        assertEquals(-30.0f, PlacementTransform.transformYaw(30.0f, Mir.LEFT_RIGHT, Mir.FRONT_BACK, Rot.NONE));
        // Mirror + rotation compose additively on the rotation step:
        // FRONT_BACK on yaw 0 -> -0, then CW90 -> -90 (S9 armor stand,
        // E2E-confirmed in-world).
        assertEquals(-90.0f, PlacementTransform.transformYaw(0.0f, Mir.FRONT_BACK, Mir.NONE, Rot.CLOCKWISE_90));
        // S11 armor stand: sub mirror FRONT_BACK swapped to LEFT_RIGHT for
        // yaw under global CW90, yaw 0 -> 180, then rotC CW90 -> 90
        // (E2E-confirmed in-world).
        assertEquals(90.0f, PlacementTransform.transformYaw(0.0f, Mir.NONE,
                PlacementTransform.effectiveSubMirror(Mir.FRONT_BACK, Rot.CLOCKWISE_90),
                Rot.CLOCKWISE_90));
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
