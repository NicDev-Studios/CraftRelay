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
package tv.nicdev.craftrelay.common.internal.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.CraftRelayState;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.message.GlobalBroadcastMessage;
import tv.nicdev.craftrelay.api.message.PlayerLocationRequest;
import tv.nicdev.craftrelay.api.message.PlayerLocationResponse;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.api.messaging.MessagePayloadCodec;
import tv.nicdev.craftrelay.api.messaging.MessageRegistration;
import tv.nicdev.craftrelay.api.messaging.MessageType;
import tv.nicdev.craftrelay.api.target.NetworkTargets;
import tv.nicdev.craftrelay.common.internal.runtime.LocalInstanceIdentity;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntimeConfig;
import tv.nicdev.craftrelay.common.internal.presence.InstancePresenceConfig;
import tv.nicdev.craftrelay.common.internal.presence.PlayerPresenceConfig;
import tv.nicdev.craftrelay.common.testing.TestNetworkTransport;
import tv.nicdev.craftrelay.common.testing.TestNetworkPresenceStore;

class CraftRelayNodeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void exposesLifecycleSafeApiStateSnapshotsAndSelfRequests()
            throws Exception {
        UUID playerId = UUID.randomUUID();
        NetworkPlayer player = player(playerId);
        List<NetworkInstance> mutableInstances =
                new ArrayList<>(List.of(instance("proxy-1")));
        TestNetworkPresenceStore instanceStore = new TestNetworkPresenceStore();
        mutableInstances.forEach(value -> instanceStore.seed(value, "seed"));
        instanceStore.seed(player, "seed");
        CraftRelayNode node =
                node(new TestNetworkTransport(), instanceStore);
        CraftRelayApi api = node.api();

        assertEquals(CraftRelayState.INITIALIZING, api.state());
        assertFutureFailure(
                ApiUnavailableException.class,
                api.publish(
                        NetworkTargets.allInstances(),
                        new GlobalBroadcastMessage("too early")));
        assertThrows(
                ApiUnavailableException.class,
                () ->
                        api.subscribe(
                                GlobalBroadcastMessage.class,
                                ignored -> {}));

        CompletableFuture<Void> firstStart = node.start();
        CompletableFuture<Void> secondStart = node.start();
        assertSame(firstStart, secondStart);
        firstStart.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(CraftRelayState.AVAILABLE, api.state());

        Collection<NetworkInstance> snapshot =
                api.instances().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        mutableInstances.clear();
        assertEquals(2, snapshot.size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.clear());
        assertEquals(
                Optional.of(player),
                api.player(playerId)
                        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

        CompletableFuture<Boolean> callbackThread =
                api.request(
                                NetworkTargets.instance("node-a"),
                                new PlayerLocationRequest(playerId),
                                PlayerLocationResponse.class,
                                TIMEOUT)
                        .thenApply(
                                response ->
                                        response.player().equals(Optional.of(player))
                                                && Thread.currentThread().isVirtual());
        assertTrue(
                callbackThread.get(
                        TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

        CompletableFuture<Void> firstClose = node.close();
        CompletableFuture<Void> secondClose = node.close();
        assertSame(firstClose, secondClose);
        firstClose.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(CraftRelayState.STOPPED, api.state());
        assertFutureFailure(
                ApiUnavailableException.class,
                api.player(playerId));
    }

    @Test
    void failedStartCanBeRetriedAndShutdownFailsPendingRequests()
            throws Exception {
        TestNetworkTransport transport = new TestNetworkTransport();
        transport.failNextConnects(1);
        CraftRelayNode node = node(
                transport,
                new TestNetworkPresenceStore());
        CraftRelayApi api = node.api();

        assertThrows(
                ExecutionException.class,
                () -> node.start().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(CraftRelayState.INITIALIZING, api.state());
        node.start().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        CompletableFuture<PlayerLocationResponse> pending =
                api.request(
                        NetworkTargets.instance("missing-node"),
                        new PlayerLocationRequest(UUID.randomUUID()),
                        PlayerLocationResponse.class,
                        TIMEOUT);
        node.close().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertFutureFailure(ApiUnavailableException.class, pending);
        assertEquals(2, transport.connectCalls());
    }

    @Test
    void lostInstanceLeaseTriggersControlledNodeShutdown() {
        TestNetworkPresenceStore store = new TestNetworkPresenceStore();
        CraftRelayNode node = CraftRelayNodes.create(
                new TestNetworkTransport(),
                new LocalInstanceIdentity(
                        "node-a",
                        NetworkInstanceType.PROXY,
                        Optional.of("eu")),
                MessagingRuntimeConfig.defaults(),
                tv.nicdev.craftrelay.common.internal.request.RequestRuntimeConfig.defaults(),
                new InstancePresenceConfig(
                        "test", Duration.ofMillis(20), Duration.ofMillis(100), 8),
                new PlayerPresenceConfig(
                        "test", Duration.ofMillis(20), Duration.ofMillis(100), 8),
                store,
                () -> 0);
        node.start().join();

        store.forceRemove("node-a");
        awaitState(node.api(), CraftRelayState.STOPPED, System.nanoTime() + TIMEOUT.toNanos())
                .join();

        assertEquals(CraftRelayState.STOPPED, node.api().state());
        assertFutureFailureUnchecked(
                ApiUnavailableException.class, node.api().instances());
    }

    @Test
    void exposesLifecycleSafeCustomMessagingAndCorrelatedHandlers() throws Exception {
        CraftRelayNode node =
                node(new TestNetworkTransport(), new TestNetworkPresenceStore());
        CraftRelayApi api = node.api();
        MessageType<CustomRequest> requestType =
                MessageType.of("example", "echo_request", 1, CustomRequest.class);
        MessageType<CustomResponse> responseType =
                MessageType.of("example", "echo_response", 1, CustomResponse.class);

        assertThrows(
                ApiUnavailableException.class,
                () -> api.customMessaging().register(requestType, requestCodec()));
        node.start().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        var requestRegistration =
                api.customMessaging().register(requestType, requestCodec());
        var responseRegistration =
                api.customMessaging().register(responseType, responseCodec());
        CompletableFuture<Boolean> handlerThread = new CompletableFuture<>();
        var handlerRegistration =
                api.customMessaging()
                        .handle(
                                requestRegistration,
                                responseRegistration,
                                (request, context) -> {
                                    handlerThread.complete(
                                            Thread.currentThread().isVirtual()
                                                    && context.sourceInstance().equals("node-a"));
                                    return CompletableFuture.completedFuture(
                                            new CustomResponse(request.value()));
                                });

        CustomResponse response =
                api.request(
                                NetworkTargets.instance("node-a"),
                                new CustomRequest("hello"),
                                CustomResponse.class,
                                TIMEOUT)
                        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertEquals(new CustomResponse("hello"), response);
        assertTrue(handlerThread.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        api.customMessaging()
                                .handle(
                                        requestRegistration,
                                        responseRegistration,
                                        (request, context) ->
                                                CompletableFuture.completedFuture(
                                                        new CustomResponse("duplicate"))));

        requestRegistration.close();
        assertTrue(requestRegistration.isClosed());
        assertTrue(handlerRegistration.isClosed());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        api.customMessaging()
                                .handle(
                                        requestRegistration,
                                        responseRegistration,
                                        (request, context) ->
                                                CompletableFuture.completedFuture(
                                                        new CustomResponse("closed"))));
        assertFutureFailure(
                tv.nicdev.craftrelay.api.exception.InvalidMessageException.class,
                api.publish(
                        NetworkTargets.allInstances(),
                        new CustomRequest("unregistered")));

        var replacementRequest =
                api.customMessaging().register(requestType, requestCodec());
        requestRegistration.close();
        assertFalse(replacementRequest.isClosed());
        MessageRegistration<CustomResponse> foreignResponse =
                new MessageRegistration<>() {
                    @Override
                    public MessageType<CustomResponse> type() {
                        return responseType;
                    }

                    @Override
                    public boolean isClosed() {
                        return false;
                    }

                    @Override
                    public void close() {
                    }
                };
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        api.customMessaging()
                                .handle(
                                        replacementRequest,
                                        foreignResponse,
                                        (request, context) ->
                                                CompletableFuture.completedFuture(
                                                        new CustomResponse("foreign"))));

        replacementRequest.close();
        responseRegistration.close();
        node.close().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThrows(
                ApiUnavailableException.class,
                () -> api.customMessaging().register(requestType, requestCodec()));
    }

    private static CraftRelayNode node(
            TestNetworkTransport transport,
            TestNetworkPresenceStore presenceStore) {
        return CraftRelayNodes.create(
                transport,
                new LocalInstanceIdentity(
                        "node-a",
                        NetworkInstanceType.PROXY,
                        Optional.of("eu")),
                MessagingRuntimeConfig.defaults(),
                presenceStore,
                () -> 0);
    }

    private static NetworkInstance instance(String id) {
        Instant now = Instant.now();
        return new NetworkInstance(
                id,
                NetworkInstanceType.PROXY,
                Optional.of("eu"),
                now,
                now,
                1);
    }

    private static NetworkPlayer player(UUID playerId) {
        Instant now = Instant.now();
        return new NetworkPlayer(
                playerId,
                "Player",
                "proxy-1",
                Optional.of("lobby"),
                UUID.randomUUID(),
                now,
                now);
    }

    private static MessagePayloadCodec<CustomRequest> requestCodec() {
        return textCodec(CustomRequest::value, CustomRequest::new);
    }

    private static MessagePayloadCodec<CustomResponse> responseCodec() {
        return textCodec(CustomResponse::value, CustomResponse::new);
    }

    private static <M extends tv.nicdev.craftrelay.api.NetworkMessage>
            MessagePayloadCodec<M> textCodec(
                    java.util.function.Function<M, String> encoder,
                    java.util.function.Function<String, M> decoder) {
        return new MessagePayloadCodec<>() {
            @Override
            public byte[] encode(M message) {
                return ("{\"value\":\"" + encoder.apply(message) + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public M decode(byte[] payload) {
                String json = new String(payload, StandardCharsets.UTF_8);
                int start = json.indexOf(':') + 2;
                int end = json.lastIndexOf('"');
                return decoder.apply(json.substring(start, end));
            }
        };
    }

    private record CustomRequest(String value)
            implements tv.nicdev.craftrelay.api.NetworkMessage {
    }

    private record CustomResponse(String value)
            implements tv.nicdev.craftrelay.api.NetworkMessage {
    }

    private static void assertFutureFailure(
            Class<? extends Throwable> expected,
            CompletableFuture<?> future)
            throws Exception {
        ExecutionException failure =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                future.get(
                                        TIMEOUT.toMillis(),
                                        TimeUnit.MILLISECONDS));
        assertInstanceOf(expected, failure.getCause());
    }

    private static void assertFutureFailureUnchecked(
            Class<? extends Throwable> expected, CompletableFuture<?> future) {
        java.util.concurrent.CompletionException failure =
                assertThrows(java.util.concurrent.CompletionException.class, future::join);
        assertInstanceOf(expected, failure.getCause());
    }

    private static CompletableFuture<Void> awaitState(
            CraftRelayApi api, CraftRelayState expected, long deadline) {
        if (api.state() == expected) {
            return CompletableFuture.completedFuture(null);
        }
        if (System.nanoTime() >= deadline) {
            return CompletableFuture.failedFuture(
                    new AssertionError("API did not reach state " + expected));
        }
        return CompletableFuture.runAsync(
                        () -> {},
                        CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS))
                .thenCompose(ignored -> awaitState(api, expected, deadline));
    }

}
