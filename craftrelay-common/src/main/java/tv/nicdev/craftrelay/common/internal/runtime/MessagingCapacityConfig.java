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

/**
 * Shared bounded messaging capacities.
 *
 * @param dispatchQueueCapacity maximum queued work per isolated lane
 * @param maximumListeners maximum local runtime listeners
 * @param maximumCustomRegistrations maximum public custom message registrations
 * @param maximumCustomRequestHandlers maximum public custom request handlers
 */
public record MessagingCapacityConfig(
        int dispatchQueueCapacity,
        int maximumListeners,
        int maximumCustomRegistrations,
        int maximumCustomRequestHandlers) {

    public static final int MAXIMUM_CONFIGURED_CAPACITY = 65_536;

    public MessagingCapacityConfig {
        validate(dispatchQueueCapacity, "dispatchQueueCapacity");
        validate(maximumListeners, "maximumListeners");
        validate(maximumCustomRegistrations, "maximumCustomRegistrations");
        validate(maximumCustomRequestHandlers, "maximumCustomRequestHandlers");
    }

    /** Returns production defaults. */
    public static MessagingCapacityConfig defaults() {
        return new MessagingCapacityConfig(1_024, 4_096, 1_024, 1_024);
    }

    private static void validate(int value, String name) {
        if (value <= 0 || value > MAXIMUM_CONFIGURED_CAPACITY) {
            throw new IllegalArgumentException(
                    name + " must be between 1 and " + MAXIMUM_CONFIGURED_CAPACITY);
        }
    }
}
