package me.sshcrack.mc_talking.config;

import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.controller.ControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.config.v2.api.ConfigClassHandler;
import dev.isxander.yacl3.config.v2.api.ConfigField;
import dev.isxander.yacl3.config.v2.api.SerialEntry;
import dev.isxander.yacl3.config.v2.api.autogen.AutoGen;
import dev.isxander.yacl3.config.v2.api.autogen.DoubleField;
import dev.isxander.yacl3.config.v2.api.autogen.DoubleSlider;
import dev.isxander.yacl3.config.v2.api.autogen.EnumCycler;
import dev.isxander.yacl3.config.v2.api.autogen.IntField;
import dev.isxander.yacl3.config.v2.api.autogen.ListGroup;
import dev.isxander.yacl3.config.v2.api.autogen.StringField;
import dev.isxander.yacl3.config.v2.api.autogen.TickBox;
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder;
import dev.isxander.yacl3.platform.YACLPlatform;
import me.sshcrack.mc_talking.util.ComplaintRamp;
import me.sshcrack.mc_talking.McTalking;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration class for the McTalking mod.
 * Handles loading and managing configuration options.
 */
public class McTalkingConfig {
    public static final String FLASH_MODEL = "gemini-3.5-flash-lite";
    public static final String TTS_MODEL = "gemini-3.1-flash-tts-preview";
    public static final AvailableAI CHEAP_LIVE_MODEL = AvailableAI.Flash3;

    /** Movement speed multiplier when a citizen walks to the player on urgent contact. */
    public static final double CITIZEN_URGENT_WALK_SPEED = 1.2;

    public static final ConfigClassHandler<McTalkingConfig> INSTANCE = ConfigClassHandler.createBuilder(McTalkingConfig.class)
            .id(YACLPlatform.rl("mc_talking", "config"))
            .serializer(config -> GsonConfigSerializerBuilder.create(config)
                    .setPath(YACLPlatform.getConfigDir().resolve("yacl-mc_talking.json5"))
                    .setJson5(true)
                    .build())
            .build();

    // Preset selector (roadmap Q8). Kept first so it is at the top of the screen.
    @AutoGen(category = "api")
    @EnumCycler
    @SerialEntry(comment = "Sets the session and ambient-chatter entries at once. FREE_TIER: the defaults, fits a free AI Studio key. PAID_KEY: more concurrent sessions and chatter. QUIET_COLONY: citizens only talk when a player talks to them. Editing one of those entries afterwards switches this to CUSTOM. See docs/config-presets.md for the keys each preset sets.")
    public ConfigPreset configPreset = ConfigPreset.FREE_TIER;

    @SerialEntry(comment = "Internal: the preset whose values were last written. A different configPreset is applied on the next load.")
    public ConfigPreset appliedConfigPreset = ConfigPreset.FREE_TIER;

    // API Configuration
    @AutoGen(category = "api")
    @StringField
    @SerialEntry(comment = "This key is used to authenticate with the Gemini API. You can get one at https://aistudio.google.com/apikey")
    public String geminiApiKey = "";

    @AutoGen(category = "api")
    @EnumCycler
    @SerialEntry(comment = "Gemini Live model used for interactive citizen conversations.")
    public AvailableAI currentAiModel = AvailableAI.Flash3;

    // Language Configuration
    @AutoGen(category = "general")
    @StringField
    @SerialEntry(comment = "The language the AI should use to speak")
    public String language = "en-US";

    // Interaction Configuration
    @AutoGen(category = "general", group = "interaction")
    @TickBox
    @SerialEntry(comment = "Whether the citizens should respond if the player is in a group or not.")
    public boolean respondInGroups = false;

    @AutoGen(category = "general", group = "interaction")
    @TickBox
    @SerialEntry(comment = "If true, text messages from mumbling and citizen-to-citizen conversations will also be sent to nearby players in chat.")
    public boolean sendMumblingAndConversationsToChat = false;

    @AutoGen(category = "general", group = "interaction")
    @TickBox
    @SerialEntry(comment = "Show small animated speech bubbles above nearby citizens on this client.")
    public boolean showConversationBubbles = true;

    @AutoGen(category = "general", group = "interaction")
    @TickBox
    @SerialEntry(comment = "Show conversation feedback when looking at a citizen on this client.")
    public boolean showConversationHint = true;

    @AutoGen(category = "general", group = "interaction")
    @TickBox
    @SerialEntry(comment = "Keep conversation icons static and disable added conversation gestures on this client.")
    public boolean reducedConversationMotion = false;

    @AutoGen(category = "general", group = "interaction")
    @TickBox
    @SerialEntry(comment = "Animate built-in adult citizen mouths from received voice audio on this client.")
    public boolean showConversationMouths = true;

    // Colony Statistics Mentions
    @AutoGen(category = "citizens")
    @TickBox
    @SerialEntry(comment = "If true, citizens will occasionally mention colony milestones (buildings built, mobs killed, etc.) in their idle mumbles and conversations.")
    public boolean enableColonyStatsMentions = true;

