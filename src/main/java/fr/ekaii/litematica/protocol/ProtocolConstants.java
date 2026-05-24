package fr.ekaii.litematica.protocol;

/**
 * Wire-format constants for the Servux-compatible custom payload protocol.
 *
 * <p>Channel names and packet-type IDs are reverse-engineered from the
 * public source of
 * <a href="https://github.com/sakura-ryoko/servux">sakura-ryoko/servux</a>
 * (LGPL-3.0). The Servux project is referenced for the wire format only —
 * no code was copied. Channel names and integer packet IDs are facts about
 * an interoperable wire format and are not themselves copyrightable.
 *
 * <h2>Channels</h2>
 * <ul>
 *   <li>{@code servux:litematics} — main Litematica integration channel
 *       (metadata, block-entity / entity NBT requests, paste).</li>
 *   <li>{@code servux:structures} — vanilla structure bounding-box stream
 *       used by MiniHUD. Bonus, partially scaffolded.</li>
 *   <li>{@code servux:tweaks} / {@code servux:entity_data} /
 *       {@code servux:hud_metadata} — declared by Servux but out of scope
 *       for this plugin.</li>
 * </ul>
 *
 * <h2>Litematics packet layout</h2>
 * Every packet on {@code servux:litematics} starts with a {@code VarInt}
 * encoding the packet type (see {@link Litematics}), followed by a
 * type-specific payload. Layouts are documented on each constant.
 *
 * <h2>Structures packet layout</h2>
 * Same convention — VarInt type id then payload.
 *
 * <h2>Easy Place V3</h2>
 * The base Servux project does not currently ship Easy Place V3 — that
 * functionality lives in the upstream Litematica client mod. To keep this
 * plugin a self-contained server-side bridge, we extend the protocol with
 * a vendor namespace ({@code litematicafolia:easy_place}) and a vendor
 * packet block ({@link EasyPlace}). The handshake announces the
 * {@code "easy_place_v3"} capability so clients can detect the
 * extension.
 */
public final class ProtocolConstants {

    private ProtocolConstants() {
    }

    // ---------------------------------------------------------- channel names

    /** Primary Litematica channel (Servux-compatible). */
    public static final String CHANNEL_LITEMATICS = "servux:litematics";

    /** Vanilla structure bbox stream (MiniHUD-compatible). */
    public static final String CHANNEL_STRUCTURES = "servux:structures";

    /** Metadata / handshake channel (alias of litematics in Servux). */
    public static final String CHANNEL_METADATA   = "servux:litematics";

    /**
     * Easy Place V2/V3 vendor channel. Distinct from {@code servux:litematics}
     * so that we can iterate on the placement protocol without breaking the
     * core channel. Servux uses a different wire path for Easy Place; we
     * adopt our own to avoid lock-in.
     */
    public static final String CHANNEL_EASY_PLACE = "litematicafolia:easy_place";

    // ----------------------------------------------- Litematics packet IDs

    /**
     * Packet type identifiers for {@link #CHANNEL_LITEMATICS}. Values
     * are verbatim from Servux 0.10.x to guarantee wire interop with the
     * vanilla Litematica client / Servux companion mod.
     *
     * <p>Direction tag in each entry: S2C = server→client, C2S = client→server.
     */
    public static final class Litematics {
        private Litematics() {}

        // ----- handshake -----

        /**
         * S2C — server announces availability + capabilities.
         * Payload: VarInt(typeId) + NBT(metadata compound).
         * The metadata compound carries at minimum:
         *   String  "name"      provider name (e.g. "litematic_data")
         *   String  "id"        channel identifier
         *   Int     "version"   protocol version
         *   String  "servux"    server software identifier
         */
        public static final int S2C_METADATA               = 1;

        /**
         * C2S — client requests the metadata compound. Sent after the
         * client confirms it understands the channel.
         * Payload: VarInt(typeId) + NBT(empty compound — may carry client
         * capabilities in future revisions).
         */
        public static final int C2S_METADATA_REQUEST       = 2;

        // ----- block entity / entity NBT (used for chest contents, signs, …)

        /**
         * C2S — client asks for the BlockEntity NBT at {@code pos}.
         * Payload: VarInt(typeId) + VarInt(transactionId, ignored —
         * legacy compat) + BlockPos.
         */
        public static final int C2S_BLOCK_ENTITY_REQUEST   = 3;

        /**
         * C2S — client asks for the entity NBT for the entity with
         * {@code entityId}.
         * Payload: VarInt(typeId) + VarInt(transactionId, ignored) +
         * VarInt(entityId).
         */
        public static final int C2S_ENTITY_REQUEST         = 4;

        /**
         * S2C — single BlockEntity NBT reply.
         * Payload: VarInt(typeId) + BlockPos + NBT(compound).
         */
        public static final int S2C_BLOCK_NBT_REPLY        = 5;

