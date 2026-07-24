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
package tv.nicdev.craftrelay.common.internal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.message.GlobalBroadcastMessage;
import tv.nicdev.craftrelay.api.message.PlayerLocationRequest;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.api.target.NetworkTargets;
import tv.nicdev.craftrelay.common.internal.protocol.DecodedMessage;
import tv.nicdev.craftrelay.common.internal.protocol.MessageCodec;
import tv.nicdev.craftrelay.common.internal.protocol.MessageCodecs;
import tv.nicdev.craftrelay.common.transport.TransportState;

class DefaultMessagingRuntimeTest {

    private static final String CHANNEL = "craftrelay:messages";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void lifecycleOperationsAreSharedAndReceivePathExistsBeforeConnection() {
        TestNetworkTransport transport = new TestNetworkTransport();
        CompletableFuture<Void> connection = new CompletableFuture<>();
        CompletableFuture<Void> transportClose = new CompletableFuture<>();
        transport.holdNextConnect(connection);
        transport.holdNextClose(transportClose);
        MessagingRuntime runtime = runtime(transport, NetworkInstanceType.PROXY, Optional.of("eu"));
        Subscription early =
                runtime.subscribe(GlobalBroadcastMessage.class, ignored -> {});

        CompletableFuture<Void> firstStart = runtime.start();
        CompletableFuture<Void> concurrentStart = runtime.start();

        assertSame(firstStart, concurrentStart);
        assertEquals(MessagingRuntimeState.STARTING, runtime.state());
        assertEquals(1, transport.listenerCount(CHANNEL));
        connection.complete(null);
        firstStart.join();
        assertEquals(MessagingRuntimeState.RUNNING, runtime.state());

        CompletableFuture<Void> firstClose = runtime.close();
        CompletableFuture<Void> concurrentClose = runtime.close();
        assertSame(firstClose, concurrentClose);
        assertEquals(MessagingRuntimeState.STOPPING, runtime.state());
        assertTrue(early.isClosed() || transport.listenerCount(CHANNEL) == 0);
        transportClose.complete(null);
        firstClose.join();
        assertEquals(MessagingRuntimeState.STOPPED, runtime.state());
    }

    @Test
    void failedStartReturnsToNewAndCanBeRetried() {
        TestNetworkTransport transport = new TestNetworkTransport();
        transport.failNextConnects(1);
        MessagingRuntime runtime = runtime(transport, NetworkInstanceType.SERVER, Optional.empty());
        try {
            assertThrows(CompletionException.class, () -> runtime.start().join());
            assertEquals(MessagingRuntimeState.NEW, runtime.state());
            assertEquals(0, transport.listenerCount(CHANNEL));

            runtime.start().join();

            assertEquals(MessagingRuntimeState.RUNNING, runtime.state());
            assertEquals(2, transport.connectCalls());
            assertEquals(1, transport.listenerCount(CHANNEL));
        } finally {
            runtime.close().join();
        }
    }

    @Test
    void closeDuringStartCompletesBothOperationsWithoutWaitingForConnection() {
        TestNetworkTransport transport = new TestNetworkTransport();
        CompletableFuture<Void> connection = new CompletableFuture<>();
        transport.holdNextConnect(connection);
        MessagingRuntime runtime = runtime(transport, NetworkInstanceType.SERVER, Optional.empty());

        CompletableFuture<Void> start = runtime.start();
        runtime.close().join();

        assertTrue(start.isCompletedExceptionally());
        assertEquals(MessagingRuntimeState.STOPPED, runtime.state());
        connection.complete(null);
    }

    @Test
    void subscriptionCancellationFailureCannotStrandShutdown() {
        TestNetworkTransport transport = new TestNetworkTransport();
        MessagingRuntime runtime = runtime(transport, NetworkInstanceType.SERVER, Optional.empty());
        runtime.start().join();
        transport.failNextSubscriptionClose();

        CompletableFuture<Void> close = runtime.close();

        assertThrows(CompletionException.class, close::join);
        assertEquals(MessagingRuntimeState.STOPPED, runtime.state());
        assertEquals(TransportState.CLOSED, transport.state());
        assertSame(close, runtime.close());
    }

    @Test
    void rejectsPublishOutsideRunningStateAndSubscriptionsAfterShutdown() {
        TestNetworkTransport transport = new TestNetworkTransport();
        MessagingRuntime runtime = runtime(transport, NetworkInstanceType.SERVER, Optional.empty());
        GlobalBroadcastMessage message = new GlobalBroadcastMessage("hello");

        assertThrows(
                CompletionException.class,
                () -> runtime.publish(NetworkTargets.allInstances(), message).join());
        runtime.start().join();
        runtime.close().join();
        assertThrows(
                CompletionException.class,
                () -> runtime.publish(NetworkTargets.allInstances(), message).join());
        assertThrows(
                IllegalStateException.class,
                () -> runtime.subscribe(GlobalBroadcastMessage.class, ignored -> {}));
    }

