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
import java.util.regex.Pattern;
import tv.nicdev.craftrelay.api.NetworkMessage;

/**
 * Stable wire identity for one version of a custom message.
 *
 * @param namespace lowercase plugin namespace
 * @param name lowercase message name
 * @param payloadVersion positive payload-schema version
 * @param messageClass exact immutable Java message class
 * @param <M> message type
 */
public record MessageType<M extends NetworkMessage>(
        String namespace, String name, int payloadVersion, Class<M> messageClass) {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[a-z0-9]+(?:[._-][a-z0-9]+)*");

    /** Validates and creates a custom message type. */
    public MessageType {
        namespace = requireIdentifier(namespace, "namespace");
        if (namespace.equals("craftrelay")) {
            throw new IllegalArgumentException("namespace 'craftrelay' is reserved");
        }
        name = requireIdentifier(name, "name");
        if (payloadVersion <= 0) {
            throw new IllegalArgumentException("payloadVersion must be positive");
        }
        messageClass = Objects.requireNonNull(messageClass, "messageClass");
    }

    /**
     * Creates a validated message type.
     *
     * @param namespace lowercase plugin namespace
     * @param name lowercase message name
     * @param payloadVersion positive payload-schema version
     * @param messageClass exact message class
     * @param <M> message type
     * @return validated type
     */
    public static <M extends NetworkMessage> MessageType<M> of(
            String namespace, String name, int payloadVersion, Class<M> messageClass) {
        return new MessageType<>(namespace, name, payloadVersion, messageClass);
    }

    /**
     * Returns the stable namespace-qualified wire identifier.
     *
     * @return identifier such as {@code myplugin:warp_request}
     */
    public String identifier() {
        return namespace + ":" + name;
    }

    private static String requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    name + " must be a lowercase identifier");
        }
        return value;
    }
}
