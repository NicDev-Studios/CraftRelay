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
package tv.nicdev.craftrelay.common.testing;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.common.internal.state.NetworkInstanceStore;

/** Deterministic in-memory instance store for Common unit tests only. */
public final class TestNetworkInstanceStore implements NetworkInstanceStore {

    private final Map<String, Lease> leases = new LinkedHashMap<>();
    private final AtomicInteger heartbeatCalls = new AtomicInteger();

    private boolean connected;
    private boolean closed;

    public synchronized void seed(NetworkInstance instance, String token) {
        NetworkInstance value = Objects.requireNonNull(instance, "instance");
        leases.put(value.id(), new Lease(value, token));
    }

    public int heartbeatCalls() {
        return heartbeatCalls.get();
    }

    public synchronized void forceRemove(String instanceId) {
        leases.remove(instanceId);
    }

    @Override
    public synchronized CompletableFuture<Void> connect() {
        if (closed) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("store is closed"));
        }
        connected = true;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Boolean> claim(
            NetworkInstance instance, String leaseToken, Duration ttl) {
        requireConnected();
        Lease existing = leases.get(instance.id());
        if (existing != null && !existing.token().equals(leaseToken)) {
            return CompletableFuture.completedFuture(false);
        }
        leases.put(instance.id(), new Lease(instance, leaseToken));
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public synchronized CompletableFuture<Boolean> heartbeat(
            NetworkInstance instance, String leaseToken, Duration ttl) {
        requireConnected();
        heartbeatCalls.incrementAndGet();
        Lease existing = leases.get(instance.id());
        if (existing == null || !existing.token().equals(leaseToken)) {
            return CompletableFuture.completedFuture(false);
        }
        leases.put(instance.id(), new Lease(instance, leaseToken));
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public synchronized CompletableFuture<Boolean> release(
            String instanceId, String leaseToken) {
        requireConnected();
        Lease existing = leases.get(instanceId);
        if (existing == null || !existing.token().equals(leaseToken)) {
            return CompletableFuture.completedFuture(false);
        }
        leases.remove(instanceId);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Void> cleanupExpired(int batchSize) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<? extends Collection<NetworkInstance>> instances() {
        requireConnected();
        return CompletableFuture.completedFuture(
                leases.values().stream().map(Lease::instance).toList());
    }

    @Override
    public synchronized CompletableFuture<Void> close() {
        closed = true;
        connected = false;
        return CompletableFuture.completedFuture(null);
    }

    private void requireConnected() {
        if (!connected || closed) {
            throw new IllegalStateException("store is not connected");
        }
    }

    private record Lease(NetworkInstance instance, String token) {

        private Lease {
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(token, "token");
        }
    }
}
