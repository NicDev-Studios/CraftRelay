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
package tv.nicdev.craftrelay.transport.redis;

import java.util.Objects;
import java.util.function.IntSupplier;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.common.internal.node.CraftRelayNode;
import tv.nicdev.craftrelay.common.internal.node.CraftRelayNodes;
import tv.nicdev.craftrelay.common.internal.observability.DiagnosticSink;
import tv.nicdev.craftrelay.common.internal.observability.NodeDiagnostics;
import tv.nicdev.craftrelay.common.internal.presence.PlayerOwnershipListener;
import tv.nicdev.craftrelay.common.internal.runtime.LocalInstanceIdentity;
import tv.nicdev.craftrelay.transport.redis.config.CraftRelayRedisConfig;

/** Central composition factory shared by the Paper and Velocity adapters. */
public final class RedisCraftRelayNodeFactory {

    private RedisCraftRelayNodeFactory() {
    }

    /**
     * Creates one fully owned Redis-backed node.
     *
     * @param config validated shared configuration
     * @param instanceType platform-enforced node type
     * @param onlinePlayerCount constant-time local player count
     * @param ownershipListener lost local player-session listener
     * @return unopened node
     */
    public static CraftRelayNode create(
            CraftRelayRedisConfig config,
            NetworkInstanceType instanceType,
            IntSupplier onlinePlayerCount,
            PlayerOwnershipListener ownershipListener) {
        return create(
                config,
                instanceType,
                onlinePlayerCount,
                ownershipListener,
                DiagnosticSink.NOOP);
    }

    /** Creates one fully owned node with a platform diagnostic sink. */
    public static CraftRelayNode create(
            CraftRelayRedisConfig config,
            NetworkInstanceType instanceType,
            IntSupplier onlinePlayerCount,
            PlayerOwnershipListener ownershipListener,
            DiagnosticSink diagnosticSink) {
        CraftRelayRedisConfig settings = Objects.requireNonNull(config, "config");
        NodeDiagnostics diagnostics =
                new NodeDiagnostics(Objects.requireNonNull(diagnosticSink, "diagnosticSink"));
        LettuceRedisBackend backend =
                new LettuceRedisBackend(
                        settings.redis(),
                        diagnostics,
                        settings.messaging().capacities().dispatchQueueCapacity());
        try {
            LettuceRedisTransport transport = backend.transport();
            LettuceRedisPresenceStore store =
                    backend.presenceStore(
                            settings.instancePresence(), settings.playerPresence());
            return CraftRelayNodes.create(
                    transport,
                    new LocalInstanceIdentity(
                            settings.instanceId(),
                            Objects.requireNonNull(instanceType, "instanceType"),
                            settings.group()),
                    settings.messaging(),
                    settings.requests(),
                    settings.instancePresence(),
                    settings.playerPresence(),
                    store,
                    Objects.requireNonNull(onlinePlayerCount, "onlinePlayerCount"),
                    Objects.requireNonNull(ownershipListener, "ownershipListener"),
                    diagnostics);
        } catch (RuntimeException | Error failure) {
            backend.close();
            throw failure;
        }
    }
}
