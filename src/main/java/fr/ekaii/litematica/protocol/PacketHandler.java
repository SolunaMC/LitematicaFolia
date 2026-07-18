package fr.ekaii.litematica.protocol;

import fr.ekaii.litematica.protocol.handler.BlockEntityRequestHandler;
import fr.ekaii.litematica.protocol.handler.BulkNbtRequestHandler;
import fr.ekaii.litematica.protocol.handler.DirectPasteHandler;
import fr.ekaii.litematica.protocol.handler.EasyPlaceHandler;
import fr.ekaii.litematica.protocol.handler.EntityRequestHandler;
import fr.ekaii.litematica.protocol.handler.MetadataHandler;
import fr.ekaii.litematica.protocol.handler.StructureBboxHandler;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central dispatcher for inbound custom-payload packets across all
 * Servux-compatible channels. Decodes the leading VarInt packet type and
 * routes to the appropriate handler.
 *
 * <p>This class plays the role of the Netty handler the task brief asks
 * for — but using Paper's plugin-messaging API instead of raw Netty
 * pipeline injection. Same shape: decode → dispatch → encode reply.
 */
public final class PacketHandler {

    private static final Logger LOG = Logger.getLogger("LitematicaFolia/PacketHandler");

    /** Top-level "litematica.protocol.use" permission gating any payload at all. */
    public static final String PERM_USE       = "litematica.protocol.use";
    public static final String PERM_PASTE     = "litematica.protocol.paste";
    public static final String PERM_EASYPLACE = "litematica.protocol.easyplace";

    private final Plugin plugin;
    private final MetadataHandler metadata;
    private final EasyPlaceHandler easyPlace;
    private final DirectPasteHandler directPaste;
    private final StructureBboxHandler structures;
    private final BlockEntityRequestHandler blockEntityRequest;
    private final EntityRequestHandler entityRequest;
    private final BulkNbtRequestHandler bulkNbtRequest;

    public PacketHandler(Plugin plugin,
                         MetadataHandler metadata,
                         EasyPlaceHandler easyPlace,
                         DirectPasteHandler directPaste,
                         StructureBboxHandler structures,
                         BlockEntityRequestHandler blockEntityRequest,
                         EntityRequestHandler entityRequest,
                         BulkNbtRequestHandler bulkNbtRequest) {
        this.plugin = plugin;
        this.metadata = metadata;
        this.easyPlace = easyPlace;
        this.directPaste = directPaste;
        this.structures = structures;
        this.blockEntityRequest = blockEntityRequest;
        this.entityRequest = entityRequest;
        this.bulkNbtRequest = bulkNbtRequest;
    }

    // ------------------------------------------------------------- entrypoints

    /**
     * Called by {@link ServuxBridge} for every {@code servux:litematics}
     * payload. Branches on the leading {@code VarInt(packetType)}:
     *
     * <ul>
     *   <li>Type 2 / 3 / 4 / 7 → handshake + simple BE/Entity/bulk requests.</li>
     *   <li>Type 13 → outer-splitter slice for Direct Paste. Type 12
     *       ({@code _NBT_STREAM_START}) does not appear on the wire
     *       (internal Servux marker) — see
     *       {@link fr.ekaii.litematica.protocol.handler.DirectPasteHandler}.</li>
     * </ul>
     */
    public void onLitematicsPacket(String channel, Player player, byte[] payload) {
        int type = -1;
        try {
            ProtocolBuffer.Reader r = new ProtocolBuffer.Reader(payload);
            type = r.readVarInt();
            logInbound(channel, player, payload, type,
                    type == ProtocolConstants.Litematics.C2S_NBT_STREAM_DATA
                            || type == ProtocolConstants.Litematics.C2S_NBT_STREAM_START);
            if (!checkBaseGate(player)) {
                LOG.info("[diag-net] RX " + channel + " dropped (perm gate) for " + player.getName());
                return;
            }
            switch (type) {
                case ProtocolConstants.Litematics.C2S_METADATA_REQUEST ->
                    metadata.onMetadataRequest(player, r);
                case ProtocolConstants.Litematics.C2S_BLOCK_ENTITY_REQUEST ->
                    blockEntityRequest.onRequest(player, r);
                case ProtocolConstants.Litematics.C2S_ENTITY_REQUEST ->
                    entityRequest.onRequest(player, r);
                case ProtocolConstants.Litematics.C2S_BULK_NBT_REQUEST ->
                    bulkNbtRequest.onRequest(player, r);
                case ProtocolConstants.Litematics.C2S_NBT_STREAM_DATA ->
                    directPaste.onSplitterSlice(player, r);
                case ProtocolConstants.Litematics.C2S_NBT_STREAM_START ->
                    // Servux never emits this on the wire; if a client
                    // does, treat it as the first slice of a stream so we
                    // are more tolerant than the upstream receiver.
                    directPaste.onSplitterSlice(player, r);
                default -> LOG.warning("unknown servux:litematics packet type " + type
                        + " from " + player.getName());
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "decode servux:litematics (type=" + type + ", len="
                    + payload.length + ") for " + player.getName(), t);
        }
    }

