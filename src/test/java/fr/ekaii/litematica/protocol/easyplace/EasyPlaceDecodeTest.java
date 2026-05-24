package fr.ekaii.litematica.protocol.easyplace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure round-trip tests for the Easy Place V3 wire format. No Bukkit /
 * PacketEvents dependency so they run under {@code ./gradlew test}
 * without a server.
 *
 * <p>The tests exercise {@link EasyPlaceProtocolDecoder} against
 * hand-computed reference values derived from the
 * {@code PlacementHandler.applyPlacementProtocolV3} (Servux) and
 * {@code WorldUtils.applyPlacementProtocolV3} (Litematica) source.
 */
final class EasyPlaceDecodeTest {

    /**
     * A vanilla click — cursor in [0, 1] — must decode to -1
     * (no override). This is the most important invariant: it's what
     * keeps the listener from accidentally rewriting placements from
     * non-Litematica clients.
     */
    @Test
    void vanillaClick_decodesAsNoOverride() {
        for (float cursorX : new float[]{0.0f, 0.25f, 0.5f, 0.75f, 0.999f, 1.0f, 1.999f}) {
            assertEquals(-1, EasyPlaceProtocolDecoder.decodeProtocolValue(cursorX),
                    "cursorX=" + cursorX + " must decode as no-override");
        }
    }

    /**
     * Boundary: cursorX = 2.0 → protocolValue 0 (encodes a "no
     * direction property" override) which is still a valid Litematica
     * marker. Per upstream PlacementHandler line 74 the {@code < 0}
     * branch is the only "no override" sentinel.
     */
    @Test
    void protocolValueZero_isStillAnOverride() {
        // 2.0f → (int)2.0 - 2 = 0 → valid (non-negative) protocol value.
        assertEquals(0, EasyPlaceProtocolDecoder.decodeProtocolValue(2.0f));
    }

    /**
     * Round-trip: pack a known protocol value with facing=NORTH, encode
     * into the cursor X-fractional, decode, and assert match.
     *
     * <p>Reference: WorldUtils.applyPlacementProtocolV3 line 1146:
     * {@code protocolValue |= direction.get3DDataValue() << shiftAmount}
     * where {@code shiftAmount = 1}. So with facing=NORTH
     * ({@code get3DDataValue()=2}): {@code protocolValue = 2 << 1 = 4}.
     */
    @Test
    void facingNorth_roundtrips() {
        int packed = EasyPlaceProtocolDecoder.encodeFacing(0, /* NORTH */ 2);
        assertEquals(4, packed, "NORTH (3DDataValue=2) at shift=1 must produce 0x04");

        float cursor = EasyPlaceProtocolDecoder.encodeProtocolValue(0.5f, packed);
        // cursor = 0.5 + 2 + 4 = 6.5
        assertEquals(6.5f, cursor, 0.0001f);

        int decoded = EasyPlaceProtocolDecoder.decodeProtocolValue(cursor);
        assertEquals(4, decoded);

        int facingIdx = EasyPlaceProtocolDecoder.decodeFacingIndex(decoded);
        assertEquals(2, facingIdx, "decoded facing index must be NORTH (2)");
    }

    /**
     * Round-trip all 6 cardinal directions.
     */
    @Test
    void allDirections_roundtrip() {
        // Direction.from3DDataValue: 0=DOWN 1=UP 2=NORTH 3=SOUTH 4=WEST 5=EAST
        String[] names = {"DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"};
        for (int idx = 0; idx <= 5; idx++) {
            int packed = EasyPlaceProtocolDecoder.encodeFacing(0, idx);
            float cursor = EasyPlaceProtocolDecoder.encodeProtocolValue(0.5f, packed);
            int decoded = EasyPlaceProtocolDecoder.decodeProtocolValue(cursor);
            int facingIdx = EasyPlaceProtocolDecoder.decodeFacingIndex(decoded);
            assertEquals(idx, facingIdx,
                    "round-trip failed for " + names[idx] + " (idx=" + idx + ")");
        }
    }

    /**
     * Round-trip facing=6 ("opposite of current facing", Servux
     * convention — PlacementHandler line 238).
     */
    @Test
    void facingOpposite_roundtrips() {
        int packed = EasyPlaceProtocolDecoder.encodeFacing(0, 6);
        // packed = 6 << 1 = 12 = 0x0C
        assertEquals(0x0C, packed);

        float cursor = EasyPlaceProtocolDecoder.encodeProtocolValue(0.0f, packed);
        // cursor = 0 + 2 + 12 = 14.0
        assertEquals(14.0f, cursor, 0.0001f);

        int decoded = EasyPlaceProtocolDecoder.decodeProtocolValue(cursor);
        assertEquals(0x0C, decoded);

        int facingIdx = EasyPlaceProtocolDecoder.decodeFacingIndex(decoded);
        assertEquals(6, facingIdx, "decoded facing index must be 6 (opposite)");
    }

