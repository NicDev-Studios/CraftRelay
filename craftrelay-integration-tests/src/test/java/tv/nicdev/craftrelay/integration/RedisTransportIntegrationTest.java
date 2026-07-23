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

    @BeforeAll
    static void createRedisProxy() throws IOException {
        ToxiproxyClient client =
                new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
        redisProxy =
                client.createProxy("redis", "0.0.0.0:" + PROXY_PORT, "redis:" + REDIS_PORT);
    }

    @AfterEach
    void closeTransports() {
        CompletableFuture.allOf(transports.stream()
                        .map(LettuceRedisTransport::close)
                        .toArray(CompletableFuture[]::new))
                .orTimeout(10, TimeUnit.SECONDS)
                .join();
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
        await(
                () -> sender.state() == TransportState.CONNECTING
                        || receiver.state() == TransportState.CONNECTING,
                Duration.ofSeconds(10));
        redisProxy.enable();
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
