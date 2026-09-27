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
package tv.nicdev.craftrelay.transport.redis.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.schema.CoreSchema;
import tv.nicdev.craftrelay.common.internal.presence.InstancePresenceConfig;
import tv.nicdev.craftrelay.common.internal.presence.PlayerPresenceConfig;
import tv.nicdev.craftrelay.common.internal.request.RequestRuntimeConfig;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingCapacityConfig;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntimeConfig;
import tv.nicdev.craftrelay.transport.redis.RedisTransportConfig;

/**
 * Strict safe-YAML loader for the shared Paper and Velocity configuration.
 */
public final class YamlCraftRelayConfigLoader {

    static final String INSTANCE_ID_ENV = "CRAFTRELAY_INSTANCE_ID";
    static final String INSTANCE_GROUP_ENV = "CRAFTRELAY_INSTANCE_GROUP";
    static final String REDIS_USERNAME_ENV = "CRAFTRELAY_REDIS_USERNAME";
    static final String REDIS_PASSWORD_ENV = "CRAFTRELAY_REDIS_PASSWORD";

    private static final Pattern SHORT_DURATION =
            Pattern.compile("([1-9][0-9]*)(ms|s|m|h)");
    private static final int CODE_POINT_LIMIT = 1_000_000;
    private static final int MAX_ALIASES = 10;

    private final Load yaml = new Load(LoadSettings.builder()
            .setLabel("CraftRelay config")
            .setSchema(new CoreSchema())
            .setCodePointLimit(CODE_POINT_LIMIT)
            .setMaxAliasesForCollections(MAX_ALIASES)
            .build());

    /**
     * Loads and validates one configuration file.
     *
     * @param path YAML file
     * @return validated node configuration
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if the document is structurally invalid
     */
    public CraftRelayRedisConfig load(Path path) throws IOException {
        return load(path, System.getenv());
    }

    CraftRelayRedisConfig load(Path path, Map<String, String> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        Object document;
        try (InputStream input = Files.newInputStream(path)) {
            document = yaml.loadFromInputStream(input);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid CraftRelay YAML");
        }

        Map<String, Object> root = mapping(document, "root");
        int configVersion = integer(root, "config-version");
        if (configVersion != 1) {
            throw new IllegalArgumentException(
                    "Unsupported CraftRelay config-version: " + configVersion);
        }
        requireKeys(root, "root", Set.of(
                "config-version", "instance", "redis", "messaging", "requests",
                "presence", "platform"));

        Map<String, Object> instance = section(root, "instance");
        requireKnownKeys(instance, "instance", Set.of("id", "group"));
        String instanceId = environment.containsKey(INSTANCE_ID_ENV)
                ? environmentText(environment, INSTANCE_ID_ENV)
                : instanceIdFromYaml(instance);
        Optional<String> group = environment.containsKey(INSTANCE_GROUP_ENV)
                ? environmentOptionalText(environment, INSTANCE_GROUP_ENV)
                : instance.containsKey("group")
                        ? optionalText(instance, "group")
                        : Optional.empty();
        validateInstanceId(instanceId, environment.containsKey(INSTANCE_ID_ENV)
                ? INSTANCE_ID_ENV
                : "instance.id");

        Map<String, Object> redis = section(root, "redis");
        requireKeys(redis, "redis", Set.of(
                "host", "port", "username", "password", "database", "tls",
                "connection-timeout"));

        Map<String, Object> messaging = section(root, "messaging");
        requireKeys(messaging, "messaging", Set.of(
                "prefix",
                "duplicate-cache-capacity",
                "dispatch-queue-capacity",
                "maximum-listeners",
                "maximum-custom-registrations",
                "maximum-custom-request-handlers"));

        Map<String, Object> requests = section(root, "requests");
        requireKeys(requests, "requests", Set.of("maximum-pending"));

        Map<String, Object> presence = section(root, "presence");
        requireKeys(presence, "presence", Set.of("instance", "player"));
        Map<String, Object> instancePresence = section(presence, "instance");
        requireKeys(instancePresence, "presence.instance", Set.of(
                "heartbeat-interval", "ttl", "cleanup-batch"));
        Map<String, Object> playerPresence = section(presence, "player");
        requireKeys(playerPresence, "presence.player", Set.of(
                "refresh-interval", "ttl", "batch-size"));

        Map<String, Object> platform = section(root, "platform");
        requireKeys(platform, "platform", Set.of(
                "shutdown-timeout", "login-unavailable-message",
                "duplicate-session-message"));

        String prefix = text(messaging, "prefix");
        Optional<String> redisUsername = environment.containsKey(REDIS_USERNAME_ENV)
                ? environmentOptionalText(environment, REDIS_USERNAME_ENV)
                : optionalText(redis, "username");
        Optional<String> redisPassword = environment.containsKey(REDIS_PASSWORD_ENV)
                ? Optional.of(environmentText(environment, REDIS_PASSWORD_ENV))
                : optionalText(redis, "password");
        CraftRelayRedisConfig result = new CraftRelayRedisConfig(
                instanceId,
                group,
                new RedisTransportConfig(
                        text(redis, "host"),
                        integer(redis, "port"),
                        redisUsername,
                        redisPassword,
                        integer(redis, "database"),
                        bool(redis, "tls"),
                        duration(redis, "connection-timeout")),
                new MessagingRuntimeConfig(
                        prefix,
                        integer(messaging, "duplicate-cache-capacity"),
                        new MessagingCapacityConfig(
                                integer(messaging, "dispatch-queue-capacity"),
                                integer(messaging, "maximum-listeners"),
                                integer(messaging, "maximum-custom-registrations"),
                                integer(messaging, "maximum-custom-request-handlers"))),
                new RequestRuntimeConfig(integer(requests, "maximum-pending")),
                new InstancePresenceConfig(
                        prefix,
                        duration(instancePresence, "heartbeat-interval"),
                        duration(instancePresence, "ttl"),
                        integer(instancePresence, "cleanup-batch")),
                new PlayerPresenceConfig(
                        prefix,
                        duration(playerPresence, "refresh-interval"),
                        duration(playerPresence, "ttl"),
                        integer(playerPresence, "batch-size")),
                duration(platform, "shutdown-timeout"),
                text(platform, "login-unavailable-message"),
                text(platform, "duplicate-session-message"));
        return result;
    }

