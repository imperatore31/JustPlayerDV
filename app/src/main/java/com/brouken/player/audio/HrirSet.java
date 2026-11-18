package com.brouken.player.audio;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Head-related impulse responses for a set of directions.
 *
 * <p>Loaded from the binary asset made by {@code tools/hrtf/make_hrir_asset.py}: MIT KEMAR
 * measurements (Gardner and Martin, MIT Media Lab, 1994), diffuse-field equalised, minimum phase
 * with the measured interaural delay. Only the left ear is stored; the right ear of azimuth a is
 * the left ear of azimuth -a (the measurements are symmetric).
 *
 * <p>Coordinates: azimuth in degrees counter-clockwise from the front (90 = left), elevation in
 * degrees (90 = above).
 */
final class HrirSet {

    static final String ASSET_NAME = "hrir_kemar.bin";

    final int sampleRate;
    final int taps;
    /** Samples before the arrival time at the centre of the head in every response. */
    final int margin;

    private final float[][] left;
    private final int[] mirror;
    private final float[] x;
    private final float[] y;
    private final float[] z;

    private HrirSet(int sampleRate, int taps, int margin, float[] azimuth, float[] elevation, float[][] left) {
        this.sampleRate = sampleRate;
        this.taps = taps;
        this.margin = margin;
        this.left = left;
        final int n = azimuth.length;
        x = new float[n];
        y = new float[n];
        z = new float[n];
        for (int i = 0; i < n; i++) {
            final double az = Math.toRadians(azimuth[i]);
            final double el = Math.toRadians(elevation[i]);
            // x: front, y: left, z: up
            x[i] = (float) (Math.cos(el) * Math.cos(az));
            y[i] = (float) (Math.cos(el) * Math.sin(az));
            z[i] = (float) Math.sin(el);
        }
        mirror = new int[n];
        for (int i = 0; i < n; i++) {
            mirror[i] = nearest(x[i], -y[i], z[i]);
        }
    }

    static HrirSet load(InputStream inputStream) throws IOException {
        final DataInputStream in = new DataInputStream(new BufferedInputStream(inputStream));
        final byte[] magic = new byte[4];
        in.readFully(magic);
        if (magic[0] != 'H' || magic[1] != 'R' || magic[2] != 'I' || magic[3] != 'R') {
            throw new IOException("Not an HRIR file");
        }
        final int version = in.readInt();
        if (version != 1) {
            throw new IOException("Unsupported HRIR file version " + version);
        }
        final int sampleRate = in.readInt();
        final int directions = in.readInt();
        final int taps = in.readInt();
        final float scale = in.readFloat();
        final int margin = in.readInt();
        final float[] azimuth = new float[directions];
        final float[] elevation = new float[directions];
        final float[][] left = new float[directions][taps];
        for (int d = 0; d < directions; d++) {
            azimuth[d] = in.readFloat();
            elevation[d] = in.readFloat();
            for (int i = 0; i < taps; i++) {
                left[d][i] = in.readShort() * scale;
            }
        }
        return new HrirSet(sampleRate, taps, margin, azimuth, elevation, left);
    }

    int size() {
        return left.length;
    }

    /** Index of the measured direction closest to the unit vector (front, left, up). */
    int nearest(double front, double leftward, double up) {
        int best = 0;
        double bestDot = -2;
        for (int i = 0; i < x.length; i++) {
            final double dot = x[i] * front + y[i] * leftward + z[i] * up;
            if (dot > bestDot) {
                bestDot = dot;
                best = i;
            }
        }
        return best;
    }

    int nearestAzimuthElevation(double azimuthDegrees, double elevationDegrees) {
        final double az = Math.toRadians(azimuthDegrees);
        final double el = Math.toRadians(elevationDegrees);
        return nearest(Math.cos(el) * Math.cos(az), Math.cos(el) * Math.sin(az), Math.sin(el));
    }

    /** Response of the left ear for direction {@code index}. Do not modify. */
    float[] leftEar(int index) {
        return left[index];
    }

    /** Response of the right ear for direction {@code index}. Do not modify. */
    float[] rightEar(int index) {
        return left[mirror[index]];
    }
}
