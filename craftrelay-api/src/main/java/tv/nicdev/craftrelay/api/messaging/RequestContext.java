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

import java.util.Objects;

/**
 * Public metadata for a received request.
 *
 * @param sourceInstance ID of the requesting CraftRelay instance
 */
public record RequestContext(String sourceInstance) {

    /** Creates validated request metadata. */
    public RequestContext {
        Objects.requireNonNull(sourceInstance, "sourceInstance");
        if (sourceInstance.isBlank()) {
            throw new IllegalArgumentException("sourceInstance must not be blank");
        }
    }
}
