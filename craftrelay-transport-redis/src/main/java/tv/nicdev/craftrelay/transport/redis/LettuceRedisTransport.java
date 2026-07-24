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

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.common.internal.concurrent.ListenerDispatcher;
import tv.nicdev.craftrelay.common.transport.NetworkTransport;
import tv.nicdev.craftrelay.common.transport.TransportListener;
import tv.nicdev.craftrelay.common.transport.TransportState;

/**
 * Thread-safe Redis Pub/Sub implementation backed by Lettuce.
 *
 * <p>This transport owns its Redis client and both connections. It uses one regular connection for
 * publishing and a separate Pub/Sub connection for receiving.
 */
public final class LettuceRedisTransport implements NetworkTransport {

    private static final System.Logger LOGGER =
            System.getLogger(LettuceRedisTransport.class.getName());

    private final Object lifecycleLock = new Object();
    private final RedisClient client;
    private final RedisURI redisUri;
    private final ListenerDispatcher listenerDispatcher;
    private final Map<String, List<ListenerRegistration>> listeners = new HashMap<>();
    private final Set<String> brokerSubscriptions = new HashSet<>();

    private volatile TransportState state = TransportState.NEW;
    private CompletableFuture<Void> connectFuture;
    private CompletableFuture<Void> reconnectFuture;
    private CompletableFuture<Void> closeFuture;
    private StatefulRedisConnection<byte[], byte[]> publishingConnection;
    private StatefulRedisPubSubConnection<byte[], byte[]> subscriptionConnection;
    private boolean publisherConnected;
    private boolean subscriberConnected;

    /**
     * Creates a transport without opening network connections.
     *
     * @param config validated Redis settings
     */
    public LettuceRedisTransport(RedisTransportConfig config) {
        Objects.requireNonNull(config, "config");
        this.listenerDispatcher =
                new ListenerDispatcher("craftrelay-redis-listener-");
        this.redisUri = createRedisUri(config);
        this.client = RedisClient.create();
        this.client.setOptions(ClientOptions.builder()
                .autoReconnect(true)
                .build());
    }

    @Override
    public CompletableFuture<Void> connect() {
        synchronized (lifecycleLock) {
            if (state == TransportState.CONNECTED) {
                return CompletableFuture.completedFuture(null);
            }
            if (state == TransportState.CONNECTING) {
                return reconnectFuture == null ? connectFuture : reconnectFuture;
            }
            if (state == TransportState.CLOSING || state == TransportState.CLOSED) {
                return failedFuture("transport is closing or closed");
            }

            state = TransportState.CONNECTING;
            try {
                connectFuture = Objects.requireNonNull(
                        connectConnections(), "connectConnections()");
            } catch (RuntimeException failure) {
                state = TransportState.NEW;
                connectFuture = CompletableFuture.failedFuture(failure);
            }
            return connectFuture;
        }
    }

    @Override
    public CompletableFuture<Void> publish(String channel, byte[] payload) {
        String validatedChannel = requireChannel(channel);
        byte[] payloadCopy = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
        StatefulRedisConnection<byte[], byte[]> connection;
        synchronized (lifecycleLock) {
            if (state != TransportState.CONNECTED || publishingConnection == null) {
                return failedFuture("transport is not connected");
            }
            connection = publishingConnection;
        }

        return connection.async()
                .publish(channelBytes(validatedChannel), payloadCopy)
                .toCompletableFuture()
                .thenApply(ignored -> null);
    }

