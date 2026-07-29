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

/** Fixed-cardinality cumulative telemetry counters. */
public enum TelemetryCounter {
    MESSAGES_SENT,
    MESSAGES_RECEIVED,
    MESSAGES_DECODED,
    MESSAGES_DELIVERED,
    MESSAGE_DUPLICATES_DROPPED,
    MESSAGE_INVALID_DROPPED,
    MESSAGE_PUBLISH_FAILURES,
    MESSAGE_CODEC_FAILURES,
    REQUESTS_STARTED,
    REQUESTS_COMPLETED,
    REQUESTS_CANCELLED,
    REQUESTS_REJECTED,
    REQUESTS_TIMED_OUT,
    DISPATCH_OVERFLOWS,
    REDIS_DISCONNECTS,
    REDIS_RECONNECTS,
    REDIS_OPERATION_FAILURES,
    INSTANCE_HEARTBEAT_SUCCESSES,
    INSTANCE_HEARTBEAT_FAILURES,
    PLAYER_REFRESH_SUCCESSES,
    PLAYER_REFRESH_FAILURES,
    INSTANCE_LEASE_LOSSES,
    PLAYER_OWNERSHIP_LOSSES
}