    @AutoGen(category = "general", group = "interaction")
    @TickBox
    @SerialEntry(comment = "If true, citizens continue wandering normally while in a player conversation / conversation with another citizen etc. "
            + "If false (default), they stay in place for the duration of the conversation.")
    public boolean continueWorkDuringConversation = false;

    // Citizen - Citizen Interaction (Conversations between them)
    @AutoGen(category = "citizens", group = "citizen_to_citizen")
    @TickBox
    @SerialEntry(comment = "If true, an AI will look at the conversation and take not of the most notable events that happend in that conversation. If not the real time AI is only able to update their memory mid conversation (less accurate)")
    public boolean enableConversationSummaryAndMemorize = false;

    @AutoGen(category = "citizens", group = "citizen_to_citizen")
    @TickBox
    @SerialEntry(comment = "If true, citizens will be able to start conversations with each other without player involvement.")
    public boolean enableCitizenToCitizenConversation = true;

    @AutoGen(category = "citizens", group = "citizen_to_citizen")
    @EnumCycler
    @SerialEntry(comment = "How citizen-to-citizen conversations are generated.\nLIVE_WEBSOCKETS: Two Gemini Live sessions feed audio to each other in real time.\nFLASH_TTS: Flash generates a script, then Gemini TTS renders multi-speaker audio. Higher quality but subject to stricter preview-model free-tier limits.\nAUTO (default): Tries Flash+TTS first; automatically falls back to Live WebSockets if that pipeline is rate-limited or unavailable.")
    public ConversationMode conversationMode = ConversationMode.AUTO;

    // Random citizen-to-citizen conversations
    @AutoGen(category = "citizens", group = "random_conversations")
    @TickBox
    @SerialEntry(comment = "If true, citizens will randomly start conversations with each other based on the chance below. Requires enableCitizenToCitizenConversation to be true.")
    public boolean enableRandomConversations = true;

    @AutoGen(category = "citizens", group = "random_conversations")
    @DoubleSlider(min = 0.0, max = 1.0, step = 0.01)
    @SerialEntry(comment = "Chance (0.0-1.0) that a pair of nearby citizens start a random conversation per check interval")
    public double randomConversationChance = 0.08;

    @AutoGen(category = "citizens", group = "random_conversations")
    @IntField(min = 1, max = 10000)
    @SerialEntry(comment = "How often (in server ticks) to check for random citizen conversations. 20 ticks = 1 second")
    public int randomConversationCheckIntervalTicks = 400;

    // Pregeneration
    @AutoGen(category = "citizens", group = "pregeneration")
    @TickBox
    @SerialEntry(comment = "If true, the mod will pregenerate audio for citizen greetings and threats in the background to reduce audio latency.")
    public boolean enablePregeneration = true;

    @AutoGen(category = "citizens", group = "pregeneration")
    @DoubleField(min = 1.0, max = 20.0)
    @SerialEntry(comment = "Distance in blocks within which passing citizens will trigger their pregenerated greeting.")
    public double pregeneratedGreetingDistance = 6.0;

    @AutoGen(category = "citizens", group = "pregeneration")
    @IntField(min = 0, max = 60000)
    @SerialEntry(comment = "Cooldown in milliseconds between threat pregeneration plays for a citizen. Set to 0 to disable.")
    public int threatPlayCooldownMs = 15000;

    @AutoGen(category = "citizens", group = "pregeneration")
    @IntField(min = 0, max = 100)
    @SerialEntry(comment = "Maximum number of pregenerated greetings to store per citizen. Oldest greetings are discarded when limit is reached.")
    public int maxPregeneratedGreetingsPerCitizen = 5;

    @AutoGen(category = "citizens", group = "pregeneration")
    @IntField(min = 1, max = 10)
    @SerialEntry(comment = "Maximum number of pregenerated citizen-to-citizen greetings that may play in a single tick interval (default: 1). Increase only if you have a very large colony and want more ambient chatter.")
    public int maxGreetingsPerTickInterval = 1;

    // Resource Management
    @AutoGen(category = "general", group = "resource_management")
    @IntField(min = 1, max = 100)
    @SerialEntry(comment = "Maximum simultaneous foreground Gemini Live sessions. Defaults conservatively for Gemini AI Studio free-tier usage; actual limits vary by project and model.")
    public int maxConcurrentAgents = 2;

    @AutoGen(category = "general", group = "resource_management")
    @IntField(min = 1, max = 10)
    @SerialEntry(comment = "Maximum concurrent background Gemini Live sessions used by memory compaction and greeting pregeneration. Kept low by default to preserve free-tier capacity for player conversations.")
    public int maxConcurrentBackground = 1;

    @AutoGen(category = "general", group = "interaction")
    @TickBox
    @SerialEntry(comment = "If true, a player in a conversation can type to the citizen: chat lines starting with chatToCitizenPrefix (or every line, after /citizen_chat on) go to the citizen instead of server chat.")
    public boolean enableChatToCitizen = true;

    @AutoGen(category = "general", group = "interaction")
    @StringField
    @SerialEntry(comment = "Chat lines starting with this go to the citizen you are talking to. Leave empty to only use /citizen_chat on.")
    public String chatToCitizenPrefix = "@";

