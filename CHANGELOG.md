# Changelog

All notable changes to LitematicaFolia. Format inspired by [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html) with a `+<mc-version>` suffix.

## 0.5.0+26.2 — 2026-07-18

### Changed — Minecraft 26.2 port

- **Dev bundle** `26.1.2.build.53-stable` → `26.2.build.62-beta` (no `-stable`
  channel published for 26.2 yet); `api-version` 26.1 → 26.2.
- **NMS drift was a single call site**: `EntityType.loadEntityRecursive` no
  longer accepts a raw `CompoundTag` together with `EntitySpawnReason` (the
  `CompoundTag` overload now pairs with the new `EntitySpawnRequest`). We keep
  `EntitySpawnReason.LOAD` and go through the surviving
  `(ValueInput, Level, EntitySpawnReason, EntityProcessor)` overload by wrapping
  the tag in a `TagValueInput` — the same pattern the bridge already used for
  block entities.
- **`NmsBridge26_1_2` → `NmsBridge26_2`**; `NmsBridge.get()` now walks a
  `BRIDGE_CANDIDATES` list (newest first) instead of hardcoding one class name,
  and logs loudly before degrading to the no-op bridge.
- **Servux wire format re-verified on 26.2** against upstream
  `26.1.2-0.10.2 → 26.2-0.11.1` (20 commits): litematics `PROTOCOL_VERSION=1`
  and structures `=2` unchanged, packet classes untouched. Upstream `0.11.2`
  raised the packet-splitter buffer cap — ours has been 128 MiB configurable
  since 0.3.0. See the 26.2 status note in `SERVUX_WIRE_FORMAT.md`.

### Fixed — stale word-aligned PackedLongArray tests

- Two `PackedLongArrayTest` cases still asserted the **word-aligned** packing
  of the original scaffold and had been failing since the codec was switched to
  the compact **cross-word** `.litematic` layout in the Direct Paste
  byte-alignment work (`4b4e7f1`, validated against a real client). The
  implementation was correct; the tests were stale. They now assert
  `requiredLongs = ceil(entries×bits/64)` and that a 5-bit entry genuinely
  spans a long boundary (v0.4.x releases were cut with these two tests red —
  test suite is green again, enforce it from now on).

### Fixed — the v0.3.0 "0 blocks after paste" bug was a misdiagnosis, and saves now origin-normalize

- Building a save round-trip smoke for 26.2 reproduced the "0 blocks" symptom
  and disproved the staleness theory. The save read path was **always
  correct** (it sees `setblock`-placed blocks and terrain fine). The real
  cause: `stone-cube-4` is generated (`Fixtures.java`) with region
  `Position=(0,64,0)`, and paste follows the Litematica convention
  `world = paste origin + region Position + local` — so pasting at `(x,64,z)`
  puts the cube at `y=128`, and the original repro saved the empty box at
  `y=64..67`. The "CraftChunk snapshot staleness across region threads"
  diagnosis (and the NMS-direct read in `11483cc` that "fixed" it) addressed
  a bug that never existed.
- **Paste now logs each region's effective world placement**
  (`paste region 'main': offset (0,64,0) yaw=0 -> world start (…)`), so a
  baked region offset can never masquerade as a silent no-op again.
- **`/litematica save` now writes region `Position=(0,0,0)`** (relative,
  upstream-idiomatic) instead of baking the absolute world min-corner. Before
  this, pasting a schematic you saved at world `(1000,64,1000)` with
  `/litematica paste s 50 64 50` would land it at `(1050,128,1050)` — the
  same offset trap, self-inflicted. TE/entity/pending-tick coords were
  already region-local; only the region origin was absolute.

### Changed — PacketEvents 2.7.0 → 2.13.0

- PacketEvents 2.7.0 fails to initialize on 26.2-era servers — it can't parse
  the new server version-string format (`Version string must be in the format
  'major.minor[.patch][+commit][-SNAPSHOT]', found '26.2.build.584'`) — which
  silently disabled the Easy Place V3 listener. 2.13.0 (2026-06-22) parses it;
  no API drift at our call sites. Fat jar grows ~1.6 MiB.
- Its update-check thread is now disabled (`checkForUpdates(false)`): the lib
  is shaded so updates ship with plugin releases, and the 2.13 checker
  NoClassDefFoundErrors against the adventure-api Paper 26.2 bundles
  (`Buildable` was removed upstream).

### Changed — test harness

- All four harness scripts now prefer `folia*.jar` in the server-jar globs.
  Trap this fixes: with only an unrecognized jar name present, `run-tests.sh`
  silently fell back to **copying the sibling axiom-folia Luminol 26.1.2 jar**
  and the whole "26.2" battery actually ran on 26.1.2 (caught because the
  protocol-776 bot got kicked with "Outdated server! I'm still on 26.1.2").
- Smoke server is a **source-built Folia `ver/26.2.x`**
  (`folia-paperclip-26.2.local-SNAPSHOT.jar`, `createPaperclipJar` — note:
  not `createMojmapPaperclipJar` anymore). Lophine `26.2-917b2cf` also kept
  for load/paste/save runs.
- `protocol-bot` bumped to protocol **776** (26.2, data version 4903) — all
  packet IDs from 26.1.2 survived unchanged.

### Known issue — Lophine/Leaves servers natively intercept `servux:*` channels

- Lophine (and anything carrying Leaves protocol patches) implements the
  Servux protocol **in the server core** (`org.leavesmc.leaves.protocol.servux`,
  toggles under `[function.protocol.servux]` in `lophine_global_config.toml`).
  `LeavesProtocolManager.handleBytebuf` consumes any payload whose channel has
  a registered receiver **before** the Bukkit Messenger dispatch — and the
  receivers are registered even when config-disabled. Net effect: on Lophine,
  C2S `servux:litematics` traffic never reaches this plugin (Direct Paste /
  bulk requests / metadata requests dead; the S2C metadata push still works).
  Commands, paste, save are unaffected. On real Folia/Paper the Messenger
  path is intact. **Do not deploy the Servux bridge on Leaves-lineage
  servers** — use their native implementation there instead.

### Validation

- 62 unit tests green (1 skipped stress gate).
- Smoke on **Lophine `26.2-917b2cf`** (Luminol downstream, Folia family) AND
  on **Folia `ver/26.2.x` built from source** (no published 26.2 build yet):
  plugin-load smoke PASS, paste smoke PASS, new **save round-trip smoke**
  PASS (see below). Servux protocol-bot smoke (protocol 776) PASS on Folia;
  on Lophine it cannot pass — see known issue above.

### Added — save round-trip smoke (`test-harness/save-smoke.sh`)

- paste `stone-cube-4` at `(100,64,100)` → save the box where it actually
  lands (`y+64`, per the fixture's baked region offset) and assert exactly
  64 blocks → re-paste the saved file (origin-normalized, so it lands exactly
  at the re-paste coordinates) → save that box too and assert 64 again.
  Guards the whole paste/save coordinate contract end-to-end.
- Debug aid: `-Dlitematica.debugPaste=true` logs the first write of each
  chunk batch with thread, region ownership, chunk-loaded state, and a
  read-back of the block just written.

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
