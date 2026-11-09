package com.brouken.player.dovi;

import java.util.Arrays;

/**
 * MSB-first bit writer into a growable byte array, with Exp-Golomb support.
 */
final class BitWriter {

    private byte[] buffer;
    private long position;

    BitWriter(int initialCapacity) {
        buffer = new byte[Math.max(16, initialCapacity)];
    }

    long bitLength() {
        return position;
    }

    boolean isByteAligned() {
        return (position & 7) == 0;
    }

    void writeBit(boolean bit) {
        final int byteIndex = (int) (position >>> 3);
        if (byteIndex >= buffer.length) {
            buffer = Arrays.copyOf(buffer, buffer.length * 2);
        }
        if (bit) {
            buffer[byteIndex] |= (byte) (0x80 >>> (int) (position & 7));
        }
        position++;
    }

    /** Writes the lowest {@code n} bits (0 to 64) of {@code value}, MSB first. */
    void writeBits(int n, long value) {
        for (int i = n - 1; i >= 0; i--) {
            writeBit(((value >>> i) & 1) == 1);
        }
    }

    void writeUe(long value) {
        final long codeNum = value + 1;
        final int length = 64 - Long.numberOfLeadingZeros(codeNum);
        writeBits(length - 1, 0);
        writeBits(length, codeNum);
    }

    void writeSe(long value) {
        writeUe(value > 0 ? 2 * value - 1 : -2 * value);
    }

    void byteAlign() {
        while (!isByteAligned()) {
            writeBit(false);
        }
    }

    void copyBits(BitReader reader, long count) throws DoviException {
        for (long i = 0; i < count; i++) {
            writeBit(reader.readBit());
        }
    }

    void writeBitString(BitString bits) {
        final byte[] data = bits.data;
        for (long i = 0; i < bits.bitLength; i++) {
            writeBit(((data[(int) (i >>> 3)] >> (7 - (int) (i & 7))) & 1) == 1);
        }
    }

    /** Returns the written bytes. The writer must be byte aligned. */
    byte[] toByteArray() {
        if (!isByteAligned()) {
            throw new IllegalStateException("BitWriter is not byte aligned");
        }
        return Arrays.copyOf(buffer, (int) (position >>> 3));
    }

    /** Returns a copy of the written bytes in the range [from, to). The writer must be byte aligned. */
    byte[] bytes(int from, int to) {
        if (!isByteAligned()) {
            throw new IllegalStateException("BitWriter is not byte aligned");
        }
        return Arrays.copyOfRange(buffer, from, to);
    }

    /** An immutable sequence of bits, used to carry opaque parts of an RPU verbatim. */
    static final class BitString {
        final byte[] data;
        final long bitLength;

        private BitString(byte[] data, long bitLength) {
            this.data = data;
            this.bitLength = bitLength;
        }

        static BitString read(BitReader reader, long bitCount) throws DoviException {
            final BitWriter writer = new BitWriter((int) Math.min(Integer.MAX_VALUE, bitCount / 8 + 1));
            writer.copyBits(reader, bitCount);
            final long length = writer.bitLength();
            writer.byteAlign();
            return new BitString(writer.toByteArray(), length);
        }
    }
}
