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
import java.util.Objects;
import java.util.Optional;

/** Immutable, bounded and non-sensitive diagnostic event. */
public record DiagnosticEvent(
        DiagnosticCode code,
        Instant occurredAt,
        long suppressedCount,
        Optional<SafeFailure> failure) {

    public DiagnosticEvent {
        code = Objects.requireNonNull(code, "code");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        if (suppressedCount < 0) {
            throw new IllegalArgumentException("suppressedCount must not be negative");
        }
        failure = Objects.requireNonNull(failure, "failure");
    }

    /** Returns a safe single-line representation suitable for platform logs. */
    public String logMessage() {
        StringBuilder message =
                new StringBuilder()
                        .append('[')
                        .append(code.id())
                        .append("] ")
                        .append(code.description());
        failure.ifPresent(value -> message.append(" (").append(value.type()).append(')'));
        if (suppressedCount > 0) {
            message.append(" [").append(suppressedCount).append(" similar events suppressed]");
        }
        return message.toString();
    }
}
