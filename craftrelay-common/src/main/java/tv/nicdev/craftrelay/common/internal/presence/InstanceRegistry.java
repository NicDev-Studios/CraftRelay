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

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.message.InstanceHeartbeatMessage;
import tv.nicdev.craftrelay.api.message.InstanceStartedMessage;
import tv.nicdev.craftrelay.api.message.InstanceStoppedMessage;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.target.NetworkTargets;
import tv.nicdev.craftrelay.common.internal.concurrent.AsyncFailures;
import tv.nicdev.craftrelay.common.internal.observability.DiagnosticCode;
import tv.nicdev.craftrelay.common.internal.observability.DiagnosticComponent;
import tv.nicdev.craftrelay.common.internal.observability.NodeDiagnostics;
import tv.nicdev.craftrelay.common.internal.observability.TelemetryCounter;
import tv.nicdev.craftrelay.common.internal.runtime.LocalInstanceIdentity;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntime;
import tv.nicdev.craftrelay.common.internal.state.InstanceStateProvider;
import tv.nicdev.craftrelay.common.internal.state.NetworkInstanceStore;

/**
 * Lifecycle-safe local owner of one distributed instance lease.
 *
 * <p>The next heartbeat is scheduled only after the previous asynchronous attempt has completed,
 * so heartbeat operations never overlap.
 */
public final class InstanceRegistry implements InstanceStateProvider {

    private final Object lifecycleLock = new Object();
    private final NetworkInstanceStore store;
    private final MessagingRuntime runtime;
    private final LocalInstanceIdentity identity;
    private final InstancePresenceConfig config;
    private final IntSupplier onlinePlayerCount;
    private final Consumer<? super Throwable> leaseLossHandler;
    private final Clock clock;
    private final Instant startedAt;
    private final NodeLease nodeLease;
    private final ScheduledExecutorService executor;
    private final NodeDiagnostics diagnostics;

    private RegistryState state = RegistryState.NEW;
    private CompletableFuture<Void> startFuture;
    private CompletableFuture<Void> stopFuture;
    private ScheduledFuture<?> scheduledHeartbeat;
    private CompletableFuture<Void> heartbeatFuture = CompletableFuture.completedFuture(null);
    private boolean leaseOwned;

    /** Creates an instance registry using the system UTC clock. */
    public InstanceRegistry(
            NetworkInstanceStore store,
            MessagingRuntime runtime,
            LocalInstanceIdentity identity,
            InstancePresenceConfig config,
            IntSupplier onlinePlayerCount,
            Consumer<? super Throwable> leaseLossHandler) {
        this(
                store,
                runtime,
                identity,
                config,
                NodeLease.create(),
                onlinePlayerCount,
                leaseLossHandler,
                new NodeDiagnostics());
    }

    /** Creates an instance registry using the node-wide presence lease. */
    public InstanceRegistry(
            NetworkInstanceStore store,
            MessagingRuntime runtime,
            LocalInstanceIdentity identity,
            InstancePresenceConfig config,
            NodeLease nodeLease,
            IntSupplier onlinePlayerCount,
            Consumer<? super Throwable> leaseLossHandler) {
        this(
                store,
                runtime,
                identity,
                config,
                nodeLease,
                onlinePlayerCount,
                leaseLossHandler,
                Clock.systemUTC(),
                new NodeDiagnostics());
    }

    /** Creates an instance registry sharing node diagnostics. */
    public InstanceRegistry(
            NetworkInstanceStore store,
            MessagingRuntime runtime,
            LocalInstanceIdentity identity,
            InstancePresenceConfig config,
            NodeLease nodeLease,
            IntSupplier onlinePlayerCount,
            Consumer<? super Throwable> leaseLossHandler,
            NodeDiagnostics diagnostics) {
        this(
                store,
                runtime,
                identity,
                config,
                nodeLease,
                onlinePlayerCount,
                leaseLossHandler,
                Clock.systemUTC(),
                diagnostics);
    }

