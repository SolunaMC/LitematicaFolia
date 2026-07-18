package fr.ekaii.litematica.protocol;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reclaims Servux plugin-message channels from a Leaves-derived server
 * base (Leaves, Lophine, ...).
 *
 * <h2>Why this exists</h2>
 * Those bases ship their own server-side Servux implementation wired
 * through a "Leaves protocol core". {@code LeavesProtocolManager.init()}
 * scans {@code org.leavesmc.leaves.protocol.*} at boot and
 * <b>unconditionally</b> registers a {@code StreamCodec} for every
 * {@code LeavesCustomPayload} identifier — including
 * {@code servux:litematics} and {@code servux:structures} — regardless of
 * the {@code function.protocol.servux} config gates
 * ({@code litematics-enabled = false} does NOT prevent registration; it
 * only gates the handler via {@code LeavesProtocol.isActive()}).
 *
 * <p>From then on, every inbound {@code ServerboundCustomPayloadPacket}
 * on those identifiers decodes into a {@code LeavesCustomPayload} instead
 * of a {@code DiscardedPayload}, and the patched
 * {@code ServerCommonPacketListenerImpl.handleCustomPayload()} consumes
 * it and returns <b>before</b> Paper's
 * {@code Messenger.dispatchIncomingMessage(...)} call. When the base
 * module is config-disabled, {@code AbstractInvokerHolder.invoke0()}
 * additionally drops the decoded payload silently (no log). Net effect:
 * this plugin's registered incoming plugin channels never fire, and a
 * Litematica client hangs forever on "uploading to Servux for pasting".
 *
 * <h2>The fix</h2>
 * Evict our channel identifiers from the manager's private
 * {@code ID2CODEC} map, so {@code LeavesProtocolManager.decode()} returns
 * {@code null} again. The payload then falls back to the vanilla
 * {@code DiscardedPayload} codec and Paper's normal path delivers it to
 * the Bukkit {@link org.bukkit.plugin.messaging.Messenger} — i.e. to this
 * plugin's {@link PacketHandler}. On bases without the Leaves protocol
 * core (Paper, Folia, Luminol) this is a clean no-op.
 *
 * <p>Note: after eviction the <i>base's</i> Servux litematics/structures
 * modules can no longer receive C2S packets even if an admin later flips
 * {@code litematics-enabled = true} — this plugin owns the channels for
 * the lifetime of the process. That is intentional: running both at once
 * would double-paste.
 *
 * <p>Thread-safety: {@code ID2CODEC} is a plain {@code HashMap} read from
 * Netty decode threads. We mutate it during plugin enable — at server
 * boot no play-phase connection exists yet, so there is no concurrent
 * reader. (A {@code /reload} with players online is a theoretical benign
 * race on a bucket read; removal never resizes the table.)
 *
 * <p>Everything is reflective so this class links on any server
 * implementation.
 */
final class LeavesChannelReclaim {

    /** Outcome of a reclaim attempt, for the caller's logging/telemetry. */
    enum Result {
        /** No Leaves protocol core on this base — nothing to reclaim. */
        NO_LEAVES_CORE,
        /** Leaves core present; our channels are now (or already were) unclaimed. */
        RECLAIMED,
        /** Leaves core present but eviction failed — C2S will be swallowed. */
        FAILED
    }

    private LeavesChannelReclaim() {
    }

    /**
     * Evicts {@code channels} from the base's Leaves protocol manager and
     * verifies each one now falls through the payload decoder.
     */
    static Result reclaim(Logger log, String... channels) {
        final Class<?> manager;
        try {
            manager = Class.forName("org.leavesmc.leaves.protocol.core.LeavesProtocolManager");
        } catch (ClassNotFoundException absent) {
            log.fine("[reclaim] no Leaves protocol core on this base — channel reclaim not needed");
            return Result.NO_LEAVES_CORE;
        }

        try {
            Class<?> idCls = resolveIdentifierClass();
            Method parse = idCls.getMethod("tryParse", String.class);

            Field mapField = manager.getDeclaredField("ID2CODEC");
            mapField.setAccessible(true);
            Map<?, ?> id2codec = (Map<?, ?>) mapField.get(null);

            // public static LeavesCustomPayload decode(Identifier, FriendlyByteBuf)
            // — with an unknown identifier it returns null without touching
            // the buffer, so invoking it with a null buf is a safe probe.
            Method decode = null;
            for (Method m : manager.getMethods()) {
                if (m.getName().equals("decode") && m.getParameterCount() == 2
                        && m.getParameterTypes()[0].equals(idCls)) {
                    decode = m;
                    break;
                }
            }

            boolean allClear = true;
            for (String channel : channels) {
                Object key = parse.invoke(null, channel);
                if (key == null) {
                    log.warning("[reclaim] cannot parse channel identifier '" + channel + "'");
                    allClear = false;
                    continue;
                }
                boolean evicted = id2codec.remove(key) != null;
                Object leftover = decode == null ? null : decode.invoke(null, key, null);
                if (leftover != null) {
                    // Non-null (incl. the manager's INVALID_PAYLOAD sentinel)
                    // means the base would still claim the channel.
                    log.warning("[reclaim] base still decodes " + channel
                            + " after eviction — C2S packets will be swallowed by the server base");
                    allClear = false;
                    continue;
                }
                if (evicted) {
                    log.info("[reclaim] evicted base Servux codec for " + channel
                            + " — C2S payloads now fall through to this plugin (verified: base decode -> null)");
                } else {
                    log.info("[reclaim] " + channel + " was not claimed by the base — nothing to evict");
                }
            }
            return allClear ? Result.RECLAIMED : Result.FAILED;
        } catch (Throwable t) {
            log.log(Level.WARNING, "[reclaim] Leaves protocol core detected but channel eviction failed — "
                    + "the base will swallow servux C2S packets (paste will hang). "
                    + "Either update this plugin's reclaim logic for the new base, or remove the base's "
                    + "servux litematics protocol.", t);
            return Result.FAILED;
        }
    }

    private static Class<?> resolveIdentifierClass() throws ClassNotFoundException {
        try {
            return Class.forName("net.minecraft.resources.Identifier"); // 26.2+
        } catch (ClassNotFoundException e) {
            return Class.forName("net.minecraft.resources.ResourceLocation"); // older mojmap
        }
    }
}
