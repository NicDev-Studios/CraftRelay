/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package tv.nicdev.craftrelay.common.internal.presence;

/** Indicates that a different active session already owns a player ID. */
public final class PlayerSessionConflictException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** Creates a conflict failure. */
    public PlayerSessionConflictException(String message) {
        super(message);
    }
}
