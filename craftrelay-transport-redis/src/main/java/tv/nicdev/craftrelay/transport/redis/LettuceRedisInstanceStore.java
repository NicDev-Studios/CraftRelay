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

import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.StringCodec;
import java.net.SocketAddress;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.common.internal.concurrent.AsyncFailures;
import tv.nicdev.craftrelay.common.internal.presence.InstancePresenceConfig;
import tv.nicdev.craftrelay.common.internal.state.NetworkInstanceStore;

/**
 * Redis-backed, server-time-based instance lease store.
 *
 * <p>This implementation is internal CraftRelay composition API. It never exposes Lettuce types
 * through the public plugin API.
 */
public final class LettuceRedisInstanceStore implements NetworkInstanceStore {

    private static final String CLAIM_SCRIPT = """
            local nowParts = redis.call('TIME')
            local now = (tonumber(nowParts[1]) * 1000) + math.floor(tonumber(nowParts[2]) / 1000)
            local currentToken = redis.call('HGET', KEYS[1], 'leaseToken')
            local currentExpiry = tonumber(redis.call('ZSCORE', KEYS[2], ARGV[1]) or '0')
            if currentToken and currentToken ~= ARGV[2] and currentExpiry > now then
                return 0
            end
            local expiresAt = now + tonumber(ARGV[3])
            redis.call('HSET', KEYS[1],
                'id', ARGV[4],
                'type', ARGV[5],
                'groupPresent', ARGV[6],
                'group', ARGV[7],
                'startedAt', ARGV[8],
                'lastHeartbeat', ARGV[9],
                'onlinePlayerCount', ARGV[10],
                'leaseToken', ARGV[2])
            redis.call('PEXPIREAT', KEYS[1], expiresAt)
            redis.call('ZADD', KEYS[2], expiresAt, ARGV[1])
            return 1
            """;

    private static final String HEARTBEAT_SCRIPT = """
            if redis.call('HGET', KEYS[1], 'leaseToken') ~= ARGV[2] then
                return 0
            end
            local nowParts = redis.call('TIME')
            local now = (tonumber(nowParts[1]) * 1000) + math.floor(tonumber(nowParts[2]) / 1000)
            local expiresAt = now + tonumber(ARGV[3])
            redis.call('HSET', KEYS[1],
                'id', ARGV[4],
                'type', ARGV[5],
                'groupPresent', ARGV[6],
                'group', ARGV[7],
                'startedAt', ARGV[8],
                'lastHeartbeat', ARGV[9],
                'onlinePlayerCount', ARGV[10])
            redis.call('PEXPIREAT', KEYS[1], expiresAt)
            redis.call('ZADD', KEYS[2], expiresAt, ARGV[1])
            return 1
            """;

    private static final String RELEASE_SCRIPT = """
            if redis.call('HGET', KEYS[1], 'leaseToken') ~= ARGV[2] then
                return 0
            end
            redis.call('DEL', KEYS[1])
            redis.call('ZREM', KEYS[2], ARGV[1])
            return 1
            """;

    private static final String CLEANUP_SCRIPT = """
            local nowParts = redis.call('TIME')
            local now = (tonumber(nowParts[1]) * 1000) + math.floor(tonumber(nowParts[2]) / 1000)
            local expired = redis.call(
                'ZRANGEBYSCORE', KEYS[1], '-inf', now, 'LIMIT', 0, tonumber(ARGV[1]))
            for _, member in ipairs(expired) do
                redis.call('DEL', ARGV[2] .. member)
                redis.call('ZREM', KEYS[1], member)
            end
            return #expired
            """;

