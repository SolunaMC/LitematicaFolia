# Servux wire format reference

Reverse-engineered from the upstream Servux source at tag
`26.1.2-0.10.2` (commit `a053ada`, branch `26.1`). Used to align our
plugin's protocol code byte-for-byte. **No code from Servux has been
imported** — only the wire facts, which are not themselves copyrightable.

Servux is LGPL-3.0; this file documents an interoperable protocol, the
same way a network RFC documents a protocol.

Cross-references in `[file:line]` notation point at the upstream
Servux source so anyone verifying this doc can walk back to the proof.

## Channels

| Channel identifier        | Purpose                                | Direction | Servux source                                              |
| ------------------------- | -------------------------------------- | --------- | ---------------------------------------------------------- |
| `servux:litematics`       | Block / entity NBT + paste streaming   | C2S + S2C | `network/packet/ServuxLitematicaHandler.java:43`           |
| `servux:structures`       | Vanilla structure boxes (MiniHUD)      | C2S + S2C | `network/packet/ServuxStructuresHandler.java:37`           |
| `servux:entity_data`      | Out of scope (used by EntitiesPacket)  | —         | `network/packet/ServuxEntitiesHandler.java:37`             |
| `servux:hud_metadata`     | HUD + spawn metadata (MiniHUD)         | —         | (not handled here)                                         |
| `servux:tweaks`           | Tweakeroo client tweaks                | —         | (not handled here)                                         |

NOTE: The Litematica channel is `servux:litematics` (plural). The
previous draft of our memory referred to `servux:metadata` — that
channel does not exist in Servux. There is **no separate metadata
channel**: handshake/metadata travels on `servux:litematics` itself.

## Primitive encodings

Every Servux packet rides Mojang's `FriendlyByteBuf`. The relevant
encodings:

| Primitive | Encoding                                                                                                                       |
| --------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `VarInt`  | 1–5 bytes, 7 bits/byte, MSB = continuation flag, zigzag NOT applied                                                            |
| `Int`     | 4 bytes, big-endian, signed                                                                                                    |
| `Long`    | 8 bytes, big-endian, signed                                                                                                    |
| `Float`   | 4 bytes, IEEE-754 big-endian (`Float.intBitsToFloat(readInt())`)                                                               |
| `Boolean` | 1 byte, 0 or 1                                                                                                                 |
| `String`  | `VarInt(byteLength)` + UTF-8 bytes — NOT `DataOutput.writeUTF` (which is unsigned-short + modified-UTF-8)                      |
| `BlockPos`| Packed signed long: `((x & 0x3FFFFFF) << 38) \| ((z & 0x3FFFFFF) << 12) \| (y & 0xFFF)` (26+12+26 bits, `y` is the low 12)     |
| `ChunkPos`| Packed signed long, big-endian: `(x & 0xFFFFFFFFL) \| ((z & 0xFFFFFFFFL) << 32)`. Mojang's `ChunkPos.pack` form on MC 26.1.x. NOT two consecutive ints — that was a v0.1.0 draft error. |
| `NBT`     | Mojang network NBT (1.20.2+): `[id byte][payload]` with **no root name**. `[id=0]` (TAG_END) means "null/no NBT".              |

## `servux:litematics` packets

All packets begin with `VarInt(packetType)`. Types from
`network/packet/ServuxLitematicaPacket.java:475-499`.

Direction: S2C = server→client, C2S = client→server.

