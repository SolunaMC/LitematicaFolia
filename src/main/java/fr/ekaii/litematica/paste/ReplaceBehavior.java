package fr.ekaii.litematica.paste;

/**
 * Litematica/Servux paste replace semantics ({@code ReplaceMode} in the
 * Direct Paste placement NBT). Matches upstream
 * {@code fi.dy.masa.servux.util.ReplaceBehavior}:
 *
 * <ul>
 *   <li>{@link #NONE} — only write where the destination block is air.</li>
 *   <li>{@link #WITH_NON_AIR} — write every non-air schematic block over
 *       whatever is there; schematic air is skipped.</li>
 *   <li>{@link #ALL} — write everything including schematic air, clearing
 *       destination blocks inside the schematic volume.</li>
 * </ul>
 *
 * <p>{@code minecraft:structure_void} is always skipped regardless of mode
 * (upstream behavior).
 */
public enum ReplaceBehavior {
    NONE("none"),
    ALL("all"),
    WITH_NON_AIR("with_non_air");

    private final String configString;

    ReplaceBehavior(String configString) {
        this.configString = configString;
    }

    /**
     * Parses the wire value. Accepts the Litematica config string
     * ({@code none} / {@code all} / {@code with_non_air}) and the enum name,
     * case-insensitively.
     *
     * @param value    the wire string, or null when the field is absent
     * @param fallback returned when {@code value} is null. An unknown
     *                 non-null value maps to {@link #NONE}, matching Servux's
     *                 {@code fromStringStatic}.
     */
    public static ReplaceBehavior fromString(String value, ReplaceBehavior fallback) {
        if (value == null || value.isEmpty()) return fallback;
        for (ReplaceBehavior b : values()) {
            if (b.configString.equalsIgnoreCase(value) || b.name().equalsIgnoreCase(value)) {
                return b;
            }
        }
        return NONE;
    }
}
