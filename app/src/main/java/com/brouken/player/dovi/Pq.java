package com.brouken.player.dovi;

/** SMPTE ST 2084 (PQ) conversions, same constants as dovi_tool. */
final class Pq {

    private static final double Y_MAX = 10000.0;
    private static final double M1 = 2610.0 / 16384.0;
    private static final double M2 = (2523.0 / 4096.0) * 128.0;
    private static final double C1 = 3424.0 / 4096.0;
    private static final double C2 = (2413.0 / 4096.0) * 32.0;
    private static final double C3 = (2392.0 / 4096.0) * 32.0;

    private Pq() {
    }

    /** PQ signal (0..1) to luminance in cd/m². */
    static double toNits(double pq) {
        if (pq <= 0) {
            return 0;
        }
        final double xpow = Math.pow(pq, 1.0 / M2);
        final double num = Math.max(xpow - C1, 0.0);
        final double den = C2 - C3 * xpow;
        return Math.pow(num / den, 1.0 / M1) * Y_MAX;
    }

    /** Luminance in cd/m² to PQ signal (0..1). */
    static double fromNits(double nits) {
        final double y = Math.max(0, nits) / Y_MAX;
        final double ym1 = Math.pow(y, M1);
        return Math.pow((C1 + C2 * ym1) / (1.0 + C3 * ym1), M2);
    }

    /** 12 bit PQ code (as in Dolby Vision L1/L6 source fields) to cd/m². */
    static double code12ToNits(int code) {
        return toNits(code / 4095.0);
    }

    /** cd/m² to 12 bit PQ code, like dovi_tool's nits_to_pq_12_bit. */
    static int nitsToCode12(double nits) {
        return (int) Math.round(fromNits(nits) * 4095.0);
    }
}
