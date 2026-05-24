package fr.ekaii.litematica.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fr.ekaii.litematica.LitematicaFolia;
import fr.ekaii.litematica.core.BlockStateEntry;
import fr.ekaii.litematica.core.LitematicMetadata;
import fr.ekaii.litematica.core.LitematicNbt;
import fr.ekaii.litematica.core.LitematicReader;
import fr.ekaii.litematica.core.LitematicRegion;
import fr.ekaii.litematica.core.LitematicSchematic;
import fr.ekaii.litematica.core.LitematicWriter;
import fr.ekaii.litematica.paste.FoliaCompat;
import fr.ekaii.litematica.paste.PasteOperation;
import fr.ekaii.litematica.paste.PasteOptions;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Brigadier-backed {@code /litematica} command tree. Wired into Paper via
 * {@link LifecycleEvents#COMMANDS} at plugin enable time.
 *
 * <h2>Subcommands</h2>
 * <ul>
 *   <li>{@code /litematica paste <file> [x y z] [yaw] [--no-entities] [--no-physics] [--no-tile-entities] [--no-pending-ticks]}
 *   <li>{@code /litematica save <name> <x1 y1 z1> <x2 y2 z2>} — blocks-only v1 (no TE/entities/ticks; TODO).
 *   <li>{@code /litematica materials <file>}
 *   <li>{@code /litematica list [--filter glob]}
 *   <li>{@code /litematica info <file>}
 *   <li>{@code /litematica reload}
 * </ul>
 */
public final class LitematicaCommands {

    private final LitematicaFolia plugin;

    /**
     * Active paste operations, keyed by an opaque ticket string ({@code sender}
     * name + monotonically incrementing counter). {@code /litematica cancel}
     * flips the most recent (or all) of these. Concurrent map because paste
     * tasks complete from arbitrary region threads.
     */
    private final java.util.concurrent.ConcurrentMap<String, PasteOperation> activeOps =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong opSeq =
            new java.util.concurrent.atomic.AtomicLong(0);

    public LitematicaCommands(LitematicaFolia plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers the command tree on the plugin's lifecycle manager. Safe to
     * call from {@link LitematicaFolia#onEnable()}.
     */
    public void register() {
        try {
            plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
                Commands registrar = event.registrar();
                registrar.register(buildRoot().build(), "LitematicaFolia paste/save/materials/list/info/reload");
            });
            plugin.getLogger().info("LitematicaCommands: /litematica registered via Brigadier.");
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Failed to register /litematica command via Brigadier", t);
        }
    }

    // ------------------------------------------------------------- tree shape

    private LiteralArgumentBuilder<CommandSourceStack> buildRoot() {
        return Commands.literal("litematica")
                .requires(src -> src.getSender().hasPermission("litematica.use"))
                .executes(this::executeHelp)
                .then(buildPasteBranch())
                .then(buildSaveBranch())
                .then(buildMaterialsBranch())
                .then(buildListBranch())
                .then(buildInfoBranch())
                .then(buildCancelBranch())
                .then(buildReloadBranch());
    }

    // ----------------------------------------------------------------- paste

    private LiteralArgumentBuilder<CommandSourceStack> buildPasteBranch() {
        // Build flag literals (four "--no-…" toggles) and attach them to each
        // terminal node where they may appear:
        //   - the file-arg terminal (so flags work without coords)
        //   - the z-arg terminal (coords without yaw)
        //   - the yaw-arg terminal
        //   - each flag's own terminal (so flags chain in any order)
        // Each terminal calls .executes(this::doPasteImpl).
        RequiredArgumentBuilder<CommandSourceStack, Integer> yawArg =
                Commands.argument("yaw", IntegerArgumentType.integer(0, 359))
                        .executes(this::doPasteImpl);
        attachFlagChildren(yawArg);

        RequiredArgumentBuilder<CommandSourceStack, Double> zArg =
                Commands.argument("z", DoubleArgumentType.doubleArg())
                        .executes(this::doPasteImpl)
                        .then(yawArg);
        attachFlagChildren(zArg);

        RequiredArgumentBuilder<CommandSourceStack, Double> yArg =
                Commands.argument("y", DoubleArgumentType.doubleArg())
                        .then(zArg);

        RequiredArgumentBuilder<CommandSourceStack, Double> xArg =
                Commands.argument("x", DoubleArgumentType.doubleArg())
                        .then(yArg);

        RequiredArgumentBuilder<CommandSourceStack, String> fileArg =
                Commands.argument("file", StringArgumentType.string())
                        .suggests(schematicFileSuggestions())
                        .executes(this::doPasteImpl)
                        .then(xArg);
        attachFlagChildren(fileArg);

        return Commands.literal("paste")
                .requires(src -> src.getSender().hasPermission("litematica.paste"))
                .then(fileArg);
    }

    /**
     * Attach the four "--no-…" flag literal children to {@code parent}. Each
     * flag terminal both executes the paste and recursively chains the remaining
     * flags so combinations like {@code --no-entities --no-physics} parse.
     *
     * Recursion is bounded by tracking a bitmask of already-used flag bits.
     */
    private void attachFlagChildren(com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> parent) {
        attachFlagChildren(parent, 0);
    }

    private static final int FLAG_NO_ENTITIES      = 1;
    private static final int FLAG_NO_PHYSICS       = 2;
    private static final int FLAG_NO_TILE_ENTITIES = 4;
    private static final int FLAG_NO_PENDING_TICKS = 8;

    private void attachFlagChildren(com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> parent, int usedMask) {
        if ((usedMask & FLAG_NO_ENTITIES) == 0)      parent.then(buildFlagNode("--no-entities",      FLAG_NO_ENTITIES,      usedMask));
        if ((usedMask & FLAG_NO_PHYSICS) == 0)       parent.then(buildFlagNode("--no-physics",       FLAG_NO_PHYSICS,       usedMask));
        if ((usedMask & FLAG_NO_TILE_ENTITIES) == 0) parent.then(buildFlagNode("--no-tile-entities", FLAG_NO_TILE_ENTITIES, usedMask));
        if ((usedMask & FLAG_NO_PENDING_TICKS) == 0) parent.then(buildFlagNode("--no-pending-ticks", FLAG_NO_PENDING_TICKS, usedMask));
    }

    /**
     * Build one flag literal. {@code selfBit} is the bit for this flag (added to
     * usedMask before recursing). Recursion terminates when all 4 bits are used.
     */
    private LiteralArgumentBuilder<CommandSourceStack> buildFlagNode(String name, int selfBit, int usedMask) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(name).executes(this::doPasteImpl);
        attachFlagChildren(node, usedMask | selfBit);
        return node;
    }

    /** Single dispatch point — figures out if coords/yaw/flags were provided from the parsed context. */
    private int doPasteImpl(CommandContext<CommandSourceStack> ctx) {
        boolean withCoords = hasArg(ctx, "x");
        return doPasteImpl(ctx, withCoords);
    }

    private static boolean hasArg(CommandContext<CommandSourceStack> ctx, String name) {
        for (var node : ctx.getNodes()) {
            if (name.equals(node.getNode().getName())) return true;
        }
        return false;
    }

    private int doPasteImpl(CommandContext<CommandSourceStack> ctx, boolean withCoords) {
        CommandSender sender = ctx.getSource().getSender();
        if (!sender.hasPermission("litematica.paste")) {
            sender.sendMessage(Component.text("missing permission litematica.paste", NamedTextColor.RED));
            return 0;
        }

        String fileName = StringArgumentType.getString(ctx, "file");
        File schemFile = resolveSchematicFile(fileName);
        if (schemFile == null || !schemFile.isFile()) {
            sender.sendMessage(Component.text("schematic not found: " + fileName, NamedTextColor.RED));
            return 0;
        }

        Location origin;
        if (withCoords) {
            try {
                double x = DoubleArgumentType.getDouble(ctx, "x");
                double y = DoubleArgumentType.getDouble(ctx, "y");
                double z = DoubleArgumentType.getDouble(ctx, "z");
                World world = senderWorld(sender);
                if (world == null) {
                    sender.sendMessage(Component.text("could not resolve world (run from a player or in a worldful context)", NamedTextColor.RED));
                    return 0;
                }
                origin = new Location(world, x, y, z);
            } catch (IllegalArgumentException e) {
                // shouldn't happen, but defensive
                sender.sendMessage(Component.text("invalid coordinates: " + e.getMessage(), NamedTextColor.RED));
                return 0;
            }
        } else {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("coordinates required (you are not a player)", NamedTextColor.RED));
                return 0;
            }
            origin = player.getLocation();
        }

        int yaw = 0;
        try {
            yaw = IntegerArgumentType.getInteger(ctx, "yaw");
            if (yaw != 0 && yaw != 90 && yaw != 180 && yaw != 270) {
                // round to nearest valid step
                yaw = ((yaw + 45) / 90) * 90 % 360;
            }
        } catch (IllegalArgumentException ignored) {
            // yaw not specified — default 0
        }

        // Flags — Brigadier records traversed literals via getNodes(), so scan it.
        boolean noEntities = false;
        boolean noPhysics = false;
        boolean noTileEntities = false;
        boolean noPendingTicks = false;
        for (var node : ctx.getNodes()) {
            String name = node.getNode().getName();
            switch (name) {
                case "--no-entities" -> noEntities = true;
                case "--no-physics" -> noPhysics = true;
                case "--no-tile-entities" -> noTileEntities = true;
                case "--no-pending-ticks" -> noPendingTicks = true;
                default -> { /* no-op */ }
            }
        }

        // Build options from defaults + config + flags.
        PasteOptions base = PasteOptions.defaults(origin);
        PasteOptions opts = new PasteOptions(
                origin,
                /* placeEntities */     !noEntities && plugin.getConfig().getBoolean("paste.allowEntities", base.placeEntities()),
                /* placeTileEntities */ !noTileEntities && plugin.getConfig().getBoolean("paste.allowTileEntities", base.placeTileEntities()),
                /* placePendingTicks */ !noPendingTicks && plugin.getConfig().getBoolean("paste.allowPendingTicks", base.placePendingTicks()),
                /* deferredPhysics */   !noPhysics && plugin.getConfig().getBoolean("paste.deferredPhysics", base.deferredPhysics()),
                /* observersLast */     plugin.getConfig().getBoolean("paste.observersLast", base.observersLast()),
                /* maxBlocksPerChunkTask */ plugin.getConfig().getInt("paste.maxBlocksPerChunkTask", base.maxBlocksPerChunkTask()),
                /* yawRotation */ yaw,
                /* progress */ null
        );

        final int finalYaw = yaw;
        sender.sendMessage(Component.text("pasting " + fileName + " at "
                + origin.getBlockX() + "," + origin.getBlockY() + "," + origin.getBlockZ()
                + " (yaw=" + finalYaw + ")…", NamedTextColor.GREEN));

        // Read the file — IO is fast, do it on the calling thread.
        LitematicSchematic schem;
        try {
            schem = LitematicReader.read(schemFile.toPath());
        } catch (IOException e) {
            sender.sendMessage(Component.text("failed to read " + fileName + ": " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().log(Level.WARNING, "paste: read failed", e);
            return 0;
        }

        PasteOperation op = new PasteOperation(plugin, schem, opts);
        String ticket = sender.getName() + "#" + opSeq.incrementAndGet();
        activeOps.put(ticket, op);
        sender.sendMessage(Component.text(
                "ticket " + ticket + " — /litematica cancel " + ticket + " to abort",
                NamedTextColor.AQUA));

        CompletableFuture<fr.ekaii.litematica.paste.PasteResult> future = op.execute();

        future.whenComplete((result, throwable) -> {
            activeOps.remove(ticket);
            if (throwable != null) {
                sender.sendMessage(Component.text("paste failed: " + throwable.getMessage(), NamedTextColor.RED));
                plugin.getLogger().log(Level.WARNING, "paste failed", throwable);
                return;
            }
            String tag = op.isCancelled() ? "paste CANCELLED: " : "paste complete: ";
            NamedTextColor color = op.isCancelled() ? NamedTextColor.YELLOW : NamedTextColor.GREEN;
            sender.sendMessage(Component.text(
                    tag + result.blocksPlaced() + " blocks, "
                            + result.entitiesSpawned() + " entities, "
                            + result.tileEntitiesPlaced() + " tile entities, "
                            + result.durationMs() + " ms",
                    color));
            if (!result.errors().isEmpty()) {
                int shown = Math.min(5, result.errors().size());
                sender.sendMessage(Component.text(
                        result.errors().size() + " error(s) — first " + shown + ":",
                        NamedTextColor.YELLOW));
                for (int i = 0; i < shown; i++) {
                    sender.sendMessage(Component.text("  • " + result.errors().get(i), NamedTextColor.YELLOW));
                }
            }
        });
        return 1;
    }

    // ---------------------------------------------------------------- cancel

    private LiteralArgumentBuilder<CommandSourceStack> buildCancelBranch() {
        // `/litematica cancel` cancels all active ops; `/litematica cancel <ticket>`
        // cancels one. Both gated by litematica.paste (you must be able to paste
        // to cancel — admins use litematica.admin can override via OP).
        return Commands.literal("cancel")
                .requires(src -> src.getSender().hasPermission("litematica.paste"))
                .executes(ctx -> doCancel(ctx, null))
                .then(Commands.argument("ticket", StringArgumentType.string())
                        .suggests((c, b) -> {
                            for (String t : activeOps.keySet()) b.suggest(t);
                            return b.buildFuture();
                        })
                        .executes(ctx -> doCancel(ctx, StringArgumentType.getString(ctx, "ticket"))));
    }

    private int doCancel(CommandContext<CommandSourceStack> ctx, String ticket) {
        CommandSender sender = ctx.getSource().getSender();
        if (activeOps.isEmpty()) {
            sender.sendMessage(Component.text("no active paste operations", NamedTextColor.YELLOW));
            return 0;
        }
        if (ticket == null) {
            int count = 0;
            for (PasteOperation op : activeOps.values()) {
                if (op.cancel()) count++;
            }
            sender.sendMessage(Component.text(
                    "cancelled " + count + " paste operation(s)", NamedTextColor.GREEN));
            return count;
        }
        PasteOperation op = activeOps.get(ticket);
        if (op == null) {
            sender.sendMessage(Component.text("no such ticket: " + ticket, NamedTextColor.RED));
            return 0;
        }
        if (op.cancel()) {
            sender.sendMessage(Component.text("cancelled " + ticket, NamedTextColor.GREEN));
            return 1;
        } else {
            sender.sendMessage(Component.text(ticket + " already cancelled", NamedTextColor.YELLOW));
            return 0;
        }
    }

    // ------------------------------------------------------------------ save

    private LiteralArgumentBuilder<CommandSourceStack> buildSaveBranch() {
        RequiredArgumentBuilder<CommandSourceStack, Integer> z2 =
                Commands.argument("z2", IntegerArgumentType.integer())
                        .executes(this::doSave);
        RequiredArgumentBuilder<CommandSourceStack, Integer> y2 =
                Commands.argument("y2", IntegerArgumentType.integer()).then(z2);
        RequiredArgumentBuilder<CommandSourceStack, Integer> x2 =
                Commands.argument("x2", IntegerArgumentType.integer()).then(y2);
        RequiredArgumentBuilder<CommandSourceStack, Integer> z1 =
                Commands.argument("z1", IntegerArgumentType.integer()).then(x2);
        RequiredArgumentBuilder<CommandSourceStack, Integer> y1 =
                Commands.argument("y1", IntegerArgumentType.integer()).then(z1);
        RequiredArgumentBuilder<CommandSourceStack, Integer> x1 =
                Commands.argument("x1", IntegerArgumentType.integer()).then(y1);
        RequiredArgumentBuilder<CommandSourceStack, String> nameArg =
                Commands.argument("name", StringArgumentType.word()).then(x1);

        return Commands.literal("save")
                .requires(src -> src.getSender().hasPermission("litematica.save"))
                .then(nameArg);
    }

    private int doSave(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!sender.hasPermission("litematica.save")) {
            sender.sendMessage(Component.text("missing permission litematica.save", NamedTextColor.RED));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name");
        int x1 = IntegerArgumentType.getInteger(ctx, "x1");
        int y1 = IntegerArgumentType.getInteger(ctx, "y1");
        int z1 = IntegerArgumentType.getInteger(ctx, "z1");
        int x2 = IntegerArgumentType.getInteger(ctx, "x2");
        int y2 = IntegerArgumentType.getInteger(ctx, "y2");
        int z2 = IntegerArgumentType.getInteger(ctx, "z2");

        World world = senderWorld(sender);
        if (world == null) {
            sender.sendMessage(Component.text("could not resolve world (run as a player)", NamedTextColor.RED));
            return 0;
        }

        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        long volume = (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > Integer.MAX_VALUE) {
            sender.sendMessage(Component.text("region too large (" + volume + " cells)", NamedTextColor.RED));
            return 0;
        }
        long maxVolume = plugin.getConfig().getLong("schematic.maxSaveVolume", 16_000_000L);
        if (volume > maxVolume) {
            sender.sendMessage(Component.text("region exceeds maxSaveVolume=" + maxVolume + " (got " + volume + ")", NamedTextColor.RED));
            return 0;
        }

        sender.sendMessage(Component.text(
                "saving region [" + minX + "," + minY + "," + minZ + "]→[" + maxX + "," + maxY + "," + maxZ + "] as "
                        + name + " (" + volume + " cells)…", NamedTextColor.GREEN));

        // Per-chunk read for Folia safety. We collect block states into a flat
        // array, build a palette, and then write the file from a single
        // background task once all per-chunk reads have completed.
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;

        final int[] blocks = new int[sizeX * sizeY * sizeZ];
        final Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        final List<BlockStateEntry> paletteList = new ArrayList<>();
        // Palette index 0 is air by convention.
        paletteIndex.put("minecraft:air", 0);
        paletteList.add(new BlockStateEntry("minecraft:air"));

        int minCX = minX >> 4, maxCX = maxX >> 4;
        int minCZ = minZ >> 4, maxCZ = maxZ >> 4;
        List<CompletableFuture<Void>> reads = new ArrayList<>();
        final long startNs = System.nanoTime();
        // Synchronisation: per-chunk tasks read the world; the palette is
        // shared, so a single lock per chunk merge keeps it consistent.
        final Object paletteLock = new Object();
        final java.util.concurrent.atomic.AtomicLong nonAir = new java.util.concurrent.atomic.AtomicLong();

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                final int fcx = cx, fcz = cz;
                reads.add(FoliaCompat.runOnRegion(plugin, world, fcx, fcz, () -> {
                    int xMinC = Math.max(minX, fcx << 4);
                    int xMaxC = Math.min(maxX, (fcx << 4) + 15);
                    int zMinC = Math.max(minZ, fcz << 4);
                    int zMaxC = Math.min(maxZ, (fcz << 4) + 15);
                    for (int x = xMinC; x <= xMaxC; x++) {
                        for (int z = zMinC; z <= zMaxC; z++) {
                            for (int y = minY; y <= maxY; y++) {
                                Block b;
                                try {
                                    b = world.getBlockAt(x, y, z);
                                } catch (Throwable t) {
                                    continue;
                                }
                                BlockData data = b.getBlockData();
                                String asString = data.getAsString();
                                BlockStateEntry entry = parseBlockState(asString);
                                int idx;
                                synchronized (paletteLock) {
                                    Integer existing = paletteIndex.get(asString);
                                    if (existing == null) {
                                        idx = paletteList.size();
                                        paletteIndex.put(asString, idx);
                                        paletteList.add(entry);
                                    } else {
                                        idx = existing;
                                    }
                                }
                                if (idx != 0) nonAir.incrementAndGet();
                                int lx = x - minX, ly = y - minY, lz = z - minZ;
                                blocks[ly * sizeX * sizeZ + lz * sizeX + lx] = idx;
                            }
                        }
                    }
                }));
            }
        }

        CompletableFuture.allOf(reads.toArray(new CompletableFuture[0])).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                sender.sendMessage(Component.text("save failed: " + throwable.getMessage(), NamedTextColor.RED));
                plugin.getLogger().log(Level.WARNING, "save: chunk read failed", throwable);
                return;
            }
            // Build the schematic POJO + write to disk on an async worker.
            FoliaCompat.runAsync(plugin, () -> {
                try {
                    LitematicSchematic schem = new LitematicSchematic();
                    schem.version = 6;
                    schem.minecraftDataVersion = 0; // unknown — DataFixer-only consumers will skip
                    LitematicMetadata md = schem.metadata;
                    md.name = name;
                    md.author = (sender instanceof Player p) ? p.getName() : "console";
                    md.description = "Saved via /litematica save by " + md.author;
                    md.enclosingSizeX = sizeX;
                    md.enclosingSizeY = sizeY;
                    md.enclosingSizeZ = sizeZ;
                    md.totalBlocks = nonAir.get();
                    md.totalVolume = (long) sizeX * sizeY * sizeZ;
                    md.timeCreated = System.currentTimeMillis();
                    md.timeModified = md.timeCreated;
                    md.regionCount = 1;

                    LitematicRegion region = new LitematicRegion();
                    region.name = name;
                    region.originX = minX;
                    region.originY = minY;
                    region.originZ = minZ;
                    region.sizeX = sizeX;
                    region.sizeY = sizeY;
                    region.sizeZ = sizeZ;
                    region.palette.addAll(paletteList);
                    region.blocks = blocks;
                    region.tileEntities      = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
                    region.entities          = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
                    region.pendingBlockTicks = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
                    region.pendingFluidTicks = new LitematicNbt.NbtList(LitematicNbt.TAG_COMPOUND, new ArrayList<>());
                    schem.regions.put(name, region);

                    File outDir = plugin.getSchematicsDir();
                    if (!outDir.isDirectory()) outDir.mkdirs();
                    File outFile = new File(outDir, name + ".litematic");
                    LitematicWriter.write(outFile.toPath(), schem);
                    long ms = (System.nanoTime() - startNs) / 1_000_000L;
                    sender.sendMessage(Component.text(
                            "saved " + outFile.getName() + " (" + nonAir.get() + " non-air blocks, "
                                    + paletteList.size() + " palette entries, " + ms + " ms)",
                            NamedTextColor.GREEN));
                    // TODO(P1d v2): capture tile-entity NBT + pending ticks + entities via FoliaCompat
                    //                + NmsBridge#fromNmsCompound. Currently blocks-only.
                } catch (Throwable t) {
                    sender.sendMessage(Component.text("save write failed: " + t.getMessage(), NamedTextColor.RED));
                    plugin.getLogger().log(Level.WARNING, "save: write failed", t);
                }
            });
        });
        return 1;
    }

    /** Parses a Bukkit BlockData {@code getAsString()} form back into {@link BlockStateEntry}. */
    static BlockStateEntry parseBlockState(String s) {
        // s is e.g. "minecraft:oak_log[axis=y]"
        int br = s.indexOf('[');
        if (br < 0) return new BlockStateEntry(s);
        String name = s.substring(0, br);
        String props = s.substring(br + 1, s.length() - 1); // drop trailing ]
        Map<String, String> map = new LinkedHashMap<>();
        if (!props.isEmpty()) {
            for (String pair : props.split(",")) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                map.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return new BlockStateEntry(name, map);
    }

    // -------------------------------------------------------------- materials

    private LiteralArgumentBuilder<CommandSourceStack> buildMaterialsBranch() {
        return Commands.literal("materials")
                .requires(src -> src.getSender().hasPermission("litematica.materials"))
                .then(Commands.argument("file", StringArgumentType.string())
                        .suggests(schematicFileSuggestions())
                        .executes(this::doMaterials));
    }

    private int doMaterials(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!sender.hasPermission("litematica.materials")) {
            sender.sendMessage(Component.text("missing permission litematica.materials", NamedTextColor.RED));
            return 0;
        }
        String fileName = StringArgumentType.getString(ctx, "file");
        File schemFile = resolveSchematicFile(fileName);
        if (schemFile == null || !schemFile.isFile()) {
            sender.sendMessage(Component.text("schematic not found: " + fileName, NamedTextColor.RED));
            return 0;
        }
        LitematicSchematic schem;
        try {
            schem = LitematicReader.read(schemFile.toPath());
        } catch (IOException e) {
            sender.sendMessage(Component.text("failed to read " + fileName + ": " + e.getMessage(), NamedTextColor.RED));
            return 0;
        }
        // Tally: blockName → count (drop properties; group by block name).
        Map<String, Long> tally = new LinkedHashMap<>();
        for (LitematicRegion region : schem.regionsList()) {
            if (region.blocks == null) continue;
            // Per-palette-index occurrence count
            long[] perIdx = new long[region.palette.size()];
            for (int b : region.blocks) {
                if (b >= 0 && b < perIdx.length) perIdx[b]++;
            }
            for (int i = 0; i < region.palette.size(); i++) {
                BlockStateEntry e = region.palette.get(i);
                String name = e.name().contains(":") ? e.name() : "minecraft:" + e.name();
                if (name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air")) {
                    continue;
                }
                tally.merge(name, perIdx[i], Long::sum);
            }
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(tally.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        sender.sendMessage(Component.text("materials for " + fileName + " (top 30):", NamedTextColor.GOLD));
        int shown = Math.min(30, sorted.size());
        for (int i = 0; i < shown; i++) {
            var e = sorted.get(i);
            sender.sendMessage(Component.text("  " + e.getKey() + ": " + e.getValue(), NamedTextColor.GREEN));
        }
        if (sorted.size() > 30) {
            sender.sendMessage(Component.text("  … " + (sorted.size() - 30) + " more", NamedTextColor.YELLOW));
        }
        return 1;
    }

    // ------------------------------------------------------------------- list

    private LiteralArgumentBuilder<CommandSourceStack> buildListBranch() {
        return Commands.literal("list")
                .requires(src -> src.getSender().hasPermission("litematica.use"))
                .executes(ctx -> doList(ctx, null))
                .then(Commands.literal("--filter")
                        .then(Commands.argument("glob", StringArgumentType.string())
                                .executes(ctx -> doList(ctx, StringArgumentType.getString(ctx, "glob")))));
    }

    private int doList(CommandContext<CommandSourceStack> ctx, String glob) {
        CommandSender sender = ctx.getSource().getSender();
        File dir = plugin.getSchematicsDir();
        if (!dir.isDirectory()) {
            sender.sendMessage(Component.text("schematics dir not found: " + dir.getAbsolutePath(), NamedTextColor.YELLOW));
            return 0;
        }
        PathMatcher matcher = (glob == null)
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + glob);
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".litematic"));
        if (files == null || files.length == 0) {
            sender.sendMessage(Component.text("no .litematic files in " + dir.getAbsolutePath(), NamedTextColor.YELLOW));
            return 0;
        }
        java.util.Arrays.sort(files, Comparator.comparing(File::getName));
        sender.sendMessage(Component.text("schematics in " + dir.getName() + "/:", NamedTextColor.GOLD));
        int shown = 0;
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for (File f : files) {
            if (matcher != null && !matcher.matches(f.toPath().getFileName())) continue;
            shown++;
            String mtime;
            int paletteSize = -1;
            try {
                BasicFileAttributes attrs = Files.readAttributes(f.toPath(), BasicFileAttributes.class);
                mtime = fmt.format(attrs.lastModifiedTime().toInstant().atZone(ZoneId.systemDefault()));
            } catch (IOException e) {
                mtime = "?";
            }
            try {
                LitematicSchematic s = LitematicReader.read(f.toPath());
                paletteSize = 0;
                for (LitematicRegion r : s.regionsList()) paletteSize += r.palette.size();
            } catch (IOException ignored) {
                // unreadable — show without palette
            }
            sender.sendMessage(Component.text("  ")
                    .append(Component.text(f.getName(), NamedTextColor.AQUA))
                    .append(Component.text("  " + f.length() + " B  " + mtime
                            + (paletteSize >= 0 ? "  palette=" + paletteSize : "  unreadable"),
                            NamedTextColor.GREEN)));
        }
        if (shown == 0) {
            sender.sendMessage(Component.text("(no files matching filter)", NamedTextColor.YELLOW));
        }
        return 1;
    }

    // ------------------------------------------------------------------- info

    private LiteralArgumentBuilder<CommandSourceStack> buildInfoBranch() {
        return Commands.literal("info")
                .requires(src -> src.getSender().hasPermission("litematica.use"))
                .then(Commands.argument("file", StringArgumentType.string())
                        .suggests(schematicFileSuggestions())
                        .executes(this::doInfo));
    }

    private int doInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String fileName = StringArgumentType.getString(ctx, "file");
        File schemFile = resolveSchematicFile(fileName);
        if (schemFile == null || !schemFile.isFile()) {
            sender.sendMessage(Component.text("schematic not found: " + fileName, NamedTextColor.RED));
            return 0;
        }
        LitematicSchematic schem;
        try {
            schem = LitematicReader.read(schemFile.toPath());
        } catch (IOException e) {
            sender.sendMessage(Component.text("failed to read " + fileName + ": " + e.getMessage(), NamedTextColor.RED));
            return 0;
        }

        LitematicMetadata md = schem.metadata;
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String created = md.timeCreated > 0
                ? fmt.format(Instant.ofEpochMilli(md.timeCreated).atZone(ZoneId.systemDefault()))
                : "(unknown)";
        sender.sendMessage(Component.text("=== " + fileName + " ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("name: " + (md.name == null ? "" : md.name), NamedTextColor.GREEN));
        sender.sendMessage(Component.text("author: " + (md.author == null ? "" : md.author), NamedTextColor.GREEN));
        sender.sendMessage(Component.text("description: " + (md.description == null ? "" : md.description), NamedTextColor.GREEN));
        sender.sendMessage(Component.text("created: " + created, NamedTextColor.GREEN));
        sender.sendMessage(Component.text("MC data version: " + schem.minecraftDataVersion, NamedTextColor.GREEN));
        sender.sendMessage(Component.text("schema version: " + schem.version
                + (schem.subVersion != null ? "." + schem.subVersion : ""), NamedTextColor.GREEN));
        sender.sendMessage(Component.text("enclosing size: " + md.enclosingSizeX + "×" + md.enclosingSizeY + "×" + md.enclosingSizeZ,
                NamedTextColor.GREEN));
        sender.sendMessage(Component.text("totalBlocks: " + md.totalBlocks + "  totalVolume: " + md.totalVolume, NamedTextColor.GREEN));
        sender.sendMessage(Component.text("regions: " + schem.regions.size(), NamedTextColor.GOLD));
        for (LitematicRegion r : schem.regionsList()) {
            int teCount = r.tileEntities == null ? 0 : r.tileEntities.size();
            int enCount = r.entities == null ? 0 : r.entities.size();
            sender.sendMessage(Component.text("  • " + r.name + " @ ("
                    + r.originX + "," + r.originY + "," + r.originZ + ") size "
                    + r.sizeX + "×" + r.sizeY + "×" + r.sizeZ
                    + "  palette=" + r.palette.size()
                    + "  blocks=" + r.countNonAir()
                    + "  te=" + teCount
                    + "  ent=" + enCount, NamedTextColor.GREEN));
        }
        return 1;
    }

    // ----------------------------------------------------------------- reload

    private LiteralArgumentBuilder<CommandSourceStack> buildReloadBranch() {
        return Commands.literal("reload")
                .requires(src -> src.getSender().hasPermission("litematica.admin"))
                .executes(this::doReload);
    }

    private int doReload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!sender.hasPermission("litematica.admin")) {
            sender.sendMessage(Component.text("missing permission litematica.admin", NamedTextColor.RED));
            return 0;
        }
        plugin.reloadConfig();
        File dir = plugin.getSchematicsDir();
        if (!dir.isDirectory()) dir.mkdirs();
        sender.sendMessage(Component.text("config reloaded; schematics dir = " + dir.getAbsolutePath(),
                NamedTextColor.GREEN));
        return 1;
    }

    // ------------------------------------------------------------- help/utils

    private int executeHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(Component.text("LitematicaFolia commands:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  /litematica paste <file> [x y z] [yaw] [--no-entities|--no-physics|--no-tile-entities|--no-pending-ticks]", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("  /litematica save <name> <x1 y1 z1> <x2 y2 z2>", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("  /litematica materials <file>", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("  /litematica list [--filter <glob>]", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("  /litematica info <file>", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("  /litematica reload", NamedTextColor.GREEN));
        return 1;
    }

    private SuggestionProvider<CommandSourceStack> schematicFileSuggestions() {
        return (ctx, builder) -> {
            File dir = plugin.getSchematicsDir();
            if (dir.isDirectory()) {
                File[] files = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".litematic"));
                if (files != null) {
                    String remaining = builder.getRemainingLowerCase();
                    for (File f : files) {
                        if (remaining.isEmpty() || f.getName().toLowerCase().startsWith(remaining)) {
                            builder.suggest(f.getName());
                        }
                    }
                }
            }
            return builder.buildFuture();
        };
    }

    /**
     * Resolve a schematic file by name. If the name lacks {@code .litematic}, we
     * append it. Path traversal is rejected.
     */
    private File resolveSchematicFile(String name) {
        if (name == null || name.isBlank()) return null;
        if (name.contains("..") || name.contains("/") || name.contains("\\")) return null;
        String fname = name.toLowerCase().endsWith(".litematic") ? name : name + ".litematic";
        return new File(plugin.getSchematicsDir(), fname);
    }

    private static World senderWorld(CommandSender sender) {
        if (sender instanceof Player p) return p.getWorld();
        if (sender instanceof org.bukkit.command.BlockCommandSender bcs) return bcs.getBlock().getWorld();
        // Both ConsoleCommandSender and RemoteConsoleCommandSender (RCON) lack a
        // world binding — fall back to the primary world. Intentionally permissive
        // so the smoke harness (and ops) can paste from the console / RCON.
        if (sender instanceof ConsoleCommandSender
                || sender instanceof org.bukkit.command.RemoteConsoleCommandSender) {
            List<World> worlds = org.bukkit.Bukkit.getWorlds();
            return worlds.isEmpty() ? null : worlds.get(0);
        }
        return null;
    }
}