    @AutoGen(category = "general", group = "interaction")
    @DoubleField(min = 1.0, max = 100.0)
    @SerialEntry(comment = "Maximum distance the player can be from a citizen before the conversation is ended")
    public double maxConversationDistance = 8.0;

    @AutoGen(category = "general", group = "interaction")
    @TickBox
    @SerialEntry(comment = "If true, players can start (or end) a conversation without the Talking Device: by sneaking and "
            + "left-clicking a citizen with an empty main hand, or via the optional 'Talk to Citizen' keybind (unbound by default). "
            + "Does not change or remove the Talking Device item.")
    public boolean enableTalkWithoutDevice = true;

    @AutoGen(category = "general", group = "interaction")
    @EnumCycler
    @SerialEntry(comment = "In which format the AI should respond. This can be text, audio or both.")
    public ModalityModes modality = ModalityModes.AUDIO;

    @AutoGen(category = "general")
    @ListGroup(valueFactory = ToolListFactory.class, controllerFactory = ToolListFactory.class)
    @SerialEntry(comment = "List of disabled tools for the AI. These tools can't be used by the AI to perform actions. Use the /list_tools command to see a list of the available tools.")
    public List<String> disabledTools = new ArrayList<>();

    @AutoGen(category = "general")
    @TickBox
    @SerialEntry(comment = "If true, errors will be sent to players that have OP permissions. If false, errors will only be logged to the console.")
    public boolean sendErrorsToPlayers = true;

    // Proximity Mumbling
    @AutoGen(category = "citizens", group = "mumbling")
    @DoubleSlider(min = 0.0, max = 1.0, step = 0.01)
    @SerialEntry(comment = "Chance (0.0-1.0) that a nearby citizen starts mumbling to themselves per check interval")
    public double mumblingChance = 0.08;

    @AutoGen(category = "citizens")
    @DoubleField(min = 1.0, max = 100.0)
    @SerialEntry(comment = "Distance in blocks within which a citizen can be triggered to mumble/start a conversation etc when a player is nearby")
    //TODO this is also used for greetings between citizens
    public double citizenInteractionRange = 16.0;

    @AutoGen(category = "citizens", group = "mumbling")
    @IntField(min = 1, max = 10000)
    @SerialEntry(comment = "How often (in server ticks) to check for citizens to trigger mumbling near players. 20 ticks = 1 second")
    public int mumblingCheckIntervalTicks = 200;

    // Citizen-Initiated Contact
    @AutoGen(category = "citizens", group = "citizen_contact")
    @TickBox
    @SerialEntry(comment = "If true, citizens with urgent needs will proactively speak to nearby players.")
    public boolean enableCitizenInitiatedContact = true;

    @AutoGen(category = "citizens", group = "citizen_contact")
    @TickBox
    @SerialEntry(comment = "If true, a citizen welcomes each player to their colony with the Colony Handbook, and later tells them once about each addon feature as it becomes relevant.")
    public boolean enableIntroductions = true;

    @AutoGen(category = "citizens", group = "citizen_contact")
    @DoubleSlider(min = 0.0, max = 1.0, step = 0.01)
    @SerialEntry(comment = "Base chance (0.0-1.0) per check interval that an urgent citizen speaks to a nearby player. Multiplied by an urgency weight derived from the citizen's state (unhappiness, injury, hunger, etc.).")
    public double citizenContactBaseChance = 0.5;

    @AutoGen(category = "citizens", group = "citizen_contact")
    @IntField(min = 1, max = 10000)
    @SerialEntry(comment = "How often (in server ticks) to check for citizens that should initiate contact. 20 ticks = 1 second")
    public int citizenContactCheckIntervalTicks = 80;

    @AutoGen(category = "citizens", group = "citizen_contact")
    @TickBox
    @SerialEntry(comment = "When enabled, urgent citizens walk to the player and follow them, searching over a larger radius. The conversation auto-starts when they get close.")
    public boolean enableUrgentContactWalkToPlayer = true;

    @AutoGen(category = "citizens", group = "citizen_contact")
    @DoubleField(min = 5.0, max = 100.0)
    @SerialEntry(comment = "Search radius for urgent contacts when walk-to-player is enabled.")
    public double urgentContactSearchRange = 30.0;

    @AutoGen(category = "citizens", group = "citizen_contact")
    @DoubleSlider(min = 0.0, max = 10.0, step = 0.1)
    @SerialEntry(comment = "Extra urgency weight applied when the citizen is stuck (blocked by missing tools/items). Makes them much more likely to call for help.")
    public double blockingTaskUrgencyMultiplier = 3.0;

    @AutoGen(category = "citizens", group = "citizen_contact")
    @IntField(min = 0, max = 10000)
    @SerialEntry(comment = "Minimum cooldown in seconds between urgent citizen contacts for the same player. Set to 0 to disable.")
    public int playerUrgentContactCooldownSeconds = 60;

    @AutoGen(category = "citizens", group = "citizen_contact")
    @IntField(min = 0, max = 10000)
    @SerialEntry(comment = "Minimum seconds between any two unprompted lines citizens address to the same player: walking up with a need, casual and pregenerated greetings. Set to 0 to disable.")
    public int playerAddressCooldownSeconds = 120;

