package fr.ekaii.litematica.paste;

import fr.ekaii.litematica.LitematicaFolia;
import fr.ekaii.litematica.core.BlockStateEntry;
import fr.ekaii.litematica.core.LitematicNbt;
import fr.ekaii.litematica.core.LitematicRegion;
import fr.ekaii.litematica.core.LitematicSchematic;
import fr.ekaii.litematica.integration.BlockChangeLogger;
import fr.ekaii.litematica.nms.NmsBridge;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Folia-safe paste operation. Reads a {@link LitematicSchematic} and writes
 * it into the world by dispatching one task per affected chunk onto that
 * chunk's owning region (via {@link FoliaCompat#runOnRegion}).
 *
 * <h2>Why per-chunk dispatch?</h2>
 * Direct ports of axiom-paper-folia's regionfile-corruption fix
 * ({@code SetBlockBufferOperation}, May 2026): writing to a chunk's
 * sections / heightmaps / block-entity map from a non-owning thread races
 * with Paper's I/O Worker pool flushing the chunk to its regionfile —
 * exactly the race that corrupted creaclone's {@code r.5.11.mca} /
 * {@code r.6.10.mca} / {@code r.6.12.mca} / {@code r.7.11.mca} when Axiom
 * v0.1.8 was deployed.
 *
 * <h2>Two-pass placement (observersLast)</h2>
 * The {@link #ACTIVE_BLOCK_NAMES} set covers blocks that fire feedback
 * loops if placed in random order (observers, pistons, comparators,
 * repeaters, redstone wire, sticky pistons). When
 * {@link PasteOptions#observersLast()} is true the operation runs the
 * placement pipeline twice: first with all "active" blocks replaced by
 * air, then a second pass that overwrites only those positions with the
 * actual active state. This mirrors the workaround from Litematica
 * issue #538.
 *
 * <h2>Deferred physics</h2>
 * Block writes use the {@code applyPhysics=false} Bukkit overload. After
 * all chunks complete, a single sweep across the bounding box calls
 * {@link BlockState#update(boolean, boolean)} to trigger physics in a
 * controlled order. This avoids cascading updates during placement.
 */
public final class PasteOperation {

    private static final Logger LOG = Logger.getLogger("LitematicaFolia/PasteOperation");

    /**
     * In-flight chunk-task throttle (v0.4.0).
     *
     * <p>Folia's RegionScheduler accepts an unbounded queue of region-bound
     * runnables. A large paste (100+ chunks, observed on creaclone 2026-05-25)
     * previously fired every {@link FoliaCompat#runOnRegion} call inside a
     * tight loop in &lt;1 ms with zero throttle — each chunk's owning region
     * then had to drain its queue while Paper's I/O Worker pool tried to flush
     * region files in parallel. Combined with markUnsaved storms, this
     * cascaded into the region-file corruption events tracked in the audit at
     * {@code /tmp/audit-&#42;/}.
     *
     * <p>This semaphore caps the number of chunk tasks that have been
     * dispatched but not yet run (or are currently running) at any moment.
     * It is acquired synchronously in {@link #execute()} before each
     * {@code runOnRegion} call — when 32 tasks are already in flight, the
     * dispatching thread blocks until one completes. This mirrors the
     * {@code MAX_CHUNK_FUTURES = 256} cap used by Axiom's
     * {@code SetBlockBufferOperation}, scaled down because Litematica pastes
     * are typically smaller per-chunk than an Axiom buffer paste.
     *
     * <p>The permit is released either:
     * <ul>
     *   <li>Inside the runnable's {@code finally} block (normal path), OR</li>
     *   <li>Via {@link CompletableFuture#whenComplete} on the future returned
     *       by {@link FoliaCompat#runOnRegion} (covers the path where the
     *       scheduler itself throws and the lambda never runs).</li>
     * </ul>
     * Both paths use {@code tryAcquire}-style accounting via
     * {@link #acquireSlot()} / {@link #releaseSlot} so a release that fires
     * twice is a no-op.
     *
     * <p><strong>v0.4.2 hardening.</strong> {@link #acquireSlot()} now waits at
     * most {@link #ACQUIRE_TIMEOUT_MS} and then proceeds unthrottled, so a lost
     * permit can degrade the throttle but can never park a dispatch thread
     * <em>indefinitely</em> — the exact "Semaphore never released → thread
     * blocked forever" failure reported for creaclone (Folia issue #1). Paired
     * with the off-region continuation dispatch (see {@link #offRegionExecutor}),
     * this closes the region-thread self-deadlock for good.
     */
    private static final Semaphore CHUNK_THROTTLE = new Semaphore(32, true);

    /**
     * Upper bound on how long a dispatch thread waits for a throttle permit
     * before giving up and proceeding <em>without</em> one. A single chunk task
     * completes in well under a second, so a multi-second wait only happens
     * under extreme in-flight pressure or an actual permit leak. In either case
     * dispatching one chunk unthrottled is strictly safer than blocking a
     * thread forever (the creaclone freeze). Deliberately generous so it never
     * fires during normal operation.
     */
    private static final long ACQUIRE_TIMEOUT_MS = 60_000L;

    /** Blocks whose physics behaviour requires "observers-last" placement. */
    public static final Set<String> ACTIVE_BLOCK_NAMES = Set.of(
            "minecraft:observer",
            "minecraft:piston",
            "minecraft:sticky_piston",
            "minecraft:comparator",
            "minecraft:repeater",
            "minecraft:redstone_wire"
    );

    private final Plugin plugin;
    private final LitematicSchematic schematic;
    private final PasteOptions options;
    private final NmsBridge nms;
    private final String actorName;
    private final BlockChangeLogger blockChangeLogger;

    /**
     * Off-region executor for the pass-2 and physics-sweep dispatch loops
     * (v0.4.2 fix for creaclone 2026-06-04..11 freeze).
     *
     * <p>Those loops call the <em>blocking</em> {@link #acquireSlot()} and MUST
     * NOT run on a Folia region tick thread — see the {@link #execute()}
     * javadoc. A plain {@code thenCompose} continuation runs on whatever thread
     * completed the upstream future, which for {@link FoliaCompat#runOnRegion}
     * is a region tick thread. Routing those continuations through this
     * executor (Folia async pool / Paper async worker) keeps every
     * {@code acquireSlot} call off-region, so blocking on the throttle can
     * never park a region and trip the Folia Watchdog.
     */
    private final java.util.concurrent.Executor offRegionExecutor;

    /**
     * Cooperative cancellation flag. Per-chunk tasks check this between writes
     * (and at chunk-task entry). When flipped to {@code true}, no further writes
     * are issued and the operation completes early with whatever has been
     * placed so far. An entry is added to {@link PasteResult#errors()}.
     *
     * <p>Memory model: writes from {@link #cancel()} are visible to every chunk
     * worker thanks to the {@link AtomicBoolean} happens-before.
     *
     * <p>TODO: paste hot path currently materialises full palette + block grid
     * in memory; a {@code --stream} flag for very large schematics is future
     * work (would read+place region-by-region instead of pre-planning).
     */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public PasteOperation(Plugin plugin, LitematicSchematic schematic, PasteOptions options) {
        this(plugin, schematic, options, "#litematica");
    }

    public PasteOperation(Plugin plugin, LitematicSchematic schematic, PasteOptions options,
                          String actorName) {
        this.plugin = plugin;
        this.schematic = schematic;
        this.options = options;
        this.nms = NmsBridge.get();
        this.actorName = actorName == null || actorName.isBlank() ? "#litematica" : actorName;
        LitematicaFolia owner = LitematicaFolia.get();
        this.blockChangeLogger = owner == null
                ? BlockChangeLogger.noOp()
                : owner.getBlockChangeLogger();
        this.offRegionExecutor = command -> FoliaCompat.runAsync(plugin, command);
    }

    /**
     * Cooperative cancellation. Flips the {@link #cancelled} flag; in-flight
     * chunk tasks will short-circuit on their next per-block iteration. This
     * does NOT abort already-scheduled region tasks — Folia's RegionScheduler
     * has no API for that — but each task exits very quickly once the flag is
     * set.
     *
     * <p>Returns true if this call actually flipped the flag (caller was first
     * to cancel).
     */
    public boolean cancel() {
        boolean firstToCancel = cancelled.compareAndSet(false, true);
        if (firstToCancel) {
            reportProgress("paste cancellation requested");
        }
        return firstToCancel;
    }

    /** True once {@link #cancel()} has been invoked. */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Kicks off the paste. The returned future completes when every
     * per-chunk task has finished. Errors collected along the way are
     * delivered in {@link PasteResult#errors()}.
     *
     * <p><strong>Threading contract (v0.4.1 fix for creaclone 2026-05-28 freeze).</strong>
     * The dispatch loop calls {@link #acquireSlot()} synchronously on its
     * calling thread before each {@link FoliaCompat#runOnRegion} call. If
     * invoked on a Folia region tick thread (e.g. straight from a
     * plugin-message handler), and any dispatched chunk task targets the
     * SAME region, that task can never run — the tick thread is parked in
     * {@code Semaphore.acquire()}, the runnable's {@code release()} never
     * fires, and the Folia Watchdog kills the region after 60 s. Real
     * incident: ExoRamC pasted in {@code world_nether [1087,-236]} from a
     * region tick thread; 200+ s freeze; container restart.
     *
     * <p>Fix (v0.4.1): every call to {@code execute()} is hopped to
     * {@link org.bukkit.scheduler.BukkitScheduler}'s async pool via
     * {@link org.bukkit.Server#getAsyncScheduler()} before the dispatch loop
     * runs. Async-pool threads can block on the throttle without affecting
     * any region's tick rate, and the per-chunk {@code runOnRegion}
     * dispatches still queue onto the correct region thread.
     *
     * <p><strong>v0.4.2 completes the fix.</strong> The v0.4.1 hop only covered
     * the pass-1 loop. The pass-2 and physics-sweep loops were chained with a
     * plain {@code thenCompose}, which runs its body on the thread that
     * completed the upstream future — a Folia region tick thread (the last
     * per-chunk task to finish completes it inside {@code runOnRegion}). Those
     * loops then called the blocking {@link #acquireSlot()} <em>on a region
     * thread</em>; when that same region still had more pending chunk tasks
     * than free permits, the region parked in {@code Semaphore.acquire()}
     * waiting for permits only it could free by running its own queued tasks →
     * self-deadlock. Real incident: creaclone regions stuck up to 116h
     * (Folia issue #1), stack pinned at {@code PasteOperation.java:293}
     * (pass 2) and {@code :345} (sweep). Fix: both continuations now hop off
     * the region via {@link #offRegionExecutor} ({@code thenComposeAsync}), so
     * every {@code acquireSlot} runs on an async worker.
     */
    public CompletableFuture<PasteResult> execute() {
        CompletableFuture<PasteResult> proxy = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> {
            try {
                executeInternal().whenComplete((res, err) -> {
                    if (err != null) proxy.completeExceptionally(err);
                    else proxy.complete(res);
                });
            } catch (Throwable t) {
                proxy.completeExceptionally(t);
            }
        });
        return proxy;
    }

    /**
     * The actual paste pipeline. <strong>Never call this directly</strong> —
     * always go through {@link #execute()} so the dispatch loop runs on an
     * async worker, never on a region tick thread. See {@link #execute()}
     * javadoc for the deadlock this prevents.
     */
    private CompletableFuture<PasteResult> executeInternal() {
        long startNs = System.nanoTime();

        World world = options.origin().getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(new PasteResult(0, 0, 0, 0,
                    List.of("origin has no world")));
        }

        AtomicLong blocksPlaced = new AtomicLong();
        AtomicLong tilesPlaced = new AtomicLong();
        AtomicLong entitiesSpawned = new AtomicLong();
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

        // ---------- pass 1: inert blocks (or all blocks if observersLast=false)
        Map<ChunkKey, List<PendingWrite>> pass1 = new HashMap<>();
        Map<ChunkKey, List<PendingWrite>> pass2 = new HashMap<>();  // active blocks, deferred

        // We also collect per-chunk TileEntities, Entities, PendingTicks so a
        // single chunk task handles them all.
        Map<ChunkKey, List<PendingTileEntity>> teByChunk = new HashMap<>();
        Map<ChunkKey, List<PendingEntity>>     entitiesByChunk = new HashMap<>();
        Map<ChunkKey, List<PendingTick>>       blockTicksByChunk = new HashMap<>();
        Map<ChunkKey, List<PendingTick>>       fluidTicksByChunk = new HashMap<>();

        BoundingBox bbox = new BoundingBox();

        for (LitematicRegion region : schematic.regionsList()) {
            try {
                planRegion(region, world, bbox, pass1, pass2, teByChunk, entitiesByChunk,
                        blockTicksByChunk, fluidTicksByChunk, errors);
            } catch (Throwable t) {
                errors.add("planRegion(" + region.name + "): " + t.getClass().getSimpleName() + " " + t.getMessage());
                LOG.log(Level.WARNING, "planRegion failed for " + region.name, t);
            }
        }

        // ----------------------------------------------- pass 1 dispatch
        // Throttled per-chunk dispatch — acquireSlot blocks if 32 tasks are
        // already in flight. See CHUNK_THROTTLE javadoc for rationale.
        List<CompletableFuture<Void>> pass1Futures = new ArrayList<>(pass1.size());
        for (var entry : pass1.entrySet()) {
            ChunkKey key = entry.getKey();
            List<PendingWrite> writes = entry.getValue();
            List<PendingTileEntity> tes = teByChunk.getOrDefault(key, Collections.emptyList());
            boolean acquired = acquireSlot();
            AtomicBoolean releasedOnce = new AtomicBoolean(!acquired);
            // Pass 1 places blocks (batched, yielding to the region scheduler
            // every maxBlocksPerChunkTask writes — issue #4.7) + TileEntities
            // in the final batch; entities & ticks are run in pass 2. The
            // throttle permit spans all batches of the chunk and is released
            // when the whole chain is done.
            CompletableFuture<Void> f = runChunkBatched(world, key, writes,
                    () -> applyChunkTileEntities(tes, tilesPlaced, errors),
                    blocksPlaced, errors, "pass1");
            f.whenComplete((v, t) -> releaseSlot(releasedOnce));
            pass1Futures.add(f);
        }

        CompletableFuture<Void> pass1All = CompletableFuture.allOf(
                pass1Futures.toArray(new CompletableFuture[0]));

        // ----------------------------------------------- pass 2 + entities + ticks
        // thenComposeAsync(..., offRegionExecutor): this loop calls the blocking
        // acquireSlot() and must run on an async worker, never the region tick
        // thread that completed pass1All. See execute() javadoc (Folia issue #1).
        CompletableFuture<Void> pass2All = pass1All.thenComposeAsync(ignored -> {
            List<CompletableFuture<Void>> p2 = new ArrayList<>();

            // Union of all chunks that need pass-2 work.
            Set<ChunkKey> chunks = new HashSet<>();
            chunks.addAll(pass2.keySet());
            chunks.addAll(entitiesByChunk.keySet());
            chunks.addAll(blockTicksByChunk.keySet());
            chunks.addAll(fluidTicksByChunk.keySet());

            for (ChunkKey key : chunks) {
                List<PendingWrite>     writes  = pass2.getOrDefault(key, Collections.emptyList());
                List<PendingEntity>    ents    = entitiesByChunk.getOrDefault(key, Collections.emptyList());
                List<PendingTick>      bticks  = blockTicksByChunk.getOrDefault(key, Collections.emptyList());
                List<PendingTick>      fticks  = fluidTicksByChunk.getOrDefault(key, Collections.emptyList());
                boolean acquired = acquireSlot();
                AtomicBoolean releasedOnce = new AtomicBoolean(!acquired);
                CompletableFuture<Void> f = runChunkBatched(world, key, writes, () -> {
                    if (options.placeEntities() && !ents.isEmpty()) {
                        applyChunkEntities(world, ents, entitiesSpawned, errors);
                    }
                    if (options.placePendingTicks()) {
                        for (PendingTick t : bticks) {
                            try { nms.scheduleBlockTick(world, t.x, t.y, t.z, t.nbt); }
                            catch (Throwable ex) { errors.add("blockTick: " + ex.getMessage()); }
                        }
                        for (PendingTick t : fticks) {
                            try { nms.scheduleFluidTick(world, t.x, t.y, t.z, t.nbt); }
                            catch (Throwable ex) { errors.add("fluidTick: " + ex.getMessage()); }
                        }
                    }
                }, blocksPlaced, errors, "pass2");
                f.whenComplete((v, t) -> releaseSlot(releasedOnce));
                p2.add(f);
            }
            return CompletableFuture.allOf(p2.toArray(new CompletableFuture[0]));
        }, offRegionExecutor);

        // --------------------------------------------- deferred physics sweep
        // Same off-region requirement as pass 2 — this loop blocks in
        // acquireSlot() and was the :345 self-deadlock site in Folia issue #1.
        CompletableFuture<Void> finalPhase = pass2All.thenComposeAsync(ignored -> {
            if (!options.deferredPhysics() || bbox.empty) {
                return CompletableFuture.completedFuture(null);
            }
            // Dispatch one task per chunk in the bbox to sweep its blocks and
            // call BlockState#update — this re-evaluates physics on the owning
            // region.
            List<CompletableFuture<Void>> sweeps = new ArrayList<>();
            int minCX = bbox.minX >> 4, maxCX = bbox.maxX >> 4;
            int minCZ = bbox.minZ >> 4, maxCZ = bbox.maxZ >> 4;
            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    int fcx = cx, fcz = cz;
                    boolean acquired = acquireSlot();
                    AtomicBoolean releasedOnce = new AtomicBoolean(!acquired);
                    CompletableFuture<Void> f;
                    try {
                        f = FoliaCompat.runOnRegion(plugin, world, fcx, fcz, () -> {
                            try {
                                int xMin = Math.max(bbox.minX, fcx << 4);
                                int xMax = Math.min(bbox.maxX, (fcx << 4) + 15);
                                int zMin = Math.max(bbox.minZ, fcz << 4);
                                int zMax = Math.min(bbox.maxZ, (fcz << 4) + 15);
                                for (int x = xMin; x <= xMax; x++) {
                                    for (int z = zMin; z <= zMax; z++) {
                                        for (int y = bbox.minY; y <= bbox.maxY; y++) {
                                            Block b = world.getBlockAt(x, y, z);
                                            try {
                                                b.getState().update(true, true);
                                            } catch (Throwable t) {
                                                if (!FoliaThreadException.isFoliaThreadException(t)) {
                                                    errors.add("physics-sweep (" + x + "," + y + "," + z + "): " + t.getMessage());
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                if (!FoliaThreadException.isFoliaThreadException(t)) {
                                    errors.add("physics-sweep chunk (" + fcx + "," + fcz + "): " + t.getMessage());
                                }
                            } finally {
                                releaseSlot(releasedOnce);
                            }
                        });
                    } catch (Throwable t) {
                        releaseSlot(releasedOnce);
                        errors.add("dispatch sweep chunk (" + fcx + "," + fcz + "): "
                                + t.getClass().getSimpleName() + " " + t.getMessage());
                        continue;
                    }
                    f.whenComplete((v, t) -> releaseSlot(releasedOnce));
                    sweeps.add(f);
                }
            }
            return CompletableFuture.allOf(sweeps.toArray(new CompletableFuture[0]));
        }, offRegionExecutor);

        return finalPhase.thenApply(ignored -> {
            long ms = (System.nanoTime() - startNs) / 1_000_000L;
            String summary = "paste complete: " + blocksPlaced.get() + " blocks, "
                    + tilesPlaced.get() + " TE, "
                    + entitiesSpawned.get() + " entities, "
                    + errors.size() + " err in " + ms + "ms";
            // Always log to plugin logger so latest.log captures completion
            // (RCON-only senders won't see Component.text replies in the log).
            LOG.info(summary);
            reportProgress(summary);
            return new PasteResult(
                    blocksPlaced.get(),
                    tilesPlaced.get(),
                    entitiesSpawned.get(),
                    ms,
                    new ArrayList<>(errors));
        });
    }

    // ---------------------------------------------------------- region → tasks

    private void planRegion(LitematicRegion region, World world, BoundingBox bbox,
                            Map<ChunkKey, List<PendingWrite>> pass1,
                            Map<ChunkKey, List<PendingWrite>> pass2,
                            Map<ChunkKey, List<PendingTileEntity>> teByChunk,
                            Map<ChunkKey, List<PendingEntity>> entitiesByChunk,
                            Map<ChunkKey, List<PendingTick>> blockTicksByChunk,
                            Map<ChunkKey, List<PendingTick>> fluidTicksByChunk,
                            ConcurrentLinkedQueue<String> errors) {
        // -------- effective placement for this region (issue #4.5)
        SubRegionOverride override = options.subRegionFor(region.name);
        if (override != null && !override.enabled()) {
            LOG.info("paste region '" + region.name + "': disabled by placement, skipping");
            return;
        }
        final PlacementTransform.Rot rotG = options.rotation();
        final PlacementTransform.Mir mirG = options.mirror();
        final PlacementTransform.Rot rotS = override == null
                ? PlacementTransform.Rot.NONE : override.rotation();
        final PlacementTransform.Mir mirS = PlacementTransform.effectiveSubMirror(
                override == null ? PlacementTransform.Mir.NONE : override.mirror(), rotG);
        // Combined state transform for directional blocks / entity yaw.
        final PlacementTransform.Rot rotC = rotG.combine(rotS);
        final boolean identity = rotG == PlacementTransform.Rot.NONE
                && rotS == PlacementTransform.Rot.NONE
                && mirG == PlacementTransform.Mir.NONE
                && mirS == PlacementTransform.Mir.NONE;

        final ReplaceBehavior replace = options.replaceBehavior();
        final LayerFilter filter = options.layerFilter();

        // Resolve palette → cached BlockData + flags. Directional block
        // states are transformed once per palette entry (issue #4: stairs,
        // rails, logs must face the rotated/mirrored way).
        List<BlockStateEntry> palette = region.palette;
        BlockData[] paletteData = new BlockData[palette.size()];
        boolean[]   paletteActive = new boolean[palette.size()];
        boolean[]   paletteAir   = new boolean[palette.size()];
        boolean[]   paletteVoid  = new boolean[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            BlockStateEntry e = palette.get(i);
            String name = e.name();
            String full = name.contains(":") ? name : "minecraft:" + name;
            paletteActive[i] = ACTIVE_BLOCK_NAMES.contains(full);
            paletteAir[i] = full.equals("minecraft:air") || full.equals("minecraft:cave_air") || full.equals("minecraft:void_air");
            // structure_void is never pasted, in any replace mode (upstream).
            paletteVoid[i] = full.equals("minecraft:structure_void");
            try {
                BlockData data = Bukkit.createBlockData(e.toMinecraftString());
                paletteData[i] = identity ? data
                        : nms.transformBlockData(data, mirG.ordinal(), mirS.ordinal(), rotC.ordinal());
            } catch (Throwable t) {
                errors.add("palette[" + i + "]=" + e.toMinecraftString() + ": " + t.getMessage());
                paletteData[i] = null;
            }
        }

        int sizeX = region.sizeX, sizeY = region.sizeY, sizeZ = region.sizeZ;
        Location origin = options.origin();

        // Region coordinate bases (issue #4.2). The litematic format uses TWO
        // per-region bases:
        //   - blocks / tile entities / pending ticks are relative to the
        //     normalised MIN corner (region.originX/Y/Z);
        //   - entity Pos vectors are relative to the raw on-disk Position
        //     corner "pos1" (region.pos1X/Y/Z) — Litematica saves them as
        //     entityPos - box.getPos1().
        // The placement transform pipeline (upstream placeBlocksToWorld /
        // placeEntitiesToWorld) works in pos1-relative coordinates:
        //   world = origin + Tg(effPos1) + Ts(Tg(pos1RelativeLocal))
        // where Tg = global mirror+rotation, Ts = sub-region mirror+rotation,
        // and effPos1 is the SubRegions override position when present.
        final int pos1X = region.pos1X(), pos1Y = region.pos1Y(), pos1Z = region.pos1Z();
        final int effX = override != null && override.hasPos() ? override.posX() : pos1X;
        final int effY = override != null && override.hasPos() ? override.posY() : pos1Y;
        final int effZ = override != null && override.hasPos() ? override.posZ() : pos1Z;
        // min-corner-relative -> pos1-relative delta (0 on positive-size axes)
        final int dX = region.originX - pos1X;
        final int dY = region.originY - pos1Y;
        final int dZ = region.originZ - pos1Z;

        int[] baseT = PlacementTransform.transformInt(effX, effZ, mirG, rotG);
        final int baseX = origin.getBlockX() + baseT[0];
        final int baseY = origin.getBlockY() + effY;
        final int baseZ = origin.getBlockZ() + baseT[1];

        LOG.info("paste region '" + region.name + "': pos1 ("
                + pos1X + "," + pos1Y + "," + pos1Z + ")"
                + (override != null && override.hasPos()
                        ? " override (" + effX + "," + effY + "," + effZ + ")" : "")
                + " rotG=" + rotG + " mirG=" + mirG
                + (rotS != PlacementTransform.Rot.NONE || mirS != PlacementTransform.Mir.NONE
                        ? " rotS=" + rotS + " mirS=" + mirS : "")
                + " replace=" + replace
                + (filter != null ? " " + filter : "")
                + " -> world base (" + baseX + "," + baseY + "," + baseZ + "), size "
                + sizeX + "x" + sizeY + "x" + sizeZ);

        for (int ry = 0; ry < sizeY; ry++) {
            int wy = baseY + dY + ry;
            for (int rz = 0; rz < sizeZ; rz++) {
                for (int rx = 0; rx < sizeX; rx++) {
                    int paletteIdx = region.blockIndexAt(rx, ry, rz);
                    if (paletteIdx < 0 || paletteIdx >= paletteData.length) continue;
                    if (paletteVoid[paletteIdx]) continue;
                    boolean isAir = paletteAir[paletteIdx];
                    // Replace semantics (issue #4.1): WITH_NON_AIR skips
                    // schematic air; NONE only ever writes into destination
                    // air, so schematic air would be a no-op — skip it; ALL
                    // keeps air as a real write that clears the destination.
                    if (isAir && replace != ReplaceBehavior.ALL) continue;
                    BlockData data = paletteData[paletteIdx];
                    if (data == null) continue;

                    int[] t1 = PlacementTransform.transformInt(dX + rx, dZ + rz, mirG, rotG);
                    int[] t2 = PlacementTransform.transformInt(t1[0], t1[1], mirS, rotS);
                    int wx = baseX + t2[0];
                    int wz = baseZ + t2[1];

                    if (filter != null && !filter.test(wx, wy, wz)) continue;

                    bbox.include(wx, wy, wz);
                    ChunkKey key = new ChunkKey(wx >> 4, wz >> 4);
                    PendingWrite pw = new PendingWrite(wx, wy, wz, data);
                    if (options.observersLast() && paletteActive[paletteIdx]) {
                        pass2.computeIfAbsent(key, k -> new ArrayList<>()).add(pw);
                    } else {
                        pass1.computeIfAbsent(key, k -> new ArrayList<>()).add(pw);
                    }
                }
            }
        }

        // Tile entities — x/y/z are min-corner relative; same transform as blocks.
        if (options.placeTileEntities() && region.tileEntities != null) {
            for (LitematicNbt.NbtTag tag : region.tileEntities.values()) {
                if (!(tag instanceof LitematicNbt.NbtCompound c)) continue;
                Integer tx = c.getInt("x");
                Integer ty = c.getInt("y");
                Integer tz = c.getInt("z");
                if (tx == null || ty == null || tz == null) continue;
                int[] t1 = PlacementTransform.transformInt(dX + tx, dZ + tz, mirG, rotG);
                int[] t2 = PlacementTransform.transformInt(t1[0], t1[1], mirS, rotS);
                int wx = baseX + t2[0];
                int wy = baseY + dY + ty;
                int wz = baseZ + t2[1];
                if (filter != null && !filter.test(wx, wy, wz)) continue;
                ChunkKey key = new ChunkKey(wx >> 4, wz >> 4);
                teByChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(new PendingTileEntity(wx, wy, wz, c));
            }
        }

        // Entities — Pos is a TAG_LIST of doubles, pos1-relative (issue #4.2:
        // no re-subtraction of the region origin), transformed with the
        // cell-preserving double transform (issue #4.3) and spawned with the
        // orientation transform applied (issue #4.4).
        boolean regionEntities = options.placeEntities()
                && (override == null || !override.ignoreEntities());
        if (regionEntities && region.entities != null) {
            for (LitematicNbt.NbtTag tag : region.entities.values()) {
                if (!(tag instanceof LitematicNbt.NbtCompound c)) continue;
                LitematicNbt.NbtList posList = c.getList("Pos");
                if (posList == null || posList.size() < 3) continue;
                double ex = doubleOf(posList.get(0));
                double ey = doubleOf(posList.get(1));
                double ez = doubleOf(posList.get(2));
                double[] v1 = PlacementTransform.transformVec(ex, ez, mirG, rotG);
                double[] v2 = PlacementTransform.transformVec(v1[0], v1[1], mirS, rotS);
                double wx = baseX + v2[0];
                double wy = baseY + ey;
                double wz = baseZ + v2[1];
                if (filter != null && !filter.test(wx, wy, wz)) continue;
                ChunkKey key = new ChunkKey((int) Math.floor(wx) >> 4, (int) Math.floor(wz) >> 4);
                entitiesByChunk.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new PendingEntity(wx, wy, wz, c,
                                rotC.ordinal(), mirG.ordinal(), mirS.ordinal()));
            }
        }

        // Pending block/fluid ticks — min-corner relative, like blocks.
        if (options.placePendingTicks()) {
            if (region.pendingBlockTicks != null) {
                for (LitematicNbt.NbtTag tag : region.pendingBlockTicks.values()) {
                    PendingTick pt = translateTick(tag, baseX, baseY, baseZ, dX, dY, dZ,
                            mirG, rotG, mirS, rotS);
                    if (pt == null) continue;
                    if (filter != null && !filter.test(pt.x, pt.y, pt.z)) continue;
                    ChunkKey key = new ChunkKey(pt.x >> 4, pt.z >> 4);
                    blockTicksByChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(pt);
                }
            }
            if (region.pendingFluidTicks != null) {
                for (LitematicNbt.NbtTag tag : region.pendingFluidTicks.values()) {
                    PendingTick pt = translateTick(tag, baseX, baseY, baseZ, dX, dY, dZ,
                            mirG, rotG, mirS, rotS);
                    if (pt == null) continue;
                    if (filter != null && !filter.test(pt.x, pt.y, pt.z)) continue;
                    ChunkKey key = new ChunkKey(pt.x >> 4, pt.z >> 4);
                    fluidTicksByChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(pt);
                }
            }
        }
    }

    private static PendingTick translateTick(LitematicNbt.NbtTag tag,
                                             int baseX, int baseY, int baseZ,
                                             int dX, int dY, int dZ,
                                             PlacementTransform.Mir mirG, PlacementTransform.Rot rotG,
                                             PlacementTransform.Mir mirS, PlacementTransform.Rot rotS) {
        if (!(tag instanceof LitematicNbt.NbtCompound c)) return null;
        Integer tx = c.getInt("x");
        Integer ty = c.getInt("y");
        Integer tz = c.getInt("z");
        if (tx == null || ty == null || tz == null) return null;
        int[] t1 = PlacementTransform.transformInt(dX + tx, dZ + tz, mirG, rotG);
        int[] t2 = PlacementTransform.transformInt(t1[0], t1[1], mirS, rotS);
        return new PendingTick(baseX + t2[0], baseY + dY + ty, baseZ + t2[1], c);
    }

    private static double doubleOf(LitematicNbt.NbtTag t) {
        if (t instanceof LitematicNbt.NbtDouble d) return d.value();
        if (t instanceof LitematicNbt.NbtFloat f) return f.value();
        if (t instanceof LitematicNbt.NbtInt i) return i.value();
        if (t instanceof LitematicNbt.NbtLong l) return l.value();
        return 0;
    }

    // ----------------------------------------------------------- chunk apply

    /**
     * Runs a chunk's block writes on its owning region in batches of at most
     * {@link PasteOptions#maxBlocksPerChunkTask()} blocks, yielding back to
     * the region scheduler between batches so one dense chunk cannot pin a
     * region tick for the whole write list (issue #4.7). {@code tail} runs
     * on the region after the final batch (TileEntities in pass 1, entities +
     * pending ticks in pass 2). The returned future completes when the whole
     * chain — every batch plus the tail — has finished; it never completes
     * exceptionally (failures are recorded in {@code errors}), so callers can
     * hang throttle-release and pass barriers off it directly.
     *
     * <p>Threading: only the FIRST batch dispatch may block (the caller holds
     * the chunk's throttle permit); continuation dispatches happen from the
     * region thread itself via {@code runOnRegion} which never blocks — the
     * v0.4.x "no blocking acquire on a region thread" rule is preserved.
     */
    private CompletableFuture<Void> runChunkBatched(World world, ChunkKey key,
                                                    List<PendingWrite> writes, Runnable tail,
                                                    AtomicLong blockCounter,
                                                    ConcurrentLinkedQueue<String> errors,
                                                    String phase) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        scheduleChunkBatch(world, key, writes, 0, tail, done, blockCounter, errors, phase);
        return done;
    }

    private void scheduleChunkBatch(World world, ChunkKey key,
                                    List<PendingWrite> writes, int from, Runnable tail,
                                    CompletableFuture<Void> done, AtomicLong blockCounter,
                                    ConcurrentLinkedQueue<String> errors, String phase) {
        CompletableFuture<Void> f;
        try {
            f = FoliaCompat.runOnRegion(plugin, world, key.cx, key.cz, () -> {
                int batch = Math.max(1, options.maxBlocksPerChunkTask());
                int to = Math.min(writes.size(), from + batch);
                try {
                    if (to > from) {
                        applyChunkBlocks(world, writes.subList(from, to), blockCounter, errors);
                    }
                    if (to < writes.size() && !cancelled.get()) {
                        // Yield: reschedule the remainder as a fresh region
                        // task. The chain's future is completed by that task
                        // (or its scheduling safety net), NOT here.
                        scheduleChunkBatch(world, key, writes, to, tail, done,
                                blockCounter, errors, phase);
                        return;
                    }
                    if (tail != null && !cancelled.get()) {
                        try {
                            tail.run();
                        } catch (Throwable t) {
                            errors.add(phase + " chunk tail (" + key.cx + "," + key.cz + "): "
                                    + t.getClass().getSimpleName() + " " + t.getMessage());
                        }
                    }
                } catch (Throwable t) {
                    errors.add(phase + " chunk (" + key.cx + "," + key.cz + ") batch@" + from + ": "
                            + t.getClass().getSimpleName() + " " + t.getMessage());
                }
                done.complete(null);
            });
        } catch (Throwable t) {
            errors.add("dispatch " + phase + " chunk (" + key.cx + "," + key.cz + "): "
                    + t.getClass().getSimpleName() + " " + t.getMessage());
            done.complete(null);
            return;
        }
        // Safety net: if the scheduler rejected/failed the runnable so the
        // lambda never ran, complete the chain anyway so barriers advance.
        f.whenComplete((v, t) -> {
            if (t != null) {
                errors.add(phase + " chunk (" + key.cx + "," + key.cz + "): scheduler "
                        + t.getClass().getSimpleName());
                done.complete(null);
            }
        });
    }

    private void applyChunkBlocks(World world, List<PendingWrite> writes, AtomicLong counter,
                                  ConcurrentLinkedQueue<String> errors) {
        if (cancelled.get()) return;
        boolean dbgFirst = Boolean.getBoolean("litematica.debugPaste");
        for (PendingWrite pw : writes) {
            // Cancellation checkpoint — cheap volatile read between writes.
            if (cancelled.get()) {
                errors.add("cancelled mid-chunk at (" + pw.x + "," + pw.y + "," + pw.z + ")");
                return;
            }
            try {
                Block b = world.getBlockAt(pw.x, pw.y, pw.z);
                // ReplaceBehavior.NONE: only ever write into destination air
                // (issue #4.1). Checked here, on the owning region thread, so
                // the destination read is race-free.
                if (options.replaceBehavior() == ReplaceBehavior.NONE
                        && !b.getType().isAir()) {
                    continue;
                }
                BlockState before = null;
                if (blockChangeLogger.isEnabled()) {
                    try {
                        before = b.getState();
                    } catch (Throwable snapshotFailure) {
                        // Report the audit failure through the fail-open hook,
                        // but do not prevent the existing block write.
                        blockChangeLogger.logBlockChange(actorName, null, null);
                    }
                }
                // Bukkit overload: setBlockData(data, applyPhysics) — applyPhysics=false to defer.
                b.setBlockData(pw.data, !options.deferredPhysics());
                counter.incrementAndGet();
                if (before != null) {
                    BlockState after = null;
                    try {
                        after = b.getState();
                    } catch (Throwable snapshotFailure) {
                        // The hook handles this as a logging-only failure.
                    }
                    blockChangeLogger.logBlockChange(actorName, before, after);
                }
                if (dbgFirst) {
                    dbgFirst = false;
                    String readBack;
                    try {
                        readBack = b.getBlockData().getAsString();
                    } catch (Throwable rb) {
                        readBack = "READBACK-THREW: " + rb;
                    }
                    boolean owned;
                    try {
                        owned = org.bukkit.Bukkit.isOwnedByCurrentRegion(world, pw.x >> 4, pw.z >> 4);
                    } catch (Throwable ow) {
                        owned = false;
                    }
                    LOG.info("[debugPaste] first write (" + pw.x + "," + pw.y + "," + pw.z + ")"
                            + " thread=" + Thread.currentThread().getName()
                            + " ownedByRegion=" + owned
                            + " chunkLoaded=" + world.isChunkLoaded(pw.x >> 4, pw.z >> 4)
                            + " wrote=" + pw.data.getAsString()
                            + " readBack=" + readBack);
                }
            } catch (Throwable t) {
                if (FoliaThreadException.isFoliaThreadException(t)) {
                    errors.add("setBlock (" + pw.x + "," + pw.y + "," + pw.z + "): wrong region thread");
                } else {
                    errors.add("setBlock (" + pw.x + "," + pw.y + "," + pw.z + "): "
                            + t.getClass().getSimpleName() + " " + t.getMessage());
                    // Log full stack so non-Folia bugs are not swallowed silently.
                    LOG.log(Level.WARNING, "setBlock failure at "
                            + pw.x + "," + pw.y + "," + pw.z, t);
                }
            }
        }
    }

    private void applyChunkTileEntities(List<PendingTileEntity> tes, AtomicLong counter,
                                        ConcurrentLinkedQueue<String> errors) {
        for (PendingTileEntity te : tes) {
            try {
                // Rewrite x/y/z in NBT to absolute world coords so the BE doesn't
                // copy the local schematic coords on load.
                LinkedHashCompoundCopy copy = LinkedHashCompoundCopy.of(te.nbt);
                copy.compound.put("x", new LitematicNbt.NbtInt(te.x));
                copy.compound.put("y", new LitematicNbt.NbtInt(te.y));
                copy.compound.put("z", new LitematicNbt.NbtInt(te.z));
                Block block = options.origin().getWorld().getBlockAt(te.x, te.y, te.z);
                nms.loadTileEntityNbt(block, copy.compound);
                counter.incrementAndGet();
            } catch (Throwable t) {
                if (!FoliaThreadException.isFoliaThreadException(t)) {
                    errors.add("loadTE (" + te.x + "," + te.y + "," + te.z + "): " + t.getMessage());
                }
            }
        }
    }

    private void applyChunkEntities(World world, List<PendingEntity> ents, AtomicLong counter,
                                    ConcurrentLinkedQueue<String> errors) {
        for (PendingEntity pe : ents) {
            try {
                Location at = new Location(world, pe.x, pe.y, pe.z);
                Object spawned = nms.spawnEntityFromNbt(at, pe.nbt,
                        pe.rotationOrdinal, pe.mirrorMainOrdinal, pe.mirrorSubOrdinal);
                if (spawned != null) counter.incrementAndGet();
            } catch (Throwable t) {
                if (!FoliaThreadException.isFoliaThreadException(t)) {
                    errors.add("spawnEntity: " + t.getMessage());
                }
            }
        }
    }

    // ------------------------------------------------------------- utilities

    /**
     * Tries to acquire a chunk-dispatch slot from {@link #CHUNK_THROTTLE},
     * waiting at most {@link #ACQUIRE_TIMEOUT_MS}.
     *
     * @return {@code true} if a permit is now held — the caller MUST pair it
     *         with exactly one {@link #releaseSlot}; {@code false} if the wait
     *         timed out or the thread was interrupted, in which case the caller
     *         holds no permit and must NOT release one (seed its
     *         {@code releasedOnce} guard with {@code true}).
     *
     * <p>Never blocks indefinitely: a timeout proceeds unthrottled rather than
     * parking the thread, which is what previously froze creaclone regions when
     * a permit leaked (Folia issue #1). Under normal load a permit is always
     * available within microseconds, so the timeout path is a safety valve, not
     * a hot path.
     */
    private static boolean acquireSlot() {
        try {
            boolean ok = CHUNK_THROTTLE.tryAcquire(ACQUIRE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!ok) {
                LOG.warning("CHUNK_THROTTLE permit wait timed out after " + ACQUIRE_TIMEOUT_MS
                        + "ms — dispatching this chunk unthrottled (extreme load or a leaked permit); "
                        + "server stays live rather than parking a thread");
            }
            return ok;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Releases the chunk-dispatch slot exactly once. {@code releasedOnce} is
     * the per-dispatch guard — the throttle is released the first time this
     * method is called with a given {@code AtomicBoolean}, and is a no-op on
     * subsequent calls. Necessary because both the lambda's {@code finally}
     * and the future's {@code whenComplete} could legitimately fire for the
     * same dispatch (e.g. scheduler runs the task AND the future completes
     * normally — both want to release).
     */
    private static void releaseSlot(AtomicBoolean releasedOnce) {
        if (releasedOnce.compareAndSet(false, true)) {
            CHUNK_THROTTLE.release();
        }
    }

    private void reportProgress(String message) {
        var progress = options.progress();
        if (progress != null) {
            try { progress.accept(null, message); } catch (Throwable ignored) {}
        }
    }

    // ---------------------------------------------------- internal data types

    private record ChunkKey(int cx, int cz) {}

    private record PendingWrite(int x, int y, int z, BlockData data) {}

    private record PendingTileEntity(int x, int y, int z, LitematicNbt.NbtCompound nbt) {}

    private record PendingEntity(double x, double y, double z, LitematicNbt.NbtCompound nbt,
                                 int rotationOrdinal, int mirrorMainOrdinal, int mirrorSubOrdinal) {}

    private record PendingTick(int x, int y, int z, LitematicNbt.NbtCompound nbt) {}

    private static final class BoundingBox {
        boolean empty = true;
        int minX, minY, minZ, maxX, maxY, maxZ;
        void include(int x, int y, int z) {
            if (empty) {
                minX = maxX = x; minY = maxY = y; minZ = maxZ = z;
                empty = false;
            } else {
                if (x < minX) minX = x; else if (x > maxX) maxX = x;
                if (y < minY) minY = y; else if (y > maxY) maxY = y;
                if (z < minZ) minZ = z; else if (z > maxZ) maxZ = z;
            }
        }
    }

    /** Helper: deep-copy an NbtCompound so we can rewrite x/y/z. */
    private static final class LinkedHashCompoundCopy {
        final LitematicNbt.NbtCompound compound;
        LinkedHashCompoundCopy(LitematicNbt.NbtCompound c) { this.compound = c; }
        static LinkedHashCompoundCopy of(LitematicNbt.NbtCompound source) {
            java.util.LinkedHashMap<String, LitematicNbt.NbtTag> entries = new java.util.LinkedHashMap<>(source.entries());
            return new LinkedHashCompoundCopy(new LitematicNbt.NbtCompound(entries));
        }
    }
}
