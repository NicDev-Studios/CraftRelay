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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.common.internal.concurrent.AsyncFailures;
import tv.nicdev.craftrelay.common.internal.presence.InstancePresenceConfig;
import tv.nicdev.craftrelay.common.internal.presence.PlayerPresenceConfig;
import tv.nicdev.craftrelay.common.internal.state.NetworkPresenceStore;
import tv.nicdev.craftrelay.common.internal.state.PlayerMutationResult;
import tv.nicdev.craftrelay.common.internal.state.PlayerMutationStatus;
import tv.nicdev.craftrelay.common.internal.state.PlayerSessionKey;

/**
 * Redis-backed, server-time-based instance lease store.
 *
 * <p>This implementation is internal CraftRelay composition API. It never exposes Lettuce types
 * through the public plugin API.
 */
public final class LettuceRedisPresenceStore implements NetworkPresenceStore {

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

    private static final String PLAYER_CLAIM_SCRIPT = """
            local nowParts = redis.call('TIME')
            local now = (tonumber(nowParts[1]) * 1000) + math.floor(tonumber(nowParts[2]) / 1000)
            if redis.call('HGET', KEYS[4], 'leaseToken') ~= ARGV[4] then
                return {2}
            end
            local currentSession = redis.call('HGET', KEYS[1], 'sessionId')
            local currentExpiry = tonumber(redis.call('ZSCORE', KEYS[2], ARGV[1]) or '0')
            local currentProxy = redis.call('HGET', KEYS[1], 'proxyId')
            local currentToken = redis.call('HGET', KEYS[1], 'leaseToken')
            if currentSession
                and (currentSession ~= ARGV[2]
                    or currentProxy ~= ARGV[3]
                    or currentToken ~= ARGV[4])
                and currentExpiry > now then
                return {0,
                    redis.call('HGET', KEYS[1], 'uniqueId') or '',
                    redis.call('HGET', KEYS[1], 'username') or '',
                    redis.call('HGET', KEYS[1], 'proxyId') or '',
                    redis.call('HGET', KEYS[1], 'serverPresent') or '0',
                    redis.call('HGET', KEYS[1], 'serverId') or '',
                    currentSession,
                    redis.call('HGET', KEYS[1], 'connectedAt') or tostring(now),
                    redis.call('HGET', KEYS[1], 'lastUpdatedAt') or tostring(now)}
            end
            local connectedAt = now
            if currentSession == ARGV[2]
                and currentProxy == ARGV[3]
                and currentToken == ARGV[4] then
                connectedAt = tonumber(redis.call('HGET', KEYS[1], 'connectedAt') or tostring(now))
            end
            local expiresAt = now + tonumber(ARGV[5])
            redis.call('HSET', KEYS[1],
                'uniqueId', ARGV[1],
                'username', ARGV[6],
                'proxyId', ARGV[3],
                'serverPresent', ARGV[7],
                'serverId', ARGV[8],
                'sessionId', ARGV[2],
                'connectedAt', connectedAt,
                'lastUpdatedAt', now,
                'leaseToken', ARGV[4],
                'proxyIndexKey', KEYS[3])
            redis.call('PEXPIREAT', KEYS[1], expiresAt)
            redis.call('ZADD', KEYS[2], expiresAt, ARGV[1])
            redis.call('SADD', KEYS[3], ARGV[1])
            redis.call('PEXPIREAT', KEYS[3], expiresAt)
            return {1, ARGV[1], ARGV[6], ARGV[3], ARGV[7], ARGV[8],
                ARGV[2], tostring(connectedAt), tostring(now)}
            """;