    @Override
    public Subscription subscribe(String channel, TransportListener listener) {
        String validatedChannel = requireChannel(channel);
        TransportListener validatedListener = Objects.requireNonNull(listener, "listener");
        ListenerRegistration registration = new ListenerRegistration(validatedListener);
        boolean subscribeAtBroker = false;
        StatefulRedisPubSubConnection<byte[], byte[]> connection = null;

        synchronized (lifecycleLock) {
            if (state == TransportState.CLOSING || state == TransportState.CLOSED) {
                registration.close();
                throw new IllegalStateException("transport is closing or closed");
            }
            List<ListenerRegistration> channelListeners =
                    listeners.computeIfAbsent(validatedChannel, ignored -> new ArrayList<>());
            channelListeners.add(registration);
            if ((state == TransportState.CONNECTED || state == TransportState.CONNECTING)
                    && subscriptionConnection != null
                    && brokerSubscriptions.add(validatedChannel)) {
                subscribeAtBroker = true;
                connection = subscriptionConnection;
            }
        }

        if (subscribeAtBroker) {
            subscribeAtBroker(connection, validatedChannel);
        }
        return Subscription.create(() -> unsubscribe(validatedChannel, registration));
    }

    @Override
    public TransportState state() {
        return state;
    }

    @Override
    public CompletableFuture<Void> close() {
        CompletableFuture<Void> pendingReconnect;
        CompletableFuture<Void> operation;
        synchronized (lifecycleLock) {
            if (state == TransportState.CLOSED) {
                return closeFuture;
            }
            if (state == TransportState.CLOSING) {
                return closeFuture;
            }

            state = TransportState.CLOSING;
            pendingReconnect = reconnectFuture;
            reconnectFuture = null;
            listeners.values().forEach(registrations ->
                    registrations.forEach(ListenerRegistration::close));
            listeners.clear();
            brokerSubscriptions.clear();
            CompletableFuture<Void> connectionAttempt = connectFuture == null
                    ? CompletableFuture.completedFuture(null)
                    : connectFuture.handle((ignored, failure) -> null);
            closeFuture = connectionAttempt
                    .thenCompose(ignored -> shutdownResources())
                    .whenComplete((ignored, failure) -> {
                        listenerDispatcher.close();
                        synchronized (lifecycleLock) {
                            state = TransportState.CLOSED;
                        }
                    });
            operation = closeFuture;
        }
        if (pendingReconnect != null) {
            pendingReconnect.completeExceptionally(
                    new IllegalStateException("transport closed during reconnect"));
        }
        return operation;
    }

