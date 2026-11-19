package com.brouken.player.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

/** Tests of the headphone virtual surround engine (pure Java, runs on the JVM). */
public class VirtualSurroundEngineTest {

    private static HrirSet hrirs;

    @BeforeClass
    public static void loadHrirs() throws IOException {
        // Unit tests run in the module directory
        File file = new File("src/main/assets/" + HrirSet.ASSET_NAME);
        if (!file.exists()) {
            file = new File("app/src/main/assets/" + HrirSet.ASSET_NAME);
        }
        try (InputStream in = new FileInputStream(file)) {
            hrirs = HrirSet.load(in);
        }
    }

    @Test
    public void fftRoundTrip() {
        final Fft fft = new Fft(256);
        final Random random = new Random(3);
        final float[] re = new float[256];
        final float[] im = new float[256];
        final float[] original = new float[256];
        for (int i = 0; i < 256; i++) {
            original[i] = re[i] = (float) random.nextGaussian();
        }
        fft.forward(re, im);
        // Parseval
        double timeEnergy = 0;
        double frequencyEnergy = 0;
        for (int i = 0; i < 256; i++) {
            timeEnergy += original[i] * original[i];
            frequencyEnergy += re[i] * re[i] + im[i] * im[i];
        }
        assertEquals(timeEnergy, frequencyEnergy / 256, 1e-3 * timeEnergy);
        fft.inverse(re, im);
        for (int i = 0; i < 256; i++) {
            assertEquals(original[i], re[i], 1e-5);
            assertEquals(0, im[i], 1e-5);
        }
    }

    @Test
    public void convolverMatchesDirectConvolution() {
        final Random random = new Random(1);
        final int inputs = 3;
        final int blockSize = 64;
        final float[][][] responses = new float[inputs][2][];
        for (int i = 0; i < inputs; i++) {
            for (int ear = 0; ear < 2; ear++) {
                responses[i][ear] = new float[50 + random.nextInt(300)];
                for (int k = 0; k < responses[i][ear].length; k++) {
                    responses[i][ear][k] = (float) random.nextGaussian();
                }
            }
        }
        final PartitionedConvolver convolver = new PartitionedConvolver(responses, blockSize);
        final int blocks = 12;
        final int total = blocks * blockSize;
        final float[][] input = new float[inputs][total];
        for (float[] channel : input) {
            for (int k = 0; k < total; k++) {
                channel[k] = (float) random.nextGaussian();
            }
        }
        final float[][] output = new float[2][total];
        final float[][] block = new float[inputs][blockSize];
        final float[] left = new float[blockSize];
        final float[] right = new float[blockSize];
        for (int b = 0; b < blocks; b++) {
            for (int i = 0; i < inputs; i++) {
                System.arraycopy(input[i], b * blockSize, block[i], 0, blockSize);
            }
            convolver.process(block, left, right);
            System.arraycopy(left, 0, output[0], b * blockSize, blockSize);
            System.arraycopy(right, 0, output[1], b * blockSize, blockSize);
        }
        for (int ear = 0; ear < 2; ear++) {
            for (int n = 0; n < total; n++) {
                double expected = 0;
                for (int i = 0; i < inputs; i++) {
                    final float[] response = responses[i][ear];
                    for (int k = 0; k < response.length && k <= n; k++) {
                        expected += response[k] * input[i][n - k];
                    }
                }
                assertEquals("ear " + ear + " frame " + n, expected, output[ear][n], 1e-3);
            }
        }
    }

    @Test
    public void resamplerKeepsSine() {
        final int from = 48000;
        final int to = 44100;
        final float[] in = new float[4800];
        for (int i = 0; i < in.length; i++) {
            in[i] = (float) Math.sin(2 * Math.PI * 1000 * i / from);
        }
        final float[] out = Resampler.resample(in, from, to);
        assertEquals(4410, out.length);
        // Away from the edges
        for (int i = 200; i < out.length - 200; i++) {
            assertEquals(Math.sin(2 * Math.PI * 1000 * i / to), out[i], 2e-3);
        }
    }

    @Test
    public void hrirsAreSymmetricAndLateralised() {
        final int left = hrirs.nearestAzimuthElevation(90, 0);
        final int right = hrirs.nearestAzimuthElevation(-90, 0);
        // A source on the left is louder in the left ear
        assertTrue(energy(hrirs.leftEar(left)) > 4 * energy(hrirs.rightEar(left)));
        // Mirror image
        assertEquals(energy(hrirs.leftEar(left)), energy(hrirs.rightEar(right)), 1e-6);
    }

