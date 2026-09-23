/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tv.nicdev.craftrelay.embedded;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.CraftRelayProvider;
import tv.nicdev.craftrelay.api.CraftRelayState;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.common.internal.CraftRelayStartupBanner;
import tv.nicdev.craftrelay.common.internal.node.CraftRelayNode;

/**
 * Lifecycle owner for one CraftRelay node embedded in a host plugin.
 *
 * <p>Start and stop are asynchronous and safe to call concurrently. Calls made while an
 * operation is already in progress share that operation's future. A failed start returns the
 * node to a retryable state; once stopping starts, the node is terminal. The host owns the
 * scheduler and must route any platform access from API callbacks to that scheduler.
 *
 * @since 0.1.0
 */
public final class EmbeddedCraftRelayNode implements CraftRelayProvider {

    private final Object lifecycleLock = new Object();
    private final CraftRelayNode delegate;
    private final ExecutorService diagnosticExecutor;
    private final Consumer<? super String> startupLogger;

    private volatile State state = State.NEW;
    private CompletableFuture<CraftRelayApi> startFuture;
    private CompletableFuture<Void> stopFuture;

    EmbeddedCraftRelayNode(
            CraftRelayNode delegate,
            ExecutorService diagnosticExecutor,
            Consumer<? super String> startupLogger) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.diagnosticExecutor = Objects.requireNonNull(diagnosticExecutor, "diagnosticExecutor");
        this.startupLogger = Objects.requireNonNull(startupLogger, "startupLogger");
    }

    /**
     * Starts the embedded node and returns its public API after all receive paths are ready.
     *
     * @return shared asynchronous start future
     * @since 0.1.0
     */
    public CompletableFuture<CraftRelayApi> start() {
        CompletableFuture<CraftRelayApi> operation;
        synchronized (lifecycleLock) {
            if (state == State.STARTING || state == State.RUNNING) {
                return startFuture;
            }
            if (state == State.STOPPING || state == State.STOPPED) {
                return CompletableFuture.failedFuture(
                        new ApiUnavailableException("Embedded CraftRelay node is stopped"));
            }
            state = State.STARTING;
            operation = new CompletableFuture<>();
            startFuture = operation;
        }

        logStartup();

        CompletableFuture<Void> delegateStart;
        try {
            delegateStart = Objects.requireNonNull(delegate.start(), "delegate.start()");
        } catch (Throwable failure) {
            completeStart(operation, failure);
            return operation;
        }
        delegateStart.whenComplete((ignored, failure) -> completeStart(operation, failure));
        return operation;
    }

    /**
     * Returns the public API only while this node is running.
     *
     * @return available API, or empty while starting, stopping or stopped
     * @since 0.1.0
     */
    @Override
    public Optional<CraftRelayApi> api() {
        // Linearize the view with start/stop state transitions. This prevents a caller from
        // observing an API after a concurrent stop has already entered its shutdown phase.
        synchronized (lifecycleLock) {
            if (state != State.RUNNING) {
                return Optional.empty();
            }
            CraftRelayApi candidate = delegate.api();
            return candidate.state() == CraftRelayState.AVAILABLE
                    ? Optional.of(candidate)
                    : Optional.empty();
        }
    }

    /**
     * Stops the node and releases all owned resources.
     *
     * @return shared asynchronous stop future
     * @since 0.1.0
     */
    public CompletableFuture<Void> stop() {
        CompletableFuture<Void> operation;
        synchronized (lifecycleLock) {
            if (state == State.STOPPING || state == State.STOPPED) {
                return stopFuture;
            }
            state = State.STOPPING;
            operation = new CompletableFuture<>();
            stopFuture = operation;
        }
        CompletableFuture<Void> delegateClose;
        try {
            delegateClose = Objects.requireNonNull(delegate.close(), "delegate.close()");
        } catch (Throwable failure) {
            finishStop(operation, failure);
            return operation;
        }
        delegateClose.whenComplete((ignored, failure) -> finishStop(operation, failure));
        return operation;
    }

    private void completeStart(CompletableFuture<CraftRelayApi> operation, Throwable failure) {
        Throwable actualFailure = unwrap(failure);
        boolean success = false;
        synchronized (lifecycleLock) {
            if (startFuture != operation) {
                return;
            }
            if (actualFailure == null && state == State.STARTING) {
                state = State.RUNNING;
                success = true;
            }
            if (!success && state == State.STARTING) {
                state = State.NEW;
            }
        }
        if (success) {
            operation.complete(delegate.api());
            return;
        }
        if (actualFailure == null) {
            actualFailure = new ApiUnavailableException("Embedded CraftRelay node stopped during start");
        }
        operation.completeExceptionally(actualFailure);
    }

    private void finishStop(CompletableFuture<Void> operation, Throwable failure) {
        diagnosticExecutor.shutdownNow();
        synchronized (lifecycleLock) {
            state = State.STOPPED;
        }
        Throwable actualFailure = unwrap(failure);
        if (actualFailure == null) {
            operation.complete(null);
        } else {
            operation.completeExceptionally(actualFailure);
        }
    }

    private void logStartup() {
        try {
            diagnosticExecutor.execute(() -> {
                CraftRelayStartupBanner.writeTo(this::safeLog);
                safeLog("Embedded CraftRelay is starting.");
            });
        } catch (RejectedExecutionException ignored) {
            // A concurrent stop may close the executor before startup logging is scheduled.
        }
    }

    private void safeLog(String line) {
        try {
            startupLogger.accept(line);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            // Host logging must never influence the embedded lifecycle.
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure == null) {
            return null;
        }
        if (failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException) {
            return failure.getCause() == null ? failure : unwrap(failure.getCause());
        }
        return failure;
    }

    private enum State {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED
    }
}
