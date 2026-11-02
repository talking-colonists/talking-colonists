package me.sshcrack.mc_talking.api;

import me.sshcrack.mc_talking.api.service.ColonyEventFeedService;
import me.sshcrack.mc_talking.api.service.ContextService;
import me.sshcrack.mc_talking.api.service.ProviderStatusService;
import me.sshcrack.mc_talking.api.service.ConversationRuleService;
import me.sshcrack.mc_talking.api.service.ConversationService;
import me.sshcrack.mc_talking.api.service.MemoryService;
import me.sshcrack.mc_talking.api.service.GuideService;
import me.sshcrack.mc_talking.api.service.IntroductionService;
import me.sshcrack.mc_talking.api.service.PlayerSpeechService;
import me.sshcrack.mc_talking.api.service.TextService;
import me.sshcrack.mc_talking.api.service.PregenerationService;
import me.sshcrack.mc_talking.api.service.PromptService;
import me.sshcrack.mc_talking.api.service.ToolService;
import org.jetbrains.annotations.NotNull;

/**
 * Root entry point for the Talking Colonists addon API.
 *
 * <p>The normal Talking Colonists mod contains the runtime implementation. The separate
 * {@code mc_talking-api} artifact is only a compile/source surface for addon developers and must
 * not be installed as an additional mod.</p>
 */
public final class TalkingColonistsApi {
    /** Breaking API generation for addon compatibility declarations. */
    public static final int API_MAJOR_VERSION = 2;

    /**
     * Additive minor revision within {@link #API_MAJOR_VERSION}. Bumped whenever a new, purely
     * additive entry point or {@link ApiFeature} lands. Addons should prefer
     * {@link #supports(ApiFeature)} over comparing this number directly, since it only says a
     * runtime is at least this new, not which individual features it implements.
     */
    public static final int API_MINOR_VERSION = 1;

    private static final String IMPLEMENTATION_CLASS =
            "me.sshcrack.mc_talking.internal.api.TalkingColonistsApiBackend";
    @SuppressWarnings("NullAway.Init") // Set by the mod runtime on first use (see services()).
    private static volatile Services services;

    private TalkingColonistsApi() {
    }

