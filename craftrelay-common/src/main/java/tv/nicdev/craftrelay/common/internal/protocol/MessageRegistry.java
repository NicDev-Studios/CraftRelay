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
import java.util.concurrent.atomic.AtomicLong;
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
import tv.nicdev.craftrelay.api.messaging.MessagePayloadCodec;
import tv.nicdev.craftrelay.api.messaging.MessageType;

final class MessageRegistry {

    private static final Pattern TYPE_PATTERN =
            Pattern.compile("[a-z0-9]+(?:[._-][a-z0-9]+)*:[a-z0-9]+(?:[._-][a-z0-9]+)*");

    private final AtomicReference<RegistryState> state =
            new AtomicReference<>(new RegistryState(Map.of(), Map.of()));
    private final AtomicLong nextGeneration = new AtomicLong();

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
        registerBinding(new Binding<>(
                key(type, 1), nextBindingKey(type, 1), messageClass, null, false));
    }

    synchronized <M extends NetworkMessage> CodecRegistration<M> registerCustom(
            MessageType<M> type, MessagePayloadCodec<M> codec) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(codec, "codec");
        Binding<M> binding =
                new Binding<>(
                        key(type.identifier(), type.payloadVersion()),
                        nextBindingKey(type.identifier(), type.payloadVersion()),
                        type.messageClass(),
                        codec,
                        true);
        registerBinding(binding);
        return new CodecRegistration<>(
                type, binding, tv.nicdev.craftrelay.api.Subscription.create(() -> remove(binding)));
    }

    boolean isRegistered(MessageType<?> type) {
        Objects.requireNonNull(type, "type");
        Binding<?> binding =
                state.get().bindingsByKey().get(key(type.identifier(), type.payloadVersion()));
        return binding != null && binding.messageClass() == type.messageClass();
    }

    Binding<? extends NetworkMessage> bindingFor(NetworkMessage message) {
        Objects.requireNonNull(message, "message");
        Binding<?> binding = state.get().bindingsByClass().get(message.getClass());
        if (binding == null) {
            throw new InvalidMessageException(
                    "message class is not registered: " + message.getClass().getName());
        }
        return binding;
    }

    Binding<? extends NetworkMessage> bindingFor(String type, int payloadVersion) {
        RegistryKey registryKey;
        try {
            registryKey = key(type, payloadVersion);
        } catch (IllegalArgumentException exception) {
            throw new InvalidMessageException("message type is not registered: " + type, exception);
        }
        Binding<?> binding = state.get().bindingsByKey().get(registryKey);
        if (binding == null) {
            throw new InvalidMessageException(
                    "message type is not registered: " + type + " version " + payloadVersion);
        }
        return binding;
    }

    String typeOf(NetworkMessage message) {
        return bindingFor(message).key().type();
    }

    Class<? extends NetworkMessage> classFor(String type) {
        return bindingFor(type, 1).messageClass();
    }

    Map<RegistryKey, Binding<?>> snapshot() {
        return state.get().bindingsByKey();
    }

    boolean isActive(MessageBindingKey bindingKey) {
        Objects.requireNonNull(bindingKey, "bindingKey");
        Binding<?> binding =
                state.get()
                        .bindingsByKey()
                        .get(key(bindingKey.type(), bindingKey.payloadVersion()));
        return binding != null && binding.bindingKey().equals(bindingKey);
    }

    private void registerBinding(Binding<?> binding) {
        RegistryState current = state.get();
        if (current.bindingsByKey().containsKey(binding.key())) {
            throw new IllegalArgumentException(
                    "message type is already registered: "
                            + binding.key().type()
                            + " version "
                            + binding.key().payloadVersion());
        }
        if (current.bindingsByClass().containsKey(binding.messageClass())) {
            throw new IllegalArgumentException(
                    "message class is already registered: " + binding.messageClass().getName());
        }

        Map<RegistryKey, Binding<?>> byKey = new HashMap<>(current.bindingsByKey());
        Map<Class<? extends NetworkMessage>, Binding<?>> byClass =
                new HashMap<>(current.bindingsByClass());
        byKey.put(binding.key(), binding);
        byClass.put(binding.messageClass(), binding);
        state.set(new RegistryState(Map.copyOf(byKey), Map.copyOf(byClass)));
    }

    private synchronized void remove(Binding<?> expected) {
        RegistryState current = state.get();
        if (current.bindingsByKey().get(expected.key()) != expected
                || current.bindingsByClass().get(expected.messageClass()) != expected) {
            return;
        }
        Map<RegistryKey, Binding<?>> byKey = new HashMap<>(current.bindingsByKey());
        Map<Class<? extends NetworkMessage>, Binding<?>> byClass =
                new HashMap<>(current.bindingsByClass());
        byKey.remove(expected.key());
        byClass.remove(expected.messageClass());
        state.set(new RegistryState(Map.copyOf(byKey), Map.copyOf(byClass)));
    }

    private static RegistryKey key(String type, int payloadVersion) {
        Objects.requireNonNull(type, "type");
        if (!TYPE_PATTERN.matcher(type).matches()) {
            throw new IllegalArgumentException(
                    "type must be a lowercase namespace-qualified identifier");
        }
        if (payloadVersion <= 0) {
            throw new IllegalArgumentException("payloadVersion must be positive");
        }
        return new RegistryKey(type, payloadVersion);
    }

    private MessageBindingKey nextBindingKey(String type, int payloadVersion) {
        return new MessageBindingKey(
                type, payloadVersion, nextGeneration.incrementAndGet());
    }

    record RegistryKey(String type, int payloadVersion) {
    }

    record Binding<M extends NetworkMessage>(
            RegistryKey key,
            MessageBindingKey bindingKey,
            Class<M> messageClass,
            MessagePayloadCodec<M> customCodec,
            boolean custom) {

        Binding {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(bindingKey, "bindingKey");
            Objects.requireNonNull(messageClass, "messageClass");
            if (!bindingKey.type().equals(key.type())
                    || bindingKey.payloadVersion() != key.payloadVersion()) {
                throw new IllegalArgumentException("binding identity does not match registry key");
            }
            if (custom != (customCodec != null)) {
                throw new IllegalArgumentException("custom codec and binding type do not match");
            }
        }
    }

    private record RegistryState(
            Map<RegistryKey, Binding<?>> bindingsByKey,
            Map<Class<? extends NetworkMessage>, Binding<?>> bindingsByClass) {
    }
}
