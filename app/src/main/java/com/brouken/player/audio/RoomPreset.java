package com.brouken.player.audio;

/** Ready-made virtual rooms, and the room tuned by the listener. */
public enum RoomPreset {

    /** Small treated control room: speakers close, short and quiet reverb. */
    STUDIO("studio", new RoomParams(1.8, 1.0, 0.22, 0, 30, 0, 0)),
    /** Home theatre / listening room: the default. */
    LISTENING_ROOM("room", new RoomParams(2.3, 1.05, 0.30, 0, 30, 0, 0)),
    /** Large hall: far walls (late, spacious reflections), speakers a bit farther. */
    HALL("hall", new RoomParams(3.0, 1.4, 0.45, 0, 30, 0, 0)),
    /** Tuned by the listener (stored in the preferences). */
    CUSTOM("custom", null);

    /** Value stored in the preferences. */
    public final String key;
    /** The room, null for {@link #CUSTOM}. */
    public final RoomParams params;

    RoomPreset(String key, RoomParams params) {
        this.key = key;
        this.params = params;
    }

    public static RoomPreset fromKey(String key) {
        for (RoomPreset preset : values()) {
            if (preset.key.equals(key)) {
                return preset;
            }
        }
        return LISTENING_ROOM;
    }

    /** @return the room of this preset, or {@code custom} for {@link #CUSTOM} */
    public RoomParams resolve(RoomParams custom) {
        return params != null ? params : custom;
    }
}
