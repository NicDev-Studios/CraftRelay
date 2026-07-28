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
package tv.nicdev.craftrelay.example.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.CraftRelayState;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.api.target.NetworkTarget;

class VelocityApiBindingsTest {

    @Test
    void replacingApiClosesOnlyThePreviousSubscription() {
        VelocityApiBindings bindings = new VelocityApiBindings(message -> { });
        RecordingApi first = new RecordingApi();
        RecordingApi replacement = new RecordingApi();

        bindings.bind(first);
        bindings.bind(replacement);

        assertEquals(1, first.closedSubscriptions.get());
        assertEquals(0, replacement.closedSubscriptions.get());
        assertSame(replacement, bindings.current().orElseThrow().api());

        bindings.close();
        bindings.close();
        assertEquals(1, replacement.closedSubscriptions.get());
        assertTrue(bindings.current().isEmpty());
    }

    @Test
    void concurrentDuplicateReadyCallbacksCreateOneSubscription()
            throws InterruptedException {
        VelocityApiBindings bindings = new VelocityApiBindings(message -> { });
        RecordingApi api = new RecordingApi();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] callers = new Thread[32];
        for (int index = 0; index < callers.length; index++) {
            callers[index] = Thread.ofVirtual().start(() -> {
                await(start);
                bindings.bind(api);
            });
        }

        start.countDown();
        for (Thread caller : callers) {
            caller.join();
        }

        assertEquals(1, api.createdSubscriptions.get());
        bindings.close();
        assertEquals(1, api.closedSubscriptions.get());
    }

    @Test
    void shutdownRacingWithReadyCannotLeaveAnOpenBinding()
            throws InterruptedException {
        VelocityApiBindings bindings = new VelocityApiBindings(message -> { });
        RecordingApi api = new RecordingApi();
        CountDownLatch start = new CountDownLatch(1);
        Thread binder = Thread.ofVirtual().start(() -> {
            await(start);
            bindings.bind(api);
        });
        Thread closer = Thread.ofVirtual().start(() -> {
            await(start);
            bindings.close();
        });

        start.countDown();
        binder.join();
        closer.join();
        bindings.bind(new RecordingApi());

        assertTrue(bindings.current().isEmpty());
        assertEquals(api.createdSubscriptions.get(), api.closedSubscriptions.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static final class RecordingApi implements CraftRelayApi {

        private final AtomicInteger createdSubscriptions = new AtomicInteger();
        private final AtomicInteger closedSubscriptions = new AtomicInteger();

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
            createdSubscriptions.incrementAndGet();
            return Subscription.create(closedSubscriptions::incrementAndGet);
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

        @Override
        public CraftRelayState state() {
            return CraftRelayState.AVAILABLE;
        }
    }
}