    public void onStructuresPacket(String channel, Player player, byte[] payload) {
        int type = -1;
        try {
            ProtocolBuffer.Reader r = new ProtocolBuffer.Reader(payload);
            type = r.readVarInt();
            logInbound(channel, player, payload, type, false);
            if (!checkBaseGate(player)) {
                LOG.info("[diag-net] RX " + channel + " dropped (perm gate) for " + player.getName());
                return;
            }
            switch (type) {
                case ProtocolConstants.Structures.C2S_REGISTER ->
                    structures.onRegister(player, r);
                case ProtocolConstants.Structures.C2S_UNREGISTER ->
                    structures.onUnregister(player, r);
                case ProtocolConstants.Structures.C2S_REQUEST_SPAWN_METADATA ->
                    structures.onSpawnMetadataRequest(player, r);
                default -> LOG.warning("unknown servux:structures packet type " + type);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "decode servux:structures (type=" + type + ", len="
                    + payload.length + ") for " + player.getName(), t);
        }
    }

    /**
     * One-line INFO diagnostic per inbound C2S packet, gated on
     * {@code protocol.logInbound} (default true). Direct-paste stream
     * slices are downsampled — first slice then every 128th — so a large
     * upload does not flood the log; the per-player slice counter resets
     * whenever a non-slice packet arrives. {@code protocol.logInboundHex}
     * (default false) appends a 96-byte hex dump for deep debugging.
     */
    private void logInbound(String channel, Player player, byte[] payload, int type, boolean slice) {
        if (!plugin.getConfig().getBoolean("protocol.logInbound", true)) return;
        long n = 0;
        if (slice) {
            n = sliceCounters.merge(player.getUniqueId(), 1L, Long::sum);
            if (n != 1 && n % 128 != 0) return;
        } else {
            sliceCounters.remove(player.getUniqueId());
        }
        StringBuilder sb = new StringBuilder(96)
                .append("[diag-net] RX ").append(channel)
                .append(" type=").append(type)
                .append(" len=").append(payload.length)
                .append(" from ").append(player.getName());
        if (slice) sb.append(" (slice #").append(n).append(')');
        if (plugin.getConfig().getBoolean("protocol.logInboundHex", false)) {
            sb.append(" hex=").append(hexDump(payload, 96));
        }
        LOG.info(sb.toString());
    }

    /** Per-player Direct-Paste slice counters for {@link #logInbound}. */
    private final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long> sliceCounters =
            new java.util.concurrent.ConcurrentHashMap<>();

    public void onEasyPlacePacket(String channel, Player player, byte[] payload) {
        if (!checkBaseGate(player)) return;
        if (!player.hasPermission(PERM_EASYPLACE) && !player.isOp()) {
            LOG.fine("easyplace denied: " + player.getName() + " lacks " + PERM_EASYPLACE);
            return;
        }
        try {
            ProtocolBuffer.Reader r = new ProtocolBuffer.Reader(payload);
            int type = r.readVarInt();
            if (type == ProtocolConstants.EasyPlace.C2S_PLACE_REQUEST) {
                easyPlace.onPlaceRequest(player, r);
            } else {
                LOG.warning("unknown easy_place packet type " + type);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "decode easy_place for " + player.getName(), t);
        }
    }

    // ------------------------------------------------- shared encode helpers

    /**
     * Builds a Litematics-channel payload by writing the type VarInt then
     * delegating to {@code body} to fill the rest.
     */
    public static byte[] buildLitematics(int type, ByteWriter body) throws Exception {
        ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
        w.writeVarInt(type);
        body.write(w);
        return w.toByteArray();
    }

    /** Same for the structures channel. */
    public static byte[] buildStructures(int type, ByteWriter body) throws Exception {
        ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
        w.writeVarInt(type);
        body.write(w);
        return w.toByteArray();
    }

    /** Same for the easy-place channel. */
    public static byte[] buildEasyPlace(int type, ByteWriter body) throws Exception {
        ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
        w.writeVarInt(type);
        body.write(w);
        return w.toByteArray();
    }

    @FunctionalInterface
    public interface ByteWriter {
        void write(ProtocolBuffer.Writer w) throws Exception;
    }

    // ---------------------------------------------------- permission gating

    private boolean checkBaseGate(Player player) {
        if (player == null) return false;
        if (player.isOp()) return true;
        if (player.hasPermission(PERM_USE)) return true;
        return false;
    }

    /** Hex-dump the first {@code max} bytes of {@code data} for [diag-net] logs. */
    private static String hexDump(byte[] data, int max) {
        if (data == null || data.length == 0) return "<empty>";
        int n = Math.min(data.length, max);
        StringBuilder sb = new StringBuilder(n * 3 + 8);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", data[i] & 0xFF));
        }
        if (data.length > max) sb.append(" …(+").append(data.length - max).append("B)");
        return sb.toString();
    }
}