        /**
         * S2C — single Entity NBT reply.
         * Payload: VarInt(typeId) + VarInt(entityId) + NBT(compound).
         */
        public static final int S2C_ENTITY_NBT_REPLY       = 6;

        /**
         * C2S — bulk dump of every BE+Entity in a chunk.
         * Payload: VarInt(typeId) + ChunkPos(int x, int z) + NBT.
         */
        public static final int C2S_BULK_NBT_REQUEST       = 7;

        // ----- splitter (for oversized NBT blobs over the 32 KiB C2S cap)

        /**
         * S2C — start frame of a packet-splitter sequence. Carries the
         * total payload size + the small header NBT compound for the
         * recipient to allocate / dispatch.
         * Payload: VarInt(typeId) + NBT(header compound).
         */
        public static final int S2C_NBT_STREAM_START       = 10;

        /**
         * S2C — body slice. All subsequent slices until the buffer is
         * drained. The first slice writes a VarInt of the total length;
         * subsequent slices append raw bytes.
         * Payload: VarInt(typeId) + raw bytes.
         */
        public static final int S2C_NBT_STREAM_DATA        = 11;

        /**
         * C2S — start frame of a client → server splitter sequence
         * (e.g. /paste with a large schematic).
         * Payload: VarInt(typeId) + NBT(header compound).
         */
        public static final int C2S_NBT_STREAM_START       = 12;

        /**
         * C2S — body slice (client → server). Same format as
         * {@link #S2C_NBT_STREAM_DATA} but in the opposite direction.
         */
        public static final int C2S_NBT_STREAM_DATA        = 13;
    }

    // ----------------------------------------------- Structures packet IDs

    /**
     * Packet type identifiers for {@link #CHANNEL_STRUCTURES}. Verbatim
     * from Servux 0.10.x. Out of primary scope — only handshake +
     * registration are stubbed.
     */
    public static final class Structures {
        private Structures() {}

        /** S2C — capability metadata. Payload: VarInt(typeId) + NBT. */
        public static final int S2C_METADATA               = 1;
        /** S2C — chunk of structure box data. Payload: VarInt(typeId) + raw bytes (splitter). */
        public static final int S2C_STRUCTURE_DATA         = 2;
        /** C2S — client registers for streaming. Payload: VarInt(typeId) + NBT. */
        public static final int C2S_REGISTER               = 3;
        /** C2S — client unregisters. Payload: VarInt(typeId) + NBT (empty). */
        public static final int C2S_UNREGISTER             = 4;
        /** S2C — splitter start. Payload: VarInt(typeId) + NBT(header). */
        public static final int S2C_STRUCTURE_DATA_START   = 5;
        /** S2C — server spawn / world border / etc. metadata. */
        public static final int S2C_SPAWN_METADATA         = 10;
        /** C2S — request spawn / weather metadata. */
        public static final int C2S_REQUEST_SPAWN_METADATA = 11;
        /** S2C — current weather snapshot. */
        public static final int S2C_WEATHER_DATA           = 12;
    }

    // ----------------------------------------------- Easy Place packet IDs

    /**
     * Packet type identifiers for {@link #CHANNEL_EASY_PLACE}. This is a
     * LitematicaFolia-vendor extension — Servux itself does not ship this
     * channel today. IDs are new (no upstream constraint).
     */
    public static final class EasyPlace {
        private EasyPlace() {}

        /**
         * C2S — client requests placement of a single block.
         * Payload: VarInt(typeId)
         *        + BlockPos (long packed)
         *        + String blockState (vanilla "minecraft:foo[prop=val]" form)
         *        + Boolean hasItemNbt + (optional) NBT(item compound)
         *        + Float hitX + Float hitY + Float hitZ
         *        + VarInt facingOrdinal (0=down,1=up,2=north,3=south,4=west,5=east)
         *        + VarInt requestId.
         */
        public static final int C2S_PLACE_REQUEST = 1;

        /**
         * S2C — server acknowledges (or rejects) a place request.
         * Payload: VarInt(typeId) + VarInt(requestId) + Boolean(success)
         *        + String(reason)  // empty when success
         */
        public static final int S2C_PLACE_ACK     = 2;
    }

    // ---------------------------------------------------------- metadata keys

    /** Our server identification string for the handshake. */
    public static final String SERVER_NAME       = "LitematicaFolia 0.1.0";

    /** Capabilities we advertise. Stored as TAG_LIST of TAG_STRING. */
    public static final String[] CAPABILITIES = {
            "easy_place_v3",
            "direct_paste",
            "structure_bbox"
    };

    /** Custom capability protocol version. Independent of Servux's. */
    public static final int CAPABILITY_PROTOCOL_VERSION = 3;
}