    private static final String PLAYER_UPDATE_SCRIPT = """
            local nowParts = redis.call('TIME')
            local now = (tonumber(nowParts[1]) * 1000) + math.floor(tonumber(nowParts[2]) / 1000)
            if redis.call('HGET', KEYS[4], 'leaseToken') ~= ARGV[4]
                or redis.call('HGET', KEYS[1], 'proxyId') ~= ARGV[3]
                or redis.call('HGET', KEYS[1], 'sessionId') ~= ARGV[2]
                or redis.call('HGET', KEYS[1], 'leaseToken') ~= ARGV[4] then
                return {2}
            end
            local previousPresent = redis.call('HGET', KEYS[1], 'serverPresent') or '0'
            local previousServer = redis.call('HGET', KEYS[1], 'serverId') or ''
            local expiresAt = now + tonumber(ARGV[5])
            redis.call('HSET', KEYS[1],
                'serverPresent', ARGV[6],
                'serverId', ARGV[7],
                'lastUpdatedAt', now)
            redis.call('PEXPIREAT', KEYS[1], expiresAt)
            redis.call('ZADD', KEYS[2], expiresAt, ARGV[1])
            redis.call('PEXPIREAT', KEYS[3], expiresAt)
            return {1,
                redis.call('HGET', KEYS[1], 'uniqueId') or ARGV[1],
                redis.call('HGET', KEYS[1], 'username') or '',
                ARGV[3], ARGV[6], ARGV[7], ARGV[2],
                redis.call('HGET', KEYS[1], 'connectedAt') or tostring(now),
                tostring(now), previousPresent, previousServer}
            """;

    private static final String PLAYER_RELEASE_SCRIPT = """
            if redis.call('HGET', KEYS[4], 'leaseToken') ~= ARGV[4]
                or redis.call('HGET', KEYS[1], 'proxyId') ~= ARGV[3]
                or redis.call('HGET', KEYS[1], 'sessionId') ~= ARGV[2]
                or redis.call('HGET', KEYS[1], 'leaseToken') ~= ARGV[4] then
                return {2}
            end
            local result = {1,
                redis.call('HGET', KEYS[1], 'uniqueId') or ARGV[1],
                redis.call('HGET', KEYS[1], 'username') or '',
                ARGV[3],
                redis.call('HGET', KEYS[1], 'serverPresent') or '0',
                redis.call('HGET', KEYS[1], 'serverId') or '',
                ARGV[2],
                redis.call('HGET', KEYS[1], 'connectedAt') or '0',
                redis.call('HGET', KEYS[1], 'lastUpdatedAt') or '0'}
            redis.call('DEL', KEYS[1])
            redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('SREM', KEYS[3], ARGV[1])
            return result
            """;

    private static final String PLAYER_REFRESH_SCRIPT = """
            local nowParts = redis.call('TIME')
            local now = (tonumber(nowParts[1]) * 1000) + math.floor(tonumber(nowParts[2]) / 1000)
            if redis.call('HGET', KEYS[2], 'leaseToken') ~= ARGV[2] then
                return {}
            end
            local expiresAt = now + tonumber(ARGV[3])
            local refreshed = {}
            for index = 4, #KEYS do
                local argument = 4 + ((index - 4) * 2)
                local playerId = ARGV[argument]
                local sessionId = ARGV[argument + 1]
                if redis.call('HGET', KEYS[index], 'proxyId') == ARGV[1]
                    and redis.call('HGET', KEYS[index], 'sessionId') == sessionId
                    and redis.call('HGET', KEYS[index], 'leaseToken') == ARGV[2] then
                    redis.call('HSET', KEYS[index], 'lastUpdatedAt', now)
                    redis.call('PEXPIREAT', KEYS[index], expiresAt)
                    redis.call('ZADD', KEYS[1], expiresAt, playerId)
                    table.insert(refreshed, playerId)
                    table.insert(refreshed, sessionId)
                end
            end
            if #refreshed > 0 then
                redis.call('PEXPIREAT', KEYS[3], expiresAt)
            end
            return refreshed
            """;

