package fr.ekaii.litematica.command;

import fr.ekaii.litematica.LitematicaFolia;

/**
 * Command-tree stub. A future agent (P1b) will wire {@code /litematica} and
 * its subcommands; this placeholder exists so the main plugin class
 * compiles while the core parser module (P1a) is in flight.
 */
public final class LitematicaCommands {

    private final LitematicaFolia plugin;

    public LitematicaCommands(LitematicaFolia plugin) {
        this.plugin = plugin;
    }

    /** Registers the command tree. Currently a no-op — see TODO above. */
    public void register() {
        plugin.getLogger().info("LitematicaCommands.register() — stub (P1b not yet implemented).");
    }
}
