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

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.exception.InvalidMessageException;
import tv.nicdev.craftrelay.api.exception.ProtocolException;
import tv.nicdev.craftrelay.api.messaging.MessagePayloadCodec;
import tv.nicdev.craftrelay.api.messaging.MessageType;
import tv.nicdev.craftrelay.api.target.NetworkTarget;

final class JacksonMessageCodec implements MessageCodec {

    static final int PROTOCOL_VERSION = 1;
    static final int DEFAULT_MAXIMUM_MESSAGE_SIZE = 1_048_576;

    private static final Set<String> REQUIRED_ENVELOPE_FIELDS =
            Set.of(
                    "messageId",
                    "protocolVersion",
                    "type",
                    "payloadVersion",
                    "sourceInstance",
                    "target",
                    "createdAt",
                    "payload");
    private static final Set<String> ALLOWED_ENVELOPE_FIELDS =
            Set.of(
                    "messageId",
                    "protocolVersion",
                    "type",
                    "payloadVersion",
                    "sourceInstance",
                    "target",
                    "createdAt",
                    "correlationId",
                    "payload");

    private final MessageRegistry registry;
    private final ObjectMapper mapper;
    private final int maximumMessageSize;
    private final Clock clock;
    private final Supplier<UUID> messageIdSupplier;

    JacksonMessageCodec(MessageRegistry registry) {
        this(
                registry,
                JsonMapper.builder().build(),
                DEFAULT_MAXIMUM_MESSAGE_SIZE,
                Clock.systemUTC(),
                UUID::randomUUID);
    }

