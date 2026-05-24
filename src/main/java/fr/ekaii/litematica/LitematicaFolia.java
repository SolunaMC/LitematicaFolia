package fr.ekaii.litematica;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class LitematicaFolia extends JavaPlugin implements Listener {

    private static LitematicaFolia instance;
    private fr.ekaii.litematica.protocol.ServuxBridge servuxBridge;
    private fr.ekaii.litematica.protocol.easyplace.EasyPlaceListener easyPlaceListener;
    private boolean packetEventsLoaded;

    public static LitematicaFolia get() {
        return instance;
    }

    public fr.ekaii.litematica.protocol.ServuxBridge getServuxBridge() {
        return servuxBridge;
    }

    @Override
    public void onLoad() {
        // PacketEvents must be set up in onLoad so its packet listeners
        // can be wired before any player connects. Guarded — a missing
        // PacketEvents class (relocation gone wrong on a weird
        // classloader, Folia shim incompatibility, …) must NOT prevent
        // the rest of the plugin from loading. The Easy Place listener
        // will simply not be registered in that case.
        try {
            PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
            PacketEvents.getAPI().load();
            packetEventsLoaded = true;
        } catch (Throwable t) {
            getLogger().warning("PacketEvents load failed — Easy Place V3 listener disabled: " + t);
            packetEventsLoaded = false;
        }
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
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
        // protocol.enableEasyPlace (default false). PacketEvents must be
        // init'd regardless of whether the Easy Place listener is
        // registered, because once load() succeeded the api expects a
        // matching init/terminate pair.
        if (packetEventsLoaded) {
            try {
                PacketEvents.getAPI().init();
            } catch (Throwable t) {
                getLogger().warning("PacketEvents init failed: " + t);
                packetEventsLoaded = false;
            }
        }
        if (packetEventsLoaded
                && getConfig().getBoolean("protocol.enableEasyPlace", false)) {
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
        } else if (packetEventsLoaded) {
            getLogger().info("Easy Place V3 listener disabled (protocol.enableEasyPlace=false).");
        }
        getLogger().info("LitematicaFolia ready.");
    }

    /** Log channels a player declares — useful to detect Servux-capable clients in the wild. */
    @EventHandler
    public void onChannelRegister(PlayerRegisterChannelEvent e) {
        String ch = e.getChannel();
        if (ch.startsWith("servux:") || ch.startsWith("litematica") || ch.startsWith("malilib")) {
            getLogger().info("[diag] " + e.getPlayer().getName() + " registered channel: " + ch);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Dump after a short delay so the client has time to send its registers.
        org.bukkit.Bukkit.getAsyncScheduler().runDelayed(this, t -> {
            var chans = e.getPlayer().getListeningPluginChannels();
            String summary = chans.stream()
                    .filter(c -> c.startsWith("servux:") || c.startsWith("litematica") || c.startsWith("malilib") || c.startsWith("minecraft:"))
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(none of interest)");
            getLogger().info("[diag] " + e.getPlayer().getName() + " channels after 5s: " + summary);
        }, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        if (servuxBridge != null) {
            try { servuxBridge.disable(); } catch (Throwable ignored) {}
            servuxBridge = null;
        }
        if (packetEventsLoaded) {
            try { PacketEvents.getAPI().terminate(); } catch (Throwable ignored) {}
            packetEventsLoaded = false;
        }
        easyPlaceListener = null;
        instance = null;
    }

    public java.io.File getSchematicsDir() {
        String sub = getConfig().getString("schematic.directory", "schematics");
        return new java.io.File(getDataFolder(), sub);
    }
}
