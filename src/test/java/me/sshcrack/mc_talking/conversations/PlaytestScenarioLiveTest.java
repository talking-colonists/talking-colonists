package me.sshcrack.mc_talking.conversations;

import com.google.gson.JsonObject;
import me.sshcrack.gemini_live_lib.gson.BidiGenerateContentSetup;
import me.sshcrack.mc_talking.api.memory.CitizenMemoryEntryView;
import me.sshcrack.mc_talking.api.memory.CitizenMemorySnapshot;
import me.sshcrack.mc_talking.api.memory.MemoryEntryType;
import me.sshcrack.mc_talking.api.memory.MemoryProvenance;
import me.sshcrack.mc_talking.api.prompt.view.CitizenPromptView;
import me.sshcrack.mc_talking.api.prompt.view.HappinessModifierType;
import me.sshcrack.mc_talking.api.prompt.view.HappinessModifierView;
import me.sshcrack.mc_talking.broadcast.BroadcastMessages;
import me.sshcrack.mc_talking.internal.api.IntroductionServiceBackend;
import me.sshcrack.mc_talking.internal.prompt.PromptRuntime;
import me.sshcrack.mc_talking.manager.tools.AITools;
import me.sshcrack.mc_talking.manager.tools.FunctionAction;
import me.sshcrack.mc_talking.onboarding.IntroductionTexts;
import me.sshcrack.mc_talking.testing.CitizenPromptViewFixture;
import me.sshcrack.mc_talking.testing.TestPromptProviders;
import me.sshcrack.mc_talking.util.MiscUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static me.sshcrack.mc_talking.testing.CitizenPromptViewFixture.PLAYER_ID;
import static me.sshcrack.mc_talking.testing.CitizenPromptViewFixture.citizen;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Virtual playtests: situations a real playtest got wrong, replayed against the Live model the game
 * uses, with the real prompts and the real tool declarations. Each runs {@value #SAMPLES} times, since
 * a prompt that works one time in three is not fixed, and every answer goes to
 * {@code build/prompt-behaviour-playtest.txt} to read. Tool calls get a neutral answer, except
 * {@code initiate_broadcast}, which rejects placeholders like the game. Opt-in like
 * {@link PromptBehaviourLiveTest} ({@code scripts/test-prompt-behaviour.sh}).
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = PromptBehaviourLiveTest.KEY_ENV, matches = ".+")
class PlaytestScenarioLiveTest {
    private static final int SAMPLES = 3;
    private static final Path LOG = Path.of("build", "prompt-behaviour-playtest.txt");
    private static final List<String> HOSTILE = List.of("incompeten", "useless", "pathetic", "neglect", "how dare",
            "disgrace", "lazy", "worthless", "abandon");
    private static final String NOTICE_HINT = "Tell them that anyone can post a notice for the whole colony: write it in a "
            + "book and put it on a lectern, the notice board, and citizens will read and answer it.";
    private static final String CAMPFIRE = "It is evening and you sit at the campfire with other citizens. Tell them a "
            + "short story from your life, two or three sentences, in your own voice.";
    private static final boolean RESUME_SYSTEM_SESSIONS = "1".equals(System.getenv("MC_TALKING_RESUME_SYSTEM_SESSIONS"));
    private static List<BidiGenerateContentSetup.Tool> tools;

    @BeforeAll
    static void setUp() throws IOException {
        TestPromptProviders.installDefault();
        AITools.register();
        tools = List.of(declarations());
        Files.createDirectories(LOG.getParent());
        Files.writeString(LOG, "");
    }

    /** 2026-09-27: asked how to tell everyone something, a citizen broadcast "Change this message...". */
    @Test
    void askingHowToAnnounceDoesNotBroadcast() {
        check("player asks how to announce", roleplay(citizen().build()),
                "[Steve is now speaking to you] How can I tell everyone in the colony something?",
                result -> !result.called("initiate_broadcast"));
    }

    /**
     * The same, asking the citizen to do it: before the fix this broadcast "Attention everyone, Steve has an
     * announcement!" every time. A call is fine only when the game rejects it, and the citizen then asks.
     */
    @Test
    void askingForAnAnnouncementWithoutWordsAsksForThem() {
        check("player asks for an announcement, no words", roleplay(citizen().build()),
                "[Steve is now speaking to you] How can I tell everyone in the colony something? Can you do it for me?",
                result -> result.calls().stream().noneMatch(call -> call.name().equals("initiate_broadcast")
                        && !BroadcastMessages.isPlaceholder(call.args().get("message").getAsString())));
    }

