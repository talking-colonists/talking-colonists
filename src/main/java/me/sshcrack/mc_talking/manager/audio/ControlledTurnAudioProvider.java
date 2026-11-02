package me.sshcrack.mc_talking.manager.audio;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import me.sshcrack.mc_talking.api.conversation.ControlledAudioAnchor;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.internal.audio.VoicechatAccess;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Entity-following audio by default, fixed locational audio when a controlled turn supplies an anchor. */
public final class ControlledTurnAudioProvider implements AudioProvider {
    private final AbstractEntityCitizen entity;
    @Nullable private final ControlledAudioAnchor anchor;

    public ControlledTurnAudioProvider(AbstractEntityCitizen entity, @Nullable ControlledAudioAnchor anchor) {
        this.entity = entity;
        this.anchor = anchor;
    }

    @Override
    public @Nullable AudioChannel createChannel() {
        if (anchor == null) return new CitizenEntityAudioProvider(entity, null).createChannel();
        var api = VoicechatAccess.get();
        if (api == null) return null;
        var server = entity.level().getServer();
        if (server == null) return null;
        ServerLevel level = server.getLevel(anchor.dimension());
        if (level == null) throw new IllegalStateException("Controlled audio-anchor dimension is not loaded");
        var channel = api.createLocationalAudioChannel(
                UUID.randomUUID(), api.fromServerLevel(level), api.createPosition(anchor.x(), anchor.y(), anchor.z()));
        if (channel == null) throw new IllegalStateException("Failed to create controlled locational audio channel");
        var distance = McTalkingConfig.INSTANCE.instance().citizenVoiceDistance;
        if (distance != 0) channel.setDistance(distance);
        return channel;
    }

    @Override
    public @Nullable OpusDecoder createDecoder() {
        var api = VoicechatAccess.get();
        return api == null ? null : api.createDecoder();
    }
}
