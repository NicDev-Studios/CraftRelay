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
package tv.nicdev.craftrelay.api.exception;

import java.io.Serial;

/**
 * Indicates an unsupported or malformed CraftRelay protocol interaction.
 */
public final class ProtocolException extends CraftRelayException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a protocol failure.
     *
     * @param message failure description
     */
    public ProtocolException(String message) {
        super(message);
    }

    /**
     * Creates a protocol failure with its cause.
     *
     * @param message failure description
     * @param cause underlying cause
     */
    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
