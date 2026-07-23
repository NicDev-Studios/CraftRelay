package tv.nicdev.craftrelay.api.target;

/**
 * Factory for the standard CraftRelay network targets.
 */
public final class NetworkTargets {

    private static final NetworkTarget ALL_INSTANCES = new NetworkTarget.AllInstances();
    private static final NetworkTarget ALL_PROXIES = new NetworkTarget.AllProxies();
    private static final NetworkTarget ALL_SERVERS = new NetworkTarget.AllServers();

    private NetworkTargets() {
    }

    /**
     * Targets all participating instances.
     *
     * @return shared all-instances target
     */
    public static NetworkTarget allInstances() {
        return ALL_INSTANCES;
    }

    /**
     * Targets all proxy instances.
     *
     * @return shared all-proxies target
     */
    public static NetworkTarget allProxies() {
        return ALL_PROXIES;
    }

    /**
     * Targets all backend-server instances.
     *
     * @return shared all-servers target
     */
    public static NetworkTarget allServers() {
        return ALL_SERVERS;
    }

    /**
     * Targets one instance.
     *
     * @param id network-unique instance ID
     * @return validated instance target
     */
    public static NetworkTarget instance(String id) {
        return new NetworkTarget.Instance(id);
    }

    /**
     * Targets all instances in a routing group.
     *
     * @param name routing-group name
     * @return validated group target
     */
    public static NetworkTarget group(String name) {
        return new NetworkTarget.Group(name);
    }
}
