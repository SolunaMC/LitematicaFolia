# LitematicaFolia

[![MC](https://img.shields.io/badge/Minecraft-26.1.2-blue)](https://papermc.io/) [![Folia](https://img.shields.io/badge/Folia-supported-green)](https://papermc.io/software/folia) [![JDK](https://img.shields.io/badge/JDK-25-orange)](https://openjdk.org/) [![License](https://img.shields.io/badge/license-MIT-lightgrey)](LICENSE) [![Release](https://img.shields.io/badge/release-v0.1.0%2B26.1.2-brightgreen)](https://forgejo.ekaii.fr/admin_ekaii/litematica-folia-ekaii/releases)

Server-side Paper/Folia plugin that reads `.litematic` schematics (the format produced by [Litematica](https://github.com/maruohon/litematica)) and pastes them into a live Minecraft world.

**Status: 0.1.0 released — first version covering parser, paste, NMS, commands, Servux bridge (opt-in).** See `HANDOFF.md` for build history and `CHANGELOG.md` for release notes.

> Screenshots: _add screenshot here_ (paste in progress, materials list, info pane).

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
