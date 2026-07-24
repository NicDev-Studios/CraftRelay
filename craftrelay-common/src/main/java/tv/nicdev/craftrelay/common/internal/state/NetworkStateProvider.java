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
package tv.nicdev.craftrelay.common.internal.state;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;

/**
 * Internal asynchronous source of network-state snapshots.
 *
 * <p>Step 7 supplies the distributed presence implementation.
 */
public interface NetworkStateProvider {

    /**
     * Returns known instances.
     *
     * @return asynchronous point-in-time collection
     */
    CompletableFuture<? extends Collection<NetworkInstance>> instances();

    /**
     * Looks up a player.
     *
     * @param playerId player unique ID
     * @return asynchronous optional player snapshot
     */
    CompletableFuture<Optional<NetworkPlayer>> player(UUID playerId);
}
