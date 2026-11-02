package me.sshcrack.mc_talking.internal.tool;

import me.sshcrack.mc_talking.internal.compat.MineColoniesCompatibilityMapper;

import com.google.gson.JsonObject;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.api.tool.AiCommandTool;
import me.sshcrack.mc_talking.api.tool.AiQueryTool;
import me.sshcrack.mc_talking.api.tool.AiTool;
import me.sshcrack.mc_talking.api.tool.AiToolContext;
import me.sshcrack.mc_talking.api.tool.AiToolOperationOutcome;
import me.sshcrack.mc_talking.api.tool.AiToolOperationStatus;
import me.sshcrack.mc_talking.api.tool.AiToolPermission;
import me.sshcrack.mc_talking.api.tool.AiToolScope;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

/** Central runtime dispatcher for supported addon AI tools. */
public final class AiToolDispatcher {
    private static final int DEFAULT_MAX_ACTIVE_COMMANDS = 64;
    private static final int DEFAULT_MAX_TERMINAL_RESULTS = 256;

    private final int maxActiveCommands;
    private final int maxTerminalResults;
    private final ToolResolver toolResolver;
    private final Predicate<AiToolRuntime.RegisteredTool> configurationEnabled;
    private final PermissionChecker permissionChecker;
    private final Map<CallKey, Operation> operations = new HashMap<>();
    private final ArrayDeque<CallKey> terminalOrder = new ArrayDeque<>();
    private int activeCommands;

    public AiToolDispatcher() {
        this(
                DEFAULT_MAX_ACTIVE_COMMANDS,
                DEFAULT_MAX_TERMINAL_RESULTS,
                AiToolRuntime::findByProviderName,
                registered -> registered.tool().isEnabled()
                        && !McTalkingConfig.INSTANCE.instance().disabledTools.contains(registered.providerName()),
                AiToolDispatcher::hasMineColoniesPermission
        );
    }

    AiToolDispatcher(
            int maxActiveCommands,
            int maxTerminalResults,
            @NotNull ToolResolver toolResolver,
            @NotNull Predicate<AiToolRuntime.RegisteredTool> configurationEnabled,
            @NotNull PermissionChecker permissionChecker
    ) {
        if (maxActiveCommands < 1) throw new IllegalArgumentException("maxActiveCommands must be positive");
        if (maxTerminalResults < 0) throw new IllegalArgumentException("maxTerminalResults must not be negative");
        this.maxActiveCommands = maxActiveCommands;
        this.maxTerminalResults = maxTerminalResults;
        this.toolResolver = Objects.requireNonNull(toolResolver, "toolResolver");
        this.configurationEnabled = Objects.requireNonNull(configurationEnabled, "configurationEnabled");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
    }

    /**
     * Validates and executes one provider call. This method may wait for a short server-thread hop,
     * but never waits for asynchronous command completion.
     */
    public @NotNull JsonObject dispatch(
            @Nullable String callId,
            @NotNull String providerName,
            @Nullable JsonObject arguments,
            @NotNull SessionEndpoint session
    ) {
        Objects.requireNonNull(providerName, "providerName");
        Objects.requireNonNull(session, "session");

        AiToolRuntime.RegisteredTool registered = toolResolver.resolve(providerName);
        if (registered == null) return failed(null, null, "unknown_tool", "Unknown addon tool: " + providerName);

        AiToolContext context = session.context();
        if (!session.sessionId().equals(context.sessionId())) {
            return failed(null, registered.id(), "wrong_session", "Tool context does not belong to this session");
        }
        if (!session.isAvailable()) {
            return failed(null, registered.id(), "session_closed", "The owning conversation is no longer active");
        }
        if (!configurationEnabled.test(registered)) {
            return failed(null, registered.id(), "disabled", "This tool is currently disabled");
        }

        String schemaError = AiToolSchemaValidator.validate(registered.tool().parameters(), arguments);
        if (schemaError != null) {
            return failed(null, registered.id(), "invalid_arguments", schemaError);
        }

        JsonObject copiedArguments = arguments == null ? null : arguments.deepCopy();
        if (registered.tool() instanceof AiQueryTool query) {
            return dispatchQuery(registered, query, copiedArguments, session);
        }
        if (registered.tool() instanceof AiCommandTool command) {
            return dispatchCommand(callId, registered, command, copiedArguments, session);
        }
        return failed(null, registered.id(), "invalid_tool_type", "Registered addon tool has no supported execution type");
    }

