# test-harness/fabric-client — P11c visual smoke

Local Fabric 1.21.11 client with **Litematica** + **MaLiLib** + **Fabric API**
preinstalled, pointed at the local LitematicaFolia plugin running on
`127.0.0.1:25699`.

This is the **visual safety net** for the P11b autonomous validation: when
the headless test harness can't tell you "the Litematica UI actually rendered
the schematic ghost and the Servux capabilities banner appeared", you boot
this and look with your eyes.

## What goes where

```
test-harness/fabric-client/
├── install-client.sh   ← idempotent installer (downloads MC + Fabric + mods)
├── launch-client.sh    ← classpath builder + Fabric KnotClient launcher
├── README.md
├── instance/           ← MC instance (gitignored; rebuilt by installer)
│   ├── versions/
│   │   ├── 1.21.11/
│   │   │   ├── 1.21.11.jar              MC client jar
│   │   │   └── 1.21.11.json             piston-meta version JSON
│   │   └── fabric-loader-0.19.2-1.21.11/
│   │       └── *.json                   Fabric profile
│   ├── libraries/                       MC + Fabric libs (~107 jars)
│   ├── assets/                          asset index + objects (~4500 files)
│   ├── natives/                         LWJGL native extract dir
│   ├── logs/                            client + launcher logs
│   ├── mods/                            symlinks → ../mods/*.jar
│   ├── options.txt                      reduced settings
│   ├── servers.dat                      (only if --auto-connect was used)
│   └── .install-complete                idempotency marker
└── mods/                ← canonical mod jars (gitignored)
    ├── fabric-api-0.141.4+1.21.11.jar
    ├── malilib-fabric-1.21.11-0.27.12.jar
    └── litematica-fabric-1.21.11-0.26.8.jar
```

The full `instance/` + `mods/` payload is ~250–400 MiB (mostly assets);
committed footprint is **scripts + this README only**.

## Resolved dependencies

| Component        | Version   | Source                                        | Size  |
| ---              | ---       | ---                                           | ---   |
| Minecraft client | 1.21.11   | piston-data.mojang.com                        | 30 MB |
| Fabric Loader    | 0.19.2    | meta.fabricmc.net (latest **stable** for MC 1.21.11) | ~3 MB |
| Fabric Installer | 1.1.1     | maven.fabricmc.net (informational — we drive the profile JSON ourselves) | – |
| Fabric API       | 0.141.4+1.21.11 | Modrinth `P7dR8mSH`                     | ~2 MB |
| MaLiLib          | 0.27.12   | Modrinth `GcWjdA9I`                           | ~2 MB |
| Litematica       | 0.26.8    | Modrinth `bEpr0Arc` (maruohon official)       | ~2 MB |

### Why no Servux jar in `mods/`

The Servux Modrinth listing (`zQhsx8KF`) declares `client_side: unsupported`,
`server_side: required` — it's a server-only mod that **provides** the
Servux network protocol to Litematica clients.

We don't need it on the client because:

1. **The LitematicaFolia plugin emulates the Servux protocol** server-side
   (see `src/main/java/fr/ekaii/litematica/protocol/` — `MetadataHandler`
   advertises `ProtocolVersion=3` + capabilities `[easy_place_v3,
   direct_paste, structure_bbox]`).
2. **Litematica owns the client-side of the Servux protocol natively** —
   it sends/receives Servux packets directly, regardless of whether a
   Servux jar is loaded.

So Litematica connecting to the LitematicaFolia plugin should see the
Servux capabilities banner (if `protocol.enableServuxBridge=true` is set
in `config.yml`).

## Install

```bash
bash test-harness/fabric-client/install-client.sh
```

What it does (in order):

1. Preflight: `curl`, `python3`, `java`, `unzip` present; Mojang manifest reachable.
2. Early-exit if `instance/.install-complete` already exists.
3. Fetch piston version manifest → MC 1.21.11 version JSON.
4. Fetch MC 1.21.11 client jar (sha1-verified).
5. Fetch all MC libraries (~107 jars) filtered by host OS rules, sha1-verified.
6. Fetch asset index + every asset object (~4500 files), sha1-by-content.
7. Fetch Fabric profile JSON + its 8 libraries.
8. Drop the three mods into `mods/`.
9. Write a `options.txt` with low graphics / small window (1280×720) / muted sound.
10. Touch `instance/.install-complete`.

Every download is timestamp-skipped + sha1-verified when sha1 is available,
so rerunning the installer is cheap (a few seconds) and idempotent.

### If Mojang's manifest is unreachable

