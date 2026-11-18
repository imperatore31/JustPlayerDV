package com.brouken.player.audio;

/**
 * Virtual listening rooms: a rectangular room with the speakers on a circle around the listener,
 * all at ear height and pointed at the listener.
 */
public enum RoomPreset {

    /** Small treated control room: speakers close, short and quiet reverb. */
    STUDIO("studio", 4.6, 5.6, 2.7, 0.22, 1.8, 1.0, 0.040, 1.0),
    /** Home theatre / listening room: the default. */
    LISTENING_ROOM("room", 5.8, 7.0, 2.9, 0.30, 2.3, 1.2, 0.060, 1.0),
    /** Large hall with distant speakers. */
    HALL("hall", 9.0, 12.0, 4.5, 0.55, 3.5, 1.8, 0.090, 1.0);

    /** Value stored in the preferences. */
    public final String key;
    /** Room size in metres: x (left to right), y (back to front), z (floor to ceiling). */
    final double width;
    final double length;
    final double height;
    /** Reverberation time (-60 dB) at middle frequencies, seconds. */
    final double reverbTime;
    /** Distance of the speakers from the listener, metres. */
    final double speakerDistance;
    /** Distance of the front speakers from the front wall, metres. */
    final double frontWallDistance;
    /** Reflections arriving later than this after the direct sound make the statistical tail, seconds. */
    final double mixingTime;
    /** Level of all reflections relative to the physical model (1 = physical). */
    final double reflectionGain;

    RoomPreset(String key, double width, double length, double height, double reverbTime,
               double speakerDistance, double frontWallDistance, double mixingTime, double reflectionGain) {
        this.key = key;
        this.width = width;
        this.length = length;
        this.height = height;
        this.reverbTime = reverbTime;
        this.speakerDistance = speakerDistance;
        this.frontWallDistance = frontWallDistance;
        this.mixingTime = mixingTime;
        this.reflectionGain = reflectionGain;
    }

    public static RoomPreset fromKey(String key) {
        for (RoomPreset preset : values()) {
            if (preset.key.equals(key)) {
                return preset;
            }
        }
        return LISTENING_ROOM;
    }

    /** The next preset, wrapping around (quick switching from the player). */
    public RoomPreset next() {
        final RoomPreset[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
