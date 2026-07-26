/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package tv.nicdev.craftrelay.common.internal.state;

import java.util.Objects;
import java.util.Optional;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;

/**
 * Immutable result of a player-store mutation.
 *
 * @param status mutation outcome
 * @param previous snapshot before an applied update, when available
 * @param current authoritative snapshot after the operation, when available
 */
public record PlayerMutationResult(
        PlayerMutationStatus status,
        Optional<NetworkPlayer> previous,
        Optional<NetworkPlayer> current) {

    /** Creates an immutable result. */
    public PlayerMutationResult {
        status = Objects.requireNonNull(status, "status");
        previous = Objects.requireNonNull(previous, "previous");
        current = Objects.requireNonNull(current, "current");
    }
}
