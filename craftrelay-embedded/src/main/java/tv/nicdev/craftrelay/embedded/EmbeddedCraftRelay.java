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

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.common.internal.node.CraftRelayNode;
import tv.nicdev.craftrelay.common.internal.observability.DiagnosticEvent;
import tv.nicdev.craftrelay.common.internal.observability.DiagnosticSink;
import tv.nicdev.craftrelay.transport.redis.RedisCraftRelayNodeFactory;
import tv.nicdev.craftrelay.transport.redis.config.CraftRelayConfigFiles;
import tv.nicdev.craftrelay.transport.redis.config.CraftRelayRedisConfig;

/**
 * Entry point for composing a CraftRelay node inside a host plugin.
 *
 * <p>The builder performs configuration I/O synchronously so a host can fail fast during its
 * own setup phase. Network work starts only when {@link EmbeddedCraftRelayNode#start()} is
 * called. The returned node owns all CraftRelay resources and must be stopped by its host.
 *
 * @since 0.1.0
 */
public final class EmbeddedCraftRelay {

    private EmbeddedCraftRelay() {
    }

    /**
     * Creates a builder for an embedded node.
     *
     * @param configDirectory directory containing the node's {@code config.yml}
     * @param instanceType role of this node in the network
     * @return a new builder
     * @throws NullPointerException if an argument is {@code null}
     * @since 0.1.0
     */
    public static Builder builder(Path configDirectory, NetworkInstanceType instanceType) {
        return new Builder(configDirectory, instanceType);
    }

    /** Mutable configuration builder. Create one node per configured instance directory.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private final Path configDirectory;
        private final NetworkInstanceType instanceType;
        private IntSupplier onlinePlayerCount;
        private Consumer<EmbeddedDiagnostic> diagnosticListener = ignored -> { };
        private Consumer<? super String> startupLogger = ignored -> { };

        private Builder(Path configDirectory, NetworkInstanceType instanceType) {
            this.configDirectory = Objects.requireNonNull(configDirectory, "configDirectory");
            this.instanceType = Objects.requireNonNull(instanceType, "instanceType");
        }

        /**
         * Supplies the current local online-player count in constant time.
         *
         * <p>The supplier is sampled on CraftRelay's background presence scheduler. It must not
         * perform platform I/O or block. A negative result or a runtime failure is reported as a
         * safe diagnostic and treated as zero.
         *
         * @param supplier non-null count supplier
         * @return this builder
         * @throws NullPointerException if {@code supplier} is {@code null}
         * @since 0.1.0
         */
        public Builder onlinePlayerCount(IntSupplier supplier) {
            onlinePlayerCount = Objects.requireNonNull(supplier, "onlinePlayerCount");
            return this;
        }

        /**
         * Receives safe diagnostic events on a CraftRelay background path.
         *
         * <p>The callback must return promptly. Exceptions thrown by it are isolated and never
         * affect the node lifecycle.
         *
         * @param listener callback, or {@code null} is rejected
         * @return this builder
         * @throws NullPointerException if {@code listener} is {@code null}
         * @since 0.1.0
         */
        public Builder diagnosticListener(Consumer<EmbeddedDiagnostic> listener) {
            diagnosticListener = Objects.requireNonNull(listener, "diagnosticListener");
            return this;
        }

        /**
         * Supplies a host-owned logger for the SDK startup banner and one short startup line.
         *
         * <p>The callback is invoked asynchronously on a CraftRelay background path and must
         * return promptly. It receives no secrets, payloads, identifiers, or exception text.
         * Ready and failure handling remains the responsibility of the {@link
         * EmbeddedCraftRelayNode#start()} future, so hosts can choose their own log level and
         * scheduler. The default is a no-op; the SDK never installs or uses a global logger.
         *
         * @param logger host-owned logging callback
         * @return this builder
         * @throws NullPointerException if {@code logger} is {@code null}
         * @since 0.1.0
         */
        public Builder startupLogger(Consumer<? super String> logger) {
            startupLogger = Objects.requireNonNull(logger, "startupLogger");
            return this;
        }

