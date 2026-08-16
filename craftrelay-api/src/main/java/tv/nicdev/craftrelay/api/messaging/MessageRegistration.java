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
 * Thread-safe owner of one local custom-message type registration.
 *
 * <p>Closing a registration is idempotent. It rejects new work for the type and automatically
 * removes request handlers that depend on it. Work already accepted by CraftRelay may finish using
 * the immutable codec snapshot captured before the registration was closed.
 *
 * @param <M> registered message type
 *
 * @since 0.1.0
 */
public interface MessageRegistration<M extends NetworkMessage> extends AutoCloseable {

    /**
     * Returns the exact registered wire type.
     *
     * @return message type
     */
    MessageType<M> type();

    /**
     * Returns whether this registration has been closed.
     *
     * @return {@code true} after the registration stopped accepting new work
     */
    boolean isClosed();

    /** Closes this registration at most once. */
    @Override
    void close();
}