| ID   | Name                                  | Direction | Payload after the type VarInt                                                |
| ---- | ------------------------------------- | --------- | ---------------------------------------------------------------------------- |
| 1    | `PACKET_S2C_METADATA`                 | S2C       | `NBT(metadata)`                                                              |
| 2    | `PACKET_C2S_METADATA_REQUEST`         | C2S       | `NBT(client metadata, may be empty)`                                         |
| 3    | `PACKET_C2S_BLOCK_ENTITY_REQUEST`     | C2S       | `VarInt(transactionId) + BlockPos(packed long)`                              |
| 4    | `PACKET_C2S_ENTITY_REQUEST`           | C2S       | `VarInt(transactionId) + VarInt(entityId)`                                   |
| 5    | `PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE`| S2C       | `BlockPos(packed long) + NBT(be tag)`                                        |
| 6    | `PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE`| S2C      | `VarInt(entityId) + NBT(entity tag)`                                         |
| 7    | `PACKET_C2S_BULK_ENTITY_NBT_REQUEST`  | C2S       | `ChunkPos(packed long, low=x high=z) + NBT(request payload)`                 |
| 10   | `PACKET_S2C_NBT_RESPONSE_START`       | **internal** | Never appears on the wire — see splitter rules below                       |
| 11   | `PACKET_S2C_NBT_RESPONSE_DATA`        | S2C       | Raw splitter slice (see "Splitter rules"). First slice begins with `VarInt(totalLen)` prefix injected by `PacketSplitter` |
| 12   | `PACKET_C2S_NBT_RESPONSE_START`       | **internal** | Never appears on the wire — see splitter rules below                       |
| 13   | `PACKET_C2S_NBT_RESPONSE_DATA`        | C2S       | Raw splitter slice. First slice begins with `VarInt(totalLen)` prefix       |

`network/packet/ServuxLitematicaPacket.java:219-318` shows
the `toPacket` encoder; `:321-444` shows the decoder.

### Metadata compound (S2C type 1)

`dataproviders/LitematicsDataProvider.java:69-72` builds the metadata
compound with exactly four keys:

| Key      | Type     | Value                                                              |
| -------- | -------- | ------------------------------------------------------------------ |
| `name`   | `String` | Provider name — `"litematic_data"`                                 |
| `id`     | `String` | Channel identifier — `"servux:litematics"`                         |
| `version`| `Int`    | Provider protocol version — `1` (= `ServuxLitematicaPacket.PROTOCOL_VERSION`) |
| `servux` | `String` | Server software identifier — Servux's own `Reference.MOD_STRING`   |

Servux clients ignore unknown keys, so additional keys are
backwards-compatible; but the minimum / standard set is exactly those
four.

### BlockEntity request (C2S type 3)

`network/packet/ServuxLitematicaPacket.java:225-237` (encode) and
`:334-346` (decode). The transactionId field is currently unused on
both sides (`// todo: old code compat` comment in the decoder); always
sent as the cached value `-1` (`VarInt`-encoded; -1 zig-zag-equivalent
is the 5-byte form `0xFF 0xFF 0xFF 0xFF 0x0F`).

### Entity request (C2S type 4)

`network/packet/ServuxLitematicaPacket.java:238-250` (encode) and
`:347-359` (decode). transactionId again ignored.

### Bulk request (C2S type 7)

`network/packet/ServuxLitematicaPacket.java:275-286` (encode) and
`:382-392` (decode). The trailing NBT compound describes what to
include (which entities/BEs) — exact schema TBD; Servux treats it as
the request body the handler receives.

## `servux:structures` packets

| ID | Name                                | Direction    | Payload                          |
| -- | ----------------------------------- | ------------ | -------------------------------- |
| 1  | `PACKET_S2C_METADATA`               | S2C          | `NBT(metadata)`                  |
| 2  | `PACKET_S2C_STRUCTURE_DATA`         | S2C          | Splitter slice (raw bytes)       |
| 3  | `PACKET_C2S_STRUCTURES_REGISTER`    | C2S          | `NBT(possibly empty)`            |
| 4  | `PACKET_C2S_STRUCTURES_UNREGISTER`  | C2S          | `NBT(possibly empty)`            |
| 5  | `PACKET_S2C_STRUCTURE_DATA_START`   | **internal** | Triggers splitter path; never appears on the wire |
| 10 | `PACKET_S2C_SPAWN_METADATA`         | S2C          | `NBT(metadata)`                  |
| 11 | `PACKET_C2S_REQUEST_SPAWN_METADATA` | C2S          | `NBT(may be empty)`              |
| 12 | `PACKET_S2C_WEATHER_DATA`           | S2C          | `NBT(metadata)`                  |

