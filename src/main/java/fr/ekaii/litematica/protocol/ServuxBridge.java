package fr.ekaii.litematica.protocol;

import fr.ekaii.litematica.nms.NmsBridge;
import fr.ekaii.litematica.protocol.handler.BlockEntityRequestHandler;
import fr.ekaii.litematica.protocol.handler.BulkNbtRequestHandler;
import fr.ekaii.litematica.protocol.handler.DirectPasteHandler;
import fr.ekaii.litematica.protocol.handler.EasyPlaceHandler;
import fr.ekaii.litematica.protocol.handler.EntityRequestHandler;
import fr.ekaii.litematica.protocol.handler.MetadataHandler;
import fr.ekaii.litematica.protocol.handler.StructureBboxHandler;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Servux-compatible custom-payload bridge. Registers the
 * {@code servux:litematics}, {@code servux:structures} and
 * {@code litematicafolia:easy_place} plugin-message channels on enable so
 * a vanilla Litematica client (with Servux installed) can interact with
 * this Paper/Folia server.
 *
 * <h2>Transport</h2>
 * We use Paper's plugin-messaging API
 * ({@link org.bukkit.plugin.messaging.Messenger}) rather than Netty
 * pipeline injection. Paper's Messenger directly bridges to Minecraft's
 * custom-payload channel system, so {@code register*PluginChannel} on a
 * channel like {@code servux:litematics} makes any matching payload —
 * sent by a Servux-equipped client — surface as
 * {@link org.bukkit.plugin.messaging.PluginMessageListener#onPluginMessageReceived}
 * with the raw bytes as Servux wrote them. This is simpler than Netty
 * pipeline injection, Folia-safe (no per-player pipeline state to clean
 * up), and avoids touching internal Paper classes.
 *
 * <h2>Lifecycle</h2>
 * Wire {@link #enable(Plugin)} from {@code onEnable} after
 * {@code saveDefaultConfig()}. {@link #disable()} unregisters every
 * channel. Re-enabling is supported (config reload).
 *
 * <h2>Config gate</h2>
 * The bridge reads {@code protocol.enableServuxBridge} from the plugin
 * config and refuses to wire anything if that flag is {@code false}.
 * Disabled by default; flip on once smoke-tested against a real
 * Litematica client.
 */
public final class ServuxBridge {

    private static final Logger LOG = Logger.getLogger("LitematicaFolia/ServuxBridge");

    private final Plugin plugin;
    private final PacketHandler packetHandler;
    private final MetadataHandler metadataHandler;
    private final EasyPlaceHandler easyPlaceHandler;
    private final DirectPasteHandler directPasteHandler;
    private final StructureBboxHandler structureBboxHandler;
    private final BlockEntityRequestHandler blockEntityRequestHandler;
    private final EntityRequestHandler entityRequestHandler;
    private final BulkNbtRequestHandler bulkNbtRequestHandler;

    private volatile boolean enabled;

    public ServuxBridge(Plugin plugin) {
        this.plugin = plugin;
        NmsBridge nms = NmsBridge.get();
        this.metadataHandler    = new MetadataHandler(plugin);
        this.easyPlaceHandler   = new EasyPlaceHandler(plugin);
        this.directPasteHandler = new DirectPasteHandler(plugin);
        this.structureBboxHandler = new StructureBboxHandler(plugin);
        this.blockEntityRequestHandler = new BlockEntityRequestHandler(plugin, nms);
        this.entityRequestHandler      = new EntityRequestHandler(plugin, nms);
        this.bulkNbtRequestHandler     = new BulkNbtRequestHandler(plugin, nms);
        this.packetHandler = new PacketHandler(
                plugin,
                metadataHandler,
                easyPlaceHandler,
                directPasteHandler,
                structureBboxHandler,
                blockEntityRequestHandler,
                entityRequestHandler,
                bulkNbtRequestHandler);
    }

    /**
     * Registers the custom-payload channels with Paper and starts handling
     * inbound packets. No-op if the {@code protocol.enableServuxBridge}
     * config flag is false.
     *
     * @return whether the bridge actually came online.
     */
    public boolean enable(Plugin owner) {
        if (enabled) return true;

        boolean flag = plugin.getConfig().getBoolean("protocol.enableServuxBridge", false);
        if (!flag) {
            LOG.info("Servux bridge disabled (protocol.enableServuxBridge=false).");
            return false;
        }

        Messenger messenger = plugin.getServer().getMessenger();
        try {
            messenger.registerOutgoingPluginChannel(owner, ProtocolConstants.CHANNEL_LITEMATICS);
            messenger.registerIncomingPluginChannel(owner, ProtocolConstants.CHANNEL_LITEMATICS,
                    packetHandler::onLitematicsPacket);

            messenger.registerOutgoingPluginChannel(owner, ProtocolConstants.CHANNEL_STRUCTURES);
            messenger.registerIncomingPluginChannel(owner, ProtocolConstants.CHANNEL_STRUCTURES,
                    packetHandler::onStructuresPacket);

            // Easy Place V3 is NOT a custom Servux packet — Servux drives
            // it via the X-fractional component of the vanilla
            // ServerboundUseItemOnPacket. The litematicafolia:easy_place
            // channel was a draft idea; not part of the on-wire Servux
            // protocol. We therefore do NOT register it by default. If
            // protocol.enableEasyPlaceChannel is explicitly set we honour
            // the legacy registration so smoke tests can still exercise
            // the dispatcher.
            if (plugin.getConfig().getBoolean("protocol.enableEasyPlaceChannel", false)) {
                messenger.registerOutgoingPluginChannel(owner, ProtocolConstants.CHANNEL_EASY_PLACE);
                messenger.registerIncomingPluginChannel(owner, ProtocolConstants.CHANNEL_EASY_PLACE,
                        packetHandler::onEasyPlacePacket);
                LOG.info("Vendor channel " + ProtocolConstants.CHANNEL_EASY_PLACE + " registered (legacy)");
            }
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "Failed to register Servux channels", t);
            return false;
        }

        enabled = true;
        LOG.info("Servux bridge online — channels: " + ProtocolConstants.CHANNEL_LITEMATICS
                + ", " + ProtocolConstants.CHANNEL_STRUCTURES);
        return true;
    }

    /** Tears down every channel. Safe to call when not enabled. */
    public void disable() {
        if (!enabled) return;
        try {
            Messenger messenger = plugin.getServer().getMessenger();
            messenger.unregisterIncomingPluginChannel(plugin, ProtocolConstants.CHANNEL_LITEMATICS);
            messenger.unregisterOutgoingPluginChannel(plugin, ProtocolConstants.CHANNEL_LITEMATICS);
            messenger.unregisterIncomingPluginChannel(plugin, ProtocolConstants.CHANNEL_STRUCTURES);
            messenger.unregisterOutgoingPluginChannel(plugin, ProtocolConstants.CHANNEL_STRUCTURES);
            // Easy place channel may or may not have been registered;
            // unregistering an unregistered channel is a silent no-op on
            // Paper.
            try {
                messenger.unregisterIncomingPluginChannel(plugin, ProtocolConstants.CHANNEL_EASY_PLACE);
                messenger.unregisterOutgoingPluginChannel(plugin, ProtocolConstants.CHANNEL_EASY_PLACE);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Cleanup error", t);
        }
        enabled = false;
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Convenience send-to-player on the Litematics channel. No-op if the
     * bridge is disabled or the player does not declare itself as a
     * listener on the channel.
     */
    public void sendLitematics(Player player, byte[] payload) {
        sendOnChannel(player, ProtocolConstants.CHANNEL_LITEMATICS, payload);
    }

    public void sendEasyPlace(Player player, byte[] payload) {
        sendOnChannel(player, ProtocolConstants.CHANNEL_EASY_PLACE, payload);
    }

    public void sendStructures(Player player, byte[] payload) {
        sendOnChannel(player, ProtocolConstants.CHANNEL_STRUCTURES, payload);
    }

    private void sendOnChannel(Player player, String channel, byte[] payload) {
        if (!enabled) return;
        try {
            // Server can always send — Paper's Messenger will silently
            // drop the packet if the player hasn't declared the channel,
            // which matches Servux behaviour for a vanilla client.
            player.sendPluginMessage(plugin, channel, payload);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "send on " + channel + " failed", t);
        }
    }
}
