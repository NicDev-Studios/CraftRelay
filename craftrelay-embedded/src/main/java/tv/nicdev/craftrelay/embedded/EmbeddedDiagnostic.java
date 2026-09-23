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

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Safe, immutable diagnostic information emitted by an embedded node.
 *
 * <p>Only bounded metadata is exposed. The event never contains payloads, credentials, lease
 * tokens, player identifiers, instance identifiers or exception messages.
 *
 * @param code stable CraftRelay diagnostic code
 * @param severity event severity
 * @param occurredAt UTC timestamp at which the event was emitted
 * @param suppressedCount number of equal events coalesced before this event
 * @param failureType optional fully qualified type name of the classified failure
 * @since 0.1.0
 */
public record EmbeddedDiagnostic(
        String code,
        Severity severity,
        Instant occurredAt,
        long suppressedCount,
        Optional<String> failureType) {

    /** Creates a validated immutable diagnostic event.
     *
     * @since 0.1.0
     */
    public EmbeddedDiagnostic {
        code = requireText(code, "code");
        severity = Objects.requireNonNull(severity, "severity");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        if (suppressedCount < 0) {
            throw new IllegalArgumentException("suppressedCount must not be negative");
        }
        failureType = Objects.requireNonNull(failureType, "failureType")
                .map(value -> requireText(value, "failureType"));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Severity used by an embedded diagnostic event.
     *
     * @since 0.1.0
     */
    public enum Severity {
        /** Informational state transition. */
        INFO,
        /** Recoverable or temporary problem. */
        WARNING,
        /** Node or operation failure requiring attention. */
        ERROR
    }
}
