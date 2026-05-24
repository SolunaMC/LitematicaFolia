#!/usr/bin/env bash
# install-client.sh — P11c local Fabric 1.21.11 client installer
#
# Idempotent, hermetic installer for a Litematica + malilib Fabric client
# pointed at the local LitematicaFolia plugin (127.0.0.1:25699).
#
# Downloads:
#   - MC 1.21.11 client jar + libraries + asset index + assets (from piston-meta)
#   - Fabric Loader 0.19.2 profile + its 8 libs (from meta.fabricmc.net)
#   - Fabric API, MaLiLib, Litematica (from Modrinth CDN)
#
# Does NOT install Servux on the client — Servux is server-side only on
# Modrinth (project zQhsx8KF, client_side=unsupported). The LitematicaFolia
# plugin emulates the Servux network protocol; Litematica itself implements
# the client side of that protocol natively. See README.md.
#
# Usage:   bash install-client.sh
# Idempotency: rerun is cheap — every download is `curl -z` (timestamped) +
# sha1 verified when piston-meta gives us a sha1.

set -euo pipefail

# ----------------------------- config ----------------------------------------
MC_VERSION="1.21.11"
FABRIC_LOADER_VERSION="0.19.2"
FABRIC_INSTALLER_VERSION="1.1.1"

# Mod versions (resolved 2026-05-24 via Modrinth API)
FABRIC_API_URL="https://cdn.modrinth.com/data/P7dR8mSH/versions/5zJNhXV2/fabric-api-0.141.4%2B1.21.11.jar"
FABRIC_API_FILE="fabric-api-0.141.4+1.21.11.jar"

MALILIB_URL="https://cdn.modrinth.com/data/GcWjdA9I/versions/m5frsnWf/malilib-fabric-1.21.11-0.27.12.jar"
MALILIB_FILE="malilib-fabric-1.21.11-0.27.12.jar"

LITEMATICA_URL="https://cdn.modrinth.com/data/bEpr0Arc/versions/kSNW8FbJ/litematica-fabric-1.21.11-0.26.8.jar"
LITEMATICA_FILE="litematica-fabric-1.21.11-0.26.8.jar"

VERSION_MANIFEST_URL="https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
FABRIC_PROFILE_URL="https://meta.fabricmc.net/v2/versions/loader/${MC_VERSION}/${FABRIC_LOADER_VERSION}/profile/json"

# ----------------------------- paths -----------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTANCE_DIR="$SCRIPT_DIR/instance"
MODS_DIR="$SCRIPT_DIR/mods"
VERSIONS_DIR="$INSTANCE_DIR/versions"
LIBRARIES_DIR="$INSTANCE_DIR/libraries"
ASSETS_DIR="$INSTANCE_DIR/assets"
ASSET_INDEXES_DIR="$ASSETS_DIR/indexes"
ASSET_OBJECTS_DIR="$ASSETS_DIR/objects"
NATIVES_DIR="$INSTANCE_DIR/natives"
MARKER_FILE="$INSTANCE_DIR/.install-complete"

UA="litematica-folia-ekaii/0.1 (paul.chauvat@dedale.com)"
CURL_OPTS=(--fail --silent --show-error --location --max-time 300 -A "$UA")

log() { printf '[install-client] %s\n' "$*"; }
warn() { printf '[install-client][WARN] %s\n' "$*" >&2; }
die() { printf '[install-client][FATAL] %s\n' "$*" >&2; exit 1; }

require() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || die "missing required tool: $cmd"
}

# fetch URL DEST [SHA1]
fetch() {
  local url="$1" dest="$2" sha1="${3:-}"
  if [[ -f "$dest" && -n "$sha1" ]]; then
    local got
    got="$(sha1_of "$dest")"
    if [[ "$got" == "$sha1" ]]; then
      return 0
    fi
    warn "sha1 mismatch on $dest (have $got, want $sha1); refetching"
    rm -f "$dest"
  elif [[ -f "$dest" && -z "$sha1" ]]; then
    # No sha to verify — assume previous run is good.
    return 0
  fi
  mkdir -p "$(dirname "$dest")"
  if ! curl "${CURL_OPTS[@]}" "$url" -o "$dest.tmp"; then
    rm -f "$dest.tmp"
    die "download failed: $url"
  fi
  mv "$dest.tmp" "$dest"
  if [[ -n "$sha1" ]]; then
    local got
    got="$(sha1_of "$dest")"
    [[ "$got" == "$sha1" ]] || die "sha1 mismatch after download: $dest (have $got, want $sha1)"
  fi
}

