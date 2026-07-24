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

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable settings for distributed instance presence.
 *
 * @param keyPrefix non-blank Redis key namespace
 * @param heartbeatInterval positive delay between completed heartbeat attempts
 * @param instanceTtl lease lifetime, at least twice the heartbeat interval
 * @param cleanupBatch maximum expired index entries removed per cleanup
 */
public record InstancePresenceConfig(
        String keyPrefix,
        Duration heartbeatInterval,
        Duration instanceTtl,
        int cleanupBatch) {

    /** Creates validated presence settings. */
    public InstancePresenceConfig {
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        if (keyPrefix.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        heartbeatInterval = requirePositive(heartbeatInterval, "heartbeatInterval");
        instanceTtl = requirePositive(instanceTtl, "instanceTtl");
        if (instanceTtl.compareTo(heartbeatInterval.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException(
                    "instanceTtl must be at least twice heartbeatInterval");
        }
        if (cleanupBatch < 1) {
            throw new IllegalArgumentException("cleanupBatch must be positive");
        }
    }

    /** Returns defaults: {@code craftrelay}, 5 seconds, 20 seconds, and 512 entries. */
    public static InstancePresenceConfig defaults() {
        return new InstancePresenceConfig(
                "craftrelay", Duration.ofSeconds(5), Duration.ofSeconds(20), 512);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
