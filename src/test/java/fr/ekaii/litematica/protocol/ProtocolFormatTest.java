package fr.ekaii.litematica.protocol;

import fr.ekaii.litematica.core.LitematicNbt;
import fr.ekaii.litematica.protocol.handler.EasyPlaceHandler;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure round-trip tests for the wire format. No Paper / Bukkit
 * dependency so they run under {@code ./gradlew test} without a server.
 */
final class ProtocolFormatTest {

    @Test
    void varint_roundtrip() throws Exception {
        int[] cases = {0, 1, 127, 128, 255, 16383, 16384, 0x1FFFFF,
                Integer.MAX_VALUE, -1, -127, Integer.MIN_VALUE};
        for (int v : cases) {
            ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
            w.writeVarInt(v);
            byte[] bytes = w.toByteArray();
            assertTrue(bytes.length >= 1 && bytes.length <= 5,
                    "VarInt length out of bounds for " + v + ": " + bytes.length);
            int back = new ProtocolBuffer.Reader(bytes).readVarInt();
            assertEquals(v, back, "VarInt round-trip for " + v);
        }
    }

    @Test
    void string_roundtrip_utf8() throws Exception {
        String[] cases = {"", "abc", "minecraft:stone[facing=north,powered=true]",
                "héllo wörld", "日本語", "💎"};
        for (String s : cases) {
            ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
            w.writeString(s);
            String back = new ProtocolBuffer.Reader(w.toByteArray()).readString();
            assertEquals(s, back);
        }
    }

    @Test
    void blockpos_roundtrip() throws Exception {
        int[][] cases = {
                {0, 0, 0},
                {1, 2, 3},
                {-1, -2, -3},
                {123456, 200, -789012},
                {(1 << 25) - 1, 2047, -(1 << 25)},
                {-(1 << 25), -2048, (1 << 25) - 1},
        };
        for (int[] c : cases) {
            ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
            w.writeBlockPos(c[0], c[1], c[2]);
            int[] back = new ProtocolBuffer.Reader(w.toByteArray()).readBlockPos();
            assertArrayEquals(c, back, "BlockPos " + Arrays.toString(c) + " → " + Arrays.toString(back));
        }
    }

    @Test
    void chunkpos_roundtrip() throws Exception {
        int[][] cases = {{0, 0}, {1, 1}, {-1, -1}, {123, -456}, {Integer.MAX_VALUE, Integer.MIN_VALUE}};
        for (int[] c : cases) {
            ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
            w.writeChunkPos(c[0], c[1]);
            int[] back = new ProtocolBuffer.Reader(w.toByteArray()).readChunkPos();
            assertArrayEquals(c, back);
        }
    }

    @Test
    void nbt_compound_roundtrip_and_null() throws Exception {
        LitematicNbt.NbtCompound c = new LitematicNbt.NbtCompound();
        c.putString("Task", "LitematicaPaste");
        c.putInt("OriginX", 42);
        c.putInt("OriginY", 64);
        c.putInt("OriginZ", -17);
        c.putByte("PlaceEntities", (byte) 1);
        ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
        w.writeNbt(c);
        byte[] bytes = w.toByteArray();
        // First byte must be TAG_COMPOUND (10), and no UTF length follows
        // (anonymous-root Minecraft 1.20.2+ network format).
        assertEquals(LitematicNbt.TAG_COMPOUND, bytes[0]);
        // The compound's first child header should follow immediately.
        // For a compound with a String("Task") first, we expect:
        //   byte tag id=8 (string), then UTF length(2 bytes), then key bytes.
        assertEquals(LitematicNbt.TAG_STRING, bytes[1]);

        ProtocolBuffer.Reader r = new ProtocolBuffer.Reader(bytes);
        LitematicNbt.NbtCompound back = r.readNbt();
        assertNotNull(back);
        assertEquals("LitematicaPaste", back.getString("Task"));
        assertEquals(Integer.valueOf(42), back.getInt("OriginX"));
        assertEquals(Integer.valueOf(64), back.getInt("OriginY"));
        assertEquals(Integer.valueOf(-17), back.getInt("OriginZ"));
        assertEquals(Byte.valueOf((byte) 1), back.getByte("PlaceEntities"));
        // Buffer fully drained.
        assertEquals(0, r.remaining());
    }

