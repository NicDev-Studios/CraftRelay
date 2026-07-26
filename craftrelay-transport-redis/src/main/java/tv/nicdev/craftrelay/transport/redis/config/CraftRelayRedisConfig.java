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

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import tv.nicdev.craftrelay.common.internal.presence.InstancePresenceConfig;
import tv.nicdev.craftrelay.common.internal.presence.PlayerPresenceConfig;
import tv.nicdev.craftrelay.common.internal.request.RequestRuntimeConfig;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntimeConfig;
import tv.nicdev.craftrelay.transport.redis.RedisTransportConfig;

/**
 * Immutable configuration used to compose one Redis-backed CraftRelay node.
 *
 * <p>This is platform composition API and not part of the third-party CraftRelay API.
 *
 * @param instanceId stable node ID
 * @param group optional routing group
 * @param redis Redis connection settings
 * @param messaging messaging runtime settings
 * @param requests request runtime settings
 * @param instancePresence instance lease settings
 * @param playerPresence player session settings
 * @param shutdownTimeout maximum synchronous Paper shutdown wait
 * @param loginUnavailableMessage Velocity login message for unavailable presence
 * @param duplicateSessionMessage Velocity login message for an active duplicate session
 */
public record CraftRelayRedisConfig(
        String instanceId,
        Optional<String> group,
        RedisTransportConfig redis,
        MessagingRuntimeConfig messaging,
        RequestRuntimeConfig requests,
        InstancePresenceConfig instancePresence,
        PlayerPresenceConfig playerPresence,
        Duration shutdownTimeout,
        String loginUnavailableMessage,
        String duplicateSessionMessage) {

    /** Creates validated node configuration. */
    public CraftRelayRedisConfig {
        instanceId = requireText(instanceId, "instanceId");
        if ("change-me".equalsIgnoreCase(instanceId)) {
            throw new IllegalArgumentException("instanceId must be changed from 'change-me'");
        }
        group = normalizeOptional(group, "group");
        redis = Objects.requireNonNull(redis, "redis");
        messaging = Objects.requireNonNull(messaging, "messaging");
        requests = Objects.requireNonNull(requests, "requests");
        instancePresence = Objects.requireNonNull(instancePresence, "instancePresence");
        playerPresence = Objects.requireNonNull(playerPresence, "playerPresence");
        playerPresence.validateCompatible(instancePresence);
        if (!messaging.channelPrefix().equals(instancePresence.keyPrefix())) {
            throw new IllegalArgumentException(
                    "Messaging and presence prefixes must match");
        }
        shutdownTimeout = requirePositive(shutdownTimeout, "shutdownTimeout");
        loginUnavailableMessage =
                requireText(loginUnavailableMessage, "loginUnavailableMessage");
        duplicateSessionMessage =
                requireText(duplicateSessionMessage, "duplicateSessionMessage");
    }

    private static Optional<String> normalizeOptional(
            Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        return value.map(text -> requireText(text, name));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must not have surrounding whitespace");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            if (value.toMillis() < 1) {
                throw new IllegalArgumentException(name + " must be at least one millisecond");
            }
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(name + " is too large", failure);
        }
        return value;
    }
}
