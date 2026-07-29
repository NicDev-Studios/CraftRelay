/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package tv.nicdev.craftrelay.common.internal.runtime;

import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.messaging.MessageType;
import tv.nicdev.craftrelay.common.internal.protocol.MessageBindingKey;

/** Internal owner of one exact runtime codec-registration generation. */
public interface RuntimeMessageRegistration<M extends NetworkMessage> extends AutoCloseable {

    /** Returns the registered public type. */
    MessageType<M> type();

    /** Returns the exact local binding generation. */
    MessageBindingKey bindingKey();

    /** Returns whether the generation stopped accepting new work. */
    boolean isClosed();

    @Override
    void close();
}
