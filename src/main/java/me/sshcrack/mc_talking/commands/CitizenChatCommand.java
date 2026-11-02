package me.sshcrack.mc_talking.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.handler.ChatToCitizenHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /citizen_chat [on|off]} (roadmap Q10): whether every chat line goes to the citizen you are
 * talking to, not only lines starting with the prefix. Without an argument it toggles. Any player.
 */
public final class CitizenChatCommand {
    private CitizenChatCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("citizen_chat")
                .requires(source -> source.getPlayer() != null)
                .executes(ctx -> set(ctx.getSource(), null))
                .then(Commands.literal("on").executes(ctx -> set(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> set(ctx.getSource(), false))));
    }

    private static int set(CommandSourceStack source, @Nullable Boolean value) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        boolean on = value == null ? !ChatToCitizenHandler.isAllChatOn(player) : value;
        ChatToCitizenHandler.setAllChat(player, on);
        String prefix = McTalkingConfig.INSTANCE.instance().chatToCitizenPrefix;
        source.sendSuccess(() -> Component.translatable(on ? "mc_talking.chat_to_citizen.all_on" : "mc_talking.chat_to_citizen.all_off",
                prefix), false);
        return Command.SINGLE_SUCCESS;
    }
}
