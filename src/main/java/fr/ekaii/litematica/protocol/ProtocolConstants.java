package fr.ekaii.litematica.protocol;

/**
 * Wire-format constants for the Servux-compatible custom payload protocol.
 *
 * <p>Channel names and packet-type IDs are extracted from the public
 * source of <a href="https://github.com/sakura-ryoko/servux">sakura-ryoko/servux</a>
 * at tag {@code 26.1.2-0.10.2}. The Servux project (LGPL-3.0) is
 * referenced for the wire format only — no code was copied. Channel
 * names and integer packet IDs are facts about an interoperable wire
 * format and are not themselves copyrightable. See
 * {@code SERVUX_WIRE_FORMAT.md} in the repo root for the full spec +
 * source-line citations.
 *
 * <h2>Channels</h2>
 * <ul>
 *   <li>{@code servux:litematics} — main Litematica integration channel
 *       (metadata, block-entity / entity NBT requests, Direct Paste
 *       file-transmit). This is also the handshake channel: there is no
 *       separate {@code servux:metadata}.</li>
 *   <li>{@code servux:structures} — vanilla structure bounding-box
 *       stream used by MiniHUD. Scaffolded; not exercised end-to-end.</li>
 *   <li>{@code servux:tweaks} / {@code servux:entity_data} /
 *       {@code servux:hud_metadata} — declared by Servux but out of
 *       scope for this plugin.</li>
 * </ul>
 *
 * <h2>Packet layout convention</h2>
 * Every Servux packet on any channel starts with a {@code VarInt}
 * encoding the packet type, followed by a type-specific payload. The
 * type IDs are namespaced per channel.
 *
 * <h2>Easy Place V3</h2>
 * Servux does NOT implement Easy Place V3 with a custom packet — it
 * decodes the protocol value from the X-fractional component of the
 * hit vector inside a vanilla {@code ServerboundUseItemOnPacket}
 * (server-side {@code BlockItem} mixin). To stay wire-compatible with
 * unmodified Litematica clients we would need an equivalent mixin on
 * Paper; that is deferred. The previously declared
 * {@code litematicafolia:easy_place} channel is unused at runtime —
 * the constants below remain only so the handler / test code can
 * compile, but {@link ServuxBridge} does not register the channel.
 */
public final class ProtocolConstants {

    private ProtocolConstants() {
    }

    // ---------------------------------------------------------- channel names

    /** Primary Litematica channel (Servux-compatible). Also carries the handshake. */
    public static final String CHANNEL_LITEMATICS = "servux:litematics";

    /** Vanilla structure bbox stream (MiniHUD-compatible). */
    public static final String CHANNEL_STRUCTURES = "servux:structures";

    /**
     * @deprecated Servux has no separate metadata channel — handshake
     *             rides on {@link #CHANNEL_LITEMATICS}. Kept as an alias
     *             for source compatibility; do NOT use as a real channel.
     */
    @Deprecated
    public static final String CHANNEL_METADATA   = CHANNEL_LITEMATICS;

    /**
     * LitematicaFolia-vendor Easy Place channel. <strong>Not registered
     * at runtime</strong> — Servux drives Easy Place via the vanilla
     * use-item-on packet (see class javadoc). Kept for compile + test
     * compatibility; future revisions may wire it back if/when a Paper
     * mixin pipeline lands.
     */
    public static final String CHANNEL_EASY_PLACE = "litematicafolia:easy_place";

    // ----------------------------------------------- Litematics packet IDs

    /**
     * Packet type identifiers for {@link #CHANNEL_LITEMATICS}. Values
     * are verbatim from Servux 0.10.x. Direction tag in each entry:
     * S2C = server→client, C2S = client→server.
     *
     * <p>Note: types 10 and 12 ({@code _NBT_STREAM_START}) are
     * <strong>internal markers</strong> in Servux that trigger the
     * {@code PacketSplitter} path. They <em>never</em> appear on the
     * wire — every splitter slice is wrapped as type 11 (S2C) or 13
     * (C2S). They are listed here only as documentation; the wire
     * dispatcher should only branch on 11 / 13.
     */
    public static final class Litematics {
        private Litematics() {}

        /**
         * S2C — server announces availability (handshake response).
         * Payload: {@code NBT(metadata)} where metadata carries
         * exactly four keys ({@code name}, {@code id},
         * {@code version}, {@code servux}). Additional keys are
         * tolerated but Servux clients only look at those four.
         */
        public static final int S2C_METADATA               = 1;

        /**
         * C2S — client opens the handshake. Payload: {@code NBT(client
         * metadata, may be empty)}.
         */
        public static final int C2S_METADATA_REQUEST       = 2;

