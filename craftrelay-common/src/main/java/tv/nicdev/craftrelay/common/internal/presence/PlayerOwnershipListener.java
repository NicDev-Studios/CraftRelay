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

import tv.nicdev.craftrelay.common.internal.state.PlayerSessionKey;

/** Receives local player sessions whose Redis ownership was lost. */
@FunctionalInterface
public interface PlayerOwnershipListener {

    /** No-op listener for nodes without a platform player bridge. */
    PlayerOwnershipListener NOOP = ignored -> {};

    /**
     * Handles one lost local session.
     *
     * @param session lost player/session pair
     */
    void onOwnershipLost(PlayerSessionKey session);
}
