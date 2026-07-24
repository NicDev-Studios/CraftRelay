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
package tv.nicdev.craftrelay.common.internal.node;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.CraftRelayState;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.message.PlayerLocationRequest;
import tv.nicdev.craftrelay.api.message.PlayerLocationResponse;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.common.internal.concurrent.AsyncFailures;
import tv.nicdev.craftrelay.common.internal.concurrent.FutureCompletionDispatcher;
import tv.nicdev.craftrelay.common.internal.request.PendingRequestManager;
import tv.nicdev.craftrelay.common.internal.request.RequestHandlerRegistries;
import tv.nicdev.craftrelay.common.internal.request.RequestHandlerRegistry;
import tv.nicdev.craftrelay.common.internal.request.RequestRuntimeConfig;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntime;
import tv.nicdev.craftrelay.common.internal.state.NetworkStateProvider;

final class DefaultCraftRelayNode implements CraftRelayNode {

    private final Object lifecycleLock = new Object();
    private final MessagingRuntime runtime;
    private final NetworkStateProvider stateProvider;
    private final FutureCompletionDispatcher completionDispatcher =
            new FutureCompletionDispatcher("craftrelay-api-completion-");
    private final PendingRequestManager requestManager;
    private final RequestHandlerRegistry requestHandlers;
    private final CraftRelayApi api;

    private volatile NodeState state = NodeState.NEW;
    private CompletableFuture<Void> startFuture;
    private CompletableFuture<Void> closeFuture;

    DefaultCraftRelayNode(
            MessagingRuntime runtime,
            RequestRuntimeConfig requestConfig,
            NetworkStateProvider stateProvider) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.stateProvider = Objects.requireNonNull(stateProvider, "stateProvider");
        requestManager =
                new PendingRequestManager(runtime, requestConfig, completionDispatcher);
        requestHandlers =
                RequestHandlerRegistries.create(runtime, completionDispatcher);
        requestHandlers.register(
                PlayerLocationRequest.class,
                (request, context) -> locationResponse(request));
        api =
                new DefaultCraftRelayApi(
                        this,
                        runtime,
                        requestManager,
                        stateProvider,
                        completionDispatcher);
    }

    @Override
    public CompletableFuture<Void> start() {
        CompletableFuture<Void> operation;
        synchronized (lifecycleLock) {
            if (state == NodeState.RUNNING || state == NodeState.STARTING) {
                return startFuture;
            }
            if (state == NodeState.STOPPING || state == NodeState.STOPPED) {
                return CompletableFuture.failedFuture(
                        new ApiUnavailableException(
                                "CraftRelay node is stopping or stopped"));
            }
            state = NodeState.STARTING;
            operation = completionDispatcher.newFuture();
            startFuture = operation;
        }

        CompletableFuture<Void> runtimeStart;
        try {
            runtimeStart = Objects.requireNonNull(runtime.start(), "runtime.start()");
        } catch (RuntimeException failure) {
            completeStart(operation, failure);
            return operation;
        }
        runtimeStart.whenComplete(
                (ignored, failure) -> completeStart(operation, failure));
        return operation;
    }

    @Override
    public CraftRelayApi api() {
        return api;
    }

    @Override
    public RequestHandlerRegistry requestHandlers() {
        return requestHandlers;
    }

    @Override
    public CompletableFuture<Void> close() {
        CompletableFuture<Void> operation;
        synchronized (lifecycleLock) {
            if (state == NodeState.STOPPING || state == NodeState.STOPPED) {
                return closeFuture;
            }
            state = NodeState.STOPPING;
            operation = new CompletableFuture<>();
            closeFuture = operation;
        }

        Throwable cleanupFailure = null;
        try {
            requestHandlers.close();
        } catch (Throwable failure) {
            cleanupFailure = AsyncFailures.merge(cleanupFailure, failure);
        }
        try {
            requestManager.close();
        } catch (Throwable failure) {
            cleanupFailure = AsyncFailures.merge(cleanupFailure, failure);
        }

        CompletableFuture<Void> runtimeClose;
        try {
            runtimeClose = Objects.requireNonNull(runtime.close(), "runtime.close()");
        } catch (Throwable failure) {
            finishClose(
                    operation,
                    AsyncFailures.merge(cleanupFailure, failure));
            return operation;
        }
        Throwable priorFailure = cleanupFailure;
        runtimeClose.whenComplete(
                (ignored, failure) ->
                        finishClose(
                                operation,
                                AsyncFailures.merge(priorFailure, failure)));
        return operation;
    }

    CraftRelayState apiState() {
        return switch (state) {
            case NEW, STARTING -> CraftRelayState.INITIALIZING;
            case RUNNING -> CraftRelayState.AVAILABLE;
            case STOPPING -> CraftRelayState.STOPPING;
            case STOPPED -> CraftRelayState.STOPPED;
        };
    }

    boolean isAvailable() {
        return state == NodeState.RUNNING;
    }

    private CompletableFuture<PlayerLocationResponse> locationResponse(
            PlayerLocationRequest request) {
        CompletableFuture<Optional<NetworkPlayer>> playerFuture =
                Objects.requireNonNull(
                        stateProvider.player(request.playerId()),
                        "stateProvider.player()");
        return completionDispatcher.relay(
                playerFuture,
                player ->
                        new PlayerLocationResponse(
                                request.playerId(),
                                Objects.requireNonNull(player, "player result")),
                AsyncFailures::unwrap);
    }

    private void completeStart(
            CompletableFuture<Void> operation, Throwable failure) {
        Throwable completionFailure = failure;
        synchronized (lifecycleLock) {
            if (startFuture != operation) {
                return;
            }
            if (failure == null && state == NodeState.STARTING) {
                state = NodeState.RUNNING;
            } else {
                if (state == NodeState.STARTING) {
                    state = NodeState.NEW;
                }
                if (completionFailure == null) {
                    completionFailure =
                            new ApiUnavailableException(
                                    "CraftRelay node stopped during start");
                }
            }
        }
        if (completionFailure == null) {
            completionDispatcher.complete(operation, null);
        } else {
            completionDispatcher.fail(
                    operation, AsyncFailures.unwrap(completionFailure));
        }
    }

    private void finishClose(
            CompletableFuture<Void> operation, Throwable runtimeFailure) {
        ApiUnavailableException unavailable =
                new ApiUnavailableException("CraftRelay node has stopped");
        completionDispatcher.close(unavailable)
                .whenComplete(
                        (ignored, dispatcherFailure) -> {
                            Throwable failure =
                                    AsyncFailures.merge(
                                            AsyncFailures.unwrapNullable(runtimeFailure),
                                            AsyncFailures.unwrapNullable(dispatcherFailure));
                            synchronized (lifecycleLock) {
                                state = NodeState.STOPPED;
                            }
                            if (failure == null) {
                                operation.complete(null);
                            } else {
                                operation.completeExceptionally(failure);
                            }
                        });
    }

    private enum NodeState {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED
    }
}