    InstanceRegistry(
            NetworkInstanceStore store,
            MessagingRuntime runtime,
            LocalInstanceIdentity identity,
            InstancePresenceConfig config,
            IntSupplier onlinePlayerCount,
            Consumer<? super Throwable> leaseLossHandler,
            Clock clock) {
        this(
                store,
                runtime,
                identity,
                config,
                NodeLease.create(),
                onlinePlayerCount,
                leaseLossHandler,
                clock,
                new NodeDiagnostics());
    }

    InstanceRegistry(
            NetworkInstanceStore store,
            MessagingRuntime runtime,
            LocalInstanceIdentity identity,
            InstancePresenceConfig config,
            NodeLease nodeLease,
            IntSupplier onlinePlayerCount,
            Consumer<? super Throwable> leaseLossHandler,
            Clock clock) {
        this(
                store,
                runtime,
                identity,
                config,
                nodeLease,
                onlinePlayerCount,
                leaseLossHandler,
                clock,
                new NodeDiagnostics());
    }

    InstanceRegistry(
            NetworkInstanceStore store,
            MessagingRuntime runtime,
            LocalInstanceIdentity identity,
            InstancePresenceConfig config,
            NodeLease nodeLease,
            IntSupplier onlinePlayerCount,
            Consumer<? super Throwable> leaseLossHandler,
            Clock clock,
            NodeDiagnostics diagnostics) {
        this.store = Objects.requireNonNull(store, "store");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.config = Objects.requireNonNull(config, "config");
        this.nodeLease = Objects.requireNonNull(nodeLease, "nodeLease");
        this.onlinePlayerCount = Objects.requireNonNull(onlinePlayerCount, "onlinePlayerCount");
        this.leaseLossHandler = Objects.requireNonNull(leaseLossHandler, "leaseLossHandler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        startedAt = clock.instant();
        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform()
                        .daemon()
                        .name("craftrelay-instance-presence-")
                        .factory());
    }

    /** Connects the authoritative instance store. */
    public CompletableFuture<Void> connect() {
        return store.connect();
    }

    /** Claims the local lease, announces startup, and starts heartbeats. */
    public CompletableFuture<Void> start() {
        synchronized (lifecycleLock) {
            if (state == RegistryState.RUNNING || state == RegistryState.STARTING) {
                return startFuture;
            }
            if (state == RegistryState.STOPPING || state == RegistryState.STOPPED) {
                return CompletableFuture.failedFuture(
                        new ApiUnavailableException("Instance registry is stopping or stopped"));
            }
            state = RegistryState.STARTING;
            CompletableFuture<Void> attempt;
            try {
                NetworkInstance snapshot = snapshot(clock.instant());
                attempt = claim(snapshot)
                        .thenComposeAsync(
                                claimed -> announceClaim(claimed, snapshot),
                                executor);
            } catch (RuntimeException failure) {
                attempt = CompletableFuture.failedFuture(failure);
            }
            startFuture = attempt.handleAsync(
                    (ignored, failure) -> finishStart(failure), executor);
            return startFuture;
        }
    }

