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
import tv.nicdev.craftrelay.api.target.NetworkTarget;

final class JacksonMessageCodec implements MessageCodec {

    static final int PROTOCOL_VERSION = 1;
    static final int DEFAULT_MAXIMUM_MESSAGE_SIZE = 1_048_576;

    private static final Set<String> REQUIRED_ENVELOPE_FIELDS =
            Set.of(
                    "messageId",
                    "protocolVersion",
                    "type",
                    "sourceInstance",
                    "target",
                    "createdAt",
                    "payload");
    private static final Set<String> ALLOWED_ENVELOPE_FIELDS =
            Set.of(
                    "messageId",
                    "protocolVersion",
                    "type",
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
                        .build();
        if (maximumMessageSize <= 0) {
            throw new IllegalArgumentException("maximumMessageSize must be positive");
        }
        this.maximumMessageSize = maximumMessageSize;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.messageIdSupplier = Objects.requireNonNull(messageIdSupplier, "messageIdSupplier");
    }

    @Override
    public byte[] encode(
            String sourceInstance,
            NetworkTarget target,
            NetworkMessage message,
            Optional<UUID> correlationId) {
        requireText(sourceInstance, "sourceInstance");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(correlationId, "correlationId");

        String type = registry.typeOf(message);
        try {
            JsonNode payload = mapper.valueToTree(message);
            MessageEnvelope envelope =
                    new MessageEnvelope(
                            Objects.requireNonNull(messageIdSupplier.get(), "messageId"),
                            PROTOCOL_VERSION,
                            type,
                            sourceInstance,
                            target,
                            Instant.now(clock),
                            correlationId,
                            payload);
            byte[] encoded = mapper.writeValueAsBytes(toJson(envelope));
            requireAllowedSize(encoded);
            return encoded;
        } catch (InvalidMessageException exception) {
            throw exception;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidMessageException("message could not be encoded", exception);
        }
    }

    @Override
    public DecodedMessage decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0) {
            throw new ProtocolException("encoded message must not be empty");
        }
        requireAllowedSize(encoded);

        try {
            JsonNode root = mapper.readTree(encoded);
            MessageEnvelope envelope = readEnvelope(root);
            Class<? extends NetworkMessage> messageClass = registry.classFor(envelope.type());
            NetworkMessage message = mapper.treeToValue(envelope.payload(), messageClass);
            return new DecodedMessage(
                    envelope.messageId(),
                    envelope.sourceInstance(),
                    envelope.target(),
                    envelope.createdAt(),
                    envelope.correlationId(),
                    message);
        } catch (InvalidMessageException exception) {
            throw exception;
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            throw new ProtocolException("encoded message is malformed", exception);
        }
    }

    private ObjectNode toJson(MessageEnvelope envelope) {
        ObjectNode root = mapper.createObjectNode();
        root.put("messageId", envelope.messageId().toString());
        root.put("protocolVersion", envelope.protocolVersion());
        root.put("type", envelope.type());
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
