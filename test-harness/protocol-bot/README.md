# protocol-bot

Headless Minecraft 26.1.2 client used by `../servux-smoke.sh` to validate
the LitematicaFolia Servux bridge end-to-end without a real Fabric client.

## What it does

1. Opens a raw `Socket` to the smoke server (default `127.0.0.1:25699`).
2. Drives the MC protocol from Handshake → Login → Configuration → Play:
   - **Handshake**: `intent=2 (login)` with protocol version `775` (MC 26.1.2).
   - **Login**: sends a Login Start with the offline-mode UUID. Bails out on
     EncryptionRequest (server must be `online-mode=false`). Refuses
     compression (server must set `network-compression-threshold=-1`).
   - **Configuration**: replies to KeepAlive / Ping, mirrors Brand,
     answers `SelectKnownPacks` with an empty list (forces the server to
     send the embedded vanilla registry pack, which we then discard).
   - **Play**: waits for `Login (Play)`, then sends our brand and
     immediately fires `C2S_METADATA_REQUEST` on `servux:litematics`.
3. Reads the gzipped `.litematic` fixture, wraps the root NBT compound as
   `Schematic` inside a payload (with origin coordinates + paste flags),
   then ships it via `C2S_NBT_STREAM_START` + N×`C2S_NBT_STREAM_DATA`.
4. Pumps inbound packets for `--hold-seconds` (default 10s), acking
   teleports and replying to KeepAlive so we don't get kicked. Parses
   `S2C_METADATA` and logs the announced ServerVersion / ProtocolVersion.

No Netty, no Mojang libraries — only `java.net.Socket` +
`DataInputStream` / `DataOutputStream` + the project's own `LitematicNbt`.

## Build

```
javac --release 25 \
    -d build \
    -cp ../../build/classes/java/main \
    src/ProtocolBot.java
```

The bot depends on `fr.ekaii.litematica.core.LitematicNbt` from the main
plugin, so run `./gradlew compileJava` once before compiling.

## Run

```
java -cp build:../../build/classes/java/main ProtocolBot \
    --target 127.0.0.1:25699 \
    --litematic ../../schematics-fixtures/stone-cube-4.litematic \
    --paste-origin 100,64,100 \
    --username ProtoBot \
    --hold-seconds 15
```

| Arg | Default | Description |
| --- | --- | --- |
| `--target host:port` | `127.0.0.1:25699` | Server endpoint |
| `--litematic path` | (required) | `.litematic` file to ship |
| `--paste-origin x,y,z` | `100,64,100` | World coords |
| `--username name` | `ProtoBot` | Offline-mode handle |
| `--hold-seconds n` | `10` | How long to pump packets after streaming |
| `--quiet` | off | Suppress verbose per-packet logging |

## Packet IDs

Reverse-engineered from the Luminol 26.1.2 jar via `javap`:

```
Login    C2S: 0=Hello 2=PluginAnswer 3=LoginAck 4=CookieResp
Login    S2C: 0=Disconnect 1=EncryptionRequest 2=LoginSuccess
              3=SetCompression 4=PluginRequest 5=CookieRequest
Config   C2S: 0=ClientInfo 1=CookieResp 2=CustomPayload 3=FinishConfig
              4=KeepAlive 5=Pong 6=ResourcePack 7=SelectKnownPacks
Config   S2C: 0=CookieReq 1=CustomPayload 2=Disconnect 3=FinishConfig
              4=KeepAlive 5=Ping 6=ResetChat 7=RegistryData
              10=StoreCookie 11=Transfer 12=UpdateEnabledFeatures
              13=UpdateTags 14=SelectKnownPacks
Play     C2S: 0=AcceptTeleport 17=ConfigAck 22=CustomPayload 28=KeepAlive
Play     S2C: 0=Bundle 24=CustomPayload 32=Disconnect 44=KeepAlive
              45=LevelChunkWithLight 49=Login(Play) 61=Ping
              72=PlayerPosition 82=Respawn 118=StartConfiguration
```

Build artifacts go to `build/` which is gitignored.
