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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import tv.nicdev.craftrelay.api.model.NetworkInstance;

/**
 * Transport-neutral asynchronous store for fenced instance-presence leases.
 *
 * <p>Every mutation is guarded by an opaque owner token. Implementations must make claim,
 * renewal, and removal atomic.
 */
public interface NetworkInstanceStore extends InstanceStateProvider {

    /** Opens the underlying state connection. */
    CompletableFuture<Void> connect();

    /** Claims an absent or expired instance ID. */
    CompletableFuture<Boolean> claim(
            NetworkInstance instance, String leaseToken, Duration ttl);

    /** Renews a lease only when the owner token still matches. */
    CompletableFuture<Boolean> heartbeat(
            NetworkInstance instance, String leaseToken, Duration ttl);

    /** Removes a lease only when the owner token still matches. */
    CompletableFuture<Boolean> release(String instanceId, String leaseToken);

    /** Removes at most {@code batchSize} expired index entries. */
    CompletableFuture<Void> cleanupExpired(int batchSize);

    /** Closes the state connection and its owned backend resources. */
    CompletableFuture<Void> close();
}
