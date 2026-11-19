package com.brouken.player.audio;

import java.util.Random;

/**
 * Synthesises binaural room impulse responses (BRIR): what reaches the two ears of a listener when
 * a speaker plays an impulse in a virtual room.
 *
 * <ul>
 *   <li>Direct sound and early reflections: image source method in a rectangular room, every
 *       reflection filtered by the head-related response of its direction of arrival, attenuated
 *       by distance, wall absorption (more at high frequencies) and speaker directivity.</li>
 *   <li>Late reverberation: statistical tail with the same energy decay as the image sources,
 *       made of dense reflections from random directions (so the two ears get a realistically
 *       decorrelated, diffuse reverb).</li>
 * </ul>
 *
 * The responses start {@link HrirSet#margin} samples before the arrival of the direct sound at
 * the centre of the head, the same for every speaker.
 */
final class BrirGenerator {

    private static final double SPEED_OF_SOUND = 343.0;
    private static final double EAR_HEIGHT = 1.2;
    /** Crossfade between image sources and statistical tail, seconds. */
    private static final double TAIL_FADE = 0.010;
    /**
     * Window before the crossfade where the energy of the image sources sets the level of the
     * statistical tail, seconds.
     */
    private static final double CALIBRATION_WINDOW = 0.015;
    /** Length of the tail segments that share the same absorption filter, seconds. */
    private static final double TAIL_SEGMENT = 0.010;
    /** Highest density of the synthetic tail reflections, per second. */
    private static final double MAX_TAIL_DENSITY = 8000;
    /** One-pole low-pass coefficient (at 48 kHz) applied for each wall reflection. */
    private static final double WALL_DAMPING_48K = 0.1;
    /**
     * Absorption of each surface relative to the average (left, right, back, front, floor,
     * ceiling walls): a real room is not symmetric (windows, curtains, furniture). With a
     * symmetric room and a listener in the middle, the reflections reach both ears identically
     * and a centre speaker is heard inside the head.
     */
    private static final double[] WALL_ABSORPTION = {0.7, 1.3, 1.2, 0.9, 0.8, 1.1};
    /**
     * Below this frequency the room is left out: the bass of each speaker reaches the ears
     * directly (it carries no direction cues, and the image sources of a rectangular room would
     * add up to a boomy, uneven low end).
     */
    private static final double BASS_CROSSOVER = 150;
    /** Mean square directivity of the speaker model over all directions (diffuse field). */
    private static final double MEAN_SQUARE_DIRECTIVITY = 0.49 + 0.09 / 3;

    private BrirGenerator() {
    }

    /**
     * @param azimuths speaker azimuths, degrees counter-clockwise from the front (90 = left)
     * @return [speaker][ear: 0 left, 1 right][samples] at the sample rate of {@code hrirs}
     */
    static float[][][] generate(HrirSet hrirs, RoomPreset room, double[] azimuths) {
        final float[][][] out = new float[azimuths.length][][];
        for (int s = 0; s < azimuths.length; s++) {
            out[s] = generate(hrirs, room, azimuths[s], 0x5EEDL * 31 + room.ordinal() * 7919L + s);
        }
        return out;
    }

    static int length(HrirSet hrirs, RoomPreset room) {
        return (int) ((room.mixingTime + room.reverbTime) * hrirs.sampleRate) + hrirs.taps;
    }

