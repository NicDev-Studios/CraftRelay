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
package tv.nicdev.craftrelay.api.message;

import java.time.Instant;
import tv.nicdev.craftrelay.api.NetworkMessage;

/**
 * Announces a graceful instance shutdown.
 *
 * @param instanceId network-unique instance ID
 * @param stoppedAt shutdown time
 */
public record InstanceStoppedMessage(String instanceId, Instant stoppedAt)
        implements NetworkMessage {

    /** Creates a validated instance-stopped message. */
    public InstanceStoppedMessage {
        instanceId = MessageValidation.requireText(instanceId, "instanceId");
        stoppedAt = MessageValidation.requireInstant(stoppedAt, "stoppedAt");
    }
}