        /**
         * Loads or creates the shared CraftRelay configuration and composes an unopened node.
         *
         * @return configured embedded node
         * @throws IllegalStateException if no online-player supplier was configured
         * @throws EmbeddedConfigurationException if configuration I/O or validation fails
         * @since 0.1.0
         */
        public EmbeddedCraftRelayNode build() {
            IntSupplier countSupplier = onlinePlayerCount;
            if (countSupplier == null) {
                throw new IllegalStateException("onlinePlayerCount must be configured");
            }
            Consumer<EmbeddedDiagnostic> listener = diagnosticListener;
            Consumer<? super String> logger = startupLogger;
            ExecutorService diagnosticExecutor = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("craftrelay-embedded-diagnostics-", 0).factory());

            CraftRelayRedisConfig config;
            try {
                config = CraftRelayConfigFiles.loadOrCreate(configDirectory);
            } catch (Exception failure) {
                diagnosticExecutor.shutdownNow();
                throw new EmbeddedConfigurationException(
                        "Could not load or validate the CraftRelay configuration");
            }

            DiagnosticSink sink = event -> dispatchDiagnostic(
                    diagnosticExecutor, () -> reportDiagnostic(listener, event));
            IntSupplier safeCount = () -> safeCount(countSupplier, diagnosticExecutor, listener);
            CraftRelayNode node;
            try {
                node = RedisCraftRelayNodeFactory.create(
                        config,
                        instanceType,
                        safeCount,
                        session -> { },
                        sink);
            } catch (RuntimeException failure) {
                diagnosticExecutor.shutdownNow();
                throw new EmbeddedConfigurationException(
                        "Could not initialize the embedded CraftRelay node");
            } catch (Error fatal) {
                diagnosticExecutor.shutdownNow();
                throw fatal;
            }
            return new EmbeddedCraftRelayNode(node, diagnosticExecutor, logger);
        }

        private static int safeCount(
                IntSupplier supplier,
                ExecutorService diagnosticExecutor,
                Consumer<EmbeddedDiagnostic> listener) {
            try {
                int count = supplier.getAsInt();
                if (count < 0) {
                    dispatchDiagnostic(diagnosticExecutor, () -> reportDiagnostic(
                            listener,
                            "CR-EMBEDDED-ONLINE-COUNT-INVALID",
                            EmbeddedDiagnostic.Severity.WARNING));
                    return 0;
                }
                return count;
            } catch (RuntimeException failure) {
                dispatchDiagnostic(diagnosticExecutor, () -> reportDiagnostic(
                        listener,
                        "CR-EMBEDDED-ONLINE-COUNT-FAILED",
                        EmbeddedDiagnostic.Severity.WARNING));
                return 0;
            }
        }

        private static void reportDiagnostic(
                Consumer<EmbeddedDiagnostic> listener, DiagnosticEvent event) {
            reportDiagnostic(
                    listener,
                    new EmbeddedDiagnostic(
                            event.code().id(),
                            EmbeddedDiagnostic.Severity.valueOf(event.code().severity().name()),
                            event.occurredAt(),
                            event.suppressedCount(),
                            event.failure().map(value -> value.type())));
        }

        private static void reportDiagnostic(
                Consumer<EmbeddedDiagnostic> listener,
                String code,
                EmbeddedDiagnostic.Severity severity) {
            reportDiagnostic(listener, new EmbeddedDiagnostic(
                    code,
                    severity,
                    Instant.now(),
                    0,
                    Optional.empty()));
        }

        private static void reportDiagnostic(
                Consumer<EmbeddedDiagnostic> listener,
                EmbeddedDiagnostic diagnostic) {
            try {
                listener.accept(diagnostic);
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                // Host diagnostics are deliberately isolated from the count supplier path.
            }
        }

        private static void dispatchDiagnostic(
                ExecutorService executor,
                Runnable task) {
            try {
                executor.execute(task);
            } catch (RejectedExecutionException ignored) {
                // Diagnostics emitted after node shutdown are intentionally discarded.
            }
        }
    }
}
