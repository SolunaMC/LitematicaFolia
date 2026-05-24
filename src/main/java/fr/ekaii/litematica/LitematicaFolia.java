package fr.ekaii.litematica;

import org.bukkit.plugin.java.JavaPlugin;

public final class LitematicaFolia extends JavaPlugin {

    private static LitematicaFolia instance;
    private fr.ekaii.litematica.protocol.ServuxBridge servuxBridge;

    public static LitematicaFolia get() {
        return instance;
    }

    public fr.ekaii.litematica.protocol.ServuxBridge getServuxBridge() {
        return servuxBridge;
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
        getLogger().info("LitematicaFolia ready.");
    }

    @Override
    public void onDisable() {
        if (servuxBridge != null) {
            try { servuxBridge.disable(); } catch (Throwable ignored) {}
            servuxBridge = null;
        }
        instance = null;
    }

    public java.io.File getSchematicsDir() {
        String sub = getConfig().getString("schematic.directory", "schematics");
        return new java.io.File(getDataFolder(), sub);
    }
}
