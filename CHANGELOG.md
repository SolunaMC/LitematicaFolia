# Changelog

All notable changes to LitematicaFolia. Format inspired by [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html) with a `+<mc-version>` suffix.

## 0.4.2+26.1.2 — 2026-07-02

### Fixed — PasteOperation semaphore deadlock (continuation loops on region threads)

- **The v0.4.1 fix was incomplete.** v0.4.1 hopped only the *pass-1* dispatch loop off the region tick thread. The **pass-2** loop (`pass1All.thenCompose(...)`) and the **deferred-physics sweep** loop (`pass2All.thenCompose(...)`) were chained with a plain (non-async) `thenCompose`, which runs its body **on the thread that completed the upstream future** — and that future is completed by `done.complete(null)` *inside* `FoliaCompat.runOnRegion`, i.e. on a **Folia region tick thread** (whichever chunk task finished last). Those loops then called the *blocking* `PasteOperation.acquireSlot()` on that region thread. When the same region still had more pending chunk tasks than free `CHUNK_THROTTLE` permits, the region parked in `Semaphore.acquire()` waiting for permits only it could release by running its own queued tasks → **self-deadlock**. The region never ticked again; Folia's Watchdog logged `Tick region … has not responded` forever without crashing. Cumulative across regions (Overworld + Nether). Reproduced on **creaclone**: a region stuck **~116 h** before a manual restart (Folia issue #1), stacks pinned at `PasteOperation.java:293` (pass 2) and `:345` (physics sweep).
- **Fix (two layers):**
  1. **Off-region continuations.** Both continuation loops now run via `thenComposeAsync(..., offRegionExecutor)`, where `offRegionExecutor` submits to the Folia async pool (Paper async worker on non-Folia). Every `acquireSlot()` call therefore runs on an async worker, never a region tick thread — a blocked dispatch thread can no longer park a region.
  2. **Bounded acquire.** `acquireSlot()` now uses `tryAcquire(60 s)` instead of an unbounded `acquire()`, returning whether a permit was actually taken. A timeout dispatches the chunk *unthrottled* and logs a warning rather than blocking forever, and the `releasedOnce` guard is seeded so a permit that was never taken is never over-released. This is a hard safety valve: even a leaked permit can only degrade throttling, never freeze a thread indefinitely.

### Operational notes

- No schema or config changes vs 0.4.1 — straight in-place drop-in.
- Behaviour under normal load is unchanged: permits are available within microseconds, so the timeout path never fires and throttling (32 in-flight chunk tasks) is preserved.

## 0.4.1+26.1.2 — 2026-05-28

### Fixed — region tick-thread deadlock during Direct Paste

