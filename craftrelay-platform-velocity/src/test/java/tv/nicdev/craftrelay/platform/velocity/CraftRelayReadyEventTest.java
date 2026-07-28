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
package tv.nicdev.craftrelay.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.CraftRelayApi;

class CraftRelayReadyEventTest {

    @Test
    void retainsValidatedApi() {
        CraftRelayApi api = new UnavailableApi();
        assertSame(api, new CraftRelayReadyEvent(api).api());
        assertThrows(NullPointerException.class, () -> new CraftRelayReadyEvent(null));
    }

    private static final class UnavailableApi implements CraftRelayApi {

        @Override
        public tv.nicdev.craftrelay.api.messaging.CustomMessaging customMessaging() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> publish(
                tv.nicdev.craftrelay.api.target.NetworkTarget target,
                tv.nicdev.craftrelay.api.NetworkMessage message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <M extends tv.nicdev.craftrelay.api.NetworkMessage>
                tv.nicdev.craftrelay.api.Subscription subscribe(
                        Class<M> messageType,
                        java.util.function.Consumer<? super M> listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R extends tv.nicdev.craftrelay.api.NetworkMessage>
                java.util.concurrent.CompletableFuture<R> request(
                        tv.nicdev.craftrelay.api.target.NetworkTarget target,
                        tv.nicdev.craftrelay.api.NetworkMessage request,
                        Class<R> responseType,
                        java.time.Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.CompletableFuture<
                        java.util.Collection<tv.nicdev.craftrelay.api.model.NetworkInstance>>
                instances() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.CompletableFuture<
                        java.util.Optional<tv.nicdev.craftrelay.api.model.NetworkPlayer>>
                player(java.util.UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public tv.nicdev.craftrelay.api.CraftRelayState state() {
            return tv.nicdev.craftrelay.api.CraftRelayState.INITIALIZING;
        }
    }
}