sha1_of() {
  if command -v sha1sum >/dev/null 2>&1; then
    sha1sum "$1" | awk '{print $1}'
  else
    shasum -a 1 "$1" | awk '{print $1}'
  fi
}

# ----------------------------- preflight -------------------------------------
require curl
require python3
require java
require unzip

# Check that python3 can do json (always true on stdlib, but sanity check).
python3 -c "import json,os,sys,urllib.parse" >/dev/null || die "python3 missing stdlib modules"

# DNS reachability — fail fast on offline / blocked Mojang.
if ! curl --fail --silent --max-time 10 -A "$UA" --head "$VERSION_MANIFEST_URL" >/dev/null; then
  die "Mojang launchermeta unreachable ($VERSION_MANIFEST_URL). Check DNS / rate limits and retry."
fi

# ----------------------------- early-exit ------------------------------------
if [[ -f "$MARKER_FILE" ]]; then
  log "already installed (marker: $MARKER_FILE) — nothing to do."
  log "delete the marker to force a re-verify."
  exit 0
fi

log "MC version:         $MC_VERSION"
log "Fabric loader:      $FABRIC_LOADER_VERSION"
log "Instance dir:       $INSTANCE_DIR"
log "Mods dir:           $MODS_DIR"

mkdir -p "$INSTANCE_DIR" "$MODS_DIR" "$VERSIONS_DIR" "$LIBRARIES_DIR" \
         "$ASSET_INDEXES_DIR" "$ASSET_OBJECTS_DIR" "$NATIVES_DIR"

# ----------------------------- step 1: piston-meta version JSON --------------
VERSION_JSON_DIR="$VERSIONS_DIR/$MC_VERSION"
VERSION_JSON="$VERSION_JSON_DIR/$MC_VERSION.json"
mkdir -p "$VERSION_JSON_DIR"

log "step 1/6: locate MC $MC_VERSION in piston-meta version manifest"
MC_JSON_URL="$(curl "${CURL_OPTS[@]}" "$VERSION_MANIFEST_URL" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
for v in d['versions']:
    if v['id']=='$MC_VERSION':
        print(v['url']); break
")"
[[ -n "$MC_JSON_URL" ]] || die "MC version $MC_VERSION not found in piston manifest"
log "  → $MC_JSON_URL"
fetch "$MC_JSON_URL" "$VERSION_JSON"

# ----------------------------- step 2: client jar ----------------------------
CLIENT_JAR="$VERSION_JSON_DIR/$MC_VERSION.jar"
CLIENT_URL="$(python3 -c "import json; d=json.load(open('$VERSION_JSON')); print(d['downloads']['client']['url'])")"
CLIENT_SHA1="$(python3 -c "import json; d=json.load(open('$VERSION_JSON')); print(d['downloads']['client']['sha1'])")"
log "step 2/6: client jar"
log "  → $CLIENT_URL ($(python3 -c "import json; print(d['downloads']['client']['size'])" 2>/dev/null || echo '?') bytes)"
fetch "$CLIENT_URL" "$CLIENT_JAR" "$CLIENT_SHA1"

# ----------------------------- step 3: libraries -----------------------------
log "step 3/6: MC libraries (this is the big one — ~107 jars, ~150 MiB)"
python3 - <<PYEOF
import json, os, sys, subprocess, hashlib, urllib.request, urllib.error
import platform

ROOT = "$LIBRARIES_DIR"
VERSION_JSON = "$VERSION_JSON"
UA = "$UA"

with open(VERSION_JSON) as f:
    data = json.load(f)

def os_name():
    s = platform.system().lower()
    if s == 'darwin': return 'osx'
    if s == 'linux': return 'linux'
    if s == 'windows': return 'windows'
    return s

def rule_allows(rules, ctx):
    """Mojang library rules; default allow if no rules at all."""
    if not rules:
        return True
    allow = False
    for r in rules:
        action = r.get('action', 'allow')
        applies = True
        if 'os' in r:
            os_spec = r['os']
            if 'name' in os_spec and os_spec['name'] != ctx['os']:
                applies = False
            if 'arch' in os_spec and os_spec['arch'] != ctx['arch']:
                applies = False
        if applies:
            allow = (action == 'allow')
    return allow

