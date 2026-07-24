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
package tv.nicdev.craftrelay.common.transport;

/**
 * Receives opaque transport payloads.
 *
 * <p>The supplied payload is isolated from other listeners. Implementations catch listener
 * failures so one consumer cannot interrupt delivery to another consumer. Implementations must
 * dispatch callbacks away from transport I/O threads.
 */
@FunctionalInterface
public interface TransportListener {

    /**
     * Handles a transport message.
     *
     * @param channel source channel
     * @param payload independently owned payload bytes
     */
    void onMessage(String channel, byte[] payload);
}
