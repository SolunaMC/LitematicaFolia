package fr.ekaii.litematica.protocol;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player negotiated Servux wire version.
 *
 * <p>Populated by the metadata handshake
 * ({@link fr.ekaii.litematica.protocol.handler.MetadataHandler}): the
 * {@code version} tag of the C2S metadata request is an {@code Int >= 2}
 * on Litematica 26.2-0.28.5+ (wire v2) and a {@code String} (the client
 * MOD_STRING) on 0.28.0-0.28.4 (wire v1). Handlers consult this to pick
 * the right request decode and response encode; packet-shape sniffing
 * remains as a fallback for players whose handshake we never saw (for
 * example when the proactive metadata push beat the client's own
 * request).
 */
public final class ProtocolSessions {

    /** Wire era of the old (vanilla-NBT, transactionId) protocol. */
    public static final int V1 = 1;
    /** Wire era of the Data Tag / Task Scheduler protocol (0.28.5+). */
    public static final int V2 = 2;

    private final Map<UUID, Integer> versions = new ConcurrentHashMap<>();

    /** Record the version a player declared in its metadata request. */
    public void setVersion(UUID player, int version) {
        versions.put(player, version >= V2 ? V2 : V1);
    }

    /**
     * The negotiated version, or {@code fallback} when this player never
     * completed a handshake.
     */
    public int versionOr(UUID player, int fallback) {
        Integer v = versions.get(player);
        return v != null ? v : fallback;
    }

    /** Whether the player has completed a metadata handshake. */
    public boolean isKnown(UUID player) {
        return versions.containsKey(player);
    }

    /** True when the player negotiated the v2 (Data Tag) wire. */
    public boolean isV2(UUID player) {
        return versionOr(player, V1) >= V2;
    }

    /** Drop the state for a disconnecting or unregistering player. */
    public void forget(UUID player) {
        versions.remove(player);
    }
}