    private final Object lifecycleLock = new Object();
    private final LettuceRedisBackend backend;
    private final InstancePresenceConfig config;
    private final boolean closesBackend;
    private final String indexKey;
    private final String instanceKeyPrefix;
    private final ExecutorService stateExecutor =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("craftrelay-redis-state-", 0).factory());

    private StoreState state = StoreState.NEW;
    private CompletableFuture<Void> connectFuture;
    private CompletableFuture<Void> reconnectFuture;
    private CompletableFuture<Void> closeFuture;
    private StatefulRedisConnection<String, String> connection;

    /** Creates a standalone instance store that owns its Lettuce backend. */
    public LettuceRedisInstanceStore(
            RedisTransportConfig redisConfig, InstancePresenceConfig presenceConfig) {
        this(
                new LettuceRedisBackend(Objects.requireNonNull(redisConfig, "redisConfig")),
                presenceConfig,
                true);
    }

    LettuceRedisInstanceStore(
            LettuceRedisBackend backend,
            InstancePresenceConfig config,
            boolean closesBackend) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.config = Objects.requireNonNull(config, "config");
        this.closesBackend = closesBackend;
        indexKey = config.keyPrefix() + ":presence:instances";
        instanceKeyPrefix = config.keyPrefix() + ":presence:instance:";
    }

    @Override
    public CompletableFuture<Void> connect() {
        synchronized (lifecycleLock) {
            if (state == StoreState.CONNECTED) {
                return CompletableFuture.completedFuture(null);
            }
            if (state == StoreState.CONNECTING) {
                return reconnectFuture == null ? connectFuture : reconnectFuture;
            }
            if (state == StoreState.CLOSING || state == StoreState.CLOSED) {
                return unavailableFuture("Instance store is closing or closed", null);
            }
            state = StoreState.CONNECTING;
            try {
                CompletableFuture<Void> attempt = mapFailure(
                        backend.client()
                                .connectAsync(StringCodec.UTF8, backend.redisUri())
                                .toCompletableFuture()
                                .thenAccept(this::activateConnection),
                        "Could not connect Redis instance store");
                connectFuture = attempt.whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        synchronized (lifecycleLock) {
                            if (state == StoreState.CONNECTING) {
                                state = StoreState.NEW;
                            }
                        }
                    }
                });
            } catch (RuntimeException failure) {
                state = StoreState.NEW;
                connectFuture = unavailableFuture(
                        "Could not connect Redis instance store", failure);
            }
            return connectFuture;
        }
    }

    @Override
    public CompletableFuture<Boolean> claim(
            NetworkInstance instance, String leaseToken, Duration ttl) {
        NetworkInstance value = Objects.requireNonNull(instance, "instance");
        String token = requireText(leaseToken, "leaseToken");
        long ttlMillis = requirePositiveMillis(ttl);
        String member = encodeId(value.id());
        String[] arguments = mutationArguments(member, token, ttlMillis, value);
        return command(
                commands -> commands.eval(
                        CLAIM_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[] {instanceKey(member), indexKey},
                        arguments),
                "Could not claim instance lease")
                .thenApply(result -> ((Number) result).longValue() == 1L);
    }

    @Override
    public CompletableFuture<Boolean> heartbeat(
            NetworkInstance instance, String leaseToken, Duration ttl) {
        NetworkInstance value = Objects.requireNonNull(instance, "instance");
        String token = requireText(leaseToken, "leaseToken");
        long ttlMillis = requirePositiveMillis(ttl);
        String member = encodeId(value.id());
        String[] arguments = mutationArguments(member, token, ttlMillis, value);
        return command(
                commands -> commands.eval(
                        HEARTBEAT_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[] {instanceKey(member), indexKey},
                        arguments),
                "Could not renew instance lease")
                .thenApply(result -> ((Number) result).longValue() == 1L);
    }

    @Override
    public CompletableFuture<Boolean> release(String instanceId, String leaseToken) {
        String member = encodeId(requireText(instanceId, "instanceId"));
        String token = requireText(leaseToken, "leaseToken");
        return command(
                commands -> commands.eval(
                        RELEASE_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[] {instanceKey(member), indexKey},
                        member,
                        token),
                "Could not release instance lease")
                .thenApply(result -> ((Number) result).longValue() == 1L);
    }

    @Override
    public CompletableFuture<Void> cleanupExpired(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        return command(
                commands -> commands.eval(
                        CLEANUP_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[] {indexKey},
                        Integer.toString(batchSize),
                        instanceKeyPrefix),
                "Could not clean expired instance leases")
                .thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<? extends Collection<NetworkInstance>> instances() {
        return cleanupExpired(config.cleanupBatch())
                .thenCompose(ignored -> loadInstances());
    }

    @Override
    public CompletableFuture<Void> close() {
        StatefulRedisConnection<String, String> activeConnection;
        synchronized (lifecycleLock) {
            if (state == StoreState.CLOSING || state == StoreState.CLOSED) {
                return closeFuture;
            }
            state = StoreState.CLOSING;
            if (reconnectFuture != null) {
                reconnectFuture.completeExceptionally(
                        new ApiUnavailableException("Instance store closed during reconnect"));
                reconnectFuture = null;
            }
            activeConnection = connection;
            connection = null;
            CompletableFuture<Void> connectionClose = activeConnection == null
                    ? CompletableFuture.completedFuture(null)
                    : activeConnection.closeAsync();
            closeFuture = connectionClose
                    .handle((ignored, failure) -> failure)
                    .thenCompose(connectionFailure -> {
                        CompletableFuture<Void> backendClose = closesBackend
                                ? backend.close()
                                : CompletableFuture.completedFuture(null);
                        return backendClose.handle((ignored, backendFailure) -> {
                            Throwable failure = AsyncFailures.merge(
                                    connectionFailure, backendFailure);
                            if (failure != null) {
                                throw new java.util.concurrent.CompletionException(failure);
                            }
                            return (Void) null;
                        });
                    })
                    .whenComplete((ignored, failure) -> {
                        stateExecutor.shutdownNow();
                        synchronized (lifecycleLock) {
                            state = StoreState.CLOSED;
                        }
                    });
            return closeFuture;
        }
    }

    private void activateConnection(StatefulRedisConnection<String, String> connected) {
        synchronized (lifecycleLock) {
            if (state != StoreState.CONNECTING) {
                connected.closeAsync();
                throw new IllegalStateException("Instance store closed while connecting");
            }
            connection = connected;
            connection.addListener(new StoreConnectionListener());
            state = StoreState.CONNECTED;
        }
    }

    private CompletableFuture<List<NetworkInstance>> loadInstances() {
        RedisAsyncCommands<String, String> commands = commands();
        if (commands == null) {
            return unavailableFuture("Instance store is not connected", null);
        }
        return mapFailure(
                        commands.zrange(indexKey, 0, -1),
                        "Could not read active instance index")
                .thenComposeAsync(members -> loadSnapshots(commands, members), stateExecutor);
    }

    private CompletableFuture<List<NetworkInstance>> loadSnapshots(
            RedisAsyncCommands<String, String> commands, List<String> members) {
        List<CompletableFuture<Map<String, String>>> reads = members.stream()
                .map(member -> commands.hgetall(instanceKey(member)).toCompletableFuture())
                .toList();
        return mapFailure(
                        CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)),
                        "Could not read active instance snapshots")
                .thenApplyAsync(ignored -> {
                    List<NetworkInstance> snapshots = new ArrayList<>(reads.size());
                    for (CompletableFuture<Map<String, String>> read : reads) {
                        Map<String, String> fields = read.getNow(Map.of());
                        if (!fields.isEmpty()) {
                            snapshots.add(parseSnapshot(fields));
                        }
                    }
                    snapshots.sort(Comparator.comparing(NetworkInstance::id));
                    return List.copyOf(snapshots);
                }, stateExecutor);
    }

    private <T> CompletableFuture<T> command(
            java.util.function.Function<
                            RedisAsyncCommands<String, String>,
                            ? extends CompletionStage<T>>
                    operation,
            String failureMessage) {
        RedisAsyncCommands<String, String> commands = commands();
        if (commands == null) {
            return unavailableFuture("Instance store is not connected", null);
        }
        try {
            return mapFailure(operation.apply(commands), failureMessage);
        } catch (RuntimeException failure) {
            return unavailableFuture(failureMessage, failure);
        }
    }

    private RedisAsyncCommands<String, String> commands() {
        synchronized (lifecycleLock) {
            if (state != StoreState.CONNECTED || connection == null || !connection.isOpen()) {
                return null;
            }
            return connection.async();
        }
    }

    private static NetworkInstance parseSnapshot(Map<String, String> fields) {
        try {
            Optional<String> group = "1".equals(required(fields, "groupPresent"))
                    ? Optional.of(required(fields, "group"))
                    : Optional.empty();
            return new NetworkInstance(
                    required(fields, "id"),
                    NetworkInstanceType.valueOf(required(fields, "type")),
                    group,
                    Instant.parse(required(fields, "startedAt")),
                    Instant.parse(required(fields, "lastHeartbeat")),
                    Integer.parseInt(required(fields, "onlinePlayerCount")));
        } catch (RuntimeException failure) {
            throw new ApiUnavailableException("Redis contains an invalid instance snapshot", failure);
        }
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Missing instance field: " + name);
        }
        return value;
    }

    private String instanceKey(String encodedId) {
        return instanceKeyPrefix + encodedId;
    }

    private static String[] mutationArguments(
            String member, String token, long ttlMillis, NetworkInstance instance) {
        return new String[] {
            member,
            token,
            Long.toString(ttlMillis),
            instance.id(),
            instance.type().name(),
            instance.group().isPresent() ? "1" : "0",
            instance.group().orElse(""),
            instance.startedAt().toString(),
            instance.lastHeartbeat().toString(),
            Integer.toString(instance.onlinePlayerCount())
        };
    }

    private static String encodeId(String id) {
        try {
            var bytes = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(id));
            byte[] encoded = new byte[bytes.remaining()];
            bytes.get(encoded);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(encoded);
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("instanceId must be valid Unicode", failure);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static long requirePositiveMillis(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        try {
            long millis = ttl.toMillis();
            if (millis < 1) {
                throw new IllegalArgumentException("ttl must be at least one millisecond");
            }
            return millis;
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("ttl is too large", failure);
        }
    }

    private static <T> CompletableFuture<T> mapFailure(
            CompletionStage<T> source, String message) {
        CompletableFuture<T> result = new CompletableFuture<>();
        source.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
            } else {
                Throwable cause = AsyncFailures.unwrap(failure);
                result.completeExceptionally(
                        cause instanceof ApiUnavailableException
                                ? cause
                                : new ApiUnavailableException(message, cause));
            }
        });
        return result;
    }

    private static <T> CompletableFuture<T> unavailableFuture(
            String message, Throwable cause) {
        ApiUnavailableException failure = cause == null
                ? new ApiUnavailableException(message)
                : new ApiUnavailableException(message, cause);
        return CompletableFuture.failedFuture(failure);
    }

    private final class StoreConnectionListener implements RedisConnectionStateListener {

        @Override
        public void onRedisConnected(
                RedisChannelHandler<?, ?> redisConnection, SocketAddress socketAddress) {
            CompletableFuture<Void> readiness = null;
            synchronized (lifecycleLock) {
                if (state == StoreState.CONNECTING && reconnectFuture != null) {
                    state = StoreState.CONNECTED;
                    readiness = reconnectFuture;
                    reconnectFuture = null;
                }
            }
            if (readiness != null) {
                readiness.complete(null);
            }
        }

        @Override
        public void onRedisDisconnected(RedisChannelHandler<?, ?> redisConnection) {
            synchronized (lifecycleLock) {
                if (state == StoreState.CONNECTED) {
                    state = StoreState.CONNECTING;
                    reconnectFuture = new CompletableFuture<>();
                }
            }
        }
    }

    private enum StoreState {
        NEW,
        CONNECTING,
        CONNECTED,
        CLOSING,
        CLOSED
    }
}