    private static float[][] generate(HrirSet hrirs, RoomPreset room, double azimuthDegrees, long seed) {
        final double fs = hrirs.sampleRate;
        final double c = SPEED_OF_SOUND;
        final double width = room.width;
        final double depth = room.length;
        final double height = room.height;
        final double r0 = room.speakerDistance;
        final double volume = width * depth * height;
        final double surface = 2 * (width * depth + width * height + depth * height);
        // Eyring: uniform absorption giving the requested reverberation time
        final double absorption = 1 - Math.exp(-0.161 * volume / (surface * room.reverbTime));
        final double beta = Math.sqrt(1 - absorption);
        // Reflection coefficient of each wall, same average absorption
        final double[] areas = {depth * height, depth * height, width * height, width * height, width * depth, width * depth};
        double weighted = 0;
        for (int i = 0; i < 6; i++) {
            weighted += areas[i] * WALL_ABSORPTION[i];
        }
        final double[] wallBeta = new double[6];
        for (int i = 0; i < 6; i++) {
            final double wallAbsorption = Math.min(0.95, absorption * WALL_ABSORPTION[i] * surface / weighted);
            wallBeta[i] = Math.sqrt(1 - wallAbsorption);
        }
        final double damping = Math.pow(WALL_DAMPING_48K, 48000.0 / fs);

        // Listener near the middle of the room (left to right), facing the front wall (+y)
        final double lx = width / 2 - room.listenerOffset;
        final double ly = depth - room.frontWallDistance - r0 * Math.cos(Math.toRadians(30));
        final double lz = EAR_HEIGHT;
        final double az = Math.toRadians(azimuthDegrees);
        // Left is -x
        final double sx = lx - r0 * Math.sin(az);
        final double sy = ly + r0 * Math.cos(az);
        final double sz = lz;
        // Speaker axis: towards the listener
        final double ax = (lx - sx) / r0;
        final double ay = (ly - sy) / r0;

        final int length = length(hrirs, room);
        final int taps = hrirs.taps;
        final float[] left = new float[length];
        final float[] right = new float[length];

        // ---- Direct sound and early reflections (image sources), one buffer per reflection order
        final double mixing = room.mixingTime;
        final int earlyLength = (int) Math.ceil(mixing * fs) + taps + 1;
        final double maxDistance = r0 + c * mixing;
        final int nxMax = (int) Math.ceil(maxDistance / (2 * width)) + 1;
        final int nyMax = (int) Math.ceil(maxDistance / (2 * depth)) + 1;
        final int nzMax = (int) Math.ceil(maxDistance / (2 * height)) + 1;
        final int maxOrder = 2 * (nxMax + nyMax + nzMax) + 3;
        final float[][][] early = new float[maxOrder + 1][][];
        final int[] directPeak = new int[2];
        final double calibrationStart = mixing - TAIL_FADE - CALIBRATION_WINDOW;
        double calibrationEnergy = 0;

        for (int nx = -nxMax; nx <= nxMax; nx++) {
            for (int ny = -nyMax; ny <= nyMax; ny++) {
                for (int nz = -nzMax; nz <= nzMax; nz++) {
                    for (int u = 0; u <= 1; u++) {
                        for (int v = 0; v <= 1; v++) {
                            for (int w = 0; w <= 1; w++) {
                                final double ix = (1 - 2 * u) * sx + 2 * nx * width;
                                final double iy = (1 - 2 * v) * sy + 2 * ny * depth;
                                final double iz = (1 - 2 * w) * sz + 2 * nz * height;
                                final double dx = ix - lx;
                                final double dy = iy - ly;
                                final double dz = iz - lz;
                                final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                                final double delay = (distance - r0) / c;
                                if (delay >= mixing) {
                                    continue;
                                }
                                final int order = Math.abs(nx - u) + Math.abs(nx)
                                        + Math.abs(ny - v) + Math.abs(ny)
                                        + Math.abs(nz - w) + Math.abs(nz);
                                double gain = r0 / distance
                                        * Math.pow(wallBeta[0], Math.abs(nx - u)) * Math.pow(wallBeta[1], Math.abs(nx))
                                        * Math.pow(wallBeta[2], Math.abs(ny - v)) * Math.pow(wallBeta[3], Math.abs(ny))
                                        * Math.pow(wallBeta[4], Math.abs(nz - w)) * Math.pow(wallBeta[5], Math.abs(nz));
                                if (order > 0) {
                                    // Directivity: angle between the (mirrored) speaker axis and the
                                    // direction from the image source to the listener
                                    final double cos = ((1 - 2 * u) * ax * -dx + (1 - 2 * v) * ay * -dy) / distance;
                                    gain *= 0.7 + 0.3 * cos;
                                    gain *= room.reflectionGain;
                                }
                                if (delay >= calibrationStart && delay < mixing - TAIL_FADE) {
                                    calibrationEnergy += gain * gain;
                                }
                                // Crossfade into the statistical tail
                                gain *= Math.sqrt(1 - tailWeight(delay, mixing));
                                final int direction = hrirs.nearest(dy / distance, -dx / distance, dz / distance);
                                if (order == 0) {
                                    directPeak[0] = peakIndex(hrirs.leftEar(direction));
                                    directPeak[1] = peakIndex(hrirs.rightEar(direction));
                                }
                                if (early[order] == null) {
                                    early[order] = new float[2][earlyLength];
                                }
                                final int offset = (int) Math.round(delay * fs);
                                addScaled(early[order][0], offset, hrirs.leftEar(direction), (float) gain);
                                addScaled(early[order][1], offset, hrirs.rightEar(direction), (float) gain);
                            }
                        }
                    }
                }
            }
        }
        for (int order = 0; order <= maxOrder; order++) {
            if (early[order] == null) {
                continue;
            }
            for (int ear = 0; ear < 2; ear++) {
                final float[] buffer = early[order][ear];
                lowPass(buffer, damping, order);
                final float[] target = ear == 0 ? left : right;
                for (int i = 0; i < earlyLength && i < length; i++) {
                    target[i] += buffer[i];
                }
            }
        }

        // ---- Late reverberation
        final Random random = new Random(seed);
        final double meanFreePathTime = 4 * volume / (surface * c);
        // Energy per second of the reflections arriving at time t (after the direct sound):
        // statistical model (Eyring decay), at the level of the image sources before the crossfade.
        // The model alone underestimates the image sources of a rectangular room (the reflections
        // along its axes hit few walls), so its level is matched to them.
        double energyScale = MEAN_SQUARE_DIRECTIVITY * 4 * Math.PI * c * r0 * r0 / volume
                * room.reflectionGain * room.reflectionGain;
        // Model energy over a window as long as the calibration window, at the start of the
        // crossfade: the tail continues the level of the last image sources, then decays.
        final double modelEnergy = energyScale * CALIBRATION_WINDOW
                * Math.exp(2 * Math.log(beta) * (mixing - TAIL_FADE + r0 / c) / meanFreePathTime);
        if (calibrationEnergy > 0 && modelEnergy > 0) {
            energyScale *= calibrationEnergy / modelEnergy;
        }
        final double tailStart = mixing - TAIL_FADE;
        final double tailEnd = (double) (length - taps) / fs;
        final int segmentSamples = (int) Math.round(TAIL_SEGMENT * fs);
        final float[][] segment = new float[2][segmentSamples + taps + 1];
        double t = tailStart;
        int segmentIndex = -1;
        int segmentStart = 0;
        while (true) {
            final double absoluteTime = t + r0 / c;
            final double density = Math.min(MAX_TAIL_DENSITY,
                    4 * Math.PI * c * c * c * absoluteTime * absoluteTime / volume);
            t += -Math.log(1 - random.nextDouble()) / density;
            final int sample = (int) Math.round(t * fs);
            final int index = t < tailEnd ? (sample - (int) Math.round(tailStart * fs)) / segmentSamples : -2;
            if (index != segmentIndex) {
                if (segmentIndex >= 0) {
                    // Wall absorption for the average number of reflections in this segment
                    final double segmentMiddle = (segmentStart + segmentSamples / 2.0) / fs + r0 / c;
                    final int reflections = (int) Math.round(segmentMiddle / meanFreePathTime);
                    for (int ear = 0; ear < 2; ear++) {
                        lowPass(segment[ear], damping, reflections);
                        final float[] target = ear == 0 ? left : right;
                        for (int i = 0; i < segment[ear].length && segmentStart + i < length; i++) {
                            target[segmentStart + i] += segment[ear][i];
                        }
                        java.util.Arrays.fill(segment[ear], 0f);
                    }
                }
                if (index < 0) {
                    break;
                }
                segmentIndex = index;
                segmentStart = (int) Math.round(tailStart * fs) + index * segmentSamples;
            }
            final double energy = energyScale * Math.exp(2 * Math.log(beta) * (t + r0 / c) / meanFreePathTime);
            double gain = Math.sqrt(energy / density * tailWeight(t, mixing));
            if (random.nextBoolean()) {
                gain = -gain;
            }
            // Uniformly distributed direction
            final double up = 2 * random.nextDouble() - 1;
            final double angle = 2 * Math.PI * random.nextDouble();
            final double horizontal = Math.sqrt(1 - up * up);
            final int direction = hrirs.nearest(horizontal * Math.cos(angle), horizontal * Math.sin(angle), up);
            addScaled(segment[0], sample - segmentStart, hrirs.leftEar(direction), (float) gain);
            addScaled(segment[1], sample - segmentStart, hrirs.rightEar(direction), (float) gain);
        }

        // Fade out the end of the tail
        final int fadeLength = length / 4;
        for (int i = 0; i < fadeLength; i++) {
            final float f = (float) (0.5 + 0.5 * Math.cos(Math.PI * (i + 1) / fadeLength));
            left[length - fadeLength + i] *= f;
            right[length - fadeLength + i] *= f;
        }
        directBass(left, directPeak[0], fs);
        directBass(right, directPeak[1], fs);
        return new float[][]{left, right};
    }

