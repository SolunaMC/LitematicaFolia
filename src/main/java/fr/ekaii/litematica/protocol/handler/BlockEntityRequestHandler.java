package fr.ekaii.litematica.protocol.handler;

import fr.ekaii.litematica.core.LitematicNbt;
import fr.ekaii.litematica.nms.NmsBridge;
import fr.ekaii.litematica.paste.FoliaCompat;
import fr.ekaii.litematica.protocol.DataTagCodec;
import fr.ekaii.litematica.protocol.PacketHandler;
import fr.ekaii.litematica.protocol.ProtocolBuffer;
import fr.ekaii.litematica.protocol.ProtocolConstants;
import fr.ekaii.litematica.protocol.ProtocolSessions;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles {@code C2S_BLOCK_ENTITY_REQUEST} (type 3) on the
 * {@code servux:litematics} channel.
 *
 * <p>Servux wire format:
 * <pre>
 *   VarInt(packetType = 3)
 *   VarInt(transactionId)   // ignored on both sides (legacy)
 *   BlockPos (packed long)  // position of the block-entity to fetch
 * </pre>
 *
 * <p>Reply: {@code S2C_BLOCK_NBT_REPLY (type 5)} →
 * {@code BlockPos + NBT(be tag or empty)}.
 *
 * <h2>Folia safety</h2>
 *
 * <p>BlockEntity access must happen on the chunk's owning region (Folia
 * v0.1.8 regionfile-corruption fix — see
 * {@link fr.ekaii.litematica.paste.FoliaCompat}). We hop onto the
 * region for the requested coords via
 * {@link FoliaCompat#runOnRegion(Plugin, org.bukkit.World, int, int, Runnable)}
 * and then reply on completion.
 *
 * <h2>Permissions</h2>
 *
 * <p>Already gated by {@link PacketHandler#PERM_USE} (top-level
 * {@code litematica.protocol.use}). We additionally rate-limit at 64
 * BlockEntity queries per second per player — see {@link RateLimiter}.
 * Excess requests are dropped with an empty NBT reply carrying an
 * {@code error="rate_limited"} tag so the client can distinguish
 * "no BE here" from "throttled".
 *
 * <h2>WorldGuard / claims</h2>
 *
 * <p>TODO (v0.4): subject the requested coords to WorldGuard / claim
 * checks unless {@code litematica.protocol.bypass_regions} is granted.
 * For now we trust the base permission gate.
 */
public final class BlockEntityRequestHandler {

    private static final Logger LOG = Logger.getLogger("LitematicaFolia/BlockEntityRequestHandler");

    /** Token-bucket rate limiter (64 requests / second / player). */
    public static final int DEFAULT_CAPACITY = 64;
    public static final double DEFAULT_REFILL = 64.0;

    private final Plugin plugin;
    private final NmsBridge nms;
    private final RateLimiter limiter;
    private final ProtocolSessions sessions;

    public BlockEntityRequestHandler(Plugin plugin, NmsBridge nms, ProtocolSessions sessions) {
        this(plugin, nms, sessions, new RateLimiter(DEFAULT_CAPACITY, DEFAULT_REFILL));
    }

    public BlockEntityRequestHandler(Plugin plugin, NmsBridge nms, ProtocolSessions sessions,
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
        // Wire v1 prefixes a legacy transactionId VarInt (always -1, five
        // bytes) before the packed BlockPos; wire v2 (0.28.5+) dropped it.
        // The body length disambiguates unambiguously: 8 bytes = v2 (pos
        // only), anything longer starts with the VarInt to drain.
        if (r.remaining() > 8) {
            try { r.readVarInt(); } catch (Throwable ignored) {}
        }

        int[] pos;
        try {
            pos = r.readBlockPos();
        } catch (Throwable t) {
            LOG.warning("BE request: bad blockpos from " + player.getName());
            return;
        }

        if (!limiter.tryAcquire(player.getUniqueId())) {
            LOG.fine("BE request rate-limited for " + player.getName()
                    + " at " + pos[0] + "," + pos[1] + "," + pos[2]);
            sendReply(player, pos[0], pos[1], pos[2], rateLimitedTag());
            return;
        }

        final int x = pos[0], y = pos[1], z = pos[2];
        final int cx = x >> 4;
        final int cz = z >> 4;

        FoliaCompat.runOnRegion(plugin, player.getWorld(), cx, cz, () -> {
            LitematicNbt.NbtCompound nbt = null;
            try {
                LitematicNbt.NbtTag tag = nms.extractTileEntityNbt(player.getWorld(), x, y, z);
                if (tag instanceof LitematicNbt.NbtCompound c) {
                    nbt = c;
                }
            } catch (Throwable t) {
                LOG.log(Level.FINE, "extractTileEntityNbt failed at " + x + "," + y + "," + z, t);
            }
            try {
                sendReply(player, x, y, z, nbt);
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "send BE reply failed for " + player.getName(), t);
            }
        }).whenComplete((unused, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "region hop failed for BE request " + x + "," + y + "," + z, err);
                try {
                    sendReply(player, x, y, z, null);
                } catch (Throwable ignored) {}
            }
        });
    }

    private void sendReply(Player player, int x, int y, int z, LitematicNbt.NbtCompound nbt) throws Exception {
        LitematicNbt.NbtCompound body = nbt; // may be null → empty NBT / empty Data Tag
        final boolean v2 = sessions.isV2(player.getUniqueId());
        byte[] reply = PacketHandler.buildLitematics(
                ProtocolConstants.Litematics.S2C_BLOCK_NBT_REPLY,
                w -> {
                    w.writeBlockPos(x, y, z);
                    if (v2) {
                        // Wire v2 replies carry a Data Tag blob.
                        w.writeRawBytes(DataTagCodec.encode(body));
                    } else {
                        w.writeNbt(body);
                    }
                });
        player.sendPluginMessage(plugin, ProtocolConstants.CHANNEL_LITEMATICS, reply);
    }

    /** Marker compound returned when a request is throttled. */
    private static LitematicNbt.NbtCompound rateLimitedTag() {
        LitematicNbt.NbtCompound c = new LitematicNbt.NbtCompound();
        c.putString("error", "rate_limited");
        return c;
    }
}