    @AutoGen(category = "citizens", group = "citizen_contact")
    @IntField(min = 0, max = 100000)
    @SerialEntry(comment = "Minimum seconds before the same citizen walks up to a player about a need again. Set to 0 to disable.")
    public int citizenUrgentContactCooldownSeconds = 600;

    @AutoGen(category = "citizens", group = "citizen_contact")
    @DoubleSlider(min = 0.0, max = 1.0, step = 0.01)
    @SerialEntry(comment = "Base weight for casual greetings (0.0-1.0). Even content citizens get this small chance to wave/say hello per check interval. Multiplied by citizenContactBaseChance.")
    public double citizenCasualGreetingWeight = 0.15;

    @AutoGen(category = "citizens", group = "pregeneration")
    @TickBox
    @SerialEntry(comment = "If true, the mod will pregenerate player-specific greetings for frequent citizen-player pairs in the background.")
    public boolean enablePlayerGreetingPregen = true;

    @AutoGen(category = "citizens", group = "pregeneration")
    @DoubleField(min = 1.0, max = 20.0)
    @SerialEntry(comment = "Distance in blocks within which a citizen will trigger their pregenerated player greeting.")
    public double playerGreetingDistance = 8.0;

    @AutoGen(category = "citizens", group = "voice_chat")
    @TickBox
    @SerialEntry(comment = "If true, citizens will whisper when talking")
    public boolean citizenVoiceWhisper = true;

    @AutoGen(category = "citizens", group = "voice_chat")
    @IntField(min = 0)
    @SerialEntry(comment = "Max voice distance of the citizen. Use 0 to use default distance")
    public int citizenVoiceDistance = 0;

    @AutoGen(category = "citizens", group = "voice_chat")
    @DoubleField(min = 0.0, max = 128.0)
    @SerialEntry(comment = "Citizens do not start unprompted speech (greetings, mumbling, rumors, addon lines) while a player within this many blocks already hears another citizen speaking or is in a conversation. 0 lets everyone talk at once.")
    public double speechFloorRadius = 32.0;

    @AutoGen(category = "citizens", group = "voice_chat")
    @DoubleField(min = 0.0, max = 1.0)
    @SerialEntry(comment = "While you talk to a citizen, other citizens' voices play at this volume (client side, 0 to 1). 1 turns this off.")
    public double otherCitizensVolumeWhileTalking = 0.3;

    // Per-citizen automatic-session cooldown
    @AutoGen(category = "citizens")
    @IntField(min = 0, max = 10000)
    @SerialEntry(comment = "Minimum number of seconds that must pass after a citizen's automatic session (mumble or citizen-to-citizen) ends before they can be selected for another one. Set to 0 to disable.")
    public int citizenCooldownSeconds = 120;

    // Post-Raid Trauma
    @AutoGen(category = "citizens", group = "raid_trauma")
    @IntField(min = 0, max = 7200)
    @SerialEntry(comment = "How long (in seconds) citizens express post-raid trauma in their prompts after a raid ends. Set to 0 to disable.")
    public int raidTraumaDurationSeconds = 1200;

    // Colony Events Window
    @AutoGen(category = "citizens", group = "colony_events")
    @IntField(min = 0, max = 7200)
    @SerialEntry(comment = "How long (in seconds) colony lifecycle events (births, deaths, job changes, building changes) appear in citizen prompts. Set to 0 to disable.")
    public int colonyEventWindowSeconds = 1200;

    // Rumor Mill
    @AutoGen(category = "citizens", group = "rumor_mill")
    @TickBox
    @SerialEntry(comment = "If true, citizens will share memories with each other as rumors, creating a living information ecosystem.")
    public boolean enableRumorMill = true;

    @AutoGen(category = "citizens", group = "rumor_mill")
    @IntField(min = 1, max = 72000)
    @SerialEntry(comment = "How often (in server ticks) to check for rumor propagation between nearby citizens. 20 ticks = 1 second.")
    public int rumorMillCheckIntervalTicks = 600;

    @AutoGen(category = "citizens", group = "rumor_mill")
    @DoubleField(min = 1.0, max = 100.0)
    @SerialEntry(comment = "Maximum distance in blocks for rumor propagation between citizens.")
    public double rumorMillRange = 12.0;

    @AutoGen(category = "citizens", group = "rumor_mill")
    @DoubleSlider(min = 0.0, max = 1.0, step = 0.05)
    @SerialEntry(comment = "Chance (0.0-1.0) that rumors are shared between a pair of nearby citizens per check.")
    public double rumorMillChancePerPair = 0.4;

    @AutoGen(category = "citizens", group = "rumor_mill")
    @IntField(min = 1, max = 100)
    @SerialEntry(comment = "Maximum number of rumor propagations per tick to bound server cost.")
    public int rumorMillMaxPropagationsPerTick = 3;

    @AutoGen(category = "citizens", group = "rumor_mill")
    @TickBox
    @SerialEntry(comment = "If true, citizens will voice rumors aloud when a player is nearby, creating immersive gossip.")
    public boolean enableRumorTalking = true;

