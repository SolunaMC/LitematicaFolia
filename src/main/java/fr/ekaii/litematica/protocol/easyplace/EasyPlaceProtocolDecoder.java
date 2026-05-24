package fr.ekaii.litematica.protocol.easyplace;

/**
 * Pure decoder/encoder for Litematica Easy Place V3 wire format.
 *
 * <h2>Wire format</h2>
 * Litematica clients (with no Servux installed) encode the placement
 * protocol value into the <strong>X-fractional component of the
 * cursor position</strong> in a vanilla
 * {@code ServerboundUseItemOnPacket}. The cursor position in that
 * packet is a {@code Vector3f} <em>relative to the clicked block</em>
 * (range nominally {@code [0, 1]} on each axis for a legitimate
 * vanilla click).
 *
 * <p>The wire formula (extracted by reading {@code
 * fi.dy.masa.litematica.util.WorldUtils#applyPlacementProtocolV3}
 * line 1200 in litematica-source/26.1.2 — read only, no code copied):
 *
 * <pre>
 *     cursor.x = relX + 2 + protocolValue
 * </pre>
 *
 * where {@code relX} is the original raycast hit-relative X
 * (in {@code [0, 1]}) and {@code protocolValue >= 0} is a packed bit
 * field carrying:
 * <ul>
 *   <li>bit 0: unused (reserved / sentinel)</li>
 *   <li>bits 1..3: facing index (0..5 = {@code Direction.from3DDataValue},
 *       6 = "opposite of the requested facing")</li>
 *   <li>bits 4..N: variable-bit-width fields for the remaining
 *       {@link PlacementHandler}-whitelisted properties (HALF, AXIS,
 *       CHEST_TYPE, …). The bit widths are computed at decode time from
 *       the live block-state property's possible-value count via
 *       {@code Mth.smallestEncompassingPowerOfTwo}.</li>
 * </ul>
 *
 * <p>Server-side, Servux's
 * {@code fi.dy.masa.servux.util.PlacementHandler#applyPlacementProtocolV3}
 * (line 67) decodes via:
 *
 * <pre>
 *     int protocolValue = (int) (hitVec.x - pos.x) - 2;
 *     if (protocolValue &lt; 0) return state; // no override
 * </pre>
 *
 * <p>Negative protocol values signal "no override" (a normal vanilla
 * placement) and we MUST leave the placement untouched.
 *
 * <h2>Bit layout (decode side)</h2>
 * Reading bits proceeds in the order properties were written by the
 * client, which is:
 * <ol>
 *   <li>First direction property (4 bits: 3 data bits + the unused-bit
 *       at position 0).</li>
 *   <li>{@link PlacementHandler#WHITELISTED_PROPERTIES alphabetically
 *       sorted whitelisted properties} other than the direction one,
 *       each consuming {@code ceil(log2(size))} bits.</li>
 * </ol>
 *
 * <h2>Citation</h2>
 * Server-side decode: {@code /tmp/servux-source/.../PlacementHandler.java}
 * line 69 ({@code (int)(hitVec.x - pos.x) - 2}). Client-side encode:
 * {@code /tmp/litematica-source/.../WorldUtils.java} line 1200
 * ({@code pos.getX() + relX + 2 + protocolValue}). Read only — no code
 * copied; only the formula (which is a fact about an interoperable
 * wire format, not copyrightable).
 *
 * <h2>Why x and not y/z</h2>
 * Mojang's server only sanity-checks that the cursor float component
 * fits in a reasonable range — but the X axis is the one that's
 * least likely to interact with slab-half / observer-orientation
 * heuristics on the placement side. Litematica picks X for the same
 * reason Servux does: the hit-pos subtract check (which would normally
 * reject cursor.x &gt;= 2) is the only thing that needs disabling on
 * the server side.
 */
public final class EasyPlaceProtocolDecoder {

    private EasyPlaceProtocolDecoder() {
    }

