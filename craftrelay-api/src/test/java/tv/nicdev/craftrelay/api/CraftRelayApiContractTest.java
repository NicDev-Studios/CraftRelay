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
package tv.nicdev.craftrelay.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.target.NetworkTarget;
import tv.nicdev.craftrelay.api.messaging.CustomMessaging;
import tv.nicdev.craftrelay.api.messaging.MessagePayloadCodec;
import tv.nicdev.craftrelay.api.messaging.MessageRegistration;
import tv.nicdev.craftrelay.api.messaging.MessageType;
import tv.nicdev.craftrelay.api.messaging.RequestHandler;

class CraftRelayApiContractTest {

    @Test
    void potentiallySlowOperationsReturnCompletableFutures() {
        Method[] asynchronousMethods =
                Arrays.stream(CraftRelayApi.class.getDeclaredMethods())
                        .filter(method -> !method.getName().equals("subscribe"))
                        .filter(method -> !method.getName().equals("state"))
                        .filter(method -> !method.getName().equals("customMessaging"))
                        .toArray(Method[]::new);

        assertEquals(4, asynchronousMethods.length);
        assertTrue(
                Arrays.stream(asynchronousMethods)
                        .allMatch(method -> method.getReturnType() == CompletableFuture.class));
    }

    @Test
    void exposesTypedPublicContract() throws NoSuchMethodException {
        Method publish =
                CraftRelayApi.class.getMethod(
                        "publish", NetworkTarget.class, NetworkMessage.class);
        Method subscribe =
                CraftRelayApi.class.getMethod("subscribe", Class.class, Consumer.class);
        Method request =
                CraftRelayApi.class.getMethod(
                        "request",
                        NetworkTarget.class,
                        NetworkMessage.class,
                        Class.class,
                        Duration.class);
        Method instances = CraftRelayApi.class.getMethod("instances");
        Method player = CraftRelayApi.class.getMethod("player", UUID.class);
        Method customMessaging = CraftRelayApi.class.getMethod("customMessaging");

        assertEquals(CompletableFuture.class, publish.getReturnType());
        assertEquals(Subscription.class, subscribe.getReturnType());
        assertEquals(CompletableFuture.class, request.getReturnType());
        assertFuturePayload(instances, Collection.class);
        assertFuturePayload(player, Optional.class);
        assertEquals(CustomMessaging.class, customMessaging.getReturnType());
    }

    @Test
    void customMessagingUsesOwnedMessageRegistrations()
            throws NoSuchMethodException {
        Method register =
                CustomMessaging.class.getMethod(
                        "register", MessageType.class, MessagePayloadCodec.class);
        Method handle =
                CustomMessaging.class.getMethod(
                        "handle",
                        MessageRegistration.class,
                        MessageRegistration.class,
                        RequestHandler.class);

        assertEquals(MessageRegistration.class, register.getReturnType());
        assertEquals(Subscription.class, handle.getReturnType());
        assertTrue(AutoCloseable.class.isAssignableFrom(MessageRegistration.class));
    }

    private static void assertFuturePayload(Method method, Class<?> expectedPayload) {
        ParameterizedType future = (ParameterizedType) method.getGenericReturnType();
        ParameterizedType payload = (ParameterizedType) future.getActualTypeArguments()[0];
        assertEquals(expectedPayload, payload.getRawType());
    }
}
