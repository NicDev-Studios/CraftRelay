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
package tv.nicdev.craftrelay.common.internal.node;

import java.util.Objects;
import java.util.function.IntSupplier;
import tv.nicdev.craftrelay.common.internal.presence.InstancePresenceConfig;
import tv.nicdev.craftrelay.common.internal.request.RequestRuntimeConfig;
import tv.nicdev.craftrelay.common.internal.runtime.LocalInstanceIdentity;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntime;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntimeConfig;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntimes;
import tv.nicdev.craftrelay.common.internal.state.NetworkInstanceStore;
import tv.nicdev.craftrelay.common.internal.state.PlayerStateProvider;
import tv.nicdev.craftrelay.common.transport.NetworkTransport;

/**
 * Internal factories for composed CraftRelay nodes.
 */
public final class CraftRelayNodes {

    private CraftRelayNodes() {
    }

    /**
     * Creates a node with default request-capacity settings.
     *
     * @param transport message transport
     * @param identity local node identity
     * @param runtimeConfig messaging settings
     * @param instanceStore authoritative instance store
     * @param playerStateProvider player-state provider
     * @param onlinePlayerCount constant-time local player count
     * @return new node
     */
    public static CraftRelayNode create(
            NetworkTransport transport,
            LocalInstanceIdentity identity,
            MessagingRuntimeConfig runtimeConfig,
            NetworkInstanceStore instanceStore,
            PlayerStateProvider playerStateProvider,
            IntSupplier onlinePlayerCount) {
        return create(
                transport,
                identity,
                runtimeConfig,
                RequestRuntimeConfig.defaults(),
                InstancePresenceConfig.defaults(),
                instanceStore,
                playerStateProvider,
                onlinePlayerCount);
    }

    /**
     * Creates a node with explicit messaging and request settings.
     *
     * @param transport message transport
     * @param identity local node identity
     * @param runtimeConfig messaging settings
     * @param requestConfig request settings
     * @param presenceConfig instance-presence settings
     * @param instanceStore authoritative instance store
     * @param playerStateProvider player-state provider
     * @param onlinePlayerCount constant-time local player count
     * @return new node
     */
    public static CraftRelayNode create(
            NetworkTransport transport,
            LocalInstanceIdentity identity,
            MessagingRuntimeConfig runtimeConfig,
            RequestRuntimeConfig requestConfig,
            InstancePresenceConfig presenceConfig,
            NetworkInstanceStore instanceStore,
            PlayerStateProvider playerStateProvider,
            IntSupplier onlinePlayerCount) {
        LocalInstanceIdentity validatedIdentity = Objects.requireNonNull(identity, "identity");
        MessagingRuntime runtime =
                MessagingRuntimes.create(
                        Objects.requireNonNull(transport, "transport"),
                        validatedIdentity,
                        Objects.requireNonNull(runtimeConfig, "runtimeConfig"));
        return new DefaultCraftRelayNode(
                runtime,
                validatedIdentity,
                Objects.requireNonNull(requestConfig, "requestConfig"),
                Objects.requireNonNull(presenceConfig, "presenceConfig"),
                Objects.requireNonNull(instanceStore, "instanceStore"),
                Objects.requireNonNull(playerStateProvider, "playerStateProvider"),
                Objects.requireNonNull(onlinePlayerCount, "onlinePlayerCount"));
    }
}
