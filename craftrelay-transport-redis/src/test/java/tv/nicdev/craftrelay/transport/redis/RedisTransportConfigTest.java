package tv.nicdev.craftrelay.transport.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RedisTransportConfigTest {

    @Test
    void acceptsValidConfiguration() {
        RedisTransportConfig config = new RedisTransportConfig(
                "redis.internal",
                6380,
                Optional.of("craftrelay"),
                Optional.of("secret"),
                2,
                true,
                Duration.ofSeconds(3));

        assertEquals("redis.internal", config.host());
        assertEquals(6380, config.port());
        assertEquals(Optional.of("craftrelay"), config.username());
        assertEquals(Optional.of("secret"), config.password());
        assertEquals(2, config.database());
        assertEquals(Duration.ofSeconds(3), config.connectionTimeout());
        assertTrue(config.toString().contains("REDACTED"));
        assertFalse(config.toString().contains("secret"));
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(
                NullPointerException.class,
                () -> config(null, 6379, Optional.empty(), Optional.empty(), 0, Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> config(" ", 6379, Optional.empty(), Optional.empty(), 0, Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> config("localhost", 0, Optional.empty(), Optional.empty(), 0, Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> config("localhost", 65_536, Optional.empty(), Optional.empty(), 0, Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> config("localhost", 6379, Optional.empty(), Optional.empty(), -1, Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> config("localhost", 6379, Optional.empty(), Optional.empty(), 0, Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> config(
                        "localhost",
                        6379,
                        Optional.of("user"),
                        Optional.empty(),
                        0,
                        Duration.ofSeconds(1)));
    }

    private static RedisTransportConfig config(
            String host,
            int port,
            Optional<String> username,
            Optional<String> password,
            int database,
            Duration timeout) {
        return new RedisTransportConfig(
                host, port, username, password, database, false, timeout);
    }
}
