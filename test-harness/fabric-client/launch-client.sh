#!/usr/bin/env bash
# launch-client.sh — P11c launch the local Fabric 1.21.11 client
#
# Builds the classpath from instance/libraries/ + the MC jar + Fabric libs,
# launches via net.fabricmc.loader.impl.launch.knot.KnotClient.
#
# ⚠ Opens a GUI window. Do NOT call from a headless autonomous loop.
#
# Usage:
#   bash launch-client.sh                          # default: LitematicaTester, no auto-connect
#   bash launch-client.sh --auto-connect           # auto-join 127.0.0.1:25699
#   bash launch-client.sh --offline-username Foo   # set offline username
#   bash launch-client.sh --offline-username Foo --auto-connect

set -euo pipefail

# ----------------------------- defaults --------------------------------------
SERVER_HOST="127.0.0.1"
SERVER_PORT="25699"
OFFLINE_USERNAME="LitematicaTester"
AUTO_CONNECT=0
JVM_XMX="${LITEMATICA_CLIENT_XMX:--Xmx2G}"
JVM_XMS="${LITEMATICA_CLIENT_XMS:--Xms1G}"

# ----------------------------- args ------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --auto-connect)
      AUTO_CONNECT=1
      shift ;;
    --offline-username)
      [[ $# -ge 2 ]] || { echo "--offline-username needs an argument" >&2; exit 2; }
      OFFLINE_USERNAME="$2"
      shift 2 ;;
    --server)
      [[ $# -ge 2 ]] || { echo "--server needs HOST:PORT" >&2; exit 2; }
      SERVER_HOST="${2%%:*}"
      SERVER_PORT="${2##*:}"
      shift 2 ;;
    -h|--help)
      sed -n '2,15p' "$0"
      exit 0 ;;
    *)
      echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

# ----------------------------- paths -----------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTANCE_DIR="$SCRIPT_DIR/instance"
MODS_DIR="$SCRIPT_DIR/mods"
MC_VERSION="1.21.11"
FABRIC_LOADER_VERSION="0.19.2"

log() { printf '[launch-client] %s\n' "$*"; }
die() { printf '[launch-client][FATAL] %s\n' "$*" >&2; exit 1; }

# ----------------------------- ensure install --------------------------------
if [[ ! -f "$INSTANCE_DIR/.install-complete" ]]; then
  log "install marker missing — running install-client.sh first"
  bash "$SCRIPT_DIR/install-client.sh"
fi

CLIENT_JAR="$INSTANCE_DIR/versions/$MC_VERSION/$MC_VERSION.jar"
VERSION_JSON="$INSTANCE_DIR/versions/$MC_VERSION/$MC_VERSION.json"
FABRIC_PROFILE_JSON="$INSTANCE_DIR/versions/fabric-loader-${FABRIC_LOADER_VERSION}-${MC_VERSION}/fabric-loader-${FABRIC_LOADER_VERSION}-${MC_VERSION}.json"
LIBRARIES_DIR="$INSTANCE_DIR/libraries"
ASSETS_DIR="$INSTANCE_DIR/assets"
NATIVES_DIR="$INSTANCE_DIR/natives"
GAME_DIR="$INSTANCE_DIR"
LOGS_DIR="$INSTANCE_DIR/logs"

for f in "$CLIENT_JAR" "$VERSION_JSON" "$FABRIC_PROFILE_JSON"; do
  [[ -f "$f" ]] || die "missing: $f — re-run install-client.sh"
done

mkdir -p "$LOGS_DIR" "$NATIVES_DIR" "$GAME_DIR/mods"

# ----------------------------- mods symlink ----------------------------------
# Fabric Loader expects mods in <game_dir>/mods. We keep our canonical pile
# in test-harness/fabric-client/mods/ and symlink each jar in.
for jar in "$MODS_DIR"/*.jar; do
  [[ -f "$jar" ]] || continue
  base="$(basename "$jar")"
  target="$GAME_DIR/mods/$base"
  if [[ -L "$target" || -f "$target" ]]; then
    continue
  fi
  ln -sf "$jar" "$target"
done

# ----------------------------- servers.dat optional --------------------------
if [[ "$AUTO_CONNECT" == "1" ]]; then
  log "auto-connect: ensuring server $SERVER_HOST:$SERVER_PORT in multiplayer list"
  python3 - <<PYEOF
import os, gzip, struct, io

# Minimal NBT writer for servers.dat (uncompressed Java Edition format).
TAG_END=0; TAG_BYTE=1; TAG_SHORT=2; TAG_INT=3; TAG_LONG=4
TAG_FLOAT=5; TAG_DOUBLE=6; TAG_BYTE_ARRAY=7; TAG_STRING=8
TAG_LIST=9; TAG_COMPOUND=10; TAG_INT_ARRAY=11; TAG_LONG_ARRAY=12

def write_string(buf, s):
    b = s.encode('utf-8')
    buf.write(struct.pack('>H', len(b)))
    buf.write(b)

def write_named(buf, tag, name, payload_writer):
    buf.write(struct.pack('>B', tag))
    write_string(buf, name)
    payload_writer(buf)

def write_compound(buf, entries):
    for tag, name, writer in entries:
        write_named(buf, tag, name, writer)
    buf.write(struct.pack('>B', TAG_END))

def make_payload():
    inner = io.BytesIO()
    server = io.BytesIO()
    write_compound(server, [
        (TAG_STRING, 'name',  lambda b: write_string(b, 'LitematicaFolia (local)')),
        (TAG_STRING, 'ip',    lambda b: write_string(b, '${SERVER_HOST}:${SERVER_PORT}')),
        (TAG_BYTE,   'hidden',lambda b: b.write(struct.pack('>b', 0))),
    ])
    server_bytes = server.getvalue()
    # write list of compounds: header = type(1) + length(4)
    inner.write(struct.pack('>B', TAG_COMPOUND))
    inner.write(struct.pack('>i', 1))
    inner.write(server_bytes)
    # outer compound: tag=10, name="", containing TAG_LIST 'servers'
    full = io.BytesIO()
    full.write(struct.pack('>B', TAG_COMPOUND))
    write_string(full, '')  # root name empty
    full.write(struct.pack('>B', TAG_LIST))
    write_string(full, 'servers')
    full.write(inner.getvalue())
    full.write(struct.pack('>B', TAG_END))  # end of root compound
    return full.getvalue()

dest = "$GAME_DIR/servers.dat"
data = make_payload()
# Backup existing servers.dat — never silently clobber
if os.path.exists(dest):
    os.replace(dest, dest + '.bak')
with open(dest, 'wb') as f:
    f.write(data)
print(f'  wrote {dest} ({len(data)} bytes)')
PYEOF
fi

# ----------------------------- classpath -------------------------------------
log "building classpath"

# Read all MC libs (filtered by OS rules — same logic as install)
CLASSPATH="$(python3 - <<PYEOF
import json, os, platform
MC = json.load(open("$VERSION_JSON"))
FABRIC = json.load(open("$FABRIC_PROFILE_JSON"))
ROOT = "$LIBRARIES_DIR"

def os_name():
    s = platform.system().lower()
    if s == 'darwin': return 'osx'
    if s == 'linux': return 'linux'
    if s == 'windows': return 'windows'
    return s

def rule_allows(rules, ctx):
    if not rules: return True
    allow = False
    for r in rules:
        action = r.get('action', 'allow')
        applies = True
        if 'os' in r:
            os_spec = r['os']
            if 'name' in os_spec and os_spec['name'] != ctx['os']:
                applies = False
        if applies:
            allow = (action == 'allow')
    return allow

def maven_to_path(coord):
    parts = coord.split(':')
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = '-' + parts[3] if len(parts) >= 4 else ''
    return f'{group.replace(".","/")}/{artifact}/{version}/{artifact}-{version}{classifier}.jar'

ctx = {'os': os_name(), 'arch': 'x64'}
paths = []

# Fabric libs first — they must win on the classpath (loader bootstrap)
for lib in FABRIC.get('libraries', []):
    rel = maven_to_path(lib['name'])
    full = os.path.join(ROOT, rel)
    if os.path.exists(full):
        paths.append(full)

# MC libraries
for lib in MC.get('libraries', []):
    if not rule_allows(lib.get('rules'), ctx): continue
    art = lib.get('downloads', {}).get('artifact')
    if not art: continue
    full = os.path.join(ROOT, art['path'])
    if os.path.exists(full):
        paths.append(full)

# MC client jar last
paths.append("$CLIENT_JAR")

print(':'.join(paths))
PYEOF
)"

[[ -n "$CLASSPATH" ]] || die "empty classpath — install corrupt?"

# ----------------------------- launch ----------------------------------------
ASSET_INDEX_ID="$(python3 -c "import json; print(json.load(open('$VERSION_JSON'))['assetIndex']['id'])")"

JVM_ARGS=(
  "$JVM_XMX" "$JVM_XMS"
  -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20
  -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M
  "-Djava.library.path=$NATIVES_DIR"
  "-Djna.tmpdir=$NATIVES_DIR"
  "-Dorg.lwjgl.system.SharedLibraryExtractPath=$NATIVES_DIR"
  "-Dio.netty.native.workdir=$NATIVES_DIR"
  -Dminecraft.launcher.brand=litematica-folia-ekaii
  -Dminecraft.launcher.version=0.1
  -Dfabric.skipMcProvider=false
)

if [[ "$(uname)" == "Darwin" ]]; then
  JVM_ARGS=("-XstartOnFirstThread" "${JVM_ARGS[@]}")
fi

# Generate offline-mode UUID v3 from "OfflinePlayer:<name>" (Mojang convention)
OFFLINE_UUID="$(python3 -c "
import hashlib
name='$OFFLINE_USERNAME'
h=hashlib.md5(('OfflinePlayer:'+name).encode('utf-8')).digest()
b=bytearray(h)
b[6]=(b[6]&0x0f)|0x30  # version 3
b[8]=(b[8]&0x3f)|0x80  # variant
hex=b.hex()
print(f'{hex[0:8]}-{hex[8:12]}-{hex[12:16]}-{hex[16:20]}-{hex[20:32]}')
")"

GAME_ARGS=(
  --username       "$OFFLINE_USERNAME"
  --version        "fabric-loader-${FABRIC_LOADER_VERSION}-${MC_VERSION}"
  --gameDir        "$GAME_DIR"
  --assetsDir      "$ASSETS_DIR"
  --assetIndex     "$ASSET_INDEX_ID"
  --uuid           "$OFFLINE_UUID"
  --accessToken    "0"
  --clientId       ""
  --xuid           ""
  --userType       legacy
  --versionType    release
)

if [[ "$AUTO_CONNECT" == "1" ]]; then
  GAME_ARGS+=(--quickPlayMultiplayer "${SERVER_HOST}:${SERVER_PORT}")
fi

log "username:     $OFFLINE_USERNAME"
log "uuid:         $OFFLINE_UUID"
log "server:       $SERVER_HOST:$SERVER_PORT (auto-connect=$AUTO_CONNECT)"
log "asset index:  $ASSET_INDEX_ID"
log "logs:         $LOGS_DIR/launcher.log"
log "launching..."

cd "$GAME_DIR"
exec java "${JVM_ARGS[@]}" \
  -cp "$CLASSPATH" \
  net.fabricmc.loader.impl.launch.knot.KnotClient \
  "${GAME_ARGS[@]}" 2>&1 | tee "$LOGS_DIR/launcher.log"
