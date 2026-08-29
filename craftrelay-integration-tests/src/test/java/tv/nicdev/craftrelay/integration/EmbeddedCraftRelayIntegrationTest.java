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
package tv.nicdev.craftrelay.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.message.GlobalBroadcastMessage;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.api.messaging.MessagePayloadCodec;
import tv.nicdev.craftrelay.api.messaging.MessageRegistration;
import tv.nicdev.craftrelay.api.messaging.MessageType;
import tv.nicdev.craftrelay.api.target.NetworkTargets;
import tv.nicdev.craftrelay.embedded.EmbeddedCraftRelay;
import tv.nicdev.craftrelay.embedded.EmbeddedCraftRelayNode;

/** End-to-end checks for two platform-neutral embedded nodes. */
@Testcontainers
class EmbeddedCraftRelayIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final DockerImageName REDIS_IMAGE =
            DockerImageName.parse("redis:7.4.2-alpine");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(REDIS_PORT);

    @Test
    void embeddedNodesExchangeBuiltInAndCustomMessagesAndExposePresence(@TempDir Path directory)
            throws Exception {
        String prefix = "embedded-test-" + System.nanoTime();
        EmbeddedCraftRelayNode server = node(
                directory.resolve("server"), "embedded-server", NetworkInstanceType.SERVER,
                prefix);
        EmbeddedCraftRelayNode proxy = node(
                directory.resolve("proxy"), "embedded-proxy", NetworkInstanceType.PROXY,
                prefix);
        Subscription broadcastSubscription = null;
        Subscription customSubscription = null;
        MessageRegistration<ProbeMessage> serverRegistration = null;
        MessageRegistration<ProbeMessage> proxyRegistration = null;
        try {
            CompletableFuture.allOf(server.start(), proxy.start())
                    .orTimeout(15, TimeUnit.SECONDS)
                    .join();
            var serverApi = server.api().orElseThrow();
            var proxyApi = proxy.api().orElseThrow();

            CountDownLatch broadcastReceived = new CountDownLatch(1);
            AtomicReference<GlobalBroadcastMessage> broadcast = new AtomicReference<>();
            broadcastSubscription = serverApi.subscribe(
                    GlobalBroadcastMessage.class,
                    message -> {
                        broadcast.set(message);
                        broadcastReceived.countDown();
                    });
            proxyApi.publish(
                            NetworkTargets.allInstances(),
                            new GlobalBroadcastMessage("embedded broadcast"))
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();
            assertTrue(broadcastReceived.await(5, TimeUnit.SECONDS));
            assertEquals("embedded broadcast", broadcast.get().content());

            MessageType<ProbeMessage> type = MessageType.of(
                    "integration", "probe", 1, ProbeMessage.class);
            MessagePayloadCodec<ProbeMessage> codec = new ProbeCodec();
            serverRegistration = serverApi.customMessaging().register(type, codec);
            proxyRegistration = proxyApi.customMessaging().register(type, codec);
            CountDownLatch customReceived = new CountDownLatch(1);
            AtomicReference<ProbeMessage> custom = new AtomicReference<>();
            customSubscription = serverApi.subscribe(
                    ProbeMessage.class,
                    message -> {
                        custom.set(message);
                        customReceived.countDown();
                    });
            proxyApi.publish(NetworkTargets.allServers(), new ProbeMessage("custom payload"))
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();
            assertTrue(customReceived.await(5, TimeUnit.SECONDS));
            assertEquals(new ProbeMessage("custom payload"), custom.get());

            Collection<NetworkInstance> instances = serverApi.instances()
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();
            assertEquals(
                    java.util.List.of("embedded-proxy", "embedded-server"),
                    instances.stream().map(NetworkInstance::id).toList());

            EmbeddedCraftRelayNode duplicate = node(
                    directory.resolve("duplicate"), "embedded-server", NetworkInstanceType.SERVER,
                    prefix);
            try {
                CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                        CompletionException.class,
                        () -> duplicate.start().orTimeout(5, TimeUnit.SECONDS).join());
                assertInstanceOf(
                        tv.nicdev.craftrelay.api.exception.ApiUnavailableException.class,
                        failure.getCause());
            } finally {
                duplicate.stop().orTimeout(5, TimeUnit.SECONDS).join();
            }
        } finally {
            close(broadcastSubscription);
            close(customSubscription);
            close(serverRegistration);
            close(proxyRegistration);
            CompletableFuture.allOf(server.stop(), proxy.stop())
                    .orTimeout(10, TimeUnit.SECONDS)
                    .join();
        }
    }

    private static EmbeddedCraftRelayNode node(
            Path directory, String id, NetworkInstanceType type, String prefix) throws IOException {
        writeConfig(directory, id, prefix);
        return EmbeddedCraftRelay.builder(directory, type)
                .onlinePlayerCount(() -> 0)
                .build();
    }

    private static void writeConfig(Path directory, String id, String prefix) throws IOException {
        Files.createDirectories(directory);
        String template;
        try (var input = EmbeddedCraftRelayIntegrationTest.class
                .getResourceAsStream("/craftrelay-default-config.yml")) {
            if (input == null) {
                throw new IOException("CraftRelay default configuration is missing");
            }
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String config = template
                .replace("change-me", id)
                .replace("127.0.0.1", REDIS.getHost())
                .replace("6379", Integer.toString(REDIS.getMappedPort(REDIS_PORT)))
                .replace("prefix: \"craftrelay\"", "prefix: \"" + prefix + "\"")
                .replace("group: null", "group: \"integration\"");
        Files.writeString(directory.resolve("config.yml"), config);
    }

    private static void close(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Cleanup is best effort; the node close below remains authoritative.
            }
        }
    }

    private record ProbeMessage(String value) implements NetworkMessage {
        private ProbeMessage {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("value must not be blank");
            }
        }
    }

    private static final class ProbeCodec implements MessagePayloadCodec<ProbeMessage> {
        @Override
        public byte[] encode(ProbeMessage message) {
            String escaped = message.value().replace("\\", "\\\\").replace("\"", "\\\"");
            return ("{\"value\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public ProbeMessage decode(byte[] payload) {
            String json = new String(payload, StandardCharsets.UTF_8);
            if (!json.startsWith("{\"value\":\"") || !json.endsWith("\"}")) {
                throw new IllegalArgumentException("invalid probe payload");
            }
            return new ProbeMessage(json.substring(10, json.length() - 2));
        }
    }
}
