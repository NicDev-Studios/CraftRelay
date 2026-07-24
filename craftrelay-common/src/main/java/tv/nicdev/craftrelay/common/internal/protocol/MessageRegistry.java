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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.exception.InvalidMessageException;
import tv.nicdev.craftrelay.api.message.GlobalBroadcastMessage;
import tv.nicdev.craftrelay.api.message.InstanceHeartbeatMessage;
import tv.nicdev.craftrelay.api.message.InstanceStartedMessage;
import tv.nicdev.craftrelay.api.message.InstanceStoppedMessage;
import tv.nicdev.craftrelay.api.message.PlayerConnectRequest;
import tv.nicdev.craftrelay.api.message.PlayerConnectedMessage;
import tv.nicdev.craftrelay.api.message.PlayerDisconnectedMessage;
import tv.nicdev.craftrelay.api.message.PlayerLocationRequest;
import tv.nicdev.craftrelay.api.message.PlayerLocationResponse;
import tv.nicdev.craftrelay.api.message.PlayerServerSwitchMessage;

final class MessageRegistry {

    private static final Pattern TYPE_PATTERN =
            Pattern.compile("craftrelay:[a-z0-9]+(?:[._-][a-z0-9]+)*");

    private final AtomicReference<RegistryState> state =
            new AtomicReference<>(new RegistryState(Map.of(), Map.of()));

    static MessageRegistry withStandardMessages() {
        MessageRegistry registry = new MessageRegistry();
        registry.register("craftrelay:instance_started", InstanceStartedMessage.class);
        registry.register("craftrelay:instance_stopped", InstanceStoppedMessage.class);
        registry.register("craftrelay:instance_heartbeat", InstanceHeartbeatMessage.class);
        registry.register("craftrelay:player_connected", PlayerConnectedMessage.class);
        registry.register("craftrelay:player_disconnected", PlayerDisconnectedMessage.class);
        registry.register("craftrelay:player_server_switch", PlayerServerSwitchMessage.class);
        registry.register("craftrelay:player_location_request", PlayerLocationRequest.class);
        registry.register("craftrelay:player_location_response", PlayerLocationResponse.class);
        registry.register("craftrelay:player_connect_request", PlayerConnectRequest.class);
        registry.register("craftrelay:global_broadcast", GlobalBroadcastMessage.class);
        return registry;
    }

    synchronized void register(String type, Class<? extends NetworkMessage> messageClass) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(messageClass, "messageClass");
        if (!TYPE_PATTERN.matcher(type).matches()) {
            throw new IllegalArgumentException(
                    "type must be a lowercase craftrelay-prefixed identifier");
        }
        RegistryState current = state.get();
        if (current.classesByType().containsKey(type)) {
            throw new IllegalArgumentException("message type is already registered: " + type);
        }
        if (current.typesByClass().containsKey(messageClass)) {
            throw new IllegalArgumentException(
                    "message class is already registered: " + messageClass.getName());
        }

        Map<String, Class<? extends NetworkMessage>> classesByType =
                new HashMap<>(current.classesByType());
        Map<Class<? extends NetworkMessage>, String> typesByClass =
                new HashMap<>(current.typesByClass());
        classesByType.put(type, messageClass);
        typesByClass.put(messageClass, type);
        state.set(new RegistryState(Map.copyOf(classesByType), Map.copyOf(typesByClass)));
    }

    String typeOf(NetworkMessage message) {
        Objects.requireNonNull(message, "message");
        String type = state.get().typesByClass().get(message.getClass());
        if (type == null) {
            throw new InvalidMessageException(
                    "message class is not registered: " + message.getClass().getName());
        }
        return type;
    }

    Class<? extends NetworkMessage> classFor(String type) {
        Objects.requireNonNull(type, "type");
        Class<? extends NetworkMessage> messageClass = state.get().classesByType().get(type);
        if (messageClass == null) {
            throw new InvalidMessageException("message type is not registered: " + type);
        }
        return messageClass;
    }

    Map<String, Class<? extends NetworkMessage>> snapshot() {
        return state.get().classesByType();
    }

    private record RegistryState(
            Map<String, Class<? extends NetworkMessage>> classesByType,
            Map<Class<? extends NetworkMessage>, String> typesByClass) {
    }
}
