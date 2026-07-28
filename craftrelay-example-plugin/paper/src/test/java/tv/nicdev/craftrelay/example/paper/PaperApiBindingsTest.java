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
package tv.nicdev.craftrelay.example.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.CraftRelayState;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.api.target.NetworkTarget;

class PaperApiBindingsTest {

    @Test
    void staleServiceRemovalCannotClearItsReplacement() {
        PaperApiBindings bindings = new PaperApiBindings();
        StubApi first = new StubApi(CraftRelayState.INITIALIZING);
        StubApi replacement = new StubApi(CraftRelayState.AVAILABLE);

        bindings.bind(first);
        bindings.bind(replacement);
        bindings.unbind(first);

        assertEquals(
                "CraftRelay state: AVAILABLE",
                bindings.commands().orElseThrow()
                        .execute(new String[] {"state"}).join().getFirst());
    }

    @Test
    void concurrentReadsObserveOnlyCompleteBindings() throws InterruptedException {
        PaperApiBindings bindings = new PaperApiBindings();
        StubApi first = new StubApi(CraftRelayState.INITIALIZING);
        StubApi replacement = new StubApi(CraftRelayState.AVAILABLE);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();

        Thread writer = Thread.ofVirtual().start(() -> {
            captureFailure(asynchronousFailure, () -> {
                await(start);
                for (int iteration = 0; iteration < 1_000; iteration++) {
                    bindings.bind((iteration & 1) == 0 ? first : replacement);
                }
            });
        });
        Thread reader = Thread.ofVirtual().start(() -> {
            captureFailure(asynchronousFailure, () -> {
                await(start);
                for (int iteration = 0; iteration < 1_000; iteration++) {
                    bindings.commands().ifPresent(commands -> {
                        String state =
                                commands.execute(new String[] {"state"}).join().getFirst();
                        assertTrue(state.endsWith("INITIALIZING")
                                || state.endsWith("AVAILABLE"));
                    });
                }
            });
        });

        start.countDown();
        writer.join();
        reader.join();
        if (asynchronousFailure.get() != null) {
            throw new AssertionError("Concurrent binding operation failed", asynchronousFailure.get());
        }
        bindings.clear();
        assertTrue(bindings.commands().isEmpty());
    }

    private static void captureFailure(
            AtomicReference<Throwable> failure,
            Runnable operation) {
        try {
            operation.run();
        } catch (Throwable thrown) {
            failure.compareAndSet(null, thrown);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private record StubApi(CraftRelayState state) implements CraftRelayApi {

        @Override
        public tv.nicdev.craftrelay.api.messaging.CustomMessaging customMessaging() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> publish(NetworkTarget target, NetworkMessage message) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <M extends NetworkMessage> Subscription subscribe(
                Class<M> messageType, Consumer<? super M> listener) {
            return Subscription.create(() -> { });
        }

        @Override
        public <R extends NetworkMessage> CompletableFuture<R> request(
                NetworkTarget target,
                NetworkMessage request,
                Class<R> responseType,
                Duration timeout) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<Collection<NetworkInstance>> instances() {
            return CompletableFuture.completedFuture(java.util.List.of());
        }

        @Override
        public CompletableFuture<Optional<NetworkPlayer>> player(UUID playerId) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }
}
