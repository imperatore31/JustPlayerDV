package com.brouken.player.audio;

/**
 * Convolves N input channels with N pairs of impulse responses (left ear, right ear) and sums the
 * results into two output channels: uniformly partitioned overlap-save convolution in the
 * frequency domain.
 *
 * <p>Works on blocks of {@link #getBlockSize()} frames. The output block holds the convolution
 * result for the same frames as the input block: the convolver adds no delay to the signal (the
 * caller only has to collect a whole block before processing it).
 *
 * <p>Two real signals share one complex FFT: input channels are transformed in pairs, and both
 * output channels come out of a single inverse FFT.
 */
final class PartitionedConvolver {

    private final int inputs;
    private final int blockSize;
    private final int fftSize;
    private final int bins;
    private final int partitions;
    private final Fft fft;

    /** Spectra of the impulse response partitions: [input][ear][partition * bins + bin]. */
    private final float[][][] irRe;
    private final float[][][] irIm;
    /** Frequency domain delay line: spectra of the last input blocks, [input][slot * bins + bin]. */
    private final float[][] fdlRe;
    private final float[][] fdlIm;
    /** Previous and current block of each input, the FFT input of overlap-save. */
    private final float[][] history;
    private int slot;

    private final float[] workRe;
    private final float[] workIm;
    private final float[] accLRe;
    private final float[] accLIm;
    private final float[] accRRe;
    private final float[] accRIm;

    /**
     * @param impulseResponses [input][ear: 0 left, 1 right][samples]; responses can differ in length
     * @param blockSize        block size in frames, a power of two
     */
    PartitionedConvolver(float[][][] impulseResponses, int blockSize) {
        this.inputs = impulseResponses.length;
        this.blockSize = blockSize;
        this.fftSize = 2 * blockSize;
        this.bins = blockSize + 1;
        int maxLength = 1;
        for (float[][] pair : impulseResponses) {
            maxLength = Math.max(maxLength, Math.max(pair[0].length, pair[1].length));
        }
        this.partitions = (maxLength + blockSize - 1) / blockSize;
        this.fft = new Fft(fftSize);
        workRe = new float[fftSize];
        workIm = new float[fftSize];
        accLRe = new float[bins];
        accLIm = new float[bins];
        accRRe = new float[bins];
        accRIm = new float[bins];

        irRe = new float[inputs][2][partitions * bins];
        irIm = new float[inputs][2][partitions * bins];
        for (int input = 0; input < inputs; input++) {
            final float[] left = impulseResponses[input][0];
            final float[] right = impulseResponses[input][1];
            for (int p = 0; p < partitions; p++) {
                // Both ears in one FFT: left as real part, right as imaginary part
                java.util.Arrays.fill(workRe, 0f);
                java.util.Arrays.fill(workIm, 0f);
                final int start = p * blockSize;
                for (int i = 0; i < blockSize; i++) {
                    if (start + i < left.length) workRe[i] = left[start + i];
                    if (start + i < right.length) workIm[i] = right[start + i];
                }
                fft.forward(workRe, workIm);
                split(workRe, workIm, irRe[input][0], irIm[input][0], irRe[input][1], irIm[input][1], p * bins);
            }
        }
        fdlRe = new float[inputs][partitions * bins];
        fdlIm = new float[inputs][partitions * bins];
        history = new float[inputs][fftSize];
    }

    int getBlockSize() {
        return blockSize;
    }

    int getInputCount() {
        return inputs;
    }

    /** Forgets all past input (after a seek). */
    void reset() {
        for (int input = 0; input < inputs; input++) {
            java.util.Arrays.fill(fdlRe[input], 0f);
            java.util.Arrays.fill(fdlIm[input], 0f);
            java.util.Arrays.fill(history[input], 0f);
        }
        slot = 0;
    }

