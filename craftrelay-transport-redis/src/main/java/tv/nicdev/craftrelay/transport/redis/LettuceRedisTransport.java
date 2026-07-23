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
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import tv.nicdev.craftrelay.api.Subscription;
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
    private static final int MAX_PENDING_DELIVERIES_PER_LISTENER = 1_024;

    private final Object lifecycleLock = new Object();
    private final RedisClient client;
    private final RedisURI redisUri;
    private final ExecutorService listenerDispatchExecutor;
    private final Map<String, List<ListenerRegistration>> listeners = new HashMap<>();
    private final Set<String> brokerSubscriptions = new HashSet<>();

    private volatile TransportState state = TransportState.NEW;
    private CompletableFuture<Void> connectFuture;
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
        this.listenerDispatchExecutor = createListenerDispatchExecutor();
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
                return connectFuture;
            }
            if (state == TransportState.CLOSING || state == TransportState.CLOSED) {
                return failedFuture("transport is closing or closed");
            }

            state = TransportState.CONNECTING;
            connectFuture = connectConnections();
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
        synchronized (lifecycleLock) {
            if (state == TransportState.CLOSED) {
                return CompletableFuture.completedFuture(null);
            }
            if (state == TransportState.CLOSING) {
                return closeFuture;
            }

            state = TransportState.CLOSING;
            listeners.values().forEach(registrations ->
                    registrations.forEach(ListenerRegistration::close));
            listeners.clear();
            brokerSubscriptions.clear();
            CompletableFuture<Void> connectionAttempt = connectFuture == null
                    ? CompletableFuture.completedFuture(null)
                    : connectFuture.handle((ignored, failure) -> null);
            closeFuture = connectionAttempt
                    .thenCompose(ignored -> closeConnections())
                    .thenCompose(ignored -> client.shutdownAsync())
                    .whenComplete((ignored, failure) -> {
                        listenerDispatchExecutor.shutdownNow();
                        synchronized (lifecycleLock) {
                            state = TransportState.CLOSED;
                        }
                    });
            return closeFuture;
        }
    }

    private CompletableFuture<Void> connectConnections() {
        CompletableFuture<StatefulRedisConnection<byte[], byte[]>> publisher =
                client.connectAsync(ByteArrayCodec.INSTANCE, redisUri).toCompletableFuture();
        CompletableFuture<StatefulRedisPubSubConnection<byte[], byte[]>> subscriber =
                client.connectPubSubAsync(ByteArrayCodec.INSTANCE, redisUri).toCompletableFuture();

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

    private static ExecutorService createListenerDispatchExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("craftrelay-redis-listener-", 0).factory());
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
            synchronized (lifecycleLock) {
                if (publisher) {
                    publisherConnected = true;
                } else {
                    subscriberConnected = true;
                }
                if (state == TransportState.CONNECTING
                        && publisherConnected
                        && subscriberConnected
                        && connectFuture != null
                        && connectFuture.isDone()) {
                    state = TransportState.CONNECTED;
                }
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
                }
            }
        }
    }

    private final class ListenerRegistration {

        private final TransportListener listener;
        private final Queue<Delivery> deliveries = new ConcurrentLinkedQueue<>();
        private final AtomicInteger pendingDeliveries = new AtomicInteger();
        private final AtomicBoolean draining = new AtomicBoolean();
        private final AtomicBoolean active = new AtomicBoolean(true);

        private ListenerRegistration(TransportListener listener) {
            this.listener = listener;
        }

        private void enqueue(String channel, byte[] payload) {
            if (!active.get()) {
                return;
            }
            int pending = pendingDeliveries.incrementAndGet();
            if (pending > MAX_PENDING_DELIVERIES_PER_LISTENER) {
                pendingDeliveries.decrementAndGet();
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Dropping Redis delivery for slow listener on channel {0}; queue limit is {1}",
                        channel,
                        MAX_PENDING_DELIVERIES_PER_LISTENER);
                return;
            }
            deliveries.add(new Delivery(channel, payload));
            scheduleDrain();
        }

        private void scheduleDrain() {
            if (!draining.compareAndSet(false, true)) {
                return;
            }
            try {
                listenerDispatchExecutor.execute(this::drain);
            } catch (RejectedExecutionException rejection) {
                handleRejectedDelivery(rejection);
            }
        }

        private void drain() {
            try {
                Delivery delivery;
                while ((delivery = deliveries.poll()) != null) {
                    pendingDeliveries.decrementAndGet();
                    if (!active.get()) {
                        continue;
                    }
                    try {
                        listener.onMessage(delivery.channel(), delivery.payload());
                    } catch (RuntimeException failure) {
                        LOGGER.log(
                                System.Logger.Level.WARNING,
                                "Transport listener failed on channel {0}: {1}",
                                delivery.channel(),
                                failure.getMessage());
                    }
                }
            } finally {
                draining.set(false);
                if (!deliveries.isEmpty()) {
                    scheduleDrain();
                }
            }
        }

        private void close() {
            active.set(false);
        }

        private void discardPendingDeliveries() {
            Delivery delivery;
            while ((delivery = deliveries.poll()) != null) {
                pendingDeliveries.decrementAndGet();
            }
        }

        private void handleRejectedDelivery(RejectedExecutionException rejection) {
            draining.set(false);
            discardPendingDeliveries();
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Listener executor rejected Redis message delivery: {0}",
                    rejection.getMessage());
        }
    }

    private record Delivery(String channel, byte[] payload) {}
}