    /** The other side: with the words given, the broadcast still happens and carries them. */
    @Test
    void givenAnnouncementIsBroadcast() {
        check("player gives the announcement", roleplay(citizen().build()),
                "[Steve is now speaking to you] Please announce to the whole colony: the market opens tomorrow at dawn, "
                        + "everyone bring spare wheat.",
                result -> result.calls().stream().anyMatch(call -> call.name().equals("initiate_broadcast")
                        && call.args() != null && call.args().has("message")
                        && call.args().get("message").getAsString().toLowerCase(Locale.ROOT).contains("market")));
    }

    /** 2026-09-27: the citizen who handed over the handbook welcomed the player again when asked about the day. */
    @Test
    void welcomedPlayerIsNotWelcomedAgain() {
        check("chat after the welcome", roleplay(welcomed(citizen().colonyAgeDays(1)).build()),
                "[Steve is now speaking to you] Hey! How's your day going?",
                result -> !mentionsWelcome(result));
    }

    /**
     * 2026-09-27: introductions wrote their own memories with add_event_to_memory, which the campfire then
     * told stories about. A grumpy, unhappy citizen is the hard case for a sincere welcome.
     */
    @Test
    void welcomeLineIsWarmAndWritesNoMemory() {
        String system = controlled(citizen()
                .colonyAgeDays(1)
                .happiness(3.0)
                .happinessModifiers(new HappinessModifierView(HappinessModifierType.HOMELESSNESS, 0.4, 2))
                .build());
        check("the welcome line", system,
                IntroductionTexts.directive("Steve", true, IntroductionServiceBackend.WELCOME_LINE_HINT, false),
                result -> !result.called("add_event_to_memory") && !result.called("initiate_broadcast")
                        && lower(result).contains("handbook") && HOSTILE.stream().noneMatch(lower(result)::contains));
    }

    /** Election scenario 2026-09-27: the citizen who had welcomed the player opened the next introduction with "Welcome!". */
    @Test
    void laterIntroductionDoesNotWelcomeAgain() {
        check("a later introduction", controlled(welcomed(citizen().colonyAgeDays(1)).build()),
                IntroductionTexts.directive("Steve", false, NOTICE_HINT, true),
                result -> !mentionsWelcome(result) && !result.called("add_event_to_memory"));
    }

    /**
     * A citizen's sessions follow each other: the welcome, then a later introduction and an evening at the
     * campfire. System-started sessions do not resume the last one ({@code CitizenWsClient
     * #shouldResumeAndSaveSession}); when they did, the later lines re-welcomed the player 3 times in 3 and
     * the campfire story opened with the welcome. {@code MC_TALKING_RESUME_SYSTEM_SESSIONS=1} shows that again.
     */
    @Test
    void laterSessionsDoNotCarryTheWelcome() {
        String system = controlled(citizen().colonyAgeDays(1).build());
        List<String> failures = new ArrayList<>();
        StringBuilder log = new StringBuilder("== later sessions after the welcome (resumed: " + RESUME_SYSTEM_SESSIONS + ")\n");
        for (int sample = 0; sample < SAMPLES; sample++) {
            LiveModelTurn.Result welcome = live(system, IntroductionTexts.directive("Steve", true,
                    IntroductionServiceBackend.WELCOME_LINE_HINT, false), null);
            String resume = RESUME_SYSTEM_SESSIONS ? welcome.resumeHandle() : null;
            LiveModelTurn.Result notice = live(system, IntroductionTexts.directive("Steve", false, NOTICE_HINT, true), resume);
            LiveModelTurn.Result campfire = live(system, CAMPFIRE, resume);
            boolean passed = !mentionsWelcome(notice) && !lower(campfire).contains("handbook");
            log.append(passed ? "PASS  " : "FAIL  ").append("welcome: ").append(welcome.describe())
                    .append(" | notice: ").append(notice.describe()).append(" | campfire: ").append(campfire.describe()).append('\n');
            if (!passed) failures.add(notice.describe() + " / " + campfire.describe());
        }
        write(log);
        assertTrue(failures.isEmpty(), failures.size() + "/" + SAMPLES + " samples carried the welcome:\n"
                + String.join("\n---\n", failures));
    }

