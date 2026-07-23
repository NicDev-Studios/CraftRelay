package tv.nicdev.craftrelay.api;

/**
 * Lifecycle state of the local CraftRelay API implementation.
 */
public enum CraftRelayState {
    /** The implementation is being initialized and cannot serve requests yet. */
    INITIALIZING,
    /** The implementation is ready to serve requests. */
    AVAILABLE,
    /** The implementation is shutting down and no longer accepts new work. */
    STOPPING,
    /** The implementation has stopped permanently. */
    STOPPED
}
