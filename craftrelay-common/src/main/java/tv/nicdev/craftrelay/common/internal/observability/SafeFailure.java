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

import java.util.Objects;

/**
 * Non-sensitive failure classification.
 *
 * <p>Exception messages are deliberately excluded because third-party codecs and Redis clients
 * can include payloads, keys, URIs, or credentials in them.
 */
public record SafeFailure(String type) {

    public SafeFailure {
        type = Objects.requireNonNull(type, "type");
    }

    static SafeFailure from(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        return new SafeFailure(failure.getClass().getName());
    }
}
