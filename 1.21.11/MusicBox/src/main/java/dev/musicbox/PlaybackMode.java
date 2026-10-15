package dev.musicbox;

public enum PlaybackMode {
    PLAYLIST,
    SINGLE,
    SHUFFLE;

    public PlaybackMode next() {
        PlaybackMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}


