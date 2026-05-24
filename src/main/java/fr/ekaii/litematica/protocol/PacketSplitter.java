package fr.ekaii.litematica.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Re-assembly side of the Servux {@code PacketSplitter}.
 *
 * <p>Servux splits oversized payloads into N slices and ships them as
 * {@code PACKET_S2C_NBT_RESPONSE_DATA} (type 11) or
 * {@code PACKET_C2S_NBT_RESPONSE_DATA} (type 13) packets. The first
 * slice has a {@code VarInt} of the total payload length prepended;
 * subsequent slices are raw bytes that continue from where the previous
 * one ended. When the accumulated body reaches the declared total
 * length, the session is complete and the assembled buffer is returned
 * for normal {@code VarInt + NBT} parsing.
 *
 * <p>Servux keys sessions by a pre-shared random {@code long}; on our
 * receive side we don't see that key — the wire packet itself does not
 * carry one — so we key by the originating {@link UUID} (player). That
 * matches Servux behaviour: the upstream
 * {@code ServuxLitematicaHandler#decodeServerData} also generates one
 * session key per UUID for inbound reassembly.
 *
 * <p>Each instance is stateful and not thread-safe; callers should
 * dispatch packets for one player from a single thread (the
 * plugin-message thread is single-threaded per player on Paper).
 */
public final class PacketSplitter {

    /**
     * Default cap on the size of a reassembled C2S buffer. Matches
     * Servux's {@code DEFAULT_MAX_RECEIVE_SIZE_C2S = 16 MiB}. Beyond
     * this we abort the session and log a warning rather than allocate
     * unbounded memory.
     */
    public static final int DEFAULT_MAX_C2S_RECEIVE = 16 * 1024 * 1024;

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final int maxSize;

    public PacketSplitter() {
        this(DEFAULT_MAX_C2S_RECEIVE);
    }

    public PacketSplitter(int maxSize) {
        this.maxSize = maxSize;
    }

    /**
     * Feed one splitter slice for {@code player} (the bytes that
     * followed the {@code VarInt(packetType)} of the inbound packet).
     *
     * @return the fully assembled payload bytes (i.e. {@code VarInt +
     *         NBT}) once the last slice arrives, or {@code null} if
     *         more slices are still expected.
     */
    public byte[] receive(UUID player, byte[] slice) throws IOException {
        Session session = sessions.get(player);
        if (session == null) {
            session = new Session();
            sessions.put(player, session);
        }
        byte[] done = session.feed(slice, maxSize);
        if (done != null) {
            sessions.remove(player);
        }
        return done;
    }

    /** Discard any partial state for a player (used on disconnect). */
    public void forget(UUID player) {
        sessions.remove(player);
    }

    /**
     * Encode a complete payload {@code (VarInt transactionId + NBT
     * payload)} as one or more slices ready to be wrapped as type-11
     * or type-13 wire packets. The first slice has a leading
     * {@code VarInt(totalLen)}; subsequent slices are raw bytes.
     *
     * <p>Mirrors {@code PacketSplitter.send} on the upstream side.
     */
    public static byte[][] split(byte[] payload, int payloadLimit) throws IOException {
        int len = payload.length;
        if (payloadLimit < 8) {
            throw new IOException("payloadLimit too small: " + payloadLimit);
        }
        // The first slice must contain the VarInt(totalLen) header
        // inside its budget. Worst-case the VarInt is 5 bytes.
        java.util.List<byte[]> slices = new java.util.ArrayList<>();
        int offset = 0;
        boolean first = true;
        while (offset < len || first) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (first) {
                writeVarIntTo(baos, len);
            }
            int budget = payloadLimit - baos.size();
            int thisLen = Math.min(len - offset, budget);
            if (thisLen > 0) {
                baos.write(payload, offset, thisLen);
                offset += thisLen;
            }
            slices.add(baos.toByteArray());
            first = false;
            // Guard against an infinite loop on a zero-length payload
            // (first slice carries only the VarInt(0) header).
            if (len == 0) {
                break;
            }
        }
        return slices.toArray(new byte[0][]);
    }

    private static void writeVarIntTo(ByteArrayOutputStream baos, int value) {
        while ((value & ~0x7F) != 0) {
            baos.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        baos.write(value & 0x7F);
    }

    // -------------------------------------------------------------- session

    private static final class Session {
        private int expectedSize = -1;
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();

        byte[] feed(byte[] slice, int maxSize) throws IOException {
            int idx = 0;
            if (expectedSize < 0) {
                // First slice: parse the VarInt totalLength prefix.
                int len = 0;
                int shift = 0;
                int b;
                do {
                    if (idx >= slice.length) {
                        throw new IOException("splitter: VarInt header truncated");
                    }
                    b = slice[idx++] & 0xFF;
                    len |= (b & 0x7F) << shift;
                    shift += 7;
                    if (shift >= 35) {
                        throw new IOException("splitter: VarInt header too long");
                    }
                } while ((b & 0x80) != 0);
                if (len < 0 || len > maxSize) {
                    throw new IOException("splitter: declared size " + len
                            + " out of bounds (max " + maxSize + ")");
                }
                expectedSize = len;
            }
            int bodyLen = slice.length - idx;
            if (bodyLen > 0) {
                if (received.size() + bodyLen > expectedSize) {
                    // Trim — Servux's PacketSplitter does not (it relies
                    // on the underlying buffer length), but we are
                    // defensive against a malformed sender.
                    bodyLen = expectedSize - received.size();
                }
                received.write(slice, idx, bodyLen);
            }
            if (received.size() >= expectedSize) {
                return received.toByteArray();
            }
            return null;
        }
    }
}