    /** #282: on day 1 an unhappy, homeless citizen is constructive, not accusing. */
    @Test
    void dayOneComplaintIsConstructive() {
        check("day one complaint", roleplay(citizen()
                        .colonyAgeDays(1)
                        .homeless()
                        .happiness(4.0)
                        .happinessModifiers(new HappinessModifierView(HappinessModifierType.HOMELESSNESS, 0.4, 1))
                        .build()),
                "[Steve is now speaking to you] Hi there, how are you settling in?",
                result -> HOSTILE.stream().noneMatch(lower(result)::contains));
    }

    /** Runs a scenario {@value #SAMPLES} times, logs every answer and fails when any sample did. */
    private static void check(String name, String system, String input, Predicate<LiveModelTurn.Result> passes) {
        String key = System.getenv(PromptBehaviourLiveTest.KEY_ENV);
        List<String> failures = new ArrayList<>();
        StringBuilder log = new StringBuilder("== ").append(name).append('\n');
        for (int sample = 0; sample < SAMPLES; sample++) {
            LiveModelTurn.Result result;
            try {
                result = LiveModelTurn.run(key, system, tools, input, PlaytestScenarioLiveTest::answer);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", e);
            } catch (IOException e) {
                throw new AssertionError("could not close the Live socket", e);
            }
            boolean passed = passes.test(result);
            log.append(passed ? "PASS  " : "FAIL  ").append(result.describe().replace('\n', ' ')).append('\n');
            if (!passed) failures.add(result.describe());
        }
        write(log);
        assertTrue(failures.isEmpty(), name + ": " + failures.size() + "/" + SAMPLES + " samples failed:\n"
                + String.join("\n---\n", failures));
    }

    /**
     * Every built-in tool, as the game declares it with the default config (which cannot load outside
     * the game, so {@link AITools#getEnabledTools()} is not used here).
     */
    private static BidiGenerateContentSetup.Tool declarations() {
        var tool = new BidiGenerateContentSetup.Tool();
        for (String name : AITools.getRegisteredFunctionNames()) {
            FunctionAction action = AITools.getAction(name);
            var declaration = new BidiGenerateContentSetup.Tool.FunctionDeclaration(action.getName(), action.getDescription());
            if (action.getProperty() != null) declaration.parameters = action.getProperty();
            tool.functionDeclarations.add(declaration);
        }
        return tool;
    }

    private static LiveModelTurn.Result live(String system, String input, String resume) {
        try {
            return LiveModelTurn.run(System.getenv(PromptBehaviourLiveTest.KEY_ENV), system, tools, input,
                    PlaytestScenarioLiveTest::answer, resume);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        } catch (IOException e) {
            throw new AssertionError("could not close the Live socket", e);
        }
    }

    private static void write(StringBuilder log) {
        try {
            Files.writeString(LOG, Files.readString(LOG) + log + "\n");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static JsonObject answer(String name, JsonObject args) {
        JsonObject result = new JsonObject();
        if (name.equals("initiate_broadcast") && args != null && args.has("message")
                && BroadcastMessages.isPlaceholder(args.get("message").getAsString())) {
            result.addProperty("success", false);
            result.addProperty("error", "That is not the player's announcement. Ask the player what the whole colony "
                    + "should hear, then call this with their words.");
            return result;
        }
        result.addProperty("success", true);
        return result;
    }

    private static boolean mentionsWelcome(LiveModelTurn.Result result) {
        return lower(result).contains("welcome");
    }

    private static String lower(LiveModelTurn.Result result) {
        return result.transcript().toLowerCase(Locale.ROOT);
    }

    private static String roleplay(CitizenPromptView view) {
        return MiscUtil.withFirstPicks(() -> PromptRuntime.generateCitizenRoleplayPrompt(view));
    }

    private static String controlled(CitizenPromptView view) {
        return MiscUtil.withFirstPicks(() -> PromptRuntime.generateSystemControlledRoleplayPrompt(view));
    }

    /** The citizen remembers welcoming Steve, as {@code Introductions} stores it after the welcome. */
    private static CitizenPromptViewFixture welcomed(CitizenPromptViewFixture fixture) {
        String memory = IntroductionTexts.memory("Steve", true, "the Colony Handbook");
        return fixture.memories(new CitizenMemorySnapshot(List.of(), List.of(),
                List.of(new CitizenMemoryEntryView(MemoryEntryType.EVENT, memory,
                        MemoryProvenance.ADDON_CONFIRMED_OUTCOME, PLAYER_ID, "mc_talking", null)),
                List.of(), List.of(), List.of(), List.of(), ""));
    }
}
