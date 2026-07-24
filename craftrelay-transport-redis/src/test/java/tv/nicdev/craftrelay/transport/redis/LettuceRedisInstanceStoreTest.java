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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.common.internal.presence.InstancePresenceConfig;

class LettuceRedisInstanceStoreTest {

    @Test
    void validatesLeaseArgumentsBeforeRedisAccess() {
        LettuceRedisInstanceStore store = new LettuceRedisInstanceStore(
                RedisTransportConfig.localhost(6379),
                InstancePresenceConfig.defaults());
        NetworkInstance instance = instance("proxy-\uD83C\uDF0D");
        try {
            assertThrows(NullPointerException.class, () -> store.claim(null, "token", Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class, () -> store.claim(instance, " ", Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class, () -> store.claim(instance, "token", Duration.ZERO));
            assertThrows(IllegalArgumentException.class, () -> store.cleanupExpired(0));
            assertThrows(IllegalArgumentException.class, () -> store.release("\uD800", "token"));
        } finally {
            store.close().join();
        }
    }

    @Test
    void sharedBackendAllowsExactlyOneTransportAndStore() {
        LettuceRedisBackend backend =
                new LettuceRedisBackend(RedisTransportConfig.localhost(6379));
        LettuceRedisTransport transport = backend.transport();
        LettuceRedisInstanceStore store =
                backend.instanceStore(InstancePresenceConfig.defaults());

        assertThrows(IllegalStateException.class, backend::transport);
        assertThrows(
                IllegalStateException.class,
                () -> backend.instanceStore(InstancePresenceConfig.defaults()));
        transport.close().join();
        store.close().join();
        assertDoesNotThrow(store::close);
    }

    private static NetworkInstance instance(String id) {
        Instant now = Instant.now();
        return new NetworkInstance(
                id,
                NetworkInstanceType.PROXY,
                Optional.of("eu"),
                now,
                now,
                0);
    }
}
