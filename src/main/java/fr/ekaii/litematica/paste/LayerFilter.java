package fr.ekaii.litematica.paste;

import fr.ekaii.litematica.core.LitematicNbt;

/**
 * World-position filter reproducing Litematica's render-layer-limited paste
 * ({@code PasteLayerBehavior=RENDERED_ONLY} + {@code RenderLayerRange}).
 *
 * <p>The wire shape is the NBT encoding of Servux's
 * {@code LayerRange.CODEC}: {@code mode} and {@code axis} as lowercase
 * serialized names, plus the int bounds. Filtering happens on final world
 * coordinates, exactly like upstream {@code shouldPasteBlock} /
 * {@code shouldPasteEntity}.
 */
public final class LayerFilter {

    /** Layer mode serialized names, from upstream {@code LayerMode}. */
    private enum Mode { ALL, SINGLE_LAYER, LAYER_RANGE, ALL_BELOW, ALL_ABOVE }

    private enum Axis { X, Y, Z }

    private final Mode mode;
    private final Axis axis;
    private final int layerSingle;
    private final int layerAbove;
    private final int layerBelow;
    private final int layerRangeMin;
    private final int layerRangeMax;

    private LayerFilter(Mode mode, Axis axis, int layerSingle, int layerAbove,
                        int layerBelow, int layerRangeMin, int layerRangeMax) {
        this.mode = mode;
        this.axis = axis;
        this.layerSingle = layerSingle;
        this.layerAbove = layerAbove;
        this.layerBelow = layerBelow;
        this.layerRangeMin = layerRangeMin;
        this.layerRangeMax = layerRangeMax;
    }

    /**
     * Builds the effective filter from the wire fields.
     *
     * @return null when no filtering applies — behavior is {@code all} (or
     *         absent), the range compound is missing/broken, or the range
     *         mode is {@code all}. A null filter means "paste everything",
     *         matching upstream.
     */
    public static LayerFilter fromNbt(String pasteLayerBehavior, LitematicNbt.NbtCompound range) {
        if (pasteLayerBehavior == null
                || pasteLayerBehavior.equalsIgnoreCase("all")
                || range == null) {
            return null;
        }
        // Only RENDERED_ONLY ("rendered_only") activates range filtering.
        if (!pasteLayerBehavior.equalsIgnoreCase("rendered_only")
                && !pasteLayerBehavior.equalsIgnoreCase("RENDERED_ONLY")) {
            return null;
        }
        Mode mode = parseEnum(Mode.values(), range.getString("mode"), Mode.ALL);
        if (mode == Mode.ALL) return null;
        Axis axis = parseEnum(Axis.values(), range.getString("axis"), Axis.Y);
        return new LayerFilter(mode, axis,
                intOr(range, "layer_single", 0),
                intOr(range, "layer_above", 0),
                intOr(range, "layer_below", 0),
                intOr(range, "layer_range_min", 0),
                intOr(range, "layer_range_max", 0));
    }

    /** True when the world position passes the layer filter. */
    public boolean test(int x, int y, int z) {
        int v = switch (axis) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
        return switch (mode) {
            case ALL          -> true;
            case SINGLE_LAYER -> v == layerSingle;
            case ALL_ABOVE    -> v >= layerAbove;
            case ALL_BELOW    -> v <= layerBelow;
            case LAYER_RANGE  -> v >= layerRangeMin && v <= layerRangeMax;
        };
    }

    /** Entity variant: truncates like upstream {@code shouldPasteEntity}. */
    public boolean test(double x, double y, double z) {
        return test((int) x, (int) y, (int) z);
    }

    @Override
    public String toString() {
        return "LayerFilter[" + mode + " " + axis
                + " single=" + layerSingle + " above=" + layerAbove + " below=" + layerBelow
                + " range=" + layerRangeMin + ".." + layerRangeMax + "]";
    }

    private static <E extends Enum<E>> E parseEnum(E[] values, String name, E fallback) {
        if (name == null) return fallback;
        for (E e : values) {
            if (e.name().equalsIgnoreCase(name)) return e;
        }
        return fallback;
    }

    private static int intOr(LitematicNbt.NbtCompound c, String key, int fallback) {
        Integer v = c.getInt(key);
        return v == null ? fallback : v;
    }
}