    @Test
    void nbt_null_compound_writes_single_tag_end() throws Exception {
        ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
        w.writeNbt(null);
        byte[] bytes = w.toByteArray();
        assertEquals(1, bytes.length);
        assertEquals(LitematicNbt.TAG_END, bytes[0]);
        LitematicNbt.NbtCompound back = new ProtocolBuffer.Reader(bytes).readNbt();
        assertNull(back);
    }

    @Test
    void easyplace_request_byte_roundtrip() throws Exception {
        EasyPlaceHandler.EasyPlaceRequest src = new EasyPlaceHandler.EasyPlaceRequest();
        src.x = 123; src.y = 64; src.z = -456;
        src.blockState = "minecraft:repeater[delay=2,facing=north,locked=false,powered=false]";
        src.hasItemNbt = false;
        src.itemNbt = new byte[0];
        src.hitX = 0.5f; src.hitY = 0.5f; src.hitZ = 0.5f;
        src.facingOrdinal = 1;
        src.requestId = 99;

        ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
        // Match the on-wire framing: leading VarInt type then body.
        w.writeVarInt(ProtocolConstants.EasyPlace.C2S_PLACE_REQUEST);
        src.write(w);
        byte[] wire = w.toByteArray();

        ProtocolBuffer.Reader r = new ProtocolBuffer.Reader(wire);
        int type = r.readVarInt();
        assertEquals(ProtocolConstants.EasyPlace.C2S_PLACE_REQUEST, type);

        EasyPlaceHandler.EasyPlaceRequest back = EasyPlaceHandler.EasyPlaceRequest.read(r);
        assertEquals(src.x, back.x);
        assertEquals(src.y, back.y);
        assertEquals(src.z, back.z);
        assertEquals(src.blockState, back.blockState);
        assertEquals(src.hasItemNbt, back.hasItemNbt);
        assertEquals(src.hitX, back.hitX, 1e-6);
        assertEquals(src.hitY, back.hitY, 1e-6);
        assertEquals(src.hitZ, back.hitZ, 1e-6);
        assertEquals(src.facingOrdinal, back.facingOrdinal);
        assertEquals(src.requestId, back.requestId);
        assertEquals(0, r.remaining());

        // Now re-encode and assert byte-equality.
        ProtocolBuffer.Writer w2 = new ProtocolBuffer.Writer();
        w2.writeVarInt(type);
        back.write(w2);
        byte[] wire2 = w2.toByteArray();
        assertArrayEquals(wire, wire2,
                "byte equality after decode→encode");
    }

    @Test
    void easyplace_request_with_item_nbt_roundtrip() throws Exception {
        EasyPlaceHandler.EasyPlaceRequest src = new EasyPlaceHandler.EasyPlaceRequest();
        src.x = -1; src.y = 0; src.z = 1;
        src.blockState = "minecraft:stone";
        src.hasItemNbt = true;
        src.itemNbt = new byte[]{0x0A, 0x00, 0x00, 0x00};  // empty compound w/ header
        src.hitX = 0; src.hitY = 0; src.hitZ = 0;
        src.facingOrdinal = 4;
        src.requestId = 1;

        ProtocolBuffer.Writer w = new ProtocolBuffer.Writer();
        src.write(w);
        byte[] wire = w.toByteArray();

        EasyPlaceHandler.EasyPlaceRequest back =
                EasyPlaceHandler.EasyPlaceRequest.read(new ProtocolBuffer.Reader(wire));
        assertEquals(src.x, back.x);
        assertEquals(src.blockState, back.blockState);
        assertTrue(back.hasItemNbt);
        assertArrayEquals(src.itemNbt, back.itemNbt);
        assertFalse(back.hasItemNbt && back.itemNbt.length != src.itemNbt.length);
    }
}
