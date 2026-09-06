package fr.ekaii.litematica.protocol.easyplace;

import fr.ekaii.litematica.paste.FoliaCompat;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.HandlerNames;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server-side <strong>Litematica Easy Place V3</strong> without PacketEvents
 * or mixins, on Paper / Folia 26.2.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>On join (and for players already online when the feature is
 *       enabled) an {@link EasyPlaceChannelHandler} is added to the
 *       player's Netty pipeline before {@code packet_handler}. It rewrites
 *       V3-encoded {@code ServerboundUseItemOnPacket}s so vanilla accepts
 *       them, and memoises the protocol value here under
 *       {@code (player, clicked block)}.</li>
 *   <li>Vanilla performs the placement on the player's region thread and
 *       CraftBukkit fires {@link BlockPlaceEvent} while the block is in
 *       the world but before neighbour updates and the client
 *       notification. At {@link EventPriority#HIGHEST} (after protection
 *       plugins had their say) the memo is consumed, the corrected state
 *       is computed by {@link EasyPlaceStateResolver} from the placed
 *       state, and written without physics. CraftBukkit's post-event
 *       step then runs {@code onPlace}, neighbour updates and the block
 *       update packet once, on the corrected state. A cancelled event
 *       reverts the whole placement, override included.</li>
 * </ol>
 *
 * <h2>Threads</h2>
 * The Netty handler only writes to a concurrent map. World access happens
 * on the thread that fired {@code BlockPlaceEvent}, which on Folia is the
 * region owning both the player and the block (placement range is far
 * below the region merge radius); this is asserted with
 * {@link Bukkit#isOwnedByCurrentRegion(Block)} and falls back to the
 * region scheduler otherwise.
 *
 * <h2>Permission</h2>
 * {@code litematica.easyplace.use} (default op) gates the state override,
 * checked on the region thread. Players without it get plain vanilla
 * placement for a V3 click (the cursor rewrite is unconditional so the
 * click is not silently dropped by the range check).
 *
 * <h2>Vanilla-client safety</h2>
 * Vanilla clients send cursor fractions in {@code [0, 1]}; those decode to
 * "no protocol value" and the handler forwards the original packet object.
 */
public final class EasyPlaceListener implements Listener {

    private static final Logger LOG = Logger.getLogger("LitematicaFolia/EasyPlace");

    /** Permission required for the state override. Default op (see plugin.yml). */
    public static final String PERMISSION = "litematica.easyplace.use";

    /** Memos older than this are stale (the placement never happened). */
    private static final long MEMO_TTL_NANOS = 10_000_000_000L;
    /** Cap on outstanding memos to bound memory under a packet storm. */
    private static final int MAX_PENDING = 2048;

    private final Plugin plugin;
    private final Map<MemoKey, Memo> pending = new ConcurrentHashMap<>();
    private final Map<UUID, EasyPlaceChannelHandler> handlers = new ConcurrentHashMap<>();
    private volatile boolean enabled;

    public EasyPlaceListener(Plugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ lifecycle

    /** Registers the Bukkit listener and injects every online player. */
    public void enable() {
        enabled = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        int injected = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (inject(player)) injected++;
        }
        LOG.info("Easy Place V3 online: Netty handler '" + EasyPlaceChannelHandler.NAME
                + "' before '" + HandlerNames.PACKET_HANDLER + "', " + injected + " player(s) injected.");
    }

    /** Removes every injected handler and unregisters the Bukkit listener. */
    public void disable() {
        enabled = false;
        HandlerList.unregisterAll(this);
        for (UUID id : handlers.keySet().toArray(new UUID[0])) {
            eject(id);
        }
        pending.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        inject(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        eject(id);
        pending.keySet().removeIf(key -> key.player().equals(id));
    }

    // ------------------------------------------------------------ injection

    private boolean inject(Player player) {
        if (!enabled) return false;
        Channel channel;
        try {
            ServerPlayer handle = ((CraftPlayer) player).getHandle();
            ServerGamePacketListenerImpl listener = handle.connection;
            if (listener == null || listener.connection == null) return false;
            channel = listener.connection.channel;
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "easy-place: cannot resolve the connection of " + player.getName(), t);
            return false;
        }
        if (channel == null || !channel.isOpen()) return false;

        EasyPlaceChannelHandler handler = new EasyPlaceChannelHandler(this, player.getUniqueId(), player.getName());
        EasyPlaceChannelHandler previous = handlers.put(player.getUniqueId(), handler);
        if (previous != null) {
            removeFromPipeline(channel);
        }
        Runnable add = () -> {
            try {
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.get(EasyPlaceChannelHandler.NAME) != null) {
                    pipeline.remove(EasyPlaceChannelHandler.NAME);
                }
                if (pipeline.get(HandlerNames.PACKET_HANDLER) != null) {
                    pipeline.addBefore(HandlerNames.PACKET_HANDLER, EasyPlaceChannelHandler.NAME, handler);
                } else {
                    pipeline.addLast(EasyPlaceChannelHandler.NAME, handler);
                }
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "easy-place: pipeline injection failed for " + player.getName(), t);
            }
        };
        if (channel.eventLoop().inEventLoop()) {
            add.run();
        } else {
            channel.eventLoop().execute(add);
        }
        return true;
    }

    private void eject(UUID id) {
        EasyPlaceChannelHandler handler = handlers.remove(id);
        if (handler == null) return;
        Player player = Bukkit.getPlayer(id);
        if (player == null) return; // channel is gone with the player
        try {
            ServerPlayer handle = ((CraftPlayer) player).getHandle();
            ServerGamePacketListenerImpl listener = handle.connection;
            if (listener != null && listener.connection != null && listener.connection.channel != null) {
                removeFromPipeline(listener.connection.channel);
            }
        } catch (Throwable ignored) {
            // Connection already torn down.
        }
    }

    private static void removeFromPipeline(Channel channel) {
        Runnable remove = () -> {
            try {
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.get(EasyPlaceChannelHandler.NAME) != null) {
                    pipeline.remove(EasyPlaceChannelHandler.NAME);
                }
            } catch (Throwable ignored) {
                // Closed channel; nothing to clean.
            }
        };
        if (channel.eventLoop().inEventLoop()) {
            remove.run();
        } else {
            channel.eventLoop().execute(remove);
        }
    }

    // ----------------------------------------------------------------- memos

    /** Called from the Netty thread by {@link EasyPlaceChannelHandler}. */
    void remember(UUID player, BlockPos clicked, Direction face, int protocolValue) {
        if (pending.size() >= MAX_PENDING) {
            long now = System.nanoTime();
            pending.values().removeIf(memo -> now - memo.at() > MEMO_TTL_NANOS);
            if (pending.size() >= MAX_PENDING) {
                pending.clear();
            }
        }
        pending.put(new MemoKey(player, clicked.getX(), clicked.getY(), clicked.getZ()),
                new Memo(protocolValue, face, System.nanoTime()));
    }

    // ------------------------------------------------------- BlockPlaceEvent

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (pending.isEmpty()) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Block against = event.getBlockAgainst();
        Block placed = event.getBlockPlaced();

        Memo memo = pending.remove(new MemoKey(id, against.getX(), against.getY(), against.getZ()));
        if (memo == null) {
            memo = pending.remove(new MemoKey(id, placed.getX(), placed.getY(), placed.getZ()));
        }
        if (memo == null) return;
        if (System.nanoTime() - memo.at() > MEMO_TTL_NANOS) return;
        if (!player.hasPermission(PERMISSION)) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("easy-place ignored for " + player.getName() + " (missing " + PERMISSION + ")");
            }
            return;
        }

        final Memo finalMemo = memo;
        boolean ownedHere = Bukkit.isOwnedByCurrentRegion(placed);
        boolean hasBlockEntity;
        try {
            hasBlockEntity = ((CraftWorld) placed.getWorld()).getHandle()
                    .getBlockState(new BlockPos(placed.getX(), placed.getY(), placed.getZ()))
                    .hasBlockEntity();
        } catch (Throwable t) {
            hasBlockEntity = true;
        }
        if (ownedHere && !hasBlockEntity) {
            // Inside the event: one silent write, then CraftBukkit's
            // post-event step does the single onPlace / physics / client
            // update on the corrected state.
            applyOverride(player, placed, finalMemo, false);
        } else {
            // Block-entity blocks (chests, hoppers, comparators, signs, ...):
            // CraftBukkit is still holding their block entity in its
            // placement staging list at this point, so a write now would
            // spawn a throwaway block entity and a "state mismatch" warning
            // when the staged one is installed. Apply on the next tick of
            // the owning region instead (a second, full block update).
            // Also the path for the theoretical foreign-region case.
            FoliaCompat.runOnRegion(plugin, placed.getWorld(), placed.getX() >> 4, placed.getZ() >> 4,
                    () -> applyOverride(player, placed, finalMemo, true));
        }
    }

    /**
     * Computes and writes the corrected state. With {@code applyPhysics}
     * false (inside the event) the write is silent and CraftBukkit's
     * post-event step performs the single onPlace / neighbour / client
     * update on the corrected state; with {@code true} (deferred path) the
     * write is a regular block update.
     */
    private void applyOverride(Player player, Block placed, Memo memo, boolean applyPhysics) {
        try {
            ServerLevel level = ((CraftWorld) placed.getWorld()).getHandle();
            BlockPos pos = new BlockPos(placed.getX(), placed.getY(), placed.getZ());
            BlockState current = level.getBlockState(pos);
            if (applyPhysics && !current.is(((CraftBlockData) placed.getBlockData()).getState().getBlock())) {
                // Deferred path only: the block changed between the event and
                // this tick (broken, replaced); do not touch it.
                return;
            }

            // Two-block-tall blocks: always operate on the lower half.
            if (current.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && current.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                    && level.getBlockState(pos.below()).is(current.getBlock())) {
                pos = pos.below();
                current = level.getBlockState(pos);
            }

            Direction playerFacing = ((CraftPlayer) player).getHandle().getDirection();
            BlockState resolved = EasyPlaceStateResolver.resolve(current, memo.protocolValue(), level, pos, playerFacing);
            if (resolved == null || resolved == current) {
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine("easy-place no-op at " + pos.toShortString() + " (state already matches or not applicable)");
                }
                return;
            }

            CraftBlock.setBlockState(level, pos, resolved, applyPhysics);

            if (resolved.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                BlockPos otherPos = resolved.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                        ? pos.above() : pos.below();
                BlockState other = level.getBlockState(otherPos);
                if (other.is(resolved.getBlock())) {
                    BlockState otherNew = EasyPlaceStateResolver.copyForOtherHalf(resolved, other);
                    if (otherNew != other) {
                        CraftBlock.setBlockState(level, otherPos, otherNew, applyPhysics);
                    }
                }
            }

            LOG.info("easy-place applied: player=" + player.getName() + " pos=" + pos.toShortString()
                    + " " + describe(current) + " -> " + describe(resolved)
                    + " (protocolValue=0x" + Integer.toHexString(memo.protocolValue()) + ")");
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "easy-place override failed at " + placed.getX() + "," + placed.getY()
                    + "," + placed.getZ(), t);
        }
    }

    private static String describe(BlockState state) {
        String s = state.toString();
        int idx = s.indexOf("Block{");
        return idx >= 0 ? s.substring(idx + 6).replace("}", "") : s;
    }

    /** Lookup key for {@link #pending}: player plus clicked block position. */
    private record MemoKey(UUID player, int x, int y, int z) {
    }

    /** Decoded protocol value plus the click face and arrival time. */
    private record Memo(int protocolValue, Direction face, long at) {
    }
}
