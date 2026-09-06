package fr.ekaii.litematica.protocol;

import fr.ekaii.litematica.core.LitematicNbt;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Servux wire-v2 "Data Tag" blob codec
 * (Int32 length + gzip(NBT file stream, empty root name)) and the
 * v1-vs-v2 framing discriminator. Pure JVM, no Bukkit.
 */
final class DataTagCodecTest {

    private static LitematicNbt.NbtCompound sampleCompound() {
        LitematicNbt.NbtCompound c = new LitematicNbt.NbtCompound();
        c.putString("Task", "LitematicaPaste");
        c.putInt("Interval", 1);
        c.putString("Name", "test-cube");
        c.putIntArray("Origin", new int[] {100, 64, 100});
        c.putInt("Rotation", 0);
        c.putInt("Mirror", 0);
        LitematicNbt.NbtCompound schem = new LitematicNbt.NbtCompound();
        schem.putInt("Version", 6);
        schem.putInt("MinecraftDataVersion", 4438);
        c.put("Schematics", schem);
        LitematicNbt.NbtCompound subs = new LitematicNbt.NbtCompound();
        LitematicNbt.NbtCompound region = new LitematicNbt.NbtCompound();
        region.putIntArray("Pos", new int[] {0, 0, 0});
        region.putInt("Rotation", 0);
        region.putByte("Enabled", (byte) 1);
        subs.put("main", region);
        c.put("SubRegions", subs);
        return c;
    }

    @Test
    void roundtrip_compound() throws Exception {
        LitematicNbt.NbtCompound in = sampleCompound();
        byte[] blob = DataTagCodec.encode(in);

        // Framing: Int32 big-endian length prefix covering the rest.
        int declared = ((blob[0] & 0xFF) << 24) | ((blob[1] & 0xFF) << 16)
                | ((blob[2] & 0xFF) << 8) | (blob[3] & 0xFF);
        assertEquals(blob.length - 4, declared, "Int32 length must frame the body exactly");
        // Body is gzip (magic 1f 8b).
        assertEquals(0x1f, blob[4] & 0xFF, "gzip magic byte 0");
        assertEquals(0x8b, blob[5] & 0xFF, "gzip magic byte 1");

        LitematicNbt.NbtCompound out = DataTagCodec.decode(blob);
        assertNotNull(out);
        assertEquals("LitematicaPaste", out.getString("Task"));
        assertEquals(1, out.getInt("Interval"));
        assertArrayEquals(new int[] {100, 64, 100}, out.getIntArray("Origin"));
        assertEquals(6, out.getCompound("Schematics").getInt("Version"));
        assertEquals((byte) 1,
                out.getCompound("SubRegions").getCompound("main").getByte("Enabled"));
    }

    @Test
    void empty_compound_decodes_to_null() throws Exception {
        byte[] blob = DataTagCodec.encode(null);
        assertNull(DataTagCodec.decode(blob), "TAG_END body must decode to null");
        byte[] blob2 = DataTagCodec.encode(new LitematicNbt.NbtCompound());
        assertNull(DataTagCodec.decode(blob2), "empty compound encodes as TAG_END");
    }

    @Test
    void uncompressed_fallback_accepted() throws Exception {
        // Upstream DataByteBufUtils.fromByteBuf falls back to an
        // uncompressed NBT file stream on ZipException. Mirror that.
        LitematicNbt.NbtCompound in = new LitematicNbt.NbtCompound();
        in.putString("k", "v");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(body)) {
            LitematicNbt.writeNamedTag(dos, new LitematicNbt.NamedTag("", in));
        }
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(blob);
        dos.writeInt(body.size());
        dos.write(body.toByteArray());

