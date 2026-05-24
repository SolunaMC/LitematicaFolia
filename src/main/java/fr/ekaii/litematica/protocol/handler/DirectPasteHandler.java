package fr.ekaii.litematica.protocol.handler;

import fr.ekaii.litematica.core.LitematicNbt;
import fr.ekaii.litematica.core.LitematicReader;
import fr.ekaii.litematica.core.LitematicSchematic;
import fr.ekaii.litematica.paste.PasteOperation;
import fr.ekaii.litematica.paste.PasteOptions;
import fr.ekaii.litematica.paste.PasteResult;
import fr.ekaii.litematica.protocol.PacketHandler;
import fr.ekaii.litematica.protocol.ProtocolBuffer;
import fr.ekaii.litematica.protocol.ProtocolConstants;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Receives a {@code .litematic} payload streamed in chunks from a Servux
 * client, reassembles it, and runs it through the standard
 * {@link PasteOperation} pipeline.
 *
 * <h2>Wire format</h2>
 * <ol>
 *   <li>C2S {@link ProtocolConstants.Litematics#C2S_NBT_STREAM_START} —
 *       carries a small NBT compound describing the upcoming stream
 *       (transaction id, target world, origin x/y/z, paste options).
 *       Servux uses the {@code Task} field with values like
 *       {@code "Litematic-TransmitStart"} / {@code "Litematic-TransmitData"} /
 *       {@code "Litematic-TransmitEnd"}.</li>
 *   <li>C2S {@link ProtocolConstants.Litematics#C2S_NBT_STREAM_DATA} —
 *       body slices. The first slice writes a VarInt of the total
 *       payload length, then raw bytes; subsequent slices are raw bytes
 *       continuing from where the previous slice ended. The reassembled
 *       payload is itself a VarInt(transactionType) + NBT(compound).</li>
 * </ol>
 *
 * <h2>Reuse of PasteOperation</h2>
 * On stream completion we instantiate a {@link LitematicSchematic} via
 * {@link LitematicReader#fromCompound}, build a {@link PasteOptions}
 * from defaults + per-request overrides, and call
 * {@link PasteOperation#execute()}. The handler does not duplicate any
 * placement logic.
 */
public final class DirectPasteHandler {

    private static final Logger LOG = Logger.getLogger("LitematicaFolia/DirectPasteHandler");

    private final Plugin plugin;

    /** In-progress streams keyed by player UUID. */
    private final Map<UUID, StreamSession> sessions = new HashMap<>();

    public DirectPasteHandler(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Common entrypoint for both STREAM_START and STREAM_DATA frames. */
    public void onStreamFrame(Player player, int packetType, ProtocolBuffer.Reader r) throws Exception {
        if (!player.hasPermission(PacketHandler.PERM_PASTE) && !player.isOp()) {
            LOG.fine("paste denied: " + player.getName() + " lacks " + PacketHandler.PERM_PASTE);
            return;
        }

        UUID uid = player.getUniqueId();
        if (packetType == ProtocolConstants.Litematics.C2S_NBT_STREAM_START) {
            LitematicNbt.NbtCompound header;
            try {
                header = r.readNbt();
            } catch (Throwable t) {
                LOG.warning("STREAM_START: bad NBT header — " + t.getMessage());
                return;
            }
            sessions.put(uid, new StreamSession(uid, header));
            return;
        }

        // STREAM_DATA
        StreamSession session = sessions.get(uid);
        if (session == null) {
            LOG.warning("STREAM_DATA without START for " + player.getName());
            return;
        }
        byte[] slice = r.readRemainingBytes();
        boolean done = session.appendSlice(slice);
        if (done) {
            sessions.remove(uid);
            handleAssembled(player, session);
        }
    }

    private void handleAssembled(Player player, StreamSession session) {
        try {
            byte[] full = session.assembled();
            // The reassembled payload is (VarInt transactionType) + (NBT compound).
            ProtocolBuffer.Reader r = new ProtocolBuffer.Reader(full);
            int transactionType;
            try {
                transactionType = r.readVarInt();
            } catch (Throwable t) {
                LOG.warning("paste payload missing transactionType");
                return;
            }
            LitematicNbt.NbtCompound payload = r.readNbt();
            if (payload == null) {
                LOG.warning("paste payload missing root compound");
                return;
            }

            // The schematic NBT is shipped as a sub-compound. Servux uses
            // the "Schematic" key for the full litematic root (per
            // LitematicaSchematic.receiveFileTransmit conventions).
            LitematicNbt.NbtCompound schemRoot = payload.getCompound("Schematic");
            if (schemRoot == null) {
                LOG.warning("paste payload has no 'Schematic' compound — type=" + transactionType);
                return;
            }
            LitematicSchematic schem = LitematicReader.fromCompound(schemRoot);

            Location origin = readOrigin(payload, player);
            PasteOptions opts = readOptions(payload, origin);
            new PasteOperation(plugin, schem, opts).execute()
                    .whenComplete((res, err) -> reportComplete(player, res, err));
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "direct paste assembly failed", t);
        }
    }

    private void reportComplete(Player player, PasteResult res, Throwable err) {
        try {
            if (err != null) {
                LOG.log(Level.WARNING, "direct paste failed for " + player.getName(), err);
                player.sendMessage("[LitematicaFolia] paste failed: " + err.getClass().getSimpleName());
                return;
            }
            String msg = "[LitematicaFolia] pasted " + res.blocksPlaced()
                    + " blocks in " + res.durationMs() + " ms";
            if (!res.errors().isEmpty()) {
                msg += " (" + res.errors().size() + " errors)";
            }
            player.sendMessage(msg);
        } catch (Throwable ignored) {
        }
    }

    private Location readOrigin(LitematicNbt.NbtCompound payload, Player player) {
        Integer x = payload.getInt("OriginX");
        Integer y = payload.getInt("OriginY");
        Integer z = payload.getInt("OriginZ");
        if (x == null || y == null || z == null) {
            // Fallback: paste at the player's feet.
            return player.getLocation().clone();
        }
        return new Location(player.getWorld(), x, y, z);
    }

    private PasteOptions readOptions(LitematicNbt.NbtCompound payload, Location origin) {
        PasteOptions defaults = PasteOptions.defaults(origin);
        Byte placeEntities      = payload.getByte("PlaceEntities");
        Byte placeTileEntities  = payload.getByte("PlaceTileEntities");
        Byte placePendingTicks  = payload.getByte("PlacePendingTicks");
        Byte deferredPhysics    = payload.getByte("DeferredPhysics");
        Byte observersLast      = payload.getByte("ObserversLast");
        Integer yawRotation     = payload.getInt("YawRotation");

        return new PasteOptions(
                origin,
                placeEntities      == null ? defaults.placeEntities()      : placeEntities      != 0,
                placeTileEntities  == null ? defaults.placeTileEntities()  : placeTileEntities  != 0,
                placePendingTicks  == null ? defaults.placePendingTicks()  : placePendingTicks  != 0,
                deferredPhysics    == null ? defaults.deferredPhysics()    : deferredPhysics    != 0,
                observersLast      == null ? defaults.observersLast()      : observersLast      != 0,
                defaults.maxBlocksPerChunkTask(),
                yawRotation == null ? defaults.yawRotation() : (yawRotation % 360),
                null);
    }

    // ----------------------------------------------------------- stream state

    /**
     * Accumulates the slices of a single client → server splitter stream.
     * The first slice declares the total length as a leading VarInt.
     */
    private static final class StreamSession {
        final UUID player;
        final LitematicNbt.NbtCompound header;
        private final java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
        private int totalLength = -1;
        private final AtomicInteger received = new AtomicInteger();

        StreamSession(UUID player, LitematicNbt.NbtCompound header) {
            this.player = player;
            this.header = header == null ? new LitematicNbt.NbtCompound() : header;
        }

        /** @return true when the full stream has been received. */
        boolean appendSlice(byte[] slice) throws java.io.IOException {
            if (slice.length == 0) return totalLength >= 0 && received.get() >= totalLength;
            if (totalLength < 0) {
                // First slice: VarInt total length, then bytes.
                DataInputStream dis = new DataInputStream(new ByteArrayInputStream(slice));
                int len = 0;
                int shift = 0;
                int consumed = 0;
                int b;
                do {
                    b = dis.readByte() & 0xFF;
                    consumed++;
                    len |= (b & 0x7F) << shift;
                    shift += 7;
                    if (shift >= 35) throw new java.io.IOException("VarInt too long in stream");
                } while ((b & 0x80) != 0);
                totalLength = len;
                int bodyLen = slice.length - consumed;
                if (bodyLen > 0) {
                    sink.write(slice, consumed, bodyLen);
                    received.addAndGet(bodyLen);
                }
            } else {
                sink.write(slice, 0, slice.length);
                received.addAndGet(slice.length);
            }
            return totalLength >= 0 && received.get() >= totalLength;
        }

        byte[] assembled() {
            return sink.toByteArray();
        }
    }
}
