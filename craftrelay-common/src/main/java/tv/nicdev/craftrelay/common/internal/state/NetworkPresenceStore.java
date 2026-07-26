/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package tv.nicdev.craftrelay.common.internal.state;

/** One shared authoritative state connection for instance and player presence. */
public interface NetworkPresenceStore extends NetworkInstanceStore, NetworkPlayerStore {
}