        /**
         * C2S — client asks for the BlockEntity NBT at a position.
         * Payload: {@code VarInt(transactionId) + BlockPos}.
         * transactionId is currently ignored on both sides ({@code //
         * todo: old code compat} in upstream).
         */
        public static final int C2S_BLOCK_ENTITY_REQUEST   = 3;

        /**
         * C2S — client asks for the entity NBT.
         * Payload: {@code VarInt(transactionId) + VarInt(entityId)}.
         */
        public static final int C2S_ENTITY_REQUEST         = 4;

        /**
         * S2C — single BlockEntity NBT reply.
         * Payload: {@code BlockPos + NBT(be)}.
         */
        public static final int S2C_BLOCK_NBT_REPLY        = 5;

        /**
         * S2C — single Entity NBT reply.
         * Payload: {@code VarInt(entityId) + NBT(entity)}.
         */
        public static final int S2C_ENTITY_NBT_REPLY       = 6;

        /**
         * C2S — bulk request for every BE+Entity in a chunk.
         * Payload: {@code ChunkPos(int x, int z) + NBT(request body)}.
         */
        public static final int C2S_BULK_NBT_REQUEST       = 7;

        /**
         * <strong>Internal Servux marker</strong> — never appears on the
         * wire. Indicates that the application payload {@code
         * (VarInt transactionId + NBT)} should be sent via the
         * {@link fr.ekaii.litematica.protocol.PacketSplitter}.
         */
        public static final int S2C_NBT_STREAM_START       = 10;

        /**
         * S2C — body slice. The first slice of any stream begins with a
         * {@code VarInt(totalLength)} of the application payload,
         * inserted by the splitter; subsequent slices are raw bytes.
         */
        public static final int S2C_NBT_STREAM_DATA        = 11;

        /**
         * <strong>Internal Servux marker</strong> — never appears on the
         * wire. C2S equivalent of {@link #S2C_NBT_STREAM_START}.
         */
        public static final int C2S_NBT_STREAM_START       = 12;

        /**
         * C2S — body slice (client → server). Same format as
         * {@link #S2C_NBT_STREAM_DATA} but in the opposite direction.
         */
        public static final int C2S_NBT_STREAM_DATA        = 13;
    }

    // ----------------------------------------------- Structures packet IDs

    /** Packet type identifiers for {@link #CHANNEL_STRUCTURES}. */
    public static final class Structures {
        private Structures() {}

        /** S2C — capability metadata. Payload: {@code NBT}. */
        public static final int S2C_METADATA               = 1;
        /** S2C — splitter slice of structure box data. */
        public static final int S2C_STRUCTURE_DATA         = 2;
        /** C2S — client registers for streaming. Payload: {@code NBT(may be empty)}. */
        public static final int C2S_REGISTER               = 3;
        /** C2S — client unregisters. Payload: {@code NBT(may be empty)}. */
        public static final int C2S_UNREGISTER             = 4;
        /** Internal Servux marker — never appears on the wire (splitter start). */
        public static final int S2C_STRUCTURE_DATA_START   = 5;
        /** S2C — spawn / world border metadata. */
        public static final int S2C_SPAWN_METADATA         = 10;
        /** C2S — request spawn / weather metadata. */
        public static final int C2S_REQUEST_SPAWN_METADATA = 11;
        /** S2C — current weather snapshot. */
        public static final int S2C_WEATHER_DATA           = 12;
    }

    // ----------------------------------------------- Easy Place packet IDs

    /**
     * LitematicaFolia-vendor Easy Place packet IDs. Not on the wire
     * today — see class javadoc.
     */
    public static final class EasyPlace {
        private EasyPlace() {}

        /** C2S — client requests placement of a single block. */
        public static final int C2S_PLACE_REQUEST = 1;

        /** S2C — server acknowledges (or rejects) a place request. */
        public static final int S2C_PLACE_ACK     = 2;
    }

    // ---------------------------------------------------------- metadata

    /** Provider name advertised in the handshake metadata. Matches Servux exactly. */
    public static final String METADATA_PROVIDER_NAME = "litematic_data";

    /**
     * Servux's own protocol version for the litematics channel. From
     * {@code ServuxLitematicaPacket.PROTOCOL_VERSION = 1}.
     */
    public static final int LITEMATICS_PROTOCOL_VERSION = 1;

    /**
     * Servux's own protocol version for the structures channel. From
     * {@code ServuxStructuresPacket.PROTOCOL_VERSION = 2}.
     */
    public static final int STRUCTURES_PROTOCOL_VERSION = 2;

    /** Our server identification string emitted as the {@code servux} key. */
    public static final String SERVER_NAME       = "LitematicaFolia 0.1.0";
}
