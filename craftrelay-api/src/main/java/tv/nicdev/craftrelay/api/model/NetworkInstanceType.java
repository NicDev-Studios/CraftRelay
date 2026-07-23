package tv.nicdev.craftrelay.api.model;

/**
 * Role of an instance participating in the CraftRelay network.
 */
public enum NetworkInstanceType {
    /** A Velocity proxy or another supported proxy implementation. */
    PROXY,
    /** A Paper server or another supported backend-server implementation. */
    SERVER
}