    private JsonObject dispatchQuery(
            AiToolRuntime.RegisteredTool registered,
            AiQueryTool query,
            @Nullable JsonObject arguments,
            SessionEndpoint session
    ) {
        String operationId = newOperationId();
        try {
            return session.context().supplyOnServerThread(() -> {
                String authorizationError = authorizationError(registered, session);
                if (authorizationError != null) {
                    return failed(operationId, registered.id(), "unauthorized", authorizationError);
                }
                JsonObject result = query.executeQuery(session.context(), arguments);
                if (result == null) {
                    return failed(operationId, registered.id(), "null_result", "Query returned no result");
                }
                return terminal(operationId, registered.id(), AiToolOperationStatus.COMPLETED, result, null, null);
            }).join();
        } catch (CompletionException e) {
            return failed(operationId, registered.id(), "execution_failed", rootMessage(e));
        } catch (RuntimeException e) {
            return failed(operationId, registered.id(), "execution_failed", rootMessage(e));
        }
    }

    private JsonObject dispatchCommand(
            @Nullable String callId,
            AiToolRuntime.RegisteredTool registered,
            AiCommandTool command,
            @Nullable JsonObject arguments,
            SessionEndpoint session
    ) {
        if (callId == null || callId.isBlank()) {
            return failed(null, registered.id(), "missing_call_id",
                    "Asynchronous commands require the provider call ID for idempotency");
        }

        CallKey key = new CallKey(session.operationScopeId(), callId);
        String fingerprint = registered.id() + "\n" + AiToolSchemaValidator.canonicalArguments(arguments);
        Operation operation;
        synchronized (this) {
            Operation existing = operations.get(key);
            if (existing != null) {
                if (!existing.fingerprint.equals(fingerprint)) {
                    return failed(existing.operationId, registered.id(), "call_id_conflict",
                            "This provider call ID was already used for a different command or arguments");
                }
                return operationJson(existing);
            }
            if (activeCommands >= maxActiveCommands) {
                return failed(null, registered.id(), "command_capacity",
                        "Too many addon commands are still running; try again later");
            }
            operation = new Operation(
                    key,
                    session.sessionId(),
                    newOperationId(),
                    registered.id(),
                    fingerprint,
                    command,
                    session
            );
            operations.put(key, operation);
            activeCommands++;
        }

        try {
            CompletionStage<JsonObject> stage = session.context().supplyOnServerThread(() -> {
                if (!session.isAvailable()) throw new SessionClosedBeforeStartException();
                AiToolRuntime.RegisteredTool current = toolResolver.resolve(registered.providerName());
                if (current == null || current.tool() != command) {
                    throw new AuthorizationException("Tool registration changed before command execution");
                }
                String authorizationError = authorizationError(current, session);
                if (authorizationError != null) throw new AuthorizationException(authorizationError);
                CompletionStage<JsonObject> started = command.executeCommand(session.context(), arguments);
                if (started == null) throw new IllegalStateException("Command returned no completion stage");
                return started;
            }).join();
            stage.whenComplete((result, failure) -> completeCommand(operation, result, failure));
        } catch (CompletionException e) {
            completeCommand(operation, null, e.getCause() == null ? e : e.getCause());
        } catch (RuntimeException e) {
            completeCommand(operation, null, e);
        }

        synchronized (this) {
            return operationJson(operation);
        }
    }