    /**
     * Linkwitz-Riley crossover at {@link #BASS_CROSSOVER}: the response above, a plain impulse at
     * the arrival of the direct sound below (complementary filters: flat sum).
     */
    private static void directBass(float[] response, int directIndex, double fs) {
        final double[] highPass = butterworth(BASS_CROSSOVER, fs, true);
        final double[] lowPass = butterworth(BASS_CROSSOVER, fs, false);
        final double[] x = new double[response.length];
        for (int i = 0; i < x.length; i++) {
            x[i] = response[i];
        }
        filter(x, highPass);
        filter(x, highPass);
        final double[] bass = new double[response.length];
        bass[directIndex] = 1;
        filter(bass, lowPass);
        filter(bass, lowPass);
        for (int i = 0; i < x.length; i++) {
            response[i] = (float) (x[i] + bass[i]);
        }
    }

    /** Second order Butterworth coefficients {b0, b1, b2, a1, a2} (a0 = 1). */
    private static double[] butterworth(double cutoff, double fs, boolean highPass) {
        final double w = 2 * Math.PI * cutoff / fs;
        final double cos = Math.cos(w);
        final double alpha = Math.sin(w) / (2 * Math.sqrt(0.5));
        final double a0 = 1 + alpha;
        final double b0 = highPass ? (1 + cos) / 2 : (1 - cos) / 2;
        final double b1 = highPass ? -(1 + cos) : 1 - cos;
        return new double[]{b0 / a0, b1 / a0, b0 / a0, -2 * cos / a0, (1 - alpha) / a0};
    }

