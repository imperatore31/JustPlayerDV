package com.brouken.player.audio;

import java.util.Locale;

/**
 * A virtual listening room, from the few values a listener can tune by ear: a rectangular room
 * with the speakers on a circle around the listener, pointed at the listener.
 *
 * <p>The room geometry follows from the speaker distance and the size factor: the listener sits
 * in the middle of the room, slightly off-centre towards the left wall (a symmetric position puts
 * a centre speaker inside the head).
 */
public final class RoomParams {

    // ---- Tunable values, with their ranges

    public static final double MIN_DISTANCE = 1.5, MAX_DISTANCE = 5.0;
    public static final double MIN_SIZE = 1.0, MAX_SIZE = 3.0;
    public static final double MIN_REVERB = 0.15, MAX_REVERB = 1.0;
    public static final double MIN_ROOM_LEVEL = -9, MAX_ROOM_LEVEL = 6;
    public static final double MIN_FRONT_WIDTH = 20, MAX_FRONT_WIDTH = 45;
    public static final double MIN_BRIGHTNESS = -4, MAX_BRIGHTNESS = 4;
    /** Screen heights available: elevations of the front speakers measured in the HRTF set. */
    public static final double[] SCREEN_HEIGHTS = {0, 10, 20};

    /** Distance of the speakers from the listener, metres. */
    public final double distance;
    /** Room size, 1 = just large enough for the speakers. */
    public final double size;
    /** Reverberation time (-60 dB) at middle frequencies, seconds. */
    public final double reverbTime;
    /** Level of all reflections relative to the physical model, dB. */
    public final double roomLevelDb;
    /** Azimuth of the front left and right speakers, degrees (standard: 30). */
    public final double frontWidth;
    /** High frequency tilt (above 2 kHz, full at 8 kHz), dB. */
    public final double brightnessDb;
    /** Elevation of the front speakers (left, centre, right), degrees. */
    public final double screenHeight;

    // ---- Derived geometry

    /** Room size in metres: x (left to right), y (back to front), z (floor to ceiling). */
    final double width;
    final double length;
    final double height;
    /** Listener position, metres. */
    final double listenerX;
    final double listenerY;
    /** Reflections arriving later than this after the direct sound make the statistical tail, seconds. */
    final double mixingTime;
    final double reflectionGain;

    public RoomParams(double distance, double size, double reverbTime, double roomLevelDb,
                      double frontWidth, double brightnessDb, double screenHeight) {
        this.distance = clamp(distance, MIN_DISTANCE, MAX_DISTANCE);
        this.size = clamp(size, MIN_SIZE, MAX_SIZE);
        this.reverbTime = clamp(reverbTime, MIN_REVERB, MAX_REVERB);
        this.roomLevelDb = clamp(roomLevelDb, MIN_ROOM_LEVEL, MAX_ROOM_LEVEL);
        this.frontWidth = clamp(frontWidth, MIN_FRONT_WIDTH, MAX_FRONT_WIDTH);
        this.brightnessDb = clamp(brightnessDb, MIN_BRIGHTNESS, MAX_BRIGHTNESS);
        this.screenHeight = clamp(screenHeight, SCREEN_HEIGHTS[0], SCREEN_HEIGHTS[SCREEN_HEIGHTS.length - 1]);

        final double d = this.distance;
        final double k = this.size;
        // Large enough for side speakers (+-100 degrees) and back speakers (+-145 degrees)
        width = (2 * d + 1.4) * k;
        length = (1.82 * d + 2.4) * k;
        height = 2.6 + 2.4 * (k - 1) + Math.max(0, d * Math.sin(Math.toRadians(this.screenHeight)) - 0.8);
        listenerX = width / 2 - 0.065 * width;
        listenerY = length / 2;
        mixingTime = clamp(0.035 + 0.025 * (k - 1) + 0.006 * (d - 2), 0.03, 0.1);
        reflectionGain = Math.pow(10, this.roomLevelDb / 20);
    }

    public RoomParams withDistance(double value) {
        return new RoomParams(value, size, reverbTime, roomLevelDb, frontWidth, brightnessDb, screenHeight);
    }

    public RoomParams withSize(double value) {
        return new RoomParams(distance, value, reverbTime, roomLevelDb, frontWidth, brightnessDb, screenHeight);
    }

    public RoomParams withReverbTime(double value) {
        return new RoomParams(distance, size, value, roomLevelDb, frontWidth, brightnessDb, screenHeight);
    }

    public RoomParams withRoomLevel(double value) {
        return new RoomParams(distance, size, reverbTime, value, frontWidth, brightnessDb, screenHeight);
    }

    public RoomParams withFrontWidth(double value) {
        return new RoomParams(distance, size, reverbTime, roomLevelDb, value, brightnessDb, screenHeight);
    }

    public RoomParams withBrightness(double value) {
        return new RoomParams(distance, size, reverbTime, roomLevelDb, frontWidth, value, screenHeight);
    }

    public RoomParams withScreenHeight(double value) {
        return new RoomParams(distance, size, reverbTime, roomLevelDb, frontWidth, brightnessDb, value);
    }

    /** Room dimensions for display, e.g. "6 × 7 m". */
    public String describeSize() {
        return String.format(Locale.ROOT, "%.0f × %.0f × %.1f m", width, length, height);
    }

    /** Identifies the room responses (cache key). */
    String key() {
        return String.format(Locale.ROOT, "%.3f/%.3f/%.3f/%.2f/%.1f/%.2f/%.1f",
                distance, size, reverbTime, roomLevelDb, frontWidth, brightnessDb, screenHeight);
    }

    /** Stored form (preferences), see {@link #parse(String)}. */
    public String serialize() {
        return key();
    }

    /** @return the room stored with {@link #serialize()}, or {@code fallback} */
    public static RoomParams parse(String value, RoomParams fallback) {
        if (value == null) {
            return fallback;
        }
        final String[] parts = value.split("/");
        if (parts.length != 7) {
            return fallback;
        }
        try {
            return new RoomParams(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]), Double.parseDouble(parts[3]), Double.parseDouble(parts[4]),
                    Double.parseDouble(parts[5]), Double.parseDouble(parts[6]));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RoomParams && ((RoomParams) o).key().equals(key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }

    @Override
    public String toString() {
        return key();
    }

    private static double clamp(double value, double min, double max) {
        return Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }
}
