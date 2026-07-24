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
package tv.nicdev.craftrelay.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.message.GlobalBroadcastMessage;
import tv.nicdev.craftrelay.api.message.PlayerLocationRequest;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.api.target.NetworkTargets;
import tv.nicdev.craftrelay.common.internal.runtime.LocalInstanceIdentity;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntime;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntimeConfig;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntimes;
import tv.nicdev.craftrelay.common.transport.TransportState;
import tv.nicdev.craftrelay.transport.redis.LettuceRedisTransport;
import tv.nicdev.craftrelay.transport.redis.RedisTransportConfig;

@Testcontainers
class RedisTransportIntegrationTest {

    private static final DockerImageName REDIS_IMAGE =
            DockerImageName.parse("redis:7.4.2-alpine");
    private static final DockerImageName TOXIPROXY_IMAGE =
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0");
    private static final int REDIS_PORT = 6379;
    private static final int PROXY_PORT = 8666;
    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(REDIS_PORT)
            .withNetwork(NETWORK)
            .withNetworkAliases("redis");

    @Container
    private static final ToxiproxyContainer TOXIPROXY =
            new ToxiproxyContainer(TOXIPROXY_IMAGE).withNetwork(NETWORK);

    private static Proxy redisProxy;

    private final List<LettuceRedisTransport> transports = new CopyOnWriteArrayList<>();
    private final List<MessagingRuntime> runtimes = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void createRedisProxy() throws IOException {
        ToxiproxyClient client =
                new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
        redisProxy =
                client.createProxy("redis", "0.0.0.0:" + PROXY_PORT, "redis:" + REDIS_PORT);
    }

    @AfterEach
    void closeTransports() {
        CompletableFuture.allOf(runtimes.stream()
                        .map(MessagingRuntime::close)
                        .toArray(CompletableFuture[]::new))
                .orTimeout(10, TimeUnit.SECONDS)
                .join();
        CompletableFuture.allOf(transports.stream()
                        .map(LettuceRedisTransport::close)
                        .toArray(CompletableFuture[]::new))
                .orTimeout(10, TimeUnit.SECONDS)
                .join();
    }

    @Test
    void messagingRuntimesRouteStandardMessagesWithoutCrossTalk() throws Exception {
        MessagingRuntime proxy = newRuntime(
                "proxy-eu-1", NetworkInstanceType.PROXY, Optional.of("eu"));
        MessagingRuntime server = newRuntime(
                "server-eu-1", NetworkInstanceType.SERVER, Optional.of("eu"));
        List<String> proxyDeliveries = new CopyOnWriteArrayList<>();
        List<String> serverDeliveries = new CopyOnWriteArrayList<>();
        CountDownLatch proxyBarrier = new CountDownLatch(1);
        CountDownLatch serverBarrier = new CountDownLatch(1);
        CountDownLatch locationRequest = new CountDownLatch(1);
        proxy.subscribe(GlobalBroadcastMessage.class, message -> {
            proxyDeliveries.add(message.content());
            if (message.content().equals("barrier")) {
                proxyBarrier.countDown();
            }
        });
        server.subscribe(GlobalBroadcastMessage.class, message -> {
            serverDeliveries.add(message.content());
            if (message.content().equals("barrier")) {
                serverBarrier.countDown();
            }
        });
        server.subscribe(PlayerLocationRequest.class, ignored -> locationRequest.countDown());
        CompletableFuture.allOf(proxy.start(), server.start())
                .orTimeout(10, TimeUnit.SECONDS)
                .join();

        proxy.publish(NetworkTargets.allInstances(), new GlobalBroadcastMessage("all"))
                .join();
        proxy.publish(NetworkTargets.allProxies(), new GlobalBroadcastMessage("proxies"))
                .join();
        proxy.publish(NetworkTargets.allServers(), new GlobalBroadcastMessage("servers"))
                .join();
        proxy.publish(
                        NetworkTargets.instance("server-eu-1"),
                        new GlobalBroadcastMessage("instance"))
                .join();
        proxy.publish(NetworkTargets.group("eu"), new GlobalBroadcastMessage("group"))
                .join();
        proxy.publish(
                        NetworkTargets.instance("missing"),
                        new GlobalBroadcastMessage("wrong-instance"))
                .join();
        proxy.publish(NetworkTargets.group("us"), new GlobalBroadcastMessage("wrong-group"))
                .join();
        proxy.publish(
                        NetworkTargets.instance("server-eu-1"),
                        new PlayerLocationRequest(java.util.UUID.randomUUID()))
                .join();
        proxy.publish(NetworkTargets.allInstances(), new GlobalBroadcastMessage("barrier"))
                .join();

        assertTrue(proxyBarrier.await(10, TimeUnit.SECONDS));
        assertTrue(serverBarrier.await(10, TimeUnit.SECONDS));
        assertTrue(locationRequest.await(10, TimeUnit.SECONDS));
        assertEquals(List.of("all", "proxies", "group", "barrier"), proxyDeliveries);
        assertEquals(
                List.of("all", "servers", "instance", "group", "barrier"),
                serverDeliveries);
    }