    /** Returns whether the normal Talking Colonists runtime is present and exposes this API. */
    public static boolean isAvailable() {
        try {
            return resolveServices() != null;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    /** Returns the major API generation implemented by the installed normal mod. */
    public static int runtimeApiMajorVersion() {
        return services().apiMajorVersion();
    }

    /**
     * Returns the additive minor revision implemented by the installed normal mod. A runtime that
     * predates {@link #API_MINOR_VERSION} (for example a 2.0 runtime with an addon compiled
     * against 2.1) answers {@code 0} through {@link Services#apiMinorVersion()}'s default method,
     * never by throwing.
     */
    public static int runtimeApiMinorVersion() {
        return services().apiMinorVersion();
    }

    /**
     * Returns whether the installed Talking Colonists runtime implements the given additive
     * feature. Safe to call on any runtime, including one that predates {@link ApiFeature} itself:
     * {@link Services#supports(ApiFeature)} is a default method, so an older {@code Services}
     * implementation that never heard of a newer feature simply answers {@code false} for it
     * instead of throwing {@link NoSuchMethodError}.
     *
     * <p>See {@code docs/addon-api.md} for the additional pattern addons need so that calling
     * {@code TalkingColonistsApi.supports(...)} itself is safe on a 2.0 runtime that predates this
     * very method.</p>
     */
    public static boolean supports(@NotNull ApiFeature feature) {
        return services().supports(feature);
    }

    /**
     * Throws the standard {@link UnsupportedOperationException} used by every unsupported feature
     * entry point, instead of letting gameplay proceed as if the feature existed. Feature entry
     * points added by later Track A tasks should call this at the top of their implementation
     * rather than inventing their own message/exception shape.
     */
    public static void requireSupported(@NotNull ApiFeature feature) {
        if (!supports(feature)) {
            throw new UnsupportedOperationException(
                    "Talking Colonists runtime does not implement " + feature
                            + " (installed API " + runtimeApiMajorVersion() + "."
                            + runtimeApiMinorVersion() + "); guard the call site with "
                            + "TalkingColonistsApi.supports(ApiFeature." + feature + ") first"
            );
        }
    }

    /**
     * Returns the focused runtime services supplied by the installed Talking Colonists mod.
     * Static facade classes are conveniences over these same service objects.
     */
    public static @NotNull Services services() {
        Services current = resolveServices();
        if (current == null) {
            throw new IllegalStateException("Talking Colonists runtime is not available");
        }
        return current;
    }

    private static Services resolveServices() {
        Services current = services;
        if (current != null) return current;
        synchronized (TalkingColonistsApi.class) {
            current = services;
            if (current != null) return current;
            try {
                Class<?> implementation = Class.forName(
                        IMPLEMENTATION_CLASS,
                        true,
                        TalkingColonistsApi.class.getClassLoader()
                );
                Object instance = implementation.getField("INSTANCE").get(null);
                if (!(instance instanceof Services resolved)) {
                    throw new IllegalStateException(
                            "Installed Talking Colonists runtime does not implement the expected addon API services"
                    );
                }
                services = resolved;
                return resolved;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "Talking Colonists runtime is not available; install the normal Talking Colonists mod",
                        e
                );
            }
        }
    }

    /** Aggregate of focused service contracts. Implementations are supplied only by the normal mod. */
    public interface Services {
        int apiMajorVersion();

        /**
         * Additive minor revision. Default method: a {@code Services} implementation compiled
         * before {@link #API_MINOR_VERSION} existed (i.e. a pre-2.1 runtime) never overrides this,
         * so it answers {@code 0} instead of throwing {@link NoSuchMethodError}.
         */
        default int apiMinorVersion() {
            return 0;
        }

        /**
         * Whether this runtime implements {@code feature}. Default method: an older runtime that
         * predates {@link ApiFeature} entirely answers {@code false} for every feature rather than
         * throwing, which is exactly the "unsupported" answer a feature-detecting addon expects.
         */
        default boolean supports(@NotNull ApiFeature feature) {
            return false;
        }

        @NotNull PromptService prompts();
        @NotNull ConversationRuleService conversationRules();
        @NotNull PregenerationService pregeneration();
        @NotNull ToolService tools();
        @NotNull ContextService context();
        @NotNull ConversationService conversations();
        @NotNull MemoryService memory();

        /** Roadmap A3. Default for runtimes that predate text generation; see {@link ApiFeature#TEXT_GENERATION}. */
        default @NotNull TextService text() {
            throw new UnsupportedOperationException("Text generation is not implemented by this runtime");
        }

        /** Roadmap A2. Default for runtimes that predate the event feed; see {@link ApiFeature#COLONY_EVENTS}. */
        default @NotNull ColonyEventFeedService colonyEvents() {
            throw new UnsupportedOperationException("The colony event feed is not implemented by this runtime");
        }

        /** Roadmap A7. Default for runtimes that predate provider status; see {@link ApiFeature#PROVIDER_BUDGET}. */
        default @NotNull ProviderStatusService providerStatus() {
            throw new UnsupportedOperationException("Provider budget status is not implemented by this runtime");
        }

        /** Roadmap A10. Default for runtimes that predate speech capture; see {@link ApiFeature#PLAYER_SPEECH_CAPTURE}. */
        default @NotNull PlayerSpeechService playerSpeech() {
            throw new UnsupportedOperationException("Player speech capture is not implemented by this runtime");
        }

        /** Default for runtimes that predate addon guides; see {@link ApiFeature#ADDON_GUIDES}. */
        default @NotNull GuideService guides() {
            throw new UnsupportedOperationException("Addon guides are not implemented by this runtime");
        }

        /** Default for runtimes that predate introductions; see {@link ApiFeature#INTRODUCTIONS}. */
        default @NotNull IntroductionService introductions() {
            throw new UnsupportedOperationException("Introductions are not implemented by this runtime");
        }
    }
}
