# Changelog

All notable changes to LitematicaFolia. Format inspired by [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html) with a `+<mc-version>` suffix.

## 0.1.0+26.1.2 — 2026-05-24

First public release. Reads `.litematic` schematics and pastes them into a live Minecraft 26.1.2 world. Folia-safe via per-chunk `RegionScheduler` dispatch. Vanilla-Paper compatible via runtime detection.

### Added

- **P1a — `.litematic` parser** (`core/`): inline NBT reader/writer (all 12 tag types, gzip in/out, magic-byte sniffing), `BlockStateEntry`, `PackedLongArray` (word-aligned 1.16+ encoder/decoder), `LitematicReader` / `LitematicWriter`, `LitematicSchematic` / `LitematicRegion` (sign-normalised). Pure Java, zero external schematic libs. 27+ JUnit 5 round-trip tests.
- **P1b — Folia-safe paste** (`paste/`): `PasteOperation` with two-pass `observersLast` placement, deferred physics sweep, per-chunk dispatch through `FoliaCompat.runOnRegion`. Cooperative `cancel()` flag checked between per-block writes.
- **P1c — NMS bridge** (`nms/`): Paper 26.1.2 implementation (`NmsBridge26_1_2`) — `loadTileEntityNbt` via `BlockEntity.loadWithComponents`, `spawnEntityFromNbt` via `EntityType.loadEntityRecursive` + `tryAddFreshEntityWithPassengers`, scheduled block/fluid ticks, DataFixerUpper (`References.BLOCK_STATE` / `BLOCK_ENTITY` / `ENTITY`), full `Nbt*` ↔ `CompoundTag` round-trip. No-op fallback when NMS classes are missing.
- **P1d — `/litematica` commands** (`command/`): Brigadier registration via `LifecycleEvents.COMMANDS`. Subcommands `paste`, `save`, `materials`, `list`, `info`, `cancel`, `reload`. `paste` supports optional coords, yaw, and chainable `--no-entities` / `--no-physics` / `--no-tile-entities` / `--no-pending-ticks` flags.
- **P1e — smoke harness** (`test-harness/`): hermetic Luminol 26.1.2 boot, RCON shake-out, fail-pattern scan; `paste-smoke.sh` validates `litematica paste` end-to-end on real fixtures; `stress-smoke.sh` covers a 256×64×256 generated fixture (≈4.2 M cells) with a 15-minute hard cap.
- **P2 — Servux-compatible network bridge** (`protocol/`): plugin-channel registration for `servux:litematics`, `servux:structures`, `servux:metadata`; metadata handshake (v3), bulk NBT replies (stubbed), V3 EasyPlace ack, direct-paste streaming. Off by default (`protocol.enableServuxBridge: false`). 8 round-trip protocol tests.
- **Stress fixture & test** (`Fixtures.largeFixture256`, `LargeFixtureTest`): deterministic 256×64×256 generator (80% terrain, 15% chests, 5% redstone). Off by default; flip with `-Dlitematica.stress=true` or via `stress-smoke.sh`.

### Hardened

- `PasteOperation` now logs full stack traces for non-Folia exceptions (previously only `errors` queue capture). Cancellation flag is checked between writes.
- `LitematicaCommands.buildFlagNode` no longer recurses unbounded — `StackOverflowError` on first command registration on Luminol 26.1.2 fixed by bitmask-tracking already-used flags.
- `senderWorld` falls back to the primary world for `RemoteConsoleCommandSender` (RCON), enabling smoke-harness and ops to paste from console without a player binding.

### Known limitations

- `/litematica save` v1 is blocks-only — tile entities, entities, pending ticks are not serialised yet (TODO `NmsBridge.fromNmsCompound`).
- FAWE adapter (Phase 3) is on hold — `mvn.intellectualsites.com` NXDOMAIN. The build script keeps the dependency commented out behind a feature flag.
- Servux bridge is wire-compatible but has not been validated against a real Litematica client; metadata BE/Entity replies are stubbed (`TODO smoke-test with vanilla Litematica client`).
- `protocol.enableServuxBridge` is intentionally `false` by default — operators must opt in.