        LitematicNbt.NbtCompound out = DataTagCodec.decode(blob.toByteArray());
        assertNotNull(out);
        assertEquals("v", out.getString("k"));
    }

    @Test
    void reader_position_advances_past_blob() throws Exception {
        // A blob embedded mid-stream (as in type 5/6/16 payloads) must
        // leave the reader exactly after its Int32+body.
        byte[] blob = DataTagCodec.encode(sampleCompound());
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(blob);
        stream.write(0x7f); // trailing sentinel
        ProtocolBuffer.Reader r = new ProtocolBuffer.Reader(stream.toByteArray());
        LitematicNbt.NbtCompound out = DataTagCodec.decode(r);
        assertNotNull(out);
        assertEquals(1, r.remaining(), "decode must consume exactly the blob");
        assertEquals(0x7f, r.readByte());
    }

    @Test
    void framing_discriminator_v2_blob() throws Exception {
        byte[] blob = DataTagCodec.encode(sampleCompound());
        assertTrue(DataTagCodec.looksLikeDataTagBlob(blob),
                "a real Data Tag blob must be detected as v2");
    }

    @Test
    void framing_discriminator_v1_payload() throws Exception {
        // v1 reassembled payload: VarInt(transactionId) + vanilla network
        // NBT. The old paste path sends transactionId 12 (0x0c).
        LitematicNbt.NbtCompound frame = new LitematicNbt.NbtCompound();
        frame.putString("Task", "LitematicaPaste");
        ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
        w.writeVarInt(12);
        w.writeNbt(frame);
        byte[] v1 = w.toByteArray();
        assertFalse(DataTagCodec.looksLikeDataTagBlob(v1),
                "a v1 transactionId+NBT payload must not be detected as v2");

        // And with the legacy -1 transactionId (5-byte VarInt).
        ProtocolBuffer.Writer w2 = new ProtocolBuffer.Writer();
        w2.writeVarInt(-1);
        w2.writeNbt(frame);
        assertFalse(DataTagCodec.looksLikeDataTagBlob(w2.toByteArray()));
    }

    @Test
    void splitter_roundtrip_of_v2_paste_payload() throws Exception {
        // End-to-end framing: Data Tag blob -> outer splitter slices ->
        // reassembly -> discriminator -> decode. This is the exact path
        // a 26.2-0.28.5 client's Servux Paste takes (type 13 slices).
        LitematicNbt.NbtCompound in = sampleCompound();
        byte[] blob = DataTagCodec.encode(in);
        byte[][] slices = PacketSplitter.split(blob, 1024);
        assertTrue(slices.length >= 1);

        PacketSplitter rx = new PacketSplitter();
        java.util.UUID uid = java.util.UUID.randomUUID();
        byte[] assembled = null;
        for (byte[] slice : slices) {
            assembled = rx.receive(uid, slice);
        }
        assertNotNull(assembled, "stream must complete after the last slice");
        assertArrayEquals(blob, assembled, "reassembly must be byte-exact");
        assertTrue(DataTagCodec.looksLikeDataTagBlob(assembled));
        LitematicNbt.NbtCompound out = DataTagCodec.decode(assembled);
        assertEquals("LitematicaPaste", out.getString("Task"));
    }

    @Test
    void gzip_without_int_prefix_is_rejected() {
        // Defensive: a bare gzip stream (no Int32 frame) must not decode.
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(new byte[] {0x0a, 0, 0, 0});
            }
            byte[] bare = out.toByteArray();
            boolean threw = false;
            try {
                DataTagCodec.decode(bare);
            } catch (Exception expected) {
                threw = true;
            }
            assertTrue(threw, "bare gzip without Int32 framing must throw");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void all_tag_types_survive_roundtrip() throws Exception {
        LitematicNbt.NbtCompound c = new LitematicNbt.NbtCompound();
        c.putByte("b", (byte) -3);
        c.putShort("s", (short) -12345);
        c.putInt("i", Integer.MIN_VALUE);
        c.putLong("l", Long.MAX_VALUE);
        c.putFloat("f", 3.5f);
        c.putDouble("d", -2.25d);
        c.putString("str", "héllo 日本語");
        c.putByteArray("ba", new byte[] {1, 2, 3});
        c.putIntArray("ia", new int[] {-1, 0, 1});
        c.putLongArray("la", new long[] {Long.MIN_VALUE, 42L});
        c.put("list", new LitematicNbt.NbtList(LitematicNbt.TAG_STRING, List.of(
                new LitematicNbt.NbtString("a"), new LitematicNbt.NbtString("b"))));

        LitematicNbt.NbtCompound out = DataTagCodec.decode(DataTagCodec.encode(c));
        assertEquals((byte) -3, out.getByte("b"));
        assertEquals((short) -12345, out.getShort("s"));
        assertEquals(Integer.MIN_VALUE, out.getInt("i"));
        assertEquals(Long.MAX_VALUE, out.getLong("l"));
        assertEquals(3.5f, out.getFloat("f"));
        assertEquals(-2.25d, out.getDouble("d"));
        assertEquals("héllo 日本語", out.getString("str"));
        assertArrayEquals(new byte[] {1, 2, 3}, out.getByteArray("ba"));
        assertArrayEquals(new int[] {-1, 0, 1}, out.getIntArray("ia"));
        assertArrayEquals(new long[] {Long.MIN_VALUE, 42L}, out.getLongArray("la"));
        assertEquals(2, out.getList("list").size());
    }
}
