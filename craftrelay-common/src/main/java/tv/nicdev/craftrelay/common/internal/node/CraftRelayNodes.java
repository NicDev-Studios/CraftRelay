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
import tv.nicdev.craftrelay.common.internal.request.RequestRuntimeConfig;
import tv.nicdev.craftrelay.common.internal.runtime.LocalInstanceIdentity;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntime;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntimeConfig;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntimes;
import tv.nicdev.craftrelay.common.internal.state.NetworkStateProvider;
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
     * @param stateProvider network-state provider
     * @return new node
     */
    public static CraftRelayNode create(
            NetworkTransport transport,
            LocalInstanceIdentity identity,
            MessagingRuntimeConfig runtimeConfig,
            NetworkStateProvider stateProvider) {
        return create(
                transport,
                identity,
                runtimeConfig,
                RequestRuntimeConfig.defaults(),
                stateProvider);
    }

    /**
     * Creates a node with explicit messaging and request settings.
     *
     * @param transport message transport
     * @param identity local node identity
     * @param runtimeConfig messaging settings
     * @param requestConfig request settings
     * @param stateProvider network-state provider
     * @return new node
     */
    public static CraftRelayNode create(
            NetworkTransport transport,
            LocalInstanceIdentity identity,
            MessagingRuntimeConfig runtimeConfig,
            RequestRuntimeConfig requestConfig,
            NetworkStateProvider stateProvider) {
        MessagingRuntime runtime =
                MessagingRuntimes.create(
                        Objects.requireNonNull(transport, "transport"),
                        Objects.requireNonNull(identity, "identity"),
                        Objects.requireNonNull(runtimeConfig, "runtimeConfig"));
        return new DefaultCraftRelayNode(
                runtime,
                Objects.requireNonNull(requestConfig, "requestConfig"),
                Objects.requireNonNull(stateProvider, "stateProvider"));
    }
}
