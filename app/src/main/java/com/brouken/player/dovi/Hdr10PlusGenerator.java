package com.brouken.player.dovi;

/**
 * Builds HDR10+ (profile A, application version 1) metadata from Dolby Vision metadata.
 *
 * <p>Dolby Vision L1 gives, for each shot, the minimum, average and maximum of maxRGB as 12 bit PQ
 * codes. The HDR10+ values are derived as the inverse of dovi_tool's HDR10+ to Dolby Vision
 * mapping (avg_pq = PQ(average_maxrgb), max_pq = PQ(max MaxScl)). HDR10+ also needs a maxRGB
 * distribution that Dolby Vision doesn't carry: it is estimated with a simple model in the PQ
 * domain (median at the L1 average, highlights between the average and the maximum). The model
 * coefficients below are the knobs to tune by watching real content.
 *
 * <p>In HDR10+ version 1 metadata two of the nine distribution slots have a special meaning
 * (HDR10+ LLC specification, as quoted by the hdr10plus_tool author): the 5% slot carries
 * DistributionY99, the highest 99.99th percentile of the frames' luminance in the scene, and the
 * 10% slot carries DistributionY100nit, the percentage (0 to 100) of pixels at or below 100 cd/m².
 */
final class Hdr10PlusGenerator {

    // Fractions of (avg - min) below the median, of (max - avg) above it, in the PQ domain.
    static final double P1 = 0.05;
    static final double P25 = 0.55;
    static final double P75 = 0.25;
    static final double P90 = 0.47;
    static final double P95 = 0.62;
    static final double P99_98 = 0.97;
    static final double Y99 = 0.95;

    private static final double PQ_100_NITS = Pq.fromNits(100);

    private Hdr10PlusGenerator() {
    }

    /** From Dolby Vision L1 (12 bit PQ codes). */
    static Hdr10PlusMetadata fromLevel1(int minPqCode, int maxPqCode, int avgPqCode) {
        final double max = clamp01(maxPqCode / 4095.0);
        final double avg = Math.min(clamp01(avgPqCode / 4095.0), max);
        final double min = Math.min(clamp01(minPqCode / 4095.0), avg);

        final Hdr10PlusMetadata m = new Hdr10PlusMetadata();
        m.applicationVersion = 1;
        m.numWindows = 1;
        m.targetedSystemDisplayMaximumLuminance = 0; // profile A: no Bezier curve
        m.toneMappingFlag = false;

        final long maxValue = tenthsOfNits(max);
        m.maxscl[0] = maxValue;
        m.maxscl[1] = maxValue;
        m.maxscl[2] = maxValue;
        m.averageMaxrgb = tenthsOfNits(avg);

        // Model of the maxRGB distribution: (percentage, PQ value) points.
        final double[] percent = {0, 1, 25, 50, 75, 90, 95, 99.98, 100};
        final double[] value = {
                min,
                min + P1 * (avg - min),
                min + P25 * (avg - min),
                avg,
                avg + P75 * (max - avg),
                avg + P90 * (max - avg),
                avg + P95 * (max - avg),
                avg + P99_98 * (max - avg),
                max,
        };

        m.distributionPercentages = Hdr10PlusMetadata.DISTRIBUTION_INDEXES_9.clone();
        m.distributionValues = new long[]{
                tenthsOfNits(value[1]), // 1%
                tenthsOfNits(avg + Y99 * (max - avg)), // 5% slot: DistributionY99
                Math.round(percentAtOrBelow(percent, value, PQ_100_NITS)), // 10% slot: DistributionY100nit
                tenthsOfNits(value[2]), // 25%
                tenthsOfNits(value[3]), // 50%
                tenthsOfNits(value[4]), // 75%
                tenthsOfNits(value[5]), // 90%
                tenthsOfNits(value[6]), // 95%
                tenthsOfNits(value[7]), // 99 (99.98%)
        };
        m.fractionBrightPixels = 0;
        return m;
    }

    /** Fallback from static metadata (Dolby Vision L6 MaxCLL/MaxFALL, in cd/m²). */
    static Hdr10PlusMetadata fromMaxCll(int maxCll, int maxFall) {
        return fromLevel1(0, Pq.nitsToCode12(maxCll), Pq.nitsToCode12(Math.min(maxFall, maxCll)));
    }

    private static long tenthsOfNits(double pq) {
        return Math.min(100_000L, Math.round(Pq.toNits(pq) * 10.0));
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Percentage of the modelled distribution at or below the given PQ value. */
    static double percentAtOrBelow(double[] percent, double[] value, double pq) {
        if (pq < value[0]) {
            return 0;
        }
        for (int i = value.length - 1; i > 0; i--) {
            if (pq >= value[i]) {
                return percent[i];
            }
            if (pq >= value[i - 1]) {
                final double span = value[i] - value[i - 1];
                final double t = span <= 0 ? 1 : (pq - value[i - 1]) / span;
                return percent[i - 1] + t * (percent[i] - percent[i - 1]);
            }
        }
        return percent[0];
    }
}