    @AutoGen(category = "citizens", group = "rumor_mill")
    @TickBox
    @SerialEntry(comment = "If true, citizens who pass on a rumor or news while you watch stop, turn to each other for a moment, and you get a subtitle such as \"Kayla whispers some gossip to Mila\".")
    public boolean showGossipMoments = true;

    @AutoGen(category = "citizens", group = "rumor_mill")
    @DoubleSlider(min = 0.0, max = 1.0, step = 0.05)
    @SerialEntry(comment = "Chance (0.0-1.0) that a rumor propagation is voiced aloud when a player is nearby.")
    public double rumorTalkingChance = 0.5;

    @AutoGen(category = "citizens", group = "rumor_mill")
    @DoubleField(min = 1.0, max = 50.0)
    @SerialEntry(comment = "Maximum distance in blocks for a player to witness voiced rumors.")
    public double rumorTalkingRange = 12.0;

    @AutoGen(category = "citizens", group = "rumor_mill")
    @IntField(min = 1, max = 100)
    @SerialEntry(comment = "Maximum rumors stored per citizen. Oldest discarded when limit reached.")
    public int maxRumorsStored = 10;

    @AutoGen(category = "citizens", group = "rumor_mill")
    @IntField(min = 0, max = 20)
    @SerialEntry(comment = "How many rumors to include in a citizen's prompt. Set to 0 to disable.")
    public int maxRumorsInPrompt = 3;

    // Broadcast System
    @AutoGen(category = "citizens", group = "broadcast")
    @TickBox
    @SerialEntry(comment = "If true, players can ask citizens to broadcast messages across the colony, which propagate through citizen-to-citizen interactions.")
    public boolean enableBroadcastPropagation = true;

    @AutoGen(category = "citizens", group = "broadcast")
    @IntField(min = 1, max = 72000)
    @SerialEntry(comment = "How often (in server ticks) to check for broadcast propagation between citizens. 20 ticks = 1 second.")
    public int broadcastPropagationIntervalTicks = 300;

    @AutoGen(category = "citizens", group = "broadcast")
    @IntField(min = 1, max = 100)
    @SerialEntry(comment = "Maximum number of broadcast propagations per tick.")
    public int broadcastMaxPropagationsPerTick = 5;

    @AutoGen(category = "citizens", group = "broadcast")
    @DoubleField(min = 1.0, max = 1000.0)
    @SerialEntry(comment = "Maximum distance in blocks between citizens for broadcast propagation. Broadcasts spread through proximity like rumors.")
    public double broadcastPropagationRange = 24.0;

    @AutoGen(category = "citizens", group = "broadcast")
    @IntField(min = 0, max = 20)
    @SerialEntry(comment = "How many of the most recent broadcasts to include in a citizen's prompt.")
    public int maxBroadcastsInPrompt = 3;

    @AutoGen(category = "citizens", group = "broadcast")
    @IntField(min = 1, max = 100)
    @SerialEntry(comment = "Maximum broadcasts stored per citizen. Oldest discarded when limit reached.")
    public int maxBroadcastsStored = 20;

    @AutoGen(category = "citizens", group = "broadcast")
    @TickBox
    @SerialEntry(comment = "If true, citizens will announce broadcasts aloud when a player is nearby.")
    public boolean enableBroadcastYelling = true;

    @AutoGen(category = "citizens", group = "broadcast")
    @DoubleField(min = 1.0, max = 10000.0)
    @SerialEntry(comment = "Maximum distance in blocks for a player to hear a citizen announce a broadcast aloud.")
    // This is half of the default voice distance range
    public double broadcastYellingRange = 24.0;

    @SerialEntry(comment = "Internal: config schema version for one-time migrations.")
    public int configVersion = 4;

    // Personality Archetypes
    @AutoGen(category = "citizens", group = "personality")
    @TickBox
    @SerialEntry(comment = "If true, each citizen is randomly assigned a personality archetype that influences their speech style and tone.")
    public boolean enablePersonalityArchetypes = true;

    @AutoGen(category = "citizens", group = "personality")
    @TickBox
    @SerialEntry(comment = "If true, the Optimist archetype is in the random pool for newly assigned citizens.")
    public boolean personalityOptimist = true;

    @AutoGen(category = "citizens", group = "personality")
    @TickBox
    @SerialEntry(comment = "If true, the Grump archetype is in the random pool for newly assigned citizens.")
    public boolean personalityGrump = true;

    @AutoGen(category = "citizens", group = "personality")
    @TickBox
    @SerialEntry(comment = "If true, the Stoic archetype is in the random pool for newly assigned citizens.")
    public boolean personalityStoic = true;

    @AutoGen(category = "citizens", group = "personality")
    @TickBox
    @SerialEntry(comment = "If true, the Gossip archetype is in the random pool for newly assigned citizens.")
    public boolean personalityGossip = true;

    @AutoGen(category = "citizens", group = "personality")
    @TickBox
    @SerialEntry(comment = "If true, the Anxious archetype is in the random pool for newly assigned citizens.")
    public boolean personalityAnxious = true;

    @AutoGen(category = "citizens", group = "personality")
    @TickBox
    @SerialEntry(comment = "If true, the Boastful archetype is in the random pool for newly assigned citizens.")
    public boolean personalityBoastful = true;

