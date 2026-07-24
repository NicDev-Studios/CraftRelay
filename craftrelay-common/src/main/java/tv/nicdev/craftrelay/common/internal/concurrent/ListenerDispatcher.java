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
package tv.nicdev.craftrelay.common.internal.concurrent;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Internal owner of isolated, ordered listener queues executed on virtual threads.
 *
 * <p>Each registered lane is bounded independently. A slow listener therefore cannot consume
 * unbounded memory or delay another listener.
 */
public final class ListenerDispatcher implements AutoCloseable {

    /** Default maximum number of pending deliveries for one listener. */
    public static final int DEFAULT_QUEUE_CAPACITY = 1_024;

    private final ExecutorService executor;
    private final Set<DispatchLane<?>> lanes = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a dispatcher using an owned virtual-thread-per-task executor.
     *
     * @param threadNamePrefix non-blank virtual-thread name prefix
     */
    public ListenerDispatcher(String threadNamePrefix) {
        Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
        if (threadNamePrefix.isBlank()) {
            throw new IllegalArgumentException("threadNamePrefix must not be blank");
        }
        executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(threadNamePrefix, 0).factory());
    }

    /**
     * Registers an independently ordered and bounded delivery lane.
     *
     * @param capacity maximum pending deliveries
     * @param listener delivery consumer
     * @param failureHandler handler for non-fatal listener failures
     * @param overflowHandler handler invoked when this lane is full
     * @param <T> delivery type
     * @return the new lane
     */
    public <T> DispatchLane<T> register(
            int capacity,
            Consumer<? super T> listener,
            Consumer<? super Throwable> failureHandler,
            Runnable overflowHandler) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(failureHandler, "failureHandler");
        Objects.requireNonNull(overflowHandler, "overflowHandler");
        if (closed.get()) {
            throw new IllegalStateException("dispatcher is closed");
        }

        DispatchLane<T> lane =
                new DispatchLane<>(
                        this, capacity, listener, failureHandler, overflowHandler);
        lanes.add(lane);
        if (closed.get()) {
            lanes.remove(lane);
            lane.close();
            throw new IllegalStateException("dispatcher is closed");
        }
        return lane;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (DispatchLane<?> lane : lanes) {
            lane.close();
        }
        lanes.clear();
        executor.shutdownNow();
    }

    private void unregister(DispatchLane<?> lane) {
        lanes.remove(lane);
    }

    /**
     * One independently ordered listener queue.
     *
     * @param <T> delivery type
     */
    public static final class DispatchLane<T> implements AutoCloseable {

        private final ListenerDispatcher owner;
        private final int capacity;
        private final Consumer<? super T> listener;
        private final Consumer<? super Throwable> failureHandler;
        private final Runnable overflowHandler;
        private final Queue<T> pending = new ArrayDeque<>();

        private boolean draining;
        private boolean closed;
        private boolean overflowReportScheduled;

        private DispatchLane(
                ListenerDispatcher owner,
                int capacity,
                Consumer<? super T> listener,
                Consumer<? super Throwable> failureHandler,
                Runnable overflowHandler) {
            this.owner = owner;
            this.capacity = capacity;
            this.listener = listener;
            this.failureHandler = failureHandler;
            this.overflowHandler = overflowHandler;
        }

        /**
         * Queues a delivery if this lane is open and has capacity.
         *
         * @param delivery non-null delivery
         * @return {@code true} if queued
         */
        public boolean dispatch(T delivery) {
            Objects.requireNonNull(delivery, "delivery");
            boolean schedule = false;
            boolean overflow = false;
            synchronized (this) {
                if (closed) {
                    return false;
                }
                if (pending.size() >= capacity) {
                    overflow = true;
                } else {
                    pending.add(delivery);
                    if (!draining) {
                        draining = true;
                        schedule = true;
                    }
                }
            }
            if (overflow) {
                scheduleOverflowReport();
                return false;
            }
            if (schedule) {
                scheduleDrain();
            }
            return true;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                pending.clear();
            }
            owner.unregister(this);
        }

        private void scheduleDrain() {
            try {
                owner.executor.execute(this::drain);
            } catch (RejectedExecutionException ignored) {
                synchronized (this) {
                    draining = false;
                    pending.clear();
                }
            }
        }

        private void drain() {
            try {
                while (true) {
                    T delivery;
                    synchronized (this) {
                        if (closed) {
                            pending.clear();
                            return;
                        }
                        delivery = pending.poll();
                        if (delivery == null) {
                            return;
                        }
                    }
                    try {
                        listener.accept(delivery);
                    } catch (Throwable failure) {
                        if (isFatal(failure)) {
                            throw (Error) failure;
                        }
                        invokeFailureHandler(failure);
                    }
                }
            } finally {
                boolean reschedule;
                synchronized (this) {
                    draining = false;
                    reschedule = !closed && !pending.isEmpty();
                    if (reschedule) {
                        draining = true;
                    }
                }
                if (reschedule) {
                    scheduleDrain();
                }
            }
        }

        private void scheduleOverflowReport() {
            synchronized (this) {
                if (closed || overflowReportScheduled) {
                    return;
                }
                overflowReportScheduled = true;
            }
            try {
                owner.executor.execute(() -> {
                    try {
                        invokeOverflowHandler();
                    } finally {
                        synchronized (DispatchLane.this) {
                            overflowReportScheduled = false;
                        }
                    }
                });
            } catch (RejectedExecutionException ignored) {
                synchronized (this) {
                    overflowReportScheduled = false;
                }
            }
        }

        private void invokeFailureHandler(Throwable failure) {
            try {
                failureHandler.accept(failure);
            } catch (Throwable diagnosticFailure) {
                if (isFatal(diagnosticFailure)) {
                    throw (Error) diagnosticFailure;
                }
                // A diagnostic callback must never terminate dispatching.
            }
        }

        private void invokeOverflowHandler() {
            try {
                overflowHandler.run();
            } catch (Throwable diagnosticFailure) {
                if (isFatal(diagnosticFailure)) {
                    throw (Error) diagnosticFailure;
                }
                // A diagnostic callback must never terminate overflow reporting.
            }
        }

        private static boolean isFatal(Throwable failure) {
            return failure instanceof VirtualMachineError;
        }
    }
}