    @Test
    void slowRuntimeListenerDoesNotBlockPublisherOrAnotherRuntime() throws Exception {
        MessagingRuntime sender = newRuntime(
                "proxy-eu-1", NetworkInstanceType.PROXY, Optional.of("eu"));
        MessagingRuntime receiver = newRuntime(
                "server-eu-1", NetworkInstanceType.SERVER, Optional.of("eu"));
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch senderReceived = new CountDownLatch(1);
        receiver.subscribe(GlobalBroadcastMessage.class, ignored -> {
            slowStarted.countDown();
            try {
                releaseSlow.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            }
        });
        sender.subscribe(
                GlobalBroadcastMessage.class,
                ignored -> senderReceived.countDown());
        CompletableFuture.allOf(sender.start(), receiver.start()).join();

        try {
            sender.publish(
                            NetworkTargets.allInstances(),
                            new GlobalBroadcastMessage("non-blocking"))
                    .orTimeout(2, TimeUnit.SECONDS)
                    .join();

            assertTrue(slowStarted.await(5, TimeUnit.SECONDS));
            assertTrue(senderReceived.await(5, TimeUnit.SECONDS));
            sender.publish(
                            NetworkTargets.instance("proxy-eu-1"),
                            new GlobalBroadcastMessage("still-responsive"))
                    .orTimeout(2, TimeUnit.SECONDS)
                    .join();
        } finally {
            releaseSlow.countDown();
        }
    }

    @Test
    void messagingRuntimeContinuesAfterRedisReconnect() throws Exception {
        MessagingRuntime sender = newRuntime(
                "proxy-eu-1", NetworkInstanceType.PROXY, Optional.of("eu"));
        MessagingRuntime receiver = newRuntime(
                "server-eu-1", NetworkInstanceType.SERVER, Optional.of("eu"));
        CountDownLatch beforeInterruption = new CountDownLatch(1);
        CountDownLatch afterInterruption = new CountDownLatch(1);
        receiver.subscribe(GlobalBroadcastMessage.class, message -> {
            if (message.content().equals("before")) {
                beforeInterruption.countDown();
            } else if (message.content().equals("after")) {
                afterInterruption.countDown();
            }
        });
        CompletableFuture.allOf(sender.start(), receiver.start()).join();
        sender.publish(
                        NetworkTargets.instance("server-eu-1"),
                        new GlobalBroadcastMessage("before"))
                .join();
        assertTrue(beforeInterruption.await(5, TimeUnit.SECONDS));

        redisProxy.disable();
        try {
            await(
                    () -> transports.stream()
                            .anyMatch(transport -> transport.state() == TransportState.CONNECTING),
                    Duration.ofSeconds(10));
        } finally {
            redisProxy.enable();
        }
        await(
                () -> transports.stream()
                        .allMatch(transport -> transport.state() == TransportState.CONNECTED),
                Duration.ofSeconds(20));
        retryUntil(
                        () -> sender.publish(
                                        NetworkTargets.instance("server-eu-1"),
                                        new GlobalBroadcastMessage("after"))
                                .thenApply(ignored -> true),
                        Duration.ofSeconds(10),
                        "runtime publish did not recover")
                .join();

        assertTrue(afterInterruption.await(10, TimeUnit.SECONDS));
    }

