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
package tv.nicdev.craftrelay.common.internal.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.message.InstanceHeartbeatMessage;
import tv.nicdev.craftrelay.api.message.InstanceStartedMessage;
import tv.nicdev.craftrelay.api.message.InstanceStoppedMessage;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.common.internal.runtime.LocalInstanceIdentity;
import tv.nicdev.craftrelay.common.testing.TestMessagingRuntime;
import tv.nicdev.craftrelay.common.testing.TestNetworkInstanceStore;

class InstanceRegistryTest {

    private static final InstancePresenceConfig FAST_CONFIG =
            new InstancePresenceConfig(
                    "test", Duration.ofMillis(20), Duration.ofMillis(100), 8);

    @Test
    void startsHeartbeatsWithStableStartTimeAndDynamicPlayerCount() throws Exception {
        TestNetworkInstanceStore store = new TestNetworkInstanceStore();
        TestMessagingRuntime runtime = new TestMessagingRuntime();
        AtomicInteger players = new AtomicInteger(2);
        CountDownLatch heartbeat = new CountDownLatch(1);
        AtomicReference<NetworkInstance> startedSnapshot = new AtomicReference<>();
        runtime.onPublish(published -> {
            if (published.message() instanceof InstanceStartedMessage started) {
                startedSnapshot.set(started.instance());
            } else if (published.message() instanceof InstanceHeartbeatMessage) {
                heartbeat.countDown();
            }
        });
        InstanceRegistry registry = registry(store, runtime, players, ignored -> {});

        registry.connect().join();
        CompletableFuture<Void> firstStart = registry.start();
        assertSame(firstStart, registry.start());
        firstStart.join();
        NetworkInstance started = startedSnapshot.get();
        assertTrue(started != null);

        players.set(7);
        assertTrue(heartbeat.await(2, TimeUnit.SECONDS));
        NetworkInstance current = registry.instances().join().iterator().next();
        assertEquals(started.startedAt(), current.startedAt());
        assertEquals(7, current.onlinePlayerCount());
        assertTrue(current.lastHeartbeat().isAfter(started.lastHeartbeat())
                || current.lastHeartbeat().equals(started.lastHeartbeat()));

        registry.stop().join();
        assertInstanceOf(InstanceStoppedMessage.class, runtime.lastPublished().message());
        assertTrue(registry.instances().join().isEmpty());
        registry.close().join();
    }

    @Test
    void failedAnnouncementRollsBackLeaseAndAllowsRetry() {
        TestNetworkInstanceStore store = new TestNetworkInstanceStore();
        TestMessagingRuntime runtime = new TestMessagingRuntime();
        runtime.onPublish(published -> {
            if (published.message() instanceof InstanceStartedMessage) {
                throw new IllegalStateException("expected publish failure");
            }
        });
        InstanceRegistry registry = registry(store, runtime, new AtomicInteger(), ignored -> {});
        registry.connect().join();

        assertThrows(CompletionException.class, () -> registry.start().join());
        assertTrue(store.instances().join().isEmpty());

        runtime.onPublish(ignored -> {});
        registry.start().join();
        assertEquals(1, store.instances().join().size());
        registry.stop().join();
        registry.close().join();
    }

    @Test
    void lostLeaseIsReportedAndHeartbeatDoesNotOverlap() throws Exception {
        BlockingHeartbeatStore store = new BlockingHeartbeatStore();
        TestMessagingRuntime runtime = new TestMessagingRuntime();
        CountDownLatch leaseLost = new CountDownLatch(1);
        InstanceRegistry registry =
                new InstanceRegistry(
                        store,
                        runtime,
                        identity(),
                        FAST_CONFIG,
                        () -> 0,
                        ignored -> leaseLost.countDown());
        registry.connect().join();
        registry.start().join();

        assertTrue(store.firstHeartbeat.await(2, TimeUnit.SECONDS));
        CompletableFuture.runAsync(
                        () -> {},
                        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS))
                .join();
        assertEquals(1, store.heartbeatCalls.get());

        store.heartbeatResult.complete(false);
        assertTrue(leaseLost.await(2, TimeUnit.SECONDS));
        registry.stop().join();
        registry.close().join();
    }

    @Test
    void rejectedClaimNeverPublishesAStopForTheActualOwner() {
        TestNetworkInstanceStore store = new TestNetworkInstanceStore();
        Instant now = Instant.now();
        store.seed(
                new NetworkInstance(
                        "proxy-eu-1",
                        NetworkInstanceType.PROXY,
                        Optional.of("eu"),
                        now,
                        now,
                        0),
                "actual-owner");
        TestMessagingRuntime runtime = new TestMessagingRuntime();
        InstanceRegistry registry = registry(store, runtime, new AtomicInteger(), ignored -> {});
        registry.connect().join();

        assertThrows(CompletionException.class, () -> registry.start().join());
        registry.stop().join();

        assertTrue(runtime.lastPublished() == null);
        assertEquals(1, store.instances().join().size());
        registry.close().join();
    }

    private static InstanceRegistry registry(
            TestNetworkInstanceStore store,
            TestMessagingRuntime runtime,
            AtomicInteger players,
            java.util.function.Consumer<? super Throwable> leaseLossHandler) {
        return new InstanceRegistry(
                store, runtime, identity(), FAST_CONFIG, players::get, leaseLossHandler);
    }

    private static LocalInstanceIdentity identity() {
        return new LocalInstanceIdentity(
                "proxy-eu-1", NetworkInstanceType.PROXY, Optional.of("eu"));
    }

    private static final class BlockingHeartbeatStore
            extends DelegatingTestStore {

        private final AtomicInteger heartbeatCalls = new AtomicInteger();
        private final CountDownLatch firstHeartbeat = new CountDownLatch(1);
        private final CompletableFuture<Boolean> heartbeatResult = new CompletableFuture<>();

        @Override
        public CompletableFuture<Boolean> heartbeat(
                NetworkInstance instance, String leaseToken, Duration ttl) {
            heartbeatCalls.incrementAndGet();
            firstHeartbeat.countDown();
            return heartbeatResult;
        }
    }

    private static class DelegatingTestStore
            implements tv.nicdev.craftrelay.common.internal.state.NetworkInstanceStore {

        private final TestNetworkInstanceStore delegate = new TestNetworkInstanceStore();

        @Override
        public CompletableFuture<Void> connect() {
            return delegate.connect();
        }

        @Override
        public CompletableFuture<Boolean> claim(
                NetworkInstance instance, String leaseToken, Duration ttl) {
            return delegate.claim(instance, leaseToken, ttl);
        }

        @Override
        public CompletableFuture<Boolean> heartbeat(
                NetworkInstance instance, String leaseToken, Duration ttl) {
            return delegate.heartbeat(instance, leaseToken, ttl);
        }

        @Override
        public CompletableFuture<Boolean> release(String instanceId, String leaseToken) {
            return delegate.release(instanceId, leaseToken);
        }

        @Override
        public CompletableFuture<Void> cleanupExpired(int batchSize) {
            return delegate.cleanupExpired(batchSize);
        }

        @Override
        public CompletableFuture<? extends java.util.Collection<NetworkInstance>> instances() {
            return delegate.instances();
        }

        @Override
        public CompletableFuture<Void> close() {
            return delegate.close();
        }
    }
}
