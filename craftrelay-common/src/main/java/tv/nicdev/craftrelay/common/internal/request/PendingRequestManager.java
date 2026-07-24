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
package tv.nicdev.craftrelay.common.internal.request;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.exception.RequestTimeoutException;
import tv.nicdev.craftrelay.api.target.NetworkTarget;
import tv.nicdev.craftrelay.common.internal.concurrent.AsyncFailures;
import tv.nicdev.craftrelay.common.internal.concurrent.FutureCompletionDispatcher;
import tv.nicdev.craftrelay.common.internal.protocol.DecodedMessage;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntime;

/**
 * Thread-safe owner of correlated, in-flight requests.
 */
public final class PendingRequestManager implements AutoCloseable {

    private final Object lock = new Object();
    private final MessagingRuntime runtime;
    private final int maximumPendingRequests;
    private final FutureCompletionDispatcher completionDispatcher;
    private final ScheduledExecutorService timeoutScheduler;
    private final Map<UUID, PendingRequest<?>> pendingRequests = new HashMap<>();
    private final Subscription responseSubscription;

    private boolean closed;

    /**
     * Creates a request manager and installs its metadata-aware response listener.
     *
     * @param runtime messaging runtime
     * @param config request settings
     * @param completionDispatcher controlled public-future completer
     */
    public PendingRequestManager(
            MessagingRuntime runtime,
            RequestRuntimeConfig config,
            FutureCompletionDispatcher completionDispatcher) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(config, "config");
        this.completionDispatcher =
                Objects.requireNonNull(completionDispatcher, "completionDispatcher");
        maximumPendingRequests = config.maximumPendingRequests();
        timeoutScheduler =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform()
                                .daemon()
                                .name("craftrelay-request-timeout")
                                .factory());
        responseSubscription = runtime.subscribeDecoded(this::acceptResponse);
    }

    /**
     * Registers and publishes a correlated request.
     *
     * @param target request target
     * @param request request payload
     * @param responseType exact expected response type
     * @param timeout positive finite timeout
     * @param <R> response type
     * @return correlated result future
     */
    public <R extends NetworkMessage> CompletableFuture<R> request(
            NetworkTarget target,
            NetworkMessage request,
            Class<R> responseType,
            Duration timeout) {
        Objects.requireNonNull(target, "target");
        long timeoutNanos =
                RequestValidation.validateAndGetTimeoutNanos(
                        request, responseType, timeout);

        CompletableFuture<R> result;
        PendingRequest<R> pending;
        synchronized (lock) {
            if (closed) {
                return failedFuture(new ApiUnavailableException("request manager is closed"));
            }
            if (pendingRequests.size() >= maximumPendingRequests) {
                return failedFuture(
                        new ApiUnavailableException(
                                "maximum number of pending requests reached"));
            }

            result = completionDispatcher.newFuture();
            UUID correlationId = nextCorrelationId();
            pending = new PendingRequest<>(correlationId, responseType, target, result);
            pendingRequests.put(correlationId, pending);
            try {
                pending.timeoutTask =
                        timeoutScheduler.schedule(
                                () -> timeOut(correlationId, pending),
                                timeoutNanos,
                                TimeUnit.NANOSECONDS);
            } catch (RuntimeException failure) {
                pendingRequests.remove(correlationId);
                completionDispatcher.fail(result, failure);
                return result;
            }
        }

        PendingRequest<R> captured = pending;
        result.whenComplete(
                (ignored, failure) -> {
                    if (result.isCancelled()) {
                        remove(captured.correlationId, captured);
                    }
                });
        if (!isPending(pending.correlationId, pending)) {
            return result;
        }

        CompletableFuture<Void> publish;
        try {
            publish =
                    Objects.requireNonNull(
                            runtime.publish(
                                    target,
                                    request,
                                    Optional.of(pending.correlationId)),
                            "runtime.publish()");
        } catch (RuntimeException failure) {
            failIfPending(pending.correlationId, pending, failure);
            return result;
        }
        publish.whenComplete(
                (ignored, failure) -> {
                    if (failure != null) {
                        failIfPending(pending.correlationId, pending, failure);
                    }
                });
        return result;
    }

    /**
     * Returns the current number of requests awaiting responses.
     *
     * @return pending request count
     */
    public int pendingCount() {
        synchronized (lock) {
            return pendingRequests.size();
        }
    }

    /**
     * Stops accepting work and fails all in-flight requests.
     */
    @Override
    public void close() {
        List<PendingRequest<?>> pending;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            pending = new ArrayList<>(pendingRequests.values());
            pendingRequests.clear();
        }
        responseSubscription.close();
        timeoutScheduler.shutdownNow();
        ApiUnavailableException failure =
                new ApiUnavailableException("CraftRelay node is shutting down");
        for (PendingRequest<?> request : pending) {
            request.cancelTimeout();
            completionDispatcher.fail(request.result, failure);
        }
    }

    private void acceptResponse(DecodedMessage decoded) {
        Optional<UUID> correlationId = decoded.correlationId();
        if (correlationId.isEmpty()) {
            return;
        }

        PendingRequest<?> pending;
        synchronized (lock) {
            pending = pendingRequests.get(correlationId.get());
            if (pending == null
                    || pending.responseType != decoded.message().getClass()
                    || !sourceMatches(pending.target, decoded.sourceInstance())) {
                return;
            }
            pendingRequests.remove(correlationId.get());
        }
        pending.cancelTimeout();
        completeResponse(pending, decoded.message());
    }

    private <R extends NetworkMessage> void completeResponse(
            PendingRequest<R> pending, NetworkMessage message) {
        completionDispatcher.complete(pending.result, pending.responseType.cast(message));
    }

    private void timeOut(UUID correlationId, PendingRequest<?> pending) {
        if (!remove(correlationId, pending)) {
            return;
        }
        completionDispatcher.fail(
                pending.result,
                new RequestTimeoutException(
                        "request " + correlationId + " timed out"));
    }

    private void failIfPending(
            UUID correlationId, PendingRequest<?> pending, Throwable failure) {
        if (remove(correlationId, pending)) {
            completionDispatcher.fail(
                    pending.result, AsyncFailures.unwrap(failure));
        }
    }

    private boolean remove(UUID correlationId, PendingRequest<?> expected) {
        synchronized (lock) {
            if (!pendingRequests.remove(correlationId, expected)) {
                return false;
            }
        }
        expected.cancelTimeout();
        return true;
    }

    private boolean isPending(UUID correlationId, PendingRequest<?> expected) {
        synchronized (lock) {
            return pendingRequests.get(correlationId) == expected;
        }
    }

    private UUID nextCorrelationId() {
        UUID correlationId;
        do {
            correlationId = UUID.randomUUID();
        } while (pendingRequests.containsKey(correlationId));
        return correlationId;
    }

    private <R extends NetworkMessage> CompletableFuture<R> failedFuture(
            Throwable failure) {
        try {
            CompletableFuture<R> future = completionDispatcher.newFuture();
            completionDispatcher.fail(future, failure);
            return future;
        } catch (IllegalStateException closedDispatcher) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static boolean sourceMatches(NetworkTarget target, String sourceInstance) {
        return !(target instanceof NetworkTarget.Instance instance)
                || instance.id().equals(sourceInstance);
    }

    private static final class PendingRequest<R extends NetworkMessage> {

        private final UUID correlationId;
        private final Class<R> responseType;
        private final NetworkTarget target;
        private final CompletableFuture<R> result;
        private volatile ScheduledFuture<?> timeoutTask;

        private PendingRequest(
                UUID correlationId,
                Class<R> responseType,
                NetworkTarget target,
                CompletableFuture<R> result) {
            this.correlationId = correlationId;
            this.responseType = responseType;
            this.target = target;
            this.result = result;
        }

        private void cancelTimeout() {
            ScheduledFuture<?> task = timeoutTask;
            if (task != null) {
                task.cancel(false);
            }
        }
    }
}
