package fr.ekaii.litematica.paste;

import fr.ekaii.litematica.core.BlockStateEntry;
import fr.ekaii.litematica.core.LitematicNbt;
import fr.ekaii.litematica.core.LitematicRegion;
import fr.ekaii.litematica.core.LitematicSchematic;
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

    public PasteOperation(Plugin plugin, LitematicSchematic schematic, PasteOptions options) {
        this.plugin = plugin;
        this.schematic = schematic;
        this.options = options;
        this.nms = NmsBridge.get();
    }

    /**
     * Kicks off the paste. The returned future completes when every
     * per-chunk task has finished. Errors collected along the way are
     * delivered in {@link PasteResult#errors()}.
     */
    public CompletableFuture<PasteResult> execute() {
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
        List<CompletableFuture<Void>> pass1Futures = new ArrayList<>(pass1.size());
        for (var entry : pass1.entrySet()) {
            ChunkKey key = entry.getKey();
            List<PendingWrite> writes = entry.getValue();
            List<PendingTileEntity> tes = teByChunk.getOrDefault(key, Collections.emptyList());
            // Pass 1 places blocks + TileEntities; entities & ticks are run in pass 2.
            pass1Futures.add(FoliaCompat.runOnRegion(plugin, world, key.cx, key.cz, () -> {
                applyChunkBlocks(world, writes, blocksPlaced, errors);
                applyChunkTileEntities(tes, tilesPlaced, errors);
            }));
        }

        CompletableFuture<Void> pass1All = CompletableFuture.allOf(
                pass1Futures.toArray(new CompletableFuture[0]));

        // ----------------------------------------------- pass 2 + entities + ticks
        CompletableFuture<Void> pass2All = pass1All.thenCompose(ignored -> {
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
                p2.add(FoliaCompat.runOnRegion(plugin, world, key.cx, key.cz, () -> {
                    if (!writes.isEmpty()) {
                        applyChunkBlocks(world, writes, blocksPlaced, errors);
                    }
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
                }));
            }
            return CompletableFuture.allOf(p2.toArray(new CompletableFuture[0]));
        });

        // --------------------------------------------- deferred physics sweep
        CompletableFuture<Void> finalPhase = pass2All.thenCompose(ignored -> {
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
                    sweeps.add(FoliaCompat.runOnRegion(plugin, world, fcx, fcz, () -> {
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
                        }
                    }));
                }
            }
            return CompletableFuture.allOf(sweeps.toArray(new CompletableFuture[0]));
        });

        return finalPhase.thenApply(ignored -> {
            long ms = (System.nanoTime() - startNs) / 1_000_000L;
            reportProgress("paste complete: " + blocksPlaced.get() + " blocks in " + ms + "ms");
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
        // Resolve palette → cached BlockData + "is active" flags
        List<BlockStateEntry> palette = region.palette;
        BlockData[] paletteData = new BlockData[palette.size()];
        boolean[]   paletteActive = new boolean[palette.size()];
        boolean[]   paletteAir   = new boolean[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            BlockStateEntry e = palette.get(i);
            String name = e.name();
            String full = name.contains(":") ? name : "minecraft:" + name;
            paletteActive[i] = ACTIVE_BLOCK_NAMES.contains(full);
            paletteAir[i] = full.equals("minecraft:air") || full.equals("minecraft:cave_air") || full.equals("minecraft:void_air");
            try {
                paletteData[i] = Bukkit.createBlockData(e.toMinecraftString());
            } catch (Throwable t) {
                errors.add("palette[" + i + "]=" + e.toMinecraftString() + ": " + t.getMessage());
                paletteData[i] = null;
            }
        }

        int sizeX = region.sizeX, sizeY = region.sizeY, sizeZ = region.sizeZ;
        Location origin = options.origin();
        int yaw = options.yawRotation();

        // World coordinate of the region origin (post yaw rotation, post offset).
        // We rotate the region's local axes around the schematic origin.
        // For each (rx, ry, rz) local cell, world (wx, wy, wz) is:
        //   start = origin + rotate(region.origin)
        //   (wx,wz) = start + rotateXZ((rx,rz), yaw)
        //   wy = origin.y + region.originY + ry
        int[] rotRO = rotateXZ(region.originX, region.originZ, yaw);
        int startX = origin.getBlockX() + rotRO[0];
        int startY = origin.getBlockY() + region.originY;
        int startZ = origin.getBlockZ() + rotRO[1];

        for (int ry = 0; ry < sizeY; ry++) {
            int wy = startY + ry;
            for (int rz = 0; rz < sizeZ; rz++) {
                for (int rx = 0; rx < sizeX; rx++) {
                    int[] rxz = rotateXZ(rx, rz, yaw);
                    int wx = startX + rxz[0];
                    int wz = startZ + rxz[1];

                    int paletteIdx = region.blockIndexAt(rx, ry, rz);
                    if (paletteIdx < 0 || paletteIdx >= paletteData.length) continue;
                    if (paletteAir[paletteIdx]) continue;
                    BlockData data = paletteData[paletteIdx];
                    if (data == null) continue;

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

        // Tile entities — read NBT positions, translate to world, group by chunk.
        if (options.placeTileEntities() && region.tileEntities != null) {
            for (LitematicNbt.NbtTag tag : region.tileEntities.values()) {
                if (!(tag instanceof LitematicNbt.NbtCompound c)) continue;
                Integer tx = c.getInt("x");
                Integer ty = c.getInt("y");
                Integer tz = c.getInt("z");
                if (tx == null || ty == null || tz == null) continue;
                int[] rxz = rotateXZ(tx, tz, yaw);
                int wx = startX + rxz[0];
                int wy = startY + ty;
                int wz = startZ + rxz[1];
                ChunkKey key = new ChunkKey(wx >> 4, wz >> 4);
                teByChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(new PendingTileEntity(wx, wy, wz, c));
            }
        }

        // Entities — coords stored as TAG_LIST of doubles "Pos".
        if (options.placeEntities() && region.entities != null) {
            for (LitematicNbt.NbtTag tag : region.entities.values()) {
                if (!(tag instanceof LitematicNbt.NbtCompound c)) continue;
                LitematicNbt.NbtList posList = c.getList("Pos");
                if (posList == null || posList.size() < 3) continue;
                double lx = doubleOf(posList.get(0)) - region.originX;
                double ly = doubleOf(posList.get(1)) - region.originY;
                double lz = doubleOf(posList.get(2)) - region.originZ;
                int[] rxz = rotateXZdouble(lx, lz, yaw);
                double wx = startX + rxz[0] + (lx - (int) Math.floor(lx));
                double wy = startY + ly;
                double wz = startZ + rxz[1] + (lz - (int) Math.floor(lz));
                ChunkKey key = new ChunkKey((int) Math.floor(wx) >> 4, (int) Math.floor(wz) >> 4);
                entitiesByChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(new PendingEntity(wx, wy, wz, c));
            }
        }

        // Pending block/fluid ticks
        if (options.placePendingTicks()) {
            if (region.pendingBlockTicks != null) {
                for (LitematicNbt.NbtTag tag : region.pendingBlockTicks.values()) {
                    PendingTick pt = translateTick(tag, startX, startY, startZ, yaw);
                    if (pt == null) continue;
                    ChunkKey key = new ChunkKey(pt.x >> 4, pt.z >> 4);
                    blockTicksByChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(pt);
                }
            }
            if (region.pendingFluidTicks != null) {
                for (LitematicNbt.NbtTag tag : region.pendingFluidTicks.values()) {
                    PendingTick pt = translateTick(tag, startX, startY, startZ, yaw);
                    if (pt == null) continue;
                    ChunkKey key = new ChunkKey(pt.x >> 4, pt.z >> 4);
                    fluidTicksByChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(pt);
                }
            }
        }
    }

    private static PendingTick translateTick(LitematicNbt.NbtTag tag, int startX, int startY, int startZ, int yaw) {
        if (!(tag instanceof LitematicNbt.NbtCompound c)) return null;
        Integer tx = c.getInt("x");
        Integer ty = c.getInt("y");
        Integer tz = c.getInt("z");
        if (tx == null || ty == null || tz == null) return null;
        int[] rxz = rotateXZ(tx, tz, yaw);
        return new PendingTick(startX + rxz[0], startY + ty, startZ + rxz[1], c);
    }

    private static double doubleOf(LitematicNbt.NbtTag t) {
        if (t instanceof LitematicNbt.NbtDouble d) return d.value();
        if (t instanceof LitematicNbt.NbtFloat f) return f.value();
        if (t instanceof LitematicNbt.NbtInt i) return i.value();
        if (t instanceof LitematicNbt.NbtLong l) return l.value();
        return 0;
    }

    // ----------------------------------------------------------- chunk apply

    private void applyChunkBlocks(World world, List<PendingWrite> writes, AtomicLong counter,
                                  ConcurrentLinkedQueue<String> errors) {
        for (PendingWrite pw : writes) {
            try {
                Block b = world.getBlockAt(pw.x, pw.y, pw.z);
                // Bukkit overload: setBlockData(data, applyPhysics) — applyPhysics=false to defer.
                b.setBlockData(pw.data, !options.deferredPhysics());
                counter.incrementAndGet();
            } catch (Throwable t) {
                if (FoliaThreadException.isFoliaThreadException(t)) {
                    errors.add("setBlock (" + pw.x + "," + pw.y + "," + pw.z + "): wrong region thread");
                } else {
                    errors.add("setBlock (" + pw.x + "," + pw.y + "," + pw.z + "): " + t.getClass().getSimpleName() + " " + t.getMessage());
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
                Object spawned = nms.spawnEntityFromNbt(at, pe.nbt);
                if (spawned != null) counter.incrementAndGet();
            } catch (Throwable t) {
                if (!FoliaThreadException.isFoliaThreadException(t)) {
                    errors.add("spawnEntity: " + t.getMessage());
                }
            }
        }
    }

    // ------------------------------------------------------------- utilities

    private void reportProgress(String message) {
        var progress = options.progress();
        if (progress != null) {
            try { progress.accept(null, message); } catch (Throwable ignored) {}
        }
    }

    /**
     * Rotate (x,z) by {@code yaw} degrees around (0,0).
     * yaw=0:   (x,z) → (x,z)
     * yaw=90:  (x,z) → (-z, x)
     * yaw=180: (x,z) → (-x,-z)
     * yaw=270: (x,z) → (z, -x)
     */
    private static int[] rotateXZ(int x, int z, int yaw) {
        return switch (yaw) {
            case 90  -> new int[] {-z, x};
            case 180 -> new int[] {-x, -z};
            case 270 -> new int[] {z, -x};
            default  -> new int[] {x, z};
        };
    }

    private static int[] rotateXZdouble(double x, double z, int yaw) {
        // Floor-truncation for the integer chunk-key path; callers add the
        // fractional component separately when computing entity positions.
        return rotateXZ((int) Math.floor(x), (int) Math.floor(z), yaw);
    }

    // ---------------------------------------------------- internal data types

    private record ChunkKey(int cx, int cz) {}

    private record PendingWrite(int x, int y, int z, BlockData data) {}

    private record PendingTileEntity(int x, int y, int z, LitematicNbt.NbtCompound nbt) {}

    private record PendingEntity(double x, double y, double z, LitematicNbt.NbtCompound nbt) {}

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
