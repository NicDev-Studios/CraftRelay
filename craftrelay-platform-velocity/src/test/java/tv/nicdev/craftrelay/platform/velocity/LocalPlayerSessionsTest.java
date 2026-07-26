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
package tv.nicdev.craftrelay.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalPlayerSessionsTest {

    @Test
    void staleOwnershipLossCannotRemoveReplacementSession() {
        LocalPlayerSessions sessions = new LocalPlayerSessions();
        UUID playerId = UUID.randomUUID();
        UUID oldSession = UUID.randomUUID();
        UUID newSession = UUID.randomUUID();

        sessions.set(playerId, oldSession);
        sessions.set(playerId, newSession);

        assertFalse(sessions.remove(playerId, oldSession));
        assertEquals(newSession, sessions.find(playerId).orElseThrow());
        assertTrue(sessions.remove(playerId, newSession));
        assertTrue(sessions.find(playerId).isEmpty());
    }

    @Test
    void removeReturnsTheCurrentSessionOnlyOnce() {
        LocalPlayerSessions sessions = new LocalPlayerSessions();
        UUID playerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        sessions.set(playerId, sessionId);

        assertEquals(sessionId, sessions.remove(playerId).orElseThrow());
        assertTrue(sessions.remove(playerId).isEmpty());
    }
}
