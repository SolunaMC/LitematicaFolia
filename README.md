# LitematicaFolia

Server-side Paper/Folia plugin that reads `.litematic` schematics (the format produced by [Litematica](https://github.com/maruohon/litematica)) and pastes them into a live Minecraft world.

**Status: in-development autonomous build** (see `HANDOFF.md`).

## Why

Litematica is a Fabric **client** mod. Its companion server tooling (Servux, Syncmatica) is also Fabric. There is currently no Paper/Bukkit plugin that natively reads `.litematic` files — FAWE doesn't, and AntiLitematica only blocks them. LitematicaFolia fills that gap.

## Features (target)

- Read `.litematic` v6/v7 (gzip NBT) — palette, packed block states, tile entities, entities, pending ticks
- `/litematica paste <file> [x y z] [--no-entities] [--no-physics]`
- `/litematica save <name> <pos1> <pos2>` — export a region back to `.litematic`
- `/litematica materials <file>` — material list with totals
- Folia-safe: per-chunk RegionScheduler dispatch, no main-thread mutations
- DataFixerUpper bridge for older DataVersion `.litematic` files
- Optional Servux-compatible network channel so vanilla Litematica clients can paste directly (`/litematica protocol enable`)
- Optional FAWE ClipboardFormat registration (`//schem load file.litematic`)

## Build

```bash
./gradlew build
# output: build/libs/LitematicaFolia-<version>-all.jar
```

Requires JDK 25 and a Paper/Folia server at API 26.1 (Paper 26.1.2+).

## Compatibility

| Server | Status |
| --- | --- |
| Paper 26.1.2+ | yes |
| Folia (Paper merged) 26.1.2+ | yes (target) |
| Luminol 26.1.2 ekaii | yes (smoke harness) |
| Older Paper / Spigot / Forge | no |

## License

MIT. Format parsing inspired by [GoldenDelicios/Lite2Edit](https://github.com/GoldenDelicios/Lite2Edit) (MIT) and [SmylerMC/litemapy](https://github.com/SmylerMC/litemapy) (spec only, no code reuse — litemapy is GPL-3).