    @Test
    void exchangesDefensiveBinaryPayloadsAndIsolatesChannelsAndListeners() throws Exception {
        LettuceRedisTransport sender = connectedTransport();
        LettuceRedisTransport receiver = newTransport();
        byte[] expected = {(byte) 0xFF, 0, 42, (byte) 0x80};
        byte[] published = expected.clone();
        CountDownLatch binaryReceived = new CountDownLatch(2);
        CountDownLatch emptyReceived = new CountDownLatch(1);
        List<byte[]> received = new CopyOnWriteArrayList<>();

        receiver.subscribe("craftrelay:binary", (channel, payload) -> {
            received.add(payload);
            payload[0] = 1;
            binaryReceived.countDown();
        });
        receiver.subscribe("craftrelay:binary", (channel, payload) -> {
            received.add(payload);
            binaryReceived.countDown();
        });
        receiver.subscribe("craftrelay:empty", (channel, payload) -> {
            assertEquals(0, payload.length);
            emptyReceived.countDown();
        });
        receiver.subscribe("craftrelay:other", (channel, payload) -> {
            throw new AssertionError("message crossed channel boundary");
        });
        receiver.connect().orTimeout(10, TimeUnit.SECONDS).join();

        sender.publish("craftrelay:binary", published).join();
        published[0] = 7;
        sender.publish("craftrelay:empty", new byte[0]).join();

        assertTrue(binaryReceived.await(5, TimeUnit.SECONDS));
        assertTrue(emptyReceived.await(5, TimeUnit.SECONDS));
        assertEquals(2, received.size());
        assertTrue(received.stream().anyMatch(payload -> java.util.Arrays.equals(expected, payload)));
        assertTrue(received.stream().anyMatch(payload -> payload[0] == 1));
    }

