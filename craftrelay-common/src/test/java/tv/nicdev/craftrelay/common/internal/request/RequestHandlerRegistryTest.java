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
package tv.nicdev.craftrelay.common.internal.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.message.GlobalBroadcastMessage;
import tv.nicdev.craftrelay.api.message.PlayerConnectRequest;
import tv.nicdev.craftrelay.api.message.PlayerLocationRequest;
import tv.nicdev.craftrelay.api.message.PlayerLocationResponse;
import tv.nicdev.craftrelay.api.target.NetworkTarget;
import tv.nicdev.craftrelay.api.target.NetworkTargets;
import tv.nicdev.craftrelay.common.internal.concurrent.FutureCompletionDispatcher;
import tv.nicdev.craftrelay.common.testing.TestMessagingRuntime;

class RequestHandlerRegistryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private TestMessagingRuntime runtime;
    private FutureCompletionDispatcher completionDispatcher;
    private RequestHandlerRegistry registry;

    @BeforeEach
    void setUp() {
        runtime = new TestMessagingRuntime();
        completionDispatcher =
                new FutureCompletionDispatcher("request-handler-test-");
        registry =
                RequestHandlerRegistries.create(runtime, completionDispatcher);
    }

    @AfterEach
    void tearDown() throws Exception {
        registry.close();
        completionDispatcher.close(new ApiUnavailableException("test shutdown"))
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Test
    void rejectsDuplicateHandlersAndRepliesToTheRequestSource()
            throws Exception {
        UUID playerId = UUID.randomUUID();
        CountDownLatch published = new CountDownLatch(1);
        List<TestMessagingRuntime.PublishedMessage> responses =
                new CopyOnWriteArrayList<>();
        runtime.onPublish(
                response -> {
                    responses.add(response);
                    published.countDown();
                });
        registry.register(
                PlayerLocationRequest.class,
                (request, context) ->
                        CompletableFuture.completedFuture(
                                new PlayerLocationResponse(
                                        request.playerId(),
                                        Optional.empty())));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        registry.register(
                                PlayerLocationRequest.class,
                                (request, context) ->
                                        CompletableFuture.completedFuture(
                                                new PlayerLocationResponse(
                                                        request.playerId(),
                                                        Optional.empty()))));

        UUID correlationId = UUID.randomUUID();
        runtime.emit(
                "proxy-eu-1",
                NetworkTargets.allInstances(),
                Optional.of(correlationId),
                new PlayerLocationRequest(playerId));

        assertTrue(published.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        TestMessagingRuntime.PublishedMessage response = responses.getFirst();
        assertEquals(
                new NetworkTarget.Instance("proxy-eu-1"),
                response.target());
        assertEquals(Optional.of(correlationId), response.correlationId());
        assertInstanceOf(PlayerLocationResponse.class, response.message());
    }

    @Test
    void blockingAndFailingHandlersCannotDelayIndependentHandlers()
            throws Exception {
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch responses = new CountDownLatch(2);
        CountDownLatch fastResponse = new CountDownLatch(1);
        List<String> responseContents = new CopyOnWriteArrayList<>();
        runtime.onPublish(
                published -> {
                    if (published.message()
                            instanceof GlobalBroadcastMessage broadcast) {
                        responseContents.add(broadcast.content());
                        fastResponse.countDown();
                    }
                    responses.countDown();
                });
        registry.register(
                PlayerLocationRequest.class,
                (request, context) -> {
                    slowEntered.countDown();
                    await(releaseSlow);
                    return CompletableFuture.completedFuture(
                            new PlayerLocationResponse(
                                    request.playerId(),
                                    Optional.empty()));
                });
        registry.register(
                PlayerConnectRequest.class,
                (request, context) ->
                        CompletableFuture.completedFuture(
                                new GlobalBroadcastMessage("fast")));

        runtime.emit(
                "requester",
                NetworkTargets.allInstances(),
                Optional.of(UUID.randomUUID()),
                new PlayerLocationRequest(UUID.randomUUID()));
        assertTrue(slowEntered.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        runtime.emit(
                "requester",
                NetworkTargets.allInstances(),
                Optional.of(UUID.randomUUID()),
                new PlayerConnectRequest(UUID.randomUUID(), "lobby"));

        assertTrue(
                fastResponse.await(
                        TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(List.of("fast"), responseContents);
        releaseSlow.countDown();
        assertTrue(responses.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
    }

    @Test
    void handlerFailuresProduceNoRemoteResponse() throws Exception {
        CountDownLatch published = new CountDownLatch(1);
        runtime.onPublish(ignored -> published.countDown());
        registry.register(
                PlayerLocationRequest.class,
                (request, context) -> {
                    throw new IllegalStateException("expected handler failure");
                });

        runtime.emit(
                "requester",
                NetworkTargets.allInstances(),
                Optional.of(UUID.randomUUID()),
                new PlayerLocationRequest(UUID.randomUUID()));

        org.junit.jupiter.api.Assertions.assertFalse(
                published.await(150, TimeUnit.MILLISECONDS));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test handler interrupted", failure);
        }
    }
}
