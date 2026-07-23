package tv.nicdev.craftrelay.transport.redis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.common.transport.TransportState;

class LettuceRedisTransportTest {

    @Test
    void validatesOperationsBeforeAccessingRedis() {
        LettuceRedisTransport transport =
                new LettuceRedisTransport(RedisTransportConfig.localhost(6379));
        try {
            assertThrows(NullPointerException.class, () -> transport.publish(null, new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> transport.publish(" ", new byte[0]));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> transport.publish("\uD800", new byte[0]));
            assertThrows(NullPointerException.class, () -> transport.publish("channel", null));
            assertThrows(
                    CompletionException.class,
                    () -> transport.publish("channel", new byte[0]).join());
            assertThrows(NullPointerException.class, () -> transport.subscribe(null, (c, p) -> {}));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> transport.subscribe("\t", (c, p) -> {}));
            assertThrows(NullPointerException.class, () -> transport.subscribe("channel", null));
        } finally {
            transport.close().join();
        }
    }

    @Test
    void subscriptionAndCloseAreIdempotentWithoutConnecting() {
        LettuceRedisTransport transport =
                new LettuceRedisTransport(RedisTransportConfig.localhost(6379));
        Subscription subscription = transport.subscribe("craftrelay:test", (channel, payload) -> {});

        subscription.close();
        assertDoesNotThrow(subscription::close);

        var firstClose = transport.close();
        var concurrentClose = transport.close();
        if (!firstClose.isDone()) {
            assertSame(firstClose, concurrentClose);
        }
        firstClose.join();
        assertEquals(TransportState.CLOSED, transport.state());
        assertTrue(transport.close().isDone());
        assertThrows(
                IllegalStateException.class,
                () -> transport.subscribe("craftrelay:test", (channel, payload) -> {}));
    }

    @Test
    void failedConnectCompletesExceptionallyAndCanBeClosed() {
        RedisTransportConfig config = new RedisTransportConfig(
                "127.0.0.1",
                1,
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                0,
                false,
                Duration.ofMillis(200));
        LettuceRedisTransport transport = new LettuceRedisTransport(config);

        assertThrows(CompletionException.class, () -> transport.connect().join());
        assertEquals(TransportState.NEW, transport.state());
        transport.close().join();
        assertEquals(TransportState.CLOSED, transport.state());
    }

    @Test
    void concurrentLocalSubscriptionsCanBeClosedSafely() {
        LettuceRedisTransport transport =
                new LettuceRedisTransport(RedisTransportConfig.localhost(6379));
        try {
            List<Subscription> subscriptions = IntStream.range(0, 250)
                    .parallel()
                    .mapToObj(index -> transport.subscribe(
                            "craftrelay:concurrent", (channel, payload) -> {}))
                    .toList();

            subscriptions.parallelStream().forEach(Subscription::close);
            subscriptions.parallelStream().forEach(Subscription::close);
            assertTrue(subscriptions.stream().allMatch(Subscription::isClosed));
        } finally {
            transport.close().join();
        }
    }
}