    @AutoGen(category = "citizens", group = "personality")
    @TickBox
    @SerialEntry(comment = "If true, the Timid archetype is in the random pool for newly assigned citizens.")
    public boolean personalityTimid = true;

    @AutoGen(category = "citizens", group = "personality")
    @TickBox
    @SerialEntry(comment = "If true, the Philosophical archetype is in the random pool for newly assigned citizens.")
    public boolean personalityPhilosophical = true;

    @AutoGen(category = "citizens", group = "personality")
    @TickBox
    @SerialEntry(comment = "If true, the Sarcastic archetype is in the random pool for newly assigned citizens.")
    public boolean personalitySarcastic = true;

    @AutoGen(category = "citizens", group = "personality")
    @TickBox
    @SerialEntry(comment = "If true, the Dramatic archetype is in the random pool for newly assigned citizens.")
    public boolean personalityDramatic = true;

    @AutoGen(category = "citizens", group = "personality")
    @TickBox
    @SerialEntry(comment = "If true, the Nurturing archetype is in the random pool for newly assigned citizens.")
    public boolean personalityNurturing = true;

    @AutoGen(category = "citizens", group = "personality")
    @TickBox
    @SerialEntry(comment = "If true, the Competitive archetype is in the random pool for newly assigned citizens.")
    public boolean personalityCompetitive = true;

    @AutoGen(category = "citizens", group = "personality")
    @TickBox
    @SerialEntry(comment = "If true, the Curious archetype is in the random pool for newly assigned citizens.")
    public boolean personalityCurious = true;

    @AutoGen(category = "citizens", group = "personality")
    @TickBox
    @SerialEntry(comment = "If true, the Nostalgic archetype is in the random pool for newly assigned citizens.")
    public boolean personalityNostalgic = true;

    @AutoGen(category = "citizens", group = "personality")
    @TickBox
    @SerialEntry(comment = "If true, the Superstitious archetype is in the random pool for newly assigned citizens.")
    public boolean personalitySuperstitious = true;

    @AutoGen(category = "citizens")
    @ListGroup(valueFactory = ToolListFactory.class, controllerFactory = ToolListFactory.class)
    @SerialEntry(comment = "Custom personality archetype strings added to the random pool citizens can be assigned. Each entry is a freeform instruction injected into the citizen's system prompt. Example: 'Always speak in rhyming couplets.'")
    public List<String> customPersonalityArchetypes = new ArrayList<>();

    // Complaint ramp (roadmap Q5)
    @AutoGen(category = "citizens", group = "complaints")
    @TickBox
    @SerialEntry(comment = "If true, citizens voice a lasting problem (homelessness, no job, sickness, idling at work) more strongly the longer it lasts, from a remark to a complaint to a demand. MineColonies' happiness values are not changed.")
    public boolean enableComplaintRamp = true;

    @AutoGen(category = "citizens", group = "complaints")
    @IntField(min = 0, max = 100)
    @SerialEntry(comment = "Harshness: in an established colony, a problem may last this many times its usual fix time (about 2 days for a home, 1 for a job) before a passing remark becomes a complaint.")
    public int complaintAfterDays = 1;

    @AutoGen(category = "citizens", group = "complaints")
    @IntField(min = 0, max = 100)
    @SerialEntry(comment = "Harshness: in an established colony, a problem may last this many times its usual fix time before a complaint becomes a demand.")
    public int complaintDemandAfterDays = 5;

    @AutoGen(category = "citizens", group = "complaints")
    @IntField(min = 0, max = 100)
    @SerialEntry(comment = "Over this many colony days the founding patience (see youngColonyPatience) fades gradually to normal, so citizens never turn from patient to angry on one day. Raids are only feared after one happened. (The key keeps its old name so saved configs still apply.)")
    public int youngColonyHousingGraceDays = 10;

    @AutoGen(category = "citizens", group = "complaints")
    @DoubleField(min = 1.0, max = 10.0)
    @SerialEntry(comment = "How many times longer problems may last in a brand-new colony before citizens complain, and how much milder their moods are. Fades gradually to normal over the young colony grace days. 1 turns the founding patience off.")
    public double youngColonyPatience = ComplaintRamp.Settings.DEFAULT_YOUNG_COLONY_PATIENCE;

    // Colony Diplomacy
    @AutoGen(category = "citizens", group = "colony_diplomacy")
    @TickBox
    @SerialEntry(comment = "If true, citizens will reference neighboring colonies and their diplomatic standing (allies, enemies, etc.) in conversations.")
    public boolean enableColonyDiplomacy = true;

    // Memory Compaction
    @AutoGen(category = "citizens", group = "memory")
    @EnumCycler
    @SerialEntry(comment = "How memory compaction is performed. LIVE uses a text-only WebSocket session. FLASH uses the Gemini Flash API.")
    public MemoryMode memoryMode = MemoryMode.LIVE;

    @AutoGen(category = "citizens", group = "memory")
    @TickBox
    @SerialEntry(comment = "If true, memory requests (conversation memories and compaction) fall back to the cheap Gemini Live model when Flash-Lite's quota is used up or its service fails. The Live model has no daily limit on the free tier but uses one background Live session while it runs.")
    public boolean enableLiveTextFallback = true;

