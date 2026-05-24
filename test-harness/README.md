# LitematicaFolia smoke harness

Hermetic boot of a Folia (Luminol 26.1.2) server with the freshly-built
LitematicaFolia plugin installed. Verifies the plugin loads, enables, and
survives a quiet window + an RCON shake-out without tripping any
Folia-incompatibility pattern.

## Layout

```
test-harness/
├── run-tests.sh        ← primary smoke harness: boot + RCON + log scan
├── paste-smoke.sh      ← post-PASS paste smoke (runs schematic fixtures)
├── lib/
│   └── rcon.py         ← minimal Source RCON client (stdlib only)
├── server/             ← hermetic server work-dir (gitignored)
└── README.md           ← this file
```

After a run, see:

- `test-harness/result.txt`        — `PASS` / `FAIL` + diagnostics for `run-tests.sh`
- `test-harness/paste-result.txt`  — same for `paste-smoke.sh`
- `test-harness/run.log`           — full JVM stdout/stderr
- `test-harness/server/logs/latest.log` — server-side log

## How to run

### Phase 1: plugin-load smoke

```bash
./test-harness/run-tests.sh
```

Steps:

1. Find the highest-version `build/libs/LitematicaFolia-*-all.jar`.
   If absent, run `./gradlew build` first.
2. Resolve a server jar:
   - reuse `test-harness/server/luminol-paperclip-26.1.2.local-SNAPSHOT.jar` if cached;
   - copy from `~/axiom-folia/test-harness/server/` if available;
   - else download in this order: Forgejo `admin_ekaii/luminol-ekaii` latest release →
     GitHub `LuminolMC/Luminol` latest → Paper 1.21.11 fallback.
3. Write hermetic `eula.txt` + `server.properties` (online-mode=false,
   port 25699, RCON on 25698 / password `lftest`, level `world_smoke`).
4. Copy plugin jar into `server/plugins/`.
5. Boot the JVM (background, `Xmx2G`, JDK 25 module flags).
6. Wait up to 180s for `Done (`.
7. 10s quiet window scanning logs for failure patterns.
8. RCON shake-out: `op @a`, `time set day`, `weather clear`, `tps`, `list`, `save-all`.
9. 15s post-RCON settle.
10. Final log scan; on clean → `PASS`. Graceful `stop` via RCON, then TERM.

### Phase 2: paste smoke

```bash
./test-harness/paste-smoke.sh
```

Requires that `run-tests.sh` has already passed and fixture schematics
exist in `schematics-fixtures/`. Stages them into
`server/plugins/LitematicaFolia/schematics/`, runs
`litematica paste <name> 100 64 100` for each, then `save-all`, then
verifies the world directory mtime advanced (proves real writes
happened) and logs are still clean.

## Failure patterns

Any regex match in `run.log` or `server/logs/latest.log` → FAIL:

| Pattern | Why |
| --- | --- |
| `WrongThreadException` | Hit a Bukkit API from the wrong region thread |
| `is not Folia compatible` | Folia explicit refusal |
| `SEVERE.*[Ll]itematica` | Severe-level log line mentioning the plugin |
| `Exception.*LitematicaFolia` | Any exception explicitly naming the plugin |
| `Could not load plugin` | Plugin load failure |
| `RegionFileSizeException` | Region file corruption (axiom v0.1.8 class) |
| `Region file corruption` | Same family |
| `\[STDERR\]` | Anything Paper redirected to STDERR (usually a stack trace) |

## Tooling

- **JDK 25**: defaults to `/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`,
  override via `$JAVA_HOME`. Falls back to `java` on `$PATH`.
- **RCON**: uses `mcrcon` if installed (`brew install mcrcon`), otherwise
  the bundled `lib/rcon.py` (stdlib only, Source protocol).
- **Network**: only needed on the very first run to download the server
  jar. Subsequent runs are fully offline.

## Troubleshooting

- **`plugin jar not found — wait for P1a/b`** → the parser / paste agents
  haven't committed the code yet. Wait for the next autonomous tick, or
  run `./gradlew build` manually to see the compile error.
- **`./gradlew build FAILED`** → see `test-harness/gradle.log`.
- **`Server did not reach 'Done ('`** → check `run.log` tail. Common
  causes: stale `versions/` cache after a server-jar swap (delete
  `server/versions/` and re-run), JDK version mismatch, port 25699 in
  use (`lsof -ti tcp:25699`).
- **RCON_AUTH_FAIL** → stale `server/server.properties` from a previous
  run. `run-tests.sh` overwrites it on every run, so this should not
  happen unless someone hand-edited it mid-boot.
- **`world dir mtime unchanged`** (paste-smoke only) → the paste command
  did not actually write blocks. Likely the parse failed silently or
  the paste op was a no-op.

## Hermetic guarantees

`run-tests.sh` wipes between runs:
- `result.txt`, `run.log`, `server.pid`, `gradle.log`
- `server/logs/`, `server/world_smoke*`, `server/world*`
- stale `server/usercache.json`, `banned-*`, `ops.json`, `whitelist.json`
- stale `server/plugins/.paper-remapped/`
- old `server/plugins/LitematicaFolia-*.jar`

It keeps `server/versions/` and `server/libraries/` (expensive Paperclip
extraction). Nuke them by hand if you swap server jars.