    @Test
    public void centredSourceKeepsItsLevel() {
        for (RoomPreset preset : RoomPreset.values()) {
            for (int channels : new int[]{1, 2}) {
                final VirtualSurroundEngine engine = new VirtualSurroundEngine(hrirs, preset, channels, 48000);
                final float[][] out = run(engine, bandNoise(channels, true, 48000 * 2, 0.1f));
                // Mono noise (0.1 rms) in the middle: about the same level at each ear (+-3 dB)
                final double rmsLeft = rms(out[0], 48000);
                final double rmsRight = rms(out[1], 48000);
                final String message = preset + " " + channels + " ch: " + rmsLeft + " / " + rmsRight;
                assertTrue(message, rmsLeft > 0.07 && rmsLeft < 0.141);
                assertTrue(message, rmsRight > 0.07 && rmsRight < 0.141);
            }
        }
    }

    @Test
    public void leftChannelIsLouderInLeftEar() {
        for (int channels : new int[]{2, 6, 8}) {
            final VirtualSurroundEngine engine = new VirtualSurroundEngine(hrirs, RoomPreset.LISTENING_ROOM, channels, 44100);
            final float[][] in = new float[channels][44100];
            final Random random = new Random(5);
            for (int i = 0; i < in[0].length; i++) {
                in[0][i] = (float) (0.1 * random.nextGaussian());
            }
            final float[][] out = run(engine, in);
            assertTrue(channels + " ch", rms(out[0], 0) > 1.5 * rms(out[1], 0));
        }
    }

    @Test
    public void bassIsFlatForACentredSource() {
        for (RoomPreset preset : RoomPreset.values()) {
            final VirtualSurroundEngine engine = new VirtualSurroundEngine(hrirs, preset, 2, 48000);
            final int block = engine.getBlockSize();
            final int blocks = 48;
            final float[] response = new float[block * blocks];
            final float[][] in = new float[2][block];
            final float[] left = new float[block];
            final float[] right = new float[block];
            for (int b = 0; b < blocks; b++) {
                in[0][0] = in[1][0] = b == 0 ? 0.25f : 0f;
                engine.process(in, left, right);
                for (int i = 0; i < block; i++) {
                    response[b * block + i] = 4 * left[i];
                }
            }
            final double reference = level(response, 1000);
            for (double frequency : new double[]{5, 10, 20, 31.5, 40, 50, 63, 80, 100, 125, 160, 200}) {
                final double db = level(response, frequency) - reference;
                assertTrue(preset + " " + frequency + " Hz: " + db + " dB", Math.abs(db) < 4);
            }
        }
    }

    /** Level in dB of a 1/3 octave band of an impulse response. */
    private static double level(float[] response, double frequency) {
        double sum = 0;
        int count = 0;
        for (double f = frequency * Math.pow(2, -1.0 / 6); f <= frequency * Math.pow(2, 1.0 / 6); f += frequency / 40) {
            double re = 0;
            double im = 0;
            for (int i = 0; i < response.length; i++) {
                final double phase = 2 * Math.PI * f * i / 48000;
                re += response[i] * Math.cos(phase);
                im -= response[i] * Math.sin(phase);
            }
            sum += re * re + im * im;
            count++;
        }
        return 10 * Math.log10(sum / count);
    }

    @Test
    public void dialogueBoostRaisesTheCentre() {
        // 5.1: centre channel only
        final float[][] centre = new float[6][];
        final float[][] noise = bandNoise(1, false, 48000, 0.05f);
        for (int c = 0; c < 6; c++) {
            centre[c] = c == 2 ? noise[0] : new float[48000];
        }
        assertEquals(2.0, boostRatio(6, centre, 6), 0.1);
        // Stereo: the same signal in both channels (centred voice)
        final float[][] stereo = {noise[0].clone(), noise[0].clone()};
        assertEquals(2.0, boostRatio(2, stereo, 6), 0.3);
        // Stereo: a side signal (left only) gets much less
        final float[][] side = {noise[0].clone(), new float[48000]};
        assertTrue(boostRatio(2, side, 6) < 1.5);
    }

    /** Output level with a dialogue boost relative to without (rms, after the first block). */
    private static double boostRatio(int channels, float[][] in, float decibels) {
        final VirtualSurroundEngine plain = new VirtualSurroundEngine(hrirs, RoomPreset.LISTENING_ROOM, channels, 48000);
        final VirtualSurroundEngine boosted = new VirtualSurroundEngine(hrirs, RoomPreset.LISTENING_ROOM, channels, 48000);
        boosted.setDialogueBoost(decibels);
        boosted.reset();
        final int skip = plain.getBlockSize();
        return rms(run(boosted, in)[0], skip) / rms(run(plain, in)[0], skip);
    }

