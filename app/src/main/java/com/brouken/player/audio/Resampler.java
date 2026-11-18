package com.brouken.player.audio;

/** Offline band-limited resampling (windowed sinc), used to bring impulse responses to the playback rate. */
final class Resampler {

    private static final int HALF_WIDTH = 24;
    private static final int TABLE_RESOLUTION = 512;
    private static final double KAISER_BETA = 8.6;

    private Resampler() {
    }

    static float[] resample(float[] in, int fromRate, int toRate) {
        if (fromRate == toRate) {
            return in.clone();
        }
        final double ratio = (double) toRate / fromRate;
        // Low-pass at 95 % of the lower Nyquist frequency, in input samples
        final double cutoff = Math.min(1.0, ratio) * 0.95;
        final int halfWidth = (int) Math.ceil(HALF_WIDTH / Math.min(1.0, ratio));
        final float[] table = new float[halfWidth * TABLE_RESOLUTION + 2];
        final double i0Beta = besselI0(KAISER_BETA);
        for (int i = 0; i < table.length; i++) {
            final double x = (double) i / TABLE_RESOLUTION;
            if (x >= halfWidth) {
                table[i] = 0f;
                continue;
            }
            final double sinc = x == 0 ? 1.0 : Math.sin(Math.PI * cutoff * x) / (Math.PI * cutoff * x);
            final double r = x / halfWidth;
            final double window = besselI0(KAISER_BETA * Math.sqrt(1 - r * r)) / i0Beta;
            table[i] = (float) (cutoff * sinc * window);
        }
        final int outLength = (int) Math.ceil(in.length * ratio);
        final float[] out = new float[outLength];
        final int divisor = gcd(fromRate, toRate);
        final int up = toRate / divisor;
        final int down = fromRate / divisor;
        if (up <= 4096) {
            // Rational ratio up/down: one precomputed set of filter taps per output phase
            final int taps = 2 * halfWidth;
            final float[] polyphase = new float[up * taps];
            for (int phase = 0; phase < up; phase++) {
                for (int t = 0; t < taps; t++) {
                    // Distance between the output position and input sample (center + halfWidth - t)
                    final double x = Math.abs((t - halfWidth) + (double) phase / up) * TABLE_RESOLUTION;
                    final int index = (int) x;
                    final double frac = x - index;
                    polyphase[phase * taps + t] = index + 1 < table.length
                            ? (float) (table[index] + frac * (table[index + 1] - table[index])) : 0f;
                }
            }
            for (int n = 0; n < outLength; n++) {
                final long position = (long) n * down;
                final int center = (int) (position / up);
                final int phase = (int) (position % up);
                final int base = phase * taps;
                float sum = 0;
                final int first = center + halfWidth;
                for (int t = 0; t < taps; t++) {
                    final int j = first - t;
                    if (j >= 0 && j < in.length) {
                        sum += in[j] * polyphase[base + t];
                    }
                }
                out[n] = sum;
            }
            return out;
        }
        for (int n = 0; n < outLength; n++) {
            final double position = n / ratio;
            final int center = (int) Math.floor(position);
            double sum = 0;
            for (int j = center - halfWidth + 1; j <= center + halfWidth; j++) {
                if (j < 0 || j >= in.length) {
                    continue;
                }
                final double x = Math.abs(position - j) * TABLE_RESOLUTION;
                final int index = (int) x;
                if (index + 1 >= table.length) {
                    continue;
                }
                final double frac = x - index;
                sum += in[j] * (table[index] + frac * (table[index + 1] - table[index]));
            }
            out[n] = (float) sum;
        }
        return out;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            final int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    private static double besselI0(double x) {
        double sum = 1;
        double term = 1;
        final double half = x / 2;
        for (int k = 1; k < 50; k++) {
            term *= (half / k) * (half / k);
            sum += term;
            if (term < 1e-12 * sum) {
                break;
            }
        }
        return sum;
    }
}
