package me.sshcrack.mc_talking.internal.session;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Deterministic bounded-recovery state machine for one provider session.
 *
 * <p>Only transport/service failures are retried automatically. Generic 1007 invalid-argument and
 * 1008 policy closures are terminal unless a caller has already recognized a narrower recoverable
 * condition (for example an expired session-resumption handle or an explicitly rejected voice).</p>
 */
public final class ProviderRecoveryController {
    public enum State {
        NEW,
        CONNECTING,
        SETTING_UP,
        ACTIVE,
        RECOVERING,
        CLOSED,
        TERMINAL_ERROR,
        QUOTA_EXCEEDED
    }

    public enum TerminalReason {
        NONE,
        NORMAL_CLOSE,
        INTENTIONAL_CLOSE,
        AUTHENTICATION,
        CONFIGURATION,
        PROVIDER_POLICY,
        QUOTA,
        RECOVERY_EXHAUSTED,
        PROVIDER_ERROR
    }

    public enum CloseDisposition {
        NORMAL,
        TRANSIENT,
        SESSION_TOKEN_INVALID,
        AUTHENTICATION_FAILURE,
        CONFIGURATION_FAILURE,
        POLICY_FAILURE
    }

    public record Diagnostic(
            State state,
            TerminalReason terminalReason,
            String detail,
            int totalRecoveryAttempts,
            int consecutiveRecoveryAttempts,
            long elapsedMillis
    ) {
        public boolean terminal() {
            return state == State.CLOSED || state == State.TERMINAL_ERROR || state == State.QUOTA_EXCEEDED;
        }
    }

    public record RecoveryAttempt(boolean allowed, int attemptNumber, long delayMillis, Diagnostic diagnostic) {
    }

    private final int maxAttempts;
    private final long maxWindowMillis;
    private final LongSupplier millisClock;
    private long recoveryStartedAtMillis;
    private boolean recoveryWindowActive = true;

    private State state = State.NEW;
    private TerminalReason terminalReason = TerminalReason.NONE;
    private String detail = "new";
    private int totalRecoveryAttempts;
    private int consecutiveRecoveryAttempts;
    private boolean intentionalClose;

    public ProviderRecoveryController(int maxAttempts, long maxWindowMillis, LongSupplier millisClock) {
        if (maxAttempts < 0) throw new IllegalArgumentException("maxAttempts must be non-negative");
        if (maxWindowMillis <= 0) throw new IllegalArgumentException("maxWindowMillis must be positive");
        this.maxAttempts = maxAttempts;
        this.maxWindowMillis = maxWindowMillis;
        this.millisClock = Objects.requireNonNull(millisClock, "millisClock");
        this.recoveryStartedAtMillis = millisClock.getAsLong();
    }

    public synchronized void markConnecting(String detail) {
        if (terminal()) return;
        state = State.CONNECTING;
        this.detail = safe(detail, "connecting");
    }

    public synchronized void markSettingUp(String detail) {
        if (terminal()) return;
        state = State.SETTING_UP;
        this.detail = safe(detail, "setting up");
    }

    public synchronized void setupSucceeded() {
        if (terminal()) return;
        state = State.ACTIVE;
        terminalReason = TerminalReason.NONE;
        detail = "setup complete";
        consecutiveRecoveryAttempts = 0;
        recoveryWindowActive = false;
    }

    /** Reserves one bounded recovery attempt and returns its exponential-backoff delay. */
    public synchronized RecoveryAttempt beginRecovery(String cause) {
        if (!terminal() && !recoveryWindowActive) {
            recoveryStartedAtMillis = millisClock.getAsLong();
            recoveryWindowActive = true;
        }
        if (!canRecoverLocked()) {
            if (!terminal()) {
                state = State.TERMINAL_ERROR;
                terminalReason = TerminalReason.RECOVERY_EXHAUSTED;
                detail = recoveryExhaustionDetail(cause);
            }
            return new RecoveryAttempt(false, totalRecoveryAttempts, 0, diagnosticLocked());
        }

        totalRecoveryAttempts++;
        consecutiveRecoveryAttempts++;
        state = State.RECOVERING;
        detail = safe(cause, "provider recovery");
        long delay = Math.min(8000L, 1000L << Math.min(consecutiveRecoveryAttempts - 1, 3));
        return new RecoveryAttempt(true, totalRecoveryAttempts, delay, diagnosticLocked());
    }

    public synchronized boolean canRecover() {
        return canRecoverLocked();
    }

    public synchronized void closeIntentional(String detail) {
        intentionalClose = true;
        if (terminal()) return;
        state = State.CLOSED;
        terminalReason = TerminalReason.INTENTIONAL_CLOSE;
        this.detail = safe(detail, "intentional close");
    }

