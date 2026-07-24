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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/**
 * Completes externally visible futures on owned virtual threads.
 *
 * <p>The lightweight callbacks attached to source stages only enqueue completion work. User
 * continuations therefore never execute on transport, scheduler, or state-provider threads.
 */
public final class FutureCompletionDispatcher {

    private final Object lock = new Object();
    private final ExecutorService executor;
    private final Set<CompletableFuture<?>> trackedFutures = new HashSet<>();

    private boolean closed;
    private CompletableFuture<Void> closeFuture;

    /**
     * Creates a dispatcher backed by virtual threads.
     *
     * @param threadNamePrefix non-blank virtual-thread name prefix
     */
    public FutureCompletionDispatcher(String threadNamePrefix) {
        Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
        if (threadNamePrefix.isBlank()) {
            throw new IllegalArgumentException("threadNamePrefix must not be blank");
        }
        executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(threadNamePrefix, 0).factory());
    }

    /**
     * Creates a future whose completion is tracked until shutdown.
     *
     * @param <T> result type
     * @return new incomplete future
     */
    public <T> CompletableFuture<T> newFuture() {
        CompletableFuture<T> future = new CompletableFuture<>();
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("future completion dispatcher is closed");
            }
            trackedFutures.add(future);
        }
        future.whenComplete((ignored, failure) -> untrack(future));
        return future;
    }

    /**
     * Relays a source stage and transforms its successful result on a virtual thread.
     *
     * @param source source stage
     * @param mapper successful-result mapper
     * @param failureMapper failure mapper
     * @param <S> source type
     * @param <T> target type
     * @return tracked result future
     */
    public <S, T> CompletableFuture<T> relay(
            CompletionStage<? extends S> source,
            Function<? super S, ? extends T> mapper,
            Function<? super Throwable, ? extends Throwable> failureMapper) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(failureMapper, "failureMapper");
        CompletableFuture<T> target = newFuture();
        try {
            source.whenComplete(
                    (value, failure) ->
                            executeCompletion(
                                    target,
                                    () -> completeMapped(
                                            target,
                                            value,
                                            failure,
                                            mapper,
                                            failureMapper)));
        } catch (RuntimeException failure) {
            fail(target, failure);
        }
        return target;
    }

    /**
     * Completes a tracked future successfully on a virtual thread.
     *
     * @param target target future
     * @param value result value
     * @param <T> result type
     * @return future completed after the completion task ran
     */
    public <T> CompletableFuture<Void> complete(
            CompletableFuture<T> target, T value) {
        Objects.requireNonNull(target, "target");
        return executeCompletion(target, () -> target.complete(value));
    }

    /**
     * Completes a tracked future exceptionally on a virtual thread.
     *
     * @param target target future
     * @param failure completion failure
     * @return future completed after the completion task ran
     */
    public CompletableFuture<Void> fail(
            CompletableFuture<?> target, Throwable failure) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(failure, "failure");
        return executeCompletion(target, () -> target.completeExceptionally(failure));
    }

    /**
     * Executes internal completion work on a virtual thread when still open.
     *
     * @param task completion work
     */
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        synchronized (lock) {
            if (closed) {
                return;
            }
            try {
                executor.execute(task);
            } catch (RejectedExecutionException ignored) {
                // A concurrent close owns all remaining tracked completions.
            }
        }
    }

    /**
     * Fails every remaining tracked future and shuts down the virtual-thread executor.
     *
     * @param failure shutdown failure exposed to incomplete callers
     * @return shared asynchronous close operation
     */
    public CompletableFuture<Void> close(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        CompletableFuture<Void> operation;
        List<CompletableFuture<?>> pending;
        synchronized (lock) {
            if (closeFuture != null) {
                return closeFuture;
            }
            closed = true;
            operation = new CompletableFuture<>();
            closeFuture = operation;
            pending = new ArrayList<>(trackedFutures);
        }

        List<CompletableFuture<Void>> completions = new ArrayList<>(pending.size());
        for (CompletableFuture<?> future : pending) {
            completions.add(scheduleDuringClose(
                    () -> future.completeExceptionally(failure)));
        }
        CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new))
                .whenComplete(
                        (ignored, completionFailure) -> {
                            executor.shutdownNow();
                            if (completionFailure == null) {
                                operation.complete(null);
                            } else {
                                operation.completeExceptionally(completionFailure);
                            }
                        });
        return operation;
    }

    private CompletableFuture<Void> executeCompletion(
            CompletableFuture<?> target, Runnable task) {
        if (target.isDone()) {
            return CompletableFuture.completedFuture(null);
        }
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.completedFuture(null);
            }
            return schedule(task);
        }
    }

    private CompletableFuture<Void> scheduleDuringClose(Runnable task) {
        synchronized (lock) {
            return schedule(task);
        }
    }

    private CompletableFuture<Void> schedule(Runnable task) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        try {
            executor.execute(
                    () -> {
                        try {
                            task.run();
                            completion.complete(null);
                        } catch (Throwable failure) {
                            if (failure instanceof VirtualMachineError fatal) {
                                completion.completeExceptionally(fatal);
                                throw fatal;
                            }
                            completion.completeExceptionally(failure);
                        }
                    });
        } catch (RejectedExecutionException failure) {
            completion.completeExceptionally(failure);
        }
        return completion;
    }

    private void untrack(CompletableFuture<?> future) {
        synchronized (lock) {
            trackedFutures.remove(future);
        }
    }

    private static <S, T> void completeMapped(
            CompletableFuture<T> target,
            S value,
            Throwable failure,
            Function<? super S, ? extends T> mapper,
            Function<? super Throwable, ? extends Throwable> failureMapper) {
        try {
            if (failure == null) {
                target.complete(mapper.apply(value));
            } else {
                target.completeExceptionally(
                        Objects.requireNonNull(
                                failureMapper.apply(failure),
                                "failureMapper result"));
            }
        } catch (Throwable mappingFailure) {
            target.completeExceptionally(mappingFailure);
            if (mappingFailure instanceof VirtualMachineError fatal) {
                throw fatal;
            }
        }
    }
}
