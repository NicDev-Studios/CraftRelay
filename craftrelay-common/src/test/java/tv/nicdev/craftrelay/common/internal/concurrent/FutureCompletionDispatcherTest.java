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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FutureCompletionDispatcherTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void relaysResultsAndUserContinuationsOnVirtualThreads() throws Exception {
        FutureCompletionDispatcher dispatcher =
                new FutureCompletionDispatcher("completion-test-");
        try {
            CompletableFuture<String> source = new CompletableFuture<>();
            CompletableFuture<Boolean> continuation =
                    dispatcher.relay(source, String::toUpperCase, failure -> failure)
                            .thenApply(
                                    value ->
                                            value.equals("READY")
                                                    && Thread.currentThread().isVirtual());

            source.complete("ready");

            assertTrue(continuation.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        } finally {
            dispatcher.close(new IllegalStateException("test shutdown"))
                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void mapperFailuresAndShutdownNeverLeaveTrackedFuturesIncomplete()
            throws Exception {
        FutureCompletionDispatcher dispatcher =
                new FutureCompletionDispatcher("completion-failure-test-");
        CompletableFuture<String> mapped =
                dispatcher.relay(
                        CompletableFuture.completedFuture("value"),
                        ignored -> {
                            throw new IllegalArgumentException("bad mapping");
                        },
                        failure -> failure);
        ExecutionException mappingFailure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        ExecutionException.class,
                        () -> mapped.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertInstanceOf(IllegalArgumentException.class, mappingFailure.getCause());

        CompletableFuture<String> unfinished = dispatcher.newFuture();
        dispatcher.close(new IllegalStateException("closed"))
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        ExecutionException closeFailure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        ExecutionException.class,
                        () -> unfinished.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertEquals("closed", closeFailure.getCause().getMessage());
    }
}
