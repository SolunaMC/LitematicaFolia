package fr.ekaii.litematica.protocol.handler;

import fr.ekaii.litematica.core.LitematicNbt;
import fr.ekaii.litematica.core.LitematicReader;
import fr.ekaii.litematica.core.LitematicSchematic;
import fr.ekaii.litematica.paste.PasteOperation;
import fr.ekaii.litematica.paste.PasteOptions;
import fr.ekaii.litematica.paste.PasteResult;
import fr.ekaii.litematica.protocol.PacketHandler;
import fr.ekaii.litematica.protocol.PacketSplitter;
import fr.ekaii.litematica.protocol.ProtocolBuffer;
import fr.ekaii.litematica.protocol.ProtocolConstants;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Receives a Direct Paste stream from a Servux client and reassembles
 * it into a {@link LitematicSchematic} that runs through
 * {@link PasteOperation}.
 *
 * <h2>Wire model (corrected v2 — see SERVUX_WIRE_FORMAT.md)</h2>
 *
 * <p>Servux uses a <em>two-layer</em> splitter for Direct Paste:
 *
 * <ol>
 *   <li><strong>Outer (Servux PacketSplitter)</strong>. Every
 *       application-level payload {@code (VarInt transactionId + NBT
 *       compound)} is chunked into N raw byte slices, each wrapped as
 *       a {@code PACKET_C2S_NBT_RESPONSE_DATA} (type 13) wire packet.
 *       The very first slice has a {@code VarInt(totalLen)} of the
 *       application payload prepended. <em>The {@code _NBT_STREAM_START}
 *       packet types (10 / 12) never travel on the wire</em>: they are
 *       internal Servux markers triggering the splitter path.</li>
 *   <li><strong>Inner (Litematic-Transmit sub-protocol)</strong>. Each
 *       reassembled application payload's NBT compound carries a
 *       {@code Task} field with one of {@code Litematic-TransmitStart},
 *       {@code Litematic-TransmitData}, {@code Litematic-TransmitEnd},
 *       {@code Litematic-TransmitCancel}. A {@code SliceKey} (random
 *       long) ties together multiple of these compounds across the
 *       lifetime of a single file transfer. The actual {@code .litematic}
 *       bytes are carried in the {@code Data} byte-array of each
 *       {@code Litematic-TransmitData} compound.</li>
 * </ol>
 *
 * <h2>Why this matters</h2>
 *
 * <p>The previous draft of this handler assumed type 12 carried a
 * header NBT and type 13 carried raw bytes that accumulated to form
 * the schematic. That model is incompatible with any real Servux
 * client. The on-wire stream is purely type-13 packets all the way
 * through; only the outer splitter reassembles them; and the
 * Transmit sub-protocol lives entirely inside the resulting NBT
 * compound.
 */
public final class DirectPasteHandler {

    private static final Logger LOG = Logger.getLogger("LitematicaFolia/DirectPasteHandler");

    private final Plugin plugin;

    /**
     * Outer-splitter reassembly state, per player. Cap is configurable via
     * {@code protocol.maxDirectPasteSize} (default 128 MiB) so server ops
     * can raise it when 32M+ block schematics are expected.
     */
    private final PacketSplitter splitter;

    /** Inner Transmit-protocol state, keyed by Servux {@code SliceKey}. */
    private final Map<Long, TransmitSession> transmits = new HashMap<>();

    /** Map UUID → SliceKey so we can drop sessions on disconnect. */
    private final Map<UUID, Long> playerSessions = new HashMap<>();

    public DirectPasteHandler(Plugin plugin) {
        this.plugin = plugin;
        int cap = plugin.getConfig().getInt("protocol.maxDirectPasteSize",
                PacketSplitter.DEFAULT_MAX_C2S_RECEIVE);
        this.splitter = new PacketSplitter(cap);
        LOG.info("[direct-paste] outer-splitter cap = "
                + (cap / (1024 * 1024)) + " MiB");
    }

