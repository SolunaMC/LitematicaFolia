package fr.ekaii.litematica.integration;

import org.bukkit.block.BlockState;

/**
 * Optional audit sink for direct block changes made by a paste.
 *
 * <p>Implementations must be fail-open: auditing must never change whether a
 * schematic block is placed.</p>
 */
public interface BlockChangeLogger {

    BlockChangeLogger NO_OP = new BlockChangeLogger() {
        @Override
        public void logBlockChange(String actor, BlockState before, BlockState after) {
        }

        @Override
        public boolean isEnabled() {
            return false;
        }
    };

    /** Records one completed block-state transition. */
    void logBlockChange(String actor, BlockState before, BlockState after);

    /** True when callers should incur the cost of capturing snapshots. */
    default boolean isEnabled() {
        return true;
    }

    /** Returns a logger that deliberately ignores all changes. */
    static BlockChangeLogger noOp() {
        return NO_OP;
    }
}
