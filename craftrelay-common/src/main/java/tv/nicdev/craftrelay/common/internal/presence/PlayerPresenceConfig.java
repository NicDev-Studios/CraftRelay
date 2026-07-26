/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package tv.nicdev.craftrelay.common.internal.presence;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable settings for distributed player presence.
 *
 * @param keyPrefix Redis key namespace shared with instance presence
 * @param refreshInterval delay between completed refresh attempts
 * @param playerTtl player-session lease lifetime
 * @param batchSize maximum sessions processed by one store operation
 */
public record PlayerPresenceConfig(
        String keyPrefix, Duration refreshInterval, Duration playerTtl, int batchSize) {

    /** Creates validated player-presence settings. */
    public PlayerPresenceConfig {
        keyPrefix = PresenceValidation.requirePrefix(keyPrefix);
        refreshInterval =
                PresenceValidation.requirePositive(refreshInterval, "refreshInterval");
        playerTtl = PresenceValidation.requirePositive(playerTtl, "playerTtl");
        PresenceValidation.requireLeaseRatio(
                refreshInterval, "refreshInterval", playerTtl, "playerTtl");
        batchSize = PresenceValidation.requirePositiveBatch(batchSize, "batchSize");
    }

    /** Returns defaults: {@code craftrelay}, 5 seconds, 20 seconds, and 512 sessions. */
    public static PlayerPresenceConfig defaults() {
        return new PlayerPresenceConfig(
                "craftrelay", Duration.ofSeconds(5), Duration.ofSeconds(20), 512);
    }

    /** Verifies that instance and player presence share one Redis namespace. */
    public void validateCompatible(InstancePresenceConfig instanceConfig) {
        Objects.requireNonNull(instanceConfig, "instanceConfig");
        if (!keyPrefix.equals(instanceConfig.keyPrefix())) {
            throw new IllegalArgumentException(
                    "Instance and player presence key prefixes must match");
        }
    }
}
