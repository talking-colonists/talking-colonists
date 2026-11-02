package me.sshcrack.mc_talking.conversations.memory;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Coordinates generation completion with one server-thread persistence authorization. */
final class MemorySaveCoordinator<T> {
    enum Status { SAVED, CANCELLED, FAILED }

    record Result(Status status, String detail, @Nullable Throwable cause) {
        static Result saved() { return new Result(Status.SAVED, "memory persisted", null); }
        static Result cancelled(String detail) { return new Result(Status.CANCELLED, detail, null); }
        static Result failed(String detail, Throwable cause) { return new Result(Status.FAILED, detail, cause); }
    }

    private final Consumer<Runnable> serverDispatcher;
    private final Consumer<T> persistence;
    private final CompletableFuture<Result> completion = new CompletableFuture<>();
    private boolean saveAuthorized;
    private boolean generationCompleted;
    private boolean saveDispatched;
    private boolean persistenceStarted;
    private @Nullable T generatedValue;
    private @Nullable Result terminalResult;

    MemorySaveCoordinator(Consumer<Runnable> serverDispatcher, Consumer<T> persistence) {
        this.serverDispatcher = Objects.requireNonNull(serverDispatcher, "serverDispatcher");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    CompletionStage<Result> completion() { return completion; }

    void authorizeSave() {
        T valueToDispatch = null;
        synchronized (this) {
            if (terminalResult != null || saveAuthorized) return;
            saveAuthorized = true;
            if (generationCompleted && !saveDispatched) {
                saveDispatched = true;
                valueToDispatch = generatedValue;
            }
        }
        dispatchIfReady(valueToDispatch);
    }

    void generationSucceeded(T value) {
        Objects.requireNonNull(value, "value");
        T valueToDispatch = null;
        synchronized (this) {
            if (terminalResult != null || generationCompleted) return;
            generationCompleted = true;
            generatedValue = value;
            if (saveAuthorized && !saveDispatched) {
                saveDispatched = true;
                valueToDispatch = value;
            }
        }
        dispatchIfReady(valueToDispatch);
    }

    void generationFailed(String detail, Throwable cause) {
        completeTerminal(Result.failed(detail, cause), false);
    }

    boolean cancel(String detail) {
        Result result;
        synchronized (this) {
            if (terminalResult != null || persistenceStarted) return false;
            result = Result.cancelled(detail);
            terminalResult = result;
        }
        completion.complete(result);
        return true;
    }

    private void dispatchIfReady(@Nullable T value) {
        if (value == null) return;
        try {
            serverDispatcher.accept(() -> persistOnServerThread(value));
        } catch (RuntimeException exception) {
            completeTerminal(Result.failed("failed to schedule memory persistence", exception), false);
        }
    }

    private void persistOnServerThread(T value) {
        synchronized (this) {
            if (terminalResult != null) return;
            persistenceStarted = true;
        }
        try {
            persistence.accept(value);
            completeTerminal(Result.saved(), true);
        } catch (RuntimeException exception) {
            completeTerminal(Result.failed("memory persistence failed", exception), true);
        }
    }

    private void completeTerminal(Result result, boolean persistenceHadStarted) {
        synchronized (this) {
            if (terminalResult != null) return;
            if (!persistenceHadStarted && persistenceStarted) return;
            terminalResult = result;
        }
        completion.complete(result);
    }
}
