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

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.CraftRelayState;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.api.target.NetworkTarget;
import tv.nicdev.craftrelay.common.internal.concurrent.AsyncFailures;
import tv.nicdev.craftrelay.common.internal.concurrent.FutureCompletionDispatcher;
import tv.nicdev.craftrelay.common.internal.request.PendingRequestManager;
import tv.nicdev.craftrelay.common.internal.request.RequestValidation;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntime;
import tv.nicdev.craftrelay.common.internal.state.NetworkStateProvider;

final class DefaultCraftRelayApi implements CraftRelayApi {

    private final DefaultCraftRelayNode node;
    private final MessagingRuntime runtime;
    private final PendingRequestManager requestManager;
    private final NetworkStateProvider stateProvider;
    private final FutureCompletionDispatcher completionDispatcher;

    DefaultCraftRelayApi(
            DefaultCraftRelayNode node,
            MessagingRuntime runtime,
            PendingRequestManager requestManager,
            NetworkStateProvider stateProvider,
            FutureCompletionDispatcher completionDispatcher) {
        this.node = node;
        this.runtime = runtime;
        this.requestManager = requestManager;
        this.stateProvider = stateProvider;
        this.completionDispatcher = completionDispatcher;
    }

    @Override
    public CompletableFuture<Void> publish(
            NetworkTarget target, NetworkMessage message) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
        if (!node.isAvailable()) {
            return unavailableFuture();
        }

        CompletableFuture<Void> publish;
        try {
            publish =
                    Objects.requireNonNull(
                            runtime.publish(target, message),
                            "runtime.publish()");
        } catch (RuntimeException failure) {
            return failedOperation(failure);
        }
        return relayOperation(publish, ignored -> null);
    }

    @Override
    public <M extends NetworkMessage> Subscription subscribe(
            Class<M> messageType, Consumer<? super M> listener) {
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(listener, "listener");
        if (!node.isAvailable()) {
            throw unavailable();
        }
        try {
            return runtime.subscribe(messageType, listener);
        } catch (IllegalStateException failure) {
            throw new ApiUnavailableException(
                    "CraftRelay API stopped while subscribing", failure);
        }
    }

    @Override
    public <R extends NetworkMessage> CompletableFuture<R> request(
            NetworkTarget target,
            NetworkMessage request,
            Class<R> responseType,
            Duration timeout) {
        Objects.requireNonNull(target, "target");
        RequestValidation.validateAndGetTimeoutNanos(
                request, responseType, timeout);
        if (!node.isAvailable()) {
            return unavailableFuture();
        }
        return requestManager.request(target, request, responseType, timeout);
    }

    @Override
    public CompletableFuture<Collection<NetworkInstance>> instances() {
        if (!node.isAvailable()) {
            return unavailableFuture();
        }
        CompletableFuture<? extends Collection<NetworkInstance>> instances;
        try {
            instances =
                    Objects.requireNonNull(
                            stateProvider.instances(),
                            "stateProvider.instances()");
        } catch (RuntimeException failure) {
            return failedOperation(failure);
        }
        return relayOperation(
                instances,
                snapshot -> {
                    Collection<NetworkInstance> values =
                            Objects.requireNonNull(snapshot, "instances result");
                    return List.copyOf(values);
                });
    }

    @Override
    public CompletableFuture<Optional<NetworkPlayer>> player(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!node.isAvailable()) {
            return unavailableFuture();
        }
        CompletableFuture<Optional<NetworkPlayer>> player;
        try {
            player =
                    Objects.requireNonNull(
                            stateProvider.player(playerId),
                            "stateProvider.player()");
        } catch (RuntimeException failure) {
            return failedOperation(failure);
        }
        return relayOperation(
                player,
                result -> Objects.requireNonNull(result, "player result"));
    }

    @Override
    public CraftRelayState state() {
        return node.apiState();
    }

    private <T> CompletableFuture<T> failedOperation(Throwable failure) {
        CompletableFuture<T> future;
        try {
            future = completionDispatcher.newFuture();
        } catch (IllegalStateException closed) {
            return CompletableFuture.failedFuture(unavailable());
        }
        completionDispatcher.fail(future, mapFailure(failure));
        return future;
    }

    private <S, T> CompletableFuture<T> relayOperation(
            CompletionStage<? extends S> source,
            Function<? super S, ? extends T> mapper) {
        try {
            return completionDispatcher.relay(
                    source, mapper, this::mapFailure);
        } catch (IllegalStateException closedDispatcher) {
            return unavailableFuture();
        }
    }

    private <T> CompletableFuture<T> unavailableFuture() {
        return CompletableFuture.failedFuture(unavailable());
    }

    private ApiUnavailableException unavailable() {
        return new ApiUnavailableException(
                "CraftRelay API is not available while state is " + state());
    }

    private Throwable mapFailure(Throwable failure) {
        Throwable unwrapped = AsyncFailures.unwrap(failure);
        if (unwrapped instanceof IllegalStateException && !node.isAvailable()) {
            return new ApiUnavailableException(
                    "CraftRelay API stopped during the operation", unwrapped);
        }
        return unwrapped;
    }

}