    public synchronized void closeNormal(String detail) {
        if (terminal()) return;
        state = State.CLOSED;
        terminalReason = TerminalReason.NORMAL_CLOSE;
        this.detail = safe(detail, "normal close");
    }

    public synchronized void quotaExceeded(String detail) {
        if (terminal()) return;
        state = State.QUOTA_EXCEEDED;
        terminalReason = TerminalReason.QUOTA;
        this.detail = safe(detail, "quota exceeded");
    }

    public synchronized void terminal(TerminalReason reason, String detail) {
        if (reason == TerminalReason.NONE || reason == TerminalReason.NORMAL_CLOSE
                || reason == TerminalReason.INTENTIONAL_CLOSE || reason == TerminalReason.QUOTA) {
            throw new IllegalArgumentException("Use the dedicated terminal transition for " + reason);
        }
        state = State.TERMINAL_ERROR;
        terminalReason = reason;
        this.detail = safe(detail, reason.name().toLowerCase(Locale.ROOT));
    }

    public synchronized Diagnostic diagnostic() {
        return diagnosticLocked();
    }

    public synchronized boolean intentionalClose() {
        return intentionalClose;
    }

    private boolean canRecoverLocked() {
        if (intentionalClose || terminal()) return false;
        if (consecutiveRecoveryAttempts >= maxAttempts) return false;
        return elapsedMillisLocked() < maxWindowMillis;
    }

    private boolean terminal() {
        return state == State.CLOSED || state == State.TERMINAL_ERROR || state == State.QUOTA_EXCEEDED;
    }

    private String recoveryExhaustionDetail(@Nullable String cause) {
        String why = consecutiveRecoveryAttempts >= maxAttempts
                ? "attempt limit " + maxAttempts + " reached"
                : "recovery window " + maxWindowMillis + " ms exceeded";
        String source = cause == null || cause.isBlank() ? "provider recovery" : cause;
        return source + ": " + why;
    }

    private Diagnostic diagnosticLocked() {
        return new Diagnostic(
                state,
                terminalReason,
                detail,
                totalRecoveryAttempts,
                consecutiveRecoveryAttempts,
                elapsedMillisLocked()
        );
    }

    private long elapsedMillisLocked() {
        return recoveryWindowActive ? Math.max(0L, millisClock.getAsLong() - recoveryStartedAtMillis) : 0L;
    }

    /** Classifies generic WebSocket closures after narrower voice/quota checks have run. */
    public static CloseDisposition classifyClose(int code, @Nullable String reason) {
        String normalized = reason == null ? "" : reason.toLowerCase(Locale.ROOT);

        // Keep session-resumption recovery deliberately narrow. A generic 1007 "invalid argument"
        // can describe a poisoned request/tool turn and replaying it may deterministically fail.
        if (normalized.contains("bidigeneratecontent session")
                && (normalized.contains("invalid") || normalized.contains("expired")
                || normalized.contains("not found") || normalized.contains("resume"))) {
            return CloseDisposition.SESSION_TOKEN_INVALID;
        }

        if (containsAny(normalized,
                "api key not valid", "invalid api key", "api key is invalid", "unauthenticated",
                "authentication failed", "authentication error", "permission denied", "forbidden")) {
            return CloseDisposition.AUTHENTICATION_FAILURE;
        }

        if (containsAny(normalized,
                "invalid argument", "model not found", "unsupported model", "not supported",
                "failed precondition", "billing is disabled", "billing disabled", "malformed request")) {
            return CloseDisposition.CONFIGURATION_FAILURE;
        }

        if (code == 1000) return CloseDisposition.NORMAL;
        // The provider ends a session that sat idle (the player said nothing for minutes) with
        // 1008 "The operation was aborted.": the conversation is over, not a policy failure.
        if (normalized.contains("operation was aborted")) return CloseDisposition.NORMAL;
        if (code == 1007 || code == 1009 || code == 1002 || code == 1003) {
            return CloseDisposition.CONFIGURATION_FAILURE;
        }
        if (code == 1008) return CloseDisposition.POLICY_FAILURE;

        // RFC/service restart, abnormal transport loss, server error, overload and gateway failure.
        if (code == 1001 || code == 1006 || code == 1011 || code == 1012 || code == 1013 || code == 1014) {
            return CloseDisposition.TRANSIENT;
        }
        if (containsAny(normalized, "temporarily unavailable", "service unavailable", "internal error",
                "server error", "try again later", "overloaded", "connection reset")) {
            return CloseDisposition.TRANSIENT;
        }

        // Unknown transport/application close codes are not assumed to be configuration faults.
        // They receive the same bounded recovery budget, so proprietary transient provider codes
        // cannot create an endless loop while still getting a chance to recover.
        return CloseDisposition.TRANSIENT;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private static String safe(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
