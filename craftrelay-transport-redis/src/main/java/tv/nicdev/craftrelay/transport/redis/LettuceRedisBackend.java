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
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import tv.nicdev.craftrelay.common.internal.presence.InstancePresenceConfig;

/**
 * Shared Lettuce client and event-loop owner for one CraftRelay node.
 *
 * <p>Create the transport and instance store before starting either component. Closing the store
 * after the transport closes the shared client. Standalone {@link LettuceRedisTransport}
 * construction remains supported.
 */
public final class LettuceRedisBackend {

    private final Object lock = new Object();
    private final RedisClient client;
    private final RedisURI redisUri;

    private boolean transportCreated;
    private boolean storeCreated;
    private CompletableFuture<Void> closeFuture;

    /** Creates an unopened shared backend. */
    public LettuceRedisBackend(RedisTransportConfig config) {
        Objects.requireNonNull(config, "config");
        redisUri = createRedisUri(config);
        client = RedisClient.create();
        client.setOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(config.connectionTimeout()))
                .build());
    }

    /** Creates this backend's sole Pub/Sub transport. */
    public LettuceRedisTransport transport() {
        synchronized (lock) {
            ensureOpen();
            if (transportCreated) {
                throw new IllegalStateException("Redis transport already created");
            }
            transportCreated = true;
            return new LettuceRedisTransport(this, false);
        }
    }

    /** Creates this backend's sole authoritative instance store. */
    public LettuceRedisInstanceStore instanceStore(InstancePresenceConfig config) {
        synchronized (lock) {
            ensureOpen();
            if (storeCreated) {
                throw new IllegalStateException("Redis instance store already created");
            }
            storeCreated = true;
            return new LettuceRedisInstanceStore(this, config, true);
        }
    }

    /**
     * Idempotently shuts down the shared Lettuce client.
     *
     * <p>A composed node normally reaches this through its instance store. Callers that only
     * create a subset of the backend components must close the backend explicitly.
     */
    public CompletableFuture<Void> close() {
        synchronized (lock) {
            if (closeFuture == null) {
                closeFuture = client.shutdownAsync();
            }
            return closeFuture;
        }
    }

    RedisClient client() {
        return client;
    }

    RedisURI redisUri() {
        return redisUri;
    }

    private void ensureOpen() {
        if (closeFuture != null) {
            throw new IllegalStateException("Redis backend is closed");
        }
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
}
