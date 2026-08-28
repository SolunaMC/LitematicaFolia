package fr.ekaii.litematica;

import com.github.retrooper.packetevents.PacketEvents;
import fr.ekaii.litematica.integration.BlockChangeLogger;
import fr.ekaii.litematica.integration.CoreProtectBlockChangeLogger;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
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
    private boolean packetEventsLoaded;
    private boolean packetEventsInited;
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
     * This build drives NMS through NmsBridge_1_21_11 and ships
     * PacketEvents mapped for the 1.21.11 wire (protocol 774), so it must
     * refuse to run anywhere else. Bukkit's api-version does NOT reject a
     * plugin NEWER than the server, and a version-mismatched packet
     * pipeline corrupts client connections instead of failing cleanly
     * (issue #3 / issue #5: joins died on a garbage clientbound packet /
     * "void future").
     */
    private static boolean isSupportedServerVersion(String mc) {
        return mc.equals("1.21.11") || mc.startsWith("1.21.11");
    }

    @Override
    public void onLoad() {
        supportedServer = isSupportedServerVersion(getServer().getMinecraftVersion());
        if (!supportedServer) {
            getLogger().severe("Unsupported Minecraft version " + getServer().getMinecraftVersion()
                    + ": this build supports 1.21.11 only. Use the 0.6.x/0.8.x releases for 26.2,"
                    + " the 0.4.x releases for 26.1.x; anything else is not supported at all."
                    + " PacketEvents stays out of the pipeline and the plugin will disable"
                    + " itself on enable.");
            return;
        }
        // PacketEvents must be set up in onLoad so its packet listeners
        // can be wired before any player connects. Guarded — a missing
        // PacketEvents class (relocation gone wrong on a weird
        // classloader, Folia shim incompatibility, …) must NOT prevent
        // the rest of the plugin from loading. The Easy Place listener
        // will simply not be registered in that case.
        try {
            PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
            // No update checks: shaded lib, updates come with plugin releases —
            // and the 2.13 checker thread NoClassDefFoundErrors against the
            // adventure-api Paper 26.2 ships (Buildable was removed upstream).
            PacketEvents.getAPI().getSettings().checkForUpdates(false);
            PacketEvents.getAPI().load();
            packetEventsLoaded = true;
        } catch (Throwable t) {
            getLogger().warning("PacketEvents load failed — Easy Place V3 listener disabled: " + t);
            packetEventsLoaded = false;
        }
    }

    @Override
    public void onEnable() {
        if (!supportedServer) {
            getLogger().severe("LitematicaFolia " + getPluginMeta().getVersion()
                    + " does not support Minecraft " + getServer().getMinecraftVersion()
                    + ". Supported: 1.21.11 (this build). For 26.2 use the 0.6.x/0.8.x releases;"
                    + " for 26.1.x use the 0.4.x releases; anything else is unsupported."
                    + " Disabling to avoid breaking client connections.");
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

        // Easy Place V3 server-side via PacketEvents. Gated by
        // protocol.enableEasyPlace (default false). init() is what injects
        // PacketEvents into every connection's netty pipeline, and Easy
        // Place is the ONLY consumer, so we no longer init when the
        // feature is off: the Servux bridge runs on the Bukkit Messenger
        // alone and a dormant pipeline injector is pure risk (issue #3).
        // terminate() in onDisable is gated on packetEventsInited to match.
        boolean easyPlaceWanted = getConfig().getBoolean("protocol.enableEasyPlace", false);
        if (packetEventsLoaded && easyPlaceWanted) {
            try {
                PacketEvents.getAPI().init();
                packetEventsInited = true;
            } catch (Throwable t) {
                getLogger().warning("PacketEvents init failed: " + t);
            }
        }
        if (packetEventsInited) {
            try {
                easyPlaceListener =
                        new fr.ekaii.litematica.protocol.easyplace.EasyPlaceListener(this);
                PacketEvents.getAPI().getEventManager().registerListener(easyPlaceListener);
                getServer().getPluginManager().registerEvents(easyPlaceListener, this);
                getLogger().info("Easy Place V3 listener online (protocol.enableEasyPlace=true).");
            } catch (Throwable t) {
                getLogger().warning("Easy Place V3 listener registration failed: " + t);
                easyPlaceListener = null;
            }
        } else if (easyPlaceWanted) {
            getLogger().warning("Easy Place V3 requested but PacketEvents is unavailable; feature stays off.");
        } else {
            getLogger().info("Easy Place V3 listener disabled (protocol.enableEasyPlace=false); PacketEvents left uninjected.");
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
        if (packetEventsInited) {
            try { PacketEvents.getAPI().terminate(); } catch (Throwable ignored) {}
            packetEventsInited = false;
        }
        packetEventsLoaded = false;
        easyPlaceListener = null;
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
