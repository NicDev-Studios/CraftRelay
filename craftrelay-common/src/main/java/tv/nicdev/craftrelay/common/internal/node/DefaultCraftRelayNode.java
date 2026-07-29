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
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.CraftRelayState;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.message.PlayerLocationRequest;
import tv.nicdev.craftrelay.api.message.PlayerLocationResponse;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.common.internal.concurrent.AsyncFailures;
import tv.nicdev.craftrelay.common.internal.concurrent.FutureCompletionDispatcher;
import tv.nicdev.craftrelay.common.internal.presence.InstancePresenceConfig;
import tv.nicdev.craftrelay.common.internal.presence.InstanceRegistry;
import tv.nicdev.craftrelay.common.internal.presence.NodeLease;
import tv.nicdev.craftrelay.common.internal.presence.PlayerPresence;
import tv.nicdev.craftrelay.common.internal.presence.PlayerPresenceConfig;
import tv.nicdev.craftrelay.common.internal.presence.PlayerOwnershipListener;
import tv.nicdev.craftrelay.common.internal.presence.PlayerRegistry;
import tv.nicdev.craftrelay.common.internal.request.PendingRequestManager;
import tv.nicdev.craftrelay.common.internal.request.RequestHandlerRegistries;
import tv.nicdev.craftrelay.common.internal.request.RequestHandlerRegistry;
import tv.nicdev.craftrelay.common.internal.request.RequestRuntimeConfig;
import tv.nicdev.craftrelay.common.internal.runtime.LocalInstanceIdentity;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntime;
import tv.nicdev.craftrelay.common.internal.state.NetworkPresenceStore;
import tv.nicdev.craftrelay.common.internal.state.PlayerSessionKey;

final class DefaultCraftRelayNode implements CraftRelayNode {

    private static final System.Logger LOGGER =
            System.getLogger(DefaultCraftRelayNode.class.getName());

    private final Object lifecycleLock = new Object();
    private final MessagingRuntime runtime;
    private final FutureCompletionDispatcher completionDispatcher =
            new FutureCompletionDispatcher("craftrelay-api-completion-");
    private final PendingRequestManager requestManager;
    private final RequestHandlerRegistry requestHandlers;
    private final DefaultCustomMessaging customMessaging;
    private final InstanceRegistry instanceRegistry;
    private final PlayerRegistry playerRegistry;
    private final PlayerOwnershipListener ownershipListener;
    private final CraftRelayApi api;

    private volatile NodeState state = NodeState.NEW;
    private CompletableFuture<Void> startFuture;
    private CompletableFuture<Void> closeFuture;

    DefaultCraftRelayNode(
            MessagingRuntime runtime,
            LocalInstanceIdentity identity,
            RequestRuntimeConfig requestConfig,
            InstancePresenceConfig instanceConfig,
            PlayerPresenceConfig playerConfig,
            NetworkPresenceStore presenceStore,
            IntSupplier onlinePlayerCount,
            PlayerOwnershipListener ownershipListener) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.ownershipListener =
                Objects.requireNonNull(ownershipListener, "ownershipListener");
        Objects.requireNonNull(instanceConfig, "instanceConfig");
        Objects.requireNonNull(playerConfig, "playerConfig")
                .validateCompatible(instanceConfig);
        Objects.requireNonNull(presenceStore, "presenceStore");
        LocalInstanceIdentity localIdentity = Objects.requireNonNull(identity, "identity");
        NodeLease nodeLease = NodeLease.create();
        playerRegistry =
                new PlayerRegistry(
                        presenceStore,
                        runtime,
                        localIdentity,
                        playerConfig,
                        nodeLease,
                        this::handlePlayerOwnershipLoss);
        requestManager =
                new PendingRequestManager(runtime, requestConfig, completionDispatcher);
        requestHandlers =
                RequestHandlerRegistries.create(runtime, completionDispatcher);
        requestHandlers.register(
                PlayerLocationRequest.class,
                (request, context) -> locationResponse(request));
        instanceRegistry =
                new InstanceRegistry(
                        presenceStore,
                        runtime,
                        localIdentity,
                        instanceConfig,
                        nodeLease,
                        localIdentity.instanceType()
                                        == tv.nicdev.craftrelay.api.model.NetworkInstanceType.PROXY
                                ? playerRegistry::onlinePlayerCount
                                : Objects.requireNonNull(onlinePlayerCount, "onlinePlayerCount"),
                        this::handleLeaseLoss);
        customMessaging = new DefaultCustomMessaging(this, runtime, requestHandlers);
        api =
                new DefaultCraftRelayApi(
                        this,
                        runtime,
                        requestManager,
                        instanceRegistry,
                        playerRegistry,
                        completionDispatcher,
                        customMessaging);
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

