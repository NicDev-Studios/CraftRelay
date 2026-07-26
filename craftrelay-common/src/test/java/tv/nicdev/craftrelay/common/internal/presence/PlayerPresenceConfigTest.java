/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package tv.nicdev.craftrelay.common.internal.presence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PlayerPresenceConfigTest {

    @Test
    void providesValidatedDefaultsAndSharedPrefixValidation() {
        PlayerPresenceConfig defaults = PlayerPresenceConfig.defaults();

        assertEquals("craftrelay", defaults.keyPrefix());
        assertEquals(Duration.ofSeconds(5), defaults.refreshInterval());
        assertEquals(Duration.ofSeconds(20), defaults.playerTtl());
        assertEquals(512, defaults.batchSize());
        assertDoesNotThrow(
                () -> defaults.validateCompatible(InstancePresenceConfig.defaults()));
        assertThrows(
                IllegalArgumentException.class,
                () -> defaults.validateCompatible(new InstancePresenceConfig(
                        "other", Duration.ofSeconds(5), Duration.ofSeconds(20), 512)));
    }

    @Test
    void rejectsInvalidRefreshTtlAndBatchSettings() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlayerPresenceConfig(
                        " ", Duration.ofSeconds(1), Duration.ofSeconds(2), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlayerPresenceConfig(
                        "test", Duration.ZERO, Duration.ofSeconds(2), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlayerPresenceConfig(
                        "test", Duration.ofSeconds(2), Duration.ofSeconds(3), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlayerPresenceConfig(
                        "test", Duration.ofSeconds(1), Duration.ofSeconds(2), 0));
    }
}
