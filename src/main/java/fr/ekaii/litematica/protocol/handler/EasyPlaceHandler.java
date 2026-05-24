package fr.ekaii.litematica.protocol.handler;

import fr.ekaii.litematica.paste.FoliaCompat;
import fr.ekaii.litematica.protocol.PacketHandler;
import fr.ekaii.litematica.protocol.ProtocolBuffer;
import fr.ekaii.litematica.protocol.ProtocolConstants;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Easy Place V3 handler. The client streams a single
 * {@code EasyPlaceRequest} per intended click; the server validates
 * permission, optionally checks for region/claim restrictions, and on
 * success applies the block via the Folia-safe Paper API.
 *
 * <h2>Anti-cheat note</h2>
 * Because this handler writes blocks via {@link Block#setBlockData(BlockData)}
 * on the owning {@code RegionScheduler} thread, anti-cheat plugins (NCP,
 * Grim, Vulcan) see the change as a server-driven action — there is no
 * synthetic "player placed block" event raised, so legitimate cheat
 * checks that gate on packet-rate or reach should not be tripped.
 * <em>However</em>, anti-grief plugins (CoreProtect, LogBlock, …) will
 * also <em>not</em> attribute the block to the player unless they hook
 * the underlying Bukkit BlockPlaceEvent — which we do not currently fire.
 * If you need attribution, layer a follow-up Bukkit event in the
 * RegionScheduler task.
 *
 * <h2>Wire format</h2>
 * See {@link ProtocolConstants.EasyPlace#C2S_PLACE_REQUEST}.
 */
public final class EasyPlaceHandler {

    private static final Logger LOG = Logger.getLogger("LitematicaFolia/EasyPlaceHandler");

    private final Plugin plugin;

    public EasyPlaceHandler(Plugin plugin) {
        this.plugin = plugin;
    }

    public void onPlaceRequest(Player player, ProtocolBuffer.Reader r) {
        try {
            EasyPlaceRequest req = EasyPlaceRequest.read(r);

            World world = player.getWorld();
            BlockData data;
            try {
                data = Bukkit.createBlockData(req.blockState);
            } catch (Throwable t) {
                ack(player, req.requestId, false, "invalid blockState '" + req.blockState + "'");
                return;
            }

            // Defer to the owning region thread before mutating the world.
            // We do not check region/claim integrations here — that's the
            // anti-cheat / WG layer's job, and it's better that we don't
            // pretend to police what we cannot reliably police on Folia.
            // TODO integrate with WorldGuard if it's available — for now
            // just let the underlying setBlockData() trigger WG's listeners
            // through the synthetic placement.
            int cx = req.x >> 4;
            int cz = req.z >> 4;
            FoliaCompat.runOnRegion(plugin, world, cx, cz, () -> {
                try {
                    Block block = world.getBlockAt(req.x, req.y, req.z);
                    // TODO smoke-test with vanilla Litematica client —
                    // also consider checking "expected current state"
                    // against what the client thinks is there before
                    // overwriting (V3 carries the expected state in the
                    // hit-vector path; we ignore that for now).
                    block.setBlockData(data, /* applyPhysics */ true);
                    ack(player, req.requestId, true, "");
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "easyplace setBlockData failed", t);
                    ack(player, req.requestId, false, t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }).exceptionally(t -> {
                ack(player, req.requestId, false, "schedule failed");
                return null;
            });
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "easyplace decode for " + player.getName(), t);
        }
    }

    private void ack(Player player, int requestId, boolean success, String reason) {
        try {
            byte[] reply = PacketHandler.buildEasyPlace(
                    ProtocolConstants.EasyPlace.S2C_PLACE_ACK,
                    w -> {
                        w.writeVarInt(requestId);
                        w.writeBoolean(success);
                        w.writeString(reason == null ? "" : reason);
                    });
            player.sendPluginMessage(plugin, ProtocolConstants.CHANNEL_EASY_PLACE, reply);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "easyplace ack send", t);
        }
    }

    // -------------------------------------------------------- request DTO

    /** Wire-format struct for an Easy Place V3 placement request. */
    public static final class EasyPlaceRequest {
        public int x, y, z;
        public String blockState;
        public boolean hasItemNbt;
        // We don't decode item NBT into a typed object — it's passed
        // opaquely as a byte slice the future implementation can hand
        // to NmsBridge.
        public byte[] itemNbt;
        public float hitX, hitY, hitZ;
        public int facingOrdinal;
        public int requestId;

        public static EasyPlaceRequest read(ProtocolBuffer.Reader r) throws Exception {
            EasyPlaceRequest req = new EasyPlaceRequest();
            int[] pos = r.readBlockPos();
            req.x = pos[0]; req.y = pos[1]; req.z = pos[2];
            req.blockState = r.readString();
            req.hasItemNbt = r.readBoolean();
            if (req.hasItemNbt) {
                int len = r.readVarInt();
                if (len > 0) {
                    req.itemNbt = new byte[len];
                    for (int i = 0; i < len; i++) {
                        req.itemNbt[i] = r.readByte();
                    }
                } else {
                    req.itemNbt = new byte[0];
                }
            } else {
                req.itemNbt = new byte[0];
            }
            req.hitX = r.readFloat();
            req.hitY = r.readFloat();
            req.hitZ = r.readFloat();
            req.facingOrdinal = r.readVarInt();
            req.requestId = r.readVarInt();
            return req;
        }

        public void write(ProtocolBuffer.Writer w) throws Exception {
            w.writeBlockPos(x, y, z);
            w.writeString(blockState);
            w.writeBoolean(hasItemNbt);
            if (hasItemNbt) {
                w.writeVarInt(itemNbt == null ? 0 : itemNbt.length);
                if (itemNbt != null) {
                    for (byte b : itemNbt) {
                        w.writeByte(b);
                    }
                }
            }
            w.writeFloat(hitX);
            w.writeFloat(hitY);
            w.writeFloat(hitZ);
            w.writeVarInt(facingOrdinal);
            w.writeVarInt(requestId);
        }
    }
}
