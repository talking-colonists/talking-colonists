package me.sshcrack.mc_talking.manager.tools;

import me.sshcrack.gemini_live_lib.gson.BidiGenerateContentSetup;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.internal.tool.AiToolRuntime;
import me.sshcrack.mc_talking.api.tool.AiToolScope;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.function.Predicate;

/** Internal bridge between built-in tools and the public addon tool registry. */
public class AITools {
    private AITools() {
        /* This utility class should not be instantiated */
    }

    private static final Map<String, FunctionAction> registeredFunctions = new HashMap<>();
    private static final Map<String, FunctionAction> playerConversationOnlyTools = new HashMap<>();

    private static void addAll(Map<String, FunctionAction> map, List<FunctionAction> actions) {
        for (var action : actions) {
            map.put(action.getName(), action);
        }
    }

    @Nullable
    public static FunctionAction getAction(String name) {
        var action = registeredFunctions.get(name);
        if (action != null) return action;
        return playerConversationOnlyTools.get(name);
    }

    public static boolean hasAction(String name) {
        return getAction(name) != null || AiToolRuntime.findByProviderName(name) != null;
    }

    public static boolean isPlayerOnlyAction(String name) {
        var action = getAction(name);
        if (action != null) return action.isPlayerOnly();
        var addon = AiToolRuntime.findByProviderName(name);
        return addon != null && addon.tool().scope() == AiToolScope.PLAYER_CONVERSATION;
    }

    public static @Nullable String getToolDescription(String name) {
        var action = getAction(name);
        if (action != null) return action.getDescription();
        var addon = AiToolRuntime.findByProviderName(name);
        return addon == null ? null : addon.tool().description();
    }

    public static @Nullable Property getToolProperty(String name) {
        var action = getAction(name);
        if (action != null) return action.getProperty();
        var addon = AiToolRuntime.findByProviderName(name);
        return addon == null ? null : AiToolSchemaAdapter.toGemini(addon.tool().parameters());
    }

    public static List<String> getRegisteredFunctionNames() {
        var names = new ArrayList<>(registeredFunctions.keySet());
        names.addAll(playerConversationOnlyTools.keySet());
        AiToolRuntime.registeredTools().stream().map(AiToolRuntime.RegisteredTool::providerName).forEach(names::add);
        return names;
    }

    public static List<BidiGenerateContentSetup.Tool> getEnabledTools() {
        return getEnabledTools(ignored -> true);
    }

    public static List<BidiGenerateContentSetup.Tool> getEnabledTools(Predicate<AiToolRuntime.RegisteredTool> addonFilter) {
        return getEnabledTools(ignored -> true, addonFilter);
    }

    public static List<BidiGenerateContentSetup.Tool> getEnabledTools(Predicate<String> builtInFilter,
                                                                      Predicate<AiToolRuntime.RegisteredTool> addonFilter) {
        var list = new ArrayList<BidiGenerateContentSetup.Tool>();
        var tool = new BidiGenerateContentSetup.Tool();
        var rawToolsDisabled = McTalkingConfig.INSTANCE.instance().disabledTools;

        var builtIns = Stream.concat(
                registeredFunctions.values().stream(),
                playerConversationOnlyTools.values().stream()
        );

        tool.functionDeclarations.addAll(
                builtIns
                        .filter(e -> !rawToolsDisabled.contains(e.getName()))
                        .filter(e -> builtInFilter.test(e.getName()))
                        .filter(FunctionAction::isEnabled)
                        .map(e -> {
                            var declaration = new BidiGenerateContentSetup.Tool.FunctionDeclaration(e.getName(), e.getDescription());
                            if (e.getProperty() != null) declaration.parameters = e.getProperty();
                            return declaration;
                        })
                        .toList()
        );

        for (var addon : AiToolRuntime.registeredTools()) {
            if (!addonFilter.test(addon)) continue;
            var addonTool = addon.tool();
            if (!addonTool.isEnabled() || rawToolsDisabled.contains(addon.providerName())) continue;
            var declaration = new BidiGenerateContentSetup.Tool.FunctionDeclaration(
                    addon.providerName(), addonTool.description());
            if (addonTool.parameters() != null) declaration.parameters = AiToolSchemaAdapter.toGemini(addonTool.parameters());
            tool.functionDeclarations.add(declaration);
        }

        list.add(tool);
        return list;
    }

    public static void register() {
        addAll(registeredFunctions, List.of(
                new GetCitizenInfoAction(),
                new ListCitizenAction(),
                new GetInventoryAction(),
                new GetColonyAction(),
                new DescribeSurroundingsAction(),
                new DescribeBuildingAction(),
                new EndConversationAction(),
                new RecordRelationshipChange(),
                new AddEventToMemory(),
                new RecommendJobAction(),
                new GetCurrentSituationAction()
//                new JobSpecificAction()
        ));
        addAll(playerConversationOnlyTools, List.of(
                new DropItemAction(),
                new LeaveColonyAction(),
                new InitiateBroadcastAction(),
                new RaiseConcernAction()
        ));
    }
}
