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
package tv.nicdev.craftrelay.api.messaging;

import tv.nicdev.craftrelay.api.NetworkMessage;

/**
 * Explicit JSON payload codec for one custom message type.
 *
 * <p>The encoded bytes must contain exactly one UTF-8 JSON object. Implementations must be
 * thread-safe. CraftRelay invokes codecs away from Redis, Netty, Paper, and Velocity I/O threads.
 *
 * @param <M> message type
 *
 * @since 0.1.0
 */
public interface MessagePayloadCodec<M extends NetworkMessage> {

    /**
     * Encodes one immutable message.
     *
     * @param message message to encode
     * @return non-null UTF-8 bytes containing one JSON object
     */
    byte[] encode(M message);

    /**
     * Decodes one JSON object.
     *
     * @param payload defensive UTF-8 JSON byte-array copy
     * @return non-null decoded message
     */
    M decode(byte[] payload);
}
