package me.sshcrack.mc_talking.manager.audio;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import me.sshcrack.mc_talking.McTalkingVoicechatPlugin;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.internal.audio.VoicechatAccess;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class CitizenEntityAudioProvider implements AudioProvider {
    private final AbstractEntityCitizen entity;
    @Nullable
    private final String channelId;

    public CitizenEntityAudioProvider(AbstractEntityCitizen entity, @Nullable String channelId) {
        this.entity = entity;
        this.channelId = channelId;
    }

    @Override
    public @Nullable AudioChannel createChannel() {
        var vcApi = VoicechatAccess.get();
        if (vcApi == null) {
            return null;
        }

        var channel = vcApi.createEntityAudioChannel(UUID.randomUUID(), vcApi.fromEntity(entity));
        if (channel == null) {
            throw new IllegalStateException("Failed to create audio channel for entity: " + entity.getStringUUID());
        }

        if (channelId != null) {
            channel.setCategory(channelId);
        }

        if (McTalkingConfig.INSTANCE.instance().citizenVoiceWhisper) {
            channel.setWhispering(true);
        }

        var dist = McTalkingConfig.INSTANCE.instance().citizenVoiceDistance;
        if (dist != 0) {
            channel.setDistance(dist);
        }

        return channel;
    }

    @Override
    public @Nullable OpusDecoder createDecoder() {
        var vcApi = VoicechatAccess.get();
        return vcApi == null ? null : vcApi.createDecoder();
    }
}
