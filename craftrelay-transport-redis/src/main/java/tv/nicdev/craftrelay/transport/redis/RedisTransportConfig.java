package tv.nicdev.craftrelay.transport.redis;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable connection settings for {@link LettuceRedisTransport}.
 *
 * <p>This is an internal CraftRelay implementation type, not public plugin API.
 *
 * @param host Redis host name
 * @param port Redis TCP port
 * @param username optional Redis ACL user
 * @param password optional password
 * @param database Redis database index
 * @param ssl whether TLS is enabled
 * @param connectionTimeout positive connection timeout
 */
public record RedisTransportConfig(
        String host,
        int port,
        Optional<String> username,
        Optional<String> password,
        int database,
        boolean ssl,
        Duration connectionTimeout) {

    public RedisTransportConfig {
        host = requireText(host, "host");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        username = normalizeOptional(username, "username");
        password = Objects.requireNonNull(password, "password");
        if (username.isPresent() && password.isEmpty()) {
            throw new IllegalArgumentException("password is required when username is configured");
        }
        if (database < 0) {
            throw new IllegalArgumentException("database must not be negative");
        }
        connectionTimeout = Objects.requireNonNull(connectionTimeout, "connectionTimeout");
        if (connectionTimeout.isZero() || connectionTimeout.isNegative()) {
            throw new IllegalArgumentException("connectionTimeout must be positive");
        }
    }

    /**
     * Creates unauthenticated local Redis settings.
     *
     * @param port mapped Redis port
     * @return local configuration with a five-second timeout
     */
    public static RedisTransportConfig localhost(int port) {
        return new RedisTransportConfig(
                "127.0.0.1",
                port,
                Optional.empty(),
                Optional.empty(),
                0,
                false,
                Duration.ofSeconds(5));
    }

    /**
     * Returns a diagnostic representation without exposing the configured password.
     *
     * @return redacted configuration text
     */
    @Override
    public String toString() {
        return "RedisTransportConfig[host=" + host
                + ", port=" + port
                + ", username=" + username
                + ", password=" + (password.isPresent() ? "Optional[REDACTED]" : "Optional.empty")
                + ", database=" + database
                + ", ssl=" + ssl
                + ", connectionTimeout=" + connectionTimeout
                + ']';
    }

    private static Optional<String> normalizeOptional(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            return value;
        }
        return Optional.of(requireText(value.orElseThrow(), name));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