    @Override
    public CompletableFuture<? extends Collection<NetworkInstance>> instances() {
        return store.instances()
                .thenApplyAsync(
                        values -> values.stream()
                                .sorted(Comparator.comparing(NetworkInstance::id))
                                .toList(),
                        executor)
                .whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        diagnostics.healthy(DiagnosticComponent.INSTANCE_PRESENCE);
                    } else {
                        diagnostics.degraded(DiagnosticComponent.INSTANCE_PRESENCE);
                    }
                });
    }

    /** Stops heartbeats, releases this owner's lease, and best-effort announces shutdown. */
    public CompletableFuture<Void> stop() {
        CompletableFuture<Void> inFlight;
        synchronized (lifecycleLock) {
            if (state == RegistryState.STOPPING || state == RegistryState.STOPPED) {
                return stopFuture;
            }
            RegistryState previousState = state;
            state = RegistryState.STOPPING;
            if (scheduledHeartbeat != null) {
                scheduledHeartbeat.cancel(false);
                scheduledHeartbeat = null;
            }
            CompletableFuture<Void> activeOperation =
                    previousState == RegistryState.STARTING && startFuture != null
                            ? startFuture
                            : heartbeatFuture;
            inFlight = activeOperation.handle((ignored, failure) -> null);
            stopFuture = inFlight.thenComposeAsync(ignored -> releaseAndAnnounce(), executor)
                    .whenCompleteAsync((ignored, failure) -> {
                        synchronized (lifecycleLock) {
                            state = RegistryState.STOPPED;
                        }
                        diagnostics.unavailable(DiagnosticComponent.INSTANCE_PRESENCE);
                    }, executor);
            return stopFuture;
        }
    }

    /** Closes the state store and presence executor. */
    public CompletableFuture<Void> close() {
        try {
            CompletableFuture<Void> close =
                    Objects.requireNonNull(store.close(), "store.close()");
            return close.whenComplete((ignored, failure) -> executor.shutdownNow());
        } catch (RuntimeException failure) {
            executor.shutdownNow();
            return CompletableFuture.failedFuture(failure);
        }
    }

    Instant startedAt() {
        return startedAt;
    }

    private CompletableFuture<Void> announceClaim(
            boolean claimed, NetworkInstance snapshot) {
        if (!claimed) {
            return CompletableFuture.failedFuture(
                    new ApiUnavailableException(
                            "Instance ID is already active: " + identity.instanceId()));
        }
        synchronized (lifecycleLock) {
            leaseOwned = true;
        }
        return publish(new InstanceStartedMessage(snapshot))
                .handle((ignored, failure) -> failure)
                .thenComposeAsync(failure -> {
                    if (failure == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return release()
                            .handle((released, rollbackFailure) -> {
                                markLeaseReleased();
                                Throwable combined = AsyncFailures.merge(
                                        AsyncFailures.unwrapNullable(failure),
                                        AsyncFailures.unwrapNullable(rollbackFailure));
                                throw new CompletionException(combined);
                            });
                }, executor);
    }

    private Void finishStart(Throwable failure) {
        Throwable completionFailure = AsyncFailures.unwrapNullable(failure);
        synchronized (lifecycleLock) {
            if (state == RegistryState.STARTING) {
                if (completionFailure == null) {
                    state = RegistryState.RUNNING;
                    diagnostics.healthy(DiagnosticComponent.INSTANCE_PRESENCE);
                    scheduleHeartbeat();
                } else {
                    state = RegistryState.NEW;
                    diagnostics.unavailable(DiagnosticComponent.INSTANCE_PRESENCE);
                }
            } else if (completionFailure == null) {
                completionFailure =
                        new ApiUnavailableException("Instance registry stopped during start");
            }
        }
        if (completionFailure != null) {
            throw new CompletionException(completionFailure);
        }
        return null;
    }

    private void scheduleHeartbeat() {
        synchronized (lifecycleLock) {
            if (state != RegistryState.RUNNING) {
                return;
            }
            scheduledHeartbeat = executor.schedule(
                    this::beginHeartbeat,
                    config.heartbeatInterval().toNanos(),
                    TimeUnit.NANOSECONDS);
        }
    }

    private void beginHeartbeat() {
        synchronized (lifecycleLock) {
            if (state != RegistryState.RUNNING) {
                return;
            }
            CompletableFuture<Void> attempt;
            try {
                NetworkInstance snapshot = snapshot(clock.instant());
                attempt = heartbeat(snapshot)
                        .thenComposeAsync(owned -> continueHeartbeat(owned, snapshot), executor);
            } catch (RuntimeException failure) {
                attempt = CompletableFuture.failedFuture(failure);
            }
            heartbeatFuture = attempt
                    .whenCompleteAsync(this::finishHeartbeat, executor);
        }
    }

    private CompletableFuture<Void> continueHeartbeat(
            boolean owned, NetworkInstance snapshot) {
        if (!owned) {
            return CompletableFuture.failedFuture(
                    new LeaseLostException(
                            "Instance lease was lost: " + identity.instanceId()));
        }
        diagnostics.increment(TelemetryCounter.INSTANCE_HEARTBEAT_SUCCESSES);
        diagnostics.healthy(DiagnosticComponent.INSTANCE_PRESENCE);
        return publish(new InstanceHeartbeatMessage(snapshot))
                .handle((ignored, failure) -> {
                    if (failure != null) {
                        logFailure("Could not publish instance heartbeat", failure);
                    }
                    return null;
                })
                .thenCompose(ignored -> store.cleanupExpired(config.cleanupBatch())
                        .exceptionally(failure -> {
                            logFailure("Could not clean expired instance leases", failure);
                            return null;
                        }));
    }

    private void finishHeartbeat(Void ignored, Throwable failure) {
        Throwable cause = AsyncFailures.unwrapNullable(failure);
        if (cause instanceof LeaseLostException) {
            markLeaseReleased();
            leaseLossHandler.accept(cause);
            return;
        }
        if (cause != null) {
            diagnostics.increment(TelemetryCounter.INSTANCE_HEARTBEAT_FAILURES);
            diagnostics.degraded(DiagnosticComponent.INSTANCE_PRESENCE);
            diagnostics.report(DiagnosticCode.INSTANCE_HEARTBEAT_FAILED, cause);
        }
        scheduleHeartbeat();
    }

    private CompletableFuture<Void> releaseAndAnnounce() {
        synchronized (lifecycleLock) {
            if (!leaseOwned) {
                return CompletableFuture.completedFuture(null);
            }
        }
        return release()
                .handle((released, failure) -> {
                    markLeaseReleased();
                    if (failure != null) {
                        logFailure("Could not release instance lease; TTL will expire it", failure);
                    }
                    return failure == null && Boolean.TRUE.equals(released);
                })
                .thenCompose(released -> released
                        ? publish(new InstanceStoppedMessage(identity.instanceId(), clock.instant()))
                        .exceptionally(failure -> {
                            logFailure("Could not publish instance stop", failure);
                            return null;
                        })
                        : CompletableFuture.completedFuture(null));
    }

    private CompletableFuture<Boolean> claim(NetworkInstance snapshot) {
        try {
            return Objects.requireNonNull(
                    store.claim(snapshot, nodeLease.token(), config.instanceTtl()),
                    "store.claim()");
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletableFuture<Boolean> heartbeat(NetworkInstance snapshot) {
        try {
            return Objects.requireNonNull(
                    store.heartbeat(snapshot, nodeLease.token(), config.instanceTtl()),
                    "store.heartbeat()");
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletableFuture<Boolean> release() {
        try {
            return Objects.requireNonNull(
                    store.release(identity.instanceId(), nodeLease.token()),
                    "store.release()");
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletableFuture<Void> publish(NetworkMessage message) {
        try {
            return Objects.requireNonNull(
                    runtime.publish(NetworkTargets.allInstances(), message),
                    "runtime.publish()");
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void markLeaseReleased() {
        synchronized (lifecycleLock) {
            leaseOwned = false;
        }
    }

    private NetworkInstance snapshot(Instant heartbeatAt) {
        int playerCount = onlinePlayerCount.getAsInt();
        if (playerCount < 0) {
            throw new IllegalStateException("onlinePlayerCount must not be negative");
        }
        return new NetworkInstance(
                identity.instanceId(),
                identity.instanceType(),
                identity.group(),
                startedAt,
                heartbeatAt.isBefore(startedAt) ? startedAt : heartbeatAt,
                playerCount);
    }

    private void logFailure(String message, Throwable failure) {
        diagnostics.report(
                DiagnosticCode.REDIS_OPERATION_FAILED,
                AsyncFailures.unwrap(failure));
    }

    private enum RegistryState {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED
    }

    private static final class LeaseLostException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private LeaseLostException(String message) {
            super(message);
        }
    }
}
