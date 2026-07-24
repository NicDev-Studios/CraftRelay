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

import java.util.concurrent.CompletionStage;
import tv.nicdev.craftrelay.api.NetworkMessage;

/**
 * Internal asynchronous handler for a built-in request message.
 *
 * @param <Q> request type
 * @param <R> response type
 */
@FunctionalInterface
public interface RequestHandler<Q extends NetworkMessage, R extends NetworkMessage> {

    /**
     * Handles a request without running on a transport I/O thread.
     *
     * @param request request payload
     * @param context request wire metadata
     * @return non-null asynchronous response
     */
    CompletionStage<? extends R> handle(Q request, RequestContext context);
}