- **`PasteOperation.execute()` no longer blocks on a region tick thread.** The v0.4.0 chunk-dispatch throttle (`Semaphore CHUNK_THROTTLE`, 32 permits) acquires permits synchronously in the dispatch loop. When `execute()` was invoked straight from the Servux plugin-message handler (i.e. on the player's region tick thread), and any dispatched chunk task targeted that same region, the runnable's `release()` could never fire — the tick thread was parked in `Semaphore.acquire()`. After 60 s Folia's Watchdog killed the region; the container restarted. Reproduced on creaclone 2026-05-28: ExoRamC pasted in `world_nether [1087, -236]` → 200+ s region freeze → docker exit. Fix: `execute()` is now a thin wrapper that hops the entire dispatch pipeline to `Bukkit.getAsyncScheduler().runNow(...)` before any `acquire()` runs. Per-chunk `runOnRegion(...)` dispatches still target the correct region thread; only the throttle loop moves off-tick.
- **Per-player concurrent-paste gate** in `DirectPasteHandler`. A single Direct Paste can pin an async worker for several seconds; stacking two from the same player doubled regionfile pressure on overlapping chunks. Both `completeAssembly` (Transmit-protocol path) and `handleInlineLitematicaPaste` (maruohon inline path) now refuse a second paste from the same player until the first one completes, with a clear chat message. Distinct players still paste in parallel.

### Operational notes

- No schema or config changes vs 0.4.0 — straight in-place drop-in.
- Recommended `paper-global.yml` watchdog tuning for early signals (applied on creaclone + plot in mc-stack 2026-05-28):
  ```yaml
  watchdog:
    early-warning-every: 5000
    early-warning-delay: 10000
  ```

## 0.3.0+26.1.2 — 2026-05-25

### Added — Direct Paste end-to-end via Servux

- **Inline `LitematicaPaste` task handler** (`protocol/handler/DirectPasteHandler.java`) — supports the maruohon Litematica payload schema (separate from the upstream Servux `Litematic-TransmitStart/Data/End` multi-frame path, both now handled).
- **Servux BulkRequest fulfillment** — server replies to `C2S_BLOCK_ENTITY_REQUEST` (3), `C2S_ENTITY_REQUEST` (4), `C2S_BULK_NBT_REQUEST` (7) with real NBT, with per-player rate limiter.
- **Easy Place V3 server-side scaffold** — PacketEvents 2.7 listener that decodes the X-fractional protocol value from `UseItemOnPacket`. Gated off by default (`protocol.enableEasyPlace`); local Luminol version-string mismatch makes runtime activation flaky on pre-release builds.
- **NMS save extractors** — `extractTileEntityNbt`, `extractEntityNbt`, `extractPendingBlockTicks/Fluids` API surface for `/litematica save` v2. Real impl partial (iteration bug, tracked).
- **256³ stress fixture** (`Fixtures.largeFixture256`) — 4.2 M blocks, 80% terrain / 15% chests / 5% redstone, gated `-Dlitematica.stress=true`.
- **Java protocol bot** (`test-harness/protocol-bot/`) — headless MC client, drives Servux Direct Paste end-to-end for autonomous validation.
- **Local Fabric client harness** (`test-harness/fabric-client/`) — installs Fabric Loader 0.19.2 + MC 1.21.11 + MaLiLib + Litematica 0.27.6 for visual smoke.

### Validated in production

- Direct Paste of **869,514 blocks + 6,516 tile entities in 5.6 s** on creaclone via real Litematica 0.27.6 client.
- Stress paste of **4,194,304 blocks + 629,533 tile entities in 9.5 s**, zero region-file corruption.
- 62 JUnit tests + 3 smoke harnesses (boot, paste, stress) all green reproducibly.

### Fixed

- **Brigadier `StackOverflowError`** at command registration — flag literals chained recursively without dedup. Fixed via bitmask of used-flags.
- **Async paste parse** — `/litematica paste` now parses `.litematic` off the command thread via `FoliaCompat.runAsync`. Eliminates Folia Watchdog ERRORs on schematics > 3 MiB.
- **`PackedLongArray` packing format** — switched from word-aligned (MC 1.16+ chunk-section style) to compact cross-word. `.litematic` uses compact regardless of MC version. Symptom: "Packed array too short" for bitsPerEntry ∈ {5,6,7,9-15}.
- **`SubRegions` vs `Schematics`** — the actual schematic NBT lives under the `Schematics` key in the `LitematicaPaste` payload (not `SubRegions`, which holds per-region placement overrides).
- **PacketSplitter cap** — raised default from upstream Servux's 16 MiB to **128 MiB**, configurable via `protocol.maxDirectPasteSize`. Friendly chat error on oversize instead of silent drop.
- **Servux wire format** — 7 byte-level discrepancies vs upstream `sakura-ryoko/servux 26.1.2-0.10.2` fixed (metadata key set, `ChunkPos` packed-long encoding, splitter framing, Easy Place protocol misconception, deprecated channel alias).
- **`S2C_METADATA` push on join** — Litematica 0.27.x doesn't auto-send `C2S_METADATA_REQUEST` on join. Server now pushes the metadata 3 s after `PlayerJoinEvent` so the client flips `servuxRegistered=true` without user interaction.

### Documented — production gotchas

These are critical to know if you're deploying:

- **Litematica client gate**: `ENTITY_DATA_SYNC = true` must be set client-side or Servux integration silently no-ops. `PASTE_USING_SERVUX = true` alone is useless. The name is misleading; it's the master switch.
- **Velocity 3.5 `[packet-limiter] decompressed-bytes-per-second`** defaults to 5 MiB/s → kicks Direct Paste burst uploads. Set to `-1`.
- **Velocity 3.5 `compression-threshold`** should stay at default 256. Setting to `-1` tanks normal play (uncompressed chunks). The 64× zip-bomb ratio check was removed in build 599+.
- **Paper keep-alive default 30 s** — set `-Dpaper.playerconnection.keepalive=120` for clients running Mojang DataFixer on old `.litematic` files (e.g. DataVersion 3955 from MC 1.21.0).

## 0.2.0+26.1.2 — 2026-05-24

### Added — Servux protocol alignment

- Servux wire-format aligned byte-for-byte with upstream `sakura-ryoko/servux 26.1.2-0.10.2` (`SERVUX_WIRE_FORMAT.md`).
- 10 `ServuxByteCompatibilityTest` cases proving byte-equality with reference sequences.
- `PacketSplitter` reassembly matching upstream `PacketSplitter.send/receive` (first-slice VarInt prefix, 16 MiB cap).

### Fixed

- `ChunkPos` encoding switched from two `Int`s to packed signed long.
- `Easy Place V3` is not a custom Servux packet — Servux drives it via a server-side mixin on `ServerboundUseItemOnPacket`. Vendor-channel was a misunderstanding, now gated.

## 0.1.0+26.1.2 — 2026-05-24

First public release. Reads `.litematic` schematics and pastes them into a live Minecraft 26.1.2 world. Folia-safe via per-chunk `RegionScheduler` dispatch. Vanilla-Paper compatible via runtime detection.

### Added

- **`.litematic` parser** (`core/`): inline NBT reader/writer (all 12 tag types, gzip in/out, magic-byte sniffing), `BlockStateEntry`, `PackedLongArray`, `LitematicReader` / `LitematicWriter`, `LitematicSchematic` / `LitematicRegion` (sign-normalised). Pure Java, zero external schematic libs. 27+ JUnit 5 round-trip tests.
- **Folia-safe paste** (`paste/`): `PasteOperation` with two-pass `observersLast` placement, deferred physics sweep, per-chunk dispatch through `FoliaCompat.runOnRegion`. Cooperative `cancel()` flag.
- **NMS bridge** (`nms/`): Paper 26.1.2 implementation — `loadTileEntityNbt` via `BlockEntity.loadWithComponents`, `spawnEntityFromNbt` via `EntityType.loadEntityRecursive` + `tryAddFreshEntityWithPassengers`, scheduled block/fluid ticks, DataFixerUpper.
- **`/litematica` commands** (`command/`): Brigadier registration via `LifecycleEvents.COMMANDS`. Subcommands `paste`, `save`, `materials`, `list`, `info`, `cancel`, `reload`.
- **Smoke harness** (`test-harness/`): hermetic Luminol 26.1.2 boot, RCON shake-out, fail-pattern scan; `paste-smoke.sh` validates `litematica paste` end-to-end on real fixtures; `stress-smoke.sh` covers a 256×64×256 generated fixture (≈4.2 M cells) with a 15-minute hard cap.

[0.3.0+26.1.2]: https://forgejo.ekaii.fr/admin_ekaii/litematica-folia-ekaii/releases/tag/v0.3.0+26.1.2
[0.2.0+26.1.2]: https://forgejo.ekaii.fr/admin_ekaii/litematica-folia-ekaii/releases/tag/v0.2.0+26.1.2
[0.1.0+26.1.2]: https://forgejo.ekaii.fr/admin_ekaii/litematica-folia-ekaii/releases/tag/v0.1.0+26.1.2
