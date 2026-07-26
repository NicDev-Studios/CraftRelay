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
import tv.nicdev.craftrelay.common.internal.presence.PlayerOwnershipListener;
import tv.nicdev.craftrelay.common.internal.presence.PlayerPresenceConfig;
import tv.nicdev.craftrelay.common.internal.request.RequestRuntimeConfig;
import tv.nicdev.craftrelay.common.internal.runtime.LocalInstanceIdentity;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntime;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntimeConfig;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntimes;
import tv.nicdev.craftrelay.common.internal.state.NetworkPresenceStore;
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
     * @param presenceStore authoritative instance/player store
     * @param onlinePlayerCount constant-time local player count
     * @return new node
     */
    public static CraftRelayNode create(
            NetworkTransport transport,
            LocalInstanceIdentity identity,
            MessagingRuntimeConfig runtimeConfig,
            NetworkPresenceStore presenceStore,
            IntSupplier onlinePlayerCount) {
        return create(
                transport,
                identity,
                runtimeConfig,
                RequestRuntimeConfig.defaults(),
                InstancePresenceConfig.defaults(),
                PlayerPresenceConfig.defaults(),
                presenceStore,
                onlinePlayerCount,
                PlayerOwnershipListener.NOOP);
    }

    /**
     * Creates a node with explicit messaging and request settings.
     *
     * @param transport message transport
     * @param identity local node identity
     * @param runtimeConfig messaging settings
     * @param requestConfig request settings
     * @param instanceConfig instance-presence settings
     * @param playerConfig player-presence settings
     * @param presenceStore authoritative instance/player store
     * @param onlinePlayerCount constant-time local player count
     * @return new node
     */
    public static CraftRelayNode create(
            NetworkTransport transport,
            LocalInstanceIdentity identity,
            MessagingRuntimeConfig runtimeConfig,
            RequestRuntimeConfig requestConfig,
            InstancePresenceConfig instanceConfig,
            PlayerPresenceConfig playerConfig,
            NetworkPresenceStore presenceStore,
            IntSupplier onlinePlayerCount) {
        return create(
                transport,
                identity,
                runtimeConfig,
                requestConfig,
                instanceConfig,
                playerConfig,
                presenceStore,
                onlinePlayerCount,
                PlayerOwnershipListener.NOOP);
    }

    /**
     * Creates a node with explicit settings and a player-ownership listener.
     *
     * @param transport message transport
     * @param identity local node identity
     * @param runtimeConfig messaging settings
     * @param requestConfig request settings
     * @param instanceConfig instance-presence settings
     * @param playerConfig player-presence settings
     * @param presenceStore authoritative instance/player store
     * @param onlinePlayerCount constant-time local player count
     * @param ownershipListener lost-session listener
     * @return new node
     */
    public static CraftRelayNode create(
            NetworkTransport transport,
            LocalInstanceIdentity identity,
            MessagingRuntimeConfig runtimeConfig,
            RequestRuntimeConfig requestConfig,
            InstancePresenceConfig instanceConfig,
            PlayerPresenceConfig playerConfig,
            NetworkPresenceStore presenceStore,
            IntSupplier onlinePlayerCount,
            PlayerOwnershipListener ownershipListener) {
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
                Objects.requireNonNull(instanceConfig, "instanceConfig"),
                Objects.requireNonNull(playerConfig, "playerConfig"),
                Objects.requireNonNull(presenceStore, "presenceStore"),
                Objects.requireNonNull(onlinePlayerCount, "onlinePlayerCount"),
                Objects.requireNonNull(ownershipListener, "ownershipListener"));
    }
}