    @Test
    void listenerFailureDoesNotPreventOtherListenersAndClosedSubscriptionStopsDelivery()
            throws Exception {
        LettuceRedisTransport sender = connectedTransport();
        LettuceRedisTransport receiver = newTransport();
        CountDownLatch firstDelivery = new CountDownLatch(1);
        CountDownLatch healthyListener = new CountDownLatch(1);

        Subscription removed = receiver.subscribe(
                "craftrelay:events", (channel, payload) -> firstDelivery.countDown());
        receiver.subscribe("craftrelay:events", (channel, payload) -> {
            throw new IllegalStateException("expected test failure");
        });
        receiver.subscribe(
                "craftrelay:events", (channel, payload) -> healthyListener.countDown());
        receiver.connect().join();

        sender.publish("craftrelay:events", new byte[] {1}).join();
        assertTrue(firstDelivery.await(5, TimeUnit.SECONDS));
        assertTrue(healthyListener.await(5, TimeUnit.SECONDS));

        removed.close();
        CountDownLatch temporaryActive = new CountDownLatch(1);
        CountDownLatch unexpectedDelivery = new CountDownLatch(1);
        Subscription temporary = receiver.subscribe(
                "craftrelay:temporary", (channel, payload) -> {
                    if (payload[0] == 1) {
                        temporaryActive.countDown();
                    } else {
                        unexpectedDelivery.countDown();
                    }
                });
        publishUntilReceived(
                sender,
                "craftrelay:temporary",
                new byte[] {1},
                temporaryActive,
                Duration.ofSeconds(5));
        temporary.close();
        sender.publish("craftrelay:temporary", new byte[] {2}).join();
        assertFalse(unexpectedDelivery.await(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void blockingListenerDoesNotBlockRedisIoOrOtherListeners() throws Exception {
        LettuceRedisTransport sender = connectedTransport();
        LettuceRedisTransport receiver = newTransport();
        CountDownLatch blockingListenerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlockingListener = new CountDownLatch(1);
        CountDownLatch independentListenerReceived = new CountDownLatch(1);
        AtomicReference<Thread> callbackThread = new AtomicReference<>();

        receiver.subscribe("craftrelay:nonblocking", (channel, payload) -> {
            callbackThread.set(Thread.currentThread());
            blockingListenerStarted.countDown();
            try {
                releaseBlockingListener.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            }
        });
        receiver.subscribe(
                "craftrelay:nonblocking",
                (channel, payload) -> independentListenerReceived.countDown());
        receiver.connect().join();

        try {
            sender.publish("craftrelay:nonblocking", new byte[] {1}).join();
            assertTrue(blockingListenerStarted.await(5, TimeUnit.SECONDS));
            assertTrue(callbackThread.get().isVirtual());
            assertFalse(callbackThread.get().getName().contains("lettuce"));
            assertTrue(independentListenerReceived.await(5, TimeUnit.SECONDS));
            sender.publish("craftrelay:still-responsive", new byte[] {2})
                    .orTimeout(2, TimeUnit.SECONDS)
                    .join();
        } finally {
            releaseBlockingListener.countDown();
        }
    }

    @Test
    void reconnectsAfterTemporaryNetworkInterruption() throws Exception {
        LettuceRedisTransport sender = connectedTransport();
        LettuceRedisTransport receiver = newTransport();
        CountDownLatch beforeRestart = new CountDownLatch(1);
        CountDownLatch afterRestart = new CountDownLatch(1);
        receiver.subscribe("craftrelay:reconnect", (channel, payload) -> {
            if (payload[0] == 1) {
                beforeRestart.countDown();
            } else {
                afterRestart.countDown();
            }
        });
        receiver.connect().join();
        sender.publish("craftrelay:reconnect", new byte[] {1}).join();
        assertTrue(beforeRestart.await(5, TimeUnit.SECONDS));

        redisProxy.disable();
        CompletableFuture<Void> reconnectReady;
        try {
            await(
                    () -> receiver.state() == TransportState.CONNECTING,
                    Duration.ofSeconds(10));
            reconnectReady = receiver.connect();
            assertFalse(reconnectReady.isDone());
        } finally {
            redisProxy.enable();
        }
        reconnectReady.orTimeout(20, TimeUnit.SECONDS).join();
        await(
                () -> sender.state() == TransportState.CONNECTED
                        && receiver.state() == TransportState.CONNECTED,
                Duration.ofSeconds(20));
        publishEventually(sender, "craftrelay:reconnect", new byte[] {2}, Duration.ofSeconds(10));

        assertTrue(afterRestart.await(10, TimeUnit.SECONDS));
    }

    @Test
    void failedConnectionCompletesWithinConfiguredDeadline() {
        RedisTransportConfig config = new RedisTransportConfig(
                TOXIPROXY.getHost(),
                1,
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                0,
                false,
                Duration.ofMillis(250));
        LettuceRedisTransport transport = new LettuceRedisTransport(config);
        transports.add(transport);

        assertThrows(
                RuntimeException.class,
                () -> transport.connect().orTimeout(5, TimeUnit.SECONDS).join());
    }

    private LettuceRedisTransport connectedTransport() {
        LettuceRedisTransport transport = newTransport();
        transport.connect().orTimeout(10, TimeUnit.SECONDS).join();
        return transport;
    }

    private LettuceRedisTransport newTransport() {
        RedisTransportConfig config = new RedisTransportConfig(
                TOXIPROXY.getHost(),
                TOXIPROXY.getMappedPort(PROXY_PORT),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                0,
                false,
                Duration.ofSeconds(5));
        LettuceRedisTransport transport = new LettuceRedisTransport(config);
        transports.add(transport);
        return transport;
    }

    private MessagingRuntime newRuntime(
            String instanceId, NetworkInstanceType type, Optional<String> group) {
        LettuceRedisTransport transport = newTransport();
        MessagingRuntime runtime = MessagingRuntimes.create(
                transport,
                new LocalInstanceIdentity(instanceId, type, group),
                MessagingRuntimeConfig.defaults());
        runtimes.add(runtime);
        return runtime;
    }

    private static void publishEventually(
            LettuceRedisTransport transport, String channel, byte[] payload, Duration timeout) {
        retryUntil(
                        () -> transport.publish(channel, payload).thenApply(ignored -> true),
                        timeout,
                        "publish did not recover")
                .join();
    }

    private static void publishUntilReceived(
            LettuceRedisTransport transport,
            String channel,
            byte[] payload,
            CountDownLatch received,
            Duration timeout) {
        retryUntil(
                        () -> transport.publish(channel, payload)
                                .thenApply(ignored -> received.getCount() == 0),
                        timeout,
                        "message was not received")
                .join();
    }

    private static void await(BooleanSupplier condition, Duration timeout) {
        retryUntil(
                        () -> CompletableFuture.completedFuture(condition.getAsBoolean()),
                        timeout,
                        "condition was not met")
                .join();
    }

    private static CompletableFuture<Void> retryUntil(
            Supplier<CompletableFuture<Boolean>> attempt,
            Duration timeout,
            String failureMessage) {
        return retryUntil(attempt, System.nanoTime() + timeout.toNanos(), timeout, failureMessage);
    }

    private static CompletableFuture<Void> retryUntil(
            Supplier<CompletableFuture<Boolean>> attempt,
            long deadline,
            Duration timeout,
            String failureMessage) {
        CompletableFuture<Boolean> result;
        try {
            result = attempt.get();
        } catch (RuntimeException failure) {
            result = CompletableFuture.failedFuture(failure);
        }

        return result.<CompletableFuture<Void>>handle((successful, failure) -> {
                    if (failure == null && Boolean.TRUE.equals(successful)) {
                        return CompletableFuture.completedFuture(null);
                    }
                    if (System.nanoTime() >= deadline) {
                        return CompletableFuture.failedFuture(
                                new AssertionError(failureMessage + " within " + timeout, failure));
                    }
                    return CompletableFuture.runAsync(
                                    () -> {},
                                    CompletableFuture.delayedExecutor(
                                            100, TimeUnit.MILLISECONDS))
                            .thenCompose(ignored ->
                                    retryUntil(attempt, deadline, timeout, failureMessage));
                })
                .thenCompose(next -> next);
    }
}