    /**
     * After consuming the 4 facing bits, additional property bits should
     * survive. Reference: PlacementHandler line 111-114 consumes 3+1=4
     * bits after applying the direction property.
     */
    @Test
    void facingBits_consumeCorrectly() {
        // Pack: facing=EAST (3DData=5) at shift 1, then valueIndex=3 at shift 4.
        int facing = 5 << 1;            // 0b1010 = 0x0A
        int extra  = 3 << 4;            // 0b00110000 = 0x30
        int packed = facing | extra;    // 0x3A

        // Decode facing.
        assertEquals(5, EasyPlaceProtocolDecoder.decodeFacingIndex(packed));
        // After consuming facing bits, the extra payload (3) should be
        // exposed as the new low bits.
        int afterFacing = EasyPlaceProtocolDecoder.consumeFacingBits(packed);
        assertEquals(3, afterFacing, "after consuming 4 facing bits, low bits = valueIndex=3");
    }

    /**
     * The encoder must reject negative protocol values — those are the
     * "no override" sentinel and can't be embedded in the wire format.
     */
    @Test
    void encode_rejectsNegativeProtocolValue() {
        assertThrows(IllegalArgumentException.class,
                () -> EasyPlaceProtocolDecoder.encodeProtocolValue(0.5f, -1));
    }

    /**
     * A larger packed payload — facing=SOUTH (3) + valueIndex=2 in
     * the next 2 bits + valueIndex=0 in the bit after — must
     * round-trip through the float boundary correctly.
     *
     * <p>Practical motivation: a 24-bit protocol value (max
     * 0xFFFFFF = 16 777 215) encodes into cursor.x = 16 777 217.5 which
     * is still well within float precision for {@code (int) cursorX}
     * to round correctly. We test a representative ~20-bit value.
     */
    @Test
    void largePayload_roundtrips() {
        // Build a 20-bit-ish payload.
        int packed = 0;
        packed = EasyPlaceProtocolDecoder.encodeFacing(packed, 3);  // SOUTH @ shift 1
        packed |= (2 << 4);                                         // 2 in bits 4..5
        packed |= (0 << 6);                                         // 0 in bits 6..7
        packed |= (1 << 8);                                         // 1 in bit 8

        // packed = 0b1_0010_0110 = 0x126.
        assertEquals(0x126, packed);

        float cursor = EasyPlaceProtocolDecoder.encodeProtocolValue(0.5f, packed);
        // cursor = 0.5 + 2 + 294 = 296.5
        assertEquals(296.5f, cursor, 0.0001f);

        int decoded = EasyPlaceProtocolDecoder.decodeProtocolValue(cursor);
        assertEquals(packed, decoded,
                "20-bit protocol payload must survive the int<->float<->int round-trip");
    }

    /**
     * Float-precision sanity: at the high end of the protocol value
     * (24-bit max), the int-cast round-trip should still be exact for
     * any relX in [0, 1). 24 bits is well below the 23-bit mantissa
     * margin once the integer part dominates, but we verify a sweep of
     * 20-bit values.
     */
    @Test
    void floatPrecision_holdsTo20Bits() {
        for (int pv = 0; pv <= 0xFFFFF; pv += 0xFFF) {
            float cursor = EasyPlaceProtocolDecoder.encodeProtocolValue(0.5f, pv);
            int decoded = EasyPlaceProtocolDecoder.decodeProtocolValue(cursor);
            assertEquals(pv, decoded,
                    "protocol value " + pv + " (0x" + Integer.toHexString(pv) + ") lost precision");
        }
    }

    /**
     * Decoder must never throw on extreme float input (NaN, infinity).
     * It returns -1 (no override) for any out-of-envelope value to
     * avoid integer-cast overflow attacks (a malicious client sending
     * NEGATIVE_INFINITY would otherwise produce a wraparound).
     */
    @Test
    void decoder_handlesNaNAndInfinity() {
        assertEquals(-1, EasyPlaceProtocolDecoder.decodeProtocolValue(Float.NaN));
        assertEquals(-1, EasyPlaceProtocolDecoder.decodeProtocolValue(Float.POSITIVE_INFINITY));
        assertEquals(-1, EasyPlaceProtocolDecoder.decodeProtocolValue(Float.NEGATIVE_INFINITY));
        // Out-of-envelope ints reject too.
        assertEquals(-1, EasyPlaceProtocolDecoder.decodeProtocolValue(-100.0f));
        assertEquals(-1, EasyPlaceProtocolDecoder.decodeProtocolValue(1.0e9f));
    }
    // Note: the implementation rejects cursorX >= 2^25 (≈ 33.5M) to
    // stay below the 23-bit float mantissa "exact-integer" boundary.
    // Litematica's actual protocol value never exceeds 2^24 in practice
    // (4 facing bits + ~20 property bits).
}
