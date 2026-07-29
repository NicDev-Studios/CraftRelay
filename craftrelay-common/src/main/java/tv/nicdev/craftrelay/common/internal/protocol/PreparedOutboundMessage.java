/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package tv.nicdev.craftrelay.common.internal.protocol;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.target.NetworkTarget;

/**
 * Immutable outbound metadata and exact codec snapshot awaiting isolated encoding.
 */
public final class PreparedOutboundMessage {

    private final UUID messageId;
    private final String sourceInstance;
    private final NetworkTarget target;
    private final Instant createdAt;
    private final Optional<UUID> correlationId;
    private final NetworkMessage message;
    private final MessageRegistry.Binding<?> binding;

    PreparedOutboundMessage(
            UUID messageId,
            String sourceInstance,
            NetworkTarget target,
            Instant createdAt,
            Optional<UUID> correlationId,
            NetworkMessage message,
            MessageRegistry.Binding<?> binding) {
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.sourceInstance = Objects.requireNonNull(sourceInstance, "sourceInstance");
        this.target = Objects.requireNonNull(target, "target");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.message = Objects.requireNonNull(message, "message");
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    /** Returns the exact local binding generation used by this publish. */
    public MessageBindingKey bindingKey() {
        return binding.bindingKey();
    }

    UUID messageId() {
        return messageId;
    }

    String sourceInstance() {
        return sourceInstance;
    }

    NetworkTarget target() {
        return target;
    }

    Instant createdAt() {
        return createdAt;
    }

    Optional<UUID> correlationId() {
        return correlationId;
    }

    NetworkMessage message() {
        return message;
    }

    MessageRegistry.Binding<?> binding() {
        return binding;
    }
}
