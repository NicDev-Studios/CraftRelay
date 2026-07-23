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

class CraftRelayApiContractTest {

    @Test
    void potentiallySlowOperationsReturnCompletableFutures() {
        Method[] asynchronousMethods =
                Arrays.stream(CraftRelayApi.class.getDeclaredMethods())
                        .filter(method -> !method.getName().equals("subscribe"))
                        .filter(method -> !method.getName().equals("state"))
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

        assertEquals(CompletableFuture.class, publish.getReturnType());
        assertEquals(Subscription.class, subscribe.getReturnType());
        assertEquals(CompletableFuture.class, request.getReturnType());
        assertFuturePayload(instances, Collection.class);
        assertFuturePayload(player, Optional.class);
    }

    private static void assertFuturePayload(Method method, Class<?> expectedPayload) {
        ParameterizedType future = (ParameterizedType) method.getGenericReturnType();
        ParameterizedType payload = (ParameterizedType) future.getActualTypeArguments()[0];
        assertEquals(expectedPayload, payload.getRawType());
    }
}
