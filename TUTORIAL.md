# LitematicaFolia tutorial

Step-by-step guides for the two main workflows: server admin paste, and player Direct Paste via Servux.

## Workflow A — admin paste (no client setup needed)

The simplest path. Best for staff/build teams and very large schematics.

### 1. Install the plugin
```bash
# Drop in your server plugins dir
cp LitematicaFolia-0.3.0+26.1.2-all.jar /opt/mc/plugins/
```

Restart the server. On first start the plugin creates `plugins/LitematicaFolia/` with a `schematics/` subdir.

### 2. Drop your `.litematic` file in the schematics dir
```bash
scp myschem.litematic mc-server:/opt/mc/plugins/LitematicaFolia/schematics/
```

### 3. Paste in-game
```
/litematica list                              # see what's available
/litematica info myschem                      # check dimensions / palette / block count
/litematica paste myschem 100 64 -200         # paste at coords
/litematica paste myschem                     # paste at your current location
```

Watch the chat — the plugin reports:
```
[LitematicaFolia] pasting myschem at 100,64,-200 (yaw=0)…
[LitematicaFolia] ticket exo#3 — /litematica cancel exo#3 to abort
[LitematicaFolia] paste complete: 4194304 blocks, 629533 tile entities, 0 entities, 9572 ms
```

### Flags

```
/litematica paste myschem 100 64 -200 --no-entities          # skip entity spawning
/litematica paste myschem 100 64 -200 --no-physics           # paste without block updates (faster)
/litematica paste myschem 100 64 -200 --no-tile-entities     # blocks-only (no chest contents, no signs text)
/litematica paste myschem 100 64 -200 --no-pending-ticks     # don't reschedule pending block/fluid ticks
```

Flags chain in any order.

## Workflow B — Direct Paste from your own client (via Servux)

Best for ongoing build collaboration: every player with the right setup can paste from their personal schematic collection without touching the server filesystem.

### Prerequisites

You need a Fabric 1.21.11 instance with:
- Fabric API
- MaLiLib 0.28.6+
- **Litematica 0.27.6** (or newer for 1.21.11)

Optional but useful: Servux companion mod is **not** needed client-side — Litematica speaks the Servux wire protocol natively.

### Server admin: enable the bridge

Once. In `plugins/LitematicaFolia/config.yml`:
```yaml
protocol:
  enableServuxBridge: true     # was false by default
  maxDirectPasteSize: 134217728  # 128 MiB, raise if you expect 100M+ block uploads
```

Then `/litematica reload` (or restart). Players can now use Direct Paste.

### Client: enable the 4 toggles

Open Litematica menu (`M`), go to **Configs → Generic**, set:

| Toggle | Value |
|---|---|
| `ENTITY_DATA_SYNC` | **true** ← the master switch (don't skip this) |
| `PASTE_USING_SERVUX` | true |
| `PASTE_USE_FILL_COMMAND` | false |
| `PASTE_ALWAYS_USE_FILL` | false |

> ⚠ The `ENTITY_DATA_SYNC` name is misleading. It's not just for entity NBT — it's the entire gate that flips Litematica into "this server speaks Servux" mode. Without it, `PASTE_USING_SERVUX` is ignored and you fall back to `/fill` spam (kicked for spamming).

Then **Configs → Hotkeys**, scroll to find `executeOperation` and bind it to a key (e.g. `=` or `B`). Default is unbound.

### Client: paste your schematic

1. Drop your `.litematic` in your local Litematica schematics dir (or `~/.minecraft/schematics/`)
2. Litematica menu → **Load Schematics** → pick your file → **Load Schematic to Memory**
3. **Loaded Schematics** → click yours → **Create Placement**
4. Position the cyan box in the world (sub-region tool or `Move to player`)
5. Close all menus, hold a stick (or your set Tool Item)
6. Make sure **Tool Mode** at the bottom-left reads **`Paste Schematic in world`** (cycle the tool mode if not)
7. Aim at the placement, press your `executeOperation` keybind

Chat shows:
```
[LitematicaFolia] Direct Paste via Servux — placing 1 region(s) at 1741,128,-2123…
[LitematicaFolia] pasted 869514 blocks in 5747 ms
```

## Troubleshooting

### My ping/lag is terrible after enabling Direct Paste

You probably tuned `velocity.toml` compression-threshold. Don't. The right knob is `[packet-limiter].decompressed-bytes-per-second = -1`.

### Litematica still uses `/fill` and I get kicked

You missed `ENTITY_DATA_SYNC = true`. Re-read the client config section above. It's not optional.

### I get "An internal error occurred in your connection" on paste click

Velocity packet-limiter. Set `decompressed-bytes-per-second = -1` in `velocity.toml [packet-limiter]`. See the README's "Server admin — Velocity proxy tuning" section.

### Paste click → instant disconnect, no error visible

Your schematic's DataVersion is old enough that Mojang's DataFixer takes 25-60 s to upgrade it client-side. Paper kicks the player at the 30 s keep-alive timeout. Two fixes:
1. Server: add `-Dpaper.playerconnection.keepalive=120` to JVM args
2. Client: load the schematic once (let DataFixer run), then **Save Schematic to File** with a new name. The new file is already in current DataVersion and re-paste is instant.

### Paste completes but blocks aren't there

Check `[LitematicaFolia/PasteOperation] paste complete:` in the server log. If `blocks: 0` something silently rejected the placement (region wasn't loaded, permission denied, etc.). If `blocks: N > 0` you're probably looking at the wrong coords — Litematica uses placement Origin not your current position.

### `/litematica save` returns 0 blocks

Known v0.3.0 bug — chunk snapshot staleness after a fresh paste. Wait ~30 s for the chunk to re-tick, OR teleport away and back, OR use the client's Save Area feature instead. Tracked: see roadmap below.

## Next steps

- Read the [README](README.md) for feature comparison and architecture.
- Browse `SERVUX_WIRE_FORMAT.md` if you want to understand the protocol.
- Source / issues at [forgejo.ekaii.fr/admin_ekaii/litematica-folia-ekaii](https://forgejo.ekaii.fr/admin_ekaii/litematica-folia-ekaii).
- Roadmap to v0.4: save-v2 full TE/entities/pending-ticks, FAWE adapter, real-client Easy Place V3 smoke.
