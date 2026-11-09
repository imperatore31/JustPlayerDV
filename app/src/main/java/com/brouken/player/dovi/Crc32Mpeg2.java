package com.brouken.player.dovi;

/** CRC-32/MPEG-2 (poly 0x04C11DB7, init 0xFFFFFFFF, no reflection, no final xor). */
final class Crc32Mpeg2 {

    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i << 24;
            for (int j = 0; j < 8; j++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
            }
            TABLE[i] = crc;
        }
    }

    private Crc32Mpeg2() {
    }

    static int compute(byte[] data, int offset, int length) {
        int crc = 0xFFFFFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc = (crc << 8) ^ TABLE[((crc >>> 24) ^ data[i]) & 0xFF];
        }
        return crc;
    }
}
