package fr.ekaii.litematica.protocol.handler;

import fr.ekaii.litematica.core.LitematicNbt;
import fr.ekaii.litematica.nms.NmsBridge;
import fr.ekaii.litematica.paste.FoliaCompat;
import fr.ekaii.litematica.protocol.DataTagCodec;
import fr.ekaii.litematica.protocol.PacketHandler;
import fr.ekaii.litematica.protocol.PacketSplitter;
import fr.ekaii.litematica.protocol.ProtocolBuffer;
import fr.ekaii.litematica.protocol.ProtocolConstants;
import fr.ekaii.litematica.protocol.ProtocolSessions;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles {@code C2S_BULK_NBT_REQUEST} (type 7) on the
 * {@code servux:litematics} channel.
 *
 * <p>Wire format:
 * <pre>
 *   VarInt(packetType = 7)
 *   ChunkPos (packed long, low=x high=z)
 *   NBT (request body — freeform per upstream)
 * </pre>
 *
 * <p>The request body's exact schema is "TBD" per upstream
 * (see {@link fr.ekaii.litematica.protocol.ProtocolConstants}). The
 * usual contract is: dump every BE + entity in the requested chunk and
 * stream the answer back as one big NBT compound carrying:
 * <pre>
 *   compound BulkReply {
 *     int ChunkX
 *     int ChunkZ
 *     list BlockEntities  // list of compound { x, y, z, NBT }
 *     list Entities       // list of compound { entityId, NBT }
 *   }
 * </pre>
 *
 * <p>The reply is wrapped as application payload
 * {@code (VarInt transactionId + NBT(reply))} and chunked through
 * {@link PacketSplitter#split(byte[], int)} so each wire packet stays
 * within Paper's 32 KiB plugin-message budget. The first slice carries
 * a {@code VarInt(totalLen)} header (the splitter's job); subsequent
 * slices are raw bytes. All slices are wrapped as
 * {@code S2C_NBT_STREAM_DATA (type 11)}.
 *
 * <h2>Folia safety</h2>
 *
 * <p>BlockEntity enumeration must run on the chunk's owning region.
 * Entities are iterated from the same hop (Folia exposes entities in
 * the chunk via {@link Chunk#getEntities()} which is region-safe).
 *
 * <h2>Permissions / rate limit</h2>
 *
 * <p>Same {@code litematica.protocol.use} gate as the simple replies.
 * A bulk request counts as one "operation" for rate-limiting purposes
 * (we don't want a single bulk request to eat the 64/32 budget for
 * BE/entity requests — the budgets are separate per handler).
 */
public final class BulkNbtRequestHandler {

    private static final Logger LOG = Logger.getLogger("LitematicaFolia/BulkNbtRequestHandler");

    /** Bulk requests are coarser than per-BE/per-entity, so the cap is lower. */
    public static final int DEFAULT_CAPACITY = 8;
    public static final double DEFAULT_REFILL = 4.0;

    /**
     * Max slice size for the outer splitter. Paper allows ~32 KiB per
     * plugin message; we leave headroom for the type VarInt + payload
     * VarInt prefix.
     */
    public static final int SLICE_BUDGET = 16 * 1024;

    private final Plugin plugin;
    private final NmsBridge nms;
    private final RateLimiter limiter;
    private final ProtocolSessions sessions;

    public BulkNbtRequestHandler(Plugin plugin, NmsBridge nms, ProtocolSessions sessions) {
        this(plugin, nms, sessions, new RateLimiter(DEFAULT_CAPACITY, DEFAULT_REFILL));
    }

    public BulkNbtRequestHandler(Plugin plugin, NmsBridge nms, ProtocolSessions sessions,
                                 RateLimiter limiter) {
        this.plugin = plugin;
        this.nms = nms;
        this.sessions = sessions;
        this.limiter = limiter;
    }

    public RateLimiter limiter() {
        return limiter;
    }

    public void onRequest(Player player, ProtocolBuffer.Reader r) throws Exception {
        int[] cp;
        try {
            cp = r.readChunkPos();
        } catch (Throwable t) {
            LOG.warning("bulk request: bad chunkpos from " + player.getName());
            return;
        }
        // Body: vanilla network NBT on wire v1, Data Tag blob on wire v2.
        // Sniff on the exact Int32 length framing so unknown sessions
        // still decode; the body only carries Task/minY/maxY hints.
        final byte[] bodyBytes = r.readRemainingBytes();
        final boolean v2;
        if (sessions.isKnown(player.getUniqueId())) {
            v2 = sessions.isV2(player.getUniqueId());
        } else {
            v2 = DataTagCodec.looksLikeDataTagBlob(bodyBytes);
        }
        try {
            if (v2) {
                DataTagCodec.decode(bodyBytes);
            } else {
                new ProtocolBuffer.Reader(bodyBytes).readNbt();
            }
        } catch (Throwable ignored) {
            // Body hints are optional; keep serving the chunk dump.
        }

        if (!limiter.tryAcquire(player.getUniqueId())) {
            LOG.fine("bulk request rate-limited for " + player.getName()
                    + " chunk=" + cp[0] + "," + cp[1]);
            sendErrorReply(player, cp[0], cp[1], "rate_limited", v2);
            return;
        }

        final int cx = cp[0];
        final int cz = cp[1];
        final World world = player.getWorld();

        FoliaCompat.runOnRegion(plugin, world, cx, cz, () -> {
            try {
                LitematicNbt.NbtCompound reply = v2
                        ? buildBulkReplyV2(world, cx, cz)
                        : buildBulkReply(world, cx, cz);
                streamReply(player, reply, v2);
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "bulk request gather failed for "
                        + player.getName() + " chunk=" + cx + "," + cz, t);
                try { sendErrorReply(player, cx, cz, t.getClass().getSimpleName(), v2); }
                catch (Throwable ignored) {}
            }
        }).whenComplete((unused, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "region hop failed for bulk request chunk="
                        + cx + "," + cz, err);
            }
        });
    }

    /**
     * Gather all BE + entity NBT for the chunk {@code (cx, cz)}.
     * Must run on the chunk's owning region.
     */
    LitematicNbt.NbtCompound buildBulkReply(World world, int cx, int cz) {
        LitematicNbt.NbtCompound reply = new LitematicNbt.NbtCompound();
        reply.putString("Task", "BulkNbtReply");
        reply.putInt("ChunkX", cx);
        reply.putInt("ChunkZ", cz);

        List<LitematicNbt.NbtTag> beList = new ArrayList<>();
        List<LitematicNbt.NbtTag> entList = new ArrayList<>();

        try {
            Chunk chunk = world.getChunkAt(cx, cz);
            for (BlockState be : chunk.getTileEntities()) {
                int x = be.getX(), y = be.getY(), z = be.getZ();
                LitematicNbt.NbtTag nbt = null;
                try {
                    nbt = nms.extractTileEntityNbt(world, x, y, z);
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "bulk: BE extract failed at " + x + "," + y + "," + z, t);
                }
                LitematicNbt.NbtCompound entry = new LitematicNbt.NbtCompound();
                entry.putInt("x", x);
                entry.putInt("y", y);
                entry.putInt("z", z);
                if (nbt instanceof LitematicNbt.NbtCompound c) {
                    entry.put("NBT", c);
                }
                beList.add(entry);
            }
            for (Entity e : chunk.getEntities()) {
                LitematicNbt.NbtTag nbt = null;
                try {
                    nbt = nms.extractEntityNbt(e);
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "bulk: entity extract failed eid=" + e.getEntityId(), t);
                }
                LitematicNbt.NbtCompound entry = new LitematicNbt.NbtCompound();
                entry.putInt("entityId", e.getEntityId());
                entry.putString("type", e.getType().name());
                if (nbt instanceof LitematicNbt.NbtCompound c) {
                    entry.put("NBT", c);
                }
                entList.add(entry);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "bulk: chunk enumeration failed at " + cx + "," + cz, t);
        }

        reply.put("BlockEntities", new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, beList));
        reply.put("Entities", new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, entList));
        return reply;
    }

    /**
     * Wire-v2 bulk reply, following the upstream 26.2-0.11.3 schema the
     * 0.28.5 client parses ({@code EntityDataManager.handleBulkEntityData}):
     * {@code {Task:"BulkEntityReply", TileEntities: List<BE tag>,
     * Entities: List<entity tag + entityId>, chunkX, chunkZ}}. BE tags
     * embed their own x/y/z/id; entity tags carry an {@code entityId}
     * int and an {@code id} type string.
     */
    LitematicNbt.NbtCompound buildBulkReplyV2(World world, int cx, int cz) {
        LitematicNbt.NbtCompound reply = new LitematicNbt.NbtCompound();
        reply.putString("Task", "BulkEntityReply");
        reply.putInt("chunkX", cx);
        reply.putInt("chunkZ", cz);

        List<LitematicNbt.NbtTag> beList = new ArrayList<>();
        List<LitematicNbt.NbtTag> entList = new ArrayList<>();

        try {
            Chunk chunk = world.getChunkAt(cx, cz);
            for (BlockState be : chunk.getTileEntities()) {
                try {
                    LitematicNbt.NbtTag nbt = nms.extractTileEntityNbt(
                            world, be.getX(), be.getY(), be.getZ());
                    if (nbt instanceof LitematicNbt.NbtCompound c) {
                        beList.add(c);
                    }
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "bulk v2: BE extract failed at "
                            + be.getX() + "," + be.getY() + "," + be.getZ(), t);
                }
            }
            for (Entity e : chunk.getEntities()) {
                if (e instanceof Player) {
                    continue;
                }
                try {
                    LitematicNbt.NbtTag nbt = nms.extractEntityNbt(e);
                    if (nbt instanceof LitematicNbt.NbtCompound c) {
                        c.putInt("entityId", e.getEntityId());
                        if (c.getString("id") == null) {
                            c.putString("id", e.getType().getKey().toString());
                        }
                        entList.add(c);
                    }
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "bulk v2: entity extract failed eid=" + e.getEntityId(), t);
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "bulk v2: chunk enumeration failed at " + cx + "," + cz, t);
        }

        reply.put("TileEntities", new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, beList));
        reply.put("Entities", new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, entList));
        return reply;
    }

    /**
     * Encode the reply as the era-appropriate application payload, chunk
     * it through the splitter, and ship each slice as
     * {@code S2C_NBT_STREAM_DATA (11)}.
     */
    void streamReply(Player player, LitematicNbt.NbtCompound reply, boolean v2) throws Exception {
        byte[] payload = v2 ? DataTagCodec.encode(reply) : encodeApplicationPayload(reply);
        byte[][] slices = PacketSplitter.split(payload, SLICE_BUDGET);
        for (byte[] slice : slices) {
            byte[] wire = PacketHandler.buildLitematics(
                    ProtocolConstants.Litematics.S2C_NBT_STREAM_DATA,
                    w -> w.writeRawBytes(slice));
            player.sendPluginMessage(plugin, ProtocolConstants.CHANNEL_LITEMATICS, wire);
        }
    }

    /**
     * Build the v1 application payload Servux expected after reassembly:
     * {@code VarInt(transactionId) + NBT(reply)}. transactionId is
     * legacy / ignored — we emit {@code -1} like Servux did. Wire v2
     * replaced this with a bare Data Tag blob (no transactionId).
     */
    static byte[] encodeApplicationPayload(LitematicNbt.NbtCompound reply) throws java.io.IOException {
        ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
        w.writeVarInt(-1);     // legacy transactionId
        w.writeNbt(reply);
        return w.toByteArray();
    }

    private void sendErrorReply(Player player, int cx, int cz, String reason, boolean v2) throws Exception {
        LitematicNbt.NbtCompound err = new LitematicNbt.NbtCompound();
        err.putString("Task", v2 ? "BulkEntityReply" : "BulkNbtReply");
        err.putInt("ChunkX", cx);
        err.putInt("ChunkZ", cz);
        err.putInt("chunkX", cx);
        err.putInt("chunkZ", cz);
        err.putString("error", reason);
        streamReply(player, err, v2);
    }
}
