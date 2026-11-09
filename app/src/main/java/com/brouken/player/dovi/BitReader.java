package com.brouken.player.dovi;

/**
 * MSB-first bit reader over a byte array, with Exp-Golomb support.
 */
final class BitReader {

    private final byte[] data;
    private final long limitBits;
    private long position;

    BitReader(byte[] data, int offset, int length) {
        this.data = data;
        this.position = (long) offset * 8;
        this.limitBits = (long) (offset + length) * 8;
    }

    long bitsLeft() {
        return limitBits - position;
    }

    boolean isByteAligned() {
        return (position & 7) == 0;
    }

    boolean readBit() throws DoviException {
        if (position >= limitBits) {
            throw new DoviException("Unexpected end of RPU data");
        }
        final int bit = (data[(int) (position >>> 3)] >> (7 - (int) (position & 7))) & 1;
        position++;
        return bit == 1;
    }

    /** Reads {@code n} bits (0 to 64) as an unsigned value. */
    long readBits(int n) throws DoviException {
        if (n < 0 || n > 64) {
            throw new DoviException("Invalid bit count: " + n);
        }
        if (bitsLeft() < n) {
            throw new DoviException("Unexpected end of RPU data");
        }
        long value = 0;
        for (int i = 0; i < n; i++) {
            final int bit = (data[(int) (position >>> 3)] >> (7 - (int) (position & 7))) & 1;
            position++;
            value = (value << 1) | bit;
        }
        return value;
    }

    int readInt(int n) throws DoviException {
        return (int) readBits(n);
    }

    long readUe() throws DoviException {
        int leadingZeros = 0;
        while (!readBit()) {
            leadingZeros++;
            if (leadingZeros > 62) {
                throw new DoviException("Invalid Exp-Golomb code");
            }
        }
        if (leadingZeros == 0) {
            return 0;
        }
        return ((1L << leadingZeros) - 1) + readBits(leadingZeros);
    }

    long readSe() throws DoviException {
        final long codeNum = readUe();
        return (codeNum & 1) == 1 ? (codeNum + 1) / 2 : -(codeNum / 2);
    }
}
