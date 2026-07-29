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

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Single thread-safe internal health, telemetry and diagnostic-event owner for one node.
 */
public final class NodeDiagnostics {

    static final Duration EVENT_DEDUPLICATION_WINDOW = Duration.ofSeconds(30);
    static final Duration OVERLOAD_DEGRADATION_WINDOW = Duration.ofSeconds(60);

    private final Clock clock;
    private final LongSupplier nanoTime;
    private final DiagnosticSink sink;
    private final EnumMap<DiagnosticComponent, AtomicLong> componentStates =
            new EnumMap<>(DiagnosticComponent.class);
    private final EnumMap<TelemetryCounter, LongAdder> counters =
            new EnumMap<>(TelemetryCounter.class);
    private final EnumMap<TelemetryGauge, AtomicLong> gauges =
            new EnumMap<>(TelemetryGauge.class);
    private final Map<DiagnosticCode, EventWindow> eventWindows = new ConcurrentHashMap<>();
    private final AtomicLong lastOverflowNanos = new AtomicLong(Long.MIN_VALUE);

    /** Creates diagnostics using system clocks and a no-op event sink. */
    public NodeDiagnostics() {
        this(DiagnosticSink.NOOP);
    }

    /** Creates diagnostics using system clocks. */
    public NodeDiagnostics(DiagnosticSink sink) {
        this(Clock.systemUTC(), System::nanoTime, sink);
    }

    NodeDiagnostics(Clock clock, LongSupplier nanoTime, DiagnosticSink sink) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.sink = Objects.requireNonNull(sink, "sink");
        for (DiagnosticComponent component : DiagnosticComponent.values()) {
            componentStates.put(component, new AtomicLong(HealthStatus.UNAVAILABLE.ordinal()));
        }
        for (TelemetryCounter counter : TelemetryCounter.values()) {
            counters.put(counter, new LongAdder());
        }
        for (TelemetryGauge gauge : TelemetryGauge.values()) {
            gauges.put(gauge, new AtomicLong());
        }
    }

    /** Marks a component healthy. */
    public void healthy(DiagnosticComponent component) {
        setHealth(component, HealthStatus.HEALTHY);
    }

    /** Marks a component degraded. */
    public void degraded(DiagnosticComponent component) {
        setHealth(component, HealthStatus.DEGRADED);
    }

    /** Marks a component unavailable. */
    public void unavailable(DiagnosticComponent component) {
        setHealth(component, HealthStatus.UNAVAILABLE);
    }

    /** Increments one cumulative counter. */
    public void increment(TelemetryCounter counter) {
        counters.get(Objects.requireNonNull(counter, "counter")).increment();
    }

    /** Adds a signed delta to a non-negative gauge. */
    public void addGauge(TelemetryGauge gauge, long delta) {
        AtomicLong value = gauges.get(Objects.requireNonNull(gauge, "gauge"));
        value.updateAndGet(current -> {
            if (delta > 0L && current > Long.MAX_VALUE - delta) {
                return Long.MAX_VALUE;
            }
            if (delta < 0L && delta < -current) {
                return 0L;
            }
            return current + delta;
        });
    }

    /** Sets one non-negative gauge. */
    public void setGauge(TelemetryGauge gauge, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("gauge value must not be negative");
        }
        gauges.get(Objects.requireNonNull(gauge, "gauge")).set(value);
    }

    /** Records bounded queue overload and its diagnostic event. */
    public void overflow() {
        lastOverflowNanos.set(nanoTime.getAsLong());
        increment(TelemetryCounter.DISPATCH_OVERFLOWS);
        report(DiagnosticCode.DISPATCH_OVERFLOW, null);
    }

    /** Reports one controlled event without exposing failure messages. */
    public void report(DiagnosticCode code, Throwable failure) {
        Objects.requireNonNull(code, "code");
        long nowNanos = nanoTime.getAsLong();
        EventEmission emission =
                eventWindows
                        .computeIfAbsent(code, ignored -> new EventWindow())
                        .record(nowNanos, EVENT_DEDUPLICATION_WINDOW.toNanos());
        if (!emission.emit()) {
            return;
        }
        DiagnosticEvent event =
                new DiagnosticEvent(
                        code,
                        clock.instant(),
                        emission.suppressed(),
                        Optional.ofNullable(failure).map(SafeFailure::from));
        try {
            sink.report(event);
        } catch (Throwable sinkFailure) {
            if (sinkFailure instanceof VirtualMachineError fatal) {
                throw fatal;
            }
        }
    }

    /** Returns one lock-free immutable snapshot. */
    public DiagnosticsSnapshot snapshot() {
        EnumMap<DiagnosticComponent, HealthStatus> componentSnapshot =
                new EnumMap<>(DiagnosticComponent.class);
        componentStates.forEach(
                (component, value) ->
                        componentSnapshot.put(
                                component, HealthStatus.values()[(int) value.get()]));
        EnumMap<TelemetryCounter, Long> counterSnapshot =
                new EnumMap<>(TelemetryCounter.class);
        counters.forEach((counter, value) -> counterSnapshot.put(counter, value.sum()));
        EnumMap<TelemetryGauge, Long> gaugeSnapshot =
                new EnumMap<>(TelemetryGauge.class);
        gauges.forEach((gauge, value) -> gaugeSnapshot.put(gauge, value.get()));
        return new DiagnosticsSnapshot(
                aggregateHealth(componentSnapshot),
                clock.instant(),
                componentSnapshot,
                counterSnapshot,
                gaugeSnapshot);
    }

    private void setHealth(DiagnosticComponent component, HealthStatus status) {
        componentStates
                .get(Objects.requireNonNull(component, "component"))
                .set(Objects.requireNonNull(status, "status").ordinal());
    }

    private HealthStatus aggregateHealth(
            Map<DiagnosticComponent, HealthStatus> componentSnapshot) {
        if (componentSnapshot.get(DiagnosticComponent.NODE) == HealthStatus.UNAVAILABLE) {
            return HealthStatus.UNAVAILABLE;
        }
        boolean overloaded =
                elapsedSince(lastOverflowNanos.get()) <= OVERLOAD_DEGRADATION_WINDOW.toNanos();
        boolean degraded = overloaded
                || componentSnapshot.values().stream()
                        .anyMatch(status -> status != HealthStatus.HEALTHY);
        return degraded ? HealthStatus.DEGRADED : HealthStatus.HEALTHY;
    }

    private long elapsedSince(long startedNanos) {
        if (startedNanos == Long.MIN_VALUE) {
            return Long.MAX_VALUE;
        }
        long elapsed = nanoTime.getAsLong() - startedNanos;
        return elapsed < 0 ? Long.MAX_VALUE : elapsed;
    }

    private static final class EventWindow {

        private long lastEmissionNanos = Long.MIN_VALUE;
        private long suppressed;

        private synchronized EventEmission record(long nowNanos, long windowNanos) {
            long elapsed = nowNanos - lastEmissionNanos;
            if (lastEmissionNanos != Long.MIN_VALUE && elapsed >= 0 && elapsed < windowNanos) {
                suppressed++;
                return new EventEmission(false, 0);
            }
            long priorSuppressed = suppressed;
            suppressed = 0;
            lastEmissionNanos = nowNanos;
            return new EventEmission(true, priorSuppressed);
        }
    }

    private record EventEmission(boolean emit, long suppressed) {}
}
