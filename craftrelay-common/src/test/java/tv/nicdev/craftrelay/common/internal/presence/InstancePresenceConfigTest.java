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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class InstancePresenceConfigTest {

    @Test
    void exposesValidatedProductionDefaults() {
        InstancePresenceConfig defaults = InstancePresenceConfig.defaults();

        assertEquals("craftrelay", defaults.keyPrefix());
        assertEquals(Duration.ofSeconds(5), defaults.heartbeatInterval());
        assertEquals(Duration.ofSeconds(20), defaults.instanceTtl());
        assertEquals(512, defaults.cleanupBatch());
    }

    @Test
    void rejectsInvalidTimingAndCleanupValues() {
        assertThrows(
                NullPointerException.class,
                () -> new InstancePresenceConfig(
                        null, Duration.ofSeconds(1), Duration.ofSeconds(2), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> config(" ", Duration.ofSeconds(1), Duration.ofSeconds(2), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> config("test", Duration.ZERO, Duration.ofSeconds(2), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> config("test", Duration.ofSeconds(2), Duration.ofSeconds(3), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> config("test", Duration.ofSeconds(1), Duration.ofSeconds(2), 0));
    }

    private static InstancePresenceConfig config(
            String prefix, Duration heartbeat, Duration ttl, int cleanupBatch) {
        return new InstancePresenceConfig(prefix, heartbeat, ttl, cleanupBatch);
    }
}
