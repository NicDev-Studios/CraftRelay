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
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.exception.RequestTimeoutException;
import tv.nicdev.craftrelay.api.message.GlobalBroadcastMessage;
import tv.nicdev.craftrelay.api.message.PlayerLocationRequest;
import tv.nicdev.craftrelay.api.message.PlayerLocationResponse;
import tv.nicdev.craftrelay.api.target.NetworkTargets;
import tv.nicdev.craftrelay.common.internal.concurrent.FutureCompletionDispatcher;
import tv.nicdev.craftrelay.common.testing.TestMessagingRuntime;

class PendingRequestManagerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private TestMessagingRuntime runtime;
    private FutureCompletionDispatcher completionDispatcher;
    private PendingRequestManager manager;

    @BeforeEach
    void setUp() {
        runtime = new TestMessagingRuntime();
        completionDispatcher =
                new FutureCompletionDispatcher("pending-request-test-");
        manager =
                new PendingRequestManager(
                        runtime,
                        new RequestRuntimeConfig(2),
                        completionDispatcher);
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.close();
        completionDispatcher.close(new ApiUnavailableException("test shutdown"))
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Test
    void registersBeforePublishingAndAcceptsAnImmediateSelfResponse()
            throws Exception {
        UUID playerId = UUID.randomUUID();
        PlayerLocationResponse expected =
                new PlayerLocationResponse(playerId, Optional.empty());
        runtime.onPublish(
                published ->
                        runtime.emit(
                                "node-a",
                                published.target(),
                                published.correlationId(),
                                expected));

        PlayerLocationResponse actual =
                manager.request(
                                NetworkTargets.instance("node-a"),
                                new PlayerLocationRequest(playerId),
                                PlayerLocationResponse.class,
                                TIMEOUT)
                        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertEquals(expected, actual);
        assertEquals(0, manager.pendingCount());
    }

    @Test
    void requiresMatchingTypeCorrelationAndInstanceSource() throws Exception {
        UUID playerId = UUID.randomUUID();
        CompletableFuture<PlayerLocationResponse> result =
                manager.request(
                        NetworkTargets.instance("expected-node"),
                        new PlayerLocationRequest(playerId),
                        PlayerLocationResponse.class,
                        TIMEOUT);
        UUID correlationId =
                runtime.lastPublished().correlationId().orElseThrow();

        runtime.emit(
                "expected-node",
                NetworkTargets.instance("requester"),
                Optional.of(UUID.randomUUID()),
                new PlayerLocationResponse(playerId, Optional.empty()));
        runtime.emit(
                "expected-node",
                NetworkTargets.instance("requester"),
                Optional.of(correlationId),
                new GlobalBroadcastMessage("wrong type"));
        runtime.emit(
                "wrong-node",
                NetworkTargets.instance("requester"),
                Optional.of(correlationId),
                new PlayerLocationResponse(playerId, Optional.empty()));
        assertEquals(1, manager.pendingCount());

        PlayerLocationResponse expected =
                new PlayerLocationResponse(playerId, Optional.empty());
        runtime.emit(
                "expected-node",
                NetworkTargets.instance("requester"),
                Optional.of(correlationId),
                expected);

        assertEquals(
                expected,
                result.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(0, manager.pendingCount());
    }

    @Test
    void firstTypedResponseWinsForMultiTargets() throws Exception {
        UUID playerId = UUID.randomUUID();
        CompletableFuture<PlayerLocationResponse> result =
                manager.request(
                        NetworkTargets.group("eu"),
                        new PlayerLocationRequest(playerId),
                        PlayerLocationResponse.class,
                        TIMEOUT);
        UUID correlationId =
                runtime.lastPublished().correlationId().orElseThrow();
        PlayerLocationResponse first =
                new PlayerLocationResponse(playerId, Optional.empty());

        runtime.emit(
                "server-1",
                NetworkTargets.instance("requester"),
                Optional.of(correlationId),
                first);
        runtime.emit(
                "server-2",
                NetworkTargets.instance("requester"),
                Optional.of(correlationId),
                new PlayerLocationResponse(playerId, Optional.empty()));

        assertEquals(
                first,
                result.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(0, manager.pendingCount());
    }

    @Test
    void validatesArgumentsCapacityCancellationAndShutdown() throws Exception {
        PlayerLocationRequest request =
                new PlayerLocationRequest(UUID.randomUUID());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manager.request(
                                NetworkTargets.allInstances(),
                                request,
                                PlayerLocationResponse.class,
                                Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manager.request(
                                NetworkTargets.allInstances(),
                                request,
                                PlayerLocationRequest.class,
                                TIMEOUT));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manager.request(
                                NetworkTargets.allInstances(),
                                request,
                                PlayerLocationResponse.class,
                                Duration.ofSeconds(Long.MAX_VALUE)));

        CompletableFuture<PlayerLocationResponse> first =
                manager.request(
                        NetworkTargets.allInstances(),
                        request,
                        PlayerLocationResponse.class,
                        TIMEOUT);
        CompletableFuture<PlayerLocationResponse> second =
                manager.request(
                        NetworkTargets.allInstances(),
                        request,
                        PlayerLocationResponse.class,
                        TIMEOUT);
        CompletableFuture<PlayerLocationResponse> overCapacity =
                manager.request(
                        NetworkTargets.allInstances(),
                        request,
                        PlayerLocationResponse.class,
                        TIMEOUT);
        assertInstanceOf(
                ApiUnavailableException.class,
                assertThrows(
                                ExecutionException.class,
                                () ->
                                        overCapacity.get(
                                                TIMEOUT.toMillis(),
                                                TimeUnit.MILLISECONDS))
                        .getCause());

        assertTrue(first.cancel(false));
        assertEquals(1, manager.pendingCount());
        manager.close();
        assertEquals(0, manager.pendingCount());
        assertInstanceOf(
                ApiUnavailableException.class,
                assertThrows(
                                ExecutionException.class,
                                () ->
                                        second.get(
                                                TIMEOUT.toMillis(),
                                                TimeUnit.MILLISECONDS))
                        .getCause());
    }

    @Test
    void timeoutAndPublishFailureRemovePendingEntries() throws Exception {
        CompletableFuture<PlayerLocationResponse> timedOut =
                manager.request(
                        NetworkTargets.allInstances(),
                        new PlayerLocationRequest(UUID.randomUUID()),
                        PlayerLocationResponse.class,
                        Duration.ofMillis(25));
        assertInstanceOf(
                RequestTimeoutException.class,
                assertThrows(
                                ExecutionException.class,
                                () ->
                                        timedOut.get(
                                                TIMEOUT.toMillis(),
                                                TimeUnit.MILLISECONDS))
                        .getCause());
        assertEquals(0, manager.pendingCount());

        runtime.onPublish(
                ignored -> {
                    throw new IllegalStateException("publish failed");
                });
        CompletableFuture<PlayerLocationResponse> failed =
                manager.request(
                        NetworkTargets.allInstances(),
                        new PlayerLocationRequest(UUID.randomUUID()),
                        PlayerLocationResponse.class,
                        TIMEOUT);
        assertEquals(
                "publish failed",
                assertThrows(
                                ExecutionException.class,
                                () ->
                                        failed.get(
                                                TIMEOUT.toMillis(),
                                                TimeUnit.MILLISECONDS))
                        .getCause()
                        .getMessage());
        assertEquals(0, manager.pendingCount());
    }

    @Test
    void responseCancellationTimeoutAndShutdownRacesLeaveNoPendingRequests()
            throws Exception {
        manager.close();
        manager =
                new PendingRequestManager(
                        runtime,
                        new RequestRuntimeConfig(64),
                        completionDispatcher);
        List<CompletableFuture<PlayerLocationResponse>> results =
                new ArrayList<>();
        List<UUID> correlations = new ArrayList<>();
        List<UUID> playerIds = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            UUID playerId = UUID.randomUUID();
            CompletableFuture<PlayerLocationResponse> result =
                    manager.request(
                            NetworkTargets.allInstances(),
                            new PlayerLocationRequest(playerId),
                            PlayerLocationResponse.class,
                            Duration.ofMillis(100));
            results.add(result);
            correlations.add(
                    runtime.lastPublished().correlationId().orElseThrow());
            playerIds.add(playerId);
        }

        try (ExecutorService racers =
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual()
                                .name("request-race-test-", 0)
                                .factory())) {
            for (int index = 0; index < results.size(); index++) {
                int captured = index;
                racers.execute(
                        () -> {
                            if (captured % 3 == 0) {
                                results.get(captured).cancel(false);
                            } else if (captured % 3 == 1) {
                                runtime.emit(
                                        "responder",
                                        NetworkTargets.instance("requester"),
                                        Optional.of(correlations.get(captured)),
                                        new PlayerLocationResponse(
                                                playerIds.get(captured),
                                                Optional.empty()));
                            }
                        });
            }
            manager.close();
        }

        CompletableFuture.allOf(
                        results.stream()
                                .map(
                                        result ->
                                                result.handle(
                                                        (value, failure) -> null))
                                .toArray(CompletableFuture[]::new))
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(0, manager.pendingCount());
    }
}
