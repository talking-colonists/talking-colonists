package me.sshcrack.mc_talking.internal.session;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRecoveryControllerTest {
    @Test
    void transientRecoveryIsBoundedByAttemptCountAndObservable() {
        AtomicLong clock = new AtomicLong(1_000);
        var controller = new ProviderRecoveryController(2, 60_000, clock::get);
        controller.markConnecting("initial connect");
        controller.markSettingUp("opened");
        controller.setupSucceeded();

        var first = controller.beginRecovery("service restart");
        assertTrue(first.allowed());
        assertEquals(1, first.attemptNumber());
        assertEquals(1_000, first.delayMillis());
        assertEquals(ProviderRecoveryController.State.RECOVERING, first.diagnostic().state());
        assertEquals(1, first.diagnostic().totalRecoveryAttempts());

        controller.markSettingUp("reopened");
        controller.setupSucceeded();
        var second = controller.beginRecovery("transport loss");
        assertTrue(second.allowed());
        assertEquals(2, second.attemptNumber());
        assertEquals(1_000, second.delayMillis(), "successful setup resets the recovery episode");

        assertTrue(controller.beginRecovery("still unavailable").allowed());

        var exhausted = controller.beginRecovery("still unavailable");
        assertFalse(exhausted.allowed());
        assertTrue(exhausted.diagnostic().terminal());
        assertEquals(ProviderRecoveryController.State.TERMINAL_ERROR, exhausted.diagnostic().state());
        assertEquals(ProviderRecoveryController.TerminalReason.RECOVERY_EXHAUSTED,
                exhausted.diagnostic().terminalReason());
        assertTrue(exhausted.diagnostic().detail().contains("attempt limit 2"));
    }

    @Test
    void healthyConversationAgeDoesNotSpendTheNextRecoveryBudget() {
        AtomicLong clock = new AtomicLong();
        var controller = new ProviderRecoveryController(2, 5_000, clock::get);
        controller.setupSucceeded();
        clock.set(30 * 60_000);
        assertTrue(controller.beginRecovery("first outage after thirty healthy minutes").allowed());
        clock.addAndGet(5_001);
        assertFalse(controller.beginRecovery("outage still ongoing").allowed());
        assertTrue(controller.diagnostic().terminal());
    }

    @Test
    void recoveryWindowAlsoTerminatesWithoutAnotherAttempt() {
        AtomicLong clock = new AtomicLong(10_000);
        var controller = new ProviderRecoveryController(10, 5_000, clock::get);
        controller.markConnecting("initial");
        clock.addAndGet(5_001);

        var attempt = controller.beginRecovery("late disconnect");
        assertFalse(attempt.allowed());
        assertEquals(0, attempt.diagnostic().totalRecoveryAttempts());
        assertEquals(ProviderRecoveryController.TerminalReason.RECOVERY_EXHAUSTED,
                attempt.diagnostic().terminalReason());
        assertTrue(attempt.diagnostic().detail().contains("recovery window 5000 ms exceeded"));
    }

    @Test
    void intentionalCloseSuppressesReconnectAndKeepsItsTerminalReason() {
        AtomicLong clock = new AtomicLong();
        var controller = new ProviderRecoveryController(6, 60_000, clock::get);
        controller.markConnecting("initial");
        controller.closeIntentional("cancelled by owner");

        var attempt = controller.beginRecovery("late websocket callback");
        assertFalse(attempt.allowed());
        assertEquals(ProviderRecoveryController.State.CLOSED, attempt.diagnostic().state());
        assertEquals(ProviderRecoveryController.TerminalReason.INTENTIONAL_CLOSE,
                attempt.diagnostic().terminalReason());
        assertEquals("cancelled by owner", attempt.diagnostic().detail());
    }

    @Test
    void cleanupCloseDoesNotOverwriteProviderTerminalDiagnostic() {
        AtomicLong clock = new AtomicLong();
        var controller = new ProviderRecoveryController(6, 60_000, clock::get);
        controller.terminal(ProviderRecoveryController.TerminalReason.AUTHENTICATION, "invalid API key");
        controller.closeIntentional("registry cleanup");

        var diagnostic = controller.diagnostic();
        assertEquals(ProviderRecoveryController.State.TERMINAL_ERROR, diagnostic.state());
        assertEquals(ProviderRecoveryController.TerminalReason.AUTHENTICATION, diagnostic.terminalReason());
        assertEquals("invalid API key", diagnostic.detail());
        assertTrue(controller.intentionalClose());
    }

    @Test
    void closeClassificationSeparatesTransientFromTerminalProviderFailures() {
        assertEquals(ProviderRecoveryController.CloseDisposition.TRANSIENT,
                ProviderRecoveryController.classifyClose(1012, "service restart"));
        assertEquals(ProviderRecoveryController.CloseDisposition.TRANSIENT,
                ProviderRecoveryController.classifyClose(1013, "try again later"));
        assertEquals(ProviderRecoveryController.CloseDisposition.AUTHENTICATION_FAILURE,
                ProviderRecoveryController.classifyClose(1008, "API key not valid"));
        assertEquals(ProviderRecoveryController.CloseDisposition.CONFIGURATION_FAILURE,
                ProviderRecoveryController.classifyClose(1007, "invalid argument: unsupported model"));
        assertEquals(ProviderRecoveryController.CloseDisposition.POLICY_FAILURE,
                ProviderRecoveryController.classifyClose(1008, "request violates provider policy"));
        assertEquals(ProviderRecoveryController.CloseDisposition.SESSION_TOKEN_INVALID,
                ProviderRecoveryController.classifyClose(1007,
                        "BidiGenerateContent session expired and cannot resume"));
        assertEquals(ProviderRecoveryController.CloseDisposition.NORMAL,
                ProviderRecoveryController.classifyClose(1000, "done"));
        // An idle session the provider ended (automated playtest 2026-09-27): no error for the player.
        assertEquals(ProviderRecoveryController.CloseDisposition.NORMAL,
                ProviderRecoveryController.classifyClose(1008, "The operation was aborted."));
    }
}
