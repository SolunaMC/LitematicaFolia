package fr.ekaii.litematica.paste;

import fr.ekaii.litematica.core.LitematicNbt;

/**
 * Placement coordinate/orientation transforms, byte-compatible with the
 * Litematica/Servux placement pipeline (issue #4).
 *
 * <p>All formulas replicate upstream exactly:
 * <ul>
 *   <li>Integer block positions: {@code PositionUtils.getTransformedBlockPos}
 *       — mirror first ({@code z=-z} / {@code x=-x}), then rotation
 *       ({@code CW90: (x,z)->(-z,x)}).</li>
 *   <li>Double (entity) positions: {@code PositionUtils.getTransformedPosition}
 *       — the cell-preserving {@code 1.0-v} convention
 *       ({@code CW90: (x,z)->(1-z,x)}). A block cell {@code [n,n+1)} maps onto
 *       the rotated cell, so an entity centred in a block stays centred in the
 *       rotated block. Plain negation (the naive fix) would shift entities
 *       into the neighbouring cell.</li>
 *   <li>Sub-region mirror axis swap when the global rotation is 90/270
 *       degrees ({@code SchematicPlacingUtils}).</li>
 * </ul>
 */
public final class PlacementTransform {

    private PlacementTransform() { }

    /**
     * Placement rotation. Ordinals match vanilla {@code net.minecraft.world.level.block.Rotation}
     * (NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90) — Litematica
     * wire v2 sends the ordinal as an Int, wire v1 the enum name as a String.
     */
    public enum Rot {
        NONE(0), CLOCKWISE_90(90), CLOCKWISE_180(180), COUNTERCLOCKWISE_90(270);

        /** Equivalent clockwise yaw in degrees (for logging / legacy yaw API). */
        public final int degrees;

        Rot(int degrees) { this.degrees = degrees; }

        public static Rot fromOrdinal(int ordinal) {
            Rot[] v = values();
            return ordinal >= 0 && ordinal < v.length ? v[ordinal] : NONE;
        }

        public static Rot fromName(String name) {
            if (name == null) return NONE;
            for (Rot r : values()) {
                if (r.name().equalsIgnoreCase(name)) return r;
            }
            return NONE;
        }

        public static Rot fromDegrees(int degrees) {
            return switch (Math.floorMod(degrees, 360)) {
                case 90  -> CLOCKWISE_90;
                case 180 -> CLOCKWISE_180;
                case 270 -> COUNTERCLOCKWISE_90;
                default  -> NONE;
            };
        }

        /** Composition, matching vanilla {@code Rotation.getRotated}: degrees add mod 360. */
        public Rot combine(Rot other) {
            return fromOrdinal((this.ordinal() + other.ordinal()) & 3);
        }
    }

    /**
     * Placement mirror. Ordinals match vanilla {@code net.minecraft.world.level.block.Mirror}
     * (NONE, LEFT_RIGHT, FRONT_BACK).
     */
    public enum Mir {
        NONE, LEFT_RIGHT, FRONT_BACK;

        public static Mir fromOrdinal(int ordinal) {
            Mir[] v = values();
            return ordinal >= 0 && ordinal < v.length ? v[ordinal] : NONE;
        }

        public static Mir fromName(String name) {
            if (name == null) return NONE;
            for (Mir m : values()) {
                if (m.name().equalsIgnoreCase(name)) return m;
            }
            return NONE;
        }
    }

    /**
     * Integer position transform (mirror, then rotation) around (0,0).
     * Replicates {@code PositionUtils.getTransformedBlockPos}. Y is untouched
     * by both transforms and therefore not part of the signature.
     */
    public static int[] transformInt(int x, int z, Mir mirror, Rot rotation) {
        switch (mirror) {
            case LEFT_RIGHT -> z = -z;
            case FRONT_BACK -> x = -x;
            default -> { }
        }
        return switch (rotation) {
            case CLOCKWISE_90        -> new int[] {-z, x};
            case CLOCKWISE_180       -> new int[] {-x, -z};
            case COUNTERCLOCKWISE_90 -> new int[] {z, -x};
            default                  -> new int[] {x, z};
        };
    }

    /**
     * Double (entity) position transform (mirror, then rotation), using the
     * upstream cell-preserving {@code 1.0-v} convention. Replicates
     * {@code PositionUtils.getTransformedPosition}.
     */
    public static double[] transformVec(double x, double z, Mir mirror, Rot rotation) {
        switch (mirror) {
            case LEFT_RIGHT -> z = 1.0D - z;
            case FRONT_BACK -> x = 1.0D - x;
            default -> { }
        }
        return switch (rotation) {
            case CLOCKWISE_90        -> new double[] {1.0D - z, x};
            case CLOCKWISE_180       -> new double[] {1.0D - x, 1.0D - z};
            case COUNTERCLOCKWISE_90 -> new double[] {z, 1.0D - x};
            default                  -> new double[] {x, z};
        };
    }

    /**
     * When the global placement rotation is a quarter turn, a sub-region
     * mirror's axis flips meaning. Replicates the swap in upstream
     * {@code SchematicPlacingUtils.placeBlocksWithinChunk}.
     */
    public static Mir effectiveSubMirror(Mir subMirror, Rot globalRotation) {
        if (subMirror != Mir.NONE
                && (globalRotation == Rot.CLOCKWISE_90 || globalRotation == Rot.COUNTERCLOCKWISE_90)) {
            return subMirror == Mir.FRONT_BACK ? Mir.LEFT_RIGHT : Mir.FRONT_BACK;
        }
        return subMirror;
    }

    /**
     * Reads a Rotation field that may be either the v1 String enum name or
     * the v2 Int enum ordinal.
     */
    public static Rot readRotation(LitematicNbt.NbtCompound payload, String key) {
        LitematicNbt.NbtTag tag = payload.get(key);
        if (tag instanceof LitematicNbt.NbtString s) return Rot.fromName(s.value());
        if (tag instanceof LitematicNbt.NbtInt i)    return Rot.fromOrdinal(i.value());
        return Rot.NONE;
    }

    /** Reads a Mirror field (String name or Int ordinal), like {@link #readRotation}. */
    public static Mir readMirror(LitematicNbt.NbtCompound payload, String key) {
        LitematicNbt.NbtTag tag = payload.get(key);
        if (tag instanceof LitematicNbt.NbtString s) return Mir.fromName(s.value());
        if (tag instanceof LitematicNbt.NbtInt i)    return Mir.fromOrdinal(i.value());
        return Mir.NONE;
    }
}