        CompletableFuture<Void> start;
        try {
            start = startStage(instanceRegistry::connect, "instanceRegistry.connect()")
                    .thenComposeAsync(
                            ignored -> startStage(runtime::start, "runtime.start()"),
                            completionDispatcher::execute)
                    .thenComposeAsync(
                            ignored -> startStage(instanceRegistry::start, "instanceRegistry.start()"),
                            completionDispatcher::execute)
                    .thenComposeAsync(
                            ignored -> startStage(playerRegistry::start, "playerRegistry.start()"),
                            completionDispatcher::execute);
        } catch (RuntimeException failure) {
            completeStart(operation, failure);
            return operation;
        }
        start.whenComplete((ignored, failure) -> completeStart(operation, failure));
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
    public PlayerPresence playerPresence() {
        return playerRegistry;
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

        closeStage(
                        null,
                        playerRegistry::stop,
                        "playerRegistry.stop()")
                .thenApply(failure ->
                        AsyncFailures.merge(failure, closeRequestSystem()))
                .thenCompose(failure -> closeStage(
                        failure, instanceRegistry::stop, "instanceRegistry.stop()"))
                .thenCompose(failure -> closeStage(
                        failure, runtime::close, "runtime.close()"))
                .thenCompose(failure -> closeStage(
                        failure, instanceRegistry::close, "instanceRegistry.close()"))
                .whenComplete((failure, chainFailure) -> finishClose(
                        operation,
                        AsyncFailures.merge(
                                failure, AsyncFailures.unwrapNullable(chainFailure))));
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
                        playerRegistry.player(request.playerId()),
                        "playerRegistry.player()");
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

    private Throwable closeRequestSystem() {
        Throwable failure = null;
        try {
            customMessaging.close();
        } catch (Throwable closeFailure) {
            failure = AsyncFailures.merge(failure, closeFailure);
        }
        try {
            requestHandlers.close();
        } catch (Throwable closeFailure) {
            failure = AsyncFailures.merge(failure, closeFailure);
        }
        try {
            requestManager.close();
        } catch (Throwable closeFailure) {
            failure = AsyncFailures.merge(failure, closeFailure);
        }
        return failure;
    }

    private static CompletableFuture<Throwable> closeStage(
            Throwable priorFailure,
            Supplier<CompletableFuture<Void>> closeAction,
            String description) {
        CompletableFuture<Void> close;
        try {
            close = requireFuture(closeAction.get(), description);
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(
                    AsyncFailures.merge(priorFailure, failure));
        }
        return close.handle((ignored, failure) ->
                AsyncFailures.merge(priorFailure, AsyncFailures.unwrapNullable(failure)));
    }

    private void finishClose(
            CompletableFuture<Void> operation, Throwable lifecycleFailure) {
        ApiUnavailableException unavailable =
                new ApiUnavailableException("CraftRelay node has stopped");
        completionDispatcher.close(unavailable)
                .whenComplete(
                        (ignored, dispatcherFailure) -> {
                            Throwable failure =
                                    AsyncFailures.merge(
                                            AsyncFailures.unwrapNullable(lifecycleFailure),
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

    private void handleLeaseLoss(Throwable failure) {
        LOGGER.log(
                System.Logger.Level.ERROR,
                "CraftRelay instance lease was lost; stopping node",
                failure);
        close();
    }

    private void handlePlayerOwnershipLoss(PlayerSessionKey session) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "Local player session ownership was lost: " + session);
        try {
            ownershipListener.onOwnershipLost(session);
        } catch (RuntimeException failure) {
            LOGGER.log(
                    System.Logger.Level.ERROR,
                    "Player ownership listener failed for " + session,
                    failure);
        }
    }

    private static <T> CompletableFuture<T> requireFuture(
            CompletableFuture<T> future, String description) {
        return Objects.requireNonNull(future, description);
    }

    private CompletableFuture<Void> startStage(
            Supplier<CompletableFuture<Void>> action, String description) {
        synchronized (lifecycleLock) {
            if (state != NodeState.STARTING) {
                return CompletableFuture.failedFuture(
                        new ApiUnavailableException("CraftRelay node stopped during start"));
            }
        }
        return requireFuture(action.get(), description);
    }

    private enum NodeState {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED
    }
}