    @AutoGen(category = "citizens", group = "memory")
    @TickBox
    @SerialEntry(comment = "If true, citizen memories will be periodically compacted and summarized to prevent unbounded growth.")
    public boolean enableMemoryCompaction = true;

    @AutoGen(category = "citizens", group = "memory")
    @IntField(min = 20, max = 72000)
    @SerialEntry(comment = "How often (in server ticks) to check for memory compaction candidates. 20 ticks = 1 second.")
    public int memoryCompactionIntervalTicks = 100;

    @AutoGen(category = "citizens", group = "memory")
    @IntField(min = 1, max = 500)
    @SerialEntry(comment = "Maximum number of individual events/facts before compaction is triggered for a citizen.")
    public int memoryCompactionThreshold = 15;

    // Ambient Speech Budget
    @AutoGen(category = "citizens", group = "ambient_speech_budget")
    @TickBox
    @SerialEntry(comment = "If true, limits how many ambient lines (greetings, mumbles, voiced rumors/broadcasts, random citizen-to-citizen conversations, addon ambient lines) each player can hear within the rolling window below. When a nearby player is over budget the line is skipped entirely so a crowd of citizens cannot all talk over each other. Urgent contacts, player-started conversations, and controlled/meeting sessions are always exempt.")
    public boolean enableAmbientSpeechBudget = true;

    @AutoGen(category = "citizens", group = "ambient_speech_budget")
    @IntField(min = 1, max = 50)
    @SerialEntry(comment = "Maximum number of ambient lines a player may hear within the rolling window before further ambient lines near them are skipped.")
    public int ambientSpeechBudgetMaxLines = 3;

    @AutoGen(category = "citizens", group = "ambient_speech_budget")
    @IntField(min = 1, max = 3600)
    @SerialEntry(comment = "Length in seconds of the rolling window used to count ambient lines heard by a player.")
    public int ambientSpeechBudgetWindowSeconds = 60;

    @AutoGen(category = "citizens", group = "ambient_speech_budget")
    @DoubleField(min = 1.0, max = 128.0)
    @SerialEntry(comment = "Distance in blocks within which a player is considered to hear an ambient line for budget purposes. An ambient line is skipped entirely if any player within this range of the speaking citizen is over budget.")
    public double ambientSpeechBudgetHearingRange = 16.0;

    public static class ToolListFactory implements ListGroup.ValueFactory<String>, ListGroup.ControllerFactory<String> {
        @Override
        public String provideNewValue() {
            return "";
        }

        @Override
        public ControllerBuilder<String> createController(ListGroup annotation, ConfigField<List<String>> field, dev.isxander.yacl3.config.v2.api.autogen.OptionAccess storage, Option<String> option) {
            return StringControllerBuilder.create(option);
        }
    }



    /** Whether a built-in archetype is in the random pool. Citizens keep an archetype they already have. */
    public boolean isArchetypeEnabled(PersonalityArchetype archetype) {
        return switch (archetype) {
            case OPTIMIST -> personalityOptimist;
            case GRUMP -> personalityGrump;
            case STOIC -> personalityStoic;
            case GOSSIP -> personalityGossip;
            case ANXIOUS -> personalityAnxious;
            case BOASTFUL -> personalityBoastful;
            case TIMID -> personalityTimid;
            case PHILOSOPHICAL -> personalityPhilosophical;
            case SARCASTIC -> personalitySarcastic;
            case DRAMATIC -> personalityDramatic;
            case NURTURING -> personalityNurturing;
            case COMPETITIVE -> personalityCompetitive;
            case CURIOUS -> personalityCurious;
            case NOSTALGIC -> personalityNostalgic;
            case SUPERSTITIOUS -> personalitySuperstitious;
        };
    }

    public ComplaintRamp.Settings complaintRampSettings() {
        return new ComplaintRamp.Settings(enableComplaintRamp, complaintAfterDays,
                complaintDemandAfterDays, youngColonyHousingGraceDays, youngColonyPatience);
    }

    public static boolean hasGeminiApiKey() {
        String key = INSTANCE.instance().geminiApiKey;
        return key != null
                && !key.trim().isEmpty();
    }