    @Test
    public void loudInputIsLimited() {
        final VirtualSurroundEngine engine = new VirtualSurroundEngine(hrirs, RoomPreset.HALL, 6, 48000);
        final float[][] in = new float[6][48000];
        for (int c = 0; c < 6; c++) {
            for (int i = 0; i < in[c].length; i++) {
                in[c][i] = (i / 50) % 2 == 0 ? 1f : -1f;
            }
        }
        final float[][] out = run(engine, in);
        for (float[] channel : out) {
            for (float v : channel) {
                assertTrue(Float.isFinite(v));
                assertTrue("sample " + v, Math.abs(v) <= 1f);
            }
        }
    }

    @Test
    public void bypassGivesTheOriginalStereo() {
        final VirtualSurroundEngine engine = new VirtualSurroundEngine(hrirs, RoomPreset.STUDIO, 2, 48000);
        engine.setBypassed(true);
        engine.reset();
        final float[][] in = bandNoise(2, false, engine.getBlockSize() * 4, 0.1f);
        final float[][] out = run(engine, in);
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < in[c].length; i++) {
                assertEquals(in[c][i], out[c][i], 1e-6);
            }
        }
    }

    @Test
    public void bypassSwitchIsSmooth() {
        final VirtualSurroundEngine engine = new VirtualSurroundEngine(hrirs, RoomPreset.LISTENING_ROOM, 2, 48000);
        final int block = engine.getBlockSize();
        final float[][] in = new float[2][block];
        final float[] left = new float[block];
        final float[] right = new float[block];
        final float[] previous = new float[1];
        boolean first = true;
        for (int b = 0; b < 40; b++) {
            for (int i = 0; i < block; i++) {
                // Low frequency sine: no step can hide in it
                in[0][i] = in[1][i] = (float) (0.2 * Math.sin(2 * Math.PI * 100 * (b * block + i) / 48000.0));
            }
            if (b == 20) engine.setBypassed(true);
            if (b == 30) engine.setBypassed(false);
            engine.process(in, left, right);
            for (int i = 0; i < block; i++) {
                if (!first) {
                    assertTrue("jump at block " + b, Math.abs(left[i] - previous[0]) < 0.05);
                }
                previous[0] = left[i];
                first = false;
            }
        }
    }

    // ---- helpers

    private static float[][] run(VirtualSurroundEngine engine, float[][] in) {
        final int block = engine.getBlockSize();
        final int channels = in.length;
        final int frames = in[0].length / block * block;
        final float[][] out = new float[2][frames];
        final float[][] blockIn = new float[channels][block];
        final float[] left = new float[block];
        final float[] right = new float[block];
        for (int start = 0; start < frames; start += block) {
            for (int c = 0; c < channels; c++) {
                System.arraycopy(in[c], start, blockIn[c], 0, block);
            }
            engine.process(blockIn, left, right);
            System.arraycopy(left, 0, out[0], start, block);
            System.arraycopy(right, 0, out[1], start, block);
        }
        return out;
    }

    /** Noise band-limited to about 300 Hz - 4 kHz, the same in all channels when {@code same}. */
    private static float[][] bandNoise(int channels, boolean same, int frames, float rms) {
        final Random random = new Random(7);
        final float[][] out = new float[channels][frames];
        for (int c = 0; c < channels; c++) {
            if (same && c > 0) {
                out[c] = out[0].clone();
                continue;
            }
            double low1 = 0, low2 = 0;
            for (int i = 0; i < frames; i++) {
                final double x = random.nextGaussian();
                low1 += 0.4 * (x - low1);      // ~4 kHz low-pass
                low2 += 0.04 * (low1 - low2);  // ~300 Hz low-pass, subtracted: band-pass
                out[c][i] = (float) (low1 - low2);
            }
            final double actual = rms(out[c], 0);
            for (int i = 0; i < frames; i++) {
                out[c][i] *= (float) (rms / actual);
            }
        }
        return out;
    }

    private static double rms(float[] x, int from) {
        double sum = 0;
        for (int i = from; i < x.length; i++) {
            sum += x[i] * x[i];
        }
        return Math.sqrt(sum / (x.length - from));
    }

    private static double energy(float[] x) {
        double sum = 0;
        for (float v : x) {
            sum += v * v;
        }
        return sum;
    }
}