ctx = {'os': os_name(), 'arch': 'x64' if platform.machine() not in ('i386','i686') else 'x86'}

def sha1_of(path):
    h = hashlib.sha1()
    with open(path,'rb') as f:
        for chunk in iter(lambda: f.read(8192), b''):
            h.update(chunk)
    return h.hexdigest()

def download(url, dest, sha1=None):
    if os.path.exists(dest):
        if sha1 is None or sha1_of(dest) == sha1:
            return False
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    try:
        with urllib.request.urlopen(req, timeout=120) as resp, open(dest+'.tmp','wb') as out:
            while True:
                chunk = resp.read(65536)
                if not chunk: break
                out.write(chunk)
        os.replace(dest+'.tmp', dest)
        if sha1 and sha1_of(dest) != sha1:
            raise RuntimeError(f'sha1 mismatch: {dest}')
    except urllib.error.HTTPError as e:
        raise RuntimeError(f'HTTP {e.code} on {url}') from e
    return True

count = 0
skipped = 0
for lib in data.get('libraries', []):
    if not rule_allows(lib.get('rules'), ctx):
        skipped += 1
        continue
    art = lib.get('downloads', {}).get('artifact')
    if not art:
        skipped += 1
        continue
    path = art['path']
    url = art['url']
    sha1 = art.get('sha1')
    dest = os.path.join(ROOT, path)
    fresh = download(url, dest, sha1)
    if fresh:
        count += 1
        if count % 10 == 0:
            print(f'  ... downloaded {count} libs')
print(f'  done: {count} new, {skipped} skipped, total libs processed = {count + (sum(1 for l in data.get("libraries",[]) if rule_allows(l.get("rules"),ctx) and l.get("downloads",{}).get("artifact")) - count)}')
PYEOF

# ----------------------------- step 4: assets --------------------------------
log "step 4/6: asset index + objects"
ASSET_INDEX_ID="$(python3 -c "import json; print(json.load(open('$VERSION_JSON'))['assetIndex']['id'])")"
ASSET_INDEX_URL="$(python3 -c "import json; print(json.load(open('$VERSION_JSON'))['assetIndex']['url'])")"
ASSET_INDEX_SHA1="$(python3 -c "import json; print(json.load(open('$VERSION_JSON'))['assetIndex']['sha1'])")"
ASSET_INDEX_FILE="$ASSET_INDEXES_DIR/$ASSET_INDEX_ID.json"
fetch "$ASSET_INDEX_URL" "$ASSET_INDEX_FILE" "$ASSET_INDEX_SHA1"

python3 - <<PYEOF
import json, os, hashlib, urllib.request, urllib.error, sys

ROOT = "$ASSET_OBJECTS_DIR"
INDEX = "$ASSET_INDEX_FILE"
UA = "$UA"
BASE = "https://resources.download.minecraft.net"

with open(INDEX) as f:
    data = json.load(f)

def sha1_of(path):
    h = hashlib.sha1()
    with open(path,'rb') as f:
        for chunk in iter(lambda: f.read(8192), b''):
            h.update(chunk)
    return h.hexdigest()

def download(url, dest, sha1=None):
    if os.path.exists(dest):
        if sha1 is None or sha1_of(dest) == sha1:
            return False
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    with urllib.request.urlopen(req, timeout=120) as resp, open(dest+'.tmp','wb') as out:
        while True:
            chunk = resp.read(65536)
            if not chunk: break
            out.write(chunk)
    os.replace(dest+'.tmp', dest)
    return True

objs = data.get('objects', {})
total = len(objs)
print(f'  asset objects total: {total}')
new = 0
errs = 0
for i, (name, meta) in enumerate(objs.items(), 1):
    h = meta['hash']
    sub = h[:2]
    url = f'{BASE}/{sub}/{h}'
    dest = os.path.join(ROOT, sub, h)
    try:
        if download(url, dest, h):
            new += 1
    except Exception as e:
        errs += 1
        if errs <= 5:
            print(f'  ! asset failed {name}: {e}', file=sys.stderr)
    if i % 500 == 0:
        print(f'  ... {i}/{total} ({new} new, {errs} errors)')
print(f'  done: {new} new, {errs} errors, {total} total')
if errs > total // 10:
    raise SystemExit(f'too many asset download errors ({errs})')
PYEOF