    private static void filter(double[] x, double[] c) {
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            final double y = c[0] * x[i] + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2;
            x2 = x1;
            x1 = x[i];
            y2 = y1;
            y1 = y;
            x[i] = y;
        }
    }

    private static int peakIndex(float[] x) {
        int best = 0;
        for (int i = 1; i < x.length; i++) {
            if (Math.abs(x[i]) > Math.abs(x[best])) {
                best = i;
            }
        }
        return best;
    }

    /** Correction of the voice region applied to the speaker that carries the dialogue, 0..1. */
    private static final double PRESENCE_CORRECTION = 0.7;
    private static final int EQUALIZER_TAPS = 4096;

    /**
     * Equalises the responses, in three steps:
     * <ol>
     *   <li>Low end of each speaker (below about 1 kHz, average of both ears) flattened to the
     *       level of its middle frequencies: the room makes dips and peaks of several dB that
     *       depend on the position (e.g. at 125 Hz, where the voices are).</li>
     *   <li>Bass shelf (below 200 - 400 Hz) on the front pair only, bringing the bass of the
     *       pair playing the same signal (sum, both ears) to the level of its middle frequencies,
     *       as with headphones: the pair adds up in phase in the bass (+6 dB), only partly above.</li>
     *   <li>Voice region (2 - 16 kHz) of the dialogue speaker (centre, or front pair) moved
     *       {@link #PRESENCE_CORRECTION} of the way towards flat: the pinna of the dummy head
     *       makes a broad dip at 5 - 10 kHz that sounds muffled with another head.</li>
     * </ol>
     * Step 3 applies the same correction to all speakers, so the differences between them
     * (direction cues) are kept.
     *
     * @param pairA    front left speaker (or the only speaker)
     * @param pairB    front right speaker, or -1
     * @param dialogue centre speaker, or -1 to use the front pair
     */
    static void equalize(float[][][] responses, int pairA, int pairB, int dialogue, int sampleRate) {
        int maxLength = 0;
        for (float[][] pair : responses) {
            maxLength = Math.max(maxLength, pair[0].length);
        }
        int n = 32768;
        while (n < maxLength + EQUALIZER_TAPS) {
            n <<= 1;
        }
        final Fft fft = new Fft(n);
        final double binHz = (double) sampleRate / n;
        final int bins = n / 2 + 1;

        // 1. Bass of each speaker
        for (int s = 0; s < responses.length; s++) {
            final double[] power = smoothedPower(fft, responses, s, -1);
            final double reference = bandAverage(power, binHz, 500, 2000);
            final double[] gainDb = new double[bins];
            for (int k = 0; k < bins; k++) {
                final double f = Math.max(k * binHz, 20);
                if (f < 1400) {
                    final int bin = (int) Math.round(f / binHz);
                    double g = clamp(10 * Math.log10(reference / Math.max(power[bin], 1e-20)), 6);
                    if (f > 700) {
                        g *= fade(f, 700, 1400);
                    }
                    gainDb[k] = g;
                }
            }
            final float[] filter = minimumPhase(fft, gainDb);
            for (int ear = 0; ear < 2; ear++) {
                responses[s][ear] = convolveTruncated(fft, responses[s][ear], filter);
            }
        }

        // 2. and 3., one filter for all speakers
        final double[] pairPower = smoothedPower(fft, responses, pairA, pairB);
        final double[] dialoguePower = dialogue >= 0 && dialogue != pairA
                ? smoothedPower(fft, responses, dialogue, -1) : pairPower;
        final double pairReference = bandAverage(pairPower, binHz, 500, 2000);
        final double dialogueReference = bandAverage(dialoguePower, binHz, 500, 2000);
        // Bass shelf: a pair playing the same bass adds up in phase (+6 dB) while the middle
        // frequencies add up only partly
        final double shelfDb = pairB >= 0
                ? clamp(10 * Math.log10(pairReference / bandAverage(pairPower, binHz, 40, 200)), 6) : 0;
        final double[] gainDb = new double[bins];
        final double[] pairGainDb = new double[bins];
        for (int k = 0; k < bins; k++) {
            final double f = Math.max(k * binHz, 20);
            final int bin = (int) Math.round(f / binHz);
            double g = 0;
            if (f > 1400 && f < 20000) {
                double presence = PRESENCE_CORRECTION
                        * clamp(10 * Math.log10(dialogueReference / Math.max(dialoguePower[bin], 1e-20)), 6);
                if (f < 2000) {
                    presence *= 1 - fade(f, 1400, 2000);
                } else if (f > 16000) {
                    presence *= fade(f, 16000, 20000);
                }
                g += presence;
            }
            gainDb[k] = g;
            pairGainDb[k] = g + shelfDb * fade(f, 200, 400);
        }
        final float[] filter = minimumPhase(fft, gainDb);
        final float[] pairFilter = minimumPhase(fft, pairGainDb);
        for (int s = 0; s < responses.length; s++) {
            final float[] h = s == pairA || s == pairB ? pairFilter : filter;
            for (int ear = 0; ear < 2; ear++) {
                responses[s][ear] = convolveTruncated(fft, responses[s][ear], h);
            }
        }
    }

    /** 1/3 octave smoothed power spectrum of responses a (+ b), averaged over both ears. */
    private static double[] smoothedPower(Fft fft, float[][][] responses, int a, int b) {
        final int n = fft.size();
        final double[] power = new double[n / 2 + 1];
        final float[] re = new float[n];
        final float[] im = new float[n];
        for (int ear = 0; ear < 2; ear++) {
            java.util.Arrays.fill(re, 0f);
            java.util.Arrays.fill(im, 0f);
            for (int i = 0; i < responses[a][ear].length; i++) {
                re[i] = responses[a][ear][i] + (b >= 0 ? responses[b][ear][i] : 0f);
            }
            fft.forward(re, im);
            for (int k = 0; k <= n / 2; k++) {
                power[k] += 0.5 * (re[k] * re[k] + im[k] * im[k]);
            }
        }
        final double[] prefix = new double[power.length + 1];
        for (int k = 0; k < power.length; k++) {
            prefix[k + 1] = prefix[k] + power[k];
        }
        final double[] smoothed = new double[power.length];
        smoothed[0] = power[0];
        for (int k = 1; k < power.length; k++) {
            final int lo = Math.max(1, (int) Math.floor(k * Math.pow(2, -1.0 / 6)));
            final int hi = Math.min(power.length - 1, (int) Math.ceil(k * Math.pow(2, 1.0 / 6)));
            smoothed[k] = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1);
        }
        return smoothed;
    }

    /** Average of a power spectrum between two frequencies, equal weight per octave. */
    private static double bandAverage(double[] power, double binHz, double from, double to) {
        double sum = 0;
        double weights = 0;
        for (int k = (int) (from / binHz); k <= (int) (to / binHz); k++) {
            sum += power[k] / k;
            weights += 1.0 / k;
        }
        return sum / weights;
    }

    private static double clamp(double db, double limit) {
        return Math.max(-limit, Math.min(limit, db));
    }

    /** 1 at {@code from}, 0 at {@code to}, raised cosine on a logarithmic frequency axis. */
    private static double fade(double f, double from, double to) {
        final double x = Math.log(f / from) / Math.log(to / from);
        return x <= 0 ? 1 : x >= 1 ? 0 : 0.5 + 0.5 * Math.cos(Math.PI * x);
    }

    /** Minimum phase filter ({@link #EQUALIZER_TAPS} taps) with the given gains (dB, bins 0..n/2). */
    private static float[] minimumPhase(Fft fft, double[] gainDb) {
        final int n = fft.size();
        final float[] logMagnitude = new float[n];
        for (int k = 0; k < gainDb.length; k++) {
            logMagnitude[k] = (float) (gainDb[k] / 20 * Math.log(10));
            if (k > 0 && k < n / 2) {
                logMagnitude[n - k] = logMagnitude[k];
            }
        }
        return minimumPhase(fft, logMagnitude, EQUALIZER_TAPS);
    }

    /** Minimum phase impulse response with the given log magnitude spectrum (real cepstrum method). */
    private static float[] minimumPhase(Fft fft, float[] logMagnitude, int length) {
        final int n = fft.size();
        final float[] re = logMagnitude.clone();
        final float[] im = new float[n];
        fft.inverse(re, im);
        // Fold the cepstrum: causal part doubled, anti-causal part removed
        for (int i = 1; i < n / 2; i++) {
            re[i] *= 2;
            re[n - i] = 0;
        }
        java.util.Arrays.fill(im, 0f);
        fft.forward(re, im);
        for (int k = 0; k < n; k++) {
            final double magnitude = Math.exp(re[k]);
            re[k] = (float) (magnitude * Math.cos(im[k]));
            im[k] = (float) (magnitude * Math.sin(im[k]));
        }
        fft.inverse(re, im);
        final float[] out = new float[length];
        final int fade = length / 4;
        for (int i = 0; i < length; i++) {
            final float w = i < length - fade ? 1f : (float) (0.5 + 0.5 * Math.cos(Math.PI * (i - (length - fade) + 1) / fade));
            out[i] = re[i] * w;
        }
        return out;
    }

    /** x convolved with h, truncated to the length of x. */
    private static float[] convolveTruncated(Fft fft, float[] x, float[] h) {
        final int n = fft.size();
        if (x.length + h.length - 1 > n) {
            throw new IllegalArgumentException("Response too long for the equalisation FFT");
        }
        final float[] xRe = new float[n];
        final float[] xIm = new float[n];
        // Both signals in one FFT: x real, h imaginary
        System.arraycopy(x, 0, xRe, 0, x.length);
        System.arraycopy(h, 0, xIm, 0, h.length);
        fft.forward(xRe, xIm);
        final float[] re = new float[n];
        final float[] im = new float[n];
        for (int k = 0; k < n; k++) {
            final int m = k == 0 ? 0 : n - k;
            final float ar = 0.5f * (xRe[k] + xRe[m]);
            final float ai = 0.5f * (xIm[k] - xIm[m]);
            final float br = 0.5f * (xIm[k] + xIm[m]);
            final float bi = 0.5f * (xRe[m] - xRe[k]);
            re[k] = ar * br - ai * bi;
            im[k] = ar * bi + ai * br;
        }
        fft.inverse(re, im);
        final float[] out = new float[x.length];
        System.arraycopy(re, 0, out, 0, x.length);
        return out;
    }

    /**
     * Energy weight of the statistical tail at time t (0 before the crossfade, 1 after the mixing
     * time); the image sources get the complement (equal power crossfade).
     */
    private static double tailWeight(double t, double mixing) {
        if (t <= mixing - TAIL_FADE) {
            return 0;
        }
        if (t >= mixing) {
            return 1;
        }
        final double x = (t - (mixing - TAIL_FADE)) / TAIL_FADE;
        return Math.sin(0.5 * Math.PI * x) * Math.sin(0.5 * Math.PI * x);
    }

    private static void addScaled(float[] target, int offset, float[] source, float gain) {
        final int end = Math.min(source.length, target.length - offset);
        for (int i = Math.max(0, -offset); i < end; i++) {
            target[offset + i] += gain * source[i];
        }
    }

    /** Applies a one-pole low-pass (unity gain at DC) {@code times} times. */
    private static void lowPass(float[] buffer, double coefficient, int times) {
        final float a = (float) coefficient;
        final float b = 1 - a;
        for (int k = 0; k < times; k++) {
            float state = 0;
            for (int i = 0; i < buffer.length; i++) {
                state = b * buffer[i] + a * state;
                buffer[i] = state;
            }
        }
    }
}
