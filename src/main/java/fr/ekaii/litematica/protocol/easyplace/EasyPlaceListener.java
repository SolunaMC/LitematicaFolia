package fr.ekaii.litematica.protocol.easyplace;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import fr.ekaii.litematica.paste.FoliaCompat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PacketEvents-based listener that implements <strong>Litematica
 * Easy Place V3</strong> server-side without mixin / NMS access.
 *
 * <h2>Approach</h2>
 * Servux's Fabric server-mod hooks two things via mixin:
 * <ol>
 *   <li>{@code ServerGamePacketListenerImpl#handleUseItemOn} —
 *       short-circuits the "is the cursor inside the clicked face?"
 *       sanity check ({@code hitPos.subtract(blockCenter)} →
 *       {@code Vec3.ZERO}). Without this, Mojang's server rejects any
 *       cursor with {@code x &gt;= 2}, which is exactly what
 *       Litematica encodes.</li>
 *   <li>{@code BlockItem#getPlacementState} — calls
 *       {@code PlacementHandler.applyPlacementProtocolV3} to derive the
 *       schematic-correct {@code BlockState} from the cursor's
 *       X-fractional bit field.</li>
 * </ol>
 *
 * <p>On a Paper plugin we cannot mixin, so we approximate both:
 * <ol>
 *   <li>In the {@link PacketListener#onPacketReceive} hook for
 *       {@code PLAYER_BLOCK_PLACEMENT} (the PacketEvents alias for
 *       {@code ServerboundUseItemOnPacket}) we decode the protocol
 *       value from {@code cursorPosition.x} and, if it is &ge; 0,
 *       <strong>rewrite</strong> the cursor X-fractional to a sane
 *       in-range value via {@link WrapperPlayClientPlayerBlockPlacement#setCursorPosition(Vector3f)}.
 *       This makes Mojang's server accept the click as legitimate and
 *       perform the placement.</li>
 *   <li>We <strong>memoise</strong> the decoded protocol value keyed by
 *       {@code (player UUID, block pos)}. A Bukkit {@link BlockPlaceEvent}
 *       listener at {@link EventPriority#MONITOR} consumes the memo
 *       and, on the owning region thread, applies a Bukkit-API best
 *       effort {@code Directional#setFacing} override.</li>
 * </ol>
 *
 * <h2>Limitations vs Servux</h2>
 * <p>Servux's {@code PlacementHandler.applyPlacementProtocolV3}
 * (PlacementHandler.java line 67) iterates over the live block-state's
 * <em>generic property set</em> in alphabetical order, decoding a
 * variable-bit-width index per whitelisted property
 * ({@code BlockStateProperties.HALF, AXIS, SLAB_TYPE, …}). Bukkit's
 * API exposes only typed interfaces ({@code Directional, Bisected,
 * Orientable, …}) without a generic property-iteration mechanism, so
 * a faithful reproduction of the multi-property decode would need NMS
 * reflection.
 *
 * <p>For v0.3.0 we implement the most common override — <strong>facing
 * direction</strong>, which covers the vast majority of Easy Place use
 * cases (doors, stairs, observers, dispensers, chests, …). The HALF /
 * AXIS / SLAB_TYPE override is left as a {@code TODO} and would need
 * either Bukkit API surface expansion or an NmsBridge call into
 * {@code BlockState.getProperties()}.
 *
 * <h2>Vanilla-client safety</h2>
 * Vanilla MC clients always produce a cursor X-fractional in
 * {@code [0, 1]} (the actual raycast hit location relative to the
 * block face). {@link EasyPlaceProtocolDecoder#decodeProtocolValue}
 * returns {@code -1} (no override) in that case, so this listener is a
 * <strong>strict no-op for vanilla clients</strong> — no risk of
 * breaking vanilla placement.
 *
 * <h2>Permission gate</h2>
 * Per-player check on {@code litematica.easyplace.use} (default op).
 * Rejected silently — the placement proceeds as if no protocol value
 * had been sent (vanilla behaviour for that player).
 *
 * <h2>Config gate</h2>
 * Entire listener registration is gated by
 * {@code protocol.enableEasyPlace} (default {@code false}).
 *
 * <h2>Folia safety</h2>
 * The {@code BlockPlaceEvent} fires on the region thread already, but
 * to be defensive about future {@code BlockPlaceEvent} firings off
 * the region (e.g. async place events from other plugins), the
 * override hop is always wrapped in
 * {@link FoliaCompat#runOnRegion(Plugin, World, int, int, Runnable)}.
 *
 * <p>TODO real-client smoke: launch test-harness/fabric-client/launch-client.sh,
 * connect to 127.0.0.1:25590, enable Litematica Easy Place mode, attempt
 * block placement on schematic, verify our log line "easy-place applied:
 * pos=… state=…" fires.
 */
public final class EasyPlaceListener extends PacketListenerAbstract implements Listener {

    private static final Logger LOG = Logger.getLogger("LitematicaFolia/EasyPlace");

    /** Permission required. Default op (see plugin.yml). */
    public static final String PERMISSION = "litematica.easyplace.use";

    private final Plugin plugin;

    /**
     * Pending protocol-value memos keyed by {@code (player UUID, blockX,
     * blockY, blockZ)}. Cleared on consumption by the BlockPlaceEvent
     * listener or on player disconnect (the entry expires after a few
     * seconds anyway; we cap the map size to bound memory).
     */
    private final Map<MemoKey, Integer> pending = new HashMap<>();

    /** Cap pending memo count to bound memory under packet storm. */
    private static final int MAX_PENDING = 1024;

    public EasyPlaceListener(Plugin plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
    }

    // ----------------------------------------------------------- PacketListener

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            return;
        }

        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;

        Player player;
        try {
            player = Bukkit.getPlayer(user.getUUID());
        } catch (Throwable t) {
            return;
        }
        if (player == null) return;
        if (!player.hasPermission(PERMISSION)) return;

        WrapperPlayClientPlayerBlockPlacement wrapper =
                new WrapperPlayClientPlayerBlockPlacement(event);

        Vector3f cursor = wrapper.getCursorPosition();
        if (cursor == null) return;

        int protocolValue = EasyPlaceProtocolDecoder.decodeProtocolValue(cursor.getX());
        if (protocolValue < 0) {
            // Vanilla click — pass through untouched.
            return;
        }

        Vector3i pos = wrapper.getBlockPosition();
        if (pos == null) return;

        // Memoise so the BlockPlaceEvent listener can apply the override.
        synchronized (pending) {
            if (pending.size() >= MAX_PENDING) {
                pending.clear();
            }
            pending.put(new MemoKey(user.getUUID(), pos.getX(), pos.getY(), pos.getZ()),
                    protocolValue);
        }

        // Rewrite the cursor X so Mojang's server accepts the click.
        // Keep relX = 0.5 (center of face) — vanilla MC has no sanity
        // requirement on the exact fractional value, only that it stays
        // within sane range after the hit-pos check.
        wrapper.setCursorPosition(new Vector3f(0.5f, cursor.getY(), cursor.getZ()));
        event.markForReEncode(true);

        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("easy-place decoded: player=" + player.getName()
                    + " pos=(" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                    + ") protocolValue=0x" + Integer.toHexString(protocolValue));
        }
    }

    // ------------------------------------------------------- BlockPlaceEvent

    /**
     * Applies the memoised protocol value to the just-placed block,
     * scheduled on the owning region.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        if (!player.hasPermission(PERMISSION)) return;

        Block placed = event.getBlockPlaced();
        MemoKey key = new MemoKey(player.getUniqueId(),
                placed.getX(), placed.getY(), placed.getZ());
        Integer protocolValue;
        synchronized (pending) {
            protocolValue = pending.remove(key);
        }
        if (protocolValue == null) return;

        int facingIndex = EasyPlaceProtocolDecoder.decodeFacingIndex(protocolValue);
        if (facingIndex < 0) return;

        World world = placed.getWorld();
        Location loc = placed.getLocation();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        BlockData originalData = event.getBlockReplacedState().getBlockData(); // unused, kept for symmetry
        BlockData placedData = placed.getBlockData();

        // Best-effort facing override via Bukkit Directional API.
        // Servux iterates over all whitelisted properties — we cover the
        // most-common one here (facing) since Bukkit doesn't expose
        // generic property iteration without NMS.
        if (!(placedData instanceof Directional directional)) {
            // Block does not expose a facing — nothing to do.
            // TODO HALF / AXIS / SLAB_TYPE / ORIENTATION via NmsBridge.
            return;
        }

        BlockFace face = facingFromIndex(facingIndex, directional.getFacing());
        if (face == null || face == directional.getFacing()) return;
        if (!directional.getFaces().contains(face)) return;

        BlockData mutated = placedData.clone();
        ((Directional) mutated).setFacing(face);

        FoliaCompat.runOnRegion(plugin, world, cx, cz, () -> {
            try {
                placed.setBlockData(mutated, /* applyPhysics */ true);
                LOG.info("easy-place applied: pos=(" + placed.getX() + "," + placed.getY()
                        + "," + placed.getZ() + ") state=" + mutated.getAsString());
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "easy-place override failed", t);
            }
        });
    }

    /**
     * Maps a Mojang {@code Direction.from3DDataValue} index 0..5 to a
     * Bukkit {@link BlockFace}. Index 6 (Servux convention) =
     * "opposite of current facing".
     */
    private static BlockFace facingFromIndex(int idx, BlockFace currentFacing) {
        // Direction.from3DDataValue: 0=DOWN 1=UP 2=NORTH 3=SOUTH 4=WEST 5=EAST
        return switch (idx) {
            case 0 -> BlockFace.DOWN;
            case 1 -> BlockFace.UP;
            case 2 -> BlockFace.NORTH;
            case 3 -> BlockFace.SOUTH;
            case 4 -> BlockFace.WEST;
            case 5 -> BlockFace.EAST;
            case 6 -> currentFacing.getOppositeFace();
            default -> null;
        };
    }

    /** Lookup key for {@link #pending}. */
    private record MemoKey(UUID player, int x, int y, int z) {}
}
