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

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable wire metadata available to an internal request handler.
 *
 * @param sourceInstance requesting instance ID
 * @param correlationId request correlation ID
 */
public record RequestContext(String sourceInstance, UUID correlationId) {

    /** Creates validated request metadata. */
    public RequestContext {
        sourceInstance = requireText(sourceInstance, "sourceInstance");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