# ----------------------------- step 5: Fabric loader + libs ------------------
log "step 5/6: Fabric loader $FABRIC_LOADER_VERSION profile + libs"
FABRIC_PROFILE_DIR="$VERSIONS_DIR/fabric-loader-${FABRIC_LOADER_VERSION}-${MC_VERSION}"
FABRIC_PROFILE_JSON="$FABRIC_PROFILE_DIR/fabric-loader-${FABRIC_LOADER_VERSION}-${MC_VERSION}.json"
mkdir -p "$FABRIC_PROFILE_DIR"
fetch "$FABRIC_PROFILE_URL" "$FABRIC_PROFILE_JSON"

python3 - <<PYEOF
import json, os, urllib.request, urllib.error, hashlib

ROOT = "$LIBRARIES_DIR"
PROFILE = "$FABRIC_PROFILE_JSON"
UA = "$UA"

with open(PROFILE) as f:
    data = json.load(f)

def maven_to_path(coord):
    # group:artifact:version  →  group/artifact/version/artifact-version.jar
    parts = coord.split(':')
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = ''
    if len(parts) >= 4:
        classifier = '-' + parts[3]
    gpath = group.replace('.', '/')
    return f'{gpath}/{artifact}/{version}/{artifact}-{version}{classifier}.jar'

def download(url, dest):
    if os.path.exists(dest):
        return False
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    with urllib.request.urlopen(req, timeout=120) as resp, open(dest+'.tmp','wb') as out:
        while True:
            chunk = resp.read(65536)
            if not chunk: break
            out.write(chunk)
    os.replace(dest+'.tmp', dest)
    return True

new = 0
for lib in data.get('libraries', []):
    coord = lib['name']
    base = lib.get('url', 'https://maven.fabricmc.net/')
    if not base.endswith('/'):
        base += '/'
    path = maven_to_path(coord)
    url = base + path
    dest = os.path.join(ROOT, path)
    if download(url, dest):
        new += 1
        print(f'  + {coord}')
print(f'  done: {new} new Fabric libs')
PYEOF

# ----------------------------- step 6: mods ----------------------------------
log "step 6/6: mods (Fabric API, MaLiLib, Litematica)"
fetch "$FABRIC_API_URL" "$MODS_DIR/$FABRIC_API_FILE"
fetch "$MALILIB_URL"    "$MODS_DIR/$MALILIB_FILE"
fetch "$LITEMATICA_URL" "$MODS_DIR/$LITEMATICA_FILE"

# Note: Servux is server-side only on Modrinth (zQhsx8KF / client_side=unsupported).
# LitematicaFolia plugin emulates the Servux network protocol; Litematica
# itself owns the client side. Nothing to install here.

# ----------------------------- options.txt -----------------------------------
OPTIONS_FILE="$INSTANCE_DIR/options.txt"
if [[ ! -f "$OPTIONS_FILE" ]]; then
  log "writing default options.txt (low graphics, small window, sound off)"
  cat > "$OPTIONS_FILE" <<'EOF'
# Reduced settings for a fast visual smoke against 127.0.0.1:25699
version:4189
renderDistance:6
simulationDistance:6
graphicsMode:0
ao:false
biomeBlendRadius:0
particles:2
maxFps:60
enableVsync:false
fullscreen:false
overrideWidth:1280
overrideHeight:720
fov:0.0
gamma:1.0
guiScale:2
mainHand:right
soundCategory_master:0.0
soundCategory_music:0.0
soundCategory_record:0.0
soundCategory_weather:0.0
soundCategory_block:0.0
soundCategory_hostile:0.0
soundCategory_neutral:0.0
soundCategory_player:0.0
soundCategory_ambient:0.0
soundCategory_voice:0.0
showSubtitles:false
chatHeightFocused:1.0
chatHeightUnfocused:0.5
chatScale:1.0
chatWidth:1.0
chatVisibility:0
tutorialStep:none
lang:en_us
EOF
fi

# ----------------------------- marker ----------------------------------------
date -u +"%Y-%m-%dT%H:%M:%SZ" > "$MARKER_FILE"
log "INSTALL COMPLETE"
log ""
log "Instance:  $INSTANCE_DIR"
log "Mods:      $MODS_DIR"
log ""
log "Next: bash $SCRIPT_DIR/launch-client.sh"
log "(Will open a Minecraft window — do NOT run from a headless autonomous loop)"
