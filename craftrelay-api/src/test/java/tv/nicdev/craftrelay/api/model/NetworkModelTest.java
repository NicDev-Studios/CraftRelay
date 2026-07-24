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
package tv.nicdev.craftrelay.api.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NetworkModelTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant UPDATE = START.plusSeconds(5);

    @Test
    void createsValidInstanceSnapshot() {
        NetworkInstance instance =
                new NetworkInstance(
                        "proxy-eu-1",
                        NetworkInstanceType.PROXY,
                        Optional.of("eu"),
                        START,
                        UPDATE,
                        12);

        assertEquals("proxy-eu-1", instance.id());
        assertEquals(Optional.of("eu"), instance.group());
        assertEquals(12, instance.onlinePlayerCount());
    }

    @Test
    void rejectsInvalidInstanceValues() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new NetworkInstance(
                                " ", NetworkInstanceType.PROXY, Optional.empty(), START, UPDATE, 0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new NetworkInstance(
                                "proxy",
                                NetworkInstanceType.PROXY,
                                Optional.of(" "),
                                START,
                                UPDATE,
                                0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new NetworkInstance(
                                "proxy",
                                NetworkInstanceType.PROXY,
                                Optional.empty(),
                                START,
                                START.minusSeconds(1),
                                0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new NetworkInstance(
                                "proxy",
                                NetworkInstanceType.PROXY,
                                Optional.empty(),
                                START,
                                UPDATE,
                                -1));
    }

    @Test
    void createsValidPlayerSnapshot() {
        UUID playerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        NetworkPlayer player =
                new NetworkPlayer(
                        playerId,
                        "NicDev",
                        "proxy-eu-1",
                        Optional.of("lobby-1"),
                        sessionId,
                        START,
                        UPDATE);

        assertEquals(playerId, player.uniqueId());
        assertEquals(Optional.of("lobby-1"), player.serverId());
        assertEquals(sessionId, player.sessionId());
    }

    @Test
    void rejectsInvalidPlayerValues() {
        UUID playerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new NetworkPlayer(
                                playerId,
                                "",
                                "proxy",
                                Optional.empty(),
                                sessionId,
                                START,
                                UPDATE));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new NetworkPlayer(
                                playerId,
                                "NicDev",
                                " ",
                                Optional.empty(),
                                sessionId,
                                START,
                                UPDATE));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new NetworkPlayer(
                                playerId,
                                "NicDev",
                                "proxy",
                                Optional.of(""),
                                sessionId,
                                START,
                                UPDATE));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new NetworkPlayer(
                                playerId,
                                "NicDev",
                                "proxy",
                                Optional.empty(),
                                sessionId,
                                START,
                                START.minusSeconds(1)));
    }
}
