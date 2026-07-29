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

import java.util.Objects;

/**
 * Validated envelope awaiting payload decoding on its isolated message-type lane.
 */
public final class PreparedMessage {

    private final MessageEnvelope envelope;
    private final MessageRegistry.Binding<?> binding;

    PreparedMessage(MessageEnvelope envelope, MessageRegistry.Binding<?> binding) {
        this.envelope = Objects.requireNonNull(envelope, "envelope");
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    /**
     * Returns the stable lane key for this message type and payload version.
     *
     * @return dispatch key
     */
    public MessageBindingKey bindingKey() {
        return binding.bindingKey();
    }

    MessageEnvelope envelope() {
        return envelope;
    }

    MessageRegistry.Binding<?> binding() {
        return binding;
    }
}
