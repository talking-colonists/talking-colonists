package me.sshcrack.mc_talking.manager.audio;

import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import org.jetbrains.annotations.Nullable;

public interface AudioProvider {
    @Nullable
    AudioChannel createChannel();

    @Nullable
    OpusDecoder createDecoder();
}
