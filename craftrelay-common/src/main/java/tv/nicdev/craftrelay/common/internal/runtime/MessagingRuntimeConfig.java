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

import java.util.Objects;

/**
 * Immutable messaging-runtime settings.
 *
 * @param channelPrefix Redis-independent transport channel prefix
 * @param duplicateCacheCapacity maximum remembered message IDs
 */
public record MessagingRuntimeConfig(String channelPrefix, int duplicateCacheCapacity) {

    /** Default channel prefix. */
    public static final String DEFAULT_CHANNEL_PREFIX = "craftrelay";

    /** Default number of remembered message IDs. */
    public static final int DEFAULT_DUPLICATE_CACHE_CAPACITY = 10_000;

    /** Creates validated runtime settings. */
    public MessagingRuntimeConfig {
        Objects.requireNonNull(channelPrefix, "channelPrefix");
        if (channelPrefix.isBlank()) {
            throw new IllegalArgumentException("channelPrefix must not be blank");
        }
        if (!channelPrefix.equals(channelPrefix.strip())) {
            throw new IllegalArgumentException("channelPrefix must not have surrounding whitespace");
        }
        if (duplicateCacheCapacity <= 0) {
            throw new IllegalArgumentException("duplicateCacheCapacity must be positive");
        }
    }

    /**
     * Returns standard runtime settings.
     *
     * @return standard settings
     */
    public static MessagingRuntimeConfig defaults() {
        return new MessagingRuntimeConfig(
                DEFAULT_CHANNEL_PREFIX, DEFAULT_DUPLICATE_CACHE_CAPACITY);
    }

    /**
     * Returns the single channel used for CraftRelay envelopes.
     *
     * @return message channel
     */
    public String messageChannel() {
        return channelPrefix + ":messages";
    }
}
