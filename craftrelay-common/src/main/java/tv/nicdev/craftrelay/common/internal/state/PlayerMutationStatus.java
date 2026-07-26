/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package tv.nicdev.craftrelay.common.internal.state;

/** Outcome of one atomically fenced player-store mutation. */
public enum PlayerMutationStatus {
    /** The mutation was applied, including an idempotent repeat. */
    APPLIED,
    /** Another active session owns the player ID. */
    CONFLICT,
    /** The requested session or its owning node no longer owns the record. */
    OWNERSHIP_LOST
}