    /**
     * Entry point for any inbound {@code C2S_NBT_STREAM_DATA} (type 13)
     * slice. The {@code C2S_NBT_STREAM_START} (type 12) "packet type" is
     * not handled here because it does not appear on the wire — see
     * class javadoc.
     */
    public void onSplitterSlice(Player player, ProtocolBuffer.Reader r) {
        if (!player.hasPermission(PacketHandler.PERM_PASTE) && !player.isOp()) {
            LOG.fine("paste denied: " + player.getName() + " lacks " + PacketHandler.PERM_PASTE);
            return;
        }

        UUID uid = player.getUniqueId();
        byte[] sliceBody = r.readRemainingBytes();
        byte[] assembled;
        try {
            assembled = splitter.receive(uid, sliceBody);
        } catch (Throwable t) {
            LOG.warning("[direct-paste] splitter rejected slice from " + player.getName()
                    + " — " + t.getMessage()
                    + " (raise protocol.maxDirectPasteSize if expected)");
            try {
                player.sendMessage("[LitematicaFolia] direct paste aborted: " + t.getMessage()
                        + ". Schematic too big — ask staff to raise protocol.maxDirectPasteSize "
                        + "or upload it server-side.");
            } catch (Throwable ignored) { }
            splitter.forget(uid);
            return;
        }
        if (assembled == null) {
            return;  // need more slices
        }
        LOG.info("[direct-paste] splitter reassembled " + assembled.length + " bytes from "
                + player.getName());

        // Decode (VarInt transactionId + NBT(compound)) of the
        // application-layer payload Servux flushed.
        ProtocolBuffer.Reader payloadReader = new ProtocolBuffer.Reader(assembled);
        int transactionId;
        LitematicNbt.NbtCompound payload;
        try {
            transactionId = payloadReader.readVarInt();
            payload = payloadReader.readNbt();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "splitter payload parse failed for " + player.getName(), t);
            return;
        }
        if (payload == null) {
            LOG.warning("splitter payload had null NBT root for " + player.getName());
            return;
        }
        handleTransmitFrame(player, transactionId, payload);
    }

    /**
     * Process one Transmit-protocol frame (the NBT compound carried by
     * one outer-splitter payload).
     */
    private void handleTransmitFrame(Player player, int transactionId,
                                     LitematicNbt.NbtCompound payload) {
        String task = orEmpty(payload.getString("Task"));
        // The inline "LitematicaPaste" path (maruohon Litematica
        // SchematicPlacementManager) carries the entire placement +
        // schematic data in ONE compound. No SliceKey, no state machine.
        if (task.equals("LitematicaPaste")) {
            handleInlineLitematicaPaste(player, payload);
            return;
        }
        Long sliceKey = payload.getLong("SliceKey");
        if (task.isEmpty() || sliceKey == null) {
            LOG.warning("Transmit frame missing Task / SliceKey from " + player.getName()
                    + " — task=" + task + " key=" + sliceKey);
            return;
        }

        switch (task) {
            case "Litematic-TransmitStart" -> {
                TransmitSession s = new TransmitSession(player.getUniqueId(), sliceKey, payload);
                transmits.put(sliceKey, s);
                playerSessions.put(player.getUniqueId(), sliceKey);
            }
            case "Litematic-TransmitData" -> {
                TransmitSession s = transmits.get(sliceKey);
                if (s == null) {
                    LOG.warning("Transmit DATA for unknown SliceKey " + sliceKey
                            + " from " + player.getName());
                    return;
                }
                Integer size = payload.getInt("Size");
                byte[] data = payload.getByteArray("Data");
                if (size == null || data == null || size < 0) {
                    LOG.warning("Transmit DATA missing Size/Data for SliceKey " + sliceKey);
                    return;
                }
                int n = Math.min(size, data.length);
                if (n > 0) {
                    s.sink.write(data, 0, n);
                }
            }
            case "Litematic-TransmitEnd" -> {
                TransmitSession s = transmits.remove(sliceKey);
                playerSessions.remove(player.getUniqueId());
                if (s == null) {
                    LOG.warning("Transmit END for unknown SliceKey " + sliceKey
                            + " from " + player.getName());
                    return;
                }
                Integer totalSize = payload.getInt("TotalSize");
                if (totalSize != null && totalSize != s.sink.size()) {
                    LOG.warning("Transmit END size mismatch for SliceKey " + sliceKey
                            + ": expected " + totalSize + " got " + s.sink.size());
                }
                completeAssembly(player, s);
            }
            case "Litematic-TransmitCancel" -> {
                transmits.remove(sliceKey);
                playerSessions.remove(player.getUniqueId());
                LOG.info("Transmit cancelled for SliceKey " + sliceKey
                        + " by " + player.getName());
            }
            default -> LOG.fine("ignoring Direct Paste task '" + task
                    + "' (transactionId=" + transactionId + ") from " + player.getName());
        }
    }

    private void completeAssembly(Player player, TransmitSession session) {
        byte[] fileBytes = session.sink.toByteArray();
        try {
            // Servux ships the raw gzipped .litematic file bytes via the
            // Data slices, so feed them to the standard reader.
            LitematicSchematic schem = LitematicReader.read(fileBytes);

            Location origin   = readOrigin(session.placementData, player);
            PasteOptions opts = readOptions(session.placementData, origin);

            new PasteOperation(plugin, schem, opts).execute()
                    .whenComplete((res, err) -> reportComplete(player, res, err));
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Direct Paste assembly failed", t);
            try {
                player.sendMessage("[LitematicaFolia] direct paste failed: "
                        + t.getClass().getSimpleName());
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Handle the maruohon Litematica 0.27.x inline Direct Paste path:
     * {@code Task=LitematicaPaste} carrying a single compound
     * {@code {Origin: int[3], SubRegions: Compound{regionName: regionNbt}, IgnoreEntities: byte, …}}.
     *
     * <p>SubRegions[name] follows the same schema as a {@code .litematic}
     * file's {@code Regions[name]} compound — palette + bit-packed
     * blockstates + tile entities + entities + pending ticks. We hand the
     * compound directly to {@link LitematicReader#fromCompound} after
     * wrapping it in a synthetic outer root with the required Metadata
     * fields.
     */
    private void handleInlineLitematicaPaste(Player player, LitematicNbt.NbtCompound payload) {
        LOG.info("[direct-paste] inline LitematicaPaste from " + player.getName()
                + " (root keys=" + payload.entries().keySet() + ")");
        // The actual schematic compound (Version/MinecraftDataVersion/Metadata/Regions)
        // lives under the "Schematics" key — Litematica embeds its full
        // LitematicaSchematic.toNbt() there. "SubRegions" is just per-region
        // placement overrides (position/rotation/enabled), not block data.
        LitematicNbt.NbtCompound schematicsCompound = payload.getCompound("Schematics");
        if (schematicsCompound == null) {
            LOG.warning("[direct-paste] LitematicaPaste missing Schematics from " + player.getName());
            return;
        }
        Location origin = resolveInlineOrigin(payload, player);
        if (origin == null) {
            LOG.warning("[direct-paste] LitematicaPaste no usable origin from " + player.getName());
            return;
        }
        Byte ignoreEntities = payload.getByte("IgnoreEntities");
        boolean placeEntities = ignoreEntities == null || ignoreEntities == 0;

        // Map Litematica's Rotation enum string → yaw integer.
        // NONE=0, CLOCKWISE_90=90, CLOCKWISE_180=180, COUNTERCLOCKWISE_90=270.
        int yaw = 0;
        String rot = payload.getString("Rotation");
        if (rot != null) {
            switch (rot) {
                case "CLOCKWISE_90"       -> yaw = 90;
                case "CLOCKWISE_180"      -> yaw = 180;
                case "COUNTERCLOCKWISE_90"-> yaw = 270;
                default                   -> yaw = 0;
            }
        }

        LitematicSchematic schem;
        try {
            schem = LitematicReader.fromCompound(schematicsCompound);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[direct-paste] LitematicaPaste fromCompound failed for "
                    + player.getName() + " — Schematics keys="
                    + schematicsCompound.entries().keySet(), t);
            player.sendMessage("[LitematicaFolia] direct paste decode failed: "
                    + t.getClass().getSimpleName() + " — " + t.getMessage());
            return;
        }

        PasteOptions defaults = PasteOptions.defaults(origin);
        PasteOptions opts = new PasteOptions(
                origin,
                placeEntities,
                defaults.placeTileEntities(),
                defaults.placePendingTicks(),
                defaults.deferredPhysics(),
                defaults.observersLast(),
                defaults.maxBlocksPerChunkTask(),
                yaw,
                null);

        player.sendMessage("[LitematicaFolia] Direct Paste via Servux — placing "
                + schem.regions.size() + " region(s) at "
                + origin.getBlockX() + "," + origin.getBlockY() + "," + origin.getBlockZ() + "…");
        new PasteOperation(plugin, schem, opts).execute()
                .whenComplete((res, err) -> reportComplete(player, res, err));
    }

    private Location resolveInlineOrigin(LitematicNbt.NbtCompound payload, Player player) {
        // Preferred: Origin as IntArray[3].
        int[] arr = payload.getIntArray("Origin");
        if (arr != null && arr.length >= 3) {
            return new Location(player.getWorld(), arr[0], arr[1], arr[2]);
        }
        // Or a compound {x,y,z}.
        LitematicNbt.NbtCompound c = payload.getCompound("Origin");
        if (c != null) {
            Integer ox = c.getInt("x");
            Integer oy = c.getInt("y");
            Integer oz = c.getInt("z");
            if (ox != null && oy != null && oz != null) {
                return new Location(player.getWorld(), ox, oy, oz);
            }
        }
        // Or scalars at the root.
        Integer ox = payload.getInt("OriginX");
        Integer oy = payload.getInt("OriginY");
        Integer oz = payload.getInt("OriginZ");
        if (ox != null && oy != null && oz != null) {
            return new Location(player.getWorld(), ox, oy, oz);
        }
        return null;
    }

    private static String firstRegionName(LitematicNbt.NbtCompound subRegions) {
        for (String k : subRegions.entries().keySet()) {
            return k;
        }
        return "DirectPaste";
    }

    private static LitematicNbt.NbtCompound pickEnclosingSize(
            LitematicNbt.NbtCompound payload, LitematicNbt.NbtCompound subRegions) {
        LitematicNbt.NbtCompound supplied = payload.getCompound("EnclosingSize");
        if (supplied != null) return supplied;
        // Take the first region's Size (a sane default for one-region paste).
        for (LitematicNbt.NbtTag t : subRegions.entries().values()) {
            if (t instanceof LitematicNbt.NbtCompound rc) {
                LitematicNbt.NbtCompound size = rc.getCompound("Size");
                if (size != null) return size;
            }
        }
        // Last resort: zero-size — parser will compute from packed array.
        LitematicNbt.NbtCompound zero = new LitematicNbt.NbtCompound();
        zero.putInt("x", 0); zero.putInt("y", 0); zero.putInt("z", 0);
        return zero;
    }

    /** Called from the bridge when a player disconnects. */
    public void onPlayerQuit(UUID player) {
        splitter.forget(player);
        Long key = playerSessions.remove(player);
        if (key != null) {
            transmits.remove(key);
        }
    }

    // ------------------------------------------------------- helpers

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private void reportComplete(Player player, PasteResult res, Throwable err) {
        try {
            if (err != null) {
                LOG.log(Level.WARNING, "direct paste failed for " + player.getName(), err);
                player.sendMessage("[LitematicaFolia] paste failed: "
                        + err.getClass().getSimpleName());
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
        if (payload == null) {
            return player.getLocation().clone();
        }
        Integer x = payload.getInt("OriginX");
        Integer y = payload.getInt("OriginY");
        Integer z = payload.getInt("OriginZ");
        if (x == null || y == null || z == null) {
            return player.getLocation().clone();
        }
        return new Location(player.getWorld(), x, y, z);
    }

    private PasteOptions readOptions(LitematicNbt.NbtCompound payload, Location origin) {
        PasteOptions defaults = PasteOptions.defaults(origin);
        if (payload == null) {
            return defaults;
        }
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

    // --------------------------------------------------- Transmit session

    /** Accumulates the {@code Litematic-TransmitData} byte slices for one upload. */
    private static final class TransmitSession {
        final UUID player;
        final long sliceKey;
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        final LitematicNbt.NbtCompound placementData;

        TransmitSession(UUID player, long sliceKey, LitematicNbt.NbtCompound startFrame) {
            this.player    = player;
            this.sliceKey  = sliceKey;
            this.placementData = startFrame.getCompound("PlacementData");
        }
    }
}