    public static void loadConfig() {
        Path oldConfig = YACLPlatform.getConfigDir().resolve("mc_talking-common.toml");
        Path newConfig = YACLPlatform.getConfigDir().resolve("yacl-mc_talking.json5");

        boolean shouldMigrate = Files.exists(oldConfig) && !Files.exists(newConfig);
        INSTANCE.load();

        if (shouldMigrate) {
            McTalking.LOGGER.info("Migrating old TOML config to YACL JSON5 config...");
            try {
                List<String> lines = Files.readAllLines(oldConfig);
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split("=", 2);
                    if (parts.length != 2) continue;
                    String key = parts[0].trim();
                    String val = parts[1].trim();

                    if (val.startsWith("\"") && val.endsWith("\"")) {
                        val = val.substring(1, val.length() - 1);
                    }

                    switch (key) {
                        case "gemini_key":
                            INSTANCE.instance().geminiApiKey = val;
                            break;
                        case "ai_model":
                            try {
                                INSTANCE.instance().currentAiModel = AvailableAI.valueOf(val);
                            } catch (Exception e) {
                                McTalking.LOGGER.warn("Unknown AI model in old config: {}", val, e);
                            }
                            break;
                        case "language":
                            INSTANCE.instance().language = val;
                            break;
                        case "respond_in_group":
                            INSTANCE.instance().respondInGroups = java.lang.Boolean.parseBoolean(val);
                            break;
                        case "max_conversation_distance":
                            INSTANCE.instance().maxConversationDistance = Double.parseDouble(val);
                            break;
                        case "ai_modality":
                            try {
                                INSTANCE.instance().modality = ModalityModes.valueOf(val);
                            } catch (Exception e) {
                                McTalking.LOGGER.warn("Unknown modality in old config: {}", val, e);
                            }
                            break;
                        case "send_errors_to_players":
                            INSTANCE.instance().sendErrorsToPlayers = Boolean.parseBoolean(val);
                            break;
                        case "disabled_tools":
                            if (!val.equals("[]")) {
                                String toolsStr = val.replaceAll("[\\[\\]\"]", "");
                                String[] tools = toolsStr.split(",");
                                List<String> disabledTools = new ArrayList<>();
                                for (String t : tools) {
                                    if (!t.trim().isEmpty()) {
                                        disabledTools.add(t.trim());
                                    }
                                }
                                INSTANCE.instance().disabledTools = disabledTools;
                            }
                            break;
                        default:
                            McTalking.LOGGER.info("Unknown config key in old TOML config: {}", key);
                    }
                }

                INSTANCE.save();
                Files.deleteIfExists(oldConfig);
                McTalking.LOGGER.info("Successfully migrated old TOML config.");
            } catch (Exception e) {
                McTalking.LOGGER.error("Failed to migrate old TOML config", e);
            }
        }

        // One-time migration: LIVE_WEBSOCKETS → AUTO for existing JSON5 configs
        if (INSTANCE.instance().configVersion < 1) {
            if (INSTANCE.instance().conversationMode == ConversationMode.LIVE_WEBSOCKETS) {
                INSTANCE.instance().conversationMode = ConversationMode.AUTO;
                McTalking.LOGGER.info("[Config] Migrated conversationMode from LIVE_WEBSOCKETS to AUTO");
            }
            INSTANCE.instance().configVersion = 1;
            INSTANCE.save();
        }

        // One-time migration: old default citizenContactBaseChance (0.02) → new default (0.5)
        if (INSTANCE.instance().configVersion < 2) {
            if (INSTANCE.instance().citizenContactBaseChance <= 0.02) {
                INSTANCE.instance().citizenContactBaseChance = 0.5;
                McTalking.LOGGER.info("[Config] Migrated citizenContactBaseChance from 0.02 to 0.5");
            }
            INSTANCE.instance().configVersion = 2;
            INSTANCE.save();
        }

        // One-time migration: old default citizenContactCheckIntervalTicks (400) → new default (80)
        if (INSTANCE.instance().configVersion < 3) {
            if (INSTANCE.instance().citizenContactCheckIntervalTicks == 400) {
                INSTANCE.instance().citizenContactCheckIntervalTicks = 80;
                McTalking.LOGGER.info("[Config] Migrated citizenContactCheckIntervalTicks from 400 to 80");
            }
            INSTANCE.instance().configVersion = 3;
            INSTANCE.save();
        }

        // One-time migration: move old defaults onto the current free-tier-friendly
        // Live model and conservative concurrency. Exact non-default values are kept.
        if (INSTANCE.instance().configVersion < 4) {
            if (INSTANCE.instance().currentAiModel == AvailableAI.Flash2_5) {
                INSTANCE.instance().currentAiModel = AvailableAI.Flash3;
                McTalking.LOGGER.info("[Config] Migrated Gemini Live model from Flash2_5 to Flash3");
            }
            if (INSTANCE.instance().maxConcurrentAgents == 3) {
                INSTANCE.instance().maxConcurrentAgents = 2;
                McTalking.LOGGER.info("[Config] Migrated default maxConcurrentAgents from 3 to 2");
            }
            if (INSTANCE.instance().maxConcurrentBackground == 3) {
                INSTANCE.instance().maxConcurrentBackground = 1;
                McTalking.LOGGER.info("[Config] Migrated default maxConcurrentBackground from 3 to 1");
            }
            INSTANCE.instance().configVersion = 4;
            INSTANCE.save();
        }

        applyPresetChanges();
    }

    /**
     * Writes a newly chosen preset, or marks the preset {@link ConfigPreset#CUSTOM} when one
     * of its values was edited, and saves if that changed anything.
     */
    public static void applyPresetChanges() {
        ConfigPreset before = INSTANCE.instance().configPreset;
        if (ConfigPresets.reconcile(INSTANCE.instance())) {
            McTalking.LOGGER.info("[Config] Preset {} -> {}", before, INSTANCE.instance().configPreset);
            INSTANCE.save();
        }
    }
}
