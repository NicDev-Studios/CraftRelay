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
package tv.nicdev.craftrelay.common.internal.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.exception.InvalidMessageException;
import tv.nicdev.craftrelay.api.message.GlobalBroadcastMessage;
import tv.nicdev.craftrelay.api.message.PlayerLocationRequest;

class MessageRegistryTest {

    @Test
    void standardRegistryResolvesBothDirections() {
        MessageRegistry registry = MessageRegistry.withStandardMessages();

        assertEquals(
                "craftrelay:global_broadcast",
                registry.typeOf(new GlobalBroadcastMessage("hello")));
        assertEquals(
                PlayerLocationRequest.class,
                registry.classFor("craftrelay:player_location_request"));
    }

    @Test
    void duplicateTypesAndClassesAreRejected() {
        MessageRegistry registry = new MessageRegistry();
        registry.register("craftrelay:first", GlobalBroadcastMessage.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.register("craftrelay:first", PlayerLocationRequest.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.register("craftrelay:second", GlobalBroadcastMessage.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.register("Other:Type", PlayerLocationRequest.class));
    }

    @Test
    void unknownTypesAndClassesAreRejected() {
        MessageRegistry registry = new MessageRegistry();

        assertThrows(
                InvalidMessageException.class,
                () -> registry.typeOf(new GlobalBroadcastMessage("hello")));
        assertThrows(
                InvalidMessageException.class,
                () -> registry.classFor("craftrelay:unknown"));
    }

    @Test
    void concurrentReadsSeeCompleteStandardRegistry() throws Exception {
        MessageRegistry registry = MessageRegistry.withStandardMessages();
        List<Callable<String>> lookups = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            lookups.add(
                    () -> registry.typeOf(new GlobalBroadcastMessage("concurrent")));
        }

        try (var executor = Executors.newFixedThreadPool(8)) {
            for (var result : executor.invokeAll(lookups)) {
                assertEquals("craftrelay:global_broadcast", result.get());
            }
        }
        assertEquals(10, registry.snapshot().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> registry.snapshot().clear());
    }
}