    /**
     * Processes one block.
     *
     * @param in   [input][frame], blockSize frames per input
     * @param outL receives blockSize frames (overwritten)
     * @param outR receives blockSize frames (overwritten)
     */
    void process(float[][] in, float[] outL, float[] outR) {
        final int blockSize = this.blockSize;
        final int bins = this.bins;
        slot = slot == 0 ? partitions - 1 : slot - 1;
        final int slotOffset = slot * bins;

        // Input spectra, two inputs per FFT
        for (int input = 0; input < inputs; input += 2) {
            final boolean pair = input + 1 < inputs;
            final float[] historyA = history[input];
            System.arraycopy(historyA, blockSize, historyA, 0, blockSize);
            System.arraycopy(in[input], 0, historyA, blockSize, blockSize);
            System.arraycopy(historyA, 0, workRe, 0, fftSize);
            if (pair) {
                final float[] historyB = history[input + 1];
                System.arraycopy(historyB, blockSize, historyB, 0, blockSize);
                System.arraycopy(in[input + 1], 0, historyB, blockSize, blockSize);
                System.arraycopy(historyB, 0, workIm, 0, fftSize);
            } else {
                java.util.Arrays.fill(workIm, 0f);
            }
            fft.forward(workRe, workIm);
            if (pair) {
                split(workRe, workIm, fdlRe[input], fdlIm[input], fdlRe[input + 1], fdlIm[input + 1], slotOffset);
            } else {
                System.arraycopy(workRe, 0, fdlRe[input], slotOffset, bins);
                System.arraycopy(workIm, 0, fdlIm[input], slotOffset, bins);
            }
        }

        // Multiply-accumulate over all partitions
        java.util.Arrays.fill(accLRe, 0f);
        java.util.Arrays.fill(accLIm, 0f);
        java.util.Arrays.fill(accRRe, 0f);
        java.util.Arrays.fill(accRIm, 0f);
        for (int input = 0; input < inputs; input++) {
            final float[] xRe = fdlRe[input];
            final float[] xIm = fdlIm[input];
            final float[] hLRe = irRe[input][0];
            final float[] hLIm = irIm[input][0];
            final float[] hRRe = irRe[input][1];
            final float[] hRIm = irIm[input][1];
            int s = slot;
            for (int p = 0; p < partitions; p++) {
                final int xo = s * bins;
                final int ho = p * bins;
                for (int k = 0; k < bins; k++) {
                    final float xr = xRe[xo + k];
                    final float xi = xIm[xo + k];
                    final float lr = hLRe[ho + k];
                    final float li = hLIm[ho + k];
                    final float rr = hRRe[ho + k];
                    final float ri = hRIm[ho + k];
                    accLRe[k] += xr * lr - xi * li;
                    accLIm[k] += xr * li + xi * lr;
                    accRRe[k] += xr * rr - xi * ri;
                    accRIm[k] += xr * ri + xi * rr;
                }
                s = s + 1 == partitions ? 0 : s + 1;
            }
        }

        // Both ears in one inverse FFT: W = L + iR (L, R spectra of real signals)
        for (int k = 0; k < bins; k++) {
            workRe[k] = accLRe[k] - accRIm[k];
            workIm[k] = accLIm[k] + accRRe[k];
        }
        for (int k = 1; k < blockSize; k++) {
            workRe[fftSize - k] = accLRe[k] + accRIm[k];
            workIm[fftSize - k] = accRRe[k] - accLIm[k];
        }
        fft.inverse(workRe, workIm);
        System.arraycopy(workRe, blockSize, outL, 0, blockSize);
        System.arraycopy(workIm, blockSize, outR, 0, blockSize);
    }

    /**
     * Separates the spectra of two real signals a, b transformed together as a + ib, keeping bins
     * 0..n/2.
     */
    private void split(float[] zRe, float[] zIm, float[] aRe, float[] aIm, float[] bRe, float[] bIm, int offset) {
        final int n = fftSize;
        for (int k = 0; k < bins; k++) {
            final int m = k == 0 ? 0 : n - k;
            final float zr = zRe[k];
            final float zi = zIm[k];
            final float mr = zRe[m];
            final float mi = zIm[m];
            aRe[offset + k] = 0.5f * (zr + mr);
            aIm[offset + k] = 0.5f * (zi - mi);
            bRe[offset + k] = 0.5f * (zi + mi);
            bIm[offset + k] = 0.5f * (mr - zr);
        }
    }
}
