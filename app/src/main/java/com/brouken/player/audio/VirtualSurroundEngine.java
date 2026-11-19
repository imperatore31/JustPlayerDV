package com.brouken.player.audio;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Headphone virtual surround: plays every input channel through a virtual speaker in a virtual
 * room (binaural rendering) and mixes the result into two channels for headphones.
 *
 * <p>Channel layouts follow the Android / Media3 order for 1 to 8 channels:
 * <pre>
 * 1: C                      5: FL FR C BL BR
 * 2: FL FR                  6: FL FR C LFE BL BR        (5.1)
 * 3: FL FR C                7: FL FR C LFE BL BR BC     (6.1)
 * 4: FL FR BL BR            8: FL FR C LFE BL BR SL SR  (7.1)
 * </pre>
 * The surround channels of 5.1 are placed at +-110 degrees (ITU-R BS.775), those of 7.1 at +-100
 * (side) and +-145 (back) degrees.
 *
 * <p>Not thread safe, except {@link #setBypassed(boolean)}.
 */
public final class VirtualSurroundEngine {

    public static final int MAX_CHANNELS = 8;

    private static final float LIMITER_THRESHOLD = 0.98f;
    private static final double LIMITER_RELEASE_SECONDS = 0.1;
    private static final double LFE_CUTOFF = 120;
    /** LFE level relative to the direct sound of one speaker (+3 dB). */
    private static final double LFE_GAIN = 1.41;
    private static final float DRY_MULTICHANNEL_GAIN = 0.7071f;
    private static final float MINUS_3_DB = 0.7071f;

    private static final Map<String, float[][][]> CACHE = new LinkedHashMap<String, float[][][]>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[][][]> eldest) {
            return size() > 4;
        }
    };

    private final int channelCount;
    private final int sampleRate;
    private final RoomPreset preset;
    private final int blockSize;
    /** Input channel of each virtual speaker. */
    private final int[] speakerChannels;
    private final int lfeChannel;
    private final PartitionedConvolver convolver;
    private final float[][] convolverInput;
    private final float lfeGain;
    private final Biquad lfeFilter;
    private final float[] wetL;
    private final float[] wetR;
    private final float[] dryL;
    private final float[] dryR;
    private final float limiterRelease;

    private volatile boolean bypassRequested;
    private float wetGain = 1f;
    private boolean convolverStale;
    private float limiterEnvelope;

    public static boolean supportsChannelCount(int channelCount) {
        return channelCount >= 1 && channelCount <= MAX_CHANNELS;
    }

    /**
     * Builds the engine. Slow the first time for a combination of room, layout and sample rate
     * (the room responses are synthesised, around 0.1 - 0.5 s), fast afterwards (cached).
     */
    public VirtualSurroundEngine(HrirSet hrirs, RoomPreset preset, int channelCount, int sampleRate) {
        if (!supportsChannelCount(channelCount)) {
            throw new IllegalArgumentException("Unsupported channel count " + channelCount);
        }
        this.channelCount = channelCount;
        this.sampleRate = sampleRate;
        this.preset = preset;
        this.blockSize = blockSizeFor(sampleRate);

        final double[] azimuths = speakerAzimuths(channelCount);
        lfeChannel = lfeChannel(channelCount);
        speakerChannels = SPEAKER_CHANNELS[channelCount];

        final float[][][] responses = getResponses(hrirs, preset, azimuths, sampleRate);
        convolver = new PartitionedConvolver(responses, blockSize);
        convolverInput = new float[speakerChannels.length][];
        // LFE: relative to the level of the direct sound of one speaker
        lfeGain = (float) (LFE_GAIN / normalisationGain(responses, azimuths, sampleRate, true));
        lfeFilter = Biquad.lowPass(LFE_CUTOFF, sampleRate);
        wetL = new float[blockSize];
        wetR = new float[blockSize];
        dryL = new float[blockSize];
        dryR = new float[blockSize];
        limiterRelease = (float) Math.exp(-1.0 / (LIMITER_RELEASE_SECONDS * sampleRate));
    }

    /** Block size of the engine for a sample rate: about 20 ms. */
    public static int blockSizeFor(int sampleRate) {
        return sampleRate <= 50000 ? 1024 : sampleRate <= 100000 ? 2048 : 4096;
    }

    public int getBlockSize() {
        return blockSize;
    }

    public int getChannelCount() {
        return channelCount;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public RoomPreset getPreset() {
        return preset;
    }

    /**
     * Switches between virtual surround and a plain stereo downmix (for comparison, or when the
     * output is not headphones), with a short crossfade. Can be called from any thread.
     */
    public void setBypassed(boolean bypassed) {
        bypassRequested = bypassed;
    }

    public boolean isBypassed() {
        return bypassRequested;
    }

    /** Starts the next block from the plain downmix and crossfades into virtual surround. */
    public void fadeIn() {
        wetGain = 0f;
    }

    /** Forgets the past input (after a seek). */
    public void reset() {
        convolver.reset();
        lfeFilter.reset();
        convolverStale = false;
        limiterEnvelope = 0;
        wetGain = bypassRequested ? 0f : 1f;
    }

    /**
     * Processes one block of {@link #getBlockSize()} frames.
     *
     * @param in   [channel][frame]
     * @param outL left output, overwritten
     * @param outR right output, overwritten
     */
    public void process(float[][] in, float[] outL, float[] outR) {
        final int n = blockSize;
        final float wetTarget = bypassRequested ? 0f : 1f;
        final float wetStart = wetGain;
        final boolean needWet = wetStart > 0f || wetTarget > 0f;
        final boolean needDry = wetStart < 1f || wetTarget < 1f;

        if (needWet) {
            if (convolverStale) {
                convolver.reset();
                lfeFilter.reset();
                convolverStale = false;
            }
            for (int i = 0; i < speakerChannels.length; i++) {
                convolverInput[i] = in[speakerChannels[i]];
            }
            convolver.process(convolverInput, wetL, wetR);
            if (lfeChannel >= 0) {
                final float[] lfe = in[lfeChannel];
                for (int i = 0; i < n; i++) {
                    final float v = lfeGain * lfeFilter.process(lfe[i]);
                    wetL[i] += v;
                    wetR[i] += v;
                }
            }
        } else {
            convolverStale = true;
        }
        if (needDry) {
            downmix(channelCount, in, blockSize, dryL, dryR);
        }

        if (wetStart == wetTarget) {
            if (wetTarget == 1f) {
                System.arraycopy(wetL, 0, outL, 0, n);
                System.arraycopy(wetR, 0, outR, 0, n);
            } else {
                System.arraycopy(dryL, 0, outL, 0, n);
                System.arraycopy(dryR, 0, outR, 0, n);
            }
        } else {
            // Equal power crossfade over the block
            for (int i = 0; i < n; i++) {
                final float x = wetStart + (wetTarget - wetStart) * (i + 1) / n;
                final float gWet = (float) Math.sin(0.5 * Math.PI * x);
                final float gDry = (float) Math.cos(0.5 * Math.PI * x);
                outL[i] = gWet * wetL[i] + gDry * dryL[i];
                outR[i] = gWet * wetR[i] + gDry * dryR[i];
            }
            wetGain = wetTarget;
        }
        limit(outL, outR);
    }

    /**
     * Plain stereo downmix (ITU, without LFE): the output without virtual surround.
     *
     * @param in [channel][frame]
     */
    public static void downmix(int channelCount, float[][] in, int frames, float[] outL, float[] outR) {
        switch (channelCount) {
            case 1:
                System.arraycopy(in[0], 0, outL, 0, frames);
                System.arraycopy(in[0], 0, outR, 0, frames);
                return;
            case 2:
                System.arraycopy(in[0], 0, outL, 0, frames);
                System.arraycopy(in[1], 0, outR, 0, frames);
                return;
            default:
                break;
        }
        final int[] channels = SPEAKER_CHANNELS[channelCount];
        final double[] azimuths = SPEAKER_AZIMUTHS[channelCount];
        for (int i = 0; i < frames; i++) {
            float l = in[0][i];
            float r = in[1][i];
            for (int s = 2; s < channels.length; s++) {
                final float v = in[channels[s]][i];
                final double azimuth = azimuths[s];
                if (azimuth == 0 || azimuth == 180) {
                    l += MINUS_3_DB * v;
                    r += MINUS_3_DB * v;
                } else if (azimuth > 0) {
                    l += MINUS_3_DB * v;
                } else {
                    r += MINUS_3_DB * v;
                }
            }
            outL[i] = DRY_MULTICHANNEL_GAIN * l;
            outR[i] = DRY_MULTICHANNEL_GAIN * r;
        }
    }

    /** Peak limiter (instant attack, smooth release), linked on both channels. */
    private void limit(float[] left, float[] right) {
        float envelope = limiterEnvelope;
        final float release = limiterRelease;
        for (int i = 0; i < blockSize; i++) {
            final float peak = Math.max(Math.abs(left[i]), Math.abs(right[i]));
            envelope = peak > envelope ? peak : envelope * release;
            if (envelope > LIMITER_THRESHOLD) {
                final float gain = LIMITER_THRESHOLD / envelope;
                left[i] *= gain;
                right[i] *= gain;
            }
        }
        limiterEnvelope = envelope < 1e-9f ? 0f : envelope;
    }

    // ---- Layouts

    /** Speaker azimuths (degrees, counter-clockwise, 90 = left) by channel count, without LFE. */
    private static final double[][] SPEAKER_AZIMUTHS = {
            {},
            {0},
            {30, -30},
            {30, -30, 0},
            {30, -30, 110, -110},
            {30, -30, 0, 110, -110},
            {30, -30, 0, 110, -110},
            {30, -30, 0, 110, -110, 180},
            {30, -30, 0, 145, -145, 100, -100},
    };

    /** Input channel of each virtual speaker, by channel count (the LFE channel is skipped). */
    private static final int[][] SPEAKER_CHANNELS = new int[MAX_CHANNELS + 1][];

    static {
        for (int count = 1; count <= MAX_CHANNELS; count++) {
            final int lfe = lfeChannel(count);
            final int[] channels = new int[SPEAKER_AZIMUTHS[count].length];
            for (int i = 0, channel = 0; i < channels.length; i++, channel++) {
                if (channel == lfe) {
                    channel++;
                }
                channels[i] = channel;
            }
            SPEAKER_CHANNELS[count] = channels;
        }
    }

    private static int lfeChannel(int channelCount) {
        return channelCount >= 6 ? 3 : -1;
    }

    static double[] speakerAzimuths(int channelCount) {
        return SPEAKER_AZIMUTHS[channelCount].clone();
    }

    // ---- Room responses

    private static float[][][] getResponses(HrirSet hrirs, RoomPreset preset, double[] azimuths, int sampleRate) {
        final StringBuilder key = new StringBuilder(preset.key).append('/').append(sampleRate);
        for (double azimuth : azimuths) {
            key.append('/').append(azimuth);
        }
        synchronized (CACHE) {
            final float[][][] cached = CACHE.get(key.toString());
            if (cached != null) {
                return cached;
            }
        }
        final float[][][] responses = BrirGenerator.generate(hrirs, preset, azimuths);
        final int[] reference = referenceSpeakers(azimuths);
        int centre = -1;
        for (int i = 0; i < azimuths.length; i++) {
            if (azimuths[i] == 0) centre = i;
        }
        BrirGenerator.equalize(responses, reference[0], reference[1], centre, hrirs.sampleRate);
        final double gain = normalisationGain(responses, azimuths, hrirs.sampleRate, false);
        for (float[][] pair : responses) {
            for (int ear = 0; ear < 2; ear++) {
                float[] response = pair[ear];
                for (int i = 0; i < response.length; i++) {
                    response[i] *= (float) gain;
                }
                pair[ear] = Resampler.resample(response, hrirs.sampleRate, sampleRate);
            }
        }
        synchronized (CACHE) {
            CACHE.put(key.toString(), responses);
        }
        return responses;
    }

    /**
     * Gain that makes a centred (mono) source as loud as without processing, at middle frequencies:
     * the front speaker pair playing the same signal when there is one, the centre speaker otherwise.
     *
     * @param directOnly only the direct sound of one front speaker (reference for the LFE)
     */
    private static double normalisationGain(float[][][] responses, double[] azimuths, int sampleRate, boolean directOnly) {
        final int[] reference = referenceSpeakers(azimuths);
        final int a = reference[0];
        int b = reference[1];
        int length = responses[a][0].length;
        if (directOnly) {
            // First 2.5 ms: the direct sound
            length = Math.min(length, (int) (0.0025 * sampleRate) + 1);
            b = -1;
        }
        int fftSize = 1;
        while (fftSize < length) {
            fftSize <<= 1;
        }
        fftSize = Math.max(fftSize, 4096);
        final Fft fft = new Fft(fftSize);
        double energy = 0;
        double weights = 0;
        for (int ear = 0; ear < 2; ear++) {
            final float[] re = new float[fftSize];
            final float[] im = new float[fftSize];
            for (int i = 0; i < length; i++) {
                re[i] = responses[a][ear][i] + (b >= 0 ? responses[b][ear][i] : 0f);
            }
            fft.forward(re, im);
            for (int k = 1; k < fftSize / 2; k++) {
                final double f = (double) k * sampleRate / fftSize;
                if (f < 200 || f > 5000) {
                    continue;
                }
                // Equal weight per octave
                final double weight = 1 / f;
                energy += weight * (re[k] * re[k] + im[k] * im[k]);
                weights += weight;
            }
        }
        return energy > 0 ? Math.sqrt(weights / energy) : 1;
    }

    /** The front speaker pair (left, right), or the only speaker and -1. */
    private static int[] referenceSpeakers(double[] azimuths) {
        int a = -1;
        int b = -1;
        for (int i = 0; i < azimuths.length; i++) {
            if (azimuths[i] == 30) a = i;
            if (azimuths[i] == -30) b = i;
        }
        return a >= 0 && b >= 0 ? new int[]{a, b} : new int[]{0, -1};
    }

    /** Second order IIR filter (direct form I). */
    static final class Biquad {
        private final float b0, b1, b2, a1, a2;
        private float x1, x2, y1, y2;

        private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = (float) (b0 / a0);
            this.b1 = (float) (b1 / a0);
            this.b2 = (float) (b2 / a0);
            this.a1 = (float) (a1 / a0);
            this.a2 = (float) (a2 / a0);
        }

        /** Butterworth low-pass. */
        static Biquad lowPass(double cutoff, double sampleRate) {
            final double w = 2 * Math.PI * cutoff / sampleRate;
            final double alpha = Math.sin(w) / (2 * Math.sqrt(0.5));
            final double cos = Math.cos(w);
            return new Biquad((1 - cos) / 2, 1 - cos, (1 - cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
        }

        float process(float x) {
            final float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = Math.abs(y) < 1e-20f ? 0f : y;
            return y;
        }

        void reset() {
            x1 = x2 = y1 = y2 = 0;
        }
    }
}