Source: `network/packet/ServuxStructuresPacket.java:204-213`,
`network/packet/ServuxStructuresHandler.java:120-137`.

`PROTOCOL_VERSION = 2` for structures (vs 1 for litematics) —
the metadata compound's `version` field reflects that.

## Splitter rules

Splitter behaviour is centralised in `network/PacketSplitter.java`.

### Sender side (`PacketSplitter.send`, `:36-61`)

Input: a `FriendlyByteBuf` holding the application payload.
Algorithm:

```
let len = payload.writerIndex()
for offset in 0, payloadLimit, 2*payloadLimit, …:
    let thisLen = min(len - offset, payloadLimit)
    let slice  = empty buffer
    if offset == 0:
        slice.writeVarInt(len)              // total length prefix
    slice.writeBytes(payload, thisLen)      // append raw bytes
    handler.encodeWithSplitter(player, slice, networkHandler)
```

The `encodeWithSplitter` callback wraps each slice into a
`PACKET_S2C_NBT_RESPONSE_DATA` (type 11) or
`PACKET_C2S_NBT_RESPONSE_DATA` (type 13) packet via
`network/packet/ServuxLitematicaHandler.java:179-184` (S2C) — see also
`encodeWithSplitter` on the structures handler at `:120-125`.

### Receiver side (`PacketSplitter.receive`, `:63-78`)

A `ReadingSession` keyed by a pre-shared random 64-bit `key`
accumulates slices into a single buffer. The first slice declares
`expectedSize` via leading `VarInt`. When `received.writerIndex() >=
expectedSize` the session is closed and the accumulated buffer is
returned for further parsing.

### What the receiver does once a stream is complete

For `servux:litematics`, `network/packet/ServuxLitematicaHandler.java:107-125`
reads:
1. `VarInt(transactionId)` (= the value the START packet would have
   carried, see `:197`)
2. `NBT(payload compound)`

…then dispatches based on the compound's `"Task"` field, which selects
between bulk paste and file-transmit (`receiveFileTransmit` at
`schematic/LitematicaSchematic.java:1167-1233`).

### Direct paste file-transmit sub-protocol

`schematic/LitematicaSchematic.java:1102-1165` shows how a client
streams a `.litematic` file to the server. The contract is **not**
"start packet then data packets" at the Servux-packet-type level — it
is "multiple SAME `ResponseC2SStart` Servux-internal sends, each
of which becomes a stream of type-13 splitter slices, with a different
`Task` field in the inner NBT compound on each round":

- First send: NBT contains
  `Task="Litematic-TransmitStart"`, `FileName`, `FileType`,
  `SliceKey`, optional `PlacementData`.
- N data sends: NBT contains
  `Task="Litematic-TransmitData"`, `SliceKey`, `Slice` (int index),
  `Size` (int), `Data` (byte array).
- Final send: NBT contains
  `Task="Litematic-TransmitEnd"`, `SliceKey`, `TotalSize`,
  `TotalSlices`.
- On error: `Task="Litematic-TransmitCancel"`.

Each of those "sends" is itself a stream of type-13 splitter
slices, because at the splitter layer there is no concept of message
boundaries — the splitter just chunks a single buffer in/out. The
buffer payload of each "send" is `VarInt(transactionId=type) +
NBT(compound)`.

### Implication for our handler

