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
package tv.nicdev.craftrelay.common.internal.protocol;

/**
 * Internal factories for supported CraftRelay wire codecs.
 */
public final class MessageCodecs {

    private MessageCodecs() {
    }

    /**
     * Creates a protocol-version-1 codec containing every built-in message type.
     *
     * @return a new standard codec
     */
    public static MessageCodec standard() {
        return new JacksonMessageCodec(MessageRegistry.withStandardMessages());
    }
}