    /**
     * Decode the raw protocol value from the cursor X-fractional
     * component. Returns {@code -1} (no override) for a vanilla click.
     *
     * <p>Mirrors {@code PlacementHandler.applyPlacementProtocolV3}
     * line 69:
     * <pre>
     *     int protocolValue = (int) (context.hitVec().x -
     *                                (double) context.pos().getX()) - 2;
     *     if (protocolValue &lt; 0) return state; // no override
     * </pre>
     *
     * <p>In Bukkit / PacketEvents terms, {@code cursor.x} is already
     * {@code hitVec.x - pos.x} (the packet ships relative coordinates),
     * so the formula collapses to:
     * <pre>
     *     int protocolValue = (int) cursor.x - 2;
     * </pre>
     *
     * @param cursorX the X component of the cursor position from the
     *                wrapped {@code USE_ITEM_ON} packet.
     * @return the decoded protocol value, or {@code -1} for "no
     *         override" (cursorX out of encoding range).
     */
    public static int decodeProtocolValue(float cursorX) {
        // Guard against pathological floats. (int)Float.NEGATIVE_INFINITY
        // returns Integer.MIN_VALUE, and Integer.MIN_VALUE - 2 overflows
        // to a large positive — which would be misinterpreted as a valid
        // protocol value. The wire format always has cursorX in
        // [0, 1 << 24 + 1.999] for a real Litematica client, so any
        // value outside that "sane" envelope is rejected.
        if (Float.isNaN(cursorX) || cursorX < 2.0f || cursorX >= 0x1.0p25f) {
            return -1;
        }
        int protocolValue = (int) cursorX - 2;
        return protocolValue < 0 ? -1 : protocolValue;
    }

    /**
     * Encode a protocol value back into a cursor X-fractional component.
     * Used by the test fixture to round-trip the decoder.
     *
     * <p>Mirrors {@code WorldUtils.applyPlacementProtocolV3} line 1200:
     * <pre>
     *     double x = pos.getX() + relX + 2 + protocolValue;
     * </pre>
     *
     * @param relX           the original raycast-hit relative X (in
     *                       {@code [0, 1]}); set to 0.5 for "center of
     *                       face" if you don't care.
     * @param protocolValue  the bit field to embed (must be {@code >= 0}).
     * @return the encoded cursor.x value to send in the packet.
     */
    public static float encodeProtocolValue(float relX, int protocolValue) {
        if (protocolValue < 0) {
            throw new IllegalArgumentException("protocolValue must be >= 0, got " + protocolValue);
        }
        return relX + 2.0f + protocolValue;
    }

    /**
     * Decode the facing index from a protocol value. Mirrors
     * {@code PlacementHandler.applyDirectionProperty} line 236:
     * <pre>
     *     int decodedFacingIndex = (protocolValue &amp; 0xF) &gt;&gt; 1;
     * </pre>
     *
     * @return facing index in {@code 0..5} (= {@code Direction.from3DDataValue}),
     *         {@code 6} = opposite of state's current facing, or {@code -1}
     *         if no facing override is encoded.
     */
    public static int decodeFacingIndex(int protocolValue) {
        if (protocolValue < 0) return -1;
        return (protocolValue & 0xF) >> 1;
    }

    /**
     * Consume the {@code 3 + 1 = 4} bits used by the facing override so
     * downstream property decoders can pick up at the right offset.
     *
     * <p>Per {@code PlacementHandler} line 111-114:
     * <pre>
     *     // Consume the bits used for the facing
     *     protocolValue &gt;&gt;&gt;= 3;
     *     // Consume the lowest unused bit
     *     protocolValue &gt;&gt;&gt;= 1;
     * </pre>
     */
    public static int consumeFacingBits(int protocolValue) {
        return protocolValue >>> 4;
    }

    /**
     * Encode a facing index (0..5 or 6=opposite) into a protocol value.
     * Mirrors {@code WorldUtils.applyPlacementProtocolV3} line 1146:
     * <pre>
     *     protocolValue |= direction.get3DDataValue() &lt;&lt; shiftAmount;
     * </pre>
     * with {@code shiftAmount=1} (skipping the lowest unused bit).
     */
    public static int encodeFacing(int protocolValue, int facingIndex3DData) {
        return protocolValue | (facingIndex3DData << 1);
    }
}
