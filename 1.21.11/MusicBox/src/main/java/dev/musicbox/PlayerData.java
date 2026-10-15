package dev.musicbox;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PlayerData {
    /** id -> link. Chỉ truy cập trên main thread. */
    public final Map<String, String> songs = new LinkedHashMap<>();

    // Các trường dưới được đọc từ thread của voice chat nên để volatile
    public volatile boolean listenOthers = true;
    public volatile boolean repeat = false;
    public volatile PlaybackMode mode = PlaybackMode.PLAYLIST;
    public volatile boolean banned = false;
    public volatile String lastPlayed;
    public volatile String name;
}


