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
package tv.nicdev.craftrelay.common.internal.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class NodeDiagnosticsTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void snapshotsAreImmutableAndAggregateComponentHealth() {
        NodeDiagnostics diagnostics = diagnostics(event -> {});

        assertEquals(HealthStatus.UNAVAILABLE, diagnostics.snapshot().health());
        markAllHealthy(diagnostics);
        assertEquals(HealthStatus.HEALTHY, diagnostics.snapshot().health());

        diagnostics.degraded(DiagnosticComponent.TRANSPORT);
        assertEquals(HealthStatus.DEGRADED, diagnostics.snapshot().health());
        diagnostics.healthy(DiagnosticComponent.TRANSPORT);
        assertEquals(HealthStatus.HEALTHY, diagnostics.snapshot().health());

        DiagnosticsSnapshot snapshot = diagnostics.snapshot();
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.components().put(
                        DiagnosticComponent.NODE, HealthStatus.UNAVAILABLE));
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.counters().put(TelemetryCounter.MESSAGES_SENT, 1L));
    }

    @Test
    void countersRemainExactUnderParallelUpdates() {
        NodeDiagnostics diagnostics = diagnostics(event -> {});

        IntStream.range(0, 20_000)
                .parallel()
                .forEach(ignored ->
                        diagnostics.increment(TelemetryCounter.MESSAGES_RECEIVED));

        assertEquals(
                20_000L,
                diagnostics.snapshot().counters().get(TelemetryCounter.MESSAGES_RECEIVED));
    }

    @Test
    void gaugesSaturateWithoutOverflowingProductionPaths() {
        NodeDiagnostics diagnostics = diagnostics(event -> {});

        diagnostics.setGauge(TelemetryGauge.LISTENERS, Long.MAX_VALUE);
        assertDoesNotThrow(() -> diagnostics.addGauge(TelemetryGauge.LISTENERS, 1L));
        assertEquals(
                Long.MAX_VALUE,
                diagnostics.snapshot().gauges().get(TelemetryGauge.LISTENERS));

        assertDoesNotThrow(
                () -> diagnostics.addGauge(TelemetryGauge.LISTENERS, Long.MIN_VALUE));
        assertEquals(0L, diagnostics.snapshot().gauges().get(TelemetryGauge.LISTENERS));
    }

    @Test
    void overloadDegradesForBoundedWindowWithoutScheduler() {
        AtomicLong ticker = new AtomicLong();
        NodeDiagnostics diagnostics = diagnostics(ticker, event -> {});
        markAllHealthy(diagnostics);

        diagnostics.overflow();
        assertEquals(HealthStatus.DEGRADED, diagnostics.snapshot().health());

        ticker.addAndGet(NodeDiagnostics.OVERLOAD_DEGRADATION_WINDOW.plusSeconds(1).toNanos());
        assertEquals(HealthStatus.HEALTHY, diagnostics.snapshot().health());
    }

    @Test
    void repeatedEventsAreCoalescedAndFailuresNeverExposeMessages() {
        AtomicLong ticker = new AtomicLong();
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        NodeDiagnostics diagnostics = diagnostics(ticker, events::add);
        RuntimeException secret = new RuntimeException(
                "redis://user:secret@example.invalid payload=private");

        diagnostics.report(DiagnosticCode.REDIS_OPERATION_FAILED, secret);
        diagnostics.report(DiagnosticCode.REDIS_OPERATION_FAILED, secret);
        diagnostics.report(DiagnosticCode.REDIS_OPERATION_FAILED, secret);
        assertEquals(1, events.size());

        ticker.addAndGet(NodeDiagnostics.EVENT_DEDUPLICATION_WINDOW.plusSeconds(1).toNanos());
        diagnostics.report(DiagnosticCode.REDIS_OPERATION_FAILED, secret);

        assertEquals(2, events.size());
        assertEquals(2L, events.get(1).suppressedCount());
        assertFalse(events.get(1).logMessage().contains("secret"));
        assertFalse(events.get(1).logMessage().contains("private"));
    }

    @Test
    void failingDiagnosticSinkIsIsolated() {
        NodeDiagnostics diagnostics = diagnostics(event -> {
            throw new IllegalStateException("sink failed");
        });

        assertDoesNotThrow(
                () -> diagnostics.report(DiagnosticCode.MESSAGE_DECODE_FAILED, null));
    }

    private static NodeDiagnostics diagnostics(DiagnosticSink sink) {
        return diagnostics(new AtomicLong(), sink);
    }

    private static NodeDiagnostics diagnostics(
            AtomicLong ticker, DiagnosticSink sink) {
        return new NodeDiagnostics(
                Clock.fixed(NOW, ZoneOffset.UTC), ticker::get, sink);
    }

    private static void markAllHealthy(NodeDiagnostics diagnostics) {
        for (DiagnosticComponent component : DiagnosticComponent.values()) {
            diagnostics.healthy(component);
        }
    }
}
