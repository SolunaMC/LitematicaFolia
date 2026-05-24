# LitematicaFolia — autonomous build handoff

> Read this file at the start of every autonomous tick. It is the single source of truth for current state. Update it as work progresses (do NOT branch the truth across files).

## Mission

Production-ready Paper/Folia plugin that reads `.litematic` files and pastes them via the Folia RegionScheduler. First of its kind on Paper.

Mode: **full autonomous** — multi-agent, wakeup every 5–10 min, no user prompts.

## State (last tick: 2026-05-24 ~16:35 — **P11b servux-smoke green; Direct Paste verified headlessly**)

### P11b — protocol bot + servux-smoke (2026-05-24 ~16:35)

- `test-harness/protocol-bot/src/ProtocolBot.java` — single-file headless MC
  26.1.2 client (proto 775) using only `java.net.Socket` +
  `DataInputStream/OutputStream` + the project's own `LitematicNbt`. Drives
  full Handshake → Login → Configuration → Play, answers KeepAlive +
  PlayerPosition + SelectKnownPacks, sends `minecraft:register` then
  `servux:litematics` `C2S_METADATA_REQUEST` and the corrected three-frame
  Litematic-Transmit sequence (Start / Data / End) wrapped in the outer
  type-13 Servux PacketSplitter.
- `test-harness/servux-smoke.sh` — hermetic harness: builds plugin jar,
  boots Luminol 26.1.2, flips `protocol.enableServuxBridge=true` in the
  scratch `plugins/LitematicaFolia/config.yml` (committed config.yml stays
  `false`), waits for `Servux bridge online`, `op`s the bot, compiles the
  bot against `build/classes/java/main`, runs it, then verifies both the
  `paste complete:` log line and region-file mtime advance.
- Packet IDs reverse-engineered via `javap` against the Luminol jar
  (LoginProtocols / ConfigurationProtocols / GameProtocols static-init
  bytecode + BootstrapMethods table). Documented in
  `test-harness/protocol-bot/README.md`.
- **End-to-end PASS after 1 wire-format revision.** First draft used the
  deprecated `STREAM_START(12) + STREAM_DATA(13)` two-packet-type model
  that P11a had already replaced with single-channel type-13 outer
  splitter + Transmit sub-protocol keyed by `SliceKey`. Updated bot to
  send three Transmit frames (Start / Data / End) through the outer
  splitter.
- Bot log shows `METADATA OK — bridge active` (4-key Servux compound:
  `name=litematic_data`, `id=servux:litematics`, `version=1`, `servux=
  LitematicaFolia 0.1.0`). Server log shows
  `[LitematicaFolia/PasteOperation] paste complete: 64 blocks, 0 TE, 0
  entities, 0 err in 237ms`. Stone-cube-4 fixture (4³ solid stone)
  successfully streamed from the bot and placed via PasteOperation +
  RegionScheduler — zero WrongThreadException / SEVERE / RegionFile
  patterns.
- Artefacts committed: `test-harness/servux-smoke.txt` (PASS),
  `test-harness/servux-paste-evidence.log` (server log excerpt).