The installer **exits with a clear error** before touching anything if
DNS / rate limit blocks `https://launchermeta.mojang.com/`. This is the
single most likely failure mode and we don't want to half-install.

## Launch

⚠ **Opens a Minecraft window** — never run from the autonomous loop.

```bash
# default: username LitematicaTester, no auto-connect
bash test-harness/fabric-client/launch-client.sh

# auto-connect to the local plugin server
bash test-harness/fabric-client/launch-client.sh --auto-connect

# override username (offline-mode UUID is derived deterministically)
bash test-harness/fabric-client/launch-client.sh --offline-username Alice

# point at a different host
bash test-harness/fabric-client/launch-client.sh --server 127.0.0.1:25590 --auto-connect
```

Defaults:

- `--Xmx 2G --Xms 1G` (override via `LITEMATICA_CLIENT_XMX=-Xmx4G`)
- mainClass: `net.fabricmc.loader.impl.launch.knot.KnotClient`
- macOS: `-XstartOnFirstThread` is prepended automatically
- Offline UUID = MD5-v3 of `OfflinePlayer:<username>` (same scheme as
  vanilla offline-mode servers, so the server sees a stable identity)

Logs:

- `instance/logs/launcher.log` — full stdout/stderr of the JVM
- `instance/logs/latest.log` — MC's own log4j sink

## Visual verification checklist

When the client window comes up:

1. **Title screen**: bottom-left should show `Minecraft 1.21.11/fabric-loader-0.19.2`.
2. Press **F3** → top-left HUD prints "Fabric Loader 0.19.2" + the loaded
   mod count. Expect at minimum: `fabric-api`, `fabric-loader`,
   `malilib`, `litematica`, `minecraft`, `java`.
3. **Main menu HUD** (bottom-right or top-left, depending on Litematica
   version): a banner like `Litematica 0.26.8` and `MaLiLib 0.27.12`.
4. **Multiplayer** → **Direct Connect** → `127.0.0.1:25699` (or click the
   auto-added `LitematicaFolia (local)` entry if you launched with
   `--auto-connect`).
5. On join, look for the Servux handshake. With `protocol.enableServuxBridge=true`
   in the plugin's `config.yml`, the client should receive metadata packets
   containing `ProtocolVersion=3` + capability list.
   - Litematica's standard probe is in
     **Configuration menus (M)** → **Generic Configs** → "Server Status".
   - F3 + I (copy block to clipboard) on a placed structure also pings
     the bulk-NBT path through the Servux channel.

## Exercise Litematica's paste flow against the local server

Once joined to the server:

1. Place a small build by hand (or `/setblock` via op).
2. **Selection Mode**: hotkey `M` → "Area Selection".
3. Drag corners → name the selection → **Save Schematic** (writes to
   `instance/schematics/`).
4. **Schematic Placement**: hotkey `M` → "Schematic Placement Manager"
   → load the saved file → move it nearby.
5. With LitematicaFolia plugin loaded server-side:
   - If `enableServuxBridge=true` and capabilities include `direct_paste`,
     Litematica's "Execute Operation" or "Paste" should round-trip through
     the Servux protocol channel.
   - Server-side check: tail
     `/opt/mc-stack/<server>/logs/latest.log` for
     `protocol channel handshake completed` + paste-progress lines.

## Constraints honoured

- **No GUI auto-launch.** `install-client.sh` is fully headless;
  `launch-client.sh` is the only entry that opens a window and is **not**
  invoked from install. The autonomous loop must never call
  `launch-client.sh`.
- **Heavy dirs ignored.** `.gitignore` excludes `instance/`, `mods/`, logs,
  marker file. Only scripts + this README are committed.
- **Does not touch creaclone/plot/exo.** Pure localhost setup.
- **Offline-friendly failure.** If Mojang is unreachable, the installer
  refuses to start and prints a clear message.

## Troubleshooting

- **`java: command not found`** → install Temurin 21+ (`brew install temurin`).
  MC 1.21.11 requires Java 21+.
- **`sha1 mismatch`** → delete the offending file in
  `instance/libraries/` or `instance/assets/objects/` and rerun.
- **Black screen + LWJGL crash on macOS** → ensure the launcher arg
  `-XstartOnFirstThread` is present (the script adds it automatically
  on Darwin). If you ran with `-J-Xdock:name=...` or similar overrides,
  double-check ordering.
- **Mods not loading** → check `instance/mods/` symlinks are intact
  (`ls -la instance/mods/`). Re-run `install-client.sh` to recreate them.
- **Connection refused on 127.0.0.1:25699** → start the LitematicaFolia
  plugin server first (`test-harness/run-tests.sh` or the dedicated
  Luminol boot under `test-harness/server/`).
