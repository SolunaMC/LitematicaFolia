# LitematicaFolia — autonomous build handoff

> Read this file at the start of every autonomous tick. It is the single source of truth for current state. Update it as work progresses (do NOT branch the truth across files).

## Mission

Production-ready Paper/Folia plugin that reads `.litematic` files and pastes them via the Folia RegionScheduler. First of its kind on Paper.

Mode: **full autonomous** — multi-agent, wakeup every 5–10 min, no user prompts.

## State (last tick: 2026-05-24 ~04:33 — bootstrap)

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

### In flight / next
- [ ] Initial commit + push to forgejo
- [ ] Spawn agents: litematic-core parser, PasteOperation, smoke harness
- [ ] First green CI build

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
