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

import java.util.Objects;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.model.NetworkInstance;

/**
 * Reports the latest state of a live network instance.
 *
 * @param instance immutable instance snapshot
 */
public record InstanceHeartbeatMessage(NetworkInstance instance) implements NetworkMessage {

    /** Creates a validated heartbeat message. */
    public InstanceHeartbeatMessage {
        instance = Objects.requireNonNull(instance, "instance");
    }
}
