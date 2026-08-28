package fr.ekaii.litematica.paste;

import fr.ekaii.litematica.core.LitematicSchematic;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the issue #4.7 batching contract with a real assertion instead of
 * trusting the code: a chunk whose write list exceeds
 * {@code paste.maxBlocksPerChunkTask} MUST be split into multiple region
 * tasks (a genuine yield back to the region scheduler between batches),
 * and a list within the cap must run as a single task.
 *
 * <p>The region scheduler is substituted through the package-private
 * {@link PasteOperation.RegionTaskDispatcher} seam with a synchronous
 * dispatcher that counts dispatches, so the whole batch chain runs
 * deterministically without a server. World/Block are reflective stubs;
 * every write "succeeds" so the batching walk sees realistic conditions.
 */
class PasteBatchingTest {

    private static World stubWorld() {
        Block block = (Block) Proxy.newProxyInstance(
                PasteBatchingTest.class.getClassLoader(),
                new Class<?>[] {Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setBlockData" -> null;               // void write, "succeeds"
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "StubBlock";
                    default -> defaultValue(method.getReturnType());
                });
        return (World) Proxy.newProxyInstance(
                PasteBatchingTest.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBlockAt" -> block;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "StubWorld";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == long.class) return 0L;
        return 0;
    }

    private static PasteOperation newOperation(int maxBlocksPerChunkTask) {
        PasteOptions options = new PasteOptions(
                new Location(null, 0, 0, 0),
                /* placeEntities */     true,
                /* placeTileEntities */ true,
                /* placePendingTicks */ true,
                /* deferredPhysics */   true,
                /* observersLast */     true,
                maxBlocksPerChunkTask,
                PlacementTransform.Rot.NONE,
                PlacementTransform.Mir.NONE,
                ReplaceBehavior.ALL,
                /* layerFilter */ null,
                /* subRegions */  Map.of(),
                /* progress */    null);
        return new PasteOperation(null, new LitematicSchematic(), options);
    }

    private static List<PasteOperation.PendingWrite> writes(int n) {
        List<PasteOperation.PendingWrite> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new PasteOperation.PendingWrite(i, 64, 0, null));
        }
        return out;
    }

    /** Runs one chunk chain to completion and returns the dispatch count. */
    private static int runChain(PasteOperation op, int writeCount, AtomicLong blockCounter) {
        AtomicInteger dispatches = new AtomicInteger();
        op.setRegionTaskDispatcherForTest((world, cx, cz, task) -> {
            dispatches.incrementAndGet();
            task.run();
            return CompletableFuture.completedFuture(null);
        });
        AtomicBoolean tailRan = new AtomicBoolean();
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
        CompletableFuture<Void> done = op.runChunkBatched(stubWorld(),
                new PasteOperation.ChunkKey(0, 0), writes(writeCount),
                () -> tailRan.set(true), blockCounter, errors, "test");
        assertTrue(done.isDone(), "batch chain must complete");
        assertTrue(tailRan.get(), "tail must run after the FINAL batch, not earlier");
        assertTrue(errors.isEmpty(), "no write errors expected, got: " + errors);
        return dispatches.get();
    }

    @Test
    void chunk_exceeding_max_blocks_yields_into_multiple_region_tasks() {
        // 65 writes at 16/task: 5 region tasks (16+16+16+16+1), i.e. the
        // chain yielded back to the scheduler 4 times.
        PasteOperation op = newOperation(16);
        AtomicLong placed = new AtomicLong();
        assertEquals(5, runChain(op, 65, placed));
        assertEquals(65, placed.get(), "every write must land exactly once across batches");
        assertEquals(5, op.batchTasksDispatched());
    }

    @Test
    void chunk_within_max_blocks_runs_as_a_single_region_task() {
        assertEquals(1, runChain(newOperation(16), 16, new AtomicLong()));
    }

    @Test
    void one_write_over_the_cap_forces_exactly_one_yield() {
        assertEquals(2, runChain(newOperation(16), 17, new AtomicLong()));
    }
}
