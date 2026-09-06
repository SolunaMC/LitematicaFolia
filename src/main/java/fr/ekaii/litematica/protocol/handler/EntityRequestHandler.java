package fr.ekaii.litematica.protocol.handler;

import fr.ekaii.litematica.core.LitematicNbt;
import fr.ekaii.litematica.nms.NmsBridge;
import fr.ekaii.litematica.paste.FoliaCompat;
import fr.ekaii.litematica.protocol.DataTagCodec;
import fr.ekaii.litematica.protocol.PacketHandler;
import fr.ekaii.litematica.protocol.ProtocolBuffer;
import fr.ekaii.litematica.protocol.ProtocolConstants;
import fr.ekaii.litematica.protocol.ProtocolSessions;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles {@code C2S_ENTITY_REQUEST} (type 4) on the
 * {@code servux:litematics} channel.
 *
 * <p>Servux wire format:
 * <pre>
 *   VarInt(packetType = 4)
 *   VarInt(transactionId)   // ignored (legacy)
 *   VarInt(entityId)        // vanilla EID (NOT UUID)
 * </pre>
 *
 * <p>Reply: {@code S2C_ENTITY_NBT_REPLY (type 6)} →
 * {@code VarInt(entityId) + NBT(entity tag or empty)}.
 *
 * <h2>Lookup</h2>
 *
 * <p>Bukkit doesn't expose a {@code getEntityById(int)} directly. We
 * scan {@code world.getEntities()} matching on
 * {@link Entity#getEntityId()}. This is O(N) in entities-in-world but
 * the per-player rate-limit (32/s) caps the cost, and Litematica
 * clients only fire this for entities they can already see.
 *
 * <h2>Folia safety</h2>
 *
 * <p>Each entity has its own region in Folia. We use
 * {@link FoliaCompat#runOnEntity(Plugin, Entity, Runnable)} to extract
 * NBT on the owning region thread.
 *
 * <p>Player entities cannot be serialised this way — the NMS bridge
 * returns {@code null}, which we forward as an empty NBT reply.
 */
public final class EntityRequestHandler {

    private static final Logger LOG = Logger.getLogger("LitematicaFolia/EntityRequestHandler");

    /** Token-bucket rate limiter (32 requests / second / player). */
    public static final int DEFAULT_CAPACITY = 32;
    public static final double DEFAULT_REFILL = 32.0;

    private final Plugin plugin;
    private final NmsBridge nms;
    private final RateLimiter limiter;
    private final ProtocolSessions sessions;

    public EntityRequestHandler(Plugin plugin, NmsBridge nms, ProtocolSessions sessions) {
        this(plugin, nms, sessions, new RateLimiter(DEFAULT_CAPACITY, DEFAULT_REFILL));
    }

    public EntityRequestHandler(Plugin plugin, NmsBridge nms, ProtocolSessions sessions,
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
        // Wire v1: VarInt(transactionId = always -1) + VarInt(entityId).
        // Wire v2 (0.28.5+): VarInt(entityId) only. Entity ids are never
        // negative, so a leading -1 marks the legacy prefix to drain.
        final int entityId;
        try {
            int first = r.readVarInt();
            entityId = (first == -1) ? r.readVarInt() : first;
        } catch (Throwable t) {
            LOG.warning("entity request: bad entityId from " + player.getName());
            return;
        }

        if (!limiter.tryAcquire(player.getUniqueId())) {
            LOG.fine("entity request rate-limited for " + player.getName() + " eid=" + entityId);
            sendReply(player, entityId, rateLimitedTag());
            return;
        }

        Entity match = findById(player, entityId);
        if (match == null) {
            // Send an empty reply so the client doesn't hang.
            try { sendReply(player, entityId, null); } catch (Throwable ignored) {}
            return;
        }

        final Entity entity = match;
        FoliaCompat.runOnEntity(plugin, entity, () -> {
            LitematicNbt.NbtCompound nbt = null;
            try {
                LitematicNbt.NbtTag tag = nms.extractEntityNbt(entity);
                if (tag instanceof LitematicNbt.NbtCompound c) {
                    nbt = c;
                }
            } catch (Throwable t) {
                LOG.log(Level.FINE, "extractEntityNbt failed for eid=" + entityId, t);
            }
            try {
                sendReply(player, entityId, nbt);
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "send entity reply failed for " + player.getName(), t);
            }
        }).whenComplete((unused, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "region hop failed for entity request eid=" + entityId, err);
                try { sendReply(player, entityId, null); } catch (Throwable ignored) {}
            }
        });
    }

    /**
     * Resolve a Bukkit {@link Entity} from a vanilla entity id by scanning
     * the player's current world. Bukkit doesn't expose
     * {@code World#getEntity(int)}.
     */
    private Entity findById(Player player, int entityId) {
        try {
            for (Entity e : player.getWorld().getEntities()) {
                if (e.getEntityId() == entityId) return e;
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "findById iteration failed", t);
        }
        return null;
    }

    private void sendReply(Player player, int entityId, LitematicNbt.NbtCompound nbt) throws Exception {
        LitematicNbt.NbtCompound body = nbt;
        final boolean v2 = sessions.isV2(player.getUniqueId());
        byte[] reply = PacketHandler.buildLitematics(
                ProtocolConstants.Litematics.S2C_ENTITY_NBT_REPLY,
                w -> {
                    w.writeVarInt(entityId);
                    if (v2) {
                        // Wire v2 replies carry a Data Tag blob.
                        w.writeRawBytes(DataTagCodec.encode(body));
                    } else {
                        w.writeNbt(body);
                    }
                });
        player.sendPluginMessage(plugin, ProtocolConstants.CHANNEL_LITEMATICS, reply);
    }

    private static LitematicNbt.NbtCompound rateLimitedTag() {
        LitematicNbt.NbtCompound c = new LitematicNbt.NbtCompound();
        c.putString("error", "rate_limited");
        return c;
    }
}
