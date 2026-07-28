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
package tv.nicdev.craftrelay.api.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.NetworkMessage;

class MessageTypeTest {

    @Test
    void createsStableVersionedIdentifier() {
        MessageType<TestMessage> type =
                MessageType.of("my_plugin", "warp-request", 2, TestMessage.class);

        assertEquals("my_plugin:warp-request", type.identifier());
        assertEquals(2, type.payloadVersion());
        assertEquals(TestMessage.class, type.messageClass());
        assertEquals(
                type,
                MessageType.of("my_plugin", "warp-request", 2, TestMessage.class));
    }

    @Test
    void rejectsInvalidAndReservedIdentifiers() {
        assertThrows(
                NullPointerException.class,
                () -> MessageType.of(null, "message", 1, TestMessage.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> MessageType.of("MyPlugin", "message", 1, TestMessage.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> MessageType.of("myplugin", "bad name", 1, TestMessage.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> MessageType.of("craftrelay", "message", 1, TestMessage.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> MessageType.of("myplugin", "message", 0, TestMessage.class));
        assertThrows(
                NullPointerException.class,
                () -> MessageType.of("myplugin", "message", 1, null));
    }

    private record TestMessage(String value) implements NetworkMessage {
    }
}
