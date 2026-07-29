/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package tv.nicdev.craftrelay.common.internal.protocol;

import java.util.Objects;

/**
 * Immutable identity of one concrete codec-registration generation.
 *
 * <p>The generation is local to one codec instance and never appears on the wire.
 */
public record MessageBindingKey(String type, int payloadVersion, long generation) {

    /** Validates one binding identity. */
    public MessageBindingKey {
        Objects.requireNonNull(type, "type");
        if (payloadVersion <= 0) {
            throw new IllegalArgumentException("payloadVersion must be positive");
        }
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
    }

    /** Returns a stable diagnostic label for this generation. */
    public String diagnosticName() {
        return type + '@' + payloadVersion + '#' + generation;
    }
}
