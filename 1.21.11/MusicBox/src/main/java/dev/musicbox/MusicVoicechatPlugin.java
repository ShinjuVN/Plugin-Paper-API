package dev.musicbox;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;

public final class MusicVoicechatPlugin implements VoicechatPlugin {

    private final MusicBoxPlugin plugin;

    public MusicVoicechatPlugin(MusicBoxPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getPluginId() {
        return "musicbox";
    }

    @Override
    public void initialize(VoicechatApi api) {
        // Không cần làm gì, API server lấy từ VoicechatServerStartedEvent
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class,
                event -> plugin.playback().setApi(event.getVoicechat()));
        registration.registerEvent(VoicechatServerStoppedEvent.class,
                event -> plugin.playback().setApi(null));
    }
}