We previously implemented `C2S_NBT_STREAM_START`/`C2S_NBT_STREAM_DATA`
as if they were two distinct packet types arriving in sequence —
`START` carrying header NBT, `DATA` carrying raw slice bytes that
accumulate to form the schematic. **That's wrong**. The packet-12
"START" type never travels on the wire. The on-wire stream is purely
packet-13 slices. Re-assembly is keyed by a pre-shared session
`SliceKey` (a random long inside the NBT compound). The result of
re-assembling **one** stream is a single `VarInt + NBT(compound)`
chunk, whose `Task` field decides whether more streams must follow to
complete a Direct Paste operation.

## Easy Place V3

### Critical fact

**Easy Place V3 is NOT a custom Servux packet.** Servux implements it
purely server-side via a `BlockItem` mixin
(`mixin/item/MixinBlockItem_EasyPlace.java`) that intercepts
`getPlacementState` and re-derives the desired state from the **hit
vector X coordinate** of the vanilla
`ServerboundUseItemOnPacket`. See `util/PlacementHandler.java:67-229`
for `applyPlacementProtocolV3`. The encoding is:

```
int protocolValue = (int)(hitVec.x - pos.x) - 2;
if (protocolValue < 0) return defaultState;   // no protocol in this click
// bits 0..3       -> facing override (0..5 = Direction.from3DDataValue,
//                                     6 = opposite of default)
// bit 4           -> unused (always shifted off)
// bits 5..N       -> alphabetical WHITELISTED_PROPERTIES, each occupying
//                    ceil(log2(possibleValueCount)) bits, value = list index
```

The client therefore "smuggles" the protocol payload as a fractional
component of the hit-vector X. There is no `litematicafolia:easy_place`
channel in Servux.

### Implication for our handler

Our previous draft defined a custom
`litematicafolia:easy_place` channel with packet types
`C2S_PLACE_REQUEST` and `S2C_PLACE_ACK`. **No Servux client will ever
send a packet on that channel** — Litematica + Servux clients drive
Easy Place by setting the hit-vector X they include in their normal
`ServerboundUseItemOnPacket`. To be wire-compatible with a vanilla
Servux client we would need a mixin equivalent on Paper, which is out
of scope for v0.1.x (we cannot mixin into Paper's network/item code
without an NMS bridge of the same shape).

For v0.1.x we therefore **drop the `litematicafolia:easy_place`
channel from the registered set**, keep the data structures and
handler code dead (so we can re-introduce them later if we ever ship
an NMS-backed mixin), and document the limitation in `CHANGELOG.md`.

## Protocol-version field summary

| Channel              | `PROTOCOL_VERSION` in source                                                                                  |
| -------------------- | ------------------------------------------------------------------------------------------------------------- |
| `servux:litematics`  | 1 (`network/packet/ServuxLitematicaPacket.java:27`)                                                           |
| `servux:structures`  | 2 (`network/packet/ServuxStructuresPacket.java:20`)                                                           |
| `servux:entity_data` | 1 (`network/packet/ServuxEntitiesPacket.java:25`)                                                             |

These values appear as the `version` field of the corresponding
channel's metadata compound and are the only canonical client/server
agreement on protocol revision.

## Open questions

1. **`PROTOCOL_VERSION` semantics across minor releases.** Servux
   re-uses the same `PROTOCOL_VERSION` constant across at least the
   `26.1-0.10.0` → `26.1.2-0.10.2` window. Whether bumping the mod
   version requires bumping the protocol version is policy, not
   wire-format — left to a future tick.
2. **`servux:hud_metadata` channel name.** Visible in the
   `ServuxHudHandler` source but not used by any of our targeted
   packets. Not validated.
3. **Bulk request NBT body schema (packet 7).** Source treats it as a
   freeform compound interpreted by the receiver. We stub the
   response.
4. **Item-NBT carry channel.** Servux does not transport item NBT via
   a custom channel either — Easy Place uses the vanilla
   `ServerboundUseItemOnPacket`'s held item. Our previous "item NBT
   blob in EasyPlaceRequest" design was unconstrained by upstream.
