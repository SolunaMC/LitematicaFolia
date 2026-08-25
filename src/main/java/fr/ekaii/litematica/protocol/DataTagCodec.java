package fr.ekaii.litematica.protocol;

import fr.ekaii.litematica.core.LitematicNbt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

/**
 * Codec for the Servux protocol-v2 "Data Tag" wire blobs (the
 * "Data Tag Compression packets" introduced by upstream Servux
 * {@code 26.2-0.11.3} / Litematica {@code 26.2-0.28.5}, commit
 * {@code a24b3a3} "network protocol overhaul ... Introduce Compressed
 * Data Tag Packets").
 *
 * <h2>Wire format</h2>
 * <pre>
 *   blob := Int32(bodyLength, big-endian) ++ body
 *   body := gzip( nbtFileStream )                 // writer always gzips
 *   nbtFileStream := [tagId=10][writeUTF(rootName="")][compound payload]
 * </pre>
 *
 * The inner serialization is byte-identical to the classic Java-edition
 * NBT <em>file</em> format (named root tag, {@code DataOutput.writeUTF}
 * names) — exactly what {@link LitematicNbt#writeNamedTag} /
 * {@link LitematicNbt#readNamedTag} already speak. The upstream reader
 * ({@code DataByteBufUtils.fromByteBuf}) tolerates an uncompressed body
 * as a fallback; the writer always compresses. A body whose first tag
 * byte is {@code TAG_END} decodes to "no data" ({@code null}).
 *
 * <p>Facts verified against {@code sakura-ryoko/servux} tag
 * {@code 26.2-0.11.3}: {@code util/data/tag/util/DataByteBufUtils.java},
 * {@code util/data/tag/util/DataFileUtils.java} (readFromNbtStream /
 * writeToNbtStream), and each {@code util/data/tag/*Data.java} write()
 * (all standard NBT payload encodings, ids 0..12). No upstream code was
 * copied — wire facts only. See {@code SERVUX_WIRE_FORMAT.md}.
 */
public final class DataTagCodec {

    private DataTagCodec() {
    }

    /**
     * Encode a compound as a v2 Data Tag blob (Int32 length prefix +
     * gzipped NBT file stream with an empty root name). A {@code null}
     * or empty compound encodes as the gzipped single {@code TAG_END}
     * byte, mirroring upstream {@code EmptyData}.
     */
    public static byte[] encode(LitematicNbt.NbtCompound compound) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream gz = new DataOutputStream(new GZIPOutputStream(body))) {
            if (compound == null || compound.size() == 0) {
                gz.writeByte(LitematicNbt.TAG_END);
            } else {
                LitematicNbt.writeNamedTag(gz, new LitematicNbt.NamedTag("", compound));
            }
        }
        byte[] gzBytes = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(gzBytes.length + 4);
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(gzBytes.length);
        dos.write(gzBytes);
        dos.flush();
        return out.toByteArray();
    }

    /**
     * Decode a v2 Data Tag blob starting at the reader's current
     * position. Returns {@code null} when the body carries no data
     * (TAG_END root), matching upstream semantics.
     *
     * @throws IOException on malformed framing or NBT.
     */
    public static LitematicNbt.NbtCompound decode(ProtocolBuffer.Reader r) throws IOException {
        int len = r.readInt();
        if (len < 0 || len > r.remaining()) {
            throw new IOException("data-tag blob length " + len + " out of bounds (remaining "
                    + r.remaining() + ")");
        }
        byte[] body = new byte[len];
        for (int i = 0; i < len; i++) {
            body[i] = r.readByte();
        }
        return decodeBody(body);
    }

    /** Decode a full blob held in a standalone byte array. */
    public static LitematicNbt.NbtCompound decode(byte[] blob) throws IOException {
        return decode(new ProtocolBuffer.Reader(blob));
    }

    private static LitematicNbt.NbtCompound decodeBody(byte[] body) throws IOException {
        try (DataInputStream dis = new DataInputStream(
                new GZIPInputStream(new ByteArrayInputStream(body)))) {
            return readNbtFileStream(dis);
        } catch (ZipException notGzip) {
            // Upstream fallback: tolerate an uncompressed body.
            try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(body))) {
                return readNbtFileStream(dis);
            }
        }
    }

    private static LitematicNbt.NbtCompound readNbtFileStream(DataInputStream dis) throws IOException {
        // Peek the tag id: TAG_END means "no data" (upstream EmptyData).
        int tagId = dis.read();
        if (tagId <= 0) {
            return null;
        }
        if (tagId != LitematicNbt.TAG_COMPOUND) {
            throw new IOException("data-tag root must be a compound, got id " + tagId);
        }
        // Reconstruct the named-tag stream for LitematicNbt (id + name + payload).
        // We already consumed the id byte, so feed the remainder prefixed by it.
        ByteArrayOutputStream rest = new ByteArrayOutputStream();
        rest.write(tagId);
        byte[] buf = new byte[8192];
        int n;
        while ((n = dis.read(buf)) > 0) {
            rest.write(buf, 0, n);
        }
        try (DataInputStream full = new DataInputStream(
                new ByteArrayInputStream(rest.toByteArray()))) {
            LitematicNbt.NamedTag named = LitematicNbt.readNamedTag(full);
            if (named.tag() instanceof LitematicNbt.NbtCompound c) {
                return c;
            }
            throw new IOException("data-tag root not a compound after parse");
        }
    }

    /**
     * Heuristic: does {@code assembled} (a fully reassembled splitter
     * stream) carry a v2 Data Tag blob rather than the v1
     * {@code VarInt(transactionId) + vanilla network NBT} payload?
     *
     * <p>A v2 blob is exactly {@code Int32(L) + body[L]}, so the first
     * four bytes as a big-endian int equal {@code length - 4}. A v1
     * payload starts with a VarInt transactionId (the old paste path
     * sends 12 = 0x0c, and any 1-byte VarInt below 0x80 makes the int32
     * reading enormous), so a false positive would require a ~200 MB v1
     * payload — far beyond the configured receive cap.
     */
    public static boolean looksLikeDataTagBlob(byte[] assembled) {
        if (assembled == null || assembled.length < 5) {
            return false;
        }
        int declared = ((assembled[0] & 0xFF) << 24)
                | ((assembled[1] & 0xFF) << 16)
                | ((assembled[2] & 0xFF) << 8)
                | (assembled[3] & 0xFF);
        return declared == assembled.length - 4;
    }
}
