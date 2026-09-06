package fr.ekaii.litematica;

import fr.ekaii.litematica.integration.BlockChangeLogger;
import fr.ekaii.litematica.integration.CoreProtectBlockChangeLogger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class LitematicaFolia extends JavaPlugin implements Listener {

    private static LitematicaFolia instance;
    private fr.ekaii.litematica.protocol.ServuxBridge servuxBridge;
    private fr.ekaii.litematica.protocol.easyplace.EasyPlaceListener easyPlaceListener;
    private boolean supportedServer;
    private volatile BlockChangeLogger blockChangeLogger = BlockChangeLogger.noOp();

    public static LitematicaFolia get() {
        return instance;
    }

    public fr.ekaii.litematica.protocol.ServuxBridge getServuxBridge() {
        return servuxBridge;
    }

    public BlockChangeLogger getBlockChangeLogger() {
        return blockChangeLogger;
    }

    /**
     * This build drives NMS through NmsBridge26_2 and injects a Netty handler
     * that reads 26.2 packet records, so it must refuse to run anywhere else.
     * Bukkit's api-version does NOT reject a plugin NEWER than the server
     * (Paper and Leaves 1.21.11 both enable this 26.2 build without a
     * complaint), and a version-mismatched packet pipeline corrupts client
     * connections instead of failing cleanly (issue #3: every join on a
     * Leaves 1.21.11 server died on a garbage clientbound packet).
     */
    private static boolean isSupportedServerVersion(String mc) {
        return mc.equals("26.2") || mc.startsWith("26.2.");
    }

    @Override
    public void onLoad() {
        supportedServer = isSupportedServerVersion(getServer().getMinecraftVersion());
        if (!supportedServer) {
            getLogger().severe("Unsupported Minecraft version " + getServer().getMinecraftVersion()
                    + ": this build supports 26.2 only. Use the 0.4.x releases for 26.1.x;"
                    + " 1.21.x and older are not supported at all. The plugin will disable itself on enable.");
        }
    }

    @Override
    public void onEnable() {
        if (!supportedServer) {
            getLogger().severe("LitematicaFolia " + getPluginMeta().getVersion()
                    + " does not support Minecraft " + getServer().getMinecraftVersion()
                    + ". Supported: 26.2 (this build). For 26.1.x use the 0.4.x releases;"
                    + " 1.21.x and older are unsupported. Disabling to avoid breaking client"
                    + " connections.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        instance = this;
        saveDefaultConfig();
        initializeBlockChangeLogger();
        getLogger().info("LitematicaFolia enabling — schematics dir: " + getSchematicsDir());
        getLogger().info("Folia detected: " + fr.ekaii.litematica.paste.FoliaCompat.isFolia());
        getSchematicsDir().mkdirs();
        new fr.ekaii.litematica.command.LitematicaCommands(this).register();
        // Servux-compatible custom payload bridge. Reads
        // protocol.enableServuxBridge from config; gated off by default
        // until smoke-tested against a real Litematica client.
        servuxBridge = new fr.ekaii.litematica.protocol.ServuxBridge(this);
        servuxBridge.enable(this);
        getServer().getPluginManager().registerEvents(this, this);

        // Easy Place V3 server-side. Gated by protocol.enableEasyPlace
        // (default false). Implemented as a per-connection Netty handler on
        // the server's own packet classes (no PacketEvents, nothing shaded:
        // issue #5), injected on join and removed on quit / disable. When
        // the feature is off nothing touches the network pipeline.
        if (getConfig().getBoolean("protocol.enableEasyPlace", false)) {
            try {
                easyPlaceListener = new fr.ekaii.litematica.protocol.easyplace.EasyPlaceListener(this);
                easyPlaceListener.enable();
                getLogger().info("Easy Place V3 listener online (protocol.enableEasyPlace=true).");
            } catch (Throwable t) {
                getLogger().log(java.util.logging.Level.WARNING,
                        "Easy Place V3 listener registration failed; feature stays off.", t);
                easyPlaceListener = null;
            }
        } else {
            getLogger().info("Easy Place V3 listener disabled (protocol.enableEasyPlace=false); network pipeline untouched.");
        }
        getLogger().info("LitematicaFolia ready.");
    }

    /** Verbose: log EVERY channel a player declares on this server. */
    @EventHandler
    public void onChannelRegister(PlayerRegisterChannelEvent e) {
        getLogger().info("[diag-net] +register " + e.getPlayer().getName() + " channel=" + e.getChannel());
    }

    @EventHandler
    public void onChannelUnregister(org.bukkit.event.player.PlayerUnregisterChannelEvent e) {
        getLogger().info("[diag-net] -unregister " + e.getPlayer().getName() + " channel=" + e.getChannel());
    }

    /** Drop per-player protocol state (wire version, partial paste streams). */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        if (servuxBridge != null) {
            servuxBridge.onPlayerQuit(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Initial dump on join (channels declared during config phase).
        getLogger().info("[diag-net] " + e.getPlayer().getName()
                + " joined; channels @ join = " + String.join(", ", e.getPlayer().getListeningPluginChannels()));
        // Push Servux metadata proactively after the channel registration
        // settles (~3s). Litematica 0.27.x doesn't auto-send the metadata
        // request on join — pushing it server-initiated flips its
        // servuxRegistered flag so the Server-side paste menu options
        // surface and the client stops falling back to /fill spam.
        org.bukkit.Bukkit.getAsyncScheduler().runDelayed(this, t -> {
            if (e.getPlayer().isOnline() && servuxBridge != null && servuxBridge.isEnabled()
                    && e.getPlayer().getListeningPluginChannels()
                            .contains(fr.ekaii.litematica.protocol.ProtocolConstants.CHANNEL_LITEMATICS)) {
                servuxBridge.getMetadataHandler().pushMetadata(e.getPlayer());
            }
        }, 3, java.util.concurrent.TimeUnit.SECONDS);
        // Re-dump after 5s and 30s so we catch lazy/late registrations.
        org.bukkit.Bukkit.getAsyncScheduler().runDelayed(this, t ->
                getLogger().info("[diag-net] " + e.getPlayer().getName()
                        + " channels @ +5s = " + String.join(", ", e.getPlayer().getListeningPluginChannels())),
                5, java.util.concurrent.TimeUnit.SECONDS);
        org.bukkit.Bukkit.getAsyncScheduler().runDelayed(this, t ->
                getLogger().info("[diag-net] " + e.getPlayer().getName()
                        + " channels @ +30s = " + String.join(", ", e.getPlayer().getListeningPluginChannels())),
                30, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Paper can enable this STARTUP-phase plugin before an optional
     * POSTWORLD Bukkit dependency. Retry the CoreProtect hook when that
     * plugin actually becomes enabled instead of keeping the startup no-op
     * logger for the lifetime of the server.
     */
    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        Plugin enabledPlugin = event.getPlugin();
        if (!blockChangeLogger.isEnabled()
                && "CoreProtect".equals(enabledPlugin.getName())) {
            initializeBlockChangeLogger(enabledPlugin);
        }
    }

    @Override
    public void onDisable() {
        if (servuxBridge != null) {
            try { servuxBridge.disable(); } catch (Throwable ignored) {}
            servuxBridge = null;
        }
        if (easyPlaceListener != null) {
            try { easyPlaceListener.disable(); } catch (Throwable ignored) {}
            easyPlaceListener = null;
        }
        blockChangeLogger = BlockChangeLogger.noOp();
        instance = null;
    }

    /**
     * Loads the CoreProtect-linked class only when the optional plugin is
     * actually present, so servers without CoreProtect keep a clean no-op
     * path and never need its classes.
     */
    private void initializeBlockChangeLogger() {
        initializeBlockChangeLogger(
                getServer().getPluginManager().getPlugin("CoreProtect"));
    }

    private void initializeBlockChangeLogger(Plugin coreProtect) {
        if (!getConfig().getBoolean("logging.coreprotect", true)) {
            getLogger().info("CoreProtect paste block logging disabled by config (logging.coreprotect: false).");
            blockChangeLogger = BlockChangeLogger.noOp();
            return;
        }
        if (coreProtect == null) {
            getLogger().info("CoreProtect not found yet; paste block logging will activate if it becomes available.");
            blockChangeLogger = BlockChangeLogger.noOp();
            return;
        }
        if (!coreProtect.isEnabled()) {
            getLogger().info("CoreProtect detected but not enabled yet; paste block logging is waiting for it.");
            blockChangeLogger = BlockChangeLogger.noOp();
            return;
        }

        try {
            blockChangeLogger = CoreProtectBlockChangeLogger.connect(this, coreProtect);
        } catch (Throwable failure) {
            blockChangeLogger = BlockChangeLogger.noOp();
            getLogger().log(java.util.logging.Level.WARNING,
                    "CoreProtect integration could not be initialized; paste behavior is unchanged.",
                    failure);
        }
    }

    public java.io.File getSchematicsDir() {
        String sub = getConfig().getString("schematic.directory", "schematics");
        return new java.io.File(getDataFolder(), sub);
    }
}
