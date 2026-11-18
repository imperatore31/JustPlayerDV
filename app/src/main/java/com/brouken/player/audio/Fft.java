package com.brouken.player.audio;

/**
 * In-place iterative radix-2 complex FFT on separate real and imaginary arrays.
 *
 * <p>The forward transform is unscaled, the inverse transform is scaled by 1/n, so that
 * {@code inverse(forward(x)) == x}.
 */
final class Fft {

    private final int n;
    private final int[] bitReversed;
    private final float[] cos;
    private final float[] sin;

    Fft(int n) {
        if (n < 2 || Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("FFT size must be a power of two: " + n);
        }
        this.n = n;
        final int bits = Integer.numberOfTrailingZeros(n);
        bitReversed = new int[n];
        for (int i = 0; i < n; i++) {
            bitReversed[i] = Integer.reverse(i) >>> (32 - bits);
        }
        cos = new float[n / 2];
        sin = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            final double angle = -2 * Math.PI * i / n;
            cos[i] = (float) Math.cos(angle);
            sin[i] = (float) Math.sin(angle);
        }
    }

    int size() {
        return n;
    }

    void forward(float[] re, float[] im) {
        transform(re, im, false);
    }

    void inverse(float[] re, float[] im) {
        transform(re, im, true);
        final float scale = 1f / n;
        for (int i = 0; i < n; i++) {
            re[i] *= scale;
            im[i] *= scale;
        }
    }

    private void transform(float[] re, float[] im, boolean inverse) {
        final int n = this.n;
        for (int i = 0; i < n; i++) {
            final int j = bitReversed[i];
            if (j > i) {
                float t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        final float sign = inverse ? -1f : 1f;
        for (int size = 2; size <= n; size <<= 1) {
            final int half = size >> 1;
            final int step = n / size;
            for (int start = 0; start < n; start += size) {
                int k = 0;
                for (int i = start; i < start + half; i++) {
                    final float wr = cos[k];
                    final float wi = sign * sin[k];
                    k += step;
                    final int j = i + half;
                    final float xr = re[j] * wr - im[j] * wi;
                    final float xi = re[j] * wi + im[j] * wr;
                    re[j] = re[i] - xr;
                    im[j] = im[i] - xi;
                    re[i] += xr;
                    im[i] += xi;
                }
            }
        }
    }
}
