package fr.ekaii.litematica.protocol.handler;

import fr.ekaii.litematica.core.LitematicNbt;
import fr.ekaii.litematica.protocol.PacketHandler;
import fr.ekaii.litematica.protocol.ProtocolBuffer;
import fr.ekaii.litematica.protocol.ProtocolConstants;
import fr.ekaii.litematica.protocol.ProtocolSessions;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * Handles the Servux handshake (C2S metadata request → S2C metadata) and
 * negotiates the per-player wire version.
 *
 * <h2>Version negotiation</h2>
 *
 * The {@code version} tag of the request discriminates the client era:
 * <ul>
 *   <li>Litematica 26.2-0.28.5+ (wire v2, Data Tag protocol) sends
 *       {@code {version: Int 2}} — see upstream
 *       {@code EntityDataManager.requestMetadata}.</li>
 *   <li>Litematica 26.2-0.28.0..0.28.4 (wire v1) sends
 *       {@code {version: String MOD_STRING}}.</li>
 * </ul>
 *
 * The reply mirrors the negotiated era:
 * <ul>
 *   <li>v2 clients hard-validate {@code version == 2} and
 *       {@code servux.startsWith("servux-fabric-" + MC_VERSION)}; on
 *       mismatch they disable their entity-data-sync config, killing
 *       Direct Paste client-side. We send exactly what they require.</li>
 *   <li>v1 clients only warn on mismatch; they keep the legacy
 *       {@code version=1} + plain server-name reply that 0.5.x shipped.</li>
 * </ul>
 *
 * <p>Source of truth: upstream {@code LitematicsDataProvider.register}
 * (26.2-0.11.3) and client {@code EntityDataManager.receiveServuxMetadata}
 * (26.2-0.28.5 vs 26.2-0.28.3). See SERVUX_WIRE_FORMAT.md.
 */
public final class MetadataHandler {

    private static final Logger LOG = Logger.getLogger("LitematicaFolia/MetadataHandler");

    private final Plugin plugin;
    private final ProtocolSessions sessions;

    public MetadataHandler(Plugin plugin, ProtocolSessions sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    /**
     * C2S {@code MetadataRequest} → S2C {@code MetadataResponse}, with
     * per-player wire-version tracking.
     */
    public void onMetadataRequest(Player player, ProtocolBuffer.Reader r) throws Exception {
        LitematicNbt.NbtCompound req = null;
        try {
            req = r.readNbt();
        } catch (Throwable ignored) {
            // Tolerate an absent/garbled request compound — treat as v1.
        }

        int wire = detectWireVersion(req);
        sessions.setVersion(player.getUniqueId(), wire);
        LOG.info("[diag-net] metadata request from " + player.getName()
                + " -> negotiated wire v" + wire
                + (req != null && req.get("version") != null
                        ? " (version tag: " + req.get("version") + ")" : " (no version tag)"));

        sendMetadata(player, wire);
    }

    /**
     * Wire-version discriminator: an {@code Int} {@code version} tag of
     * 2 or above marks a v2 (0.28.5+) client; a {@code String} tag (the
     * old MOD_STRING) or a missing tag marks a v1 client.
     */
    static int detectWireVersion(LitematicNbt.NbtCompound req) {
        if (req != null && req.get("version") instanceof LitematicNbt.NbtInt i
                && i.value() >= ProtocolConstants.LITEMATICS_PROTOCOL_VERSION_V2) {
            return ProtocolSessions.V2;
        }
        return ProtocolSessions.V1;
    }

    /**
     * Build the canonical 4-key Servux metadata compound for the given
     * wire era: {@code name}, {@code id}, {@code version}, {@code servux}.
     */
    public LitematicNbt.NbtCompound buildMetadataPayload(int wireVersion) {
        LitematicNbt.NbtCompound c = new LitematicNbt.NbtCompound();
        c.putString("name", ProtocolConstants.METADATA_PROVIDER_NAME);
        c.putString("id",   ProtocolConstants.CHANNEL_LITEMATICS);
        if (wireVersion >= ProtocolSessions.V2) {
            c.putInt("version", ProtocolConstants.LITEMATICS_PROTOCOL_VERSION_V2);
            c.putString("servux", ProtocolConstants.servuxCompatString(
                    plugin.getServer().getMinecraftVersion()));
        } else {
            c.putInt("version", ProtocolConstants.LITEMATICS_PROTOCOL_VERSION);
            c.putString("servux", ProtocolConstants.SERVER_NAME);
        }
        return c;
    }

    private void sendMetadata(Player player, int wireVersion) throws Exception {
        LitematicNbt.NbtCompound meta = buildMetadataPayload(wireVersion);
        byte[] bytes = PacketHandler.buildLitematics(
                ProtocolConstants.Litematics.S2C_METADATA,
                w -> w.writeNbt(meta));
        player.sendPluginMessage(plugin, ProtocolConstants.CHANNEL_LITEMATICS, bytes);
    }

    /**
     * Proactively push the metadata to a player after join, as a
     * fallback for clients that never send their own request. Every
     * MC 26.2 Litematica build (0.28.x) auto-requests on its tick loop,
     * so in practice the client's request lands first and fixes the
     * negotiated version; this push then re-sends idempotently. For a
     * player with no handshake on record we push the v2 shape: 0.28.5
     * validates it, 0.28.0-0.28.4 warn and accept.
     */
    public void pushMetadata(Player player) {
        try {
            int wire = sessions.versionOr(player.getUniqueId(), ProtocolSessions.V2);
            sendMetadata(player, wire);
            LOG.info("[diag-net] TX servux:litematics S2C_METADATA (v" + wire
                    + " shape) pushed to " + player.getName());
        } catch (Throwable t) {
            LOG.warning("pushMetadata failed for " + player.getName() + ": " + t);
        }
    }
}
