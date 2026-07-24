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
package tv.nicdev.craftrelay.common.internal.runtime;

import java.util.Objects;
import tv.nicdev.craftrelay.common.internal.protocol.MessageCodecs;
import tv.nicdev.craftrelay.common.transport.NetworkTransport;

/** Internal factories for messaging runtimes. */
public final class MessagingRuntimes {

    private MessagingRuntimes() {
    }

    /**
     * Creates a runtime using the standard versioned wire codec.
     *
     * @param transport owned transport
     * @param identity local instance identity
     * @param config runtime settings
     * @return a new, unstarted runtime
     */
    public static MessagingRuntime create(
            NetworkTransport transport,
            LocalInstanceIdentity identity,
            MessagingRuntimeConfig config) {
        return new DefaultMessagingRuntime(
                Objects.requireNonNull(transport, "transport"),
                MessageCodecs.standard(),
                Objects.requireNonNull(identity, "identity"),
                Objects.requireNonNull(config, "config"));
    }
}
