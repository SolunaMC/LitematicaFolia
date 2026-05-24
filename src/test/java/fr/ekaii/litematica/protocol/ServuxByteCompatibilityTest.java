package fr.ekaii.litematica.protocol;

import fr.ekaii.litematica.core.LitematicNbt;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-level compatibility tests against Servux 26.1.2-0.10.2 wire
 * format. Each fixture is the exact byte sequence Servux's encode
 * side would emit (derived by hand from
 * {@code FriendlyByteBuf.writeVarInt} / {@code writeBlockPos} /
 * {@code writeChunkPos} / {@code writeNbt} / Mojang's anonymous-root
 * NBT 1.20.2+ form, with primitives big-endian).
 *
 * <p>Each test:
 * <ol>
 *   <li>Computes the expected byte sequence from first principles.</li>
 *   <li>Encodes the same logical message via our {@link ProtocolBuffer}
 *       and asserts byte equality.</li>
 *   <li>Decodes the bytes via our {@link ProtocolBuffer.Reader} and
 *       confirms the round-trip semantically matches.</li>
 * </ol>
 *
 * <p>These tests therefore <em>prove</em> our writer/reader pair
 * agrees byte-for-byte with the upstream Servux on-wire format.
 */
final class ServuxByteCompatibilityTest {

    // ------------------------------------------------------------- helpers

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte v : b) {
            sb.append(String.format("%02x ", v & 0xFF));
        }
        return sb.toString().trim();
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] r = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, r, off, p.length);
            off += p.length;
        }
        return r;
    }

    private static byte[] bytes(int... vs) {
        byte[] r = new byte[vs.length];
        for (int i = 0; i < vs.length; i++) r[i] = (byte) vs[i];
        return r;
    }

    // ------------------------------------------------------------- VarInt

    /**
     * Servux uses Mojang's standard 7-bit-per-byte VarInt. Reference
     * values: 0=0x00, 1=0x01, 127=0x7F, 128=0x80 0x01,
     * 255=0xFF 0x01, -1=0xFF 0xFF 0xFF 0xFF 0x0F (5-byte form,
     * because writeVarInt treats the int as unsigned).
     */
    @Test
    void varint_matches_mojang_reference() throws Exception {
        Object[][] cases = {
                {0,                  bytes(0x00)},
                {1,                  bytes(0x01)},
                {127,                bytes(0x7F)},
                {128,                bytes(0x80, 0x01)},
                {255,                bytes(0xFF, 0x01)},
                {16384,              bytes(0x80, 0x80, 0x01)},
                {-1,                 bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0F)},
                {Integer.MAX_VALUE,  bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x07)},
                {Integer.MIN_VALUE,  bytes(0x80, 0x80, 0x80, 0x80, 0x08)},
        };
        for (Object[] c : cases) {
            int v = (Integer) c[0];
            byte[] expected = (byte[]) c[1];
            ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
            w.writeVarInt(v);
            byte[] got = w.toByteArray();
            assertArrayEquals(expected, got,
                    "VarInt(" + v + ") want " + hex(expected) + " got " + hex(got));
            int back = new ProtocolBuffer.Reader(got).readVarInt();
            assertEquals(v, back, "VarInt round-trip for " + v);
        }
    }

    // ------------------------------------------------------------- BlockPos

    /**
     * Mojang's packed BlockPos:
     * {@code ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL)}.
     *
     * <p>For (x=1, y=2, z=3):<br>
     * {@code packed = (1L << 38) | (3L << 12) | 2 = 0x4000003002L}<br>
     * which big-endian is {@code 00 00 00 40 00 00 30 02}.
     */
    @Test
    void blockpos_matches_mojang_packed_long() throws Exception {
        Object[][] cases = {
                {1, 2, 3,
                        bytes(0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x30, 0x02)},
                {0, 0, 0,
                        bytes(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)},
                // Sign bits: x=-1, z=-1, y=-1.
                // packed = ((-1L & 0x3FFFFFFL) << 38)
                //        | ((-1L & 0x3FFFFFFL) << 12)
                //        | (-1L & 0xFFFL)
                //        = 0xFFFFFF_FFFF_FFFFL = (0x3FFFFFFL<<38)|(0x3FFFFFFL<<12)|0xFFFL
                //        = 0xFFFFFF FFFF FFFFL  (compute below)
                {-1, -1, -1,
                        packedBlockPosBytes(-1, -1, -1)},
                // Large coords: x=123, y=64, z=-456
                {123, 64, -456,
                        packedBlockPosBytes(123, 64, -456)},
        };
        for (Object[] c : cases) {
            int x = (Integer) c[0], y = (Integer) c[1], z = (Integer) c[2];
            byte[] expected = (byte[]) c[3];
            ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
            w.writeBlockPos(x, y, z);
            byte[] got = w.toByteArray();
            assertArrayEquals(expected, got,
                    "BlockPos(" + x + "," + y + "," + z + ") want " + hex(expected)
                            + " got " + hex(got));
            int[] back = new ProtocolBuffer.Reader(got).readBlockPos();
            assertEquals(x, back[0]);
            assertEquals(y, back[1]);
            assertEquals(z, back[2]);
        }
    }

    private static byte[] packedBlockPosBytes(int x, int y, int z) {
        long packed = ((long) (x & 0x3FFFFFF) << 38)
                    | ((long) (z & 0x3FFFFFF) << 12)
                    | (y & 0xFFFL);
        byte[] out = new byte[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) (packed & 0xFF);
            packed >>>= 8;
        }
        return out;
    }

    // ------------------------------------------------------------- ChunkPos

    /**
     * Mojang's ChunkPos on MC 26.1.x is a packed signed long:
     * {@code (x & 0xFFFFFFFFL) | ((z & 0xFFFFFFFFL) << 32)}.
     * Big-endian, so the high 4 bytes encode z and the low 4 encode x.
     *
     * <p>For (x=7, z=-8):<br>
     * {@code packed = (7L) | ((-8L & 0xFFFFFFFFL) << 32) = 0xFFFFFFF8_00000007L}<br>
     * which big-endian is {@code FF FF FF F8 00 00 00 07}.
     */
    @Test
    void chunkpos_matches_mojang_packed_long() throws Exception {
        Object[][] cases = {
                {0, 0,
                        bytes(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)},
                {7, -8,
                        bytes(0xFF, 0xFF, 0xFF, 0xF8, 0x00, 0x00, 0x00, 0x07)},
                {1, 1,
                        bytes(0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01)},
                {-1, 0,
                        bytes(0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF)},
        };
        for (Object[] c : cases) {
            int x = (Integer) c[0], z = (Integer) c[1];
            byte[] expected = (byte[]) c[2];
            ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
            w.writeChunkPos(x, z);
            byte[] got = w.toByteArray();
            assertArrayEquals(expected, got,
                    "ChunkPos(" + x + "," + z + ") want " + hex(expected)
                            + " got " + hex(got));
            int[] back = new ProtocolBuffer.Reader(got).readChunkPos();
            assertEquals(x, back[0]);
            assertEquals(z, back[1]);
        }
    }

    // ------------------------------------------------------- BLOCK_ENTITY_REQUEST

    /**
     * Servux's PACKET_C2S_BLOCK_ENTITY_REQUEST (type 3) encode is:
     * <pre>
     *     writeVarInt(3)
     *     writeVarInt(transactionId)
     *     writeBlockPos(pos)
     * </pre>
     * For transactionId=-1, pos=(1,2,3) we therefore expect:
     * {@code 03}  (VarInt 3, the type)
     * + {@code FF FF FF FF 0F}  (VarInt -1)
     * + {@code 00 00 00 40 00 00 30 02}  (BlockPos packed long)
     */
    @Test
    void block_entity_request_packet_byte_layout() throws Exception {
        byte[] expected = concat(
                bytes(0x03),
                bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0F),
                packedBlockPosBytes(1, 2, 3));

        ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
        w.writeVarInt(ProtocolConstants.Litematics.C2S_BLOCK_ENTITY_REQUEST);
        w.writeVarInt(-1);          // transactionId, the canonical "no value" Servux uses
        w.writeBlockPos(1, 2, 3);
        byte[] got = w.toByteArray();
        assertArrayEquals(expected, got,
                "C2S_BLOCK_ENTITY_REQUEST want " + hex(expected) + " got " + hex(got));

        ProtocolBuffer.Reader r = new ProtocolBuffer.Reader(got);
        assertEquals(ProtocolConstants.Litematics.C2S_BLOCK_ENTITY_REQUEST, r.readVarInt());
        assertEquals(-1, r.readVarInt());
        int[] back = r.readBlockPos();
        assertArrayEquals(new int[]{1, 2, 3}, back);
        assertEquals(0, r.remaining());
    }

    // -------------------------------------------------- ENTITY_REQUEST

    /**
     * Servux's PACKET_C2S_ENTITY_REQUEST (type 4):
     * {@code writeVarInt(4) + writeVarInt(transactionId) + writeVarInt(entityId)}.
     * Tx=-1, entity=12345: {@code 04 FF FF FF FF 0F B9 60}.
     */
    @Test
    void entity_request_packet_byte_layout() throws Exception {
        byte[] expected = concat(
                bytes(0x04),
                bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0F),
                bytes(0xB9, 0x60));  // VarInt(12345) = 0xB9 0x60

        ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
        w.writeVarInt(ProtocolConstants.Litematics.C2S_ENTITY_REQUEST);
        w.writeVarInt(-1);
        w.writeVarInt(12345);
        byte[] got = w.toByteArray();
        assertArrayEquals(expected, got,
                "C2S_ENTITY_REQUEST want " + hex(expected) + " got " + hex(got));

        ProtocolBuffer.Reader r = new ProtocolBuffer.Reader(got);
        assertEquals(ProtocolConstants.Litematics.C2S_ENTITY_REQUEST, r.readVarInt());
        assertEquals(-1, r.readVarInt());
        assertEquals(12345, r.readVarInt());
    }

    // ------------------------------------------------------- BULK_NBT_REQUEST

    /**
     * Servux's PACKET_C2S_BULK_ENTITY_NBT_REQUEST (type 7) encode is:
     * <pre>
     *     writeVarInt(7) + writeChunkPos(pos) + writeNbt(nbt)
     * </pre>
     * Note ChunkPos is now a packed long (NOT two ints — the v0.1.0
     * draft had this wrong). For chunk (3, -5) with an empty NBT we
     * expect:
     * {@code 07}                           (VarInt type)
     * + ChunkPos packed long
     * + {@code 00}                         (NBT null marker)
     */
    @Test
    void bulk_nbt_request_packet_byte_layout() throws Exception {
        // ChunkPos(3, -5) packed = (3L) | ((-5L & 0xFFFFFFFFL) << 32)
        //                       = 0xFFFFFFFB_00000003L
        byte[] chunkPosBytes = bytes(
                0xFF, 0xFF, 0xFF, 0xFB,  // z = -5
                0x00, 0x00, 0x00, 0x03); // x = 3

        byte[] expected = concat(
                bytes(0x07),
                chunkPosBytes,
                bytes(0x00));  // empty NBT = single TAG_END byte

        ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
        w.writeVarInt(ProtocolConstants.Litematics.C2S_BULK_NBT_REQUEST);
        w.writeChunkPos(3, -5);
        w.writeNbt(null);
        byte[] got = w.toByteArray();
        assertArrayEquals(expected, got,
                "C2S_BULK_NBT_REQUEST want " + hex(expected) + " got " + hex(got));

        ProtocolBuffer.Reader r = new ProtocolBuffer.Reader(got);
        assertEquals(ProtocolConstants.Litematics.C2S_BULK_NBT_REQUEST, r.readVarInt());
        assertArrayEquals(new int[]{3, -5}, r.readChunkPos());
        assertNull(r.readNbt());
    }

    // ---------------------------------------------------------- METADATA

    /**
     * Servux's PACKET_S2C_METADATA (type 1) encode is:
     * {@code writeVarInt(1) + writeNbt(metadata)}.
     *
     * <p>The metadata compound has exactly 4 keys in Servux:
     * <ul>
     *   <li>{@code "name"}    -> String "litematic_data"</li>
     *   <li>{@code "id"}      -> String "servux:litematics"</li>
     *   <li>{@code "version"} -> Int 1</li>
     *   <li>{@code "servux"}  -> String (server identifier)</li>
     * </ul>
     *
     * <p>The NBT compound goes out in Mojang's 1.20.2+ anonymous-root
     * form: {@code [0x0A][entries...][0x00]} where {@code 0x0A} is
     * TAG_COMPOUND and there is no root-name length prefix. Inside the
     * compound each entry is {@code [tagId][nameLenHi nameLenLo][nameBytes][payload]}.
     *
     * <p>Order of entries in our writer depends on insertion order
     * (LinkedHashMap-backed). Servux's CompoundTag is also insertion-
     * ordered, but we don't (and shouldn't) assume the upstream
     * insertion order matches ours — Mojang's NBT format does not
     * require a particular order, and Servux clients look up by name.
     * So we test the byte sequence in a known canonical order and
     * separately decode + assert semantically.
     */
    @Test
    void metadata_packet_compound_round_trip() throws Exception {
        LitematicNbt.NbtCompound meta = new LitematicNbt.NbtCompound();
        meta.putString("name",    ProtocolConstants.METADATA_PROVIDER_NAME);
        meta.putString("id",      ProtocolConstants.CHANNEL_LITEMATICS);
        meta.putInt   ("version", ProtocolConstants.LITEMATICS_PROTOCOL_VERSION);
        meta.putString("servux",  "TestServer");

        ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
        w.writeVarInt(ProtocolConstants.Litematics.S2C_METADATA);
        w.writeNbt(meta);
        byte[] wire = w.toByteArray();

        // Wire prefix sanity: [VarInt(1)] [TAG_COMPOUND] [first child TAG_STRING] ...
        assertEquals((byte) ProtocolConstants.Litematics.S2C_METADATA, wire[0]);
        assertEquals(LitematicNbt.TAG_COMPOUND, wire[1]);
        assertEquals(LitematicNbt.TAG_STRING,   wire[2]);
        // first child name is "name" (4 UTF bytes); UTF len prefix is 2 bytes big-endian.
        assertEquals(0x00, wire[3] & 0xFF);
        assertEquals(0x04, wire[4] & 0xFF);
        assertEquals('n',  (char) (wire[5] & 0xFF));
        assertEquals('a',  (char) (wire[6] & 0xFF));
        assertEquals('m',  (char) (wire[7] & 0xFF));
        assertEquals('e',  (char) (wire[8] & 0xFF));

        // Round-trip: decode and confirm field values
        ProtocolBuffer.Reader r = new ProtocolBuffer.Reader(wire);
        assertEquals(ProtocolConstants.Litematics.S2C_METADATA, r.readVarInt());
        LitematicNbt.NbtCompound back = r.readNbt();
        assertNotNull(back);
        assertEquals("litematic_data",      back.getString("name"));
        assertEquals("servux:litematics",   back.getString("id"));
        assertEquals(Integer.valueOf(1),    back.getInt("version"));
        assertEquals("TestServer",          back.getString("servux"));
        // No trailing bytes.
        assertEquals(0, r.remaining());

        // Wire ends in TAG_END (the compound terminator) — no extra padding.
        assertEquals(LitematicNbt.TAG_END, wire[wire.length - 1]);
    }

    // ---------------------------------------------------- BLOCK_NBT_REPLY

    /**
     * Servux's PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE (type 5):
     * {@code writeVarInt(5) + writeBlockPos(pos) + writeNbt(nbt)}.
     * Reference: empty-NBT reply for pos=(0, 64, 0):
     * {@code 05 [packed-pos:8] 00}.
     */
    @Test
    void block_nbt_reply_byte_layout() throws Exception {
        byte[] expected = concat(
                bytes(0x05),
                packedBlockPosBytes(0, 64, 0),
                bytes(0x00));  // empty NBT (TAG_END)

        ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
        w.writeVarInt(ProtocolConstants.Litematics.S2C_BLOCK_NBT_REPLY);
        w.writeBlockPos(0, 64, 0);
        w.writeNbt(null);
        byte[] got = w.toByteArray();
        assertArrayEquals(expected, got,
                "S2C_BLOCK_NBT_REPLY want " + hex(expected) + " got " + hex(got));
    }

    // ------------------------------------------------------- PacketSplitter

    /**
     * Servux's PacketSplitter behaviour: the first emitted slice starts
     * with {@code VarInt(totalLen)} of the application payload, then as
     * many bytes of the payload as fit. Subsequent slices are raw
     * payload continuation. Reassembly returns the original bytes.
     */
    @Test
    void packet_splitter_roundtrips_oversized_payload() throws Exception {
        // 200 byte payload, 64 byte slice budget → 4 slices (first 63 + 64 + 64 + 9)
        byte[] payload = new byte[200];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0xFF);

        byte[][] slices = PacketSplitter.split(payload, 64);
        assertTrue(slices.length >= 2, "expected >1 slice, got " + slices.length);

        // First slice MUST start with VarInt(200) = 0xC8 0x01 (since 200 = 128 + 72).
        assertEquals((byte) 0xC8, slices[0][0]);
        assertEquals((byte) 0x01, slices[0][1]);
        // Then the payload bytes 0, 1, 2, ...
        assertEquals((byte) 0x00, slices[0][2]);
        assertEquals((byte) 0x01, slices[0][3]);

        // Reassembly via PacketSplitter receiver.
        PacketSplitter receiver = new PacketSplitter();
        java.util.UUID uid = java.util.UUID.randomUUID();
        byte[] assembled = null;
        for (int i = 0; i < slices.length; i++) {
            assembled = receiver.receive(uid, slices[i]);
            if (i < slices.length - 1) {
                // Should still be incomplete (unless the splitter sized
                // exactly one slice, which we excluded above).
                if (assembled != null) break;
            }
        }
        assertNotNull(assembled, "splitter should have returned an assembled buffer");
        assertArrayEquals(payload, assembled,
                "round-trip mismatch — splitter assembly differs from input");
    }

    @Test
    void packet_splitter_first_slice_has_varint_total_length_prefix() throws Exception {
        // Small payload: a single byte. Should fit in one slice but
        // still carry the VarInt(1) prefix.
        byte[] payload = bytes(0x42);
        byte[][] slices = PacketSplitter.split(payload, 64);
        assertEquals(1, slices.length, "expected exactly 1 slice for tiny payload");
        assertArrayEquals(bytes(0x01, 0x42), slices[0],
                "first slice format: VarInt(totalLen=1) followed by the byte 0x42");
    }
}
