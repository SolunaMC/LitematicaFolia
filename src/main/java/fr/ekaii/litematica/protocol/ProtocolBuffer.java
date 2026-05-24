package fr.ekaii.litematica.protocol;

import fr.ekaii.litematica.core.LitematicNbt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Minimal read/write helper that re-implements the parts of Minecraft's
 * {@code FriendlyByteBuf} (plus the Servux conventions on top) that we
 * need for the {@code servux:litematics} wire format.
 *
 * <p>Implemented from scratch so we don't depend on Netty in code paths
 * that just want to encode / decode a byte array. The Paper plugin
 * messaging API delivers payloads as {@code byte[]}, so a pure
 * {@code DataInputStream} / {@code DataOutputStream} backing is enough.
 *
 * <h2>Wire format reminders</h2>
 * <ul>
 *   <li><b>VarInt</b>: 7 bits per byte, MSB continuation flag. Used for
 *       packet type IDs, lengths and entity IDs.</li>
 *   <li><b>String</b>: VarInt(byte length) + UTF-8 bytes. Distinct from
 *       Java's {@code DataInput.readUTF} (which uses a fixed unsigned
 *       short prefix + modified UTF-8).</li>
 *   <li><b>BlockPos</b>: packed as a single signed long.
 *       {@code ((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF)}.
 *       This is the canonical Minecraft wire form.</li>
 *   <li><b>ChunkPos</b>: two consecutive ints (x, z).</li>
 *   <li><b>NBT</b> (1.20.2+): anonymous root — single byte tag id, then
 *       payload (no root name UTF). An end tag (0) means "empty / null".</li>
 * </ul>
 */
public final class ProtocolBuffer {

    private ProtocolBuffer() {
    }

    // ----------------------------------------------------------------- Reader

    /** Read-cursor over a byte array. Methods throw {@link IOException} on EOF. */
    public static final class Reader {
        private final byte[] data;
        private int pos;

        public Reader(byte[] data) {
            this.data = data;
        }

        public int remaining() { return data.length - pos; }

        public byte readByte() throws IOException {
            if (pos >= data.length) throw new EOFException("readByte at " + pos);
            return data[pos++];
        }

        public boolean readBoolean() throws IOException {
            return readByte() != 0;
        }

        public short readShort() throws IOException {
            int hi = readByte() & 0xFF;
            int lo = readByte() & 0xFF;
            return (short) ((hi << 8) | lo);
        }

        public int readInt() throws IOException {
            return ((readByte() & 0xFF) << 24)
                 | ((readByte() & 0xFF) << 16)
                 | ((readByte() & 0xFF) << 8)
                 |  (readByte() & 0xFF);
        }

        public long readLong() throws IOException {
            long h = (long) readInt() & 0xFFFFFFFFL;
            long l = (long) readInt() & 0xFFFFFFFFL;
            return (h << 32) | l;
        }

        public float readFloat() throws IOException {
            return Float.intBitsToFloat(readInt());
        }

        public double readDouble() throws IOException {
            return Double.longBitsToDouble(readLong());
        }

        /**
         * Reads a Minecraft-style VarInt (up to 5 bytes, 7 bits per byte,
         * MSB = continuation).
         */
        public int readVarInt() throws IOException {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                if (shift >= 35) throw new IOException("VarInt too long");
                b = readByte();
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            return value;
        }

        public String readString() throws IOException {
            int len = readVarInt();
            if (len < 0 || len > 1 << 20) {
                throw new IOException("Bad string length " + len);
            }
            if (pos + len > data.length) throw new EOFException("string body");
            String s = new String(data, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        /** Reads a Minecraft packed BlockPos (single long). */
        public int[] readBlockPos() throws IOException {
            long v = readLong();
            int x = (int) (v >> 38);
            int y = (int) (v << 52 >> 52);
            int z = (int) (v << 26 >> 38);
            return new int[] {x, y, z};
        }

        /** Reads a ChunkPos as {x, z}. */
        public int[] readChunkPos() throws IOException {
            int x = readInt();
            int z = readInt();
            return new int[] {x, z};
        }

        /**
         * Reads an anonymous-root NBT compound (1.20.2+ Minecraft wire
         * format). The header is a single tag-id byte; if it is
         * {@code TAG_END} the result is {@code null} ("no NBT"); otherwise
         * the payload of the indicated tag is read.
         *
         * <p>Note that the network format omits the root tag name, which is
         * the only deviation from the disk format produced by
         * {@link LitematicNbt#writeRaw}.
         */
        public LitematicNbt.NbtCompound readNbt() throws IOException {
            if (remaining() < 1) throw new EOFException("nbt header");
            byte id = data[pos];
            if (id == LitematicNbt.TAG_END) {
                pos++;
                return null;
            }
            if (id != LitematicNbt.TAG_COMPOUND) {
                throw new IOException("readNbt expected COMPOUND (10) but got " + id);
            }
            // Use DataInputStream over the remaining buffer; LitematicNbt
            // expects (id) + (utf name) + (payload). The Minecraft network
            // form is (id) + (payload) — no name. We forge a temporary
            // stream that prepends an empty UTF name.
            byte[] forged = new byte[remaining() + 2 /* empty UTF len */];
            forged[0] = id;
            forged[1] = 0;
            forged[2] = 0;
            System.arraycopy(data, pos + 1, forged, 3, remaining() - 1);
            try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(forged))) {
                long before = dis.available();
                LitematicNbt.NamedTag tag = LitematicNbt.readNamedTag(dis);
                long after = dis.available();
                long consumed = before - after;
                // consumed counts the (id) + (2-byte UTF len) + payload of
                // the forged stream. Translate back to bytes consumed in
                // our backing buffer (drop the 2 forged UTF length bytes).
                pos += (int) consumed - 2;
                if (!(tag.tag() instanceof LitematicNbt.NbtCompound c)) {
                    throw new IOException("readNbt root not a compound");
                }
                return c;
            }
        }

        public byte[] readRemainingBytes() {
            byte[] r = new byte[remaining()];
            System.arraycopy(data, pos, r, 0, r.length);
            pos = data.length;
            return r;
        }
    }

    // ----------------------------------------------------------------- Writer

    /**
     * Append-only byte writer. Backed by a {@link ByteArrayOutputStream}.
     * Call {@link #toByteArray()} when done.
     */
    public static final class Writer {
        private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        private final DataOutputStream dos = new DataOutputStream(baos);

        public Writer writeByte(int v) throws IOException {
            dos.writeByte(v);
            return this;
        }

        public Writer writeBoolean(boolean v) throws IOException {
            dos.writeBoolean(v);
            return this;
        }

        public Writer writeShort(int v) throws IOException {
            dos.writeShort(v);
            return this;
        }

        public Writer writeInt(int v) throws IOException {
            dos.writeInt(v);
            return this;
        }

        public Writer writeLong(long v) throws IOException {
            dos.writeLong(v);
            return this;
        }

        public Writer writeFloat(float v) throws IOException {
            dos.writeFloat(v);
            return this;
        }

        public Writer writeDouble(double v) throws IOException {
            dos.writeDouble(v);
            return this;
        }

        /** Writes a Minecraft VarInt (1-5 bytes). */
        public Writer writeVarInt(int value) throws IOException {
            // Treat as unsigned for shifting.
            while ((value & ~0x7F) != 0) {
                dos.writeByte((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            dos.writeByte(value & 0x7F);
            return this;
        }

        public Writer writeString(String s) throws IOException {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            writeVarInt(bytes.length);
            dos.write(bytes);
            return this;
        }

        /** Writes a Minecraft packed BlockPos (single long). */
        public Writer writeBlockPos(int x, int y, int z) throws IOException {
            long packed = ((long) (x & 0x3FFFFFF) << 38)
                        | ((long) (z & 0x3FFFFFF) << 12)
                        | (y & 0xFFF);
            writeLong(packed);
            return this;
        }

        /** Writes a ChunkPos (two ints). */
        public Writer writeChunkPos(int x, int z) throws IOException {
            writeInt(x);
            writeInt(z);
            return this;
        }

        /**
         * Writes an anonymous-root NBT compound (Minecraft 1.20.2+ network
         * form). {@code null} or empty compounds are written as a single
         * {@code TAG_END} byte.
         */
        public Writer writeNbt(LitematicNbt.NbtCompound c) throws IOException {
            if (c == null || c.size() == 0) {
                writeByte(LitematicNbt.TAG_END);
                return this;
            }
            // We need to write: TAG_COMPOUND(10) + payload (entries +
            // TAG_END). The simplest path is to write the named tag with
            // an empty name and then strip the 2 UTF-length bytes.
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            DataOutputStream named = new DataOutputStream(sink);
            LitematicNbt.writeNamedTag(named, new LitematicNbt.NamedTag("", c));
            named.flush();
            byte[] full = sink.toByteArray();
            // full = [id][utfLenHi][utfLenLo][...payload...]
            // We keep [id] and drop the two UTF-length bytes (which are 0).
            if (full.length < 3) throw new IOException("nbt encode underflow");
            dos.write(full, 0, 1);
            dos.write(full, 3, full.length - 3);
            return this;
        }

        public Writer writeRawBytes(byte[] bytes) throws IOException {
            dos.write(bytes);
            return this;
        }

        public byte[] toByteArray() throws IOException {
            dos.flush();
            return baos.toByteArray();
        }
    }
}