    private @Nullable String authorizationError(
            AiToolRuntime.RegisteredTool registered,
            SessionEndpoint session
    ) {
        if (!session.isAvailable()) return "The owning conversation is no longer active";
        if (!configurationEnabled.test(registered)) return "This tool is currently disabled";

        AiTool tool = registered.tool();
        AiToolContext context = session.context();
        if (tool.scope() == AiToolScope.PLAYER_CONVERSATION && context.authenticatedPlayerId() == null) {
            return "This tool requires an authenticated player conversation";
        }
        AiToolPermission permission = tool.permission();
        if (permission == null) return "Tool declared no valid permission policy";
        if (!permissionChecker.allowed(context, permission)) {
            return "The authenticated player does not currently have the required colony permission";
        }
        if (!tool.canExecute(context)) return "The addon denied this tool call";
        return null;
    }

    private void completeCommand(Operation operation, @Nullable JsonObject result, @Nullable Throwable failure) {
        completeCommand(operation, result, failure, true);
    }

    private void completeCommand(
            Operation operation,
            @Nullable JsonObject result,
            @Nullable Throwable failure,
            boolean allowDelivery
    ) {
        AiToolOperationStatus status;
        String errorCode = null;
        String error = null;
        JsonObject safeResult = null;

        Throwable root = unwrap(failure);
        if (root instanceof CancellationException || root instanceof SessionClosedBeforeStartException) {
            status = AiToolOperationStatus.CANCELLED;
            errorCode = "cancelled";
            error = root instanceof SessionClosedBeforeStartException
                    ? "The owning conversation closed before the command started"
                    : "The command was cancelled";
        } else if (root != null) {
            status = AiToolOperationStatus.FAILED;
            errorCode = root instanceof AuthorizationException ? "unauthorized" : "execution_failed";
            error = rootMessage(root);
        } else if (result == null) {
            status = AiToolOperationStatus.FAILED;
            errorCode = "null_result";
            error = "Command completed without a result";
        } else {
            status = AiToolOperationStatus.COMPLETED;
            safeResult = result.deepCopy();
        }

        synchronized (this) {
            if (operation.status != AiToolOperationStatus.ACCEPTED) return;
            operation.status = status;
            operation.result = safeResult;
            operation.errorCode = errorCode;
            operation.error = error;
            activeCommands--;
            terminalOrder.addLast(operation.key);
            pruneTerminalResults();
        }

        boolean delivered = allowDelivery
                && operation.session.isAvailable()
                && operation.session.deliver(operationJson(operation));
        AiToolOperationOutcome outcome = new AiToolOperationOutcome(
                operation.operationId,
                operation.sessionId,
                operation.toolId,
                operation.key.callId,
                status,
                safeResult,
                error,
                delivered
        );
        try {
            operation.command.onCompletion(outcome);
        } catch (RuntimeException callbackError) {
            McTalking.LOGGER.error("Addon AI tool completion hook failed for operation {}", operation.operationId, callbackError);
        }

        if (!allowDelivery || !operation.session.isAvailable()) {
            synchronized (this) {
                if (operations.remove(operation.key, operation)) terminalOrder.remove(operation.key);
            }
        }
    }

    private synchronized void pruneTerminalResults() {
        while (terminalOrder.size() > maxTerminalResults) {
            CallKey oldest = terminalOrder.removeFirst();
            Operation operation = operations.get(oldest);
            if (operation != null && operation.status != AiToolOperationStatus.ACCEPTED) {
                operations.remove(oldest, operation);
            }
        }
    }

    /** Terminalizes all retained/running operations owned by a conversation and releases capacity. */
    public void forgetSession(@NotNull UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        java.util.List<Operation> active;
        synchronized (this) {
            terminalOrder.removeIf(key -> {
                Operation operation = operations.get(key);
                return operation != null && operation.sessionId.equals(sessionId);
            });
            operations.entrySet().removeIf(entry ->
                    entry.getValue().sessionId.equals(sessionId)
                            && entry.getValue().status != AiToolOperationStatus.ACCEPTED);
            active = operations.values().stream()
                    .filter(operation -> operation.sessionId.equals(sessionId)
                            && operation.status == AiToolOperationStatus.ACCEPTED)
                    .toList();
        }
        for (Operation operation : active) {
            completeCommand(operation, null, new CancellationException("Owning conversation ended"), false);
        }
    }

