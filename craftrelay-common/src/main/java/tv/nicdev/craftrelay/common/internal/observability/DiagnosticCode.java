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

/** Stable, bounded diagnostic codes with controlled, non-sensitive descriptions. */
public enum DiagnosticCode {
    NODE_START_FAILED("CR-NODE-START-FAILED", DiagnosticComponent.NODE,
            DiagnosticSeverity.ERROR, "CraftRelay node startup failed"),
    NODE_STOP_FAILED("CR-NODE-STOP-FAILED", DiagnosticComponent.NODE,
            DiagnosticSeverity.WARNING, "CraftRelay node shutdown completed with failures"),
    REDIS_DISCONNECTED("CR-REDIS-DISCONNECTED", DiagnosticComponent.TRANSPORT,
            DiagnosticSeverity.WARNING, "Redis transport connection was lost"),
    REDIS_RECONNECTED("CR-REDIS-RECONNECTED", DiagnosticComponent.TRANSPORT,
            DiagnosticSeverity.INFO, "Redis transport connection was restored"),
    REDIS_OPERATION_FAILED("CR-REDIS-OPERATION-FAILED", DiagnosticComponent.TRANSPORT,
            DiagnosticSeverity.WARNING, "A Redis operation failed"),
    INSTANCE_HEARTBEAT_FAILED(
            "CR-INSTANCE-HEARTBEAT-FAILED", DiagnosticComponent.INSTANCE_PRESENCE,
            DiagnosticSeverity.WARNING, "Instance presence heartbeat failed"),
    PLAYER_REFRESH_FAILED(
            "CR-PLAYER-REFRESH-FAILED", DiagnosticComponent.PLAYER_PRESENCE,
            DiagnosticSeverity.WARNING, "Player presence refresh failed"),
    INSTANCE_LEASE_LOST("CR-INSTANCE-LEASE-LOST", DiagnosticComponent.INSTANCE_PRESENCE,
            DiagnosticSeverity.ERROR, "Instance lease ownership was lost"),
    PLAYER_OWNERSHIP_LOST("CR-PLAYER-OWNERSHIP-LOST", DiagnosticComponent.PLAYER_PRESENCE,
            DiagnosticSeverity.WARNING, "A local player session lost ownership"),
    MESSAGE_DECODE_FAILED("CR-MESSAGE-DECODE-FAILED", DiagnosticComponent.MESSAGING,
            DiagnosticSeverity.WARNING, "An inbound message could not be decoded"),
    MESSAGE_PUBLISH_FAILED("CR-MESSAGE-PUBLISH-FAILED", DiagnosticComponent.MESSAGING,
            DiagnosticSeverity.WARNING, "A message could not be published"),
    MESSAGE_CODEC_FAILED("CR-MESSAGE-CODEC-FAILED", DiagnosticComponent.MESSAGING,
            DiagnosticSeverity.WARNING, "A message codec failed"),
    LISTENER_FAILED("CR-LISTENER-FAILED", DiagnosticComponent.DISPATCHER,
            DiagnosticSeverity.WARNING, "A message listener failed"),
    HANDLER_FAILED("CR-HANDLER-FAILED", DiagnosticComponent.REQUESTS,
            DiagnosticSeverity.WARNING, "A request handler failed"),
    DISPATCH_OVERFLOW("CR-DISPATCH-OVERFLOW", DiagnosticComponent.DISPATCHER,
            DiagnosticSeverity.WARNING, "A bounded dispatch queue rejected work"),
    REQUEST_REJECTED("CR-REQUEST-REJECTED", DiagnosticComponent.REQUESTS,
            DiagnosticSeverity.WARNING,
            "A request was rejected by a capacity or lifecycle boundary"),
    REQUEST_TIMEOUT("CR-REQUEST-TIMEOUT", DiagnosticComponent.REQUESTS,
            DiagnosticSeverity.WARNING, "A request timed out");

    private final String id;
    private final DiagnosticComponent component;
    private final DiagnosticSeverity severity;
    private final String description;

    DiagnosticCode(
            String id,
            DiagnosticComponent component,
            DiagnosticSeverity severity,
            String description) {
        this.id = id;
        this.component = component;
        this.severity = severity;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public DiagnosticComponent component() {
        return component;
    }

    public DiagnosticSeverity severity() {
        return severity;
    }

    public String description() {
        return description;
    }
}
