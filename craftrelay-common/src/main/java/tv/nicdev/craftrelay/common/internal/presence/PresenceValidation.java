/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package tv.nicdev.craftrelay.common.internal.presence;

import java.time.Duration;
import java.util.Objects;

final class PresenceValidation {

    private PresenceValidation() {
    }

    static String requirePrefix(String value) {
        Objects.requireNonNull(value, "keyPrefix");
        if (value.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        return value;
    }

    static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    static void requireLeaseRatio(
            Duration refreshInterval, String intervalName, Duration ttl, String ttlName) {
        if (ttl.compareTo(refreshInterval.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException(
                    ttlName + " must be at least twice " + intervalName);
        }
    }

    static int requirePositiveBatch(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
