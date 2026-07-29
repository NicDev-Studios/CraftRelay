/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package tv.nicdev.craftrelay.common.internal.protocol;

import java.util.Objects;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.messaging.MessageType;

/** Internal owner of one exact custom-codec binding. */
public final class CodecRegistration<M extends NetworkMessage> implements AutoCloseable {

    private final MessageType<M> type;
    private final MessageRegistry.Binding<M> binding;
    private final Subscription removal;

    CodecRegistration(
            MessageType<M> type,
            MessageRegistry.Binding<M> binding,
            Subscription removal) {
        this.type = Objects.requireNonNull(type, "type");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.removal = Objects.requireNonNull(removal, "removal");
    }

    /** Returns the registered public type. */
    public MessageType<M> type() {
        return type;
    }

    /** Returns the exact local binding generation. */
    public MessageBindingKey bindingKey() {
        return binding.bindingKey();
    }

    /** Returns whether the binding has been removed from the active registry. */
    public boolean isClosed() {
        return removal.isClosed();
    }

    @Override
    public void close() {
        removal.close();
    }

    MessageRegistry.Binding<M> binding() {
        return binding;
    }
}
