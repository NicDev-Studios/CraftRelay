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
package tv.nicdev.craftrelay.transport.redis.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlCraftRelayConfigLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsCompleteStrictConfiguration() throws IOException {
        CraftRelayRedisConfig config = load(validYaml("proxy-eu-1"));

        assertEquals("proxy-eu-1", config.instanceId());
        assertEquals("eu", config.group().orElseThrow());
        assertEquals("redis.internal", config.redis().host());
        assertEquals(6380, config.redis().port());
        assertEquals(Duration.ofSeconds(3), config.redis().connectionTimeout());
        assertEquals("network", config.messaging().channelPrefix());
        assertEquals(20_000, config.messaging().duplicateCacheCapacity());
        assertEquals(512, config.messaging().capacities().dispatchQueueCapacity());
        assertEquals(1_024, config.messaging().capacities().maximumListeners());
        assertEquals(256, config.messaging().capacities().maximumCustomRegistrations());
        assertEquals(128, config.messaging().capacities().maximumCustomRequestHandlers());
        assertEquals(2_048, config.requests().maximumPendingRequests());
        assertEquals(Duration.ofSeconds(10), config.shutdownTimeout());
        assertFalse(config.redis().toString().contains("secret"));
    }

    @Test
    void rejectsUnknownKeysAndPlaceholderIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> load(validYaml("proxy-1").replace(
                        "  group: \"eu\"", "  group: \"eu\"\n  unexpected: true")));
        assertThrows(
                IllegalArgumentException.class,
                () -> load(validYaml("change-me")));
        assertThrows(
                IllegalArgumentException.class,
                () -> load(validYaml("")));
        assertThrows(
                IllegalArgumentException.class,
                () -> load(validYaml("proxy-1").replace("  id: \"proxy-1\"\n", "")));
    }

    @Test
    void environmentOverridesPerNodeIdentityGroupAndRedisPassword() throws IOException {
        String password = "runtime-secret-value";
        String username = "runtime-acl-user";
        CraftRelayRedisConfig config = load(
                validYaml("yaml-id"),
                Map.of(
                        "CRAFTRELAY_INSTANCE_ID", "paper-lobby-7",
                        "CRAFTRELAY_INSTANCE_GROUP", "lobby",
                        "CRAFTRELAY_REDIS_USERNAME", username,
                        "CRAFTRELAY_REDIS_PASSWORD", password));

        assertEquals("paper-lobby-7", config.instanceId());
        assertEquals("lobby", config.group().orElseThrow());
        assertEquals(username, config.redis().username().orElseThrow());
        assertEquals(password, config.redis().password().orElseThrow());
        assertFalse(config.redis().toString().contains(username));
        assertFalse(config.redis().toString().contains(password));
        assertFalse(config.toString().contains(username));
        assertFalse(config.toString().contains(password));
    }

    @Test
    void environmentIdentityCanReplaceAnOmittedYamlIdAndGroupIsOptional() throws IOException {
        String yaml = validYaml("yaml-id")
                .replace(
                        "instance:\n  id: \"yaml-id\"\n  group: \"eu\"",
                        "instance: {}");

        CraftRelayRedisConfig config = load(
                yaml, Map.of("CRAFTRELAY_INSTANCE_ID", "proxy-eu-3"));

        assertEquals("proxy-eu-3", config.instanceId());
        assertTrue(config.group().isEmpty());
    }

    @Test
    void emptyGroupEnvironmentValueClearsYamlGroup() throws IOException {
        CraftRelayRedisConfig config = load(
                validYaml("proxy-1"), Map.of("CRAFTRELAY_INSTANCE_GROUP", ""));

        assertTrue(config.group().isEmpty());
    }

    @Test
    void rejectsEmptyOrPlaceholderEnvironmentIdentityWithoutExposingSecrets() throws IOException {
        String secret = "do-not-print-this-password";
        IllegalArgumentException empty = assertThrows(
                IllegalArgumentException.class,
                () -> load(validYaml("yaml-id"), Map.of(
                        "CRAFTRELAY_INSTANCE_ID", "",
                        "CRAFTRELAY_REDIS_PASSWORD", secret)));
        assertTrue(empty.getMessage().contains("CRAFTRELAY_INSTANCE_ID"));
        assertFalse(empty.getMessage().contains(secret));

        IllegalArgumentException placeholder = assertThrows(
                IllegalArgumentException.class,
                () -> load(validYaml("yaml-id"), Map.of(
                        "CRAFTRELAY_INSTANCE_ID", "change-me")));
        assertTrue(placeholder.getMessage().contains("change-me"));
    }

    @Test
    void rejectsAnEmptyRedisPasswordEnvironmentValueWithoutEchoingIt() throws IOException {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> load(validYaml("proxy-1"), Map.of(
                        "CRAFTRELAY_REDIS_PASSWORD", "")));

        assertTrue(failure.getMessage().contains("CRAFTRELAY_REDIS_PASSWORD"));
        assertFalse(failure.getMessage().contains("secret"));
    }

    @Test
    void requiresSupportedSchemaVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> load(validYaml("proxy-1").replace("config-version: 1", "config-version: 2")));
        assertThrows(
                IllegalArgumentException.class,
                () -> load(validYaml("proxy-1").replace("config-version: 1\n", "")));
    }

    @Test
    void rejectsUnsafeTagsAndExcessiveAliases() {
        assertThrows(
                IllegalArgumentException.class,
                () -> load("!!java.lang.Runtime {}\n"));

        StringBuilder aliases = new StringBuilder("values: &values [1]\nroot:\n");
        for (int index = 0; index < 12; index++) {
            aliases.append("  value").append(index).append(": *values\n");
        }
        assertThrows(IllegalArgumentException.class, () -> load(aliases.toString()));
    }

    @Test
    void createsDocumentedDefaultWithoutSilentlyStarting() throws IOException {
        assertThrows(
                IllegalArgumentException.class,
                () -> CraftRelayConfigFiles.loadOrCreate(
                        temporaryDirectory.resolve("plugin"), Map.of()));
        Path config = temporaryDirectory.resolve("plugin").resolve("config.yml");
        assertTrue(Files.isRegularFile(config));
        assertTrue(Files.readString(config).contains("id: \"change-me\""));
    }

    private CraftRelayRedisConfig load(String yaml) throws IOException {
        return load(yaml, Map.of());
    }

    private CraftRelayRedisConfig load(String yaml, Map<String, String> environment)
            throws IOException {
        Path file = temporaryDirectory.resolve("config.yml");
        Files.writeString(file, yaml);
        return new YamlCraftRelayConfigLoader().load(file, environment);
    }

    private static String validYaml(String instanceId) {
        return """
                config-version: 1
                instance:
                  id: "%s"
                  group: "eu"
                redis:
                  host: "redis.internal"
                  port: 6380
                  username: "craftrelay"
                  password: "secret"
                  database: 2
                  tls: true
                  connection-timeout: "3s"
                messaging:
                  prefix: "network"
                  duplicate-cache-capacity: 20000
                  dispatch-queue-capacity: 512
                  maximum-listeners: 1024
                  maximum-custom-registrations: 256
                  maximum-custom-request-handlers: 128
                requests:
                  maximum-pending: 2048
                presence:
                  instance:
                    heartbeat-interval: "5s"
                    ttl: "20s"
                    cleanup-batch: 128
                  player:
                    refresh-interval: "5s"
                    ttl: "20s"
                    batch-size: 128
                platform:
                  shutdown-timeout: "PT10S"
                  login-unavailable-message: "Unavailable"
                  duplicate-session-message: "Already online"
                """.formatted(instanceId);
    }

}