    synchronized int activeCommandCount() {
        return activeCommands;
    }

    synchronized int retainedTerminalCount() {
        return terminalOrder.size();
    }

    private static JsonObject operationJson(Operation operation) {
        return terminal(
                operation.operationId,
                operation.toolId,
                operation.status,
                operation.result,
                operation.errorCode,
                operation.error
        );
    }

    private static JsonObject failed(
            @Nullable String operationId,
            @Nullable String toolId,
            @NotNull String code,
            @NotNull String message
    ) {
        return terminal(
                operationId == null ? newOperationId() : operationId,
                toolId,
                AiToolOperationStatus.FAILED,
                null,
                code,
                message
        );
    }

    private static JsonObject terminal(
            @NotNull String operationId,
            @Nullable String toolId,
            @NotNull AiToolOperationStatus status,
            @Nullable JsonObject result,
            @Nullable String errorCode,
            @Nullable String errorMessage
    ) {
        JsonObject response = new JsonObject();
        response.addProperty("status", status.name().toLowerCase(java.util.Locale.ROOT));
        response.addProperty("operationId", operationId);
        if (toolId != null) response.addProperty("toolId", toolId);
        if (result != null) response.add("result", result.deepCopy());
        if (errorMessage != null) {
            JsonObject error = new JsonObject();
            if (errorCode != null) error.addProperty("code", errorCode);
            error.addProperty("message", errorMessage);
            response.add("error", error);
        }
        return response;
    }

    private static boolean hasMineColoniesPermission(AiToolContext context, AiToolPermission permission) {
        if (permission == AiToolPermission.NONE) return true;
        if (context.player() == null) return false;
        var action = MineColoniesCompatibilityMapper.toolPermission(permission);
        return action != null && context.colony().getPermissions().hasPermission(context.player(), action);
    }

    private static String newOperationId() {
        return UUID.randomUUID().toString();
    }

    @Nullable
    private static Throwable unwrap(@Nullable Throwable failure) {
        Throwable result = failure;
        while ((result instanceof CompletionException || result instanceof java.util.concurrent.ExecutionException)
                && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = unwrap(failure);
        if (root == null) return failure.getClass().getSimpleName();
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    interface ToolResolver {
        @Nullable AiToolRuntime.RegisteredTool resolve(@NotNull String providerName);
    }

    interface PermissionChecker {
        boolean allowed(@NotNull AiToolContext context, @NotNull AiToolPermission permission);
    }

    public interface SessionEndpoint {
        @NotNull UUID sessionId();

        /** Scope used only for provider-call idempotency; controlled turns use their turn ID. */
        default @NotNull UUID operationScopeId() { return sessionId(); }

        @NotNull AiToolContext context();

        /** Whether delivery/execution can still target the original conversation without reconnecting. */
        boolean isAvailable();

        /** Delivers a terminal operation payload without queuing or reopening a provider session. */
        boolean deliver(@NotNull JsonObject outcome);
    }

    private record CallKey(UUID operationScopeId, String callId) {
    }

    private static final class Operation {
        private final CallKey key;
        private final UUID sessionId;
        private final String operationId;
        private final String toolId;
        private final String fingerprint;
        private final AiCommandTool command;
        private final SessionEndpoint session;
        private AiToolOperationStatus status = AiToolOperationStatus.ACCEPTED;
        @Nullable private JsonObject result;
        @Nullable private String errorCode;
        @Nullable private String error;

        private Operation(
                CallKey key,
                UUID sessionId,
                String operationId,
                String toolId,
                String fingerprint,
                AiCommandTool command,
                SessionEndpoint session
        ) {
            this.key = key;
            this.sessionId = sessionId;
            this.operationId = operationId;
            this.toolId = toolId;
            this.fingerprint = fingerprint;
            this.command = command;
            this.session = session;
        }
    }

    private static final class AuthorizationException extends RuntimeException {
        private AuthorizationException(String message) {
            super(message);
        }
    }

    private static final class SessionClosedBeforeStartException extends RuntimeException {
    }
}
