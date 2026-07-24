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
package tv.nicdev.craftrelay.common.internal.request;

import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.Subscription;

/**
 * Internal registry of built-in request handlers.
 */
public interface RequestHandlerRegistry extends AutoCloseable {

    /**
     * Registers the only handler for an exact request class.
     *
     * @param requestType exact request class
     * @param handler asynchronous handler
     * @param <Q> request type
     * @param <R> response type
     * @return idempotent registration
     * @throws IllegalArgumentException if the request type is already registered
     */
    <Q extends NetworkMessage, R extends NetworkMessage> Subscription register(
            Class<Q> requestType, RequestHandler<Q, R> handler);

    /**
     * Removes all handlers and stops handler delivery.
     */
    @Override
    void close();
}
