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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;

class MessagingRuntimeValueTest {

    @Test
    void identityValidatesAllFields() {
        LocalInstanceIdentity identity = new LocalInstanceIdentity(
                "proxy-eu-1", NetworkInstanceType.PROXY, Optional.of("eu"));

        assertEquals("proxy-eu-1", identity.instanceId());
        assertEquals(NetworkInstanceType.PROXY, identity.instanceType());
        assertEquals(Optional.of("eu"), identity.group());
        assertThrows(
                NullPointerException.class,
                () -> new LocalInstanceIdentity(null, NetworkInstanceType.PROXY, Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LocalInstanceIdentity(" ", NetworkInstanceType.PROXY, Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () -> new LocalInstanceIdentity("proxy-1", null, Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LocalInstanceIdentity(
                        "proxy-1", NetworkInstanceType.PROXY, Optional.of("\t")));
    }

    @Test
    void configDerivesOneValidatedMessageChannel() {
        MessagingRuntimeConfig defaults = MessagingRuntimeConfig.defaults();

        assertEquals("craftrelay:messages", defaults.messageChannel());
        assertEquals(10_000, defaults.duplicateCacheCapacity());
        assertEquals(
                "network:v1:messages",
                new MessagingRuntimeConfig("network:v1", 1).messageChannel());
        assertThrows(
                NullPointerException.class,
                () -> new MessagingRuntimeConfig(null, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MessagingRuntimeConfig(" ", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MessagingRuntimeConfig(" craftrelay", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MessagingRuntimeConfig("craftrelay", 0));
    }

    @Test
    void duplicateCacheEvictsItsOldestEntryAtCapacity() {
        DuplicateMessageCache cache = new DuplicateMessageCache(2);
        var first = java.util.UUID.randomUUID();
        var second = java.util.UUID.randomUUID();
        var third = java.util.UUID.randomUUID();

        org.junit.jupiter.api.Assertions.assertTrue(cache.markIfNew(first));
        org.junit.jupiter.api.Assertions.assertFalse(cache.markIfNew(first));
        org.junit.jupiter.api.Assertions.assertTrue(cache.markIfNew(second));
        org.junit.jupiter.api.Assertions.assertTrue(cache.markIfNew(third));
        assertEquals(2, cache.size());
        org.junit.jupiter.api.Assertions.assertTrue(cache.markIfNew(first));
    }
}
