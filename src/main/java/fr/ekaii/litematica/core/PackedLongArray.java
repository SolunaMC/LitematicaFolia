package fr.ekaii.litematica.core;

/**
 * Word-aligned packed-long-array codec, matching the Minecraft 1.16+ palette
 * encoding used by Litematica schematics. Each entry occupies
 * {@code bitsPerEntry} bits within a long, and no entry crosses a long
 * boundary — any unused trailing bits inside a long are zero-padded.
 *
 * <pre>
 *   bitsPerEntry   = max(ceil(log2(paletteSize)), 2)
 *   entriesPerLong = floor(64 / bitsPerEntry)
 *   longCount      = ceil(totalEntries / entriesPerLong)
 *
 *   long  = data[i / entriesPerLong]
 *   shift = (i % entriesPerLong) * bitsPerEntry
 *   value = (long >>> shift) &amp; ((1 &lt;&lt; bitsPerEntry) - 1)
 * </pre>
 */
public final class PackedLongArray {

    private PackedLongArray() {
    }

    /**
     * Recommended bit width for a palette of the given size, clamped to a
     * minimum of 2 bits (Litematica's invariant for non-trivial palettes).
     */
    public static int recommendedBitsPerEntry(int paletteSize) {
        if (paletteSize <= 0) return 2;
        int bits = 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        return Math.max(bits, 2);
    }

    /**
     * Number of longs required to pack {@code totalEntries} entries at
     * {@code bitsPerEntry} bits each, with word-aligned packing.
     */
    public static int requiredLongs(int totalEntries, int bitsPerEntry) {
        if (totalEntries == 0) return 0;
        int entriesPerLong = 64 / bitsPerEntry;
        return (totalEntries + entriesPerLong - 1) / entriesPerLong;
    }

    /** Decodes a packed long array back to per-cell palette indices. */
    public static int[] decode(long[] data, int bitsPerEntry, int totalEntries) {
        if (bitsPerEntry < 1 || bitsPerEntry > 32) {
            throw new IllegalArgumentException("bitsPerEntry out of range: " + bitsPerEntry);
        }
        if (totalEntries < 0) {
            throw new IllegalArgumentException("totalEntries < 0: " + totalEntries);
        }
        int[] out = new int[totalEntries];
        if (totalEntries == 0) return out;

        int entriesPerLong = 64 / bitsPerEntry;
        long mask = (1L << bitsPerEntry) - 1L;
        int expectedLongs = requiredLongs(totalEntries, bitsPerEntry);
        if (data.length < expectedLongs) {
            throw new IllegalArgumentException(
                    "Packed array too short: have " + data.length + " longs, need " + expectedLongs);
        }
        for (int i = 0; i < totalEntries; i++) {
            int longIdx = i / entriesPerLong;
            int shift = (i % entriesPerLong) * bitsPerEntry;
            out[i] = (int) ((data[longIdx] >>> shift) & mask);
        }
        return out;
    }

    /** Encodes per-cell palette indices into a word-aligned packed long array. */
    public static long[] encode(int[] entries, int bitsPerEntry) {
        if (bitsPerEntry < 1 || bitsPerEntry > 32) {
            throw new IllegalArgumentException("bitsPerEntry out of range: " + bitsPerEntry);
        }
        int total = entries.length;
        if (total == 0) return new long[0];

        int entriesPerLong = 64 / bitsPerEntry;
        long mask = (1L << bitsPerEntry) - 1L;
        long[] out = new long[requiredLongs(total, bitsPerEntry)];
        for (int i = 0; i < total; i++) {
            int v = entries[i];
            if (v < 0 || (long) v > mask) {
                throw new IllegalArgumentException(
                        "entry " + v + " at index " + i + " exceeds " + bitsPerEntry + " bits");
            }
            int longIdx = i / entriesPerLong;
            int shift = (i % entriesPerLong) * bitsPerEntry;
            out[longIdx] |= ((long) v & mask) << shift;
        }
        return out;
    }
}