    @Test
    void routesAllProxyInstanceAndGroupTargetsIncludingSelfDelivery() {
        TestNetworkTransport transport = new TestNetworkTransport();
        MessagingRuntime runtime = runtime(transport, NetworkInstanceType.PROXY, Optional.of("eu"));
        List<String> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch barrier = new CountDownLatch(1);
        runtime.subscribe(GlobalBroadcastMessage.class, message -> {
            delivered.add(message.content());
            if (message.content().equals("barrier")) {
                barrier.countDown();
            }
        });
        runtime.start().join();

        try {
            runtime.publish(
                            NetworkTargets.allInstances(),
                            new GlobalBroadcastMessage("all"))
                    .join();
            runtime.publish(
                            NetworkTargets.allProxies(),
                            new GlobalBroadcastMessage("proxy"))
                    .join();
            runtime.publish(
                            NetworkTargets.allServers(),
                            new GlobalBroadcastMessage("wrong-server"))
                    .join();
            runtime.publish(
                            NetworkTargets.instance("proxy-eu-1"),
                            new GlobalBroadcastMessage("instance"))
                    .join();
            runtime.publish(
                            NetworkTargets.instance("proxy-us-1"),
                            new GlobalBroadcastMessage("wrong-instance"))
                    .join();
            runtime.publish(
                            NetworkTargets.group("eu"),
                            new GlobalBroadcastMessage("group"))
                    .join();
            runtime.publish(
                            NetworkTargets.group("us"),
                            new GlobalBroadcastMessage("wrong-group"))
                    .join();
            runtime.publish(
                            NetworkTargets.allInstances(),
                            new GlobalBroadcastMessage("barrier"))
                    .join();

            assertTrue(await(barrier));
            assertEquals(List.of("all", "proxy", "instance", "group", "barrier"), delivered);
        } finally {
            runtime.close().join();
        }
    }

    @Test
    void routesServerTargetsAndOnlyExactMessageTypes() {
        TestNetworkTransport transport = new TestNetworkTransport();
        MessagingRuntime runtime = runtime(transport, NetworkInstanceType.SERVER, Optional.empty());
        List<String> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch barrier = new CountDownLatch(1);
        runtime.subscribe(GlobalBroadcastMessage.class, message -> {
            delivered.add(message.content());
            if (message.content().equals("barrier")) {
                barrier.countDown();
            }
        });
        runtime.subscribe(PlayerLocationRequest.class, ignored -> {
            throw new AssertionError("wrong Java message type received");
        });
        runtime.start().join();

        try {
            runtime.publish(
                            NetworkTargets.allProxies(),
                            new GlobalBroadcastMessage("wrong-proxy"))
                    .join();
            runtime.publish(
                            NetworkTargets.allServers(),
                            new GlobalBroadcastMessage("server"))
                    .join();
            runtime.publish(
                            NetworkTargets.allInstances(),
                            new GlobalBroadcastMessage("barrier"))
                    .join();

            assertTrue(await(barrier));
            assertEquals(List.of("server", "barrier"), delivered);
        } finally {
            runtime.close().join();
        }
    }

    @Test
    void deduplicatesMessagesAndContinuesAfterMalformedInput() {
        TestNetworkTransport transport = new TestNetworkTransport();
        MessagingRuntime runtime = runtime(transport, NetworkInstanceType.SERVER, Optional.empty());
        List<String> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch second = new CountDownLatch(1);
        runtime.subscribe(GlobalBroadcastMessage.class, message -> {
            delivered.add(message.content());
            if (message.content().equals("second")) {
                second.countDown();
            }
        });
        runtime.start().join();

        try {
            runtime.publish(
                            NetworkTargets.allInstances(),
                            new GlobalBroadcastMessage("first"))
                    .join();
            transport.emit(CHANNEL, transport.publishedPayload(0));
            transport.emit(CHANNEL, "{broken".getBytes(StandardCharsets.UTF_8));
            runtime.publish(
                            NetworkTargets.allInstances(),
                            new GlobalBroadcastMessage("second"))
                    .join();

            assertTrue(await(second));
            assertEquals(List.of("first", "second"), delivered);
        } finally {
            runtime.close().join();
        }
    }

    @Test
    void metadataAwareDeliveryPreservesEnvelopeFields() {
        TestNetworkTransport transport = new TestNetworkTransport();
        DefaultMessagingRuntime runtime = new DefaultMessagingRuntime(
                transport,
                MessageCodecs.standard(),
                identity(NetworkInstanceType.SERVER, Optional.of("eu")),
                MessagingRuntimeConfig.defaults());
        AtomicReference<DecodedMessage> received = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        runtime.subscribeDecoded(decoded -> {
            received.set(decoded);
            delivered.countDown();
        });
        runtime.start().join();
        UUID correlationId = UUID.randomUUID();
        MessageCodec codec = MessageCodecs.standard();
        byte[] encoded = codec.encode(
                "proxy-1",
                NetworkTargets.group("eu"),
                new GlobalBroadcastMessage("metadata"),
                Optional.of(correlationId));

        try {
            transport.emit(CHANNEL, encoded);

            assertTrue(await(delivered));
            assertEquals("proxy-1", received.get().sourceInstance());
            assertEquals(Optional.of(correlationId), received.get().correlationId());
            assertEquals(NetworkTargets.group("eu"), received.get().target());
            assertTrue(!received.get().createdAt().isAfter(Instant.now()));
        } finally {
            runtime.close().join();
        }
    }

