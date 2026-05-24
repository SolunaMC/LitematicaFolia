package fr.ekaii.litematica;

import org.bukkit.plugin.java.JavaPlugin;

public final class LitematicaFolia extends JavaPlugin {

    private static LitematicaFolia instance;

    public static LitematicaFolia get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getLogger().info("LitematicaFolia enabling — schematics dir: " + getSchematicsDir());
        getLogger().info("Folia detected: " + fr.ekaii.litematica.paste.FoliaCompat.isFolia());
        getSchematicsDir().mkdirs();
        new fr.ekaii.litematica.command.LitematicaCommands(this).register();
        getLogger().info("LitematicaFolia ready.");
    }

    @Override
    public void onDisable() {
        instance = null;
    }

    public java.io.File getSchematicsDir() {
        String sub = getConfig().getString("schematic.directory", "schematics");
        return new java.io.File(getDataFolder(), sub);
    }
}