    private CompletableFuture<Void> connectConnections() {
        CompletableFuture<StatefulRedisConnection<byte[], byte[]>> publisher;
        try {
            publisher = client.connectAsync(ByteArrayCodec.INSTANCE, redisUri).toCompletableFuture();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        CompletableFuture<StatefulRedisPubSubConnection<byte[], byte[]>> subscriber;
        try {
            subscriber =
                    client.connectPubSubAsync(ByteArrayCodec.INSTANCE, redisUri)
                            .toCompletableFuture();
        } catch (RuntimeException failure) {
            publisher.thenAccept(StatefulRedisConnection::closeAsync);
            return CompletableFuture.failedFuture(failure);
        }

        return publisher.thenCombine(subscriber, Connections::new)
                .thenCompose(this::activateConnections)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        synchronized (lifecycleLock) {
                            if (state == TransportState.CONNECTING) {
                                state = TransportState.NEW;
                            }
                        }
                        publisher.thenAccept(StatefulRedisConnection::closeAsync);
                        subscriber.thenAccept(StatefulRedisPubSubConnection::closeAsync);
                    }
                });
    }

    private CompletableFuture<Void> activateConnections(Connections connections) {
        List<String> channels;
        synchronized (lifecycleLock) {
            if (state != TransportState.CONNECTING) {
                connections.publisher().closeAsync();
                connections.subscriber().closeAsync();
                return failedFuture("transport was closed while connecting");
            }
            publishingConnection = connections.publisher();
            subscriptionConnection = connections.subscriber();
            publisherConnected = true;
            subscriberConnected = true;
            installConnectionListeners(connections);
            subscriptionConnection.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(byte[] channel, byte[] message) {
                    dispatch(channel, message);
                }
            });
            channels = new ArrayList<>(listeners.keySet());
            brokerSubscriptions.addAll(channels);
        }

        CompletableFuture<?>[] subscriptions = channels.stream()
                .map(channel -> connections.subscriber()
                        .async()
                        .subscribe(channelBytes(channel))
                        .toCompletableFuture())
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(subscriptions).thenRun(() -> {
            synchronized (lifecycleLock) {
                if (state == TransportState.CONNECTING) {
                    state = TransportState.CONNECTED;
                }
            }
        });
    }

    private void installConnectionListeners(Connections connections) {
        connections.publisher().addListener(new ConnectionStateListener(true));
        connections.subscriber().addListener(new ConnectionStateListener(false));
    }

    private void subscribeAtBroker(
            StatefulRedisPubSubConnection<byte[], byte[]> connection, String channel) {
        if (connection == null) {
            return;
        }
        connection.async()
                .subscribe(channelBytes(channel))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        synchronized (lifecycleLock) {
                            brokerSubscriptions.remove(channel);
                        }
                        LOGGER.log(
                                System.Logger.Level.WARNING,
                                "Redis subscription failed for channel {0}: {1}",
                                channel,
                                failure.getMessage());
                    }
                });
    }

    private void unsubscribe(String channel, ListenerRegistration registration) {
        StatefulRedisPubSubConnection<byte[], byte[]> connection = null;
        boolean unsubscribeAtBroker = false;
        synchronized (lifecycleLock) {
            List<ListenerRegistration> channelListeners = listeners.get(channel);
            if (channelListeners == null || !channelListeners.remove(registration)) {
                return;
            }
            registration.close();
            if (channelListeners.isEmpty()) {
                listeners.remove(channel);
                if (brokerSubscriptions.remove(channel)
                        && (state == TransportState.CONNECTED
                                || state == TransportState.CONNECTING)
                        && subscriptionConnection != null) {
                    connection = subscriptionConnection;
                    unsubscribeAtBroker = true;
                }
            }
        }
        if (unsubscribeAtBroker && connection != null) {
            connection.async().unsubscribe(channelBytes(channel));
        }
    }

    private void dispatch(byte[] encodedChannel, byte[] payload) {
        String channel = new String(encodedChannel, StandardCharsets.UTF_8);
        List<ListenerRegistration> channelListeners;
        synchronized (lifecycleLock) {
            List<ListenerRegistration> registered = listeners.get(channel);
            if (registered == null) {
                return;
            }
            channelListeners = List.copyOf(registered);
        }
        for (ListenerRegistration registration : channelListeners) {
            registration.enqueue(channel, Arrays.copyOf(payload, payload.length));
        }
    }

    private CompletableFuture<Void> closeConnections() {
        StatefulRedisConnection<byte[], byte[]> publisher;
        StatefulRedisPubSubConnection<byte[], byte[]> subscriber;
        synchronized (lifecycleLock) {
            publisher = publishingConnection;
            subscriber = subscriptionConnection;
            publishingConnection = null;
            subscriptionConnection = null;
            publisherConnected = false;
            subscriberConnected = false;
        }
        CompletableFuture<Void> publisherClose = publisher == null
                ? CompletableFuture.completedFuture(null)
                : publisher.closeAsync();
        CompletableFuture<Void> subscriberClose = subscriber == null
                ? CompletableFuture.completedFuture(null)
                : subscriber.closeAsync();
        return CompletableFuture.allOf(publisherClose, subscriberClose);
    }

    private CompletableFuture<Void> shutdownResources() {
        return closeConnections()
                .handle((ignored, connectionFailure) -> connectionFailure)
                .thenCompose(connectionFailure ->
                        client.shutdownAsync()
                                .handle((ignored, clientFailure) -> {
                                    Throwable failure =
                                            mergeFailures(connectionFailure, clientFailure);
                                    if (failure != null) {
                                        throw new CompletionException(failure);
                                    }
                                    return null;
                                }));
    }

    private static Throwable mergeFailures(Throwable first, Throwable second) {
        if (first == null) {
            return second;
        }
        if (second != null && second != first) {
            first.addSuppressed(second);
        }
        return first;
    }

    private static RedisURI createRedisUri(RedisTransportConfig config) {
        RedisURI.Builder builder = RedisURI.Builder.redis(config.host(), config.port())
                .withDatabase(config.database())
                .withSsl(config.ssl())
                .withTimeout(config.connectionTimeout());
        if (config.username().isPresent()) {
            builder.withAuthentication(
                    config.username().orElseThrow(),
                    config.password().orElseThrow().toCharArray());
        } else {
            config.password().ifPresent(password -> builder.withPassword(password.toCharArray()));
        }
        return builder.build();
    }

    private static String requireChannel(String channel) {
        Objects.requireNonNull(channel, "channel");
        if (channel.isBlank()) {
            throw new IllegalArgumentException("channel must not be blank");
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(channel)) {
            throw new IllegalArgumentException("channel must be valid Unicode");
        }
        return channel;
    }

    private static byte[] channelBytes(String channel) {
        return channel.getBytes(StandardCharsets.UTF_8);
    }

    private static CompletableFuture<Void> failedFuture(String message) {
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }

    private record Connections(
            StatefulRedisConnection<byte[], byte[]> publisher,
            StatefulRedisPubSubConnection<byte[], byte[]> subscriber) {}

    private final class ConnectionStateListener implements RedisConnectionStateListener {

        private final boolean publisher;

        private ConnectionStateListener(boolean publisher) {
            this.publisher = publisher;
        }

        @Override
        public void onRedisConnected(
                RedisChannelHandler<?, ?> connection, SocketAddress socketAddress) {
            CompletableFuture<Void> readiness = null;
            synchronized (lifecycleLock) {
                if (publisher) {
                    publisherConnected = true;
                } else {
                    subscriberConnected = true;
                }
                if (state == TransportState.CONNECTING
                        && publisherConnected
                        && subscriberConnected
                        && reconnectFuture != null) {
                    state = TransportState.CONNECTED;
                    readiness = reconnectFuture;
                    reconnectFuture = null;
                }
            }
            if (readiness != null) {
                readiness.completeAsync(() -> null);
            }
        }

        @Override
        public void onRedisDisconnected(RedisChannelHandler<?, ?> connection) {
            synchronized (lifecycleLock) {
                if (publisher) {
                    publisherConnected = false;
                } else {
                    subscriberConnected = false;
                }
                if (state == TransportState.CONNECTED) {
                    state = TransportState.CONNECTING;
                    reconnectFuture = new CompletableFuture<>();
                }
            }
        }
    }

    private final class ListenerRegistration {

        private final TransportListener listener;
        private final ListenerDispatcher.DispatchLane<Delivery> dispatchLane;

        private ListenerRegistration(TransportListener listener) {
            this.listener = listener;
            this.dispatchLane = listenerDispatcher.register(
                    ListenerDispatcher.DEFAULT_QUEUE_CAPACITY,
                    this::deliver,
                    failure -> LOGGER.log(
                            System.Logger.Level.WARNING,
                            "Transport listener failed: {0}",
                            failure.getMessage()),
                    () -> LOGGER.log(
                            System.Logger.Level.WARNING,
                            "Dropping Redis delivery for a slow listener; queue limit is {0}",
                            ListenerDispatcher.DEFAULT_QUEUE_CAPACITY));
        }

        private void enqueue(String channel, byte[] payload) {
            dispatchLane.dispatch(new Delivery(channel, payload));
        }

        private void deliver(Delivery delivery) {
            listener.onMessage(delivery.channel(), delivery.payload());
        }

        private void close() {
            dispatchLane.close();
        }
    }

    private record Delivery(String channel, byte[] payload) {}
}
