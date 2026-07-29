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

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Immutable point-in-time internal diagnostic snapshot. */
public record DiagnosticsSnapshot(
        HealthStatus health,
        Instant capturedAt,
        Map<DiagnosticComponent, HealthStatus> components,
        Map<TelemetryCounter, Long> counters,
        Map<TelemetryGauge, Long> gauges) {

    public DiagnosticsSnapshot {
        health = Objects.requireNonNull(health, "health");
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        components = Map.copyOf(Objects.requireNonNull(components, "components"));
        counters = Map.copyOf(Objects.requireNonNull(counters, "counters"));
        gauges = Map.copyOf(Objects.requireNonNull(gauges, "gauges"));
    }
}
