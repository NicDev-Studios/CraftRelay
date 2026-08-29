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
package tv.nicdev.craftrelay.embedded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;

class EmbeddedCraftRelayTest {

    private static final long TIMEOUT_MILLIS = 5_000L;

    @Test
    void builderRequiresAnOnlinePlayerCount(@TempDir Path directory) {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> EmbeddedCraftRelay.builder(directory, NetworkInstanceType.SERVER).build());
        assertEquals("onlinePlayerCount must be configured", failure.getMessage());
    }

    @Test
    void createsMissingConfigurationButDoesNotOverwriteIt(@TempDir Path directory)
            throws Exception {
        Path config = directory.resolve("config.yml");
        assertThrows(
                EmbeddedConfigurationException.class,
                () -> EmbeddedCraftRelay.builder(directory, NetworkInstanceType.SERVER)
                        .onlinePlayerCount(() -> 0)
                        .build());
        assertTrue(Files.isRegularFile(config));
        String generated = Files.readString(config);

        Files.writeString(config, generated.replace("change-me", "embedded-test"));
        String before = Files.readString(config);
        EmbeddedCraftRelayNode second = EmbeddedCraftRelay.builder(
                        directory, NetworkInstanceType.SERVER)
                .onlinePlayerCount(() -> 0)
                .build();
        assertEquals(before, Files.readString(config));
        second.stop().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Test
    void invalidConfigurationUsesAStableSafeException(@TempDir Path directory)
            throws IOException {
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("config.yml"),
                "config-version: 1\nredis:\n  password: super-secret\n");

        EmbeddedConfigurationException failure = assertThrows(
                EmbeddedConfigurationException.class,
                () -> EmbeddedCraftRelay.builder(directory, NetworkInstanceType.SERVER)
                        .onlinePlayerCount(() -> 0)
                        .build());
        assertEquals(
                "Could not load or validate the CraftRelay configuration",
                failure.getMessage());
        assertFalse(failure.toString().contains("super-secret"));
    }

    @Test
    void apiIsEmptyBeforeStartAndStopIsTerminal(@TempDir Path directory) throws Exception {
        writeValidConfiguration(directory, "embedded-lifecycle");
        EmbeddedCraftRelayNode node = EmbeddedCraftRelay.builder(
                        directory, NetworkInstanceType.SERVER)
                .onlinePlayerCount(() -> 0)
                .build();

        assertTrue(node.api().isEmpty());
        CompletableFuture<Void> firstStop = node.stop();
        assertSame(firstStop, node.stop());
        firstStop.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        assertTrue(node.api().isEmpty());

        CompletableFuture<CraftRelayApi> startAfterStop = node.start();
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> startAfterStop.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        assertInstanceOf(ApiUnavailableException.class, failure.getCause());
    }

    @Test
    void diagnosticValueIsImmutableAndValidated() {
        Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");
        EmbeddedDiagnostic diagnostic = new EmbeddedDiagnostic(
                "CR-TEST",
                EmbeddedDiagnostic.Severity.WARNING,
                timestamp,
                3,
                Optional.of("java.lang.IllegalStateException"));
        assertEquals("CR-TEST", diagnostic.code());
        assertEquals(timestamp, diagnostic.occurredAt());
        assertEquals(Optional.of("java.lang.IllegalStateException"), diagnostic.failureType());

        assertThrows(
                IllegalArgumentException.class,
                () -> new EmbeddedDiagnostic(
                        "CR-TEST", EmbeddedDiagnostic.Severity.INFO, timestamp, -1,
                        Optional.empty()));
    }

    @Test
    void startupLoggerRejectsNull(@TempDir Path directory) {
        assertThrows(
                NullPointerException.class,
                () -> EmbeddedCraftRelay.builder(directory, NetworkInstanceType.SERVER)
                        .startupLogger(null));
    }

    @Test
    void countSupplierCanBeReusedWithoutPlatformTypes(@TempDir Path directory) throws Exception {
        writeValidConfiguration(directory, "embedded-count");
        AtomicInteger count = new AtomicInteger(4);
        IntSupplier supplier = count::get;
        EmbeddedCraftRelayNode node = EmbeddedCraftRelay.builder(
                        directory, NetworkInstanceType.PROXY)
                .onlinePlayerCount(supplier)
                .build();
        count.set(8);
        node.stop().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static void writeValidConfiguration(Path directory, String instanceId)
            throws IOException {
        Files.createDirectories(directory);
        String template;
        try (var input = EmbeddedCraftRelayTest.class.getResourceAsStream(
                "/craftrelay-default-config.yml")) {
            if (input == null) {
                throw new IOException("test configuration resource is missing");
            }
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Files.writeString(
                directory.resolve("config.yml"),
                template.replace("change-me", instanceId));
    }
}
