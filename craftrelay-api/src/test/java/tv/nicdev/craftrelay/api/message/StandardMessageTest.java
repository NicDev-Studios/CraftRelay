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
package tv.nicdev.craftrelay.api.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;

class StandardMessageTest {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");
    private static final UUID PLAYER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SESSION_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void valueObjectsRetainValidatedValues() {
        NetworkInstance instance =
                new NetworkInstance(
                        "proxy-eu-1",
                        NetworkInstanceType.PROXY,
                        Optional.of("eu"),
                        NOW,
                        NOW,
                        12);
        NetworkPlayer player =
                new NetworkPlayer(
                        PLAYER_ID,
                        "NicDev",
                        "proxy-eu-1",
                        Optional.of("lobby-1"),
                        SESSION_ID,
                        NOW,
                        NOW);

        assertEquals(instance, new InstanceStartedMessage(instance).instance());
        assertEquals(instance, new InstanceHeartbeatMessage(instance).instance());
        assertEquals(player, new PlayerConnectedMessage(player).player());
        assertEquals(
                Optional.of(player),
                new PlayerLocationResponse(PLAYER_ID, Optional.of(player)).player());
    }

    @Test
    void messagesRejectNullAndBlankValues() {
        assertThrows(NullPointerException.class, () -> new InstanceStartedMessage(null));
        assertThrows(
                IllegalArgumentException.class, () -> new InstanceStoppedMessage(" ", NOW));
        assertThrows(
                NullPointerException.class,
                () -> new PlayerDisconnectedMessage(null, SESSION_ID, "proxy-1", NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlayerConnectRequest(PLAYER_ID, ""));
        assertThrows(IllegalArgumentException.class, () -> new GlobalBroadcastMessage("\t"));
        assertThrows(
                NullPointerException.class,
                () ->
                        new PlayerServerSwitchMessage(
                                PLAYER_ID,
                                SESSION_ID,
                                "proxy-1",
                                null,
                                "server-1",
                                NOW));
    }

    @Test
    void locationResponseRequiresMatchingPlayerId() {
        NetworkPlayer player =
                new NetworkPlayer(
                        PLAYER_ID,
                        "NicDev",
                        "proxy-1",
                        Optional.empty(),
                        SESSION_ID,
                        NOW,
                        NOW);

        assertThrows(
                IllegalArgumentException.class,
                () -> new PlayerLocationResponse(UUID.randomUUID(), Optional.of(player)));
    }
}