    JacksonMessageCodec(
            MessageRegistry registry,
            ObjectMapper mapper,
            int maximumMessageSize,
            Clock clock,
            Supplier<UUID> messageIdSupplier) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.mapper =
                Objects.requireNonNull(mapper, "mapper")
                        .rebuild()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .build();
        if (maximumMessageSize <= 0) {
            throw new IllegalArgumentException("maximumMessageSize must be positive");
        }
        this.maximumMessageSize = maximumMessageSize;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.messageIdSupplier = Objects.requireNonNull(messageIdSupplier, "messageIdSupplier");
    }

    @Override
    public PreparedOutboundMessage prepare(
            String sourceInstance,
            NetworkTarget target,
            NetworkMessage message,
            Optional<UUID> correlationId) {
        requireText(sourceInstance, "sourceInstance");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(correlationId, "correlationId");

        MessageRegistry.Binding<? extends NetworkMessage> binding =
                registry.bindingFor(message);
        return preparedOutbound(
                sourceInstance, target, message, correlationId, binding);
    }

    @Override
    public <M extends NetworkMessage> PreparedOutboundMessage prepare(
            CodecRegistration<M> registration,
            String sourceInstance,
            NetworkTarget target,
            M message,
            Optional<UUID> correlationId) {
        Objects.requireNonNull(registration, "registration");
        requireText(sourceInstance, "sourceInstance");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(correlationId, "correlationId");
        if (message.getClass() != registration.type().messageClass()) {
            throw new InvalidMessageException(
                    "message class does not match registration: "
                            + message.getClass().getName());
        }
        return preparedOutbound(
                sourceInstance,
                target,
                message,
                correlationId,
                registration.binding());
    }

    @Override
    public byte[] encode(PreparedOutboundMessage prepared) {
        Objects.requireNonNull(prepared, "prepared");
        MessageRegistry.Binding<? extends NetworkMessage> binding = prepared.binding();
        try {
            JsonNode payload = encodePayload(binding, prepared.message());
            MessageEnvelope envelope =
                    new MessageEnvelope(
                            prepared.messageId(),
                            PROTOCOL_VERSION,
                            binding.key().type(),
                            binding.key().payloadVersion(),
                            prepared.sourceInstance(),
                            prepared.target(),
                            prepared.createdAt(),
                            prepared.correlationId(),
                            payload);
            byte[] encoded = mapper.writeValueAsBytes(toJson(envelope));
            requireAllowedSize(encoded);
            return encoded;
        } catch (InvalidMessageException exception) {
            throw exception;
        } catch (ProtocolException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidMessageException("message could not be encoded", exception);
        }
    }

    private PreparedOutboundMessage preparedOutbound(
            String sourceInstance,
            NetworkTarget target,
            NetworkMessage message,
            Optional<UUID> correlationId,
            MessageRegistry.Binding<? extends NetworkMessage> binding) {
        return new PreparedOutboundMessage(
                Objects.requireNonNull(messageIdSupplier.get(), "messageId"),
                sourceInstance,
                target,
                Instant.now(clock),
                correlationId,
                message,
                binding);
    }

    @Override
    public PreparedMessage prepare(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0) {
            throw new ProtocolException("encoded message must not be empty");
        }
        requireAllowedSize(encoded);

        try {
            JsonNode root = mapper.readTree(encoded);
            MessageEnvelope envelope = readEnvelope(root);
            MessageRegistry.Binding<? extends NetworkMessage> binding =
                    registry.bindingFor(envelope.type(), envelope.payloadVersion());
            return new PreparedMessage(envelope, binding);
        } catch (InvalidMessageException exception) {
            throw exception;
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            throw new ProtocolException("encoded message is malformed", exception);
        }
    }

    @Override
    public DecodedMessage decode(PreparedMessage prepared) {
        Objects.requireNonNull(prepared, "prepared");
        MessageEnvelope envelope = prepared.envelope();
        try {
            NetworkMessage message = decodePayload(prepared.binding(), envelope.payload());
            return new DecodedMessage(
                    envelope.messageId(),
                    envelope.sourceInstance(),
                    envelope.target(),
                    envelope.createdAt(),
                    envelope.correlationId(),
                    message);
        } catch (ProtocolException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ProtocolException("message payload is malformed", exception);
        }
    }

    @Override
    public <M extends NetworkMessage> CodecRegistration<M> register(
            MessageType<M> type, MessagePayloadCodec<M> payloadCodec) {
        return registry.registerCustom(type, payloadCodec);
    }

    @Override
    public boolean isRegistered(MessageType<?> type) {
        return registry.isRegistered(type);
    }

    @Override
    public boolean isActive(MessageBindingKey bindingKey) {
        return registry.isActive(bindingKey);
    }

    private ObjectNode toJson(MessageEnvelope envelope) {
        ObjectNode root = mapper.createObjectNode();
        root.put("messageId", envelope.messageId().toString());
        root.put("protocolVersion", envelope.protocolVersion());
        root.put("type", envelope.type());
        root.put("payloadVersion", envelope.payloadVersion());
        root.put("sourceInstance", envelope.sourceInstance());
        root.set("target", writeTarget(envelope.target()));
        root.put("createdAt", envelope.createdAt().toString());
        envelope.correlationId()
                .ifPresentOrElse(
                        id -> root.put("correlationId", id.toString()),
                        () -> root.putNull("correlationId"));
        root.set("payload", envelope.payload());
        return root;
    }

    private MessageEnvelope readEnvelope(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new ProtocolException("message envelope must be a JSON object");
        }
        Set<String> fields = Set.copyOf(root.propertyNames());
        if (!fields.containsAll(REQUIRED_ENVELOPE_FIELDS)
                || !ALLOWED_ENVELOPE_FIELDS.containsAll(fields)) {
            throw new ProtocolException("message envelope has missing or unknown fields");
        }

        int protocolVersion = requiredInteger(root, "protocolVersion");
        if (protocolVersion != PROTOCOL_VERSION) {
            throw new ProtocolException("unsupported protocol version: " + protocolVersion);
        }

        int payloadVersion = requiredPositiveInteger(root, "payloadVersion");
        JsonNode correlationNode = root.get("correlationId");
        Optional<UUID> correlationId =
                correlationNode == null || correlationNode.isNull()
                        ? Optional.empty()
                        : Optional.of(parseUuid(requiredString(root, "correlationId"), "correlationId"));
        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new ProtocolException("payload must be a JSON object");
        }

        return new MessageEnvelope(
                parseUuid(requiredString(root, "messageId"), "messageId"),
                protocolVersion,
                requiredString(root, "type"),
                payloadVersion,
                requiredString(root, "sourceInstance"),
                readTarget(root.get("target")),
                parseInstant(requiredString(root, "createdAt"), "createdAt"),
                correlationId,
                payload);
    }

    private ObjectNode writeTarget(NetworkTarget target) {
        ObjectNode node = mapper.createObjectNode();
        switch (target) {
            case NetworkTarget.AllInstances ignored -> node.put("type", "ALL");
            case NetworkTarget.AllProxies ignored -> node.put("type", "ALL_PROXIES");
            case NetworkTarget.AllServers ignored -> node.put("type", "ALL_SERVERS");
            case NetworkTarget.Instance instance -> {
                node.put("type", "INSTANCE");
                node.put("value", instance.id());
            }
            case NetworkTarget.Group group -> {
                node.put("type", "GROUP");
                node.put("value", group.name());
            }
        }
        return node;
    }

    private NetworkTarget readTarget(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new ProtocolException("target must be a JSON object");
        }
        String type = requiredString(node, "type");
        return switch (type) {
            case "ALL" -> requireTargetFields(node, false, new NetworkTarget.AllInstances());
            case "ALL_PROXIES" -> requireTargetFields(node, false, new NetworkTarget.AllProxies());
            case "ALL_SERVERS" -> requireTargetFields(node, false, new NetworkTarget.AllServers());
            case "INSTANCE" ->
                    requireTargetFields(
                            node, true, new NetworkTarget.Instance(requiredString(node, "value")));
            case "GROUP" ->
                    requireTargetFields(
                            node, true, new NetworkTarget.Group(requiredString(node, "value")));
            default -> throw new ProtocolException("unknown target type: " + type);
        };
    }

    private NetworkTarget requireTargetFields(
            JsonNode node, boolean hasValue, NetworkTarget target) {
        Set<String> expected = hasValue ? Set.of("type", "value") : Set.of("type");
        if (!node.propertyNames().equals(expected)) {
            throw new ProtocolException("target has missing or unknown fields");
        }
        return target;
    }

    private String requiredString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new ProtocolException(field + " must be a non-blank string");
        }
        return value.stringValue();
    }

    private int requiredInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isInt()) {
            throw new ProtocolException(field + " must be an integer");
        }
        return value.intValue();
    }

    private int requiredPositiveInteger(JsonNode node, String field) {
        int value = requiredInteger(node, field);
        if (value <= 0) {
            throw new ProtocolException(field + " must be positive");
        }
        return value;
    }

    private JsonNode encodePayload(
            MessageRegistry.Binding<? extends NetworkMessage> binding,
            NetworkMessage message)
            throws JacksonException {
        if (!binding.custom()) {
            return mapper.valueToTree(message);
        }
        return encodeCustomPayload(binding, message);
    }

    private <M extends NetworkMessage> JsonNode encodeCustomPayload(
            MessageRegistry.Binding<M> binding, NetworkMessage message)
            throws JacksonException {
        byte[] encoded =
                Objects.requireNonNull(
                        binding.customCodec().encode(binding.messageClass().cast(message)),
                        "custom codec result");
        if (encoded.length == 0) {
            throw new IllegalArgumentException("custom payload must not be empty");
        }
        requireAllowedSize(encoded);
        JsonNode payload = mapper.readTree(encoded.clone());
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("custom payload must be one JSON object");
        }
        return payload;
    }

    private NetworkMessage decodePayload(
            MessageRegistry.Binding<? extends NetworkMessage> binding,
            JsonNode payload)
            throws JacksonException {
        if (!binding.custom()) {
            return mapper.treeToValue(payload, binding.messageClass());
        }
        return decodeCustomPayload(binding, payload);
    }

    private <M extends NetworkMessage> M decodeCustomPayload(
            MessageRegistry.Binding<M> binding, JsonNode payload)
            throws JacksonException {
        byte[] encoded = mapper.writeValueAsBytes(payload);
        M message =
                Objects.requireNonNull(
                        binding.customCodec().decode(encoded.clone()),
                        "custom codec result");
        if (message.getClass() != binding.messageClass()) {
            throw new ProtocolException(
                    "custom codec returned "
                            + message.getClass().getName()
                            + " instead of "
                            + binding.messageClass().getName());
        }
        return message;
    }

    private UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(field + " must be a UUID", exception);
        }
    }

    private Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new ProtocolException(field + " must be an ISO-8601 instant", exception);
        }
    }

    private void requireAllowedSize(byte[] encoded) {
        if (encoded.length > maximumMessageSize) {
            throw new ProtocolException(
                    "encoded message exceeds maximum size of " + maximumMessageSize + " bytes");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