    private static final String PLAYER_RELEASE_BATCH_SCRIPT = """
            if redis.call('HGET', KEYS[3], 'leaseToken') ~= ARGV[2] then
                return {}
            end
            local members = redis.call('SMEMBERS', KEYS[2])
            local result = {}
            local processed = 0
            for _, playerId in ipairs(members) do
                if processed >= tonumber(ARGV[3]) then
                    break
                end
                local playerKey = ARGV[4] .. playerId
                local token = redis.call('HGET', playerKey, 'leaseToken')
                local proxyId = redis.call('HGET', playerKey, 'proxyId')
                local shouldRelease = proxyId == ARGV[1]
                    and ((ARGV[5] == 'owned' and token == ARGV[2])
                        or (ARGV[5] == 'stale' and token ~= ARGV[2]))
                if shouldRelease then
                    table.insert(result, redis.call('HGET', playerKey, 'uniqueId') or playerId)
                    table.insert(result, redis.call('HGET', playerKey, 'username') or '')
                    table.insert(result, proxyId)
                    table.insert(result, redis.call('HGET', playerKey, 'serverPresent') or '0')
                    table.insert(result, redis.call('HGET', playerKey, 'serverId') or '')
                    table.insert(result, redis.call('HGET', playerKey, 'sessionId') or '')
                    table.insert(result, redis.call('HGET', playerKey, 'connectedAt') or '0')
                    table.insert(result, redis.call('HGET', playerKey, 'lastUpdatedAt') or '0')
                    redis.call('DEL', playerKey)
                    redis.call('ZREM', KEYS[1], playerId)
                    redis.call('SREM', KEYS[2], playerId)
                    processed = processed + 1
                elseif not token then
                    redis.call('ZREM', KEYS[1], playerId)
                    redis.call('SREM', KEYS[2], playerId)
                end
            end
            if redis.call('SCARD', KEYS[2]) == 0 then
                redis.call('DEL', KEYS[2])
            end
            return result
            """;

    private static final String PLAYER_CLEANUP_SCRIPT = """
            local nowParts = redis.call('TIME')
            local now = (tonumber(nowParts[1]) * 1000) + math.floor(tonumber(nowParts[2]) / 1000)
            local expired = redis.call(
                'ZRANGEBYSCORE', KEYS[1], '-inf', now, 'LIMIT', 0, tonumber(ARGV[1]))
            for _, playerId in ipairs(expired) do
                local playerKey = ARGV[2] .. playerId
                local proxyIndexKey = redis.call('HGET', playerKey, 'proxyIndexKey')
                if proxyIndexKey then
                    redis.call('SREM', proxyIndexKey, playerId)
                end
                redis.call('DEL', playerKey)
                redis.call('ZREM', KEYS[1], playerId)
            end
            return #expired
            """;

