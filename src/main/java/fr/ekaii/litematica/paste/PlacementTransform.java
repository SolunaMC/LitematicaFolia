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
     * mirror's axis flips meaning for BLOCK-STATE orientation and ENTITY
     * yaw. Replicates the swap in upstream
     * {@code SchematicPlacingUtils.placeBlocksWithinChunk} /
     * {@code WorldPlacingUtils.placeBlocksToProtoChunk}.
     *
     * <p><strong>Never use the swapped mirror for POSITION mapping.</strong>
     * Upstream {@code PositionUtils.getTransformedPlacementPosition} feeds the
     * raw {@code placement.getMirror()} into the position transform; only the
     * {@code state.mirror(mirrorSub)} / {@code rotateEntity(...)} calls see
     * the swapped value. Use {@link #transformPlacementInt} /
     * {@link #transformPlacementVec} for positions instead.
     */
    public static Mir effectiveSubMirror(Mir subMirror, Rot globalRotation) {
        if (subMirror != Mir.NONE
                && (globalRotation == Rot.CLOCKWISE_90 || globalRotation == Rot.COUNTERCLOCKWISE_90)) {
            return subMirror == Mir.FRONT_BACK ? Mir.LEFT_RIGHT : Mir.FRONT_BACK;
        }
        return subMirror;
    }

    /**
     * Full sub-region POSITION composition for integer (block) coordinates:
     * global mirror+rotation first, then the sub-region's mirror+rotation
     * with the RAW (unswapped) sub mirror. Replicates upstream
     * {@code PositionUtils.getTransformedPlacementPosition}:
     * <pre>
     * pos = getTransformedBlockPos(pos, schematicPlacement.getMirror(), schematicPlacement.getRotation());
     * pos = getTransformedBlockPos(pos, placement.getMirror(), placement.getRotation());
     * </pre>
     * The quarter-turn axis swap ({@link #effectiveSubMirror}) applies only
     * to block-state orientation and entity yaw, never here. Feeding the
     * swapped mirror into this composition mirrored positions along the
     * wrong axis when a sub-region mirror combined with a global 90/270
     * rotation (issue #4 follow-up defect 1).
     */
    public static int[] transformPlacementInt(int x, int z, Mir mirG, Rot rotG,
                                              Mir subMirrorRaw, Rot rotS) {
        int[] t = transformInt(x, z, mirG, rotG);
        return transformInt(t[0], t[1], subMirrorRaw, rotS);
    }

    /**
     * Full sub-region POSITION composition for double (entity) coordinates.
     * Same convention as {@link #transformPlacementInt}; replicates the two
     * chained {@code PositionUtils.getTransformedPosition} calls in upstream
     * {@code placeEntitiesToWorldWithinChunk} /
     * {@code WorldPlacingUtils.prepareEntitiesInProtoChunk}, which also use
     * the RAW {@code placement.getMirror()}.
     */
    public static double[] transformPlacementVec(double x, double z, Mir mirG, Rot rotG,
                                                 Mir subMirrorRaw, Rot rotS) {
        double[] t = transformVec(x, z, mirG, rotG);
        return transformVec(t[0], t[1], subMirrorRaw, rotS);
    }

    /** Vanilla {@code Mth.wrapDegrees}: wraps into [-180, 180). */
    private static float wrapDegrees(float value) {
        float f = value % 360.0F;
        if (f >= 180.0F) f -= 360.0F;
        if (f < -180.0F) f += 360.0F;
        return f;
    }

    /**
     * Default vanilla {@code Entity#mirror} return value (yaw only),
     * verified against the mapped 26.2 server bytecode
     * ({@code Entity.mirror} switch: FRONT_BACK is the {@code -f} arm,
     * LEFT_RIGHT the {@code 180 - f} arm). Geometrically consistent with
     * the position transform: LEFT_RIGHT flips Z (south/north, yaw 0/180),
     * FRONT_BACK flips X (west/east, yaw 90/-90).
     */
    private static float mirrorYaw(float yaw, Mir mirror) {
        float f = wrapDegrees(yaw);
        return switch (mirror) {
            case FRONT_BACK -> -f;
            case LEFT_RIGHT -> 180.0F - f;
            default -> f;
        };
    }

    /**
     * Entity yaw under a placement transform, exactly as the Litematica
     * CLIENT computes it for both its schematic-world PREVIEW
     * ({@code WorldPlacingUtils.rotateEntity}) and its own paste
     * ({@code SchematicPlacingUtils.rotateEntity}):
     * <pre>
     * float rotationYaw = entity.getYRot();
     * if (mirrorMain != NONE) rotationYaw = entity.mirror(mirrorMain);
     * if (mirrorSub  != NONE) rotationYaw = entity.mirror(mirrorSub);
     * if (rotationCombined != NONE) rotationYaw += entity.getYRot() - entity.rotate(rotationCombined);
     * </pre>
     * Three deliberate upstream quirks reproduced faithfully, because the
     * server-side paste must land entities the way the client preview
     * showed them:
     * <ul>
     *   <li>{@code entity.mirror}/{@code entity.rotate} read the entity's
     *       ORIGINAL yaw, so the sub-mirror value REPLACES the main-mirror
     *       value instead of composing with it;</li>
     *   <li>vanilla {@code Entity.rotate(CLOCKWISE_90)} returns
     *       {@code wrap(yaw) + 90}, so the client's
     *       {@code yaw += getYRot() - rotate(rot)} yields {@code yaw - 90}
     *       on a clockwise quarter turn. That is the OPPOSITE sense of
     *       vanilla StructureTemplate placement (and of the block/position
     *       rotation), but it is what the client preview renders;</li>
     *   <li>{@code mirrorSub} here is the axis-SWAPPED sub mirror
     *       ({@link #effectiveSubMirror}), matching the client call site.</li>
     * </ul>
     * This is the pure-math model for the default {@code Entity}
     * implementation; the NMS path ({@code NmsBridge26_2.spawnEntityFromNbt})
     * calls the real {@code Entity#mirror}/{@code Entity#rotate} with the
     * same structure so hanging-entity overrides (item frame / painting
     * facing) keep their side effects.
     */
    public static float transformYaw(float yaw, Mir mirrorMain, Mir mirrorSub, Rot rotationCombined) {
        float rotationYaw = yaw;
        if (mirrorMain != Mir.NONE) rotationYaw = mirrorYaw(yaw, mirrorMain);
        if (mirrorSub != Mir.NONE) rotationYaw = mirrorYaw(yaw, mirrorSub);
        if (rotationCombined != Rot.NONE) {
            // vanilla Entity.rotate returns wrap(yaw) + degrees
            float rotated = wrapDegrees(yaw) + rotationCombined.degrees;
            rotationYaw += yaw - rotated;
        }
        return rotationYaw;
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