    @Test
    void unsubscribeIsIdempotentAndStopsOnlyItsRegistration() {
        TestNetworkTransport transport = new TestNetworkTransport();
        MessagingRuntime runtime = runtime(transport, NetworkInstanceType.SERVER, Optional.empty());
        List<String> removedDeliveries = new CopyOnWriteArrayList<>();
        CountDownLatch healthy = new CountDownLatch(1);
        Subscription removed = runtime.subscribe(
                GlobalBroadcastMessage.class,
                message -> removedDeliveries.add(message.content()));
        runtime.subscribe(GlobalBroadcastMessage.class, message -> healthy.countDown());
        removed.close();
        removed.close();
        runtime.start().join();

        try {
            runtime.publish(
                            NetworkTargets.allInstances(),
                            new GlobalBroadcastMessage("hello"))
                    .join();

            assertTrue(await(healthy));
            assertTrue(removed.isClosed());
            assertEquals(List.of(), removedDeliveries);
        } finally {
            runtime.close().join();
        }
    }

    @Test
    void slowAndFailingListenersCannotBlockOtherListeners() {
        TestNetworkTransport transport = new TestNetworkTransport();
        MessagingRuntime runtime = runtime(transport, NetworkInstanceType.SERVER, Optional.empty());
        CountDownLatch failingInvoked = new CountDownLatch(1);
        CountDownLatch healthyAfterFailure = new CountDownLatch(1);
        Subscription failing = runtime.subscribe(GlobalBroadcastMessage.class, ignored -> {
            failingInvoked.countDown();
            throw new IllegalStateException("expected listener failure");
        });
        runtime.subscribe(
                GlobalBroadcastMessage.class,
                ignored -> healthyAfterFailure.countDown());
        runtime.start().join();

        try {
            runtime.publish(
                            NetworkTargets.allInstances(),
                            new GlobalBroadcastMessage("failure"))
                    .join();
            assertTrue(await(failingInvoked));
            assertTrue(await(healthyAfterFailure));
            failing.close();

            CountDownLatch slowStarted = new CountDownLatch(1);
            CountDownLatch releaseSlow = new CountDownLatch(1);
            CountDownLatch slowAccepted =
                    new CountDownLatch(ListenerDispatcherCapacity.ACCEPTED_WHILE_BLOCKED);
            int messageCount = ListenerDispatcherCapacity.ACCEPTED_WHILE_BLOCKED + 5;
            CountDownLatch fastReceived = new CountDownLatch(messageCount);
            List<Integer> fastOrder = new CopyOnWriteArrayList<>();
            AtomicReference<Thread> slowThread = new AtomicReference<>();
            runtime.subscribe(GlobalBroadcastMessage.class, message -> {
                slowThread.compareAndSet(null, Thread.currentThread());
                slowAccepted.countDown();
                if (message.content().equals("1")) {
                    slowStarted.countDown();
                    awaitUninterruptibly(releaseSlow);
                }
            });
            runtime.subscribe(GlobalBroadcastMessage.class, message -> {
                fastOrder.add(Integer.parseInt(message.content()));
                fastReceived.countDown();
            });

            try {
                runtime.publish(
                                NetworkTargets.allInstances(),
                                new GlobalBroadcastMessage("1"))
                        .join();
                assertTrue(await(slowStarted));
                for (int index = 2; index <= messageCount; index++) {
                    runtime.publish(
                                    NetworkTargets.allInstances(),
                                    new GlobalBroadcastMessage(Integer.toString(index)))
                            .join();
                }

                assertTrue(await(fastReceived));
                assertTrue(slowThread.get().isVirtual());
                assertEquals(expectedSequence(messageCount), fastOrder);
            } finally {
                releaseSlow.countDown();
            }
            assertTrue(await(slowAccepted));
        } finally {
            runtime.close().join();
        }
    }

    private static MessagingRuntime runtime(
            TestNetworkTransport transport,
            NetworkInstanceType type,
            Optional<String> group) {
        return new DefaultMessagingRuntime(
                transport,
                MessageCodecs.standard(),
                identity(type, group),
                MessagingRuntimeConfig.defaults());
    }

    private static LocalInstanceIdentity identity(
            NetworkInstanceType type, Optional<String> group) {
        return new LocalInstanceIdentity(
                type == NetworkInstanceType.PROXY ? "proxy-eu-1" : "server-eu-1",
                type,
                group);
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<Integer> expectedSequence(int size) {
        List<Integer> expected = new ArrayList<>(size);
        for (int value = 1; value <= size; value++) {
            expected.add(value);
        }
        return expected;
    }

    private static final class ListenerDispatcherCapacity {

        private static final int ACCEPTED_WHILE_BLOCKED =
                tv.nicdev.craftrelay.common.internal.concurrent.ListenerDispatcher
                                .DEFAULT_QUEUE_CAPACITY
                        + 1;

        private ListenerDispatcherCapacity() {
        }
    }
}
