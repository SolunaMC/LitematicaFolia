package fr.ekaii.litematica.integration;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CoreProtect-backed block-state logger.
 *
 * <p>CoreProtect API v10 introduced the thread-safe {@code BlockState}
 * placement/removal overloads used here:
 * https://docs.coreprotect.net/api/version/v10/#logplacementstring-user-blockstate-blockstate</p>
 */
public final class CoreProtectBlockChangeLogger implements BlockChangeLogger {

    private static final int MINIMUM_API_VERSION = 10;

    private final CoreProtectAPI api;
    private final Logger logger;
    private final AtomicBoolean warned = new AtomicBoolean(false);

    CoreProtectBlockChangeLogger(CoreProtectAPI api, Logger logger) {
        this.api = Objects.requireNonNull(api, "api");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Resolves the optional CoreProtect plugin after dependency loading.
     * Returns a no-op logger whenever the plugin or a compatible API is not
     * available, preserving normal paste behavior.
     */
    public static BlockChangeLogger connect(Plugin owner) {
        return connect(owner, owner.getServer().getPluginManager().getPlugin("CoreProtect"));
    }

    /**
     * Connects to a specific enabled plugin instance. The overload is used
     * by the plugin-enable listener so Paper/Bukkit mixed load phases cannot
     * lose a late CoreProtect activation between plugin-manager lookups.
     */
    public static BlockChangeLogger connect(Plugin owner, Plugin installed) {
        if (!(installed instanceof CoreProtect coreProtect)) {
            owner.getLogger().info("CoreProtect not found; paste block logging disabled.");
            return BlockChangeLogger.noOp();
        }

        CoreProtectAPI api = coreProtect.getAPI();
        if (api == null || !api.isEnabled()) {
            owner.getLogger().warning("CoreProtect API is disabled; paste block logging disabled.");
            return BlockChangeLogger.noOp();
        }
        if (api.APIVersion() < MINIMUM_API_VERSION) {
            owner.getLogger().warning("CoreProtect API v" + api.APIVersion()
                    + " is too old; v" + MINIMUM_API_VERSION + "+ is required for paste block logging.");
            return BlockChangeLogger.noOp();
        }

        owner.getLogger().info("CoreProtect paste block logging enabled (API v"
                + api.APIVersion() + ").");
        return new CoreProtectBlockChangeLogger(api, owner.getLogger());
    }

    @Override
    public void logBlockChange(String actor, BlockState before, BlockState after) {
        if (before == null || after == null) {
            warnOnce("CoreProtect skipped a paste record because a block-state snapshot was missing.", null);
            return;
        }

        try {
            if (sameState(before, after)) {
                return;
            }

            String user = actor == null || actor.isBlank() ? "#litematica" : actor;

            // Match CoreProtect's WorldEdit replacement semantics: remove the
            // old non-air state first, then place the new non-air state.
            if (!isAir(before.getType())) {
                attempt("removal", () -> api.logRemoval(user, before));
            }
            if (!isAir(after.getType())) {
                attempt("placement", () -> api.logPlacement(user, after));
            }
        } catch (Throwable failure) {
            warnOnce("CoreProtect failed while preparing a paste block record; the paste will continue.", failure);
        }
    }

    private static boolean sameState(BlockState before, BlockState after) {
        return before.getType() == after.getType()
                && before.getBlockData().getAsString().equals(after.getBlockData().getAsString());
    }

    private static boolean isAir(Material material) {
        return material == Material.AIR
                || material == Material.CAVE_AIR
                || material == Material.VOID_AIR;
    }

    private void attempt(String action, LogCall call) {
        try {
            if (!call.run()) {
                warnOnce("CoreProtect rejected a paste block " + action
                        + " record; the paste will continue.", null);
            }
        } catch (Throwable failure) {
            warnOnce("CoreProtect threw while logging a paste block " + action
                    + "; the paste will continue.", failure);
        }
    }

    private void warnOnce(String message, Throwable failure) {
        if (!warned.compareAndSet(false, true)) {
            return;
        }
        if (failure == null) {
            logger.warning(message);
        } else {
            logger.log(Level.WARNING, message, failure);
        }
    }

    @FunctionalInterface
    private interface LogCall {
        boolean run();
    }
}