    private final Object lifecycleLock = new Object();
    private final LettuceRedisBackend backend;
    private final InstancePresenceConfig instanceConfig;
    private final PlayerPresenceConfig playerConfig;
    private final boolean closesBackend;
    private final String indexKey;
    private final String instanceKeyPrefix;
    private final String playerIndexKey;
    private final String playerKeyPrefix;
    private final String proxyKeyPrefix;
    private final ExecutorService stateExecutor =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("craftrelay-redis-state-", 0).factory());

    private StoreState state = StoreState.NEW;
    private CompletableFuture<Void> connectFuture;
    private CompletableFuture<Void> reconnectFuture;
    private CompletableFuture<Void> closeFuture;
    private StatefulRedisConnection<String, String> connection;

    /** Creates a standalone instance/player presence store that owns its Lettuce backend. */
    public LettuceRedisPresenceStore(
            RedisTransportConfig redisConfig,
            InstancePresenceConfig instanceConfig,
            PlayerPresenceConfig playerConfig) {
        this(
                new LettuceRedisBackend(Objects.requireNonNull(redisConfig, "redisConfig")),
                instanceConfig,
                playerConfig,
                true);
    }

    LettuceRedisPresenceStore(
            LettuceRedisBackend backend,
            InstancePresenceConfig instanceConfig,
            PlayerPresenceConfig playerConfig,
            boolean closesBackend) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.instanceConfig = Objects.requireNonNull(instanceConfig, "instanceConfig");
        this.playerConfig = Objects.requireNonNull(playerConfig, "playerConfig");
        this.playerConfig.validateCompatible(this.instanceConfig);
        this.closesBackend = closesBackend;
        indexKey = instanceConfig.keyPrefix() + ":presence:instances";
        instanceKeyPrefix = instanceConfig.keyPrefix() + ":presence:instance:";
        playerIndexKey = playerConfig.keyPrefix() + ":presence:players";
        playerKeyPrefix = playerConfig.keyPrefix() + ":presence:player:";
        proxyKeyPrefix = playerConfig.keyPrefix() + ":presence:proxy:";
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
                        "Could not connect Redis presence store");
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
                        "Could not connect Redis presence store", failure);
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
    public CompletableFuture<PlayerMutationResult> claim(
            NetworkPlayer player, String nodeLeaseToken, Duration ttl) {
        NetworkPlayer value = Objects.requireNonNull(player, "player");
        String token = requireText(nodeLeaseToken, "nodeLeaseToken");
        long ttlMillis = requirePositiveMillis(ttl);
        String playerId = value.uniqueId().toString();
        String proxyIndex = proxyKey(value.proxyId());
        String[] arguments = {
            playerId,
            value.sessionId().toString(),
            value.proxyId(),
            token,
            Long.toString(ttlMillis),
            value.username(),
            value.serverId().isPresent() ? "1" : "0",
            value.serverId().orElse("")
        };
        return evalMulti(
                        PLAYER_CLAIM_SCRIPT,
                        new String[] {
                            playerKey(playerId),
                            playerIndexKey,
                            proxyIndex,
                            instanceKey(encodeId(value.proxyId()))
                        },
                        arguments,
                        "Could not claim player session")
                .thenApply(this::parseClaimResult);
    }

    @Override
    public CompletableFuture<PlayerMutationResult> updateServer(
            UUID playerId,
            UUID sessionId,
            String proxyId,
            String nodeLeaseToken,
            Optional<String> serverId,
            Duration ttl) {
        String id = Objects.requireNonNull(playerId, "playerId").toString();
        String session = Objects.requireNonNull(sessionId, "sessionId").toString();
        String proxy = requireText(proxyId, "proxyId");
        String token = requireText(nodeLeaseToken, "nodeLeaseToken");
        Optional<String> server = Objects.requireNonNull(serverId, "serverId")
                .map(value -> requireText(value, "serverId"));
        long ttlMillis = requirePositiveMillis(ttl);
        return evalMulti(
                        PLAYER_UPDATE_SCRIPT,
                        new String[] {
                            playerKey(id),
                            playerIndexKey,
                            proxyKey(proxy),
                            instanceKey(encodeId(proxy))
                        },
                        new String[] {
                            id,
                            session,
                            proxy,
                            token,
                            Long.toString(ttlMillis),
                            server.isPresent() ? "1" : "0",
                            server.orElse("")
                        },
                        "Could not update player server")
                .thenApply(this::parseUpdateResult);
    }

    @Override
    public CompletableFuture<PlayerMutationResult> release(
            UUID playerId, UUID sessionId, String proxyId, String nodeLeaseToken) {
        String id = Objects.requireNonNull(playerId, "playerId").toString();
        String session = Objects.requireNonNull(sessionId, "sessionId").toString();
        String proxy = requireText(proxyId, "proxyId");
        String token = requireText(nodeLeaseToken, "nodeLeaseToken");
        return evalMulti(
                        PLAYER_RELEASE_SCRIPT,
                        new String[] {
                            playerKey(id),
                            playerIndexKey,
                            proxyKey(proxy),
                            instanceKey(encodeId(proxy))
                        },
                        new String[] {id, session, proxy, token},
                        "Could not release player session")
                .thenApply(this::parseReleaseResult);
    }

    @Override
    public CompletableFuture<Set<PlayerSessionKey>> refresh(
            String proxyId,
            String nodeLeaseToken,
            Collection<PlayerSessionKey> sessions,
            Duration ttl) {
        String proxy = requireText(proxyId, "proxyId");
        String token = requireText(nodeLeaseToken, "nodeLeaseToken");
        Collection<PlayerSessionKey> values = List.copyOf(
                Objects.requireNonNull(sessions, "sessions"));
        if (values.size() > playerConfig.batchSize()) {
            throw new IllegalArgumentException("sessions exceed configured batch size");
        }
        long ttlMillis = requirePositiveMillis(ttl);
        List<String> keys = new ArrayList<>(values.size() + 3);
        keys.add(playerIndexKey);
        keys.add(instanceKey(encodeId(proxy)));
        keys.add(proxyKey(proxy));
        List<String> arguments = new ArrayList<>(values.size() * 2 + 3);
        arguments.add(proxy);
        arguments.add(token);
        arguments.add(Long.toString(ttlMillis));
        for (PlayerSessionKey session : values) {
            PlayerSessionKey value = Objects.requireNonNull(session, "session");
            String id = value.playerId().toString();
            keys.add(playerKey(id));
            arguments.add(id);
            arguments.add(value.sessionId().toString());
        }
        return evalMulti(
                        PLAYER_REFRESH_SCRIPT,
                        keys.toArray(String[]::new),
                        arguments.toArray(String[]::new),
                        "Could not refresh player sessions")
                .thenApply(this::parseSessionKeys);
    }

    @Override
    public CompletableFuture<Collection<NetworkPlayer>> releaseStale(
            String proxyId, String nodeLeaseToken, int batchSize) {
        return releaseBatch(proxyId, nodeLeaseToken, batchSize, "stale");
    }

    @Override
    public CompletableFuture<Collection<NetworkPlayer>> releaseOwned(
            String proxyId, String nodeLeaseToken, int batchSize) {
        return releaseBatch(proxyId, nodeLeaseToken, batchSize, "owned");
    }

    @Override
    public CompletableFuture<Void> cleanupExpiredPlayers(int batchSize) {
        requireBatchSize(batchSize);
        return command(
                        commands -> commands.eval(
                                PLAYER_CLEANUP_SCRIPT,
                                ScriptOutputType.INTEGER,
                                new String[] {playerIndexKey},
                                Integer.toString(batchSize),
                                playerKeyPrefix),
                        "Could not clean expired player sessions")
                .thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Optional<NetworkPlayer>> player(UUID playerId) {
        String id = Objects.requireNonNull(playerId, "playerId").toString();
        return cleanupExpiredPlayers(playerConfig.batchSize())
                .thenCompose(ignored -> command(
                        commands -> commands.hgetall(playerKey(id)),
                        "Could not read player presence"))
                .thenApply(fields -> fields.isEmpty()
                        ? Optional.empty()
                        : Optional.of(parsePlayer(fields)));
    }

    private CompletableFuture<Collection<NetworkPlayer>> releaseBatch(
            String proxyId, String nodeLeaseToken, int batchSize, String mode) {
        String proxy = requireText(proxyId, "proxyId");
        String token = requireText(nodeLeaseToken, "nodeLeaseToken");
        requireBatchSize(batchSize);
        return evalMulti(
                        PLAYER_RELEASE_BATCH_SCRIPT,
                        new String[] {
                            playerIndexKey,
                            proxyKey(proxy),
                            instanceKey(encodeId(proxy))
                        },
                        new String[] {
                            proxy,
                            token,
                            Integer.toString(batchSize),
                            playerKeyPrefix,
                            mode
                        },
                        "Could not release proxy player sessions")
                .thenApply(this::parsePlayerBatch);
    }

    @Override
    public CompletableFuture<? extends Collection<NetworkInstance>> instances() {
        return cleanupExpired(instanceConfig.cleanupBatch())
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

    private CompletableFuture<List<Object>> evalMulti(
            String script, String[] keys, String[] arguments, String failureMessage) {
        return command(
                commands -> commands.<List<Object>>eval(
                        script, ScriptOutputType.MULTI, keys, arguments),
                failureMessage);
    }

    private PlayerMutationResult parseClaimResult(List<Object> values) {
        int status = status(values);
        if (status == 2) {
            return result(PlayerMutationStatus.OWNERSHIP_LOST);
        }
        NetworkPlayer current = parsePlayer(values, 1);
        return new PlayerMutationResult(
                status == 0 ? PlayerMutationStatus.CONFLICT : PlayerMutationStatus.APPLIED,
                Optional.empty(),
                Optional.of(current));
    }

    private PlayerMutationResult parseUpdateResult(List<Object> values) {
        if (status(values) != 1) {
            return result(PlayerMutationStatus.OWNERSHIP_LOST);
        }
        NetworkPlayer current = parsePlayer(values, 1);
        Optional<String> previousServer =
                "1".equals(text(values.get(9)))
                        ? Optional.of(text(values.get(10)))
                        : Optional.empty();
        NetworkPlayer previous = new NetworkPlayer(
                current.uniqueId(),
                current.username(),
                current.proxyId(),
                previousServer,
                current.sessionId(),
                current.connectedAt(),
                current.lastUpdatedAt());
        return new PlayerMutationResult(
                PlayerMutationStatus.APPLIED,
                Optional.of(previous),
                Optional.of(current));
    }

    private PlayerMutationResult parseReleaseResult(List<Object> values) {
        if (status(values) != 1) {
            return result(PlayerMutationStatus.OWNERSHIP_LOST);
        }
        return new PlayerMutationResult(
                PlayerMutationStatus.APPLIED,
                Optional.of(parsePlayer(values, 1)),
                Optional.empty());
    }

    private Set<PlayerSessionKey> parseSessionKeys(List<Object> values) {
        if ((values.size() & 1) != 0) {
            throw new ApiUnavailableException("Redis returned an invalid player refresh result");
        }
        java.util.LinkedHashSet<PlayerSessionKey> sessions = new java.util.LinkedHashSet<>();
        for (int index = 0; index < values.size(); index += 2) {
            sessions.add(new PlayerSessionKey(
                    UUID.fromString(text(values.get(index))),
                    UUID.fromString(text(values.get(index + 1)))));
        }
        return Set.copyOf(sessions);
    }

    private Collection<NetworkPlayer> parsePlayerBatch(List<Object> values) {
        if (values.size() % 8 != 0) {
            throw new ApiUnavailableException("Redis returned an invalid player batch");
        }
        List<NetworkPlayer> players = new ArrayList<>(values.size() / 8);
        for (int index = 0; index < values.size(); index += 8) {
            players.add(parsePlayer(values, index));
        }
        return List.copyOf(players);
    }

    private static PlayerMutationResult result(PlayerMutationStatus status) {
        return new PlayerMutationResult(status, Optional.empty(), Optional.empty());
    }

    private static int status(List<Object> values) {
        if (values.isEmpty()) {
            throw new ApiUnavailableException("Redis returned an empty player mutation result");
        }
        Object value = values.get(0);
        return value instanceof Number number
                ? number.intValue()
                : Integer.parseInt(text(value));
    }

    private static NetworkPlayer parsePlayer(List<Object> values, int offset) {
        try {
            Optional<String> server = "1".equals(text(values.get(offset + 3)))
                    ? Optional.of(text(values.get(offset + 4)))
                    : Optional.empty();
            return new NetworkPlayer(
                    UUID.fromString(text(values.get(offset))),
                    text(values.get(offset + 1)),
                    text(values.get(offset + 2)),
                    server,
                    UUID.fromString(text(values.get(offset + 5))),
                    Instant.ofEpochMilli(Long.parseLong(text(values.get(offset + 6)))),
                    Instant.ofEpochMilli(Long.parseLong(text(values.get(offset + 7)))));
        } catch (RuntimeException failure) {
            throw new ApiUnavailableException("Redis contains an invalid player snapshot", failure);
        }
    }

    private static NetworkPlayer parsePlayer(Map<String, String> fields) {
        try {
            Optional<String> server = "1".equals(required(fields, "serverPresent"))
                    ? Optional.of(required(fields, "serverId"))
                    : Optional.empty();
            return new NetworkPlayer(
                    UUID.fromString(required(fields, "uniqueId")),
                    required(fields, "username"),
                    required(fields, "proxyId"),
                    server,
                    UUID.fromString(required(fields, "sessionId")),
                    Instant.ofEpochMilli(Long.parseLong(required(fields, "connectedAt"))),
                    Instant.ofEpochMilli(Long.parseLong(required(fields, "lastUpdatedAt"))));
        } catch (RuntimeException failure) {
            throw new ApiUnavailableException("Redis contains an invalid player snapshot", failure);
        }
    }

    private static String text(Object value) {
        return Objects.toString(value, "");
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

    private String playerKey(String playerId) {
        return playerKeyPrefix + playerId;
    }

    private String proxyKey(String proxyId) {
        return proxyKeyPrefix + encodeId(proxyId) + ":players";
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

    private static void requireBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
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