- **Fix-cycle count: 1** (initial protocol revision after observing
  P11a's converged `DirectPasteHandler`).
- Block-state RCON verification (`execute if block` / `setblock replace`)
  is blocked by a Folia NPE in `Level.getCurrentWorldData()` for commands
  running on a region thread without a player context — we fall back to
  the authoritative `paste complete:` log signal + region-file mtime as
  the success criteria.

### P11a — Servux wire format validated against source (2026-05-24)

- Cloned upstream `sakura-ryoko/servux` at tag `26.1.2-0.10.2` to `/tmp/servux-source` for read-only inspection (no source imported into our repo — LGPL clean).
- Produced `SERVUX_WIRE_FORMAT.md` at the repo root: full spec of every Litematics + Structures packet, primitive encodings, splitter algorithm, Direct Paste sub-protocol, Easy Place V3 reality check. Each table cross-references the exact upstream source line.
- **7 discrepancies fixed in `protocol/*.java`** vs the v0.1.0 scaffolded draft:
  1. Metadata compound was over-specified (`ServerVersion`, `ProtocolVersion`, `Capabilities` extras). Servux clients only consume the 4 canonical keys (`name`, `id`, `version`, `servux`). Stripped.
  2. `ChunkPos` was encoded as two `Int`s. Mojang's `FriendlyByteBuf#writeChunkPos` on MC 26.1.x writes a packed long via `ChunkPos.pack()` (low 32 bits = x, high 32 bits = z). Fixed in `ProtocolBuffer.{read,write}ChunkPos`.
  3. `DirectPasteHandler` was modelled as `STREAM_START → STREAM_DATA*`. **Wrong.** The packet-12 `_NBT_STREAM_START` type never appears on the wire — it is an internal Servux marker that triggers the splitter path. The wire is purely type-13 slices. Rewrote the handler around the actual two-layer model: outer splitter reassembles `(VarInt txnId + NBT)` payloads, and the inner Transmit sub-protocol dispatches on the `Task` field inside the resulting NBT (`Litematic-TransmitStart`/`Data`/`End`/`Cancel`, keyed by `SliceKey`).
  4. New `protocol/PacketSplitter.java` with `split()` + per-player `receive()` matching Servux's `PacketSplitter.send/receive`. First-slice has VarInt(totalLen) prefix; subsequent slices are raw bytes; receiver caps at 16 MiB.
  5. **Easy Place V3 is not a Servux packet.** Servux drives it via a server-side mixin that decodes the protocol value from the X-fractional component of the vanilla `ServerboundUseItemOnPacket`. Our `litematicafolia:easy_place` channel was unfounded. Channel registration is now gated by `protocol.enableEasyPlaceChannel` (default false); the handler + DTO remain for future re-use, but the channel is no longer claimed during handshake.
  6. `CHANNEL_METADATA` was previously aliased the same as `CHANNEL_LITEMATICS` with confusing javadoc suggesting a separate channel. Re-documented as deprecated alias.
  7. `PacketHandler` dispatcher previously routed both type 12 and type 13 to a single "stream frame" entrypoint. Now routes 13 → `onSplitterSlice`; type 12 is tolerated (forwarded to splitter) but never emitted by upstream.
- New `src/test/java/fr/ekaii/litematica/protocol/ServuxByteCompatibilityTest.java`:
  - 10 hand-computed reference byte sequences (`VarInt(-1) = FF FF FF FF 0F`, packed BlockPos, packed ChunkPos, full `C2S_BLOCK_ENTITY_REQUEST` / `C2S_ENTITY_REQUEST` / `C2S_BULK_NBT_REQUEST` / `S2C_BLOCK_NBT_REPLY` / `S2C_METADATA` byte layouts, PacketSplitter round-trip).
  - Each test asserts byte equality on the writer output AND round-trips through the reader. These tests prove our writer/reader pair agrees byte-for-byte with the upstream Servux wire format.
- `./gradlew build` GREEN. `./gradlew test` GREEN — **46 tests** (34 prior + 1 stress-skipped + 1 new fixture round-trip + 10 byte-compat).

## State (previous tick: 2026-05-24 ~05:40 — **v0.1.0 LIVE in prod, autonomous loop terminated**)

### Production hot-load (2026-05-24)

- `mc-creaclone` log @ `11:16:42` UTC: `Loading server plugin LitematicaFolia v0.1.0+26.1.2` → `Enabling` → `LitematicaCommands: /litematica registered via Brigadier.` → `LitematicaFolia ready.` ✓
- `mc-plot` log @ `11:03:06` UTC: `Folia detected: true` → `Brigadier` → `Servux bridge disabled (protocol.enableServuxBridge=false).` → `ready` ✓
- Jars staged at `/opt/mc-stack/{creaclone,plot}/plugins/LitematicaFolia-0.1.0+26.1.2-all.jar` (125 K each, owner `root:exo`).
- **Autonomous loop terminated.** Scope v0.1.x is COMPLETE.

### Open roadmap (future manual ticks)

1. **Save v2 NMS impl** — API surface added (`02a407f`), stubs return null/empty; needs `BlockEntity#saveWithFullMetadata` / `Entity#save` / `ServerLevel.getBlockTicks()` wiring + LitematicaCommands.handleSave() rewrite + round-trip test. Task #10.
2. **Servux real-client smoke** — Fabric client w/ Litematica + Servux against creaclone/plot, `enableServuxBridge=true`. Task #11.
3. **P3 FAWE ClipboardFormat adapter** — blocked on `mvn.intellectualsites.com` NXDOMAIN. Poll DNS periodically. Task #8.

### v0.1.0 release

- Tag `v0.1.0+26.1.2` pushed
- Forgejo release: https://forgejo.ekaii.fr/admin_ekaii/litematica-folia-ekaii/releases/tag/v0.1.0+26.1.2 (id=1766)
- Asset: `LitematicaFolia-0.1.0+26.1.2-all.jar` (≈128 KiB) uploaded
- Smoke harness: **PASS**
- Paste smoke: **PASS** (2 fixtures pasted via RCON; 297 + 64 blocks; latest.log contains `paste complete: …`)
- Stress smoke: **PASS**
  - Fixture: 256×64×256 = 4 194 304 cells, 11-entry palette, deterministic mix (80% terrain / 15% chests / 5% redstone)
  - Disk size: 3 673 750 bytes (3.5 MiB) gzipped
  - Paste duration (wall clock — server-internal): **9.572 s** for 4.2 M blocks
  - Paste 4 194 304 blocks + 629 533 TileEntities, **0 errors**
  - Peak JVM RSS during paste: **3.70 GiB** (heap was -Xmx4G)
  - Total stress-smoke run including boot: 15 s post-fixture-generation
  - No WrongThreadException / OOM / RegionFileSizeException / SEVERE patterns

### Deploy status (Phase E)

- exo reachable; mc-creaclone + mc-plot running healthy
- Jar copied to:
  - `/tmp/LitematicaFolia-0.1.0+26.1.2-all.jar` (staging)
  - `/opt/mc-stack/creaclone/plugins/LitematicaFolia-0.1.0+26.1.2-all.jar`
  - `/opt/mc-stack/plot/plugins/LitematicaFolia-0.1.0+26.1.2-all.jar`
- **No restart performed** — per `feedback_mc_restart_via_rcon` rule. Plugin
  will load on next planned restart.

### P4 — production hardening (2026-05-24 ~05:17)

- Fixed `LitematicaCommands.buildFlagNode` StackOverflowError — was recursing
  unbounded across 4 flags; rewritten with bitmask-tracked usedMask so each
  level only chains flags not yet consumed.
- Added `PasteOperation.cancel()` — cooperative AtomicBoolean checked between
  every block write; `applyChunkBlocks` short-circuits on flip. Wired
  `/litematica cancel [ticket]` command with per-paste ticket id (sender name +
  monotonic counter).
- `PasteOperation` now logs full stack traces for non-Folia exceptions
  (`LOG.log(Level.WARNING, ..., t)`) so non-Folia bugs are not silently
  swallowed via the errors queue.
- `paste-smoke.sh`:
  - Re-boot log-offset bug fixed (was matching previous run's `Done (`).
  - `ls -1 luminol*.jar paper*.jar` no longer kills `set -e pipefail`.
  - World-mtime check broadened to walk children (macOS parent dir mtime
    doesn't tick on file rewrites in place).
  - Treats `paste complete:` log entries as authoritative success signal.
- `senderWorld` now resolves `RemoteConsoleCommandSender` (RCON) to the primary
  world — required for the paste-smoke harness.
- Added 256×64×256 stress fixture (`Fixtures.largeFixture256`) — 80% terrain /
  15% chests / 5% redstone, deterministic RNG seed `0x11717AC711CAL`.
  `LargeFixtureTest` gated on `-Dlitematica.stress=true`. Generated file
  `schematics-fixtures/large-256.litematic` ≈ 3.5 MiB.
- New `test-harness/stress-smoke.sh` — regenerates fixture, boots server, fires
  paste, polls latest.log for `paste complete:` with 15-min hard cap.
- `build.gradle.kts` forwards `-Dlitematica.stress=true` to test JVM; test heap
  bumped to 2 GiB so LargeFixtureTest doesn't OOM.
- New docs: `CHANGELOG.md`, `DISK_USAGE.md`.
- README updated with badges + screenshot placeholder.

## State (previous tick: 2026-05-24 ~05:15 — P1a + P1b + P1c + P1d + P1e + P2 landed)

### Done
- [x] `admin_ekaii/litematica-folia-ekaii` repo created on forgejo.ekaii.fr
- [x] Local repo at `/Users/paulchauvat/litematica-folia-ekaii/` git init'd
- [x] Gradle skeleton (`build.gradle.kts`, `settings.gradle.kts`, `libs.versions.toml`)
- [x] Gradle wrapper copied from axiom-folia
- [x] `.forgejo/workflows/build.yml` (node:20-bookworm + temurin 25 + upload-artifact@v3)
- [x] `paper-plugin.yml` + `plugin.yml` + `config.yml`
- [x] Skeleton `LitematicaFolia` JavaPlugin
- [x] `.gitignore`, `README.md`
- [x] Memory `project_litematica_folia_ekaii.md` + `feedback_forgejo_secret_format.md`
- [x] **P1a — litematic-core parser module** (committed 2026-05-24 ~04:47):
  - `core/LitematicNbt.java` — inline NBT reader/writer (all 12 tag types, gzip in/out, magic-bytes detection)
  - `core/BlockStateEntry.java` — name + properties; `toMinecraftString()` with `minecraft:` default + alphabetical props
  - `core/PackedLongArray.java` — word-aligned 1.16+ encoder/decoder
  - `core/LitematicMetadata.java`, `core/LitematicRegion.java` (sign-normalised), `core/LitematicSchematic.java`
  - `core/LitematicReader.java` / `core/LitematicWriter.java` — gzipped NBT ↔ POJO
  - Pure Java — zero Paper deps, no external schematic libs
  - **27 JUnit 5 tests green** (`./gradlew test`): roundtrip, fixture decode, negative-size normalisation, bitsPerEntry=2/3/5, empty-region edge
  - 2 on-disk fixtures regenerated by tests: `schematics-fixtures/stone-cube-4.litematic` (342 B), `schematics-fixtures/mixed-room-8.litematic` (634 B) — gitignored
  - FAWE compileOnly deps commented out in `build.gradle.kts` (Phase 3 hold — upstream `mvn.intellectualsites.com` NXDOMAIN)
  - `command/LitematicaCommands.java` placeholder added so main source set compiles (P1d will fill it)
- [x] **P1e — smoke harness** : `test-harness/run-tests.sh` (hermetic Luminol 26.1.2 boot + RCON shake-out + 8-pattern log scan), `test-harness/paste-smoke.sh` (fixture paste + world-mtime verification), `test-harness/lib/rcon.py` (stdlib Source RCON), `test-harness/README.md`. Dry-run validated: graceful FAIL on missing jar / missing fixtures.
- [x] **P1b — Folia-safe PasteOperation**
  - `paste/FoliaCompat.java` — RegionScheduler / Async / Global / Entity / loadChunkAsync shim, vanilla Paper fallback
  - `paste/FoliaThreadException.java` — ported verbatim from axiom (`isFoliaThreadException`)
  - `paste/PasteOptions.java` — record + `defaults()` factory
  - `paste/PasteResult.java` — record
  - `paste/PasteOperation.java` — per-chunk dispatch, two-pass observers-last, deferred physics sweep
- [x] **P1c — NMS bridges (Paper 26.1.2)**
  - `nms/NmsBridge.java` — interface + reflective factory (`NmsBridge.get()` loads `NmsBridge26_1_2` via `Class.forName`, falls back to no-op)
  - `nms/NmsBridge26_1_2.java` — Paper 26.1.2 impl. Methods: `loadTileEntityNbt` (BlockEntity.loadWithComponents via TagValueInput), `spawnEntityFromNbt` (EntityType.loadEntityRecursive + tryAddFreshEntityWithPassengers), `scheduleBlockTick` / `scheduleFluidTick` (level.getBlockTicks/getFluidTicks.schedule), `dataFix` (DataFixers.getDataFixer().update with References.BLOCK_STATE/BLOCK_ENTITY/ENTITY), `toNmsCompound` / `fromNmsCompound` (full Nbt* ↔ CompoundTag round-trip).
  - `nms/NoopNmsBridge.java` — logging fallback
- [x] `LitematicaFolia#onEnable()` logs `Folia detected: <bool>` via `FoliaCompat.isFolia()`
- [x] `./gradlew compileJava` GREEN end-to-end (NMS bridge included, no source-set exclusions)
- [x] **Build + smoke validator** (tick 2026-05-24 04:52)
  - `./gradlew build` GREEN → `LitematicaFolia-0.1.0+26.1.2-all.jar` 76K
  - `test-harness/run-tests.sh` PASS on Luminol 26.1.2 (`luminol-paperclip-26.1.2.local-SNAPSHOT.jar`)
  - Server boot 4.335s; plugin `Enabling` + `Folia detected: true` + `ready`; 0 ERROR, 6 benign WARN
  - RCON shake-out OK (op, time, weather, tps, list responded; `save-all` returns "Unknown command" on Folia — autosave handles it; non-fatal, not in fail patterns)
  - Disabled cleanly on RCON `stop` (`save-all flush` would be ideal but harness already calls `stop` which triggers RegionShutdownThread)
  - Fixes applied: `description: "${description}"` quoted in plugin.yml/paper-plugin.yml (Gradle expand was emitting an unquoted colon), additional fail patterns (`Error loading plugin`, `Initialized 0 plugins`), `|| true` guard on luminol*.jar cache lookup

- [x] **P1d — `/litematica` commands** (tick 2026-05-24 ~05:05)
  - `command/LitematicaCommands.java` — Brigadier registration via `getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, …)` (pattern from `axiom-folia/AxiomPaper.java:286`)
  - Subcommands implemented:
    - `/litematica paste <file> [x y z] [yaw] [--no-entities|--no-physics|--no-tile-entities|--no-pending-ticks]` (`litematica.paste`) — tab-completes from `schematics/*.litematic`; flags accepted as Brigadier literal terminals (chainable in any order via recursive subtree); reads schematic, builds `PasteOptions` from defaults + config + flags, dispatches `PasteOperation`, reports progress + completion + first 5 errors
    - `/litematica save <name> <x1 y1 z1> <x2 y2 z2>` (`litematica.save`) — **blocks-only v1** (TODO: TE/entities/pending-ticks via NmsBridge.fromNmsCompound); per-chunk read via `FoliaCompat.runOnRegion`, palette built from `BlockData.getAsString()`, file written async via `FoliaCompat.runAsync`; guarded by `schematic.maxSaveVolume` (default 16M cells)
    - `/litematica materials <file>` (`litematica.materials`) — group by `BlockStateEntry.name()`, drop properties, filter air variants, top 30 by count
    - `/litematica list [--filter <glob>]` (`litematica.use`) — name + size + mtime + palette size per file; PathMatcher with `glob:` prefix
    - `/litematica info <file>` (`litematica.use`) — header (name/author/desc/created/DV/version), per-region (name/origin/size/palette/blocks/TE/entities)
    - `/litematica reload` (`litematica.admin`) — `reloadConfig()` + mkdirs schematics dir
  - All output via Adventure `Component` with GREEN (info/success) / YELLOW (warning) / RED (error) / GOLD (header) / AQUA (filenames)
  - `./gradlew compileJava` GREEN (verified after staging out parallel P2 WIP)

- [x] **P2 — Servux-compatible network channel** (tick 2026-05-24 ~05:15)
  - `protocol/ProtocolConstants.java` — channel names + Servux verbatim packet IDs
    (Litematics: S2C_METADATA=1, C2S_METADATA_REQUEST=2, C2S_BLOCK_ENTITY_REQUEST=3,
    C2S_ENTITY_REQUEST=4, S2C_BLOCK_NBT_REPLY=5, S2C_ENTITY_NBT_REPLY=6,
    C2S_BULK_NBT_REQUEST=7, NBT_STREAM_START/DATA=10-13; Structures 1-12)
    + LitematicaFolia-vendor `litematicafolia:easy_place` (1=request, 2=ack)
  - `protocol/ProtocolBuffer.java` — VarInt / String / BlockPos (packed long) /
    ChunkPos / NBT (anonymous-root 1.20.2+ network form) reader & writer, pure Java
  - `protocol/ServuxBridge.java` — enable / disable / isEnabled; uses Paper
    `Messenger` plugin-channel API (not raw Netty pipeline injection — simpler +
    Folia-safe); gated by `protocol.enableServuxBridge` (default false)
  - `protocol/PacketHandler.java` — VarInt-typed dispatcher across the 3 channels,
    base-gate permission check, build* helpers for replies
  - `protocol/handler/MetadataHandler.java` — handshake: emits NBT compound with
    `name`, `id`, `version=1` (Servux compat), `servux`, `ServerVersion`,
    `ProtocolVersion=3`, `Capabilities=[easy_place_v3, direct_paste, structure_bbox]`
    + stubbed BE/Entity/Bulk replies (TODO: NMS-backed real BE serialisation)
  - `protocol/handler/EasyPlaceHandler.java` — V3 place request: read packed
    BlockPos + state string + optional item NBT + hit-vec + facing + reqId;
    dispatches to `FoliaCompat.runOnRegion` → `block.setBlockData()`; ACKs
    success / reason; doc'd anti-cheat note (server-driven blocks not seen as
    player placements by anti-grief)
  - `protocol/handler/DirectPasteHandler.java` — splitter-aware: STREAM_START
    captures header NBT, STREAM_DATA accumulates slices (first slice has
    leading VarInt total length), on completion builds `LitematicSchematic`
    via `LitematicReader.fromCompound` then **reuses `PasteOperation`** for
    placement; reads origin + paste options from NBT
  - `protocol/handler/StructureBboxHandler.java` — register / unregister /
    spawn metadata stubs (real bbox enumeration left as TODO; needs NMS
    StructureManager access)
  - Permissions: `litematica.protocol.use` / `.paste` / `.easyplace` — all
    `default: false` in plugin.yml + paper-plugin.yml
  - `LitematicaFolia#onEnable()` wires `ServuxBridge.enable(this)` after
    command registration; cleanup in `onDisable()`
  - **8 round-trip JUnit tests green** (`protocol/ProtocolFormatTest.java`):
    VarInt, String UTF-8, BlockPos, ChunkPos, NBT compound, null NBT,
    EasyPlaceRequest byte-equality after decode→encode, item-NBT variant
  - `./gradlew compileJava` GREEN; full test suite **34 tests pass**
  - Open TODOs marked in code with `// TODO smoke-test with vanilla Litematica client`

### In flight / next
- [ ] **P1d v2** — `save` to capture TE/entities/pending-ticks (currently blocks-only; documented TODO)
- [ ] **Re-run smoke harness** with P1d commands jar
- [ ] **P3 FAWE adapter** — on hold (`mvn.intellectualsites.com` NXDOMAIN)
- [ ] **P4 hardening + release** — pending smoke green

## Architecture

```
src/main/java/fr/ekaii/litematica/
├── LitematicaFolia.java          ← JavaPlugin entrypoint
├── core/                          ← parser + POJO (pure Java, testable)
│   ├── LitematicSchematic.java
│   ├── LitematicRegion.java
│   ├── LitematicReader.java       ← NBT gzipped → POJO
│   ├── LitematicWriter.java       ← POJO → NBT gzipped (Phase 1+)
│   ├── BlockStatePalette.java
│   └── PackedLongArray.java       ← BlockStates decode/encode
├── paste/                         ← Folia-safe paste operation
│   ├── PasteOperation.java        ← per-chunk RegionScheduler dispatch
│   ├── PasteOptions.java
│   └── FoliaThreadException.java  ← isFoliaThreadException helper
├── nms/                           ← NMS bridges (TileEntity, Entity, PendingTicks, DFU)
├── command/                       ← /litematica subcommands
├── protocol/                      ← Phase 2: Servux channel
└── compat/                        ← Phase 3: FAWE ClipboardFormat
```

## Folia patterns (inherited from axiom-paper-folia)

| Site | Pattern | Reason |
| --- | --- | --- |
| Chunk mutation | `Bukkit.getRegionScheduler().run(plugin, world, cx, cz, task)` | Axiom v0.1.8 regionfile corruption fix |
| Chunk load | `AsyncScheduler.runNow → getChunkAtAsync → CraftChunk→LevelChunk inside whenComplete` | Folia: `Cannot asynchronously load chunks` from tick thread |
| Pending tasks | `List<CompletableFuture<Void>> pendingMutations` until all done before marking finished | Coherent completion signal |
| `isFoliaThreadException` helper | catches WrongThreadException + NPE on `capturedTileEntities`, `captureTreeGeneration` | Paper APIs crash silently on Folia |
| Entity mutation | `bukkitEntity.getScheduler().run(plugin, task, null)` | Per-entity scheduler |
| Teleport | `player.teleportAsync(loc)` | Region-safe |
| `plugin.yml` / `paper-plugin.yml` | `folia-supported: true` | Required |

## Pitfalls already mitigated

1. Forgejo token has comment header → extract via `grep -oE '[0-9a-f]{40}' ~/.secrets/forgejo-ekaii.txt | head -1`. Saved in feedback memory.
2. CoreProtect maven CF-gated — N/A here (no CoreProtect dep).

## Pitfalls to watch

1. **BlockStates packed array** post-MC 1.16 = word-aligned (no entry crosses long boundaries). Old Litematica `.litematic` v5- may use the old packing.
2. **DataFixerUpper** access — needs NMS `MinecraftServer.getServer().fixerUpper()`. Wrap in nms/ adapter.
3. **TileEntity loadStatic** signature may differ between paperweight mappings — verify on first build.
4. **Folia RegionScheduler** does not exist on plain Paper without Folia — guard `Bukkit.getRegionScheduler()` calls behind a `FoliaCompat` shim that falls back to `getServer().getScheduler().runTask(plugin, runnable)` on vanilla Paper.

## Commands (target)

| Command | Permission | Description |
| --- | --- | --- |
| `/litematica paste <file> [x y z] [yaw] [--no-entities] [--no-physics]` | `litematica.paste` | Paste at the player or coords |
| `/litematica save <name> <x1 y1 z1> <x2 y2 z2>` | `litematica.save` | Export region |
| `/litematica materials <file>` | `litematica.materials` | Material list |
| `/litematica list [--filter glob]` | `litematica.use` | List schematics |
| `/litematica reload` | `litematica.admin` | Reload config |

## Build + test

```bash
./gradlew build
./test-harness/run-tests.sh
```

Smoke harness: hermetic Luminol 26.1.2 boot → RCON → paste 16³ / 64³ / 256³ → scan `latest.log` for `WrongThreadException|SEVERE|RegionFileSizeException`.

## Deployment target

ekaii MC stack on exo: `mc-creaclone`, `mc-plot` (running Luminol 26.1.2 + LuckPerms-Folia).

## Next ticks (priority)

1. Initial commit + push
2. Implement litematic-core parser + unit tests
3. Implement FoliaCompat shim + PasteOperation skeleton
4. Wire smoke harness
5. First green build on Forgejo CI
6. Iterate on paste correctness with bigger fixtures
7. Phase 2: Servux protocol
8. Phase 3: FAWE adapter
9. Phase 4: hardening + release

## Memory pointers

- `project_litematica_folia_ekaii.md` (this project)
- `project_axiom_paper_folia.md` (recipe source)
- `feedback_plugin_port_recipe.md` (25-point checklist)
- `feedback_forgejo_secret_format.md` (token extraction)
- `feedback_autonomous_full_completion.md` (autonomous mode rules)
- `feedback_never_ask_just_do.md` (no validation prompts)
