/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package tv.nicdev.craftrelay.common.internal.presence;

import java.util.Objects;
import java.util.UUID;

/** Opaque ownership identity shared by all presence records of one node run. */
public record NodeLease(String token) {

    /** Creates a validated lease token. */
    public NodeLease {
        Objects.requireNonNull(token, "token");
        if (token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
    }

    /** Creates a cryptographically unpredictable UUID-based lease token. */
    public static NodeLease create() {
        return new NodeLease(UUID.randomUUID().toString());
    }
}
