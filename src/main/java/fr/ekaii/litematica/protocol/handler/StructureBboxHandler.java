package fr.ekaii.litematica.protocol.handler;

import fr.ekaii.litematica.core.LitematicNbt;
import fr.ekaii.litematica.protocol.PacketHandler;
import fr.ekaii.litematica.protocol.ProtocolBuffer;
import fr.ekaii.litematica.protocol.ProtocolConstants;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Stub for the {@code servux:structures} channel that MiniHUD uses to
 * draw vanilla structure bounding boxes (mansions, monuments, jungle
 * temples, …) on the client.
 *
 * <p>This is marked as a bonus by the task brief and is scaffolded only.
 * Real implementation requires NMS access to
 * {@code MinecraftServer#getStructureManager()} and a way to enumerate
 * the active structures around a player — which is non-trivial to keep
 * Folia-safe (the structure manager is per-level and reading it must
 * happen on that level's global region).
 *
 * <p>// TODO smoke-test with vanilla MiniHUD client and replace the
 * stub responses with real bbox enumeration.
 */
public final class StructureBboxHandler {

    private static final Logger LOG = Logger.getLogger("LitematicaFolia/StructureBboxHandler");

    private final Plugin plugin;

    /** Players that have opted into the structure stream. */
    private final Set<UUID> subscribers = new HashSet<>();

    public StructureBboxHandler(Plugin plugin) {
        this.plugin = plugin;
    }

    public void onRegister(Player player, ProtocolBuffer.Reader r) throws Exception {
        LitematicNbt.NbtCompound nbt = null;
        try { nbt = r.readNbt(); } catch (Throwable ignored) {}
        subscribers.add(player.getUniqueId());

        // Reply with capability metadata so the client knows we know.
        // Same 4-key layout as the litematics handshake — Servux's
        // StructureDataProvider uses the same convention.
        LitematicNbt.NbtCompound meta = new LitematicNbt.NbtCompound();
        meta.putString("name", "structure_data");
        meta.putString("id",   ProtocolConstants.CHANNEL_STRUCTURES);
        meta.putInt   ("version", ProtocolConstants.STRUCTURES_PROTOCOL_VERSION);
        meta.putString("servux", ProtocolConstants.SERVER_NAME);
        byte[] reply = PacketHandler.buildStructures(
                ProtocolConstants.Structures.S2C_METADATA,
                w -> w.writeNbt(meta));
        player.sendPluginMessage(plugin, ProtocolConstants.CHANNEL_STRUCTURES, reply);
        LOG.fine("structures: " + player.getName() + " registered (header=" + nbt + ")");
    }

    public void onUnregister(Player player, ProtocolBuffer.Reader r) {
        try { r.readNbt(); } catch (Throwable ignored) {}
        subscribers.remove(player.getUniqueId());
        LOG.fine("structures: " + player.getName() + " unregistered");
    }

    public void onSpawnMetadataRequest(Player player, ProtocolBuffer.Reader r) throws Exception {
        try { r.readNbt(); } catch (Throwable ignored) {}
        // Build a minimal spawn metadata compound. Real implementation
        // would source spawn point + world border from the live world.
        LitematicNbt.NbtCompound meta = new LitematicNbt.NbtCompound();
        meta.putInt("SpawnPosX", player.getWorld().getSpawnLocation().getBlockX());
        meta.putInt("SpawnPosY", player.getWorld().getSpawnLocation().getBlockY());
        meta.putInt("SpawnPosZ", player.getWorld().getSpawnLocation().getBlockZ());
        meta.putInt("SpawnChunkRadius", 2);
        byte[] reply = PacketHandler.buildStructures(
                ProtocolConstants.Structures.S2C_SPAWN_METADATA,
                w -> w.writeNbt(meta));
        player.sendPluginMessage(plugin, ProtocolConstants.CHANNEL_STRUCTURES, reply);
    }
}
