package tv.nicdev.craftrelay.common.transport;

/**
 * Lifecycle state of a network transport.
 */
public enum TransportState {
    NEW,
    CONNECTING,
    CONNECTED,
    CLOSING,
    CLOSED
}