    private static String environmentText(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must be set to a non-empty value");
        }
        return value;
    }

    private static Optional<String> environmentOptionalText(
            Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " must not be null");
        }
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static void validateInstanceId(String value, String source) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(source + " must not be empty");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(source + " must not have surrounding whitespace");
        }
        if ("change-me".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(source + " must be changed from 'change-me'");
        }
    }

    private static Map<String, Object> section(Map<String, Object> parent, String key) {
        return mapping(required(parent, key), key);
    }

    private static Map<String, Object> mapping(Object value, String path) {
        if (!(value instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException(path + " must be a mapping");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, entry) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException(path + " contains a non-string key");
            }
            result.put(text, entry);
        });
        return Collections.unmodifiableMap(result);
    }

    private static void requireKeys(
            Map<String, Object> values, String path, Set<String> allowed) {
        requireKnownKeys(values, path, allowed);
        for (String key : allowed) {
            if (!values.containsKey(key)) {
                throw new IllegalArgumentException("Missing configuration key: " + path + '.' + key);
            }
        }
    }

    private static void requireKnownKeys(
            Map<String, Object> values, String path, Set<String> allowed) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Unknown configuration key: " + path + '.' + key);
            }
        }
    }

    private static String instanceIdFromYaml(Map<String, Object> instance) {
        if (!instance.containsKey("id")) {
            throw new IllegalArgumentException("Missing configuration key: instance.id");
        }
        return text(instance, "id");
    }

    private static Object required(Map<String, Object> values, String key) {
        if (!values.containsKey(key)) {
            throw new IllegalArgumentException("Missing configuration key: " + key);
        }
        return values.get(key);
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = required(values, key);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return text;
    }

    private static Optional<String> optionalText(Map<String, Object> values, String key) {
        Object value = required(values, key);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " must be a string or null");
        }
        return Optional.of(text);
    }

    private static int integer(Map<String, Object> values, String key) {
        Object value = required(values, key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        long result = number.longValue();
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE
                || number.doubleValue() != result) {
            throw new IllegalArgumentException(key + " must be a 32-bit integer");
        }
        return (int) result;
    }

    private static boolean bool(Map<String, Object> values, String key) {
        Object value = required(values, key);
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return result;
    }

    private static Duration duration(Map<String, Object> values, String key) {
        String value = text(values, key);
        Matcher matcher = SHORT_DURATION.matcher(value);
        if (matcher.matches()) {
            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(key + " duration is too large", failure);
            }
            try {
                return switch (matcher.group(2)) {
                    case "ms" -> Duration.ofMillis(amount);
                    case "s" -> Duration.ofSeconds(amount);
                    case "m" -> Duration.ofMinutes(amount);
                    case "h" -> Duration.ofHours(amount);
                    default -> throw new IllegalStateException("Unhandled duration unit");
                };
            } catch (ArithmeticException failure) {
                throw new IllegalArgumentException(key + " duration is too large", failure);
            }
        }
        try {
            return Duration.parse(value);
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException(
                    key + " must be an ISO-8601 duration or use ms, s, m, or h", failure);
        }
    }
}
