# LitematicaFolia

**Server-side Litematica for Paper and Folia.** Load `.litematic` schematics directly on your Minecraft server, paste them with full block-state, tile-entity and entity fidelity, and accept Direct-Paste uploads from vanilla [Litematica](https://github.com/maruohon/litematica) clients via the [Servux](https://github.com/sakura-ryoko/servux) plugin-message protocol: no `/fill` spam, no kick-for-spamming, no `WorldEdit` dependency.

[![release](https://img.shields.io/badge/release-v0.5.1-brightgreen)](https://forgejo.ekaii.fr/admin_ekaii/litematica-folia-ekaii/releases) [![mc](https://img.shields.io/badge/Minecraft-1.21.11%20%7C%2026.1%20%7C%2026.2-blue)](https://papermc.io/) [![api](https://img.shields.io/badge/Paper%20API-1.21-blue)](https://papermc.io/) [![folia](https://img.shields.io/badge/Folia-supported-purple)](https://papermc.io/software/folia) [![jdk](https://img.shields.io/badge/JDK-21%2B-orange)](https://openjdk.org/) [![license](https://img.shields.io/badge/license-MIT-lightgrey)](LICENSE)

> First Paper/Folia plugin to natively parse and paste `.litematic`. Replaces the legacy Litematica `/setblock` + `/fill` fallback (slow, lossy, kick-prone) with a real server-side pipeline.

**What's new in 0.5.1**: Direct Paste now works reliably on Leaves-lineage 26.2 servers (Lophine, Leaves, …): these servers ship their own in-core Servux protocol handling that used to swallow the plugin's `servux:*` channels, leaving client uploads hanging forever. The plugin now reclaims its channels automatically at startup; no config change needed, and a clean no-op on plain Paper/Folia/Luminol.

**Tested on**: Paper 26.2 (build 62, boot + paste), Folia 26.2 (built from `ver/26.2.x` source, no upstream build published yet; boot + full Servux Direct-Paste end-to-end), and Lophine 26.2 (production, channel-reclaim path).

---

## Why does this exist?

[Litematica](https://github.com/maruohon/litematica) is a **client-only** Fabric mod. Its companion server tooling ([Servux](https://github.com/sakura-ryoko/servux), [Syncmatica](https://github.com/sakura-ryoko/syncmatica)) is Fabric-only. On Paper/Bukkit servers the only available client behaviour is the `/setblock`-spam fallback, which is:

- **lossy**: no tile-entity NBT, no entities, no pending block ticks
- **slow**: chat-command rate limit caps it at ~20 blocks/sec
- **fragile**: Paper kicks for spamming after 30+ commands/second

**LitematicaFolia** is the missing piece for the Paper side of the wall. It:

1. Reads `.litematic` files server-side via an inline NBT parser (no external dep)
2. Pastes them via Folia's `RegionScheduler` per-chunk dispatch: observers-last, deferred-physics, full TE and entity NBT
3. Speaks the [Servux](https://github.com/sakura-ryoko/servux) wire protocol so vanilla Litematica clients can Direct-Paste their in-memory schematics directly to the server, the same way it works on a Fabric+Servux server

## Features

- ✅ Native `.litematic` v6/v7 reader and writer (NBT-gzipped, compact cross-word packing, multi-region, palette resolution)
- ✅ Folia-safe paste: per-chunk `RegionScheduler.run`, observers/pistons placed in a second pass, full TileEntity + Entity + PendingBlockTick + PendingFluidTick NBT preserved
- ✅ **Servux Direct Paste**: vanilla Litematica clients can paste from their schematic GUI directly to the server, no `/fill` spam. Speaks BOTH wire eras: v1 (clients up to `26.2-0.28.4`, vanilla-NBT protocol) and v2 (`26.2-0.28.5+`, the "Data Tag" / Task Scheduler protocol), negotiated per player from the metadata handshake
- ✅ DataFixerUpper bridge: older `.litematic` (1.16.5+) are upgraded to the current MC version at paste time
- ✅ Brigadier commands: `/litematica paste|save|materials|list|info|reload|cancel` with permission-gated subcommands
- ✅ Production stress-tested: 4.2M blocks pasted in **9.5 seconds**, 870k blocks + 6,500 tile entities via Direct Paste in **5.6 seconds**, zero region-file corruption
- ✅ Configurable splitter cap (default 128 MiB) so 40 MiB+ schematics go through without rejection
- ✅ **CoreProtect integration**: pasted block changes are logged under the player who ran the paste (`#litematica` for console), with WorldEdit-style removal+placement semantics so `/co rollback` works. Fully optional: no CoreProtect, no overhead. Contributed by [UPSOKen](https://forgejo.ekaii.fr/UPSOKen)

## Installation

```
1. Download LitematicaFolia-<version>-all.jar
2. Drop it in your server plugins/ folder
3. Restart the server (or use a hot-reload plugin like PlugManX)
4. (Optional) Edit plugins/LitematicaFolia/config.yml to enable Servux bridge
5. Done.
```

Requirements:

| | |
|---|---|
| Server | Paper 1.21.11, 26.1.x or 26.2.x (or any fork: Folia, Luminol/Lophine, Leaves, Purpur); one jar for all three |
| Java | 21+ on 1.21.11, 25 on 26.x (what the server itself needs); building needs JDK 25 |
| API version | 1.21 |
| Optional | LuckPerms (perm gating), FastAsyncWorldEdit (future ClipboardFormat adapter) |

> **Version gate**: Bukkit does not stop a too-new plugin from loading, so on anything other than Minecraft 1.21.11, 26.1.x or 26.2.x the plugin logs a SEVERE and disables itself instead of running blind (a mismatched packet pipeline once broke every client join, issue #3). Since 0.10.0 one jar covers all three lines: the server-internal (NMS) calls compile to identical bytecode against the 1.21.11, 26.1.2 and 26.2 dev bundles, which the build can re-check with `./gradlew -PdevBundle=<bundle> test`.

## Client setup: Litematica Direct Paste

For players to paste directly from their own client (no admin upload), they need:

| Mod | Version | Source |
|---|---|---|
| Fabric Loader | 0.16.x+ | [fabricmc.net](https://fabricmc.net) |
| Fabric API | latest for your MC version | [Modrinth](https://modrinth.com/mod/fabric-api) |
| MaLiLib | latest for your MC version | [Modrinth](https://modrinth.com/mod/malilib) |
| **Litematica** | latest for your MC version | [Modrinth](https://modrinth.com/mod/litematica) |

Validated end-to-end with Litematica 0.27.6 + MaLiLib 0.28.6 (26.1-era clients) and re-verified on 26.2. Litematica `26.2-0.28.5` broke the wire (upstream Servux `26.2-0.11.3` "network protocol overhaul": Data Tag compression, protocol version 2, Task Scheduler paste); 0.7.0 speaks both eras and negotiates per player, so 0.28.4- and 0.28.5+ clients paste against the same server. See `SERVUX_WIRE_FORMAT.md` (wire v2 section) for the full delta.

### Critical client config (the one nobody documents)

In Litematica's **Configs → Generic** tab, set:

| Setting | Value | Why |
|---|---|---|
| **`ENTITY_DATA_SYNC`** | **`true`** ← the hidden gate | Without this, Litematica silently rejects our `S2C_METADATA` packet → `servuxRegistered` stays false → falls back to `/setblock` spam |
| **`PASTE_USING_SERVUX`** | `true` | Tells Litematica to route paste through Servux instead of `/fill` |
| `PASTE_USE_FILL_COMMAND` | `false` | Disables the `/fill` fallback path |
| `PASTE_ALWAYS_USE_FILL` | `false` | Same |
| `ENTITY_DATA_SYNC_BACKUP` | `true` | Fallback if main sync drops |

> 🔍 **Why `ENTITY_DATA_SYNC` is named so misleadingly:** Litematica's `EntitiesDataStorage.receiveServuxMetadata()` (decompile, not documented) gates the entire Servux integration on this single flag. The name suggests it only controls entity NBT sync, but it's actually the master switch.

Then bind **`executeOperation`** to a free key in **Configs → Hotkeys** (default `KEY_NONE`). Suggestion: `=`, `B`, or `Backquote`.

### Player workflow

1. Open Litematica menu (`M` by default)
2. **Load Schematics** → pick your `.litematic`
3. **Loaded Schematics** → **Create Placement** → position it
4. Hold the Litematica tool item (a stick, set via Tool Item hotkey)
5. **Tool Mode: Paste Schematic in world** (cycle if needed)
6. Aim at the placement and press your `executeOperation` keybind

Direct Paste flies via the `servux:litematics` channel. Server logs:
```
[direct-paste] inline LitematicaPaste from <player>
paste complete: 869514 blocks, 6516 TE, 0 entities, 0 err in 5747ms
```

## Server admin: Leaves-lineage servers (Lophine, Leaves, …)

Leaves-based servers (including **Lophine**, the Luminol downstream) implement
the Servux protocol **in the server core** and consume every `servux:*`
custom-payload before Bukkit plugins can see it, even with the
`[function.protocol.servux]` toggles off in `lophine_global_config.toml`.
Symptom (through 0.5.0): Litematica clients hang on *"The placement is being
uploaded to Servux for pasting"* with zero server-side logs.

**Fixed in 0.5.1**: when the Servux bridge is enabled, the plugin reclaims its
`servux:litematics` / `servux:structures` channels from the Leaves protocol
core at startup, so Direct Paste and bulk requests reach the plugin as they do
on plain Paper/Folia. On non-Leaves servers this is a clean no-op; if the
reclaim ever fails, the plugin logs a SEVERE line telling you so instead of
failing silently.

## Server admin: Velocity proxy tuning

If your players connect through a Velocity proxy, **two gotchas** kick Direct Paste uploads:

### 1. Velocity 3.5 [packet-limiter] (NEW)

```toml
# velocity.toml
[packet-limiter]
decompressed-bytes-per-second = -1   # default 5 MiB/s, kicks burst paste
```

A 41 MiB schematic streamed in 2s = 20 MB/s of decompressed bytes, which kicks at the 5 MiB/s default. Set to `-1` (or a high number like `104857600` if you want a sanity cap) and restart Velocity.

### 2. Velocity 3.5 compression-threshold

Leave it at the default (`256`). **Do NOT** set to `-1` or to a value above 16 KiB; that disables chunk-data compression and tanks normal play. The old 64× zip-bomb ratio check in Velocity 597 has been removed in build 599+; you don't need to fight it.

### 3. Paper keep-alive timeout

If your players use `.litematic` files with old DataVersion (e.g. 3955 / MC 1.21.0), the client-side DataFixer can freeze for 25-60 s and exceed Paper's default 30 s keep-alive. Add to your server JVM args:

```
-Dpaper.playerconnection.keepalive=120
```

Or tell players to re-save the schematic in their current MC version (Litematica → Schematic Manager → Save Schematic to File).

## Commands

| Command | Permission (default) | Description |
|---|---|---|
| `/litematica paste <file> [x y z] [yaw] [--no-entities] [--no-physics] [--no-tile-entities] [--no-pending-ticks]` | `litematica.paste` (op) | Paste a `.litematic` from the schematics dir |
| `/litematica save <name> <x1 y1 z1> <x2 y2 z2>` | `litematica.save` (op) | Export a region back to `.litematic` (blocks only for now, origin-normalized) |
| `/litematica materials <file>` | `litematica.materials` (op) | Material list of a schematic |
| `/litematica list [--filter <glob>]` | `litematica.use` (op) | List schematics in the dir |
| `/litematica info <file>` | `litematica.use` (op) | Header info + region stats |
| `/litematica cancel [ticket]` | `litematica.paste` (op) | Cancel an in-progress paste |
| `/litematica reload` | `litematica.admin` (op) | Reload config |

All commands are registered via Paper's modern Brigadier API, so suggestions tab-complete (filenames, flags, coordinates).

## Configuration

Default `config.yml` (excerpt):

```yaml
paste:
  maxBlocksPerChunkTask: 8192        # blocks per region-scheduler tick
  allowEntities: true
  allowTileEntities: true
  allowPendingTicks: true            # differentiator vs WorldEdit
  deferredPhysics: true
  observersLast: true                # see Litematica issue #538
  runDataFixer: true                 # NMS DataFixerUpper for older .litematic
  minDataVersion: 2586               # reject schematics older than MC 1.16.5

protocol:
  enableServuxBridge: false          # OFF by default; flip to true to enable Direct Paste
  enableEasyPlace: false             # Easy Place V3 server-side (Netty handler, no PacketEvents)
  maxDirectPasteSize: 134217728      # 128 MiB; raise if you stream 32M+ block schematics

logging:
  coreprotect: true                  # log paste block changes to CoreProtect (if installed)
```

### CoreProtect notes

- Needs CoreProtect **API v10+** (any recent build). The plugin prints the detected API version at startup and falls back to a clean no-op if CoreProtect is missing, too old, or fails mid-paste; auditing never breaks a paste.
- On MC 26.2 you need a CoreProtect build that actually accepts 26.2: stock CE 24.0 predates it and disables itself at boot ("Minecraft 26.2 is not supported"). The integration then stays dormant, pastes are unaffected.
- Tile-entity NBT and entities are not logged (block states only).
- Budget for it on giant pastes: a 4.2M-block paste went from 5.4 s to 7.1 s with logging on, and CoreProtect's consumer queue transiently holds one entry per block. Set `logging.coreprotect: false` if you'd rather paste without records.

## Limitations and known issues

- **`/litematica save` is blocks-only for now.** Saved regions are origin-normalized since 0.5.0 (re-pasting a saved file lands exactly at the coordinates you give it), and the paste/save coordinate contract is guarded by a round-trip smoke test, but TileEntity / Entity / PendingTick capture on save is still on the roadmap. Workaround: use Litematica's Save Area on the client.
- **Direct Paste size cap = client-side limited.** Litematica's `sliceForServux` threshold is 64 MiB NBT; above that it uses a multi-frame Transmit protocol (our handler supports it, untested at scale).
- **Easy Place V3 is gated off by default.** Since 0.8.1 it runs on a direct Netty handler over the server's own packet classes (PacketEvents is gone, issue #5) and applies the full V3 property set (facing, half, axis, slab type, stairs shape, hinge, rail shape, rotation, comparator mode, repeater delay, ...). It has been verified by unit tests and code review against the Servux 26.2 / Litematica 26.2-0.28.x wire, not yet by a real client: `protocol.enableEasyPlace: true` is a canary switch. Beds keep their vanilla orientation.
- **FAWE ClipboardFormat adapter is stubbed.** The FAWE Maven repository (`mvn.intellectualsites.com`) has been unreachable across releases; the adapter will return when the upstream repo is.

## How does it compare?

| | LitematicaFolia | Litematica `/fill` fallback | FastAsyncWorldEdit |
|---|---|---|---|
| `.litematic` support | native | n/a (client-only) | not natively |
| Server-side paste | yes | no | yes (for `.schem`) |
| Direct Paste from client | yes (Servux) | no | no |
| TileEntity preservation | full NBT | none | partial |
| Entity preservation | full NBT | none | partial |
| Pending block ticks | yes | no | no |
| Folia support | native | depends on server | partial |
| Rate-limit kick risk | none | high (50+ cmd/s) | none |

## For developers

The plugin is split into composable modules:

```
fr.ekaii.litematica/
├── core/            ← pure-Java .litematic parser (no Paper deps; reusable)
├── paste/           ← Folia-safe paste operation
├── nms/             ← NMS bridges (TileEntity, Entity, PendingTicks, DataFixer)
├── command/         ← Brigadier command tree
├── protocol/        ← Servux-compatible plugin-message bridge
│   ├── handler/     ← Metadata, Direct Paste, BulkRequest, Structure bbox
│   └── easyplace/   ← Netty handler + state resolver for Easy Place V3
└── compat/          ← FAWE adapter (future)
```

The wire format is documented byte-for-byte in [`SERVUX_WIRE_FORMAT.md`](SERVUX_WIRE_FORMAT.md). A headless Java protocol bot in [`test-harness/protocol-bot/`](test-harness/protocol-bot/) drives the full handshake + Direct Paste flow for end-to-end smoke testing.

## License

[MIT](LICENSE). LitematicaFolia is an original implementation: no upstream code copied; LGPL clean. Format parsing reverse-engineered from the public Litematica spec ([litemapy](https://litemapy.readthedocs.io/), [Lite2Edit](https://github.com/GoldenDelicios/Lite2Edit) MIT). Servux wire format aligned byte-for-byte with upstream [`sakura-ryoko/servux`](https://github.com/sakura-ryoko/servux): wire v1 against `26.1.2-0.10.2` / `26.2-0.11.1` (litematics `PROTOCOL_VERSION=1`, structures `=2`), wire v2 against `26.2-0.11.3` (litematics `PROTOCOL_VERSION=2`, Data Tag packets, Task Scheduler).

## Credits

Made by **exo** for the [ekaii](https://ekaii.fr) Minecraft network. Built on top of the patterns established by [Moulberry/AxiomPaperPlugin](https://github.com/Moulberry/AxiomPaperPlugin) (Folia paste scheduler) and [PaperMC/Paper](https://papermc.io) (the only sane MC server).

Litematica is by [maruohon](https://github.com/maruohon). Servux protocol by [sakura-ryoko](https://github.com/sakura-ryoko). Without their work this plugin would not exist.

CoreProtect paste logging contributed by [UPSOKen](https://forgejo.ekaii.fr/UPSOKen), the first external contribution to this project, and a clean one.

## Links

- [Source](https://forgejo.ekaii.fr/admin_ekaii/litematica-folia-ekaii)
- [Issue tracker](https://forgejo.ekaii.fr/admin_ekaii/litematica-folia-ekaii/issues)
- [Releases](https://forgejo.ekaii.fr/admin_ekaii/litematica-folia-ekaii/releases)
- [Modrinth](https://modrinth.com/plugin/litematicafolia)
