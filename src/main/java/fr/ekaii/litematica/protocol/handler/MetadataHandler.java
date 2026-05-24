package fr.ekaii.litematica.protocol.handler;

import fr.ekaii.litematica.core.LitematicNbt;
import fr.ekaii.litematica.protocol.PacketHandler;
import fr.ekaii.litematica.protocol.ProtocolBuffer;
import fr.ekaii.litematica.protocol.ProtocolConstants;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Handles the Servux handshake + simple BlockEntity / Entity NBT replies.
 *
 * <p>When a Servux-equipped client joins, the client opens the
 * {@code servux:litematics} channel and immediately sends a
 * {@link ProtocolConstants.Litematics#C2S_METADATA_REQUEST}. We respond
 * with {@link ProtocolConstants.Litematics#S2C_METADATA} carrying:
 * <ul>
 *   <li>{@code String "name"} — provider name (matches Servux convention).</li>
 *   <li>{@code String "id"}   — channel identifier.</li>
 *   <li>{@code Int    "version"} — Servux-protocol version for compatibility
 *       (we emit {@code 1} to match Servux's
 *       {@code ServuxLitematicaPacket.PROTOCOL_VERSION}).</li>
 *   <li>{@code String "servux"} — server software identifier.</li>
 *   <li>{@code String "ServerVersion"}   — {@code "LitematicaFolia 0.1.0"}.</li>
 *   <li>{@code List   "Capabilities"}    — strings advertised to clients.</li>
 *   <li>{@code Int    "ProtocolVersion"} — our extension version (3).</li>
 * </ul>
 */
public final class MetadataHandler {

    private static final Logger LOG = Logger.getLogger("LitematicaFolia/MetadataHandler");

    /** Servux's own protocol version for the {@code servux:litematics} channel. */
    private static final int SERVUX_LITEMATICS_PROTOCOL = 1;

    private final Plugin plugin;

    public MetadataHandler(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * C2S {@code MetadataRequest} → S2C {@code MetadataResponse} with our
     * capability set.
     */
    public void onMetadataRequest(Player player, ProtocolBuffer.Reader r) throws Exception {
        // Drain any optional NBT the client may send (Servux currently
        // sends an empty compound here).
        try { r.readNbt(); } catch (Throwable ignored) {}

        LitematicNbt.NbtCompound meta = buildMetadataPayload();
        byte[] bytes = PacketHandler.buildLitematics(
                ProtocolConstants.Litematics.S2C_METADATA,
                w -> w.writeNbt(meta));
        player.sendPluginMessage(plugin, ProtocolConstants.CHANNEL_LITEMATICS, bytes);
    }

    public LitematicNbt.NbtCompound buildMetadataPayload() {
        LitematicNbt.NbtCompound c = new LitematicNbt.NbtCompound();
        c.putString("name",    "litematic_data");
        c.putString("id",      ProtocolConstants.CHANNEL_LITEMATICS);
        c.putInt   ("version", SERVUX_LITEMATICS_PROTOCOL);
        c.putString("servux",  ProtocolConstants.SERVER_NAME);

        // Our extension keys (announced as additional fields so older
        // Servux clients simply ignore them).
        c.putString("ServerVersion",   ProtocolConstants.SERVER_NAME);
        c.putInt   ("ProtocolVersion", ProtocolConstants.CAPABILITY_PROTOCOL_VERSION);

        List<LitematicNbt.NbtTag> caps = new ArrayList<>();
        for (String cap : ProtocolConstants.CAPABILITIES) {
            caps.add(new LitematicNbt.NbtString(cap));
        }
        c.put("Capabilities", new LitematicNbt.NbtList(LitematicNbt.TAG_STRING, caps));
        return c;
    }

    // -------------------------- BlockEntity / Entity simple replies --------

    /**
     * C2S {@code BlockEntityRequest} → S2C {@code SimpleBlockResponse}.
     * Implemented as a stub that returns an empty NBT compound. Real
     * implementation would need NMS access to the live BE NBT; we defer
     * to a later iteration.
     *
     * <p>// TODO smoke-test with vanilla Litematica client and wire up
     * real BE serialisation via NmsBridge.
     */
    public void onBlockEntityRequest(Player player, ProtocolBuffer.Reader r) throws Exception {
        try { r.readVarInt(); } catch (Throwable ignored) {}  // legacy transactionId
        int[] pos;
        try {
            pos = r.readBlockPos();
        } catch (Throwable t) {
            LOG.warning("BE request: bad blockpos");
            return;
        }
        LitematicNbt.NbtCompound be = new LitematicNbt.NbtCompound();
        byte[] reply = PacketHandler.buildLitematics(
                ProtocolConstants.Litematics.S2C_BLOCK_NBT_REPLY,
                w -> {
                    w.writeBlockPos(pos[0], pos[1], pos[2]);
                    w.writeNbt(be);
                });
        player.sendPluginMessage(plugin, ProtocolConstants.CHANNEL_LITEMATICS, reply);
    }

    /**
     * C2S {@code EntityRequest} → S2C {@code SimpleEntityResponse}.
     * Same stubbing rationale as {@link #onBlockEntityRequest}.
     *
     * <p>// TODO smoke-test with vanilla Litematica client.
     */
    public void onEntityRequest(Player player, ProtocolBuffer.Reader r) throws Exception {
        try { r.readVarInt(); } catch (Throwable ignored) {}  // legacy transactionId
        int entityId;
        try {
            entityId = r.readVarInt();
        } catch (Throwable t) {
            LOG.warning("entity request: bad entityId");
            return;
        }
        LitematicNbt.NbtCompound ent = new LitematicNbt.NbtCompound();
        byte[] reply = PacketHandler.buildLitematics(
                ProtocolConstants.Litematics.S2C_ENTITY_NBT_REPLY,
                w -> {
                    w.writeVarInt(entityId);
                    w.writeNbt(ent);
                });
        player.sendPluginMessage(plugin, ProtocolConstants.CHANNEL_LITEMATICS, reply);
    }

    /**
     * C2S {@code BulkNbtRequest} for a whole chunk.
     *
     * <p>// TODO smoke-test with vanilla Litematica client. Real
     * implementation needs to iterate the chunk on the owning region
     * thread (FoliaCompat) and stream the result via the splitter
     * packets.
     */
    public void onBulkRequest(Player player, ProtocolBuffer.Reader r) throws Exception {
        int[] cp;
        try {
            cp = r.readChunkPos();
        } catch (Throwable t) {
            LOG.warning("bulk request: bad chunkpos");
            return;
        }
        try { r.readNbt(); } catch (Throwable ignored) {}
        LOG.fine("bulk request for chunk " + cp[0] + "," + cp[1] + " from " + player.getName()
                + " — not implemented yet");
        // No-op; clients fall back to per-entity / per-BE requests.
    }
}
